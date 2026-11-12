package io.floci.aws.services.observabilityadmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.FlowLogService;
import io.floci.aws.services.ec2.model.FlowLog;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.Reservation;
import io.floci.aws.services.ec2.model.Tag;
import io.floci.aws.services.ec2.model.Vpc;
import io.floci.aws.services.eks.EksService;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.LogSetup;
import io.floci.aws.services.eks.model.Logging;
import io.floci.aws.services.eks.model.UpdateClusterConfigRequest;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.LambdaFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The resources telemetry config audits, read from Floci's own services, and the changes telemetry rules make to
 * them. EC2 instances report metrics (detailed monitoring), VPCs logs (flow logs), Lambda functions logs, metrics and
 * traces (X-Ray active tracing), and EKS clusters logs (control plane logging). A VPC flow-log rule creates flow logs
 * into {@code /aws/vpc}, tagged {@code CloudWatchTelemetryRuleManaged=true}; an instance metrics rule turns on
 * detailed monitoring; a Lambda traces rule turns on active tracing; an EKS logs rule turns on the log types it names.
 */
@ApplicationScoped
public class TelemetryResources {

    private static final Logger LOG = Logger.getLogger(TelemetryResources.class);
    static final String VPC_LOG_GROUP = "/aws/vpc";
    static final String MANAGED_TAG = "CloudWatchTelemetryRuleManaged";
    private static final Map<String, String> EKS_LOG_TYPES = Map.of("EKS_API_LOGS", "api", "EKS_AUDIT_LOGS",
            "audit", "EKS_AUTHENTICATOR_LOGS", "authenticator", "EKS_CONTROLLER_MANAGER_LOGS", "controllerManager",
            "EKS_SCHEDULER_LOGS", "scheduler");

    /** A resource telemetry config sees: its type, identifier, tags and the state of each telemetry type. */
    record Resource(String type, String id, Map<String, String> tags, Map<String, String> states, String sourceType) {
    }

    private final Provider<Ec2Service> ec2;
    private final Provider<FlowLogService> flowLogs;
    private final Provider<LambdaService> lambda;
    private final Provider<EksService> eks;
    private final Provider<CloudWatchLogsService> logs;
    private final ObjectMapper mapper;

    @Inject
    public TelemetryResources(Provider<Ec2Service> ec2, Provider<FlowLogService> flowLogs,
                              Provider<LambdaService> lambda, Provider<EksService> eks,
                              Provider<CloudWatchLogsService> logs, ObjectMapper mapper) {
        this.ec2 = ec2;
        this.flowLogs = flowLogs;
        this.lambda = lambda;
        this.eks = eks;
        this.logs = logs;
        this.mapper = mapper;
    }

