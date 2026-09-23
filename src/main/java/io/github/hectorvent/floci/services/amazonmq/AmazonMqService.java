package io.github.hectorvent.floci.services.amazonmq;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.amazonmq.container.RabbitMqManager;
import io.github.hectorvent.floci.services.amazonmq.model.Broker;
import io.github.hectorvent.floci.services.amazonmq.model.BrokerInstance;
import io.github.hectorvent.floci.services.amazonmq.model.BrokerState;
import io.github.hectorvent.floci.services.amazonmq.model.MqUser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class AmazonMqService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(AmazonMqService.class);
    private static final String ENGINE_RABBITMQ = "RABBITMQ";
    private static final String DEFAULT_ENGINE_VERSION = "3.13";
    private static final String DEPLOYMENT_SINGLE_INSTANCE = "SINGLE_INSTANCE";
    static final List<String> RABBITMQ_ENGINE_VERSIONS = List.of("3.13");

    private final StorageBackend<String, Broker> storage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final RabbitMqManager rabbitMqManager;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    @Inject
    public AmazonMqService(StorageFactory storageFactory, EmulatorConfig config,
                           RegionResolver regionResolver, RabbitMqManager rabbitMqManager) {
        this.storage = storageFactory.create("amazonmq", "amazonmq-brokers.json",
                new TypeReference<Map<String, Broker>>() {});
        this.config = config;
        this.regionResolver = regionResolver;
        this.rabbitMqManager = rabbitMqManager;
    }

    @PostConstruct
    public void init() {
        startReadinessPoller();
    }

    @PreDestroy
    public void shutdown() {
        // Container teardown is wired into EmulatorLifecycle.onStop() via
        // RabbitMqManager.stopAll() (ordered with the other container managers);
        // here we only stop the readiness poller.
        poller.shutdown();
    }

    public Broker createBroker(CreateBrokerParams params) {
        String name = params.brokerName();
        if (name == null || name.isBlank()) {
            throw new AwsException("BadRequestException", "BrokerName is required", 400);
        }
        // EngineType is case-insensitive on real AWS, and the mixed-case spelling is the
        // one the AWS docs, the console and the aws_mq_broker registry examples all use --
        // so an exact match rejects the form virtually every Terraform module is written
        // with. Distinguish the unsupported engine from an unrecognised one so the two
        // failures are not reported identically.
        if (!ENGINE_RABBITMQ.equalsIgnoreCase(params.engineType())) {
            throw new AwsException("BadRequestException",
                    "EngineType " + params.engineType() + " is not supported; only RabbitMQ is emulated",
                    400);
        }
        String deploymentMode = params.deploymentMode() == null
                ? DEPLOYMENT_SINGLE_INSTANCE : params.deploymentMode();
        // DeploymentMode has the same problem as EngineType above: the wire enum is upper case,
        // but callers spell it however their tooling does, and the real API matches without
        // regard to case. An exact compare rejected "single_instance" -- the one mode this
        // emulator does support -- as unsupported.
        if (!DEPLOYMENT_SINGLE_INSTANCE.equalsIgnoreCase(deploymentMode)) {
            throw new AwsException("BadRequestException",
                    "Only SINGLE_INSTANCE DeploymentMode is supported", 400);
        }
        // Store the canonical wire casing, not the caller's, so DescribeBroker reads back the
        // enum value the SDKs expect however the request happened to be spelled.
        deploymentMode = DEPLOYMENT_SINGLE_INSTANCE;
        // RabbitMQ brokers require exactly one user at creation; that user becomes the
        // broker's RabbitMQ administrator (seeded into the container). This mirrors AWS,
        // which rejects CreateBroker for RabbitMQ unless exactly one user is supplied.
        List<MqUser> requestedUsers = params.users() == null ? List.of() : params.users();
        if (requestedUsers.size() != 1) {
            throw new AwsException("BadRequestException",
                    "Exactly one broker user is required for a RabbitMQ broker", 400);
        }
        MqUser admin = requestedUsers.get(0);
        if (admin.getUsername() == null || admin.getUsername().isBlank()) {
            throw new AwsException("BadRequestException", "Broker user username is required", 400);
        }
        validateUserPassword(admin.getPassword());

        if (storage.scan(k -> true).stream().anyMatch(b -> name.equals(b.getBrokerName()))) {
            throw new AwsException("ConflictException", "Broker already exists: " + name, 409);
        }

        String brokerId = "b-" + UUID.randomUUID();
        String accountId = regionResolver.getAccountId();
        String brokerArn = AwsArnUtils.Arn.of("mq", config.defaultRegion(), accountId,
                "broker:" + name + ":" + brokerId).toString();
        String engineVersion = (params.engineVersion() == null || params.engineVersion().isBlank())
                ? DEFAULT_ENGINE_VERSION : params.engineVersion();

        Broker broker = new Broker(brokerId, brokerArn, name, ENGINE_RABBITMQ,
                engineVersion, deploymentMode, params.hostInstanceType());
        broker.setAccountId(accountId);
        broker.setVolumeId(String.format("%06x", new SecureRandom().nextInt(0xFFFFFF)));
        broker.setPubliclyAccessible(params.publiclyAccessible());
        broker.setAutoMinorVersionUpgrade(params.autoMinorVersionUpgrade());
        if (params.users() != null) {
            broker.setUsers(new ArrayList<>(params.users()));
        }
        if (params.tags() != null) {
            broker.setTags(new HashMap<>(params.tags()));
        }

        if (config.services().amazonmq().mock()) {
            // No backing container: come up immediately with synthetic endpoints.
            applyLocalEndpoints(broker);
            broker.setBrokerState(BrokerState.RUNNING);
        } else {
            try {
                // Start the container; the broker stays CREATION_IN_PROGRESS until
                // the readiness poller observes the management API answering.
                rabbitMqManager.startContainer(broker);
            } catch (RuntimeException e) {
                broker.setBrokerState(BrokerState.CREATION_FAILED);
                storage.put(brokerId, broker);
                // Keep the cause in the logs; don't leak internal details (or a null
                // message) into the AWS error envelope returned to the client.
                LOG.errorv(e, "Failed to provision broker {0} ({1})", name, brokerId);
                throw new AwsException("InternalServerErrorException",
                        "Failed to provision broker " + name, 500);
            }
        }

        storage.put(brokerId, broker);
        LOG.infov("Created Amazon MQ broker {0} ({1})", name, brokerId);
        return broker;
    }

    public Broker describeBroker(String brokerId) {
        return storage.get(brokerId)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Broker not found: " + brokerId, 404));
    }

    public List<Broker> listBrokers() {
        return storage.scan(k -> true);
    }

    // --- Tags (broker ARNs; CreateTags/DeleteTags/ListTags on /v1/tags/{arn}) ---

    public Map<String, String> listBrokerTags(String brokerArn) {
        Map<String, String> tags = brokerByArn(brokerArn).getTags();
        return tags != null ? new HashMap<>(tags) : new HashMap<>();
    }

    public void tagBroker(String brokerArn, Map<String, String> tags) {
        Broker broker = brokerByArn(brokerArn);
        Map<String, String> merged = broker.getTags() != null ? new HashMap<>(broker.getTags()) : new HashMap<>();
        if (tags != null) {
            merged.putAll(tags);
        }
        broker.setTags(merged);
        storage.put(broker.getBrokerId(), broker);
    }

    public void untagBroker(String brokerArn, List<String> tagKeys) {
        Broker broker = brokerByArn(brokerArn);
        if (broker.getTags() != null && tagKeys != null) {
            Map<String, String> remaining = new HashMap<>(broker.getTags());
            tagKeys.forEach(remaining::remove);
            broker.setTags(remaining);
        }
        storage.put(broker.getBrokerId(), broker);
    }

    private Broker brokerByArn(String brokerArn) {
        return storage.scan(k -> true).stream()
                .filter(b -> brokerArn.equals(b.getBrokerArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Can't find requested resource [" + brokerArn + "].", 404));
    }

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Broker broker : storage.scan(k -> true)) {
            String arn = broker.getBrokerArn();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(
                    arn, "mq:broker", "mq",
                    parsed.region(), parsed.accountId(),
                    broker.getCreated() != null ? broker.getCreated() : Instant.now(),
                    broker.getTags() != null ? broker.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("mq:broker", "mq", true));
    }

    public void deleteBroker(String brokerId) {
        Broker broker = describeBroker(brokerId);
        broker.setBrokerState(BrokerState.DELETION_IN_PROGRESS);
        if (!config.services().amazonmq().mock()) {
            rabbitMqManager.stopContainer(broker);
            rabbitMqManager.removeBrokerStorage(broker);
        }
        storage.delete(brokerId);
        LOG.infov("Deleted Amazon MQ broker {0}", brokerId);
    }

    public Broker rebootBroker(String brokerId) {
        Broker broker = describeBroker(brokerId);
        // AWS allows RebootBroker only on a broker in the RUNNING state. Without
        // this guard a non-RUNNING broker (e.g. CREATION_FAILED, which has no
        // backing container) would be silently promoted to RUNNING and never
        // reconciled by the readiness poller.
        if (broker.getBrokerState() != BrokerState.RUNNING) {
            throw new AwsException("BadRequestException",
                    "Broker " + brokerId + " cannot be rebooted while in state "
                            + broker.getBrokerState() + "; it must be RUNNING", 400);
        }
        // RebootBroker is asynchronous and returns the broker to RUNNING. This tier
        // does not cycle the container, so the broker simply stays RUNNING. A reboot
        // applies the changes UpdateBroker left pending.
        if (applyPendingChanges(broker)) {
            putBroker(broker);
        }
        return broker;
    }

    private static boolean applyPendingChanges(Broker broker) {
        boolean changed = false;
        if (broker.getPendingEngineVersion() != null) {
            broker.setEngineVersion(broker.getPendingEngineVersion());
            broker.setPendingEngineVersion(null);
            changed = true;
        }
        if (broker.getPendingHostInstanceType() != null) {
            broker.setHostInstanceType(broker.getPendingHostInstanceType());
            broker.setPendingHostInstanceType(null);
            changed = true;
        }
        if (broker.getPendingAuthenticationStrategy() != null) {
            broker.setAuthenticationStrategy(broker.getPendingAuthenticationStrategy());
            broker.setPendingAuthenticationStrategy(null);
            changed = true;
        }
        if (broker.getPendingConfigurationId() != null) {
            broker.setConfigurationId(broker.getPendingConfigurationId());
            broker.setConfigurationRevision(broker.getPendingConfigurationRevision());
            broker.setPendingConfigurationId(null);
            broker.setPendingConfigurationRevision(null);
            changed = true;
        }
        return changed;
    }

    /**
     * UpdateBroker. {@code autoMinorVersionUpgrade}, the maintenance window, logs and security
     * groups apply immediately; engine version, instance type, authentication strategy and
     * configuration become pending changes that the next RebootBroker applies.
     */
    public Broker updateBroker(String brokerId, BrokerUpdate update) {
        Broker broker = describeBroker(brokerId);
        if (update.dataReplicationMode() != null && !"NONE".equals(update.dataReplicationMode())) {
            throw new AwsException("BadRequestException",
                    "Data replication mode " + update.dataReplicationMode()
                            + " is not supported for RabbitMQ brokers", 400);
        }
        if (update.engineVersion() != null && !RABBITMQ_ENGINE_VERSIONS.contains(update.engineVersion())) {
            throw new AwsException("BadRequestException",
                    "Broker engine version [" + update.engineVersion() + "] is not supported for RabbitMQ", 400);
        }
        if (update.hostInstanceType() != null && !update.hostInstanceType().matches("mq\\.[a-z0-9]+\\.[a-z0-9]+")) {
            throw new AwsException("BadRequestException",
                    "Broker instance type [" + update.hostInstanceType() + "] is not valid", 400);
        }
        if (update.authenticationStrategy() != null
                && !Set.of("SIMPLE", "LDAP", "CONFIG_MANAGED").contains(update.authenticationStrategy())) {
            throw new AwsException("BadRequestException",
                    "Authentication strategy [" + update.authenticationStrategy() + "] is not valid", 400);
        }
        if (update.logs() != null && Boolean.TRUE.equals(update.logs().get("audit"))) {
            throw new AwsException("BadRequestException", "Audit logs are not supported for RabbitMQ brokers", 400);
        }
        if (update.autoMinorVersionUpgrade() != null) {
            broker.setAutoMinorVersionUpgrade(update.autoMinorVersionUpgrade());
        }
        if (update.engineVersion() != null && !update.engineVersion().equals(broker.getEngineVersion())) {
            broker.setPendingEngineVersion(update.engineVersion());
        }
        if (update.hostInstanceType() != null && !update.hostInstanceType().equals(broker.getHostInstanceType())) {
            broker.setPendingHostInstanceType(update.hostInstanceType());
        }
        if (update.authenticationStrategy() != null
                && !update.authenticationStrategy().equals(effectiveAuthenticationStrategy(broker))) {
            broker.setPendingAuthenticationStrategy(update.authenticationStrategy());
        }
        if (update.configurationId() != null) {
            broker.setPendingConfigurationId(update.configurationId());
            broker.setPendingConfigurationRevision(update.configurationRevision());
        }
        if (update.maintenanceWindowStartTime() != null) {
            broker.setMaintenanceWindowStartTime(new HashMap<>(update.maintenanceWindowStartTime()));
        }
        if (update.logs() != null) {
            broker.setLogs(new HashMap<>(update.logs()));
        }
        if (update.securityGroups() != null) {
            broker.setSecurityGroups(new ArrayList<>(update.securityGroups()));
        }
        putBroker(broker);
        return broker;
    }

    static String effectiveAuthenticationStrategy(Broker broker) {
        return broker.getAuthenticationStrategy() != null ? broker.getAuthenticationStrategy() : "SIMPLE";
    }

    /** Parsed UpdateBroker request; {@code null} members leave the broker's value unchanged. */
    public record BrokerUpdate(Boolean autoMinorVersionUpgrade, String engineVersion, String hostInstanceType,
                               String authenticationStrategy, String configurationId, Integer configurationRevision,
                               Map<String, Object> maintenanceWindowStartTime, Map<String, Object> logs,
                               List<String> securityGroups, String dataReplicationMode) {}

    /**
     * Promote applies only to the replica broker of a cross-Region data replication (CRDR)
     * pair. RabbitMQ brokers never belong to one, so an existing broker always rejects it.
     */
    public void promote(String brokerId, String mode) {
        if (mode == null || mode.isBlank()) {
            throw new AwsException("BadRequestException", "The request must include the mode parameter.", 400);
        }
        if (!"SWITCHOVER".equals(mode) && !"FAILOVER".equals(mode)) {
            throw new AwsException("BadRequestException",
                    "The mode [" + mode + "] is not valid. Valid values: SWITCHOVER, FAILOVER.", 400);
        }
        Broker broker = describeBroker(brokerId);
        throw new AwsException("BadRequestException",
                "Broker [" + broker.getBrokerId() + "] is not a replica broker in a data replication pair "
                        + "(DataReplicationMode is NONE).", 400);
    }

    private void applyLocalEndpoints(Broker broker) {
        BrokerInstance instance = new BrokerInstance(
                "http://localhost:15672",
                List.of("amqp://localhost:5672"),
                "localhost");
        broker.setBrokerInstances(new ArrayList<>(List.of(instance)));
    }

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                if (config.services().amazonmq().mock()) {
                    return;
                }
                for (Broker broker : allBrokers()) {
                    if (broker.getBrokerState() == BrokerState.CREATION_IN_PROGRESS
                            && rabbitMqManager.isReady(broker)) {
                        LOG.infov("Amazon MQ broker {0} is now RUNNING", broker.getBrokerName());
                        broker.setBrokerState(BrokerState.RUNNING);
                        putBroker(broker);
                    }
                }
            } catch (Exception e) {
                LOG.error("Error in Amazon MQ readiness poller", e);
            }
        }, 1, 2, TimeUnit.SECONDS);
    }

    private List<Broker> allBrokers() {
        if (storage instanceof AccountAwareStorageBackend<Broker> aware) {
            return aware.scanAllAccounts();
        }
        return storage.scan(k -> true);
    }

    private void putBroker(Broker broker) {
        if (broker.getAccountId() != null && storage instanceof AccountAwareStorageBackend<Broker> aware) {
            aware.putForAccount(broker.getAccountId(), broker.getBrokerId(), broker);
        } else {
            storage.put(broker.getBrokerId(), broker);
        }
    }

    // --- Users ---
    // Amazon MQ's standalone User API (CreateUser/DescribeUser/ListUsers/UpdateUser/
    // DeleteUser) applies only to ActiveMQ brokers. For RabbitMQ, AWS rejects these
    // operations and directs callers to the RabbitMQ web console. Every broker we host
    // is RabbitMQ, so they always reject. The broker's admin user is seeded once at
    // CreateBroker time; additional users are managed through the RabbitMQ console.

    public MqUser createUser(String brokerId, MqUser user) {
        describeBroker(brokerId);
        throw userApiNotSupported();
    }

    public MqUser describeUser(String brokerId, String username) {
        describeBroker(brokerId);
        throw userApiNotSupported();
    }

    public List<MqUser> listUsers(String brokerId) {
        describeBroker(brokerId);
        throw userApiNotSupported();
    }

    public void updateUser(String brokerId, String username) {
        describeBroker(brokerId);
        throw userApiNotSupported();
    }

    public void deleteUser(String brokerId, String username) {
        describeBroker(brokerId);
        throw userApiNotSupported();
    }

    private static AwsException userApiNotSupported() {
        return new AwsException("BadRequestException",
                "User management API operations do not apply to RabbitMQ brokers. "
                        + "Manage users through the RabbitMQ web console.", 400);
    }

    /**
     * Enforces Amazon MQ's broker-user password rule: at least 12 characters, at
     * least 4 unique characters, and no commas, colons, or equal signs.
     */
    private static void validateUserPassword(String password) {
        if (password == null || password.length() < 12) {
            throw new AwsException("BadRequestException",
                    "Broker user password must be at least 12 characters long", 400);
        }
        if (password.chars().distinct().count() < 4) {
            throw new AwsException("BadRequestException",
                    "Broker user password must contain at least 4 unique characters", 400);
        }
        if (password.contains(",") || password.contains(":") || password.contains("=")) {
            throw new AwsException("BadRequestException",
                    "Broker user password must not contain commas, colons, or equal signs", 400);
        }
    }
}
