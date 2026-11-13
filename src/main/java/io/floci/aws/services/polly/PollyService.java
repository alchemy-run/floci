package io.floci.aws.services.polly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.VendoredResources;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon Polly: lexicons, the voice catalog, synthesis (real-time, as tasks writing to S3, and
 * streamed), and speech marks. Requests are checked as Polly checks them: the voice must exist
 * and support the engine, the language must be one of the voice's, the sample rate must suit the
 * format, speech marks need the {@code json} format, SSML must be well formed, lexicons must exist,
 * and the billed characters must fit the limit. The audio is silence of the spoken length (see
 * {@link PollyAudio}).
 */
@ApplicationScoped
public class PollyService {
    static final int SYNTHESIZE_MAX_BILLED = 3_000;
    static final int TASK_MAX_BILLED = 100_000;
    static final long TASK_SCHEDULED_MILLIS = 1_000;
    static final long TASK_COMPLETED_MILLIS = 2_000;
    private static final Pattern LEXICON_NAME = Pattern.compile("[0-9A-Za-z]{1,20}");
    private static final Pattern TASK_ID = Pattern.compile("[a-zA-Z0-9_-]{1,100}");
    private static final Pattern S3_KEY_PREFIX = Pattern.compile("[0-9a-zA-Z/!\\-_.*'():;$@=+,?&]{0,800}");
    private static final Set<String> ALPHABETS = Set.of("ipa", "x-sampa", "x-amazon-pinyin", "x-amazon-jyutping",
            "x-amazon-pron-kana", "x-amazon-yomigana");
    private static final Set<String> FORMATS = Set.of("json", "mp3", "ogg_opus", "ogg_vorbis", "pcm", "mulaw", "alaw");
    private static final Set<String> ENGINES = Set.of("standard", "neural", "long-form", "generative");
    private static final Set<String> MARK_TYPES = Set.of("sentence", "ssml", "viseme", "word");
    private static final int MAX_LEXICON_SIZE = 4_000;
    private static final int MAX_LEXEME_LENGTH = 100;
    private static final int MAX_LEXICONS = 100;

    private final StorageBackend<String, ObjectNode> lexicons;
    private final StorageBackend<String, ObjectNode> tasks;
    private final RegionResolver regionResolver;
    private final Instance<S3Service> s3;
    private final Instance<SnsService> sns;
    private final ObjectMapper mapper;
    private final JsonNode catalog;