    private static <T> List<T> quietly(Supplier<List<T>> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            LOG.debugv("Telemetry config couldn't read resources: {0}", e.getMessage());
            return List.of();
        }
    }

    private static Map<String, String> states(String logs, String metrics, String traces) {
        Map<String, String> states = new LinkedHashMap<>();
        states.put("Logs", logs);
        states.put("Metrics", metrics);
        states.put("Traces", traces);
        return states;
    }

    private static Map<String, String> ec2Tags(List<Tag> tags) {
        Map<String, String> map = new LinkedHashMap<>();
        if (tags != null) {
            tags.forEach(tag -> map.put(tag.getKey(), tag.getValue()));
        }
        return map;
    }

    /** The resources of an account in a Region. */
    List<Resource> resources(String account, String region) {
        return RequestScopes.callAs(account, region, () -> {
            List<Resource> resources = new ArrayList<>();
            for (Reservation reservation : quietly(() -> ec2.get().describeInstances(region, List.of(), Map.of()))) {
                for (Instance instance : reservation.getInstances()) {
                    resources.add(new Resource("AWS::EC2::Instance", instance.getInstanceId(),
                            ec2Tags(instance.getTags()), states("NotApplicable",
                            "enabled".equals(instance.getMonitoring()) ? "Enabled" : "Disabled", "NotApplicable"),
                            null));
                }
            }
            Set<String> logged = new LinkedHashSet<>();
            quietly(() -> flowLogs.get().describeFlowLogs(region, List.of())).forEach(flowLog ->
                    logged.add(flowLog.getResourceId()));
            for (Vpc vpc : quietly(() -> ec2.get().describeVpcs(region, List.of(), Map.of()))) {
                resources.add(new Resource("AWS::EC2::VPC", vpc.getVpcId(), ec2Tags(vpc.getTags()),
                        states(logged.contains(vpc.getVpcId()) ? "Enabled" : "Disabled", "NotApplicable",
                                "NotApplicable"), "VPC_FLOW_LOGS"));
            }
            for (LambdaFunction function : quietly(() -> lambda.get().listFunctions(region))) {
                resources.add(new Resource("AWS::Lambda::Function", function.getFunctionName(),
                        function.getTags() == null ? Map.of() : function.getTags(), states("Enabled", "Enabled",
                        "Active".equalsIgnoreCase(function.getTracingMode()) ? "Enabled" : "Disabled"), null));
            }
            for (String name : quietly(() -> eks.get().listClusters())) {
                try {
                    Cluster cluster = eks.get().describeCluster(name);
                    boolean on = cluster.getLogging() != null && cluster.getLogging().getClusterLogging() != null
                            && cluster.getLogging().getClusterLogging().stream().anyMatch(setup ->
                            Boolean.TRUE.equals(setup.getEnabled()) && setup.getTypes() != null
                                    && !setup.getTypes().isEmpty());
                    resources.add(new Resource("AWS::EKS::Cluster", name, cluster.getTags() == null ? Map.of()
                            : cluster.getTags(), states(on ? "Enabled" : "Disabled", "NotApplicable",
                            "NotApplicable"), null));
                } catch (AwsException e) {
                    LOG.debugv("Telemetry config couldn't read cluster {0}: {1}", name, e.getMessage());
                }
            }
            return resources;
        });
    }

    /** The filter attributes of a resource in an account, for a rule's selection criteria. */
    static Map<String, List<String>> attributes(Resource resource, String account) {
        Map<String, List<String>> attributes = new HashMap<>();
        attributes.put("ResourceId", List.of(resource.id()));
        attributes.put("ResourceType", List.of(resource.type()));
        attributes.put("AccountId", List.of(account));
        resource.tags().forEach((key, value) -> attributes.put("tag:" + key, List.of(value)));
        return attributes;
    }

    /** The resource as ListResourceTelemetry answers it. */
    ObjectNode view(Resource resource, String account, long updated) {
        ObjectNode view = mapper.createObjectNode();
        view.put("AccountIdentifier", account);
        ObjectNode states = view.putObject("TelemetryConfigurationState");
        resource.states().forEach(states::put);
        view.put("ResourceType", resource.type());
        view.put("ResourceIdentifier", resource.id());
        ObjectNode tags = view.putObject("ResourceTags");
        resource.tags().forEach(tags::put);
        view.put("LastUpdateTimeStamp", updated);
        if (resource.sourceType() != null) {
            view.put("TelemetrySourceType", resource.sourceType());
        }
        return view;
    }

    /** Applies a telemetry rule to the matching resources of an account in a Region; returns how many it changed. */
    int apply(JsonNode rule, String account, String region) {
        String type = rule.path("ResourceType").asText();
        String telemetry = rule.path("TelemetryType").asText();
        List<String> sources = new ArrayList<>();
        rule.path("TelemetrySourceTypes").forEach(source -> sources.add(source.asText()));
        String criteria = rule.path("SelectionCriteria").asText("");
        List<Resource> matching = new ArrayList<>();
        for (Resource resource : resources(account, region)) {
            if (resource.type().equals(type) && ObservabilityAdminFilter.matches(criteria,
                    attributes(resource, account))) {
                matching.add(resource);
            }
        }
        return RequestScopes.callAs(account, region, () -> {
            int changed = 0;
            JsonNode destination = rule.path("DestinationConfiguration");
            for (Resource resource : matching) {
                try {
                    if (type.equals("AWS::EC2::VPC") && telemetry.equals("Logs")
                            && (sources.isEmpty() || sources.contains("VPC_FLOW_LOGS"))
                            && "Disabled".equals(resource.states().get("Logs"))) {
                        flowLog(resource.id(), destination, account, region);
                        changed++;
                    } else if (type.equals("AWS::EC2::Instance") && telemetry.equals("Metrics")
                            && "Disabled".equals(resource.states().get("Metrics"))) {
                        ec2.get().setInstanceMonitoring(region, List.of(resource.id()), true);
                        changed++;
                    } else if (type.equals("AWS::Lambda::Function") && telemetry.equals("Traces")
                            && "Disabled".equals(resource.states().get("Traces"))) {
                        lambda.get().updateFunctionConfiguration(region, resource.id(),
                                Map.of("TracingConfig", Map.of("Mode", "Active")));
                        changed++;
                    } else if (type.equals("AWS::EKS::Cluster") && telemetry.equals("Logs")) {
                        List<String> types = new ArrayList<>();
                        sources.forEach(source -> {
                            if (EKS_LOG_TYPES.containsKey(source)) {
                                types.add(EKS_LOG_TYPES.get(source));
                            }
                        });
                        if (types.isEmpty()) {
                            types.addAll(EKS_LOG_TYPES.values());
                        }
                        if (eksLogging(resource.id(), types)) {
                            changed++;
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.debugv("Telemetry rule couldn't configure {0}: {1}", resource.id(), e.getMessage());
                }
            }
            return changed;
        });
    }

    private void flowLog(String vpcId, JsonNode destination, String account, String region) {
        Integer retention = destination.hasNonNull("RetentionInDays") ? destination.path("RetentionInDays").asInt()
                : null;
        if (!logs.get().logGroupExists(VPC_LOG_GROUP, region)) {
            try {
                logs.get().createLogGroupForAccount(account, VPC_LOG_GROUP, retention, Map.of(), region);
            } catch (AwsException e) {
                LOG.debugv("The flow-log group {0} couldn't be created: {1}", VPC_LOG_GROUP, e.getMessage());
            }
        } else if (retention != null) {
            logs.get().putRetentionPolicy(VPC_LOG_GROUP, retention, region);
        }
        JsonNode parameters = destination.path("VPCFlowLogParameters");
        Tag tag = new Tag();
        tag.setKey(MANAGED_TAG);
        tag.setValue("true");
        FlowLog created = flowLogs.get().createFlowLog(region, vpcId, "VPC",
                parameters.path("TrafficType").asText("ALL"), "cloud-watch-logs", null,
                parameters.path("LogFormat").asText(null), parameters.path("MaxAggregationInterval").asInt(600),
                VPC_LOG_GROUP, null, List.of(tag));
        ec2.get().createTags(region, List.of(created.getFlowLogId()), List.of(tag));
        LOG.infov("A telemetry rule turned on flow log {0} for {1}", created.getFlowLogId(), vpcId);
    }

    private boolean eksLogging(String name, List<String> types) {
        Cluster cluster = eks.get().describeCluster(name);
        Set<String> enabled = new LinkedHashSet<>();
        if (cluster.getLogging() != null && cluster.getLogging().getClusterLogging() != null) {
            for (LogSetup setup : cluster.getLogging().getClusterLogging()) {
                if (Boolean.TRUE.equals(setup.getEnabled()) && setup.getTypes() != null) {
                    enabled.addAll(setup.getTypes());
                }
            }
        }
        if (enabled.containsAll(types)) {
            return false;
        }
        eks.get().updateClusterConfig(name, new UpdateClusterConfigRequest(null,
                new Logging(List.of(new LogSetup(types, true))), null, null, null, null, null, null, null, null,
                null));
        return true;
    }
}
