package io.floci.aws.services.kinesisvideo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.common.TextWebSocket;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static io.floci.aws.services.kinesisvideo.KvsStore.invalid;
import static io.floci.aws.services.kinesisvideo.KvsStore.meta;
import static io.floci.aws.services.kinesisvideo.KvsStore.text;

/**
 * WebRTC signaling for Kinesis Video signaling channels: the WebSocket endpoint
 * ({@code GetSignalingChannelEndpoint}'s {@code WSS} endpoint) the master and its viewers connect to with
 * {@code X-Amz-ChannelARN} (and a viewer's {@code X-Amz-ClientId}), which relays {@code SDP_OFFER},
 * {@code SDP_ANSWER} and {@code ICE_CANDIDATE} messages between them as Kinesis Video does: a viewer's messages go to
 * the master, held until it connects within the channel's message TTL; the master's go to the viewer it names. A
 * message that can't be delivered is answered with a {@code STATUS_RESPONSE} when it carries a correlation ID. A
 * newer master connection replaces the older one.
 *
 * <p>{@code GetIceServerConfig} returns TURN servers with time-limited credentials ({@code <expiry>:<channel>} and an
 * HMAC of it); peers on one machine connect with their host candidates, as Floci relays no media.
 * {@code SendAlexaOfferToMaster} delivers an offer to the master and waits for its answer. Storage sessions
 * ({@code JoinStorageSession}) need the channel's media storage enabled, and are recorded; Floci doesn't ingest the
 * peers' WebRTC media into the stream.
 */
@ApplicationScoped
public class KvsSignaling {

    static final String PATH = "/_floci/kinesisvideo/signaling/";
    static final Pattern ACCOUNT = Pattern.compile("\\d{12}");

    /** The connections of one channel. */
    static final class Channel {
        volatile TextWebSocket master;
        final Map<String, TextWebSocket> viewers = new ConcurrentHashMap<>();
        final List<Object[]> held = new CopyOnWriteArrayList<>();
        final Map<String, CompletableFuture<String>> answers = new ConcurrentHashMap<>();
    }

