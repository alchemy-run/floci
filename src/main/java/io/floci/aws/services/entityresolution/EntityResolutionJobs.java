package io.floci.aws.services.entityresolution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.glue.model.Table;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.entityresolution.EntityResolutionStore.ID_MAPPING_JOB;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.MATCHING_JOB;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.RECORDS;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.conflict;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.invalid;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.meta;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.notFound;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.now;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.optional;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.text;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.view;

/**
 * Entity Resolution's jobs and real-time matching. A matching job reads its workflow's input sources, matches their
 * records by the workflow's resolution technique and writes each record with its match ID to the workflow's
 * outputs, under {@code <outputS3Path>/<workflowName>/<jobId>/success/} (records it cannot process go under
 * {@code error/}); the matches it finds become the workflow's record index. An ID mapping job maps each record of
 * its SOURCE to the TARGET record its rules match. {@code GenerateMatchId} matches records against each other and
 * the index, {@code GetMatchId} looks a record up in it, and {@code BatchDeleteUniqueId} removes records from it.
 *
 * <p>A job is QUEUED, then RUNNING, then SUCCEEDED or FAILED, a transition apart.
 */
@ApplicationScoped
public class EntityResolutionJobs {

    private static final Logger LOG = Logger.getLogger(EntityResolutionJobs.class);
    private static final String HEADER_SOURCE = "InputSourceARN";

