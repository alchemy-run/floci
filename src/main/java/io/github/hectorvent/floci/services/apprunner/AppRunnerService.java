package io.github.hectorvent.floci.services.apprunner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apprunner.model.AppRunnerOperation;
import io.github.hectorvent.floci.services.apprunner.model.AppRunnerServiceRecord;
import io.github.hectorvent.floci.services.apprunner.model.AutoScalingConfiguration;
import io.github.hectorvent.floci.services.apprunner.model.ObservabilityConfiguration;
import io.github.hectorvent.floci.services.apprunner.model.ObservabilityConfiguration.TraceConfiguration;
import io.github.hectorvent.floci.services.apprunner.model.VpcConnector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * App Runner JSON 1.0 management plane: services, auto scaling and observability
 * configuration revisions, and VPC connectors.
 */
@ApplicationScoped
public class AppRunnerService implements Resettable {

    static final String SERVICE = "apprunner";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String VENDOR_XRAY = "AWSXRAY";
    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int MAX_RESULTS = 20;
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9\\-_]{3,31}");
    private static final Pattern SERVICE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9\\-_]{3,39}");
    private static final Pattern SERVICE_ARN = Pattern.compile(
            "^arn:aws:apprunner:([^:]+):([^:]+):service/([^/]+)/([^/]+)$");

    private final StorageBackend<String, ObservabilityConfiguration> store;
    private final StorageBackend<String, AppRunnerServiceRecord> serviceStore;
    private final StorageBackend<String, AutoScalingConfiguration> autoScalingStore;
    private final StorageBackend<String, VpcConnector> vpcStore;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    Instance<CloudWatchLogsService> logsService;
    @Inject
    Instance<AppRunnerContainerManager> containers;
    @Inject
    Instance<EmulatorConfig> emulatorConfig;

    @Inject
    public AppRunnerService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper mapper) {
        this(storageFactory.create("apprunner", "apprunner-observability-configurations.json",
                        new TypeReference<Map<String, ObservabilityConfiguration>>() {
                        }),
                storageFactory.create("apprunner", "apprunner-services.json",
                        new TypeReference<Map<String, AppRunnerServiceRecord>>() {
                        }),
                storageFactory.create("apprunner", "apprunner-autoscaling-configurations.json",
                        new TypeReference<Map<String, AutoScalingConfiguration>>() {
                        }),
                storageFactory.create("apprunner", "apprunner-vpc-connectors.json",
                        new TypeReference<Map<String, VpcConnector>>() {
                        }),
                regionResolver, mapper);
    }

    AppRunnerService(StorageBackend<String, ObservabilityConfiguration> store, RegionResolver regionResolver,
                     ObjectMapper mapper) {
        this(store, new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                regionResolver, mapper);
    }

    AppRunnerService(StorageBackend<String, ObservabilityConfiguration> store,
                     StorageBackend<String, AppRunnerServiceRecord> serviceStore,
                     RegionResolver regionResolver, ObjectMapper mapper) {
        this(store, serviceStore, new InMemoryStorage<>(), new InMemoryStorage<>(), regionResolver, mapper);
    }

    AppRunnerService(StorageBackend<String, ObservabilityConfiguration> store,
                     StorageBackend<String, AppRunnerServiceRecord> serviceStore,
                     StorageBackend<String, AutoScalingConfiguration> autoScalingStore,
                     StorageBackend<String, VpcConnector> vpcStore,
                     RegionResolver regionResolver, ObjectMapper mapper) {
        this.store = store;
        this.serviceStore = serviceStore;
        this.autoScalingStore = autoScalingStore;
        this.vpcStore = vpcStore;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    public JsonNode handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? mapper.createObjectNode()
                : request;
        if (!body.isObject()) {
            throw invalidRequest("Request body must be a JSON object.");
        }
        return switch (action) {
            case "CreateService" -> createService(body, region);
            case "DescribeService" -> describeService(body, region);
            case "ListServices" -> listServices(body, region);
            case "UpdateService" -> updateService(body, region);
            case "DeleteService" -> deleteService(body, region);
            case "PauseService" -> pauseService(body, region);
            case "ResumeService" -> resumeService(body, region);
            case "StartDeployment" -> startDeployment(body, region);
            case "ListOperations" -> listOperations(body, region);
            case "DescribeCustomDomains" -> describeCustomDomains(body, region);
            case "CreateObservabilityConfiguration" -> wrap("ObservabilityConfiguration",
                    createObservabilityConfiguration(body, region));
            case "DescribeObservabilityConfiguration" -> wrap("ObservabilityConfiguration",
                    describeObservabilityConfiguration(body, region));
            case "DeleteObservabilityConfiguration" -> wrap("ObservabilityConfiguration",
                    deleteObservabilityConfiguration(body, region));
            case "ListObservabilityConfigurations" -> listObservabilityConfigurations(body, region);
            case "CreateAutoScalingConfiguration" -> wrapAutoScaling(
                    createAutoScalingConfiguration(body, region));
            case "DescribeAutoScalingConfiguration" -> wrapAutoScaling(
                    describeAutoScalingConfiguration(body, region));
            case "DeleteAutoScalingConfiguration" -> wrapAutoScaling(
                    deleteAutoScalingConfiguration(body, region));
            case "ListAutoScalingConfigurations" -> listAutoScalingConfigurations(body, region);
            case "CreateVpcConnector" -> wrapVpc(createVpcConnector(body, region));
            case "DescribeVpcConnector" -> wrapVpc(describeVpcConnector(body, region));
            case "DeleteVpcConnector" -> wrapVpc(deleteVpcConnector(body, region));
            case "ListVpcConnectors" -> listVpcConnectors(body, region);
            case "ListTagsForResource" -> listTagsForResource(body, region);
            case "TagResource" -> tagResource(body, region);
            case "UntagResource" -> untagResource(body, region);
            default -> throw new AwsException("UnknownOperationException",
                    "Unknown operation: AppRunner." + action, 400);
        };
    }

    @Override
    public void clear() {
        stopAllContainers();
        store.clear();
        serviceStore.clear();
        autoScalingStore.clear();
        vpcStore.clear();
    }

    /**
     * Resolve a live service from an App Runner public hostname
     * ({@code {serviceId}.{region}.awsapprunner.com} or the local
     * {@code *.awsapprunner.com.localhost} form, with an optional port).
     */
    public AppRunnerServiceRecord findByHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String hostname = host;
        int colon = hostname.lastIndexOf(':');
        if (colon > 0 && hostname.indexOf(']') < colon) {
            hostname = hostname.substring(0, colon);
        }
        hostname = hostname.toLowerCase();
        for (AppRunnerServiceRecord record : serviceStore.values()) {
            if (record.getServiceUrl() == null || "DELETED".equals(record.getStatus())) {
                continue;
            }
            String url = record.getServiceUrl().toLowerCase();
            if (hostname.equals(url)
                    || hostname.startsWith(url + ":")
                    || hostname.startsWith(url + ".")
                    || (record.getServiceId() != null && hostname.startsWith(record.getServiceId().toLowerCase() + "."))) {
                return record;
            }
        }
        return null;
    }

    // ── Services ────────────────────────────────────────────────────────────

    private JsonNode createService(JsonNode request, String region) {
        String name = requireText(request, "ServiceName");
        if (!SERVICE_NAME.matcher(name).matches()) {
            throw invalidRequest("ServiceName must be 4-40 characters of letters, digits, hyphens, or underscores.");
        }
        for (AppRunnerServiceRecord existing : serviceStore.values()) {
            if (region.equals(existing.getRegion()) && name.equals(existing.getServiceName())
                    && !"DELETED".equals(existing.getStatus())) {
                throw invalidRequest("Service " + name + " already exists.");
            }
        }
        JsonNode source = request.get("SourceConfiguration");
        if (source == null || !source.isObject()) {
            throw invalidRequest("SourceConfiguration is required.");
        }
        long now = Instant.now().getEpochSecond();
        String id = UUID.randomUUID().toString().replace("-", "");
        String arn = "arn:aws:apprunner:" + region + ":" + regionResolver.getAccountId()
                + ":service/" + name + "/" + id;
        AppRunnerServiceRecord service = new AppRunnerServiceRecord();
        service.setServiceName(name);
        service.setServiceId(id);
        service.setServiceArn(arn);
        service.setServiceUrl(localServiceUrl(id, region));
        service.setRegion(region);
        service.setStatus("RUNNING");
        service.setCreatedAt(now);
        service.setUpdatedAt(now);
        service.setSourceConfiguration(source.deepCopy());
        if (request.has("InstanceConfiguration") && request.get("InstanceConfiguration").isObject()) {
            service.setInstanceConfiguration(request.get("InstanceConfiguration").deepCopy());
        }
        if (request.has("HealthCheckConfiguration")) {
            service.setHealthCheckConfiguration(request.get("HealthCheckConfiguration").deepCopy());
        }
        if (request.has("NetworkConfiguration")) {
            service.setNetworkConfiguration(request.get("NetworkConfiguration").deepCopy());
        }
        if (request.has("ObservabilityConfiguration")) {
            service.setObservabilityConfiguration(request.get("ObservabilityConfiguration").deepCopy());
        }
        if (request.has("EncryptionConfiguration")) {
            service.setEncryptionConfiguration(request.get("EncryptionConfiguration").deepCopy());
        }
        if (request.has("AutoScalingConfigurationArn")) {
            service.setAutoScalingConfigurationArn(optionalText(request, "AutoScalingConfigurationArn"));
        }
        service.setTags(readTags(request));
        String operationId = recordOperation(service, "CREATE_SERVICE", now);
        serviceStore.put(arn, service);
        ensureLogGroups(service);
        startContainer(service);
        ObjectNode response = mapper.createObjectNode();
        response.set("Service", serviceNode(service));
        response.put("OperationId", operationId);
        return response;
    }

    private JsonNode describeService(JsonNode request, String region) {
        AppRunnerServiceRecord service = requireService(requireText(request, "ServiceArn"), region);
        ObjectNode response = mapper.createObjectNode();
        response.set("Service", serviceNode(service));
        return response;
    }

    private JsonNode listServices(JsonNode request, String region) {
        int maxResults = readMaxResults(request);
        int offset = readOffset(request);
        List<AppRunnerServiceRecord> matches = new ArrayList<>();
        for (AppRunnerServiceRecord service : serviceStore.values()) {
            if (region.equals(service.getRegion()) && !"DELETED".equals(service.getStatus())) {
                matches.add(service);
            }
        }
        matches.sort(Comparator.comparing(AppRunnerServiceRecord::getServiceName,
                Comparator.nullsLast(String::compareTo)));
        int from = Math.min(offset, matches.size());
        int to = Math.min(from + maxResults, matches.size());
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("ServiceSummaryList");
        for (AppRunnerServiceRecord service : matches.subList(from, to)) {
            ObjectNode summary = list.addObject();
            summary.put("ServiceName", service.getServiceName());
            summary.put("ServiceId", service.getServiceId());
            summary.put("ServiceArn", service.getServiceArn());
            summary.put("ServiceUrl", service.getServiceUrl());
            summary.put("Status", service.getStatus());
            summary.put("CreatedAt", service.getCreatedAt());
            summary.put("UpdatedAt", service.getUpdatedAt());
        }
        if (to < matches.size()) {
            response.put("NextToken", Integer.toString(to));
        }
        return response;
    }

    private JsonNode updateService(JsonNode request, String region) {
        AppRunnerServiceRecord service = requireService(requireText(request, "ServiceArn"), region);
        if (request.has("SourceConfiguration") && request.get("SourceConfiguration").isObject()) {
            service.setSourceConfiguration(request.get("SourceConfiguration").deepCopy());
        }
        if (request.has("InstanceConfiguration") && request.get("InstanceConfiguration").isObject()) {
            service.setInstanceConfiguration(request.get("InstanceConfiguration").deepCopy());
        }
        if (request.has("HealthCheckConfiguration")) {
            service.setHealthCheckConfiguration(request.get("HealthCheckConfiguration").deepCopy());
        }
        if (request.has("NetworkConfiguration")) {
            service.setNetworkConfiguration(request.get("NetworkConfiguration").deepCopy());
        }
        if (request.has("ObservabilityConfiguration")) {
            service.setObservabilityConfiguration(request.get("ObservabilityConfiguration").deepCopy());
        }
        if (request.has("AutoScalingConfigurationArn")) {
            service.setAutoScalingConfigurationArn(optionalText(request, "AutoScalingConfigurationArn"));
        }
        long now = Instant.now().getEpochSecond();
        service.setUpdatedAt(now);
        String operationId = recordOperation(service, "UPDATE_SERVICE", now);
        serviceStore.put(service.getServiceArn(), service);
        startContainer(service);
        ObjectNode response = mapper.createObjectNode();
        response.set("Service", serviceNode(service));
        response.put("OperationId", operationId);
        return response;
    }

    private JsonNode deleteService(JsonNode request, String region) {
        AppRunnerServiceRecord service = requireService(requireText(request, "ServiceArn"), region);
        long now = Instant.now().getEpochSecond();
        service.setStatus("DELETED");
        service.setUpdatedAt(now);
        String operationId = recordOperation(service, "DELETE_SERVICE", now);
        stopContainer(service.getServiceId());
        serviceStore.delete(service.getServiceArn());
        ObjectNode response = mapper.createObjectNode();
        response.set("Service", serviceNode(service));
        response.put("OperationId", operationId);
        return response;
    }

    private JsonNode pauseService(JsonNode request, String region) {
        return mutateServiceStatus(request, region, "PAUSED", "PAUSE_SERVICE");
    }

    private JsonNode resumeService(JsonNode request, String region) {
        return mutateServiceStatus(request, region, "RUNNING", "RESUME_SERVICE");
    }

    private JsonNode mutateServiceStatus(JsonNode request, String region, String status, String type) {
        AppRunnerServiceRecord service = requireService(requireText(request, "ServiceArn"), region);
        long now = Instant.now().getEpochSecond();
        service.setStatus(status);
        service.setUpdatedAt(now);
        String operationId = recordOperation(service, type, now);
        serviceStore.put(service.getServiceArn(), service);
        ObjectNode response = mapper.createObjectNode();
        response.set("Service", serviceNode(service));
        response.put("OperationId", operationId);
        return response;
    }

    private JsonNode startDeployment(JsonNode request, String region) {
        AppRunnerServiceRecord service = requireService(requireText(request, "ServiceArn"), region);
        long now = Instant.now().getEpochSecond();
        service.setUpdatedAt(now);
        String operationId = recordOperation(service, "START_DEPLOYMENT", now);
        serviceStore.put(service.getServiceArn(), service);
        startContainer(service);
        ObjectNode response = mapper.createObjectNode();
        response.put("OperationId", operationId);
        return response;
    }

    private JsonNode listOperations(JsonNode request, String region) {
        AppRunnerServiceRecord service = requireService(requireText(request, "ServiceArn"), region);
        int maxResults = readMaxResults(request);
        int offset = readOffset(request);
        List<AppRunnerOperation> operations = new ArrayList<>(service.getOperations());
        operations.sort(Comparator.comparingLong(AppRunnerOperation::getStartedAt).reversed());
        int from = Math.min(offset, operations.size());
        int to = Math.min(from + maxResults, operations.size());
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("OperationSummaryList");
        for (AppRunnerOperation operation : operations.subList(from, to)) {
            ObjectNode summary = list.addObject();
            summary.put("Id", operation.getId());
            summary.put("Type", operation.getType());
            summary.put("Status", operation.getStatus());
            summary.put("TargetArn", operation.getTargetArn());
            summary.put("StartedAt", operation.getStartedAt());
            summary.put("EndedAt", operation.getEndedAt());
            summary.put("UpdatedAt", operation.getUpdatedAt());
        }
        if (to < operations.size()) {
            response.put("NextToken", Integer.toString(to));
        }
        return response;
    }

    private JsonNode describeCustomDomains(JsonNode request, String region) {
        AppRunnerServiceRecord service = requireService(requireText(request, "ServiceArn"), region);
        ObjectNode response = mapper.createObjectNode();
        response.put("DNSTarget", service.getServiceUrl());
        response.put("ServiceArn", service.getServiceArn());
        response.putArray("CustomDomains");
        response.putArray("VpcDNSTargets");
        return response;
    }

    private String recordOperation(AppRunnerServiceRecord service, String type, long now) {
        String id = UUID.randomUUID().toString();
        AppRunnerOperation operation = new AppRunnerOperation();
        operation.setId(id);
        operation.setType(type);
        operation.setStatus("SUCCEEDED");
        operation.setTargetArn(service.getServiceArn());
        operation.setStartedAt(now);
        operation.setEndedAt(now);
        operation.setUpdatedAt(now);
        List<AppRunnerOperation> operations = new ArrayList<>(service.getOperations());
        operations.add(operation);
        service.setOperations(operations);
        return id;
    }

    private ObjectNode serviceNode(AppRunnerServiceRecord service) {
        ObjectNode node = mapper.createObjectNode();
        node.put("ServiceName", service.getServiceName());
        node.put("ServiceId", service.getServiceId());
        node.put("ServiceArn", service.getServiceArn());
        node.put("ServiceUrl", service.getServiceUrl());
        node.put("Status", service.getStatus());
        node.put("CreatedAt", service.getCreatedAt());
        node.put("UpdatedAt", service.getUpdatedAt());
        if (service.getSourceConfiguration() != null) {
            node.set("SourceConfiguration", service.getSourceConfiguration());
        }
        if (service.getInstanceConfiguration() != null) {
            node.set("InstanceConfiguration", service.getInstanceConfiguration());
        }
        if (service.getHealthCheckConfiguration() != null) {
            node.set("HealthCheckConfiguration", service.getHealthCheckConfiguration());
        }
        if (service.getNetworkConfiguration() != null) {
            node.set("NetworkConfiguration", service.getNetworkConfiguration());
        }
        if (service.getObservabilityConfiguration() != null) {
            node.set("ObservabilityConfiguration", service.getObservabilityConfiguration());
        }
        if (service.getEncryptionConfiguration() != null) {
            node.set("EncryptionConfiguration", service.getEncryptionConfiguration());
        }
        ObjectNode summary = node.putObject("AutoScalingConfigurationSummary");
        String autoScalingArn = service.getAutoScalingConfigurationArn();
        if (autoScalingArn == null) {
            autoScalingArn = "arn:aws:apprunner:" + service.getRegion() + ":"
                    + regionResolver.getAccountId()
                    + ":autoscalingconfiguration/DefaultConfiguration/1/00000000000000000000000000000001";
        }
        summary.put("AutoScalingConfigurationArn", autoScalingArn);
        summary.put("AutoScalingConfigurationName",
                service.getAutoScalingConfigurationName() != null
                        ? service.getAutoScalingConfigurationName()
                        : "DefaultConfiguration");
        summary.put("AutoScalingConfigurationRevision",
                service.getAutoScalingConfigurationRevision() != null
                        ? service.getAutoScalingConfigurationRevision()
                        : 1);
        summary.put("Status", "active");
        return node;
    }

    private AppRunnerServiceRecord requireService(String arn, String region) {
        Matcher matcher = SERVICE_ARN.matcher(arn);
        if (!matcher.matches()) {
            throw notFound(arn);
        }
        AppRunnerServiceRecord service = serviceStore.get(arn).orElse(null);
        if (service == null || !region.equals(service.getRegion()) || "DELETED".equals(service.getStatus())) {
            throw notFound(arn);
        }
        return service;
    }

    // ── Observability configurations ────────────────────────────────────────

    private ObservabilityConfiguration createObservabilityConfiguration(JsonNode request, String region) {
        String name = requireText(request, "ObservabilityConfigurationName");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw invalidRequest(
                    "ObservabilityConfigurationName must be 4-32 characters of letters, digits, hyphens, or underscores.");
        }
        TraceConfiguration trace = readTraceConfiguration(request);
        Map<String, String> tags = readTags(request);

        List<ObservabilityConfiguration> existing = configsNamed(region, name);
        int nextRevision = existing.stream()
                .map(ObservabilityConfiguration::getObservabilityConfigurationRevision)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;

        for (ObservabilityConfiguration previous : existing) {
            if (Boolean.TRUE.equals(previous.getLatest()) && STATUS_ACTIVE.equals(previous.getStatus())) {
                previous.setLatest(false);
                store.put(previous.getObservabilityConfigurationArn(), previous);
            }
        }

        String arn = "arn:aws:apprunner:" + region + ":" + regionResolver.getAccountId()
                + ":observabilityconfiguration/" + name + "/" + nextRevision + "/"
                + UUID.randomUUID().toString().replace("-", "");

        ObservabilityConfiguration created = new ObservabilityConfiguration();
        created.setObservabilityConfigurationArn(arn);
        created.setObservabilityConfigurationName(name);
        created.setTraceConfiguration(trace);
        created.setObservabilityConfigurationRevision(nextRevision);
        created.setLatest(true);
        created.setStatus(STATUS_ACTIVE);
        created.setCreatedAt(Instant.now().getEpochSecond());
        created.setRegion(region);
        created.setTags(tags);
        store.put(arn, created);
        return created;
    }

    private ObservabilityConfiguration describeObservabilityConfiguration(JsonNode request, String region) {
        return requireConfig(requireText(request, "ObservabilityConfigurationArn"), region);
    }

    private ObservabilityConfiguration deleteObservabilityConfiguration(JsonNode request, String region) {
        ObservabilityConfiguration config = requireConfig(
                requireText(request, "ObservabilityConfigurationArn"), region);
        if (!STATUS_ACTIVE.equals(config.getStatus())) {
            throw notFound(config.getObservabilityConfigurationArn());
        }
        boolean wasLatest = Boolean.TRUE.equals(config.getLatest());
        config.setStatus(STATUS_INACTIVE);
        config.setLatest(false);
        config.setDeletedAt(Instant.now().getEpochSecond());
        store.put(config.getObservabilityConfigurationArn(), config);

        if (wasLatest) {
            configsNamed(region, config.getObservabilityConfigurationName()).stream()
                    .filter(c -> STATUS_ACTIVE.equals(c.getStatus()))
                    .max(Comparator.comparing(ObservabilityConfiguration::getObservabilityConfigurationRevision))
                    .ifPresent(nextLatest -> {
                        nextLatest.setLatest(true);
                        store.put(nextLatest.getObservabilityConfigurationArn(), nextLatest);
                    });
        }
        return config;
    }

    private JsonNode listObservabilityConfigurations(JsonNode request, String region) {
        String nameFilter = optionalText(request, "ObservabilityConfigurationName");
        boolean latestOnly = request.path("LatestOnly").asBoolean(false);
        int maxResults = readMaxResults(request);
        int offset = readOffset(request);

        List<ObservabilityConfiguration> matches = new ArrayList<>();
        for (ObservabilityConfiguration config : store.values()) {
            if (!region.equals(config.getRegion())) {
                continue;
            }
            if (!STATUS_ACTIVE.equals(config.getStatus())) {
                continue;
            }
            if (nameFilter != null && !nameFilter.equals(config.getObservabilityConfigurationName())) {
                continue;
            }
            if (latestOnly && !Boolean.TRUE.equals(config.getLatest())) {
                continue;
            }
            matches.add(config);
        }
        matches.sort(Comparator
                .comparing(ObservabilityConfiguration::getObservabilityConfigurationName,
                        Comparator.nullsLast(String::compareTo))
                .thenComparing(ObservabilityConfiguration::getObservabilityConfigurationRevision,
                        Comparator.nullsLast(Integer::compareTo)));

        int from = Math.min(offset, matches.size());
        int to = Math.min(from + maxResults, matches.size());
        List<ObservabilityConfiguration> page = matches.subList(from, to);

        ObjectNode response = mapper.createObjectNode();
        ArrayNode summaries = response.putArray("ObservabilityConfigurationSummaryList");
        for (ObservabilityConfiguration config : page) {
            ObjectNode summary = summaries.addObject();
            summary.put("ObservabilityConfigurationArn", config.getObservabilityConfigurationArn());
            summary.put("ObservabilityConfigurationName", config.getObservabilityConfigurationName());
            if (config.getObservabilityConfigurationRevision() != null) {
                summary.put("ObservabilityConfigurationRevision", config.getObservabilityConfigurationRevision());
            }
        }
        if (to < matches.size()) {
            response.put("NextToken", Integer.toString(to));
        }
        return response;
    }

    // ── Auto scaling configurations ─────────────────────────────────────────

    private AutoScalingConfiguration createAutoScalingConfiguration(JsonNode request, String region) {
        ensureDefaultAutoScaling(region);
        String name = requireText(request, "AutoScalingConfigurationName");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw invalidRequest(
                    "AutoScalingConfigurationName must be 4-32 characters of letters, digits, hyphens, or underscores.");
        }
        List<AutoScalingConfiguration> existing = autoScalingNamed(region, name);
        int nextRevision = existing.stream()
                .mapToInt(AutoScalingConfiguration::getAutoScalingConfigurationRevision)
                .max()
                .orElse(0) + 1;
        for (AutoScalingConfiguration previous : existing) {
            if (previous.isLatest() && previous.isActive()) {
                previous.setLatest(false);
                autoScalingStore.put(previous.getAutoScalingConfigurationArn(), previous);
            }
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        String arn = "arn:aws:apprunner:" + region + ":" + regionResolver.getAccountId()
                + ":autoscalingconfiguration/" + name + "/" + nextRevision + "/" + id;
        AutoScalingConfiguration created = new AutoScalingConfiguration();
        created.setAutoScalingConfigurationArn(arn);
        created.setAutoScalingConfigurationName(name);
        created.setAutoScalingConfigurationRevision(nextRevision);
        created.setConfigurationId(id);
        created.setLatest(true);
        created.setStatus(AutoScalingConfiguration.STATUS_ACTIVE);
        created.setMaxConcurrency(intOr(request, "MaxConcurrency", 100));
        created.setMinSize(intOr(request, "MinSize", 1));
        created.setMaxSize(intOr(request, "MaxSize", 25));
        created.setCreatedAt(Instant.now().getEpochSecond());
        created.setTags(readTags(request));
        autoScalingStore.put(arn, created);
        return created;
    }

    private AutoScalingConfiguration describeAutoScalingConfiguration(JsonNode request, String region) {
        ensureDefaultAutoScaling(region);
        return requireAutoScaling(requireText(request, "AutoScalingConfigurationArn"), region);
    }

    private AutoScalingConfiguration deleteAutoScalingConfiguration(JsonNode request, String region) {
        ensureDefaultAutoScaling(region);
        String arn = requireText(request, "AutoScalingConfigurationArn");
        boolean deleteAll = request.path("DeleteAllRevisions").asBoolean(false);
        if (deleteAll) {
            String name = autoScalingNameFromArn(arn);
            if (name == null) {
                throw invalidRequest("You cannot specify full auto scaling configuration ARN and DeleteAllRevisions as true at same time");
            }
            if (isFullAutoScalingArn(arn)) {
                throw invalidRequest("You cannot specify full auto scaling configuration ARN and DeleteAllRevisions as true at same time");
            }
            if (AutoScalingConfiguration.DEFAULT_NAME.equals(name)) {
                throw invalidRequest("The default auto scaling configuration cannot be deleted.");
            }
            List<AutoScalingConfiguration> named = autoScalingNamed(region, name);
            if (named.isEmpty()) {
                throw notFound(arn);
            }
            AutoScalingConfiguration latest = null;
            long now = Instant.now().getEpochSecond();
            for (AutoScalingConfiguration config : named) {
                config.setStatus(AutoScalingConfiguration.STATUS_INACTIVE);
                config.setLatest(false);
                config.setDeletedAt(now);
                autoScalingStore.put(config.getAutoScalingConfigurationArn(), config);
                if (latest == null
                        || config.getAutoScalingConfigurationRevision() > latest.getAutoScalingConfigurationRevision()) {
                    latest = config;
                }
            }
            if (latest != null) {
                latest.setLatest(true);
                autoScalingStore.put(latest.getAutoScalingConfigurationArn(), latest);
            }
            return latest;
        }
        AutoScalingConfiguration config = requireAutoScaling(arn, region);
        if (config.isDefault() || AutoScalingConfiguration.DEFAULT_NAME.equals(config.getAutoScalingConfigurationName())) {
            throw invalidRequest("The default auto scaling configuration cannot be deleted.");
        }
        if (!config.isActive()) {
            throw notFound(arn);
        }
        boolean wasLatest = config.isLatest();
        config.setStatus(AutoScalingConfiguration.STATUS_INACTIVE);
        config.setLatest(false);
        config.setDeletedAt(Instant.now().getEpochSecond());
        autoScalingStore.put(config.getAutoScalingConfigurationArn(), config);
        if (wasLatest) {
            autoScalingNamed(region, config.getAutoScalingConfigurationName()).stream()
                    .filter(AutoScalingConfiguration::isActive)
                    .max(Comparator.comparingInt(AutoScalingConfiguration::getAutoScalingConfigurationRevision))
                    .ifPresent(next -> {
                        next.setLatest(true);
                        autoScalingStore.put(next.getAutoScalingConfigurationArn(), next);
                    });
        }
        return config;
    }

    private JsonNode listAutoScalingConfigurations(JsonNode request, String region) {
        ensureDefaultAutoScaling(region);
        String nameFilter = optionalText(request, "AutoScalingConfigurationName");
        boolean latestOnly = request.path("LatestOnly").asBoolean(false);
        int maxResults = readMaxResults(request);
        int offset = readOffset(request);
        List<AutoScalingConfiguration> matches = new ArrayList<>();
        for (AutoScalingConfiguration config : autoScalingStore.values()) {
            if (!arnInRegion(config.getAutoScalingConfigurationArn(), region)) {
                continue;
            }
            if (nameFilter != null && !nameFilter.equals(config.getAutoScalingConfigurationName())) {
                continue;
            }
            if (latestOnly && !config.isLatest()) {
                continue;
            }
            matches.add(config);
        }
        matches.sort(Comparator
                .comparing(AutoScalingConfiguration::getAutoScalingConfigurationName,
                        Comparator.nullsLast(String::compareTo))
                .thenComparingInt(AutoScalingConfiguration::getAutoScalingConfigurationRevision));
        int from = Math.min(offset, matches.size());
        int to = Math.min(from + maxResults, matches.size());
        ObjectNode response = mapper.createObjectNode();
        ArrayNode summaries = response.putArray("AutoScalingConfigurationSummaryList");
        for (AutoScalingConfiguration config : matches.subList(from, to)) {
            ObjectNode summary = summaries.addObject();
            summary.put("AutoScalingConfigurationArn", config.getAutoScalingConfigurationArn());
            summary.put("AutoScalingConfigurationName", config.getAutoScalingConfigurationName());
            summary.put("AutoScalingConfigurationRevision", config.getAutoScalingConfigurationRevision());
            summary.put("Status", config.getStatus());
        }
        if (to < matches.size()) {
            response.put("NextToken", Integer.toString(to));
        }
        return response;
    }

    private void ensureDefaultAutoScaling(String region) {
        if (!autoScalingNamed(region, AutoScalingConfiguration.DEFAULT_NAME).isEmpty()) {
            return;
        }
        String id = "defaultconfiguration000000000000001";
        String arn = "arn:aws:apprunner:" + region + ":" + regionResolver.getAccountId()
                + ":autoscalingconfiguration/" + AutoScalingConfiguration.DEFAULT_NAME + "/1/" + id;
        AutoScalingConfiguration defaults = new AutoScalingConfiguration();
        defaults.setAutoScalingConfigurationArn(arn);
        defaults.setAutoScalingConfigurationName(AutoScalingConfiguration.DEFAULT_NAME);
        defaults.setAutoScalingConfigurationRevision(1);
        defaults.setConfigurationId(id);
        defaults.setLatest(true);
        defaults.setStatus(AutoScalingConfiguration.STATUS_ACTIVE);
        defaults.setDefault(true);
        defaults.setCreatedAt(Instant.now().getEpochSecond());
        autoScalingStore.put(arn, defaults);
    }

    private AutoScalingConfiguration requireAutoScaling(String arn, String region) {
        AutoScalingConfiguration config = autoScalingStore.get(arn).orElse(null);
        if (config == null || !arnInRegion(arn, region)) {
            throw notFound(arn);
        }
        return config;
    }

    private List<AutoScalingConfiguration> autoScalingNamed(String region, String name) {
        List<AutoScalingConfiguration> matches = new ArrayList<>();
        for (AutoScalingConfiguration config : autoScalingStore.values()) {
            if (arnInRegion(config.getAutoScalingConfigurationArn(), region)
                    && name.equals(config.getAutoScalingConfigurationName())) {
                matches.add(config);
            }
        }
        return matches;
    }

    private ObjectNode wrapAutoScaling(AutoScalingConfiguration config) {
        ObjectNode response = mapper.createObjectNode();
        ObjectNode node = mapper.createObjectNode();
        node.put("AutoScalingConfigurationArn", config.getAutoScalingConfigurationArn());
        node.put("AutoScalingConfigurationName", config.getAutoScalingConfigurationName());
        node.put("AutoScalingConfigurationRevision", config.getAutoScalingConfigurationRevision());
        node.put("Latest", config.isLatest());
        node.put("Status", config.getStatus());
        node.put("MaxConcurrency", config.getMaxConcurrency());
        node.put("MinSize", config.getMinSize());
        node.put("MaxSize", config.getMaxSize());
        node.put("CreatedAt", config.getCreatedAt());
        if (config.getDeletedAt() != null) {
            node.put("DeletedAt", config.getDeletedAt());
        }
        node.put("HasAssociatedService", config.isHasAssociatedService());
        node.put("IsDefault", config.isDefault());
        response.set("AutoScalingConfiguration", node);
        return response;
    }

    private static boolean isFullAutoScalingArn(String arn) {
        int idx = arn.indexOf(":autoscalingconfiguration/");
        if (idx < 0) {
            return false;
        }
        String resource = arn.substring(idx + ":autoscalingconfiguration/".length());
        return resource.split("/").length >= 3;
    }

    private static String autoScalingNameFromArn(String arn) {
        int idx = arn.indexOf(":autoscalingconfiguration/");
        if (idx < 0) {
            return null;
        }
        String resource = arn.substring(idx + ":autoscalingconfiguration/".length());
        String[] parts = resource.split("/");
        return parts.length == 0 ? null : parts[0];
    }

    // ── VPC connectors ──────────────────────────────────────────────────────

    private VpcConnector createVpcConnector(JsonNode request, String region) {
        String name = requireText(request, "VpcConnectorName");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw invalidRequest(
                    "VpcConnectorName must be 4-32 characters of letters, digits, hyphens, or underscores.");
        }
        List<VpcConnector> existing = vpcNamed(region, name);
        for (VpcConnector connector : existing) {
            if (connector.isActive()) {
                throw invalidRequest("VPC connector " + name + " already exists.");
            }
        }
        int nextRevision = existing.stream()
                .mapToInt(VpcConnector::getVpcConnectorRevision)
                .max()
                .orElse(0) + 1;
        List<String> subnets = stringList(request.get("Subnets"));
        if (subnets.isEmpty()) {
            throw invalidRequest("Subnets is required.");
        }
        String arn = "arn:aws:apprunner:" + region + ":" + regionResolver.getAccountId()
                + ":vpcconnector/" + name + "/" + nextRevision + "/"
                + UUID.randomUUID().toString().replace("-", "");
        VpcConnector created = new VpcConnector();
        created.setVpcConnectorName(name);
        created.setVpcConnectorArn(arn);
        created.setVpcConnectorRevision(nextRevision);
        created.setSubnets(subnets);
        created.setSecurityGroups(stringList(request.get("SecurityGroups")));
        created.setStatus(STATUS_ACTIVE);
        created.setCreatedAt(Instant.now().getEpochSecond());
        created.setRegion(region);
        created.setTags(readTags(request));
        vpcStore.put(arn, created);
        return created;
    }

    private VpcConnector describeVpcConnector(JsonNode request, String region) {
        return requireVpc(requireText(request, "VpcConnectorArn"), region);
    }

    private VpcConnector deleteVpcConnector(JsonNode request, String region) {
        VpcConnector connector = requireVpc(requireText(request, "VpcConnectorArn"), region);
        if (!connector.isActive()) {
            throw notFound(connector.getVpcConnectorArn());
        }
        connector.setStatus(STATUS_INACTIVE);
        connector.setDeletedAt(Instant.now().getEpochSecond());
        vpcStore.put(connector.getVpcConnectorArn(), connector);
        return connector;
    }

    private JsonNode listVpcConnectors(JsonNode request, String region) {
        int maxResults = readMaxResults(request);
        int offset = readOffset(request);
        List<VpcConnector> matches = new ArrayList<>();
        for (VpcConnector connector : vpcStore.values()) {
            if (region.equals(connector.getRegion())) {
                matches.add(connector);
            }
        }
        matches.sort(Comparator.comparing(VpcConnector::getVpcConnectorName,
                Comparator.nullsLast(String::compareTo)));
        int from = Math.min(offset, matches.size());
        int to = Math.min(from + maxResults, matches.size());
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("VpcConnectors");
        for (VpcConnector connector : matches.subList(from, to)) {
            list.add(vpcNode(connector));
        }
        if (to < matches.size()) {
            response.put("NextToken", Integer.toString(to));
        }
        return response;
    }

    private VpcConnector requireVpc(String arn, String region) {
        VpcConnector connector = vpcStore.get(arn).orElse(null);
        if (connector == null || !region.equals(connector.getRegion())) {
            throw notFound(arn);
        }
        return connector;
    }

    private List<VpcConnector> vpcNamed(String region, String name) {
        List<VpcConnector> matches = new ArrayList<>();
        for (VpcConnector connector : vpcStore.values()) {
            if (region.equals(connector.getRegion()) && name.equals(connector.getVpcConnectorName())) {
                matches.add(connector);
            }
        }
        return matches;
    }

    private ObjectNode wrapVpc(VpcConnector connector) {
        ObjectNode response = mapper.createObjectNode();
        response.set("VpcConnector", vpcNode(connector));
        return response;
    }

    private ObjectNode vpcNode(VpcConnector connector) {
        ObjectNode node = mapper.createObjectNode();
        node.put("VpcConnectorName", connector.getVpcConnectorName());
        node.put("VpcConnectorArn", connector.getVpcConnectorArn());
        node.put("VpcConnectorRevision", connector.getVpcConnectorRevision());
        ArrayNode subnets = node.putArray("Subnets");
        for (String subnet : connector.getSubnets()) {
            subnets.add(subnet);
        }
        ArrayNode groups = node.putArray("SecurityGroups");
        for (String group : connector.getSecurityGroups()) {
            groups.add(group);
        }
        node.put("Status", connector.getStatus());
        node.put("CreatedAt", connector.getCreatedAt());
        if (connector.getDeletedAt() != null) {
            node.put("DeletedAt", connector.getDeletedAt());
        }
        return node;
    }

    // ── Tags ────────────────────────────────────────────────────────────────

    private JsonNode listTagsForResource(JsonNode request, String region) {
        ObjectNode response = mapper.createObjectNode();
        response.set("Tags", tagsNode(tagsOf(requireText(request, "ResourceArn"), region)));
        return response;
    }

    private JsonNode tagResource(JsonNode request, String region) {
        JsonNode tagsNode = request.get("Tags");
        if (tagsNode == null || !tagsNode.isArray()) {
            throw invalidRequest("Tags is required.");
        }
        Map<String, String> extra = new LinkedHashMap<>();
        for (JsonNode tag : tagsNode) {
            if (tag == null || !tag.isObject()) {
                continue;
            }
            String key = optionalText(tag, "Key");
            if (key == null) {
                continue;
            }
            extra.put(key, tag.path("Value").isMissingNode() || tag.path("Value").isNull()
                    ? "" : tag.path("Value").asText());
        }
        mutateTags(requireText(request, "ResourceArn"), region, tags -> tags.putAll(extra));
        return mapper.createObjectNode();
    }

    private JsonNode untagResource(JsonNode request, String region) {
        JsonNode keysNode = request.get("TagKeys");
        if (keysNode == null || !keysNode.isArray()) {
            throw invalidRequest("TagKeys is required.");
        }
        List<String> keys = new ArrayList<>();
        for (JsonNode key : keysNode) {
            if (key != null && key.isTextual()) {
                keys.add(key.asText());
            }
        }
        mutateTags(requireText(request, "ResourceArn"), region, tags -> keys.forEach(tags::remove));
        return mapper.createObjectNode();
    }

    private Map<String, String> tagsOf(String arn, String region) {
        if (arn.contains(":observabilityconfiguration/")) {
            return new LinkedHashMap<>(requireConfig(arn, region).getTags());
        }
        if (arn.contains(":autoscalingconfiguration/")) {
            return new LinkedHashMap<>(requireAutoScaling(arn, region).getTags());
        }
        if (arn.contains(":vpcconnector/")) {
            return new LinkedHashMap<>(requireVpc(arn, region).getTags());
        }
        if (arn.contains(":service/")) {
            return new LinkedHashMap<>(requireService(arn, region).getTags());
        }
        throw notFound(arn);
    }

    private void mutateTags(String arn, String region, Consumer<Map<String, String>> mutator) {
        if (arn.contains(":observabilityconfiguration/")) {
            ObservabilityConfiguration config = requireConfig(arn, region);
            Map<String, String> tags = new LinkedHashMap<>(config.getTags());
            mutator.accept(tags);
            config.setTags(tags);
            store.put(config.getObservabilityConfigurationArn(), config);
            return;
        }
        if (arn.contains(":autoscalingconfiguration/")) {
            AutoScalingConfiguration config = requireAutoScaling(arn, region);
            Map<String, String> tags = new LinkedHashMap<>(config.getTags());
            mutator.accept(tags);
            config.setTags(tags);
            autoScalingStore.put(config.getAutoScalingConfigurationArn(), config);
            return;
        }
        if (arn.contains(":vpcconnector/")) {
            VpcConnector connector = requireVpc(arn, region);
            Map<String, String> tags = new LinkedHashMap<>(connector.getTags());
            mutator.accept(tags);
            connector.setTags(tags);
            vpcStore.put(connector.getVpcConnectorArn(), connector);
            return;
        }
        if (arn.contains(":service/")) {
            AppRunnerServiceRecord service = requireService(arn, region);
            Map<String, String> tags = new LinkedHashMap<>(service.getTags());
            mutator.accept(tags);
            service.setTags(tags);
            serviceStore.put(service.getServiceArn(), service);
            return;
        }
        throw notFound(arn);
    }

    private ObjectNode wrap(String field, ObservabilityConfiguration config) {
        ObjectNode response = mapper.createObjectNode();
        response.set(field, mapper.valueToTree(config));
        return response;
    }

    private ArrayNode tagsNode(Map<String, String> tags) {
        ArrayNode node = mapper.createArrayNode();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tag = node.addObject();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue());
        }
        return node;
    }

    private ObservabilityConfiguration requireConfig(String arn, String region) {
        ObservabilityConfiguration config = store.get(arn).orElse(null);
        if (config == null || !region.equals(config.getRegion())) {
            throw notFound(arn);
        }
        return config;
    }

    private List<ObservabilityConfiguration> configsNamed(String region, String name) {
        List<ObservabilityConfiguration> matches = new ArrayList<>();
        for (ObservabilityConfiguration config : store.values()) {
            if (region.equals(config.getRegion()) && name.equals(config.getObservabilityConfigurationName())) {
                matches.add(config);
            }
        }
        return matches;
    }

    private TraceConfiguration readTraceConfiguration(JsonNode request) {
        JsonNode trace = request.get("TraceConfiguration");
        if (trace == null || trace.isNull()) {
            return null;
        }
        if (!trace.isObject()) {
            throw invalidRequest("TraceConfiguration must be an object.");
        }
        String vendor = optionalText(trace, "Vendor");
        if (vendor == null) {
            throw invalidRequest("TraceConfiguration.Vendor is required.");
        }
        if (!VENDOR_XRAY.equals(vendor)) {
            throw invalidRequest("TraceConfiguration.Vendor must be AWSXRAY.");
        }
        return new TraceConfiguration(vendor);
    }

    private Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode tagsNode = request.get("Tags");
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isArray()) {
            throw invalidRequest("Tags must be a list.");
        }
        for (JsonNode tag : tagsNode) {
            if (tag == null || !tag.isObject()) {
                continue;
            }
            String key = optionalText(tag, "Key");
            if (key == null) {
                continue;
            }
            String value = tag.path("Value").isMissingNode() || tag.path("Value").isNull()
                    ? ""
                    : tag.path("Value").asText();
            tags.put(key, value);
        }
        return tags;
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private int intOr(JsonNode request, String field, int fallback) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return fallback;
        }
        return value.asInt();
    }

    private int readMaxResults(JsonNode request) {
        JsonNode value = request.get("MaxResults");
        if (value == null || value.isNull()) {
            return DEFAULT_MAX_RESULTS;
        }
        if (!value.isNumber()) {
            throw invalidRequest("MaxResults must be an integer between 1 and 20.");
        }
        int maxResults = value.asInt();
        if (maxResults < 1 || maxResults > MAX_RESULTS) {
            throw invalidRequest("MaxResults must be an integer between 1 and 20.");
        }
        return maxResults;
    }

    private int readOffset(JsonNode request) {
        String token = optionalText(request, "NextToken");
        if (token == null) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(token);
            if (offset < 0) {
                throw invalidRequest("Invalid NextToken.");
            }
            return offset;
        } catch (NumberFormatException e) {
            throw invalidRequest("Invalid NextToken.");
        }
    }

    private String requireText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null) {
            throw invalidRequest(field + " is required.");
        }
        return value;
    }

    private String optionalText(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static boolean arnInRegion(String arn, String region) {
        return arn != null && arn.startsWith("arn:aws:apprunner:" + region + ":");
    }

    private static AwsException invalidRequest(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static AwsException notFound(String arn) {
        return new AwsException("ResourceNotFoundException",
                "Resource with the specified ARN (" + arn + ") is not found.", 400);
    }

    private void ensureLogGroups(AppRunnerServiceRecord service) {
        if (logsService == null || !logsService.isResolvable()) {
            return;
        }
        CloudWatchLogsService logs = logsService.get();
        for (String name : List.of(
                "/aws/apprunner/" + service.getServiceName() + "/" + service.getServiceId() + "/application",
                "/aws/apprunner/" + service.getServiceName() + "/" + service.getServiceId() + "/service")) {
            try {
                logs.createLogGroup(name, null, null, service.getRegion());
            } catch (AwsException ignored) {
                // already exists
            }
        }
    }

    private void startContainer(AppRunnerServiceRecord service) {
        if (containers != null && containers.isResolvable()) {
            containers.get().start(service);
        }
    }

    private void stopContainer(String serviceId) {
        if (containers != null && containers.isResolvable()) {
            containers.get().stop(serviceId);
        }
    }

    private void stopAllContainers() {
        if (containers != null && containers.isResolvable()) {
            containers.get().stopManagedContainers();
        }
    }

    private String localServiceUrl(String serviceId, String region) {
        int port = 4566;
        if (emulatorConfig != null && emulatorConfig.isResolvable()) {
            EmulatorConfig config = emulatorConfig.get();
            try {
                java.net.URI uri = java.net.URI.create(config.effectiveBaseUrl());
                if (uri.getPort() > 0) {
                    port = uri.getPort();
                } else if (config.port() > 0) {
                    port = config.port();
                }
            } catch (Exception ignored) {
                if (config.port() > 0) {
                    port = config.port();
                }
            }
        }
        return serviceId + "." + region + ".awsapprunner.com.localhost:" + port;
    }
}
