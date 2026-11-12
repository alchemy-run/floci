package io.floci.aws.services.personalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.services.personalize.PersonalizeData.Field;
import io.floci.aws.services.personalize.PersonalizeModels.Recipe;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amazon Personalize's control plane: schemas, dataset groups and datasets, import, export and deletion jobs, event
 * trackers, solutions and the versions trained from them, campaigns, recommenders, filters, batch jobs and metric
 * attributions. Jobs run after the transition delay; training is {@link PersonalizeModels}.
 */
@ApplicationScoped
public class PersonalizeService {

    private static final String PENDING = "CREATE PENDING";
    private static final String IN_PROGRESS = "CREATE IN_PROGRESS";
    private static final String ACTIVE = "ACTIVE";
    private static final String FAILED = "CREATE FAILED";
    private static final String HEX = "0123456789abcdef";

    private final PersonalizeStore store;
    private final PersonalizeCommon common;
    private final PersonalizeInference inference;
    private final ObjectMapper mapper;

    @Inject
    public PersonalizeService(PersonalizeStore store, PersonalizeCommon common, PersonalizeInference inference) {
        this.store = store;
        this.common = common;
        this.inference = inference;
        this.mapper = store.mapper();
    }

    private String arn(String region, String type, String id) {
        return store.arnFor(store.account(), region, type + "/" + id);
    }

    private ObjectNode out() {
        return mapper.createObjectNode();
    }

    private static String required(JsonNode in, String member) {
        String value = in.path(member).asText("");
        if (value.isBlank()) {
            throw PersonalizeStore.invalid(member + " is required.");
        }
        return value;
    }

    private static String text(JsonNode in, String member) {
        return in.hasNonNull(member) && !in.get(member).asText().isBlank() ? in.get(member).asText() : null;
    }

    private ObjectNode created(String status) {
        ObjectNode record = store.record();
        double now = PersonalizeStore.now();
        record.put("creationDateTime", now).put("lastUpdatedDateTime", now);
        if (status != null) {
            record.put("status", status);
        }
        return record;
    }

    private void ensureNew(String region, String kind, String id, String label) {
        if (store.get(region, kind, id).isPresent()) {
            throw PersonalizeStore.exists("The " + label + " " + id + " already exists.");
        }
    }

    private static ArrayNode summaries(ObjectMapper mapper, List<ObjectNode> records, String... fields) {
        ArrayNode list = mapper.createArrayNode();
        for (ObjectNode record : records) {
            ObjectNode summary = list.addObject();
            for (String field : fields) {
                if (record.has(field)) {
                    summary.set(field, record.get(field));
                }
            }
        }
        return list;
    }

    private ObjectNode list(JsonNode in, List<ObjectNode> records, String name, String... fields) {
        ObjectNode out = out();
        List<JsonNode> items = new ArrayList<>();
        summaries(mapper, records, fields).forEach(items::add);
        PersonalizeStore.page(items, in, out, name, 100);
        return out;
    }

    private static boolean active(ObjectNode record) {
        return ACTIVE.equals(record.path("status").asText());
    }

    // ================================================================ schemas

    public ObjectNode createSchema(JsonNode in, String region) {
        String name = required(in, "name");
        String schema = required(in, "schema");
        PersonalizeData.fields(mapper, schema);
        ensureNew(region, PersonalizeStore.SCHEMA, name, "schema");
        ObjectNode record = created(null);
        record.put("name", name).put("schemaArn", arn(region, "schema", name)).put("schema", schema);
        if (text(in, "domain") != null) {
            record.put("domain", in.get("domain").asText());
        }
        store.put(region, PersonalizeStore.SCHEMA, name, record);
        return out().put("schemaArn", record.get("schemaArn").asText());
    }

    public ObjectNode describeSchema(JsonNode in, String region) {
        ObjectNode record = store.require(required(in, "schemaArn"), PersonalizeStore.SCHEMA, "schema");
        ObjectNode out = out();
        out.set("schema", PersonalizeStore.view(record));
        return out;
    }

    public ObjectNode listSchemas(JsonNode in, String region) {
        return list(in, store.all(region, PersonalizeStore.SCHEMA), "schemas", "name", "schemaArn",
                "creationDateTime", "lastUpdatedDateTime", "domain");
    }

    public ObjectNode deleteSchema(JsonNode in, String region) {
        ObjectNode record = store.require(required(in, "schemaArn"), PersonalizeStore.SCHEMA, "schema");
        String arn = record.get("schemaArn").asText();
        for (ObjectNode dataset : store.all(region, PersonalizeStore.DATASET)) {
            if (arn.equals(dataset.path("schemaArn").asText())) {
                throw PersonalizeStore.inUse("The schema is used by the dataset " + dataset.get("datasetArn")
                        .asText() + ".");
            }
        }
        store.delete(PersonalizeStore.region(record), PersonalizeStore.SCHEMA, PersonalizeStore.id(record));
        return out();
    }

    // ================================================================ dataset groups

    public ObjectNode createDatasetGroup(JsonNode in, String region) {
        String name = required(in, "name");
        ensureNew(region, PersonalizeStore.GROUP, name, "dataset group");
        if (text(in, "kmsKeyArn") != null && text(in, "roleArn") == null) {
            throw PersonalizeStore.invalid("A dataset group encrypted with a KMS key needs a roleArn.");
        }
        if (text(in, "roleArn") != null) {
            common.role(in.get("roleArn").asText(), region);
        }
        ObjectNode record = created(null);
        record.put("name", name).put("datasetGroupArn", arn(region, "dataset-group", name));
        for (String member : List.of("roleArn", "kmsKeyArn", "domain")) {
            if (text(in, member) != null) {
                record.put(member, in.get(member).asText());
            }
        }
        store.transition(record, PENDING, ACTIVE);
        PersonalizeStore.addTags(record, in.path("tags"));
        store.put(region, PersonalizeStore.GROUP, name, record);
        ObjectNode out = out().put("datasetGroupArn", record.get("datasetGroupArn").asText());
        if (record.has("domain")) {
            out.put("domain", record.get("domain").asText());
        }
        return out;
    }

    ObjectNode group(String arn) {
        return store.require(arn, PersonalizeStore.GROUP, "dataset group");
    }

    public ObjectNode describeDatasetGroup(JsonNode in, String region) {
        ObjectNode out = out();
        out.set("datasetGroup", PersonalizeStore.view(group(required(in, "datasetGroupArn"))));
        return out;
    }

    public ObjectNode listDatasetGroups(JsonNode in, String region) {
        return list(in, store.all(region, PersonalizeStore.GROUP), "datasetGroups", "name", "datasetGroupArn",
                "status", "creationDateTime", "lastUpdatedDateTime", "failureReason", "domain");
    }

    private List<ObjectNode> children(String region, String kind, String groupArn) {
        List<ObjectNode> found = new ArrayList<>();
        for (ObjectNode record : store.all(region, kind)) {
            if (groupArn.equals(record.path("datasetGroupArn").asText())) {
                found.add(record);
            }
        }
        return found;
    }

    public ObjectNode deleteDatasetGroup(JsonNode in, String region) {
        ObjectNode record = group(required(in, "datasetGroupArn"));
        String arn = record.get("datasetGroupArn").asText();
        String groupRegion = PersonalizeStore.region(record);
        Map<String, String> kinds = new LinkedHashMap<>();
        kinds.put(PersonalizeStore.DATASET, "datasets");
        kinds.put(PersonalizeStore.TRACKER, "event trackers");
        kinds.put(PersonalizeStore.SOLUTION, "solutions");
        kinds.put(PersonalizeStore.RECOMMENDER, "recommenders");
        kinds.put(PersonalizeStore.FILTER, "filters");
        kinds.put(PersonalizeStore.ATTRIBUTION, "metric attributions");
        for (Map.Entry<String, String> kind : kinds.entrySet()) {
            if (!children(groupRegion, kind.getKey(), arn).isEmpty()) {
                throw PersonalizeStore.inUse("The dataset group still has " + kind.getValue() + "; delete them first.");
            }
        }
        store.delete(groupRegion, PersonalizeStore.GROUP, PersonalizeStore.id(record));
        for (ObjectNode job : children(groupRegion, PersonalizeStore.DELETION, arn)) {
            store.delete(groupRegion, PersonalizeStore.DELETION, PersonalizeStore.id(job));
        }
        return out();
    }

    // ================================================================ datasets

