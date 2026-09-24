package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.services.cloudformation.CloudFormationService;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire-level coverage for provisioned-product lifecycle and persisted operation records. */
@QuarkusTest
class ServiceCatalogProvisionedProductLifecycleConsumerTest {

    @Inject
    CloudFormationService cloudFormationService;

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicecatalog/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWS242ServiceCatalogService." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/");
    }

    private static String createProduct(String name) {
        return call("CreateProduct", "{\"Name\":\"" + name + "\",\"Owner\":\"floci-test\","
                + "\"ProvisioningArtifactParameters\":[{\"Name\":\"v1\"}]}")
                .then().statusCode(200)
                .extract().path("ProductViewDetail.ProductViewSummary.Id");
    }

    private static String firstArtifactId(String productId) {
        return call("ListProvisioningArtifacts", "{\"ProductId\":\"" + productId + "\"}")
                .then().statusCode(200)
                .extract().path("ProvisioningArtifactDetails[0].Id");
    }

    private static String importProvisionedProduct(String productId, String artifactId, String name) {
        return call("ImportAsProvisionedProduct", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"ProvisionedProductName\":\""
                + name + "\",\"PhysicalId\":\"phys-" + name + "\",\"IdempotencyToken\":\"tok-" + name + "\"}")
                .then().statusCode(200)
                .extract().path("RecordDetail.ProvisionedProductId");
    }

    @Test
    void provisionProductTracksRealStackRecordsOutputsAndDeletion() {
        String bucket = "sc-real-stack-template";
        String s3Auth = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/s3/aws4_request";
        given().header("Authorization", s3Auth).put("/" + bucket).then().statusCode(200);
        given().header("Authorization", s3Auth).contentType("application/json").body("""
                {"AWSTemplateFormatVersion":"2010-09-09",
                 "Resources":{"Handle":{"Type":"AWS::CloudFormation::WaitConditionHandle"}},
                 "Outputs":{"Message":{"Value":"real-stack-output"}}}
                """).put("/" + bucket + "/template.json").then().statusCode(200);
        String productId = call("CreateProduct", """
                {"Name":"real-stack-product","Owner":"platform","ProductType":"CLOUD_FORMATION_TEMPLATE",
                 "IdempotencyToken":"real-stack-product",
                 "ProvisioningArtifactParameters":{"Name":"v1","Type":"CLOUD_FORMATION_TEMPLATE",
                   "Info":{"LoadTemplateFromURL":"https://%s.s3.us-east-1.amazonaws.com/template.json"}}}
                """.formatted(bucket)).then().statusCode(200)
                .extract().path("ProductViewDetail.ProductViewSummary.ProductId");
        String artifactId = firstArtifactId(productId);
        String request = """
                {"ProductId":"%s","ProvisioningArtifactId":"%s",
                 "ProvisionedProductName":"real-stack-instance","ProvisionToken":"real-stack-instance"}
                """.formatted(productId, artifactId);
        Response provisioned = call("ProvisionProduct", request);
        String recordId = provisioned.then().statusCode(200).extract().path("RecordDetail.RecordId");
        String provisionedId = provisioned.path("RecordDetail.ProvisionedProductId");
        call("ProvisionProduct", request).then().statusCode(200)
                .body("RecordDetail.RecordId", equalTo(recordId))
                .body("RecordDetail.ProvisionedProductId", equalTo(provisionedId));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                call("DescribeRecord", "{\"Id\":\"" + recordId + "\"}").then().statusCode(200)
                        .body("RecordDetail.Status", equalTo("SUCCEEDED"))
                        .body("RecordDetail.RecordErrors.size()", equalTo(0)));
        String stackArn = call("DescribeProvisionedProduct", "{\"Name\":\"real-stack-instance\"}")
                .then().statusCode(200)
                .body("ProvisionedProductDetail.Status", equalTo("AVAILABLE"))
                .body("ProvisionedProductDetail.LastRecordId", equalTo(recordId))
                .extract().path("ProvisionedProductDetail.PhysicalId");
        String accountId = stackArn.split(":")[4];
        Stack stack = cloudFormationService.describeStacks(stackArn, "us-east-1", accountId).getFirst();
        assertEquals("CREATE_COMPLETE", stack.getStatus());
        assertTrue(stack.getResources().containsKey("Handle"));
        assertEquals("real-stack-output", stack.getOutputs().get("Message"));
        call("GetProvisionedProductOutputs", "{\"ProvisionedProductName\":\"real-stack-instance\"}")
                .then().statusCode(200)
                .body("Outputs.find { it.OutputKey == 'Message' }.OutputValue", equalTo("real-stack-output"))
                .body("Outputs.find { it.OutputKey == 'CloudformationStackARN' }.OutputValue", equalTo(stackArn));
        String terminate = "{\"ProvisionedProductName\":\"real-stack-instance\",\"TerminateToken\":\"real-stack-delete\"}";
        String terminationRecordId = call("TerminateProvisionedProduct", terminate).then().statusCode(200)
                .extract().path("RecordDetail.RecordId");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                call("DescribeRecord", "{\"Id\":\"" + terminationRecordId + "\"}").then().statusCode(200)
                        .body("RecordDetail.Status", equalTo("SUCCEEDED")));
        assertEquals("DELETE_COMPLETE", cloudFormationService.describeStacks(stackArn, "us-east-1", accountId)
                .getFirst().getStatus());
        call("DescribeProvisionedProduct", "{\"Name\":\"real-stack-instance\"}").then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        call("DescribeProvisionedProduct", "{\"Id\":\"" + provisionedId + "\"}").then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        call("TerminateProvisionedProduct", terminate).then().statusCode(200)
                .body("RecordDetail.RecordId", equalTo(terminationRecordId));
        call("DeleteProduct", "{\"Id\":\"" + productId + "\"}").then().statusCode(200);
        call("ListRecordHistory", "{}").then().statusCode(200)
                .body("RecordDetails.RecordId", hasItem(recordId))
                .body("RecordDetails.RecordId", hasItem(terminationRecordId));
        given().header("Authorization", s3Auth).delete("/" + bucket + "/template.json").then().statusCode(204);
        given().header("Authorization", s3Auth).delete("/" + bucket).then().statusCode(204);
    }

    @Test
    void provisionProductRejectsMissingTemplateWithoutRecordingSuccess() {
        String productId = createProduct("missing-template-product");
        String artifactId = firstArtifactId(productId);
        call("ProvisionProduct", """
                {"ProductId":"%s","ProvisioningArtifactId":"%s",
                 "ProvisionedProductName":"missing-template-instance","ProvisionToken":"missing-template-instance"}
                """.formatted(productId, artifactId)).then().statusCode(400)
                .body("__type", equalTo("InvalidParametersException"));
        call("DescribeProvisionedProduct", "{\"Name\":\"missing-template-instance\"}")
                .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
        call("DeleteProduct", "{\"Id\":\"" + productId + "\"}").then().statusCode(200);
    }

    // ---------- DescribeProvisionedProduct ----------

    @Test
    void describeProvisionedProduct_returnsImportedProduct() {
        String productId = createProduct("ab-describe-pp-product");
        String artifactId = firstArtifactId(productId);
        String provisionedId = importProvisionedProduct(productId, artifactId, "ab-describe-pp");

        call("DescribeProvisionedProduct", "{\"Id\":\"" + provisionedId + "\"}")
        .then()
            .statusCode(200)
            .body("ProvisionedProductDetail.Id", equalTo(provisionedId))
            .body("ProvisionedProductDetail.Status", equalTo("AVAILABLE"));
    }

    @Test
    void describeProvisionedProduct_unknownId_returnsResourceNotFound() {
        call("DescribeProvisionedProduct", "{\"Id\":\"pp-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- UpdateProvisionedProduct ----------

    @Test
    void updateProvisionedProduct_returnsDescribableRecord() {
        String productId = createProduct("ab-update-pp-product");
        String artifactId = firstArtifactId(productId);
        String provisionedId = importProvisionedProduct(productId, artifactId, "ab-update-pp");

        String recordId = call("UpdateProvisionedProduct", "{\"ProvisionedProductId\":\"" + provisionedId
                + "\",\"UpdateToken\":\"tok-update-pp\"}")
        .then()
            .statusCode(200)
            .body("RecordDetail.ProvisionedProductId", equalTo(provisionedId))
            .body("RecordDetail.Status", equalTo("SUCCEEDED"))
            .extract().path("RecordDetail.RecordId");

        call("DescribeRecord", "{\"Id\":\"" + recordId + "\"}")
        .then()
            .statusCode(200)
            .body("RecordDetail.RecordType", equalTo("UPDATE_PROVISIONED_PRODUCT"));
    }

    @Test
    void updateProvisionedProduct_unknownProduct_returnsResourceNotFound() {
        call("UpdateProvisionedProduct", "{\"ProvisionedProductId\":\"pp-doesnotexist\","
                + "\"UpdateToken\":\"tok-update-unknown\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- UpdateProvisionedProductProperties ----------

    @Test
    void updateProvisionedProductProperties_persistsProperties() {
        String productId = createProduct("ab-update-pp-props-product");
        String artifactId = firstArtifactId(productId);
        String provisionedId = importProvisionedProduct(productId, artifactId, "ab-update-pp-props");

        call("UpdateProvisionedProductProperties", "{\"ProvisionedProductId\":\"" + provisionedId
                + "\",\"ProvisionedProductProperties\":{\"OWNER\":\"someone-else\"},\"IdempotencyToken\":\""
                + "tok-update-props\"}")
        .then()
            .statusCode(200)
            .body("Status", equalTo("SUCCEEDED"));

        call("DescribeProvisionedProduct", "{\"Id\":\"" + provisionedId + "\"}")
        .then()
            .statusCode(200)
            .body("ProvisionedProductDetail.Id", equalTo(provisionedId));
    }

    @Test
    void updateProvisionedProductProperties_unknownProduct_returnsResourceNotFound() {
        call("UpdateProvisionedProductProperties", "{\"ProvisionedProductId\":\"pp-doesnotexist\","
                + "\"ProvisionedProductProperties\":{\"OWNER\":\"x\"},\"IdempotencyToken\":\"tok-unknown\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- TerminateProvisionedProduct ----------

    @Test
    void terminateProvisionedProduct_removesProductAndPreservesRecord() {
        String productId = createProduct("ab-terminate-pp-product");
        String artifactId = firstArtifactId(productId);
        String provisionedId = importProvisionedProduct(productId, artifactId, "ab-terminate-pp");

        String recordId = call("TerminateProvisionedProduct", "{\"ProvisionedProductId\":\""
                + provisionedId + "\",\"TerminateToken\":\"tok-terminate-pp\"}")
        .then()
            .statusCode(200)
            .body("RecordDetail.ProvisionedProductId", equalTo(provisionedId))
            .body("RecordDetail.Status", equalTo("SUCCEEDED"))
            .body("RecordDetail.RecordType", equalTo("TERMINATE_PROVISIONED_PRODUCT"))
            .extract().path("RecordDetail.RecordId");

        call("DescribeProvisionedProduct", "{\"Id\":\"" + provisionedId + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));

        call("DescribeProvisionedProduct", "{\"Name\":\"ab-terminate-pp\"}").then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        call("SearchProvisionedProducts", "{}").then().statusCode(200)
                .body("ProvisionedProducts.Id", not(hasItem(provisionedId)));
        call("ListRecordHistory", "{}").then().statusCode(200)
                .body("RecordDetails.RecordId", hasItem(recordId));

        call("DescribeRecord", "{\"Id\":\"" + recordId + "\"}")
        .then()
            .statusCode(200)
            .body("RecordDetail.RecordType", equalTo("TERMINATE_PROVISIONED_PRODUCT"));
    }

    @Test
    void terminateProvisionedProduct_unknownProduct_returnsResourceNotFound() {
        call("TerminateProvisionedProduct", "{\"ProvisionedProductId\":\"pp-doesnotexist\","
                + "\"TerminateToken\":\"tok-terminate-unknown\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
