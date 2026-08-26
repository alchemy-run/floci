package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * SageMaker Feature Store bindings: FeatureGroup CRUD plus Put/Get/Delete/
 * BatchWrite/BatchGet/ListRecords on the online store.
 */
@QuarkusTest
class SageMakerBindingsIntegrationTest {

    private static final String JSON11 = "application/x-amz-json-1.1";
    private static final String JSON = "application/json";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sagemaker/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeFeatureGroup_missing_returnsResourceNotFound() {
        sagemaker("DescribeFeatureGroup",
                "{\"FeatureGroupName\":\"alchemy-nonexistent-feature-group-probe\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFound"));
    }

    @Test
    void featureGroupAndOnlineStore_roundTrip() {
        String name = "BindingsFeatures";
        String create = "{"
                + "\"FeatureGroupName\":\"" + name + "\","
                + "\"RecordIdentifierFeatureName\":\"user_id\","
                + "\"EventTimeFeatureName\":\"event_time\","
                + "\"FeatureDefinitions\":["
                + "{\"FeatureName\":\"user_id\",\"FeatureType\":\"String\"},"
                + "{\"FeatureName\":\"event_time\",\"FeatureType\":\"String\"},"
                + "{\"FeatureName\":\"clicks\",\"FeatureType\":\"Integral\"}"
                + "],"
                + "\"OnlineStoreConfig\":{\"EnableOnlineStore\":true},"
                + "\"Tags\":[{\"Key\":\"alchemy::id\",\"Value\":\"BindingsFeatures\"}]"
                + "}";

        String arn = sagemaker("CreateFeatureGroup", create)
                .then()
                .statusCode(200)
                .body("FeatureGroupArn", notNullValue())
                .extract().path("FeatureGroupArn");

        sagemaker("DescribeFeatureGroup", "{\"FeatureGroupName\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("FeatureGroupName", equalTo(name))
                .body("FeatureGroupStatus", equalTo("Created"))
                .body("RecordIdentifierFeatureName", equalTo("user_id"))
                .body("EventTimeFeatureName", equalTo("event_time"))
                .body("OnlineStoreConfig.EnableOnlineStore", equalTo(true));

        sagemaker("ListFeatureGroups", "{}")
                .then()
                .statusCode(200)
                .body("FeatureGroupSummaries.FeatureGroupName", hasItem(name));

        sagemaker("ListTags", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("alchemy::id"));

        String putBody = record("user-roundtrip-1", "42");
        given()
                .contentType(JSON)
                .header("Authorization", AUTH)
                .body(putBody)
                .when()
                .put("/FeatureGroup/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .queryParam("RecordIdentifierValueAsString", "user-roundtrip-1")
                .when()
                .get("/FeatureGroup/" + name)
                .then()
                .statusCode(200)
                .body("Record.FeatureName", hasItem("user_id"))
                .body("Record.ValueAsString", hasItem("user-roundtrip-1"))
                .body("Record.ValueAsString", hasItem("42"));

        given()
                .header("Authorization", AUTH)
                .queryParam("RecordIdentifierValueAsString", "user-never-written")
                .when()
                .get("/FeatureGroup/" + name)
                .then()
                .statusCode(200)
                .body("Record", hasSize(0));

        given()
                .contentType(JSON)
                .header("Authorization", AUTH)
                .body(record("user-delete-1", "1"))
                .when()
                .put("/FeatureGroup/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .header("Host", "featurestore-runtime.sagemaker.us-east-1.amazonaws.com")
                .queryParam("RecordIdentifierValueAsString", "user-delete-1")
                .queryParam("EventTime", "2026-08-26T00:00:00Z")
                .when()
                .delete("/FeatureGroup/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .queryParam("RecordIdentifierValueAsString", "user-delete-1")
                .when()
                .get("/FeatureGroup/" + name)
                .then()
                .statusCode(200)
                .body("Record", hasSize(0));

        given()
                .contentType(JSON)
                .header("Authorization", AUTH)
                .header("Host", "featurestore-runtime.sagemaker.us-east-1.amazonaws.com")
                .body("{\"Entries\":["
                        + "{\"FeatureGroupName\":\"" + name + "\",\"Record\":"
                        + features("user-batch-1", "11") + "},"
                        + "{\"FeatureGroupName\":\"" + name + "\",\"Record\":"
                        + features("user-batch-2", "22") + "}"
                        + "]}")
                .when()
                .post("/BatchWriteRecord")
                .then()
                .statusCode(200)
                .body("Errors", hasSize(0))
                .body("UnprocessedEntries", hasSize(0));

        given()
                .contentType(JSON)
                .header("Authorization", AUTH)
                .body("{\"Identifiers\":[{\"FeatureGroupName\":\"" + name
                        + "\",\"RecordIdentifiersValueAsString\":"
                        + "[\"user-batch-1\",\"user-batch-2\",\"user-batch-missing\"]}]}")
                .when()
                .post("/BatchGetRecord")
                .then()
                .statusCode(200)
                .body("Errors", hasSize(0))
                .body("Records.RecordIdentifierValueAsString", hasItem("user-batch-1"))
                .body("Records.RecordIdentifierValueAsString", hasItem("user-batch-2"));

        given()
                .contentType(JSON)
                .header("Authorization", AUTH)
                .header("Host", "featurestore-runtime.sagemaker.us-east-1.amazonaws.com")
                .body("{\"MaxResults\":100}")
                .when()
                .post("/FeatureGroup/" + name + "/ListRecords")
                .then()
                .statusCode(200)
                .body("RecordIdentifiers", hasItem("user-roundtrip-1"))
                .body("RecordIdentifiers", hasItem("user-batch-1"));

        given()
                .header("Authorization", AUTH)
                .queryParam("RecordIdentifierValueAsString", "user-roundtrip-1")
                .when()
                .delete("/FeatureGroup/" + name)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationError"));

        sagemaker("DeleteFeatureGroup", "{\"FeatureGroupName\":\"" + name + "\"}")
                .then()
                .statusCode(200);
        sagemaker("DescribeFeatureGroup", "{\"FeatureGroupName\":\"" + name + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFound"));
    }

    private static Response sagemaker(String action, String body) {
        return given()
                .contentType(JSON11)
                .header("X-Amz-Target", "SageMaker." + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }

    private static String record(String userId, String clicks) {
        return "{\"Record\":" + features(userId, clicks) + "}";
    }

    private static String features(String userId, String clicks) {
        return "["
                + "{\"FeatureName\":\"user_id\",\"ValueAsString\":\"" + userId + "\"},"
                + "{\"FeatureName\":\"event_time\",\"ValueAsString\":\"2026-01-01T00:00:00Z\"},"
                + "{\"FeatureName\":\"clicks\",\"ValueAsString\":\"" + clicks + "\"}"
                + "]";
    }
}
