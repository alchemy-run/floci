package io.floci.aws.services.comprehendmedical;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AiMockConfigLoader;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.comprehendmedical.ComprehendMedicalAnalyzer.Concept;
import io.floci.aws.services.comprehendmedical.ComprehendMedicalAnalyzer.Entity;
import io.floci.aws.services.comprehendmedical.ComprehendMedicalAnalyzer.Trait;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Amazon Comprehend Medical: the real-time analyses ({@code DetectEntitiesV2}, {@code DetectPHI},
 * {@code InferICD10CM}, {@code InferRxNorm}, {@code InferSNOMEDCT}) read the text with
 * {@link ComprehendMedicalAnalyzer}, and the five batch job families analyze every file under the
 * input S3 prefix the same way, writing one {@code <file>.out} per input and a {@code Manifest}
 * under {@code <OutputDataConfig>/<account>-<TYPE>-<JobId>/}. A caller-configured response from
 * {@link AiMockConfigLoader} (key: the text) replaces the analysis.
 */
@ApplicationScoped
public class ComprehendMedicalService {
    static final String SERVICE_KEY = "comprehendmedical";
    static final String MODEL_VERSION = "2.6.0";
    static final long SUBMITTED_MILLIS = 2_000;
    static final long COMPLETED_MILLIS = 4_000;
    private static final int MAX_TEXT_BYTES = 20_000;
    private static final int MAX_SNOMED_CHARS = 10_000;
    private static final Set<String> FINISHED = Set.of("COMPLETED", "PARTIAL_SUCCESS", "FAILED", "STOPPED");

    /** One batch job family: its action noun and the label in its output directory. */
    enum Family {
        ENTITIES_V2("EntitiesDetectionV2Job", "EntitiesDetection"),
        ICD10CM("ICD10CMInferenceJob", "ICD10CMInference"),
        PHI("PHIDetectionJob", "PHIDetection"),
        RXNORM("RxNormInferenceJob", "RxNormInference"),
        SNOMEDCT("SNOMEDCTInferenceJob", "SNOMEDCTInference");

        final String noun;
        final String outputType;

        Family(String noun, String outputType) {
            this.noun = noun;
            this.outputType = outputType;
        }

        static Family of(String action) {
            String noun = action.replaceFirst("^(Start|Stop|Describe|List)", "").replaceFirst("s$", "");
            for (Family family : values()) {
                if (family.noun.equals(noun)) {
                    return family;
                }
            }
            return null;
        }
    }

    private final StorageBackend<String, ObjectNode> jobs;
    private final RegionResolver regionResolver;
    private final S3Service s3Service;
    private final IamService iamService;
    private final AiMockConfigLoader mocks;
    private final ObjectMapper mapper;

