package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for VPC Flow Logs via the EC2 Query Protocol
 * (form-encoded POST, XML response).
 *
 * <p>Covers {@code CreateFlowLogs} / {@code DescribeFlowLogs} / {@code DeleteFlowLogs}.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2FlowLogIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String vpcId;
    private static String flowLogId;

    // =========================================================================
    // Fixture: a VPC to attach the flow log to
    // =========================================================================

    @Test
    @Order(1)
    void createVpc() {
        vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.20.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("CreateVpcResponse.vpc.state", equalTo("available"))
            .extract().path("CreateVpcResponse.vpc.vpcId");
    }

    // =========================================================================
    // VPC Flow Logs
    // =========================================================================

    @Test
    @Order(10)
    void createFlowLogs() {
        flowLogId = given()
            .formParam("Action", "CreateFlowLogs")
            .formParam("ResourceType", "VPC")
            .formParam("ResourceId.1", vpcId)
            .formParam("TrafficType", "ALL")
            .formParam("LogDestinationType", "s3")
            .formParam("LogDestination", "arn:aws:s3:::flow-logs-test-bucket")
            .formParam("MaxAggregationInterval", "60")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("CreateFlowLogsResponse.flowLogIdSet.item[0]", startsWith("fl-"))
            .extract().path("CreateFlowLogsResponse.flowLogIdSet.item[0]");
    }

    @Test
    @Order(11)
    void describeFlowLogsReturnsTheCreatedLog() {
        given()
            .formParam("Action", "DescribeFlowLogs")
            .formParam("FlowLogId.1", flowLogId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].flowLogId", equalTo(flowLogId))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].resourceId", equalTo(vpcId))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].trafficType", equalTo("ALL"))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].logDestinationType", equalTo("s3"))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].logDestination",
                    equalTo("arn:aws:s3:::flow-logs-test-bucket"))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].maxAggregationInterval", equalTo("60"));
    }

    @Test
    @Order(12)
    void deleteFlowLogsInAnotherRegionLeavesTheLogIntact() {
        given()
            .formParam("Action", "DeleteFlowLogs")
            .formParam("FlowLogId.1", flowLogId)
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260205/eu-west-1/ec2/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml");

        given()
            .formParam("Action", "DescribeFlowLogs")
            .formParam("FlowLogId.1", flowLogId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].flowLogId", equalTo(flowLogId));
    }

    @Test
    @Order(13)
    void deleteFlowLogs() {
        given()
            .formParam("Action", "DeleteFlowLogs")
            .formParam("FlowLogId.1", flowLogId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml");

        // After deletion, describing all flow logs must not return the deleted id.
        given()
            .formParam("Action", "DescribeFlowLogs")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeFlowLogsResponse.flowLogSet.findAll { it.flowLogId == '" + flowLogId + "' }.size()",
                    equalTo(0));
    }

    @Test
    @Order(20)
    void createCloudWatchFlowLogsWithTags() {
        flowLogId = given()
            .formParam("Action", "CreateFlowLogs")
            .formParam("ResourceType", "VPC")
            .formParam("ResourceId.1", vpcId)
            .formParam("TrafficType", "ALL")
            .formParam("LogDestinationType", "cloud-watch-logs")
            .formParam("LogGroupName", "/aws/vpc/flow-logs")
            .formParam("DeliverLogsPermissionArn", "arn:aws:iam::000000000000:role/flow")
            .formParam("TagSpecification.1.ResourceType", "vpc-flow-log")
            .formParam("TagSpecification.1.Tag.1.Key", "env")
            .formParam("TagSpecification.1.Tag.1.Value", "prod")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("CreateFlowLogsResponse.flowLogIdSet.item[0]", startsWith("fl-"))
            .extract().path("CreateFlowLogsResponse.flowLogIdSet.item[0]");
    }

    @Test
    @Order(21)
    void describeCloudWatchFlowLogsReturnsAlchemyShape() {
        given()
            .formParam("Action", "DescribeFlowLogs")
            .formParam("FlowLogId.1", flowLogId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].flowLogId", equalTo(flowLogId))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].resourceId", equalTo(vpcId))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].trafficType", equalTo("ALL"))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].logDestinationType", equalTo("cloud-watch-logs"))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].logGroupName", equalTo("/aws/vpc/flow-logs"))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].deliverLogsPermissionArn",
                    equalTo("arn:aws:iam::000000000000:role/flow"))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].tagSet.item.key", equalTo("env"))
            .body("DescribeFlowLogsResponse.flowLogSet.item[0].tagSet.item.value", equalTo("prod"));
    }

    @Test
    @Order(22)
    void describeTagsReturnsVpcFlowLogResourceType() {
        given()
            .formParam("Action", "DescribeTags")
            .formParam("Filter.1.Name", "resource-id")
            .formParam("Filter.1.Value.1", flowLogId)
            .formParam("Filter.2.Name", "resource-type")
            .formParam("Filter.2.Value.1", "vpc-flow-log")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeTagsResponse.tagSet.item.resourceId", equalTo(flowLogId))
            .body("DescribeTagsResponse.tagSet.item.resourceType", equalTo("vpc-flow-log"))
            .body("DescribeTagsResponse.tagSet.item.key", equalTo("env"))
            .body("DescribeTagsResponse.tagSet.item.value", equalTo("prod"));
    }
}
