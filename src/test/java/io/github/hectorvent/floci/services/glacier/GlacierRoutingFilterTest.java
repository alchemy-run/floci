package io.github.hectorvent.floci.services.glacier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlacierRoutingFilterTest {

    private static final String GLACIER_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/glacier/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesGlacierCredentialScope() {
        assertTrue(GlacierRoutingFilter.isGlacier(GLACIER_AUTH, null));
        assertFalse(GlacierRoutingFilter.isGlacier(S3_AUTH, null));
        assertFalse(GlacierRoutingFilter.isGlacier(null, null));
        assertFalse(GlacierRoutingFilter.isGlacier("", null));
    }

    @Test
    void recognizesGlacierVersionHeaderWithoutAuth() {
        assertTrue(GlacierRoutingFilter.isGlacier(null, "2012-06-01"));
    }

    @Test
    void prefixesVaultPathsOntoTheInternalPrefix() {
        assertEquals("/aws-glacier/-/vaults/demo/jobs",
                GlacierRoutingFilter.rewritePath("/-/vaults/demo/jobs"));
        assertEquals("/aws-glacier/-/vaults/demo/archives",
                GlacierRoutingFilter.rewritePath("/-/vaults/demo/archives"));
        assertEquals("/aws-glacier/-/vaults/demo",
                GlacierRoutingFilter.rewritePath("/-/vaults/demo/"));
        assertEquals("/aws-glacier/-/vaults/demo/lock-policy",
                GlacierRoutingFilter.rewritePath("/-/vaults/demo/lock-policy"));
        assertEquals("/aws-glacier/-/vaults/demo/notification-configuration",
                GlacierRoutingFilter.rewritePath("/-/vaults/demo/notification-configuration"));
        assertEquals("/aws-glacier/-/vaults/demo/access-policy",
                GlacierRoutingFilter.rewritePath("/-/vaults/demo/access-policy"));
        assertEquals("/aws-glacier/-/vaults/demo/tags",
                GlacierRoutingFilter.rewritePath("/-/vaults/demo/tags"));
    }

    @Test
    void leavesAlreadyPrefixedPathsAlone() {
        assertEquals("/aws-glacier/-/vaults/demo/jobs",
                GlacierRoutingFilter.rewritePath("/aws-glacier/-/vaults/demo/jobs"));
    }
}
