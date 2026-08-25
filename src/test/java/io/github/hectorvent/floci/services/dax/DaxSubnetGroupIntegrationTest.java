package io.github.hectorvent.floci.services.dax;

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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DaxSubnetGroupIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET = "AmazonDAXV3.";

    @Inject
    Ec2Service ec2Service;

    @Inject
    RegionResolver regionResolver;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeSubnetGroups_missingName_subnetGroupNotFoundFault() {
        invoke("DescribeSubnetGroups",
                "{\"SubnetGroupNames\":[\"alchemy-nonexistent-dax-subnet-probe\"]}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("SubnetGroupNotFoundFault"));
    }

    @Test
    void createUpdateDeleteSubnetGroup_roundTripsDescriptionAndSubnets() {
        List<String> subnetIds = defaultSubnetIds();
        assertTrue(subnetIds.size() >= 3, "default VPC must have at least 3 default-for-AZ subnets");

        String name = "dax-sg-roundtrip";
        String two = "[\"" + subnetIds.get(0) + "\",\"" + subnetIds.get(1) + "\"]";
        String three = "[\"" + subnetIds.get(0) + "\",\"" + subnetIds.get(1) + "\",\""
                + subnetIds.get(2) + "\"]";

        invoke("CreateSubnetGroup", """
                {
                  "SubnetGroupName": "%s",
                  "Description": "alchemy dax subnet group",
                  "SubnetIds": %s
                }
                """.formatted(name, two))
                .then()
                .statusCode(200)
                .body("SubnetGroup.SubnetGroupName", equalTo(name))
                .body("SubnetGroup.Description", equalTo("alchemy dax subnet group"))
                .body("SubnetGroup.VpcId", notNullValue())
                .body("SubnetGroup.Subnets", hasSize(2));

        invoke("DescribeSubnetGroups", "{\"SubnetGroupNames\":[\"" + name + "\"]}")
                .then()
                .statusCode(200)
                .body("SubnetGroups", hasSize(1))
                .body("SubnetGroups[0].Description", equalTo("alchemy dax subnet group"))
                .body("SubnetGroups[0].VpcId", notNullValue())
                .body("SubnetGroups[0].Subnets.SubnetIdentifier",
                        hasItems(subnetIds.get(0), subnetIds.get(1)));

        invoke("UpdateSubnetGroup", """
                {
                  "SubnetGroupName": "%s",
                  "Description": "alchemy dax subnet group v2",
                  "SubnetIds": %s
                }
                """.formatted(name, three))
                .then()
                .statusCode(200)
                .body("SubnetGroup.Description", equalTo("alchemy dax subnet group v2"))
                .body("SubnetGroup.Subnets", hasSize(3));

        invoke("DescribeSubnetGroups", "{\"SubnetGroupNames\":[\"" + name + "\"]}")
                .then()
                .statusCode(200)
                .body("SubnetGroups[0].Description", equalTo("alchemy dax subnet group v2"))
                .body("SubnetGroups[0].Subnets", hasSize(3));

        invoke("DeleteSubnetGroup", "{\"SubnetGroupName\":\"" + name + "\"}")
                .then()
                .statusCode(200);

        invoke("DescribeSubnetGroups", "{\"SubnetGroupNames\":[\"" + name + "\"]}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("SubnetGroupNotFoundFault"));

        invoke("DeleteSubnetGroup", "{\"SubnetGroupName\":\"" + name + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("SubnetGroupNotFoundFault"));
    }

    private List<String> defaultSubnetIds() {
        String region = regionResolver.getDefaultRegion();
        return ec2Service.describeSubnets(region, List.of(),
                        Map.of("default-for-az", List.of("true")))
                .stream()
                .map(Subnet::getSubnetId)
                .sorted()
                .toList();
    }

    private static Response invoke(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .body(body)
                .when()
                .post("/");
    }
}