    public ObjectNode createDataset(JsonNode in, String region) {
        String name = required(in, "name");
        ObjectNode schema = store.require(required(in, "schemaArn"), PersonalizeStore.SCHEMA, "schema");
        ObjectNode group = group(required(in, "datasetGroupArn"));
        if (!active(group)) {
            throw PersonalizeStore.inUse("The dataset group is " + group.path("status").asText() + ", not ACTIVE.");
        }
        String type = PersonalizeData.datasetType(required(in, "datasetType"));
        Map<String, Field> fields = PersonalizeData.fields(mapper, schema.get("schema").asText());
        PersonalizeData.checkSchemaFor(fields, type);
        String groupDomain = group.path("domain").asText("");
        String schemaDomain = schema.path("domain").asText("");
        if (!groupDomain.equals(schemaDomain)) {
            throw PersonalizeStore.invalid(groupDomain.isEmpty()
                    ? "A domain schema can only be used in a dataset group of its domain."
                    : "A " + groupDomain + " dataset group's datasets need a " + groupDomain + " schema.");
        }
        String id = group.get("name").asText() + "/" + type;
        if (store.get(region, PersonalizeStore.DATASET, id).isPresent()) {
            throw PersonalizeStore.exists("The dataset group already has a " + type + " dataset.");
        }
        for (ObjectNode existing : children(region, PersonalizeStore.DATASET, group.get("datasetGroupArn").asText())) {
            if (existing.path("name").asText().equals(name)) {
                throw PersonalizeStore.exists("A dataset named " + name + " already exists in the dataset group.");
            }
        }
        ObjectNode record = created(null);
        record.put("name", name).put("datasetArn", arn(region, "dataset", id))
                .put("datasetGroupArn", group.get("datasetGroupArn").asText()).put("datasetType", type)
                .put("schemaArn", schema.get("schemaArn").asText());
        store.transition(record, PENDING, ACTIVE);
        PersonalizeStore.addTags(record, in.path("tags"));
        store.put(region, PersonalizeStore.DATASET, id, record);
        return out().put("datasetArn", record.get("datasetArn").asText());
    }

    ObjectNode dataset(String arn) {
        return store.require(arn, PersonalizeStore.DATASET, "dataset");
    }

    public ObjectNode describeDataset(JsonNode in, String region) {
        ObjectNode record = dataset(required(in, "datasetArn"));
        ObjectNode view = PersonalizeStore.view(record);
        if (PersonalizeData.INTERACTIONS.equals(record.path("datasetType").asText())) {
            for (ObjectNode tracker : children(PersonalizeStore.region(record), PersonalizeStore.TRACKER,
                    record.get("datasetGroupArn").asText())) {
                view.put("trackingId", tracker.get("trackingId").asText());
            }
        }
        ObjectNode out = out();
        out.set("dataset", view);
        return out;
    }

    public ObjectNode listDatasets(JsonNode in, String region) {
        String group = text(in, "datasetGroupArn");
        List<ObjectNode> records = group == null ? store.all(region, PersonalizeStore.DATASET)
                : children(region, PersonalizeStore.DATASET, group);
        return list(in, records, "datasets", "name", "datasetArn", "datasetType", "status", "creationDateTime",
                "lastUpdatedDateTime");
    }

    public ObjectNode updateDataset(JsonNode in, String region) {
        ObjectNode record = dataset(required(in, "datasetArn"));
        ObjectNode schema = store.require(required(in, "schemaArn"), PersonalizeStore.SCHEMA, "schema");
        if (!active(record)) {
            throw PersonalizeStore.inUse("The dataset is " + record.path("status").asText() + ".");
        }
        String type = record.get("datasetType").asText();
        Map<String, Field> next = PersonalizeData.fields(mapper, schema.get("schema").asText());
        PersonalizeData.checkSchemaFor(next, type);
        ObjectNode old = store.require(record.get("schemaArn").asText(), PersonalizeStore.SCHEMA, "schema");
        Map<String, Field> previous = PersonalizeData.fields(mapper, old.get("schema").asText());
        for (Map.Entry<String, Field> field : previous.entrySet()) {
            Field replacement = next.get(field.getKey());
            if (replacement == null || !replacement.type().equals(field.getValue().type())) {
                throw PersonalizeStore.invalid("The new schema must keep the field " + field.getValue().name()
                        + " with type " + field.getValue().type() + ".");
            }
        }
        double now = PersonalizeStore.now();
        record.put("schemaArn", schema.get("schemaArn").asText()).put("lastUpdatedDateTime", now);
        ObjectNode update = record.putObject("latestDatasetUpdate");
        update.put("schemaArn", schema.get("schemaArn").asText()).put("status", ACTIVE)
                .put("creationDateTime", now).put("lastUpdatedDateTime", now);
        store.put(PersonalizeStore.region(record), PersonalizeStore.DATASET, PersonalizeStore.id(record), record);
        return out().put("datasetArn", record.get("datasetArn").asText());
    }

    public ObjectNode deleteDataset(JsonNode in, String region) {
        ObjectNode record = dataset(required(in, "datasetArn"));
        String groupArn = record.get("datasetGroupArn").asText();
        String datasetRegion = PersonalizeStore.region(record);
        if (PersonalizeData.INTERACTIONS.equals(record.get("datasetType").asText())
                && !children(datasetRegion, PersonalizeStore.TRACKER, groupArn).isEmpty()) {
            throw PersonalizeStore.inUse("Delete the dataset group's event tracker before its Interactions dataset.");
        }
        for (String kind : List.of(PersonalizeStore.IMPORT, PersonalizeStore.EXPORT)) {
            for (ObjectNode job : store.all(datasetRegion, kind)) {
                if (job.path("datasetArn").asText().equals(record.get("datasetArn").asText())) {
                    if (job.path("status").asText().contains("PENDING")
                            || job.path("status").asText().contains("IN_PROGRESS")) {
                        throw PersonalizeStore.inUse("The dataset has a job in progress.");
                    }
                }
            }
        }
        store.delete(datasetRegion, PersonalizeStore.DATASET, PersonalizeStore.id(record));
        store.deleteData(store.account(), "dataset/" + PersonalizeStore.id(record));
        return out();
    }

    // ================================================================ import, export and deletion jobs

    public ObjectNode createDatasetImportJob(JsonNode in, String region) {
        String name = required(in, "jobName");
        ObjectNode dataset = dataset(required(in, "datasetArn"));
        String location = required(in.path("dataSource"), "dataLocation");
        PersonalizeCommon.s3Uri(location);
        if (text(in, "roleArn") != null) {
            common.role(in.get("roleArn").asText(), region);
        }
        if (!active(dataset)) {
            throw PersonalizeStore.inUse("The dataset is " + dataset.path("status").asText() + ", not ACTIVE.");
        }
        ensureNew(region, PersonalizeStore.IMPORT, name, "dataset import job");
        ObjectNode record = created(PENDING);
        record.put("jobName", name).put("datasetImportJobArn", arn(region, "dataset-import-job", name))
                .put("datasetArn", dataset.get("datasetArn").asText())
                .put("importMode", text(in, "importMode") == null ? "FULL" : in.get("importMode").asText())
                .put("publishAttributionMetricsToS3", in.path("publishAttributionMetricsToS3").asBoolean(false));
        record.putObject("dataSource").put("dataLocation", location);
        if (text(in, "roleArn") != null) {
            record.put("roleArn", in.get("roleArn").asText());
        }
        PersonalizeStore.addTags(record, in.path("tags"));
        store.put(region, PersonalizeStore.IMPORT, name, record);
        String account = store.account();
        common.later(account, region, () -> runImport(account, region, name));
        return out().put("datasetImportJobArn", record.get("datasetImportJobArn").asText());
    }

    private void runImport(String account, String region, String name) {
        ObjectNode job = store.get(region, PersonalizeStore.IMPORT, name).orElse(null);
        if (job == null) {
            return;
        }
        store.transition(job, IN_PROGRESS, null);
        store.put(region, PersonalizeStore.IMPORT, name, job);
        try {
            ObjectNode dataset = dataset(job.get("datasetArn").asText());
            ObjectNode schema = store.require(dataset.get("schemaArn").asText(), PersonalizeStore.SCHEMA, "schema");
            Map<String, Field> fields = PersonalizeData.fields(mapper, schema.get("schema").asText());
            String type = dataset.get("datasetType").asText();
            List<ObjectNode> rows = new ArrayList<>();
            for (String uri : common.objects(job.at("/dataSource/dataLocation").asText())) {
                rows.addAll(PersonalizeData.rows(mapper, fields, type, new String(common.read(uri),
                        StandardCharsets.UTF_8)));
            }
            String key = "dataset/" + PersonalizeStore.id(dataset);
            ObjectNode data = store.data(account, key).orElse(null);
            store.putData(account, key, PersonalizeData.append(mapper, data, type, rows, "bulk",
                    "FULL".equals(job.get("importMode").asText())));
            if (job.path("publishAttributionMetricsToS3").asBoolean()) {
                publishAttribution(region, dataset.get("datasetGroupArn").asText(), rows);
            }
            store.transition(job, ACTIVE, null);
        } catch (AwsException | IllegalStateException e) {
            store.transition(job, FAILED, null);
            job.put("failureReason", e.getMessage());
        }
        store.put(region, PersonalizeStore.IMPORT, name, job);
    }

    public ObjectNode describeDatasetImportJob(JsonNode in, String region) {
        ObjectNode out = out();
        out.set("datasetImportJob", PersonalizeStore.view(store.require(required(in, "datasetImportJobArn"),
                PersonalizeStore.IMPORT, "dataset import job")));
        return out;
    }

    public ObjectNode listDatasetImportJobs(JsonNode in, String region) {
        String dataset = text(in, "datasetArn");
        List<ObjectNode> records = store.all(region, PersonalizeStore.IMPORT).stream()
                .filter(job -> dataset == null || dataset.equals(job.path("datasetArn").asText())).toList();
        return list(in, records, "datasetImportJobs", "datasetImportJobArn", "jobName", "status",
                "creationDateTime", "lastUpdatedDateTime", "failureReason", "importMode");
    }

