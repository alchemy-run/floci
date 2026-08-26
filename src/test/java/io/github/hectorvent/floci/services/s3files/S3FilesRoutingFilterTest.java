package io.github.hectorvent.floci.services.s3files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3FilesRoutingFilterTest {

    private static final String S3FILES_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3files/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesS3FilesCredentialScope() {
        assertTrue(S3FilesRoutingFilter.isS3Files(S3FILES_AUTH));
        assertFalse(S3FilesRoutingFilter.isS3Files(S3_AUTH));
        assertFalse(S3FilesRoutingFilter.isS3Files(null));
        assertFalse(S3FilesRoutingFilter.isS3Files(""));
    }

    @Test
    void prefixesFileSystemAndAccessPointPathsAndStripsTrailingSlash() {
        assertEquals("/aws-s3files/file-systems",
                S3FilesRoutingFilter.rewritePath("/file-systems"));
        assertEquals("/aws-s3files/file-systems",
                S3FilesRoutingFilter.rewritePath("/file-systems/"));
        assertEquals("/aws-s3files/file-systems/fs-0123456789abcdef0",
                S3FilesRoutingFilter.rewritePath("/file-systems/fs-0123456789abcdef0"));
        assertEquals("/aws-s3files/access-points",
                S3FilesRoutingFilter.rewritePath("/access-points"));
        assertEquals("/aws-s3files/resource-tags/fs-abc",
                S3FilesRoutingFilter.rewritePath("/resource-tags/fs-abc"));
    }

    @Test
    void leavesAlreadyPrefixedPathsAlone() {
        assertEquals("/aws-s3files/file-systems/fs-abc",
                S3FilesRoutingFilter.rewritePath("/aws-s3files/file-systems/fs-abc"));
    }
}
