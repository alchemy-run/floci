package io.floci.aws.services.vpclattice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.firehose.FirehoseService;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.route53.Route53Service;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Amazon VPC Lattice control plane: service networks and their VPC, service and resource associations, services
 * with listeners and rules, target groups and targets, auth and resource policies, access log subscriptions,
 * resource gateways and configurations, domain verifications and tags.
 *
 * <p>Resources reference what they need and are checked against it: VPCs, subnets, security groups and instances
 * in EC2, Lambda functions and Application Load Balancers as targets, and S3 buckets, CloudWatch Logs groups and
 * Firehose streams as access log destinations. A VPC joins one service network. Services, target groups, gateways and
 * associations are created and deleted asynchronously, and reject changes until they settle. A resource still in use
 * (a network with associations, a target group with targets or referenced by a rule, a gateway with configurations)
 * can't be deleted. Target health follows the target group's use and health check settings ({@link
 * VpcLatticeHealthChecker}).
 */
@ApplicationScoped
public class VpcLatticeService implements Resettable {

    private static final Logger LOG = Logger.getLogger(VpcLatticeService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALNUM = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final String HEX = "0123456789abcdef";
    static final String NETWORK = "servicenetwork";
    static final String SERVICE = "service";
    static final String VPC_ASSOCIATION = "servicenetworkvpcassociation";
    static final String SERVICE_ASSOCIATION = "servicenetworkserviceassociation";
    static final String RESOURCE_ASSOCIATION = "servicenetworkresourceassociation";
    static final String TARGET_GROUP = "targetgroup";
    static final String LISTENER = "listener";
    static final String RULE = "rule";
    static final String ACCESS_LOG = "accesslogsubscription";
    static final String GATEWAY = "resourcegateway";
    static final String CONFIGURATION = "resourceconfiguration";
    static final String DOMAIN = "domainverification";
    private static final Map<String, String> PREFIXES = Map.ofEntries(Map.entry(NETWORK, "sn-"),
            Map.entry(SERVICE, "svc-"), Map.entry(VPC_ASSOCIATION, "snva-"), Map.entry(SERVICE_ASSOCIATION, "snsa-"),
            Map.entry(RESOURCE_ASSOCIATION, "snra-"), Map.entry(TARGET_GROUP, "tg-"),
            Map.entry(LISTENER, "listener-"), Map.entry(RULE, "rule-"), Map.entry(ACCESS_LOG, "als-"),
            Map.entry(GATEWAY, "rgw-"), Map.entry(CONFIGURATION, "rcfg-"), Map.entry(DOMAIN, "dv-"));
    private static final Map<String, String> TYPE_NAMES = Map.ofEntries(Map.entry(NETWORK, "SERVICE_NETWORK"),
            Map.entry(SERVICE, "SERVICE"), Map.entry(VPC_ASSOCIATION, "SERVICE_NETWORK_VPC_ASSOCIATION"),
            Map.entry(SERVICE_ASSOCIATION, "SERVICE_NETWORK_SERVICE_ASSOCIATION"),
            Map.entry(RESOURCE_ASSOCIATION, "SERVICE_NETWORK_RESOURCE_ASSOCIATION"),
            Map.entry(TARGET_GROUP, "TARGET_GROUP"), Map.entry(LISTENER, "LISTENER"), Map.entry(RULE, "RULE"),
            Map.entry(ACCESS_LOG, "ACCESS_LOG_SUBSCRIPTION"), Map.entry(GATEWAY, "RESOURCE_GATEWAY"),
            Map.entry(CONFIGURATION, "RESOURCE_CONFIGURATION"), Map.entry(DOMAIN, "DOMAIN_VERIFICATION"));

    private final AccountAwareStorageBackend<ObjectNode> resources;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final Provider<Ec2Service> ec2;
    private final Provider<LambdaService> lambda;
    private final Provider<ElbV2Service> elbv2;
    private final Provider<S3Service> s3;
    private final Provider<CloudWatchLogsService> logs;
    private final Provider<FirehoseService> firehose;
    private final Provider<Route53Service> route53;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vpc-lattice");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public VpcLatticeService(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config,
                             Provider<Ec2Service> ec2, Provider<LambdaService> lambda, Provider<ElbV2Service> elbv2,
                             Provider<S3Service> s3, Provider<CloudWatchLogsService> logs,
                             Provider<FirehoseService> firehose, Provider<Route53Service> route53) {
        this.resources = storageFactory.create("vpc-lattice", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
        this.ec2 = ec2;
        this.lambda = lambda;
        this.elbv2 = elbv2;
        this.s3 = s3;
        this.logs = logs;
        this.firehose = firehose;
        this.route53 = route53;
    }

    @Override
    public void clear() {
        resources.clear();
    }

    public ObjectNode handle(String op, JsonNode in, String region) {
        synchronized (this) {
            return switch (op) {
                case "CreateServiceNetwork" -> createNetwork(in, region);
                case "GetServiceNetwork" -> networkView(region, find(region, NETWORK, in.path("serviceNetworkIdentifier")
                        .asText()), true);
                case "UpdateServiceNetwork" -> updateNetwork(in, region);
                case "DeleteServiceNetwork" -> deleteNetwork(in, region);
                case "ListServiceNetworks" -> list(region, NETWORK, r -> true, in, r -> networkView(region, r, false));
                case "CreateService" -> createService(in, region);
                case "GetService" -> serviceView(region, find(region, SERVICE, in.path("serviceIdentifier").asText()),
                        true);
                case "UpdateService" -> updateService(in, region);
                case "DeleteService" -> deleteService(in, region);
                case "ListServices" -> list(region, SERVICE, r -> true, in, r -> serviceView(region, r, false));
                case "CreateServiceNetworkVpcAssociation" -> createVpcAssociation(in, region);
                case "GetServiceNetworkVpcAssociation" -> vpcAssociationView(region, find(region, VPC_ASSOCIATION,
                        in.path("serviceNetworkVpcAssociationIdentifier").asText()), true);
                case "UpdateServiceNetworkVpcAssociation" -> updateVpcAssociation(in, region);
                case "DeleteServiceNetworkVpcAssociation" -> deleteAssociation(region, VPC_ASSOCIATION,
                        in.path("serviceNetworkVpcAssociationIdentifier").asText());
                case "ListServiceNetworkVpcAssociations" -> listVpcAssociations(in, region);
                case "CreateServiceNetworkServiceAssociation" -> createServiceAssociation(in, region);
                case "GetServiceNetworkServiceAssociation" -> serviceAssociationView(region, find(region,
                        SERVICE_ASSOCIATION, in.path("serviceNetworkServiceAssociationIdentifier").asText()), true);
                case "DeleteServiceNetworkServiceAssociation" -> deleteAssociation(region, SERVICE_ASSOCIATION,
                        in.path("serviceNetworkServiceAssociationIdentifier").asText());
                case "ListServiceNetworkServiceAssociations" -> listServiceAssociations(in, region);
                case "CreateServiceNetworkResourceAssociation" -> createResourceAssociation(in, region);
                case "GetServiceNetworkResourceAssociation" -> resourceAssociationView(region, find(region,
                        RESOURCE_ASSOCIATION, in.path("serviceNetworkResourceAssociationIdentifier").asText()), true);
                case "DeleteServiceNetworkResourceAssociation" -> deleteAssociation(region, RESOURCE_ASSOCIATION,
                        in.path("serviceNetworkResourceAssociationIdentifier").asText());
                case "ListServiceNetworkResourceAssociations" -> listResourceAssociations(in, region);
                case "ListServiceNetworkVpcEndpointAssociations" -> {
                    find(region, NETWORK, in.path("serviceNetworkIdentifier").asText());
                    yield items(mapper.createArrayNode(), null);
                }
                case "CreateTargetGroup" -> createTargetGroup(in, region);
                case "GetTargetGroup" -> targetGroupView(region, find(region, TARGET_GROUP,
                        in.path("targetGroupIdentifier").asText()), true);
                case "UpdateTargetGroup" -> updateTargetGroup(in, region);
                case "DeleteTargetGroup" -> deleteTargetGroup(in, region);
                case "ListTargetGroups" -> list(region, TARGET_GROUP, r ->
                                (!in.hasNonNull("vpcIdentifier") || in.get("vpcIdentifier").asText()
                                        .equals(r.path("config").path("vpcIdentifier").asText()))
                                        && (!in.hasNonNull("targetGroupType") || in.get("targetGroupType").asText()
                                        .equals(r.path("type").asText())),
                        in, r -> targetGroupView(region, r, false));
                case "RegisterTargets" -> registerTargets(in, region);
                case "DeregisterTargets" -> deregisterTargets(in, region);
                case "ListTargets" -> listTargets(in, region);
                case "CreateListener" -> createListener(in, region);
                case "GetListener" -> listenerView(listener(region, in), "get");
                case "UpdateListener" -> updateListener(in, region);
                case "DeleteListener" -> deleteListener(in, region);
                case "ListListeners" -> {
                    ObjectNode service = find(region, SERVICE, in.path("serviceIdentifier").asText());
                    yield list(region, LISTENER, r -> r.path("serviceId").asText().equals(service.path("id").asText()),
                            in, r -> listenerView(r, "summary"));
                }
                case "CreateRule" -> createRule(in, region);
                case "GetRule" -> ruleView(rule(region, in), true);
                case "UpdateRule" -> updateRule(in, region, in.path("ruleIdentifier").asText(), in);
                case "BatchUpdateRule" -> batchUpdateRules(in, region);
                case "DeleteRule" -> deleteRule(in, region);
                case "ListRules" -> listRules(in, region);
                case "PutAuthPolicy" -> putAuthPolicy(in, region);
                case "GetAuthPolicy" -> getAuthPolicy(in, region);
                case "DeleteAuthPolicy" -> deleteAuthPolicy(in, region);
                case "PutResourcePolicy" -> putResourcePolicy(in, region);
                case "GetResourcePolicy" -> getResourcePolicy(in, region);
                case "DeleteResourcePolicy" -> deleteResourcePolicy(in, region);
                case "CreateAccessLogSubscription" -> createAccessLog(in, region);
                case "GetAccessLogSubscription" -> accessLogView(find(region, ACCESS_LOG,
                        in.path("accessLogSubscriptionIdentifier").asText()));
                case "UpdateAccessLogSubscription" -> updateAccessLog(in, region);
                case "DeleteAccessLogSubscription" -> {
                    ObjectNode subscription = find(region, ACCESS_LOG, in.path("accessLogSubscriptionIdentifier")
                            .asText());
                    resources.delete(key(region, subscription.path("id").asText()));
                    yield mapper.createObjectNode();
                }
                case "ListAccessLogSubscriptions" -> {
                    ObjectNode owner = logOwner(region, in.path("resourceIdentifier").asText());
                    yield list(region, ACCESS_LOG, r -> r.path("resourceId").asText().equals(owner.path("id")
                            .asText()), in, this::accessLogView);
                }
                case "CreateResourceGateway" -> createGateway(in, region);
                case "GetResourceGateway" -> gatewayView(find(region, GATEWAY, in.path("resourceGatewayIdentifier")
                        .asText()), true);
                case "UpdateResourceGateway" -> updateGateway(in, region);
                case "DeleteResourceGateway" -> deleteGateway(in, region);
                case "ListResourceGateways" -> list(region, GATEWAY, r -> true, in, r -> gatewayView(r, false));
                case "CreateResourceConfiguration" -> createConfiguration(in, region);
                case "GetResourceConfiguration" -> configurationView(find(region, CONFIGURATION,
                        in.path("resourceConfigurationIdentifier").asText()), true);
                case "UpdateResourceConfiguration" -> updateConfiguration(in, region);
                case "DeleteResourceConfiguration" -> deleteConfiguration(in, region);
                case "ListResourceConfigurations" -> listConfigurations(in, region);
                case "ListResourceEndpointAssociations" -> {
                    find(region, CONFIGURATION, in.path("resourceConfigurationIdentifier").asText());
                    yield items(mapper.createArrayNode(), null);
                }
                case "DeleteResourceEndpointAssociation" -> throw notFound("resourceendpointassociation",
                        in.path("resourceEndpointAssociationIdentifier").asText());
                case "StartDomainVerification" -> startDomainVerification(in, region);
                case "GetDomainVerification" -> domainView(region, find(region, DOMAIN,
                        in.path("domainVerificationIdentifier").asText()), true);
                case "DeleteDomainVerification" -> deleteDomainVerification(in, region);
                case "ListDomainVerifications" -> list(region, DOMAIN, r -> true, in, r -> domainView(region, r, true));
                case "TagResource" -> tagResource(in, region);
                case "UntagResource" -> untagResource(in, region);
                case "ListTagsForResource" -> {
                    ObjectNode out = mapper.createObjectNode();
                    out.set("tags", byArn(region, in.path("resourceArn").asText()).path("_tags"));
                    yield out;
                }
                default -> throw validation("Unsupported operation " + op);
            };
        }
    }

    // ---------- service networks ----------

    private ObjectNode createNetwork(JsonNode in, String region) {
        String name = in.path("name").asText();
        Optional<ObjectNode> replay = replay(region, NETWORK, in);
        if (replay.isPresent()) {
            return created(replay.get(), "id", "name", "arn", "sharingConfig", "authType");
        }
        checkUniqueName(region, NETWORK, name, r -> true);
        ObjectNode network = create(region, NETWORK, in);
        network.put("name", name).put("authType", in.path("authType").asText("NONE"));
        network.putObject("sharingConfig").put("enabled", in.path("sharingConfig").path("enabled").asBoolean(true));
        save(region, network);
        return created(network, "id", "name", "arn", "sharingConfig", "authType");
    }

    private ObjectNode networkView(String region, ObjectNode network, boolean full) {
        ObjectNode view = view(network);
        String id = network.path("id").asText();
        long vpcs = count(region, VPC_ASSOCIATION, r -> id.equals(r.path("serviceNetworkId").asText()));
        long services = count(region, SERVICE_ASSOCIATION, r -> id.equals(r.path("serviceNetworkId").asText()));
        long configurations = count(region, RESOURCE_ASSOCIATION, r -> id.equals(r.path("serviceNetworkId").asText()));
        view.put("numberOfAssociatedVPCs", vpcs).put("numberOfAssociatedServices", services);
        if (!full) {
            view.remove("authType");
            view.remove("sharingConfig");
            view.put("numberOfAssociatedResourceConfigurations", configurations);
        }
        return view;
    }

    private ObjectNode updateNetwork(JsonNode in, String region) {
        ObjectNode network = find(region, NETWORK, in.path("serviceNetworkIdentifier").asText());
        network.put("authType", in.path("authType").asText());
        touch(network);
        save(region, network);
        ObjectNode view = view(network);
        view.remove("sharingConfig");
        view.remove("createdAt");
        view.remove("lastUpdatedAt");
        return view;
    }

    private ObjectNode deleteNetwork(JsonNode in, String region) {
        ObjectNode network = find(region, NETWORK, in.path("serviceNetworkIdentifier").asText());
        String id = network.path("id").asText();
        for (String kind : List.of(VPC_ASSOCIATION, SERVICE_ASSOCIATION, RESOURCE_ASSOCIATION)) {
            if (count(region, kind, r -> id.equals(r.path("serviceNetworkId").asText())) > 0) {
                throw conflict(NETWORK, id, "The service network " + id + " has associations. Delete its VPC, "
                        + "service and resource associations first.");
            }
        }
        deleteChildren(region, ACCESS_LOG, r -> id.equals(r.path("resourceId").asText()));
        resources.delete(key(region, id));
        return mapper.createObjectNode();
    }

    // ---------- services ----------

    private ObjectNode createService(JsonNode in, String region) {
        Optional<ObjectNode> replay = replay(region, SERVICE, in);
        if (replay.isPresent()) {
            return created(replay.get(), SERVICE_CREATED);
        }
        String name = in.path("name").asText();
        checkUniqueName(region, SERVICE, name, r -> true);
        if (in.hasNonNull("certificateArn") && !in.get("certificateArn").asText().isEmpty()
                && !in.hasNonNull("customDomainName")) {
            throw validation("A certificate requires a custom domain name.");
        }
        ObjectNode service = create(region, SERVICE, in);
        String id = service.path("id").asText();
        service.put("name", name).put("authType", in.path("authType").asText("NONE"))
                .put("idleTimeoutSeconds", in.path("idleTimeoutSeconds").asInt(60));
        copy(in, service, "customDomainName", "certificateArn");
        service.putObject("dnsEntry").put("domainName", name + "-" + id.substring(4) + "." + suffix(region))
                .put("hostedZoneId", hostedZone(region));
        transition(service, "CREATE_IN_PROGRESS", "ACTIVE");
        save(region, service);
        settle(region, id);
        return created(service, SERVICE_CREATED);
    }

    private static final String[] SERVICE_CREATED = {"id", "arn", "name", "customDomainName", "certificateArn",
        "status", "authType", "idleTimeoutSeconds", "dnsEntry"};

    /** The fields a create returns. */
    private ObjectNode created(ObjectNode node, String... fields) {
        ObjectNode out = mapper.createObjectNode();
        copy(node, out, fields);
        return out;
    }

    private ObjectNode serviceView(String region, ObjectNode service, boolean full) {
        ObjectNode view = view(service);
        if (!full) {
            view.remove("authType");
            view.remove("idleTimeoutSeconds");
            view.remove("certificateArn");
        }
        return view;
    }

    private ObjectNode updateService(JsonNode in, String region) {
        ObjectNode service = find(region, SERVICE, in.path("serviceIdentifier").asText());
        checkSettled(service, SERVICE);
        copy(in, service, "authType", "idleTimeoutSeconds", "certificateArn");
        touch(service);
        save(region, service);
        ObjectNode view = view(service);
        for (String field : List.of("status", "dnsEntry", "createdAt", "lastUpdatedAt")) {
            view.remove(field);
        }
        return view;
    }

    private ObjectNode deleteService(JsonNode in, String region) {
        ObjectNode service = find(region, SERVICE, in.path("serviceIdentifier").asText());
        checkSettled(service, SERVICE);
        String id = service.path("id").asText();
        if (count(region, SERVICE_ASSOCIATION, r -> id.equals(r.path("serviceId").asText())) > 0) {
            throw conflict(SERVICE, id, "The service " + id + " is associated with a service network. Delete the "
                    + "association first.");
        }
        deleteChildren(region, RULE, r -> id.equals(r.path("serviceId").asText()));
        deleteChildren(region, LISTENER, r -> id.equals(r.path("serviceId").asText()));
        deleteChildren(region, ACCESS_LOG, r -> id.equals(r.path("resourceId").asText()));
        transition(service, "DELETE_IN_PROGRESS", "DELETED");
        save(region, service);
        settle(region, id);
        ObjectNode out = mapper.createObjectNode();
        copy(service, out, "id", "arn", "name", "status");
        return out;
    }

    // ---------- associations ----------

    private ObjectNode createVpcAssociation(JsonNode in, String region) {
        Optional<ObjectNode> replay = replay(region, VPC_ASSOCIATION, in);
        if (replay.isPresent()) {
            return vpcAssociationCreated(replay.get());
        }
        ObjectNode network = find(region, NETWORK, in.path("serviceNetworkIdentifier").asText());
        String vpcId = in.path("vpcIdentifier").asText();
        requireVpc(region, vpcId);
        List<String> groups = strings(in.path("securityGroupIds"));
        requireSecurityGroups(region, vpcId, groups);
        for (ObjectNode existing : all(region, VPC_ASSOCIATION)) {
            if (vpcId.equals(existing.path("vpcId").asText())
                    && !"DELETE_IN_PROGRESS".equals(existing.path("status").asText())) {
                throw conflict(VPC_ASSOCIATION, existing.path("id").asText(), "The VPC " + vpcId
                        + " is already associated with service network " + existing.path("serviceNetworkId").asText()
                        + ".");
            }
        }
        ObjectNode association = create(region, VPC_ASSOCIATION, in);
        association.put("serviceNetworkId", network.path("id").asText())
                .put("serviceNetworkName", network.path("name").asText())
                .put("serviceNetworkArn", network.path("arn").asText()).put("vpcId", vpcId)
                .put("createdBy", resources.accountId())
                .put("privateDnsEnabled", in.path("privateDnsEnabled").asBoolean(true));
        association.set("securityGroupIds", array(groups));
        if (in.path("dnsOptions").isObject()) {
            association.set("dnsOptions", in.get("dnsOptions").deepCopy());
        }
        transition(association, "CREATE_IN_PROGRESS", "ACTIVE");
        save(region, association);
        settle(region, association.path("id").asText());
        return vpcAssociationCreated(association);
    }

    private ObjectNode vpcAssociationCreated(ObjectNode association) {
        ObjectNode out = mapper.createObjectNode();
        copy(association, out, "id", "status", "arn", "createdBy", "securityGroupIds", "privateDnsEnabled",
                "dnsOptions");
        return out;
    }

    private ObjectNode vpcAssociationView(String region, ObjectNode association, boolean full) {
        ObjectNode view = view(association);
        if (!full) {
            view.remove("securityGroupIds");
            view.remove("failureMessage");
            view.remove("failureCode");
        }
        return view;
    }

    private ObjectNode updateVpcAssociation(JsonNode in, String region) {
        ObjectNode association = find(region, VPC_ASSOCIATION, in.path("serviceNetworkVpcAssociationIdentifier")
                .asText());
        checkSettled(association, VPC_ASSOCIATION);
        if (in.path("securityGroupIds").isArray()) {
            List<String> groups = strings(in.get("securityGroupIds"));
            if (groups.isEmpty() && association.path("securityGroupIds").size() > 0) {
                throw validation("You can't remove all security groups from an association that has them.");
            }
            requireSecurityGroups(region, association.path("vpcId").asText(), groups);
            association.set("securityGroupIds", array(groups));
        }
        copy(in, association, "privateDnsEnabled", "dnsOptions");
        touch(association);
        save(region, association);
        return vpcAssociationCreated(association);
    }

    private ObjectNode listVpcAssociations(JsonNode in, String region) {
        String networkId = in.hasNonNull("serviceNetworkIdentifier")
                ? find(region, NETWORK, in.get("serviceNetworkIdentifier").asText()).path("id").asText() : null;
        String vpcId = in.hasNonNull("vpcIdentifier") ? in.get("vpcIdentifier").asText() : null;
        if (networkId == null && vpcId == null) {
            throw validation("Specify serviceNetworkIdentifier or vpcIdentifier.");
        }
        return list(region, VPC_ASSOCIATION, r -> (networkId == null || networkId.equals(r.path("serviceNetworkId")
                        .asText())) && (vpcId == null || vpcId.equals(r.path("vpcId").asText())), in,
                r -> vpcAssociationView(region, r, false));
    }

    private ObjectNode createServiceAssociation(JsonNode in, String region) {
        Optional<ObjectNode> replay = replay(region, SERVICE_ASSOCIATION, in);
        if (replay.isPresent()) {
            return serviceAssociationCreated(replay.get());
        }
        ObjectNode network = find(region, NETWORK, in.path("serviceNetworkIdentifier").asText());
        ObjectNode service = find(region, SERVICE, in.path("serviceIdentifier").asText());
        checkSettled(service, SERVICE);
        String networkId = network.path("id").asText();
        String serviceId = service.path("id").asText();
        for (ObjectNode existing : all(region, SERVICE_ASSOCIATION)) {
            if (networkId.equals(existing.path("serviceNetworkId").asText())
                    && serviceId.equals(existing.path("serviceId").asText())) {
                throw conflict(SERVICE_ASSOCIATION, existing.path("id").asText(), "The service " + serviceId
                        + " is already associated with service network " + networkId + ".");
            }
        }
        ObjectNode association = create(region, SERVICE_ASSOCIATION, in);
        association.put("serviceNetworkId", networkId).put("serviceNetworkName", network.path("name").asText())
                .put("serviceNetworkArn", network.path("arn").asText()).put("serviceId", serviceId)
                .put("serviceName", service.path("name").asText()).put("serviceArn", service.path("arn").asText())
                .put("createdBy", resources.accountId());
        association.set("dnsEntry", service.path("dnsEntry").deepCopy());
        copy(service, association, "customDomainName");
        transition(association, "CREATE_IN_PROGRESS", "ACTIVE");
        save(region, association);
        settle(region, association.path("id").asText());
        return serviceAssociationCreated(association);
    }

    private ObjectNode serviceAssociationCreated(ObjectNode association) {
        ObjectNode out = mapper.createObjectNode();
        copy(association, out, "id", "status", "arn", "createdBy", "customDomainName", "dnsEntry");
        return out;
    }

    private ObjectNode serviceAssociationView(String region, ObjectNode association, boolean full) {
        return view(association);
    }

    private ObjectNode listServiceAssociations(JsonNode in, String region) {
        String networkId = in.hasNonNull("serviceNetworkIdentifier")
                ? find(region, NETWORK, in.get("serviceNetworkIdentifier").asText()).path("id").asText() : null;
        String serviceId = in.hasNonNull("serviceIdentifier")
                ? find(region, SERVICE, in.get("serviceIdentifier").asText()).path("id").asText() : null;
        if (networkId == null && serviceId == null) {
            throw validation("Specify serviceNetworkIdentifier or serviceIdentifier.");
        }
        return list(region, SERVICE_ASSOCIATION, r -> (networkId == null || networkId.equals(r.path("serviceNetworkId")
                        .asText())) && (serviceId == null || serviceId.equals(r.path("serviceId").asText())), in,
                r -> serviceAssociationView(region, r, false));
    }

    private ObjectNode createResourceAssociation(JsonNode in, String region) {
        Optional<ObjectNode> replay = replay(region, RESOURCE_ASSOCIATION, in);
        if (replay.isPresent()) {
            return resourceAssociationCreated(replay.get());
        }
        ObjectNode network = find(region, NETWORK, in.path("serviceNetworkIdentifier").asText());
        ObjectNode configuration = find(region, CONFIGURATION, in.path("resourceConfigurationIdentifier").asText());
        if ("CHILD".equals(configuration.path("type").asText())) {
            throw validation("A CHILD resource configuration is associated through its GROUP.");
        }
        String networkId = network.path("id").asText();
        String configurationId = configuration.path("id").asText();
        for (ObjectNode existing : all(region, RESOURCE_ASSOCIATION)) {
            if (networkId.equals(existing.path("serviceNetworkId").asText())
                    && configurationId.equals(existing.path("resourceConfigurationId").asText())) {
                throw conflict(RESOURCE_ASSOCIATION, existing.path("id").asText(), "The resource configuration "
                        + configurationId + " is already associated with service network " + networkId + ".");
            }
        }
        ObjectNode association = create(region, RESOURCE_ASSOCIATION, in);
        association.put("serviceNetworkId", networkId).put("serviceNetworkName", network.path("name").asText())
                .put("serviceNetworkArn", network.path("arn").asText())
                .put("resourceConfigurationId", configurationId)
                .put("resourceConfigurationName", configuration.path("name").asText())
                .put("resourceConfigurationArn", configuration.path("arn").asText())
                .put("createdBy", resources.accountId()).put("isManagedAssociation", false)
                .put("privateDnsEnabled", in.path("privateDnsEnabled").asBoolean(false));
        String id = association.path("id").asText();
        association.putObject("dnsEntry").put("domainName", id + "." + configurationId + "."
                + suffix(region).replace("vpc-lattice-svcs", "vpc-lattice-rsc")).put("hostedZoneId", hostedZone(region));
        if (configuration.hasNonNull("customDomainName")) {
            association.putObject("privateDnsEntry").put("domainName", configuration.get("customDomainName").asText())
                    .put("hostedZoneId", hostedZone(region));
        }
        transition(association, "CREATE_IN_PROGRESS", "ACTIVE");
        save(region, association);
        settle(region, id);
        return resourceAssociationCreated(association);
    }

    private ObjectNode resourceAssociationCreated(ObjectNode association) {
        ObjectNode out = mapper.createObjectNode();
        copy(association, out, "id", "arn", "status", "createdBy", "privateDnsEnabled");
        return out;
    }

    private ObjectNode resourceAssociationView(String region, ObjectNode association, boolean full) {
        ObjectNode view = view(association);
        if (!full) {
            view.remove("lastUpdatedAt");
            view.remove("failureReason");
        }
        return view;
    }

    private ObjectNode listResourceAssociations(JsonNode in, String region) {
        String networkId = in.hasNonNull("serviceNetworkIdentifier")
                ? find(region, NETWORK, in.get("serviceNetworkIdentifier").asText()).path("id").asText() : null;
        String configurationId = in.hasNonNull("resourceConfigurationIdentifier")
                ? find(region, CONFIGURATION, in.get("resourceConfigurationIdentifier").asText()).path("id").asText()
                : null;
        return list(region, RESOURCE_ASSOCIATION, r -> (networkId == null || networkId.equals(
                        r.path("serviceNetworkId").asText())) && (configurationId == null
                        || configurationId.equals(r.path("resourceConfigurationId").asText())), in,
                r -> resourceAssociationView(region, r, false));
    }

    private ObjectNode deleteAssociation(String region, String kind, String identifier) {
        ObjectNode association = find(region, kind, identifier);
        checkSettled(association, kind);
        transition(association, "DELETE_IN_PROGRESS", "DELETED");
        save(region, association);
        settle(region, association.path("id").asText());
        ObjectNode out = mapper.createObjectNode();
        copy(association, out, "id", "status", "arn");
        return out;
    }

    // ---------- target groups and targets ----------

    private ObjectNode createTargetGroup(JsonNode in, String region) {
        Optional<ObjectNode> replay = replay(region, TARGET_GROUP, in);
        if (replay.isPresent()) {
            return targetGroupView(region, replay.get(), true);
        }
        String name = in.path("name").asText();
        checkUniqueName(region, TARGET_GROUP, name, r -> true);
        String type = in.path("type").asText();
        JsonNode configIn = in.path("config");
        ObjectNode config = mapper.createObjectNode();
        if ("LAMBDA".equals(type)) {
            for (String field : List.of("port", "protocol", "protocolVersion", "ipAddressType", "vpcIdentifier",
                    "healthCheck")) {
                if (configIn.has(field)) {
                    throw validation("A LAMBDA target group doesn't take config." + field + ".");
                }
            }
            config.put("lambdaEventStructureVersion", configIn.path("lambdaEventStructureVersion").asText("V1"));
        } else {
            if (configIn.has("lambdaEventStructureVersion")) {
                throw validation("Only a LAMBDA target group takes lambdaEventStructureVersion.");
            }
            for (String field : List.of("port", "protocol", "vpcIdentifier")) {
                if (!configIn.hasNonNull(field)) {
                    throw validation("A " + type + " target group requires config." + field + ".");
                }
            }
            String protocol = configIn.get("protocol").asText();
            if ("ALB".equals(type) && "TCP".equals(protocol)) {
                throw validation("An ALB target group uses HTTP or HTTPS.");
            }
            requireVpc(region, configIn.get("vpcIdentifier").asText());
            config.put("port", configIn.get("port").asInt()).put("protocol", protocol);
            if (!"TCP".equals(protocol)) {
                config.put("protocolVersion", configIn.path("protocolVersion").asText("HTTP1"));
            }
            if ("IP".equals(type)) {
                config.put("ipAddressType", configIn.path("ipAddressType").asText("IPV4"));
            }
            config.put("vpcIdentifier", configIn.get("vpcIdentifier").asText());
            if (!"ALB".equals(type)) {
                config.set("healthCheck", healthCheck(configIn.path("healthCheck"), protocol, null));
            }
        }
        ObjectNode group = create(region, TARGET_GROUP, in);
        group.put("name", name).put("type", type);
        group.set("config", config);
        group.putArray("_targets");
        transition(group, "CREATE_IN_PROGRESS", "ACTIVE");
        save(region, group);
        settle(region, group.path("id").asText());
        ObjectNode out = view(group);
        out.remove("createdAt");
        out.remove("lastUpdatedAt");
        return out;
    }

    /** A target group's health check with AWS's defaults filled in. */
    private ObjectNode healthCheck(JsonNode in, String protocol, JsonNode current) {
        ObjectNode check = current != null && current.isObject() ? ((ObjectNode) current).deepCopy()
                : mapper.createObjectNode().put("enabled", true).put("protocol", "TCP".equals(protocol) ? "HTTP"
                        : protocol).put("protocolVersion", "HTTP1").put("path", "/")
                        .put("healthCheckIntervalSeconds", 30).put("healthCheckTimeoutSeconds", 5)
                        .put("healthyThresholdCount", 5).put("unhealthyThresholdCount", 2);
        if (!check.has("matcher")) {
            check.putObject("matcher").put("httpCode", "200");
        }
        if (in != null && in.isObject()) {
            in.fields().forEachRemaining(e -> check.set(e.getKey(), e.getValue().deepCopy()));
        }
        if (check.path("healthCheckTimeoutSeconds").asInt() >= check.path("healthCheckIntervalSeconds").asInt()
                && check.path("enabled").asBoolean()) {
            throw validation("healthCheckTimeoutSeconds must be shorter than healthCheckIntervalSeconds.");
        }
        return check;
    }

    ObjectNode targetGroupView(String region, ObjectNode group, boolean full) {
        ObjectNode view = view(group);
        ArrayNode services = mapper.createArrayNode();
        servicesUsing(region, group.path("id").asText()).forEach(services::add);
        view.set("serviceArns", services);
        if (!full) {
            JsonNode config = view.remove("config");
            copy(config, view, "port", "protocol", "ipAddressType", "vpcIdentifier", "lambdaEventStructureVersion");
        }
        return view;
    }

    /** The ARNs of the services whose listeners or rules forward to the target group. */
    Set<String> servicesUsing(String region, String targetGroupId) {
        Set<String> out = new TreeSet<>();
        for (String kind : List.of(LISTENER, RULE)) {
            for (ObjectNode node : all(region, kind)) {
                JsonNode action = node.has("defaultAction") ? node.path("defaultAction") : node.path("action");
                for (JsonNode weighted : action.path("forward").path("targetGroups")) {
                    if (targetGroupId.equals(idOf(weighted.path("targetGroupIdentifier").asText()))) {
                        out.add(node.path("serviceArn").asText());
                    }
                }
            }
        }
        return out;
    }

    private ObjectNode updateTargetGroup(JsonNode in, String region) {
        ObjectNode group = find(region, TARGET_GROUP, in.path("targetGroupIdentifier").asText());
        checkSettled(group, TARGET_GROUP);
        if ("LAMBDA".equals(group.path("type").asText()) || "ALB".equals(group.path("type").asText())) {
            throw validation("A " + group.path("type").asText() + " target group has no health check.");
        }
        ObjectNode config = (ObjectNode) group.path("config");
        config.set("healthCheck", healthCheck(in.path("healthCheck"), config.path("protocol").asText(),
                config.path("healthCheck")));
        touch(group);
        save(region, group);
        ObjectNode out = view(group);
        out.remove("createdAt");
        out.remove("lastUpdatedAt");
        return out;
    }

    private ObjectNode deleteTargetGroup(JsonNode in, String region) {
        ObjectNode group = find(region, TARGET_GROUP, in.path("targetGroupIdentifier").asText());
        checkSettled(group, TARGET_GROUP);
        String id = group.path("id").asText();
        if (!servicesUsing(region, id).isEmpty()) {
            throw conflict(TARGET_GROUP, id, "The target group " + id + " is used by a listener or rule.");
        }
        drain(group);
        if (group.path("_targets").size() > 0) {
            throw conflict(TARGET_GROUP, id, "TargetGroup has targets registered with it.");
        }
        transition(group, "DELETE_IN_PROGRESS", "DELETED");
        save(region, group);
        settle(region, id);
        ObjectNode out = mapper.createObjectNode();
        copy(group, out, "id", "arn", "status");
        return out;
    }

    private ObjectNode registerTargets(JsonNode in, String region) {
        ObjectNode group = find(region, TARGET_GROUP, in.path("targetGroupIdentifier").asText());
        checkSettled(group, TARGET_GROUP);
        drain(group);
        String type = group.path("type").asText();
        ArrayNode targets = (ArrayNode) group.withArray("_targets");
        ArrayNode successful = mapper.createArrayNode();
        ArrayNode unsuccessful = mapper.createArrayNode();
        for (JsonNode target : in.path("targets")) {
            String id = target.path("id").asText();
            int port = "LAMBDA".equals(type) ? 0 : target.path("port").asInt(group.path("config").path("port")
                    .asInt());
            Optional<String> refusal = targetRefusal(region, group, id);
            if (refusal.isEmpty() && "LAMBDA".equals(type)) {
                boolean other = false;
                for (JsonNode existing : targets) {
                    other |= !id.equals(existing.path("id").asText());
                }
                if (other) {
                    refusal = Optional.of("A LAMBDA target group holds one function.");
                }
            }
            if (refusal.isPresent()) {
                unsuccessful.addObject().put("id", id).put("port", port).put("failureCode", "InvalidTarget")
                        .put("failureMessage", refusal.get());
                continue;
            }
            ObjectNode existing = null;
            for (JsonNode candidate : targets) {
                if (id.equals(candidate.path("id").asText()) && port == candidate.path("port").asInt()) {
                    existing = (ObjectNode) candidate;
                }
            }
            if (existing == null) {
                existing = targets.addObject().put("id", id).put("port", port);
            }
            existing.remove("drainUntil");
            existing.put("registeredAt", Instant.now().toEpochMilli()).put("health", "INITIAL")
                    .put("healthyCount", 0).put("unhealthyCount", 0);
            ObjectNode ok = successful.addObject().put("id", id);
            if (port > 0) {
                ok.put("port", port);
            }
        }
        save(region, group);
        ObjectNode out = mapper.createObjectNode();
        out.set("successful", successful);
        out.set("unsuccessful", unsuccessful);
        return out;
    }

    /** Why a target can't join the group, or empty when it can. */
    private Optional<String> targetRefusal(String region, ObjectNode group, String id) {
        String vpcId = group.path("config").path("vpcIdentifier").asText();
        switch (group.path("type").asText()) {
            case "IP" -> {
                if (!id.matches("\\d{1,3}(\\.\\d{1,3}){3}") && !id.contains(":")) {
                    return Optional.of(id + " is not an IP address.");
                }
                if (id.contains(":")) {
                    return Optional.empty();
                }
                for (Subnet subnet : subnets(region, vpcId)) {
                    if (inCidr(id, subnet.getCidrBlock())) {
                        return Optional.empty();
                    }
                }
                return Optional.of("The IP address " + id + " is not in a subnet of " + vpcId + ".");
            }
            case "INSTANCE" -> {
                Instance instance = ec2.get().findInstanceById(resources.accountId(), id);
                if (instance == null || "terminated".equals(instance.getState() == null ? null
                        : instance.getState().getName())) {
                    return Optional.of("The instance " + id + " doesn't exist.");
                }
                return vpcId.equals(instance.getVpcId()) ? Optional.empty()
                        : Optional.of("The instance " + id + " is not in " + vpcId + ".");
            }
            case "LAMBDA" -> {
                try {
                    lambda.get().getFunction(region, id);
                    return Optional.empty();
                } catch (AwsException e) {
                    return Optional.of("The Lambda function " + id + " doesn't exist.");
                }
            }
            case "ALB" -> {
                try {
                    String lbVpc = elbv2.get().describeLoadBalancers(region, List.of(id), List.of(), null, null).stream()
                            .findFirst().map(lb -> lb.getVpcId()).orElse(null);
                    if (lbVpc == null) {
                        return Optional.of("The load balancer " + id + " doesn't exist.");
                    }
                    return vpcId.equals(lbVpc) ? Optional.empty()
                            : Optional.of("The load balancer " + id + " is not in " + vpcId + ".");
                } catch (AwsException e) {
                    return Optional.of("The load balancer " + id + " doesn't exist.");
                }
            }
            default -> {
                return Optional.of("Unsupported target type.");
            }
        }
    }

    private ObjectNode deregisterTargets(JsonNode in, String region) {
        ObjectNode group = find(region, TARGET_GROUP, in.path("targetGroupIdentifier").asText());
        drain(group);
        ArrayNode successful = mapper.createArrayNode();
        ArrayNode unsuccessful = mapper.createArrayNode();
        long drainUntil = Instant.now().toEpochMilli() + 2 * config.services().vpcLattice().transitionMillis();
        for (JsonNode target : in.path("targets")) {
            String id = target.path("id").asText();
            boolean found = false;
            for (JsonNode existing : group.path("_targets")) {
                if (id.equals(existing.path("id").asText()) && (!target.has("port")
                        || target.get("port").asInt() == existing.path("port").asInt())) {
                    ((ObjectNode) existing).put("drainUntil", drainUntil);
                    found = true;
                }
            }
            if (found) {
                ObjectNode ok = successful.addObject().put("id", id);
                if (target.has("port")) {
                    ok.put("port", target.get("port").asInt());
                }
            } else {
                unsuccessful.addObject().put("id", id).put("failureCode", "TargetNotRegistered")
                        .put("failureMessage", "The target " + id + " is not registered.");
            }
        }
        save(region, group);
        executor.schedule(() -> RequestScopes.runAs(resources.accountId(), region, () -> {
            synchronized (this) {
                resources.get(key(region, group.path("id").asText())).ifPresent(current -> {
                    drain(current);
                    save(region, current);
                });
            }
        }), 3 * config.services().vpcLattice().transitionMillis(), TimeUnit.MILLISECONDS);
        ObjectNode out = mapper.createObjectNode();
        out.set("successful", successful);
        out.set("unsuccessful", unsuccessful);
        return out;
    }

    /** Removes targets whose deregistration delay has passed. */
    private static void drain(ObjectNode group) {
        long now = Instant.now().toEpochMilli();
        ArrayNode targets = (ArrayNode) group.withArray("_targets");
        for (int i = targets.size() - 1; i >= 0; i--) {
            JsonNode target = targets.get(i);
            if (target.has("drainUntil") && target.get("drainUntil").asLong() <= now) {
                targets.remove(i);
            }
        }
    }

    private ObjectNode listTargets(JsonNode in, String region) {
        ObjectNode group = find(region, TARGET_GROUP, in.path("targetGroupIdentifier").asText());
        drain(group);
        save(region, group);
        List<String> wanted = new ArrayList<>();
        in.path("targets").forEach(t -> wanted.add(t.path("id").asText()));
        boolean inUse = !servicesUsing(region, group.path("id").asText()).isEmpty();
        JsonNode health = group.path("config").path("healthCheck");
        boolean checked = health.path("enabled").asBoolean(false) && !"LAMBDA".equals(group.path("type").asText())
                && !"ALB".equals(group.path("type").asText());
        List<ObjectNode> out = new ArrayList<>();
        for (JsonNode target : group.path("_targets")) {
            if (!wanted.isEmpty() && !wanted.contains(target.path("id").asText())) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode().put("id", target.path("id").asText());
            if (target.path("port").asInt() > 0) {
                summary.put("port", target.path("port").asInt());
            }
            if (target.has("drainUntil")) {
                summary.put("status", "DRAINING").put("reasonCode", "TargetDeregistrationInProgress");
            } else if (!inUse) {
                summary.put("status", "UNUSED").put("reasonCode", "TargetGroupNotInUse");
            } else if (!checked) {
                summary.put("status", "UNAVAILABLE").put("reasonCode", "HealthCheckDisabled");
            } else {
                String status = target.path("health").asText("INITIAL");
                summary.put("status", status);
                if (!"HEALTHY".equals(status)) {
                    summary.put("reasonCode", "INITIAL".equals(status) ? "RegistrationInProgress"
                            : target.path("healthReason").asText("HealthCheckFailed"));
                }
            }
            out.add(summary);
        }
        ArrayNode page = mapper.createArrayNode();
        String next = page(out, in, page::add);
        return items(page, next);
    }

    /** Target groups whose targets the health checker probes: in use, with health checks enabled. */
    List<ObjectNode> checkedTargetGroups(String region) {
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode group : all(region, TARGET_GROUP)) {
            String type = group.path("type").asText();
            if (("IP".equals(type) || "INSTANCE".equals(type))
                    && group.path("config").path("healthCheck").path("enabled").asBoolean(false)
                    && !servicesUsing(region, group.path("id").asText()).isEmpty()) {
                out.add(group);
            }
        }
        return out;
    }

