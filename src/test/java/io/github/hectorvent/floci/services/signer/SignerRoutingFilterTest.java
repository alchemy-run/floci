package io.github.hectorvent.floci.services.signer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignerRoutingFilterTest {

    private static final String SIGNER_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/signer/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesSignerCredentialScope() {
        assertTrue(SignerRoutingFilter.isSigner(SIGNER_AUTH));
        assertFalse(SignerRoutingFilter.isSigner(S3_AUTH));
        assertFalse(SignerRoutingFilter.isSigner(null));
        assertFalse(SignerRoutingFilter.isSigner(""));
    }

    @Test
    void recognizesControlAndDataPlaneHosts() {
        assertTrue(SignerRoutingFilter.isSignerHost("signer.us-east-1.amazonaws.com"));
        assertTrue(SignerRoutingFilter.isSignerHost("signer-fips.us-west-2.amazonaws.com:443"));
        assertTrue(SignerRoutingFilter.isSignerHost("data-signer.us-east-1.amazonaws.com"));
        assertTrue(SignerRoutingFilter.isSignerHost("data-signer-fips.us-east-1.amazonaws.com"));
        assertFalse(SignerRoutingFilter.isSignerHost("s3.us-east-1.amazonaws.com"));
        assertFalse(SignerRoutingFilter.isSignerHost("localhost:4566"));
        assertFalse(SignerRoutingFilter.isSignerHost(null));
    }

    @Test
    void prefixesSigningProfilePathsAndStripsTrailingSlash() {
        assertEquals("/aws-signer/signing-profiles",
                SignerRoutingFilter.rewritePath("/signing-profiles"));
        assertEquals("/aws-signer/signing-profiles",
                SignerRoutingFilter.rewritePath("/signing-profiles/"));
        assertEquals("/aws-signer/signing-profiles/My_Profile",
                SignerRoutingFilter.rewritePath("/signing-profiles/My_Profile"));
        assertEquals("/aws-signer/signing-profiles/My_Profile/permissions",
                SignerRoutingFilter.rewritePath("/signing-profiles/My_Profile/permissions"));
        assertEquals("/aws-signer/signing-profiles/My_Profile/permissions/stmt-1",
                SignerRoutingFilter.rewritePath("/signing-profiles/My_Profile/permissions/stmt-1"));
        assertEquals("/aws-signer/signing-jobs",
                SignerRoutingFilter.rewritePath("/signing-jobs"));
        assertEquals("/aws-signer/revocations",
                SignerRoutingFilter.rewritePath("/revocations"));
        assertEquals("/aws-signer/signing-platforms",
                SignerRoutingFilter.rewritePath("/signing-platforms"));
    }

    @Test
    void leavesSharedTagPathsAlreadyPrefixedAndFunctionUrlsAlone() {
        assertEquals("/tags/arn:aws:signer:us-east-1:000000000000:/signing-profiles/My_Profile",
                SignerRoutingFilter.rewritePath(
                        "/tags/arn:aws:signer:us-east-1:000000000000:/signing-profiles/My_Profile"));
        assertEquals("/aws-signer/signing-profiles/My_Profile",
                SignerRoutingFilter.rewritePath("/aws-signer/signing-profiles/My_Profile"));
        assertEquals("/lambda-url/abc123/bindings",
                SignerRoutingFilter.rewritePath("/lambda-url/abc123/bindings"));
        assertEquals("/lambda-url/abc123/bindings/",
                SignerRoutingFilter.rewritePath("/lambda-url/abc123/bindings/"));
        // Unsigned Function URL probes never reach rewritePath (filter returns
        // on missing signer scope / lambda-url Host). Signed /bindings is
        // prefixed like any other restJson1 path.
        assertEquals("/aws-signer/bindings", SignerRoutingFilter.rewritePath("/bindings"));
    }

    @Test
    void lambdaUrlHostsAreNotSigner() {
        assertTrue(SignerRoutingFilter.isLambdaUrlHost(
                "abc123.lambda-url.us-east-1.localhost:4566"));
        assertFalse(SignerRoutingFilter.isLambdaUrlHost("localhost:4566"));
        assertFalse(SignerRoutingFilter.isLambdaUrlHost(null));
    }
}
