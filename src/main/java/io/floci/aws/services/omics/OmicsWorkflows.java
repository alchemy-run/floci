package io.floci.aws.services.omics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Subnet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * HealthOmics workflows and runs. A workflow (or version) is a WDL, Nextflow or CWL definition from a zip or S3; it's
 * {@code ACTIVE} once its main file is found, with a parameter template read from the main file's inputs. Runs execute
 * on {@link OmicsEngine}, at most {@code maxRuns} at a time and for at most {@code maxDuration} minutes in their run
 * group; batches start many runs from shared settings.
 */
@ApplicationScoped
public class OmicsWorkflows {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Set<String> ACTIVE_RUNS = Set.of("PENDING", "STARTING", "RUNNING", "STOPPING");
    private static final Pattern WDL_WORKFLOW = Pattern.compile("(?s)\\bworkflow\\s+(\\w+)\\s*\\{");
    private static final Pattern WDL_INPUT = Pattern.compile("(?s)\\binput\\s*\\{(.*?)\\n\\s*}");
    private static final Pattern WDL_DECLARATION = Pattern.compile("^\\s*([\\w\\[\\],\\s]+?)(\\?)?\\s+(\\w+)\\s*(=.*)?$");
    private static final Pattern NEXTFLOW_PARAM = Pattern.compile("(?m)^\\s*params\\.(\\w+)\\s*=");

    private final OmicsStore store;
    private final OmicsCommon common;
    private final OmicsEngine engine;
    private final Provider<Ec2Service> ec2;
    private final ObjectMapper mapper;

