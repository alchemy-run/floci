package io.floci.aws.services.kendra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.kendra.KendraStore.meta;
import static io.floci.aws.services.kendra.KendraStore.text;
import static io.floci.aws.services.kendra.KendraStore.validation;

/**
 * Documents put into an index directly or by a data source sync. A put is checked as it's received: a document needs
 * its content inline ({@code Blob}) or in S3 ({@code S3Path}, read with the request's role), and its attributes must
 * be fields of the index with matching types; a document that fails these is returned in {@code FailedDocuments}.
 * The rest are {@code PROCESSING} for a moment and then {@code INDEXED} ({@code UPDATED} when a document is put
 * again), or {@code FAILED} when their text can't be read or an enrichment Lambda fails.
 *
 * <p>Custom document enrichment runs as on AWS: inline rules first (a condition on an attribute, then setting or
 * deleting a target attribute, or deleting the content), then the pre-extraction Lambda over the raw document Kendra
 * writes to the hook's bucket, then text extraction, then the post-extraction Lambda over the extracted text as
 * {@code {"textContent":{"documentBodyText":...}}}. Each Lambda answers the key of the object to index and the
 * attribute updates to apply.
 */
@ApplicationScoped
public class KendraDocuments {

    static final int MAX_BATCH = 10;
    static final int MAX_INLINE_BYTES = 10 * 1024 * 1024;
    static final int MAX_S3_BYTES = 50 * 1024 * 1024;
    static final int MAX_TEXT_BYTES = 5 * 1024 * 1024;

    private final KendraIndexes indexes;
    private final KendraStore store;
    private final S3Service s3;
    private final Instance<LambdaService> lambda;

    @Inject
    public KendraDocuments(KendraIndexes indexes, KendraStore store, S3Service s3, Instance<LambdaService> lambda) {
        this.indexes = indexes;
        this.store = store;
        this.s3 = s3;
        this.lambda = lambda;
    }

    private ObjectMapper mapper() {
        return store.mapper();
    }

    // ---------------------------------------------------------------- batch operations

    public ObjectNode batchPutDocument(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        ObjectNode index = indexes.activeIndex(account, region, indexId);
        JsonNode documents = in.path("Documents");
        if (documents.size() > MAX_BATCH) {
            throw validation("A batch can put at most " + MAX_BATCH + " documents.");
        }
        String role = text(in, "RoleArn");
        boolean needsRole = in.has("CustomDocumentEnrichmentConfiguration");
        for (JsonNode d : documents) {
            needsRole |= d.has("S3Path");
        }
        if (needsRole) {
            if (role == null || role.isEmpty()) {
                throw validation("A RoleArn is needed to read documents from S3 or enrich them.");
            }
            indexes.role(role);
        }
        ObjectNode job = syncJobTarget(account, region, indexId, in.get("DataSourceSyncJobMetricTarget"));
        ArrayNode failed = mapper().createArrayNode();
        for (JsonNode d : documents) {
            String id = text(d, "Id");
            try {
                Ingested ingested = ingest(account, region, index, d, in.get("CustomDocumentEnrichmentConfiguration"),
                        job == null ? null : text(in.get("DataSourceSyncJobMetricTarget"), "DataSourceId"));
                if (job != null) {
                    count(job, ingested.updated() ? "DocumentsModified" : "DocumentsAdded");
                }
            } catch (AwsException e) {
                ObjectNode f = failed.addObject().put("Id", id);
                if (d.has("Attributes")) {
                    for (JsonNode a : d.get("Attributes")) {
                        if ("_data_source_id".equals(a.path("Key").asText())) {
                            f.put("DataSourceId", a.path("Value").path("StringValue").asText());
                        }
                    }
                }
                f.put("ErrorCode", "InvalidRequest").put("ErrorMessage", e.getMessage());
                if (job != null) {
                    count(job, "DocumentsFailed");
                }
            }
        }
        if (job != null) {
            saveJob(account, region, job);
        }
        ObjectNode out = mapper().createObjectNode();
        out.set("FailedDocuments", failed);
        return out;
    }

