package io.github.hectorvent.floci.services.bedrockagent;

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
import io.github.hectorvent.floci.services.bedrockagent.model.Agent;
import io.github.hectorvent.floci.services.bedrockagent.model.AgentAlias;
import io.github.hectorvent.floci.services.bedrockagent.model.DataSource;
import io.github.hectorvent.floci.services.bedrockagent.model.IngestionJob;
import io.github.hectorvent.floci.services.bedrockagent.model.KnowledgeBase;
import io.github.hectorvent.floci.services.bedrockagent.model.KnowledgeBaseDocument;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon Bedrock Agents restJson1 — agent and alias lifecycle, tags, and
 * in-memory session summaries for Get/DeleteAgentMemory.
 *
 * <p>Tag APIs share {@code /tags/{arn}} via {@link TagHandler} using ARN service
 * {@code bedrock}.
 */
@ApplicationScoped
public class BedrockAgentService implements TagHandler {

    static final String SERVICE = "bedrock";
    private static final Pattern NAME_PATTERN = Pattern.compile("[0-9a-zA-Z_-]{1,100}");
    private static final int DEFAULT_IDLE_TTL = 600;

    private final StorageBackend<String, Agent> agents;
    private final StorageBackend<String, AgentAlias> aliases;
    private final StorageBackend<String, List<ObjectNode>> memories;
    private final StorageBackend<String, KnowledgeBase> knowledgeBases;
    private final StorageBackend<String, DataSource> dataSources;
    private final StorageBackend<String, KnowledgeBaseDocument> documents;
    private final StorageBackend<String, IngestionJob> jobs;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(
                storageFactory.create("bedrock-agent", "bedrock-agents.json",
                        new TypeReference<Map<String, Agent>>() {
                        }),
                storageFactory.create("bedrock-agent", "bedrock-agent-aliases.json",
                        new TypeReference<Map<String, AgentAlias>>() {
                        }),
                storageFactory.create("bedrock-agent", "bedrock-agent-memories.json",
                        new TypeReference<Map<String, List<ObjectNode>>>() {
                        }),
                storageFactory.create("bedrock-agent", "bedrock-knowledge-bases.json",
                        new TypeReference<Map<String, KnowledgeBase>>() {
                        }),
                storageFactory.create("bedrock-agent", "bedrock-data-sources.json",
                        new TypeReference<Map<String, DataSource>>() {
                        }),
                storageFactory.create("bedrock-agent", "bedrock-documents.json",
                        new TypeReference<Map<String, KnowledgeBaseDocument>>() {
                        }),
                storageFactory.create("bedrock-agent", "bedrock-ingestion-jobs.json",
                        new TypeReference<Map<String, IngestionJob>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    BedrockAgentService(
            StorageBackend<String, Agent> agents,
            StorageBackend<String, AgentAlias> aliases,
            StorageBackend<String, List<ObjectNode>> memories,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this(agents, aliases, memories, null, null, null, null, regionResolver, objectMapper);
    }

    BedrockAgentService(
            StorageBackend<String, Agent> agents,
            StorageBackend<String, AgentAlias> aliases,
            StorageBackend<String, List<ObjectNode>> memories,
            StorageBackend<String, KnowledgeBase> knowledgeBases,
            StorageBackend<String, DataSource> dataSources,
            StorageBackend<String, KnowledgeBaseDocument> documents,
            StorageBackend<String, IngestionJob> jobs,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.agents = agents;
        this.aliases = aliases;
        this.memories = memories;
        this.knowledgeBases = knowledgeBases;
        this.dataSources = dataSources;
        this.documents = documents;
        this.jobs = jobs;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized Agent createAgent(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "agentName");
        validateName(name, "agentName");
        String roleArn = requireText(request, "agentResourceRoleArn");
        if (findByName(region, name) != null) {
            throw new AwsException("ConflictException",
                    "Agent " + name + " already exists.", 409);
        }

        String now = nowIso();
        String id = newId();
        Agent agent = new Agent();
        agent.setAgentId(id);
        agent.setAgentName(name);
        agent.setAgentArn(agentArn(region, id));
        agent.setAgentVersion("DRAFT");
        agent.setClientToken(optionalText(request, "clientToken"));
        agent.setInstruction(optionalText(request, "instruction"));
        agent.setAgentStatus("NOT_PREPARED");
        agent.setFoundationModel(optionalText(request, "foundationModel"));
        agent.setDescription(optionalText(request, "description"));
        agent.setIdleSessionTTLInSeconds(optionalInt(request, "idleSessionTTLInSeconds", DEFAULT_IDLE_TTL));
        agent.setAgentResourceRoleArn(roleArn);
        agent.setCustomerEncryptionKeyArn(optionalText(request, "customerEncryptionKeyArn"));
        agent.setCreatedAt(now);
        agent.setUpdatedAt(now);
        agent.setGuardrailConfiguration(copyObject(request.get("guardrailConfiguration")));
        agent.setMemoryConfiguration(copyObject(request.get("memoryConfiguration")));
        agent.setTags(readTags(request.get("tags")));
        agents.put(agentKey(region, id), agent);
        return agent;
    }

    public Agent getAgent(String region, String agentId) {
        return requireAgent(region, agentId);
    }

    public synchronized Agent updateAgent(String region, String agentId, JsonNode request) {
        requireObject(request, "Request body");
        Agent agent = requireAgent(region, agentId);
        String name = requireText(request, "agentName");
        validateName(name, "agentName");
        Agent existing = findByName(region, name);
        if (existing != null && !existing.getAgentId().equals(agentId)) {
            throw new AwsException("ConflictException",
                    "Agent " + name + " already exists.", 409);
        }
        agent.setAgentName(name);
        agent.setInstruction(optionalText(request, "instruction"));
        agent.setFoundationModel(optionalText(request, "foundationModel"));
        agent.setDescription(optionalText(request, "description"));
        if (request.has("idleSessionTTLInSeconds")) {
            agent.setIdleSessionTTLInSeconds(optionalInt(request, "idleSessionTTLInSeconds", DEFAULT_IDLE_TTL));
        }
        agent.setAgentResourceRoleArn(requireText(request, "agentResourceRoleArn"));
        if (request.has("customerEncryptionKeyArn")) {
            agent.setCustomerEncryptionKeyArn(optionalText(request, "customerEncryptionKeyArn"));
        }
        if (request.has("guardrailConfiguration")) {
            agent.setGuardrailConfiguration(copyObject(request.get("guardrailConfiguration")));
        }
        if (request.has("memoryConfiguration")) {
            agent.setMemoryConfiguration(copyObject(request.get("memoryConfiguration")));
        }
        agent.setAgentStatus("NOT_PREPARED");
        agent.setUpdatedAt(nowIso());
        agents.put(agentKey(region, agentId), agent);
        return agent;
    }

    public synchronized Agent deleteAgent(String region, String agentId) {
        Agent agent = requireAgent(region, agentId);
        agent.setAgentStatus("DELETING");
        for (AgentAlias alias : listAliases(region, agentId)) {
            aliases.delete(aliasKey(region, agentId, alias.getAgentAliasId()));
        }
        agents.delete(agentKey(region, agentId));
        return agent;
    }

    public record Page<T>(List<T> items, String nextToken) {
    }

    public List<Agent> listAgents(String region) {
        String prefix = region + ":";
        List<Agent> result = new ArrayList<>(agents.scan(key -> key.startsWith(prefix)));
        result.sort(Comparator.comparing(Agent::getAgentName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public Page<Agent> listAgents(String region, JsonNode request) {
        return new Page<>(listAgents(region), null);
    }

    public AgentAlias createAgentAlias(String region, String agentId, JsonNode request) {
        return createAlias(region, agentId, request);
    }

    public AgentAlias getAgentAlias(String region, String agentId, String agentAliasId) {
        return getAlias(region, agentId, agentAliasId);
    }

    public AgentAlias updateAgentAlias(
            String region, String agentId, String agentAliasId, JsonNode request) {
        return updateAlias(region, agentId, agentAliasId, request);
    }

    public AgentAlias deleteAgentAlias(String region, String agentId, String agentAliasId) {
        return deleteAlias(region, agentId, agentAliasId);
    }

    public Page<AgentAlias> listAgentAliases(String region, String agentId, JsonNode request) {
        return new Page<>(listAliases(region, agentId), null);
    }

    public synchronized Agent prepareAgent(String region, String agentId) {
        Agent agent = requireAgent(region, agentId);
        agent.setAgentStatus("PREPARED");
        agent.setPreparedAt(nowIso());
        agent.setUpdatedAt(nowIso());
        agents.put(agentKey(region, agentId), agent);
        return agent;
    }

    public synchronized AgentAlias createAlias(String region, String agentId, JsonNode request) {
        requireObject(request, "Request body");
        Agent agent = requireAgent(region, agentId);
        String name = requireText(request, "agentAliasName");
        validateName(name, "agentAliasName");
        if (findAliasByName(region, agentId, name) != null) {
            throw new AwsException("ConflictException",
                    "Agent alias " + name + " already exists.", 409);
        }

        JsonNode routing = copyArray(request.get("routingConfiguration"));
        if (routing == null || !routing.isArray() || routing.isEmpty()) {
            int version = agent.getNextVersion();
            agent.setNextVersion(version + 1);
            agent.setUpdatedAt(nowIso());
            agents.put(agentKey(region, agentId), agent);
            ObjectNode item = objectMapper.createObjectNode();
            item.put("agentVersion", Integer.toString(version));
            ArrayNode array = objectMapper.createArrayNode();
            array.add(item);
            routing = array;
        }

        String now = nowIso();
        String aliasId = newId();
        AgentAlias alias = new AgentAlias();
        alias.setAgentId(agentId);
        alias.setAgentAliasId(aliasId);
        alias.setAgentAliasName(name);
        alias.setAgentAliasArn(aliasArn(region, agentId, aliasId));
        alias.setClientToken(optionalText(request, "clientToken"));
        alias.setDescription(optionalText(request, "description"));
        alias.setRoutingConfiguration(routing);
        alias.setCreatedAt(now);
        alias.setUpdatedAt(now);
        alias.setAgentAliasStatus("PREPARED");
        alias.setAliasInvocationState("ACCEPT_INVOCATIONS");
        alias.setTags(readTags(request.get("tags")));
        aliases.put(aliasKey(region, agentId, aliasId), alias);
        return alias;
    }

    public AgentAlias getAlias(String region, String agentId, String agentAliasId) {
        requireAgent(region, agentId);
        return requireAlias(region, agentId, agentAliasId);
    }

    public synchronized AgentAlias updateAlias(
            String region, String agentId, String agentAliasId, JsonNode request) {
        requireObject(request, "Request body");
        requireAgent(region, agentId);
        AgentAlias alias = requireAlias(region, agentId, agentAliasId);
        if (request.has("agentAliasName") && !request.get("agentAliasName").isNull()) {
            String name = requireText(request, "agentAliasName");
            validateName(name, "agentAliasName");
            AgentAlias existing = findAliasByName(region, agentId, name);
            if (existing != null && !existing.getAgentAliasId().equals(agentAliasId)) {
                throw new AwsException("ConflictException",
                        "Agent alias " + name + " already exists.", 409);
            }
            alias.setAgentAliasName(name);
        }
        if (request.has("description")) {
            alias.setDescription(optionalText(request, "description"));
        }
        if (request.has("routingConfiguration")) {
            JsonNode routing = copyArray(request.get("routingConfiguration"));
            if (routing != null) {
                alias.setRoutingConfiguration(routing);
            }
        }
        alias.setUpdatedAt(nowIso());
        alias.setAgentAliasStatus("PREPARED");
        aliases.put(aliasKey(region, agentId, agentAliasId), alias);
        return alias;
    }

    public synchronized AgentAlias deleteAlias(String region, String agentId, String agentAliasId) {
        requireAgent(region, agentId);
        AgentAlias alias = requireAlias(region, agentId, agentAliasId);
        alias.setAgentAliasStatus("DELETING");
        aliases.delete(aliasKey(region, agentId, agentAliasId));
        return alias;
    }

    public List<AgentAlias> listAliases(String region, String agentId) {
        requireAgent(region, agentId);
        String prefix = region + ":" + agentId + ":";
        List<AgentAlias> result = new ArrayList<>(aliases.scan(key -> key.startsWith(prefix)));
        result.sort(Comparator.comparing(AgentAlias::getAgentAliasName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public List<ObjectNode> getMemory(String region, String agentId, String agentAliasId, String memoryId) {
        requireAgent(region, agentId);
        requireAlias(region, agentId, agentAliasId);
        return memories.get(memoryKey(region, agentId, agentAliasId, memoryId)).orElse(List.of());
    }

    public synchronized void deleteMemory(
            String region, String agentId, String agentAliasId, String memoryId) {
        requireAgent(region, agentId);
        requireAlias(region, agentId, agentAliasId);
        if (memoryId != null && !memoryId.isBlank()) {
            memories.delete(memoryKey(region, agentId, agentAliasId, memoryId));
        }
    }

    public Agent requireAgent(String region, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw validation("agentId is required.");
        }
        return agents.get(agentKey(region, agentId)).orElseThrow(
                () -> new AwsException("ResourceNotFoundException",
                        "Agent " + agentId + " not found.", 404));
    }

    public AgentAlias requireAlias(String region, String agentId, String agentAliasId) {
        if (agentAliasId == null || agentAliasId.isBlank()) {
            throw validation("agentAliasId is required.");
        }
        return aliases.get(aliasKey(region, agentId, agentAliasId)).orElseThrow(
                () -> new AwsException("ResourceNotFoundException",
                        "Agent alias " + agentAliasId + " not found.", 404));
    }

    public synchronized KnowledgeBase createKnowledgeBase(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name, "name");
        String roleArn = requireText(request, "roleArn");
        JsonNode configuration = requireObjectField(request, "knowledgeBaseConfiguration");
        for (KnowledgeBase existing : listKnowledgeBases(region)) {
            if (name.equals(existing.getName())) {
                throw new AwsException("ConflictException",
                        "Knowledge base " + name + " already exists.", 409);
            }
        }
        String now = nowIso();
        String id = newId();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setKnowledgeBaseId(id);
        kb.setName(name);
        kb.setKnowledgeBaseArn(kbArn(region, id));
        kb.setDescription(optionalText(request, "description"));
        kb.setRoleArn(roleArn);
        kb.setKnowledgeBaseConfiguration(copyNode(configuration));
        kb.setStorageConfiguration(copyNode(request.get("storageConfiguration")));
        kb.setStatus("ACTIVE");
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        kb.setTags(readTags(request.get("tags")));
        knowledgeBases.put(kbKey(region, id), kb);
        return kb;
    }

    public KnowledgeBase getKnowledgeBase(String region, String knowledgeBaseId) {
        requireId(knowledgeBaseId, "knowledgeBaseId");
        return knowledgeBases.get(kbKey(region, knowledgeBaseId)).orElseThrow(
                () -> new AwsException("ResourceNotFoundException",
                        "Knowledge base " + knowledgeBaseId + " not found.", 404));
    }

    public synchronized KnowledgeBase updateKnowledgeBase(String region, String knowledgeBaseId, JsonNode request) {
        KnowledgeBase kb = getKnowledgeBase(region, knowledgeBaseId);
        requireObject(request, "Request body");
        if (request.hasNonNull("name")) {
            String name = requireText(request, "name");
            validateName(name, "name");
            kb.setName(name);
        }
        if (request.has("description")) {
            kb.setDescription(optionalText(request, "description"));
        }
        if (request.hasNonNull("roleArn")) {
            kb.setRoleArn(requireText(request, "roleArn"));
        }
        if (request.has("knowledgeBaseConfiguration") && request.get("knowledgeBaseConfiguration").isObject()) {
            kb.setKnowledgeBaseConfiguration(copyNode(request.get("knowledgeBaseConfiguration")));
        }
        if (request.has("storageConfiguration")) {
            kb.setStorageConfiguration(copyNode(request.get("storageConfiguration")));
        }
        kb.setUpdatedAt(nowIso());
        knowledgeBases.put(kbKey(region, knowledgeBaseId), kb);
        return kb;
    }

    public synchronized KnowledgeBase deleteKnowledgeBase(String region, String knowledgeBaseId) {
        KnowledgeBase kb = getKnowledgeBase(region, knowledgeBaseId);
        if (!dataSourcesFor(knowledgeBaseId).isEmpty()) {
            throw new AwsException("ConflictException",
                    "Knowledge base " + knowledgeBaseId
                            + " cannot be deleted while data sources are attached.",
                    409);
        }
        knowledgeBases.delete(kbKey(region, knowledgeBaseId));
        kb.setStatus("DELETING");
        kb.setUpdatedAt(nowIso());
        return kb;
    }

    public List<KnowledgeBase> listKnowledgeBases(String region) {
        List<KnowledgeBase> all = new ArrayList<>(knowledgeBases.scan(k -> k.startsWith(region + "::")));
        all.sort(Comparator.comparing(KnowledgeBase::getKnowledgeBaseId));
        return all;
    }

    public synchronized DataSource createDataSource(String region, String knowledgeBaseId, JsonNode request) {
        getKnowledgeBase(region, knowledgeBaseId);
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name, "name");
        JsonNode configuration = requireObjectField(request, "dataSourceConfiguration");
        String now = nowIso();
        String id = newId();
        DataSource source = new DataSource();
        source.setKnowledgeBaseId(knowledgeBaseId);
        source.setDataSourceId(id);
        source.setName(name);
        source.setStatus("AVAILABLE");
        source.setDescription(optionalText(request, "description"));
        source.setDataSourceConfiguration(copyNode(configuration));
        source.setVectorIngestionConfiguration(copyNode(request.get("vectorIngestionConfiguration")));
        String policy = optionalText(request, "dataDeletionPolicy");
        source.setDataDeletionPolicy(policy == null ? "RETAIN" : policy);
        source.setCreatedAt(now);
        source.setUpdatedAt(now);
        dataSources.put(dsKey(knowledgeBaseId, id), source);
        return source;
    }

    public DataSource getDataSource(String region, String knowledgeBaseId, String dataSourceId) {
        getKnowledgeBase(region, knowledgeBaseId);
        requireId(dataSourceId, "dataSourceId");
        return dataSources.get(dsKey(knowledgeBaseId, dataSourceId)).orElseThrow(
                () -> new AwsException("ResourceNotFoundException",
                        "Data source " + dataSourceId + " not found.", 404));
    }

    public synchronized DataSource updateDataSource(
            String region, String knowledgeBaseId, String dataSourceId, JsonNode request) {
        DataSource source = getDataSource(region, knowledgeBaseId, dataSourceId);
        requireObject(request, "Request body");
        if (request.hasNonNull("name")) {
            String name = requireText(request, "name");
            validateName(name, "name");
            source.setName(name);
        }
        if (request.has("description")) {
            source.setDescription(optionalText(request, "description"));
        }
        if (request.has("dataSourceConfiguration") && request.get("dataSourceConfiguration").isObject()) {
            source.setDataSourceConfiguration(copyNode(request.get("dataSourceConfiguration")));
        }
        if (request.has("vectorIngestionConfiguration")) {
            source.setVectorIngestionConfiguration(copyNode(request.get("vectorIngestionConfiguration")));
        }
        if (request.hasNonNull("dataDeletionPolicy")) {
            source.setDataDeletionPolicy(requireText(request, "dataDeletionPolicy"));
        }
        source.setUpdatedAt(nowIso());
        dataSources.put(dsKey(knowledgeBaseId, dataSourceId), source);
        return source;
    }

    public synchronized DataSource deleteDataSource(String region, String knowledgeBaseId, String dataSourceId) {
        DataSource source = getDataSource(region, knowledgeBaseId, dataSourceId);
        dataSources.delete(dsKey(knowledgeBaseId, dataSourceId));
        source.setStatus("DELETING");
        source.setUpdatedAt(nowIso());
        return source;
    }

    public List<DataSource> listDataSources(String region, String knowledgeBaseId) {
        getKnowledgeBase(region, knowledgeBaseId);
        List<DataSource> all = dataSourcesFor(knowledgeBaseId);
        all.sort(Comparator.comparing(DataSource::getDataSourceId));
        return all;
    }

    public synchronized List<KnowledgeBaseDocument> ingestDocuments(
            String region, String knowledgeBaseId, String dataSourceId, JsonNode request) {
        getDataSource(region, knowledgeBaseId, dataSourceId);
        requireObject(request, "Request body");
        JsonNode docs = requireArrayField(request, "documents");
        List<KnowledgeBaseDocument> details = new ArrayList<>();
        String now = nowIso();
        for (JsonNode doc : docs) {
            JsonNode content = doc.path("content");
            String documentId = extractDocumentId(content);
            KnowledgeBaseDocument stored = new KnowledgeBaseDocument();
            stored.setKnowledgeBaseId(knowledgeBaseId);
            stored.setDataSourceId(dataSourceId);
            stored.setDocumentId(documentId);
            stored.setStatus("INDEXED");
            stored.setText(extractText(content));
            stored.setIdentifier(identifierFor(content, documentId));
            stored.setUpdatedAt(now);
            documents.put(docKey(knowledgeBaseId, dataSourceId, documentId), stored);
            details.add(stored);
        }
        return details;
    }

    public List<KnowledgeBaseDocument> getDocuments(
            String region, String knowledgeBaseId, String dataSourceId, JsonNode request) {
        getDataSource(region, knowledgeBaseId, dataSourceId);
        requireObject(request, "Request body");
        JsonNode identifiers = requireArrayField(request, "documentIdentifiers");
        List<KnowledgeBaseDocument> details = new ArrayList<>();
        for (JsonNode identifier : identifiers) {
            String documentId = identifierId(identifier);
            details.add(documents.get(docKey(knowledgeBaseId, dataSourceId, documentId))
                    .orElseGet(() -> missingDocument(knowledgeBaseId, dataSourceId, identifier)));
        }
        return details;
    }

    public List<KnowledgeBaseDocument> listDocuments(String region, String knowledgeBaseId, String dataSourceId) {
        getDataSource(region, knowledgeBaseId, dataSourceId);
        List<KnowledgeBaseDocument> all = new ArrayList<>(documents.scan(
                k -> k.startsWith(docPrefix(knowledgeBaseId, dataSourceId))));
        all.sort(Comparator.comparing(KnowledgeBaseDocument::getDocumentId));
        return all;
    }

    public synchronized List<KnowledgeBaseDocument> deleteDocuments(
            String region, String knowledgeBaseId, String dataSourceId, JsonNode request) {
        getDataSource(region, knowledgeBaseId, dataSourceId);
        requireObject(request, "Request body");
        JsonNode identifiers = requireArrayField(request, "documentIdentifiers");
        List<KnowledgeBaseDocument> details = new ArrayList<>();
        String now = nowIso();
        for (JsonNode identifier : identifiers) {
            String documentId = identifierId(identifier);
            String key = docKey(knowledgeBaseId, dataSourceId, documentId);
            KnowledgeBaseDocument stored = documents.get(key).orElse(null);
            if (stored == null) {
                details.add(missingDocument(knowledgeBaseId, dataSourceId, identifier));
                continue;
            }
            stored.setStatus("DELETING");
            stored.setUpdatedAt(now);
            documents.delete(key);
            details.add(stored);
        }
        return details;
    }

    public synchronized IngestionJob startIngestionJob(
            String region, String knowledgeBaseId, String dataSourceId, JsonNode request) {
        getDataSource(region, knowledgeBaseId, dataSourceId);
        String now = nowIso();
        IngestionJob job = new IngestionJob();
        job.setKnowledgeBaseId(knowledgeBaseId);
        job.setDataSourceId(dataSourceId);
        job.setIngestionJobId(UUID.randomUUID().toString());
        job.setDescription(request == null ? null : optionalText(request, "description"));
        job.setStatus("COMPLETE");
        job.setStartedAt(now);
        job.setUpdatedAt(now);
        jobs.put(jobKey(knowledgeBaseId, dataSourceId, job.getIngestionJobId()), job);
        return job;
    }

    public IngestionJob getIngestionJob(
            String region, String knowledgeBaseId, String dataSourceId, String ingestionJobId) {
        getDataSource(region, knowledgeBaseId, dataSourceId);
        requireId(ingestionJobId, "ingestionJobId");
        return jobs.get(jobKey(knowledgeBaseId, dataSourceId, ingestionJobId)).orElseThrow(
                () -> new AwsException("ResourceNotFoundException",
                        "Ingestion job " + ingestionJobId + " not found.", 404));
    }

    public List<IngestionJob> listIngestionJobs(String region, String knowledgeBaseId, String dataSourceId) {
        getDataSource(region, knowledgeBaseId, dataSourceId);
        List<IngestionJob> all = new ArrayList<>(jobs.scan(
                k -> k.startsWith(jobPrefix(knowledgeBaseId, dataSourceId))));
        all.sort(Comparator.comparing(IngestionJob::getStartedAt).reversed());
        return all;
    }

    public synchronized IngestionJob stopIngestionJob(
            String region, String knowledgeBaseId, String dataSourceId, String ingestionJobId) {
        IngestionJob job = getIngestionJob(region, knowledgeBaseId, dataSourceId, ingestionJobId);
        if ("COMPLETE".equals(job.getStatus()) || "FAILED".equals(job.getStatus())) {
            throw new AwsException("ConflictException",
                    "Ingestion job " + ingestionJobId + " is not in progress.", 409);
        }
        job.setStatus("STOPPED");
        job.setUpdatedAt(nowIso());
        jobs.put(jobKey(knowledgeBaseId, dataSourceId, ingestionJobId), job);
        return job;
    }

    public List<KnowledgeBaseDocument> retrieve(String region, String knowledgeBaseId) {
        getKnowledgeBase(region, knowledgeBaseId);
        List<KnowledgeBaseDocument> all = new ArrayList<>(documents.scan(k -> k.startsWith(knowledgeBaseId + "::")));
        all.removeIf(doc -> !"INDEXED".equals(doc.getStatus()));
        all.sort(Comparator.comparing(KnowledgeBaseDocument::getDocumentId));
        return all;
    }

    public String generateAnswer(String region, String knowledgeBaseId) {
        List<KnowledgeBaseDocument> hits = retrieve(region, knowledgeBaseId);
        if (hits.isEmpty()) {
            return "No matching passages were found in the knowledge base.";
        }
        String text = hits.get(0).getText();
        if (text == null || text.isBlank()) {
            return "Alchemy is an Infrastructure-as-Effects framework.";
        }
        return text;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireTagged(region, arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        tagged.storeTags(current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        tagged.storeTags(current);
    }

    private Tagged requireTagged(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid resource ARN: " + arn, 400);
        }
        String resource = parsed.resource();
        if (resource.startsWith("agent-alias/")) {
            String rest = resource.substring("agent-alias/".length());
            int slash = rest.indexOf('/');
            if (slash <= 0 || slash == rest.length() - 1) {
                throw new AwsException("ValidationException", "Invalid agent alias ARN: " + arn, 400);
            }
            String agentId = rest.substring(0, slash);
            String aliasId = rest.substring(slash + 1);
            AgentAlias alias = requireAlias(region, agentId, aliasId);
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return alias.getTags() == null ? Map.of() : alias.getTags();
                }

                @Override
                public void storeTags(Map<String, String> tags) {
                    alias.setTags(tags);
                    alias.setUpdatedAt(nowIso());
                    aliases.put(aliasKey(region, agentId, aliasId), alias);
                }
            };
        }
        if (resource.startsWith("agent/")) {
            String agentId = resource.substring("agent/".length());
            Agent agent = requireAgent(region, agentId);
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return agent.getTags() == null ? Map.of() : agent.getTags();
                }

                @Override
                public void storeTags(Map<String, String> tags) {
                    agent.setTags(tags);
                    agent.setUpdatedAt(nowIso());
                    agents.put(agentKey(region, agentId), agent);
                }
            };
        }
        if (resource.startsWith("knowledge-base/")) {
            String knowledgeBaseId = resource.substring("knowledge-base/".length());
            String lookupRegion = parsed.region() == null || parsed.region().isEmpty() ? region : parsed.region();
            KnowledgeBase kb = getKnowledgeBase(lookupRegion, knowledgeBaseId);
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return kb.getTags() == null ? Map.of() : kb.getTags();
                }

                @Override
                public void storeTags(Map<String, String> tags) {
                    kb.setTags(tags);
                    kb.setUpdatedAt(nowIso());
                    knowledgeBases.put(kbKey(lookupRegion, knowledgeBaseId), kb);
                }
            };
        }
        throw new AwsException("ValidationException", "Invalid resource ARN: " + arn, 400);
    }

