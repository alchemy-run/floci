package io.floci.aws.services.devopsguru;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudformation.CloudFormationService;
import io.floci.aws.services.cloudformation.model.Stack;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudtrail.CloudTrailEventService;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.cloudwatch.metrics.model.Dimension;
import io.floci.aws.services.cloudwatch.metrics.model.MetricAlarm;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.resourcegroupstagging.ResourceGroupsTaggingService;
import io.floci.aws.services.resourcegroupstagging.model.ResourceTagMapping;
import io.floci.aws.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Amazon DevOps Guru for one account and Region: what it analyzes (a resource collection of CloudFormation stacks
 * or {@code devops-guru-*} tag values), its integrations and event sources, notification channels, feedback and
 * cost estimates, and the insights its analysis produces.
 * <p>
 * floci's analysis reads CloudWatch: an alarm in {@code ALARM} on a metric of a monitored resource (its dimension
 * names the resource) is an ongoing reactive anomaly, grouped into one reactive insight per stack or tag value.
 * The anomaly closes when the alarm leaves {@code ALARM}, and the insight when all of its anomalies have. Each new
 * insight, new anomaly, severity upgrade, recommendation and closure notifies the channels whose filters match
 * (through SNS) and publishes an {@code aws.devops-guru} event. Events come from CloudTrail; anomalous log groups
 * from the error lines of the insight's Lambda functions' logs, when log anomaly detection is enabled.
 */
@ApplicationScoped
public class DevOpsGuruService implements Resettable {

    private static final Logger LOG = Logger.getLogger(DevOpsGuruService.class);
    private static final String SOURCE = "aws.devops-guru";
    private static final int MAX_CHANNELS = 2;
    private static final double UNIT_COST = 0.0028;
    private static final double HOURS_PER_MONTH = 730;
    /** CloudWatch dimension names that identify a monitored resource. */
    private static final Set<String> RESOURCE_DIMENSIONS = Set.of("FunctionName", "TableName", "QueueName",
            "TopicName", "BucketName", "StreamName", "DBInstanceIdentifier", "DBClusterIdentifier", "ApiName",
            "ApiId", "LoadBalancer", "TargetGroup", "ClusterName", "ServiceName", "StateMachineArn",
            "AutoScalingGroupName", "InstanceId", "CacheClusterId", "DomainName", "NatGatewayId", "EndpointName",
            "DistributionId", "HostedZoneId", "HealthCheckId", "ClusterIdentifier");

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final RegionResolver regions;
    private final Instance<CloudWatchMetricsService> metrics;
    private final Instance<CloudFormationService> cloudFormation;
    private final Instance<ResourceGroupsTaggingService> tagging;
    private final Instance<SnsService> sns;
    private final Instance<EventBridgeService> events;
    private final Instance<CloudTrailEventService> trail;
    private final Instance<CloudWatchLogsService> logs;
    private final Instance<KmsService> kms;
    private final long transitionMillis;

    @Inject
    public DevOpsGuruService(StorageFactory storageFactory, ObjectMapper mapper, RegionResolver regions,
                             Instance<CloudWatchMetricsService> metrics, Instance<CloudFormationService> cloudFormation,
                             Instance<ResourceGroupsTaggingService> tagging, Instance<SnsService> sns,
                             Instance<EventBridgeService> events, Instance<CloudTrailEventService> trail,
                             Instance<CloudWatchLogsService> logs, Instance<KmsService> kms, EmulatorConfig config) {
        this.storage = storageFactory.create("devopsguru", "state.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.regions = regions;
        this.metrics = metrics;
        this.cloudFormation = cloudFormation;
        this.tagging = tagging;
        this.sns = sns;
        this.events = events;
        this.trail = trail;
        this.logs = logs;
        this.kms = kms;
        this.transitionMillis = config.services().devopsguru().transitionMillis();
    }

    @Override
    public void clear() {
        storage.clear();
    }

    // ---------------------------------------------------------------- storage

    private String account() {
        return storage.accountId();
    }

    private ObjectNode state(String region) {
        return storage.getForAccount(account(), region + "/state").orElseGet(() -> {
            ObjectNode state = mapper.createObjectNode();
            state.putObject("collection");
            state.putObject("integration");
            state.putObject("eventSources");
            state.putObject("channels");
            state.putObject("insights");
            state.putObject("anomalies");
            state.putObject("feedback");
            return state;
        });
    }

    private void save(String region, ObjectNode state) {
        storage.putForAccount(account(), region + "/state", state);
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400, Map.of("Reason", "FIELD_VALIDATION_FAILED"));
    }

    private static AwsException notFound(String message, String id, String type) {
        return new AwsException("ResourceNotFoundException", message, 404, Map.of("ResourceId", id,
                "ResourceType", type));
    }

    private static AwsException conflict(String message, String id, String type) {
        return new AwsException("ConflictException", message, 409, Map.of("ResourceId", id, "ResourceType", type));
    }

