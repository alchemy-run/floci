package io.github.hectorvent.floci.services.personalize;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Personalize bindings coverage: schema/dataset/tracker round-trip, PutEvents /
 * PutItems, PutActions typed error, and missing-ARN probes.
 */
@QuarkusTest
class PersonalizeBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/personalize/aws4_request";
    private static final String INTERACTIONS_SCHEMA = "{"
            + "\"type\":\"record\",\"name\":\"Interactions\","
            + "\"namespace\":\"com.amazonaws.personalize.schema\","
            + "\"fields\":["
            + "{\"name\":\"USER_ID\",\"type\":\"string\"},"
            + "{\"name\":\"ITEM_ID\",\"type\":\"string\"},"
            + "{\"name\":\"TIMESTAMP\",\"type\":\"long\"}"
            + "],\"version\":\"1.0\"}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeSchema_missingArn_returnsResourceNotFoundException() {
        personalize("DescribeSchema",
                "{\"schemaArn\":\"arn:aws:personalize:us-east-1:000000000000:schema/does_not_exist\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void schemaDatasetGroupDatasetAndTracker_roundTrip() {
        String schemaArn = personalize("CreateSchema",
                "{\"name\":\"BindingsInteractions\",\"schema\":" + quote(INTERACTIONS_SCHEMA) + "}")
                .then()
                .statusCode(200)
                .body("schemaArn", notNullValue())
                .extract().path("schemaArn");

        personalize("DescribeSchema", "{\"schemaArn\":\"" + schemaArn + "\"}")
                .then()
                .statusCode(200)
                .body("schema.name", equalTo("BindingsInteractions"))
                .body("schema.schema", equalTo(INTERACTIONS_SCHEMA));

        String groupArn = personalize("CreateDatasetGroup", "{\"name\":\"BindingsGroup\"}")
                .then()
                .statusCode(200)
                .body("datasetGroupArn", notNullValue())
                .extract().path("datasetGroupArn");

        personalize("DescribeDatasetGroup", "{\"datasetGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200)
                .body("datasetGroup.status", equalTo("ACTIVE"));

        String datasetArn = personalize("CreateDataset", "{"
                + "\"name\":\"BindingsInteractionsDs\","
                + "\"schemaArn\":\"" + schemaArn + "\","
                + "\"datasetGroupArn\":\"" + groupArn + "\","
                + "\"datasetType\":\"Interactions\","
                + "\"tags\":[{\"tagKey\":\"alchemy::id\",\"tagValue\":\"Interactions\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("datasetArn", notNullValue())
                .extract().path("datasetArn");

        personalize("DescribeDataset", "{\"datasetArn\":\"" + datasetArn + "\"}")
                .then()
                .statusCode(200)
                .body("dataset.datasetType", equalTo("INTERACTIONS"))
                .body("dataset.status", equalTo("ACTIVE"));

        String trackerArn = personalize("CreateEventTracker", "{"
                + "\"name\":\"BindingsTracker\","
                + "\"datasetGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200)
                .body("eventTrackerArn", notNullValue())
                .body("trackingId", notNullValue())
                .extract().path("eventTrackerArn");

        String trackingId = personalize("DescribeEventTracker",
                "{\"eventTrackerArn\":\"" + trackerArn + "\"}")
                .then()
                .statusCode(200)
                .body("eventTracker.status", equalTo("ACTIVE"))
                .extract().path("eventTracker.trackingId");

        events("/events", "{"
                + "\"trackingId\":\"" + trackingId + "\","
                + "\"userId\":\"alchemy-user-1\","
                + "\"sessionId\":\"alchemy-session-1\","
                + "\"eventList\":[{\"eventType\":\"click\",\"itemId\":\"alchemy-item-1\",\"sentAt\":1700000000}]"
                + "}")
                .then()
                .statusCode(200);

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

        personalize("DescribeSchema", "{\"schemaArn\":\"" + schemaArn + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void putItemsOnItemsDataset_andPutActionsOnItemsDataset_typedError() {
        String itemsSchemaArn = personalize("CreateSchema",
                "{\"name\":\"BindingsItemsSchema\",\"schema\":\"{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"Items\\\"}\"}")
                .then()
                .statusCode(200)
                .extract().path("schemaArn");
        String groupArn = personalize("CreateDatasetGroup", "{\"name\":\"BindingsItemsGroup\"}")
                .then()
                .statusCode(200)
                .extract().path("datasetGroupArn");
        String itemsArn = personalize("CreateDataset", "{"
                + "\"name\":\"BindingsItems\","
                + "\"schemaArn\":\"" + itemsSchemaArn + "\","
                + "\"datasetGroupArn\":\"" + groupArn + "\","
                + "\"datasetType\":\"Items\"}")
                .then()
                .statusCode(200)
                .extract().path("datasetArn");

        events("/items", "{\"datasetArn\":\"" + itemsArn
                + "\",\"items\":[{\"itemId\":\"alchemy-item-1\",\"properties\":\"{\\\"category\\\":\\\"books\\\"}\"}]}")
                .then()
                .statusCode(200);

        events("/actions", "{\"datasetArn\":\"" + itemsArn
                + "\",\"actions\":[{\"actionId\":\"alchemy-action-1\"}]}")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("InvalidInputException"))
                .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    void putUsersOnUsersDataset_returns200() {
        String usersSchemaArn = personalize("CreateSchema",
                "{\"name\":\"BindingsUsersSchema\",\"schema\":\"{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"Users\\\"}\"}")
                .then()
                .statusCode(200)
                .extract().path("schemaArn");
        String groupArn = personalize("CreateDatasetGroup", "{\"name\":\"BindingsUsersGroup\"}")
                .then()
                .statusCode(200)
                .extract().path("datasetGroupArn");
        String usersArn = personalize("CreateDataset", "{"
                + "\"name\":\"BindingsUsers\","
                + "\"schemaArn\":\"" + usersSchemaArn + "\","
                + "\"datasetGroupArn\":\"" + groupArn + "\","
                + "\"datasetType\":\"Users\"}")
                .then()
                .statusCode(200)
                .extract().path("datasetArn");

        events("/users", "{\"datasetArn\":\"" + usersArn
                + "\",\"users\":[{\"userId\":\"alchemy-user-1\",\"properties\":\"{\\\"membership\\\":\\\"gold\\\"}\"}]}")
                .then()
                .statusCode(200);
    }

    @Test
    void bindingProbes_missingArns_returnResourceNotFoundException() {
        String campaign = "arn:aws:personalize:us-east-1:000000000000:campaign/alchemy-probe";
        String dataset = "arn:aws:personalize:us-east-1:000000000000:dataset/alchemy-probe/INTERACTIONS";
        String importJob = "arn:aws:personalize:us-east-1:000000000000:dataset-import-job/alchemy-probe";
        String group = "arn:aws:personalize:us-east-1:000000000000:dataset-group/alchemy-probe";
        String solution = "arn:aws:personalize:us-east-1:000000000000:solution/alchemy-probe";
        String version = "arn:aws:personalize:us-east-1:000000000000:solution/alchemy-probe/0123456789abcdef";
        String batch = "arn:aws:personalize:us-east-1:000000000000:batch-inference-job/alchemy-probe";
        String role = "arn:aws:iam::000000000000:role/alchemy-nonexistent-probe-role";

        assertNotFound("DescribeDatasetImportJob", "{\"datasetImportJobArn\":\"" + importJob + "\"}");
        assertNotFound("CreateDatasetImportJob", "{"
                + "\"jobName\":\"alchemy-import-probe\","
                + "\"datasetArn\":\"" + dataset + "\","
                + "\"dataSource\":{\"dataLocation\":\"s3://alchemy-nonexistent-probe-bucket/data.csv\"},"
                + "\"roleArn\":\"" + role + "\"}");
        assertNotFound("CreateSolution", "{"
                + "\"name\":\"alchemy-solution-probe\","
                + "\"recipeArn\":\"arn:aws:personalize:::recipe/aws-user-personalization\","
                + "\"datasetGroupArn\":\"" + group + "\"}");
        assertNotFound("CreateSolutionVersion", "{\"solutionArn\":\"" + solution + "\"}");
        assertNotFound("DescribeSolutionVersion", "{\"solutionVersionArn\":\"" + version + "\"}");
        assertNotFound("GetSolutionMetrics", "{\"solutionVersionArn\":\"" + version + "\"}");
        assertNotFound("CreateCampaign", "{"
                + "\"name\":\"alchemy-campaign-probe\","
                + "\"solutionVersionArn\":\"" + version + "\"}");
        assertNotFound("UpdateCampaign", "{\"campaignArn\":\"" + campaign + "\"}");
        assertNotFound("DescribeCampaign", "{\"campaignArn\":\"" + campaign + "\"}");
        assertNotFound("CreateBatchInferenceJob", "{"
                + "\"jobName\":\"alchemy-batch-probe\","
                + "\"solutionVersionArn\":\"" + version + "\","
                + "\"jobInput\":{\"s3DataSource\":{\"path\":\"s3://alchemy-nonexistent-probe-bucket/users.json\"}},"
                + "\"jobOutput\":{\"s3DataDestination\":{\"path\":\"s3://alchemy-nonexistent-probe-bucket/scores/\"}},"
                + "\"roleArn\":\"" + role + "\"}");
        assertNotFound("DescribeBatchInferenceJob", "{\"batchInferenceJobArn\":\"" + batch + "\"}");

        runtime("/recommendations", "{\"campaignArn\":\"" + campaign + "\",\"userId\":\"alchemy-user-1\"}")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
        runtime("/personalize-ranking", "{\"campaignArn\":\"" + campaign
                + "\",\"userId\":\"alchemy-user-1\",\"inputList\":[\"alchemy-item-1\"]}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        runtime("/action-recommendations", "{\"campaignArn\":\"" + campaign + "\",\"userId\":\"alchemy-user-1\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void putActionInteractions_withoutTracker_returnsResourceNotFoundException() {
        events("/action-interactions", "{"
                + "\"trackingId\":\"missing-tracker\","
                + "\"actionInteractions\":[{"
                + "\"actionId\":\"alchemy-action-1\","
                + "\"userId\":\"alchemy-user-1\","
                + "\"sessionId\":\"alchemy-session-1\","
                + "\"eventType\":\"Taken\","
                + "\"timestamp\":1700000000}]}")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void putActionInteractions_trackerWithoutActionInteractionsDataset_returnsResourceNotFoundException() {
        String schemaArn = personalize("CreateSchema",
                "{\"name\":\"BindingsActionIxSchema\",\"schema\":" + quote(INTERACTIONS_SCHEMA) + "}")
                .then()
                .statusCode(200)
                .extract().path("schemaArn");
        String groupArn = personalize("CreateDatasetGroup", "{\"name\":\"BindingsActionIxGroup\"}")
                .then()
                .statusCode(200)
                .extract().path("datasetGroupArn");
        personalize("CreateDataset", "{"
                + "\"name\":\"BindingsActionIxDs\","
                + "\"schemaArn\":\"" + schemaArn + "\","
                + "\"datasetGroupArn\":\"" + groupArn + "\","
                + "\"datasetType\":\"Interactions\"}")
                .then()
                .statusCode(200);
        String trackerArn = personalize("CreateEventTracker", "{"
                + "\"name\":\"BindingsActionIxTracker\","
                + "\"datasetGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200)
                .extract().path("eventTrackerArn");
        String trackingId = personalize("DescribeEventTracker",
                "{\"eventTrackerArn\":\"" + trackerArn + "\"}")
                .then()
                .statusCode(200)
                .extract().path("eventTracker.trackingId");

        events("/action-interactions", "{"
                + "\"trackingId\":\"" + trackingId + "\","
                + "\"actionInteractions\":[{"
                + "\"actionId\":\"alchemy-action-1\","
                + "\"userId\":\"alchemy-user-1\","
                + "\"sessionId\":\"alchemy-session-1\","
                + "\"eventType\":\"Taken\","
                + "\"timestamp\":1700000000}]}")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static void assertNotFound(String action, String body) {
        personalize(action, body)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
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

    private static Response events(String path, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post(path);
    }

    private static Response runtime(String path, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post(path);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
