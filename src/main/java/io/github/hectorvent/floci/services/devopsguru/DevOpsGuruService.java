package io.github.hectorvent.floci.services.devopsguru;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.devopsguru.model.DevOpsGuruAccount;
import io.github.hectorvent.floci.services.devopsguru.model.NotificationChannel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon DevOps Guru restJson1: resource collections, notification channels,
 * event sources, service-integration, and insight lookups.
 *
 * <p>An account has a quota of two channels per Region. Channel configuration is
 * immutable; callers converge filter changes by remove + re-add.
 */
@ApplicationScoped
public class DevOpsGuruService {

    static final String SERVICE = "devops-guru";
    private static final int CHANNEL_QUOTA = 2;
    private static final String INSIGHT_TYPE = "Insight";
    private static final String CHANNEL_TYPE = "NotificationChannel";
    private static final String ENABLED = "ENABLED";
    private static final String DISABLED = "DISABLED";
    private static final Set<String> OPT_IN = Set.of(ENABLED, DISABLED);
    private static final Set<String> EVENT_SOURCE_STATUSES = OPT_IN;
    private static final Set<String> ENCRYPTION_TYPES = Set.of(
            "AWS_OWNED_KMS_KEY", "CUSTOMER_MANAGED_KEY");
    private static final Pattern SNS_ARN = Pattern.compile(
            "arn:[^:]+:sns:[^:]*:\\d{12}:.+$");

    private final StorageBackend<String, DevOpsGuruAccount> accounts;

    @Inject
    public DevOpsGuruService(StorageFactory storageFactory) {
        this(storageFactory.create(
                "devopsguru",
                "devopsguru-accounts.json",
                new TypeReference<Map<String, DevOpsGuruAccount>>() {
                }));
    }

    DevOpsGuruService(StorageBackend<String, DevOpsGuruAccount> accounts) {
        this.accounts = accounts;
    }

    public void describeInsight(String insightId) {
        String id = requireId(insightId, "Id");
        throw notFound(id, INSIGHT_TYPE);
    }

    public synchronized NotificationChannel addNotificationChannel(String region, JsonNode request) {
        requireObject(request, "Request body");
        JsonNode config = request.get("Config");
        requireObject(config, "Config");
        JsonNode sns = config.get("Sns");
        requireObject(sns, "Config.Sns");
        String topicArn = requireText(sns, "TopicArn");
        validateTopicArn(topicArn);

        DevOpsGuruAccount account = account(region);
        List<NotificationChannel> existing = channelsOf(account);
        Optional<NotificationChannel> duplicate = existing.stream()
                .filter(channel -> topicArn.equals(channel.getTopicArn()))
                .findFirst();
        if (duplicate.isPresent()) {
            NotificationChannel channel = duplicate.get();
            throw conflict(
                    channel.getId(),
                    CHANNEL_TYPE,
                    "A notification channel already exists for topic " + topicArn + ".");
        }
        if (existing.size() >= CHANNEL_QUOTA) {
            throw new AwsException(
                    "ServiceQuotaExceededException",
                    "The maximum number of notification channels for this account has been reached.",
                    402);
        }

        NotificationChannel channel = new NotificationChannel();
        channel.setId(UUID.randomUUID().toString());
        channel.setTopicArn(topicArn);
        JsonNode filters = optionalObject(config, "Filters");
        if (filters != null) {
            channel.setSeverities(readStringArray(filters, "Severities"));
            channel.setMessageTypes(readStringArray(filters, "MessageTypes"));
        }
        existing.add(channel);
        account.setChannels(existing);
        save(region, account);
        return channel;
    }

    public List<NotificationChannel> listNotificationChannels(String region) {
        return new ArrayList<>(channelsOf(account(region)));
    }

    public synchronized void removeNotificationChannel(String region, String channelId) {
        String id = requireId(channelId, "Id");
        DevOpsGuruAccount account = account(region);
        List<NotificationChannel> existing = channelsOf(account);
        boolean removed = existing.removeIf(channel -> id.equals(channel.getId()));
        if (!removed) {
            throw notFound(id, CHANNEL_TYPE);
        }
        account.setChannels(existing);
        save(region, account);
    }

    public DevOpsGuruAccount describeEventSources(String region) {
        return account(region);
    }

