package io.github.hectorvent.floci.services.route53profiles;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Route53ProfilesRoutingFilterTest {

    private static final String PROFILES_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/route53profiles/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesRoute53ProfilesCredentialScope() {
        assertTrue(Route53ProfilesRoutingFilter.isRoute53Profiles(PROFILES_AUTH));
        assertFalse(Route53ProfilesRoutingFilter.isRoute53Profiles(S3_AUTH));
        assertFalse(Route53ProfilesRoutingFilter.isRoute53Profiles(null));
        assertFalse(Route53ProfilesRoutingFilter.isRoute53Profiles(""));
    }

    @Test
    void prefixesRestJson1PathsAndStripsTrailingSlash() {
        assertEquals("/aws-route53profiles/profile",
                Route53ProfilesRoutingFilter.rewritePath("/profile"));
        assertEquals("/aws-route53profiles/profile",
                Route53ProfilesRoutingFilter.rewritePath("/profile/"));
        assertEquals("/aws-route53profiles/profiles",
                Route53ProfilesRoutingFilter.rewritePath("/profiles"));
        assertEquals("/aws-route53profiles/profileassociations",
                Route53ProfilesRoutingFilter.rewritePath("/profileassociations"));
        assertEquals("/aws-route53profiles/profileresourceassociations/profileid/rp-abc",
                Route53ProfilesRoutingFilter.rewritePath(
                        "/profileresourceassociations/profileid/rp-abc"));
    }

    @Test
    void leavesSharedTagPathsAlreadyPrefixedPathsAndFunctionUrlsAlone() {
        assertEquals(
                "/tags/arn:aws:route53profiles:us-east-1:000000000000:profile/rp-abc",
                Route53ProfilesRoutingFilter.rewritePath(
                        "/tags/arn:aws:route53profiles:us-east-1:000000000000:profile/rp-abc"));
        assertEquals("/aws-route53profiles/profiles",
                Route53ProfilesRoutingFilter.rewritePath("/aws-route53profiles/profiles"));
        assertEquals(
                "/lambda-url/abc123/bindings",
                Route53ProfilesRoutingFilter.rewritePath("/lambda-url/abc123/bindings"));
        assertEquals(
                "/lambda-url/abc123/bindings/",
                Route53ProfilesRoutingFilter.rewritePath("/lambda-url/abc123/bindings/"));
    }

    @Test
    void lambdaUrlHostsAreNotRewritten() {
        assertTrue(Route53ProfilesRoutingFilter.isLambdaUrlHost(
                "abc123.lambda-url.us-east-1.localhost:4566"));
        assertFalse(Route53ProfilesRoutingFilter.isLambdaUrlHost("localhost:4566"));
        assertFalse(Route53ProfilesRoutingFilter.isLambdaUrlHost(null));
    }
}
