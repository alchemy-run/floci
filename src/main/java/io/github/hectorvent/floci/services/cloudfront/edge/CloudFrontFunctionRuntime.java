package io.github.hectorvent.floci.services.cloudfront.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontService;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontFunction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs CloudFront Functions.
 *
 * <p>CloudFront Functions do not run on Node.js — the runtime is a bare
 * JavaScript engine with no network, no filesystem, no timers and no module
 * system beyond the built-in {@code cloudfront} module. Emulating it with a
 * normal Node process would be strictly more permissive than the real thing,
 * which is the one failure mode that matters here: code that works locally and
 * breaks on deploy.
 *
 * <p>So the engine is a long-lived {@code node} child process whose only job is
 * to evaluate function code inside a fresh {@code node:vm} context with dynamic
 * code generation disabled. A fresh V8 context exposes the ECMAScript
 * intrinsics and nothing else, which is the CloudFront shape. See
 * {@code src/main/resources/cloudfront/cf-function-host.mjs}.
 *
 * <p>The same runtime backs both the emulated edge
 * ({@link CloudFrontEdgeController}) and the {@code TestFunction} API, so a
 * locally-served request and {@code aws cloudfront test-function} agree.
 *
 * <p>This is a fidelity sandbox, not a security boundary — like every other
 * emulated runtime in floci it executes code you already trust.
 */
@ApplicationScoped
public class CloudFrontFunctionRuntime {

    private static final Logger LOG = Logger.getLogger(CloudFrontFunctionRuntime.class);
    private static final String HOST_SCRIPT_RESOURCE = "cloudfront/cf-function-host.mjs";

    private final CloudFrontService service;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    private final Object lifecycleLock = new Object();
    private final Object writeLock = new Object();
    private final Map<String, CompletableFuture<Execution>> inflight = new ConcurrentHashMap<>();
    private final Map<String, List<String>> executionStores = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    private Process process;
    private BufferedWriter writer;
    private Path scriptPath;

    @Inject
    public CloudFrontFunctionRuntime(CloudFrontService service, ObjectMapper mapper, EmulatorConfig config) {
        this.service = service;
        this.mapper = mapper;
        this.config = config;
    }

    /**
     * The result of one function invocation. {@code output} is the event object
     * the function returned — either a request or a response.
     */
    public record Execution(boolean ok,
                            JsonNode output,
                            JsonNode origin,
                            String originId,
                            List<String> logs,
                            String error,
                            long micros) {

        /** CloudFront reports a response when the returned object carries a status code. */
        public boolean isResponse() {
            return ok && output != null && output.hasNonNull("statusCode");
        }

        /**
         * Percentage of the documented per-invocation budget this run consumed.
         * Derived from wall time in the emulator's JS engine, so it is a rough
         * signal only — it is not comparable to the value AWS reports.
         */
        public String computeUtilization() {
            long pct = Math.min(100, Math.max(0, micros / 10));
            return String.valueOf(pct);
        }
    }

    /**
     * Execute {@code function}'s code against {@code event}.
     *
     * @param event the CloudFront event object (viewer-request or viewer-response shape)
     */
    public Execution execute(CloudFrontFunction function, JsonNode event) {
        String code = function.getFunctionCode();
        if (code == null || code.isBlank()) {
            return new Execution(false, null, null, null, List.of(),
                    "The function has no code.", 0);
        }
        String id = "x" + sequence.incrementAndGet();
        List<String> stores = function.getKeyValueStoreArns() != null
                ? List.copyOf(function.getKeyValueStoreArns())
                : List.of();

        ObjectNode frame = mapper.createObjectNode();
        frame.put("t", "exec");
        frame.put("id", id);
        frame.put("code", code);
        frame.set("event", event);
        frame.put("timeoutMs", config.services().cloudfront().functionTimeoutMs());
        frame.put("maxCodeBytes", config.services().cloudfront().functionMaxCodeBytes());
        var kvsIds = frame.putArray("kvsIds");
        stores.forEach(kvsIds::add);

        CompletableFuture<Execution> future = new CompletableFuture<>();
        inflight.put(id, future);
        executionStores.put(id, stores);
        try {
            ensureProcess();
            write(frame);
            long budget = config.services().cloudfront().functionTimeoutMs() + 5000L;
            return future.get(budget, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new Execution(false, null, null, null, List.of(),
                    "The CloudFront Functions runtime did not respond in time.", 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Execution(false, null, null, null, List.of(),
                    "Interrupted while running the CloudFront Function.", 0);
        } catch (ExecutionException e) {
            return new Execution(false, null, null, null, List.of(),
                    String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), 0);
        } finally {
            inflight.remove(id);
            executionStores.remove(id);
        }
    }

    // ── Sidecar lifecycle ─────────────────────────────────────────────────────

    private void ensureProcess() {
        synchronized (lifecycleLock) {
            if (process != null && process.isAlive()) {
                return;
            }
            String command = config.services().cloudfront().functionRuntimeCommand();
            Path script = hostScript();
            ProcessBuilder builder = new ProcessBuilder(command, script.toString());
            builder.redirectErrorStream(false);
            try {
                process = builder.start();
            } catch (IOException e) {
                throw new AwsException("UnsupportedOperation",
                        "CloudFront Functions need a JavaScript runtime. Could not start '" + command
                                + "': " + e.getMessage()
                                + ". Install Node.js in the emulator image or set"
                                + " FLOCI_SERVICES_CLOUDFRONT_FUNCTION_RUNTIME_COMMAND to its path.", 400);
            }
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Process started = process;
            Thread reader = new Thread(() -> readLoop(started), "cloudfront-function-runtime");
            reader.setDaemon(true);
            reader.start();
            Thread errors = new Thread(() -> drainErrors(started), "cloudfront-function-runtime-stderr");
            errors.setDaemon(true);
            errors.start();
            LOG.infov("Started the CloudFront Functions runtime ({0} {1})", command, script);
        }
    }

    private Path hostScript() {
        if (scriptPath != null && Files.exists(scriptPath)) {
            return scriptPath;
        }
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(HOST_SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new AwsException("UnsupportedOperation",
                        "The CloudFront Functions runtime host script is missing from the emulator image.", 500);
            }
            Path target = Files.createTempDirectory("floci-cloudfront").resolve("cf-function-host.mjs");
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
            scriptPath = target;
            return target;
        } catch (IOException e) {
            throw new AwsException("UnsupportedOperation",
                    "Could not stage the CloudFront Functions runtime host script: " + e.getMessage(), 500);
        }
    }

