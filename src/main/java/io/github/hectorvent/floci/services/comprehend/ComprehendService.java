package io.github.hectorvent.floci.services.comprehend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local Amazon Comprehend stub. Sync detect APIs run a small deterministic
 * heuristic (no ML). Async Start/Describe/List/Stop jobs are stored in memory.
 *
 * @see <a href="https://docs.aws.amazon.com/comprehend/latest/APIReference/Welcome.html">Comprehend API</a>
 */
@ApplicationScoped
public class ComprehendService implements Resettable {

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern S3_URI = Pattern.compile(
            "^s3://[a-z0-9][a-z0-9.\\-]{1,61}[a-z0-9](/.*)?$");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9]+|[^A-Za-z0-9\\s]");

    private static final Set<String> PERSONS = Set.of(
            "bob", "jane", "doe", "john", "mary", "alice", "tom", "smith", "anna");
    private static final Set<String> LOCATIONS = Set.of(
            "seattle", "london", "paris", "tokyo", "chicago", "boston", "portland",
            "austin", "denver", "miami", "california", "washington", "york");
    private static final Set<String> POSITIVE = Set.of(
            "love", "loved", "wonderful", "wonderfully", "great", "excellent", "good",
            "gorgeous", "amazing", "happy", "exceeded");
    private static final Set<String> NEGATIVE = Set.of(
            "hate", "terrible", "disappointing", "late", "damaged", "bad", "awful",
            "horrible", "sad", "worst");
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "be",
            "to", "of", "in", "on", "for", "with", "it", "this", "that", "i", "you",
            "he", "she", "we", "they", "my", "me", "at", "from", "by", "as");
    private static final Map<String, String> POS = Map.ofEntries(
            Map.entry("the", "DET"), Map.entry("a", "DET"), Map.entry("an", "DET"),
            Map.entry("this", "DET"), Map.entry("that", "DET"),
            Map.entry("i", "PRON"), Map.entry("you", "PRON"), Map.entry("he", "PRON"),
            Map.entry("she", "PRON"), Map.entry("we", "PRON"), Map.entry("they", "PRON"),
            Map.entry("it", "PRON"), Map.entry("my", "PRON"), Map.entry("me", "PRON"),
            Map.entry("is", "VERB"), Map.entry("are", "VERB"), Map.entry("was", "VERB"),
            Map.entry("were", "VERB"), Map.entry("be", "VERB"), Map.entry("am", "VERB"),
            Map.entry("sat", "VERB"), Map.entry("moved", "VERB"), Map.entry("ordered", "VERB"),
            Map.entry("works", "VERB"), Map.entry("love", "VERB"), Map.entry("arrived", "VERB"),
            Map.entry("exceeded", "VERB"),
            Map.entry("in", "ADP"), Map.entry("on", "ADP"), Map.entry("to", "ADP"),
            Map.entry("of", "ADP"), Map.entry("for", "ADP"), Map.entry("with", "ADP"),
            Map.entry("at", "ADP"), Map.entry("from", "ADP"), Map.entry("by", "ADP"),
            Map.entry("and", "CCONJ"), Map.entry("but", "CCONJ"), Map.entry("or", "CCONJ"),
            Map.entry("wonderfully", "ADV"), Map.entry("yesterday", "ADV"),
            Map.entry("gorgeous", "ADJ"), Map.entry("disappointing", "ADJ"),
            Map.entry("late", "ADJ"), Map.entry("damaged", "ADJ"), Map.entry("quarterly", "ADJ"));

    enum JobFamily {
        DOCUMENT_CLASSIFICATION("DocumentClassificationJobProperties", "document-classification-job"),
        DOMINANT_LANGUAGE("DominantLanguageDetectionJobProperties", "dominant-language-detection-job"),
        ENTITIES("EntitiesDetectionJobProperties", "entities-detection-job"),
        EVENTS("EventsDetectionJobProperties", "events-detection-job"),
        KEY_PHRASES("KeyPhrasesDetectionJobProperties", "key-phrases-detection-job"),
        PII_ENTITIES("PiiEntitiesDetectionJobProperties", "pii-entities-detection-job"),
        SENTIMENT("SentimentDetectionJobProperties", "sentiment-detection-job"),
        TARGETED_SENTIMENT("TargetedSentimentDetectionJobProperties", "targeted-sentiment-detection-job"),
        TOPICS("TopicsDetectionJobProperties", "topics-detection-job");

        final String propertiesField;
        final String arnResource;

        JobFamily(String propertiesField, String arnResource) {
            this.propertiesField = propertiesField;
            this.arnResource = arnResource;
        }

        String listField() {
            return propertiesField + "List";
        }
    }

    private record Token(String text, int begin, int end) {
        String lower() {
            return text.toLowerCase(Locale.ROOT);
        }
    }

    private record AsyncJob(
            JobFamily family,
            String jobId,
            String jobArn,
            String jobName,
            String jobStatus,
            String languageCode,
            String dataAccessRoleArn,
            JsonNode inputDataConfig,
            JsonNode outputDataConfig,
            long submitTime
    ) {}

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final ConcurrentHashMap<String, AsyncJob> jobs = new ConcurrentHashMap<>();

    @Inject
    public ComprehendService(ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    public void clear() {
        jobs.clear();
    }

    public ObjectNode detectDominantLanguage(JsonNode request) {
        String text = requireText(request);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode languages = root.putArray("Languages");
        ObjectNode lang = languages.addObject();
        lang.put("LanguageCode", guessLanguage(text));
        lang.put("Score", 0.99);
        return root;
    }

    public ObjectNode detectEntities(JsonNode request) {
        String text = requireText(request);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("Entities", entitiesArray(text));
        return root;
    }

    public ObjectNode detectKeyPhrases(JsonNode request) {
        String text = requireText(request);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("KeyPhrases", keyPhrasesArray(text));
        return root;
    }

    public ObjectNode detectPiiEntities(JsonNode request) {
        String text = requireText(request);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("Entities", piiArray(text));
        return root;
    }

    public ObjectNode detectSentiment(JsonNode request) {
        String text = requireText(request);
        return sentimentNode(scoreSentiment(text));
    }

    public ObjectNode detectSyntax(JsonNode request) {
        String text = requireText(request);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode tokens = root.putArray("SyntaxTokens");
        int id = 1;
        for (Token token : tokenize(text)) {
            ObjectNode node = tokens.addObject();
            node.put("TokenId", id++);
            node.put("Text", token.text);
            node.put("BeginOffset", token.begin);
            node.put("EndOffset", token.end);
            ObjectNode pos = node.putObject("PartOfSpeech");
            pos.put("Tag", posTag(token));
            pos.put("Score", 0.99);
        }
        return root;
    }

    public ObjectNode detectTargetedSentiment(JsonNode request) {
        String text = requireText(request);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("Entities", targetedArray(text));
        return root;
    }

    public ObjectNode detectToxicContent(JsonNode request) {
        JsonNode segments = request == null ? null : request.get("TextSegments");
        if (segments == null || !segments.isArray() || segments.isEmpty()) {
            throw invalid("TextSegments is required.");
        }
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode resultList = root.putArray("ResultList");
        for (JsonNode segment : segments) {
            String text = stringField(segment, "Text");
            if (text == null) {
                text = "";
            }
            String sentiment = scoreSentiment(text);
            double toxicity = "NEGATIVE".equals(sentiment) ? 0.2 : 0.01;
            ObjectNode labels = resultList.addObject();
            labels.putArray("Labels");
            labels.put("Toxicity", toxicity);
        }
        return root;
    }

    public ObjectNode containsPiiEntities(JsonNode request) {
        String text = requireText(request);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode labels = root.putArray("Labels");
        LinkedHashSet<String> types = new LinkedHashSet<>();
        ArrayNode entities = piiArray(text);
        for (JsonNode entity : entities) {
            types.add(entity.path("Type").asText());
        }
        for (String type : types) {
            ObjectNode label = labels.addObject();
            label.put("Name", type);
            label.put("Score", 0.99);
        }
        return root;
    }

    public ObjectNode classifyDocument(JsonNode request) {
        String endpointArn = stringField(request, "EndpointArn");
        if (endpointArn == null || endpointArn.isBlank()) {
            throw invalid("EndpointArn is required.");
        }
        throw new AwsException("ResourceUnavailableException",
                "The specified endpoint is not available.", 404);
    }

    public ObjectNode batchDetectDominantLanguage(JsonNode request) {
        List<String> texts = requireTextList(request);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode resultList = root.putArray("ResultList");
        root.putArray("ErrorList");
        for (int i = 0; i < texts.size(); i++) {
            ObjectNode item = resultList.addObject();
            item.put("Index", i);
            ArrayNode languages = item.putArray("Languages");
            ObjectNode lang = languages.addObject();
            lang.put("LanguageCode", guessLanguage(texts.get(i)));
            lang.put("Score", 0.99);
        }
        return root;
    }

    public ObjectNode batchDetectEntities(JsonNode request) {
        List<String> texts = requireTextList(request);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode resultList = root.putArray("ResultList");
        root.putArray("ErrorList");
        for (int i = 0; i < texts.size(); i++) {
            ObjectNode item = resultList.addObject();
            item.put("Index", i);
            item.set("Entities", entitiesArray(texts.get(i)));
        }
        return root;
    }

    public ObjectNode batchDetectKeyPhrases(JsonNode request) {
        List<String> texts = requireTextList(request);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode resultList = root.putArray("ResultList");
        root.putArray("ErrorList");
        for (int i = 0; i < texts.size(); i++) {
            ObjectNode item = resultList.addObject();
            item.put("Index", i);
            item.set("KeyPhrases", keyPhrasesArray(texts.get(i)));
        }
        return root;
    }

    public ObjectNode batchDetectSentiment(JsonNode request) {
        List<String> texts = requireTextList(request);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode resultList = root.putArray("ResultList");
        root.putArray("ErrorList");
        for (int i = 0; i < texts.size(); i++) {
            ObjectNode item = resultList.addObject();
            item.put("Index", i);
            String sentiment = scoreSentiment(texts.get(i));
            item.put("Sentiment", sentiment);
            item.set("SentimentScore", sentimentScore(sentiment));
        }
        return root;
    }

    public ObjectNode batchDetectSyntax(JsonNode request) {
        List<String> texts = requireTextList(request);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode resultList = root.putArray("ResultList");
        root.putArray("ErrorList");
        for (int i = 0; i < texts.size(); i++) {
            ObjectNode item = resultList.addObject();
            item.put("Index", i);
            item.set("SyntaxTokens", detectSyntax(textNode(texts.get(i))).get("SyntaxTokens"));
        }
        return root;
    }

    public ObjectNode batchDetectTargetedSentiment(JsonNode request) {
        List<String> texts = requireTextList(request);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode resultList = root.putArray("ResultList");
        root.putArray("ErrorList");
        for (int i = 0; i < texts.size(); i++) {
            ObjectNode item = resultList.addObject();
            item.put("Index", i);
            item.set("Entities", targetedArray(texts.get(i)));
        }
        return root;
    }

    public ObjectNode startJob(JobFamily family, JsonNode request) {
        validateStart(request);
        String jobId = UUID.randomUUID().toString().replace("-", "");
        String region = regionResolver.getRegion();
        String account = regionResolver.getAccountId();
        String jobArn = "arn:aws:comprehend:" + region + ":" + account + ":"
                + family.arnResource + "/" + jobId;
        String jobName = stringField(request, "JobName");
        AsyncJob job = new AsyncJob(
                family,
                jobId,
                jobArn,
                jobName,
                "SUBMITTED",
                stringField(request, "LanguageCode"),
                stringField(request, "DataAccessRoleArn"),
                request.path("InputDataConfig"),
                request.path("OutputDataConfig"),
                Instant.now().getEpochSecond());
        jobs.put(jobKey(family, jobId), job);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", jobId);
        root.put("JobArn", jobArn);
        root.put("JobStatus", "SUBMITTED");
        return root;
    }

    public ObjectNode describeJob(JobFamily family, JsonNode request) {
        AsyncJob job = requireJob(family, stringField(request, "JobId"));
        ObjectNode root = objectMapper.createObjectNode();
        root.set(family.propertiesField, jobProperties(job));
        return root;
    }

    public ObjectNode listJobs(JobFamily family) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode list = root.putArray(family.listField());
        jobs.values().stream()
                .filter(job -> job.family == family)
                .sorted((a, b) -> a.jobId.compareTo(b.jobId))
                .forEach(job -> list.add(jobProperties(job)));
        return root;
    }

    public ObjectNode stopJob(JobFamily family, JsonNode request) {
        AsyncJob job = requireJob(family, stringField(request, "JobId"));
        String status = "STOP_REQUESTED";
        if ("STOPPED".equals(job.jobStatus) || "COMPLETED".equals(job.jobStatus)
                || "FAILED".equals(job.jobStatus)) {
            status = job.jobStatus;
        } else {
            AsyncJob stopped = new AsyncJob(
                    job.family, job.jobId, job.jobArn, job.jobName, "STOP_REQUESTED",
                    job.languageCode, job.dataAccessRoleArn, job.inputDataConfig,
                    job.outputDataConfig, job.submitTime);
            jobs.put(jobKey(family, job.jobId), stopped);
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", job.jobId);
        root.put("JobStatus", status);
        return root;
    }

    private void validateStart(JsonNode request) {
        String roleArn = stringField(request, "DataAccessRoleArn");
        if (roleArn == null || roleArn.isBlank()) {
            throw invalid("DataAccessRoleArn is required.");
        }
        String inputUri = request.path("InputDataConfig").path("S3Uri").asText(null);
        String outputUri = request.path("OutputDataConfig").path("S3Uri").asText(null);
        if (!isValidS3Uri(inputUri) || !isValidS3Uri(outputUri)) {
            throw invalid("Invalid S3 URI. URI must be in the format s3://<bucket>/<key>.");
        }
    }

    private static boolean isValidS3Uri(String uri) {
        return uri != null && S3_URI.matcher(uri).matches();
    }

    private AsyncJob requireJob(JobFamily family, String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw invalid("JobId is required.");
        }
        AsyncJob job = jobs.get(jobKey(family, jobId));
        if (job == null) {
            throw new AwsException("JobNotFoundException",
                    "The specified job was not found.", 404);
        }
        return job;
    }

    private ObjectNode jobProperties(AsyncJob job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("JobId", job.jobId);
        node.put("JobArn", job.jobArn);
        if (job.jobName != null) {
            node.put("JobName", job.jobName);
        }
        node.put("JobStatus", job.jobStatus);
        node.put("SubmitTime", job.submitTime);
        if (job.languageCode != null) {
            node.put("LanguageCode", job.languageCode);
        }
        if (job.dataAccessRoleArn != null) {
            node.put("DataAccessRoleArn", job.dataAccessRoleArn);
        }
        if (job.inputDataConfig != null && !job.inputDataConfig.isMissingNode()) {
            node.set("InputDataConfig", job.inputDataConfig);
        }
        if (job.outputDataConfig != null && !job.outputDataConfig.isMissingNode()) {
            node.set("OutputDataConfig", job.outputDataConfig);
        }
        return node;
    }

    private static String jobKey(JobFamily family, String jobId) {
        return family.name() + ":" + jobId;
    }

    private ArrayNode entitiesArray(String text) {
        ArrayNode entities = objectMapper.createArrayNode();
        for (Token token : tokenize(text)) {
            String type = entityType(token);
            if (type == null) {
                continue;
            }
            ObjectNode entity = entities.addObject();
            entity.put("Score", 0.99);
            entity.put("Type", type);
            entity.put("Text", token.text);
            entity.put("BeginOffset", token.begin);
            entity.put("EndOffset", token.end);
        }
        return entities;
    }

    private ArrayNode keyPhrasesArray(String text) {
        ArrayNode phrases = objectMapper.createArrayNode();
        List<Token> tokens = tokenize(text);
        int i = 0;
        while (i < tokens.size()) {
            Token token = tokens.get(i);
            if (!isWord(token) || STOPWORDS.contains(token.lower())) {
                i++;
                continue;
            }
            int begin = token.begin;
            int end = token.end;
            int j = i + 1;
            while (j < tokens.size()) {
                Token next = tokens.get(j);
                if (!isWord(next) || STOPWORDS.contains(next.lower())) {
                    break;
                }
                end = next.end;
                j++;
            }
            ObjectNode phrase = phrases.addObject();
            phrase.put("Score", 0.99);
            phrase.put("Text", text.substring(begin, end));
            phrase.put("BeginOffset", begin);
            phrase.put("EndOffset", end);
            i = j;
        }
        if (phrases.isEmpty() && text != null && !text.isBlank()) {
            ObjectNode phrase = phrases.addObject();
            phrase.put("Score", 0.99);
            phrase.put("Text", text);
            phrase.put("BeginOffset", 0);
            phrase.put("EndOffset", text.length());
        }
        return phrases;
    }

    private ArrayNode piiArray(String text) {
        ArrayNode entities = objectMapper.createArrayNode();
        List<Token> tokens = tokenize(text);
        int i = 0;
        while (i < tokens.size()) {
            Token token = tokens.get(i);
            if (PERSONS.contains(token.lower())) {
                int begin = token.begin;
                int end = token.end;
                int j = i + 1;
                while (j < tokens.size() && PERSONS.contains(tokens.get(j).lower())) {
                    end = tokens.get(j).end;
                    j++;
                }
                ObjectNode name = entities.addObject();
                name.put("Score", 0.99);
                name.put("Type", "NAME");
                name.put("BeginOffset", begin);
                name.put("EndOffset", end);
                i = j;
                continue;
            }
            i++;
        }
        Matcher emails = EMAIL.matcher(text);
        while (emails.find()) {
            ObjectNode email = entities.addObject();
            email.put("Score", 0.99);
            email.put("Type", "EMAIL");
            email.put("BeginOffset", emails.start());
            email.put("EndOffset", emails.end());
        }
        return entities;
    }

    private ArrayNode targetedArray(String text) {
        ArrayNode entities = objectMapper.createArrayNode();
        String overall = scoreSentiment(text);
        for (Token token : tokenize(text)) {
            if (!isWord(token) || STOPWORDS.contains(token.lower()) || POS.containsKey(token.lower())) {
                continue;
            }
            if (token.text.length() < 3) {
                continue;
            }
            ObjectNode entity = entities.addObject();
            ArrayNode mentions = entity.putArray("Mentions");
            ObjectNode mention = mentions.addObject();
            mention.put("Score", 0.99);
            mention.put("GroupScore", 0.99);
            mention.put("Text", token.text);
            mention.put("Type", "ATTRIBUTE");
            mention.put("BeginOffset", token.begin);
            mention.put("EndOffset", token.end);
            ObjectNode mentionSentiment = mention.putObject("MentionSentiment");
            mentionSentiment.put("Sentiment", overall);
            mentionSentiment.set("SentimentScore", sentimentScore(overall));
            entity.putArray("DescriptiveMentionIndex").add(0);
        }
        return entities;
    }

    private ObjectNode sentimentNode(String sentiment) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("Sentiment", sentiment);
        root.set("SentimentScore", sentimentScore(sentiment));
        return root;
    }

    private ObjectNode sentimentScore(String sentiment) {
        ObjectNode score = objectMapper.createObjectNode();
        score.put("Positive", "POSITIVE".equals(sentiment) ? 0.95 : 0.02);
        score.put("Negative", "NEGATIVE".equals(sentiment) ? 0.95 : 0.02);
        score.put("Neutral", "NEUTRAL".equals(sentiment) ? 0.95 : 0.02);
        score.put("Mixed", "MIXED".equals(sentiment) ? 0.95 : 0.01);
        return score;
    }

    private static String entityType(Token token) {
        String lower = token.lower();
        if (PERSONS.contains(lower)) {
            return "PERSON";
        }
        if (LOCATIONS.contains(lower)) {
            return "LOCATION";
        }
        if (lower.matches("\\d{4}")) {
            return "DATE";
        }
        return null;
    }

    private static String posTag(Token token) {
        if (!isWord(token)) {
            return "PUNCT";
        }
        if (token.text.chars().allMatch(Character::isDigit)) {
            return "NUM";
        }
        String mapped = POS.get(token.lower());
        if (mapped != null) {
            return mapped;
        }
        if (!token.text.isEmpty() && Character.isUpperCase(token.text.charAt(0))) {
            return "PROPN";
        }
        return "NOUN";
    }

    private static String scoreSentiment(String text) {
        int pos = 0;
        int neg = 0;
        for (Token token : tokenize(text)) {
            if (POSITIVE.contains(token.lower())) {
                pos++;
            }
            if (NEGATIVE.contains(token.lower())) {
                neg++;
            }
        }
        if (pos > 0 && neg > 0) {
            return "MIXED";
        }
        if (pos > 0) {
            return "POSITIVE";
        }
        if (neg > 0) {
            return "NEGATIVE";
        }
        return "NEUTRAL";
    }

    private static String guessLanguage(String text) {
        if (text != null && text.codePoints().anyMatch(
                cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
            return "zh";
        }
        return "en";
    }

    private static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            tokens.add(new Token(matcher.group(), matcher.start(), matcher.end()));
        }
        return tokens;
    }

    private static boolean isWord(Token token) {
        return !token.text.isEmpty() && Character.isLetterOrDigit(token.text.charAt(0));
    }

    private String requireText(JsonNode request) {
        String text = stringField(request, "Text");
        if (text == null || text.isBlank()) {
            throw invalid("Text is required.");
        }
        return text;
    }

    private List<String> requireTextList(JsonNode request) {
        JsonNode list = request == null ? null : request.get("TextList");
        if (list == null || !list.isArray() || list.isEmpty()) {
            throw invalid("TextList is required.");
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode node : list) {
            texts.add(node.asText(""));
        }
        return texts;
    }

    private ObjectNode textNode(String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Text", text);
        return node;
    }

    private static String stringField(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isEmpty() ? null : text;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
