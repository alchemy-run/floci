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
import io.github.hectorvent.floci.services.amazonmq.container.ActiveMqBrokerConfig;
import io.github.hectorvent.floci.services.amazonmq.container.ActiveMqManager;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@ApplicationScoped
public class AmazonMqService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(AmazonMqService.class);
    static final String ENGINE_RABBITMQ = "RABBITMQ";
    static final String ENGINE_ACTIVEMQ = "ACTIVEMQ";
    private static final String DEFAULT_ENGINE_VERSION = "3.13";
    private static final String DEFAULT_ACTIVEMQ_ENGINE_VERSION = "5.18";
    private static final String DEPLOYMENT_SINGLE_INSTANCE = "SINGLE_INSTANCE";
    static final List<String> RABBITMQ_ENGINE_VERSIONS = List.of("3.13");
    static final List<String> ACTIVEMQ_ENGINE_VERSIONS = List.of("5.18", "5.17.6", "5.16.7", "5.15.16");
    private static final int MAX_ACTIVEMQ_USERS = 250;
    private static final int MAX_USER_GROUPS = 20;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[a-zA-Z0-9_~.-]{2,100}");
    private static final Pattern GROUP_PATTERN = Pattern.compile("[a-zA-Z0-9_~.-]{2,100}");

    private final StorageBackend<String, Broker> storage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final RabbitMqManager rabbitMqManager;
    private final ActiveMqManager activeMqManager;
    private final AmazonMqConfigurationService configurations;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    // Brokers whose reboot is still replacing the container; the readiness poller leaves them alone.
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

    @PostConstruct
    public void init() {
        startReadinessPoller();
    }

    @PreDestroy
    public void shutdown() {
        // RabbitMQ containers are torn down from EmulatorLifecycle.onStop() and ActiveMQ
        // containers through ContainerTeardown; here we only stop the readiness poller.
        poller.shutdown();
    }

    public Broker createBroker(CreateBrokerParams params) {
        String name = params.brokerName();
        if (name == null || name.isBlank()) {
            throw new AwsException("BadRequestException", "BrokerName is required", 400);
        }
        // EngineType is case-insensitive on real AWS, and the mixed-case spelling is the one
        // the AWS docs, the console and the aws_mq_broker registry examples all use, so an
        // exact match would reject the form virtually every Terraform module is written with.
        String engine = canonicalEngine(params.engineType());
        String deploymentMode = params.deploymentMode() == null
                ? DEPLOYMENT_SINGLE_INSTANCE : params.deploymentMode();
        // DeploymentMode has the same casing problem as EngineType: the wire enum is upper case,
        // but callers spell it however their tooling does, and the real API matches without
        // regard to case.
        if (!DEPLOYMENT_SINGLE_INSTANCE.equalsIgnoreCase(deploymentMode)) {
            throw new AwsException("BadRequestException",
                    "Only SINGLE_INSTANCE DeploymentMode is supported", 400);
        }
        // Store the canonical wire casing, not the caller's, so DescribeBroker reads back the
        // enum value the SDKs expect however the request happened to be spelled.
        deploymentMode = DEPLOYMENT_SINGLE_INSTANCE;

        List<MqUser> requestedUsers = params.users() == null ? List.of() : params.users();
        if (ENGINE_RABBITMQ.equals(engine)) {
            // RabbitMQ brokers require exactly one user at creation; that user becomes the
            // broker's RabbitMQ administrator (seeded into the container).
            if (requestedUsers.size() != 1) {
                throw new AwsException("BadRequestException",
                        "Exactly one broker user is required for a RabbitMQ broker", 400);
            }
        } else if (requestedUsers.isEmpty() || requestedUsers.size() > MAX_ACTIVEMQ_USERS) {
            throw new AwsException("BadRequestException",
                    "An ActiveMQ broker requires between 1 and " + MAX_ACTIVEMQ_USERS + " users", 400);
        }
        Set<String> usernames = new HashSet<>();
        for (MqUser user : requestedUsers) {
            validateUsername(user.getUsername());
            validateUserPassword(user.getPassword());
            validateGroups(user.getGroups());
            if (!usernames.add(user.getUsername())) {
                throw new AwsException("BadRequestException",
                        "Duplicate broker user [" + user.getUsername() + "]", 400);
            }
        }

        String engineVersion = resolveEngineVersion(engine, params.engineVersion());

        if (storage.scan(k -> true).stream().anyMatch(b -> name.equals(b.getBrokerName()))) {
            throw new AwsException("ConflictException", "Broker already exists: " + name, 409);
        }

        String brokerId = "b-" + UUID.randomUUID();
        String accountId = regionResolver.getAccountId();
        String brokerArn = AwsArnUtils.Arn.of("mq", config.defaultRegion(), accountId,
                "broker:" + name + ":" + brokerId).toString();

        Broker broker = new Broker(brokerId, brokerArn, name, engine,
                engineVersion, deploymentMode, params.hostInstanceType());
        broker.setAccountId(accountId);
        broker.setVolumeId(String.format("%06x", new SecureRandom().nextInt(0xFFFFFF)));
        broker.setPubliclyAccessible(params.publiclyAccessible());
        broker.setAutoMinorVersionUpgrade(params.autoMinorVersionUpgrade());
        List<MqUser> users = new ArrayList<>();
        for (MqUser user : requestedUsers) {
            users.add(new MqUser(user.getUsername(), user.getPassword(), user.isConsoleAccess(),
                    user.getGroups() == null ? null : new ArrayList<>(user.getGroups())));
        }
        broker.setUsers(users);
        if (params.tags() != null) {
            broker.setTags(new HashMap<>(params.tags()));
        }
        if (params.authenticationStrategy() != null && !params.authenticationStrategy().isBlank()) {
            broker.setAuthenticationStrategy(params.authenticationStrategy().toUpperCase(Locale.ROOT));
        }
        if (params.configurationId() != null) {
            broker.setConfigurationId(params.configurationId());
            broker.setConfigurationRevision(
                    configurations.resolveRevision(params.configurationId(), params.configurationRevision()));
        }
        if (params.maintenanceWindowStartTime() != null) {
            broker.setMaintenanceWindowStartTime(new HashMap<>(params.maintenanceWindowStartTime()));
        }
        if (params.logs() != null) {
            broker.setLogs(new HashMap<>(params.logs()));
        }
        if (params.securityGroups() != null) {
            broker.setSecurityGroups(new ArrayList<>(params.securityGroups()));
        }

        if (config.services().amazonmq().mock()) {
            // No backing container: come up immediately with synthetic endpoints.
            applyLocalEndpoints(broker);
            broker.setBrokerState(BrokerState.RUNNING);
        } else {
            try {
                // Start the container; the broker stays CREATION_IN_PROGRESS until
                // the readiness poller observes the broker answering.
                if (ENGINE_ACTIVEMQ.equals(engine)) {
                    activeMqManager.startContainer(broker, credentials(broker.getUsers()),
                            configurationDocument(broker));
                } else {
                    rabbitMqManager.startContainer(broker);
                }
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
            if (ENGINE_ACTIVEMQ.equals(broker.getEngineType())) {
                activeMqManager.stopContainer(broker);
                activeMqManager.removeBrokerStorage(broker);
            } else {
                rabbitMqManager.stopContainer(broker);
                rabbitMqManager.removeBrokerStorage(broker);
            }
        }
        rebootsInFlight.remove(brokerId);
        storage.delete(brokerId);
        LOG.infov("Deleted Amazon MQ broker {0}", brokerId);
    }

    public Broker rebootBroker(String brokerId) {
        Broker broker = describeBroker(brokerId);
        // AWS allows RebootBroker only on a broker in the RUNNING state. Without this guard a
        // non-RUNNING broker (e.g. CREATION_FAILED, which has no backing container) would be
        // silently promoted to RUNNING and never reconciled by the readiness poller.
        if (broker.getBrokerState() != BrokerState.RUNNING) {
            throw new AwsException("BadRequestException",
                    "Broker " + brokerId + " cannot be rebooted while in state "
                            + broker.getBrokerState() + "; it must be RUNNING", 400);
        }
        if (!ENGINE_ACTIVEMQ.equals(broker.getEngineType())) {
            // The RabbitMQ tier does not cycle the container, so the broker simply stays
            // RUNNING. A reboot applies the changes UpdateBroker left pending.
            if (applyPendingChanges(broker)) {
                putBroker(broker);
            }
            return broker;
        }

        // A reboot applies the broker's pending changes and the users staged through the
        // User API, then restarts the broker on the resulting configuration.
        List<MqUser> usersAfterReboot = usersAfterReboot(broker.getUsers());
        boolean mock = config.services().amazonmq().mock();
        List<ActiveMqBrokerConfig.Credential> credentials = mock ? List.of() : credentials(usersAfterReboot);
        applyPendingChanges(broker);
        broker.setUsers(usersAfterReboot);
        if (mock) {
            putBroker(broker);
            return broker;
        }
        String configurationDocument = configurationDocument(broker);
        broker.setBrokerState(BrokerState.REBOOT_IN_PROGRESS);
        rebootsInFlight.add(brokerId);
        putBroker(broker);
        poller.execute(() -> restartActiveMq(broker, credentials, configurationDocument));
        return broker;
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

    private void applyLocalEndpoints(Broker broker) {
        BrokerInstance instance;
        if (ENGINE_ACTIVEMQ.equals(broker.getEngineType())) {
            instance = new BrokerInstance(
                    "http://localhost:" + ActiveMqManager.CONSOLE_PORT,
                    List.of("tcp://localhost:" + ActiveMqManager.OPENWIRE_PORT,
                            "amqp://localhost:" + ActiveMqManager.AMQP_PORT,
                            "stomp://localhost:" + ActiveMqManager.STOMP_PORT,
                            "mqtt://localhost:" + ActiveMqManager.MQTT_PORT,
                            "ws://localhost:" + ActiveMqManager.WS_PORT),
                    "localhost");
        } else {
            instance = new BrokerInstance(
                    "http://localhost:15672",
                    List.of("amqp://localhost:5672"),
                    "localhost");
        }
        broker.setBrokerInstances(new ArrayList<>(List.of(instance)));
    }

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                if (config.services().amazonmq().mock()) {
                    return;
                }
                for (Broker broker : allBrokers()) {
                    pollReadiness(broker);
                }
            } catch (Exception e) {
                LOG.error("Error in Amazon MQ readiness poller", e);
            }
        }, 1, 2, TimeUnit.SECONDS);
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
    // Amazon MQ's standalone User API (CreateUser/DescribeUser/ListUsers/UpdateUser/DeleteUser)
    // applies only to ActiveMQ brokers; for RabbitMQ AWS rejects these operations and directs
    // callers to the RabbitMQ web console. On ActiveMQ every change is staged as a pending
    // CREATE, UPDATE or DELETE that the next RebootBroker applies to the running broker.

    public MqUser createUser(String brokerId, MqUser user) {
        Broker broker = activeMqBroker(brokerId);
        validateUsername(user.getUsername());
        validateUserPassword(user.getPassword());
        validateGroups(user.getGroups());
        if (findUser(broker, user.getUsername()).isPresent()) {
            throw new AwsException("ConflictException",
                    "User [" + user.getUsername() + "] already exists on broker [" + brokerId + "].", 409);
        }
        if (broker.getUsers().size() >= MAX_ACTIVEMQ_USERS) {
            throw new AwsException("BadRequestException",
                    "A broker can have at most " + MAX_ACTIVEMQ_USERS + " users.", 400);
        }
        MqUser staged = new MqUser(user.getUsername(), null, false, null);
        staged.setPendingChange(MqUser.CHANGE_CREATE);
        staged.setPendingConsoleAccess(user.isConsoleAccess());
        staged.setPendingGroups(user.getGroups() == null ? List.of() : new ArrayList<>(user.getGroups()));
        staged.setPendingPassword(user.getPassword());
        List<MqUser> users = new ArrayList<>(broker.getUsers());
        users.add(staged);
        broker.setUsers(users);
        putBroker(broker);
        return staged;
    }

    public MqUser describeUser(String brokerId, String username) {
        Broker broker = activeMqBroker(brokerId);
        return findUser(broker, username).orElseThrow(() -> userNotFound(brokerId, username));
    }

    public List<MqUser> listUsers(String brokerId) {
        return new ArrayList<>(activeMqBroker(brokerId).getUsers());
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

    public void deleteUser(String brokerId, String username) {
        Broker broker = activeMqBroker(brokerId);
        MqUser user = findUser(broker, username).orElseThrow(() -> userNotFound(brokerId, username));
        if (MqUser.CHANGE_CREATE.equals(user.getPendingChange())) {
            // Never applied to the broker, so there is nothing left to stage.
            List<MqUser> users = new ArrayList<>(broker.getUsers());
            users.remove(user);
            broker.setUsers(users);
        } else {
            user.setPendingChange(MqUser.CHANGE_DELETE);
            user.setPendingConsoleAccess(null);
            user.setPendingGroups(null);
            user.setPendingPassword(null);
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

    private static AwsException userApiNotSupported() {
        return new AwsException("BadRequestException",
                "User management API operations do not apply to RabbitMQ brokers. "
                        + "Manage users through the RabbitMQ web console.", 400);
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
