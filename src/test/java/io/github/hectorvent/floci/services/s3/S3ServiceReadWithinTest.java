package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3ServiceReadWithinTest {

    @Test
    void aBodyOfExactlyItsLimitIsReadWhole() {
        List<Long> tooLarge = new ArrayList<>();

        byte[] read = S3Service.readWithin(body("0123456789"), 10, tooLarge::add);

        assertArrayEquals("0123456789".getBytes(StandardCharsets.UTF_8), read);
        assertTrue(tooLarge.isEmpty());
    }

    @Test
    void aBodyThatRunsPastItsLimitIsEntityTooLargeNamingOnlyTheLimit() {
        List<Long> tooLarge = new ArrayList<>();

        AwsException error = assertThrows(AwsException.class,
                () -> S3Service.readWithin(body("0123456789A"), 10, tooLarge::add));

        assertEquals("EntityTooLarge", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("Your proposed upload exceeds the maximum allowed size", error.getMessage());
        assertEquals(Map.of("MaxSizeAllowed", 10L), error.getExtendedData());
        assertEquals(List.of(11L), tooLarge);
    }

    private static ByteArrayInputStream body(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
