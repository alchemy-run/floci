package io.github.hectorvent.floci.services.bcmdataexports;

import io.github.hectorvent.floci.services.bcmdataexports.model.ExportExecution;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the BCM Data Exports management plane.
 *
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1,
 * X-Amz-Target: AWSBillingAndCostManagementDataExports.&lt;Action&gt;
 */
@QuarkusTest
@TestProfile(BcmDataExportsIntegrationTest.IsolatedProfile.class)
class BcmDataExportsIntegrationTest {

    @Inject
    BcmDataExportsService service;

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bcm-data-exports/aws4_request";

    public static final class IsolatedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.storage.mode", "memory", "floci.services.bcm-data-exports.emit-mode", "off");
        }
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String validExportBody(String name) {
        return "{\"Export\":{" +
                "\"Name\":\"" + name + "\"," +
                "\"Description\":\"daily focus export\"," +
                "\"DataQuery\":{\"QueryStatement\":\"SELECT * FROM COST_AND_USAGE_REPORT\"}," +
                "\"DestinationConfigurations\":{\"S3Destination\":{" +
                  "\"S3Bucket\":\"my-bucket\",\"S3Prefix\":\"out/\",\"S3Region\":\"us-east-1\"," +
                  "\"S3OutputConfigurations\":{\"Format\":\"PARQUET\",\"Compression\":\"PARQUET\",\"OutputType\":\"CUSTOM\",\"Overwrite\":\"OVERWRITE_REPORT\"}}}," +
                "\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";
    }

    private static String createExportAndReturnArn(String name) {
        return given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(validExportBody(name))
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ExportArn", notNullValue())
            .extract().path("ExportArn");
    }

    @Test
    void createExport_succeeds() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(validExportBody("create-success"))
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ExportArn", containsString("export/create-success"));
    }

    @Test
    void createExport_duplicate_returnsValidation() {
        createExportAndReturnArn("dup-export");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(validExportBody("dup-export"))
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createExport_invalidName_returnsValidation() {
        String body = validExportBody("bad name with spaces");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createExport_missingDataQuery_returnsValidation() {
        String body = "{\"Export\":{" +
                "\"Name\":\"no-query\"," +
                "\"DestinationConfigurations\":{\"S3Destination\":{" +
                  "\"S3Bucket\":\"b\",\"S3Region\":\"us-east-1\"}}," +
                "\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createExport_invalidFrequency_returnsValidation() {
        String body = "{\"Export\":{" +
                "\"Name\":\"bad-cadence\"," +
                "\"DataQuery\":{\"QueryStatement\":\"SELECT 1\"}," +
                "\"DestinationConfigurations\":{\"S3Destination\":{" +
                  "\"S3Bucket\":\"b\",\"S3Region\":\"us-east-1\"}}," +
                "\"RefreshCadence\":{\"Frequency\":\"DAILY\"}}}";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getExport_returnsCreated() {
        String arn = createExportAndReturnArn("get-target");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.GetExport")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"" + arn + "\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Export.Name", equalTo("get-target"))
            .body("Export.RefreshCadence.Frequency", equalTo("SYNCHRONOUS"));
    }

    @Test
    void getExport_notFound_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.GetExport")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"arn:aws:bcm-data-exports:us-east-1:000000000000:export/missing\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listExports_includesCreated() {
        createExportAndReturnArn("listed-export");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.ListExports")
            .header("Authorization", AUTH).body("{}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Exports.ExportName", hasItem("listed-export"));
    }

    @Test
    void updateExport_replacesDescription() {
        String arn = createExportAndReturnArn("upd-target");
        String body = "{\"ExportArn\":\"" + arn + "\"," +
                "\"Export\":{" +
                "\"Name\":\"upd-target\"," +
                "\"Description\":\"updated description\"," +
                "\"DataQuery\":{\"QueryStatement\":\"SELECT 2\"}," +
                "\"DestinationConfigurations\":{\"S3Destination\":{\"S3Bucket\":\"new-bucket\",\"S3Region\":\"us-east-1\"}}," +
                "\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.UpdateExport")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ExportArn", equalTo(arn));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.GetExport")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"" + arn + "\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Export.Description", equalTo("updated description"))
            .body("Export.DestinationConfigurations.S3Destination.S3Bucket", equalTo("new-bucket"));
    }

    @Test
    void updateExport_notFound_returns400() {
        String body = "{\"ExportArn\":\"arn:aws:bcm-data-exports:us-east-1:000000000000:export/missing\"," +
                "\"Export\":{\"Name\":\"missing\"," +
                "\"DataQuery\":{\"QueryStatement\":\"SELECT 1\"}," +
                "\"DestinationConfigurations\":{\"S3Destination\":{\"S3Bucket\":\"valid-bucket\",\"S3Region\":\"us-east-1\"}}," +
                "\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.UpdateExport")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteExport_removes() {
        String arn = createExportAndReturnArn("del-target");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.DeleteExport")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"" + arn + "\"}")
        .when().post("/")
        .then().statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.GetExport")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"" + arn + "\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteExport_unknownArn_returns200() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.DeleteExport")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"arn:aws:bcm-data-exports:us-east-1:000000000000:export/never-was\"}")
        .when().post("/")
        .then().statusCode(200);
    }

    @Test
    void listExecutions_emptyForFreshExport() {
        String arn = createExportAndReturnArn("exec-list");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.ListExecutions")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"" + arn + "\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Executions", hasSize(0));
    }

    @Test
    void listExecutions_unknownExport_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.ListExecutions")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"arn:aws:bcm-data-exports:us-east-1:000000000000:export/missing\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getExecution_unknownIds_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.GetExecution")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"arn:aws:bcm-data-exports:us-east-1:000000000000:export/missing\",\"ExecutionId\":\"x\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createExport_invalidBucketName_returnsValidation() {
        String body = "{\"Export\":{" +
                "\"Name\":\"bad-bucket\"," +
                "\"DataQuery\":{\"QueryStatement\":\"SELECT 1\"}," +
                "\"DestinationConfigurations\":{\"S3Destination\":{" +
                  "\"S3Bucket\":\"InvalidUpper\",\"S3Region\":\"us-east-1\"}}," +
                "\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createExport_quoteInS3Prefix_returnsValidation() {
        String body = "{\"Export\":{" +
                "\"Name\":\"quote-prefix\"," +
                "\"DataQuery\":{\"QueryStatement\":\"SELECT 1\"}," +
                "\"DestinationConfigurations\":{\"S3Destination\":{" +
                  "\"S3Bucket\":\"valid-bucket\"," +
                  "\"S3Prefix\":\"out'); DROP DATABASE; --\"," +
                  "\"S3Region\":\"us-east-1\"}}," +
                "\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createExport_textOrCsvFormat_persistsWithoutExecution() {
        String body = "{\"Export\":{" +
                "\"Name\":\"csv-attempt\"," +
                "\"DataQuery\":{\"QueryStatement\":\"SELECT 1\"}," +
                "\"DestinationConfigurations\":{\"S3Destination\":{" +
                  "\"S3Bucket\":\"valid-bucket\",\"S3Region\":\"us-east-1\"," +
                  "\"S3OutputConfigurations\":{\"Format\":\"TEXT_OR_CSV\",\"Compression\":\"GZIP\",\"OutputType\":\"CUSTOM\"}}}," +
                "\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";
        String arn = request("CreateExport", body).statusCode(200).extract().path("ExportArn");
        request("GetExport", "{\"ExportArn\":\"" + arn + "\"}").statusCode(200)
                .body("Export.DestinationConfigurations.S3Destination.S3OutputConfigurations.Format", equalTo("TEXT_OR_CSV"))
                .body("Export.DestinationConfigurations.S3Destination.S3OutputConfigurations.Compression", equalTo("GZIP"));
        request("ListExecutions", "{\"ExportArn\":\"" + arn + "\"}").statusCode(200)
                .body("Executions", empty());
    }

    @Test
    void metadataAndTags_surviveUpdates() {
        String definition = validExportBody("metadata-export")
                .replace("\"S3Region\":\"us-east-1\"", "\"S3Region\":\"us-east-1\",\"S3BucketOwner\":\"123456789012\"")
                .replace("\"QueryStatement\":\"SELECT * FROM COST_AND_USAGE_REPORT\"",
                        "\"QueryStatement\":\"SELECT identity_line_item_id FROM COST_AND_USAGE_REPORT\","
                                + "\"TableConfigurations\":{\"COST_AND_USAGE_REPORT\":{\"TIME_GRANULARITY\":\"HOURLY\"}}");
        String tagged = definition.substring(0, definition.length() - 1)
                + ",\"ResourceTags\":[{\"Key\":\"fixture\",\"Value\":\"bcm\"},{\"Key\":\"remove\",\"Value\":\"yes\"}]}";
        String arn = request("CreateExport", tagged).statusCode(200).extract().path("ExportArn");
        String resource = "\"ResourceArn\":\"" + arn + "\"";
        request("TagResource", "{" + resource + ",\"ResourceTags\":[{\"Key\":\"phase\",\"Value\":\"two\"}]}")
                .statusCode(200);
        request("UntagResource", "{" + resource + ",\"ResourceTagKeys\":[\"remove\",\"absent\"]}").statusCode(200);
        String updated = "{\"ExportArn\":\"" + arn + "\"," + definition.substring(1)
                .replace("daily focus export", "updated").replace("out/", "new-prefix/")
                .replace("\"Format\":\"PARQUET\"", "\"Format\":\"TEXT_OR_CSV\"")
                .replace("\"Compression\":\"PARQUET\"", "\"Compression\":\"GZIP\"");
        request("UpdateExport", updated).statusCode(200).body("ExportArn", equalTo(arn));
        request("GetExport", "{\"ExportArn\":\"" + arn + "\"}").statusCode(200)
                .body("Export.Description", equalTo("updated"))
                .body("Export.DataQuery.TableConfigurations.COST_AND_USAGE_REPORT.TIME_GRANULARITY", equalTo("HOURLY"))
                .body("Export.DestinationConfigurations.S3Destination.S3BucketOwner", equalTo("123456789012"))
                .body("Export.DestinationConfigurations.S3Destination.S3Prefix", equalTo("new-prefix/"))
                .body("Export.DestinationConfigurations.S3Destination.S3OutputConfigurations.Compression", equalTo("GZIP"))
                .body("ExportStatus.StatusCode", equalTo("HEALTHY"))
                .body("Export.ExportStatus", nullValue());
        String token = request("ListTagsForResource", "{" + resource + ",\"MaxResults\":1}").statusCode(200)
                .body("ResourceTags[0].Key", equalTo("fixture")).extract().path("NextToken");
        request("ListTagsForResource", "{" + resource + ",\"NextToken\":\"" + token + "\"}")
                .statusCode(200).body("ResourceTags[0].Key", equalTo("phase"))
                .body("ResourceTags[0].Value", equalTo("two")).body("NextToken", nullValue());
        request("DeleteExport", "{\"ExportArn\":\"" + arn + "\"}").statusCode(200);
        request("ListTagsForResource", "{" + resource + "}").statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void tableCatalog_hasColumnsAndConfigurableProperties() {
        request("ListTables", "{\"MaxResults\":100}").statusCode(200)
                .body("Tables.TableName", hasItem("COST_AND_USAGE_REPORT"))
                .body("Tables[0].TableProperties.Name", hasItem("TIME_GRANULARITY"));
        request("GetTable", "{\"TableName\":\"COST_AND_USAGE_REPORT\"}").statusCode(200)
                .body("Schema.Name", hasItems("identity_line_item_id", "identity_time_interval", "line_item_unblended_cost"))
                .body("TableProperties.TIME_GRANULARITY", equalTo("HOURLY"));
        request("GetTable", "{\"TableName\":\"COST_AND_USAGE_REPORT\","
                + "\"TableProperties\":{\"INCLUDE_RESOURCES\":\"TRUE\",\"TIME_GRANULARITY\":\"DAILY\"}}")
                .statusCode(200).body("Schema.Name", hasItem("line_item_resource_id"))
                .body("TableProperties.TIME_GRANULARITY", equalTo("DAILY"));
        request("GetTable", "{\"TableName\":\"missing\"}").statusCode(400)
                .body("__type", equalTo("ValidationException"));
        request("GetTable", "{\"TableName\":\"COST_AND_USAGE_REPORT\",\"TableProperties\":{\"TIME_GRANULARITY\":\"YEARLY\"}}")
                .statusCode(400).body("__type", equalTo("ValidationException"));
        request("ListTables", "{\"NextToken\":\"bad\"}").statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void executionQueries_exposeRecordedFailureAndOriginalDefinition() {
        String arn = createExportAndReturnArn("execution-wire");
        String account = service.accountIdFromArn(arn);
        ExportExecution execution = service.recordExecution(account, arn, "USER");
        service.completeExecution(account, execution, false, "Delivery format not implemented");
        String updated = "{\"ExportArn\":\"" + arn + "\"," + validExportBody("execution-wire").substring(1)
                .replace("daily focus export", "new description");
        request("UpdateExport", updated).statusCode(200);
        request("GetExecution", "{\"ExportArn\":\"" + arn + "\",\"ExecutionId\":\""
                + execution.getExecutionId() + "\"}").statusCode(200)
                .body("ExecutionId", equalTo(execution.getExecutionId()))
                .body("ExecutionStatus.StatusCode", equalTo("DELIVERY_FAILURE"))
                .body("ExecutionStatus.StatusReason", equalTo("INTERNAL_FAILURE"))
                .body("ExecutionStatus.CompletedAt", notNullValue())
                .body("Export.Description", equalTo("daily focus export"))
                .body("Execution", nullValue());
        request("ListExecutions", "{\"ExportArn\":\"" + arn + "\",\"MaxResults\":1}").statusCode(200)
                .body("Executions[0].ExecutionId", equalTo(execution.getExecutionId()))
                .body("Executions[0].ExecutionStatus.StatusCode", equalTo("DELIVERY_FAILURE"));
        request("GetExport", "{\"ExportArn\":\"" + arn + "\"}").statusCode(200)
                .body("ExportStatus.StatusCode", equalTo("UNHEALTHY"));
        request("DeleteExport", "{\"ExportArn\":\"" + arn + "\"}").statusCode(200);
        request("GetExecution", "{\"ExportArn\":\"" + arn + "\",\"ExecutionId\":\""
                + execution.getExecutionId() + "\"}").statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createExport_rejectsMismatchedCompression() {
        request("CreateExport", validExportBody("bad-compression").replace("\"Compression\":\"PARQUET\"",
                "\"Compression\":\"GZIP\"")).statusCode(400).body("__type", equalTo("ValidationException"));
    }

    private static ValidatableResponse request(String action, String body) {
        return given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports." + action)
                .header("Authorization", AUTH).body(body).when().post("/").then();
    }

    @Test
    void unknownAction_returnsUnknownOperation() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.GetBogusAction")
            .header("Authorization", AUTH).body("{}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
