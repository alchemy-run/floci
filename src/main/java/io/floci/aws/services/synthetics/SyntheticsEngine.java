package io.floci.aws.services.synthetics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.iam.AssumeRolePolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.lambda.LambdaLayerService;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.services.lambda.model.LambdaLayerVersion;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A canary's Lambda resources, built as AWS builds them: the canary's code is a layer version named after the
 * canary, and a backing function {@code cwsyn-<name>-<id>} runs it on the runtime version's Lambda runtime. The
 * function's own code is floci's Synthetics library and a launcher that runs the canary's handler and reports the
 * run.
 */
@ApplicationScoped
public class SyntheticsEngine {

    private static final Logger LOG = Logger.getLogger(SyntheticsEngine.class);
    static final String LAUNCHER_HANDLER = "__floci_synthetics.handler";
    private static final List<String> NODE_LIBRARY = List.of(
            "__floci_synthetics.js",
            "synthetics_modules/Synthetics/index.js",
            "synthetics_modules/Synthetics/runtime.js",
            "synthetics_modules/SyntheticsLogger/index.js",
            "synthetics_modules/SyntheticsLogHelper/index.js",
            "synthetics_modules/@aws/synthetics-puppeteer/index.js",
            "synthetics_modules/@aws/synthetics-logger/index.js",
            "synthetics_modules/@aws/synthetics-log-helper/index.js",
            "synthetics_modules/@aws/synthetics-playwright/index.js",
            "synthetics_modules/@amzn/synthetics-playwright/index.js");
    private static final List<String> PYTHON_LIBRARY = List.of(
            "__floci_synthetics.py",
            "synthetics_modules/aws_synthetics/__init__.py",
            "synthetics_modules/aws_synthetics/common/__init__.py",
            "synthetics_modules/aws_synthetics/common/_runtime.py",
            "synthetics_modules/aws_synthetics/common/synthetics_logger.py",
            "synthetics_modules/aws_synthetics/common/synthetics_configuration.py",
            "synthetics_modules/aws_synthetics/selenium/__init__.py",
            "synthetics_modules/aws_synthetics/selenium/synthetics_webdriver.py");

    private final Provider<LambdaService> lambda;
    private final Provider<LambdaLayerService> layers;
    private final Provider<S3Service> s3;
    private final Provider<IamService> iam;
    private final Provider<AssumeRolePolicyEvaluator> trust;
    private final ObjectMapper mapper;
    private final Map<String, byte[]> libraries = new LinkedHashMap<>();

    @Inject
    public SyntheticsEngine(Provider<LambdaService> lambda, Provider<LambdaLayerService> layers,
                            Provider<S3Service> s3, Provider<IamService> iam,
                            Provider<AssumeRolePolicyEvaluator> trust, ObjectMapper mapper) {
        this.lambda = lambda;
        this.layers = layers;
        this.s3 = s3;
        this.iam = iam;
        this.trust = trust;
        this.mapper = mapper;
    }

    /** What a backing function runs: its runtime version, handler, role and run settings. */
    record Spec(String functionName, SyntheticsRuntimes.Version runtime, String handler, String roleArn,
                int timeoutSeconds, int memoryMb, Integer ephemeralStorageMb, boolean activeTracing,
                Map<String, String> environment, List<String> subnetIds, List<String> securityGroupIds,
                Map<String, String> tags, String canaryName, String canaryArn) {
    }

    /** A published code layer version. */
    record Code(String layerVersionArn, long version) {
    }

    // ---------- code ----------

    /** The canary's code: an inline zip, or a zip in S3. */
    byte[] readCode(JsonNode code) {
        if (code.hasNonNull("ZipFile")) {
            try {
                return Base64.getDecoder().decode(code.get("ZipFile").asText());
            } catch (IllegalArgumentException e) {
                throw validation("Code.ZipFile is not valid base64.");
            }
        }
        if (!code.hasNonNull("S3Bucket") || !code.hasNonNull("S3Key")) {
            throw validation("Code must have either ZipFile, or S3Bucket and S3Key.");
        }
        String bucket = code.get("S3Bucket").asText();
        String key = code.get("S3Key").asText();
        String version = code.hasNonNull("S3Version") ? code.get("S3Version").asText() : null;
        try (S3Service.ObjectRead read = s3.get().openObject(bucket, key, version)) {
            return read.body().readAllBytes();
        } catch (AwsException e) {
            throw validation("Unable to fetch the canary code from s3://" + bucket + "/" + key + ": "
                    + e.getMessage());
        } catch (IOException e) {
            throw new AwsException("InternalServerException", "The canary code couldn't be read from S3.", 500);
        }
    }

