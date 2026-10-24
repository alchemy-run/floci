package io.github.hectorvent.floci.services.lambdamicrovms;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.microvm.MicrovmImageService;
import io.github.hectorvent.floci.services.lambda.microvm.MicrovmRuntimeService;
import io.github.hectorvent.floci.services.lambda.microvm.MicrovmStore;
import io.github.hectorvent.floci.services.lambda.microvm.model.MicrovmImageRecord;
import io.github.hectorvent.floci.services.lambda.microvm.model.MicrovmImageVersionRecord;
import io.github.hectorvent.floci.services.lambda.microvm.model.MicrovmRecord;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Shared MicroVM control-plane views over the Docker-backed image and VM stores, plus network connectors. */
@ApplicationScoped
public class LambdaMicrovmsService {

    private static final int MAX_CONNECTOR_SUBNETS = 16;

    private final StorageFactory storageFactory;

    private final MicrovmImageService imageService;
    private final MicrovmRuntimeService runtimeService;
    private final MicrovmStore microvmStore;

    private Map<String, NetworkConnector> connectors = new ConcurrentHashMap<>();

    @Inject
    public LambdaMicrovmsService(StorageFactory storageFactory, MicrovmImageService imageService,
                                 MicrovmRuntimeService runtimeService, MicrovmStore microvmStore) {
        this.storageFactory = storageFactory;
        this.imageService = imageService;
        this.runtimeService = runtimeService;
        this.microvmStore = microvmStore;
    }

    @PostConstruct
    void init() {
        initializeStorage();
    }

    /**
     * MicroVMs are delivered as part of the lambda service, so they share its storage key and
     * therefore its configured mode and flush interval rather than declaring their own.
     */
    void initializeStorage() {
        if (storageFactory == null) {
            return; // keeps non-CDI unit tests working
        }
        this.connectors = new StorageBackedMap<>(storageFactory.create("lambda",
                "lambda-network-connectors.json", new TypeReference<Map<String, NetworkConnector>>() {}));
    }

    // ---------------------------------------------------------------- images

    @RegisterForReflection
    public static final class MicrovmImage {
        public String name;
        public String imageArn;
        public String state;
        public String description;
        public String baseImageArn;
        public String baseImageVersion;
        public String buildRoleArn;
        public String codeArtifactUri;
        public String latestActiveImageVersion;
        public String latestFailedImageVersion;
        public Map<String, Object> config = Map.of();
        public Instant createdAt;
        public Instant updatedAt;
        public final Map<String, String> tags = new LinkedHashMap<>();
        public final List<MicrovmImageVersion> versions = new ArrayList<>();
    }

    @RegisterForReflection
    public static final class MicrovmImageVersion {
        public String imageVersion;
        public String state;
        public String status;
        public String stateReason;
        public Map<String, Object> config = Map.of();
        public Instant createdAt;
        public final List<MicrovmBuild> builds = new ArrayList<>();
    }

    @RegisterForReflection
    public static final class MicrovmBuild {
        public String buildId;
        public String buildState;
        public String stateReason;
        public String architecture;
        public Instant createdAt;
        /** Graviton generation. The service builds for more than one. */
        public String chipsetGeneration;
    }

    public MicrovmImage createImage(String region, String accountId, String name,
                                    String baseImageArn, String buildRoleArn,
                                    String codeArtifactUri, String description) {
        return createImage(region, accountId,
                imageRequest(name, baseImageArn, buildRoleArn, codeArtifactUri, description));
    }

    public MicrovmImage createImage(String region, String accountId, Map<String, Object> request) {
        imageService.createImage(region, accountId, request);
        return getImage(region, (String) request.get("name"));
    }

