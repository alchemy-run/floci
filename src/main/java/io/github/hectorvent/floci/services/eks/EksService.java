package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.eks.model.AccessEntry;
import io.github.hectorvent.floci.services.eks.model.Addon;
import io.github.hectorvent.floci.services.eks.model.AddonStatus;
import io.github.hectorvent.floci.services.eks.model.AssociateAccessPolicyRequest;
import io.github.hectorvent.floci.services.eks.model.CertificateAuthority;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAccessEntryRequest;
import io.github.hectorvent.floci.services.eks.model.CreateAddonRequest;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.CreateFargateProfileRequest;
import io.github.hectorvent.floci.services.eks.model.CreateNodeGroupRequest;
import io.github.hectorvent.floci.services.eks.model.CreatePodIdentityAssociationRequest;
import io.github.hectorvent.floci.services.eks.model.FargateProfile;
import io.github.hectorvent.floci.services.eks.model.FargateProfileStatus;
import io.github.hectorvent.floci.services.eks.model.KubernetesNetworkConfig;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import io.github.hectorvent.floci.services.eks.model.NodegroupScalingConfig;
import io.github.hectorvent.floci.services.eks.model.NodegroupStatus;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociation;
import io.github.hectorvent.floci.services.eks.model.ResourcesVpcConfig;
import io.github.hectorvent.floci.services.eks.model.UpdateAccessEntryRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateAddonRequest;
import io.github.hectorvent.floci.services.eks.model.UpdatePodIdentityAssociationRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
public class EksService implements TagHandler {

    static final String SERVICE = "eks";

    private static final Logger LOG = Logger.getLogger(EksService.class);

    /**
     * Dummy CA for mock (and pre-k3s) clusters. Alchemy's Cluster reconciler requires
     * non-empty {@code certificateAuthority.data} before it will return attributes.
     */
    static final String MOCK_CA_DATA = Base64.getEncoder().encodeToString(
            "-----BEGIN CERTIFICATE-----\nMOCK\n-----END CERTIFICATE-----\n".getBytes());

    private final StorageBackend<String, Cluster> storage;
    private final StorageBackend<String, Nodegroup> nodeGroupStorage;
    private final StorageBackend<String, FargateProfile> fargateProfileStorage;
    private final StorageBackend<String, Addon> addonStorage;
    private final StorageBackend<String, AccessEntry> accessEntryStorage;
    private final StorageBackend<String, PodIdentityAssociation> podIdentityStorage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final EksClusterManager clusterManager;
    private final Ec2Service ec2Service;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Map<String, Object>> insightsRefreshByCluster = new ConcurrentHashMap<>();

    @Inject
    public EksService(StorageFactory storageFactory, EmulatorConfig config,
            RegionResolver regionResolver, EksClusterManager clusterManager, Ec2Service ec2Service) {
        this.storage = storageFactory.create("eks", "eks-clusters.json",
                new TypeReference<Map<String, Cluster>>() {
                });
        this.nodeGroupStorage = storageFactory.create("eks", "eks-nodegroups.json",
                new TypeReference<Map<String, Nodegroup>>() {
                });
        this.fargateProfileStorage = storageFactory.create("eks", "eks-fargate-profiles.json",
                new TypeReference<Map<String, FargateProfile>>() {
                });
        this.accessEntryStorage = storageFactory.create("eks", "eks-access-entries.json",
                new TypeReference<Map<String, AccessEntry>>() {
                });
        this.addonStorage = storageFactory.create("eks", "eks-addons.json",
                new TypeReference<Map<String, Addon>>() {
                });
        this.podIdentityStorage = storageFactory.create("eks", "eks-pod-identity-associations.json",
                new TypeReference<Map<String, PodIdentityAssociation>>() {
                });
        this.config = config;
        this.regionResolver = regionResolver;
        this.clusterManager = clusterManager;
        this.ec2Service = ec2Service;
    }

    @PostConstruct
    public void init() {
        if (!config.services().eks().mock()) {
            startReadinessPoller();
        }
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        if (!config.services().eks().mock()) {
            for (Cluster cluster : allClusters()) {
                clusterManager.stopCluster(cluster);
            }
        }
    }

    public Cluster createCluster(CreateClusterRequest request) {
        String name = request.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterException", "Cluster name is required", 400);
        }
        if (storage.get(name).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Cluster already exists: " + name, 409);
        }

