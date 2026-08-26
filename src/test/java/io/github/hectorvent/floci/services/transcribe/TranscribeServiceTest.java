package io.github.hectorvent.floci.services.transcribe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TranscribeServiceTest {

    private static final String BOGUS = "alchemy-nonexistent-transcribe-probe";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void getTranscriptionJob_missing_throwsBadRequestException() {
        TranscribeService service = new TranscribeService(null);

        AwsException error = assertThrows(AwsException.class,
                () -> service.getTranscriptionJob("alchemy-nonexistent-job-probe"));

        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void deleteMissingJobs_throwBadRequestException() {
        TranscribeService service = new TranscribeService(null);

        assertBadRequest(() -> service.deleteTranscriptionJob(BOGUS));
        assertBadRequest(() -> service.deleteCallAnalyticsJob(named("CallAnalyticsJobName")));
        assertBadRequest(() -> service.deleteMedicalTranscriptionJob(named("MedicalTranscriptionJobName")));
        assertBadRequest(() -> service.deleteMedicalScribeJob(named("MedicalScribeJobName")));
        assertBadRequest(() -> service.deleteLanguageModel(named("ModelName")));
    }

    @Test
    void deleteMissingVocabularies_throwNotFoundException() {
        TranscribeService service = new TranscribeService(null);

        AwsException vocab = assertThrows(AwsException.class, () -> service.deleteVocabulary(BOGUS));
        assertEquals("NotFoundException", vocab.getErrorCode());
        assertEquals(400, vocab.getHttpStatus());

        AwsException medical = assertThrows(AwsException.class,
                () -> service.deleteMedicalVocabulary(named("VocabularyName")));
        assertEquals("NotFoundException", medical.getErrorCode());
        assertEquals(400, medical.getHttpStatus());
    }

    private static ObjectNode named(String field) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put(field, BOGUS);
        return request;
    }

    private static void assertBadRequest(Runnable action) {
        AwsException error = assertThrows(AwsException.class, action::run);
        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }
}