    private static Map<String, Object> imageRequest(String name, String baseImageArn,
                                                   String buildRoleArn, String artifactUri, String description) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", name);
        request.put("baseImageArn", baseImageArn);
        request.put("buildRoleArn", buildRoleArn);
        request.put("codeArtifact", artifactUri == null ? null : Map.of("uri", artifactUri));
        request.put("description", description);
        return request;
    }

    private static String unwrap(String identifier) {
        if (identifier != null && identifier.startsWith("arn:")) {
            return identifier.substring(Math.max(identifier.lastIndexOf(':'), identifier.lastIndexOf('/')) + 1);
        }
        return identifier;
    }

    public MicrovmImage getImage(String region, String identifier) {
        return imageView(imageService.requireImage(region, identifier));
    }

    public List<MicrovmImage> listImages(String region) {
        return imageService.listImageRecords(region).stream()
                .map(LambdaMicrovmsService::imageView)
                .sorted(Comparator.comparing(image -> image.name)).toList();
    }

    public MicrovmImage updateImage(String region, String name, String baseImageArn,
                                    String buildRoleArn, String codeArtifactUri, String description) {
        return updateImage(region, name,
                imageRequest(name, baseImageArn, buildRoleArn, codeArtifactUri, description));
    }

    public MicrovmImage updateImage(String region, String identifier, Map<String, Object> request) {
        imageService.updateImage(region, identifier, request);
        return getImage(region, identifier);
    }

    public void deleteImage(String region, String identifier) {
        imageService.deleteImage(region, identifier);
    }

    public MicrovmImageVersion getVersion(String region, String identifier, String imageVersion) {
        MicrovmImageRecord image = imageService.requireImage(region, identifier);
        return versionView(imageService.requireVersion(image, imageVersion));
    }

    public MicrovmImageVersion updateVersionStatus(String region, String identifier,
                                                   String imageVersion, String status) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("status", status);
        imageService.updateImageVersion(region, identifier, imageVersion, request);
        return getVersion(region, identifier, imageVersion);
    }

    public void deleteVersion(String region, String identifier, String imageVersion) {
        imageService.deleteImageVersion(region, identifier, imageVersion);
    }

    private static MicrovmImage imageView(MicrovmImageRecord record) {
        MicrovmImage image = new MicrovmImage();
        image.name = record.getName();
        image.imageArn = record.getImageArn();
        image.state = record.getState();
        image.latestActiveImageVersion = record.getLatestActiveImageVersion();
        image.latestFailedImageVersion = record.getLatestFailedImageVersion();
        image.createdAt = Instant.ofEpochMilli(record.getCreatedAt());
        image.updatedAt = Instant.ofEpochMilli(record.getUpdatedAt() != null
                ? record.getUpdatedAt() : record.getCreatedAt());
        image.tags.putAll(record.getTags());
        for (MicrovmImageVersionRecord version : record.getVersions().values()) {
            if (!"DELETED".equals(version.getState())) {
                image.versions.add(versionView(version));
            }
            image.config = new LinkedHashMap<>(version.getConfig());
        }
        image.description = (String) image.config.get("description");
        image.baseImageArn = (String) image.config.get("baseImageArn");
        image.baseImageVersion = (String) image.config.get("baseImageVersion");
        image.buildRoleArn = (String) image.config.get("buildRoleArn");
        if (image.config.get("codeArtifact") instanceof Map<?, ?> artifact) {
            image.codeArtifactUri = (String) artifact.get("uri");
        }
        return image;
    }

    private static MicrovmImageVersion versionView(MicrovmImageVersionRecord record) {
        MicrovmImageVersion version = new MicrovmImageVersion();
        version.imageVersion = record.getImageVersion();
        version.state = record.getState();
        version.status = record.getStatus();
        version.stateReason = record.getStateReason();
        version.config = new LinkedHashMap<>(record.getConfig());
        version.createdAt = Instant.ofEpochMilli(record.getCreatedAt());
        // Both advertised Graviton targets use the local host build in the Docker emulator.
        for (String generation : List.of("4", "3")) {
            MicrovmBuild build = new MicrovmBuild();
            build.buildId = "4".equals(generation) ? record.getBuildId() : record.getBuildId() + "-3";
            build.buildState = record.getBuildState();
            build.stateReason = record.getBuildStateReason();
            build.architecture = record.getArchitecture();
            build.chipsetGeneration = generation;
            build.createdAt = version.createdAt;
            version.builds.add(build);
        }
        return version;
    }

    // -------------------------------------------------------- managed images

    /** The AWS-managed base image catalog, seeded with the one documented entry. */
    public List<Map<String, Object>> listManagedImages(String region) {
        return List.of(managedImage(region));
    }

    public List<Map<String, Object>> listManagedImageVersions(String region, String identifier) {
        if (identifier == null || (!identifier.contains("al2023-1") && !identifier.contains("al2023-minimal"))) {
            throw new AwsException("ResourceNotFoundException",
                    "Managed MicroVM image not found: " + identifier, 404);
        }
        Map<String, Object> version = new LinkedHashMap<>(managedImage(region));
        version.put("imageVersion", "1.0");
        return List.of(version);
    }

    private Map<String, Object> managedImage(String region) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("imageArn", "arn:aws:lambda:" + region + ":aws:microvm-image:al2023-1");
        entry.put("createdAt", 1781833144.754d);
        entry.put("updatedAt", 1784165932.388d);
        return entry;
    }

    // ------------------------------------------------------------------- vms

    @RegisterForReflection
    public static final class Microvm {
        public String microvmId;
        public String state;
        public String imageArn;
        public String imageVersion;
        public String endpoint;
        public String executionRoleArn;
        public Map<String, Object> idlePolicy;
        public int maximumDurationInSeconds;
        public List<String> ingressNetworkConnectors;
        public List<String> egressNetworkConnectors;
        public String stateReason;
        public Instant startedAt;
        public Instant terminatedAt;
    }

    public Microvm runMicrovm(String region, String accountId, String imageIdentifier) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("imageIdentifier", imageIdentifier);
        return runMicrovm(region, accountId, request);
    }

    public Microvm runMicrovm(String region, String accountId, Map<String, Object> request) {
        Map<String, Object> response = runtimeService.runMicrovm(region, accountId, request);
        return vmView(runtimeService.requireMicrovm(region, (String) response.get("microvmId")));
    }

    public Microvm getMicrovm(String region, String identifier) {
        return vmView(runtimeService.requireMicrovm(region, unwrap(identifier)));
    }

    public List<Microvm> listMicrovms(String region) {
        return listMicrovms(region, null, null);
    }

    public List<Microvm> listMicrovms(String region, String imageIdentifier, String imageVersion) {
        return microvmStore.list(region).stream()
                .filter(vm -> imageIdentifier == null || imageIdentifier.isBlank()
                        || (imageIdentifier.startsWith("arn:")
                            ? vm.getImageArn().replace(":microvm-image/", ":microvm-image:")
                                .equals(imageIdentifier.replace(":microvm-image/", ":microvm-image:"))
                            : imageIdentifier.equals(unwrap(vm.getImageArn()))))
                .filter(vm -> imageVersion == null || imageVersion.isBlank()
                        || imageVersion.equals(vm.getImageVersion()))
                .map(LambdaMicrovmsService::vmView)
                .sorted(Comparator.comparing(vm -> vm.microvmId)).toList();
    }

    public void terminateMicrovm(String region, String identifier) {
        Microvm vm = getMicrovm(region, identifier);
        if ("TERMINATED".equals(vm.state)) {
            throw new AwsException("ValidationException",
                    "The MicroVM " + vm.microvmId + " has been terminated and its state cannot be changed.", 400);
        }
        runtimeService.terminateMicrovm(region, vm.microvmId, "Success.");
    }

    private static Microvm vmView(MicrovmRecord record) {
        Microvm vm = new Microvm();
        vm.microvmId = record.getMicrovmId();
        vm.state = record.getState();
        vm.imageArn = record.getImageArn();
        vm.imageVersion = record.getImageVersion();
        vm.endpoint = record.getEndpoint();
        vm.executionRoleArn = record.getExecutionRoleArn();
        vm.idlePolicy = record.getIdlePolicy();
        vm.maximumDurationInSeconds = record.getMaximumDurationInSeconds();
        vm.ingressNetworkConnectors = record.getIngressNetworkConnectors();
        vm.egressNetworkConnectors = record.getEgressNetworkConnectors();
        vm.stateReason = record.getStateReason();
        vm.startedAt = Instant.ofEpochMilli(record.getStartedAt());
        vm.terminatedAt = record.getTerminatedAt() == null ? null : Instant.ofEpochMilli(record.getTerminatedAt());
        return vm;
    }

    // ------------------------------------------------------------ connectors

    @RegisterForReflection
    public static final class NetworkConnector {
        public String id;
        public String name;
        public String arn;
        public String state;
        public String operatorRole;
        public List<String> subnetIds = List.of();
        public List<String> securityGroupIds = List.of();
        // Both arrive on the request and were previously validated and then
        // dropped, so a client could not read back what it had just set.
        public String networkProtocol;
        public List<String> associatedComputeResourceTypes = List.of();
        public Instant lastModified;
        public String stateReason;
        public String lastUpdateStatus;
        public String lastUpdateStatusReason;
        public final Map<String, String> tags = new LinkedHashMap<>();
    }

    public NetworkConnector createConnector(String region, String accountId, String name,
                                            List<String> subnetIds, List<String> securityGroupIds,
                                            String operatorRole, String clientToken,
                                            List<String> computeResourceTypes, String networkProtocol) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "Name is required", 400);
        }
        // The three checks below are recorded live behavior; the Smithy model
        // marks all three members optional.
        if (clientToken == null || clientToken.isBlank()) {
            throw new AwsException("InvalidParameterValueException",
                    "ClientToken is a required field", 400);
        }
        if (computeResourceTypes == null || computeResourceTypes.isEmpty()) {
            throw new AwsException("InvalidParameterValueException",
                    "AssociatedComputeResourceTypes is required for VPC_EGRESS connector type", 400);
        }
        if (networkProtocol == null || networkProtocol.isBlank()) {
            throw new AwsException("InvalidParameterValueException",
                    "NetworkProtocol cannot be null or empty for VPC_EGRESS connector", 400);
        }
        if (operatorRole == null || operatorRole.isBlank()) {
            throw new AwsException("InvalidParameterValueException",
                    "NetworkConnectorOperatorRole is required for VPC_EGRESS connector type", 400);
        }
        if (subnetIds == null || subnetIds.isEmpty() || subnetIds.size() > MAX_CONNECTOR_SUBNETS) {
            throw new AwsException("InvalidParameterValueException",
                    "SubnetIds must contain between 1 and " + MAX_CONNECTOR_SUBNETS + " entries", 400);
        }
        boolean duplicate = in(connectors, region)
                .anyMatch(c -> name.equals(c.name));
        if (duplicate) {
            throw new AwsException("ResourceConflictException",
                    "A network connector with this name already exists: " + name, 409);
        }
        NetworkConnector connector = new NetworkConnector();
        connector.id = "nc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        connector.name = name;
        connector.arn = "arn:aws:lambda:" + region + ":" + accountId + ":network-connector:" + connector.id;
        connector.state = "PENDING";
        connector.operatorRole = operatorRole;
        connector.subnetIds = List.copyOf(subnetIds);
        connector.securityGroupIds = securityGroupIds == null ? List.of() : List.copyOf(securityGroupIds);
        connector.networkProtocol = networkProtocol;
        connector.associatedComputeResourceTypes =
                computeResourceTypes == null ? List.of() : List.copyOf(computeResourceTypes);
        connector.lastModified = Instant.now();
        // Recorded on the first read of a freshly created connector.
        connector.stateReason = "Initial creation";
        // Connectors do not provision local network infrastructure.
        connector.state = "ACTIVE";
        connectors.put(key(region, connector.id), connector);
        // The create response is a snapshot taken before the instant settle,
        // so it reports PENDING while the stored connector is already ACTIVE.
        // It has to carry every member the stored one does, or a client reads
        // back less than it just sent.
        NetworkConnector response = new NetworkConnector();
        response.id = connector.id;
        response.name = connector.name;
        response.arn = connector.arn;
        response.state = "PENDING";
        response.operatorRole = connector.operatorRole;
        response.subnetIds = connector.subnetIds;
        response.securityGroupIds = connector.securityGroupIds;
        response.networkProtocol = connector.networkProtocol;
        response.associatedComputeResourceTypes = connector.associatedComputeResourceTypes;
        response.lastModified = connector.lastModified;
        response.stateReason = connector.stateReason;
        return response;
    }

    public NetworkConnector getConnector(String region, String id) {
        NetworkConnector connector = connectors.get(key(region, unwrap(id)));
        if (connector == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Network connector not found: " + id, 404);
        }
        return connector;
    }

    public List<NetworkConnector> listConnectors(String region) {
        return in(connectors, region)
                .sorted(Comparator.comparing(c -> c.id))
                .toList();
    }

    /**
     * Applies an UpdateNetworkConnector. Every member of the VPC egress configuration and the
     * operator role is mutable; members absent from the request keep their stored value.
     */
    public NetworkConnector updateConnector(String region, String id,
                                            List<String> subnetIds, List<String> securityGroupIds,
                                            String networkProtocol, List<String> computeResourceTypes,
                                            String operatorRole) {
        NetworkConnector connector = getConnector(region, id);
        if (subnetIds != null && subnetIds.size() > MAX_CONNECTOR_SUBNETS) {
            throw new AwsException("InvalidParameterValueException",
                    "SubnetIds must contain between 1 and " + MAX_CONNECTOR_SUBNETS + " entries", 400);
        }
        boolean changed = false;
        if (subnetIds != null && !subnetIds.isEmpty() && !subnetIds.equals(connector.subnetIds)) {
            connector.subnetIds = List.copyOf(subnetIds);
            changed = true;
        }
        if (securityGroupIds != null && !securityGroupIds.equals(connector.securityGroupIds)) {
            connector.securityGroupIds = List.copyOf(securityGroupIds);
            changed = true;
        }
        if (networkProtocol != null && !networkProtocol.isBlank()
                && !networkProtocol.equals(connector.networkProtocol)) {
            connector.networkProtocol = networkProtocol;
            changed = true;
        }
        if (computeResourceTypes != null && !computeResourceTypes.isEmpty()
                && !computeResourceTypes.equals(connector.associatedComputeResourceTypes)) {
            connector.associatedComputeResourceTypes = List.copyOf(computeResourceTypes);
            changed = true;
        }
        if (operatorRole != null && !operatorRole.isBlank() && !operatorRole.equals(connector.operatorRole)) {
            connector.operatorRole = operatorRole;
            changed = true;
        }
        connector.lastModified = Instant.now();
        connector.lastUpdateStatus = "Successful";
        connector.lastUpdateStatusReason = changed ? null : "No configuration changes detected";
        persist(connectors, region, connector.id, connector);
        return connector;
    }

    public void deleteConnector(String region, String id) {
        getConnector(region, id);
        connectors.remove(key(region, unwrap(id)));
    }

    // ------------------------------------------------------------------ tags

    /** True when the ARN names a MicroVM-family resource this service owns. */
    public static boolean ownsArn(String arn) {
        return arn != null && (arn.contains(":microvm-image:") || arn.contains(":microvm-image/")
                || arn.contains(":microvm:")
                || arn.contains(":network-connector:"));
    }

    public Map<String, String> listTags(String region, String arn) {
        return taggable(region, arn);
    }

    public void tagResource(String region, String arn, Map<String, String> tags) {
        if (imageService.isMicrovmImageArn(arn)) {
            imageService.tagResource(region, arn, tags);
        } else {
            mutateTags(region, arn, target -> target.putAll(tags));
        }
    }

    public void untagResource(String region, String arn, List<String> tagKeys) {
        if (imageService.isMicrovmImageArn(arn)) {
            imageService.untagResource(region, arn, tagKeys == null ? List.of() : tagKeys);
        } else {
            mutateTags(region, arn, target -> {
                if (tagKeys != null) {
                    tagKeys.forEach(target::remove);
                }
            });
        }
    }

    /**
     * Applies a tag mutation and writes the owning resource back. The tag map is a field of the
     * stored object, so mutating it alone would never reach the backend.
     */
    private void mutateTags(String region, String arn, Consumer<Map<String, String>> mutation) {
        if (arn.contains(":network-connector:")) {
            NetworkConnector connector = getConnector(region, arn.substring(arn.lastIndexOf(':') + 1));
            mutation.accept(connector.tags);
            persist(connectors, region, connector.id, connector);
            return;
        }
        throw new AwsException("ResourceNotFoundException",
                "Resource not found: " + arn, 404);
    }

    private Map<String, String> taggable(String region, String arn) {
        if (imageService.isMicrovmImageArn(arn)) {
            return imageService.listTags(region, arn);
        }
        if (arn.contains(":network-connector:")) {
            String id = arn.substring(arn.lastIndexOf(':') + 1);
            return getConnector(region, id).tags;
        }
        throw new AwsException("ResourceNotFoundException",
                "Resource not found: " + arn, 404);
    }

    // ----------------------------------------------------------------- state

    /**
     * The stored key for a resource. The account half is added by the backend, so callers only
     * supply the region, and a listing filters on the same prefix the backend hands back.
     */
    private static String key(String region, String id) {
        return region + "/" + id;
    }

    private static <V> Stream<V> in(Map<String, V> store, String region) {
        String prefix = region + "/";
        return store.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue);
    }

    /**
     * Re-persists an entity whose fields were mutated in place. {@link StorageBackedMap} only marks
     * the backend dirty on put and remove, so a mutation applied to a value from get or a listing
     * would be dropped by the periodic flush and lost on restart.
     */
    private static <V> void persist(Map<String, V> store, String region, String id, V value) {
        store.put(key(region, id), value);
    }
}
