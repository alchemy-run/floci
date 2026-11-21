package io.floci.aws.services.vpclattice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Probes the targets of target groups that are in use and have health checks enabled, at each group's interval, and
 * records the results against its healthy and unhealthy thresholds. IP targets are probed at their address, instance
 * targets at the instance's container. HTTP and HTTPS checks request the path and compare the status with the
 * matcher; TCP checks open a connection.
 */
@ApplicationScoped
public class VpcLatticeHealthChecker {

    private static final Logger LOG = Logger.getLogger(VpcLatticeHealthChecker.class);
    private static final long TICK_MILLIS = 1000;

    private final VpcLatticeService service;
    private final Provider<Ec2Service> ec2;
    private final Map<String, Long> lastChecked = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "vpc-lattice-health");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient insecure;

    @Inject
    public VpcLatticeHealthChecker(VpcLatticeService service, Provider<Ec2Service> ec2) {
        this.service = service;
        this.ec2 = ec2;
        this.insecure = HttpClient.newBuilder().sslContext(trustAll()).followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    void onStart(@Observes StartupEvent ignored) {
        executor.scheduleWithFixedDelay(this::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        executor.shutdownNow();
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : service.allTargetGroups()) {
                String region = AwsArnUtils.parse(entry.value().path("arn").asText()).region();
                RequestScopes.runAs(entry.accountId(), region, () -> {
                    for (ObjectNode group : service.checkedTargetGroups(region)) {
                        if (!group.path("id").asText().equals(entry.value().path("id").asText())) {
                            continue;
                        }
                        JsonNode check = group.path("config").path("healthCheck");
                        long interval = check.path("healthCheckIntervalSeconds").asLong(30) * 1000;
                        for (JsonNode target : group.path("_targets")) {
                            if (target.has("drainUntil")) {
                                continue;
                            }
                            String key = entry.accountId() + "/" + group.path("id").asText() + "/"
                                    + target.path("id").asText() + ":" + target.path("port").asInt();
                            Long last = lastChecked.get(key);
                            if (last != null && now - last < interval) {
                                continue;
                            }
                            lastChecked.put(key, now);
                            executor.execute(() -> RequestScopes.runAs(entry.accountId(), region,
                                    () -> probe(region, group, target)));
                        }
                    }
                });
            }
        } catch (RuntimeException e) {
            LOG.debugv(e, "VPC Lattice health check sweep failed");
        }
    }

    private void probe(String region, ObjectNode group, JsonNode target) {
        JsonNode check = group.path("config").path("healthCheck");
        String host = host(group, target.path("id").asText());
        int port = check.path("port").asInt(0) > 0 ? check.path("port").asInt() : target.path("port").asInt();
        Duration timeout = Duration.ofSeconds(Math.max(1, check.path("healthCheckTimeoutSeconds").asInt(5)));
        String protocol = check.path("protocol").asText("HTTP");
        boolean healthy;
        String reason = "HealthCheckFailed";
        try {
            if ("TCP".equals(protocol)) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
                }
                healthy = true;
            } else {
                String scheme = "HTTPS".equals(protocol) ? "https" : "http";
                HttpRequest request = HttpRequest.newBuilder(URI.create(scheme + "://" + host + ":" + port
                                + check.path("path").asText("/"))).timeout(timeout).GET().build();
                int status = insecure.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                healthy = matches(check.path("matcher").path("httpCode").asText("200"), status);
                if (!healthy) {
                    reason = "HealthCheckFailed";
                }
            }
        } catch (IOException e) {
            healthy = false;
            reason = e instanceof HttpTimeoutException ? "Timeout" : "ConnectionFailed";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (RuntimeException e) {
            healthy = false;
            reason = "ConnectionFailed";
        }
        service.recordHealth(region, group.path("id").asText(), target.path("id").asText(),
                target.path("port").asInt(), healthy, reason);
    }

    /** An instance target is probed at its container; an IP target at its address. */
    private String host(ObjectNode group, String id) {
        if ("INSTANCE".equals(group.path("type").asText())) {
            Instance instance = ec2.get().findInstanceById(service.account(), id);
            if (instance != null && instance.getContainerBridgeIp() != null
                    && !instance.getContainerBridgeIp().isBlank()) {
                return instance.getContainerBridgeIp();
            }
        }
        return id;
    }

    /** {@code 200}, {@code 200,202} or {@code 200-299}. */
    static boolean matches(String matcher, int status) {
        for (String part : matcher.split(",")) {
            String trimmed = part.trim();
            if (trimmed.contains("-")) {
                String[] range = trimmed.split("-");
                if (status >= Integer.parseInt(range[0].trim()) && status <= Integer.parseInt(range[1].trim())) {
                    return true;
                }
            } else if (!trimmed.isEmpty() && status == Integer.parseInt(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static SSLContext trustAll() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            return context;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
