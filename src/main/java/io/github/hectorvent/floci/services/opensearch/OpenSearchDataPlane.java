package io.github.hectorvent.floci.services.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process OpenSearch REST data plane (index / get / search / bulk / …).
 *
 * <p>Mock-mode domains — and real-mode domains addressed via their advertised
 * {@code search-{name}-{id}.{region}.es.amazonaws.com} endpoint — store documents
 * here so Alchemy's {@code DomainRead}/{@code DomainWrite} bindings can round-trip
 * without a Docker cluster.
 */
@ApplicationScoped
public class OpenSearchDataPlane {

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, DomainIndex> domains = new ConcurrentHashMap<>();

    public OpenSearchDataPlane() {
        this(new ObjectMapper());
    }

    OpenSearchDataPlane(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void drop(String domainName) {
        if (domainName != null) {
            domains.remove(domainName);
        }
    }

    public ObjectNode indexDocument(String domainName, String index, String id, JsonNode source) {
        StoredDocument stored = indexOf(domainName).put(index, id, source == null ? objectMapper.createObjectNode() : source);
        return writeResponse(stored, stored.created ? "created" : "updated");
    }

    public String allocateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    public GetResult getDocument(String domainName, String index, String id) {
        StoredDocument stored = indexOf(domainName).get(index, id);
        if (stored == null) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("_index", index);
            body.put("_id", id);
            body.put("found", false);
            return new GetResult(false, body);
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("_index", stored.index);
        body.put("_id", stored.id);
        body.put("_version", stored.version.get());
        body.put("found", true);
        body.set("_source", stored.source);
        return new GetResult(true, body);
    }

    public boolean existsDocument(String domainName, String index, String id) {
        return indexOf(domainName).get(index, id) != null;
    }

    public ObjectNode deleteDocument(String domainName, String index, String id) {
        StoredDocument stored = indexOf(domainName).remove(index, id);
        if (stored == null) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("_index", index);
            body.put("_id", id);
            body.put("result", "not_found");
            body.put("found", false);
            return body;
        }
        return writeResponse(stored, "deleted");
    }

