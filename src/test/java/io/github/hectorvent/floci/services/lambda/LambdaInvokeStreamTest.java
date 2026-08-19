package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaInvokeStreamTest {

    @Test
    void encodeInvokeStream_containsPayloadAndCompleteEvents() throws Exception {
        InvokeResult result = new InvokeResult(200, null, "ok".getBytes(StandardCharsets.UTF_8), null, "req-1");
        byte[] encoded = LambdaExtendedController.encodeInvokeStream(result);
        String asString = new String(encoded, StandardCharsets.ISO_8859_1);
        assertTrue(asString.contains("PayloadChunk"), asString);
        assertTrue(asString.contains("InvokeComplete"), asString);
        assertTrue(asString.contains("ok"), asString);
    }
}
