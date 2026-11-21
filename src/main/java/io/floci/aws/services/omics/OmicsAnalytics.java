package io.floci.aws.services.omics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * HealthOmics analytics: annotation stores (GFF, TSV or VCF, with versions) and variant stores, filled by import jobs
 * that read VCF, GFF and TSV files from S3 and count their records, and shares that let another account use a store or
 * workflow once it accepts.
 */
@ApplicationScoped
public class OmicsAnalytics {

    private static final Set<String> SHAREABLE = Set.of("annotationStore", "variantStore", "workflow");

    private final OmicsStore store;
    private final OmicsCommon common;
    private final OmicsStorage storage;
    private final ObjectMapper mapper;

    @Inject
    public OmicsAnalytics(OmicsStore store, OmicsCommon common, OmicsStorage storage) {
        this.store = store;
        this.common = common;
        this.storage = storage;
        this.mapper = store.mapper();
    }

    private static String required(JsonNode in, String member) {
        return OmicsCommon.required(in, member);
    }

    private ObjectNode empty() {
        return mapper.createObjectNode();
    }

    private ObjectNode page(List<ObjectNode> items, JsonNode in, String name) {
        ObjectNode out = empty();
        OmicsStore.page(items, in, out, name, 100);
        return out;
    }

    private static boolean wanted(JsonNode record, JsonNode in) {
        if (in.path("ids").isArray() && !in.path("ids").isEmpty()) {
            boolean listed = false;
            for (JsonNode id : in.path("ids")) {
                listed |= id.asText().equals(record.path("id").asText());
            }
            if (!listed) {
                return false;
            }
        }
        JsonNode status = in.path("filter").path("status");
        return status.isMissingNode() || status.isNull() || status.asText().equals(record.path("status").asText());
    }

    private void settle(String account, String region, String kind, String key) {
        common.later(account, region, () -> store.get(region, kind, key).ifPresent(record -> {
            if ("CREATING".equals(record.path("status").asText()) || "UPDATING".equals(record.path("status")
                    .asText())) {
                record.put("status", "ACTIVE");
                record.put("statusMessage", "");
                record.put("updateTime", OmicsStore.timestamp());
                store.put(region, kind, key, record);
            }
        }));
    }

    private void reference(JsonNode reference, String region, boolean required) {
        String arn = reference.path("referenceArn").asText("");
        if (arn.isBlank()) {
            if (required) {
                throw OmicsStore.validation("reference.referenceArn is required.");
            }
            return;
        }
        if (!storage.referenceExists(arn, region)) {
            throw OmicsStore.validation("The reference " + arn + " doesn't exist.");
        }
    }

    // ================================================================ stores

    private ObjectNode createStore(JsonNode in, String region, String kind, String type) {
        String name = required(in, "name");
        String account = store.account();
        if (store.get(region, OmicsStore.ANNOTATION_STORE, name).isPresent()
                || store.get(region, OmicsStore.VARIANT_STORE, name).isPresent()) {
            throw OmicsStore.conflict("A store named " + name + " already exists.");
        }
        ObjectNode record = store.record();
        String now = OmicsStore.timestamp();
        record.put("id", UUID.randomUUID().toString());
        record.set("reference", in.path("reference").isObject() ? in.get("reference").deepCopy() : empty());
        record.put("status", "CREATING");
        record.put("storeArn", common.arn(region, account, type + "/" + name));
        record.put("name", name);
        record.put("description", in.path("description").asText(""));
        record.set("sseConfig", in.path("sseConfig").isObject() ? in.get("sseConfig").deepCopy()
                : empty().put("type", "KMS"));
        record.put("creationTime", now);
        record.put("updateTime", now);
        record.put("statusMessage", "");
        record.put("storeSizeBytes", 0);
        store.put(region, kind, name, record);
        common.tagged(account, record.path("storeArn").asText(), in.path("tags"));
        settle(account, region, kind, name);
        return record;
    }

