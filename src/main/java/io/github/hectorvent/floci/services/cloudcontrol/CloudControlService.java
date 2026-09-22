package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationResourceProvisioner;
import io.github.hectorvent.floci.services.cloudformation.SsmResourceBackend;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

@ApplicationScoped
public class CloudControlService {

    private static final String DEFAULT_ACCOUNT = "000000000000";

    private final S3Service s3Service;
    private final Ec2Service ec2Service;
    private final IamService iamService;
    private final CloudFormationResourceProvisioner provisioner;
    private final ObjectMapper mapper;
    @Inject
    SsmResourceBackend ssmBackend;
    private final Map<String, ReentrantLock> operationLocks = new ConcurrentHashMap<>();
    private final AccountAwareStorageBackend<PersistedRequest> requestStore;
    private final AccountAwareStorageBackend<PersistedCreatedResource> createdStore;
    /** How many finished request tokens to keep before evicting the oldest. */
    private static final int MAX_RETAINED_REQUESTS = 1000;

    /**
     * Types whose delete needs state captured at create time — a custom resource's ServiceToken and
     * properties, a nodegroup's cluster name, an inline policy's principals. Deleting one of these
     * from type and identifier alone is a no-op, so Cloud Control must not report SUCCESS for it.
     */
    private static final Set<String> ATTRIBUTE_BACKED_DELETES =
            Set.of("AWS::EKS::Nodegroup", "AWS::IAM::Policy");

    /** RequestToken → ProgressEvent. Cloud Control is async; clients poll by token. */
    private final Map<String, ProgressEvent> requests = new ConcurrentHashMap<>();
    /** Token insertion order, so the map can be bounded without losing in-flight requests. */
    private final ConcurrentLinkedQueue<String> requestOrder =
            new ConcurrentLinkedQueue<>();
    /** Create-time deletion metadata; reads always observe the backend. */
    private final Map<String, CreatedResource> created = new ConcurrentHashMap<>();
    private final ExecutorService executor =
            Executors.newFixedThreadPool(4);

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void restorePersistedState() {
        for (AccountAwareStorageBackend.AccountEntry<PersistedCreatedResource> entry
                : createdStore.scanAllAccountEntries(key -> true)) {
            PersistedCreatedResource persisted = entry.value();
            String accountId = persisted.accountId() == null ? DEFAULT_ACCOUNT : persisted.accountId();
            created.put(createdKey(accountId, persisted.region(), persisted.typeName(), persisted.identifier()),
                    new CreatedResource(persisted.requestToken(), accountId,
                            persisted.attributes() == null ? Map.of() : Map.copyOf(persisted.attributes()),
                            persisted.model()));
        }
        List<AccountAwareStorageBackend.AccountEntry<PersistedRequest>> persistedRequests =
                requestStore.scanAllAccountEntries(key -> true);
        persistedRequests.sort(Comparator.comparingLong(
                        (AccountAwareStorageBackend.AccountEntry<PersistedRequest> entry) -> entry.value().createdAt())
                .thenComparing(entry -> entry.value().event().requestToken()));
        for (AccountAwareStorageBackend.AccountEntry<PersistedRequest> entry : persistedRequests) {
            PersistedRequest persisted = entry.value();
            ProgressEvent event = persisted.event();
            String accountId = event.accountId() == null ? DEFAULT_ACCOUNT : event.accountId();
            ProgressEvent normalized = event.accountId() == null
                    ? new ProgressEvent(event.typeName(), event.identifier(), event.requestToken(),
                    event.operation(), event.operationStatus(), event.statusMessage(),
                    event.resourceModel(), accountId) : event;
            if ("IN_PROGRESS".equals(normalized.operationStatus())) {
                normalized = normalized.failed("Operation interrupted by emulator restart; inspect the live resource before retrying.");
                persistRequest(new PersistedRequest(normalized, persisted.region(), persisted.desiredStateJson(),
                        persisted.createdAt()));
            }
            requests.put(normalized.requestToken(), normalized);
            requestOrder.add(normalized.requestToken());
        }
        trimPersistedRequests();
    }

    private void persistRequest(PersistedRequest persisted) {
        String accountId = persisted.event().accountId() == null ? DEFAULT_ACCOUNT : persisted.event().accountId();
        requestStore.putForAccount(accountId, persisted.event().requestToken(), persisted);
    }

