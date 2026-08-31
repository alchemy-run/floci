package io.github.hectorvent.floci.services.personalize;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 Personalize coverage used by Alchemy Schema / DatasetGroup /
 * Dataset / EventTracker: DescribeDatasetGroup typed not-found, schema +
 * group + dataset + tracker CRUD, tags, and delete.
 */
@QuarkusTest
class PersonalizeIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/personalize/aws4_request";
    private static final String INTERACTIONS_SCHEMA = "{"
            + "\"type\":\"record\","
            + "\"name\":\"Interactions\","
            + "\"namespace\":\"com.amazonaws.personalize.schema\","
            + "\"fields\":["
            + "{\"name\":\"USER_ID\",\"type\":\"string\"},"
            + "{\"name\":\"ITEM_ID\",\"type\":\"string\"},"
            + "{\"name\":\"TIMESTAMP\",\"type\":\"long\"}"
            + "],"
            + "\"version\":\"1.0\"}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeDatasetGroup_missingArn_returnsResourceNotFoundException() {
        personalize("DescribeDatasetGroup",
                "{\"datasetGroupArn\":\"arn:aws:personalize:us-east-1:000000000000:dataset-group/does-not-exist\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void schemaDatasetGroupDatasetAndTracker_lifecycleTagsAndDelete() {
        String schemaArn = personalize("CreateSchema", "{"
                + "\"name\":\"Interactions\","
                + "\"schema\":" + jsonString(INTERACTIONS_SCHEMA)
                + "}")
                .then()
                .statusCode(200)
                .body("schemaArn", notNullValue())
                .extract().path("schemaArn");

        personalize("DescribeSchema", "{\"schemaArn\":\"" + schemaArn + "\"}")
                .then()
                .statusCode(200)
                .body("schema.schemaArn", equalTo(schemaArn))
                .body("schema.name", equalTo("Interactions"));

        String groupArn = personalize("CreateDatasetGroup", "{"
                + "\"name\":\"Group\","
                + "\"tags\":[{\"tagKey\":\"Environment\",\"tagValue\":\"test\"},{\"tagKey\":\"alchemy::id\",\"tagValue\":\"Group\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("datasetGroupArn", notNullValue())
                .extract().path("datasetGroupArn");

        personalize("DescribeDatasetGroup", "{\"datasetGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200)
                .body("datasetGroup.status", equalTo("ACTIVE"))
                .body("datasetGroup.name", equalTo("Group"));

        personalize("ListTagsForResource", "{\"resourceArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200)
                .body("tags.tagKey", hasItem("alchemy::id"));

        String datasetArn = personalize("CreateDataset", "{"
                + "\"name\":\"Dataset\","
                + "\"schemaArn\":\"" + schemaArn + "\","
                + "\"datasetGroupArn\":\"" + groupArn + "\","
                + "\"datasetType\":\"Interactions\","
                + "\"tags\":[{\"tagKey\":\"Environment\",\"tagValue\":\"test\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("datasetArn", notNullValue())
                .extract().path("datasetArn");

        personalize("DescribeDataset", "{\"datasetArn\":\"" + datasetArn + "\"}")
                .then()
                .statusCode(200)
                .body("dataset.datasetType", equalTo("INTERACTIONS"))
                .body("dataset.schemaArn", equalTo(schemaArn))
                .body("dataset.status", equalTo("ACTIVE"));

        String trackerArn = personalize("CreateEventTracker", "{"
                + "\"name\":\"Tracker\","
                + "\"datasetGroupArn\":\"" + groupArn + "\","
                + "\"tags\":[{\"tagKey\":\"Environment\",\"tagValue\":\"test\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("eventTrackerArn", notNullValue())
                .body("trackingId", notNullValue())
                .extract().path("eventTrackerArn");

        personalize("DescribeEventTracker", "{\"eventTrackerArn\":\"" + trackerArn + "\"}")
                .then()
                .statusCode(200)
                .body("eventTracker.status", equalTo("ACTIVE"))
                .body("eventTracker.datasetGroupArn", equalTo(groupArn))
                .body("eventTracker.trackingId", notNullValue());

        personalize("TagResource",
                "{\"resourceArn\":\"" + groupArn + "\",\"tags\":[{\"tagKey\":\"Extra\",\"tagValue\":\"yes\"}]}")
                .then()
                .statusCode(200);
        personalize("ListTagsForResource", "{\"resourceArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200)
                .body("tags.tagKey", hasItem("Extra"));

        personalize("TagResource",
                "{\"resourceArn\":\"" + trackerArn + "\",\"tags\":[{\"tagKey\":\"Extra\",\"tagValue\":\"yes\"}]}")
                .then()
                .statusCode(200);
        personalize("ListTagsForResource", "{\"resourceArn\":\"" + trackerArn + "\"}")
                .then()
                .statusCode(200)
                .body("tags.tagKey", hasItem("Extra"));

        personalize("DeleteEventTracker", "{\"eventTrackerArn\":\"" + trackerArn + "\"}")
                .then()
                .statusCode(200);
        personalize("DeleteDataset", "{\"datasetArn\":\"" + datasetArn + "\"}")
                .then()
                .statusCode(200);
        personalize("DeleteDatasetGroup", "{\"datasetGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200);
        personalize("DeleteSchema", "{\"schemaArn\":\"" + schemaArn + "\"}")
                .then()
                .statusCode(200);

        personalize("DescribeDatasetGroup", "{\"datasetGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        personalize("DescribeDataset", "{\"datasetArn\":\"" + datasetArn + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        personalize("DescribeEventTracker", "{\"eventTrackerArn\":\"" + trackerArn + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Response personalize(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonPersonalize." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
