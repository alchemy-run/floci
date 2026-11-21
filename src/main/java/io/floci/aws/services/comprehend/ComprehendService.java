package io.floci.aws.services.comprehend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AiMockConfigLoader;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.comprehend.ComprehendAnalyzer.Entity;
import io.floci.aws.services.comprehend.ComprehendAnalyzer.Phrase;
import io.floci.aws.services.comprehend.ComprehendAnalyzer.Sentiment;
import io.floci.aws.services.comprehend.ComprehendAnalyzer.Tagged;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Amazon Comprehend's real-time analysis: the Detect*, BatchDetect*,
 * ContainsPiiEntities, DetectToxicContent, and ClassifyDocument actions.
 * <p>
 * Answers come from {@link ComprehendAnalyzer}'s rules, so they follow the
 * text: "Bob moved to Seattle" has a PERSON and a LOCATION, "I love it" is
 * POSITIVE. Callers can still pin any answer per exact {@code Text} with
 * {@link AiMockConfigLoader} (see {@code docs/services/comprehend.md}); the
 * lookup happens after input validation, so a malformed request still fails.
 *
 * @see <a href="https://docs.aws.amazon.com/comprehend/latest/APIReference/Welcome.html">Comprehend API Reference</a>
 */
@ApplicationScoped
public class ComprehendService {
    private static final String SERVICE_KEY = "comprehend";
    /** The LanguageCode enum the general detect operations accept. */
    static final Set<String> SUPPORTED_LANGUAGE_CODES = Set.of(
            "en", "es", "fr", "de", "it", "pt", "ar", "hi", "ja", "ko", "zh", "zh-TW");
    /** DetectPiiEntities / ContainsPiiEntities: "English (en) or Spanish (es)". */
    static final Set<String> PII_SUPPORTED_LANGUAGE_CODES = Set.of("en", "es");
    /** DetectSyntax's SyntaxLanguageCode enum. */
    private static final Set<String> SYNTAX_LANGUAGE_CODES = Set.of("en", "es", "fr", "de", "it", "pt");
    /** Targeted sentiment and toxicity detection run on English only. */
    private static final Set<String> ENGLISH_ONLY = Set.of("en");
    /** Real-time actions reject Text over 100 KB (5 KB per document in a batch). */
    private static final int MAX_TEXT_BYTES = 100_000;
    private static final int MAX_BATCH_TEXT_BYTES = 5_000;
    private static final int MAX_BATCH_SIZE = 25;
    private static final int MAX_TOXIC_SEGMENTS = 10;

    private final ObjectMapper objectMapper;
    private final AiMockConfigLoader mockConfigLoader;

    @Inject
    public ComprehendService(ObjectMapper objectMapper, AiMockConfigLoader mockConfigLoader) {
        this.objectMapper = objectMapper;
        this.mockConfigLoader = mockConfigLoader;
    }

    // ── Single document ────────────────────────────────────────────────────

    public ObjectNode detectDominantLanguage(JsonNode request) {
        String text = requireText(request, MAX_TEXT_BYTES);
        return mocked(text, "DetectDominantLanguage").orElseGet(() -> dominantLanguage(text));
    }

    public ObjectNode detectEntities(JsonNode request) {
        String text = requireText(request, MAX_TEXT_BYTES);
        String endpointArn = text(request, "EndpointArn");
        if (endpointArn != null) {
            throw endpointUnavailable(endpointArn);
        }
        requireLanguage(request, SUPPORTED_LANGUAGE_CODES);
        return mocked(text, "DetectEntities").orElseGet(() -> entities(text));
    }

    public ObjectNode detectKeyPhrases(JsonNode request) {
        String text = requireText(request, MAX_TEXT_BYTES);
        requireLanguage(request, SUPPORTED_LANGUAGE_CODES);
        return mocked(text, "DetectKeyPhrases").orElseGet(() -> keyPhrases(text));
    }

