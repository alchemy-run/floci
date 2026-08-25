package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 1.1 MemoryDB subnet-group control plane used by Alchemy Cluster:
 * DescribeSubnetGroups (typed SubnetGroupNotFoundFault), Create/ListTags/Delete.
 */
@QuarkusTest
class MemoryDbSubnetGroupIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/memorydb/aws4_request";
    private static final String GROUP = "it-mdb-subnets";

    @Inject
    Ec2Service ec2Service;
    @Inject
    RegionResolver regionResolver;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeSubnetGroups_missingName_returnsSubnetGroupNotFoundFault() {
        memorydb("DescribeSubnetGroups", "{\"SubnetGroupName\":\"alchemy-nonexistent-subnet-group\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("SubnetGroupNotFoundFault"));
    }

    @Test
    void createDescribeTagAndDeleteSubnetGroup() {
        String region = regionResolver.getDefaultRegion();
        List<String> subnetIds = ec2Service.describeSubnets(region, List.of(),
                        Map.of("default-for-az", List.of("true")))
                .stream()
                .map(Subnet::getSubnetId)
                .toList();
        if (subnetIds.size() < 2) {
            subnetIds = ec2Service.describeSubnets(region, List.of(), Map.of()).stream()
                    .map(Subnet::getSubnetId)
                    .toList();
        }
        assertTrue(subnetIds.size() >= 2, "default VPC must expose at least two subnets");

        try {
            memorydb("DeleteSubnetGroup", "{\"SubnetGroupName\":\"" + GROUP + "\"}");
        } catch (Exception ignored) {
            // best-effort leftover cleanup
        }

        String createBody = "{"
                + "\"SubnetGroupName\":\"" + GROUP + "\","
                + "\"Description\":\"alchemy memorydb cluster subnets\","
                + "\"SubnetIds\":[\"" + subnetIds.get(0) + "\",\"" + subnetIds.get(1) + "\"],"
                + "\"Tags\":[{\"Key\":\"fixture\",\"Value\":\"memorydb-cluster\"}]"
                + "}";

        memorydb("CreateSubnetGroup", createBody)
                .then()
                .statusCode(200)
                .body("SubnetGroup.Name", equalTo(GROUP))
                .body("SubnetGroup.ARN", containsString(":subnetgroup/"))
                .body("SubnetGroup.Subnets.Identifier", hasItems(subnetIds.get(0), subnetIds.get(1)));

        String arn = memorydb("DescribeSubnetGroups", "{\"SubnetGroupName\":\"" + GROUP + "\"}")
                .then()
                .statusCode(200)
                .body("SubnetGroups[0].Name", equalTo(GROUP))
                .extract()
                .path("SubnetGroups[0].ARN");

        memorydb("ListTags", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("TagList.Key", hasItems("fixture"))
                .body("TagList.Value", hasItems("memorydb-cluster"));

        memorydb("DeleteSubnetGroup", "{\"SubnetGroupName\":\"" + GROUP + "\"}")
                .then()
                .statusCode(200)
                .body("SubnetGroup.Name", equalTo(GROUP));

        memorydb("DescribeSubnetGroups", "{\"SubnetGroupName\":\"" + GROUP + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("SubnetGroupNotFoundFault"));
    }

    private static Response memorydb(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonMemoryDB." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
