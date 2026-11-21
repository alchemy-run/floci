package io.floci.aws.services.resourcegroups;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationService;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.resourcegroups.model.ResourceGroup.ResourceQuery;
import io.floci.aws.services.resourcegroupstagging.ResourceGroupsTaggingService;
import io.floci.aws.services.resourcegroupstagging.model.ResourceTagMapping;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Resolves a group's resource query into the resources it selects.
 * <p>
 * {@code TAG_FILTERS_1_0} reads the account's tagged resources through the Resource Groups Tagging
 * service: every tag filter must match (one of its values, or any value when it lists none), and the
 * resource type must be one of {@code ResourceTypeFilters} ({@code AWS::AllSupported} for any).
 * {@code CLOUDFORMATION_STACK_1_0} lists the resources of a stack in the Region that have an ARN. A
 * resource's type is its CloudFormation type, as the Resource Groups API reports it.
 */
@ApplicationScoped
public class ResourceGroupsQueries {
    static final String TAG_QUERY = "TAG_FILTERS_1_0";
    static final String STACK_QUERY = "CLOUDFORMATION_STACK_1_0";
    static final String ALL_SUPPORTED = "AWS::AllSupported";

    /** Service segments whose CloudFormation namespace is not the capitalized service name. */
    private static final Map<String, String> SERVICE_NAMESPACES = Map.ofEntries(
            Map.entry("ec2", "EC2"), Map.entry("s3", "S3"), Map.entry("sqs", "SQS"), Map.entry("sns", "SNS"),
            Map.entry("iam", "IAM"), Map.entry("kms", "KMS"), Map.entry("ecs", "ECS"), Map.entry("ecr", "ECR"),
            Map.entry("eks", "EKS"), Map.entry("rds", "RDS"), Map.entry("ssm", "SSM"), Map.entry("acm", "CertificateManager"),
            Map.entry("dynamodb", "DynamoDB"), Map.entry("lambda", "Lambda"), Map.entry("logs", "Logs"),
            Map.entry("events", "Events"), Map.entry("states", "StepFunctions"), Map.entry("kinesis", "Kinesis"),
            Map.entry("firehose", "KinesisFirehose"), Map.entry("secretsmanager", "SecretsManager"),
            Map.entry("cloudformation", "CloudFormation"), Map.entry("cloudwatch", "CloudWatch"),
            Map.entry("apigateway", "ApiGateway"), Map.entry("elasticloadbalancing", "ElasticLoadBalancingV2"),
            Map.entry("resource-groups", "ResourceGroups"), Map.entry("elasticache", "ElastiCache"),
            Map.entry("es", "OpenSearchService"), Map.entry("cognito-idp", "Cognito"),
            Map.entry("codebuild", "CodeBuild"), Map.entry("codepipeline", "CodePipeline"),
            Map.entry("glue", "Glue"), Map.entry("athena", "Athena"), Map.entry("redshift", "Redshift"),
            Map.entry("redshift-serverless", "RedshiftServerless"), Map.entry("sagemaker", "SageMaker"),
            Map.entry("scheduler", "Scheduler"), Map.entry("route53", "Route53"), Map.entry("wafv2", "WAFv2"),
            Map.entry("appsync", "AppSync"), Map.entry("cloudfront", "CloudFront"), Map.entry("backup", "Backup"),
            Map.entry("elasticfilesystem", "EFS"), Map.entry("autoscaling", "AutoScaling"));