    public ObjectNode createAnnotationStore(JsonNode in, String region) {
        String format = required(in, "storeFormat");
        reference(in.path("reference"), region, !"TSV".equals(format));
        if ("TSV".equals(format) && !in.path("storeOptions").path("tsvStoreOptions").isObject()) {
            throw OmicsStore.validation("TSV annotation stores need storeOptions.tsvStoreOptions.");
        }
        ObjectNode record = createStore(in, region, OmicsStore.ANNOTATION_STORE, "annotationStore");
        record.put("storeFormat", format);
        if (in.path("storeOptions").isObject()) {
            record.set("storeOptions", in.get("storeOptions").deepCopy());
        }
        String versionName = in.path("versionName").asText("default");
        record.put("numVersions", 1);
        String name = record.path("name").asText();
        store.put(region, OmicsStore.ANNOTATION_STORE, name, record);
        version(region, record, versionName, in.path("description").asText(""), null, empty());
        ObjectNode out = empty();
        for (String field : List.of("id", "reference", "storeFormat", "storeOptions", "status", "name",
                "creationTime")) {
            if (record.has(field)) {
                out.set(field, record.get(field));
            }
        }
        out.put("versionName", versionName);
        return out;
    }

    public ObjectNode createVariantStore(JsonNode in, String region) {
        reference(in.path("reference"), region, true);
        ObjectNode record = createStore(in, region, OmicsStore.VARIANT_STORE, "variantStore");
        ObjectNode out = empty();
        for (String field : List.of("id", "reference", "status", "name", "creationTime")) {
            out.set(field, record.get(field));
        }
        return out;
    }

    private ObjectNode storeRecord(String region, String kind, String name) {
        return store.get(region, kind, name).orElseThrow(() ->
                OmicsStore.notFound("Store " + name + " doesn't exist."));
    }

    public ObjectNode getAnnotationStore(JsonNode in, String region) {
        ObjectNode view = OmicsStore.view(storeRecord(region, OmicsStore.ANNOTATION_STORE, required(in, "name")));
        view.set("tags", common.tags(store.account(), view.path("storeArn").asText()));
        return view;
    }

    public ObjectNode getVariantStore(JsonNode in, String region) {
        ObjectNode view = OmicsStore.view(storeRecord(region, OmicsStore.VARIANT_STORE, required(in, "name")));
        view.set("tags", common.tags(store.account(), view.path("storeArn").asText()));
        return view;
    }

    private ObjectNode updateStore(JsonNode in, String region, String kind) {
        ObjectNode record = storeRecord(region, kind, required(in, "name"));
        if (in.hasNonNull("description")) {
            record.put("description", in.get("description").asText());
        }
        record.put("updateTime", OmicsStore.timestamp());
        store.put(region, kind, record.path("name").asText(), record);
        ObjectNode view = OmicsStore.view(record);
        view.remove(List.of("storeArn", "sseConfig", "statusMessage", "storeSizeBytes", "numVersions"));
        return view;
    }

    public ObjectNode updateAnnotationStore(JsonNode in, String region) {
        return updateStore(in, region, OmicsStore.ANNOTATION_STORE);
    }

    public ObjectNode updateVariantStore(JsonNode in, String region) {
        return updateStore(in, region, OmicsStore.VARIANT_STORE);
    }

    private ObjectNode deleteStore(JsonNode in, String region, String kind) {
        ObjectNode record = storeRecord(region, kind, required(in, "name"));
        boolean force = in.path("force").asBoolean(false);
        if (!force && record.path("storeSizeBytes").asLong() > 0) {
            throw OmicsStore.conflict("Store " + record.path("name").asText() + " has data; delete it with force.");
        }
        String name = record.path("name").asText();
        record.put("status", "DELETING");
        store.put(region, kind, name, record);
        String account = store.account();
        common.later(account, region, () -> {
            store.delete(region, kind, name);
            for (ObjectNode version : store.list(region, OmicsStore.ANNOTATION_VERSION)) {
                if (name.equals(version.path("name").asText())) {
                    store.delete(region, OmicsStore.ANNOTATION_VERSION, name + "/" + version.path("versionName")
                            .asText());
                    common.untagged(account, version.path("versionArn").asText());
                }
            }
            common.untagged(account, record.path("storeArn").asText());
        });
        ObjectNode out = empty();
        out.put("status", "DELETING");
        return out;
    }