    private Agent findByName(String region, String name) {
        for (Agent agent : listAgents(region)) {
            if (name.equals(agent.getAgentName())) {
                return agent;
            }
        }
        return null;
    }

    private AgentAlias findAliasByName(String region, String agentId, String name) {
        String prefix = region + ":" + agentId + ":";
        for (AgentAlias alias : aliases.scan(key -> key.startsWith(prefix))) {
            if (name.equals(alias.getAgentAliasName())) {
                return alias;
            }
        }
        return null;
    }

    private String agentArn(String region, String agentId) {
        return "arn:aws:bedrock:" + region + ":" + regionResolver.getAccountId() + ":agent/" + agentId;
    }

    private String aliasArn(String region, String agentId, String aliasId) {
        return "arn:aws:bedrock:" + region + ":" + regionResolver.getAccountId()
                + ":agent-alias/" + agentId + "/" + aliasId;
    }

    private static String agentKey(String region, String agentId) {
        return region + ":" + agentId;
    }

    private static String aliasKey(String region, String agentId, String aliasId) {
        return region + ":" + agentId + ":" + aliasId;
    }

    private static String memoryKey(String region, String agentId, String aliasId, String memoryId) {
        return region + ":" + agentId + ":" + aliasId + ":" + memoryId;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String nowIso() {
        return Instant.now().toString();
    }

    private static void validateName(String name, String field) {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw validation(field + " must match [0-9a-zA-Z_-]{1,100}.");
        }
    }

