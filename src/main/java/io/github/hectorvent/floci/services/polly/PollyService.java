package io.github.hectorvent.floci.services.polly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.polly.model.Lexicon;
import io.github.hectorvent.floci.services.polly.model.SynthesisTaskEntry;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amazon Polly restJson1 — lexicons, voices, speech synthesis, and async
 * synthesis tasks. Audio is a dummy MPEG payload (no real TTS).
 *
 * @see <a href="https://docs.aws.amazon.com/polly/latest/dg/API_Operations.html">Polly API</a>
 */
@ApplicationScoped
public class PollyService implements Resettable {

    static final String SERVICE = "polly";

    private static final Pattern NAME_PATTERN = Pattern.compile("[0-9A-Za-z]{1,20}");
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Za-z0-9:]+)\\s*=\\s*\"([^\"]+)\"");
    private static final Set<String> ALPHABETS = Set.of("ipa", "x-sampa");
    private static final Set<String> ENGINES = Set.of("standard", "neural", "long-form", "generative");
    private static final Set<String> OUTPUT_FORMATS =
            Set.of("json", "mp3", "ogg_opus", "ogg_vorbis", "pcm", "mulaw", "alaw");
    private static final int AUDIO_BYTES = 2048;
    private static final List<Voice> VOICES = List.of(
            voice("Joanna", "Female", "en-US", "US English", "standard", "neural", "generative"),
            voice("Matthew", "Male", "en-US", "US English", "standard", "neural", "generative"),
            voice("Ivy", "Female", "en-US", "US English", "standard", "neural"),
            voice("Joey", "Male", "en-US", "US English", "standard", "neural"),
            voice("Justin", "Male", "en-US", "US English", "standard", "neural"),
            voice("Kendra", "Female", "en-US", "US English", "standard", "neural"),
            voice("Kimberly", "Female", "en-US", "US English", "standard", "neural"),
            voice("Salli", "Female", "en-US", "US English", "standard", "neural"),
            voice("Kevin", "Male", "en-US", "US English", "neural"),
            voice("Ruth", "Female", "en-US", "US English", "neural", "generative"),
            voice("Stephen", "Male", "en-US", "US English", "neural", "generative"),
            voice("Danielle", "Female", "en-US", "US English", "generative", "long-form"),
            voice("Gregory", "Male", "en-US", "US English", "generative", "long-form"),
            voice("Amy", "Female", "en-GB", "British English", "standard", "neural", "generative"),
            voice("Brian", "Male", "en-GB", "British English", "standard", "neural"),
            voice("Emma", "Female", "en-GB", "British English", "standard", "neural"),
            voice("Lupe", "Female", "es-US", "US Spanish", "standard", "neural", "generative"),
            voice("Mizuki", "Female", "ja-JP", "Japanese", "standard"));

    private final StorageBackend<String, Lexicon> lexicons;
    private final ConcurrentHashMap<String, SynthesisTaskEntry> tasks = new ConcurrentHashMap<>();
    private final RegionResolver regionResolver;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    @Inject
    public PollyService(
            StorageFactory storageFactory,
            RegionResolver regionResolver,
            S3Service s3Service,
            ObjectMapper objectMapper) {
        this(storageFactory.create("polly", "polly-lexicons.json",
                        new TypeReference<Map<String, Lexicon>>() {
                        }),
                regionResolver, s3Service, objectMapper);
    }

    PollyService(
            StorageBackend<String, Lexicon> lexicons,
            RegionResolver regionResolver,
            S3Service s3Service,
            ObjectMapper objectMapper) {
        this.lexicons = lexicons;
        this.regionResolver = regionResolver;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
    }

    @Override
    public void clear() {
        lexicons.clear();
        tasks.clear();
    }

    public synchronized void putLexicon(String region, String name, JsonNode request) {
        requireName(name);
        String content = requireText(request, "Content");
        if (!content.toLowerCase(Locale.ROOT).contains("<lexicon")) {
            throw new AwsException("InvalidLexiconException",
                    "The specified lexicon is not valid.", 400);
        }
        String alphabet = attribute(content, "alphabet");
        if (alphabet != null && !ALPHABETS.contains(alphabet.toLowerCase(Locale.ROOT))) {
            throw new AwsException("UnsupportedPlsAlphabetException",
                    "The specified alphabet is not supported.", 400);
        }
        String languageCode = attribute(content, "xml:lang");
        if (languageCode == null) {
            languageCode = attribute(content, "lang");
        }
        Lexicon entry = new Lexicon();
        entry.setName(name);
        entry.setContent(content);
        entry.setAlphabet(alphabet);
        entry.setLanguageCode(languageCode);
        entry.setLexemesCount(countTag(content, "lexeme"));
        entry.setSize(content.getBytes(StandardCharsets.UTF_8).length);
        entry.setLastModified(Instant.now().getEpochSecond());
        entry.setRegion(region);
        entry.setLexiconArn(regionResolver.buildArn(SERVICE, region, "lexicon/" + name));
        lexicons.put(lexiconKey(region, name), entry);
    }

    public ObjectNode getLexicon(String region, String name) {
        Lexicon entry = requireLexicon(region, name);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode lexicon = response.putObject("Lexicon");
        lexicon.put("Name", entry.getName());
        lexicon.put("Content", entry.getContent());
        response.set("LexiconAttributes", toAttributes(entry));
        return response;
    }

    public ObjectNode listLexicons(String region, String nextToken) {
        List<Lexicon> entries = lexicons.scan(k -> true).stream()
                .filter(entry -> region == null || region.equals(entry.getRegion()))
                .sorted(Comparator.comparing(Lexicon::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        int start = parseOffset(nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Lexicons");
        for (int i = start; i < entries.size(); i++) {
            Lexicon entry = entries.get(i);
            ObjectNode item = list.addObject();
            item.put("Name", entry.getName());
            item.set("Attributes", toAttributes(entry));
        }
        return response;
    }

    public void deleteLexicon(String region, String name) {
        requireLexicon(region, name);
        lexicons.delete(lexiconKey(region, name));
    }

    public ObjectNode describeVoices(String engine, String languageCode, Boolean includeAdditional) {
        String engineFilter = engine == null ? null : engine.trim();
        String languageFilter = languageCode == null ? null : languageCode.trim();
        boolean include = Boolean.TRUE.equals(includeAdditional);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode voices = response.putArray("Voices");
        for (Voice voice : VOICES) {
            if (engineFilter != null && !voice.supportedEngines.contains(engineFilter)) {
                continue;
            }
            if (languageFilter != null) {
                boolean primary = languageFilter.equals(voice.languageCode);
                boolean additional = include && voice.additionalLanguageCodes.contains(languageFilter);
                if (!primary && !additional) {
                    continue;
                }
            }
            ObjectNode node = voices.addObject();
            node.put("Gender", voice.gender);
            node.put("Id", voice.id);
            node.put("LanguageCode", voice.languageCode);
            node.put("LanguageName", voice.languageName);
            node.put("Name", voice.id);
            ArrayNode engines = node.putArray("SupportedEngines");
            for (String supported : voice.supportedEngines) {
                engines.add(supported);
            }
        }
        return response;
    }

    public SynthesisResult synthesizeSpeech(JsonNode request) {
        String text = requireText(request, "Text");
        String outputFormat = requireText(request, "OutputFormat");
        requireText(request, "VoiceId");
        validateEngine(optionalText(request, "Engine"));
        validateOutputFormat(outputFormat);
        requireLexicons(regionResolver.getRegion(), request);
        byte[] audio = dummyAudio(outputFormat);
        return new SynthesisResult(audio, contentType(outputFormat), text.length());
    }

    public ObjectNode startSpeechSynthesisTask(String region, JsonNode request) {
        String text = requireText(request, "Text");
        String outputFormat = requireText(request, "OutputFormat");
        String voiceId = requireText(request, "VoiceId");
        String bucket = requireText(request, "OutputS3BucketName");
        validateEngine(optionalText(request, "Engine"));
        validateOutputFormat(outputFormat);
        requireLexicons(region, request);

        String prefix = optionalText(request, "OutputS3KeyPrefix");
        if (prefix == null) {
            prefix = "";
        }
        String taskId = UUID.randomUUID().toString();
        String key = prefix + taskId + "." + fileExtension(outputFormat);
        byte[] audio = dummyAudio(outputFormat);
        try {
            s3Service.putObject(bucket, key, audio, contentType(outputFormat), Map.of());
        } catch (AwsException e) {
            if ("NoSuchBucket".equals(e.getErrorCode())) {
                throw new AwsException("InvalidS3BucketException",
                        "The specified Amazon S3 bucket does not exist.", 400);
            }
            throw e;
        }

        SynthesisTaskEntry task = new SynthesisTaskEntry();
        task.setTaskId(taskId);
        task.setEngine(optionalText(request, "Engine"));
        task.setTaskStatus("completed");
        task.setOutputUri("https://s3." + region + ".amazonaws.com/" + bucket + "/" + key);
        task.setOutputFormat(outputFormat);
        task.setSampleRate(optionalText(request, "SampleRate"));
        String textType = optionalText(request, "TextType");
        task.setTextType(textType == null ? "text" : textType);
        task.setVoiceId(voiceId);
        task.setLanguageCode(optionalText(request, "LanguageCode"));
        task.setSnsTopicArn(optionalText(request, "SnsTopicArn"));
        task.setLexiconNames(stringList(request.get("LexiconNames")));
        task.setSpeechMarkTypes(stringList(request.get("SpeechMarkTypes")));
        task.setRequestCharacters(text.length());
        task.setCreationTime(Instant.now().getEpochSecond());
        tasks.put(taskId, task);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("SynthesisTask", toTask(task));
        return response;
    }

    public ObjectNode getSpeechSynthesisTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new AwsException("InvalidTaskIdException", "The specified task id is not valid.", 400);
        }
        SynthesisTaskEntry task = tasks.get(taskId);
        if (task == null) {
            throw new AwsException("SynthesisTaskNotFoundException",
                    "The Speech Synthesis task with id " + taskId + " was not found.", 400);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SynthesisTask", toTask(task));
        return response;
    }

    public ObjectNode listSpeechSynthesisTasks(String status, Integer maxResults, String nextToken) {
        List<SynthesisTaskEntry> filtered = tasks.values().stream()
                .filter(task -> status == null || status.equals(task.getTaskStatus()))
                .sorted(Comparator.comparing(SynthesisTaskEntry::getCreationTime).reversed())
                .toList();
        int start = parseOffset(nextToken);
        int limit = maxResults == null ? filtered.size() : Math.min(Math.max(maxResults, 1), 100);
        int end = Math.min(start + limit, filtered.size());
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("SynthesisTasks");
        for (int i = start; i < end; i++) {
            list.add(toTask(filtered.get(i)));
        }
        if (end < filtered.size()) {
            response.put("NextToken", String.valueOf(end));
        }
        return response;
    }

    public record SynthesisResult(byte[] audio, String contentType, int requestCharacters) {}

    private ObjectNode toAttributes(Lexicon entry) {
        ObjectNode attributes = objectMapper.createObjectNode();
        if (entry.getAlphabet() != null) {
            attributes.put("Alphabet", entry.getAlphabet());
        }
        if (entry.getLanguageCode() != null) {
            attributes.put("LanguageCode", entry.getLanguageCode());
        }
        attributes.put("LastModified", entry.getLastModified());
        attributes.put("LexiconArn", entry.getLexiconArn());
        attributes.put("LexemesCount", entry.getLexemesCount());
        attributes.put("Size", entry.getSize());
        return attributes;
    }

    private ObjectNode toTask(SynthesisTaskEntry task) {
        ObjectNode node = objectMapper.createObjectNode();
        putOptional(node, "Engine", task.getEngine());
        node.put("TaskId", task.getTaskId());
        node.put("TaskStatus", task.getTaskStatus());
        putOptional(node, "TaskStatusReason", task.getTaskStatusReason());
        putOptional(node, "OutputUri", task.getOutputUri());
        node.put("CreationTime", task.getCreationTime());
        node.put("RequestCharacters", task.getRequestCharacters());
        putOptional(node, "SnsTopicArn", task.getSnsTopicArn());
        putOptional(node, "OutputFormat", task.getOutputFormat());
        putOptional(node, "SampleRate", task.getSampleRate());
        putOptional(node, "TextType", task.getTextType());
        putOptional(node, "VoiceId", task.getVoiceId());
        putOptional(node, "LanguageCode", task.getLanguageCode());
        if (!task.getLexiconNames().isEmpty()) {
            ArrayNode names = node.putArray("LexiconNames");
            task.getLexiconNames().forEach(names::add);
        }
        if (!task.getSpeechMarkTypes().isEmpty()) {
            ArrayNode marks = node.putArray("SpeechMarkTypes");
            task.getSpeechMarkTypes().forEach(marks::add);
        }
        return node;
    }

    private Lexicon requireLexicon(String region, String name) {
        requireName(name);
        Lexicon entry = lexicons.get(lexiconKey(region, name)).orElse(null);
        if (entry == null) {
            throw new AwsException("LexiconNotFoundException",
                    "The lexicon " + name + " was not found.", 404);
        }
        return entry;
    }

    private void requireLexicons(String region, JsonNode request) {
        for (String name : stringList(request.get("LexiconNames"))) {
            requireLexicon(region, name);
        }
    }

    private void requireName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("InvalidLexiconException",
                    "The specified lexicon name is not valid.", 400);
        }
    }

    private void validateEngine(String engine) {
        if (engine != null && !ENGINES.contains(engine)) {
            throw new AwsException("EngineNotSupportedException",
                    "The specified engine is not supported.", 400);
        }
    }

    private void validateOutputFormat(String outputFormat) {
        if (!OUTPUT_FORMATS.contains(outputFormat)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + outputFormat
                            + "' at 'outputFormat' failed to satisfy constraint: Member must satisfy enum value set",
                    400);
        }
    }

    private static String lexiconKey(String region, String name) {
        return region + "/" + name;
    }

    private static String requireText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at '" + field
                            + "' failed to satisfy constraint: Member must not be null",
                    400);
        }
        return value;
    }

    private static String optionalText(JsonNode request, String field) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return null;
        }
        String value = request.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.isNull() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static void putOptional(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static String attribute(String xml, String name) {
        Matcher matcher = ATTRIBUTE.matcher(xml);
        while (matcher.find()) {
            if (name.equals(matcher.group(1))) {
                return matcher.group(2);
            }
        }
        return null;
    }

    private static int countTag(String xml, String tag) {
        Pattern pattern = Pattern.compile("<" + tag + "\\b", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(xml);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int parseOffset(String nextToken) {
        if (nextToken == null || nextToken.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(nextToken);
            if (offset < 0) {
                throw new NumberFormatException(nextToken);
            }
            return offset;
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidNextTokenException", "The specified next token is not valid.", 400);
        }
    }

    static byte[] dummyAudio(String outputFormat) {
        if ("json".equals(outputFormat)) {
            return "[]".getBytes(StandardCharsets.UTF_8);
        }
        byte[] audio = new byte[AUDIO_BYTES];
        // MPEG-1 Layer III frame header (128 kbps, 44.1 kHz).
        audio[0] = (byte) 0xFF;
        audio[1] = (byte) 0xFB;
        audio[2] = (byte) 0x90;
        audio[3] = 0x00;
        Arrays.fill(audio, 4, audio.length, (byte) 0x00);
        return audio;
    }

    static String contentType(String outputFormat) {
        return switch (outputFormat) {
            case "ogg_vorbis", "ogg_opus" -> "audio/ogg";
            case "pcm" -> "audio/pcm";
            case "json" -> "application/x-json-stream";
            case "mulaw", "alaw" -> "audio/basic";
            default -> "audio/mpeg";
        };
    }

    static String fileExtension(String outputFormat) {
        return switch (outputFormat) {
            case "ogg_vorbis", "ogg_opus" -> "ogg";
            case "pcm" -> "pcm";
            case "json" -> "marks";
            case "mulaw" -> "ulaw";
            case "alaw" -> "alaw";
            default -> "mp3";
        };
    }

    private static Voice voice(String id, String gender, String languageCode, String languageName,
                               String... engines) {
        return new Voice(id, gender, languageCode, languageName, List.of(engines), List.of());
    }

    private record Voice(
            String id,
            String gender,
            String languageCode,
            String languageName,
            List<String> supportedEngines,
            List<String> additionalLanguageCodes) {}
}