    private void persistCreated(String accountId, String region, String typeName, String identifier,
                                CreatedResource resource) {
        createdStore.putForAccount(accountId, region + "|" + typeName + "|" + identifier,
                new PersistedCreatedResource(resource.requestToken(), accountId, region, typeName, identifier,
                        resource.attributes(), resource.model()));
    }

    private void removePersistedCreated(String accountId, String region, String typeName, String identifier) {
        createdStore.deleteForAccount(accountId, region + "|" + typeName + "|" + identifier);
    }

    /** Create-time state for a resource this service provisioned. */
    private record CreatedResource(String requestToken, String accountId,
                                   Map<String, String> attributes, String model) {}

    @RegisterForReflection
    record PersistedRequest(ProgressEvent event, String region, String desiredStateJson, long createdAt) {
        PersistedRequest(ProgressEvent event, String region, String desiredStateJson) {
            this(event, region, desiredStateJson, 0L);
        }
    }

    @RegisterForReflection
    record PersistedCreatedResource(String requestToken, String accountId, String region, String typeName,
                                            String identifier, Map<String, String> attributes,
                                            String model) {}

    private static String createdKey(String accountId, String region, String typeName, String identifier) {
        return accountId + "|" + region + "|" + typeName + "|" + identifier;
    }


    @Inject
    public CloudControlService(S3Service s3Service, Ec2Service ec2Service,
                               IamService iamService, CloudFormationResourceProvisioner provisioner,
                               ObjectMapper mapper, StorageFactory storageFactory) {
        this(s3Service, ec2Service, iamService, provisioner, mapper,
                storageFactory.create("cloudcontrol", "cloudcontrol-requests.json",
                        new TypeReference<Map<String, PersistedRequest>>() {}),
                storageFactory.create("cloudcontrol", "cloudcontrol-created.json",
                        new TypeReference<Map<String, PersistedCreatedResource>>() {}));
    }

    public CloudControlService(S3Service s3Service, Ec2Service ec2Service,
                               IamService iamService, CloudFormationResourceProvisioner provisioner,
                               ObjectMapper mapper) {
        this(s3Service, ec2Service, iamService, provisioner, mapper,
                AccountAwareStorageBackend.inMemory(DEFAULT_ACCOUNT),
                AccountAwareStorageBackend.inMemory(DEFAULT_ACCOUNT));
    }

    CloudControlService(S3Service s3Service, Ec2Service ec2Service,
                                IamService iamService, CloudFormationResourceProvisioner provisioner,
                                ObjectMapper mapper,
                                AccountAwareStorageBackend<PersistedRequest> requestStore,
                                AccountAwareStorageBackend<PersistedCreatedResource> createdStore) {
        this.s3Service = s3Service;
        this.ec2Service = ec2Service;
        this.iamService = iamService;
        this.provisioner = provisioner;
        this.mapper = mapper;
        this.requestStore = requestStore;
        this.createdStore = createdStore;
        restorePersistedState();
    }

    private boolean hasCreatedResourceForAnotherAccount(String region, String typeName,
                                                         String identifier, String accountId) {
        String suffix = "|" + region + "|" + typeName + "|" + identifier;
        return created.entrySet().stream()
                .anyMatch(entry -> entry.getKey().endsWith(suffix)
                        && !accountId.equals(entry.getValue().accountId()));
    }

    /** Compatibility entry point for direct callers without request account context. */
    public ProgressEvent createResource(String region, String typeName, String desiredStateJson) {
        return createResource(region, DEFAULT_ACCOUNT, typeName, desiredStateJson);
    }

    public ProgressEvent createResource(String region, String accountId, String typeName, String desiredStateJson) {
        // DesiredState is a required member of CreateResourceInput. Defaulting an absent one to an
        // empty object provisioned a resource the caller never described.
        if (desiredStateJson == null || desiredStateJson.isBlank()) {
            throw new AwsException("InvalidRequestException", "DesiredState is required.", 400);
        }
        JsonNode props;
        try {
            props = mapper.readTree(desiredStateJson);
        } catch (Exception e) {
            throw new AwsException("InvalidRequestException", "DesiredState is not valid JSON.", 400);
        }
        if (props == null || !props.isObject()) {
            throw new AwsException("InvalidRequestException", "DesiredState must be a JSON object.", 400);
        }
        listResources(region, accountId, typeName);
        String token = UUID.randomUUID().toString();
        ProgressEvent pending = new ProgressEvent(typeName, null, token, "CREATE", "IN_PROGRESS", null, null, accountId);
        operationLocks.put(token, new ReentrantLock());
        persistRequest(new PersistedRequest(pending, region, desiredStateJson, System.currentTimeMillis()));
        record(pending);
        submitCreate(region, accountId, typeName, desiredStateJson, token, pending, props);
        return pending;
    }

