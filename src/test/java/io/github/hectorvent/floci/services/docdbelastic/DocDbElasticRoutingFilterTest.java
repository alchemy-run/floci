package io.github.hectorvent.floci.services.docdbelastic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocDbElasticRoutingFilterTest {

    private static final String ELASTIC_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/docdb-elastic/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesDocDbElasticCredentialScope() {
        assertTrue(DocDbElasticRoutingFilter.isDocDbElastic(ELASTIC_AUTH));
        assertFalse(DocDbElasticRoutingFilter.isDocDbElastic(S3_AUTH));
        assertFalse(DocDbElasticRoutingFilter.isDocDbElastic(null));
        assertFalse(DocDbElasticRoutingFilter.isDocDbElastic(""));
    }

    @Test
    void prefixesClusterPathsAndStripsTrailingSlash() {
        assertEquals("/aws-docdb-elastic/cluster", DocDbElasticRoutingFilter.rewritePath("/cluster"));
        assertEquals("/aws-docdb-elastic/cluster", DocDbElasticRoutingFilter.rewritePath("/cluster/"));
        assertEquals("/aws-docdb-elastic/clusters", DocDbElasticRoutingFilter.rewritePath("/clusters"));
        assertEquals(
                "/aws-docdb-elastic/cluster/arn:aws:docdb-elastic:us-east-1:000000000000:cluster/abc",
                DocDbElasticRoutingFilter.rewritePath(
                        "/cluster/arn:aws:docdb-elastic:us-east-1:000000000000:cluster/abc"));
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals(
                "/tags/arn:aws:docdb-elastic:us-east-1:000000000000:cluster/abc",
                DocDbElasticRoutingFilter.rewritePath(
                        "/tags/arn:aws:docdb-elastic:us-east-1:000000000000:cluster/abc"));
        assertEquals(
                "/aws-docdb-elastic/cluster/abc",
                DocDbElasticRoutingFilter.rewritePath("/aws-docdb-elastic/cluster/abc"));
    }
}
