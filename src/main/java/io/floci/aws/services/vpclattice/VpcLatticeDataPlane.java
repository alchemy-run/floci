package io.floci.aws.services.vpclattice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.SigV4CredentialScope;
import io.floci.aws.core.common.dns.ContainerEndpoints;
import io.floci.aws.core.common.dns.DnsClientVpcSource;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.firehose.FirehoseService;
import io.floci.aws.services.firehose.model.Record;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.CallerContext;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.s3.S3Service;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.JdkSSLEngineOptions;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509ExtendedKeyManager;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * VPC Lattice's data plane. Every listener port has a listener in Floci's process (HTTP, HTTPS with certificates
 * from Floci's CA, or TLS passthrough), and a service that clients can reach (it is in a service network a VPC is
 * associated with) is relayed to them on its DNS names and listener ports, as Lattice answers them inside associated
 * VPCs.
 *
 * <p>A request is served as Lattice serves it: the client's VPC (when Floci knows it) must be associated with a
 * service network the service is in; networks and services with {@code AWS_IAM} auth require their auth policies to
 * allow {@code vpc-lattice-svcs:Invoke} for the caller (SigV4 signed, or anonymous); the listener's rules are matched
 * by priority on method, path and headers; a forward picks a target group by weight and a healthy target (all of them
 * when none is), adds the {@code x-amzn-lattice-*} headers, and proxies to IP and instance targets, invokes Lambda
 * targets with Lattice's event (V1 or V2), or forwards to an Application Load Balancer. Each request is written to the
 * service's and network's access log subscriptions.
 */
@ApplicationScoped
public class VpcLatticeDataPlane {

