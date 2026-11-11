package io.floci.aws.services.ivschat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.ServiceCallAuthorizer;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.floci.aws.services.ivschat.IvsChatStore.LOGGING;
import static io.floci.aws.services.ivschat.IvsChatStore.ROOM;
import static io.floci.aws.services.ivschat.IvsChatStore.key;
import static io.floci.aws.services.ivschat.IvsChatStore.meta;
import static io.floci.aws.services.ivschat.IvsChatStore.notFound;
import static io.floci.aws.services.ivschat.IvsChatStore.validation;

/**
 * Amazon IVS Chat: rooms and logging configurations, chat tokens, and the rooms' messaging. A token connects one
 * WebSocket to its room through {@link IvsChatEdge}, once, before it expires, for its session's duration. A
 * connection's {@code SEND_MESSAGE} is checked against its capabilities and the room's message length and rate,
 * reviewed by the room's message review handler (a Lambda function, invoked as {@code ivschat.amazonaws.com}, whose
 * failures and invalid answers fall back to the handler's {@code fallbackResult}), and delivered to every
 * connection in the room. {@code SendEvent}, {@code DeleteMessage} and {@code DisconnectUser}, as API calls or
 * messaging actions, reach the room's connections the same way, and everything delivered is logged by
 * {@link IvsChatLogging}.
 */
@ApplicationScoped
public class IvsChatService implements Resettable {

