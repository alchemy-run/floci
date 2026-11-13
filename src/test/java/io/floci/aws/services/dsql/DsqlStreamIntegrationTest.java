package io.floci.aws.services.dsql;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Aurora DSQL CDC streams: create, get, list, tag, delete, and what creation checks. */
@QuarkusTest
class DsqlStreamIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/dsql/aws4_request";
    private static final String ROLE = "arn:aws:iam::000000000000:role/cdc";
    private static final String KINESIS_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/kinesis/aws4_request";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification json(String body) {
        return given().contentType("application/json").header("Authorization", AUTH).body(body);
    }

    @Test
    void aStreamIntoKinesisIsCreatedReadTaggedAndDeleted() {
        String clusterId = json("{\"deletionProtectionEnabled\":false}").when().post("/cluster").then()
            .statusCode(200).extract().path("identifier");
        given().contentType("application/x-amz-json-1.1").header("Authorization", KINESIS_AUTH)
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .body("{\"StreamName\":\"cdc-target\",\"ShardCount\":1}")
            .when().post("/").then().statusCode(200);
        String target = given().contentType("application/x-amz-json-1.1").header("Authorization", KINESIS_AUTH)
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStreamSummary")
            .body("{\"StreamName\":\"cdc-target\"}")
            .when().post("/").then().statusCode(200)
            .extract().path("StreamDescriptionSummary.StreamARN");

        String body = """
                {"targetDefinition":{"kinesis":{"streamArn":"%s","roleArn":"%s"}},
                 "ordering":"UNORDERED","format":"JSON","tags":{"app":"alchemy"}}""".formatted(target, ROLE);
        String streamId = json(body).when().post("/stream/" + clusterId).then()
            .statusCode(200)
            .body("status", equalTo("CREATING"))
            .body("arn", containsString(":cluster/" + clusterId + "/stream/"))
            .extract().path("streamIdentifier");
        String arn = given().header("Authorization", AUTH).when().get("/stream/" + clusterId + "/" + streamId).then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"))
            .body("targetDefinition.kinesis.streamArn", equalTo(target))
            .body("tags.app", equalTo("alchemy"))
            .extract().path("arn");
        given().header("Authorization", AUTH).when().get("/stream/" + clusterId).then()
            .statusCode(200).body("streams.streamIdentifier", contains(streamId));

        json("{\"tags\":{\"team\":\"data\"}}").when().post("/tags/" + arn).then().statusCode(200);
        given().header("Authorization", AUTH).when().get("/tags/" + arn).then()
            .statusCode(200).body("tags.team", equalTo("data")).body("tags.app", equalTo("alchemy"));

        json(body.replace("UNORDERED", "ORDERED")).when().post("/stream/" + clusterId).then()
            .statusCode(400).body("__type", containsString("ValidationException"));
        json(body.replace(target, target + "-missing")).when().post("/stream/" + clusterId).then()
            .statusCode(400).body("__type", containsString("ValidationException"));
        json(body).when().post("/stream/aaaaaaaaaaaaaaaaaaaaaaaaaa").then().statusCode(404);

        given().header("Authorization", AUTH).when().delete("/stream/" + clusterId + "/" + streamId).then()
            .statusCode(200).body("status", equalTo("DELETING"));
        given().header("Authorization", AUTH).when().get("/stream/" + clusterId + "/" + streamId).then()
            .statusCode(404).body("__type", containsString("ResourceNotFoundException"));
    }
}
