package io.github.hectorvent.floci.services.rolesanywhere;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolesAnywhereRoutingFilterTest {

    private static final String RA_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/rolesanywhere/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesRolesAnywhereCredentialScope() {
        assertTrue(RolesAnywhereRoutingFilter.isRolesAnywhere(RA_AUTH));
        assertFalse(RolesAnywhereRoutingFilter.isRolesAnywhere(S3_AUTH));
        assertFalse(RolesAnywhereRoutingFilter.isRolesAnywhere(null));
        assertFalse(RolesAnywhereRoutingFilter.isRolesAnywhere(""));
    }

    @Test
    void prefixesPublicPathsAndStripsTrailingSlash() {
        assertEquals("/aws-rolesanywhere/subjects",
                RolesAnywhereRoutingFilter.rewritePath("/subjects"));
        assertEquals("/aws-rolesanywhere/subjects",
                RolesAnywhereRoutingFilter.rewritePath("/subjects/"));
        assertEquals("/aws-rolesanywhere/subject/" + "00000000-0000-0000-0000-000000000000",
                RolesAnywhereRoutingFilter.rewritePath("/subject/00000000-0000-0000-0000-000000000000"));
        assertEquals("/aws-rolesanywhere/trustanchors",
                RolesAnywhereRoutingFilter.rewritePath("/trustanchors"));
        assertEquals("/aws-rolesanywhere/ListTagsForResource",
                RolesAnywhereRoutingFilter.rewritePath("/ListTagsForResource"));
    }

    @Test
    void leavesAlreadyPrefixedPathsAlone() {
        assertEquals("/aws-rolesanywhere/subjects",
                RolesAnywhereRoutingFilter.rewritePath("/aws-rolesanywhere/subjects"));
    }
}
