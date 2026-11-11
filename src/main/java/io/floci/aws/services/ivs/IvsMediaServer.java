package io.floci.aws.services.ivs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Frame;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.PortAllocator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The MediaMTX media server behind every IVS channel: one container for all of them. A stream key is a configured
 * path, {@code app/<stream key>}, so a broadcaster can publish only with a key that exists, over RTMP
 * ({@code rtmp://<ingest endpoint>/app/<stream key>}) or SRT ({@code streamid=publish:app/<stream key>} with the
 * channel's passphrase). Floci reads the server's state through its API, plays its HLS through the playback proxy,
 * and records a channel's streams to segments it turns into the channel's S3 recording.
 */
@ApplicationScoped
public class IvsMediaServer implements ContainerTeardown {

    static final String SERVICE = "ivs";
    static final String CONTAINER = "floci-ivs-media";
    static final String RECORDINGS = "/recordings";
    private static final Logger LOG = Logger.getLogger(IvsMediaServer.class);
    private static final int API_PORT = 9997;
    private static final int HLS_PORT = 8888;
    private static final int RTMP_PORT = 1935;
    private static final int SRT_PORT = 8890;

    /** Where floci reaches the server: its API and its HLS. */
    record Endpoint(String containerId, String api, String hls) {
    }

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private volatile Endpoint endpoint;
    private final List<Integer> allocated = new ArrayList<>();

    @Inject
    public IvsMediaServer(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                          PortAllocator portAllocator, EmulatorConfig config, ObjectMapper mapper) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.portAllocator = portAllocator;
        this.config = config;
        this.mapper = mapper;
    }

    boolean dockerReachable() {
        try {
            lifecycleManager.getDockerClient().pingCmd().exec();
            return true;
        } catch (RuntimeException e) {
            LOG.debugv("Docker daemon is not reachable: {0}", e.getMessage());
            return false;
        }
    }

    /** The running server, or null when it has not been started. */
    Endpoint endpoint() {
        return endpoint;
    }

    /** Starts the server when it is not running, and waits until its API answers. */
    synchronized Endpoint ensureStarted() {
        if (endpoint != null && running(endpoint.containerId())) {
            return endpoint;
        }
        EmulatorConfig.IvsServiceConfig serviceConfig = config.services().ivs();
        LOG.infov("Starting the IVS media server {0} using image {1}", CONTAINER, serviceConfig.image());
        lifecycleManager.removeIfExists(CONTAINER);
        releasePorts();
        int apiPort = portAllocator.allocate(serviceConfig.hostPortBase(), serviceConfig.hostPortMax());
        int hlsPort = portAllocator.allocate(serviceConfig.hostPortBase(), serviceConfig.hostPortMax());
        allocated.add(apiPort);
        allocated.add(hlsPort);
        ContainerBuilder.Builder builder = containerBuilder.newContainer(serviceConfig.image())
                .withName(CONTAINER)
                .withDockerNetwork(serviceConfig.dockerNetwork())
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(SERVICE, "media", "", ""))
                .withPortBinding(API_PORT, apiPort)
                .withPortBinding(HLS_PORT, hlsPort)
                .withPortBinding(RTMP_PORT, serviceConfig.rtmpPort())
                .withUdpPortBinding(SRT_PORT, serviceConfig.srtPort());
        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.applyStorage(builder, lifecycleManager, config, SERVICE, null, "recordings",
                    RECORDINGS);
        }
        ContainerSpec spec = builder.build();
        String id;
        ContainerInfo info;
        try {
            id = lifecycleManager.create(spec);
            copy(id, "/", Map.of("mediamtx.yml", configuration()));
            info = lifecycleManager.startCreated(id, spec);
        } catch (RuntimeException e) {
            lifecycleManager.removeIfExists(CONTAINER);
            releasePorts();
            throw e;
        }
        EndpointInfo api = info.getEndpoint(API_PORT);
        EndpointInfo hls = info.getEndpoint(HLS_PORT);
        Endpoint started = new Endpoint(id, "http://" + api.host() + ":" + api.port(),
                "http://" + hls.host() + ":" + hls.port());
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(serviceConfig.readinessTimeoutSeconds());
        while (System.currentTimeMillis() < deadline) {
            if (!running(id)) {
                throw new IllegalStateException("The IVS media server exited before it was ready.");
            }
            try {
                if (get(started, "/v3/config/global/get") != null) {
                    endpoint = started;
                    LOG.infov("The IVS media server is ready: RTMP on {0}, SRT on {1}",
                            String.valueOf(serviceConfig.rtmpPort()), String.valueOf(serviceConfig.srtPort()));
                    return started;
                }
            } catch (IllegalStateException e) {
                LOG.debugv("The IVS media server is not ready: {0}", e.getMessage());
            }
            sleep(250);
        }
        throw new IllegalStateException("The IVS media server did not answer within "
                + serviceConfig.readinessTimeoutSeconds() + "s");
    }

    /** The server's configuration: only configured paths (stream keys) take publishers, and anyone may read. */
    private static String configuration() {
        return """
                logLevel: info
                authMethod: internal
                authInternalUsers:
                  - user: any
                    pass:
                    ips: []
                    permissions:
                      - action: publish
                      - action: read
                      - action: playback
                      - action: api
                api: true
                apiAddress: :9997
                rtsp: false
                webrtc: false
                moq: false
                rtmp: true
                rtmpAddress: :1935
                srt: true
                srtAddress: :8890
                hls: true
                hlsAddress: :8888
                hlsVariant: mpegts
                hlsAlwaysRemux: true
                hlsSegmentDuration: 2s
                pathDefaults:
                  recordPath: /recordings/%path/%Y-%m-%d_%H-%M-%S-%f
                  recordFormat: fmp4
                  recordSegmentDuration: 10s
                  recordDeleteAfter: 0s
                paths: {}
                """;
    }

    private boolean running(String containerId) {
        try {
            InspectContainerResponse.ContainerState state = lifecycleManager.getDockerClient()
                    .inspectContainerCmd(containerId).exec().getState();
            return state != null && Boolean.TRUE.equals(state.getRunning());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void releasePorts() {
        allocated.forEach(portAllocator::release);
        allocated.clear();
    }

    @Override
    public synchronized void stopManagedContainers() {
        Endpoint current = endpoint;
        endpoint = null;
        if (current != null) {
            lifecycleManager.stopAndRemove(current.containerId(), null);
        } else {
            lifecycleManager.removeIfExists(CONTAINER);
        }
        releasePorts();
    }

    // ---------------------------------------------------------------- paths (stream keys)

    static String pathOf(String streamKey) {
        return "app/" + streamKey;
    }

    /** Configures (or reconfigures) a stream key's path: whether it records, and its SRT passphrase. */
    void configurePath(String streamKey, boolean record, String srtPassphrase) {
        Endpoint server = endpoint;
        if (server == null) {
            return;
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("record", record);
        if (srtPassphrase != null) {
            body.put("srtPublishPassphrase", srtPassphrase);
        }
        String name = encode(pathOf(streamKey));
        if (send(server, "PATCH", "/v3/config/paths/patch/" + name, body) == 404) {
            send(server, "POST", "/v3/config/paths/add/" + name, body);
        }
    }

    /** Removes a stream key's path, which disconnects a broadcaster using it. */
    void removePath(String streamKey) {
        Endpoint server = endpoint;
        if (server != null) {
            send(server, "DELETE", "/v3/config/paths/delete/" + encode(pathOf(streamKey)), null);
        }
    }

    /** The paths with a source: their name, readiness, tracks and byte counts. */
    List<JsonNode> livePaths() {
        Endpoint server = endpoint;
        List<JsonNode> paths = new ArrayList<>();
        if (server == null) {
            return paths;
        }
        JsonNode list = get(server, "/v3/paths/list?itemsPerPage=1000");
        if (list != null) {
            list.path("items").forEach(paths::add);
        }
        return paths;
    }

    /** The number of HLS sessions reading each path: its viewers. */
    Map<String, Integer> viewers() {
        Endpoint server = endpoint;
        Map<String, Integer> viewers = new LinkedHashMap<>();
        if (server == null) {
            return viewers;
        }
        JsonNode list = get(server, "/v3/hlssessions/list?itemsPerPage=1000");
        if (list != null) {
            list.path("items").forEach(session -> viewers.merge(session.path("path").asText(), 1, Integer::sum));
        }
        return viewers;
    }

    /** Disconnects whoever publishes to a path, over RTMP or SRT. */
    boolean kick(String path) {
        Endpoint server = endpoint;
        if (server == null) {
            return false;
        }
        boolean kicked = false;
        for (String kind : List.of("rtmpconns", "srtconns")) {
            JsonNode list = get(server, "/v3/" + kind + "/list?itemsPerPage=1000");
            if (list == null) {
                continue;
            }
            for (JsonNode connection : list.path("items")) {
                if (path.equals(connection.path("path").asText()) && "publish".equals(connection.path("state")
                        .asText())) {
                    kicked |= send(server, "POST", "/v3/" + kind + "/kick/" + connection.path("id").asText(),
                            null) < 300;
                }
            }
        }
        return kicked;
    }

    private JsonNode get(Endpoint server, String path) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(server.api() + path))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                return null;
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("The IVS media server could not be reached: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling the IVS media server", e);
        }
    }

    private int send(Endpoint server, String method, String path, JsonNode body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(server.api() + path))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body.toString())).build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 300 && status != 404) {
                LOG.warnv("The IVS media server answered {0} {1} with {2}", method, path, status);
            }
            return status;
        } catch (IOException e) {
            LOG.warnv("The IVS media server could not be reached for {0} {1}: {2}", method, path, e.getMessage());
            return 503;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 503;
        }
    }

    private static String encode(String path) {
        return URLEncoder.encode(path, StandardCharsets.UTF_8).replace("%2F", "/");
    }

    // ---------------------------------------------------------------- recordings

    /** The recorded segment files of a path, oldest first. */
    List<String> recordedSegments(String path) {
        ExecResult result = exec(List.of("sh", "-c", "ls -1 " + RECORDINGS + "/" + path + "/*.mp4 2>/dev/null | sort"));
        List<String> files = new ArrayList<>();
        for (String line : result.output().split("\n")) {
            if (!line.isBlank()) {
                files.add(line.trim());
            }
        }
        return files;
    }

    /** Runs a command in the server's container. */
    ExecResult exec(List<String> command) {
        Endpoint server = endpoint;
        if (server == null) {
            return new ExecResult(-1, "");
        }
        ExecCreateCmdResponse created = lifecycleManager.getDockerClient().execCreateCmd(server.containerId())
                .withCmd(command.toArray(String[]::new)).withAttachStdout(true).withAttachStderr(true).exec();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            lifecycleManager.getDockerClient().execStartCmd(created.getId()).exec(
                    new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            output.writeBytes(frame.getPayload());
                        }
                    }).awaitCompletion(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult(-1, output.toString(StandardCharsets.UTF_8));
        }
        Long code = lifecycleManager.getDockerClient().inspectExecCmd(created.getId()).exec().getExitCodeLong();
        return new ExecResult(code == null ? -1 : code.intValue(), output.toString(StandardCharsets.UTF_8));
    }

    record ExecResult(int code, String output) {
    }

    /** The files under a directory of the container, by path relative to it. */
    Map<String, byte[]> copyOut(String directory) {
        Endpoint server = endpoint;
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (server == null) {
            return files;
        }
        try (InputStream archive = lifecycleManager.getDockerClient().copyArchiveFromContainerCmd(
                server.containerId(), directory).exec(); TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
            TarArchiveEntry entry;
            String root = directory.substring(directory.lastIndexOf('/') + 1) + "/";
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().startsWith(root) ? entry.getName().substring(root.length())
                        : entry.getName();
                files.put(name, tar.readAllBytes());
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not copy " + directory + " out of the IVS media server: "
                    + e.getMessage(), e);
        }
        return files;
    }

    private void copy(String containerId, String directory, Map<String, String> files) {
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
            throw new IllegalStateException("Could not package the IVS media server configuration", e);
        }
        lifecycleManager.getDockerClient().copyArchiveToContainerCmd(containerId).withRemotePath(directory)
                .withTarInputStream(new ByteArrayInputStream(archive.toByteArray())).exec();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