    public UpdateResult updateDocument(String domainName, String index, String id, JsonNode body) {
        StoredDocument stored = indexOf(domainName).get(index, id);
        if (stored == null) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("error", "document_missing_exception");
            error.put("_index", index);
            error.put("_id", id);
            return new UpdateResult(404, error);
        }
        JsonNode patch = body != null && body.has("doc") ? body.get("doc") : body;
        boolean changed = stored.merge(patch);
        stored.version.incrementAndGet();
        return new UpdateResult(200, writeResponse(stored, changed ? "updated" : "noop"));
    }

    public ObjectNode bulk(String domainName, String ndjson) {
        ArrayNode items = objectMapper.createArrayNode();
        boolean errors = false;
        List<String> lines = splitNdjson(ndjson);
        int i = 0;
        while (i < lines.size()) {
            JsonNode actionLine;
            try {
                actionLine = objectMapper.readTree(lines.get(i));
            } catch (Exception e) {
                errors = true;
                items.add(bulkItem("index", "_index", "", 400, "error"));
                i++;
                continue;
            }
            i++;
            Iterator<Map.Entry<String, JsonNode>> fields = actionLine.fields();
            if (!fields.hasNext()) {
                continue;
            }
            Map.Entry<String, JsonNode> action = fields.next();
            String op = action.getKey();
            JsonNode meta = action.getValue();
            String index = text(meta, "_index");
            String id = text(meta, "_id");
            if ("delete".equals(op)) {
                ObjectNode deleted = deleteDocument(domainName, index, id);
                boolean missing = "not_found".equals(deleted.path("result").asText());
                items.add(bulkItem("delete", index, id, missing ? 404 : 200, deleted.path("result").asText()));
                continue;
            }
            JsonNode source = objectMapper.createObjectNode();
            if (i < lines.size()) {
                try {
                    source = objectMapper.readTree(lines.get(i));
                } catch (Exception ignored) {
                    source = objectMapper.createObjectNode();
                }
                i++;
            }
            if (id == null || id.isBlank()) {
                id = allocateId();
            }
            if ("update".equals(op)) {
                UpdateResult updated = updateDocument(domainName, index, id, source);
                if (updated.status() != 200) {
                    errors = true;
                }
                items.add(bulkItem("update", index, id, updated.status(),
                        updated.body().path("result").asText("error")));
            } else {
                ObjectNode indexed = indexDocument(domainName, index, id, source);
                items.add(bulkItem(op, index, id,
                        "created".equals(indexed.path("result").asText()) ? 201 : 200,
                        indexed.path("result").asText()));
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("took", 1);
        response.put("errors", errors);
        response.set("items", items);
        return response;
    }

    public ObjectNode search(String domainName, String index, JsonNode sourceQuery) {
        List<StoredDocument> matches = indexOf(domainName).search(index, sourceQuery);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("took", 1);
        response.put("timed_out", false);
        ObjectNode hits = response.putObject("hits");
        ObjectNode total = hits.putObject("total");
        total.put("value", matches.size());
        total.put("relation", "eq");
        hits.put("max_score", matches.isEmpty() ? 0 : 1.0);
        ArrayNode hitArray = hits.putArray("hits");
        for (StoredDocument doc : matches) {
            ObjectNode hit = objectMapper.createObjectNode();
            hit.put("_index", doc.index);
            hit.put("_id", doc.id);
            hit.put("_score", 1.0);
            hit.set("_source", doc.source);
            hitArray.add(hit);
        }
        return response;
    }

    public ObjectNode count(String domainName, String index, JsonNode sourceQuery) {
        List<StoredDocument> matches = indexOf(domainName).search(index, sourceQuery);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("count", matches.size());
        return response;
    }

    public ObjectNode clusterHealth(String domainName) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("cluster_name", domainName);
        body.put("status", "green");
        body.put("timed_out", false);
        body.put("number_of_nodes", 1);
        body.put("number_of_data_nodes", 1);
        body.put("active_shards", 1);
        return body;
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }

    private DomainIndex indexOf(String domainName) {
        return domains.computeIfAbsent(domainName, ignored -> new DomainIndex());
    }

    private ObjectNode writeResponse(StoredDocument stored, String result) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("_index", stored.index);
        body.put("_id", stored.id);
        body.put("_version", stored.version.get());
        body.put("result", result);
        ObjectNode shards = body.putObject("_shards");
        shards.put("total", 1);
        shards.put("successful", 1);
        shards.put("failed", 0);
        return body;
    }

    private ObjectNode bulkItem(String op, String index, String id, int status, String result) {
        ObjectNode wrapper = objectMapper.createObjectNode();
        ObjectNode body = wrapper.putObject(op == null || op.isBlank() ? "index" : op);
        body.put("_index", index == null ? "" : index);
        body.put("_id", id == null ? "" : id);
        body.put("status", status);
        body.put("result", result);
        return wrapper;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode()) {
            return null;
        }
        return node.path(field).asText(null);
    }

    private static List<String> splitNdjson(String ndjson) {
        List<String> lines = new ArrayList<>();
        if (ndjson == null || ndjson.isBlank()) {
            return lines;
        }
        for (String line : ndjson.split("\n", -1)) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    public record GetResult(boolean found, ObjectNode body) {
    }

    public record UpdateResult(int status, ObjectNode body) {
    }

    private static final class StoredDocument {
        final String index;
        final String id;
        final AtomicInteger version;
        final ObjectNode source;
        final boolean created;

        StoredDocument(String index, String id, ObjectNode source, int version, boolean created) {
            this.index = index;
            this.id = id;
            this.source = source;
            this.version = new AtomicInteger(version);
            this.created = created;
        }

        boolean merge(JsonNode patch) {
            if (patch == null || !patch.isObject()) {
                return false;
            }
            boolean changed = false;
            Iterator<Map.Entry<String, JsonNode>> fields = patch.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode previous = source.get(field.getKey());
                if (previous == null || !previous.equals(field.getValue())) {
                    source.set(field.getKey(), field.getValue());
                    changed = true;
                }
            }
            return changed;
        }
    }

    private static final class DomainIndex {
        private final ConcurrentHashMap<String, ConcurrentHashMap<String, StoredDocument>> indices =
                new ConcurrentHashMap<>();

        StoredDocument put(String index, String id, JsonNode source) {
            ConcurrentHashMap<String, StoredDocument> docs =
                    indices.computeIfAbsent(index, ignored -> new ConcurrentHashMap<>());
            ObjectNode copy = copyObject(source);
            StoredDocument existing = docs.get(id);
            if (existing == null) {
                StoredDocument created = new StoredDocument(index, id, copy, 1, true);
                docs.put(id, created);
                return created;
            }
            existing.source.removeAll();
            existing.source.setAll(copy);
            existing.version.incrementAndGet();
            return new StoredDocument(index, id, existing.source, existing.version.get(), false);
        }

        private static ObjectNode copyObject(JsonNode source) {
            if (source != null && source.isObject()) {
                return (ObjectNode) source.deepCopy();
            }
            return new ObjectMapper().createObjectNode();
        }

        StoredDocument get(String index, String id) {
            ConcurrentHashMap<String, StoredDocument> docs = indices.get(index);
            if (docs == null) {
                return null;
            }
            return docs.get(id);
        }

        StoredDocument remove(String index, String id) {
            ConcurrentHashMap<String, StoredDocument> docs = indices.get(index);
            if (docs == null) {
                return null;
            }
            return docs.remove(id);
        }

        List<StoredDocument> search(String index, JsonNode sourceQuery) {
            List<StoredDocument> matches = new ArrayList<>();
            if (index != null && !index.isBlank()) {
                ConcurrentHashMap<String, StoredDocument> docs = indices.get(index);
                if (docs != null) {
                    docs.values().forEach(doc -> {
                        if (matchesQuery(doc, sourceQuery)) {
                            matches.add(doc);
                        }
                    });
                }
                return matches;
            }
            indices.values().forEach(docs -> docs.values().forEach(doc -> {
                if (matchesQuery(doc, sourceQuery)) {
                    matches.add(doc);
                }
            }));
            return matches;
        }

        private static boolean matchesQuery(StoredDocument doc, JsonNode sourceQuery) {
            if (sourceQuery == null || sourceQuery.isNull() || sourceQuery.isMissingNode()) {
                return true;
            }
            JsonNode query = sourceQuery.has("query") ? sourceQuery.get("query") : sourceQuery;
            if (query == null || query.isNull() || query.isMissingNode() || query.has("match_all")) {
                return true;
            }
            JsonNode match = query.get("match");
            if (match == null || !match.isObject() || match.isEmpty()) {
                return true;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = match.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String expected = queryText(field.getValue());
                JsonNode actual = doc.source.get(field.getKey());
                if (actual == null || actual.isNull()) {
                    return false;
                }
                if (!actual.asText("").toLowerCase(Locale.ROOT)
                        .contains(expected.toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
            return true;
        }

        private static String queryText(JsonNode value) {
            if (value == null || value.isNull()) {
                return "";
            }
            if (value.isObject() && value.has("query")) {
                return value.get("query").asText("");
            }
            return value.asText("");
        }
    }
}
