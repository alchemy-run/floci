package io.floci.aws.services.bedrockruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.bedrockruntime.backend.BedrockBackend;
import io.floci.aws.services.bedrockruntime.backend.ProxyBackend;
import io.floci.aws.services.bedrockruntime.backend.StubBackend;
import io.floci.aws.services.bedrockruntime.backend.StubModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * Thin orchestration layer for Bedrock Runtime: delegates Converse/InvokeModel to the
 * backend selected by {@code floci.services.bedrock-runtime.backend}.
 */
@ApplicationScoped
public class BedrockRuntimeService {

    private static final Logger LOG = Logger.getLogger(BedrockRuntimeService.class);

    private final EmulatorConfig config;
    private final StubBackend stubBackend;
    private final ProxyBackend proxyBackend;

    @Inject
    public BedrockRuntimeService(EmulatorConfig config, StubBackend stubBackend, ProxyBackend proxyBackend) {
        this.config = config;
        this.stubBackend = stubBackend;
        this.proxyBackend = proxyBackend;
    }

    public ObjectNode buildConverseResponse(String modelId, ObjectNode bedrockRequest) {
        return backend().converse(modelId, bedrockRequest);
    }

    public byte[] buildInvokeModelResponse(String modelId, byte[] body) {
        return backend().invokeModel(modelId, body);
    }

    public Consumer<OutputStream> buildConverseStreamResponse(String modelId, ObjectNode bedrockRequest) {
        return backend().converseStream(modelId, bedrockRequest);
    }

    public Consumer<OutputStream> buildInvokeModelStreamResponse(String modelId, byte[] body) {
        return backend().invokeModelWithResponseStream(modelId, body);
    }

    /**
     * CountTokens: the tokens of a Converse input (system, messages, tools) or of an
     * InvokeModel body, counted the way the stub backend counts its replies, as words
     * and punctuation marks. Bedrock counts with the model's own tokenizer, so real
     * counts differ; the count is stable and grows with the input.
     */
    public int countTokens(JsonNode input) {
        if (input.has("converse")) {
            return Math.max(1, StubModel.countConverseTokens(input.get("converse")));
        }
        if (input.has("invokeModel")) {
            return Math.max(1, StubModel.countTokens(input.get("invokeModel").path("body").asText("")));
        }
        throw new AwsException("ValidationException", "input must contain converse or invokeModel.", 400);
    }

    private BedrockBackend backend() {
        String backend = config.services().bedrockRuntime().backend();
        if ("proxy".equalsIgnoreCase(backend)) {
            return proxyBackend;
        }
        if (!"stub".equalsIgnoreCase(backend)) {
            LOG.warnv("Unrecognized floci.services.bedrock-runtime.backend value \"{0}\"; falling back to stub. "
                    + "Expected \"stub\" or \"proxy\".", backend);
        }
        return stubBackend;
    }
}
