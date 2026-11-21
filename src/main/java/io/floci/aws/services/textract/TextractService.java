package io.floci.aws.services.textract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AiMockConfigLoader;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsGeometry;
import io.floci.aws.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
/**
 * Response builder for Amazon Textract. Stateless for sync operations; async
 * operations (Start* and Get*) use an in-memory job store. No OCR runs: a call
 * returns a fixed stub Block list matching the AWS Textract wire format, unless
 * {@link AiMockConfigLoader} has an answer for the document.
 * <p>
 * A document's mock is looked up by each of its keys in turn (see
 * {@link TextractDocuments}): its {@code "Bucket/Name"}, then {@code "sha256:<hex>"}
 * of its bytes. Async jobs look their document up when they start, so a job's
 * results follow the same mock (DetectDocumentText for text detection,
 * AnalyzeDocument for analysis, AnalyzeExpense for expense analysis, and
 * DetectDocumentText's blocks for the pages of a lending analysis).
 *
 * @see <a href="https://docs.aws.amazon.com/textract/latest/dg/API_Operations.html">Textract API Reference</a>
 */
@ApplicationScoped
public class TextractService implements Resettable {
    static final String MODEL_VERSION = "1.0";
    private static final String SERVICE_KEY = "textract";
    /** AWS retains asynchronous results for about 7 days; an expired job reads as an invalid job. */
    static final Duration RESULT_RETENTION = Duration.ofDays(7);
    /** Hard cap so a producer that never reads its results cannot grow the store without bound. */
    static final int MAX_RETAINED_JOBS = 10_000;
    private final ObjectMapper objectMapper;
    private final AiMockConfigLoader mockConfigLoader;
    private final Clock clock;
    /** In-memory async job store: jobId to the job's type, creation time, and stable result. */
    private final ConcurrentHashMap<String, AsyncJob> asyncJobs = new ConcurrentHashMap<>();
    @Inject
    public TextractService(ObjectMapper objectMapper, AiMockConfigLoader mockConfigLoader) {
        this(objectMapper, mockConfigLoader, Clock.systemUTC());
    }
    TextractService(ObjectMapper objectMapper, AiMockConfigLoader mockConfigLoader, Clock clock) {
        this.objectMapper = objectMapper;
        this.mockConfigLoader = mockConfigLoader;
        this.clock = clock;
    }
    public void clear() {
        asyncJobs.clear();
    }
    /** A completed async job, retained until {@link #RESULT_RETENTION} elapses. */
    record AsyncJob(String jobType, Instant createdAt, ObjectNode result) {}
    /**
     * DetectDocumentText — returns a stub PAGE + LINE + WORD block hierarchy.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_DetectDocumentText.html
     */
    public Response detectDocumentText(List<String> keys) {
        return Response.ok(textDetection(keys)).build();
    }

