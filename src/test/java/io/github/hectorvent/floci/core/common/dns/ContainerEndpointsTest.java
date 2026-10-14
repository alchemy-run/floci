package io.github.hectorvent.floci.core.common.dns;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerEndpointsTest {

    private EmbeddedDnsServer dnsServer;
    private SourceNetworkHelper sourceHelper;
    private ContainerEndpoints endpoints;

    @BeforeEach
    void setUp() {
        dnsServer = mock(EmbeddedDnsServer.class);
        sourceHelper = mock(SourceNetworkHelper.class);
        endpoints = new ContainerEndpoints(dnsServer, sourceHelper);
    }

    @Test
    void fromSourceAHostListenerIsRelayedThroughTheHelper() {
        when(dnsServer.isSourceMode()).thenReturn(true);

        endpoints.publishHostPort(27017);
        endpoints.withdrawHostPort(27017);

        verify(sourceHelper).forwardPort(27017);
        verify(sourceHelper).releaseForwardedPort(27017);
    }

    @Test
    void inDockerContainersAlreadyReachFlociOnEveryPort() {
        when(dnsServer.isSourceMode()).thenReturn(false);

        endpoints.publishHostPort(27017);
        endpoints.withdrawHostPort(27017);

        verify(sourceHelper, never()).forwardPort(anyInt());
        verify(sourceHelper, never()).releaseForwardedPort(anyInt());
    }

    @Test
    void aRelayFailureDoesNotFailTheResourceOperation() {
        when(dnsServer.isSourceMode()).thenReturn(true);
        doThrow(new IllegalStateException("helper gone")).when(sourceHelper).forwardPort(9098);

        assertDoesNotThrow(() -> endpoints.publishHostPort(9098));
    }

    @Test
    void hostnameRoutesGoToTheEmbeddedDns() {
        endpoints.routeToContainer("master.a.abc123.use1.cache.localhost.floci.io", "172.18.0.5");
        endpoints.refuse("master.b.abc123.use1.cache.localhost.floci.io");
        endpoints.release("master.a.abc123.use1.cache.localhost.floci.io");
        endpoints.release(null);

        verify(dnsServer).routeHost("master.a.abc123.use1.cache.localhost.floci.io", "172.18.0.5");
        verify(dnsServer).refuseHost("master.b.abc123.use1.cache.localhost.floci.io");
        verify(dnsServer).releaseHost("master.a.abc123.use1.cache.localhost.floci.io");
    }

    private static final String SECURE = "master.secure.abc123.use1.cache.localhost.floci.io";

    @Test
    void fromSourceARelayedHostnameResolvesToItsOwnRelayToTheHost() {
        EndpointRelays relays = mock(EndpointRelays.class);
        ContainerEndpoints relaying = new ContainerEndpoints(dnsServer, sourceHelper, relays);
        when(dnsServer.isSourceMode()).thenReturn(true);
        when(dnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.2"));
        when(relays.start(SECURE, 6379, SourceNetworkHelper.HOST_GATEWAY, 6380)).thenReturn("172.18.0.9");

        assertTrue(relaying.relayToFloci(SECURE, 6379, 6380));
        verify(dnsServer).routeHost(SECURE, "172.18.0.9");

        relaying.release(SECURE);
        verify(dnsServer).releaseHost(SECURE);
        verify(relays).stop(SECURE);
    }

    @Test
    void inDockerARelayTargetsFlocisOwnAddress() {
        EndpointRelays relays = mock(EndpointRelays.class);
        ContainerEndpoints relaying = new ContainerEndpoints(dnsServer, sourceHelper, relays);
        when(dnsServer.isSourceMode()).thenReturn(false);
        when(dnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.2"));
        when(relays.start(SECURE, 6379, "172.18.0.2", 6380)).thenReturn("172.18.0.9");

        assertTrue(relaying.relayToFloci(SECURE, 6379, 6380));
        verify(dnsServer).routeHost(SECURE, "172.18.0.9");
    }

    @Test
    void withoutContainerDnsNothingIsRelayed() {
        EndpointRelays relays = mock(EndpointRelays.class);
        ContainerEndpoints relaying = new ContainerEndpoints(dnsServer, sourceHelper, relays);
        when(dnsServer.isSourceMode()).thenReturn(true);
        when(dnsServer.getServerIp()).thenReturn(Optional.empty());

        assertFalse(relaying.relayToFloci(SECURE, 6379, 6380));
        verify(relays, never()).start(anyString(), anyInt(), anyString(), anyInt());
    }

    @Test
    void reroutingAHostnameStopsItsRelay() {
        EndpointRelays relays = mock(EndpointRelays.class);
        ContainerEndpoints relaying = new ContainerEndpoints(dnsServer, sourceHelper, relays);

        relaying.routeToContainer(SECURE, "172.18.0.5");
        relaying.refuse(SECURE);

        verify(relays, org.mockito.Mockito.times(2)).stop(SECURE);
    }

    @Test
    void relayScriptListensOnTheEndpointPortAndRelaysToTheTarget() {
        assertEquals("exec socat TCP4-LISTEN:6379,reuseaddr,fork TCP4:host.docker.internal:6380",
                EndpointRelays.relayScript(6379, "host.docker.internal", 6380));
        assertThrows(IllegalArgumentException.class, () -> EndpointRelays.relayScript(6379, "a;rm -rf /", 6380));
        assertThrows(IllegalArgumentException.class, () -> EndpointRelays.relayScript(0, "10.0.0.1", 6380));
        assertEquals(100, EndpointRelays.containerNameOf("x".repeat(150)).length());
    }
}
