package io.github.hectorvent.floci.services.cloudfront.edge;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Gives every emulated distribution its own local port.
 *
 * <p>A distribution's {@code {id}.cloudfront.net} domain is a real AWS hostname
 * that resolves to nothing on a developer's machine: reaching the emulated edge
 * through it means a {@code /etc/hosts} entry, a fight over port 443 and a
 * self-signed certificate. So each distribution also gets a plain-HTTP port of
 * its own — {@code http://localhost:9500} — which is openable in a browser with
 * no host file, no DNS and no TLS, like any other local dev server.
 *
 * <p>The port server is a thin proxy onto the gateway's own edge route
 * ({@code /_floci/cloudfront/{id}/...}), so the whole edge pipeline — cache
 * behavior matching, CloudFront Functions, origin selection — is shared with the
 * Host-addressed path and cannot drift from it. The viewer's {@code Host} is
 * passed through untouched, so a function sees the hostname the request really
 * arrived on.
 *
 * <p>Ports come from {@code floci.services.cloudfront.edge-base-port} ..
 * {@code edge-max-port}, or from the explicit {@code edge-ports} list. A
 * containerized emulator is only reachable on published ports, so the process
 * that runs the container narrows the list to what it published.
 *
 * @see CloudFrontEdgeInfoController the endpoint that reports the assignments
 */
@ApplicationScoped
public class CloudFrontEdgePorts {

    private static final Logger LOG = Logger.getLogger(CloudFrontEdgePorts.class);

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length");

    private final EmulatorConfig config;
    private final Vertx vertx;
    /** Quarkus's own view of the running gateway, not the Vert.x server type below. */
    private final io.quarkus.vertx.http.HttpServer gateway;

    /** distributionId -> the port it is served on. */
    private final Map<String, Integer> ports = new ConcurrentHashMap<>();
    /** port -> the server bound to it. */
    private final Map<Integer, HttpServer> servers = new ConcurrentHashMap<>();

    private HttpClient client;

    @Inject
    public CloudFrontEdgePorts(EmulatorConfig config, Vertx vertx,
                               io.quarkus.vertx.http.HttpServer gateway) {
        this.config = config;
        this.vertx = vertx;
        this.gateway = gateway;
    }

    @PostConstruct
    void init() {
        client = vertx.createHttpClient(new HttpClientOptions()
                .setMaxPoolSize(50)
                .setConnectTimeout(10_000)
                .setKeepAlive(true));
    }

    @PreDestroy
    void shutdown() {
        servers.values().forEach(HttpServer::close);
        servers.clear();
        ports.clear();
    }

    /** Whether per-distribution edge ports are switched on. */
    public boolean enabled() {
        return config.services() != null
                && config.services().cloudfront() != null
                && config.services().cloudfront().edgePortsEnabled();
    }

    /** The port serving {@code distributionId}, or {@code null} when it has none. */
    public Integer portOf(String distributionId) {
        return distributionId == null ? null : ports.get(distributionId);
    }

    /** The local URL of {@code distributionId}'s edge, or {@code null}. */
    public String urlOf(String distributionId) {
        Integer port = portOf(distributionId);
        return port == null ? null : "http://" + host() + ":" + port;
    }

    /** Every distribution that currently has a port, in assignment order. */
    public Map<String, Integer> assignments() {
        return new LinkedHashMap<>(ports);
    }

    /**
     * Assign (or reuse) a port for {@code distributionId} and serve its edge on
     * it. Never throws: a distribution the emulator cannot give a port to is
     * still reachable by Host on the gateway, so control-plane
     * CreateDistribution must not fail over it.
     *
     * @return the port, or {@code null} when none could be bound
     */
    public Integer bind(String distributionId) {
        if (distributionId == null || !enabled()) {
            return null;
        }
        Integer existing = ports.get(distributionId);
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            existing = ports.get(distributionId);
            if (existing != null) {
                return existing;
            }
            for (int port : candidatePorts()) {
                if (servers.containsKey(port)) {
                    continue;
                }
                HttpServer server = listen(distributionId, port);
                if (server == null) {
                    continue;
                }
                servers.put(port, server);
                ports.put(distributionId, port);
                LOG.infov("CloudFront distribution {0} edge listening on http://{1}:{2}",
                        distributionId, host(), String.valueOf(port));
                return port;
            }
            LOG.warnv("CloudFront distribution {0}: no free edge port in {1} — the edge is only "
                    + "reachable by Host on the gateway", distributionId, describeRange());
            return null;
        }
    }

    /** Release {@code distributionId}'s port, if it holds one. */
    public void release(String distributionId) {
        if (distributionId == null) {
            return;
        }
        Integer port = ports.remove(distributionId);
        if (port == null) {
            return;
        }
        HttpServer server = servers.remove(port);
        if (server != null) {
            server.close();
        }
    }

    /**
     * Bind synchronously so the caller can report the port in the very response
     * that creates the distribution.
     */
    private HttpServer listen(String distributionId, int port) {
        HttpServer server = vertx.createHttpServer(new HttpServerOptions()
                .setHost("0.0.0.0")
                .setPort(port));
        server.requestHandler(req -> proxy(distributionId, req));
        try {
            server.listen().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            return server;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.close();
            return null;
        } catch (Exception e) {
            LOG.debugv("CloudFront edge port {0} unavailable: {1}", String.valueOf(port), e.getMessage());
            server.close();
            return null;
        }
    }

    /** Forward the viewer request onto the gateway's edge route for this distribution. */
    private void proxy(String distributionId, HttpServerRequest req) {
        String uri = CloudFrontEdgeRoutingFilter.EDGE_PREFIX + "/" + distributionId + req.uri();
        req.body().compose(body -> client.request(new RequestOptions()
                        .setMethod(req.method() == null ? HttpMethod.GET : req.method())
                        .setHost("127.0.0.1")
                        .setPort(gatewayPort())
                        .setURI(uri)
                        .setTimeout(60_000))
                .compose(edgeRequest -> {
                    req.headers().forEach(header -> {
                        if (!HOP_BY_HOP.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                            edgeRequest.putHeader(header.getKey(), header.getValue());
                        }
                    });
                    // Keep the hostname the viewer actually used: the edge builds
                    // the CloudFront event from these headers, and a function that
                    // reads `event.request.headers.host` must see what the browser
                    // sent, not the internal gateway address.
                    edgeRequest.putHeader("Host", viewerHost(req, distributionId));
                    return body == null || body.length() == 0
                            ? edgeRequest.send()
                            : edgeRequest.send(body);
                })
                .compose(edgeResponse -> {
                    req.response().setStatusCode(edgeResponse.statusCode());
                    edgeResponse.headers().forEach(header -> {
                        if (!HOP_BY_HOP.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                            req.response().putHeader(header.getKey(), header.getValue());
                        }
                    });
                    return edgeResponse.body();
                })
                .compose(payload -> req.response().end(payload == null ? Buffer.buffer() : payload)))
                .onFailure(error -> {
                    LOG.debugv(error, "CloudFront edge port proxy failed for {0}", distributionId);
                    if (!req.response().ended()) {
                        req.response().setStatusCode(502).end("The emulated CloudFront edge is unreachable: "
                                + error.getMessage());
                    }
                });
    }

    /**
     * The port the gateway is really listening on. {@code floci.port} is the
     * port it was asked for; a test — or anyone running with
     * {@code quarkus.http.port=0} — gets an ephemeral one instead, and the
     * proxy has to follow the socket that exists rather than the configured
     * number.
     */
    private int gatewayPort() {
        int actual = gateway.getPort();
        return actual > 0 ? actual : config.port();
    }

    private static String viewerHost(HttpServerRequest req, String distributionId) {
        String header = req.getHeader("Host");
        if (header != null && !header.isBlank()) {
            return header;
        }
        return req.authority() != null ? req.authority().toString() : distributionId;
    }

    private String host() {
        return config.hostname().filter(h -> !h.isBlank()).orElse("localhost");
    }

    private String describeRange() {
        EmulatorConfig.CloudFrontServiceConfig cf = config.services().cloudfront();
        return cf.edgePorts().filter(s -> !s.isBlank())
                .orElseGet(() -> cf.edgeBasePort() + "-" + cf.edgeMaxPort());
    }

    /** The configured ports, in preference order. */
    private List<Integer> candidatePorts() {
        EmulatorConfig.CloudFrontServiceConfig cf = config.services().cloudfront();
        String explicit = cf.edgePorts().filter(s -> !s.isBlank()).orElse(null);
        List<Integer> out = new ArrayList<>();
        if (explicit != null) {
            for (String part : explicit.split(",")) {
                addRange(out, part.trim());
            }
        } else {
            for (int port = cf.edgeBasePort(); port <= cf.edgeMaxPort(); port++) {
                out.add(port);
            }
        }
        return out;
    }

    private static void addRange(List<Integer> out, String spec) {
        if (spec.isEmpty()) {
            return;
        }
        int dash = spec.indexOf('-', 1);
        try {
            if (dash < 0) {
                out.add(Integer.parseInt(spec));
                return;
            }
            int from = Integer.parseInt(spec.substring(0, dash).trim());
            int to = Integer.parseInt(spec.substring(dash + 1).trim());
            for (int port = from; port <= to; port++) {
                out.add(port);
            }
        } catch (NumberFormatException e) {
            LOG.warnv("Ignoring unparseable CloudFront edge port spec ''{0}''", spec);
        }
    }
}
