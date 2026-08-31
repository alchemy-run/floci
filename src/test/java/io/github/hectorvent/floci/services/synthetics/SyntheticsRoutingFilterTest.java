package io.github.hectorvent.floci.services.synthetics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticsRoutingFilterTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/synthetics/aws4_request";

    @Test
    void identifiesSyntheticsScope() {
        assertTrue(SyntheticsRoutingFilter.isSynthetics(AUTH));
        assertFalse(SyntheticsRoutingFilter.isSynthetics(
                "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request"));
        assertFalse(SyntheticsRoutingFilter.isSynthetics(null));
        assertFalse(SyntheticsRoutingFilter.isSynthetics(""));
    }

    @Test
    void prefixesPublicPathsAndLeavesTagsAlone() {
        assertEquals("/synthetics/canary", SyntheticsRoutingFilter.rewritePath("/canary"));
        assertEquals("/synthetics/canary/bindings-canary",
                SyntheticsRoutingFilter.rewritePath("/canary/bindings-canary"));
        assertEquals("/synthetics/canaries/last-run",
                SyntheticsRoutingFilter.rewritePath("/canaries/last-run"));
        assertEquals("/synthetics/canary", SyntheticsRoutingFilter.rewritePath("/synthetics/canary"));
        assertEquals("/tags/arn:aws:synthetics:us-east-1:1:canary:x",
                SyntheticsRoutingFilter.rewritePath("/tags/arn:aws:synthetics:us-east-1:1:canary:x"));
    }

    @Test
    void leavesFunctionUrlInvocationsAlone() {
        assertTrue(SyntheticsRoutingFilter.isLambdaUrlHost(
                "deadbeefdeadbeefdeadbeefdeadbeef.lambda-url.us-east-1.localhost:4566"));
        assertFalse(SyntheticsRoutingFilter.isLambdaUrlHost("localhost:4566"));
        assertEquals(
                "/lambda-url/deadbeefdeadbeefdeadbeefdeadbeef/bindings",
                SyntheticsRoutingFilter.rewritePath(
                        "/lambda-url/deadbeefdeadbeefdeadbeefdeadbeef/bindings"));
        assertEquals(
                "/lambda-url/deadbeefdeadbeefdeadbeefdeadbeef/canary",
                SyntheticsRoutingFilter.rewritePath(
                        "/lambda-url/deadbeefdeadbeefdeadbeefdeadbeef/canary"));
    }
}
