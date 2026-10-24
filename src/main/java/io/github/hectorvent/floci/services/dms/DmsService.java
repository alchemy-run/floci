package io.github.hectorvent.floci.services.dms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dms.DmsRequests.Filter;
import io.github.hectorvent.floci.services.dms.model.DmsConnection;
import io.github.hectorvent.floci.services.dms.model.DmsEndpoint;
import io.github.hectorvent.floci.services.dms.model.DmsEvent;
import io.github.hectorvent.floci.services.dms.model.ReplicationInstance;
import io.github.hectorvent.floci.services.dms.model.ReplicationSubnetGroup;
import io.github.hectorvent.floci.services.dms.model.ResourceTag;
import io.github.hectorvent.floci.services.dms.model.SchemaRefresh;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.hectorvent.floci.services.dms.DmsRequests.alreadyExists;
import static io.github.hectorvent.floci.services.dms.DmsRequests.bool;
import static io.github.hectorvent.floci.services.dms.DmsRequests.integer;
import static io.github.hectorvent.floci.services.dms.DmsRequests.invalidCombination;
import static io.github.hectorvent.floci.services.dms.DmsRequests.invalidParameter;
import static io.github.hectorvent.floci.services.dms.DmsRequests.invalidState;
import static io.github.hectorvent.floci.services.dms.DmsRequests.maxRecords;
import static io.github.hectorvent.floci.services.dms.DmsRequests.optionalStringList;
import static io.github.hectorvent.floci.services.dms.DmsRequests.readTags;
import static io.github.hectorvent.floci.services.dms.DmsRequests.requireText;
import static io.github.hectorvent.floci.services.dms.DmsRequests.resourceNotFound;
import static io.github.hectorvent.floci.services.dms.DmsRequests.serialization;
import static io.github.hectorvent.floci.services.dms.DmsRequests.stringList;
import static io.github.hectorvent.floci.services.dms.DmsRequests.text;

/**
 * DMS control plane: replication subnet groups, endpoints, replication instances, connection
 * tests, schema refreshes, and the event history.
 *
 * <p>Replication instances are records only. Floci runs no replication compute, so an instance
 * settles from {@code creating}, {@code modifying}, or {@code rebooting} to {@code available} the
 * next time it is observed after the request that started the transition, and it owns no network
 * interface. Floci has no replication tasks or replication configs, so operations addressing one
 * answer with the fault DMS returns for an ARN that names nothing.
 *
 * <p>TestConnection and RefreshSchemas do the real work: they log in to the endpoint's database
 * asynchronously (see {@link DmsConnectivityProbe}) and record the honest outcome.
 */
@ApplicationScoped
public class DmsService implements Resettable {

