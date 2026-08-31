package io.github.hectorvent.floci.services.kendra;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 Kendra coverage used by Alchemy Bindings:
 * Query typed not-found, index CRUD, document ingest/search, and the
 * remaining binding operations against a missing index.
 */
@QuarkusTest
class KendraIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/kendra/aws4_request";
    private static final String MISSING = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void query_missingIndex_returnsResourceNotFoundException() {
        kendra("Query", "{\"IndexId\":\"" + MISSING + "\",\"QueryText\":\"probe\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void bindingProbes_missingIndex_returnResourceNotFoundException() {
        assertNotFound("Retrieve", "{\"IndexId\":\"" + MISSING + "\",\"QueryText\":\"probe\"}");
        assertNotFound("GetQuerySuggestions", "{\"IndexId\":\"" + MISSING + "\",\"QueryText\":\"probe\"}");
        assertNotFound("SubmitFeedback",
                "{\"IndexId\":\"" + MISSING + "\",\"QueryId\":\"probe-query-id\","
                        + "\"ClickFeedbackItems\":[{\"ResultId\":\"probe-result\",\"ClickTime\":1}]}");
        assertNotFound("BatchPutDocument",
                "{\"IndexId\":\"" + MISSING + "\",\"Documents\":[{\"Id\":\"probe\",\"ContentType\":\"PLAIN_TEXT\"}]}");
        assertNotFound("BatchDeleteDocument",
                "{\"IndexId\":\"" + MISSING + "\",\"DocumentIdList\":[\"probe\"]}");
        assertNotFound("BatchGetDocumentStatus",
                "{\"IndexId\":\"" + MISSING + "\",\"DocumentInfoList\":[{\"DocumentId\":\"probe\"}]}");
        assertNotFound("GetSnapshots",
                "{\"IndexId\":\"" + MISSING + "\",\"Interval\":\"ONE_WEEK_AGO\",\"MetricType\":\"QUERIES_BY_COUNT\"}");
        assertNotFound("PutPrincipalMapping",
                "{\"IndexId\":\"" + MISSING + "\",\"GroupId\":\"probe\","
                        + "\"GroupMembers\":{\"MemberUsers\":[{\"UserId\":\"probe@example.com\"}]}}");
        assertNotFound("DeletePrincipalMapping",
                "{\"IndexId\":\"" + MISSING + "\",\"GroupId\":\"probe\"}");
        assertNotFound("DescribePrincipalMapping",
                "{\"IndexId\":\"" + MISSING + "\",\"GroupId\":\"probe\"}");
        assertNotFound("ListGroupsOlderThanOrderingId",
                "{\"IndexId\":\"" + MISSING + "\",\"OrderingId\":1}");
        assertNotFound("ClearQuerySuggestions", "{\"IndexId\":\"" + MISSING + "\"}");
        assertNotFound("DescribeQuerySuggestionsConfig", "{\"IndexId\":\"" + MISSING + "\"}");
        assertNotFound("UpdateQuerySuggestionsConfig",
                "{\"IndexId\":\"" + MISSING + "\",\"Mode\":\"LEARN_ONLY\"}");
        assertNotFound("CreateAccessControlConfiguration",
                "{\"IndexId\":\"" + MISSING + "\",\"Name\":\"probe\"}");
        assertNotFound("DescribeAccessControlConfiguration",
                "{\"IndexId\":\"" + MISSING + "\",\"Id\":\"probe\"}");
        assertNotFound("UpdateAccessControlConfiguration",
                "{\"IndexId\":\"" + MISSING + "\",\"Id\":\"probe\"}");
        assertNotFound("DeleteAccessControlConfiguration",
                "{\"IndexId\":\"" + MISSING + "\",\"Id\":\"probe\"}");
        assertNotFound("ListAccessControlConfigurations", "{\"IndexId\":\"" + MISSING + "\"}");
        assertNotFound("StartDataSourceSyncJob",
                "{\"IndexId\":\"" + MISSING + "\",\"Id\":\"" + MISSING + "\"}");
        assertNotFound("StopDataSourceSyncJob",
                "{\"IndexId\":\"" + MISSING + "\",\"Id\":\"" + MISSING + "\"}");
        assertNotFound("ListDataSourceSyncJobs",
                "{\"IndexId\":\"" + MISSING + "\",\"Id\":\"" + MISSING + "\"}");
        assertNotFound("DescribeIndex", "{\"Id\":\"" + MISSING + "\"}");
        assertNotFound("DescribeDataSource",
                "{\"IndexId\":\"" + MISSING + "\",\"Id\":\"" + MISSING + "\"}");
    }

    @Test
    void indexDocumentQueryAndBindings_roundTrip() {
        String indexId = kendra("CreateIndex", "{"
                + "\"Name\":\"AlchemyKendraBindings\","
                + "\"Edition\":\"DEVELOPER_EDITION\","
                + "\"RoleArn\":\"arn:aws:iam::000000000000:role/KendraRole\","
                + "\"Description\":\"bindings\","
                + "\"Tags\":[{\"Key\":\"Environment\",\"Value\":\"test\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .extract().path("Id");

        kendra("DescribeIndex", "{\"Id\":\"" + indexId + "\"}")
                .then()
                .statusCode(200)
                .body("Name", equalTo("AlchemyKendraBindings"))
                .body("Edition", equalTo("DEVELOPER_EDITION"))
                .body("Status", equalTo("ACTIVE"));

        String blob = Base64.getEncoder().encodeToString(
                "Alchemy is an Infrastructure-as-Effects framework. The zanzibar passphrase is quicksilver."
                        .getBytes(StandardCharsets.UTF_8));
        kendra("BatchPutDocument", "{"
                + "\"IndexId\":\"" + indexId + "\","
                + "\"Documents\":[{\"Id\":\"welcome\",\"Title\":\"Welcome to Alchemy\","
                + "\"Blob\":\"" + blob + "\",\"ContentType\":\"PLAIN_TEXT\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("FailedDocuments.size()", equalTo(0));

        kendra("BatchGetDocumentStatus",
                "{\"IndexId\":\"" + indexId + "\",\"DocumentInfoList\":[{\"DocumentId\":\"welcome\"}]}")
                .then()
                .statusCode(200)
                .body("DocumentStatusList[0].DocumentStatus", equalTo("INDEXED"));

        kendra("Query", "{\"IndexId\":\"" + indexId + "\",\"QueryText\":\"zanzibar\"}")
                .then()
                .statusCode(200)
                .body("QueryId", notNullValue())
                .body("TotalNumberOfResults", greaterThanOrEqualTo(1))
                .body("ResultItems[0].DocumentId", equalTo("welcome"));

        kendra("Retrieve", "{\"IndexId\":\"" + indexId + "\",\"QueryText\":\"zanzibar\"}")
                .then()
                .statusCode(200)
                .body("ResultItems.size()", greaterThanOrEqualTo(1));

        kendra("GetQuerySuggestions", "{\"IndexId\":\"" + indexId + "\",\"QueryText\":\"zanzi\"}")
                .then()
                .statusCode(200);

        kendra("DescribeQuerySuggestionsConfig", "{\"IndexId\":\"" + indexId + "\"}")
                .then()
                .statusCode(200)
                .body("Mode", notNullValue());

        kendra("UpdateQuerySuggestionsConfig",
                "{\"IndexId\":\"" + indexId + "\",\"Mode\":\"LEARN_ONLY\"}")
                .then()
                .statusCode(200);
        kendra("ClearQuerySuggestions", "{\"IndexId\":\"" + indexId + "\"}")
                .then()
                .statusCode(200);

        String aclId = kendra("CreateAccessControlConfiguration", "{"
                + "\"IndexId\":\"" + indexId + "\","
                + "\"Name\":\"block-departed-users\","
                + "\"AccessControlList\":[{\"Name\":\"departed-user\",\"Type\":\"USER\",\"Access\":\"DENY\"}]"
                + "}")
                .then()
                .statusCode(200)
                .extract().path("Id");
        kendra("DescribeAccessControlConfiguration",
                "{\"IndexId\":\"" + indexId + "\",\"Id\":\"" + aclId + "\"}")
                .then()
                .statusCode(200)
                .body("Name", equalTo("block-departed-users"));
        kendra("ListAccessControlConfigurations", "{\"IndexId\":\"" + indexId + "\"}")
                .then()
                .statusCode(200)
                .body("AccessControlConfigurations.Id", hasItem(aclId));
        kendra("DeleteAccessControlConfiguration",
                "{\"IndexId\":\"" + indexId + "\",\"Id\":\"" + aclId + "\"}")
                .then()
                .statusCode(200);

        kendra("PutPrincipalMapping", "{"
                + "\"IndexId\":\"" + indexId + "\","
                + "\"GroupId\":\"engineering\","
                + "\"GroupMembers\":{\"MemberUsers\":[{\"UserId\":\"user@example.com\"}]}"
                + "}")
                .then()
                .statusCode(200);
        kendra("DescribePrincipalMapping",
                "{\"IndexId\":\"" + indexId + "\",\"GroupId\":\"engineering\"}")
                .then()
                .statusCode(200)
                .body("GroupOrderingIdSummaries.size()", greaterThanOrEqualTo(1));
        kendra("DeletePrincipalMapping",
                "{\"IndexId\":\"" + indexId + "\",\"GroupId\":\"engineering\"}")
                .then()
                .statusCode(200);

        String dataSourceId = kendra("CreateDataSource", "{"
                + "\"IndexId\":\"" + indexId + "\","
                + "\"Name\":\"Docs\","
                + "\"Type\":\"S3\","
                + "\"RoleArn\":\"arn:aws:iam::000000000000:role/KendraDataRole\","
                + "\"Configuration\":{\"S3Configuration\":{\"BucketName\":\"kendra-docs\"}}"
                + "}")
                .then()
                .statusCode(200)
                .extract().path("Id");
        kendra("StartDataSourceSyncJob",
                "{\"IndexId\":\"" + indexId + "\",\"Id\":\"" + dataSourceId + "\"}")
                .then()
                .statusCode(200)
                .body("ExecutionId", notNullValue());
        kendra("ListDataSourceSyncJobs",
                "{\"IndexId\":\"" + indexId + "\",\"Id\":\"" + dataSourceId + "\"}")
                .then()
                .statusCode(200)
                .body("History.size()", greaterThanOrEqualTo(1));

        kendra("GetSnapshots",
                "{\"IndexId\":\"" + indexId + "\",\"Interval\":\"ONE_WEEK_AGO\",\"MetricType\":\"QUERIES_BY_COUNT\"}")
                .then()
                .statusCode(200)
                .body("SnapshotsDataHeader", notNullValue());

        kendra("BatchDeleteDocument",
                "{\"IndexId\":\"" + indexId + "\",\"DocumentIdList\":[\"welcome\"]}")
                .then()
                .statusCode(200)
                .body("FailedDocuments.size()", equalTo(0));

        kendra("DeleteDataSource",
                "{\"IndexId\":\"" + indexId + "\",\"Id\":\"" + dataSourceId + "\"}")
                .then()
                .statusCode(200);
        kendra("DeleteIndex", "{\"Id\":\"" + indexId + "\"}")
                .then()
                .statusCode(200);
        assertNotFound("DescribeIndex", "{\"Id\":\"" + indexId + "\"}");
    }

    private static void assertNotFound(String action, String body) {
        kendra(action, body)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static Response kendra(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSKendraFrontendService." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
