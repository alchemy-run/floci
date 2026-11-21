package io.floci.aws.services.lexv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AiMockConfigLoader;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.bedrockagent.BedrockAgentRuntimeService;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.kendra.KendraQueries;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.polly.PollyService;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The Lex V2 runtime: conversations with a bot alias's locale. Each turn classifies what the user said with the built
 * model ({@link LexNlu}), or fills the slot or answers the confirmation it was asked for, then walks the intent's
 * conversation flow: the initial response, the dialog code hook, slot elicitation in priority order, confirmation,
 * fulfillment (the fulfillment code hook, or {@code ReadyForFulfillment} without one) and the closing response, with
 * conditional branches and next steps. Code hooks are the alias locale's Lambda function, invoked with the Lex V2 1.0
 * event. Sessions expire after the bot's idle session TTL. Each turn is kept for the analytics APIs and written to the
 * alias's text conversation log; speech input is logged to its audio log bucket.
 */
@ApplicationScoped
public class LexRuntime {

    private static final Logger LOG = Logger.getLogger(LexRuntime.class);
    private static final Pattern SLOT_PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)\\}");
    private static final Pattern SESSION_PLACEHOLDER = Pattern.compile("\\[([A-Za-z0-9_.:-]+)\\]");
    private static final Pattern REQUEST_PLACEHOLDER = Pattern.compile("\\(\\(([A-Za-z0-9_.:-]+)\\)\\)");
    private static final Set<String> POSITIVE = Set.of("good", "great", "love", "thanks", "thank", "awesome",
            "excellent", "happy", "wonderful", "perfect", "nice", "amazing", "glad");
    private static final Set<String> NEGATIVE = Set.of("bad", "terrible", "hate", "awful", "angry", "upset",
            "worst", "horrible", "sad", "annoyed", "frustrated", "wrong", "broken");

    private final LexStore store;
    private final LexBots bots;
    private final ObjectMapper mapper;
    private final Instance<LambdaService> lambda;
    private final Instance<CloudWatchLogsService> logs;
    private final Instance<S3Service> s3;
    private final Instance<PollyService> polly;
    private final Instance<KendraQueries> kendra;
    private final Instance<BedrockAgentRuntimeService> bedrock;
    private final AiMockConfigLoader mocks;

    @Inject
    public LexRuntime(LexStore store, LexBots bots, Instance<LambdaService> lambda,
                      Instance<CloudWatchLogsService> logs, Instance<S3Service> s3, Instance<PollyService> polly,
                      Instance<KendraQueries> kendra, Instance<BedrockAgentRuntimeService> bedrock,
                      AiMockConfigLoader mocks) {
        this.store = store;
        this.bots = bots;
        this.mapper = store.mapper();
        this.lambda = lambda;
        this.logs = logs;
        this.s3 = s3;
        this.polly = polly;
        this.kendra = kendra;
        this.bedrock = bedrock;
        this.mocks = mocks;
    }

    /** What an utterance or session call returns besides its JSON: the input mode, transcript and response audio. */
    public record Spoken(ObjectNode result, String inputMode, String transcript, byte[] audio, String contentType) {
    }

    // ================================================================ operations

    public ObjectNode recognizeText(ObjectNode in, String region) {
        String text = in.path("text").asText();
        Target target = target(region, in);
        return converse(target, in.path("sessionId").asText(), text, "Text", in.get("sessionState"),
                in.get("requestAttributes"), "text/plain; charset=utf-8", null);
    }

    public Spoken recognizeUtterance(ObjectNode in, byte[] body, String region) {
        Target target = target(region, in);
        String requestType = in.path("requestContentType").asText("").toLowerCase(Locale.ROOT);
        String responseType = in.path("responseContentType").asText("audio/mpeg");
        JsonNode state = decodeHeader(in.path("sessionState").asText(null), "sessionState");
        JsonNode attributes = decodeHeader(in.path("requestAttributes").asText(null), "requestAttributes");
        byte[] input = body == null ? new byte[0] : body;
        String mode;
        String text;
        if (requestType.startsWith("text/plain")) {
            mode = "Text";
            text = new String(input, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                throw LexStore.validation("The text input is empty.");
            }
        } else if (requestType.startsWith("audio/l16") || requestType.startsWith("audio/x-l16")
                || requestType.startsWith("audio/lpcm") || requestType.startsWith("audio/x-cbr-opus-with-preamble")) {
            mode = "Speech";
            text = transcript(input);
        } else {
            throw LexStore.validation("The request content type " + requestType + " is not supported.");
        }
        checkResponseType(responseType, target);
        ObjectNode result = converse(target, in.path("sessionId").asText(), text, mode, state, attributes,
                responseType, "Speech".equals(mode) ? input : null);
        return spoken(target, result, mode, text, responseType);
    }

    public ObjectNode getSession(ObjectNode in, String region) {
        Target target = target(region, in);
        String sessionId = in.path("sessionId").asText();
        ObjectNode session = session(target, sessionId)
                .orElseThrow(() -> LexStore.notFound("The session " + sessionId + " does not exist."));
        ObjectNode out = mapper.createObjectNode();
        out.put("sessionId", sessionId);
        out.set("messages", session.path("messages").deepCopy());
        out.set("interpretations", session.path("interpretations").deepCopy());
        out.set("sessionState", session.path("sessionState").deepCopy());
        return out;
    }

    public Spoken putSession(ObjectNode in, String region) {
        Target target = target(region, in);
        String sessionId = in.path("sessionId").asText();
        String responseType = in.path("responseContentType").asText("audio/mpeg");
        checkResponseType(responseType, target);
        JsonNode state = in.path("sessionState");
        String action = state.path("dialogAction").path("type").asText();
        ObjectNode result;
        if ("Delegate".equals(action)) {
            result = converse(target, sessionId, null, "Text", state, in.get("requestAttributes"), responseType,
                    null);
        } else {
            if (("ElicitSlot".equals(action) || "ConfirmIntent".equals(action) || "Close".equals(action))
                    && !state.has("intent")) {
                throw LexStore.validation("A " + action + " dialog action needs an intent.");
            }
            if ("ElicitSlot".equals(action) && !state.path("dialogAction").hasNonNull("slotToElicit")) {
                throw LexStore.validation("An ElicitSlot dialog action needs slotToElicit.");
            }
            ObjectNode session = session(target, sessionId).orElseGet(this::newSession);
            ObjectNode sessionState = state.deepCopy();
            if (sessionState.has("intent")) {
                fillSlots(target.model(), (ObjectNode) sessionState.get("intent"));
            }
            if (!sessionState.has("sessionAttributes")) {
                sessionState.putObject("sessionAttributes");
            }
            sessionState.put("originatingRequestId", LexStore.uuid());
            session.set("sessionState", sessionState);
            session.set("messages", in.path("messages").isArray() ? in.get("messages").deepCopy()
                    : mapper.createArrayNode());
            session.set("interpretations", mapper.createArrayNode());
            LexStore.meta(session).putObject("attempts");
            saveSession(target, sessionId, session);
            result = mapper.createObjectNode();
            result.set("messages", session.get("messages").deepCopy());
            result.set("sessionState", sessionState.deepCopy());
            if (in.has("requestAttributes")) {
                result.set("requestAttributes", in.get("requestAttributes").deepCopy());
            }
            result.put("sessionId", sessionId);
        }
        return spoken(target, result, null, null, responseType);
    }

    public ObjectNode deleteSession(ObjectNode in, String region) {
        Target target = target(region, in);
        String sessionId = in.path("sessionId").asText();
        if (session(target, sessionId).isEmpty()) {
            throw LexStore.notFound("The session " + sessionId + " does not exist.");
        }
        store.delete(target.region(), LexStore.SESSION, sessionPath(target, sessionId));
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", target.botId());
        out.put("botAliasId", target.aliasId());
        out.put("localeId", target.localeId());
        out.put("sessionId", sessionId);
        return out;
    }

    // ================================================================ targets and sessions

    /** The alias's version and locale a conversation runs against, with its built model and code hook. */
    record Target(String region, String botId, ObjectNode bot, String aliasId, ObjectNode alias, String version,
                  String localeId, ObjectNode model, String hookArn) {
    }

    Target target(String region, JsonNode in) {
        return target(region, in.path("botId").asText(), in.path("botAliasId").asText(),
                in.path("localeId").asText());
    }

    Target target(String region, String botId, String aliasId, String localeId) {
        ObjectNode bot = store.live(region, LexStore.BOT, botId)
                .filter(b -> !LexStore.meta(b).has("deleteAt"))
                .orElseThrow(() -> LexStore.notFound("The bot " + botId + " does not exist."));
        ObjectNode alias = store.live(region, LexStore.ALIAS, botId + "/" + aliasId)
                .filter(a -> !LexStore.meta(a).has("deleteAt"))
                .orElseThrow(() -> LexStore.notFound("The alias " + aliasId + " of bot " + botId
                        + " does not exist."));
        String version = alias.path("botVersion").asText(null);
        if (version == null) {
            throw LexStore.notFound("The alias " + aliasId + " is not associated with a bot version.");
        }
        JsonNode settings = alias.path("botAliasLocaleSettings").path(localeId);
        if (settings.isObject() && !settings.path("enabled").asBoolean(true)) {
            throw LexStore.validation("The locale " + localeId + " is not enabled for the alias " + aliasId + ".");
        }
        ObjectNode model;
        if ("BotNetwork".equals(bot.path("botType").asText())) {
            model = networkModel(region, bot, localeId);
        } else {
            if (store.live(region, LexStore.LOCALE, botId + "/" + version + "/" + localeId).isEmpty()) {
                throw LexStore.notFound("The version " + version + " of bot " + botId + " has no locale "
                        + localeId + ".");
            }
            model = bots.model(region, botId, version, localeId)
                    .orElseThrow(() -> LexStore.notFound("The locale " + localeId + " of bot " + botId
                            + " has not been built. Build the locale and try again."));
        }
        String hook = settings.path("codeHookSpecification").path("lambdaCodeHook").path("lambdaARN")
                .asText(null);
        return new Target(region, botId, bot, aliasId, alias, version, localeId, model, hook);
    }

    /** A bot network's model: its members' built models, each intent tagged with the member it belongs to. */
    private ObjectNode networkModel(String region, ObjectNode network, String localeId) {
        ObjectNode model = mapper.createObjectNode();
        ArrayNode intents = model.putArray("intents");
        ArrayNode slots = model.putArray("slots");
        ArrayNode types = model.putArray("slotTypes");
        boolean any = false;
        for (JsonNode member : network.path("botMembers")) {
            String memberId = member.path("botMemberId").asText();
            Optional<ObjectNode> memberModel = bots.model(region, memberId, member.path("botMemberVersion").asText(),
                    localeId);
            if (memberModel.isEmpty()) {
                continue;
            }
            any = true;
            if (!model.has("locale")) {
                model.set("locale", memberModel.get().path("locale").deepCopy());
            }
            for (JsonNode intent : memberModel.get().path("intents")) {
                ObjectNode copy = intent.deepCopy();
                copy.put("_member", memberId);
                copy.put("_memberName", member.path("botMemberName").asText());
                if (LexStore.FALLBACK_ID.equals(copy.path("intentId").asText())) {
                    if (intents.findValues("intentId").stream().anyMatch(i -> LexStore.FALLBACK_ID.equals(i.asText()))) {
                        continue;
                    }
                }
                intents.add(copy);
            }
            memberModel.get().path("slots").forEach(slots::add);
            memberModel.get().path("slotTypes").forEach(types::add);
        }
        if (!any) {
            throw LexStore.notFound("No member of the bot network has a built locale " + localeId + ".");
        }
        return model;
    }

    private static String sessionPath(Target target, String sessionId) {
        return target.botId() + "/" + target.aliasId() + "/" + target.localeId() + "/" + sessionId;
    }

    private Optional<ObjectNode> session(Target target, String sessionId) {
        String path = sessionPath(target, sessionId);
        Optional<ObjectNode> session = store.get(target.region(), LexStore.SESSION, path);
        if (session.isPresent()) {
            long ttl = target.bot().path("idleSessionTTLInSeconds").asLong(300) * 1000;
            if (session.get().path("_").path("lastActive").asLong() + ttl < LexStore.millis()) {
                store.delete(target.region(), LexStore.SESSION, path);
                return Optional.empty();
            }
        }
        return session;
    }

    private ObjectNode newSession() {
        ObjectNode session = store.record();
        ObjectNode state = session.putObject("sessionState");
        state.putObject("sessionAttributes");
        session.putArray("messages");
        session.putArray("interpretations");
        LexStore.meta(session).putObject("attempts");
        return session;
    }

    private void saveSession(Target target, String sessionId, ObjectNode session) {
        LexStore.meta(session).put("lastActive", LexStore.millis());
        store.put(target.region(), LexStore.SESSION, sessionPath(target, sessionId), session);
    }

    // ================================================================ a turn

    /**
     * One turn of a conversation: {@code text} is what the user said, or null when a session is delegated to Lex.
     * {@code state}, when given, replaces the session's state for this turn.
     */
    ObjectNode converse(Target target, String sessionId, String text, String inputMode, JsonNode state,
                        JsonNode requestAttributes, String responseContentType, byte[] audio) {
        long started = LexStore.millis();
        ObjectNode session = session(target, sessionId).orElseGet(this::newSession);
        boolean newSession = !session.path("_").has("lastActive");
        Turn turn = new Turn(target, session, sessionId, text, inputMode, requestAttributes, responseContentType);
        if (state != null && state.isObject()) {
            turn.state = state.deepCopy();
            if (!turn.state.has("sessionAttributes")) {
                turn.state.putObject("sessionAttributes");
            }
        }
        turn.ageContexts();
        turn.run();
        turn.finish();
        session.set("sessionState", turn.state.deepCopy());
        session.set("messages", turn.messages.deepCopy());
        session.set("interpretations", turn.interpretations.deepCopy());
        saveSession(target, sessionId, session);

        ObjectNode out = mapper.createObjectNode();
        out.set("messages", turn.messages);
        out.set("sessionState", turn.state);
        out.set("interpretations", turn.interpretations);
        if (requestAttributes != null && requestAttributes.isObject() && !requestAttributes.isEmpty()) {
            out.set("requestAttributes", requestAttributes.deepCopy());
        }
        out.put("sessionId", sessionId);
        if (turn.member != null) {
            out.putObject("recognizedBotMember").put("botId", turn.member).put("botName", turn.memberName);
        }
        record(target, sessionId, turn, newSession, LexStore.millis() - started);
        log(target, sessionId, turn, out, audio);
        return out;
    }

    /** The mutable state of one turn. */
    private final class Turn {
        final Target target;
        final ObjectNode session;
        final String sessionId;
        final String text;
        final String inputMode;
        final JsonNode requestAttributes;
        final String responseContentType;
        final String requestId = LexStore.uuid();
        ObjectNode state;
        ArrayNode messages = mapper.createArrayNode();
        ArrayNode interpretations = mapper.createArrayNode();
        JsonNode intentRecord;
        boolean hookUsed;
        boolean missed;
        String member;
        String memberName;
        JsonNode sentiment;

        Turn(Target target, ObjectNode session, String sessionId, String text, String inputMode,
             JsonNode requestAttributes, String responseContentType) {
            this.target = target;
            this.session = session;
            this.sessionId = sessionId;
            this.text = text;
            this.inputMode = inputMode;
            this.requestAttributes = requestAttributes == null ? mapper.createObjectNode() : requestAttributes;
            this.responseContentType = responseContentType;
            this.state = session.path("sessionState").deepCopy();
            if (!state.has("sessionAttributes")) {
                state.putObject("sessionAttributes");
            }
        }

        ObjectNode intent() {
            return state.get("intent") instanceof ObjectNode i ? i : null;
        }

        ObjectNode attempts() {
            ObjectNode meta = LexStore.meta(session);
            return meta.get("attempts") instanceof ObjectNode a ? a : meta.putObject("attempts");
        }

        /** Counts this turn against each active context's turns and drops the contexts that have expired. */
        void ageContexts() {
            if (!(state.get("activeContexts") instanceof ArrayNode contexts)) {
                return;
            }
            ObjectNode expiry = LexStore.meta(session).get("contextExpiry") instanceof ObjectNode e ? e
                    : LexStore.meta(session).putObject("contextExpiry");
            long now = LexStore.millis();
            for (int i = contexts.size() - 1; i >= 0; i--) {
                ObjectNode context = (ObjectNode) contexts.get(i);
                String name = context.path("name").asText();
                ObjectNode ttl = context.withObject("/timeToLive");
                if (!expiry.has(name)) {
                    expiry.put(name, now + ttl.path("timeToLiveInSeconds").asLong(86400) * 1000);
                }
                if (text != null) {
                    ttl.put("turnsToLive", ttl.path("turnsToLive").asInt(20) - 1);
                }
                if (ttl.path("turnsToLive").asInt(1) < 0 || expiry.path(name).asLong() < now) {
                    contexts.remove(i);
                    expiry.remove(name);
                }
            }
        }

        Set<String> activeContexts() {
            Set<String> names = new HashSet<>();
            state.path("activeContexts").forEach(c -> names.add(c.path("name").asText()));
            return names;
        }

        void run() {
            if (text == null) {
                ObjectNode current = intent();
                if (current == null) {
                    state.putObject("dialogAction").put("type", "ElicitIntent");
                    return;
                }
                intentRecord = intentByName(current.path("name").asText());
                if (intentRecord == null) {
                    throw LexStore.validation("The intent " + current.path("name").asText() + " does not exist.");
                }
                fillSlots(target.model(), current);
                flow(Step.HOOK);
                return;
            }
            sentiment = sentiment(text);
            String action = state.path("dialogAction").path("type").asText("");
            ObjectNode current = intent();
            boolean active = current != null && !Set.of("Fulfilled", "Failed", "ReadyForFulfillment")
                    .contains(current.path("state").asText()) && !"Close".equals(action);
            if (active && "ElicitSlot".equals(action)) {
                intentRecord = intentByName(current.path("name").asText());
                if (intentRecord != null) {
                    slotInput(current, state.path("dialogAction").path("slotToElicit").asText());
                    return;
                }
            }
            if (active && "ConfirmIntent".equals(action)) {
                intentRecord = intentByName(current.path("name").asText());
                if (intentRecord != null) {
                    confirmationInput(current);
                    return;
                }
            }
            classifyAndStart();
        }

        JsonNode intentByName(String name) {
            for (JsonNode intent : target.model().path("intents")) {
                if (name.equals(intent.path("intentName").asText())) {
                    return intent;
                }
            }
            return null;
        }

        JsonNode slotByName(JsonNode intent, String name) {
            for (JsonNode slot : LexNlu.slotsOf(target.model(), intent)) {
                if (name.equals(slot.path("slotName").asText())) {
                    return slot;
                }
            }
            return null;
        }

        void classifyAndStart() {
            List<LexNlu.Candidate> candidates = LexNlu.classify(mapper, target.model(), text, activeContexts());
            double threshold = target.model().path("locale").path("nluIntentConfidenceThreshold").asDouble(0.4);
            LexNlu.Candidate top = !candidates.isEmpty() && candidates.get(0).score() >= threshold
                    && candidates.get(0).score() > 0 ? candidates.get(0) : null;
            JsonNode fallback = null;
            JsonNode generative = null;
            for (JsonNode intent : target.model().path("intents")) {
                String signature = intent.path("parentIntentSignature").asText("");
                if (LexStore.FALLBACK_SIGNATURE.equals(signature)) {
                    fallback = intent;
                } else if (LexNlu.generative(signature) && generative == null) {
                    generative = intent;
                }
            }
            JsonNode chosen;
            ObjectNode slots;
            if (top != null) {
                chosen = top.intent();
                slots = top.slots();
            } else if (generative != null) {
                chosen = generative;
                slots = mapper.createObjectNode();
            } else {
                chosen = fallback;
                slots = mapper.createObjectNode();
                missed = true;
            }
            interpretations = mapper.createArrayNode();
            ObjectNode first = interpretations.addObject();
            if (top != null) {
                first.putObject("nluConfidence").put("score", top.score());
            }
            first.put("interpretationSource", "Lex");
            if (chosen != fallback && fallback != null) {
                interpretations.addObject().put("interpretationSource", "Lex").putObject("intent")
                        .put("name", LexStore.FALLBACK).putObject("slots");
            }
            int others = 0;
            for (LexNlu.Candidate candidate : candidates) {
                if (candidate == top || candidate.score() <= 0 || others >= 3) {
                    continue;
                }
                ObjectNode other = interpretations.addObject();
                other.putObject("nluConfidence").put("score", candidate.score());
                other.put("interpretationSource", "Lex");
                ObjectNode intent = other.putObject("intent");
                intent.put("name", candidate.intent().path("intentName").asText());
                intent.set("slots", allSlots(target.model(), candidate.intent(), candidate.slots()));
                others++;
            }
            if (sentiment != null && target.alias().path("sentimentAnalysisSettings").path("detectSentiment")
                    .asBoolean(false)) {
                interpretations.forEach(i -> ((ObjectNode) i).set("sentimentResponse", sentiment.deepCopy()));
            }
            if (chosen == null) {
                state.putObject("dialogAction").put("type", "ElicitIntent");
                state.remove("intent");
                return;
            }
            start(chosen, slots);
        }

        void start(JsonNode chosen, ObjectNode slots) {
            intentRecord = chosen;
            if (chosen.has("_member")) {
                member = chosen.path("_member").asText();
                memberName = chosen.path("_memberName").asText();
            }
            ObjectNode intent = mapper.createObjectNode();
            intent.put("name", chosen.path("intentName").asText());
            intent.set("slots", allSlots(target.model(), chosen, slots));
            intent.put("state", "InProgress");
            intent.put("confirmationState", "None");
            state.set("intent", intent);
            attempts().removeAll();
            flow(Step.START);
        }

        void slotInput(ObjectNode intent, String slotName) {
            JsonNode slot = slotByName(intentRecord, slotName);
            if (slot == null) {
                classifyAndStart();
                return;
            }
            Optional<ObjectNode> value = LexNlu.slotValue(mapper, target.model(), slot, text);
            ObjectNode interpretation = mapper.createObjectNode();
            interpretation.put("interpretationSource", "Lex");
            interpretation.putObject("nluConfidence").put("score", 1.0);
            interpretations = mapper.createArrayNode();
            interpretations.add(interpretation);
            if (value.isPresent()) {
                intent.withObject("/slots").set(slotName, value.get());
                attempts().remove(slotName);
                JsonNode capture = slot.path("valueElicitationSetting").path("slotCaptureSetting");
                respond(capture.path("captureResponse"));
                Step next = nextStep(capture.path("captureNextStep"), capture.path("captureConditional"));
                flow(next == null ? Step.HOOK : next);
                return;
            }
            List<LexNlu.Candidate> candidates = LexNlu.classify(mapper, target.model(), text, activeContexts());
            if (!candidates.isEmpty() && candidates.get(0).score() >= 0.9
                    && !candidates.get(0).intent().path("intentName").asText().equals(intent.path("name").asText())) {
                interpretations = mapper.createArrayNode();
                interpretations.addObject().put("interpretationSource", "Lex").putObject("nluConfidence")
                        .put("score", candidates.get(0).score());
                start(candidates.get(0).intent(), candidates.get(0).slots());
                return;
            }
            int attempt = attempts().path(slotName).asInt(0) + 1;
            attempts().put(slotName, attempt);
            JsonNode prompt = slot.path("valueElicitationSetting").path("promptSpecification");
            if (attempt > prompt.path("maxRetries").asInt(2)) {
                JsonNode capture = slot.path("valueElicitationSetting").path("slotCaptureSetting");
                respond(capture.path("failureResponse"));
                Step next = nextStep(capture.path("failureNextStep"), capture.path("failureConditional"));
                if (next != null) {
                    flow(next);
                    return;
                }
                intent.put("state", "Failed");
                state.putObject("dialogAction").put("type", "Close");
                return;
            }
            elicit(slot);
        }

        void confirmationInput(ObjectNode intent) {
            Optional<String> answer = LexValues.builtIn("AMAZON.Confirmation", text);
            JsonNode setting = intentRecord.path("intentConfirmationSetting");
            interpretations = mapper.createArrayNode();
            interpretations.addObject().put("interpretationSource", "Lex").putObject("nluConfidence")
                    .put("score", 1.0);
            if (answer.isPresent() && "Yes".equals(answer.get())) {
                intent.put("confirmationState", "Confirmed");
                attempts().remove("_confirmation");
                respond(setting.path("confirmationResponse"));
                Step next = nextStep(setting.path("confirmationNextStep"), setting.path("confirmationConditional"));
                flow(next == null ? Step.HOOK : next);
                return;
            }
            if (answer.isPresent() && "No".equals(answer.get())) {
                intent.put("confirmationState", "Denied");
                attempts().remove("_confirmation");
                respond(setting.path("declinationResponse"));
                Step next = nextStep(setting.path("declinationNextStep"), setting.path("declinationConditional"));
                if (next != null) {
                    flow(next);
                    return;
                }
                if (dialogHook()) {
                    flow(Step.HOOK);
                    return;
                }
                intent.put("state", "Failed");
                state.putObject("dialogAction").put("type", "Close");
                return;
            }
            int attempt = attempts().path("_confirmation").asInt(0) + 1;
            attempts().put("_confirmation", attempt);
            if (attempt > setting.path("promptSpecification").path("maxRetries").asInt(2)) {
                respond(setting.path("failureResponse"));
                Step next = nextStep(setting.path("failureNextStep"), setting.path("failureConditional"));
                if (next != null) {
                    flow(next);
                    return;
                }
                intent.put("state", "Failed");
                state.putObject("dialogAction").put("type", "Close");
                return;
            }
            confirm();
        }

        // ------------------------------------------------------------ the conversation flow

        void flow(Step first) {
            Step step = first;
            for (int guard = 0; step != null && guard < 32; guard++) {
                step = execute(step);
            }
        }

        private Step execute(Step step) {
            ObjectNode intent = intent();
            switch (step.kind) {
                case START -> {
                    String signature = intentRecord.path("parentIntentSignature").asText("");
                    if (LexNlu.generative(signature)) {
                        answer(intent, signature);
                        return Step.CLOSE;
                    }
                    JsonNode initial = intentRecord.path("initialResponseSetting");
                    respond(initial.path("initialResponse"));
                    Step next = nextStep(initial.path("nextStep"), initial.path("conditional"));
                    JsonNode hook = initial.path("codeHook");
                    if (hook.path("active").asBoolean(false) && hook.path("enableCodeHookInvocation").asBoolean(false)
                            && target.hookArn() != null) {
                        return Step.hook(hook.path("invocationLabel").asText(null));
                    }
                    return next == null ? Step.HOOK : next;
                }
                case HOOK -> {
                    if (dialogHook() && !hookUsed) {
                        hookUsed = true;
                        Step proposed = defaultStep(intent);
                        ObjectNode response = invoke("DialogCodeHook", proposed, step.label);
                        return apply(response, false);
                    }
                    return defaultStep(intent);
                }
                case DEFAULT -> {
                    return defaultStep(intent);
                }
                case ELICIT_SLOT -> {
                    JsonNode slot = slotByName(intentRecord, step.slot);
                    if (slot == null) {
                        throw dependency("The slot " + step.slot + " does not exist in intent "
                                + intent.path("name").asText() + ".");
                    }
                    elicit(slot);
                    return null;
                }
                case CONFIRM -> {
                    confirm();
                    return null;
                }
                case FULFILL -> {
                    return fulfill(intent);
                }
                case CLOSE -> {
                    close(intent);
                    return null;
                }
                case ELICIT_INTENT -> {
                    state.putObject("dialogAction").put("type", "ElicitIntent");
                    if (intent != null && !Set.of("Fulfilled", "Failed").contains(intent.path("state").asText())) {
                        intent.put("state", "Fulfilled".equals(intent.path("state").asText()) ? "Fulfilled"
                                : intent.path("state").asText());
                    }
                    return null;
                }
                case START_INTENT -> {
                    JsonNode next = intentByName(step.slot);
                    if (next == null) {
                        throw dependency("The intent " + step.slot + " does not exist.");
                    }
                    ObjectNode slots = mapper.createObjectNode();
                    if (step.overrides != null) {
                        step.overrides.fields().forEachRemaining(e -> slots.set(e.getKey(), e.getValue()));
                    }
                    start(next, slots);
                    return null;
                }
                case END -> {
                    state.putObject("dialogAction").put("type", "Close");
                    if (intent != null && "InProgress".equals(intent.path("state").asText())) {
                        intent.put("state", "Fulfilled");
                    }
                    return null;
                }
                default -> {
                    return null;
                }
            }
        }

        /** What Lex does next on its own: elicit the next empty required slot, confirm, or fulfill. */
        Step defaultStep(ObjectNode intent) {
            JsonNode slots = intent.path("slots");
            List<JsonNode> ordered = new ArrayList<>();
            for (JsonNode priority : sortedPriorities(intentRecord)) {
                for (JsonNode slot : LexNlu.slotsOf(target.model(), intentRecord)) {
                    if (priority.path("slotId").asText().equals(slot.path("slotId").asText())) {
                        ordered.add(slot);
                    }
                }
            }
            for (JsonNode slot : LexNlu.slotsOf(target.model(), intentRecord)) {
                if (!ordered.contains(slot)) {
                    ordered.add(slot);
                }
            }
            for (JsonNode slot : ordered) {
                String name = slot.path("slotName").asText();
                JsonNode current = slots.path(name);
                boolean filled = current.isObject() && (current.has("value") || current.has("values")
                        || current.has("subSlots"));
                if (filled) {
                    continue;
                }
                JsonNode setting = slot.path("valueElicitationSetting");
                Optional<ObjectNode> fallbackValue = defaultValue(slot);
                if (fallbackValue.isPresent()) {
                    intent.withObject("/slots").set(name, fallbackValue.get());
                    continue;
                }
                if ("Required".equals(setting.path("slotConstraint").asText())) {
                    return Step.elicit(name);
                }
            }
            JsonNode confirmation = intentRecord.path("intentConfirmationSetting");
            if (confirmation.isObject() && confirmation.path("active").asBoolean(true)
                    && confirmation.has("promptSpecification")
                    && "None".equals(intent.path("confirmationState").asText("None"))) {
                return Step.CONFIRM;
            }
            if ("Denied".equals(intent.path("confirmationState").asText())) {
                intent.put("state", "Failed");
                state.putObject("dialogAction").put("type", "Close");
                return null;
            }
            return Step.FULFILL;
        }

        Optional<ObjectNode> defaultValue(JsonNode slot) {
            for (JsonNode value : slot.path("valueElicitationSetting").path("defaultValueSpecification")
                    .path("defaultValueList")) {
                String spec = value.path("defaultValue").asText();
                String resolved;
                if (spec.startsWith("[") && spec.endsWith("]")) {
                    resolved = state.path("sessionAttributes").path(spec.substring(1, spec.length() - 1))
                            .asText(null);
                } else if (spec.startsWith("#")) {
                    String[] parts = spec.substring(1).split("\\.", 2);
                    resolved = null;
                    for (JsonNode context : state.path("activeContexts")) {
                        if (parts.length == 2 && parts[0].equals(context.path("name").asText())) {
                            resolved = context.path("contextAttributes").path(parts[1]).asText(null);
                        }
                    }
                } else {
                    resolved = spec;
                }
                if (resolved != null && !resolved.isEmpty()) {
                    Optional<ObjectNode> v = LexNlu.slotValue(mapper, target.model(), slot, resolved);
                    if (v.isPresent()) {
                        return v;
                    }
                }
            }
            return Optional.empty();
        }

        boolean dialogHook() {
            return intentRecord != null && intentRecord.path("dialogCodeHook").path("enabled").asBoolean(false)
                    && target.hookArn() != null;
        }

        void elicit(JsonNode slot) {
            ObjectNode intent = intent();
            intent.put("state", "InProgress");
            ObjectNode action = state.putObject("dialogAction");
            action.put("type", "ElicitSlot");
            action.put("slotToElicit", slot.path("slotName").asText());
            prompt(slot.path("valueElicitationSetting").path("promptSpecification"));
        }

        void confirm() {
            ObjectNode intent = intent();
            intent.put("state", "InProgress");
            state.putObject("dialogAction").put("type", "ConfirmIntent");
            prompt(intentRecord.path("intentConfirmationSetting").path("promptSpecification"));
        }

        Step fulfill(ObjectNode intent) {
            JsonNode hook = intentRecord.path("fulfillmentCodeHook");
            if (hook.path("enabled").asBoolean(false) && hook.path("active").asBoolean(true)
                    && target.hookArn() != null) {
                intent.put("state", "FulfillmentInProgress");
                ObjectNode response = invoke("FulfillmentCodeHook", null, null);
                return apply(response, true);
            }
            intent.put("state", "ReadyForFulfillment");
            return Step.CLOSE;
        }

        void close(ObjectNode intent) {
            state.putObject("dialogAction").put("type", "Close");
            JsonNode closing = intentRecord.path("intentClosingSetting");
            if (closing.isObject() && closing.path("active").asBoolean(true)) {
                respond(closing.path("closingResponse"));
                Step next = nextStep(closing.path("nextStep"), closing.path("conditional"));
                if (next != null && next.kind != Kind.CLOSE && next.kind != Kind.END) {
                    activateOutputContexts();
                    flow(next);
                    return;
                }
            }
            activateOutputContexts();
        }

        void activateOutputContexts() {
            if (intentRecord == null || intentRecord.path("outputContexts").isEmpty()) {
                return;
            }
            ArrayNode contexts = state.withArray("activeContexts");
            ObjectNode expiry = LexStore.meta(session).get("contextExpiry") instanceof ObjectNode e ? e
                    : LexStore.meta(session).putObject("contextExpiry");
            for (JsonNode output : intentRecord.path("outputContexts")) {
                String name = output.path("name").asText();
                for (int i = contexts.size() - 1; i >= 0; i--) {
                    if (name.equals(contexts.get(i).path("name").asText())) {
                        contexts.remove(i);
                    }
                }
                ObjectNode context = contexts.addObject();
                context.put("name", name);
                context.putObject("timeToLive").put("timeToLiveInSeconds", output.path("timeToLiveInSeconds")
                        .asInt(90)).put("turnsToLive", output.path("turnsToLive").asInt(5));
                context.putObject("contextAttributes");
                expiry.put(name, LexStore.millis() + output.path("timeToLiveInSeconds").asLong(90) * 1000);
            }
        }

        /** Answers a Kendra search or QnA intent from its Kendra index or Bedrock knowledge base. */
        void answer(ObjectNode intent, String signature) {
            String answer = null;
            try {
                if ("AMAZON.KendraSearchIntent".equals(signature)) {
                    answer = kendraAnswer(intentRecord.path("kendraConfiguration").path("kendraIndex").asText());
                } else if ("AMAZON.QnAIntent".equals(signature)) {
                    JsonNode source = intentRecord.path("qnAIntentConfiguration").path("dataSourceConfiguration");
                    if (source.has("kendraConfiguration")) {
                        answer = kendraAnswer(source.path("kendraConfiguration").path("kendraIndex").asText());
                    } else if (source.has("bedrockKnowledgeStoreConfiguration")) {
                        String kb = source.path("bedrockKnowledgeStoreConfiguration")
                                .path("bedrockKnowledgeBaseArn").asText();
                        ObjectNode request = mapper.createObjectNode();
                        request.putObject("input").put("text", text);
                        ObjectNode config = request.putObject("retrieveAndGenerateConfiguration");
                        config.put("type", "KNOWLEDGE_BASE");
                        config.putObject("knowledgeBaseConfiguration")
                                .put("knowledgeBaseId", kb.substring(kb.lastIndexOf('/') + 1))
                                .put("modelArn", intentRecord.path("qnAIntentConfiguration")
                                        .path("bedrockModelConfiguration").path("modelArn").asText());
                        answer = bedrock.get().retrieveAndGenerate(request, target.region()).text();
                    }
                }
            } catch (AwsException e) {
                LOG.debugv("Lex {0} could not answer: {1}", signature, e.getMessage());
            }
            if (answer == null || answer.isBlank()) {
                intent.put("state", "Failed");
                return;
            }
            messages.addObject().put("contentType", "PlainText").put("content", answer);
            intent.put("state", "Fulfilled");
        }

        private String kendraAnswer(String indexArn) {
            String indexId = indexArn.substring(indexArn.lastIndexOf('/') + 1);
            ObjectNode query = mapper.createObjectNode();
            query.put("IndexId", indexId);
            query.put("QueryText", text);
            String filter = intentRecord.path("kendraConfiguration").path("queryFilterString").asText(null);
            if (filter != null && intentRecord.path("kendraConfiguration").path("queryFilterStringEnabled")
                    .asBoolean(false)) {
                try {
                    query.set("AttributeFilter", mapper.readTree(filter));
                } catch (IOException e) {
                    throw LexStore.validation("The Kendra query filter string is not valid JSON.");
                }
            }
            ObjectNode result = kendra.get().query(query, target.region());
            String best = null;
            for (JsonNode item : result.path("ResultItems")) {
                String type = item.path("Type").asText();
                JsonNode excerpt = item.path("DocumentExcerpt").path("Text");
                if ("ANSWER".equals(type) || "QUESTION_ANSWER".equals(type)) {
                    for (JsonNode attribute : item.path("AdditionalAttributes")) {
                        String value = attribute.path("Value").path("TextWithHighlightsValue").path("Text")
                                .asText(null);
                        if ("AnswerText".equals(attribute.path("Key").asText()) && value != null) {
                            return value;
                        }
                    }
                    if (excerpt.isTextual()) {
                        return excerpt.asText();
                    }
                }
                if (best == null && excerpt.isTextual()) {
                    best = excerpt.asText();
                }
            }
            return best;
        }

        // ------------------------------------------------------------ next steps

        /** The step a dialog state names, after its conditional branches; null when it names none. */
        Step nextStep(JsonNode dialogState, JsonNode conditional) {
            if (conditional.isObject() && conditional.path("active").asBoolean(false)) {
                ObjectNode intent = intent();
                JsonNode slots = intent == null ? mapper.createObjectNode() : intent.path("slots");
                for (JsonNode branch : conditional.path("conditionalBranches")) {
                    if (LexConditions.evaluate(branch.path("condition").path("expressionString").asText(), slots,
                            state.path("sessionAttributes"), requestAttributes)) {
                        respond(branch.path("response"));
                        return stepOf(branch.path("nextStep"));
                    }
                }
                JsonNode fallback = conditional.path("defaultBranch");
                respond(fallback.path("response"));
                return stepOf(fallback.path("nextStep"));
            }
            return stepOf(dialogState);
        }

        Step stepOf(JsonNode dialogState) {
            if (!dialogState.isObject() || !dialogState.has("dialogAction")) {
                if (dialogState.isObject()) {
                    applyOverrides(dialogState);
                }
                return null;
            }
            applyOverrides(dialogState);
            JsonNode action = dialogState.path("dialogAction");
            return switch (action.path("type").asText()) {
                case "ElicitIntent" -> Step.ELICIT_INTENT;
                case "StartIntent" -> Step.startIntent(dialogState.path("intent").path("name").asText(),
                        dialogState.path("intent").path("slots"));
                case "ElicitSlot" -> Step.elicit(action.path("slotToElicit").asText());
                case "EvaluateConditional" -> Step.DEFAULT;
                case "InvokeDialogCodeHook" -> Step.HOOK;
                case "ConfirmIntent" -> Step.CONFIRM;
                case "FulfillIntent" -> Step.FULFILL;
                case "CloseIntent" -> Step.CLOSE;
                case "EndConversation" -> Step.END;
                default -> null;
            };
        }

        void applyOverrides(JsonNode dialogState) {
            if (dialogState.get("sessionAttributes") instanceof ObjectNode attributes) {
                state.withObject("/sessionAttributes").setAll(attributes.deepCopy());
            }
            ObjectNode intent = intent();
            if (intent != null && dialogState.path("intent").path("slots").isObject()
                    && !"StartIntent".equals(dialogState.path("dialogAction").path("type").asText())) {
                dialogState.path("intent").path("slots").fields().forEachRemaining(e ->
                        intent.withObject("/slots").set(e.getKey(), e.getValue().deepCopy()));
            }
        }

        // ------------------------------------------------------------ code hooks

        ObjectNode invoke(String source, Step proposed, String label) {
            ObjectNode event = mapper.createObjectNode();
            event.put("messageVersion", "1.0");
            event.put("invocationSource", source);
            event.put("inputMode", inputMode);
            event.put("responseContentType", responseContentType);
            event.put("sessionId", sessionId);
            event.put("inputTranscript", text == null ? "" : text);
            if (label != null) {
                event.put("invocationLabel", label);
            }
            ObjectNode bot = event.putObject("bot");
            bot.put("id", target.botId());
            bot.put("name", target.bot().path("botName").asText());
            bot.put("localeId", target.localeId());
            bot.put("version", target.version());
            bot.put("aliasId", target.aliasId());
            bot.put("aliasName", target.alias().path("botAliasName").asText());
            ArrayNode list = event.putArray("interpretations");
            for (JsonNode interpretation : interpretations) {
                ObjectNode copy = interpretation.deepCopy();
                if (!copy.has("intent")) {
                    copy.set("intent", intent().deepCopy());
                }
                list.add(copy);
            }
            if (list.isEmpty() && intent() != null) {
                list.addObject().put("interpretationSource", "Lex").set("intent", intent().deepCopy());
            }
            if (proposed != null && proposed.kind == Kind.ELICIT_SLOT) {
                ObjectNode next = event.putObject("proposedNextState");
                next.putObject("dialogAction").put("type", "ElicitSlot").put("slotToElicit", proposed.slot);
                next.set("intent", intent().deepCopy());
                next.putObject("prompt").put("attempt", attempt(proposed.slot));
            }
            if (requestAttributes.isObject() && !requestAttributes.isEmpty()) {
                event.set("requestAttributes", requestAttributes.deepCopy());
            }
            ObjectNode sessionState = state.deepCopy();
            sessionState.put("originatingRequestId", requestId);
            event.set("sessionState", sessionState);
            if ("Speech".equals(inputMode) && text != null) {
                ObjectNode transcription = event.putArray("transcriptions").addObject();
                transcription.put("transcription", text);
                transcription.put("transcriptionConfidence", 1.0);
                transcription.putObject("resolvedContext").put("intent", intent().path("name").asText());
                transcription.set("resolvedSlots", intent().path("slots").deepCopy());
            }
            String arn = target.hookArn();
            String region = target.region();
            try {
                region = AwsArnUtils.parse(arn).region();
            } catch (RuntimeException e) {
                // a bare function name runs in the bot's Region
            }
            InvokeResult result;
            try {
                result = lambda.get().invoke(region, arn, mapper.writeValueAsBytes(event),
                        InvocationType.RequestResponse);
            } catch (AwsException e) {
                throw dependency("Invalid Lambda Response: " + e.getMessage());
            } catch (IOException e) {
                throw new AwsException("InternalServerException", e.getMessage(), 500);
            }
            byte[] payload = result.getPayload() == null ? new byte[0] : result.getPayload();
            if (result.getFunctionError() != null) {
                throw dependency("Invalid Lambda Response: Received error response from Lambda: "
                        + new String(payload, StandardCharsets.UTF_8));
            }
            try {
                JsonNode response = mapper.readTree(payload);
                if (!(response instanceof ObjectNode object) || !object.path("sessionState").isObject()) {
                    throw dependency("Invalid Lambda Response: The response has no sessionState.");
                }
                return object;
            } catch (IOException e) {
                throw dependency("Invalid Lambda Response: The response is not valid JSON.");
            }
        }

        String attempt(String slot) {
            int n = attempts().path(slot).asInt(0);
            return n == 0 ? "Initial" : "Retry" + Math.min(n, 5);
        }

        /** Applies a code hook's response; the next step, or null when the turn ends with it. */
        Step apply(ObjectNode response, boolean fulfillment) {
            JsonNode rs = response.path("sessionState");
            String type = rs.path("dialogAction").path("type").asText(null);
            if (type == null) {
                throw dependency("Invalid Lambda Response: The response has no dialogAction type.");
            }
            if (rs.get("sessionAttributes") instanceof ObjectNode attributes) {
                state.set("sessionAttributes", attributes.deepCopy());
            }
            if (rs.get("activeContexts") instanceof ArrayNode contexts) {
                state.set("activeContexts", contexts.deepCopy());
            }
            if (rs.get("runtimeHints") instanceof ObjectNode hints) {
                state.set("runtimeHints", hints.deepCopy());
            }
            if (rs.get("intent") instanceof ObjectNode returned) {
                String name = returned.path("name").asText(intent() == null ? "" : intent().path("name").asText());
                JsonNode record = intentByName(name);
                if (record == null) {
                    throw dependency("Invalid Lambda Response: The intent " + name + " does not exist.");
                }
                intentRecord = record;
                ObjectNode intent = mapper.createObjectNode();
                intent.put("name", name);
                ObjectNode slots = returned.get("slots") instanceof ObjectNode s ? s.deepCopy()
                        : mapper.createObjectNode();
                intent.set("slots", allSlots(target.model(), record, slots));
                intent.put("state", returned.path("state").asText(intent() == null ? "InProgress"
                        : intent().path("state").asText("InProgress")));
                intent.put("confirmationState", returned.path("confirmationState").asText(intent() == null ? "None"
                        : intent().path("confirmationState").asText("None")));
                state.set("intent", intent);
            }
            boolean said = false;
            for (JsonNode message : response.path("messages")) {
                String contentType = message.path("contentType").asText();
                if (!Set.of("PlainText", "SSML", "CustomPayload", "ImageResponseCard").contains(contentType)) {
                    throw dependency("Invalid Lambda Response: The message content type " + contentType
                            + " is not valid.");
                }
                messages.add(message.deepCopy());
                said = true;
            }
            ObjectNode intent = intent();
            switch (type) {
                case "Close" -> {
                    String outcome = intent == null ? "Fulfilled" : intent.path("state").asText();
                    if (!Set.of("Fulfilled", "Failed", "ReadyForFulfillment", "InProgress").contains(outcome)) {
                        throw dependency("Invalid Lambda Response: Received invalid intent state " + outcome
                                + " for a Close dialog action.");
                    }
                    if ("InProgress".equals(outcome) || "FulfillmentInProgress".equals(outcome)) {
                        intent.put("state", "Fulfilled");
                        outcome = "Fulfilled";
                    }
                    state.putObject("dialogAction").put("type", "Close");
                    if (fulfillment) {
                        JsonNode post = intentRecord.path("fulfillmentCodeHook")
                                .path("postFulfillmentStatusSpecification");
                        boolean success = "Fulfilled".equals(outcome);
                        if (!said) {
                            respond(post.path(success ? "successResponse" : "failureResponse"));
                        }
                        Step next = nextStep(post.path(success ? "successNextStep" : "failureNextStep"),
                                post.path(success ? "successConditional" : "failureConditional"));
                        if (next != null) {
                            return next;
                        }
                    }
                    activateOutputContexts();
                    return null;
                }
                case "Delegate" -> {
                    if (fulfillment) {
                        if (intent != null && "FulfillmentInProgress".equals(intent.path("state").asText())) {
                            intent.put("state", "Fulfilled");
                        }
                        return Step.CLOSE;
                    }
                    return Step.DEFAULT;
                }
                case "ElicitSlot" -> {
                    String slot = rs.path("dialogAction").path("slotToElicit").asText(null);
                    JsonNode record = slot == null ? null : slotByName(intentRecord, slot);
                    if (record == null) {
                        throw dependency("Invalid Lambda Response: ElicitSlot names slot " + slot
                                + ", which the intent does not have.");
                    }
                    intent.put("state", "InProgress");
                    ObjectNode action = state.putObject("dialogAction");
                    action.put("type", "ElicitSlot");
                    action.put("slotToElicit", slot);
                    if (rs.path("dialogAction").hasNonNull("slotElicitationStyle")) {
                        action.put("slotElicitationStyle", rs.path("dialogAction").get("slotElicitationStyle")
                                .asText());
                    }
                    if (!said) {
                        prompt(record.path("valueElicitationSetting").path("promptSpecification"));
                    }
                    return null;
                }
                case "ConfirmIntent" -> {
                    intent.put("state", "InProgress");
                    state.putObject("dialogAction").put("type", "ConfirmIntent");
                    if (!said) {
                        prompt(intentRecord.path("intentConfirmationSetting").path("promptSpecification"));
                    }
                    return null;
                }
                case "ElicitIntent" -> {
                    state.putObject("dialogAction").put("type", "ElicitIntent");
                    return null;
                }
                default -> throw dependency("Invalid Lambda Response: The dialog action type " + type
                        + " is not valid.");
            }
        }

        // ------------------------------------------------------------ messages

        void prompt(JsonNode specification) {
            respond(specification);
        }

        /** Adds a response or prompt specification's messages: one per message group. */
        void respond(JsonNode specification) {
            for (JsonNode group : specification.path("messageGroups")) {
                JsonNode message = group.path("message");
                ObjectNode out = message(message);
                if (out != null) {
                    messages.add(out);
                }
            }
        }

        ObjectNode message(JsonNode message) {
            ObjectNode out = mapper.createObjectNode();
            if (message.has("plainTextMessage")) {
                out.put("contentType", "PlainText");
                out.put("content", render(message.path("plainTextMessage").path("value").asText()));
            } else if (message.has("ssmlMessage")) {
                out.put("contentType", "SSML");
                out.put("content", render(message.path("ssmlMessage").path("value").asText()));
            } else if (message.has("customPayload")) {
                out.put("contentType", "CustomPayload");
                out.put("content", render(message.path("customPayload").path("value").asText()));
            } else if (message.has("imageResponseCard")) {
                out.put("contentType", "ImageResponseCard");
                ObjectNode card = message.path("imageResponseCard").deepCopy();
                card.put("title", render(card.path("title").asText()));
                if (card.has("subtitle")) {
                    card.put("subtitle", render(card.path("subtitle").asText()));
                }
                out.set("imageResponseCard", card);
            } else {
                return null;
            }
            return out;
        }

        String render(String template) {
            ObjectNode intent = intent();
            String result = replace(SLOT_PLACEHOLDER, template, name -> {
                JsonNode slot = intent == null ? null : intent.path("slots").path(name.split("\\.")[0]);
                JsonNode value = slot == null ? null : slot.path("value").path("interpretedValue");
                return value != null && value.isTextual() ? value.asText() : null;
            });
            result = replace(REQUEST_PLACEHOLDER, result, name -> requestAttributes.path(name).asText(null));
            result = replace(SESSION_PLACEHOLDER, result, name -> state.path("sessionAttributes").path(name)
                    .asText(null));
            return result;
        }

        void finish() {
            if (!interpretations.isEmpty() && intent() != null) {
                ObjectNode first = (ObjectNode) interpretations.get(0);
                if (!first.has("intent")) {
                    first.set("intent", intent().deepCopy());
                } else if (first.path("intent").path("name").asText().equals(intent().path("name").asText())) {
                    first.set("intent", intent().deepCopy());
                }
            }
            state.put("originatingRequestId", requestId);
            if (state.get("activeContexts") instanceof ArrayNode contexts && contexts.isEmpty()) {
                state.remove("activeContexts");
            }
        }
    }

    private interface Lookup {
        String value(String name);
    }

    private static String replace(Pattern pattern, String template, Lookup lookup) {
        Matcher matcher = pattern.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = lookup.value(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group(0) : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private enum Kind { START, HOOK, DEFAULT, ELICIT_SLOT, CONFIRM, FULFILL, CLOSE, ELICIT_INTENT, START_INTENT, END }

    /** A step of an intent's conversation flow. */
    private static final class Step {
        static final Step START = new Step(Kind.START, null, null, null);
        static final Step HOOK = new Step(Kind.HOOK, null, null, null);
        static final Step DEFAULT = new Step(Kind.DEFAULT, null, null, null);
        static final Step CONFIRM = new Step(Kind.CONFIRM, null, null, null);
        static final Step FULFILL = new Step(Kind.FULFILL, null, null, null);
        static final Step CLOSE = new Step(Kind.CLOSE, null, null, null);
        static final Step ELICIT_INTENT = new Step(Kind.ELICIT_INTENT, null, null, null);
        static final Step END = new Step(Kind.END, null, null, null);

        final Kind kind;
        final String slot;
        final String label;
        final JsonNode overrides;

        private Step(Kind kind, String slot, String label, JsonNode overrides) {
            this.kind = kind;
            this.slot = slot;
            this.label = label;
            this.overrides = overrides;
        }

        static Step elicit(String slot) {
            return new Step(Kind.ELICIT_SLOT, slot, null, null);
        }

        static Step hook(String label) {
            return new Step(Kind.HOOK, null, label, null);
        }

        static Step startIntent(String name, JsonNode slots) {
            return new Step(Kind.START_INTENT, name, null, slots.isObject() ? slots : null);
        }
    }

    private static List<JsonNode> sortedPriorities(JsonNode intent) {
        List<JsonNode> priorities = new ArrayList<>();
        intent.path("slotPriorities").forEach(priorities::add);
        priorities.sort((a, b) -> Integer.compare(a.path("priority").asInt(), b.path("priority").asInt()));
        return priorities;
    }

    /** An intent's slots: each of its slots, filled from {@code filled} or null. */
    private ObjectNode allSlots(JsonNode model, JsonNode intent, JsonNode filled) {
        ObjectNode slots = mapper.createObjectNode();
        for (JsonNode slot : LexNlu.slotsOf(model, intent)) {
            String name = slot.path("slotName").asText();
            JsonNode value = filled == null ? null : filled.get(name);
            slots.set(name, value == null || value.isNull() ? NullNode.getInstance() : value.deepCopy());
        }
        if (filled != null) {
            filled.fields().forEachRemaining(e -> {
                if (!slots.has(e.getKey())) {
                    slots.set(e.getKey(), e.getValue().deepCopy());
                }
            });
        }
        return slots;
    }

    private void fillSlots(JsonNode model, ObjectNode intent) {
        String name = intent.path("name").asText();
        for (JsonNode record : model.path("intents")) {
            if (name.equals(record.path("intentName").asText())) {
                intent.set("slots", allSlots(model, record, intent.path("slots")));
            }
        }
        if (!intent.has("state")) {
            intent.put("state", "InProgress");
        }
        if (!intent.has("confirmationState")) {
            intent.put("confirmationState", "None");
        }
    }

    private static AwsException dependency(String message) {
        return new AwsException("DependencyFailedException", message, 424);
    }

    private ObjectNode sentiment(String text) {
        int positive = 0;
        int negative = 0;
        for (String word : LexValues.normalize(text).split("\\s+")) {
            if (POSITIVE.contains(word)) {
                positive++;
            } else if (NEGATIVE.contains(word)) {
                negative++;
            }
        }
        String label = positive > 0 && negative > 0 ? "MIXED" : positive > 0 ? "POSITIVE"
                : negative > 0 ? "NEGATIVE" : "NEUTRAL";
        ObjectNode response = mapper.createObjectNode();
        response.put("sentiment", label);
        ObjectNode score = response.putObject("sentimentScore");
        score.put("positive", "POSITIVE".equals(label) ? 0.9 : "MIXED".equals(label) ? 0.4 : 0.03);
        score.put("negative", "NEGATIVE".equals(label) ? 0.9 : "MIXED".equals(label) ? 0.4 : 0.03);
        score.put("neutral", "NEUTRAL".equals(label) ? 0.92 : 0.06);
        score.put("mixed", "MIXED".equals(label) ? 0.16 : 0.02);
        return response;
    }

    // ================================================================ speech

    private void checkResponseType(String type, Target target) {
        String t = type.toLowerCase(Locale.ROOT);
        if (t.startsWith("text/plain")) {
            return;
        }
        if (!t.startsWith("audio/mpeg") && !t.startsWith("audio/ogg") && !t.startsWith("audio/pcm")
                && !t.startsWith("audio/*")) {
            throw LexStore.validation("The response content type " + type + " is not supported.");
        }
        if (!target.model().path("locale").path("voiceSettings").hasNonNull("voiceId")) {
            throw LexStore.validation("The locale " + target.localeId() + " has no voice settings, so it can't "
                    + "respond with speech. Ask for text/plain; charset=utf-8.");
        }
    }

    /** What speech says, for the Test Workbench's audio test sets. */
    String transcriptOf(byte[] audio) {
        return transcript(audio);
    }

    /** What speech says: the {@code lex} entry of AI_MOCK_CONFIG for the audio's SHA-256, or nothing. */
    private String transcript(byte[] audio) {
        String digest = sha256(audio);
        return mocks.lookup("lex", digest, "RecognizeUtterance")
                .map(node -> node.path("inputTranscript").asText(""))
                .orElse("");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A conversation's messages as speech in {@code responseType}. */
    Spoken speak(Target target, ObjectNode result, String responseType) {
        checkResponseType(responseType, target);
        return spoken(target, result, null, null, responseType);
    }

    private Spoken spoken(Target target, ObjectNode result, String inputMode, String transcript, String responseType) {
        String t = responseType.toLowerCase(Locale.ROOT);
        if (t.startsWith("text/plain")) {
            return new Spoken(result, inputMode, transcript, new byte[0], responseType);
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode message : result.path("messages")) {
            if ("PlainText".equals(message.path("contentType").asText())
                    || "SSML".equals(message.path("contentType").asText())) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(message.path("content").asText().replaceAll("<[^>]+>", ""));
            }
        }
        if (text.isEmpty()) {
            return new Spoken(result, inputMode, transcript, new byte[0], responseType);
        }
        JsonNode voice = target.model().path("locale").path("voiceSettings");
        ObjectNode request = mapper.createObjectNode();
        request.put("Text", text.toString());
        request.put("VoiceId", voice.path("voiceId").asText());
        if (voice.hasNonNull("engine")) {
            request.put("Engine", voice.get("engine").asText());
        }
        String format = t.startsWith("audio/ogg") ? "ogg_vorbis" : t.startsWith("audio/pcm") ? "pcm" : "mp3";
        request.put("OutputFormat", format);
        if ("pcm".equals(format)) {
            request.put("SampleRate", "16000");
        }
        PollyService.Output audio = polly.get().synthesizeSpeech(request, target.region());
        String contentType = "pcm".equals(format) ? "audio/pcm" : audio.contentType();
        return new Spoken(result, inputMode, transcript, audio.bytes(), contentType);
    }

    // ================================================================ headers

    /** A header value as Lex V2 encodes it: gzip, then base64. */
    static String encodeHeader(String value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(value.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A gzipped (or plain) base64 JSON header; null when absent. */
    JsonNode decodeHeader(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            throw LexStore.validation("The " + name + " header is not valid base64.");
        }
        byte[] json = raw;
        if (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                json = in.readAllBytes();
            } catch (IOException e) {
                throw LexStore.validation("The " + name + " header is not valid gzip.");
            }
        }
        try {
            JsonNode parsed = mapper.readTree(json);
            if (!parsed.isObject()) {
                throw LexStore.validation("The " + name + " header must be a JSON object.");
            }
            return parsed;
        } catch (IOException e) {
            throw LexStore.validation("The " + name + " header is not valid JSON.");
        }
    }

    // ================================================================ analytics and logs

    /** Keeps the turn for the analytics APIs. */
    private void record(Target target, String sessionId, Turn turn, boolean newSession, long durationMillis) {
        ObjectNode record = store.record();
        long now = LexStore.millis();
        record.put("botId", target.botId());
        record.put("botAliasId", target.aliasId());
        record.put("botVersion", target.version());
        record.put("localeId", target.localeId());
        record.put("sessionId", sessionId);
        record.put("requestId", turn.requestId);
        record.put("timestamp", now);
        record.put("utterance", turn.text == null ? "" : turn.text);
        record.put("inputMode", turn.inputMode);
        record.put("channel", "API");
        record.put("newSession", newSession);
        record.put("durationMillis", durationMillis);
        record.put("missed", turn.missed);
        ObjectNode intent = turn.intent();
        if (intent != null) {
            record.put("intent", intent.path("name").asText());
            record.put("intentState", intent.path("state").asText());
        }
        String action = turn.state.path("dialogAction").path("type").asText("");
        record.put("dialogAction", action);
        if (turn.state.path("dialogAction").hasNonNull("slotToElicit")) {
            record.put("slotToElicit", turn.state.path("dialogAction").get("slotToElicit").asText());
        }
        if (turn.sentiment != null) {
            record.put("sentiment", turn.sentiment.path("sentiment").asText());
        }
        record.set("botResponses", turn.messages.deepCopy());
        if (intent != null) {
            ObjectNode filled = record.putObject("slots");
            intent.path("slots").fields().forEachRemaining(e -> {
                if (e.getValue().isObject()) {
                    filled.put(e.getKey(), e.getValue().path("value").path("interpretedValue").asText(""));
                }
            });
        }
        store.put(target.region(), LexStore.TURN, target.botId() + "/" + now + "-" + LexStore.id(), record);
    }

    /** Writes the turn to the alias's text log group and its speech to the audio log bucket. */
    private void log(Target target, String sessionId, Turn turn, ObjectNode out, byte[] audio) {
        JsonNode settings = target.alias().path("conversationLogSettings");
        String audioLocation = null;
        if (audio != null && audio.length > 0) {
            for (JsonNode setting : settings.path("audioLogSettings")) {
                if (!setting.path("enabled").asBoolean(false)) {
                    continue;
                }
                JsonNode destination = setting.path("destination").path("s3Bucket");
                String bucketArn = destination.path("s3BucketArn").asText();
                String bucket = bucketArn.substring(bucketArn.lastIndexOf(':') + 1);
                String prefix = destination.path("logPrefix").asText("");
                String key = (prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/") + target.botId() + "/"
                        + target.aliasId() + "/" + target.localeId() + "/" + sessionId + "/" + turn.requestId
                        + ".wav";
                try {
                    s3.get().putObject(bucket, key, audio, "audio/wav", Map.of());
                    audioLocation = "s3://" + bucket + "/" + key;
                } catch (AwsException e) {
                    LOG.debugv("Lex audio log to {0} failed: {1}", bucket, e.getMessage());
                }
            }
        }
        for (JsonNode setting : settings.path("textLogSettings")) {
            if (!setting.path("enabled").asBoolean(false)) {
                continue;
            }
            JsonNode destination = setting.path("destination").path("cloudWatch");
            String groupArn = destination.path("cloudWatchLogGroupArn").asText();
            String group = groupArn.replaceAll(":\\*$", "");
            group = group.substring(group.indexOf("log-group:") >= 0 ? group.indexOf("log-group:") + 10 : 0);
            String stream = destination.path("logPrefix").asText("") + target.aliasId() + "/" + target.localeId()
                    + "/" + Instant.now().toString().substring(0, 10);
            ObjectNode entry = mapper.createObjectNode();
            entry.set("messages", out.path("messages").deepCopy());
            ObjectNode bot = entry.putObject("bot");
            bot.put("name", target.bot().path("botName").asText());
            bot.put("version", target.version());
            bot.put("localeId", target.localeId());
            bot.put("id", target.botId());
            bot.put("aliasId", target.aliasId());
            bot.put("aliasName", target.alias().path("botAliasName").asText());
            entry.set("sessionState", out.path("sessionState").deepCopy());
            entry.put("inputMode", turn.inputMode);
            entry.put("requestId", turn.requestId);
            entry.put("timestamp", Instant.now().toString());
            entry.put("operationName", turn.text == null ? "PutSession" : "Speech".equals(turn.inputMode)
                    ? "RecognizeUtterance" : "RecognizeText");
            entry.put("inputTranscript", turn.text == null ? "" : turn.text);
            entry.set("interpretations", out.path("interpretations").deepCopy());
            entry.put("sessionId", sessionId);
            entry.set("requestAttributes", turn.requestAttributes.deepCopy());
            entry.put("missedUtterance", turn.missed);
            if (audioLocation != null) {
                entry.put("audioLogFileLocation", audioLocation);
            }
            String region = target.region();
            try {
                region = AwsArnUtils.parse(groupArn).region();
            } catch (RuntimeException e) {
                // a bare log group name lives in the bot's Region
            }
            try {
                try {
                    logs.get().createLogStream(group, stream, region);
                } catch (AwsException e) {
                    if (!"ResourceAlreadyExistsException".equals(e.getErrorCode())) {
                        throw e;
                    }
                }
                logs.get().putLogEvents(group, stream, List.of(Map.of("timestamp", LexStore.millis(), "message",
                        entry.toString())), region);
            } catch (AwsException e) {
                LOG.debugv("Lex text log to {0} failed: {1}", group, e.getMessage());
            }
        }
    }
}
