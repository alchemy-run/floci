package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactEventPublisher.PackageVersionChange;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class CodeArtifactEventPublisherTest {

    private static final String ACCOUNT_ID = "123456789012";

    @Test
    @SuppressWarnings("unchecked")
    void publishesAPackageVersionStateChangeToTheOwnersDefaultBus() throws Exception {
        EventBridgeService eventBridge = mock(EventBridgeService.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        ObjectMapper mapper = new ObjectMapper();
        CodeArtifactEventPublisher publisher = new CodeArtifactEventPublisher(eventBridge, regionResolver, mapper);

        publisher.publish(new PackageVersionChange("us-east-1", "packages", ACCOUNT_ID, "source", "generic",
                null, "artifact", "3.0.0", "Published", "REV1", "Created", 1, 0, 0, true, true));

        ArgumentCaptor<List<Map<String, Object>>> entries = ArgumentCaptor.forClass(List.class);
        verify(eventBridge).putEvents(entries.capture(), eq("us-east-1"), isNull());
        Map<String, Object> entry = entries.getValue().getFirst();
        assertEquals("aws.codeartifact", entry.get("Source"));
        assertEquals("CodeArtifact Package Version State Change", entry.get("DetailType"));
        assertNull(entry.get("EventBusName"));
        assertEquals("arn:aws:codeartifact:us-east-1:" + ACCOUNT_ID + ":package/packages/source/generic//artifact",
                ((JsonNode) entry.get("Resources")).get(0).asText());

        JsonNode detail = mapper.readTree((String) entry.get("Detail"));
        assertEquals("packages", detail.path("domainName").asText());
        assertEquals(ACCOUNT_ID, detail.path("domainOwner").asText());
        assertEquals("source", detail.path("repositoryName").asText());
        assertEquals("generic", detail.path("packageFormat").asText());
        assertTrue(detail.get("packageNamespace").isNull());
        assertEquals("artifact", detail.path("packageName").asText());
        assertEquals("3.0.0", detail.path("packageVersion").asText());
        assertEquals("Published", detail.path("packageVersionState").asText());
        assertEquals("REV1", detail.path("packageVersionRevision").asText());
        assertEquals("Created", detail.path("operationType").asText());
        assertEquals(1, detail.path("changes").path("assetsAdded").asInt());
        assertTrue(detail.path("changes").path("statusChanged").asBoolean());
        assertFalse(detail.path("eventDeduplicationId").asText().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void crossAccountDomainEventsGoToTheDomainOwnersAccount() {
        EventBridgeService eventBridge = mock(EventBridgeService.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        CodeArtifactEventPublisher publisher =
                new CodeArtifactEventPublisher(eventBridge, regionResolver, new ObjectMapper());

        publisher.publish(new PackageVersionChange("us-west-2", "shared", "210987654321", "repo", "npm",
                "scope", "pkg", "1.0.0", "Deleted", "REV2", "Deleted", 0, 1, 0, false, true));

        verify(eventBridge).putEvents(any(List.class), eq("us-west-2"), eq("210987654321"));
    }
}
