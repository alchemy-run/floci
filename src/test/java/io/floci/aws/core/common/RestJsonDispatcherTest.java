package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Routing REST JSON requests by DataZone's vendored HTTP bindings. */
class RestJsonDispatcherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestJsonDispatcher dispatcher = new RestJsonDispatcher(mapper, "datazone");

    private RestJsonDispatcher.Call match(String method, String path, MultivaluedMap<String, String> query,
                                          String body) {
        return dispatcher.match(method, path, query, null, body == null ? null : body.getBytes(StandardCharsets.UTF_8))
                .orElseThrow();
    }

    @Test
    void literalSegmentsOutrankLabels() {
        RestJsonDispatcher.Call search = match("POST", "/v2/domains/dzd_1/listings/search", null, "{}");
        assertEquals("SearchListings", search.operation());
        RestJsonDispatcher.Call changeSet = match("POST", "/v2/domains/dzd_1/listings/change-set", null,
                "{\"entityIdentifier\":\"a\"}");
        assertEquals("CreateListingChangeSet", changeSet.operation());
        assertEquals("dzd_1", changeSet.input().get("domainIdentifier").asText());
        assertEquals("a", changeSet.input().get("entityIdentifier").asText());
        assertEquals("GetListing", match("GET", "/v2/domains/dzd_1/listings/l1", null, null).operation());
    }

    @Test
    void labelsAreDecodedAndQueryMembersTyped() {
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.add("maxResults", "5");
        query.add("timestamp", "2026-01-01T00:00:00Z");
        RestJsonDispatcher.Call node = match("GET", "/v2/domains/dzd_1/lineage/nodes/s3%3A%2F%2Fraw", query, null);
        assertEquals("GetLineageNode", node.operation());
        assertEquals("s3://raw", node.input().get("identifier").asText());
        assertEquals(1767225600.0, node.input().get("eventTimestamp").asDouble());

        RestJsonDispatcher.Call list = match("GET", "/v2/domains/dzd_1/projects", query, null);
        assertEquals(5, list.input().get("maxResults").asInt());
        assertTrue(list.input().get("maxResults").isIntegralNumber());
    }

    @Test
    void aFinalLabelMaySpanAnUnencodedSlash() {
        RestJsonDispatcher.Call tag = match("POST", "/tags/arn:aws:datazone:us-east-1:000000000000:domain/dzd_1",
                null, "{\"tags\":{}}");
        assertEquals("TagResource", tag.operation());
        assertEquals("arn:aws:datazone:us-east-1:000000000000:domain/dzd_1", tag.input().get("resourceArn").asText());
    }

    @Test
    void idempotencyTokensAreFilledAndBlobPayloadsEncoded() {
        RestJsonDispatcher.Call asset = match("POST", "/v2/domains/dzd_1/assets", null, "{\"name\":\"a\"}");
        assertTrue(asset.input().hasNonNull("clientToken"));
        RestJsonDispatcher.Call lineage = match("POST", "/v2/domains/dzd_1/lineage/events", null, "{\"x\":1}");
        assertEquals("eyJ4IjoxfQ==", lineage.input().get("event").asText());
    }

    @Test
    void outputsMoveToHeadersStatusAndPayload() {
        Response response = dispatcher.dispatch("GET", "/v2/domains/dzd_1/lineage/events/e1", null, null, null,
                (operation, input) -> {
                    ObjectNode output = mapper.createObjectNode();
                    output.put("id", "e1");
                    output.put("processingStatus", "SUCCESS");
                    output.put("eventTime", 1767225600.0);
                    output.put("event", "eyJ4IjoxfQ==");
                    return output;
                });
        assertEquals(200, response.getStatus());
        assertEquals("SUCCESS", response.getHeaderString("Processing-Status"));
        assertEquals("Thu, 1 Jan 2026 00:00:00 GMT", response.getHeaderString("Event-Time"));
        assertEquals("{\"x\":1}", new String((byte[]) response.getEntity(), StandardCharsets.UTF_8));

        Response deleted = dispatcher.dispatch("DELETE", "/v2/domains/dzd_1/rules/r1", null, null, null,
                (operation, input) -> mapper.createObjectNode());
        assertEquals(204, deleted.getStatus());
        assertNull(deleted.getEntity());
    }

    @Test
    void anUnknownRouteIsRefused() {
        AwsException error = assertThrows(AwsException.class, () -> dispatcher.dispatch("GET", "/v2/nothing", null,
                null, null, (operation, input) -> mapper.createObjectNode()));
        assertEquals("UnknownOperationException", error.getErrorCode());
    }
}