    private void submitCreate(String region, String accountId, String typeName, String desiredStateJson,
                              String token, ProgressEvent pending, JsonNode props) {
        persistRequest(new PersistedRequest(pending, region, desiredStateJson, System.currentTimeMillis()));
        ReentrantLock lock = operationLocks.get(token);
        executor.submit(() -> RequestScopes.runAs(accountId, () -> {
            lock.lock();
            try {
                ProgressEvent current = requests.get(token);
                if (current == null || !"IN_PROGRESS".equals(current.operationStatus())) return;
                try {
                    String identifier;
                    Map<String, String> attributes;
                    if ("AWS::SSM::Parameter".equals(typeName)) {
                        identifier = props.path("Name").asText("cloudcontrol-" + token);
                        ssmBackend.write(identifier, props, false, region);
                        attributes = Map.of();
                    } else {
                        StackResource resource = provisioner.provisionStandalone(typeName, props, region, accountId);
                        if (resource == null || resource.getPhysicalId() == null
                                || !"CREATE_COMPLETE".equals(resource.getStatus())) {
                            throw new AwsException("InvalidRequestException", resource == null
                                    ? "No resource was provisioned." : String.valueOf(resource.getStatusReason()), 400);
                        }
                        identifier = resource.getPhysicalId();
                        attributes = resource.getAttributes() == null ? Map.of() : Map.copyOf(resource.getAttributes());
                    }
                    String model = getResource(region, accountId, typeName, identifier).properties();
                    CreatedResource state = new CreatedResource(token, accountId, attributes, model);
                    created.put(createdKey(accountId, region, typeName, identifier), state);
                    persistCreated(accountId, region, typeName, identifier, state);
                    record(new ProgressEvent(typeName, identifier, token, "CREATE", "SUCCESS", null, model, accountId));
                } catch (Exception e) {
                    record(e instanceof AwsException aws ? pending.failed(aws)
                            : pending.failed(e.getMessage() == null ? e.toString() : e.getMessage()));
                }
            } finally {
                operationLocks.remove(token, lock);
                lock.unlock();
            }
        }));
    }

    public ProgressEvent updateResource(String region, String accountId, String typeName,
                                        String identifier, String patchDocument) {
        if (!"AWS::SSM::Parameter".equals(typeName)) {
            throw new AwsException("UnsupportedActionException", "UpdateResource is not supported for " + typeName, 400);
        }
        JsonNode patch;
        JsonNode observed;
        try {
            patch = mapper.readTree(patchDocument);
            observed = mapper.readTree(getResource(region, accountId, typeName, identifier).properties());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new AwsException("InvalidRequestException", "PatchDocument is not valid JSON.", 400);
        }
        JsonNode desired = ResourcePatch.apply(observed, patch);
        String token = UUID.randomUUID().toString();
        ProgressEvent pending = new ProgressEvent(typeName, identifier, token, "UPDATE", "IN_PROGRESS", null, null, accountId);
        persistRequest(new PersistedRequest(pending, region, desired.toString(), System.currentTimeMillis()));
        record(pending);
        try {
            ObjectNode actual = RequestScopes.callAs(accountId, () -> ssmBackend.write(identifier, desired, true, region));
            return record(new ProgressEvent(typeName, identifier, token, "UPDATE", "SUCCESS", null,
                    actual.toString(), accountId));
        } catch (AwsException e) {
            return record(pending.failed(e));
        }
    }

    public ProgressEvent requestStatus(String region, String accountId, String token) {
        ProgressEvent event = requestStatus(accountId, token);
        PersistedRequest persisted = requestStore.getForAccount(accountId, token).orElse(null);
        if (persisted == null || !region.equals(persisted.region())) {
            throw new AwsException("RequestTokenNotFoundException", "Request token " + token + " was not found.", 404);
        }
        return event;
    }

