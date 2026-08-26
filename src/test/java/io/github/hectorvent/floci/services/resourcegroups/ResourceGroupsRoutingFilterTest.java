package io.github.hectorvent.floci.services.resourcegroups;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceGroupsRoutingFilterTest {

    private static final String RG_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/resource-groups/aws4_request";
    private static final String BACKUP_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/backup/aws4_request";

    @Test
    void recognizesResourceGroupsCredentialScope() {
        assertTrue(ResourceGroupsRoutingFilter.isResourceGroups(RG_AUTH));
        assertFalse(ResourceGroupsRoutingFilter.isResourceGroups(BACKUP_AUTH));
        assertFalse(ResourceGroupsRoutingFilter.isResourceGroups(null));
        assertFalse(ResourceGroupsRoutingFilter.isResourceGroups(""));
    }

    @Test
    void prefixesPublicPathsAndStripsTrailingSlash() {
        assertEquals("/aws-resource-groups/groups",
                ResourceGroupsRoutingFilter.rewritePath("/groups"));
        assertEquals("/aws-resource-groups/groups",
                ResourceGroupsRoutingFilter.rewritePath("/groups/"));
        assertEquals("/aws-resource-groups/list-group-resources",
                ResourceGroupsRoutingFilter.rewritePath("/list-group-resources"));
        assertEquals("/aws-resource-groups/resources/search",
                ResourceGroupsRoutingFilter.rewritePath("/resources/search"));
        assertEquals("/aws-resource-groups/resources/arn:aws:resource-groups:us-east-1:000000000000:group/g/tags",
                ResourceGroupsRoutingFilter.rewritePath(
                        "/resources/arn:aws:resource-groups:us-east-1:000000000000:group/g/tags"));
    }

    @Test
    void leavesAlreadyPrefixedPathsAlone() {
        assertEquals("/aws-resource-groups/groups",
                ResourceGroupsRoutingFilter.rewritePath("/aws-resource-groups/groups"));
    }
}