    private static final Logger LOG = Logger.getLogger(IvsChatService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DEFAULT_RATE = 10;
    private static final int DEFAULT_LENGTH = 500;
    private static final int ATTRIBUTES_BYTES = 1024;
    private static final Set<String> TAGGED = Set.of(ROOM, LOGGING);

    private final IvsChatStore store;
    private final IvsChatLogging logging;
    private final Instance<LambdaService> lambda;
    private final Instance<ServiceCallAuthorizer> serviceCalls;
    private final Map<String, Grant> tokens = new ConcurrentHashMap<>();
    private final Map<String, Set<Connection>> connections = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> sent = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    /** What a chat token grants, and whether it has been used. */
    record Grant(String account, String region, String roomId, String userId, Set<String> capabilities,
                 ObjectNode attributes, Instant tokenExpiry, Instant sessionExpiry) {
    }

    /** A room's connection, and the queue its messaging requests are handled on, in order. */
    static final class Connection {
        final Grant grant;
        final IvsChatSocket socket;
        final String ip;
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

        Connection(Grant grant, IvsChatSocket socket, String ip) {
            this.grant = grant;
            this.socket = socket;
            this.ip = ip;
        }

        String room() {
            return grant.account() + "/" + grant.region() + "/" + grant.roomId();
        }
    }

    @Inject
    public IvsChatService(IvsChatStore store, IvsChatLogging logging, Instance<LambdaService> lambda,
                          Instance<ServiceCallAuthorizer> serviceCalls) {
        this.store = store;
        this.logging = logging;
        this.lambda = lambda;
        this.serviceCalls = serviceCalls;
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
    }

    @Override
    public void clear() {
        tokens.clear();
        connections.values().forEach(room -> room.forEach(c -> c.socket.close(1001, "The emulator was reset.")));
        connections.clear();
        sent.clear();
    }

    private ObjectNode object() {
        return store.mapper().createObjectNode();
    }

    // ---------------------------------------------------------------- rooms

    public synchronized ObjectNode createRoom(JsonNode input, String region) {
        String id = IvsChatStore.id(12);
        ObjectNode room = store.record(region, ROOM, id);
        String now = IvsChatStore.now();
        room.put("name", input.path("name").asText("")).put("createTime", now).put("updateTime", now)
                .put("maximumMessageRatePerSecond", input.path("maximumMessageRatePerSecond").asInt(DEFAULT_RATE))
                .put("maximumMessageLength", input.path("maximumMessageLength").asInt(DEFAULT_LENGTH));
        room.set("messageReviewHandler", reviewHandler(input.get("messageReviewHandler"), null, region));
        room.set("loggingConfigurationIdentifiers", loggingIdentifiers(input.get("loggingConfigurationIdentifiers"),
                store.mapper().createArrayNode(), region));
        room.set("tags", store.tags(input.path("tags"), null));
        if (room.path("messageReviewHandler").isNull()) {
            room.remove("messageReviewHandler");
        }
        store.put(key(region, ROOM, id), room);
        return store.view(room);
    }

    public ObjectNode getRoom(JsonNode input, String region) {
        return store.view(store.require(input.path("identifier").asText(null), ROOM, region));
    }

    public synchronized ObjectNode updateRoom(JsonNode input, String region) {
        ObjectNode room = store.require(input.path("identifier").asText(null), ROOM, region);
        if (input.has("name")) {
            room.put("name", input.path("name").asText(""));
        }
        if (input.hasNonNull("maximumMessageRatePerSecond")) {
            room.put("maximumMessageRatePerSecond", input.get("maximumMessageRatePerSecond").asInt());
        }
        if (input.hasNonNull("maximumMessageLength")) {
            room.put("maximumMessageLength", input.get("maximumMessageLength").asInt());
        }
        if (input.has("messageReviewHandler")) {
            ObjectNode handler = reviewHandler(input.get("messageReviewHandler"),
                    (ObjectNode) room.get("messageReviewHandler"), region);
            if (handler == null) {
                room.remove("messageReviewHandler");
            } else {
                room.set("messageReviewHandler", handler);
            }
        }
        if (input.has("loggingConfigurationIdentifiers")) {
            room.set("loggingConfigurationIdentifiers", loggingIdentifiers(input.get("loggingConfigurationIdentifiers"),
                    (ArrayNode) room.get("loggingConfigurationIdentifiers"), region));
        }
        room.put("updateTime", IvsChatStore.now());
        store.put(key(region, ROOM, room.path("id").asText()), room);
        return store.view(room);
    }

    public synchronized ObjectNode deleteRoom(JsonNode input, String region) {
        ObjectNode room = store.require(input.path("identifier").asText(null), ROOM, region);
        store.delete(key(region, ROOM, room.path("id").asText()));
        Set<Connection> open = connections.remove(store.account() + "/" + region + "/" + room.path("id").asText());
        if (open != null) {
            open.forEach(connection -> connection.socket.close(1000, "The room was deleted."));
        }
        return object();
    }

    public ObjectNode listRooms(JsonNode input, String region) {
        String name = input.path("name").asText(null);
        String handler = input.path("messageReviewHandlerUri").asText(null);
        String logged = input.path("loggingConfigurationIdentifier").asText(null);
        String loggedId = logged == null ? null : idOf(logged);
        List<ObjectNode> rooms = new ArrayList<>();
        for (ObjectNode room : store.list(region + "/" + ROOM + "/")) {
            if (name != null && !name.isEmpty() && !name.equals(room.path("name").asText())) {
                continue;
            }
            if (handler != null && !handler.isEmpty()
                    && !handler.equals(room.path("messageReviewHandler").path("uri").asText())) {
                continue;
            }
            if (loggedId != null && !attachedTo(room, loggedId, region)) {
                continue;
            }
            ObjectNode summary = store.view(room);
            summary.remove("maximumMessageRatePerSecond");
            summary.remove("maximumMessageLength");
            rooms.add(summary);
        }
        return store.page(rooms, input, "rooms", 50);
    }

    /** The handler a room is given, defaulted to fall back to ALLOW; null when none is or the URI is empty. */
    private ObjectNode reviewHandler(JsonNode given, ObjectNode existing, String region) {
        if (given == null || given.isNull()) {
            return existing;
        }
        String uri = given.path("uri").asText(existing == null ? "" : existing.path("uri").asText(""));
        if (uri.isEmpty()) {
            return null;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.isArnFor(uri, "lambda") ? AwsArnUtils.parse(uri) : null;
        if (arn == null || !region.equals(arn.region()) || !store.account().equals(arn.accountId())) {
            throw validation("messageReviewHandler.uri must be a Lambda function of this account and Region.");
        }
        ObjectNode handler = object().put("uri", uri);
        handler.put("fallbackResult", given.path("fallbackResult")
                .asText(existing == null ? "ALLOW" : existing.path("fallbackResult").asText("ALLOW")));
        return handler;
    }

    private ArrayNode loggingIdentifiers(JsonNode given, ArrayNode existing, String region) {
        if (given == null || given.isNull()) {
            return existing;
        }
        ArrayNode identifiers = store.mapper().createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode identifier : given) {
            store.require(identifier.asText(), LOGGING, region);
            if (seen.add(idOf(identifier.asText()))) {
                identifiers.add(identifier.asText());
            }
        }
        return identifiers;
    }

    private static String idOf(String identifier) {
        if (!AwsArnUtils.isArn(identifier)) {
            return identifier;
        }
        int slash = identifier.lastIndexOf('/');
        return slash < 0 ? identifier : identifier.substring(slash + 1);
    }

    private boolean attachedTo(ObjectNode room, String loggingId, String region) {
        for (JsonNode identifier : room.path("loggingConfigurationIdentifiers")) {
            if (loggingId.equals(idOf(identifier.asText()))) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- logging configurations

    public synchronized ObjectNode createLoggingConfiguration(JsonNode input, String region) {
        JsonNode destination = destination(input.path("destinationConfiguration"));
        logging.requireDestination(destination, store.account(), region);
        String id = IvsChatStore.id(12);
        ObjectNode configuration = store.record(region, LOGGING, id);
        String now = IvsChatStore.now();
        configuration.put("name", input.path("name").asText("")).put("createTime", now).put("updateTime", now)
                .put("state", "ACTIVE");
        configuration.set("destinationConfiguration", destination);
        configuration.set("tags", store.tags(input.path("tags"), null));
        store.put(key(region, LOGGING, id), configuration);
        return store.view(configuration);
    }

    public ObjectNode getLoggingConfiguration(JsonNode input, String region) {
        return store.view(store.require(input.path("identifier").asText(null), LOGGING, region));
    }

    public synchronized ObjectNode updateLoggingConfiguration(JsonNode input, String region) {
        ObjectNode configuration = store.require(input.path("identifier").asText(null), LOGGING, region);
        if (input.has("name")) {
            configuration.put("name", input.path("name").asText(""));
        }
        if (input.hasNonNull("destinationConfiguration")) {
            JsonNode destination = destination(input.get("destinationConfiguration"));
            logging.requireDestination(destination, store.account(), region);
            configuration.set("destinationConfiguration", destination);
        }
        configuration.put("updateTime", IvsChatStore.now()).put("state", "ACTIVE");
        store.put(key(region, LOGGING, configuration.path("id").asText()), configuration);
        return store.view(configuration);
    }

    public synchronized ObjectNode deleteLoggingConfiguration(JsonNode input, String region) {
        ObjectNode configuration = store.require(input.path("identifier").asText(null), LOGGING, region);
        String id = configuration.path("id").asText();
        for (ObjectNode room : store.list(region + "/" + ROOM + "/")) {
            if (attachedTo(room, id, region)) {
                throw IvsChatStore.conflict("Logging configuration " + configuration.path("arn").asText()
                        + " is attached to room " + room.path("arn").asText() + ".", id, LOGGING);
            }
        }
        store.delete(key(region, LOGGING, id));
        return object();
    }

    public ObjectNode listLoggingConfigurations(JsonNode input, String region) {
        List<ObjectNode> configurations = new ArrayList<>();
        store.list(region + "/" + LOGGING + "/").forEach(c -> configurations.add(store.view(c)));
        return store.page(configurations, input, "loggingConfigurations", 50);
    }

    /** The one destination a configuration names. */
    private static JsonNode destination(JsonNode given) {
        int named = 0;
        for (String type : List.of("s3", "cloudWatchLogs", "firehose")) {
            named += given.hasNonNull(type) ? 1 : 0;
        }
        if (named != 1) {
            throw validation("destinationConfiguration names exactly one of s3, cloudWatchLogs or firehose.");
        }
        return given.deepCopy();
    }

    // ---------------------------------------------------------------- tags

    private ObjectNode tagged(String arn, String region) {
        String[] parts = IvsChatStore.parse(arn, region, store.account());
        if (parts == null || !TAGGED.contains(parts[0])) {
            throw notFound(arn == null ? "" : arn.substring(arn.lastIndexOf('/') + 1), ROOM);
        }
        return store.require(arn, parts[0], region);
    }

    public ObjectNode listTagsForResource(String arn, String region) {
        ObjectNode response = object();
        response.set("tags", tagged(arn, region).path("tags").deepCopy());
        return response;
    }

    public synchronized ObjectNode tagResource(String arn, JsonNode input, String region) {
        ObjectNode record = tagged(arn, region);
        if (!input.path("tags").isObject()) {
            throw validation("tags is required.");
        }
        record.set("tags", store.tags(input.path("tags"), (ObjectNode) record.get("tags")));
        store.put(key(region, meta(record).path("kind").asText(), record.path("id").asText()), record);
        return object();
    }

    public synchronized ObjectNode untagResource(String arn, List<String> tagKeys, String region) {
        ObjectNode record = tagged(arn, region);
        if (tagKeys == null || tagKeys.isEmpty()) {
            throw validation("tagKeys is required.");
        }
        ObjectNode tags = (ObjectNode) record.get("tags");
        tagKeys.forEach(tags::remove);
        store.put(key(region, meta(record).path("kind").asText(), record.path("id").asText()), record);
        return object();
    }

    // ---------------------------------------------------------------- tokens and connections

    public ObjectNode createChatToken(JsonNode input, String region) {
        ObjectNode room = store.require(input.path("roomIdentifier").asText(null), ROOM, region);
        String userId = input.path("userId").asText(null);
        if (userId == null || userId.isEmpty()) {
            throw validation("userId is required.");
        }
        Set<String> capabilities = new HashSet<>();
        input.path("capabilities").forEach(capability -> capabilities.add(capability.asText()));
        ObjectNode attributes = stringMap(input.path("attributes"), "attributes");
        if (bytes(attributes) > ATTRIBUTES_BYTES) {
            throw validation("attributes are at most 1 KB.");
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant tokenExpiry = now.plus(store.config().tokenMinutes(), ChronoUnit.MINUTES);
        Instant sessionExpiry = now.plus(input.path("sessionDurationInMinutes").asInt(60), ChronoUnit.MINUTES);
        byte[] random = new byte[96];
        RANDOM.nextBytes(random);
        String token = "AQICAH" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        tokens.put(token, new Grant(store.account(), region, room.path("id").asText(), userId, capabilities,
                attributes, tokenExpiry, sessionExpiry));
        ObjectNode response = object();
        response.put("token", token).put("tokenExpirationTime", tokenExpiry.toString())
                .put("sessionExpirationTime", sessionExpiry.toString());
        return response;
    }

    /**
     * Redeems a token at a Region's messaging endpoint: its grant, or an AwsException carrying the handshake's
     * refusal (401 for a token that is missing, used, expired or of another Region, 404 for a deleted room).
     */
    Grant redeem(String token, String region) {
        Grant grant = token == null ? null : tokens.remove(token);
        if (grant == null || !grant.region().equals(region) || Instant.now().isAfter(grant.tokenExpiry())) {
            throw new AwsException("Unauthorized", "The chat token is missing, used or expired.", 401);
        }
        if (store.get(grant.account(), key(region, ROOM, grant.roomId())).isEmpty()) {
            throw new AwsException("NotFound", "The room does not exist.", 404);
        }
        return grant;
    }

    Connection connect(Grant grant, IvsChatSocket socket, String ip) {
        Connection connection = new Connection(grant, socket, ip);
        connections.computeIfAbsent(connection.room(), ignored -> new CopyOnWriteArraySet<>()).add(connection);
        return connection;
    }

    void disconnected(Connection connection) {
        Set<Connection> room = connections.get(connection.room());
        if (room != null) {
            room.remove(connection);
        }
    }

    /** Handles a connection's messaging request on the connection's queue, after the ones it sent before. */
    void receive(Connection connection, String text) {
        synchronized (connection) {
            connection.tail = connection.tail.thenRunAsync(() -> {
                try {
                    handle(connection, text);
                } catch (RuntimeException e) {
                    LOG.warnv(e, "IVS Chat could not handle a messaging request");
                    error(connection, null, null, 500, "Internal server error.");
                }
            }, workers);
        }
    }

    private void handle(Connection connection, String text) {
        JsonNode request;
        try {
            request = store.mapper().readTree(text);
        } catch (JsonProcessingException e) {
            error(connection, null, null, 400, "The request is not valid JSON.");
            return;
        }
        if (request == null || !request.isObject()) {
            error(connection, null, null, 400, "The request must be a JSON object.");
            return;
        }
        String requestId = request.path("RequestId").asText(null);
        String id = IvsChatStore.id(12);
        if (Instant.now().isAfter(connection.grant.sessionExpiry())) {
            error(connection, id, requestId, 401, "The connection has expired.");
            connection.socket.close(1000, "The session expired.");
            return;
        }
        String action = request.path("Action").asText("");
        String capability = switch (action) {
            case "SEND_MESSAGE", "DELETE_MESSAGE", "DISCONNECT_USER" -> action;
            default -> null;
        };
        if (capability == null) {
            error(connection, id, requestId, 400, "Unknown action " + action + ".");
            return;
        }
        if (!connection.grant.capabilities().contains(capability)) {
            error(connection, id, requestId, 403, "The connection does not have the " + capability + " capability.");
            return;
        }
        Grant grant = connection.grant;
        ObjectNode room = store.get(grant.account(), key(grant.region(), ROOM, grant.roomId())).orElse(null);
        if (room == null) {
            error(connection, id, requestId, 404, "The room does not exist.");
            return;
        }
        switch (action) {
            case "SEND_MESSAGE" -> sendMessage(connection, room, request, id, requestId);
            case "DELETE_MESSAGE" -> {
                String messageId = request.path("Id").asText("");
                if (messageId.isEmpty()) {
                    error(connection, id, requestId, 400, "Id is required.");
                    return;
                }
                deliverDelete(grant.account(), grant.region(), room, id, messageId,
                        request.path("Reason").asText(null), requestId);
            }
            default -> {
                String userId = request.path("UserId").asText("");
                if (userId.isEmpty()) {
                    error(connection, id, requestId, 400, "UserId is required.");
                    return;
                }
                disconnect(grant.account(), grant.region(), grant.roomId(), userId,
                        request.path("Reason").asText(null));
            }
        }
    }

    private void sendMessage(Connection connection, ObjectNode room, JsonNode request, String id,
                             String requestId) {
        JsonNode content = request.get("Content");
        if (content == null || !content.isTextual()) {
            error(connection, id, requestId, 400, "Content is required.");
            return;
        }
        int maxLength = room.path("maximumMessageLength").asInt(DEFAULT_LENGTH);
        if (content.asText().codePointCount(0, content.asText().length()) > maxLength) {
            error(connection, id, requestId, 400, "Messages must be at most " + maxLength + " chars.");
            return;
        }
        JsonNode given = request.path("Attributes");
        if (!given.isMissingNode() && !given.isNull() && !given.isObject()) {
            error(connection, id, requestId, 400, "Attributes must be a map of strings.");
            return;
        }
        ObjectNode attributes = stringMap(given, "Attributes");
        if (bytes(attributes) > ATTRIBUTES_BYTES) {
            error(connection, id, requestId, 413, "Attributes are at most 1 KB.");
            return;
        }
        if (!admit(connection.room(), room.path("maximumMessageRatePerSecond").asInt(DEFAULT_RATE))) {
            error(connection, id, requestId, 429, "The room's message rate was exceeded.");
            return;
        }
        Grant grant = connection.grant;
        String text = content.asText();
        JsonNode handler = room.get("messageReviewHandler");
        if (handler != null && !handler.path("uri").asText("").isEmpty()) {
            Review review = review(grant, room, handler, id, text, attributes, connection.ip, maxLength);
            if (!review.allow()) {
                error(connection, id, requestId, 406, review.reason());
                return;
            }
            text = review.content();
            attributes = review.attributes();
        }
        ObjectNode message = object();
        message.set("Attributes", attributes);
        message.put("Content", text).put("Id", id);
        if (requestId != null) {
            message.put("RequestId", requestId);
        }
        ObjectNode sender = message.putObject("Sender");
        sender.set("Attributes", grant.attributes().deepCopy());
        sender.put("UserId", grant.userId());
        message.put("SendTime", Instant.now().toString()).put("Type", "MESSAGE");
        deliver(grant.account(), grant.region(), room, message);
    }

    /** Whether the room has rate left for one more message this second. */
    private boolean admit(String room, int perSecond) {
        Deque<Long> times = sent.computeIfAbsent(room, ignored -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() <= now - 1000) {
                times.pollFirst();
            }
            if (times.size() >= perSecond) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    private record Review(boolean allow, String content, ObjectNode attributes, String reason) {
    }

    /** Asks the room's message review handler about a message, falling back on any failure. */
    private Review review(Grant grant, ObjectNode room, JsonNode handler, String id, String content,
                          ObjectNode attributes, String ip, int maxLength) {
        String uri = handler.path("uri").asText();
        Review fallback = "DENY".equals(handler.path("fallbackResult").asText("ALLOW"))
                ? new Review(false, null, null, "The message was denied by the room's fallback result.")
                : new Review(true, content, attributes, null);
        ObjectNode event = object();
        event.put("Content", content).put("MessageId", id).put("RoomArn", room.path("arn").asText());
        event.set("Attributes", attributes.deepCopy());
        ObjectNode sender = event.putObject("Sender");
        sender.set("Attributes", grant.attributes().deepCopy());
        sender.put("UserId", grant.userId()).put("Ip", ip == null ? "" : ip);
        try {
            InvokeResult result = RequestScopes.callAs(grant.account(), grant.region(), () -> {
                if (serviceCalls.get().authorize("ivschat", "lambda:InvokeFunction", uri, room.path("arn").asText(),
                        grant.account()) == IamPolicyEvaluator.Decision.DENY) {
                    return null;
                }
                return lambda.get().invokeArn(uri, event.toString().getBytes(StandardCharsets.UTF_8),
                        InvocationType.RequestResponse);
            });
            if (result == null || result.getFunctionError() != null || result.getPayload() == null) {
                return fallback;
            }
            JsonNode answer = store.mapper().readTree(result.getPayload());
            String verdict = answer.path("ReviewResult").asText("");
            JsonNode returned = answer.path("Attributes");
            if (!returned.isMissingNode() && !returned.isNull() && !returned.isObject()) {
                return fallback;
            }
            ObjectNode answered = stringMap(returned, "Attributes");
            if (bytes(answered) > ATTRIBUTES_BYTES) {
                return fallback;
            }
            if ("DENY".equals(verdict)) {
                String reason = answered.path("Reason").asText("");
                return new Review(false, null, null,
                        reason.isEmpty() ? "The message was denied by the room's message review handler." : reason);
            }
            String edited = answer.path("Content").asText("");
            if (!"ALLOW".equals(verdict) || edited.isEmpty()
                    || edited.codePointCount(0, edited.length()) > maxLength) {
                return fallback;
            }
            return new Review(true, edited, answered.isEmpty() ? attributes : answered, null);
        } catch (Exception e) {
            LOG.debugv(e, "IVS Chat could not invoke the message review handler {0}", uri);
            return fallback;
        }
    }

    // ---------------------------------------------------------------- delivery

    public ObjectNode sendEvent(JsonNode input, String region) {
        ObjectNode room = store.require(input.path("roomIdentifier").asText(null), ROOM, region);
        String eventName = input.path("eventName").asText("");
        if (eventName.isEmpty()) {
            throw validation("eventName is required.");
        }
        String id = IvsChatStore.id(12);
        ObjectNode event = object();
        event.set("Attributes", stringMap(input.path("attributes"), "attributes"));
        event.put("EventName", eventName).put("Id", id).put("SendTime", Instant.now().toString()).put("Type", "EVENT");
        deliver(store.account(), region, room, event);
        return object().put("id", id);
    }

    public ObjectNode deleteMessage(JsonNode input, String region) {
        ObjectNode room = store.require(input.path("roomIdentifier").asText(null), ROOM, region);
        String id = IvsChatStore.id(12);
        deliverDelete(store.account(), region, room, id, input.path("id").asText(),
                input.path("reason").asText(null), null);
        return object().put("id", id);
    }

    public ObjectNode disconnectUser(JsonNode input, String region) {
        ObjectNode room = store.require(input.path("roomIdentifier").asText(null), ROOM, region);
        disconnect(store.account(), region, room.path("id").asText(), input.path("userId").asText(),
                input.path("reason").asText(null));
        return object();
    }

    private void deliverDelete(String account, String region, ObjectNode room, String id, String messageId,
                               String reason, String requestId) {
        ObjectNode event = object();
        ObjectNode attributes = event.putObject("Attributes");
        attributes.put("MessageID", messageId).put("MessageId", messageId);
        if (reason != null) {
            attributes.put("Reason", reason);
        }
        event.put("EventName", "aws:DELETE_MESSAGE").put("Id", id);
        if (requestId != null) {
            event.put("RequestId", requestId);
        }
        event.put("SendTime", Instant.now().toString()).put("Type", "EVENT");
        deliver(account, region, room, event);
    }

    private void disconnect(String account, String region, String roomId, String userId, String reason) {
        Set<Connection> room = connections.get(account + "/" + region + "/" + roomId);
        if (room == null) {
            return;
        }
        for (Connection connection : room) {
            if (userId.equals(connection.grant.userId())) {
                connection.socket.close(1000, reason == null ? "Disconnected by the room's moderator." : reason);
            }
        }
    }

    /** Sends a message or event to every connection in the room, and logs it. */
    private void deliver(String account, String region, ObjectNode room, ObjectNode payload) {
        Set<Connection> open = connections.get(account + "/" + region + "/" + room.path("id").asText());
        if (open != null) {
            String text = payload.toString();
            open.forEach(connection -> connection.socket.send(text));
        }
        workers.execute(() -> logging.log(account, region, room, payload));
    }

    private void error(Connection connection, String id, String requestId, int code, String message) {
        ObjectNode error = object();
        error.put("ErrorCode", code).put("ErrorMessage", message).put("Id", id == null ? IvsChatStore.id(12) : id);
        if (requestId != null) {
            error.put("RequestId", requestId);
        }
        error.put("Type", "ERROR");
        connection.socket.send(error.toString());
    }

    private ObjectNode stringMap(JsonNode given, String field) {
        ObjectNode map = object();
        if (given == null || given.isMissingNode() || given.isNull()) {
            return map;
        }
        if (!given.isObject()) {
            throw validation(field + " must be a map of strings.");
        }
        given.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().asText()));
        return map;
    }

    private static int bytes(ObjectNode map) {
        int total = 0;
        for (Map.Entry<String, JsonNode> entry : (Iterable<Map.Entry<String, JsonNode>>) map::fields) {
            total += entry.getKey().getBytes(StandardCharsets.UTF_8).length
                    + entry.getValue().asText().getBytes(StandardCharsets.UTF_8).length;
        }
        return total;
    }
}
