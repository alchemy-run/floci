package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerCluster;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerClusterSchedulerConfig;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerComputeQuota;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEndpoint;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEndpointConfig;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerFeatureGroup;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SageMaker control plane (JSON 1.1, target prefix {@code SageMaker.}).
 *
 * <p>HyperPod clusters settle immediately to {@code InService} so local reconcilers
 * do not wait on the live 10–25 minute provisioner. Feature groups and compute
 * quotas settle to {@code Created} immediately for the same reason.
 */
@ApplicationScoped
public class SageMakerService {

    static final String SERVICE = "sagemaker";
    private static final TypeReference<Map<String, SageMakerCluster>> CLUSTER_STORE_TYPE =
            new TypeReference<>() {
            };
    private static final TypeReference<Map<String, SageMakerFeatureGroup>> FEATURE_GROUP_STORE_TYPE =
            new TypeReference<>() {
            };
    private static final TypeReference<Map<String, SageMakerComputeQuota>> COMPUTE_QUOTA_STORE_TYPE =
            new TypeReference<>() {
            };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {
    };

    private final StorageBackend<String, SageMakerCluster> clusters;
    private final StorageBackend<String, SageMakerFeatureGroup> featureGroups;
    private final StorageBackend<String, SageMakerComputeQuota> computeQuotas;
    private final StorageBackend<String, SageMakerModel> models = new InMemoryStorage<>();
    private final StorageBackend<String, SageMakerEndpointConfig> endpointConfigs = new InMemoryStorage<>();
    private final StorageBackend<String, SageMakerEndpoint> endpoints = new InMemoryStorage<>();
    private final StorageBackend<String, SageMakerClusterSchedulerConfig> schedulerConfigs =
            new InMemoryStorage<>();
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SageMakerService(StorageFactory storageFactory, RegionResolver regionResolver,
                            ObjectMapper objectMapper) {
        this(storageFactory.create(SERVICE, "sagemaker-clusters.json", CLUSTER_STORE_TYPE),
                storageFactory.create(SERVICE, "sagemaker-feature-groups.json", FEATURE_GROUP_STORE_TYPE),
                storageFactory.create(SERVICE, "sagemaker-compute-quotas.json", COMPUTE_QUOTA_STORE_TYPE),
                regionResolver, objectMapper);
    }