    @Inject
    public OmicsWorkflows(OmicsStore store, OmicsCommon common, OmicsEngine engine, Provider<Ec2Service> ec2) {
        this.store = store;
        this.common = common;
        this.engine = engine;
        this.ec2 = ec2;
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

    private ObjectNode idempotent(String region, String operation, JsonNode in, String tokenField,
                                  Supplier<ObjectNode> create) {
        String token = in.path(tokenField).asText(null);
        ObjectNode request = in.deepCopy();
        request.remove(tokenField);
        ObjectNode earlier = store.repeated(region, operation, token, request);
        if (earlier != null) {
            return earlier;
        }
        ObjectNode out = create.get();
        store.remember(region, operation, token, request, out);
        return out;
    }

    private static String numeric() {
        return OmicsStore.random(7, "0123456789").replaceFirst("^0", "1");
    }

    // ================================================================ definitions

    /** A workflow definition's files, its main file, engine and parameters, read from its zip. */
    record Definition(Map<String, byte[]> files, String main, String engine, ObjectNode parameters) {
    }

    private Definition definition(JsonNode in, String region) {
        byte[] zip;
        if (in.hasNonNull("definitionZip")) {
            zip = Base64.getDecoder().decode(in.get("definitionZip").asText());
        } else if (in.hasNonNull("definitionUri")) {
            try {
                zip = common.read(in.get("definitionUri").asText());
            } catch (IllegalStateException e) {
                throw OmicsStore.validation(e.getMessage());
            }
        } else if (in.has("definitionRepository")) {
            throw OmicsStore.validation("Floci can't read workflow definitions from a code repository; use "
                    + "definitionZip or definitionUri.");
        } else {
            throw OmicsStore.validation("A workflow needs definitionZip or definitionUri.");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (zip.length > 4 && zip[0] == 'P' && zip[1] == 'K') {
            try (ZipInputStream entries = new ZipInputStream(new ByteArrayInputStream(zip))) {
                ZipEntry entry;
                while ((entry = entries.getNextEntry()) != null) {
                    if (!entry.isDirectory() && !entry.getName().startsWith("__MACOSX/")) {
                        files.put(entry.getName(), entries.readAllBytes());
                    }
                }
            } catch (IOException e) {
                throw OmicsStore.validation("The workflow definition isn't a readable zip: " + e.getMessage());
            }
        } else {
            String name = in.path("main").asText(in.hasNonNull("definitionUri")
                    ? in.get("definitionUri").asText().substring(in.get("definitionUri").asText().lastIndexOf('/') + 1)
                    : "main.wdl");
            files.put(name, zip);
        }
        String main = in.path("main").asText(null);
        if (main == null) {
            for (String candidate : List.of("main.wdl", "main.nf", "main.cwl", "workflow.cwl")) {
                if (files.containsKey(candidate)) {
                    main = candidate;
                }
            }
            if (main == null && files.size() == 1) {
                main = files.keySet().iterator().next();
            }
        }
        if (main == null || !files.containsKey(main)) {
            throw OmicsStore.validation("The workflow definition has no main file" + (main == null ? "" : " " + main)
                    + ".");
        }
        String engineName = in.path("engine").asText(null);
        if (engineName == null) {
            engineName = main.endsWith(".nf") ? "NEXTFLOW" : main.endsWith(".cwl") ? "CWL" : "WDL";
        }
        String text = new String(files.get(main), StandardCharsets.UTF_8);
        ObjectNode parameters = in.path("parameterTemplate").isObject() ? (ObjectNode) in.get("parameterTemplate")
                .deepCopy() : parameters(engineName, text);
        if (engineName.startsWith("WDL") && !WDL_WORKFLOW.matcher(text).find()) {
            throw OmicsStore.validation("The main file " + main + " defines no WDL workflow.");
        }
        return new Definition(files, main, engineName, parameters);
    }

    /** The parameter template a main file's inputs make. */
    private ObjectNode parameters(String engineName, String text) {
        ObjectNode template = empty();
        if (engineName.startsWith("WDL")) {
            Matcher workflow = WDL_WORKFLOW.matcher(text);
            if (workflow.find()) {
                Matcher input = WDL_INPUT.matcher(text.substring(workflow.end()));
                if (input.find()) {
                    for (String line : input.group(1).split("\\n")) {
                        Matcher declaration = WDL_DECLARATION.matcher(line.replaceAll("#.*", ""));
                        if (declaration.matches()) {
                            boolean optional = declaration.group(2) != null || declaration.group(4) != null;
                            template.putObject(declaration.group(3)).put("description", declaration.group(1).trim()
                                    + (declaration.group(2) != null ? "?" : "")).put("optional", optional);
                        }
                    }
                }
            }
        } else if (engineName.equals("NEXTFLOW")) {
            Matcher param = NEXTFLOW_PARAM.matcher(text);
            while (param.find()) {
                template.putObject(param.group(1)).put("description", "params." + param.group(1))
                        .put("optional", true);
            }
        } else {
            try {
                JsonNode document = YAML.readTree(text);
                JsonNode inputs = document.path("inputs");
                if (inputs.isObject()) {
                    inputs.properties().forEach(entry -> template.putObject(entry.getKey()).put("description",
                            entry.getValue().path("doc").asText(entry.getKey())).put("optional",
                            entry.getValue().path("type").asText(entry.getValue().asText()).endsWith("?")
                                    || entry.getValue().has("default")));
                } else if (inputs.isArray()) {
                    inputs.forEach(input -> template.putObject(input.path("id").asText()).put("description",
                            input.path("doc").asText(input.path("id").asText())).put("optional",
                            input.path("type").asText().endsWith("?") || input.has("default")));
                }
            } catch (IOException e) {
                throw OmicsStore.validation("The CWL main file isn't YAML: " + e.getMessage());
            }
        }
        return template;
    }

    private static String digest(Map<String, byte[]> files) {
        StringBuilder all = new StringBuilder();
        files.forEach((name, data) -> all.append(name).append(OmicsFiles.digest("SHA-256", data)));
        return "sha256:" + OmicsFiles.digest("SHA-256", all.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void storeDefinition(String account, String key, Definition definition) {
        ObjectNode files = empty();
        definition.files().forEach((name, data) -> files.put(name, Base64.getEncoder().encodeToString(data)));
        store.putData(account, key, files.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void describe(ObjectNode record, JsonNode in, Definition definition) {
        record.put("engine", definition.engine());
        record.put("main", definition.main());
        record.put("digest", digest(definition.files()));
        record.set("parameterTemplate", definition.parameters());
        for (String field : List.of("description", "storageType", "readmePath", "parameterTemplatePath")) {
            if (in.hasNonNull(field)) {
                record.put(field, in.get(field).asText());
            }
        }
        if (in.hasNonNull("storageCapacity")) {
            record.put("storageCapacity", in.get("storageCapacity").asInt());
        } else if (!"DYNAMIC".equals(record.path("storageType").asText())) {
            record.put("storageCapacity", 1200);
        }
        if (!record.has("storageType")) {
            record.put("storageType", "DYNAMIC");
        }
        if (in.path("accelerators").isTextual()) {
            record.put("accelerators", in.get("accelerators").asText());
        }
        if (in.path("containerRegistryMap").isObject()) {
            record.set("containerRegistryMap", in.get("containerRegistryMap").deepCopy());
        }
        ObjectNode metadata = record.putObject("metadata");
        metadata.put("files", String.valueOf(definition.files().size()));
        record.put("creationTime", OmicsStore.timestamp());
    }

    private void activate(String account, String region, String kind, String key) {
        common.later(account, region, () -> store.get(region, kind, key).ifPresent(record -> {
            if ("CREATING".equals(record.path("status").asText()) || "UPDATING".equals(record.path("status")
                    .asText())) {
                record.put("status", "ACTIVE");
                store.put(region, kind, key, record);
            }
        }));
    }

    // ================================================================ workflows

    ObjectNode workflow(String region, String id) {
        return store.get(region, OmicsStore.WORKFLOW, id).filter(workflow -> !"DELETED".equals(workflow.path("status")
                .asText())).orElseThrow(() -> OmicsStore.notFound("Workflow " + id + " doesn't exist."));
    }

    public ObjectNode createWorkflow(JsonNode in, String region) {
        return idempotent(region, "CreateWorkflow", in, "requestId", () -> {
            String account = store.account();
            Definition definition = definition(in, region);
            String id = numeric();
            ObjectNode record = store.record();
            record.put("arn", common.arn(region, account, "workflow/" + id));
            record.put("id", id);
            record.put("status", "CREATING");
            record.put("type", "PRIVATE");
            if (in.hasNonNull("name")) {
                record.put("name", in.get("name").asText());
            }
            describe(record, in, definition);
            record.put("uuid", UUID.randomUUID().toString());
            store.put(region, OmicsStore.WORKFLOW, id, record);
            storeDefinition(account, "workflow/" + id, definition);
            common.tagged(account, record.path("arn").asText(), in.path("tags"));
            activate(account, region, OmicsStore.WORKFLOW, id);
            ObjectNode out = empty();
            for (String field : List.of("arn", "id", "status", "uuid")) {
                out.set(field, record.get(field));
            }
            out.set("tags", common.tags(account, record.path("arn").asText()));
            return out;
        });
    }

    private ObjectNode view(ObjectNode record) {
        ObjectNode view = OmicsStore.view(record);
        view.set("tags", common.tags(store.account(), record.path("arn").asText()));
        return view;
    }

    public ObjectNode getWorkflow(JsonNode in, String region) {
        if ("READY2RUN".equals(in.path("type").asText())) {
            throw OmicsStore.notFound("Floci has no Ready2Run workflows.");
        }
        return view(workflow(region, required(in, "id")));
    }

    public ObjectNode listWorkflows(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        if (!"READY2RUN".equals(in.path("type").asText())) {
            for (ObjectNode record : store.list(region, OmicsStore.WORKFLOW)) {
                if (!"DELETED".equals(record.path("status").asText()) && (!in.hasNonNull("name")
                        || in.get("name").asText().equals(record.path("name").asText()))) {
                    ObjectNode item = empty();
                    for (String field : List.of("arn", "id", "name", "status", "type", "digest", "creationTime",
                            "metadata")) {
                        if (record.has(field)) {
                            item.set(field, record.get(field));
                        }
                    }
                    items.add(item);
                }
            }
        }
        return page(items, in, "items");
    }

    public ObjectNode updateWorkflow(JsonNode in, String region) {
        ObjectNode record = workflow(region, required(in, "id"));
        for (String field : List.of("name", "description", "storageType", "readmeMarkdown")) {
            if (in.hasNonNull(field)) {
                record.put(field, in.get(field).asText());
            }
        }
        if (in.hasNonNull("storageCapacity")) {
            record.put("storageCapacity", in.get("storageCapacity").asInt());
        }
        store.put(region, OmicsStore.WORKFLOW, record.path("id").asText(), record);
        return empty();
    }

    public ObjectNode deleteWorkflow(JsonNode in, String region) {
        ObjectNode record = workflow(region, required(in, "id"));
        String id = record.path("id").asText();
        for (ObjectNode run : store.list(region, OmicsStore.RUN)) {
            if (id.equals(run.path("workflowId").asText()) && ACTIVE_RUNS.contains(run.path("status").asText())) {
                throw OmicsStore.conflict("Workflow " + id + " has runs in progress.");
            }
        }
        String account = store.account();
        store.delete(region, OmicsStore.WORKFLOW, id);
        store.deleteData(account, "workflow/" + id);
        for (ObjectNode version : store.list(region, OmicsStore.WORKFLOW_VERSION)) {
            if (id.equals(version.path("workflowId").asText())) {
                store.delete(region, OmicsStore.WORKFLOW_VERSION, id + "/" + version.path("versionName").asText());
                store.deleteData(account, "workflow/" + id + "/" + version.path("versionName").asText());
                common.untagged(account, version.path("arn").asText());
            }
        }
        common.untagged(account, record.path("arn").asText());
        return empty();
    }

    // ================================================================ workflow versions

    private ObjectNode version(String region, String workflowId, String versionName) {
        workflow(region, workflowId);
        return store.get(region, OmicsStore.WORKFLOW_VERSION, workflowId + "/" + versionName).orElseThrow(() ->
                OmicsStore.notFound("Version " + versionName + " of workflow " + workflowId + " doesn't exist."));
    }

    public ObjectNode createWorkflowVersion(JsonNode in, String region) {
        String workflowId = required(in, "workflowId");
        ObjectNode workflow = workflow(region, workflowId);
        String versionName = required(in, "versionName");
        return idempotent(region, "CreateWorkflowVersion", in, "requestId", () -> {
            if (store.get(region, OmicsStore.WORKFLOW_VERSION, workflowId + "/" + versionName).isPresent()) {
                throw OmicsStore.conflict("Version " + versionName + " already exists.");
            }
            String account = store.account();
            Definition definition = definition(in, region);
            ObjectNode record = store.record();
            record.put("arn", workflow.path("arn").asText() + "/version/" + versionName);
            record.put("workflowId", workflowId);
            record.put("versionName", versionName);
            record.put("status", "CREATING");
            record.put("type", "PRIVATE");
            describe(record, in, definition);
            record.put("uuid", UUID.randomUUID().toString());
            store.put(region, OmicsStore.WORKFLOW_VERSION, workflowId + "/" + versionName, record);
            storeDefinition(account, "workflow/" + workflowId + "/" + versionName, definition);
            common.tagged(account, record.path("arn").asText(), in.path("tags"));
            activate(account, region, OmicsStore.WORKFLOW_VERSION, workflowId + "/" + versionName);
            ObjectNode out = empty();
            for (String field : List.of("arn", "workflowId", "versionName", "status", "uuid")) {
                out.set(field, record.get(field));
            }
            out.set("tags", common.tags(account, record.path("arn").asText()));
            return out;
        });
    }

    public ObjectNode getWorkflowVersion(JsonNode in, String region) {
        return view(version(region, required(in, "workflowId"), required(in, "versionName")));
    }

    public ObjectNode updateWorkflowVersion(JsonNode in, String region) {
        ObjectNode record = version(region, required(in, "workflowId"), required(in, "versionName"));
        for (String field : List.of("description", "storageType", "readmeMarkdown")) {
            if (in.hasNonNull(field)) {
                record.put(field, in.get(field).asText());
            }
        }
        if (in.hasNonNull("storageCapacity")) {
            record.put("storageCapacity", in.get("storageCapacity").asInt());
        }
        store.put(region, OmicsStore.WORKFLOW_VERSION, record.path("workflowId").asText() + "/"
                + record.path("versionName").asText(), record);
        return empty();
    }

    public ObjectNode deleteWorkflowVersion(JsonNode in, String region) {
        ObjectNode record = version(region, required(in, "workflowId"), required(in, "versionName"));
        String key = record.path("workflowId").asText() + "/" + record.path("versionName").asText();
        store.delete(region, OmicsStore.WORKFLOW_VERSION, key);
        store.deleteData(store.account(), "workflow/" + key);
        common.untagged(store.account(), record.path("arn").asText());
        return empty();
    }

    public ObjectNode listWorkflowVersions(JsonNode in, String region) {
        String workflowId = required(in, "workflowId");
        workflow(region, workflowId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, OmicsStore.WORKFLOW_VERSION)) {
            if (workflowId.equals(record.path("workflowId").asText())) {
                ObjectNode item = empty();
                for (String field : List.of("arn", "workflowId", "versionName", "description", "status", "type",
                        "digest", "creationTime", "metadata")) {
                    if (record.has(field)) {
                        item.set(field, record.get(field));
                    }
                }
                items.add(item);
            }
        }
        return page(items, in, "items");
    }

    // ================================================================ run groups, caches, configurations

    private ObjectNode runGroup(String region, String id) {
        return store.get(region, OmicsStore.RUN_GROUP, id).orElseThrow(() ->
                OmicsStore.notFound("Run group " + id + " doesn't exist."));
    }

    private static void limits(JsonNode in, ObjectNode record) {
        for (String field : List.of("maxCpus", "maxRuns", "maxDuration", "maxGpus")) {
            if (in.hasNonNull(field)) {
                if (in.get(field).asInt() < 1) {
                    throw OmicsStore.validation(field + " must be at least 1.");
                }
                record.put(field, in.get(field).asInt());
            }
        }
    }

    public ObjectNode createRunGroup(JsonNode in, String region) {
        return idempotent(region, "CreateRunGroup", in, "requestId", () -> {
            String account = store.account();
            String id = numeric();
            ObjectNode record = store.record();
            record.put("arn", common.arn(region, account, "runGroup/" + id));
            record.put("id", id);
            if (in.hasNonNull("name")) {
                record.put("name", in.get("name").asText());
            }
            limits(in, record);
            record.put("creationTime", OmicsStore.timestamp());
            store.put(region, OmicsStore.RUN_GROUP, id, record);
            common.tagged(account, record.path("arn").asText(), in.path("tags"));
            ObjectNode out = empty();
            out.put("arn", record.path("arn").asText());
            out.put("id", id);
            out.set("tags", common.tags(account, record.path("arn").asText()));
            return out;
        });
    }

    public ObjectNode getRunGroup(JsonNode in, String region) {
        return view(runGroup(region, required(in, "id")));
    }

    public ObjectNode updateRunGroup(JsonNode in, String region) {
        ObjectNode record = runGroup(region, required(in, "id"));
        if (in.hasNonNull("name")) {
            record.put("name", in.get("name").asText());
        }
        limits(in, record);
        store.put(region, OmicsStore.RUN_GROUP, record.path("id").asText(), record);
        return empty();
    }

    public ObjectNode deleteRunGroup(JsonNode in, String region) {
        ObjectNode record = runGroup(region, required(in, "id"));
        String id = record.path("id").asText();
        for (ObjectNode run : store.list(region, OmicsStore.RUN)) {
            if (id.equals(run.path("runGroupId").asText()) && ACTIVE_RUNS.contains(run.path("status").asText())) {
                throw OmicsStore.conflict("Run group " + id + " has runs in progress.");
            }
        }
        store.delete(region, OmicsStore.RUN_GROUP, id);
        common.untagged(store.account(), record.path("arn").asText());
        return empty();
    }

    public ObjectNode listRunGroups(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, OmicsStore.RUN_GROUP)) {
            if (!in.hasNonNull("name") || in.get("name").asText().equals(record.path("name").asText())) {
                items.add(OmicsStore.view(record));
            }
        }
        return page(items, in, "items");
    }

    private ObjectNode runCache(String region, String id) {
        return store.get(region, OmicsStore.RUN_CACHE, id).orElseThrow(() ->
                OmicsStore.notFound("Run cache " + id + " doesn't exist."));
    }

    public ObjectNode createRunCache(JsonNode in, String region) {
        return idempotent(region, "CreateRunCache", in, "requestId", () -> {
            String account = store.account();
            String location = required(in, "cacheS3Location");
            OmicsCommon.s3Uri(location);
            String id = numeric();
            ObjectNode record = store.record();
            record.put("arn", common.arn(region, account, "runCache/" + id));
            record.put("id", id);
            record.put("cacheBehavior", in.path("cacheBehavior").asText("CACHE_ON_FAILURE"));
            record.put("cacheBucketOwnerId", in.path("cacheBucketOwnerId").asText(account));
            record.put("cacheS3Uri", location);
            record.put("creationTime", OmicsStore.timestamp());
            for (String field : List.of("description", "name")) {
                if (in.hasNonNull(field)) {
                    record.put(field, in.get(field).asText());
                }
            }
            record.put("status", "ACTIVE");
            store.put(region, OmicsStore.RUN_CACHE, id, record);
            common.tagged(account, record.path("arn").asText(), in.path("tags"));
            ObjectNode out = empty();
            for (String field : List.of("arn", "id", "status")) {
                out.set(field, record.get(field));
            }
            out.set("tags", common.tags(account, record.path("arn").asText()));
            return out;
        });
    }

    public ObjectNode getRunCache(JsonNode in, String region) {
        return view(runCache(region, required(in, "id")));
    }

    public ObjectNode updateRunCache(JsonNode in, String region) {
        ObjectNode record = runCache(region, required(in, "id"));
        for (String field : List.of("cacheBehavior", "description", "name")) {
            if (in.hasNonNull(field)) {
                record.put(field, in.get(field).asText());
            }
        }
        store.put(region, OmicsStore.RUN_CACHE, record.path("id").asText(), record);
        return empty();
    }

    public ObjectNode deleteRunCache(JsonNode in, String region) {
        ObjectNode record = runCache(region, required(in, "id"));
        store.delete(region, OmicsStore.RUN_CACHE, record.path("id").asText());
        common.untagged(store.account(), record.path("arn").asText());
        return empty();
    }

    public ObjectNode listRunCaches(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, OmicsStore.RUN_CACHE)) {
            ObjectNode item = OmicsStore.view(record);
            item.remove(List.of("description", "cacheBucketOwnerId"));
            items.add(item);
        }
        return page(items, in, "items");
    }

    private ObjectNode configuration(String region, String name) {
        return store.get(region, OmicsStore.CONFIGURATION, name).orElseThrow(() ->
                OmicsStore.notFound("Configuration " + name + " doesn't exist."));
    }

    public ObjectNode createConfiguration(JsonNode in, String region) {
        return idempotent(region, "CreateConfiguration", in, "requestId", () -> {
            String name = required(in, "name");
            if (store.get(region, OmicsStore.CONFIGURATION, name).isPresent()) {
                throw OmicsStore.conflict("Configuration " + name + " already exists.");
            }
            String account = store.account();
            JsonNode vpc = in.path("runConfigurations").path("vpcConfig");
            ObjectNode record = store.record();
            record.put("arn", common.arn(region, account, "configuration/" + name));
            record.put("uuid", UUID.randomUUID().toString());
            record.put("name", name);
            if (in.hasNonNull("description")) {
                record.put("description", in.get("description").asText());
            }
            ObjectNode response = record.putObject("runConfigurations").putObject("vpcConfig");
            if (vpc.isObject()) {
                String vpcId = null;
                ArrayNode subnets = response.putArray("subnetIds");
                for (JsonNode subnetId : vpc.path("subnetIds")) {
                    try {
                        Subnet subnet = ec2.get().requireSubnet(region, subnetId.asText());
                        if (vpcId != null && !vpcId.equals(subnet.getVpcId())) {
                            throw OmicsStore.validation("The subnets must be in one VPC.");
                        }
                        vpcId = subnet.getVpcId();
                    } catch (AwsException e) {
                        if ("ValidationException".equals(e.getErrorCode())) {
                            throw e;
                        }
                        throw OmicsStore.validation("The subnet " + subnetId.asText() + " doesn't exist.");
                    }
                    subnets.add(subnetId.asText());
                }
                response.set("securityGroupIds", vpc.path("securityGroupIds").deepCopy());
                if (vpcId != null) {
                    response.put("vpcId", vpcId);
                }
                response.put("vpcOwnerAccountId", account);
            }
            record.put("status", "CREATING");
            record.put("creationTime", OmicsStore.timestamp());
            store.put(region, OmicsStore.CONFIGURATION, name, record);
            common.tagged(account, record.path("arn").asText(), in.path("tags"));
            activate(account, region, OmicsStore.CONFIGURATION, name);
            return view(record);
        });
    }

    public ObjectNode getConfiguration(JsonNode in, String region) {
        return view(configuration(region, required(in, "name")));
    }

    public ObjectNode deleteConfiguration(JsonNode in, String region) {
        ObjectNode record = configuration(region, required(in, "name"));
        record.put("status", "DELETING");
        String name = record.path("name").asText();
        store.put(region, OmicsStore.CONFIGURATION, name, record);
        String account = store.account();
        common.later(account, region, () -> {
            store.delete(region, OmicsStore.CONFIGURATION, name);
            common.untagged(account, record.path("arn").asText());
        });
        return empty();
    }

    public ObjectNode listConfigurations(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, OmicsStore.CONFIGURATION)) {
            ObjectNode item = empty();
            for (String field : List.of("arn", "name", "description", "status", "creationTime")) {
                if (record.has(field)) {
                    item.set(field, record.get(field));
                }
            }
            items.add(item);
        }
        return page(items, in, "items");
    }

    // ================================================================ runs

    ObjectNode run(String region, String id) {
        return store.get(region, OmicsStore.RUN, id).filter(run -> !"DELETED".equals(run.path("status").asText()))
                .orElseThrow(() -> OmicsStore.notFound("Run " + id + " doesn't exist."));
    }

    /** Creates a run from its settings, queues it on the engine, and returns it. */
    ObjectNode createRun(JsonNode in, String region, String batchId) {
        String account = store.account();
        String workflowId = in.path("workflowId").asText("");
        String rerun = in.path("runId").asText("");
        if (workflowId.isBlank() && !rerun.isBlank()) {
            workflowId = run(region, rerun).path("workflowId").asText();
        }
        if (workflowId.isBlank()) {
            throw OmicsStore.validation("workflowId is required.");
        }
        if ("READY2RUN".equals(in.path("workflowType").asText())) {
            throw OmicsStore.notFound("Floci has no Ready2Run workflows.");
        }
        ObjectNode workflow = workflow(region, workflowId);
        if (!"ACTIVE".equals(workflow.path("status").asText())) {
            throw OmicsStore.conflict("Workflow " + workflowId + " isn't ACTIVE.");
        }
        ObjectNode definition = workflow;
        String versionName = in.path("workflowVersionName").asText(null);
        String filesKey = "workflow/" + workflowId;
        if (versionName != null) {
            definition = version(region, workflowId, versionName);
            filesKey += "/" + versionName;
        }
        String role = required(in, "roleArn");
        common.role(role, account, region);
        String output = required(in, "outputUri");
        OmicsCommon.s3Uri(output);
        if (in.hasNonNull("runGroupId")) {
            runGroup(region, in.get("runGroupId").asText());
        }
        if (in.hasNonNull("cacheId")) {
            runCache(region, in.get("cacheId").asText());
        }
        ObjectNode configuration = in.hasNonNull("configurationName") ? configuration(region,
                in.get("configurationName").asText()) : null;
        JsonNode parameters = in.path("parameters").isObject() ? in.get("parameters") : empty();
        definition.path("parameterTemplate").properties().forEach(entry -> {
            if (!entry.getValue().path("optional").asBoolean(true) && !parameters.has(entry.getKey())) {
                throw OmicsStore.validation("The run is missing the required parameter " + entry.getKey() + ".");
            }
        });
        String id = numeric();
        ObjectNode run = store.record();
        String uuid = UUID.randomUUID().toString();
        run.put("arn", common.arn(region, account, "run/" + id));
        run.put("id", id);
        run.put("uuid", uuid);
        run.put("status", "PENDING");
        run.put("workflowId", workflowId);
        run.put("workflowType", "PRIVATE");
        run.put("workflowUuid", definition.path("uuid").asText());
        if (versionName != null) {
            run.put("workflowVersionName", versionName);
        }
        run.put("workflowName", workflow.path("name").asText(""));
        run.put("engineVersion", OmicsEngine.version(definition.path("engine").asText()));
        run.put("roleArn", role);
        for (String field : List.of("name", "runGroupId", "cacheId", "cacheBehavior")) {
            if (in.hasNonNull(field)) {
                run.put(field, in.get(field).asText());
            }
        }
        if (!rerun.isBlank()) {
            run.put("runId", rerun);
        }
        if (batchId != null) {
            run.put("batchId", batchId);
        }
        run.put("priority", in.path("priority").asInt(0));
        run.put("digest", definition.path("digest").asText());
        run.set("parameters", parameters.deepCopy());
        run.put("storageType", in.path("storageType").asText(definition.path("storageType").asText("DYNAMIC")));
        if (in.hasNonNull("storageCapacity")) {
            run.put("storageCapacity", in.get("storageCapacity").asInt());
        }
        run.put("outputUri", output);
        run.put("runOutputUri", output.replaceAll("/+$", "") + "/" + id);
        run.put("logLevel", in.path("logLevel").asText("ALL"));
        run.put("retentionMode", in.path("retentionMode").asText("RETAIN"));
        run.put("networkingMode", in.path("networkingMode").asText(configuration == null ? "RESTRICTED" : "VPC"));
        run.put("scratchStorageMode", in.path("scratchStorageMode").asText("LOCAL"));
        run.put("startedBy", common.arn(region, account, "user"));
        run.put("creationTime", OmicsStore.timestamp());
        ObjectNode logs = run.putObject("logLocation");
        logs.put("engineLogStream", "run/" + id + "/engine");
        logs.put("runLogStream", "run/" + id);
        if (configuration != null) {
            run.putObject("configuration").put("name", configuration.path("name").asText())
                    .put("arn", configuration.path("arn").asText()).put("uuid", configuration.path("uuid").asText());
            run.set("vpcConfig", configuration.path("runConfigurations").path("vpcConfig").deepCopy());
        }
        OmicsStore.meta(run).put("engine", definition.path("engine").asText());
        OmicsStore.meta(run).put("main", definition.path("main").asText());
        OmicsStore.meta(run).put("filesKey", filesKey);
        store.put(region, OmicsStore.RUN, id, run);
        JsonNode tags = in.path(batchId == null ? "tags" : "runTags");
        common.tagged(account, run.path("arn").asText(), tags);
        engine.submit(account, region, id);
        return run;
    }

    public ObjectNode startRun(JsonNode in, String region) {
        return idempotent(region, "StartRun", in, "requestId", () -> {
            ObjectNode run = createRun(in, region, null);
            ObjectNode out = empty();
            for (String field : List.of("arn", "id", "status", "uuid", "runOutputUri", "configuration",
                    "networkingMode")) {
                if (run.has(field)) {
                    out.set(field, run.get(field));
                }
            }
            out.set("tags", common.tags(store.account(), run.path("arn").asText()));
            return out;
        });
    }

    public ObjectNode getRun(JsonNode in, String region) {
        ObjectNode view = view(run(region, required(in, "id")));
        view.remove("workflowName");
        return view;
    }

    public ObjectNode listRuns(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode run : store.list(region, OmicsStore.RUN)) {
            if ("DELETED".equals(run.path("status").asText())) {
                continue;
            }
            boolean matches = true;
            for (String field : List.of("name", "runGroupId", "batchId", "status")) {
                matches &= !in.hasNonNull(field) || in.get(field).asText().equals(run.path(field).asText());
            }
            if (matches) {
                ObjectNode item = empty();
                for (String field : List.of("arn", "id", "status", "workflowId", "batchId", "name", "priority",
                        "storageCapacity", "creationTime", "startTime", "stopTime", "storageType",
                        "workflowVersionName", "workflowName")) {
                    if (run.has(field)) {
                        item.set(field, run.get(field));
                    }
                }
                items.add(item);
            }
        }
        return page(items, in, "items");
    }

    public ObjectNode cancelRun(JsonNode in, String region) {
        ObjectNode run = run(region, required(in, "id"));
        if (!ACTIVE_RUNS.contains(run.path("status").asText())) {
            throw OmicsStore.conflict("Run " + run.path("id").asText() + " is " + run.path("status").asText()
                    + " and can't be cancelled.");
        }
        engine.cancel(store.account(), region, run.path("id").asText());
        return empty();
    }

    public ObjectNode deleteRun(JsonNode in, String region) {
        ObjectNode run = run(region, required(in, "id"));
        if (ACTIVE_RUNS.contains(run.path("status").asText())) {
            throw OmicsStore.conflict("Run " + run.path("id").asText() + " is still " + run.path("status").asText()
                    + ".");
        }
        run.put("status", "DELETED");
        store.put(region, OmicsStore.RUN, run.path("id").asText(), run);
        common.untagged(store.account(), run.path("arn").asText());
        return empty();
    }

    public ObjectNode listRunTasks(JsonNode in, String region) {
        ObjectNode run = run(region, required(in, "id"));
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode task : OmicsStore.meta(run).path("tasks")) {
            if (!in.hasNonNull("status") || in.get("status").asText().equals(task.path("status").asText())) {
                ObjectNode item = (ObjectNode) task.deepCopy();
                item.remove(List.of("statusMessage", "logStream", "failureReason", "imageDetails"));
                items.add(item);
            }
        }
        return page(items, in, "items");
    }

    public ObjectNode getRunTask(JsonNode in, String region) {
        ObjectNode run = run(region, required(in, "id"));
        String taskId = required(in, "taskId");
        for (JsonNode task : OmicsStore.meta(run).path("tasks")) {
            if (taskId.equals(task.path("taskId").asText())) {
                return (ObjectNode) task.deepCopy();
            }
        }
        throw OmicsStore.notFound("Task " + taskId + " of run " + run.path("id").asText() + " doesn't exist.");
    }

    // ================================================================ batches

    private ObjectNode batch(String region, String id) {
        return store.get(region, OmicsStore.BATCH, id).orElseThrow(() ->
                OmicsStore.notFound("Batch " + id + " doesn't exist."));
    }

    public ObjectNode startRunBatch(JsonNode in, String region) {
        return idempotent(region, "StartRunBatch", in, "requestId", () -> {
            String account = store.account();
            JsonNode defaults = in.path("defaultRunSetting");
            workflow(region, required(defaults, "workflowId"));
            List<JsonNode> settings = new ArrayList<>();
            in.path("batchRunSettings").path("inlineSettings").forEach(settings::add);
            String settingsUri = in.path("batchRunSettings").path("s3UriSettings").asText(null);
            if (settingsUri != null) {
                try {
                    mapper.readTree(common.read(settingsUri)).forEach(settings::add);
                } catch (IOException | IllegalStateException e) {
                    throw OmicsStore.validation("The batch run settings at " + settingsUri + " can't be read: "
                            + e.getMessage());
                }
            }
            if (settings.isEmpty()) {
                throw OmicsStore.validation("The batch has no run settings.");
            }
            String id = numeric();
            ObjectNode batch = store.record();
            batch.put("id", id);
            batch.put("arn", common.arn(region, account, "runBatch/" + id));
            batch.put("uuid", UUID.randomUUID().toString());
            if (in.hasNonNull("batchName")) {
                batch.put("name", in.get("batchName").asText());
            }
            batch.put("status", "INPROGRESS");
            batch.put("totalRuns", settings.size());
            batch.set("defaultRunSetting", defaults.deepCopy());
            String now = OmicsStore.timestamp();
            batch.put("creationTime", now);
            batch.put("submittedTime", now);
            batch.put("workflowId", defaults.path("workflowId").asText());
            ArrayNode runs = OmicsStore.meta(batch).putArray("runs");
            int ok = 0;
            int failed = 0;
            for (JsonNode setting : settings) {
                ObjectNode merged = defaults.deepCopy();
                setting.properties().forEach(entry -> {
                    if ("parameters".equals(entry.getKey()) && merged.path("parameters").isObject()) {
                        ((ObjectNode) merged.get("parameters")).setAll((ObjectNode) entry.getValue());
                    } else {
                        merged.set(entry.getKey(), entry.getValue().deepCopy());
                    }
                });
                ObjectNode entry = runs.addObject();
                entry.put("runSettingId", setting.path("runSettingId").asText());
                try {
                    ObjectNode run = createRun(merged, region, id);
                    entry.put("runId", run.path("id").asText());
                    entry.put("runInternalUuid", run.path("uuid").asText());
                    entry.put("runArn", run.path("arn").asText());
                    entry.put("submissionStatus", "SUCCESS");
                    ok++;
                } catch (AwsException e) {
                    entry.put("submissionStatus", "FAILED");
                    entry.put("submissionFailureReason", e.getErrorCode());
                    entry.put("submissionFailureMessage", e.getMessage());
                    failed++;
                }
            }
            ObjectNode summary = batch.putObject("submissionSummary");
            summary.put("successfulStartSubmissionCount", ok);
            summary.put("failedStartSubmissionCount", failed);
            summary.put("pendingStartSubmissionCount", 0);
            store.put(region, OmicsStore.BATCH, id, batch);
            common.tagged(account, batch.path("arn").asText(), in.path("tags"));
            ObjectNode out = empty();
            for (String field : List.of("id", "arn", "status", "uuid")) {
                out.set(field, batch.get(field));
            }
            out.set("tags", common.tags(account, batch.path("arn").asText()));
            return out;
        });
    }

    /** A batch with its run summary, settled once every run has finished. */
    private ObjectNode summarized(String region, ObjectNode batch) {
        ObjectNode summary = batch.putObject("runSummary");
        Map<String, String> counts = Map.of("PENDING", "pendingRunCount", "STARTING", "startingRunCount",
                "RUNNING", "runningRunCount", "STOPPING", "stoppingRunCount", "COMPLETED", "completedRunCount",
                "DELETED", "deletedRunCount", "FAILED", "failedRunCount", "CANCELLED", "cancelledRunCount");
        counts.values().forEach(field -> summary.put(field, 0));
        boolean active = false;
        for (JsonNode entry : OmicsStore.meta(batch).path("runs")) {
            ObjectNode run = store.get(region, OmicsStore.RUN, entry.path("runId").asText("")).orElse(null);
            if (run == null) {
                continue;
            }
            String field = counts.get(run.path("status").asText());
            if (field != null) {
                summary.put(field, summary.path(field).asInt() + 1);
            }
            active |= ACTIVE_RUNS.contains(run.path("status").asText());
        }
        if ("INPROGRESS".equals(batch.path("status").asText()) && !active) {
            batch.put("status", "PROCESSED");
            batch.put("processedTime", OmicsStore.timestamp());
            store.put(region, OmicsStore.BATCH, batch.path("id").asText(), batch);
        }
        return batch;
    }

    public ObjectNode getBatch(JsonNode in, String region) {
        ObjectNode batch = summarized(region, batch(region, required(in, "batchId")));
        ObjectNode view = view(batch);
        view.remove("workflowId");
        return view;
    }

    public ObjectNode listBatch(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode batch : store.list(region, OmicsStore.BATCH)) {
            summarized(region, batch);
            boolean matches = (!in.hasNonNull("status") || in.get("status").asText().equals(batch.path("status")
                    .asText())) && (!in.hasNonNull("name") || in.get("name").asText().equals(batch.path("name")
                    .asText())) && (!in.hasNonNull("runGroupId") || in.get("runGroupId").asText()
                    .equals(batch.path("defaultRunSetting").path("runGroupId").asText()));
            if (matches) {
                ObjectNode item = empty();
                item.put("id", batch.path("id").asText());
                if (batch.has("name")) {
                    item.put("name", batch.path("name").asText());
                }
                item.put("status", batch.path("status").asText());
                item.put("createdAt", batch.path("creationTime").asText());
                item.put("totalRuns", batch.path("totalRuns").asInt());
                item.put("workflowId", batch.path("workflowId").asText());
                items.add(item);
            }
        }
        return page(items, in, "items");
    }

    public ObjectNode listRunsInBatch(JsonNode in, String region) {
        ObjectNode batch = batch(region, required(in, "batchId"));
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode entry : OmicsStore.meta(batch).path("runs")) {
            boolean matches = true;
            for (String field : List.of("submissionStatus", "runSettingId", "runId")) {
                matches &= !in.hasNonNull(field) || in.get(field).asText().equals(entry.path(field).asText());
            }
            if (matches) {
                items.add((ObjectNode) entry.deepCopy());
            }
        }
        return page(items, in, "runs");
    }

    public ObjectNode cancelRunBatch(JsonNode in, String region) {
        ObjectNode batch = summarized(region, batch(region, required(in, "batchId")));
        if (!"INPROGRESS".equals(batch.path("status").asText())) {
            throw OmicsStore.conflict("Batch " + batch.path("id").asText() + " is " + batch.path("status").asText()
                    + ".");
        }
        int cancelled = 0;
        for (JsonNode entry : OmicsStore.meta(batch).path("runs")) {
            ObjectNode run = store.get(region, OmicsStore.RUN, entry.path("runId").asText("")).orElse(null);
            if (run != null && ACTIVE_RUNS.contains(run.path("status").asText())) {
                engine.cancel(store.account(), region, run.path("id").asText());
                cancelled++;
            }
        }
        batch.put("status", "CANCELLED");
        ((ObjectNode) batch.path("submissionSummary")).put("successfulCancelSubmissionCount", cancelled);
        store.put(region, OmicsStore.BATCH, batch.path("id").asText(), batch);
        return empty();
    }

    public ObjectNode deleteRunBatch(JsonNode in, String region) {
        ObjectNode batch = summarized(region, batch(region, required(in, "batchId")));
        if ("INPROGRESS".equals(batch.path("status").asText())) {
            throw OmicsStore.conflict("Batch " + batch.path("id").asText() + " still has runs in progress.");
        }
        int deleted = 0;
        for (JsonNode entry : OmicsStore.meta(batch).path("runs")) {
            ObjectNode run = store.get(region, OmicsStore.RUN, entry.path("runId").asText("")).orElse(null);
            if (run != null && !"DELETED".equals(run.path("status").asText())) {
                run.put("status", "DELETED");
                store.put(region, OmicsStore.RUN, run.path("id").asText(), run);
                deleted++;
            }
        }
        batch.put("status", "RUNS_DELETED");
        ((ObjectNode) batch.path("submissionSummary")).put("successfulDeleteSubmissionCount", deleted);
        store.put(region, OmicsStore.BATCH, batch.path("id").asText(), batch);
        return empty();
    }

    public ObjectNode deleteBatch(JsonNode in, String region) {
        ObjectNode batch = summarized(region, batch(region, required(in, "batchId")));
        if ("INPROGRESS".equals(batch.path("status").asText())) {
            throw OmicsStore.conflict("Batch " + batch.path("id").asText() + " still has runs in progress.");
        }
        store.delete(region, OmicsStore.BATCH, batch.path("id").asText());
        common.untagged(store.account(), batch.path("arn").asText());
        return empty();
    }
}
