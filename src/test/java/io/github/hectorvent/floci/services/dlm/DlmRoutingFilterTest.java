package io.github.hectorvent.floci.services.dlm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DlmRoutingFilterTest {

    private static final String DLM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/dlm/aws4_request";
    private static final String IOT_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iot/aws4_request";

    @Test
    void recognizesDlmCredentialScope() {
        assertTrue(DlmRoutingFilter.isDlm(DLM_AUTH));
        assertFalse(DlmRoutingFilter.isDlm(IOT_AUTH));
        assertFalse(DlmRoutingFilter.isDlm(null));
        assertFalse(DlmRoutingFilter.isDlm(""));
    }

    @Test
    void prefixesPolicyPathsAndStripsTrailingSlash() {
        assertEquals("/aws-dlm/policies", DlmRoutingFilter.rewritePath("/policies"));
        assertEquals("/aws-dlm/policies", DlmRoutingFilter.rewritePath("/policies/"));
        assertEquals("/aws-dlm/policies/policy-abc",
                DlmRoutingFilter.rewritePath("/policies/policy-abc"));
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals("/tags/arn:aws:dlm:us-east-1:000000000000:policy/policy-abc",
                DlmRoutingFilter.rewritePath(
                        "/tags/arn:aws:dlm:us-east-1:000000000000:policy/policy-abc"));
        assertEquals("/aws-dlm/policies/policy-abc",
                DlmRoutingFilter.rewritePath("/aws-dlm/policies/policy-abc"));
    }
}
