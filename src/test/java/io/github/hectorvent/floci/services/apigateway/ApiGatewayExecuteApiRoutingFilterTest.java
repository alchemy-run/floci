package io.github.hectorvent.floci.services.apigateway;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiGatewayExecuteApiRoutingFilterTest {

    @Test
    void resolveHostFallsBackToUriAuthorityWhenHostHeaderMissing() {
        assertEquals(
                "abc123.execute-api.us-east-1.amazonaws.com:4566",
                ApiGatewayExecuteApiRoutingFilter.resolveHost(
                        null,
                        URI.create("https://abc123.execute-api.us-east-1.amazonaws.com:4566/test/@connections/xyz")));
        assertEquals(
                "abc123.execute-api.us-east-1.amazonaws.com",
                ApiGatewayExecuteApiRoutingFilter.resolveHost(
                        "abc123.execute-api.us-east-1.amazonaws.com",
                        URI.create("https://ignored.example/test")));
    }

    @Test
    void extractsApiIdAndRegionFromExecuteApiHost() {
        assertEquals("abc123", ApiGatewayExecuteApiRoutingFilter.extractApiId(
                "abc123.execute-api.us-east-1.amazonaws.com"));
        assertEquals("us-east-1", ApiGatewayExecuteApiRoutingFilter.extractRegion(
                "abc123.execute-api.us-east-1.amazonaws.com:443"));
        assertNull(ApiGatewayExecuteApiRoutingFilter.extractApiId("localhost:4566"));
        assertNull(ApiGatewayExecuteApiRoutingFilter.extractApiId(
                "bucket.s3-website-us-east-1.amazonaws.com"));
    }

    @Test
    void buildsExecuteApiPath() {
        assertEquals("/execute-api/abc/test/",
                ApiGatewayExecuteApiRoutingFilter.executeApiPath("abc", "test", ""));
        assertEquals("/execute-api/abc/test/items",
                ApiGatewayExecuteApiRoutingFilter.executeApiPath("abc", "test", "/items"));
        assertEquals("/execute-api/abc/$default/echo",
                ApiGatewayExecuteApiRoutingFilter.executeApiPath("abc", "$default", "echo"));
    }

    @Test
    void splitsStageAndRemainingPath() {
        assertEquals("test", ApiGatewayExecuteApiRoutingFilter.firstSegment("/test/items"));
        assertEquals("/items", ApiGatewayExecuteApiRoutingFilter.remainingAfterFirstSegment("/test/items"));
        assertEquals("/", ApiGatewayExecuteApiRoutingFilter.remainingAfterFirstSegment("/test/"));
        assertNull(ApiGatewayExecuteApiRoutingFilter.firstSegment("/"));

        assertEquals("echo", ApiGatewayExecuteApiRoutingFilter.remainingAfterStage(
                "/echo", "$default", "HTTP"));
        assertEquals("/items", ApiGatewayExecuteApiRoutingFilter.remainingAfterStage(
                "/prod/items", "prod", "HTTP"));
    }

    @Test
    void ignoresAlreadyPathStyleRequests() {
        assertTrue(ApiGatewayExecuteApiRoutingFilter.alreadyPathStyle("/execute-api/abc/test/hello"));
        assertTrue(ApiGatewayExecuteApiRoutingFilter.alreadyPathStyle("/restapis/abc/test/_user_request_/hello"));
        assertFalse(ApiGatewayExecuteApiRoutingFilter.alreadyPathStyle("/test/hello"));
    }
}