    private void write(ObjectNode frame) {
        synchronized (writeLock) {
            try {
                writer.write(mapper.writeValueAsString(frame));
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                throw new AwsException("UnsupportedOperation",
                        "The CloudFront Functions runtime is not reachable: " + e.getMessage(), 500);
            }
        }
    }

    private void readLoop(Process owner) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(owner.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handleFrame(line);
            }
        } catch (IOException e) {
            LOG.debugv(e, "CloudFront Functions runtime stdout closed");
        }
        failInflight("The CloudFront Functions runtime exited.");
    }

    private void drainErrors(Process owner) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(owner.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOG.warnv("cloudfront-function-runtime: {0}", line);
            }
        } catch (IOException e) {
            LOG.debugv(e, "CloudFront Functions runtime stderr closed");
        }
    }

    private void handleFrame(String line) {
        JsonNode frame;
        try {
            frame = mapper.readTree(line);
        } catch (IOException e) {
            LOG.warnv("Unparseable frame from the CloudFront Functions runtime: {0}", line);
            return;
        }
        String type = frame.path("t").asText("");
        switch (type) {
            case "ready" -> LOG.debugv("CloudFront Functions runtime ready (protocol {0})",
                    frame.path("protocol").asInt());
            case "kvs" -> answerKeyValueStore(frame);
            case "result" -> completeExecution(frame);
            default -> LOG.warnv("Unknown frame type from the CloudFront Functions runtime: {0}", type);
        }
    }

    private void completeExecution(JsonNode frame) {
        String id = frame.path("id").asText("");
        CompletableFuture<Execution> future = inflight.get(id);
        if (future == null) {
            return;
        }
        List<String> logs = new ArrayList<>();
        frame.path("logs").forEach(entry -> logs.add(entry.asText()));
        JsonNode origin = frame.get("origin");
        future.complete(new Execution(
                frame.path("ok").asBoolean(false),
                frame.get("output"),
                origin != null && !origin.isNull() ? origin : null,
                frame.path("originId").isNull() ? null : frame.path("originId").asText(null),
                logs,
                frame.path("error").isMissingNode() ? null : frame.path("error").asText(null),
                frame.path("micros").asLong(0)));
    }

    /**
     * Resolve a {@code cf.kvs()} operation against the emulator's own key value
     * store. The function's associated store ARNs bound what it may read, and
     * {@code cf.kvs()} with no argument selects the first association — the same
     * rule CloudFront applies.
     */
    private void answerKeyValueStore(JsonNode frame) {
        String id = frame.path("id").asText("");
        ObjectNode reply = mapper.createObjectNode();
        reply.put("t", "kvs.result");
        reply.put("id", id);
        reply.put("cid", frame.path("cid").asText(""));
        try {
            String arn = resolveStoreArn(executionStores.getOrDefault(id, List.of()),
                    frame.path("kvsId").isNull() ? null : frame.path("kvsId").asText(null));
            switch (frame.path("op").asText("")) {
                case "get" -> {
                    String value = service.kvsGetKey(arn, frame.path("key").asText(""));
                    if ("json".equals(frame.path("format").asText("string"))) {
                        reply.set("value", mapper.readTree(value));
                    } else {
                        reply.put("value", value);
                    }
                    reply.put("ok", true);
                }
                case "exists" -> {
                    reply.put("value", service.kvsKeys(arn).containsKey(frame.path("key").asText("")));
                    reply.put("ok", true);
                }
                case "meta" -> {
                    ObjectNode meta = reply.putObject("value");
                    meta.put("keyCount", service.kvsKeys(arn).size());
                    reply.put("ok", true);
                }
                default -> {
                    reply.put("ok", false);
                    reply.put("error", "Unsupported key value store operation.");
                }
            }
        } catch (AwsException e) {
            reply.put("ok", false);
            reply.put("error", e.getErrorCode() + ": " + e.getMessage());
        } catch (Exception e) {
            reply.put("ok", false);
            reply.put("error", String.valueOf(e.getMessage()));
        }
        write(reply);
    }

    private String resolveStoreArn(List<String> associated, String requested) {
        if (associated.isEmpty()) {
            throw new AwsException("InvalidArgument",
                    "No key value store is associated with this function.", 400);
        }
        if (requested == null || requested.isBlank()) {
            return associated.get(0);
        }
        for (String arn : associated) {
            if (arn.equals(requested) || arn.endsWith("/" + requested)) {
                return arn;
            }
        }
        throw new AwsException("InvalidArgument",
                "Key value store '" + requested + "' is not associated with this function.", 400);
    }

    private void failInflight(String message) {
        inflight.forEach((id, future) -> future.complete(
                new Execution(false, null, null, null, List.of(), message, 0)));
        inflight.clear();
    }

    @PreDestroy
    void stop() {
        synchronized (lifecycleLock) {
            if (process != null) {
                process.destroy();
                process = null;
            }
        }
    }
}
