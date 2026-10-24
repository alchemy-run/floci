package io.github.hectorvent.floci.services.amazonmq;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
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
import io.github.hectorvent.floci.services.amazonmq.container.ActiveMqBrokerConfig;
import io.github.hectorvent.floci.services.amazonmq.container.ActiveMqManager;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@ApplicationScoped
public class AmazonMqService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(AmazonMqService.class);
    private static final String ENGINE_RABBITMQ = "RABBITMQ";
    private static final String DEFAULT_ENGINE_VERSION = "3.13";
    private static final String DEPLOYMENT_SINGLE_INSTANCE = "SINGLE_INSTANCE";

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
        String brokerArn = AwsArnUtils.Arn.of("mq", regionResolver.getRegion(), accountId,
                "broker:" + name + ":" + brokerId).toString();
        String engineVersion = (params.engineVersion() == null || params.engineVersion().isBlank())
                ? DEFAULT_ENGINE_VERSION : params.engineVersion();

        Broker broker = new Broker(brokerId, brokerArn, name, ENGINE_RABBITMQ,
                engineVersion, deploymentMode, params.hostInstanceType());
        broker.setAccountId(accountId);
        broker.setVolumeId(String.format("%06x", new SecureRandom().nextInt(0xFFFFFF)));
        // Stamp the volume name now, with the current prefix, so it is persisted rather than
        // recomputed later. Only records predating this field fall back to the legacy name.
        broker.setDockerVolumeName(ContainerStorageHelper.resourceName(
                config, "amazonmq", broker.getVolumeId(), broker.getBrokerId()));
        broker.setPubliclyAccessible(params.publiclyAccessible());
        broker.setAutoMinorVersionUpgrade(params.autoMinorVersionUpgrade());
        if (params.users() != null) {
            broker.setUsers(new ArrayList<>(params.users()));
        }
        if (params.tags() != null) {
            broker.setTags(new HashMap<>(params.tags()));
        }
        broker.setSecurityGroups(params.securityGroups());
        broker.setSubnetIds(params.subnetIds());
        broker.setLogs(params.logs());
        broker.setMaintenanceWindowStartTime(params.maintenanceWindowStartTime());
        broker.setStorageType(params.storageType());
        broker.setAuthenticationStrategy(params.authenticationStrategy());
        broker.setEncryptionOptions(params.encryptionOptions());
        broker.setConfiguration(params.configuration());

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
        // does not cycle the container, so the broker simply stays RUNNING.
        return broker;
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
        throw userApiNotSupported();
    }

    public MqUser describeUser(String brokerId, String username) {
        throw userApiNotSupported();
    }

    public List<MqUser> listUsers(String brokerId) {
        throw userApiNotSupported();
    }

    public void deleteUser(String brokerId, String username) {
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

    static final String ENGINE_ACTIVEMQ = "ACTIVEMQ";

    private static final String DEFAULT_ACTIVEMQ_ENGINE_VERSION = "5.18";

    static final List<String> RABBITMQ_ENGINE_VERSIONS = List.of("3.13");

    static final List<String> ACTIVEMQ_ENGINE_VERSIONS = List.of("5.18", "5.17.6", "5.16.7", "5.15.16");

    private static final int MAX_ACTIVEMQ_USERS = 250;

    private static final int MAX_USER_GROUPS = 20;

    private static final Pattern USERNAME_PATTERN = Pattern.compile("[a-zA-Z0-9_~.-]{2,100}");

    private static final Pattern GROUP_PATTERN = Pattern.compile("[a-zA-Z0-9_~.-]{2,100}");

    private final ActiveMqManager activeMqManager;

    private final AmazonMqConfigurationService configurations;

    private final Set<String> rebootsInFlight = ConcurrentHashMap.newKeySet();

    @Inject
    public AmazonMqService(StorageFactory storageFactory, EmulatorConfig config,
                           RegionResolver regionResolver, RabbitMqManager rabbitMqManager,
                           ActiveMqManager activeMqManager, AmazonMqConfigurationService configurations) {
        this.storage = storageFactory.create("amazonmq", "amazonmq-brokers.json",
                new TypeReference<Map<String, Broker>>() {});
        this.config = config;
        this.regionResolver = regionResolver;
        this.rabbitMqManager = rabbitMqManager;
        this.activeMqManager = activeMqManager;
        this.configurations = configurations;
    }

    private static String canonicalEngine(String engineType) {
        String upper = engineType == null ? "" : engineType.toUpperCase(Locale.ROOT);
        if (!ENGINE_RABBITMQ.equals(upper) && !ENGINE_ACTIVEMQ.equals(upper)) {
            throw new AwsException("BadRequestException",
                    "Broker engine type [" + engineType + "] is not supported. Valid values: [ACTIVEMQ, RABBITMQ].",
                    400);
        }
        return upper;
    }

    private static String resolveEngineVersion(String engine, String requested) {
        if (ENGINE_RABBITMQ.equals(engine)) {
            return requested == null || requested.isBlank() ? DEFAULT_ENGINE_VERSION : requested;
        }
        if (requested == null || requested.isBlank()) {
            return DEFAULT_ACTIVEMQ_ENGINE_VERSION;
        }
        if (!ACTIVEMQ_ENGINE_VERSIONS.contains(requested)) {
            throw new AwsException("BadRequestException",
                    "Broker engine version [" + requested + "] is not supported for ActiveMQ", 400);
        }
        return requested;
    }

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

    private void restartActiveMq(Broker broker, List<ActiveMqBrokerConfig.Credential> credentials,
                                 String configurationDocument) {
        try {
            activeMqManager.startContainer(broker, credentials, configurationDocument);
        } catch (RuntimeException e) {
            LOG.errorv(e, "Failed to restart ActiveMQ broker {0} ({1})", broker.getBrokerName(), broker.getBrokerId());
            broker.setBrokerState(BrokerState.CRITICAL_ACTION_REQUIRED);
        }
        if (!rebootsInFlight.remove(broker.getBrokerId())) {
            // DeleteBroker ran while the container was being replaced; do not leave the new one behind.
            activeMqManager.stopContainer(broker);
            return;
        }
        putBroker(broker);
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

    /** The broker's users once a reboot applies every staged CREATE, UPDATE and DELETE. */
    private static List<MqUser> usersAfterReboot(List<MqUser> users) {
        List<MqUser> applied = new ArrayList<>();
        for (MqUser user : users) {
            if (MqUser.CHANGE_DELETE.equals(user.getPendingChange())) {
                continue;
            }
            MqUser next = new MqUser(user.getUsername(), user.getPassword(), user.isConsoleAccess(), user.getGroups());
            if (user.getPendingChange() != null) {
                if (user.getPendingConsoleAccess() != null) {
                    next.setConsoleAccess(user.getPendingConsoleAccess());
                }
                if (user.getPendingGroups() != null) {
                    next.setGroups(user.getPendingGroups());
                }
                if (user.getPendingPassword() != null) {
                    next.setPassword(user.getPendingPassword());
                }
            }
            applied.add(next);
        }
        return applied;
    }

    /** The users a broker authenticates: every user except those only staged for creation. */
    private static List<ActiveMqBrokerConfig.Credential> credentials(List<MqUser> users) {
        List<ActiveMqBrokerConfig.Credential> credentials = new ArrayList<>();
        for (MqUser user : users) {
            if (MqUser.CHANGE_CREATE.equals(user.getPendingChange())) {
                continue;
            }
            if (user.getPassword() == null) {
                // Passwords are secrets kept in memory only, so a broker reloaded from persistent
                // storage cannot be restarted with its users. Fail before touching the broker.
                throw new AwsException("InternalServerErrorException",
                        "The password of broker user [" + user.getUsername() + "] is unavailable because "
                                + "broker user passwords are not persisted across emulator restarts", 500);
            }
            credentials.add(new ActiveMqBrokerConfig.Credential(user.getUsername(), user.getPassword(),
                    user.isConsoleAccess(), user.getGroups()));
        }
        return credentials;
    }

    private String configurationDocument(Broker broker) {
        if (broker.getConfigurationId() == null) {
            return null;
        }
        return configurations.activeMqDocument(broker.getConfigurationId(), broker.getConfigurationRevision());
    }

    /**
     * UpdateBroker. {@code autoMinorVersionUpgrade}, the maintenance window, logs and security
     * groups apply immediately; engine version, instance type, authentication strategy and
     * configuration become pending changes that the next RebootBroker applies.
     */
    public Broker updateBroker(String brokerId, BrokerUpdate update) {
        Broker broker = describeBroker(brokerId);
        boolean activeMq = ENGINE_ACTIVEMQ.equals(broker.getEngineType());
        String engineName = activeMq ? "ActiveMQ" : "RabbitMQ";
        if (update.dataReplicationMode() != null && !"NONE".equals(update.dataReplicationMode())) {
            throw new AwsException("BadRequestException",
                    "Data replication mode " + update.dataReplicationMode()
                            + " is not supported for " + (activeMq ? "SINGLE_INSTANCE ActiveMQ" : "RabbitMQ")
                            + " brokers", 400);
        }
        List<String> supportedVersions = activeMq ? ACTIVEMQ_ENGINE_VERSIONS : RABBITMQ_ENGINE_VERSIONS;
        if (update.engineVersion() != null && !supportedVersions.contains(update.engineVersion())) {
            throw new AwsException("BadRequestException",
                    "Broker engine version [" + update.engineVersion() + "] is not supported for " + engineName, 400);
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
        if (!activeMq && update.logs() != null && Boolean.TRUE.equals(update.logs().get("audit"))) {
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
            broker.setPendingConfigurationRevision(
                    configurations.resolveRevision(update.configurationId(), update.configurationRevision()));
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
     * pair. No broker hosted here belongs to one, so an existing broker always rejects it.
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

    void pollReadiness(Broker broker) {
        BrokerState state = broker.getBrokerState();
        if (state != BrokerState.CREATION_IN_PROGRESS && state != BrokerState.REBOOT_IN_PROGRESS) {
            return;
        }
        if (rebootsInFlight.contains(broker.getBrokerId())) {
            return;
        }
        if (ENGINE_ACTIVEMQ.equals(broker.getEngineType())) {
            if (activeMqManager.isReady(broker)) {
                LOG.infov("Amazon MQ broker {0} is now RUNNING", broker.getBrokerName());
                broker.setBrokerState(BrokerState.RUNNING);
                putBroker(broker);
            } else if (activeMqManager.hasExited(broker)) {
                LOG.warnv("ActiveMQ container for broker {0} exited before the broker became ready",
                        broker.getBrokerName());
                broker.setBrokerState(state == BrokerState.CREATION_IN_PROGRESS
                        ? BrokerState.CREATION_FAILED : BrokerState.CRITICAL_ACTION_REQUIRED);
                putBroker(broker);
            }
        } else if (state == BrokerState.CREATION_IN_PROGRESS && rabbitMqManager.isReady(broker)) {
            LOG.infov("Amazon MQ broker {0} is now RUNNING", broker.getBrokerName());
            broker.setBrokerState(BrokerState.RUNNING);
            putBroker(broker);
        }
    }

    /** UpdateUser; {@code null} members leave the user's value unchanged. */
    public void updateUser(String brokerId, String username, String password, Boolean consoleAccess,
                           List<String> groups) {
        Broker broker = activeMqBroker(brokerId);
        MqUser user = findUser(broker, username).orElseThrow(() -> userNotFound(brokerId, username));
        if (password != null) {
            validateUserPassword(password);
        }
        validateGroups(groups);
        if (!MqUser.CHANGE_CREATE.equals(user.getPendingChange())) {
            if (user.getPendingChange() == null || MqUser.CHANGE_DELETE.equals(user.getPendingChange())) {
                user.setPendingConsoleAccess(user.isConsoleAccess());
                user.setPendingGroups(user.getGroups() == null ? List.of() : new ArrayList<>(user.getGroups()));
            }
            user.setPendingChange(MqUser.CHANGE_UPDATE);
        }
        if (consoleAccess != null) {
            user.setPendingConsoleAccess(consoleAccess);
        }
        if (groups != null) {
            user.setPendingGroups(new ArrayList<>(groups));
        }
        if (password != null) {
            user.setPendingPassword(password);
        }
        putBroker(broker);
    }

    private Broker activeMqBroker(String brokerId) {
        Broker broker = describeBroker(brokerId);
        if (!ENGINE_ACTIVEMQ.equals(broker.getEngineType())) {
            throw userApiNotSupported();
        }
        return broker;
    }

    private static Optional<MqUser> findUser(Broker broker, String username) {
        return broker.getUsers().stream().filter(u -> u.getUsername().equals(username)).findFirst();
    }

    private static AwsException userNotFound(String brokerId, String username) {
        return new AwsException("NotFoundException",
                "Can't find requested user [" + username + "] on broker [" + brokerId + "].", 404);
    }

    /**
     * Amazon MQ's broker-user name rule: 2-100 characters, only alphanumerics, dashes,
     * periods, underscores and tildes.
     */
    private static void validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new AwsException("BadRequestException", "Broker user username is required", 400);
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new AwsException("BadRequestException",
                    "Broker user username [" + username + "] must be 2-100 characters long and contain only "
                            + "alphanumeric characters, dashes, periods, underscores, and tildes (- . _ ~)", 400);
        }
    }

    private static void validateGroups(List<String> groups) {
        if (groups == null) {
            return;
        }
        if (groups.size() > MAX_USER_GROUPS) {
            throw new AwsException("BadRequestException",
                    "A broker user can belong to at most " + MAX_USER_GROUPS + " groups", 400);
        }
        for (String group : groups) {
            if (group == null || !GROUP_PATTERN.matcher(group).matches()) {
                throw new AwsException("BadRequestException",
                        "Broker user group [" + group + "] must be 2-100 characters long and contain only "
                                + "alphanumeric characters, dashes, periods, underscores, and tildes (- . _ ~)", 400);
            }
        }
    }
}
