package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmrReleaseCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RegionResolver resolver = mock(RegionResolver.class);
    private final EmrReleaseCatalog catalog = new EmrReleaseCatalog(mapper, resolver);

    @Test
    void everyListedReleaseCanBeDescribedAndHasInstanceMetadata() {
        ObjectNode releases = catalog.listReleaseLabels(mapper.createObjectNode(), "us-east-1");
        assertFalse(releases.path("ReleaseLabels").isEmpty());
        releases.path("ReleaseLabels").forEach(label -> {
            ObjectNode request = mapper.createObjectNode().put("ReleaseLabel", label.asText());
            ObjectNode description = catalog.describeReleaseLabel(request, "us-east-1");
            assertEquals(label, description.path("ReleaseLabel"));
            assertFalse(description.path("Applications").isEmpty());
            description.path("Applications").forEach(app -> {
                assertFalse(app.path("Name").asText().isBlank());
                assertFalse(app.path("Version").asText().isBlank());
            });
            ObjectNode instances = catalog.listSupportedInstanceTypes(request, "us-east-1");
            assertFalse(instances.path("SupportedInstanceTypes").isEmpty());
            instances.path("SupportedInstanceTypes").forEach(type -> {
                assertTrue(type.path("VCPU").asInt() > 0);
                assertTrue(type.path("MemoryGB").asInt() > 0);
                assertTrue(type.path("EbsStorageOnly").asBoolean());
                assertEquals(0, type.path("NumberOfDisks").asInt());
            });
        });
    }

    @Test
    void applicationPagesHaveNoDuplicatesAndTerminate() {
        ObjectNode request = mapper.createObjectNode().put("ReleaseLabel", "emr-7.5.0").put("MaxResults", 1);
        ObjectNode first = catalog.describeReleaseLabel(request, "us-east-1");
        assertEquals(1, first.path("Applications").size());
        assertTrue(first.has("NextToken"));
        ObjectNode second = catalog.describeReleaseLabel(request.put("NextToken", first.path("NextToken").asText()), "us-east-1");
        assertEquals(1, second.path("Applications").size());
        assertFalse(second.has("NextToken"));
        Set<String> applications = new HashSet<>();
        applications.add(first.path("Applications").get(0).path("Name").asText());
        applications.add(second.path("Applications").get(0).path("Name").asText());
        assertEquals(Set.of("Hadoop", "Spark"), applications);
    }

    @Test
    void tokensCannotBeReusedAcrossFiltersReleasesAccountsOrRegions() {
        when(resolver.getAccountId()).thenReturn("111111111111");
        ObjectNode first = catalog.listReleaseLabels(mapper.createObjectNode().put("MaxResults", 1), "us-east-1");
        ObjectNode next = mapper.createObjectNode().put("NextToken", first.path("NextToken").asText());
        assertInvalid(() -> catalog.listReleaseLabels(next, "us-west-2"));
        ObjectNode filtered = next.deepCopy();
        filtered.putObject("Filters").put("Prefix", "emr-7.");
        assertInvalid(() -> catalog.listReleaseLabels(filtered, "us-east-1"));
        assertInvalid(() -> catalog.describeReleaseLabel(next.deepCopy().put("ReleaseLabel", "emr-7.5.0"), "us-east-1"));
        when(resolver.getAccountId()).thenReturn("222222222222");
        assertInvalid(() -> catalog.listReleaseLabels(next, "us-east-1"));
        when(resolver.getAccountId()).thenReturn("111111111111");
        ObjectNode request = mapper.createObjectNode().put("ReleaseLabel", "emr-7.5.0").put("MaxResults", 1);
        String token = catalog.describeReleaseLabel(request, "us-east-1").path("NextToken").asText();
        request.put("ReleaseLabel", "emr-6.15.0").put("NextToken", token);
        assertInvalid(() -> catalog.describeReleaseLabel(request, "us-east-1"));
    }

    @Test
    void malformedPaginationIsNotAnInternalServerError() {
        for (int size : new int[] {-1, 0, 101}) {
            assertInvalid(() -> catalog.listReleaseLabels(mapper.createObjectNode().put("MaxResults", size), "us-east-1"));
        }
        assertInvalid(() -> catalog.listReleaseLabels(mapper.createObjectNode().put("MaxResults", "1"), "us-east-1"));
        assertInvalid(() -> catalog.listReleaseLabels(mapper.createObjectNode().put("MaxResults", 1.5), "us-east-1"));
        assertInvalid(() -> catalog.listReleaseLabels(mapper.createObjectNode().put("NextToken", "!"), "us-east-1"));
        String valid = catalog.listReleaseLabels(mapper.createObjectNode().put("MaxResults", 1), "us-east-1")
                .path("NextToken").asText();
        String cursor = new String(Base64.getUrlDecoder().decode(valid), StandardCharsets.UTF_8);
        for (String offset : new String[] {"-1", "0", "999", "abc", "2147483648"}) {
            String malformed = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (cursor.substring(0, cursor.lastIndexOf('\n') + 1) + offset).getBytes(StandardCharsets.UTF_8));
            assertInvalid(() -> catalog.listReleaseLabels(mapper.createObjectNode().put("NextToken", malformed), "us-east-1"));
        }
    }

    private static void assertInvalid(Runnable operation) {
        AwsException error = assertThrows(AwsException.class, operation::run);
        assertEquals("InvalidRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }
}