    private ObjectNode textDetection(List<String> keys) {
        Optional<JsonNode> mock = mocked(keys, "DetectDocumentText");
        if (mock.isPresent() && mock.get().isObject()) {
            return (ObjectNode) mock.get().deepCopy();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("Blocks", buildStubBlocks());
        root.put("DetectDocumentTextModelVersion", MODEL_VERSION);
        return root;
    }

    /** The document's mock for {@code action} under the first of its keys that has one. */
    private Optional<JsonNode> mocked(List<String> keys, String action) {
        for (String key : keys) {
            Optional<JsonNode> mock = mockConfigLoader.lookup(SERVICE_KEY, key, action);
            if (mock.isPresent()) {
                return mock;
            }
        }
        return Optional.empty();
    }

    /** The document's blocks: its DetectDocumentText mock's, or the stub hierarchy. */
    private ArrayNode blocks(List<String> keys) {
        JsonNode blocks = textDetection(keys).get("Blocks");
        return blocks != null && blocks.isArray() ? (ArrayNode) blocks.deepCopy() : buildStubBlocks();
    }
    /**
     * AnalyzeDocument — returns the same stub blocks; FeatureTypes are accepted but ignored.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_AnalyzeDocument.html
     */
    public Response analyzeDocument(List<String> keys) {
        return Response.ok(documentAnalysis(keys)).build();
    }

    /** AnalyzeDocument: its own mock, else the document's text blocks. */
    private ObjectNode documentAnalysis(List<String> keys) {
        Optional<JsonNode> mock = mocked(keys, "AnalyzeDocument");
        if (mock.isPresent() && mock.get().isObject()) {
            return (ObjectNode) mock.get().deepCopy();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("Blocks", blocks(keys));
        root.put("AnalyzeDocumentModelVersion", MODEL_VERSION);
        return root;
    }

    /**
     * AnalyzeExpense — one expense document per page with no summary fields or line
     * items unless mocked; its blocks are the document's text blocks.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_AnalyzeExpense.html
     */
    public Response analyzeExpense(List<String> keys) {
        return Response.ok(expenseAnalysis(keys)).build();
    }

    private ObjectNode expenseAnalysis(List<String> keys) {
        Optional<JsonNode> mock = mocked(keys, "AnalyzeExpense");
        if (mock.isPresent() && mock.get().isObject()) {
            return (ObjectNode) mock.get().deepCopy();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        ObjectNode document = root.putArray("ExpenseDocuments").addObject();
        document.put("ExpenseIndex", 1);
        document.putArray("SummaryFields");
        document.putArray("LineItemGroups");
        document.set("Blocks", blocks(keys));
        return root;
    }

    /**
     * AnalyzeID — one identity document per page (Textract reads each page as one),
     * with no fields unless the page's AnalyzeID mock gives them.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_AnalyzeID.html
     */
    public Response analyzeID(List<List<String>> pages) {
        if (pages.isEmpty() || pages.size() > 2) {
            throw new AwsException("InvalidParameterException", "DocumentPages must contain 1 or 2 pages.", 400);
        }
        if (pages.size() == 1) {
            Optional<JsonNode> mock = mocked(pages.getFirst(), "AnalyzeID");
            if (mock.isPresent()) {
                return Response.ok(mock.get()).build();
            }
        }
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode documents = root.putArray("IdentityDocuments");
        for (int index = 0; index < pages.size(); index++) {
            ObjectNode document = documents.addObject();
            document.put("DocumentIndex", index + 1);
            document.putArray("IdentityDocumentFields");
            document.set("Blocks", blocks(pages.get(index)));
        }
        root.set("DocumentMetadata", buildDocumentMetadata(pages.size()));
        root.put("AnalyzeIDModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }

    /** StartExpenseAnalysis — completes at once with the document's expense analysis. */
    public Response startExpenseAnalysis(List<String> keys) {
        ObjectNode result = expenseAnalysis(keys);
        result.put("JobStatus", "SUCCEEDED");
        result.put("AnalyzeExpenseModelVersion", MODEL_VERSION);
        return started("EXPENSE_ANALYSIS", result);
    }

    public Response getExpenseAnalysis(String jobId) {
        return Response.ok(requireJobResult(jobId, "EXPENSE_ANALYSIS")).build();
    }

    /**
     * StartLendingAnalysis — completes at once. Each page is classified
     * {@code UNCLASSIFIED} with no extractions, and the summary groups the
     * pages into one {@code UNCLASSIFIED} document.
     */
    public Response startLendingAnalysis(List<String> keys) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("JobStatus", "SUCCEEDED");
        result.set("DocumentMetadata", buildDocumentMetadata(1));
        ObjectNode page = result.putArray("Results").addObject();
        page.put("Page", 1);
        ObjectNode classification = page.putObject("PageClassification");
        classification.putArray("PageType").addObject().put("Value", "UNCLASSIFIED").put("Confidence", 99.0);
        classification.putArray("PageNumber").addObject().put("Value", "1").put("Confidence", 99.0);
        page.putArray("Extractions");
        result.put("AnalyzeLendingModelVersion", MODEL_VERSION);
        return started("LENDING_ANALYSIS", result);
    }

    public Response getLendingAnalysis(String jobId) {
        return Response.ok(requireJobResult(jobId, "LENDING_ANALYSIS")).build();
    }

    public Response getLendingAnalysisSummary(String jobId) {
        ObjectNode analysis = requireJobResult(jobId, "LENDING_ANALYSIS");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobStatus", "SUCCEEDED");
        root.set("DocumentMetadata", analysis.path("DocumentMetadata").deepCopy());
        ObjectNode summary = root.putObject("Summary");
        ObjectNode group = summary.putArray("DocumentGroups").addObject();
        group.put("Type", "UNCLASSIFIED");
        ObjectNode split = group.putArray("SplitDocuments").addObject();
        split.put("Index", 1);
        split.putArray("Pages").add(1);
        group.putArray("DetectedSignatures");
        group.putArray("UndetectedSignatures");
        summary.putArray("UndetectedDocumentTypes");
        root.put("AnalyzeLendingModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }

    private Response started(String jobType, ObjectNode result) {
        String jobId = UUID.randomUUID().toString().replace("-", "");
        storeJob(jobId, jobType, result);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", jobId);
        return Response.ok(root).build();
    }
    /**
     * StartDocumentTextDetection — enqueues a fake async job and immediately marks it SUCCEEDED.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_StartDocumentTextDetection.html
     */
    public Response startDocumentTextDetection() {
        return startDocumentTextDetection(List.of());
    }

    /** StartDocumentTextDetection for the document with these mock keys. */
    public Response startDocumentTextDetection(List<String> keys) {
        String jobId = UUID.randomUUID().toString();
        ObjectNode result = textDetection(keys);
        result.put("JobStatus", "SUCCEEDED");
        storeJob(jobId, "TEXT_DETECTION", result);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", jobId);
        return Response.ok(root).build();
    }
    /**
     * GetDocumentTextDetection — returns the job's stable SUCCEEDED result until it expires.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_GetDocumentTextDetection.html
     */
    public Response getDocumentTextDetection(String jobId) {
        return Response.ok(requireJobResult(jobId, "TEXT_DETECTION")).build();
    }
    /**
     * StartDocumentAnalysis — enqueues a fake async job and immediately marks it SUCCEEDED.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_StartDocumentAnalysis.html
     */
    public Response startDocumentAnalysis() {
        return startDocumentAnalysis(List.of());
    }

    /** StartDocumentAnalysis for the document with these mock keys. */
    public Response startDocumentAnalysis(List<String> keys) {
        String jobId = UUID.randomUUID().toString();
        ObjectNode result = documentAnalysis(keys);
        result.put("JobStatus", "SUCCEEDED");
        storeJob(jobId, "DOCUMENT_ANALYSIS", result);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", jobId);
        return Response.ok(root).build();
    }
    /**
     * GetDocumentAnalysis — returns the job's stable SUCCEEDED result until it expires.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_GetDocumentAnalysis.html
     */
    public Response getDocumentAnalysis(String jobId) {
        return Response.ok(requireJobResult(jobId, "DOCUMENT_ANALYSIS")).build();
    }
    private void storeJob(String jobId, String jobType, ObjectNode result) {
        sweepExpired();
        asyncJobs.put(jobId, new AsyncJob(jobType, clock.instant(), result));
        enforceCapacity();
    }
    // Private helpers
    private ObjectNode requireJobResult(String jobId, String expectedType) {
        if (jobId == null || jobId.isBlank()) {
            throw new AwsException("ValidationException", "JobId is required.", 400);
        }
        AsyncJob job = asyncJobs.get(jobId);
        if (job == null) {
            throw invalidJobId();
        }
        if (job.createdAt().isBefore(clock.instant().minus(RESULT_RETENTION))) {
            asyncJobs.remove(jobId);
            throw invalidJobId();
        }
        if (!expectedType.equals(job.jobType())) {
            throw new AwsException("InvalidJobIdException",
                    "Job was not started by the correct operation.", 400);
        }
        return job.result();
    }
    private static AwsException invalidJobId() {
        return new AwsException("InvalidJobIdException",
                "An invalid job identifier was passed to an Amazon Textract operation.", 400);
    }
    private void sweepExpired() {
        Instant cutoff = clock.instant().minus(RESULT_RETENTION);
        asyncJobs.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(cutoff));
    }
    private void enforceCapacity() {
        int excess = asyncJobs.size() - MAX_RETAINED_JOBS;
        if (excess <= 0) {
            return;
        }
        List<Map.Entry<String, AsyncJob>> oldest = asyncJobs.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparing(AsyncJob::createdAt)))
                .limit(excess)
                .toList();
        oldest.forEach(entry -> asyncJobs.remove(entry.getKey(), entry.getValue()));
    }
    private ObjectNode buildDocumentMetadata(int pages) {
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("Pages", pages);
        return meta;
    }
    /**
     * Builds a minimal AWS-shaped Block hierarchy: PAGE to LINE to WORD.
     * Each Block follows https://docs.aws.amazon.com/textract/latest/dg/API_Block.html
     */
    private ArrayNode buildStubBlocks() {
        ArrayNode blocks = objectMapper.createArrayNode();
        String wordId = UUID.randomUUID().toString();
        String lineId = UUID.randomUUID().toString();
        String pageId = UUID.randomUUID().toString();
        // WORD block
        ObjectNode word = objectMapper.createObjectNode();
        word.put("BlockType", "WORD");
        word.put("Id", wordId);
        word.put("Confidence", 99.9);
        word.put("Text", "Floci");
        word.set("Geometry", AwsGeometry.buildGeometry(0.1, 0.1, 0.15, 0.05));
        word.put("Page", 1);
        blocks.add(word);
        // LINE block (child: WORD)
        ObjectNode line = objectMapper.createObjectNode();
        line.put("BlockType", "LINE");
        line.put("Id", lineId);
        line.put("Confidence", 99.9);
        line.put("Text", "Floci");
        line.set("Geometry", AwsGeometry.buildGeometry(0.1, 0.1, 0.15, 0.05));
        line.set("Relationships", buildRelationships("CHILD", wordId));
        line.put("Page", 1);
        blocks.add(line);
        // PAGE block (child: LINE)
        ObjectNode page = objectMapper.createObjectNode();
        page.put("BlockType", "PAGE");
        page.put("Id", pageId);
        page.put("Confidence", 99.9);
        page.set("Geometry", AwsGeometry.buildGeometry(0.0, 0.0, 1.0, 1.0));
        page.set("Relationships", buildRelationships("CHILD", lineId));
        page.put("Page", 1);
        blocks.add(page);
        return blocks;
    }
    /**
     * Builds a single Relationship entry.
     * @see <a href="https://docs.aws.amazon.com/textract/latest/dg/API_Relationship.html">Relationship</a>
     */
    private ArrayNode buildRelationships(String type, String... childIds) {
        ArrayNode relationships = objectMapper.createArrayNode();
        ObjectNode rel = relationships.addObject();
        rel.put("Type", type);
        ArrayNode ids = rel.putArray("Ids");
        for (String id : childIds) {
            ids.add(id);
        }
        return relationships;
    }
}
