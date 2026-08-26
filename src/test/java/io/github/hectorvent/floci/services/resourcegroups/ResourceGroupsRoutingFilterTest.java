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
    void leavesAlreadyPrefixedPathsAndFunctionUrlsAlone() {
        assertEquals("/aws-resource-groups/groups",
                ResourceGroupsRoutingFilter.rewritePath("/aws-resource-groups/groups"));
        assertEquals("/lambda-url/abc123/bindings",
                ResourceGroupsRoutingFilter.rewritePath("/lambda-url/abc123/bindings"));
        assertEquals("/lambda-url/abc123/bindings/",
                ResourceGroupsRoutingFilter.rewritePath("/lambda-url/abc123/bindings/"));
        // Unsigned Function URL probes never reach rewritePath (filter returns
        // on missing resource-groups scope / lambda-url Host). Signed /bindings is
        // prefixed like any other restJson1 path.
        assertEquals("/aws-resource-groups/bindings",
                ResourceGroupsRoutingFilter.rewritePath("/bindings"));
    }

    @Test
    void lambdaUrlHostsAreNotResourceGroups() {
        assertTrue(ResourceGroupsRoutingFilter.isLambdaUrlHost(
                "abc123.lambda-url.us-east-1.localhost:4566"));
        assertFalse(ResourceGroupsRoutingFilter.isLambdaUrlHost("localhost:4566"));
        assertFalse(ResourceGroupsRoutingFilter.isLambdaUrlHost(null));
    }
}
