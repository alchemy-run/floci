package io.floci.aws.services.apprunner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.route53.Route53Service;
import io.floci.aws.services.route53.model.HostedZone;
import io.floci.aws.services.route53.model.ResourceRecordSet;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AWS App Runner. Services run their image in a container on Floci ({@link AppRunnerRuntime}),
 * reached at {@code https://<id>.<region>.awsapprunner.com} and their active custom domains.
 * Creating, updating, redeploying, pausing, resuming and deleting a service are operations that run
 * in the background, as on AWS, and the service is {@code OPERATION_IN_PROGRESS} until they finish.
 * Auto scaling and observability configurations are revisioned by name, and every account has
 * their {@code DefaultConfiguration}. VPC connectors, VPC ingress connections, source connections and
 * custom domains (validated through Floci's Route 53) are kept with their states.
 */
@ApplicationScoped
public class AppRunnerService implements Resettable {
    private static final Logger LOG = Logger.getLogger(AppRunnerService.class);
    private static final String DEFAULT = "DefaultConfiguration";

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/apprunner-request-shapes.json", "App Runner request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return invalid(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    return invalid(path + " is outside the allowed bounds" + (min == null ? "" : " (min " + min
                            + ")") + (max == null ? "" : " (max " + max + ")") + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return invalid(path + " does not match the pattern " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return invalid(path + " must be one of " + values + ".");
                }

                @Override
                public AwsException required(String path) {
                    return invalid(path + " is required.");
                }
            });

    private final AccountAwareStorageBackend<ObjectNode> services;
    private final AccountAwareStorageBackend<ObjectNode> configurations;
    private final AccountAwareStorageBackend<ObjectNode> connectors;
    private final AccountAwareStorageBackend<ObjectNode> ingress;
    private final AccountAwareStorageBackend<ObjectNode> connections;
    private final AccountAwareStorageBackend<ObjectNode> operations;
    private final AppRunnerRuntime runtime;
    private final Instance<Route53Service> route53;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService worker = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "apprunner-operations");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public AppRunnerService(StorageFactory storageFactory, AppRunnerRuntime runtime, Instance<Route53Service> route53,
                            RegionResolver regionResolver, ObjectMapper mapper) {
        TypeReference<Map<String, ObjectNode>> type = new TypeReference<>() {};
        this.services = storageFactory.create("apprunner", "apprunner-services.json", type);
        this.configurations = storageFactory.create("apprunner", "apprunner-configurations.json", type);
        this.connectors = storageFactory.create("apprunner", "apprunner-vpc-connectors.json", type);
        this.ingress = storageFactory.create("apprunner", "apprunner-vpc-ingress.json", type);
        this.connections = storageFactory.create("apprunner", "apprunner-connections.json", type);
        this.operations = storageFactory.create("apprunner", "apprunner-operations.json", type);
        this.runtime = runtime;
        this.route53 = route53;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    static void check(String operation, JsonNode request) {
        SHAPES.check(operation, request);
    }

    // ── Services ───────────────────────────────────────────────────────────

    public synchronized ObjectNode createService(JsonNode in, String region) {
        check("CreateService", in);
        String name = in.path("ServiceName").asText();
        boolean taken = services.scan(k -> k.startsWith(region + "::")).stream()
                .anyMatch(s -> name.equals(s.path("ServiceName").asText()) && !"DELETED".equals(s.path("Status")
                        .asText()));
        if (taken) {
            throw invalid("Service with the provided name already exists: " + name);
        }
        JsonNode source = in.path("SourceConfiguration");
        checkSource(source);
        String id = HexFormat.of().formatHex(randomBytes(16));
        String arn = arn(region, "service/" + name + "/" + id);
        double now = now();
        ObjectNode service = mapper.createObjectNode();
        service.put("ServiceName", name);
        service.put("ServiceId", id);
        service.put("ServiceArn", arn);
        service.put("ServiceUrl", randomLabel(10) + "." + region + ".awsapprunner.com");
        service.put("CreatedAt", now);
        service.put("UpdatedAt", now);
        service.put("Status", "OPERATION_IN_PROGRESS");
        service.set("SourceConfiguration", normalizeSource(source));
        service.set("InstanceConfiguration", instance(in.path("InstanceConfiguration"), null));
        service.set("HealthCheckConfiguration", healthCheck(in.path("HealthCheckConfiguration"), null));
        service.set("AutoScalingConfigurationSummary", scalingSummary(in.path("AutoScalingConfigurationArn")
                .asText(null), region));
        service.set("NetworkConfiguration", network(in.path("NetworkConfiguration"), null, region));
        service.set("ObservabilityConfiguration", observability(in.path("ObservabilityConfiguration"), region));
        if (in.path("EncryptionConfiguration").isObject()) {
            service.set("EncryptionConfiguration", in.get("EncryptionConfiguration").deepCopy());
        }
        service.set("Tags", tagMap(in.path("Tags")));
        services.put(key(region, id), service);
        String operationId = startOperation(region, service, "CREATE_SERVICE", () -> {
            runtime.serviceLog(regionResolver.getAccountId(), region, name, id, "Service creation started.");
            runtime.deploy(regionResolver.getAccountId(), region, service);
        }, "RUNNING", "CREATE_FAILED");
        ObjectNode out = mapper.createObjectNode();
        out.set("Service", view(service));
        out.put("OperationId", operationId);
        return out;
    }

    private void checkSource(JsonNode source) {
        JsonNode image = source.path("ImageRepository");
        JsonNode code = source.path("CodeRepository");
        if (image.isObject() == code.isObject()) {
            throw invalid("SourceConfiguration needs exactly one of ImageRepository and CodeRepository.");
        }
        if (image.isObject() && "ECR".equals(image.path("ImageRepositoryType").asText())
                && source.path("AuthenticationConfiguration").path("AccessRoleArn").asText("").isEmpty()) {
            throw invalid("An ECR image repository needs AuthenticationConfiguration.AccessRoleArn.");
        }
        if (code.isObject() && source.path("AuthenticationConfiguration").path("ConnectionArn").asText("")
                .isEmpty()) {
            throw invalid("A code repository needs AuthenticationConfiguration.ConnectionArn.");
        }
    }

    private ObjectNode normalizeSource(JsonNode source) {
        ObjectNode copy = source.deepCopy();
        if (!copy.has("AutoDeploymentsEnabled")) {
            copy.put("AutoDeploymentsEnabled", copy.path("ImageRepository").path("ImageRepositoryType").asText("")
                    .equals("ECR"));
        }
        if (copy.path("ImageRepository").isObject()) {
            ObjectNode image = ((ObjectNode) copy.get("ImageRepository")).withObject("ImageConfiguration");
            if (!image.has("Port")) {
                image.put("Port", "8080");
            }
        }
        return copy;
    }

    private ObjectNode instance(JsonNode requested, JsonNode current) {
        ObjectNode out = current == null ? mapper.createObjectNode() : current.deepCopy();
        out.put("Cpu", cpu(requested.path("Cpu").asText(out.path("Cpu").asText("1024"))));
        out.put("Memory", memory(requested.path("Memory").asText(out.path("Memory").asText("2048"))));
        if (requested.has("InstanceRoleArn")) {
            out.put("InstanceRoleArn", requested.get("InstanceRoleArn").asText());
        }
        return out;
    }

    /** App Runner reports CPU in units ({@code 1024}), whichever form ({@code 1 vCPU}) it was given in. */
    private static String cpu(String value) {
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.endsWith("vcpu")) {
            return Long.toString(Math.round(Double.parseDouble(text.replace("vcpu", "").trim()) * 1024));
        }
        return text;
    }

    private static String memory(String value) {
        String text = value.trim().toUpperCase(Locale.ROOT);
        if (text.endsWith("GB")) {
            return Long.toString(Math.round(Double.parseDouble(text.replace("GB", "").trim()) * 1024));
        }
        return text.replace("MB", "").trim();
    }

    private ObjectNode healthCheck(JsonNode requested, JsonNode current) {
        ObjectNode out = current == null ? mapper.createObjectNode() : current.deepCopy();
        out.put("Protocol", requested.path("Protocol").asText(out.path("Protocol").asText("TCP")));
        out.put("Path", requested.path("Path").asText(out.path("Path").asText("/")));
        out.put("Interval", requested.path("Interval").asInt(out.path("Interval").asInt(5)));
        out.put("Timeout", requested.path("Timeout").asInt(out.path("Timeout").asInt(2)));
        out.put("HealthyThreshold", requested.path("HealthyThreshold").asInt(out.path("HealthyThreshold").asInt(1)));
        out.put("UnhealthyThreshold", requested.path("UnhealthyThreshold").asInt(out.path("UnhealthyThreshold")
                .asInt(5)));
        return out;
    }

    private ObjectNode network(JsonNode requested, JsonNode current, String region) {
        ObjectNode out = current == null ? mapper.createObjectNode() : current.deepCopy();
        ObjectNode egress = out.withObject("EgressConfiguration");
        JsonNode requestedEgress = requested.path("EgressConfiguration");
        egress.put("EgressType", requestedEgress.path("EgressType").asText(egress.path("EgressType").asText("DEFAULT")));
        if ("VPC".equals(egress.path("EgressType").asText())) {
            String connector = requestedEgress.path("VpcConnectorArn").asText(egress.path("VpcConnectorArn")
                    .asText(""));
            ObjectNode found = configurationByArn(connectors, connector, region);
            if (found == null || !"ACTIVE".equals(found.path("Status").asText())) {
                throw invalid("VPC egress needs an ACTIVE VPC connector; " + connector + " isn't one.");
            }
            egress.put("VpcConnectorArn", connector);
        } else {
            egress.remove("VpcConnectorArn");
        }
        out.withObject("IngressConfiguration").put("IsPubliclyAccessible", requested.path("IngressConfiguration")
                .path("IsPubliclyAccessible").asBoolean(out.path("IngressConfiguration").path("IsPubliclyAccessible")
                        .asBoolean(true)));
        out.put("IpAddressType", requested.path("IpAddressType").asText(out.path("IpAddressType").asText("IPV4")));
        return out;
    }

    private ObjectNode observability(JsonNode requested, String region) {
        ObjectNode out = mapper.createObjectNode();
        boolean enabled = requested.path("ObservabilityEnabled").asBoolean(false);
        out.put("ObservabilityEnabled", enabled);
        if (enabled) {
            String arn = requested.path("ObservabilityConfigurationArn").asText("");
            ObjectNode config = arn.isEmpty() ? defaultObservability(region)
                    : configurationByArn(configurations, arn, region);
            if (config == null || !"ACTIVE".equals(config.path("Status").asText())) {
                throw invalid("The observability configuration " + arn + " isn't ACTIVE.");
            }
            out.put("ObservabilityConfigurationArn", config.path("ObservabilityConfigurationArn").asText());
        }
        return out;
    }

    private ObjectNode scalingSummary(String arn, String region) {
        ObjectNode config = arn == null || arn.isEmpty() ? defaultScaling(region)
                : configurationByArn(configurations, arn, region);
        if (config == null || !"ACTIVE".equals(config.path("Status").asText())) {
            throw invalid("The auto scaling configuration " + arn + " isn't ACTIVE.");
        }
        ObjectNode summary = mapper.createObjectNode();
        for (String field : List.of("AutoScalingConfigurationArn", "AutoScalingConfigurationName",
                "AutoScalingConfigurationRevision", "Status", "CreatedAt", "IsDefault")) {
            summary.set(field, config.path(field).deepCopy());
        }
        summary.put("HasAssociatedService", true);
        return summary;
    }

    public synchronized ObjectNode updateService(JsonNode in, String region) {
        check("UpdateService", in);
        ObjectNode service = requireService(in.path("ServiceArn").asText(), region);
        requireSettled(service, Set.of("RUNNING", "CREATE_FAILED"));
        ObjectNode before = service.deepCopy();
        if (in.path("SourceConfiguration").isObject()) {
            checkSource(in.get("SourceConfiguration"));
            service.set("SourceConfiguration", normalizeSource(in.get("SourceConfiguration")));
        }
        if (in.path("InstanceConfiguration").isObject()) {
            service.set("InstanceConfiguration", instance(in.get("InstanceConfiguration"),
                    service.get("InstanceConfiguration")));
        }
        if (in.path("HealthCheckConfiguration").isObject()) {
            service.set("HealthCheckConfiguration", healthCheck(in.get("HealthCheckConfiguration"),
                    service.get("HealthCheckConfiguration")));
        }
        if (in.hasNonNull("AutoScalingConfigurationArn")) {
            service.set("AutoScalingConfigurationSummary", scalingSummary(in.get("AutoScalingConfigurationArn")
                    .asText(), region));
        }
        if (in.path("NetworkConfiguration").isObject()) {
            service.set("NetworkConfiguration", network(in.get("NetworkConfiguration"),
                    service.get("NetworkConfiguration"), region));
        }
        if (in.path("ObservabilityConfiguration").isObject()) {
            service.set("ObservabilityConfiguration", observability(in.get("ObservabilityConfiguration"), region));
        }
        service.put("UpdatedAt", now());
        String wasStatus = service.path("Status").asText();
        service.put("Status", "OPERATION_IN_PROGRESS");
        services.put(key(region, service.path("ServiceId").asText()), service);
        String account = regionResolver.getAccountId();
        String operationId = startOperation(region, service, "UPDATE_SERVICE", () -> {
            try {
                runtime.deploy(account, region, service);
            } catch (AwsException e) {
                // The update rolls back to the configuration that last ran.
                if ("RUNNING".equals(wasStatus)) {
                    services.put(key(region, service.path("ServiceId").asText()), restore(before));
                    throw new Rollback(e.getMessage());
                }
                throw e;
            }
        }, "RUNNING", "RUNNING".equals(wasStatus) ? "RUNNING" : "CREATE_FAILED");
        ObjectNode out = mapper.createObjectNode();
        out.set("Service", view(service));
        out.put("OperationId", operationId);
        return out;
    }

    private ObjectNode restore(ObjectNode before) {
        ObjectNode copy = before.deepCopy();
        copy.put("Status", "RUNNING");
        return copy;
    }

    /** A failed update that rolled back to the configuration that last ran. */
    private static final class Rollback extends RuntimeException {
        Rollback(String message) {
            super(message);
        }
    }

    public synchronized ObjectNode startDeployment(JsonNode in, String region) {
        check("StartDeployment", in);
        ObjectNode service = requireService(in.path("ServiceArn").asText(), region);
        requireSettled(service, Set.of("RUNNING"));
        service.put("Status", "OPERATION_IN_PROGRESS");
        services.put(key(region, service.path("ServiceId").asText()), service);
        String account = regionResolver.getAccountId();
        String operationId = startOperation(region, service, "START_DEPLOYMENT", () -> {
            runtime.serviceLog(account, region, service.path("ServiceName").asText(),
                    service.path("ServiceId").asText(), "Deployment with ID : " + UUID.randomUUID() + " started.");
            runtime.deploy(account, region, service);
        }, "RUNNING", "RUNNING");
        return mapper.createObjectNode().put("OperationId", operationId);
    }

    public synchronized ObjectNode pauseService(JsonNode in, String region) {
        check("PauseService", in);
        ObjectNode service = requireService(in.path("ServiceArn").asText(), region);
        requireSettled(service, Set.of("RUNNING"));
        service.put("Status", "OPERATION_IN_PROGRESS");
        services.put(key(region, service.path("ServiceId").asText()), service);
        String operationId = startOperation(region, service, "PAUSE_SERVICE",
                () -> runtime.stop(service.path("ServiceArn").asText()), "PAUSED", "RUNNING");
        ObjectNode out = mapper.createObjectNode();
        out.set("Service", view(service));
        out.put("OperationId", operationId);
        return out;
    }

    public synchronized ObjectNode resumeService(JsonNode in, String region) {
        check("ResumeService", in);
        ObjectNode service = requireService(in.path("ServiceArn").asText(), region);
        requireSettled(service, Set.of("PAUSED"));
        service.put("Status", "OPERATION_IN_PROGRESS");
        services.put(key(region, service.path("ServiceId").asText()), service);
        String account = regionResolver.getAccountId();
        String operationId = startOperation(region, service, "RESUME_SERVICE",
                () -> runtime.deploy(account, region, service), "RUNNING", "PAUSED");
        ObjectNode out = mapper.createObjectNode();
        out.set("Service", view(service));
        out.put("OperationId", operationId);
        return out;
    }

    public synchronized ObjectNode deleteService(JsonNode in, String region) {
        check("DeleteService", in);
        ObjectNode service = requireService(in.path("ServiceArn").asText(), region);
        requireSettled(service, Set.of("RUNNING", "PAUSED", "CREATE_FAILED", "DELETE_FAILED"));
        service.put("Status", "OPERATION_IN_PROGRESS");
        services.put(key(region, service.path("ServiceId").asText()), service);
        String operationId = startOperation(region, service, "DELETE_SERVICE",
                () -> runtime.stop(service.path("ServiceArn").asText()), "DELETED", "DELETE_FAILED");
        ObjectNode out = mapper.createObjectNode();
        out.set("Service", view(service));
        out.put("OperationId", operationId);
        return out;
    }

    public ObjectNode describeService(JsonNode in, String region) {
        check("DescribeService", in);
        ObjectNode out = mapper.createObjectNode();
        out.set("Service", view(requireService(in.path("ServiceArn").asText(), region)));
        return out;
    }

    public ObjectNode listServices(JsonNode in, String region) {
        check("ListServices", in);
        List<ObjectNode> all = services.scan(k -> k.startsWith(region + "::")).stream()
                .filter(s -> !"DELETED".equals(s.path("Status").asText()))
                .sorted(Comparator.comparingDouble(s -> s.path("CreatedAt").asDouble()))
                .map(this::summary).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("ServiceSummaryList");
        page(all, in, out, 20).forEach(list::add);
        return out;
    }

    private ObjectNode summary(ObjectNode service) {
        ObjectNode out = mapper.createObjectNode();
        for (String field : List.of("ServiceName", "ServiceId", "ServiceArn", "ServiceUrl", "CreatedAt", "UpdatedAt",
                "Status")) {
            out.set(field, service.path(field).deepCopy());
        }
        return out;
    }

    private ObjectNode view(ObjectNode service) {
        ObjectNode copy = service.deepCopy();
        copy.remove("Tags");
        return copy;
    }

    ObjectNode requireService(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "apprunner")) {
            throw invalid("ServiceArn must be an App Runner service ARN.");
        }
        String[] parts = AwsArnUtils.parse(arn).resource().split("/");
        if (parts.length != 3 || !"service".equals(parts[0])) {
            throw invalid("ServiceArn must be an App Runner service ARN.");
        }
        return services.get(key(region, parts[2])).filter(s -> s.path("ServiceArn").asText().equals(arn))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Service " + arn + " not found.",
                        400));
    }

    private static void requireSettled(ObjectNode service, Set<String> allowed) {
        String status = service.path("Status").asText();
        if (!allowed.contains(status)) {
            throw new AwsException("InvalidStateException", "The service is " + status + "; this operation needs it "
                    + "to be " + String.join(" or ", allowed) + ".", 400);
        }
    }

    /** Records an operation and runs it in the background, then settles the service's status. */
    private String startOperation(String region, ObjectNode service, String type, Runnable work, String success,
                                  String failure) {
        String id = UUID.randomUUID().toString().replace("-", "");
        String serviceArn = service.path("ServiceArn").asText();
        String serviceKey = key(region, service.path("ServiceId").asText());
        ObjectNode operation = mapper.createObjectNode();
        operation.put("Id", id);
        operation.put("Type", type);
        operation.put("Status", "IN_PROGRESS");
        operation.put("TargetArn", serviceArn);
        operation.put("StartedAt", now());
        operation.put("UpdatedAt", now());
        operations.put(key(region, id), operation);
        String account = regionResolver.getAccountId();
        String name = service.path("ServiceName").asText();
        String serviceId = service.path("ServiceId").asText();
        worker.submit(() -> RequestScopes.runAs(account, region, () -> {
            String status = "SUCCEEDED";
            String settled = success;
            try {
                work.run();
            } catch (Rollback e) {
                status = "ROLLBACK_SUCCEEDED";
                settled = failure;
                runtime.serviceLog(account, region, name, serviceId, "Deployment failed: " + e.getMessage()
                        + " Rolling back to the previous deployment.");
            } catch (RuntimeException e) {
                status = "FAILED";
                settled = failure;
                runtime.serviceLog(account, region, name, serviceId, type + " failed: " + e.getMessage());
                LOG.debugv("App Runner {0} of {1} failed: {2}", type, serviceArn, e.getMessage());
            }
            synchronized (AppRunnerService.this) {
                ObjectNode current = services.get(serviceKey).orElse(null);
                if (current != null) {
                    current.put("Status", settled);
                    current.put("UpdatedAt", now());
                    if ("DELETED".equals(settled)) {
                        current.put("DeletedAt", now());
                    }
                    services.put(serviceKey, current);
                }
                operation.put("Status", status);
                operation.put("EndedAt", now());
                operation.put("UpdatedAt", now());
                operations.put(key(region, id), operation);
            }
            if ("SUCCEEDED".equals(status) && "RUNNING".equals(settled)) {
                runtime.serviceLog(account, region, name, serviceId, "Successfully routed incoming traffic to "
                        + "application.");
            }
        }));
        return id;
    }

    public ObjectNode listOperations(JsonNode in, String region) {
        check("ListOperations", in);
        String serviceArn = in.path("ServiceArn").asText();
        requireService(serviceArn, region);
        List<ObjectNode> all = operations.scan(k -> k.startsWith(region + "::")).stream()
                .filter(o -> serviceArn.equals(o.path("TargetArn").asText()))
                .sorted(Comparator.comparingDouble((ObjectNode o) -> o.path("StartedAt").asDouble()).reversed()).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("OperationSummaryList");
        page(all, in, out, 20).forEach(list::add);
        return out;
    }

    /** The running service a host names (its service URL or an active custom domain), starting its container if needed. */
    Optional<AppRunnerRuntime.Running> target(String host) {
        String name = host.toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "");
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : services.scanAllAccountEntries(k -> true)) {
            ObjectNode service = entry.value();
            boolean matches = name.equals(service.path("ServiceUrl").asText());
            if (!matches) {
                for (JsonNode domain : service.withArray("CustomDomains")) {
                    String domainName = domain.path("DomainName").asText();
                    matches |= "ACTIVE".equals(domain.path("Status").asText()) && (name.equals(domainName)
                            || (domain.path("EnableWWWSubdomain").asBoolean(false) && name.equals("www." + domainName)));
                }
            }
            if (!matches) {
                continue;
            }
            if (!"RUNNING".equals(service.path("Status").asText())) {
                return Optional.empty();
            }
            AppRunnerRuntime.Running running = runtime.running(service.path("ServiceArn").asText());
            if (running == null) {
                String region = AwsArnUtils.parse(service.path("ServiceArn").asText()).region();
                RequestScopes.runAs(entry.accountId(), region, () -> runtime.deploy(entry.accountId(), region,
                        service));
                running = runtime.running(service.path("ServiceArn").asText());
            }
            return Optional.ofNullable(running);
        }
        return Optional.empty();
    }

    // ── Auto scaling and observability configurations ──────────────────────

    public synchronized ObjectNode createAutoScaling(JsonNode in, String region) {
        check("CreateAutoScalingConfiguration", in);
        String name = in.path("AutoScalingConfigurationName").asText();
        if (DEFAULT.equals(name)) {
            throw invalid("DefaultConfiguration is reserved.");
        }
        int maxConcurrency = in.path("MaxConcurrency").asInt(100);
        int minSize = in.path("MinSize").asInt(1);
        int maxSize = in.path("MaxSize").asInt(25);
        if (minSize > maxSize) {
            throw invalid("MinSize can't be greater than MaxSize.");
        }
        defaultScaling(region);
        ObjectNode config = newRevision(region, "autoscalingconfiguration", "AutoScalingConfiguration", name);
        config.put("MaxConcurrency", maxConcurrency);
        config.put("MinSize", minSize);
        config.put("MaxSize", maxSize);
        config.put("IsDefault", false);
        config.put("HasAssociatedService", false);
        config.set("Tags", tagMap(in.path("Tags")));
        saveConfiguration(region, config, "AutoScalingConfigurationArn");
        ObjectNode out = mapper.createObjectNode();
        out.set("AutoScalingConfiguration", scalingView(config, region));
        return out;
    }

    /** A new revision of a named configuration, the latest of its name. */
    private ObjectNode newRevision(String region, String arnType, String prefix, String name) {
        List<ObjectNode> revisions = configurations.scan(k -> k.startsWith(region + "::" + arnType + "/" + name + "/"));
        int revision = revisions.stream().mapToInt(c -> c.path(prefix + "Revision").asInt()).max().orElse(0) + 1;
        if (revisions.stream().filter(c -> "ACTIVE".equals(c.path("Status").asText())).count() >= 5) {
            throw new AwsException("ServiceQuotaExceededException", "A configuration name can have at most 5 "
                    + "active revisions.", 400);
        }
        for (ObjectNode previous : revisions) {
            previous.put("Latest", false);
            configurations.put(configKey(region, previous.path(prefix + "Arn").asText()), previous);
        }
        ObjectNode config = mapper.createObjectNode();
        String arn = arn(region, arnType + "/" + name + "/" + revision + "/" + HexFormat.of().formatHex(randomBytes(16)));
        config.put(prefix + "Arn", arn);
        config.put(prefix + "Name", name);
        config.put(prefix + "Revision", revision);
        config.put("Latest", true);
        config.put("Status", "ACTIVE");
        config.put("CreatedAt", now());
        return config;
    }

    private void saveConfiguration(String region, ObjectNode config, String arnField) {
        configurations.put(configKey(region, config.path(arnField).asText()), config);
    }

    private static String configKey(String region, String arn) {
        return region + "::" + AwsArnUtils.parse(arn).resource();
    }

    private ObjectNode configurationByArn(AccountAwareStorageBackend<ObjectNode> store, String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "apprunner")) {
            return null;
        }
        String[] parts = AwsArnUtils.parse(arn).resource().split("/");
        if (parts.length < 2) {
            return null;
        }
        if (parts.length == 2) {
            // name only: the latest active revision
            return store.scan(k -> k.startsWith(region + "::" + parts[0] + "/" + parts[1] + "/")).stream()
                    .filter(c -> c.path("Latest").asBoolean()).findFirst().orElse(null);
        }
        if (parts.length == 3) {
            return store.scan(k -> k.startsWith(region + "::" + parts[0] + "/" + parts[1] + "/" + parts[2] + "/"))
                    .stream().findFirst().orElse(null);
        }
        return store.get(configKey(region, arn)).orElse(null);
    }

    private ObjectNode defaultScaling(String region) {
        String prefix = region + "::autoscalingconfiguration/" + DEFAULT + "/";
        List<ObjectNode> found = configurations.scan(k -> k.startsWith(prefix));
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        ObjectNode config = newRevision(region, "autoscalingconfiguration", "AutoScalingConfiguration", DEFAULT);
        config.put("MaxConcurrency", 100);
        config.put("MinSize", 1);
        config.put("MaxSize", 25);
        config.put("IsDefault", true);
        config.set("Tags", mapper.createObjectNode());
        saveConfiguration(region, config, "AutoScalingConfigurationArn");
        return config;
    }

    private ObjectNode defaultObservability(String region) {
        String prefix = region + "::observabilityconfiguration/" + DEFAULT + "/";
        List<ObjectNode> found = configurations.scan(k -> k.startsWith(prefix));
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        ObjectNode config = newRevision(region, "observabilityconfiguration", "ObservabilityConfiguration", DEFAULT);
        config.putObject("TraceConfiguration").put("Vendor", "AWSXRAY");
        config.set("Tags", mapper.createObjectNode());
        saveConfiguration(region, config, "ObservabilityConfigurationArn");
        return config;
    }

    private ObjectNode scalingView(ObjectNode config, String region) {
        ObjectNode copy = config.deepCopy();
        copy.remove("Tags");
        String arn = config.path("AutoScalingConfigurationArn").asText();
        copy.put("HasAssociatedService", services.scan(k -> k.startsWith(region + "::")).stream()
                .filter(s -> !"DELETED".equals(s.path("Status").asText()))
                .anyMatch(s -> arn.equals(s.path("AutoScalingConfigurationSummary")
                        .path("AutoScalingConfigurationArn").asText())));
        return copy;
    }

    public ObjectNode describeAutoScaling(JsonNode in, String region) {
        check("DescribeAutoScalingConfiguration", in);
        defaultScaling(region);
        ObjectNode config = configurationByArn(configurations, in.path("AutoScalingConfigurationArn").asText(), region);
        if (config == null) {
            throw new AwsException("ResourceNotFoundException", "Auto scaling configuration "
                    + in.path("AutoScalingConfigurationArn").asText() + " not found.", 400);
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("AutoScalingConfiguration", scalingView(config, region));
        return out;
    }

    public synchronized ObjectNode deleteAutoScaling(JsonNode in, String region) {
        check("DeleteAutoScalingConfiguration", in);
        ObjectNode config = configurationByArn(configurations, in.path("AutoScalingConfigurationArn").asText(), region);
        if (config == null || !"ACTIVE".equals(config.path("Status").asText())) {
            throw new AwsException("ResourceNotFoundException", "Auto scaling configuration "
                    + in.path("AutoScalingConfigurationArn").asText() + " not found.", 400);
        }
        List<ObjectNode> targets = in.path("DeleteAllRevisions").asBoolean(false)
                ? configurations.scan(k -> k.startsWith(region + "::autoscalingconfiguration/"
                        + config.path("AutoScalingConfigurationName").asText() + "/"))
                : List.of(config);
        for (ObjectNode target : targets) {
            if (scalingView(target, region).path("HasAssociatedService").asBoolean()) {
                throw invalid("The auto scaling configuration is associated with a service.");
            }
            if (target.path("IsDefault").asBoolean() && DEFAULT.equals(target.path("AutoScalingConfigurationName")
                    .asText())) {
                throw invalid("The DefaultConfiguration can't be deleted.");
            }
        }
        for (ObjectNode target : targets) {
            target.put("Status", "INACTIVE");
            target.put("DeletedAt", now());
            target.put("Latest", false);
            saveConfiguration(region, target, "AutoScalingConfigurationArn");
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("AutoScalingConfiguration", scalingView(config, region));
        return out;
    }

    public ObjectNode listAutoScaling(JsonNode in, String region) {
        check("ListAutoScalingConfigurations", in);
        defaultScaling(region);
        String name = in.path("AutoScalingConfigurationName").asText(null);
        boolean latestOnly = in.path("LatestOnly").asBoolean(false);
        List<ObjectNode> all = configurations.scan(k -> k.startsWith(region + "::autoscalingconfiguration/")).stream()
                .filter(c -> "ACTIVE".equals(c.path("Status").asText()))
                .filter(c -> name == null || name.equals(c.path("AutoScalingConfigurationName").asText()))
                .filter(c -> !latestOnly || c.path("Latest").asBoolean())
                .sorted(Comparator.comparing((ObjectNode c) -> c.path("AutoScalingConfigurationName").asText())
                        .thenComparingInt(c -> c.path("AutoScalingConfigurationRevision").asInt()))
                .map(c -> {
                    ObjectNode view = scalingView(c, region);
                    ObjectNode summary = mapper.createObjectNode();
                    for (String field : List.of("AutoScalingConfigurationArn", "AutoScalingConfigurationName",
                            "AutoScalingConfigurationRevision", "Status", "CreatedAt", "HasAssociatedService",
                            "IsDefault")) {
                        summary.set(field, view.path(field).deepCopy());
                    }
                    return summary;
                }).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("AutoScalingConfigurationSummaryList");
        page(all, in, out, 10).forEach(list::add);
        return out;
    }

    public synchronized ObjectNode updateDefaultAutoScaling(JsonNode in, String region) {
        check("UpdateDefaultAutoScalingConfiguration", in);
        ObjectNode config = configurationByArn(configurations, in.path("AutoScalingConfigurationArn").asText(), region);
        if (config == null || !"ACTIVE".equals(config.path("Status").asText())) {
            throw new AwsException("ResourceNotFoundException", "Auto scaling configuration "
                    + in.path("AutoScalingConfigurationArn").asText() + " not found.", 400);
        }
        for (ObjectNode other : configurations.scan(k -> k.startsWith(region + "::autoscalingconfiguration/"))) {
            other.put("IsDefault", false);
            saveConfiguration(region, other, "AutoScalingConfigurationArn");
        }
        config.put("IsDefault", true);
        saveConfiguration(region, config, "AutoScalingConfigurationArn");
        ObjectNode out = mapper.createObjectNode();
        out.set("AutoScalingConfiguration", scalingView(config, region));
        return out;
    }

    public ObjectNode listServicesForAutoScaling(JsonNode in, String region) {
        check("ListServicesForAutoScalingConfiguration", in);
        String arn = in.path("AutoScalingConfigurationArn").asText();
        List<ObjectNode> all = services.scan(k -> k.startsWith(region + "::")).stream()
                .filter(s -> !"DELETED".equals(s.path("Status").asText()))
                .filter(s -> arn.equals(s.path("AutoScalingConfigurationSummary").path("AutoScalingConfigurationArn")
                        .asText()))
                .map(s -> mapper.createObjectNode().put("arn", s.path("ServiceArn").asText())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("ServiceArnList");
        page(all, in, out, 20).forEach(s -> list.add(s.path("arn").asText()));
        return out;
    }

    public synchronized ObjectNode createObservability(JsonNode in, String region) {
        check("CreateObservabilityConfiguration", in);
        String name = in.path("ObservabilityConfigurationName").asText();
        if (DEFAULT.equals(name)) {
            throw invalid("DefaultConfiguration is reserved.");
        }
        defaultObservability(region);
        ObjectNode config = newRevision(region, "observabilityconfiguration", "ObservabilityConfiguration", name);
        if (in.path("TraceConfiguration").isObject()) {
            config.set("TraceConfiguration", in.get("TraceConfiguration").deepCopy());
        }
        config.set("Tags", tagMap(in.path("Tags")));
        saveConfiguration(region, config, "ObservabilityConfigurationArn");
        ObjectNode out = mapper.createObjectNode();
        out.set("ObservabilityConfiguration", withoutTags(config));
        return out;
    }

    public ObjectNode describeObservability(JsonNode in, String region) {
        check("DescribeObservabilityConfiguration", in);
        defaultObservability(region);
        ObjectNode config = configurationByArn(configurations, in.path("ObservabilityConfigurationArn").asText(),
                region);
        if (config == null) {
            throw new AwsException("ResourceNotFoundException", "Observability configuration "
                    + in.path("ObservabilityConfigurationArn").asText() + " not found.", 400);
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("ObservabilityConfiguration", withoutTags(config));
        return out;
    }

    public synchronized ObjectNode deleteObservability(JsonNode in, String region) {
        check("DeleteObservabilityConfiguration", in);
        ObjectNode config = configurationByArn(configurations, in.path("ObservabilityConfigurationArn").asText(),
                region);
        if (config == null || !"ACTIVE".equals(config.path("Status").asText())) {
            throw new AwsException("ResourceNotFoundException", "Observability configuration "
                    + in.path("ObservabilityConfigurationArn").asText() + " not found.", 400);
        }
        String arn = config.path("ObservabilityConfigurationArn").asText();
        boolean used = services.scan(k -> k.startsWith(region + "::")).stream()
                .filter(s -> !"DELETED".equals(s.path("Status").asText()))
                .anyMatch(s -> arn.equals(s.path("ObservabilityConfiguration").path("ObservabilityConfigurationArn")
                        .asText()));
        if (used) {
            throw invalid("The observability configuration is used by a service.");
        }
        config.put("Status", "INACTIVE");
        config.put("DeletedAt", now());
        config.put("Latest", false);
        saveConfiguration(region, config, "ObservabilityConfigurationArn");
        ObjectNode out = mapper.createObjectNode();
        out.set("ObservabilityConfiguration", withoutTags(config));
        return out;
    }

    public ObjectNode listObservability(JsonNode in, String region) {
        check("ListObservabilityConfigurations", in);
        defaultObservability(region);
        String name = in.path("ObservabilityConfigurationName").asText(null);
        boolean latestOnly = in.path("LatestOnly").asBoolean(false);
        List<ObjectNode> all = configurations.scan(k -> k.startsWith(region + "::observabilityconfiguration/"))
                .stream().filter(c -> "ACTIVE".equals(c.path("Status").asText()))
                .filter(c -> name == null || name.equals(c.path("ObservabilityConfigurationName").asText()))
                .filter(c -> !latestOnly || c.path("Latest").asBoolean())
                .sorted(Comparator.comparing((ObjectNode c) -> c.path("ObservabilityConfigurationName").asText())
                        .thenComparingInt(c -> c.path("ObservabilityConfigurationRevision").asInt()))
                .map(c -> mapper.createObjectNode()
                        .put("ObservabilityConfigurationArn", c.path("ObservabilityConfigurationArn").asText())
                        .put("ObservabilityConfigurationName", c.path("ObservabilityConfigurationName").asText())
                        .put("ObservabilityConfigurationRevision", c.path("ObservabilityConfigurationRevision")
                                .asInt())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("ObservabilityConfigurationSummaryList");
        page(all, in, out, 10).forEach(list::add);
        return out;
    }

    // ── VPC connectors and ingress connections ─────────────────────────────

    public synchronized ObjectNode createVpcConnector(JsonNode in, String region) {
        check("CreateVpcConnector", in);
        String name = in.path("VpcConnectorName").asText();
        ObjectNode connector = newRevision(region, "vpcconnector", "VpcConnector", name);
        connector.remove("Latest");
        connector.set("Subnets", in.path("Subnets").deepCopy());
        connector.set("SecurityGroups", in.path("SecurityGroups").isArray() ? in.get("SecurityGroups").deepCopy()
                : mapper.createArrayNode());
        connector.set("Tags", tagMap(in.path("Tags")));
        connectors.put(configKey(region, connector.path("VpcConnectorArn").asText()), connector);
        ObjectNode out = mapper.createObjectNode();
        out.set("VpcConnector", withoutTags(connector));
        return out;
    }

    public ObjectNode describeVpcConnector(JsonNode in, String region) {
        check("DescribeVpcConnector", in);
        ObjectNode connector = configurationByArn(connectors, in.path("VpcConnectorArn").asText(), region);
        if (connector == null) {
            throw new AwsException("ResourceNotFoundException", "VPC connector " + in.path("VpcConnectorArn").asText()
                    + " not found.", 400);
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("VpcConnector", withoutTags(connector));
        return out;
    }

    public synchronized ObjectNode deleteVpcConnector(JsonNode in, String region) {
        check("DeleteVpcConnector", in);
        ObjectNode connector = configurationByArn(connectors, in.path("VpcConnectorArn").asText(), region);
        if (connector == null || !"ACTIVE".equals(connector.path("Status").asText())) {
            throw new AwsException("ResourceNotFoundException", "VPC connector " + in.path("VpcConnectorArn").asText()
                    + " not found.", 400);
        }
        String arn = connector.path("VpcConnectorArn").asText();
        boolean used = services.scan(k -> k.startsWith(region + "::")).stream()
                .filter(s -> !"DELETED".equals(s.path("Status").asText()))
                .anyMatch(s -> arn.equals(s.path("NetworkConfiguration").path("EgressConfiguration")
                        .path("VpcConnectorArn").asText()));
        if (used) {
            throw invalid("The VPC connector is used by a service.");
        }
        connector.put("Status", "INACTIVE");
        connector.put("DeletedAt", now());
        connectors.put(configKey(region, arn), connector);
        ObjectNode out = mapper.createObjectNode();
        out.set("VpcConnector", withoutTags(connector));
        return out;
    }

    public ObjectNode listVpcConnectors(JsonNode in, String region) {
        check("ListVpcConnectors", in);
        List<ObjectNode> all = connectors.scan(k -> k.startsWith(region + "::")).stream()
                .filter(c -> "ACTIVE".equals(c.path("Status").asText()))
                .sorted(Comparator.comparingDouble(c -> c.path("CreatedAt").asDouble())).map(this::withoutTags).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("VpcConnectors");
        page(all, in, out, 20).forEach(list::add);
        return out;
    }

    public synchronized ObjectNode createVpcIngress(JsonNode in, String region) {
        check("CreateVpcIngressConnection", in);
        ObjectNode service = requireService(in.path("ServiceArn").asText(), region);
        String name = in.path("VpcIngressConnectionName").asText();
        boolean taken = ingress.scan(k -> k.startsWith(region + "::")).stream()
                .anyMatch(c -> name.equals(c.path("VpcIngressConnectionName").asText())
                        && !"DELETED".equals(c.path("Status").asText()));
        if (taken) {
            throw invalid("A VPC ingress connection named " + name + " already exists.");
        }
        String id = HexFormat.of().formatHex(randomBytes(16));
        ObjectNode connection = mapper.createObjectNode();
        connection.put("VpcIngressConnectionArn", arn(region, "vpcingressconnection/" + name + "/" + id));
        connection.put("VpcIngressConnectionName", name);
        connection.put("ServiceArn", service.path("ServiceArn").asText());
        connection.put("Status", "AVAILABLE");
        connection.put("AccountId", regionResolver.getAccountId());
        connection.put("DomainName", randomLabel(10) + "." + service.path("ServiceId").asText().substring(0, 8) + "."
                + region + ".awsapprunner.com");
        connection.set("IngressVpcConfiguration", in.path("IngressVpcConfiguration").deepCopy());
        connection.put("CreatedAt", now());
        connection.set("Tags", tagMap(in.path("Tags")));
        ingress.put(key(region, id), connection);
        ObjectNode out = mapper.createObjectNode();
        out.set("VpcIngressConnection", withoutTags(connection));
        return out;
    }

    private ObjectNode requireIngress(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "apprunner")) {
            throw invalid("VpcIngressConnectionArn must be an App Runner ARN.");
        }
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        return ingress.get(key(region, id)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "VPC ingress connection " + arn + " not found.", 400));
    }

    public ObjectNode describeVpcIngress(JsonNode in, String region) {
        check("DescribeVpcIngressConnection", in);
        ObjectNode out = mapper.createObjectNode();
        out.set("VpcIngressConnection", withoutTags(requireIngress(in.path("VpcIngressConnectionArn").asText(),
                region)));
        return out;
    }

    public synchronized ObjectNode updateVpcIngress(JsonNode in, String region) {
        check("UpdateVpcIngressConnection", in);
        ObjectNode connection = requireIngress(in.path("VpcIngressConnectionArn").asText(), region);
        if (!"AVAILABLE".equals(connection.path("Status").asText())
                && !"FAILED_UPDATE".equals(connection.path("Status").asText())) {
            throw new AwsException("InvalidStateException", "The VPC ingress connection is "
                    + connection.path("Status").asText() + ".", 400);
        }
        connection.set("IngressVpcConfiguration", in.path("IngressVpcConfiguration").deepCopy());
        ingress.put(key(region, idOf(connection.path("VpcIngressConnectionArn").asText())), connection);
        ObjectNode out = mapper.createObjectNode();
        out.set("VpcIngressConnection", withoutTags(connection));
        return out;
    }

    public synchronized ObjectNode deleteVpcIngress(JsonNode in, String region) {
        check("DeleteVpcIngressConnection", in);
        ObjectNode connection = requireIngress(in.path("VpcIngressConnectionArn").asText(), region);
        connection.put("Status", "DELETED");
        connection.put("DeletedAt", now());
        ingress.put(key(region, idOf(connection.path("VpcIngressConnectionArn").asText())), connection);
        ObjectNode out = mapper.createObjectNode();
        out.set("VpcIngressConnection", withoutTags(connection));
        return out;
    }

    public ObjectNode listVpcIngress(JsonNode in, String region) {
        check("ListVpcIngressConnections", in);
        String serviceArn = in.path("Filter").path("ServiceArn").asText(null);
        String endpoint = in.path("Filter").path("VpcEndpointId").asText(null);
        List<ObjectNode> all = ingress.scan(k -> k.startsWith(region + "::")).stream()
                .filter(c -> !"DELETED".equals(c.path("Status").asText()))
                .filter(c -> serviceArn == null || serviceArn.equals(c.path("ServiceArn").asText()))
                .filter(c -> endpoint == null || endpoint.equals(c.path("IngressVpcConfiguration")
                        .path("VpcEndpointId").asText()))
                .map(c -> mapper.createObjectNode().put("VpcIngressConnectionArn", c.path("VpcIngressConnectionArn")
                        .asText()).put("ServiceArn", c.path("ServiceArn").asText())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("VpcIngressConnectionSummaryList");
        page(all, in, out, 20).forEach(list::add);
        return out;
    }

    // ── Connections ────────────────────────────────────────────────────────

    public synchronized ObjectNode createConnection(JsonNode in, String region) {
        check("CreateConnection", in);
        String name = in.path("ConnectionName").asText();
        if (connections.scan(k -> k.startsWith(region + "::")).stream()
                .anyMatch(c -> name.equals(c.path("ConnectionName").asText()))) {
            throw invalid("A connection named " + name + " already exists.");
        }
        String id = HexFormat.of().formatHex(randomBytes(16));
        ObjectNode connection = mapper.createObjectNode();
        connection.put("ConnectionName", name);
        connection.put("ConnectionArn", arn(region, "connection/" + name + "/" + id));
        connection.put("ProviderType", in.path("ProviderType").asText());
        // The handshake happens in the provider's console, which Floci can't reach.
        connection.put("Status", "PENDING_HANDSHAKE");
        connection.put("CreatedAt", now());
        connection.set("Tags", tagMap(in.path("Tags")));
        connections.put(key(region, id), connection);
        ObjectNode out = mapper.createObjectNode();
        out.set("Connection", withoutTags(connection));
        return out;
    }

    public synchronized ObjectNode deleteConnection(JsonNode in, String region) {
        check("DeleteConnection", in);
        String arn = in.path("ConnectionArn").asText();
        String storeKey = key(region, idOf(arn));
        ObjectNode connection = connections.get(storeKey).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "Connection " + arn + " not found.", 400));
        connections.delete(storeKey);
        connection.put("Status", "DELETED");
        ObjectNode out = mapper.createObjectNode();
        out.set("Connection", withoutTags(connection));
        return out;
    }

    public ObjectNode listConnections(JsonNode in, String region) {
        check("ListConnections", in);
        String name = in.path("ConnectionName").asText(null);
        List<ObjectNode> all = connections.scan(k -> k.startsWith(region + "::")).stream()
                .filter(c -> name == null || name.equals(c.path("ConnectionName").asText()))
                .sorted(Comparator.comparingDouble(c -> c.path("CreatedAt").asDouble())).map(this::withoutTags).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("ConnectionSummaryList");
        page(all, in, out, 20).forEach(list::add);
        return out;
    }

    // ── Custom domains ─────────────────────────────────────────────────────

    public synchronized ObjectNode associateCustomDomain(JsonNode in, String region) {
        check("AssociateCustomDomain", in);
        ObjectNode service = requireService(in.path("ServiceArn").asText(), region);
        String domain = in.path("DomainName").asText().toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        ArrayNode domains = service.withArray("CustomDomains");
        for (JsonNode existing : domains) {
            if (domain.equals(existing.path("DomainName").asText())) {
                throw invalid("The domain " + domain + " is already associated with the service.");
            }
        }
        if (domains.size() >= 5) {
            throw new AwsException("ServiceQuotaExceededException", "A service can have at most 5 custom domains.",
                    400);
        }
        boolean www = in.path("EnableWWWSubdomain").asBoolean(true);
        ObjectNode custom = domains.addObject();
        custom.put("DomainName", domain);
        custom.put("EnableWWWSubdomain", www);
        ArrayNode records = custom.putArray("CertificateValidationRecords");
        for (String name : www ? List.of(domain, "www." + domain) : List.of(domain)) {
            records.addObject().put("Name", "_" + HexFormat.of().formatHex(randomBytes(16)) + "." + name + ".")
                    .put("Type", "CNAME").put("Value", "_" + HexFormat.of().formatHex(randomBytes(16))
                            + ".acm-validations.aws.").put("Status", "PENDING_VALIDATION");
        }
        custom.put("Status", "PENDING_CERTIFICATE_DNS_VALIDATION");
        services.put(key(region, service.path("ServiceId").asText()), service);
        return domainResult(service, custom);
    }

    private ObjectNode domainResult(ObjectNode service, JsonNode custom) {
        ObjectNode out = mapper.createObjectNode();
        out.put("DNSTarget", service.path("ServiceUrl").asText());
        out.put("ServiceArn", service.path("ServiceArn").asText());
        out.set("CustomDomain", custom.deepCopy());
        return out;
    }

    /** Validates a pending domain whose validation CNAMEs exist in Floci's Route 53. */
    private void validate(ObjectNode custom) {
        if (!"PENDING_CERTIFICATE_DNS_VALIDATION".equals(custom.path("Status").asText()) || !route53.isResolvable()) {
            return;
        }
        boolean all = true;
        for (JsonNode record : custom.withArray("CertificateValidationRecords")) {
            if ("SUCCESS".equals(record.path("Status").asText())) {
                continue;
            }
            if (recordExists(record.path("Name").asText(), record.path("Value").asText())) {
                ((ObjectNode) record).put("Status", "SUCCESS");
            } else {
                all = false;
            }
        }
        if (all) {
            custom.put("Status", "ACTIVE");
        }
    }

    private boolean recordExists(String name, String value) {
        String wanted = name.endsWith(".") ? name : name + ".";
        String[] labels = wanted.split("\\.");
        for (int i = 1; i < labels.length - 1; i++) {
            String zoneName = String.join(".", Arrays.copyOfRange(labels, i, labels.length)) + ".";
            for (HostedZone zone : route53.get().listHostedZonesByName(zoneName, 1)) {
                if (!zone.getName().equalsIgnoreCase(zoneName)) {
                    continue;
                }
                for (ResourceRecordSet set : route53.get().listResourceRecordSets(zone.getId(), wanted, "CNAME", 10)) {
                    if (Route53Service.canonicalRecordName(set.getName()).equalsIgnoreCase(wanted)
                            && set.getRecords() != null && set.getRecords().stream()
                            .anyMatch(r -> r.getValue().replaceAll("\\.$", "").equalsIgnoreCase(value
                                    .replaceAll("\\.$", "")))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public synchronized ObjectNode describeCustomDomains(JsonNode in, String region) {
        check("DescribeCustomDomains", in);
        ObjectNode service = requireService(in.path("ServiceArn").asText(), region);
        List<ObjectNode> all = new ArrayList<>();
        for (JsonNode custom : service.withArray("CustomDomains")) {
            validate((ObjectNode) custom);
            all.add(((ObjectNode) custom).deepCopy());
        }
        services.put(key(region, service.path("ServiceId").asText()), service);
        ObjectNode out = mapper.createObjectNode();
        out.put("DNSTarget", service.path("ServiceUrl").asText());
        out.put("ServiceArn", service.path("ServiceArn").asText());
        ArrayNode list = out.putArray("CustomDomains");
        page(all, in, out, 5).forEach(list::add);
        out.putArray("VpcDNSTargets");
        return out;
    }

    public synchronized ObjectNode disassociateCustomDomain(JsonNode in, String region) {
        check("DisassociateCustomDomain", in);
        ObjectNode service = requireService(in.path("ServiceArn").asText(), region);
        String domain = in.path("DomainName").asText().toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        ArrayNode domains = service.withArray("CustomDomains");
        for (int i = 0; i < domains.size(); i++) {
            if (domain.equals(domains.get(i).path("DomainName").asText())) {
                ObjectNode removed = (ObjectNode) domains.remove(i);
                removed.put("Status", "DELETING");
                services.put(key(region, service.path("ServiceId").asText()), service);
                return domainResult(service, removed);
            }
        }
        throw new AwsException("ResourceNotFoundException", "The domain " + domain + " isn't associated with the "
                + "service.", 400);
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    private record Tagged(AccountAwareStorageBackend<ObjectNode> store, String key, ObjectNode resource) {}

    private Tagged tagged(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "apprunner")) {
            throw invalid("ResourceArn must be an App Runner ARN.");
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String type = resource.substring(0, resource.indexOf('/'));
        String id = idOf(arn);
        return switch (type) {
            case "service" -> {
                ObjectNode service = requireService(arn, region);
                yield new Tagged(services, key(region, service.path("ServiceId").asText()), service);
            }
            case "autoscalingconfiguration", "observabilityconfiguration" -> {
                ObjectNode config = configurationByArn(configurations, arn, region);
                if (config == null) {
                    throw new AwsException("ResourceNotFoundException", "Resource " + arn + " not found.", 400);
                }
                yield new Tagged(configurations, configKey(region, arn), config);
            }
            case "vpcconnector" -> {
                ObjectNode connector = configurationByArn(connectors, arn, region);
                if (connector == null) {
                    throw new AwsException("ResourceNotFoundException", "Resource " + arn + " not found.", 400);
                }
                yield new Tagged(connectors, configKey(region, arn), connector);
            }
            case "vpcingressconnection" -> new Tagged(ingress, key(region, id), requireIngress(arn, region));
            case "connection" -> new Tagged(connections, key(region, id), connections.get(key(region, id))
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Resource " + arn
                            + " not found.", 400)));
            default -> throw new AwsException("ResourceNotFoundException", "Resource " + arn + " not found.", 400);
        };
    }

    public ObjectNode listTags(JsonNode in, String region) {
        check("ListTagsForResource", in);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("Tags");
        tagged(in.path("ResourceArn").asText(), region).resource().path("Tags").fields().forEachRemaining(e ->
                list.addObject().put("Key", e.getKey()).put("Value", e.getValue().asText()));
        return out;
    }

    public synchronized ObjectNode tag(JsonNode in, String region) {
        check("TagResource", in);
        Tagged tagged = tagged(in.path("ResourceArn").asText(), region);
        ObjectNode tags = tagged.resource().withObject("Tags");
        in.path("Tags").forEach(t -> tags.put(t.path("Key").asText(), t.path("Value").asText("")));
        if (tags.size() > 50) {
            throw invalid("A resource can have at most 50 tags.");
        }
        tagged.store().put(tagged.key(), tagged.resource());
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode untag(JsonNode in, String region) {
        check("UntagResource", in);
        Tagged tagged = tagged(in.path("ResourceArn").asText(), region);
        in.path("TagKeys").forEach(k -> tagged.resource().withObject("Tags").remove(k.asText()));
        tagged.store().put(tagged.key(), tagged.resource());
        return mapper.createObjectNode();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ObjectNode withoutTags(ObjectNode resource) {
        ObjectNode copy = resource.deepCopy();
        copy.remove("Tags");
        return copy;
    }

    private ObjectNode tagMap(JsonNode tags) {
        ObjectNode map = mapper.createObjectNode();
        tags.forEach(t -> map.put(t.path("Key").asText(), t.path("Value").asText("")));
        return map;
    }

    private static List<ObjectNode> page(List<ObjectNode> all, JsonNode in, ObjectNode out, int defaultMax) {
        int max = in.path("MaxResults").asInt(defaultMax);
        int start = 0;
        if (in.hasNonNull("NextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getDecoder().decode(in.get("NextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw invalid("The NextToken is not valid.");
            }
        }
        int end = Math.min(all.size(), start + Math.max(1, max));
        if (end < all.size()) {
            out.put("NextToken", Base64.getEncoder().encodeToString(Integer.toString(end)
                    .getBytes(StandardCharsets.UTF_8)));
        }
        return all.subList(Math.min(start, end), end);
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("apprunner", region, regionResolver.getAccountId(), resource).toString();
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private String randomLabel(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    private static String idOf(String arn) {
        return arn.substring(arn.lastIndexOf('/') + 1);
    }

    /** A timestamp as the JSON protocol sends it: epoch seconds. */
    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    @Override
    public void clear() {
        services.scan(k -> true).forEach(s -> runtime.stop(s.path("ServiceArn").asText()));
        services.clear();
        configurations.clear();
        connectors.clear();
        ingress.clear();
        connections.clear();
        operations.clear();
    }
}