    @Inject
    public PollyService(StorageFactory storageFactory, RegionResolver regionResolver, Instance<S3Service> s3,
                        Instance<SnsService> sns, ObjectMapper mapper) {
        this.lexicons = storageFactory.create("polly", "polly-lexicons.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.tasks = storageFactory.create("polly", "polly-tasks.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regionResolver = regionResolver;
        this.s3 = s3;
        this.sns = sns;
        this.mapper = mapper;
        this.catalog = VendoredResources.read("polly/voices.json", "Polly voices", mapper::readTree);
    }

    // ── Lexicons ───────────────────────────────────────────────────────────

    public synchronized void putLexicon(String name, JsonNode request, String region) {
        validLexiconName(name);
        String content = request.path("Content").asText(null);
        if (content == null || content.isEmpty()) {
            throw validation("Content is required.");
        }
        if (content.length() > MAX_LEXICON_SIZE) {
            throw new AwsException("LexiconSizeExceededException", "The lexicon is larger than "
                    + MAX_LEXICON_SIZE + " characters.", 400);
        }
        Element root = parseXml(content, "InvalidLexiconException").getDocumentElement();
        if (!"lexicon".equals(root.getLocalName() == null ? root.getTagName() : root.getLocalName())) {
            throw new AwsException("InvalidLexiconException", "The root element of a lexicon must be <lexicon>.", 400);
        }
        String alphabet = root.getAttribute("alphabet");
        if (!ALPHABETS.contains(alphabet)) {
            throw new AwsException("UnsupportedPlsAlphabetException", "The alphabet " + alphabet
                    + " is not supported.", 400);
        }
        String language = root.getAttributeNS(XMLConstants.XML_NS_URI, "lang");
        if (language.isEmpty()) {
            language = root.getAttribute("xml:lang");
        }
        if (!supportedLexiconLanguage(language)) {
            throw new AwsException("UnsupportedPlsLanguageException", "The language " + language
                    + " is not supported.", 400);
        }
        NodeList lexemes = root.getElementsByTagNameNS("*", "lexeme");
        for (int i = 0; i < lexemes.getLength(); i++) {
            Element lexeme = (Element) lexemes.item(i);
            if (lexeme.getElementsByTagNameNS("*", "grapheme").getLength() == 0) {
                throw new AwsException("InvalidLexiconException", "Every lexeme needs a grapheme.", 400);
            }
            NodeList replacements = lexeme.getElementsByTagNameNS("*", "*");
            boolean replaced = false;
            for (int j = 0; j < replacements.getLength(); j++) {
                Element child = (Element) replacements.item(j);
                String tag = child.getLocalName();
                if ("phoneme".equals(tag) || "alias".equals(tag)) {
                    replaced = true;
                    if (child.getTextContent().length() > MAX_LEXEME_LENGTH) {
                        throw new AwsException("MaxLexemeLengthExceededException", "A lexeme's " + tag
                                + " is longer than " + MAX_LEXEME_LENGTH + " characters.", 400);
                    }
                }
            }
            if (!replaced) {
                throw new AwsException("InvalidLexiconException", "Every lexeme needs a phoneme or an alias.", 400);
            }
        }
        String key = key(region, name);
        if (lexicons.get(key).isEmpty()
                && lexicons.scan(k -> k.startsWith(region + "::")).size() >= MAX_LEXICONS) {
            throw new AwsException("MaxLexiconsNumberExceededException", "An account can store at most "
                    + MAX_LEXICONS + " lexicons in a Region.", 400);
        }
        ObjectNode lexicon = mapper.createObjectNode();
        lexicon.put("Name", name);
        lexicon.put("Content", content);
        ObjectNode attributes = lexicon.putObject("Attributes");
        attributes.put("Alphabet", alphabet);
        attributes.put("LanguageCode", language);
        attributes.put("LastModified", Instant.now().toEpochMilli() / 1000.0);
        attributes.put("LexiconArn", regionResolver.buildArn("polly", region, "lexicon/" + name));
        attributes.put("LexemesCount", lexemes.getLength());
        attributes.put("Size", content.length());
        lexicons.put(key, lexicon);
    }

    public ObjectNode getLexicon(String name, String region) {
        ObjectNode lexicon = requireLexicon(name, region);
        ObjectNode response = mapper.createObjectNode();
        response.putObject("Lexicon").put("Content", lexicon.path("Content").asText()).put("Name", name);
        response.set("LexiconAttributes", lexicon.get("Attributes").deepCopy());
        return response;
    }

    public synchronized void deleteLexicon(String name, String region) {
        requireLexicon(name, region);
        lexicons.delete(key(region, name));
    }

    public ObjectNode listLexicons(String nextToken, String region) {
        List<ObjectNode> all = lexicons.scan(k -> k.startsWith(region + "::")).stream()
                .sorted(Comparator.comparing(l -> l.path("Name").asText())).toList();
        int start = offset(nextToken);
        int end = Math.min(all.size(), start + 100);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("Lexicons");
        for (ObjectNode lexicon : all.subList(Math.min(start, end), end)) {
            list.addObject().put("Name", lexicon.path("Name").asText())
                    .set("Attributes", lexicon.get("Attributes").deepCopy());
        }
        if (end < all.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        return response;
    }

    private ObjectNode requireLexicon(String name, String region) {
        validLexiconName(name);
        return lexicons.get(key(region, name)).orElseThrow(() -> new AwsException("LexiconNotFoundException",
                "Lexicon not found: " + name, 404));
    }

    private static void validLexiconName(String name) {
        if (name == null || !LEXICON_NAME.matcher(name).matches()) {
            throw validation("Lexicon names are 1-20 letters and digits.");
        }
    }

    private boolean supportedLexiconLanguage(String language) {
        if (language == null || language.isEmpty()) {
            return false;
        }
        for (Iterator<String> it = catalog.path("languages").fieldNames(); it.hasNext(); ) {
            String code = it.next();
            if (code.equalsIgnoreCase(language) || code.toLowerCase(Locale.ROOT).startsWith(language.toLowerCase(Locale.ROOT) + "-")) {
                return true;
            }
        }
        return false;
    }

    // ── Voices ─────────────────────────────────────────────────────────────

    public ObjectNode describeVoices(String engine, String languageCode, Boolean includeAdditional, String nextToken) {
        if (engine != null && !ENGINES.contains(engine)) {
            throw validation("Engine must be one of " + ENGINES + ".");
        }
        List<JsonNode> matching = new ArrayList<>();
        for (JsonNode voice : catalog.path("voices")) {
            boolean engineMatches = engine == null || contains(voice.path("SupportedEngines"), engine);
            boolean languageMatches = languageCode == null || languageCode.equals(voice.path("LanguageCode").asText())
                    || Boolean.TRUE.equals(includeAdditional) && contains(voice.path("AdditionalLanguageCodes"),
                            languageCode);
            if (engineMatches && languageMatches) {
                matching.add(voice);
            }
        }
        int start = offset(nextToken);
        int end = Math.min(matching.size(), start + 100);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("Voices");
        matching.subList(Math.min(start, end), end).forEach(voice -> list.add(voice.deepCopy()));
        if (end < matching.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        return response;
    }

    private JsonNode voice(String id) {
        for (JsonNode voice : catalog.path("voices")) {
            if (voice.path("Id").asText().equals(id)) {
                return voice;
            }
        }
        throw validation("1 validation error detected: Value '" + id + "' at 'voiceId' failed to satisfy constraint:"
                + " Member must satisfy enum value set.");
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (item.asText().equals(value)) {
                return true;
            }
        }
        return false;
    }

    // ── Synthesis ──────────────────────────────────────────────────────────

    /** A checked synthesis request: what to say, how, and the output. */
    record Plan(String voiceId, String engine, String languageCode, String format, int sampleRate,
                Set<String> markTypes, String textType, List<String> lexiconNames) {}

    /** The synthesized output and the characters billed for it. */
    public record Output(byte[] bytes, String contentType, int characters) {}

    Plan plan(String voiceId, String engine, String languageCode, String format, String sampleRate,
              Set<String> markTypes, String textType, List<String> lexiconNames, String region) {
        if (voiceId == null) {
            throw validation("VoiceId is required.");
        }
        if (format == null || !FORMATS.contains(format)) {
            throw validation("OutputFormat must be one of " + FORMATS + ".");
        }
        String resolvedEngine = engine == null ? "standard" : engine;
        if (!ENGINES.contains(resolvedEngine)) {
            throw validation("Engine must be one of " + ENGINES + ".");
        }
        JsonNode voice = voice(voiceId);
        if (!contains(voice.path("SupportedEngines"), resolvedEngine)) {
            throw new AwsException("EngineNotSupportedException", "This voice does not support the selected engine: "
                    + resolvedEngine, 400);
        }
        String language = languageCode == null ? voice.path("LanguageCode").asText() : languageCode;
        if (!language.equals(voice.path("LanguageCode").asText())
                && !contains(voice.path("AdditionalLanguageCodes"), language)) {
            throw new AwsException("LanguageNotSupportedException", "The language " + language
                    + " is not supported by voice " + voiceId + ".", 400);
        }
        int rate = sampleRate(format, resolvedEngine, sampleRate);
        Set<String> marks = markTypes == null ? Set.of() : markTypes;
        for (String mark : marks) {
            if (!MARK_TYPES.contains(mark)) {
                throw validation("SpeechMarkTypes must be among " + MARK_TYPES + ".");
            }
        }
        if (!marks.isEmpty() && !"json".equals(format)) {
            throw new AwsException("MarksNotSupportedForFormatException", "Speech marks are only available with the"
                    + " json output format.", 400);
        }
        String type = textType == null ? "text" : textType;
        if (!"text".equals(type) && !"ssml".equals(type)) {
            throw validation("TextType must be text or ssml.");
        }
        if (marks.contains("ssml") && !"ssml".equals(type)) {
            throw new AwsException("SsmlMarksNotSupportedForTextTypeException", "SSML speech marks need SSML input.",
                    400);
        }
        List<String> names = lexiconNames == null ? List.of() : lexiconNames;
        if (names.size() > 5) {
            throw validation("At most five lexicons can be applied.");
        }
        names.forEach(name -> requireLexicon(name, region));
        return new Plan(voiceId, resolvedEngine, language, format, rate, marks, type, names);
    }

    private static int sampleRate(String format, String engine, String requested) {
        Set<String> allowed = switch (format) {
            case "pcm" -> Set.of("8000", "16000");
            case "mulaw", "alaw" -> Set.of("8000");
            case "json" -> Set.of("8000", "16000", "22050", "24000");
            default -> Set.of("8000", "16000", "22050", "24000");
        };
        if (requested == null) {
            return switch (format) {
                case "pcm" -> 16000;
                case "mulaw", "alaw" -> 8000;
                default -> "standard".equals(engine) ? 22050 : 24000;
            };
        }
        if (!allowed.contains(requested)) {
            throw new AwsException("InvalidSampleRateException", "The sample rate " + requested
                    + " is not valid for the " + format + " format.", 400);
        }
        return Integer.parseInt(requested);
    }

    /** Synthesizes {@code text} for a checked plan, within {@code maxBilled} billed characters. */
    Output synthesize(Plan plan, String text, int maxBilled) {
        if (text == null || text.isEmpty()) {
            throw validation("Text is required.");
        }
        String spoken = "ssml".equals(plan.textType()) ? ssmlText(text) : text;
        if (spoken.length() > maxBilled || text.length() > maxBilled * 2) {
            throw new AwsException("TextLengthExceededException", "Maximum text length has been exceeded", 400);
        }
        if ("json".equals(plan.format())) {
            return new Output(PollyAudio.speechMarks(text, plan.markTypes(), mapper).getBytes(StandardCharsets.UTF_8),
                    PollyAudio.contentType("json"), spoken.length());
        }
        return new Output(PollyAudio.audio(plan.format(), plan.sampleRate(), PollyAudio.durationMillis(spoken)),
                PollyAudio.contentType(plan.format()), spoken.length());
    }

    public Output synthesizeSpeech(JsonNode request, String region) {
        Plan plan = plan(request, region);
        return synthesize(plan, request.path("Text").asText(null), SYNTHESIZE_MAX_BILLED);
    }

    private Plan plan(JsonNode request, String region) {
        Set<String> marks = new LinkedHashSet<>();
        request.path("SpeechMarkTypes").forEach(mark -> marks.add(mark.asText()));
        List<String> names = new ArrayList<>();
        request.path("LexiconNames").forEach(name -> names.add(name.asText()));
        return plan(request.path("VoiceId").asText(null), request.path("Engine").asText(null),
                request.path("LanguageCode").asText(null), request.path("OutputFormat").asText(null),
                request.path("SampleRate").asText(null), marks, request.path("TextType").asText(null), names, region);
    }

    /** The text an SSML document speaks; {@code InvalidSsmlException} when it is not a {@code <speak>} document. */
    private static String ssmlText(String ssml) {
        Element root = parseXml(ssml, "InvalidSsmlException").getDocumentElement();
        if (!"speak".equals(root.getTagName())) {
            throw new AwsException("InvalidSsmlException", "The SSML must have <speak> as its root element.", 400);
        }
        return root.getTextContent();
    }

    private static Document parseXml(String content, String errorCode) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AwsException(errorCode, "The document is not well-formed XML: " + e.getMessage(), 400);
        }
    }