        String region = regionResolver.getRegion();
        String resolvedVpcId = validateSubnetsAndResolveVpcId(region, request.getResourcesVpcConfig());
        String accountId = regionResolver.getAccountId();
        String arn = AwsArnUtils.Arn.of("eks", region, accountId, "cluster/" + name).toString();

        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setArn(arn);
        cluster.setAccountId(accountId);
        cluster.setCreatedAt(Instant.now());
        cluster.setVersion(request.getVersion() != null ? request.getVersion() : "1.29");
        cluster.setRoleArn(request.getRoleArn());
        cluster.setResourcesVpcConfig(buildVpcConfigResponse(request.getResourcesVpcConfig(), resolvedVpcId));
        cluster.setKubernetesNetworkConfig(buildNetworkConfig(request.getKubernetesNetworkConfig()));
        cluster.setStatus(ClusterStatus.CREATING);
        cluster.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());
        cluster.setPlatformVersion("eks.1");
        cluster.setCertificateAuthority(new CertificateAuthority(MOCK_CA_DATA));

        if (config.services().eks().mock()) {
            cluster.setStatus(ClusterStatus.ACTIVE);
            cluster.setEndpoint("https://localhost:" + config.services().eks().apiServerBasePort());
        } else {
            try {
                clusterManager.startCluster(cluster);
            } catch (Exception e) {
                LOG.errorv("Failed to start k3s container for cluster {0}: {1}", name, e.getMessage());
                cluster.setStatus(ClusterStatus.FAILED);
            }
        }

        storage.put(name, cluster);
        return cluster;
    }

    public Cluster describeCluster(String name) {
        return storage.get(name)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No cluster found for name: " + name, 404));
    }

    public List<String> listClusters() {
        return storage.scan(k -> true).stream()
                .map(Cluster::getName)
                .collect(Collectors.toList());
    }

    public Cluster deleteCluster(String name) {
        Cluster cluster = storage.get(name)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No cluster found for name: " + name, 404));

        cluster.setStatus(ClusterStatus.DELETING);
        if (!config.services().eks().mock()) {
            clusterManager.stopCluster(cluster);
        }
        String accessPrefix = name + "::";
        for (String key : accessEntryStorage.keys()) {
            if (key.startsWith(accessPrefix)) {
                accessEntryStorage.delete(key);
            }
        }
        String addonPrefix = name + "/";
        for (String key : addonStorage.keys()) {
            if (key.startsWith(addonPrefix)) {
                addonStorage.delete(key);
            }
        }
        String fargatePrefix = name + "/";
        for (String key : fargateProfileStorage.keys()) {
            if (key.startsWith(fargatePrefix)) {
                fargateProfileStorage.delete(key);
            }
        }
        String podIdentityPrefix = name + "/";
        for (String key : podIdentityStorage.keys()) {
            if (key.startsWith(podIdentityPrefix)) {
                podIdentityStorage.delete(key);
            }
        }
        insightsRefreshByCluster.remove(name);
        storage.delete(name);
        return cluster;
    }

    public Nodegroup createNodeGroup(String clusterName, CreateNodeGroupRequest request) {
        Nodegroup nodegroup = new Nodegroup();
        nodegroup.setNodegroupName(request.getNodegroupName());
        nodegroup.setVersion(request.getVersion());
        nodegroup.setReleaseVersion(request.getReleaseVersion());
        nodegroup.setSubnets(request.getSubnets());
        nodegroup.setNodeRole(request.getNodeRole());
        nodegroup.setAmiType(request.getAmiType());
        nodegroup.setCapacityType(request.getCapacityType());
        nodegroup.setDiskSize(request.getDiskSize());
        nodegroup.setInstanceTypes(request.getInstanceTypes());
        nodegroup.setScalingConfig(request.getScalingConfig());
        nodegroup.setUpdateConfig(request.getUpdateConfig());
        nodegroup.setLabels(request.getLabels());
        nodegroup.setTags(request.getTags());
        nodegroup.setClientRequestToken(request.getClientRequestToken());
        return createNodeGroup(clusterName, nodegroup);
    }

    public Nodegroup createNodeGroup(String clusterName, Nodegroup request) {
        Cluster cluster = describeCluster(clusterName);

        String nodegroupName = request.getNodegroupName();
        if (nodegroupName == null || nodegroupName.isBlank()) {
            throw new AwsException("InvalidParameterException", "Nodegroup name is required", 400);
        }
        if (request.getNodeRole() == null || request.getNodeRole().isBlank()) {
            throw new AwsException("InvalidParameterException", "nodeRole is required", 400);
        }
        if (request.getSubnets() == null || request.getSubnets().isEmpty()) {
            throw new AwsException("InvalidParameterException", "subnets are required", 400);
        }

        String storageKey = nodeGroupKey(clusterName, nodegroupName);
        if (nodeGroupStorage.get(storageKey).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Nodegroup already exists: " + nodegroupName, 409);
        }

        String region = config.defaultRegion();
        String accountId = regionResolver.getAccountId();
        String id = UUID.randomUUID().toString();
        String arn = AwsArnUtils.Arn.of("eks", region, accountId,
                "nodegroup/" + clusterName + "/" + nodegroupName + "/" + id).toString();

        Instant now = Instant.now();
        Nodegroup nodeGroup = new Nodegroup();
        nodeGroup.setNodegroupName(nodegroupName);
        nodeGroup.setNodegroupArn(arn);
        nodeGroup.setClusterName(clusterName);
        nodeGroup.setAccountId(accountId);
        nodeGroup.setCreatedAt(now);
        nodeGroup.setModifiedAt(now);
        String resolvedVersion = request.getVersion() != null ? request.getVersion() : cluster.getVersion();
        nodeGroup.setVersion(resolvedVersion);
        nodeGroup.setReleaseVersion(request.getReleaseVersion() != null
                ? request.getReleaseVersion() : resolvedVersion + "-eks-1");
        nodeGroup.setStatus(NodegroupStatus.ACTIVE);
        nodeGroup.setCapacityType(request.getCapacityType() != null ? request.getCapacityType() : "ON_DEMAND");
        nodeGroup.setScalingConfig(request.getScalingConfig() != null ? request.getScalingConfig() : defaultScalingConfig());
        nodeGroup.setInstanceTypes(request.getInstanceTypes() != null ? request.getInstanceTypes() : List.of("t3.medium"));
        nodeGroup.setSubnets(request.getSubnets() != null ? request.getSubnets() : List.of());
        nodeGroup.setAmiType(request.getAmiType() != null ? request.getAmiType() : "AL2_x86_64");
        nodeGroup.setNodeRole(request.getNodeRole());
        nodeGroup.setDiskSize(request.getDiskSize() != null ? request.getDiskSize() : 20);
        nodeGroup.setResources(defaultNodeGroupResources(nodegroupName));
        nodeGroup.setHealth(defaultNodeGroupHealth());
        nodeGroup.setUpdateConfig(request.getUpdateConfig() != null ? request.getUpdateConfig() : defaultUpdateConfig());
        nodeGroup.setLabels(request.getLabels() != null ? new HashMap<>(request.getLabels()) : null);
        nodeGroup.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());

        nodeGroupStorage.put(storageKey, nodeGroup);
        return nodeGroup;
    }

    public Nodegroup describeNodeGroup(String clusterName, String nodegroupName) {
        describeCluster(clusterName);
        return nodeGroupStorage.get(nodeGroupKey(clusterName, nodegroupName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No nodegroup found for name: " + nodegroupName, 404));
    }

    public List<String> listNodeGroups(String clusterName) {
        describeCluster(clusterName);
        String prefix = clusterName + "/";
        return nodeGroupStorage.scan(key -> key.startsWith(prefix)).stream()
                .map(Nodegroup::getNodegroupName)
                .collect(Collectors.toList());
    }

    public Nodegroup deleteNodeGroup(String clusterName, String nodegroupName) {
        Nodegroup nodeGroup = describeNodeGroup(clusterName, nodegroupName);
        nodeGroup.setStatus(NodegroupStatus.DELETING);
        nodeGroup.setModifiedAt(Instant.now());
        nodeGroupStorage.delete(nodeGroupKey(clusterName, nodegroupName));
        return nodeGroup;
    }

    public FargateProfile createFargateProfile(String clusterName, CreateFargateProfileRequest request) {
        describeCluster(clusterName);

        String fargateProfileName = request.getFargateProfileName();
        if (fargateProfileName == null || fargateProfileName.isBlank()) {
            throw new AwsException("InvalidParameterException", "Fargate profile name is required", 400);
        }
        if (request.getPodExecutionRoleArn() == null || request.getPodExecutionRoleArn().isBlank()) {
            throw new AwsException("InvalidParameterException", "podExecutionRoleArn is required", 400);
        }

        String storageKey = fargateProfileKey(clusterName, fargateProfileName);
        if (fargateProfileStorage.get(storageKey).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Fargate profile already exists: " + fargateProfileName, 409);
        }

        String region = config.defaultRegion();
        String accountId = regionResolver.getAccountId();
        String id = UUID.randomUUID().toString();
        String arn = AwsArnUtils.Arn.of("eks", region, accountId,
                "fargateprofile/" + clusterName + "/" + fargateProfileName + "/" + id).toString();

        FargateProfile profile = new FargateProfile();
        profile.setFargateProfileName(fargateProfileName);
        profile.setFargateProfileArn(arn);
        profile.setClusterName(clusterName);
        profile.setAccountId(accountId);
        profile.setCreatedAt(Instant.now());
        profile.setPodExecutionRoleArn(request.getPodExecutionRoleArn());
        profile.setSubnets(request.getSubnets() != null ? request.getSubnets() : List.of());
        profile.setSelectors(request.getSelectors() != null ? request.getSelectors() : List.of());
        profile.setStatus(FargateProfileStatus.ACTIVE);
        profile.setHealth(defaultFargateProfileHealth());
        profile.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());

        fargateProfileStorage.put(storageKey, profile);
        return profile;
    }

    public FargateProfile describeFargateProfile(String clusterName, String fargateProfileName) {
        describeCluster(clusterName);
        return fargateProfileStorage.get(fargateProfileKey(clusterName, fargateProfileName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No fargate profile found for name: " + fargateProfileName, 404));
    }

    public List<String> listFargateProfiles(String clusterName) {
        describeCluster(clusterName);
        String prefix = clusterName + "/";
        return fargateProfileStorage.scan(key -> key.startsWith(prefix)).stream()
                .map(FargateProfile::getFargateProfileName)
                .collect(Collectors.toList());
    }

    public FargateProfile deleteFargateProfile(String clusterName, String fargateProfileName) {
        FargateProfile profile = describeFargateProfile(clusterName, fargateProfileName);
        profile.setStatus(FargateProfileStatus.DELETING);
        fargateProfileStorage.delete(fargateProfileKey(clusterName, fargateProfileName));
        return profile;
    }

    public Addon createAddon(String clusterName, CreateAddonRequest request) {
        describeCluster(clusterName);
        if (request == null || request.getAddonName() == null || request.getAddonName().isBlank()) {
            throw new AwsException("InvalidParameterException", "addonName is required", 400);
        }
        String addonName = request.getAddonName();
        String storageKey = addonKey(clusterName, addonName);
        if (addonStorage.get(storageKey).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Addon already exists: " + addonName, 409);
        }

        String region = regionResolver.getRegion();
        String accountId = regionResolver.getAccountId();
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Addon addon = new Addon();
        addon.setAddonName(addonName);
        addon.setClusterName(clusterName);
        addon.setAccountId(accountId);
        addon.setAddonArn(AwsArnUtils.Arn.of("eks", region, accountId,
                "addon/" + clusterName + "/" + addonName + "/" + id).toString());
        addon.setStatus(AddonStatus.ACTIVE);
        addon.setAddonVersion(request.getAddonVersion() != null
                ? request.getAddonVersion() : "v1.0.0-eksbuild.1");
        addon.setServiceAccountRoleArn(request.getServiceAccountRoleArn());
        addon.setConfigurationValues(request.getConfigurationValues());
        addon.setCreatedAt(now);
        addon.setModifiedAt(now);
        addon.setPublisher("eks");
        addon.setOwner("amazon");
        addon.setHealth(Map.of("issues", List.of()));
        addon.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());
        addon.setNamespaceConfig(resolveNamespace(request.getNamespaceConfig()));
        addon.setPodIdentityAssociations(podIdentityArns(region, accountId, clusterName,
                request.getPodIdentityAssociations()));
        addonStorage.put(storageKey, addon);
        return addon;
    }

    public Addon describeAddon(String clusterName, String addonName) {
        describeCluster(clusterName);
        return addonStorage.get(addonKey(clusterName, addonName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No addon found for name: " + addonName, 404));
    }

    public List<String> listAddons(String clusterName) {
        describeCluster(clusterName);
        String prefix = clusterName + "/";
        return addonStorage.scan(key -> key.startsWith(prefix)).stream()
                .map(Addon::getAddonName)
                .collect(Collectors.toList());
    }

    public Map<String, Object> updateAddon(String clusterName, String addonName, UpdateAddonRequest request) {
        Addon addon = describeAddon(clusterName, addonName);
        if (request != null) {
            if (request.getAddonVersion() != null) {
                addon.setAddonVersion(request.getAddonVersion());
            }
            if (request.getServiceAccountRoleArn() != null) {
                addon.setServiceAccountRoleArn(request.getServiceAccountRoleArn());
            }
            if (request.getConfigurationValues() != null) {
                addon.setConfigurationValues(request.getConfigurationValues());
            }
            if (request.getPodIdentityAssociations() != null) {
                String region = regionResolver.getRegion();
                String accountId = regionResolver.getAccountId();
                addon.setPodIdentityAssociations(podIdentityArns(region, accountId, clusterName,
                        request.getPodIdentityAssociations()));
            }
        }
        addon.setModifiedAt(Instant.now());
        addon.setStatus(AddonStatus.ACTIVE);
        addonStorage.put(addonKey(clusterName, addonName), addon);

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("id", UUID.randomUUID().toString());
        update.put("status", "Successful");
        update.put("type", "AddonUpdate");
        update.put("params", List.of());
        update.put("createdAt", Instant.now().getEpochSecond());
        update.put("errors", List.of());
        return update;
    }

    public Addon deleteAddon(String clusterName, String addonName) {
        Addon addon = describeAddon(clusterName, addonName);
        addon.setStatus(AddonStatus.DELETING);
        addon.setModifiedAt(Instant.now());
        addonStorage.delete(addonKey(clusterName, addonName));
        return addon;
    }

    public Map<String, Object> page(List<String> items, Integer maxResults, String nextToken, String itemsKey) {
        int start = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                start = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidParameterException", "Invalid nextToken", 400);
            }
        }
        if (start < 0) {
            start = 0;
        }
        if (start > items.size()) {
            start = items.size();
        }
        int limit = (maxResults != null && maxResults > 0) ? maxResults : Math.max(items.size() - start, 0);
        int end = Math.min(items.size(), start + limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(itemsKey, items.subList(start, end));
        if (end < items.size()) {
            body.put("nextToken", Integer.toString(end));
        }
        return body;
    }

    public AccessEntry createAccessEntry(String clusterName, CreateAccessEntryRequest request) {
        describeCluster(clusterName);
        if (request == null || request.getPrincipalArn() == null || request.getPrincipalArn().isBlank()) {
            throw new AwsException("InvalidParameterException", "principalArn is required", 400);
        }
        String principalArn = request.getPrincipalArn();
        String storageKey = accessEntryKey(clusterName, principalArn);
        if (accessEntryStorage.get(storageKey).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Access entry already exists for principal: " + principalArn, 409);
        }

        String region = regionResolver.getRegion();
        String accountId = regionResolver.getAccountId();
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();

        AccessEntry entry = new AccessEntry();
        entry.setClusterName(clusterName);
        entry.setPrincipalArn(principalArn);
        entry.setKubernetesGroups(request.getKubernetesGroups() != null
                ? new ArrayList<>(request.getKubernetesGroups()) : new ArrayList<>());
        entry.setAccessEntryArn(buildAccessEntryArn(region, accountId, clusterName, principalArn, id));
        entry.setCreatedAt(now);
        entry.setModifiedAt(now);
        entry.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());
        entry.setUsername(request.getUsername() != null && !request.getUsername().isBlank()
                ? request.getUsername() : principalArn);
        entry.setType(request.getType() != null && !request.getType().isBlank()
                ? request.getType() : "STANDARD");
        entry.setAccountId(accountId);
        entry.setAssociatedAccessPolicies(new ArrayList<>());

        accessEntryStorage.put(storageKey, entry);
        return entry;
    }

    public AccessEntry describeAccessEntry(String clusterName, String principalArn) {
        describeCluster(clusterName);
        return accessEntryStorage.get(accessEntryKey(clusterName, principalArn))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No access entry found for principal: " + principalArn, 404));
    }

    public List<String> listAccessEntries(String clusterName, String associatedPolicyArn) {
        describeCluster(clusterName);
        String prefix = clusterName + "::";
        return accessEntryStorage.scan(key -> key.startsWith(prefix)).stream()
                .filter(entry -> associatedPolicyArn == null || associatedPolicyArn.isBlank()
                        || hasAssociatedPolicy(entry, associatedPolicyArn))
                .map(AccessEntry::getPrincipalArn)
                .collect(Collectors.toList());
    }

    public AccessEntry updateAccessEntry(String clusterName, String principalArn,
            UpdateAccessEntryRequest request) {
        AccessEntry entry = describeAccessEntry(clusterName, principalArn);
        if (request != null) {
            if (request.getKubernetesGroups() != null) {
                entry.setKubernetesGroups(new ArrayList<>(request.getKubernetesGroups()));
            }
            if (request.getUsername() != null) {
                entry.setUsername(request.getUsername());
            }
        }
        entry.setModifiedAt(Instant.now());
        accessEntryStorage.put(accessEntryKey(clusterName, principalArn), entry);
        return entry;
    }

    public void deleteAccessEntry(String clusterName, String principalArn) {
        describeAccessEntry(clusterName, principalArn);
        accessEntryStorage.delete(accessEntryKey(clusterName, principalArn));
    }

    public AccessEntry.AssociatedAccessPolicy associateAccessPolicy(String clusterName, String principalArn,
            AssociateAccessPolicyRequest request) {
        if (request == null || request.getPolicyArn() == null || request.getPolicyArn().isBlank()) {
            throw new AwsException("InvalidParameterException", "policyArn is required", 400);
        }
        if (request.getAccessScope() == null || request.getAccessScope().getType() == null
                || request.getAccessScope().getType().isBlank()) {
            throw new AwsException("InvalidParameterException", "accessScope.type is required", 400);
        }
        AccessEntry entry = describeAccessEntry(clusterName, principalArn);
        Instant now = Instant.now();
        AccessEntry.AssociatedAccessPolicy existing = entry.getAssociatedAccessPolicies().stream()
                .filter(policy -> request.getPolicyArn().equals(policy.getPolicyArn()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            existing.setAccessScope(request.getAccessScope());
            existing.setModifiedAt(now);
            accessEntryStorage.put(accessEntryKey(clusterName, principalArn), entry);
            return existing;
        }

        AccessEntry.AssociatedAccessPolicy associated = new AccessEntry.AssociatedAccessPolicy();
        associated.setPolicyArn(request.getPolicyArn());
        associated.setAccessScope(request.getAccessScope());
        associated.setAssociatedAt(now);
        associated.setModifiedAt(now);
        entry.getAssociatedAccessPolicies().add(associated);
        entry.setModifiedAt(now);
        accessEntryStorage.put(accessEntryKey(clusterName, principalArn), entry);
        return associated;
    }

    public void disassociateAccessPolicy(String clusterName, String principalArn, String policyArn) {
        AccessEntry entry = describeAccessEntry(clusterName, principalArn);
        boolean removed = entry.getAssociatedAccessPolicies()
                .removeIf(policy -> policyArn.equals(policy.getPolicyArn()));
        if (!removed) {
            throw new AwsException("ResourceNotFoundException",
                    "No associated access policy found: " + policyArn, 404);
        }
        entry.setModifiedAt(Instant.now());
        accessEntryStorage.put(accessEntryKey(clusterName, principalArn), entry);
    }

    public List<AccessEntry.AssociatedAccessPolicy> listAssociatedAccessPolicies(String clusterName,
            String principalArn) {
        return new ArrayList<>(describeAccessEntry(clusterName, principalArn).getAssociatedAccessPolicies());
    }

    public Map<String, Object> listInsights(String clusterName) {
        describeCluster(clusterName);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("insights", List.of());
        return body;
    }

    public Map<String, Object> describeInsight(String clusterName, String insightId) {
        describeCluster(clusterName);
        if (insightId == null || insightId.isBlank()) {
            throw new AwsException("InvalidParameterException", "id is required", 400);
        }
        throw new AwsException("ResourceNotFoundException",
                "No insight found for id: " + insightId, 404);
    }

    public List<String> listUpdateIds(String clusterName) {
        describeCluster(clusterName);
        return List.of();
    }

    public Map<String, Object> describeUpdate(String clusterName, String updateId) {
        describeCluster(clusterName);
        if (updateId == null || updateId.isBlank()) {
            throw new AwsException("InvalidParameterException", "updateId is required", 400);
        }
        throw new AwsException("ResourceNotFoundException",
                "No update found for id: " + updateId, 404);
    }

    public Map<String, Object> listCapabilities(String clusterName) {
        describeCluster(clusterName);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("capabilities", List.of());
        return body;
    }

    public Map<String, Object> describeCapability(String clusterName, String capabilityName) {
        describeCluster(clusterName);
        if (capabilityName == null || capabilityName.isBlank()) {
            throw new AwsException("InvalidParameterException", "capabilityName is required", 400);
        }
        throw new AwsException("ResourceNotFoundException",
                "No capability found for name: " + capabilityName, 404);
    }

    public Map<String, Object> describeIdentityProviderConfig(String clusterName, String type, String configName) {
        describeCluster(clusterName);
        if (type == null || type.isBlank() || configName == null || configName.isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "identityProviderConfig type and name are required", 400);
        }
        throw new AwsException("ResourceNotFoundException",
                "No identity provider config found: " + configName, 404);
    }

    public PodIdentityAssociation createPodIdentityAssociation(String clusterName,
            CreatePodIdentityAssociationRequest request) {
        describeCluster(clusterName);
        if (request == null) {
            throw new AwsException("InvalidParameterException",
                    "namespace, serviceAccount, and roleArn are required", 400);
        }
        if (request.getNamespace() == null || request.getNamespace().isBlank()) {
            throw new AwsException("InvalidParameterException", "namespace is required", 400);
        }
        if (request.getServiceAccount() == null || request.getServiceAccount().isBlank()) {
            throw new AwsException("InvalidParameterException", "serviceAccount is required", 400);
        }
        if (request.getRoleArn() == null || request.getRoleArn().isBlank()) {
            throw new AwsException("InvalidParameterException", "roleArn is required", 400);
        }
        String namespace = request.getNamespace();
        String serviceAccount = request.getServiceAccount();
        if (findPodIdentity(clusterName, namespace, serviceAccount) != null) {
            throw new AwsException("ResourceInUseException",
                    "Pod identity association already exists for " + namespace + "/" + serviceAccount, 409);
        }

        String region = regionResolver.getRegion();
        String accountId = regionResolver.getAccountId();
        String associationId = "a-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        Instant now = Instant.now();

        PodIdentityAssociation association = new PodIdentityAssociation();
        association.setClusterName(clusterName);
        association.setNamespace(namespace);
        association.setServiceAccount(serviceAccount);
        association.setRoleArn(request.getRoleArn());
        association.setAssociationId(associationId);
        association.setAssociationArn(AwsArnUtils.Arn.of("eks", region, accountId,
                "podidentityassociation/" + clusterName + "/" + associationId).toString());
        association.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());
        association.setCreatedAt(now);
        association.setModifiedAt(now);
        association.setDisableSessionTags(Boolean.TRUE.equals(request.getDisableSessionTags()));
        association.setTargetRoleArn(blankToNull(request.getTargetRoleArn()));
        association.setPolicy(blankToNull(request.getPolicy()));
        association.setAccountId(accountId);
        if (association.getTargetRoleArn() != null) {
            association.setExternalId(UUID.randomUUID().toString());
        }

        podIdentityStorage.put(podIdentityKey(clusterName, associationId), association);
        return association;
    }

    public Map<String, Object> listPodIdentityAssociations(String clusterName, String namespace,
            String serviceAccount, Integer maxResults, String nextToken) {
        describeCluster(clusterName);
        String prefix = clusterName + "/";
        List<Map<String, Object>> summaries = podIdentityStorage.scan(key -> key.startsWith(prefix)).stream()
                .filter(association -> namespace == null || namespace.isBlank()
                        || namespace.equals(association.getNamespace()))
                .filter(association -> serviceAccount == null || serviceAccount.isBlank()
                        || serviceAccount.equals(association.getServiceAccount()))
                .map(this::toPodIdentitySummary)
                .collect(Collectors.toList());
        return pageObjects(summaries, maxResults, nextToken, "associations");
    }

    public PodIdentityAssociation describePodIdentityAssociation(String clusterName, String associationId) {
        describeCluster(clusterName);
        if (associationId == null || associationId.isBlank()) {
            throw new AwsException("InvalidParameterException", "associationId is required", 400);
        }
        return podIdentityStorage.get(podIdentityKey(clusterName, associationId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No pod identity association found: " + associationId, 404));
    }

    public PodIdentityAssociation updatePodIdentityAssociation(String clusterName, String associationId,
            UpdatePodIdentityAssociationRequest request) {
        PodIdentityAssociation association = describePodIdentityAssociation(clusterName, associationId);
        if (request != null) {
            if (request.getRoleArn() != null && !request.getRoleArn().isBlank()) {
                association.setRoleArn(request.getRoleArn());
            }
            if (request.getDisableSessionTags() != null) {
                association.setDisableSessionTags(request.getDisableSessionTags());
            }
            if (request.getTargetRoleArn() != null) {
                String target = blankToNull(request.getTargetRoleArn());
                association.setTargetRoleArn(target);
                if (target != null && association.getExternalId() == null) {
                    association.setExternalId(UUID.randomUUID().toString());
                }
                if (target == null) {
                    association.setExternalId(null);
                }
            }
            if (request.getPolicy() != null) {
                association.setPolicy(blankToNull(request.getPolicy()));
            }
        }
        association.setModifiedAt(Instant.now());
        podIdentityStorage.put(podIdentityKey(clusterName, associationId), association);
        return association;
    }

    public PodIdentityAssociation deletePodIdentityAssociation(String clusterName, String associationId) {
        PodIdentityAssociation association = describePodIdentityAssociation(clusterName, associationId);
        podIdentityStorage.delete(podIdentityKey(clusterName, associationId));
        return association;
    }

    public Map<String, Object> startInsightsRefresh(String clusterName) {
        describeCluster(clusterName);
        Instant now = Instant.now();
        Map<String, Object> refresh = new LinkedHashMap<>();
        refresh.put("status", "COMPLETED");
        refresh.put("message", "Insights refresh completed");
        refresh.put("startedAt", now.getEpochSecond());
        refresh.put("endedAt", now.getEpochSecond());
        insightsRefreshByCluster.put(clusterName, refresh);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", refresh.get("message"));
        body.put("status", refresh.get("status"));
        return body;
    }

    public Map<String, Object> describeInsightsRefresh(String clusterName) {
        describeCluster(clusterName);
        Map<String, Object> refresh = insightsRefreshByCluster.get(clusterName);
        if (refresh == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "COMPLETED");
            return body;
        }
        return new LinkedHashMap<>(refresh);
    }

    @Override
    public String serviceKey() {
        return "eks";
    }

    @Override
    public void tagResource(String region, String resourceArn, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        String resource = resourcePart(resourceArn);
        if (resource.startsWith("access-entry/")) {
            AccessEntry entry = requireAccessEntryByArn(resourceArn);
            if (entry.getTags() == null) {
                entry.setTags(new HashMap<>());
            }
            entry.getTags().putAll(tags);
            entry.setModifiedAt(Instant.now());
            accessEntryStorage.put(accessEntryKey(entry.getClusterName(), entry.getPrincipalArn()), entry);
            return;
        }
        if (resource.startsWith("addon/")) {
            Addon addon = requireAddonByArn(resourceArn);
            if (addon.getTags() == null) {
                addon.setTags(new HashMap<>());
            }
            addon.getTags().putAll(tags);
            addon.setModifiedAt(Instant.now());
            addonStorage.put(addonKey(addon.getClusterName(), addon.getAddonName()), addon);
            return;
        }
        if (resource.startsWith("fargateprofile/")) {
            FargateProfile profile = requireFargateProfileByArn(resourceArn);
            if (profile.getTags() == null) {
                profile.setTags(new HashMap<>());
            }
            profile.getTags().putAll(tags);
            fargateProfileStorage.put(fargateProfileKey(profile.getClusterName(),
                    profile.getFargateProfileName()), profile);
            return;
        }
        if (resource.startsWith("podidentityassociation/")) {
            PodIdentityAssociation association = requirePodIdentityByArn(resourceArn);
            if (association.getTags() == null) {
                association.setTags(new HashMap<>());
            }
            association.getTags().putAll(tags);
            association.setModifiedAt(Instant.now());
            podIdentityStorage.put(podIdentityKey(association.getClusterName(),
                    association.getAssociationId()), association);
            return;
        }
        Cluster cluster = requireClusterByArn(resourceArn);
        if (cluster.getTags() == null) {
            cluster.setTags(new HashMap<>());
        }
        cluster.getTags().putAll(tags);
        storage.put(cluster.getName(), cluster);
    }

    @Override
    public void untagResource(String region, String resourceArn, List<String> tagKeys) {
        String resource = resourcePart(resourceArn);
        if (resource.startsWith("access-entry/")) {
            AccessEntry entry = requireAccessEntryByArn(resourceArn);
            if (entry.getTags() != null && tagKeys != null) {
                tagKeys.forEach(entry.getTags()::remove);
            }
            entry.setModifiedAt(Instant.now());
            accessEntryStorage.put(accessEntryKey(entry.getClusterName(), entry.getPrincipalArn()), entry);
            return;
        }
        if (resource.startsWith("addon/")) {
            Addon addon = requireAddonByArn(resourceArn);
            if (addon.getTags() != null && tagKeys != null) {
                tagKeys.forEach(addon.getTags()::remove);
            }
            addon.setModifiedAt(Instant.now());
            addonStorage.put(addonKey(addon.getClusterName(), addon.getAddonName()), addon);
            return;
        }
        if (resource.startsWith("fargateprofile/")) {
            FargateProfile profile = requireFargateProfileByArn(resourceArn);
            if (profile.getTags() != null && tagKeys != null) {
                tagKeys.forEach(profile.getTags()::remove);
            }
            fargateProfileStorage.put(fargateProfileKey(profile.getClusterName(),
                    profile.getFargateProfileName()), profile);
            return;
        }
        if (resource.startsWith("podidentityassociation/")) {
            PodIdentityAssociation association = requirePodIdentityByArn(resourceArn);
            if (association.getTags() != null && tagKeys != null) {
                tagKeys.forEach(association.getTags()::remove);
            }
            association.setModifiedAt(Instant.now());
            podIdentityStorage.put(podIdentityKey(association.getClusterName(),
                    association.getAssociationId()), association);
            return;
        }
        Cluster cluster = requireClusterByArn(resourceArn);
        if (cluster.getTags() != null && tagKeys != null) {
            tagKeys.forEach(cluster.getTags()::remove);
        }
        storage.put(cluster.getName(), cluster);
    }

    @Override
    public Map<String, String> listTags(String region, String resourceArn) {
        String resource = resourcePart(resourceArn);
        if (resource.startsWith("access-entry/")) {
            AccessEntry entry = requireAccessEntryByArn(resourceArn);
            return entry.getTags() != null ? entry.getTags() : Map.of();
        }
        if (resource.startsWith("addon/")) {
            Addon addon = requireAddonByArn(resourceArn);
            return addon.getTags() != null ? addon.getTags() : Map.of();
        }
        if (resource.startsWith("fargateprofile/")) {
            FargateProfile profile = requireFargateProfileByArn(resourceArn);
            return profile.getTags() != null ? profile.getTags() : Map.of();
        }
        if (resource.startsWith("podidentityassociation/")) {
            PodIdentityAssociation association = requirePodIdentityByArn(resourceArn);
            return association.getTags() != null ? association.getTags() : Map.of();
        }
        Cluster cluster = requireClusterByArn(resourceArn);
        return cluster.getTags() != null ? cluster.getTags() : Map.of();
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        tagResource(null, resourceArn, tags);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        untagResource(null, resourceArn, tagKeys);
    }

    public Map<String, String> listTagsForResource(String resourceArn) {
        return listTags(null, resourceArn);
    }

    private String resourcePart(String resourceArn) {
        try {
            return AwsArnUtils.parse(resourceArn).resource();
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterException",
                    "Invalid resource ARN: " + resourceArn, 400);
        }
    }

    private Cluster requireClusterByArn(String resourceArn) {
        String resource = resourcePart(resourceArn);
        if (!resource.startsWith("cluster/")) {
            throw new AwsException("ResourceNotFoundException",
                    "Resource not found: " + resourceArn, 404);
        }
        String clusterName = resource.substring("cluster/".length());
        return storage.get(clusterName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource not found: " + resourceArn, 404));
    }

    private Addon requireAddonByArn(String resourceArn) {
        return addonStorage.scan(k -> true).stream()
                .filter(addon -> resourceArn.equals(addon.getAddonArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource not found: " + resourceArn, 404));
    }

    private FargateProfile requireFargateProfileByArn(String resourceArn) {
        return fargateProfileStorage.scan(k -> true).stream()
                .filter(profile -> resourceArn.equals(profile.getFargateProfileArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource not found: " + resourceArn, 404));
    }

    private PodIdentityAssociation requirePodIdentityByArn(String resourceArn) {
        return podIdentityStorage.scan(k -> true).stream()
                .filter(association -> resourceArn.equals(association.getAssociationArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource not found: " + resourceArn, 404));
    }

    private PodIdentityAssociation findPodIdentity(String clusterName, String namespace, String serviceAccount) {
        String prefix = clusterName + "/";
        return podIdentityStorage.scan(key -> key.startsWith(prefix)).stream()
                .filter(association -> namespace.equals(association.getNamespace())
                        && serviceAccount.equals(association.getServiceAccount()))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> toPodIdentitySummary(PodIdentityAssociation association) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("clusterName", association.getClusterName());
        summary.put("namespace", association.getNamespace());
        summary.put("serviceAccount", association.getServiceAccount());
        summary.put("associationArn", association.getAssociationArn());
        summary.put("associationId", association.getAssociationId());
        if (association.getOwnerArn() != null) {
            summary.put("ownerArn", association.getOwnerArn());
        }
        return summary;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String addonKey(String clusterName, String addonName) {
        return clusterName + "/" + addonName;
    }

    private Addon.NamespaceConfig resolveNamespace(Addon.NamespaceConfig requested) {
        String namespace = requested != null && requested.getNamespace() != null
                && !requested.getNamespace().isBlank()
                ? requested.getNamespace() : "kube-system";
        return new Addon.NamespaceConfig(namespace);
    }

    private List<String> podIdentityArns(String region, String accountId, String clusterName,
            List<Map<String, String>> associations) {
        if (associations == null || associations.isEmpty()) {
            return List.of();
        }
        List<String> arns = new ArrayList<>();
        for (int ignored = 0; ignored < associations.size(); ignored++) {
            arns.add(AwsArnUtils.Arn.of("eks", region, accountId,
                    "podidentityassociation/" + clusterName + "/" + UUID.randomUUID()).toString());
        }
        return arns;
    }

    private AccessEntry requireAccessEntryByArn(String resourceArn) {
        return accessEntryStorage.scan(k -> true).stream()
                .filter(entry -> resourceArn.equals(entry.getAccessEntryArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource not found: " + resourceArn, 404));
    }

    private String accessEntryKey(String clusterName, String principalArn) {
        return clusterName + "::" + principalArn;
    }

    private boolean hasAssociatedPolicy(AccessEntry entry, String policyArn) {
        return entry.getAssociatedAccessPolicies().stream()
                .anyMatch(policy -> policyArn.equals(policy.getPolicyArn()));
    }

    private String buildAccessEntryArn(String region, String accountId, String clusterName,
            String principalArn, String id) {
        String principalType = "role";
        String principalAccount = accountId;
        String principalName = principalArn;
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(principalArn);
            if (!parsed.accountId().isEmpty()) {
                principalAccount = parsed.accountId();
            }
            String resource = parsed.resource();
            int slash = resource.indexOf('/');
            if (slash >= 0) {
                principalType = resource.substring(0, slash);
                principalName = resource.substring(slash + 1);
            } else if (!resource.isBlank()) {
                principalType = resource;
                principalName = resource;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall back to the raw principal ARN pieces above.
        }
        return AwsArnUtils.Arn.of("eks", region, accountId,
                "access-entry/" + clusterName + "/" + principalType + "/" + principalAccount
                        + "/" + principalName + "/" + id)
                .toString();
    }

    /**
     * Validates every requested subnet and returns the VPC they belong to.
     *
     * CreateCluster carries no vpcId — real EKS derives it from the subnets, and
     * #1942 reported resourcesVpcConfig.vpcId coming back blank because the
     * Subnet that requireSubnet already resolves was discarded here.
     *
     * @return the vpcId of the requested subnets, or null when none were given
     */
    private String validateSubnetsAndResolveVpcId(String region, ResourcesVpcConfig vpcConfig) {
        if (vpcConfig == null || vpcConfig.getSubnetIds() == null) {
            return null;
        }
        String vpcId = null;
        for (String subnetId : vpcConfig.getSubnetIds()) {
            try {
                vpcId = ec2Service.requireSubnet(region, subnetId).getVpcId();
            } catch (AwsException e) {
                throw new AwsException("InvalidParameterException",
                        "Subnet ID '" + subnetId + "' does not exist", 400);
            }
        }
        return vpcId;
    }

    private ResourcesVpcConfig buildVpcConfigResponse(ResourcesVpcConfig request, String resolvedVpcId) {
        ResourcesVpcConfig response = new ResourcesVpcConfig();
        if (request != null) {
            response.setSubnetIds(request.getSubnetIds() != null ? request.getSubnetIds() : List.of());
            response.setSecurityGroupIds(request.getSecurityGroupIds() != null ? request.getSecurityGroupIds() : List.of());
            // A caller-supplied vpcId still wins; otherwise fall back to the one
            // the subnets resolved to, and only then to empty.
            String vpcId = request.getVpcId() != null && !request.getVpcId().isBlank()
                    ? request.getVpcId()
                    : (resolvedVpcId != null ? resolvedVpcId : "");
            response.setVpcId(vpcId);
            response.setEndpointPublicAccess(
                    request.getEndpointPublicAccess() != null ? request.getEndpointPublicAccess() : Boolean.TRUE);
            response.setEndpointPrivateAccess(
                    request.getEndpointPrivateAccess() != null ? request.getEndpointPrivateAccess() : Boolean.FALSE);
            response.setPublicAccessCidrs(
                    request.getPublicAccessCidrs() != null ? request.getPublicAccessCidrs() : List.of("0.0.0.0/0"));
        } else {
            response.setSubnetIds(List.of());
            response.setSecurityGroupIds(List.of());
            response.setVpcId("");
            response.setEndpointPublicAccess(Boolean.TRUE);
            response.setEndpointPrivateAccess(Boolean.FALSE);
            response.setPublicAccessCidrs(List.of("0.0.0.0/0"));
        }
        return response;
    }

    private KubernetesNetworkConfig buildNetworkConfig(KubernetesNetworkConfig request) {
        KubernetesNetworkConfig config = new KubernetesNetworkConfig();
        if (request != null) {
            config.setServiceIpv4Cidr(request.getServiceIpv4Cidr() != null ? request.getServiceIpv4Cidr() : "10.100.0.0/16");
            config.setIpFamily(request.getIpFamily() != null ? request.getIpFamily() : "ipv4");
        } else {
            config.setServiceIpv4Cidr("10.100.0.0/16");
            config.setIpFamily("ipv4");
        }
        return config;
    }

    private String nodeGroupKey(String clusterName, String nodegroupName) {
        return clusterName + "/" + nodegroupName;
    }

    private String fargateProfileKey(String clusterName, String fargateProfileName) {
        return clusterName + "/" + fargateProfileName;
    }

    private String podIdentityKey(String clusterName, String associationId) {
        return clusterName + "/" + associationId;
    }

    private NodegroupScalingConfig defaultScalingConfig() {
        NodegroupScalingConfig scalingConfig = new NodegroupScalingConfig();
        scalingConfig.setMinSize(1);
        scalingConfig.setMaxSize(1);
        scalingConfig.setDesiredSize(1);
        return scalingConfig;
    }

    private Map<String, Integer> defaultUpdateConfig() {
        return Map.of("maxUnavailable", 1);
    }

    private Map<String, Object> defaultNodeGroupResources(String nodegroupName) {
        Map<String, Object> resources = new LinkedHashMap<>();
        Map<String, Object> autoScalingGroup = new LinkedHashMap<>();
        autoScalingGroup.put("name", "eks-" + nodegroupName + "-" + UUID.randomUUID().toString().substring(0, 8));
        resources.put("autoScalingGroups", List.of(autoScalingGroup));
        return resources;
    }

    private Map<String, List<Object>> defaultNodeGroupHealth() {
        Map<String, List<Object>> health = new LinkedHashMap<>();
        health.put("issues", new ArrayList<>());
        return health;
    }

    private FargateProfile.Health defaultFargateProfileHealth() {
        FargateProfile.Health health = new FargateProfile.Health();
        health.setIssues(List.of());
        return health;
    }

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                for (Cluster cluster : allClusters()) {
                    if (cluster.getStatus() == ClusterStatus.CREATING) {
                        if (clusterManager.isReady(cluster)) {
                            LOG.infov("EKS cluster {0} is now ACTIVE", cluster.getName());
                            clusterManager.finalizeCluster(cluster);
                            cluster.setStatus(ClusterStatus.ACTIVE);
                            putCluster(cluster);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("Error in EKS readiness poller", e);
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    public Map<String, Object> listAccessPolicies(Integer maxResults, String nextToken) {
        return pageObjects(EksCatalog.accessPolicies(), maxResults, nextToken, "accessPolicies");
    }

    public Map<String, Object> describeClusterVersions(Boolean defaultOnly, Boolean includeAll,
            String clusterType, List<String> clusterVersions, Integer maxResults, String nextToken,
            String status, String versionStatus) {
        List<Map<String, Object>> versions = new ArrayList<>(EksCatalog.clusterVersions());
        if (!Boolean.TRUE.equals(includeAll)) {
            versions.removeIf(version -> "unsupported".equals(version.get("status")));
        }
        if (Boolean.TRUE.equals(defaultOnly)) {
            versions.removeIf(version -> !Boolean.TRUE.equals(version.get("defaultVersion")));
        }
        if (clusterType != null && !clusterType.isBlank()) {
            versions.removeIf(version -> !clusterType.equals(version.get("clusterType")));
        }
        if (clusterVersions != null && !clusterVersions.isEmpty()) {
            versions.removeIf(version -> !clusterVersions.contains(version.get("clusterVersion")));
        }
        if (status != null && !status.isBlank()) {
            versions.removeIf(version -> !status.equals(version.get("status")));
        }
        if (versionStatus != null && !versionStatus.isBlank()) {
            versions.removeIf(version -> !versionStatus.equals(version.get("versionStatus")));
        }
        return pageObjects(versions, maxResults, nextToken, "clusterVersions");
    }

    public Map<String, Object> describeAddonVersions(String addonName, String kubernetesVersion,
            Integer maxResults, String nextToken, List<String> types, List<String> publishers,
            List<String> owners) {
        List<Map<String, Object>> addons = new ArrayList<>();
        for (Map<String, Object> addon : EksCatalog.addons()) {
            if (addonName != null && !addonName.isBlank() && !addonName.equals(addon.get("addonName"))) {
                continue;
            }
            if (types != null && !types.isEmpty() && !types.contains(addon.get("type"))) {
                continue;
            }
            if (publishers != null && !publishers.isEmpty() && !publishers.contains(addon.get("publisher"))) {
                continue;
            }
            if (owners != null && !owners.isEmpty() && !owners.contains(addon.get("owner"))) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(addon);
            if (kubernetesVersion != null && !kubernetesVersion.isBlank()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> versions = (List<Map<String, Object>>) copy.get("addonVersions");
                List<Map<String, Object>> filtered = new ArrayList<>();
                for (Map<String, Object> version : versions) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> compatibilities =
                            (List<Map<String, Object>>) version.get("compatibilities");
                    boolean matches = compatibilities.stream()
                            .anyMatch(compatibility -> kubernetesVersion.equals(compatibility.get("clusterVersion")));
                    if (matches) {
                        filtered.add(version);
                    }
                }
                if (filtered.isEmpty()) {
                    continue;
                }
                copy.put("addonVersions", filtered);
            }
            addons.add(copy);
        }
        return pageObjects(addons, maxResults, nextToken, "addons");
    }

    public Map<String, Object> describeAddonConfiguration(String addonName, String addonVersion) {
        if (addonName == null || addonName.isBlank() || addonVersion == null || addonVersion.isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "addonName and addonVersion are required", 400);
        }
        String schema = EksCatalog.configurationSchema(addonName, addonVersion);
        if (schema == null) {
            throw new AwsException("ResourceNotFoundException",
                    "No configuration found for addon " + addonName + " version " + addonVersion, 404);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addonName", addonName);
        body.put("addonVersion", addonVersion);
        body.put("configurationSchema", schema);
        return body;
    }

    private Map<String, Object> pageObjects(List<Map<String, Object>> items, Integer maxResults,
            String nextToken, String itemsKey) {
        int start = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                start = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidParameterException", "Invalid nextToken", 400);
            }
        }
        if (start < 0) {
            start = 0;
        }
        if (start > items.size()) {
            start = items.size();
        }
        int limit = (maxResults != null && maxResults > 0) ? maxResults : Math.max(items.size() - start, 0);
        int end = Math.min(items.size(), start + limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(itemsKey, items.subList(start, end));
        if (end < items.size()) {
            body.put("nextToken", Integer.toString(end));
        }
        return body;
    }

    private List<Cluster> allClusters() {
        if (storage instanceof AccountAwareStorageBackend<Cluster> aware) {
            return aware.scanAllAccounts();
        }
        return storage.scan(k -> true);
    }

    private void putCluster(Cluster cluster) {
        if (cluster.getAccountId() != null && storage instanceof AccountAwareStorageBackend<Cluster> aware) {
            aware.putForAccount(cluster.getAccountId(), cluster.getName(), cluster);
        } else {
            storage.put(cluster.getName(), cluster);
        }
    }
}