    /** Records one health check result for a target, applying the group's thresholds. */
    synchronized void recordHealth(String region, String groupId, String targetId, int port, boolean healthy,
                                   String reason) {
        resources.get(key(region, groupId)).ifPresent(group -> {
            JsonNode check = group.path("config").path("healthCheck");
            for (JsonNode node : group.path("_targets")) {
                ObjectNode target = (ObjectNode) node;
                if (!targetId.equals(target.path("id").asText()) || port != target.path("port").asInt()) {
                    continue;
                }
                if (healthy) {
                    int count = target.path("healthyCount").asInt() + 1;
                    target.put("healthyCount", count).put("unhealthyCount", 0);
                    if (count >= check.path("healthyThresholdCount").asInt(5)) {
                        target.put("health", "HEALTHY");
                        target.remove("healthReason");
                    }
                } else {
                    int count = target.path("unhealthyCount").asInt() + 1;
                    target.put("unhealthyCount", count).put("healthyCount", 0);
                    if (count >= check.path("unhealthyThresholdCount").asInt(2)) {
                        target.put("health", "UNHEALTHY").put("healthReason", reason);
                    }
                }
            }
            save(region, group);
        });
    }

    // ---------- listeners and rules ----------

    private ObjectNode createListener(JsonNode in, String region) {
        ObjectNode service = find(region, SERVICE, in.path("serviceIdentifier").asText());
        checkSettled(service, SERVICE);
        String serviceId = service.path("id").asText();
        String name = in.path("name").asText();
        String protocol = in.path("protocol").asText();
        int port = in.has("port") ? in.get("port").asInt() : "HTTP".equals(protocol) ? 80 : 443;
        for (ObjectNode existing : all(region, LISTENER)) {
            if (!serviceId.equals(existing.path("serviceId").asText())) {
                continue;
            }
            if (name.equals(existing.path("name").asText())) {
                throw conflict(LISTENER, existing.path("id").asText(), "A listener named " + name
                        + " already exists on service " + serviceId + ".");
            }
            if (port == existing.path("port").asInt()) {
                throw conflict(LISTENER, existing.path("id").asText(), "A listener on port " + port
                        + " already exists on service " + serviceId + ".");
            }
        }
        checkAction(region, protocol, in.path("defaultAction"));
        String id = "listener-" + random(17, ALNUM);
        Instant now = Instant.now();
        ObjectNode listener = mapper.createObjectNode().put("_kind", LISTENER).put("id", id)
                .put("arn", service.path("arn").asText() + "/listener/" + id).put("name", name)
                .put("protocol", protocol).put("port", port).put("serviceArn", service.path("arn").asText())
                .put("serviceId", serviceId).put("createdAt", now.toString()).put("lastUpdatedAt", now.toString());
        listener.set("defaultAction", in.path("defaultAction").deepCopy());
        listener.set("_tags", tags(in));
        listener.put("_defaultRuleId", "rule-" + random(17, ALNUM));
        save(region, listener);
        return listenerView(listener, "create");
    }