    /** Checks the code is a zip holding the handler's script where the runtime version looks for it. */
    void checkCode(byte[] zip, String handler, SyntheticsRuntimes.Version runtime) {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        } catch (IOException e) {
            throw validation("The canary code is not a valid zip file.");
        }
        if (names.isEmpty()) {
            throw validation("The canary code is not a valid zip file.");
        }
        int dot = handler.lastIndexOf('.');
        String file = handler.substring(0, Math.max(dot, 0));
        List<String> candidates = runtime.python()
                ? List.of("python/" + file + ".py", file + ".py")
                : List.of("nodejs/node_modules/" + file + ".js", "nodejs/node_modules/" + file + ".mjs",
                        "nodejs/" + file + ".js", file + ".js", file + ".mjs");
        if (candidates.stream().noneMatch(names::contains)) {
            throw validation("The canary code has no script for the handler " + handler + " (expected "
                    + candidates.get(0) + ").");
        }
    }

    Code publishCode(String region, String layerName, byte[] zip, SyntheticsRuntimes.Version runtime) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("Content", Map.of("ZipFile", Base64.getEncoder().encodeToString(zip)));
        request.put("Description", "Synthetics canary code");
        if (runtime.lambdaRuntime() != null) {
            request.put("CompatibleRuntimes", List.of(runtime.lambdaRuntime()));
        }
        LambdaLayerVersion published = layers.get().publishLayerVersion(region, layerName, request);
        return new Code(published.getLayerVersionArn(), published.getVersion());
    }

    void deleteLayer(String region, String layerName, long version) {
        try {
            layers.get().deleteLayerVersion(region, layerName, version);
        } catch (AwsException e) {
            LOG.debugv("Layer {0}:{1} was already gone: {2}", layerName, version, e.getMessage());
        }
    }

    // ---------- role ----------

    /** Why Lambda can't assume the role, as Lambda puts it, or empty when it can. */
    Optional<String> roleRefusal(String account, String roleArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(roleArn);
        } catch (IllegalArgumentException e) {
            return Optional.of("The role defined for the function cannot be assumed by Lambda.");
        }
        String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        Optional<IamRole> role = iam.get().findRole(arn.accountId(), name)
                .filter(found -> IamService.roleArnMatches(roleArn, found));
        if (role.isEmpty() || !account.equals(arn.accountId())
                || !trust.get().allowsService(role.get().getAssumeRolePolicyDocument(),
                ServicePrincipals.of("lambda"))) {
            return Optional.of("The role defined for the function cannot be assumed by Lambda.");
        }
        return Optional.empty();
    }

    // ---------- backing function ----------

    /** Creates or updates the backing function and publishes a version of it; returns the version's ARN. */
    String deploy(String region, Spec spec, Code code) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("Runtime", spec.runtime().lambdaRuntime());
        config.put("Role", spec.roleArn());
        config.put("Handler", LAUNCHER_HANDLER);
        config.put("Layers", List.of(code.layerVersionArn()));
        config.put("Timeout", Math.min(900, spec.timeoutSeconds() + 60));
        config.put("MemorySize", spec.memoryMb());
        config.put("Description", "Backing function of the Synthetics canary " + spec.canaryName());
        Map<String, String> environment = new LinkedHashMap<>(spec.environment());
        environment.put("SYNTHETICS_CANARY_NAME", spec.canaryName());
        environment.put("SYNTHETICS_CANARY_ARN", spec.canaryArn());
        environment.put("SYNTHETICS_RUNTIME_VERSION", spec.runtime().name());
        environment.put("SYNTHETICS_HANDLER", spec.handler());
        environment.put("SYNTHETICS_TIMEOUT_SECONDS", String.valueOf(spec.timeoutSeconds()));
        config.put("Environment", Map.of("Variables", environment));
        config.put("TracingConfig", Map.of("Mode", spec.activeTracing() ? "Active" : "PassThrough"));
        if (spec.ephemeralStorageMb() != null) {
            config.put("EphemeralStorage", Map.of("Size", spec.ephemeralStorageMb()));
        }
        if (!spec.subnetIds().isEmpty()) {
            config.put("VpcConfig", Map.of("SubnetIds", spec.subnetIds(),
                    "SecurityGroupIds", spec.securityGroupIds()));
        }
        LambdaService service = lambda.get();
        if (exists(region, spec.functionName())) {
            service.updateFunctionConfiguration(region, spec.functionName(), config);
        } else {
            Map<String, Object> create = new LinkedHashMap<>(config);
            create.put("FunctionName", spec.functionName());
            create.put("Code", Map.of("ZipFile", Base64.getEncoder().encodeToString(library(spec.runtime()))));
            if (!spec.tags().isEmpty()) {
                create.put("Tags", spec.tags());
            }
            service.createFunction(region, create);
        }
        LambdaFunction version = service.publishVersion(region, spec.functionName(), null);
        return version.getFunctionArn().endsWith(":" + version.getVersion())
                ? version.getFunctionArn() : version.getFunctionArn() + ":" + version.getVersion();
    }

    boolean exists(String region, String functionName) {
        try {
            lambda.get().getFunction(region, functionName);
            return true;
        } catch (AwsException e) {
            return false;
        }
    }

    void deleteFunction(String region, String functionName) {
        try {
            lambda.get().deleteFunction(region, functionName);
        } catch (AwsException e) {
            LOG.debugv("Backing function {0} was already gone: {1}", functionName, e.getMessage());
        }
    }

    /** The outcome of one canary run, as the launcher reports it. */
    record Outcome(boolean passed, String reason, boolean executionFailure, JsonNode steps, String log,
                   String startTime, String endTime) {
    }

    /** Runs the canary once on its backing function version. */
    Outcome invoke(String region, String engineArn, String runId) {
        String functionName = functionNameOf(engineArn);
        String qualifier = qualifierOf(engineArn);
        byte[] payload = mapper.createObjectNode().put("canaryRunId", runId).toString()
                .getBytes(StandardCharsets.UTF_8);
        InvokeResult result;
        try {
            result = lambda.get().invoke(region, functionName, qualifier, payload, InvocationType.RequestResponse);
        } catch (AwsException e) {
            return new Outcome(false, e.getMessage(), true, mapper.createArrayNode(), "", null, null);
        }
        JsonNode body;
        try {
            body = mapper.readTree(result.getPayload() == null ? new byte[0] : result.getPayload());
        } catch (IOException e) {
            body = mapper.createObjectNode();
        }
        if (result.getFunctionError() != null || body == null || !body.has("status")) {
            String message = body != null && body.hasNonNull("errorMessage") ? body.get("errorMessage").asText()
                    : "The canary's backing function failed (" + result.getFunctionError() + ").";
            return new Outcome(false, message, true, mapper.createArrayNode(), "", null, null);
        }
        boolean passed = "PASSED".equals(body.path("status").asText());
        return new Outcome(passed, passed ? null : body.path("reason").asText("Canary failed"), false,
                body.path("steps"), body.path("log").asText(""), body.path("startTime").asText(null),
                body.path("endTime").asText(null));
    }

    static String functionNameOf(String engineArn) {
        String rest = engineArn.substring(engineArn.indexOf(":function:") + ":function:".length());
        int colon = rest.indexOf(':');
        return colon < 0 ? rest : rest.substring(0, colon);
    }

    private static String qualifierOf(String engineArn) {
        String rest = engineArn.substring(engineArn.indexOf(":function:") + ":function:".length());
        int colon = rest.indexOf(':');
        return colon < 0 ? null : rest.substring(colon + 1);
    }

    // ---------- library ----------

    private synchronized byte[] library(SyntheticsRuntimes.Version runtime) {
        String language = runtime.python() ? "python" : "nodejs";
        return libraries.computeIfAbsent(language, l -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (String file : "python".equals(l) ? PYTHON_LIBRARY : NODE_LIBRARY) {
                    try (InputStream in = SyntheticsEngine.class.getResourceAsStream("/synthetics/" + l + "/" + file)) {
                        if (in == null) {
                            throw new IllegalStateException("Missing Synthetics library file " + file);
                        }
                        zip.putNextEntry(new ZipEntry(file));
                        in.transferTo(zip);
                        zip.closeEntry();
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not package the Synthetics library", e);
            }
            return bytes.toByteArray();
        });
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