    public List<ProgressEvent> listRequests(String region, String accountId, List<String> operations,
                                           List<String> statuses) {
        return requests.values().stream().filter(event -> accountId.equals(event.accountId()))
                .filter(event -> requestStore.getForAccount(accountId, event.requestToken())
                        .map(request -> region.equals(request.region())).orElse(false))
                .filter(event -> operations.isEmpty() || operations.contains(event.operation()))
                .filter(event -> statuses.isEmpty() || statuses.contains(event.operationStatus()))
                .sorted(Comparator.comparing(ProgressEvent::requestToken)).toList();
    }

    public ProgressEvent cancelRequest(String region, String accountId, String token) {
        requestStatus(region, accountId, token);
        ReentrantLock lock = operationLocks.get(token);
        if (lock != null && lock.tryLock()) {
            try {
                ProgressEvent current = requestStatus(region, accountId, token);
                if ("IN_PROGRESS".equals(current.operationStatus())) {
                    return record(new ProgressEvent(current.typeName(), current.identifier(), token,
                            current.operation(), "CANCEL_COMPLETE", "Cancelled before provisioning started.",
                            null, accountId));
                }
            } finally {
                lock.unlock();
            }
        }
        throw new AwsException("ConcurrentModificationException", "The resource operation cannot be cancelled in its current state.", 400);
    }

    /** Compatibility entry point for direct callers without request account context. */
    public ProgressEvent deleteResource(String region, String typeName, String identifier) {
        return deleteResource(region, DEFAULT_ACCOUNT, typeName, identifier);
    }

    /** Cloud Control {@code DeleteResource}. Deletes are quick, so this stays synchronous. */
    public ProgressEvent deleteResource(String region, String accountId, String typeName, String identifier) {
        String key = createdKey(accountId, region, typeName, identifier);
        CreatedResource state = created.get(key);
        Map<String, String> attributes = state == null ? Map.of() : state.attributes();
        if (state == null && hasCreatedResourceForAnotherAccount(region, typeName, identifier, accountId)
                && listResources(region, accountId, typeName).stream().noneMatch(r -> identifier.equals(r.identifier()))) {
            return record(region, new ProgressEvent(typeName, identifier, UUID.randomUUID().toString(),
                    "DELETE", "FAILED", "Resource belongs to another account.", null, accountId));
        }
        boolean custom = typeName != null
                && (typeName.startsWith("Custom::") || "AWS::CloudFormation::CustomResource".equals(typeName));
        if (attributes.isEmpty() && (custom || ATTRIBUTE_BACKED_DELETES.contains(typeName))) {
            // The delete would no-op. Reporting SUCCESS over a resource that is still there is the
            // worse failure, so surface it instead.
            return record(region, new ProgressEvent(typeName, identifier, UUID.randomUUID().toString(),
                    "DELETE", "FAILED",
                    "DeleteResource for " + typeName + " needs create-time state that Cloud Control does "
                    + "not hold for " + identifier + ".", null, accountId));
        }

        getResource(region, accountId, typeName, identifier);
        ProgressEvent pending = new ProgressEvent(typeName, identifier, UUID.randomUUID().toString(),
                "DELETE", "IN_PROGRESS", null, null, accountId);
        persistRequest(new PersistedRequest(pending, region, null, System.currentTimeMillis()));
        record(pending);
        try {
            RequestScopes.runAs(accountId, () -> {
                if ("AWS::SSM::Parameter".equals(typeName)) ssmBackend.delete(identifier, region);
                else provisioner.deleteStandalone(typeName, identifier, region, accountId, attributes);
            });
            boolean exists = listResources(region, accountId, typeName).stream()
                    .anyMatch(resource -> identifier.equals(resource.identifier()));
            if (exists) return record(pending.failed("Backend still contains resource after deletion."));
            created.remove(key);
            removePersistedCreated(accountId, region, typeName, identifier);
            return record(new ProgressEvent(typeName, identifier, pending.requestToken(),
                    "DELETE", "SUCCESS", null, null, accountId));
        } catch (AwsException e) {
            return record(pending.failed(e));
        }
    }

    /** Compatibility entry point for direct callers without request account context. */
    public ProgressEvent requestStatus(String requestToken) {
        return requestStatus(DEFAULT_ACCOUNT, requestToken);
    }

    /** Cloud Control {@code GetResourceRequestStatus}. */
    public ProgressEvent requestStatus(String accountId, String requestToken) {
        ProgressEvent event = requests.get(requestToken);
        if (event == null || !accountId.equals(event.accountId())) {
            throw new AwsException("RequestTokenNotFoundException",
                    "Request token " + requestToken + " was not found.", 404);
        }
        return event;
    }

