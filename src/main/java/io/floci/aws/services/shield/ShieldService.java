package io.floci.aws.services.shield;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudfront.CloudFrontService;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.elb.ElbClassicService;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.globalaccelerator.GlobalAcceleratorService;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamPolicy;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.route53.Route53Service;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.wafv2.WafV2Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AWS Shield Advanced: the subscription, protections on resources that exist in the services that own them,
 * protection groups whose members follow from their pattern, health checks, application-layer automatic response
 * (which needs a WAF web ACL on the resource), Shield Response Team access and emergency contacts. Without a
 * subscription the protection APIs answer {@code The subscription does not exist.}, as AWS does. No attacks are
 * ever detected, so attack listings are empty.
 */
@ApplicationScoped
public class ShieldService implements Resettable {

    private static final String NO_SUBSCRIPTION = "The subscription does not exist.";
    private static final long YEAR = 365L * 24 * 3600;
    /** The partition of the request being handled: an account, and so its subscription, lives in one. */
    private static final ThreadLocal<String> PARTITION = new ThreadLocal<>();

    private final AccountAwareStorageBackend<ObjectNode> state;
    private final AccountAwareStorageBackend<ObjectNode> protections;
    private final AccountAwareStorageBackend<ObjectNode> groups;
    private final ObjectMapper mapper;
    private final Provider<Ec2Service> ec2;
    private final Provider<ElbV2Service> elbv2;
    private final Provider<ElbClassicService> elb;
    private final Provider<CloudFrontService> cloudFront;
    private final Provider<Route53Service> route53;
    private final Provider<GlobalAcceleratorService> globalAccelerator;
    private final Provider<WafV2Service> waf;
    private final Provider<IamService> iam;
    private final Provider<S3Service> s3;

