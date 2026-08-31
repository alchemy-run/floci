package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 1.1 Service Catalog coverage used by Alchemy's association resources:
 * portfolio + product + associate product/principal + list + teardown.
 */
@QuarkusTest
class ServiceCatalogAssociationIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicecatalog/aws4_request";
    private static final String TARGET = "AWS242ServiceCatalogService.";
    private static final String ROLE_ARN =
            "arn:aws:iam::000000000000:role/floci-servicecatalog-assoc";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listPortfolios_emptyOrExisting_returns200() {
        invoke("ListPortfolios", "{}")
                .then()
                .statusCode(200);
    }

    @Test
    void describePortfolio_unknownId_returnsResourceNotFound() {
        invoke("DescribePortfolio", "{\"Id\":\"port-doesnotexist1\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void associateProductAndPrincipal_listAndTeardown() {
        Response createdPortfolio = invoke("CreatePortfolio", """
                {
                  "DisplayName": "floci-sc-assoc-portfolio",
                  "ProviderName": "alchemy-tests",
                  "IdempotencyToken": "flociScAssocPortfolio1"
                }
                """);
        createdPortfolio.then().statusCode(200);
        String portfolioId = createdPortfolio.jsonPath().getString("PortfolioDetail.Id");
        assertTrue(portfolioId.startsWith("port-"));

        invoke("DescribePortfolio", "{\"Id\":\"" + portfolioId + "\"}")
                .then()
                .statusCode(200)
                .body("PortfolioDetail.DisplayName", equalTo("floci-sc-assoc-portfolio"))
                .body("PortfolioDetail.ARN", startsWith("arn:aws:catalog:"));

        invoke("ListPortfolios", "{}")
                .then()
                .statusCode(200)
                .body("PortfolioDetails.Id", hasItem(portfolioId));

        Response createdProduct = invoke("CreateProduct", """
                {
                  "Name": "floci-sc-assoc-product",
                  "Owner": "alchemy-tests",
                  "ProductType": "CLOUD_FORMATION_TEMPLATE",
                  "IdempotencyToken": "flociScAssocProduct1",
                  "ProvisioningArtifactParameters": {
                    "Name": "v1",
                    "Type": "CLOUD_FORMATION_TEMPLATE",
                    "Info": {"LoadTemplateFromURL": "https://example.s3.us-east-1.amazonaws.com/t.json"}
                  }
                }
                """);
        createdProduct.then().statusCode(200);
        String productId = createdProduct.jsonPath().getString("ProductViewDetail.ProductViewSummary.ProductId");
        assertTrue(productId.startsWith("prod-"));

        invoke("DescribeProductAsAdmin", "{\"Id\":\"" + productId + "\"}")
                .then()
                .statusCode(200)
                .body("ProductViewDetail.ProductViewSummary.Name", equalTo("floci-sc-assoc-product"));

        invoke("SearchProductsAsAdmin", "{}")
                .then()
                .statusCode(200)
                .body("ProductViewDetails.ProductViewSummary.ProductId", hasItem(productId));

        invoke("AssociateProductWithPortfolio",
                "{\"ProductId\":\"" + productId + "\",\"PortfolioId\":\"" + portfolioId + "\"}")
                .then()
                .statusCode(200);

        invoke("AssociateProductWithPortfolio",
                "{\"ProductId\":\"" + productId + "\",\"PortfolioId\":\"" + portfolioId + "\"}")
                .then()
                .statusCode(200);

        invoke("ListPortfoliosForProduct", "{\"ProductId\":\"" + productId + "\"}")
                .then()
                .statusCode(200)
                .body("PortfolioDetails", hasSize(1))
                .body("PortfolioDetails[0].Id", equalTo(portfolioId));

        invoke("AssociatePrincipalWithPortfolio", """
                {
                  "PortfolioId": "%s",
                  "PrincipalARN": "%s",
                  "PrincipalType": "IAM"
                }
                """.formatted(portfolioId, ROLE_ARN))
                .then()
                .statusCode(200);

        invoke("ListPrincipalsForPortfolio", "{\"PortfolioId\":\"" + portfolioId + "\"}")
                .then()
                .statusCode(200)
                .body("Principals", hasSize(1))
                .body("Principals[0].PrincipalARN", equalTo(ROLE_ARN))
                .body("Principals[0].PrincipalType", equalTo("IAM"));

        invoke("DeletePortfolio", "{\"Id\":\"" + portfolioId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceInUseException"));

        invoke("DisassociateProductFromPortfolio",
                "{\"ProductId\":\"" + productId + "\",\"PortfolioId\":\"" + portfolioId + "\"}")
                .then()
                .statusCode(200);

        invoke("DisassociatePrincipalFromPortfolio", """
                {
                  "PortfolioId": "%s",
                  "PrincipalARN": "%s",
                  "PrincipalType": "IAM"
                }
                """.formatted(portfolioId, ROLE_ARN))
                .then()
                .statusCode(200);

        invoke("DeleteProduct", "{\"Id\":\"" + productId + "\"}")
                .then()
                .statusCode(200);
        invoke("DeletePortfolio", "{\"Id\":\"" + portfolioId + "\"}")
                .then()
                .statusCode(200);

        invoke("DescribePortfolio", "{\"Id\":\"" + portfolioId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static Response invoke(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