    public ObjectNode createDatasetExportJob(JsonNode in, String region) {
        String name = required(in, "jobName");
        ObjectNode dataset = dataset(required(in, "datasetArn"));
        String role = required(in, "roleArn");
        String path = required(in.at("/jobOutput/s3DataDestination"), "path");
        PersonalizeCommon.s3Uri(path);
        common.role(role, region);
        ensureNew(region, PersonalizeStore.EXPORT, name, "dataset export job");
        ObjectNode record = created(PENDING);
        record.put("jobName", name).put("datasetExportJobArn", arn(region, "dataset-export-job", name))
                .put("datasetArn", dataset.get("datasetArn").asText())
                .put("ingestionMode", text(in, "ingestionMode") == null ? "PUT" : in.get("ingestionMode").asText())
                .put("roleArn", role);
        record.set("jobOutput", in.get("jobOutput").deepCopy());
        PersonalizeStore.addTags(record, in.path("tags"));
        store.put(region, PersonalizeStore.EXPORT, name, record);
        String account = store.account();
        common.later(account, region, () -> runExport(account, region, name));
        return out().put("datasetExportJobArn", record.get("datasetExportJobArn").asText());
    }

    private void runExport(String account, String region, String name) {
        ObjectNode job = store.get(region, PersonalizeStore.EXPORT, name).orElse(null);
        if (job == null) {
            return;
        }
        try {
            ObjectNode dataset = dataset(job.get("datasetArn").asText());
            ObjectNode schema = store.require(dataset.get("schemaArn").asText(), PersonalizeStore.SCHEMA, "schema");
            Map<String, Field> fields = PersonalizeData.fields(mapper, schema.get("schema").asText());
            String mode = job.get("ingestionMode").asText();
            List<ObjectNode> rows = PersonalizeData.stored(store.data(account, "dataset/"
                    + PersonalizeStore.id(dataset)).orElse(null)).stream()
                    .filter(row -> PersonalizeData.exported(row, mode)).toList();
            String path = job.at("/jobOutput/s3DataDestination/path").asText();
            String prefix = path.endsWith("/") ? path : path + "/";
            common.write(prefix + name + "/" + dataset.get("datasetType").asText().toLowerCase() + ".csv",
                    PersonalizeData.toCsv(fields, rows).getBytes(StandardCharsets.UTF_8), "text/csv");
            store.transition(job, ACTIVE, null);
        } catch (AwsException | IllegalStateException e) {
            store.transition(job, FAILED, null);
            job.put("failureReason", e.getMessage());
        }
        store.put(region, PersonalizeStore.EXPORT, name, job);
    }

    public ObjectNode describeDatasetExportJob(JsonNode in, String region) {
        ObjectNode out = out();
        out.set("datasetExportJob", PersonalizeStore.view(store.require(required(in, "datasetExportJobArn"),
                PersonalizeStore.EXPORT, "dataset export job")));
        return out;
    }

    public ObjectNode listDatasetExportJobs(JsonNode in, String region) {
        String dataset = text(in, "datasetArn");
        List<ObjectNode> records = store.all(region, PersonalizeStore.EXPORT).stream()
                .filter(job -> dataset == null || dataset.equals(job.path("datasetArn").asText())).toList();
        return list(in, records, "datasetExportJobs", "datasetExportJobArn", "jobName", "status",
                "creationDateTime", "lastUpdatedDateTime", "failureReason");
    }

    public ObjectNode createDataDeletionJob(JsonNode in, String region) {
        String name = required(in, "jobName");
        ObjectNode group = group(required(in, "datasetGroupArn"));
        String location = required(in.path("dataSource"), "dataLocation");
        PersonalizeCommon.s3Uri(location);
        String role = required(in, "roleArn");
        common.role(role, region);
        ensureNew(region, PersonalizeStore.DELETION, name, "data deletion job");
        ObjectNode record = created(PENDING);
        record.put("jobName", name).put("dataDeletionJobArn", arn(region, "data-deletion-job", name))
                .put("datasetGroupArn", group.get("datasetGroupArn").asText()).put("roleArn", role);
        record.putObject("dataSource").put("dataLocation", location);
        PersonalizeStore.addTags(record, in.path("tags"));
        store.put(region, PersonalizeStore.DELETION, name, record);
        String account = store.account();
        common.later(account, region, () -> runDeletion(account, region, name));
        return out().put("dataDeletionJobArn", record.get("dataDeletionJobArn").asText());
    }

    private void runDeletion(String account, String region, String name) {
        ObjectNode job = store.get(region, PersonalizeStore.DELETION, name).orElse(null);
        if (job == null) {
            return;
        }
        try {
            Set<String> users = new HashSet<>();
            for (String uri : common.objects(job.at("/dataSource/dataLocation").asText())) {
                List<List<String>> table = PersonalizeData.csv(new String(common.read(uri), StandardCharsets.UTF_8));
                if (table.isEmpty()) {
                    continue;
                }
                int column = -1;
                for (int i = 0; i < table.get(0).size(); i++) {
                    if (table.get(0).get(i).trim().equalsIgnoreCase("USER_ID")) {
                        column = i;
                    }
                }
                if (column < 0) {
                    throw PersonalizeStore.invalid("The file " + uri + " has no USER_ID column.");
                }
                for (List<String> row : table.subList(1, table.size())) {
                    users.add(row.get(column));
                }
            }
            int deleted = 0;
            for (ObjectNode dataset : children(region, PersonalizeStore.DATASET, job.get("datasetGroupArn").asText())) {
                String key = "dataset/" + PersonalizeStore.id(dataset);
                ObjectNode data = store.data(account, key).orElse(null);
                if (data == null) {
                    continue;
                }
                List<ObjectNode> rows = PersonalizeData.stored(data);
                int before = rows.size();
                rows.removeIf(row -> users.contains(row.path("USER_ID").asText(null)));
                deleted += before - rows.size();
                ArrayNode array = data.putArray("rows");
                rows.forEach(array::add);
                store.putData(account, key, data);
            }
            job.put("numDeleted", deleted);
            store.transition(job, "COMPLETED", null);
        } catch (AwsException | IllegalStateException e) {
            store.transition(job, "FAILED", null);
            job.put("failureReason", e.getMessage());
        }
        store.put(region, PersonalizeStore.DELETION, name, job);
    }

    public ObjectNode describeDataDeletionJob(JsonNode in, String region) {
        ObjectNode out = out();
        out.set("dataDeletionJob", PersonalizeStore.view(store.require(required(in, "dataDeletionJobArn"),
                PersonalizeStore.DELETION, "data deletion job")));
        return out;
    }

    public ObjectNode listDataDeletionJobs(JsonNode in, String region) {
        String group = text(in, "datasetGroupArn");
        List<ObjectNode> records = group == null ? store.all(region, PersonalizeStore.DELETION)
                : children(region, PersonalizeStore.DELETION, group);
        return list(in, records, "dataDeletionJobs", "dataDeletionJobArn", "datasetGroupArn", "jobName", "status",
                "creationDateTime", "lastUpdatedDateTime", "failureReason");
    }

    // ================================================================ event trackers

    public ObjectNode createEventTracker(JsonNode in, String region) {
        String name = required(in, "name");
        ObjectNode group = group(required(in, "datasetGroupArn"));
        String groupArn = group.get("datasetGroupArn").asText();
        if (!children(region, PersonalizeStore.TRACKER, groupArn).isEmpty()) {
            throw PersonalizeStore.exists("The dataset group already has an event tracker.");
        }
        if (store.get(region, PersonalizeStore.DATASET, group.get("name").asText() + "/"
                + PersonalizeData.INTERACTIONS).isEmpty()) {
            throw PersonalizeStore.invalid("The dataset group needs an Interactions dataset before an event "
                    + "tracker.");
        }
        for (ObjectNode tracker : store.all(region, PersonalizeStore.TRACKER)) {
            if (tracker.path("name").asText().equals(name)) {
                throw PersonalizeStore.exists("The event tracker " + name + " already exists.");
            }
        }
        String id = PersonalizeStore.random(8, HEX);
        ObjectNode record = created(null);
        record.put("name", name).put("eventTrackerArn", arn(region, "event-tracker", id))
                .put("accountId", store.account()).put("trackingId", UUID.randomUUID().toString())
                .put("datasetGroupArn", groupArn);
        store.transition(record, PENDING, ACTIVE);
        PersonalizeStore.addTags(record, in.path("tags"));
        store.put(region, PersonalizeStore.TRACKER, id, record);
        return out().put("eventTrackerArn", record.get("eventTrackerArn").asText())
                .put("trackingId", record.get("trackingId").asText());
    }

    public ObjectNode describeEventTracker(JsonNode in, String region) {
        ObjectNode out = out();
        out.set("eventTracker", PersonalizeStore.view(store.require(required(in, "eventTrackerArn"),
                PersonalizeStore.TRACKER, "event tracker")));
        return out;
    }

    public ObjectNode listEventTrackers(JsonNode in, String region) {
        String group = text(in, "datasetGroupArn");
        List<ObjectNode> records = group == null ? store.all(region, PersonalizeStore.TRACKER)
                : children(region, PersonalizeStore.TRACKER, group);
        return list(in, records, "eventTrackers", "name", "eventTrackerArn", "status", "creationDateTime",
                "lastUpdatedDateTime");
    }

    public ObjectNode deleteEventTracker(JsonNode in, String region) {
        ObjectNode record = store.require(required(in, "eventTrackerArn"), PersonalizeStore.TRACKER,
                "event tracker");
        store.delete(PersonalizeStore.region(record), PersonalizeStore.TRACKER, PersonalizeStore.id(record));
        return out();
    }

