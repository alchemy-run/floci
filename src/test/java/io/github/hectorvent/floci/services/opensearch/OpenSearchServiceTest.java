package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import io.github.hectorvent.floci.services.opensearch.model.DomainMaintenance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A domain stays {@code Processing=true} until the readiness poller flips it. Mock mode and a
 * Floci with no reachable Docker daemon report {@code Processing=false} from the first read.
 */
class OpenSearchServiceTest {

    private OpenSearchService service;
    private OpenSearchDomainManager domainManager;
    private EmulatorConfig.OpenSearchServiceConfig osConfig;

    @BeforeEach
    void setUp() {
        domainManager = mock(OpenSearchDomainManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        osConfig = mock(EmulatorConfig.OpenSearchServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.opensearch()).thenReturn(osConfig);
        when(osConfig.mock()).thenReturn(false);

        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        service = new OpenSearchService(AccountAwareStorageBackend.inMemory("000000000000"),
                config, regionResolver, domainManager);
    }

    @Test
    void createDomainKeepsProcessingTrueWhileContainerStarts() {
        when(domainManager.tryStartDomain(any())).thenReturn(true);

        Domain domain = service.createDomain("docker-domain", "OpenSearch_2.11",
                null, null, null, "us-east-1");

        assertTrue(domain.isProcessing(),
                "the readiness poller flips Processing once the container answers");
        assertTrue(service.describeDomain("docker-domain").isProcessing());
        verify(domainManager).tryStartDomain(any());
    }

    @Test
    void createDomainWithoutDockerDaemonStillSucceedsAndReportsProcessingFalse() {
        // tryStartDomain() returns false when no Docker daemon is reachable. The domain record is
        // metadata, so the create still succeeds and the first describe already reports the
        // terminal state.
        when(domainManager.tryStartDomain(any())).thenReturn(false);

        Domain domain = service.createDomain("no-docker-domain", "OpenSearch_2.11",
                null, null, null, "us-east-1");

        assertFalse(domain.isProcessing());
        assertEquals("no-docker-domain", service.describeDomain("no-docker-domain").getDomainName());
        assertFalse(service.describeDomain("no-docker-domain").isProcessing());
    }

    @Test
    void mockModeKeepsProcessingFalse() {
        when(osConfig.mock()).thenReturn(true);

        Domain domain = service.createDomain("mock-domain", "OpenSearch_2.11",
                null, null, null, "us-east-1");

        assertFalse(domain.isProcessing());
        verify(domainManager, Mockito.never()).tryStartDomain(any());
    }

    @Test
    void readinessPollerContinuesAfterOneReadinessFailure() {
        when(domainManager.tryStartDomain(any())).thenReturn(true);
        when(domainManager.isReady(any()))
                .thenThrow(new RuntimeException("temporary readiness failure"))
                .thenReturn(true);

        service.createDomain("ready-recovery", "OpenSearch_2.11",
                null, null, null, "us-east-1");

        service.pollReadiness();
        assertTrue(service.describeDomain("ready-recovery").isProcessing());

        service.pollReadiness();
        assertFalse(service.describeDomain("ready-recovery").isProcessing());
        verify(domainManager, atLeast(2)).isReady(any());
    }

    @Test
    void healthLookupOfMissingDomainIsBaseExceptionWhileMaintenanceStartIsResourceNotFound() {
        AwsException health = assertThrows(AwsException.class,
                () -> service.describeDomainNodes("absent-domain"));
        assertEquals("BaseException", health.getErrorCode());
        assertEquals(400, health.getHttpStatus());

        AwsException start = assertThrows(AwsException.class,
                () -> service.startDomainMaintenance("absent-domain", "REBOOT_NODE", null));
        assertEquals("ResourceNotFoundException", start.getErrorCode());
        assertEquals(409, start.getHttpStatus());
    }

    @Test
    void searchProcessRestartRestartsTheBackingContainer() {
        when(domainManager.tryStartDomain(any())).thenAnswer(inv -> {
            inv.<Domain>getArgument(0).setContainerId("container-1");
            return true;
        });
        when(domainManager.isReady(any())).thenReturn(true);
        service.createDomain("restart-domain", "OpenSearch_2.11", null, null, null, "us-east-1");
        service.pollReadiness();

        DomainMaintenance maintenance =
                service.startDomainMaintenance("restart-domain", "RESTART_SEARCH_PROCESS", null);

        assertEquals("COMPLETED", maintenance.getStatus());
        assertTrue(service.describeDomain("restart-domain").isProcessing(),
                "the restarted container is processing until the poller sees it ready");
        verify(domainManager, Mockito.times(2)).tryStartDomain(any());
        assertEquals(maintenance.getMaintenanceId(), service.getDomainMaintenanceStatus(
                "restart-domain", maintenance.getMaintenanceId()).getMaintenanceId());
    }

    @Test
    void restartWithoutReachableDockerIsRecordedAsFailed() {
        when(domainManager.tryStartDomain(any()))
                .thenAnswer(inv -> {
                    inv.<Domain>getArgument(0).setContainerId("container-1");
                    return true;
                })
                .thenReturn(false);
        service.createDomain("docker-gone", "OpenSearch_2.11", null, null, null, "us-east-1");

        DomainMaintenance maintenance =
                service.startDomainMaintenance("docker-gone", "REBOOT_NODE", null);

        assertEquals("FAILED", maintenance.getStatus());
        assertEquals(1, service.listDomainMaintenances("docker-gone", null, "FAILED").size());
    }

    @Test
    void unknownMaintenanceActionIsRejected() {
        when(osConfig.mock()).thenReturn(true);
        service.createDomain("action-domain", "OpenSearch_2.11", null, null, null, "us-east-1");

        AwsException e = assertThrows(AwsException.class,
                () -> service.startDomainMaintenance("action-domain", "SHUTDOWN", null));
        assertEquals("ValidationException", e.getErrorCode());
    }
}