    /** Compatibility entry point for direct callers without request account context. */
    public ResourceDescription getResource(String region, String typeName, String identifier) {
        return getResource(region, DEFAULT_ACCOUNT, typeName, identifier);
    }

    /** Cloud Control {@code GetResource}: a single resource from the read side, by identifier. */
    public ResourceDescription getResource(String region, String accountId, String typeName, String identifier) {
        List<ResourceDescription> listed = RequestScopes.callAs(accountId,
                () -> resourcesForType(region, typeName));
        if (listed != null) {
            for (ResourceDescription d : listed) {
                if (d.identifier().equals(identifier)) {
                    return d;
                }
            }
        }
        if (listed == null) {
            throw new AwsException("UnsupportedActionException", "GetResource is not supported for " + typeName, 400);
        }
        throw new AwsException("ResourceNotFoundException",
                "Resource " + identifier + " of type " + typeName + " was not found.", 404);
    }

    /**
     * Stores a request's latest state, evicting the oldest finished tokens once the map grows past
     * {@link #MAX_RETAINED_REQUESTS}. In-flight tokens are never evicted — a client still polling
     * must not get RequestTokenNotFound.
     */
    private ProgressEvent record(String region, ProgressEvent event) {
        persistRequest(new PersistedRequest(event, region, null, System.currentTimeMillis()));
        return record(event);
    }

    public Double eventTime(ProgressEvent event) {
        return requestStore.getForAccount(event.accountId(), event.requestToken())
                .filter(request -> request.createdAt() > 0)
                .map(request -> request.createdAt() / 1000.0).orElse(null);
    }

    private ProgressEvent record(ProgressEvent event) {
        if (requests.put(event.requestToken(), event) == null) {
            requestOrder.add(event.requestToken());
        }
        PersistedRequest previous = requestStore.getForAccount(
                event.accountId() == null ? DEFAULT_ACCOUNT : event.accountId(), event.requestToken()).orElse(null);
        persistRequest(new PersistedRequest(event,
                previous == null ? null : previous.region(),
                previous == null ? null : previous.desiredStateJson(),
                previous == null ? System.currentTimeMillis() : previous.createdAt()));
        trimPersistedRequests();
        return event;
    }

    private void trimPersistedRequests() {
        int inspected = 0;
        int candidates = requestOrder.size();
        while (requests.size() > MAX_RETAINED_REQUESTS && inspected < candidates) {
            String oldest = requestOrder.poll();
            if (oldest == null) {
                break;
            }
            ProgressEvent existing = requests.get(oldest);
            inspected++;
            if (existing != null && "IN_PROGRESS".equals(existing.operationStatus())) {
                requestOrder.add(oldest); // still running — keep it and move on
                continue;
            }
            if (existing != null) {
                requests.remove(oldest);
                String accountId = existing.accountId() == null ? DEFAULT_ACCOUNT : existing.accountId();
                requestStore.deleteForAccount(accountId, oldest);
            }
        }
    }

    @RegisterForReflection
    public record ProgressEvent(String typeName, String identifier, String requestToken,
                                String operation, String operationStatus, String statusMessage,
                                String resourceModel, String accountId, String errorCode) {
        public ProgressEvent(String typeName, String identifier, String requestToken,
                             String operation, String operationStatus, String statusMessage,
                             String resourceModel, String accountId) {
            this(typeName, identifier, requestToken, operation, operationStatus, statusMessage,
                    resourceModel, accountId, "FAILED".equals(operationStatus) ? "GeneralServiceException" : null);
        }

        public ProgressEvent(String typeName, String identifier, String requestToken,
                             String operation, String operationStatus, String statusMessage,
                             String resourceModel) {
            this(typeName, identifier, requestToken, operation, operationStatus, statusMessage,
                    resourceModel, DEFAULT_ACCOUNT);
        }

        ProgressEvent failed(String message) {
            return new ProgressEvent(typeName, identifier, requestToken, operation, "FAILED", message, resourceModel, accountId);
        }

        ProgressEvent failed(AwsException error) {
            String code = switch (error.getErrorCode()) {
                case "ParameterAlreadyExists" -> "AlreadyExists";
                case "ParameterNotFound", "ResourceNotFoundException" -> "NotFound";
                case "ValidationException", "InvalidRequestException", "ParameterPatternMismatchException" -> "InvalidRequest";
                default -> "GeneralServiceException";
            };
            return new ProgressEvent(typeName, identifier, requestToken, operation, "FAILED", error.getMessage(),
                    resourceModel, accountId, code);
        }
    }

