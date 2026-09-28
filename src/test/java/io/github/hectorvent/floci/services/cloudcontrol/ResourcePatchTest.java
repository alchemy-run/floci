package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourcePatchTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void appliesAllOperationsWithEscapedPointersAndArrayInsertion() throws Exception {
        var source = mapper.readTree("{\"a/b\":{\"~key\":[1,3]},\"Value\":\"old\"}");
        var patch = mapper.readTree("""
                [{"op":"test","path":"/Value","value":"old"},
                 {"op":"add","path":"/a~1b/~0key/1","value":2},
                 {"op":"copy","from":"/a~1b/~0key","path":"/copy"},
                 {"op":"move","from":"/copy/0","path":"/copy/-"},
                 {"op":"replace","path":"/Value","value":"new"},
                 {"op":"remove","path":"/a~1b"}]
                """);
        assertEquals(mapper.readTree("{\"Value\":\"new\",\"copy\":[2,3,1]}"), ResourcePatch.apply(source, patch));
        assertEquals("old", source.path("Value").asText());
    }

    @Test
    void rejectsInvalidPatchesWithoutMutatingInput() throws Exception {
        var source = mapper.readTree("{\"Value\":\"old\",\"list\":[1]}");
        for (String patch : new String[]{
                "[{\"op\":\"replace\",\"path\":\"/absent\",\"value\":1}]",
                "[{\"op\":\"remove\",\"path\":\"/absent\"}]",
                "[{\"op\":\"add\",\"path\":\"/list/01\",\"value\":1}]",
                "[{\"op\":\"add\",\"path\":\"/list/3\",\"value\":1}]",
                "[{\"op\":\"add\",\"path\":\"/bad~2key\",\"value\":1}]",
                "[{\"op\":\"replace\",\"path\":\"/Value\",\"value\":\"new\"},{\"op\":\"test\",\"path\":\"/Value\",\"value\":\"old\"}]"
        }) {
            assertThrows(AwsException.class, () -> ResourcePatch.apply(source, mapper.readTree(patch)));
            assertEquals("old", source.path("Value").asText());
        }
    }
}
