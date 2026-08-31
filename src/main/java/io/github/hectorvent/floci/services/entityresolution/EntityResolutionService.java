package io.github.hectorvent.floci.services.entityresolution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.entityresolution.model.IdMappingJob;
import io.github.hectorvent.floci.services.entityresolution.model.IdMappingWorkflow;
import io.github.hectorvent.floci.services.entityresolution.model.IdNamespace;
import io.github.hectorvent.floci.services.entityresolution.model.MatchEntry;
import io.github.hectorvent.floci.services.entityresolution.model.MatchingJob;
import io.github.hectorvent.floci.services.entityresolution.model.MatchingWorkflow;
import io.github.hectorvent.floci.services.entityresolution.model.SchemaMapping;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.HttpHeaders;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS Entity Resolution restJson1 — schema mappings, matching workflows, ID
 * namespaces, ID mapping workflows, and the matching data-plane (jobs, match
 * ids, unique-id deletes).
 */
@ApplicationScoped
public class EntityResolutionService implements TagHandler {

    static final String SERVICE = "entityresolution";
    private static final String TOKEN_PREFIX = "entityresolution:v1:";
    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int MAX_RESULTS = 25;
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,255}");
    private static final Pattern MATCHING_WORKFLOW_ARN = Pattern.compile(
            "^arn:aws(-[a-z]+)*:entityresolution:[^:]+:[^:]+:matchingworkflow/.+");
    private static final Set<String> NAMESPACE_TYPES = Set.of("SOURCE", "TARGET");
    private static final Set<String> IN_PROGRESS = Set.of("QUEUED", "RUNNING");

    private final StorageBackend<String, SchemaMapping> schemas;
    private final StorageBackend<String, MatchingWorkflow> matchingWorkflows;
    private final StorageBackend<String, IdNamespace> namespaces;
    private final StorageBackend<String, IdMappingWorkflow> idMappingWorkflows;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public EntityResolutionService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("entityresolution", "entityresolution-schemas.json",
                        new TypeReference<Map<String, SchemaMapping>>() {
                        }),
                storageFactory.create("entityresolution", "entityresolution-matching-workflows.json",
                        new TypeReference<Map<String, MatchingWorkflow>>() {
                        }),
                storageFactory.create("entityresolution", "entityresolution-id-namespaces.json",
                        new TypeReference<Map<String, IdNamespace>>() {
                        }),
                storageFactory.create("entityresolution", "entityresolution-id-mapping-workflows.json",
                        new TypeReference<Map<String, IdMappingWorkflow>>() {
                        }),
                regionResolver, objectMapper);
    }

    EntityResolutionService(
            StorageBackend<String, SchemaMapping> schemas,
            StorageBackend<String, MatchingWorkflow> matchingWorkflows,
            StorageBackend<String, IdNamespace> namespaces,
            StorageBackend<String, IdMappingWorkflow> idMappingWorkflows,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.schemas = schemas;
        this.matchingWorkflows = matchingWorkflows;
        this.namespaces = namespaces;
        this.idMappingWorkflows = idMappingWorkflows;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized SchemaMapping createSchemaMapping(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "schemaName");
        validateName(name, "schemaName");
        JsonNode fields = requireArray(request, "mappedInputFields");
        String key = storageKey(region, name);
        if (schemas.get(key).isPresent()) {
            throw conflict("Schema mapping " + name + " already exists.");
        }
        long now = now();
        SchemaMapping mapping = new SchemaMapping();
        mapping.setSchemaName(name);
        mapping.setSchemaArn(arn(region, "schemamapping/" + name));
        mapping.setDescription(optionalText(request, "description"));
        mapping.setMappedInputFields(fields.deepCopy());
        mapping.setTags(readTags(request.get("tags")));
        mapping.setCreatedAt(now);
        mapping.setUpdatedAt(now);
        schemas.put(key, mapping);
        return mapping;
    }

    public SchemaMapping getSchemaMapping(String region, String schemaName) {
        return requireSchema(region, schemaName);
    }

    public synchronized SchemaMapping updateSchemaMapping(String region, String schemaName, JsonNode request) {
        requireObject(request, "Request body");
        SchemaMapping mapping = requireSchema(region, schemaName);
        if (schemaHasWorkflows(region, mapping.getSchemaName())) {
            throw conflict("Cannot update schema mapping " + mapping.getSchemaName()
                    + " because it is referenced by a matching workflow.");
        }
        if (request.has("mappedInputFields") && !request.get("mappedInputFields").isNull()) {
            mapping.setMappedInputFields(requireArray(request, "mappedInputFields").deepCopy());
        }
        if (request.has("description")) {
            mapping.setDescription(optionalText(request, "description"));
        }
        mapping.setUpdatedAt(now());
        schemas.put(storageKey(region, mapping.getSchemaName()), mapping);
        return mapping;
    }

    public synchronized void deleteSchemaMapping(String region, String schemaName) {
        String name = decode(schemaName);
        if (name == null || name.isBlank()) {
            throw validation("schemaName must be a string.");
        }
        String key = storageKey(region, name);
        if (schemas.get(key).isEmpty()) {
            return;
        }
        if (schemaHasWorkflows(region, name)) {
            throw conflict("Schema mapping " + name + " is referenced by a workflow.");
        }
        schemas.delete(key);
    }

    public Page<SchemaMapping> listSchemaMappings(String region, String maxResults, String nextToken) {
        List<SchemaMapping> items = schemas.scan(key -> key.startsWith(region + "::"));
        items.sort(Comparator.comparing(SchemaMapping::getSchemaName, Comparator.nullsLast(String::compareTo)));
        return page(items, parseMaxResults(maxResults), nextToken);
    }

    public boolean schemaHasWorkflows(String region, String schemaName) {
        for (MatchingWorkflow workflow : matchingWorkflows.scan(key -> key.startsWith(region + "::"))) {
            if (referencesSchema(workflow.getInputSourceConfig(), schemaName)) {
                return true;
            }
        }
        for (IdNamespace namespace : namespaces.scan(key -> key.startsWith(region + "::"))) {
            if (referencesSchema(namespace.getInputSourceConfig(), schemaName)) {
                return true;
            }
        }
        return false;
    }

    public synchronized MatchingWorkflow createMatchingWorkflow(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "workflowName");
        validateName(name, "workflowName");
        JsonNode inputs = requireArray(request, "inputSourceConfig");
        JsonNode outputs = requireArray(request, "outputSourceConfig");
        JsonNode techniques = requireObjectField(request, "resolutionTechniques");
        String roleArn = requireText(request, "roleArn");
        validateSchemaRefs(region, inputs);
        String key = storageKey(region, name);
        if (matchingWorkflows.get(key).isPresent()) {
            throw conflict("Matching workflow " + name + " already exists.");
        }
        long now = now();
        MatchingWorkflow workflow = new MatchingWorkflow();
        workflow.setWorkflowName(name);
        workflow.setWorkflowArn(arn(region, "matchingworkflow/" + name));
        workflow.setDescription(optionalText(request, "description"));
        workflow.setInputSourceConfig(inputs.deepCopy());
        workflow.setOutputSourceConfig(outputs.deepCopy());
        workflow.setResolutionTechniques(techniques.deepCopy());
        workflow.setIncrementalRunConfig(optionalObject(request, "incrementalRunConfig"));
        workflow.setRoleArn(roleArn);
        workflow.setTags(readTags(request.get("tags")));
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        workflow.setJobs(new LinkedHashMap<>());
        workflow.setMatchIndex(new LinkedHashMap<>());
        matchingWorkflows.put(key, workflow);
        return workflow;
    }

    public MatchingWorkflow getMatchingWorkflow(String region, String workflowName) {
        return requireMatchingWorkflow(region, workflowName);
    }

    public synchronized MatchingWorkflow updateMatchingWorkflow(String region, String workflowName, JsonNode request) {
        requireObject(request, "Request body");
        MatchingWorkflow workflow = requireMatchingWorkflow(region, workflowName);
        JsonNode inputs = requireArray(request, "inputSourceConfig");
        JsonNode outputs = requireArray(request, "outputSourceConfig");
        JsonNode techniques = requireObjectField(request, "resolutionTechniques");
        validateSchemaRefs(region, inputs);
        workflow.setDescription(optionalText(request, "description"));
        workflow.setInputSourceConfig(inputs.deepCopy());
        workflow.setOutputSourceConfig(outputs.deepCopy());
        workflow.setResolutionTechniques(techniques.deepCopy());
        workflow.setIncrementalRunConfig(optionalObject(request, "incrementalRunConfig"));
        workflow.setRoleArn(requireText(request, "roleArn"));
        workflow.setUpdatedAt(now());
        matchingWorkflows.put(storageKey(region, workflow.getWorkflowName()), workflow);
        return workflow;
    }

    public synchronized void deleteMatchingWorkflow(String region, String workflowName) {
        String name = decode(workflowName);
        if (name == null || name.isBlank()) {
            throw validation("workflowName must be a string.");
        }
        String key = storageKey(region, name);
        MatchingWorkflow workflow = matchingWorkflows.get(key).orElse(null);
        if (workflow == null) {
            return;
        }
        if (hasInProgressJobs(workflow.getJobs())) {
            throw conflict("Matching workflow " + name + " has a running job.");
        }
        if (namespaceReferencesMatchingWorkflow(region, workflow.getWorkflowArn())) {
            throw conflict("Matching workflow " + name + " is referenced by an ID namespace.");
        }
        matchingWorkflows.delete(key);
    }

    public Page<MatchingWorkflow> listMatchingWorkflows(String region, String maxResults, String nextToken) {
        List<MatchingWorkflow> items = matchingWorkflows.scan(key -> key.startsWith(region + "::"));
        items.sort(Comparator.comparing(MatchingWorkflow::getWorkflowName, Comparator.nullsLast(String::compareTo)));
        return page(items, parseMaxResults(maxResults), nextToken);
    }

    public List<MatchingJob> listMatchingJobs(String region, String workflowName) {
        MatchingWorkflow workflow = requireMatchingWorkflow(region, workflowName);
        List<MatchingJob> jobs = new ArrayList<>(jobsOf(workflow).values());
        jobs.sort(Comparator.comparing(MatchingJob::getStartTime).reversed());
        return jobs;
    }

    public MatchingJob getMatchingJob(String region, String workflowName, String jobId) {
        MatchingWorkflow workflow = requireMatchingWorkflow(region, workflowName);
        MatchingJob job = jobsOf(workflow).get(decode(jobId));
        if (job == null) {
            throw notFound("Job " + jobId + " was not found.");
        }
        return job;
    }

    public synchronized MatchingJob startMatchingJob(String region, String workflowName) {
        MatchingWorkflow workflow = requireMatchingWorkflow(region, workflowName);
        MatchingJob job = new MatchingJob();
        job.setJobId(newJobId());
        job.setWorkflowName(workflow.getWorkflowName());
        job.setStatus("QUEUED");
        job.setStartTime(now());
        job.setOutputSourceConfig(copy(workflow.getOutputSourceConfig()));
        Map<String, MatchingJob> jobs = jobsOf(workflow);
        jobs.put(job.getJobId(), job);
        workflow.setJobs(jobs);
        matchingWorkflows.put(storageKey(region, workflow.getWorkflowName()), workflow);
        return job;
    }

    public synchronized ObjectNode getMatchId(String region, String workflowName, JsonNode request) {
        requireObject(request, "Request body");
        MatchingWorkflow workflow = requireMatchingWorkflow(region, workflowName);
        JsonNode record = requireObjectField(request, "record");
        Map<String, String> attributes = stringMap(record);
        MatchEntry match = findMatch(workflow, attributes);
        ObjectNode response = objectMapper.createObjectNode();
        if (match != null) {
            response.put("matchId", match.getMatchId());
            if (match.getMatchRule() != null) {
                response.put("matchRule", match.getMatchRule());
            }
        }
        return response;
    }

    public synchronized ObjectNode generateMatchId(String region, String workflowName, JsonNode request) {
        requireObject(request, "Request body");
        MatchingWorkflow workflow = requireMatchingWorkflow(region, workflowName);
        JsonNode recordsNode = requireArray(request, "records");
        List<IncomingRecord> records = new ArrayList<>();
        ArrayNode failed = objectMapper.createArrayNode();
        for (JsonNode node : recordsNode) {
            requireObject(node, "records members");
            String uniqueId = requireText(node, "uniqueId");
            String inputSourceArn = requireText(node, "inputSourceARN");
            JsonNode attrsNode = requireObjectField(node, "recordAttributeMap");
            records.add(new IncomingRecord(uniqueId, inputSourceArn, stringMap(attrsNode)));
        }

        List<Rule> rules = matchingRules(workflow.getResolutionTechniques());
        Map<String, List<IncomingRecord>> remaining = new LinkedHashMap<>();
        remaining.put("all", new ArrayList<>(records));
        ArrayNode groups = objectMapper.createArrayNode();
        Map<String, MatchEntry> index = matchIndexOf(workflow);

        for (Rule rule : rules) {
            Map<String, List<IncomingRecord>> grouped = new LinkedHashMap<>();
            for (IncomingRecord record : remaining.getOrDefault("all", List.of())) {
                String key = ruleKey(record.attributes, rule.matchingKeys);
                if (key == null) {
                    ObjectNode fail = failed.addObject();
                    fail.put("inputSourceARN", record.inputSourceArn);
                    fail.put("uniqueId", record.uniqueId);
                    fail.put("errorMessage", "Record is missing matching keys for rule " + rule.ruleName);
                    continue;
                }
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(record);
            }
            List<IncomingRecord> unmatched = new ArrayList<>();
            for (List<IncomingRecord> group : grouped.values()) {
                if (group.size() < 2) {
                    unmatched.addAll(group);
                    continue;
                }
                String matchId = UUID.randomUUID().toString();
                ObjectNode matchGroup = groups.addObject();
                matchGroup.put("matchId", matchId);
                matchGroup.put("matchRule", rule.ruleName);
                ArrayNode matchedRecords = matchGroup.putArray("records");
                for (IncomingRecord record : group) {
                    ObjectNode matched = matchedRecords.addObject();
                    matched.put("inputSourceARN", record.inputSourceArn);
                    matched.put("recordId", record.uniqueId);
                    MatchEntry entry = new MatchEntry();
                    entry.setUniqueId(record.uniqueId);
                    entry.setMatchId(matchId);
                    entry.setMatchRule(rule.ruleName);
                    entry.setInputSourceArn(record.inputSourceArn);
                    entry.setAttributes(record.attributes);
                    index.put(record.uniqueId, entry);
                }
            }
            remaining.put("all", unmatched);
        }

        workflow.setMatchIndex(index);
        matchingWorkflows.put(storageKey(region, workflow.getWorkflowName()), workflow);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("matchGroups", groups);
        response.set("failedRecords", failed);
        return response;
    }

    public synchronized ObjectNode batchDeleteUniqueId(
            String region, String workflowName, HttpHeaders headers, JsonNode request) {
        MatchingWorkflow workflow = requireMatchingWorkflow(region, workflowName);
        List<String> uniqueIds = parseUniqueIds(headers, request);
        if (uniqueIds.isEmpty()) {
            throw validation("uniqueIds must contain at least one value.");
        }
        Map<String, MatchEntry> index = matchIndexOf(workflow);
        ArrayNode deleted = objectMapper.createArrayNode();
        ArrayNode errors = objectMapper.createArrayNode();
        ArrayNode disconnected = objectMapper.createArrayNode();
        for (String uniqueId : uniqueIds) {
            MatchEntry removed = index.remove(uniqueId);
            if (removed != null) {
                deleted.addObject().put("uniqueId", uniqueId);
            } else {
                ObjectNode error = errors.addObject();
                error.put("uniqueId", uniqueId);
                error.put("errorType", "VALIDATION_ERROR");
            }
        }
        workflow.setMatchIndex(index);
        matchingWorkflows.put(storageKey(region, workflow.getWorkflowName()), workflow);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", "COMPLETED");
        response.set("deleted", deleted);
        response.set("errors", errors);
        response.set("disconnectedUniqueIds", disconnected);
        return response;
    }

    public synchronized IdNamespace createIdNamespace(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "idNamespaceName");
        validateName(name, "idNamespaceName");
        String type = requireText(request, "type");
        if (!NAMESPACE_TYPES.contains(type)) {
            throw validation("type must be SOURCE or TARGET.");
        }
        JsonNode inputs = optionalArray(request, "inputSourceConfig");
        if ("TARGET".equals(type) && inputs != null) {
            validateTargetNamespaceInputs(inputs);
        }
        String key = storageKey(region, name);
        if (namespaces.get(key).isPresent()) {
            throw conflict("ID namespace " + name + " already exists.");
        }
        long now = now();
        IdNamespace namespace = new IdNamespace();
        namespace.setIdNamespaceName(name);
        namespace.setIdNamespaceArn(arn(region, "idnamespace/" + name));
        namespace.setDescription(optionalText(request, "description"));
        namespace.setType(type);
        namespace.setInputSourceConfig(inputs);
        namespace.setIdMappingWorkflowProperties(optionalArray(request, "idMappingWorkflowProperties"));
        namespace.setRoleArn(optionalText(request, "roleArn"));
        namespace.setTags(readTags(request.get("tags")));
        namespace.setCreatedAt(now);
        namespace.setUpdatedAt(now);
        namespaces.put(key, namespace);
        return namespace;
    }

    public IdNamespace getIdNamespace(String region, String idNamespaceName) {
        return requireNamespace(region, idNamespaceName);
    }

    public synchronized IdNamespace updateIdNamespace(String region, String idNamespaceName, JsonNode request) {
        requireObject(request, "Request body");
        IdNamespace namespace = requireNamespace(region, idNamespaceName);
        JsonNode inputs = optionalArray(request, "inputSourceConfig");
        if ("TARGET".equals(namespace.getType()) && inputs != null) {
            validateTargetNamespaceInputs(inputs);
        }
        if (request.has("description")) {
            namespace.setDescription(optionalText(request, "description"));
        }
        if (request.has("inputSourceConfig")) {
            namespace.setInputSourceConfig(inputs);
        }
        if (request.has("idMappingWorkflowProperties")) {
            namespace.setIdMappingWorkflowProperties(optionalArray(request, "idMappingWorkflowProperties"));
        }
        if (request.has("roleArn")) {
            namespace.setRoleArn(optionalText(request, "roleArn"));
        }
        namespace.setUpdatedAt(now());
        namespaces.put(storageKey(region, namespace.getIdNamespaceName()), namespace);
        return namespace;
    }

    public synchronized void deleteIdNamespace(String region, String idNamespaceName) {
        String name = decode(idNamespaceName);
        if (name == null || name.isBlank()) {
            throw validation("idNamespaceName must be a string.");
        }
        String key = storageKey(region, name);
        IdNamespace namespace = namespaces.get(key).orElse(null);
        if (namespace == null) {
            return;
        }
        if (idMappingWorkflowReferencesNamespace(region, namespace.getIdNamespaceArn())) {
            throw conflict("ID namespace " + name + " is referenced by an ID mapping workflow.");
        }
        namespaces.delete(key);
    }

    public Page<IdNamespace> listIdNamespaces(String region, String maxResults, String nextToken) {
        List<IdNamespace> items = namespaces.scan(key -> key.startsWith(region + "::"));
        items.sort(Comparator.comparing(IdNamespace::getIdNamespaceName, Comparator.nullsLast(String::compareTo)));
        return page(items, parseMaxResults(maxResults), nextToken);
    }

    public synchronized IdMappingWorkflow createIdMappingWorkflow(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "workflowName");
        validateName(name, "workflowName");
        JsonNode inputs = requireArray(request, "inputSourceConfig");
        JsonNode techniques = requireObjectField(request, "idMappingTechniques");
        String key = storageKey(region, name);
        if (idMappingWorkflows.get(key).isPresent()) {
            throw conflict("ID mapping workflow " + name + " already exists.");
        }
        long now = now();
        IdMappingWorkflow workflow = new IdMappingWorkflow();
        workflow.setWorkflowName(name);
        workflow.setWorkflowArn(arn(region, "idmappingworkflow/" + name));
        workflow.setDescription(optionalText(request, "description"));
        workflow.setInputSourceConfig(inputs.deepCopy());
        workflow.setOutputSourceConfig(optionalArray(request, "outputSourceConfig"));
        workflow.setIdMappingTechniques(techniques.deepCopy());
        workflow.setIncrementalRunConfig(optionalObject(request, "incrementalRunConfig"));
        workflow.setRoleArn(optionalText(request, "roleArn"));
        workflow.setTags(readTags(request.get("tags")));
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        workflow.setJobs(new LinkedHashMap<>());
        idMappingWorkflows.put(key, workflow);
        return workflow;
    }

    public IdMappingWorkflow getIdMappingWorkflow(String region, String workflowName) {
        return requireIdMappingWorkflow(region, workflowName);
    }

    public synchronized IdMappingWorkflow updateIdMappingWorkflow(
            String region, String workflowName, JsonNode request) {
        requireObject(request, "Request body");
        IdMappingWorkflow workflow = requireIdMappingWorkflow(region, workflowName);
        JsonNode inputs = requireArray(request, "inputSourceConfig");
        JsonNode techniques = requireObjectField(request, "idMappingTechniques");
        workflow.setDescription(optionalText(request, "description"));
        workflow.setInputSourceConfig(inputs.deepCopy());
        workflow.setOutputSourceConfig(optionalArray(request, "outputSourceConfig"));
        workflow.setIdMappingTechniques(techniques.deepCopy());
        workflow.setIncrementalRunConfig(optionalObject(request, "incrementalRunConfig"));
        workflow.setRoleArn(optionalText(request, "roleArn"));
        workflow.setUpdatedAt(now());
        idMappingWorkflows.put(storageKey(region, workflow.getWorkflowName()), workflow);
        return workflow;
    }

    public synchronized void deleteIdMappingWorkflow(String region, String workflowName) {
        String name = decode(workflowName);
        if (name == null || name.isBlank()) {
            throw validation("workflowName must be a string.");
        }
        String key = storageKey(region, name);
        IdMappingWorkflow workflow = idMappingWorkflows.get(key).orElse(null);
        if (workflow == null) {
            return;
        }
        if (hasInProgressIdMappingJobs(workflow.getJobs())) {
            throw conflict("ID mapping workflow " + name + " has a running job.");
        }
        idMappingWorkflows.delete(key);
    }

    public Page<IdMappingWorkflow> listIdMappingWorkflows(String region, String maxResults, String nextToken) {
        List<IdMappingWorkflow> items = idMappingWorkflows.scan(key -> key.startsWith(region + "::"));
        items.sort(Comparator.comparing(IdMappingWorkflow::getWorkflowName, Comparator.nullsLast(String::compareTo)));
        return page(items, parseMaxResults(maxResults), nextToken);
    }

    public List<IdMappingJob> listIdMappingJobs(String region, String workflowName) {
        IdMappingWorkflow workflow = requireIdMappingWorkflow(region, workflowName);
        List<IdMappingJob> jobs = new ArrayList<>(idMappingJobsOf(workflow).values());
        jobs.sort(Comparator.comparing(IdMappingJob::getStartTime).reversed());
        return jobs;
    }

    public IdMappingJob getIdMappingJob(String region, String workflowName, String jobId) {
        IdMappingWorkflow workflow = requireIdMappingWorkflow(region, workflowName);
        IdMappingJob job = idMappingJobsOf(workflow).get(decode(jobId));
        if (job == null) {
            throw notFound("Job " + jobId + " was not found.");
        }
        return job;
    }

    public synchronized IdMappingJob startIdMappingJob(String region, String workflowName, JsonNode request) {
        IdMappingWorkflow workflow = requireIdMappingWorkflow(region, workflowName);
        IdMappingJob job = new IdMappingJob();
        job.setJobId(newJobId());
        job.setWorkflowName(workflow.getWorkflowName());
        job.setStatus("QUEUED");
        job.setStartTime(now());
        JsonNode output = request != null ? optionalArray(request, "outputSourceConfig") : null;
        job.setOutputSourceConfig(output != null ? output : copy(workflow.getOutputSourceConfig()));
        job.setJobType(request != null ? optionalText(request, "jobType") : null);
        Map<String, IdMappingJob> jobs = idMappingJobsOf(workflow);
        jobs.put(job.getJobId(), job);
        workflow.setJobs(jobs);
        idMappingWorkflows.put(storageKey(region, workflow.getWorkflowName()), workflow);
        return job;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(tagsOf(requireByArn(region, arn)));
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireByArn(region, arn);
        Map<String, String> current = tagsOf(tagged);
        if (tags != null) {
            current.putAll(tags);
        }
        persistTags(region, tagged, current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireByArn(region, arn);
        Map<String, String> current = tagsOf(tagged);
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        persistTags(region, tagged, current);
    }

    private void persistTags(String region, Tagged tagged, Map<String, String> tags) {
        switch (tagged.kind) {
            case SCHEMA -> {
                tagged.schema.setTags(tags);
                schemas.put(storageKey(region, tagged.schema.getSchemaName()), tagged.schema);
            }
            case MATCHING -> {
                tagged.matching.setTags(tags);
                matchingWorkflows.put(storageKey(region, tagged.matching.getWorkflowName()), tagged.matching);
            }
            case NAMESPACE -> {
                tagged.namespace.setTags(tags);
                namespaces.put(storageKey(region, tagged.namespace.getIdNamespaceName()), tagged.namespace);
            }
            case ID_MAPPING -> {
                tagged.idMapping.setTags(tags);
                idMappingWorkflows.put(storageKey(region, tagged.idMapping.getWorkflowName()), tagged.idMapping);
            }
        }
    }

    private Tagged requireByArn(String region, String arn) {
        String decoded = decode(arn);
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decoded);
        } catch (IllegalArgumentException e) {
            throw validation("resourceArn is invalid.");
        }
        if (!SERVICE.equals(parsed.service())) {
            throw validation("resourceArn is invalid.");
        }
        String resource = parsed.resource();
        if (resource.startsWith("schemamapping/")) {
            SchemaMapping mapping = requireSchema(region, resource.substring("schemamapping/".length()));
            if (!decoded.equals(mapping.getSchemaArn())) {
                throw notFound("Resource " + decoded + " was not found.");
            }
            return Tagged.schema(mapping);
        }
        if (resource.startsWith("matchingworkflow/")) {
            MatchingWorkflow workflow = requireMatchingWorkflow(region, resource.substring("matchingworkflow/".length()));
            if (!decoded.equals(workflow.getWorkflowArn())) {
                throw notFound("Resource " + decoded + " was not found.");
            }
            return Tagged.matching(workflow);
        }
        if (resource.startsWith("idnamespace/")) {
            IdNamespace namespace = requireNamespace(region, resource.substring("idnamespace/".length()));
            if (!decoded.equals(namespace.getIdNamespaceArn())) {
                throw notFound("Resource " + decoded + " was not found.");
            }
            return Tagged.namespace(namespace);
        }
        if (resource.startsWith("idmappingworkflow/")) {
            IdMappingWorkflow workflow =
                    requireIdMappingWorkflow(region, resource.substring("idmappingworkflow/".length()));
            if (!decoded.equals(workflow.getWorkflowArn())) {
                throw notFound("Resource " + decoded + " was not found.");
            }
            return Tagged.idMapping(workflow);
        }
        throw validation("resourceArn is invalid.");
    }

    private SchemaMapping requireSchema(String region, String schemaName) {
        String name = decode(schemaName);
        validateName(name, "schemaName");
        return schemas.get(storageKey(region, name))
                .orElseThrow(() -> notFound("Schema mapping " + name + " was not found."));
    }

    private MatchingWorkflow requireMatchingWorkflow(String region, String workflowName) {
        String name = decode(workflowName);
        validateName(name, "workflowName");
        return matchingWorkflows.get(storageKey(region, name))
                .orElseThrow(() -> notFound("Matching workflow " + name + " was not found."));
    }

    private IdNamespace requireNamespace(String region, String idNamespaceName) {
        String name = decode(idNamespaceName);
        validateName(name, "idNamespaceName");
        return namespaces.get(storageKey(region, name))
                .orElseThrow(() -> notFound("ID namespace " + name + " was not found."));
    }

    private IdMappingWorkflow requireIdMappingWorkflow(String region, String workflowName) {
        String name = decode(workflowName);
        validateName(name, "workflowName");
        return idMappingWorkflows.get(storageKey(region, name))
                .orElseThrow(() -> notFound("ID mapping workflow " + name + " was not found."));
    }

    private void validateSchemaRefs(String region, JsonNode inputs) {
        for (JsonNode input : inputs) {
            requireObject(input, "inputSourceConfig members");
            String schemaName = requireText(input, "schemaName");
            requireSchema(region, schemaName);
        }
    }

    private void validateTargetNamespaceInputs(JsonNode inputs) {
        for (JsonNode input : inputs) {
            requireObject(input, "inputSourceConfig members");
            String arn = requireText(input, "inputSourceARN");
            if (!MATCHING_WORKFLOW_ARN.matcher(arn).matches()) {
                throw validation("Check that it follows the pattern: "
                        + "arn:(aws|aws-cn|aws-us-gov):entityresolution:[a-z0-9-]+:\\d{12}:matchingworkflow/{resource_name}");
            }
        }
    }

    private boolean namespaceReferencesMatchingWorkflow(String region, String workflowArn) {
        for (IdNamespace namespace : namespaces.scan(key -> key.startsWith(region + "::"))) {
            JsonNode inputs = namespace.getInputSourceConfig();
            if (inputs == null || !inputs.isArray()) {
                continue;
            }
            for (JsonNode input : inputs) {
                if (input != null && workflowArn.equals(text(input, "inputSourceARN"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean idMappingWorkflowReferencesNamespace(String region, String namespaceArn) {
        for (IdMappingWorkflow workflow : idMappingWorkflows.scan(key -> key.startsWith(region + "::"))) {
            JsonNode inputs = workflow.getInputSourceConfig();
            if (inputs == null || !inputs.isArray()) {
                continue;
            }
            for (JsonNode input : inputs) {
                if (input != null && namespaceArn.equals(text(input, "inputSourceARN"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean referencesSchema(JsonNode inputs, String schemaName) {
        if (inputs == null || !inputs.isArray()) {
            return false;
        }
        for (JsonNode input : inputs) {
            if (input != null && schemaName.equals(text(input, "schemaName"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInProgressJobs(Map<String, MatchingJob> jobs) {
        if (jobs == null) {
            return false;
        }
        for (MatchingJob job : jobs.values()) {
            if (job != null && IN_PROGRESS.contains(job.getStatus())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInProgressIdMappingJobs(Map<String, IdMappingJob> jobs) {
        if (jobs == null) {
            return false;
        }
        for (IdMappingJob job : jobs.values()) {
            if (job != null && IN_PROGRESS.contains(job.getStatus())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, MatchingJob> jobsOf(MatchingWorkflow workflow) {
        return workflow.getJobs() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(workflow.getJobs());
    }

    private static Map<String, IdMappingJob> idMappingJobsOf(IdMappingWorkflow workflow) {
        return workflow.getJobs() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(workflow.getJobs());
    }

    private static Map<String, MatchEntry> matchIndexOf(MatchingWorkflow workflow) {
        return workflow.getMatchIndex() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(workflow.getMatchIndex());
    }

    private static Map<String, String> tagsOf(Tagged tagged) {
        Map<String, String> tags = switch (tagged.kind) {
            case SCHEMA -> tagged.schema.getTags();
            case MATCHING -> tagged.matching.getTags();
            case NAMESPACE -> tagged.namespace.getTags();
            case ID_MAPPING -> tagged.idMapping.getTags();
        };
        return tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    private MatchEntry findMatch(MatchingWorkflow workflow, Map<String, String> attributes) {
        Map<String, MatchEntry> index = matchIndexOf(workflow);
        String uniqueId = attributes.get("id");
        if (uniqueId != null && index.containsKey(uniqueId)) {
            return index.get(uniqueId);
        }
        for (String value : attributes.values()) {
            if (value != null && index.containsKey(value)) {
                return index.get(value);
            }
        }
        for (MatchEntry entry : index.values()) {
            if (entry.getAttributes() != null && attributesEqual(entry.getAttributes(), attributes)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean attributesEqual(Map<String, String> left, Map<String, String> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (Map.Entry<String, String> entry : left.entrySet()) {
            if (!java.util.Objects.equals(entry.getValue(), right.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static List<Rule> matchingRules(JsonNode techniques) {
        List<Rule> rules = new ArrayList<>();
        if (techniques == null || !techniques.isObject()) {
            return rules;
        }
        JsonNode properties = techniques.get("ruleBasedProperties");
        if (properties == null || !properties.isObject()) {
            return rules;
        }
        JsonNode rulesNode = properties.get("rules");
        if (rulesNode == null || !rulesNode.isArray()) {
            return rules;
        }
        for (JsonNode ruleNode : rulesNode) {
            if (ruleNode == null || !ruleNode.isObject()) {
                continue;
            }
            String name = text(ruleNode, "ruleName");
            JsonNode keys = ruleNode.get("matchingKeys");
            if (name == null || keys == null || !keys.isArray()) {
                continue;
            }
            List<String> matchingKeys = new ArrayList<>();
            for (JsonNode key : keys) {
                if (key != null && key.isTextual()) {
                    matchingKeys.add(key.textValue());
                }
            }
            if (!matchingKeys.isEmpty()) {
                rules.add(new Rule(name, matchingKeys));
            }
        }
        return rules;
    }

    private static String ruleKey(Map<String, String> attributes, List<String> matchingKeys) {
        StringBuilder builder = new StringBuilder();
        for (String key : matchingKeys) {
            String value = attributes.get(key);
            if (value == null || value.isBlank()) {
                return null;
            }
            builder.append('\u0000').append(value);
        }
        return builder.toString();
    }

    private List<String> parseUniqueIds(HttpHeaders headers, JsonNode request) {
        List<String> values = new ArrayList<>();
        if (headers != null) {
            List<String> header = headers.getRequestHeader("uniqueIds");
            if (header == null || header.isEmpty()) {
                header = headers.getRequestHeader("uniqueids");
            }
            if (header != null) {
                for (String raw : header) {
                    values.addAll(splitUniqueIds(raw));
                }
            }
        }
        if (values.isEmpty() && request != null && request.has("uniqueIds")) {
            JsonNode node = request.get("uniqueIds");
            if (node.isArray()) {
                for (JsonNode value : node) {
                    if (value != null && value.isTextual() && !value.textValue().isBlank()) {
                        values.add(value.textValue());
                    }
                }
            } else if (node.isTextual()) {
                values.addAll(splitUniqueIds(node.textValue()));
            }
        }
        return values;
    }

    private static List<String> splitUniqueIds(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        for (String part : trimmed.split(",")) {
            String value = part.trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return values;
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && value.isTextual()) {
                values.put(entry.getKey(), value.textValue());
            }
        });
        return values;
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), resource).toString();
    }

    private static String storageKey(String region, String name) {
        return region + "::" + name;
    }

    private static String newJobId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private JsonNode copy(JsonNode node) {
        return node == null ? null : node.deepCopy();
    }

    static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static void validateName(String name, String field) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw validation(field + " must match [a-zA-Z0-9_-]+ and contain at most 255 characters.");
        }
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static JsonNode requireObjectField(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        requireObject(value, field);
        return value;
    }

    private static JsonNode requireArray(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) {
            throw validation(field + " must be an array.");
        }
        return value;
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    private JsonNode optionalObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        requireObject(value, field);
        return value.deepCopy();
    }

    private JsonNode optionalArray(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            throw validation(field + " must be an array.");
        }
        return value.deepCopy();
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        if (tagsNode == null || tagsNode.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!tagsNode.isObject()) {
            throw validation("tags must be an object.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || !value.isTextual()) {
                throw validation("tags contains an invalid key or value.");
            }
            tags.put(entry.getKey(), value.textValue());
        });
        return tags;
    }

    private static int parseMaxResults(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > MAX_RESULTS) {
                throw validation("maxResults must be between 1 and 25.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw validation("maxResults must be an integer between 1 and 25.");
        }
    }

    private static <T> Page<T> page(List<T> items, int maxResults, String nextToken) {
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + maxResults, items.size());
        String responseToken = end < items.size() ? encodeOffset(end) : null;
        return new Page<>(items.subList(offset, end), responseToken);
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw validation("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset > resultSize) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 400);
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    private record IncomingRecord(String uniqueId, String inputSourceArn, Map<String, String> attributes) {
    }

    private record Rule(String ruleName, List<String> matchingKeys) {
    }

    private static final class Tagged {
        private enum Kind {SCHEMA, MATCHING, NAMESPACE, ID_MAPPING}

        private final Kind kind;
        private final SchemaMapping schema;
        private final MatchingWorkflow matching;
        private final IdNamespace namespace;
        private final IdMappingWorkflow idMapping;

        private Tagged(Kind kind, SchemaMapping schema, MatchingWorkflow matching,
                       IdNamespace namespace, IdMappingWorkflow idMapping) {
            this.kind = kind;
            this.schema = schema;
            this.matching = matching;
            this.namespace = namespace;
            this.idMapping = idMapping;
        }

        static Tagged schema(SchemaMapping schema) {
            return new Tagged(Kind.SCHEMA, schema, null, null, null);
        }

        static Tagged matching(MatchingWorkflow matching) {
            return new Tagged(Kind.MATCHING, null, matching, null, null);
        }

        static Tagged namespace(IdNamespace namespace) {
            return new Tagged(Kind.NAMESPACE, null, null, namespace, null);
        }

        static Tagged idMapping(IdMappingWorkflow idMapping) {
            return new Tagged(Kind.ID_MAPPING, null, null, null, idMapping);
        }
    }
}
