package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * Alchemy-parity operations that the live EC2 suite hits in local-dev mode:
 * route-table association replace, snapshots, peering, DHCP, prefix lists,
 * egress-only IGWs, ENIs, and VPC endpoint modify.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2AlchemyParityIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String vpcId;
    private static String subnetId;
    private static String routeTableId;
    private static String associationId;
    private static String volumeId;
    private static String snapshotId;
    private static String peeringId;
    private static String prefixListId;
    private static String eniId;

    @Test
    @Order(1)
    void seedVpcAndSubnet() {
        vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.80.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        subnetId = given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.80.1.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSubnetResponse.subnet.subnetId");
    }

    @Test
    @Order(2)
    void replaceRouteTableAssociationMintsANewId() {
        routeTableId = given()
            .formParam("Action", "CreateRouteTable")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateRouteTableResponse.routeTable.routeTableId");

        associationId = given()
            .formParam("Action", "AssociateRouteTable")
            .formParam("RouteTableId", routeTableId)
            .formParam("SubnetId", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("AssociateRouteTableResponse.associationId");

        String replacementTable = given()
            .formParam("Action", "CreateRouteTable")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateRouteTableResponse.routeTable.routeTableId");

        given()
            .formParam("Action", "ReplaceRouteTableAssociation")
            .formParam("AssociationId", associationId)
            .formParam("RouteTableId", replacementTable)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ReplaceRouteTableAssociationResponse.newAssociationId", startsWith("rtbassoc-"));
    }

    @Test
    @Order(3)
    void snapshotRoundTrip() {
        volumeId = given()
            .formParam("Action", "CreateVolume")
            .formParam("AvailabilityZone", "us-east-1a")
            .formParam("Size", "1")
            .formParam("VolumeType", "gp3")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVolumeResponse.volumeId");

        snapshotId = given()
            .formParam("Action", "CreateSnapshot")
            .formParam("VolumeId", volumeId)
            .formParam("Description", "alchemy-parity")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSnapshotResponse.snapshotId", startsWith("snap-"))
            .body("CreateSnapshotResponse.status", equalTo("completed"))
            .extract().path("CreateSnapshotResponse.snapshotId");

        given()
            .formParam("Action", "DescribeSnapshots")
            .formParam("SnapshotId.1", snapshotId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSnapshotsResponse.snapshotSet.item.volumeId", equalTo(volumeId));

        given()
            .formParam("Action", "DeleteSnapshot")
            .formParam("SnapshotId", snapshotId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void modifyVolumeGrowsSize() {
        given()
            .formParam("Action", "ModifyVolume")
            .formParam("VolumeId", volumeId)
            .formParam("Size", "2")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyVolumeResponse.volumeModification.targetSize", equalTo("2"));
    }

    @Test
    @Order(5)
    void vpcPeeringCreateAcceptDescribeDelete() {
        String peerVpc = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.81.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        peeringId = given()
            .formParam("Action", "CreateVpcPeeringConnection")
            .formParam("VpcId", vpcId)
            .formParam("PeerVpcId", peerVpc)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateVpcPeeringConnectionResponse.vpcPeeringConnection.status.code",
                    equalTo("pending-acceptance"))
            .extract().path("CreateVpcPeeringConnectionResponse.vpcPeeringConnection.vpcPeeringConnectionId");

        given()
            .formParam("Action", "AcceptVpcPeeringConnection")
            .formParam("VpcPeeringConnectionId", peeringId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AcceptVpcPeeringConnectionResponse.vpcPeeringConnection.status.code",
                    equalTo("active"));

        given()
            .formParam("Action", "DeleteVpcPeeringConnection")
            .formParam("VpcPeeringConnectionId", peeringId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void dhcpOptionsAndEgressOnlyInternetGateway() {
        String dopt = given()
            .formParam("Action", "CreateDhcpOptions")
            .formParam("DhcpConfiguration.1.Key", "domain-name")
            .formParam("DhcpConfiguration.1.Value.1", "internal.test")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateDhcpOptionsResponse.dhcpOptions.dhcpOptionsId", startsWith("dopt-"))
            .extract().path("CreateDhcpOptionsResponse.dhcpOptions.dhcpOptionsId");

        given()
            .formParam("Action", "AssociateDhcpOptions")
            .formParam("DhcpOptionsId", dopt)
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String eigw = given()
            .formParam("Action", "CreateEgressOnlyInternetGateway")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateEgressOnlyInternetGatewayResponse.egressOnlyInternetGateway.egressOnlyInternetGatewayId",
                    startsWith("eigw-"))
            .extract().path("CreateEgressOnlyInternetGatewayResponse.egressOnlyInternetGateway.egressOnlyInternetGatewayId");

        given()
            .formParam("Action", "DeleteEgressOnlyInternetGateway")
            .formParam("EgressOnlyInternetGatewayId", eigw)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(7)
    void managedPrefixListRoundTrip() {
        prefixListId = given()
            .formParam("Action", "CreateManagedPrefixList")
            .formParam("PrefixListName", "alchemy-parity")
            .formParam("AddressFamily", "IPv4")
            .formParam("MaxEntries", "10")
            .formParam("Entry.1.Cidr", "10.0.0.0/8")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateManagedPrefixListResponse.prefixList.state", equalTo("create-complete"))
            .extract().path("CreateManagedPrefixListResponse.prefixList.prefixListId");

        given()
            .formParam("Action", "GetManagedPrefixListEntries")
            .formParam("PrefixListId", prefixListId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetManagedPrefixListEntriesResponse.entrySet.item.cidr", equalTo("10.0.0.0/8"));

        given()
            .formParam("Action", "ModifyManagedPrefixList")
            .formParam("PrefixListId", prefixListId)
            .formParam("CurrentVersion", "1")
            .formParam("AddEntry.1.Cidr", "192.168.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyManagedPrefixListResponse.prefixList.version", equalTo("2"));

        given()
            .formParam("Action", "DeleteManagedPrefixList")
            .formParam("PrefixListId", prefixListId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(8)
    void networkInterfaceAndVpcEndpointModify() {
        eniId = given()
            .formParam("Action", "CreateNetworkInterface")
            .formParam("SubnetId", subnetId)
            .formParam("Description", "alchemy-parity")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId", startsWith("eni-"))
            .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");

        given()
            .formParam("Action", "ModifyNetworkInterfaceAttribute")
            .formParam("NetworkInterfaceId", eniId)
            .formParam("Description.Value", "updated")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String endpointId = given()
            .formParam("Action", "CreateVpcEndpoint")
            .formParam("VpcId", vpcId)
            .formParam("ServiceName", "com.amazonaws.us-east-1.s3")
            .formParam("VpcEndpointType", "Gateway")
            .formParam("RouteTableId.1", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId");

        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", endpointId)
            .formParam("RemoveRouteTableId.1", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DeleteNetworkInterface")
            .formParam("NetworkInterfaceId", eniId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
