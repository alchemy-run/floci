package io.github.hectorvent.floci.services.securityhub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubAssociation;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class SecurityHubService implements Resettable {
    private static final String SELF_MANAGED = "SELF_MANAGED_SECURITY_HUB";
    private static final List<String> LINKING_MODES = List.of(
            "ALL_REGIONS", "ALL_REGIONS_EXCEPT_SPECIFIED", "SPECIFIED_REGIONS", "NO_REGIONS");

    private final AccountAwareStorageBackend<SecurityHubState> states;
    private final RegionResolver regionResolver;
    private final OrganizationsService organizationsService;

    @Inject
    public SecurityHubService(StorageFactory storageFactory, RegionResolver regionResolver,
                              OrganizationsService organizationsService) {
        this(storageFactory.create("securityhub", "securityhub-state.json",
                new TypeReference<Map<String, SecurityHubState>>() {}), regionResolver, organizationsService);
    }

    SecurityHubService(AccountAwareStorageBackend<SecurityHubState> states, RegionResolver regionResolver,
                       OrganizationsService organizationsService) {
        this.states = states;
        this.regionResolver = regionResolver;
        this.organizationsService = organizationsService;
    }

    public SecurityHubState state(String region) {
        return states.get(region).orElseGet(SecurityHubState::new);
    }

    public SecurityHubState organizationAdminState(String region) {
        String callerAccountId = regionResolver.getAccountId();
        String managementAccountId = organizationsService.findManagementAccountForResource(callerAccountId)
                .orElseThrow(() -> invalidAccess("Only the organization management account can perform this operation."));
        if (!callerAccountId.equals(managementAccountId)) {
            throw invalidAccess("Only the organization management account can perform this operation.");
        }
        return state(region);
    }

    public synchronized SecurityHubState enableOrganizationAdminAccount(String region, String adminAccountId,
                                                                        String feature) {
        requireAccountId(adminAccountId, "AdminAccountId");
        String requestedFeature = normalizeFeature(feature);
        requireOrganizationManagementAccount(adminAccountId);
        SecurityHubState management = state(region);
        if (management.getAdminAccountId() != null && !management.getAdminAccountId().equals(adminAccountId)) {
            throw conflict("A different Security Hub administrator account is already configured.");
        }
        management.setAdminAccountId(adminAccountId);
        management.setAdminFeature(requestedFeature);
        save(region, management);

        SecurityHubState delegated = states.getForAccount(adminAccountId, region).orElseGet(SecurityHubState::new);
        delegated.setAdminAccountId(adminAccountId);
        delegated.setAdminFeature(requestedFeature);
        if ("SecurityHub".equals(requestedFeature)) {
            delegated.setEnabled(true);
        }
        states.putForAccount(adminAccountId, region, delegated);
        return management;
    }

    public synchronized void enableSecurityHub(String region, JsonNode request) {
        SecurityHubState state = state(region);
        if (state.isEnabled()) {
            throw conflict("Security Hub is already enabled for this account.");
        }
        String controlFindingGenerator = text(request, "ControlFindingGenerator");
        if (controlFindingGenerator != null
                && !"SECURITY_CONTROL".equals(controlFindingGenerator)
                && !"STANDARD_CONTROL".equals(controlFindingGenerator)) {
            throw invalid("ControlFindingGenerator must be SECURITY_CONTROL or STANDARD_CONTROL.");
        }
        validateTags(request == null ? null : request.get("Tags"));
        if (request.has("EnableDefaultStandards") && !request.get("EnableDefaultStandards").isBoolean()) {
            throw invalid("EnableDefaultStandards must be a boolean.");
        }
        state.setEnabled(true);
        if (request.path("EnableDefaultStandards").asBoolean(true)) {
            enableStandard(region, state, object());
        }
        if (controlFindingGenerator != null) {
            state.setControlFindingGenerator(controlFindingGenerator);
        }
        state.setHubTags(readTags(request == null ? null : request.get("Tags")));
        save(region, state);
    }

    public synchronized void updateSecurityHubConfiguration(String region, JsonNode request) {
        requireEnabled(region);
        SecurityHubState state = state(region);
        JsonNode autoEnableControls = request == null ? null : request.get("AutoEnableControls");
        if (autoEnableControls != null && !autoEnableControls.isBoolean()) {
            throw invalid("AutoEnableControls must be a boolean.");
        }
        String controlFindingGenerator = text(request, "ControlFindingGenerator");
        if (controlFindingGenerator != null
                && !"SECURITY_CONTROL".equals(controlFindingGenerator)
                && !"STANDARD_CONTROL".equals(controlFindingGenerator)) {
            throw invalid("ControlFindingGenerator must be SECURITY_CONTROL or STANDARD_CONTROL.");
        }
        if (autoEnableControls != null) {
            state.setAutoEnableControls(autoEnableControls.booleanValue());
        }
        if (controlFindingGenerator != null) {
            state.setControlFindingGenerator(controlFindingGenerator);
        }
        save(region, state);
    }

    public synchronized void disableSecurityHub(String region) {
        requireEnabled(region);
        SecurityHubState state = state(region);
        state.setEnabled(false);
        state.setHubTags(new LinkedHashMap<>());
        state.setAutoEnableControls(true);
        state.setControlFindingGenerator("SECURITY_CONTROL");
        state.getActionTargets().clear();
        state.getInsights().clear();
        state.getAutomationRules().clear();
        state.getFindings().clear();
        state.getFindingHistory().clear();
        state.getStandardsSubscriptions().clear();
        state.getProductSubscriptions().clear();
        state.getMembers().clear();
        state.setAdministrator(null);
        state.setAggregatorArn(null);
        state.setRegionLinkingMode(null);
        state.setRegions(null);
        save(region, state);
    }

    public void requireEnabled(String region) {
        if (!state(region).isEnabled()) {
            throw notFound("Security Hub is not enabled for this account.");
        }
    }

    private void requireSubscribed(String region) {
        if (!state(region).isEnabled()) {
            throw invalidAccess("Security Hub is not enabled for this account and region.");
        }
    }

    public synchronized ObjectNode createActionTarget(String region, JsonNode request) {
        requireSubscribed(region);
        String id = requireText(request, "Id", "InvalidInputException");
        if (!id.matches("[a-zA-Z0-9_-]{1,20}")) {
            throw invalid("Id must contain 1 to 20 alphanumeric, hyphen, or underscore characters.");
        }
        String arn = resourceArn(region, "action/custom/" + id);
        SecurityHubState state = state(region);
        if (state.getActionTargets().containsKey(arn)) {
            throw conflict("The action target already exists.");
        }
        ObjectNode action = object().put("ActionTargetArn", arn);
        action.put("Name", boundedText(request, "Name", 20));
        action.put("Description", boundedText(request, "Description", 500));
        state.getActionTargets().put(arn, action);
        save(region, state);
        return object().put("ActionTargetArn", arn);
    }

    public synchronized ObjectNode describeActionTargets(String region, JsonNode request) {
        requireSubscribed(region);
        return documentPage("ActionTargets", select(state(region).getActionTargets(), request,
                "ActionTargetArns", 100), "ActionTargetArn", request);
    }

    public synchronized void updateActionTarget(String region, String arn, JsonNode request) {
        requireSubscribed(region);
        SecurityHubState state = state(region);
        ObjectNode action = requiredDocument(state.getActionTargets(), arn).deepCopy();
        if (request.has("Name")) {
            action.put("Name", boundedText(request, "Name", 20));
        }
        if (request.has("Description")) {
            action.put("Description", boundedText(request, "Description", 500));
        }
        state.getActionTargets().put(arn, action);
        save(region, state);
    }

    public synchronized ObjectNode deleteActionTarget(String region, String arn) {
        requireSubscribed(region);
        SecurityHubState state = state(region);
        requiredDocument(state.getActionTargets(), arn);
        state.getActionTargets().remove(arn);
        save(region, state);
        return object().put("ActionTargetArn", arn);
    }

    public synchronized ObjectNode createInsight(String region, JsonNode request) {
        requireSubscribed(region);
        SecurityHubState state = state(region);
        String name = boundedText(request, "Name", 128);
        if (state.getInsights().values().stream().anyMatch(item -> name.equals(item.path("Name").asText()))) {
            throw conflict("An insight with this name already exists.");
        }
        validateFilters(request.get("Filters"));
        String group = requireText(request, "GroupByAttribute", "InvalidInputException");
        String arn = resourceArn(region, "insight/" + regionResolver.getAccountId() + "/custom/" + UUID.randomUUID());
        ObjectNode insight = object().put("InsightArn", arn).put("Name", name).put("GroupByAttribute", group);
        insight.set("Filters", request.path("Filters").deepCopy());
        state.getInsights().put(arn, insight);
        save(region, state);
        return object().put("InsightArn", arn);
    }

    public synchronized ObjectNode getInsights(String region, JsonNode request) {
        requireSubscribed(region);
        return documentPage("Insights", select(state(region).getInsights(), request, "InsightArns", 100),
                "InsightArn", request);
    }

    public synchronized void updateInsight(String region, String arn, JsonNode request) {
        requireSubscribed(region);
        SecurityHubState state = state(region);
        ObjectNode insight = requiredDocument(state.getInsights(), arn).deepCopy();
        if (request.has("Name")) {
            String name = boundedText(request, "Name", 128);
            if (state.getInsights().entrySet().stream().anyMatch(entry -> !entry.getKey().equals(arn)
                    && name.equals(entry.getValue().path("Name").asText()))) {
                throw conflict("An insight with this name already exists.");
            }
            insight.put("Name", name);
        }
        if (request.has("Filters")) {
            validateFilters(request.get("Filters"));
            insight.set("Filters", request.get("Filters").deepCopy());
        }
        if (request.has("GroupByAttribute")) {
            insight.put("GroupByAttribute", requireText(request, "GroupByAttribute", "InvalidInputException"));
        }
        state.getInsights().put(arn, insight);
        save(region, state);
    }

    public synchronized ObjectNode deleteInsight(String region, String arn) {
        requireSubscribed(region);
        SecurityHubState state = state(region);
        requiredDocument(state.getInsights(), arn);
        state.getInsights().remove(arn);
        save(region, state);
        return object().put("InsightArn", arn);
    }

    public synchronized ObjectNode createAutomationRule(String region, JsonNode request) {
        requireSubscribed(region);
        ObjectNode rule = (ObjectNode) request.deepCopy();
        validateRule(rule);
        validateTags(rule.get("Tags"));
        String arn = resourceArn(region, "automation-rule/" + UUID.randomUUID());
        String now = Instant.now().toString();
        rule.put("RuleArn", arn).put("CreatedAt", now).put("UpdatedAt", now)
                .put("CreatedBy", regionResolver.getAccountId());
        if (!rule.has("RuleStatus")) {
            rule.put("RuleStatus", "ENABLED");
        }
        if (!rule.has("IsTerminal")) {
            rule.put("IsTerminal", false);
        }
        SecurityHubState state = state(region);
        state.getAutomationRules().put(arn, rule);
        save(region, state);
        return object().put("RuleArn", arn);
    }

    public synchronized ObjectNode listAutomationRules(String region, JsonNode request) {
        requireSubscribed(region);
        return documentPage("AutomationRulesMetadata", new ArrayList<>(state(region).getAutomationRules().values()),
                "RuleArn", request);
    }

    public synchronized ObjectNode batchAutomationRules(String region, JsonNode request, String operation) {
        requireSubscribed(region);
        SecurityHubState state = state(region);
        JsonNode items = requiredArray(request,
                "update".equals(operation) ? "UpdateAutomationRulesRequestItems" : "AutomationRulesArns", 100);
        ObjectNode response = object();
        ArrayNode processed = response.putArray("get".equals(operation) ? "Rules" : "ProcessedAutomationRules");
        ArrayNode unprocessed = response.putArray("UnprocessedAutomationRules");
        for (JsonNode item : items) {
            String arn = "update".equals(operation) ? text(item, "RuleArn") : item.asText(null);
            try {
                ObjectNode rule = requiredDocument(state.getAutomationRules(), arn).deepCopy();
                if ("get".equals(operation)) {
                    processed.add(rule);
                } else if ("delete".equals(operation)) {
                    state.getAutomationRules().remove(arn);
                    processed.add(arn);
                } else {
                    for (String field : List.of("RuleName", "Description", "RuleOrder", "RuleStatus", "IsTerminal",
                            "Criteria", "Actions")) {
                        if (item.has(field)) {
                            rule.set(field, item.get(field).deepCopy());
                        }
                    }
                    validateRule(rule);
                    rule.put("UpdatedAt", Instant.now().toString());
                    state.getAutomationRules().put(arn, rule);
                    processed.add(arn);
                }
            } catch (AwsException e) {
                unprocessed.addObject().put("RuleArn", arn).put("ErrorCode", e.getHttpStatus())
                        .put("ErrorMessage", e.getMessage());
            }
        }
        if (!"get".equals(operation)) {
            save(region, state);
        }
        return response;
    }

    private static void validateRule(JsonNode rule) {
        boundedText(rule, "RuleName", 256);
        boundedText(rule, "Description", 1024);
        Integer order = integer(rule, "RuleOrder");
        if (order == null || order < 1 || order > 1000) {
            throw invalid("RuleOrder must be between 1 and 1000.");
        }
        if (rule.has("RuleStatus") && !List.of("ENABLED", "DISABLED").contains(rule.path("RuleStatus").asText())) {
            throw invalid("RuleStatus must be ENABLED or DISABLED.");
        }
        if (rule.has("IsTerminal") && !rule.get("IsTerminal").isBoolean()) {
            throw invalid("IsTerminal must be a boolean.");
        }
        validateFilters(rule.get("Criteria"));
        for (JsonNode action : requiredArray(rule, "Actions", 1)) {
            if (!"FINDING_FIELDS_UPDATE".equals(action.path("Type").asText())
                    || !action.path("FindingFieldsUpdate").isObject()) {
                throw invalid("Actions must contain a FINDING_FIELDS_UPDATE action with FindingFieldsUpdate.");
            }
            validateFindingUpdate(action.get("FindingFieldsUpdate"));
        }
    }

    public synchronized void deleteFindingAggregator(String region, String arn) {
        SecurityHubState state = getFindingAggregator(region, arn);
        state.setAggregatorArn(null);
        state.setRegionLinkingMode(null);
        state.setRegions(null);
        save(region, state);
    }

    public synchronized ObjectNode batchImportFindings(String region, JsonNode request) {
        requireSubscribed(region);
        JsonNode findings = requiredArray(request, "Findings", 100);
        SecurityHubState state = state(region);
        ObjectNode response = object();
        ArrayNode errors = response.putArray("FailedFindings");
        int success = 0;
        for (JsonNode input : findings) {
            try {
                validateFinding(region, input);
                String key = findingKey(input);
                JsonNode previous = state.getFindings().get(key);
                if (previous != null && timestamp(input, "UpdatedAt").isBefore(timestamp(previous, "UpdatedAt"))) {
                    success++;
                    continue;
                }
                ObjectNode finding = previous == null ? object() : (ObjectNode) previous.deepCopy();
                finding.setAll((ObjectNode) input.deepCopy());
                if (previous != null) {
                    for (String field : List.of("Note", "Workflow", "VerificationState", "UserDefinedFields")) {
                        if (previous.has(field)) {
                            finding.set(field, previous.get(field).deepCopy());
                        }
                    }
                }
                if (!finding.has("RecordState")) {
                    finding.put("RecordState", "ACTIVE");
                }
                if (!finding.has("Workflow")) {
                    finding.putObject("Workflow").put("Status", "NEW");
                }
                finding.put("Region", region);
                applyAutomationRules(state, finding);
                state.getFindings().put(key, finding);
                recordHistory(state, key, previous, finding, "BATCH_IMPORT_FINDINGS");
                success++;
            } catch (AwsException e) {
                errors.addObject().put("Id", input.path("Id").asText())
                        .put("ErrorCode", "InvalidAccessException".equals(e.getErrorCode()) ? "InvalidAccess" : "InvalidInput")
                        .put("ErrorMessage", e.getMessage());
            }
        }
        save(region, state);
        return response.put("SuccessCount", success).put("FailedCount", errors.size());
    }

    public synchronized ObjectNode getFindings(String region, JsonNode request) {
        requireSubscribed(region);
        JsonNode filters = request.has("Filters") ? request.get("Filters") : object();
        validateFilters(filters);
        if (request.has("SortCriteria") && !request.path("SortCriteria").isEmpty()) {
            throw invalid("SortCriteria is not supported by the local findings store.");
        }
        List<JsonNode> findings = state(region).getFindings().values().stream()
                .filter(finding -> matchesFilters(finding, filters)).toList();
        PaginatedResult<JsonNode> page = Pagination.paginate(findings, SecurityHubService::findingKey,
                integer(request, "MaxResults"), text(request, "NextToken"), 100, "InvalidInputException");
        ObjectNode response = object();
        ArrayNode items = response.putArray("Findings");
        page.items().forEach(item -> items.add(item.<JsonNode>deepCopy()));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public synchronized ObjectNode batchUpdateFindings(String region, JsonNode request) {
        requireSubscribed(region);
        JsonNode identifiers = requiredArray(request, "FindingIdentifiers", 100);
        for (JsonNode identifier : identifiers) {
            findingKey(identifier);
        }
        validateFindingUpdate(request);
        SecurityHubState state = state(region);
        ObjectNode response = object();
        ArrayNode processed = response.putArray("ProcessedFindings");
        ArrayNode unprocessed = response.putArray("UnprocessedFindings");
        for (JsonNode identifier : identifiers) {
            String key = findingKey(identifier);
            JsonNode previous = state.getFindings().get(key);
            if (previous == null) {
                ObjectNode error = unprocessed.addObject().put("ErrorCode", "FindingNotFound")
                        .put("ErrorMessage", "The specified finding was not found.");
                error.set("FindingIdentifier", identifier.deepCopy());
                continue;
            }
            ObjectNode finding = (ObjectNode) previous.deepCopy();
            applyFindingUpdate(finding, request);
            state.getFindings().put(key, finding);
            recordHistory(state, key, previous, finding, "BATCH_UPDATE_FINDINGS");
            processed.add(identifier.<JsonNode>deepCopy());
        }
        save(region, state);
        return response;
    }

    public synchronized ObjectNode getFindingHistory(String region, JsonNode request) {
        requireSubscribed(region);
        String key = findingKey(request.path("FindingIdentifier"));
        SecurityHubState state = state(region);
        requiredDocument(state.getFindings(), key);
        Instant start = request.has("StartTime") ? timestamp(request, "StartTime") : Instant.MIN;
        Instant end = request.has("EndTime") ? timestamp(request, "EndTime") : Instant.MAX;
        if (end.isBefore(start)) {
            throw invalid("EndTime must not precede StartTime.");
        }
        List<JsonNode> records = new ArrayList<>();
        JsonNode history = state.getFindingHistory().get(key);
        if (history != null) {
            for (JsonNode record : history) {
                Instant time = timestamp(record, "UpdateTime");
                if (!time.isBefore(start) && !time.isAfter(end)) {
                    records.add(record);
                }
            }
        }
        return documentPage("Records", records, "UpdateTime", request);
    }

    private void validateFinding(String region, JsonNode finding) {
        if (!finding.isObject() || !"2018-10-08".equals(finding.path("SchemaVersion").asText())) {
            throw invalid("SchemaVersion must be 2018-10-08.");
        }
        boundedText(finding, "Id", 512);
        boundedText(finding, "GeneratorId", 512);
        boundedText(finding, "Title", 256);
        boundedText(finding, "Description", 1024);
        String account = requireText(finding, "AwsAccountId", "InvalidInputException");
        requireAccountId(account, "AwsAccountId");
        if (!regionResolver.getAccountId().equals(account)
                || !defaultProductArn(region).equals(text(finding, "ProductArn"))) {
            throw invalidAccess("Findings must belong to the caller and use the caller's default product ARN.");
        }
        Instant created = timestamp(finding, "CreatedAt");
        if (timestamp(finding, "UpdatedAt").isBefore(created)) {
            throw invalid("UpdatedAt must not precede CreatedAt.");
        }
        for (JsonNode type : requiredArray(finding, "Types", 50)) {
            if (!type.isTextual() || type.textValue().isBlank()) {
                throw invalid("Types must contain non-empty strings.");
            }
        }
        for (JsonNode resource : requiredArray(finding, "Resources", 32)) {
            requireText(resource, "Id", "InvalidInputException");
            requireText(resource, "Type", "InvalidInputException");
        }
        if (!finding.path("Severity").isObject()) {
            throw invalid("Severity is required.");
        }
        validateFindingUpdate(finding);
        if (finding.has("RecordState") && !List.of("ACTIVE", "ARCHIVED").contains(finding.path("RecordState").asText())) {
            throw invalid("RecordState must be ACTIVE or ARCHIVED.");
        }
    }

    private static Instant timestamp(JsonNode input, String field) {
        String value = requireText(input, field, "InvalidInputException");
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(field + " must be an ISO-8601 timestamp.");
        }
    }

    private static String findingKey(JsonNode identifier) {
        return requireText(identifier, "ProductArn", "InvalidInputException") + "\u0000"
                + requireText(identifier, "Id", "InvalidInputException");
    }

    private static void validateFindingUpdate(JsonNode request) {
        if (request.has("Workflow") && !List.of("NEW", "NOTIFIED", "RESOLVED", "SUPPRESSED")
                .contains(request.path("Workflow").path("Status").asText())) {
            throw invalid("Workflow.Status must be NEW, NOTIFIED, RESOLVED, or SUPPRESSED.");
        }
        if (request.has("Note")) {
            boundedText(request.get("Note"), "Text", 512);
            boundedText(request.get("Note"), "UpdatedBy", 512);
        }
        if (request.has("Severity")) {
            JsonNode severity = request.get("Severity");
            if (!severity.isObject() || severity.isEmpty()) {
                throw invalid("Severity must be a non-empty object.");
            }
            if (severity.has("Label") && !List.of("INFORMATIONAL", "LOW", "MEDIUM", "HIGH", "CRITICAL")
                    .contains(severity.path("Label").asText())) {
                throw invalid("Severity.Label is invalid.");
            }
        }
        for (String field : List.of("Confidence", "Criticality")) {
            if (request.has(field)) {
                Integer value = integer(request, field);
                if (value == null || value < 0 || value > 100) {
                    throw invalid(field + " must be between 0 and 100.");
                }
            }
        }
        if (request.has("UserDefinedFields") && !request.get("UserDefinedFields").isObject()) {
            throw invalid("UserDefinedFields must be an object.");
        }
        if (request.has("VerificationState") && !List.of("UNKNOWN", "TRUE_POSITIVE", "FALSE_POSITIVE", "BENIGN_POSITIVE")
                .contains(request.path("VerificationState").asText())) {
            throw invalid("VerificationState is invalid.");
        }
    }

    private static void applyFindingUpdate(ObjectNode finding, JsonNode request) {
        for (String field : List.of("Note", "Severity", "VerificationState", "Confidence", "Criticality",
                "Types", "UserDefinedFields", "Workflow", "RelatedFindings")) {
            if (request.has(field)) {
                finding.set(field, request.get(field).deepCopy());
            }
        }
        if (request.has("Note")) {
            ((ObjectNode) finding.get("Note")).put("UpdatedAt", Instant.now().toString());
        }
    }

    private static void applyAutomationRules(SecurityHubState state, ObjectNode finding) {
        List<JsonNode> rules = state.getAutomationRules().values().stream()
                .filter(rule -> "ENABLED".equals(rule.path("RuleStatus").asText()))
                .sorted((left, right) -> Integer.compare(left.path("RuleOrder").asInt(), right.path("RuleOrder").asInt()))
                .toList();
        for (JsonNode rule : rules) {
            if (matchesFilters(finding, rule.path("Criteria"))) {
                for (JsonNode action : rule.path("Actions")) {
                    applyFindingUpdate(finding, action.path("FindingFieldsUpdate"));
                }
                if (rule.path("IsTerminal").asBoolean()) {
                    break;
                }
            }
        }
    }

    private void recordHistory(SecurityHubState state, String key, JsonNode previous, JsonNode finding, String source) {
        ObjectNode record = object().put("UpdateTime", Instant.now().toString()).put("FindingCreated", previous == null);
        record.putObject("FindingIdentifier").put("Id", finding.path("Id").asText())
                .put("ProductArn", finding.path("ProductArn").asText());
        record.putObject("UpdateSource").put("Type", source).put("Identity", regionResolver.getAccountId());
        ArrayNode updates = record.putArray("Updates");
        finding.fields().forEachRemaining(entry -> {
            if (List.of("CreatedAt", "UpdatedAt", "FirstObservedAt", "LastObservedAt", "ProcessedAt")
                    .contains(entry.getKey())) {
                return;
            }
            JsonNode old = previous == null ? null : previous.get(entry.getKey());
            if (!entry.getValue().equals(old)) {
                ObjectNode change = updates.addObject().put("UpdatedField", entry.getKey())
                        .put("NewValue", historyValue(entry.getValue()));
                if (old != null) {
                    change.put("OldValue", historyValue(old));
                }
            }
        });
        if (previous != null && updates.isEmpty()) {
            return;
        }
        ArrayNode history = state.getFindingHistory().containsKey(key)
                ? (ArrayNode) state.getFindingHistory().get(key).deepCopy() : JsonNodeFactory.instance.arrayNode();
        history.add(record);
        state.getFindingHistory().put(key, history);
    }

    private static String historyValue(JsonNode value) {
        return value.isTextual() ? value.textValue() : value.toString();
    }

    private static void validateFilters(JsonNode filters) {
        if (filters == null || !filters.isObject()) {
            throw invalid("Filters or Criteria must be an object.");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = filters.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!List.of("Id", "ProductArn", "AwsAccountId", "GeneratorId", "Title", "Description", "RecordState",
                    "WorkflowStatus", "SeverityLabel", "VerificationState", "ResourceId", "ResourceType",
                    "ResourceRegion", "Region", "Type", "ComplianceStatus").contains(field.getKey())) {
                throw invalid("Unsupported finding filter: " + field.getKey());
            }
            JsonNode conditions = field.getValue();
            if (!conditions.isArray() || conditions.isEmpty() || conditions.size() > 20) {
                throw invalid("Finding filters must contain between 1 and 20 comparisons.");
            }
            boolean negative = false;
            boolean positive = false;
            for (JsonNode condition : conditions) {
                requireText(condition, "Value", "InvalidInputException");
                String comparison = requireText(condition, "Comparison", "InvalidInputException");
                if (!List.of("EQUALS", "NOT_EQUALS", "PREFIX", "PREFIX_NOT_EQUALS", "CONTAINS", "NOT_CONTAINS")
                        .contains(comparison)) {
                    throw invalid("Unsupported string comparison: " + comparison);
                }
                if (comparison.contains("NOT")) {
                    negative = true;
                } else {
                    positive = true;
                }
            }
            if (negative && positive) {
                throw invalid("Positive and negative comparisons cannot be combined for the same filter.");
            }
        }
    }

    private static boolean matchesFilters(JsonNode finding, JsonNode filters) {
        Iterator<Map.Entry<String, JsonNode>> fields = filters.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            List<String> values = findingValues(finding, field.getKey());
            boolean negative = field.getValue().get(0).path("Comparison").asText().contains("NOT");
            boolean matched = negative;
            for (JsonNode condition : field.getValue()) {
                String comparison = condition.path("Comparison").asText();
                String expected = condition.path("Value").asText();
                boolean any = values.stream().anyMatch(value -> switch (comparison) {
                    case "PREFIX", "PREFIX_NOT_EQUALS" -> value.startsWith(expected);
                    case "CONTAINS", "NOT_CONTAINS" -> value.contains(expected);
                    default -> value.equals(expected);
                });
                matched = negative ? matched && !any : matched || any;
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static List<String> findingValues(JsonNode finding, String field) {
        if (field.startsWith("Resource")) {
            String resourceField = field.substring("Resource".length());
            List<String> values = new ArrayList<>();
            for (JsonNode resource : finding.path("Resources")) {
                if (resource.has(resourceField)) {
                    values.add(resource.path(resourceField).asText());
                }
            }
            return values;
        }
        JsonNode value = switch (field) {
            case "WorkflowStatus" -> finding.path("Workflow").path("Status");
            case "SeverityLabel" -> finding.path("Severity").path("Label");
            case "ComplianceStatus" -> finding.path("Compliance").path("Status");
            case "Type" -> finding.path("Types");
            default -> finding.path(field);
        };
        if (value.isArray()) {
            List<String> values = new ArrayList<>();
            value.forEach(item -> values.add(item.asText()));
            return values;
        }
        return value.isMissingNode() ? List.of() : List.of(value.asText());
    }

    public synchronized ObjectNode describeProducts(String region, JsonNode request) {
        ObjectNode product = object().put("ProductArn", defaultProductArn(region)).put("ProductName", "Default")
                .put("CompanyName", "Personal").put("Description", "Default product for custom findings");
        product.putArray("IntegrationTypes").add("SEND_FINDINGS_TO_SECURITY_HUB");
        String arn = text(request, "ProductArn");
        return documentPage("Products", arn == null || arn.equals(defaultProductArn(region))
                ? List.of(product) : List.of(), "ProductArn", request);
    }

    public synchronized ObjectNode describeStandards(String region, JsonNode request) {
        return documentPage("Standards", standards(region), "StandardsArn", request);
    }

    private List<JsonNode> standards(String region) {
        ObjectNode standard = object().put("StandardsArn", standardsArn(region))
                .put("Name", "AWS Foundational Security Best Practices v1.0.0")
                .put("Description", "AWS Foundational Security Best Practices standard")
                .put("EnabledByDefault", true);
        standard.putObject("StandardsManagedBy").put("Company", "AWS");
        return List.of(standard);
    }

    private String standardsArn(String region) {
        return "arn:aws:securityhub:" + region + "::standards/aws-foundational-security-best-practices/v/1.0.0";
    }

    private String defaultProductArn(String region) {
        return resourceArn(region, "product/" + regionResolver.getAccountId() + "/default");
    }

    public synchronized ObjectNode getEnabledStandards(String region, JsonNode request) {
        requireSubscribed(region);
        return documentPage("StandardsSubscriptions", select(state(region).getStandardsSubscriptions(), request,
                "StandardsSubscriptionArns", 25), "StandardsSubscriptionArn", request);
    }

    public synchronized ObjectNode batchEnableStandards(String region, JsonNode request) {
        requireSubscribed(region);
        JsonNode subscriptions = requiredArray(request, "StandardsSubscriptionRequests", 25);
        for (JsonNode subscription : subscriptions) {
            if (!standardsArn(region).equals(text(subscription, "StandardsArn"))) {
                throw invalid("The specified standard is not available in this region's local catalog.");
            }
            if (subscription.has("StandardsInput") && !subscription.get("StandardsInput").isObject()) {
                throw invalid("StandardsInput must be an object.");
            }
        }
        SecurityHubState state = state(region);
        ObjectNode response = object();
        ArrayNode items = response.putArray("StandardsSubscriptions");
        for (JsonNode subscription : subscriptions) {
            items.add(enableStandard(region, state, subscription));
        }
        save(region, state);
        return response;
    }

    private ObjectNode enableStandard(String region, SecurityHubState state, JsonNode request) {
        String arn = resourceArn(region, "subscription/aws-foundational-security-best-practices/v/1.0.0");
        // Standards are metadata only; no control evaluations run.
        ObjectNode subscription = object().put("StandardsArn", standardsArn(region)).put("StandardsSubscriptionArn", arn)
                .put("StandardsStatus", "INCOMPLETE").put("StandardsControlsUpdatable", "NOT_READY_FOR_UPDATES");
        subscription.set("StandardsInput", request.has("StandardsInput") ? request.get("StandardsInput").deepCopy() : object());
        state.getStandardsSubscriptions().put(arn, subscription);
        return subscription.deepCopy();
    }

    public synchronized ObjectNode batchDisableStandards(String region, JsonNode request) {
        requireSubscribed(region);
        SecurityHubState state = state(region);
        List<JsonNode> subscriptions = select(state.getStandardsSubscriptions(), request, "StandardsSubscriptionArns", 25);
        requiredArray(request, "StandardsSubscriptionArns", 25);
        ObjectNode response = object();
        ArrayNode items = response.putArray("StandardsSubscriptions");
        for (JsonNode subscription : subscriptions) {
            state.getStandardsSubscriptions().remove(subscription.path("StandardsSubscriptionArn").asText());
            items.add(((ObjectNode) subscription.deepCopy()).put("StandardsStatus", "DELETING"));
        }
        save(region, state);
        return response;
    }

    public synchronized ObjectNode listSecurityControlDefinitions(String region, JsonNode request) {
        String standard = text(request, "StandardsArn");
        if (standard != null && !standard.equals(standardsArn(region))) {
            throw notFound("The specified standard was not found in the local catalog.");
        }
        return documentPage("SecurityControlDefinitions", List.of(controlDefinition("IAM.1")), "SecurityControlId", request);
    }

    public synchronized ObjectNode getSecurityControlDefinition(String id) {
        ObjectNode response = object();
        response.set("SecurityControlDefinition", controlDefinition(id));
        return response;
    }

    private static ObjectNode controlDefinition(String id) {
        if (id == null || id.isBlank()) {
            throw invalid("SecurityControlId is required.");
        }
        if (!"IAM.1".equals(id)) {
            throw notFound("The specified security control definition was not found in the local catalog.");
        }
        ObjectNode control = object().put("SecurityControlId", id)
                .put("Title", "IAM policies should not allow full \"*\" administrative privileges")
                .put("Description", "Checks whether IAM policies grant full administrative privileges.")
                .put("RemediationUrl", "https://docs.aws.amazon.com/securityhub/latest/userguide/iam-controls.html#iam-1")
                .put("SeverityRating", "HIGH").put("CurrentRegionAvailability", "AVAILABLE");
        control.putObject("ParameterDefinitions");
        return control;
    }

    public synchronized ObjectNode listEnabledProductsForImport(String region, JsonNode request) {
        requireSubscribed(region);
        PaginatedResult<String> page = Pagination.paginate(new ArrayList<>(state(region).getProductSubscriptions().keySet()),
                item -> item, integer(request, "MaxResults"), text(request, "NextToken"), 100, "InvalidInputException");
        ObjectNode response = object();
        ArrayNode items = response.putArray("ProductSubscriptions");
        page.items().forEach(items::add);
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public synchronized ObjectNode enableImportFindingsForProduct(String region, JsonNode request) {
        requireSubscribed(region);
        if (!defaultProductArn(region).equals(requireText(request, "ProductArn", "InvalidInputException"))) {
            throw notFound("The product was not found in the local catalog.");
        }
        String arn = resourceArn(region, "product-subscription/" + regionResolver.getAccountId() + "/default");
        SecurityHubState state = state(region);
        state.getProductSubscriptions().put(arn, request.deepCopy());
        save(region, state);
        return object().put("ProductSubscriptionArn", arn);
    }

    public synchronized void disableImportFindingsForProduct(String region, String arn) {
        requireSubscribed(region);
        SecurityHubState state = state(region);
        requiredDocument(state.getProductSubscriptions(), arn);
        state.getProductSubscriptions().remove(arn);
        save(region, state);
    }

    public synchronized ObjectNode listMembers(String region, JsonNode request) {
        requireSubscribed(region);
        if (request.has("OnlyAssociated") && !request.get("OnlyAssociated").isBoolean()) {
            throw invalid("OnlyAssociated must be a boolean.");
        }
        boolean associated = request.path("OnlyAssociated").asBoolean(true);
        List<JsonNode> members = state(region).getMembers().values().stream()
                .filter(member -> !associated || "Enabled".equals(member.path("MemberStatus").asText())).toList();
        return documentPage("Members", members, "AccountId", request);
    }

    public synchronized ObjectNode createMembers(String region, JsonNode request) {
        requireSubscribed(region);
        SecurityHubState state = state(region);
        ObjectNode response = object();
        ArrayNode errors = response.putArray("UnprocessedAccounts");
        for (JsonNode detail : requiredArray(request, "AccountDetails", 50)) {
            String id = text(detail, "AccountId");
            try {
                requireAccountId(id, "AccountId");
                if (regionResolver.getAccountId().equals(id)) {
                    throw invalid("An account cannot be its own member.");
                }
                String email = boundedText(detail, "Email", 320);
                if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
                    throw invalid("Email must be a valid email address.");
                }
                if (!state.getMembers().containsKey(id)) {
                    state.getMembers().put(id, object().put("AccountId", id).put("Email", email)
                            .put("AdministratorId", regionResolver.getAccountId()).put("MemberStatus", "Created")
                            .put("UpdatedAt", Instant.now().toString()));
                }
            } catch (AwsException e) {
                errors.addObject().put("AccountId", id).put("ProcessingResult", e.getMessage());
            }
        }
        save(region, state);
        return response;
    }

    public synchronized ObjectNode memberBatch(String region, JsonNode request, boolean delete) {
        requireSubscribed(region);
        SecurityHubState state = state(region);
        ObjectNode response = object();
        ArrayNode errors = response.putArray("UnprocessedAccounts");
        ArrayNode members = delete ? null : response.putArray("Members");
        for (JsonNode id : requiredArray(request, "AccountIds", 50)) {
            requireAccountId(id.asText(null), "AccountIds");
            JsonNode member = state.getMembers().get(id.textValue());
            if (member == null) {
                errors.addObject().put("AccountId", id.textValue()).put("ProcessingResult", "Member not found.");
            } else if (delete) {
                state.getMembers().remove(id.textValue());
            } else {
                members.add(member.<JsonNode>deepCopy());
            }
        }
        if (delete) {
            save(region, state);
        }
        return response;
    }

    public synchronized ObjectNode listInvitations(String region, JsonNode request) {
        return documentPage("Invitations", new ArrayList<>(state(region).getInvitations().values()), "InvitationId", request);
    }

    public synchronized ObjectNode getInvitationsCount(String region) {
        return object().put("InvitationsCount", state(region).getInvitations().size());
    }

    public synchronized ObjectNode getAdministratorAccount(String region) {
        requireSubscribed(region);
        ObjectNode response = object();
        JsonNode administrator = state(region).getAdministrator();
        if (administrator != null) {
            response.set("Administrator", administrator.deepCopy());
        }
        return response;
    }

    private String resourceArn(String region, String suffix) {
        return "arn:aws:securityhub:" + region + ":" + regionResolver.getAccountId() + ":" + suffix;
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static ObjectNode requiredDocument(Map<String, JsonNode> documents, String key) {
        JsonNode document = key == null ? null : documents.get(key);
        if (!(document instanceof ObjectNode result)) {
            throw notFound("The specified Security Hub resource was not found.");
        }
        return result;
    }

    private static JsonNode requiredArray(JsonNode request, String field, int maximum) {
        JsonNode items = request == null ? null : request.get(field);
        if (items == null || !items.isArray() || items.isEmpty() || items.size() > maximum) {
            throw invalid(field + " must contain between 1 and " + maximum + " entries.");
        }
        return items;
    }

    private static String boundedText(JsonNode request, String field, int maximum) {
        String value = requireText(request, field, "InvalidInputException");
        if (value.length() > maximum) {
            throw invalid(field + " exceeds the maximum length of " + maximum + ".");
        }
        return value;
    }

    private static List<JsonNode> select(Map<String, JsonNode> documents, JsonNode request, String field, int maximum) {
        if (!request.has(field)) {
            return new ArrayList<>(documents.values());
        }
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode identifier : requiredArray(request, field, maximum)) {
            if (!identifier.isTextual()) {
                throw invalid(field + " must contain strings.");
            }
            result.add(requiredDocument(documents, identifier.textValue()));
        }
        return result;
    }

    private static ObjectNode documentPage(String field, List<JsonNode> documents, String key, JsonNode request) {
        PaginatedResult<JsonNode> page = Pagination.paginate(documents, item -> item.path(key).asText(),
                integer(request, "MaxResults"), text(request, "NextToken"), 100, "InvalidInputException");
        ObjectNode response = object();
        ArrayNode items = response.putArray(field);
        page.items().forEach(item -> items.add(item.<JsonNode>deepCopy()));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public String normalizeFeature(String feature) {
        String requestedFeature = feature == null || feature.isBlank() ? "SecurityHub" : feature;
        if (!"SecurityHub".equals(requestedFeature) && !"SecurityHubV2".equals(requestedFeature)) {
            throw invalid("Feature must be SecurityHub or SecurityHubV2.");
        }
        return requestedFeature;
    }

    public synchronized SecurityHubState createFindingAggregator(String region, JsonNode request) {
        requireEnabled(region);
        SecurityHubState state = state(region);
        if (state.getAggregatorArn() != null) {
            throw new AwsException("LimitExceededException",
                    "Only one finding aggregator can exist for an account.", 429);
        }
        String mode = requireLinkingMode(request);
        JsonNode regions = validateAggregatorRegions(mode, request.get("Regions"));
        state.setAggregatorArn("arn:aws:securityhub:" + region + ":" + regionResolver.getAccountId()
                + ":finding-aggregator/" + UUID.randomUUID());
        state.setRegionLinkingMode(mode);
        state.setRegions(regions);
        save(region, state);
        return state;
    }

    public SecurityHubState getFindingAggregator(String region, String findingAggregatorArn) {
        SecurityHubState state = state(region);
        if (state.getAggregatorArn() == null || findingAggregatorArn == null
                || !state.getAggregatorArn().equals(findingAggregatorArn)) {
            throw notFound("The finding aggregator was not found.");
        }
        return state;
    }

    public synchronized SecurityHubState updateFindingAggregator(String region, JsonNode request) {
        requireEnabled(region);
        SecurityHubState state = state(region);
        String arn = requireText(request, "FindingAggregatorArn", "InvalidInputException");
        if (state.getAggregatorArn() == null || !state.getAggregatorArn().equals(arn)) {
            throw notFound("The finding aggregator was not found.");
        }
        String mode = requireLinkingMode(request);
        JsonNode regions = validateAggregatorRegions(mode, request.get("Regions"));
        state.setRegionLinkingMode(mode);
        state.setRegions(regions);
        save(region, state);
        return state;
    }

    public synchronized void updateOrganizationConfiguration(String region, JsonNode request) {
        SecurityHubState state = requireAdministrator(region);
        JsonNode autoEnable = request.get("AutoEnable");
        if (autoEnable == null || !autoEnable.isBoolean()) {
            throw invalid("AutoEnable is required.");
        }
        String autoStandards = text(request, "AutoEnableStandards");
        if (autoStandards != null && !"DEFAULT".equals(autoStandards) && !"NONE".equals(autoStandards)) {
            throw invalid("AutoEnableStandards must be DEFAULT or NONE.");
        }
        JsonNode organizationConfiguration = request.get("OrganizationConfiguration");
        String type = organizationConfiguration != null && organizationConfiguration.isObject()
                ? text(organizationConfiguration, "ConfigurationType") : null;
        if (type != null && !"CENTRAL".equals(type) && !"LOCAL".equals(type)) {
            throw invalid("ConfigurationType must be CENTRAL or LOCAL.");
        }
        String desiredType = type == null ? state.getOrganizationConfigurationType() : type;
        if (desiredType == null) {
            desiredType = "LOCAL";
        }
        state.setOrganizationConfigurationType(desiredType);
        if ("CENTRAL".equals(desiredType)) {
            state.setAutoEnable(false);
            state.setAutoEnableStandards("NONE");
            state.setOrganizationConfigurationStatus("PENDING");
            state.setOrganizationConfigurationPendingPollsRemaining(1);
        } else {
            state.setAutoEnable(autoEnable.booleanValue());
            if (autoStandards != null) {
                state.setAutoEnableStandards(autoStandards);
            }
            state.setOrganizationConfigurationStatus("ENABLED");
            state.setOrganizationConfigurationPendingPollsRemaining(0);
        }
        save(region, state);
    }

    public synchronized SecurityHubState organizationConfiguration(String region) {
        SecurityHubState state = requireAdministrator(region);
        if ("PENDING".equals(state.getOrganizationConfigurationStatus())
                && state.getOrganizationConfigurationPendingPollsRemaining() > 0) {
            SecurityHubState response = copyConfigurationState(state);
            state.setOrganizationConfigurationPendingPollsRemaining(
                    state.getOrganizationConfigurationPendingPollsRemaining() - 1);
            save(region, state);
            return response;
        }
        if ("PENDING".equals(state.getOrganizationConfigurationStatus())) {
            state.setOrganizationConfigurationStatus("ENABLED");
            save(region, state);
        }
        return state;
    }

    public synchronized String createConfigurationPolicy(String region, JsonNode request) {
        SecurityHubState state = requireAdministrator(region);
        String name = requireText(request, "Name", "InvalidInputException");
        validatePolicyName(name);
        boolean duplicate = state.getPolicies().values().stream()
                .anyMatch(policy -> name.equals(text(policy, "Name")));
        if (duplicate) {
            throw conflict("A configuration policy with the same name already exists.");
        }
        validateConfigurationPolicy(request.get("ConfigurationPolicy"));
        validateTags(request.get("Tags"));
        String id = UUID.randomUUID().toString();
        ObjectNode stored = (ObjectNode) request.deepCopy();
        String now = java.time.Instant.now().toString();
        stored.put("CreatedAt", now);
        stored.put("UpdatedAt", now);
        state.getPolicies().put(id, stored);
        save(region, state);
        return id;
    }

    public JsonNode getConfigurationPolicy(String region, String identifier) {
        String id = normalizePolicyId(identifier);
        JsonNode policy = requireAdministrator(region).getPolicies().get(id);
        if (policy == null) {
            throw notFound("The configuration policy was not found.");
        }
        return policy;
    }

    public synchronized JsonNode updateConfigurationPolicy(String region, String identifier, JsonNode request) {
        String id = normalizePolicyId(identifier);
        SecurityHubState state = requireAdministrator(region);
        JsonNode current = state.getPolicies().get(id);
        if (current == null) {
            throw notFound("The configuration policy was not found.");
        }
        String newName = text(request, "Name");
        if (newName != null) {
            validatePolicyName(newName);
            boolean duplicate = state.getPolicies().entrySet().stream()
                    .anyMatch(entry -> !entry.getKey().equals(id)
                            && newName.equals(text(entry.getValue(), "Name")));
            if (duplicate) {
                throw conflict("A configuration policy with the same name already exists.");
            }
        }
        ObjectNode merged = (ObjectNode) current.deepCopy();
        if (newName != null) {
            merged.put("Name", newName);
        }
        if (request.has("Description")) {
            merged.set("Description", request.get("Description"));
        }
        if (request.has("ConfigurationPolicy")) {
            validateConfigurationPolicy(request.get("ConfigurationPolicy"));
            merged.set("ConfigurationPolicy", request.get("ConfigurationPolicy").deepCopy());
        }
        merged.put("UpdatedAt", java.time.Instant.now().toString());
        state.getPolicies().put(id, merged);
        save(region, state);
        return merged;
    }

    public synchronized SecurityHubAssociation associate(String region, JsonNode request) {
        SecurityHubState state = requireAdministrator(region);
        TargetRef target = organizationTarget(request);
        String policyIdentifier = requireText(request, "ConfigurationPolicyIdentifier", "InvalidInputException");
        String policyId = normalizePolicyId(policyIdentifier);
        if (!SELF_MANAGED.equals(policyId) && !state.getPolicies().containsKey(policyId)) {
            throw notFound("The configuration policy was not found.");
        }
        SecurityHubAssociation existing = state.getAssociations().get(target.id());
        if (existing != null && !existing.isDisassociating()
                && policyId.equals(existing.getPolicyId())) {
            throw conflict("The target is already associated with the specified configuration policy.");
        }
        SecurityHubAssociation association = new SecurityHubAssociation(
                target.id(), target.type(), policyId, "PENDING", 1);
        state.getAssociations().put(target.id(), association);
        save(region, state);
        return association.copy();
    }

    public synchronized SecurityHubAssociation association(String region, JsonNode request) {
        SecurityHubState state = requireAdministrator(region);
        TargetRef target = organizationTarget(request);
        SecurityHubAssociation association = state.getAssociations().get(target.id());
        if (association == null) {
            throw notFound("The configuration policy association was not found.");
        }
        if ("PENDING".equals(association.getStatus()) && association.getPendingPollsRemaining() > 0) {
            SecurityHubAssociation response = association.copy();
            association.setPendingPollsRemaining(association.getPendingPollsRemaining() - 1);
            save(region, state);
            return response;
        }
        if ("PENDING".equals(association.getStatus())) {
            if (association.isDisassociating()) {
                state.getAssociations().remove(target.id());
                save(region, state);
                throw notFound("The configuration policy association was not found.");
            }
            association.setStatus("SUCCESS");
            association.setUpdatedAt(java.time.Instant.now().toString());
            applyPolicyToAccountTarget(region, state, association);
            save(region, state);
        }
        return association.copy();
    }

    public synchronized void disassociate(String region, JsonNode request) {
        SecurityHubState state = requireAdministrator(region);
        TargetRef target = organizationTarget(request);
        String policyIdentifier = requireText(request, "ConfigurationPolicyIdentifier", "InvalidInputException");
        String requestedPolicyId = normalizePolicyId(policyIdentifier);
        SecurityHubAssociation association = state.getAssociations().get(target.id());
        if (association == null || !requestedPolicyId.equals(association.getPolicyId())) {
            throw notFound("The configuration policy association was not found.");
        }
        if (association.isDisassociating()) {
            throw conflict("The configuration policy association is already being removed.");
        }
        association.setStatus("PENDING");
        association.setUpdatedAt(java.time.Instant.now().toString());
        association.setPendingPollsRemaining(1);
        association.setDisassociating(true);
        save(region, state);
    }

    private void applyPolicyToAccountTarget(String region, SecurityHubState administrator,
                                            SecurityHubAssociation association) {
        if (!"ACCOUNT".equals(association.getTargetType()) || SELF_MANAGED.equals(association.getPolicyId())) {
            return;
        }
        JsonNode policy = administrator.getPolicies().get(association.getPolicyId());
        JsonNode securityHub = policy == null ? null : policy.path("ConfigurationPolicy").path("SecurityHub");
        if (securityHub == null || !securityHub.isObject() || !securityHub.path("ServiceEnabled").isBoolean()) {
            return;
        }
        SecurityHubState target = states.getForAccount(association.getTargetId(), region)
                .orElseGet(SecurityHubState::new);
        target.setEnabled(securityHub.path("ServiceEnabled").asBoolean());
        states.putForAccount(association.getTargetId(), region, target);
    }

    public List<SecurityHubAssociation> associations(String region) {
        return requireAdministrator(region).getAssociations().values().stream()
                .map(SecurityHubAssociation::copy)
                .toList();
    }

    public PaginatedResult<SecurityHubAssociation> associationPage(String region, JsonNode request) {
        JsonNode filters = request == null ? null : request.get("Filters");
        if (filters != null && !filters.isObject()) {
            throw invalid("Filters must be an object.");
        }
        String policyId = filters == null ? null : text(filters, "ConfigurationPolicyId");
        String associationType = filters == null ? null : text(filters, "AssociationType");
        String associationStatus = filters == null ? null : text(filters, "AssociationStatus");
        if (associationType != null && !"APPLIED".equals(associationType) && !"INHERITED".equals(associationType)) {
            throw invalid("AssociationType must be APPLIED or INHERITED.");
        }
        if (associationStatus != null && !List.of("PENDING", "SUCCESS", "FAILED").contains(associationStatus)) {
            throw invalid("AssociationStatus must be PENDING, SUCCESS, or FAILED.");
        }
        Integer maxResults = integer(request, "MaxResults");
        String nextToken = text(request, "NextToken");
        List<SecurityHubAssociation> filtered = associations(region).stream()
                .filter(item -> policyId == null || normalizePolicyId(policyId).equals(item.getPolicyId()))
                .filter(item -> associationType == null || "APPLIED".equals(associationType))
                .filter(item -> associationStatus == null || associationStatus.equals(item.getStatus()))
                .toList();
        return Pagination.paginate(filtered,
                item -> item.getTargetType() + "\u0000" + item.getTargetId(),
                maxResults, nextToken, 100, 100, "InvalidInputException");
    }

    public List<Map.Entry<String, JsonNode>> policies(String region) {
        return List.copyOf(requireAdministrator(region).getPolicies().entrySet());
    }

    public PaginatedResult<Map.Entry<String, JsonNode>> policyPage(String region, Integer maxResults,
                                                                   String nextToken) {
        return Pagination.paginate(policies(region), Map.Entry::getKey,
                maxResults, nextToken, 100, 100, "InvalidInputException");
    }

    public Map<String, String> tagsForResource(String region, String arn) {
        SecurityHubState state = state(region);
        validateResourceArn(region, arn);
        if (arn.equals(hubArn(region))) {
            if (!state.isEnabled()) {
                throw notFound("The specified Security Hub resource was not found.");
            }
            return Map.copyOf(state.getHubTags());
        }
        JsonNode resource = taggedPolicy(state, region, arn);
        JsonNode tags = resource.get("Tags");
        if (tags == null || !tags.isObject()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        tags.fields().forEachRemaining(entry -> {
            if (entry.getValue().isTextual()) {
                result.put(entry.getKey(), entry.getValue().textValue());
            }
        });
        return result;
    }

    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        SecurityHubState state = state(region);
        validateResourceArn(region, arn);
        if (arn.equals(hubArn(region))) {
            if (!state.isEnabled()) {
                throw notFound("The specified Security Hub resource was not found.");
            }
            Map<String, String> merged = new LinkedHashMap<>(state.getHubTags());
            merged.putAll(tags);
            validateTags(merged);
            state.setHubTags(merged);
            save(region, state);
            return;
        }
        ObjectNode resource = policyResource(state, region, arn);
        ObjectNode current = resource.withObject("Tags");
        tags.forEach(current::put);
        validateTags(current);
        save(region, state);
    }

    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        SecurityHubState state = state(region);
        validateResourceArn(region, arn);
        if (arn.equals(hubArn(region))) {
            if (!state.isEnabled()) {
                throw notFound("The specified Security Hub resource was not found.");
            }
            Map<String, String> updated = new LinkedHashMap<>(state.getHubTags());
            tagKeys.forEach(updated::remove);
            state.setHubTags(updated);
            save(region, state);
            return;
        }
        ObjectNode resource = policyResource(state, region, arn);
        ObjectNode tags = resource.withObject("Tags");
        tagKeys.forEach(tags::remove);
        save(region, state);
    }

    private JsonNode taggedPolicy(SecurityHubState state, String region, String arn) {
        validateResourceArn(region, arn);
        if (arn.startsWith(resourceArn(region, "automation-rule/"))) {
            return requiredDocument(state.getAutomationRules(), arn);
        }
        if (!arn.startsWith(resourceArn(region, "configuration-policy/"))) {
            throw notFound("The specified Security Hub resource was not found.");
        }
        String id = normalizePolicyId(arn);
        JsonNode policy = state.getPolicies().get(id);
        if (policy == null) {
            throw notFound("The specified Security Hub resource was not found.");
        }
        return policy;
    }

    private void validateResourceArn(String region, String arn) {
        if (arn == null || !arn.startsWith("arn:aws:securityhub:" + region + ":" + regionResolver.getAccountId() + ":")) {
            throw notFound("The specified Security Hub resource was not found.");
        }
    }

    private ObjectNode policyResource(SecurityHubState state, String region, String arn) {
        JsonNode resource = taggedPolicy(state, region, arn);
        if (!(resource instanceof ObjectNode object)) {
            throw notFound("The specified Security Hub resource does not support mutable tags in this emulator.");
        }
        return object;
    }

    public String hubArn(String region) {
        return "arn:aws:securityhub:" + region + ":" + regionResolver.getAccountId() + ":hub/default";
    }

    public String policyArn(String region, String id) {
        return "arn:aws:securityhub:" + region + ":" + regionResolver.getAccountId()
                + ":configuration-policy/" + id;
    }

    private static SecurityHubState copyConfigurationState(SecurityHubState source) {
        SecurityHubState copy = new SecurityHubState();
        copy.setAdminAccountId(source.getAdminAccountId());
        copy.setAdminFeature(source.getAdminFeature());
        copy.setEnabled(source.isEnabled());
        copy.setAutoEnable(source.isAutoEnable());
        copy.setAutoEnableStandards(source.getAutoEnableStandards());
        copy.setOrganizationConfigurationType(source.getOrganizationConfigurationType());
        copy.setOrganizationConfigurationStatus(source.getOrganizationConfigurationStatus());
        return copy;
    }

    private static JsonNode validateAggregatorRegions(String mode, JsonNode regions) {
        if (regions != null && !regions.isArray()) {
            throw invalid("Regions must be an array.");
        }
        if ("SPECIFIED_REGIONS".equals(mode) && (regions == null || regions.isEmpty())) {
            throw invalid("Regions is required when RegionLinkingMode is SPECIFIED_REGIONS.");
        }
        if (("NO_REGIONS".equals(mode) || "ALL_REGIONS".equals(mode))
                && regions != null && !regions.isEmpty()) {
            throw invalid("Regions must be empty for the selected RegionLinkingMode.");
        }
        if (regions != null) {
            for (JsonNode candidate : regions) {
                if (!candidate.isTextual() || candidate.textValue().isBlank()) {
                    throw invalid("Regions must contain non-blank Region identifiers.");
                }
            }
        }
        return regions == null ? null : regions.deepCopy();
    }

    private static String requireLinkingMode(JsonNode request) {
        String mode = requireText(request, "RegionLinkingMode", "InvalidInputException");
        if (!LINKING_MODES.contains(mode)) {
            throw invalid("RegionLinkingMode is invalid.");
        }
        return mode;
    }

    private static void validateTags(JsonNode tags) {
        if (tags == null || tags.isNull()) {
            return;
        }
        if (!tags.isObject() || tags.size() > 50) {
            throw invalid("Tags must be an object with at most 50 entries.");
        }
        tags.fields().forEachRemaining(entry -> validateTag(entry.getKey(), entry.getValue()));
    }

    private static void validateTags(Map<String, String> tags) {
        if (tags.size() > 50) {
            throw invalid("Tags must contain at most 50 entries.");
        }
        tags.forEach((key, value) -> validateTag(
                key, value == null ? null : new com.fasterxml.jackson.databind.node.TextNode(value)));
    }

    private static void validateTag(String key, JsonNode value) {
        if (key == null || key.isBlank() || key.length() > 128 || key.startsWith("aws:")
                || !key.matches("[a-zA-Z+\\-=._:/]+")
                || value == null || !value.isTextual() || value.textValue().length() > 256) {
            throw invalid("Tags contain an invalid key or value.");
        }
    }

    private static Map<String, String> readTags(JsonNode tags) {
        if (tags == null || tags.isNull()) {
            return new LinkedHashMap<>();
        }
        validateTags(tags);
        Map<String, String> result = new LinkedHashMap<>();
        tags.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().textValue()));
        return result;
    }

    private SecurityHubState requireAdministrator(String region) {
        requireEnabled(region);
        SecurityHubState state = state(region);
        if (state.getAdminAccountId() == null || !regionResolver.getAccountId().equals(state.getAdminAccountId())) {
            throw new AwsException("InvalidAccessException",
                    "Only the delegated Security Hub administrator account can manage central configuration.", 401);
        }
        return state;
    }

    private void save(String region, SecurityHubState state) {
        states.put(region, state);
    }

    private static String normalizePolicyId(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw invalid("Identifier is required.");
        }
        if (SELF_MANAGED.equals(identifier)) {
            return SELF_MANAGED;
        }
        int slash = identifier.lastIndexOf('/');
        return slash >= 0 ? identifier.substring(slash + 1) : identifier;
    }

    private String requireCallerManagementAccount() {
        String callerAccountId = regionResolver.getAccountId();
        String managementAccountId = organizationsService.findManagementAccountForResource(callerAccountId)
                .orElseThrow(() -> new AwsException("AccessDeniedException",
                        "Only the organization management account can perform this operation.", 403));
        if (!callerAccountId.equals(managementAccountId)) {
            throw new AwsException("AccessDeniedException",
                    "Only the organization management account can perform this operation.", 403);
        }
        return managementAccountId;
    }

    private void requireOrganizationManagementAccount(String targetAccountId) {
        String managementAccountId = requireCallerManagementAccount();
        String targetManagementAccountId = organizationsService.findManagementAccountForResource(targetAccountId)
                .orElseThrow(() -> invalid("AdminAccountId must belong to the caller's AWS organization."));
        if (!managementAccountId.equals(targetManagementAccountId)) {
            throw invalid("AdminAccountId must belong to the caller's AWS organization.");
        }
    }

    private TargetRef organizationTarget(JsonNode input) {
        TargetRef target = target(input);
        String callerAccountId = regionResolver.getAccountId();
        String managementAccountId = organizationsService.findManagementAccountForResource(callerAccountId)
                .orElseThrow(() -> new AwsException("InvalidAccessException",
                        "The delegated administrator must belong to an AWS organization.", 401));
        String targetManagementAccountId = organizationsService.findManagementAccountForResource(target.id())
                .orElseThrow(() -> notFound("The specified organization target was not found."));
        if (!managementAccountId.equals(targetManagementAccountId)) {
            throw notFound("The specified organization target was not found.");
        }
        return target;
    }

    private static void validatePolicyName(String name) {
        if (name == null || name.isBlank()) {
            throw invalid("Name must contain a non-whitespace character.");
        }
        if (name.length() > 128) {
            throw invalid("Name exceeds the maximum length.");
        }
    }

    private static void validateConfigurationPolicy(JsonNode configurationPolicy) {
        if (configurationPolicy == null || !configurationPolicy.isObject()
                || configurationPolicy.size() != 1 || !configurationPolicy.has("SecurityHub")) {
            throw invalid("ConfigurationPolicy must contain exactly one SecurityHub policy.");
        }
        JsonNode securityHub = configurationPolicy.get("SecurityHub");
        if (securityHub == null || !securityHub.isObject()) {
            throw invalid("ConfigurationPolicy.SecurityHub must be an object.");
        }
        JsonNode serviceEnabled = securityHub.get("ServiceEnabled");
        if (serviceEnabled != null && !serviceEnabled.isBoolean()) {
            throw invalid("ConfigurationPolicy.SecurityHub.ServiceEnabled must be a boolean.");
        }
        JsonNode standards = securityHub.get("EnabledStandardIdentifiers");
        if (standards != null) {
            if (!standards.isArray()) {
                throw invalid("EnabledStandardIdentifiers must be an array.");
            }
            for (JsonNode standard : standards) {
                if (!standard.isTextual() || standard.textValue().isBlank()) {
                    throw invalid("EnabledStandardIdentifiers must contain non-blank strings.");
                }
            }
        }
        JsonNode controls = securityHub.get("SecurityControlsConfiguration");
        if (controls != null && !controls.isObject()) {
            throw invalid("SecurityControlsConfiguration must be an object.");
        }
    }

    private static TargetRef target(JsonNode input) {
        JsonNode target = input == null ? null : input.get("Target");
        if (target == null || !target.isObject()) {
            throw invalid("Target is required.");
        }
        TargetRef found = null;
        for (Map.Entry<String, String> candidate : Map.of(
                "AccountId", "ACCOUNT", "OrganizationalUnitId", "ORGANIZATIONAL_UNIT", "RootId", "ROOT").entrySet()) {
            String value = text(target, candidate.getKey());
            if (value != null) {
                if (found != null) {
                    throw invalid("Target must contain exactly one identifier.");
                }
                found = new TargetRef(value, candidate.getValue());
            }
        }
        if (found == null) {
            throw invalid("Target identifier is required.");
        }
        if ("ACCOUNT".equals(found.type()) && !found.id().matches("\\d{12}")) {
            throw invalid("AccountId must be a 12 digit account ID.");
        }
        if ("ORGANIZATIONAL_UNIT".equals(found.type()) && !found.id().matches("ou-[a-z0-9]{4,32}-[a-z0-9]{8,32}")) {
            throw invalid("OrganizationalUnitId is invalid.");
        }
        if ("ROOT".equals(found.type()) && !found.id().matches("r-[a-z0-9]{4,32}")) {
            throw invalid("RootId is invalid.");
        }
        return found;
    }

    private static void requireAccountId(String value, String field) {
        if (value == null || !value.matches("\\d{12}")) {
            throw invalid(field + " must be a 12 digit account ID.");
        }
    }

    private static String requireText(JsonNode input, String field, String errorCode) {
        String value = text(input, field);
        if (value == null || value.isBlank()) {
            throw new AwsException(errorCode, field + " is required.", 400);
        }
        return value;
    }

    private static String text(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private static Integer integer(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalid(field + " must be an integer.");
        }
        return node.intValue();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }

    private static AwsException invalidAccess(String message) {
        return new AwsException("InvalidAccessException", message, 401);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ResourceConflictException", message, 409);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    @Override
    public void clear() {
        states.clear();
    }

    private record TargetRef(String id, String type) {}
}
