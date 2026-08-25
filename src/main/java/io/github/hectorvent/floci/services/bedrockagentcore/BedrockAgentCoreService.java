package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrockagentcore.model.AgentCoreSession;
import io.github.hectorvent.floci.services.bedrockagentcore.model.AgentRuntime;
import io.github.hectorvent.floci.services.bedrockagentcore.model.Browser;
import io.github.hectorvent.floci.services.bedrockagentcore.model.CodeInterpreter;
import io.github.hectorvent.floci.services.bedrockagentcore.model.Gateway;
import io.github.hectorvent.floci.services.bedrockagentcore.model.MemoryEvent;
import io.github.hectorvent.floci.services.bedrockagentcore.model.MemoryRecordItem;
import io.github.hectorvent.floci.services.bedrockagentcore.model.MemoryResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bedrock AgentCore restJson1 — control-plane resources plus the data-plane
 * operations Alchemy bindings exercise (events, records, sessions).
 *
 * <p>Requests are signed as {@code bedrock-agentcore}.
 */
@ApplicationScoped
public class BedrockAgentCoreService implements TagHandler {

    public static final String SERVICE = "bedrock-agentcore";
    static final String RESOURCE_BROWSER = "browser";
    static final String RESOURCE_MEMORY = "memory";
    static final String RESOURCE_CODE_INTERPRETER = "code-interpreter";
    static final String RESOURCE_GATEWAY = "gateway";
    static final String RESOURCE_RUNTIME = "runtime";

    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int MAX_RESULTS = 100;
    private static final String BROWSER_TOKEN_PREFIX = "bedrock-agentcore:browser:v1:";
    private static final String MEMORY_TOKEN_PREFIX = "bedrock-agentcore:memory:v1:";
    private static final String CI_TOKEN_PREFIX = "bedrock-agentcore:code-interpreter:v1:";
    private static final String GATEWAY_TOKEN_PREFIX = "bedrock-agentcore:gateway:v1:";
    private static final String RUNTIME_TOKEN_PREFIX = "bedrock-agentcore:runtime:v1:";
    private static final Set<String> NETWORK_MODES = Set.of("PUBLIC", "VPC");
    private static final Set<String> CI_NETWORK_MODES = Set.of("PUBLIC", "SANDBOX", "VPC");
    private static final Set<String> RESOURCE_TYPES = Set.of("SYSTEM", "CUSTOM");
    private static final Set<String> AUTHORIZER_TYPES =
            Set.of("CUSTOM_JWT", "AWS_IAM", "NONE", "AUTHENTICATE_ONLY");
    private static final Set<String> PROTOCOL_TYPES = Set.of("MCP");
    private static final Set<String> RUNTIME_PROTOCOLS = Set.of("HTTP", "MCP", "A2A", "AGUI");
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,47}");
    private static final Pattern GATEWAY_NAME_PATTERN = Pattern.compile("([0-9a-zA-Z][-]?){1,100}");
    private static final Pattern PRINT_MUL = Pattern.compile("print\\((\\d+)\\s*\\*\\s*(\\d+)\\)");

    private final StorageBackend<String, Browser> browserStore;
    private final StorageBackend<String, MemoryResource> memoryStore;
    private final StorageBackend<String, CodeInterpreter> codeInterpreterStore;
    private final StorageBackend<String, Gateway> gatewayStore;
    private final StorageBackend<String, AgentCoreSession> browserSessionStore;
    private final StorageBackend<String, AgentCoreSession> codeSessionStore;
    private final StorageBackend<String, AgentRuntime> runtimeStore;
    private final RegionResolver regionResolver;

    @Inject
    public BedrockAgentCoreService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(
                storageFactory.create(
                        "bedrockagentcore",
                        "bedrock-agentcore-browsers.json",
                        new TypeReference<Map<String, Browser>>() {
                        }),
                storageFactory.create(
                        "bedrockagentcore",
                        "bedrock-agentcore-memories.json",
                        new TypeReference<Map<String, MemoryResource>>() {
                        }),
                storageFactory.create(
                        "bedrockagentcore",
                        "bedrock-agentcore-code-interpreters.json",
                        new TypeReference<Map<String, CodeInterpreter>>() {
                        }),
                storageFactory.create(
                        "bedrockagentcore",
                        "bedrock-agentcore-gateways.json",
                        new TypeReference<Map<String, Gateway>>() {
                        }),
                storageFactory.create(
                        "bedrockagentcore",
                        "bedrock-agentcore-browser-sessions.json",
                        new TypeReference<Map<String, AgentCoreSession>>() {
                        }),
                storageFactory.create(
                        "bedrockagentcore",
                        "bedrock-agentcore-code-sessions.json",
                        new TypeReference<Map<String, AgentCoreSession>>() {
                        }),
                storageFactory.create(
                        "bedrockagentcore",
                        "bedrock-agentcore-runtimes.json",
                        new TypeReference<Map<String, AgentRuntime>>() {
                        }),
                regionResolver);
    }

    BedrockAgentCoreService(StorageBackend<String, Browser> browserStore) {
        this(browserStore, null, null, null, null, null, null, new RegionResolver("us-east-1", "000000000000"));
    }

    BedrockAgentCoreService(StorageBackend<String, Browser> browserStore, RegionResolver regionResolver) {
        this(browserStore, null, null, null, null, null, null, regionResolver);
    }

    BedrockAgentCoreService(
            StorageBackend<String, Browser> browserStore,
            StorageBackend<String, MemoryResource> memoryStore,
            StorageBackend<String, CodeInterpreter> codeInterpreterStore,
            StorageBackend<String, Gateway> gatewayStore,
            StorageBackend<String, AgentCoreSession> browserSessionStore,
            StorageBackend<String, AgentCoreSession> codeSessionStore,
            StorageBackend<String, AgentRuntime> runtimeStore,
            RegionResolver regionResolver) {
        this.browserStore = browserStore;
        this.memoryStore = memoryStore;
        this.codeInterpreterStore = codeInterpreterStore;
        this.gatewayStore = gatewayStore;
        this.browserSessionStore = browserSessionStore;
        this.codeSessionStore = codeSessionStore;
        this.runtimeStore = runtimeStore;
        this.regionResolver = regionResolver;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    public synchronized Browser createBrowser(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name);
        if (findLiveBrowserByName(region, name) != null) {
            throw new AwsException("ConflictException", "Browser " + name + " already exists.", 409);
        }

        JsonNode networkConfiguration = readNetworkConfiguration(request, NETWORK_MODES, "PUBLIC");
        String now = isoTimestamp();
        String browserId = name + "-" + shortId();
        Browser browser = new Browser();
        browser.setBrowserId(browserId);
        browser.setBrowserArn(arn(region, RESOURCE_BROWSER, browserId));
        browser.setName(name);
        browser.setDescription(optionalText(request, "description"));
        browser.setExecutionRoleArn(optionalText(request, "executionRoleArn"));
        browser.setNetworkConfiguration(networkConfiguration);
        browser.setRecording(optionalCopy(request, "recording"));
        browser.setBrowserSigning(optionalCopy(request, "browserSigning"));
        browser.setEnterprisePolicies(optionalCopy(request, "enterprisePolicies"));
        browser.setCertificates(optionalCopy(request, "certificates"));
        browser.setStatus("READY");
        browser.setCreatedAt(now);
        browser.setLastUpdatedAt(now);
        browser.setTags(readTags(request.get("tags")));
        browserStore.put(storageKey(region, browserId), browser);
        return browser;
    }

    public Browser getBrowser(String region, String browserId) {
        return requireBrowser(region, browserId);
    }

    public synchronized Browser deleteBrowser(String region, String browserId) {
        Browser browser = requireBrowser(region, browserId);
        browser.setStatus("DELETING");
        browser.setLastUpdatedAt(isoTimestamp());
        browserStore.delete(storageKey(region, browser.getBrowserId()));
        return browser;
    }

    public Page<Browser> listBrowsers(String region, String maxResultsValue, String nextToken, String type) {
        if (type != null && !type.isBlank() && !RESOURCE_TYPES.contains(type)) {
            throw validation("type must be SYSTEM or CUSTOM.");
        }
        if ("SYSTEM".equals(type)) {
            return new Page<>(List.of(), null);
        }
        int maxResults = parseMaxResults(maxResultsValue);
        List<Browser> browsers = browserStore.scan(key -> key.startsWith(region + "::"));
        browsers.sort(Comparator.comparing(Browser::getName, Comparator.nullsLast(String::compareTo)));
        return page(browsers, maxResults, nextToken, BROWSER_TOKEN_PREFIX);
    }

    public synchronized MemoryResource createMemory(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name);
        if (findLiveMemoryByName(region, name) != null) {
            throw new AwsException("ConflictException", "Memory " + name + " already exists.", 409);
        }
        int expiry = requireInt(request, "eventExpiryDuration");
        long now = epochSeconds();
        String memoryId = name + "-" + shortId();
        MemoryResource memory = new MemoryResource();
        memory.setId(memoryId);
        memory.setArn(arn(region, RESOURCE_MEMORY, memoryId));
        memory.setName(name);
        memory.setDescription(optionalText(request, "description"));
        memory.setEncryptionKeyArn(optionalText(request, "encryptionKeyArn"));
        memory.setMemoryExecutionRoleArn(optionalText(request, "memoryExecutionRoleArn"));
        memory.setEventExpiryDuration(expiry);
        memory.setStatus("ACTIVE");
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        memory.setStrategies(buildStrategies(request.get("memoryStrategies"), now));
        memory.setTags(readTags(request.get("tags")));
        memoryStore.put(storageKey(region, memoryId), memory);
        return memory;
    }

    public MemoryResource getMemory(String region, String memoryId) {
        return requireMemory(region, memoryId);
    }

    public synchronized MemoryResource updateMemory(String region, String memoryId, JsonNode request) {
        MemoryResource memory = requireMemory(region, memoryId);
        requireObject(request, "Request body");
        if (request.has("description")) {
            memory.setDescription(optionalText(request, "description"));
        }
        if (request.has("eventExpiryDuration")) {
            memory.setEventExpiryDuration(requireInt(request, "eventExpiryDuration"));
        }
        if (request.has("memoryExecutionRoleArn")) {
            memory.setMemoryExecutionRoleArn(optionalText(request, "memoryExecutionRoleArn"));
        }
        memory.setUpdatedAt(epochSeconds());
        memoryStore.put(storageKey(region, memory.getId()), memory);
        return memory;
    }

    public synchronized MemoryResource deleteMemory(String region, String memoryId) {
        MemoryResource memory = requireMemory(region, memoryId);
        memory.setStatus("DELETING");
        memory.setUpdatedAt(epochSeconds());
        memoryStore.delete(storageKey(region, memory.getId()));
        return memory;
    }

    public Page<MemoryResource> listMemories(String region, JsonNode request) {
        int maxResults = parseMaxResults(optionalTextOrNumber(request, "maxResults"));
        String nextToken = optionalText(request, "nextToken");
        List<MemoryResource> memories = memoryStore.scan(key -> key.startsWith(region + "::"));
        memories.sort(Comparator.comparing(MemoryResource::getName, Comparator.nullsLast(String::compareTo)));
        return page(memories, maxResults, nextToken, MEMORY_TOKEN_PREFIX);
    }

    public synchronized CodeInterpreter createCodeInterpreter(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name);
        if (findLiveCodeInterpreterByName(region, name) != null) {
            throw new AwsException("ConflictException", "Code interpreter " + name + " already exists.", 409);
        }
        JsonNode networkConfiguration = readNetworkConfiguration(request, CI_NETWORK_MODES, "SANDBOX");
        String now = isoTimestamp();
        String id = name + "-" + shortId();
        CodeInterpreter interpreter = new CodeInterpreter();
        interpreter.setCodeInterpreterId(id);
        interpreter.setCodeInterpreterArn(arn(region, RESOURCE_CODE_INTERPRETER, id));
        interpreter.setName(name);
        interpreter.setDescription(optionalText(request, "description"));
        interpreter.setExecutionRoleArn(optionalText(request, "executionRoleArn"));
        interpreter.setNetworkConfiguration(networkConfiguration);
        interpreter.setCertificates(optionalCopy(request, "certificates"));
        interpreter.setStatus("READY");
        interpreter.setCreatedAt(now);
        interpreter.setLastUpdatedAt(now);
        interpreter.setTags(readTags(request.get("tags")));
        codeInterpreterStore.put(storageKey(region, id), interpreter);
        return interpreter;
    }

    public CodeInterpreter getCodeInterpreter(String region, String codeInterpreterId) {
        return requireCodeInterpreter(region, codeInterpreterId);
    }

    public synchronized CodeInterpreter deleteCodeInterpreter(String region, String codeInterpreterId) {
        CodeInterpreter interpreter = requireCodeInterpreter(region, codeInterpreterId);
        interpreter.setStatus("DELETING");
        interpreter.setLastUpdatedAt(isoTimestamp());
        codeInterpreterStore.delete(storageKey(region, interpreter.getCodeInterpreterId()));
        return interpreter;
    }

    public Page<CodeInterpreter> listCodeInterpreters(
            String region, String maxResultsValue, String nextToken, String type) {
        if (type != null && !type.isBlank() && !RESOURCE_TYPES.contains(type)) {
            throw validation("type must be SYSTEM or CUSTOM.");
        }
        if ("SYSTEM".equals(type)) {
            return new Page<>(List.of(), null);
        }
        int maxResults = parseMaxResults(maxResultsValue);
        List<CodeInterpreter> interpreters =
                codeInterpreterStore.scan(key -> key.startsWith(region + "::"));
        interpreters.sort(Comparator.comparing(CodeInterpreter::getName, Comparator.nullsLast(String::compareTo)));
        return page(interpreters, maxResults, nextToken, CI_TOKEN_PREFIX);
    }

    public synchronized Gateway createGateway(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateGatewayName(name);
        String roleArn = requireText(request, "roleArn");
        String authorizerType = requireText(request, "authorizerType");
        if (!AUTHORIZER_TYPES.contains(authorizerType)) {
            throw validation("authorizerType is invalid.");
        }
        if ("CUSTOM_JWT".equals(authorizerType)
                && (request.get("authorizerConfiguration") == null
                || request.get("authorizerConfiguration").isNull())) {
            throw validation("authorizerConfiguration is required when authorizerType is CUSTOM_JWT.");
        }
        String protocolType = optionalText(request, "protocolType");
        if (protocolType == null) {
            protocolType = "MCP";
        }
        if (!PROTOCOL_TYPES.contains(protocolType)) {
            throw validation("protocolType is invalid.");
        }
        if (findLiveGatewayByName(region, name) != null) {
            throw new AwsException("ConflictException", "Gateway " + name + " already exists.", 409);
        }
        String now = isoTimestamp();
        String gatewayId = name + "-" + shortId();
        Gateway gateway = new Gateway();
        gateway.setGatewayId(gatewayId);
        gateway.setGatewayArn(arn(region, RESOURCE_GATEWAY, gatewayId));
        gateway.setGatewayUrl(gatewayUrl(region, gatewayId));
        gateway.setName(name);
        gateway.setDescription(optionalText(request, "description"));
        gateway.setRoleArn(roleArn);
        gateway.setProtocolType(protocolType);
        gateway.setProtocolConfiguration(optionalCopy(request, "protocolConfiguration"));
        gateway.setAuthorizerType(authorizerType);
        gateway.setAuthorizerConfiguration(optionalCopy(request, "authorizerConfiguration"));
        gateway.setKmsKeyArn(optionalText(request, "kmsKeyArn"));
        gateway.setExceptionLevel(optionalText(request, "exceptionLevel"));
        gateway.setInterceptorConfigurations(optionalCopy(request, "interceptorConfigurations"));
        gateway.setPolicyEngineConfiguration(optionalCopy(request, "policyEngineConfiguration"));
        gateway.setCustomTransformConfiguration(optionalCopy(request, "customTransformConfiguration"));
        gateway.setStatus("READY");
        gateway.setCreatedAt(now);
        gateway.setUpdatedAt(now);
        gateway.setTags(readTags(request.get("tags")));
        gatewayStore.put(storageKey(region, gatewayId), gateway);
        return gateway;
    }

    public Gateway getGateway(String region, String gatewayIdentifier) {
        return requireGateway(region, gatewayIdentifier);
    }

    public synchronized Gateway updateGateway(String region, String gatewayIdentifier, JsonNode request) {
        Gateway gateway = requireGateway(region, gatewayIdentifier);
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateGatewayName(name);
        Gateway existing = findLiveGatewayByName(region, name);
        if (existing != null && !existing.getGatewayId().equals(gateway.getGatewayId())) {
            throw new AwsException("ConflictException", "Gateway " + name + " already exists.", 409);
        }
        String authorizerType = requireText(request, "authorizerType");
        if (!AUTHORIZER_TYPES.contains(authorizerType)) {
            throw validation("authorizerType is invalid.");
        }
        if ("CUSTOM_JWT".equals(authorizerType)
                && (request.get("authorizerConfiguration") == null
                || request.get("authorizerConfiguration").isNull())
                && gateway.getAuthorizerConfiguration() == null) {
            throw validation("authorizerConfiguration is required when authorizerType is CUSTOM_JWT.");
        }
        String protocolType = optionalText(request, "protocolType");
        if (protocolType == null) {
            protocolType = gateway.getProtocolType() == null ? "MCP" : gateway.getProtocolType();
        }
        if (!PROTOCOL_TYPES.contains(protocolType)) {
            throw validation("protocolType is invalid.");
        }
        gateway.setName(name);
        gateway.setDescription(optionalText(request, "description"));
        gateway.setRoleArn(requireText(request, "roleArn"));
        gateway.setProtocolType(protocolType);
        if (request.has("protocolConfiguration")) {
            gateway.setProtocolConfiguration(optionalCopy(request, "protocolConfiguration"));
        }
        gateway.setAuthorizerType(authorizerType);
        if (request.has("authorizerConfiguration")) {
            gateway.setAuthorizerConfiguration(optionalCopy(request, "authorizerConfiguration"));
        }
        if (request.has("kmsKeyArn")) {
            gateway.setKmsKeyArn(optionalText(request, "kmsKeyArn"));
        }
        if (request.has("exceptionLevel")) {
            gateway.setExceptionLevel(optionalText(request, "exceptionLevel"));
        }
        if (request.has("interceptorConfigurations")) {
            gateway.setInterceptorConfigurations(optionalCopy(request, "interceptorConfigurations"));
        }
        if (request.has("policyEngineConfiguration")) {
            gateway.setPolicyEngineConfiguration(optionalCopy(request, "policyEngineConfiguration"));
        }
        if (request.has("customTransformConfiguration")) {
            gateway.setCustomTransformConfiguration(optionalCopy(request, "customTransformConfiguration"));
        }
        if (request.has("wafConfiguration") || request.has("webAclArn")) {
            // accepted and ignored — WAF association is out of scope for the emulator
        }
        gateway.setUpdatedAt(isoTimestamp());
        gatewayStore.put(storageKey(region, gateway.getGatewayId()), gateway);
        return gateway;
    }

    public synchronized Gateway deleteGateway(String region, String gatewayIdentifier) {
        Gateway gateway = requireGateway(region, gatewayIdentifier);
        gateway.setStatus("DELETING");
        gateway.setUpdatedAt(isoTimestamp());
        gatewayStore.delete(storageKey(region, gateway.getGatewayId()));
        return gateway;
    }

    public Page<Gateway> listGateways(String region, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        List<Gateway> gateways = gatewayStore.scan(key -> key.startsWith(region + "::"));
        gateways.sort(Comparator.comparing(Gateway::getName, Comparator.nullsLast(String::compareTo)));
        return page(gateways, maxResults, nextToken, GATEWAY_TOKEN_PREFIX);
    }

    public synchronized AgentRuntime createAgentRuntime(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "agentRuntimeName");
        validateName(name);
        if (findLiveRuntimeByName(region, name) != null) {
            throw new AwsException("ConflictException", "Agent runtime " + name + " already exists.", 409);
        }
        String roleArn = requireText(request, "roleArn");
        JsonNode artifact = readRuntimeArtifact(request);
        JsonNode networkConfiguration = readNetworkConfiguration(request, NETWORK_MODES, "PUBLIC");
        JsonNode protocolConfiguration = readRuntimeProtocol(request);
        JsonNode lifecycleConfiguration = readLifecycleConfiguration(request);
        String now = isoTimestamp();
        String runtimeId = name + "-" + shortId();
        AgentRuntime runtime = new AgentRuntime();
        runtime.setAgentRuntimeId(runtimeId);
        runtime.setAgentRuntimeArn(arn(region, RESOURCE_RUNTIME, runtimeId));
        runtime.setAgentRuntimeName(name);
        runtime.setAgentRuntimeVersion("1");
        runtime.setDescription(optionalText(request, "description"));
        runtime.setRoleArn(roleArn);
        runtime.setAgentRuntimeArtifact(artifact);
        runtime.setNetworkConfiguration(networkConfiguration);
        runtime.setProtocolConfiguration(protocolConfiguration);
        runtime.setAuthorizerConfiguration(optionalCopy(request, "authorizerConfiguration"));
        runtime.setRequestHeaderConfiguration(optionalCopy(request, "requestHeaderConfiguration"));
        runtime.setLifecycleConfiguration(lifecycleConfiguration);
        runtime.setEnvironmentVariables(optionalCopy(request, "environmentVariables"));
        runtime.setMetadataConfiguration(optionalCopy(request, "metadataConfiguration"));
        runtime.setFilesystemConfigurations(optionalCopy(request, "filesystemConfigurations"));
        ObjectNode identity = JsonNodeFactory.instance.objectNode();
        identity.put(
                "workloadIdentityArn",
                arn(region, "workload-identity-directory/default/workload-identity", name));
        runtime.setWorkloadIdentityDetails(identity);
        runtime.setStatus("READY");
        runtime.setCreatedAt(now);
        runtime.setLastUpdatedAt(now);
        runtime.setTags(readTags(request.get("tags")));
        runtimeStore.put(storageKey(region, runtimeId), runtime);
        return runtime;
    }

    public AgentRuntime getAgentRuntime(String region, String agentRuntimeId) {
        return requireRuntime(region, agentRuntimeId);
    }

    public synchronized AgentRuntime updateAgentRuntime(String region, String agentRuntimeId, JsonNode request) {
        AgentRuntime runtime = requireRuntime(region, agentRuntimeId);
        requireObject(request, "Request body");
        runtime.setAgentRuntimeArtifact(readRuntimeArtifact(request));
        runtime.setRoleArn(requireText(request, "roleArn"));
        runtime.setNetworkConfiguration(readNetworkConfiguration(request, NETWORK_MODES, "PUBLIC"));
        if (request.has("description")) {
            runtime.setDescription(optionalText(request, "description"));
        }
        if (request.has("protocolConfiguration")) {
            runtime.setProtocolConfiguration(readRuntimeProtocol(request));
        }
        if (request.has("authorizerConfiguration")) {
            runtime.setAuthorizerConfiguration(optionalCopy(request, "authorizerConfiguration"));
        }
        if (request.has("requestHeaderConfiguration")) {
            runtime.setRequestHeaderConfiguration(optionalCopy(request, "requestHeaderConfiguration"));
        }
        if (request.has("lifecycleConfiguration")) {
            runtime.setLifecycleConfiguration(readLifecycleConfiguration(request));
        }
        if (request.has("environmentVariables")) {
            runtime.setEnvironmentVariables(optionalCopy(request, "environmentVariables"));
        }
        if (request.has("metadataConfiguration")) {
            runtime.setMetadataConfiguration(optionalCopy(request, "metadataConfiguration"));
        }
        if (request.has("filesystemConfigurations")) {
            runtime.setFilesystemConfigurations(optionalCopy(request, "filesystemConfigurations"));
        }
        runtime.setAgentRuntimeVersion(nextVersion(runtime.getAgentRuntimeVersion()));
        runtime.setStatus("READY");
        runtime.setLastUpdatedAt(isoTimestamp());
        runtimeStore.put(storageKey(region, runtime.getAgentRuntimeId()), runtime);
        return runtime;
    }

    public synchronized AgentRuntime deleteAgentRuntime(String region, String agentRuntimeId) {
        AgentRuntime runtime = requireRuntime(region, agentRuntimeId);
        runtime.setStatus("DELETING");
        runtime.setLastUpdatedAt(isoTimestamp());
        runtimeStore.delete(storageKey(region, runtime.getAgentRuntimeId()));
        return runtime;
    }

    public Page<AgentRuntime> listAgentRuntimes(String region, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        List<AgentRuntime> runtimes = runtimeStore.scan(key -> key.startsWith(region + "::"));
        runtimes.sort(Comparator.comparing(
                AgentRuntime::getAgentRuntimeName, Comparator.nullsLast(String::compareTo)));
        return page(runtimes, maxResults, nextToken, RUNTIME_TOKEN_PREFIX);
    }

    public synchronized MemoryEvent createEvent(String region, String memoryId, JsonNode request) {
        MemoryResource memory = requireMemory(region, memoryId);
        requireObject(request, "Request body");
        String actorId = requireText(request, "actorId");
        String sessionId = optionalText(request, "sessionId");
        if (sessionId == null) {
            sessionId = "session-" + shortId();
        }
        MemoryEvent event = new MemoryEvent();
        event.setMemoryId(memory.getId());
        event.setActorId(actorId);
        event.setSessionId(sessionId);
        event.setEventId("evt-" + shortId());
        event.setEventTimestamp(readEpochSeconds(request, "eventTimestamp", epochSeconds()));
        event.setPayload(optionalCopy(request, "payload"));
        event.setBranch(optionalCopy(request, "branch"));
        event.setMetadata(optionalCopy(request, "metadata"));
        memory.getEvents().add(event);
        memory.setUpdatedAt(epochSeconds());
        memoryStore.put(storageKey(region, memory.getId()), memory);
        return event;
    }

    public List<MemoryEvent> listEvents(String region, String memoryId, String actorId, String sessionId) {
        MemoryResource memory = requireMemory(region, memoryId);
        List<MemoryEvent> matches = new ArrayList<>();
        for (MemoryEvent event : memory.getEvents()) {
            if (actorId.equals(event.getActorId()) && sessionId.equals(event.getSessionId())) {
                matches.add(event);
            }
        }
        return matches;
    }

    public MemoryEvent getEvent(
            String region, String memoryId, String actorId, String sessionId, String eventId) {
        MemoryEvent event = findEvent(requireMemory(region, memoryId), actorId, sessionId, eventId);
        if (event == null) {
            throw resourceNotFound("Event " + eventId + " was not found.");
        }
        return event;
    }

    public synchronized String deleteEvent(
            String region, String memoryId, String actorId, String sessionId, String eventId) {
        MemoryResource memory = requireMemory(region, memoryId);
        MemoryEvent event = findEvent(memory, actorId, sessionId, eventId);
        if (event == null) {
            throw resourceNotFound("Event " + eventId + " was not found.");
        }
        memory.getEvents().remove(event);
        memory.setUpdatedAt(epochSeconds());
        memoryStore.put(storageKey(region, memory.getId()), memory);
        return event.getEventId();
    }

    public List<String> listActors(String region, String memoryId) {
        MemoryResource memory = requireMemory(region, memoryId);
        Set<String> actors = new LinkedHashSet<>();
        for (MemoryEvent event : memory.getEvents()) {
            actors.add(event.getActorId());
        }
        return new ArrayList<>(actors);
    }

    public List<MemoryEvent> listSessions(String region, String memoryId, String actorId) {
        MemoryResource memory = requireMemory(region, memoryId);
        Map<String, MemoryEvent> firstBySession = new LinkedHashMap<>();
        for (MemoryEvent event : memory.getEvents()) {
            if (actorId.equals(event.getActorId())) {
                firstBySession.putIfAbsent(event.getSessionId(), event);
            }
        }
        return new ArrayList<>(firstBySession.values());
    }

    public synchronized BatchResult batchCreateRecords(String region, String memoryId, JsonNode request) {
        MemoryResource memory = requireMemory(region, memoryId);
        requireObject(request, "Request body");
        JsonNode recordsNode = request.get("records");
        if (recordsNode == null || !recordsNode.isArray()) {
            throw validation("records is required.");
        }
        List<MemoryRecordItem> successful = new ArrayList<>();
        for (JsonNode recordNode : recordsNode) {
            requireObject(recordNode, "records[]");
            MemoryRecordItem record = new MemoryRecordItem();
            record.setMemoryRecordId("rec-" + shortId());
            record.setRequestIdentifier(requireText(recordNode, "requestIdentifier"));
            record.setContent(optionalCopy(recordNode, "content"));
            record.setMemoryStrategyId(optionalText(recordNode, "memoryStrategyId"));
            record.setNamespaces(readStringList(recordNode.get("namespaces")));
            record.setCreatedAt(readEpochSeconds(recordNode, "timestamp", epochSeconds()));
            record.setMetadata(optionalCopy(recordNode, "metadata"));
            memory.getRecords().add(record);
            successful.add(record);
        }
        memory.setUpdatedAt(epochSeconds());
        memoryStore.put(storageKey(region, memory.getId()), memory);
        return new BatchResult(successful, List.of());
    }

    public synchronized BatchResult batchUpdateRecords(String region, String memoryId, JsonNode request) {
        MemoryResource memory = requireMemory(region, memoryId);
        requireObject(request, "Request body");
        JsonNode recordsNode = request.get("records");
        if (recordsNode == null || !recordsNode.isArray()) {
            throw validation("records is required.");
        }
        List<MemoryRecordItem> successful = new ArrayList<>();
        List<MemoryRecordItem> failed = new ArrayList<>();
        for (JsonNode recordNode : recordsNode) {
            requireObject(recordNode, "records[]");
            String recordId = requireText(recordNode, "memoryRecordId");
            MemoryRecordItem record = findRecord(memory, recordId);
            if (record == null) {
                MemoryRecordItem missing = new MemoryRecordItem();
                missing.setMemoryRecordId(recordId);
                failed.add(missing);
                continue;
            }
            if (recordNode.has("content")) {
                record.setContent(optionalCopy(recordNode, "content"));
            }
            if (recordNode.has("namespaces")) {
                record.setNamespaces(readStringList(recordNode.get("namespaces")));
            }
            if (recordNode.has("memoryStrategyId")) {
                record.setMemoryStrategyId(optionalText(recordNode, "memoryStrategyId"));
            }
            if (recordNode.has("metadata")) {
                record.setMetadata(optionalCopy(recordNode, "metadata"));
            }
            if (recordNode.has("timestamp")) {
                record.setCreatedAt(readEpochSeconds(recordNode, "timestamp", record.getCreatedAt()));
            }
            successful.add(record);
        }
        memory.setUpdatedAt(epochSeconds());
        memoryStore.put(storageKey(region, memory.getId()), memory);
        return new BatchResult(successful, failed);
    }

    public synchronized BatchResult batchDeleteRecords(String region, String memoryId, JsonNode request) {
        MemoryResource memory = requireMemory(region, memoryId);
        requireObject(request, "Request body");
        JsonNode recordsNode = request.get("records");
        if (recordsNode == null || !recordsNode.isArray()) {
            throw validation("records is required.");
        }
        List<MemoryRecordItem> successful = new ArrayList<>();
        for (JsonNode recordNode : recordsNode) {
            requireObject(recordNode, "records[]");
            String recordId = requireText(recordNode, "memoryRecordId");
            MemoryRecordItem record = findRecord(memory, recordId);
            if (record != null) {
                memory.getRecords().remove(record);
                successful.add(record);
            }
        }
        memory.setUpdatedAt(epochSeconds());
        memoryStore.put(storageKey(region, memory.getId()), memory);
        return new BatchResult(successful, List.of());
    }

    public MemoryRecordItem getMemoryRecord(String region, String memoryId, String memoryRecordId) {
        MemoryRecordItem record = findRecord(requireMemory(region, memoryId), memoryRecordId);
        if (record == null) {
            throw resourceNotFound("Memory record " + memoryRecordId + " was not found.");
        }
        return record;
    }

    public synchronized String deleteMemoryRecord(String region, String memoryId, String memoryRecordId) {
        MemoryResource memory = requireMemory(region, memoryId);
        MemoryRecordItem record = findRecord(memory, memoryRecordId);
        if (record == null) {
            throw resourceNotFound("Memory record " + memoryRecordId + " was not found.");
        }
        memory.getRecords().remove(record);
        memory.setUpdatedAt(epochSeconds());
        memoryStore.put(storageKey(region, memory.getId()), memory);
        return record.getMemoryRecordId();
    }

    public List<MemoryRecordItem> listMemoryRecords(String region, String memoryId, JsonNode request) {
        MemoryResource memory = requireMemory(region, memoryId);
        String namespace = optionalText(request, "namespace");
        List<MemoryRecordItem> matches = new ArrayList<>();
        for (MemoryRecordItem record : memory.getRecords()) {
            if (namespace == null || record.getNamespaces().contains(namespace)) {
                matches.add(record);
            }
        }
        return matches;
    }

    public List<MemoryRecordItem> retrieveMemoryRecords(String region, String memoryId, JsonNode request) {
        MemoryResource memory = requireMemory(region, memoryId);
        requireObject(request, "Request body");
        String namespace = optionalText(request, "namespace");
        String query = null;
        JsonNode criteria = request.get("searchCriteria");
        if (criteria != null && criteria.isObject()) {
            query = optionalText(criteria, "searchQuery");
        }
        String needle = query == null ? "" : query.toLowerCase();
        List<MemoryRecordItem> matches = new ArrayList<>();
        for (MemoryRecordItem record : memory.getRecords()) {
            if (namespace != null && !record.getNamespaces().contains(namespace)) {
                continue;
            }
            if (!needle.isEmpty() && !contentText(record).toLowerCase().contains(needle)) {
                continue;
            }
            matches.add(record);
        }
        return matches;
    }

    public List<JsonNode> listExtractionJobs(String region, String memoryId) {
        return List.copyOf(requireMemory(region, memoryId).getExtractionJobs());
    }

    public synchronized String startExtractionJob(String region, String memoryId, JsonNode request) {
        MemoryResource memory = requireMemory(region, memoryId);
        requireObject(request, "Request body");
        JsonNode extractionJob = request.get("extractionJob");
        String jobId = extractionJob != null && extractionJob.isObject()
                ? optionalText(extractionJob, "jobId")
                : null;
        if (jobId == null) {
            jobId = "job-" + shortId();
        }
        ObjectNode job = JsonNodeFactory.instance.objectNode();
        job.put("jobID", jobId);
        ObjectNode messages = job.putObject("messages");
        messages.putArray("messagesList");
        job.put("status", "FAILED");
        memory.getExtractionJobs().add(job);
        memoryStore.put(storageKey(region, memory.getId()), memory);
        return jobId;
    }

    public synchronized AgentCoreSession startBrowserSession(
            String region, String browserId, JsonNode request) {
        requireBrowser(region, browserId);
        return startSession(browserSessionStore, region, browserId, request);
    }

    public AgentCoreSession getBrowserSession(String region, String browserId, String sessionId) {
        requireBrowser(region, browserId);
        return requireSession(browserSessionStore, region, browserId, sessionId);
    }

    public List<AgentCoreSession> listBrowserSessions(String region, String browserId, JsonNode request) {
        requireBrowser(region, browserId);
        return listSessions(browserSessionStore, region, browserId, optionalText(request, "status"));
    }

    public synchronized AgentCoreSession stopBrowserSession(String region, String browserId, String sessionId) {
        requireBrowser(region, browserId);
        return stopSession(browserSessionStore, region, browserId, sessionId);
    }

    public AgentCoreSession requireReadyBrowserSession(String region, String browserId, String sessionId) {
        requireBrowser(region, browserId);
        AgentCoreSession session = requireSession(browserSessionStore, region, browserId, sessionId);
        if (!"READY".equals(session.getStatus())) {
            throw new AwsException(
                    "ConflictException",
                    "Browser session " + sessionId + " is not READY.",
                    409);
        }
        return session;
    }

    public synchronized AgentCoreSession startCodeInterpreterSession(
            String region, String codeInterpreterId, JsonNode request) {
        requireCodeInterpreter(region, codeInterpreterId);
        return startSession(codeSessionStore, region, codeInterpreterId, request);
    }

    public AgentCoreSession getCodeInterpreterSession(
            String region, String codeInterpreterId, String sessionId) {
        requireCodeInterpreter(region, codeInterpreterId);
        return requireSession(codeSessionStore, region, codeInterpreterId, sessionId);
    }

    public List<AgentCoreSession> listCodeInterpreterSessions(
            String region, String codeInterpreterId, JsonNode request) {
        requireCodeInterpreter(region, codeInterpreterId);
        return listSessions(codeSessionStore, region, codeInterpreterId, optionalText(request, "status"));
    }

    public synchronized AgentCoreSession stopCodeInterpreterSession(
            String region, String codeInterpreterId, String sessionId) {
        requireCodeInterpreter(region, codeInterpreterId);
        return stopSession(codeSessionStore, region, codeInterpreterId, sessionId);
    }

    public AgentCoreSession requireReadyCodeSession(
            String region, String codeInterpreterId, String sessionId) {
        requireCodeInterpreter(region, codeInterpreterId);
        AgentCoreSession session = requireSession(codeSessionStore, region, codeInterpreterId, sessionId);
        if (!"READY".equals(session.getStatus())) {
            throw new AwsException(
                    "ConflictException",
                    "Code interpreter session " + sessionId + " is not READY.",
                    409);
        }
        return session;
    }

    public String executeCode(JsonNode request) {
        JsonNode arguments = request.path("arguments");
        String code = arguments.path("code").asText("");
        Matcher matcher = PRINT_MUL.matcher(code);
        if (matcher.find()) {
            long left = Long.parseLong(matcher.group(1));
            long right = Long.parseLong(matcher.group(2));
            return (left * right) + "\n";
        }
        return code.isBlank() ? "ok\n" : code + "\n";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Tagged tagged = requireTagged(region, arn);
        return tagged.tags() == null ? Map.of() : tagged.tags();
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = tagged.tags() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(tagged.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        tagged.setTags(current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(region, arn);
        if (tagged.tags() != null && tagKeys != null) {
            Map<String, String> current = new LinkedHashMap<>(tagged.tags());
            tagKeys.forEach(current::remove);
            tagged.setTags(current);
        }
    }

    private AgentCoreSession startSession(
            StorageBackend<String, AgentCoreSession> store,
            String region,
            String resourceId,
            JsonNode request) {
        JsonNode body = request == null || request.isNull() ? JsonNodeFactory.instance.objectNode() : request;
        String now = isoTimestamp();
        AgentCoreSession session = new AgentCoreSession();
        session.setResourceId(resourceId);
        session.setSessionId("sess-" + shortId());
        session.setName(optionalText(body, "name"));
        session.setStatus("READY");
        session.setCreatedAt(now);
        session.setLastUpdatedAt(now);
        if (body.has("sessionTimeoutSeconds") && body.get("sessionTimeoutSeconds").isNumber()) {
            session.setSessionTimeoutSeconds(body.get("sessionTimeoutSeconds").intValue());
        }
        store.put(sessionKey(region, resourceId, session.getSessionId()), session);
        return session;
    }

    private AgentCoreSession requireSession(
            StorageBackend<String, AgentCoreSession> store,
            String region,
            String resourceId,
            String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw validation("sessionId is required.");
        }
        return store.get(sessionKey(region, resourceId, decode(sessionId)))
                .orElseThrow(() -> resourceNotFound("Session " + sessionId + " was not found."));
    }

    private List<AgentCoreSession> listSessions(
            StorageBackend<String, AgentCoreSession> store,
            String region,
            String resourceId,
            String status) {
        String prefix = region + "::" + resourceId + "::";
        List<AgentCoreSession> sessions = store.scan(key -> key.startsWith(prefix));
        if (status != null && !status.isBlank()) {
            sessions.removeIf(session -> !status.equals(session.getStatus()));
        }
        sessions.sort(Comparator.comparing(AgentCoreSession::getCreatedAt, Comparator.nullsLast(String::compareTo)));
        return sessions;
    }

    private AgentCoreSession stopSession(
            StorageBackend<String, AgentCoreSession> store,
            String region,
            String resourceId,
            String sessionId) {
        AgentCoreSession session = requireSession(store, region, resourceId, sessionId);
        if ("TERMINATED".equals(session.getStatus())) {
            throw new AwsException(
                    "ConflictException",
                    "Session " + session.getSessionId() + " is already TERMINATED.",
                    409);
        }
        session.setStatus("TERMINATED");
        session.setLastUpdatedAt(isoTimestamp());
        store.put(sessionKey(region, resourceId, session.getSessionId()), session);
        return session;
    }

    private Browser findLiveBrowserByName(String region, String name) {
        for (Browser browser : browserStore.scan(key -> key.startsWith(region + "::"))) {
            if (name.equals(browser.getName())) {
                return browser;
            }
        }
        return null;
    }

    private MemoryResource findLiveMemoryByName(String region, String name) {
        for (MemoryResource memory : memoryStore.scan(key -> key.startsWith(region + "::"))) {
            if (name.equals(memory.getName())) {
                return memory;
            }
        }
        return null;
    }

    private CodeInterpreter findLiveCodeInterpreterByName(String region, String name) {
        for (CodeInterpreter interpreter : codeInterpreterStore.scan(key -> key.startsWith(region + "::"))) {
            if (name.equals(interpreter.getName())) {
                return interpreter;
            }
        }
        return null;
    }

    private Gateway findLiveGatewayByName(String region, String name) {
        for (Gateway gateway : gatewayStore.scan(key -> key.startsWith(region + "::"))) {
            if (name.equals(gateway.getName())) {
                return gateway;
            }
        }
        return null;
    }

    private Browser requireBrowser(String region, String browserId) {
        String decoded = requireId(browserId, "browserId");
        return browserStore.get(storageKey(region, decoded)).orElseThrow(() -> resourceNotFound(decoded));
    }

    private MemoryResource requireMemory(String region, String memoryId) {
        String decoded = requireId(memoryId, "memoryId");
        return memoryStore.get(storageKey(region, decoded))
                .orElseThrow(() -> resourceNotFound("Memory " + decoded + " was not found."));
    }

    private CodeInterpreter requireCodeInterpreter(String region, String codeInterpreterId) {
        String decoded = requireId(codeInterpreterId, "codeInterpreterId");
        return codeInterpreterStore.get(storageKey(region, decoded))
                .orElseThrow(() -> resourceNotFound("Code interpreter " + decoded + " was not found."));
    }

    private Gateway requireGateway(String region, String gatewayIdentifier) {
        String decoded = requireId(gatewayIdentifier, "gatewayIdentifier");
        return gatewayStore.get(storageKey(region, decoded))
                .orElseThrow(() -> resourceNotFound("Gateway " + decoded + " was not found."));
    }

    private AgentRuntime findLiveRuntimeByName(String region, String name) {
        for (AgentRuntime runtime : runtimeStore.scan(key -> key.startsWith(region + "::"))) {
            if (name.equals(runtime.getAgentRuntimeName())) {
                return runtime;
            }
        }
        return null;
    }

    private AgentRuntime requireRuntime(String region, String agentRuntimeId) {
        String decoded = requireId(agentRuntimeId, "agentRuntimeId");
        return runtimeStore.get(storageKey(region, decoded))
                .orElseThrow(() -> resourceNotFound("Agent runtime " + decoded + " was not found."));
    }

    private String gatewayUrl(String region, String gatewayId) {
        return "https://" + gatewayId + ".gateway.bedrock-agentcore." + region + ".amazonaws.com/mcp";
    }

    private Tagged requireTagged(String region, String arn) {
        String decoded = decode(arn);
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decoded);
        } catch (IllegalArgumentException e) {
            throw resourceNotFound(decoded);
        }
        if (!SERVICE.equals(parsed.service()) || parsed.resource() == null) {
            throw resourceNotFound(decoded);
        }
        String resource = parsed.resource();
        if (resource.startsWith(RESOURCE_BROWSER + "/")) {
            Browser browser = requireBrowser(region, resource.substring((RESOURCE_BROWSER + "/").length()));
            if (!decoded.equals(browser.getBrowserArn())) {
                throw resourceNotFound(decoded);
            }
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return browser.getTags();
                }

                @Override
                public void setTags(Map<String, String> tags) {
                    browser.setTags(tags);
                    browser.setLastUpdatedAt(isoTimestamp());
                    browserStore.put(storageKey(region, browser.getBrowserId()), browser);
                }
            };
        }
        if (resource.startsWith(RESOURCE_MEMORY + "/")) {
            MemoryResource memory = requireMemory(region, resource.substring((RESOURCE_MEMORY + "/").length()));
            if (!decoded.equals(memory.getArn())) {
                throw resourceNotFound(decoded);
            }
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return memory.getTags();
                }

                @Override
                public void setTags(Map<String, String> tags) {
                    memory.setTags(tags);
                    memory.setUpdatedAt(epochSeconds());
                    memoryStore.put(storageKey(region, memory.getId()), memory);
                }
            };
        }
        if (resource.startsWith(RESOURCE_CODE_INTERPRETER + "/")) {
            CodeInterpreter interpreter = requireCodeInterpreter(
                    region, resource.substring((RESOURCE_CODE_INTERPRETER + "/").length()));
            if (!decoded.equals(interpreter.getCodeInterpreterArn())) {
                throw resourceNotFound(decoded);
            }
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return interpreter.getTags();
                }

                @Override
                public void setTags(Map<String, String> tags) {
                    interpreter.setTags(tags);
                    interpreter.setLastUpdatedAt(isoTimestamp());
                    codeInterpreterStore.put(
                            storageKey(region, interpreter.getCodeInterpreterId()), interpreter);
                }
            };
        }
        if (resource.startsWith(RESOURCE_GATEWAY + "/")) {
            Gateway gateway = requireGateway(region, resource.substring((RESOURCE_GATEWAY + "/").length()));
            if (!decoded.equals(gateway.getGatewayArn())) {
                throw resourceNotFound(decoded);
            }
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return gateway.getTags();
                }

                @Override
                public void setTags(Map<String, String> tags) {
                    gateway.setTags(tags);
                    gateway.setUpdatedAt(isoTimestamp());
                    gatewayStore.put(storageKey(region, gateway.getGatewayId()), gateway);
                }
            };
        }
        if (resource.startsWith(RESOURCE_RUNTIME + "/")) {
            AgentRuntime runtime = requireRuntime(region, resource.substring((RESOURCE_RUNTIME + "/").length()));
            if (!decoded.equals(runtime.getAgentRuntimeArn())) {
                throw resourceNotFound(decoded);
            }
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return runtime.getTags();
                }

                @Override
                public void setTags(Map<String, String> tags) {
                    runtime.setTags(tags);
                    runtime.setLastUpdatedAt(isoTimestamp());
                    runtimeStore.put(storageKey(region, runtime.getAgentRuntimeId()), runtime);
                }
            };
        }
        throw resourceNotFound(decoded);
    }

    private String arn(String region, String kind, String id) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), kind + "/" + id).toString();
    }

    private static MemoryEvent findEvent(
            MemoryResource memory, String actorId, String sessionId, String eventId) {
        for (MemoryEvent event : memory.getEvents()) {
            if (eventId.equals(event.getEventId())
                    && actorId.equals(event.getActorId())
                    && sessionId.equals(event.getSessionId())) {
                return event;
            }
        }
        return null;
    }

    private static MemoryRecordItem findRecord(MemoryResource memory, String memoryRecordId) {
        for (MemoryRecordItem record : memory.getRecords()) {
            if (memoryRecordId.equals(record.getMemoryRecordId())) {
                return record;
            }
        }
        return null;
    }

    private static JsonNode buildStrategies(JsonNode input, long now) {
        ArrayNode strategies = JsonNodeFactory.instance.arrayNode();
        if (input == null || input.isNull()) {
            return strategies;
        }
        if (!input.isArray()) {
            throw validation("memoryStrategies must be an array.");
        }
        for (JsonNode item : input) {
            requireObject(item, "memoryStrategies[]");
            ObjectNode strategy = strategies.addObject();
            StrategyKind kind = strategyKind(item);
            JsonNode nested = item.get(kind.field());
            requireObject(nested, kind.field());
            strategy.put("strategyId", kind.type().toLowerCase() + "-" + shortId());
            strategy.put("name", requireText(nested, "name"));
            if (nested.has("description") && nested.get("description").isTextual()) {
                strategy.put("description", nested.get("description").textValue());
            }
            strategy.put("type", kind.type());
            ArrayNode namespaces = strategy.putArray("namespaces");
            for (String namespace : readStringList(nested.get("namespaces"))) {
                namespaces.add(namespace);
            }
            ArrayNode templates = strategy.putArray("namespaceTemplates");
            List<String> templateValues = readStringList(nested.get("namespaceTemplates"));
            if (templateValues.isEmpty()) {
                templateValues = readStringList(nested.get("namespaces"));
            }
            for (String template : templateValues) {
                templates.add(template);
            }
            strategy.put("status", "ACTIVE");
            strategy.put("createdAt", now);
            strategy.put("updatedAt", now);
        }
        return strategies;
    }

    private static StrategyKind strategyKind(JsonNode item) {
        if (item.has("semanticMemoryStrategy")) {
            return new StrategyKind("semanticMemoryStrategy", "SEMANTIC");
        }
        if (item.has("summaryMemoryStrategy")) {
            return new StrategyKind("summaryMemoryStrategy", "SUMMARIZATION");
        }
        if (item.has("userPreferenceMemoryStrategy")) {
            return new StrategyKind("userPreferenceMemoryStrategy", "USER_PREFERENCE");
        }
        if (item.has("customMemoryStrategy")) {
            return new StrategyKind("customMemoryStrategy", "CUSTOM");
        }
        if (item.has("episodicMemoryStrategy")) {
            return new StrategyKind("episodicMemoryStrategy", "EPISODIC");
        }
        throw validation("memoryStrategies entry is missing a strategy member.");
    }

    private static String contentText(MemoryRecordItem record) {
        JsonNode content = record.getContent();
        if (content != null && content.has("text") && content.get("text").isTextual()) {
            return content.get("text").textValue();
        }
        return "";
    }

    private static JsonNode readNetworkConfiguration(
            JsonNode request, Set<String> allowedModes, String defaultMode) {
        JsonNode configuration = request.get("networkConfiguration");
        if (configuration == null || configuration.isNull()) {
            ObjectNode defaults = JsonNodeFactory.instance.objectNode();
            defaults.put("networkMode", defaultMode);
            return defaults;
        }
        requireObject(configuration, "networkConfiguration");
        String networkMode = configuration.has("networkMode")
                ? requireText(configuration, "networkMode")
                : defaultMode;
        if (!allowedModes.contains(networkMode)) {
            throw validation("networkConfiguration.networkMode is invalid.");
        }
        if ("VPC".equals(networkMode)) {
            JsonNode vpcConfig = configuration.get("vpcConfig");
            if (vpcConfig == null || vpcConfig.isNull() || !vpcConfig.isObject()) {
                throw validation("networkConfiguration.vpcConfig is required when networkMode is VPC.");
            }
        }
        return configuration.deepCopy();
    }

    private static JsonNode readRuntimeArtifact(JsonNode request) {
        JsonNode artifact = request.get("agentRuntimeArtifact");
        requireObject(artifact, "agentRuntimeArtifact");
        boolean container = artifact.has("containerConfiguration")
                && artifact.get("containerConfiguration") != null
                && artifact.get("containerConfiguration").isObject();
        boolean code = artifact.has("codeConfiguration")
                && artifact.get("codeConfiguration") != null
                && artifact.get("codeConfiguration").isObject();
        if (container == code) {
            throw validation("agentRuntimeArtifact must contain containerConfiguration or codeConfiguration.");
        }
        if (container) {
            requireText(artifact.get("containerConfiguration"), "containerUri");
        }
        return artifact.deepCopy();
    }

    private static JsonNode readRuntimeProtocol(JsonNode request) {
        JsonNode configuration = optionalCopy(request, "protocolConfiguration");
        if (configuration == null) {
            ObjectNode defaults = JsonNodeFactory.instance.objectNode();
            defaults.put("serverProtocol", "HTTP");
            return defaults;
        }
        requireObject(configuration, "protocolConfiguration");
        String protocol = requireText(configuration, "serverProtocol");
        if (!RUNTIME_PROTOCOLS.contains(protocol)) {
            throw validation("protocolConfiguration.serverProtocol is invalid.");
        }
        return configuration.deepCopy();
    }

    private static JsonNode readLifecycleConfiguration(JsonNode request) {
        JsonNode configuration = optionalCopy(request, "lifecycleConfiguration");
        if (configuration == null) {
            return JsonNodeFactory.instance.objectNode();
        }
        requireObject(configuration, "lifecycleConfiguration");
        return configuration.deepCopy();
    }

    private static String nextVersion(String current) {
        try {
            return Integer.toString(Integer.parseInt(current) + 1);
        } catch (NumberFormatException e) {
            return "1";
        }
    }

    private static JsonNode optionalCopy(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        return parent.get(field).deepCopy();
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
            if (!entry.getValue().isTextual()) {
                throw validation("tags values must be strings.");
            }
            tags.put(entry.getKey(), entry.getValue().textValue());
        });
        return tags;
    }

    private static List<String> readStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (!node.isArray()) {
            throw validation("expected an array of strings.");
        }
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw validation("array values must be strings.");
            }
            values.add(item.textValue());
        }
        return values;
    }

    private static void validateName(String name) {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw validation("name must match [a-zA-Z][a-zA-Z0-9_]{0,47}.");
        }
    }

    private static void validateGatewayName(String name) {
        if (!GATEWAY_NAME_PATTERN.matcher(name).matches()) {
            throw validation("name must match ([0-9a-zA-Z][-]?){1,100}.");
        }
    }

    private static int parseMaxResults(String maxResultsValue) {
        if (maxResultsValue == null || maxResultsValue.isBlank()) {
            return DEFAULT_MAX_RESULTS;
        }
        int maxResults;
        try {
            maxResults = Integer.parseInt(maxResultsValue);
        } catch (NumberFormatException e) {
            throw validation("maxResults must be an integer.");
        }
        if (maxResults < 1 || maxResults > MAX_RESULTS) {
            throw validation("maxResults must be between 1 and " + MAX_RESULTS + ".");
        }
        return maxResults;
    }

    private static <T> Page<T> page(List<T> items, int maxResults, String nextToken, String tokenPrefix) {
        int offset = decodeOffset(nextToken, items.size(), tokenPrefix);
        int end = Math.min(offset + maxResults, items.size());
        String responseToken = end < items.size() ? encodeOffset(end, tokenPrefix) : null;
        return new Page<>(items.subList(offset, end), responseToken);
    }

    private static int decodeOffset(String nextToken, int size, String tokenPrefix) {
        if (nextToken == null || nextToken.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(nextToken), StandardCharsets.UTF_8);
            if (!decoded.startsWith(tokenPrefix)) {
                throw validation("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(tokenPrefix.length()));
            if (offset < 0 || offset > size) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset, String tokenPrefix) {
        return Base64.getEncoder().encodeToString((tokenPrefix + offset).getBytes(StandardCharsets.UTF_8));
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

    private static String requireId(String value, String field) {
        String decoded = decode(value);
        if (decoded == null || decoded.isBlank()) {
            throw validation(field + " is required.");
        }
        return decoded;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " is required.");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    private static String optionalTextOrNumber(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (value.isNumber()) {
            return Integer.toString(value.intValue());
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        throw validation(field + " must be a number or string.");
    }

    private static int requireInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isNumber()) {
            throw validation(field + " is required.");
        }
        return value.intValue();
    }

    private static long readEpochSeconds(JsonNode parent, String field, long fallback) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return fallback;
        }
        JsonNode value = parent.get(field);
        if (value.isNumber()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            try {
                return Instant.parse(value.textValue()).getEpochSecond();
            } catch (Exception e) {
                throw validation(field + " must be epoch seconds.");
            }
        }
        throw validation(field + " must be epoch seconds.");
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static String sessionKey(String region, String resourceId, String sessionId) {
        return region + "::" + resourceId + "::" + sessionId;
    }

    private static String isoTimestamp() {
        return Instant.now().toString();
    }

    private static long epochSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException resourceNotFound(String identifier) {
        return new AwsException("ResourceNotFoundException", identifier, 404);
    }

    public record Page<T>(List<T> items, String nextToken) {
        public List<Browser> browsers() {
            @SuppressWarnings("unchecked")
            List<Browser> browsers = (List<Browser>) items;
            return browsers;
        }
    }

    public record BatchResult(List<MemoryRecordItem> successful, List<MemoryRecordItem> failed) {
    }

    private record StrategyKind(String field, String type) {
    }

    private interface Tagged {
        Map<String, String> tags();

        void setTags(Map<String, String> tags);
    }
}