    // ================================================================ recipes, algorithms and transformations

    static Recipe recipe(String arn) {
        String name = PersonalizeModels.nameOf(arn, "recipe");
        Recipe recipe = name == null ? null : PersonalizeModels.RECIPES.get(name);
        if (recipe == null) {
            throw PersonalizeStore.notFound("The recipe " + arn + " doesn't exist.");
        }
        return recipe;
    }

    private static final double RECIPE_DATE = 1_543_622_400;

    public ObjectNode describeRecipe(JsonNode in, String region) {
        String arn = required(in, "recipeArn");
        Recipe recipe = recipe(arn);
        String partition = AwsArnUtils.parse(arn).partition();
        ObjectNode out = out();
        ObjectNode view = out.putObject("recipe");
        view.put("name", recipe.name()).put("recipeArn", arn)
                .put("algorithmArn", PersonalizeModels.arnOf(partition, "algorithm", recipe.name()))
                .put("featureTransformationArn", PersonalizeModels.arnOf(partition, "feature-transformation",
                        recipe.name()))
                .put("status", ACTIVE).put("recipeType", recipe.type())
                .put("description", "Floci's " + recipe.mode() + " model.")
                .put("creationDateTime", RECIPE_DATE).put("lastUpdatedDateTime", RECIPE_DATE);
        return out;
    }

    public ObjectNode listRecipes(JsonNode in, String region) {
        String domain = text(in, "domain");
        String partition = AwsRegions.partitionFor(region);
        List<ObjectNode> records = new ArrayList<>();
        for (Recipe recipe : PersonalizeModels.RECIPES.values()) {
            if (domain != null && !domain.equals(recipe.domain())) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode().put("name", recipe.name())
                    .put("recipeArn", recipe.arn(partition)).put("status", ACTIVE).put("creationDateTime", RECIPE_DATE)
                    .put("lastUpdatedDateTime", RECIPE_DATE);
            if (recipe.domain() != null) {
                summary.put("domain", recipe.domain());
            }
            records.add(summary);
        }
        return list(in, records, "recipes", "name", "recipeArn", "status", "creationDateTime",
                "lastUpdatedDateTime", "domain");
    }

    private static Recipe recipeOf(String arn, String type) {
        String name = PersonalizeModels.nameOf(arn, type);
        if (name == null) {
            throw PersonalizeStore.invalid("The ARN " + arn + " isn't a " + type + " ARN.");
        }
        Recipe recipe = PersonalizeModels.RECIPES.get(name);
        if (recipe == null) {
            throw PersonalizeStore.notFound("The " + type + " " + arn + " doesn't exist.");
        }
        return recipe;
    }

    public ObjectNode describeAlgorithm(JsonNode in, String region) {
        String arn = required(in, "algorithmArn");
        Recipe recipe = recipeOf(arn, "algorithm");
        ObjectNode out = out();
        ObjectNode view = out.putObject("algorithm");
        view.put("name", recipe.name()).put("algorithmArn", arn).put("trainingInputMode", "File")
                .put("creationDateTime", RECIPE_DATE).put("lastUpdatedDateTime", RECIPE_DATE);
        view.putObject("algorithmImage").put("name", recipe.name()).put("dockerURI", "floci/" + recipe.name());
        view.putObject("defaultHyperParameters");
        view.putObject("defaultHyperParameterRanges");
        view.putObject("defaultResourceConfig");
        return out;
    }

    public ObjectNode describeFeatureTransformation(JsonNode in, String region) {
        String arn = required(in, "featureTransformationArn");
        Recipe recipe = recipeOf(arn, "feature-transformation");
        ObjectNode out = out();
        ObjectNode view = out.putObject("featureTransformation");
        view.put("name", recipe.name()).put("featureTransformationArn", arn).put("status", ACTIVE)
                .put("creationDateTime", RECIPE_DATE).put("lastUpdatedDateTime", RECIPE_DATE);
        view.putObject("defaultParameters");
        return out;
    }

    // ================================================================ solutions

    public ObjectNode createSolution(JsonNode in, String region) {
        String name = required(in, "name");
        ObjectNode group = group(required(in, "datasetGroupArn"));
        boolean autoML = in.path("performAutoML").asBoolean(false);
        String recipeArn = text(in, "recipeArn");
        if (autoML == (recipeArn != null)) {
            throw PersonalizeStore.invalid(autoML ? "A solution with performAutoML takes no recipeArn."
                    : "A solution needs a recipeArn or performAutoML.");
        }
        if (recipeArn != null) {
            recipe(recipeArn);
        } else {
            JsonNode recipes = in.at("/solutionConfig/autoMLConfig/recipeList");
            if (recipes.isArray()) {
                recipes.forEach(arn -> recipe(arn.asText()));
            }
        }
        if (!active(group)) {
            throw PersonalizeStore.inUse("The dataset group is " + group.path("status").asText() + ", not ACTIVE.");
        }
        ensureNew(region, PersonalizeStore.SOLUTION, name, "solution");
        String expression = in.at("/solutionConfig/autoTrainingConfig/schedulingExpression").asText("");
        if (!expression.isEmpty()) {
            checkSchedule(expression);
        }
        ObjectNode record = created(null);
        record.put("name", name).put("solutionArn", arn(region, "solution", name))
                .put("datasetGroupArn", group.get("datasetGroupArn").asText())
                .put("performHPO", in.path("performHPO").asBoolean(false)).put("performAutoML", autoML)
                .put("performAutoTraining", in.path("performAutoTraining").asBoolean(true))
                .put("performIncrementalUpdate", in.path("performIncrementalUpdate").asBoolean(false));
        if (recipeArn != null) {
            record.put("recipeArn", recipeArn);
        }
        if (text(in, "eventType") != null) {
            record.put("eventType", in.get("eventType").asText());
        }
        if (in.has("solutionConfig")) {
            record.set("solutionConfig", in.get("solutionConfig").deepCopy());
        }
        store.transition(record, PENDING, ACTIVE);
        PersonalizeStore.addTags(record, in.path("tags"));
        store.put(region, PersonalizeStore.SOLUTION, name, record);
        return out().put("solutionArn", record.get("solutionArn").asText());
    }

    private static void checkSchedule(String expression) {
        Matcher matcher = Pattern.compile("rate\\((\\d+) days?\\)")
                .matcher(expression);
        if (!matcher.matches() || Integer.parseInt(matcher.group(1)) < 1 || Integer.parseInt(matcher.group(1)) > 30) {
            throw PersonalizeStore.invalid("The schedulingExpression must be rate(N days), N from 1 to 30.");
        }
    }

    ObjectNode solution(String arn) {
        return store.require(arn, PersonalizeStore.SOLUTION, "solution");
    }

    private List<ObjectNode> versionsOf(String region, String solutionArn) {
        List<ObjectNode> versions = new ArrayList<>();
        for (ObjectNode version : store.all(region, PersonalizeStore.VERSION)) {
            if (solutionArn.equals(version.path("solutionArn").asText())) {
                versions.add(version);
            }
        }
        return versions;
    }

    public ObjectNode describeSolution(JsonNode in, String region) {
        ObjectNode record = solution(required(in, "solutionArn"));
        ObjectNode view = PersonalizeStore.view(record);
        List<ObjectNode> versions = versionsOf(PersonalizeStore.region(record), record.get("solutionArn").asText());
        if (!versions.isEmpty()) {
            ObjectNode latest = versions.get(versions.size() - 1);
            view.set("latestSolutionVersion", summaries(mapper, List.of(latest), "solutionVersionArn", "status",
                    "trainingMode", "trainingType", "creationDateTime", "lastUpdatedDateTime", "failureReason")
                    .get(0));
            if (latest.has("autoMLResult")) {
                view.set("autoMLResult", latest.get("autoMLResult"));
            }
        }
        ObjectNode out = out();
        out.set("solution", view);
        return out;
    }

    public ObjectNode listSolutions(JsonNode in, String region) {
        String group = text(in, "datasetGroupArn");
        List<ObjectNode> records = group == null ? store.all(region, PersonalizeStore.SOLUTION)
                : children(region, PersonalizeStore.SOLUTION, group);
        return list(in, records, "solutions", "name", "solutionArn", "status", "creationDateTime",
                "lastUpdatedDateTime", "recipeArn");
    }

    public ObjectNode updateSolution(JsonNode in, String region) {
        ObjectNode record = solution(required(in, "solutionArn"));
        if (!active(record)) {
            throw PersonalizeStore.inUse("The solution is " + record.path("status").asText() + ".");
        }
        JsonNode config = in.path("solutionUpdateConfig");
        String expression = config.at("/autoTrainingConfig/schedulingExpression").asText("");
        if (!expression.isEmpty()) {
            checkSchedule(expression);
        }
        double now = PersonalizeStore.now();
        if (in.has("performAutoTraining")) {
            record.put("performAutoTraining", in.get("performAutoTraining").asBoolean());
        }
        if (in.has("performIncrementalUpdate")) {
            record.put("performIncrementalUpdate", in.get("performIncrementalUpdate").asBoolean());
        }
        ObjectNode solutionConfig = record.has("solutionConfig") ? (ObjectNode) record.get("solutionConfig")
                : record.putObject("solutionConfig");
        config.properties().forEach(entry -> solutionConfig.set(entry.getKey(), entry.getValue().deepCopy()));
        ObjectNode update = record.putObject("latestSolutionUpdate");
        update.set("solutionUpdateConfig", config.deepCopy());
        update.put("status", ACTIVE).put("performAutoTraining", record.path("performAutoTraining").asBoolean())
                .put("performIncrementalUpdate", record.path("performIncrementalUpdate").asBoolean())
                .put("creationDateTime", now).put("lastUpdatedDateTime", now);
        record.put("lastUpdatedDateTime", now);
        store.put(PersonalizeStore.region(record), PersonalizeStore.SOLUTION, PersonalizeStore.id(record), record);
        return out().put("solutionArn", record.get("solutionArn").asText());
    }