    private ObjectNode listener(String region, JsonNode in) {
        ObjectNode service = find(region, SERVICE, in.path("serviceIdentifier").asText());
        ObjectNode listener = find(region, LISTENER, in.path("listenerIdentifier").asText());
        if (!service.path("id").asText().equals(listener.path("serviceId").asText())) {
            throw notFound(LISTENER, in.path("listenerIdentifier").asText());
        }
        return listener;
    }

    /** A listener as CreateListener ({@code create}), GetListener ({@code get}) or ListListeners shows it. */
    private ObjectNode listenerView(ObjectNode listener, String mode) {
        ObjectNode view = view(listener);
        if ("summary".equals(mode)) {
            for (String field : List.of("defaultAction", "serviceArn", "serviceId")) {
                view.remove(field);
            }
        } else if ("create".equals(mode)) {
            view.remove("createdAt");
            view.remove("lastUpdatedAt");
        }
        return view;
    }

    private ObjectNode updateListener(JsonNode in, String region) {
        ObjectNode listener = listener(region, in);
        checkAction(region, listener.path("protocol").asText(), in.path("defaultAction"));
        listener.set("defaultAction", in.path("defaultAction").deepCopy());
        touch(listener);
        save(region, listener);
        ObjectNode view = view(listener);
        view.remove("createdAt");
        view.remove("lastUpdatedAt");
        return view;
    }

