package io.github.hectorvent.floci.services.elbv2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsProxyServer;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.github.hectorvent.floci.services.elbv2.model.RuleCondition;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class ElbV2DataPlane {

    private static final Logger LOG = Logger.getLogger(ElbV2DataPlane.class);

    private static final List<String> HOP_BY_HOP_HEADERS = List.of(
            "connection", "keep-alive", "transfer-encoding", "upgrade", "te", "trailers", "proxy-authorization", "proxy-authenticate"
    );
    private static final String PRESERVE_HOST_HEADER_ATTRIBUTE = "routing.http.preserve_host_header.enabled";
    static final String LAMBDA_MULTI_VALUE_HEADERS_ATTRIBUTE = "lambda.multi_value_headers.enabled";
    private static final int MAX_LAMBDA_BODY_BYTES = 1024 * 1024;
    // Framing headers are recomputed by the load balancer for the body it actually writes.
    private static final List<String> LAMBDA_RESPONSE_FRAMING_HEADERS = List.of(
            "content-length", "transfer-encoding", "connection", "keep-alive", "upgrade", "te", "trailer"
    );

    @Inject
    Vertx vertx;

    @Inject
    ElbV2Service elbV2Service;

    @Inject
    ElbV2HealthChecker healthChecker;

    @Inject
    EmulatorConfig config;

    @Inject
    TlsProxyServer tlsProxyServer;

    @Inject
    LambdaService lambdaService;

    @Inject
    Ec2Service ec2Service;

    @Inject
    ObjectMapper objectMapper;

    private final Map<Integer, HttpServer> servers = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean releaseHandlerRegistered =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<Integer, Map<String, String>> listenersByHostAndPort = new ConcurrentHashMap<>();
    private final Map<String, ListenerBinding> listenerBindings = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<List<CompiledRule>>> ruleChains = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> rrCounters = new ConcurrentHashMap<>();
    private final Map<String, String> listenerRegions = new ConcurrentHashMap<>();
    private final Map<Integer, String> sharedAddressAffinity = new ConcurrentHashMap<>();

    private HttpClient proxyClient;

    private record ListenerBinding(int port, String host, String loadBalancerArn) {}

    @PostConstruct
    void init() {
        proxyClient = vertx.createHttpClient(new HttpClientOptions()
                .setMaxPoolSize(100)
                .setConnectTimeout(5000)
                .setKeepAlive(true));
    }

    @PreDestroy
    void shutdown() {
        for (Map.Entry<Integer, HttpServer> e : servers.entrySet()) {
            e.getValue().close();
        }
        servers.clear();
        listenersByHostAndPort.clear();
        listenerBindings.clear();
        ruleChains.clear();
        rrCounters.clear();
        listenerRegions.clear();
        sharedAddressAffinity.clear();
    }

    public void startListener(Listener listener, String region, List<Rule> rules) {
        try {
            startListenerUnsafe(listener, region, rules);
        } catch (RuntimeException e) {
            // Control-plane Create/ModifyListener must not fail because the local
            // HTTP proxy cannot bind (null port, NLB/TCP/TLS, port-in-use). AWS
            // CreateListener is a control-plane call; leaking an NPE as
            // InternalFailure: Unexpected error: null is what blocked ECS NLB/ALB.
            LOG.errorf(e, "ELBv2 data-plane failed to start listener %s",
                    listener != null ? listener.getListenerArn() : null);
        }
    }

    private void startListenerUnsafe(Listener listener, String region, List<Rule> rules) {
        if (config == null || config.services() == null || config.services().elbv2() == null
                || config.services().elbv2().mock()) {
            return;
        }
        if (listener == null || listener.getListenerArn() == null || listener.getPort() == null) {
            LOG.warnv("Skipping ELBv2 data-plane start: listener={0} port={1}",
                    listener != null ? listener.getListenerArn() : null,
                    listener != null ? listener.getPort() : null);
            return;
        }
        String listenerArn = listener.getListenerArn();
        List<CompiledRule> compiled = compileRules(rules != null ? rules : List.of());
        ruleChains.put(listenerArn, new AtomicReference<>(compiled));
        listenerRegions.put(listenerArn, region);
        ListenerBinding binding = binding(listener, region);
        if (binding == null) {
            return;
        }
        listenerBindings.put(listenerArn, binding);
        listenersByHostAndPort.computeIfAbsent(binding.port(), ignored -> new ConcurrentHashMap<>())
                .put(binding.host(), listenerArn);
        ensureReleaseHandlerRegistered();
        servers.computeIfAbsent(binding.port(), this::startPortServer);
    }

    public void restartListener(Listener listener, String region, List<Rule> rules) {
        try {
            restartListenerUnsafe(listener, region, rules);
        } catch (RuntimeException e) {
            LOG.errorf(e, "ELBv2 data-plane failed to restart listener %s",
                    listener != null ? listener.getListenerArn() : null);
        }
    }

    private void restartListenerUnsafe(Listener listener, String region, List<Rule> rules) {
        if (config == null || config.services() == null || config.services().elbv2() == null
                || config.services().elbv2().mock()) {
            return;
        }
        if (listener == null || listener.getListenerArn() == null) {
            return;
        }
        String listenerArn = listener.getListenerArn();
        ListenerBinding newBinding = binding(listener, region);
        if (newBinding == null) {
            return;
        }
        ListenerBinding oldBinding = listenerBindings.get(listenerArn);
        if (newBinding.equals(oldBinding)) {
            ruleChains.put(listenerArn, new AtomicReference<>(compileRules(rules)));
            listenerRegions.put(listenerArn, region);
            listenersByHostAndPort.computeIfAbsent(newBinding.port(), ignored -> new ConcurrentHashMap<>())
                    .put(newBinding.host(), listenerArn);
            ensureReleaseHandlerRegistered();
            servers.computeIfAbsent(newBinding.port(), this::startPortServer);
            return;
        }
        oldBinding = listenerBindings.remove(listenerArn);
        if (oldBinding != null) {
            Map<String, String> listenersByHost = listenersByHostAndPort.get(oldBinding.port());
            if (listenersByHost != null) {
                listenersByHost.remove(oldBinding.host(), listenerArn);
                closePortServerIfUnused(oldBinding.port(), listenersByHost);
            }
        }
        startListenerUnsafe(listener, region, rules);
    }

    public void stopListener(String listenerArn) {
        ListenerBinding binding = listenerBindings.remove(listenerArn);
        if (binding != null) {
            Map<String, String> listenersByHost = listenersByHostAndPort.get(binding.port());
            if (listenersByHost != null) {
                listenersByHost.remove(binding.host(), listenerArn);
                closePortServerIfUnused(binding.port(), listenersByHost);
            }
        }
        ruleChains.remove(listenerArn);
        listenerRegions.remove(listenerArn);
        sharedAddressAffinity.values().removeIf(listenerArn::equals);
    }

    public void recompileRules(String listenerArn, List<Rule> rules) {
        AtomicReference<List<CompiledRule>> ref = ruleChains.get(listenerArn);
        if (ref != null) {
            ref.set(compileRules(rules));
        }
    }

    /**
     * Ports Floci reserves for itself, which a load balancer listener must not take.
     *
     * <p>The emulator's own port is always reserved. With TLS enabled the TLS proxy also binds
     * {@code floci.tls.aws-https-port} (443 by default), because CDK's custom-resource
     * {@code cfn-response} callback hardcodes {@code https://} and ignores the port in the
     * ResponseURL, so it always lands on 443.
     *
     * <p>Without this the two raced: whichever started first won. A stack restored from
     * persistence could hand 443 to an ALB listener, which then answered CDK's callback in plain
     * HTTP and hung every {@code Custom::} resource - and the reverse on a fresh start. Yielding
     * here makes the outcome deterministic and keeps the emulator's own control path working. To
     * give 443 to load balancers instead, set {@code floci.tls.aws-https-port=0} or disable TLS.
     */
    // Package-private for ElbV2ReservedPortTest.
    boolean isReservedByFloci(int port) {
        if (port == config.port()) {
            return true;
        }
        // Ask the proxy whether it actually holds the port rather than inferring it from config.
        // Binding the AWS-HTTPS port is privileged and its failure is non-fatal, so a port the
        // proxy never acquired must stay available to listeners instead of going unserved.
        return tlsProxyServer.reservesPort(port);
    }

    /**
     * Binds any registered listener whose port the proxy has just released. Yielding a port whose
     * bind had not resolved yet is the safe default, but if that bind then fails nothing else
     * would ever retry the listener, leaving it registered with no data plane and the port free.
     */
    private void bindListenersWaitingOn(int port) {
        if (listenersByHostAndPort.containsKey(port)) {
            servers.computeIfAbsent(port, this::startPortServer);
        }
    }

    /**
     * Subscribes to the proxy's released ports, once, on the first listener to need it: the data
     * plane is only live once a listener exists, and this keeps the proxy out of the mock-mode
     * path entirely.
     *
     * <p>Placement is load-bearing on both sides. It runs after the listener is in
     * {@code listenersByHostAndPort}, so the replay of ports already given up finds it, and before
     * {@code servers.computeIfAbsent}, because {@code onPortReleased} replays synchronously: doing
     * this from inside the mapping function re-entered {@code computeIfAbsent} for the very key it
     * was still computing, which a {@link ConcurrentHashMap} rejects outright.
     */
    private void ensureReleaseHandlerRegistered() {
        if (releaseHandlerRegistered.compareAndSet(false, true)) {
            tlsProxyServer.onPortReleased(this::bindListenersWaitingOn);
        }
    }

    private HttpServer startPortServer(int port) {
        if (isReservedByFloci(port)) {
            // Returning null leaves `servers` untouched: computeIfAbsent skips a null mapping.
            // The emulator's own port cannot be handed over, so the two cases need different
            // advice: freeing floci.tls.aws-https-port is only meaningful for the TLS proxy.
            String remedy = port == config.port()
                    ? "This is the emulator's own port, so it cannot be freed; give the listener a different port."
                    : "Set floci.tls.aws-https-port=0 (or disable TLS) to free it.";
            LOG.warnv("ELBv2 listener port {0} is reserved by Floci itself; the listener is "
                    + "registered but serves no traffic. {1}", String.valueOf(port), remedy);
            return null;
        }
        HttpServer server = vertx.createHttpServer(new HttpServerOptions()
                .setHost("0.0.0.0")
                .setPort(port));
        server.requestHandler(req -> handleRequest(req, port));
        server.listen()
                .onSuccess(s -> LOG.infov("ELBv2 listener port started on {0}", String.valueOf(port)))
                .onFailure(err -> {
                    servers.remove(port);
                    clearPortBindings(port);
                    server.close();
                    LOG.warnv("ELBv2 listener port failed to start on {0}: {1}", String.valueOf(port), err.getMessage());
                });
        return server;
    }

    private void closePortServerIfUnused(int port, Map<String, String> listenersByHost) {
        if (!listenersByHost.isEmpty()) {
            return;
        }
        listenersByHostAndPort.remove(port, listenersByHost);
        HttpServer server = servers.remove(port);
        if (server != null) {
            server.close();
        }
    }

    private void clearPortBindings(int port) {
        sharedAddressAffinity.remove(port);
        Map<String, String> listenersByHost = listenersByHostAndPort.remove(port);
        if (listenersByHost == null) {
            return;
        }
        for (String listenerArn : listenersByHost.values()) {
            listenerBindings.remove(listenerArn);
            ruleChains.remove(listenerArn);
            listenerRegions.remove(listenerArn);
        }
    }

    private ListenerBinding binding(Listener listener, String region) {
        if (listener == null || listener.getPort() == null) {
            return null;
        }
        LoadBalancer loadBalancer = elbV2Service != null
                ? elbV2Service.getLoadBalancer(region, listener.getLoadBalancerArn())
                : null;
        String host = loadBalancer != null
                ? normalizeHost(loadBalancer.getDnsName())
                : listener.getLoadBalancerArn();
        return new ListenerBinding(listener.getPort(), host, listener.getLoadBalancerArn());
    }

    private void handleRequest(io.vertx.core.http.HttpServerRequest req, int port) {
        String listenerArn = resolveListenerArn(port, req);
        if (listenerArn == null) {
            req.response().setStatusCode(502).end("No listener for host");
            return;
        }
        String region = listenerRegions.get(listenerArn);
        if (region == null) {
            req.response().setStatusCode(502).end("No listener region");
            return;
        }
        AtomicReference<List<CompiledRule>> ref = ruleChains.get(listenerArn);
        if (ref == null) {
            req.response().setStatusCode(502).end("No rule chain");
            return;
        }
        List<CompiledRule> chain = ref.get();
        for (CompiledRule compiled : chain) {
            if (compiled.matches(req)) {
                executeAction(req, compiled.action, region, listenerArn);
                return;
            }
        }
        req.response().setStatusCode(502).end("No matching rule");
    }

    private String resolveListenerArn(int port, HttpServerRequest req) {
        Map<String, String> listenersByHost = listenersByHostAndPort.get(port);
        if (listenersByHost == null || listenersByHost.isEmpty()) {
            return null;
        }
        String host = normalizeHost(req.host());
        String listenerArn = listenersByHost.get(host);
        if (listenerArn != null) {
            return listenerArn;
        }
        if (listenersByHost.size() == 1) {
            return listenersByHost.values().iterator().next();
        }
        if (!ElbV2TargetResolver.isIpLiteral(host)) {
            return null;
        }
        return resolveSharedAddressListener(port, listenersByHost.values(), req);
    }

    /**
     * On AWS every load balancer owns its addresses, so a request sent to a resolved IP reaches
     * exactly one load balancer. Every Floci load balancer resolves to the same local address, so
     * a request addressed by IP carries no load balancer identity once two listeners share a
     * port. The listener whose explicit (non-default) rule matches the request is the only one
     * that could have been configured for it; when that does not single one out, the listener
     * last resolved this way on the port keeps serving the client that was talking to it.
     */
    private String resolveSharedAddressListener(int port, Collection<String> candidates, HttpServerRequest req) {
        List<String> explicitMatches = new ArrayList<>();
        for (String candidate : candidates) {
            AtomicReference<List<CompiledRule>> ref = ruleChains.get(candidate);
            if (ref == null) {
                continue;
            }
            for (CompiledRule compiled : ref.get()) {
                if (compiled.matches(req)) {
                    if (!compiled.rule.isDefault()) {
                        explicitMatches.add(candidate);
                    }
                    break;
                }
            }
        }
        String selected = selectSharedAddressListener(explicitMatches, candidates, sharedAddressAffinity.get(port));
        if (selected != null && explicitMatches.size() == 1) {
            sharedAddressAffinity.put(port, selected);
        }
        return selected;
    }

    static String selectSharedAddressListener(List<String> explicitMatches, Collection<String> candidates,
                                              String affinity) {
        if (explicitMatches.size() == 1) {
            return explicitMatches.get(0);
        }
        boolean affinityLive = affinity != null && candidates.contains(affinity);
        if (!affinityLive) {
            return null;
        }
        if (explicitMatches.isEmpty() || explicitMatches.contains(affinity)) {
            return affinity;
        }
        return null;
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String normalized = host.trim().toLowerCase();
        int colon = normalized.lastIndexOf(':');
        int bracket = normalized.lastIndexOf(']');
        if (colon > -1 && colon > bracket) {
            normalized = normalized.substring(0, colon);
        }
        return normalized;
    }

    private void executeAction(io.vertx.core.http.HttpServerRequest req, Action action, String region, String listenerArn) {
        if (action == null) {
            req.response().setStatusCode(502).end("No action");
            return;
        }
        switch (action.getType() != null ? action.getType() : "") {
            case "forward" -> executeForward(req, action, region, listenerArn);
            case "redirect" -> executeRedirect(req, action);
            case "fixed-response" -> executeFixedResponse(req, action);
            default -> req.response().setStatusCode(502).end("Unsupported action type");
        }
    }

    private void executeForward(io.vertx.core.http.HttpServerRequest req, Action action, String region, String listenerArn) {
        String tgArn = resolveTgArn(action);
        if (tgArn == null) {
            req.response().setStatusCode(502).end("No target group");
            return;
        }
        TargetGroup tg = elbV2Service.getTargetGroup(region, tgArn);
        if (tg == null) {
            req.response().setStatusCode(502).end("Target group not found");
            return;
        }

        if ("lambda".equals(tg.getTargetType())) {
            List<TargetDescription> targets = tg.getTargets();
            if (targets.isEmpty()) {
                req.response().setStatusCode(503).end("No Lambda targets registered");
                return;
            }
            String functionArn = targets.get(0).getId();
            invokeLambdaTarget(req, tg, functionArn, region);
            return;
        }

        List<TargetDescription> allTargets = tg.getTargets();
        List<TargetDescription> healthy = allTargets.stream()
                .filter(t -> healthChecker.isHealthy(tgArn, t, ElbV2HealthChecker.effectivePort(t, tg)))
                .collect(Collectors.toList());
        List<TargetDescription> candidates = healthy.isEmpty() ? allTargets : healthy;
        if (candidates.isEmpty()) {
            req.response().setStatusCode(503).end("No targets available");
            return;
        }
        AtomicInteger counter = rrCounters.computeIfAbsent(tgArn, k -> new AtomicInteger(0));
        int idx = Math.abs(counter.getAndIncrement() % candidates.size());
        TargetDescription target = candidates.get(idx);
        int targetPort = ElbV2HealthChecker.effectivePort(target, tg);
        proxyRequest(req, ElbV2TargetResolver.resolveHost(ec2Service, tg, target), targetPort,
                preserveHostHeader(listenerArn, region));
    }

    private boolean preserveHostHeader(String listenerArn, String region) {
        ListenerBinding binding = listenerBindings.get(listenerArn);
        if (binding == null) {
            return false;
        }
        LoadBalancer loadBalancer = elbV2Service.getLoadBalancer(region, binding.loadBalancerArn());
        return loadBalancer != null
                && loadBalancer.getAttributes() != null
                && Boolean.parseBoolean(loadBalancer.getAttributes().get(PRESERVE_HOST_HEADER_ATTRIBUTE));
    }

    private void invokeLambdaTarget(io.vertx.core.http.HttpServerRequest req, TargetGroup tg, String functionArn,
                                    String region) {
        if (req.isEnded()) {
            invokeLambdaWithBody(req, tg, functionArn, region, Buffer.buffer());
            return;
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        AtomicBoolean rejected = new AtomicBoolean();
        req.handler(chunk -> {
            if (rejected.get()) {
                return;
            }
            if (body.size() > MAX_LAMBDA_BODY_BYTES - chunk.length()) {
                rejected.set(true);
                req.response().setStatusCode(413).end();
                return;
            }
            body.writeBytes(chunk.getBytes());
        });
        req.exceptionHandler(error -> {
            if (rejected.compareAndSet(false, true)) {
                req.response().setStatusCode(502).end("Request body error");
            }
        });
        req.endHandler(ignored -> {
            if (rejected.get()) {
                return;
            }
            invokeLambdaWithBody(req, tg, functionArn, region, Buffer.buffer(body.toByteArray()));
        });
    }

    private void invokeLambdaWithBody(io.vertx.core.http.HttpServerRequest req, TargetGroup tg, String functionArn,
                                      String region, Buffer body) {
        boolean multiValueHeaders = multiValueHeadersEnabled(tg);
        List<Map.Entry<String, String>> requestHeaders = new ArrayList<>();
        for (Map.Entry<String, String> header : req.headers()) {
            requestHeaders.add(header);
        }
        Map<String, Object> event = buildAlbEvent(req.method().name(), req.path(), req.query(), requestHeaders,
                body != null ? body.getBytes() : new byte[0], tg.getTargetGroupArn(), multiValueHeaders);
        // Lambda invocation is synchronous and may take seconds while a cold container
        // boots and polls the Runtime API. The Runtime API itself runs on Vert.x event
        // loops, so blocking the listener's event loop here would deadlock the runtime
        // and the function would time out. Offload to a worker thread, same as WebSocket.
        // ordered=false so independent ALB requests run in parallel on the worker pool.
        vertx.<InvokeResult>executeBlocking(() -> {
            byte[] payload = objectMapper.writeValueAsBytes(event);
            return lambdaService.invoke(region, functionArn, payload, InvocationType.RequestResponse);
        }, false).onSuccess(result -> {
            try {
                writeLambdaResponse(req, result, multiValueHeaders);
            } catch (Exception e) {
                LOG.errorf(e, "Error writing Lambda response for %s", functionArn);
                req.response().setStatusCode(502).end("Lambda invocation error");
            }
        }).onFailure(e -> {
            LOG.errorf(e, "Error invoking Lambda target %s", functionArn);
            req.response().setStatusCode(502).end("Lambda invocation error");
        });
    }

    private void writeLambdaResponse(io.vertx.core.http.HttpServerRequest req, InvokeResult result,
                                     boolean multiValueHeadersEnabled) throws IOException {
        if (result.getFunctionError() != null) {
            req.response().setStatusCode(502).end("Lambda function error: " + result.getFunctionError());
            return;
        }

        if (result.getPayload() == null || result.getPayload().length == 0) {
            req.response().setStatusCode(200).end();
            return;
        }
        Map<String, Object> lambdaResp = objectMapper.readValue(result.getPayload(),
                new TypeReference<Map<String, Object>>() {});

        Object responseBody = lambdaResp.get("body");
        Boolean isBase64 = (Boolean) lambdaResp.get("isBase64Encoded");
        byte[] decodedBody = null;
        String textBody = null;
        if (responseBody != null) {
            if (Boolean.TRUE.equals(isBase64)) {
                decodedBody = Base64.getDecoder().decode(String.valueOf(responseBody));
                if (decodedBody.length > MAX_LAMBDA_BODY_BYTES) {
                    req.response().setStatusCode(502).end();
                    return;
                }
            } else {
                textBody = String.valueOf(responseBody);
                if (textBody.getBytes(StandardCharsets.UTF_8).length > MAX_LAMBDA_BODY_BYTES) {
                    req.response().setStatusCode(502).end();
                    return;
                }
            }
        }

        int statusCode = 200;
        Object sc = lambdaResp.get("statusCode");
        if (sc != null) {
            statusCode = ((Number) sc).intValue();
        }
        req.response().setStatusCode(statusCode);

        for (Map.Entry<String, String> header : lambdaResponseHeaders(lambdaResp, multiValueHeadersEnabled)) {
            req.response().headers().add(header.getKey(), header.getValue());
        }

        if (responseBody == null) {
            req.response().end();
        } else if (decodedBody != null) {
            req.response().end(Buffer.buffer(decodedBody));
        } else {
            req.response().end(textBody);
        }
    }

    static boolean multiValueHeadersEnabled(TargetGroup tg) {
        return tg != null && tg.getAttributes() != null
                && Boolean.parseBoolean(tg.getAttributes().get(LAMBDA_MULTI_VALUE_HEADERS_ATTRIBUTE));
    }

    /**
     * Builds the Lambda target event documented for Application Load Balancers. The target group's
     * {@code lambda.multi_value_headers.enabled} attribute selects exactly one representation:
     * {@code headers}/{@code queryStringParameters} (duplicate names keep the last value) or
     * {@code multiValueHeaders}/{@code multiValueQueryStringParameters}. Header names are
     * lowercased and query parameters are passed through without URL-decoding, as ALB does.
     */
    static Map<String, Object> buildAlbEvent(String method, String path, String query,
                                             List<Map.Entry<String, String>> requestHeaders, byte[] body,
                                             String targetGroupArn, boolean multiValueHeaders) {
        Map<String, Object> event = new LinkedHashMap<>();
        Map<String, Object> elb = new LinkedHashMap<>();
        elb.put("targetGroupArn", targetGroupArn != null ? targetGroupArn : "");
        event.put("requestContext", Map.of("elb", elb));
        event.put("httpMethod", method);
        event.put("path", path != null && !path.isEmpty() ? path : "/");

        Map<String, String> queryParams = new LinkedHashMap<>();
        Map<String, List<String>> multiValueQueryParams = new LinkedHashMap<>();
        if (query != null && !query.isEmpty()) {
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String key = eq >= 0 ? pair.substring(0, eq) : pair;
                String val = eq >= 0 ? pair.substring(eq + 1) : "";
                queryParams.put(key, val);
                multiValueQueryParams.computeIfAbsent(key, k -> new ArrayList<>()).add(val);
            }
        }

        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, List<String>> multiValueHeaderMap = new LinkedHashMap<>();
        String contentType = null;
        for (Map.Entry<String, String> entry : requestHeaders) {
            String key = entry.getKey().toLowerCase();
            headers.put(key, entry.getValue());
            multiValueHeaderMap.computeIfAbsent(key, k -> new ArrayList<>()).add(entry.getValue());
            if ("content-type".equals(key)) {
                contentType = entry.getValue();
            }
        }

        if (multiValueHeaders) {
            event.put("multiValueQueryStringParameters", multiValueQueryParams);
            event.put("multiValueHeaders", multiValueHeaderMap);
        } else {
            event.put("queryStringParameters", queryParams);
            event.put("headers", headers);
        }

        boolean isBase64 = false;
        String bodyStr = "";
        if (body != null && body.length > 0) {
            if (contentType != null && !contentType.startsWith("text/") && !contentType.contains("json")
                    && !contentType.contains("xml") && !contentType.contains("form")) {
                bodyStr = Base64.getEncoder().encodeToString(body);
                isBase64 = true;
            } else {
                bodyStr = new String(body, StandardCharsets.UTF_8);
            }
        }
        event.put("body", bodyStr);
        event.put("isBase64Encoded", isBase64);

        return event;
    }

    /**
     * Response headers the load balancer writes back. With multi-value headers enabled ALB reads
     * {@code multiValueHeaders}, otherwise {@code headers}; framing headers are dropped because
     * the load balancer frames the body itself.
     */
    static List<Map.Entry<String, String>> lambdaResponseHeaders(Map<String, Object> lambdaResp,
                                                                  boolean multiValueHeadersEnabled) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        if (multiValueHeadersEnabled) {
            if (lambdaResp.get("multiValueHeaders") instanceof Map<?, ?> mvh) {
                for (Map.Entry<?, ?> entry : mvh.entrySet()) {
                    String name = String.valueOf(entry.getKey());
                    if (isLambdaResponseFramingHeader(name)) {
                        continue;
                    }
                    if (entry.getValue() instanceof List<?> values) {
                        for (Object value : values) {
                            if (value != null) {
                                out.add(Map.entry(name, String.valueOf(value)));
                            }
                        }
                    }
                }
            }
            return out;
        }
        if (lambdaResp.get("headers") instanceof Map<?, ?> headerMap) {
            for (Map.Entry<?, ?> entry : headerMap.entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (isLambdaResponseFramingHeader(name) || entry.getValue() == null) {
                    continue;
                }
                out.add(Map.entry(name, String.valueOf(entry.getValue())));
            }
        }
        return out;
    }

    private static boolean isLambdaResponseFramingHeader(String name) {
        return LAMBDA_RESPONSE_FRAMING_HEADERS.contains(name.toLowerCase());
    }

    private String resolveTgArn(Action action) {
        if (action.getTargetGroupArn() != null) {
            return action.getTargetGroupArn();
        }
        List<Action.TargetGroupTuple> tuples = action.getTargetGroups();
        if (tuples == null || tuples.isEmpty()) {
            return null;
        }
        double total = tuples.stream().mapToDouble(t -> t.getWeight() != null ? t.getWeight() : 1).sum();
        double roll = Math.random() * total;
        double cumulative = 0;
        for (Action.TargetGroupTuple tuple : tuples) {
            cumulative += (tuple.getWeight() != null ? tuple.getWeight() : 1);
            if (roll < cumulative) {
                return tuple.getTargetGroupArn();
            }
        }
        return tuples.get(tuples.size() - 1).getTargetGroupArn();
    }

    private void proxyRequest(io.vertx.core.http.HttpServerRequest req, String host, int port,
                              boolean preserveHostHeader) {
        req.pause();
        if (ElbV2TargetResolver.isIpLiteral(host)) {
            try {
                proxyRequestTo(req, ElbV2TargetResolver.resolveCheckedAddress(host), host, port, preserveHostHeader);
            } catch (IOException e) {
                rejectTarget(req, host, e);
            }
            return;
        }
        vertx.<String>executeBlocking(() -> ElbV2TargetResolver.resolveCheckedAddress(host))
                .onSuccess(address -> proxyRequestTo(req, address, host, port, preserveHostHeader))
                .onFailure(err -> rejectTarget(req, host, err));
    }

    private void rejectTarget(HttpServerRequest req, String host, Throwable err) {
        LOG.warnv("Refusing to proxy to target {0}: {1}", host, err.getMessage());
        req.resume();
        req.response().setStatusCode(503).end("Service unavailable");
    }

    private void proxyRequestTo(HttpServerRequest req, String address, String host, int port,
                                boolean preserveHostHeader) {
        RequestOptions opts = new RequestOptions()
                .setHost(address)
                .setPort(port)
                .setURI(req.uri())
                .setMethod(req.method());
        proxyClient.request(opts)
                .onSuccess(clientReq -> {
                    req.headers().forEach(entry -> {
                        if (!HOP_BY_HOP_HEADERS.contains(entry.getKey().toLowerCase())) {
                            clientReq.putHeader(entry.getKey(), entry.getValue());
                        }
                    });
                    if (!preserveHostHeader) {
                        clientReq.putHeader("Host", host + ":" + port);
                    }
                    AtomicBoolean responseStarted = new AtomicBoolean();
                    AtomicBoolean requestFailed = new AtomicBoolean();
                    clientReq.response().onSuccess(resp -> {
                        if (requestFailed.get()) {
                            return;
                        }
                        responseStarted.set(true);
                        req.response().setStatusCode(resp.statusCode());
                        resp.headers().forEach(entry -> {
                            if (!HOP_BY_HOP_HEADERS.contains(entry.getKey().toLowerCase())) {
                                req.response().putHeader(entry.getKey(), entry.getValue());
                            }
                        });
                        if (resp.getHeader("Content-Length") == null) {
                            req.response().setChunked(true);
                        }
                        resp.pipeTo(req.response());
                        resp.exceptionHandler(error -> {
                            if (req.response().headWritten()) {
                                req.response().close();
                            } else {
                                req.response().setStatusCode(502).end("Body error");
                            }
                        });
                    }).onFailure(error -> {
                        vertx.runOnContext(ignored -> {
                            if (requestFailed.compareAndSet(false, true)) {
                                if (responseStarted.get() || req.response().headWritten()) {
                                    req.response().close();
                                } else {
                                    req.response().setStatusCode(502).end("Bad gateway");
                                }
                            }
                        });
                    });

                    clientReq.send(req);
                    req.resume();
                })
                .onFailure(err -> {
                    req.resume();
                    req.response().setStatusCode(503).end("Service unavailable");
                });
    }

    private void executeRedirect(io.vertx.core.http.HttpServerRequest req, Action action) {
        String reqHost = req.host();
        String reqPort = "";
        if (reqHost != null && reqHost.contains(":")) {
            String[] parts = reqHost.split(":", 2);
            reqHost = parts[0];
            reqPort = parts[1];
        }
        String reqPath = req.path() != null ? req.path() : "/";
        String reqQuery = req.query();
        String reqProtocol = "HTTP";

        String protocol = action.getRedirectProtocol() != null ? action.getRedirectProtocol() : reqProtocol;
        String host = action.getRedirectHost() != null ? action.getRedirectHost() : reqHost;
        String portStr = action.getRedirectPort() != null ? action.getRedirectPort() : reqPort;
        String path = action.getRedirectPath() != null ? action.getRedirectPath() : reqPath;
        String query = action.getRedirectQuery() != null ? action.getRedirectQuery() : (reqQuery != null ? reqQuery : "");

        final String finalReqHost = reqHost;
        final String finalReqPort = reqPort;

        protocol = substitute(protocol, finalReqHost, finalReqPort, reqPath, reqProtocol, reqQuery);
        host = substitute(host, finalReqHost, finalReqPort, reqPath, reqProtocol, reqQuery);
        portStr = substitute(portStr, finalReqHost, finalReqPort, reqPath, reqProtocol, reqQuery);
        path = substitute(path, finalReqHost, finalReqPort, reqPath, reqProtocol, reqQuery);
        query = substitute(query, finalReqHost, finalReqPort, reqPath, reqProtocol, reqQuery);

        StringBuilder location = new StringBuilder(protocol.toLowerCase()).append("://").append(host);
        if (!portStr.isEmpty()) {
            location.append(":").append(portStr);
        }
        location.append(path);
        if (!query.isEmpty()) {
            location.append("?").append(query);
        }

        int statusCode = "HTTP_301".equals(action.getRedirectStatusCode()) ? 301 : 302;
        req.response()
                .setStatusCode(statusCode)
                .putHeader("Location", location.toString())
                .end();
    }

    private String substitute(String template, String host, String port, String path, String protocol, String query) {
        if (template == null) {
            return "";
        }
        String result = template
                .replace("#{host}", host != null ? host : "")
                .replace("#{port}", port != null ? port : "")
                .replace("#{path}", path != null ? path : "/")
                .replace("#{protocol}", protocol != null ? protocol : "HTTP");
        if (query != null && !query.isEmpty()) {
            result = result.replace("#{query}", query);
        } else {
            result = result.replace("#{query}", "");
        }
        return result;
    }

    private void executeFixedResponse(io.vertx.core.http.HttpServerRequest req, Action action) {
        int statusCode = 200;
        try {
            if (action.getFixedResponseStatusCode() != null) {
                statusCode = Integer.parseInt(action.getFixedResponseStatusCode());
            }
        } catch (NumberFormatException ignored) {
        }
        req.response().setStatusCode(statusCode);
        if (action.getFixedResponseContentType() != null) {
            req.response().putHeader("Content-Type", action.getFixedResponseContentType());
        }
        String body = action.getFixedResponseMessageBody() != null ? action.getFixedResponseMessageBody() : "";
        req.response().end(body);
    }

    private List<CompiledRule> compileRules(List<Rule> rules) {
        return rules.stream()
                .map(CompiledRule::new)
                .collect(Collectors.toList());
    }

    private Action getRoutingAction(Rule rule) {
        List<Action> actions = rule.getActions();
        if (actions == null || actions.isEmpty()) {
            return null;
        }
        Action last = null;
        for (Action a : actions) {
            String type = a.getType();
            if ("forward".equals(type) || "redirect".equals(type) || "fixed-response".equals(type)) {
                last = a;
            }
        }
        return last;
    }

    private static boolean globMatches(String pattern, String text) {
        if (text == null) {
            return false;
        }
        StringBuilder regex = new StringBuilder("(?i)");
        for (char c : pattern.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.matches(regex.toString(), text);
    }

    private class CompiledRule {
        final Rule rule;
        final Action action;

        CompiledRule(Rule rule) {
            this.rule = rule;
            this.action = getRoutingAction(rule);
        }

        boolean matches(io.vertx.core.http.HttpServerRequest req) {
            if (rule.isDefault()) {
                return true;
            }
            for (RuleCondition condition : rule.getConditions()) {
                if (!matchesCondition(condition, req)) {
                    return false;
                }
            }
            return true;
        }

        private boolean matchesCondition(RuleCondition condition, io.vertx.core.http.HttpServerRequest req) {
            String field = condition.getField();
            if (field == null) {
                return true;
            }
            return switch (field) {
                case "host-header" -> {
                    List<String> patterns = condition.getHostHeaderValues().isEmpty()
                            ? condition.getValues()
                            : condition.getHostHeaderValues();
                    String host = req.host();
                    if (host != null && host.contains(":")) {
                        host = host.substring(0, host.indexOf(':'));
                    }
                    String effectiveHost = host;
                    yield patterns.stream().anyMatch(p -> globMatches(p, effectiveHost));
                }
                case "path-pattern" -> {
                    List<String> patterns = condition.getPathPatternValues().isEmpty()
                            ? condition.getValues()
                            : condition.getPathPatternValues();
                    String path = req.path();
                    yield patterns.stream().anyMatch(p -> globMatches(p, path));
                }
                case "http-header" -> {
                    String headerName = condition.getHttpHeaderName();
                    if (headerName == null) {
                        yield true;
                    }
                    String headerValue = req.getHeader(headerName);
                    yield condition.getHttpHeaderValues().stream().anyMatch(p -> globMatches(p, headerValue));
                }
                case "http-request-method" -> {
                    String method = req.method().name();
                    yield condition.getHttpMethodValues().stream()
                            .anyMatch(m -> m.equalsIgnoreCase(method));
                }
                case "query-string" -> {
                    String queryString = req.query();
                    Map<String, String> queryParams = parseQueryString(queryString);
                    yield condition.getQueryStringValues().stream().allMatch(pair -> {
                        String key = pair.getKey();
                        String valuePattern = pair.getValue();
                        if (key == null) {
                            return queryParams.values().stream().anyMatch(v -> globMatches(valuePattern, v));
                        }
                        String paramValue = queryParams.get(key);
                        return paramValue != null && globMatches(valuePattern, paramValue);
                    });
                }
                case "source-ip" -> true;
                default -> true;
            };
        }

        private Map<String, String> parseQueryString(String query) {
            Map<String, String> params = new java.util.LinkedHashMap<>();
            if (query == null || query.isEmpty()) {
                return params;
            }
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq >= 0) {
                    params.put(pair.substring(0, eq), pair.substring(eq + 1));
                } else {
                    params.put(pair, "");
                }
            }
            return params;
        }
    }
}