    public ObjectNode deleteAnnotationStore(JsonNode in, String region) {
        return deleteStore(in, region, OmicsStore.ANNOTATION_STORE);
    }

    public ObjectNode deleteVariantStore(JsonNode in, String region) {
        return deleteStore(in, region, OmicsStore.VARIANT_STORE);
    }

    private ObjectNode listStores(JsonNode in, String region, String kind, String name) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, kind)) {
            if (wanted(record, in)) {
                ObjectNode item = OmicsStore.view(record);
                item.remove(List.of("storeOptions", "numVersions"));
                items.add(item);
            }
        }
        return page(items, in, name);
    }

    public ObjectNode listAnnotationStores(JsonNode in, String region) {
        return listStores(in, region, OmicsStore.ANNOTATION_STORE, "annotationStores");
    }

    public ObjectNode listVariantStores(JsonNode in, String region) {
        return listStores(in, region, OmicsStore.VARIANT_STORE, "variantStores");
    }

    // ================================================================ annotation store versions

    private ObjectNode version(String region, ObjectNode annotationStore, String versionName, String description,
                               JsonNode options, JsonNode tags) {
        String name = annotationStore.path("name").asText();
        if (store.get(region, OmicsStore.ANNOTATION_VERSION, name + "/" + versionName).isPresent()) {
            throw OmicsStore.conflict("Version " + versionName + " of " + name + " already exists.");
        }
        String account = store.account();
        ObjectNode version = store.record();
        String now = OmicsStore.timestamp();
        version.put("storeId", annotationStore.path("id").asText());
        version.put("id", UUID.randomUUID().toString());
        version.put("status", "CREATING");
        version.put("versionArn", common.arn(region, account, "annotationStore/" + name + "/version/" + versionName));
        version.put("name", name);
        version.put("versionName", versionName);
        version.put("description", description);
        version.put("creationTime", now);
        version.put("updateTime", now);
        version.put("statusMessage", "");
        version.put("versionSizeBytes", 0);
        if (options != null && options.isObject()) {
            version.set("versionOptions", options.deepCopy());
        }
        store.put(region, OmicsStore.ANNOTATION_VERSION, name + "/" + versionName, version);
        common.tagged(account, version.path("versionArn").asText(), tags);
        settle(account, region, OmicsStore.ANNOTATION_VERSION, name + "/" + versionName);
        return version;
    }

    private ObjectNode versionRecord(String region, String name, String versionName) {
        storeRecord(region, OmicsStore.ANNOTATION_STORE, name);
        return store.get(region, OmicsStore.ANNOTATION_VERSION, name + "/" + versionName).orElseThrow(() ->
                OmicsStore.notFound("Version " + versionName + " of " + name + " doesn't exist."));
    }

    public ObjectNode createAnnotationStoreVersion(JsonNode in, String region) {
        ObjectNode annotationStore = storeRecord(region, OmicsStore.ANNOTATION_STORE, required(in, "name"));
        ObjectNode version = version(region, annotationStore, required(in, "versionName"),
                in.path("description").asText(""), in.path("versionOptions"), in.path("tags"));
        annotationStore.put("numVersions", annotationStore.path("numVersions").asInt() + 1);
        store.put(region, OmicsStore.ANNOTATION_STORE, annotationStore.path("name").asText(), annotationStore);
        ObjectNode out = empty();
        for (String field : List.of("id", "versionName", "storeId", "versionOptions", "name", "status",
                "creationTime")) {
            if (version.has(field)) {
                out.set(field, version.get(field));
            }
        }
        return out;
    }

    public ObjectNode getAnnotationStoreVersion(JsonNode in, String region) {
        ObjectNode view = OmicsStore.view(versionRecord(region, required(in, "name"), required(in, "versionName")));
        view.set("tags", common.tags(store.account(), view.path("versionArn").asText()));
        return view;
    }

    public ObjectNode updateAnnotationStoreVersion(JsonNode in, String region) {
        ObjectNode version = versionRecord(region, required(in, "name"), required(in, "versionName"));
        if (in.hasNonNull("description")) {
            version.put("description", in.get("description").asText());
        }
        version.put("updateTime", OmicsStore.timestamp());
        store.put(region, OmicsStore.ANNOTATION_VERSION, version.path("name").asText() + "/"
                + version.path("versionName").asText(), version);
        ObjectNode view = OmicsStore.view(version);
        view.remove(List.of("versionArn", "statusMessage", "versionSizeBytes", "versionOptions"));
        return view;
    }

    public ObjectNode listAnnotationStoreVersions(JsonNode in, String region) {
        String name = required(in, "name");
        storeRecord(region, OmicsStore.ANNOTATION_STORE, name);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode version : store.list(region, OmicsStore.ANNOTATION_VERSION)) {
            if (name.equals(version.path("name").asText()) && wanted(version, in)) {
                ObjectNode item = OmicsStore.view(version);
                item.remove("versionOptions");
                items.add(item);
            }
        }
        return page(items, in, "annotationStoreVersions");
    }

    public ObjectNode deleteAnnotationStoreVersions(JsonNode in, String region) {
        String name = required(in, "name");
        ObjectNode annotationStore = storeRecord(region, OmicsStore.ANNOTATION_STORE, name);
        boolean force = in.path("force").asBoolean(false);
        ObjectNode out = empty();
        ArrayNode errors = out.putArray("errors");
        String account = store.account();
        for (JsonNode node : in.path("versions")) {
            String versionName = node.asText();
            ObjectNode version = store.get(region, OmicsStore.ANNOTATION_VERSION, name + "/" + versionName)
                    .orElse(null);
            if (version == null) {
                errors.addObject().put("versionName", versionName).put("message", "The version doesn't exist.");
            } else if (!force && version.path("versionSizeBytes").asLong() > 0) {
                errors.addObject().put("versionName", versionName).put("message",
                        "The version has data; delete it with force.");
            } else {
                store.delete(region, OmicsStore.ANNOTATION_VERSION, name + "/" + versionName);
                common.untagged(account, version.path("versionArn").asText());
                annotationStore.put("numVersions", Math.max(0, annotationStore.path("numVersions").asInt() - 1));
            }
        }
        store.put(region, OmicsStore.ANNOTATION_STORE, name, annotationStore);
        return out;
    }

    // ================================================================ import jobs

    /** Counts the records of an annotation or variant file: lines that aren't headers, less a TSV's header row. */
    static long records(byte[] data, String format) throws IOException {
        byte[] content = OmicsFiles.gzipped(data) ? OmicsFiles.gunzip(data) : data;
        long count = 0;
        boolean header = "TSV".equals(format);
        for (String line : new String(content, StandardCharsets.UTF_8).split("\\r?\\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (header) {
                header = false;
                continue;
            }
            if ("VCF".equals(format) && line.split("\t").length < 8) {
                throw new IllegalArgumentException("A VCF record has fewer than 8 columns.");
            }
            if ("GFF".equals(format) && line.split("\t").length != 9) {
                throw new IllegalArgumentException("A GFF record doesn't have 9 columns.");
            }
            count++;
        }
        return count;
    }

    private ObjectNode startImport(JsonNode in, String region, String kind, String storeKind) {
        String destination = required(in, "destinationName");
        ObjectNode target = storeRecord(region, storeKind, destination);
        if (!"ACTIVE".equals(target.path("status").asText())) {
            throw OmicsStore.conflict("Store " + destination + " isn't ACTIVE.");
        }
        String account = store.account();
        String role = required(in, "roleArn");
        common.role(role, account, region);
        String id = UUID.randomUUID().toString();
        ObjectNode job = store.record();
        String now = OmicsStore.timestamp();
        job.put("id", id);
        job.put("destinationName", destination);
        if (OmicsStore.ANNOTATION_IMPORT.equals(kind)) {
            String versionName = in.path("versionName").asText("default");
            versionRecord(region, destination, versionName);
            job.put("versionName", versionName);
            job.set("formatOptions", in.path("formatOptions").isObject() ? in.get("formatOptions").deepCopy()
                    : empty());
        }
        job.put("roleArn", role);
        job.put("status", "SUBMITTED");
        job.put("statusMessage", "");
        job.put("creationTime", now);
        job.put("updateTime", now);
        job.put("completionTime", now);
        job.put("runLeftNormalization", in.path("runLeftNormalization").asBoolean(false));
        if (in.path("annotationFields").isObject()) {
            job.set("annotationFields", in.get("annotationFields").deepCopy());
        }
        ArrayNode items = job.putArray("items");
        in.path("items").forEach(item -> items.addObject().put("source", required(item, "source"))
                .put("jobStatus", "SUBMITTED"));
        store.put(region, kind, id, job);
        common.later(account, region, () -> runImport(region, kind, storeKind, id));
        ObjectNode out = empty();
        out.put("jobId", id);
        return out;
    }

    private void runImport(String region, String kind, String storeKind, String id) {
        ObjectNode job = store.get(region, kind, id).orElse(null);
        if (job == null || !"SUBMITTED".equals(job.path("status").asText())) {
            return;
        }
        String destination = job.path("destinationName").asText();
        ObjectNode target = store.get(region, storeKind, destination).orElse(null);
        if (target == null) {
            job.put("status", "FAILED");
            job.put("statusMessage", "Store " + destination + " no longer exists.");
            store.put(region, kind, id, job);
            return;
        }
        String format = OmicsStore.VARIANT_STORE.equals(storeKind) ? "VCF" : target.path("storeFormat").asText();
        int failed = 0;
        long bytes = 0;
        long records = 0;
        for (JsonNode node : job.path("items")) {
            ObjectNode item = (ObjectNode) node;
            try {
                byte[] data = common.read(item.path("source").asText());
                records += records(data, format);
                bytes += data.length;
                item.put("jobStatus", "COMPLETED");
            } catch (IOException | RuntimeException e) {
                failed++;
                item.put("jobStatus", "FAILED");
                item.put("statusMessage", e.getMessage());
            }
        }
        int total = job.path("items").size();
        job.put("status", failed == 0 ? "COMPLETED" : failed == total ? "FAILED" : "COMPLETED_WITH_FAILURES");
        job.put("statusMessage", records + " records imported.");
        String now = OmicsStore.timestamp();
        job.put("updateTime", now);
        job.put("completionTime", now);
        store.put(region, kind, id, job);
        target.put("storeSizeBytes", target.path("storeSizeBytes").asLong() + bytes);
        target.put("updateTime", now);
        store.put(region, storeKind, destination, target);
        if (job.has("versionName")) {
            String key = destination + "/" + job.path("versionName").asText();
            long added = bytes;
            store.get(region, OmicsStore.ANNOTATION_VERSION, key).ifPresent(version -> {
                version.put("versionSizeBytes", version.path("versionSizeBytes").asLong() + added);
                store.put(region, OmicsStore.ANNOTATION_VERSION, key, version);
            });
        }
    }

    private ObjectNode importJob(String region, String kind, String id) {
        return store.get(region, kind, id).orElseThrow(() ->
                OmicsStore.notFound("Import job " + id + " doesn't exist."));
    }

    private ObjectNode cancelImport(JsonNode in, String region, String kind) {
        ObjectNode job = importJob(region, kind, required(in, "jobId"));
        if (!Set.of("SUBMITTED", "IN_PROGRESS").contains(job.path("status").asText())) {
            throw OmicsStore.validation("Import job " + job.path("id").asText() + " is "
                    + job.path("status").asText() + " and can't be cancelled.");
        }
        job.put("status", "CANCELLED");
        job.put("updateTime", OmicsStore.timestamp());
        store.put(region, kind, job.path("id").asText(), job);
        return empty();
    }

    private ObjectNode listImports(JsonNode in, String region, String kind, String name) {
        List<ObjectNode> items = new ArrayList<>();
        JsonNode filter = in.path("filter");
        for (ObjectNode job : store.list(region, kind)) {
            if (wanted(job, in) && (!filter.hasNonNull("storeName")
                    || filter.get("storeName").asText().equals(job.path("destinationName").asText()))) {
                ObjectNode item = OmicsStore.view(job);
                item.remove(List.of("items", "statusMessage", "formatOptions"));
                items.add(item);
            }
        }
        return page(items, in, name);
    }

    public ObjectNode startAnnotationImportJob(JsonNode in, String region) {
        return startImport(in, region, OmicsStore.ANNOTATION_IMPORT, OmicsStore.ANNOTATION_STORE);
    }

    public ObjectNode startVariantImportJob(JsonNode in, String region) {
        return startImport(in, region, OmicsStore.VARIANT_IMPORT, OmicsStore.VARIANT_STORE);
    }

    public ObjectNode getAnnotationImportJob(JsonNode in, String region) {
        return OmicsStore.view(importJob(region, OmicsStore.ANNOTATION_IMPORT, required(in, "jobId")));
    }

    public ObjectNode getVariantImportJob(JsonNode in, String region) {
        return OmicsStore.view(importJob(region, OmicsStore.VARIANT_IMPORT, required(in, "jobId")));
    }

    public ObjectNode cancelAnnotationImportJob(JsonNode in, String region) {
        return cancelImport(in, region, OmicsStore.ANNOTATION_IMPORT);
    }

    public ObjectNode cancelVariantImportJob(JsonNode in, String region) {
        return cancelImport(in, region, OmicsStore.VARIANT_IMPORT);
    }

    public ObjectNode listAnnotationImportJobs(JsonNode in, String region) {
        return listImports(in, region, OmicsStore.ANNOTATION_IMPORT, "annotationImportJobs");
    }

    public ObjectNode listVariantImportJobs(JsonNode in, String region) {
        return listImports(in, region, OmicsStore.VARIANT_IMPORT, "variantImportJobs");
    }

    // ================================================================ shares

    public ObjectNode createShare(JsonNode in, String region) {
        String arn = required(in, "resourceArn");
        String subscriber = required(in, "principalSubscriber");
        if (!AwsArnUtils.isArnFor(arn, OmicsStore.SERVICE)) {
            throw OmicsStore.validation("The resource " + arn + " isn't a HealthOmics ARN.");
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String type = resource.substring(0, Math.max(0, resource.indexOf('/')));
        if (!SHAREABLE.contains(type)) {
            throw OmicsStore.validation("Only annotation stores, variant stores and workflows can be shared.");
        }
        if (!subscriber.matches("\\d{12}")) {
            throw OmicsStore.validation("principalSubscriber must be an account ID.");
        }
        common.listTagsForResource(empty().put("resourceArn", arn));
        String account = store.account();
        ObjectNode share = store.record();
        String now = OmicsStore.timestamp();
        String id = UUID.randomUUID().toString();
        share.put("shareId", id);
        share.put("resourceArn", arn);
        share.put("resourceId", resource.substring(resource.indexOf('/') + 1));
        share.put("principalSubscriber", subscriber);
        share.put("ownerId", account);
        share.put("status", "PENDING");
        share.put("statusMessage", "");
        if (in.hasNonNull("shareName")) {
            share.put("shareName", in.get("shareName").asText());
        }
        share.put("creationTime", now);
        share.put("updateTime", now);
        store.putForAccount(OmicsStore.SHARE, OmicsStore.GLOBAL_TAGS, OmicsStore.SHARE, id, share);
        ObjectNode out = empty();
        out.put("shareId", id);
        out.put("status", "PENDING");
        if (share.has("shareName")) {
            out.put("shareName", share.path("shareName").asText());
        }
        return out;
    }

    private ObjectNode share(String id) {
        return store.getForAccount(OmicsStore.SHARE, OmicsStore.GLOBAL_TAGS, OmicsStore.SHARE, id)
                .filter(share -> !"DELETED".equals(share.path("status").asText()))
                .filter(share -> {
                    String account = store.account();
                    return account.equals(share.path("ownerId").asText())
                            || account.equals(share.path("principalSubscriber").asText());
                })
                .orElseThrow(() -> OmicsStore.notFound("Share " + id + " doesn't exist."));
    }

    public ObjectNode acceptShare(JsonNode in, String region) {
        ObjectNode share = share(required(in, "shareId"));
        if (!store.account().equals(share.path("principalSubscriber").asText())) {
            throw OmicsStore.denied("Only the share's subscriber can accept it.");
        }
        share.put("status", "ACTIVE");
        share.put("updateTime", OmicsStore.timestamp());
        store.putForAccount(OmicsStore.SHARE, OmicsStore.GLOBAL_TAGS, OmicsStore.SHARE,
                share.path("shareId").asText(), share);
        ObjectNode out = empty();
        out.put("status", "ACTIVE");
        return out;
    }

    public ObjectNode getShare(JsonNode in, String region) {
        ObjectNode out = empty();
        out.set("share", OmicsStore.view(share(required(in, "shareId"))));
        return out;
    }

    public ObjectNode deleteShare(JsonNode in, String region) {
        ObjectNode share = share(required(in, "shareId"));
        share.put("status", "DELETED");
        share.put("updateTime", OmicsStore.timestamp());
        store.putForAccount(OmicsStore.SHARE, OmicsStore.GLOBAL_TAGS, OmicsStore.SHARE,
                share.path("shareId").asText(), share);
        ObjectNode out = empty();
        out.put("status", "DELETED");
        return out;
    }

    public ObjectNode listShares(JsonNode in, String region) {
        String owner = required(in, "resourceOwner");
        String account = store.account();
        JsonNode filter = in.path("filter");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode share : store.listForAccount(OmicsStore.SHARE, OmicsStore.GLOBAL_TAGS, OmicsStore.SHARE)) {
            boolean mine = account.equals(share.path("ownerId").asText());
            boolean theirs = account.equals(share.path("principalSubscriber").asText());
            if (!("SELF".equals(owner) ? mine : theirs)) {
                continue;
            }
            if (filter.path("resourceArns").isArray() && !filter.path("resourceArns").isEmpty()
                    && !filter.path("resourceArns").toString().contains("\"" + share.path("resourceArn").asText()
                    + "\"")) {
                continue;
            }
            if (filter.path("status").isArray() && !filter.path("status").isEmpty()
                    && !filter.path("status").toString().contains("\"" + share.path("status").asText() + "\"")) {
                continue;
            }
            items.add(OmicsStore.view(share));
        }
        return page(items, in, "shares");
    }

    /** Whether an account may use a resource another account shared with it. */
    boolean sharedWith(String account, String arn) {
        return store.listForAccount(OmicsStore.SHARE, OmicsStore.GLOBAL_TAGS, OmicsStore.SHARE).stream()
                .anyMatch(share -> arn.equals(share.path("resourceArn").asText())
                        && account.equals(share.path("principalSubscriber").asText())
                        && "ACTIVE".equals(share.path("status").asText()));
    }
}
