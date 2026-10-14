package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreSandboxRuntime.ExecResult;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreSandboxRuntime.Sandbox;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreSandboxRuntime.SandboxSpec;
import io.github.hectorvent.floci.services.bedrockagentcore.model.ToolSession;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreToolsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BedrockAgentCoreCodeInterpreterServiceTest {

    private static final String REGION = "us-east-1";
    private static final String INTERPRETER = "TestInterpreter-abcdefghij";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BedrockAgentCoreSandboxRuntime runtime;
    private BedrockAgentCoreToolsService tools;
    private EmulatorConfig config;
    private BedrockAgentCoreCodeInterpreterService service;

    @BeforeEach
    void setUp() {
        runtime = mock(BedrockAgentCoreSandboxRuntime.class);
        tools = mock(BedrockAgentCoreToolsService.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().bedrockAgentCore().codeInterpreterImage()).thenReturn("python:3.12-slim");
        when(config.services().bedrockAgentCore().codeInterpreterMemoryMb()).thenReturn(512);
        when(config.services().bedrockAgentCore().codeInterpreterMaxActiveSessions()).thenReturn(2);
        when(config.services().bedrockAgentCore().toolExecutionTimeoutSeconds()).thenReturn(300);
        when(tools.getCodeInterpreter(INTERPRETER, REGION)).thenReturn(interpreter("SANDBOX"));
        when(runtime.launch(any())).thenAnswer(call -> new Sandbox("container-" + System.nanoTime(), null, null));
        when(runtime.isLive(anyString())).thenReturn(true);
        when(runtime.isTracked(anyString())).thenReturn(true);
        service = new BedrockAgentCoreCodeInterpreterService(new InMemoryStorage<>(), tools, runtime,
                new RegionResolver(REGION, "000000000000"), config, MAPPER);
    }

    private static ObjectNode interpreter(String networkMode) {
        ObjectNode node = MAPPER.createObjectNode();
        node.putObject("networkConfiguration").put("networkMode", networkMode);
        return node;
    }

    private static JsonNode json(String body) throws Exception {
        return MAPPER.readTree(body);
    }

    private ToolSession start() throws Exception {
        return service.start(INTERPRETER, json("{\"sessionTimeoutSeconds\":300}"), REGION);
    }

    @Test
    void startLaunchesAnIsolatedContainerForASandboxInterpreter() throws Exception {
        ToolSession session = start();
        assertEquals("READY", session.getStatus());
        assertTrue(session.getSessionId().matches("[0-9A-Z]{32}"));
        assertEquals(300, session.getSessionTimeoutSeconds());

        ArgumentCaptor<SandboxSpec> spec = ArgumentCaptor.forClass(SandboxSpec.class);
        verify(runtime).launch(spec.capture());
        assertTrue(spec.getValue().isolated(), "SANDBOX network mode means no network");
        assertEquals("python:3.12-slim", spec.getValue().image());
        assertEquals(512, spec.getValue().memoryMb());
        assertEquals(List.of("sleep", "infinity"), spec.getValue().cmd());
    }

    @Test
    void publicInterpreterGetsANetwork() throws Exception {
        when(tools.getCodeInterpreter(INTERPRETER, REGION)).thenReturn(interpreter("PUBLIC"));
        start();
        ArgumentCaptor<SandboxSpec> spec = ArgumentCaptor.forClass(SandboxSpec.class);
        verify(runtime).launch(spec.capture());
        assertFalse(spec.getValue().isolated());
    }

    @Test
    void executeCodeRunsPythonInTheSessionContainer() throws Exception {
        ToolSession session = start();
        when(runtime.exec(eq(session.getContainerId()), eq(List.of("python3", "-c", "print(21 * 2)")),
                anyString(), anyInt())).thenReturn(new ExecResult("42\n", "", 0, 120, false));

        ObjectNode result = service.invoke(INTERPRETER, session.getSessionId(),
                json("{\"name\":\"executeCode\",\"arguments\":{\"language\":\"python\",\"code\":\"print(21 * 2)\"}}"),
                REGION);
        assertEquals("text", result.get("content").get(0).get("type").asText());
        assertEquals("42\n", result.get("content").get(0).get("text").asText());
        assertEquals("42\n", result.get("structuredContent").get("stdout").asText());
        assertEquals(0, result.get("structuredContent").get("exitCode").asInt());
        assertFalse(result.get("isError").asBoolean());
    }

    @Test
    void failingCodeIsReportedAsAnErrorResult() throws Exception {
        ToolSession session = start();
        when(runtime.exec(anyString(), any(), anyString(), anyInt()))
                .thenReturn(new ExecResult("", "ZeroDivisionError: division by zero\n", 1, 50, false));
        ObjectNode result = service.invoke(INTERPRETER, session.getSessionId(),
                json("{\"name\":\"executeCode\",\"arguments\":{\"code\":\"1/0\"}}"), REGION);
        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("structuredContent").get("stderr").asText().contains("ZeroDivisionError"));
    }

    @Test
    void getAndListReportTheSession() throws Exception {
        ToolSession session = start();
        assertEquals("READY", service.get(INTERPRETER, session.getSessionId(), REGION).getStatus());
        List<ToolSession> listed = service.list(INTERPRETER, json("{}"), REGION).items();
        assertEquals(1, listed.size());
        assertEquals(session.getSessionId(), listed.get(0).getSessionId());
        ObjectNode described = service.describe(session);
        assertEquals(INTERPRETER, described.get("codeInterpreterIdentifier").asText());
        assertTrue(described.get("createdAt").asText().endsWith("Z"));
    }

    @Test
    void stoppingRemovesTheContainerAndStoppingAgainConflicts() throws Exception {
        ToolSession session = start();
        ToolSession stopped = service.stop(INTERPRETER, session.getSessionId(), REGION);
        assertEquals("TERMINATED", stopped.getStatus());
        verify(runtime).remove(session.getContainerId());

        AwsException again = assertThrows(AwsException.class,
                () -> service.stop(INTERPRETER, session.getSessionId(), REGION));
        assertEquals("ConflictException", again.getErrorCode());
        assertEquals(409, again.getHttpStatus());

        AwsException invoke = assertThrows(AwsException.class, () -> service.invoke(INTERPRETER,
                session.getSessionId(), json("{\"name\":\"executeCode\",\"arguments\":{\"code\":\"1\"}}"), REGION));
        assertEquals("ConflictException", invoke.getErrorCode());
        assertEquals("TERMINATED", service.get(INTERPRETER, session.getSessionId(), REGION).getStatus());
    }

    @Test
    void aSessionWhoseContainerDiedIsTerminated() throws Exception {
        ToolSession session = start();
        when(runtime.isLive(session.getContainerId())).thenReturn(false);
        assertEquals("TERMINATED", service.get(INTERPRETER, session.getSessionId(), REGION).getStatus());
    }

    @Test
    void unknownSessionIsNotFound() {
        AwsException e = assertThrows(AwsException.class, () -> service.get(INTERPRETER, "NOSUCHSESSION", REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void activeSessionsAreCapped() throws Exception {
        start();
        start();
        AwsException e = assertThrows(AwsException.class, this::start);
        assertEquals("ServiceQuotaExceededException", e.getErrorCode());
        assertEquals(402, e.getHttpStatus());
    }

    @Test
    void clientTokenReplaysTheSameSession() throws Exception {
        String body = "{\"clientToken\":\"" + "c".repeat(40) + "\"}";
        ToolSession first = service.start(INTERPRETER, json(body), REGION);
        ToolSession second = service.start(INTERPRETER, json(body), REGION);
        assertEquals(first.getSessionId(), second.getSessionId());
        verify(runtime).launch(any());
    }

    @Test
    void invokeRequiresASessionAndAKnownTool() throws Exception {
        ToolSession session = start();
        assertThrows(AwsException.class, () -> service.invoke(INTERPRETER, null,
                json("{\"name\":\"executeCode\",\"arguments\":{\"code\":\"1\"}}"), REGION));
        AwsException unknown = assertThrows(AwsException.class, () -> service.invoke(INTERPRETER,
                session.getSessionId(), json("{\"name\":\"doSomething\"}"), REGION));
        assertEquals("ValidationException", unknown.getErrorCode());
        verify(runtime, never()).exec(anyString(), any(), anyString(), anyInt());
    }

    @Test
    void writeFilesResolvesRelativePathsAgainstTheWorkingDirectory() throws Exception {
        ToolSession session = start();
        ObjectNode result = service.invoke(INTERPRETER, session.getSessionId(), json("{\"name\":\"writeFiles\","
                + "\"arguments\":{\"content\":[{\"path\":\"data.txt\",\"text\":\"hello\"}]}}"), REGION);
        assertFalse(result.get("isError").asBoolean());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, byte[]>> files = ArgumentCaptor.forClass(Map.class);
        verify(runtime).writeFiles(eq(session.getContainerId()), files.capture());
        byte[] written = files.getValue().get(BedrockAgentCoreCodeInterpreterService.WORKING_DIR + "/data.txt");
        assertEquals("hello", new String(written, StandardCharsets.UTF_8));
    }

    @Test
    void timeoutOutsideTheModeledRangeIsRejected() {
        AwsException e = assertThrows(AwsException.class, () -> service.start(INTERPRETER,
                json("{\"sessionTimeoutSeconds\":28801}"), REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }
}
