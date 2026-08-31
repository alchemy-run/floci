package io.github.hectorvent.floci.services.ivschat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ivschat.model.Room;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Amazon IVS Chat messaging data plane.
 *
 * <p>Clients connect with {@code wss://edge.ivschat.{region}.amazonaws.com} (or the
 * path-style {@code /ivschat} on the gateway) and present the CreateChatToken
 * value as the WebSocket subprotocol. Frames are PascalCase
 * {@code {Action, Content, RequestId}} / {@code {Type, Content, ErrorCode, ErrorMessage}}.
 */
@ApplicationScoped
public class IvsChatWebSocketHandler {

    private static final Logger LOG = Logger.getLogger(IvsChatWebSocketHandler.class);

    private final IvsChatService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final Vertx vertx;

    @Inject
    public IvsChatWebSocketHandler(IvsChatService service, ObjectMapper objectMapper,
                                   RegionResolver regionResolver, Vertx vertx) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.vertx = vertx;
    }

    void init(@Observes Router router) {
        // Path-style: host tests rewrite wss://edge.ivschat.{region}.amazonaws.com onto
        // ws://127.0.0.1:{port}/ivschat. Do not register a catch-all route() — that
        // runs on every HTTP request (Function URL probes included) and stacked
        // quarkus:dev reloads wedge the event loop.
        router.route("/ivschat").order(-40).handler(this::handleUpgrade);
        router.route("/ivschat/*").order(-40).handler(this::handleUpgrade);
    }

    private void handleUpgrade(RoutingContext ctx) {
        if (!isWebSocketUpgrade(ctx)) {
            ctx.next();
            return;
        }
        String protocol = selectedProtocol(ctx.request().getHeader("Sec-WebSocket-Protocol"));
        IvsChatService.ParsedChatToken token;
        Room room;
        try {
            token = service.parseChatToken(protocol);
            room = service.requireExistingRoom(regionResolver.getRegion(), token.roomArn());
        } catch (AwsException e) {
            LOG.debugv("IVS Chat websocket rejected: {0}", e.getMessage());
            ctx.response().setStatusCode(403).end();
            return;
        }
        if (protocol != null) {
            ctx.response().putHeader("Sec-WebSocket-Protocol", protocol);
        }
        IvsChatService.ParsedChatToken accepted = token;
        Room acceptedRoom = room;
        ctx.request().toWebSocket().onSuccess(ws -> attach(ws, accepted, acceptedRoom, ctx))
                .onFailure(err -> LOG.debugv("IVS Chat websocket upgrade failed: {0}", err.getMessage()));
    }

    private void attach(ServerWebSocket ws, IvsChatService.ParsedChatToken token, Room room,
                        RoutingContext ctx) {
        String sourceIp = ctx.request().remoteAddress() != null
                ? ctx.request().remoteAddress().host() : "127.0.0.1";
        ChatSocketSession session = new ChatSocketSession(token.roomArn(), token.userId(), ws);
        service.registerSession(session);
        ws.textMessageHandler(message -> onMessage(ws, token, room, sourceIp, message));
        ws.closeHandler(v -> service.unregisterSession(session));
        ws.exceptionHandler(err -> {
            LOG.debugv("IVS Chat websocket error: {0}", err.getMessage());
            service.unregisterSession(session);
        });
    }

    private void onMessage(ServerWebSocket ws, IvsChatService.ParsedChatToken token, Room room,
                           String sourceIp, String message) {
        JsonNode frame;
        try {
            frame = objectMapper.readTree(message);
        } catch (Exception e) {
            return;
        }
        String action = text(frame, "Action");
        if (action == null) {
            action = text(frame, "action");
        }
        if (!"SEND_MESSAGE".equals(action)) {
            return;
        }
        if (!token.capabilities().contains("SEND_MESSAGE")) {
            sendJson(ws, errorFrame(text(frame, "RequestId"), 403, "SEND_MESSAGE is not permitted"));
            return;
        }
        String rawContent = text(frame, "Content");
        final String content = rawContent == null ? "" : rawContent;
        if (room.getMaximumMessageLength() > 0 && content.length() > room.getMaximumMessageLength()) {
            sendJson(ws, errorFrame(text(frame, "RequestId"), 400, "Content exceeds maximumMessageLength"));
            return;
        }
        final String requestId = text(frame, "RequestId");
        final String messageId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        // Review may invoke Lambda; never do that on the Vert.x event loop.
        vertx.<IvsChatService.MessageReview>executeBlocking(() -> service.reviewMessage(
                        room, content, messageId, token.userId(), Map.of(), token.attributes(), sourceIp))
                .onSuccess(review -> deliver(ws, token, room, requestId, messageId, content, review))
                .onFailure(err -> {
                    LOG.debugv("IVS Chat review failed: {0}", err.getMessage());
                    deliver(ws, token, room, requestId, messageId, content,
                            fallbackReview(room, content));
                });
    }

    private void deliver(ServerWebSocket ws, IvsChatService.ParsedChatToken token, Room room,
                         String requestId, String messageId, String content,
                         IvsChatService.MessageReview review) {
        if (review.denied()) {
            String reason = review.attributes() == null ? null : review.attributes().get("Reason");
            sendJson(ws, errorFrame(requestId, 406, reason == null ? "Message rejected" : reason));
            return;
        }
        ObjectNode delivered = objectMapper.createObjectNode();
        delivered.put("Type", "MESSAGE");
        delivered.put("Id", messageId);
        if (requestId != null) {
            delivered.put("RequestId", requestId);
        }
        delivered.put("Content", review.content() == null ? content : review.content());
        delivered.put("SendTime", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());
        ObjectNode attributes = delivered.putObject("Attributes");
        if (review.attributes() != null) {
            review.attributes().forEach(attributes::put);
        }
        ObjectNode sender = delivered.putObject("Sender");
        sender.put("UserId", token.userId());
        ObjectNode senderAttrs = sender.putObject("Attributes");
        token.attributes().forEach(senderAttrs::put);
        service.broadcast(room.getArn(), delivered.toString());
    }

    private static IvsChatService.MessageReview fallbackReview(Room room, String content) {
        String fallback = room.getMessageReviewHandlerFallbackResult();
        return new IvsChatService.MessageReview("DENY".equals(fallback), content, Map.of());
    }

    private ObjectNode errorFrame(String requestId, int code, String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Type", "ERROR");
        if (requestId != null) {
            node.put("RequestId", requestId);
        }
        node.put("ErrorCode", code);
        node.put("ErrorMessage", message);
        return node;
    }

    private void sendJson(ServerWebSocket ws, ObjectNode node) {
        try {
            ws.writeTextMessage(node.toString());
        } catch (Exception e) {
            LOG.debugv("IVS Chat websocket write failed: {0}", e.getMessage());
        }
    }

    static boolean isWebSocketUpgrade(RoutingContext ctx) {
        String upgrade = ctx.request().getHeader("Upgrade");
        if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade.trim())) {
            return false;
        }
        String connection = ctx.request().getHeader("Connection");
        return connection != null && connection.toLowerCase().contains("upgrade");
    }

    static boolean isIvsChatHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String hostname = host;
        int colon = hostname.indexOf(':');
        if (colon >= 0) {
            hostname = hostname.substring(0, colon);
        }
        return hostname.toLowerCase().contains("edge.ivschat.");
    }

    static String selectedProtocol(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        return header.split(",")[0].trim();
    }

    private static String text(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        return value.isTextual() ? value.textValue() : value.asText();
    }

    private static final class ChatSocketSession implements IvsChatService.ChatSession {
        private final String roomArn;
        private final String userId;
        private final ServerWebSocket socket;

        private ChatSocketSession(String roomArn, String userId, ServerWebSocket socket) {
            this.roomArn = roomArn;
            this.userId = userId;
            this.socket = socket;
        }

        @Override
        public String roomArn() {
            return roomArn;
        }

        @Override
        public String userId() {
            return userId;
        }

        @Override
        public void send(String frame) {
            try {
                socket.writeTextMessage(frame);
            } catch (Exception ignored) {
                // connection already closed
            }
        }

        @Override
        public void close(String reason) {
            try {
                socket.close((short) 1000, reason == null ? "" : reason);
            } catch (Exception ignored) {
                // already closed
            }
        }
    }
}
