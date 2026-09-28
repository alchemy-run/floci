package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreBrowserDriver.BrowserDriverException;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreSandboxRuntime.Sandbox;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreSandboxRuntime.SandboxSpec;
import io.github.hectorvent.floci.services.bedrockagentcore.model.ToolSession;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreToolsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BedrockAgentCoreBrowserServiceTest {

    private static final String REGION = "us-east-1";
    private static final String BROWSER = "TestBrowser-abcdefghij";
    private static final String PROFILE = "TestProfile-abcdefghij";
    private static final String ENDPOINT = "ws://localhost:40001/devtools/browser/abc";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BedrockAgentCoreSandboxRuntime runtime;
    private BedrockAgentCoreBrowserDriver driver;
    private BedrockAgentCoreToolsService tools;
    private BedrockAgentCoreBrowserService service;

    @BeforeEach
    void setUp() {
        runtime = mock(BedrockAgentCoreSandboxRuntime.class);
        driver = mock(BedrockAgentCoreBrowserDriver.class);
        tools = mock(BedrockAgentCoreToolsService.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().bedrockAgentCore().browserImage()).thenReturn("chromedp/headless-shell:test");
        when(config.services().bedrockAgentCore().browserMemoryMb()).thenReturn(1024);
        when(config.services().bedrockAgentCore().browserMaxActiveSessions()).thenReturn(3);
        when(config.services().bedrockAgentCore().browserStartupTimeoutSeconds()).thenReturn(30);
        when(runtime.launch(any())).thenAnswer(call -> new Sandbox("browser-" + System.nanoTime(), "localhost", 40001));
        when(runtime.isLive(anyString())).thenReturn(true);
        when(runtime.isTracked(anyString())).thenReturn(true);
        when(driver.awaitBrowserEndpoint(eq("localhost"), eq(40001), any(Duration.class))).thenReturn(ENDPOINT);
        service = new BedrockAgentCoreBrowserService(new InMemoryStorage<>(), new InMemoryStorage<>(), tools,
                runtime, driver, new RegionResolver(REGION, "000000000000"), config, MAPPER);
    }

    private static JsonNode json(String body) throws Exception {
        return MAPPER.readTree(body);
    }

    private ToolSession start() throws Exception {
        return service.start(BROWSER, json("{\"sessionTimeoutSeconds\":300}"), REGION);
    }

    @Test
    void startLaunchesHeadlessChromeAndExposesItsDevToolsEndpoint() throws Exception {
        ToolSession session = start();
        assertEquals("READY", session.getStatus());
        assertEquals(ENDPOINT, session.getStreamEndpoint());

        ArgumentCaptor<SandboxSpec> spec = ArgumentCaptor.forClass(SandboxSpec.class);
        verify(runtime).launch(spec.capture());
        assertEquals(9222, spec.getValue().servicePort());
        assertFalse(spec.getValue().isolated());
        assertTrue(spec.getValue().cmd().contains("--window-size=1456,819"));

        ObjectNode streams = service.streams(session);
        assertEquals(ENDPOINT, streams.get("automationStream").get("streamEndpoint").asText());
        assertEquals("ENABLED", streams.get("automationStream").get("streamStatus").asText());
    }

    @Test
    void aBrowserThatNeverAnswersIsRemovedAndReported() {
        when(driver.awaitBrowserEndpoint(anyString(), anyInt(), any(Duration.class)))
                .thenThrow(new BrowserDriverException("no response"));
        AwsException e = assertThrows(AwsException.class, this::start);
        assertEquals("InternalServerException", e.getErrorCode());
        verify(runtime).remove(anyString());
    }

    @Test
    void screenshotReturnsThePngData() throws Exception {
        ToolSession session = start();
        when(driver.screenshot("localhost", 40001)).thenReturn("iVBORw0KGgo=");
        ObjectNode result = service.invoke(BROWSER, session.getSessionId(),
                json("{\"action\":{\"screenshot\":{}}}"), REGION);
        assertEquals("SUCCESS", result.get("screenshot").get("status").asText());
        assertEquals("iVBORw0KGgo=", result.get("screenshot").get("data").asText());
    }

    @Test
    void aFailedActionIsReportedInTheResult() throws Exception {
        ToolSession session = start();
        doThrow(new BrowserDriverException("Input.dispatchMouseEvent failed"))
                .when(driver).perform(eq("localhost"), eq(40001), eq("mouseClick"), any());
        ObjectNode result = service.invoke(BROWSER, session.getSessionId(),
                json("{\"action\":{\"mouseClick\":{\"x\":1,\"y\":2}}}"), REGION);
        assertEquals("FAILED", result.get("mouseClick").get("status").asText());
        assertTrue(result.get("mouseClick").get("error").asText().contains("dispatchMouseEvent"));
    }

    @Test
    void actionMustBeExactlyOneKnownAction() throws Exception {
        ToolSession session = start();
        assertThrows(AwsException.class, () -> service.invoke(BROWSER, session.getSessionId(),
                json("{\"action\":{}}"), REGION));
        assertThrows(AwsException.class, () -> service.invoke(BROWSER, session.getSessionId(),
                json("{\"action\":{\"teleport\":{}}}"), REGION));
    }

    @Test
    void stopTerminatesAndSecondStopConflicts() throws Exception {
        ToolSession session = start();
        assertEquals("TERMINATED", service.stop(BROWSER, session.getSessionId(), REGION).getStatus());
        verify(runtime).remove(session.getContainerId());
        AwsException e = assertThrows(AwsException.class, () -> service.stop(BROWSER, session.getSessionId(), REGION));
        assertEquals("ConflictException", e.getErrorCode());
        ObjectNode described = service.describe(service.get(BROWSER, session.getSessionId(), REGION));
        assertEquals("TERMINATED", described.get("status").asText());
        assertFalse(described.has("streams"));
    }

    @Test
    void listFiltersByStatus() throws Exception {
        ToolSession stopped = start();
        service.stop(BROWSER, stopped.getSessionId(), REGION);
        ToolSession ready = start();
        assertEquals(2, service.list(BROWSER, json("{}"), REGION).items().size());
        assertEquals(ready.getSessionId(),
                service.list(BROWSER, json("{\"status\":\"READY\"}"), REGION).items().get(0).getSessionId());
        assertThrows(AwsException.class, () -> service.list(BROWSER, json("{\"status\":\"ACTIVE\"}"), REGION));
    }

    @Test
    void updateStreamRecordsTheStatus() throws Exception {
        ToolSession session = start();
        ToolSession updated = service.updateStream(BROWSER, session.getSessionId(),
                json("{\"streamUpdate\":{\"automationStreamUpdate\":{\"streamStatus\":\"DISABLED\"}}}"), REGION);
        assertEquals("DISABLED", service.streams(updated).get("automationStream").get("streamStatus").asText());
    }

    @Test
    void viewPortIsValidated() {
        assertThrows(AwsException.class, () -> service.start(BROWSER,
                json("{\"viewPort\":{\"width\":100,\"height\":100}}"), REGION));
    }

    @Test
    void savedProfileCookiesAreRestoredIntoANewSession() throws Exception {
        ToolSession session = start();
        ArrayNode cookies = MAPPER.createArrayNode();
        cookies.addObject().put("name", "sid").put("value", "abc").put("domain", "example.com");
        when(driver.cookies(ENDPOINT)).thenReturn(cookies);

        Instant savedAt = service.saveProfile(PROFILE, json("{\"browserIdentifier\":\"" + BROWSER
                + "\",\"sessionId\":\"" + session.getSessionId() + "\"}"), REGION);
        verify(tools).recordBrowserProfileSave(PROFILE, BROWSER, session.getSessionId(), savedAt, REGION);

        service.start(BROWSER, json("{\"profileConfiguration\":{\"profileIdentifier\":\"" + PROFILE + "\"}}"),
                REGION);
        verify(driver).setCookies(ENDPOINT, cookies);
    }
}
