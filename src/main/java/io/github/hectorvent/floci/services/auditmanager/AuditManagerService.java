package io.github.hectorvent.floci.services.auditmanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.auditmanager.model.Assessment;
import io.github.hectorvent.floci.services.auditmanager.model.Control;
import io.github.hectorvent.floci.services.auditmanager.model.Framework;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AWS Audit Manager restJson1 — account registration plus custom control, framework,
 * and assessment lifecycle.
 *
 * <p>Accounts default to {@code ACTIVE} so local stacks can create resources without the
 * live-AWS RegisterAccount maintenance-mode gate. Resources are isolated by account
 * (storage decorator) and region.
 */
@ApplicationScoped
public class AuditManagerService implements TagHandler {

    static final String SERVICE = "auditmanager";
    private static final String RESOURCE_CONTROL = "AWS::AuditManager::Control";
    private static final String RESOURCE_FRAMEWORK = "AWS::AuditManager::AssessmentFramework";
    private static final String RESOURCE_ASSESSMENT = "AWS::AuditManager::Assessment";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String ACCOUNT_KEY = "status";
    private static final String TOKEN_PREFIX = "auditmanager:v1:";
    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int MAX_RESULTS = 1000;
    private static final Set<String> CONTROL_TYPES = Set.of("Standard", "Custom", "Core");
    private static final Set<String> FRAMEWORK_TYPES = Set.of("Standard", "Custom");
    private static final Set<String> ASSESSMENT_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> DATA_SOURCES = Set.of(
            "AWS_Cloudtrail", "AWS_Config", "AWS_Security_Hub", "AWS_API_Call", "MANUAL");

