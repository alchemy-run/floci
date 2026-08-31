package io.github.hectorvent.floci.services.comprehendmedical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.comprehendmedical.model.ComprehendMedicalJob;
import io.github.hectorvent.floci.services.comprehendmedical.model.ComprehendMedicalJob.Family;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based stub for Amazon Comprehend Medical. Sync Detect and Infer calls scan
 * a small clinical lexicon so Alchemy fixture texts produce AWS-shaped entities;
 * async Start/Describe/List/Stop jobs are stored in memory.
 *
 * @see <a href="https://docs.aws.amazon.com/comprehend-medical/latest/api/API_Operations.html">Comprehend Medical API</a>
 */
@ApplicationScoped
public class ComprehendMedicalService implements Resettable {

    static final String MODEL_VERSION = "3.0.0.20241001";
    static final int TEXT_LIMIT = 20_000;

    private static final Pattern ISO_DATE = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern AGE = Pattern.compile("(?i)\\bage\\s+(\\d{1,3})\\b");
    private static final Pattern PROPER_NAME = Pattern.compile("\\b[A-Z][a-z]+\\s+[A-Z][a-z]+\\b");
    private static final Pattern ROLE_ARN = Pattern.compile(
            "^arn:aws(?:-us-gov|-cn)?:iam::(\\d{12}):role/(.+)$");

    private final ObjectMapper objectMapper;
    private final IamService iamService;
    private final ConcurrentHashMap<String, ComprehendMedicalJob> jobs = new ConcurrentHashMap<>();

    @Inject
    public ComprehendMedicalService(ObjectMapper objectMapper, IamService iamService) {
        this.objectMapper = objectMapper;
        this.iamService = iamService;
    }

    @Override
    public void clear() {
        jobs.clear();
    }

