package io.github.hectorvent.floci.services.efs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EfsRoutingFilterTest {

    private static final String EFS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/elasticfilesystem/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesElasticfilesystemCredentialScope() {
        assertTrue(EfsRoutingFilter.isEfs(EFS_AUTH));
        assertFalse(EfsRoutingFilter.isEfs(S3_AUTH));
        assertFalse(EfsRoutingFilter.isEfs(null));
        assertFalse(EfsRoutingFilter.isEfs(""));
    }

    @Test
    void prefixesDatedApiPathsOntoTheInternalPrefix() {
        assertEquals("/aws-efs/2015-02-01/file-systems",
                EfsRoutingFilter.rewritePath("/2015-02-01/file-systems"));
        assertEquals("/aws-efs/2015-02-01/file-systems",
                EfsRoutingFilter.rewritePath("/2015-02-01/file-systems/"));
        assertEquals("/aws-efs/2015-02-01/access-points/fsap-abc",
                EfsRoutingFilter.rewritePath("/2015-02-01/access-points/fsap-abc"));
    }

    @Test
    void leavesAlreadyPrefixedPathsAlone() {
        assertEquals("/aws-efs/2015-02-01/file-systems/fs-abc",
                EfsRoutingFilter.rewritePath("/aws-efs/2015-02-01/file-systems/fs-abc"));
    }
}
