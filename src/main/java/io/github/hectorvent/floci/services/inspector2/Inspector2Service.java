package io.github.hectorvent.floci.services.inspector2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.inspector2.model.AccountStatus;
import io.github.hectorvent.floci.services.inspector2.model.Inspector2Account;
import io.github.hectorvent.floci.services.inspector2.model.Inspector2Filter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon Inspector2 restJson1: account/region scan-type enablement, findings filters,
 * and the read-only bindings Alchemy's Inspector2 fixture drives.
 *
 * <p>Emulates {@code BatchGetAccountStatus}, {@code Enable}, and {@code Disable} so Alchemy's
 * Inspector2 Enabler can converge locally. Scan types are {@code ENABLED} or {@code DISABLED}
 * immediately — Inspector's real transitional {@code ENABLING}/{@code DISABLING} states are
 * skipped so local stacks do not wait on eventual consistency. Findings filters work
 * regardless of enablement; CIS scan-configuration APIs reject a disabled account with
 * {@code AccessDeniedException}. Empty {@code accountIds} defaults to the caller.
 *
 * <p>List/get bindings return empty collections or default configuration. The public
 * vulnerability catalog resolves log4shell. Missing encryption keys, findings reports,
 * and delegated administrators surface typed {@code ResourceNotFoundException}.
 */
@ApplicationScoped
public class Inspector2Service implements Resettable, TagHandler {

    static final String SERVICE = "inspector2"; // restJson1 findings-filter + enablement
    static final String STATUS_ENABLED = "ENABLED";
    static final String STATUS_DISABLED = "DISABLED";
    static final List<String> RESOURCE_TYPES = List.of(
            "EC2", "ECR", "LAMBDA", "LAMBDA_CODE", "CODE_REPOSITORY");
    private static final Set<String> RESOURCE_TYPE_SET = Set.copyOf(RESOURCE_TYPES);
    private static final Set<String> FILTER_ACTIONS = Set.of("NONE", "SUPPRESS");
    private static final Set<String> SCAN_TYPES = Set.of("NETWORK", "PACKAGE", "CODE");
    private static final Set<String> ENCRYPTION_RESOURCE_TYPES = Set.of(
            "AWS_EC2_INSTANCE", "AWS_ECR_CONTAINER_IMAGE", "AWS_ECR_REPOSITORY", "AWS_LAMBDA_FUNCTION");
    private static final List<String> FREE_TRIAL_TYPES = List.of("EC2", "ECR", "LAMBDA", "LAMBDA_CODE");
    private static final Map<String, Map<String, Object>> VULNERABILITIES = vulnerabilities();
    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StorageBackend<String, AccountStatus> store;
    private final StorageBackend<String, Inspector2Filter> filters;
    private final RegionResolver regionResolver;

