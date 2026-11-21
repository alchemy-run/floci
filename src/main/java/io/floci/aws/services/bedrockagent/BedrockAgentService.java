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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bedrock Agents: agents, their numbered versions, aliases, action groups,
 * knowledge base associations, and the tags on agents and aliases.
 * <p>
 * State follows Bedrock's rules, settled at once: a new or updated agent is
 * {@code NOT_PREPARED} until {@code PrepareAgent} makes it {@code PREPARED};
 * an alias created without a routing configuration snapshots the draft as the
 * next numbered version and points at it; an alias is {@code PREPARED}; an
 * agent with aliases can't be deleted unless {@code skipResourceInUseCheck}
 * says so. Action groups and knowledge bases attach to the {@code DRAFT}
 * version and are copied into each snapshot.
 */
@ApplicationScoped
public class BedrockAgentService {
    static final String DRAFT = "DRAFT";
    private static final String ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int DEFAULT_IDLE_TTL = 600;

    private final StorageBackend<String, ObjectNode> agents;
    private final StorageBackend<String, ObjectNode> tags;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public BedrockAgentService(StorageFactory storageFactory, RegionResolver regionResolver,
                               ObjectMapper objectMapper) {
        this.agents = storageFactory.create("bedrock-agent", "bedrock-agents.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.tags = storageFactory.create("bedrock-agent", "bedrock-agent-tags.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ── Agents ─────────────────────────────────────────────────────────────

    public synchronized ObjectNode createAgent(JsonNode request, String region) {
        String name = requireName(request, "agentName");
        for (ObjectNode stored : agents.scan(k -> k.startsWith(region + "/"))) {
            if (name.equals(stored.path("agent").path("agentName").asText())) {
                throw new AwsException("ConflictException", "An agent named " + name + " already exists.", 409);
            }
        }
        String id = newId();
        String now = now();
        ObjectNode agent = objectMapper.createObjectNode();
        agent.put("agentId", id);
        agent.put("agentName", name);
        agent.put("agentArn", regionResolver.buildArn("bedrock", region, "agent/" + id));
        agent.put("agentVersion", DRAFT);
        agent.put("agentStatus", "NOT_PREPARED");
        agent.put("idleSessionTTLInSeconds", request.path("idleSessionTTLInSeconds").asInt(DEFAULT_IDLE_TTL));
        agent.put("agentResourceRoleArn", text(request, "agentResourceRoleArn") == null
                ? "" : text(request, "agentResourceRoleArn"));
        agent.put("orchestrationType", text(request, "orchestrationType") == null
                ? "DEFAULT" : text(request, "orchestrationType"));
        agent.put("agentCollaboration", text(request, "agentCollaboration") == null
                ? "DISABLED" : text(request, "agentCollaboration"));
        copy(request, agent, "clientToken", "instruction", "foundationModel", "description", "customerEncryptionKeyArn",
                "promptOverrideConfiguration", "guardrailConfiguration", "memoryConfiguration", "customOrchestration");
        agent.put("createdAt", now);
        agent.put("updatedAt", now);
        ObjectNode stored = objectMapper.createObjectNode();
        stored.set("agent", agent);
        stored.putObject("versions");
        stored.putObject("aliases");
        stored.set("draft", components());
        agents.put(key(region, id), stored);
        putTags(agent.path("agentArn").asText(), request.get("tags"));
        return wrap("agent", agent);
    }

    public ObjectNode getAgent(String agentId, String region) {
        return wrap("agent", requireAgent(agentId, region).get("agent"));
    }

    public synchronized ObjectNode updateAgent(String agentId, JsonNode request, String region) {
        ObjectNode stored = requireAgent(agentId, region);
        ObjectNode agent = (ObjectNode) stored.get("agent");
        agent.put("agentName", requireName(request, "agentName"));
        agent.put("foundationModel", requireText(request, "foundationModel"));
        agent.put("agentResourceRoleArn", requireText(request, "agentResourceRoleArn"));
        for (String field : List.of("instruction", "description", "customerEncryptionKeyArn",
                "promptOverrideConfiguration", "guardrailConfiguration", "memoryConfiguration",
                "agentCollaboration", "orchestrationType", "customOrchestration")) {
            if (request.has(field)) {
                agent.set(field, request.get(field).deepCopy());
            } else if (!"agentCollaboration".equals(field) && !"orchestrationType".equals(field)) {
                agent.remove(field);
            }
        }
        if (request.has("idleSessionTTLInSeconds")) {
            agent.put("idleSessionTTLInSeconds", request.get("idleSessionTTLInSeconds").asInt());
        }
        agent.put("agentStatus", "NOT_PREPARED");
        agent.put("updatedAt", now());
        agents.put(key(region, agentId), stored);
        return wrap("agent", agent);
    }

    public synchronized ObjectNode prepareAgent(String agentId, String region) {
        ObjectNode stored = requireAgent(agentId, region);
        ObjectNode agent = (ObjectNode) stored.get("agent");
        if (text(agent, "foundationModel") == null || agent.path("agentResourceRoleArn").asText().isEmpty()) {
            throw validation("An agent needs a foundationModel and an agentResourceRoleArn to be prepared.");
        }
        String now = now();
        agent.put("agentStatus", "PREPARED");
        agent.put("preparedAt", now);
        agent.put("updatedAt", now);
        agents.put(key(region, agentId), stored);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("agentId", agentId);
        response.put("agentStatus", "PREPARING");
        response.put("agentVersion", DRAFT);
        response.put("preparedAt", now);
        return response;
    }

    public synchronized ObjectNode deleteAgent(String agentId, boolean skipInUseCheck, String region) {
        ObjectNode stored = requireAgent(agentId, region);
        if (!skipInUseCheck && !stored.path("aliases").isEmpty()) {
            throw new AwsException("ConflictException",
                    "Agent " + agentId + " has aliases; delete them or set skipResourceInUseCheck.", 409);
        }
        agents.delete(key(region, agentId));
        tags.delete(stored.path("agent").path("agentArn").asText());
        stored.path("aliases").forEach(alias -> tags.delete(alias.path("agentAliasArn").asText()));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("agentId", agentId);
        response.put("agentStatus", "DELETING");
        return response;
    }

    public ObjectNode listAgents(JsonNode request, String region) {
        List<ObjectNode> all = agents.scan(k -> k.startsWith(region + "/")).stream()
                .sorted(Comparator.comparing((ObjectNode s) -> s.path("agent").path("createdAt").asText()))
                .toList();
        Page page = page(all.size(), request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("agentSummaries");
        for (ObjectNode stored : all.subList(page.start(), page.end())) {
            JsonNode agent = stored.get("agent");
            ObjectNode summary = list.addObject();
            summary.put("agentId", agent.path("agentId").asText());
            summary.put("agentName", agent.path("agentName").asText());
            summary.put("agentStatus", agent.path("agentStatus").asText());
            copy(agent, summary, "description", "guardrailConfiguration");
            summary.put("updatedAt", agent.path("updatedAt").asText());
            String latest = latestVersion(stored);
            if (latest != null) {
                summary.put("latestAgentVersion", latest);
            }
        }
        page.next(response);
        return response;
    }

    // ── Versions ───────────────────────────────────────────────────────────

    public ObjectNode getAgentVersion(String agentId, String version, String region) {
        ObjectNode stored = requireAgent(agentId, region);
        return wrap("agentVersion", version(stored, version, agentId));
    }

    public ObjectNode listAgentVersions(String agentId, JsonNode request, String region) {
        ObjectNode stored = requireAgent(agentId, region);
        List<JsonNode> versions = new ArrayList<>();
        versions.add(draftVersion(stored));
        stored.path("versions").forEach(versions::add);
        Page page = page(versions.size(), request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("agentVersionSummaries");
        for (JsonNode version : versions.subList(page.start(), page.end())) {
            ObjectNode summary = list.addObject();
            summary.put("agentName", version.path("agentName").asText());
            summary.put("agentStatus", version.path("agentStatus").asText());
            summary.put("agentVersion", version.path("version").asText());
            summary.put("createdAt", version.path("createdAt").asText());
            summary.put("updatedAt", version.path("updatedAt").asText());
            copy(version, summary, "description", "guardrailConfiguration");
        }
        page.next(response);
        return response;
    }

    public synchronized ObjectNode deleteAgentVersion(String agentId, String version, boolean skipInUseCheck,
                                                      String region) {
        ObjectNode stored = requireAgent(agentId, region);
        if (DRAFT.equals(version) || !stored.path("versions").has(version)) {
            throw new AwsException("ResourceNotFoundException", "Agent version " + version + " was not found.", 404);
        }
        boolean routed = false;
        for (JsonNode alias : stored.path("aliases")) {
            for (JsonNode route : alias.path("routingConfiguration")) {
                routed |= version.equals(route.path("agentVersion").asText());
            }
        }
        if (routed && !skipInUseCheck) {
            throw new AwsException("ConflictException", "Agent version " + version + " is used by an alias.", 409);
        }
        ((ObjectNode) stored.get("versions")).remove(version);
        agents.put(key(region, agentId), stored);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("agentId", agentId);
        response.put("agentVersion", version);
        response.put("agentStatus", "DELETING");
        return response;
    }

    private ObjectNode draftVersion(ObjectNode stored) {
        ObjectNode agent = (ObjectNode) stored.get("agent");
        ObjectNode version = objectMapper.createObjectNode();
        for (String field : List.of("agentId", "agentName", "agentArn", "instruction", "agentStatus", "foundationModel",
                "description", "idleSessionTTLInSeconds", "agentResourceRoleArn", "customerEncryptionKeyArn",
                "createdAt", "updatedAt", "promptOverrideConfiguration", "guardrailConfiguration",
                "memoryConfiguration", "agentCollaboration")) {
            if (agent.has(field)) {
                version.set(field, agent.get(field).deepCopy());
            }
        }
        version.put("version", DRAFT);
        return version;
    }

    private JsonNode version(ObjectNode stored, String version, String agentId) {
        if (DRAFT.equals(version)) {
            return draftVersion(stored);
        }
        JsonNode found = stored.path("versions").get(version);
        if (found == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Agent " + agentId + " has no version " + version + ".", 404);
        }
        return found;
    }

    /** Snapshots the draft (agent and components) as the next numbered version. */
    private String snapshot(ObjectNode stored) {
        ObjectNode versions = (ObjectNode) stored.get("versions");
        int next = 1;
        for (Iterator<String> names = versions.fieldNames(); names.hasNext(); ) {
            next = Math.max(next, Integer.parseInt(names.next()) + 1);
        }
        String number = Integer.toString(next);
        ObjectNode version = draftVersion(stored);
        version.put("version", number);
        version.put("agentStatus", "PREPARED");
        String now = now();
        version.put("createdAt", now);
        version.put("updatedAt", now);
        version.set("components", stored.get("draft").deepCopy());
        versions.set(number, version);
        return number;
    }

    private static String latestVersion(ObjectNode stored) {
        int latest = 0;
        for (Iterator<String> names = stored.path("versions").fieldNames(); names.hasNext(); ) {
            latest = Math.max(latest, Integer.parseInt(names.next()));
        }
        return latest == 0 ? null : Integer.toString(latest);
    }

    // ── Aliases ────────────────────────────────────────────────────────────

    public synchronized ObjectNode createAgentAlias(String agentId, JsonNode request, String region) {
        ObjectNode stored = requireAgent(agentId, region);
        String name = requireName(request, "agentAliasName");
        for (JsonNode alias : stored.path("aliases")) {
            if (name.equals(alias.path("agentAliasName").asText())) {
                throw new AwsException("ConflictException", "An alias named " + name + " already exists.", 409);
            }
        }
        JsonNode routing = request.get("routingConfiguration");
        ArrayNode routes;
        if (routing == null || !routing.isArray() || routing.isEmpty()) {
            if (!"PREPARED".equals(stored.path("agent").path("agentStatus").asText())) {
                throw validation("Prepare agent " + agentId + " before creating an alias without a routing "
                        + "configuration.");
            }
            routes = objectMapper.createArrayNode();
            routes.addObject().put("agentVersion", snapshot(stored));
        } else {
            routes = (ArrayNode) routing.deepCopy();
            for (JsonNode route : routes) {
                version(stored, route.path("agentVersion").asText(), agentId);
            }
        }
        String id = newId();
        String now = now();
        ObjectNode alias = objectMapper.createObjectNode();
        alias.put("agentId", agentId);
        alias.put("agentAliasId", id);
        alias.put("agentAliasName", name);
        alias.put("agentAliasArn", regionResolver.buildArn("bedrock", region, "agent-alias/" + agentId + "/" + id));
        copy(request, alias, "clientToken", "description");
        alias.set("routingConfiguration", routes);
        alias.put("createdAt", now);
        alias.put("updatedAt", now);
        ArrayNode history = alias.putArray("agentAliasHistoryEvents");
        history.addObject().put("startDate", now).set("routingConfiguration", routes.deepCopy());
        alias.put("agentAliasStatus", "PREPARED");
        alias.put("aliasInvocationState", "ACCEPT_INVOCATIONS");
        ((ObjectNode) stored.get("aliases")).set(id, alias);
        agents.put(key(region, agentId), stored);
        putTags(alias.path("agentAliasArn").asText(), request.get("tags"));
        ObjectNode response = alias.deepCopy();
        response.put("agentAliasStatus", "CREATING");
        return wrap("agentAlias", response);
    }

    public ObjectNode getAgentAlias(String agentId, String aliasId, String region) {
        return wrap("agentAlias", requireAlias(requireAgent(agentId, region), aliasId));
    }

    public synchronized ObjectNode updateAgentAlias(String agentId, String aliasId, JsonNode request, String region) {
        ObjectNode stored = requireAgent(agentId, region);
        ObjectNode alias = requireAlias(stored, aliasId);
        alias.put("agentAliasName", requireName(request, "agentAliasName"));
        if (request.has("description")) {
            alias.put("description", request.get("description").asText());
        } else {
            alias.remove("description");
        }
        JsonNode routing = request.get("routingConfiguration");
        if (routing != null && routing.isArray() && !routing.isEmpty()) {
            for (JsonNode route : routing) {
                version(stored, route.path("agentVersion").asText(), agentId);
            }
            String now = now();
            ArrayNode history = (ArrayNode) alias.get("agentAliasHistoryEvents");
            if (!history.isEmpty()) {
                ((ObjectNode) history.get(history.size() - 1)).put("endDate", now);
            }
            history.addObject().put("startDate", now).set("routingConfiguration", routing.deepCopy());
            alias.set("routingConfiguration", routing.deepCopy());
        }
        if (request.hasNonNull("aliasInvocationState")) {
            alias.put("aliasInvocationState", request.get("aliasInvocationState").asText());
        }
        alias.put("updatedAt", now());
        agents.put(key(region, agentId), stored);
        ObjectNode response = alias.deepCopy();
        response.put("agentAliasStatus", "UPDATING");
        return wrap("agentAlias", response);
    }

    public synchronized ObjectNode deleteAgentAlias(String agentId, String aliasId, String region) {
        ObjectNode stored = requireAgent(agentId, region);
        ObjectNode alias = requireAlias(stored, aliasId);
        ((ObjectNode) stored.get("aliases")).remove(aliasId);
        agents.put(key(region, agentId), stored);
        tags.delete(alias.path("agentAliasArn").asText());
        ObjectNode response = objectMapper.createObjectNode();
        response.put("agentId", agentId);
        response.put("agentAliasId", aliasId);
        response.put("agentAliasStatus", "DELETING");
        return response;
    }

    public ObjectNode listAgentAliases(String agentId, JsonNode request, String region) {
        ObjectNode stored = requireAgent(agentId, region);
        List<JsonNode> aliases = new ArrayList<>();
        stored.path("aliases").forEach(aliases::add);
        Page page = page(aliases.size(), request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("agentAliasSummaries");
        for (JsonNode alias : aliases.subList(page.start(), page.end())) {
            ObjectNode summary = list.addObject();
            for (String field : List.of("agentAliasId", "agentAliasName", "description", "routingConfiguration",
                    "agentAliasStatus", "createdAt", "updatedAt", "aliasInvocationState")) {
                if (alias.has(field)) {
                    summary.set(field, alias.get(field).deepCopy());
                }
            }
        }
        page.next(response);
        return response;
    }

    /** The alias and the version it routes to, for the agent runtime. */
    public Resolved resolve(String agentId, String aliasId, String region) {
        ObjectNode stored = requireAgent(agentId, region);
        if ("TSTALIASID".equals(aliasId)) {
            return new Resolved(stored, null, draftVersion(stored), stored.get("draft"));
        }
        ObjectNode alias = requireAlias(stored, aliasId);
        String version = alias.path("routingConfiguration").path(0).path("agentVersion").asText(DRAFT);
        JsonNode agentVersion = version(stored, version, agentId);
        JsonNode components = DRAFT.equals(version) ? stored.get("draft") : agentVersion.get("components");
        return new Resolved(stored, alias, agentVersion, components);
    }

    /** An alias resolved for an invocation: the agent, the alias, its version, and that version's components. */
    public record Resolved(ObjectNode stored, JsonNode alias, JsonNode version, JsonNode components) {}

    // ── Action groups and knowledge base associations ──────────────────────

    public synchronized ObjectNode createActionGroup(String agentId, String version, JsonNode request, String region) {
        ObjectNode stored = requireAgent(agentId, region);
        ObjectNode groups = draftComponents(stored, version).withObject("/actionGroups");
        String name = requireName(request, "actionGroupName");
        for (JsonNode group : groups) {
            if (name.equals(group.path("actionGroupName").asText())) {
                throw new AwsException("ConflictException", "An action group named " + name + " already exists.", 409);
            }
        }
        String id = newId();
        String now = now();
        ObjectNode group = objectMapper.createObjectNode();
        group.put("agentId", agentId);
        group.put("agentVersion", DRAFT);
        group.put("actionGroupId", id);
        group.put("actionGroupName", name);
        copy(request, group, "clientToken", "description", "parentActionGroupSignature", "parentActionGroupSignatureParams",
                "actionGroupExecutor", "apiSchema", "functionSchema");
        if (request.has("parentActionGroupSignature")) {
            group.set("parentActionSignature", request.get("parentActionGroupSignature").deepCopy());
            group.remove("parentActionGroupSignature");
        }
        group.put("createdAt", now);
        group.put("updatedAt", now);
        group.put("actionGroupState", text(request, "actionGroupState") == null ? "ENABLED"
                : text(request, "actionGroupState"));
        groups.set(id, group);
        markUnprepared(stored);
        agents.put(key(region, agentId), stored);
        return wrap("agentActionGroup", group);
    }

    public ObjectNode getActionGroup(String agentId, String version, String groupId, String region) {
        return wrap("agentActionGroup", requireComponent(requireAgent(agentId, region), version, "actionGroups",
                groupId, "Action group"));
    }

    public synchronized ObjectNode updateActionGroup(String agentId, String version, String groupId,
                                                     JsonNode request, String region) {
        ObjectNode stored = requireAgent(agentId, region);
        ObjectNode group = (ObjectNode) requireComponent(stored, version, "actionGroups", groupId, "Action group");
        group.put("actionGroupName", requireName(request, "actionGroupName"));
        for (String field : List.of("description", "actionGroupExecutor", "apiSchema", "functionSchema",
                "parentActionGroupSignatureParams")) {
            if (request.has(field)) {
                group.set(field, request.get(field).deepCopy());
            }
        }
        if (request.hasNonNull("actionGroupState")) {
            group.put("actionGroupState", request.get("actionGroupState").asText());
        }
        group.put("updatedAt", now());
        markUnprepared(stored);
        agents.put(key(region, agentId), stored);
        return wrap("agentActionGroup", group);
    }

    public synchronized void deleteActionGroup(String agentId, String version, String groupId, String region) {
        ObjectNode stored = requireAgent(agentId, region);
        requireComponent(stored, version, "actionGroups", groupId, "Action group");
        draftComponents(stored, version).withObject("/actionGroups").remove(groupId);
        markUnprepared(stored);
        agents.put(key(region, agentId), stored);
    }

    public ObjectNode listActionGroups(String agentId, String version, JsonNode request, String region) {
        JsonNode components = components(requireAgent(agentId, region), version, agentId);
        List<JsonNode> groups = new ArrayList<>();
        components.path("actionGroups").forEach(groups::add);
        Page page = page(groups.size(), request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("actionGroupSummaries");
        for (JsonNode group : groups.subList(page.start(), page.end())) {
            ObjectNode summary = list.addObject();
            for (String field : List.of("actionGroupId", "actionGroupName", "actionGroupState", "description",
                    "updatedAt")) {
                if (group.has(field)) {
                    summary.set(field, group.get(field).deepCopy());
                }
            }
        }
        page.next(response);
        return response;
    }

    public synchronized ObjectNode associateKnowledgeBase(String agentId, String version, JsonNode request,
                                                          String region, boolean knowledgeBaseExists) {
        ObjectNode stored = requireAgent(agentId, region);
        String kbId = requireText(request, "knowledgeBaseId");
        if (!knowledgeBaseExists) {
            throw new AwsException("ResourceNotFoundException", "Knowledge base " + kbId + " was not found.", 404);
        }
        ObjectNode kbs = draftComponents(stored, version).withObject("/knowledgeBases");
        if (kbs.has(kbId)) {
            throw new AwsException("ConflictException", "Knowledge base " + kbId + " is already associated.", 409);
        }
        String now = now();
        ObjectNode association = objectMapper.createObjectNode();
        association.put("agentId", agentId);
        association.put("agentVersion", DRAFT);
        association.put("knowledgeBaseId", kbId);
        association.put("description", requireText(request, "description"));
        association.put("createdAt", now);
        association.put("updatedAt", now);
        association.put("knowledgeBaseState", text(request, "knowledgeBaseState") == null ? "ENABLED"
                : text(request, "knowledgeBaseState"));
        kbs.set(kbId, association);
        markUnprepared(stored);
        agents.put(key(region, agentId), stored);
        return wrap("agentKnowledgeBase", association);
    }

    public ObjectNode getAgentKnowledgeBase(String agentId, String version, String kbId, String region) {
        return wrap("agentKnowledgeBase", requireComponent(requireAgent(agentId, region), version, "knowledgeBases",
                kbId, "Knowledge base association"));
    }

    public synchronized ObjectNode updateAgentKnowledgeBase(String agentId, String version, String kbId,
                                                            JsonNode request, String region) {
        ObjectNode stored = requireAgent(agentId, region);
        ObjectNode association = (ObjectNode) requireComponent(stored, version, "knowledgeBases", kbId,
                "Knowledge base association");
        if (request.hasNonNull("description")) {
            association.put("description", request.get("description").asText());
        }
        if (request.hasNonNull("knowledgeBaseState")) {
            association.put("knowledgeBaseState", request.get("knowledgeBaseState").asText());
        }
        association.put("updatedAt", now());
        markUnprepared(stored);
        agents.put(key(region, agentId), stored);
        return wrap("agentKnowledgeBase", association);
    }

    public synchronized void disassociateKnowledgeBase(String agentId, String version, String kbId, String region) {
        ObjectNode stored = requireAgent(agentId, region);
        requireComponent(stored, version, "knowledgeBases", kbId, "Knowledge base association");
        draftComponents(stored, version).withObject("/knowledgeBases").remove(kbId);
        markUnprepared(stored);
        agents.put(key(region, agentId), stored);
    }

    public ObjectNode listAgentKnowledgeBases(String agentId, String version, JsonNode request, String region) {
        JsonNode components = components(requireAgent(agentId, region), version, agentId);
        List<JsonNode> kbs = new ArrayList<>();
        components.path("knowledgeBases").forEach(kbs::add);
        Page page = page(kbs.size(), request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("agentKnowledgeBaseSummaries");
        for (JsonNode kb : kbs.subList(page.start(), page.end())) {
            ObjectNode summary = list.addObject();
            for (String field : List.of("knowledgeBaseId", "description", "knowledgeBaseState", "updatedAt")) {
                if (kb.has(field)) {
                    summary.set(field, kb.get(field).deepCopy());
                }
            }
        }
        page.next(response);
        return response;
    }

    private ObjectNode components() {
        ObjectNode components = objectMapper.createObjectNode();
        components.putObject("actionGroups");
        components.putObject("knowledgeBases");
        return components;
    }

    /** Only the draft's components can change. */
    private ObjectNode draftComponents(ObjectNode stored, String version) {
        if (!DRAFT.equals(version)) {
            throw validation("Only the DRAFT version of an agent can be changed.");
        }
        return (ObjectNode) stored.get("draft");
    }

    private JsonNode components(ObjectNode stored, String version, String agentId) {
        if (DRAFT.equals(version)) {
            return stored.get("draft");
        }
        return version(stored, version, agentId).path("components");
    }

    private JsonNode requireComponent(ObjectNode stored, String version, String kind, String id, String what) {
        JsonNode found = components(stored, version, stored.path("agent").path("agentId").asText()).path(kind).get(id);
        if (found == null) {
            throw new AwsException("ResourceNotFoundException", what + " " + id + " was not found.", 404);
        }
        return found;
    }

    private static void markUnprepared(ObjectNode stored) {
        ((ObjectNode) stored.get("agent")).put("agentStatus", "NOT_PREPARED");
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    /** Whether the ARN names an agent or alias that exists. */
    public boolean owns(String arn, String region) {
        for (ObjectNode stored : agents.scan(k -> k.startsWith(region + "/"))) {
            if (arn.equals(stored.path("agent").path("agentArn").asText())) {
                return true;
            }
            for (JsonNode alias : stored.path("aliases")) {
                if (arn.equals(alias.path("agentAliasArn").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    public Map<String, String> listTags(String arn) {
        Map<String, String> result = new LinkedHashMap<>();
        tags.get(arn).ifPresent(current -> current.fields()
                .forEachRemaining(tag -> result.put(tag.getKey(), tag.getValue().asText())));
        return result;
    }

    public synchronized void tag(String arn, Map<String, String> values) {
        ObjectNode current = tags.get(arn).orElse(objectMapper.createObjectNode());
        values.forEach(current::put);
        tags.put(arn, current);
    }

    public synchronized void deleteTags(String arn) {
        tags.delete(arn);
    }

    public synchronized void untag(String arn, List<String> keys) {
        ObjectNode current = tags.get(arn).orElse(objectMapper.createObjectNode());
        keys.forEach(current::remove);
        tags.put(arn, current);
    }

    private void putTags(String arn, JsonNode map) {
        if (map != null && map.isObject() && !map.isEmpty()) {
            ObjectNode current = tags.get(arn).orElse(objectMapper.createObjectNode());
            current.setAll((ObjectNode) map);
            tags.put(arn, current);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    ObjectNode requireAgent(String agentId, String region) {
        Optional<ObjectNode> stored = agentId == null ? Optional.empty() : agents.get(key(region, agentId));
        return stored.orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Agent " + agentId + " was not found.", 404));
    }

    private static ObjectNode requireAlias(ObjectNode stored, String aliasId) {
        JsonNode alias = aliasId == null ? null : stored.path("aliases").get(aliasId);
        if (alias == null) {
            throw new AwsException("ResourceNotFoundException", "Agent alias " + aliasId + " was not found.", 404);
        }
        return (ObjectNode) alias;
    }

    String newId() {
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            id.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    private ObjectNode wrap(String key, JsonNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(key, value.deepCopy());
        return response;
    }

    static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.hasNonNull(field)) {
                to.set(field, from.get(field).deepCopy());
            }
        }
    }

    static String requireName(JsonNode request, String field) {
        String name = requireText(request, field);
        if (!name.matches("^([0-9a-zA-Z][_-]?){1,100}$")) {
            throw validation(field + " must match ([0-9a-zA-Z][_-]?){1,100}.");
        }
        return name;
    }

    static String requireText(JsonNode request, String field) {
        String value = text(request, field);
        if (value == null || value.isBlank()) {
            throw validation(field + " is a required field.");
        }
        return value;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static String now() {
        return Instant.now().toString();
    }

    private static String key(String region, String id) {
        return region + "/" + id;
    }

    record Page(int start, int end, String nextToken) {
        void next(ObjectNode response) {
            if (nextToken != null) {
                response.put("nextToken", nextToken);
            }
        }
    }

    static Page page(int size, JsonNode request) {
        int max = request.path("maxResults").asInt(1000);
        if (max < 1 || max > 1000) {
            throw validation("maxResults must be between 1 and 1000.");
        }
        int start = 0;
        String token = text(request, "nextToken");
        if (token != null) {
            try {
                start = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw validation("Invalid nextToken.");
            }
        }
        start = Math.min(Math.max(start, 0), size);
        int end = Math.min(size, start + max);
        return new Page(start, end, end < size ? Integer.toString(end) : null);
    }
}