    public Response detectEntitiesV2(JsonNode request) {
        String text = requireText(request);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("Entities", entitiesV2(text));
        root.put("ModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }

    public Response detectPhi(JsonNode request) {
        String text = requireText(request);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("Entities", phiEntities(text));
        root.put("ModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }

    public Response inferIcd10cm(JsonNode request) {
        String text = requireText(request);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode entities = root.putArray("Entities");
        int id = 0;
        for (LexemeMatch match : findLexemes(text)) {
            if (match.lexeme.icd10 == null) {
                continue;
            }
            ObjectNode entity = entities.addObject();
            putOffsets(entity, id++, match);
            entity.put("Category", "MEDICAL_CONDITION");
            entity.put("Type", "DX_NAME");
            ArrayNode codes = entity.putArray("ICD10CMConcepts");
            ObjectNode code = codes.addObject();
            code.put("Code", match.lexeme.icd10);
            code.put("Description", match.lexeme.icd10Description);
            code.put("Score", 0.99);
        }
        root.put("ModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }

    public Response inferRxNorm(JsonNode request) {
        String text = requireText(request);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode entities = root.putArray("Entities");
        int id = 0;
        for (LexemeMatch match : findLexemes(text)) {
            if (match.lexeme.rxNorm == null) {
                continue;
            }
            ObjectNode entity = entities.addObject();
            putOffsets(entity, id++, match);
            entity.put("Category", "MEDICATION");
            entity.put("Type", "GENERIC_NAME");
            ArrayNode concepts = entity.putArray("RxNormConcepts");
            ObjectNode concept = concepts.addObject();
            concept.put("Code", match.lexeme.rxNorm);
            concept.put("Description", match.lexeme.rxNormDescription);
            concept.put("Score", 0.99);
        }
        root.put("ModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }

    public Response inferSnomedCt(JsonNode request) {
        String text = requireText(request);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode entities = root.putArray("Entities");
        int id = 0;
        for (LexemeMatch match : findLexemes(text)) {
            if (match.lexeme.snomed == null) {
                continue;
            }
            ObjectNode entity = entities.addObject();
            putOffsets(entity, id++, match);
            entity.put("Category", "MEDICAL_CONDITION");
            entity.put("Type", "DX_NAME");
            ArrayNode concepts = entity.putArray("SNOMEDCTConcepts");
            ObjectNode concept = concepts.addObject();
            concept.put("Code", match.lexeme.snomed);
            concept.put("Description", match.lexeme.snomedDescription);
            concept.put("Score", 0.99);
        }
        root.put("ModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }

    public Response startJob(Family family, JsonNode request) {
        JsonNode input = request.path("InputDataConfig");
        JsonNode output = request.path("OutputDataConfig");
        String inputBucket = textOrNull(input, "S3Bucket");
        String outputBucket = textOrNull(output, "S3Bucket");
        if (inputBucket == null || inputBucket.isBlank()) {
            throw new AwsException("InvalidRequestException", "InputDataConfig.S3Bucket is required.", 400);
        }
        if (outputBucket == null || outputBucket.isBlank()) {
            throw new AwsException("InvalidRequestException", "OutputDataConfig.S3Bucket is required.", 400);
        }
        String languageCode = textOrNull(request, "LanguageCode");
        if (languageCode == null || languageCode.isBlank()) {
            throw new AwsException("InvalidRequestException", "LanguageCode is required.", 400);
        }
        if (!"en".equals(languageCode)) {
            throw new AwsException("InvalidRequestException",
                    "LanguageCode " + languageCode + " is not supported.", 400);
        }
        String roleArn = requireDataAccessRole(textOrNull(request, "DataAccessRoleArn"));
        String jobId = newJobId();
        long now = Instant.now().getEpochSecond();
        ComprehendMedicalJob job = new ComprehendMedicalJob(
                jobId,
                family,
                textOrNull(request, "JobName"),
                "SUBMITTED",
                now,
                null,
                inputBucket,
                textOrNull(input, "S3Key"),
                outputBucket,
                textOrNull(output, "S3Key"),
                languageCode,
                roleArn);
        jobs.put(jobId, job);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", jobId);
        return Response.ok(root).build();
    }

    public Response describeJob(Family family, JsonNode request) {
        ComprehendMedicalJob job = requireJob(textOrNull(request, "JobId"), family);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("ComprehendMedicalAsyncJobProperties", jobNode(job));
        return Response.ok(root).build();
    }

    public Response listJobs(Family family, JsonNode request) {
        String jobName = request.path("Filter").path("JobName").asText(null);
        String jobStatus = request.path("Filter").path("JobStatus").asText(null);
        ArrayNode list = objectMapper.createArrayNode();
        jobs.values().stream()
                .filter(job -> job.family() == family)
                .filter(job -> jobName == null || jobName.isBlank() || jobName.equals(job.jobName()))
                .filter(job -> jobStatus == null || jobStatus.isBlank() || jobStatus.equals(job.jobStatus()))
                .sorted(Comparator.comparingLong(ComprehendMedicalJob::submitTime).reversed())
                .forEach(job -> list.add(jobNode(job)));
        ObjectNode root = objectMapper.createObjectNode();
        root.set("ComprehendMedicalAsyncJobPropertiesList", list);
        return Response.ok(root).build();
    }

    public Response stopJob(Family family, JsonNode request) {
        ComprehendMedicalJob job = requireJob(textOrNull(request, "JobId"), family);
        if ("SUBMITTED".equals(job.jobStatus()) || "IN_PROGRESS".equals(job.jobStatus())) {
            job = job.withStatus("STOPPED", Instant.now().getEpochSecond());
            jobs.put(job.jobId(), job);
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", job.jobId());
        return Response.ok(root).build();
    }

    private ArrayNode entitiesV2(String text) {
        ArrayNode entities = objectMapper.createArrayNode();
        int id = 0;
        for (LexemeMatch match : findLexemes(text)) {
            ObjectNode entity = entities.addObject();
            putOffsets(entity, id++, match);
            entity.put("Category", match.lexeme.category);
            entity.put("Type", match.lexeme.type);
        }
        return entities;
    }

    private ArrayNode phiEntities(String text) {
        ArrayNode entities = objectMapper.createArrayNode();
        boolean[] used = new boolean[text.length()];
        int id = 0;
        id = addPhiPattern(entities, text, used, id, ISO_DATE, "DATE", 0);
        Matcher age = AGE.matcher(text);
        while (age.find()) {
            int begin = age.start(1);
            int end = age.end(1);
            if (!rangeUsed(used, begin, end)) {
                mark(used, age.start(), end);
                entities.add(phiEntity(id++, begin, end, text.substring(begin, end), "AGE"));
            }
        }
        Matcher names = PROPER_NAME.matcher(text);
        while (names.find()) {
            int begin = names.start();
            int end = names.end();
            if (rangeUsed(used, begin, end)) {
                continue;
            }
            String span = text.substring(begin, end);
            String type = isFacility(span) ? "ADDRESS" : "NAME";
            mark(used, begin, end);
            entities.add(phiEntity(id++, begin, end, span, type));
        }
        return entities;
    }

    private int addPhiPattern(ArrayNode entities, String text, boolean[] used, int id,
                              Pattern pattern, String type, int group) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int begin = group == 0 ? matcher.start() : matcher.start(group);
            int end = group == 0 ? matcher.end() : matcher.end(group);
            if (!rangeUsed(used, begin, end)) {
                mark(used, begin, end);
                entities.add(phiEntity(id++, begin, end, text.substring(begin, end), type));
            }
        }
        return id;
    }

    private ObjectNode phiEntity(int id, int begin, int end, String span, String type) {
        ObjectNode entity = objectMapper.createObjectNode();
        entity.put("Id", id);
        entity.put("BeginOffset", begin);
        entity.put("EndOffset", end);
        entity.put("Score", 0.99);
        entity.put("Text", span);
        entity.put("Category", "PROTECTED_HEALTH_INFORMATION");
        entity.put("Type", type);
        return entity;
    }

    private static boolean isFacility(String span) {
        String lower = span.toLowerCase(Locale.ROOT);
        return lower.contains("clinic") || lower.contains("hospital") || lower.contains("center");
    }

    private void putOffsets(ObjectNode entity, int id, LexemeMatch match) {
        entity.put("Id", id);
        entity.put("BeginOffset", match.begin);
        entity.put("EndOffset", match.end);
        entity.put("Score", 0.99);
        entity.put("Text", match.text);
    }

    private ObjectNode jobNode(ComprehendMedicalJob job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("JobId", job.jobId());
        if (job.jobName() != null) {
            node.put("JobName", job.jobName());
        }
        node.put("JobStatus", job.jobStatus());
        node.put("SubmitTime", job.submitTime());
        if (job.endTime() != null) {
            node.put("EndTime", job.endTime());
        }
        ObjectNode input = node.putObject("InputDataConfig");
        input.put("S3Bucket", job.inputBucket());
        if (job.inputKey() != null) {
            input.put("S3Key", job.inputKey());
        }
        ObjectNode output = node.putObject("OutputDataConfig");
        output.put("S3Bucket", job.outputBucket());
        if (job.outputKey() != null) {
            output.put("S3Key", job.outputKey());
        }
        node.put("LanguageCode", job.languageCode());
        node.put("DataAccessRoleArn", job.dataAccessRoleArn());
        node.put("ModelVersion", MODEL_VERSION);
        return node;
    }

    private ComprehendMedicalJob requireJob(String jobId, Family family) {
        if (jobId == null || jobId.isBlank()) {
            throw new AwsException("InvalidRequestException", "JobId is required.", 400);
        }
        ComprehendMedicalJob job = jobs.get(jobId);
        if (job == null || job.family() != family) {
            throw new AwsException("ResourceNotFoundException", "The specified job was not found.", 404);
        }
        return job;
    }

    private String requireDataAccessRole(String roleArn) {
        if (roleArn == null || roleArn.isBlank()) {
            throw new AwsException("InvalidRequestException", "DataAccessRoleArn is required.", 400);
        }
        Matcher matcher = ROLE_ARN.matcher(roleArn);
        if (!matcher.matches()) {
            throw invalidDataAccessRole();
        }
        String accountId = matcher.group(1);
        String resource = matcher.group(2);
        String roleName = resource.substring(resource.lastIndexOf('/') + 1);
        Optional<?> role = iamService.findRole(accountId, roleName);
        if (role.isEmpty()) {
            throw invalidDataAccessRole();
        }
        return roleArn;
    }

    private static AwsException invalidDataAccessRole() {
        return new AwsException("InvalidRequestException",
                "The DataAccessRoleArn that you supplied is not valid. Reason: DATA_ACCESS_ROLE_ARN_INVALID",
                400);
    }

    private String requireText(JsonNode request) {
        String text = textOrNull(request, "Text");
        if (text == null || text.isBlank()) {
            throw new AwsException("InvalidRequestException", "Text is required.", 400);
        }
        if (text.length() > TEXT_LIMIT) {
            throw new AwsException("TextSizeLimitExceededException",
                    "The text size exceeds the limit of " + TEXT_LIMIT + " characters.", 400);
        }
        return text;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : null;
    }

    private static String newJobId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }

    private static List<LexemeMatch> findLexemes(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean[] used = new boolean[text.length()];
        List<Lexeme> sorted = new ArrayList<>(LEXICON);
        sorted.sort((a, b) -> Integer.compare(b.phrase.length(), a.phrase.length()));
        List<LexemeMatch> matches = new ArrayList<>();
        for (Lexeme lexeme : sorted) {
            String needle = lexeme.phrase.toLowerCase(Locale.ROOT);
            int from = 0;
            while (from <= lower.length() - needle.length()) {
                int idx = lower.indexOf(needle, from);
                if (idx < 0) {
                    break;
                }
                if (isWordBounded(lower, idx, needle.length()) && !rangeUsed(used, idx, idx + needle.length())) {
                    mark(used, idx, idx + needle.length());
                    matches.add(new LexemeMatch(idx, idx + needle.length(),
                            text.substring(idx, idx + needle.length()), lexeme));
                }
                from = idx + 1;
            }
        }
        matches.sort(Comparator.comparingInt(m -> m.begin));
        return matches;
    }

    private static boolean isWordBounded(String lower, int start, int length) {
        if (start > 0 && Character.isLetterOrDigit(lower.charAt(start - 1))) {
            return false;
        }
        int end = start + length;
        return end >= lower.length() || !Character.isLetterOrDigit(lower.charAt(end));
    }

    private static boolean rangeUsed(boolean[] used, int begin, int end) {
        for (int i = begin; i < end && i < used.length; i++) {
            if (used[i]) {
                return true;
            }
        }
        return false;
    }

    private static void mark(boolean[] used, int begin, int end) {
        for (int i = begin; i < end && i < used.length; i++) {
            used[i] = true;
        }
    }

    private static final List<Lexeme> LEXICON = List.of(
            new Lexeme("type 2 diabetes", "MEDICAL_CONDITION", "DX_NAME",
                    "E11.9", "Type 2 diabetes mellitus without complications",
                    null, null,
                    "44054006", "Type 2 diabetes mellitus"),
            new Lexeme("diabetes", "MEDICAL_CONDITION", "DX_NAME",
                    "E11.9", "Type 2 diabetes mellitus without complications",
                    null, null,
                    "73211009", "Diabetes mellitus"),
            new Lexeme("hypertension", "MEDICAL_CONDITION", "DX_NAME",
                    "I10", "Essential (primary) hypertension",
                    null, null,
                    "38341003", "Hypertensive disorder"),
            new Lexeme("atenolol", "MEDICATION", "GENERIC_NAME",
                    null, null,
                    "1202", "Atenolol",
                    null, null),
            new Lexeme("metformin", "MEDICATION", "GENERIC_NAME",
                    null, null,
                    "6809", "Metformin",
                    null, null)
    );

    private record Lexeme(
            String phrase,
            String category,
            String type,
            String icd10,
            String icd10Description,
            String rxNorm,
            String rxNormDescription,
            String snomed,
            String snomedDescription
    ) {
    }

    private record LexemeMatch(int begin, int end, String text, Lexeme lexeme) {
    }
}
