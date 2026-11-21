package io.floci.aws.services.entityresolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.iam.AssumeRolePolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.CallerContext;
import io.floci.aws.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.floci.aws.services.entityresolution.EntityResolutionStore.ID_MAPPING_WORKFLOW;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.ID_NAMESPACE;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.MATCHING_WORKFLOW;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.SCHEMA;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.conflict;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.denied;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.invalid;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.meta;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.notFound;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.now;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.optional;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.text;
import static io.floci.aws.services.entityresolution.EntityResolutionStore.view;

/**
 * AWS Entity Resolution's resources: schema mappings, matching workflows, ID namespaces and ID mapping workflows,
 * their tags and resource policies. Jobs and real-time matching are {@link EntityResolutionJobs}'.
 *
 * <p>A workflow's role is checked as Entity Resolution assumes it: its trust policy must let
 * {@code entityresolution.amazonaws.com} assume it, and, with IAM enforcement on, its policies must let it read the
 * input tables from Glue and their data from S3 and write to the output location. A schema mapping a workflow uses
 * cannot change or be deleted, nor can an ID namespace an ID mapping workflow uses.
 */
@ApplicationScoped
public class EntityResolutionService {

    private static final IamPolicyEvaluator POLICIES = new IamPolicyEvaluator(new ObjectMapper());
    private static final AssumeRolePolicyEvaluator TRUST = new AssumeRolePolicyEvaluator(new ObjectMapper(), POLICIES);

    private final EntityResolutionStore store;
    private final EntityResolutionData data;
    private final IamService iam;
    private final EmulatorConfig config;
    private final Instance<EntityResolutionJobs> jobs;

    @Inject
    public EntityResolutionService(EntityResolutionStore store, EntityResolutionData data, IamService iam,
                                   EmulatorConfig config, Instance<EntityResolutionJobs> jobs) {
        this.store = store;
        this.data = data;
        this.iam = iam;
        this.config = config;
        this.jobs = jobs;
    }

    // ---------------------------------------------------------------- schema mappings

    public synchronized ObjectNode createSchemaMapping(JsonNode input, String region) {
        String name = text(input, "schemaName");
        if (store.get(region, SCHEMA, name).isPresent()) {
            throw conflict("A schema mapping named " + name + " already exists.");
        }
        validateFields(input.path("mappedInputFields"));
        ObjectNode schema = store.record();
        schema.put("schemaName", name).put("schemaArn", store.arn(region, SCHEMA, name))
                .put("description", input.path("description").asText(""));
        schema.set("mappedInputFields", input.get("mappedInputFields").deepCopy());
        schema.put("createdAt", now()).put("updatedAt", now());
        schema.set("tags", tags(input.get("tags")));
        store.put(region, SCHEMA, name, schema);
        return schemaResponse(schema);
    }

    public synchronized ObjectNode getSchemaMapping(JsonNode input, String region) {
        ObjectNode schema = schema(region, text(input, "schemaName"));
        ObjectNode response = view(schema);
        response.put("hasWorkflows", hasWorkflows(region, schema.path("schemaName").asText()));
        return response;
    }

