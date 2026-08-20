package io.github.hectorvent.floci.services.lambda.microvm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MicrovmEndpointRoutingFilterTest {

    @Test
    void extractsMicrovmIdFromEndpointHostname() {
        assertEquals("mvm-0123abcd",
                MicrovmEndpointRoutingFilter.extractMicrovmId(
                        "mvm-0123abcd.lambda-microvm.us-east-1.localhost.floci.io"));
    }

    @Test
    void stripsPortFromHostHeader() {
        assertEquals("mvm-0123abcd",
                MicrovmEndpointRoutingFilter.extractMicrovmId(
                        "mvm-0123abcd.lambda-microvm.us-east-1.localhost.floci.io:443"));
    }

    @Test
    void ignoresNonMicrovmHosts() {
        assertNull(MicrovmEndpointRoutingFilter.extractMicrovmId("localhost"));
        assertNull(MicrovmEndpointRoutingFilter.extractMicrovmId("localhost:4566"));
        assertNull(MicrovmEndpointRoutingFilter.extractMicrovmId(
                "my-fn.lambda-url.us-east-1.localhost.floci.io"));
        assertNull(MicrovmEndpointRoutingFilter.extractMicrovmId(null));
        assertNull(MicrovmEndpointRoutingFilter.extractMicrovmId(""));
    }

    @Test
    void rejectsMultiLabelPrefixes() {
        // Only `<id>.lambda-microvm.<region>...` is a MicroVM endpoint; a
        // deeper subdomain is not ours to route.
        assertNull(MicrovmEndpointRoutingFilter.extractMicrovmId(
                "a.b.lambda-microvm.us-east-1.localhost.floci.io"));
    }
}
