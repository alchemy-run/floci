package io.github.hectorvent.floci.services.appsync;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppSyncRoutingFilterTest {

    @Test
    void extractsApiIdFromAppsyncApiHost() {
        assertEquals("747c6aee7d754b748a59438a17", AppSyncRoutingFilter.extractApiId(
                "747c6aee7d754b748a59438a17.appsync-api.us-east-1.amazonaws.com"));
        assertEquals("abc123", AppSyncRoutingFilter.extractApiId(
                "abc123.appsync-api.us-west-2.amazonaws.com:443"));
        assertNull(AppSyncRoutingFilter.extractApiId("localhost:4566"));
        assertNull(AppSyncRoutingFilter.extractApiId("appsync.us-east-1.amazonaws.com"));
        assertNull(AppSyncRoutingFilter.extractApiId("abc123.execute-api.us-east-1.amazonaws.com"));
        assertNull(AppSyncRoutingFilter.extractApiId(null));
    }

    @Test
    void buildsGraphqlPath() {
        assertEquals("/v1/apis/abc/graphql", AppSyncRoutingFilter.graphqlPath("abc", "/graphql"));
        assertEquals("/v1/apis/abc/graphql", AppSyncRoutingFilter.graphqlPath("abc", "/"));
        assertEquals("/v1/apis/abc/graphql", AppSyncRoutingFilter.graphqlPath("abc", null));
        assertEquals("/v1/apis/abc/graphql", AppSyncRoutingFilter.graphqlPath("abc", "graphql"));
    }

    @Test
    void ignoresAlreadyPathStyleRequests() {
        assertTrue(AppSyncRoutingFilter.alreadyPathStyle("/v1/apis/abc/graphql"));
        assertFalse(AppSyncRoutingFilter.alreadyPathStyle("/graphql"));
        assertFalse(AppSyncRoutingFilter.alreadyPathStyle(null));
    }

    @Test
    void routesHttp2RequestsByAuthorityWhenHostHeaderIsAbsent() throws Exception {
        // HTTP/2 over TLS carries the advertised host only in :authority.
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(request.getHeaderString("Host")).thenReturn(null);
        when(request.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getRequestUri()).thenReturn(
                URI.create("https://abc123.appsync-api.us-east-1.localhost.floci.io:4566/graphql"));

        new AppSyncRoutingFilter().filter(request);

        ArgumentCaptor<URI> rewritten = ArgumentCaptor.forClass(URI.class);
        verify(request).setRequestUri(rewritten.capture());
        assertEquals("/v1/apis/abc123/graphql", rewritten.getValue().getPath());
    }
}
