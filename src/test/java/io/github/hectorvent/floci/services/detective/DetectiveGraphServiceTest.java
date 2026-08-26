package io.github.hectorvent.floci.services.detective;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.detective.model.Graph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Graph create/list/tag/delete — the operations Graph.test.ts drives. */
class DetectiveGraphServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000601";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DetectiveService service = new DetectiveService(
            new InMemoryStorage<>(), new RegionResolver(REGION, ACCOUNT));

    @Test
    void listGraphsOnAFreshAccountIsEmpty() {
        assertTrue(service.listGraphs(REGION).isEmpty());
    }

    @Test
    void createListTagUntagAndDeleteLifecycle() throws Exception {
        assertTrue(service.listGraphs(REGION).isEmpty());

        Graph created = service.createGraph(REGION, objectMapper.readTree("""
                {"Tags":{"env":"test","alchemy::id":"Graph"}}
                """));
        assertTrue(created.getArn().contains(":graph:"));
        assertTrue(created.getArn().startsWith("arn:aws:detective:" + REGION + ":" + ACCOUNT + ":graph:"));
        assertEquals("test", created.getTags().get("env"));
        assertEquals("Graph", created.getTags().get("alchemy::id"));

        List<Graph> listed = service.listGraphs(REGION);
        assertEquals(1, listed.size());
        assertEquals(created.getArn(), listed.get(0).getArn());
        assertEquals(created.getCreatedTime(), listed.get(0).getCreatedTime());

        assertEquals("test", service.listTags(REGION, created.getArn()).get("env"));

        service.tagResource(REGION, created.getArn(), Map.of("env", "prod"));
        assertEquals("prod", service.listTags(REGION, created.getArn()).get("env"));
        assertEquals("Graph", service.listTags(REGION, created.getArn()).get("alchemy::id"));

        service.untagResource(REGION, created.getArn(), List.of("env"));
        assertEquals(null, service.listTags(REGION, created.getArn()).get("env"));
        assertEquals("Graph", service.listTags(REGION, created.getArn()).get("alchemy::id"));

        service.deleteGraph(REGION, objectMapper.readTree("{\"GraphArn\":\"" + created.getArn() + "\"}"));
        assertTrue(service.listGraphs(REGION).isEmpty());

        AwsException missing = assertThrows(
                AwsException.class,
                () -> service.deleteGraph(
                        REGION, objectMapper.readTree("{\"GraphArn\":\"" + created.getArn() + "\"}")));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());
    }
}