    public ObjectNode deleteSolution(JsonNode in, String region) {
        ObjectNode record = solution(required(in, "solutionArn"));
        String solutionRegion = PersonalizeStore.region(record);
        List<ObjectNode> versions = versionsOf(solutionRegion, record.get("solutionArn").asText());
        Set<String> versionArns = new HashSet<>();
        versions.forEach(version -> versionArns.add(version.get("solutionVersionArn").asText()));
        for (ObjectNode campaign : store.all(solutionRegion, PersonalizeStore.CAMPAIGN)) {
            if (versionArns.contains(campaign.path("solutionVersionArn").asText())) {
                throw PersonalizeStore.inUse("The campaign " + campaign.get("campaignArn").asText()
                        + " uses the solution; delete it first.");
            }
        }
        for (ObjectNode version : versions) {
            if (version.path("status").asText().equals(IN_PROGRESS)) {
                throw PersonalizeStore.inUse("A solution version is still training.");
            }
        }
        for (ObjectNode version : versions) {
            store.delete(solutionRegion, PersonalizeStore.VERSION, PersonalizeStore.id(version));
            store.deleteData(store.account(), "model/" + PersonalizeStore.id(version));
        }
        store.delete(solutionRegion, PersonalizeStore.SOLUTION, PersonalizeStore.id(record));
        return out();
    }

    // ================================================================ solution versions

    public ObjectNode createSolutionVersion(JsonNode in, String region) {
        ObjectNode solution = solution(required(in, "solutionArn"));
        if (!active(solution)) {
            throw PersonalizeStore.inUse("The solution is " + solution.path("status").asText() + ", not ACTIVE.");
        }
        String solutionRegion = PersonalizeStore.region(solution);
        String mode = text(in, "trainingMode") == null ? "FULL" : in.get("trainingMode").asText();
        List<ObjectNode> versions = versionsOf(solutionRegion, solution.get("solutionArn").asText());
        for (ObjectNode version : versions) {
            if (version.path("status").asText().equals(PENDING) || version.path("status").asText()
                    .equals(IN_PROGRESS)) {
                throw PersonalizeStore.inUse("A version of the solution is already training.");
            }
        }
        if (!"FULL".equals(mode) && versions.stream().noneMatch(PersonalizeService::active)) {
            throw PersonalizeStore.invalid("An " + mode + " training needs an ACTIVE version to update.");
        }
        String id = solution.get("name").asText() + "/" + PersonalizeStore.random(8, HEX);
        String name = text(in, "name");
        if (name != null) {
            for (ObjectNode version : versions) {
                if (name.equals(version.path("name").asText())) {
                    throw PersonalizeStore.exists("The solution already has a version named " + name + ".");
                }
            }
        }
        ObjectNode record = created(PENDING);
        if (name != null) {
            record.put("name", name);
        }
        record.put("solutionVersionArn", arn(solutionRegion, "solution", id))
                .put("solutionArn", solution.get("solutionArn").asText())
                .put("datasetGroupArn", solution.get("datasetGroupArn").asText())
                .put("performHPO", solution.path("performHPO").asBoolean())
                .put("performAutoML", solution.path("performAutoML").asBoolean())
                .put("performIncrementalUpdate", solution.path("performIncrementalUpdate").asBoolean())
                .put("trainingMode", mode).put("trainingType", "MANUAL");
        if (solution.has("recipeArn")) {
            record.put("recipeArn", solution.get("recipeArn").asText());
        }
        if (solution.has("eventType")) {
            record.put("eventType", solution.get("eventType").asText());
        }
        if (solution.has("solutionConfig")) {
            record.set("solutionConfig", solution.get("solutionConfig").deepCopy());
        }
        PersonalizeStore.addTags(record, in.path("tags"));
        store.put(solutionRegion, PersonalizeStore.VERSION, id, record);
        String account = store.account();
        common.later(account, solutionRegion, () -> train(account, solutionRegion, id));
        return out().put("solutionVersionArn", record.get("solutionVersionArn").asText());
    }

    /** Trains a solution version from its dataset group's interactions. */
    private void train(String account, String region, String id) {
        ObjectNode version = store.get(region, PersonalizeStore.VERSION, id).orElse(null);
        if (version == null || !PENDING.equals(version.path("status").asText())) {
            return;
        }
        store.transition(version, IN_PROGRESS, null);
        store.put(region, PersonalizeStore.VERSION, id, version);
        long started = System.currentTimeMillis();
        try {
            List<String> candidates = new ArrayList<>();
            if (version.has("recipeArn")) {
                candidates.add(version.get("recipeArn").asText());
            } else {
                version.at("/solutionConfig/autoMLConfig/recipeList").forEach(arn -> candidates.add(arn.asText()));
                if (candidates.isEmpty()) {
                    String partition = AwsRegions.partitionFor(region);
                    candidates.add(PersonalizeModels.RECIPES.get("aws-user-personalization").arn(partition));
                    candidates.add(PersonalizeModels.RECIPES.get("aws-popularity-count").arn(partition));
                }
            }
            String metric = version.at("/solutionConfig/autoMLConfig/metricName").asText("precision_at_25");
            Double threshold = version.at("/solutionConfig/eventValueThreshold").isMissingNode() ? null
                    : version.at("/solutionConfig/eventValueThreshold").asDouble();
            ObjectNode best = null;
            ObjectNode bestMetrics = null;
            String bestRecipe = null;
            for (String recipeArn : candidates) {
                Recipe recipe = recipe(recipeArn);
                List<PersonalizeModels.Interaction> interactions = inference.trainingSet(region,
                        version.get("datasetGroupArn").asText(), recipe, version.path("eventType").asText(null),
                        threshold);
                ObjectNode metrics = PersonalizeModels.metrics(mapper, recipe.mode(), interactions);
                if (best == null || metrics.path(metric).asDouble() > bestMetrics.path(metric).asDouble()) {
                    best = PersonalizeModels.train(mapper, recipe.mode(), interactions);
                    best.put("recipeArn", recipeArn).put("trainedAt", PersonalizeStore.now());
                    bestMetrics = metrics;
                    bestRecipe = recipeArn;
                }
            }
            store.putData(account, "model/" + id, best);
            PersonalizeStore.meta(version).set("metrics", bestMetrics);
            if (version.path("performAutoML").asBoolean()) {
                version.putObject("autoMLResult").put("bestRecipeArn", bestRecipe);
            }
            if (version.path("performHPO").asBoolean()) {
                ObjectNode tuned = version.putObject("tunedHPOParams").putObject("algorithmHyperParameters");
                version.at("/solutionConfig/algorithmHyperParameters").properties()
                        .forEach(entry -> tuned.set(entry.getKey(), entry.getValue()));
            }
            version.put("trainingHours", Math.round((System.currentTimeMillis() - started) / 3_600_000.0 * 1e6) / 1e6);
            ObjectNode current = store.get(region, PersonalizeStore.VERSION, id).orElse(null);
            if (current != null && current.path("status").asText().startsWith("CREATE STOP")) {
                store.transition(version, "CREATE STOPPED", null);
            } else {
                store.transition(version, ACTIVE, null);
            }
        } catch (AwsException | IllegalStateException e) {
            store.transition(version, FAILED, null);
            version.put("failureReason", e.getMessage());
        }
        if (store.get(region, PersonalizeStore.VERSION, id).isPresent()) {
            store.put(region, PersonalizeStore.VERSION, id, version);
        }
    }

    ObjectNode version(String arn) {
        return store.require(arn, PersonalizeStore.VERSION, "solution version");
    }

    public ObjectNode describeSolutionVersion(JsonNode in, String region) {
        ObjectNode out = out();
        out.set("solutionVersion", PersonalizeStore.view(version(required(in, "solutionVersionArn"))));
        return out;
    }

    public ObjectNode listSolutionVersions(JsonNode in, String region) {
        String solutionArn = text(in, "solutionArn");
        List<ObjectNode> records;
        if (solutionArn == null) {
            records = store.all(region, PersonalizeStore.VERSION);
        } else {
            ObjectNode solution = solution(solutionArn);
            records = versionsOf(PersonalizeStore.region(solution), solutionArn);
        }
        return list(in, records, "solutionVersions", "solutionVersionArn", "status", "trainingMode",
                "trainingType", "creationDateTime", "lastUpdatedDateTime", "failureReason");
    }

    public ObjectNode stopSolutionVersionCreation(JsonNode in, String region) {
        ObjectNode version = version(required(in, "solutionVersionArn"));
        String status = version.path("status").asText();
        if (!status.equals(PENDING) && !status.equals(IN_PROGRESS)) {
            throw PersonalizeStore.inUse("The solution version is " + status + "; only training can be stopped.");
        }
        if (status.equals(PENDING)) {
            store.transition(version, "CREATE STOPPED", null);
        } else {
            store.transition(version, "CREATE STOPPING", null);
        }
        store.put(PersonalizeStore.region(version), PersonalizeStore.VERSION, PersonalizeStore.id(version), version);
        return out();
    }