    SageMakerService(StorageBackend<String, SageMakerCluster> clusters,
                     StorageBackend<String, SageMakerFeatureGroup> featureGroups,
                     StorageBackend<String, SageMakerComputeQuota> computeQuotas,
                     RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.clusters = clusters;
        this.featureGroups = featureGroups;
        this.computeQuotas = computeQuotas;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized ObjectNode createCluster(JsonNode request, String region) {
        String name = requireText(request, "ClusterName");
        SageMakerCluster existing = clusters.get(storageKey(region, name)).orElse(null);
        if (existing != null) {
            throw new AwsException("ResourceInUse",
                    "Cluster " + name + " is already in use.", 400);
        }
        SageMakerCluster cluster = new SageMakerCluster();
        cluster.setClusterName(name);
        cluster.setClusterArn(regionResolver.buildArn("sagemaker", region, "cluster/" + name));
        cluster.setClusterStatus("InService");
        cluster.setRegion(region);
        cluster.setCreationTime(Instant.now().getEpochSecond());
        cluster.setNodeRecovery(textOrDefault(request, "NodeRecovery", "Automatic"));
        cluster.setNodeProvisioningMode(textOrNull(request, "NodeProvisioningMode"));
        cluster.setClusterRole(textOrNull(request, "ClusterRole"));
        cluster.setInstanceGroups(toGroupDetails(request.get("InstanceGroups")));
        cluster.setRestrictedInstanceGroups(toGroupDetails(request.get("RestrictedInstanceGroups")));
        cluster.setRestrictedInstanceGroupsConfig(toMap(request.get("RestrictedInstanceGroupsConfig")));
        cluster.setVpcConfig(toMap(request.get("VpcConfig")));
        cluster.setOrchestrator(toMap(request.get("Orchestrator")));
        cluster.setTieredStorageConfig(toMap(request.get("TieredStorageConfig")));
        cluster.setAutoScaling(toMap(request.get("AutoScaling")));
        cluster.getTags().putAll(readTags(request.get("Tags")));
        clusters.put(storageKey(region, name), cluster);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ClusterArn", cluster.getClusterArn());
        return response;
    }

    public synchronized ObjectNode describeCluster(JsonNode request, String region) {
        SageMakerCluster cluster = requireCluster(region, requireText(request, "ClusterName"));
        return toDescribeNode(cluster);
    }

    public synchronized ObjectNode updateCluster(JsonNode request, String region) {
        SageMakerCluster cluster = requireCluster(region, requireText(request, "ClusterName"));
        if (request.has("InstanceGroups") || request.has("InstanceGroupsToDelete")) {
            cluster.setInstanceGroups(mergeGroups(
                    cluster.getInstanceGroups(),
                    toGroupDetails(request.get("InstanceGroups")),
                    stringList(request.get("InstanceGroupsToDelete"))));
        }
        if (request.has("RestrictedInstanceGroups")) {
            cluster.setRestrictedInstanceGroups(toGroupDetails(request.get("RestrictedInstanceGroups")));
        }
        if (request.has("RestrictedInstanceGroupsConfig")) {
            cluster.setRestrictedInstanceGroupsConfig(toMap(request.get("RestrictedInstanceGroupsConfig")));
        }
        if (request.has("TieredStorageConfig")) {
            cluster.setTieredStorageConfig(toMap(request.get("TieredStorageConfig")));
        }
        if (request.hasNonNull("NodeRecovery")) {
            cluster.setNodeRecovery(request.get("NodeRecovery").asText());
        }
        if (request.hasNonNull("NodeProvisioningMode")) {
            cluster.setNodeProvisioningMode(request.get("NodeProvisioningMode").asText());
        }
        if (request.hasNonNull("ClusterRole")) {
            cluster.setClusterRole(request.get("ClusterRole").asText());
        }
        if (request.has("AutoScaling")) {
            cluster.setAutoScaling(toMap(request.get("AutoScaling")));
        }
        if (request.has("Orchestrator")) {
            cluster.setOrchestrator(toMap(request.get("Orchestrator")));
        }
        cluster.setClusterStatus("InService");
        clusters.put(storageKey(region, cluster.getClusterName()), cluster);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ClusterArn", cluster.getClusterArn());
        return response;
    }

    public synchronized ObjectNode deleteCluster(JsonNode request, String region) {
        SageMakerCluster cluster = requireCluster(region, requireText(request, "ClusterName"));
        clusters.delete(storageKey(region, cluster.getClusterName()));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ClusterArn", cluster.getClusterArn());
        return response;
    }

    public synchronized ObjectNode listClusters(JsonNode request, String region) {
        List<SageMakerCluster> all = clusters.scan(key -> key.startsWith(region + "::"));
        all.sort(Comparator.comparing(SageMakerCluster::getClusterName));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("ClusterSummaries");
        for (SageMakerCluster cluster : all) {
            ObjectNode summary = summaries.addObject();
            summary.put("ClusterArn", cluster.getClusterArn());
            summary.put("ClusterName", cluster.getClusterName());
            summary.put("CreationTime", cluster.getCreationTime());
            summary.put("ClusterStatus", cluster.getClusterStatus());
        }
        return response;
    }

    public synchronized ObjectNode listClusterNodes(JsonNode request, String region) {
        requireCluster(region, requireText(request, "ClusterName"));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("ClusterNodeSummaries");
        return response;
    }

    public synchronized ObjectNode createClusterSchedulerConfig(JsonNode request, String region) {
        String name = requireText(request, "Name");
        String clusterArn = requireText(request, "ClusterArn");
        if (findSchedulerByName(region, name) != null) {
            throw new AwsException("ConflictException",
                    "Cluster scheduler config " + name + " already exists.", 400);
        }
        if (findSchedulerByClusterArn(region, clusterArn) != null) {
            throw new AwsException("ConflictException",
                    "A cluster scheduler config already exists for cluster " + clusterArn + ".", 400);
        }
        String id = newSchedulerConfigId(region);
        long now = Instant.now().getEpochSecond();
        SageMakerClusterSchedulerConfig config = new SageMakerClusterSchedulerConfig();
        config.setClusterSchedulerConfigId(id);
        config.setClusterSchedulerConfigArn(regionResolver.buildArn(
                "sagemaker", region, "cluster-scheduler-config/" + id));
        config.setName(name);
        config.setClusterArn(clusterArn);
        config.setRegion(region);
        config.setStatus("Created");
        config.setDescription(textOrNull(request, "Description"));
        config.setClusterSchedulerConfigVersion(1);
        config.setCreationTime(now);
        config.setLastModifiedTime(now);
        config.setSchedulerConfig(toMap(request.get("SchedulerConfig")));
        config.getTags().putAll(readTags(request.get("Tags")));
        schedulerConfigs.put(storageKey(region, id), config);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ClusterSchedulerConfigArn", config.getClusterSchedulerConfigArn());
        response.put("ClusterSchedulerConfigId", config.getClusterSchedulerConfigId());
        return response;
    }

    public synchronized ObjectNode describeClusterSchedulerConfig(JsonNode request, String region) {
        return toDescribeSchedulerConfig(
                requireSchedulerConfig(region, requireText(request, "ClusterSchedulerConfigId")));
    }

    public synchronized ObjectNode updateClusterSchedulerConfig(JsonNode request, String region) {
        SageMakerClusterSchedulerConfig config =
                requireSchedulerConfig(region, requireText(request, "ClusterSchedulerConfigId"));
        if (request.hasNonNull("TargetVersion")
                && request.get("TargetVersion").asInt() != config.getClusterSchedulerConfigVersion()) {
            throw new AwsException("ConflictException",
                    "TargetVersion " + request.get("TargetVersion").asInt()
                            + " does not match current version "
                            + config.getClusterSchedulerConfigVersion() + ".", 400);
        }
        if (request.has("SchedulerConfig")) {
            config.setSchedulerConfig(toMap(request.get("SchedulerConfig")));
        }
        if (request.has("Description")) {
            config.setDescription(textOrNull(request, "Description"));
        }
        config.setClusterSchedulerConfigVersion(config.getClusterSchedulerConfigVersion() + 1);
        config.setStatus("Updated");
        config.setLastModifiedTime(Instant.now().getEpochSecond());
        schedulerConfigs.put(storageKey(region, config.getClusterSchedulerConfigId()), config);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ClusterSchedulerConfigArn", config.getClusterSchedulerConfigArn());
        response.put("ClusterSchedulerConfigVersion", config.getClusterSchedulerConfigVersion());
        return response;
    }

    public synchronized ObjectNode deleteClusterSchedulerConfig(JsonNode request, String region) {
        SageMakerClusterSchedulerConfig config =
                requireSchedulerConfig(region, requireText(request, "ClusterSchedulerConfigId"));
        schedulerConfigs.delete(storageKey(region, config.getClusterSchedulerConfigId()));
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listClusterSchedulerConfigs(JsonNode request, String region) {
        String nameContains = textOrNull(request, "NameContains");
        String clusterArn = textOrNull(request, "ClusterArn");
        String status = textOrNull(request, "Status");
        List<SageMakerClusterSchedulerConfig> all =
                schedulerConfigs.scan(key -> key.startsWith(region + "::"));
        all.sort(Comparator.comparing(
                SageMakerClusterSchedulerConfig::getName, Comparator.nullsLast(String::compareTo)));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("ClusterSchedulerConfigSummaries");
        for (SageMakerClusterSchedulerConfig config : all) {
            if (nameContains != null && (config.getName() == null || !config.getName().contains(nameContains))) {
                continue;
            }
            if (clusterArn != null && !clusterArn.equals(config.getClusterArn())) {
                continue;
            }
            if (status != null && !status.equals(config.getStatus())) {
                continue;
            }
            ObjectNode summary = summaries.addObject();
            summary.put("ClusterSchedulerConfigArn", config.getClusterSchedulerConfigArn());
            summary.put("ClusterSchedulerConfigId", config.getClusterSchedulerConfigId());
            summary.put("ClusterSchedulerConfigVersion", config.getClusterSchedulerConfigVersion());
            summary.put("Name", config.getName());
            summary.put("CreationTime", config.getCreationTime());
            summary.put("LastModifiedTime", config.getLastModifiedTime());
            summary.put("Status", config.getStatus());
            if (config.getClusterArn() != null) {
                summary.put("ClusterArn", config.getClusterArn());
            }
        }
        return response;
    }

    public synchronized ObjectNode createComputeQuota(JsonNode request, String region) {
        String name = requireText(request, "Name");
        if (findQuotaByName(region, name) != null) {
            throw new AwsException("ConflictException",
                    "Compute quota " + name + " already exists.", 400);
        }
        Map<String, Object> target = toMap(request.get("ComputeQuotaTarget"));
        if (target == null) {
            throw new AwsException("ValidationException",
                    "ComputeQuotaTarget is a required parameter.", 400);
        }
        String id = newQuotaId(region);
        long now = Instant.now().getEpochSecond();
        SageMakerComputeQuota quota = new SageMakerComputeQuota();
        quota.setComputeQuotaId(id);
        quota.setComputeQuotaArn(regionResolver.buildArn("sagemaker", region, "compute-quota/" + id));
        quota.setName(name);
        quota.setDescription(textOrNull(request, "Description"));
        quota.setComputeQuotaVersion(1);
        quota.setStatus("Created");
        quota.setClusterArn(requireText(request, "ClusterArn"));
        quota.setActivationState(textOrDefault(request, "ActivationState", "Enabled"));
        quota.setRegion(region);
        quota.setCreationTime(now);
        quota.setLastModifiedTime(now);
        quota.setComputeQuotaConfig(toMap(request.get("ComputeQuotaConfig")));
        quota.setComputeQuotaTarget(target);
        quota.getTags().putAll(readTags(request.get("Tags")));
        computeQuotas.put(storageKey(region, id), quota);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ComputeQuotaArn", quota.getComputeQuotaArn());
        response.put("ComputeQuotaId", quota.getComputeQuotaId());
        return response;
    }

    public synchronized ObjectNode describeComputeQuota(JsonNode request, String region) {
        return toDescribeQuota(requireQuota(region, requireText(request, "ComputeQuotaId")));
    }

    public synchronized ObjectNode updateComputeQuota(JsonNode request, String region) {
        SageMakerComputeQuota quota = requireQuota(region, requireText(request, "ComputeQuotaId"));
        if (request.hasNonNull("TargetVersion")
                && request.get("TargetVersion").asInt() != quota.getComputeQuotaVersion()) {
            throw new AwsException("ConflictException",
                    "TargetVersion " + request.get("TargetVersion").asInt()
                            + " does not match current version "
                            + quota.getComputeQuotaVersion() + ".", 400);
        }
        if (request.has("ComputeQuotaConfig")) {
            quota.setComputeQuotaConfig(toMap(request.get("ComputeQuotaConfig")));
        }
        if (request.has("ComputeQuotaTarget")) {
            Map<String, Object> target = toMap(request.get("ComputeQuotaTarget"));
            if (target != null) {
                quota.setComputeQuotaTarget(target);
            }
        }
        if (request.hasNonNull("ActivationState")) {
            quota.setActivationState(request.get("ActivationState").asText());
        }
        if (request.has("Description")) {
            quota.setDescription(textOrNull(request, "Description"));
        }
        quota.setComputeQuotaVersion(quota.getComputeQuotaVersion() + 1);
        quota.setStatus("Updated");
        quota.setLastModifiedTime(Instant.now().getEpochSecond());
        computeQuotas.put(storageKey(region, quota.getComputeQuotaId()), quota);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ComputeQuotaArn", quota.getComputeQuotaArn());
        response.put("ComputeQuotaVersion", quota.getComputeQuotaVersion());
        return response;
    }

    public synchronized ObjectNode deleteComputeQuota(JsonNode request, String region) {
        SageMakerComputeQuota quota = requireQuota(region, requireText(request, "ComputeQuotaId"));
        computeQuotas.delete(storageKey(region, quota.getComputeQuotaId()));
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listComputeQuotas(JsonNode request, String region) {
        String nameContains = textOrNull(request, "NameContains");
        String clusterArn = textOrNull(request, "ClusterArn");
        String status = textOrNull(request, "Status");
        List<SageMakerComputeQuota> all = computeQuotas.scan(key -> key.startsWith(region + "::"));
        all.sort(Comparator.comparing(SageMakerComputeQuota::getName, Comparator.nullsLast(String::compareTo)));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("ComputeQuotaSummaries");
        for (SageMakerComputeQuota quota : all) {
            if (nameContains != null && (quota.getName() == null || !quota.getName().contains(nameContains))) {
                continue;
            }
            if (clusterArn != null && !clusterArn.equals(quota.getClusterArn())) {
                continue;
            }
            if (status != null && !status.equals(quota.getStatus())) {
                continue;
            }
            ObjectNode summary = summaries.addObject();
            summary.put("ComputeQuotaArn", quota.getComputeQuotaArn());
            summary.put("ComputeQuotaId", quota.getComputeQuotaId());
            summary.put("Name", quota.getName());
            summary.put("ComputeQuotaVersion", quota.getComputeQuotaVersion());
            summary.put("Status", quota.getStatus());
            if (quota.getClusterArn() != null) {
                summary.put("ClusterArn", quota.getClusterArn());
            }
            if (quota.getComputeQuotaConfig() != null) {
                summary.set("ComputeQuotaConfig", objectMapper.valueToTree(quota.getComputeQuotaConfig()));
            }
            if (quota.getComputeQuotaTarget() != null) {
                summary.set("ComputeQuotaTarget", objectMapper.valueToTree(quota.getComputeQuotaTarget()));
            }
            if (quota.getActivationState() != null) {
                summary.put("ActivationState", quota.getActivationState());
            }
            summary.put("CreationTime", quota.getCreationTime());
            summary.put("LastModifiedTime", quota.getLastModifiedTime());
        }
        return response;
    }

    public synchronized ObjectNode createModel(JsonNode request, String region) {
        String name = requireText(request, "ModelName");
        if (models.get(storageKey(region, name)).isPresent()) {
            throw new AwsException("ValidationException",
                    "Cannot create already existing model \"" + name + "\".", 400);
        }
        SageMakerModel model = new SageMakerModel();
        model.setModelName(name);
        model.setModelArn(regionResolver.buildArn("sagemaker", region, "model/" + name));
        model.setRegion(region);
        model.setExecutionRoleArn(requireText(request, "ExecutionRoleArn"));
        model.setPrimaryContainer(toMap(request.get("PrimaryContainer")));
        model.setContainers(toObjectList(request.get("Containers")));
        model.setInferenceExecutionConfig(toMap(request.get("InferenceExecutionConfig")));
        model.setVpcConfig(toMap(request.get("VpcConfig")));
        model.setEnableNetworkIsolation(request.path("EnableNetworkIsolation").asBoolean(false));
        model.setCreationTime(Instant.now().getEpochSecond());
        model.getTags().putAll(readTags(request.get("Tags")));
        models.put(storageKey(region, name), model);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ModelArn", model.getModelArn());
        return response;
    }

    public synchronized ObjectNode describeModel(JsonNode request, String region) {
        SageMakerModel model = requireModel(region, requireText(request, "ModelName"));
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ModelName", model.getModelName());
        node.put("ModelArn", model.getModelArn());
        node.put("CreationTime", model.getCreationTime());
        if (model.getExecutionRoleArn() != null) {
            node.put("ExecutionRoleArn", model.getExecutionRoleArn());
        }
        if (model.getPrimaryContainer() != null) {
            node.set("PrimaryContainer", objectMapper.valueToTree(model.getPrimaryContainer()));
        }
        if (model.getContainers() != null && !model.getContainers().isEmpty()) {
            node.set("Containers", objectMapper.valueToTree(model.getContainers()));
        }
        if (model.getInferenceExecutionConfig() != null) {
            node.set("InferenceExecutionConfig", objectMapper.valueToTree(model.getInferenceExecutionConfig()));
        }
        if (model.getVpcConfig() != null) {
            node.set("VpcConfig", objectMapper.valueToTree(model.getVpcConfig()));
        }
        node.put("EnableNetworkIsolation", model.isEnableNetworkIsolation());
        return node;
    }

    public synchronized ObjectNode deleteModel(JsonNode request, String region) {
        SageMakerModel model = requireModel(region, requireText(request, "ModelName"));
        models.delete(storageKey(region, model.getModelName()));
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listModels(JsonNode request, String region) {
        String nameContains = textOrNull(request, "NameContains");
        List<SageMakerModel> all = models.scan(key -> key.startsWith(region + "::"));
        all.sort(Comparator.comparing(SageMakerModel::getModelName));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("Models");
        for (SageMakerModel model : all) {
            if (nameContains != null
                    && !model.getModelName().toLowerCase().contains(nameContains.toLowerCase())) {
                continue;
            }
            ObjectNode summary = summaries.addObject();
            summary.put("ModelName", model.getModelName());
            summary.put("ModelArn", model.getModelArn());
            summary.put("CreationTime", model.getCreationTime());
        }
        return response;
    }

    public synchronized ObjectNode createEndpointConfig(JsonNode request, String region) {
        String name = requireText(request, "EndpointConfigName");
        if (endpointConfigs.get(storageKey(region, name)).isPresent()) {
            throw new AwsException("ValidationException",
                    "Cannot create already existing endpoint configuration \"" + name + "\".", 400);
        }
        List<Map<String, Object>> variants = toObjectList(request.get("ProductionVariants"));
        if (variants.isEmpty()) {
            throw new AwsException("ValidationException",
                    "ProductionVariants is a required parameter.", 400);
        }
        SageMakerEndpointConfig config = new SageMakerEndpointConfig();
        config.setEndpointConfigName(name);
        config.setEndpointConfigArn(regionResolver.buildArn("sagemaker", region, "endpoint-config/" + name));
        config.setRegion(region);
        config.setProductionVariants(variants);
        config.setShadowProductionVariants(toObjectList(request.get("ShadowProductionVariants")));
        config.setDataCaptureConfig(toMap(request.get("DataCaptureConfig")));
        config.setKmsKeyId(textOrNull(request, "KmsKeyId"));
        config.setAsyncInferenceConfig(toMap(request.get("AsyncInferenceConfig")));
        config.setExplainerConfig(toMap(request.get("ExplainerConfig")));
        config.setExecutionRoleArn(textOrNull(request, "ExecutionRoleArn"));
        config.setVpcConfig(toMap(request.get("VpcConfig")));
        config.setEnableNetworkIsolation(request.path("EnableNetworkIsolation").asBoolean(false));
        config.setCreationTime(Instant.now().getEpochSecond());
        config.getTags().putAll(readTags(request.get("Tags")));
        endpointConfigs.put(storageKey(region, name), config);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("EndpointConfigArn", config.getEndpointConfigArn());
        return response;
    }

    public synchronized ObjectNode describeEndpointConfig(JsonNode request, String region) {
        SageMakerEndpointConfig config = requireEndpointConfig(region, requireText(request, "EndpointConfigName"));
        ObjectNode node = objectMapper.createObjectNode();
        node.put("EndpointConfigName", config.getEndpointConfigName());
        node.put("EndpointConfigArn", config.getEndpointConfigArn());
        node.put("CreationTime", config.getCreationTime());
        node.set("ProductionVariants", objectMapper.valueToTree(config.getProductionVariants()));
        if (config.getShadowProductionVariants() != null && !config.getShadowProductionVariants().isEmpty()) {
            node.set("ShadowProductionVariants", objectMapper.valueToTree(config.getShadowProductionVariants()));
        }
        if (config.getDataCaptureConfig() != null) {
            node.set("DataCaptureConfig", objectMapper.valueToTree(config.getDataCaptureConfig()));
        }
        if (config.getKmsKeyId() != null) {
            node.put("KmsKeyId", config.getKmsKeyId());
        }
        if (config.getAsyncInferenceConfig() != null) {
            node.set("AsyncInferenceConfig", objectMapper.valueToTree(config.getAsyncInferenceConfig()));
        }
        if (config.getExplainerConfig() != null) {
            node.set("ExplainerConfig", objectMapper.valueToTree(config.getExplainerConfig()));
        }
        if (config.getExecutionRoleArn() != null) {
            node.put("ExecutionRoleArn", config.getExecutionRoleArn());
        }
        if (config.getVpcConfig() != null) {
            node.set("VpcConfig", objectMapper.valueToTree(config.getVpcConfig()));
        }
        node.put("EnableNetworkIsolation", config.isEnableNetworkIsolation());
        return node;
    }

    public synchronized ObjectNode deleteEndpointConfig(JsonNode request, String region) {
        SageMakerEndpointConfig config = requireEndpointConfig(region, requireText(request, "EndpointConfigName"));
        endpointConfigs.delete(storageKey(region, config.getEndpointConfigName()));
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listEndpointConfigs(JsonNode request, String region) {
        String nameContains = textOrNull(request, "NameContains");
        List<SageMakerEndpointConfig> all = endpointConfigs.scan(key -> key.startsWith(region + "::"));
        all.sort(Comparator.comparing(SageMakerEndpointConfig::getEndpointConfigName));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("EndpointConfigs");
        for (SageMakerEndpointConfig config : all) {
            if (nameContains != null
                    && !config.getEndpointConfigName().toLowerCase().contains(nameContains.toLowerCase())) {
                continue;
            }
            ObjectNode summary = summaries.addObject();
            summary.put("EndpointConfigName", config.getEndpointConfigName());
            summary.put("EndpointConfigArn", config.getEndpointConfigArn());
            summary.put("CreationTime", config.getCreationTime());
        }
        return response;
    }

    public synchronized ObjectNode createEndpoint(JsonNode request, String region) {
        String name = requireText(request, "EndpointName");
        if (endpoints.get(storageKey(region, name)).isPresent()) {
            throw new AwsException("ValidationException",
                    "Cannot create already existing endpoint \"" + name + "\".", 400);
        }
        String configName = requireText(request, "EndpointConfigName");
        long now = Instant.now().getEpochSecond();
        SageMakerEndpoint endpoint = new SageMakerEndpoint();
        endpoint.setEndpointName(name);
        endpoint.setEndpointArn(regionResolver.buildArn("sagemaker", region, "endpoint/" + name));
        endpoint.setEndpointConfigName(configName);
        endpoint.setEndpointStatus("InService");
        endpoint.setRegion(region);
        endpoint.setCreationTime(now);
        endpoint.setLastModifiedTime(now);
        endpoint.setDeploymentConfig(toMap(request.get("DeploymentConfig")));
        endpoint.setProductionVariants(variantSummariesForConfig(region, configName));
        endpoint.getTags().putAll(readTags(request.get("Tags")));
        endpoints.put(storageKey(region, name), endpoint);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("EndpointArn", endpoint.getEndpointArn());
        return response;
    }

    public synchronized ObjectNode describeEndpoint(JsonNode request, String region) {
        SageMakerEndpoint endpoint = requireEndpoint(region, requireText(request, "EndpointName"));
        ObjectNode node = objectMapper.createObjectNode();
        node.put("EndpointName", endpoint.getEndpointName());
        node.put("EndpointArn", endpoint.getEndpointArn());
        node.put("EndpointConfigName", endpoint.getEndpointConfigName());
        node.put("EndpointStatus", endpoint.getEndpointStatus());
        node.put("CreationTime", endpoint.getCreationTime());
        node.put("LastModifiedTime", endpoint.getLastModifiedTime());
        node.set("ProductionVariants", objectMapper.valueToTree(
                endpoint.getProductionVariants() != null ? endpoint.getProductionVariants() : List.of()));
        if (endpoint.getDeploymentConfig() != null) {
            node.set("LastDeploymentConfig", objectMapper.valueToTree(endpoint.getDeploymentConfig()));
        }
        if (endpoint.getFailureReason() != null) {
            node.put("FailureReason", endpoint.getFailureReason());
        }
        return node;
    }

    public synchronized ObjectNode updateEndpoint(JsonNode request, String region) {
        SageMakerEndpoint endpoint = requireEndpoint(region, requireText(request, "EndpointName"));
        String configName = requireText(request, "EndpointConfigName");
        endpoint.setEndpointConfigName(configName);
        if (request.has("DeploymentConfig")) {
            endpoint.setDeploymentConfig(toMap(request.get("DeploymentConfig")));
        }
        endpoint.setProductionVariants(variantSummariesForConfig(region, configName));
        endpoint.setEndpointStatus("InService");
        endpoint.setLastModifiedTime(Instant.now().getEpochSecond());
        endpoints.put(storageKey(region, endpoint.getEndpointName()), endpoint);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("EndpointArn", endpoint.getEndpointArn());
        return response;
    }

    public synchronized ObjectNode updateEndpointWeightsAndCapacities(JsonNode request, String region) {
        SageMakerEndpoint endpoint = requireEndpoint(region, requireText(request, "EndpointName"));
        JsonNode desired = request.get("DesiredWeightsAndCapacities");
        if (desired == null || !desired.isArray() || desired.isEmpty()) {
            throw new AwsException("ValidationException",
                    "DesiredWeightsAndCapacities is a required parameter.", 400);
        }
        for (JsonNode item : desired) {
            String variantName = requireText(item, "VariantName");
            Map<String, Object> variant = findVariant(endpoint.getProductionVariants(), variantName);
            if (variant == null) {
                throw new AwsException("ValidationException",
                        "Could not find variant \"" + variantName + "\".", 400);
            }
            if (item.hasNonNull("DesiredWeight")) {
                double weight = item.get("DesiredWeight").asDouble();
                variant.put("DesiredWeight", weight);
                variant.put("CurrentWeight", weight);
            }
            if (item.hasNonNull("DesiredInstanceCount")) {
                int count = item.get("DesiredInstanceCount").asInt();
                variant.put("DesiredInstanceCount", count);
                variant.put("CurrentInstanceCount", count);
            }
        }
        endpoint.setLastModifiedTime(Instant.now().getEpochSecond());
        endpoints.put(storageKey(region, endpoint.getEndpointName()), endpoint);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("EndpointArn", endpoint.getEndpointArn());
        return response;
    }

    public synchronized ObjectNode deleteEndpoint(JsonNode request, String region) {
        SageMakerEndpoint endpoint = requireEndpoint(region, requireText(request, "EndpointName"));
        endpoints.delete(storageKey(region, endpoint.getEndpointName()));
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listEndpoints(JsonNode request, String region) {
        String nameContains = textOrNull(request, "NameContains");
        String statusEquals = textOrNull(request, "StatusEquals");
        List<SageMakerEndpoint> all = endpoints.scan(key -> key.startsWith(region + "::"));
        all.sort(Comparator.comparing(SageMakerEndpoint::getEndpointName));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("Endpoints");
        for (SageMakerEndpoint endpoint : all) {
            if (nameContains != null
                    && !endpoint.getEndpointName().toLowerCase().contains(nameContains.toLowerCase())) {
                continue;
            }
            if (statusEquals != null && !statusEquals.equals(endpoint.getEndpointStatus())) {
                continue;
            }
            ObjectNode summary = summaries.addObject();
            summary.put("EndpointName", endpoint.getEndpointName());
            summary.put("EndpointArn", endpoint.getEndpointArn());
            summary.put("CreationTime", endpoint.getCreationTime());
            summary.put("LastModifiedTime", endpoint.getLastModifiedTime());
            summary.put("EndpointStatus", endpoint.getEndpointStatus());
        }
        return response;
    }

    public synchronized ObjectNode addTags(JsonNode request, String region) {
        String arn = requireText(request, "ResourceArn");
        Map<String, String> tags = tagsForArn(region, arn);
        tags.putAll(readTags(request.get("Tags")));
        persistByArn(region, arn);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Tags", tagsArray(tags));
        return response;
    }

    public synchronized ObjectNode deleteTags(JsonNode request, String region) {
        String arn = requireText(request, "ResourceArn");
        Map<String, String> tags = tagsForArn(region, arn);
        for (String key : stringList(request.get("TagKeys"))) {
            tags.remove(key);
        }
        persistByArn(region, arn);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listTags(JsonNode request, String region) {
        Map<String, String> tags = tagsForArn(region, requireText(request, "ResourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Tags", tagsArray(tags));
        return response;
    }

    public synchronized ObjectNode createFeatureGroup(JsonNode request, String region) {
        String name = requireText(request, "FeatureGroupName");
        if (featureGroups.get(storageKey(region, name)).isPresent()) {
            throw new AwsException("ResourceInUse",
                    "Feature group " + name + " is already in use.", 400);
        }
        SageMakerFeatureGroup group = new SageMakerFeatureGroup();
        group.setFeatureGroupName(name);
        group.setFeatureGroupArn(regionResolver.buildArn("sagemaker", region, "feature-group/" + name));
        group.setRegion(region);
        group.setRecordIdentifierFeatureName(requireText(request, "RecordIdentifierFeatureName"));
        group.setEventTimeFeatureName(requireText(request, "EventTimeFeatureName"));
        group.setFeatureGroupStatus("Created");
        group.setCreationTime(Instant.now().getEpochSecond());
        group.setRoleArn(textOrNull(request, "RoleArn"));
        group.setDescription(textOrNull(request, "Description"));
        group.setFeatureDefinitions(toObjectList(request.get("FeatureDefinitions")));
        group.setOnlineStoreConfig(toMap(request.get("OnlineStoreConfig")));
        group.setOfflineStoreConfig(toMap(request.get("OfflineStoreConfig")));
        group.setThroughputConfig(toMap(request.get("ThroughputConfig")));
        group.getTags().putAll(readTags(request.get("Tags")));
        featureGroups.put(storageKey(region, name), group);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FeatureGroupArn", group.getFeatureGroupArn());
        return response;
    }

    public synchronized ObjectNode describeFeatureGroup(JsonNode request, String region) {
        SageMakerFeatureGroup group = requireFeatureGroup(region, requireText(request, "FeatureGroupName"));
        ObjectNode node = objectMapper.createObjectNode();
        node.put("FeatureGroupArn", group.getFeatureGroupArn());
        node.put("FeatureGroupName", group.getFeatureGroupName());
        node.put("RecordIdentifierFeatureName", group.getRecordIdentifierFeatureName());
        node.put("EventTimeFeatureName", group.getEventTimeFeatureName());
        node.put("FeatureGroupStatus", group.getFeatureGroupStatus());
        node.put("CreationTime", group.getCreationTime());
        node.set("FeatureDefinitions", objectMapper.valueToTree(group.getFeatureDefinitions()));
        if (group.getOnlineStoreConfig() != null) {
            node.set("OnlineStoreConfig", objectMapper.valueToTree(group.getOnlineStoreConfig()));
        }
        if (group.getOfflineStoreConfig() != null) {
            node.set("OfflineStoreConfig", objectMapper.valueToTree(group.getOfflineStoreConfig()));
        }
        if (group.getThroughputConfig() != null) {
            node.set("ThroughputConfig", objectMapper.valueToTree(group.getThroughputConfig()));
        }
        if (group.getRoleArn() != null) {
            node.put("RoleArn", group.getRoleArn());
        }
        if (group.getDescription() != null) {
            node.put("Description", group.getDescription());
        }
        return node;
    }

    public synchronized ObjectNode deleteFeatureGroup(JsonNode request, String region) {
        SageMakerFeatureGroup group = requireFeatureGroup(region, requireText(request, "FeatureGroupName"));
        featureGroups.delete(storageKey(region, group.getFeatureGroupName()));
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listFeatureGroups(JsonNode request, String region) {
        List<SageMakerFeatureGroup> all = featureGroups.scan(key -> key.startsWith(region + "::"));
        all.sort(Comparator.comparing(SageMakerFeatureGroup::getFeatureGroupName));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("FeatureGroupSummaries");
        for (SageMakerFeatureGroup group : all) {
            ObjectNode summary = summaries.addObject();
            summary.put("FeatureGroupName", group.getFeatureGroupName());
            summary.put("FeatureGroupArn", group.getFeatureGroupArn());
            summary.put("CreationTime", group.getCreationTime());
            summary.put("FeatureGroupStatus", group.getFeatureGroupStatus());
        }
        return response;
    }

    public synchronized ObjectNode putRecord(String featureGroupName, JsonNode request) {
        SageMakerFeatureGroup group = requireFeatureGroupInCurrentRegion(featureGroupName);
        List<Map<String, Object>> record = toObjectList(request.get("Record"));
        String identifier = recordIdentifier(group, record);
        group.getRecords().put(identifier, record);
        featureGroups.put(storageKey(group.getRegion(), group.getFeatureGroupName()), group);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode getRecord(String featureGroupName, String identifier, List<String> featureNames) {
        SageMakerFeatureGroup group = requireFeatureGroupInCurrentRegion(featureGroupName);
        ObjectNode response = objectMapper.createObjectNode();
        List<Map<String, Object>> record = group.getRecords().get(identifier);
        if (record == null) {
            response.putArray("Record");
            return response;
        }
        List<Map<String, Object>> filtered = filterFeatures(record, featureNames);
        response.set("Record", objectMapper.valueToTree(filtered));
        return response;
    }

    public synchronized ObjectNode deleteRecord(String featureGroupName, String identifier, String deletionMode) {
        SageMakerFeatureGroup group = requireFeatureGroupInCurrentRegion(featureGroupName);
        if (identifier != null) {
            group.getRecords().remove(identifier);
            featureGroups.put(storageKey(group.getRegion(), group.getFeatureGroupName()), group);
        }
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listRecords(String featureGroupName, JsonNode request) {
        SageMakerFeatureGroup group = requireFeatureGroupInCurrentRegion(featureGroupName);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode identifiers = response.putArray("RecordIdentifiers");
        for (String identifier : group.getRecords().keySet()) {
            identifiers.add(identifier);
        }
        return response;
    }

    public synchronized ObjectNode batchGetRecord(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode records = response.putArray("Records");
        response.putArray("Errors");
        response.putArray("UnprocessedIdentifiers");
        JsonNode identifiers = request.get("Identifiers");
        if (identifiers == null || !identifiers.isArray()) {
            return response;
        }
        for (JsonNode identifier : identifiers) {
            String groupName = textOrNull(identifier, "FeatureGroupName");
            if (groupName == null) {
                continue;
            }
            SageMakerFeatureGroup group = requireFeatureGroupInCurrentRegion(groupName);
            for (String value : stringList(identifier.get("RecordIdentifiersValueAsString"))) {
                List<Map<String, Object>> record = group.getRecords().get(value);
                if (record == null) {
                    continue;
                }
                ObjectNode item = records.addObject();
                item.put("FeatureGroupName", groupName);
                item.put("RecordIdentifierValueAsString", value);
                item.set("Record", objectMapper.valueToTree(record));
            }
        }
        return response;
    }

    public synchronized ObjectNode batchWriteRecord(JsonNode request) {
        JsonNode entries = request.get("Entries");
        if (entries != null && entries.isArray()) {
            for (JsonNode entry : entries) {
                String groupName = textOrNull(entry, "FeatureGroupName");
                if (groupName == null) {
                    continue;
                }
                SageMakerFeatureGroup group = requireFeatureGroupInCurrentRegion(groupName);
                List<Map<String, Object>> record = toObjectList(entry.get("Record"));
                String identifier = recordIdentifier(group, record);
                group.getRecords().put(identifier, record);
                featureGroups.put(storageKey(group.getRegion(), group.getFeatureGroupName()), group);
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Errors");
        response.putArray("UnprocessedEntries");
        return response;
    }

    private ObjectNode toDescribeNode(SageMakerCluster cluster) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ClusterArn", cluster.getClusterArn());
        node.put("ClusterName", cluster.getClusterName());
        node.put("ClusterStatus", cluster.getClusterStatus());
        node.put("CreationTime", cluster.getCreationTime());
        node.set("InstanceGroups", objectMapper.valueToTree(
                cluster.getInstanceGroups() != null ? cluster.getInstanceGroups() : List.of()));
        if (cluster.getRestrictedInstanceGroups() != null && !cluster.getRestrictedInstanceGroups().isEmpty()) {
            node.set("RestrictedInstanceGroups", objectMapper.valueToTree(cluster.getRestrictedInstanceGroups()));
        }
        if (cluster.getRestrictedInstanceGroupsConfig() != null) {
            node.set("RestrictedInstanceGroupsConfig",
                    objectMapper.valueToTree(cluster.getRestrictedInstanceGroupsConfig()));
        }
        if (cluster.getVpcConfig() != null) {
            node.set("VpcConfig", objectMapper.valueToTree(cluster.getVpcConfig()));
        }
        if (cluster.getOrchestrator() != null) {
            node.set("Orchestrator", objectMapper.valueToTree(cluster.getOrchestrator()));
        }
        if (cluster.getTieredStorageConfig() != null) {
            node.set("TieredStorageConfig", objectMapper.valueToTree(cluster.getTieredStorageConfig()));
        }
        if (cluster.getNodeRecovery() != null) {
            node.put("NodeRecovery", cluster.getNodeRecovery());
        }
        if (cluster.getNodeProvisioningMode() != null) {
            node.put("NodeProvisioningMode", cluster.getNodeProvisioningMode());
        }
        if (cluster.getClusterRole() != null) {
            node.put("ClusterRole", cluster.getClusterRole());
        }
        if (cluster.getAutoScaling() != null) {
            node.set("AutoScaling", objectMapper.valueToTree(cluster.getAutoScaling()));
        }
        if (cluster.getFailureMessage() != null) {
            node.put("FailureMessage", cluster.getFailureMessage());
        }
        return node;
    }

    private SageMakerCluster requireCluster(String region, String name) {
        return clusters.get(storageKey(region, name)).orElseThrow(() -> notFound(name));
    }

    private SageMakerCluster requireClusterByArn(String region, String arn) {
        return clusters.scan(key -> key.startsWith(region + "::")).stream()
                .filter(cluster -> arn.equals(cluster.getClusterArn()))
                .findFirst()
                .orElse(null);
    }

    private SageMakerClusterSchedulerConfig requireSchedulerConfig(String region, String id) {
        return schedulerConfigs.get(storageKey(region, id))
                .orElseThrow(() -> new AwsException("ResourceNotFound",
                        "Could not find cluster scheduler config " + id + ".", 400));
    }

    private SageMakerClusterSchedulerConfig findSchedulerByArn(String region, String arn) {
        return schedulerConfigs.scan(key -> key.startsWith(region + "::")).stream()
                .filter(config -> arn.equals(config.getClusterSchedulerConfigArn()))
                .findFirst()
                .orElse(null);
    }

    private SageMakerClusterSchedulerConfig findSchedulerByName(String region, String name) {
        return schedulerConfigs.scan(key -> key.startsWith(region + "::")).stream()
                .filter(config -> name.equals(config.getName()))
                .findFirst()
                .orElse(null);
    }

    private SageMakerClusterSchedulerConfig findSchedulerByClusterArn(String region, String clusterArn) {
        return schedulerConfigs.scan(key -> key.startsWith(region + "::")).stream()
                .filter(config -> clusterArn.equals(config.getClusterArn()))
                .findFirst()
                .orElse(null);
    }

    private String newSchedulerConfigId(String region) {
        for (int i = 0; i < 8; i++) {
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            if (schedulerConfigs.get(storageKey(region, id)).isEmpty()) {
                return id;
            }
        }
        throw new AwsException("InternalFailure", "Failed to allocate cluster scheduler config id.", 500);
    }

    private ObjectNode toDescribeSchedulerConfig(SageMakerClusterSchedulerConfig config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ClusterSchedulerConfigArn", config.getClusterSchedulerConfigArn());
        node.put("ClusterSchedulerConfigId", config.getClusterSchedulerConfigId());
        node.put("Name", config.getName());
        node.put("ClusterSchedulerConfigVersion", config.getClusterSchedulerConfigVersion());
        node.put("Status", config.getStatus());
        if (config.getClusterArn() != null) {
            node.put("ClusterArn", config.getClusterArn());
        }
        if (config.getSchedulerConfig() != null) {
            node.set("SchedulerConfig", objectMapper.valueToTree(config.getSchedulerConfig()));
        }
        if (config.getDescription() != null) {
            node.put("Description", config.getDescription());
        }
        node.put("CreationTime", config.getCreationTime());
        node.put("LastModifiedTime", config.getLastModifiedTime());
        return node;
    }

    private SageMakerFeatureGroup requireFeatureGroup(String region, String name) {
        return featureGroups.get(storageKey(region, name))
                .orElseThrow(() -> notFound(name));
    }

    private SageMakerFeatureGroup requireFeatureGroupInCurrentRegion(String name) {
        return requireFeatureGroup(regionResolver.getRegion(), name);
    }

    private SageMakerFeatureGroup requireFeatureGroupByArn(String region, String arn) {
        return featureGroups.scan(key -> key.startsWith(region + "::")).stream()
                .filter(group -> arn.equals(group.getFeatureGroupArn()))
                .findFirst()
                .orElse(null);
    }

    private SageMakerComputeQuota requireQuota(String region, String id) {
        return computeQuotas.get(storageKey(region, id)).orElseThrow(() -> notFound(id));
    }

    private SageMakerComputeQuota requireQuotaByArn(String region, String arn) {
        return computeQuotas.scan(key -> key.startsWith(region + "::")).stream()
                .filter(quota -> arn.equals(quota.getComputeQuotaArn()))
                .findFirst()
                .orElse(null);
    }

    private SageMakerComputeQuota findQuotaByName(String region, String name) {
        return computeQuotas.scan(key -> key.startsWith(region + "::")).stream()
                .filter(quota -> name.equals(quota.getName()))
                .findFirst()
                .orElse(null);
    }

    private String newQuotaId(String region) {
        for (int i = 0; i < 8; i++) {
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            if (computeQuotas.get(storageKey(region, id)).isEmpty()) {
                return id;
            }
        }
        throw new AwsException("InternalFailure", "Failed to allocate compute quota id.", 500);
    }

    private ObjectNode toDescribeQuota(SageMakerComputeQuota quota) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ComputeQuotaArn", quota.getComputeQuotaArn());
        node.put("ComputeQuotaId", quota.getComputeQuotaId());
        node.put("Name", quota.getName());
        if (quota.getDescription() != null) {
            node.put("Description", quota.getDescription());
        }
        node.put("ComputeQuotaVersion", quota.getComputeQuotaVersion());
        node.put("Status", quota.getStatus());
        if (quota.getFailureReason() != null) {
            node.put("FailureReason", quota.getFailureReason());
        }
        if (quota.getClusterArn() != null) {
            node.put("ClusterArn", quota.getClusterArn());
        }
        if (quota.getComputeQuotaConfig() != null) {
            node.set("ComputeQuotaConfig", objectMapper.valueToTree(quota.getComputeQuotaConfig()));
        }
        if (quota.getComputeQuotaTarget() != null) {
            node.set("ComputeQuotaTarget", objectMapper.valueToTree(quota.getComputeQuotaTarget()));
        }
        if (quota.getActivationState() != null) {
            node.put("ActivationState", quota.getActivationState());
        }
        node.put("CreationTime", quota.getCreationTime());
        node.put("LastModifiedTime", quota.getLastModifiedTime());
        return node;
    }

    private SageMakerEndpoint requireEndpoint(String region, String name) {
        return endpoints.get(storageKey(region, name)).orElseThrow(() -> endpointNotFound(name));
    }

    private SageMakerEndpoint findEndpointByArn(String region, String arn) {
        return endpoints.scan(key -> key.startsWith(region + "::")).stream()
                .filter(endpoint -> arn.equals(endpoint.getEndpointArn()))
                .findFirst()
                .orElse(null);
    }

    private SageMakerModel requireModel(String region, String name) {
        return models.get(storageKey(region, name)).orElseThrow(() -> modelNotFound(name));
    }

    private SageMakerModel findModelByArn(String region, String arn) {
        return models.scan(key -> key.startsWith(region + "::")).stream()
                .filter(model -> arn.equals(model.getModelArn()))
                .findFirst()
                .orElse(null);
    }

    private SageMakerEndpointConfig requireEndpointConfig(String region, String name) {
        return endpointConfigs.get(storageKey(region, name)).orElseThrow(() -> endpointConfigNotFound(name));
    }

    private SageMakerEndpointConfig findEndpointConfigByArn(String region, String arn) {
        return endpointConfigs.scan(key -> key.startsWith(region + "::")).stream()
                .filter(config -> arn.equals(config.getEndpointConfigArn()))
                .findFirst()
                .orElse(null);
    }

    private List<Map<String, Object>> variantSummariesForConfig(String region, String configName) {
        SageMakerEndpointConfig config = endpointConfigs.get(storageKey(region, configName)).orElse(null);
        if (config == null) {
            return new ArrayList<>();
        }
        return toVariantSummaries(config.getProductionVariants());
    }

    private static List<Map<String, Object>> toVariantSummaries(List<Map<String, Object>> variants) {
        List<Map<String, Object>> summaries = new ArrayList<>();
        if (variants == null) {
            return summaries;
        }
        for (Map<String, Object> variant : variants) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("VariantName", variant.get("VariantName"));
            Object weight = variant.get("InitialVariantWeight");
            double resolvedWeight = weight instanceof Number number ? number.doubleValue() : 1.0;
            summary.put("CurrentWeight", resolvedWeight);
            summary.put("DesiredWeight", resolvedWeight);
            Object count = variant.get("InitialInstanceCount");
            if (count instanceof Number number) {
                summary.put("CurrentInstanceCount", number.intValue());
                summary.put("DesiredInstanceCount", number.intValue());
            }
            Object serverless = variant.get("ServerlessConfig");
            if (serverless != null) {
                summary.put("CurrentServerlessConfig", serverless);
                summary.put("DesiredServerlessConfig", serverless);
            }
            summaries.add(summary);
        }
        return summaries;
    }

    private static Map<String, Object> findVariant(List<Map<String, Object>> variants, String variantName) {
        if (variants == null) {
            return null;
        }
        for (Map<String, Object> variant : variants) {
            Object name = variant.get("VariantName");
            if (variantName.equals(String.valueOf(name))) {
                return variant;
            }
        }
        return null;
    }

    private Map<String, String> tagsForArn(String region, String arn) {
        SageMakerCluster cluster = requireClusterByArn(region, arn);
        if (cluster != null) {
            return cluster.getTags();
        }
        SageMakerClusterSchedulerConfig scheduler = findSchedulerByArn(region, arn);
        if (scheduler != null) {
            return scheduler.getTags();
        }
        SageMakerEndpoint endpoint = findEndpointByArn(region, arn);
        if (endpoint != null) {
            return endpoint.getTags();
        }
        SageMakerModel model = findModelByArn(region, arn);
        if (model != null) {
            return model.getTags();
        }
        SageMakerEndpointConfig config = findEndpointConfigByArn(region, arn);
        if (config != null) {
            return config.getTags();
        }
        SageMakerFeatureGroup group = requireFeatureGroupByArn(region, arn);
        if (group != null) {
            return group.getTags();
        }
        SageMakerComputeQuota quota = requireQuotaByArn(region, arn);
        if (quota != null) {
            return quota.getTags();
        }
        throw notFound(arn);
    }

    private void persistByArn(String region, String arn) {
        SageMakerCluster cluster = requireClusterByArn(region, arn);
        if (cluster != null) {
            clusters.put(storageKey(region, cluster.getClusterName()), cluster);
            return;
        }
        SageMakerClusterSchedulerConfig scheduler = findSchedulerByArn(region, arn);
        if (scheduler != null) {
            schedulerConfigs.put(storageKey(region, scheduler.getClusterSchedulerConfigId()), scheduler);
            return;
        }
        SageMakerEndpoint endpoint = findEndpointByArn(region, arn);
        if (endpoint != null) {
            endpoints.put(storageKey(region, endpoint.getEndpointName()), endpoint);
            return;
        }
        SageMakerModel model = findModelByArn(region, arn);
        if (model != null) {
            models.put(storageKey(region, model.getModelName()), model);
            return;
        }
        SageMakerEndpointConfig config = findEndpointConfigByArn(region, arn);
        if (config != null) {
            endpointConfigs.put(storageKey(region, config.getEndpointConfigName()), config);
            return;
        }
        SageMakerFeatureGroup group = requireFeatureGroupByArn(region, arn);
        if (group != null) {
            featureGroups.put(storageKey(region, group.getFeatureGroupName()), group);
            return;
        }
        SageMakerComputeQuota quota = requireQuotaByArn(region, arn);
        if (quota != null) {
            computeQuotas.put(storageKey(region, quota.getComputeQuotaId()), quota);
        }
    }

    private List<Map<String, Object>> toObjectList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new ArrayList<>();
        }
        return objectMapper.convertValue(node, LIST_MAP_TYPE);
    }

    private String recordIdentifier(SageMakerFeatureGroup group, List<Map<String, Object>> record) {
        String feature = group.getRecordIdentifierFeatureName();
        for (Map<String, Object> value : record) {
            Object name = value.get("FeatureName");
            if (feature.equals(String.valueOf(name))) {
                Object identifier = value.get("ValueAsString");
                if (identifier != null) {
                    return identifier.toString();
                }
            }
        }
        throw new AwsException("ValidationException",
                "Record is missing identifier feature " + feature + ".", 400);
    }

    private static List<Map<String, Object>> filterFeatures(List<Map<String, Object>> record,
                                                            List<String> featureNames) {
        if (featureNames == null || featureNames.isEmpty()) {
            return record;
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> value : record) {
            Object name = value.get("FeatureName");
            if (name != null && featureNames.contains(name.toString())) {
                filtered.add(value);
            }
        }
        return filtered;
    }

    private List<Map<String, Object>> toGroupDetails(JsonNode specs) {
        if (specs == null || !specs.isArray()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> details = new ArrayList<>();
        for (JsonNode spec : specs) {
            Map<String, Object> group = objectMapper.convertValue(spec, MAP_TYPE);
            Object count = group.remove("InstanceCount");
            if (count != null) {
                group.put("CurrentCount", count);
                group.put("TargetCount", count);
            }
            Object min = group.remove("MinInstanceCount");
            if (min != null) {
                group.put("MinCount", min);
            }
            group.put("Status", "InService");
            details.add(group);
        }
        return details;
    }

    private static List<Map<String, Object>> mergeGroups(List<Map<String, Object>> existing,
                                                         List<Map<String, Object>> incoming,
                                                         List<String> toDelete) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        if (existing != null) {
            for (Map<String, Object> group : existing) {
                Object name = group.get("InstanceGroupName");
                if (name != null) {
                    byName.put(name.toString(), group);
                }
            }
        }
        for (String name : toDelete) {
            byName.remove(name);
        }
        if (incoming != null) {
            for (Map<String, Object> group : incoming) {
                Object name = group.get("InstanceGroupName");
                if (name != null) {
                    byName.put(name.toString(), group);
                }
            }
        }
        return new ArrayList<>(byName.values());
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            return null;
        }
        return objectMapper.convertValue(node, MAP_TYPE);
    }

    private ArrayNode tagsArray(Map<String, String> tags) {
        ArrayNode array = objectMapper.createArrayNode();
        if (tags == null) {
            return array;
        }
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue() != null ? entry.getValue() : "");
            array.add(tag);
        }
        return array;
    }

    private static Map<String, String> readTags(JsonNode tags) {
        Map<String, String> result = new LinkedHashMap<>();
        if (tags == null || !tags.isArray()) {
            return result;
        }
        for (JsonNode tag : tags) {
            String key = textOrNull(tag, "Key");
            if (key != null) {
                String value = textOrNull(tag, "Value");
                result.put(key, value != null ? value : "");
            }
        }
        return result;
    }

    private static List<String> stringList(JsonNode node) {
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

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw new AwsException("ValidationException", field + " is a required parameter.", 400);
        }
        return value;
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        String value = textOrNull(node, field);
        return value != null ? value : fallback;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String storageKey(String region, String name) {
        return region + "::" + name;
    }

    private static AwsException notFound(String name) {
        return new AwsException("ResourceNotFound",
                "Could not find cluster " + name + ".", 400);
    }

    private static AwsException endpointNotFound(String name) {
        return new AwsException("ValidationException",
                "Could not find endpoint \"" + name + "\".", 400);
    }

    private static AwsException modelNotFound(String name) {
        return new AwsException("ValidationException",
                "Could not find model \"" + name + "\".", 400);
    }

    private static AwsException endpointConfigNotFound(String name) {
        return new AwsException("ValidationException",
                "Could not find endpoint configuration \"" + name + "\".", 400);
    }
}
