package io.github.hectorvent.floci.services.dsql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DsqlRoutingFilterTest {

    private static final String DSQL_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/dsql/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesDsqlCredentialScope() {
        assertTrue(DsqlRoutingFilter.isDsql(DSQL_AUTH));
        assertFalse(DsqlRoutingFilter.isDsql(S3_AUTH));
        assertFalse(DsqlRoutingFilter.isDsql(null));
        assertFalse(DsqlRoutingFilter.isDsql(""));
    }

    @Test
    void prefixesClusterAndStreamPathsAndStripsTrailingSlash() {
        assertEquals("/aws-dsql/cluster", DsqlRoutingFilter.rewritePath("/cluster"));
        assertEquals("/aws-dsql/cluster", DsqlRoutingFilter.rewritePath("/cluster/"));
        assertEquals("/aws-dsql/cluster/abc", DsqlRoutingFilter.rewritePath("/cluster/abc"));
        assertEquals("/aws-dsql/stream/abc", DsqlRoutingFilter.rewritePath("/stream/abc"));
        assertEquals("/aws-dsql/stream/abc/def", DsqlRoutingFilter.rewritePath("/stream/abc/def"));
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals(
                "/tags/arn:aws:dsql:us-east-1:000000000000:cluster/abc",
                DsqlRoutingFilter.rewritePath("/tags/arn:aws:dsql:us-east-1:000000000000:cluster/abc"));
        assertEquals("/aws-dsql/cluster/abc", DsqlRoutingFilter.rewritePath("/aws-dsql/cluster/abc"));
    }
}
