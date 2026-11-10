package io.floci.aws.services.redshiftserverless;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.model.Endpoint;
import io.floci.aws.services.redshiftserverless.model.Workgroup;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftServerlessEndpointsTest {

    private final RedshiftService redshift = mock(RedshiftService.class);

    private RedshiftServerlessEndpoints endpoints(String domain) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().redshiftServerless().endpointDomain()).thenReturn(Optional.ofNullable(domain));
        return new RedshiftServerlessEndpoints(redshift, config);
    }

    private static Workgroup workgroup(String name, int port) {
        Workgroup workgroup = new Workgroup();
        workgroup.setWorkgroupName(name);
        workgroup.setPort(port);
        return workgroup;
    }

    @Test
    void withoutADomainAllocateAdvertisesTheProxyPortFromTheSharedPool() {
        when(redshift.reserveProxyPort()).thenReturn(7101);
        when(redshift.advertisedEndpoint(7101)).thenReturn(new Endpoint("localhost", 7101));
        RedshiftServerlessEndpoints endpoints = endpoints(null);
        Workgroup wg = workgroup("wg", 5439);

        endpoints.allocate(wg, "000000000000", "us-east-1");

        assertFalse(endpoints.routed());
        assertEquals(7101, wg.getProxyPort());
        assertEquals("localhost", wg.getEndpoint().getAddress());
        assertEquals(7101, wg.getEndpoint().getPort());
    }

    @Test
    void underADomainTheEndpointIsAwsShapedOnTheWorkgroupPort() {
        when(redshift.reserveProxyPort()).thenReturn(7102);
        RedshiftServerlessEndpoints endpoints = endpoints("redshift-serverless.localhost.floci.io");
        Workgroup wg = workgroup("My-WG", 5439);

        endpoints.allocate(wg, "123456789012", "eu-west-1");

        assertTrue(endpoints.routed());
        assertEquals(7102, wg.getProxyPort());
        assertEquals("my-wg.123456789012.eu-west-1.redshift-serverless.localhost.floci.io",
                wg.getEndpoint().getAddress());
        assertEquals(5439, wg.getEndpoint().getPort());
    }

    @Test
    void aBlankDomainIsTreatedAsUnset() {
        assertFalse(endpoints(" ").routed());
    }

    @Test
    void restoreKeepsThePersistedProxyPortAndRebuildsTheAddressFromCurrentConfiguration() {
        when(redshift.reserveProxyPort(7105)).thenReturn(true);
        when(redshift.advertisedEndpoint(7105)).thenReturn(new Endpoint("new-host", 7105));
        Workgroup wg = workgroup("wg", 5439);
        wg.setEndpoint(new Endpoint("old-host", 7105));

        endpoints(null).restore(wg, "000000000000", "us-east-1");

        assertEquals("new-host", wg.getEndpoint().getAddress());
        assertEquals(7105, wg.getProxyPort());
        verify(redshift, never()).reserveProxyPort();
    }

    @Test
    void restoreAllocatesAFreshPortWhenThePersistedOneIsTaken() {
        when(redshift.reserveProxyPort(7105)).thenReturn(false);
        when(redshift.reserveProxyPort()).thenReturn(7110);
        when(redshift.advertisedEndpoint(7110)).thenReturn(new Endpoint("localhost", 7110));
        Workgroup wg = workgroup("wg", 5439);
        wg.setProxyPort(7105);

        endpoints(null).restore(wg, "000000000000", "us-east-1");

        assertEquals(7110, wg.getProxyPort());
        assertEquals(7110, wg.getEndpoint().getPort());
    }

    @Test
    void restoreOfAWorkgroupWithNoPortAllocatesOne() {
        when(redshift.reserveProxyPort()).thenReturn(7111);
        when(redshift.advertisedEndpoint(7111)).thenReturn(new Endpoint("localhost", 7111));
        Workgroup wg = workgroup("wg", 5439);

        endpoints(null).restore(wg, "000000000000", "us-east-1");

        assertEquals(7111, wg.getProxyPort());
    }

    @Test
    void releaseReturnsTheProxyPortAndToleratesAWorkgroupWithoutOne() {
        RedshiftServerlessEndpoints endpoints = endpoints(null);
        Workgroup wg = workgroup("wg", 5439);
        wg.setProxyPort(7120);

        endpoints.release(wg);
        endpoints.release(workgroup("none", 5439));

        verify(redshift).releaseProxyPort(7120);
    }
}