    /** Unsupported backends are errors, not empty inventories. */
    public List<ResourceDescription> listResources(String region, String typeName) {
        return listResources(region, DEFAULT_ACCOUNT, typeName);
    }

    public List<ResourceDescription> listResources(String region, String accountId, String typeName) {
        List<ResourceDescription> resources = RequestScopes.callAs(accountId,
                () -> resourcesForType(region, typeName));
        if (resources == null) {
            throw new AwsException("UnsupportedActionException",
                    "ListResources is not supported for resource type " + typeName + ".", 400);
        }
        return resources;
    }

    /** The types {@link #listResources} and {@link #getResource} can enumerate, or {@code null}. */
    private List<ResourceDescription> resourcesForType(String region, String typeName) {
        return switch (typeName) {
            case "AWS::SSM::Parameter" -> ssmBackend.list(region).stream()
                    .map(model -> new ResourceDescription(model.path("Name").asText(), model.toString())).toList();
            case "AWS::S3::Bucket" -> s3Buckets();
            case "AWS::EC2::InternetGateway" -> ec2Service.describeInternetGateways(region, List.of(), Map.of()).stream()
                    .map(gateway -> {
                        ObjectNode model = mapper.createObjectNode();
                        model.put("InternetGatewayId", gateway.getInternetGatewayId());
                        addTags(model, gateway.getTags());
                        return new ResourceDescription(gateway.getInternetGatewayId(), model.toString());
                    }).toList();
            case "AWS::EC2::VPC" -> vpcs(region);
            case "AWS::EC2::Subnet" -> subnets(region);
            case "AWS::EC2::SecurityGroup" -> securityGroups(region);
            case "AWS::IAM::Role" -> roles();
            case "AWS::IAM::User" -> users();
            case "AWS::EC2::Instance" -> instances(region);
            case "AWS::EC2::LaunchTemplate" -> launchTemplates(region);
            case "AWS::IAM::InstanceProfile" -> instanceProfiles();
            default -> null;
        };
    }

    /**
     * Cloud Control reports a resource's current model, not an echo of what was asked for, so an
     * instance has to carry the values only the running resource has — its addresses, its state,
     * and the subnet and security groups it actually landed in. A caller that provisions through
     * Cloud Control and then reads the resource back has no other way to reach them.
     */
    private List<ResourceDescription> instances(String region) {
        List<ResourceDescription> resources = new ArrayList<>();
        for (Reservation reservation : ec2Service.describeInstances(region, List.of(), Map.of())) {
            for (Instance instance : reservation.getInstances()) {
                ObjectNode properties = mapper.createObjectNode();
                properties.put("InstanceId", instance.getInstanceId());
                putIfPresent(properties, "ImageId", instance.getImageId());
                putIfPresent(properties, "InstanceType", instance.getInstanceType());
                putIfPresent(properties, "SubnetId", instance.getSubnetId());
                putIfPresent(properties, "VpcId", instance.getVpcId());
                putIfPresent(properties, "PrivateIp", instance.getPrivateIpAddress());
                putIfPresent(properties, "PublicIp", instance.getPublicIpAddress());
                putIfPresent(properties, "AvailabilityZone",
                        instance.getPlacement() == null ? null : instance.getPlacement().getAvailabilityZone());
                if (instance.getState() != null) {
                    putIfPresent(properties, "State", instance.getState().getName());
                }
                if (instance.getSecurityGroups() != null && !instance.getSecurityGroups().isEmpty()) {
                    ArrayNode groups = properties.putArray("SecurityGroupIds");
                    for (GroupIdentifier g : instance.getSecurityGroups()) {
                        if (g.getGroupId() != null) groups.add(g.getGroupId());
                    }
                }
                resources.add(new ResourceDescription(instance.getInstanceId(), propertiesString(properties)));
            }
        }
        return resources;
    }

