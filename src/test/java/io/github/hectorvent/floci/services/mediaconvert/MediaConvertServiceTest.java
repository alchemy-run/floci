package io.github.hectorvent.floci.services.mediaconvert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.mediaconvert.model.MediaConvertJob;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaConvertServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void getJobThrowsNotFoundForUnknownId() {
        MediaConvertService service = service(missingRole());
        AwsException error = assertThrows(AwsException.class,
                () -> service.getJob("us-east-1", "0000000000000-aaaaaa"));
        assertEquals("NotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void cancelJobThrowsNotFoundForUnknownId() {
        MediaConvertService service = service(missingRole());
        AwsException error = assertThrows(AwsException.class,
                () -> service.cancelJob("us-east-1", "0000000000000-aaaaaa"));
        assertEquals("NotFoundException", error.getErrorCode());
    }

    @Test
    void createJobWithoutRoleIsBadRequest() {
        MediaConvertService service = service(missingRole());
        AwsException error = assertThrows(AwsException.class,
                () -> service.createJob("us-east-1", mapper.createObjectNode()));
        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createJobWithUnknownRoleIsBadRequest() {
        MediaConvertService service = service(missingRole());
        ObjectNode request = mapper.createObjectNode();
        request.put("role", "arn:aws:iam::000000000000:role/alchemy-does-not-exist");
        request.putObject("settings");
        AwsException error = assertThrows(AwsException.class,
                () -> service.createJob("us-east-1", request));
        assertEquals("BadRequestException", error.getErrorCode());
    }

    @Test
    void createJobWithExistingRoleSucceeds() {
        IamService iam = mock(IamService.class);
        when(iam.findRole(anyString(), anyString())).thenReturn(Optional.of(new IamRole()));
        MediaConvertService service = service(iam);
        ObjectNode request = mapper.createObjectNode();
        request.put("role", "arn:aws:iam::000000000000:role/MediaConvertRole");
        request.putObject("settings");
        MediaConvertJob job = service.createJob("us-east-1", request);
        assertEquals("COMPLETE", job.getStatus());
        assertEquals(job.getId(), service.getJob("us-east-1", job.getId()).getId());
    }

    @Test
    void probeMissingS3IsNotFound() {
        MediaConvertService service = service(missingRole());
        ObjectNode request = mapper.createObjectNode();
        request.putArray("inputFiles").addObject().put("fileUrl", "s3://alchemy-nonexistent/in.mp4");
        AwsException error = assertThrows(AwsException.class, () -> service.probe(request));
        assertEquals("NotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void startJobsQueryCompletesImmediately() {
        MediaConvertService service = service(missingRole());
        ObjectNode started = service.startJobsQuery("us-east-1", mapper.createObjectNode());
        String id = started.get("id").asText();
        var result = service.getJobsQueryResults("us-east-1", id);
        assertEquals("COMPLETE", result.getStatus());
        assertEquals(0, result.getJobs().size());
    }

    @Test
    void listJobsStartsEmpty() {
        MediaConvertService service = service(missingRole());
        assertEquals(0, service.listJobs("us-east-1").get("jobs").size());
    }

    private static IamService missingRole() {
        IamService iam = mock(IamService.class);
        when(iam.findRole(anyString(), anyString())).thenReturn(Optional.empty());
        return iam;
    }

    private MediaConvertService service(IamService iam) {
        return new MediaConvertService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"),
                iam,
                mapper);
    }
}
