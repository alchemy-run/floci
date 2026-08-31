package io.github.hectorvent.floci.services.aiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.aiops.model.InvestigationGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiOpsServiceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiOpsService service = new AiOpsService(
            new InMemoryStorage<>(), new RegionResolver(REGION, "000000000201"));

    @Test
    void getMissingGroupThrowsResourceNotFound() {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.get(REGION, "nonexistent0000000000"));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void createThenGetRoundTripsNameRetentionAndArn() throws Exception {
        InvestigationGroup created = service.create(REGION, objectMapper.readTree("""
                {
                  "name":"unit-group",
                  "roleArn":"arn:aws:iam::000000000201:role/AIOps",
                  "retentionInDays":7,
                  "tagKeyBoundaries":["Application"],
                  "tags":{"Environment":"test"}
                }
                """));

        assertEquals("unit-group", created.getName());
        assertEquals(7, created.getRetentionInDays());
        assertTrue(created.getArn().contains(":investigation-group/"));
        assertEquals("test", created.getTags().get("Environment"));

        InvestigationGroup fetched = service.get(REGION, created.getArn());
        assertEquals(created.getArn(), fetched.getArn());
        assertEquals(7, fetched.getRetentionInDays());
        assertEquals("unit-group", service.get(REGION, "unit-group").getName());
    }

    @Test
    void secondCreateInTheSameRegionConflicts() throws Exception {
        service.create(REGION, objectMapper.readTree("""
                {"name":"first","roleArn":"arn:aws:iam::000000000201:role/AIOps"}
                """));

        AwsException error = assertThrows(
                AwsException.class,
                () -> service.create(REGION, objectMapper.readTree("""
                        {"name":"second","roleArn":"arn:aws:iam::000000000201:role/AIOps"}
                        """)));
        assertEquals("ConflictException", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
    }

    @Test
    void getPolicyWhenUnattachedThrowsResourceNotFound() throws Exception {
        InvestigationGroup created = service.create(REGION, objectMapper.readTree("""
                {"name":"policy-group","roleArn":"arn:aws:iam::000000000201:role/AIOps","retentionInDays":7}
                """));

        AwsException error = assertThrows(
                AwsException.class,
                () -> service.getPolicy(REGION, created.getArn()));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }
}
