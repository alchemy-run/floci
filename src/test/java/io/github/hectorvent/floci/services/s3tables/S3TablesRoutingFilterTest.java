package io.github.hectorvent.floci.services.s3tables;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3TablesRoutingFilterTest {

    private static final String S3TABLES_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3tables/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesS3TablesCredentialScope() {
        assertTrue(S3TablesRoutingFilter.isS3Tables(S3TABLES_AUTH));
        assertFalse(S3TablesRoutingFilter.isS3Tables(S3_AUTH));
        assertFalse(S3TablesRoutingFilter.isS3Tables(null));
        assertFalse(S3TablesRoutingFilter.isS3Tables(""));
    }

    @Test
    void prefixesBucketAndTablePathsAndStripsTrailingSlash() {
        assertEquals("/aws-s3tables/buckets", S3TablesRoutingFilter.rewritePath("/buckets"));
        assertEquals("/aws-s3tables/buckets", S3TablesRoutingFilter.rewritePath("/buckets/"));
        assertEquals("/aws-s3tables/namespaces", S3TablesRoutingFilter.rewritePath("/namespaces"));
        assertEquals("/aws-s3tables/tables", S3TablesRoutingFilter.rewritePath("/tables"));
        assertEquals("/aws-s3tables/get-table", S3TablesRoutingFilter.rewritePath("/get-table"));
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals("/tags/arn:aws:s3tables:us-east-1:000000000000:bucket/b",
                S3TablesRoutingFilter.rewritePath(
                        "/tags/arn:aws:s3tables:us-east-1:000000000000:bucket/b"));
        assertEquals("/aws-s3tables/buckets",
                S3TablesRoutingFilter.rewritePath("/aws-s3tables/buckets"));
    }
}
