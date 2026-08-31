package io.github.hectorvent.floci.services.finspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.finspace.model.FinSpaceEnvironment;
import io.github.hectorvent.floci.services.finspace.model.KxChangeset;
import io.github.hectorvent.floci.services.finspace.model.KxCluster;
import io.github.hectorvent.floci.services.finspace.model.KxDatabase;
import io.github.hectorvent.floci.services.finspace.model.KxDataview;
import io.github.hectorvent.floci.services.finspace.model.KxEnvironment;
import io.github.hectorvent.floci.services.finspace.model.KxNode;
import io.github.hectorvent.floci.services.finspace.model.KxUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon FinSpace Management restJson1 — classic environments plus managed kdb
 * ({@code kx}) environments, databases, clusters, users, changesets and dataviews.
 */
@ApplicationScoped
public class FinSpaceService implements TagHandler {

    static final String SERVICE = "finspace";
    private static final Pattern ENVIRONMENT_ID = Pattern.compile("[a-zA-Z0-9]{1,26}");
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,254}");
    private static final Pattern DATABASE_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}");
    private static final String KX_ENV_PREFIX = "kxEnvironment/";
    private static final String KX_DB_INFIX = "/kxDatabase/";
    private static final String CLASSIC_ENV_PREFIX = "environment/";

    private final StorageBackend<String, KxEnvironment> kxEnvironments;
    private final StorageBackend<String, KxDatabase> kxDatabases;
    private final StorageBackend<String, FinSpaceEnvironment> environments;
    private final StorageBackend<String, KxCluster> kxClusters;
    private final StorageBackend<String, KxUser> kxUsers;
    private final StorageBackend<String, KxChangeset> kxChangesets;
    private final StorageBackend<String, KxDataview> kxDataviews;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public FinSpaceService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("finspace", "finspace-kx-environments.json",
                        new TypeReference<Map<String, KxEnvironment>>() {
                        }),
                storageFactory.create("finspace", "finspace-kx-databases.json",
                        new TypeReference<Map<String, KxDatabase>>() {
                        }),
                storageFactory.create("finspace", "finspace-environments.json",
                        new TypeReference<Map<String, FinSpaceEnvironment>>() {
                        }),
                storageFactory.create("finspace", "finspace-kx-clusters.json",
                        new TypeReference<Map<String, KxCluster>>() {
                        }),
                storageFactory.create("finspace", "finspace-kx-users.json",
                        new TypeReference<Map<String, KxUser>>() {
                        }),
                storageFactory.create("finspace", "finspace-kx-changesets.json",
                        new TypeReference<Map<String, KxChangeset>>() {
                        }),
                storageFactory.create("finspace", "finspace-kx-dataviews.json",
                        new TypeReference<Map<String, KxDataview>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    FinSpaceService(
            StorageBackend<String, KxEnvironment> kxEnvironments,
            StorageBackend<String, KxDatabase> kxDatabases,
            StorageBackend<String, FinSpaceEnvironment> environments,
            StorageBackend<String, KxCluster> kxClusters,
            StorageBackend<String, KxUser> kxUsers,
            StorageBackend<String, KxChangeset> kxChangesets,
            StorageBackend<String, KxDataview> kxDataviews,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.kxEnvironments = kxEnvironments;
        this.kxDatabases = kxDatabases;
        this.environments = environments;
        this.kxClusters = kxClusters;
        this.kxUsers = kxUsers;
        this.kxChangesets = kxChangesets;
        this.kxDataviews = kxDataviews;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized KxEnvironment createKxEnvironment(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name);
        String kmsKeyId = requireText(request, "kmsKeyId");
        if (findKxByName(region, name) != null) {
            throw alreadyExists("A kdb environment named " + name + " already exists.");
        }
        long now = Instant.now().getEpochSecond();
        String account = regionResolver.getAccountId();
        String id = newId();
        KxEnvironment env = new KxEnvironment();
        env.setEnvironmentId(id);
        env.setName(name);
        env.setDescription(optionalText(request, "description"));
        env.setKmsKeyId(kmsKeyId);
        env.setStatus("CREATED");
        env.setTgwStatus("NONE");
        env.setDnsStatus("NONE");
        env.setAwsAccountId(account);
        env.setDedicatedServiceAccountId(account);
        env.setRegion(region);
        env.setEnvironmentArn(kxEnvironmentArn(region, account, id));
        env.setCertificateAuthorityArn(
                "arn:aws:acm-pca:" + region + ":" + account + ":certificate-authority/" + id);
        env.setAvailabilityZoneIds(List.of(region.replace("-", "") + "-az1"));
        env.setTags(readTags(request));
        env.setCreationTimestamp(now);
        env.setUpdateTimestamp(now);
        kxEnvironments.put(kxEnvKey(region, id), env);
        return env;
    }

    public KxEnvironment getKxEnvironment(String region, String environmentId) {
        return requireKxEnvironment(region, environmentId);
    }

    public synchronized KxEnvironment updateKxEnvironment(String region, String environmentId, JsonNode request) {
        requireObject(request, "Request body");
        KxEnvironment env = requireKxEnvironment(region, environmentId);
        if (request.has("name") && !request.get("name").isNull()) {
            String name = requireText(request, "name");
            validateName(name);
            KxEnvironment clash = findKxByName(region, name);
            if (clash != null && !environmentId.equals(clash.getEnvironmentId())) {
                throw alreadyExists("A kdb environment named " + name + " already exists.");
            }
            env.setName(name);
        }
        if (request.has("description") && !request.get("description").isNull()) {
            env.setDescription(requireText(request, "description"));
        }
        env.setUpdateTimestamp(Instant.now().getEpochSecond());
        kxEnvironments.put(kxEnvKey(region, environmentId), env);
        return env;
    }

    public synchronized KxEnvironment updateKxEnvironmentNetwork(
            String region, String environmentId, JsonNode request) {
        requireObject(request, "Request body");
        KxEnvironment env = requireKxEnvironment(region, environmentId);
        boolean hasNetwork = env.getTransitGatewayConfiguration() != null
                || (env.getCustomDNSConfiguration() != null
                && env.getCustomDNSConfiguration().isArray()
                && !env.getCustomDNSConfiguration().isEmpty());
        if (hasNetwork) {
            throw new AwsException(
                    "ConflictException",
                    "Network configuration for kdb environment " + environmentId + " cannot be changed.",
                    409);
        }
        if (request.has("transitGatewayConfiguration") && !request.get("transitGatewayConfiguration").isNull()) {
            env.setTransitGatewayConfiguration(request.get("transitGatewayConfiguration").deepCopy());
            env.setTgwStatus("SUCCESSFULLY_UPDATED");
        }
        if (request.has("customDNSConfiguration") && !request.get("customDNSConfiguration").isNull()) {
            env.setCustomDNSConfiguration(request.get("customDNSConfiguration").deepCopy());
            env.setDnsStatus("SUCCESSFULLY_UPDATED");
        }
        env.setUpdateTimestamp(Instant.now().getEpochSecond());
        kxEnvironments.put(kxEnvKey(region, environmentId), env);
        return env;
    }

    public synchronized void deleteKxEnvironment(String region, String environmentId) {
        requireKxEnvironment(region, environmentId);
        String prefix = region + "::" + environmentId + "::";
        for (KxDatabase database : kxDatabases.scan(key -> key.startsWith(prefix))) {
            kxDatabases.delete(kxDbKey(region, environmentId, database.getDatabaseName()));
        }
        for (KxCluster cluster : kxClusters.scan(key -> key.startsWith(prefix))) {
            kxClusters.delete(childKey(region, environmentId, cluster.getClusterName()));
        }
        for (KxUser user : kxUsers.scan(key -> key.startsWith(prefix))) {
            kxUsers.delete(childKey(region, environmentId, user.getUserName()));
        }
        for (KxChangeset changeset : kxChangesets.scan(key -> key.startsWith(prefix))) {
            kxChangesets.delete(changesetKey(region, environmentId, changeset.getDatabaseName(),
                    changeset.getChangesetId()));
        }
        for (KxDataview dataview : kxDataviews.scan(key -> key.startsWith(prefix))) {
            kxDataviews.delete(dataviewKey(region, environmentId, dataview.getDatabaseName(),
                    dataview.getDataviewName()));
        }
        kxEnvironments.delete(kxEnvKey(region, environmentId));
    }

    public List<KxEnvironment> listKxEnvironments(String region) {
        List<KxEnvironment> matches =
                new ArrayList<>(kxEnvironments.scan(key -> key.startsWith(region + "::")));
        matches.sort(Comparator.comparing(KxEnvironment::getName, Comparator.nullsLast(String::compareTo)));
        return matches;
    }

    public synchronized KxDatabase createKxDatabase(String region, String environmentId, JsonNode request) {
        requireObject(request, "Request body");
        KxEnvironment env = requireKxEnvironment(region, environmentId);
        String databaseName = requireText(request, "databaseName");
        validateDatabaseName(databaseName);
        String key = kxDbKey(region, environmentId, databaseName);
        if (kxDatabases.get(key).isPresent()) {
            throw alreadyExists("Database " + databaseName + " already exists.");
        }
        long now = Instant.now().getEpochSecond();
        KxDatabase database = new KxDatabase();
        database.setEnvironmentId(environmentId);
        database.setDatabaseName(databaseName);
        database.setDescription(optionalText(request, "description"));
        database.setRegion(region);
        database.setDatabaseArn(env.getEnvironmentArn() + KX_DB_INFIX + databaseName);
        database.setTags(readTags(request));
        database.setCreatedTimestamp(now);
        database.setLastModifiedTimestamp(now);
        kxDatabases.put(key, database);
        return database;
    }

    public KxDatabase getKxDatabase(String region, String environmentId, String databaseName) {
        requireKxEnvironment(region, environmentId);
        validateDatabaseName(databaseName);
        return kxDatabases.get(kxDbKey(region, environmentId, databaseName)).orElseThrow(
                () -> notFound("Database " + databaseName + " does not exist."));
    }

    public synchronized KxDatabase updateKxDatabase(
            String region, String environmentId, String databaseName, JsonNode request) {
        requireObject(request, "Request body");
        KxDatabase database = getKxDatabase(region, environmentId, databaseName);
        if (request.has("description") && !request.get("description").isNull()) {
            database.setDescription(requireText(request, "description"));
        }
        database.setLastModifiedTimestamp(Instant.now().getEpochSecond());
        kxDatabases.put(kxDbKey(region, environmentId, databaseName), database);
        return database;
    }

    public synchronized void deleteKxDatabase(String region, String environmentId, String databaseName) {
        getKxDatabase(region, environmentId, databaseName);
        kxDatabases.delete(kxDbKey(region, environmentId, databaseName));
    }

    public KxCluster getKxCluster(String region, String environmentId, String clusterName) {
        requireKxEnvironment(region, environmentId);
        if (clusterName == null || clusterName.isBlank()) {
            throw validation("clusterName is required.");
        }
        return kxClusters.get(childKey(region, environmentId, clusterName)).orElseThrow(
                () -> notFound("Cluster " + clusterName + " does not exist."));
    }

    public KxCluster requireKxCluster(String region, String environmentId, String clusterName) {
        return getKxCluster(region, environmentId, clusterName);
    }

    public void requireKxScalingGroup(String region, String environmentId, String scalingGroupName) {
        requireKxEnvironment(region, environmentId);
        if (scalingGroupName == null || scalingGroupName.isBlank()) {
            throw validation("scalingGroupName is required.");
        }
        throw notFound("Scaling group " + scalingGroupName + " does not exist.");
    }

    public void requireKxVolume(String region, String environmentId, String volumeName) {
        requireKxEnvironment(region, environmentId);
        if (volumeName == null || volumeName.isBlank()) {
            throw validation("volumeName is required.");
        }
        throw notFound("Volume " + volumeName + " does not exist.");
    }

    public synchronized KxCluster createKxCluster(String region, String environmentId, JsonNode request) {
        requireObject(request, "Request body");
        KxEnvironment env = requireKxEnvironment(region, environmentId);
        String clusterName = requireText(request, "clusterName");
        String clusterType = requireText(request, "clusterType");
        String key = childKey(region, environmentId, clusterName);
        if (kxClusters.get(key).isPresent()) {
            throw alreadyExists("Cluster " + clusterName + " already exists.");
        }
        long now = Instant.now().getEpochSecond();
        KxCluster cluster = new KxCluster();
        cluster.setEnvironmentId(environmentId);
        cluster.setClusterName(clusterName);
        cluster.setClusterType(clusterType);
        cluster.setStatus("RUNNING");
        cluster.setRegion(region);
        cluster.setAzMode(optionalText(request, "azMode"));
        cluster.setReleaseLabel(optionalText(request, "releaseLabel"));
        cluster.setAvailabilityZoneId(env.getAvailabilityZoneIds().isEmpty()
                ? null : env.getAvailabilityZoneIds().get(0));
        if (request.has("vpcConfiguration") && !request.get("vpcConfiguration").isNull()) {
            cluster.setVpcConfiguration(request.get("vpcConfiguration").deepCopy());
        }
        cluster.setClientToken(optionalText(request, "clientToken"));
        cluster.setTags(readTags(request));
        KxNode node = new KxNode();
        node.setNodeId(newId());
        node.setAvailabilityZoneId(cluster.getAvailabilityZoneId());
        node.setLaunchTime(now);
        node.setStatus("RUNNING");
        cluster.setNodes(List.of(node));
        kxClusters.put(key, cluster);
        return cluster;
    }

    public List<KxNode> listKxClusterNodes(String region, String environmentId, String clusterName) {
        return new ArrayList<>(getKxCluster(region, environmentId, clusterName).getNodes());
    }

    public ObjectNode getKxConnectionString(
            String region, String environmentId, String userArn, String clusterName) {
        requireKxEnvironment(region, environmentId);
        if (userArn == null || userArn.isBlank()) {
            throw validation("userArn is required.");
        }
        getKxCluster(region, environmentId, clusterName);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("signedConnectionString",
                "signed://" + environmentId + "/" + clusterName + "?user=" + userArn);
        return response;
    }

    public synchronized KxUser createKxUser(String region, String environmentId, JsonNode request) {
        requireObject(request, "Request body");
        KxEnvironment env = requireKxEnvironment(region, environmentId);
        String userName = requireText(request, "userName");
        String iamRole = requireText(request, "iamRole");
        String key = childKey(region, environmentId, userName);
        if (kxUsers.get(key).isPresent()) {
            throw alreadyExists("User " + userName + " already exists.");
        }
        KxUser user = new KxUser();
        user.setEnvironmentId(environmentId);
        user.setUserName(userName);
        user.setIamRole(iamRole);
        user.setRegion(region);
        user.setUserArn(env.getEnvironmentArn() + "/kxUser/" + userName);
        user.setClientToken(optionalText(request, "clientToken"));
        user.setTags(readTags(request));
        kxUsers.put(key, user);
        return user;
    }

    public KxUser getKxUser(String region, String environmentId, String userName) {
        requireKxEnvironment(region, environmentId);
        if (userName == null || userName.isBlank()) {
            throw validation("userName is required.");
        }
        return kxUsers.get(childKey(region, environmentId, userName)).orElseThrow(
                () -> notFound("User " + userName + " does not exist."));
    }

    public synchronized KxChangeset createKxChangeset(
            String region, String environmentId, String databaseName, JsonNode request) {
        requireObject(request, "Request body");
        getKxDatabase(region, environmentId, databaseName);
        long now = Instant.now().getEpochSecond();
        String changesetId = newId();
        KxChangeset changeset = new KxChangeset();
        changeset.setEnvironmentId(environmentId);
        changeset.setDatabaseName(databaseName);
        changeset.setChangesetId(changesetId);
        changeset.setStatus("COMPLETED");
        changeset.setRegion(region);
        changeset.setCreatedTimestamp(now);
        changeset.setLastModifiedTimestamp(now);
        changeset.setClientToken(optionalText(request, "clientToken"));
        if (request.has("changeRequests") && !request.get("changeRequests").isNull()) {
            changeset.setChangeRequests(request.get("changeRequests").deepCopy());
        }
        kxChangesets.put(changesetKey(region, environmentId, databaseName, changesetId), changeset);
        return changeset;
    }

    public List<KxChangeset> listKxChangesets(String region, String environmentId, String databaseName) {
        getKxDatabase(region, environmentId, databaseName);
        String prefix = region + "::" + environmentId + "::" + databaseName + "::";
        List<KxChangeset> matches = new ArrayList<>(kxChangesets.scan(key -> key.startsWith(prefix)));
        matches.sort(Comparator.comparing(KxChangeset::getCreatedTimestamp));
        return matches;
    }

    public synchronized KxDataview createKxDataview(
            String region, String environmentId, String databaseName, JsonNode request) {
        requireObject(request, "Request body");
        getKxDatabase(region, environmentId, databaseName);
        String dataviewName = requireText(request, "dataviewName");
        String key = dataviewKey(region, environmentId, databaseName, dataviewName);
        if (kxDataviews.get(key).isPresent()) {
            throw alreadyExists("Dataview " + dataviewName + " already exists.");
        }
        long now = Instant.now().getEpochSecond();
        KxDataview dataview = new KxDataview();
        dataview.setEnvironmentId(environmentId);
        dataview.setDatabaseName(databaseName);
        dataview.setDataviewName(dataviewName);
        dataview.setAzMode(optionalText(request, "azMode"));
        dataview.setAvailabilityZoneId(optionalText(request, "availabilityZoneId"));
        dataview.setChangesetId(optionalText(request, "changesetId"));
        dataview.setDescription(optionalText(request, "description"));
        dataview.setStatus("ACTIVE");
        dataview.setRegion(region);
        dataview.setCreatedTimestamp(now);
        dataview.setLastModifiedTimestamp(now);
        dataview.setClientToken(optionalText(request, "clientToken"));
        if (request.has("segmentConfigurations") && !request.get("segmentConfigurations").isNull()) {
            dataview.setSegmentConfigurations(request.get("segmentConfigurations").deepCopy());
        }
        kxDataviews.put(key, dataview);
        return dataview;
    }

    public KxDataview getKxDataview(
            String region, String environmentId, String databaseName, String dataviewName) {
        getKxDatabase(region, environmentId, databaseName);
        if (dataviewName == null || dataviewName.isBlank()) {
            throw validation("dataviewName is required.");
        }
        return kxDataviews.get(dataviewKey(region, environmentId, databaseName, dataviewName)).orElseThrow(
                () -> notFound("Dataview " + dataviewName + " does not exist."));
    }

    public FinSpaceEnvironment getEnvironment(String region, String environmentId) {
        return requireClassicEnvironment(region, environmentId);
    }

    public synchronized FinSpaceEnvironment createEnvironment(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name);
        if (findClassicByName(region, name) != null) {
            throw alreadyExists("An environment named " + name + " already exists.");
        }
        String federationMode = optionalText(request, "federationMode");
        if (federationMode != null) {
            validateFederationMode(federationMode);
        } else {
            federationMode = "LOCAL";
        }
        String account = regionResolver.getAccountId();
        String id = newId();
        while (environments.get(classicKey(region, id)).isPresent()) {
            id = newId();
        }
        FinSpaceEnvironment env = new FinSpaceEnvironment();
        env.setEnvironmentId(id);
        env.setEnvironmentArn(classicEnvironmentArn(region, account, id));
        env.setName(name);
        env.setDescription(optionalText(request, "description"));
        env.setStatus("CREATED");
        env.setAwsAccountId(account);
        env.setEnvironmentUrl("https://" + id + ".finspace.amazonaws.com");
        env.setSageMakerStudioDomainUrl("https://" + id + ".studio." + region + ".sagemaker.aws");
        String kmsKeyId = optionalText(request, "kmsKeyId");
        env.setKmsKeyId(kmsKeyId != null
                ? kmsKeyId
                : "arn:aws:kms:" + region + ":" + account + ":key/" + id);
        env.setDedicatedServiceAccountId(account);
        env.setFederationMode(federationMode);
        if (request.has("federationParameters") && !request.get("federationParameters").isNull()) {
            JsonNode parameters = request.get("federationParameters");
            if (!parameters.isObject()) {
                throw validation("federationParameters must be an object.");
            }
            env.setFederationParameters(parameters.deepCopy());
        }
        env.setTags(readTags(request));
        env.setUpdateTimestamp(Instant.now().getEpochSecond());
        environments.put(classicKey(region, id), env);
        return env;
    }

    public synchronized FinSpaceEnvironment updateEnvironment(
            String region, String environmentId, JsonNode request) {
        requireObject(request, "Request body");
        FinSpaceEnvironment env = requireClassicEnvironment(region, environmentId);
        if (request.has("name") && !request.get("name").isNull()) {
            String name = requireText(request, "name");
            validateName(name);
            FinSpaceEnvironment clash = findClassicByName(region, name);
            if (clash != null && !env.getEnvironmentId().equals(clash.getEnvironmentId())) {
                throw alreadyExists("An environment named " + name + " already exists.");
            }
            env.setName(name);
        }
        if (request.has("description")) {
            env.setDescription(optionalText(request, "description"));
        }
        if (request.has("federationMode") && !request.get("federationMode").isNull()) {
            String federationMode = requireText(request, "federationMode");
            validateFederationMode(federationMode);
            env.setFederationMode(federationMode);
        }
        if (request.has("federationParameters") && !request.get("federationParameters").isNull()) {
            JsonNode parameters = request.get("federationParameters");
            if (!parameters.isObject()) {
                throw validation("federationParameters must be an object.");
            }
            env.setFederationParameters(parameters.deepCopy());
        }
        env.setUpdateTimestamp(Instant.now().getEpochSecond());
        environments.put(classicKey(region, env.getEnvironmentId()), env);
        return env;
    }

    public synchronized void deleteEnvironment(String region, String environmentId) {
        FinSpaceEnvironment env = requireClassicEnvironment(region, environmentId);
        environments.delete(classicKey(region, env.getEnvironmentId()));
    }

    public List<FinSpaceEnvironment> listEnvironments(String region) {
        List<FinSpaceEnvironment> matches =
                new ArrayList<>(environments.scan(key -> key.startsWith(region + "::")));
        matches.sort(Comparator.comparing(
                FinSpaceEnvironment::getName, Comparator.nullsLast(String::compareTo)));
        return matches;
    }

    public ObjectNode toCreateClassicEnvironment(FinSpaceEnvironment env) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("environmentId", env.getEnvironmentId());
        node.put("environmentArn", env.getEnvironmentArn());
        if (env.getEnvironmentUrl() != null) {
            node.put("environmentUrl", env.getEnvironmentUrl());
        }
        return node;
    }

    public ObjectNode toEnvironment(KxEnvironment env) {
        return toKxEnvironmentNode(env);
    }

    public ObjectNode toCreateEnvironment(KxEnvironment env) {
        return toCreateKxEnvironmentNode(env);
    }

    public ObjectNode toDatabase(KxDatabase database) {
        return toKxDatabaseNode(database);
    }

    public ObjectNode toKxEnvironmentNode(KxEnvironment env) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", env.getName());
        node.put("environmentId", env.getEnvironmentId());
        node.put("awsAccountId", env.getAwsAccountId());
        node.put("status", env.getStatus());
        node.put("tgwStatus", env.getTgwStatus());
        node.put("dnsStatus", env.getDnsStatus());
        if (env.getDescription() != null) {
            node.put("description", env.getDescription());
        }
        node.put("environmentArn", env.getEnvironmentArn());
        node.put("kmsKeyId", env.getKmsKeyId());
        node.put("dedicatedServiceAccountId", env.getDedicatedServiceAccountId());
        if (env.getTransitGatewayConfiguration() != null) {
            node.set("transitGatewayConfiguration", env.getTransitGatewayConfiguration());
        }
        if (env.getCustomDNSConfiguration() != null) {
            node.set("customDNSConfiguration", env.getCustomDNSConfiguration());
        }
        node.put("creationTimestamp", env.getCreationTimestamp());
        node.put("updateTimestamp", env.getUpdateTimestamp());
        ArrayNode azs = node.putArray("availabilityZoneIds");
        for (String az : env.getAvailabilityZoneIds()) {
            azs.add(az);
        }
        if (env.getCertificateAuthorityArn() != null) {
            node.put("certificateAuthorityArn", env.getCertificateAuthorityArn());
        }
        return node;
    }

    public ObjectNode toCreateKxEnvironmentNode(KxEnvironment env) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", env.getName());
        node.put("status", env.getStatus());
        node.put("environmentId", env.getEnvironmentId());
        if (env.getDescription() != null) {
            node.put("description", env.getDescription());
        }
        node.put("environmentArn", env.getEnvironmentArn());
        node.put("kmsKeyId", env.getKmsKeyId());
        node.put("creationTimestamp", env.getCreationTimestamp());
        return node;
    }

    public ObjectNode toKxDatabaseNode(KxDatabase database) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("databaseName", database.getDatabaseName());
        node.put("databaseArn", database.getDatabaseArn());
        node.put("environmentId", database.getEnvironmentId());
        if (database.getDescription() != null) {
            node.put("description", database.getDescription());
        }
        node.put("createdTimestamp", database.getCreatedTimestamp());
        node.put("lastModifiedTimestamp", database.getLastModifiedTimestamp());
        node.put("numBytes", 0);
        node.put("numChangesets", 0);
        node.put("numFiles", 0);
        return node;
    }

    public ObjectNode toEnvironmentNode(FinSpaceEnvironment env) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", env.getName());
        node.put("environmentId", env.getEnvironmentId());
        if (env.getAwsAccountId() != null) {
            node.put("awsAccountId", env.getAwsAccountId());
        }
        node.put("status", env.getStatus());
        if (env.getEnvironmentUrl() != null) {
            node.put("environmentUrl", env.getEnvironmentUrl());
        }
        if (env.getDescription() != null) {
            node.put("description", env.getDescription());
        }
        node.put("environmentArn", env.getEnvironmentArn());
        if (env.getSageMakerStudioDomainUrl() != null) {
            node.put("sageMakerStudioDomainUrl", env.getSageMakerStudioDomainUrl());
        }
        if (env.getKmsKeyId() != null) {
            node.put("kmsKeyId", env.getKmsKeyId());
        }
        if (env.getDedicatedServiceAccountId() != null) {
            node.put("dedicatedServiceAccountId", env.getDedicatedServiceAccountId());
        }
        if (env.getFederationMode() != null) {
            node.put("federationMode", env.getFederationMode());
        }
        if (env.getFederationParameters() != null) {
            node.set("federationParameters", env.getFederationParameters());
        }
        return node;
    }

    public ObjectNode toUserNode(KxUser user) {
        return toUser(user);
    }

    public ObjectNode toChangesetNode(KxChangeset changeset) {
        return toChangeset(changeset);
    }

    public ObjectNode toDataviewNode(KxDataview dataview) {
        return toDataview(dataview);
    }

    public ObjectNode toClusterNode(KxCluster cluster) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("environmentId", cluster.getEnvironmentId());
        response.put("status", cluster.getStatus());
        response.put("clusterName", cluster.getClusterName());
        response.put("clusterType", cluster.getClusterType());
        if (cluster.getAzMode() != null) {
            response.put("azMode", cluster.getAzMode());
        }
        if (cluster.getReleaseLabel() != null) {
            response.put("releaseLabel", cluster.getReleaseLabel());
        }
        if (cluster.getVpcConfiguration() != null) {
            response.set("vpcConfiguration", cluster.getVpcConfiguration());
        }
        return response;
    }

    public ObjectNode toUser(KxUser user) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("userName", user.getUserName());
        node.put("userArn", user.getUserArn());
        node.put("environmentId", user.getEnvironmentId());
        node.put("iamRole", user.getIamRole());
        return node;
    }

    public ObjectNode toChangeset(KxChangeset changeset) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("changesetId", changeset.getChangesetId());
        node.put("databaseName", changeset.getDatabaseName());
        node.put("environmentId", changeset.getEnvironmentId());
        node.put("status", changeset.getStatus());
        node.put("createdTimestamp", changeset.getCreatedTimestamp());
        node.put("lastModifiedTimestamp", changeset.getLastModifiedTimestamp());
        if (changeset.getChangeRequests() != null) {
            node.set("changeRequests", changeset.getChangeRequests());
        }
        return node;
    }

    public ObjectNode toChangesetSummary(KxChangeset changeset) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("changesetId", changeset.getChangesetId());
        node.put("status", changeset.getStatus());
        node.put("createdTimestamp", changeset.getCreatedTimestamp());
        node.put("lastModifiedTimestamp", changeset.getLastModifiedTimestamp());
        return node;
    }

    public ObjectNode toDataview(KxDataview dataview) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("dataviewName", dataview.getDataviewName());
        node.put("databaseName", dataview.getDatabaseName());
        node.put("environmentId", dataview.getEnvironmentId());
        node.put("status", dataview.getStatus());
        if (dataview.getAzMode() != null) {
            node.put("azMode", dataview.getAzMode());
        }
        if (dataview.getDescription() != null) {
            node.put("description", dataview.getDescription());
        }
        node.put("createdTimestamp", dataview.getCreatedTimestamp());
        node.put("lastModifiedTimestamp", dataview.getLastModifiedTimestamp());
        return node;
    }

    public ObjectNode toNode(KxNode node) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("nodeId", node.getNodeId());
        json.put("status", node.getStatus());
        json.put("launchTime", node.getLaunchTime());
        if (node.getAvailabilityZoneId() != null) {
            json.put("availabilityZoneId", node.getAvailabilityZoneId());
        }
        return json;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return new LinkedHashMap<>(tagsOf(requireTagged(region, arn)));
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagsOf(tagged));
        if (tags != null) {
            current.putAll(tags);
        }
        persistTags(region, tagged, current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagsOf(tagged));
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        persistTags(region, tagged, current);
    }

    private KxEnvironment requireKxEnvironment(String region, String environmentId) {
        validateEnvironmentId(environmentId);
        return kxEnvironments.get(kxEnvKey(region, environmentId)).orElseThrow(
                () -> notFound("Kdb environment " + environmentId + " does not exist."));
    }

    private FinSpaceEnvironment requireClassicEnvironment(String region, String environmentId) {
        validateEnvironmentId(environmentId);
        return environments.get(classicKey(region, environmentId)).orElseThrow(
                () -> notFound("Environment " + environmentId + " does not exist."));
    }

    private FinSpaceEnvironment findClassicByName(String region, String name) {
        for (FinSpaceEnvironment env : environments.scan(key -> key.startsWith(region + "::"))) {
            if (name.equals(env.getName())) {
                return env;
            }
        }
        return null;
    }

    private KxEnvironment findKxByName(String region, String name) {
        for (KxEnvironment env : kxEnvironments.scan(key -> key.startsWith(region + "::"))) {
            if (name.equals(env.getName())) {
                return env;
            }
        }
        return null;
    }

    private Tagged requireTagged(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("Invalid resource ARN.");
        }
        if (!SERVICE.equals(parsed.service())) {
            throw validation("Invalid resource ARN.");
        }
        String resource = parsed.resource();
        if (resource == null) {
            throw validation("Invalid resource ARN.");
        }
        if (resource.startsWith(KX_ENV_PREFIX) && resource.contains(KX_DB_INFIX)) {
            int idx = resource.indexOf(KX_DB_INFIX);
            String environmentId = resource.substring(KX_ENV_PREFIX.length(), idx);
            String databaseName = resource.substring(idx + KX_DB_INFIX.length());
            return new Tagged(getKxDatabase(region, environmentId, databaseName), null, null);
        }
        if (resource.startsWith(KX_ENV_PREFIX)) {
            String environmentId = resource.substring(KX_ENV_PREFIX.length());
            return new Tagged(null, requireKxEnvironment(region, environmentId), null);
        }
        if (resource.startsWith(CLASSIC_ENV_PREFIX)) {
            String environmentId = resource.substring(CLASSIC_ENV_PREFIX.length());
            return new Tagged(null, null, requireClassicEnvironment(region, environmentId));
        }
        throw validation("Invalid resource ARN.");
    }

    private static Map<String, String> tagsOf(Tagged tagged) {
        if (tagged.database() != null) {
            return tagged.database().getTags();
        }
        if (tagged.kxEnvironment() != null) {
            return tagged.kxEnvironment().getTags();
        }
        return tagged.environment().getTags();
    }

    private void persistTags(String region, Tagged tagged, Map<String, String> tags) {
        long now = Instant.now().getEpochSecond();
        if (tagged.database() != null) {
            KxDatabase database = tagged.database();
            database.setTags(tags);
            database.setLastModifiedTimestamp(now);
            kxDatabases.put(kxDbKey(region, database.getEnvironmentId(), database.getDatabaseName()), database);
            return;
        }
        if (tagged.kxEnvironment() != null) {
            KxEnvironment env = tagged.kxEnvironment();
            env.setTags(tags);
            env.setUpdateTimestamp(now);
            kxEnvironments.put(kxEnvKey(region, env.getEnvironmentId()), env);
            return;
        }
        FinSpaceEnvironment env = tagged.environment();
        env.setTags(tags);
        env.setUpdateTimestamp(now);
        environments.put(classicKey(region, env.getEnvironmentId()), env);
    }

    private static void validateEnvironmentId(String environmentId) {
        if (environmentId == null || !ENVIRONMENT_ID.matcher(environmentId).matches()) {
            throw validation("environmentId must match ^[a-zA-Z0-9]{1,26}$.");
        }
    }

    private static void validateName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw validation("name is invalid.");
        }
    }

    private static void validateDatabaseName(String name) {
        if (name == null || !DATABASE_NAME.matcher(name).matches()) {
            throw validation("databaseName is invalid.");
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 26);
    }

    private static String kxEnvKey(String region, String environmentId) {
        return region + "::" + environmentId;
    }

    private static String kxDbKey(String region, String environmentId, String databaseName) {
        return region + "::" + environmentId + "::" + databaseName;
    }

    private static String childKey(String region, String environmentId, String name) {
        return region + "::" + environmentId + "::" + name;
    }

    private static String changesetKey(
            String region, String environmentId, String databaseName, String changesetId) {
        return region + "::" + environmentId + "::" + databaseName + "::" + changesetId;
    }

    private static String dataviewKey(
            String region, String environmentId, String databaseName, String dataviewName) {
        return region + "::" + environmentId + "::" + databaseName + "::" + dataviewName;
    }

    private static String classicKey(String region, String environmentId) {
        return region + "::" + environmentId;
    }

    private static String kxEnvironmentArn(String region, String accountId, String environmentId) {
        return AwsArnUtils.Arn.of(SERVICE, region, accountId, KX_ENV_PREFIX + environmentId).toString();
    }

    private static String classicEnvironmentArn(String region, String accountId, String environmentId) {
        return AwsArnUtils.Arn.of(SERVICE, region, accountId, CLASSIC_ENV_PREFIX + environmentId).toString();
    }

    private static void validateFederationMode(String federationMode) {
        if (!"FEDERATED".equals(federationMode) && !"LOCAL".equals(federationMode)) {
            throw validation("federationMode must be FEDERATED or LOCAL.");
        }
    }

    private Map<String, String> readTags(JsonNode request) {
        if (request == null || !request.has("tags") || request.get("tags").isNull()) {
            return new LinkedHashMap<>();
        }
        JsonNode tagsNode = request.get("tags");
        if (!tagsNode.isObject()) {
            throw validation("tags must be an object.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && entry.getValue().isTextual()) {
                tags.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return tags;
    }

    private static void requireObject(JsonNode request, String label) {
        if (request == null || !request.isObject()) {
            throw validation(label + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            throw validation(field + " is required.");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException alreadyExists(String message) {
        return new AwsException("ResourceAlreadyExistsException", message, 409);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private record Tagged(KxDatabase database, KxEnvironment kxEnvironment, FinSpaceEnvironment environment) {
    }
}
