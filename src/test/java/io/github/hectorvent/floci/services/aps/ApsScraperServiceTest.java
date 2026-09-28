package io.github.hectorvent.floci.services.aps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.aps.model.PrometheusWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApsScraperServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String CLUSTER_ARN = "arn:aws:eks:us-east-1:000000000000:cluster/scraped";
    private static final String CONFIG = Base64.getEncoder().encodeToString(
            "global:\n  scrape_interval: 30s\nscrape_configs:\n  - job_name: apiserver\n"
                    .getBytes(StandardCharsets.UTF_8));
    private static final String LOG_GROUP = "arn:aws:logs:us-east-1:000000000000:log-group:/aws/vendedlogs/scraper:*";

    private ApsService service;
    private ApsScraperSources sources;
    private PrometheusWorkspace workspace;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> {
                    StorageBackend<String, ?> backend = AccountAwareStorageBackend.inMemory(ACCOUNT);
                    return backend;
                });
        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn("https://localhost:4566");
        sources = Mockito.mock(ApsScraperSources.class);
        when(sources.validateSource(anyString(), anyString(), any())).thenAnswer(invocation ->
                ((JsonNode) invocation.getArgument(2)).deepCopy());
        when(sources.createScraperRole(anyString())).thenAnswer(invocation ->
                "arn:aws:iam::000000000000:role/aws-service-role/scraper.aps.amazonaws.com/AWSServiceRoleForScraperAps_"
                        + ((String) invocation.getArgument(0)).substring(2));
        service = new ApsService(storageFactory, new RegionResolver(REGION, ACCOUNT), config,
                Mockito.mock(ApsPrometheusBackend.class), sources);
        workspace = service.createWorkspace(REGION, "scraped", Map.of(), null);
    }

    private Map<String, Object> createRequest(String alias) {
        Map<String, Object> request = new HashMap<>();
        request.put("alias", alias);
        request.put("scrapeConfiguration", Map.of("configurationBlob", CONFIG));
        request.put("source", Map.of("eksConfiguration",
                Map.of("clusterArn", CLUSTER_ARN, "subnetIds", List.of("subnet-a", "subnet-b"))));
        request.put("destination", Map.of("ampConfiguration", Map.of("workspaceArn", workspace.getArn())));
        request.put("tags", Map.of("Environment", "test"));
        return request;
    }

    @Test
    void scraperLifecycleCreatesUpdatesListsAndDeletes() {
        ObjectNode created = service.createScraper(REGION, createRequest("first"));
        String scraperId = created.path("scraperId").asText();
        assertTrue(scraperId.matches("s-[0-9a-f-]{36}"));
        assertEquals("CREATING", created.path("status").path("statusCode").asText());
        assertEquals("arn:aws:aps:us-east-1:000000000000:scraper/" + scraperId, created.path("arn").asText());
        assertEquals("test", created.path("tags").path("Environment").asText());

        ObjectNode described = service.describeScraper(REGION, scraperId);
        assertEquals("ACTIVE", described.path("status").path("statusCode").asText());
        assertEquals(ApsService.SCRAPER_LIMITATION, described.path("statusReason").asText());
        assertEquals(CONFIG, described.path("scrapeConfiguration").path("configurationBlob").asText());
        assertEquals(CLUSTER_ARN, described.path("source").path("eksConfiguration").path("clusterArn").asText());
        assertTrue(described.path("roleArn").asText().endsWith(scraperId.substring(2)));
        assertTrue(described.path("createdAt").isNumber());

        ObjectNode updated = service.updateScraper(REGION, scraperId, Map.of("alias", "second"));
        assertEquals("UPDATING", updated.path("status").path("statusCode").asText());
        assertEquals("second", service.describeScraper(REGION, scraperId).path("alias").asText());
        assertEquals(CONFIG, service.describeScraper(REGION, scraperId)
                .path("scrapeConfiguration").path("configurationBlob").asText());

        PaginatedResult<ObjectNode> listed = service.listScrapers(REGION,
                Map.of("alias", List.of("second"), "status", List.of("ACTIVE", "CREATING")), null, null);
        assertEquals(1, listed.items().size());
        assertFalse(listed.items().getFirst().has("scrapeConfiguration"));
        assertTrue(service.listScrapers(REGION, Map.of("alias", List.of("first")), null, null).items().isEmpty());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.listScrapers(REGION, Map.of("color", List.of("red")), null, null)).getErrorCode());

        String arn = created.path("arn").asText();
        service.tagResource(REGION, arn, Map.of("team", "metrics"));
        assertEquals(Map.of("Environment", "test", "team", "metrics"), service.listTags(REGION, arn));
        service.untagResource(REGION, arn, List.of("team"));
        assertEquals(Map.of("Environment", "test"), service.listTags(REGION, arn));

        String roleArn = described.path("roleArn").asText();
        ObjectNode deleted = service.deleteScraper(REGION, scraperId);
        assertEquals("DELETING", deleted.path("status").path("statusCode").asText());
        verify(sources).deleteScraperRole(roleArn);
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.describeScraper(REGION, scraperId)).getErrorCode());
    }

    @Test
    void scraperLoggingConfigurationIsAnUpsertScopedToTheScraper() {
        String scraperId = service.createScraper(REGION, createRequest("logged")).path("scraperId").asText();
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.describeScraperLoggingConfiguration(REGION, scraperId)).getErrorCode());

        Map<String, Object> request = Map.of("loggingDestination",
                Map.of("cloudWatchLogs", Map.of("logGroupArn", LOG_GROUP)));
        assertEquals("CREATING", service.updateScraperLoggingConfiguration(REGION, scraperId, request)
                .path("status").path("statusCode").asText());
        ObjectNode logging = service.describeScraperLoggingConfiguration(REGION, scraperId);
        assertEquals("ACTIVE", logging.path("status").path("statusCode").asText());
        assertEquals(scraperId, logging.path("scraperId").asText());
        assertEquals(LOG_GROUP, logging.path("loggingDestination").path("cloudWatchLogs").path("logGroupArn").asText());
        assertEquals(3, logging.path("scraperComponents").size());
        assertTrue(logging.path("modifiedAt").isNumber());
        assertEquals("UPDATING", service.updateScraperLoggingConfiguration(REGION, scraperId, request)
                .path("status").path("statusCode").asText());

        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.updateScraperLoggingConfiguration(REGION, scraperId, Map.of("loggingDestination",
                        Map.of("cloudWatchLogs", Map.of("logGroupArn", LOG_GROUP.replace(":*", "")))))).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.updateScraperLoggingConfiguration(REGION, scraperId, Map.of(
                        "loggingDestination", Map.of("cloudWatchLogs", Map.of("logGroupArn", LOG_GROUP)),
                        "scraperComponents", List.of(Map.of("type", "BOGUS"))))).getErrorCode());

        service.deleteScraperLoggingConfiguration(REGION, scraperId);
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.deleteScraperLoggingConfiguration(REGION, scraperId)).getErrorCode());
    }

    @Test
    void createRejectsInvalidConfigurationAndMissingWorkspace() {
        Map<String, Object> badConfig = createRequest("bad");
        badConfig.put("scrapeConfiguration", Map.of("configurationBlob",
                Base64.getEncoder().encodeToString("global: {}\n".getBytes(StandardCharsets.UTF_8))));
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createScraper(REGION, badConfig)).getErrorCode());

        Map<String, Object> badAlias = createRequest("-leading-dash");
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createScraper(REGION, badAlias)).getErrorCode());

        Map<String, Object> missingWorkspace = createRequest("orphan");
        missingWorkspace.put("destination", Map.of("ampConfiguration", Map.of("workspaceArn",
                "arn:aws:aps:us-east-1:000000000000:workspace/ws-00000000-0000-0000-0000-000000000000")));
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.createScraper(REGION, missingWorkspace)).getErrorCode());
        Mockito.verify(sources, Mockito.never()).createScraperRole(anyString());
    }
}