    public ObjectNode batchDeleteDocument(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.activeIndex(account, region, indexId);
        JsonNode ids = in.path("DocumentIdList");
        if (ids.size() > MAX_BATCH) {
            throw validation("A batch can delete at most " + MAX_BATCH + " documents.");
        }
        ObjectNode job = syncJobTarget(account, region, indexId, in.get("DataSourceSyncJobMetricTarget"));
        for (JsonNode id : ids) {
            if (store.document(account, region, indexId, id.asText()).isPresent()) {
                store.deleteDocument(account, region, indexId, id.asText());
                if (job != null) {
                    count(job, "DocumentsDeleted");
                }
            }
        }
        if (job != null) {
            saveJob(account, region, job);
        }
        ObjectNode out = mapper().createObjectNode();
        out.putArray("FailedDocuments");
        return out;
    }

    public ObjectNode batchGetDocumentStatus(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.activeIndex(account, region, indexId);
        JsonNode infos = in.path("DocumentInfoList");
        if (infos.size() > MAX_BATCH) {
            throw validation("A batch can get the status of at most " + MAX_BATCH + " documents.");
        }
        ObjectNode out = mapper().createObjectNode();
        ArrayNode statuses = out.putArray("DocumentStatusList");
        out.putArray("Errors");
        for (JsonNode info : infos) {
            String id = text(info, "DocumentId");
            ObjectNode document = store.document(account, region, indexId, id).orElse(null);
            String wantedSource = null;
            for (JsonNode a : info.path("Attributes")) {
                if ("_data_source_id".equals(a.path("Key").asText())) {
                    wantedSource = a.path("Value").path("StringValue").asText();
                }
            }
            if (document != null && wantedSource != null
                    && !wantedSource.equals(meta(document).path("dataSourceId").asText(null))) {
                document = null;
            }
            ObjectNode status = statuses.addObject().put("DocumentId", id);
            if (document == null) {
                status.put("DocumentStatus", "NOT_FOUND");
                continue;
            }
            ObjectNode m = meta(document);
            if (m.has("failure")) {
                status.put("DocumentStatus", m.path("version").asInt() > 1 ? "UPDATE_FAILED" : "FAILED")
                        .put("FailureCode", m.path("failure").path("code").asText())
                        .put("FailureReason", m.path("failure").path("reason").asText());
            } else if (m.path("readyAt").asLong() > System.currentTimeMillis()) {
                status.put("DocumentStatus", "PROCESSING");
            } else {
                status.put("DocumentStatus", m.path("version").asInt() > 1 ? "UPDATED" : "INDEXED");
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- ingestion

    record Ingested(String id, boolean updated) {
    }

    /**
     * Checks and stores a document. Problems found as the request is received throw; problems found while the
     * document is processed (its text, its enrichment) leave it {@code FAILED}.
     */
    Ingested ingest(String account, String region, ObjectNode index, JsonNode d, JsonNode enrichment,
                    String dataSourceId) {
        String indexId = index.path("Id").asText();
        String id = text(d, "Id");
        Map<String, ObjectNode> fields = KendraIndexes.fields(index);
        byte[] content;
        String contentType = text(d, "ContentType");
        String uri = null;
        if (d.hasNonNull("Blob") == d.has("S3Path")) {
            throw validation("A document needs exactly one of Blob and S3Path.");
        }
        if (d.hasNonNull("Blob")) {
            try {
                content = Base64.getDecoder().decode(d.get("Blob").asText());
            } catch (IllegalArgumentException e) {
                throw validation("The document's Blob is not valid base64.");
            }
            if (content.length > MAX_INLINE_BYTES) {
                throw validation("An inline document can be at most 10 MB.");
            }
        } else {
            String bucket = text(d.get("S3Path"), "Bucket");
            String key = text(d.get("S3Path"), "Key");
            try {
                content = s3.getObject(bucket, key).getData();
            } catch (AwsException e) {
                throw validation("Unable to read s3://" + bucket + "/" + key + ": " + e.getMessage());
            }
            if (content.length > MAX_S3_BYTES) {
                throw validation("A document in S3 can be at most 50 MB.");
            }
            if (contentType == null) {
                contentType = KendraText.contentType(key);
            }
            uri = "https://s3." + region + ".amazonaws.com/" + bucket + "/" + key; // partition-literal: S3 URI
        }
        ObjectNode attributes = mapper().createObjectNode();
        for (JsonNode a : d.path("Attributes")) {
            String key = a.path("Key").asText();
            JsonNode value = a.path("Value");
            ObjectNode field = fields.get(key);
            if (field == null) {
                throw validation("The document attribute " + key + " is not a field of the index. Add it with "
                        + "UpdateIndex first.");
            }
            checkType(key, field.path("Type").asText(), value);
            attributes.set(key, value.deepCopy());
        }
        if (dataSourceId != null && !attributes.has("_data_source_id")) {
            attributes.putObject("_data_source_id").put("StringValue", dataSourceId);
        }
        if (uri != null && !attributes.has("_source_uri")) {
            attributes.putObject("_source_uri").put("StringValue", uri);
        }
        if (!attributes.has("_language_code")) {
            attributes.putObject("_language_code").put("StringValue", "en");
        }
        String aclConfig = text(d, "AccessControlConfigurationId");
        if (aclConfig != null && indexes.find(account, region, KendraStore.ACCESS_CONTROL,
                indexId + "/" + aclConfig) == null) {
            throw validation("The access control configuration " + aclConfig + " doesn't exist.");
        }
        int limit = "DEVELOPER_EDITION".equals(index.path("Edition").asText()) ? 10_000 : 100_000;
        ObjectNode earlier = store.document(account, region, indexId, id).orElse(null);
        if (earlier == null && store.documents(account, region, indexId).size() >= limit) {
            throw KendraStore.quota("The index has reached its limit of " + limit + " documents.");
        }

        ObjectNode document = store.record();
        document.put("Id", id);
        if (d.hasNonNull("Title")) {
            document.put("Title", d.get("Title").asText());
        }
        if (d.has("AccessControlList")) {
            document.set("AccessControlList", d.get("AccessControlList").deepCopy());
        }
        if (d.has("HierarchicalAccessControlList")) {
            document.set("HierarchicalAccessControlList", d.get("HierarchicalAccessControlList").deepCopy());
        }
        if (aclConfig != null) {
            document.put("AccessControlConfigurationId", aclConfig);
        }
        ObjectNode m = meta(document);
        if (earlier != null) {
            m.put("seq", meta(earlier).path("seq").asLong());
        }
        m.put("version", earlier == null ? 1 : meta(earlier).path("version").asInt(1) + 1);
        m.put("readyAt", System.currentTimeMillis() + store.config().documentProcessingMillis());
        if (dataSourceId != null) {
            m.put("dataSourceId", dataSourceId);
        }
        try {
            process(region, index, document, attributes, content, contentType, enrichment, d);
        } catch (Failure f) {
            m.putObject("failure").put("code", f.code).put("reason", f.getMessage());
        }
        document.set("Attributes", attributes);
        store.putDocument(account, region, indexId, id, document);
        return new Ingested(id, earlier != null);
    }

    /** A document that fails while it's processed, with Kendra's failure code. */
    static final class Failure extends RuntimeException {
        final String code;

        Failure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private void process(String region, ObjectNode index, ObjectNode document, ObjectNode attributes, byte[] content,
                         String contentType, JsonNode enrichment, JsonNode input) {
        boolean deleteContent = false;
        if (enrichment != null) {
            for (JsonNode rule : enrichment.path("InlineConfigurations")) {
                if (rule.has("Condition") && !condition(rule.get("Condition"), attributes, null)) {
                    continue;
                }
                JsonNode target = rule.get("Target");
                if (target != null && target.hasNonNull("TargetDocumentAttributeKey")) {
                    String key = target.get("TargetDocumentAttributeKey").asText();
                    if (target.path("TargetDocumentAttributeValueDeletion").asBoolean(false)) {
                        attributes.remove(key);
                    } else if (target.has("TargetDocumentAttributeValue")) {
                        attributes.set(key, target.get("TargetDocumentAttributeValue").deepCopy());
                    }
                }
                deleteContent |= rule.path("DocumentContentDeletion").asBoolean(false);
            }
        }
        if (deleteContent) {
            content = new byte[0];
        }
        String indexId = index.path("Id").asText();
        JsonNode pre = enrichment == null ? null : enrichment.get("PreExtractionHookConfiguration");
        if (pre != null && !deleteContent && (!pre.has("InvocationCondition")
                || condition(pre.get("InvocationCondition"), attributes, null))) {
            content = hook(region, pre, indexId, document.path("Id").asText(), content, attributes, "pre");
        }
        KendraText.Extracted extracted;
        try {
            extracted = content.length == 0 ? new KendraText.Extracted(null, "", null)
                    : KendraText.extract(content, contentType);
        } catch (IllegalArgumentException e) {
            throw new Failure("InvalidDocument", e.getMessage());
        }
        String text = extracted.text();
        JsonNode post = enrichment == null ? null : enrichment.get("PostExtractionHookConfiguration");
        if (post != null && (!post.has("InvocationCondition")
                || condition(post.get("InvocationCondition"), attributes, text))) {
            ObjectNode body = mapper().createObjectNode();
            body.putObject("textContent").put("documentBodyText", text);
            byte[] result = hook(region, post, indexId, document.path("Id").asText(),
                    body.toString().getBytes(StandardCharsets.UTF_8), attributes, "post");
            try {
                text = mapper().readTree(result).path("textContent").path("documentBodyText").asText("");
            } catch (IOException e) {
                throw new Failure("PostExtractionHookFailed", "The post-extraction Lambda's object is not JSON.");
            }
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) {
            text = new String(bytes, 0, MAX_TEXT_BYTES, StandardCharsets.UTF_8);
        }
        document.put("Text", text);
        if (!document.has("Title") && extracted.title() != null) {
            document.put("Title", extracted.title());
        }
        String type = contentType == null ? KendraText.sniff(content) : contentType;
        document.put("ContentType", type);
        if (!attributes.has("_file_type")) {
            attributes.putObject("_file_type").put("StringValue", type);
        }
        if (extracted.table() != null) {
            ArrayNode rows = document.putArray("Table");
            extracted.table().forEach(row -> {
                ArrayNode r = rows.addArray();
                row.forEach(r::add);
            });
        }
    }

    /**
     * Runs an enrichment Lambda: writes the object to the hook's bucket, invokes the function with Kendra's event,
     * applies its attribute updates, and returns the object it names.
     */
    private byte[] hook(String region, JsonNode hook, String indexId, String documentId, byte[] content,
                        ObjectNode attributes, String stage) {
        String bucket = hook.path("S3Bucket").asText();
        String key = indexId + "/" + stage + "-extraction/" + KendraStore.uuid() + "/"
                + documentId.replaceAll("[^A-Za-z0-9._-]", "_");
        try {
            s3.putObject(bucket, key, content, "application/octet-stream", Map.of());
        } catch (AwsException e) {
            throw new Failure(stage.equals("pre") ? "PreExtractionHookFailed" : "PostExtractionHookFailed",
                    "Unable to write to the hook's bucket " + bucket + ": " + e.getMessage());
        }
        ObjectNode event = mapper().createObjectNode();
        event.put("version", "v0").put("s3Bucket", bucket).put("s3ObjectKey", key);
        ArrayNode list = event.putObject("metadata").putArray("attributes");
        attributes.fields().forEachRemaining(e -> list.addObject().put("name", e.getKey())
                .set("value", lambdaValue(e.getValue())));
        String code = stage.equals("pre") ? "PreExtractionHookFailed" : "PostExtractionHookFailed";
        InvokeResult result;
        try {
            result = lambda.get().invoke(region, hook.path("LambdaArn").asText(),
                    event.toString().getBytes(StandardCharsets.UTF_8), InvocationType.RequestResponse);
        } catch (AwsException e) {
            throw new Failure(code, "Unable to invoke " + hook.path("LambdaArn").asText() + ": " + e.getMessage());
        }
        if (result.getFunctionError() != null) {
            throw new Failure(code, "The " + stage + "-extraction Lambda failed: "
                    + new String(result.getPayload(), StandardCharsets.UTF_8));
        }
        JsonNode response;
        try {
            response = mapper().readTree(result.getPayload());
        } catch (IOException e) {
            throw new Failure(code, "The " + stage + "-extraction Lambda's response is not JSON.");
        }
        for (JsonNode update : response.path("metadataUpdates")) {
            JsonNode value = update.path("value");
            String name = update.path("name").asText();
            if (value.isMissingNode() || value.isNull() || value.isEmpty()) {
                attributes.remove(name);
            } else {
                attributes.set(name, kendraValue(value));
            }
        }
        String next = response.path("s3ObjectKey").asText(null);
        if (next == null || next.isEmpty()) {
            throw new Failure(code, "The " + stage + "-extraction Lambda's response has no s3ObjectKey.");
        }
        try {
            return s3.getObject(bucket, next).getData();
        } catch (AwsException e) {
            throw new Failure(code, "Unable to read s3://" + bucket + "/" + next + ": " + e.getMessage());
        }
    }

    private ObjectNode lambdaValue(JsonNode value) {
        ObjectNode out = mapper().createObjectNode();
        if (value.has("StringValue")) {
            out.put("stringValue", value.get("StringValue").asText());
        } else if (value.has("StringListValue")) {
            out.set("stringListValue", value.get("StringListValue").deepCopy());
        } else if (value.has("LongValue")) {
            out.put("longValue", value.get("LongValue").asLong());
        } else if (value.has("DateValue")) {
            out.put("dateValue", Instant.ofEpochMilli((long) (value.get("DateValue").asDouble() * 1000))
                    .toString());
        }
        return out;
    }

    private ObjectNode kendraValue(JsonNode value) {
        ObjectNode out = mapper().createObjectNode();
        if (value.has("stringValue")) {
            out.put("StringValue", value.get("stringValue").asText());
        } else if (value.has("stringListValue")) {
            out.set("StringListValue", value.get("stringListValue").deepCopy());
        } else if (value.has("longValue")) {
            out.put("LongValue", value.get("longValue").asLong());
        } else if (value.has("dateValue")) {
            Double date = KendraSearch.dateOf(value.get("dateValue").asText());
            out.put("DateValue", date == null ? 0 : date);
        }
        return out;
    }

    /** Whether an enrichment condition holds for a document's attributes ({@code _document_body}: its text). */
    static boolean condition(JsonNode condition, ObjectNode attributes, String body) {
        String key = condition.path("ConditionDocumentAttributeKey").asText();
        String op = condition.path("Operator").asText();
        JsonNode actual = "_document_body".equals(key) && body != null
                ? KendraSearch.ObjectMapperHolder.MAPPER.createObjectNode().put("StringValue", body)
                : attributes.get(key);
        JsonNode wanted = condition.path("ConditionOnValue");
        switch (op) {
            case "Exists" -> {
                return actual != null;
            }
            case "NotExists" -> {
                return actual == null;
            }
            default -> {
            }
        }
        if (actual == null) {
            return "NotEquals".equals(op) || "NotContains".equals(op);
        }
        List<String> actualStrings = KendraSearch.stringValues(actual);
        List<String> wantedStrings = KendraSearch.stringValues(wanted);
        Double a = actual.has("LongValue") ? Double.valueOf(actual.get("LongValue").asDouble())
                : actual.has("DateValue") ? Double.valueOf(actual.get("DateValue").asDouble()) : null;
        Double w = wanted.has("LongValue") ? Double.valueOf(wanted.get("LongValue").asDouble())
                : wanted.has("DateValue") ? Double.valueOf(wanted.get("DateValue").asDouble()) : null;
        return switch (op) {
            case "Equals" -> a != null ? a.equals(w) : actualStrings.equals(wantedStrings);
            case "NotEquals" -> a != null ? !a.equals(w) : !actualStrings.equals(wantedStrings);
            case "Contains" -> wantedStrings.stream().allMatch(s -> actualStrings.stream().anyMatch(v ->
                    v.contains(s)));
            case "NotContains" -> wantedStrings.stream().noneMatch(s -> actualStrings.stream().anyMatch(v ->
                    v.contains(s)));
            case "BeginsWith" -> !wantedStrings.isEmpty() && actualStrings.stream().anyMatch(v ->
                    v.startsWith(wantedStrings.get(0)));
            case "GreaterThan" -> a != null && w != null && a > w;
            case "GreaterThanOrEquals" -> a != null && w != null && a >= w;
            case "LessThan" -> a != null && w != null && a < w;
            case "LessThanOrEquals" -> a != null && w != null && a <= w;
            default -> false;
        };
    }

    private static void checkType(String key, String type, JsonNode value) {
        boolean ok = switch (type) {
            case "STRING_VALUE" -> value.has("StringValue");
            case "STRING_LIST_VALUE" -> value.has("StringListValue");
            case "LONG_VALUE" -> value.has("LongValue");
            case "DATE_VALUE" -> value.has("DateValue");
            default -> false;
        };
        if (!ok) {
            throw validation("The document attribute " + key + " must be a " + type + ".");
        }
    }

    // ---------------------------------------------------------------- sync job metrics

    /** The running sync job a batch counts its documents against, or null when it names none. */
    ObjectNode syncJobTarget(String account, String region, String indexId, JsonNode target) {
        if (target == null || target.isNull()) {
            return null;
        }
        String sourceId = text(target, "DataSourceId");
        if (indexes.find(account, region, KendraStore.DATA_SOURCE, indexId + "/" + sourceId) == null) {
            throw KendraStore.notFound("Data source " + sourceId + " doesn't exist.");
        }
        String jobId = text(target, "DataSourceSyncJobId");
        ObjectNode job = null;
        for (ObjectNode candidate : store.list(account, region, KendraStore.SYNC_JOB, indexId + "/" + sourceId
                + "/")) {
            if (jobId == null ? "SYNCING".equals(candidate.path("Status").asText())
                    : jobId.equals(candidate.path("ExecutionId").asText())) {
                job = candidate;
            }
        }
        if (job == null || !Set.of("SYNCING", "SYNCING_INDEXING").contains(job.path("Status").asText())) {
            throw validation("The data source " + sourceId + " has no running sync job"
                    + (jobId == null ? "." : " " + jobId + "."));
        }
        return job;
    }

    static void count(ObjectNode job, String metric) {
        ObjectNode metrics = job.has("Metrics") ? (ObjectNode) job.get("Metrics") : job.putObject("Metrics");
        metrics.put(metric, String.valueOf(Long.parseLong(metrics.path(metric).asText("0")) + 1));
    }

    void saveJob(String account, String region, ObjectNode job) {
        store.put(account, region, KendraStore.SYNC_JOB, meta(job).path("path").asText(), job);
    }

    // ---------------------------------------------------------------- reading

    /** The documents of an index that are indexed and searchable now. */
    List<ObjectNode> searchable(String account, String region, String indexId) {
        long now = System.currentTimeMillis();
        List<ObjectNode> found = new ArrayList<>();
        for (ObjectNode document : store.documents(account, region, indexId)) {
            if (meta(document).path("readyAt").asLong() <= now && !meta(document).has("failure")) {
                found.add(document);
            }
        }
        return found;
    }

    /** Reads an S3 object with the role Kendra is given. */
    byte[] read(String bucket, String key) {
        try {
            return s3.getObject(bucket, key).getData();
        } catch (AwsException e) {
            throw validation("Unable to read s3://" + bucket + "/" + key + ": " + e.getMessage());
        }
    }

    /** Removes the documents a data source put. */
    void deleteBySource(String account, String region, String indexId, String sourceId) {
        store.deleteDocuments(account, region, indexId, d -> sourceId.equals(meta(d).path("dataSourceId")
                .asText(null)));
    }

    /** The ids of the documents a data source put. */
    Set<String> idsBySource(String account, String region, String indexId, String sourceId) {
        Set<String> ids = new HashSet<>();
        for (ObjectNode document : store.documents(account, region, indexId)) {
            if (sourceId.equals(meta(document).path("dataSourceId").asText(null))) {
                ids.add(document.path("Id").asText());
            }
        }
        return ids;
    }

    static Iterator<String> names(JsonNode node) {
        return node.fieldNames();
    }
}
