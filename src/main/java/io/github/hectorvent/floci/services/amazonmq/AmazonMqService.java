package io.github.hectorvent.floci.services.amazonmq;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.amazonmq.container.RabbitMqManager;
import io.github.hectorvent.floci.services.amazonmq.model.Broker;
import io.github.hectorvent.floci.services.amazonmq.model.BrokerInstance;
import io.github.hectorvent.floci.services.amazonmq.model.BrokerState;
import io.github.hectorvent.floci.services.amazonmq.model.MqConfiguration;
import io.github.hectorvent.floci.services.amazonmq.model.MqConfigurationRevision;
import io.github.hectorvent.floci.services.amazonmq.model.MqUser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class AmazonMqService {

    static final String SERVICE = "mq";
    private static final Logger LOG = Logger.getLogger(AmazonMqService.class);
    private static final String ENGINE_RABBITMQ = "RABBITMQ";
    private static final String ENGINE_ACTIVEMQ = "ACTIVEMQ";
    private static final String DISPLAY_RABBITMQ = "RabbitMQ";
    private static final String DISPLAY_ACTIVEMQ = "ActiveMQ";
    private static final String DEFAULT_ENGINE_VERSION = "3.13";
    private static final String DEFAULT_ACTIVEMQ_VERSION = "5.18";
    private static final String DEPLOYMENT_SINGLE_INSTANCE = "SINGLE_INSTANCE";
    private static final String DEFAULT_AUTH_STRATEGY = "SIMPLE";
    // Distinct from the ActiveMQ XML Alchemy publishes on create so the first
    // UpdateConfiguration actually produces revision 2 (AWS seeds revision 1
    // with the engine default, then publishes custom data as revision 2).
    private static final String DEFAULT_ACTIVEMQ_DATA =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<broker xmlns=\"http://activemq.apache.org/schema/core\" persistent=\"true\">\n"
                    + "</broker>\n";
    private static final String DEFAULT_RABBITMQ_DATA = "# Default RabbitMQ configuration\n";

    private final StorageBackend<String, Broker> storage;
    private final StorageBackend<String, MqConfiguration> configurations;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final RabbitMqManager rabbitMqManager;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    @Inject
    public AmazonMqService(StorageFactory storageFactory, EmulatorConfig config,
                           RegionResolver regionResolver, RabbitMqManager rabbitMqManager) {
        this.storage = storageFactory.create("amazonmq", "amazonmq-brokers.json",
                new TypeReference<Map<String, Broker>>() {});
        this.configurations = storageFactory.create("amazonmq", "amazonmq-configurations.json",
                new TypeReference<Map<String, MqConfiguration>>() {});
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
        if (!ENGINE_RABBITMQ.equals(params.engineType())) {
            throw new AwsException("BadRequestException",
                    "Only RABBITMQ EngineType is supported", 400);
        }
        String deploymentMode = params.deploymentMode() == null
                ? DEPLOYMENT_SINGLE_INSTANCE : params.deploymentMode();
        if (!DEPLOYMENT_SINGLE_INSTANCE.equals(deploymentMode)) {
            throw new AwsException("BadRequestException",
                    "Only SINGLE_INSTANCE DeploymentMode is supported", 400);
        }
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
    // is RabbitMQ, so they always reject *after* the broker is shown to exist —
    // a missing broker is NotFoundException, matching live AWS. The broker's admin
    // user is seeded once at CreateBroker time; additional users are managed through
    // the RabbitMQ console.

    public MqUser createUser(String brokerId, MqUser user) {
        rejectUserApi(brokerId);
        return user;
    }

    public MqUser describeUser(String brokerId, String username) {
        rejectUserApi(brokerId);
        return null;
    }

    public List<MqUser> listUsers(String brokerId) {
        rejectUserApi(brokerId);
        return List.of();
    }

    public void deleteUser(String brokerId, String username) {
        rejectUserApi(brokerId);
    }

    /**
     * AWS looks the broker up first: a missing id is {@code NotFoundException}
     * (HTTP 404). Only an existing RabbitMQ broker is then rejected with
     * {@code BadRequestException}.
     */
    private void rejectUserApi(String brokerId) {
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

    // --- Configurations ---
    // CreateConfiguration seeds revision 1 with the engine default document.
    // UpdateConfiguration publishes a new immutable revision (custom data).

    public MqConfiguration createConfiguration(CreateConfigurationParams params) {
        String name = params.name();
        if (name == null || name.isBlank()) {
            throw new AwsException("BadRequestException", "Name is required", 400);
        }
        String engineType = displayEngineType(params.engineType());
        String engineVersion = params.engineVersion() == null || params.engineVersion().isBlank()
                ? defaultEngineVersion(engineType) : params.engineVersion();
        String auth = params.authenticationStrategy() == null || params.authenticationStrategy().isBlank()
                ? DEFAULT_AUTH_STRATEGY : params.authenticationStrategy();

        if (configurations.scan(k -> true).stream().anyMatch(c -> name.equals(c.getName()))) {
            throw new AwsException("ConflictException", "Configuration already exists: " + name, 409);
        }

        String configurationId = "c-" + UUID.randomUUID();
        String accountId = regionResolver.getAccountId();
        String arn = AwsArnUtils.Arn.of("mq", config.defaultRegion(), accountId,
                "configuration:" + name + ":" + configurationId).toString();

        MqConfiguration configuration = new MqConfiguration(
                configurationId, arn, name, engineType, engineVersion, auth);
        configuration.setAccountId(accountId);
        if (params.tags() != null) {
            configuration.setTags(new LinkedHashMap<>(params.tags()));
        }
        Instant now = Instant.now();
        configuration.getRevisions().add(new MqConfigurationRevision(
                1, now, "Amazon MQ default configuration", defaultData(engineType)));
        configurations.put(configurationId, configuration);
        LOG.infov("Created Amazon MQ configuration {0} ({1})", name, configurationId);
        return configuration;
    }

    public MqConfiguration describeConfiguration(String configurationId) {
        return configurations.get(configurationId)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Configuration not found: " + configurationId, 404));
    }

    public List<MqConfiguration> listConfigurations() {
        return configurations.scan(k -> true);
    }

    public MqConfiguration updateConfiguration(String configurationId, String data, String description) {
        MqConfiguration configuration = describeConfiguration(configurationId);
        MqConfigurationRevision latest = configuration.latestRevision();
        int next = latest == null ? 1 : latest.getRevision() + 1;
        String decoded = decodeConfigurationData(data);
        configuration.getRevisions().add(new MqConfigurationRevision(
                next, Instant.now(), description, decoded));
        putConfiguration(configuration);
        LOG.infov("Published Amazon MQ configuration {0} revision {1}", configurationId, next);
        return configuration;
    }

    public void deleteConfiguration(String configurationId) {
        describeConfiguration(configurationId);
        configurations.delete(configurationId);
        LOG.infov("Deleted Amazon MQ configuration {0}", configurationId);
    }

    public MqConfigurationRevision describeConfigurationRevision(String configurationId, String revision) {
        MqConfiguration configuration = describeConfiguration(configurationId);
        int number;
        try {
            number = Integer.parseInt(revision);
        } catch (NumberFormatException e) {
            throw new AwsException("NotFoundException",
                    "Configuration revision not found: " + revision, 404);
        }
        MqConfigurationRevision found = configuration.revision(number);
        if (found == null) {
            throw new AwsException("NotFoundException",
                    "Configuration revision not found: " + revision, 404);
        }
        return found;
    }

    public List<MqConfigurationRevision> listConfigurationRevisions(String configurationId) {
        return new ArrayList<>(describeConfiguration(configurationId).getRevisions());
    }

    public List<Map<String, Object>> describeBrokerEngineTypes(String engineType) {
        List<Map<String, Object>> types = new ArrayList<>();
        types.add(engineTypeEntry(ENGINE_ACTIVEMQ, List.of(DEFAULT_ACTIVEMQ_VERSION, "5.17.6")));
        types.add(engineTypeEntry(ENGINE_RABBITMQ, List.of(DEFAULT_ENGINE_VERSION, "3.12.14")));
        if (engineType == null || engineType.isBlank()) {
            return types;
        }
        String wanted = engineType.trim().toUpperCase(Locale.ROOT).replace("-", "");
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> entry : types) {
            Object raw = entry.get("engineType");
            if (raw != null && wanted.equals(raw.toString().toUpperCase(Locale.ROOT))) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    public Map<String, String> listTags(String resourceArn) {
        return new LinkedHashMap<>(taggedResource(resourceArn).tags());
    }

    public void createTags(String resourceArn, Map<String, String> tags) {
        TaggedResource resource = taggedResource(resourceArn);
        if (tags != null) {
            resource.tags().putAll(tags);
            resource.persist();
        }
    }

    public void deleteTags(String resourceArn, List<String> tagKeys) {
        TaggedResource resource = taggedResource(resourceArn);
        if (tagKeys != null) {
            for (String key : tagKeys) {
                resource.tags().remove(key);
            }
            resource.persist();
        }
    }

    static String encodeConfigurationData(String raw) {
        if (raw == null) {
            return "";
        }
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static String decodeConfigurationData(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(encoded.strip()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return encoded;
        }
    }

    static String decodeArn(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private TaggedResource taggedResource(String resourceArn) {
        String arn = decodeArn(resourceArn);
        for (MqConfiguration configuration : configurations.scan(k -> true)) {
            if (arn.equals(configuration.getArn())) {
                return new TaggedResource(ensureConfigurationTags(configuration),
                        () -> putConfiguration(configuration));
            }
        }
        for (Broker broker : storage.scan(k -> true)) {
            if (arn.equals(broker.getBrokerArn())) {
                return new TaggedResource(ensureBrokerTags(broker), () -> putBroker(broker));
            }
        }
        throw new AwsException("NotFoundException", "Resource not found: " + arn, 404);
    }

    private Map<String, String> ensureBrokerTags(Broker broker) {
        if (broker.getTags() == null) {
            broker.setTags(new LinkedHashMap<>());
        }
        return broker.getTags();
    }

    private Map<String, String> ensureConfigurationTags(MqConfiguration configuration) {
        if (configuration.getTags() == null) {
            configuration.setTags(new LinkedHashMap<>());
        }
        return configuration.getTags();
    }

    private void putConfiguration(MqConfiguration configuration) {
        if (configuration.getAccountId() != null
                && configurations instanceof AccountAwareStorageBackend<MqConfiguration> aware) {
            aware.putForAccount(configuration.getAccountId(), configuration.getId(), configuration);
        } else {
            configurations.put(configuration.getId(), configuration);
        }
    }

    private static String displayEngineType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AwsException("BadRequestException", "EngineType is required", 400);
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace("-", "");
        if (ENGINE_ACTIVEMQ.equals(normalized)) {
            return DISPLAY_ACTIVEMQ;
        }
        if (ENGINE_RABBITMQ.equals(normalized)) {
            return DISPLAY_RABBITMQ;
        }
        throw new AwsException("BadRequestException", "Invalid EngineType: " + raw, 400);
    }

    private static String defaultEngineVersion(String displayEngineType) {
        return DISPLAY_ACTIVEMQ.equals(displayEngineType)
                ? DEFAULT_ACTIVEMQ_VERSION : DEFAULT_ENGINE_VERSION;
    }

    private static String defaultData(String displayEngineType) {
        return DISPLAY_RABBITMQ.equals(displayEngineType)
                ? DEFAULT_RABBITMQ_DATA : DEFAULT_ACTIVEMQ_DATA;
    }

    private static Map<String, Object> engineTypeEntry(String engineType, List<String> versions) {
        List<Map<String, Object>> engineVersions = new ArrayList<>();
        for (String version : versions) {
            engineVersions.add(Map.of("name", version));
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("engineType", engineType);
        entry.put("engineVersions", engineVersions);
        return entry;
    }

    private record TaggedResource(Map<String, String> tags, Runnable persist) {}
}
