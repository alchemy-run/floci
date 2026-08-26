package io.github.hectorvent.floci.services.transcribe;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TranscribeServiceTest {

    @Test
    void getTranscriptionJob_missing_throwsBadRequestException() {
        TranscribeService service = new TranscribeService(null);

        AwsException error = assertThrows(AwsException.class,
                () -> service.getTranscriptionJob("alchemy-nonexistent-job-probe"));

        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }
}
