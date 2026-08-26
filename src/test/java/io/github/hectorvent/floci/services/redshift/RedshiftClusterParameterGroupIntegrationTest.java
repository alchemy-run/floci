package io.github.hectorvent.floci.services.redshift;

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
class RedshiftClusterParameterGroupIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String GROUP = "floci-redshift-cpg-it";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260516/us-east-1/redshift/aws4_request, "
                    + "SignedHeaders=content-type;host, Signature=test";

    @Test
    @Order(1)
    void describeMissingGroup() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeClusterParameterGroups")
            .formParam("ParameterGroupName", "alchemy-nonexistent-redshift-cpg-probe")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("ClusterParameterGroupNotFound"));
    }

    @Test
    @Order(2)
    void createRequiresName() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateClusterParameterGroup")
            .formParam("ParameterGroupFamily", "redshift-1.0")
            .formParam("Description", "missing name")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("ParameterGroupName is required"));
    }

    @Test
    @Order(3)
    void createWithTags() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateClusterParameterGroup")
            .formParam("ParameterGroupName", GROUP)
            .formParam("ParameterGroupFamily", "redshift-1.0")
            .formParam("Description", "Managed by Alchemy")
            .formParam("Tags.Tag.1.Key", "fixture")
            .formParam("Tags.Tag.1.Value", "redshift-parameter-group")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ParameterGroupName>" + GROUP + "</ParameterGroupName>"))
            .body(containsString("<ParameterGroupFamily>redshift-1.0</ParameterGroupFamily>"))
            .body(containsString("<Key>fixture</Key>"))
            .body(containsString("<Value>redshift-parameter-group</Value>"));
    }

    @Test
    @Order(4)
    void createDuplicateFails() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateClusterParameterGroup")
            .formParam("ParameterGroupName", GROUP)
            .formParam("ParameterGroupFamily", "redshift-1.0")
            .formParam("Description", "dup")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("ClusterParameterGroupAlreadyExists"));
    }

    @Test
    @Order(5)
    void describeGroupIncludesTags() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeClusterParameterGroups")
            .formParam("ParameterGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(GROUP))
            .body(containsString("redshift-1.0"))
            .body(containsString("<Key>fixture</Key>"))
            .body(containsString("<Value>redshift-parameter-group</Value>"));
    }

    @Test
    @Order(6)
    void describeUserParametersInitiallyEmpty() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeClusterParameters")
            .formParam("ParameterGroupName", GROUP)
            .formParam("Source", "user")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("enable_user_activity_logging")));
    }

    @Test
    @Order(7)
    void modifyParameters() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "ModifyClusterParameterGroup")
            .formParam("ParameterGroupName", GROUP)
            .formParam("Parameters.Parameter.1.ParameterName", "enable_user_activity_logging")
            .formParam("Parameters.Parameter.1.ParameterValue", "true")
            .formParam("Parameters.Parameter.2.ParameterName", "statement_timeout")
            .formParam("Parameters.Parameter.2.ParameterValue", "60000")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(GROUP));
    }

    @Test
    @Order(8)
    void describeUserParametersAfterModify() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeClusterParameters")
            .formParam("ParameterGroupName", GROUP)
            .formParam("Source", "user")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ParameterName>enable_user_activity_logging</ParameterName>"))
            .body(containsString("<ParameterValue>true</ParameterValue>"))
            .body(containsString("<ParameterName>statement_timeout</ParameterName>"))
            .body(containsString("<ParameterValue>60000</ParameterValue>"))
            .body(containsString("<Source>user</Source>"));
    }

    @Test
    @Order(9)
    void resetDroppedParameter() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "ResetClusterParameterGroup")
            .formParam("ParameterGroupName", GROUP)
            .formParam("ResetAllParameters", "false")
            .formParam("Parameters.Parameter.1.ParameterName", "enable_user_activity_logging")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(GROUP));
    }

    @Test
    @Order(10)
    void describeUserParametersAfterReset() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeClusterParameters")
            .formParam("ParameterGroupName", GROUP)
            .formParam("Source", "user")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("enable_user_activity_logging")))
            .body(containsString("<ParameterName>statement_timeout</ParameterName>"));
    }

    @Test
    @Order(11)
    void createTagsOnParameterGroupArn() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateTags")
            .formParam("ResourceName", "arn:aws:redshift:us-east-1:000000000000:parametergroup:" + GROUP)
            .formParam("Tags.Tag.1.Key", "extra")
            .formParam("Tags.Tag.1.Value", "yes")
        .when().post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(12)
    void deleteGroup() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DeleteClusterParameterGroup")
            .formParam("ParameterGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(13)
    void describeAfterDelete() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeClusterParameterGroups")
            .formParam("ParameterGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("ClusterParameterGroupNotFound"));
    }
}
