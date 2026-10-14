package io.github.hectorvent.floci.services.opensearch;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenSearchDataPlaneRoutingFilterTest {

    private static final String DOMAIN_HOST = "logs.us-east-1.es.localhost.floci.io:4566";

    @Test
    void http2RequestKeepsItsAuthorityAsTheSignedHostHeader() {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        ContainerRequestContext ctx = request("http://" + DOMAIN_HOST + "/_cluster/health", null, headers);

        new OpenSearchDataPlaneRoutingFilter().filter(ctx);

        assertEquals(DOMAIN_HOST, headers.getFirst("Host"));
        ArgumentCaptor<URI> rewritten = ArgumentCaptor.forClass(URI.class);
        verify(ctx).setRequestUri(rewritten.capture());
        assertEquals("/_floci/opensearch-domain/us-east-1/logs/_cluster/health", rewritten.getValue().getRawPath());
        assertEquals("localhost", rewritten.getValue().getHost());
    }

    @Test
    void http1HostHeaderIsLeftAlone() {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.putSingle("Host", DOMAIN_HOST);
        ContainerRequestContext ctx = request("http://localhost:4566/_cluster/health", DOMAIN_HOST, headers);

        new OpenSearchDataPlaneRoutingFilter().filter(ctx);

        assertEquals(DOMAIN_HOST, headers.getFirst("Host"));
        verify(ctx).setRequestUri(any());
    }

    @Test
    void otherHostsAreNotRouted() {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        ContainerRequestContext ctx = request("http://localhost:4566/", null, headers);

        new OpenSearchDataPlaneRoutingFilter().filter(ctx);

        assertNull(headers.getFirst("Host"));
        verify(ctx, never()).setRequestUri(any());
    }

    private static ContainerRequestContext request(String uri, String hostHeader,
                                                   MultivaluedMap<String, String> headers) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(URI.create(uri));
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(ctx.getHeaderString("Host")).thenReturn(hostHeader);
        when(ctx.getHeaders()).thenReturn(headers);
        return ctx;
    }
}
