package io.floci.aws.services.omics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * HealthOmics storage. Reference stores hold FASTA references imported from S3, each with its samtools index. Sequence
 * stores hold read sets (FASTQ, BAM, uBAM or CRAM) imported from S3 or uploaded in parts, counted read by read, with an
 * ETag in the store's algorithm family. References and read sets are read back in 100 MiB parts, exported to S3, and
 * archived after {@code archive-after-days} without a read until an activation job restores them.
 */
@ApplicationScoped
public class OmicsStorage {

    private static final Set<String> RUNNING = Set.of("SUBMITTED", "IN_PROGRESS");

    private final OmicsStore store;
    private final OmicsCommon common;
    private final ObjectMapper mapper;

    @Inject
    public OmicsStorage(OmicsStore store, OmicsCommon common) {
        this.store = store;
        this.common = common;
        this.mapper = store.mapper();
    }

    private static String required(JsonNode in, String member) {
        return OmicsCommon.required(in, member);
    }

    private ObjectNode empty() {
        return mapper.createObjectNode();
    }

    private static boolean filtered(JsonNode record, JsonNode filter, String... fields) {
        for (String field : fields) {
            if (filter.hasNonNull(field) && !filter.get(field).asText().equals(record.path(field).asText())) {
                return false;
            }
        }
        String created = record.path("creationTime").asText("");
        if (filter.hasNonNull("createdAfter") && created.compareTo(filter.get("createdAfter").asText()) < 0) {
            return false;
        }
        return !filter.hasNonNull("createdBefore") || created.compareTo(filter.get("createdBefore").asText()) <= 0;
    }

    private ObjectNode page(List<ObjectNode> items, JsonNode in, String name) {
        ObjectNode out = empty();
        OmicsStore.page(items, in, out, name, 100);
        return out;
    }

    private ObjectNode idempotent(String region, String operation, JsonNode in, Supplier<ObjectNode>
            create) {
        String token = in.path("clientToken").asText(null);
        ObjectNode request = in.deepCopy();
        request.remove("clientToken");
        ObjectNode earlier = store.repeated(region, operation, token, request);
        if (earlier != null) {
            return earlier;
        }
        ObjectNode out = create.get();
        store.remember(region, operation, token, request, out);
        return out;
    }

    private static String dataKey(String... parts) {
        return String.join("/", parts);
    }

    // ================================================================ reference stores

    private ObjectNode referenceStore(String region, String id) {
        return store.get(region, OmicsStore.REFERENCE_STORE, id).orElseThrow(() ->
                OmicsStore.notFound("Reference store " + id + " doesn't exist."));
    }

    public ObjectNode createReferenceStore(JsonNode in, String region) {
        return idempotent(region, "CreateReferenceStore", in, () -> {
            String name = required(in, "name");
            String account = store.account();
            if (store.list(region, OmicsStore.REFERENCE_STORE).size() >= store.config().maxReferenceStores()) {
                throw OmicsStore.quota("The account has the maximum number of reference stores.");
            }
            String id = OmicsStore.numericId();
            ObjectNode record = store.record();
            record.put("id", id);
            record.put("arn", common.arn(region, account, "referenceStore/" + id));
            record.put("name", name);
            if (in.hasNonNull("description")) {
                record.put("description", in.get("description").asText());
            }
            record.set("sseConfig", in.path("sseConfig").isObject() ? in.get("sseConfig").deepCopy()
                    : empty().put("type", "KMS"));
            record.put("creationTime", OmicsStore.timestamp());
            store.put(region, OmicsStore.REFERENCE_STORE, id, record);
            common.tagged(account, record.path("arn").asText(), in.path("tags"));
            return OmicsStore.view(record);
        });
    }

    public ObjectNode getReferenceStore(JsonNode in, String region) {
        return OmicsStore.view(referenceStore(region, required(in, "id")));
    }