    private static final Logger LOG = Logger.getLogger(VpcLatticeDataPlane.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter LOG_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd")
            .withZone(ZoneOffset.UTC);

    /** A Lattice service as the data plane routes it. */
    record Route(String account, String region, ObjectNode service) {
    }

    private final VpcLatticeService lattice;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final Vertx vertx;
    private final Provider<ContainerEndpoints> endpoints;
    private final Instance<DnsClientVpcSource> clientVpcs;
    private final Provider<LambdaService> lambda;
    private final Provider<Ec2Service> ec2;
    private final Provider<ElbV2Service> elbv2;
    private final Provider<IamService> iam;
    private final Provider<IamPolicyEvaluator> policies;
    private final Provider<CloudWatchLogsService> logs;
    private final Provider<S3Service> s3;
    private final Provider<FirehoseService> firehose;
    private final Provider<FlociCertificateAuthority> certificates;
    private final CertificateGenerator certificateParser = new CertificateGenerator();
    /** Floci's listener for each Lattice listener port and protocol, by {@code protocol:port}. */
    private final Map<String, Object> servers = new ConcurrentHashMap<>();
    private final Map<String, Integer> serverPorts = new ConcurrentHashMap<>();
    /** The ports each relayed hostname has, to restart its relay only when they change. */
    private final Map<String, Map<Integer, Integer>> relayed = new ConcurrentHashMap<>();
    private final Map<String, Route> hosts = new ConcurrentHashMap<>();
    private final Map<String, KeyMaterial> keys = new ConcurrentHashMap<>();
    private final Map<String, Integer> roundRobin = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vpc-lattice-data-plane");
        thread.setDaemon(true);
        return thread;
    });
    private volatile HttpClient client;
    private volatile NetClient tcpClient;

    private record KeyMaterial(X509Certificate[] chain, PrivateKey key) {
    }

    @Inject
    public VpcLatticeDataPlane(VpcLatticeService lattice, ObjectMapper mapper, EmulatorConfig config, Vertx vertx,
                               Provider<ContainerEndpoints> endpoints, Instance<DnsClientVpcSource> clientVpcs,
                               Provider<LambdaService> lambda, Provider<Ec2Service> ec2,
                               Provider<ElbV2Service> elbv2, Provider<IamService> iam,
                               Provider<IamPolicyEvaluator> policies, Provider<CloudWatchLogsService> logs,
                               Provider<S3Service> s3, Provider<FirehoseService> firehose,
                               Provider<FlociCertificateAuthority> certificates) {
        this.lattice = lattice;
        this.mapper = mapper;
        this.config = config;
        this.vertx = vertx;
        this.endpoints = endpoints;
        this.clientVpcs = clientVpcs;
        this.lambda = lambda;
        this.ec2 = ec2;
        this.elbv2 = elbv2;
        this.iam = iam;
        this.policies = policies;
        this.logs = logs;
        this.s3 = s3;
        this.firehose = firehose;
        this.certificates = certificates;
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!config.services().vpcLattice().enabled()) {
            return;
        }
        client = vertx.createHttpClient(new HttpClientOptions().setTrustAll(true).setVerifyHost(false)
                .setConnectTimeout(5000));
        tcpClient = vertx.createNetClient();
        executor.scheduleWithFixedDelay(this::reconcile, 1, 1, TimeUnit.SECONDS);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        executor.shutdownNow();
        for (String host : List.copyOf(relayed.keySet())) {
            release(host);
        }
    }

    /** The port of Floci's listener for a Lattice listener port, or empty when there is none (yet). */
    public Optional<Integer> flociPort(String protocol, int listenerPort) {
        return Optional.ofNullable(serverPorts.get(protocol + ":" + listenerPort));
    }

    // ---------- reconcile ----------

    /** Brings listeners, host routes and relays in line with the services and their listeners. */
    void reconcile() {
        try {
            Map<String, Route> routes = new HashMap<>();
            Map<String, Map<Integer, String>> wanted = new HashMap<>();
            Map<String, Boolean> reachable = new HashMap<>();
            List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> all = lattice.allResources();
            for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : all) {
                ObjectNode service = entry.value();
                if (!VpcLatticeService.SERVICE.equals(service.path("_kind").asText())
                        || !"ACTIVE".equals(service.path("status").asText())) {
                    continue;
                }
                String region = entry.key().substring(0, entry.key().indexOf('/'));
                Route route = new Route(entry.accountId(), region, service);
                Map<Integer, String> ports = new HashMap<>();
                for (AccountAwareStorageBackend.AccountEntry<ObjectNode> other : all) {
                    ObjectNode listener = other.value();
                    if (VpcLatticeService.LISTENER.equals(listener.path("_kind").asText())
                            && entry.accountId().equals(other.accountId())
                            && service.path("id").asText().equals(listener.path("serviceId").asText())) {
                        ports.put(listener.path("port").asInt(), listener.path("protocol").asText());
                    }
                }
                boolean inNetwork = isReachable(all, entry.accountId(), service.path("id").asText());
                for (String host : hostnames(service)) {
                    routes.put(host, route);
                    wanted.put(host, ports);
                    reachable.put(host, inNetwork);
                }
            }
            hosts.keySet().retainAll(routes.keySet());
            hosts.putAll(routes);
            for (Map<Integer, String> ports : wanted.values()) {
                ports.forEach((port, protocol) -> ensureServer(protocol, port));
            }
            for (Map.Entry<String, Map<Integer, String>> host : wanted.entrySet()) {
                Map<Integer, Integer> relay = new HashMap<>();
                host.getValue().forEach((port, protocol) -> {
                    Integer floci = serverPorts.get(protocol + ":" + port);
                    if (floci != null) {
                        relay.put(port, floci);
                    }
                });
                if (Boolean.TRUE.equals(reachable.get(host.getKey())) && !relay.isEmpty()) {
                    if (!relay.equals(relayed.get(host.getKey()))) {
                        relay(host.getKey(), relay);
                    }
                } else if (relayed.containsKey(host.getKey())) {
                    release(host.getKey());
                }
            }
            for (String host : List.copyOf(relayed.keySet())) {
                if (!wanted.containsKey(host)) {
                    release(host);
                }
            }
        } catch (RuntimeException e) {
            LOG.debugv(e, "VPC Lattice data plane reconcile failed");
        }
    }

    private static List<String> hostnames(ObjectNode service) {
        List<String> out = new ArrayList<>();
        String dns = service.path("dnsEntry").path("domainName").asText(null);
        if (dns != null) {
            out.add(dns.toLowerCase(Locale.ROOT));
        }
        if (service.hasNonNull("customDomainName")) {
            out.add(service.get("customDomainName").asText().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** A service is reachable when it is in a service network that a VPC is associated with. */
    private static boolean isReachable(List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> all, String account,
                                       String serviceId) {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> association : all) {
            ObjectNode node = association.value();
            if (!account.equals(association.accountId())
                    || !VpcLatticeService.SERVICE_ASSOCIATION.equals(node.path("_kind").asText())
                    || !serviceId.equals(node.path("serviceId").asText())
                    || !"ACTIVE".equals(node.path("status").asText())) {
                continue;
            }
            String network = node.path("serviceNetworkId").asText();
            for (AccountAwareStorageBackend.AccountEntry<ObjectNode> vpc : all) {
                if (account.equals(vpc.accountId())
                        && VpcLatticeService.VPC_ASSOCIATION.equals(vpc.value().path("_kind").asText())
                        && network.equals(vpc.value().path("serviceNetworkId").asText())
                        && "ACTIVE".equals(vpc.value().path("status").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void relay(String host, Map<Integer, Integer> ports) {
        try {
            if (endpoints.get().relayToFloci(host, ports)) {
                relayed.put(host, ports);
            }
        } catch (RuntimeException e) {
            LOG.warnv("VPC Lattice could not relay {0}: {1}", host, e.getMessage());
        }
    }

    private void release(String host) {
        relayed.remove(host);
        try {
            endpoints.get().release(host);
        } catch (RuntimeException e) {
            LOG.debugv("Releasing {0} failed: {1}", host, e.getMessage());
        }
    }

    private void ensureServer(String protocol, int listenerPort) {
        String key = protocol + ":" + listenerPort;
        if (servers.containsKey(key)) {
            return;
        }
        if ("TLS_PASSTHROUGH".equals(protocol)) {
            NetServer server = vertx.createNetServer(new NetServerOptions().setHost("0.0.0.0").setPort(0)
                    .setSni(true));
            server.connectHandler(socket -> passthrough(socket, listenerPort));
            servers.put(key, server);
            server.listen().onSuccess(s -> serverPorts.put(key, s.actualPort()))
                    .onFailure(e -> {
                        servers.remove(key);
                        LOG.warnv("VPC Lattice TLS listener for port {0} failed: {1}", listenerPort, e.getMessage());
                    });
            return;
        }
        HttpServerOptions options = new HttpServerOptions().setHost("0.0.0.0").setPort(0);
        if ("HTTPS".equals(protocol)) {
            options.setSsl(true).setSni(true).setSslEngineOptions(new JdkSSLEngineOptions())
                    .setKeyCertOptions(KeyCertOptions.wrap(new SniKeyManager()));
        }
        HttpServer server = vertx.createHttpServer(options);
        server.requestHandler(request -> request.body().onSuccess(body -> serve(request, body, protocol,
                listenerPort)).onFailure(e -> request.response().setStatusCode(400).end()));
        servers.put(key, server);
        server.listen().onSuccess(s -> serverPorts.put(key, s.actualPort()))
                .onFailure(e -> {
                    servers.remove(key);
                    LOG.warnv("VPC Lattice listener for port {0} failed: {1}", listenerPort, e.getMessage());
                });
    }

    // ---------- requests ----------

    private void serve(HttpServerRequest request, Buffer body, String protocol, int listenerPort) {
        long started = System.currentTimeMillis();
        String host = String.valueOf(request.getHeader("Host")).replaceAll(":\\d+$", "").toLowerCase(Locale.ROOT);
        Route route = hosts.get(host);
        if (route == null) {
            request.response().setStatusCode(404).end("No VPC Lattice service answers " + host);
            return;
        }
        vertx.executeBlocking(() -> RequestScopes.callAs(route.account(), route.region(),
                        () -> dispatch(route, request, body, protocol, listenerPort)), false)
                .onSuccess(result -> {
                    respond(request, result);
                    accessLog(route, request, result, started);
                })
                .onFailure(e -> {
                    LOG.warnv(e, "VPC Lattice request to {0} failed", host);
                    request.response().setStatusCode(502).end("Bad Gateway");
                });
    }

    /** What the data plane answers: a status, headers and body, or a target to proxy to. */
    record Result(int status, Map<String, String> headers, byte[] body, String targetGroupArn, String target,
                  String network, String denied) {

        static Result of(int status, String body) {
            return new Result(status, Map.of("Content-Type", "text/plain"), body.getBytes(StandardCharsets.UTF_8),
                    null, null, null, null);
        }
    }

    private Result dispatch(Route route, HttpServerRequest request, Buffer body, String protocol, int listenerPort) {
        ObjectNode service = route.service();
        String serviceId = service.path("id").asText();
        Optional<DnsClientVpcSource.ClientVpc> clientVpc = clientVpc(request.remoteAddress() == null ? null
                : request.remoteAddress().hostAddress());
        ObjectNode network = network(route, serviceId, clientVpc.map(DnsClientVpcSource.ClientVpc::vpcId)
                .orElse(null));
        if (clientVpc.isPresent() && network == null) {
            return Result.of(403, "The VPC " + clientVpc.get().vpcId() + " is not associated with a service "
                    + "network the service is in.");
        }
        String resource = service.path("arn").asText() + request.path();
        Optional<String> denial = authorize(route, request, network, service, resource, clientVpc);
        if (denial.isPresent()) {
            return new Result(403, Map.of("Content-Type", "application/json"), ("{\"Message\":\"" + denial.get()
                    + "\"}").getBytes(StandardCharsets.UTF_8), null, null, networkArn(network), denial.get());
        }
        ObjectNode listener = listenerFor(route, serviceId, protocol, listenerPort);
        if (listener == null) {
            return Result.of(404, "No listener");
        }
        JsonNode action = listener.path("defaultAction");
        for (ObjectNode rule : rules(route, listener.path("id").asText())) {
            if (matches(rule.path("match").path("httpMatch"), request)) {
                action = rule.path("action");
                break;
            }
        }
        if (action.has("fixedResponse")) {
            return new Result(action.path("fixedResponse").path("statusCode").asInt(), Map.of(), new byte[0], null,
                    null, networkArn(network), null);
        }
        ObjectNode group = weighted(route, action.path("forward").path("targetGroups"));
        if (group == null) {
            return Result.of(503, "No target group can take the request.");
        }
        JsonNode target = target(route, group);
        if (target == null) {
            return new Result(503, Map.of(), "No targets".getBytes(StandardCharsets.UTF_8),
                    group.path("arn").asText(), null, networkArn(network), null);
        }
        Map<String, String> latticeHeaders = new LinkedHashMap<>();
        clientVpc.ifPresent(vpc -> {
            latticeHeaders.put("x-amzn-source-vpc", vpc.vpcId());
            latticeHeaders.put("x-amzn-lattice-network", "SourceVpcArn=" + AwsArnUtils.Arn.of("ec2", vpc.region(),
                    vpc.accountId(), "vpc/" + vpc.vpcId()));
        });
        latticeHeaders.put("x-amzn-lattice-target", "ServiceArn=" + service.path("arn").asText()
                + "; ServiceNetworkArn=" + networkArn(network) + "; TargetGroupArn=" + group.path("arn").asText());
        callerPrincipal(request).ifPresent(principal -> latticeHeaders.put("x-amzn-lattice-identity",
                "Principal=" + principal));
        return switch (group.path("type").asText()) {
            case "LAMBDA" -> invokeLambda(route, request, body, group, target, network, service, clientVpc);
            default -> proxy(route, request, body, group, target, latticeHeaders, network);
        };
    }

    private Optional<DnsClientVpcSource.ClientVpc> clientVpc(String address) {
        if (address == null) {
            return Optional.empty();
        }
        for (DnsClientVpcSource source : clientVpcs) {
            try {
                Optional<DnsClientVpcSource.ClientVpc> vpc = source.vpcForClient(address);
                if (vpc.isPresent()) {
                    return vpc;
                }
            } catch (RuntimeException e) {
                LOG.debugv("Client VPC lookup failed: {0}", e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** The service network the request comes through: the one of the client's VPC, or any the service is in. */
    private ObjectNode network(Route route, String serviceId, String vpcId) {
        List<ObjectNode> networks = new ArrayList<>();
        for (ObjectNode association : lattice.all(route.region(), VpcLatticeService.SERVICE_ASSOCIATION)) {
            if (serviceId.equals(association.path("serviceId").asText())
                    && "ACTIVE".equals(association.path("status").asText())) {
                try {
                    networks.add(lattice.find(route.region(), VpcLatticeService.NETWORK,
                            association.path("serviceNetworkId").asText()));
                } catch (RuntimeException e) {
                    LOG.debugv("Network of association gone: {0}", e.getMessage());
                }
            }
        }
        if (vpcId == null) {
            return networks.isEmpty() ? null : networks.get(0);
        }
        for (ObjectNode vpcAssociation : lattice.all(route.region(), VpcLatticeService.VPC_ASSOCIATION)) {
            if (vpcId.equals(vpcAssociation.path("vpcId").asText())
                    && "ACTIVE".equals(vpcAssociation.path("status").asText())) {
                for (ObjectNode network : networks) {
                    if (network.path("id").asText().equals(vpcAssociation.path("serviceNetworkId").asText())) {
                        return network;
                    }
                }
            }
        }
        return null;
    }

    private static String networkArn(ObjectNode network) {
        return network == null ? null : network.path("arn").asText();
    }

    /** Why the request is refused by an {@code AWS_IAM} network's or service's auth policy, if it is. */
    private Optional<String> authorize(Route route, HttpServerRequest request, ObjectNode network, ObjectNode service,
                                       String resource, Optional<DnsClientVpcSource.ClientVpc> clientVpc) {
        Optional<String> principal = callerPrincipal(request);
        Map<String, List<String>> context = new HashMap<>();
        clientVpc.ifPresent(vpc -> context.put("vpc-lattice-svcs:SourceVpc", List.of(vpc.vpcId())));
        context.put("vpc-lattice-svcs:RequestMethod", List.of(request.method().name()));
        context.put("vpc-lattice-svcs:ServiceArn", List.of(service.path("arn").asText()));
        if (network != null) {
            context.put("vpc-lattice-svcs:ServiceNetworkArn", List.of(network.path("arn").asText()));
        }
        for (ObjectNode owner : network == null ? List.of(service) : List.of(network, service)) {
            if (!"AWS_IAM".equals(owner.path("authType").asText())) {
                continue;
            }
            String policy = owner.path("_authPolicy").path("policy").asText(null);
            if (policy == null) {
                return Optional.of("AccessDeniedException: " + owner.path("arn").asText() + " has no auth policy.");
            }
            IamPolicyEvaluator.ResourcePolicyDecision decision = policies.get().evaluateResourcePolicy(
                    List.of(policy), principal.orElse(null), "vpc-lattice-svcs:Invoke", resource, context);
            if (decision != IamPolicyEvaluator.ResourcePolicyDecision.ALLOW
                    && decision != IamPolicyEvaluator.ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER
                    && decision != IamPolicyEvaluator.ResourcePolicyDecision.ALLOW_ACCOUNT) {
                return Optional.of("AccessDeniedException: User: " + principal.orElse("anonymous")
                        + " is not authorized to perform: vpc-lattice-svcs:Invoke on resource: " + resource);
            }
        }
        return Optional.empty();
    }

    /** The IAM principal a SigV4-signed request comes from. */
    private Optional<String> callerPrincipal(HttpServerRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null) {
            return Optional.empty();
        }
        return SigV4CredentialScope.accessKeyId(authorization).map(key -> {
            CallerContext caller = iam.get().resolveCallerContext(key);
            return caller == null ? null : caller.principalArn();
        });
    }

    private ObjectNode listenerFor(Route route, String serviceId, String protocol, int port) {
        for (ObjectNode listener : lattice.all(route.region(), VpcLatticeService.LISTENER)) {
            if (serviceId.equals(listener.path("serviceId").asText()) && port == listener.path("port").asInt()
                    && protocol.equals(listener.path("protocol").asText())) {
                return listener;
            }
        }
        return null;
    }

    private List<ObjectNode> rules(Route route, String listenerId) {
        return lattice.all(route.region(), VpcLatticeService.RULE).stream()
                .filter(r -> listenerId.equals(r.path("listenerId").asText()))
                .sorted(Comparator.comparingInt(r -> r.path("priority").asInt())).toList();
    }

    static boolean matches(JsonNode match, HttpServerRequest request) {
        if (match.hasNonNull("method") && !match.get("method").asText().equalsIgnoreCase(request.method().name())) {
            return false;
        }
        JsonNode path = match.path("pathMatch");
        if (path.isObject()) {
            boolean caseSensitive = path.path("caseSensitive").asBoolean(true);
            String actual = caseSensitive ? request.path() : request.path().toLowerCase(Locale.ROOT);
            JsonNode type = path.path("match");
            if (type.has("exact")) {
                String wanted = caseSensitive ? type.get("exact").asText() : type.get("exact").asText()
                        .toLowerCase(Locale.ROOT);
                if (!actual.equals(wanted)) {
                    return false;
                }
            } else if (type.has("prefix")) {
                String wanted = caseSensitive ? type.get("prefix").asText() : type.get("prefix").asText()
                        .toLowerCase(Locale.ROOT);
                if (!actual.startsWith(wanted)) {
                    return false;
                }
            }
        }
        for (JsonNode header : match.path("headerMatches")) {
            String value = request.getHeader(header.path("name").asText());
            if (value == null) {
                return false;
            }
            boolean caseSensitive = header.path("caseSensitive").asBoolean(false);
            String actual = caseSensitive ? value : value.toLowerCase(Locale.ROOT);
            JsonNode type = header.path("match");
            String wanted = type.has("exact") ? type.get("exact").asText() : type.has("prefix")
                    ? type.get("prefix").asText() : type.path("contains").asText();
            wanted = caseSensitive ? wanted : wanted.toLowerCase(Locale.ROOT);
            boolean ok = type.has("exact") ? actual.equals(wanted) : type.has("prefix") ? actual.startsWith(wanted)
                    : actual.contains(wanted);
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** A target group of the forward, picked by weight. */
    private ObjectNode weighted(Route route, JsonNode targetGroups) {
        List<ObjectNode> candidates = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int total = 0;
        for (JsonNode weighted : targetGroups) {
            int weight = weighted.path("weight").asInt(1);
            if (weight <= 0) {
                continue;
            }
            try {
                candidates.add(lattice.find(route.region(), VpcLatticeService.TARGET_GROUP,
                        weighted.path("targetGroupIdentifier").asText()));
                weights.add(weight);
                total += weight;
            } catch (RuntimeException e) {
                LOG.debugv("Target group gone: {0}", e.getMessage());
            }
        }
        if (total == 0) {
            return null;
        }
        int pick = RANDOM.nextInt(total);
        for (int i = 0; i < candidates.size(); i++) {
            pick -= weights.get(i);
            if (pick < 0) {
                return candidates.get(i);
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /** A target of the group: the healthy ones in turn, or every registered one when none is healthy. */
    private JsonNode target(Route route, ObjectNode group) {
        List<JsonNode> registered = new ArrayList<>();
        List<JsonNode> healthy = new ArrayList<>();
        boolean checked = group.path("config").path("healthCheck").path("enabled").asBoolean(false);
        for (JsonNode target : group.path("_targets")) {
            if (target.has("drainUntil")) {
                continue;
            }
            registered.add(target);
            if (!checked || "HEALTHY".equals(target.path("health").asText())) {
                healthy.add(target);
            }
        }
        List<JsonNode> pool = healthy.isEmpty() ? registered : healthy;
        if (pool.isEmpty()) {
            return null;
        }
        int next = roundRobin.merge(route.account() + "/" + group.path("id").asText(), 1, Integer::sum);
        return pool.get(Math.floorMod(next, pool.size()));
    }

    private Result proxy(Route route, HttpServerRequest request, Buffer body, ObjectNode group, JsonNode target,
                         Map<String, String> latticeHeaders, ObjectNode network) {
        String host = target.path("id").asText();
        int port = target.path("port").asInt();
        String hostHeader = request.getHeader("Host");
        if ("INSTANCE".equals(group.path("type").asText())) {
            String bridge = Optional.ofNullable(ec2.get().findInstanceById(route.account(), host))
                    .map(instance -> instance.getContainerBridgeIp()).orElse(null);
            if (bridge != null && !bridge.isBlank()) {
                host = bridge;
            }
        } else if ("ALB".equals(group.path("type").asText())) {
            String dns = elbv2.get().describeLoadBalancers(route.region(), List.of(host), List.of(), null, null)
                    .stream().findFirst().map(lb -> lb.getDnsName()).orElse(null);
            hostHeader = dns != null ? dns : hostHeader;
            host = "127.0.0.1";
            port = group.path("config").path("port").asInt();
        }
        boolean tls = "HTTPS".equals(group.path("config").path("protocol").asText());
        RequestOptions options = new RequestOptions().setMethod(HttpMethod.valueOf(request.method().name()))
                .setHost(host).setPort(port).setURI(request.uri()).setSsl(tls).setTimeout(30_000);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().addAll(request.headers());
        latticeHeaders.forEach(headers::set);
        if (hostHeader != null) {
            headers.set("Host", hostHeader);
        }
        options.setHeaders(headers);
        String targetLabel = host + ":" + port;
        try {
            return client.request(options).compose(outgoing -> outgoing.send(body))
                    .compose(response -> response.body().map(responseBody -> {
                        Map<String, String> out = new LinkedHashMap<>();
                        response.headers().forEach(h -> out.put(h.getKey(), h.getValue()));
                        return new Result(response.statusCode(), out, responseBody.getBytes(),
                                group.path("arn").asText(), targetLabel, networkArn(network), null);
                    })).toCompletionStage().toCompletableFuture().get(35, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new Result(502, Map.of(), ("Bad Gateway: " + e.getMessage()).getBytes(StandardCharsets.UTF_8),
                    group.path("arn").asText(), targetLabel, networkArn(network), null);
        }
    }

    /** Invokes a Lambda target with Lattice's event, in the group's event structure version. */
    private Result invokeLambda(Route route, HttpServerRequest request, Buffer body, ObjectNode group,
                                JsonNode target, ObjectNode network, ObjectNode service,
                                Optional<DnsClientVpcSource.ClientVpc> clientVpc) {
        boolean v2 = "V2".equals(group.path("config").path("lambdaEventStructureVersion").asText("V1"));
        byte[] bytes = body.getBytes();
        boolean binary = !isText(request.getHeader("Content-Type"));
        String encodedBody = binary ? Base64.getEncoder().encodeToString(bytes) : new String(bytes,
                StandardCharsets.UTF_8);
        ObjectNode event = mapper.createObjectNode();
        if (v2) {
            event.put("version", "2.0").put("path", request.path()).put("method", request.method().name());
            ObjectNode headers = event.putObject("headers");
            request.headers().forEach(h -> headers.withArray(h.getKey().toLowerCase(Locale.ROOT)).add(h.getValue()));
            ObjectNode query = event.putObject("queryStringParameters");
            request.params().forEach(p -> query.withArray(p.getKey()).add(p.getValue()));
            event.put("body", encodedBody).put("isBase64Encoded", binary);
            ObjectNode context = event.putObject("requestContext");
            if (network != null) {
                context.put("serviceNetworkArn", network.path("arn").asText());
            }
            context.put("serviceArn", service.path("arn").asText()).put("targetGroupArn", group.path("arn").asText());
            ObjectNode identity = context.putObject("identity");
            clientVpc.ifPresent(vpc -> identity.put("sourceVpcArn", AwsArnUtils.Arn.of("ec2", vpc.region(),
                    vpc.accountId(), "vpc/" + vpc.vpcId()).toString()));
            callerPrincipal(request).ifPresent(p -> identity.put("type", "AWS_IAM").put("principal", p));
            context.put("region", route.region()).put("timeEpoch", String.valueOf(Instant.now().toEpochMilli() * 1000));
        } else {
            event.put("raw_path", request.uri()).put("method", request.method().name());
            ObjectNode headers = event.putObject("headers");
            request.headers().forEach(h -> headers.put(h.getKey().toLowerCase(Locale.ROOT), h.getValue()));
            ObjectNode query = event.putObject("query_string_parameters");
            request.params().forEach(p -> query.put(p.getKey(), p.getValue()));
            event.put("body", encodedBody).put("is_base64_encoded", binary);
        }
        String function = target.path("id").asText();
        InvokeResult result;
        try {
            result = lambda.get().invoke(route.region(), function, null,
                    event.toString().getBytes(StandardCharsets.UTF_8), InvocationType.RequestResponse);
        } catch (RuntimeException e) {
            return new Result(502, Map.of(), ("Bad Gateway: " + e.getMessage()).getBytes(StandardCharsets.UTF_8),
                    group.path("arn").asText(), function, networkArn(network), null);
        }
        if (result.getFunctionError() != null) {
            return new Result(502, Map.of(), "Bad Gateway".getBytes(StandardCharsets.UTF_8),
                    group.path("arn").asText(), function, networkArn(network), null);
        }
        try {
            JsonNode response = mapper.readTree(result.getPayload() == null ? new byte[0] : result.getPayload());
            if (response == null || !response.has("statusCode")) {
                return new Result(502, Map.of(), "Bad Gateway: the function's response has no statusCode"
                        .getBytes(StandardCharsets.UTF_8), group.path("arn").asText(), function,
                        networkArn(network), null);
            }
            Map<String, String> headers = new LinkedHashMap<>();
            response.path("headers").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().isArray()
                    ? e.getValue().path(0).asText() : e.getValue().asText()));
            String text = response.path("body").asText("");
            byte[] out = response.path("isBase64Encoded").asBoolean(false) ? Base64.getDecoder().decode(text)
                    : text.getBytes(StandardCharsets.UTF_8);
            return new Result(response.get("statusCode").asInt(), headers, out, group.path("arn").asText(), function,
                    networkArn(network), null);
        } catch (Exception e) {
            return new Result(502, Map.of(), "Bad Gateway".getBytes(StandardCharsets.UTF_8),
                    group.path("arn").asText(), function, networkArn(network), null);
        }
    }

    private static boolean isText(String contentType) {
        return contentType == null || contentType.startsWith("text/") || contentType.contains("json")
                || contentType.contains("xml") || contentType.contains("x-www-form-urlencoded");
    }

    private static void respond(HttpServerRequest request, Result result) {
        request.response().setStatusCode(result.status());
        result.headers().forEach((name, value) -> {
            if (!"transfer-encoding".equalsIgnoreCase(name) && !"content-length".equalsIgnoreCase(name)
                    && !"connection".equalsIgnoreCase(name)) {
                request.response().putHeader(name, value);
            }
        });
        request.response().end(Buffer.buffer(result.body()));
    }

    // ---------- TLS passthrough ----------

    /** Relays a TLS connection, by its server name, to a TCP target of the service's listener on the port. */
    private void passthrough(NetSocket socket, int listenerPort) {
        String host = socket.indicatedServerName() == null ? null : socket.indicatedServerName()
                .toLowerCase(Locale.ROOT);
        Route route = host == null ? null : hosts.get(host);
        if (route == null) {
            socket.close();
            return;
        }
        vertx.executeBlocking(() -> RequestScopes.callAs(route.account(), route.region(), () -> {
            ObjectNode listener = listenerFor(route, route.service().path("id").asText(), "TLS_PASSTHROUGH",
                    listenerPort);
            if (listener == null) {
                return null;
            }
            ObjectNode group = weighted(route, listener.path("defaultAction").path("forward").path("targetGroups"));
            JsonNode target = group == null ? null : target(route, group);
            return target == null ? null : new String[]{target.path("id").asText(), target.path("port").asText()};
        }), false).onSuccess(target -> {
            if (target == null) {
                socket.close();
                return;
            }
            socket.pause();
            tcpClient.connect(Integer.parseInt(target[1]), target[0]).onSuccess(upstream -> {
                socket.handler(upstream::write);
                upstream.handler(socket::write);
                socket.closeHandler(v -> upstream.close());
                upstream.closeHandler(v -> socket.close());
                socket.resume();
            }).onFailure(e -> socket.close());
        }).onFailure(e -> socket.close());
    }

    // ---------- access logs ----------

    private void accessLog(Route route, HttpServerRequest request, Result result, long started) {
        long duration = System.currentTimeMillis() - started;
        executor.execute(() -> RequestScopes.runAs(route.account(), route.region(), () -> {
            String serviceId = route.service().path("id").asText();
            String networkId = result.network() == null ? null : idOf(result.network());
            for (ObjectNode subscription : lattice.all(route.region(), VpcLatticeService.ACCESS_LOG)) {
                String owner = subscription.path("resourceId").asText();
                if (!owner.equals(serviceId) && !owner.equals(networkId)) {
                    continue;
                }
                ObjectNode entry = mapper.createObjectNode();
                entry.put("startTime", Instant.ofEpochMilli(started).toString()).put("duration", duration)
                        .put("method", request.method().name()).put("requestPath", request.uri())
                        .put("protocol", request.version() == null ? "HTTP/1.1" : request.version().alpnName()
                                .toUpperCase(Locale.ROOT)).put("responseCode", result.status())
                        .put("responseBodySize", result.body().length)
                        .put("serviceArn", route.service().path("arn").asText())
                        .put("sourceIpPort", request.remoteAddress() == null ? null
                                : request.remoteAddress().hostAddress() + ":" + request.remoteAddress().port())
                        .put("userAgent", request.getHeader("User-Agent"));
                if (result.network() != null) {
                    entry.put("serviceNetworkArn", result.network());
                }
                if (result.targetGroupArn() != null) {
                    entry.put("targetGroupArn", result.targetGroupArn());
                }
                if (result.target() != null) {
                    entry.put("targetIpPort", result.target());
                }
                if (result.denied() != null) {
                    entry.put("authDeniedReason", result.denied());
                }
                write(route, subscription.path("destinationArn").asText(), owner, entry.toString());
            }
        }));
    }

    private void write(Route route, String destination, String owner, String line) {
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(destination);
            switch (arn.service()) {
                case "logs" -> {
                    String group = arn.resource().replaceFirst("^log-group:", "").replaceFirst(":\\*$", "");
                    String stream = "vpc-lattice-" + owner;
                    if (logs.get().describeLogStreams(group, stream, arn.region()).stream()
                            .noneMatch(s -> stream.equals(s.getLogStreamName()))) {
                        logs.get().createLogStream(group, stream, arn.region());
                    }
                    logs.get().putLogEvents(group, stream, List.of(Map.of("timestamp", System.currentTimeMillis(),
                            "message", line)), arn.region());
                }
                case "s3" -> {
                    String bucket = arn.resource().contains("/") ? arn.resource().substring(0,
                            arn.resource().indexOf('/')) : arn.resource();
                    String prefix = arn.resource().contains("/") ? arn.resource().substring(
                            arn.resource().indexOf('/') + 1) + "/" : "";
                    String key = prefix + "AWSLogs/" + route.account() + "/VpcLattice/AccessLogs/" + route.region()
                            + "/" + LOG_PATH.format(Instant.now()) + "/" + owner + "_" + System.currentTimeMillis()
                            + "_" + Integer.toHexString(RANDOM.nextInt()) + ".log";
                    s3.get().putObject(bucket, key, (line + "\n").getBytes(StandardCharsets.UTF_8),
                            "application/json", Map.of());
                }
                case "firehose" -> firehose.get().putRecord(arn.accountId(), arn.region(),
                        arn.resource().replaceFirst("^deliverystream/", ""),
                        new Record((line + "\n").getBytes(StandardCharsets.UTF_8)));
                default -> LOG.debugv("Unknown access log destination {0}", destination);
            }
        } catch (RuntimeException e) {
            LOG.debugv(e, "VPC Lattice could not write an access log to {0}", destination);
        }
    }

    private static String idOf(String arn) {
        return arn.substring(arn.lastIndexOf('/') + 1);
    }

    // ---------- TLS ----------

    /** Presents, per server name, a certificate for that Lattice service issued by Floci's CA. */
    private final class SniKeyManager extends X509ExtendedKeyManager {

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            if (engine == null || !"RSA".equalsIgnoreCase(keyType)) {
                return null;
            }
            String host = requestedHost(engine.getHandshakeSession());
            if (host == null || !hosts.containsKey(host)) {
                return null;
            }
            keys.computeIfAbsent(host, VpcLatticeDataPlane.this::issue);
            return host;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            KeyMaterial material = alias == null ? null : keys.get(alias);
            return material == null ? null : material.chain().clone();
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            KeyMaterial material = alias == null ? null : keys.get(alias);
            return material == null ? null : material.key();
        }
    }

    private static String requestedHost(SSLSession session) {
        if (!(session instanceof ExtendedSSLSession extended)) {
            return null;
        }
        return extended.getRequestedServerNames().stream().filter(SNIHostName.class::isInstance)
                .map(name -> ((SNIHostName) name).getAsciiName().toLowerCase(Locale.ROOT)).findFirst().orElse(null);
    }

    private KeyMaterial issue(String host) {
        CertificateGenerator.GeneratedCertificate generated = certificates.get().issueServerCertificate(host,
                List.of(host), KeyAlgorithm.RSA_2048, null);
        return new KeyMaterial(new X509Certificate[]{certificateParser.parseCertificate(generated.certificatePem()),
                certificates.get().certificate()}, certificateParser.parsePrivateKey(generated.privateKeyPem()));
    }
}