    public synchronized ObjectNode listSchemaMappings(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode schema : store.list(region, SCHEMA, "", record -> true)) {
            ObjectNode item = store.mapper().createObjectNode();
            item.put("schemaName", schema.path("schemaName").asText())
                    .put("schemaArn", schema.path("schemaArn").asText())
                    .put("createdAt", schema.path("createdAt").asDouble())
                    .put("updatedAt", schema.path("updatedAt").asDouble())
                    .put("hasWorkflows", hasWorkflows(region, schema.path("schemaName").asText()));
            items.add(item);
        }
        return store.page(items, input, "schemaList");
    }

    public synchronized ObjectNode updateSchemaMapping(JsonNode input, String region) {
        String name = text(input, "schemaName");
        ObjectNode schema = schema(region, name);
        if (hasWorkflows(region, name)) {
            throw conflict("Schema mapping " + name + " is used by a workflow and cannot be updated.");
        }
        validateFields(input.path("mappedInputFields"));
        schema.set("mappedInputFields", input.get("mappedInputFields").deepCopy());
        if (input.has("description")) {
            schema.put("description", input.path("description").asText());
        }
        schema.put("updatedAt", now());
        store.put(region, SCHEMA, name, schema);
        return schemaResponse(schema);
    }

    /** Deletes a schema mapping no workflow uses; deleting one that does not exist succeeds. */
    public synchronized ObjectNode deleteSchemaMapping(JsonNode input, String region) {
        String name = text(input, "schemaName");
        if (store.get(region, SCHEMA, name).isPresent()) {
            if (hasWorkflows(region, name)) {
                throw conflict("Schema mapping " + name + " is used by a workflow and cannot be deleted.");
            }
            store.delete(region, SCHEMA, name);
        }
        return message("Schema mapping " + name + " deleted.");
    }

    private ObjectNode schemaResponse(ObjectNode schema) {
        ObjectNode response = store.mapper().createObjectNode();
        response.put("schemaName", schema.path("schemaName").asText())
                .put("schemaArn", schema.path("schemaArn").asText())
                .put("description", schema.path("description").asText(""));
        response.set("mappedInputFields", schema.get("mappedInputFields").deepCopy());
        return response;
    }

    /** A schema's fields: one UNIQUE_ID, names that do not repeat, and match keys of one type each. */
    private static void validateFields(JsonNode fields) {
        Set<String> names = new LinkedHashSet<>();
        Map<String, String> keyTypes = new HashMap<>();
        int uniqueIds = 0;
        for (JsonNode field : fields) {
            String name = field.path("fieldName").asText();
            if (!names.add(name)) {
                throw invalid("Field " + name + " is mapped twice.");
            }
            String type = field.path("type").asText();
            if ("UNIQUE_ID".equals(type)) {
                uniqueIds++;
                if (field.hasNonNull("matchKey")) {
                    throw invalid("The UNIQUE_ID field " + name + " cannot have a match key.");
                }
            }
            String key = field.path("matchKey").asText(null);
            if (key != null && !field.hasNonNull("groupName")) {
                String family = type.replaceFirst("_.*", "");
                String previous = keyTypes.putIfAbsent(key, family);
                if (previous != null && !previous.equals(family)) {
                    throw invalid("Match key " + key + " maps fields of different types.");
                }
            }
        }
        if (uniqueIds != 1) {
            throw invalid("A schema mapping needs exactly one UNIQUE_ID field.");
        }
    }

    /** Whether a matching workflow or an ID namespace uses a schema mapping. */
    boolean hasWorkflows(String region, String schemaName) {
        for (String kind : List.of(MATCHING_WORKFLOW, ID_NAMESPACE, ID_MAPPING_WORKFLOW)) {
            for (ObjectNode record : store.list(region, kind, "", r -> true)) {
                for (JsonNode source : record.path("inputSourceConfig")) {
                    if (schemaName.equals(source.path("schemaName").asText(null))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    ObjectNode schema(String region, String name) {
        return store.get(region, SCHEMA, name).orElseThrow(() -> notFound("Schema mapping " + name
                + " not found."));
    }

    // ---------------------------------------------------------------- matching workflows

    public synchronized ObjectNode createMatchingWorkflow(JsonNode input, String region) {
        String name = text(input, "workflowName");
        if (store.get(region, MATCHING_WORKFLOW, name).isPresent()) {
            throw conflict("A matching workflow named " + name + " already exists.");
        }
        ObjectNode workflow = store.record();
        workflow.put("workflowName", name).put("workflowArn", store.arn(region, MATCHING_WORKFLOW, name));
        applyMatching(region, workflow, input);
        workflow.put("createdAt", now()).put("updatedAt", now());
        workflow.set("tags", tags(input.get("tags")));
        store.put(region, MATCHING_WORKFLOW, name, workflow);
        return matchingResponse(workflow);
    }

    public synchronized ObjectNode getMatchingWorkflow(JsonNode input, String region) {
        return view(matchingWorkflow(region, text(input, "workflowName")));
    }

    public synchronized ObjectNode listMatchingWorkflows(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode workflow : store.list(region, MATCHING_WORKFLOW, "", record -> true)) {
            ObjectNode item = summary(workflow);
            item.put("resolutionType", workflow.path("resolutionTechniques").path("resolutionType").asText());
            items.add(item);
        }
        return store.page(items, input, "workflowSummaries");
    }

    public synchronized ObjectNode updateMatchingWorkflow(JsonNode input, String region) {
        String name = text(input, "workflowName");
        ObjectNode workflow = matchingWorkflow(region, name);
        if (jobs.isResolvable() && jobs.get().running(region, EntityResolutionStore.MATCHING_JOB, name)) {
            throw conflict("Matching workflow " + name + " has a job running.");
        }
        applyMatching(region, workflow, input);
        workflow.put("updatedAt", now());
        store.put(region, MATCHING_WORKFLOW, name, workflow);
        return matchingResponse(workflow);
    }

    /** Deletes a matching workflow with no job running that no ID namespace reads; a missing one succeeds. */
    public synchronized ObjectNode deleteMatchingWorkflow(JsonNode input, String region) {
        String name = text(input, "workflowName");
        Optional<ObjectNode> workflow = store.get(region, MATCHING_WORKFLOW, name);
        if (workflow.isPresent()) {
            if (jobs.isResolvable() && jobs.get().running(region, EntityResolutionStore.MATCHING_JOB, name)) {
                throw conflict("Matching workflow " + name + " has a job running.");
            }
            String arn = workflow.get().path("workflowArn").asText();
            for (ObjectNode namespace : store.list(region, ID_NAMESPACE, "", record -> true)) {
                for (JsonNode source : namespace.path("inputSourceConfig")) {
                    if (arn.equals(source.path("inputSourceARN").asText())) {
                        throw conflict("Matching workflow " + name + " is an input of ID namespace "
                                + namespace.path("idNamespaceName").asText() + ".");
                    }
                }
            }
            store.delete(region, MATCHING_WORKFLOW, name);
            if (jobs.isResolvable()) {
                jobs.get().forget(region, EntityResolutionStore.MATCHING_JOB, name);
            }
        }
        return message("Matching workflow " + name + " deleted.");
    }

    /** Validates and applies a matching workflow's configuration from a create or update request. */
    private void applyMatching(String region, ObjectNode workflow, JsonNode input) {
        String role = text(input, "roleArn");
        JsonNode sources = input.path("inputSourceConfig");
        if (!sources.isArray() || sources.isEmpty()) {
            throw invalid("inputSourceConfig needs at least one input source.");
        }
        Set<String> matchKeys = new LinkedHashSet<>();
        Set<String> fieldNames = new LinkedHashSet<>();
        List<Table> tables = new ArrayList<>();
        for (JsonNode source : sources) {
            ObjectNode schema = schemaFor(region, text(source, "schemaName"));
            Table table = data.table(text(source, "inputSourceARN"));
            tables.add(table);
            List<String> columns = EntityResolutionData.columns(table);
            for (EntityResolutionMatcher.Field field : EntityResolutionMatcher.fields(schema.path("mappedInputFields"))) {
                if (!columns.isEmpty() && !columns.contains(field.name())) {
                    throw invalid("The Glue table " + table.getName() + " has no column " + field.name() + ".");
                }
                fieldNames.add(field.name());
                if (field.matchKey() != null) {
                    matchKeys.add(field.matchKey());
                }
            }
        }
        JsonNode outputs = input.path("outputSourceConfig");
        if (!outputs.isArray() || outputs.isEmpty()) {
            throw invalid("outputSourceConfig needs at least one output source.");
        }
        for (JsonNode output : outputs) {
            String path = optional(output, "outputS3Path");
            if (path != null && EntityResolutionData.location(path) == null) {
                throw invalid("outputS3Path must be an S3 path: " + path);
            }
            for (JsonNode attribute : output.path("output")) {
                if (!fieldNames.contains(attribute.path("name").asText())) {
                    throw invalid("Output attribute " + attribute.path("name").asText()
                            + " is not a field of the input schemas.");
                }
            }
        }
        JsonNode techniques = input.path("resolutionTechniques");
        EntityResolutionMatcher.validate(techniques, matchKeys);
        if ("PROVIDER".equals(techniques.path("resolutionType").asText())) {
            throw invalid("Provider service " + techniques.path("providerProperties").path("providerServiceArn")
                    .asText() + " does not exist.");
        }
        authorizeRole(role, tables, outputs, region);
        workflow.put("description", input.path("description").asText(workflow.path("description").asText("")));
        if (workflow.path("description").asText().isEmpty()) {
            workflow.remove("description");
        }
        workflow.set("inputSourceConfig", sources.deepCopy());
        workflow.set("outputSourceConfig", outputs.deepCopy());
        workflow.set("resolutionTechniques", techniques.deepCopy());
        if (input.has("incrementalRunConfig")) {
            workflow.set("incrementalRunConfig", input.get("incrementalRunConfig").deepCopy());
        } else {
            workflow.remove("incrementalRunConfig");
        }
        workflow.put("roleArn", role);
    }

    private ObjectNode matchingResponse(ObjectNode workflow) {
        ObjectNode response = view(workflow);
        response.remove(List.of("createdAt", "updatedAt", "tags"));
        return response;
    }

    ObjectNode matchingWorkflow(String region, String name) {
        return store.get(region, MATCHING_WORKFLOW, name).orElseThrow(() -> notFound("Matching workflow " + name
                + " not found."));
    }

    // ---------------------------------------------------------------- ID namespaces

    public synchronized ObjectNode createIdNamespace(JsonNode input, String region) {
        String name = text(input, "idNamespaceName");
        if (store.get(region, ID_NAMESPACE, name).isPresent()) {
            throw conflict("An ID namespace named " + name + " already exists.");
        }
        ObjectNode namespace = store.record();
        namespace.put("idNamespaceName", name).put("idNamespaceArn", store.arn(region, ID_NAMESPACE, name))
                .put("type", text(input, "type"));
        applyNamespace(region, namespace, input);
        namespace.put("createdAt", now()).put("updatedAt", now());
        namespace.set("tags", tags(input.get("tags")));
        store.put(region, ID_NAMESPACE, name, namespace);
        return view(namespace);
    }

    public synchronized ObjectNode getIdNamespace(JsonNode input, String region) {
        return view(idNamespace(region, text(input, "idNamespaceName")));
    }

    public synchronized ObjectNode listIdNamespaces(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode namespace : store.list(region, ID_NAMESPACE, "", record -> true)) {
            ObjectNode item = store.mapper().createObjectNode();
            item.put("idNamespaceName", namespace.path("idNamespaceName").asText())
                    .put("idNamespaceArn", namespace.path("idNamespaceArn").asText());
            if (namespace.has("description")) {
                item.put("description", namespace.path("description").asText());
            }
            ArrayNode metadata = item.putArray("idMappingWorkflowProperties");
            namespace.path("idMappingWorkflowProperties").forEach(properties -> metadata.addObject()
                    .put("idMappingType", properties.path("idMappingType").asText()));
            item.put("type", namespace.path("type").asText()).put("createdAt", namespace.path("createdAt").asDouble())
                    .put("updatedAt", namespace.path("updatedAt").asDouble());
            items.add(item);
        }
        return store.page(items, input, "idNamespaceSummaries");
    }

    public synchronized ObjectNode updateIdNamespace(JsonNode input, String region) {
        String name = text(input, "idNamespaceName");
        ObjectNode namespace = idNamespace(region, name);
        applyNamespace(region, namespace, input);
        namespace.put("updatedAt", now());
        store.put(region, ID_NAMESPACE, name, namespace);
        return view(namespace);
    }

    /** Deletes an ID namespace no ID mapping workflow uses; deleting one that does not exist succeeds. */
    public synchronized ObjectNode deleteIdNamespace(JsonNode input, String region) {
        String name = text(input, "idNamespaceName");
        Optional<ObjectNode> namespace = store.get(region, ID_NAMESPACE, name);
        if (namespace.isPresent()) {
            String arn = namespace.get().path("idNamespaceArn").asText();
            for (ObjectNode workflow : store.list(region, ID_MAPPING_WORKFLOW, "", record -> true)) {
                for (JsonNode source : workflow.path("inputSourceConfig")) {
                    if (arn.equals(source.path("inputSourceARN").asText())) {
                        throw conflict("ID namespace " + name + " is used by ID mapping workflow "
                                + workflow.path("workflowName").asText() + ".");
                    }
                }
            }
            store.delete(region, ID_NAMESPACE, name);
        }
        return message("ID namespace " + name + " deleted.");
    }

    /**
     * An ID namespace's inputs, Glue tables with their schema or a matching workflow's output, and how it maps:
     * rules, the sides whose rules apply, and its matching models.
     */
    private void applyNamespace(String region, ObjectNode namespace, JsonNode input) {
        List<Table> tables = new ArrayList<>();
        if (input.has("inputSourceConfig")) {
            for (JsonNode source : input.path("inputSourceConfig")) {
                String arn = text(source, "inputSourceARN");
                if (arn.contains(":entityresolution:") && arn.contains(":matchingworkflow/")) {
                    matchingWorkflow(region, arn.substring(arn.lastIndexOf('/') + 1));
                } else {
                    schemaFor(region, text(source, "schemaName"));
                    tables.add(data.table(arn));
                }
            }
            namespace.set("inputSourceConfig", input.get("inputSourceConfig").deepCopy());
        }
        for (JsonNode properties : input.path("idMappingWorkflowProperties")) {
            String type = properties.path("idMappingType").asText();
            if ("PROVIDER".equals(type)) {
                throw invalid("Provider service " + properties.path("providerProperties").path("providerServiceArn")
                        .asText() + " does not exist.");
            }
        }
        if (input.has("idMappingWorkflowProperties")) {
            namespace.set("idMappingWorkflowProperties", input.get("idMappingWorkflowProperties").deepCopy());
        }
        if (input.has("description")) {
            namespace.put("description", input.path("description").asText());
        }
        String role = optional(input, "roleArn");
        if (role != null) {
            authorizeRole(role, tables, store.mapper().createArrayNode(), region);
            namespace.put("roleArn", role);
        } else if (!tables.isEmpty() && !namespace.has("roleArn")) {
            throw invalid("roleArn is required when an ID namespace reads input sources.");
        }
    }

    ObjectNode idNamespace(String region, String name) {
        return store.get(region, ID_NAMESPACE, name).orElseThrow(() -> notFound("ID namespace " + name
                + " not found."));
    }

    // ---------------------------------------------------------------- ID mapping workflows

    public synchronized ObjectNode createIdMappingWorkflow(JsonNode input, String region) {
        String name = text(input, "workflowName");
        if (store.get(region, ID_MAPPING_WORKFLOW, name).isPresent()) {
            throw conflict("An ID mapping workflow named " + name + " already exists.");
        }
        ObjectNode workflow = store.record();
        workflow.put("workflowName", name).put("workflowArn", store.arn(region, ID_MAPPING_WORKFLOW, name));
        applyIdMapping(region, workflow, input);
        workflow.put("createdAt", now()).put("updatedAt", now());
        workflow.set("tags", tags(input.get("tags")));
        store.put(region, ID_MAPPING_WORKFLOW, name, workflow);
        ObjectNode response = view(workflow);
        response.remove(List.of("createdAt", "updatedAt", "tags"));
        return response;
    }

    public synchronized ObjectNode getIdMappingWorkflow(JsonNode input, String region) {
        return view(idMappingWorkflow(region, text(input, "workflowName")));
    }

    public synchronized ObjectNode listIdMappingWorkflows(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode workflow : store.list(region, ID_MAPPING_WORKFLOW, "", record -> true)) {
            items.add(summary(workflow));
        }
        return store.page(items, input, "workflowSummaries");
    }

    public synchronized ObjectNode updateIdMappingWorkflow(JsonNode input, String region) {
        String name = text(input, "workflowName");
        ObjectNode workflow = idMappingWorkflow(region, name);
        if (jobs.isResolvable() && jobs.get().running(region, EntityResolutionStore.ID_MAPPING_JOB, name)) {
            throw conflict("ID mapping workflow " + name + " has a job running.");
        }
        applyIdMapping(region, workflow, input);
        workflow.put("updatedAt", now());
        store.put(region, ID_MAPPING_WORKFLOW, name, workflow);
        ObjectNode response = view(workflow);
        response.remove(List.of("createdAt", "updatedAt", "tags"));
        return response;
    }

    public synchronized ObjectNode deleteIdMappingWorkflow(JsonNode input, String region) {
        String name = text(input, "workflowName");
        if (store.get(region, ID_MAPPING_WORKFLOW, name).isPresent()) {
            if (jobs.isResolvable() && jobs.get().running(region, EntityResolutionStore.ID_MAPPING_JOB, name)) {
                throw conflict("ID mapping workflow " + name + " has a job running.");
            }
            store.delete(region, ID_MAPPING_WORKFLOW, name);
            if (jobs.isResolvable()) {
                jobs.get().forget(region, EntityResolutionStore.ID_MAPPING_JOB, name);
            }
        }
        return message("ID mapping workflow " + name + " deleted.");
    }

    /**
     * An ID mapping workflow's sources: one SOURCE, and for rule-based mapping one TARGET, each an ID namespace or a
     * Glue table with its schema; the rules come from the request or from the side its rule definition type names.
     */
    private void applyIdMapping(String region, ObjectNode workflow, JsonNode input) {
        int sources = 0;
        int targets = 0;
        List<Table> tables = new ArrayList<>();
        for (JsonNode source : input.path("inputSourceConfig")) {
            String arn = text(source, "inputSourceARN");
            String type;
            if (arn.contains(":idnamespace/")) {
                ObjectNode namespace = idNamespace(region, arn.substring(arn.lastIndexOf('/') + 1));
                type = namespace.path("type").asText();
            } else {
                schemaFor(region, text(source, "schemaName"));
                tables.add(data.table(arn));
                type = source.path("type").asText("SOURCE");
            }
            sources += "SOURCE".equals(type) ? 1 : 0;
            targets += "TARGET".equals(type) ? 1 : 0;
        }
        JsonNode techniques = input.path("idMappingTechniques");
        String mappingType = techniques.path("idMappingType").asText();
        if ("PROVIDER".equals(mappingType)) {
            throw invalid("Provider service " + techniques.path("providerProperties").path("providerServiceArn")
                    .asText() + " does not exist.");
        }
        if (sources != 1 || targets > 1 || ("RULE_BASED".equals(mappingType) && targets != 1)) {
            throw invalid("An ID mapping workflow takes one SOURCE input and, for RULE_BASED mapping, one TARGET.");
        }
        JsonNode outputs = input.path("outputSourceConfig");
        for (JsonNode output : outputs) {
            if (EntityResolutionData.location(text(output, "outputS3Path")) == null) {
                throw invalid("outputS3Path must be an S3 path: " + output.path("outputS3Path").asText());
            }
        }
        String role = optional(input, "roleArn");
        if (role != null) {
            authorizeRole(role, tables, outputs, region);
            workflow.put("roleArn", role);
        }
        if (input.has("description")) {
            workflow.put("description", input.path("description").asText());
        }
        workflow.set("inputSourceConfig", input.get("inputSourceConfig").deepCopy());
        if (input.has("outputSourceConfig")) {
            workflow.set("outputSourceConfig", outputs.deepCopy());
        }
        workflow.set("idMappingTechniques", techniques.deepCopy());
        if (input.has("incrementalRunConfig")) {
            workflow.set("incrementalRunConfig", input.get("incrementalRunConfig").deepCopy());
        }
    }

    ObjectNode idMappingWorkflow(String region, String name) {
        return store.get(region, ID_MAPPING_WORKFLOW, name).orElseThrow(() -> notFound("ID mapping workflow " + name
                + " not found."));
    }

    private ObjectNode summary(ObjectNode workflow) {
        ObjectNode item = store.mapper().createObjectNode();
        item.put("workflowName", workflow.path("workflowName").asText())
                .put("workflowArn", workflow.path("workflowArn").asText())
                .put("createdAt", workflow.path("createdAt").asDouble())
                .put("updatedAt", workflow.path("updatedAt").asDouble());
        return item;
    }

    // ---------------------------------------------------------------- roles

    /**
     * Checks a workflow's role as Entity Resolution uses it: it must exist and trust the service, and, with IAM
     * enforcement on, its policies must allow reading the input tables and their data and writing the outputs.
     */
    private void authorizeRole(String roleArn, List<Table> tables, JsonNode outputs, String region) {
        IamRole role = role(roleArn);
        if (role == null || !TRUST.allowsService(role.getAssumeRolePolicyDocument(),
                ServicePrincipals.of("entityresolution"))) {
            throw denied("Exception in assuming the passed role " + roleArn
                    + ". Make sure the role exists and trusts " + ServicePrincipals.of("entityresolution") + ".");
        }
        if (!config.services().iam().enforcementEnabled()) {
            return;
        }
        CallerContext caller;
        try {
            caller = iam.resolvePrincipalContext(roleArn);
        } catch (AwsException e) {
            throw denied("Exception in assuming the passed role " + roleArn + ".");
        }
        String partition = AwsArnUtils.parse(roleArn).partition();
        for (Table table : tables) {
            String tableArn = AwsArnUtils.Arn.of("glue", region, store.account(), "table/" + table.getDatabaseName()
                    + "/" + table.getName()).toString();
            if (!allows(caller, "glue:GetTable", tableArn)) {
                throw denied("The service does not have access to read your data in Glue. Grant the role "
                        + roleArn + " glue:GetTable on " + tableArn + ".");
            }
            EntityResolutionData.Location location = EntityResolutionData.location(
                    table.getStorageDescriptor() == null ? null : table.getStorageDescriptor().getLocation());
            if (location != null && !allows(caller, "s3:GetObject", location.arn(partition))) {
                throw denied("The service does not have access to read your data in S3. Grant the role " + roleArn
                        + " s3:GetObject on s3://" + location.bucket() + "/" + location.prefix() + ".");
            }
        }
        for (JsonNode output : outputs) {
            EntityResolutionData.Location location = EntityResolutionData.location(
                    output.path("outputS3Path").asText(null));
            if (location != null && !allows(caller, "s3:PutObject", location.arn(partition))) {
                throw denied("The service does not have access to write to S3. Grant the role " + roleArn
                        + " s3:PutObject on s3://" + location.bucket() + "/" + location.prefix() + ".");
            }
        }
    }

    private static boolean allows(CallerContext caller, String action, String resource) {
        return POLICIES.evaluate(caller, List.of(), action, resource, Map.of()) == IamPolicyEvaluator.Decision.ALLOW;
    }

    private IamRole role(String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            String name = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
            return iam.findRole(parsed.accountId(), name).filter(role -> arn.equals(role.getArn())).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ObjectNode schemaFor(String region, String name) {
        return store.get(region, SCHEMA, name).orElseThrow(() -> invalid("Schema mapping " + name
                + " does not exist."));
    }

    // ---------------------------------------------------------------- policies

    public synchronized ObjectNode getPolicy(JsonNode input, String region) {
        String arn = text(input, "arn");
        return policyResponse(arn, policyHolder(region, arn));
    }

    /** Replaces a resource's policy; a token other than the current one is a conflicting update. */
    public synchronized ObjectNode putPolicy(JsonNode input, String region) {
        String arn = text(input, "arn");
        ObjectNode record = policyHolder(region, arn);
        String token = optional(input, "token");
        if (meta(record).has("policyToken") && token != null
                && !token.equals(meta(record).path("policyToken").asText())) {
            throw conflict("The policy token does not match the current policy's.");
        }
        String policy = text(input, "policy");
        try {
            JsonNode document = store.mapper().readTree(policy);
            if (document == null || !document.isObject()) {
                throw invalid("The policy is not a JSON policy document.");
            }
        } catch (JsonProcessingException e) {
            throw invalid("The policy is not valid JSON.");
        }
        meta(record).put("policy", policy).put("policyToken", UUID.randomUUID().toString());
        save(region, arn, record);
        return policyResponse(arn, record);
    }

    public synchronized ObjectNode addPolicyStatement(JsonNode input, String region) {
        String arn = text(input, "arn");
        ObjectNode record = policyHolder(region, arn);
        String sid = text(input, "statementId");
        ObjectNode document = policyDocument(record);
        for (JsonNode statement : document.path("Statement")) {
            if (sid.equals(statement.path("Sid").asText())) {
                throw conflict("A statement with id " + sid + " already exists.");
            }
        }
        ObjectNode statement = document.withArray("/Statement").addObject();
        statement.put("Sid", sid).put("Effect", text(input, "effect"));
        statement.set("Principal", store.mapper().createObjectNode().set("AWS", input.path("principal").deepCopy()));
        statement.set("Action", input.path("action").deepCopy());
        statement.put("Resource", arn);
        String condition = optional(input, "condition");
        if (condition != null) {
            try {
                statement.set("Condition", store.mapper().readTree(condition));
            } catch (JsonProcessingException e) {
                throw invalid("The condition is not valid JSON.");
            }
        }
        meta(record).put("policy", document.toString()).put("policyToken", UUID.randomUUID().toString());
        save(region, arn, record);
        return policyResponse(arn, record);
    }

    public synchronized ObjectNode deletePolicyStatement(JsonNode input, String region) {
        String arn = text(input, "arn");
        ObjectNode record = policyHolder(region, arn);
        String sid = text(input, "statementId");
        ObjectNode document = policyDocument(record);
        ArrayNode statements = document.withArray("/Statement");
        boolean removed = false;
        for (int index = statements.size() - 1; index >= 0; index--) {
            if (sid.equals(statements.get(index).path("Sid").asText())) {
                statements.remove(index);
                removed = true;
            }
        }
        if (!removed) {
            throw notFound("Statement " + sid + " not found in the policy of " + arn + ".");
        }
        meta(record).put("policy", document.toString()).put("policyToken", UUID.randomUUID().toString());
        save(region, arn, record);
        return policyResponse(arn, record);
    }

    private ObjectNode policyDocument(ObjectNode record) {
        String policy = meta(record).path("policy").asText(null);
        if (policy == null) {
            ObjectNode document = store.mapper().createObjectNode();
            document.put("Version", "2012-10-17").putArray("Statement");
            return document;
        }
        try {
            return (ObjectNode) store.mapper().readTree(policy);
        } catch (JsonProcessingException e) {
            throw invalid("The stored policy is not valid JSON.");
        }
    }

    private ObjectNode policyResponse(String arn, ObjectNode record) {
        ObjectNode response = store.mapper().createObjectNode();
        response.put("arn", arn);
        if (!meta(record).has("policyToken")) {
            meta(record).put("policyToken", UUID.randomUUID().toString());
        }
        response.put("token", meta(record).path("policyToken").asText());
        if (meta(record).has("policy")) {
            response.put("policy", meta(record).path("policy").asText());
        }
        return response;
    }

    /** The workflow or ID namespace a policy ARN names. */
    private ObjectNode policyHolder(String region, String arn) {
        ObjectNode record = byArn(region, arn);
        String kind = kindOf(arn);
        if (SCHEMA.equals(kind)) {
            throw invalid("Schema mappings take no resource policy.");
        }
        return record;
    }

    // ---------------------------------------------------------------- tags

    public synchronized ObjectNode listTagsForResource(JsonNode input, String region) {
        ObjectNode record = byArn(region, text(input, "resourceArn"));
        ObjectNode response = store.mapper().createObjectNode();
        response.set("tags", record.path("tags").deepCopy());
        return response;
    }

    public synchronized ObjectNode tagResource(JsonNode input, String region) {
        String arn = text(input, "resourceArn");
        ObjectNode record = byArn(region, arn);
        ObjectNode tags = record.withObject("/tags");
        input.path("tags").fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        if (tags.size() > 200) {
            throw invalid("A resource can have at most 200 tags.");
        }
        save(region, arn, record);
        return store.mapper().createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode input, String region) {
        String arn = text(input, "resourceArn");
        ObjectNode record = byArn(region, arn);
        ObjectNode tags = record.withObject("/tags");
        input.path("tagKeys").forEach(key -> tags.remove(key.asText()));
        save(region, arn, record);
        return store.mapper().createObjectNode();
    }

    private static String kindOf(String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!"entityresolution".equals(parsed.service()) || !parsed.resource().contains("/")) {
                return null;
            }
            return parsed.resource().substring(0, parsed.resource().indexOf('/'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ObjectNode byArn(String region, String arn) {
        String kind = kindOf(arn);
        if (kind == null || !Set.of(SCHEMA, MATCHING_WORKFLOW, ID_MAPPING_WORKFLOW, ID_NAMESPACE).contains(kind)) {
            throw invalid("Invalid resource ARN " + arn + ".");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String name = parsed.resource().substring(kind.length() + 1);
        Optional<ObjectNode> record = region.equals(parsed.region()) && store.account().equals(parsed.accountId())
                ? store.get(region, kind, name) : Optional.empty();
        return record.orElseThrow(() -> notFound("Resource " + arn + " not found."));
    }

    private void save(String region, String arn, ObjectNode record) {
        String kind = kindOf(arn);
        store.put(region, kind, AwsArnUtils.parse(arn).resource().substring(kind.length() + 1), record);
    }

    // ---------------------------------------------------------------- provider services

    /** The third-party provider services an account can use; floci offers none. */
    public ObjectNode listProviderServices(JsonNode input, String region) {
        return store.page(List.of(), input, "providerServiceSummaries");
    }

    public ObjectNode getProviderService(JsonNode input, String region) {
        throw notFound("Provider service " + text(input, "providerName") + "/" + text(input, "providerServiceName")
                + " not found.");
    }

    // ---------------------------------------------------------------- shared

    ObjectNode tags(JsonNode input) {
        ObjectNode tags = store.mapper().createObjectNode();
        if (input != null && input.isObject()) {
            input.fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        }
        return tags;
    }

    private ObjectNode message(String text) {
        ObjectNode response = store.mapper().createObjectNode();
        response.put("message", text);
        return response;
    }
}
