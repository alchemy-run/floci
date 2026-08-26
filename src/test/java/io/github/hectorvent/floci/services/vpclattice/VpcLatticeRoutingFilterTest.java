package io.github.hectorvent.floci.services.vpclattice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VpcLatticeRoutingFilterTest {

    private static final String LATTICE_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/vpc-lattice/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesVpcLatticeCredentialScope() {
        assertTrue(VpcLatticeRoutingFilter.isVpcLattice(LATTICE_AUTH));
        assertFalse(VpcLatticeRoutingFilter.isVpcLattice(S3_AUTH));
        assertFalse(VpcLatticeRoutingFilter.isVpcLattice(null));
        assertFalse(VpcLatticeRoutingFilter.isVpcLattice(""));
    }

    @Test
    void prefixesPublicPathsAndLeavesTagsAlone() {
        assertEquals("/aws-vpclattice/servicenetworks",
                VpcLatticeRoutingFilter.rewritePath("/servicenetworks"));
        assertEquals("/aws-vpclattice/servicenetworks",
                VpcLatticeRoutingFilter.rewritePath("/servicenetworks/"));
        assertEquals("/aws-vpclattice/services",
                VpcLatticeRoutingFilter.rewritePath("/services"));
        assertEquals("/aws-vpclattice/targetgroups/tg-1",
                VpcLatticeRoutingFilter.rewritePath("/targetgroups/tg-1"));
        assertEquals("/tags/arn:aws:vpc-lattice:us-east-1:000000000000:servicenetwork/sn-1",
                VpcLatticeRoutingFilter.rewritePath(
                        "/tags/arn:aws:vpc-lattice:us-east-1:000000000000:servicenetwork/sn-1"));
    }

    @Test
    void leavesAlreadyPrefixedPathsAlone() {
        assertEquals("/aws-vpclattice/servicenetworks",
                VpcLatticeRoutingFilter.rewritePath("/aws-vpclattice/servicenetworks"));
    }
}
