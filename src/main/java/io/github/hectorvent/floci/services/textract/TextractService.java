package io.github.hectorvent.floci.services.textract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Dummy response builder for Amazon Textract. Stateless for sync operations.
 * Async operations (Start* and Get*) use an in-memory job store.
 * Adapter management (CreateAdapter / GetAdapter / ListAdapters / UpdateAdapter
 * / DeleteAdapter / TagResource) is an in-memory CRUD store.
 * No real OCR or document analysis is performed: every call returns a fixed
 * stub Block list matching the AWS Textract wire format.
 *
 * @see <a href="https://docs.aws.amazon.com/textract/latest/dg/API_Operations.html">Textract API Reference</a>
 */
@ApplicationScoped
public class TextractService implements Resettable {

    static final String MODEL_VERSION = "1.0";
    /** LINE/WORD stub text — matches the alchemy PNG fixture that renders "HELLO". */
    static final String STUB_TEXT = "HELLO";
    private static final Pattern ADAPTER_NAME = Pattern.compile("[a-zA-Z0-9-_]{1,128}");
    private static final String ADAPTER_ARN_MARKER = "/adapters/";
    private static final String TEXT_DETECTION = "TEXT_DETECTION";
    private static final String DOCUMENT_ANALYSIS = "DOCUMENT_ANALYSIS";
    private static final String EXPENSE_ANALYSIS = "EXPENSE_ANALYSIS";
    private static final String LENDING_ANALYSIS = "LENDING_ANALYSIS";

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final S3Service s3Service;
    /** In-memory async job store: jobId to jobType. Jobs are kept so Get* can be polled. */
    private final ConcurrentHashMap<String, String> asyncJobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AdapterRecord> adapters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> adapterIdsByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> adapterIdsByToken = new ConcurrentHashMap<>();

