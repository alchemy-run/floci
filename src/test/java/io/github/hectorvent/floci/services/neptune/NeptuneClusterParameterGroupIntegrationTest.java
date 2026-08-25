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
class NeptuneClusterParameterGroupIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String GROUP = "floci-neptune-cpg-it";
    private static final String AUTH_NEPTUNE =
            "AWS4-HMAC-SHA256 Credential=test/20260516/us-east-1/neptune/aws4_request, " +
            "SignedHeaders=content-type;host, Signature=test";
    private static final String AUTH_RDS =
            "AWS4-HMAC-SHA256 Credential=test/20260516/us-east-1/rds/aws4_request, " +
            "SignedHeaders=content-type;host, Signature=test";

    @Test
    @Order(1)
    void createRequiresName() {
        given()
            .header("Authorization", AUTH_NEPTUNE)
            .contentType(FORM)
            .formParam("Action", "CreateDBClusterParameterGroup")
            .formParam("DBParameterGroupFamily", "neptune1.4")
            .formParam("Description", "missing name")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("DBClusterParameterGroupName is required"));
    }

    @Test
    @Order(2)
    void createClusterParameterGroup() {
        given()
            .header("Authorization", AUTH_NEPTUNE)
            .contentType(FORM)
            .formParam("Action", "CreateDBClusterParameterGroup")
            .formParam("DBClusterParameterGroupName", GROUP)
            .formParam("DBParameterGroupFamily", "neptune1.4")
            .formParam("Description", "alchemy neptune cluster params")
            .formParam("Tags.member.1.Key", "fixture")
            .formParam("Tags.member.1.Value", "neptune-cluster-params")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBClusterParameterGroupName>" + GROUP + "</DBClusterParameterGroupName>"))
            .body(containsString("<DBParameterGroupFamily>neptune1.4</DBParameterGroupFamily>"))
            .body(containsString(":cluster-pg:" + GROUP));
    }

    @Test
    @Order(3)
    void createDuplicateFails() {
        given()
            .header("Authorization", AUTH_NEPTUNE)
            .contentType(FORM)
            .formParam("Action", "CreateDBClusterParameterGroup")
            .formParam("DBClusterParameterGroupName", GROUP)
            .formParam("DBParameterGroupFamily", "neptune1.4")
            .formParam("Description", "dup")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("DBParameterGroupAlreadyExists"));
    }

    @Test
    @Order(4)
    void rdsSignedCreateWithNeptuneFamilyRoutesToNeptune() {
        given()
            .header("Authorization", AUTH_RDS)
            .contentType(FORM)
            .formParam("Action", "CreateDBClusterParameterGroup")
            .formParam("DBClusterParameterGroupName", GROUP)
            .formParam("DBParameterGroupFamily", "neptune1.4")
            .formParam("Description", "dup via rds signing")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("DBParameterGroupAlreadyExists"));
    }

    @Test
    @Order(5)
    void describeGroup() {
        given()
            .header("Authorization", AUTH_NEPTUNE)
            .contentType(FORM)
            .formParam("Action", "DescribeDBClusterParameterGroups")
            .formParam("DBClusterParameterGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(GROUP))
            .body(containsString("neptune1.4"))
            .body(containsString(":cluster-pg:"));
    }

    @Test
    @Order(6)
    void describeMissingGroup() {
        given()
            .header("Authorization", AUTH_NEPTUNE)
            .contentType(FORM)
            .formParam("Action", "DescribeDBClusterParameterGroups")
            .formParam("DBClusterParameterGroupName", "does-not-exist-cpg")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBParameterGroupNotFound"));
    }

    @Test
    @Order(7)
    void describeUserParametersInitiallyEmpty() {
        given()
            .header("Authorization", AUTH_NEPTUNE)
            .contentType(FORM)
            .formParam("Action", "DescribeDBClusterParameters")
            .formParam("DBClusterParameterGroupName", GROUP)
            .formParam("Source", "user")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("neptune_query_timeout")));
    }

    @Test
    @Order(8)
    void modifyParameter() {
        given()
            .header("Authorization", AUTH_NEPTUNE)
            .contentType(FORM)
            .formParam("Action", "ModifyDBClusterParameterGroup")
            .formParam("DBClusterParameterGroupName", GROUP)
            .formParam("Parameters.Parameter.1.ParameterName", "neptune_query_timeout")
            .formParam("Parameters.Parameter.1.ParameterValue", "180000")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(GROUP));
    }

    @Test
    @Order(9)
    void describeUserParametersAfterModify() {
        given()
            .header("Authorization", AUTH_NEPTUNE)
            .contentType(FORM)
            .formParam("Action", "DescribeDBClusterParameters")
            .formParam("DBClusterParameterGroupName", GROUP)
            .formParam("Source", "user")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ParameterName>neptune_query_timeout</ParameterName>"))
            .body(containsString("<ParameterValue>180000</ParameterValue>"))
            .body(containsString("<Source>user</Source>"));
    }

    @Test
    @Order(10)
    void resetParameter() {
        given()
            .header("Authorization", AUTH_NEPTUNE)
            .contentType(FORM)
            .formParam("Action", "ResetDBClusterParameterGroup")
            .formParam("DBClusterParameterGroupName", GROUP)
            .formParam("ResetAllParameters", "false")
            .formParam("Parameters.Parameter.1.ParameterName", "neptune_query_timeout")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(GROUP));
    }

    @Test
    @Order(11)
    void describeUserParametersAfterReset() {
        given()
            .header("Authorization", AUTH_NEPTUNE)
            .contentType(FORM)
            .formParam("Action", "DescribeDBClusterParameters")
            .formParam("DBClusterParameterGroupName", GROUP)
            .formParam("Source", "user")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("neptune_query_timeout")));
    }

    @Test
    @Order(12)
    void listTagsIncludesCreateTags() {
        given()
            .header("Authorization", AUTH_NEPTUNE)
            .contentType(FORM)
            .formParam("Action", "ListTagsForResource")
            .formParam("ResourceName",
                    "arn:aws:rds:us-east-1:000000000000:cluster-pg:" + GROUP)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Key>fixture</Key>"))
            .body(containsString("<Value>neptune-cluster-params</Value>"));
    }

    @Test
    @Order(13)
    void deleteGroup() {
        given()
            .header("Authorization", AUTH_NEPTUNE)
            .contentType(FORM)
            .formParam("Action", "DeleteDBClusterParameterGroup")
            .formParam("DBClusterParameterGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(14)
    void describeAfterDelete() {
        given()
            .header("Authorization", AUTH_NEPTUNE)
            .contentType(FORM)
            .formParam("Action", "DescribeDBClusterParameterGroups")
            .formParam("DBClusterParameterGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBParameterGroupNotFound"));
    }
}