    @Inject
    public ShieldService(StorageFactory storageFactory, ObjectMapper mapper, Provider<Ec2Service> ec2,
                         Provider<ElbV2Service> elbv2, Provider<ElbClassicService> elb,
                         Provider<CloudFrontService> cloudFront, Provider<Route53Service> route53,
                         Provider<GlobalAcceleratorService> globalAccelerator, Provider<WafV2Service> waf,
                         Provider<IamService> iam, Provider<S3Service> s3) {
        this.state = storageFactory.create("shield", "state.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.protections = storageFactory.create("shield", "protections.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.groups = storageFactory.create("shield", "protection-groups.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.ec2 = ec2;
        this.elbv2 = elbv2;
        this.elb = elb;
        this.cloudFront = cloudFront;
        this.route53 = route53;
        this.globalAccelerator = globalAccelerator;
        this.waf = waf;
        this.iam = iam;
        this.s3 = s3;
    }

    @Override
    public void clear() {
        state.clear();
        protections.clear();
        groups.clear();
    }

    public ObjectNode handle(String action, JsonNode in, String region) {
        PARTITION.set(AwsRegions.partitionFor(region));
        try {
            return dispatch(action, in, region);
        } finally {
            PARTITION.remove();
        }
    }

    private static String key(String name) {
        return name + "/" + PARTITION.get();
    }

    private ObjectNode dispatch(String action, JsonNode in, String region) {
        return switch (action) {
            case "GetSubscriptionState" -> mapper.createObjectNode().put("SubscriptionState",
                    subscription().isPresent() ? "ACTIVE" : "INACTIVE");
            case "CreateSubscription" -> createSubscription(region);
            case "DescribeSubscription" -> wrap("Subscription", subscriptionView(requireSubscription()));
            case "UpdateSubscription" -> updateSubscription(in);
            case "DeleteSubscription" -> deleteSubscription();
            case "CreateProtection" -> createProtection(in, region);
            case "DescribeProtection" -> wrap("Protection", view(protection(in)));
            case "DeleteProtection" -> deleteProtection(in);
            case "ListProtections" -> listProtections(in);
            case "AssociateHealthCheck" -> healthCheck(in, true);
            case "DisassociateHealthCheck" -> healthCheck(in, false);
            case "EnableApplicationLayerAutomaticResponse" -> automaticResponse(in, "enable");
            case "UpdateApplicationLayerAutomaticResponse" -> automaticResponse(in, "update");
            case "DisableApplicationLayerAutomaticResponse" -> automaticResponse(in, "disable");
            case "CreateProtectionGroup" -> createGroup(in);
            case "DescribeProtectionGroup" -> wrap("ProtectionGroup", groupView(group(in, true)));
            case "UpdateProtectionGroup" -> updateGroup(in);
            case "DeleteProtectionGroup" -> deleteGroup(in);
            case "ListProtectionGroups" -> listGroups(in);
            case "ListResourcesInProtectionGroup" -> listGroupResources(in);
            case "AssociateDRTRole" -> associateRole(in);
            case "DisassociateDRTRole" -> disassociateRole();
            case "AssociateDRTLogBucket" -> logBucket(in, true);
            case "DisassociateDRTLogBucket" -> logBucket(in, false);
            case "DescribeDRTAccess" -> describeDrtAccess();
            case "UpdateEmergencyContactSettings" -> updateContacts(in);
            case "DescribeEmergencyContactSettings" -> describeContacts();
            case "AssociateProactiveEngagementDetails" -> associateProactive(in);
            case "EnableProactiveEngagement" -> proactive(true);
            case "DisableProactiveEngagement" -> proactive(false);
            case "ListAttacks" -> mapper.createObjectNode().set("AttackSummaries", mapper.createArrayNode());
            case "DescribeAttack" -> mapper.createObjectNode();
            case "DescribeAttackStatistics" -> attackStatistics();
            case "TagResource" -> tag(in, true);
            case "UntagResource" -> tag(in, false);
            case "ListTagsForResource" -> listTags(in);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + action + ".", 400);
        };
    }

    // ---------- subscription ----------

    private Optional<ObjectNode> subscription() {
        return state.get(key("subscription")).map(ObjectNode::deepCopy);
    }

    private ObjectNode requireSubscription() {
        return subscription().orElseThrow(() -> notFound(NO_SUBSCRIPTION));
    }

    private ObjectNode createSubscription(String region) {
        if (subscription().isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "The subscription already exists.", 400);
        }
        long now = Instant.now().getEpochSecond();
        ObjectNode subscription = mapper.createObjectNode().put("StartTime", now).put("EndTime", now + YEAR)
                .put("TimeCommitmentInSeconds", YEAR).put("AutoRenew", "ENABLED")
                .put("ProactiveEngagementStatus", "DISABLED")
                .put("SubscriptionArn", AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "shield",
                        state.accountId(), "subscription/" + UUID.randomUUID().toString().replace("-", "")
                                .substring(0, 16)).toString());
        state.put(key("subscription"), subscription);
        return mapper.createObjectNode();
    }

    private ObjectNode subscriptionView(ObjectNode subscription) {
        ObjectNode view = subscription.deepCopy();
        ArrayNode limits = view.putArray("Limits");
        limits.addObject().put("Type", "MitigationCapacityUnits").put("Max", 10000);
        ObjectNode subscriptionLimits = view.putObject("SubscriptionLimits");
        ArrayNode typeLimits = subscriptionLimits.putObject("ProtectionLimits").putArray("ProtectedResourceTypeLimits");
        for (String type : List.of("ELASTIC_IP_ALLOCATION", "APPLICATION_LOAD_BALANCER", "CLASSIC_LOAD_BALANCER",
                "CLOUDFRONT_DISTRIBUTION", "ROUTE_53_HOSTED_ZONE", "GLOBAL_ACCELERATOR")) {
            typeLimits.addObject().put("Type", type).put("Max", 1000);
        }
        subscriptionLimits.putObject("ProtectionGroupLimits").put("MaxProtectionGroups", 100)
                .putObject("PatternTypeLimits").putObject("ArbitraryPatternLimits").put("MaxMembers", 10000);
        return view;
    }

    private ObjectNode updateSubscription(JsonNode in) {
        ObjectNode subscription = requireSubscription();
        if (in.hasNonNull("AutoRenew")) {
            subscription.put("AutoRenew", in.get("AutoRenew").asText());
        }
        state.put(key("subscription"), subscription);
        return mapper.createObjectNode();
    }

    /** A subscription can't be ended inside its one-year commitment. */
    private ObjectNode deleteSubscription() {
        ObjectNode subscription = requireSubscription();
        if (Instant.now().getEpochSecond() < subscription.path("EndTime").asLong()) {
            throw new AwsException("LockedSubscriptionException", "You can't delete the subscription before its "
                    + "commitment ends on " + Instant.ofEpochSecond(subscription.path("EndTime").asLong()) + ".",
                    400);
        }
        state.delete(key("subscription"));
        return mapper.createObjectNode();
    }

    // ---------- protections ----------

    /** The type of resource the ARN names, after checking that it exists in the service that owns it. */
    private String resourceType(String arn) {
        if (!AwsArnUtils.isArn(arn)) {
            throw invalidResource("The resource " + arn + " isn't a valid ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        try {
            switch (parsed.service()) {
                case "ec2" -> {
                    if (resource.startsWith("eip-allocation/") && !ec2.get().describeAddresses(parsed.region(),
                            List.of(resource.substring("eip-allocation/".length())), Map.of()).isEmpty()) {
                        return "ELASTIC_IP_ALLOCATION";
                    }
                }
                case "elasticloadbalancing" -> {
                    if (resource.startsWith("loadbalancer/app/") && !elbv2.get().describeLoadBalancers(
                            parsed.region(), List.of(arn), null, null, null).isEmpty()) {
                        return "APPLICATION_LOAD_BALANCER";
                    }
                    if (resource.startsWith("loadbalancer/") && resource.split("/").length == 2
                            && !elb.get().describeLoadBalancers(parsed.region(),
                            List.of(resource.substring("loadbalancer/".length()))).isEmpty()) {
                        return "CLASSIC_LOAD_BALANCER";
                    }
                }
                case "cloudfront" -> {
                    if (resource.startsWith("distribution/")
                            && cloudFront.get().getDistribution(resource.substring("distribution/".length())) != null) {
                        return "CLOUDFRONT_DISTRIBUTION";
                    }
                }
                case "route53" -> {
                    if (resource.startsWith("hostedzone/")
                            && route53.get().getHostedZone(resource.substring("hostedzone/".length())) != null) {
                        return "ROUTE_53_HOSTED_ZONE";
                    }
                }
                case "globalaccelerator" -> {
                    if (resource.startsWith("accelerator/") && globalAccelerator.get().describeAccelerator(arn) != null) {
                        return "GLOBAL_ACCELERATOR";
                    }
                }
                default -> throw invalidResource("Shield can't protect " + arn + ". It protects Elastic IPs, "
                        + "Application and Classic Load Balancers, CloudFront distributions, Route 53 hosted zones "
                        + "and Global Accelerator accelerators.");
            }
        } catch (AwsException e) {
            if ("InvalidResourceException".equals(e.getErrorCode())) {
                throw e;
            }
        }
        throw invalidResource("Unable to find the resource " + arn + ".");
    }

    private ObjectNode createProtection(JsonNode in, String region) {
        requireSubscription();
        String arn = in.path("ResourceArn").asText();
        String type = resourceType(arn);
        for (ObjectNode existing : protections.scan(k -> true)) {
            if (arn.equals(existing.path("ResourceArn").asText())) {
                throw new AwsException("ResourceAlreadyExistsException", "The resource " + arn + " is already "
                        + "protected.", 400);
            }
        }
        String id = UUID.randomUUID().toString();
        ObjectNode protection = mapper.createObjectNode();
        protection.put("Id", id).put("Name", in.path("Name").asText()).put("ResourceArn", arn)
                .put("ProtectionArn", AwsArnUtils.Arn.global(AwsArnUtils.parse(arn).partition(), "shield",
                        protections.accountId(), "protection/" + id).toString());
        protection.putArray("HealthCheckIds");
        ObjectNode meta = protection.putObject("_").put("type", type);
        meta.set("tags", tags(in.path("Tags")));
        protections.put(id, protection);
        return mapper.createObjectNode().put("ProtectionId", id);
    }

    private ObjectNode protection(JsonNode in) {
        requireSubscription();
        if (in.hasNonNull("ProtectionId")) {
            String id = in.get("ProtectionId").asText();
            return protections.get(id).map(ObjectNode::deepCopy)
                    .orElseThrow(() -> notFound("The referenced protection " + id + " does not exist."));
        }
        String arn = in.path("ResourceArn").asText();
        return protections.scan(k -> true).stream().filter(p -> arn.equals(p.path("ResourceArn").asText()))
                .findFirst().map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("The resource " + arn + " is not protected."));
    }

    private ObjectNode deleteProtection(JsonNode in) {
        ObjectNode protection = protection(in);
        String arn = protection.path("ResourceArn").asText();
        for (ObjectNode group : groups.scan(k -> true)) {
            ArrayNode members = (ArrayNode) group.get("Members");
            for (int i = members.size() - 1; i >= 0; i--) {
                if (arn.equals(members.get(i).asText())) {
                    members.remove(i);
                    groups.put(group.path("ProtectionGroupId").asText(), group);
                }
            }
        }
        protections.delete(protection.path("Id").asText());
        return mapper.createObjectNode();
    }

    private ObjectNode listProtections(JsonNode in) {
        requireSubscription();
        JsonNode filters = in.path("InclusionFilters");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode protection : protections.scan(k -> true)) {
            if (matches(filters.path("ResourceArns"), protection.path("ResourceArn").asText())
                    && matches(filters.path("ProtectionNames"), protection.path("Name").asText())
                    && matches(filters.path("ResourceTypes"), protection.path("_").path("type").asText())) {
                items.add(view(protection));
            }
        }
        items.sort(Comparator.comparing(item -> item.path("Name").asText()));
        return page(items, in, "Protections");
    }

    private static boolean matches(JsonNode filter, String value) {
        if (!filter.isArray() || filter.isEmpty()) {
            return true;
        }
        for (JsonNode candidate : filter) {
            if (candidate.asText().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode healthCheck(JsonNode in, boolean associate) {
        ObjectNode protection = protection(in);
        String arn = in.path("HealthCheckArn").asText();
        if (!AwsArnUtils.isArnFor(arn, "route53") || !AwsArnUtils.parse(arn).resource().startsWith("healthcheck/")) {
            throw new AwsException("InvalidParameterException", "HealthCheckArn must be a Route 53 health check ARN.",
                    400);
        }
        String id = AwsArnUtils.parse(arn).resource().substring("healthcheck/".length());
        ArrayNode ids = (ArrayNode) protection.get("HealthCheckIds");
        if (associate) {
            try {
                route53.get().getHealthCheck(id);
            } catch (AwsException e) {
                throw new AwsException("InvalidResourceException", "The health check " + id + " does not exist.",
                        400);
            }
            if (!ids.isEmpty()) {
                throw new AwsException("LimitsExceededException", "A protection can have one health check.", 400);
            }
            ids.add(id);
        } else {
            for (int i = ids.size() - 1; i >= 0; i--) {
                if (id.equals(ids.get(i).asText())) {
                    ids.remove(i);
                }
            }
        }
        protections.put(protection.path("Id").asText(), protection);
        return mapper.createObjectNode();
    }

    /** Automatic response works for CloudFront distributions and ALBs with a WAF web ACL in front of them. */
    private ObjectNode automaticResponse(JsonNode in, String mode) {
        ObjectNode protection = protection(in);
        String type = protection.path("_").path("type").asText();
        JsonNode current = protection.path("ApplicationLayerAutomaticResponseConfiguration");
        boolean enabled = "ENABLED".equals(current.path("Status").asText());
        switch (mode) {
            case "enable" -> {
                if (!"CLOUDFRONT_DISTRIBUTION".equals(type) && !"APPLICATION_LOAD_BALANCER".equals(type)) {
                    throw new AwsException("InvalidParameterException", "Automatic application layer DDoS "
                            + "mitigation is available only for CloudFront distributions and Application Load "
                            + "Balancers.", 400);
                }
                if (enabled) {
                    throw new AwsException("InvalidOperationException", "Automatic response is already enabled. "
                            + "Use UpdateApplicationLayerAutomaticResponse to change its action.", 400);
                }
                if (waf.get().getWebAclForResource(protection.path("ResourceArn").asText()) == null) {
                    throw new AwsException("InvalidOperationException", "The resource has no AWS WAF web ACL. "
                            + "Associate one before enabling automatic application layer DDoS mitigation.", 400);
                }
                setResponse(protection, "ENABLED", in.path("Action"));
            }
            case "update" -> {
                if (!enabled) {
                    throw new AwsException("InvalidOperationException", "Automatic response isn't enabled.", 400);
                }
                setResponse(protection, "ENABLED", in.path("Action"));
            }
            default -> {
                if (!enabled) {
                    throw new AwsException("InvalidOperationException", "Automatic response isn't enabled.", 400);
                }
                setResponse(protection, "DISABLED", current.path("Action"));
            }
        }
        protections.put(protection.path("Id").asText(), protection);
        return mapper.createObjectNode();
    }

    private void setResponse(ObjectNode protection, String status, JsonNode action) {
        if (!action.has("Block") && !action.has("Count")) {
            throw new AwsException("InvalidParameterException", "Action must be Block or Count.", 400);
        }
        ObjectNode configuration = protection.putObject("ApplicationLayerAutomaticResponseConfiguration")
                .put("Status", status);
        configuration.set("Action", action.deepCopy());
    }

    // ---------- protection groups ----------

    private ObjectNode createGroup(JsonNode in) {
        requireSubscription();
        String id = in.path("ProtectionGroupId").asText();
        if (groups.get(id).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "The protection group " + id
                    + " already exists.", 400);
        }
        if (groups.scan(k -> true).size() >= 100) {
            throw new AwsException("LimitsExceededException", "An account has at most 100 protection groups.", 400);
        }
        ObjectNode group = mapper.createObjectNode();
        group.put("ProtectionGroupId", id).put("ProtectionGroupArn", AwsArnUtils.Arn.global(
                partition(), "shield", groups.accountId(), "protection-group/" + id).toString());
        applyGroup(group, in);
        group.putObject("_").set("tags", tags(in.path("Tags")));
        groups.put(id, group);
        return mapper.createObjectNode();
    }

    private String partition() {
        ObjectNode subscription = requireSubscription();
        return AwsArnUtils.parse(subscription.path("SubscriptionArn").asText()).partition();
    }

    /** A group's pattern decides what else it takes: nothing, a resource type, or protected members. */
    private void applyGroup(ObjectNode group, JsonNode in) {
        String pattern = in.path("Pattern").asText();
        boolean hasType = in.hasNonNull("ResourceType");
        boolean hasMembers = in.path("Members").isArray() && !in.get("Members").isEmpty();
        switch (pattern) {
            case "ALL" -> {
                if (hasType || hasMembers) {
                    throw invalidParameter("A group with pattern ALL takes no ResourceType or Members.");
                }
            }
            case "BY_RESOURCE_TYPE" -> {
                if (!hasType || hasMembers) {
                    throw invalidParameter("A group with pattern BY_RESOURCE_TYPE takes a ResourceType and no "
                            + "Members.");
                }
            }
            default -> {
                if (hasType || !hasMembers) {
                    throw invalidParameter("A group with pattern ARBITRARY takes Members and no ResourceType.");
                }
                for (JsonNode member : in.get("Members")) {
                    boolean protectedMember = protections.scan(k -> true).stream()
                            .anyMatch(p -> member.asText().equals(p.path("ResourceArn").asText()));
                    if (!protectedMember) {
                        throw invalidParameter("The member " + member.asText() + " isn't a protected resource.");
                    }
                }
            }
        }
        group.put("Aggregation", in.path("Aggregation").asText()).put("Pattern", pattern);
        if (hasType) {
            group.put("ResourceType", in.get("ResourceType").asText());
        } else {
            group.remove("ResourceType");
        }
        group.set("Members", hasMembers ? in.get("Members").deepCopy() : mapper.createArrayNode());
    }

    /** A missing group is reported before a missing subscription, as AWS does for reads. */
    private ObjectNode group(JsonNode in, boolean groupFirst) {
        String id = in.path("ProtectionGroupId").asText();
        if (!groupFirst) {
            requireSubscription();
        }
        ObjectNode group = groups.get(id).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("The referenced protection group " + id + " does not exist."));
        requireSubscription();
        return group;
    }

    /** The resources a group covers: every protected one, those of its type, or its members. */
    private List<String> resourcesOf(ObjectNode group) {
        List<String> out = new ArrayList<>();
        switch (group.path("Pattern").asText()) {
            case "ALL" -> protections.scan(k -> true).forEach(p -> out.add(p.path("ResourceArn").asText()));
            case "BY_RESOURCE_TYPE" -> protections.scan(k -> true).stream()
                    .filter(p -> group.path("ResourceType").asText().equals(p.path("_").path("type").asText()))
                    .forEach(p -> out.add(p.path("ResourceArn").asText()));
            default -> group.path("Members").forEach(member -> out.add(member.asText()));
        }
        out.sort(String::compareTo);
        return out;
    }

    private ObjectNode groupView(ObjectNode group) {
        ObjectNode view = group.deepCopy();
        view.remove("_");
        if (!"ARBITRARY".equals(group.path("Pattern").asText())) {
            view.set("Members", mapper.valueToTree(resourcesOf(group)));
        }
        return view;
    }

    private ObjectNode updateGroup(JsonNode in) {
        ObjectNode group = group(in, false);
        applyGroup(group, in);
        groups.put(group.path("ProtectionGroupId").asText(), group);
        return mapper.createObjectNode();
    }

    private ObjectNode deleteGroup(JsonNode in) {
        ObjectNode group = group(in, false);
        groups.delete(group.path("ProtectionGroupId").asText());
        return mapper.createObjectNode();
    }

    private ObjectNode listGroups(JsonNode in) {
        requireSubscription();
        JsonNode filters = in.path("InclusionFilters");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode group : groups.scan(k -> true)) {
            if (matches(filters.path("ProtectionGroupIds"), group.path("ProtectionGroupId").asText())
                    && matches(filters.path("Patterns"), group.path("Pattern").asText())
                    && matches(filters.path("ResourceTypes"), group.path("ResourceType").asText())
                    && matches(filters.path("Aggregations"), group.path("Aggregation").asText())) {
                items.add(groupView(group));
            }
        }
        items.sort(Comparator.comparing(item -> item.path("ProtectionGroupId").asText()));
        return page(items, in, "ProtectionGroups");
    }

    private ObjectNode listGroupResources(JsonNode in) {
        ObjectNode group = group(in, true);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode arns = out.putArray("ResourceArns");
        resourcesOf(group).forEach(arns::add);
        return out;
    }

    // ---------- Shield Response Team access ----------

    /** The SRT role must trust the DRT service and carry AWSShieldDRTAccessPolicy. */
    private ObjectNode associateRole(JsonNode in) {
        requireSubscription();
        String arn = in.path("RoleArn").asText();
        if (!AwsArnUtils.isArnFor(arn, "iam") || !AwsArnUtils.parse(arn).resource().startsWith("role/")) {
            throw invalidParameter("RoleArn must be an IAM role ARN.");
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        IamRole role;
        try {
            role = iam.get().getRole(name);
        } catch (AwsException e) {
            throw new AwsException("AccessDeniedForDependencyException", "The role " + arn + " does not exist.",
                    400);
        }
        String trust = role.getAssumeRolePolicyDocument() == null ? "" : URLDecoder.decode(
                role.getAssumeRolePolicyDocument(), StandardCharsets.UTF_8);
        if (!trust.contains(ServicePrincipals.of("drt.shield"))) {
            throw invalidParameter("The role " + arn + " must trust " + ServicePrincipals.of("drt.shield") + ".");
        }
        boolean hasPolicy = false;
        for (IamPolicy policy : iam.get().listAttachedRolePolicies(name, null)) {
            hasPolicy |= "AWSShieldDRTAccessPolicy".equals(policy.getPolicyName());
        }
        if (!hasPolicy) {
            throw invalidParameter("The role " + arn + " must have the AWSShieldDRTAccessPolicy managed policy "
                    + "attached.");
        }
        ObjectNode access = drtAccess();
        access.put("RoleArn", arn);
        state.put(key("drt"), access);
        return mapper.createObjectNode();
    }

    private ObjectNode drtAccess() {
        return state.get(key("drt")).map(ObjectNode::deepCopy).orElseGet(() -> {
            ObjectNode empty = mapper.createObjectNode();
            empty.putArray("LogBucketList");
            return empty;
        });
    }

    private ObjectNode disassociateRole() {
        requireSubscription();
        ObjectNode access = drtAccess();
        access.remove("RoleArn");
        state.put(key("drt"), access);
        return mapper.createObjectNode();
    }

    private ObjectNode logBucket(JsonNode in, boolean associate) {
        requireSubscription();
        ObjectNode access = drtAccess();
        if (!access.hasNonNull("RoleArn")) {
            throw new AwsException("NoAssociatedRoleException", "Associate a role with the Shield Response Team "
                    + "before granting it log buckets.", 400);
        }
        String bucket = in.path("LogBucket").asText();
        ArrayNode buckets = (ArrayNode) access.get("LogBucketList");
        if (associate) {
            if (!s3.get().bucketExists(bucket)) {
                throw new AwsException("InvalidOperationException", "The bucket " + bucket + " does not exist.",
                        400);
            }
            if (buckets.size() >= 10) {
                throw new AwsException("LimitsExceededException", "The Shield Response Team can have at most 10 "
                        + "log buckets.", 400);
            }
            boolean present = false;
            for (JsonNode existing : buckets) {
                present |= bucket.equals(existing.asText());
            }
            if (!present) {
                buckets.add(bucket);
            }
        } else {
            for (int i = buckets.size() - 1; i >= 0; i--) {
                if (bucket.equals(buckets.get(i).asText())) {
                    buckets.remove(i);
                }
            }
        }
        state.put(key("drt"), access);
        return mapper.createObjectNode();
    }

    private ObjectNode describeDrtAccess() {
        if (subscription().isEmpty()) {
            throw notFound("The account doesn't have a Shield Advanced subscription.");
        }
        return drtAccess();
    }

    // ---------- emergency contacts and proactive engagement ----------

    private ObjectNode updateContacts(JsonNode in) {
        requireSubscription();
        ObjectNode contacts = mapper.createObjectNode();
        contacts.set("EmergencyContactList", in.path("EmergencyContactList").isArray()
                ? in.get("EmergencyContactList").deepCopy() : mapper.createArrayNode());
        state.put(key("contacts"), contacts);
        return mapper.createObjectNode();
    }

    private ObjectNode describeContacts() {
        requireSubscription();
        return state.get(key("contacts")).map(ObjectNode::deepCopy).orElseGet(() -> {
            ObjectNode empty = mapper.createObjectNode();
            empty.putArray("EmergencyContactList");
            return empty;
        });
    }

    private ObjectNode associateProactive(JsonNode in) {
        ObjectNode subscription = requireSubscription();
        if (!"DISABLED".equals(subscription.path("ProactiveEngagementStatus").asText())
                || state.get(key("contacts")).map(c -> !c.path("EmergencyContactList").isEmpty()).orElse(false)) {
            throw new AwsException("InvalidOperationException", "Proactive engagement details are already set. Use "
                    + "UpdateEmergencyContactSettings.", 400);
        }
        updateContacts(in);
        requirePhones();
        subscription.put("ProactiveEngagementStatus", "ENABLED");
        state.put(key("subscription"), subscription);
        return mapper.createObjectNode();
    }

    private void requirePhones() {
        boolean phone = false;
        for (JsonNode contact : describeContacts().path("EmergencyContactList")) {
            phone |= contact.hasNonNull("PhoneNumber");
        }
        if (!phone) {
            throw new AwsException("InvalidParameterException", "Proactive engagement needs an emergency contact "
                    + "with a phone number.", 400);
        }
    }

    private ObjectNode proactive(boolean enable) {
        ObjectNode subscription = requireSubscription();
        if (enable) {
            requirePhones();
        }
        subscription.put("ProactiveEngagementStatus", enable ? "ENABLED" : "DISABLED");
        state.put(key("subscription"), subscription);
        return mapper.createObjectNode();
    }

    // ---------- attacks ----------

    private ObjectNode attackStatistics() {
        long now = Instant.now().getEpochSecond();
        ObjectNode out = mapper.createObjectNode();
        out.putObject("TimeRange").put("FromInclusive", now - YEAR).put("ToExclusive", now);
        out.putArray("DataItems");
        return out;
    }

    // ---------- tags ----------

    private ObjectNode tagged(String arn) {
        requireSubscription();
        if (!AwsArnUtils.isArnFor(arn, "shield")) {
            throw invalidResource("The resource " + arn + " isn't a Shield ARN.");
        }
        String resource = AwsArnUtils.parse(arn).resource();
        if (resource.startsWith("protection/")) {
            return protections.get(resource.substring("protection/".length())).map(ObjectNode::deepCopy)
                    .orElseThrow(() -> notFound("The resource " + arn + " does not exist."));
        }
        if (resource.startsWith("protection-group/")) {
            return groups.get(resource.substring("protection-group/".length())).map(ObjectNode::deepCopy)
                    .orElseThrow(() -> notFound("The resource " + arn + " does not exist."));
        }
        throw notFound("The resource " + arn + " does not exist.");
    }

    private void saveTagged(ObjectNode record) {
        if (record.has("ProtectionGroupId")) {
            groups.put(record.path("ProtectionGroupId").asText(), record);
        } else {
            protections.put(record.path("Id").asText(), record);
        }
    }

    private ObjectNode tag(JsonNode in, boolean add) {
        ObjectNode record = tagged(in.path("ResourceARN").asText());
        ObjectNode tags = record.withObject("/_/tags");
        if (add) {
            tags.setAll(tags(in.path("Tags")));
            if (tags.size() > 200) {
                throw invalidParameter("A resource can have at most 200 tags.");
            }
        } else {
            in.path("TagKeys").forEach(key -> tags.remove(key.asText()));
        }
        saveTagged(record);
        return mapper.createObjectNode();
    }

    private ObjectNode listTags(JsonNode in) {
        ObjectNode record = tagged(in.path("ResourceARN").asText());
        ArrayNode tags = mapper.createArrayNode();
        record.path("_").path("tags").fields().forEachRemaining(entry -> tags.addObject().put("Key", entry.getKey())
                .put("Value", entry.getValue().asText()));
        return mapper.createObjectNode().set("Tags", tags);
    }

    // ---------- shared ----------

    private ObjectNode tags(JsonNode list) {
        ObjectNode map = mapper.createObjectNode();
        for (JsonNode tag : list) {
            map.put(tag.path("Key").asText(), tag.path("Value").asText());
        }
        return map;
    }

    private ObjectNode view(ObjectNode record) {
        ObjectNode view = record.deepCopy();
        view.remove("_");
        return view;
    }

    private ObjectNode wrap(String field, ObjectNode value) {
        ObjectNode out = mapper.createObjectNode();
        out.set(field, value);
        return out;
    }

    private ObjectNode page(List<ObjectNode> items, JsonNode in, String field) {
        int max = in.path("MaxResults").asInt(100);
        int start = 0;
        if (in.hasNonNull("NextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(in.get("NextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new AwsException("InvalidPaginationTokenException", "The NextToken is not valid.", 400);
            }
        }
        start = Math.max(0, Math.min(start, items.size()));
        int end = Math.min(items.size(), start + Math.max(1, max));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode array = out.putArray(field);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    private static AwsException invalidResource(String message) {
        return new AwsException("InvalidResourceException", message, 400);
    }

    private static AwsException invalidParameter(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
