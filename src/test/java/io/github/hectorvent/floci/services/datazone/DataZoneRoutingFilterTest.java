package io.github.hectorvent.floci.services.datazone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataZoneRoutingFilterTest {

    private static final String DATAZONE_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/datazone/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesDataZoneCredentialScope() {
        assertTrue(DataZoneRoutingFilter.isDataZone(DATAZONE_AUTH));
        assertFalse(DataZoneRoutingFilter.isDataZone(S3_AUTH));
        assertFalse(DataZoneRoutingFilter.isDataZone(null));
        assertFalse(DataZoneRoutingFilter.isDataZone(""));
    }

    @Test
    void prefixesDomainPathsAndStripsTrailingSlash() {
        assertEquals("/aws-datazone/v2/domains", DataZoneRoutingFilter.rewritePath("/v2/domains"));
        assertEquals("/aws-datazone/v2/domains", DataZoneRoutingFilter.rewritePath("/v2/domains/"));
        assertEquals("/aws-datazone/v2/domains/dzd_abc",
                DataZoneRoutingFilter.rewritePath("/v2/domains/dzd_abc"));
        assertEquals("/aws-datazone/v2/domains/dzd_abc/projects",
                DataZoneRoutingFilter.rewritePath("/v2/domains/dzd_abc/projects"));
        assertEquals("/aws-datazone/v2/domains/dzd_abc/environments/0000000000",
                DataZoneRoutingFilter.rewritePath("/v2/domains/dzd_abc/environments/0000000000"));
        assertEquals("/aws-datazone/v2/domains/dzd_abc/search",
                DataZoneRoutingFilter.rewritePath("/v2/domains/dzd_abc/search"));
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals("/tags/arn:aws:datazone:us-east-1:000000000000:domain/dzd_abc",
                DataZoneRoutingFilter.rewritePath(
                        "/tags/arn:aws:datazone:us-east-1:000000000000:domain/dzd_abc"));
        assertEquals("/aws-datazone/v2/domains",
                DataZoneRoutingFilter.rewritePath("/aws-datazone/v2/domains"));
    }
}