    private final KvsStore store;
    private final KvsService service;
    private final ServiceConfigAccess serviceConfigAccess;
    private final EmulatorConfig config;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final byte[] secret = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);

    @Inject
    public KvsSignaling(KvsStore store, KvsService service, ServiceConfigAccess serviceConfigAccess,
                        EmulatorConfig config) {
        this.store = store;
        this.service = service;
        this.serviceConfigAccess = serviceConfigAccess;
        this.config = config;
    }

    private ObjectMapper mapper() {
        return store.mapper();
    }

    private Channel channel(String arn) {
        return channels.computeIfAbsent(arn, k -> new Channel());
    }

    // ---------------------------------------------------------------- WebSocket

    void register(@Observes Router router) {
        router.route(PATH + ":region").handler(this::connect);
    }

    private static String account(String credential, String fallback) {
        if (credential != null) {
            String key = credential.split("/")[0];
            if (ACCOUNT.matcher(key).matches()) {
                return key;
            }
        }
        return fallback;
    }

    private void connect(RoutingContext context) {
        HttpServerRequest request = context.request();
        if (!serviceConfigAccess.isEnabled(KvsStore.SERVICE)
                || !"websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
            context.next();
            return;
        }
        String key = request.getHeader("Sec-WebSocket-Key");
        String region = context.pathParam("region");
        String arn = request.getParam("X-Amz-ChannelARN");
        String clientId = request.getParam("X-Amz-ClientId");
        String account = account(request.getParam("X-Amz-Credential"), config.defaultAccountId());
        if (key == null || arn == null) {
            request.response().setStatusCode(400).end("Missing Sec-WebSocket-Key or X-Amz-ChannelARN.");
            return;
        }
        ObjectNode found = RequestScopes.callAs(account, region, () -> service.channelByArn(account, region, arn));
        if (found == null || !"ACTIVE".equals(found.path("ChannelStatus").asText())) {
            request.response().setStatusCode(404).end("The signaling channel " + arn + " doesn't exist.");
            return;
        }
        int ttl = found.path("SingleMasterConfiguration").path("MessageTtlSeconds").asInt(60);
        request.response().setStatusCode(101).putHeader("Upgrade", "websocket").putHeader("Connection", "Upgrade")
                .putHeader("Sec-WebSocket-Accept", TextWebSocket.accept(key));
        request.toNetSocket().onSuccess(socket -> {
            Channel channel = channel(arn);
            TextWebSocket[] holder = new TextWebSocket[1];
            holder[0] = new TextWebSocket(socket, text -> receive(channel, clientId, holder[0], text, ttl), () -> {
                if (clientId == null) {
                    if (channel.master == holder[0]) {
                        channel.master = null;
                    }
                } else {
                    channel.viewers.remove(clientId, holder[0]);
                }
            });
            if (clientId == null) {
                TextWebSocket previous = channel.master;
                channel.master = holder[0];
                if (previous != null && previous.isOpen()) {
                    previous.close(1000, "A newer master connection replaced this one.");
                }
                long now = System.currentTimeMillis();
                for (Object[] message : channel.held) {
                    channel.held.remove(message);
                    if ((Long) message[1] >= now) {
                        holder[0].send((String) message[0]);
                    }
                }
            } else {
                channel.viewers.put(clientId, holder[0]);
            }
        });
    }

    private void receive(Channel channel, String clientId, TextWebSocket socket, String text, int ttl) {
        JsonNode message;
        try {
            message = mapper().readTree(text);
        } catch (IOException e) {
            status(socket, null, "InvalidArgumentException", "The message is not JSON.");
            return;
        }
        String action = message.path("action").asText();
        String correlation = message.path("correlationId").asText(null);
        if (!List.of("SDP_OFFER", "SDP_ANSWER", "ICE_CANDIDATE").contains(action)) {
            status(socket, correlation, "InvalidArgumentException", "Unknown action " + action + ".");
            return;
        }
        String payload = message.path("messagePayload").asText(null);
        if (payload == null || payload.length() > 10_000) {
            status(socket, correlation, "InvalidArgumentException", "The messagePayload is missing or too long.");
            return;
        }
        ObjectNode delivered = mapper().createObjectNode();
        delivered.put("messageType", action).put("messagePayload", payload);
        if (clientId == null) {
            String recipient = message.path("recipientClientId").asText(null);
            if (recipient == null) {
                status(socket, correlation, "InvalidArgumentException", "The master's messages need a "
                        + "recipientClientId.");
                return;
            }
            CompletableFuture<String> waiting = channel.answers.get(recipient);
            if (waiting != null && "SDP_ANSWER".equals(action)) {
                waiting.complete(payload);
                return;
            }
            TextWebSocket viewer = channel.viewers.get(recipient);
            if (viewer == null || !viewer.isOpen()) {
                status(socket, correlation, "InvalidClientException", "The viewer " + recipient + " isn't "
                        + "connected.");
                return;
            }
            viewer.send(delivered.toString());
        } else {
            delivered.put("senderClientId", clientId);
            deliverToMaster(channel, delivered.toString(), ttl);
        }
    }

    private void deliverToMaster(Channel channel, String message, int ttl) {
        TextWebSocket master = channel.master;
        if (master != null && master.isOpen()) {
            master.send(message);
        } else {
            channel.held.add(new Object[] {message, System.currentTimeMillis() + ttl * 1000L});
        }
    }

    private void status(TextWebSocket socket, String correlation, String error, String description) {
        if (correlation == null) {
            return;
        }
        ObjectNode out = mapper().createObjectNode();
        out.put("messageType", "STATUS_RESPONSE");
        out.putObject("statusResponse").put("correlationId", correlation).put("errorType", error)
                .put("statusCode", "400").put("description", description);
        socket.send(out.toString());
    }

    // ---------------------------------------------------------------- HTTPS API

    private ObjectNode channelRecord(String account, String region, String arn) {
        return service.activeChannel(account, region, mapper().createObjectNode().put("ChannelARN", arn));
    }

    public ObjectNode getIceServerConfig(JsonNode in, String region) {
        String account = store.account();
        ObjectNode channel = channelRecord(account, region, text(in, "ChannelARN"));
        if (in.hasNonNull("Service") && !"TURN".equals(text(in, "Service"))) {
            throw invalid("Service must be TURN.");
        }
        String host = URI.create(service.endpoint()).getHost();
        long expiry = System.currentTimeMillis() / 1000 + 300;
        ObjectNode out = mapper().createObjectNode();
        ArrayNode list = out.putArray("IceServerList");
        for (int i = 0; i < 2; i++) {
            String username = expiry + ":" + channel.path("ChannelName").asText() + (i == 0 ? "" : ":" + i);
            ObjectNode server = list.addObject();
            ArrayNode uris = server.putArray("Uris");
            uris.add("turn:" + host + ":443?transport=udp");
            uris.add("turns:" + host + ":443?transport=udp");
            uris.add("turns:" + host + ":443?transport=tcp");
            server.put("Username", username).put("Password", hmac(username)).put("Ttl", 300);
        }
        return out;
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    public ObjectNode sendAlexaOfferToMaster(JsonNode in, String region) {
        String account = store.account();
        String arn = text(in, "ChannelARN");
        ObjectNode record = channelRecord(account, region, arn);
        String sender = text(in, "SenderClientId");
        Channel channel = channel(arn);
        int ttl = record.path("SingleMasterConfiguration").path("MessageTtlSeconds").asInt(60);
        CompletableFuture<String> answer = new CompletableFuture<>();
        channel.answers.put(sender, answer);
        try {
            ObjectNode offer = mapper().createObjectNode();
            offer.put("messageType", "SDP_OFFER").put("messagePayload", text(in, "MessagePayload"))
                    .put("senderClientId", sender);
            deliverToMaster(channel, offer.toString(), ttl);
            ObjectNode out = mapper().createObjectNode();
            try {
                out.put("Answer", answer.get(ttl, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                // No answer within the message TTL.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                throw new AwsException("ClientLimitExceededException", "The offer couldn't be delivered.", 400);
            }
            return out;
        } finally {
            channel.answers.remove(sender, answer);
        }
    }

    private ObjectNode storageChannel(String account, String region, String arn) {
        ObjectNode channel = channelRecord(account, region, arn);
        JsonNode storage = meta(channel).path("storage");
        if (!"ENABLED".equals(storage.path("Status").asText())) {
            throw invalid("The channel " + arn + " doesn't have its MediaStorageConfiguration enabled.");
        }
        return channel;
    }

    public ObjectNode joinStorageSession(JsonNode in, String region) {
        String account = store.account();
        String arn = text(in, "channelArn");
        storageChannel(account, region, arn);
        Channel channel = channel(arn);
        if (channel.master == null || !channel.master.isOpen()) {
            throw KvsStore.notFound("The channel's master isn't connected.");
        }
        return mapper().createObjectNode();
    }

    public ObjectNode joinStorageSessionAsViewer(JsonNode in, String region) {
        String account = store.account();
        String arn = text(in, "channelArn");
        storageChannel(account, region, arn);
        Channel channel = channel(arn);
        if (channel.master == null || !channel.master.isOpen()) {
            throw KvsStore.notFound("The channel has no storage session: its master isn't connected.");
        }
        String clientId = text(in, "clientId");
        if (clientId == null || clientId.isBlank()) {
            throw invalid("clientId is required.");
        }
        return mapper().createObjectNode();
    }

    List<String> connectedViewers(String arn) {
        return new ArrayList<>(channel(arn).viewers.keySet());
    }
}
