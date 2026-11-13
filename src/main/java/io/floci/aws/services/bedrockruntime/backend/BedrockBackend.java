package io.floci.aws.services.bedrockruntime.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsEventStreamWriter;

import java.io.OutputStream;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * Executes Bedrock Runtime Converse/InvokeModel requests against a concrete backend
 * (a hardcoded stub, or a proxy to a real OpenAI-compatible model endpoint).
 */
public interface BedrockBackend {

    ObjectNode converse(String modelId, ObjectNode bedrockRequest);

    byte[] invokeModel(String modelId, byte[] body);

    Consumer<OutputStream> converseStream(String modelId, ObjectNode bedrockRequest);

    /**
     * InvokeModelWithResponseStream: by default the whole InvokeModel response in one
     * {@code chunk} event; a backend that can stream overrides it.
     */
    default Consumer<OutputStream> invokeModelWithResponseStream(String modelId, byte[] body) {
        byte[] response = invokeModel(modelId, body);
        return output -> {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode event = mapper.createObjectNode();
            event.put("bytes", Base64.getEncoder().encodeToString(response));
            AwsEventStreamWriter.writeEvent(mapper, output, "chunk", event);
        };
    }
}
