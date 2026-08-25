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
 * Amazon Inspector2 restJson1: account/region scan-type enablement and findings filters.
 *
 * <p>Emulates {@code BatchGetAccountStatus}, {@code Enable}, and {@code Disable} so Alchemy's
 * Inspector2 Enabler can converge locally. Scan types are {@code ENABLED} or {@code DISABLED}
 * immediately — Inspector's real transitional {@code ENABLING}/{@code DISABLING} states are
 * skipped so local stacks do not wait on eventual consistency. Findings filters work
 * regardless of enablement; CIS scan-configuration APIs reject a disabled account with
 * {@code AccessDeniedException}. Empty {@code accountIds} defaults to the caller.
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