    public ObjectNode detectPiiEntities(JsonNode request) {
        String text = requireText(request, MAX_TEXT_BYTES);
        requireLanguage(request, PII_SUPPORTED_LANGUAGE_CODES);
        return mocked(text, "DetectPiiEntities").orElseGet(() -> {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode entities = root.putArray("Entities");
            for (Entity pii : ComprehendAnalyzer.pii(text)) {
                entities.addObject()
                        .put("Score", pii.score())
                        .put("Type", pii.type())
                        .put("BeginOffset", pii.begin())
                        .put("EndOffset", pii.end());
            }
            return root;
        });
    }

    public ObjectNode containsPiiEntities(JsonNode request) {
        String text = requireText(request, MAX_TEXT_BYTES);
        requireLanguage(request, PII_SUPPORTED_LANGUAGE_CODES);
        return mocked(text, "ContainsPiiEntities").orElseGet(() -> {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode labels = root.putArray("Labels");
            ComprehendAnalyzer.pii(text).stream().map(Entity::type).distinct()
                    .forEach(type -> labels.addObject().put("Name", type).put("Score", 0.99));
            return root;
        });
    }

    public ObjectNode detectSentiment(JsonNode request) {
        String text = requireText(request, MAX_TEXT_BYTES);
        requireLanguage(request, SUPPORTED_LANGUAGE_CODES);
        return mocked(text, "DetectSentiment").orElseGet(() -> sentiment(text));
    }

    public ObjectNode detectSyntax(JsonNode request) {
        String text = requireText(request, MAX_TEXT_BYTES);
        requireLanguage(request, SYNTAX_LANGUAGE_CODES);
        return mocked(text, "DetectSyntax").orElseGet(() -> syntax(text));
    }

    public ObjectNode detectTargetedSentiment(JsonNode request) {
        String text = requireText(request, MAX_TEXT_BYTES);
        requireLanguage(request, ENGLISH_ONLY);
        return mocked(text, "DetectTargetedSentiment").orElseGet(() -> targetedSentiment(text));
    }

    public ObjectNode detectToxicContent(JsonNode request) {
        JsonNode segments = request.get("TextSegments");
        if (segments == null || !segments.isArray() || segments.isEmpty()) {
            throw invalid("TextSegments is a required field.");
        }
        if (segments.size() > MAX_TOXIC_SEGMENTS) {
            throw new AwsException("TextSizeLimitExceededException",
                    "TextSegments can contain at most " + MAX_TOXIC_SEGMENTS + " segments.", 400);
        }
        requireLanguage(request, ENGLISH_ONLY);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode results = root.putArray("ResultList");
        for (JsonNode segment : segments) {
            String text = segment.path("Text").asText("");
            if (text.isEmpty()) {
                throw invalid("TextSegments[].Text is a required field.");
            }
            Optional<ObjectNode> mock = mocked(text, "DetectToxicContent");
            if (mock.isPresent()) {
                results.add(mock.get());
                continue;
            }
            Map<String, Double> scores = ComprehendAnalyzer.toxicity(text);
            ObjectNode result = results.addObject();
            ArrayNode labels = result.putArray("Labels");
            for (String label : ComprehendAnalyzer.TOXICITY_LABELS) {
                labels.addObject().put("Name", label).put("Score", scores.get(label));
            }
            result.put("Toxicity", scores.get("TOXICITY"));
        }
        return root;
    }

    /**
     * ClassifyDocument needs a custom classifier endpoint, and Floci trains
     * none, so every endpoint ARN is one that isn't available.
     */
    public ObjectNode classifyDocument(JsonNode request) {
        if (text(request, "Text") == null && request.get("Bytes") == null) {
            throw invalid("Either Text or Bytes is required.");
        }
        String endpointArn = text(request, "EndpointArn");
        if (endpointArn == null) {
            throw invalid("EndpointArn is a required field.");
        }
        throw endpointUnavailable(endpointArn);
    }

    // ── Batches ────────────────────────────────────────────────────────────

    public ObjectNode batchDetectDominantLanguage(JsonNode request) {
        return batch(request, null, this::dominantLanguage, "DetectDominantLanguage");
    }

