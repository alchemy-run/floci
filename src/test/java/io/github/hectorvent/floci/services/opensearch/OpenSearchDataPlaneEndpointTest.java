package io.github.hectorvent.floci.services.opensearch;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The endpoint DescribeDomain reports must be a bare host[:port] (clients prepend https://), must
 * resolve to Floci from the host and from Lambda containers, and must route back to its domain.
 */
class OpenSearchDataPlaneEndpointTest {

    @Test
    void loopbackGatewayPublishesTheFlociWildcardDnsHostWithItsPort() {
        assertEquals("songs.us-east-1.es.localhost.floci.io:4566",
                OpenSearchDataPlaneEndpoint.publicEndpoint("songs", "us-east-1", "https://localhost:4566"));
        assertEquals("songs.eu-west-1.es.localhost.floci.io:4566",
                OpenSearchDataPlaneEndpoint.publicEndpoint("songs", "eu-west-1", "http://127.0.0.1:4566"));
    }

    @Test
    void namedGatewayHostIsUsedAsTheSuffix() {
        assertEquals("songs.us-east-1.es.floci:4566",
                OpenSearchDataPlaneEndpoint.publicEndpoint("songs", "us-east-1", "http://floci:4566"));
        assertEquals("songs.us-east-1.es.floci.example.com",
                OpenSearchDataPlaneEndpoint.publicEndpoint("songs", "us-east-1", "https://floci.example.com"));
    }

    @Test
    void certificateWildcardCoversEveryDomainInTheRegion() {
        assertEquals("*.us-east-1.es.localhost.floci.io",
                OpenSearchDataPlaneEndpoint.certificateWildcard("us-east-1", "https://localhost:4566"));
    }

    @Test
    void parsesThePublishedHostBackToItsDomain() {
        String endpoint = OpenSearchDataPlaneEndpoint.publicEndpoint(
                "data-plane-1", "us-east-1", "https://localhost:4566");
        assertEquals(Optional.of(new OpenSearchDataPlaneEndpoint.Target("data-plane-1", "us-east-1")),
                OpenSearchDataPlaneEndpoint.parse(endpoint));
        assertEquals(Optional.of(new OpenSearchDataPlaneEndpoint.Target("logs", "ap-southeast-2")),
                OpenSearchDataPlaneEndpoint.parse("LOGS.ap-southeast-2.es.localhost.floci.io"));
    }

    @Test
    void doesNotClaimOtherHosts() {
        assertTrue(OpenSearchDataPlaneEndpoint.parse("localhost:4566").isEmpty());
        assertTrue(OpenSearchDataPlaneEndpoint.parse("es.us-east-1.amazonaws.com").isEmpty());
        assertTrue(OpenSearchDataPlaneEndpoint.parse(
                "search-songs-abc123.us-east-1.es.amazonaws.com").isEmpty());
        assertTrue(OpenSearchDataPlaneEndpoint.parse("my-bucket.localhost.floci.io:4566").isEmpty());
        assertTrue(OpenSearchDataPlaneEndpoint.parse("songs.not-a-region.es.localhost.floci.io").isEmpty());
        assertTrue(OpenSearchDataPlaneEndpoint.parse("abc.lambda-url.us-east-1.localhost:4566").isEmpty());
        assertTrue(OpenSearchDataPlaneEndpoint.parse(null).isEmpty());
    }

    @Test
    void routingKeepsTheRawClientPathAndTheControllerRecoversIt() {
        OpenSearchDataPlaneEndpoint.Target target = new OpenSearchDataPlaneEndpoint.Target("songs", "us-east-1");
        String proxy = OpenSearchDataPlaneRoutingFilter.proxyPath(target, "/songs/_doc/a%2Fb");
        assertEquals("/_floci/opensearch-domain/us-east-1/songs/songs/_doc/a%2Fb", proxy);
        assertEquals("/songs/_doc/a%2Fb", OpenSearchDataPlaneController.clientPath(proxy, "us-east-1", "songs"));
        assertEquals("/", OpenSearchDataPlaneController.clientPath(
                OpenSearchDataPlaneRoutingFilter.proxyPath(target, "/"), "us-east-1", "songs"));
    }
}
