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
class RedshiftClusterSubnetGroupIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String GROUP = "alchemy-redshift-sng-it";
    private static final String ARN =
            "arn:aws:redshift:us-east-1:000000000000:subnetgroup:" + GROUP;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/redshift/aws4_request, "
                    + "SignedHeaders=content-type;host, Signature=test";

    @Test
    @Order(1)
    void describeMissingFaultsWithClusterSubnetGroupNotFoundFault() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeClusterSubnetGroups")
            .formParam("ClusterSubnetGroupName", "alchemy-nonexistent-redshift-sng-probe")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("ClusterSubnetGroupNotFoundFault"));
    }

    @Test
    @Order(2)
    void createWithDistilledListEncodingAndTags() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateClusterSubnetGroup")
            .formParam("ClusterSubnetGroupName", GROUP)
            .formParam("Description", "alchemy redshift subnet group")
            .formParam("SubnetIds.SubnetIdentifier.1", "subnet-default-a")
            .formParam("Tags.Tag.1.Key", "fixture")
            .formParam("Tags.Tag.1.Value", "redshift-subnet-group")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(GROUP))
            .body(containsString("alchemy redshift subnet group"))
            .body(containsString("Complete"))
            .body(containsString("subnet-default-a"))
            .body(containsString("vpc-default"))
            .body(containsString("fixture"))
            .body(containsString("redshift-subnet-group"));
    }

    @Test
    @Order(3)
    void createDuplicateFails() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateClusterSubnetGroup")
            .formParam("ClusterSubnetGroupName", GROUP)
            .formParam("Description", "dup")
            .formParam("SubnetIds.SubnetIdentifier.1", "subnet-default-a")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("ClusterSubnetGroupAlreadyExists"));
    }

    @Test
    @Order(4)
    void describeReturnsTagsAndCompleteStatus() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeClusterSubnetGroups")
            .formParam("ClusterSubnetGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubnetGroupStatus>Complete</SubnetGroupStatus>"))
            .body(containsString("fixture"))
            .body(containsString("subnet-default-a"));
    }

    @Test
    @Order(5)
    void modifyExpandsSubnetsAndDescription() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "ModifyClusterSubnetGroup")
            .formParam("ClusterSubnetGroupName", GROUP)
            .formParam("Description", "alchemy redshift subnet group (updated)")
            .formParam("SubnetIds.SubnetIdentifier.1", "subnet-default-a")
            .formParam("SubnetIds.SubnetIdentifier.2", "subnet-default-b")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("alchemy redshift subnet group (updated)"))
            .body(containsString("subnet-default-a"))
            .body(containsString("subnet-default-b"));
    }

    @Test
    @Order(6)
    void createTagsAndDeleteTagsWithDistilledEncoding() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateTags")
            .formParam("ResourceName", ARN)
            .formParam("Tags.Tag.1.Key", "stage")
            .formParam("Tags.Tag.1.Value", "updated")
        .when().post("/")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DeleteTags")
            .formParam("ResourceName", ARN)
            .formParam("TagKeys.TagKey.1", "fixture")
        .when().post("/")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeClusterSubnetGroups")
            .formParam("ClusterSubnetGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("stage"))
            .body(containsString("updated"))
            .body(not(containsString("fixture")))
            .body(containsString("subnet-default-b"));
    }

    @Test
    @Order(7)
    void deleteAndDescribeGone() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DeleteClusterSubnetGroup")
            .formParam("ClusterSubnetGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeClusterSubnetGroups")
            .formParam("ClusterSubnetGroupName", GROUP)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("ClusterSubnetGroupNotFoundFault"));
    }
}
