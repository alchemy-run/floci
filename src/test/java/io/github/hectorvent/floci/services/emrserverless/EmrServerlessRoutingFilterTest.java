package io.github.hectorvent.floci.services.emrserverless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmrServerlessRoutingFilterTest {

    private static final String EMRS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/emr-serverless/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesEmrServerlessCredentialScope() {
        assertTrue(EmrServerlessRoutingFilter.isEmrServerless(EMRS_AUTH));
        assertFalse(EmrServerlessRoutingFilter.isEmrServerless(S3_AUTH));
        assertFalse(EmrServerlessRoutingFilter.isEmrServerless(null));
        assertFalse(EmrServerlessRoutingFilter.isEmrServerless(""));
    }

    @Test
    void prefixesApplicationPathsAndStripsTrailingSlash() {
        assertEquals("/aws-emr-serverless/applications",
                EmrServerlessRoutingFilter.rewritePath("/applications"));
        assertEquals("/aws-emr-serverless/applications",
                EmrServerlessRoutingFilter.rewritePath("/applications/"));
        assertEquals("/aws-emr-serverless/applications/00abcdefabcdef01/dashboard",
                EmrServerlessRoutingFilter.rewritePath("/applications/00abcdefabcdef01/dashboard"));
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals(
                "/tags/arn:aws:emr-serverless:us-east-1:000000000000:/applications/abc",
                EmrServerlessRoutingFilter.rewritePath(
                        "/tags/arn:aws:emr-serverless:us-east-1:000000000000:/applications/abc"));
        assertEquals("/aws-emr-serverless/applications",
                EmrServerlessRoutingFilter.rewritePath("/aws-emr-serverless/applications"));
    }
}