    /** ARN resource types whose CloudFormation type is not the Pascal-cased ARN type. */
    private static final Map<String, String> TYPE_OVERRIDES = Map.ofEntries(
            Map.entry("logs:log-group", "AWS::Logs::LogGroup"), Map.entry("states:stateMachine", "AWS::StepFunctions::StateMachine"),
            Map.entry("dynamodb:table", "AWS::DynamoDB::Table"), Map.entry("kinesis:stream", "AWS::Kinesis::Stream"),
            Map.entry("firehose:deliverystream", "AWS::KinesisFirehose::DeliveryStream"),
            Map.entry("rds:db", "AWS::RDS::DBInstance"), Map.entry("rds:cluster", "AWS::RDS::DBCluster"),
            Map.entry("elasticloadbalancing:loadbalancer", "AWS::ElasticLoadBalancingV2::LoadBalancer"),
            Map.entry("elasticloadbalancing:targetgroup", "AWS::ElasticLoadBalancingV2::TargetGroup"),
            Map.entry("cloudwatch:alarm", "AWS::CloudWatch::Alarm"), Map.entry("events:rule", "AWS::Events::Rule"),
            Map.entry("events:event-bus", "AWS::Events::EventBus"), Map.entry("ssm:parameter", "AWS::SSM::Parameter"),
            Map.entry("secretsmanager:secret", "AWS::SecretsManager::Secret"), Map.entry("kms:key", "AWS::KMS::Key"),
            Map.entry("ecr:repository", "AWS::ECR::Repository"), Map.entry("ecs:cluster", "AWS::ECS::Cluster"),
            Map.entry("ecs:service", "AWS::ECS::Service"), Map.entry("ecs:task-definition", "AWS::ECS::TaskDefinition"),
            Map.entry("cloudformation:stack", "AWS::CloudFormation::Stack"),
            Map.entry("resource-groups:group", "AWS::ResourceGroups::Group"),
            Map.entry("ec2:security-group", "AWS::EC2::SecurityGroup"), Map.entry("ec2:vpc", "AWS::EC2::VPC"),
            Map.entry("ec2:natgateway", "AWS::EC2::NatGateway"), Map.entry("ec2:elastic-ip", "AWS::EC2::EIP"),
            Map.entry("ec2:image", "AWS::EC2::Image"), Map.entry("apigateway:restapis", "AWS::ApiGateway::RestApi"),
            Map.entry("sqs:queue", "AWS::SQS::Queue"), Map.entry("sns:topic", "AWS::SNS::Topic"),
            Map.entry("s3:bucket", "AWS::S3::Bucket"), Map.entry("acm:certificate", "AWS::CertificateManager::Certificate"));

    private final Instance<ResourceGroupsTaggingService> tagging;
    private final Instance<CloudFormationService> cloudFormation;
    private final ObjectMapper mapper;

    @Inject
    public ResourceGroupsQueries(Instance<ResourceGroupsTaggingService> tagging,
                                 Instance<CloudFormationService> cloudFormation, ObjectMapper mapper) {
        this.tagging = tagging;
        this.cloudFormation = cloudFormation;
        this.mapper = mapper;
    }

    /** A selected resource and its CloudFormation type. */
    public record Resource(String arn, String type) {}

    /** What a query selected, and why it selected less than it could ({@code QueryErrors}). */
    public record Result(List<Resource> resources, List<Map<String, String>> errors) {}

    /** Checks a query's shape the way {@code CreateGroup} and {@code SearchResources} do. */
    public void validate(ResourceQuery query) {
        parse(query);
    }

    public Result resolve(ResourceQuery query, String region, String account) {
        Parsed parsed = parse(query);
        List<Map<String, String>> errors = new ArrayList<>();
        Map<String, Resource> found = new TreeMap<>();
        if (TAG_QUERY.equals(query.type())) {
            String token = null;
            do {
                ResourceGroupsTaggingService.PageResult page = tagging.get().getResources(null, parsed.tagFilters(),
                        null, token, 100, region);
                for (ResourceTagMapping mapping : page.items()) {
                    add(found, mapping.getResourceArn(), null, parsed.types(), account);
                }
                token = page.nextPaginationToken();
            } while (token != null);
        } else {
            try {
                for (StackResource resource : cloudFormation.get().describeStackResources(parsed.stack(), region)) {
                    String arn = resource.getAttributes().get("Arn");
                    if (arn == null && AwsArnUtils.isArn(resource.getPhysicalId())) {
                        arn = resource.getPhysicalId();
                    }
                    if (arn != null) {
                        add(found, arn, resource.getResourceType(), parsed.types(), account);
                    }
                }
            } catch (AwsException e) {
                errors.add(Map.of("ErrorCode", "CLOUDFORMATION_STACK_NOT_EXISTING",
                        "Message", "The stack " + parsed.stack() + " does not exist."));
            }
        }
        return new Result(new ArrayList<>(found.values()), errors);
    }

    private static void add(Map<String, Resource> found, String arn, String declaredType, Set<String> types,
                            String account) {
        if (!AwsArnUtils.isArn(arn)) {
            return;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!parsed.accountId().isEmpty() && !parsed.accountId().equals(account)) {
            return;
        }
        String type = declaredType != null ? declaredType : cloudFormationType(arn);
        if (types.contains(ALL_SUPPORTED) || types.contains(type)) {
            found.put(arn, new Resource(arn, type));
        }
    }