    public synchronized DevOpsGuruAccount updateEventSources(String region, JsonNode request) {
        requireObject(request, "Request body");
        JsonNode sources = optionalObject(request, "EventSources");
        if (sources == null) {
            return account(region);
        }
        DevOpsGuruAccount account = account(region);
        JsonNode profiler = optionalObject(sources, "AmazonCodeGuruProfiler");
        if (profiler != null && profiler.has("Status")) {
            account.setProfilerStatus(requireOptIn(profiler, "Status"));
        }
        save(region, account);
        return account;
    }

    public DevOpsGuruAccount describeServiceIntegration(String region) {
        return account(region);
    }

    public synchronized DevOpsGuruAccount updateServiceIntegration(String region, JsonNode request) {
        requireObject(request, "Request body");
        JsonNode integration = request.get("ServiceIntegration");
        requireObject(integration, "ServiceIntegration");
        DevOpsGuruAccount account = account(region);

        JsonNode opsCenter = optionalObject(integration, "OpsCenter");
        if (opsCenter != null && opsCenter.has("OptInStatus")) {
            account.setOpsCenterStatus(requireOptIn(opsCenter, "OptInStatus"));
        }
        JsonNode logs = optionalObject(integration, "LogsAnomalyDetection");
        if (logs != null && logs.has("OptInStatus")) {
            account.setLogsAnomalyStatus(requireOptIn(logs, "OptInStatus"));
        }
        JsonNode kms = optionalObject(integration, "KMSServerSideEncryption");
        if (kms != null) {
            if (kms.has("Type")) {
                String type = requireText(kms, "Type");
                if (!ENCRYPTION_TYPES.contains(type)) {
                    throw validation("KMSServerSideEncryption.Type is invalid.");
                }
                account.setEncryptionType(type);
                if ("AWS_OWNED_KMS_KEY".equals(type)) {
                    account.setKmsKeyId(null);
                }
            }
            if (kms.has("KMSKeyId") && !kms.get("KMSKeyId").isNull()) {
                account.setKmsKeyId(requireText(kms, "KMSKeyId"));
            }
            if (kms.has("OptInStatus")) {
                account.setEncryptionType(account.getEncryptionType());
            }
        }
        save(region, account);
        return account;
    }

    public ObjectNode describeAccountHealth() {
        ObjectNode response = object();
        response.put("OpenReactiveInsights", 0);
        response.put("OpenProactiveInsights", 0);
        response.put("MetricsAnalyzed", 0);
        response.put("ResourceHours", 0);
        response.put("AnalyzedResourceCount", 0);
        return response;
    }

    public ObjectNode describeAccountOverview(JsonNode request) {
        requireObject(request, "Request body");
        requireField(request, "FromTime");
        ObjectNode response = object();
        response.put("ReactiveInsights", 0);
        response.put("ProactiveInsights", 0);
        response.put("MeanTimeToRecoverInMilliseconds", 0);
        return response;
    }

    public ObjectNode describeOrganizationHealth() {
        ObjectNode response = object();
        response.put("OpenReactiveInsights", 0);
        response.put("OpenProactiveInsights", 0);
        response.put("MetricsAnalyzed", 0);
        response.put("ResourceHours", 0);
        return response;
    }

    public ObjectNode describeOrganizationOverview(JsonNode request) {
        requireObject(request, "Request body");
        requireField(request, "FromTime");
        ObjectNode response = object();
        response.put("ReactiveInsights", 0);
        response.put("ProactiveInsights", 0);
        return response;
    }

    public ObjectNode listInsights(JsonNode request) {
        requireObject(request, "Request body");
        JsonNode filter = request.get("StatusFilter");
        requireObject(filter, "StatusFilter");
        JsonNode section = firstPresent(filter, "Ongoing", "Closed", "Any");
        if (section == null) {
            throw validation("StatusFilter must contain Ongoing, Closed, or Any.");
        }
        String type = requireText(section, "Type").toUpperCase(Locale.ROOT);
        requireInsightType(type);
        return emptyInsights(type);
    }

    public ObjectNode searchInsights(JsonNode request) {
        requireObject(request, "Request body");
        String type = requireText(request, "Type").toUpperCase(Locale.ROOT);
        requireInsightType(type);
        JsonNode range = request.get("StartTimeRange");
        requireObject(range, "StartTimeRange");
        if (!range.has("FromTime") || range.get("FromTime").isNull()) {
            throw validation("StartTimeRange.FromTime is required.");
        }
        if (!range.has("ToTime") || range.get("ToTime").isNull()) {
            throw validation("StartTimeRange.ToTime is required.");
        }
        return emptyInsights(type);
    }

