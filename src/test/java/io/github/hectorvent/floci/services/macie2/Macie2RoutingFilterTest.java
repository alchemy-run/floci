package io.github.hectorvent.floci.services.macie2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Macie2RoutingFilterTest {

    private static final String MACIE_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/macie2/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesMacie2CredentialScope() {
        assertTrue(Macie2RoutingFilter.isMacie2(MACIE_AUTH));
        assertFalse(Macie2RoutingFilter.isMacie2(S3_AUTH));
        assertFalse(Macie2RoutingFilter.isMacie2(null));
        assertFalse(Macie2RoutingFilter.isMacie2(""));
    }

    @Test
    void prefixesMacieRestJson1PathsAndStripsTrailingSlash() {
        assertEquals("/macie2/macie", Macie2RoutingFilter.rewritePath("/macie"));
        assertEquals("/macie2/macie", Macie2RoutingFilter.rewritePath("/macie/"));
        assertEquals("/macie2/findings/sample", Macie2RoutingFilter.rewritePath("/findings/sample"));
        assertEquals("/macie2/admin", Macie2RoutingFilter.rewritePath("/admin"));
    }

    @Test
    void leavesSharedTagPathsAlreadyPrefixedPathsAndFunctionUrlsAlone() {
        assertEquals(
                "/tags/arn:aws:macie2:us-east-1:000000000000:allow-list/abc",
                Macie2RoutingFilter.rewritePath(
                        "/tags/arn:aws:macie2:us-east-1:000000000000:allow-list/abc"));
        assertEquals("/macie2/macie", Macie2RoutingFilter.rewritePath("/macie2/macie"));
        assertEquals(
                "/lambda-url/abc123/bindings",
                Macie2RoutingFilter.rewritePath("/lambda-url/abc123/bindings"));
        assertEquals(
                "/lambda-url/abc123/bindings/",
                Macie2RoutingFilter.rewritePath("/lambda-url/abc123/bindings/"));
    }
}