    /** The CloudFormation type of the resource {@code arn} names, as Resource Groups reports it. */
    public static String cloudFormationType(String arn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String service = parsed.service();
        String resource = parsed.resource().startsWith("/") ? parsed.resource().substring(1) : parsed.resource();
        String arnType = "s3".equals(service) && !resource.contains("/") ? "bucket"
                : "sqs".equals(service) ? "queue" : "sns".equals(service) ? "topic"
                : resource.split("[/:]", 2)[0];
        String override = TYPE_OVERRIDES.get(service + ":" + arnType);
        if (override != null) {
            return override;
        }
        String namespace = SERVICE_NAMESPACES.getOrDefault(service, pascal(service));
        return "AWS::" + namespace + "::" + pascal(arnType);
    }

    private static String pascal(String value) {
        StringBuilder out = new StringBuilder();
        for (String part : value.split("[-_]")) {
            if (!part.isEmpty()) {
                out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return out.toString();
    }

    private record Parsed(Set<String> types, List<ResourceGroupsTaggingService.TagFilter> tagFilters, String stack) {}

    private Parsed parse(ResourceQuery query) {
        if (query == null || query.type() == null || query.query() == null) {
            throw bad("ResourceQuery needs a Type and a Query.");
        }
        if (!TAG_QUERY.equals(query.type()) && !STACK_QUERY.equals(query.type())) {
            throw bad("ResourceQuery Type must be TAG_FILTERS_1_0 or CLOUDFORMATION_STACK_1_0.");
        }
        JsonNode node;
        try {
            node = mapper.readTree(query.query());
        } catch (Exception e) {
            throw bad("Query is not valid JSON.");
        }
        if (node == null || !node.isObject()) {
            throw bad("Query must be a JSON object.");
        }
        Set<String> types = new LinkedHashSet<>();
        JsonNode typeFilters = node.path("ResourceTypeFilters");
        if (typeFilters.isMissingNode()) {
            types.add(ALL_SUPPORTED);
        } else if (!typeFilters.isArray() || typeFilters.isEmpty()) {
            throw bad("ResourceTypeFilters must list at least one resource type.");
        } else {
            for (JsonNode type : typeFilters) {
                if (!type.isTextual() || !(type.asText().equals(ALL_SUPPORTED) || type.asText().matches("AWS::[A-Za-z0-9]+::[A-Za-z0-9]+"))) {
                    throw bad("Invalid resource type " + type + " in ResourceTypeFilters.");
                }
                types.add(type.asText());
            }
            if (types.contains(ALL_SUPPORTED) && types.size() > 1) {
                throw bad("AWS::AllSupported cannot be combined with other resource types.");
            }
        }
        if (TAG_QUERY.equals(query.type())) {
            JsonNode tagFilters = node.path("TagFilters");
            if (!tagFilters.isArray() || tagFilters.isEmpty() || tagFilters.size() > 50) {
                throw bad("A tag query needs one to fifty TagFilters.");
            }
            List<ResourceGroupsTaggingService.TagFilter> filters = new ArrayList<>();
            for (JsonNode filter : tagFilters) {
                JsonNode key = filter.path("Key");
                if (!key.isTextual() || key.asText().isEmpty() || key.asText().length() > 128) {
                    throw bad("Every tag filter needs a Key.");
                }
                List<String> values = new ArrayList<>();
                JsonNode valueNodes = filter.path("Values");
                if (!valueNodes.isMissingNode()) {
                    if (!valueNodes.isArray() || valueNodes.size() > 20) {
                        throw bad("Tag filter Values must be a list of at most twenty values.");
                    }
                    valueNodes.forEach(value -> values.add(value.asText()));
                }
                filters.add(new ResourceGroupsTaggingService.TagFilter(key.asText(), values));
            }
            return new Parsed(types, filters, null);
        }
        JsonNode stack = node.path("StackIdentifier");
        if (!stack.isTextual() || !AwsArnUtils.isArnFor(stack.asText(), "cloudformation")) {
            throw bad("A CloudFormation stack query needs the stack ARN as StackIdentifier.");
        }
        return new Parsed(types, List.of(), stack.asText());
    }

    /** A query result page in the {@code ResourceIdentifiers} / {@code Resources} shapes. */
    static Map<String, Object> identifier(Resource resource) {
        Map<String, Object> identifier = new LinkedHashMap<>();
        identifier.put("ResourceArn", resource.arn());
        identifier.put("ResourceType", resource.type());
        return identifier;
    }

    private static AwsException bad(String message) {
        return new AwsException("BadRequestException", message, 400);
    }
}