    // ── Synthesis tasks ────────────────────────────────────────────────────

    public synchronized ObjectNode startTask(JsonNode request, String region) {
        Plan plan = plan(request, region);
        String bucket = request.path("OutputS3BucketName").asText(null);
        if (bucket == null || bucket.isEmpty()) {
            throw validation("OutputS3BucketName is required.");
        }
        String prefix = request.path("OutputS3KeyPrefix").asText("");
        if (!S3_KEY_PREFIX.matcher(prefix).matches()) {
            throw new AwsException("InvalidS3KeyException", "The S3 key prefix " + prefix + " is not valid.", 400);
        }
        String topic = request.path("SnsTopicArn").asText(null);
        if (topic != null && !AwsArnUtils.isArnFor(topic, "sns")) {
            throw new AwsException("InvalidSnsTopicArnException", "The SNS topic ARN " + topic + " is not valid.", 400);
        }
        Output output = synthesize(plan, request.path("Text").asText(null), TASK_MAX_BILLED);
        String taskId = UUID.randomUUID().toString();
        String key = prefix + taskId + "." + PollyAudio.extension(plan.format());
        try {
            s3.get().putObject(bucket, key, output.bytes(), output.contentType(), Map.of());
        } catch (AwsException e) {
            throw new AwsException("InvalidS3BucketException", "The S3 bucket " + bucket
                    + " does not exist or is not writable: " + e.getMessage(), 400);
        }
        ObjectNode task = mapper.createObjectNode();
        task.put("Engine", plan.engine());
        task.put("TaskId", taskId);
        task.put("OutputUri", "https://s3." + region + "." + AwsRegions.dnsSuffixFor(region) + "/" + bucket + "/" + key);
        task.put("CreationTime", Instant.now().toEpochMilli() / 1000.0);
        task.put("RequestCharacters", output.characters());
        if (topic != null) {
            task.put("SnsTopicArn", topic);
        }
        task.set("LexiconNames", mapper.valueToTree(plan.lexiconNames()));
        task.put("OutputFormat", plan.format());
        task.put("SampleRate", Integer.toString(plan.sampleRate()));
        task.set("SpeechMarkTypes", mapper.valueToTree(plan.markTypes()));
        task.put("TextType", plan.textType());
        task.put("VoiceId", plan.voiceId());
        task.put("LanguageCode", plan.languageCode());
        tasks.put(key(region, taskId), task);
        if (topic != null && sns.isResolvable()) {
            ObjectNode message = mapper.createObjectNode().put("taskId", taskId).put("taskStatus", "COMPLETED")
                    .put("outputUri", task.path("OutputUri").asText()).put("requestCharacters", output.characters());
            sns.get().publish(topic, null, message.toString(), null, region);
        }
        return view(task);
    }

