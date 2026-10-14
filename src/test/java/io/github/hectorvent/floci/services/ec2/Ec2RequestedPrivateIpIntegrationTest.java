package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class Ec2RequestedPrivateIpIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260921/us-east-1/ec2/aws4_request";

    @ParameterizedTest
    @ValueSource(strings = {"PrivateIpAddress", "NetworkInterface.1.PrivateIpAddress",
            "NetworkInterface.1.PrivateIpAddresses.1.PrivateIpAddress"})
    void requestedAddressRoundTripsAcrossEc2AndEniQueryResponses(String parameter) {
        String vpc = request("CreateVpc").formParam("CidrBlock", "10.0.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String subnet = null;
        String instance = null;
        try {
            subnet = request("CreateSubnet").formParam("VpcId", vpc).formParam("CidrBlock", "10.0.1.0/24")
                    .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
            RequestSpecification launch = request("RunInstances").formParam("ImageId", "ami-private-ip")
                    .formParam("InstanceType", "t3.micro").formParam("MinCount", "1").formParam("MaxCount", "1")
                    .formParam(parameter, "10.0.1.77");
            if (parameter.startsWith("NetworkInterface")) {
                launch.formParam("NetworkInterface.1.DeviceIndex", "0").formParam("NetworkInterface.1.SubnetId", subnet);
                if (parameter.contains("PrivateIpAddresses")) {
                    launch.formParam("NetworkInterface.1.PrivateIpAddresses.1.Primary", "true");
                }
            } else {
                launch.formParam("SubnetId", subnet);
            }
            instance = launch.post("/").then().statusCode(200)
                    .body("RunInstancesResponse.instancesSet.item.privateIpAddress", equalTo("10.0.1.77"))
                    .body("RunInstancesResponse.instancesSet.item.networkInterfaceSet.item.privateIpAddress", equalTo("10.0.1.77"))
                    .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
            request("DescribeInstances").formParam("InstanceId.1", instance).post("/").then().statusCode(200)
                    .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.privateIpAddress", equalTo("10.0.1.77"));
            request("DescribeNetworkInterfaces").formParam("Filter.1.Name", "attachment.instance-id")
                    .formParam("Filter.1.Value.1", instance).post("/").then().statusCode(200)
                    .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.privateIpAddress", equalTo("10.0.1.77"));
            request("RunInstances").formParam("ImageId", "ami-private-ip").formParam("SubnetId", subnet)
                    .formParam("PrivateIpAddress", "10.0.1.77").post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidIPAddress.InUse"));
        } finally {
            if (instance != null) {
                request("TerminateInstances").formParam("InstanceId.1", instance).post("/").then().statusCode(200);
            }
            if (subnet != null) {
                request("DeleteSubnet").formParam("SubnetId", subnet).post("/").then().statusCode(200);
            }
            request("DeleteVpc").formParam("VpcId", vpc).post("/").then().statusCode(200);
        }
    }

    private static RequestSpecification request(String action) {
        return given().header("Authorization", AUTH).formParam("Action", action);
    }
}