    public ObjectNode getSolutionMetrics(JsonNode in, String region) {
        String arn = required(in, "solutionVersionArn");
        ObjectNode version = version(arn);
        if (!active(version)) {
            throw PersonalizeStore.inUse("The solution version is " + version.path("status").asText()
                    + "; metrics are ready once it's ACTIVE.");
        }
        ObjectNode out = out().put("solutionVersionArn", arn);
        out.set("metrics", PersonalizeStore.meta(version).path("metrics").deepCopy());
        return out;
    }

    // ================================================================ campaigns

    public ObjectNode createCampaign(JsonNode in, String region) {
        String name = required(in, "name");
        ObjectNode version = version(required(in, "solutionVersionArn"));
        if (!active(version)) {
            throw PersonalizeStore.invalid("The solution version is " + version.path("status").asText()
                    + ", not ACTIVE.");
        }
        ensureNew(region, PersonalizeStore.CAMPAIGN, name, "campaign");
        int tps = in.path("minProvisionedTPS").asInt(1);
        if (tps < 1) {
            throw PersonalizeStore.invalid("minProvisionedTPS must be at least 1.");
        }
        ObjectNode record = created(null);
        record.put("name", name).put("campaignArn", arn(region, "campaign", name))
                .put("solutionVersionArn", version.get("solutionVersionArn").asText()).put("minProvisionedTPS", tps);
        ObjectNode config = record.putObject("campaignConfig");
        if (in.has("campaignConfig")) {
            config.setAll((ObjectNode) in.get("campaignConfig").deepCopy());
        }
        PersonalizeStore.meta(record).put("datasetGroupArn", version.get("datasetGroupArn").asText());
        store.transition(record, PENDING, ACTIVE);
        PersonalizeStore.addTags(record, in.path("tags"));
        store.put(region, PersonalizeStore.CAMPAIGN, name, record);
        return out().put("campaignArn", record.get("campaignArn").asText());
    }

    ObjectNode campaign(String arn) {
        return store.require(arn, PersonalizeStore.CAMPAIGN, "campaign");
    }

    public ObjectNode describeCampaign(JsonNode in, String region) {
        ObjectNode out = out();
        out.set("campaign", PersonalizeStore.view(campaign(required(in, "campaignArn"))));
        return out;
    }

    public ObjectNode listCampaigns(JsonNode in, String region) {
        String solutionArn = text(in, "solutionArn");
        List<ObjectNode> records = store.all(region, PersonalizeStore.CAMPAIGN).stream()
                .filter(campaign -> solutionArn == null || campaign.path("solutionVersionArn").asText()
                        .startsWith(solutionArn + "/")).toList();
        return list(in, records, "campaigns", "name", "campaignArn", "status", "creationDateTime",
                "lastUpdatedDateTime", "failureReason");
    }

    public ObjectNode updateCampaign(JsonNode in, String region) {
        ObjectNode record = campaign(required(in, "campaignArn"));
        if (!active(record)) {
            throw PersonalizeStore.inUse("The campaign is " + record.path("status").asText() + ".");
        }
        if (text(in, "solutionVersionArn") != null) {
            ObjectNode version = version(in.get("solutionVersionArn").asText());
            if (!active(version)) {
                throw PersonalizeStore.invalid("The solution version is " + version.path("status").asText()
                        + ", not ACTIVE.");
            }
            String current = record.get("solutionVersionArn").asText();
            if (!current.substring(0, current.lastIndexOf('/')).equals(version.get("solutionArn").asText())) {
                throw PersonalizeStore.invalid("A campaign can only move to a version of its own solution.");
            }
            record.put("solutionVersionArn", version.get("solutionVersionArn").asText());
        }
        if (in.has("minProvisionedTPS")) {
            record.put("minProvisionedTPS", in.get("minProvisionedTPS").asInt());
        }
        if (in.has("campaignConfig")) {
            ((ObjectNode) record.get("campaignConfig")).setAll((ObjectNode) in.get("campaignConfig").deepCopy());
        }
        double now = PersonalizeStore.now();
        ObjectNode update = record.putObject("latestCampaignUpdate");
        update.put("solutionVersionArn", record.get("solutionVersionArn").asText())
                .put("minProvisionedTPS", record.get("minProvisionedTPS").asInt()).put("status", ACTIVE)
                .put("creationDateTime", now).put("lastUpdatedDateTime", now);
        update.set("campaignConfig", record.get("campaignConfig").deepCopy());
        store.transition(record, "UPDATE PENDING", ACTIVE);
        store.put(PersonalizeStore.region(record), PersonalizeStore.CAMPAIGN, PersonalizeStore.id(record), record);
        return out().put("campaignArn", record.get("campaignArn").asText());
    }

    public ObjectNode deleteCampaign(JsonNode in, String region) {
        ObjectNode record = campaign(required(in, "campaignArn"));
        store.delete(PersonalizeStore.region(record), PersonalizeStore.CAMPAIGN, PersonalizeStore.id(record));
        return out();
    }

    // ================================================================ recommenders

    public ObjectNode createRecommender(JsonNode in, String region) {
        String name = required(in, "name");
        ObjectNode group = group(required(in, "datasetGroupArn"));
        Recipe recipe = recipe(required(in, "recipeArn"));
        String domain = group.path("domain").asText("");
        if (domain.isEmpty()) {
            throw PersonalizeStore.invalid("Recommenders need a domain dataset group.");
        }
        if (!domain.equals(recipe.domain())) {
            throw PersonalizeStore.invalid("The recipe " + recipe.name() + " isn't a " + domain + " use case.");
        }
        if (!active(group)) {
            throw PersonalizeStore.inUse("The dataset group is " + group.path("status").asText() + ", not ACTIVE.");
        }
        ensureNew(region, PersonalizeStore.RECOMMENDER, name, "recommender");
        ObjectNode record = created(PENDING);
        record.put("name", name).put("recommenderArn", arn(region, "recommender", name))
                .put("datasetGroupArn", group.get("datasetGroupArn").asText()).put("recipeArn", in.get("recipeArn").asText());
        ObjectNode config = record.putObject("recommenderConfig");
        if (in.has("recommenderConfig")) {
            config.setAll((ObjectNode) in.get("recommenderConfig").deepCopy());
        }
        if (!config.has("minRecommendationRequestsPerSecond")) {
            config.put("minRecommendationRequestsPerSecond", 1);
        }
        PersonalizeStore.addTags(record, in.path("tags"));
        store.put(region, PersonalizeStore.RECOMMENDER, name, record);
        String account = store.account();
        common.later(account, region, () -> trainRecommender(account, region, name));
        return out().put("recommenderArn", record.get("recommenderArn").asText());
    }

    private void trainRecommender(String account, String region, String name) {
        ObjectNode record = store.get(region, PersonalizeStore.RECOMMENDER, name).orElse(null);
        if (record == null) {
            return;
        }
        try {
            Recipe recipe = recipe(record.get("recipeArn").asText());
            List<PersonalizeModels.Interaction> interactions = inference.trainingSet(region,
                    record.get("datasetGroupArn").asText(), recipe, recipe.eventType(), null);
            ObjectNode model = PersonalizeModels.train(mapper, recipe.mode(), interactions);
            model.put("recipeArn", record.get("recipeArn").asText()).put("trainedAt", PersonalizeStore.now());
            store.putData(account, "model/recommender/" + name, model);
            record.set("modelMetrics", PersonalizeModels.metrics(mapper, recipe.mode(), interactions));
            store.transition(record, ACTIVE, null);
        } catch (AwsException | IllegalStateException e) {
            store.transition(record, FAILED, null);
            record.put("failureReason", e.getMessage());
        }
        store.put(region, PersonalizeStore.RECOMMENDER, name, record);
    }

    ObjectNode recommender(String arn) {
        return store.require(arn, PersonalizeStore.RECOMMENDER, "recommender");
    }

    public ObjectNode describeRecommender(JsonNode in, String region) {
        ObjectNode out = out();
        out.set("recommender", PersonalizeStore.view(recommender(required(in, "recommenderArn"))));
        return out;
    }

    public ObjectNode listRecommenders(JsonNode in, String region) {
        String group = text(in, "datasetGroupArn");
        List<ObjectNode> records = group == null ? store.all(region, PersonalizeStore.RECOMMENDER)
                : children(region, PersonalizeStore.RECOMMENDER, group);
        return list(in, records, "recommenders", "name", "recommenderArn", "datasetGroupArn", "recipeArn",
                "recommenderConfig", "status", "creationDateTime", "lastUpdatedDateTime");
    }

    public ObjectNode updateRecommender(JsonNode in, String region) {
        ObjectNode record = recommender(required(in, "recommenderArn"));
        if (!active(record)) {
            throw PersonalizeStore.inUse("The recommender is " + record.path("status").asText() + ".");
        }
        ((ObjectNode) record.get("recommenderConfig")).setAll((ObjectNode) in.get("recommenderConfig").deepCopy());
        double now = PersonalizeStore.now();
        ObjectNode update = record.putObject("latestRecommenderUpdate");
        update.set("recommenderConfig", record.get("recommenderConfig").deepCopy());
        update.put("status", ACTIVE).put("creationDateTime", now).put("lastUpdatedDateTime", now);
        store.transition(record, "UPDATE PENDING", ACTIVE);
        store.put(PersonalizeStore.region(record), PersonalizeStore.RECOMMENDER, PersonalizeStore.id(record), record);
        return out().put("recommenderArn", record.get("recommenderArn").asText());
    }

