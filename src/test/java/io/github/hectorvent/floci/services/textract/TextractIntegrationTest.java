package io.github.hectorvent.floci.services.textract;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
/**
 * Integration tests for the Amazon Textract stub.
 * Validates AWS-compatible wire format using RestAssured.
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1, X-Amz-Target: Textract.<Action>
 */
@QuarkusTest
class TextractIntegrationTest {
    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/textract/aws4_request";
    @Test
    void detectDocumentText_returnsBlocksAndDocumentMetadata() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.DetectDocumentText")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"test.pdf\"}}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentMetadata.Pages", equalTo(1))
            .body("DetectDocumentTextModelVersion", equalTo("1.0"))
            .body("Blocks", hasSize(3))
            .body("Blocks.BlockType", hasItems("PAGE", "LINE", "WORD"));
    }
    @Test
    void detectDocumentText_blockShapesAreAwsCompatible() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.DetectDocumentText")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Blocks[0].Id", notNullValue())
            .body("Blocks[0].Confidence", notNullValue())
            .body("Blocks[0].Geometry.BoundingBox.Width", notNullValue())
            .body("Blocks[0].Geometry.BoundingBox.Height", notNullValue())
            .body("Blocks[0].Geometry.BoundingBox.Left", notNullValue())
            .body("Blocks[0].Geometry.BoundingBox.Top", notNullValue())
            .body("Blocks[0].Geometry.Polygon", hasSize(4));
    }
    @Test
    void analyzeDocument_returnsBlocksAndModelVersion() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.AnalyzeDocument")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"test.pdf\"}},\"FeatureTypes\":[\"TABLES\",\"FORMS\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentMetadata.Pages", equalTo(1))
            .body("AnalyzeDocumentModelVersion", equalTo("1.0"))
            .body("Blocks", hasSize(3))
            .body("Blocks.BlockType", hasItems("PAGE", "LINE", "WORD"));
    }
    @Test
    void asyncTextDetection_startAndGetSucceeded() {
        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DocumentLocation\":{\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"test.pdf\"}}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobId", notNullValue())
            .extract().path("JobId");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobStatus", equalTo("SUCCEEDED"))
            .body("DocumentMetadata.Pages", equalTo(1))
            .body("DetectDocumentTextModelVersion", equalTo("1.0"))
            .body("Blocks", hasSize(3));
    }
    @Test
    void getDocumentTextDetection_unknownJobId_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"non-existent-job-id\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidJobIdException"));
    }
    @Test
    void getDocumentTextDetection_missingJobId_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }
    @Test
    void asyncDocumentAnalysis_startAndGetSucceeded() {
        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DocumentLocation\":{\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"test.pdf\"}},\"FeatureTypes\":[\"TABLES\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobId", notNullValue())
            .extract().path("JobId");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobStatus", equalTo("SUCCEEDED"))
            .body("DocumentMetadata.Pages", equalTo(1))
            .body("AnalyzeDocumentModelVersion", equalTo("1.0"))
            .body("Blocks", hasSize(3));
    }
    @Test
    void getDocumentAnalysis_wrongJobType_returns400() {
        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("JobId");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidJobIdException"));
    }
    @Test
    void unknownAction_returnsUnknownOperationError() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.DetectSentiment")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void analyzeDocument_blockShapesAreAwsCompatible() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.AnalyzeDocument")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"test.pdf\"}},\"FeatureTypes\":[\"TABLES\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Blocks[0].Id", notNullValue())
            .body("Blocks[0].Geometry.BoundingBox.Width", notNullValue())
            .body("Blocks[0].Geometry.Polygon", hasSize(4));
    }

    @Test
    void analyzeDocument_featureTypesIsOptional() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.AnalyzeDocument")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentMetadata.Pages", equalTo(1))
            .body("AnalyzeDocumentModelVersion", equalTo("1.0"));
    }

    @Test
    void detectDocumentText_wordBlockHasText() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.DetectDocumentText")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Blocks.findAll { it.BlockType == 'WORD' }.Text", hasItem("HELLO"));
    }

    @Test
    void detectDocumentText_confidenceIsPresent() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.DetectDocumentText")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Blocks.Confidence", everyItem(notNullValue()));
    }

    @Test
    void detectDocumentText_eachBlockHasPageNumber() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.DetectDocumentText")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Blocks.Page", everyItem(equalTo(1)));
    }

    @Test
    void getDocumentAnalysis_unknownJobId_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"non-existent-job-id\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidJobIdException"));
    }

    @Test
    void getDocumentAnalysis_missingJobId_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getDocumentTextDetection_wrongJobType_returns400() {
        // Start a DocumentAnalysis job, then try to get it as TextDetection
        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FeatureTypes\":[\"TABLES\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("JobId");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidJobIdException"));
    }

    @Test
    void asyncTextDetection_jobIdIsUnique() {
        String jobId1 = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("JobId");

        String jobId2 = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("JobId");

        assertThat(jobId1, not(equalTo(jobId2)));
    }

    @Test
    void asyncDocumentAnalysis_jobIdIsUnique() {
        String jobId1 = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FeatureTypes\":[\"TABLES\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("JobId");

        String jobId2 = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FeatureTypes\":[\"FORMS\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("JobId");

        assertThat(jobId1, not(equalTo(jobId2)));
    }

    @Test
    void getDocumentTextDetection_returnsCorrectModelVersion() {
        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .extract().path("JobId");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DetectDocumentTextModelVersion", equalTo("1.0"));
    }

    @Test
    void getDocumentAnalysis_returnsCorrectModelVersion() {
        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FeatureTypes\":[\"TABLES\"]}")
        .when()
            .post("/")
        .then()
            .extract().path("JobId");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AnalyzeDocumentModelVersion", equalTo("1.0"));
    }

    @Test
    void analyzeExpense_returnsExpenseDocuments() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.AnalyzeExpense")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"Bytes\":\"aGVsbG8=\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentMetadata.Pages", equalTo(1))
            .body("ExpenseDocuments", hasSize(greaterThanOrEqualTo(1)))
            .body("ExpenseDocuments[0].SummaryFields", notNullValue());
    }

    @Test
    void analyzeExpense_secondCallAlsoReturnsPages() {
        String body = "{\"Document\":{\"Bytes\":\"aGVsbG8=\"}}";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.AnalyzeExpense")
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentMetadata.Pages", equalTo(1));
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.AnalyzeExpense")
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentMetadata.Pages", equalTo(1))
            .body("ExpenseDocuments", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    void analyzeExpense_missingDocument_validationException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.AnalyzeExpense")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void analyzeID_returnsIdentityDocuments() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.AnalyzeID")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DocumentPages\":[{\"Bytes\":\"aGVsbG8=\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("IdentityDocuments", hasSize(greaterThanOrEqualTo(1)))
            .body("AnalyzeIDModelVersion", equalTo("1.0"));
    }

    @Test
    void analyzeDocument_lineTextIsHello() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.AnalyzeDocument")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"Bytes\":\"aGVsbG8=\"},\"FeatureTypes\":[\"TABLES\",\"FORMS\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DocumentMetadata.Pages", equalTo(1))
            .body("Blocks.findAll { it.BlockType == 'LINE' }.Text", hasItem("HELLO"));
    }

    @Test
    void asyncExpenseAnalysis_startAndGetSucceeded() {
        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartExpenseAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DocumentLocation\":{\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"receipt.png\"}}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobId", notNullValue())
            .extract().path("JobId");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetExpenseAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobStatus", equalTo("SUCCEEDED"))
            .body("ExpenseDocuments", hasSize(greaterThanOrEqualTo(0)));
    }

    @Test
    void asyncLendingAnalysis_startGetAndSummarySucceeded() {
        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartLendingAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DocumentLocation\":{\"S3Object\":{\"Bucket\":\"my-bucket\",\"Name\":\"loan.pdf\"}}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobId", notNullValue())
            .extract().path("JobId");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetLendingAnalysis")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobStatus", equalTo("SUCCEEDED"))
            .body("Results", notNullValue());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetLendingAnalysisSummary")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobStatus", equalTo("SUCCEEDED"))
            .body("Summary.DocumentGroups", notNullValue());
    }

    @Test
    void getDocumentTextDetection_canBePolledTwice() {
        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.StartDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("JobId");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobStatus", equalTo("SUCCEEDED"))
            .body("Blocks.findAll { it.BlockType == 'LINE' }.Text", hasItem("HELLO"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetDocumentTextDetection")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobStatus", equalTo("SUCCEEDED"));
    }

    @Test
    void adapters_createGetListUpdateDelete() {
        String name = "alchemy-textract-" + System.nanoTime();
        String adapterId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.CreateAdapter")
            .header("Authorization", AUTH_HEADER)
            .body("{\"AdapterName\":\"" + name + "\",\"FeatureTypes\":[\"QUERIES\"],\"Description\":\"fixture\",\"AutoUpdate\":\"DISABLED\",\"Tags\":{\"owner\":\"floci\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AdapterId", notNullValue())
            .extract().path("AdapterId");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetAdapter")
            .header("Authorization", AUTH_HEADER)
            .body("{\"AdapterId\":\"" + adapterId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AdapterName", equalTo(name))
            .body("FeatureTypes", equalTo(java.util.List.of("QUERIES")))
            .body("Tags.owner", equalTo("floci"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.ListAdapters")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Adapters.AdapterName", hasItem(name));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.ListAdapterVersions")
            .header("Authorization", AUTH_HEADER)
            .body("{\"AdapterId\":\"" + adapterId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AdapterVersions", hasSize(0));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.UpdateAdapter")
            .header("Authorization", AUTH_HEADER)
            .body("{\"AdapterId\":\"" + adapterId + "\",\"Description\":\"updated\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Description", equalTo("updated"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.DeleteAdapter")
            .header("Authorization", AUTH_HEADER)
            .body("{\"AdapterId\":\"" + adapterId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void adapterVersion_missingManifest_invalidS3() {
        String name = "alchemy-textract-ver-" + System.nanoTime();
        String adapterId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.CreateAdapter")
            .header("Authorization", AUTH_HEADER)
            .body("{\"AdapterName\":\"" + name + "\",\"FeatureTypes\":[\"QUERIES\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("AdapterId");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.GetAdapterVersion")
            .header("Authorization", AUTH_HEADER)
            .body("{\"AdapterId\":\"" + adapterId + "\",\"AdapterVersion\":\"999\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.DeleteAdapterVersion")
            .header("Authorization", AUTH_HEADER)
            .body("{\"AdapterId\":\"" + adapterId + "\",\"AdapterVersion\":\"999\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.CreateAdapterVersion")
            .header("Authorization", AUTH_HEADER)
            .body("{\"AdapterId\":\"" + adapterId + "\",\"DatasetConfig\":{\"ManifestS3Object\":{\"Bucket\":\"no-such-textract-bucket\",\"Name\":\"missing-manifest.jsonl\"}},\"OutputConfig\":{\"S3Bucket\":\"no-such-textract-bucket\",\"S3Prefix\":\"adapter-training/\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", anyOf(
                    equalTo("InvalidS3ObjectException"),
                    equalTo("InvalidParameterException"),
                    equalTo("ValidationException")));
    }

    @Test
    void createAdapter_duplicateName_conflict() {
        String name = "alchemy-textract-dup-" + System.nanoTime();
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.CreateAdapter")
            .header("Authorization", AUTH_HEADER)
            .body("{\"AdapterName\":\"" + name + "\",\"FeatureTypes\":[\"QUERIES\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Textract.CreateAdapter")
            .header("Authorization", AUTH_HEADER)
            .body("{\"AdapterName\":\"" + name + "\",\"FeatureTypes\":[\"QUERIES\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ConflictException"));
    }
}
