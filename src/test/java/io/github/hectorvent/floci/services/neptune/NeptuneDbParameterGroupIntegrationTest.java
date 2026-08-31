package io.github.hectorvent.floci.services.neptune;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Query-protocol coverage for Alchemy {@code test/AWS/Neptune/DBParameterGroup.test.ts}:
 * create, modify user parameters, reset, tag, and delete a Neptune instance
 * parameter group. Distilled Neptune signs with the {@code rds} credential
 * scope, so the rds-scope cases are the path the live suite takes.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NeptuneDbParameterGroupIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String GROUP = "floci-neptune-instance-pg";
    private static final String FAMILY = "neptune1.4";
    private static final String ARN = "arn:aws:rds:us-east-1:000000000000:pg:" + GROUP;

    private static final String AUTH_NEPTUNE =
            "AWS4-HMAC-SHA256 Credential=test/20260516/us-east-1/neptune/aws4_request, " +
            "SignedHeaders=content-type;host, Signature=test";
    private static final String AUTH_RDS =
            "AWS4-HMAC-SHA256 Credential=test/20260516/us-east-1/rds/aws4_request, " +
            "SignedHeaders=content-type;host, Signature=test";

    private static RequestSpecification rds(String action) {
        return given()
                .header("Authorization", AUTH_RDS)
                .contentType(FORM)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static RequestSpecification neptune(String action) {
        return given()
                .header("Authorization", AUTH_NEPTUNE)
                .contentType(FORM)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @Test
    @Order(1)
    void createParameterGroup_rdsScope() {
        rds("CreateDBParameterGroup")
            .formParam("DBParameterGroupName", GROUP)
            .formParam("DBParameterGroupFamily", FAMILY)
            .formParam("Description", "alchemy neptune instance params")
            .formParam("Tags.member.1.Key", "fixture")
            .formParam("Tags.member.1.Value", "neptune-instance-params")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(GROUP))
            .body(containsString(FAMILY))
            .body(containsString(":pg:" + GROUP));
    }

    @Test
    @Order(2)
    void createDuplicateFails() {
        rds("CreateDBParameterGroup")
            .formParam("DBParameterGroupName", GROUP)
            .formParam("DBParameterGroupFamily", FAMILY)
            .formParam("Description", "dup")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("DBParameterGroupAlreadyExists"));
    }

    @Test
    @Order(3)
    void describeByName() {
        rds("DescribeDBParameterGroups")
            .formParam("DBParameterGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(GROUP))
            .body(containsString(FAMILY))
            .body(not(containsString("<member>")));
    }

    @Test
    @Order(4)
    void modifyAndDescribeUserParameters() {
        rds("ModifyDBParameterGroup")
            .formParam("DBParameterGroupName", GROUP)
            .formParam("Parameters.Parameter.1.ParameterName", "neptune_query_timeout")
            .formParam("Parameters.Parameter.1.ParameterValue", "180000")
            .formParam("Parameters.Parameter.1.ApplyMethod", "immediate")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(GROUP));

        rds("DescribeDBParameters")
            .formParam("DBParameterGroupName", GROUP)
            .formParam("Source", "user")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("neptune_query_timeout"))
            .body(containsString("180000"))
            .body(containsString("<Source>user</Source>"));
    }

    @Test
    @Order(5)
    void modifyUpdatesValue() {
        rds("ModifyDBParameterGroup")
            .formParam("DBParameterGroupName", GROUP)
            .formParam("Parameters.Parameter.1.ParameterName", "neptune_query_timeout")
            .formParam("Parameters.Parameter.1.ParameterValue", "60000")
        .when().post("/")
        .then()
            .statusCode(200);

        rds("DescribeDBParameters")
            .formParam("DBParameterGroupName", GROUP)
            .formParam("Source", "user")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("60000"));
    }

    @Test
    @Order(6)
    void resetRemovesUserOverride() {
        rds("ResetDBParameterGroup")
            .formParam("DBParameterGroupName", GROUP)
            .formParam("ResetAllParameters", "false")
            .formParam("Parameters.Parameter.1.ParameterName", "neptune_query_timeout")
            .formParam("Parameters.Parameter.1.ApplyMethod", "immediate")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(GROUP));

        rds("DescribeDBParameters")
            .formParam("DBParameterGroupName", GROUP)
            .formParam("Source", "user")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("neptune_query_timeout")));
    }

    @Test
    @Order(7)
    void tagsRoundTrip() {
        rds("AddTagsToResource")
            .formParam("ResourceName", ARN)
            .formParam("Tags.member.1.Key", "owner")
            .formParam("Tags.member.1.Value", "alchemy")
        .when().post("/")
        .then()
            .statusCode(200);

        rds("ListTagsForResource")
            .formParam("ResourceName", ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("fixture"))
            .body(containsString("owner"))
            .body(containsString("alchemy"));
    }

    @Test
    @Order(8)
    void deleteAndDescribeMissing() {
        rds("DeleteDBParameterGroup")
            .formParam("DBParameterGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(200);

        rds("DescribeDBParameterGroups")
            .formParam("DBParameterGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBParameterGroupNotFound"));
    }

    @Test
    @Order(9)
    void neptuneScopeCreateModifyDelete() {
        String name = GROUP + "-neptune-scope";
        neptune("CreateDBParameterGroup")
            .formParam("DBParameterGroupName", name)
            .formParam("DBParameterGroupFamily", FAMILY)
            .formParam("Description", "neptune-scope")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(name));

        neptune("DeleteDBParameterGroup")
            .formParam("DBParameterGroupName", name)
        .when().post("/")
        .then()
            .statusCode(200);
    }
}