    public ObjectNode startRecommender(JsonNode in, String region) {
        ObjectNode record = recommender(required(in, "recommenderArn"));
        if (!"INACTIVE".equals(record.path("status").asText())) {
            throw PersonalizeStore.inUse("The recommender is " + record.path("status").asText()
                    + "; only an INACTIVE recommender starts.");
        }
        store.transition(record, "START PENDING", ACTIVE);
        store.put(PersonalizeStore.region(record), PersonalizeStore.RECOMMENDER, PersonalizeStore.id(record), record);
        return out().put("recommenderArn", record.get("recommenderArn").asText());
    }

    public ObjectNode stopRecommender(JsonNode in, String region) {
        ObjectNode record = recommender(required(in, "recommenderArn"));
        if (!active(record)) {
            throw PersonalizeStore.inUse("The recommender is " + record.path("status").asText()
                    + "; only an ACTIVE recommender stops.");
        }
        store.transition(record, "STOP PENDING", "INACTIVE");
        store.put(PersonalizeStore.region(record), PersonalizeStore.RECOMMENDER, PersonalizeStore.id(record), record);
        return out().put("recommenderArn", record.get("recommenderArn").asText());
    }

    public ObjectNode deleteRecommender(JsonNode in, String region) {
        ObjectNode record = recommender(required(in, "recommenderArn"));
        store.delete(PersonalizeStore.region(record), PersonalizeStore.RECOMMENDER, PersonalizeStore.id(record));
        store.deleteData(store.account(), "model/recommender/" + PersonalizeStore.id(record));
        return out();
    }

    // ================================================================ filters

    public ObjectNode createFilter(JsonNode in, String region) {
        String name = required(in, "name");
        ObjectNode group = group(required(in, "datasetGroupArn"));
        String expression = required(in, "filterExpression");
        PersonalizeFilters.parse(expression);
        ensureNew(region, PersonalizeStore.FILTER, name, "filter");
        ObjectNode record = created(null);
        record.put("name", name).put("filterArn", arn(region, "filter", name))
                .put("datasetGroupArn", group.get("datasetGroupArn").asText()).put("filterExpression", expression);
        store.transition(record, PENDING, ACTIVE);
        PersonalizeStore.addTags(record, in.path("tags"));
        store.put(region, PersonalizeStore.FILTER, name, record);
        return out().put("filterArn", record.get("filterArn").asText());
    }

    public ObjectNode describeFilter(JsonNode in, String region) {
        ObjectNode out = out();
        out.set("filter", PersonalizeStore.view(store.require(required(in, "filterArn"), PersonalizeStore.FILTER,
                "filter")));
        return out;
    }

    public ObjectNode listFilters(JsonNode in, String region) {
        String group = text(in, "datasetGroupArn");
        List<ObjectNode> records = group == null ? store.all(region, PersonalizeStore.FILTER)
                : children(region, PersonalizeStore.FILTER, group);
        return list(in, records, "Filters", "name", "filterArn", "creationDateTime", "lastUpdatedDateTime",
                "datasetGroupArn", "failureReason", "status");
    }

    public ObjectNode deleteFilter(JsonNode in, String region) {
        ObjectNode record = store.require(required(in, "filterArn"), PersonalizeStore.FILTER, "filter");
        store.delete(PersonalizeStore.region(record), PersonalizeStore.FILTER, PersonalizeStore.id(record));
        return out();
    }

    // ================================================================ batch jobs

    public ObjectNode createBatchInferenceJob(JsonNode in, String region) {
        String name = required(in, "jobName");
        ObjectNode version = version(required(in, "solutionVersionArn"));
        String role = required(in, "roleArn");
        String input = required(in.at("/jobInput/s3DataSource"), "path");
        String output = required(in.at("/jobOutput/s3DataDestination"), "path");
        PersonalizeCommon.s3Uri(input);
        PersonalizeCommon.s3Uri(output);
        if (text(in, "filterArn") != null) {
            store.require(in.get("filterArn").asText(), PersonalizeStore.FILTER, "filter");
        }
        common.role(role, region);
        if (!active(version)) {
            throw PersonalizeStore.invalid("The solution version is " + version.path("status").asText()
                    + ", not ACTIVE.");
        }
        Recipe recipe = recipe(store.data(store.account(), "model/" + PersonalizeStore.id(version))
                .map(model -> model.path("recipeArn").asText()).orElse(version.path("recipeArn").asText()));
        String mode = text(in, "batchInferenceJobMode") == null ? "BATCH_INFERENCE"
                : in.get("batchInferenceJobMode").asText();
        if ("USER_SEGMENTATION".equals(recipe.type())) {
            throw PersonalizeStore.invalid("A USER_SEGMENTATION solution version runs batch segment jobs.");
        }
        if ("THEME_GENERATION".equals(mode) && !"RELATED_ITEMS".equals(recipe.type())) {
            throw PersonalizeStore.invalid("Theme generation needs a RELATED_ITEMS solution version.");
        }
        if ("THEME_GENERATION".equals(mode) && !in.has("themeGenerationConfig")) {
            throw PersonalizeStore.invalid("Theme generation needs themeGenerationConfig.");
        }
        ensureNew(region, PersonalizeStore.BATCH, name, "batch inference job");
        ObjectNode record = created(PENDING);
        record.put("jobName", name).put("batchInferenceJobArn", arn(region, "batch-inference-job", name))
                .put("solutionVersionArn", version.get("solutionVersionArn").asText())
                .put("numResults", in.path("numResults").asInt(25)).put("roleArn", role)
                .put("batchInferenceJobMode", mode);
        for (String member : List.of("filterArn")) {
            if (text(in, member) != null) {
                record.put(member, in.get(member).asText());
            }
        }
        for (String member : List.of("jobInput", "jobOutput", "batchInferenceJobConfig", "themeGenerationConfig")) {
            if (in.has(member)) {
                record.set(member, in.get(member).deepCopy());
            }
        }
        PersonalizeStore.addTags(record, in.path("tags"));
        store.put(region, PersonalizeStore.BATCH, name, record);
        String account = store.account();
        common.later(account, region, () -> runBatch(account, region, PersonalizeStore.BATCH, name));
        return out().put("batchInferenceJobArn", record.get("batchInferenceJobArn").asText());
    }

    public ObjectNode createBatchSegmentJob(JsonNode in, String region) {
        String name = required(in, "jobName");
        ObjectNode version = version(required(in, "solutionVersionArn"));
        String role = required(in, "roleArn");
        String input = required(in.at("/jobInput/s3DataSource"), "path");
        String output = required(in.at("/jobOutput/s3DataDestination"), "path");
        PersonalizeCommon.s3Uri(input);
        PersonalizeCommon.s3Uri(output);
        if (text(in, "filterArn") != null) {
            store.require(in.get("filterArn").asText(), PersonalizeStore.FILTER, "filter");
        }
        common.role(role, region);
        if (!active(version)) {
            throw PersonalizeStore.invalid("The solution version is " + version.path("status").asText()
                    + ", not ACTIVE.");
        }
        if (!"USER_SEGMENTATION".equals(recipe(version.path("recipeArn").asText()).type())) {
            throw PersonalizeStore.invalid("Batch segment jobs need a USER_SEGMENTATION solution version.");
        }
        ensureNew(region, PersonalizeStore.SEGMENT, name, "batch segment job");
        ObjectNode record = created(PENDING);
        record.put("jobName", name).put("batchSegmentJobArn", arn(region, "batch-segment-job", name))
                .put("solutionVersionArn", version.get("solutionVersionArn").asText())
                .put("numResults", in.path("numResults").asInt(25)).put("roleArn", role);
        if (text(in, "filterArn") != null) {
            record.put("filterArn", in.get("filterArn").asText());
        }
        record.set("jobInput", in.get("jobInput").deepCopy());
        record.set("jobOutput", in.get("jobOutput").deepCopy());
        PersonalizeStore.addTags(record, in.path("tags"));
        store.put(region, PersonalizeStore.SEGMENT, name, record);
        String account = store.account();
        common.later(account, region, () -> runBatch(account, region, PersonalizeStore.SEGMENT, name));
        return out().put("batchSegmentJobArn", record.get("batchSegmentJobArn").asText());
    }