    public ObjectNode searchOrganizationInsights(JsonNode request) {
        requireObject(request, "Request body");
        if (!request.has("AccountIds") || !request.get("AccountIds").isArray()
                || request.get("AccountIds").isEmpty()) {
            throw validation("AccountIds is required.");
        }
        return searchInsights(request);
    }

    public ObjectNode listOrganizationInsights(JsonNode request) {
        return listInsights(request);
    }

    public ObjectNode describeResourceCollectionHealth(String region, String type) {
        String collectionType = requireCollectionType(type);
        DevOpsGuruAccount account = account(region);
        ObjectNode response = object();
        if ("AWS_CLOUD_FORMATION".equals(collectionType)) {
            ArrayNode stacks = response.putArray("CloudFormation");
            if (account.isCloudFormationConfigured()) {
                for (String stack : account.getStackNames()) {
                    ObjectNode health = stacks.addObject();
                    health.put("StackName", stack);
                    ObjectNode insight = health.putObject("Insight");
                    insight.put("OpenProactiveInsights", 0);
                    insight.put("OpenReactiveInsights", 0);
                    insight.put("MeanTimeToRecoverInMilliseconds", 0);
                    health.put("AnalyzedResourceCount", 0);
                }
            }
        } else if ("AWS_TAGS".equals(collectionType)) {
            ArrayNode tags = response.putArray("Tags");
            for (Map.Entry<String, Set<String>> entry : account.getTagValuesByKey().entrySet()) {
                ObjectNode tag = tags.addObject();
                tag.put("AppBoundaryKey", entry.getKey());
                ObjectNode insight = tag.putObject("Insight");
                insight.put("OpenProactiveInsights", 0);
                insight.put("OpenReactiveInsights", 0);
                insight.put("MeanTimeToRecoverInMilliseconds", 0);
                tag.put("AnalyzedResourceCount", 0);
            }
        } else {
            response.putArray("Service");
        }
        return response;
    }

    public ObjectNode describeOrganizationResourceCollectionHealth(JsonNode request) {
        requireObject(request, "Request body");
        String type = requireText(request, "OrganizationResourceCollectionType");
        ObjectNode response = object();
        switch (type) {
            case "AWS_CLOUD_FORMATION" -> response.putArray("CloudFormation");
            case "AWS_SERVICE" -> response.putArray("Service");
            case "AWS_ACCOUNT" -> response.putArray("Account");
            case "AWS_TAGS" -> response.putArray("Tags");
            default -> throw validation("OrganizationResourceCollectionType is invalid.");
        }
        return response;
    }

    public ObjectNode listMonitoredResources(String region, JsonNode request) {
        requireObject(request, "Request body");
        if (!account(region).hasAnyCollection()) {
            throw new AwsException(
                    "ResourceNotFoundException",
                    "No CustomerResourceFilter present",
                    404,
                    Map.of("ResourceId", "ResourceCollection",
                            "ResourceType", "AWS::DevOpsGuru::ResourceCollection"));
        }
        ObjectNode response = object();
        response.putArray("MonitoredResourceIdentifiers");
        return response;
    }

    public ObjectNode getResourceCollection(String region, String type) {
        String collectionType = requireCollectionType(type);
        DevOpsGuruAccount found = account(region);
        ObjectNode filter = object();
        if ("AWS_CLOUD_FORMATION".equals(collectionType)) {
            if (!found.isCloudFormationConfigured()) {
                throw collectionNotFound();
            }
            ArrayNode stacks = filter.putObject("CloudFormation").putArray("StackNames");
            found.getStackNames().forEach(stacks::add);
        } else if ("AWS_TAGS".equals(collectionType)) {
            if (!found.hasTagCollection()) {
                throw collectionNotFound();
            }
            ArrayNode tags = filter.putArray("Tags");
            for (Map.Entry<String, Set<String>> entry : found.getTagValuesByKey().entrySet()) {
                ObjectNode tag = tags.addObject();
                tag.put("AppBoundaryKey", entry.getKey());
                ArrayNode values = tag.putArray("TagValues");
                entry.getValue().forEach(values::add);
            }
        } else {
            throw collectionNotFound();
        }
        ObjectNode response = object();
        response.set("ResourceCollection", filter);
        return response;
    }

