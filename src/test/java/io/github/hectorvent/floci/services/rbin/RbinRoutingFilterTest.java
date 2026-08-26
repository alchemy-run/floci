package io.github.hectorvent.floci.services.rbin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RbinRoutingFilterTest {

    private static final String RBIN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/rbin/aws4_request";
    private static final String IOT_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iot/aws4_request";

    @Test
    void recognizesRbinCredentialScope() {
        assertTrue(RbinRoutingFilter.isRbin(RBIN_AUTH));
        assertFalse(RbinRoutingFilter.isRbin(IOT_AUTH));
        assertFalse(RbinRoutingFilter.isRbin(null));
        assertFalse(RbinRoutingFilter.isRbin(""));
    }

    @Test
    void prefixesRulePathsAndStripsTrailingSlash() {
        assertEquals("/aws-rbin/rules", RbinRoutingFilter.rewritePath("/rules"));
        assertEquals("/aws-rbin/rules", RbinRoutingFilter.rewritePath("/rules/"));
        assertEquals("/aws-rbin/list-rules", RbinRoutingFilter.rewritePath("/list-rules"));
        assertEquals("/aws-rbin/rules/abc-123",
                RbinRoutingFilter.rewritePath("/rules/abc-123"));
        assertEquals("/aws-rbin/rules/abc-123/lock",
                RbinRoutingFilter.rewritePath("/rules/abc-123/lock"));
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals("/tags/arn:aws:rbin:us-east-1:000000000000:rule/abc-123",
                RbinRoutingFilter.rewritePath(
                        "/tags/arn:aws:rbin:us-east-1:000000000000:rule/abc-123"));
        assertEquals("/aws-rbin/rules/abc-123",
                RbinRoutingFilter.rewritePath("/aws-rbin/rules/abc-123"));
    }

    @Test
    void leavesFunctionUrlPathsAlone() {
        assertEquals("/lambda-url/abc123/bindings",
                RbinRoutingFilter.rewritePath("/lambda-url/abc123/bindings"));
        assertEquals("/lambda-url/abc123/bindings/",
                RbinRoutingFilter.rewritePath("/lambda-url/abc123/bindings/"));
        assertTrue(RbinRoutingFilter.isLambdaUrlHost(
                "abc123.lambda-url.us-east-1.localhost:4566"));
        assertFalse(RbinRoutingFilter.isLambdaUrlHost("localhost:4566"));
        assertFalse(RbinRoutingFilter.isLambdaUrlHost(null));
    }
}