    /** Runs a batch job: one output line per JSON line of every input file. */
    private void runBatch(String account, String region, String kind, String name) {
        ObjectNode job = store.get(region, kind, name).orElse(null);
        if (job == null) {
            return;
        }
        store.transition(job, IN_PROGRESS, null);
        store.put(region, kind, name, job);
        try {
            String input = job.at("/jobInput/s3DataSource/path").asText();
            String output = job.at("/jobOutput/s3DataDestination/path").asText();
            String prefix = output.endsWith("/") ? output : output + "/";
            for (String uri : common.objects(input)) {
                StringBuilder lines = new StringBuilder();
                for (String line : new String(common.read(uri), StandardCharsets.UTF_8).split("\n")) {
                    if (line.isBlank()) {
                        continue;
                    }
                    ObjectNode result = mapper.createObjectNode();
                    try {
                        JsonNode request = mapper.readTree(line);
                        result.set("input", request);
                        result.set("output", PersonalizeStore.SEGMENT.equals(kind)
                                ? inference.segmentLine(job, request) : inference.batchLine(job, request));
                        result.putNull("error");
                    } catch (AwsException e) {
                        result.put("input", line);
                        result.put("error", e.getMessage());
                    } catch (Exception e) {
                        result.put("input", line);
                        result.put("error", "The line isn't valid JSON.");
                    }
                    lines.append(mapper.writeValueAsString(result)).append('\n');
                }
                common.write(prefix + uri.substring(uri.lastIndexOf('/') + 1) + ".out",
                        lines.toString().getBytes(StandardCharsets.UTF_8), "application/json");
            }
            store.transition(job, ACTIVE, null);
        } catch (AwsException | IllegalStateException e) {
            store.transition(job, FAILED, null);
            job.put("failureReason", e.getMessage());
        } catch (Exception e) {
            store.transition(job, FAILED, null);
            job.put("failureReason", String.valueOf(e.getMessage()));
        }
        store.put(region, kind, name, job);
    }

    public ObjectNode describeBatchInferenceJob(JsonNode in, String region) {
        ObjectNode out = out();
        out.set("batchInferenceJob", PersonalizeStore.view(store.require(required(in, "batchInferenceJobArn"),
                PersonalizeStore.BATCH, "batch inference job")));
        return out;
    }

    public ObjectNode listBatchInferenceJobs(JsonNode in, String region) {
        String version = text(in, "solutionVersionArn");
        List<ObjectNode> records = store.all(region, PersonalizeStore.BATCH).stream()
                .filter(job -> version == null || version.equals(job.path("solutionVersionArn").asText())).toList();
        return list(in, records, "batchInferenceJobs", "batchInferenceJobArn", "jobName", "status",
                "creationDateTime", "lastUpdatedDateTime", "failureReason", "solutionVersionArn",
                "batchInferenceJobMode");
    }

    public ObjectNode describeBatchSegmentJob(JsonNode in, String region) {
        ObjectNode out = out();
        out.set("batchSegmentJob", PersonalizeStore.view(store.require(required(in, "batchSegmentJobArn"),
                PersonalizeStore.SEGMENT, "batch segment job")));
        return out;
    }

    public ObjectNode listBatchSegmentJobs(JsonNode in, String region) {
        String version = text(in, "solutionVersionArn");
        List<ObjectNode> records = store.all(region, PersonalizeStore.SEGMENT).stream()
                .filter(job -> version == null || version.equals(job.path("solutionVersionArn").asText())).toList();
        return list(in, records, "batchSegmentJobs", "batchSegmentJobArn", "jobName", "status", "creationDateTime",
                "lastUpdatedDateTime", "failureReason", "solutionVersionArn");
    }

    // ================================================================ metric attributions

    private static void checkMetrics(JsonNode metrics, Set<String> names) {
        for (JsonNode metric : metrics) {
            String name = metric.path("metricName").asText("");
            String expression = metric.path("expression").asText("");
            if (!expression.matches("(?i)SAMPLECOUNT\\(\\)|SUM\\(Items\\.[A-Za-z_][A-Za-z0-9_]*\\)")) {
                throw PersonalizeStore.invalid("The metric expression " + expression
                        + " must be SAMPLECOUNT() or SUM(Items.<FIELD>).");
            }
            if (!names.add(name)) {
                throw PersonalizeStore.exists("The metric " + name + " is already defined.");
            }
        }
    }

    public ObjectNode createMetricAttribution(JsonNode in, String region) {
        String name = required(in, "name");
        ObjectNode group = group(required(in, "datasetGroupArn"));
        String groupArn = group.get("datasetGroupArn").asText();
        if (!children(region, PersonalizeStore.ATTRIBUTION, groupArn).isEmpty()) {
            throw PersonalizeStore.exists("The dataset group already has a metric attribution.");
        }
        checkMetrics(in.path("metrics"), new HashSet<>());
        common.role(required(in.path("metricsOutputConfig"), "roleArn"), region);
        ensureNew(region, PersonalizeStore.ATTRIBUTION, name, "metric attribution");
        ObjectNode record = created(null);
        record.put("name", name).put("metricAttributionArn", arn(region, "metric-attribution", name))
                .put("datasetGroupArn", groupArn);
        record.set("metricsOutputConfig", in.get("metricsOutputConfig").deepCopy());
        PersonalizeStore.meta(record).set("metrics", in.get("metrics").deepCopy());
        store.transition(record, PENDING, ACTIVE);
        store.put(region, PersonalizeStore.ATTRIBUTION, name, record);
        return out().put("metricAttributionArn", record.get("metricAttributionArn").asText());
    }

    private ObjectNode attribution(String arn) {
        return store.require(arn, PersonalizeStore.ATTRIBUTION, "metric attribution");
    }

    public ObjectNode describeMetricAttribution(JsonNode in, String region) {
        ObjectNode out = out();
        out.set("metricAttribution", PersonalizeStore.view(attribution(required(in, "metricAttributionArn"))));
        return out;
    }

    public ObjectNode listMetricAttributions(JsonNode in, String region) {
        String group = text(in, "datasetGroupArn");
        List<ObjectNode> records = group == null ? store.all(region, PersonalizeStore.ATTRIBUTION)
                : children(region, PersonalizeStore.ATTRIBUTION, group);
        return list(in, records, "metricAttributions", "name", "metricAttributionArn", "status",
                "creationDateTime", "lastUpdatedDateTime", "failureReason");
    }

    public ObjectNode listMetricAttributionMetrics(JsonNode in, String region) {
        String arn = text(in, "metricAttributionArn");
        List<JsonNode> metrics = new ArrayList<>();
        if (arn != null) {
            PersonalizeStore.meta(attribution(arn)).path("metrics").forEach(metrics::add);
        }
        ObjectNode out = out();
        PersonalizeStore.page(metrics, in, out, "metrics", 100);
        return out;
    }

    public ObjectNode updateMetricAttribution(JsonNode in, String region) {
        ObjectNode record = attribution(required(in, "metricAttributionArn"));
        if (!active(record)) {
            throw PersonalizeStore.inUse("The metric attribution is " + record.path("status").asText() + ".");
        }
        ArrayNode metrics = (ArrayNode) PersonalizeStore.meta(record).path("metrics");
        Set<String> removed = new HashSet<>();
        in.path("removeMetrics").forEach(metric -> removed.add(metric.asText()));
        ArrayNode kept = mapper.createArrayNode();
        Set<String> names = new HashSet<>();
        for (JsonNode metric : metrics) {
            if (!removed.contains(metric.path("metricName").asText())) {
                kept.add(metric);
                names.add(metric.path("metricName").asText());
            }
        }
        checkMetrics(in.path("addMetrics"), names);
        in.path("addMetrics").forEach(kept::add);
        PersonalizeStore.meta(record).set("metrics", kept);
        if (in.has("metricsOutputConfig")) {
            common.role(required(in.path("metricsOutputConfig"), "roleArn"), region);
            record.set("metricsOutputConfig", in.get("metricsOutputConfig").deepCopy());
        }
        store.transition(record, "UPDATE PENDING", ACTIVE);
        store.put(PersonalizeStore.region(record), PersonalizeStore.ATTRIBUTION, PersonalizeStore.id(record), record);
        return out().put("metricAttributionArn", record.get("metricAttributionArn").asText());
    }

    public ObjectNode deleteMetricAttribution(JsonNode in, String region) {
        ObjectNode record = attribution(required(in, "metricAttributionArn"));
        store.delete(PersonalizeStore.region(record), PersonalizeStore.ATTRIBUTION, PersonalizeStore.id(record));
        return out();
    }

    /** Writes a dataset group's attribution metrics over imported interactions to its S3 destination. */
    private void publishAttribution(String region, String groupArn, List<ObjectNode> rows) {
        for (ObjectNode attribution : children(region, PersonalizeStore.ATTRIBUTION, groupArn)) {
            String path = attribution.at("/metricsOutputConfig/s3DataDestination/path").asText("");
            if (path.isEmpty()) {
                continue;
            }
            ObjectNode report = inference.attributionReport(region, groupArn,
                    (ArrayNode) PersonalizeStore.meta(attribution).path("metrics"), rows);
            common.write((path.endsWith("/") ? path : path + "/") + attribution.get("name").asText() + "/"
                    + (long) PersonalizeStore.now() + ".json", report.toString().getBytes(StandardCharsets.UTF_8),
                    "application/json");
        }
    }

    // ================================================================ tags

    private ObjectNode tagged(String arn) {
        return store.find(arn).orElseThrow(() -> PersonalizeStore.notFound("The resource " + arn
                + " doesn't exist."));
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        String arn = required(in, "resourceArn");
        ObjectNode record = tagged(arn);
        PersonalizeStore.addTags(record, in.path("tags"));
        store.put(PersonalizeStore.region(record), PersonalizeStore.kindOf(arn), PersonalizeStore.id(record), record);
        return out();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        String arn = required(in, "resourceArn");
        ObjectNode record = tagged(arn);
        in.path("tagKeys").forEach(key -> PersonalizeStore.tags(record).remove(key.asText()));
        store.put(PersonalizeStore.region(record), PersonalizeStore.kindOf(arn), PersonalizeStore.id(record), record);
        return out();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        ObjectNode out = out();
        out.set("tags", PersonalizeStore.tagList(tagged(required(in, "resourceArn")), mapper));
        return out;
    }
}
