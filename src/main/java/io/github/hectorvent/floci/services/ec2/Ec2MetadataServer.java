package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IMDS-compatible HTTP server bound to port 9169 on the Floci host.
 * EC2 guests reach this server through an authenticated link-local proxy.
 *
 * Implements IMDSv2 (token-based) and IMDSv1 (no token) — containers using the
 * standard AWS SDK credential chain will hit /latest/meta-data/iam/security-credentials/
 * to obtain temporary credentials backed by the instance's IAM instance profile.
 */
@ApplicationScoped
public class Ec2MetadataServer {

    private static final Logger LOG = Logger.getLogger(Ec2MetadataServer.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final String INSTANCE_TAGS_PREFIX = "/latest/meta-data/tags/instance/";

    private final Vertx vertx;
    private final EmulatorConfig config;
    static final String PROXY_HEADER = "X-Floci-IMDS-Capability";

    private final Ec2InstanceCredentials credentials;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, MetadataToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, GuestIdentity> capabilities = new ConcurrentHashMap<>();
    private final Map<Instance, GuestIdentity> identities = new ConcurrentHashMap<>();
    /** Source-address authentication is retained for the existing EKS relay. */
    private final Map<String, Instance> containerIpToInstance = new ConcurrentHashMap<>();

    private record GuestIdentity(Instance instance, String accountId, String containerId, String capability) {}
    private record MetadataToken(GuestIdentity identity, Instant expiresAt) {}

    private volatile HttpServer httpServer;
    private CompletableFuture<Void> starting = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> stopping = CompletableFuture.completedFuture(null);

    @Inject
    public Ec2MetadataServer(Vertx vertx, EmulatorConfig config, IamService iamService) {
        this(vertx, config, iamService, Clock.systemUTC());
    }

    Ec2MetadataServer(Vertx vertx, EmulatorConfig config, IamService iamService, Clock clock) {
        this.vertx = vertx;
        this.config = config;
        this.credentials = new Ec2InstanceCredentials(iamService);
        this.clock = clock;
    }

    /** Rotates the guest generation. The capability is delivered only through Docker's archive API. */
    public synchronized String registerProxy(Instance instance) {
        String accountId = accountId(instance);
        // A restored model object replaces the old registration of the same scoped guest.
        for (GuestIdentity previous : List.copyOf(identities.values())) {
            if (previous.instance() != instance && Objects.equals(previous.accountId(), accountId)
                    && Objects.equals(previous.instance().getRegion(), instance.getRegion())
                    && Objects.equals(previous.instance().getInstanceId(), instance.getInstanceId())) {
                unregisterInstance(previous.instance());
            }
        }
        revokeIdentity(instance);
        String capability = randomToken();
        GuestIdentity identity = new GuestIdentity(instance, accountId, instance.getDockerContainerId(), capability);
        identities.put(instance, identity);
        capabilities.put(capability, identity);
        credentials.register(instance);
        return capability;
    }

    public synchronized void unregisterProxy(Instance instance, String capability) {
        GuestIdentity identity = identities.get(instance);
        if (identity != null && Objects.equals(identity.capability(), capability)) {
            unregisterInstance(instance);
        }
    }

    private void revokeIdentity(Instance instance) {
        GuestIdentity previous = identities.remove(instance);
        if (previous != null) {
            if (previous.capability() != null) {
                capabilities.remove(previous.capability(), previous);
            }
            tokens.entrySet().removeIf(entry -> entry.getValue().identity() == previous);
        }
        credentials.unregister(instance);
    }

    private String accountId(Instance instance) {
        return instanceAccountId(instance, config == null ? null : config.defaultAccountId());
    }

    static String instanceAccountId(Instance instance, String defaultAccountId) {
        if (!instance.getNetworkInterfaces().isEmpty()
                && instance.getNetworkInterfaces().getFirst().getOwnerId() != null) {
            return instance.getNetworkInterfaces().getFirst().getOwnerId();
        }
        String profile = instance.getIamInstanceProfileArn();
        if (profile != null) {
            String[] parts = profile.split(":", 6);
            if (parts.length == 6 && parts[4].matches("[0-9]{12}")) {
                return parts[4];
            }
        }
        return defaultAccountId;
    }

    /** Called by Ec2ContainerManager after a container starts to register its IP. */
    public synchronized void registerContainer(String containerIp, String instanceId, Instance instance) {
        if (containerIp != null && !containerIp.isBlank()) {
            identities.computeIfAbsent(instance, key ->
                    new GuestIdentity(instance, accountId(instance), instance.getDockerContainerId(), null));
            credentials.register(instance);
            containerIpToInstance.put(containerIp, instance);
            LOG.debugv("IMDS: registered container {0} → instance {1}", containerIp, instanceId);
        }
    }

    /** Called by Ec2ContainerManager when a container is terminated. */
    public void unregisterContainer(String containerIp, Instance instance) {
        if (containerIp != null && instance != null) {
            containerIpToInstance.remove(containerIp, instance);
        }
    }

    /** Reconcile every Docker attachment without retaining stale addresses after restart. */
    public void reconcileContainerAddresses(Set<String> addresses, Instance instance) {
        for (String address : addresses) {
            registerContainer(address, instance.getInstanceId(), instance);
        }
        containerIpToInstance.entrySet().removeIf(entry ->
                entry.getValue() == instance && !addresses.contains(entry.getKey()));
    }

    public synchronized void unregisterInstance(Instance instance) {
        if (instance != null) {
            revokeIdentity(instance);
            containerIpToInstance.entrySet().removeIf(entry -> entry.getValue() == instance);
        }
    }

    Optional<Instance> registeredContainer(String containerIp) {
        return Optional.ofNullable(containerIpToInstance.get(containerIp));
    }

    public synchronized CompletableFuture<Void> start() {
        if (!stopping.isDone()) {
            return stopping.thenCompose(ignored -> start());
        }
        if (stopping.isCompletedExceptionally()) {
            return stopping;
        }
        if (httpServer != null) {
            return starting;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        starting = future;
        int port = config.services().ec2().imdsPort();

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        // IMDSv2 token endpoint
        router.put("/latest/api/token").handler(this::handleToken);

        // Metadata endpoints
        router.get("/latest/meta-data/instance-id").handler(ctx -> handleText(ctx, inst -> inst.getInstanceId()));
        router.get("/latest/meta-data/ami-id").handler(ctx -> handleText(ctx, inst -> inst.getImageId()));
        router.get("/latest/meta-data/instance-type").handler(ctx -> handleText(ctx, inst -> inst.getInstanceType()));
        router.get("/latest/meta-data/local-ipv4").handler(ctx -> handleText(ctx, inst -> inst.getPrivateIpAddress()));
        router.get("/latest/meta-data/public-ipv4").handler(ctx -> handleText(ctx, inst -> inst.getPublicIpAddress()));
        router.get("/latest/meta-data/public-hostname").handler(ctx -> handleText(ctx, inst -> inst.getPublicDnsName()));
        router.get("/latest/meta-data/local-hostname").handler(ctx -> handleText(ctx, inst -> inst.getPrivateDnsName()));
        router.get("/latest/meta-data/hostname").handler(ctx -> handleText(ctx, inst -> inst.getPrivateDnsName()));
        router.get("/latest/meta-data/mac").handler(ctx -> handleMac(ctx));
        router.get("/latest/meta-data/security-groups").handler(ctx -> handleSecurityGroups(ctx));
        router.get("/latest/meta-data/placement/availability-zone").handler(ctx -> handleText(ctx, inst ->
                inst.getPlacement() != null ? inst.getPlacement().getAvailabilityZone() : "us-east-1a"));
        router.get("/latest/meta-data/placement/region").handler(ctx -> handleText(ctx, inst -> inst.getRegion()));
        router.get("/latest/meta-data/iam/info").handler(ctx -> handleIamInfo(ctx));
        router.get("/latest/meta-data/iam/security-credentials/").handler(ctx -> handleCredentialsList(ctx));
        router.get("/latest/meta-data/iam/security-credentials/:role").handler(ctx -> handleCredentials(ctx));
        router.get("/latest/meta-data/tags/instance").handler(ctx -> handleInstanceTagKeys(ctx));
        router.get("/latest/meta-data/tags/instance/").handler(ctx -> handleInstanceTagKeys(ctx));
        router.getWithRegex("/latest/meta-data/tags/instance/.+").handler(ctx -> handleInstanceTagValue(ctx));
        router.get("/latest/user-data").handler(ctx -> handleUserData(ctx));
        router.get("/latest/dynamic/instance-identity/document").handler(ctx -> handleIdentityDocument(ctx));

        httpServer = vertx.createHttpServer();
        httpServer.requestHandler(router).listen(port, result -> {
            if (result.succeeded()) {
                LOG.infof("EC2 IMDS server listening on port %d", port);
                future.complete(null);
            } else {
                LOG.warnf("EC2 IMDS server failed to start on port %d: %s", port, result.cause().getMessage());
                future.completeExceptionally(result.cause());
            }
        });
        return future;
    }

    public synchronized void stop() {
        credentials.clear();
        tokens.clear();
        capabilities.clear();
        identities.clear();
        containerIpToInstance.clear();
        if (httpServer != null) {
            stopping = httpServer.close().toCompletionStage().toCompletableFuture();
            httpServer = null;
        }
    }

    // ── Token (IMDSv2) ────────────────────────────────────────────────────────

    private synchronized void handleToken(RoutingContext ctx) {
        String ttlHeader = ctx.request().getHeader("x-aws-ec2-metadata-token-ttl-seconds");
        int ttl;
        try {
            ttl = Integer.parseInt(ttlHeader);
        } catch (NumberFormatException invalid) {
            ctx.response().setStatusCode(400).end("Invalid x-aws-ec2-metadata-token-ttl-seconds");
            return;
        }
        if (ttl < 1 || ttl > 21600
                || ctx.request().headers().getAll("x-aws-ec2-metadata-token-ttl-seconds").size() != 1) {
            ctx.response().setStatusCode(400).end("Token TTL must be between 1 and 21600 seconds");
            return;
        }
        if (ctx.request().getHeader("X-Forwarded-For") != null) {
            ctx.response().setStatusCode(403).end();
            return;
        }
        GuestIdentity identity = resolveIdentity(ctx);
        if (identity == null) {
            return;
        }
        Instant now = clock.instant();
        tokens.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        String token = randomToken();
        tokens.put(token, new MetadataToken(identity, now.plusSeconds(ttl)));
        ctx.response().setStatusCode(200)
                .putHeader("x-aws-ec2-metadata-token-ttl-seconds", ttlHeader)
                .end(token);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ── Metadata helpers ──────────────────────────────────────────────────────

    @FunctionalInterface
    interface InstanceField {
        String get(Instance instance);
    }

    private void handleText(RoutingContext ctx, InstanceField field) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String value = field.get(inst);
        if (value == null) {
            ctx.response().setStatusCode(404).end("not-available");
            return;
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(value);
    }

    private void handleMac(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String mac = inst.getNetworkInterfaces().isEmpty()
                ? "02:42:ac:11:00:02"
                : inst.getNetworkInterfaces().get(0).getMacAddress();
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(mac != null ? mac : "02:42:ac:11:00:02");
    }

    private void handleSecurityGroups(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (var sg : inst.getSecurityGroups()) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append(sg.getGroupName() != null ? sg.getGroupName() : sg.getGroupId());
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(sb.toString());
    }

    private synchronized void handleIamInfo(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String profileArn = inst.getIamInstanceProfileArn();
        if (profileArn == null || !profileBelongsToGuest(inst)) {
            ctx.response().setStatusCode(404).end("{}");
            return;
        }
        String profileId = "AIPA" + inst.getInstanceId().toUpperCase().substring(2, 16);
        String body = "{\"Code\":\"Success\",\"LastUpdated\":\"" + now() + "\","
                + "\"InstanceProfileArn\":\"" + profileArn + "\","
                + "\"InstanceProfileId\":\"" + profileId + "\"}";
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(body);
    }

    private synchronized void handleCredentialsList(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        Optional<IamRole> role = profileBelongsToGuest(inst) ? credentials.role(inst) : Optional.empty();
        if (role.isEmpty()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        ctx.response().putHeader("content-type", "text/plain").end(role.get().getRoleName());
    }

    private synchronized void handleCredentials(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        Optional<SessionCredential> result = profileBelongsToGuest(inst)
                ? credentials.get(inst, ctx.pathParam("role"), clock.instant()) : Optional.empty();
        if (result.isEmpty()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        SessionCredential session = result.get();
        ctx.response().putHeader("content-type", "application/json").end(new JsonObject()
                .put("Code", "Success")
                .put("LastUpdated", ISO.format(session.getExpiration().minusSeconds(3600)))
                .put("Type", "AWS-HMAC")
                .put("AccessKeyId", session.getAccessKeyId())
                .put("SecretAccessKey", session.getSecretAccessKey())
                .put("Token", session.getSessionToken())
                .put("Expiration", ISO.format(session.getExpiration())).encode());
    }

    private void handleInstanceTagKeys(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(instanceTagKeys(inst));
    }

    private void handleInstanceTagValue(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }

        String path = ctx.request().path();
        String tagKey = path.length() <= INSTANCE_TAGS_PREFIX.length()
                ? ""
                : URLDecoder.decode(path.substring(INSTANCE_TAGS_PREFIX.length()), StandardCharsets.UTF_8);
        Optional<String> value = instanceTagValue(inst, tagKey);
        if (value.isEmpty()) {
            ctx.response().setStatusCode(404).end("not-found");
            return;
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(value.get());
    }

    private void handleUserData(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String userData = inst.getUserData();
        if (userData == null || userData.isBlank()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(userData);
    }

    private boolean profileBelongsToGuest(Instance instance) {
        GuestIdentity identity = identities.get(instance);
        String arn = instance.getIamInstanceProfileArn();
        String[] parts = arn == null ? new String[0] : arn.split(":", 6);
        if (identity == null || parts.length != 6 || !Objects.equals(identity.accountId(), parts[4])) {
            credentials.unregister(instance);
            return false;
        }
        credentials.register(instance);
        return true;
    }

    private synchronized void handleIdentityDocument(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String body = instanceIdentityDocument(inst, identities.get(inst).accountId());
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(body);
    }

    static String instanceIdentityDocument(Instance inst, String accountId) {
        String az = inst.getPlacement() != null ? inst.getPlacement().getAvailabilityZone() : "us-east-1a";
        String architecture = inst.getArchitecture() == null || inst.getArchitecture().isBlank()
                ? "x86_64"
                : inst.getArchitecture();
        String body = "{\"accountId\":\"" + accountId + "\","
                + "\"architecture\":\"" + architecture + "\","
                + "\"availabilityZone\":\"" + az + "\","
                + "\"imageId\":\"" + inst.getImageId() + "\","
                + "\"instanceId\":\"" + inst.getInstanceId() + "\","
                + "\"instanceType\":\"" + inst.getInstanceType() + "\","
                + "\"privateIp\":\"" + nvl(inst.getPrivateIpAddress()) + "\","
                + "\"region\":\"" + inst.getRegion() + "\","
                + "\"version\":\"2017-09-30\"}";
        return body;
    }

    // ── Instance resolution ───────────────────────────────────────────────────

    private GuestIdentity resolveIdentity(RoutingContext ctx) {
        String capability = ctx.request().getHeader(PROXY_HEADER);
        GuestIdentity identity;
        if (capability != null) {
            identity = capabilities.get(capability);
            if (ctx.request().headers().getAll(PROXY_HEADER).size() != 1 || identity == null) {
                ctx.response().setStatusCode(401).end();
                return null;
            }
        } else {
            String remoteIp = ctx.request().remoteAddress().host();
            Instance instance = containerIpToInstance.get(remoteIp);
            identity = instance == null ? null : identities.get(instance);
            if (identity == null) {
                ctx.response().setStatusCode(404).end(unregisteredContainerMessage(remoteIp));
                return null;
            }
            // An EC2 proxy registration cannot be bypassed through its bridge address.
            if (identity.capability() != null) {
                ctx.response().setStatusCode(401).end();
                return null;
            }
        }
        Instance instance = identity.instance();
        String state = instance.getState() == null ? null : instance.getState().getName();
        if (identities.get(instance) != identity
                || !Objects.equals(identity.containerId(), instance.getDockerContainerId())
                || "stopping".equals(state) || "stopped".equals(state)
                || "shutting-down".equals(state) || "terminated".equals(state)) {
            ctx.response().setStatusCode(401).end();
            return null;
        }
        if ("disabled".equals(instance.effectiveMetadataOptions().getHttpEndpoint())) {
            ctx.response().setStatusCode(403).end();
            return null;
        }
        return identity;
    }

    private synchronized Instance resolveInstance(RoutingContext ctx) {
        GuestIdentity identity = resolveIdentity(ctx);
        if (identity == null) {
            return null;
        }
        String token = ctx.request().getHeader("x-aws-ec2-metadata-token");
        if (token != null) {
            MetadataToken issued = tokens.get(token);
            if (ctx.request().headers().getAll("x-aws-ec2-metadata-token").size() != 1
                    || issued == null || issued.identity() != identity || !issued.expiresAt().isAfter(clock.instant())) {
                ctx.response().setStatusCode(401).end();
                return null;
            }
        } else if ("required".equals(identity.instance().effectiveMetadataOptions().getHttpTokens())) {
            ctx.response().setStatusCode(401).end();
            return null;
        }
        return identity.instance();
    }

    /**
     * Explains why IMDS has nothing to serve for a request coming from {@code remoteIp}.
     *
     * <p>IMDS only knows about containers that {@link Ec2ContainerManager} launched through EC2
     * {@code RunInstances}. Registering a container's SSM agent as a managed instance
     * ({@code UpdateInstanceInformation}) does not create an EC2 instance record, so a container
     * that was only registered with SSM ends up here.
     */
    static String unregisteredContainerMessage(String remoteIp) {
        return "Instance not found: no EC2 instance is registered for source IP " + remoteIp + ". "
                + "IMDS only serves containers launched through EC2 RunInstances; "
                + "registering a container as an SSM managed instance does not register it with IMDS. "
                + "Launch the container with RunInstances first, then register its SSM agent.";
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String now() {
        return ISO.format(Instant.now());
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    static String instanceTagKeys(Instance instance) {
        StringBuilder tags = new StringBuilder();
        if (instance == null || instance.getTags() == null) {
            return "";
        }
        for (var tag : instance.getTags()) {
            if (tag.getKey() == null || tag.getKey().isBlank()) {
                continue;
            }
            if (!tags.isEmpty()) {
                tags.append("\n");
            }
            tags.append(tag.getKey());
        }
        return tags.toString();
    }

    static Optional<String> instanceTagValue(Instance instance, String key) {
        if (instance == null || instance.getTags() == null || key == null) {
            return Optional.empty();
        }
        for (var tag : instance.getTags()) {
            if (key.equals(tag.getKey())) {
                return Optional.of(nvl(tag.getValue()));
            }
        }
        return Optional.empty();
    }
}