    private List<ResourceDescription> launchTemplates(String region) {
        List<ResourceDescription> resources = new ArrayList<>();
        for (LaunchTemplate lt : ec2Service.describeLaunchTemplates(region, List.of(), List.of(), Map.of())) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("LaunchTemplateId", lt.getLaunchTemplateId());
            putIfPresent(properties, "LaunchTemplateName", lt.getLaunchTemplateName());
            putIfPresent(properties, "LatestVersionNumber", lt.getLatestVersionNumber());
            putIfPresent(properties, "DefaultVersionNumber", lt.getDefaultVersionNumber());
            resources.add(new ResourceDescription(lt.getLaunchTemplateId(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> instanceProfiles() {
        List<ResourceDescription> resources = new ArrayList<>();
        for (InstanceProfile profile : iamService.listInstanceProfiles("/")) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("InstanceProfileName", profile.getInstanceProfileName());
            putIfPresent(properties, "Arn", profile.getArn());
            putIfPresent(properties, "Path", profile.getPath());
            resources.add(new ResourceDescription(profile.getInstanceProfileName(), propertiesString(properties)));
        }
        return resources;
    }

    /** Cloud Control omits a property it has no value for rather than reporting a null. */
    private static void putIfPresent(ObjectNode node, String name, String value) {
        if (value != null && !value.isBlank()) node.put(name, value);
    }

    private List<ResourceDescription> s3Buckets() {
        List<ResourceDescription> resources = new ArrayList<>();
        for (Bucket bucket : s3Service.listBuckets()) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("BucketName", bucket.getName());
            resources.add(new ResourceDescription(bucket.getName(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> vpcs(String region) {
        List<ResourceDescription> resources = new ArrayList<>();
        for (Vpc vpc : ec2Service.describeVpcs(region, List.of(), Map.of())) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("VpcId", vpc.getVpcId());
            properties.put("CidrBlock", vpc.getCidrBlock());
            properties.put("InstanceTenancy", vpc.getInstanceTenancy());
            addTags(properties, vpc.getTags());
            resources.add(new ResourceDescription(vpc.getVpcId(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> subnets(String region) {
        List<ResourceDescription> resources = new ArrayList<>();
        for (Subnet subnet : ec2Service.describeSubnets(region, List.of(), Map.of())) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("SubnetId", subnet.getSubnetId());
            properties.put("VpcId", subnet.getVpcId());
            properties.put("CidrBlock", subnet.getCidrBlock());
            properties.put("AvailabilityZone", subnet.getAvailabilityZone());
            addTags(properties, subnet.getTags());
            resources.add(new ResourceDescription(subnet.getSubnetId(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> securityGroups(String region) {
        List<ResourceDescription> resources = new ArrayList<>();
        for (SecurityGroup group : ec2Service.describeSecurityGroups(region, List.of(), List.of(), Map.of())) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("GroupId", group.getGroupId());
            properties.put("GroupName", group.getGroupName());
            properties.put("GroupDescription", group.getDescription());
            properties.put("VpcId", group.getVpcId());
            addTags(properties, group.getTags());
            resources.add(new ResourceDescription(group.getGroupId(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> roles() {
        List<ResourceDescription> resources = new ArrayList<>();
        for (IamRole role : iamService.listRoles("/")) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("Arn", role.getArn());
            properties.put("RoleName", role.getRoleName());
            properties.put("Path", role.getPath());
            resources.add(new ResourceDescription(role.getRoleName(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> users() {
        List<ResourceDescription> resources = new ArrayList<>();
        for (IamUser user : iamService.listUsers("/")) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("Arn", user.getArn());
            properties.put("UserName", user.getUserName());
            properties.put("Path", user.getPath());
            resources.add(new ResourceDescription(user.getUserName(), propertiesString(properties)));
        }
        return resources;
    }

    private String propertiesString(ObjectNode properties) {
        try {
            return mapper.writeValueAsString(properties);
        } catch (JsonProcessingException e) {
            throw new AwsException("InternalFailure",
                    "Failed to serialize CloudControl resource properties.", 500);
        }
    }

    private void addTags(ObjectNode properties, List<Tag> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        List<Tag> validTags = tags.stream()
                .filter(tag -> tag != null && tag.getKey() != null && !tag.getKey().isBlank())
                .toList();
        if (validTags.isEmpty()) {
            return;
        }
        ArrayNode tagArray = properties.putArray("Tags");
        for (Tag tag : validTags) {
            tagArray.addObject()
                    .put("Key", tag.getKey())
                    .put("Value", tag.getValue() == null ? "" : tag.getValue());
        }
    }

    public record ResourceDescription(String identifier, String properties) {}
}
