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

    /** appPort → hostname → backend IP */
    private final Map<Integer, Map<String, String>> backends = new ConcurrentHashMap<>();

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
     */
    public synchronized boolean register(int appPort, String hostname, String backendIp) {
        if (appPort <= 0 || hostname == null || hostname.isBlank()
                || backendIp == null || backendIp.isBlank()) {
            return false;
        }
        Map<String, String> routes = backends.computeIfAbsent(appPort, p -> new LinkedHashMap<>());
        boolean created = routes.isEmpty();
        routes.put(hostname, backendIp);
        if (created && !ensureContainer(appPort)) {
            backends.remove(appPort);
            return false;
        }
        if (!created && !applyConfig(appPort)) {
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
        Map<String, String> routes = backends.get(appPort);
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
        StringBuilder http = new StringBuilder();
        http.append("worker_processes 1;\n");
        http.append("error_log /var/log/nginx/error.log warn;\n");
        http.append("pid /tmp/nginx.pid;\n");
        http.append("events { worker_connections 128; }\n");
        http.append("http {\n");
        http.append("  access_log off;\n");
        String defaultIp = hostnameToIp.values().stream().findFirst().orElse("127.0.0.1");
        for (Map.Entry<String, String> entry : hostnameToIp.entrySet()) {
            http.append(serverBlock(appPort, entry.getKey(), entry.getValue(), false));
        }
        http.append(serverBlock(appPort, "_", defaultIp, true));
        http.append("}\n");
        return http.toString();
    }

    private static String serverBlock(int appPort, String serverName, String backendIp, boolean defaultServer) {
        return "  server {\n"
                + "    listen " + appPort + (defaultServer ? " default_server" : "") + ";\n"
                + "    server_name " + serverName + ";\n"
                + "    location / {\n"
                + "      proxy_http_version 1.1;\n"
                + "      proxy_set_header Host $host;\n"
                + "      proxy_set_header Connection \"\";\n"
                + "      proxy_pass http://" + backendIp + ":" + appPort + ";\n"
                + "    }\n"
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
            writeNginxConfig(containerId, nginxConfig(appPort, backends.getOrDefault(appPort, Map.of())));
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
            writeNginxConfig(containerId, nginxConfig(appPort, backends.getOrDefault(appPort, Map.of())));
            return reloadNginx(containerId);
        } catch (Exception e) {
            LOG.warnv("Failed to reload EC2 HTTP mux on port {0}: {1}", appPort, e.getMessage());
            return false;
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
}
