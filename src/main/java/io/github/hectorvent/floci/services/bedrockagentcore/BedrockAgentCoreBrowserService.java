package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreBrowserDriver.BrowserDriverException;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreSandboxRuntime.Sandbox;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreSandboxRuntime.SandboxSpec;
import io.github.hectorvent.floci.services.bedrockagentcore.model.ToolSession;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreToolsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AgentCore browser sessions: {@code StartBrowserSession}, {@code GetBrowserSession},
 * {@code ListBrowserSessions}, {@code StopBrowserSession}, {@code InvokeBrowser},
 * {@code UpdateBrowserStream} and {@code SaveBrowserSessionProfile}.
 *
 * <p>Each session is a real headless Chrome container. The automation stream endpoint returned
 * for a session is that browser's own DevTools WebSocket, which Playwright or Puppeteer can connect
 * to directly from wherever Floci can reach the container; there is no SigV4-signed stream proxy
 * and no live-view stream. The automation stream status is recorded as the caller sets it but is
 * not enforced on that direct endpoint. {@code SaveBrowserSessionProfile} captures the browser's
 * cookies into the profile and a session started with that profile gets them back; local
 * storage is not captured.
 */
@ApplicationScoped
public class BedrockAgentCoreBrowserService {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreBrowserService.class);
    private static final String KIND = "browser";
    private static final int DEFAULT_TIMEOUT_SECONDS = 3600;
    private static final int DEVTOOLS_PORT = 9222;
    private static final int DEFAULT_WIDTH = 1456;
    private static final int DEFAULT_HEIGHT = 819;
    private static final List<String> ACTIONS = List.of(
            "mouseClick", "mouseMove", "mouseDrag", "mouseScroll", "keyType", "keyPress", "keyShortcut", "screenshot");

    private final ToolSessionRegistry sessions;
    private final StorageBackend<String, ArrayNode> profileCookies;
    private final BedrockAgentCoreToolsService toolsService;
    private final BedrockAgentCoreSandboxRuntime runtime;
    private final BedrockAgentCoreBrowserDriver driver;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreBrowserService(StorageFactory storageFactory,
                                          BedrockAgentCoreToolsService toolsService,
                                          BedrockAgentCoreSandboxRuntime runtime,
                                          BedrockAgentCoreBrowserDriver driver,
                                          RegionResolver regionResolver,
                                          EmulatorConfig config,
                                          ObjectMapper objectMapper) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-browser-sessions.json",
                        new TypeReference<Map<String, ToolSession>>() {}),
                storageFactory.create("bedrockagentcore", "bedrock-agentcore-browser-profile-cookies.json",
                        new TypeReference<Map<String, ArrayNode>>() {}),
                toolsService, runtime, driver, regionResolver, config, objectMapper);
    }

    BedrockAgentCoreBrowserService(StorageBackend<String, ToolSession> store,
                                   StorageBackend<String, ArrayNode> profileCookies,
                                   BedrockAgentCoreToolsService toolsService,
                                   BedrockAgentCoreSandboxRuntime runtime,
                                   BedrockAgentCoreBrowserDriver driver,
                                   RegionResolver regionResolver,
                                   EmulatorConfig config,
                                   ObjectMapper objectMapper) {
        this.sessions = new ToolSessionRegistry("browser", store, runtime);
        this.profileCookies = profileCookies;
        this.toolsService = toolsService;
        this.runtime = runtime;
        this.driver = driver;
        this.regionResolver = regionResolver;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    public ToolSession start(String identifier, JsonNode request, String region) {
        toolsService.getBrowser(identifier, region);
        String clientToken = text(request, "clientToken");
        sessions.sweep();
        Optional<ToolSession> replay = sessions.byClientToken(region, identifier, clientToken);
        if (replay.isPresent()) {
            return replay.get();
        }
        int timeout = ToolSessionRegistry.timeoutSeconds(integer(request, "sessionTimeoutSeconds"),
                DEFAULT_TIMEOUT_SECONDS);
        int width = DEFAULT_WIDTH;
        int height = DEFAULT_HEIGHT;
        JsonNode viewPort = request.get("viewPort");
        if (viewPort != null && !viewPort.isNull()) {
            width = viewPort.path("width").asInt(-1);
            height = viewPort.path("height").asInt(-1);
            if (width < 320 || width > 3840 || height < 240 || height > 2160) {
                throw new AwsException("ValidationException",
                        "viewPort width must be 320-3840 and height 240-2160", 400);
            }
        }
        String profileId = request.path("profileConfiguration").path("profileIdentifier").asText(null);
        if (profileId != null) {
            toolsService.getBrowserProfile(profileId, region);
        }
        EmulatorConfig.BedrockAgentCoreServiceConfig serviceConfig = config.services().bedrockAgentCore();
        if (sessions.activeCount() >= serviceConfig.browserMaxActiveSessions()) {
            throw new AwsException("ServiceQuotaExceededException",
                    "The number of active browser sessions has reached the limit of "
                            + serviceConfig.browserMaxActiveSessions(), 402);
        }

        ToolSession session = sessions.newSession(region, identifier, text(request, "name"), timeout, clientToken);
        session.setViewPortWidth(width);
        session.setViewPortHeight(height);
        session.setProfileIdentifier(profileId);
        session.setAutomationStreamStatus("ENABLED");
        Sandbox sandbox = runtime.launch(new SandboxSpec(KIND, session.getSessionId(), region,
                regionResolver.getAccountId(), serviceConfig.browserImage(), serviceConfig.browserMemoryMb(),
                List.of("--disable-dev-shm-usage", "--window-size=" + width + "," + height),
                null, false, DEVTOOLS_PORT));
        session.setContainerId(sandbox.containerId());
        session.setServiceHost(sandbox.serviceHost());
        session.setServicePort(sandbox.servicePort());
        try {
            session.setStreamEndpoint(driver.awaitBrowserEndpoint(sandbox.serviceHost(), sandbox.servicePort(),
                    Duration.ofSeconds(serviceConfig.browserStartupTimeoutSeconds())));
            if (profileId != null) {
                driver.setCookies(session.getStreamEndpoint(), profileCookies.get(cookieKey(region, profileId))
                        .orElse(null));
            }
        } catch (BrowserDriverException e) {
            runtime.remove(sandbox.containerId());
            throw new AwsException("InternalServerException",
                    "The browser session could not be started: " + e.getMessage(), 500);
        }
        sessions.save(session);
        LOG.infov("Started browser session {0} on {1} in container {2}",
                session.getSessionId(), identifier, sandbox.containerId());
        return session;
    }

    public ToolSession get(String identifier, String sessionId, String region) {
        toolsService.getBrowser(identifier, region);
        return sessions.require(region, identifier, sessionId);
    }

    public PaginatedResult<ToolSession> list(String identifier, JsonNode request, String region) {
        toolsService.getBrowser(identifier, region);
        sessions.sweep();
        return sessions.list(region, identifier, text(request, "status"), integer(request, "maxResults"),
                text(request, "nextToken"));
    }

    public ToolSession stop(String identifier, String sessionId, String region) {
        toolsService.getBrowser(identifier, region);
        ToolSession session = sessions.stop(region, identifier, sessionId);
        LOG.infov("Stopped browser session {0} on {1}", sessionId, identifier);
        return session;
    }

    /** Performs exactly one BrowserAction and returns the matching BrowserActionResult. */
    public ObjectNode invoke(String identifier, String sessionId, JsonNode request, String region) {
        toolsService.getBrowser(identifier, region);
        JsonNode action = request.get("action");
        if (action == null || !action.isObject() || action.size() != 1) {
            throw new AwsException("ValidationException", "action must set exactly one browser action", 400);
        }
        Iterator<Map.Entry<String, JsonNode>> fields = action.fields();
        Map.Entry<String, JsonNode> selected = fields.next();
        String name = selected.getKey();
        if (!ACTIONS.contains(name)) {
            throw new AwsException("ValidationException", "Unknown browser action: " + name, 400);
        }
        ToolSession session = sessions.requireReady(region, identifier, sessionId);
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode outcome = result.putObject(name);
        try {
            if ("screenshot".equals(name)) {
                String format = selected.getValue().path("format").asText("PNG");
                if (!"PNG".equals(format)) {
                    throw new AwsException("ValidationException", "screenshot format must be PNG", 400);
                }
                outcome.put("data", driver.screenshot(session.getServiceHost(), session.getServicePort()));
            } else {
                driver.perform(session.getServiceHost(), session.getServicePort(), name, selected.getValue());
            }
            outcome.put("status", "SUCCESS");
        } catch (BrowserDriverException e) {
            outcome.removeAll();
            outcome.put("status", "FAILED");
            outcome.put("error", e.getMessage());
        }
        return result;
    }

    public ToolSession updateStream(String identifier, String sessionId, JsonNode request, String region) {
        toolsService.getBrowser(identifier, region);
        String status = request.path("streamUpdate").path("automationStreamUpdate").path("streamStatus").asText(null);
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new AwsException("ValidationException",
                    "streamUpdate.automationStreamUpdate.streamStatus must be ENABLED or DISABLED", 400);
        }
        ToolSession session = sessions.requireReady(region, identifier, sessionId);
        session.setAutomationStreamStatus(status);
        session.setLastUpdatedAtMillis(System.currentTimeMillis());
        sessions.save(session);
        return session;
    }

    /** Captures the session's cookies into the profile; returns when the profile was saved. */
    public Instant saveProfile(String profileId, JsonNode request, String region) {
        toolsService.getBrowserProfile(profileId, region);
        String identifier = text(request, "browserIdentifier");
        String sessionId = text(request, "sessionId");
        if (identifier == null || sessionId == null) {
            throw new AwsException("ValidationException", "browserIdentifier and sessionId are required", 400);
        }
        toolsService.getBrowser(identifier, region);
        ToolSession session = sessions.requireReady(region, identifier, sessionId);
        ArrayNode cookies;
        try {
            cookies = driver.cookies(session.getStreamEndpoint());
        } catch (BrowserDriverException e) {
            throw new AwsException("InternalServerException",
                    "Could not read the browser session's state: " + e.getMessage(), 500);
        }
        profileCookies.put(cookieKey(region, profileId), cookies);
        Instant savedAt = Instant.now();
        toolsService.recordBrowserProfileSave(profileId, identifier, sessionId, savedAt, region);
        return savedAt;
    }

    // ── wire shapes ──────────────────────────────────────────────

    public ObjectNode streams(ToolSession session) {
        ObjectNode streams = objectMapper.createObjectNode();
        ObjectNode automation = streams.putObject("automationStream");
        automation.put("streamEndpoint", session.getStreamEndpoint() == null ? "" : session.getStreamEndpoint());
        automation.put("streamStatus", session.getAutomationStreamStatus() == null
                ? "ENABLED" : session.getAutomationStreamStatus());
        return streams;
    }

    public ObjectNode describe(ToolSession session) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("browserIdentifier", session.getIdentifier());
        node.put("sessionId", session.getSessionId());
        if (session.getName() != null) {
            node.put("name", session.getName());
        }
        node.put("createdAt", Instant.ofEpochMilli(session.getCreatedAtMillis()).toString());
        if (session.getViewPortWidth() != null && session.getViewPortHeight() != null) {
            node.putObject("viewPort")
                    .put("width", session.getViewPortWidth())
                    .put("height", session.getViewPortHeight());
        }
        if (session.getProfileIdentifier() != null) {
            node.putObject("profileConfiguration").put("profileIdentifier", session.getProfileIdentifier());
        }
        node.put("sessionTimeoutSeconds", session.getSessionTimeoutSeconds());
        node.put("status", session.getStatus());
        if (ToolSessionRegistry.READY.equals(session.getStatus())) {
            node.set("streams", streams(session));
        }
        node.put("lastUpdatedAt", Instant.ofEpochMilli(session.getLastUpdatedAtMillis()).toString());
        return node;
    }

    public ObjectNode summary(ToolSession session) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("browserIdentifier", session.getIdentifier());
        node.put("sessionId", session.getSessionId());
        if (session.getName() != null) {
            node.put("name", session.getName());
        }
        node.put("status", session.getStatus());
        node.put("createdAt", Instant.ofEpochMilli(session.getCreatedAtMillis()).toString());
        node.put("lastUpdatedAt", Instant.ofEpochMilli(session.getLastUpdatedAtMillis()).toString());
        return node;
    }

    // ── helpers ──────────────────────────────────────────────────

    private static String cookieKey(String region, String profileId) {
        return region + "::" + profileId;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt()) {
            throw new AwsException("ValidationException", field + " must be an integer", 400);
        }
        return value.asInt();
    }
}
