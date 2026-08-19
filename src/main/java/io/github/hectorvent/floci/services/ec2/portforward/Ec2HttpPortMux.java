package io.github.hectorvent.floci.services.ec2.portforward;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Publishes one host port per guest app port and reverse-proxies by
 * {@code Host} so two instances that both serve {@code :3000} stay reachable
 * as {@code http://i-xxx.localhost.floci.io:3000}.
 */
@ApplicationScoped
public class Ec2HttpPortMux {

    private static final Logger LOG = Logger.getLogger(Ec2HttpPortMux.class);
    static final String NGINX_IMAGE = "nginx:alpine";

    private final DockerClient dockerClient;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final PortAllocator portAllocator;

    /** appPort → hostname → backend */
    private final Map<Integer, Map<String, Backend>> backends = new ConcurrentHashMap<>();

    @Inject
    public Ec2HttpPortMux(DockerClient dockerClient,
                          ContainerBuilder containerBuilder,
                          ContainerLifecycleManager lifecycleManager,
                          PortAllocator portAllocator) {
        this.dockerClient = dockerClient;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.portAllocator = portAllocator;
    }

    /**
     * Registers {@code hostname} → {@code backendIp} on {@code appPort}. Creates the
     * mux sidecar the first time the port is claimed. Returns {@code false} when
     * the host port cannot be published.
     *
     * <p>Always rewrites nginx config, including when a leftover mux container
     * is adopted: otherwise Host routing stays pointed at recycled bridge IPs
     * (the mux itself after Docker reuse) and {@code /health} loops until
     * {@code worker_connections} is exhausted.
     */
    public synchronized boolean register(int appPort, String hostname, String backendIp) {
        return register(appPort, hostname, backendIp, null);
    }

    public synchronized boolean register(int appPort, String hostname, String backendIp,
                                         String backendContainerName) {
        if (appPort <= 0 || hostname == null || hostname.isBlank()
                || backendIp == null || backendIp.isBlank()) {
            return false;
        }
        Map<String, Backend> routes = backends.computeIfAbsent(appPort, p -> new LinkedHashMap<>());
        routes.put(hostname, new Backend(backendIp, backendContainerName));
        if (!ensureContainer(appPort) || !applyConfig(appPort)) {
            routes.remove(hostname);
            if (routes.isEmpty()) {
                removeContainer(appPort);
                backends.remove(appPort);
            }
            return false;
        }
        LOG.infov("EC2 HTTP mux port {0}: {1} -> {2}:{0}", appPort, hostname, backendIp);
        return true;
    }

    public synchronized void unregister(int appPort, String hostname) {
        Map<String, Backend> routes = backends.get(appPort);
        if (routes == null) {
            return;
        }
        routes.remove(hostname);
        if (routes.isEmpty()) {
            removeContainer(appPort);
            backends.remove(appPort);
            return;
        }
        applyConfig(appPort);
    }

    static String muxContainerName(int appPort) {
        return "floci-ec2-mux-" + appPort;
    }

    public static String nginxConfig(int appPort, Map<String, String> hostnameToIp) {
        return nginxConfig(appPort, hostnameToIp, null);
    }

    /**
     * @param muxIp when set, any backend that equals the mux container's own
     *        bridge IP is omitted. Docker recycles IPs onto the mux after an
     *        instance dies; proxying that address is a self-loop.
     */
    public static String nginxConfig(int appPort, Map<String, String> hostnameToIp, String muxIp) {
        Map<String, String> routes = routesWithoutSelf(hostnameToIp, muxIp);
        StringBuilder http = new StringBuilder();
        http.append("worker_processes 1;\n");
        http.append("error_log /var/log/nginx/error.log warn;\n");
        http.append("pid /tmp/nginx.pid;\n");
        http.append("events { worker_connections 1024; }\n");
        http.append("http {\n");
        http.append("  access_log off;\n");
        http.append("  proxy_connect_timeout 2s;\n");
        http.append("  proxy_read_timeout 5s;\n");
        for (Map.Entry<String, String> entry : routes.entrySet()) {
            http.append(serverBlock(appPort, entry.getKey(), entry.getValue()));
        }
        http.append(unknownHostBlock(appPort));
        http.append("}\n");
        return http.toString();
    }

    static Map<String, String> routesWithoutSelf(Map<String, String> hostnameToIp, String muxIp) {
        if (hostnameToIp == null || hostnameToIp.isEmpty()) {
            return Map.of();
        }
        Map<String, String> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : hostnameToIp.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            if (muxIp != null && !muxIp.isBlank() && muxIp.equals(entry.getValue())) {
                continue;
            }
            filtered.put(entry.getKey(), entry.getValue());
        }
        return filtered;
    }

    private static String serverBlock(int appPort, String serverName, String backendIp) {
        return "  server {\n"
                + "    listen " + appPort + ";\n"
                + "    server_name " + serverName + " " + serverName + ":" + appPort + ";\n"
                + "    location / {\n"
                + "      proxy_http_version 1.1;\n"
                + "      proxy_set_header Host $host;\n"
                + "      proxy_set_header Connection \"\";\n"
                + "      proxy_pass http://" + backendIp + ":" + appPort + ";\n"
                + "    }\n"
                + "  }\n";
    }

    /**
     * Unknown {@code Host} values must 502 — never steal the first backend.
     * A catch-all {@code proxy_pass} to that IP becomes a self-loop once
     * Docker recycles the address onto the mux container.
     */
    private static String unknownHostBlock(int appPort) {
        return "  server {\n"
                + "    listen " + appPort + " default_server;\n"
                + "    server_name _;\n"
                + "    default_type text/plain;\n"
                + "    return 502;\n"
                + "  }\n";
    }

    private boolean ensureContainer(int appPort) {
        String name = muxContainerName(appPort);
        if (lifecycleManager.findByName(name).isPresent()) {
            portAllocator.markReserved(appPort);
            return true;
        }
        if (!portAllocator.tryAllocate(appPort)) {
            LOG.warnv("Host port {0} is busy; cannot publish EC2 HTTP mux", appPort);
            return false;
        }
        try {
            lifecycleManager.removeIfExists(name);
            ContainerSpec spec = containerBuilder.newContainer(NGINX_IMAGE)
                    .withName(name)
                    .withPortBinding(appPort, appPort)
                    .withLogRotation()
                    .build();
            String containerId = lifecycleManager.create(spec);
            writeNginxConfig(containerId, nginxConfig(appPort, storedHostnameIps(appPort)));
            lifecycleManager.startCreated(containerId, spec);
            return true;
        } catch (Exception e) {
            portAllocator.release(appPort);
            LOG.warnv("Failed to start EC2 HTTP mux on port {0}: {1}", appPort, e.getMessage());
            return false;
        }
    }

    private boolean applyConfig(int appPort) {
        String name = muxContainerName(appPort);
        var found = lifecycleManager.findByName(name);
        if (found.isEmpty()) {
            return ensureContainer(appPort) && applyConfig(appPort);
        }
        try {
            String containerId = found.get().getId();
            String muxIp = inspectContainerIp(containerId);
            Map<String, String> routes = resolveLiveRoutes(appPort, muxIp);
            writeNginxConfig(containerId, nginxConfig(appPort, routes, muxIp));
            return reloadNginx(containerId);
        } catch (Exception e) {
            LOG.warnv("Failed to reload EC2 HTTP mux on port {0}: {1}", appPort, e.getMessage());
            return false;
        }
    }

    /**
     * Re-inspects each backend container so a recycled bridge IP cannot stay
     * pointed at a dead instance (or the mux). Drops hostnames whose guest is gone.
     */
    private Map<String, String> resolveLiveRoutes(int appPort, String muxIp) {
        Map<String, Backend> stored = backends.getOrDefault(appPort, Map.of());
        Map<String, String> live = new LinkedHashMap<>();
        for (Map.Entry<String, Backend> entry : stored.entrySet()) {
            String ip = resolveLiveIp(entry.getValue());
            if (ip == null || ip.isBlank()) {
                continue;
            }
            if (muxIp != null && muxIp.equals(ip)) {
                LOG.warnv("EC2 HTTP mux port {0}: dropping {1}; backend IP {2} is the mux itself",
                        appPort, entry.getKey(), ip);
                continue;
            }
            live.put(entry.getKey(), ip);
        }
        return live;
    }

    private String resolveLiveIp(Backend backend) {
        if (backend.containerName() != null && !backend.containerName().isBlank()) {
            var found = lifecycleManager.findByName(backend.containerName());
            if (found.isPresent()) {
                String ip = inspectContainerIp(found.get().getId());
                if (ip != null && !ip.isBlank()) {
                    return ip;
                }
            }
        }
        return backend.ip();
    }

    private String inspectContainerIp(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return null;
        }
        try {
            var inspect = dockerClient.inspectContainerCmd(containerId).exec();
            if (inspect.getNetworkSettings() == null || inspect.getNetworkSettings().getNetworks() == null) {
                return null;
            }
            return inspect.getNetworkSettings().getNetworks().values().stream()
                    .map(network -> network != null ? network.getIpAddress() : null)
                    .filter(ip -> ip != null && !ip.isBlank())
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            LOG.debugv("Could not inspect mux/backend container {0}: {1}", containerId, e.getMessage());
            return null;
        }
    }

    private void removeContainer(int appPort) {
        lifecycleManager.removeIfExists(muxContainerName(appPort));
        portAllocator.release(appPort);
        LOG.infov("Removed EC2 HTTP mux on port {0}", appPort);
    }

    private void writeNginxConfig(String containerId, String config) throws IOException {
        byte[] bytes = config.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bos)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            TarArchiveEntry entry = new TarArchiveEntry("nginx.conf");
            entry.setSize(bytes.length);
            entry.setMode(0644);
            tar.putArchiveEntry(entry);
            tar.write(bytes);
            tar.closeArchiveEntry();
        }
        dockerClient.copyArchiveToContainerCmd(containerId)
                .withRemotePath("/etc/nginx")
                .withTarInputStream(new ByteArrayInputStream(bos.toByteArray()))
                .exec();
    }

    private boolean reloadNginx(String containerId) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                if (tryReloadNginx(containerId)) {
                    return true;
                }
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(200);
        }
        if (last != null) {
            throw last;
        }
        return false;
    }

    private boolean tryReloadNginx(String containerId) throws Exception {
        String execId = dockerClient.execCreateCmd(containerId)
                .withCmd("nginx", "-s", "reload")
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();
        CountDownLatch latch = new CountDownLatch(1);
        dockerClient.execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onComplete() {
                latch.countDown();
            }

            @Override
            public void onError(Throwable t) {
                latch.countDown();
            }
        });
        if (!latch.await(10, TimeUnit.SECONDS)) {
            return false;
        }
        Long exit = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
        return exit != null && exit == 0;
    }

    private Map<String, String> storedHostnameIps(int appPort) {
        Map<String, String> ips = new LinkedHashMap<>();
        for (Map.Entry<String, Backend> entry : backends.getOrDefault(appPort, Map.of()).entrySet()) {
            if (entry.getValue() != null && entry.getValue().ip() != null) {
                ips.put(entry.getKey(), entry.getValue().ip());
            }
        }
        return ips;
    }

    record Backend(String ip, String containerName) {
    }
}
