package io.github.hectorvent.floci.services.amazonmq.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerPresence;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.amazonmq.model.Broker;
import io.github.hectorvent.floci.services.amazonmq.model.BrokerInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the ActiveMQ Classic container behind an Amazon MQ ActiveMQ broker.
 *
 * <p>Each broker gets its own container whose {@code activemq.xml} and web console realm are
 * rendered by {@link ActiveMqBrokerConfig} from the broker's users and configuration revision
 * and copied in before the container starts. Every wire protocol Amazon MQ exposes for
 * ActiveMQ (OpenWire, AMQP, STOMP, MQTT, WebSocket) and the web console are published on
 * host ports, for the same reason as {@link RabbitMqManager}: nothing inside Floci relays
 * broker traffic, so a client outside the Docker network can only reach the broker through
 * a published port. The container and its JVM heap are capped at the footprint of an
 * {@code mq.t3.micro} broker.
 */
@ApplicationScoped
public class ActiveMqManager implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(ActiveMqManager.class);

    public static final int OPENWIRE_PORT = 61616;
    public static final int AMQP_PORT = 5672;
    public static final int STOMP_PORT = 61613;
    public static final int MQTT_PORT = 1883;
    public static final int WS_PORT = 61614;
    public static final int CONSOLE_PORT = 8161;

    static final String DEFAULT_IMAGE = "apache/activemq-classic:5.18.7";
    static final String LEGACY_IMAGE = "apache/activemq-classic:5.17.6";
    static final int MEMORY_MB = 768;
    static final String HEAP_OPTIONS = "-Xms64m -Xmx384m";

    private static final String ACTIVEMQ_HOME = "/opt/apache-activemq";
    private static final String CONF_DIR = ACTIVEMQ_HOME + "/conf";
    private static final String DATA_DIR = ACTIVEMQ_HOME + "/data";

    /** Wire endpoints in the order Amazon MQ lists them for an ActiveMQ broker instance. */
    private static final Map<Integer, String> ENDPOINT_SCHEMES = endpointSchemes();

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Map<String, String> containerIds = new ConcurrentHashMap<>();
    private final Map<String, Closeable> logStreams = new ConcurrentHashMap<>();

    @Inject
    public ActiveMqManager(ContainerBuilder containerBuilder,
                           ContainerLifecycleManager lifecycleManager,
                           ContainerLogStreamer logStreamer,
                           ContainerDetector containerDetector,
                           EmulatorConfig config,
                           RegionResolver regionResolver) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.config = config;
        this.regionResolver = regionResolver;
    }

    private static Map<Integer, String> endpointSchemes() {
        Map<Integer, String> schemes = new LinkedHashMap<>();
        schemes.put(OPENWIRE_PORT, "tcp");
        schemes.put(AMQP_PORT, "amqp");
        schemes.put(STOMP_PORT, "stomp");
        schemes.put(MQTT_PORT, "mqtt");
        schemes.put(WS_PORT, "ws");
        return schemes;
    }

    /** Deterministic container name for a broker, stable across emulator restarts. */
    static String containerName(String brokerId) {
        return "floci-amazonmq-" + brokerId;
    }

    /**
     * The ActiveMQ Classic image for an Amazon MQ engine version. 5.18 runs on the latest
     * 5.18 patch release; the older Amazon MQ versions predate the official image and run on
     * the oldest one published, which speaks the same wire protocols.
     */
    public String imageFor(String engineVersion) {
        String override = config.services().amazonmq().activemqImage().orElse(null);
        if (override != null && !override.isBlank()) {
            return override;
        }
        if (engineVersion != null && engineVersion.startsWith("5.18")) {
            return DEFAULT_IMAGE;
        }
        if (engineVersion != null && (engineVersion.startsWith("5.17") || engineVersion.startsWith("5.16")
                || engineVersion.startsWith("5.15"))) {
            return LEGACY_IMAGE;
        }
        return DEFAULT_IMAGE;
    }

    /**
     * Starts (or, on reboot, replaces) the broker's container with the given users and
     * configuration revision, and records its endpoints on the broker. Readiness is observed
     * separately through {@link #isReady(Broker)}.
     */
    public void startContainer(Broker broker, List<ActiveMqBrokerConfig.Credential> credentials,
                               String configurationDocument) {
        String image = imageFor(broker.getEngineVersion());
        String containerName = containerName(broker.getBrokerId());
        LOG.infov("Starting ActiveMQ container for broker {0} using image {1}", broker.getBrokerName(), image);

        Map<String, String> files = new LinkedHashMap<>();
        files.put("activemq.xml", ActiveMqBrokerConfig.activemqXml(credentials, configurationDocument));
        files.put("jetty-realm.properties", ActiveMqBrokerConfig.jettyRealm(credentials));

        stopTracked(broker.getBrokerId());
        lifecycleManager.removeIfExists(containerName);

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation()
                .withMemoryMb(MEMORY_MB)
                // The image's own ACTIVEMQ_OPTS only binds the console to all interfaces and so
                // suppresses the start script's heap and JAAS defaults; set all of them here.
                .withEnv("ACTIVEMQ_OPTS_MEMORY", HEAP_OPTIONS)
                .withEnv("ACTIVEMQ_OPTS", HEAP_OPTIONS + " -Djetty.host=0.0.0.0"
                        + " -Djava.util.logging.config.file=logging.properties"
                        + " -Djava.security.auth.login.config=" + CONF_DIR + "/login.config")
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "amazonmq", broker.getBrokerId(), regionResolver.getAccountId(),
                        regionResolver.getDefaultRegion()));
        for (int port : ENDPOINT_SCHEMES.keySet()) {
            specBuilder.withDynamicPort(port);
        }
        specBuilder.withDynamicPort(CONSOLE_PORT);

        ContainerInfo info;
        try {
            if (ContainerStorageHelper.isNamedVolumeMode(config)) {
                ContainerStorageHelper.applyStorage(specBuilder, lifecycleManager, config,
                        "amazonmq", broker.getVolumeId(), broker.getBrokerId(), DATA_DIR);
            } else {
                String hostDataPath = ContainerStorageHelper.hostResourcePath(config, "amazonmq", broker.getBrokerId())
                        .toAbsolutePath().toString();
                if (!containerDetector.isRunningInContainer()) {
                    ContainerStorageHelper.ensureHostDir(hostDataPath);
                }
                specBuilder.withBind(hostDataPath, DATA_DIR);
            }
            ContainerSpec spec = specBuilder.build();
            String containerId = lifecycleManager.create(spec);
            copyConfiguration(containerId, files);
            info = lifecycleManager.startCreated(containerId, spec);
        } catch (RuntimeException e) {
            // Roll back a partially created container so a failed start leaves nothing behind.
            lifecycleManager.removeIfExists(containerName);
            throw e;
        }

        broker.setContainerId(info.containerId());
        containerIds.put(broker.getBrokerId(), info.containerId());

        List<String> endpoints = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : ENDPOINT_SCHEMES.entrySet()) {
            EndpointInfo endpoint = info.getEndpoint(entry.getKey());
            endpoints.add(entry.getValue() + "://" + endpoint.host() + ":" + endpoint.port());
        }
        EndpointInfo console = info.getEndpoint(CONSOLE_PORT);
        broker.setBrokerInstances(new ArrayList<>(List.of(new BrokerInstance(
                "http://" + console.host() + ":" + console.port(), endpoints, console.host()))));
        LOG.infov("ActiveMQ container {0} started for broker {1}: {2}",
                info.containerId(), broker.getBrokerName(), endpoints);

        if (broker.getLogs() != null && Boolean.TRUE.equals(broker.getLogs().get("general"))) {
            String shortId = info.containerId().length() >= 8 ? info.containerId().substring(0, 8) : info.containerId();
            Closeable logHandle = logStreamer.attach(info.containerId(),
                    "/aws/amazonmq/broker/" + broker.getBrokerId() + "/general",
                    logStreamer.generateLogStreamName(shortId),
                    regionResolver.getDefaultRegion(), "amazonmq:" + broker.getBrokerId());
            if (logHandle != null) {
                logStreams.put(broker.getBrokerId(), logHandle);
            }
        }
    }

    private void copyConfiguration(String containerId, Map<String, String> files) {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                byte[] content = file.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey());
                entry.setSize(content.length);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not package the ActiveMQ broker configuration", e);
        }
        lifecycleManager.getDockerClient().copyArchiveToContainerCmd(containerId)
                .withRemotePath(CONF_DIR)
                .withTarInputStream(new ByteArrayInputStream(archive.toByteArray()))
                .exec();
    }

    /**
     * Ready once the web console answers (any non-server-error status: it requires a login)
     * and the OpenWire transport accepts connections. Jetty starts after the broker's
     * transports, so both together mean the broker is serving.
     */
    public boolean isReady(Broker broker) {
        if (broker.getBrokerInstances() == null || broker.getBrokerInstances().isEmpty()) {
            return false;
        }
        BrokerInstance instance = broker.getBrokerInstances().get(0);
        if (instance.getConsoleURL() == null || instance.getEndpoints() == null || instance.getEndpoints().isEmpty()) {
            return false;
        }
        return consoleAnswers(broker.getBrokerId(), instance.getConsoleURL())
                && acceptsConnections(broker.getBrokerId(), instance.getEndpoints().get(0));
    }

    private static boolean consoleAnswers(String brokerId, String consoleUrl) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(consoleUrl + "/").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            int status = conn.getResponseCode();
            return status > 0 && status < 500;
        } catch (Exception e) {
            // Expected while the broker is still booting; debug keeps the 2s poll quiet.
            LOG.debugf("ActiveMQ console probe for broker %s at %s not ready: %s", brokerId, consoleUrl, e.toString());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static boolean acceptsConnections(String brokerId, String endpoint) {
        URI uri = URI.create(endpoint);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 1000);
            return true;
        } catch (IOException e) {
            LOG.debugf("ActiveMQ OpenWire probe for broker %s at %s not ready: %s", brokerId, endpoint, e.toString());
            return false;
        }
    }

    /**
     * True when the broker's container has exited or disappeared, for example because the
     * broker rejected its configuration. An unreachable Docker daemon is not treated as exited.
     */
    public boolean hasExited(Broker broker) {
        String containerId = containerIds.get(broker.getBrokerId());
        if (containerId == null) {
            return false;
        }
        ContainerPresence presence = lifecycleManager.presenceOf(containerId);
        return presence == ContainerPresence.STOPPED || presence == ContainerPresence.ABSENT;
    }

    public void stopContainer(Broker broker) {
        boolean stopped = stopTracked(broker.getBrokerId());
        if (!stopped) {
            String containerId = broker.getContainerId();
            if (containerId != null) {
                lifecycleManager.stopAndRemove(containerId, null);
            } else {
                // containerId is null after an emulator restart; fall back to the deterministic name.
                lifecycleManager.removeIfExists(containerName(broker.getBrokerId()));
            }
        }
    }

    private boolean stopTracked(String brokerId) {
        String containerId = containerIds.remove(brokerId);
        Closeable logHandle = logStreams.remove(brokerId);
        if (containerId == null) {
            if (logHandle != null) {
                lifecycleManager.closeLogStreamAfterContainerStop(logHandle);
            }
            return false;
        }
        lifecycleManager.stopAndRemove(containerId, logHandle);
        LOG.infov("ActiveMQ container {0} stopped and removed", containerId);
        return true;
    }

    public void removeBrokerStorage(Broker broker) {
        ContainerStorageHelper.removeStorage(config, lifecycleManager,
                "amazonmq", broker.getVolumeId(), broker.getBrokerId());
    }

    @Override
    public void stopManagedContainers() {
        if (!containerIds.isEmpty()) {
            LOG.infov("Stopping {0} ActiveMQ container(s)", containerIds.size());
        }
        for (String brokerId : new ArrayList<>(containerIds.keySet())) {
            stopTracked(brokerId);
        }
    }
}
