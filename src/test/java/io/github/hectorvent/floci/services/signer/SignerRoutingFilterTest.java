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
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals("/tags/arn:aws:signer:us-east-1:000000000000:/signing-profiles/My_Profile",
                SignerRoutingFilter.rewritePath(
                        "/tags/arn:aws:signer:us-east-1:000000000000:/signing-profiles/My_Profile"));
        assertEquals("/aws-signer/signing-profiles/My_Profile",
                SignerRoutingFilter.rewritePath("/aws-signer/signing-profiles/My_Profile"));
    }
}