    private final EntityResolutionStore store;
    private final EntityResolutionService service;
    private final EntityResolutionData data;
    private final long transitionMillis;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("entityresolution-jobs").factory());

    @Inject
    public EntityResolutionJobs(EntityResolutionStore store, EntityResolutionService service,
                                EntityResolutionData data, EmulatorConfig config) {
        this.store = store;
        this.service = service;
        this.data = data;
        this.transitionMillis = config.services().entityresolution().transitionMillis();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    // ---------------------------------------------------------------- matching jobs

    public synchronized ObjectNode startMatchingJob(JsonNode input, String region) {
        String name = text(input, "workflowName");
        service.matchingWorkflow(region, name);
        if (running(region, MATCHING_JOB, name)) {
            throw conflict("Matching workflow " + name + " already has a job running.");
        }
        ObjectNode job = newJob(name);
        store.put(region, MATCHING_JOB, name + "/" + job.path("jobId").asText(), job);
        schedule(region, MATCHING_JOB, name, job.path("jobId").asText());
        ObjectNode response = store.mapper().createObjectNode();
        response.put("jobId", job.path("jobId").asText());
        return response;
    }

    public synchronized ObjectNode getMatchingJob(JsonNode input, String region) {
        String name = text(input, "workflowName");
        service.matchingWorkflow(region, name);
        return view(job(region, MATCHING_JOB, name, text(input, "jobId")));
    }

    public synchronized ObjectNode listMatchingJobs(JsonNode input, String region) {
        String name = text(input, "workflowName");
        service.matchingWorkflow(region, name);
        return jobList(region, MATCHING_JOB, name, input);
    }

    // ---------------------------------------------------------------- ID mapping jobs

    public synchronized ObjectNode startIdMappingJob(JsonNode input, String region) {
        String name = text(input, "workflowName");
        ObjectNode workflow = service.idMappingWorkflow(region, name);
        if (running(region, ID_MAPPING_JOB, name)) {
            throw conflict("ID mapping workflow " + name + " already has a job running.");
        }
        JsonNode outputs = input.has("outputSourceConfig") ? input.get("outputSourceConfig")
                : workflow.path("outputSourceConfig");
        if (!outputs.isArray() || outputs.isEmpty()) {
            throw invalid("The job needs an outputSourceConfig: the workflow has none.");
        }
        ObjectNode job = newJob(name);
        job.set("outputSourceConfig", outputs.deepCopy());
        String jobType = input.path("jobType").asText("BATCH");
        meta(job).put("jobType", jobType);
        store.put(region, ID_MAPPING_JOB, name + "/" + job.path("jobId").asText(), job);
        schedule(region, ID_MAPPING_JOB, name, job.path("jobId").asText());
        ObjectNode response = store.mapper().createObjectNode();
        response.put("jobId", job.path("jobId").asText());
        response.set("outputSourceConfig", outputs.deepCopy());
        response.put("jobType", jobType);
        return response;
    }

    public synchronized ObjectNode getIdMappingJob(JsonNode input, String region) {
        String name = text(input, "workflowName");
        service.idMappingWorkflow(region, name);
        return view(job(region, ID_MAPPING_JOB, name, text(input, "jobId")));
    }

    public synchronized ObjectNode listIdMappingJobs(JsonNode input, String region) {
        String name = text(input, "workflowName");
        service.idMappingWorkflow(region, name);
        return jobList(region, ID_MAPPING_JOB, name, input);
    }

    // ---------------------------------------------------------------- job lifecycle

    /** Whether a workflow has a job QUEUED or RUNNING. */
    synchronized boolean running(String region, String kind, String workflowName) {
        for (ObjectNode job : store.list(region, kind, workflowName + "/", record -> true)) {
            if (Set.of("QUEUED", "RUNNING").contains(job.path("status").asText())) {
                return true;
            }
        }
        return false;
    }

    /** Forgets a deleted workflow's jobs, and a matching workflow's record index. */
    synchronized void forget(String region, String kind, String workflowName) {
        for (ObjectNode job : store.list(region, kind, workflowName + "/", record -> true)) {
            store.delete(region, kind, workflowName + "/" + job.path("jobId").asText());
        }
        store.delete(region, RECORDS, workflowName);
    }

    private ObjectNode newJob(String workflowName) {
        ObjectNode job = store.record();
        job.put("jobId", UUID.randomUUID().toString().replace("-", "")).put("status", "QUEUED")
                .put("startTime", now());
        meta(job).put("workflowName", workflowName);
        return job;
    }

    private ObjectNode job(String region, String kind, String workflowName, String jobId) {
        return store.get(region, kind, workflowName + "/" + jobId).orElseThrow(() -> notFound("Job " + jobId
                + " not found."));
    }

    private ObjectNode jobList(String region, String kind, String workflowName, JsonNode input) {
        List<ObjectNode> jobs = new ArrayList<>(store.list(region, kind, workflowName + "/", record -> true));
        jobs.sort(Comparator.comparingDouble((ObjectNode job) -> job.path("startTime").asDouble()).reversed());
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode job : jobs) {
            ObjectNode item = store.mapper().createObjectNode();
            item.put("jobId", job.path("jobId").asText()).put("status", job.path("status").asText())
                    .put("startTime", job.path("startTime").asDouble());
            if (job.has("endTime")) {
                item.put("endTime", job.path("endTime").asDouble());
            }
            items.add(item);
        }
        return store.page(items, input, "jobs");
    }

    /** Moves a queued job to RUNNING, then runs it, a transition apart. */
    private void schedule(String region, String kind, String workflowName, String jobId) {
        String account = store.account();
        Runnable run = () -> RequestScopes.runAs(account, region, () -> {
            synchronized (this) {
                store.get(region, kind, workflowName + "/" + jobId).ifPresent(job -> {
                    job.put("status", "RUNNING");
                    store.put(region, kind, workflowName + "/" + jobId, job);
                });
            }
            Runnable finish = () -> RequestScopes.runAs(account, region, () -> execute(region, kind, workflowName,
                    jobId));
            scheduler.schedule(finish, transitionMillis, TimeUnit.MILLISECONDS);
        });
        scheduler.schedule(run, transitionMillis, TimeUnit.MILLISECONDS);
    }

    private synchronized void execute(String region, String kind, String workflowName, String jobId) {
        Optional<ObjectNode> found = store.get(region, kind, workflowName + "/" + jobId);
        if (found.isEmpty()) {
            return;
        }
        ObjectNode job = found.get();
        try {
            if (MATCHING_JOB.equals(kind)) {
                runMatching(region, workflowName, job);
            } else {
                runIdMapping(region, workflowName, job);
            }
            job.put("status", "SUCCEEDED");
        } catch (RuntimeException e) {
            LOG.debugv(e, "Entity Resolution job {0} of {1} failed", jobId, workflowName);
            job.put("status", "FAILED");
            job.putObject("errorDetails").put("errorMessage", e.getMessage() == null ? e.toString()
                    : e.getMessage());
        }
        job.put("endTime", now());
        store.put(region, kind, workflowName + "/" + jobId, job);
    }

    // ---------------------------------------------------------------- matching

    /** The records of a workflow's input sources, each source's under its own schema. */
    private record Loaded(List<EntityResolutionMatcher.Rec> records, List<EntityResolutionMatcher.Field> fields,
                          List<String[]> rejected, int input) {
    }

    private Loaded load(String region, JsonNode sources) {
        List<EntityResolutionMatcher.Rec> records = new ArrayList<>();
        Map<String, EntityResolutionMatcher.Field> fields = new LinkedHashMap<>();
        List<String[]> rejected = new ArrayList<>();
        int input = 0;
        for (JsonNode source : sources) {
            String arn = source.path("inputSourceARN").asText();
            ObjectNode schema = service.schema(region, source.path("schemaName").asText());
            List<EntityResolutionMatcher.Field> schemaFields = EntityResolutionMatcher.fields(
                    schema.path("mappedInputFields"));
            schemaFields.forEach(field -> fields.putIfAbsent(field.name(), field));
            String idField = EntityResolutionMatcher.uniqueIdField(schemaFields);
            Table table = data.table(arn);
            Set<String> seen = new LinkedHashSet<>();
            for (Map<String, String> row : data.read(table)) {
                input++;
                String id = idField == null ? null : row.get(idField);
                if (id == null || id.isBlank()) {
                    rejected.add(new String[] {arn, "", "The record has no " + idField + "."});
                } else if (!seen.add(id)) {
                    rejected.add(new String[] {arn, id, "The unique ID " + id + " repeats."});
                } else {
                    records.add(new EntityResolutionMatcher.Rec(arn, id, row));
                }
            }
        }
        return new Loaded(records, new ArrayList<>(fields.values()), rejected, input);
    }

    private void runMatching(String region, String workflowName, ObjectNode job) {
        ObjectNode workflow = service.matchingWorkflow(region, workflowName);
        Loaded loaded = load(region, workflow.path("inputSourceConfig"));
        boolean normalize = workflow.path("inputSourceConfig").path(0).path("applyNormalization").asBoolean(true);
        List<EntityResolutionMatcher.Group> groups = EntityResolutionMatcher.match(loaded.records(),
                loaded.fields(), workflow.path("resolutionTechniques"), normalize);
        ObjectNode index = index(region, workflowName);
        ObjectNode fresh = store.mapper().createObjectNode();
        Map<EntityResolutionMatcher.Rec, String> matchIds = new LinkedHashMap<>();
        Map<EntityResolutionMatcher.Rec, EntityResolutionMatcher.Group> groupOf = new LinkedHashMap<>();
        for (EntityResolutionMatcher.Group group : groups) {
            String matchId = existingMatchId(index, group.records());
            for (EntityResolutionMatcher.Rec record : group.records()) {
                matchIds.put(record, matchId);
                groupOf.put(record, group);
                fresh.set(indexKey(record.sourceArn(), record.id()), indexEntry(record, matchId, group.rule()));
            }
        }
        String jobId = job.path("jobId").asText();
        ArrayNode jobOutputs = job.putArray("outputSourceConfig");
        for (JsonNode output : workflow.path("outputSourceConfig")) {
            String path = output.path("outputS3Path").asText(null);
            if (path == null) {
                continue;
            }
            List<String> attributes = new ArrayList<>();
            Set<String> hashed = new LinkedHashSet<>();
            for (JsonNode attribute : output.path("output")) {
                attributes.add(attribute.path("name").asText());
                if (attribute.path("hashed").asBoolean(false)) {
                    hashed.add(attribute.path("name").asText());
                }
            }
            boolean ml = "ML_MATCHING".equals(workflow.path("resolutionTechniques").path("resolutionType").asText());
            List<List<String>> rows = new ArrayList<>();
            List<String> header = new ArrayList<>(List.of(HEADER_SOURCE, "RecordId", "MatchID", "MatchRule"));
            if (ml) {
                header.add("ConfidenceLevel");
            }
            header.addAll(attributes);
            rows.add(header);
            for (Map.Entry<EntityResolutionMatcher.Rec, String> entry : matchIds.entrySet()) {
                EntityResolutionMatcher.Rec record = entry.getKey();
                EntityResolutionMatcher.Group group = groupOf.get(record);
                List<String> row = new ArrayList<>(List.of(record.sourceArn(), record.id(), entry.getValue(),
                        group.rule() == null ? "" : group.rule()));
                if (ml) {
                    row.add(group.confidence() == null ? "" : String.format("%.4f", group.confidence()));
                }
                for (String attribute : attributes) {
                    String value = record.attributes().get(attribute);
                    row.add(hashed.contains(attribute) ? sha256(value) : value);
                }
                rows.add(row);
            }
            String folder = workflowName + "/" + jobId + "/";
            data.writeCsv(path, folder + "success/run-0-part-00000.csv", rows);
            if (!loaded.rejected().isEmpty()) {
                List<List<String>> errors = new ArrayList<>();
                errors.add(List.of(HEADER_SOURCE, "RecordId", "ErrorMessage"));
                loaded.rejected().forEach(rejection -> errors.add(List.of(rejection)));
                data.writeCsv(path, folder + "error/run-0-part-00000.csv", errors);
            }
            ObjectNode jobOutput = jobOutputs.addObject();
            jobOutput.put("roleArn", workflow.path("roleArn").asText()).put("outputS3Path", path);
            if (output.hasNonNull("KMSArn")) {
                jobOutput.put("KMSArn", output.get("KMSArn").asText());
            }
        }
        store.put(region, RECORDS, workflowName, fresh);
        Set<String> distinct = new LinkedHashSet<>(matchIds.values());
        job.putObject("metrics").put("inputRecords", loaded.input())
                .put("totalRecordsProcessed", loaded.records().size())
                .put("recordsNotProcessed", loaded.rejected().size()).put("matchIDs", distinct.size());
    }

    // ---------------------------------------------------------------- ID mapping

    private record Side(List<EntityResolutionMatcher.Rec> records, List<EntityResolutionMatcher.Field> fields,
                        Map<EntityResolutionMatcher.Rec, String> matchIds, JsonNode rules) {
    }

    /** An ID mapping input: an ID namespace's sources, or a Glue table with its schema. */
    private Side side(String region, JsonNode source) {
        String arn = source.path("inputSourceARN").asText();
        List<EntityResolutionMatcher.Rec> records = new ArrayList<>();
        Map<String, EntityResolutionMatcher.Field> fields = new LinkedHashMap<>();
        Map<EntityResolutionMatcher.Rec, String> matchIds = new LinkedHashMap<>();
        JsonNode rules = store.mapper().createArrayNode();
        List<JsonNode> inputs = new ArrayList<>();
        if (arn.contains(":idnamespace/")) {
            ObjectNode namespace = service.idNamespace(region, arn.substring(arn.lastIndexOf('/') + 1));
            namespace.path("inputSourceConfig").forEach(inputs::add);
            for (JsonNode properties : namespace.path("idMappingWorkflowProperties")) {
                if (properties.path("ruleBasedProperties").has("rules")) {
                    rules = properties.path("ruleBasedProperties").path("rules");
                }
            }
        } else {
            inputs.add(source);
        }
        for (JsonNode input : inputs) {
            String inputArn = input.path("inputSourceARN").asText();
            if (inputArn.contains(":matchingworkflow/")) {
                String workflowName = inputArn.substring(inputArn.lastIndexOf('/') + 1);
                ObjectNode workflow = service.matchingWorkflow(region, workflowName);
                for (JsonNode workflowSource : workflow.path("inputSourceConfig")) {
                    EntityResolutionMatcher.fields(service.schema(region, workflowSource.path("schemaName").asText())
                            .path("mappedInputFields")).forEach(field -> fields.putIfAbsent(field.name(), field));
                }
                index(region, workflowName).fields().forEachRemaining(entry -> {
                    EntityResolutionMatcher.Rec record = fromIndex(entry.getValue());
                    records.add(record);
                    matchIds.put(record, entry.getValue().path("matchId").asText());
                });
            } else {
                Loaded loaded = load(region, store.mapper().createArrayNode().add(input));
                records.addAll(loaded.records());
                loaded.fields().forEach(field -> fields.putIfAbsent(field.name(), field));
            }
        }
        return new Side(records, new ArrayList<>(fields.values()), matchIds, rules);
    }

    private void runIdMapping(String region, String workflowName, ObjectNode job) {
        ObjectNode workflow = service.idMappingWorkflow(region, workflowName);
        Side source = null;
        Side target = null;
        for (JsonNode input : workflow.path("inputSourceConfig")) {
            String arn = input.path("inputSourceARN").asText();
            String type = arn.contains(":idnamespace/")
                    ? service.idNamespace(region, arn.substring(arn.lastIndexOf('/') + 1)).path("type").asText()
                    : input.path("type").asText("SOURCE");
            if ("TARGET".equals(type)) {
                target = side(region, input);
            } else {
                source = side(region, input);
            }
        }
        if (source == null || target == null) {
            throw new IllegalStateException("The workflow needs a SOURCE and a TARGET input.");
        }
        JsonNode properties = workflow.path("idMappingTechniques").path("ruleBasedProperties");
        JsonNode rules = properties.has("rules") ? properties.path("rules")
                : "SOURCE".equals(properties.path("ruleDefinitionType").asText()) ? source.rules() : target.rules();
        if (rules.isEmpty()) {
            throw new IllegalStateException("No rules to map with: neither the workflow nor its "
                    + properties.path("ruleDefinitionType").asText() + " ID namespace defines any.");
        }
        boolean manyToMany = "MANY_TO_MANY".equals(properties.path("attributeMatchingModel").asText());
        boolean oneTarget = !"MANY_SOURCE_TO_ONE_TARGET".equals(properties.path("recordMatchingModel").asText());
        List<EntityResolutionMatcher.Field> fields = new ArrayList<>(source.fields());
        Set<String> names = new LinkedHashSet<>();
        fields.forEach(field -> names.add(field.name()));
        target.fields().stream().filter(field -> names.add(field.name())).forEach(fields::add);
        Map<String, String> keyTypes = EntityResolutionMatcher.keyTypes(fields);
        List<Map<String, String>> targetKeys = new ArrayList<>();
        for (EntityResolutionMatcher.Rec record : target.records()) {
            targetKeys.add(EntityResolutionMatcher.keys(record, fields, true));
        }
        Set<Integer> claimed = new LinkedHashSet<>();
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("SourceInputSourceARN", "SourceRecordId", "TargetInputSourceARN", "TargetRecordId",
                "TargetMatchID", "MatchRule"));
        Set<String> mappedTargets = new LinkedHashSet<>();
        for (EntityResolutionMatcher.Rec record : source.records()) {
            Map<String, String> keys = EntityResolutionMatcher.keys(record, fields, true);
            outer:
            for (JsonNode rule : rules) {
                List<String> matching = new ArrayList<>();
                rule.path("matchingKeys").forEach(key -> matching.add(key.asText()));
                for (int index = 0; index < target.records().size(); index++) {
                    if (oneTarget && claimed.contains(index)) {
                        continue;
                    }
                    if (EntityResolutionMatcher.ruleMatches(matching, keys, targetKeys.get(index), keyTypes,
                            manyToMany)) {
                        EntityResolutionMatcher.Rec matched = target.records().get(index);
                        claimed.add(index);
                        mappedTargets.add(matched.sourceArn() + "/" + matched.id());
                        rows.add(List.of(record.sourceArn(), record.id(), matched.sourceArn(), matched.id(),
                                target.matchIds().getOrDefault(matched, ""), rule.path("ruleName").asText()));
                        break outer;
                    }
                }
            }
        }
        String jobType = meta(job).path("jobType").asText("BATCH");
        int mapped = rows.size() - 1;
        if (!"DELETE_ONLY".equals(jobType)) {
            for (JsonNode output : job.path("outputSourceConfig")) {
                data.writeCsv(output.path("outputS3Path").asText(), workflowName + "/" + job.path("jobId").asText()
                        + "/success/run-0-part-00000.csv", rows);
            }
        }
        int inputs = source.records().size() + target.records().size();
        job.putObject("metrics").put("inputRecords", inputs).put("totalRecordsProcessed", inputs)
                .put("recordsNotProcessed", 0).put("totalMappedRecords", mapped)
                .put("totalMappedSourceRecords", mapped).put("totalMappedTargetRecords", mappedTargets.size())
                .put("uniqueRecordsLoaded", inputs).put("newMappedRecords", mapped)
                .put("newMappedSourceRecords", mapped).put("newMappedTargetRecords", mappedTargets.size())
                .put("newUniqueRecordsLoaded", inputs).put("deleteRecordsProcessed", 0);
    }

    // ---------------------------------------------------------------- real time

    /**
     * Matches records against each other and, unless {@code EVENTUAL_NO_LOOKUP}, the workflow's record index, and
     * keeps them in the index. A record of a source the workflow does not read fails.
     */
    public synchronized ObjectNode generateMatchId(JsonNode input, String region) {
        String name = text(input, "workflowName");
        ObjectNode workflow = service.matchingWorkflow(region, name);
        Set<String> sources = new LinkedHashSet<>();
        workflow.path("inputSourceConfig").forEach(source -> sources.add(source.path("inputSourceARN").asText()));
        List<EntityResolutionMatcher.Field> fields = workflowFields(region, workflow);
        String processing = input.path("processingType").asText("CONSISTENT");
        ObjectNode index = index(region, name);
        List<EntityResolutionMatcher.Rec> records = new ArrayList<>();
        Set<EntityResolutionMatcher.Rec> given = new LinkedHashSet<>();
        ObjectNode response = store.mapper().createObjectNode();
        ArrayNode failed = response.putArray("failedRecords");
        for (JsonNode record : input.path("records")) {
            String source = record.path("inputSourceARN").asText();
            String id = record.path("uniqueId").asText();
            if (!sources.contains(source)) {
                failed.addObject().put("inputSourceARN", source).put("uniqueId", id)
                        .put("errorMessage", "The input source is not one the workflow reads.");
                continue;
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            record.path("recordAttributeMap").fields().forEachRemaining(entry -> attributes.put(entry.getKey(),
                    entry.getValue().asText()));
            EntityResolutionMatcher.Rec rec = new EntityResolutionMatcher.Rec(source, id, attributes);
            records.add(rec);
            given.add(rec);
        }
        if (!"EVENTUAL_NO_LOOKUP".equals(processing)) {
            index.fields().forEachRemaining(entry -> {
                EntityResolutionMatcher.Rec stored = fromIndex(entry.getValue());
                boolean replaced = given.stream().anyMatch(rec -> rec.sourceArn().equals(stored.sourceArn())
                        && rec.id().equals(stored.id()));
                if (!replaced) {
                    records.add(stored);
                }
            });
        }
        List<EntityResolutionMatcher.Group> groups = EntityResolutionMatcher.match(records, fields,
                workflow.path("resolutionTechniques"), true);
        ArrayNode matchGroups = response.putArray("matchGroups");
        for (EntityResolutionMatcher.Group group : groups) {
            if (group.records().stream().noneMatch(given::contains)) {
                continue;
            }
            String matchId = existingMatchId(index, group.records());
            ObjectNode out = matchGroups.addObject();
            ArrayNode members = out.putArray("records");
            for (EntityResolutionMatcher.Rec record : group.records()) {
                members.addObject().put("inputSourceARN", record.sourceArn()).put("recordId", record.id());
                index.set(indexKey(record.sourceArn(), record.id()), indexEntry(record, matchId, group.rule()));
            }
            out.put("matchId", matchId).put("matchRule", group.rule() == null ? "" : group.rule());
        }
        store.put(region, RECORDS, name, index);
        ArrayNode ordered = store.mapper().createArrayNode();
        ordered.addAll(matchGroups);
        response.set("matchGroups", ordered);
        response.set("failedRecords", failed);
        return response;
    }

    /** The match a record belongs to in the workflow's index: its own entry, or the first indexed record it matches. */
    public synchronized ObjectNode getMatchId(JsonNode input, String region) {
        String name = text(input, "workflowName");
        ObjectNode workflow = service.matchingWorkflow(region, name);
        List<EntityResolutionMatcher.Field> fields = workflowFields(region, workflow);
        Map<String, String> attributes = new LinkedHashMap<>();
        input.path("record").fields().forEachRemaining(entry -> attributes.put(entry.getKey(),
                entry.getValue().asText()));
        String idField = EntityResolutionMatcher.uniqueIdField(fields);
        ObjectNode index = index(region, name);
        ObjectNode response = store.mapper().createObjectNode();
        String id = idField == null ? null : attributes.get(idField);
        List<EntityResolutionMatcher.Rec> candidates = new ArrayList<>();
        index.fields().forEachRemaining(entry -> candidates.add(fromIndex(entry.getValue())));
        if (id != null) {
            for (Map.Entry<String, JsonNode> entry : iterable(index)) {
                if (id.equals(entry.getValue().path("id").asText())) {
                    response.put("matchId", entry.getValue().path("matchId").asText())
                            .put("matchRule", entry.getValue().path("rule").asText(""));
                    return response;
                }
            }
        }
        if (candidates.isEmpty()) {
            return response;
        }
        EntityResolutionMatcher.Rec probe = new EntityResolutionMatcher.Rec("", id == null ? "" : id, attributes);
        List<EntityResolutionMatcher.Rec> records = new ArrayList<>(candidates);
        records.add(probe);
        boolean normalize = input.path("applyNormalization").asBoolean(true);
        for (EntityResolutionMatcher.Group group : EntityResolutionMatcher.match(records, fields,
                workflow.path("resolutionTechniques"), normalize)) {
            if (group.records().contains(probe) && group.records().size() > 1) {
                EntityResolutionMatcher.Rec member = group.records().stream().filter(rec -> rec != probe)
                        .findFirst().orElseThrow();
                response.put("matchId", index.path(indexKey(member.sourceArn(), member.id())).path("matchId")
                        .asText()).put("matchRule", group.rule() == null ? "" : group.rule());
            }
        }
        return response;
    }

    /**
     * Removes records from the workflow's index. An ID the index does not hold is a validation error for that ID;
     * a record left alone in a match by the removal is disconnected and gets a match of its own.
     */
    public synchronized ObjectNode batchDeleteUniqueId(JsonNode input, String region) {
        String name = text(input, "workflowName");
        ObjectNode workflow = service.matchingWorkflow(region, name);
        String source = optional(input, "inputSource");
        ObjectNode index = index(region, name);
        ObjectNode response = store.mapper().createObjectNode();
        response.put("status", "COMPLETED");
        ArrayNode errors = response.putArray("errors");
        ArrayNode deleted = response.putArray("deleted");
        ArrayNode disconnected = response.putArray("disconnectedUniqueIds");
        Set<String> touchedMatches = new LinkedHashSet<>();
        for (JsonNode id : ids(input.get("uniqueIds"))) {
            String removed = null;
            for (Map.Entry<String, JsonNode> entry : iterable(index)) {
                JsonNode stored = entry.getValue();
                if (id.asText().equals(stored.path("id").asText())
                        && (source == null || source.equals(stored.path("sourceArn").asText()))) {
                    removed = entry.getKey();
                    touchedMatches.add(stored.path("matchId").asText());
                }
            }
            if (removed == null) {
                errors.addObject().put("uniqueId", id.asText()).put("errorType", "VALIDATION_ERROR");
            } else {
                index.remove(removed);
                deleted.addObject().put("uniqueId", id.asText());
            }
        }
        for (String matchId : touchedMatches) {
            List<String> left = new ArrayList<>();
            for (Map.Entry<String, JsonNode> entry : iterable(index)) {
                if (matchId.equals(entry.getValue().path("matchId").asText())) {
                    left.add(entry.getKey());
                }
            }
            if (left.size() == 1) {
                ObjectNode alone = (ObjectNode) index.get(left.get(0));
                alone.put("matchId", newMatchId()).remove("rule");
                disconnected.add(alone.path("id").asText());
            }
        }
        store.put(region, RECORDS, workflow.path("workflowName").asText(), index);
        return response;
    }

    // ---------------------------------------------------------------- index

    private ObjectNode index(String region, String workflowName) {
        return store.get(region, RECORDS, workflowName).orElseGet(() -> store.mapper().createObjectNode());
    }

    private static String indexKey(String sourceArn, String id) {
        return sourceArn + "|" + id;
    }

    private ObjectNode indexEntry(EntityResolutionMatcher.Rec record, String matchId, String rule) {
        ObjectNode entry = store.mapper().createObjectNode();
        entry.put("sourceArn", record.sourceArn()).put("id", record.id()).put("matchId", matchId);
        if (rule != null) {
            entry.put("rule", rule);
        }
        entry.set("attributes", store.mapper().valueToTree(record.attributes()));
        return entry;
    }

    private static EntityResolutionMatcher.Rec fromIndex(JsonNode entry) {
        Map<String, String> attributes = new LinkedHashMap<>();
        entry.path("attributes").fields().forEachRemaining(field -> attributes.put(field.getKey(),
                field.getValue().asText()));
        return new EntityResolutionMatcher.Rec(entry.path("sourceArn").asText(), entry.path("id").asText(),
                attributes);
    }

    /** The match ID a group keeps: one a member already has in the index, or a new one. */
    private static String existingMatchId(ObjectNode index, List<EntityResolutionMatcher.Rec> records) {
        for (EntityResolutionMatcher.Rec record : records) {
            JsonNode entry = index.get(indexKey(record.sourceArn(), record.id()));
            if (entry != null && entry.hasNonNull("matchId")) {
                return entry.get("matchId").asText();
            }
        }
        return newMatchId();
    }

    private static String newMatchId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private List<EntityResolutionMatcher.Field> workflowFields(String region, ObjectNode workflow) {
        Map<String, EntityResolutionMatcher.Field> fields = new LinkedHashMap<>();
        for (JsonNode source : workflow.path("inputSourceConfig")) {
            EntityResolutionMatcher.fields(service.schema(region, source.path("schemaName").asText())
                    .path("mappedInputFields")).forEach(field -> fields.putIfAbsent(field.name(), field));
        }
        return new ArrayList<>(fields.values());
    }

    /** The IDs of a header list, which arrives as one comma-separated value or as a list. */
    private ArrayNode ids(JsonNode value) {
        ArrayNode ids = store.mapper().createArrayNode();
        if (value == null) {
            return ids;
        }
        if (value.isArray()) {
            value.forEach(item -> {
                for (String part : item.asText().split(",")) {
                    if (!part.isBlank()) {
                        ids.add(part.trim());
                    }
                }
            });
        } else {
            for (String part : value.asText().split(",")) {
                if (!part.isBlank()) {
                    ids.add(part.trim());
                }
            }
        }
        return ids;
    }

    private static Iterable<Map.Entry<String, JsonNode>> iterable(ObjectNode node) {
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        node.fields().forEachRemaining(entries::add);
        return entries;
    }

    private static String sha256(String value) {
        if (value == null) {
            return "";
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
