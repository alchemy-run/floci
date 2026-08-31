package io.github.hectorvent.floci.services.eks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksRoutingFilterTest {

    private static final String EKS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/eks/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesEksCredentialScope() {
        assertTrue(EksRoutingFilter.isEks(EKS_AUTH));
        assertFalse(EksRoutingFilter.isEks(S3_AUTH));
        assertFalse(EksRoutingFilter.isEks(null));
        assertFalse(EksRoutingFilter.isEks(""));
    }

    @Test
    void recognizesControlPlaneHosts() {
        assertTrue(EksRoutingFilter.isEksHost("eks.us-east-1.amazonaws.com"));
        assertTrue(EksRoutingFilter.isEksHost("eks-fips.us-west-2.amazonaws.com:443"));
        assertTrue(EksRoutingFilter.isEksHost("fips.eks.us-east-1.amazonaws.com"));
        assertFalse(EksRoutingFilter.isEksHost("s3.us-east-1.amazonaws.com"));
        assertFalse(EksRoutingFilter.isEksHost("localhost:4566"));
        assertFalse(EksRoutingFilter.isEksHost(null));
    }

    @Test
    void prefixesCatalogAndClusterPathsAndStripsTrailingSlash() {
        assertEquals("/aws-eks/clusters",
                EksRoutingFilter.rewritePath("/clusters"));
        assertEquals("/aws-eks/clusters",
                EksRoutingFilter.rewritePath("/clusters/"));
        assertEquals("/aws-eks/access-policies",
                EksRoutingFilter.rewritePath("/access-policies"));
        assertEquals("/aws-eks/cluster-versions",
                EksRoutingFilter.rewritePath("/cluster-versions"));
        assertEquals("/aws-eks/addons/supported-versions",
                EksRoutingFilter.rewritePath("/addons/supported-versions"));
        assertEquals("/aws-eks/addons/configuration-schemas",
                EksRoutingFilter.rewritePath("/addons/configuration-schemas"));
        assertEquals("/aws-eks/clusters/my-cluster/node-groups",
                EksRoutingFilter.rewritePath("/clusters/my-cluster/node-groups"));
    }

    @Test
    void leavesSharedTagPathsAlreadyPrefixedAndFunctionUrlsAlone() {
        assertEquals("/tags/arn:aws:eks:us-east-1:000000000000:cluster/my-cluster",
                EksRoutingFilter.rewritePath(
                        "/tags/arn:aws:eks:us-east-1:000000000000:cluster/my-cluster"));
        assertEquals("/aws-eks/clusters",
                EksRoutingFilter.rewritePath("/aws-eks/clusters"));
        assertEquals("/lambda-url/abc123/bindings",
                EksRoutingFilter.rewritePath("/lambda-url/abc123/bindings"));
        assertEquals("/lambda-url/abc123/bindings/",
                EksRoutingFilter.rewritePath("/lambda-url/abc123/bindings/"));
        // Unsigned Function URL probes never reach rewritePath (filter returns
        // on missing eks scope / lambda-url Host). Signed /bindings is
        // prefixed like any other restJson1 path.
        assertEquals("/aws-eks/bindings", EksRoutingFilter.rewritePath("/bindings"));
    }

    @Test
    void lambdaUrlHostsAreNotEks() {
        assertTrue(EksRoutingFilter.isLambdaUrlHost(
                "abc123.lambda-url.us-east-1.localhost:4566"));
        assertFalse(EksRoutingFilter.isLambdaUrlHost("localhost:4566"));
        assertFalse(EksRoutingFilter.isLambdaUrlHost(null));
    }
}
