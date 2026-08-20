package io.github.hectorvent.floci.services.appsync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