    public ObjectNode batchDetectEntities(JsonNode request) {
        return batch(request, SUPPORTED_LANGUAGE_CODES, this::entities, "DetectEntities");
    }

    public ObjectNode batchDetectKeyPhrases(JsonNode request) {
        return batch(request, SUPPORTED_LANGUAGE_CODES, this::keyPhrases, "DetectKeyPhrases");
    }

    public ObjectNode batchDetectSentiment(JsonNode request) {
        return batch(request, SUPPORTED_LANGUAGE_CODES, this::sentiment, "DetectSentiment");
    }

    public ObjectNode batchDetectSyntax(JsonNode request) {
        return batch(request, SYNTAX_LANGUAGE_CODES, this::syntax, "DetectSyntax");
    }

    public ObjectNode batchDetectTargetedSentiment(JsonNode request) {
        return batch(request, ENGLISH_ONLY, this::targetedSentiment, "DetectTargetedSentiment");
    }

    /**
     * One result per document, carrying its {@code Index}; a document that
     * is empty or too large goes to {@code ErrorList} instead, as Comprehend
     * reports per-document failures without failing the call.
     */
    private ObjectNode batch(JsonNode request, Set<String> languages,
                             Function<String, ObjectNode> analyze, String action) {
        JsonNode list = request.get("TextList");
        if (list == null || !list.isArray() || list.isEmpty()) {
            throw invalid("TextList is a required field.");
        }
        if (list.size() > MAX_BATCH_SIZE) {
            throw new AwsException("BatchSizeLimitExceededException",
                    "The number of documents in the request exceeds the limit of " + MAX_BATCH_SIZE + ".", 400);
        }
        if (languages != null) {
            requireLanguage(request, languages);
        }
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode results = root.putArray("ResultList");
        ArrayNode errors = root.putArray("ErrorList");
        for (int index = 0; index < list.size(); index++) {
            String text = list.get(index).asText("");
            if (text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > MAX_BATCH_TEXT_BYTES) {
                errors.addObject()
                        .put("Index", index)
                        .put("ErrorCode", text.isEmpty() ? "InvalidRequestException" : "TextSizeLimitExceededException")
                        .put("ErrorMessage", text.isEmpty() ? "The document is empty."
                                : "The document exceeds " + MAX_BATCH_TEXT_BYTES + " bytes.");
                continue;
            }
            ObjectNode result = mocked(text, action).orElseGet(() -> analyze.apply(text));
            ObjectNode indexed = objectMapper.createObjectNode().put("Index", index);
            indexed.setAll(result);
            results.add(indexed);
        }
        return root;
    }

    // ── Shaping ────────────────────────────────────────────────────────────

