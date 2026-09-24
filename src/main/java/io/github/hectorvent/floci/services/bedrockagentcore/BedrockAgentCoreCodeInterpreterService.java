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
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreSandboxRuntime.ExecResult;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreSandboxRuntime.Sandbox;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreSandboxRuntime.SandboxSpec;
import io.github.hectorvent.floci.services.bedrockagentcore.model.ToolSession;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreToolsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AgentCore code interpreter sessions: {@code StartCodeInterpreterSession},
 * {@code GetCodeInterpreterSession}, {@code ListCodeInterpreterSessions},
 * {@code StopCodeInterpreterSession} and {@code InvokeCodeInterpreter}.
 *
 * <p>Each session is a real container (by default {@code python:3.12-slim}) and every tool call
 * runs in it: {@code executeCode} and {@code executeCommand} exec the program, and the file
 * tools read, write, list and remove files in the container's filesystem. A code interpreter
 * whose network mode is {@code SANDBOX}, as the built-in {@code aws.codeinterpreter.v1} is, runs
 * with no network at all.
 *
 * <p>One difference from AgentCore is deliberate and visible: each {@code executeCode} call runs
 * in a fresh interpreter process, so variables do not carry over between calls (files written to
 * the session's filesystem do). {@code clearContext} is accepted and has nothing to clear. The
 * asynchronous task tools ({@code startCommandExecution}, {@code getTask}, {@code stopTask}) are
 * not emulated and are rejected with a {@code ValidationException} naming the tool.
 */
@ApplicationScoped
public class BedrockAgentCoreCodeInterpreterService {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreCodeInterpreterService.class);
    private static final String KIND = "code-interpreter";
    private static final int DEFAULT_TIMEOUT_SECONDS = 900;
    static final String WORKING_DIR = "/workspace";
    private static final int INLINE_CODE_LIMIT = 64 * 1024;

    private final ToolSessionRegistry sessions;
    private final BedrockAgentCoreToolsService toolsService;
    private final BedrockAgentCoreSandboxRuntime runtime;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreCodeInterpreterService(StorageFactory storageFactory,
                                                  BedrockAgentCoreToolsService toolsService,
                                                  BedrockAgentCoreSandboxRuntime runtime,
                                                  RegionResolver regionResolver,
                                                  EmulatorConfig config,
                                                  ObjectMapper objectMapper) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-code-interpreter-sessions.json",
                        new TypeReference<Map<String, ToolSession>>() {}),
                toolsService, runtime, regionResolver, config, objectMapper);
    }

    BedrockAgentCoreCodeInterpreterService(StorageBackend<String, ToolSession> store,
                                           BedrockAgentCoreToolsService toolsService,
                                           BedrockAgentCoreSandboxRuntime runtime,
                                           RegionResolver regionResolver,
                                           EmulatorConfig config,
                                           ObjectMapper objectMapper) {
        this.sessions = new ToolSessionRegistry("code interpreter", store, runtime);
        this.toolsService = toolsService;
        this.runtime = runtime;
        this.regionResolver = regionResolver;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    public ToolSession start(String identifier, JsonNode request, String region) {
        ObjectNode interpreter = toolsService.getCodeInterpreter(identifier, region);
        String clientToken = text(request, "clientToken");
        sessions.sweep();
        Optional<ToolSession> replay = sessions.byClientToken(region, identifier, clientToken);
        if (replay.isPresent()) {
            return replay.get();
        }
        int timeout = ToolSessionRegistry.timeoutSeconds(integer(request, "sessionTimeoutSeconds"),
                DEFAULT_TIMEOUT_SECONDS);
        EmulatorConfig.BedrockAgentCoreServiceConfig serviceConfig = config.services().bedrockAgentCore();
        if (sessions.activeCount() >= serviceConfig.codeInterpreterMaxActiveSessions()) {
            throw new AwsException("ServiceQuotaExceededException",
                    "The number of active code interpreter sessions has reached the limit of "
                            + serviceConfig.codeInterpreterMaxActiveSessions(), 402);
        }
        ToolSession session = sessions.newSession(region, identifier, text(request, "name"), timeout, clientToken);
        boolean isolated = "SANDBOX".equals(interpreter.path("networkConfiguration").path("networkMode").asText());
        Sandbox sandbox = runtime.launch(new SandboxSpec(KIND, session.getSessionId(), region,
                regionResolver.getAccountId(), serviceConfig.codeInterpreterImage(),
                serviceConfig.codeInterpreterMemoryMb(), List.of("sleep", "infinity"), WORKING_DIR, isolated, null));
        session.setContainerId(sandbox.containerId());
        sessions.save(session);
        LOG.infov("Started code interpreter session {0} on {1} in container {2}",
                session.getSessionId(), identifier, sandbox.containerId());
        return session;
    }

    public ToolSession get(String identifier, String sessionId, String region) {
        toolsService.getCodeInterpreter(identifier, region);
        return sessions.require(region, identifier, sessionId);
    }

    public PaginatedResult<ToolSession> list(String identifier, JsonNode request, String region) {
        toolsService.getCodeInterpreter(identifier, region);
        sessions.sweep();
        return sessions.list(region, identifier, text(request, "status"), integer(request, "maxResults"),
                text(request, "nextToken"));
    }

    public ToolSession stop(String identifier, String sessionId, String region) {
        toolsService.getCodeInterpreter(identifier, region);
        ToolSession session = sessions.stop(region, identifier, sessionId);
        LOG.infov("Stopped code interpreter session {0} on {1}", sessionId, identifier);
        return session;
    }

    /** Runs one tool call and returns the {@code CodeInterpreterResult} it produced. */
    public ObjectNode invoke(String identifier, String sessionId, JsonNode request, String region) {
        toolsService.getCodeInterpreter(identifier, region);
        if (sessionId == null || sessionId.isBlank()) {
            throw new AwsException("ValidationException",
                    "An active session is required: pass the x-amzn-code-interpreter-session-id header", 400);
        }
        String tool = text(request, "name");
        if (tool == null) {
            throw new AwsException("ValidationException", "name is required", 400);
        }
        JsonNode arguments = request.path("arguments");
        ToolSession session = sessions.requireReady(region, identifier, sessionId);
        String containerId = session.getContainerId();
        return switch (tool) {
            case "executeCode" -> executeCode(containerId, arguments, session);
            case "executeCommand" -> executeCommand(containerId, arguments, session);
            case "writeFiles" -> writeFiles(containerId, arguments);
            case "readFiles" -> readFiles(containerId, arguments);
            case "listFiles" -> listFiles(containerId, arguments, session);
            case "removeFiles" -> removeFiles(containerId, arguments, session);
            case "startCommandExecution", "getTask", "stopTask" -> throw new AwsException("ValidationException",
                    "The " + tool + " tool is not emulated by Floci", 400);
            default -> throw new AwsException("ValidationException", "Unknown tool name: " + tool, 400);
        };
    }

    // ── tools ────────────────────────────────────────────────────

    private ObjectNode executeCode(String containerId, JsonNode arguments, ToolSession session) {
        String code = text(arguments, "code");
        if (code == null) {
            throw new AwsException("ValidationException", "arguments.code is required for executeCode", 400);
        }
        String language = Optional.ofNullable(text(arguments, "language")).orElse("python");
        String interpreter = switch (language) {
            case "python" -> "python3";
            case "javascript" -> "node";
            case "typescript" -> "tsx";
            default -> throw new AwsException("ValidationException",
                    "language must be one of python, javascript, typescript", 400);
        };
        List<String> command;
        if (code.length() <= INLINE_CODE_LIMIT) {
            command = List.of(interpreter, "python3".equals(interpreter) ? "-c" : "-e", code);
        } else {
            // A single argv string is capped by the kernel (MAX_ARG_STRLEN), so large programs go via a file.
            String extension = switch (language) {
                case "javascript" -> ".js";
                case "typescript" -> ".ts";
                default -> ".py";
            };
            String script = "/tmp/floci-exec-" + UUID.randomUUID() + extension;
            runtime.writeFiles(containerId, Map.of(script, code.getBytes(StandardCharsets.UTF_8)));
            command = List.of(interpreter, script);
        }
        return execution(runtime.exec(containerId, command, WORKING_DIR, executionTimeout(session)));
    }

    private ObjectNode executeCommand(String containerId, JsonNode arguments, ToolSession session) {
        String command = text(arguments, "command");
        if (command == null) {
            throw new AwsException("ValidationException", "arguments.command is required for executeCommand", 400);
        }
        return execution(runtime.exec(containerId, List.of("sh", "-c", command), WORKING_DIR,
                executionTimeout(session)));
    }

    private ObjectNode writeFiles(String containerId, JsonNode arguments) {
        JsonNode content = arguments.path("content");
        if (!content.isArray() || content.isEmpty()) {
            throw new AwsException("ValidationException", "arguments.content is required for writeFiles", 400);
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (JsonNode block : content) {
            String path = text(block, "path");
            if (path == null || path.isBlank()) {
                throw new AwsException("ValidationException", "Each content block needs a path", 400);
            }
            byte[] bytes;
            if (block.hasNonNull("blob")) {
                bytes = Base64.getDecoder().decode(block.get("blob").asText());
            } else {
                bytes = Optional.ofNullable(text(block, "text")).orElse("").getBytes(StandardCharsets.UTF_8);
            }
            files.put(absolute(path), bytes);
        }
        runtime.writeFiles(containerId, files);
        return textResult("Successfully wrote all " + files.size() + " files", false);
    }

    private ObjectNode readFiles(String containerId, JsonNode arguments) {
        List<String> paths = paths(arguments);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        boolean missing = false;
        for (String path : paths) {
            String absolute = absolute(path);
            byte[] bytes = runtime.readFile(containerId, absolute);
            if (bytes == null) {
                missing = true;
                content.addObject().put("type", "text").put("text", "File not found: " + path);
                continue;
            }
            ObjectNode resource = content.addObject().put("type", "resource").putObject("resource");
            resource.put("uri", "file://" + absolute);
            String text = utf8(bytes);
            if (text != null) {
                resource.put("type", "text");
                resource.put("mimeType", "text/plain");
                resource.put("text", text);
            } else {
                resource.put("type", "blob");
                resource.put("mimeType", "application/octet-stream");
                resource.put("blob", Base64.getEncoder().encodeToString(bytes));
            }
        }
        result.put("isError", missing);
        return result;
    }

    private ObjectNode listFiles(String containerId, JsonNode arguments, ToolSession session) {
        String directory = absolute(Optional.ofNullable(text(arguments, "directoryPath")).orElse(WORKING_DIR));
        ExecResult listing = runtime.exec(containerId,
                List.of("find", directory, "-mindepth", "1", "-maxdepth", "1", "-printf", "%y\\t%s\\t%p\\n"),
                WORKING_DIR, executionTimeout(session));
        if (listing.exitCode() != 0) {
            return textResult(listing.stderr().isBlank() ? "Could not list " + directory : listing.stderr(), true);
        }
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        for (String line : listing.stdout().split("\n")) {
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) {
                continue;
            }
            String path = parts[2];
            ObjectNode link = content.addObject();
            link.put("type", "resource_link");
            link.put("uri", "file://" + path);
            link.put("name", path.substring(path.lastIndexOf('/') + 1));
            link.put("description", "d".equals(parts[0]) ? "Directory" : "File");
            if (!"d".equals(parts[0])) {
                link.put("size", Long.parseLong(parts[1]));
            }
        }
        result.put("isError", false);
        return result;
    }

    private ObjectNode removeFiles(String containerId, JsonNode arguments, ToolSession session) {
        List<String> command = new ArrayList<>(List.of("rm", "-rf", "--"));
        List<String> paths = paths(arguments);
        paths.forEach(path -> command.add(absolute(path)));
        ExecResult removal = runtime.exec(containerId, command, WORKING_DIR, executionTimeout(session));
        if (removal.exitCode() != 0) {
            return textResult(removal.stderr(), true);
        }
        return textResult("Successfully removed " + paths.size() + " files", false);
    }

    // ── results ──────────────────────────────────────────────────

    private ObjectNode execution(ExecResult exec) {
        ObjectNode result = objectMapper.createObjectNode();
        String stderr = exec.timedOut() && exec.stderr().isBlank()
                ? "Execution timed out" : exec.stderr();
        result.putArray("content").addObject().put("type", "text").put("text", exec.stdout() + stderr);
        ObjectNode structured = result.putObject("structuredContent");
        structured.put("stdout", exec.stdout());
        structured.put("stderr", stderr);
        structured.put("exitCode", exec.exitCode());
        structured.put("executionTime", exec.durationMillis() / 1000d);
        result.put("isError", exec.exitCode() != 0);
        return result;
    }

    private ObjectNode textResult(String text, boolean isError) {
        ObjectNode result = objectMapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        result.put("isError", isError);
        return result;
    }

    // ── wire shapes ──────────────────────────────────────────────

    public ObjectNode describe(ToolSession session) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("codeInterpreterIdentifier", session.getIdentifier());
        node.put("sessionId", session.getSessionId());
        if (session.getName() != null) {
            node.put("name", session.getName());
        }
        node.put("createdAt", Instant.ofEpochMilli(session.getCreatedAtMillis()).toString());
        node.put("sessionTimeoutSeconds", session.getSessionTimeoutSeconds());
        node.put("status", session.getStatus());
        return node;
    }

    public ObjectNode summary(ToolSession session) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("codeInterpreterIdentifier", session.getIdentifier());
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

    private int executionTimeout(ToolSession session) {
        long remaining = (session.getCreatedAtMillis() + session.getSessionTimeoutSeconds() * 1000L
                - System.currentTimeMillis()) / 1000L;
        int ceiling = config.services().bedrockAgentCore().toolExecutionTimeoutSeconds();
        return (int) Math.max(1L, Math.min(ceiling, remaining));
    }

    private static List<String> paths(JsonNode arguments) {
        JsonNode node = arguments.path("paths");
        if (!node.isArray() || node.isEmpty()) {
            throw new AwsException("ValidationException", "arguments.paths is required", 400);
        }
        List<String> paths = new ArrayList<>();
        node.forEach(path -> paths.add(path.asText()));
        return paths;
    }

    private static String absolute(String path) {
        return path.startsWith("/") ? path : WORKING_DIR + "/" + path;
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException expected) {
            // Not UTF-8 text: the caller returns the bytes as a blob instead.
            return null;
        }
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
