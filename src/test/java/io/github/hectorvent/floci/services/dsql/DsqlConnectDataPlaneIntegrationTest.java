package io.github.hectorvent.floci.services.dsql;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.dsql.proxy.DsqlDataPlane;
import io.github.hectorvent.floci.services.dsql.proxy.DsqlSigV4Validator;
import io.github.hectorvent.floci.services.dsql.proxy.DsqlTls;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Alchemy {@code DSQL.Connect} {@code /health} failed with
 * {@code Backend database authentication failed} when the IAM proxy's
 * per-start random password no longer matched {@code floci-rds-dsql-shared}.
 */
class DsqlConnectDataPlaneIntegrationTest {

    @Test
    void prepareSharedEngineDropsLeftoverVolumeBeforeInitdb() {
        RdsContainerManager containers = mock(RdsContainerManager.class);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        DsqlDataPlane plane = new DsqlDataPlane(
                containers, lifecycle, config, mock(DsqlSigV4Validator.class), mock(DsqlTls.class));

        plane.prepareSharedEngine();

        InOrder order = inOrder(lifecycle, containers);
        order.verify(lifecycle).removeIfExists("floci-rds-dsql-shared");
        order.verify(containers).removeVolume("dsql-shared", "dsql-shared");
        verifyNoMoreInteractions(lifecycle, containers);
        assertEquals("floci-dsql-admin", DsqlDataPlane.MASTER_PASSWORD);
        assertEquals("admin", DsqlDataPlane.MASTER_USERNAME);
    }
}
