package io.github.hectorvent.floci.services.repostspace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepostspaceRoutingFilterTest {

    private static final String REPOSTSPACE_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/repostspace/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesRepostspaceCredentialScope() {
        assertTrue(RepostspaceRoutingFilter.isRepostspace(REPOSTSPACE_AUTH));
        assertFalse(RepostspaceRoutingFilter.isRepostspace(S3_AUTH));
        assertFalse(RepostspaceRoutingFilter.isRepostspace(null));
        assertFalse(RepostspaceRoutingFilter.isRepostspace(""));
    }

    @Test
    void prefixesSpaceAndChannelPathsAndStripsTrailingSlash() {
        assertEquals("/aws-repostspace/spaces", RepostspaceRoutingFilter.rewritePath("/spaces"));
        assertEquals("/aws-repostspace/spaces", RepostspaceRoutingFilter.rewritePath("/spaces/"));
        assertEquals(
                "/aws-repostspace/spaces/SPalchemynonexistentprobe0/channels/CHalchemynonexistentprobe0",
                RepostspaceRoutingFilter.rewritePath(
                        "/spaces/SPalchemynonexistentprobe0/channels/CHalchemynonexistentprobe0"));
        assertEquals(
                "/aws-repostspace/spaces/SP1/invite",
                RepostspaceRoutingFilter.rewritePath("/spaces/SP1/invite"));
        assertEquals(
                "/aws-repostspace/spaces/SP1/roles",
                RepostspaceRoutingFilter.rewritePath("/spaces/SP1/roles"));
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals(
                "/tags/arn:aws:repostspace:us-east-1:000000000000:space/SP1",
                RepostspaceRoutingFilter.rewritePath(
                        "/tags/arn:aws:repostspace:us-east-1:000000000000:space/SP1"));
        assertEquals(
                "/aws-repostspace/spaces/SP1",
                RepostspaceRoutingFilter.rewritePath("/aws-repostspace/spaces/SP1"));
    }
}