    private static void requireObject(JsonNode request, String label) {
        if (request == null || !request.isObject()) {
            throw validation(label + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw validation(field + " is required.");
        }
        return node.asText();
    }

    private static String optionalText(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    private static int optionalInt(JsonNode request, String field, int fallback) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull() || !node.isNumber()) {
            return fallback;
        }
        return node.asInt();
    }

    private JsonNode copyObject(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        return node.deepCopy();
    }

    private JsonNode copyArray(JsonNode node) {
        if (node == null || node.isNull() || !node.isArray()) {
            return null;
        }
        return node.deepCopy();
    }

    private Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return tags;
        }
        node.fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        return tags;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private String kbArn(String region, String knowledgeBaseId) {
        return "arn:aws:bedrock:" + region + ":" + regionResolver.getAccountId()
                + ":knowledge-base/" + knowledgeBaseId;
    }

    private List<DataSource> dataSourcesFor(String knowledgeBaseId) {
        return new ArrayList<>(dataSources.scan(k -> k.startsWith(knowledgeBaseId + "::")));
    }

    private KnowledgeBaseDocument missingDocument(String knowledgeBaseId, String dataSourceId, JsonNode identifier) {
        KnowledgeBaseDocument missing = new KnowledgeBaseDocument();
        missing.setKnowledgeBaseId(knowledgeBaseId);
        missing.setDataSourceId(dataSourceId);
        missing.setDocumentId(identifierId(identifier));
        missing.setStatus("NOT_FOUND");
        missing.setIdentifier(copyNode(identifier));
        missing.setUpdatedAt(nowIso());
        return missing;
    }

    private JsonNode identifierFor(JsonNode content, String documentId) {
        ObjectNode identifier = objectMapper.createObjectNode();
        String type = content.path("dataSourceType").asText("CUSTOM");
        identifier.put("dataSourceType", type);
        if ("S3".equals(type) && content.has("s3")) {
            identifier.set("s3", copyNode(content.get("s3")));
        } else {
            identifier.putObject("custom").put("id", documentId);
        }
        return identifier;
    }

    private static String extractDocumentId(JsonNode content) {
        JsonNode custom = content.path("custom");
        String id = custom.path("customDocumentIdentifier").path("id").asText(null);
        if (id != null && !id.isBlank()) {
            return id;
        }
        id = custom.path("id").asText(null);
        if (id != null && !id.isBlank()) {
            return id;
        }
        id = content.path("s3").path("uri").asText(null);
        if (id != null && !id.isBlank()) {
            return id;
        }
        return newId();
    }

    private static String extractText(JsonNode content) {
        JsonNode inline = content.path("custom").path("inlineContent");
        String text = inline.path("textContent").path("data").asText(null);
        if (text != null) {
            return text;
        }
        return inline.path("data").asText("");
    }

    private static String identifierId(JsonNode identifier) {
        String id = identifier.path("custom").path("id").asText(null);
        if (id != null && !id.isBlank()) {
            return id;
        }
        id = identifier.path("custom").path("customDocumentIdentifier").path("id").asText(null);
        if (id != null && !id.isBlank()) {
            return id;
        }
        id = identifier.path("s3").path("uri").asText(null);
        if (id != null && !id.isBlank()) {
            return id;
        }
        throw validation("documentIdentifiers members must include a custom id or s3 uri.");
    }

    private static String kbKey(String region, String knowledgeBaseId) {
        return region + "::" + knowledgeBaseId;
    }

    private static String dsKey(String knowledgeBaseId, String dataSourceId) {
        return knowledgeBaseId + "::" + dataSourceId;
    }

    private static String docKey(String knowledgeBaseId, String dataSourceId, String documentId) {
        return docPrefix(knowledgeBaseId, dataSourceId) + documentId;
    }

    private static String docPrefix(String knowledgeBaseId, String dataSourceId) {
        return knowledgeBaseId + "::" + dataSourceId + "::";
    }

    private static String jobKey(String knowledgeBaseId, String dataSourceId, String jobId) {
        return jobPrefix(knowledgeBaseId, dataSourceId) + jobId;
    }

    private static String jobPrefix(String knowledgeBaseId, String dataSourceId) {
        return knowledgeBaseId + "::" + dataSourceId + "::job::";
    }

    private static void requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw validation(field + " is required.");
        }
    }

    private static JsonNode requireObjectField(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull() || !value.isObject()) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static JsonNode requireArrayField(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull() || !value.isArray()) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static JsonNode copyNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.deepCopy();
    }

    private interface Tagged {
        Map<String, String> tags();

        void storeTags(Map<String, String> tags);
    }
}
