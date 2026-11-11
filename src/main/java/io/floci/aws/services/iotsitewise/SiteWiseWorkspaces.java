package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.command.InspectContainerResponse;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.s3.S3Service;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.floci.aws.services.iotsitewise.SiteWiseStore.DATASET;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.WORKSPACE;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.invalid;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.meta;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.notFound;

/**
 * SiteWise workspaces and what lives in them: applications on IAM Identity Center; container tasks and the pipelines
 * that run them, each compute node a Docker container of its task's image run once the nodes it depends on succeed;
 * asynchronous SQL queries over the SiteWise views; searches for time series segments; datasets (SiteWise time
 * series, or a Kendra knowledge base) and the data segments they hold; event detection enrichment; dataset exports
 * to S3; and video capture data. Workspace records are kept under {@code workspace/<name>} and what's in them under
 * {@code workspace/<name>/<type>/<id>}, as their ARNs name them.
 */
@ApplicationScoped
public class SiteWiseWorkspaces {

    private static final Logger LOG = Logger.getLogger(SiteWiseWorkspaces.class);
    static final String APPLICATION = "application";

    private final SiteWiseStore store;
    private final ObjectMapper mapper;
    private final Instance<SiteWiseQuery> query;
    private final Instance<SiteWiseData> data;
    private final Instance<KmsService> kms;
    private final Instance<IamService> iam;
    private final Instance<S3Service> s3;
    private final Instance<ContainerBuilder> containers;
    private final Instance<ContainerLifecycleManager> lifecycle;
    private final Instance<EcrRegistryManager> ecr;
    private final ExecutorService runner = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "iotsitewise-workspace");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, String> running = new ConcurrentHashMap<>();

    @Inject
    public SiteWiseWorkspaces(SiteWiseStore store, Instance<SiteWiseQuery> query, Instance<SiteWiseData> data,
                              Instance<KmsService> kms, Instance<IamService> iam, Instance<S3Service> s3,
                              Instance<ContainerBuilder> containers, Instance<ContainerLifecycleManager> lifecycle,
                              Instance<EcrRegistryManager> ecr) {
        this.store = store;
        this.mapper = store.mapper();
        this.query = query;
        this.data = data;
        this.kms = kms;
        this.iam = iam;
        this.s3 = s3;
        this.containers = containers;
        this.lifecycle = lifecycle;
        this.ecr = ecr;
    }

    @PreDestroy
    void stop() {
        running.values().forEach(id -> {
            try {
                lifecycle.get().stopAndRemove(id, null);
            } catch (RuntimeException e) {
                LOG.debugv(e, "IoT SiteWise could not stop container {0}", id);
            }
        });
        runner.shutdownNow();
    }

    // ---------------------------------------------------------------- records

    private ObjectNode get(String account, String region, String id) {
        return store.get(account, region, WORKSPACE, id).orElse(null);
    }

    private void put(String account, String region, String id, ObjectNode record) {
        store.put(account, region, WORKSPACE, id, record);
    }

    /** The records of one type in a workspace, oldest first. */
    private List<ObjectNode> list(String account, String region, String workspace, String type) {
        List<ObjectNode> found = new ArrayList<>();
        for (ObjectNode record : store.list(account, region, WORKSPACE)) {
            if (workspace.equals(meta(record).path("workspace").asText()) && type.equals(meta(record).path("type")
                    .asText())) {
                found.add(record);
            }
        }
        return found;
    }

    private ObjectNode child(String account, String region, String workspace, String type, String id, String what) {
        ObjectNode record = get(account, region, workspace + "/" + type + "/" + id);
        if (record == null) {
            throw notFound(what + " " + id + " not found in workspace " + workspace + ".");
        }
        return record;
    }

    private ObjectNode newChild(String workspace, String type) {
        ObjectNode record = store.record();
        meta(record).put("workspace", workspace).put("type", type);
        return record;
    }

    private String arn(String account, String region, String id) {
        return store.arn(account, region, WORKSPACE, id);
    }

    // ---------------------------------------------------------------- workspaces

    ObjectNode requireWorkspace(String account, String region, String name) {
        ObjectNode workspace = get(account, region, name);
        workspace = workspace == null ? null : settle(account, region, workspace);
        if (workspace == null || !"workspace".equals(meta(workspace).path("type").asText())) {
            throw notFound("Workspace " + name + " not found.");
        }
        return workspace;
    }

    private ObjectNode settle(String account, String region, ObjectNode workspace) {
        long readyAt = meta(workspace).path("readyAt").asLong(0);
        if (readyAt == 0 || System.currentTimeMillis() < readyAt) {
            return workspace;
        }
        String name = workspace.get("workspaceName").asText();
        if ("DELETING".equals(workspace.path("workspaceStatus").path("state").asText())) {
            store.delete(account, region, WORKSPACE, name);
            return null;
        }
        workspace.set("workspaceStatus", mapper.createObjectNode().put("state", "ACTIVE"));
        meta(workspace).remove("readyAt");
        put(account, region, name, workspace);
        return workspace;
    }

    private void transition(ObjectNode workspace, String state) {
        workspace.set("workspaceStatus", mapper.createObjectNode().put("state", state));
        meta(workspace).put("readyAt", System.currentTimeMillis() + store.config().transitionMillis());
    }

    private ObjectNode encryption(String region, JsonNode configuration) {
        ObjectNode info = mapper.createObjectNode().put("encryptionType", configuration.get("encryptionType").asText());
        if ("KMS_BASED_ENCRYPTION".equals(configuration.get("encryptionType").asText())) {
            if (!configuration.hasNonNull("kmsKeyId")) {
                throw invalid("KMS_BASED_ENCRYPTION needs a kmsKeyId.");
            }
            try {
                info.put("kmsKeyArn", kms.get().describeKey(configuration.get("kmsKeyId").asText(), region).getArn());
            } catch (AwsException e) {
                throw invalid("The KMS key " + configuration.get("kmsKeyId").asText() + " doesn't exist.");
            }
        }
        return info;
    }

    ObjectNode createWorkspace(ObjectNode in, String region) {
        String account = store.account();
        String name = in.get("workspaceName").asText();
        if (get(account, region, name) != null) {
            ObjectNode existing = get(account, region, name);
            throw SiteWiseStore.exists("Workspace " + name + " already exists.", name,
                    existing.get("workspaceArn").asText());
        }
        ObjectNode workspace = store.record();
        meta(workspace).put("type", "workspace").put("workspace", name);
        double now = SiteWiseStore.now();
        workspace.put("workspaceArn", arn(account, region, name));
        workspace.put("workspaceName", name);
        if (in.hasNonNull("workspaceDescription")) {
            workspace.put("workspaceDescription", in.get("workspaceDescription").asText());
        }
        workspace.set("encryptionConfiguration", encryption(region, in.get("encryptionConfiguration")));
        workspace.put("createdAt", now);
        workspace.put("updatedAt", now);
        meta(workspace).set("tags", SiteWiseStore.tagsOf(mapper, in.get("tags")));
        transition(workspace, "CREATING");
        put(account, region, name, workspace);
        ObjectNode out = mapper.createObjectNode();
        out.put("workspaceName", name);
        out.put("workspaceArn", workspace.get("workspaceArn").asText());
        out.set("workspaceStatus", workspace.get("workspaceStatus").deepCopy());
        return out;
    }

    ObjectNode describeWorkspace(ObjectNode in, String region) {
        return SiteWiseStore.view(requireWorkspace(store.account(), region, in.get("workspaceName").asText()));
    }

    ObjectNode listWorkspaces(ObjectNode in, String region) {
        String account = store.account();
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode stored : store.list(account, region, WORKSPACE)) {
            if (!"workspace".equals(meta(stored).path("type").asText())) {
                continue;
            }
            ObjectNode workspace = settle(account, region, stored);
            if (workspace != null) {
                summaries.add(mapper.createObjectNode().put("name", workspace.get("workspaceName").asText())
                        .put("arn", workspace.get("workspaceArn").asText())
                        .<ObjectNode>set("status", workspace.get("workspaceStatus").deepCopy())
                        .put("createdAt", workspace.get("createdAt").asDouble())
                        .put("updatedAt", workspace.get("updatedAt").asDouble()));
            }
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "workspaceSummaries");
        return out;
    }

    ObjectNode updateWorkspace(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode workspace = requireWorkspace(account, region, in.get("workspaceName").asText());
        if (in.hasNonNull("workspaceDescription")) {
            workspace.put("workspaceDescription", in.get("workspaceDescription").asText());
        }
        if (in.has("encryptionConfiguration")) {
            workspace.set("encryptionConfiguration", encryption(region, in.get("encryptionConfiguration")));
        }
        workspace.put("updatedAt", SiteWiseStore.now());
        transition(workspace, "UPDATING");
        put(account, region, workspace.get("workspaceName").asText(), workspace);
        return SiteWiseStore.view(workspace);
    }

    ObjectNode deleteWorkspace(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode workspace = requireWorkspace(account, region, in.get("workspaceName").asText());
        String name = workspace.get("workspaceName").asText();
        for (String type : List.of("task", "pipeline")) {
            if (!list(account, region, name, type).isEmpty()) {
                throw SiteWiseStore.conflicting("Workspace " + name + " still has " + type + "s; delete them first.",
                        name, workspace.get("workspaceArn").asText());
            }
        }
        if (store.list(account, region, APPLICATION).stream().anyMatch(a -> name.equals(a.path("workspaceName")
                .asText()))) {
            throw SiteWiseStore.conflicting("Workspace " + name + " still has applications; delete them first.", name,
                    workspace.get("workspaceArn").asText());
        }
        for (ObjectNode record : store.list(account, region, WORKSPACE)) {
            if (name.equals(meta(record).path("workspace").asText()) && !"workspace".equals(meta(record).path("type")
                    .asText())) {
                store.delete(account, region, WORKSPACE, meta(record).path("id").asText());
            }
        }
        transition(workspace, "DELETING");
        put(account, region, name, workspace);
        ObjectNode out = mapper.createObjectNode();
        out.set("workspaceStatus", workspace.get("workspaceStatus").deepCopy());
        return out;
    }

    // ---------------------------------------------------------------- applications

    ObjectNode createApplication(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateApplication", in);
        if (repeated != null) {
            return repeated;
        }
        String workspace = requireWorkspace(account, region, in.get("workspaceName").asText()).get("workspaceName")
                .asText();
        AwsArnUtils.Arn instance;
        try {
            instance = AwsArnUtils.parse(in.get("idcInstanceArn").asText());
        } catch (RuntimeException e) {
            throw invalid("The IAM Identity Center instance ARN isn't valid.");
        }
        if (!"sso".equals(instance.service()) || !instance.resource().startsWith("instance/")) {
            throw invalid("The ARN " + in.get("idcInstanceArn").asText() + " isn't an IAM Identity Center instance.");
        }
        String id = SiteWiseStore.uuid();
        double now = SiteWiseStore.now();
        ObjectNode application = store.record();
        application.put("arn", store.arn(account, region, APPLICATION, id));
        application.put("createdAt", now);
        application.put("dnsSubdomain", in.get("name").asText().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "") + "-" + id.substring(0, 8));
        if (in.hasNonNull("description")) {
            application.put("description", in.get("description").asText());
        }
        application.put("id", id);
        String instanceId = instance.resource().substring("instance/".length());
        application.put("idcApplicationArn", AwsArnUtils.Arn.global(instance.partition(), "sso", account,
                "application/" + instanceId + "/apl-" + id.replace("-", "").substring(0, 16)).toString());
        application.put("name", in.get("name").asText());
        application.put("status", "ACTIVE");
        application.put("updatedAt", now);
        application.put("workspaceName", workspace);
        meta(application).set("tags", SiteWiseStore.tagsOf(mapper, in.get("tags")));
        store.put(account, region, APPLICATION, id, application);
        ObjectNode out = mapper.createObjectNode();
        out.put("arn", application.get("arn").asText());
        out.put("id", id);
        out.put("dnsSubdomain", application.get("dnsSubdomain").asText());
        out.put("name", application.get("name").asText());
        out.put("status", "CREATING");
        store.remember(account, region, "CreateApplication", in, out);
        return out;
    }

    private ObjectNode requireApplication(String account, String region, JsonNode in) {
        ObjectNode application = store.get(account, region, APPLICATION, in.get("id").asText()).orElse(null);
        if (application == null || !application.get("workspaceName").asText().equals(in.get("workspaceName")
                .asText())) {
            throw notFound("Application " + in.get("id").asText() + " not found.");
        }
        return application;
    }

    ObjectNode describeApplication(ObjectNode in, String region) {
        return SiteWiseStore.view(requireApplication(store.account(), region, in));
    }

    ObjectNode deleteApplication(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode application = requireApplication(account, region, in);
        store.delete(account, region, APPLICATION, application.get("id").asText());
        return mapper.createObjectNode();
    }

    ObjectNode listApplications(ObjectNode in, String region) {
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode application : store.list(store.account(), region, APPLICATION)) {
            summaries.add(mapper.createObjectNode().put("arn", application.get("arn").asText())
                    .put("id", application.get("id").asText()).put("name", application.get("name").asText())
                    .put("status", application.get("status").asText())
                    .put("createdAt", application.get("createdAt").asDouble())
                    .put("workspaceName", application.get("workspaceName").asText()));
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "applications");
        return out;
    }

    // ---------------------------------------------------------------- tasks

    private void checkTask(String account, JsonNode configuration) {
        JsonNode container = configuration.get("containerTaskConfiguration");
        if (container == null) {
            throw invalid("A task's configuration is containerTaskConfiguration.");
        }
        String role = container.get("taskExecutionRole").asText();
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(role);
            String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
            RequestScopes.callAs(account, () -> iam.get().getRole(name));
        } catch (RuntimeException e) {
            throw invalid("The task execution role " + role + " doesn't exist.");
        }
        Set<String> mounts = new HashSet<>();
        for (JsonNode mount : container.path("mounts")) {
            if (!mounts.add(mount.get("name").asText())) {
                throw invalid("The mount name " + mount.get("name").asText() + " is used more than once.");
            }
        }
    }

    ObjectNode createTask(ObjectNode in, String region) {
        String account = store.account();
        String workspace = requireWorkspace(account, region, in.get("workspaceName").asText()).get("workspaceName")
                .asText();
        String name = in.get("taskName").asText();
        String id = workspace + "/task/" + name;
        if (get(account, region, id) != null) {
            throw SiteWiseStore.exists("Task " + name + " already exists.", name, arn(account, region, id));
        }
        checkTask(account, in.get("taskConfiguration"));
        double now = SiteWiseStore.now();
        ObjectNode task = newChild(workspace, "task");
        meta(task).put("id", id);
        task.put("workspaceName", workspace);
        task.put("taskName", name);
        if (in.hasNonNull("description")) {
            task.put("description", in.get("description").asText());
        }
        task.put("taskArn", arn(account, region, id));
        task.put("version", "1");
        task.set("taskConfiguration", in.get("taskConfiguration").deepCopy());
        task.set("status", mapper.createObjectNode().put("state", "ACTIVE"));
        task.put("createdAt", now);
        task.put("updatedAt", now);
        meta(task).set("tags", SiteWiseStore.tagsOf(mapper, in.get("tags")));
        put(account, region, id, task);
        ObjectNode out = mapper.createObjectNode().put("taskName", name).put("taskArn", task.get("taskArn").asText())
                .put("version", "1");
        out.set("status", task.get("status").deepCopy());
        return out;
    }

    ObjectNode describeTask(ObjectNode in, String region) {
        return SiteWiseStore.view(child(store.account(), region, in.get("workspaceName").asText(), "task",
                in.get("taskName").asText(), "Task"));
    }

    ObjectNode listTasks(ObjectNode in, String region) {
        String account = store.account();
        String workspace = requireWorkspace(account, region, in.get("workspaceName").asText()).get("workspaceName")
                .asText();
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode task : list(account, region, workspace, "task")) {
            ObjectNode summary = SiteWiseStore.view(task);
            summary.remove("taskConfiguration");
            summary.remove("workspaceName");
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "taskSummaries");
        return out;
    }

    ObjectNode updateTask(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode task = child(account, region, in.get("workspaceName").asText(), "task", in.get("taskName").asText(),
                "Task");
        if (in.has("taskConfiguration")) {
            checkTask(account, in.get("taskConfiguration"));
            task.set("taskConfiguration", in.get("taskConfiguration").deepCopy());
        }
        if (in.hasNonNull("description")) {
            task.put("description", in.get("description").asText());
        }
        task.put("version", String.valueOf(task.get("version").asInt() + 1));
        task.put("updatedAt", SiteWiseStore.now());
        put(account, region, meta(task).get("id").asText(), task);
        ObjectNode out = mapper.createObjectNode().put("taskName", task.get("taskName").asText())
                .put("taskArn", task.get("taskArn").asText()).put("version", task.get("version").asText());
        out.set("status", task.get("status").deepCopy());
        return out;
    }

    ObjectNode deleteTask(ObjectNode in, String region) {
        String account = store.account();
        String workspace = in.get("workspaceName").asText();
        ObjectNode task = child(account, region, workspace, "task", in.get("taskName").asText(), "Task");
        for (ObjectNode pipeline : list(account, region, workspace, "pipeline")) {
            for (JsonNode node : pipeline.path("computations")) {
                if (node.get("taskName").asText().equals(task.get("taskName").asText())) {
                    throw SiteWiseStore.conflicting("Pipeline " + pipeline.get("pipelineName").asText()
                            + " runs task " + task.get("taskName").asText() + ".", task.get("taskName").asText(),
                            task.get("taskArn").asText());
                }
            }
        }
        store.delete(account, region, WORKSPACE, meta(task).get("id").asText());
        return mapper.createObjectNode();
    }

    // ---------------------------------------------------------------- pipelines

    private void checkComputations(String account, String region, String workspace, JsonNode computations) {
        if (computations == null || computations.isEmpty()) {
            throw invalid("A pipeline has at least one compute node.");
        }
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (JsonNode node : computations) {
            String name = node.get("computeNodeName").asText();
            if (edges.containsKey(name)) {
                throw invalid("The compute node name " + name + " is used more than once.");
            }
            child(account, region, workspace, "task", node.get("taskName").asText(), "Task");
            List<String> depends = new ArrayList<>();
            node.path("dependsOn").forEach(d -> depends.add(d.asText()));
            edges.put(name, depends);
        }
        for (Map.Entry<String, List<String>> entry : edges.entrySet()) {
            for (String dependency : entry.getValue()) {
                if (!edges.containsKey(dependency)) {
                    throw invalid("Compute node " + entry.getKey() + " depends on " + dependency
                            + ", which the pipeline doesn't have.");
                }
            }
        }
        order(edges);
    }

    /** Compute nodes in an order where each comes after those it depends on; a cycle is an error. */
    private static List<String> order(Map<String, List<String>> edges) {
        List<String> ordered = new ArrayList<>();
        Map<String, Integer> marks = new HashMap<>();
        for (String node : edges.keySet()) {
            visit(node, edges, marks, ordered);
        }
        return ordered;
    }

    private static void visit(String node, Map<String, List<String>> edges, Map<String, Integer> marks,
                              List<String> ordered) {
        int mark = marks.getOrDefault(node, 0);
        if (mark == 2) {
            return;
        }
        if (mark == 1) {
            throw invalid("The pipeline's compute nodes depend on each other in a cycle through " + node + ".");
        }
        marks.put(node, 1);
        for (String next : edges.getOrDefault(node, List.of())) {
            visit(next, edges, marks, ordered);
        }
        marks.put(node, 2);
        ordered.add(node);
    }

    ObjectNode createPipeline(ObjectNode in, String region) {
        String account = store.account();
        String workspace = requireWorkspace(account, region, in.get("workspaceName").asText()).get("workspaceName")
                .asText();
        String name = in.get("pipelineName").asText();
        String id = workspace + "/pipeline/" + name;
        if (get(account, region, id) != null) {
            throw SiteWiseStore.exists("Pipeline " + name + " already exists.", name, arn(account, region, id));
        }
        checkComputations(account, region, workspace, in.get("computations"));
        double now = SiteWiseStore.now();
        ObjectNode pipeline = newChild(workspace, "pipeline");
        meta(pipeline).put("id", id);
        pipeline.put("pipelineName", name);
        pipeline.put("workspaceName", workspace);
        if (in.hasNonNull("description")) {
            pipeline.put("description", in.get("description").asText());
        }
        pipeline.put("pipelineArn", arn(account, region, id));
        pipeline.put("version", "1");
        if (in.has("environmentVariables")) {
            pipeline.set("environmentVariables", in.get("environmentVariables").deepCopy());
        }
        pipeline.set("computations", in.get("computations").deepCopy());
        pipeline.set("status", mapper.createObjectNode().put("state", "ACTIVE"));
        pipeline.put("createdAt", now);
        pipeline.put("updatedAt", now);
        meta(pipeline).set("tags", SiteWiseStore.tagsOf(mapper, in.get("tags")));
        put(account, region, id, pipeline);
        ObjectNode out = mapper.createObjectNode().put("pipelineName", name)
                .put("pipelineArn", pipeline.get("pipelineArn").asText()).put("version", "1");
        out.set("status", pipeline.get("status").deepCopy());
        return out;
    }

    ObjectNode describePipeline(ObjectNode in, String region) {
        return SiteWiseStore.view(child(store.account(), region, in.get("workspaceName").asText(), "pipeline",
                in.get("pipelineName").asText(), "Pipeline"));
    }

    ObjectNode listPipelines(ObjectNode in, String region) {
        String account = store.account();
        String workspace = requireWorkspace(account, region, in.get("workspaceName").asText()).get("workspaceName")
                .asText();
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode pipeline : list(account, region, workspace, "pipeline")) {
            ObjectNode summary = mapper.createObjectNode();
            summary.put("pipelineName", pipeline.get("pipelineName").asText());
            if (pipeline.hasNonNull("description")) {
                summary.put("description", pipeline.get("description").asText());
            }
            summary.put("pipelineArn", pipeline.get("pipelineArn").asText());
            summary.put("version", pipeline.get("version").asText());
            summary.set("status", pipeline.get("status").deepCopy());
            summary.put("createdAt", pipeline.get("createdAt").asDouble());
            summary.put("updatedAt", pipeline.get("updatedAt").asDouble());
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "pipelineSummaries");
        return out;
    }

    ObjectNode updatePipeline(ObjectNode in, String region) {
        String account = store.account();
        String workspace = in.get("workspaceName").asText();
        ObjectNode pipeline = child(account, region, workspace, "pipeline", in.get("pipelineName").asText(),
                "Pipeline");
        if (in.has("computations")) {
            checkComputations(account, region, workspace, in.get("computations"));
            pipeline.set("computations", in.get("computations").deepCopy());
        }
        if (in.has("environmentVariables")) {
            pipeline.set("environmentVariables", in.get("environmentVariables").deepCopy());
        }
        if (in.hasNonNull("description")) {
            pipeline.put("description", in.get("description").asText());
        }
        pipeline.put("version", String.valueOf(pipeline.get("version").asInt() + 1));
        pipeline.put("updatedAt", SiteWiseStore.now());
        put(account, region, meta(pipeline).get("id").asText(), pipeline);
        ObjectNode out = mapper.createObjectNode().put("pipelineName", pipeline.get("pipelineName").asText())
                .put("pipelineArn", pipeline.get("pipelineArn").asText())
                .put("version", pipeline.get("version").asText());
        out.set("status", pipeline.get("status").deepCopy());
        return out;
    }

    ObjectNode deletePipeline(ObjectNode in, String region) {
        String account = store.account();
        String workspace = in.get("workspaceName").asText();
        ObjectNode pipeline = child(account, region, workspace, "pipeline", in.get("pipelineName").asText(),
                "Pipeline");
        for (ObjectNode execution : list(account, region, workspace, "pipeline-execution")) {
            if (pipeline.get("pipelineName").asText().equals(execution.get("pipelineName").asText())
                    && Set.of("NOT_STARTED", "RUNNING", "CANCELLING").contains(execution.path("status").path("state")
                    .asText())) {
                throw SiteWiseStore.conflicting("Pipeline " + pipeline.get("pipelineName").asText()
                        + " has a running execution.", pipeline.get("pipelineName").asText(),
                        pipeline.get("pipelineArn").asText());
            }
        }
        store.delete(account, region, WORKSPACE, meta(pipeline).get("id").asText());
        return mapper.createObjectNode();
    }

    ObjectNode startPipelineExecution(ObjectNode in, String region) {
        String account = store.account();
        String workspace = in.get("workspaceName").asText();
        ObjectNode pipeline = child(account, region, workspace, "pipeline", in.get("pipelineName").asText(),
                "Pipeline");
        String id = SiteWiseStore.uuid();
        String recordId = workspace + "/pipeline-execution/" + id;
        ObjectNode execution = newChild(workspace, "pipeline-execution");
        meta(execution).put("id", recordId);
        execution.put("pipelineExecutionId", id);
        execution.put("pipelineName", pipeline.get("pipelineName").asText());
        execution.put("workspaceName", workspace);
        execution.put("pipelineVersion", pipeline.get("version").asText());
        execution.set("status", mapper.createObjectNode().put("state", "NOT_STARTED"));
        ObjectNode overrides = in.has("executionEnvironmentVariableOverrides")
                ? in.get("executionEnvironmentVariableOverrides").deepCopy() : mapper.createObjectNode();
        execution.set("requestEnvironmentVariables", overrides);
        if (in.has("executionMountOverrides")) {
            execution.set("requestMountOverrides", in.get("executionMountOverrides").deepCopy());
        }
        execution.put("executionPriority", in.path("executionPriority").asInt(1));
        ArrayNode details = execution.putArray("computeNodeExecutionDetails");
        for (JsonNode node : pipeline.get("computations")) {
            ObjectNode task = child(account, region, workspace, "task", node.get("taskName").asText(), "Task");
            ObjectNode detail = details.addObject();
            detail.put("computeNodeName", node.get("computeNodeName").asText());
            detail.put("taskName", task.get("taskName").asText());
            detail.put("taskArn", task.get("taskArn").asText());
            detail.put("taskVersion", task.get("version").asText());
            detail.set("dependsOn", node.has("dependsOn") ? node.get("dependsOn").deepCopy()
                    : mapper.createArrayNode());
            detail.set("status", mapper.createObjectNode().put("state", "NOT_STARTED"));
            ObjectNode environment = mapper.createObjectNode();
            pipeline.path("environmentVariables").fields().forEachRemaining(e -> environment.set(e.getKey(),
                    e.getValue()));
            task.path("taskConfiguration").path("containerTaskConfiguration").path("environmentVariables").fields()
                    .forEachRemaining(e -> environment.set(e.getKey(), e.getValue()));
            node.path("environmentVariables").fields().forEachRemaining(e -> environment.set(e.getKey(),
                    e.getValue()));
            overrides.path("global").fields().forEachRemaining(e -> environment.set(e.getKey(), e.getValue()));
            overrides.path("computeNodes").path(node.get("computeNodeName").asText()).fields()
                    .forEachRemaining(e -> environment.set(e.getKey(), e.getValue()));
            detail.set("executionEnvironmentVariables", environment);
            JsonNode mounts = in.path("executionMountOverrides").path("computeNodes")
                    .path(node.get("computeNodeName").asText());
            detail.set("executionMounts", mounts.isArray() ? mounts.deepCopy()
                    : task.path("taskConfiguration").path("containerTaskConfiguration").path("mounts").isArray()
                    ? task.path("taskConfiguration").path("containerTaskConfiguration").path("mounts").deepCopy()
                    : mapper.createArrayNode());
        }
        put(account, region, recordId, execution);
        runner.submit(() -> RequestScopes.runAs(account, region, () -> runPipeline(account, region, recordId)));
        return mapper.createObjectNode().put("pipelineExecutionId", id);
    }

    private ObjectNode execution(String account, String region, String recordId) {
        return get(account, region, recordId);
    }

    private void runPipeline(String account, String region, String recordId) {
        ObjectNode execution = execution(account, region, recordId);
        if (execution == null || "CANCELLING".equals(execution.path("status").path("state").asText())) {
            finishPipeline(account, region, recordId, "CANCELLED", null);
            return;
        }
        execution.set("status", mapper.createObjectNode().put("state", "RUNNING"));
        execution.put("startTime", SiteWiseStore.now());
        put(account, region, recordId, execution);
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (JsonNode detail : execution.get("computeNodeExecutionDetails")) {
            List<String> depends = new ArrayList<>();
            detail.path("dependsOn").forEach(d -> depends.add(d.asText()));
            edges.put(detail.get("computeNodeName").asText(), depends);
        }
        Set<String> failed = new HashSet<>();
        String failure = null;
        for (String node : order(edges)) {
            ObjectNode current = execution(account, region, recordId);
            if (current == null || "CANCELLING".equals(current.path("status").path("state").asText())) {
                finishPipeline(account, region, recordId, "CANCELLED", null);
                return;
            }
            if (edges.get(node).stream().anyMatch(failed::contains)) {
                failed.add(node);
                continue;
            }
            String error = runNode(account, region, recordId, node);
            if (error != null) {
                failed.add(node);
                failure = failure == null ? error : failure;
            }
        }
        finishPipeline(account, region, recordId, failed.isEmpty() ? "SUCCEEDED" : "FAILED", failure);
    }

    private void finishPipeline(String account, String region, String recordId, String state, String message) {
        ObjectNode execution = execution(account, region, recordId);
        if (execution == null) {
            return;
        }
        ObjectNode status = mapper.createObjectNode().put("state", state);
        if (message != null) {
            status.putObject("stateDetails").put("code", "EXECUTION_ERROR").put("message", message);
        }
        execution.set("status", status);
        execution.put("endTime", SiteWiseStore.now());
        for (JsonNode detail : execution.get("computeNodeExecutionDetails")) {
            if ("NOT_STARTED".equals(detail.path("status").path("state").asText())) {
                ((ObjectNode) detail).set("status", mapper.createObjectNode().put("state", "FAILED")
                        .<ObjectNode>set("stateDetails", mapper.createObjectNode().put("code", "EXECUTION_ERROR")
                                .put("message", "CANCELLED".equals(state) ? "The execution was cancelled."
                                        : "A compute node it depends on failed.")));
            }
        }
        put(account, region, recordId, execution);
    }

    private ObjectNode node(ObjectNode execution, String name) {
        for (JsonNode detail : execution.get("computeNodeExecutionDetails")) {
            if (name.equals(detail.get("computeNodeName").asText())) {
                return (ObjectNode) detail;
            }
        }
        throw new IllegalStateException("no compute node " + name);
    }

    private void nodeStatus(String account, String region, String recordId, String node, String state,
                            String message, boolean start) {
        ObjectNode execution = execution(account, region, recordId);
        ObjectNode detail = node(execution, node);
        ObjectNode status = mapper.createObjectNode().put("state", state);
        if (message != null) {
            status.putObject("stateDetails").put("code", "EXECUTION_ERROR").put("message", message);
        }
        detail.set("status", status);
        detail.put(start ? "startTime" : "endTime", SiteWiseStore.now());
        put(account, region, recordId, execution);
    }

    /** Runs one compute node's task as a container; returns null when it exits 0, else the error. */
    private String runNode(String account, String region, String recordId, String node) {
        ObjectNode execution = execution(account, region, recordId);
        ObjectNode detail = node(execution, node);
        ObjectNode task = get(account, region, meta(execution).get("workspace").asText() + "/task/"
                + detail.get("taskName").asText());
        if (task == null) {
            nodeStatus(account, region, recordId, node, "FAILED", "The task no longer exists.", false);
            return "The task " + detail.get("taskName").asText() + " no longer exists.";
        }
        JsonNode container = task.get("taskConfiguration").get("containerTaskConfiguration");
        nodeStatus(account, region, recordId, node, "RUNNING", null, true);
        List<String> environment = new ArrayList<>();
        detail.path("executionEnvironmentVariables").fields().forEachRemaining(e -> environment.add(e.getKey() + "="
                + e.getValue().asText()));
        environment.add("AWS_REGION=" + region);
        environment.add("SITEWISE_WORKSPACE_NAME=" + meta(execution).get("workspace").asText());
        environment.add("SITEWISE_PIPELINE_EXECUTION_ID=" + execution.get("pipelineExecutionId").asText());
        environment.add("SITEWISE_COMPUTE_NODE_NAME=" + node);
        String containerId = null;
        try {
            String image = ecr.get().rewriteImageUri(container.get("ecrUri").asText());
            ContainerBuilder.Builder builder = containers.get().newContainer(image).withEnv(environment)
                    .withDockerNetwork(store.emulator().services().dockerNetwork()).withHostDockerInternalOnLinux()
                    .withEmbeddedDns().withLogRotation();
            if (container.path("command").isArray() && !container.get("command").isEmpty()) {
                List<String> command = new ArrayList<>();
                container.get("command").forEach(c -> command.add(c.asText()));
                builder.withCmd(command);
            }
            ContainerSpec spec = builder.build();
            containerId = lifecycle.get().create(spec);
            running.put(recordId + "/" + node, containerId);
            lifecycle.get().startCreated(containerId, spec);
            long timeout = container.path("timeoutSeconds").asLong(3600) * 1000;
            long deadline = System.currentTimeMillis() + timeout;
            Integer exit = null;
            while (System.currentTimeMillis() < deadline) {
                InspectContainerResponse inspect = lifecycle.get().getDockerClient().inspectContainerCmd(containerId)
                        .exec();
                if (!Boolean.TRUE.equals(inspect.getState().getRunning())) {
                    Long code = inspect.getState().getExitCodeLong();
                    exit = code == null ? 0 : code.intValue();
                    break;
                }
                ObjectNode current = execution(account, region, recordId);
                if (current != null && "CANCELLING".equals(current.path("status").path("state").asText())) {
                    break;
                }
                Thread.sleep(250);
            }
            if (exit == null) {
                String message = System.currentTimeMillis() >= deadline ? "The task timed out." : "Cancelled.";
                nodeStatus(account, region, recordId, node, "FAILED", message, false);
                return message;
            }
            if (exit != 0) {
                nodeStatus(account, region, recordId, node, "FAILED", "The container exited with code " + exit + ".",
                        false);
                return "Compute node " + node + " exited with code " + exit + ".";
            }
            nodeStatus(account, region, recordId, node, "SUCCEEDED", null, false);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            nodeStatus(account, region, recordId, node, "FAILED", "Interrupted.", false);
            return "Interrupted.";
        } catch (RuntimeException e) {
            String message = "The task's container couldn't run: " + e.getMessage();
            nodeStatus(account, region, recordId, node, "FAILED", message, false);
            return message;
        } finally {
            if (containerId != null) {
                running.remove(recordId + "/" + node);
                try {
                    lifecycle.get().stopAndRemove(containerId, null);
                } catch (RuntimeException e) {
                    LOG.debugv(e, "IoT SiteWise could not remove container {0}", containerId);
                }
            }
        }
    }

    ObjectNode describePipelineExecution(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode execution = child(account, region, in.get("workspaceName").asText(), "pipeline-execution",
                in.get("pipelineExecutionId").asText(), "Pipeline execution");
        if (!execution.get("pipelineName").asText().equals(in.get("pipelineName").asText())) {
            throw notFound("Pipeline execution " + in.get("pipelineExecutionId").asText() + " not found.");
        }
        ObjectNode view = SiteWiseStore.view(execution);
        List<JsonNode> details = new ArrayList<>();
        view.get("computeNodeExecutionDetails").forEach(details::add);
        ObjectNode page = mapper.createObjectNode();
        SiteWiseStore.page(details, in, 100, page, "computeNodeExecutionDetails");
        view.set("computeNodeExecutionDetails", page.get("computeNodeExecutionDetails"));
        if (page.has("nextToken")) {
            view.put("nextToken", page.get("nextToken").asText());
        }
        return view;
    }

    ObjectNode listPipelineExecutions(ObjectNode in, String region) {
        String account = store.account();
        String workspace = in.get("workspaceName").asText();
        child(account, region, workspace, "pipeline", in.get("pipelineName").asText(), "Pipeline");
        String state = in.path("state").asText(null);
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode execution : list(account, region, workspace, "pipeline-execution")) {
            if (!in.get("pipelineName").asText().equals(execution.get("pipelineName").asText())
                    || state != null && !state.equals(execution.path("status").path("state").asText())) {
                continue;
            }
            double started = execution.path("startTime").asDouble(0);
            double ended = execution.path("endTime").asDouble(0);
            if (in.has("startTimeAfter") && started < SiteWiseData.date(in.get("startTimeAfter"), 0)
                    || in.has("startTimeBefore") && started > SiteWiseData.date(in.get("startTimeBefore"), 0)
                    || in.has("endTimeAfter") && ended < SiteWiseData.date(in.get("endTimeAfter"), 0)
                    || in.has("endTimeBefore") && ended > SiteWiseData.date(in.get("endTimeBefore"), 0)) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            summary.put("pipelineExecutionId", execution.get("pipelineExecutionId").asText());
            summary.put("pipelineVersion", execution.get("pipelineVersion").asText());
            summary.set("status", execution.get("status").deepCopy());
            summary.put("executionPriority", execution.path("executionPriority").asInt(1));
            if (execution.has("startTime")) {
                summary.put("startTime", started);
            }
            if (execution.has("endTime")) {
                summary.put("endTime", ended);
            }
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "pipelineExecutionSummaries");
        return out;
    }

    ObjectNode cancelPipelineExecution(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode execution = child(account, region, in.get("workspaceName").asText(), "pipeline-execution",
                in.get("pipelineExecutionId").asText(), "Pipeline execution");
        String state = execution.path("status").path("state").asText();
        if (!Set.of("NOT_STARTED", "RUNNING").contains(state)) {
            throw SiteWiseStore.conflicting("Pipeline execution " + in.get("pipelineExecutionId").asText() + " is "
                    + state + ".", in.get("pipelineExecutionId").asText(),
                    arn(account, region, meta(execution).get("id").asText()));
        }
        ObjectNode status = mapper.createObjectNode().put("state", "CANCELLING");
        if (in.hasNonNull("reason")) {
            status.putObject("stateDetails").put("message", in.get("reason").asText());
        }
        execution.set("status", status);
        put(account, region, meta(execution).get("id").asText(), execution);
        return mapper.createObjectNode().<ObjectNode>set("state", mapper.getNodeFactory().textNode("CANCELLING"));
    }

    // ---------------------------------------------------------------- queries

    ObjectNode startQuery(ObjectNode in, String region) {
        String account = store.account();
        String workspace = requireWorkspace(account, region, in.get("workspaceName").asText()).get("workspaceName")
                .asText();
        String id = SiteWiseStore.uuid();
        String recordId = workspace + "/query/" + id;
        ObjectNode record = newChild(workspace, "query");
        meta(record).put("id", recordId);
        record.put("queryId", id);
        record.put("status", "SUBMITTED");
        record.put("submittedAt", SiteWiseStore.now());
        meta(record).put("statement", in.get("queryStatement").asText());
        put(account, region, recordId, record);
        runner.submit(() -> RequestScopes.runAs(account, region, () -> runQuery(account, region, recordId)));
        return mapper.createObjectNode().put("queryId", id).put("status", "SUBMITTED");
    }

    private void runQuery(String account, String region, String recordId) {
        ObjectNode record = get(account, region, recordId);
        if (record == null || !"SUBMITTED".equals(record.get("status").asText())) {
            return;
        }
        record.put("status", "RUNNING");
        put(account, region, recordId, record);
        long started = System.currentTimeMillis();
        try {
            ObjectNode result = query.get().executeQuery(mapper.createObjectNode()
                    .put("queryStatement", meta(record).get("statement").asText()).put("maxResults", 1_000_000), region);
            record = get(account, region, recordId);
            if (record == null || "CANCELING".equals(record.get("status").asText())) {
                if (record != null) {
                    record.put("status", "CANCELED");
                    put(account, region, recordId, record);
                }
                return;
            }
            ArrayNode columns = mapper.createArrayNode();
            result.get("columns").forEach(c -> columns.addObject().put("name", c.get("name").asText())
                    .put("type", c.path("type").path("scalarType").asText()));
            ArrayNode rows = mapper.createArrayNode();
            result.get("rows").forEach(row -> {
                ArrayNode values = rows.addArray();
                row.get("data").forEach(d -> {
                    if (d.has("nullValue")) {
                        values.addNull();
                    } else {
                        values.add(d.get("scalarValue").asText());
                    }
                });
            });
            meta(record).set("columns", columns);
            meta(record).set("rows", rows);
            record.put("status", "COMPLETED");
            record.put("completedAt", SiteWiseStore.now());
            record.putObject("statistics").put("rowCount", rows.size())
                    .put("bytesScanned", rows.toString().getBytes(StandardCharsets.UTF_8).length)
                    .put("executionTimeInMillis", System.currentTimeMillis() - started);
        } catch (AwsException e) {
            record = get(account, region, recordId);
            if (record == null) {
                return;
            }
            record.put("status", "FAILED");
            record.put("completedAt", SiteWiseStore.now());
            String message = e.getMessage();
            record.put("errorMessage", message.length() > 1024 ? message.substring(0, 1024) : message);
        }
        put(account, region, recordId, record);
    }

    ObjectNode describeQuery(ObjectNode in, String region) {
        return SiteWiseStore.view(child(store.account(), region, in.get("workspaceName").asText(), "query",
                in.get("queryId").asText(), "Query"));
    }

    ObjectNode getQueryResults(ObjectNode in, String region) {
        ObjectNode record = child(store.account(), region, in.get("workspaceName").asText(), "query",
                in.get("queryId").asText(), "Query");
        ObjectNode out = mapper.createObjectNode();
        if (!"COMPLETED".equals(record.get("status").asText())) {
            throw invalid("Query " + in.get("queryId").asText() + " is " + record.get("status").asText()
                    + ", not COMPLETED.");
        }
        out.set("columnInfo", meta(record).get("columns").deepCopy());
        List<JsonNode> rows = new ArrayList<>();
        meta(record).get("rows").forEach(rows::add);
        SiteWiseStore.page(rows, in, 1000, out, "rows");
        return out;
    }

    ObjectNode listQueries(ObjectNode in, String region) {
        String account = store.account();
        String workspace = requireWorkspace(account, region, in.get("workspaceName").asText()).get("workspaceName")
                .asText();
        String filter = in.path("filter").asText(null);
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode record : list(account, region, workspace, "query")) {
            if (filter != null && !filter.equals(record.get("status").asText())) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode().put("queryId", record.get("queryId").asText())
                    .put("status", record.get("status").asText())
                    .put("submittedAt", record.get("submittedAt").asDouble());
            if (record.has("completedAt")) {
                summary.put("completedAt", record.get("completedAt").asDouble());
            }
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 100, out, "queries");
        return out;
    }

    ObjectNode cancelQuery(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode record = child(account, region, in.get("workspaceName").asText(), "query",
                in.get("queryId").asText(), "Query");
        String status = record.get("status").asText();
        if ("SUBMITTED".equals(status)) {
            record.put("status", "CANCELED");
        } else if ("RUNNING".equals(status)) {
            record.put("status", "CANCELING");
        } else {
            throw SiteWiseStore.conflicting("Query " + in.get("queryId").asText() + " is " + status + ".",
                    in.get("queryId").asText(), arn(account, region, meta(record).get("id").asText()));
        }
        put(account, region, meta(record).get("id").asText(), record);
        return mapper.createObjectNode().put("queryId", record.get("queryId").asText())
                .put("status", record.get("status").asText());
    }

    // ---------------------------------------------------------------- datasets

    ObjectNode requireDataset(String account, String region, String id) {
        ObjectNode dataset = store.get(account, region, DATASET, id).orElse(null);
        dataset = dataset == null ? null : settleDataset(account, region, dataset);
        if (dataset == null) {
            throw notFound("Dataset " + id + " not found.");
        }
        return dataset;
    }

    private ObjectNode settleDataset(String account, String region, ObjectNode dataset) {
        long readyAt = meta(dataset).path("readyAt").asLong(0);
        if (readyAt == 0 || System.currentTimeMillis() < readyAt) {
            return dataset;
        }
        if ("DELETING".equals(dataset.path("datasetStatus").path("state").asText())) {
            store.delete(account, region, DATASET, dataset.get("datasetId").asText());
            return null;
        }
        dataset.set("datasetStatus", mapper.createObjectNode().put("state", "ACTIVE"));
        meta(dataset).remove("readyAt");
        store.put(account, region, DATASET, dataset.get("datasetId").asText(), dataset);
        return dataset;
    }

    private void datasetTransition(ObjectNode dataset, String state) {
        dataset.set("datasetStatus", mapper.createObjectNode().put("state", state));
        meta(dataset).put("readyAt", System.currentTimeMillis() + store.config().transitionMillis());
    }

    private void checkSource(String account, JsonNode source) {
        String type = source.get("sourceType").asText();
        String format = source.get("sourceFormat").asText();
        if ("KENDRA".equals(type)) {
            if (!"KNOWLEDGE_BASE".equals(format)) {
                throw invalid("A KENDRA dataset's format is KNOWLEDGE_BASE.");
            }
            JsonNode kendra = source.path("sourceDetail").path("kendra");
            if (!kendra.hasNonNull("knowledgeBaseArn") || !kendra.hasNonNull("roleArn")) {
                throw invalid("A KENDRA dataset names its knowledge base and role.");
            }
            AwsArnUtils.Arn index = AwsArnUtils.parse(kendra.get("knowledgeBaseArn").asText());
            if (!"kendra".equals(index.service()) || !index.resource().startsWith("index/")) {
                throw invalid("The knowledge base ARN isn't a Kendra index.");
            }
            String role = AwsArnUtils.parse(kendra.get("roleArn").asText()).resource();
            try {
                RequestScopes.callAs(account, () -> iam.get().getRole(role.substring(role.lastIndexOf('/') + 1)));
            } catch (RuntimeException e) {
                throw invalid("The role " + kendra.get("roleArn").asText() + " doesn't exist.");
            }
        } else if (!"TIMESERIES".equals(format)) {
            throw invalid("A SITEWISE dataset's format is TIMESERIES.");
        }
    }

    ObjectNode createDataset(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateDataset", in);
        if (repeated != null) {
            return repeated;
        }
        checkSource(account, in.get("datasetSource"));
        String workspace = in.hasNonNull("workspaceName")
                ? requireWorkspace(account, region, in.get("workspaceName").asText()).get("workspaceName").asText()
                : null;
        String id = in.hasNonNull("datasetId") ? in.get("datasetId").asText() : SiteWiseStore.uuid();
        if (store.get(account, region, DATASET, id).isPresent()) {
            throw SiteWiseStore.exists("Dataset " + id + " already exists.", id, store.arn(account, region, DATASET,
                    id));
        }
        double now = SiteWiseStore.now();
        ObjectNode dataset = store.record();
        dataset.put("datasetId", id);
        dataset.put("datasetArn", store.arn(account, region, DATASET, id));
        dataset.put("datasetName", in.get("datasetName").asText());
        dataset.put("datasetDescription", in.path("datasetDescription").asText(""));
        dataset.put("datasetType", in.path("datasetType").asText("CURATED"));
        for (String field : List.of("datasetConfig", "metadata", "datasetSource")) {
            if (in.has(field)) {
                dataset.set(field, in.get(field).deepCopy());
            }
        }
        if (workspace != null) {
            dataset.put("workspaceName", workspace);
        }
        dataset.put("datasetCreationDate", now);
        dataset.put("datasetLastUpdateDate", now);
        dataset.put("datasetVersion", "1");
        dataset.putObject("enrichmentStatus").putObject("video").put("status", "NOT_ENRICHED");
        meta(dataset).set("tags", SiteWiseStore.tagsOf(mapper, in.get("tags")));
        meta(dataset).putArray("segments");
        datasetTransition(dataset, "CREATING");
        store.put(account, region, DATASET, id, dataset);
        ObjectNode out = mapper.createObjectNode().put("datasetId", id)
                .put("datasetArn", dataset.get("datasetArn").asText());
        out.set("datasetStatus", dataset.get("datasetStatus").deepCopy());
        store.remember(account, region, "CreateDataset", in, out);
        return out;
    }

    ObjectNode describeDataset(ObjectNode in, String region) {
        return SiteWiseStore.view(requireDataset(store.account(), region, in.get("datasetId").asText()));
    }

    ObjectNode listDatasets(ObjectNode in, String region) {
        String account = store.account();
        String sourceType = in.get("sourceType").asText();
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode stored : store.list(account, region, DATASET)) {
            ObjectNode dataset = settleDataset(account, region, stored);
            if (dataset == null || !sourceType.equals(dataset.path("datasetSource").path("sourceType").asText())) {
                continue;
            }
            if (in.hasNonNull("workspaceName") && !in.get("workspaceName").asText().equals(dataset
                    .path("workspaceName").asText(null))) {
                continue;
            }
            if (in.hasNonNull("datasetType") && !in.get("datasetType").asText().equals(dataset.path("datasetType")
                    .asText())) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            summary.put("id", dataset.get("datasetId").asText());
            summary.put("arn", dataset.get("datasetArn").asText());
            summary.put("name", dataset.get("datasetName").asText());
            summary.put("description", dataset.get("datasetDescription").asText());
            summary.put("sourceType", sourceType);
            summary.put("datasetType", dataset.path("datasetType").asText());
            summary.put("creationDate", dataset.get("datasetCreationDate").asDouble());
            summary.put("lastUpdateDate", dataset.get("datasetLastUpdateDate").asDouble());
            summary.set("status", dataset.get("datasetStatus").deepCopy());
            summary.set("enrichmentStatus", dataset.get("enrichmentStatus").deepCopy());
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "datasetSummaries");
        if (in.hasNonNull("workspaceName")) {
            out.put("workspaceName", in.get("workspaceName").asText());
        }
        return out;
    }

    ObjectNode updateDataset(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode dataset = requireDataset(account, region, in.get("datasetId").asText());
        checkSource(account, in.get("datasetSource"));
        if (!in.get("datasetSource").get("sourceType").asText().equals(dataset.path("datasetSource")
                .path("sourceType").asText())) {
            throw invalid("A dataset's source type can't change.");
        }
        dataset.put("datasetName", in.get("datasetName").asText());
        dataset.put("datasetDescription", in.path("datasetDescription").asText(dataset.get("datasetDescription")
                .asText()));
        for (String field : List.of("datasetConfig", "metadata", "datasetSource")) {
            if (in.has(field)) {
                dataset.set(field, in.get(field).deepCopy());
            }
        }
        bumpDataset(dataset);
        datasetTransition(dataset, "UPDATING");
        store.put(account, region, DATASET, dataset.get("datasetId").asText(), dataset);
        ObjectNode out = mapper.createObjectNode().put("datasetId", dataset.get("datasetId").asText())
                .put("datasetArn", dataset.get("datasetArn").asText());
        out.set("datasetStatus", dataset.get("datasetStatus").deepCopy());
        return out;
    }

    private static void bumpDataset(ObjectNode dataset) {
        dataset.put("datasetVersion", String.valueOf(dataset.get("datasetVersion").asInt() + 1));
        dataset.put("datasetLastUpdateDate", SiteWiseStore.now());
    }

    ObjectNode deleteDataset(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode dataset = requireDataset(account, region, in.get("datasetId").asText());
        datasetTransition(dataset, "DELETING");
        store.put(account, region, DATASET, dataset.get("datasetId").asText(), dataset);
        ObjectNode out = mapper.createObjectNode();
        out.set("datasetStatus", dataset.get("datasetStatus").deepCopy());
        return out;
    }

    private ObjectNode workspaceDataset(String account, String region, JsonNode in) {
        ObjectNode dataset = requireDataset(account, region, in.get("datasetId").asText());
        if (!in.get("workspaceName").asText().equals(dataset.path("workspaceName").asText(null))) {
            throw notFound("Dataset " + in.get("datasetId").asText() + " isn't in workspace "
                    + in.get("workspaceName").asText() + ".");
        }
        return dataset;
    }

    static long nanos(JsonNode time) {
        return time.get("timeInSeconds").asLong() * 1_000_000_000L + time.path("offsetInNanos").asLong(0);
    }

    /** A time series by ID: an asset property's, or a disassociated stream's. */
    private ObjectNode series(String account, String region, String id) {
        return store.get(account, region, SiteWiseStore.TIME_SERIES, id).orElse(null);
    }

    ObjectNode batchAssociateDataSegmentsToDataset(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode dataset = workspaceDataset(account, region, in);
        ArrayNode segments = (ArrayNode) meta(dataset).withArray("segments");
        ArrayNode failed = mapper.createArrayNode();
        for (JsonNode entry : in.get("associateDataSegmentEntries")) {
            String error = null;
            String code = null;
            String sourceId = entry.get("sourceDatasetId").asText();
            ObjectNode source = store.get(account, region, DATASET, sourceId).orElse(null);
            ObjectNode series = series(account, region, entry.get("timeSeriesId").asText());
            if (source == null) {
                code = "RESOURCE_NOT_FOUND";
                error = "Dataset " + sourceId + " not found.";
            } else if (series == null) {
                code = "RESOURCE_NOT_FOUND";
                error = "Time series " + entry.get("timeSeriesId").asText() + " not found.";
            } else if (nanos(entry.get("startTimestamp")) >= nanos(entry.get("endTimestamp"))) {
                code = "VALIDATION_ERROR";
                error = "A segment starts before it ends.";
            }
            if (error != null) {
                ObjectNode failure = entry.deepCopy();
                failure.put("errorCode", code).put("errorMessage", error);
                failed.add(failure);
                continue;
            }
            ObjectNode segment = entry.deepCopy();
            segment.put("alias", series.path("alias").asText(series.get("timeSeriesId").asText()));
            segment.put("dataType", series.get("dataType").asText());
            segment.putObject("enrichment").put("status", "NOT_ENRICHED");
            segments.add(segment);
        }
        bumpDataset(dataset);
        store.put(account, region, DATASET, dataset.get("datasetId").asText(), dataset);
        ObjectNode out = mapper.createObjectNode().put("datasetId", dataset.get("datasetId").asText())
                .put("datasetVersion", dataset.get("datasetVersion").asText());
        out.set("failedAssociations", failed);
        return out;
    }

    private ObjectNode removeSegments(ObjectNode in, String region, String entriesName, boolean bySource,
                                      String failedName) {
        String account = store.account();
        ObjectNode dataset = workspaceDataset(account, region, in);
        ArrayNode segments = (ArrayNode) meta(dataset).withArray("segments");
        ArrayNode failed = mapper.createArrayNode();
        for (JsonNode entry : in.get(entriesName)) {
            boolean removed = false;
            for (int i = segments.size() - 1; i >= 0; i--) {
                JsonNode segment = segments.get(i);
                if (segment.get("timeSeriesId").asText().equals(entry.get("timeSeriesId").asText())
                        && nanos(segment.get("startTimestamp")) == nanos(entry.get("startTimestamp"))
                        && nanos(segment.get("endTimestamp")) == nanos(entry.get("endTimestamp"))
                        && (!bySource || segment.get("sourceDatasetId").asText().equals(entry.get("sourceDatasetId")
                        .asText()))) {
                    segments.remove(i);
                    removed = true;
                }
            }
            if (!removed) {
                ObjectNode failure = entry.deepCopy();
                failure.put("errorCode", "RESOURCE_NOT_FOUND").put("errorMessage", "The dataset has no such segment.");
                failed.add(failure);
            }
        }
        bumpDataset(dataset);
        store.put(account, region, DATASET, dataset.get("datasetId").asText(), dataset);
        ObjectNode out = mapper.createObjectNode().put("datasetId", dataset.get("datasetId").asText())
                .put("datasetVersion", dataset.get("datasetVersion").asText());
        out.set(failedName, failed);
        return out;
    }

    ObjectNode batchDeleteDatasetDataSegments(ObjectNode in, String region) {
        return removeSegments(in, region, "deleteDataSegmentEntries", false, "errors");
    }

    ObjectNode batchDisassociateDataSegmentsFromDataset(ObjectNode in, String region) {
        return removeSegments(in, region, "disassociateDataSegmentEntries", true, "failedDisassociations");
    }

    ObjectNode listDatasetDataSegments(ObjectNode in, String region) {
        ObjectNode dataset = workspaceDataset(store.account(), region, in);
        List<JsonNode> segments = new ArrayList<>();
        meta(dataset).path("segments").forEach(segments::add);
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(segments, in, 50, out, "dataSegments");
        return out;
    }

    ObjectNode listDatasetDataSegmentRelationships(ObjectNode in, String region) {
        ObjectNode dataset = workspaceDataset(store.account(), region, in);
        List<ObjectNode> relationships = new ArrayList<>();
        for (JsonNode segment : meta(dataset).path("segments")) {
            relationships.add(mapper.createObjectNode().put("targetDatasetId", dataset.get("datasetId").asText())
                    .put("sourceDatasetId", segment.get("sourceDatasetId").asText())
                    .put("timeSeriesId", segment.get("timeSeriesId").asText())
                    .<ObjectNode>set("startTimestamp", segment.get("startTimestamp").deepCopy())
                    .set("endTimestamp", segment.get("endTimestamp").deepCopy()));
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(relationships, in, 50, out, "dataSegmentRelationshipSummaries");
        return out;
    }

    // ---------------------------------------------------------------- searches

    ObjectNode startSearch(ObjectNode in, String region) {
        String account = store.account();
        String workspace = requireWorkspace(account, region, in.get("workspaceName").asText()).get("workspaceName")
                .asText();
        String id = SiteWiseStore.uuid();
        String recordId = workspace + "/search/" + id;
        ObjectNode search = newChild(workspace, "search");
        meta(search).put("id", recordId);
        search.put("searchId", id);
        search.put("workspaceName", workspace);
        search.put("status", "QUEUED");
        search.put("queryStatement", in.get("queryStatement").asText());
        search.put("searchType", in.path("searchType").asText("QUICK"));
        search.put("startedAt", SiteWiseStore.now());
        if (in.hasNonNull("groupId")) {
            search.put("groupId", in.get("groupId").asText());
        }
        meta(search).set("filters", in.has("searchFilters") ? in.get("searchFilters").deepCopy()
                : mapper.createObjectNode());
        put(account, region, recordId, search);
        runner.submit(() -> RequestScopes.runAs(account, region, () -> runSearch(account, region, recordId)));
        ObjectNode out = mapper.createObjectNode().put("searchId", id).put("workspaceName", workspace)
                .put("status", "QUEUED");
        if (in.hasNonNull("groupId")) {
            out.put("groupId", in.get("groupId").asText());
        }
        return out;
    }

    /**
     * A search scores each time series in the filters (or every one) by how many of the statement's words its alias,
     * asset and property names hold, and finds the window of each matching series where its values are highest.
     */
    private void runSearch(String account, String region, String recordId) {
        ObjectNode search = get(account, region, recordId);
        if (search == null) {
            return;
        }
        search.put("status", "RUNNING");
        put(account, region, recordId, search);
        Set<String> words = new HashSet<>();
        for (String word : search.get("queryStatement").asText().toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() > 2) {
                words.add(word);
            }
        }
        JsonNode filters = meta(search).path("filters");
        Set<String> seriesFilter = new HashSet<>();
        filters.path("timeSeriesIds").forEach(id -> seriesFilter.add(id.asText()));
        Set<String> datasetFilter = new HashSet<>();
        filters.path("datasetIds").forEach(id -> datasetFilter.add(id.asText()));
        Map<String, String> datasetOf = new LinkedHashMap<>();
        for (ObjectNode dataset : store.list(account, region, DATASET)) {
            if (!datasetFilter.isEmpty() && !datasetFilter.contains(dataset.get("datasetId").asText())) {
                continue;
            }
            meta(dataset).path("segments").forEach(s -> datasetOf.putIfAbsent(s.get("timeSeriesId").asText(),
                    dataset.get("datasetId").asText()));
        }
        ArrayNode results = mapper.createArrayNode();
        for (ObjectNode series : store.list(account, region, SiteWiseStore.TIME_SERIES)) {
            String id = series.get("timeSeriesId").asText();
            if (!seriesFilter.isEmpty() && !seriesFilter.contains(id) || !datasetOf.containsKey(id)) {
                continue;
            }
            String text = (series.path("alias").asText("") + " " + names(account, region, series))
                    .toLowerCase(Locale.ROOT);
            long hits = words.stream().filter(text::contains).count();
            if (hits == 0) {
                continue;
            }
            List<JsonNode> points = data.get().points(account, region, id);
            if (points.isEmpty()) {
                continue;
            }
            JsonNode top = points.get(0);
            for (JsonNode point : points) {
                Object value = SiteWiseData.number(point.get("v"));
                Object best = SiteWiseData.number(top.get("v"));
                if (value instanceof Double v && (!(best instanceof Double b) || v > b)) {
                    top = point;
                }
            }
            ObjectNode result = results.addObject();
            result.put("searchId", search.get("searchId").asText());
            result.put("workspaceName", search.get("workspaceName").asText());
            result.put("datasetId", datasetOf.get(id));
            result.put("timeSeriesId", id);
            result.set("startTimestamp", stamp(points.get(0)));
            result.set("endTimestamp", stamp(points.get(points.size() - 1)));
            result.set("topTimestamp", stamp(top));
            result.put("score", (float) hits / Math.max(1, words.size()));
        }
        search = get(account, region, recordId);
        if (search == null) {
            return;
        }
        meta(search).set("results", results);
        search.put("status", "SUCCEEDED");
        put(account, region, recordId, search);
    }

    private String names(String account, String region, JsonNode series) {
        if (!series.hasNonNull("assetId")) {
            return "";
        }
        ObjectNode asset = store.get(account, region, SiteWiseStore.ASSET, series.get("assetId").asText()).orElse(null);
        if (asset == null) {
            return "";
        }
        StringBuilder names = new StringBuilder(asset.get("assetName").asText());
        for (ObjectNode property : SiteWiseAssets.properties(asset)) {
            if (property.get("id").asText().equals(series.get("propertyId").asText())) {
                names.append(' ').append(property.get("name").asText());
            }
        }
        return names.toString();
    }

    private ObjectNode stamp(JsonNode point) {
        return mapper.createObjectNode().put("timeInSeconds", point.get("t").asLong())
                .put("offsetInNanos", point.get("n").asInt());
    }

    ObjectNode describeSearch(ObjectNode in, String region) {
        return SiteWiseStore.view(child(store.account(), region, in.get("workspaceName").asText(), "search",
                in.get("searchId").asText(), "Search"));
    }

    ObjectNode getSearchResults(ObjectNode in, String region) {
        ObjectNode search = child(store.account(), region, in.get("workspaceName").asText(), "search",
                in.get("searchId").asText(), "Search");
        List<JsonNode> results = new ArrayList<>();
        meta(search).path("results").forEach(results::add);
        results.sort((a, b) -> Float.compare(b.get("score").floatValue(), a.get("score").floatValue()));
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(results, in, 100, out, "searchResults");
        return out;
    }

    ObjectNode listSearches(ObjectNode in, String region) {
        String account = store.account();
        String workspace = requireWorkspace(account, region, in.get("workspaceName").asText()).get("workspaceName")
                .asText();
        JsonNode filters = in.path("listSearchesFilters");
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode search : list(account, region, workspace, "search")) {
            if (!matches(filters.path("statusFilter"), search.get("status").asText())
                    || !matches(filters.path("searchTypeFilter"), search.get("searchType").asText())
                    || !matches(filters.path("groupIdFilter"), search.path("groupId").asText(""))) {
                continue;
            }
            double started = search.get("startedAt").asDouble();
            if (filters.has("startedAfter") && started < SiteWiseData.date(filters.get("startedAfter"), 0)
                    || filters.has("startedBefore") && started > SiteWiseData.date(filters.get("startedBefore"), 0)) {
                continue;
            }
            summaries.add(SiteWiseStore.view(search));
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 100, out, "searchSummaries");
        return out;
    }

    private static boolean matches(JsonNode filter, String value) {
        if (!filter.isArray() || filter.isEmpty()) {
            return true;
        }
        for (JsonNode allowed : filter) {
            if (allowed.asText().equals(value)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- enrichment

    ObjectNode createEnrichmentJob(ObjectNode in, String region) {
        String account = store.account();
        String workspace = requireWorkspace(account, region, in.get("workspaceName").asText()).get("workspaceName")
                .asText();
        JsonNode detection = in.path("jobConfiguration").get("eventDetection");
        if (detection == null) {
            throw invalid("An enrichment job's configuration is eventDetection.");
        }
        ObjectNode dataset = requireDataset(account, region, detection.get("datasetId").asText());
        String id = SiteWiseStore.uuid();
        String recordId = workspace + "/enrichment-job/" + id;
        ObjectNode job = newChild(workspace, "enrichment-job");
        meta(job).put("id", recordId);
        job.put("jobId", id);
        job.put("status", "PENDING");
        job.put("workspaceName", workspace);
        job.put("jobType", "EVENT_DETECTION");
        job.set("jobConfiguration", in.get("jobConfiguration").deepCopy());
        job.put("createdAt", SiteWiseStore.now());
        put(account, region, recordId, job);
        String datasetId = dataset.get("datasetId").asText();
        runner.submit(() -> RequestScopes.runAs(account, region, () -> runEnrichment(account, region, recordId,
                datasetId)));
        return mapper.createObjectNode().put("jobId", id).put("status", "PENDING")
                .put("createdAt", job.get("createdAt").asDouble());
    }

    /** Event detection marks the dataset's segments in the trim range enriched. */
    private void runEnrichment(String account, String region, String recordId, String datasetId) {
        ObjectNode job = get(account, region, recordId);
        if (job == null || !"PENDING".equals(job.get("status").asText())) {
            return;
        }
        job.put("status", "RUNNING");
        job.put("updatedAt", SiteWiseStore.now());
        put(account, region, recordId, job);
        JsonNode detection = job.get("jobConfiguration").get("eventDetection");
        long start = nanos(detection.get("trimSettings").get("startTime"));
        long end = nanos(detection.get("trimSettings").get("endTime"));
        String series = detection.path("timeSeriesId").asText(null);
        String alias = detection.path("propertyAlias").asText(null);
        ObjectNode dataset = store.get(account, region, DATASET, datasetId).orElse(null);
        int enriched = 0;
        int total = 0;
        if (dataset != null) {
            for (JsonNode segment : meta(dataset).path("segments")) {
                total++;
                boolean match = (series == null || series.equals(segment.get("timeSeriesId").asText()))
                        && (alias == null || alias.equals(segment.path("alias").asText()))
                        && nanos(segment.get("startTimestamp")) < end && nanos(segment.get("endTimestamp")) > start;
                if (match) {
                    ((ObjectNode) segment).putObject("enrichment").put("status", "ENRICHED")
                            .put("lastEnrichedAt", SiteWiseStore.now());
                }
                if ("ENRICHED".equals(segment.path("enrichment").path("status").asText())) {
                    enriched++;
                }
            }
            ObjectNode video = dataset.with("enrichmentStatus").putObject("video");
            video.put("status", enriched == 0 ? "NOT_ENRICHED" : enriched == total ? "FULLY_ENRICHED"
                    : "PARTIALLY_ENRICHED");
            if (enriched > 0) {
                video.put("lastEnrichedAt", SiteWiseStore.now());
            }
            store.put(account, region, DATASET, datasetId, dataset);
        }
        job = get(account, region, recordId);
        if (job == null || "CANCELLED".equals(job.get("status").asText())) {
            return;
        }
        job.put("status", dataset == null ? "FAILED" : "COMPLETED");
        if (dataset == null) {
            job.put("failureMessage", "Dataset " + datasetId + " was deleted.");
        }
        job.put("completedAt", SiteWiseStore.now());
        job.put("updatedAt", SiteWiseStore.now());
        put(account, region, recordId, job);
    }

    ObjectNode describeEnrichmentJob(ObjectNode in, String region) {
        return SiteWiseStore.view(child(store.account(), region, in.get("workspaceName").asText(), "enrichment-job",
                in.get("jobId").asText(), "Enrichment job"));
    }

    ObjectNode cancelEnrichmentJob(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode job = child(account, region, in.get("workspaceName").asText(), "enrichment-job",
                in.get("jobId").asText(), "Enrichment job");
        if (!Set.of("PENDING", "RUNNING").contains(job.get("status").asText())) {
            throw SiteWiseStore.conflicting("Enrichment job " + in.get("jobId").asText() + " is "
                    + job.get("status").asText() + ".", in.get("jobId").asText(),
                    arn(account, region, meta(job).get("id").asText()));
        }
        job.put("status", "CANCELLED");
        job.put("cancelledAt", SiteWiseStore.now());
        put(account, region, meta(job).get("id").asText(), job);
        return mapper.createObjectNode().put("jobId", job.get("jobId").asText()).put("status", "CANCELLED");
    }

    ObjectNode listEnrichmentJobs(ObjectNode in, String region) {
        String account = store.account();
        String workspace = requireWorkspace(account, region, in.get("workspaceName").asText()).get("workspaceName")
                .asText();
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode job : list(account, region, workspace, "enrichment-job")) {
            JsonNode detection = job.get("jobConfiguration").get("eventDetection");
            ObjectNode summary = mapper.createObjectNode().put("jobId", job.get("jobId").asText())
                    .put("status", job.get("status").asText()).put("workspaceName", workspace)
                    .put("jobType", "EVENT_DETECTION").put("datasetId", detection.get("datasetId").asText())
                    .put("createdAt", job.get("createdAt").asDouble());
            if (detection.hasNonNull("propertyAlias")) {
                summary.put("propertyAlias", detection.get("propertyAlias").asText());
            }
            if (detection.hasNonNull("timeSeriesId")) {
                summary.put("timeSeriesId", detection.get("timeSeriesId").asText());
            }
            if (job.has("updatedAt")) {
                summary.put("updatedAt", job.get("updatedAt").asDouble());
            }
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "jobs");
        return out;
    }

    // ---------------------------------------------------------------- exports and capture data

    private static String[] s3Uri(String uri) {
        if (!uri.startsWith("s3://")) {
            throw invalid("The location " + uri + " isn't an s3:// URI.");
        }
        String rest = uri.substring(5);
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        String prefix = slash < 0 ? "" : rest.substring(slash + 1);
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        return new String[] {bucket, prefix};
    }

    ObjectNode createDatasetExportJob(ObjectNode in, String region) {
        String account = store.account();
        String workspace = requireWorkspace(account, region, in.get("workspaceName").asText()).get("workspaceName")
                .asText();
        String[] destination = s3Uri(in.get("destinationS3Uri").asText());
        if (!RequestScopes.callAs(account, region, () -> s3.get().bucketExists(destination[0]))) {
            throw invalid("The bucket " + destination[0] + " doesn't exist.");
        }
        s3Uri(in.get("errorReportLocation").get("s3Uri").asText());
        JsonNode input = in.get("input");
        if (input.has("dataset")) {
            requireDataset(account, region, input.get("dataset").get("datasetId").asText());
        } else if (!input.has("timeseries")) {
            throw invalid("An export's input is timeseries or a dataset.");
        }
        String id = SiteWiseStore.uuid();
        String recordId = workspace + "/dataset-export-job/" + id;
        ObjectNode job = newChild(workspace, "dataset-export-job");
        meta(job).put("id", recordId);
        job.put("jobId", id);
        job.put("workspaceName", workspace);
        job.put("status", "SUBMITTED");
        job.put("startedAt", SiteWiseStore.now());
        job.put("destinationS3Uri", in.get("destinationS3Uri").asText());
        job.set("errorReportLocation", in.get("errorReportLocation").deepCopy());
        job.set("input", input.deepCopy());
        put(account, region, recordId, job);
        runner.submit(() -> RequestScopes.runAs(account, region, () -> runExport(account, region, recordId)));
        return mapper.createObjectNode().put("jobId", id).put("workspaceName", workspace);
    }

    /** Writes each exported time series as CSV ({@code time_in_nanos,quality,value}) under the destination. */
    private void runExport(String account, String region, String recordId) {
        ObjectNode job = get(account, region, recordId);
        if (job == null) {
            return;
        }
        job.put("status", "RUNNING");
        put(account, region, recordId, job);
        String[] destination = s3Uri(job.get("destinationS3Uri").asText());
        List<String[]> items = new ArrayList<>();
        JsonNode input = job.get("input");
        if (input.has("dataset")) {
            ObjectNode dataset = store.get(account, region, DATASET, input.get("dataset").get("datasetId").asText())
                    .orElse(null);
            if (dataset != null) {
                meta(dataset).path("segments").forEach(s -> items.add(new String[] {s.get("timeSeriesId").asText(),
                        String.valueOf(nanos(s.get("startTimestamp"))), String.valueOf(nanos(s.get("endTimestamp")))}));
            }
        } else {
            for (JsonNode item : input.get("timeseries")) {
                String id = item.path("timeSeriesId").asText(null);
                if (id == null && item.hasNonNull("propertyAlias")) {
                    SiteWiseData.Stream stream = data.get().stream(account, region,
                            mapper.createObjectNode().put("propertyAlias", item.get("propertyAlias").asText()), false);
                    id = stream == null ? null : stream.timeSeriesId();
                }
                JsonNode trim = item.path("trimSettings");
                items.add(new String[] {id, trim.has("startTime") ? String.valueOf(nanos(trim.get("startTime"))) : "0",
                        trim.has("endTime") ? String.valueOf(nanos(trim.get("endTime"))) : String.valueOf(Long.MAX_VALUE)});
            }
        }
        StringBuilder errors = new StringBuilder();
        int written = 0;
        for (String[] item : items) {
            if (item[0] == null || series(account, region, item[0]) == null) {
                errors.append(item[0]).append(",The time series doesn't exist.\n");
                continue;
            }
            long from = Long.parseLong(item[1]);
            long to = Long.parseLong(item[2]);
            StringBuilder csv = new StringBuilder("time_in_nanos,quality,value\n");
            for (JsonNode point : data.get().points(account, region, item[0])) {
                long at = SiteWiseData.nanos(point);
                if (at < from || at > to) {
                    continue;
                }
                String value = null;
                for (JsonNode field : point.get("v")) {
                    if (field.isValueNode() && !field.isNull()) {
                        value = field.asText();
                    }
                }
                csv.append(at).append(',').append(point.path("q").asText("GOOD")).append(',')
                        .append(value == null ? "" : "\"" + value.replace("\"", "\"\"") + "\"").append('\n');
            }
            s3.get().putObject(destination[0], destination[1] + job.get("jobId").asText() + "/" + item[0] + ".csv",
                    csv.toString().getBytes(StandardCharsets.UTF_8), "text/csv", Map.of());
            written++;
        }
        if (!errors.isEmpty()) {
            String[] location = s3Uri(job.get("errorReportLocation").get("s3Uri").asText());
            try {
                s3.get().putObject(location[0], location[1] + job.get("jobId").asText() + "/errors.csv",
                        errors.toString().getBytes(StandardCharsets.UTF_8), "text/csv", Map.of());
            } catch (RuntimeException e) {
                LOG.debugv(e, "IoT SiteWise could not write the export error report");
            }
        }
        job = get(account, region, recordId);
        if (job == null) {
            return;
        }
        job.put("status", written == 0 && !items.isEmpty() ? "FAILED" : errors.isEmpty() ? "COMPLETED"
                : "COMPLETED_WITH_ERRORS");
        job.put("completedAt", SiteWiseStore.now());
        put(account, region, recordId, job);
    }

    ObjectNode describeDatasetExportJob(ObjectNode in, String region) {
        return SiteWiseStore.view(child(store.account(), region, in.get("workspaceName").asText(),
                "dataset-export-job", in.get("jobId").asText(), "Dataset export job"));
    }

    ObjectNode listDatasetExportJobs(ObjectNode in, String region) {
        String account = store.account();
        String workspace = requireWorkspace(account, region, in.get("workspaceName").asText()).get("workspaceName")
                .asText();
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode job : list(account, region, workspace, "dataset-export-job")) {
            ObjectNode summary = mapper.createObjectNode().put("jobId", job.get("jobId").asText())
                    .put("status", job.get("status").asText()).put("startedAt", job.get("startedAt").asDouble())
                    .put("destinationS3Uri", job.get("destinationS3Uri").asText());
            if (job.has("completedAt")) {
                summary.put("completedAt", job.get("completedAt").asDouble());
            }
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "jobs");
        return out;
    }

    /**
     * A VIDEO time series' clips: each value is a clip's S3 location, imported from an MP4 file; this returns the clip
     * that covers the requested start, with the next clip's start as the token.
     */
    ObjectNode getCaptureData(ObjectNode in, String region) {
        String account = store.account();
        requireWorkspace(account, region, in.get("workspaceName").asText());
        String id = in.path("timeSeriesId").asText(null);
        if (id == null && in.hasNonNull("propertyAlias")) {
            SiteWiseData.Stream stream = data.get().stream(account, region,
                    mapper.createObjectNode().put("propertyAlias", in.get("propertyAlias").asText()), false);
            id = stream == null ? null : stream.timeSeriesId();
        }
        ObjectNode series = id == null ? null : series(account, region, id);
        if (series == null) {
            throw notFound("The time series doesn't exist.");
        }
        if (!"VIDEO".equals(series.get("dataType").asText())) {
            throw invalid("Capture data comes from VIDEO time series.");
        }
        long start = in.hasNonNull("nextToken")
                ? Long.parseLong(new String(Base64.getUrlDecoder().decode(in.get("nextToken").asText()),
                StandardCharsets.UTF_8)) : nanos(in.get("startTime"));
        long end = nanos(in.get("endTime"));
        List<JsonNode> clips = new ArrayList<>();
        for (JsonNode point : data.get().points(account, region, id)) {
            if (SiteWiseData.nanos(point) <= end) {
                clips.add(point);
            }
        }
        JsonNode clip = null;
        JsonNode next = null;
        for (int i = 0; i < clips.size(); i++) {
            long at = SiteWiseData.nanos(clips.get(i));
            long until = i + 1 < clips.size() ? SiteWiseData.nanos(clips.get(i + 1)) : Long.MAX_VALUE;
            if (at <= start && start < until || at >= start && clip == null) {
                clip = clips.get(i);
                next = i + 1 < clips.size() ? clips.get(i + 1) : null;
                break;
            }
        }
        if (clip == null) {
            throw notFound("No capture data in that time range.");
        }
        String[] location = s3Uri(clip.get("v").get("stringValue").asText());
        String bucket = location[0];
        String key = location[1].endsWith("/") ? location[1].substring(0, location[1].length() - 1) : location[1];
        byte[] bytes = s3.get().getObject(bucket, key).getData();
        ObjectNode out = mapper.createObjectNode();
        out.put("data", Base64.getEncoder().encodeToString(bytes));
        out.set("startTime", stamp(clip));
        long clipEnd = next == null ? end : Math.min(end, SiteWiseData.nanos(next));
        out.putObject("endTime").put("timeInSeconds", Math.floorDiv(clipEnd, 1_000_000_000L))
                .put("offsetInNanos", (int) Math.floorMod(clipEnd, 1_000_000_000L));
        out.put("dataType", "VIDEO-MP4");
        if (next != null && SiteWiseData.nanos(next) <= end) {
            out.put("nextToken", Base64.getUrlEncoder().withoutPadding().encodeToString(
                    String.valueOf(SiteWiseData.nanos(next)).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }
}
