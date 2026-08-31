package io.github.hectorvent.floci.services.timestream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimestreamDiscoveryTlsTest {

    @Test
    void replacePort_flociDnsKeepsHostname() {
        assertEquals("localhost.floci.io:9443",
                TimestreamDiscoveryTls.replacePort("localhost.floci.io:4566", 9443));
    }

    @Test
    void replacePort_dockerInternalKeepsHostname() {
        assertEquals("host.docker.internal:9443",
                TimestreamDiscoveryTls.replacePort("host.docker.internal:4566", 9443));
    }

    @Test
    void replacePort_bareHostGetsPort() {
        assertEquals("127.0.0.1:9443",
                TimestreamDiscoveryTls.replacePort("127.0.0.1", 9443));
        assertEquals("localhost:9443",
                TimestreamDiscoveryTls.replacePort("localhost", 9443));
    }
}
