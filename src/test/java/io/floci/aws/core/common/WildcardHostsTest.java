package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WildcardHostsTest {

    @Test
    void loopbackAndFlociDnsNamesTakeResourceSubdomains() {
        assertTrue(WildcardHosts.available("http://localhost:4566"));
        assertTrue(WildcardHosts.available("https://127.0.0.1:4566/"));
        // A Floci configured with hostname: localhost.floci.io, whose subdomains all resolve to it.
        assertTrue(WildcardHosts.available("https://localhost.floci.io:4566"));
        assertFalse(WildcardHosts.available("http://floci.internal:4566"));
        assertFalse(WildcardHosts.available("http://localhost:4566/prefix"));
    }
}
