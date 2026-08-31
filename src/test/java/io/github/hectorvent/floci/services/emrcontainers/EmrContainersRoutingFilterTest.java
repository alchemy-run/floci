package io.github.hectorvent.floci.services.emrcontainers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmrContainersRoutingFilterTest {

    private static final String EMRC_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/emr-containers/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesEmrContainersCredentialScope() {
        assertTrue(EmrContainersRoutingFilter.isEmrContainers(EMRC_AUTH));
        assertFalse(EmrContainersRoutingFilter.isEmrContainers(S3_AUTH));
        assertFalse(EmrContainersRoutingFilter.isEmrContainers(null));
        assertFalse(EmrContainersRoutingFilter.isEmrContainers(""));
    }

    @Test
    void prefixesJobTemplateAndVirtualClusterPathsAndStripsTrailingSlash() {
        assertEquals("/aws-emr-containers/jobtemplates",
                EmrContainersRoutingFilter.rewritePath("/jobtemplates"));
        assertEquals("/aws-emr-containers/jobtemplates",
                EmrContainersRoutingFilter.rewritePath("/jobtemplates/"));
        assertEquals("/aws-emr-containers/jobtemplates/abcdefabcdefabcdefabcdef01",
                EmrContainersRoutingFilter.rewritePath("/jobtemplates/abcdefabcdefabcdefabcdef01"));
        assertEquals("/aws-emr-containers/virtualclusters",
                EmrContainersRoutingFilter.rewritePath("/virtualclusters"));
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals(
                "/tags/arn:aws:emr-containers:us-east-1:000000000000:/virtualclusters/abc",
                EmrContainersRoutingFilter.rewritePath(
                        "/tags/arn:aws:emr-containers:us-east-1:000000000000:/virtualclusters/abc"));
        assertEquals("/aws-emr-containers/jobtemplates",
                EmrContainersRoutingFilter.rewritePath("/aws-emr-containers/jobtemplates"));
    }
}