    @Inject
    public TextractService(ObjectMapper objectMapper, RegionResolver regionResolver, S3Service s3Service) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.s3Service = s3Service;
    }

    public void clear() {
        asyncJobs.clear();
        adapters.clear();
        adapterIdsByName.clear();
        adapterIdsByToken.clear();
    }

    /**
     * DetectDocumentText — returns a stub PAGE + LINE + WORD block hierarchy.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_DetectDocumentText.html
     */
    public Response detectDocumentText() {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("Blocks", buildStubBlocks());
        root.put("DetectDocumentTextModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }

    /**
     * AnalyzeDocument — returns the same stub blocks; FeatureTypes are accepted but ignored.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_AnalyzeDocument.html
     */
    public Response analyzeDocument() {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("Blocks", buildStubBlocks());
        root.put("AnalyzeDocumentModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }

    /**
     * AnalyzeExpense — stub expense document list.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_AnalyzeExpense.html
     */
    public Response analyzeExpense() {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("ExpenseDocuments", buildExpenseDocuments());
        return Response.ok(root).build();
    }

    /**
     * AnalyzeID — stub identity document list (at least one document).
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_AnalyzeID.html
     */
    public Response analyzeID() {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("IdentityDocuments", buildIdentityDocuments());
        root.put("AnalyzeIDModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }

    /**
     * StartDocumentTextDetection — enqueues a fake async job and immediately marks it SUCCEEDED.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_StartDocumentTextDetection.html
     */
    public Response startDocumentTextDetection() {
        String jobId = UUID.randomUUID().toString();
        asyncJobs.put(jobId, TEXT_DETECTION);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", jobId);
        return Response.ok(root).build();
    }

    /**
     * GetDocumentTextDetection — returns SUCCEEDED + stub blocks for any known JobId.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_GetDocumentTextDetection.html
     */
    public Response getDocumentTextDetection(String jobId) {
        requireKnownJob(jobId, TEXT_DETECTION);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobStatus", "SUCCEEDED");
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("Blocks", buildStubBlocks());
        root.put("DetectDocumentTextModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }

    /**
     * StartDocumentAnalysis — enqueues a fake async job and immediately marks it SUCCEEDED.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_StartDocumentAnalysis.html
     */
    public Response startDocumentAnalysis() {
        String jobId = UUID.randomUUID().toString();
        asyncJobs.put(jobId, DOCUMENT_ANALYSIS);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", jobId);
        return Response.ok(root).build();
    }

    /**
     * GetDocumentAnalysis — returns SUCCEEDED + stub blocks for any known JobId.
     * Response shape: https://docs.aws.amazon.com/textract/latest/dg/API_GetDocumentAnalysis.html
     */
    public Response getDocumentAnalysis(String jobId) {
        requireKnownJob(jobId, DOCUMENT_ANALYSIS);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobStatus", "SUCCEEDED");
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("Blocks", buildStubBlocks());
        root.put("AnalyzeDocumentModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }

    public Response startExpenseAnalysis() {
        return startJob(EXPENSE_ANALYSIS);
    }

    public Response getExpenseAnalysis(String jobId) {
        requireKnownJob(jobId, EXPENSE_ANALYSIS);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobStatus", "SUCCEEDED");
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("ExpenseDocuments", buildExpenseDocuments());
        root.put("AnalyzeExpenseModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }

    public Response startLendingAnalysis() {
        return startJob(LENDING_ANALYSIS);
    }

    public Response getLendingAnalysis(String jobId) {
        requireKnownJob(jobId, LENDING_ANALYSIS);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobStatus", "SUCCEEDED");
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("Results", buildLendingResults());
        root.put("AnalyzeLendingModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }

    public Response getLendingAnalysisSummary(String jobId) {
        requireKnownJob(jobId, LENDING_ANALYSIS);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobStatus", "SUCCEEDED");
        root.set("DocumentMetadata", buildDocumentMetadata(1));
        root.set("Summary", buildLendingSummary());
        root.put("AnalyzeLendingModelVersion", MODEL_VERSION);
        return Response.ok(root).build();
    }

    /**
     * CreateAdapter — stores a named adapter with feature types, optional description,
     * auto-update, and tags. Adapter identity is a server-assigned id.
     *
     * @see <a href="https://docs.aws.amazon.com/textract/latest/APIReference/API_CreateAdapter.html">CreateAdapter</a>
     */
    public Response createAdapter(JsonNode request, String region) {
        JsonNode body = objectOrEmpty(request);
        String name = requireAdapterName(stringField(body, "AdapterName"), true);
        List<String> featureTypes = requireFeatureTypes(body);
        String description = optionalDescription(body);
        String autoUpdate = optionalAutoUpdate(body, "DISABLED");
        String token = stringField(body, "ClientRequestToken");
        String fingerprint = fingerprint(name, featureTypes, description, autoUpdate);
        if (token != null && !token.isBlank()) {
            String existingId = adapterIdsByToken.get(token);
            if (existingId != null) {
                AdapterRecord existing = adapters.get(existingId);
                if (existing != null && fingerprint.equals(existing.fingerprint)) {
                    return created(existing.adapterId);
                }
                throw new AwsException("IdempotentParameterMismatchException",
                        "ClientRequestToken was reused with different parameters.", 400);
            }
        }
        if (adapterIdsByName.containsKey(name)) {
            throw new AwsException("ConflictException",
                    "An adapter with AdapterName '" + name + "' already exists.", 400);
        }
        String resolvedRegion = region != null && !region.isBlank()
                ? region : regionResolver.getRegion();
        AdapterRecord record = new AdapterRecord(
                UUID.randomUUID().toString(),
                name,
                description,
                featureTypes,
                autoUpdate,
                Instant.now().getEpochSecond(),
                resolvedRegion,
                regionResolver.getAccountId(),
                token,
                fingerprint);
        record.tags.putAll(parseTagMap(body.get("Tags")));
        adapters.put(record.adapterId, record);
        adapterIdsByName.put(name, record.adapterId);
        if (token != null && !token.isBlank()) {
            adapterIdsByToken.put(token, record.adapterId);
        }
        return created(record.adapterId);
    }

    /**
     * GetAdapter — returns configuration, tags, and creation time for an adapter id.
     *
     * @see <a href="https://docs.aws.amazon.com/textract/latest/APIReference/API_GetAdapter.html">GetAdapter</a>
     */
    public Response getAdapter(JsonNode request) {
        AdapterRecord record = requireAdapter(stringField(objectOrEmpty(request), "AdapterId"));
        return Response.ok(toGetAdapter(record)).build();
    }

    /**
     * ListAdapters — returns AdapterOverview entries, optionally filtered by creation time
     * and paginated with MaxResults / NextToken.
     *
     * @see <a href="https://docs.aws.amazon.com/textract/latest/APIReference/API_ListAdapters.html">ListAdapters</a>
     */
    public Response listAdapters(JsonNode request) {
        JsonNode body = objectOrEmpty(request);
        Long after = optionalEpoch(body, "AfterCreationTime");
        Long before = optionalEpoch(body, "BeforeCreationTime");
        int maxResults = optionalMaxResults(body);
        int offset = optionalOffset(body);
        List<AdapterRecord> matches = new ArrayList<>();
        for (AdapterRecord record : adapters.values()) {
            if (after != null && record.creationTime <= after) {
                continue;
            }
            if (before != null && record.creationTime >= before) {
                continue;
            }
            matches.add(record);
        }
        matches.sort(Comparator.comparingLong((AdapterRecord r) -> r.creationTime)
                .thenComparing(r -> r.adapterId));
        int from = Math.min(offset, matches.size());
        int to = Math.min(from + maxResults, matches.size());
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode list = root.putArray("Adapters");
        for (int i = from; i < to; i++) {
            list.add(toOverview(matches.get(i)));
        }
        if (to < matches.size()) {
            root.put("NextToken", Integer.toString(to));
        }
        return Response.ok(root).build();
    }

    /**
     * UpdateAdapter — mutates name, description, and/or AutoUpdate in place.
     * FeatureTypes cannot be changed.
     *
     * @see <a href="https://docs.aws.amazon.com/textract/latest/APIReference/API_UpdateAdapter.html">UpdateAdapter</a>
     */
    public Response updateAdapter(JsonNode request) {
        JsonNode body = objectOrEmpty(request);
        AdapterRecord record = requireAdapter(stringField(body, "AdapterId"));
        boolean hasName = body.hasNonNull("AdapterName");
        boolean hasDescription = body.hasNonNull("Description");
        boolean hasAutoUpdate = body.hasNonNull("AutoUpdate");
        if (!hasName && !hasDescription && !hasAutoUpdate) {
            throw new AwsException("ValidationException",
                    "At least one of AdapterName, Description, or AutoUpdate must be specified.", 400);
        }
        if (hasName) {
            String name = requireAdapterName(stringField(body, "AdapterName"), true);
            if (!name.equals(record.adapterName)) {
                String occupant = adapterIdsByName.putIfAbsent(name, record.adapterId);
                if (occupant != null && !occupant.equals(record.adapterId)) {
                    throw new AwsException("ConflictException",
                            "An adapter with AdapterName '" + name + "' already exists.", 400);
                }
                adapterIdsByName.remove(record.adapterName);
                record.adapterName = name;
            }
        }
        if (hasDescription) {
            record.description = optionalDescription(body);
        }
        if (hasAutoUpdate) {
            record.autoUpdate = optionalAutoUpdate(body, record.autoUpdate);
        }
        record.fingerprint = fingerprint(
                record.adapterName, record.featureTypes, record.description, record.autoUpdate);
        return Response.ok(toUpdateAdapter(record)).build();
    }

    /**
     * DeleteAdapter — removes the adapter. Missing ids raise ResourceNotFoundException.
     *
     * @see <a href="https://docs.aws.amazon.com/textract/latest/APIReference/API_DeleteAdapter.html">DeleteAdapter</a>
     */
    public Response deleteAdapter(JsonNode request) {
        AdapterRecord record = requireAdapter(stringField(objectOrEmpty(request), "AdapterId"));
        adapters.remove(record.adapterId);
        adapterIdsByName.remove(record.adapterName, record.adapterId);
        if (record.clientRequestToken != null) {
            adapterIdsByToken.remove(record.clientRequestToken, record.adapterId);
        }
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    /**
     * TagResource — upserts tags on an adapter identified by ResourceARN.
     *
     * @see <a href="https://docs.aws.amazon.com/textract/latest/APIReference/API_TagResource.html">TagResource</a>
     */
    public Response tagResource(JsonNode request) {
        JsonNode body = objectOrEmpty(request);
        AdapterRecord record = requireAdapterByArn(stringField(body, "ResourceARN"));
        JsonNode tagsNode = body.get("Tags");
        if (tagsNode == null || tagsNode.isNull()) {
            throw new AwsException("ValidationException", "Tags is required.", 400);
        }
        record.tags.putAll(parseTagMap(tagsNode));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    /**
     * UntagResource — removes TagKeys from the adapter identified by ResourceARN.
     *
     * @see <a href="https://docs.aws.amazon.com/textract/latest/APIReference/API_UntagResource.html">UntagResource</a>
     */
    public Response untagResource(JsonNode request) {
        JsonNode body = objectOrEmpty(request);
        AdapterRecord record = requireAdapterByArn(stringField(body, "ResourceARN"));
        JsonNode keys = body.get("TagKeys");
        if (keys == null || !keys.isArray()) {
            throw new AwsException("ValidationException", "TagKeys is required.", 400);
        }
        for (JsonNode key : keys) {
            if (key != null && !key.isNull()) {
                record.tags.remove(key.asText());
            }
        }
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    /**
     * ListTagsForResource — returns the tag map on the adapter identified by ResourceARN.
     *
     * @see <a href="https://docs.aws.amazon.com/textract/latest/APIReference/API_ListTagsForResource.html">ListTagsForResource</a>
     */
    public Response listTagsForResource(JsonNode request) {
        AdapterRecord record = requireAdapterByArn(stringField(objectOrEmpty(request), "ResourceARN"));
        ObjectNode root = objectMapper.createObjectNode();
        root.set("Tags", tagsNode(record.tags));
        return Response.ok(root).build();
    }

    public Response listAdapterVersions(JsonNode request) {
        JsonNode body = objectOrEmpty(request);
        String adapterId = stringField(body, "AdapterId");
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode list = root.putArray("AdapterVersions");
        if (adapterId != null) {
            AdapterRecord record = requireAdapter(adapterId);
            for (AdapterVersionRecord version : record.versions.values()) {
                list.add(toVersionOverview(record, version));
            }
        } else {
            for (AdapterRecord record : adapters.values()) {
                for (AdapterVersionRecord version : record.versions.values()) {
                    list.add(toVersionOverview(record, version));
                }
            }
        }
        return Response.ok(root).build();
    }

    public Response getAdapterVersion(JsonNode request) {
        JsonNode body = objectOrEmpty(request);
        AdapterRecord record = requireAdapter(stringField(body, "AdapterId"));
        String versionId = stringField(body, "AdapterVersion");
        if (versionId == null) {
            throw new AwsException("ValidationException", "AdapterVersion is required.", 400);
        }
        AdapterVersionRecord version = record.versions.get(versionId);
        if (version == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Adapter version " + versionId + " was not found.", 400);
        }
        ObjectNode root = toUpdateAdapter(record);
        root.put("AdapterVersion", version.adapterVersion);
        root.put("Status", version.status);
        if (version.datasetConfig != null) {
            root.set("DatasetConfig", version.datasetConfig.deepCopy());
        }
        if (version.outputConfig != null) {
            root.set("OutputConfig", version.outputConfig.deepCopy());
        }
        root.set("Tags", tagsNode(version.tags));
        return Response.ok(root).build();
    }

    public Response createAdapterVersion(JsonNode request) {
        JsonNode body = objectOrEmpty(request);
        AdapterRecord record = requireAdapter(stringField(body, "AdapterId"));
        JsonNode datasetConfig = body.get("DatasetConfig");
        JsonNode manifest = datasetConfig == null ? null : datasetConfig.get("ManifestS3Object");
        String bucket = stringField(manifest, "Bucket");
        String name = stringField(manifest, "Name");
        if (bucket == null || name == null) {
            throw new AwsException("ValidationException",
                    "DatasetConfig.ManifestS3Object.Bucket and Name are required.", 400);
        }
        JsonNode outputConfig = body.get("OutputConfig");
        if (outputConfig == null || outputConfig.isNull() || stringField(outputConfig, "S3Bucket") == null) {
            throw new AwsException("ValidationException", "OutputConfig.S3Bucket is required.", 400);
        }
        requireManifestObject(bucket, name);
        AdapterVersionRecord version = new AdapterVersionRecord();
        version.adapterVersion = String.valueOf(record.versionSeq.incrementAndGet());
        version.status = "ACTIVE";
        version.creationTime = Instant.now().getEpochSecond();
        version.datasetConfig = datasetConfig.deepCopy();
        version.outputConfig = outputConfig.deepCopy();
        version.tags.putAll(parseTagMap(body.get("Tags")));
        record.versions.put(version.adapterVersion, version);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("AdapterId", record.adapterId);
        root.put("AdapterVersion", version.adapterVersion);
        return Response.ok(root).build();
    }

    public Response deleteAdapterVersion(JsonNode request) {
        JsonNode body = objectOrEmpty(request);
        AdapterRecord record = requireAdapter(stringField(body, "AdapterId"));
        String versionId = stringField(body, "AdapterVersion");
        if (versionId == null) {
            throw new AwsException("ValidationException", "AdapterVersion is required.", 400);
        }
        record.versions.remove(versionId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // Private helpers
    private void requireKnownJob(String jobId, String expectedType) {
        if (jobId == null || jobId.isBlank()) {
            throw new AwsException("ValidationException", "JobId is required.", 400);
        }
        String type = asyncJobs.get(jobId);
        if (type == null) {
            throw new AwsException("InvalidJobIdException",
                    "An invalid job identifier was passed to an Amazon Textract operation.", 400);
        }
        if (!expectedType.equals(type)) {
            throw new AwsException("InvalidJobIdException",
                    "Job was not started by the correct operation.", 400);
        }
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
        word.put("Text", STUB_TEXT);
        word.set("Geometry", buildGeometry(0.1, 0.1, 0.15, 0.05));
        word.put("Page", 1);
        blocks.add(word);
        // LINE block (child: WORD)
        ObjectNode line = objectMapper.createObjectNode();
        line.put("BlockType", "LINE");
        line.put("Id", lineId);
        line.put("Confidence", 99.9);
        line.put("Text", STUB_TEXT);
        line.set("Geometry", buildGeometry(0.1, 0.1, 0.15, 0.05));
        line.set("Relationships", buildRelationships("CHILD", wordId));
        line.put("Page", 1);
        blocks.add(line);
        // PAGE block (child: LINE)
        ObjectNode page = objectMapper.createObjectNode();
        page.put("BlockType", "PAGE");
        page.put("Id", pageId);
        page.put("Confidence", 99.9);
        page.set("Geometry", buildGeometry(0.0, 0.0, 1.0, 1.0));
        page.set("Relationships", buildRelationships("CHILD", lineId));
        page.put("Page", 1);
        blocks.add(page);
        return blocks;
    }

    /**
     * Builds a Geometry object with BoundingBox and a 4-point Polygon.
     * @see <a href="https://docs.aws.amazon.com/textract/latest/dg/API_Geometry.html">Geometry</a>
     */
    private ObjectNode buildGeometry(double left, double top, double width, double height) {
        ObjectNode geometry = objectMapper.createObjectNode();
        ObjectNode bbox = geometry.putObject("BoundingBox");
        bbox.put("Width", width);
        bbox.put("Height", height);
        bbox.put("Left", left);
        bbox.put("Top", top);
        ArrayNode polygon = geometry.putArray("Polygon");
        addPoint(polygon, left, top);
        addPoint(polygon, left + width, top);
        addPoint(polygon, left + width, top + height);
        addPoint(polygon, left, top + height);
        return geometry;
    }

    private void addPoint(ArrayNode polygon, double x, double y) {
        ObjectNode point = polygon.addObject();
        point.put("X", x);
        point.put("Y", y);
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

    private Response created(String adapterId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("AdapterId", adapterId);
        return Response.ok(root).build();
    }

    private ObjectNode toGetAdapter(AdapterRecord record) {
        ObjectNode root = toUpdateAdapter(record);
        root.set("Tags", tagsNode(record.tags));
        return root;
    }

    private ObjectNode toUpdateAdapter(AdapterRecord record) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("AdapterId", record.adapterId);
        root.put("AdapterName", record.adapterName);
        root.put("CreationTime", record.creationTime);
        if (record.description != null) {
            root.put("Description", record.description);
        }
        ArrayNode features = root.putArray("FeatureTypes");
        for (String feature : record.featureTypes) {
            features.add(feature);
        }
        root.put("AutoUpdate", record.autoUpdate);
        return root;
    }

    private ObjectNode toOverview(AdapterRecord record) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("AdapterId", record.adapterId);
        node.put("AdapterName", record.adapterName);
        node.put("CreationTime", record.creationTime);
        ArrayNode features = node.putArray("FeatureTypes");
        for (String feature : record.featureTypes) {
            features.add(feature);
        }
        return node;
    }

    private ObjectNode tagsNode(Map<String, String> tags) {
        ObjectNode node = objectMapper.createObjectNode();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            node.put(entry.getKey(), entry.getValue());
        }
        return node;
    }

    private AdapterRecord requireAdapter(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new AwsException("ValidationException", "AdapterId is required.", 400);
        }
        AdapterRecord record = adapters.get(adapterId);
        if (record == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Adapter " + adapterId + " was not found.", 400);
        }
        return record;
    }

    private AdapterRecord requireAdapterByArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("ValidationException", "ResourceARN is required.", 400);
        }
        int idx = arn.lastIndexOf(ADAPTER_ARN_MARKER);
        if (idx < 0) {
            throw new AwsException("InvalidParameterException",
                    "ResourceARN is not a Textract adapter ARN.", 400);
        }
        String adapterId = arn.substring(idx + ADAPTER_ARN_MARKER.length());
        if (adapterId.isBlank() || adapterId.contains("/")) {
            throw new AwsException("InvalidParameterException",
                    "ResourceARN is not a Textract adapter ARN.", 400);
        }
        return requireAdapter(adapterId);
    }

    private String requireAdapterName(String name, boolean required) {
        if (name == null || name.isBlank()) {
            if (required) {
                throw new AwsException("ValidationException", "AdapterName is required.", 400);
            }
            return null;
        }
        if (!ADAPTER_NAME.matcher(name).matches()) {
            throw new AwsException("InvalidParameterException",
                    "AdapterName must match [a-zA-Z0-9-_]{1,128}.", 400);
        }
        return name;
    }

    private List<String> requireFeatureTypes(JsonNode body) {
        JsonNode node = body.get("FeatureTypes");
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new AwsException("ValidationException", "FeatureTypes is required.", 400);
        }
        List<String> features = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.isNull() && !item.asText().isBlank()) {
                features.add(item.asText());
            }
        }
        if (features.isEmpty()) {
            throw new AwsException("ValidationException", "FeatureTypes is required.", 400);
        }
        return List.copyOf(features);
    }

    private String optionalDescription(JsonNode body) {
        String description = stringField(body, "Description");
        if (description == null) {
            return null;
        }
        if (description.isBlank() || description.length() > 256) {
            throw new AwsException("InvalidParameterException",
                    "Description must be 1-256 characters.", 400);
        }
        return description;
    }

    private String optionalAutoUpdate(JsonNode body, String defaultValue) {
        String value = stringField(body, "AutoUpdate");
        if (value == null) {
            return defaultValue;
        }
        if (!"ENABLED".equals(value) && !"DISABLED".equals(value)) {
            throw new AwsException("InvalidParameterException",
                    "AutoUpdate must be ENABLED or DISABLED.", 400);
        }
        return value;
    }

    private int optionalMaxResults(JsonNode body) {
        JsonNode node = body.get("MaxResults");
        if (node == null || node.isNull()) {
            return 100;
        }
        int max = node.asInt();
        if (max < 1) {
            throw new AwsException("ValidationException", "MaxResults must be at least 1.", 400);
        }
        return Math.min(max, 1000);
    }

    private int optionalOffset(JsonNode body) {
        String token = stringField(body, "NextToken");
        if (token == null) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(token);
            if (offset < 0) {
                throw new NumberFormatException("negative");
            }
            return offset;
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException", "Invalid NextToken.", 400);
        }
    }

    private Long optionalEpoch(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        try {
            return Long.parseLong(node.asText());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException", field + " must be a timestamp.", 400);
        }
    }

    private Map<String, String> parseTagMap(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull() || !node.isObject()) {
            return tags;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue() != null && !field.getValue().isNull()) {
                tags.put(field.getKey(), field.getValue().asText());
            }
        }
        return tags;
    }

    private JsonNode objectOrEmpty(JsonNode request) {
        return request != null && request.isObject() ? request : objectMapper.createObjectNode();
    }

    private static String stringField(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static String fingerprint(
            String name, List<String> featureTypes, String description, String autoUpdate) {
        return name + "|" + String.join(",", featureTypes) + "|"
                + (description == null ? "" : description) + "|" + autoUpdate;
    }

    private Response startJob(String type) {
        String jobId = UUID.randomUUID().toString();
        asyncJobs.put(jobId, type);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", jobId);
        return Response.ok(root).build();
    }

    private void requireManifestObject(String bucket, String key) {
        try {
            if (s3Service == null || !s3Service.objectExists(bucket, key)) {
                throw invalidS3();
            }
        } catch (AwsException e) {
            if ("InvalidS3ObjectException".equals(e.getErrorCode())) {
                throw e;
            }
            throw invalidS3();
        }
    }

    private static AwsException invalidS3() {
        return new AwsException("InvalidS3ObjectException",
                "Unable to get object metadata from S3. Check object key, region and/or access permissions.",
                400);
    }

    private ObjectNode toVersionOverview(AdapterRecord record, AdapterVersionRecord version) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("AdapterId", record.adapterId);
        node.put("AdapterVersion", version.adapterVersion);
        node.put("CreationTime", version.creationTime);
        ArrayNode features = node.putArray("FeatureTypes");
        for (String feature : record.featureTypes) {
            features.add(feature);
        }
        node.put("Status", version.status);
        return node;
    }

    private ArrayNode buildExpenseDocuments() {
        ArrayNode docs = objectMapper.createArrayNode();
        ObjectNode doc = docs.addObject();
        doc.put("ExpenseIndex", 1);
        doc.putArray("SummaryFields");
        doc.putArray("LineItemGroups");
        doc.set("Blocks", buildStubBlocks());
        return docs;
    }

    private ArrayNode buildIdentityDocuments() {
        ArrayNode docs = objectMapper.createArrayNode();
        ObjectNode doc = docs.addObject();
        doc.put("DocumentIndex", 1);
        ArrayNode fields = doc.putArray("IdentityDocumentFields");
        ObjectNode field = fields.addObject();
        ObjectNode type = field.putObject("Type");
        type.put("Text", "FIRST_NAME");
        type.put("Confidence", 99.9);
        ObjectNode value = field.putObject("ValueDetection");
        value.put("Text", STUB_TEXT);
        value.put("Confidence", 99.9);
        doc.set("Blocks", buildStubBlocks());
        return docs;
    }

    private ArrayNode buildLendingResults() {
        ArrayNode results = objectMapper.createArrayNode();
        ObjectNode result = results.addObject();
        result.put("Page", 1);
        ObjectNode classification = result.putObject("PageClassification");
        ArrayNode pageType = classification.putArray("PageType");
        ObjectNode type = pageType.addObject();
        type.put("Value", "CHECK");
        type.put("Confidence", 99.9);
        ArrayNode pageNumber = classification.putArray("PageNumber");
        ObjectNode number = pageNumber.addObject();
        number.put("Value", "1");
        number.put("Confidence", 99.9);
        result.putArray("Extractions");
        return results;
    }

    private ObjectNode buildLendingSummary() {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.putArray("DocumentGroups");
        summary.putArray("UndetectedDocumentTypes");
        return summary;
    }

    private static final class AdapterRecord {
        final String adapterId;
        String adapterName;
        String description;
        final List<String> featureTypes;
        String autoUpdate;
        final long creationTime;
        final String region;
        final String accountId;
        final String clientRequestToken;
        String fingerprint;
        final Map<String, String> tags = new LinkedHashMap<>();
        final Map<String, AdapterVersionRecord> versions = new LinkedHashMap<>();
        final AtomicInteger versionSeq = new AtomicInteger();

        AdapterRecord(
                String adapterId,
                String adapterName,
                String description,
                List<String> featureTypes,
                String autoUpdate,
                long creationTime,
                String region,
                String accountId,
                String clientRequestToken,
                String fingerprint) {
            this.adapterId = adapterId;
            this.adapterName = adapterName;
            this.description = description;
            this.featureTypes = featureTypes;
            this.autoUpdate = autoUpdate;
            this.creationTime = creationTime;
            this.region = region;
            this.accountId = accountId;
            this.clientRequestToken = clientRequestToken;
            this.fingerprint = fingerprint;
        }
    }

    private static final class AdapterVersionRecord {
        String adapterVersion;
        String status;
        long creationTime;
        JsonNode datasetConfig;
        JsonNode outputConfig;
        final Map<String, String> tags = new LinkedHashMap<>();
    }
}