    @Inject
    public ComprehendMedicalService(StorageFactory storageFactory, RegionResolver regionResolver, S3Service s3Service,
                                    IamService iamService, AiMockConfigLoader mocks, ObjectMapper mapper) {
        this.jobs = storageFactory.create("comprehendmedical", "comprehendmedical-jobs.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regionResolver = regionResolver;
        this.s3Service = s3Service;
        this.iamService = iamService;
        this.mocks = mocks;
        this.mapper = mapper;
    }

    // ── Real-time analysis ─────────────────────────────────────────────────

    public ObjectNode analyze(String action, JsonNode request) {
        String text = request.path("Text").isTextual() ? request.get("Text").asText() : null;
        if (text == null || text.isEmpty()) {
            throw invalid("Text is a required field.");
        }
        if ("InferSNOMEDCT".equals(action) ? text.length() > MAX_SNOMED_CHARS
                : text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new AwsException("TextSizeLimitExceededException", "The size of the text you submitted exceeds the"
                    + " size limit. Reduce the size of the text or use a smaller document and then retry your request.",
                    400);
        }
        return mocks.lookup(SERVICE_KEY, text, action).filter(JsonNode::isObject).map(node -> (ObjectNode) node)
                .orElseGet(() -> result(action, text));
    }

    private ObjectNode result(String action, String text) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("Entities");
        List<Entity> all = ComprehendMedicalAnalyzer.entities(text);
        switch (action) {
            case "DetectEntities", "DetectEntitiesV2" -> {
                all.forEach(entity -> list.add(entityNode(entity)));
                response.putArray("UnmappedAttributes");
            }
            case "DetectPHI" -> {
                List<Entity> phi = ComprehendMedicalAnalyzer.phi(text);
                for (int i = 0; i < phi.size(); i++) {
                    Entity entity = phi.get(i);
                    list.add(entityNode(new Entity(i, entity.text(), entity.begin(), entity.end(), entity.category(),
                            entity.type(), entity.score(), entity.traits(), entity.attributes(), null, null)));
                }
            }
            case "InferICD10CM" -> all.stream().filter(entity -> entity.term() != null && entity.term().icd10() != null)
                    .forEach(entity -> list.add(withConcepts(entity, "ICD10CMConcepts", entity.term().icd10())));
            case "InferRxNorm" -> all.stream().filter(entity -> entity.term() != null && entity.term().rxnorm() != null)
                    .forEach(entity -> list.add(withConcepts(entity, "RxNormConcepts", entity.term().rxnorm())));
            case "InferSNOMEDCT" -> {
                all.stream().filter(entity -> entity.term() != null && entity.term().snomed() != null)
                        .forEach(entity -> list.add(withConcepts(entity, "SNOMEDCTConcepts", entity.term().snomed())));
                response.putObject("SNOMEDCTDetails").put("Edition", "US").put("Language", "en")
                        .put("VersionDate", "20200901");
                response.putObject("Characters").put("OriginalTextCharacters", text.length());
            }
            default -> throw new IllegalArgumentException(action);
        }
        response.put("ModelVersion", MODEL_VERSION);
        return response;
    }

    private ObjectNode entityNode(Entity entity) {
        ObjectNode node = mapper.createObjectNode();
        node.put("Id", entity.id());
        node.put("BeginOffset", entity.begin());
        node.put("EndOffset", entity.end());
        node.put("Score", entity.score());
        node.put("Text", entity.text());
        node.put("Category", entity.category());
        node.put("Type", entity.type());
        traits(node.putArray("Traits"), entity.traits());
        if (!entity.attributes().isEmpty()) {
            ArrayNode attributes = node.putArray("Attributes");
            for (Entity attribute : entity.attributes()) {
                ObjectNode item = attributes.addObject();
                item.put("Type", attribute.type());
                item.put("Score", attribute.score());
                item.put("RelationshipScore", 0.99);
                item.put("RelationshipType", attribute.relationshipType());
                item.put("Id", attribute.id());
                item.put("BeginOffset", attribute.begin());
                item.put("EndOffset", attribute.end());
                item.put("Text", attribute.text());
                item.put("Category", attribute.category());
                item.putArray("Traits");
            }
        }
        return node;
    }

    private ObjectNode withConcepts(Entity entity, String key, Concept concept) {
        ObjectNode node = entityNode(entity);
        node.putArray(key).addObject().put("Description", concept.description()).put("Code", concept.code())
                .put("Score", 0.9);
        return node;
    }

    private static void traits(ArrayNode into, List<Trait> traits) {
        traits.forEach(trait -> into.addObject().put("Name", trait.name()).put("Score", trait.score()));
    }

    // ── Batch jobs ─────────────────────────────────────────────────────────

