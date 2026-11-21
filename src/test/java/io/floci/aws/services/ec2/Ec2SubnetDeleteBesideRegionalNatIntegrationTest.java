package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.floci.aws.testing.Ec2Requests.ec2;

/**
 * A regional NAT gateway has no subnet. Deleting any subnet in its region checked whether a NAT gateway
 * still held that subnet, and the gateway's missing subnet failed the check with an internal error
 * instead of passing it.
 */
@QuarkusTest
class Ec2SubnetDeleteBesideRegionalNatIntegrationTest {

    private static final String ACCOUNT_ID = "000000000947";
    private static final String REGION = "us-east-1";

    @Test
    void aSubnetDeletesWhileARegionalNatGatewayExists() {
        String natVpcId = vpc("10.95.0.0/16");
        ec2("CreateNatGateway", ACCOUNT_ID, REGION)
                .formParam("AvailabilityMode", "regional").formParam("VpcId", natVpcId).post("/")
        .then().statusCode(200);

        String vpcId = vpc("10.96.0.0/16");
        String subnetId = ec2("CreateSubnet", ACCOUNT_ID, REGION)
                .formParam("VpcId", vpcId).formParam("CidrBlock", "10.96.1.0/24").post("/")
        .then().statusCode(200)
                .extract().path("CreateSubnetResponse.subnet.subnetId");

        ec2("DeleteSubnet", ACCOUNT_ID, REGION).formParam("SubnetId", subnetId).post("/")
        .then().statusCode(200);
    }

    private static String vpc(String cidr) {
        return ec2("CreateVpc", ACCOUNT_ID, REGION).formParam("CidrBlock", cidr).post("/")
        .then().statusCode(200)
                .extract().path("CreateVpcResponse.vpc.vpcId");
    }
}
