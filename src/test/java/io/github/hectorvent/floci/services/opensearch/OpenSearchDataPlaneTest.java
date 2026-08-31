package io.github.hectorvent.floci.services.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenSearchDataPlaneTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenSearchDataPlane plane = new OpenSearchDataPlane(mapper);

    @Test
    void indexGetSearchUpdateDeleteRoundTrip() throws Exception {
        ObjectNode song = mapper.createObjectNode();
        song.put("title", "The Wind Cries Mary");
        song.put("plays", 1);

        ObjectNode indexed = plane.indexDocument("dom", "songs", "1", song);
        assertEquals("created", indexed.path("result").asText());

        OpenSearchDataPlane.GetResult got = plane.getDocument("dom", "songs", "1");
        assertTrue(got.found());
        assertEquals("The Wind Cries Mary", got.body().path("_source").path("title").asText());

        OpenSearchDataPlane.GetResult missing = plane.getDocument("dom", "songs", "missing");
        assertFalse(missing.found());
        assertTrue(plane.existsDocument("dom", "songs", "1"));
        assertFalse(plane.existsDocument("dom", "songs", "missing"));

        ObjectNode query = (ObjectNode) mapper.readTree("{\"query\":{\"match\":{\"title\":\"wind\"}}}");
        ObjectNode search = plane.search("dom", "songs", query);
        assertEquals(1, search.path("hits").path("total").path("value").asInt());
        assertEquals("The Wind Cries Mary",
                search.path("hits").path("hits").get(0).path("_source").path("title").asText());
        assertEquals(1, plane.count("dom", "songs", null).path("count").asInt());

        ObjectNode patch = mapper.createObjectNode();
        ObjectNode doc = patch.putObject("doc");
        doc.put("plays", 2);
        assertEquals("updated", plane.updateDocument("dom", "songs", "1", patch).body().path("result").asText());

        ObjectNode bulk = plane.bulk("dom",
                "{\"index\":{\"_index\":\"songs\",\"_id\":\"2\"}}\n{\"title\":\"Purple Haze\",\"plays\":5}\n");
        assertFalse(bulk.path("errors").asBoolean());
        assertEquals(1, bulk.path("items").size());

        assertEquals("deleted", plane.deleteDocument("dom", "songs", "2").path("result").asText());
        assertEquals("not_found", plane.deleteDocument("dom", "songs", "2").path("result").asText());
        assertEquals("green", plane.clusterHealth("dom").path("status").asText());
    }
}
