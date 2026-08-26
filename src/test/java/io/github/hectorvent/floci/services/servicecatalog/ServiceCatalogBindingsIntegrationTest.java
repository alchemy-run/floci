package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 1.1 coverage for the Alchemy Service Catalog bindings suite:
 * browse, provision, record, terminate, and typed not-found probes.
 */
@QuarkusTest
class ServiceCatalogBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicecatalog/aws4_request";
    private static final String TARGET = "AWS242ServiceCatalogService.";
    private static final String PP_NAME = "floci-sc-bindings-pp";
    private static final String ROLE_ARN =
            "arn:aws:iam::000000000000:role/floci-servicecatalog-bindings";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeProvisionedProduct_unknownName_returnsResourceNotFound() {
        invoke("DescribeProvisionedProduct", "{\"Name\":\"alchemy-sc-does-not-exist\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void updateProvisionedProduct_unknownName_returnsResourceNotFound() {
        invoke("UpdateProvisionedProduct", "{"
                + "\"ProvisionedProductName\":\"alchemy-sc-does-not-exist\","
                + "\"UpdateToken\":\"flociScUpdateProbe\""
                + "}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listStackInstances_unknownId_returnsResourceNotFound() {
        invoke("ListStackInstancesForProvisionedProduct",
                "{\"ProvisionedProductId\":\"pp-aaaaaaaaaaaaa\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void serviceAction_unknownIds_returnsResourceNotFound() {
        invoke("DescribeServiceActionExecutionParameters", "{"
                + "\"ProvisionedProductId\":\"pp-aaaaaaaaaaaaa\","
                + "\"ServiceActionId\":\"act-aaaaaaaaaaaaa\""
                + "}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        invoke("ExecuteProvisionedProductServiceAction", "{"
                + "\"ProvisionedProductId\":\"pp-aaaaaaaaaaaaa\","
                + "\"ServiceActionId\":\"act-aaaaaaaaaaaaa\","
                + "\"ExecuteToken\":\"flociScExecuteProbe\""
                + "}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void browseProvisionDescribeAndTerminate() {
        Response createdPortfolio = invoke("CreatePortfolio", """
                {
                  "DisplayName": "floci-sc-bindings-portfolio",
                  "ProviderName": "alchemy-tests",
                  "IdempotencyToken": "flociScBindingsPortfolio1"
                }
                """);
        createdPortfolio.then().statusCode(200);
        String portfolioId = createdPortfolio.jsonPath().getString("PortfolioDetail.Id");
        assertTrue(portfolioId.startsWith("port-"));

        Response createdProduct = invoke("CreateProduct", """
                {
                  "Name": "floci-sc-bindings-product",
                  "Owner": "alchemy-tests",
                  "Description": "bindings test product",
                  "ProductType": "CLOUD_FORMATION_TEMPLATE",
                  "IdempotencyToken": "flociScBindingsProduct1",
                  "ProvisioningArtifactParameters": {
                    "Name": "v1",
                    "Type": "CLOUD_FORMATION_TEMPLATE",
                    "Info": {"LoadTemplateFromURL": "https://example.s3.us-east-1.amazonaws.com/template.json"}
                  }
                }
                """);
        createdProduct.then().statusCode(200);
        String productId = createdProduct.jsonPath().getString("ProductViewDetail.ProductViewSummary.ProductId");
        String artifactId = createdProduct.jsonPath().getString("ProvisioningArtifactDetail.Id");
        assertTrue(productId.startsWith("prod-"));
        assertTrue(artifactId.startsWith("pa-"));

        invoke("AssociateProductWithPortfolio", "{"
                + "\"PortfolioId\":\"" + portfolioId + "\","
                + "\"ProductId\":\"" + productId + "\""
                + "}")
                .then()
                .statusCode(200);

        invoke("AssociatePrincipalWithPortfolio", "{"
                + "\"PortfolioId\":\"" + portfolioId + "\","
                + "\"PrincipalARN\":\"" + ROLE_ARN + "\","
                + "\"PrincipalType\":\"IAM\""
                + "}")
                .then()
                .statusCode(200);

        invoke("SearchProducts", "{}")
                .then()
                .statusCode(200)
                .body("ProductViewSummaries.ProductId", org.hamcrest.Matchers.hasItem(productId));

        invoke("ListLaunchPaths", "{\"ProductId\":\"" + productId + "\"}")
                .then()
                .statusCode(200)
                .body("LaunchPathSummaries", hasSize(greaterThan(0)))
                .body("LaunchPathSummaries[0].Id", equalTo(portfolioId));

        invoke("DescribeProduct", "{\"Id\":\"" + productId + "\"}")
                .then()
                .statusCode(200)
                .body("ProductViewSummary.Name", equalTo("floci-sc-bindings-product"));

        invoke("DescribeProvisioningParameters", "{"
                + "\"ProductId\":\"" + productId + "\","
                + "\"ProvisioningArtifactId\":\"" + artifactId + "\","
                + "\"PathId\":\"" + portfolioId + "\""
                + "}")
                .then()
                .statusCode(200)
                .body("ProvisioningArtifactParameters", hasSize(0));

        invoke("DescribeProvisionedProduct", "{\"Name\":\"" + PP_NAME + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        Response provisioned = invoke("ProvisionProduct", "{"
                + "\"ProductId\":\"" + productId + "\","
                + "\"ProvisioningArtifactId\":\"" + artifactId + "\","
                + "\"PathId\":\"" + portfolioId + "\","
                + "\"ProvisionedProductName\":\"" + PP_NAME + "\","
                + "\"ProvisionToken\":\"flociScBindingsProvision1\""
                + "}");
        provisioned.then()
                .statusCode(200)
                .body("RecordDetail.RecordId", startsWith("rec-"))
                .body("RecordDetail.Status", equalTo("SUCCEEDED"))
                .body("RecordDetail.ProvisionedProductId", startsWith("pp-"));
        String recordId = provisioned.jsonPath().getString("RecordDetail.RecordId");

        invoke("DescribeRecord", "{\"Id\":\"" + recordId + "\"}")
                .then()
                .statusCode(200)
                .body("RecordDetail.Status", equalTo("SUCCEEDED"))
                .body("RecordDetail.RecordErrors", hasSize(0));

        invoke("DescribeProvisionedProduct", "{\"Name\":\"" + PP_NAME + "\"}")
                .then()
                .statusCode(200)
                .body("ProvisionedProductDetail.Status", equalTo("AVAILABLE"))
                .body("ProvisionedProductDetail.Id", startsWith("pp-"));

        invoke("SearchProvisionedProducts", "{}")
                .then()
                .statusCode(200)
                .body("ProvisionedProducts.Name", org.hamcrest.Matchers.hasItem(PP_NAME));

        invoke("GetProvisionedProductOutputs", "{\"ProvisionedProductName\":\"" + PP_NAME + "\"}")
                .then()
                .statusCode(200)
                .body("Outputs", hasSize(greaterThan(0)))
                .body("Outputs[0].OutputKey", equalTo("CloudformationStackARN"));

        invoke("ListRecordHistory", "{}")
                .then()
                .statusCode(200)
                .body("RecordDetails", hasSize(greaterThan(0)));

        invoke("TerminateProvisionedProduct", "{"
                + "\"ProvisionedProductName\":\"" + PP_NAME + "\","
                + "\"TerminateToken\":\"flociScBindingsTerminate1\""
                + "}")
                .then()
                .statusCode(200)
                .body("RecordDetail.RecordId", startsWith("rec-"));

        invoke("DescribeProvisionedProduct", "{\"Name\":\"" + PP_NAME + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static Response invoke(String action, String body) {
        return given()
                .header("Authorization", AUTH)
                .header("X-Amz-Target", TARGET + action)
                .contentType(CONTENT_TYPE)
                .body(body)
                .when()
                .post("/");
    }
}