    private ObjectNode deleteListener(JsonNode in, String region) {
        ObjectNode listener = listener(region, in);
        String id = listener.path("id").asText();
        deleteChildren(region, RULE, r -> id.equals(r.path("listenerId").asText()));
        resources.delete(key(region, id));
        return mapper.createObjectNode();
    }

    /** Checks a listener or rule action: forward target groups must exist, be ready and suit the protocol. */
    private void checkAction(String region, String protocol, JsonNode action) {
        boolean forward = action.has("forward");
        boolean fixed = action.has("fixedResponse");
        if (forward == fixed) {
            throw validation("An action is either forward or fixedResponse.");
        }
        if ("TLS_PASSTHROUGH".equals(protocol) && fixed) {
            throw validation("A TLS_PASSTHROUGH listener can only forward.");
        }
        if (!forward) {
            return;
        }
        int total = 0;
        for (JsonNode weighted : action.path("forward").path("targetGroups")) {
            ObjectNode group = find(region, TARGET_GROUP, weighted.path("targetGroupIdentifier").asText());
            checkSettled(group, TARGET_GROUP);
            String groupProtocol = group.path("config").path("protocol").asText();
            if ("TLS_PASSTHROUGH".equals(protocol) != "TCP".equals(groupProtocol)) {
                throw validation("The target group " + group.path("id").asText() + " ("
                        + (groupProtocol.isEmpty() ? group.path("type").asText() : groupProtocol)
                        + ") can't serve a " + protocol + " listener.");
            }
            total += weighted.path("weight").asInt(1);
        }
        if (total == 0) {
            throw validation("At least one target group needs a weight above 0.");
        }
    }

