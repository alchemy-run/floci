package io.floci.aws.services.bedrockagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bedrock knowledge bases, their data sources, ingestion jobs, documents, and
 * the index {@code Retrieve} searches.
 * <p>
 * No embedding model runs. An ingestion job reads the data source's S3 bucket
 * (its inclusion prefixes, when set) and splits each text object into chunks
 * of about {@link #CHUNK_WORDS} words; {@code IngestKnowledgeBaseDocuments}
 * indexes inline text or an S3 object the same way. {@link #search} scores
 * chunks by the cosine similarity of their word counts with the query's, so
 * the chunks that share the query's words rank first. A knowledge base is
 * {@code ACTIVE} and a data source {@code AVAILABLE} as soon as they are created,
 * and an ingestion job is {@code COMPLETE} as soon as it starts.
 */
@ApplicationScoped
public class BedrockKnowledgeBaseService {
    static final int CHUNK_WORDS = 300;
    private static final Pattern S3_BUCKET_ARN = Pattern.compile("^arn:[^:]+:s3:::([^/]+)$");
    private static final Pattern S3_URI = Pattern.compile("^s3://([^/]+)/(.*)$");

    private final StorageBackend<String, ObjectNode> knowledgeBases;
    private final RegionResolver regionResolver;
    private final S3Service s3Service;
    private final BedrockAgentService agents;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockKnowledgeBaseService(StorageFactory storageFactory, RegionResolver regionResolver,
                                       S3Service s3Service, BedrockAgentService agents, ObjectMapper objectMapper) {
        this.knowledgeBases = storageFactory.create("bedrock-agent", "bedrock-knowledge-bases.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regionResolver = regionResolver;
        this.s3Service = s3Service;
        this.agents = agents;
        this.objectMapper = objectMapper;
    }

    // ── Knowledge bases ────────────────────────────────────────────────────

    public synchronized ObjectNode createKnowledgeBase(JsonNode request, String region) {
        String name = BedrockAgentService.requireName(request, "name");
        BedrockAgentService.requireText(request, "roleArn");
        JsonNode configuration = request.get("knowledgeBaseConfiguration");
        if (configuration == null || !configuration.isObject() || !configuration.hasNonNull("type")) {
            throw BedrockAgentService.validation("knowledgeBaseConfiguration.type is a required field.");
        }
        if ("VECTOR".equals(configuration.path("type").asText())
                && (request.get("storageConfiguration") == null
                || !request.path("storageConfiguration").hasNonNull("type"))) {
            throw BedrockAgentService.validation("A VECTOR knowledge base needs a storageConfiguration.");
        }
        for (ObjectNode stored : knowledgeBases.scan(k -> k.startsWith(region + "/"))) {
            if (name.equals(stored.path("knowledgeBase").path("name").asText())) {
                throw new AwsException("ConflictException", "A knowledge base named " + name + " already exists.", 409);
            }
        }
        String id = agents.newId();
        String now = BedrockAgentService.now();
        ObjectNode kb = objectMapper.createObjectNode();
        kb.put("knowledgeBaseId", id);
        kb.put("name", name);
        kb.put("knowledgeBaseArn", regionResolver.buildArn("bedrock", region, "knowledge-base/" + id));
        BedrockAgentService.copy(request, kb, "description", "roleArn", "knowledgeBaseConfiguration",
                "storageConfiguration");
        kb.put("status", "ACTIVE");
        kb.put("createdAt", now);
        kb.put("updatedAt", now);
        ObjectNode stored = objectMapper.createObjectNode();
        stored.set("knowledgeBase", kb);
        stored.putObject("dataSources");
        knowledgeBases.put(key(region, id), stored);
        if (request.get("tags") != null && request.get("tags").isObject()) {
            Map<String, String> values = new LinkedHashMap<>();
            request.get("tags").fields().forEachRemaining(t -> values.put(t.getKey(), t.getValue().asText()));
            agents.tag(kb.path("knowledgeBaseArn").asText(), values);
        }
        ObjectNode response = kb.deepCopy();
        response.put("status", "CREATING");
        return wrap("knowledgeBase", response);
    }

    public ObjectNode getKnowledgeBase(String id, String region) {
        return wrap("knowledgeBase", requireKnowledgeBase(id, region).get("knowledgeBase"));
    }

    public synchronized ObjectNode updateKnowledgeBase(String id, JsonNode request, String region) {
        ObjectNode stored = requireKnowledgeBase(id, region);
        ObjectNode kb = (ObjectNode) stored.get("knowledgeBase");
        kb.put("name", BedrockAgentService.requireName(request, "name"));
        kb.put("roleArn", BedrockAgentService.requireText(request, "roleArn"));
        if (request.has("description")) {
            kb.put("description", request.get("description").asText());
        } else {
            kb.remove("description");
        }
        BedrockAgentService.copy(request, kb, "knowledgeBaseConfiguration", "storageConfiguration");
        kb.put("updatedAt", BedrockAgentService.now());
        knowledgeBases.put(key(region, id), stored);
        ObjectNode response = kb.deepCopy();
        response.put("status", "UPDATING");
        return wrap("knowledgeBase", response);
    }

    public synchronized ObjectNode deleteKnowledgeBase(String id, String region) {
        ObjectNode stored = requireKnowledgeBase(id, region);
        knowledgeBases.delete(key(region, id));
        agents.deleteTags(stored.path("knowledgeBase").path("knowledgeBaseArn").asText());
        ObjectNode response = objectMapper.createObjectNode();
        response.put("knowledgeBaseId", id);
        response.put("status", "DELETING");
        return response;
    }

    public ObjectNode listKnowledgeBases(JsonNode request, String region) {
        List<ObjectNode> all = knowledgeBases.scan(k -> k.startsWith(region + "/")).stream()
                .sorted(Comparator.comparing((ObjectNode s) -> s.path("knowledgeBase").path("createdAt").asText()))
                .toList();
        BedrockAgentService.Page page = BedrockAgentService.page(all.size(), request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("knowledgeBaseSummaries");
        for (ObjectNode stored : all.subList(page.start(), page.end())) {
            JsonNode kb = stored.get("knowledgeBase");
            ObjectNode summary = list.addObject();
            summary.put("knowledgeBaseId", kb.path("knowledgeBaseId").asText());
            summary.put("name", kb.path("name").asText());
            BedrockAgentService.copy(kb, summary, "description");
            summary.put("status", kb.path("status").asText());
            summary.put("updatedAt", kb.path("updatedAt").asText());
        }
        page.next(response);
        return response;
    }

    public boolean exists(String id, String region) {
        return id != null && knowledgeBases.get(key(region, id)).isPresent();
    }

    /** Whether the ARN names a knowledge base that exists, for tagging. */
    public boolean owns(String arn, String region) {
        return knowledgeBases.scan(k -> k.startsWith(region + "/")).stream()
                .anyMatch(s -> arn.equals(s.path("knowledgeBase").path("knowledgeBaseArn").asText()));
    }

    // ── Data sources ───────────────────────────────────────────────────────

    public synchronized ObjectNode createDataSource(String kbId, JsonNode request, String region) {
        ObjectNode stored = requireKnowledgeBase(kbId, region);
        String name = BedrockAgentService.requireName(request, "name");
        JsonNode configuration = request.get("dataSourceConfiguration");
        if (configuration == null || !configuration.hasNonNull("type")) {
            throw BedrockAgentService.validation("dataSourceConfiguration.type is a required field.");
        }
        if ("S3".equals(configuration.path("type").asText())
                && !S3_BUCKET_ARN.matcher(configuration.path("s3Configuration").path("bucketArn").asText("")).matches()) {
            throw BedrockAgentService.validation("dataSourceConfiguration.s3Configuration.bucketArn must be an S3 "
                    + "bucket ARN.");
        }
        for (JsonNode source : stored.path("dataSources")) {
            if (name.equals(source.path("dataSource").path("name").asText())) {
                throw new AwsException("ConflictException", "A data source named " + name + " already exists.", 409);
            }
        }
        String id = agents.newId();
        String now = BedrockAgentService.now();
        ObjectNode source = objectMapper.createObjectNode();
        source.put("knowledgeBaseId", kbId);
        source.put("dataSourceId", id);
        source.put("name", name);
        source.put("status", "AVAILABLE");
        BedrockAgentService.copy(request, source, "description", "dataSourceConfiguration",
                "serverSideEncryptionConfiguration", "vectorIngestionConfiguration");
        source.put("dataDeletionPolicy", BedrockAgentService.text(request, "dataDeletionPolicy") == null
                ? "DELETE" : BedrockAgentService.text(request, "dataDeletionPolicy"));
        source.put("createdAt", now);
        source.put("updatedAt", now);
        ObjectNode entry = objectMapper.createObjectNode();
        entry.set("dataSource", source);
        entry.putObject("jobs");
        entry.putObject("documents");
        ((ObjectNode) stored.get("dataSources")).set(id, entry);
        knowledgeBases.put(key(region, kbId), stored);
        return wrap("dataSource", source);
    }

    public ObjectNode getDataSource(String kbId, String dsId, String region) {
        return wrap("dataSource", requireDataSource(requireKnowledgeBase(kbId, region), dsId).get("dataSource"));
    }

    public synchronized ObjectNode updateDataSource(String kbId, String dsId, JsonNode request, String region) {
        ObjectNode stored = requireKnowledgeBase(kbId, region);
        ObjectNode source = (ObjectNode) requireDataSource(stored, dsId).get("dataSource");
        source.put("name", BedrockAgentService.requireName(request, "name"));
        if (request.get("dataSourceConfiguration") == null) {
            throw BedrockAgentService.validation("dataSourceConfiguration is a required field.");
        }
        if (request.has("description")) {
            source.put("description", request.get("description").asText());
        } else {
            source.remove("description");
        }
        BedrockAgentService.copy(request, source, "dataSourceConfiguration", "serverSideEncryptionConfiguration",
                "vectorIngestionConfiguration", "dataDeletionPolicy");
        source.put("updatedAt", BedrockAgentService.now());
        knowledgeBases.put(key(region, kbId), stored);
        return wrap("dataSource", source);
    }

    public synchronized ObjectNode deleteDataSource(String kbId, String dsId, String region) {
        ObjectNode stored = requireKnowledgeBase(kbId, region);
        requireDataSource(stored, dsId);
        ((ObjectNode) stored.get("dataSources")).remove(dsId);
        knowledgeBases.put(key(region, kbId), stored);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("knowledgeBaseId", kbId);
        response.put("dataSourceId", dsId);
        response.put("status", "DELETING");
        return response;
    }

    public ObjectNode listDataSources(String kbId, JsonNode request, String region) {
        ObjectNode stored = requireKnowledgeBase(kbId, region);
        List<JsonNode> sources = new ArrayList<>();
        stored.path("dataSources").forEach(entry -> sources.add(entry.get("dataSource")));
        BedrockAgentService.Page page = BedrockAgentService.page(sources.size(), request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("dataSourceSummaries");
        for (JsonNode source : sources.subList(page.start(), page.end())) {
            ObjectNode summary = list.addObject();
            for (String field : List.of("knowledgeBaseId", "dataSourceId", "name", "status", "description",
                    "updatedAt")) {
                if (source.has(field)) {
                    summary.set(field, source.get(field).deepCopy());
                }
            }
        }
        page.next(response);
        return response;
    }

    // ── Ingestion jobs ─────────────────────────────────────────────────────

    /** Reads the data source's S3 objects into the index; complete at once. */
    public synchronized ObjectNode startIngestionJob(String kbId, String dsId, JsonNode request, String region) {
        ObjectNode stored = requireKnowledgeBase(kbId, region);
        ObjectNode entry = requireDataSource(stored, dsId);
        JsonNode configuration = entry.path("dataSource").path("dataSourceConfiguration");
        ObjectNode documents = (ObjectNode) entry.get("documents");
        int scanned = 0;
        int indexed = 0;
        int failed = 0;
        int deleted = 0;
        ArrayNode failures = objectMapper.createArrayNode();
        if ("S3".equals(configuration.path("type").asText())) {
            JsonNode s3 = configuration.path("s3Configuration");
            Matcher bucket = S3_BUCKET_ARN.matcher(s3.path("bucketArn").asText(""));
            bucket.matches();
            List<String> prefixes = new ArrayList<>();
            s3.path("inclusionPrefixes").forEach(p -> prefixes.add(p.asText()));
            if (prefixes.isEmpty()) {
                prefixes.add("");
            }
            List<String> seen = new ArrayList<>();
            try {
                for (String prefix : prefixes) {
                    for (S3Object object : s3Service.listObjects(bucket.group(1), prefix, null, 1000)) {
                        if (object.getKey().endsWith("/") || object.getKey().endsWith(".metadata.json")) {
                            continue;
                        }
                        scanned++;
                        String uri = "s3://" + bucket.group(1) + "/" + object.getKey();
                        seen.add(uri);
                        byte[] data = s3Service.getObject(bucket.group(1), object.getKey()).getData();
                        documents.set(uri, document(kbId, dsId, "S3", uri, new String(data, StandardCharsets.UTF_8)));
                        indexed++;
                    }
                }
                List<String> stale = new ArrayList<>();
                documents.fieldNames().forEachRemaining(uri -> {
                    if (uri.startsWith("s3://") && !seen.contains(uri)) {
                        stale.add(uri);
                    }
                });
                stale.forEach(documents::remove);
                deleted = stale.size();
            } catch (AwsException e) {
                failed++;
                failures.add("Unable to read " + s3.path("bucketArn").asText() + ": " + e.getMessage());
            }
        }
        String now = BedrockAgentService.now();
        String jobId = agents.newId();
        ObjectNode job = objectMapper.createObjectNode();
        job.put("knowledgeBaseId", kbId);
        job.put("dataSourceId", dsId);
        job.put("ingestionJobId", jobId);
        BedrockAgentService.copy(request, job, "description");
        job.put("status", failed > 0 ? "FAILED" : "COMPLETE");
        ObjectNode statistics = job.putObject("statistics");
        statistics.put("numberOfDocumentsScanned", scanned);
        statistics.put("numberOfMetadataDocumentsScanned", 0);
        statistics.put("numberOfNewDocumentsIndexed", indexed);
        statistics.put("numberOfModifiedDocumentsIndexed", 0);
        statistics.put("numberOfMetadataDocumentsModified", 0);
        statistics.put("numberOfDocumentsDeleted", deleted);
        statistics.put("numberOfDocumentsFailed", failed);
        statistics.put("numberOfDocumentsSkipped", 0);
        if (!failures.isEmpty()) {
            job.set("failureReasons", failures);
        }
        job.put("startedAt", now);
        job.put("updatedAt", now);
        ((ObjectNode) entry.get("jobs")).set(jobId, job);
        knowledgeBases.put(key(region, kbId), stored);
        ObjectNode response = job.deepCopy();
        response.put("status", "STARTING");
        return wrap("ingestionJob", response);
    }

    public ObjectNode getIngestionJob(String kbId, String dsId, String jobId, String region) {
        JsonNode job = requireDataSource(requireKnowledgeBase(kbId, region), dsId).path("jobs").get(jobId);
        if (job == null) {
            throw new AwsException("ResourceNotFoundException", "Ingestion job " + jobId + " was not found.", 404);
        }
        return wrap("ingestionJob", job);
    }

    public ObjectNode listIngestionJobs(String kbId, String dsId, JsonNode request, String region) {
        ObjectNode entry = requireDataSource(requireKnowledgeBase(kbId, region), dsId);
        List<JsonNode> jobs = new ArrayList<>();
        entry.path("jobs").forEach(jobs::add);
        BedrockAgentService.Page page = BedrockAgentService.page(jobs.size(), request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ingestionJobSummaries");
        jobs.subList(page.start(), page.end()).forEach(job -> list.add(job.deepCopy()));
        page.next(response);
        return response;
    }

    /** Jobs finish when they start, so there is never one to stop. */
    public ObjectNode stopIngestionJob(String kbId, String dsId, String jobId, String region) {
        JsonNode job = requireDataSource(requireKnowledgeBase(kbId, region), dsId).path("jobs").get(jobId);
        if (job == null) {
            throw new AwsException("ResourceNotFoundException", "Ingestion job " + jobId + " was not found.", 404);
        }
        throw new AwsException("ConflictException", "Ingestion job " + jobId + " is " + job.path("status").asText()
                + " and can't be stopped.", 409);
    }

    // ── Documents ──────────────────────────────────────────────────────────

    public synchronized ObjectNode ingestDocuments(String kbId, String dsId, JsonNode request, String region) {
        ObjectNode stored = requireKnowledgeBase(kbId, region);
        ObjectNode entry = requireDataSource(stored, dsId);
        JsonNode list = request.get("documents");
        if (list == null || !list.isArray() || list.isEmpty() || list.size() > 25) {
            throw BedrockAgentService.validation("documents must contain between 1 and 25 documents.");
        }
        ObjectNode documents = (ObjectNode) entry.get("documents");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("documentDetails");
        for (JsonNode document : list) {
            JsonNode content = document.path("content");
            String type = content.path("dataSourceType").asText();
            String identifier;
            String text;
            if ("CUSTOM".equals(type)) {
                JsonNode custom = content.path("custom");
                identifier = custom.path("customDocumentIdentifier").path("id").asText(null);
                if (identifier == null) {
                    throw BedrockAgentService.validation("custom.customDocumentIdentifier.id is a required field.");
                }
                text = "S3_LOCATION".equals(custom.path("sourceType").asText())
                        ? readS3(custom.path("s3Location").path("uri").asText())
                        : custom.path("inlineContent").path("textContent").path("data").asText("");
            } else if ("S3".equals(type)) {
                identifier = content.path("s3").path("s3Location").path("uri").asText(null);
                if (identifier == null) {
                    throw BedrockAgentService.validation("s3.s3Location.uri is a required field.");
                }
                text = readS3(identifier);
            } else {
                throw BedrockAgentService.validation("content.dataSourceType must be CUSTOM or S3.");
            }
            ObjectNode doc = document(kbId, dsId, type, identifier, text);
            if (document.path("metadata").isObject()) {
                doc.set("metadata", document.get("metadata").deepCopy());
            }
            documents.set(identifier, doc);
            details.add(detail(doc));
        }
        knowledgeBases.put(key(region, kbId), stored);
        return response;
    }

    public ObjectNode getDocuments(String kbId, String dsId, JsonNode request, String region) {
        ObjectNode entry = requireDataSource(requireKnowledgeBase(kbId, region), dsId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("documentDetails");
        for (JsonNode identifier : request.path("documentIdentifiers")) {
            String id = identifierOf(identifier);
            JsonNode doc = entry.path("documents").get(id);
            if (doc != null) {
                details.add(detail(doc));
            } else {
                ObjectNode missing = details.addObject();
                missing.put("knowledgeBaseId", kbId);
                missing.put("dataSourceId", dsId);
                missing.put("status", "NOT_FOUND");
                missing.set("identifier", identifier.deepCopy());
            }
        }
        return response;
    }

    public synchronized ObjectNode deleteDocuments(String kbId, String dsId, JsonNode request, String region) {
        ObjectNode stored = requireKnowledgeBase(kbId, region);
        ObjectNode entry = requireDataSource(stored, dsId);
        ObjectNode documents = (ObjectNode) entry.get("documents");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("documentDetails");
        for (JsonNode identifier : request.path("documentIdentifiers")) {
            String id = identifierOf(identifier);
            JsonNode removed = documents.remove(id);
            ObjectNode detail = details.addObject();
            detail.put("knowledgeBaseId", kbId);
            detail.put("dataSourceId", dsId);
            detail.put("status", removed == null ? "NOT_FOUND" : "DELETING");
            detail.set("identifier", identifier.deepCopy());
            detail.put("updatedAt", BedrockAgentService.now());
        }
        knowledgeBases.put(key(region, kbId), stored);
        return response;
    }

    public ObjectNode listDocuments(String kbId, String dsId, JsonNode request, String region) {
        ObjectNode entry = requireDataSource(requireKnowledgeBase(kbId, region), dsId);
        List<JsonNode> documents = new ArrayList<>();
        entry.path("documents").forEach(documents::add);
        BedrockAgentService.Page page = BedrockAgentService.page(documents.size(), request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("documentDetails");
        documents.subList(page.start(), page.end()).forEach(doc -> details.add(detail(doc)));
        page.next(response);
        return response;
    }

    private ObjectNode document(String kbId, String dsId, String type, String identifier, String text) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("knowledgeBaseId", kbId);
        doc.put("dataSourceId", dsId);
        doc.put("type", type);
        doc.put("identifier", identifier);
        doc.put("updatedAt", BedrockAgentService.now());
        ArrayNode chunks = doc.putArray("chunks");
        List<String> words = List.of(text.trim().split("\\s+"));
        for (int start = 0; start < words.size(); start += CHUNK_WORDS) {
            String chunk = String.join(" ", words.subList(start, Math.min(words.size(), start + CHUNK_WORDS)));
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
        }
        return doc;
    }

    private ObjectNode detail(JsonNode doc) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("knowledgeBaseId", doc.path("knowledgeBaseId").asText());
        detail.put("dataSourceId", doc.path("dataSourceId").asText());
        detail.put("status", "INDEXED");
        ObjectNode identifier = detail.putObject("identifier");
        if ("S3".equals(doc.path("type").asText())) {
            identifier.put("dataSourceType", "S3");
            identifier.putObject("s3").put("uri", doc.path("identifier").asText());
        } else {
            identifier.put("dataSourceType", "CUSTOM");
            identifier.putObject("custom").put("id", doc.path("identifier").asText());
        }
        detail.put("updatedAt", doc.path("updatedAt").asText());
        return detail;
    }

    private static String identifierOf(JsonNode identifier) {
        return "S3".equals(identifier.path("dataSourceType").asText())
                ? identifier.path("s3").path("uri").asText()
                : identifier.path("custom").path("id").asText();
    }

    private String readS3(String uri) {
        Matcher matcher = S3_URI.matcher(uri);
        if (!matcher.matches()) {
            throw BedrockAgentService.validation("Invalid S3 URI: " + uri);
        }
        try {
            return new String(s3Service.getObject(matcher.group(1), matcher.group(2)).getData(),
                    StandardCharsets.UTF_8);
        } catch (AwsException e) {
            throw BedrockAgentService.validation("Unable to read " + uri + ": " + e.getMessage());
        }
    }

    // ── Search ─────────────────────────────────────────────────────────────

    /** A chunk of an indexed document and its relevance to a query. */
    public record Hit(String text, String type, String identifier, String dataSourceId, double score,
                      JsonNode metadata) {}

    /** The knowledge base's chunks most relevant to {@code query}, best first. */
    public List<Hit> search(String kbId, String query, int limit, String region) {
        ObjectNode stored = requireKnowledgeBase(kbId, region);
        List<Hit> hits = new ArrayList<>();
        stored.path("dataSources").forEach(entry -> entry.path("documents").forEach(doc -> {
            for (JsonNode chunk : doc.path("chunks")) {
                double score = Relevance.score(query, chunk.asText());
                if (score > 0) {
                    hits.add(new Hit(chunk.asText(), doc.path("type").asText(), doc.path("identifier").asText(),
                            doc.path("dataSourceId").asText(), score, doc.get("metadata")));
                }
            }
        }));
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.subList(0, Math.min(limit, hits.size()));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    ObjectNode requireKnowledgeBase(String id, String region) {
        if (id == null) {
            throw new AwsException("ResourceNotFoundException", "Knowledge base null was not found.", 404);
        }
        return knowledgeBases.get(key(region, id)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Knowledge base " + id + " was not found.", 404));
    }

    private static ObjectNode requireDataSource(ObjectNode stored, String dsId) {
        JsonNode entry = dsId == null ? null : stored.path("dataSources").get(dsId);
        if (entry == null) {
            throw new AwsException("ResourceNotFoundException", "Data source " + dsId + " was not found.", 404);
        }
        return (ObjectNode) entry;
    }

    private ObjectNode wrap(String key, JsonNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(key, value.deepCopy());
        return response;
    }

    private static String key(String region, String id) {
        return region + "/" + id;
    }
}
