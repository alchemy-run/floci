package io.github.hectorvent.floci.services.neptune;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NeptuneDbSubnetGroupIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String GROUP = "alchemy-neptune-subnets";

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260516/us-east-1/neptune/aws4_request, " +
            "SignedHeaders=content-type;host, Signature=test";

    @Test
    @Order(1)
    void describeMissingSubnetGroupFaults() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeDBSubnetGroups")
            .formParam("DBSubnetGroupName", "missing-neptune-subnets")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBSubnetGroupNotFoundFault"));
    }

    @Test
    @Order(2)
    void createSubnetGroup() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateDBSubnetGroup")
            .formParam("DBSubnetGroupName", GROUP)
            .formParam("DBSubnetGroupDescription", "alchemy neptune subnet group")
            .formParam("SubnetIds.SubnetIdentifier.1", "subnet-default-a")
            .formParam("SubnetIds.SubnetIdentifier.2", "subnet-default-b")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(GROUP))
            .body(containsString("alchemy neptune subnet group"))
            .body(containsString(":subgrp:"))
            .body(containsString("<VpcId>"))
            .body(containsString("subnet-default-a"))
            .body(containsString("subnet-default-b"));
    }

    @Test
    @Order(3)
    void createDuplicateFails() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateDBSubnetGroup")
            .formParam("DBSubnetGroupName", GROUP)
            .formParam("DBSubnetGroupDescription", "dup")
            .formParam("SubnetIds.SubnetIdentifier.1", "subnet-default-a")
            .formParam("SubnetIds.SubnetIdentifier.2", "subnet-default-b")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("DBSubnetGroupAlreadyExists"));
    }

    @Test
    @Order(4)
    void modifyUpdatesDescription() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "ModifyDBSubnetGroup")
            .formParam("DBSubnetGroupName", GROUP)
            .formParam("DBSubnetGroupDescription", "alchemy neptune subnet group v2")
            .formParam("SubnetIds.SubnetIdentifier.1", "subnet-default-a")
            .formParam("SubnetIds.SubnetIdentifier.2", "subnet-default-b")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("alchemy neptune subnet group v2"));
    }

    @Test
    @Order(5)
    void describeReturnsUpdatedDescription() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeDBSubnetGroups")
            .formParam("DBSubnetGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("alchemy neptune subnet group v2"))
            .body(not(containsString("alchemy neptune subnet group</DBSubnetGroupDescription>")));
    }

    @Test
    @Order(6)
    void deleteSubnetGroup() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DeleteDBSubnetGroup")
            .formParam("DBSubnetGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(7)
    void describeAfterDeleteFaults() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeDBSubnetGroups")
            .formParam("DBSubnetGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBSubnetGroupNotFoundFault"));
    }
}