    private void checkMatch(String protocol, JsonNode match) {
        if ("TLS_PASSTHROUGH".equals(protocol)) {
            if (match.has("httpMatch")) {
                throw validation("A TLS_PASSTHROUGH listener's rules don't match HTTP.");
            }
            return;
        }
        JsonNode http = match.path("httpMatch");
        if (!http.isObject() || (!http.has("method") && !http.has("pathMatch") && !http.has("headerMatches"))) {
            throw validation("A rule needs an httpMatch with a method, pathMatch or headerMatches.");
        }
        JsonNode path = http.path("pathMatch").path("match");
        if (http.has("pathMatch") && path.has("exact") == path.has("prefix")) {
            throw validation("A pathMatch is either exact or prefix.");
        }
        for (JsonNode header : http.path("headerMatches")) {
            int kinds = (header.path("match").has("exact") ? 1 : 0) + (header.path("match").has("prefix") ? 1 : 0)
                    + (header.path("match").has("contains") ? 1 : 0);
            if (kinds != 1) {
                throw validation("A headerMatch is exactly one of exact, prefix or contains.");
            }
        }
    }

    private ObjectNode createRule(JsonNode in, String region) {
        ObjectNode listener = listener(region, in);
        String listenerId = listener.path("id").asText();
        String name = in.path("name").asText();
        int priority = in.path("priority").asInt();
        for (ObjectNode existing : all(region, RULE)) {
            if (!listenerId.equals(existing.path("listenerId").asText())) {
                continue;
            }
            if (name.equals(existing.path("name").asText())) {
                throw conflict(RULE, existing.path("id").asText(), "A rule named " + name + " already exists.");
            }
            if (priority == existing.path("priority").asInt()) {
                throw conflict(RULE, existing.path("id").asText(), "A rule with priority " + priority
                        + " already exists.");
            }
        }
        checkMatch(listener.path("protocol").asText(), in.path("match"));
        checkAction(region, listener.path("protocol").asText(), in.path("action"));
        String id = "rule-" + random(17, ALNUM);
        Instant now = Instant.now();
        ObjectNode rule = mapper.createObjectNode().put("_kind", RULE).put("id", id)
                .put("arn", listener.path("arn").asText() + "/rule/" + id).put("name", name)
                .put("isDefault", false).put("priority", priority).put("listenerId", listenerId)
                .put("serviceId", listener.path("serviceId").asText())
                .put("serviceArn", listener.path("serviceArn").asText())
                .put("createdAt", now.toString()).put("lastUpdatedAt", now.toString());
        rule.set("match", in.path("match").deepCopy());
        rule.set("action", in.path("action").deepCopy());
        rule.set("_tags", tags(in));
        save(region, rule);
        ObjectNode out = ruleView(rule, true);
        out.remove("isDefault");
        out.remove("createdAt");
        out.remove("lastUpdatedAt");
        return out;
    }

    private ObjectNode rule(String region, JsonNode in) {
        ObjectNode listener = listener(region, in);
        String identifier = in.path("ruleIdentifier").asText();
        if (idOf(identifier).equals(listener.path("_defaultRuleId").asText())) {
            return defaultRule(listener);
        }
        ObjectNode rule = find(region, RULE, identifier);
        if (!listener.path("id").asText().equals(rule.path("listenerId").asText())) {
            throw notFound(RULE, identifier);
        }
        return rule;
    }

    /** The listener's default action, as the default rule {@code ListRules} and {@code GetRule} show. */
    private ObjectNode defaultRule(ObjectNode listener) {
        String id = listener.path("_defaultRuleId").asText();
        ObjectNode rule = mapper.createObjectNode().put("id", id).put("arn", listener.path("arn").asText()
                        + "/rule/" + id).put("name", "default").put("isDefault", true)
                .put("createdAt", listener.path("createdAt").asText())
                .put("lastUpdatedAt", listener.path("lastUpdatedAt").asText());
        rule.set("action", listener.path("defaultAction"));
        return rule;
    }

    private ObjectNode ruleView(ObjectNode rule, boolean full) {
        ObjectNode view = view(rule);
        for (String field : List.of("listenerId", "serviceId", "serviceArn")) {
            view.remove(field);
        }
        if (!full) {
            view.remove("match");
            view.remove("action");
        }
        return view;
    }

    private ObjectNode updateRule(JsonNode in, String region, String identifier, JsonNode changes) {
        ObjectNode listener = listener(region, in);
        if (idOf(identifier).equals(listener.path("_defaultRuleId").asText())) {
            throw validation("Change the default rule with UpdateListener.");
        }
        ObjectNode rule = find(region, RULE, identifier);
        if (!listener.path("id").asText().equals(rule.path("listenerId").asText())) {
            throw notFound(RULE, identifier);
        }
        if (changes.has("priority")) {
            int priority = changes.get("priority").asInt();
            for (ObjectNode existing : all(region, RULE)) {
                if (existing.path("listenerId").asText().equals(rule.path("listenerId").asText())
                        && !existing.path("id").asText().equals(rule.path("id").asText())
                        && priority == existing.path("priority").asInt()) {
                    throw conflict(RULE, existing.path("id").asText(), "A rule with priority " + priority
                            + " already exists.");
                }
            }
            rule.put("priority", priority);
        }
        if (changes.has("match")) {
            checkMatch(listener.path("protocol").asText(), changes.get("match"));
            rule.set("match", changes.get("match").deepCopy());
        }
        if (changes.has("action")) {
            checkAction(region, listener.path("protocol").asText(), changes.get("action"));
            rule.set("action", changes.get("action").deepCopy());
        }
        touch(rule);
        save(region, rule);
        ObjectNode out = ruleView(rule, true);
        out.remove("createdAt");
        out.remove("lastUpdatedAt");
        return out;
    }

