package io.floci.aws.services.textract;

import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Textract adapters and versions, expense, ID, and lending analysis, and
 * mock lookup by document content.
 */
@QuarkusTest
class TextractAdapterIntegrationTest {
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/textract/aws4_request";
    /** The test fixture's sha256 mock key is for these bytes. */
    private static final byte[] HASHED = "HASHED-DOC".getBytes(StandardCharsets.UTF_8);

    @Inject
    S3Service s3Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "Textract." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/")
            .then();
    }

    @Test
    void anAdapterWithVersionsAndTags() {
        String id = call("CreateAdapter", """
                {"AdapterName":"invoices","FeatureTypes":["QUERIES"],"Description":"v1","Tags":{"env":"test"}}""")
            .statusCode(200).extract().path("AdapterId");
        call("CreateAdapter", "{\"AdapterName\":\"invoices\",\"FeatureTypes\":[\"QUERIES\"]}")
            .statusCode(400).body("__type", equalTo("ConflictException"));
        call("GetAdapter", "{\"AdapterId\":\"" + id + "\"}")
            .statusCode(200)
            .body("AdapterName", equalTo("invoices"))
            .body("AutoUpdate", equalTo("DISABLED"))
            .body("FeatureTypes", contains("QUERIES"))
            .body("Tags.env", equalTo("test"));
        call("UpdateAdapter", "{\"AdapterId\":\"" + id + "\",\"Description\":\"v2\",\"AutoUpdate\":\"ENABLED\"}")
            .statusCode(200).body("Description", equalTo("v2")).body("AutoUpdate", equalTo("ENABLED"));
        call("ListAdapters", "{}").statusCode(200).body("Adapters.AdapterId", hasItem(id));

        String arn = "arn:aws:textract:us-east-1:000000000000:/adapters/" + id;
        call("TagResource", "{\"ResourceARN\":\"" + arn + "\",\"Tags\":{\"extra\":\"1\"}}").statusCode(200);
        call("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}")
            .statusCode(200).body("Tags.extra", equalTo("1")).body("Tags.env", equalTo("test"));
        call("UntagResource", "{\"ResourceARN\":\"" + arn + "\",\"TagKeys\":[\"extra\"]}").statusCode(200);

        call("GetAdapterVersion", "{\"AdapterId\":\"" + id + "\",\"AdapterVersion\":\"999\"}")
            .statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
        call("DeleteAdapterVersion", "{\"AdapterId\":\"" + id + "\",\"AdapterVersion\":\"999\"}").statusCode(200);
        call("CreateAdapterVersion", """
                {"AdapterId":"%s","DatasetConfig":{"ManifestS3Object":{"Bucket":"tx-adapter","Name":"missing.jsonl"}},
                 "OutputConfig":{"S3Bucket":"tx-adapter","S3Prefix":"out/"}}""".formatted(id))
            .statusCode(400).body("__type", equalTo("InvalidS3ObjectException"));
        s3Service.createBucket("tx-adapter", "us-east-1");
        s3Service.putObject("tx-adapter", "manifest.jsonl", "{}".getBytes(StandardCharsets.UTF_8),
                "application/json", Map.of());
        call("CreateAdapterVersion", """
                {"AdapterId":"%s","DatasetConfig":{"ManifestS3Object":{"Bucket":"tx-adapter","Name":"manifest.jsonl"}},
                 "OutputConfig":{"S3Bucket":"tx-adapter","S3Prefix":"out/"}}""".formatted(id))
            .statusCode(200).body("AdapterVersion", equalTo("1"));
        call("ListAdapterVersions", "{\"AdapterId\":\"" + id + "\"}")
            .statusCode(200).body("AdapterVersions.Status", contains("ACTIVE"));

        call("DeleteAdapter", "{\"AdapterId\":\"" + id + "\"}").statusCode(200);
        call("GetAdapter", "{\"AdapterId\":\"" + id + "\"}")
            .statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void contentHashMocksExpenseIdAndLending() {
        String bytes = Base64.getEncoder().encodeToString(HASHED);
        call("DetectDocumentText", "{\"Document\":{\"Bytes\":\"" + bytes + "\"}}")
            .statusCode(200).body("Blocks.Text", contains("HASHED"));
        call("AnalyzeDocument", "{\"Document\":{\"Bytes\":\"" + bytes + "\"},\"FeatureTypes\":[\"TABLES\"]}")
            .statusCode(200).body("Blocks.Text", contains("HASHED"));

        s3Service.createBucket("tx-generated-name-1234", "us-east-1");
        s3Service.putObject("tx-generated-name-1234", "doc.png", HASHED, "image/png", Map.of());
        String location = "{\"S3Object\":{\"Bucket\":\"tx-generated-name-1234\",\"Name\":\"doc.png\"}}";
        String textJob = call("StartDocumentTextDetection", "{\"DocumentLocation\":" + location + "}")
            .statusCode(200).extract().path("JobId");
        call("GetDocumentTextDetection", "{\"JobId\":\"" + textJob + "\"}")
            .statusCode(200).body("JobStatus", equalTo("SUCCEEDED")).body("Blocks.Text", contains("HASHED"));
        call("StartDocumentTextDetection",
                "{\"DocumentLocation\":{\"S3Object\":{\"Bucket\":\"tx-generated-name-1234\",\"Name\":\"none.png\"}}}")
            .statusCode(400).body("__type", equalTo("InvalidS3ObjectException"));

        call("AnalyzeExpense", "{\"Document\":{\"Bytes\":\"" + bytes + "\"}}")
            .statusCode(200)
            .body("DocumentMetadata.Pages", equalTo(1))
            .body("ExpenseDocuments", hasSize(1))
            .body("ExpenseDocuments[0].Blocks.Text", contains("HASHED"));
        call("AnalyzeID", "{\"DocumentPages\":[{\"Bytes\":\"" + bytes + "\"}]}")
            .statusCode(200).body("IdentityDocuments", hasSize(1)).body("IdentityDocuments[0].DocumentIndex", equalTo(1));

        String expense = call("StartExpenseAnalysis", "{\"DocumentLocation\":" + location + "}")
            .statusCode(200).extract().path("JobId");
        call("GetExpenseAnalysis", "{\"JobId\":\"" + expense + "\"}")
            .statusCode(200).body("JobStatus", equalTo("SUCCEEDED")).body("ExpenseDocuments", hasSize(1));
        String lending = call("StartLendingAnalysis", "{\"DocumentLocation\":" + location + "}")
            .statusCode(200).extract().path("JobId");
        call("GetLendingAnalysis", "{\"JobId\":\"" + lending + "\"}")
            .statusCode(200).body("JobStatus", equalTo("SUCCEEDED"))
            .body("Results[0].PageClassification.PageType[0].Value", equalTo("UNCLASSIFIED"));
        call("GetLendingAnalysisSummary", "{\"JobId\":\"" + lending + "\"}")
            .statusCode(200).body("Summary.DocumentGroups[0].SplitDocuments[0].Pages", contains(1));
        call("GetLendingAnalysis", "{\"JobId\":\"" + expense + "\"}")
            .statusCode(400).body("__type", equalTo("InvalidJobIdException"));
    }
}