    public synchronized ObjectNode updateResourceCollection(String region, JsonNode request) {
        requireObject(request, "Request body");
        String action = requireText(request, "Action").toUpperCase(Locale.ROOT);
        if (!"ADD".equals(action) && !"REMOVE".equals(action)) {
            throw validation("Action must be ADD or REMOVE.");
        }
        JsonNode collection = request.get("ResourceCollection");
        requireObject(collection, "ResourceCollection");
        boolean hasCfn = collection.has("CloudFormation") && !collection.get("CloudFormation").isNull();
        boolean hasTags = collection.has("Tags") && !collection.get("Tags").isNull();
        if (hasCfn == hasTags) {
            throw validation("Specify CloudFormation or Tags, not both.");
        }
        DevOpsGuruAccount found = account(region);
        if (hasCfn) {
            applyStackUpdate(found, action, collection.get("CloudFormation"));
        } else {
            applyTagUpdate(found, action, collection.get("Tags"));
        }
        save(region, found);
        return object();
    }

    public ObjectNode listEvents() {
        ObjectNode response = object();
        response.putArray("Events");
        return response;
    }

    public ObjectNode listRecommendations() {
        ObjectNode response = object();
        response.putArray("Recommendations");
        return response;
    }

    public ObjectNode describeAnomaly(String id) {
        throw notFound(requireId(id, "Id"), "Anomaly");
    }

    public ObjectNode deleteInsight(String id) {
        throw notFound(requireId(id, "Id"), INSIGHT_TYPE);
    }

    public ObjectNode listAnomaliesForInsight(String insightId) {
        throw notFound(requireId(insightId, "InsightId"), INSIGHT_TYPE);
    }

    public ObjectNode listAnomalousLogGroups(JsonNode request) {
        requireObject(request, "Request body");
        throw notFound(requireText(request, "InsightId"), INSIGHT_TYPE);
    }

    public synchronized ObjectNode putFeedback(String region, JsonNode request) {
        requireObject(request, "Request body");
        JsonNode feedback = optionalObject(request, "InsightFeedback");
        if (feedback == null) {
            return object();
        }
        String id = optionalText(feedback, "Id");
        String option = optionalText(feedback, "Feedback");
        if (id != null && option != null) {
            DevOpsGuruAccount found = account(region);
            found.getFeedbackByInsightId().put(id, option);
            found.setLastFeedbackInsightId(id);
            save(region, found);
        }
        return object();
    }

    public ObjectNode describeFeedback(String region, JsonNode request) {
        requireObject(request, "Request body");
        DevOpsGuruAccount found = account(region);
        String insightId = optionalText(request, "InsightId");
        if (insightId == null) {
            insightId = found.getLastFeedbackInsightId();
        }
        ObjectNode response = object();
        if (insightId != null && found.getFeedbackByInsightId().containsKey(insightId)) {
            ObjectNode feedback = response.putObject("InsightFeedback");
            feedback.put("Id", insightId);
            feedback.put("Feedback", found.getFeedbackByInsightId().get(insightId));
        }
        return response;
    }

    public ObjectNode getCostEstimation(String region) {
        DevOpsGuruAccount.CostEstimation estimation = account(region).getCostEstimation();
        if (estimation == null) {
            throw new AwsException(
                    "ResourceNotFoundException",
                    "No cost estimation is in progress.",
                    404,
                    Map.of("ResourceId", "CostEstimation",
                            "ResourceType", "AWS::DevOpsGuru::CostEstimation"));
        }
        ObjectNode response = object();
        response.put("Status", estimation.getStatus());
        response.put("TotalCost", estimation.getTotalCost());
        return response;
    }

    public synchronized ObjectNode startCostEstimation(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireObject(request.get("ResourceCollection"), "ResourceCollection");
        DevOpsGuruAccount found = account(region);
        found.setCostEstimation(new DevOpsGuruAccount.CostEstimation("COMPLETED", 0.0));
        save(region, found);
        return object();
    }

    private DevOpsGuruAccount account(String region) {
        return accounts.get(region).orElseGet(DevOpsGuruAccount::new);
    }

    private static List<NotificationChannel> channelsOf(DevOpsGuruAccount account) {
        List<NotificationChannel> channels = account.getChannels();
        return channels == null ? new ArrayList<>() : channels;
    }

    private void save(String region, DevOpsGuruAccount account) {
        accounts.put(region, account);
    }

    private static void validateTopicArn(String topicArn) {
        if (!SNS_ARN.matcher(topicArn).matches()) {
            throw validation("Config.Sns.TopicArn must be an Amazon SNS topic ARN.");
        }
        String name = topicArn.substring(topicArn.lastIndexOf(':') + 1);
        if (name.endsWith(".fifo")) {
            throw validation("DevOps Guru does not support Amazon SNS FIFO topics.");
        }
    }

