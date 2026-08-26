package io.github.hectorvent.floci.services.polly;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollyRoutingFilterTest {

    private static final String POLLY_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/polly/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesPollyCredentialScope() {
        assertTrue(PollyRoutingFilter.isPolly(POLLY_AUTH));
        assertFalse(PollyRoutingFilter.isPolly(S3_AUTH));
        assertFalse(PollyRoutingFilter.isPolly(null));
        assertFalse(PollyRoutingFilter.isPolly(""));
    }

    @Test
    void prefixesLexiconPathsOntoTheInternalPrefix() {
        assertEquals("/aws-polly/v1/lexicons", PollyRoutingFilter.rewritePath("/v1/lexicons"));
        assertEquals("/aws-polly/v1/lexicons/demo", PollyRoutingFilter.rewritePath("/v1/lexicons/demo"));
        assertEquals("/aws-polly/v1/lexicons/demo", PollyRoutingFilter.rewritePath("/v1/lexicons/demo/"));
    }

    @Test
    void leavesAlreadyPrefixedPathsAlone() {
        assertEquals("/aws-polly/v1/lexicons/demo",
                PollyRoutingFilter.rewritePath("/aws-polly/v1/lexicons/demo"));
    }
}