    private static final Pattern SUBNET_GROUP_IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]+");
    private static final String SUBNET_GROUP_ID_FILTER = "replication-subnet-group-id";
    private static final int MINIMUM_AVAILABILITY_ZONES = 2;
    private static final String SUBNET_GROUP_ARN_RESOURCE_TYPE = "subgrp";
    private static final String ENDPOINT_ARN_RESOURCE_TYPE = "endpoint";
    private static final String INSTANCE_ARN_RESOURCE_TYPE = "rep";
    private static final int ENDPOINT_IDENTIFIER_MAX_LENGTH = 255;
    private static final int INSTANCE_IDENTIFIER_MAX_LENGTH = 63;
    private static final String DEFAULT_MAINTENANCE_WINDOW = "sun:06:00-sun:06:30";
    private static final Duration EVENT_RETENTION = Duration.ofDays(14);
    private static final long DEFAULT_EVENT_DURATION_MINUTES = 60;
    private static final Pattern MAINTENANCE_WINDOW = Pattern.compile(
            "(mon|tue|wed|thu|fri|sat|sun):([01]\\d|2[0-3]):([0-5]\\d)-(mon|tue|wed|thu|fri|sat|sun):([01]\\d|2[0-3]):([0-5]\\d)");
    private static final List<String> DAYS = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    static final String STATUS_AVAILABLE = "available";
    static final String STATUS_CREATING = "creating";
    static final String STATUS_MODIFYING = "modifying";
    static final String STATUS_REBOOTING = "rebooting";
    static final String STATUS_DELETING = "deleting";

    /** Engine settings structures an endpoint accepts, by wire member name. */
    static final List<String> ENDPOINT_SETTINGS_MEMBERS = List.of(
            "DynamoDbSettings", "S3Settings", "DmsTransferSettings", "MongoDbSettings", "KinesisSettings",
            "KafkaSettings", "ElasticsearchSettings", "NeptuneSettings", "RedshiftSettings",
            "PostgreSQLSettings", "MySQLSettings", "OracleSettings", "SybaseSettings",
            "MicrosoftSQLServerSettings", "IBMDb2Settings", "DocDbSettings", "RedisSettings",
            "GcpMySQLSettings", "TimestreamSettings");

    private static final Set<String> ENDPOINT_FILTERS =
            Set.of("endpoint-arn", "endpoint-type", "endpoint-id", "engine-name");
    private static final Set<String> INSTANCE_FILTERS = Set.of("replication-instance-arn",
            "replication-instance-id", "replication-instance-class", "engine-version");
    private static final Set<String> CONNECTION_FILTERS = Set.of("endpoint-arn", "replication-instance-arn");
    private static final Set<String> TASK_FILTERS = Set.of("replication-task-arn", "replication-task-id",
            "migration-type", "endpoint-arn", "replication-instance-arn");
    private static final Set<String> EVENT_FILTERS = Set.of("replication-instance-id");
    private static final Set<String> EVENT_SOURCE_TYPES = Set.of("replication-instance", "replication-task");
    private static final Set<String> START_TASK_TYPES =
            Set.of("start-replication", "resume-processing", "reload-target");
    private static final Set<String> START_REPLICATION_TYPES =
            Set.of("start-replication", "resume-processing", "reload-target");

    private final AccountAwareStorageBackend<ReplicationSubnetGroup> subnetGroups;
    private final AccountAwareStorageBackend<DmsEndpoint> endpoints;
    private final AccountAwareStorageBackend<ReplicationInstance> instances;
    private final AccountAwareStorageBackend<DmsConnection> connections;
    private final AccountAwareStorageBackend<SchemaRefresh> schemaRefreshes;
    private final AccountAwareStorageBackend<DmsEvent> events;
    private final Ec2Service ec2Service;
    private final RegionResolver regionResolver;
    private final DmsConnectivityProbe probe;
    private final DmsEventPublisher eventPublisher;
    private final ExecutorService executor;
    private final SecureRandom random = new SecureRandom();

    /** One orderable (engine version, instance class) pair. */
    public record OrderableInstance(String engineVersion, String instanceClass, int includedStorage,
                                    List<String> availabilityZones) {
    }

    @Inject
    public DmsService(StorageFactory storageFactory, Ec2Service ec2Service, RegionResolver regionResolver,
                      DmsConnectivityProbe probe, DmsEventPublisher eventPublisher) {
        this(storageFactory, ec2Service, regionResolver, probe, eventPublisher,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    DmsService(StorageFactory storageFactory, Ec2Service ec2Service, RegionResolver regionResolver,
               DmsConnectivityProbe probe, DmsEventPublisher eventPublisher, ExecutorService executor) {
        this.subnetGroups = storageFactory.create("dms", "dms-replication-subnet-groups.json",
                new TypeReference<Map<String, ReplicationSubnetGroup>>() {});
        this.endpoints = storageFactory.create("dms", "dms-endpoints.json",
                new TypeReference<Map<String, DmsEndpoint>>() {});
        this.instances = storageFactory.create("dms", "dms-replication-instances.json",
                new TypeReference<Map<String, ReplicationInstance>>() {});
        this.connections = storageFactory.create("dms", "dms-connections.json",
                new TypeReference<Map<String, DmsConnection>>() {});
        this.schemaRefreshes = storageFactory.create("dms", "dms-schema-refreshes.json",
                new TypeReference<Map<String, SchemaRefresh>>() {});
        this.events = storageFactory.create("dms", "dms-events.json",
                new TypeReference<Map<String, DmsEvent>>() {});
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
        this.probe = probe;
        this.eventPublisher = eventPublisher;
        this.executor = executor;
        failInterruptedProbes();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public void clear() {
        subnetGroups.clear();
        endpoints.clear();
        instances.clear();
        connections.clear();
        schemaRefreshes.clear();
        events.clear();
    }

    // ── Replication subnet groups ───────────────────────────────────────────

    public synchronized ReplicationSubnetGroup createReplicationSubnetGroup(JsonNode request, String region) {
        String identifier = requireSubnetGroupIdentifier(request);
        String description = requireDescription(request);
        List<String> subnetIds = requireSubnetIds(request);
        if (subnetGroups.get(storageKey(region, identifier)).isPresent()) {
            throw alreadyExists("The resource you are attempting to create already exists.");
        }

        ReplicationSubnetGroup group = buildSubnetGroup(identifier, description, subnetIds, region);
        group.setTags(readTags(request.get("Tags")));
        subnetGroups.put(storageKey(region, identifier), group);
        return group;
    }

    /**
     * Pages by identifier through the shared opaque-cursor helper, so {@code Marker} stays
     * resumable when a group is created or deleted between requests.
     */
    public PaginatedResult<ReplicationSubnetGroup> describeReplicationSubnetGroups(JsonNode request, String region) {
        Integer maxRecords = maxRecords(request);
        String marker = text(request, "Marker");
        List<String> requestedIdentifiers = subnetGroupIdentifierFilters(request);
        List<ReplicationSubnetGroup> matching;
        if (!requestedIdentifiers.isEmpty()) {
            matching = requestedIdentifiers.stream()
                    .map(identifier -> subnetGroups.get(storageKey(region, identifier))
                            .orElseThrow(() -> subnetGroupNotFound(identifier)))
                    .toList();
        } else {
            matching = subnetGroups.scan(regionKeys(region));
        }
        return paginate(matching, ReplicationSubnetGroup::getReplicationSubnetGroupIdentifier, maxRecords, marker);
    }

    /**
     * Replaces the subnet set (and optionally the description) of an existing group. A subnet
     * cannot be dropped while a replication instance still sits in its Availability Zone.
     */
    public synchronized ReplicationSubnetGroup modifyReplicationSubnetGroup(JsonNode request, String region) {
        String identifier = requireSubnetGroupIdentifier(request);
        String key = storageKey(region, identifier);
        ReplicationSubnetGroup existing = subnetGroups.get(key).orElseThrow(() -> subnetGroupNotFound(identifier));
        String description = optionalDescription(request);
        List<String> subnetIds = requireSubnetIds(request);

        ReplicationSubnetGroup modified = buildSubnetGroup(identifier,
                description != null ? description : existing.getReplicationSubnetGroupDescription(),
                subnetIds, region);
        List<ReplicationInstance> placed = instancesInGroup(region, identifier);
        for (ReplicationInstance instance : placed) {
            if (!Objects.equals(modified.getVpcId(), existing.getVpcId())) {
                throw new AwsException("InvalidSubnet", "Replication subnet group " + identifier
                        + " is in use by replication instance " + instance.getReplicationInstanceIdentifier()
                        + " and cannot move to another VPC.", 400);
            }
            Set<String> zones = Set.copyOf(modified.getSubnetAvailabilityZones().values());
            for (String zone : instanceZones(instance)) {
                if (!zones.contains(zone)) {
                    throw new AwsException("SubnetAlreadyInUse", "The subnets in Availability Zone " + zone
                            + " are in use by replication instance "
                            + instance.getReplicationInstanceIdentifier() + ".", 400);
                }
            }
        }
        modified.setTags(existing.getTags());
        subnetGroups.put(key, modified);
        for (ReplicationInstance instance : placed) {
            instance.setSubnetGroup(modified);
            instances.put(storageKey(region, instance.getReplicationInstanceIdentifier()), instance);
        }
        return modified;
    }

    public synchronized void deleteReplicationSubnetGroup(JsonNode request, String region) {
        String identifier = requireSubnetGroupIdentifier(request);
        String key = storageKey(region, identifier);
        if (subnetGroups.get(key).isEmpty()) {
            throw subnetGroupNotFound(identifier);
        }
        List<ReplicationInstance> placed = instancesInGroup(region, identifier);
        if (!placed.isEmpty()) {
            throw invalidState("Replication subnet group " + identifier + " is in use by replication instance "
                    + placed.getFirst().getReplicationInstanceIdentifier() + ".");
        }
        subnetGroups.delete(key);
    }

    // ── Endpoints ───────────────────────────────────────────────────────────

    public synchronized DmsEndpoint createEndpoint(JsonNode request, String region) {
        String identifier = DmsRequests.dmsIdentifier(text(request, "EndpointIdentifier"),
                "EndpointIdentifier", ENDPOINT_IDENTIFIER_MAX_LENGTH);
        String endpointType = endpointType(requireText(request, "EndpointType"));
        String engineName = engineName(requireText(request, "EngineName"));
        requireEngineSupportsType(engineName, endpointType);
        String resourceIdentifier = DmsRequests.resourceIdentifier(request);
        String key = storageKey(region, identifier);
        if (endpoints.get(key).isPresent()) {
            throw alreadyExists("Endpoint " + identifier + " already exists.");
        }
        String arn = regionResolver.buildArn("dms", region, ENDPOINT_ARN_RESOURCE_TYPE + ":"
                + (resourceIdentifier != null ? resourceIdentifier : randomResourceId()));
        if (endpointByArn(arn, region).isPresent()) {
            throw alreadyExists("An endpoint with ARN " + arn + " already exists.");
        }

        DmsEndpoint endpoint = new DmsEndpoint();
        endpoint.setEndpointIdentifier(identifier);
        endpoint.setEndpointArn(arn);
        endpoint.setEndpointType(endpointType);
        endpoint.setEngineName(engineName);
        endpoint.setKmsKeyId(text(request, "KmsKeyId"));
        endpoint.setSslMode("none");
        applyEndpointFields(endpoint, request, true);
        endpoint.setStatus("active");
        endpoint.setTags(readTags(request.get("Tags")));
        endpoints.put(key, endpoint);
        return endpoint;
    }

    public synchronized PaginatedResult<DmsEndpoint> describeEndpoints(JsonNode request, String region) {
        Integer maxRecords = maxRecords(request);
        String marker = text(request, "Marker");
        List<Filter> filters = DmsRequests.filters(request, ENDPOINT_FILTERS);
        List<DmsEndpoint> matching = endpoints.scan(regionKeys(region)).stream()
                .filter(endpoint -> filters.stream().allMatch(filter -> endpointMatches(endpoint, filter)))
                .toList();
        if (!filters.isEmpty() && matching.isEmpty()) {
            throw resourceNotFound("No endpoints matched the supplied filters.");
        }
        return paginate(matching, DmsEndpoint::getEndpointIdentifier, maxRecords, marker);
    }

    /**
     * Applies only the members present in the request, so an omitted password or port keeps its
     * stored value. With {@code ExactSettings} true the supplied settings structures replace every
     * stored one; otherwise each supplied structure is merged into the stored one by setting name.
     */
    public synchronized DmsEndpoint modifyEndpoint(JsonNode request, String region) {
        String arn = requireText(request, "EndpointArn");
        DmsEndpoint endpoint = endpointByArn(arn, region).orElseThrow(() -> endpointNotFound(arn));
        String oldKey = storageKey(region, endpoint.getEndpointIdentifier());

        String requestedIdentifier = text(request, "EndpointIdentifier");
        String identifier = requestedIdentifier == null ? endpoint.getEndpointIdentifier()
                : DmsRequests.dmsIdentifier(requestedIdentifier, "EndpointIdentifier", ENDPOINT_IDENTIFIER_MAX_LENGTH);
        String newKey = storageKey(region, identifier);
        if (!newKey.equals(oldKey) && endpoints.get(newKey).isPresent()) {
            throw alreadyExists("Endpoint " + identifier + " already exists.");
        }
        String requestedType = text(request, "EndpointType");
        String endpointType = requestedType == null ? endpoint.getEndpointType() : endpointType(requestedType);
        String requestedEngine = text(request, "EngineName");
        String engineName = requestedEngine == null ? endpoint.getEngineName() : engineName(requestedEngine);
        requireEngineSupportsType(engineName, endpointType);
        boolean exactSettings = Boolean.TRUE.equals(bool(request, "ExactSettings"));

        DmsEndpoint modified = new DmsEndpoint(endpoint);
        modified.setEndpointIdentifier(identifier);
        modified.setEndpointType(endpointType);
        modified.setEngineName(engineName);
        applyEndpointFields(modified, request, exactSettings);
        if (!newKey.equals(oldKey)) {
            endpoints.delete(oldKey);
        }
        endpoints.put(newKey, modified);
        renameEndpointInConnections(region, modified);
        return modified;
    }

    /**
     * Removes the endpoint along with its connection tests and schema refresh. The response
     * carries status {@code deleting}, the status DMS reports for an endpoint being deleted.
     */
    public synchronized DmsEndpoint deleteEndpoint(JsonNode request, String region) {
        String arn = requireText(request, "EndpointArn");
        DmsEndpoint endpoint = endpointByArn(arn, region).orElseThrow(() -> endpointNotFound(arn));
        endpoints.delete(storageKey(region, endpoint.getEndpointIdentifier()));
        deleteConnectionsWhere(region, connection -> arn.equals(connection.getEndpointArn()));
        schemaRefreshes.delete(storageKey(region, arn));
        DmsEndpoint deleted = new DmsEndpoint(endpoint);
        deleted.setStatus(STATUS_DELETING);
        return deleted;
    }

    public PaginatedResult<DmsCatalog.EndpointSetting> describeEndpointSettings(JsonNode request) {
        String engineName = engineName(requireText(request, "EngineName"));
        Integer maxRecords = maxRecords(request);
        String marker = text(request, "Marker");
        List<DmsCatalog.EndpointSetting> settings = DmsCatalog.endpointSettings(engineName);
        if (settings == null) {
            throw invalidParameter("Floci has no endpoint settings catalogue for engine " + engineName
                    + "; it documents settings for MySQL-compatible and PostgreSQL-compatible engines only.");
        }
        return paginate(settings, DmsCatalog.EndpointSetting::name, maxRecords, marker);
    }

    // ── Replication instances ───────────────────────────────────────────────

    public synchronized ReplicationInstance createReplicationInstance(JsonNode request, String region) {
        String identifier = DmsRequests.dmsIdentifier(text(request, "ReplicationInstanceIdentifier"),
                "ReplicationInstanceIdentifier", INSTANCE_IDENTIFIER_MAX_LENGTH);
        String instanceClass = instanceClass(requireText(request, "ReplicationInstanceClass"));
        Integer requestedStorage = integer(request, "AllocatedStorage");
        int allocatedStorage = requestedStorage != null
                ? allocatedStorage(requestedStorage)
                : DmsCatalog.INSTANCE_CLASSES.get(instanceClass);
        String requestedVersion = text(request, "EngineVersion");
        String engineVersion = requestedVersion != null
                ? engineVersion(requestedVersion)
                : DmsCatalog.ENGINE_VERSIONS.getLast();
        boolean multiAZ = Boolean.TRUE.equals(bool(request, "MultiAZ"));
        String requestedZone = text(request, "AvailabilityZone");
        if (multiAZ && requestedZone != null) {
            throw invalidCombination("Requesting a specific Availability Zone is not valid for a Multi-AZ"
                    + " replication instance.");
        }
        String networkType = networkType(text(request, "NetworkType"));
        String maintenanceWindow = text(request, "PreferredMaintenanceWindow");
        maintenanceWindow = maintenanceWindow != null ? maintenanceWindow(maintenanceWindow) : DEFAULT_MAINTENANCE_WINDOW;
        String resourceIdentifier = DmsRequests.resourceIdentifier(request);
        List<String> securityGroupIds = optionalStringList(request, "VpcSecurityGroupIds");
        Map<String, String> tags = readTags(request.get("Tags"));

        String key = storageKey(region, identifier);
        if (instances.get(key).isPresent()) {
            throw alreadyExists("Replication instance " + identifier + " already exists.");
        }
        ReplicationSubnetGroup group = placementGroup(text(request, "ReplicationSubnetGroupIdentifier"), region);
        requireNetworkTypeSupported(group, networkType);
        List<String> zones = distinctZones(group);
        String zone;
        if (requestedZone != null) {
            if (!zones.contains(requestedZone)) {
                throw invalidParameter("Availability Zone " + requestedZone + " is not covered by replication"
                        + " subnet group " + group.getReplicationSubnetGroupIdentifier() + ".");
            }
            zone = requestedZone;
        } else {
            zone = zones.getFirst();
        }
        String arn = regionResolver.buildArn("dms", region, INSTANCE_ARN_RESOURCE_TYPE + ":"
                + (resourceIdentifier != null ? resourceIdentifier : randomResourceId()));
        if (instanceByArn(arn, region).isPresent()) {
            throw alreadyExists("A replication instance with ARN " + arn + " already exists.");
        }

        ReplicationInstance instance = new ReplicationInstance();
        instance.setReplicationInstanceIdentifier(identifier);
        instance.setReplicationInstanceArn(arn);
        instance.setReplicationInstanceClass(instanceClass);
        instance.setAllocatedStorage(allocatedStorage);
        instance.setEngineVersion(engineVersion);
        instance.setMultiAZ(multiAZ);
        instance.setAvailabilityZone(zone);
        instance.setSecondaryAvailabilityZone(multiAZ ? secondaryZone(zones, zone) : null);
        instance.setSubnetGroup(group);
        instance.setVpcSecurityGroupIds(securityGroups(region, securityGroupIds, group.getVpcId()));
        instance.setPreferredMaintenanceWindow(maintenanceWindow);
        Boolean autoMinor = bool(request, "AutoMinorVersionUpgrade");
        instance.setAutoMinorVersionUpgrade(autoMinor == null || autoMinor);
        Boolean publiclyAccessible = bool(request, "PubliclyAccessible");
        instance.setPubliclyAccessible(publiclyAccessible == null || publiclyAccessible);
        instance.setKmsKeyId(text(request, "KmsKeyId"));
        instance.setNetworkType(networkType);
        instance.setDnsNameServers(text(request, "DnsNameServers"));
        instance.setInstanceCreateTimeMillis(Instant.now().toEpochMilli());
        instance.setStatus(STATUS_CREATING);
        instance.setTags(tags);
        instances.put(key, instance);
        recordInstanceEvent(region, instance, DmsInstanceEvent.CREATION_STARTED);
        return instance;
    }

    public synchronized PaginatedResult<ReplicationInstance> describeReplicationInstances(JsonNode request,
                                                                                         String region) {
        Integer maxRecords = maxRecords(request);
        String marker = text(request, "Marker");
        List<Filter> filters = DmsRequests.filters(request, INSTANCE_FILTERS);
        List<ReplicationInstance> matching = instances.scan(regionKeys(region)).stream()
                .map(instance -> settle(instance, region))
                .filter(instance -> filters.stream().allMatch(filter -> instanceMatches(instance, filter)))
                .toList();
        if (!filters.isEmpty() && matching.isEmpty()) {
            throw resourceNotFound("No replication instances matched the supplied filters.");
        }
        return paginate(matching, ReplicationInstance::getReplicationInstanceIdentifier, maxRecords, marker);
    }

    /**
     * Class, storage, Multi-AZ, engine version, and network type changes wait for the maintenance
     * window unless {@code ApplyImmediately} is true, in which case the instance turns
     * {@code modifying} and applies every pending change when it settles. Floci does not run
     * maintenance windows, so a deferred change stays in {@code PendingModifiedValues} until a later
     * call applies it immediately. Security groups, the maintenance window, minor-version upgrades,
     * and the identifier change at once.
     */
    public synchronized ReplicationInstance modifyReplicationInstance(JsonNode request, String region) {
        String arn = requireText(request, "ReplicationInstanceArn");
        ReplicationInstance instance = settledInstanceByArn(arn, region);
        requireAvailable(instance);
        boolean applyImmediately = Boolean.TRUE.equals(bool(request, "ApplyImmediately"));
        String oldKey = storageKey(region, instance.getReplicationInstanceIdentifier());

        String requestedIdentifier = text(request, "ReplicationInstanceIdentifier");
        String identifier = requestedIdentifier == null ? instance.getReplicationInstanceIdentifier()
                : DmsRequests.dmsIdentifier(requestedIdentifier, "ReplicationInstanceIdentifier",
                        INSTANCE_IDENTIFIER_MAX_LENGTH);
        String newKey = storageKey(region, identifier);
        if (!newKey.equals(oldKey) && instances.get(newKey).isPresent()) {
            throw alreadyExists("Replication instance " + identifier + " already exists.");
        }

        String requestedClass = text(request, "ReplicationInstanceClass");
        String instanceClass = requestedClass != null ? instanceClass(requestedClass) : null;
        Integer requestedStorage = integer(request, "AllocatedStorage");
        if (requestedStorage != null) {
            allocatedStorage(requestedStorage);
            if (requestedStorage < instance.getAllocatedStorage()) {
                throw invalidParameter("AllocatedStorage cannot be decreased from "
                        + instance.getAllocatedStorage() + " GB.");
            }
        }
        Boolean multiAZ = bool(request, "MultiAZ");
        String requestedVersion = text(request, "EngineVersion");
        String engineVersion = requestedVersion != null ? engineVersion(requestedVersion) : null;
        if (engineVersion != null && DmsCatalog.ENGINE_VERSIONS.indexOf(engineVersion)
                < DmsCatalog.ENGINE_VERSIONS.indexOf(instance.getEngineVersion())) {
            throw invalidParameter("EngineVersion cannot be downgraded from " + instance.getEngineVersion() + ".");
        }
        String requestedNetworkType = text(request, "NetworkType");
        String networkType = requestedNetworkType != null ? networkType(requestedNetworkType) : null;
        if (networkType != null) {
            requireNetworkTypeSupported(instance.getSubnetGroup(), networkType);
        }
        List<String> securityGroupIds = optionalStringList(request, "VpcSecurityGroupIds");
        String maintenanceWindow = text(request, "PreferredMaintenanceWindow");
        if (maintenanceWindow != null) {
            maintenanceWindow = maintenanceWindow(maintenanceWindow);
        }
        Boolean autoMinor = bool(request, "AutoMinorVersionUpgrade");
        if (Boolean.TRUE.equals(multiAZ) && !instance.isMultiAZ()
                && distinctZones(instance.getSubnetGroup()).size() < 2) {
            throw invalidCombination("A Multi-AZ replication instance needs a subnet group covering at least"
                    + " two Availability Zones.");
        }
        List<String> resolvedSecurityGroups = securityGroupIds == null ? null
                : securityGroups(region, securityGroupIds, instance.getSubnetGroup().getVpcId());

        // Every validation has passed; only now mutate the stored record.
        if (resolvedSecurityGroups != null) {
            instance.setVpcSecurityGroupIds(resolvedSecurityGroups);
        }
        if (maintenanceWindow != null) {
            instance.setPreferredMaintenanceWindow(maintenanceWindow);
        }
        if (autoMinor != null) {
            instance.setAutoMinorVersionUpgrade(autoMinor);
        }
        instance.setReplicationInstanceIdentifier(identifier);

        List<DmsInstanceEvent> started = new ArrayList<>();
        if (instanceClass != null && !instanceClass.equals(instance.getReplicationInstanceClass())) {
            instance.setPendingReplicationInstanceClass(instanceClass);
            started.add(DmsInstanceEvent.CLASS_CHANGE_STARTED);
        }
        if (requestedStorage != null && requestedStorage != instance.getAllocatedStorage()) {
            instance.setPendingAllocatedStorage(requestedStorage);
            started.add(DmsInstanceEvent.STORAGE_SCALE_STARTED);
        }
        if (multiAZ != null && multiAZ != instance.isMultiAZ()) {
            instance.setPendingMultiAZ(multiAZ);
            started.add(multiAZ ? DmsInstanceEvent.MULTI_AZ_STARTED : DmsInstanceEvent.SINGLE_AZ_STARTED);
        }
        if (engineVersion != null && !engineVersion.equals(instance.getEngineVersion())) {
            instance.setPendingEngineVersion(engineVersion);
        }
        if (networkType != null && !networkType.equals(instance.getNetworkType())) {
            instance.setPendingNetworkType(networkType);
        }
        boolean transitioning = applyImmediately && instance.hasPendingModifications();
        if (transitioning) {
            instance.setPendingApplyImmediately(true);
            instance.setStatus(STATUS_MODIFYING);
        }
        if (!newKey.equals(oldKey)) {
            instances.delete(oldKey);
        }
        instances.put(newKey, instance);
        renameInstanceInConnections(region, instance);
        if (transitioning) {
            started.forEach(event -> recordInstanceEvent(region, instance, event));
        }
        return instance;
    }

    /**
     * Reboots an available instance. With {@code ForceFailover} a Multi-AZ instance swaps its
     * primary and secondary Availability Zones; asking for a failover on a Single-AZ instance is
     * rejected.
     */
    public synchronized ReplicationInstance rebootReplicationInstance(JsonNode request, String region) {
        String arn = requireText(request, "ReplicationInstanceArn");
        boolean forceFailover = Boolean.TRUE.equals(bool(request, "ForceFailover"));
        boolean forcePlannedFailover = Boolean.TRUE.equals(bool(request, "ForcePlannedFailover"));
        ReplicationInstance instance = settledInstanceByArn(arn, region);
        requireAvailable(instance);
        if (forceFailover && forcePlannedFailover) {
            throw invalidCombination("ForceFailover and ForcePlannedFailover cannot both be true.");
        }
        if ((forceFailover || forcePlannedFailover) && !instance.isMultiAZ()) {
            throw invalidCombination("A failover reboot requires a Multi-AZ replication instance.");
        }
        if ((forceFailover || forcePlannedFailover) && instance.getSecondaryAvailabilityZone() != null) {
            String primary = instance.getAvailabilityZone();
            instance.setAvailabilityZone(instance.getSecondaryAvailabilityZone());
            instance.setSecondaryAvailabilityZone(primary);
        }
        instance.setStatus(STATUS_REBOOTING);
        instances.put(storageKey(region, instance.getReplicationInstanceIdentifier()), instance);
        return instance;
    }

    /**
     * Deletes the instance and its connection tests. The response carries status
     * {@code deleting}; the record is gone as soon as the call returns because there is no compute
     * to tear down.
     */
    public synchronized ReplicationInstance deleteReplicationInstance(JsonNode request, String region) {
        String arn = requireText(request, "ReplicationInstanceArn");
        ReplicationInstance instance = settledInstanceByArn(arn, region);
        instances.delete(storageKey(region, instance.getReplicationInstanceIdentifier()));
        deleteConnectionsWhere(region, connection -> arn.equals(connection.getReplicationInstanceArn()));
        instance.setStatus(STATUS_DELETING);
        recordInstanceEvent(region, instance, DmsInstanceEvent.DELETION_STARTED);
        recordInstanceEvent(region, instance, DmsInstanceEvent.DELETION_FINISHED);
        return instance;
    }

    /** Floci runs no replication tasks, so an existing instance never holds task logs. */
    public synchronized ReplicationInstance describeReplicationInstanceTaskLogs(JsonNode request, String region) {
        String arn = requireText(request, "ReplicationInstanceArn");
        maxRecords(request);
        text(request, "Marker");
        return settledInstanceByArn(arn, region);
    }

    public List<OrderableInstance> orderableReplicationInstances(String region) {
        List<String> zones = ec2Service.describeAvailabilityZones(region).stream()
                .map(zone -> zone.get("zoneName"))
                .filter(Objects::nonNull)
                .toList();
        List<OrderableInstance> orderable = new ArrayList<>();
        for (String version : DmsCatalog.ENGINE_VERSIONS) {
            DmsCatalog.INSTANCE_CLASSES.forEach((instanceClass, included) ->
                    orderable.add(new OrderableInstance(version, instanceClass, included, zones)));
        }
        return orderable;
    }

    public PaginatedResult<OrderableInstance> describeOrderableReplicationInstances(JsonNode request, String region) {
        Integer maxRecords = maxRecords(request);
        String marker = text(request, "Marker");
        return paginate(orderableReplicationInstances(region),
                entry -> entry.engineVersion() + "|" + entry.instanceClass(), maxRecords, marker);
    }

    // ── Connections and schemas ─────────────────────────────────────────────

    /**
     * Starts a connection test from the instance to the endpoint. The response reports
     * {@code testing}; the login attempt runs in the background and DescribeConnections reports
     * {@code successful} or {@code failed} with the driver's error once it finishes.
     */
    public synchronized DmsConnection testConnection(JsonNode request, String region) {
        String instanceArn = requireText(request, "ReplicationInstanceArn");
        String endpointArn = requireText(request, "EndpointArn");
        ReplicationInstance instance = settledInstanceByArn(instanceArn, region);
        DmsEndpoint endpoint = endpointByArn(endpointArn, region).orElseThrow(() -> endpointNotFound(endpointArn));
        requireAvailable(instance);

        String key = storageKey(region, instanceArn + "|" + endpointArn);
        DmsConnection connection = new DmsConnection();
        connection.setReplicationInstanceArn(instanceArn);
        connection.setReplicationInstanceIdentifier(instance.getReplicationInstanceIdentifier());
        connection.setEndpointArn(endpointArn);
        connection.setEndpointIdentifier(endpoint.getEndpointIdentifier());
        connection.setStatus("testing");
        connection.setAttemptId(UUID.randomUUID().toString());
        connections.put(key, connection);

        String accountId = connections.accountId();
        String attemptId = connection.getAttemptId();
        DmsEndpoint snapshot = new DmsEndpoint(endpoint);
        executor.execute(() -> {
            DmsConnectivityProbe.Result result = probe.testConnection(snapshot);
            completeConnection(accountId, key, attemptId, result);
        });
        return connection;
    }

    public synchronized PaginatedResult<DmsConnection> describeConnections(JsonNode request, String region) {
        Integer maxRecords = maxRecords(request);
        String marker = text(request, "Marker");
        List<Filter> filters = DmsRequests.filters(request, CONNECTION_FILTERS);
        List<DmsConnection> matching = connections.scan(regionKeys(region)).stream()
                .filter(connection -> filters.stream().allMatch(filter -> filter.values().contains(
                        "endpoint-arn".equals(filter.name())
                                ? connection.getEndpointArn()
                                : connection.getReplicationInstanceArn())))
                .toList();
        if (!filters.isEmpty() && matching.isEmpty()) {
            throw resourceNotFound("No connections matched the supplied filters.");
        }
        return paginate(matching, connection -> connection.getReplicationInstanceArn() + "|"
                + connection.getEndpointArn(), maxRecords, marker);
    }

    /**
     * Starts a schema refresh for the endpoint through the instance. As with TestConnection the
     * response reports {@code refreshing} and the schema list is read in the background.
     */
    public synchronized SchemaRefresh refreshSchemas(JsonNode request, String region) {
        String endpointArn = requireText(request, "EndpointArn");
        String instanceArn = requireText(request, "ReplicationInstanceArn");
        ReplicationInstance instance = settledInstanceByArn(instanceArn, region);
        DmsEndpoint endpoint = endpointByArn(endpointArn, region).orElseThrow(() -> endpointNotFound(endpointArn));
        requireAvailable(instance);
        String key = storageKey(region, endpointArn);
        Optional<SchemaRefresh> previous = schemaRefreshes.get(key);
        if (previous.isPresent() && "refreshing".equals(previous.get().getStatus())) {
            throw invalidState("A schema refresh is already in progress for endpoint "
                    + endpoint.getEndpointIdentifier() + ".");
        }

        SchemaRefresh refresh = new SchemaRefresh();
        refresh.setEndpointArn(endpointArn);
        refresh.setReplicationInstanceArn(instanceArn);
        refresh.setStatus("refreshing");
        previous.ifPresent(prior -> refresh.setLastRefreshDateMillis(prior.getLastRefreshDateMillis()));
        refresh.setAttemptId(UUID.randomUUID().toString());
        schemaRefreshes.put(key, refresh);

        String accountId = schemaRefreshes.accountId();
        String attemptId = refresh.getAttemptId();
        DmsEndpoint snapshot = new DmsEndpoint(endpoint);
        executor.execute(() -> {
            DmsConnectivityProbe.Result result = probe.listSchemas(snapshot);
            completeSchemaRefresh(accountId, key, attemptId, result);
        });
        return refresh;
    }

    public synchronized SchemaRefresh describeRefreshSchemasStatus(JsonNode request, String region) {
        String endpointArn = requireText(request, "EndpointArn");
        endpointByArn(endpointArn, region).orElseThrow(() -> endpointNotFound(endpointArn));
        return schemaRefreshes.get(storageKey(region, endpointArn))
                .orElseThrow(() -> resourceNotFound("No schema refresh has been run for endpoint " + endpointArn + "."));
    }

    /** Schemas are only known after a successful RefreshSchemas for the endpoint. */
    public synchronized PaginatedResult<String> describeSchemas(JsonNode request, String region) {
        String endpointArn = requireText(request, "EndpointArn");
        Integer maxRecords = maxRecords(request);
        String marker = text(request, "Marker");
        DmsEndpoint endpoint = endpointByArn(endpointArn, region).orElseThrow(() -> endpointNotFound(endpointArn));
        SchemaRefresh refresh = schemaRefreshes.get(storageKey(region, endpointArn)).orElseThrow(() ->
                invalidState("Schemas have not been refreshed for endpoint " + endpoint.getEndpointIdentifier()
                        + ". Run RefreshSchemas first."));
        if (!"successful".equals(refresh.getStatus())) {
            throw invalidState("The latest schema refresh for endpoint " + endpoint.getEndpointIdentifier()
                    + " is " + refresh.getStatus()
                    + (refresh.getLastFailureMessage() != null ? ": " + refresh.getLastFailureMessage() : "."));
        }
        return paginate(refresh.getSchemas(), schema -> schema, maxRecords, marker);
    }

    // ── Replication tasks and serverless replications ───────────────────────

    /** Floci cannot create replication tasks, so every lookup finds none. */
    public PaginatedResult<Void> describeReplicationTasks(JsonNode request) {
        maxRecords(request);
        text(request, "Marker");
        bool(request, "WithoutSettings");
        List<Filter> filters = DmsRequests.filters(request, TASK_FILTERS);
        if (!filters.isEmpty()) {
            throw resourceNotFound("No replication tasks matched the supplied filters.");
        }
        return new PaginatedResult<>(List.of(), null);
    }

    public void startReplicationTask(JsonNode request) {
        String arn = requireText(request, "ReplicationTaskArn");
        String type = requireText(request, "StartReplicationTaskType");
        if (!START_TASK_TYPES.contains(type)) {
            throw invalidParameter("Invalid StartReplicationTaskType: " + type + ".");
        }
        throw taskNotFound(arn);
    }

    public void stopReplicationTask(JsonNode request) {
        throw taskNotFound(requireText(request, "ReplicationTaskArn"));
    }

    public void describeTableStatistics(JsonNode request) {
        String arn = requireText(request, "ReplicationTaskArn");
        integer(request, "MaxRecords");
        text(request, "Marker");
        DmsRequests.filters(request, Set.of("schema-name", "table-name", "table-state"));
        throw taskNotFound(arn);
    }

    public void reloadTables(JsonNode request) {
        String arn = requireText(request, "ReplicationTaskArn");
        JsonNode tables = request == null ? null : request.get("TablesToReload");
        if (tables == null || tables.isNull()) {
            throw invalidParameter("The parameter TablesToReload must be provided.");
        }
        if (!tables.isArray()) {
            throw serialization("TablesToReload must be a list of tables.");
        }
        throw taskNotFound(arn);
    }

    /** Floci cannot create replication configs, so every lookup finds none. */
    public PaginatedResult<Void> describeReplications(JsonNode request) {
        maxRecords(request);
        text(request, "Marker");
        List<Filter> filters = DmsRequests.filters(request, null);
        if (!filters.isEmpty()) {
            throw resourceNotFound("No replications matched the supplied filters.");
        }
        return new PaginatedResult<>(List.of(), null);
    }

    public void startReplication(JsonNode request) {
        String arn = requireText(request, "ReplicationConfigArn");
        String type = requireText(request, "StartReplicationType");
        if (!START_REPLICATION_TYPES.contains(type)) {
            throw invalidParameter("Invalid StartReplicationType: " + type + ".");
        }
        throw replicationConfigNotFound(arn);
    }

    public void stopReplication(JsonNode request) {
        throw replicationConfigNotFound(requireText(request, "ReplicationConfigArn"));
    }

    // ── Events ──────────────────────────────────────────────────────────────

    public synchronized PaginatedResult<DmsEvent> describeEvents(JsonNode request, String region) {
        Integer maxRecords = maxRecords(request);
        String marker = text(request, "Marker");
        String sourceIdentifier = text(request, "SourceIdentifier");
        String sourceType = text(request, "SourceType");
        if (sourceType != null && !EVENT_SOURCE_TYPES.contains(sourceType)) {
            throw invalidParameter("Invalid SourceType: " + sourceType + ".");
        }
        Instant startTime = DmsRequests.timestamp(request, "StartTime");
        Instant endTime = DmsRequests.timestamp(request, "EndTime");
        Integer duration = integer(request, "Duration");
        if (duration != null && duration < 0) {
            throw invalidParameter("Duration must not be negative.");
        }
        List<String> categories = optionalStringList(request, "EventCategories");
        List<Filter> filters = DmsRequests.filters(request, EVENT_FILTERS);

        Instant end = endTime != null ? endTime : Instant.now();
        Instant start = startTime != null ? startTime
                : end.minus(Duration.ofMinutes(duration != null ? duration : DEFAULT_EVENT_DURATION_MINUTES));
        if (start.isAfter(end)) {
            throw invalidParameter("StartTime must not be after EndTime.");
        }
        Set<String> wantedCategories = categories == null ? null : categories.stream()
                .map(category -> category.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        List<DmsEvent> matching = events.scan(regionKeys(region)).stream()
                .filter(event -> sourceIdentifier == null || sourceIdentifier.equals(event.sourceIdentifier()))
                .filter(event -> sourceType == null || sourceType.equals(event.sourceType()))
                .filter(event -> !Instant.ofEpochMilli(event.dateMillis()).isBefore(start)
                        && !Instant.ofEpochMilli(event.dateMillis()).isAfter(end))
                .filter(event -> wantedCategories == null
                        || event.eventCategories().stream().anyMatch(wantedCategories::contains))
                .filter(event -> filters.stream().allMatch(filter -> "replication-instance".equals(event.sourceType())
                        && filter.values().stream().anyMatch(value ->
                        value.equalsIgnoreCase(event.sourceIdentifier()))))
                .toList();
        return paginate(matching, DmsEvent::id, maxRecords, marker);
    }

    // ── Tags ────────────────────────────────────────────────────────────────

    public synchronized List<ResourceTag> listTagsForResource(JsonNode request, String region) {
        JsonNode arnListNode = request == null ? null : request.get("ResourceArnList");
        List<String> arnList = arnListNode == null || arnListNode.isNull()
                ? List.of()
                : stringList(arnListNode, "ResourceArnList");
        if (!arnList.isEmpty()) {
            List<ResourceTag> tags = new ArrayList<>();
            for (String arn : arnList) {
                tagsOf(arn, region).forEach((key, value) -> tags.add(new ResourceTag(arn, key, value)));
            }
            return tags;
        }
        String resourceArn = requireResourceArn(request);
        return tagsOf(resourceArn, region).entrySet().stream()
                .map(entry -> new ResourceTag(null, entry.getKey(), entry.getValue()))
                .toList();
    }

    public synchronized void addTagsToResource(JsonNode request, String region) {
        String resourceArn = requireResourceArn(request);
        JsonNode tagsNode = request == null ? null : request.get("Tags");
        if (tagsNode == null || tagsNode.isNull()) {
            throw invalidParameter("The parameter Tags must be provided.");
        }
        Map<String, String> added = readTags(tagsNode);
        updateTags(resourceArn, region, tags -> tags.putAll(added));
    }

    public synchronized void removeTagsFromResource(JsonNode request, String region) {
        String resourceArn = requireResourceArn(request);
        JsonNode keysNode = request == null ? null : request.get("TagKeys");
        if (keysNode == null || keysNode.isNull()) {
            throw invalidParameter("The parameter TagKeys must be provided.");
        }
        List<String> keys = stringList(keysNode, "TagKeys");
        updateTags(resourceArn, region, tags -> keys.forEach(tags::remove));
    }

    // ── Subnet group helpers ────────────────────────────────────────────────

    private ReplicationSubnetGroup buildSubnetGroup(String identifier, String description,
                                                    List<String> subnetIds, String region) {
        List<String> requested = subnetIds.stream().distinct().toList();
        Map<String, Subnet> resolved = resolveSubnets(region, requested);
        List<String> missing = requested.stream().filter(id -> !resolved.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new AwsException("InvalidSubnet",
                    "The subnet provided is invalid: " + missing + ".", 400);
        }

        // Response order follows the request rather than storage iteration order, so a describe
        // that follows a create returns the same subnet ordering every time.
        Map<String, String> availabilityZones = new LinkedHashMap<>();
        for (String subnetId : requested) {
            String availabilityZone = resolved.get(subnetId).getAvailabilityZone();
            if (availabilityZone == null) {
                throw new AwsException("InvalidSubnet",
                        "Subnet " + subnetId + " has no Availability Zone.", 400);
            }
            availabilityZones.put(subnetId, availabilityZone);
        }

        String vpcId = resolved.get(requested.getFirst()).getVpcId();
        boolean sameVpc = resolved.values().stream()
                .map(Subnet::getVpcId)
                .filter(Objects::nonNull)
                .allMatch(vpcId::equals);
        if (!sameVpc) {
            throw new AwsException("InvalidSubnet",
                    "The subnets provided for replication subnet group " + identifier
                            + " belong to more than one VPC.", 400);
        }

        if (Set.copyOf(availabilityZones.values()).size() < MINIMUM_AVAILABILITY_ZONES) {
            throw new AwsException("ReplicationSubnetGroupDoesNotCoverEnoughAZs",
                    "The replication subnet group does not cover enough Availability Zones (AZs)."
                            + " Edit the replication subnet group and add more AZs.", 400);
        }

        ReplicationSubnetGroup group = new ReplicationSubnetGroup();
        group.setReplicationSubnetGroupIdentifier(identifier);
        group.setReplicationSubnetGroupDescription(description);
        group.setVpcId(vpcId);
        group.setSubnetGroupStatus("Complete");
        group.setSubnetIds(requested);
        group.setSubnetAvailabilityZones(availabilityZones);
        group.setSupportedNetworkTypes(List.of("IPV4"));
        return group;
    }

    /**
     * Resolves each subnet on its own, so one unknown ID is reported as the DMS
     * {@code InvalidSubnet} fault rather than surfacing EC2's {@code InvalidSubnetID.NotFound}.
     */
    private Map<String, Subnet> resolveSubnets(String region, List<String> subnetIds) {
        Map<String, Subnet> resolved = new LinkedHashMap<>();
        for (String subnetId : subnetIds) {
            try {
                ec2Service.describeSubnets(region, List.of(subnetId), Map.of()).stream()
                        .filter(subnet -> subnetId.equals(subnet.getSubnetId()))
                        .findFirst()
                        .ifPresent(subnet -> resolved.put(subnetId, subnet));
            } catch (AwsException e) {
                if (!"InvalidSubnetID.NotFound".equals(e.getErrorCode())) {
                    throw e;
                }
            }
        }
        return resolved;
    }

    /**
     * The group an instance is placed in: the named group, or when none is named a group built
     * from the default VPC's subnets, which is where DMS places such an instance. The implicit
     * group is embedded in the instance and not stored as a subnet group of its own.
     */
    private ReplicationSubnetGroup placementGroup(String requestedIdentifier, String region) {
        if (requestedIdentifier != null) {
            String identifier = requestedIdentifier.toLowerCase(Locale.ROOT);
            return subnetGroups.get(storageKey(region, identifier))
                    .orElseThrow(() -> subnetGroupNotFound(identifier));
        }
        Vpc defaultVpc = ec2Service.describeVpcs(region, List.of(), Map.of()).stream()
                .filter(Vpc::isDefault)
                .findFirst()
                .orElseThrow(() -> invalidCombination("No ReplicationSubnetGroupIdentifier was given and the"
                        + " account has no default VPC in " + region + "."));
        List<String> subnetIds = ec2Service.describeSubnets(region, List.of(), Map.of()).stream()
                .filter(subnet -> defaultVpc.getVpcId().equals(subnet.getVpcId()))
                .map(Subnet::getSubnetId)
                .sorted()
                .toList();
        if (subnetIds.isEmpty()) {
            throw invalidCombination("The default VPC " + defaultVpc.getVpcId() + " has no subnets.");
        }
        return buildSubnetGroup("default-" + defaultVpc.getVpcId(),
                "Default replication subnet group for " + defaultVpc.getVpcId(), subnetIds, region);
    }

    private List<ReplicationInstance> instancesInGroup(String region, String groupIdentifier) {
        return instances.scan(regionKeys(region)).stream()
                .filter(instance -> instance.getSubnetGroup() != null && groupIdentifier.equals(
                        instance.getSubnetGroup().getReplicationSubnetGroupIdentifier()))
                .toList();
    }

    private static List<String> instanceZones(ReplicationInstance instance) {
        List<String> zones = new ArrayList<>();
        if (instance.getAvailabilityZone() != null) {
            zones.add(instance.getAvailabilityZone());
        }
        if (instance.getSecondaryAvailabilityZone() != null) {
            zones.add(instance.getSecondaryAvailabilityZone());
        }
        return zones;
    }

    private static List<String> distinctZones(ReplicationSubnetGroup group) {
        return List.copyOf(new LinkedHashSet<>(group.getSubnetIds().stream()
                .map(subnetId -> group.getSubnetAvailabilityZones().get(subnetId))
                .filter(Objects::nonNull)
                .toList()));
    }

    private static String secondaryZone(List<String> zones, String primary) {
        return zones.stream().filter(zone -> !zone.equals(primary)).findFirst()
                .orElseThrow(() -> invalidCombination("A Multi-AZ replication instance needs a subnet group"
                        + " covering at least two Availability Zones."));
    }

    /**
     * Security groups must exist and belong to the instance's VPC. With none given, the VPC's
     * default security group is used when it exists.
     */
    private List<String> securityGroups(String region, List<String> requested, String vpcId) {
        if (requested == null || requested.isEmpty()) {
            try {
                return ec2Service.getSecurityGroupsForVpc(region, vpcId, Map.of()).stream()
                        .filter(group -> "default".equals(group.getGroupName()))
                        .map(SecurityGroup::getGroupId)
                        .toList();
            } catch (AwsException e) {
                return List.of();
            }
        }
        List<String> resolved = new ArrayList<>();
        for (String groupId : requested.stream().distinct().toList()) {
            SecurityGroup group;
            try {
                group = ec2Service.describeSecurityGroups(region, List.of(groupId), List.of(), Map.of()).stream()
                        .filter(candidate -> groupId.equals(candidate.getGroupId()))
                        .findFirst()
                        .orElse(null);
            } catch (AwsException e) {
                if (!"InvalidGroup.NotFound".equals(e.getErrorCode())) {
                    throw e;
                }
                group = null;
            }
            if (group == null) {
                throw invalidParameter("The security group '" + groupId + "' does not exist.");
            }
            if (vpcId != null && !vpcId.equals(group.getVpcId())) {
                throw invalidParameter("The security group '" + groupId + "' is not in VPC " + vpcId + ".");
            }
            resolved.add(groupId);
        }
        return resolved;
    }

    /**
     * AWS stores the identifier as a lowercase string, so every lookup normalises the same way:
     * a group created as "MyGroup" is described and deleted as "mygroup".
     */
    private static String requireSubnetGroupIdentifier(JsonNode request) {
        String value = text(request, "ReplicationSubnetGroupIdentifier");
        if (value == null || value.isBlank()) {
            throw invalidParameter("The parameter ReplicationSubnetGroupIdentifier must be provided"
                    + " and must not be blank.");
        }
        String identifier = value.toLowerCase(Locale.ROOT);
        if (identifier.length() > 255 || !SUBNET_GROUP_IDENTIFIER.matcher(identifier).matches()) {
            throw invalidParameter("ReplicationSubnetGroupIdentifier must contain no more than 255"
                    + " alphanumeric characters, periods, underscores, or hyphens.");
        }
        if ("default".equals(identifier)) {
            throw invalidParameter("ReplicationSubnetGroupIdentifier must not be \"default\".");
        }
        return identifier;
    }

    /**
     * A description carrying a control character such as 0x01 is rejected rather than persisted;
     * AWS rejects non-printable control characters in this member.
     */
    private static String requireDescription(JsonNode request) {
        String description = optionalDescription(request);
        if (description == null) {
            throw invalidParameter("The parameter ReplicationSubnetGroupDescription must be provided"
                    + " and must not be blank.");
        }
        return description;
    }

    private static String optionalDescription(JsonNode request) {
        String description = text(request, "ReplicationSubnetGroupDescription");
        if (description == null) {
            return null;
        }
        if (description.isBlank()) {
            throw invalidParameter("The parameter ReplicationSubnetGroupDescription must not be blank.");
        }
        if (description.chars().anyMatch(Character::isISOControl)) {
            throw invalidParameter("The parameter ReplicationSubnetGroupDescription must contain only"
                    + " printable characters.");
        }
        return description;
    }

    private static List<String> requireSubnetIds(JsonNode request) {
        JsonNode node = request == null ? null : request.get("SubnetIds");
        if (node == null || node.isNull()) {
            throw invalidParameter("The parameter SubnetIds must be provided and must not be empty.");
        }
        if (!node.isArray()) {
            throw serialization("SubnetIds must be a list of strings.");
        }
        if (node.isEmpty()) {
            throw invalidParameter("The parameter SubnetIds must be provided and must not be empty.");
        }
        List<String> subnetIds = stringList(node, "SubnetIds");
        if (subnetIds.stream().anyMatch(String::isBlank)) {
            throw invalidParameter("The parameter SubnetIds must contain subnet identifiers.");
        }
        return subnetIds;
    }

    private static List<String> subnetGroupIdentifierFilters(JsonNode request) {
        return DmsRequests.filters(request, Set.of(SUBNET_GROUP_ID_FILTER)).stream()
                .flatMap(filter -> filter.values().stream())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .toList();
    }

    // ── Endpoint helpers ────────────────────────────────────────────────────

    private static String endpointType(String value) {
        String type = value.toLowerCase(Locale.ROOT);
        if (!"source".equals(type) && !"target".equals(type)) {
            throw invalidParameter("Invalid EndpointType: " + value + ". Valid values are source and target.");
        }
        return type.toUpperCase(Locale.ROOT);
    }

    private static String engineName(String value) {
        if (!DmsCatalog.isEngine(value)) {
            throw invalidParameter("Invalid EngineName: " + value + ".");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static void requireEngineSupportsType(String engineName, String endpointType) {
        if ("SOURCE".equals(endpointType) && DmsCatalog.TARGET_ONLY_ENGINES.contains(engineName)) {
            throw invalidParameter("Engine " + engineName + " is supported only as a target endpoint.");
        }
        if ("TARGET".equals(endpointType) && DmsCatalog.SOURCE_ONLY_ENGINES.contains(engineName)) {
            throw invalidParameter("Engine " + engineName + " is supported only as a source endpoint.");
        }
    }

    private static void applyEndpointFields(DmsEndpoint endpoint, JsonNode request, boolean replaceSettings) {
        String username = text(request, "Username");
        if (username != null) {
            endpoint.setUsername(username);
        }
        String password = text(request, "Password");
        if (password != null) {
            endpoint.setPassword(password);
        }
        String serverName = text(request, "ServerName");
        if (serverName != null) {
            endpoint.setServerName(serverName);
        }
        Integer port = integer(request, "Port");
        if (port != null) {
            if (port < 1 || port > 65535) {
                throw invalidParameter("Port must be between 1 and 65535.");
            }
            endpoint.setPort(port);
        }
        String databaseName = text(request, "DatabaseName");
        if (databaseName != null) {
            endpoint.setDatabaseName(databaseName);
        }
        String extraConnectionAttributes = text(request, "ExtraConnectionAttributes");
        if (extraConnectionAttributes != null) {
            endpoint.setExtraConnectionAttributes(extraConnectionAttributes);
        }
        String certificateArn = text(request, "CertificateArn");
        if (certificateArn != null) {
            endpoint.setCertificateArn(certificateArn);
        }
        String sslMode = text(request, "SslMode");
        if (sslMode != null) {
            if (!DmsCatalog.SSL_MODES.contains(sslMode)) {
                throw invalidParameter("Invalid SslMode: " + sslMode + ".");
            }
            endpoint.setSslMode(sslMode);
        }
        String serviceAccessRoleArn = text(request, "ServiceAccessRoleArn");
        if (serviceAccessRoleArn != null) {
            endpoint.setServiceAccessRoleArn(serviceAccessRoleArn);
        }
        String externalTableDefinition = text(request, "ExternalTableDefinition");
        if (externalTableDefinition != null) {
            endpoint.setExternalTableDefinition(externalTableDefinition);
        }

        Map<String, JsonNode> supplied = new LinkedHashMap<>();
        for (String member : ENDPOINT_SETTINGS_MEMBERS) {
            JsonNode settings = DmsRequests.object(request, member);
            if (settings != null) {
                supplied.put(member, settings.deepCopy());
            }
        }
        Map<String, JsonNode> settings = replaceSettings ? new LinkedHashMap<>() : new LinkedHashMap<>(endpoint.getSettings());
        supplied.forEach((member, value) -> {
            JsonNode existing = settings.get(member);
            if (!replaceSettings && existing instanceof ObjectNode existingObject) {
                ObjectNode merged = existingObject.deepCopy();
                merged.setAll((ObjectNode) value);
                settings.put(member, merged);
            } else {
                settings.put(member, value);
            }
        });
        if (replaceSettings && supplied.isEmpty()) {
            settings.putAll(endpoint.getSettings());
        }
        endpoint.setSettings(settings);
    }

    private static boolean endpointMatches(DmsEndpoint endpoint, Filter filter) {
        return switch (filter.name()) {
            case "endpoint-arn" -> filter.values().contains(endpoint.getEndpointArn());
            case "endpoint-type" -> filter.values().stream().anyMatch(endpoint.getEndpointType()::equalsIgnoreCase);
            case "endpoint-id" -> filter.values().stream().anyMatch(endpoint.getEndpointIdentifier()::equalsIgnoreCase);
            case "engine-name" -> filter.values().stream().anyMatch(endpoint.getEngineName()::equalsIgnoreCase);
            default -> false;
        };
    }

    private Optional<DmsEndpoint> endpointByArn(String arn, String region) {
        return endpoints.scan(regionKeys(region)).stream()
                .filter(endpoint -> arn.equals(endpoint.getEndpointArn()))
                .findFirst();
    }

    private void renameEndpointInConnections(String region, DmsEndpoint endpoint) {
        for (DmsConnection connection : connections.scan(regionKeys(region))) {
            if (endpoint.getEndpointArn().equals(connection.getEndpointArn())
                    && !endpoint.getEndpointIdentifier().equals(connection.getEndpointIdentifier())) {
                connection.setEndpointIdentifier(endpoint.getEndpointIdentifier());
                connections.put(connectionKey(region, connection), connection);
            }
        }
    }

    private static AwsException endpointNotFound(String arn) {
        return resourceNotFound("Endpoint " + arn + " not found.");
    }

    // ── Instance helpers ────────────────────────────────────────────────────

    private ReplicationInstance settledInstanceByArn(String arn, String region) {
        ReplicationInstance instance = instanceByArn(arn, region)
                .orElseThrow(() -> resourceNotFound("Replication instance " + arn + " not found."));
        return settle(instance, region);
    }

    private Optional<ReplicationInstance> instanceByArn(String arn, String region) {
        return instances.scan(regionKeys(region)).stream()
                .filter(instance -> arn.equals(instance.getReplicationInstanceArn()))
                .findFirst();
    }

    /**
     * Completes a transition started by an earlier request. There is no compute to wait for, so
     * an instance observed after its create, immediate modify, or reboot request has finished.
     */
    private ReplicationInstance settle(ReplicationInstance instance, String region) {
        String status = instance.getStatus();
        List<DmsInstanceEvent> finished = new ArrayList<>();
        switch (status == null ? "" : status) {
            case STATUS_CREATING -> finished.add(DmsInstanceEvent.CREATION_FINISHED);
            case STATUS_MODIFYING -> {
                if (instance.isPendingApplyImmediately()) {
                    finished.addAll(applyPendingModifications(instance));
                }
            }
            case STATUS_REBOOTING -> {
            }
            default -> {
                return instance;
            }
        }
        instance.setStatus(STATUS_AVAILABLE);
        instances.put(storageKey(region, instance.getReplicationInstanceIdentifier()), instance);
        finished.forEach(event -> recordInstanceEvent(region, instance, event));
        return instance;
    }

    private static List<DmsInstanceEvent> applyPendingModifications(ReplicationInstance instance) {
        List<DmsInstanceEvent> finished = new ArrayList<>();
        if (instance.getPendingReplicationInstanceClass() != null) {
            instance.setReplicationInstanceClass(instance.getPendingReplicationInstanceClass());
            finished.add(DmsInstanceEvent.CLASS_CHANGE_FINISHED);
        }
        if (instance.getPendingAllocatedStorage() != null) {
            instance.setAllocatedStorage(instance.getPendingAllocatedStorage());
            finished.add(DmsInstanceEvent.STORAGE_SCALE_FINISHED);
        }
        if (instance.getPendingMultiAZ() != null) {
            boolean multiAZ = instance.getPendingMultiAZ();
            instance.setMultiAZ(multiAZ);
            instance.setSecondaryAvailabilityZone(multiAZ
                    ? secondaryZone(distinctZones(instance.getSubnetGroup()), instance.getAvailabilityZone())
                    : null);
            finished.add(multiAZ ? DmsInstanceEvent.MULTI_AZ_FINISHED : DmsInstanceEvent.SINGLE_AZ_FINISHED);
        }
        if (instance.getPendingEngineVersion() != null) {
            instance.setEngineVersion(instance.getPendingEngineVersion());
        }
        if (instance.getPendingNetworkType() != null) {
            instance.setNetworkType(instance.getPendingNetworkType());
        }
        instance.clearPendingModifications();
        return finished;
    }

    private static void requireAvailable(ReplicationInstance instance) {
        if (!STATUS_AVAILABLE.equals(instance.getStatus())) {
            throw invalidState("Replication instance " + instance.getReplicationInstanceIdentifier()
                    + " is " + instance.getStatus() + ", not available.");
        }
    }

    private static boolean instanceMatches(ReplicationInstance instance, Filter filter) {
        return switch (filter.name()) {
            case "replication-instance-arn" -> filter.values().contains(instance.getReplicationInstanceArn());
            case "replication-instance-id" -> filter.values().stream()
                    .anyMatch(instance.getReplicationInstanceIdentifier()::equalsIgnoreCase);
            case "replication-instance-class" -> filter.values().contains(instance.getReplicationInstanceClass());
            case "engine-version" -> filter.values().contains(instance.getEngineVersion());
            default -> false;
        };
    }

    private void renameInstanceInConnections(String region, ReplicationInstance instance) {
        for (DmsConnection connection : connections.scan(regionKeys(region))) {
            if (instance.getReplicationInstanceArn().equals(connection.getReplicationInstanceArn())
                    && !instance.getReplicationInstanceIdentifier().equals(
                            connection.getReplicationInstanceIdentifier())) {
                connection.setReplicationInstanceIdentifier(instance.getReplicationInstanceIdentifier());
                connections.put(connectionKey(region, connection), connection);
            }
        }
    }

    private static String instanceClass(String value) {
        if (!DmsCatalog.INSTANCE_CLASSES.containsKey(value)) {
            throw invalidParameter("Invalid ReplicationInstanceClass: " + value + ".");
        }
        return value;
    }

    private static int allocatedStorage(int value) {
        if (value < DmsCatalog.MIN_ALLOCATED_STORAGE || value > DmsCatalog.MAX_ALLOCATED_STORAGE) {
            throw invalidParameter("AllocatedStorage must be between " + DmsCatalog.MIN_ALLOCATED_STORAGE
                    + " and " + DmsCatalog.MAX_ALLOCATED_STORAGE + " GB.");
        }
        return value;
    }

    private static String engineVersion(String value) {
        if (!DmsCatalog.ENGINE_VERSIONS.contains(value)) {
            throw invalidParameter("Invalid EngineVersion: " + value + ". Available versions are "
                    + DmsCatalog.ENGINE_VERSIONS + ".");
        }
        return value;
    }

    private static String networkType(String value) {
        if (value == null) {
            return "IPV4";
        }
        if (!DmsCatalog.NETWORK_TYPES.contains(value)) {
            throw invalidParameter("Invalid NetworkType: " + value + ".");
        }
        return value;
    }

    private static void requireNetworkTypeSupported(ReplicationSubnetGroup group, String networkType) {
        if (!group.getSupportedNetworkTypes().contains(networkType)) {
            throw invalidParameter("Replication subnet group " + group.getReplicationSubnetGroupIdentifier()
                    + " does not support network type " + networkType + ".");
        }
    }

    /** Validates {@code ddd:hh24:mi-ddd:hh24:mi} with a window of at least 30 minutes. */
    private static String maintenanceWindow(String value) {
        String window = value.toLowerCase(Locale.ROOT);
        Matcher matcher = MAINTENANCE_WINDOW.matcher(window);
        if (!matcher.matches()) {
            throw invalidParameter("PreferredMaintenanceWindow must be in the format ddd:hh24:mi-ddd:hh24:mi.");
        }
        int start = DAYS.indexOf(matcher.group(1)) * 1440 + Integer.parseInt(matcher.group(2)) * 60
                + Integer.parseInt(matcher.group(3));
        int end = DAYS.indexOf(matcher.group(4)) * 1440 + Integer.parseInt(matcher.group(5)) * 60
                + Integer.parseInt(matcher.group(6));
        int length = Math.floorMod(end - start, 7 * 1440);
        if (length < 30) {
            throw invalidParameter("PreferredMaintenanceWindow must be at least 30 minutes long.");
        }
        return window;
    }

    // ── Connection and schema helpers ───────────────────────────────────────

    private synchronized void completeConnection(String accountId, String key, String attemptId,
                                                 DmsConnectivityProbe.Result result) {
        connections.getForAccount(accountId, key)
                .filter(connection -> attemptId.equals(connection.getAttemptId()))
                .ifPresent(connection -> {
                    connection.setStatus(result.success() ? "successful" : "failed");
                    connection.setLastFailureMessage(result.success() ? null : result.failureMessage());
                    connections.putForAccount(accountId, key, connection);
                });
    }

    private synchronized void completeSchemaRefresh(String accountId, String key, String attemptId,
                                                    DmsConnectivityProbe.Result result) {
        schemaRefreshes.getForAccount(accountId, key)
                .filter(refresh -> attemptId.equals(refresh.getAttemptId()))
                .ifPresent(refresh -> {
                    refresh.setStatus(result.success() ? "successful" : "failed");
                    refresh.setLastFailureMessage(result.success() ? null : result.failureMessage());
                    if (result.success()) {
                        refresh.setSchemas(result.schemas());
                    }
                    refresh.setLastRefreshDateMillis(Instant.now().toEpochMilli());
                    schemaRefreshes.putForAccount(accountId, key, refresh);
                });
    }

    /** A probe that was running when the emulator stopped will never report; record that honestly. */
    private void failInterruptedProbes() {
        for (var entry : connections.scanAllAccountEntries(key -> true)) {
            if ("testing".equals(entry.value().getStatus())) {
                entry.value().setStatus("failed");
                entry.value().setLastFailureMessage("The emulator stopped before the connection test completed.");
                connections.putForAccount(entry.accountId(), entry.key(), entry.value());
            }
        }
        for (var entry : schemaRefreshes.scanAllAccountEntries(key -> true)) {
            if ("refreshing".equals(entry.value().getStatus())) {
                entry.value().setStatus("failed");
                entry.value().setLastFailureMessage("The emulator stopped before the schema refresh completed.");
                schemaRefreshes.putForAccount(entry.accountId(), entry.key(), entry.value());
            }
        }
    }

    private void deleteConnectionsWhere(String region, Predicate<DmsConnection> predicate) {
        for (DmsConnection connection : connections.scan(regionKeys(region))) {
            if (predicate.test(connection)) {
                connections.delete(connectionKey(region, connection));
            }
        }
    }

    private static String connectionKey(String region, DmsConnection connection) {
        return storageKey(region, connection.getReplicationInstanceArn() + "|" + connection.getEndpointArn());
    }

    // ── Event helpers ───────────────────────────────────────────────────────

    private void recordInstanceEvent(String region, ReplicationInstance instance, DmsInstanceEvent event) {
        Instant now = Instant.now();
        String id = String.format("%015d-%s", now.toEpochMilli(), UUID.randomUUID());
        events.put(storageKey(region, id), new DmsEvent(id, instance.getReplicationInstanceIdentifier(),
                "replication-instance", event.message(), List.of(event.legacyCategory()), now.toEpochMilli()));
        long oldest = now.minus(EVENT_RETENTION).toEpochMilli();
        for (DmsEvent stored : events.scan(regionKeys(region))) {
            if (stored.dateMillis() < oldest) {
                events.delete(storageKey(region, stored.id()));
            }
        }
        eventPublisher.publishInstanceEvent(event, instance.getReplicationInstanceArn(),
                instance.getReplicationInstanceIdentifier(), region);
    }

    // ── Tag helpers ─────────────────────────────────────────────────────────

    private record DmsArn(String resourceType, String resourceId) {
    }

    private Map<String, String> tagsOf(String resourceArn, String region) {
        DmsArn arn = parseDmsArn(resourceArn, region);
        return switch (arn.resourceType()) {
            case SUBNET_GROUP_ARN_RESOURCE_TYPE -> subnetGroups
                    .get(storageKey(region, arn.resourceId().toLowerCase(Locale.ROOT)))
                    .orElseThrow(() -> notFoundForArn(resourceArn)).getTags();
            case ENDPOINT_ARN_RESOURCE_TYPE -> endpointByArn(resourceArn, region)
                    .orElseThrow(() -> notFoundForArn(resourceArn)).getTags();
            case INSTANCE_ARN_RESOURCE_TYPE -> instanceByArn(resourceArn, region)
                    .orElseThrow(() -> notFoundForArn(resourceArn)).getTags();
            default -> throw notFoundForArn(resourceArn);
        };
    }

    private void updateTags(String resourceArn, String region, Consumer<Map<String, String>> mutation) {
        DmsArn arn = parseDmsArn(resourceArn, region);
        switch (arn.resourceType()) {
            case SUBNET_GROUP_ARN_RESOURCE_TYPE -> {
                String key = storageKey(region, arn.resourceId().toLowerCase(Locale.ROOT));
                ReplicationSubnetGroup group = subnetGroups.get(key).orElseThrow(() -> notFoundForArn(resourceArn));
                group.setTags(mutated(group.getTags(), mutation));
                subnetGroups.put(key, group);
            }
            case ENDPOINT_ARN_RESOURCE_TYPE -> {
                DmsEndpoint endpoint = endpointByArn(resourceArn, region)
                        .orElseThrow(() -> notFoundForArn(resourceArn));
                endpoint.setTags(mutated(endpoint.getTags(), mutation));
                endpoints.put(storageKey(region, endpoint.getEndpointIdentifier()), endpoint);
            }
            case INSTANCE_ARN_RESOURCE_TYPE -> {
                ReplicationInstance instance = instanceByArn(resourceArn, region)
                        .orElseThrow(() -> notFoundForArn(resourceArn));
                instance.setTags(mutated(instance.getTags(), mutation));
                instances.put(storageKey(region, instance.getReplicationInstanceIdentifier()), instance);
            }
            default -> throw notFoundForArn(resourceArn);
        }
    }

    private static Map<String, String> mutated(Map<String, String> tags, Consumer<Map<String, String>> mutation) {
        Map<String, String> copy = new LinkedHashMap<>(tags);
        mutation.accept(copy);
        return copy;
    }

    /**
     * Resolves a DMS ARN to its resource type and id. DescribeReplicationSubnetGroups returns no
     * ARN, so callers build {@code arn:aws:dms:<region>:<account>:subgrp:<id>} themselves.
     * Anything that is not a DMS ARN names no resource Floci holds, which is a
     * ResourceNotFoundFault rather than a parameter error.
     *
     * <p>An ARN naming another account or another Region is treated the same way. Tagging is not a
     * cross-account or cross-Region operation on AWS, and storage here is scoped to the caller's
     * account and keyed by the caller's Region, so honouring a foreign ARN would silently reach the
     * caller's own resource of that name instead of the one named.
     */
    private DmsArn parseDmsArn(String resourceArn, String callerRegion) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw notFoundForArn(resourceArn);
        }
        String[] resource = arn.resource().split(":", 2);
        if (!"dms".equals(arn.service()) || resource.length != 2 || resource[1].isBlank()) {
            throw notFoundForArn(resourceArn);
        }
        if (!arn.accountId().isBlank() && !arn.accountId().equals(regionResolver.getAccountId())) {
            throw notFoundForArn(resourceArn);
        }
        if (arn.region() != null && !arn.region().isBlank() && !arn.region().equals(callerRegion)) {
            throw notFoundForArn(resourceArn);
        }
        return new DmsArn(resource[0], resource[1]);
    }

    private static String requireResourceArn(JsonNode request) {
        String resourceArn = text(request, "ResourceArn");
        if (resourceArn == null || resourceArn.isBlank()) {
            throw invalidParameter("The parameter ResourceArn must be provided and must not be blank.");
        }
        return resourceArn;
    }

    private static AwsException notFoundForArn(String resourceArn) {
        return resourceNotFound("Resource " + resourceArn + " not found.");
    }

    // ── Shared helpers ──────────────────────────────────────────────────────

    private static <T> PaginatedResult<T> paginate(List<T> items, java.util.function.Function<T, String> cursor,
                                                   Integer maxRecords, String marker) {
        return Pagination.paginate(items, cursor, maxRecords, marker, DmsRequests.DEFAULT_MAX_RECORDS,
                DmsRequests.MAXIMUM_MAX_RECORDS, "InvalidParameterValueException");
    }

    private static AwsException subnetGroupNotFound(String identifier) {
        return resourceNotFound("Replication subnet group " + identifier + " not found.");
    }

    private static AwsException taskNotFound(String arn) {
        return resourceNotFound("Replication task " + arn + " not found.");
    }

    private static AwsException replicationConfigNotFound(String arn) {
        return resourceNotFound("Replication config " + arn + " not found.");
    }

    private String randomResourceId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder id = new StringBuilder(26);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                id.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            id.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return id.toString();
    }

    private static Predicate<String> regionKeys(String region) {
        return key -> key.startsWith(region + "::");
    }

    private static String storageKey(String region, String identifier) {
        return region + "::" + identifier;
    }
}
