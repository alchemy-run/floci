package io.github.hectorvent.floci.services.dms;

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
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 1.1 coverage for DMS replication subnet groups — create, describe,
 * modify (description + subnet swap), tags, and delete.
 */
@QuarkusTest
class DmsReplicationSubnetGroupIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/dms/aws4_request";
    private static final String TARGET = "AmazonDMSv20160101.";

    @Inject
    Ec2Service ec2Service;

    @Inject
    RegionResolver regionResolver;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeReplicationSubnetGroups_emptyAndMissingFilter_emptyList() {
        dms("DescribeReplicationSubnetGroups", "{}")
                .then()
                .statusCode(200)
                .body("ReplicationSubnetGroups", notNullValue());

        dms("DescribeReplicationSubnetGroups", """
                {"Filters":[{"Name":"replication-subnet-group-id","Values":["missing-subgrp"]}]}
                """)
                .then()
                .statusCode(200)
                .body("ReplicationSubnetGroups", hasSize(0));
    }

    @Test
    void deleteReplicationSubnetGroup_missing_resourceNotFound() {
        dms("DeleteReplicationSubnetGroup",
                "{\"ReplicationSubnetGroupIdentifier\":\"missing-subgrp\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    @Test
    void createWithOneAz_doesNotCoverEnoughAZs() {
        List<String> subnetIds = defaultSubnetIds();
        assertTrue(subnetIds.size() >= 1, "default VPC must have at least one subnet");

        dms("CreateReplicationSubnetGroup", """
                {
                  "ReplicationSubnetGroupIdentifier": "it-dms-one-az",
                  "ReplicationSubnetGroupDescription": "single az",
                  "SubnetIds": ["%s"]
                }
                """.formatted(subnetIds.get(0)))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ReplicationSubnetGroupDoesNotCoverEnoughAZs"));
    }

    @Test
    void createDescribeModifyTagAndDelete_roundTrip() {
        List<String> subnetIds = defaultSubnetIds();
        assertTrue(subnetIds.size() >= 3, "default VPC must have at least 3 default-for-AZ subnets");

        String identifier = "it-dms-sg-" + UUID.randomUUID().toString().substring(0, 8);
        String two = jsonArray(subnetIds.get(0), subnetIds.get(1));
        String swapped = jsonArray(subnetIds.get(0), subnetIds.get(2));

        dms("CreateReplicationSubnetGroup", """
                {
                  "ReplicationSubnetGroupIdentifier": "%s",
                  "ReplicationSubnetGroupDescription": "alchemy dms test subnets",
                  "SubnetIds": %s,
                  "Tags": [{"Key": "env", "Value": "test"}]
                }
                """.formatted(identifier, two))
                .then()
                .statusCode(200)
                .body("ReplicationSubnetGroup.ReplicationSubnetGroupIdentifier", equalTo(identifier))
                .body("ReplicationSubnetGroup.ReplicationSubnetGroupDescription",
                        equalTo("alchemy dms test subnets"))
                .body("ReplicationSubnetGroup.SubnetGroupStatus", equalTo("Complete"))
                .body("ReplicationSubnetGroup.VpcId", notNullValue())
                .body("ReplicationSubnetGroup.Subnets", hasSize(2))
                .body("ReplicationSubnetGroup.Subnets.SubnetIdentifier",
                        hasItems(subnetIds.get(0), subnetIds.get(1)));

        dms("DescribeReplicationSubnetGroups", """
                {"Filters":[{"Name":"replication-subnet-group-id","Values":["%s"]}]}
                """.formatted(identifier))
                .then()
                .statusCode(200)
                .body("ReplicationSubnetGroups", hasSize(1))
                .body("ReplicationSubnetGroups[0].SubnetGroupStatus", equalTo("Complete"))
                .body("ReplicationSubnetGroups[0].Subnets", hasSize(2));

        String arn = regionResolver.buildArn("dms", regionResolver.getDefaultRegion(),
                "subgrp:" + identifier);
        dms("ListTagsForResource", "{\"ResourceArn\":\"%s\"}".formatted(arn))
                .then()
                .statusCode(200)
                .body("TagList.find { it.Key == 'env' }.Value", equalTo("test"));

        dms("ModifyReplicationSubnetGroup", """
                {
                  "ReplicationSubnetGroupIdentifier": "%s",
                  "ReplicationSubnetGroupDescription": "alchemy dms test subnets (updated)",
                  "SubnetIds": %s
                }
                """.formatted(identifier, swapped))
                .then()
                .statusCode(200)
                .body("ReplicationSubnetGroup.ReplicationSubnetGroupDescription",
                        equalTo("alchemy dms test subnets (updated)"))
                .body("ReplicationSubnetGroup.Subnets.SubnetIdentifier",
                        hasItems(subnetIds.get(0), subnetIds.get(2)));

        dms("DescribeReplicationSubnetGroups", """
                {"Filters":[{"Name":"replication-subnet-group-id","Values":["%s"]}]}
                """.formatted(identifier))
                .then()
                .statusCode(200)
                .body("ReplicationSubnetGroups[0].ReplicationSubnetGroupDescription",
                        equalTo("alchemy dms test subnets (updated)"))
                .body("ReplicationSubnetGroups[0].Subnets.SubnetIdentifier",
                        hasItems(subnetIds.get(2)));

        dms("AddTagsToResource", """
                {"ResourceArn":"%s","Tags":[{"Key":"team","Value":"data"}]}
                """.formatted(arn))
                .then()
                .statusCode(200);

        dms("RemoveTagsFromResource",
                "{\"ResourceArn\":\"%s\",\"TagKeys\":[\"env\"]}".formatted(arn))
                .then()
                .statusCode(200);

        dms("ListTagsForResource", "{\"ResourceArn\":\"%s\"}".formatted(arn))
                .then()
                .statusCode(200)
                .body("TagList", hasSize(1))
                .body("TagList[0].Key", equalTo("team"));

        dms("CreateReplicationSubnetGroup", """
                {
                  "ReplicationSubnetGroupIdentifier": "%s",
                  "ReplicationSubnetGroupDescription": "dup",
                  "SubnetIds": %s
                }
                """.formatted(identifier, two))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceAlreadyExistsFault"));

        dms("DeleteReplicationSubnetGroup",
                "{\"ReplicationSubnetGroupIdentifier\":\"%s\"}".formatted(identifier))
                .then()
                .statusCode(200);

        dms("DescribeReplicationSubnetGroups", """
                {"Filters":[{"Name":"replication-subnet-group-id","Values":["%s"]}]}
                """.formatted(identifier))
                .then()
                .statusCode(200)
                .body("ReplicationSubnetGroups", hasSize(0));
    }

    @Test
    void describeReplicationInstances_emptyList() {
        dms("DescribeReplicationInstances", "{}")
                .then()
                .statusCode(200)
                .body("ReplicationInstances", notNullValue());
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

    private static String jsonArray(String a, String b) {
        return "[\"" + a + "\",\"" + b + "\"]";
    }

    private static Response dms(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