    public ObjectNode listReferenceStores(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, OmicsStore.REFERENCE_STORE)) {
            if (filtered(record, in.path("filter"), "name")) {
                items.add(OmicsStore.view(record));
            }
        }
        return page(items, in, "referenceStores");
    }

    public ObjectNode deleteReferenceStore(JsonNode in, String region) {
        ObjectNode record = referenceStore(region, required(in, "id"));
        String id = record.path("id").asText();
        if (references(region, id).stream().anyMatch(reference -> !"DELETED".equals(reference.path("status")
                .asText()))) {
            throw OmicsStore.conflict("Reference store " + id + " still has references.");
        }
        store.delete(region, OmicsStore.REFERENCE_STORE, id);
        common.untagged(store.account(), record.path("arn").asText());
        return empty();
    }

    // ================================================================ references

    private List<ObjectNode> references(String region, String storeId) {
        List<ObjectNode> list = new ArrayList<>();
        for (ObjectNode reference : store.list(region, OmicsStore.REFERENCE)) {
            if (storeId.equals(reference.path("referenceStoreId").asText())) {
                list.add(reference);
            }
        }
        return list;
    }

    private ObjectNode reference(String region, String storeId, String id) {
        referenceStore(region, storeId);
        return store.get(region, OmicsStore.REFERENCE, id)
                .filter(reference -> storeId.equals(reference.path("referenceStoreId").asText()))
                .filter(reference -> !"DELETED".equals(reference.path("status").asText()))
                .orElseThrow(() -> OmicsStore.notFound("Reference " + id + " doesn't exist."));
    }

    public ObjectNode startReferenceImportJob(JsonNode in, String region) {
        String storeId = required(in, "referenceStoreId");
        referenceStore(region, storeId);
        return idempotent(region, "StartReferenceImportJob", in, () -> {
            String account = store.account();
            String role = required(in, "roleArn");
            common.role(role, account, region);
            String id = OmicsStore.numericId();
            ObjectNode job = store.record();
            job.put("id", id);
            job.put("referenceStoreId", storeId);
            job.put("roleArn", role);
            job.put("status", "SUBMITTED");
            job.put("creationTime", OmicsStore.timestamp());
            ArrayNode sources = job.putArray("sources");
            for (JsonNode source : in.path("sources")) {
                ObjectNode item = sources.addObject();
                item.put("sourceFile", required(source, "sourceFile"));
                item.put("status", "NOT_STARTED");
                item.put("name", required(source, "name"));
                if (source.hasNonNull("description")) {
                    item.put("description", source.get("description").asText());
                }
                if (source.path("tags").isObject()) {
                    item.set("tags", source.get("tags").deepCopy());
                }
            }
            store.put(region, OmicsStore.REFERENCE_IMPORT, id, job);
            common.later(account, region, () -> importReferences(account, region, id));
            ObjectNode out = empty();
            for (String field : List.of("id", "referenceStoreId", "roleArn", "status", "creationTime")) {
                out.set(field, job.get(field));
            }
            return out;
        });
    }

    private void importReferences(String account, String region, String jobId) {
        ObjectNode job = store.get(region, OmicsStore.REFERENCE_IMPORT, jobId).orElse(null);
        if (job == null || !"SUBMITTED".equals(job.path("status").asText())) {
            return;
        }
        job.put("status", "IN_PROGRESS");
        store.put(region, OmicsStore.REFERENCE_IMPORT, jobId, job);
        int failed = 0;
        for (JsonNode node : job.path("sources")) {
            ObjectNode item = (ObjectNode) node;
            item.put("status", "IN_PROGRESS");
            try {
                byte[] fasta = common.read(item.path("sourceFile").asText());
                byte[] content = OmicsFiles.gzipped(fasta) ? OmicsFiles.gunzip(fasta) : fasta;
                String index = OmicsFiles.faidx(content);
                String id = OmicsStore.numericId();
                String storeId = job.path("referenceStoreId").asText();
                ObjectNode reference = store.record();
                reference.put("id", id);
                reference.put("arn", common.arn(region, account, "referenceStore/" + storeId + "/reference/" + id));
                reference.put("referenceStoreId", storeId);
                reference.put("md5", OmicsFiles.digest("MD5", content));
                reference.put("status", "ACTIVE");
                reference.put("name", item.path("name").asText());
                if (item.has("description")) {
                    reference.put("description", item.path("description").asText());
                }
                String now = OmicsStore.timestamp();
                reference.put("creationTime", now);
                reference.put("updateTime", now);
                reference.put("creationType", "IMPORT");
                reference.put("creationJobId", jobId);
                byte[] fai = index.getBytes(StandardCharsets.US_ASCII);
                ObjectNode files = reference.putObject("files");
                files.set("source", fileInfo(content.length));
                files.set("index", fileInfo(fai.length));
                store.putData(account, dataKey("reference", storeId, id, "SOURCE"), content);
                store.putData(account, dataKey("reference", storeId, id, "INDEX"), fai);
                store.put(region, OmicsStore.REFERENCE, id, reference);
                common.tagged(account, reference.path("arn").asText(), item.path("tags"));
                item.put("referenceId", id);
                item.put("status", "FINISHED");
            } catch (IOException | RuntimeException e) {
                failed++;
                item.put("status", "FAILED");
                item.put("statusMessage", e.getMessage());
            }
        }
        finish(job, failed);
        store.put(region, OmicsStore.REFERENCE_IMPORT, jobId, job);
    }

    private static void finish(ObjectNode job, int failed) {
        int total = job.path("sources").size() + job.path("readSets").size();
        job.put("status", failed == 0 ? "COMPLETED" : failed == total ? "FAILED" : "COMPLETED_WITH_FAILURES");
        job.put("completionTime", OmicsStore.timestamp());
    }

    private ObjectNode fileInfo(long length) {
        ObjectNode info = empty();
        info.put("totalParts", OmicsFiles.parts(length));
        info.put("partSize", OmicsFiles.PART_SIZE);
        info.put("contentLength", length);
        return info;
    }

    public ObjectNode getReferenceImportJob(JsonNode in, String region) {
        String storeId = required(in, "referenceStoreId");
        referenceStore(region, storeId);
        return OmicsStore.view(store.get(region, OmicsStore.REFERENCE_IMPORT, required(in, "id"))
                .filter(job -> storeId.equals(job.path("referenceStoreId").asText()))
                .orElseThrow(() -> OmicsStore.notFound("Import job " + in.path("id").asText() + " doesn't exist.")));
    }

    public ObjectNode listReferenceImportJobs(JsonNode in, String region) {
        String storeId = required(in, "referenceStoreId");
        referenceStore(region, storeId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode job : store.list(region, OmicsStore.REFERENCE_IMPORT)) {
            if (storeId.equals(job.path("referenceStoreId").asText()) && filtered(job, in.path("filter"), "status")) {
                ObjectNode item = OmicsStore.view(job);
                item.remove("sources");
                items.add(item);
            }
        }
        return page(items, in, "importJobs");
    }

    public ObjectNode getReferenceMetadata(JsonNode in, String region) {
        return OmicsStore.view(reference(region, required(in, "referenceStoreId"), required(in, "id")));
    }

    public ObjectNode listReferences(JsonNode in, String region) {
        String storeId = required(in, "referenceStoreId");
        referenceStore(region, storeId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode reference : references(region, storeId)) {
            if (!"DELETED".equals(reference.path("status").asText())
                    && filtered(reference, in.path("filter"), "name", "md5")) {
                ObjectNode item = OmicsStore.view(reference);
                item.remove(List.of("files", "creationType", "creationJobId"));
                items.add(item);
            }
        }
        return page(items, in, "references");
    }

    public ObjectNode getReference(JsonNode in, String region) {
        String storeId = required(in, "referenceStoreId");
        ObjectNode reference = reference(region, storeId, required(in, "id"));
        String file = in.path("file").asText("SOURCE");
        byte[] data = store.data(store.account(), dataKey("reference", storeId, reference.path("id").asText(), file))
                .orElseThrow(() -> OmicsStore.notFound("The reference has no " + file + " file."));
        return payload(range(OmicsFiles.part(data, in.path("partNumber").asInt(1)), in.path("range").asText(null)));
    }

    private ObjectNode payload(byte[] data) {
        ObjectNode out = empty();
        out.put("payload", Base64.getEncoder().encodeToString(data));
        return out;
    }

    /** The bytes a {@code Range: a-b} header selects ({@code bytes=a-b} too). */
    private static byte[] range(byte[] data, String header) {
        if (header == null || header.isBlank()) {
            return data;
        }
        String[] bounds = (header.startsWith("bytes=") ? header.substring(6) : header).split("-", 2);
        try {
            long start = bounds[0].isEmpty() ? Math.max(0, data.length - Long.parseLong(bounds[1]))
                    : Long.parseLong(bounds[0]);
            long end = bounds.length < 2 || bounds[1].isEmpty() || bounds[0].isEmpty() ? data.length - 1
                    : Math.min(data.length - 1, Long.parseLong(bounds[1]));
            if (start > end || start >= data.length) {
                throw OmicsStore.range("The range " + header + " can't be satisfied.");
            }
            byte[] slice = new byte[(int) (end - start + 1)];
            System.arraycopy(data, (int) start, slice, 0, slice.length);
            return slice;
        } catch (NumberFormatException e) {
            throw OmicsStore.validation("The range " + header + " isn't valid.");
        }
    }

    public ObjectNode deleteReference(JsonNode in, String region) {
        String storeId = required(in, "referenceStoreId");
        ObjectNode reference = reference(region, storeId, required(in, "id"));
        String arn = reference.path("arn").asText();
        for (ObjectNode readSet : store.list(region, OmicsStore.READ_SET)) {
            if (arn.equals(readSet.path("referenceArn").asText()) && !"DELETED".equals(readSet.path("status")
                    .asText())) {
                throw OmicsStore.conflict("Read sets still name reference " + reference.path("id").asText() + ".");
            }
        }
        String id = reference.path("id").asText();
        String account = store.account();
        store.delete(region, OmicsStore.REFERENCE, id);
        store.deleteData(account, dataKey("reference", storeId, id, "SOURCE"));
        store.deleteData(account, dataKey("reference", storeId, id, "INDEX"));
        common.untagged(account, arn);
        return empty();
    }

    /** Whether a reference ARN names a reference that exists. */
    boolean referenceExists(String arn, String region) {
        return store.list(region, OmicsStore.REFERENCE).stream().anyMatch(reference ->
                arn.equals(reference.path("arn").asText()) && "ACTIVE".equals(reference.path("status").asText()));
    }

    // ================================================================ sequence stores

    private ObjectNode sequenceStore(String region, String id) {
        return store.get(region, OmicsStore.SEQUENCE_STORE, id).orElseThrow(() ->
                OmicsStore.notFound("Sequence store " + id + " doesn't exist."));
    }

    public ObjectNode createSequenceStore(JsonNode in, String region) {
        return idempotent(region, "CreateSequenceStore", in, () -> {
            String name = required(in, "name");
            String account = store.account();
            if (in.hasNonNull("fallbackLocation")) {
                OmicsCommon.s3Uri(in.get("fallbackLocation").asText());
            }
            String id = OmicsStore.numericId();
            ObjectNode record = store.record();
            record.put("id", id);
            record.put("arn", common.arn(region, account, "sequenceStore/" + id));
            record.put("name", name);
            for (String field : List.of("description", "fallbackLocation")) {
                if (in.hasNonNull(field)) {
                    record.put(field, in.get(field).asText());
                }
            }
            record.set("sseConfig", in.path("sseConfig").isObject() ? in.get("sseConfig").deepCopy()
                    : empty().put("type", "KMS"));
            String now = OmicsStore.timestamp();
            record.put("creationTime", now);
            record.put("updateTime", now);
            record.put("eTagAlgorithmFamily", in.path("eTagAlgorithmFamily").asText("MD5up"));
            record.put("status", "ACTIVE");
            if (in.path("propagatedSetLevelTags").isArray()) {
                record.set("propagatedSetLevelTags", in.get("propagatedSetLevelTags").deepCopy());
            }
            ObjectNode access = record.putObject("s3Access");
            access.put("s3AccessPointArn", accessPoint(region, account, id));
            if (in.path("s3AccessConfig").hasNonNull("accessLogLocation")) {
                access.put("accessLogLocation", in.path("s3AccessConfig").path("accessLogLocation").asText());
            }
            store.put(region, OmicsStore.SEQUENCE_STORE, id, record);
            common.tagged(account, record.path("arn").asText(), in.path("tags"));
            return OmicsStore.view(record);
        });
    }

    private static String accessPoint(String region, String account, String storeId) {
        return AwsArnUtils.Arn.of("s3", region, account, "accesspoint/" + storeId
                + "-" + account).toString();
    }

    public ObjectNode getSequenceStore(JsonNode in, String region) {
        return OmicsStore.view(sequenceStore(region, required(in, "id")));
    }

    public ObjectNode updateSequenceStore(JsonNode in, String region) {
        ObjectNode record = sequenceStore(region, required(in, "id"));
        for (String field : List.of("name", "description", "fallbackLocation")) {
            if (in.hasNonNull(field)) {
                record.put(field, in.get(field).asText());
            }
        }
        if (in.path("propagatedSetLevelTags").isArray()) {
            record.set("propagatedSetLevelTags", in.get("propagatedSetLevelTags").deepCopy());
        }
        if (in.path("s3AccessConfig").hasNonNull("accessLogLocation")) {
            ((ObjectNode) record.path("s3Access")).put("accessLogLocation", in.path("s3AccessConfig")
                    .path("accessLogLocation").asText());
        }
        record.put("updateTime", OmicsStore.timestamp());
        store.put(region, OmicsStore.SEQUENCE_STORE, record.path("id").asText(), record);
        return OmicsStore.view(record);
    }

    public ObjectNode listSequenceStores(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, OmicsStore.SEQUENCE_STORE)) {
            if (filtered(record, in.path("filter"), "name", "status")) {
                ObjectNode item = OmicsStore.view(record);
                item.remove(List.of("s3Access", "propagatedSetLevelTags"));
                items.add(item);
            }
        }
        return page(items, in, "sequenceStores");
    }

    public ObjectNode deleteSequenceStore(JsonNode in, String region) {
        ObjectNode record = sequenceStore(region, required(in, "id"));
        String id = record.path("id").asText();
        if (readSets(region, id).stream().anyMatch(readSet -> !"DELETED".equals(readSet.path("status").asText()))) {
            throw OmicsStore.conflict("Sequence store " + id + " still has read sets.");
        }
        store.delete(region, OmicsStore.SEQUENCE_STORE, id);
        store.delete(region, OmicsStore.ACCESS_POLICY, id);
        common.untagged(store.account(), record.path("arn").asText());
        return empty();
    }

    // ================================================================ S3 access policies

    private ObjectNode storeForAccessPoint(String region, String arn) {
        for (ObjectNode record : store.list(region, OmicsStore.SEQUENCE_STORE)) {
            if (arn.equals(record.path("s3Access").path("s3AccessPointArn").asText())) {
                return record;
            }
        }
        throw OmicsStore.notFound("No sequence store has the access point " + arn + ".");
    }

    public ObjectNode putS3AccessPolicy(JsonNode in, String region) {
        String arn = required(in, "s3AccessPointArn");
        ObjectNode sequenceStore = storeForAccessPoint(region, arn);
        String policy = required(in, "s3AccessPolicy");
        try {
            JsonNode parsed = mapper.readTree(policy);
            if (!parsed.isObject() || !parsed.has("Statement")) {
                throw OmicsStore.validation("The S3 access policy must be a policy document with a Statement.");
            }
        } catch (JsonProcessingException e) {
            throw OmicsStore.validation("The S3 access policy isn't JSON.");
        }
        ObjectNode record = store.record();
        record.put("s3AccessPointArn", arn);
        record.put("storeId", sequenceStore.path("id").asText());
        record.put("storeType", "SEQUENCE_STORE");
        record.put("updateTime", OmicsStore.timestamp());
        record.put("s3AccessPolicy", policy);
        store.put(region, OmicsStore.ACCESS_POLICY, sequenceStore.path("id").asText(), record);
        ObjectNode out = empty();
        out.put("s3AccessPointArn", arn);
        out.put("storeId", sequenceStore.path("id").asText());
        out.put("storeType", "SEQUENCE_STORE");
        return out;
    }

    public ObjectNode getS3AccessPolicy(JsonNode in, String region) {
        ObjectNode sequenceStore = storeForAccessPoint(region, required(in, "s3AccessPointArn"));
        return OmicsStore.view(store.get(region, OmicsStore.ACCESS_POLICY, sequenceStore.path("id").asText())
                .orElseThrow(() -> OmicsStore.notFound("The access point has no S3 access policy.")));
    }

    public ObjectNode deleteS3AccessPolicy(JsonNode in, String region) {
        ObjectNode sequenceStore = storeForAccessPoint(region, required(in, "s3AccessPointArn"));
        String id = sequenceStore.path("id").asText();
        store.get(region, OmicsStore.ACCESS_POLICY, id).orElseThrow(() ->
                OmicsStore.notFound("The access point has no S3 access policy."));
        store.delete(region, OmicsStore.ACCESS_POLICY, id);
        return empty();
    }

    // ================================================================ read sets

    private List<ObjectNode> readSets(String region, String storeId) {
        List<ObjectNode> list = new ArrayList<>();
        for (ObjectNode readSet : store.list(region, OmicsStore.READ_SET)) {
            if (storeId.equals(readSet.path("sequenceStoreId").asText())) {
                list.add(archived(region, readSet));
            }
        }
        return list;
    }

    /** A read set, archived once it's gone {@code archive-after-days} without a read. */
    private ObjectNode archived(String region, ObjectNode readSet) {
        if ("ACTIVE".equals(readSet.path("status").asText())) {
            Instant last = Instant.parse(OmicsStore.meta(readSet).path("lastRead").asText(readSet.path("creationTime")
                    .asText()));
            if (last.plus(Duration.ofDays(store.config().archiveAfterDays())).isBefore(Instant.now())) {
                readSet.put("status", "ARCHIVED");
                store.put(region, OmicsStore.READ_SET, readSet.path("id").asText(), readSet);
            }
        }
        return readSet;
    }

    private ObjectNode readSet(String region, String storeId, String id) {
        sequenceStore(region, storeId);
        return store.get(region, OmicsStore.READ_SET, id)
                .filter(readSet -> storeId.equals(readSet.path("sequenceStoreId").asText()))
                .filter(readSet -> !"DELETED".equals(readSet.path("status").asText()))
                .map(readSet -> archived(region, readSet))
                .orElseThrow(() -> OmicsStore.notFound("Read set " + id + " doesn't exist."));
    }

    private static String etagAlgorithm(String type, String family) {
        String kind = "UBAM".equals(type) ? "BAM" : type;
        return kind + "_" + family;
    }

    /** Stores a read set's files and counts; returns the read set. */
    private ObjectNode createReadSet(String account, String region, ObjectNode sequenceStore, JsonNode source,
                                     byte[] source1, byte[] source2, String creationType, String jobId)
            throws IOException {
        String type = source.path("sourceFileType").asText();
        OmicsFiles.Counts counts = OmicsFiles.count(type, source1);
        if (source2 != null) {
            OmicsFiles.Counts mate = OmicsFiles.count(type, source2);
            counts = new OmicsFiles.Counts(counts.reads() + mate.reads(), counts.bases() + mate.bases(),
                    counts.alignment());
        }
        String referenceArn = source.path("referenceArn").asText("");
        if (("BAM".equals(type) || "CRAM".equals(type)) && referenceArn.isBlank()) {
            throw new IllegalArgumentException(type + " read sets need a referenceArn.");
        }
        if (!referenceArn.isBlank() && !referenceExists(referenceArn, region)) {
            throw new IllegalArgumentException("The reference " + referenceArn + " doesn't exist.");
        }
        String storeId = sequenceStore.path("id").asText();
        String id = OmicsStore.numericId();
        ObjectNode readSet = store.record();
        readSet.put("id", id);
        readSet.put("arn", common.arn(region, account, "sequenceStore/" + storeId + "/readSet/" + id));
        readSet.put("sequenceStoreId", storeId);
        readSet.put("subjectId", source.path("subjectId").asText());
        readSet.put("sampleId", source.path("sampleId").asText());
        readSet.put("status", "ACTIVE");
        for (String field : List.of("name", "description")) {
            if (source.hasNonNull(field)) {
                readSet.put(field, source.get(field).asText());
            }
        }
        readSet.put("fileType", type);
        readSet.put("creationTime", OmicsStore.timestamp());
        ObjectNode information = readSet.putObject("sequenceInformation");
        information.put("totalReadCount", counts.reads());
        information.put("totalBaseCount", counts.bases());
        if (source.hasNonNull("generatedFrom")) {
            information.put("generatedFrom", source.get("generatedFrom").asText());
        }
        information.put("alignment", counts.alignment());
        if (!referenceArn.isBlank()) {
            readSet.put("referenceArn", referenceArn);
        }
        ObjectNode files = readSet.putObject("files");
        files.set("source1", fileInfo(source1.length));
        if (source2 != null) {
            files.set("source2", fileInfo(source2.length));
        }
        readSet.put("creationType", creationType);
        if (jobId != null) {
            readSet.put("creationJobId", jobId);
        }
        String family = sequenceStore.path("eTagAlgorithmFamily").asText("MD5up");
        String algorithm = family.startsWith("SHA256") ? "SHA-256" : family.startsWith("SHA512") ? "SHA-512" : "MD5";
        ObjectNode etag = readSet.putObject("etag");
        etag.put("algorithm", etagAlgorithm(type, family));
        etag.put("source1", OmicsFiles.digest(algorithm, source1));
        if (source2 != null) {
            etag.put("source2", OmicsFiles.digest(algorithm, source2));
        }
        store.putData(account, dataKey("readset", storeId, id, "SOURCE1"), source1);
        if (source2 != null) {
            store.putData(account, dataKey("readset", storeId, id, "SOURCE2"), source2);
        }
        store.put(region, OmicsStore.READ_SET, id, readSet);
        ObjectNode tags = source.path("tags").isObject() ? (ObjectNode) source.get("tags").deepCopy() : empty();
        common.tagged(account, readSet.path("arn").asText(), tags);
        return readSet;
    }

    public ObjectNode startReadSetImportJob(JsonNode in, String region) {
        String storeId = required(in, "sequenceStoreId");
        sequenceStore(region, storeId);
        return idempotent(region, "StartReadSetImportJob", in, () -> {
            String account = store.account();
            String role = required(in, "roleArn");
            common.role(role, account, region);
            String id = OmicsStore.numericId();
            ObjectNode job = store.record();
            job.put("id", id);
            job.put("sequenceStoreId", storeId);
            job.put("roleArn", role);
            job.put("status", "SUBMITTED");
            job.put("creationTime", OmicsStore.timestamp());
            ArrayNode sources = job.putArray("sources");
            for (JsonNode source : in.path("sources")) {
                ObjectNode item = source.deepCopy();
                item.put("status", "NOT_STARTED");
                sources.add(item);
            }
            store.put(region, OmicsStore.READ_SET_IMPORT, id, job);
            common.later(account, region, () -> importReadSets(account, region, id));
            ObjectNode out = empty();
            for (String field : List.of("id", "sequenceStoreId", "roleArn", "status", "creationTime")) {
                out.set(field, job.get(field));
            }
            return out;
        });
    }

    private void importReadSets(String account, String region, String jobId) {
        ObjectNode job = store.get(region, OmicsStore.READ_SET_IMPORT, jobId).orElse(null);
        if (job == null || !"SUBMITTED".equals(job.path("status").asText())) {
            return;
        }
        job.put("status", "IN_PROGRESS");
        store.put(region, OmicsStore.READ_SET_IMPORT, jobId, job);
        ObjectNode sequenceStore = sequenceStore(region, job.path("sequenceStoreId").asText());
        int failed = 0;
        for (JsonNode node : job.path("sources")) {
            ObjectNode item = (ObjectNode) node;
            item.put("status", "IN_PROGRESS");
            try {
                byte[] source1 = common.read(item.path("sourceFiles").path("source1").asText());
                byte[] source2 = item.path("sourceFiles").hasNonNull("source2")
                        ? common.read(item.path("sourceFiles").path("source2").asText()) : null;
                ObjectNode readSet = createReadSet(account, region, sequenceStore, item, source1, source2, "IMPORT",
                        jobId);
                item.put("readSetId", readSet.path("id").asText());
                item.put("status", "FINISHED");
            } catch (IOException | RuntimeException e) {
                failed++;
                item.put("status", "FAILED");
                item.put("statusMessage", e.getMessage());
            }
        }
        finish(job, failed);
        store.put(region, OmicsStore.READ_SET_IMPORT, jobId, job);
    }

    private ObjectNode job(String region, String kind, String storeId, String id) {
        sequenceStore(region, storeId);
        return store.get(region, kind, id).filter(job -> storeId.equals(job.path("sequenceStoreId").asText()))
                .orElseThrow(() -> OmicsStore.notFound("Job " + id + " doesn't exist."));
    }

    public ObjectNode getReadSetImportJob(JsonNode in, String region) {
        return OmicsStore.view(job(region, OmicsStore.READ_SET_IMPORT, required(in, "sequenceStoreId"),
                required(in, "id")));
    }

    private ObjectNode listJobs(JsonNode in, String region, String kind, String name, String... drop) {
        String storeId = required(in, "sequenceStoreId");
        sequenceStore(region, storeId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode job : store.list(region, kind)) {
            if (storeId.equals(job.path("sequenceStoreId").asText()) && filtered(job, in.path("filter"), "status")) {
                ObjectNode item = OmicsStore.view(job);
                item.remove(List.of(drop));
                items.add(item);
            }
        }
        return page(items, in, name);
    }

    public ObjectNode listReadSetImportJobs(JsonNode in, String region) {
        return listJobs(in, region, OmicsStore.READ_SET_IMPORT, "importJobs", "sources", "statusMessage");
    }

    public ObjectNode getReadSetMetadata(JsonNode in, String region) {
        return OmicsStore.view(readSet(region, required(in, "sequenceStoreId"), required(in, "id")));
    }

    public ObjectNode listReadSets(JsonNode in, String region) {
        String storeId = required(in, "sequenceStoreId");
        sequenceStore(region, storeId);
        JsonNode filter = in.path("filter");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode readSet : readSets(region, storeId)) {
            if ("DELETED".equals(readSet.path("status").asText())
                    || !filtered(readSet, filter, "name", "status", "referenceArn", "sampleId", "subjectId",
                    "creationType")
                    || (filter.hasNonNull("generatedFrom") && !filter.get("generatedFrom").asText()
                    .equals(readSet.path("sequenceInformation").path("generatedFrom").asText()))) {
                continue;
            }
            ObjectNode item = OmicsStore.view(readSet);
            item.remove(List.of("files", "creationJobId"));
            items.add(item);
        }
        return page(items, in, "readSets");
    }

    public ObjectNode getReadSet(JsonNode in, String region) {
        String storeId = required(in, "sequenceStoreId");
        ObjectNode readSet = readSet(region, storeId, required(in, "id"));
        String status = readSet.path("status").asText();
        if (!"ACTIVE".equals(status)) {
            throw OmicsStore.conflict("Read set " + readSet.path("id").asText() + " is " + status
                    + (status.equals("ARCHIVED") ? "; activate it with an activation job." : "."));
        }
        String file = in.path("file").asText("SOURCE1");
        byte[] data = store.data(store.account(), dataKey("readset", storeId, readSet.path("id").asText(), file))
                .orElseThrow(() -> OmicsStore.notFound("The read set has no " + file + " file."));
        OmicsStore.meta(readSet).put("lastRead", OmicsStore.timestamp());
        store.put(region, OmicsStore.READ_SET, readSet.path("id").asText(), readSet);
        return payload(OmicsFiles.part(data, in.path("partNumber").asInt(1)));
    }

    public ObjectNode batchDeleteReadSet(JsonNode in, String region) {
        String storeId = required(in, "sequenceStoreId");
        sequenceStore(region, storeId);
        String account = store.account();
        ObjectNode out = empty();
        ArrayNode errors = out.putArray("errors");
        for (JsonNode node : in.path("ids")) {
            String id = node.asText();
            ObjectNode readSet = store.get(region, OmicsStore.READ_SET, id)
                    .filter(candidate -> storeId.equals(candidate.path("sequenceStoreId").asText()))
                    .filter(candidate -> !"DELETED".equals(candidate.path("status").asText())).orElse(null);
            if (readSet == null) {
                errors.addObject().put("id", id).put("code", "404").put("message", "Read set " + id
                        + " doesn't exist.");
                continue;
            }
            store.delete(region, OmicsStore.READ_SET, id);
            for (String file : List.of("SOURCE1", "SOURCE2", "INDEX")) {
                store.deleteData(account, dataKey("readset", storeId, id, file));
            }
            common.untagged(account, readSet.path("arn").asText());
        }
        return out;
    }

    // ---------------------------------------------------------------- export and activation

    public ObjectNode startReadSetExportJob(JsonNode in, String region) {
        String storeId = required(in, "sequenceStoreId");
        sequenceStore(region, storeId);
        return idempotent(region, "StartReadSetExportJob", in, () -> {
            String account = store.account();
            String role = required(in, "roleArn");
            common.role(role, account, region);
            String destination = required(in, "destination");
            OmicsCommon.s3Uri(destination);
            String id = OmicsStore.numericId();
            ObjectNode job = store.record();
            job.put("id", id);
            job.put("sequenceStoreId", storeId);
            job.put("destination", destination);
            job.put("status", "SUBMITTED");
            job.put("creationTime", OmicsStore.timestamp());
            ArrayNode readSets = job.putArray("readSets");
            for (JsonNode source : in.path("sources")) {
                readSets.addObject().put("id", required(source, "readSetId")).put("status", "NOT_STARTED");
            }
            store.put(region, OmicsStore.READ_SET_EXPORT, id, job);
            common.later(account, region, () -> export(account, region, id));
            ObjectNode out = empty();
            for (String field : List.of("id", "sequenceStoreId", "destination", "status", "creationTime")) {
                out.set(field, job.get(field));
            }
            return out;
        });
    }

    private static String extension(String type) {
        return switch (type) {
            case "FASTQ" -> ".fastq";
            case "CRAM" -> ".cram";
            default -> ".bam";
        };
    }

    private void export(String account, String region, String jobId) {
        ObjectNode job = store.get(region, OmicsStore.READ_SET_EXPORT, jobId).orElse(null);
        if (job == null || !RUNNING.contains(job.path("status").asText())) {
            return;
        }
        job.put("status", "IN_PROGRESS");
        String storeId = job.path("sequenceStoreId").asText();
        String destination = job.path("destination").asText().replaceAll("/+$", "");
        int failed = 0;
        for (JsonNode node : job.path("readSets")) {
            ObjectNode item = (ObjectNode) node;
            String id = item.path("id").asText();
            try {
                ObjectNode readSet = readSet(region, storeId, id);
                if ("ARCHIVED".equals(readSet.path("status").asText())) {
                    throw new IllegalStateException("Read set " + id + " is archived.");
                }
                for (String file : List.of("SOURCE1", "SOURCE2")) {
                    byte[] data = store.data(account, dataKey("readset", storeId, id, file)).orElse(null);
                    if (data == null) {
                        continue;
                    }
                    String suffix = "SOURCE1".equals(file) ? "_1" : "_2";
                    String name = id + suffix + extension(readSet.path("fileType").asText())
                            + (OmicsFiles.gzipped(data) && "FASTQ".equals(readSet.path("fileType").asText())
                            ? ".gz" : "");
                    common.write(destination + "/" + id + "/" + name, data, "application/octet-stream");
                }
                item.put("status", "FINISHED");
            } catch (RuntimeException e) {
                failed++;
                item.put("status", "FAILED");
                item.put("statusMessage", e.getMessage());
            }
        }
        finish(job, failed);
        store.put(region, OmicsStore.READ_SET_EXPORT, jobId, job);
    }

    public ObjectNode getReadSetExportJob(JsonNode in, String region) {
        return OmicsStore.view(job(region, OmicsStore.READ_SET_EXPORT, required(in, "sequenceStoreId"),
                required(in, "id")));
    }

    public ObjectNode listReadSetExportJobs(JsonNode in, String region) {
        return listJobs(in, region, OmicsStore.READ_SET_EXPORT, "exportJobs", "readSets", "statusMessage");
    }

    public ObjectNode startReadSetActivationJob(JsonNode in, String region) {
        String storeId = required(in, "sequenceStoreId");
        sequenceStore(region, storeId);
        return idempotent(region, "StartReadSetActivationJob", in, () -> {
            String account = store.account();
            String id = OmicsStore.numericId();
            ObjectNode job = store.record();
            job.put("id", id);
            job.put("sequenceStoreId", storeId);
            job.put("status", "SUBMITTED");
            job.put("creationTime", OmicsStore.timestamp());
            ArrayNode sources = job.putArray("sources");
            for (JsonNode source : in.path("sources")) {
                String readSetId = required(source, "readSetId");
                sources.addObject().put("readSetId", readSetId).put("status", "NOT_STARTED");
                store.get(region, OmicsStore.READ_SET, readSetId).ifPresent(readSet -> {
                    if ("ARCHIVED".equals(readSet.path("status").asText())) {
                        readSet.put("status", "ACTIVATING");
                        store.put(region, OmicsStore.READ_SET, readSetId, readSet);
                    }
                });
            }
            store.put(region, OmicsStore.ACTIVATION, id, job);
            common.later(account, region, () -> activate(region, id));
            ObjectNode out = empty();
            for (String field : List.of("id", "sequenceStoreId", "status", "creationTime")) {
                out.set(field, job.get(field));
            }
            return out;
        });
    }

    private void activate(String region, String jobId) {
        ObjectNode job = store.get(region, OmicsStore.ACTIVATION, jobId).orElse(null);
        if (job == null) {
            return;
        }
        int failed = 0;
        for (JsonNode node : job.path("sources")) {
            ObjectNode item = (ObjectNode) node;
            ObjectNode readSet = store.get(region, OmicsStore.READ_SET, item.path("readSetId").asText())
                    .filter(candidate -> job.path("sequenceStoreId").asText()
                            .equals(candidate.path("sequenceStoreId").asText())).orElse(null);
            if (readSet == null) {
                failed++;
                item.put("status", "FAILED");
                item.put("statusMessage", "Read set " + item.path("readSetId").asText() + " doesn't exist.");
                continue;
            }
            readSet.put("status", "ACTIVE");
            OmicsStore.meta(readSet).put("lastRead", OmicsStore.timestamp());
            store.put(region, OmicsStore.READ_SET, readSet.path("id").asText(), readSet);
            item.put("status", "FINISHED");
        }
        finish(job, failed);
        store.put(region, OmicsStore.ACTIVATION, jobId, job);
    }

    public ObjectNode getReadSetActivationJob(JsonNode in, String region) {
        return OmicsStore.view(job(region, OmicsStore.ACTIVATION, required(in, "sequenceStoreId"),
                required(in, "id")));
    }

    public ObjectNode listReadSetActivationJobs(JsonNode in, String region) {
        return listJobs(in, region, OmicsStore.ACTIVATION, "activationJobs", "sources", "statusMessage");
    }

    // ---------------------------------------------------------------- multipart uploads

    private ObjectNode upload(String region, String storeId, String uploadId) {
        sequenceStore(region, storeId);
        return store.get(region, OmicsStore.UPLOAD, uploadId)
                .filter(upload -> storeId.equals(upload.path("sequenceStoreId").asText()))
                .orElseThrow(() -> OmicsStore.notFound("Upload " + uploadId + " doesn't exist."));
    }

    public ObjectNode createMultipartReadSetUpload(JsonNode in, String region) {
        String storeId = required(in, "sequenceStoreId");
        sequenceStore(region, storeId);
        return idempotent(region, "CreateMultipartReadSetUpload", in, () -> {
            String type = required(in, "sourceFileType");
            String referenceArn = in.path("referenceArn").asText("");
            if (("BAM".equals(type) || "CRAM".equals(type)) && referenceArn.isBlank()) {
                throw OmicsStore.validation(type + " read sets need a referenceArn.");
            }
            if (!referenceArn.isBlank() && !referenceExists(referenceArn, region)) {
                throw OmicsStore.validation("The reference " + referenceArn + " doesn't exist.");
            }
            String id = OmicsStore.numericId();
            ObjectNode upload = store.record();
            upload.put("sequenceStoreId", storeId);
            upload.put("uploadId", id);
            upload.put("sourceFileType", type);
            upload.put("subjectId", required(in, "subjectId"));
            upload.put("sampleId", required(in, "sampleId"));
            upload.put("generatedFrom", in.path("generatedFrom").asText(""));
            upload.put("referenceArn", referenceArn);
            upload.put("name", required(in, "name"));
            if (in.hasNonNull("description")) {
                upload.put("description", in.get("description").asText());
            }
            upload.set("tags", in.path("tags").isObject() ? in.get("tags").deepCopy() : empty());
            upload.put("creationTime", OmicsStore.timestamp());
            store.put(region, OmicsStore.UPLOAD, id, upload);
            return OmicsStore.view(upload);
        });
    }

    public ObjectNode uploadReadSetPart(JsonNode in, String region) {
        String storeId = required(in, "sequenceStoreId");
        ObjectNode upload = upload(region, storeId, required(in, "uploadId"));
        String source = required(in, "partSource");
        int number = in.path("partNumber").asInt();
        if (number < 1 || number > 10000) {
            throw OmicsStore.validation("partNumber must be 1-10000.");
        }
        byte[] data = Base64.getDecoder().decode(in.path("payload").asText(""));
        String checksum = OmicsFiles.digest("SHA-256", data);
        String account = store.account();
        store.putData(account, dataKey("upload", storeId, upload.path("uploadId").asText(), source,
                String.valueOf(number)), data);
        ObjectNode meta = OmicsStore.meta(upload);
        if (!meta.path("parts").isObject()) {
            meta.putObject("parts");
        }
        ObjectNode parts = (ObjectNode) meta.get("parts");
        String now = OmicsStore.timestamp();
        ObjectNode part = parts.putObject(source + "/" + number);
        part.put("partNumber", number);
        part.put("partSize", data.length);
        part.put("partSource", source);
        part.put("checksum", checksum);
        part.put("creationTime", now);
        part.put("lastUpdatedTime", now);
        store.put(region, OmicsStore.UPLOAD, upload.path("uploadId").asText(), upload);
        ObjectNode out = empty();
        out.put("checksum", checksum);
        return out;
    }

    public ObjectNode listReadSetUploadParts(JsonNode in, String region) {
        ObjectNode upload = upload(region, required(in, "sequenceStoreId"), required(in, "uploadId"));
        String source = required(in, "partSource");
        List<ObjectNode> items = new ArrayList<>();
        OmicsStore.meta(upload).path("parts").forEach(part -> {
            if (source.equals(part.path("partSource").asText()) && filtered(part, in.path("filter"))) {
                items.add((ObjectNode) part.deepCopy());
            }
        });
        items.sort(Comparator.comparingInt(part -> part.path("partNumber").asInt()));
        return page(items, in, "parts");
    }

    public ObjectNode listMultipartReadSetUploads(JsonNode in, String region) {
        String storeId = required(in, "sequenceStoreId");
        sequenceStore(region, storeId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode upload : store.list(region, OmicsStore.UPLOAD)) {
            if (storeId.equals(upload.path("sequenceStoreId").asText())) {
                items.add(OmicsStore.view(upload));
            }
        }
        return page(items, in, "uploads");
    }

    private void dropUpload(String region, ObjectNode upload) {
        String account = store.account();
        String storeId = upload.path("sequenceStoreId").asText();
        String uploadId = upload.path("uploadId").asText();
        OmicsStore.meta(upload).path("parts").properties().forEach(entry -> store.deleteData(account,
                dataKey("upload", storeId, uploadId, entry.getKey())));
        store.delete(region, OmicsStore.UPLOAD, uploadId);
    }

    public ObjectNode abortMultipartReadSetUpload(JsonNode in, String region) {
        dropUpload(region, upload(region, required(in, "sequenceStoreId"), required(in, "uploadId")));
        return empty();
    }

    public ObjectNode completeMultipartReadSetUpload(JsonNode in, String region) {
        String storeId = required(in, "sequenceStoreId");
        ObjectNode upload = upload(region, storeId, required(in, "uploadId"));
        String account = store.account();
        JsonNode uploaded = OmicsStore.meta(upload).path("parts");
        Map<String, ByteArrayOutputStream> files = new TreeMap<>();
        List<JsonNode> parts = new ArrayList<>();
        in.path("parts").forEach(parts::add);
        parts.sort(Comparator.comparing((JsonNode part) -> part.path("partSource").asText())
                .thenComparingInt(part -> part.path("partNumber").asInt()));
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode part : parts) {
            String key = part.path("partSource").asText() + "/" + part.path("partNumber").asInt();
            JsonNode stored = uploaded.path(key);
            if (stored.isMissingNode()) {
                throw OmicsStore.validation("Part " + key + " wasn't uploaded.");
            }
            if (!stored.path("checksum").asText().equals(part.path("checksum").asText())) {
                throw OmicsStore.validation("Part " + key + "'s checksum doesn't match the uploaded part.");
            }
            if (!seen.add(key)) {
                throw OmicsStore.validation("Part " + key + " is listed twice.");
            }
            byte[] data = store.data(account, dataKey("upload", storeId, upload.path("uploadId").asText(), key))
                    .orElse(new byte[0]);
            files.computeIfAbsent(part.path("partSource").asText(), source -> new ByteArrayOutputStream())
                    .writeBytes(data);
        }
        if (!files.containsKey("SOURCE1")) {
            throw OmicsStore.validation("The upload needs SOURCE1 parts.");
        }
        ObjectNode readSet;
        try {
            readSet = createReadSet(account, region, sequenceStore(region, storeId), upload,
                    files.get("SOURCE1").toByteArray(), files.containsKey("SOURCE2")
                            ? files.get("SOURCE2").toByteArray() : null, "UPLOAD", null);
        } catch (IOException | IllegalArgumentException e) {
            throw OmicsStore.validation("The uploaded read set can't be read: " + e.getMessage());
        }
        dropUpload(region, upload);
        ObjectNode out = empty();
        out.put("readSetId", readSet.path("id").asText());
        return out;
    }
}