    public ObjectNode getTask(String taskId, String region) {
        if (taskId == null || !TASK_ID.matcher(taskId).matches()) {
            throw new AwsException("InvalidTaskIdException", "The task id " + taskId + " is not valid.", 400);
        }
        ObjectNode task = tasks.get(key(region, taskId)).orElseThrow(() -> new AwsException(
                "SynthesisTaskNotFoundException", "The synthesis task " + taskId + " was not found.", 400));
        return view(task);
    }

    public ObjectNode listTasks(Integer maxResults, String nextToken, String status, String region) {
        int max = maxResults == null ? 100 : maxResults;
        if (max < 1 || max > 100) {
            throw validation("MaxResults must be between 1 and 100.");
        }
        List<ObjectNode> all = tasks.scan(k -> k.startsWith(region + "::")).stream().map(this::view)
                .filter(task -> status == null || status.equals(task.path("TaskStatus").asText()))
                .sorted(Comparator.comparingDouble((ObjectNode task) -> task.path("CreationTime").asDouble()).reversed())
                .toList();
        int start = offset(nextToken);
        int end = Math.min(all.size(), start + max);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("SynthesisTasks");
        all.subList(Math.min(start, end), end).forEach(list::add);
        if (end < all.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        return response;
    }

    /** The task with its status for now: scheduled, then in progress, then completed. */
    private ObjectNode view(ObjectNode task) {
        ObjectNode copy = task.deepCopy();
        double elapsed = (Instant.now().toEpochMilli() / 1000.0 - task.path("CreationTime").asDouble()) * 1000;
        copy.put("TaskStatus", elapsed >= TASK_COMPLETED_MILLIS ? "completed"
                : elapsed >= TASK_SCHEDULED_MILLIS ? "inProgress" : "scheduled");
        return copy;
    }

    private static int offset(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(token));
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidNextTokenException", "The NextToken is not valid.", 400);
        }
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
