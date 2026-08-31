package io.github.hectorvent.floci.services.translate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local Amazon Translate stub. Sync TranslateText/TranslateDocument use a
 * deterministic phrase/word dictionary (no ML). Terminologies, parallel data,
 * and batch jobs are stored and returned with AWS JSON 1.1 shapes.
 *
 * @see <a href="https://docs.aws.amazon.com/translate/latest/APIReference/API_Operations.html">Translate API</a>
 */
@ApplicationScoped
public class TranslateService implements Resettable {

    private static final Pattern TOKEN = Pattern.compile("\\p{L}+|\\P{L}+");
    private static final Pattern S3_URI = Pattern.compile(
            "^s3://[a-z0-9][a-z0-9.\\-]{1,61}[a-z0-9](/.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9_-]{0,255})$");
    private static final Pattern JOB_ID = Pattern.compile("^[0-9a-f]{32}$");

    private static final List<Language> LANGUAGES = List.of(
            lang("af", "Afrikaans"), lang("am", "Amharic"), lang("ar", "Arabic"),
            lang("az", "Azerbaijani"), lang("bg", "Bulgarian"), lang("bn", "Bengali"),
            lang("bs", "Bosnian"), lang("ca", "Catalan"), lang("cs", "Czech"),
            lang("cy", "Welsh"), lang("da", "Danish"), lang("de", "German"),
            lang("el", "Greek"), lang("en", "English"), lang("es", "Spanish"),
            lang("et", "Estonian"), lang("fa", "Persian"), lang("fi", "Finnish"),
            lang("fr", "French"), lang("ga", "Irish"), lang("gu", "Gujarati"),
            lang("ha", "Hausa"), lang("he", "Hebrew"), lang("hi", "Hindi"),
            lang("hr", "Croatian"), lang("hu", "Hungarian"), lang("id", "Indonesian"),
            lang("is", "Icelandic"), lang("it", "Italian"), lang("ja", "Japanese"),
            lang("kn", "Kannada"), lang("ko", "Korean"), lang("lt", "Lithuanian"),
            lang("lv", "Latvian"), lang("ms", "Malay"), lang("nl", "Dutch"),
            lang("no", "Norwegian"), lang("pl", "Polish"), lang("pt", "Portuguese"),
            lang("ro", "Romanian"), lang("ru", "Russian"), lang("sk", "Slovak"),
            lang("sl", "Slovenian"), lang("so", "Somali"), lang("sq", "Albanian"),
            lang("sr", "Serbian"), lang("sv", "Swedish"), lang("sw", "Swahili"),
            lang("ta", "Tamil"), lang("te", "Telugu"), lang("th", "Thai"),
            lang("tr", "Turkish"), lang("uk", "Ukrainian"), lang("ur", "Urdu"),
            lang("vi", "Vietnamese"), lang("zh", "Chinese (Simplified)"),
            lang("zh-TW", "Chinese (Traditional)"));

    private static final Map<String, String> EN_ES = Map.ofEntries(
            Map.entry("hello", "hola"),
            Map.entry("world", "mundo"),
            Map.entry("good", "buenos"),
            Map.entry("morning", "días"),
            Map.entry("friend", "amigo"),
            Map.entry("i", "yo"),
            Map.entry("love", "amo"),
            Map.entry("so", "tanto"),
            Map.entry("much", "mucho"),
            Map.entry("deploys", "despliega"),
            Map.entry("infrastructure", "infraestructura"));

    private static final Map<String, String> PHRASES_EN_ES = Map.of(
            "hello, world!", "¡Hola, mundo!",
            "good morning, friend.", "Buenos días, amigo.",
            "i love alchemy so much.", "Me encanta tanto Alchemy.");

    private final StorageFactory storageFactory;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    private Map<String, Terminology> terminologies = new ConcurrentHashMap<>();
    private Map<String, ParallelData> parallelData = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TextTranslationJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> clientTokens = new ConcurrentHashMap<>();