    public synchronized ObjectNode start(Family family, JsonNode request, String region) {
        JsonNode input = request.path("InputDataConfig");
        JsonNode output = request.path("OutputDataConfig");
        String inputBucket = requireText(input, "S3Bucket", "InputDataConfig.S3Bucket");
        String outputBucket = requireText(output, "S3Bucket", "OutputDataConfig.S3Bucket");
        String role = requireText(request, "DataAccessRoleArn", "DataAccessRoleArn");
        String language = requireText(request, "LanguageCode", "LanguageCode");
        if (!"en".equals(language)) {
            throw invalid("The language code " + language + " is not supported; Comprehend Medical supports en.");
        }
        if (!roleExists(role)) {
            throw invalid("DATA_ACCESS_ROLE_ARN_INVALID: The data access role " + role
                    + " can't be assumed by Comprehend Medical.");
        }
        String token = request.path("ClientRequestToken").asText(null);
        if (token != null) {
            for (ObjectNode existing : jobs.scan(key -> true)) {
                if (token.equals(existing.path("clientToken").asText(null))
                        && family.name().equals(existing.path("family").asText())) {
                    return mapper.createObjectNode().put("JobId", existing.path("JobId").asText());
                }
            }
        }
        String jobId = UUID.randomUUID().toString().replace("-", "");
        String inputKey = input.path("S3Key").asText("");
        String outputKey = output.path("S3Key").asText("");
        String prefix = (outputKey.isEmpty() || outputKey.endsWith("/") ? outputKey : outputKey + "/")
                + regionResolver.getAccountId() + "-" + family.outputType + "-" + jobId + "/";
        ObjectNode job = mapper.createObjectNode();
        job.put("JobId", jobId);
        if (request.hasNonNull("JobName")) {
            job.put("JobName", request.get("JobName").asText());
        }
        job.put("JobStatus", "SUBMITTED");
        job.put("SubmitTime", now());
        job.put("ExpirationTime", now() + 90L * 24 * 3600);
        job.set("InputDataConfig", input.deepCopy());
        job.set("OutputDataConfig", mapper.createObjectNode().put("S3Bucket", outputBucket).put("S3Key", prefix));
        job.put("LanguageCode", language);
        job.put("DataAccessRoleArn", role);
        job.put("ManifestFilePath", outputBucket + "/" + prefix + "Manifest");
        if (request.hasNonNull("KMSKey")) {
            job.put("KMSKey", request.get("KMSKey").asText());
        }
        job.put("ModelVersion", MODEL_VERSION);
        job.put("family", family.name());
        job.put("region", region);
        if (token != null) {
            job.put("clientToken", token);
        }
        try {
            run(family, inputBucket, inputKey, outputBucket, prefix);
        } catch (AwsException e) {
            job.put("failure", e.getMessage());
        }
        jobs.put(jobId, job);
        return mapper.createObjectNode().put("JobId", jobId);
    }