    private ObjectNode page(List<ObjectNode> items, JsonNode input, String field, int max) {
        int limit = input.hasNonNull("MaxResults") ? input.get("MaxResults").asInt() : max;
        if (limit < 1 || limit > max) {
            throw invalid("MaxResults must be between 1 and " + max + ".");
        }
        Map<ObjectNode, Integer> positions = new IdentityHashMap<>();
        for (int index = 0; index < items.size(); index++) {
            positions.put(items.get(index), index);
        }
        Function<ObjectNode, String> cursor = item -> String.format("%09d", positions.get(item));
        PaginatedResult<ObjectNode> paged = Pagination.paginate(items, cursor, limit,
                input.path("NextToken").asText(null), field, bad -> invalid("Invalid NextToken."));
        ObjectNode response = mapper.createObjectNode();
        response.putArray(field).addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("NextToken", paged.nextToken());
        }
        return response;
    }

    // ---------------------------------------------------------------- resource collection

    public synchronized ObjectNode getResourceCollection(ObjectNode input, String region) {
        String type = input.path("ResourceCollectionType").asText();
        ObjectNode collection = (ObjectNode) state(region).get("collection");
        ObjectNode filter = mapper.createObjectNode();
        switch (type) {
            case "AWS_CLOUD_FORMATION" -> filter.putObject("CloudFormation").set("StackNames",
                    collection.path("stacks").isArray() ? collection.get("stacks").deepCopy()
                            : mapper.createArrayNode());
            case "AWS_TAGS" -> {
                ArrayNode tags = filter.putArray("Tags");
                collection.path("tags").properties().forEach(entry -> tags.addObject()
                        .put("AppBoundaryKey", entry.getKey()).set("TagValues", entry.getValue().deepCopy()));
            }
            default -> throw invalid("ResourceCollectionType must be AWS_CLOUD_FORMATION or AWS_TAGS.");
        }
        ObjectNode response = mapper.createObjectNode();
        response.set("ResourceCollection", filter);
        return response;
    }

    public synchronized ObjectNode updateResourceCollection(ObjectNode input, String region) {
        String action = input.path("Action").asText();
        if (!Set.of("ADD", "REMOVE").contains(action)) {
            throw invalid("Action must be ADD or REMOVE.");
        }
        JsonNode filter = input.path("ResourceCollection");
        ObjectNode state = state(region);
        ObjectNode collection = (ObjectNode) state.get("collection");
        boolean stacks = filter.path("CloudFormation").has("StackNames");
        boolean tags = filter.path("Tags").isArray() && !filter.get("Tags").isEmpty();
        if (stacks == tags) {
            throw invalid("Specify either CloudFormation stack names or tags.");
        }
        if (stacks) {
            if (collection.path("tags").size() > 0 && "ADD".equals(action)) {
                throw invalid("The resource collection is defined by tags; remove them before adding stacks.");
            }
            Set<String> names = new LinkedHashSet<>();
            collection.path("stacks").forEach(name -> names.add(name.asText()));
            for (JsonNode name : filter.get("CloudFormation").get("StackNames")) {
                String stack = name.asText();
                if (!"*".equals(stack) && !stack.matches("^[a-zA-Z*]+[a-zA-Z0-9-]*$")) {
                    throw invalid("Stack name " + stack + " is not valid.");
                }
                if ("ADD".equals(action)) {
                    names.add(stack);
                } else {
                    names.remove(stack);
                }
            }
            if (names.size() > 1000) {
                throw invalid("A resource collection can name at most 1000 stacks.");
            }
            ArrayNode list = collection.putArray("stacks");
            names.forEach(list::add);
        } else {
            if (collection.path("stacks").size() > 0 && "ADD".equals(action)) {
                throw invalid("The resource collection is defined by stacks; remove them before adding tags.");
            }
            ObjectNode tagMap = collection.withObject("tags");
            for (JsonNode tag : filter.get("Tags")) {
                String key = tag.path("AppBoundaryKey").asText();
                if (!key.toLowerCase(Locale.ROOT).startsWith("devops-guru-")) {
                    throw invalid("AppBoundaryKey must begin with devops-guru- (in any case).");
                }
                String existing = null;
                for (String present : iterable(tagMap)) {
                    if (present.equalsIgnoreCase(key)) {
                        existing = present;
                    }
                }
                if (existing == null && "ADD".equals(action) && tagMap.size() >= 1) {
                    throw invalid("A resource collection can use one tag key.");
                }
                String target = existing != null ? existing : key;
                Set<String> values = new LinkedHashSet<>();
                tagMap.path(target).forEach(value -> values.add(value.asText()));
                for (JsonNode value : tag.path("TagValues")) {
                    if ("ADD".equals(action)) {
                        values.add(value.asText());
                    } else {
                        values.remove(value.asText());
                    }
                }
                if (values.size() > 500) {
                    throw invalid("A tag key can have at most 500 values.");
                }
                if (values.isEmpty()) {
                    tagMap.remove(target);
                } else {
                    ArrayNode list = tagMap.putArray(target);
                    values.forEach(list::add);
                }
            }
        }
        if (!collection.has("since")) {
            collection.put("since", now());
        }
        save(region, state);
        return mapper.createObjectNode();
    }

    private static List<String> iterable(ObjectNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** A monitored resource: its name, CloudFormation type, ARN, and the stack or tag value it belongs to. */
    private record Monitored(String name, String type, String arn, String stack, String tagKey, String tagValue) {}

    private List<Monitored> monitored(String region, JsonNode collection) {
        List<Monitored> result = new ArrayList<>();
        if (collection.path("stacks").size() > 0 && cloudFormation.isResolvable()) {
            Set<String> names = new LinkedHashSet<>();
            collection.get("stacks").forEach(name -> names.add(name.asText()));
            List<String> stacks = new ArrayList<>();
            if (names.contains("*")) {
                try {
                    for (Stack stack : cloudFormation.get().describeStacks(null, region)) {
                        stacks.add(stack.getStackName());
                    }
                } catch (AwsException e) {
                    LOG.debugv("Could not list stacks: {0}", e.getMessage());
                }
            } else {
                stacks.addAll(names);
            }
            for (String stack : stacks) {
                try {
                    for (StackResource resource : cloudFormation.get().describeStackResources(stack, region)) {
                        if (resource.getPhysicalId() != null) {
                            result.add(new Monitored(name(resource.getPhysicalId()), resource.getResourceType(),
                                    AwsArnUtils.isArn(resource.getPhysicalId()) ? resource.getPhysicalId() : null,
                                    stack, null, null));
                        }
                    }
                } catch (AwsException e) {
                    LOG.debugv("Could not read stack {0}: {1}", stack, e.getMessage());
                }
            }
        }
        if (collection.path("tags").size() > 0 && tagging.isResolvable()) {
            collection.get("tags").properties().forEach(entry -> {
                for (JsonNode value : entry.getValue()) {
                    List<String> values = "*".equals(value.asText()) ? null : List.of(value.asText());
                    String token = null;
                    do {
                        ResourceGroupsTaggingService.PageResult page = tagging.get().getResources(null,
                                List.of(new ResourceGroupsTaggingService.TagFilter(entry.getKey(), values)), null,
                                token, 100, region);
                        for (ResourceTagMapping mapping : page.items()) {
                            String tagValue = mapping.getTags().get(entry.getKey());
                            result.add(new Monitored(name(mapping.getResourceArn()), type(mapping.getResourceArn()),
                                    mapping.getResourceArn(), null, entry.getKey(), tagValue));
                        }
                        token = page.nextPaginationToken();
                    } while (token != null && !token.isEmpty());
                }
            });
        }
        return result;
    }

    private static String name(String identifier) {
        if (!AwsArnUtils.isArn(identifier)) {
            return identifier;
        }
        String resource = AwsArnUtils.parse(identifier).resource();
        int cut = Math.max(resource.lastIndexOf('/'), resource.lastIndexOf(':'));
        return cut >= 0 ? resource.substring(cut + 1) : resource;
    }

    /** The CloudFormation type of a resource ARN, as DevOps Guru reports monitored resources. */
    private static String type(String arn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        return switch (parsed.service()) {
            case "lambda" -> "AWS::Lambda::Function";
            case "dynamodb" -> "AWS::DynamoDB::Table";
            case "sqs" -> "AWS::SQS::Queue";
            case "sns" -> "AWS::SNS::Topic";
            case "s3" -> "AWS::S3::Bucket";
            case "kinesis" -> "AWS::Kinesis::Stream";
            case "states" -> resource.startsWith("activity") ? "AWS::StepFunctions::Activity"
                    : "AWS::StepFunctions::StateMachine";
            case "rds" -> resource.startsWith("cluster") ? "AWS::RDS::DBCluster" : "AWS::RDS::DBInstance";
            case "ecs" -> resource.startsWith("service") ? "AWS::ECS::Service" : "AWS::ECS::Cluster";
            case "eks" -> "AWS::EKS::Cluster";
            case "elasticloadbalancing" -> resource.startsWith("targetgroup")
                    ? "AWS::ElasticLoadBalancingV2::TargetGroup" : "AWS::ElasticLoadBalancingV2::LoadBalancer";
            case "apigateway" -> "AWS::ApiGateway::RestApi";
            case "logs" -> "AWS::Logs::LogGroup";
            case "es" -> "AWS::OpenSearchService::Domain";
            case "elasticache" -> "AWS::ElastiCache::CacheCluster";
            case "redshift" -> "AWS::Redshift::Cluster";
            case "sagemaker" -> "AWS::SageMaker::Endpoint";
            case "route53" -> resource.startsWith("healthcheck") ? "AWS::Route53::HealthCheck"
                    : "AWS::Route53::HostedZone";
            case "ec2" -> resource.startsWith("natgateway") ? "AWS::EC2::NatGateway" : "AWS::EC2::Instance";
            default -> "AWS::" + parsed.service().toUpperCase(Locale.ROOT) + "::Resource";
        };
    }

    /** The {@code ListMonitoredResources} type filter a CloudFormation type matches. */
    private static String filterType(String type) {
        return switch (type) {
            case "AWS::Logs::LogGroup" -> "LOG_GROUPS";
            case "AWS::CloudFront::Distribution" -> "CLOUDFRONT_DISTRIBUTION";
            case "AWS::DynamoDB::Table" -> "DYNAMODB_TABLE";
            case "AWS::EC2::NatGateway" -> "EC2_NAT_GATEWAY";
            case "AWS::ECS::Cluster" -> "ECS_CLUSTER";
            case "AWS::ECS::Service" -> "ECS_SERVICE";
            case "AWS::EKS::Cluster" -> "EKS_CLUSTER";
            case "AWS::ElasticBeanstalk::Environment" -> "ELASTIC_BEANSTALK_ENVIRONMENT";
            case "AWS::ElasticLoadBalancing::LoadBalancer" -> "ELASTIC_LOAD_BALANCER_LOAD_BALANCER";
            case "AWS::ElasticLoadBalancingV2::LoadBalancer" -> "ELASTIC_LOAD_BALANCING_V2_LOAD_BALANCER";
            case "AWS::ElasticLoadBalancingV2::TargetGroup" -> "ELASTIC_LOAD_BALANCING_V2_TARGET_GROUP";
            case "AWS::ElastiCache::CacheCluster" -> "ELASTICACHE_CACHE_CLUSTER";
            case "AWS::Elasticsearch::Domain" -> "ELASTICSEARCH_DOMAIN";
            case "AWS::Kinesis::Stream" -> "KINESIS_STREAM";
            case "AWS::Lambda::Function" -> "LAMBDA_FUNCTION";
            case "AWS::OpenSearchService::Domain" -> "OPEN_SEARCH_SERVICE_DOMAIN";
            case "AWS::RDS::DBInstance" -> "RDS_DB_INSTANCE";
            case "AWS::RDS::DBCluster" -> "RDS_DB_CLUSTER";
            case "AWS::Redshift::Cluster" -> "REDSHIFT_CLUSTER";
            case "AWS::Route53::HostedZone" -> "ROUTE53_HOSTED_ZONE";
            case "AWS::Route53::HealthCheck" -> "ROUTE53_HEALTH_CHECK";
            case "AWS::S3::Bucket" -> "S3_BUCKET";
            case "AWS::SageMaker::Endpoint" -> "SAGEMAKER_ENDPOINT";
            case "AWS::SNS::Topic" -> "SNS_TOPIC";
            case "AWS::SQS::Queue" -> "SQS_QUEUE";
            case "AWS::StepFunctions::Activity" -> "STEP_FUNCTIONS_ACTIVITY";
            case "AWS::StepFunctions::StateMachine" -> "STEP_FUNCTIONS_STATE_MACHINE";
            default -> null;
        };
    }

    /** DevOps Guru's service names for its per-service health. */
    private static String serviceName(String type) {
        return switch (type.split("::").length > 1 ? type.split("::")[1] : "") {
            case "ApiGateway", "ApiGatewayV2" -> "API_GATEWAY";
            case "AutoScaling" -> "AUTO_SCALING_GROUP";
            case "CloudFront" -> "CLOUD_FRONT";
            case "DynamoDB" -> "DYNAMO_DB";
            case "EC2" -> "EC2";
            case "ECS" -> "ECS";
            case "EKS" -> "EKS";
            case "ElasticBeanstalk" -> "ELASTIC_BEANSTALK";
            case "ElastiCache" -> "ELASTI_CACHE";
            case "ElasticLoadBalancing" -> "ELB";
            case "ElasticLoadBalancingV2" -> "APPLICATION_ELB";
            case "Elasticsearch", "OpenSearchService" -> "ES";
            case "Kinesis" -> "KINESIS";
            case "Lambda" -> "LAMBDA";
            case "RDS" -> "RDS";
            case "Redshift" -> "REDSHIFT";
            case "Route53" -> "ROUTE_53";
            case "S3" -> "S3";
            case "SageMaker" -> "SAGE_MAKER";
            case "SNS" -> "SNS";
            case "SQS" -> "SQS";
            case "StepFunctions" -> "STEP_FUNCTIONS";
            default -> null;
        };
    }

    public synchronized ObjectNode listMonitoredResources(ObjectNode input, String region) {
        ObjectNode state = analyze(region);
        JsonNode filters = input.path("Filters");
        Set<String> types = new LinkedHashSet<>();
        filters.path("ResourceTypeFilters").forEach(type -> types.add(type.asText()));
        String permission = filters.path("ResourcePermission").asText("FULL_PERMISSION");
        List<ObjectNode> items = new ArrayList<>();
        if ("FULL_PERMISSION".equals(permission)) {
            for (Monitored resource : monitored(region, state.get("collection"))) {
                String filter = filterType(resource.type());
                if (!types.isEmpty() && (filter == null || !types.contains(filter))) {
                    continue;
                }
                ObjectNode item = mapper.createObjectNode();
                item.put("MonitoredResourceName", resource.name());
                item.put("Type", resource.type());
                item.put("ResourcePermission", "FULL_PERMISSION");
                item.put("LastUpdated", now());
                item.set("ResourceCollection", collectionOf(resource));
                items.add(item);
            }
        }
        return page(items, input, "MonitoredResourceIdentifiers", 50);
    }

    private ObjectNode collectionOf(Monitored resource) {
        ObjectNode collection = mapper.createObjectNode();
        if (resource.stack() != null) {
            collection.putObject("CloudFormation").putArray("StackNames").add(resource.stack());
        } else {
            collection.putArray("Tags").addObject().put("AppBoundaryKey", resource.tagKey())
                    .putArray("TagValues").add(resource.tagValue());
        }
        return collection;
    }

    // ---------------------------------------------------------------- analysis

    /** Brings the account's insights up to date with CloudWatch, notifying channels of every change. */
    private ObjectNode analyze(String region) {
        ObjectNode state = state(region);
        JsonNode collection = state.get("collection");
        if (!metrics.isResolvable() || (collection.path("stacks").isEmpty() && collection.path("tags").isEmpty())) {
            return closeAll(region, state);
        }
        List<Monitored> resources = monitored(region, collection);
        Map<String, Monitored> byName = new LinkedHashMap<>();
        for (Monitored resource : resources) {
            byName.putIfAbsent(resource.name(), resource);
        }
        ObjectNode anomalies = (ObjectNode) state.get("anomalies");
        ObjectNode insights = (ObjectNode) state.get("insights");
        Set<String> alarming = new LinkedHashSet<>();
        double now = now();
        List<String[]> notices = new ArrayList<>();
        for (MetricAlarm alarm : metrics.get().describeAlarms(null, null, region)) {
            if (!"ALARM".equals(alarm.getStateValue()) || alarm.getDimensions() == null) {
                continue;
            }
            Monitored resource = null;
            for (Dimension dimension : alarm.getDimensions()) {
                if (RESOURCE_DIMENSIONS.contains(dimension.name()) && byName.containsKey(name(dimension.value()))) {
                    resource = byName.get(name(dimension.value()));
                }
            }
            if (resource == null) {
                continue;
            }
            String key = alarm.getAlarmArn();
            alarming.add(key);
            ObjectNode anomaly = anomalyFor(anomalies, key);
            if (anomaly != null && "ONGOING".equals(anomaly.path("Status").asText())) {
                continue;
            }
            String unit = resource.stack() != null ? "stack:" + resource.stack()
                    : "tag:" + resource.tagKey() + "=" + resource.tagValue();
            ObjectNode insight = ongoingInsight(insights, unit);
            boolean created = insight == null;
            String severity = severity(alarm.getMetricName());
            if (created) {
                insight = mapper.createObjectNode();
                insight.put("Id", "ADX" + UUID.randomUUID().toString().replace("-", "").substring(0, 29)
                        .toUpperCase(Locale.ROOT));
                insight.put("Name", humanMetric(alarm.getMetricName()) + " anomalous in " + resource.name());
                insight.put("Severity", severity);
                insight.put("Status", "ONGOING");
                insight.putObject("InsightTimeRange").put("StartTime", alarmTime(alarm, now));
                insight.set("ResourceCollection", collectionOf(resource));
                insight.put("Description", "DevOps Guru detected anomalous " + alarm.getNamespace() + " "
                        + alarm.getMetricName() + " behavior in " + resource.type() + " " + resource.name() + ".");
                ObjectNode meta = insight.putObject("_");
                meta.put("type", "REACTIVE").put("unit", unit).put("created", now);
                meta.putArray("resources");
                meta.putArray("anomalies");
                insights.set(insight.get("Id").asText(), insight);
                notices.add(new String[] {"NEW_INSIGHT", insight.get("Id").asText()});
                notices.add(new String[] {"NEW_RECOMMENDATION", insight.get("Id").asText()});
            } else if (rank(severity) > rank(insight.path("Severity").asText())) {
                insight.put("Severity", severity);
                notices.add(new String[] {"SEVERITY_UPGRADED", insight.get("Id").asText()});
            }
            ObjectNode meta = (ObjectNode) insight.get("_");
            boolean known = false;
            for (JsonNode arn : meta.path("resources")) {
                known |= arn.asText().equals(resource.name());
            }
            if (!known) {
                ((ArrayNode) meta.get("resources")).add(resource.name());
                ((ObjectNode) meta).withArray("resourceArns").add(resource.arn() == null ? resource.name()
                        : resource.arn());
                ((ObjectNode) meta).withArray("services").add(Optional.ofNullable(serviceName(resource.type()))
                        .orElse("LAMBDA"));
                ((ObjectNode) meta).withArray("types").add(resource.type());
            }
            String anomalyId = "AL" + UUID.randomUUID().toString().replace("-", "").substring(0, 30)
                    .toUpperCase(Locale.ROOT);
            anomaly = mapper.createObjectNode();
            anomaly.put("Id", anomalyId);
            anomaly.put("Severity", severity);
            anomaly.put("Status", "ONGOING");
            anomaly.putObject("AnomalyTimeRange").put("StartTime", alarmTime(alarm, now));
            anomaly.putObject("AnomalyReportedTimeRange").put("OpenTime", now);
            ObjectNode metric = anomaly.putObject("SourceDetails").putArray("CloudWatchMetrics").addObject();
            metric.put("MetricName", alarm.getMetricName());
            metric.put("Namespace", alarm.getNamespace());
            ArrayNode dimensions = metric.putArray("Dimensions");
            alarm.getDimensions().forEach(dimension -> dimensions.addObject().put("Name", dimension.name())
                    .put("Value", dimension.value()));
            metric.put("Stat", stat(alarm.getStatistic()));
            if (alarm.getUnit() != null) {
                metric.put("Unit", alarm.getUnit());
            }
            metric.put("Period", alarm.getPeriod() == null ? 60 : alarm.getPeriod());
            anomaly.put("AssociatedInsightId", insight.get("Id").asText());
            anomaly.set("ResourceCollection", collectionOf(resource));
            anomaly.put("Type", "CAUSAL");
            anomaly.put("Name", alarm.getAlarmName());
            anomaly.put("Description", alarm.getStateReason());
            anomaly.putArray("AnomalyResources").addObject().put("Name", resource.name()).put("Type",
                    resource.type());
            anomaly.putObject("_").put("alarm", key).put("type", "REACTIVE");
            anomalies.set(anomalyId, anomaly);
            ((ArrayNode) meta.get("anomalies")).add(anomalyId);
            if (!created) {
                notices.add(new String[] {"NEW_ASSOCIATION", insight.get("Id").asText()});
            }
        }
        for (JsonNode anomaly : anomalies) {
            if ("ONGOING".equals(anomaly.path("Status").asText())
                    && !alarming.contains(anomaly.path("_").path("alarm").asText())) {
                ((ObjectNode) anomaly).put("Status", "CLOSED");
                ((ObjectNode) anomaly.get("AnomalyTimeRange")).put("EndTime", now);
                ((ObjectNode) anomaly.get("AnomalyReportedTimeRange")).put("CloseTime", now);
            }
        }
        for (JsonNode insight : insights) {
            if (!"ONGOING".equals(insight.path("Status").asText())) {
                continue;
            }
            boolean open = false;
            for (JsonNode id : insight.path("_").path("anomalies")) {
                open |= "ONGOING".equals(anomalies.path(id.asText()).path("Status").asText());
            }
            if (!open) {
                ((ObjectNode) insight).put("Status", "CLOSED");
                ((ObjectNode) insight.get("InsightTimeRange")).put("EndTime", now);
                notices.add(new String[] {"CLOSED_INSIGHT", insight.path("Id").asText()});
            }
        }
        save(region, state);
        for (String[] notice : notices) {
            notify(region, state, notice[0], (ObjectNode) insights.get(notice[1]));
        }
        return state;
    }

    private ObjectNode closeAll(String region, ObjectNode state) {
        boolean changed = false;
        double now = now();
        List<ObjectNode> closed = new ArrayList<>();
        for (JsonNode anomaly : state.get("anomalies")) {
            if ("ONGOING".equals(anomaly.path("Status").asText())) {
                ((ObjectNode) anomaly).put("Status", "CLOSED");
                ((ObjectNode) anomaly.get("AnomalyTimeRange")).put("EndTime", now);
                changed = true;
            }
        }
        for (JsonNode insight : state.get("insights")) {
            if ("ONGOING".equals(insight.path("Status").asText())) {
                ((ObjectNode) insight).put("Status", "CLOSED");
                ((ObjectNode) insight.get("InsightTimeRange")).put("EndTime", now);
                closed.add((ObjectNode) insight);
                changed = true;
            }
        }
        if (changed) {
            save(region, state);
            closed.forEach(insight -> notify(region, state, "CLOSED_INSIGHT", insight));
        }
        return state;
    }

    private static ObjectNode anomalyFor(ObjectNode anomalies, String alarm) {
        ObjectNode latest = null;
        for (JsonNode anomaly : anomalies) {
            if (alarm.equals(anomaly.path("_").path("alarm").asText())) {
                latest = (ObjectNode) anomaly;
            }
        }
        return latest;
    }

    private static ObjectNode ongoingInsight(ObjectNode insights, String unit) {
        for (JsonNode insight : insights) {
            if ("ONGOING".equals(insight.path("Status").asText()) && unit.equals(insight.path("_").path("unit")
                    .asText())) {
                return (ObjectNode) insight;
            }
        }
        return null;
    }

    private static double alarmTime(MetricAlarm alarm, double fallback) {
        long updated = alarm.getStateUpdatedTimestamp();
        return updated > 0 ? (updated > 100000000000L ? updated / 1000.0 : updated) : fallback;
    }

    private static String severity(String metric) {
        String name = metric == null ? "" : metric.toLowerCase(Locale.ROOT);
        if (name.contains("error") || name.contains("fault") || name.contains("5xx") || name.contains("fail")) {
            return "HIGH";
        }
        if (name.contains("latency") || name.contains("duration") || name.contains("throttl")
                || name.contains("age") || name.contains("4xx")) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    private static String stat(String statistic) {
        if (statistic == null) {
            return "Average";
        }
        return Set.of("Sum", "Average", "SampleCount", "Minimum", "Maximum").contains(statistic) ? statistic
                : "Average";
    }

    private static String humanMetric(String metric) {
        return metric == null ? "Metric" : metric.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    private void notify(String region, ObjectNode state, String type, ObjectNode insight) {
        if (insight == null) {
            return;
        }
        ObjectNode message = mapper.createObjectNode();
        message.put("accountId", account());
        message.put("region", region);
        message.put("messageType", type);
        message.put("insightId", insight.path("Id").asText());
        message.put("insightUrl", "https://" + region + "." + consoleHost(region) + "/devops-guru/insight/reactive/"
                + insight.path("Id").asText());
        message.put("insightType", "REACTIVE");
        message.put("insightDescription", insight.path("Name").asText());
        message.put("insightSeverity", insight.path("Severity").asText().toLowerCase(Locale.ROOT));
        message.put("startTime", (long) (insight.path("InsightTimeRange").path("StartTime").asDouble() * 1000));
        if (insight.path("InsightTimeRange").has("EndTime")) {
            message.put("endTime", (long) (insight.path("InsightTimeRange").path("EndTime").asDouble() * 1000));
        }
        ArrayNode anomalies = message.putArray("anomalies");
        for (JsonNode id : insight.path("_").path("anomalies")) {
            JsonNode anomaly = state.path("anomalies").path(id.asText());
            anomalies.addObject().put("id", id.asText()).put("startTime",
                    (long) (anomaly.path("AnomalyTimeRange").path("StartTime").asDouble() * 1000))
                    .put("status", anomaly.path("Status").asText());
        }
        if ("NEW_RECOMMENDATION".equals(type)) {
            message.set("recommendations", recommendations(state, insight));
        }
        for (JsonNode channel : state.get("channels")) {
            JsonNode filters = channel.path("Config").path("Filters");
            if (filters.path("Severities").isArray() && !filters.get("Severities").isEmpty()
                    && !contains(filters.get("Severities"), insight.path("Severity").asText())) {
                continue;
            }
            if (filters.path("MessageTypes").isArray() && !filters.get("MessageTypes").isEmpty()
                    && !contains(filters.get("MessageTypes"), type)) {
                continue;
            }
            String topic = channel.path("Config").path("Sns").path("TopicArn").asText();
            if (sns.isResolvable()) {
                try {
                    sns.get().publish(topic, null, message.toString(), "DevOps Guru " + type, region);
                } catch (AwsException e) {
                    LOG.debugv("Could not notify channel {0}: {1}", topic, e.getMessage());
                }
            }
        }
        String detailType = switch (type) {
            case "NEW_INSIGHT" -> "DevOps Guru New Insight Open";
            case "CLOSED_INSIGHT" -> "DevOps Guru Insight Closed";
            case "NEW_ASSOCIATION" -> "DevOps Guru New Anomaly Association";
            case "SEVERITY_UPGRADED" -> "DevOps Guru Insight Severity Upgraded";
            default -> "DevOps Guru New Recommendation Created";
        };
        if (events.isResolvable()) {
            events.get().publishServiceEvent(SOURCE, detailType, message.toString(),
                    regions.buildArn("devops-guru", region, account(), "insight/" + insight.path("Id").asText()),
                    region);
        }
    }

    /** The AWS console's host for a Region's partition. */
    private static String consoleHost(String region) {
        String suffix = AwsRegions.dnsSuffixFor(region);
        String commercial = "amazonaws.com"; // partition-literal: the commercial suffix whose console is aws.amazon.com
        return commercial.equals(suffix) ? "console.aws.amazon.com" : "console." + suffix; // partition-literal: console
    }

    private static boolean contains(JsonNode list, String value) {
        for (JsonNode item : list) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- insights

    private ObjectNode insight(ObjectNode state, String id) {
        JsonNode insight = state.path("insights").get(id);
        if (insight == null) {
            throw notFound("Insight " + id + " not found.", id, "Insight");
        }
        return (ObjectNode) insight;
    }

    private ObjectNode insightView(ObjectNode insight, boolean summary) {
        ObjectNode view = insight.deepCopy();
        view.remove("_");
        if (summary) {
            view.remove("Description");
            ArrayNode services = view.putObject("ServiceCollection").putArray("ServiceNames");
            new LinkedHashSet<String>(texts(insight.path("_").path("services"))).forEach(services::add);
            ArrayNode arns = view.putArray("AssociatedResourceArns");
            texts(insight.path("_").path("resourceArns")).forEach(arns::add);
        }
        return view;
    }

    private static List<String> texts(JsonNode list) {
        List<String> result = new ArrayList<>();
        list.forEach(item -> result.add(item.asText()));
        return result;
    }

    public synchronized ObjectNode describeInsight(ObjectNode input, String region) {
        accountScope(input);
        ObjectNode state = analyze(region);
        ObjectNode response = mapper.createObjectNode();
        response.set("ReactiveInsight", insightView(insight(state, input.path("Id").asText()), false));
        return response;
    }

    private void accountScope(JsonNode input) {
        if (input.hasNonNull("AccountId") && !account().equals(input.get("AccountId").asText())) {
            throw new AwsException("AccessDeniedException", "Account " + input.get("AccountId").asText()
                    + " is not in your organization's DevOps Guru view.", 403);
        }
    }

    public synchronized ObjectNode deleteInsight(ObjectNode input, String region) {
        ObjectNode state = analyze(region);
        ObjectNode insight = insight(state, input.path("Id").asText());
        if ("ONGOING".equals(insight.path("Status").asText())) {
            throw conflict("Insight " + insight.path("Id").asText() + " is ongoing; only closed insights can be "
                    + "deleted.", insight.path("Id").asText(), "Insight");
        }
        for (JsonNode id : insight.path("_").path("anomalies")) {
            ((ObjectNode) state.get("anomalies")).remove(id.asText());
        }
        ((ObjectNode) state.get("insights")).remove(insight.path("Id").asText());
        ((ObjectNode) state.get("feedback")).remove(insight.path("Id").asText());
        save(region, state);
        return mapper.createObjectNode();
    }

    private boolean inRange(ObjectNode insight, JsonNode range, String start, String end) {
        double from = range.path(start).asDouble(Double.NEGATIVE_INFINITY);
        double to = range.path(end).asDouble(Double.POSITIVE_INFINITY);
        double value = insight.path("InsightTimeRange").path("StartTime").asDouble();
        return value >= from && value <= to;
    }

    public synchronized ObjectNode listInsights(ObjectNode input, String region) {
        ObjectNode state = analyze(region);
        JsonNode filter = input.path("StatusFilter");
        String type;
        String status;
        JsonNode range;
        if (filter.has("Ongoing")) {
            type = filter.get("Ongoing").path("Type").asText();
            status = "ONGOING";
            range = null;
        } else if (filter.has("Closed")) {
            type = filter.get("Closed").path("Type").asText();
            status = "CLOSED";
            range = filter.get("Closed").path("EndTimeRange");
        } else if (filter.has("Any")) {
            type = filter.get("Any").path("Type").asText();
            status = null;
            range = filter.get("Any").path("StartTimeRange");
        } else {
            throw invalid("StatusFilter must be Ongoing, Closed or Any.");
        }
        if (!Set.of("REACTIVE", "PROACTIVE").contains(type)) {
            throw invalid("Type must be REACTIVE or PROACTIVE.");
        }
        List<ObjectNode> items = new ArrayList<>();
        if ("REACTIVE".equals(type)) {
            for (JsonNode node : state.get("insights")) {
                ObjectNode insight = (ObjectNode) node;
                if (status != null && !status.equals(insight.path("Status").asText())) {
                    continue;
                }
                if (filter.has("Closed")) {
                    double end = insight.path("InsightTimeRange").path("EndTime").asDouble();
                    if (end < range.path("FromTime").asDouble(Double.NEGATIVE_INFINITY)
                            || end > range.path("ToTime").asDouble(Double.POSITIVE_INFINITY)) {
                        continue;
                    }
                } else if (range != null && !inRange(insight, range, "FromTime", "ToTime")) {
                    continue;
                }
                items.add(insightView(insight, true));
            }
        }
        ObjectNode response = page(items, input, "ReactiveInsights", 100);
        response.putArray("ProactiveInsights");
        return response;
    }

    public synchronized ObjectNode searchInsights(ObjectNode input, String region) {
        ObjectNode state = analyze(region);
        String type = input.path("Type").asText();
        if (!Set.of("REACTIVE", "PROACTIVE").contains(type)) {
            throw invalid("Type must be REACTIVE or PROACTIVE.");
        }
        JsonNode range = input.path("StartTimeRange");
        JsonNode filters = input.path("Filters");
        List<ObjectNode> items = new ArrayList<>();
        if ("REACTIVE".equals(type)) {
            for (JsonNode node : state.get("insights")) {
                ObjectNode insight = (ObjectNode) node;
                if (!inRange(insight, range, "FromTime", "ToTime")
                        || (filters.path("Severities").size() > 0 && !contains(filters.get("Severities"),
                        insight.path("Severity").asText()))
                        || (filters.path("Statuses").size() > 0 && !contains(filters.get("Statuses"),
                        insight.path("Status").asText()))
                        || !matchesCollection(insight, filters.path("ResourceCollection"))
                        || (filters.path("ServiceCollection").path("ServiceNames").size() > 0
                        && texts(insight.path("_").path("services")).stream().noneMatch(service ->
                        contains(filters.get("ServiceCollection").get("ServiceNames"), service)))) {
                    continue;
                }
                items.add(insightView(insight, true));
            }
        }
        ObjectNode response = page(items, input, "ReactiveInsights", 100);
        response.putArray("ProactiveInsights");
        return response;
    }

    private boolean matchesCollection(ObjectNode insight, JsonNode filter) {
        if (filter.isMissingNode() || filter.isEmpty()) {
            return true;
        }
        JsonNode collection = insight.path("ResourceCollection");
        for (JsonNode stack : filter.path("CloudFormation").path("StackNames")) {
            if (contains(collection.path("CloudFormation").path("StackNames"), stack.asText())) {
                return true;
            }
        }
        for (JsonNode tag : filter.path("Tags")) {
            for (JsonNode own : collection.path("Tags")) {
                if (tag.path("AppBoundaryKey").asText().equalsIgnoreCase(own.path("AppBoundaryKey").asText())) {
                    for (JsonNode value : tag.path("TagValues")) {
                        if (contains(own.path("TagValues"), value.asText())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public synchronized ObjectNode describeAnomaly(ObjectNode input, String region) {
        accountScope(input);
        ObjectNode state = analyze(region);
        String id = input.path("Id").asText();
        JsonNode anomaly = state.path("anomalies").get(id);
        if (anomaly == null) {
            throw notFound("Anomaly " + id + " not found.", id, "Anomaly");
        }
        ObjectNode view = anomaly.deepCopy();
        view.remove("_");
        ObjectNode response = mapper.createObjectNode();
        response.set("ReactiveAnomaly", view);
        return response;
    }

    public synchronized ObjectNode listAnomaliesForInsight(ObjectNode input, String region) {
        accountScope(input);
        ObjectNode state = analyze(region);
        ObjectNode insight = insight(state, input.path("InsightId").asText());
        JsonNode range = input.path("StartTimeRange");
        JsonNode services = input.path("Filters").path("ServiceCollection").path("ServiceNames");
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode id : insight.path("_").path("anomalies")) {
            JsonNode anomaly = state.path("anomalies").path(id.asText());
            double start = anomaly.path("AnomalyTimeRange").path("StartTime").asDouble();
            if (start < range.path("FromTime").asDouble(Double.NEGATIVE_INFINITY)
                    || start > range.path("ToTime").asDouble(Double.POSITIVE_INFINITY)) {
                continue;
            }
            if (services.size() > 0) {
                boolean match = false;
                for (JsonNode resource : anomaly.path("AnomalyResources")) {
                    match |= contains(services, Optional.ofNullable(serviceName(resource.path("Type").asText()))
                            .orElse(""));
                }
                if (!match) {
                    continue;
                }
            }
            ObjectNode view = ((ObjectNode) anomaly).deepCopy();
            view.remove("_");
            items.add(view);
        }
        ObjectNode response = page(items, input, "ReactiveAnomalies", 500);
        response.putArray("ProactiveAnomalies");
        return response;
    }

    public synchronized ObjectNode listRecommendations(ObjectNode input, String region) {
        accountScope(input);
        ObjectNode state = analyze(region);
        ObjectNode insight = insight(state, input.path("InsightId").asText());
        List<ObjectNode> items = new ArrayList<>();
        recommendations(state, insight).forEach(item -> items.add((ObjectNode) item));
        return page(items, input, "Recommendations", 100);
    }

    /** One recommendation per anomalous metric, by the metric's service. */
    private ArrayNode recommendations(ObjectNode state, ObjectNode insight) {
        ArrayNode result = mapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode id : insight.path("_").path("anomalies")) {
            JsonNode anomaly = state.path("anomalies").path(id.asText());
            JsonNode metric = anomaly.path("SourceDetails").path("CloudWatchMetrics").path(0);
            String namespace = metric.path("Namespace").asText();
            String name = metric.path("MetricName").asText();
            if (!seen.add(namespace + "/" + name)) {
                continue;
            }
            String[] advice = advice(namespace, name);
            ObjectNode recommendation = result.addObject();
            recommendation.put("Name", advice[0]);
            recommendation.put("Description", advice[1]);
            recommendation.put("Link", advice[2]);
            recommendation.put("Reason", "The " + name + " metric in " + namespace + " is anomalous.");
            recommendation.put("Category", advice[3]);
            recommendation.putArray("RelatedEvents");
            ObjectNode related = recommendation.putArray("RelatedAnomalies").addObject();
            related.put("AnomalyId", id.asText());
            related.set("Resources", anomaly.path("AnomalyResources").deepCopy());
            related.putArray("SourceDetails").addObject().putArray("CloudWatchMetrics").addObject()
                    .put("MetricName", name).put("Namespace", namespace);
        }
        return result;
    }

    private static String[] advice(String namespace, String metric) {
        String docs = "https://docs.aws.amazon.com/"; // partition-literal: documentation links are global
        return switch (namespace) {
            case "AWS/Lambda" -> metric.toLowerCase(Locale.ROOT).contains("throttl")
                    ? new String[] {"Raise the function's concurrency", "Throttles mean the function reached its "
                    + "concurrency limit; raise its reserved concurrency or the account quota.",
                    docs + "lambda/latest/dg/configuration-concurrency.html", "Configuration"}
                    : new String[] {"Troubleshoot function errors", "Check the function's logs for the error that "
                    + "began with the anomaly and roll back the deployment that introduced it.",
                    docs + "lambda/latest/dg/invocation-retries.html", "Troubleshooting"};
            case "AWS/DynamoDB" -> new String[] {"Review the table's capacity", "Throttled or slow requests point "
                    + "to hot partitions or provisioned capacity below demand; consider on-demand capacity.",
                    docs + "amazondynamodb/latest/developerguide/bp-partition-key-design.html", "Capacity"};
            case "AWS/SQS" -> new String[] {"Scale the queue's consumers", "Messages age when consumers fall "
                    + "behind; add consumers or raise their concurrency.",
                    docs + "AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-best-practices.html", "Scaling"};
            case "AWS/ApiGateway" -> new String[] {"Trace failing API requests", "Enable access logging and "
                    + "X-Ray tracing to find the integration behind the errors.",
                    docs + "apigateway/latest/developerguide/monitoring-cloudwatch.html", "Troubleshooting"};
            case "AWS/RDS" -> new String[] {"Inspect database load", "Use Performance Insights to find the "
                    + "queries driving the anomalous load.",
                    docs + "AmazonRDS/latest/UserGuide/USER_PerfInsights.html", "Performance"};
            default -> new String[] {"Investigate the anomalous metric", "Correlate the metric with recent "
                    + "deployments and configuration changes around the anomaly's start.",
                    docs + "devops-guru/latest/userguide/working-with-insights.html", "Troubleshooting"};
        };
    }

    public synchronized ObjectNode listEvents(ObjectNode input, String region) {
        accountScope(input);
        JsonNode filters = input.path("Filters");
        if (!filters.isObject()) {
            throw invalid("Filters is required.");
        }
        ObjectNode state = analyze(region);
        double from = filters.path("EventTimeRange").path("FromTime").asDouble(0);
        double to = filters.path("EventTimeRange").path("ToTime").asDouble(now());
        Set<String> resources = new LinkedHashSet<>();
        if (filters.hasNonNull("InsightId")) {
            ObjectNode insight = insight(state, filters.get("InsightId").asText());
            resources.addAll(texts(insight.path("_").path("resources")));
            from = Math.max(from, insight.path("InsightTimeRange").path("StartTime").asDouble() - 3600);
        } else {
            for (Monitored resource : monitored(region, state.get("collection"))) {
                resources.add(resource.name());
            }
        }
        List<ObjectNode> items = new ArrayList<>();
        if (trail.isResolvable() && !"AWS_CODE_DEPLOY".equals(filters.path("DataSource").asText())) {
            ObjectNode request = mapper.createObjectNode();
            request.put("StartTime", from);
            request.put("EndTime", to);
            request.put("MaxResults", 50);
            JsonNode found;
            try {
                found = trail.get().lookup(request, region).path("Events");
            } catch (AwsException e) {
                found = mapper.createArrayNode();
            }
            for (JsonNode event : found) {
                List<ObjectNode> eventResources = new ArrayList<>();
                for (JsonNode resource : event.path("Resources")) {
                    String name = resource.path("ResourceName").asText();
                    if (resources.contains(name(name))) {
                        ObjectNode entry = mapper.createObjectNode().put("Type", resource.path("ResourceType")
                                .asText()).put("Name", name(name));
                        if (AwsArnUtils.isArn(name)) {
                            entry.put("Arn", name);
                        }
                        eventResources.add(entry);
                    }
                }
                if (eventResources.isEmpty()) {
                    continue;
                }
                String eventName = event.path("EventName").asText();
                String eventClass = eventClass(eventName, event.path("EventSource").asText());
                if (filters.hasNonNull("EventClass") && !filters.get("EventClass").asText().equals(eventClass)) {
                    continue;
                }
                String source = event.path("EventSource").asText();
                if (filters.hasNonNull("EventSource") && !filters.get("EventSource").asText().equals(source)) {
                    continue;
                }
                ObjectNode item = mapper.createObjectNode();
                item.put("Id", event.path("EventId").asText());
                item.put("Time", event.path("EventTime").asDouble());
                item.put("EventSource", source);
                item.put("Name", eventName);
                item.put("DataSource", "AWS_CLOUD_TRAIL");
                item.put("EventClass", eventClass);
                ArrayNode list = item.putArray("Resources");
                eventResources.forEach(list::add);
                items.add(item);
            }
        }
        return page(items, input, "Events", 50);
    }

    private static String eventClass(String name, String source) {
        if (source.startsWith("iam.") || source.startsWith("kms.") || name.contains("Policy")
                || name.contains("Permission")) {
            return "SECURITY_CHANGE";
        }
        if (name.contains("Code") || name.startsWith("Deploy") || name.startsWith("Publish")) {
            return "DEPLOYMENT";
        }
        if (name.contains("Schema") || name.contains("Table")) {
            return "SCHEMA_CHANGE";
        }
        if (name.startsWith("Create") || name.startsWith("Update") || name.startsWith("Put")
                || name.startsWith("Delete") || name.startsWith("Modify")) {
            return "CONFIG_CHANGE";
        }
        return "INFRASTRUCTURE";
    }

    public synchronized ObjectNode listAnomalousLogGroups(ObjectNode input, String region) {
        ObjectNode state = analyze(region);
        String insightId = input.path("InsightId").asText();
        ObjectNode insight = insight(state, insightId);
        List<ObjectNode> items = new ArrayList<>();
        boolean enabled = "ENABLED".equals(state.path("integration").path("LogsAnomalyDetection")
                .path("OptInStatus").asText());
        if (enabled && logs.isResolvable()) {
            long start = (long) (insight.path("InsightTimeRange").path("StartTime").asDouble() * 1000) - 600_000;
            long end = insight.path("InsightTimeRange").has("EndTime")
                    ? (long) (insight.path("InsightTimeRange").path("EndTime").asDouble() * 1000)
                    : System.currentTimeMillis();
            List<String> resources = texts(insight.path("_").path("resources"));
            List<String> types = texts(insight.path("_").path("types"));
            for (int index = 0; index < resources.size(); index++) {
                if (!"AWS::Lambda::Function".equals(types.size() > index ? types.get(index) : "")) {
                    continue;
                }
                String group = "/aws/lambda/" + resources.get(index);
                CloudWatchLogsService.FilteredLogEventsResult found;
                try {
                    found = logs.get().filterLogEvents(group, null, start, end, "?ERROR ?Error ?Exception", 100,
                            region);
                } catch (AwsException e) {
                    continue;
                }
                if (found.events().isEmpty()) {
                    continue;
                }
                ObjectNode item = mapper.createObjectNode();
                item.put("LogGroupName", group);
                item.put("ImpactStartTime", found.events().get(0).event().getTimestamp() / 1000.0);
                item.put("ImpactEndTime", found.events().get(found.events().size() - 1).event().getTimestamp()
                        / 1000.0);
                item.put("NumberOfLogLinesScanned", found.events().size());
                ArrayNode classes = item.putArray("LogAnomalyShowcases").addObject().putArray("LogAnomalyClasses");
                CloudWatchLogsService.FilteredEvent first = found.events().get(0);
                classes.addObject().put("LogStreamName", first.logStreamName()).put("LogAnomalyType", "KEYWORD")
                        .put("LogAnomalyToken", "ERROR").put("LogEventId", first.event().getEventId() == null ? ""
                        : first.event().getEventId()).put("Explanation", "Error lines appeared in the log group "
                        + "during the insight.").put("NumberOfLogLinesOccurrences", found.events().size())
                        .put("LogEventTimestamp", first.event().getTimestamp() / 1000.0);
                items.add(item);
            }
        }
        ObjectNode response = page(items, input, "AnomalousLogGroups", 200);
        response.put("InsightId", insightId);
        return response;
    }

    // ---------------------------------------------------------------- health and overview

    private long hours(ObjectNode state) {
        double since = state.path("collection").path("since").asDouble(now());
        return Math.max(0, (long) Math.ceil((now() - since) / 3600.0));
    }

    private int metricsAnalyzed(String region, List<Monitored> resources) {
        if (!metrics.isResolvable() || resources.isEmpty()) {
            return 0;
        }
        Set<String> names = new LinkedHashSet<>();
        resources.forEach(resource -> names.add(resource.name()));
        int count = 0;
        for (CloudWatchMetricsService.MetricIdentity metric : metrics.get().listMetrics(null, null, null, region)) {
            for (Dimension dimension : metric.dimensions()) {
                if (RESOURCE_DIMENSIONS.contains(dimension.name()) && names.contains(name(dimension.value()))) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    public synchronized ObjectNode describeAccountHealth(ObjectNode input, String region) {
        ObjectNode state = analyze(region);
        List<Monitored> resources = monitored(region, state.get("collection"));
        ObjectNode response = mapper.createObjectNode();
        response.put("OpenReactiveInsights", count(state, "ONGOING", null));
        response.put("OpenProactiveInsights", 0);
        response.put("MetricsAnalyzed", metricsAnalyzed(region, resources));
        response.put("ResourceHours", hours(state) * resources.size());
        response.put("AnalyzedResourceCount", resources.size());
        return response;
    }

    private int count(ObjectNode state, String status, String unit) {
        int count = 0;
        for (JsonNode insight : state.get("insights")) {
            if ((status == null || status.equals(insight.path("Status").asText()))
                    && (unit == null || unit.equals(insight.path("_").path("unit").asText()))) {
                count++;
            }
        }
        return count;
    }

    public synchronized ObjectNode describeAccountOverview(ObjectNode input, String region) {
        ObjectNode state = analyze(region);
        double from = input.path("FromTime").asDouble(Double.NaN);
        if (Double.isNaN(from)) {
            throw invalid("FromTime is required.");
        }
        double to = input.path("ToTime").asDouble(now());
        int reactive = 0;
        long recovery = 0;
        int closed = 0;
        for (JsonNode insight : state.get("insights")) {
            double start = insight.path("InsightTimeRange").path("StartTime").asDouble();
            if (start >= from && start <= to) {
                reactive++;
                if (insight.path("InsightTimeRange").has("EndTime")) {
                    recovery += (long) ((insight.path("InsightTimeRange").path("EndTime").asDouble() - start) * 1000);
                    closed++;
                }
            }
        }
        ObjectNode response = mapper.createObjectNode();
        response.put("ReactiveInsights", reactive);
        response.put("ProactiveInsights", 0);
        response.put("MeanTimeToRecoverInMilliseconds", closed == 0 ? 0 : recovery / closed);
        return response;
    }

    public synchronized ObjectNode describeResourceCollectionHealth(ObjectNode input, String region) {
        ObjectNode state = analyze(region);
        String type = input.path("ResourceCollectionType").asText();
        List<Monitored> resources = monitored(region, state.get("collection"));
        ObjectNode response = mapper.createObjectNode();
        List<ObjectNode> items = new ArrayList<>();
        String field;
        switch (type) {
            case "AWS_CLOUD_FORMATION" -> {
                field = "CloudFormation";
                for (JsonNode stack : state.path("collection").path("stacks")) {
                    if ("*".equals(stack.asText())) {
                        continue;
                    }
                    ObjectNode item = mapper.createObjectNode();
                    item.put("StackName", stack.asText());
                    item.set("Insight", health(state, "stack:" + stack.asText()));
                    item.put("AnalyzedResourceCount", resources.stream().filter(resource -> stack.asText()
                            .equals(resource.stack())).count());
                    items.add(item);
                }
            }
            case "AWS_SERVICE" -> {
                field = "Service";
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (Monitored resource : resources) {
                    String service = serviceName(resource.type());
                    if (service != null) {
                        counts.merge(service, 1, Integer::sum);
                    }
                }
                counts.forEach((service, count) -> {
                    ObjectNode item = mapper.createObjectNode();
                    item.put("ServiceName", service);
                    int open = 0;
                    for (JsonNode insight : state.get("insights")) {
                        if ("ONGOING".equals(insight.path("Status").asText())
                                && texts(insight.path("_").path("services")).contains(service)) {
                            open++;
                        }
                    }
                    item.putObject("Insight").put("OpenProactiveInsights", 0).put("OpenReactiveInsights", open);
                    item.put("AnalyzedResourceCount", count);
                    items.add(item);
                });
            }
            case "AWS_TAGS" -> {
                field = "Tags";
                state.path("collection").path("tags").properties().forEach(entry -> {
                    for (JsonNode value : entry.getValue()) {
                        ObjectNode item = mapper.createObjectNode();
                        item.put("AppBoundaryKey", entry.getKey());
                        item.put("TagValue", value.asText());
                        item.set("Insight", health(state, "tag:" + entry.getKey() + "=" + value.asText()));
                        item.put("AnalyzedResourceCount", resources.stream().filter(resource ->
                                value.asText().equals(resource.tagValue())).count());
                        items.add(item);
                    }
                });
            }
            default -> throw invalid("ResourceCollectionType must be AWS_CLOUD_FORMATION, AWS_SERVICE or AWS_TAGS.");
        }
        ObjectNode paged = page(items, input, field, 500);
        response.setAll(paged);
        for (String other : List.of("CloudFormation", "Service", "Tags")) {
            if (!response.has(other)) {
                response.putArray(other);
            }
        }
        return response;
    }

    private ObjectNode health(ObjectNode state, String unit) {
        long recovery = 0;
        int closed = 0;
        for (JsonNode insight : state.get("insights")) {
            if (unit.equals(insight.path("_").path("unit").asText()) && insight.path("InsightTimeRange")
                    .has("EndTime")) {
                recovery += (long) ((insight.path("InsightTimeRange").path("EndTime").asDouble()
                        - insight.path("InsightTimeRange").path("StartTime").asDouble()) * 1000);
                closed++;
            }
        }
        return mapper.createObjectNode().put("OpenProactiveInsights", 0)
                .put("OpenReactiveInsights", count(state, "ONGOING", unit))
                .put("MeanTimeToRecoverInMilliseconds", closed == 0 ? 0 : recovery / closed);
    }

    // ---------------------------------------------------------------- organization

    /** The accounts an organization view covers: those named, or the caller's own account. */
    private List<String> accounts(JsonNode input) {
        List<String> accounts = new ArrayList<>();
        input.path("AccountIds").forEach(id -> accounts.add(id.asText()));
        if (input.path("OrganizationalUnitIds").size() > 0 && accounts.isEmpty()) {
            accounts.add(account());
        }
        if (accounts.isEmpty()) {
            accounts.add(account());
        }
        for (String id : accounts) {
            if (!id.matches("^\\d{12}$")) {
                throw invalid("AccountIds must be 12-digit account IDs.");
            }
        }
        return accounts;
    }

    private ObjectNode stateOf(String accountId, String region) {
        return storage.getForAccount(accountId, region + "/state").orElse(null);
    }

    public synchronized ObjectNode describeOrganizationHealth(ObjectNode input, String region) {
        analyze(region);
        int open = 0;
        long hours = 0;
        for (String accountId : accounts(input)) {
            ObjectNode state = stateOf(accountId, region);
            if (state != null) {
                open += count(state, "ONGOING", null);
                hours += hours(state);
            }
        }
        ObjectNode response = mapper.createObjectNode();
        response.put("OpenReactiveInsights", open);
        response.put("OpenProactiveInsights", 0);
        response.put("MetricsAnalyzed", metricsAnalyzed(region, monitored(region, state(region).get("collection"))));
        response.put("ResourceHours", hours);
        return response;
    }

    public synchronized ObjectNode describeOrganizationOverview(ObjectNode input, String region) {
        analyze(region);
        double from = input.path("FromTime").asDouble(Double.NaN);
        if (Double.isNaN(from)) {
            throw invalid("FromTime is required.");
        }
        double to = input.path("ToTime").asDouble(now());
        int reactive = 0;
        for (String accountId : accounts(input)) {
            ObjectNode state = stateOf(accountId, region);
            if (state == null) {
                continue;
            }
            for (JsonNode insight : state.get("insights")) {
                double start = insight.path("InsightTimeRange").path("StartTime").asDouble();
                reactive += start >= from && start <= to ? 1 : 0;
            }
        }
        return mapper.createObjectNode().put("ReactiveInsights", reactive).put("ProactiveInsights", 0);
    }

    public synchronized ObjectNode describeOrganizationResourceCollectionHealth(ObjectNode input, String region) {
        String type = input.path("OrganizationResourceCollectionType").asText();
        if ("AWS_ACCOUNT".equals(type)) {
            analyze(region);
            List<ObjectNode> items = new ArrayList<>();
            for (String accountId : accounts(input)) {
                ObjectNode state = stateOf(accountId, region);
                ObjectNode item = mapper.createObjectNode();
                item.put("AccountId", accountId);
                item.putObject("Insight").put("OpenProactiveInsights", 0).put("OpenReactiveInsights",
                        state == null ? 0 : count(state, "ONGOING", null));
                items.add(item);
            }
            ObjectNode response = page(items, input, "Account", 500);
            response.putArray("CloudFormation");
            response.putArray("Service");
            response.putArray("Tags");
            return response;
        }
        ObjectNode request = input.deepCopy();
        request.put("ResourceCollectionType", type);
        if (!Set.of("AWS_CLOUD_FORMATION", "AWS_SERVICE", "AWS_TAGS").contains(type)) {
            throw invalid("OrganizationResourceCollectionType must be AWS_CLOUD_FORMATION, AWS_SERVICE, AWS_ACCOUNT "
                    + "or AWS_TAGS.");
        }
        accounts(input);
        ObjectNode response = describeResourceCollectionHealth(request, region);
        response.putArray("Account");
        return response;
    }

    private List<ObjectNode> organizationInsights(JsonNode input, String region, boolean search) {
        analyze(region);
        List<ObjectNode> items = new ArrayList<>();
        for (String accountId : accounts(input)) {
            ObjectNode state = stateOf(accountId, region);
            if (state == null) {
                continue;
            }
            for (JsonNode node : state.get("insights")) {
                ObjectNode insight = (ObjectNode) node;
                ObjectNode view = insightView(insight, true);
                view.remove("AssociatedResourceArns");
                view.put("AccountId", accountId);
                items.add(view);
            }
        }
        return items;
    }

    public synchronized ObjectNode listOrganizationInsights(ObjectNode input, String region) {
        JsonNode filter = input.path("StatusFilter");
        String status = filter.has("Ongoing") ? "ONGOING" : filter.has("Closed") ? "CLOSED" : null;
        String type = filter.elements().hasNext() ? filter.elements().next().path("Type").asText() : "";
        List<ObjectNode> items = new ArrayList<>();
        if ("REACTIVE".equals(type)) {
            for (ObjectNode insight : organizationInsights(input, region, false)) {
                if (status == null || status.equals(insight.path("Status").asText())) {
                    items.add(insight);
                }
            }
        } else if (!"PROACTIVE".equals(type)) {
            throw invalid("StatusFilter must name a REACTIVE or PROACTIVE type.");
        }
        ObjectNode response = page(items, input, "ReactiveInsights", 100);
        response.putArray("ProactiveInsights");
        return response;
    }

    public synchronized ObjectNode searchOrganizationInsights(ObjectNode input, String region) {
        if (!input.path("AccountIds").isArray() || input.get("AccountIds").isEmpty()) {
            throw invalid("AccountIds is required.");
        }
        String type = input.path("Type").asText();
        JsonNode range = input.path("StartTimeRange");
        JsonNode filters = input.path("Filters");
        List<ObjectNode> items = new ArrayList<>();
        if ("REACTIVE".equals(type)) {
            for (ObjectNode insight : organizationInsights(input, region, true)) {
                double start = insight.path("InsightTimeRange").path("StartTime").asDouble();
                if (start < range.path("FromTime").asDouble(Double.NEGATIVE_INFINITY)
                        || start > range.path("ToTime").asDouble(Double.POSITIVE_INFINITY)
                        || (filters.path("Severities").size() > 0 && !contains(filters.get("Severities"),
                        insight.path("Severity").asText()))
                        || (filters.path("Statuses").size() > 0 && !contains(filters.get("Statuses"),
                        insight.path("Status").asText()))) {
                    continue;
                }
                items.add(insight);
            }
        } else if (!"PROACTIVE".equals(type)) {
            throw invalid("Type must be REACTIVE or PROACTIVE.");
        }
        ObjectNode response = page(items, input, "ReactiveInsights", 100);
        response.putArray("ProactiveInsights");
        return response;
    }

    // ---------------------------------------------------------------- integrations and event sources

    public synchronized ObjectNode describeServiceIntegration(ObjectNode input, String region) {
        JsonNode integration = state(region).path("integration");
        ObjectNode config = mapper.createObjectNode();
        config.putObject("OpsCenter").put("OptInStatus", integration.path("OpsCenter").path("OptInStatus")
                .asText("DISABLED"));
        config.putObject("LogsAnomalyDetection").put("OptInStatus", integration.path("LogsAnomalyDetection")
                .path("OptInStatus").asText("DISABLED"));
        ObjectNode encryption = config.putObject("KMSServerSideEncryption");
        JsonNode kmsConfig = integration.path("KMSServerSideEncryption");
        encryption.put("OptInStatus", kmsConfig.path("OptInStatus").asText("ENABLED"));
        encryption.put("Type", kmsConfig.path("Type").asText("AWS_OWNED_KMS_KEY"));
        if (kmsConfig.hasNonNull("KMSKeyId")) {
            encryption.put("KMSKeyId", kmsConfig.get("KMSKeyId").asText());
        }
        ObjectNode response = mapper.createObjectNode();
        response.set("ServiceIntegration", config);
        return response;
    }

    public synchronized ObjectNode updateServiceIntegration(ObjectNode input, String region) {
        JsonNode update = input.path("ServiceIntegration");
        if (!update.isObject()) {
            throw invalid("ServiceIntegration is required.");
        }
        ObjectNode state = state(region);
        ObjectNode integration = (ObjectNode) state.get("integration");
        for (String name : List.of("OpsCenter", "LogsAnomalyDetection")) {
            if (update.path(name).hasNonNull("OptInStatus")) {
                String status = update.get(name).get("OptInStatus").asText();
                if (!Set.of("ENABLED", "DISABLED").contains(status)) {
                    throw invalid(name + ".OptInStatus must be ENABLED or DISABLED.");
                }
                integration.putObject(name).put("OptInStatus", status);
            }
        }
        JsonNode encryption = update.path("KMSServerSideEncryption");
        if (encryption.isObject() && !encryption.isEmpty()) {
            String type = encryption.path("Type").asText("AWS_OWNED_KMS_KEY");
            if (!Set.of("CUSTOMER_MANAGED_KEY", "AWS_OWNED_KMS_KEY").contains(type)) {
                throw invalid("KMSServerSideEncryption.Type must be CUSTOMER_MANAGED_KEY or AWS_OWNED_KMS_KEY.");
            }
            ObjectNode stored = mapper.createObjectNode();
            stored.put("OptInStatus", encryption.path("OptInStatus").asText("ENABLED"));
            stored.put("Type", type);
            if ("CUSTOMER_MANAGED_KEY".equals(type)) {
                String key = encryption.path("KMSKeyId").asText(null);
                if (key == null) {
                    throw invalid("A CUSTOMER_MANAGED_KEY needs KMSKeyId.");
                }
                if (kms.isResolvable()) {
                    try {
                        kms.get().describeKey(key, region);
                    } catch (AwsException e) {
                        throw invalid("KMS key " + key + " does not exist.");
                    }
                }
                stored.put("KMSKeyId", key);
            }
            integration.set("KMSServerSideEncryption", stored);
        }
        save(region, state);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode describeEventSourcesConfig(ObjectNode input, String region) {
        ObjectNode response = mapper.createObjectNode();
        response.putObject("EventSources").putObject("AmazonCodeGuruProfiler").put("Status",
                state(region).path("eventSources").path("AmazonCodeGuruProfiler").path("Status").asText("DISABLED"));
        return response;
    }

    public synchronized ObjectNode updateEventSourcesConfig(ObjectNode input, String region) {
        ObjectNode state = state(region);
        JsonNode profiler = input.path("EventSources").path("AmazonCodeGuruProfiler");
        if (profiler.hasNonNull("Status")) {
            String status = profiler.get("Status").asText();
            if (!Set.of("ENABLED", "DISABLED").contains(status)) {
                throw invalid("AmazonCodeGuruProfiler.Status must be ENABLED or DISABLED.");
            }
            ((ObjectNode) state.get("eventSources")).putObject("AmazonCodeGuruProfiler").put("Status", status);
        }
        save(region, state);
        return mapper.createObjectNode();
    }

    // ---------------------------------------------------------------- notification channels

    public synchronized ObjectNode addNotificationChannel(ObjectNode input, String region) {
        JsonNode config = input.path("Config");
        String topic = config.path("Sns").path("TopicArn").asText(null);
        if (topic == null) {
            throw invalid("Config.Sns.TopicArn is required.");
        }
        if (sns.isResolvable() && !sns.get().topicExists(topic, region)) {
            throw notFound("Topic " + topic + " not found.", topic, "SnsTopic");
        }
        for (String field : List.of("Severities", "MessageTypes")) {
            JsonNode values = config.path("Filters").path(field);
            Set<String> allowed = "Severities".equals(field) ? Set.of("LOW", "MEDIUM", "HIGH")
                    : Set.of("NEW_INSIGHT", "CLOSED_INSIGHT", "NEW_ASSOCIATION", "SEVERITY_UPGRADED",
                    "NEW_RECOMMENDATION");
            for (JsonNode value : values) {
                if (!allowed.contains(value.asText())) {
                    throw invalid("Filters." + field + " holds an unknown value " + value.asText() + ".");
                }
            }
        }
        ObjectNode state = state(region);
        ObjectNode channels = (ObjectNode) state.get("channels");
        for (JsonNode channel : channels) {
            if (topic.equals(channel.path("Config").path("Sns").path("TopicArn").asText())) {
                throw conflict("A notification channel for topic " + topic + " already exists.",
                        channel.path("Id").asText(), "NotificationChannel");
            }
        }
        if (channels.size() >= MAX_CHANNELS) {
            throw new AwsException("ServiceQuotaExceededException", "An account can have at most " + MAX_CHANNELS
                    + " notification channels.", 402);
        }
        String id = UUID.randomUUID().toString();
        ObjectNode channel = mapper.createObjectNode();
        channel.put("Id", id);
        channel.set("Config", config.deepCopy());
        channels.set(id, channel);
        save(region, state);
        return mapper.createObjectNode().put("Id", id);
    }

    public synchronized ObjectNode removeNotificationChannel(ObjectNode input, String region) {
        ObjectNode state = state(region);
        ((ObjectNode) state.get("channels")).remove(input.path("Id").asText());
        save(region, state);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode listNotificationChannels(ObjectNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        state(region).get("channels").forEach(channel -> items.add(((ObjectNode) channel).deepCopy()));
        return page(items, input, "Channels", 100);
    }

    // ---------------------------------------------------------------- feedback

    public synchronized ObjectNode putFeedback(ObjectNode input, String region) {
        JsonNode feedback = input.path("InsightFeedback");
        String id = feedback.path("Id").asText(null);
        if (id == null) {
            throw invalid("InsightFeedback.Id is required.");
        }
        ObjectNode state = analyze(region);
        insight(state, id);
        String option = feedback.path("Feedback").asText(null);
        if (option != null && !Set.of("VALID_COLLECTION", "RECOMMENDATION_USEFUL", "ALERT_TOO_SENSITIVE",
                "DATA_NOISY_ANOMALY", "DATA_INCORRECT").contains(option)) {
            throw invalid("Feedback holds an unknown option " + option + ".");
        }
        ObjectNode stored = mapper.createObjectNode().put("Id", id);
        if (option != null) {
            stored.put("Feedback", option);
        }
        ((ObjectNode) state.get("feedback")).set(id, stored);
        save(region, state);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode describeFeedback(ObjectNode input, String region) {
        ObjectNode state = state(region);
        String id = input.path("InsightId").asText(null);
        ObjectNode response = mapper.createObjectNode();
        if (id == null) {
            return response;
        }
        JsonNode feedback = state.path("feedback").get(id);
        if (feedback == null) {
            throw notFound("No feedback for insight " + id + ".", id, "InsightFeedback");
        }
        response.set("InsightFeedback", feedback.deepCopy());
        return response;
    }

    // ---------------------------------------------------------------- cost estimation

    public synchronized ObjectNode startCostEstimation(ObjectNode input, String region) {
        JsonNode filter = input.path("ResourceCollection");
        boolean stacks = filter.path("CloudFormation").path("StackNames").size() > 0;
        boolean tags = filter.path("Tags").size() > 0;
        if (stacks == tags) {
            throw invalid("Specify either CloudFormation stack names or tags.");
        }
        ObjectNode collection = mapper.createObjectNode();
        if (stacks) {
            collection.set("stacks", filter.get("CloudFormation").get("StackNames").deepCopy());
        } else {
            ObjectNode map = collection.putObject("tags");
            for (JsonNode tag : filter.get("Tags")) {
                map.set(tag.path("AppBoundaryKey").asText(), tag.path("TagValues").deepCopy());
            }
        }
        ObjectNode state = state(region);
        ObjectNode estimation = mapper.createObjectNode();
        estimation.set("ResourceCollection", filter.deepCopy());
        estimation.set("collection", collection);
        estimation.put("readyAt", System.currentTimeMillis() + transitionMillis);
        state.set("estimation", estimation);
        save(region, state);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode getCostEstimation(ObjectNode input, String region) {
        JsonNode estimation = state(region).path("estimation");
        if (estimation.isMissingNode()) {
            throw notFound("No cost estimation has been started.", "cost-estimation", "CostEstimation");
        }
        ObjectNode response = mapper.createObjectNode();
        response.set("ResourceCollection", estimation.get("ResourceCollection").deepCopy());
        if (System.currentTimeMillis() < estimation.path("readyAt").asLong()) {
            response.put("Status", "ONGOING");
            response.putArray("Costs");
            return response;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Monitored resource : monitored(region, estimation.get("collection"))) {
            counts.merge(resource.type(), 1, Integer::sum);
        }
        List<ObjectNode> costs = new ArrayList<>();
        double total = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            double cost = entry.getValue() * UNIT_COST * HOURS_PER_MONTH;
            total += cost;
            costs.add(mapper.createObjectNode().put("Type", entry.getKey()).put("State", "ACTIVE")
                    .put("Count", entry.getValue()).put("UnitCost", UNIT_COST).put("Cost", cost));
        }
        response.setAll(page(costs, input, "Costs", 1000));
        response.put("Status", "COMPLETED");
        double now = now();
        response.putObject("TimeRange").put("StartTime", now).put("EndTime", now
                + Duration.ofDays(30).toSeconds());
        response.put("TotalCost", total);
        return response;
    }
}