    @Inject
    public Inspector2Service(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create(
                        "inspector2",
                        "inspector2-accounts.json",
                        new TypeReference<Map<String, AccountStatus>>() {
                        }),
                storageFactory.create(
                        "inspector2",
                        "inspector2-filters.json",
                        new TypeReference<Map<String, Inspector2Filter>>() {
                        }),
                regionResolver);
    }

    Inspector2Service(StorageBackend<String, AccountStatus> store, RegionResolver regionResolver) {
        this(store, null, regionResolver);
    }

    Inspector2Service(
            StorageBackend<String, AccountStatus> store,
            StorageBackend<String, Inspector2Filter> filters,
            RegionResolver regionResolver) {
        this.store = store;
        this.filters = filters;
        this.regionResolver = regionResolver;
    }

    public List<AccountStatus> batchGetAccountStatus(String region, JsonNode request) {
        requireObject(request, "Request body");
        List<String> accountIds = readAccountIds(request, false);
        if (accountIds.isEmpty()) {
            accountIds = List.of(regionResolver.getAccountId());
        }
        List<AccountStatus> accounts = new ArrayList<>();
        for (String accountId : accountIds) {
            accounts.add(statusFor(region, accountId));
        }
        return accounts;
    }

    public synchronized List<AccountStatus> enable(String region, JsonNode request) {
        requireObject(request, "Request body");
        List<String> types = readResourceTypes(request, true);
        List<String> accountIds = requestedAccounts(request);
        List<AccountStatus> accounts = new ArrayList<>();
        for (String accountId : accountIds) {
            AccountStatus status = statusFor(region, accountId);
            if (isCaller(accountId)) {
                for (String type : types) {
                    status.getResourceStatus().put(type, STATUS_ENABLED);
                }
                persist(region, status);
            }
            accounts.add(status);
        }
        return accounts;
    }

    public synchronized List<AccountStatus> disable(String region, JsonNode request) {
        requireObject(request, "Request body");
        List<String> types = readResourceTypes(request, false);
        if (types.isEmpty()) {
            types = RESOURCE_TYPES;
        }
        List<String> accountIds = requestedAccounts(request);
        List<AccountStatus> accounts = new ArrayList<>();
        for (String accountId : accountIds) {
            AccountStatus status = statusFor(region, accountId);
            if (isCaller(accountId)) {
                for (String type : types) {
                    status.getResourceStatus().put(type, STATUS_DISABLED);
                }
                persist(region, status);
            }
            accounts.add(status);
        }
        return accounts;
    }

    static String overallStatus(AccountStatus status) {
        for (String type : RESOURCE_TYPES) {
            if (STATUS_ENABLED.equals(status.getResourceStatus().get(type))) {
                return STATUS_ENABLED;
            }
        }
        return STATUS_DISABLED;
    }

    static String jsonKey(String resourceType) {
        return switch (resourceType) {
            case "EC2" -> "ec2";
            case "ECR" -> "ecr";
            case "LAMBDA" -> "lambda";
            case "LAMBDA_CODE" -> "lambdaCode";
            case "CODE_REPOSITORY" -> "codeRepository";
            default -> resourceType;
        };
    }

    private AccountStatus statusFor(String region, String accountId) {
        if (isCaller(accountId)) {
            return store.get(region).map(this::copyOf).orElseGet(() -> disabled(accountId));
        }
        return disabled(accountId);
    }

    private void persist(String region, AccountStatus status) {
        store.put(region, copyOf(status));
    }

    private boolean isCaller(String accountId) {
        return regionResolver.getAccountId().equals(accountId);
    }

    private List<String> requestedAccounts(JsonNode request) {
        List<String> accountIds = readAccountIds(request, false);
        if (accountIds.isEmpty()) {
            return List.of(regionResolver.getAccountId());
        }
        return accountIds;
    }

    private static AccountStatus disabled(String accountId) {
        AccountStatus status = new AccountStatus();
        status.setAccountId(accountId);
        Map<String, String> resources = new LinkedHashMap<>();
        for (String type : RESOURCE_TYPES) {
            resources.put(type, STATUS_DISABLED);
        }
        status.setResourceStatus(resources);
        return status;
    }

    private AccountStatus copyOf(AccountStatus source) {
        AccountStatus copy = new AccountStatus();
        copy.setAccountId(source.getAccountId());
        copy.setResourceStatus(new LinkedHashMap<>(source.getResourceStatus()));
        for (String type : RESOURCE_TYPES) {
            copy.getResourceStatus().putIfAbsent(type, STATUS_DISABLED);
        }
        return copy;
    }

    private static List<String> readAccountIds(JsonNode request, boolean required) {
        List<String> accountIds = readStringList(request, "accountIds", required);
        for (String accountId : accountIds) {
            if (!ACCOUNT_ID.matcher(accountId).matches()) {
                throw validation("accountIds members must be 12-digit account IDs.", "fieldValidationFailed");
            }
        }
        return accountIds;
    }

    private static List<String> readResourceTypes(JsonNode request, boolean required) {
        List<String> types = readStringList(request, "resourceTypes", required);
        for (String type : types) {
            if (!RESOURCE_TYPE_SET.contains(type)) {
                throw validation("resourceTypes contains an invalid scan type: " + type + ".",
                        "fieldValidationFailed");
            }
        }
        return types;
    }

    private static List<String> readStringList(JsonNode request, String field, boolean required) {
        if (!request.has(field) || request.get(field).isNull()) {
            if (required) {
                throw validation(field + " is required.", "fieldValidationFailed");
            }
            return List.of();
        }
        JsonNode node = request.get(field);
        if (!node.isArray()) {
            throw validation(field + " must be an array.", "fieldValidationFailed");
        }
        if (required && node.isEmpty()) {
            throw validation(field + " must contain at least one value.", "fieldValidationFailed");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw validation(field + " members must be strings.", "fieldValidationFailed");
            }
            values.add(value.asText());
        }
        return values;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.", "fieldValidationFailed");
        }
    }

    static AwsException validation(String message, String reason) {
        return new AwsException("ValidationException", message, 400, Map.of("reason", reason));
    }

    public void requireCisApis(String region) {
        AccountStatus status = statusFor(region, regionResolver.getAccountId());
        if (!STATUS_ENABLED.equals(overallStatus(status))) {
            throw new AwsException(
                    "AccessDeniedException",
                    "Invoking account is not enabled.",
                    403);
        }
    }

    public void requireBody(JsonNode request) {
        requireObject(request, "Request body");
    }

    public List<Map<String, Object>> searchVulnerabilities(JsonNode request) {
        requireObject(request, "Request body");
        JsonNode criteria = request.get("filterCriteria");
        if (criteria == null || criteria.isNull()) {
            throw validation("filterCriteria is a required parameter.", "fieldValidationFailed");
        }
        if (!criteria.isObject()) {
            throw validation("filterCriteria must be a JSON object.", "fieldValidationFailed");
        }
        if (!criteria.has("vulnerabilityIds") || criteria.get("vulnerabilityIds").isNull()) {
            throw validation("filterCriteria.vulnerabilityIds is a required parameter.", "fieldValidationFailed");
        }
        JsonNode ids = criteria.get("vulnerabilityIds");
        if (!ids.isArray()) {
            throw validation("filterCriteria.vulnerabilityIds must be an array.", "fieldValidationFailed");
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        for (JsonNode idNode : ids) {
            if (idNode == null || !idNode.isTextual() || idNode.asText().isBlank()) {
                throw validation("filterCriteria.vulnerabilityIds members must be strings.", "fieldValidationFailed");
            }
            Map<String, Object> vulnerability = VULNERABILITIES.get(idNode.asText());
            if (vulnerability != null) {
                matches.add(vulnerability);
            }
        }
        return matches;
    }

    public List<String> freeTrialAccountIds(JsonNode request) {
        requireObject(request, "Request body");
        return readAccountIds(request, true);
    }

    public List<Map<String, Object>> freeTrialInfo() {
        long start = Instant.now().getEpochSecond() - 15 * 24 * 60 * 60;
        long end = start + 15 * 24 * 60 * 60;
        List<Map<String, Object>> info = new ArrayList<>();
        for (String type : FREE_TRIAL_TYPES) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", type);
            entry.put("start", start);
            entry.put("end", end);
            entry.put("status", "INACTIVE");
            info.add(entry);
        }
        return info;
    }

    public Inspector2Account configuration() {
        return Inspector2Account.defaults();
    }

    public String getEncryptionKey(String scanType, String resourceType) {
        if (scanType == null || scanType.isBlank()) {
            throw validation("scanType is a required parameter.", "fieldValidationFailed");
        }
        if (resourceType == null || resourceType.isBlank()) {
            throw validation("resourceType is a required parameter.", "fieldValidationFailed");
        }
        if (!SCAN_TYPES.contains(scanType)) {
            throw validation("scanType contains an invalid value: " + scanType + ".", "fieldValidationFailed");
        }
        if (!ENCRYPTION_RESOURCE_TYPES.contains(resourceType)) {
            throw validation(
                    "resourceType contains an invalid value: " + resourceType + ".", "fieldValidationFailed");
        }
        String key = configuration().getEncryptionKeys().get(scanType + ":" + resourceType);
        if (key == null || key.isBlank()) {
            throw notFound("The requested encryption key was not found.");
        }
        return key;
    }

    public void requireDelegatedAdminAccount() {
        String accountId = configuration().getDelegatedAdminAccountId();
        if (accountId == null || accountId.isBlank()) {
            throw notFound("The requested delegated administrator was not found.");
        }
    }

    public void requireFindingsReport(JsonNode request) {
        requireObject(request, "Request body");
        requireText(request, "reportId");
        throw notFound("The specified findings report was not found.");
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static Map<String, Map<String, Object>> vulnerabilities() {
        Map<String, Object> log4shellCvss = new LinkedHashMap<>();
        log4shellCvss.put("baseScore", 10.0);
        log4shellCvss.put("scoringVector", "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H");
        Map<String, Object> log4shell = new LinkedHashMap<>();
        log4shell.put("id", "CVE-2021-44228");
        log4shell.put("source", "NVD");
        log4shell.put("vendorSeverity", "CRITICAL");
        log4shell.put(
                "description",
                "Apache Log4j2 JNDI features do not protect against attacker controlled LDAP and other JNDI related endpoints.");
        log4shell.put("cvss3", log4shellCvss);
        Map<String, Object> followUp = new LinkedHashMap<>();
        followUp.put("id", "CVE-2021-45046");
        followUp.put("source", "NVD");
        followUp.put("vendorSeverity", "CRITICAL");
        followUp.put(
                "description",
                "Apache Log4j2 Thread Context Lookup Pattern is vulnerable to denial of service and remote code execution.");
        Map<String, Map<String, Object>> catalog = new LinkedHashMap<>();
        catalog.put("CVE-2021-44228", Map.copyOf(log4shell));
        catalog.put("CVE-2021-45046", Map.copyOf(followUp));
        return Map.copyOf(catalog);
    }

    public List<Inspector2Filter> listFilters(JsonNode request) {
        requireFiltersStore();
        requireObject(request, "Request body");
        List<Inspector2Filter> matches = new ArrayList<>(filters.values());
        if (request.has("arns") && request.get("arns").isArray() && !request.get("arns").isEmpty()) {
            List<String> arns = new ArrayList<>();
            for (JsonNode node : request.get("arns")) {
                if (node != null && node.isTextual() && !node.asText().isBlank()) {
                    arns.add(node.asText());
                }
            }
            matches.removeIf(filter -> !arns.contains(filter.getArn()));
        }
        if (request.hasNonNull("action")) {
            String action = request.get("action").asText();
            matches.removeIf(filter -> !action.equals(filter.getAction()));
        }
        return matches;
    }

    public synchronized Inspector2Filter createFilter(String region, JsonNode request) {
        requireFiltersStore();
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        if (findByName(name) != null) {
            throw new AwsException(
                    "BadRequestException",
                    "A filter with the specified name already exists.",
                    400);
        }
        String action = requireAction(textOrNull(request, "action"));
        Map<String, Object> criteria = readCriteria(request.get("filterCriteria"), true);
        long now = Instant.now().getEpochSecond();
        String account = regionResolver.getAccountId();
        Inspector2Filter filter = new Inspector2Filter();
        filter.setArn(filterArn(region, account, UUID.randomUUID().toString()));
        filter.setOwnerId(account);
        filter.setName(name);
        filter.setAction(action);
        filter.setDescription(textOrNull(request, "description"));
        filter.setReason(textOrNull(request, "reason"));
        filter.setRegion(region);
        filter.setCreatedAt(now);
        filter.setUpdatedAt(now);
        filter.setCriteria(criteria);
        filter.setTags(readTags(request.get("tags")));
        filters.put(filter.getArn(), filter);
        return filter;
    }

    public synchronized Inspector2Filter updateFilter(JsonNode request) {
        requireFiltersStore();
        requireObject(request, "Request body");
        Inspector2Filter filter = requireFilter(requireText(request, "filterArn"));
        String name = textOrNull(request, "name");
        if (name != null && !name.equals(filter.getName())) {
            Inspector2Filter existing = findByName(name);
            if (existing != null && !existing.getArn().equals(filter.getArn())) {
                throw new AwsException(
                        "BadRequestException",
                        "A filter with the specified name already exists.",
                        400);
            }
            filter.setName(name);
        }
        if (request.hasNonNull("action")) {
            filter.setAction(requireAction(request.get("action").asText()));
        }
        if (request.has("filterCriteria") && !request.get("filterCriteria").isNull()) {
            filter.setCriteria(readCriteria(request.get("filterCriteria"), false));
        }
        if (request.has("description")) {
            filter.setDescription(textOrNull(request, "description"));
        }
        if (request.has("reason")) {
            filter.setReason(textOrNull(request, "reason"));
        }
        filter.setUpdatedAt(Instant.now().getEpochSecond());
        filters.put(filter.getArn(), filter);
        return filter;
    }

    public synchronized Inspector2Filter deleteFilter(JsonNode request) {
        requireFiltersStore();
        requireObject(request, "Request body");
        Inspector2Filter filter = requireFilter(requireText(request, "arn"));
        filters.delete(filter.getArn());
        return filter;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireFilter(arn).getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Inspector2Filter filter = requireFilter(arn);
        if (tags != null) {
            filter.getTags().putAll(tags);
        }
        filter.setUpdatedAt(Instant.now().getEpochSecond());
        filters.put(filter.getArn(), filter);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Inspector2Filter filter = requireFilter(arn);
        if (tagKeys != null) {
            tagKeys.forEach(filter.getTags()::remove);
        }
        filter.setUpdatedAt(Instant.now().getEpochSecond());
        filters.put(filter.getArn(), filter);
    }

    @Override
    public void clear() {
        store.clear();
        if (filters != null) {
            filters.clear();
        }
    }

    private void requireFiltersStore() {
        if (filters == null) {
            throw new IllegalStateException("Inspector2 filter store is not configured");
        }
    }

    private Inspector2Filter findByName(String name) {
        for (Inspector2Filter filter : filters.values()) {
            if (name.equals(filter.getName())) {
                return filter;
            }
        }
        return null;
    }

    private Inspector2Filter requireFilter(String arn) {
        requireFiltersStore();
        if (arn == null || arn.isBlank()) {
            throw validation("arn is a required parameter.", "fieldValidationFailed");
        }
        return filters.get(arn).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException",
                "The specified filter was not found.",
                404));
    }

    private static String filterArn(String region, String account, String id) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "owner/" + account + "/filter/" + id).toString();
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw validation(field + " is a required parameter.", "fieldValidationFailed");
        }
        return value;
    }

    private static String textOrNull(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            return null;
        }
        String value = request.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String requireAction(String action) {
        if (action == null) {
            throw validation("action is a required parameter.", "fieldValidationFailed");
        }
        if (!FILTER_ACTIONS.contains(action)) {
            throw validation("action must be NONE or SUPPRESS.", "fieldValidationFailed");
        }
        return action;
    }

    private static Map<String, Object> readCriteria(JsonNode node, boolean required) {
        if (node == null || node.isNull()) {
            if (required) {
                throw validation("filterCriteria is a required parameter.", "fieldValidationFailed");
            }
            return new LinkedHashMap<>();
        }
        if (!node.isObject()) {
            throw validation("filterCriteria must be a JSON object.", "fieldValidationFailed");
        }
        return MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {
        });
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull() || !node.isObject()) {
            return tags;
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && !entry.getValue().isNull()) {
                tags.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return tags;
    }
}
