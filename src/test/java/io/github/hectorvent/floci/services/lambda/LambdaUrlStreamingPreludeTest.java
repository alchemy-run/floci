package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.services.lambda.model.StreamingPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the application/vnd.awslambda.http-integration-response prelude
 * parser used by the function-URL proxy for streaming responses: JSON metadata,
 * an 8-NUL delimiter, then the raw body.
 */
class LambdaUrlStreamingPreludeTest {

    private static final byte[] DELIMITER = new byte[8];
    private static final String METADATA =
            "{\"statusCode\":201,\"headers\":{\"content-type\":\"text/html\"},\"cookies\":[\"a=1\"]}";

    private final LambdaUrlInvocationController controller =
            new LambdaUrlInvocationController(null, null, new ObjectMapper());

    private static byte[] framed(String metadata, String body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(metadata.getBytes());
        out.write(DELIMITER);
        out.write(body.getBytes());
        return out.toByteArray();
    }

    @Test
    void parsesPreludeAndRemainderFromSingleChunk() throws Exception {
        StreamingPayload stream = new StreamingPayload("application/vnd.awslambda.http-integration-response");
        stream.offer(framed(METADATA, "body-start"));
        stream.close();

        LambdaUrlInvocationController.Prelude prelude = controller.readPrelude(stream);
        assertEquals(201, prelude.metadata().get("statusCode").asInt());
        assertEquals("text/html", prelude.metadata().get("headers").get("content-type").asText());
        assertEquals("a=1", prelude.metadata().get("cookies").get(0).asText());
        assertArrayEquals("body-start".getBytes(), prelude.remainder());
    }

    @Test
    void delimiterSpanningChunkBoundariesIsFound() throws Exception {
        StreamingPayload stream = new StreamingPayload(null);
        byte[] full = framed("{\"statusCode\":200}", "tail");
        // Split mid-delimiter so the 8-NUL run spans two chunks.
        int split = "{\"statusCode\":200}".length() + 3;
        stream.offer(java.util.Arrays.copyOfRange(full, 0, split));
        stream.offer(java.util.Arrays.copyOfRange(full, split, full.length));
        stream.close();

        LambdaUrlInvocationController.Prelude prelude = controller.readPrelude(stream);
        assertEquals(200, prelude.metadata().get("statusCode").asInt());
        assertArrayEquals("tail".getBytes(), prelude.remainder());
    }

    @Test
    void streamEndingBeforeDelimiterThrows() {
        StreamingPayload stream = new StreamingPayload(null);
        stream.offer("{\"statusCode\":200}".getBytes());
        stream.close();
        assertThrows(IllegalStateException.class, () -> controller.readPrelude(stream));
    }

    @Test
    void indexOfDelimiterRequiresEightConsecutiveNuls() {
        byte[] sevenNuls = new byte[]{1, 0, 0, 0, 0, 0, 0, 0, 1};
        assertEquals(-1, LambdaUrlInvocationController.indexOfDelimiter(sevenNuls));

        byte[] eightNuls = new byte[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        assertEquals(1, LambdaUrlInvocationController.indexOfDelimiter(eightNuls));
    }
}