    ObjectNode dominantLanguage(String text) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode languages = root.putArray("Languages");
        for (Map.Entry<String, Double> language : ComprehendAnalyzer.languages(text)) {
            languages.addObject().put("LanguageCode", language.getKey()).put("Score", language.getValue());
        }
        return root;
    }

    ObjectNode entities(String text) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode entities = root.putArray("Entities");
        for (Entity entity : ComprehendAnalyzer.entities(text)) {
            entities.addObject()
                    .put("Score", entity.score())
                    .put("Type", entity.type())
                    .put("Text", entity.text())
                    .put("BeginOffset", entity.begin())
                    .put("EndOffset", entity.end());
        }
        return root;
    }

    ObjectNode keyPhrases(String text) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode phrases = root.putArray("KeyPhrases");
        for (Phrase phrase : ComprehendAnalyzer.keyPhrases(text)) {
            phrases.addObject()
                    .put("Score", 0.99)
                    .put("Text", phrase.text())
                    .put("BeginOffset", phrase.begin())
                    .put("EndOffset", phrase.end());
        }
        return root;
    }

    ObjectNode sentiment(String text) {
        ObjectNode root = objectMapper.createObjectNode();
        Sentiment sentiment = ComprehendAnalyzer.sentiment(text);
        root.put("Sentiment", sentiment.label());
        root.set("SentimentScore", scores(sentiment));
        return root;
    }

    ObjectNode syntax(String text) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode tokens = root.putArray("SyntaxTokens");
        int id = 1;
        for (Tagged tagged : ComprehendAnalyzer.tag(text)) {
            ObjectNode token = tokens.addObject();
            token.put("TokenId", id++);
            token.put("Text", tagged.token().text());
            token.put("BeginOffset", tagged.token().begin());
            token.put("EndOffset", tagged.token().end());
            token.putObject("PartOfSpeech").put("Tag", tagged.tag()).put("Score", 0.99);
        }
        return root;
    }

    /**
     * Each noun mention is an entity whose sentiment is its clause's: in
     * "the screen is gorgeous but the battery is bad", the screen is
     * POSITIVE and the battery NEGATIVE.
     */
    ObjectNode targetedSentiment(String text) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode entities = root.putArray("Entities");
        List<Tagged> tagged = ComprehendAnalyzer.tag(text);
        for (Phrase clause : ComprehendAnalyzer.clauses(text)) {
            Sentiment sentiment = ComprehendAnalyzer.sentiment(clause.text());
            for (Tagged word : tagged) {
                boolean inClause = word.token().begin() >= clause.begin() && word.token().end() <= clause.end();
                if (!inClause || !Set.of("NOUN", "PROPN", "PRON").contains(word.tag())) {
                    continue;
                }
                ObjectNode entity = entities.addObject();
                entity.putArray("DescriptiveMentionIndex").add(0);
                ObjectNode mention = entity.putArray("Mentions").addObject();
                mention.put("Score", 0.99);
                mention.put("GroupScore", 1.0);
                mention.put("Text", word.token().text());
                mention.put("Type", targetedType(word));
                ObjectNode mentionSentiment = mention.putObject("MentionSentiment");
                mentionSentiment.put("Sentiment", sentiment.label());
                mentionSentiment.set("SentimentScore", scores(sentiment));
                mention.put("BeginOffset", word.token().begin());
                mention.put("EndOffset", word.token().end());
            }
        }
        return root;
    }

    private static String targetedType(Tagged word) {
        if ("PRON".equals(word.tag())) {
            return "PERSON";
        }
        if ("PROPN".equals(word.tag())) {
            return ComprehendAnalyzer.entities(word.token().text()).stream().findFirst()
                    .map(Entity::type).orElse("OTHER");
        }
        return "ATTRIBUTE";
    }

    private ObjectNode scores(Sentiment sentiment) {
        ObjectNode score = objectMapper.createObjectNode();
        score.put("Positive", sentiment.positive());
        score.put("Negative", sentiment.negative());
        score.put("Neutral", sentiment.neutral());
        score.put("Mixed", sentiment.mixed());
        return score;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Optional<ObjectNode> mocked(String text, String action) {
        return mockConfigLoader.lookup(SERVICE_KEY, text, action)
                .filter(JsonNode::isObject)
                .map(node -> (ObjectNode) node.deepCopy());
    }

    private static String requireText(JsonNode request, int maxBytes) {
        String text = text(request, "Text");
        if (text == null || text.isEmpty()) {
            throw invalid("Text is a required field.");
        }
        if (text.length() > maxBytes || text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new AwsException("TextSizeLimitExceededException",
                    "Input text size exceeds limit. Max length of request text allowed is " + maxBytes
                            + " bytes.", 400);
        }
        return text;
    }

    static String requireLanguage(JsonNode request, Set<String> allowed) {
        String code = text(request, "LanguageCode");
        if (code == null || code.isEmpty()) {
            throw invalid("LanguageCode is a required field.");
        }
        if (!allowed.contains(code)) {
            throw new AwsException("UnsupportedLanguageException",
                    "The language code \"" + code + "\" is not supported for this operation.", 400);
        }
        return code;
    }

    private static AwsException endpointUnavailable(String endpointArn) {
        return new AwsException("ResourceUnavailableException",
                "Specified resource ARN was not found or is not available: " + endpointArn, 400);
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new AwsException("SerializationException",
                    "Unable to unmarshal request; the value for '" + field + "' is not a string.", 400);
        }
        return value.asText();
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
