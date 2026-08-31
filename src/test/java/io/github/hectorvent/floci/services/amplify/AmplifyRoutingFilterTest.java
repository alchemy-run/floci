package io.github.hectorvent.floci.services.amplify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmplifyRoutingFilterTest {

    private static final String AMPLIFY_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/amplify/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesAmplifyCredentialScope() {
        assertTrue(AmplifyRoutingFilter.isAmplify(AMPLIFY_AUTH));
        assertFalse(AmplifyRoutingFilter.isAmplify(S3_AUTH));
        assertFalse(AmplifyRoutingFilter.isAmplify(null));
        assertFalse(AmplifyRoutingFilter.isAmplify(""));
    }

    @Test
    void prefixesNestedAppAndArtifactPathsAndStripsTrailingSlash() {
        assertEquals("/aws-amplify/apps", AmplifyRoutingFilter.rewritePath("/apps"));
        assertEquals("/aws-amplify/apps", AmplifyRoutingFilter.rewritePath("/apps/"));
        assertEquals("/aws-amplify/apps/dabc/branches/main/deployments",
                AmplifyRoutingFilter.rewritePath("/apps/dabc/branches/main/deployments"));
        assertEquals("/aws-amplify/apps/dabc/branches/main/jobs",
                AmplifyRoutingFilter.rewritePath("/apps/dabc/branches/main/jobs"));
        assertEquals("/aws-amplify/apps/dabc/accesslogs",
                AmplifyRoutingFilter.rewritePath("/apps/dabc/accesslogs"));
        assertEquals("/aws-amplify/artifacts/art-1",
                AmplifyRoutingFilter.rewritePath("/artifacts/art-1"));
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals(
                "/tags/arn:aws:amplify:us-east-1:000000000000:apps/dabc",
                AmplifyRoutingFilter.rewritePath(
                        "/tags/arn:aws:amplify:us-east-1:000000000000:apps/dabc"));
        assertEquals("/aws-amplify/apps/dabc",
                AmplifyRoutingFilter.rewritePath("/aws-amplify/apps/dabc"));
    }
}