    private final StorageBackend<String, String> accounts;
    private final StorageBackend<String, Control> controls;
    private final StorageBackend<String, Framework> frameworks;
    private final StorageBackend<String, Assessment> assessments;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public AuditManagerService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(
                storageFactory.create(
                        "auditmanager",
                        "auditmanager-accounts.json",
                        new TypeReference<Map<String, String>>() {
                        }),
                storageFactory.create(
                        "auditmanager",
                        "auditmanager-controls.json",
                        new TypeReference<Map<String, Control>>() {
                        }),
                storageFactory.create(
                        "auditmanager",
                        "auditmanager-frameworks.json",
                        new TypeReference<Map<String, Framework>>() {
                        }),
                storageFactory.create(
                        "auditmanager",
                        "auditmanager-assessments.json",
                        new TypeReference<Map<String, Assessment>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    AuditManagerService(
            StorageBackend<String, String> accounts,
            StorageBackend<String, Control> controls,
            StorageBackend<String, Framework> frameworks,
            StorageBackend<String, Assessment> assessments,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.accounts = accounts;
        this.controls = controls;
        this.frameworks = frameworks;
        this.assessments = assessments;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public String getAccountStatus() {
        return accounts.get(ACCOUNT_KEY).orElse(STATUS_ACTIVE);
    }

    public synchronized String registerAccount() {
        accounts.put(ACCOUNT_KEY, STATUS_ACTIVE);
        return STATUS_ACTIVE;
    }

    public synchronized String deregisterAccount() {
        accounts.put(ACCOUNT_KEY, STATUS_INACTIVE);
        return STATUS_INACTIVE;
    }

    public synchronized Control createControl(String region, JsonNode request) {
        requireActive();
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        JsonNode sources = requireArray(request, "controlMappingSources");
        if (sources.isEmpty()) {
            throw validation("controlMappingSources must contain at least one source.");
        }
        long now = nowSeconds();
        String id = newId();
        Control control = new Control();
        control.setId(id);
        control.setArn(arn(region, "control/" + id));
        control.setName(name);
        control.setDescription(optionalText(request, "description"));
        control.setTestingInformation(optionalText(request, "testingInformation"));
        control.setActionPlanTitle(optionalText(request, "actionPlanTitle"));
        control.setActionPlanInstructions(optionalText(request, "actionPlanInstructions"));
        control.setType("Custom");
        control.setState(STATUS_ACTIVE);
        control.setControlMappingSources(assignSourceIds(sources));
        control.setControlSources(controlSources(control.getControlMappingSources()));
        control.setCreatedAt(now);
        control.setLastUpdatedAt(now);
        control.setCreatedBy(callerPrincipal());
        control.setLastUpdatedBy(callerPrincipal());
        control.setTags(readTags(request.get("tags")));
        controls.put(storageKey(region, id), control);
        return control;
    }

    public Control getControl(String region, String controlId) {
        requireActive();
        return requireControl(region, controlId);
    }

    public synchronized Control updateControl(String region, String controlId, JsonNode request) {
        requireActive();
        requireObject(request, "Request body");
        Control control = requireControl(region, controlId);
        control.setName(requireText(request, "name"));
        if (request.has("description")) {
            control.setDescription(optionalText(request, "description"));
        }
        if (request.has("testingInformation")) {
            control.setTestingInformation(optionalText(request, "testingInformation"));
        }
        if (request.has("actionPlanTitle")) {
            control.setActionPlanTitle(optionalText(request, "actionPlanTitle"));
        }
        if (request.has("actionPlanInstructions")) {
            control.setActionPlanInstructions(optionalText(request, "actionPlanInstructions"));
        }
        JsonNode sources = requireArray(request, "controlMappingSources");
        if (sources.isEmpty()) {
            throw validation("controlMappingSources must contain at least one source.");
        }
        control.setControlMappingSources(assignSourceIds(sources));
        control.setControlSources(controlSources(control.getControlMappingSources()));
        control.setLastUpdatedAt(nowSeconds());
        control.setLastUpdatedBy(callerPrincipal());
        controls.put(storageKey(region, control.getId()), control);
        return control;
    }

    public synchronized void deleteControl(String region, String controlId) {
        requireActive();
        Control control = requireControl(region, controlId);
        controls.delete(storageKey(region, control.getId()));
    }

    public Page<Control> listControls(String region, String controlType, String maxResultsValue, String nextToken) {
        requireActive();
        if (controlType == null || controlType.isBlank()) {
            throw validation("controlType is a required parameter.");
        }
        if (!CONTROL_TYPES.contains(controlType)) {
            throw validation("controlType is invalid.");
        }
        List<Control> items = controls.scan(key -> key.startsWith(region + "::"));
        items.removeIf(control -> !controlType.equals(control.getType()));
        items.sort(Comparator.comparing(Control::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResultsValue, nextToken);
    }

    public synchronized Framework createFramework(String region, JsonNode request) {
        requireActive();
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        JsonNode controlSets = requireArray(request, "controlSets");
        if (controlSets.isEmpty()) {
            throw validation("controlSets must contain at least one control set.");
        }
        long now = nowSeconds();
        String id = newId();
        Framework framework = new Framework();
        framework.setId(id);
        framework.setArn(arn(region, "assessmentFramework/" + id));
        framework.setName(name);
        framework.setDescription(optionalText(request, "description"));
        framework.setComplianceType(optionalText(request, "complianceType"));
        framework.setType("Custom");
        framework.setControlSets(assignControlSetIds(controlSets));
        framework.setCreatedAt(now);
        framework.setLastUpdatedAt(now);
        framework.setCreatedBy(callerPrincipal());
        framework.setLastUpdatedBy(callerPrincipal());
        framework.setTags(readTags(request.get("tags")));
        frameworks.put(storageKey(region, id), framework);
        return framework;
    }

    public Framework getFramework(String region, String frameworkId) {
        requireActive();
        return requireFramework(region, frameworkId);
    }

    public synchronized Framework updateFramework(String region, String frameworkId, JsonNode request) {
        requireActive();
        requireObject(request, "Request body");
        Framework framework = requireFramework(region, frameworkId);
        framework.setName(requireText(request, "name"));
        if (request.has("description")) {
            framework.setDescription(optionalText(request, "description"));
        }
        if (request.has("complianceType")) {
            framework.setComplianceType(optionalText(request, "complianceType"));
        }
        JsonNode controlSets = requireArray(request, "controlSets");
        if (controlSets.isEmpty()) {
            throw validation("controlSets must contain at least one control set.");
        }
        framework.setControlSets(assignControlSetIds(controlSets));
        framework.setLastUpdatedAt(nowSeconds());
        framework.setLastUpdatedBy(callerPrincipal());
        frameworks.put(storageKey(region, framework.getId()), framework);
        return framework;
    }

    public synchronized void deleteFramework(String region, String frameworkId) {
        requireActive();
        Framework framework = requireFramework(region, frameworkId);
        frameworks.delete(storageKey(region, framework.getId()));
    }

    public Page<Framework> listFrameworks(
            String region, String frameworkType, String maxResultsValue, String nextToken) {
        requireActive();
        if (frameworkType == null || frameworkType.isBlank()) {
            throw validation("frameworkType is a required parameter.");
        }
        if (!FRAMEWORK_TYPES.contains(frameworkType)) {
            throw validation("frameworkType is invalid.");
        }
        List<Framework> items = frameworks.scan(key -> key.startsWith(region + "::"));
        items.removeIf(framework -> !frameworkType.equals(framework.getType()));
        items.sort(Comparator.comparing(Framework::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResultsValue, nextToken);
    }

    public synchronized Assessment createAssessment(String region, JsonNode request) {
        requireActive();
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        String frameworkId = requireText(request, "frameworkId");
        Framework framework = requireFramework(region, frameworkId);
        JsonNode destination = requireObjectField(request, "assessmentReportsDestination");
        JsonNode roles = requireArray(request, "roles");
        if (roles.isEmpty()) {
            throw validation("roles must contain at least one role.");
        }
        JsonNode scope = request.has("scope") && !request.get("scope").isNull()
                ? copy(request.get("scope"))
                : defaultScope();
        long now = nowSeconds();
        String id = newId();
        Assessment assessment = new Assessment();
        assessment.setId(id);
        assessment.setArn(arn(region, "assessment/" + id));
        assessment.setName(name);
        assessment.setDescription(optionalText(request, "description"));
        assessment.setStatus(STATUS_ACTIVE);
        assessment.setFrameworkId(framework.getId());
        assessment.setFrameworkArn(framework.getArn());
        assessment.setAssessmentReportsDestination(copy(destination));
        assessment.setScope(scope);
        assessment.setRoles(copy(roles));
        assessment.setCreatedAt(now);
        assessment.setLastUpdated(now);
        assessment.setTags(readTags(request.get("tags")));
        assessments.put(storageKey(region, id), assessment);
        return assessment;
    }

    public Assessment getAssessment(String region, String assessmentId) {
        requireActive();
        return requireAssessment(region, assessmentId);
    }

    public synchronized Assessment updateAssessment(String region, String assessmentId, JsonNode request) {
        requireActive();
        requireObject(request, "Request body");
        Assessment assessment = requireAssessment(region, assessmentId);
        if (request.has("assessmentName") && !request.get("assessmentName").isNull()) {
            assessment.setName(requireText(request, "assessmentName"));
        }
        if (request.has("assessmentDescription")) {
            assessment.setDescription(optionalText(request, "assessmentDescription"));
        }
        if (request.has("scope") && !request.get("scope").isNull()) {
            assessment.setScope(copy(request.get("scope")));
        }
        if (request.has("assessmentReportsDestination") && !request.get("assessmentReportsDestination").isNull()) {
            assessment.setAssessmentReportsDestination(copy(request.get("assessmentReportsDestination")));
        }
        if (request.has("roles") && !request.get("roles").isNull()) {
            JsonNode roles = requireArray(request, "roles");
            if (roles.isEmpty()) {
                throw validation("roles must contain at least one role.");
            }
            assessment.setRoles(copy(roles));
        }
        assessment.setLastUpdated(nowSeconds());
        assessments.put(storageKey(region, assessment.getId()), assessment);
        return assessment;
    }

    public synchronized void deleteAssessment(String region, String assessmentId) {
        requireActive();
        Assessment assessment = requireAssessment(region, assessmentId);
        assessments.delete(storageKey(region, assessment.getId()));
    }

    public Page<Assessment> listAssessments(
            String region, String status, String maxResultsValue, String nextToken) {
        requireActive();
        if (status != null && !status.isBlank() && !ASSESSMENT_STATUSES.contains(status)) {
            throw validation("status is invalid.");
        }
        List<Assessment> items = assessments.scan(key -> key.startsWith(region + "::"));
        if (status != null && !status.isBlank()) {
            items.removeIf(assessment -> !status.equals(assessment.getStatus()));
        }
        items.sort(Comparator.comparing(Assessment::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResultsValue, nextToken);
    }

    public ObjectNode getServicesInScope() {
        requireActive();
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode metadata = response.putArray("serviceMetadata");
        addService(metadata, "s3", "Amazon S3", "Object storage", "Storage");
        addService(metadata, "iam", "AWS Identity and Access Management", "Identity and access", "Security");
        addService(metadata, "cloudtrail", "AWS CloudTrail", "API activity logging", "Management");
        addService(metadata, "config", "AWS Config", "Resource configuration history", "Management");
        addService(metadata, "securityhub", "AWS Security Hub", "Security findings", "Security");
        return response;
    }

    public ObjectNode getInsights(String region) {
        requireActive();
        long active = assessments.scan(key -> key.startsWith(region + "::")).stream()
                .filter(assessment -> STATUS_ACTIVE.equals(assessment.getStatus()))
                .count();
        ObjectNode insights = objectMapper.createObjectNode();
        insights.put("activeAssessmentsCount", active);
        insights.put("noncompliantEvidenceCount", 0);
        insights.put("compliantEvidenceCount", 0);
        insights.put("inconclusiveEvidenceCount", 0);
        insights.put("assessmentControlsCountByNoncompliantEvidence", 0);
        insights.put("totalAssessmentControlsCount", 0);
        insights.put("lastUpdated", nowSeconds());
        ObjectNode response = objectMapper.createObjectNode();
        response.set("insights", insights);
        return response;
    }

    public ObjectNode listControlDomainInsights() {
        requireActive();
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("controlDomainInsights");
        return response;
    }

    public ObjectNode listControlInsightsByControlDomain(String controlDomainId) {
        requireActive();
        if (controlDomainId == null || controlDomainId.isBlank()) {
            throw validation("controlDomainId is a required parameter.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("controlInsightsMetadata");
        return response;
    }

    public ObjectNode getDelegations() {
        requireActive();
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("delegations");
        return response;
    }

    public ObjectNode getEvidenceFileUploadUrl(String fileName, String baseUrl) {
        requireActive();
        if (fileName == null || fileName.isBlank()) {
            throw validation("fileName is a required parameter.");
        }
        String root = baseUrl == null || baseUrl.isBlank() ? "http://localhost:4566" : baseUrl.replaceAll("/+$", "");
        ObjectNode response = objectMapper.createObjectNode();
        response.put("evidenceFileName", fileName);
        response.put("uploadUrl", root + "/auditmanager-evidence/" + fileName);
        return response;
    }

    public ObjectNode listAssessmentReports() {
        requireActive();
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("assessmentReports");
        return response;
    }

    public void validateAssessmentReportIntegrity(JsonNode request) {
        requireActive();
        requireObject(request, "Request body");
        String path = requireText(request, "s3RelativePath");
        throw resourceNotFound(
                path,
                "AWS::AuditManager::AssessmentReport",
                "Assessment report " + path + " does not exist.");
    }

    public ObjectNode listKeywordsForDataSource(String source) {
        requireActive();
        if (source == null || source.isBlank()) {
            throw validation("source is a required parameter.");
        }
        if (!DATA_SOURCES.contains(source)) {
            throw validation("source is invalid.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode keywords = response.putArray("keywords");
        for (String keyword : keywordsFor(source)) {
            keywords.add(keyword);
        }
        return response;
    }

    public ObjectNode listNotifications() {
        requireActive();
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("notifications");
        return response;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireTagged(region, arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        TaggedResource resource = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(resource.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        resource.storeTags().accept(current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        TaggedResource resource = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(resource.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        resource.storeTags().accept(current);
    }

    private void requireActive() {
        if (!STATUS_ACTIVE.equals(getAccountStatus())) {
            throw new AwsException(
                    "AccessDeniedException",
                    "Please complete AWS Audit Manager setup from the AWS console before using this API.",
                    403);
        }
    }

    private Control requireControl(String region, String controlId) {
        String id = decode(controlId);
        return controls.get(storageKey(region, id)).orElseThrow(
                () -> resourceNotFound(id, RESOURCE_CONTROL, "Control " + id + " does not exist."));
    }

    private Framework requireFramework(String region, String frameworkId) {
        String id = decode(frameworkId);
        return frameworks.get(storageKey(region, id)).orElseThrow(
                () -> resourceNotFound(id, RESOURCE_FRAMEWORK, "Framework " + id + " does not exist."));
    }

    private Assessment requireAssessment(String region, String assessmentId) {
        String id = decode(assessmentId);
        return assessments.get(storageKey(region, id)).orElseThrow(
                () -> resourceNotFound(id, RESOURCE_ASSESSMENT, "Assessment " + id + " does not exist."));
    }

    private TaggedResource requireTagged(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decode(arn));
        } catch (IllegalArgumentException e) {
            throw validation("resourceArn is invalid.");
        }
        if (!SERVICE.equals(parsed.service())) {
            throw validation("resourceArn is invalid.");
        }
        String resource = parsed.resource();
        if (resource.startsWith("control/")) {
            Control control = requireControl(region, resource.substring("control/".length()));
            return new TaggedResource(control.getTags(), tags -> {
                control.setTags(tags);
                controls.put(storageKey(region, control.getId()), control);
            });
        }
        if (resource.startsWith("assessmentFramework/")) {
            Framework framework = requireFramework(region, resource.substring("assessmentFramework/".length()));
            return new TaggedResource(framework.getTags(), tags -> {
                framework.setTags(tags);
                frameworks.put(storageKey(region, framework.getId()), framework);
            });
        }
        if (resource.startsWith("assessment/")) {
            Assessment assessment = requireAssessment(region, resource.substring("assessment/".length()));
            return new TaggedResource(assessment.getTags(), tags -> {
                assessment.setTags(tags);
                assessments.put(storageKey(region, assessment.getId()), assessment);
            });
        }
        throw validation("resourceArn is invalid.");
    }

    private ArrayNode assignSourceIds(JsonNode sources) {
        ArrayNode assigned = objectMapper.createArrayNode();
        for (JsonNode source : sources) {
            requireObject(source, "controlMappingSources members");
            ObjectNode copy = source.deepCopy();
            if (!hasText(copy, "sourceId")) {
                copy.put("sourceId", newId());
            }
            assigned.add(copy);
        }
        return assigned;
    }

    private ArrayNode assignControlSetIds(JsonNode controlSets) {
        ArrayNode assigned = objectMapper.createArrayNode();
        for (JsonNode set : controlSets) {
            requireObject(set, "controlSets members");
            ObjectNode copy = set.deepCopy();
            if (!hasText(copy, "id")) {
                copy.put("id", newId());
            }
            if (!hasText(copy, "name")) {
                throw validation("controlSets.name is required.");
            }
            assigned.add(copy);
        }
        return assigned;
    }

    private static String controlSources(JsonNode sources) {
        if (sources == null || !sources.isArray()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (JsonNode source : sources) {
            if (source.has("sourceName") && source.get("sourceName").isTextual()) {
                names.add(source.get("sourceName").textValue());
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private ObjectNode defaultScope() {
        ObjectNode scope = objectMapper.createObjectNode();
        ArrayNode accountsNode = scope.putArray("awsAccounts");
        ObjectNode account = accountsNode.addObject();
        account.put("id", regionResolver.getAccountId());
        return scope;
    }

    private static void addService(ArrayNode metadata, String name, String displayName, String description,
            String category) {
        ObjectNode service = metadata.addObject();
        service.put("name", name);
        service.put("displayName", displayName);
        service.put("description", description);
        service.put("category", category);
    }

    private static List<String> keywordsFor(String source) {
        return switch (source) {
            case "AWS_Cloudtrail" -> List.of("ConsoleLogin", "CreateUser", "DeleteUser", "AssumeRole");
            case "AWS_Config" -> List.of("IAM_PASSWORD_POLICY", "S3_BUCKET_PUBLIC_READ_PROHIBITED");
            case "AWS_Security_Hub" -> List.of("IAM.1", "S3.1", "EC2.1");
            case "AWS_API_Call" -> List.of("DescribeInstances", "GetBucketAcl", "ListUsers");
            default -> List.of();
        };
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), resource).toString();
    }

    private String callerPrincipal() {
        return "arn:aws:iam::" + regionResolver.getAccountId() + ":root";
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    private JsonNode copy(JsonNode node) {
        return node == null || node.isNull() ? objectMapper.nullNode() : node.deepCopy();
    }

    private <T> Page<T> page(List<T> items, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + maxResults, items.size());
        String responseToken = end < items.size() ? encodeOffset(end) : null;
        return new Page<>(items.subList(offset, end), responseToken);
    }

    private static int parseMaxResults(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > MAX_RESULTS) {
                throw validation("maxResults must be between 1 and 1000.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw validation("maxResults must be an integer between 1 and 1000.");
        }
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw validation("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset > resultSize) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isObject()) {
            throw validation("tags must be an object.");
        }
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || !value.isTextual()) {
                throw validation("tags contains an invalid key or value.");
            }
            tags.put(entry.getKey(), value.textValue());
        });
        return tags;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static JsonNode requireObjectField(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        requireObject(value, field);
        return value;
    }

    private static JsonNode requireArray(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) {
            throw validation(field + " must be an array.");
        }
        return value;
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static boolean hasText(JsonNode parent, String field) {
        return parent.has(field) && parent.get(field).isTextual() && !parent.get(field).textValue().isBlank();
    }

    static AwsException resourceNotFound(String resourceId, String resourceType, String message) {
        return new AwsException(
                "ResourceNotFoundException",
                message,
                404,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    private record TaggedResource(Map<String, String> tags, java.util.function.Consumer<Map<String, String>> storeTags) {
        private TaggedResource {
            tags = tags == null ? Map.of() : tags;
        }
    }
}