    @Inject
    public TranslateService(StorageFactory storageFactory, ObjectMapper objectMapper,
                            RegionResolver regionResolver) {
        this.storageFactory = storageFactory;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @PostConstruct
    void initializeStorage() {
        if (storageFactory == null) {
            return;
        }
        this.terminologies = new StorageBackedMap<>(storageFactory.create("translate",
                "translate-terminologies.json", new TypeReference<Map<String, Terminology>>() {}));
        this.parallelData = new StorageBackedMap<>(storageFactory.create("translate",
                "translate-parallel-data.json", new TypeReference<Map<String, ParallelData>>() {}));
    }

    @Override
    public void clear() {
        terminologies.clear();
        parallelData.clear();
        jobs.clear();
        clientTokens.clear();
    }

    public ObjectNode translateText(JsonNode request, String region) {
        String text = requireString(request, "Text");
        String source = requireLanguage(request, "SourceLanguageCode", false);
        String target = requireLanguage(request, "TargetLanguageCode", true);
        requirePair(source, target);
        AppliedResult applied = applyTranslation(text, source, target, request);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("TranslatedText", applied.text());
        root.put("SourceLanguageCode", source);
        root.put("TargetLanguageCode", target);
        if (applied.terminologies() != null) {
            root.set("AppliedTerminologies", applied.terminologies());
        }
        copySettings(request, root);
        return root;
    }

    public ObjectNode translateDocument(JsonNode request, String region) {
        JsonNode document = request == null ? null : request.get("Document");
        if (document == null || document.isNull()) {
            throw invalidRequest("Document is required.");
        }
        String contentType = stringField(document, "ContentType");
        if (contentType == null || contentType.isBlank()) {
            throw invalidRequest("Document.ContentType is required.");
        }
        byte[] content = decodeBlob(document.get("Content"));
        if (content == null) {
            throw invalidRequest("Document.Content is required.");
        }
        String source = requireLanguage(request, "SourceLanguageCode", false);
        String target = requireLanguage(request, "TargetLanguageCode", true);
        requirePair(source, target);
        String text = new String(content, StandardCharsets.UTF_8);
        AppliedResult applied = applyTranslation(text, source, target, request);
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode translated = root.putObject("TranslatedDocument");
        translated.put("Content", Base64.getEncoder().encodeToString(
                applied.text().getBytes(StandardCharsets.UTF_8)));
        root.put("SourceLanguageCode", source);
        root.put("TargetLanguageCode", target);
        if (applied.terminologies() != null) {
            root.set("AppliedTerminologies", applied.terminologies());
        }
        copySettings(request, root);
        return root;
    }

    public ObjectNode listLanguages(JsonNode request) {
        int limit = maxResults(request, LANGUAGES.size());
        int offset = nextOffset(request);
        int end = Math.min(offset + limit, LANGUAGES.size());
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode list = root.putArray("Languages");
        for (int i = offset; i < end; i++) {
            Language language = LANGUAGES.get(i);
            ObjectNode node = list.addObject();
            node.put("LanguageName", language.name());
            node.put("LanguageCode", language.code());
        }
        String display = stringField(request, "DisplayLanguageCode");
        root.put("DisplayLanguageCode", display == null || display.isBlank() ? "en" : display);
        if (end < LANGUAGES.size()) {
            root.put("NextToken", Integer.toString(end));
        }
        return root;
    }

    public ObjectNode importTerminology(JsonNode request, String region) {
        String name = requireName(request, "Name");
        String merge = stringField(request, "MergeStrategy");
        if (merge == null || merge.isBlank()) {
            throw invalidRequest("MergeStrategy is required.");
        }
        if (!"OVERWRITE".equals(merge)) {
            throw invalidParameter("MergeStrategy must be OVERWRITE.");
        }
        JsonNode data = request == null ? null : request.get("TerminologyData");
        if (data == null || data.isNull()) {
            throw invalidRequest("TerminologyData is required.");
        }
        String format = stringField(data, "Format");
        if (format == null || format.isBlank()) {
            throw invalidRequest("TerminologyData.Format is required.");
        }
        byte[] file = decodeBlob(data.get("File"));
        if (file == null) {
            throw invalidRequest("TerminologyData.File is required.");
        }
        String content = new String(file, StandardCharsets.UTF_8);
        ParsedTerminology parsed = parseTerminology(content, format);
        String directionality = stringField(data, "Directionality");
        if (directionality == null || directionality.isBlank()) {
            directionality = "UNI";
        }
        long now = Instant.now().getEpochSecond();
        Terminology existing = terminologies.get(name);
        String arn = existing != null
                ? existing.arn
                : regionResolver.buildArn("translate", region, "terminology/" + name);
        Map<String, String> tags = existing != null
                ? new LinkedHashMap<>(existing.tags())
                : new LinkedHashMap<>();
        mergeTags(tags, request == null ? null : request.get("Tags"));
        Terminology stored = new Terminology(
                name,
                stringField(request, "Description"),
                arn,
                parsed.sourceLanguageCode(),
                parsed.targetLanguageCodes(),
                (long) file.length,
                parsed.termCount(),
                parsed.skippedTermCount(),
                existing != null ? existing.createdAt : now,
                now,
                directionality,
                format,
                content,
                encryptionType(request),
                encryptionId(request),
                tags);
        terminologies.put(name, stored);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("TerminologyProperties", terminologyProperties(stored));
        return root;
    }

    public ObjectNode getTerminology(JsonNode request, String region) {
        Terminology terminology = requireTerminology(requireName(request, "Name"));
        ObjectNode root = objectMapper.createObjectNode();
        root.set("TerminologyProperties", terminologyProperties(terminology));
        ObjectNode location = root.putObject("TerminologyDataLocation");
        location.put("RepositoryType", "S3");
        location.put("Location", "https://aws-translate-glossary-" + region
                + ".s3.amazonaws.com/" + terminology.name);
        return root;
    }

    public ObjectNode listTerminologies(JsonNode request) {
        List<Terminology> all = terminologies.values().stream()
                .sorted(Comparator.comparing(t -> t.name))
                .toList();
        int limit = maxResults(request, 100);
        int offset = nextOffset(request);
        int end = Math.min(offset + limit, all.size());
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode list = root.putArray("TerminologyPropertiesList");
        for (int i = offset; i < end; i++) {
            list.add(terminologyProperties(all.get(i)));
        }
        if (end < all.size()) {
            root.put("NextToken", Integer.toString(end));
        }
        return root;
    }

    public ObjectNode deleteTerminology(JsonNode request) {
        String name = requireName(request, "Name");
        Terminology removed = terminologies.remove(name);
        if (removed == null) {
            throw notFound("The specified terminology " + name + " was not found.");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode createParallelData(JsonNode request, String region) {
        String name = requireName(request, "Name");
        if (parallelData.containsKey(name)) {
            throw new AwsException("ConflictException",
                    "The specified parallel data already exists.", 409);
        }
        ParallelData stored = upsertParallelData(request, region, null);
        parallelData.put(name, stored);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("Name", name);
        root.put("Status", stored.status);
        return root;
    }

    public ObjectNode updateParallelData(JsonNode request, String region) {
        String name = requireName(request, "Name");
        ParallelData existing = parallelData.get(name);
        if (existing == null) {
            throw notFound("The specified parallel data " + name + " was not found.");
        }
        ParallelData stored = upsertParallelData(request, region, existing);
        parallelData.put(name, stored);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("Name", name);
        root.put("Status", stored.status);
        return root;
    }

    public ObjectNode getParallelData(JsonNode request) {
        ParallelData data = requireParallelData(requireName(request, "Name"));
        ObjectNode root = objectMapper.createObjectNode();
        root.set("ParallelDataProperties", parallelDataProperties(data));
        ObjectNode location = root.putObject("DataLocation");
        location.put("RepositoryType", "S3");
        location.put("Location", data.s3Uri);
        return root;
    }

    public ObjectNode listParallelData(JsonNode request) {
        List<ParallelData> all = parallelData.values().stream()
                .sorted(Comparator.comparing(p -> p.name))
                .toList();
        int limit = maxResults(request, 100);
        int offset = nextOffset(request);
        int end = Math.min(offset + limit, all.size());
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode list = root.putArray("ParallelDataPropertiesList");
        for (int i = offset; i < end; i++) {
            list.add(parallelDataProperties(all.get(i)));
        }
        if (end < all.size()) {
            root.put("NextToken", Integer.toString(end));
        }
        return root;
    }

    public ObjectNode deleteParallelData(JsonNode request) {
        String name = requireName(request, "Name");
        ParallelData removed = parallelData.remove(name);
        if (removed == null) {
            throw notFound("The specified parallel data " + name + " was not found.");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("Name", name);
        root.put("Status", "DELETING");
        return root;
    }

    public ObjectNode startTextTranslationJob(JsonNode request, String region) {
        String clientToken = stringField(request, "ClientToken");
        if (clientToken != null && !clientToken.isBlank()) {
            String existingId = clientTokens.get(clientToken);
            if (existingId != null) {
                TextTranslationJob existing = jobs.get(existingId);
                if (existing != null) {
                    return jobStartResponse(existing);
                }
            }
        }
        String source = requireLanguage(request, "SourceLanguageCode", false);
        JsonNode targetsNode = request == null ? null : request.get("TargetLanguageCodes");
        if (targetsNode == null || !targetsNode.isArray() || targetsNode.isEmpty()) {
            throw invalidRequest("TargetLanguageCodes is required.");
        }
        List<String> targets = new ArrayList<>();
        for (JsonNode target : targetsNode) {
            String code = target.asText();
            requireKnownLanguage(code, true);
            requirePair(source, code);
            targets.add(code);
        }
        String roleArn = requireString(request, "DataAccessRoleArn");
        JsonNode input = request == null ? null : request.get("InputDataConfig");
        JsonNode output = request == null ? null : request.get("OutputDataConfig");
        String inputUri = input == null ? null : stringField(input, "S3Uri");
        String outputUri = output == null ? null : stringField(output, "S3Uri");
        if (!isValidS3Uri(inputUri) || !isValidS3Uri(outputUri)) {
            throw invalidParameter("Invalid S3 URI. URI must be in the format s3://<bucket>/<key>.");
        }
        String contentType = input == null ? null : stringField(input, "ContentType");
        if (contentType == null || contentType.isBlank()) {
            throw invalidRequest("InputDataConfig.ContentType is required.");
        }
        requireTerminologyNames(request);
        String jobId = UUID.randomUUID().toString().replace("-", "");
        long now = Instant.now().getEpochSecond();
        TextTranslationJob job = new TextTranslationJob(
                jobId,
                stringField(request, "JobName"),
                "SUBMITTED",
                source,
                targets,
                stringList(request, "TerminologyNames"),
                stringList(request, "ParallelDataNames"),
                now,
                null,
                inputUri,
                contentType,
                outputUri,
                roleArn);
        jobs.put(jobId, job);
        if (clientToken != null && !clientToken.isBlank()) {
            clientTokens.put(clientToken, jobId);
        }
        return jobStartResponse(job);
    }

    public ObjectNode describeTextTranslationJob(JsonNode request) {
        TextTranslationJob job = requireJob(requireJobId(request));
        ObjectNode root = objectMapper.createObjectNode();
        root.set("TextTranslationJobProperties", jobProperties(job));
        return root;
    }

    public ObjectNode listTextTranslationJobs(JsonNode request) {
        JsonNode filter = request == null ? null : request.get("Filter");
        String nameFilter = filter == null ? null : stringField(filter, "JobName");
        String statusFilter = filter == null ? null : stringField(filter, "JobStatus");
        List<TextTranslationJob> all = jobs.values().stream()
                .filter(job -> nameFilter == null || nameFilter.equals(job.jobName))
                .filter(job -> statusFilter == null || statusFilter.equals(job.jobStatus))
                .sorted(Comparator.comparing(j -> j.jobId))
                .toList();
        int limit = maxResults(request, 100);
        int offset = nextOffset(request);
        int end = Math.min(offset + limit, all.size());
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode list = root.putArray("TextTranslationJobPropertiesList");
        for (int i = offset; i < end; i++) {
            list.add(jobProperties(all.get(i)));
        }
        if (end < all.size()) {
            root.put("NextToken", Integer.toString(end));
        }
        return root;
    }

    public ObjectNode stopTextTranslationJob(JsonNode request) {
        TextTranslationJob job = requireJob(requireJobId(request));
        String status = job.jobStatus;
        if ("SUBMITTED".equals(status) || "IN_PROGRESS".equals(status)) {
            status = "STOP_REQUESTED";
            TextTranslationJob stopped = new TextTranslationJob(
                    job.jobId, job.jobName, status, job.sourceLanguageCode, job.targetLanguageCodes,
                    job.terminologyNames, job.parallelDataNames, job.submittedTime,
                    Instant.now().getEpochSecond(), job.inputS3Uri, job.contentType,
                    job.outputS3Uri, job.dataAccessRoleArn);
            jobs.put(job.jobId, stopped);
            job = stopped;
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", job.jobId);
        root.put("JobStatus", job.jobStatus);
        return root;
    }

    public ObjectNode tagResource(JsonNode request) {
        String arn = requireString(request, "ResourceArn");
        JsonNode tagsNode = request == null ? null : request.get("Tags");
        if (tagsNode == null || !tagsNode.isArray()) {
            throw invalidRequest("Tags is required.");
        }
        Tagged tagged = requireTagged(arn);
        Map<String, String> tags = new LinkedHashMap<>(tagged.tags());
        mergeTags(tags, tagsNode);
        tagged.storeTags(tags);
        return objectMapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode request) {
        String arn = requireString(request, "ResourceArn");
        JsonNode keys = request == null ? null : request.get("TagKeys");
        if (keys == null || !keys.isArray()) {
            throw invalidRequest("TagKeys is required.");
        }
        Tagged tagged = requireTagged(arn);
        Map<String, String> tags = new LinkedHashMap<>(tagged.tags());
        for (JsonNode key : keys) {
            if (key != null && !key.isNull()) {
                tags.remove(key.asText());
            }
        }
        tagged.storeTags(tags);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        Tagged tagged = requireTagged(requireString(request, "ResourceArn"));
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode tags = root.putArray("Tags");
        tagged.tags().forEach((key, value) -> {
            ObjectNode tag = tags.addObject();
            tag.put("Key", key);
            tag.put("Value", value);
        });
        return root;
    }

    private AppliedResult applyTranslation(String text, String source, String target, JsonNode request) {
        String translated = dictionaryTranslate(text, source, target);
        ArrayNode applied = null;
        List<String> names = stringList(request, "TerminologyNames");
        if (!names.isEmpty()) {
            applied = objectMapper.createArrayNode();
            for (String name : names) {
                Terminology terminology = requireTerminology(name);
                ObjectNode node = applied.addObject();
                node.put("Name", terminology.name);
                ArrayNode terms = node.putArray("Terms");
                translated = applyTerminology(translated, terminology, source, target, terms);
            }
        }
        return new AppliedResult(translated, applied);
    }

    private String dictionaryTranslate(String text, String source, String target) {
        String key = text.trim().toLowerCase(Locale.ROOT);
        if ("en".equalsIgnoreCase(source) && "es".equalsIgnoreCase(target)) {
            String phrase = PHRASES_EN_ES.get(key);
            if (phrase != null) {
                return phrase;
            }
            return wordTranslate(text, EN_ES);
        }
        if ("es".equalsIgnoreCase(source) && "en".equalsIgnoreCase(target)) {
            Map<String, String> reversed = new LinkedHashMap<>();
            EN_ES.forEach((en, es) -> reversed.put(es, en));
            return wordTranslate(text, reversed);
        }
        return text;
    }

    private static String wordTranslate(String text, Map<String, String> dict) {
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String part = matcher.group();
            if (part.chars().anyMatch(Character::isLetter)) {
                String mapped = dict.get(part.toLowerCase(Locale.ROOT));
                out.append(mapped == null ? part : preserveCase(part, mapped));
            } else {
                out.append(part);
            }
        }
        return out.toString();
    }

    private static String preserveCase(String original, String mapped) {
        if (original.equals(original.toUpperCase(Locale.ROOT))) {
            return mapped.toUpperCase(Locale.ROOT);
        }
        if (!original.isEmpty() && Character.isUpperCase(original.charAt(0))) {
            return Character.toUpperCase(mapped.charAt(0)) + mapped.substring(1);
        }
        return mapped;
    }

    private String applyTerminology(String text, Terminology terminology, String source, String target,
                                    ArrayNode appliedTerms) {
        ParsedTerminology parsed = parseTerminology(terminology.fileContent, terminology.format);
        int targetIndex = indexOfIgnoreCase(parsed.targetLanguageCodes(), target);
        boolean sourceMatches = parsed.sourceLanguageCode() != null
                && parsed.sourceLanguageCode().equalsIgnoreCase(source);
        if (!sourceMatches || targetIndex < 0) {
            return text;
        }
        String result = text;
        List<TermRow> rows = new ArrayList<>(parsed.terms());
        rows.sort(Comparator.comparingInt((TermRow row) -> row.source.length()).reversed());
        for (TermRow row : rows) {
            if (targetIndex >= row.targets.size()) {
                continue;
            }
            String replacement = row.targets.get(targetIndex);
            if (replacement == null || replacement.isBlank()) {
                continue;
            }
            if (containsTerm(result, row.source) || containsTerm(text, row.source)) {
                result = result.replace(row.source, replacement);
                ObjectNode term = appliedTerms.addObject();
                term.put("SourceText", row.source);
                term.put("TargetText", replacement);
            }
        }
        return result;
    }

    private static boolean containsTerm(String haystack, String term) {
        return haystack != null && term != null && !term.isEmpty() && haystack.contains(term);
    }

    private ParsedTerminology parseTerminology(String content, String format) {
        if (content == null || content.isBlank()) {
            throw invalidParameter("Terminology file is empty.");
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        char separator = "TSV".equalsIgnoreCase(format) ? '\t' : ',';
        List<String> header = null;
        List<TermRow> terms = new ArrayList<>();
        int skipped = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> cols = split(line, separator);
            if (header == null) {
                header = cols;
                continue;
            }
            if (cols.size() < 2) {
                skipped++;
                continue;
            }
            terms.add(new TermRow(cols.get(0), cols.subList(1, cols.size())));
        }
        if (header == null || header.size() < 2) {
            throw invalidParameter("Terminology file must include a language-code header row.");
        }
        return new ParsedTerminology(
                header.get(0),
                header.subList(1, header.size()),
                terms.size(),
                skipped,
                terms);
    }

    private static List<String> split(String line, char separator) {
        List<String> cols = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == separator) {
                cols.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cols.add(current.toString().trim());
        return cols;
    }

    private ParallelData upsertParallelData(JsonNode request, String region, ParallelData existing) {
        String name = requireName(request, "Name");
        JsonNode config = request == null ? null : request.get("ParallelDataConfig");
        String s3Uri = config == null ? null : stringField(config, "S3Uri");
        if (!isValidS3Uri(s3Uri)) {
            throw invalidParameter("ParallelDataConfig.S3Uri is required.");
        }
        String format = config == null ? null : stringField(config, "Format");
        if (format == null || format.isBlank()) {
            format = "CSV";
        }
        long now = Instant.now().getEpochSecond();
        String arn = existing != null
                ? existing.arn
                : regionResolver.buildArn("translate", region, "parallel-data/" + name);
        Map<String, String> tags = existing != null
                ? new LinkedHashMap<>(existing.tags())
                : new LinkedHashMap<>();
        mergeTags(tags, request == null ? null : request.get("Tags"));
        return new ParallelData(
                name,
                arn,
                stringField(request, "Description"),
                "ACTIVE",
                "en",
                List.of("es"),
                s3Uri,
                format,
                1L,
                0L,
                existing != null ? existing.createdAt : now,
                now,
                encryptionType(request),
                encryptionId(request),
                tags);
    }

    private ObjectNode terminologyProperties(Terminology terminology) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", terminology.name);
        if (terminology.description != null) {
            node.put("Description", terminology.description);
        }
        node.put("Arn", terminology.arn);
        if (terminology.sourceLanguageCode != null) {
            node.put("SourceLanguageCode", terminology.sourceLanguageCode);
        }
        ArrayNode targets = node.putArray("TargetLanguageCodes");
        for (String code : terminology.targetLanguageCodes()) {
            targets.add(code);
        }
        if (terminology.sizeBytes != null) {
            node.put("SizeBytes", terminology.sizeBytes);
        }
        if (terminology.termCount != null) {
            node.put("TermCount", terminology.termCount);
        }
        if (terminology.skippedTermCount != null) {
            node.put("SkippedTermCount", terminology.skippedTermCount);
        }
        if (terminology.createdAt != null) {
            node.put("CreatedAt", terminology.createdAt);
        }
        if (terminology.lastUpdatedAt != null) {
            node.put("LastUpdatedAt", terminology.lastUpdatedAt);
        }
        if (terminology.directionality != null) {
            node.put("Directionality", terminology.directionality);
        }
        if (terminology.format != null) {
            node.put("Format", terminology.format);
        }
        putEncryption(node, terminology.encryptionKeyType, terminology.encryptionKeyId);
        return node;
    }

    private ObjectNode parallelDataProperties(ParallelData data) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", data.name);
        node.put("Arn", data.arn);
        if (data.description != null) {
            node.put("Description", data.description);
        }
        node.put("Status", data.status);
        if (data.sourceLanguageCode != null) {
            node.put("SourceLanguageCode", data.sourceLanguageCode);
        }
        ArrayNode targets = node.putArray("TargetLanguageCodes");
        for (String code : data.targetLanguageCodes()) {
            targets.add(code);
        }
        ObjectNode config = node.putObject("ParallelDataConfig");
        config.put("S3Uri", data.s3Uri);
        config.put("Format", data.format);
        if (data.importedRecordCount != null) {
            node.put("ImportedRecordCount", data.importedRecordCount);
        }
        if (data.failedRecordCount != null) {
            node.put("FailedRecordCount", data.failedRecordCount);
        }
        if (data.createdAt != null) {
            node.put("CreatedAt", data.createdAt);
        }
        if (data.lastUpdatedAt != null) {
            node.put("LastUpdatedAt", data.lastUpdatedAt);
        }
        putEncryption(node, data.encryptionKeyType, data.encryptionKeyId);
        return node;
    }

    private ObjectNode jobProperties(TextTranslationJob job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("JobId", job.jobId);
        if (job.jobName != null) {
            node.put("JobName", job.jobName);
        }
        node.put("JobStatus", job.jobStatus);
        node.put("SourceLanguageCode", job.sourceLanguageCode);
        ArrayNode targets = node.putArray("TargetLanguageCodes");
        for (String code : job.targetLanguageCodes()) {
            targets.add(code);
        }
        if (!job.terminologyNames().isEmpty()) {
            ArrayNode names = node.putArray("TerminologyNames");
            job.terminologyNames().forEach(names::add);
        }
        if (!job.parallelDataNames().isEmpty()) {
            ArrayNode names = node.putArray("ParallelDataNames");
            job.parallelDataNames().forEach(names::add);
        }
        node.put("SubmittedTime", job.submittedTime);
        if (job.endTime != null) {
            node.put("EndTime", job.endTime);
        }
        ObjectNode input = node.putObject("InputDataConfig");
        input.put("S3Uri", job.inputS3Uri);
        input.put("ContentType", job.contentType);
        ObjectNode output = node.putObject("OutputDataConfig");
        output.put("S3Uri", job.outputS3Uri);
        node.put("DataAccessRoleArn", job.dataAccessRoleArn);
        return node;
    }

    private ObjectNode jobStartResponse(TextTranslationJob job) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", job.jobId);
        root.put("JobStatus", job.jobStatus);
        return root;
    }

    private void putEncryption(ObjectNode node, String type, String id) {
        if (type == null || id == null) {
            return;
        }
        ObjectNode key = node.putObject("EncryptionKey");
        key.put("Type", type);
        key.put("Id", id);
    }

    private void copySettings(JsonNode request, ObjectNode root) {
        JsonNode settings = request == null ? null : request.get("Settings");
        if (settings != null && settings.isObject() && !settings.isEmpty()) {
            root.set("AppliedSettings", settings);
        }
    }

    private Terminology requireTerminology(String name) {
        Terminology terminology = terminologies.get(name);
        if (terminology == null) {
            throw notFound("The specified terminology " + name + " was not found.");
        }
        return terminology;
    }

    private ParallelData requireParallelData(String name) {
        ParallelData data = parallelData.get(name);
        if (data == null) {
            throw notFound("The specified parallel data " + name + " was not found.");
        }
        return data;
    }

    private TextTranslationJob requireJob(String jobId) {
        TextTranslationJob job = jobs.get(jobId);
        if (job == null) {
            throw notFound("The specified job " + jobId + " was not found.");
        }
        return job;
    }

    private Tagged requireTagged(String arn) {
        for (Terminology terminology : terminologies.values()) {
            if (arn.equals(terminology.arn)) {
                return new Tagged() {
                    @Override
                    public Map<String, String> tags() {
                        return terminology.tags();
                    }

                    @Override
                    public void storeTags(Map<String, String> tags) {
                        terminologies.put(terminology.name, terminology.withTags(tags));
                    }
                };
            }
        }
        for (ParallelData data : parallelData.values()) {
            if (arn.equals(data.arn)) {
                return new Tagged() {
                    @Override
                    public Map<String, String> tags() {
                        return data.tags();
                    }

                    @Override
                    public void storeTags(Map<String, String> tags) {
                        parallelData.put(data.name, data.withTags(tags));
                    }
                };
            }
        }
        throw notFound("The specified resource was not found.");
    }

    private void requireTerminologyNames(JsonNode request) {
        for (String name : stringList(request, "TerminologyNames")) {
            requireTerminology(name);
        }
        for (String name : stringList(request, "ParallelDataNames")) {
            requireParallelData(name);
        }
    }

    private String requireJobId(JsonNode request) {
        String jobId = requireString(request, "JobId");
        if (!JOB_ID.matcher(jobId).matches()) {
            throw notFound("The specified job " + jobId + " was not found.");
        }
        return jobId;
    }

    private String requireName(JsonNode request, String field) {
        String name = requireString(request, field);
        if (!NAME.matcher(name).matches()) {
            throw invalidParameter("Value at '" + field + "' failed to satisfy constraint.");
        }
        return name;
    }

    private String requireString(JsonNode request, String field) {
        String value = stringField(request, field);
        if (value == null || value.isBlank()) {
            throw invalidRequest(field + " is required.");
        }
        return value;
    }

    private String requireLanguage(JsonNode request, String field, boolean target) {
        String code = requireString(request, field);
        requireKnownLanguage(code, target);
        return code;
    }

    private void requireKnownLanguage(String code, boolean target) {
        if ("auto".equalsIgnoreCase(code) && !target) {
            return;
        }
        for (Language language : LANGUAGES) {
            if (language.code().equalsIgnoreCase(code) || language.code().equals(code)) {
                return;
            }
        }
        throw new AwsException("UnsupportedLanguagePairException",
                "The specified language is not supported.", 400);
    }

    private void requirePair(String source, String target) {
        if (source.equalsIgnoreCase(target) && !"auto".equalsIgnoreCase(source)) {
            throw new AwsException("UnsupportedLanguagePairException",
                    "Source and target language cannot be the same.", 400);
        }
    }

    private static String stringField(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return (value != null && !value.isNull() && !value.isMissingNode()) ? value.asText() : null;
    }

    private static List<String> stringList(JsonNode node, String field) {
        if (node == null) {
            return List.of();
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (item != null && !item.isNull()) {
                result.add(item.asText());
            }
        }
        return result;
    }

    private static int maxResults(JsonNode request, int fallback) {
        if (request == null || request.get("MaxResults") == null || request.get("MaxResults").isNull()) {
            return fallback;
        }
        int value = request.get("MaxResults").asInt(fallback);
        if (value < 1) {
            throw new AwsException("InvalidParameterValueException",
                    "MaxResults must be greater than 0.", 400);
        }
        return Math.min(value, fallback);
    }

    private static int nextOffset(JsonNode request) {
        String token = stringField(request, "NextToken");
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(token));
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValueException", "Invalid NextToken.", 400);
        }
    }

    private static byte[] decodeBlob(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isBinary()) {
            try {
                return node.binaryValue();
            } catch (Exception e) {
                throw new AwsException("InvalidParameterValueException", "Invalid binary content.", 400);
            }
        }
        if (node.isTextual()) {
            String text = node.asText();
            try {
                return Base64.getDecoder().decode(text);
            } catch (IllegalArgumentException e) {
                return text.getBytes(StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void mergeTags(Map<String, String> tags, JsonNode tagsNode) {
        if (tagsNode == null || !tagsNode.isArray()) {
            return;
        }
        for (JsonNode tag : tagsNode) {
            String key = stringField(tag, "Key");
            String value = stringField(tag, "Value");
            if (key != null) {
                tags.put(key, value == null ? "" : value);
            }
        }
    }

    private static String encryptionType(JsonNode request) {
        JsonNode key = request == null ? null : request.get("EncryptionKey");
        return key == null ? null : stringField(key, "Type");
    }

    private static String encryptionId(JsonNode request) {
        JsonNode key = request == null ? null : request.get("EncryptionKey");
        return key == null ? null : stringField(key, "Id");
    }

    private static boolean isValidS3Uri(String uri) {
        return uri != null && S3_URI.matcher(uri).matches();
    }

    private static int indexOfIgnoreCase(List<String> values, String wanted) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).equalsIgnoreCase(wanted)) {
                return i;
            }
        }
        return -1;
    }

    private static AwsException invalidRequest(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static AwsException invalidParameter(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static Language lang(String code, String name) {
        return new Language(code, name);
    }

    private record Language(String code, String name) {}

    private record AppliedResult(String text, ArrayNode terminologies) {}

    private record TermRow(String source, List<String> targets) {}

    private record ParsedTerminology(
            String sourceLanguageCode,
            List<String> targetLanguageCodes,
            int termCount,
            int skippedTermCount,
            List<TermRow> terms) {}

    private interface Tagged {
        Map<String, String> tags();

        void storeTags(Map<String, String> tags);
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Terminology {
        public String name;
        public String description;
        public String arn;
        public String sourceLanguageCode;
        public List<String> targetLanguageCodes = new ArrayList<>();
        public Long sizeBytes;
        public Integer termCount;
        public Integer skippedTermCount;
        public Long createdAt;
        public Long lastUpdatedAt;
        public String directionality;
        public String format;
        public String fileContent;
        public String encryptionKeyType;
        public String encryptionKeyId;
        public Map<String, String> tags = new LinkedHashMap<>();

        public Terminology() {}

        Terminology(String name, String description, String arn, String sourceLanguageCode,
                    List<String> targetLanguageCodes, Long sizeBytes, Integer termCount,
                    Integer skippedTermCount, Long createdAt, Long lastUpdatedAt,
                    String directionality, String format, String fileContent,
                    String encryptionKeyType, String encryptionKeyId, Map<String, String> tags) {
            this.name = name;
            this.description = description;
            this.arn = arn;
            this.sourceLanguageCode = sourceLanguageCode;
            this.targetLanguageCodes = targetLanguageCodes == null ? List.of() : List.copyOf(targetLanguageCodes);
            this.sizeBytes = sizeBytes;
            this.termCount = termCount;
            this.skippedTermCount = skippedTermCount;
            this.createdAt = createdAt;
            this.lastUpdatedAt = lastUpdatedAt;
            this.directionality = directionality;
            this.format = format;
            this.fileContent = fileContent;
            this.encryptionKeyType = encryptionKeyType;
            this.encryptionKeyId = encryptionKeyId;
            this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
        }

        List<String> targetLanguageCodes() {
            return targetLanguageCodes == null ? List.of() : targetLanguageCodes;
        }

        Map<String, String> tags() {
            return tags == null ? Map.of() : tags;
        }

        Terminology withTags(Map<String, String> tags) {
            return new Terminology(name, description, arn, sourceLanguageCode, targetLanguageCodes,
                    sizeBytes, termCount, skippedTermCount, createdAt, lastUpdatedAt, directionality,
                    format, fileContent, encryptionKeyType, encryptionKeyId, tags);
        }
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class ParallelData {
        public String name;
        public String arn;
        public String description;
        public String status;
        public String sourceLanguageCode;
        public List<String> targetLanguageCodes = new ArrayList<>();
        public String s3Uri;
        public String format;
        public Long importedRecordCount;
        public Long failedRecordCount;
        public Long createdAt;
        public Long lastUpdatedAt;
        public String encryptionKeyType;
        public String encryptionKeyId;
        public Map<String, String> tags = new LinkedHashMap<>();

        public ParallelData() {}

        ParallelData(String name, String arn, String description, String status,
                     String sourceLanguageCode, List<String> targetLanguageCodes, String s3Uri,
                     String format, Long importedRecordCount, Long failedRecordCount,
                     Long createdAt, Long lastUpdatedAt, String encryptionKeyType,
                     String encryptionKeyId, Map<String, String> tags) {
            this.name = name;
            this.arn = arn;
            this.description = description;
            this.status = status;
            this.sourceLanguageCode = sourceLanguageCode;
            this.targetLanguageCodes = targetLanguageCodes == null ? List.of() : List.copyOf(targetLanguageCodes);
            this.s3Uri = s3Uri;
            this.format = format;
            this.importedRecordCount = importedRecordCount;
            this.failedRecordCount = failedRecordCount;
            this.createdAt = createdAt;
            this.lastUpdatedAt = lastUpdatedAt;
            this.encryptionKeyType = encryptionKeyType;
            this.encryptionKeyId = encryptionKeyId;
            this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
        }

        List<String> targetLanguageCodes() {
            return targetLanguageCodes == null ? List.of() : targetLanguageCodes;
        }

        Map<String, String> tags() {
            return tags == null ? Map.of() : tags;
        }

        ParallelData withTags(Map<String, String> tags) {
            return new ParallelData(name, arn, description, status, sourceLanguageCode,
                    targetLanguageCodes, s3Uri, format, importedRecordCount, failedRecordCount,
                    createdAt, lastUpdatedAt, encryptionKeyType, encryptionKeyId, tags);
        }
    }

    @RegisterForReflection
    public static final class TextTranslationJob {
        public String jobId;
        public String jobName;
        public String jobStatus;
        public String sourceLanguageCode;
        public List<String> targetLanguageCodes = new ArrayList<>();
        public List<String> terminologyNames = new ArrayList<>();
        public List<String> parallelDataNames = new ArrayList<>();
        public Long submittedTime;
        public Long endTime;
        public String inputS3Uri;
        public String contentType;
        public String outputS3Uri;
        public String dataAccessRoleArn;

        public TextTranslationJob() {}

        TextTranslationJob(String jobId, String jobName, String jobStatus, String sourceLanguageCode,
                           List<String> targetLanguageCodes, List<String> terminologyNames,
                           List<String> parallelDataNames, Long submittedTime, Long endTime,
                           String inputS3Uri, String contentType, String outputS3Uri,
                           String dataAccessRoleArn) {
            this.jobId = jobId;
            this.jobName = jobName;
            this.jobStatus = jobStatus;
            this.sourceLanguageCode = sourceLanguageCode;
            this.targetLanguageCodes = targetLanguageCodes == null ? List.of() : List.copyOf(targetLanguageCodes);
            this.terminologyNames = terminologyNames == null ? List.of() : List.copyOf(terminologyNames);
            this.parallelDataNames = parallelDataNames == null ? List.of() : List.copyOf(parallelDataNames);
            this.submittedTime = submittedTime;
            this.endTime = endTime;
            this.inputS3Uri = inputS3Uri;
            this.contentType = contentType;
            this.outputS3Uri = outputS3Uri;
            this.dataAccessRoleArn = dataAccessRoleArn;
        }

        List<String> targetLanguageCodes() {
            return targetLanguageCodes == null ? List.of() : targetLanguageCodes;
        }

        List<String> terminologyNames() {
            return terminologyNames == null ? List.of() : terminologyNames;
        }

        List<String> parallelDataNames() {
            return parallelDataNames == null ? List.of() : parallelDataNames;
        }
    }
}
