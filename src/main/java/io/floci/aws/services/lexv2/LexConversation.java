package io.floci.aws.services.lexv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsEventStreamDecoder;
import io.floci.aws.core.common.AwsEventStreamEncoder;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code StartConversation}: a bidirectional event stream over HTTP/2 (or a chunked HTTP/1.1 request). The client's
 * events arrive as they're sent, each signed in an envelope: a {@code ConfigurationEvent} with the response content
 * type, session state and welcome messages, then text, DTMF digits (a turn ends at {@code #}) or 8 kHz PCM audio (a
 * turn ends at a chunk of silence; its words come from the {@code lex} entry of AI_MOCK_CONFIG). Each turn answers with
 * a {@code TranscriptEvent} for speech, an {@code IntentResultEvent}, a {@code TextResponseEvent} and, for an audio
 * response type, {@code AudioResponseEvent}s. {@code HeartbeatEvent}s keep an idle stream open; a
 * {@code DisconnectionEvent} ends it.
 */
@ApplicationScoped
public class LexConversation {

    private static final Logger LOG = Logger.getLogger(LexConversation.class);
    private static final Pattern PATH = Pattern.compile(
            "^/bots/([^/]+)/botAliases/([^/]+)/botLocales/([^/]+)/sessions/([^/]+)/conversation$");
    private static final Pattern CREDENTIAL = Pattern.compile("Credential=([^/,\\s]+)/[^/]+/([^/]+)/([^/]+)/");
    private static final Pattern ACCOUNT = Pattern.compile("\\d{12}");
    private static final long HEARTBEAT_MILLIS = 10_000;
    private static final int AUDIO_CHUNK = 8192;

    private final LexRuntime runtime;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final ServiceConfigAccess serviceConfigAccess;
    private final Vertx vertx;

    @Inject
    public LexConversation(LexRuntime runtime, LexStore store, EmulatorConfig config,
                           ServiceConfigAccess serviceConfigAccess, Vertx vertx) {
        this.runtime = runtime;
        this.mapper = store.mapper();
        this.config = config;
        this.serviceConfigAccess = serviceConfigAccess;
        this.vertx = vertx;
    }

    void register(@Observes Router router) {
        router.routeWithRegex(HttpMethod.POST, PATH.pattern()).order(-10).handler(this::start);
    }

    private void start(RoutingContext context) {
        HttpServerRequest request = context.request();
        Matcher credential = CREDENTIAL.matcher(String.valueOf(request.getHeader("Authorization")));
        if (!serviceConfigAccess.isEnabled("lexv2") || !credential.find() || !"lex".equals(credential.group(3))) {
            context.next();
            return;
        }
        Matcher path = PATH.matcher(request.path());
        if (!path.matches()) {
            context.next();
            return;
        }
        String account = ACCOUNT.matcher(credential.group(1)).matches() ? credential.group(1)
                : config.defaultAccountId();
        String region = credential.group(2);
        Conversation conversation = new Conversation(account, region, path.group(1), path.group(2), path.group(3),
                path.group(4), request.response());
        try {
            RequestScopes.callAs(account, region, () -> runtime.target(region, path.group(1), path.group(2),
                    path.group(3)));
        } catch (AwsException e) {
            HttpServerResponse response = request.response();
            response.setStatusCode(e.getHttpStatus()).putHeader("Content-Type", "application/json")
                    .putHeader("x-amzn-ErrorType", e.getErrorCode())
                    .end(mapper.createObjectNode().put("message", e.getMessage()).toString());
            return;
        }
        HttpServerResponse response = request.response();
        response.setStatusCode(200).setChunked(true).putHeader("Content-Type", "application/vnd.amazon.eventstream");
        response.putHeader("x-amzn-RequestId", LexStore.uuid());
        response.write(Buffer.buffer());
        conversation.heartbeat = vertx.setPeriodic(HEARTBEAT_MILLIS, id -> conversation.send("HeartbeatEvent",
                mapper.createObjectNode()));
        request.handler(conversation::receive);
        request.endHandler(v -> conversation.finish());
        request.exceptionHandler(e -> conversation.finish());
        response.closeHandler(v -> conversation.close());
    }

    /** One conversation's stream: the bytes received so far, its configuration and the turn being gathered. */
    private final class Conversation {
        final String account;
        final String region;
        final String botId;
        final String aliasId;
        final String localeId;
        final String sessionId;
        final HttpServerResponse response;
        final AtomicInteger events = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        ObjectNode configuration = mapper.createObjectNode();
        StringBuilder digits = new StringBuilder();
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        boolean heard;
        long heartbeat = -1;
        /** Serializes the turns: each runs on a worker after the one before it. */
        Future<Void> queue = Future.succeededFuture();

        Conversation(String account, String region, String botId, String aliasId, String localeId, String sessionId,
                     HttpServerResponse response) {
            this.account = account;
            this.region = region;
            this.botId = botId;
            this.aliasId = aliasId;
            this.localeId = localeId;
            this.sessionId = sessionId;
            this.response = response;
        }

        void receive(Buffer buffer) {
            pending.writeBytes(buffer.getBytes());
            byte[] bytes = pending.toByteArray();
            int offset = 0;
            while (bytes.length - offset >= 4) {
                int length = ByteBuffer.wrap(bytes, offset, 4).getInt();
                if (length < 16 || bytes.length - offset < length) {
                    break;
                }
                byte[] frame = new byte[length];
                System.arraycopy(bytes, offset, frame, 0, length);
                offset += length;
                handle(frame);
            }
            pending = new ByteArrayOutputStream();
            pending.write(bytes, offset, bytes.length - offset);
        }

        private void handle(byte[] frame) {
            AwsEventStreamDecoder.Message message;
            try {
                message = new AwsEventStreamDecoder(new ByteArrayInputStream(frame)).next();
                if (message != null && message.header(":chunk-signature") != null) {
                    message = message.payload().length == 0 ? null
                            : new AwsEventStreamDecoder(new ByteArrayInputStream(message.payload())).next();
                }
            } catch (IOException e) {
                error("ValidationException", "The event stream is malformed: " + e.getMessage());
                return;
            }
            if (message == null) {
                return;
            }
            String type = message.header(":event-type");
            JsonNode event;
            try {
                event = message.payload().length == 0 ? mapper.createObjectNode() : mapper.readTree(message.payload());
            } catch (IOException e) {
                error("ValidationException", "The " + type + " payload is not valid JSON.");
                return;
            }
            enqueue(() -> dispatch(type, event));
        }

        private void enqueue(Runnable work) {
            queue = queue.compose(v -> vertx.executeBlocking(() -> {
                RequestScopes.runAs(account, region, work);
                return null;
            }, true));
        }

        private void dispatch(String type, JsonNode event) {
            if (closed.get()) {
                return;
            }
            try {
                switch (type == null ? "" : type) {
                    case "ConfigurationEvent" -> configure(event);
                    case "TextInputEvent" -> turn(event.path("text").asText(), "Text", null);
                    case "DTMFInputEvent" -> {
                        String character = event.path("inputCharacter").asText();
                        String end = configuration.path("sessionState").path("sessionAttributes")
                                .path("x-amz-lex:dtmf:end-character").asText("#");
                        String delete = configuration.path("sessionState").path("sessionAttributes")
                                .path("x-amz-lex:dtmf:deletion-character").asText("*");
                        if (end.equals(character)) {
                            String input = digits.toString();
                            digits.setLength(0);
                            if (!input.isEmpty()) {
                                turn(input, "DTMF", null);
                            }
                        } else if (delete.equals(character)) {
                            digits.setLength(0);
                        } else {
                            digits.append(character);
                        }
                    }
                    case "AudioInputEvent" -> audio(event);
                    case "PlaybackCompletionEvent" -> {
                        // the client finished playing the last response; nothing waits on it here
                    }
                    case "DisconnectionEvent" -> close();
                    default -> error("ValidationException", "The event type " + type + " is not valid.");
                }
            } catch (AwsException e) {
                error(e.getErrorCode(), e.getMessage());
            }
        }

        private void configure(JsonNode event) {
            if (!event.hasNonNull("responseContentType")) {
                throw LexStore.validation("ConfigurationEvent.responseContentType is required.");
            }
            configuration = event.deepCopy();
            JsonNode state = event.path("sessionState");
            JsonNode welcome = event.path("welcomeMessages");
            if (state.isObject() || welcome.isArray()) {
                ObjectNode put = mapper.createObjectNode();
                put.put("botId", botId).put("botAliasId", aliasId).put("localeId", localeId).put("sessionId",
                        sessionId);
                ObjectNode sessionState = state.isObject() ? state.deepCopy() : mapper.createObjectNode();
                if (!sessionState.has("dialogAction")) {
                    sessionState.putObject("dialogAction").put("type", "ElicitIntent");
                }
                put.set("sessionState", sessionState);
                if (welcome.isArray()) {
                    put.set("messages", welcome.deepCopy());
                }
                put.put("responseContentType", "text/plain; charset=utf-8");
                runtime.putSession(put, region);
            }
            if (welcome.isArray() && !welcome.isEmpty()) {
                ObjectNode text = mapper.createObjectNode();
                text.set("messages", welcome.deepCopy());
                send("TextResponseEvent", text);
                speak(welcome);
            }
        }

        private void audio(JsonNode event) {
            byte[] chunk = event.hasNonNull("audioChunk") ? Base64.getDecoder().decode(event.get("audioChunk")
                    .asText()) : new byte[0];
            boolean silent = silent(chunk);
            if (!silent) {
                heard = true;
                audio.writeBytes(chunk);
                return;
            }
            if (heard) {
                byte[] utterance = audio.toByteArray();
                audio = new ByteArrayOutputStream();
                heard = false;
                String transcript = runtime.transcriptOf(utterance);
                ObjectNode transcriptEvent = mapper.createObjectNode();
                transcriptEvent.put("transcript", transcript);
                send("TranscriptEvent", transcriptEvent);
                if (!transcript.isBlank()) {
                    turn(transcript, "Speech", utterance);
                }
            }
        }

        private void turn(String text, String mode, byte[] speech) {
            String responseType = configuration.path("responseContentType").asText("text/plain; charset=utf-8");
            LexRuntime.Target target = runtime.target(region, botId, aliasId, localeId);
            ObjectNode result = runtime.converse(target, sessionId, text, mode, null,
                    configuration.get("requestAttributes"), responseType, speech);
            ObjectNode intent = mapper.createObjectNode();
            intent.put("inputMode", mode);
            intent.set("interpretations", result.path("interpretations").deepCopy());
            intent.set("sessionState", result.path("sessionState").deepCopy());
            if (result.has("requestAttributes")) {
                intent.set("requestAttributes", result.get("requestAttributes").deepCopy());
            }
            intent.put("sessionId", sessionId);
            if (result.has("recognizedBotMember")) {
                intent.set("recognizedBotMember", result.get("recognizedBotMember").deepCopy());
            }
            send("IntentResultEvent", intent);
            if (!result.path("messages").isEmpty()) {
                ObjectNode reply = mapper.createObjectNode();
                reply.set("messages", result.path("messages").deepCopy());
                send("TextResponseEvent", reply);
                speak(result.path("messages"));
            }
        }

        /** Sends the messages as speech when the configuration asks for audio and playback isn't disabled. */
        private void speak(JsonNode messages) {
            String responseType = configuration.path("responseContentType").asText("text/plain");
            if (!responseType.startsWith("audio/") || configuration.path("disablePlayback").asBoolean(false)) {
                return;
            }
            ObjectNode result = mapper.createObjectNode();
            result.set("messages", messages.deepCopy());
            LexRuntime.Target target = runtime.target(region, botId, aliasId, localeId);
            LexRuntime.Spoken spoken = runtime.speak(target, result, responseType);
            byte[] bytes = spoken.audio();
            for (int offset = 0; offset < bytes.length; offset += AUDIO_CHUNK) {
                byte[] chunk = new byte[Math.min(AUDIO_CHUNK, bytes.length - offset)];
                System.arraycopy(bytes, offset, chunk, 0, chunk.length);
                ObjectNode event = mapper.createObjectNode();
                event.put("audioChunk", Base64.getEncoder().encodeToString(chunk));
                event.put("contentType", responseType);
                send("AudioResponseEvent", event);
            }
            ObjectNode last = mapper.createObjectNode();
            last.put("audioChunk", "");
            last.put("contentType", responseType);
            send("AudioResponseEvent", last);
        }

        void send(String type, ObjectNode event) {
            if (closed.get()) {
                return;
            }
            event.put("eventId", "RESPONSE-" + events.incrementAndGet());
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            headers.put(":message-type", "event");
            headers.put(":event-type", type);
            headers.put(":content-type", "application/json");
            write(headers, event.toString());
        }

        private void error(String code, String message) {
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            headers.put(":message-type", "exception");
            headers.put(":exception-type", code);
            headers.put(":content-type", "application/json");
            write(headers, mapper.createObjectNode().put("message", message).toString());
        }

        private void write(Map<String, String> headers, String payload) {
            byte[] frame;
            try {
                frame = AwsEventStreamEncoder.encodeMessage(new LinkedHashMap<>(headers),
                        payload.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                LOG.debugv("Lex conversation event could not be encoded: {0}", e.getMessage());
                return;
            }
            vertx.runOnContext(v -> {
                if (!closed.get() && !response.ended()) {
                    response.write(Buffer.buffer(frame));
                }
            });
        }

        void finish() {
            queue.onComplete(v -> close());
        }

        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (heartbeat >= 0) {
                vertx.cancelTimer(heartbeat);
            }
            vertx.runOnContext(v -> {
                if (!response.ended()) {
                    response.end();
                }
            });
        }
    }

    /** Whether 16-bit little-endian PCM is silence: no sample louder than about -40 dBFS. */
    static boolean silent(byte[] pcm) {
        if (pcm.length < 2) {
            return true;
        }
        ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        while (samples.remaining() >= 2) {
            if (Math.abs(samples.getShort()) > 328) {
                return false;
            }
        }
        return true;
    }
}