    private static String requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || value.isNull() || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static JsonNode optionalObject(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        requireObject(value, field);
        return value;
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static String requireOptIn(JsonNode parent, String field) {
        String value = requireText(parent, field);
        if (!OPT_IN.contains(value)) {
            throw validation(field + " must be ENABLED or DISABLED.");
        }
        return value;
    }

    private static List<String> readStringArray(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode array = parent.get(field);
        if (!array.isArray()) {
            throw validation(field + " must be an array.");
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonNode value : array) {
            if (!value.isTextual()) {
                throw validation(field + " members must be strings.");
            }
            values.add(value.textValue());
        }
        return values;
    }

    private static AwsException notFound(String resourceId, String resourceType) {
        return new AwsException(
                "ResourceNotFoundException",
                resourceType + " " + resourceId + " does not exist.",
                404,
                Map.of("ResourceId", resourceId, "ResourceType", resourceType));
    }

    private static AwsException conflict(String resourceId, String resourceType, String message) {
        return new AwsException(
                "ConflictException",
                message,
                409,
                Map.of("ResourceId", resourceId, "ResourceType", resourceType));
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static ObjectNode emptyInsights(String type) {
        ObjectNode response = object();
        if ("PROACTIVE".equals(type)) {
            response.putArray("ProactiveInsights");
        } else {
            response.putArray("ReactiveInsights");
        }
        return response;
    }

    private static void requireInsightType(String type) {
        if (!"REACTIVE".equals(type) && !"PROACTIVE".equals(type)) {
            throw validation("Type must be REACTIVE or PROACTIVE.");
        }
    }

    private static String requireCollectionType(String type) {
        if (type == null || type.isBlank()) {
            throw validation("ResourceCollectionType is required.");
        }
        String normalized = type.toUpperCase(Locale.ROOT);
        if (!Set.of("AWS_CLOUD_FORMATION", "AWS_SERVICE", "AWS_TAGS").contains(normalized)) {
            throw validation("ResourceCollectionType is invalid.");
        }
        return normalized;
    }

    private static void requireField(JsonNode request, String field) {
        if (!request.has(field) || request.get(field).isNull()) {
            throw validation(field + " is required.");
        }
    }

    private static JsonNode firstPresent(JsonNode object, String... fields) {
        for (String field : fields) {
            JsonNode value = object.get(field);
            if (value != null && value.isObject()) {
                return value;
            }
        }
        return null;
    }

    private static String optionalText(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    private void applyStackUpdate(DevOpsGuruAccount account, String action, JsonNode cloudFormation) {
        requireObject(cloudFormation, "CloudFormation");
        List<String> names = readStringArray(cloudFormation, "StackNames");
        if (names == null || names.isEmpty()) {
            throw validation("CloudFormation.StackNames is required.");
        }
        if (names.size() > 500) {
            throw validation("A resource collection can include at most 500 CloudFormation stacks.");
        }
        if ("ADD".equals(action)) {
            account.setCloudFormationConfigured(true);
            account.getStackNames().addAll(names);
        } else {
            names.forEach(account.getStackNames()::remove);
            account.setCloudFormationConfigured(!account.getStackNames().isEmpty());
        }
    }

    private void applyTagUpdate(DevOpsGuruAccount account, String action, JsonNode tags) {
        if (tags == null || !tags.isArray()) {
            throw validation("Tags must be an array.");
        }
        for (JsonNode tag : tags) {
            requireObject(tag, "tag filter");
            String key = requireText(tag, "AppBoundaryKey");
            if (!key.toLowerCase(Locale.ROOT).startsWith("devops-guru-")) {
                throw validation("AppBoundaryKey must begin with devops-guru-.");
            }
            List<String> values = readStringArray(tag, "TagValues");
            if (values == null) {
                throw validation("TagValues is required.");
            }
            String storedKey = key;
            for (String existing : account.getTagValuesByKey().keySet()) {
                if (existing.equalsIgnoreCase(key)) {
                    storedKey = existing;
                    break;
                }
            }
            Set<String> current = account.getTagValuesByKey()
                    .computeIfAbsent(storedKey, ignored -> new LinkedHashSet<>());
            if ("ADD".equals(action)) {
                current.addAll(values);
            } else {
                values.forEach(current::remove);
            }
        }
    }

    private static AwsException collectionNotFound() {
        return new AwsException(
                "ResourceNotFoundException",
                "No CustomerResourceFilter present",
                404,
                Map.of("ResourceId", "ResourceCollection",
                        "ResourceType", "AWS::DevOpsGuru::ResourceCollection"));
    }
}
