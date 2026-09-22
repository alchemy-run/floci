package io.github.hectorvent.floci.services.inspector2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.inspector2.model.InspectorFilter;
import io.github.hectorvent.floci.services.inspector2.model.InspectorState;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class Inspector2Service implements Resettable {
    private static final Set<String> RESOURCE_TYPES = Set.of(
            "EC2", "ECR", "LAMBDA", "LAMBDA_CODE", "CODE_REPOSITORY");

    private final AccountAwareStorageBackend<InspectorState> states;
    private final OrganizationsService organizationsService;

    @Inject
    public Inspector2Service(StorageFactory storageFactory, OrganizationsService organizationsService) {
        this(storageFactory.create("inspector2", "inspector2-state.json",
                new TypeReference<Map<String, InspectorState>>() {}), organizationsService);
    }

    Inspector2Service(AccountAwareStorageBackend<InspectorState> states, OrganizationsService organizationsService) {
        this.states = states;
        this.organizationsService = organizationsService;
    }

    public InspectorState state(String region) {
        return states.get(region).orElseGet(InspectorState::new);
    }

    public synchronized String createFilter(String region, String accountId, JsonNode request) {
        requireAccountId(accountId);
        InspectorState state = stateForAccount(accountId, region);
        InspectorFilter filter = new InspectorFilter();
        applyFilterProperties(filter, request, true);
        requireUniqueFilterName(state, filter.getName(), null);
        filter.setTags(readTags(request.get("tags")));
        String arn = AwsArnUtils.Arn.of("inspector2", region, accountId,
                "owner/" + accountId + "/filter/" + UUID.randomUUID()).toString();
        filter.setArn(arn);
        filter.setOwnerId(accountId);
        double now = timestamp();
        filter.setCreatedAt(now);
        filter.setUpdatedAt(now);
        state.getFilters().put(arn, filter);
        states.putForAccount(accountId, region, state);
        return arn;
    }

    public synchronized String updateFilter(String region, String accountId, JsonNode request) {
        String arn = text(request, "filterArn", 1, 128, true);
        InspectorState state = stateForAccount(accountId, region);
        InspectorFilter filter = requireFilter(state, arn).copy();
        applyFilterProperties(filter, request, false);
        requireUniqueFilterName(state, filter.getName(), arn);
        filter.setUpdatedAt(timestamp());
        state.getFilters().put(arn, filter);
        states.putForAccount(accountId, region, state);
        return arn;
    }

    public synchronized String deleteFilter(String region, String accountId, JsonNode request) {
        String arn = text(request, "arn", 1, 128, true);
        InspectorState state = stateForAccount(accountId, region);
        requireFilter(state, arn);
        state.getFilters().remove(arn);
        states.putForAccount(accountId, region, state);
        return arn;
    }

    public synchronized PaginatedResult<InspectorFilter> listFilters(
            String region, String accountId, JsonNode request) {
        requireObject(request);
        String action = request.has("action") ? filterAction(request) : null;
        JsonNode arns = request.get("arns");
        if (arns != null && !arns.isArray()) {
            throw validation("arns must be a list.");
        }
        List<String> requestedArns = new ArrayList<>();
        if (arns != null) {
            for (JsonNode arn : arns) {
                if (!arn.isTextual() || arn.textValue().isEmpty() || arn.textValue().length() > 128) {
                    throw validation("arns must contain filter ARNs.");
                }
                requestedArns.add(arn.textValue());
            }
        }
        Integer maxResults = null;
        if (request.has("maxResults")) {
            JsonNode value = request.get("maxResults");
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw validation("maxResults must be an integer.");
            }
            maxResults = value.intValue();
        }
        String token = text(request, "nextToken", 1, 1000000, false);
        List<InspectorFilter> filters = stateForAccount(accountId, region).getFilters().values().stream()
                .filter(filter -> arns == null || requestedArns.contains(filter.getArn()))
                .filter(filter -> action == null || action.equals(filter.getAction()))
                .map(InspectorFilter::copy)
                .toList();
        return Pagination.paginate(filters, InspectorFilter::getArn, maxResults, token, 100,
                "ValidationException");
    }

    public synchronized Map<String, String> listFilterTags(String region, String accountId, String arn) {
        return new LinkedHashMap<>(requireFilter(stateForAccount(accountId, region), arn).getTags());
    }

    public synchronized void tagFilter(String region, String accountId, String arn, Map<String, String> tags) {
        InspectorState state = stateForAccount(accountId, region);
        InspectorFilter filter = requireFilter(state, arn).copy();
        validateTags(tags);
        filter.getTags().putAll(tags);
        validateTags(filter.getTags());
        state.getFilters().put(arn, filter);
        states.putForAccount(accountId, region, state);
    }

    public synchronized void untagFilter(String region, String accountId, String arn, List<String> keys) {
        InspectorState state = stateForAccount(accountId, region);
        InspectorFilter filter = requireFilter(state, arn).copy();
        for (String key : keys) {
            validateTagKey(key);
        }
        keys.forEach(filter.getTags()::remove);
        state.getFilters().put(arn, filter);
        states.putForAccount(accountId, region, state);
    }

    public synchronized Map<String, Object> getEc2DeepInspectionConfiguration(String region, String accountId) {
        InspectorState state = stateForAccount(accountId, region);
        if (!"ENABLED".equals(state.getEc2Status())) {
            throw accessDenied("EC2 scanning is not enabled for the invoking account.");
        }
        return Map.of("status", state.getDeepInspectionStatus(),
                "packagePaths", List.of(), "orgPackagePaths", List.of());
    }

    public synchronized Map<String, Object> listCisScanConfigurations(
            String region, String accountId, JsonNode request) {
        requireObject(request);
        InspectorState state = stateForAccount(accountId, region);
        if (!"ENABLED".equals(state.getStatus())) {
            throw accessDenied("Invoking account is not enabled.");
        }
        throw new AwsException("NotImplementedException",
                "CIS scan configurations are not implemented by Floci.", 501);
    }

    private static InspectorFilter requireFilter(InspectorState state, String arn) {
        InspectorFilter filter = state.getFilters().get(arn);
        if (filter == null) {
            throw new AwsException("ResourceNotFoundException", "The specified filter was not found.", 404);
        }
        return filter;
    }

    private static void requireUniqueFilterName(InspectorState state, String name, String arn) {
        boolean duplicate = state.getFilters().values().stream()
                .anyMatch(filter -> name.equals(filter.getName()) && !filter.getArn().equals(arn));
        if (duplicate) {
            throw new AwsException("BadRequestException", "A filter with this name already exists.", 400);
        }
    }

    private static void applyFilterProperties(InspectorFilter filter, JsonNode request, boolean create) {
        requireObject(request);
        if (create || request.has("name")) {
            filter.setName(text(request, "name", 1, 128, true));
        }
        if (create || request.has("action")) {
            filter.setAction(filterAction(request));
        }
        if (create || request.has("filterCriteria")) {
            JsonNode criteria = request.get("filterCriteria");
            if (criteria == null || !criteria.isObject()) {
                throw validation("filterCriteria must be an object.");
            }
            for (JsonNode values : criteria) {
                if (!values.isArray()) {
                    throw validation("filterCriteria members must be lists.");
                }
                for (JsonNode value : values) {
                    if (!value.isObject()) {
                        throw validation("filterCriteria lists must contain objects.");
                    }
                }
            }
            filter.setCriteria(criteria.deepCopy());
        }
        if (request.has("description")) {
            filter.setDescription(text(request, "description", 1, 512, true));
        }
        if (request.has("reason")) {
            filter.setReason(text(request, "reason", 1, 512, true));
        }
    }

    private static String filterAction(JsonNode request) {
        String action = text(request, "action", 1, 8, true);
        if (!Set.of("NONE", "SUPPRESS").contains(action)) {
            throw validation("action must be NONE or SUPPRESS.");
        }
        return action;
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node != null) {
            if (!node.isObject()) {
                throw validation("tags must be a string map.");
            }
            node.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual()) {
                    throw validation("Tag values must be strings.");
                }
                tags.put(entry.getKey(), entry.getValue().textValue());
            });
        }
        validateTags(tags);
        return tags;
    }

    private static void validateTags(Map<String, String> tags) {
        if (tags.size() > 50) {
            throw validation("A resource can have at most 50 tags.");
        }
        tags.forEach((key, value) -> {
            validateTagKey(key);
            if (value == null || value.length() > 256) {
                throw validation("Tag values must contain at most 256 characters.");
            }
        });
    }

    private static void validateTagKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 128 || key.startsWith("aws:")) {
            throw validation("Tag keys must contain 1 to 128 characters and must not start with aws:.");
        }
    }

    private static String text(JsonNode request, String name, int min, int max, boolean required) {
        requireObject(request);
        JsonNode value = request.get(name);
        if (value == null && !required) {
            return null;
        }
        if (value == null || !value.isTextual() || value.textValue().length() < min
                || value.textValue().length() > max) {
            throw validation(name + " must be a string of length " + min + " to " + max + ".");
        }
        return value.textValue();
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw validation("Request must be a JSON object.");
        }
    }

    private static double timestamp() {
        Instant now = Instant.now();
        return now.getEpochSecond() + now.getNano() / 1_000_000_000.0;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public InspectorState delegatedAdminState(String region, String callerAccountId) {
        requireManagementAccount(callerAccountId);
        return stateForAccount(callerAccountId, region);
    }

    public synchronized void enableDelegatedAdmin(String region, String callerAccountId, String accountId) {
        requireAccountId(accountId);
        String managementAccountId = requireManagementAccount(callerAccountId);
        requireSameOrganization(managementAccountId, accountId);

        InspectorState management = stateForAccount(managementAccountId, region);
        if (management.getAdminAccountId() != null && !management.getAdminAccountId().equals(accountId)) {
            throw new AwsException("ConflictException",
                    "A different delegated administrator is already configured.", 409);
        }
        management.setAdminAccountId(accountId);
        states.putForAccount(managementAccountId, region, management);

        InspectorState delegated = stateForAccount(accountId, region);
        delegated.setAdminAccountId(accountId);
        for (String resourceType : RESOURCE_TYPES) {
            delegated.setResourceStatus(resourceType, "ENABLED");
        }
        delegated.setStatus("ENABLED");
        delegated.setDeepInspectionStatus("ACTIVATED");
        delegated.setEnablingPollsRemaining(0);
        states.putForAccount(accountId, region, delegated);
    }

    public synchronized void disableDelegatedAdmin(String region, String callerAccountId, String accountId) {
        requireAccountId(accountId);
        String managementAccountId = requireManagementAccount(callerAccountId);
        requireSameOrganization(managementAccountId, accountId);
        InspectorState management = stateForAccount(managementAccountId, region);
        if (!accountId.equals(management.getAdminAccountId())) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified delegated administrator was not found.", 404);
        }
        management.setAdminAccountId(null);
        states.putForAccount(managementAccountId, region, management);
        InspectorState delegated = stateForAccount(accountId, region);
        delegated.setAdminAccountId(null);
        states.putForAccount(accountId, region, delegated);
    }

    public synchronized InspectorState accountStatus(String region, String callerAccountId, String accountId) {
        requireAccountId(accountId);
        authorizeAccountAccess(region, callerAccountId, accountId);
        InspectorState state = stateForAccount(accountId, region);
        if ("ENABLING".equals(state.getStatus()) && state.getEnablingPollsRemaining() > 0) {
            InspectorState response = copyState(state);
            state.setEnablingPollsRemaining(state.getEnablingPollsRemaining() - 1);
            states.putForAccount(accountId, region, state);
            return response;
        }
        if ("ENABLING".equals(state.getStatus())) {
            for (String resourceType : RESOURCE_TYPES) {
                if ("ENABLING".equals(state.resourceStatus(resourceType))) {
                    state.setResourceStatus(resourceType, "ENABLED");
                    if ("EC2".equals(resourceType)) {
                        state.setDeepInspectionStatus("ACTIVATED");
                    }
                }
            }
            state.setStatus(overallStatus(state));
            states.putForAccount(accountId, region, state);
        }
        return copyState(state);
    }

    public synchronized Map<String, InspectorState> enable(
            String region, String callerAccountId, JsonNode request) {
        List<String> resourceTypes = resourceTypes(request);
        List<String> accountIds = accountIds(request, callerAccountId);

        for (String accountId : accountIds) {
            authorizeAccountAccess(region, callerAccountId, accountId);
        }

        Map<String, InspectorState> result = new LinkedHashMap<>();
        for (String accountId : accountIds) {
            InspectorState state = stateForAccount(accountId, region);
            boolean changed = false;
            for (String resourceType : resourceTypes) {
                String current = state.resourceStatus(resourceType);
                if (!"ENABLED".equals(current) && !"ENABLING".equals(current)) {
                    state.setResourceStatus(resourceType, "ENABLING");
                    changed = true;
                }
            }
            if (changed) {
                state.setStatus("ENABLING");
                state.setEnablingPollsRemaining(1);
                states.putForAccount(accountId, region, state);
            }
            result.put(accountId, copyState(state));
        }
        return result;
    }

    public synchronized InspectorState updateOrganizationConfiguration(
            String region, String callerAccountId, JsonNode request) {
        InspectorState state = requireAdministrator(region, callerAccountId);
        JsonNode autoEnable = request.get("autoEnable");
        if (autoEnable == null || !autoEnable.isObject()) {
            throw new AwsException("ValidationException", "autoEnable is required.", 400);
        }

        boolean ec2 = requiredBoolean(autoEnable, "ec2");
        boolean ecr = requiredBoolean(autoEnable, "ecr");
        boolean lambda = optionalBoolean(autoEnable, "lambda");
        boolean lambdaCode = optionalBoolean(autoEnable, "lambdaCode");
        boolean codeRepository = optionalBoolean(autoEnable, "codeRepository");

        state.setAutoEnableEc2(ec2);
        state.setAutoEnableEcr(ecr);
        state.setAutoEnableLambda(lambda);
        state.setAutoEnableLambdaCode(lambdaCode);
        state.setAutoEnableCodeRepository(codeRepository);
        states.putForAccount(callerAccountId, region, state);
        return copyState(state);
    }

    public InspectorState organizationConfiguration(String region, String callerAccountId) {
        return copyState(requireAdministrator(region, callerAccountId));
    }

    private InspectorState requireAdministrator(String region, String callerAccountId) {
        String managementAccountId = managementAccountFor(callerAccountId)
                .orElseThrow(() -> new AwsException("AccessDeniedException",
                        "Only the delegated Amazon Inspector administrator can manage organization configuration.", 403));
        InspectorState management = stateForAccount(managementAccountId, region);
        if (management.getAdminAccountId() == null || !callerAccountId.equals(management.getAdminAccountId())) {
            throw new AwsException("AccessDeniedException",
                    "Only the delegated Amazon Inspector administrator can manage organization configuration.", 403);
        }
        return stateForAccount(callerAccountId, region);
    }

    private void authorizeAccountAccess(String region, String callerAccountId, String targetAccountId) {
        if (callerAccountId.equals(targetAccountId)) {
            return;
        }
        String managementAccountId = managementAccountFor(callerAccountId)
                .orElseThrow(() -> accessDenied("The caller cannot manage the requested Amazon Inspector account."));
        requireSameOrganization(managementAccountId, targetAccountId);
        InspectorState management = stateForAccount(managementAccountId, region);
        if (!callerAccountId.equals(management.getAdminAccountId())) {
            throw accessDenied("Only the delegated Amazon Inspector administrator can manage member accounts.");
        }
    }

    private String requireManagementAccount(String callerAccountId) {
        String managementAccountId = managementAccountFor(callerAccountId)
                .orElseThrow(() -> accessDenied("Only the AWS Organizations management account can perform this operation."));
        if (!callerAccountId.equals(managementAccountId)) {
            throw accessDenied("Only the AWS Organizations management account can perform this operation.");
        }
        return managementAccountId;
    }

    private void requireSameOrganization(String managementAccountId, String accountId) {
        String targetManagementAccount = managementAccountFor(accountId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified AWS account is not a member of the organization.", 404));
        if (!managementAccountId.equals(targetManagementAccount)) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified AWS account is not a member of the organization.", 404);
        }
    }

    private java.util.Optional<String> managementAccountFor(String accountId) {
        return organizationsService.findManagementAccountForResource(accountId);
    }

    private InspectorState stateForAccount(String accountId, String region) {
        return states.getForAccount(accountId, region).orElseGet(InspectorState::new);
    }

    private static List<String> resourceTypes(JsonNode request) {
        JsonNode resourceTypes = request.get("resourceTypes");
        if (resourceTypes == null || !resourceTypes.isArray() || resourceTypes.isEmpty() || resourceTypes.size() > 5) {
            throw new AwsException("ValidationException",
                    "resourceTypes must contain between 1 and 5 resource types.", 400);
        }
        List<String> result = new ArrayList<>(resourceTypes.size());
        for (JsonNode resourceType : resourceTypes) {
            if (!resourceType.isTextual() || !RESOURCE_TYPES.contains(resourceType.textValue())) {
                throw new AwsException("ValidationException", "resourceTypes contains an invalid resource type.", 400);
            }
            if (!result.contains(resourceType.textValue())) {
                result.add(resourceType.textValue());
            }
        }
        return result;
    }

    private static List<String> accountIds(JsonNode request, String callerAccountId) {
        JsonNode accountIds = request.get("accountIds");
        if (accountIds != null && (!accountIds.isArray() || accountIds.size() > 100)) {
            throw new AwsException("ValidationException", "accountIds must contain at most 100 account IDs.", 400);
        }
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of(callerAccountId);
        }
        List<String> result = new ArrayList<>(accountIds.size());
        for (JsonNode accountId : accountIds) {
            requireAccountId(accountId.asText(null));
            result.add(accountId.asText());
        }
        return result;
    }

    private static String overallStatus(InspectorState state) {
        boolean enabling = RESOURCE_TYPES.stream().anyMatch(type -> "ENABLING".equals(state.resourceStatus(type)));
        if (enabling) {
            return "ENABLING";
        }
        boolean enabled = RESOURCE_TYPES.stream().anyMatch(type -> "ENABLED".equals(state.resourceStatus(type)));
        return enabled ? "ENABLED" : "DISABLED";
    }

    private static InspectorState copyState(InspectorState source) {
        InspectorState copy = new InspectorState();
        copy.setAdminAccountId(source.getAdminAccountId());
        copy.setStatus(source.getStatus());
        copy.setEnablingPollsRemaining(source.getEnablingPollsRemaining());
        copy.setEc2Status(source.getEc2Status());
        copy.setEcrStatus(source.getEcrStatus());
        copy.setLambdaStatus(source.getLambdaStatus());
        copy.setLambdaCodeStatus(source.getLambdaCodeStatus());
        copy.setCodeRepositoryStatus(source.getCodeRepositoryStatus());
        copy.setAutoEnableEc2(source.isAutoEnableEc2());
        copy.setAutoEnableEcr(source.isAutoEnableEcr());
        copy.setAutoEnableLambda(source.isAutoEnableLambda());
        copy.setAutoEnableLambdaCode(source.isAutoEnableLambdaCode());
        copy.setAutoEnableCodeRepository(source.isAutoEnableCodeRepository());
        copy.setDeepInspectionStatus(source.getDeepInspectionStatus());
        source.getFilters().forEach((arn, filter) -> copy.getFilters().put(arn, filter.copy()));
        return copy;
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new AwsException("ValidationException", "autoEnable." + field + " is required.", 400);
        }
        return value.booleanValue();
    }

    private static boolean optionalBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return false;
        }
        if (!value.isBoolean()) {
            throw new AwsException("ValidationException", "autoEnable." + field + " must be a boolean.", 400);
        }
        return value.booleanValue();
    }

    private static AwsException accessDenied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }

    @Override
    public void clear() {
        states.clear();
    }

    static void requireAccountId(String accountId) {
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw new AwsException("ValidationException",
                    "accountId must be a 12 digit AWS account ID.", 400);
        }
    }
}