    private boolean roleExists(String roleArn) {
        if (!AwsArnUtils.isArnFor(roleArn, "iam")) {
            return false;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(roleArn);
        if (!arn.resource().startsWith("role/")) {
            return false;
        }
        String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        return iamService.findRole(arn.accountId(), name)
                .filter(role -> IamService.roleArnMatches(roleArn, role)).isPresent();
    }

    private void run(Family family, String inputBucket, String inputKey, String outputBucket, String prefix) {
        List<S3Object> objects;
        try {
            objects = s3Service.listObjects(inputBucket, inputKey, null, 1000);
        } catch (AwsException e) {
            throw invalid("Unable to read the input s3://" + inputBucket + "/" + inputKey + ": " + e.getMessage());
        }
        StringBuilder manifest = new StringBuilder();
        int analyzed = 0;
        for (S3Object object : objects) {
            if (object.getKey().endsWith("/")) {
                continue;
            }
            String file = object.getKey().substring(object.getKey().lastIndexOf('/') + 1);
            String text = new String(s3Service.getObject(inputBucket, object.getKey()).getData(),
                    StandardCharsets.UTF_8);
            ObjectNode result = text.isBlank() ? mapper.createObjectNode().put("ModelVersion", MODEL_VERSION)
                    : result(action(family), text);
            s3Service.putObject(outputBucket, prefix + file + ".out",
                    result.toString().getBytes(StandardCharsets.UTF_8), "application/json", Map.of());
            manifest.append(object.getKey()).append(" -> ").append(prefix).append(file).append(".out\n");
            analyzed++;
        }
        if (analyzed == 0) {
            throw invalid("No input files were found under s3://" + inputBucket + "/" + inputKey);
        }
        s3Service.putObject(outputBucket, prefix + "Manifest", manifest.toString().getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());
    }

    private static String action(Family family) {
        return switch (family) {
            case ENTITIES_V2 -> "DetectEntitiesV2";
            case ICD10CM -> "InferICD10CM";
            case PHI -> "DetectPHI";
            case RXNORM -> "InferRxNorm";
            case SNOMEDCT -> "InferSNOMEDCT";
        };
    }

    public ObjectNode describe(Family family, JsonNode request) {
        ObjectNode response = mapper.createObjectNode();
        response.set("ComprehendMedicalAsyncJobProperties", view(require(family, request)));
        return response;
    }

    public ObjectNode list(Family family, JsonNode request, String region) {
        JsonNode filter = request.path("Filter");
        String name = filter.path("JobName").asText(null);
        String status = filter.path("JobStatus").asText(null);
        double before = filter.path("SubmitTimeBefore").asDouble(Double.MAX_VALUE);
        double after = filter.path("SubmitTimeAfter").asDouble(0);
        int max = request.path("MaxResults").asInt(100);
        if (max < 1 || max > 500) {
            throw new AwsException("ValidationException", "MaxResults must be between 1 and 500.", 400);
        }
        int offset = 0;
        String token = request.path("NextToken").asText(null);
        if (token != null) {
            try {
                offset = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw invalid("Invalid NextToken.");
            }
        }
        Predicate<ObjectNode> mine = job -> family.name().equals(job.path("family").asText())
                && region.equals(job.path("region").asText());
        List<ObjectNode> matching = jobs.scan(key -> true).stream().filter(mine).map(this::view)
                .filter(job -> name == null || name.equals(job.path("JobName").asText(null)))
                .filter(job -> status == null || status.equals(job.path("JobStatus").asText()))
                .filter(job -> job.path("SubmitTime").asDouble() < before && job.path("SubmitTime").asDouble() > after)
                .sorted(Comparator.comparingDouble((ObjectNode job) -> job.path("SubmitTime").asDouble()).reversed())
                .toList();
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("ComprehendMedicalAsyncJobPropertiesList");
        matching.stream().skip(offset).limit(max).forEach(list::add);
        if (offset + max < matching.size()) {
            response.put("NextToken", Integer.toString(offset + max));
        }
        return response;
    }

    public synchronized ObjectNode stop(Family family, JsonNode request) {
        ObjectNode job = require(family, request);
        String status = view(job).path("JobStatus").asText();
        if (!FINISHED.contains(status)) {
            job.put("stoppedAt", now());
            jobs.put(job.path("JobId").asText(), job);
        }
        return mapper.createObjectNode().put("JobId", job.path("JobId").asText());
    }

    private ObjectNode view(ObjectNode job) {
        ObjectNode copy = job.deepCopy();
        copy.remove(List.of("family", "region", "failure", "stoppedAt", "clientToken"));
        double submitted = job.path("SubmitTime").asDouble();
        double elapsed = (now() - submitted) * 1000;
        if (job.has("stoppedAt")) {
            copy.put("JobStatus", "STOPPED");
            copy.put("EndTime", job.path("stoppedAt").asDouble());
        } else if (elapsed >= COMPLETED_MILLIS) {
            boolean failed = job.has("failure");
            copy.put("JobStatus", failed ? "FAILED" : "COMPLETED");
            copy.put("EndTime", submitted + COMPLETED_MILLIS / 1000.0);
            if (failed) {
                copy.put("Message", job.path("failure").asText());
            }
        } else if (elapsed >= SUBMITTED_MILLIS) {
            copy.put("JobStatus", "IN_PROGRESS");
        }
        return copy;
    }

    private ObjectNode require(Family family, JsonNode request) {
        String jobId = request.path("JobId").asText(null);
        if (jobId == null || jobId.isEmpty()) {
            throw invalid("JobId is a required field.");
        }
        return jobs.get(jobId).filter(job -> family.name().equals(job.path("family").asText()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The job " + jobId + " was not found.", 400));
    }

    private static String requireText(JsonNode node, String field, String label) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isEmpty()) {
            throw invalid(label + " is a required field.");
        }
        return value.asText();
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
