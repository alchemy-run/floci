package io.github.hectorvent.floci.services.securityhub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityHubRoutingFilterTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/securityhub/aws4_request";

    @Test
    void identifiesSecurityHubScope() {
        assertTrue(SecurityHubRoutingFilter.isSecurityHub(AUTH));
        assertFalse(SecurityHubRoutingFilter.isSecurityHub(
                "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request"));
        assertFalse(SecurityHubRoutingFilter.isSecurityHub(null));
        assertFalse(SecurityHubRoutingFilter.isSecurityHub(""));
    }

    @Test
    void prefixesPublicPathsAndLeavesTagsAlone() {
        assertEquals("/securityhub/accounts", SecurityHubRoutingFilter.rewritePath("/accounts"));
        assertEquals("/securityhub/findings/import", SecurityHubRoutingFilter.rewritePath("/findings/import"));
        assertEquals("/securityhub/accounts", SecurityHubRoutingFilter.rewritePath("/securityhub/accounts"));
        assertEquals("/tags/arn:aws:securityhub:us-east-1:1:hub/default",
                SecurityHubRoutingFilter.rewritePath("/tags/arn:aws:securityhub:us-east-1:1:hub/default"));
    }

    @Test
    void leavesFunctionUrlInvocationsAlone() {
        assertTrue(SecurityHubRoutingFilter.isLambdaUrlHost(
                "4a2641d4e1713560ada2ee703a47958f.lambda-url.us-east-1.localhost:4566"));
        assertFalse(SecurityHubRoutingFilter.isLambdaUrlHost("localhost:4566"));
        assertEquals(
                "/lambda-url/4a2641d4e1713560ada2ee703a47958f/bindings",
                SecurityHubRoutingFilter.rewritePath(
                        "/lambda-url/4a2641d4e1713560ada2ee703a47958f/bindings"));
    }
}