    private ObjectNode batchUpdateRules(JsonNode in, String region) {
        listener(region, in);
        ArrayNode successful = mapper.createArrayNode();
        ArrayNode unsuccessful = mapper.createArrayNode();
        for (JsonNode change : in.path("rules")) {
            String identifier = change.path("ruleIdentifier").asText();
            try {
                successful.add(updateRule(in, region, identifier, change));
            } catch (AwsException e) {
                unsuccessful.addObject().put("ruleIdentifier", identifier).put("failureCode", e.getErrorCode())
                        .put("failureMessage", e.getMessage());
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("successful", successful);
        out.set("unsuccessful", unsuccessful);
        return out;
    }

    private ObjectNode deleteRule(JsonNode in, String region) {
        ObjectNode rule = rule(region, in);
        if (rule.path("isDefault").asBoolean()) {
            throw validation("The default rule can't be deleted.");
        }
        resources.delete(key(region, rule.path("id").asText()));
        return mapper.createObjectNode();
    }

    private ObjectNode listRules(JsonNode in, String region) {
        ObjectNode listener = listener(region, in);
        String listenerId = listener.path("id").asText();
        List<ObjectNode> rules = new ArrayList<>(all(region, RULE).stream()
                .filter(r -> listenerId.equals(r.path("listenerId").asText()))
                .sorted(Comparator.comparingInt(r -> r.path("priority").asInt())).toList());
        rules.add(defaultRule(listener));
        ArrayNode page = mapper.createArrayNode();
        String next = page(rules, in, r -> page.add(ruleView(r, false)));
        return items(page, next);
    }

    // ---------- policies ----------

    /** The service network, service or resource configuration an auth or resource policy belongs to. */
    private ObjectNode policyOwner(String region, String identifier) {
        String id = idOf(identifier);
        String kind = id.startsWith("sn-") ? NETWORK : id.startsWith("svc-") ? SERVICE
                : id.startsWith("rcfg-") ? CONFIGURATION : null;
        if (kind == null) {
            throw validation("The resource " + identifier + " isn't a service network, service or resource "
                    + "configuration.");
        }
        return find(region, kind, identifier);
    }

    private ObjectNode putAuthPolicy(JsonNode in, String region) {
        ObjectNode owner = policyOwner(region, in.path("resourceIdentifier").asText());
        String policy = in.path("policy").asText();
        checkPolicy(policy);
        Instant now = Instant.now();
        ObjectNode stored = (ObjectNode) owner.withObject("/_authPolicy");
        if (!stored.has("createdAt")) {
            stored.put("createdAt", now.toString());
        }
        stored.put("policy", policy).put("lastUpdatedAt", now.toString());
        save(region, owner);
        return mapper.createObjectNode().put("policy", policy).put("state", authState(owner));
    }

    private ObjectNode getAuthPolicy(JsonNode in, String region) {
        ObjectNode owner = policyOwner(region, in.path("resourceIdentifier").asText());
        JsonNode stored = owner.path("_authPolicy");
        if (!stored.has("policy")) {
            throw notFound("authpolicy", owner.path("id").asText());
        }
        return mapper.createObjectNode().put("policy", stored.path("policy").asText()).put("state", authState(owner))
                .put("createdAt", stored.path("createdAt").asText())
                .put("lastUpdatedAt", stored.path("lastUpdatedAt").asText());
    }

    private ObjectNode deleteAuthPolicy(JsonNode in, String region) {
        ObjectNode owner = policyOwner(region, in.path("resourceIdentifier").asText());
        owner.remove("_authPolicy");
        save(region, owner);
        return mapper.createObjectNode();
    }

    /** An auth policy is enforced only while its resource's auth type is AWS_IAM. */
    private static String authState(ObjectNode owner) {
        return "AWS_IAM".equals(owner.path("authType").asText()) ? "Active" : "Inactive";
    }

    private ObjectNode putResourcePolicy(JsonNode in, String region) {
        ObjectNode owner = byArn(region, in.path("resourceArn").asText());
        String policy = in.path("policy").asText();
        checkPolicy(policy);
        owner.put("_resourcePolicy", policy);
        save(region, owner);
        return mapper.createObjectNode();
    }

    private ObjectNode getResourcePolicy(JsonNode in, String region) {
        ObjectNode owner = byArn(region, in.path("resourceArn").asText());
        if (!owner.has("_resourcePolicy")) {
            throw notFound("resourcepolicy", owner.path("id").asText());
        }
        return mapper.createObjectNode().put("policy", owner.path("_resourcePolicy").asText());
    }

    private ObjectNode deleteResourcePolicy(JsonNode in, String region) {
        ObjectNode owner = byArn(region, in.path("resourceArn").asText());
        owner.remove("_resourcePolicy");
        save(region, owner);
        return mapper.createObjectNode();
    }

    private void checkPolicy(String policy) {
        JsonNode document;
        try {
            document = mapper.readTree(policy);
        } catch (Exception e) {
            throw validation("The policy is not valid JSON.");
        }
        if (document == null || !document.path("Statement").isArray() && !document.path("Statement").isObject()) {
            throw validation("The policy needs a Statement.");
        }
        for (JsonNode statement : document.path("Statement").isArray() ? document.path("Statement")
                : mapper.createArrayNode().add(document.path("Statement"))) {
            String effect = statement.path("Effect").asText();
            if (!"Allow".equals(effect) && !"Deny".equals(effect)) {
                throw validation("Each statement's Effect is Allow or Deny.");
            }
            if (!statement.has("Action") && !statement.has("NotAction")) {
                throw validation("Each statement needs an Action.");
            }
        }
    }

    // ---------- access logs ----------

    private ObjectNode logOwner(String region, String identifier) {
        return policyOwner(region, identifier);
    }

    private ObjectNode createAccessLog(JsonNode in, String region) {
        Optional<ObjectNode> replay = replay(region, ACCESS_LOG, in);
        if (replay.isPresent()) {
            return accessLogCreated(replay.get());
        }
        ObjectNode owner = logOwner(region, in.path("resourceIdentifier").asText());
        String destination = in.path("destinationArn").asText();
        String type = destinationType(region, destination);
        String logType = owner.path("id").asText().startsWith("sn-") ? in.path("serviceNetworkLogType")
                .asText("SERVICE") : null;
        if (logType != null && !owner.path("id").asText().startsWith("sn-")) {
            throw validation("serviceNetworkLogType applies to service networks.");
        }
        for (ObjectNode existing : all(region, ACCESS_LOG)) {
            if (existing.path("resourceId").asText().equals(owner.path("id").asText())
                    && type.equals(destinationType(region, existing.path("destinationArn").asText()))
                    && (logType == null || logType.equals(existing.path("serviceNetworkLogType").asText()))) {
                throw conflict(ACCESS_LOG, existing.path("id").asText(), "The resource already has an access log "
                        + "subscription to a " + type + " destination.");
            }
        }
        ObjectNode subscription = create(region, ACCESS_LOG, in);
        subscription.put("resourceId", owner.path("id").asText()).put("resourceArn", owner.path("arn").asText())
                .put("destinationArn", destination);
        if (logType != null) {
            subscription.put("serviceNetworkLogType", logType);
        }
        save(region, subscription);
        return accessLogCreated(subscription);
    }

    private ObjectNode accessLogCreated(ObjectNode subscription) {
        ObjectNode out = mapper.createObjectNode();
        copy(subscription, out, "id", "arn", "resourceId", "resourceArn", "serviceNetworkLogType", "destinationArn");
        return out;
    }

    private ObjectNode accessLogView(ObjectNode subscription) {
        return view(subscription);
    }

    private ObjectNode updateAccessLog(JsonNode in, String region) {
        ObjectNode subscription = find(region, ACCESS_LOG, in.path("accessLogSubscriptionIdentifier").asText());
        String destination = in.path("destinationArn").asText();
        String type = destinationType(region, destination);
        if (!type.equals(destinationType(region, subscription.path("destinationArn").asText()))) {
            throw validation("An access log subscription keeps its destination type (" + type + ").");
        }
        subscription.put("destinationArn", destination);
        touch(subscription);
        save(region, subscription);
        return accessLogCreated(subscription);
    }

    /** An S3 bucket, CloudWatch Logs group or Firehose stream that exists; returns its type. */
    private String destinationType(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("The destination " + arn + " is not an ARN.");
        }
        switch (parsed.service()) {
            case "s3" -> {
                String bucket = parsed.resource().contains("/") ? parsed.resource().substring(0,
                        parsed.resource().indexOf('/')) : parsed.resource();
                if (!s3.get().bucketExists(bucket)) {
                    throw notFound("destination", arn);
                }
                return "s3";
            }
            case "logs" -> {
                String group = parsed.resource().replaceFirst("^log-group:", "").replaceFirst(":\\*$", "");
                if (!logs.get().logGroupExists(group, parsed.region())) {
                    throw notFound("destination", arn);
                }
                return "logs";
            }
            case "firehose" -> {
                try {
                    firehose.get().describeDeliveryStream(parsed.accountId(), parsed.region(),
                            parsed.resource().replaceFirst("^deliverystream/", ""));
                } catch (AwsException e) {
                    throw notFound("destination", arn);
                }
                return "firehose";
            }
            default -> throw validation("An access log destination is an S3 bucket, a CloudWatch Logs group or a "
                    + "Firehose delivery stream.");
        }
    }

    // ---------- resource gateways and configurations ----------

    private ObjectNode createGateway(JsonNode in, String region) {
        Optional<ObjectNode> replay = replay(region, GATEWAY, in);
        if (replay.isPresent()) {
            return gatewayView(replay.get(), false);
        }
        String name = in.path("name").asText();
        checkUniqueName(region, GATEWAY, name, r -> true);
        String vpcId = in.path("vpcIdentifier").asText();
        requireVpc(region, vpcId);
        List<String> subnetIds = strings(in.path("subnetIds"));
        if (subnetIds.isEmpty()) {
            throw validation("A resource gateway needs at least one subnet.");
        }
        for (String subnetId : subnetIds) {
            Subnet subnet = ec2.get().findSubnetById(region, subnetId)
                    .orElseThrow(() -> validation("The subnet " + subnetId + " doesn't exist."));
            if (!vpcId.equals(subnet.getVpcId())) {
                throw validation("The subnet " + subnetId + " is not in " + vpcId + ".");
            }
        }
        List<String> groups = strings(in.path("securityGroupIds"));
        requireSecurityGroups(region, vpcId, groups);
        ObjectNode gateway = create(region, GATEWAY, in);
        gateway.put("name", name).put("vpcIdentifier", vpcId)
                .put("ipAddressType", in.path("ipAddressType").asText("IPV4"))
                .put("resourceConfigDnsResolution", in.path("resourceConfigDnsResolution").asText("PUBLIC"));
        if (in.has("ipv4AddressesPerEni")) {
            gateway.put("ipv4AddressesPerEni", in.get("ipv4AddressesPerEni").asInt());
        } else if (!"IPV6".equals(gateway.path("ipAddressType").asText())) {
            gateway.put("ipv4AddressesPerEni", 16);
        }
        gateway.set("subnetIds", array(subnetIds));
        gateway.set("securityGroupIds", array(groups));
        transition(gateway, "CREATE_IN_PROGRESS", "ACTIVE");
        save(region, gateway);
        settle(region, gateway.path("id").asText());
        return gatewayView(gateway, false);
    }

    private ObjectNode gatewayView(ObjectNode gateway, boolean full) {
        ObjectNode view = view(gateway);
        if (full) {
            view.put("vpcId", view.path("vpcIdentifier").asText());
            view.remove("vpcIdentifier");
            view.put("serviceManaged", false);
        } else {
            view.remove("createdAt");
            view.remove("lastUpdatedAt");
        }
        return view;
    }

    private ObjectNode updateGateway(JsonNode in, String region) {
        ObjectNode gateway = find(region, GATEWAY, in.path("resourceGatewayIdentifier").asText());
        checkSettled(gateway, GATEWAY);
        if (in.path("securityGroupIds").isArray()) {
            List<String> groups = strings(in.get("securityGroupIds"));
            requireSecurityGroups(region, gateway.path("vpcIdentifier").asText(), groups);
            gateway.set("securityGroupIds", array(groups));
        }
        touch(gateway);
        save(region, gateway);
        ObjectNode view = gatewayView(gateway, true);
        for (String field : List.of("createdAt", "lastUpdatedAt", "serviceManaged", "ipv4AddressesPerEni",
                "resourceConfigDnsResolution")) {
            view.remove(field);
        }
        return view;
    }

    private ObjectNode deleteGateway(JsonNode in, String region) {
        ObjectNode gateway = find(region, GATEWAY, in.path("resourceGatewayIdentifier").asText());
        checkSettled(gateway, GATEWAY);
        String id = gateway.path("id").asText();
        if (count(region, CONFIGURATION, r -> id.equals(r.path("resourceGatewayId").asText())) > 0) {
            throw conflict(GATEWAY, id, "The resource gateway " + id + " has resource configurations.");
        }
        transition(gateway, "DELETE_IN_PROGRESS", "DELETED");
        save(region, gateway);
        settle(region, id);
        ObjectNode out = mapper.createObjectNode();
        copy(gateway, out, "id", "arn", "name", "status");
        return out;
    }

    private ObjectNode createConfiguration(JsonNode in, String region) {
        Optional<ObjectNode> replay = replay(region, CONFIGURATION, in);
        if (replay.isPresent()) {
            return configurationView(replay.get(), false);
        }
        String name = in.path("name").asText();
        String type = in.path("type").asText();
        checkUniqueName(region, CONFIGURATION, name, r -> true);
        ObjectNode configuration = create(region, CONFIGURATION, in);
        configuration.put("name", name).put("type", type);
        switch (type) {
            case "GROUP" -> {
                if (in.has("resourceConfigurationDefinition")) {
                    throw validation("A GROUP resource configuration has no definition.");
                }
                ObjectNode gateway = find(region, GATEWAY, in.path("resourceGatewayIdentifier").asText());
                configuration.put("resourceGatewayId", gateway.path("id").asText());
            }
            case "CHILD" -> {
                ObjectNode group = find(region, CONFIGURATION, in.path("resourceConfigurationGroupIdentifier").asText());
                if (!"GROUP".equals(group.path("type").asText())) {
                    throw validation("A CHILD resource configuration belongs to a GROUP.");
                }
                configuration.put("resourceConfigurationGroupId", group.path("id").asText())
                        .put("resourceGatewayId", group.path("resourceGatewayId").asText());
                definition(in, configuration);
            }
            case "SINGLE", "ARN" -> {
                ObjectNode gateway = find(region, GATEWAY, in.path("resourceGatewayIdentifier").asText());
                checkSettled(gateway, GATEWAY);
                configuration.put("resourceGatewayId", gateway.path("id").asText());
                definition(in, configuration);
                if ("ARN".equals(type) != in.path("resourceConfigurationDefinition").has("arnResource")) {
                    throw validation("An ARN resource configuration (and only one) has an arnResource.");
                }
            }
            default -> throw validation("Resource configurations of type " + type + " are not supported.");
        }
        configuration.set("portRanges", in.path("portRanges").isArray() ? in.get("portRanges").deepCopy()
                : mapper.createArrayNode().add("1-65535"));
        configuration.put("protocol", in.path("protocol").asText("TCP"))
                .put("allowAssociationToShareableServiceNetwork",
                        in.path("allowAssociationToShareableServiceNetwork").asBoolean(true))
                .put("amazonManaged", false);
        copy(in, configuration, "customDomainName", "groupDomain");
        if (in.hasNonNull("domainVerificationIdentifier")) {
            ObjectNode domain = find(region, DOMAIN, in.get("domainVerificationIdentifier").asText());
            configuration.put("domainVerificationId", domain.path("id").asText())
                    .put("domainVerificationArn", domain.path("arn").asText());
        }
        transition(configuration, "CREATE_IN_PROGRESS", "ACTIVE");
        save(region, configuration);
        settle(region, configuration.path("id").asText());
        return configurationView(configuration, false);
    }

    private void definition(JsonNode in, ObjectNode configuration) {
        JsonNode definition = in.path("resourceConfigurationDefinition");
        int kinds = 0;
        for (String field : List.of("dnsResource", "ipResource", "arnResource", "cidrResource")) {
            kinds += definition.has(field) ? 1 : 0;
        }
        if (kinds != 1) {
            throw validation("A resource configuration definition is one of dnsResource, ipResource, arnResource "
                    + "or cidrResource.");
        }
        configuration.set("resourceConfigurationDefinition", definition.deepCopy());
    }

    private ObjectNode configurationView(ObjectNode configuration, boolean full) {
        ObjectNode view = view(configuration);
        String domain = configuration.path("domainVerificationId").asText(null);
        if (full && domain != null) {
            String region = AwsArnUtils.parse(configuration.path("arn").asText()).region();
            resources.get(key(region, domain)).ifPresent(d -> view.put("domainVerificationStatus",
                    domainStatus(region, d)));
        }
        if (!full) {
            view.remove("lastUpdatedAt");
            view.remove("amazonManaged");
        }
        return view;
    }

    private ObjectNode updateConfiguration(JsonNode in, String region) {
        ObjectNode configuration = find(region, CONFIGURATION, in.path("resourceConfigurationIdentifier").asText());
        checkSettled(configuration, CONFIGURATION);
        if (in.has("resourceConfigurationDefinition")) {
            if ("GROUP".equals(configuration.path("type").asText())) {
                throw validation("A GROUP resource configuration has no definition.");
            }
            definition(in, configuration);
        }
        copy(in, configuration, "allowAssociationToShareableServiceNetwork", "portRanges");
        touch(configuration);
        save(region, configuration);
        ObjectNode view = view(configuration);
        for (String field : List.of("createdAt", "lastUpdatedAt", "amazonManaged", "customDomainName")) {
            view.remove(field);
        }
        return view;
    }

    private ObjectNode deleteConfiguration(JsonNode in, String region) {
        ObjectNode configuration = find(region, CONFIGURATION, in.path("resourceConfigurationIdentifier").asText());
        checkSettled(configuration, CONFIGURATION);
        String id = configuration.path("id").asText();
        if (count(region, RESOURCE_ASSOCIATION, r -> id.equals(r.path("resourceConfigurationId").asText())) > 0) {
            throw conflict(CONFIGURATION, id, "The resource configuration " + id + " is associated with a service "
                    + "network.");
        }
        if (count(region, CONFIGURATION, r -> id.equals(r.path("resourceConfigurationGroupId").asText())) > 0) {
            throw conflict(CONFIGURATION, id, "The resource configuration group " + id + " has child "
                    + "configurations.");
        }
        transition(configuration, "DELETE_IN_PROGRESS", "DELETED");
        save(region, configuration);
        settle(region, id);
        return mapper.createObjectNode();
    }

    private ObjectNode listConfigurations(JsonNode in, String region) {
        String gatewayId = in.hasNonNull("resourceGatewayIdentifier")
                ? find(region, GATEWAY, in.get("resourceGatewayIdentifier").asText()).path("id").asText() : null;
        String groupId = in.hasNonNull("resourceConfigurationGroupIdentifier")
                ? idOf(in.get("resourceConfigurationGroupIdentifier").asText()) : null;
        String domainId = in.hasNonNull("domainVerificationIdentifier")
                ? idOf(in.get("domainVerificationIdentifier").asText()) : null;
        return list(region, CONFIGURATION, r -> (gatewayId == null || gatewayId.equals(r.path("resourceGatewayId")
                        .asText())) && (groupId == null || groupId.equals(r.path("resourceConfigurationGroupId")
                        .asText())) && (domainId == null || domainId.equals(r.path("domainVerificationId").asText())),
                in, r -> configurationView(r, false));
    }

    // ---------- domain verification ----------

    private ObjectNode startDomainVerification(JsonNode in, String region) {
        Optional<ObjectNode> replay = replay(region, DOMAIN, in);
        if (replay.isPresent()) {
            return domainView(region, replay.get(), false);
        }
        String domain = in.path("domainName").asText().toLowerCase();
        for (ObjectNode existing : all(region, DOMAIN)) {
            if (domain.equals(existing.path("domainName").asText())) {
                throw conflict(DOMAIN, existing.path("id").asText(), "The domain " + domain + " is already being "
                        + "verified.");
            }
        }
        ObjectNode verification = create(region, DOMAIN, in);
        verification.put("domainName", domain);
        verification.putObject("txtMethodConfig").put("name", "_vpc-lattice-verification." + domain)
                .put("value", "vpc-lattice-verification=" + random(32, HEX));
        save(region, verification);
        return domainView(region, verification, false);
    }

    /** A domain is verified once a Route 53 hosted zone publishes its TXT record. */
    private String domainStatus(String region, ObjectNode verification) {
        if (verification.has("lastVerifiedTime")) {
            return "VERIFIED";
        }
        String name = verification.path("txtMethodConfig").path("name").asText();
        String value = verification.path("txtMethodConfig").path("value").asText();
        if (txtPublished(name, value)) {
            verification.put("lastVerifiedTime", Instant.now().toString());
            save(region, verification);
            return "VERIFIED";
        }
        return "PENDING";
    }

    private boolean txtPublished(String name, String value) {
        try {
            Route53Service dns = route53.get();
            return dns.listHostedZones(null, Integer.MAX_VALUE).stream().anyMatch(zone -> {
                try {
                    return dns.listResourceRecordSets(zone.getId(), null, null, Integer.MAX_VALUE).stream()
                            .filter(set -> "TXT".equalsIgnoreCase(set.getType()))
                            .filter(set -> name.equalsIgnoreCase(set.getName().replaceAll("\\.$", "")))
                            .anyMatch(set -> set.getRecords() != null && set.getRecords().stream()
                                    .anyMatch(record -> value.equals(record.getValue().replace("\"", ""))));
                } catch (AwsException e) {
                    return false;
                }
            });
        } catch (RuntimeException e) {
            LOG.debugv(e, "VPC Lattice could not read Route 53 for {0}", name);
            return false;
        }
    }

    private ObjectNode domainView(String region, ObjectNode verification, boolean full) {
        ObjectNode view = view(verification);
        view.put("status", domainStatus(region, verification));
        if (verification.has("lastVerifiedTime")) {
            view.put("lastVerifiedTime", verification.path("lastVerifiedTime").asText());
        }
        if (full) {
            view.set("tags", verification.path("_tags"));
        } else {
            view.remove("createdAt");
            view.remove("lastUpdatedAt");
        }
        view.remove("lastUpdatedAt");
        return view;
    }

    private ObjectNode deleteDomainVerification(JsonNode in, String region) {
        ObjectNode verification = find(region, DOMAIN, in.path("domainVerificationIdentifier").asText());
        String id = verification.path("id").asText();
        if (count(region, CONFIGURATION, r -> id.equals(r.path("domainVerificationId").asText())) > 0) {
            throw conflict(DOMAIN, id, "The domain verification " + id + " is used by a resource configuration.");
        }
        resources.delete(key(region, id));
        return mapper.createObjectNode();
    }

    // ---------- tags ----------

    private ObjectNode tagResource(JsonNode in, String region) {
        ObjectNode resource = byArn(region, in.path("resourceArn").asText());
        ObjectNode tags = (ObjectNode) resource.withObject("/_tags");
        in.path("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        if (tags.size() > 50) {
            throw validation("A resource can have at most 50 tags.");
        }
        save(region, resource);
        return mapper.createObjectNode();
    }

    private ObjectNode untagResource(JsonNode in, String region) {
        ObjectNode resource = byArn(region, in.path("resourceArn").asText());
        ObjectNode tags = (ObjectNode) resource.withObject("/_tags");
        strings(in.path("tagKeys")).forEach(tags::remove);
        save(region, resource);
        return mapper.createObjectNode();
    }

    // ---------- lifecycle ----------

    /** A new resource of {@code kind}: id, ARN, creation time, tags and its idempotency token. */
    private ObjectNode create(String region, String kind, JsonNode in) {
        String prefix = PREFIXES.get(kind);
        String id = prefix + random(17, RESOURCE_ASSOCIATION.equals(kind) || DOMAIN.equals(kind) ? HEX : ALNUM);
        Instant now = Instant.now();
        ObjectNode node = mapper.createObjectNode().put("_kind", kind).put("id", id)
                .put("arn", AwsArnUtils.Arn.of("vpc-lattice", region, resources.accountId(), kind + "/" + id)
                        .toString())
                .put("createdAt", now.toString()).put("lastUpdatedAt", now.toString());
        node.set("_tags", tags(in));
        if (in.hasNonNull("clientToken")) {
            node.put("_clientToken", in.get("clientToken").asText()).put("_request", requestHash(in));
        }
        return node;
    }

    /** A create retried with the same client token returns what the first one made; changed parameters conflict. */
    private Optional<ObjectNode> replay(String region, String kind, JsonNode in) {
        if (!in.hasNonNull("clientToken")) {
            return Optional.empty();
        }
        String token = in.get("clientToken").asText();
        for (ObjectNode existing : all(region, kind)) {
            if (token.equals(existing.path("_clientToken").asText())) {
                if (!requestHash(in).equals(existing.path("_request").asText())) {
                    throw conflict(kind, existing.path("id").asText(), "The client token " + token
                            + " was used with different parameters.");
                }
                return Optional.of(existing);
            }
        }
        return Optional.empty();
    }

    private String requestHash(JsonNode in) {
        ObjectNode copy = ((ObjectNode) in).deepCopy();
        copy.remove("clientToken");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(mapper.readTree(copy.toString()))));
        } catch (Exception e) {
            return copy.toString();
        }
    }

    private static void transition(ObjectNode node, String status, String target) {
        node.put("status", status).put("_settleTo", target);
    }

    /** Finishes a create or delete once the transition delay has passed. */
    private void settle(String region, String id) {
        String account = resources.accountId();
        executor.schedule(() -> RequestScopes.runAs(account, region, () -> {
            synchronized (this) {
                resources.get(key(region, id)).ifPresent(node -> {
                    String target = node.path("_settleTo").asText(null);
                    if ("DELETED".equals(target)) {
                        resources.delete(key(region, id));
                    } else if (target != null) {
                        node.put("status", target).remove("_settleTo");
                        save(region, node);
                    }
                });
            }
        }), config.services().vpcLattice().transitionMillis(), TimeUnit.MILLISECONDS);
    }

    private void checkSettled(ObjectNode node, String kind) {
        String status = node.path("status").asText("");
        if (status.endsWith("_IN_PROGRESS")) {
            throw conflict(kind, node.path("id").asText(), "The " + TYPE_NAMES.get(kind).toLowerCase()
                    .replace('_', ' ') + " " + node.path("id").asText() + " is " + status + ".");
        }
    }

    private void checkUniqueName(String region, String kind, String name, Predicate<ObjectNode> scope) {
        for (ObjectNode existing : all(region, kind)) {
            if (scope.test(existing) && name.equals(existing.path("name").asText())) {
                throw conflict(kind, existing.path("id").asText(), "A " + TYPE_NAMES.get(kind).toLowerCase()
                        .replace('_', ' ') + " named " + name + " already exists.");
            }
        }
    }

    private void deleteChildren(String region, String kind, Predicate<ObjectNode> filter) {
        for (ObjectNode child : all(region, kind)) {
            if (filter.test(child)) {
                resources.delete(key(region, child.path("id").asText()));
            }
        }
    }

    private static void touch(ObjectNode node) {
        node.put("lastUpdatedAt", Instant.now().toString());
    }

    // ---------- EC2 ----------

    private void requireVpc(String region, String vpcId) {
        try {
            if (!ec2.get().describeVpcs(region, List.of(vpcId), Map.of()).isEmpty()) {
                return;
            }
        } catch (AwsException e) {
            LOG.debugv("VPC {0} lookup failed: {1}", vpcId, e.getMessage());
        }
        throw notFound("vpc", vpcId);
    }

    private List<Subnet> subnets(String region, String vpcId) {
        return ec2.get().describeSubnets(region, List.of(), Map.of("vpc-id", List.of(vpcId)));
    }

    private void requireSecurityGroups(String region, String vpcId, List<String> groups) {
        if (groups.isEmpty()) {
            return;
        }
        List<SecurityGroup> found;
        try {
            found = ec2.get().describeSecurityGroups(region, groups, List.of(), Map.of());
        } catch (AwsException e) {
            throw validation("The security groups " + groups + " don't exist.");
        }
        for (String group : groups) {
            SecurityGroup match = found.stream().filter(g -> group.equals(g.getGroupId())).findFirst()
                    .orElseThrow(() -> validation("The security group " + group + " doesn't exist."));
            if (!vpcId.equals(match.getVpcId())) {
                throw validation("The security group " + group + " is not in " + vpcId + ".");
            }
        }
    }

    static boolean inCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            int bits = Integer.parseInt(parts[1]);
            long mask = bits == 0 ? 0 : 0xFFFFFFFFL << (32 - bits) & 0xFFFFFFFFL;
            return (ipv4(ip) & mask) == (ipv4(parts[0]) & mask);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long ipv4(String ip) {
        long value = 0;
        for (String part : ip.split("\\.")) {
            int octet = Integer.parseInt(part);
            if (octet < 0 || octet > 255) {
                throw new IllegalArgumentException(ip);
            }
            value = value << 8 | octet;
        }
        return value;
    }

    // ---------- storage helpers ----------

    ObjectNode find(String region, String kind, String identifier) {
        String id = idOf(identifier);
        ObjectNode node = resources.get(key(region, id)).filter(n -> kind.equals(n.path("_kind").asText()))
                .filter(n -> !"DELETED".equals(n.path("status").asText()))
                .orElseThrow(() -> notFound(kind, identifier));
        if (AwsArnUtils.looksLikeArn(identifier) && !identifier.equals(node.path("arn").asText())) {
            throw notFound(kind, identifier);
        }
        return node;
    }

    private ObjectNode byArn(String region, String arn) {
        for (ObjectNode node : resources.scan(k -> k.startsWith(region + "/"))) {
            if (arn.equals(node.path("arn").asText())) {
                return node;
            }
        }
        throw notFound("resource", arn);
    }

    List<ObjectNode> all(String region, String kind) {
        return resources.scan(k -> k.startsWith(region + "/")).stream()
                .filter(n -> kind.equals(n.path("_kind").asText())).toList();
    }

    private long count(String region, String kind, Predicate<ObjectNode> filter) {
        return all(region, kind).stream().filter(filter).count();
    }

    void save(String region, ObjectNode node) {
        resources.put(key(region, node.path("id").asText()), node);
    }

    String account() {
        return resources.accountId();
    }

    List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> allTargetGroups() {
        return resources.scanAllAccountEntries(k -> true).stream()
                .filter(e -> TARGET_GROUP.equals(e.value().path("_kind").asText())).toList();
    }

    private ObjectNode list(String region, String kind, Predicate<ObjectNode> filter, JsonNode in,
                            Function<ObjectNode, ObjectNode> render) {
        List<ObjectNode> matches = new ArrayList<>(all(region, kind).stream().filter(filter)
                .sorted(Comparator.comparing(n -> n.path("createdAt").asText())).toList());
        ArrayNode page = mapper.createArrayNode();
        String next = page(matches, in, n -> page.add(render.apply(n)));
        return items(page, next);
    }

    private ObjectNode items(ArrayNode page, String next) {
        ObjectNode out = mapper.createObjectNode();
        out.set("items", page);
        if (next != null) {
            out.put("nextToken", next);
        }
        return out;
    }

    private static <T> String page(List<T> items, JsonNode in, Consumer<T> emit) {
        int size = in.has("maxResults") ? in.get("maxResults").asInt() : 100;
        int start = 0;
        if (in.hasNonNull("nextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getDecoder().decode(in.get("nextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("The nextToken is not valid.");
            }
        }
        int end = Math.min(items.size(), start + size);
        for (int i = Math.min(start, items.size()); i < end; i++) {
            emit.accept(items.get(i));
        }
        return end < items.size()
                ? Base64.getEncoder().encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)) : null;
    }

    private ObjectNode view(ObjectNode node) {
        ObjectNode view = node.deepCopy();
        List<String> internal = new ArrayList<>();
        view.fieldNames().forEachRemaining(name -> {
            if (name.startsWith("_")) {
                internal.add(name);
            }
        });
        view.remove(internal);
        return view;
    }

    private ObjectNode tags(JsonNode in) {
        return in.path("tags").isObject() ? ((ObjectNode) in.get("tags")).deepCopy() : mapper.createObjectNode();
    }

    private ArrayNode array(List<String> values) {
        ArrayNode out = mapper.createArrayNode();
        values.forEach(out::add);
        return out;
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.has(field) && !from.get(field).isNull()) {
                to.set(field, from.get(field).deepCopy());
            }
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(v -> out.add(v.asText()));
        }
        return out;
    }

    /** An identifier's id: itself, or the last path segment of its ARN. */
    static String idOf(String identifier) {
        return identifier.contains("/") ? identifier.substring(identifier.lastIndexOf('/') + 1) : identifier;
    }

    private static String key(String region, String id) {
        return region + "/" + id;
    }

    /** The account's VPC Lattice DNS suffix: {@code <hash>.vpc-lattice-svcs.<region>.on.aws}. */
    private String suffix(String region) {
        return sha(resources.accountId()).substring(0, 7) + ".vpc-lattice-svcs." + region + ".on.aws";
    }

    private static String hostedZone(String region) {
        return "Z" + sha("vpc-lattice/" + region).substring(0, 20).toUpperCase();
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String random(int length, String alphabet) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length; i++) {
            out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String kind, String identifier) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resourceId", identifier);
        data.put("resourceType", TYPE_NAMES.getOrDefault(kind, kind.toUpperCase()));
        return new AwsException("ResourceNotFoundException", "The " + kind + " " + identifier + " was not found.",
                404, data);
    }

    private static AwsException conflict(String kind, String id, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resourceId", id);
        data.put("resourceType", TYPE_NAMES.getOrDefault(kind, kind.toUpperCase()));
        return new AwsException("ConflictException", message, 409, data);
    }
}
