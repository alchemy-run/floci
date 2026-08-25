package io.github.hectorvent.floci.services.opensearch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenSearchRoutingFilterTest {

    @Test
    void extractDomainName_fromAwsSearchHost() {
        assertEquals("mydomain", OpenSearchRoutingFilter.extractDomainName(
                "search-mydomain-a1b2c3.us-east-1.es.amazonaws.com"));
        assertEquals("dp-songs", OpenSearchRoutingFilter.extractDomainName(
                "search-dp-songs-ffffff.us-west-2.es.amazonaws.com:443"));
        assertEquals("my-domain", OpenSearchRoutingFilter.extractDomainName(
                "search-my-domain-0abcde.us-east-1.aos.amazonaws.com"));
    }

    @Test
    void extractDomainName_rejectsManagementAndUnrelatedHosts() {
        assertNull(OpenSearchRoutingFilter.extractDomainName("es.us-east-1.amazonaws.com"));
        assertNull(OpenSearchRoutingFilter.extractDomainName("localhost:4566"));
        assertNull(OpenSearchRoutingFilter.extractDomainName("aps-workspaces.us-east-1.amazonaws.com"));
        assertNull(OpenSearchRoutingFilter.extractDomainName(null));
    }

    @Test
    void rewritePath_prefixesDomain() {
        assertEquals("/_floci/opensearch/songs-domain/songs/_doc/1",
                OpenSearchRoutingFilter.rewritePath("songs-domain", "/songs/_doc/1"));
        assertEquals("/_floci/opensearch/songs-domain/_cluster/health",
                OpenSearchRoutingFilter.rewritePath("songs-domain", "/_cluster/health"));
        assertEquals("/_floci/opensearch/songs-domain/",
                OpenSearchRoutingFilter.rewritePath("songs-domain", "/"));
    }

    @Test
    void alreadyPathStyle() {
        assertTrue(OpenSearchRoutingFilter.alreadyPathStyle("/_floci/opensearch/d/songs/_doc/1"));
        assertFalse(OpenSearchRoutingFilter.alreadyPathStyle("/songs/_doc/1"));
        assertFalse(OpenSearchRoutingFilter.alreadyPathStyle(null));
    }

    @Test
    void isDataPlaneHost() {
        assertTrue(OpenSearchRoutingFilter.isDataPlaneHost(
                "search-mydomain-a1b2c3.us-east-1.es.amazonaws.com"));
        assertFalse(OpenSearchRoutingFilter.isDataPlaneHost("es.us-east-1.amazonaws.com"));
    }
}
