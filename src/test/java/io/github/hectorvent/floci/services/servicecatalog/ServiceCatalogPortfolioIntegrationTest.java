package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 1.1 Service Catalog portfolio coverage used by Alchemy's Portfolio
 * resource: create / describe / in-place update (provider, description, tags)
 * / delete, plus not-found and idempotent create.
 */
@QuarkusTest
class ServiceCatalogPortfolioIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicecatalog/aws4_request";
    private static final String TARGET = "AWS242ServiceCatalogService.";

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
    void deletePortfolio_unknownId_returnsResourceNotFound() {
        invoke("DeletePortfolio", "{\"Id\":\"port-doesnotexist1\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createUpdateTagsAndDelete() {
        Response created = invoke("CreatePortfolio", """
                {
                  "DisplayName": "floci-sc-portfolio-lifecycle",
                  "ProviderName": "alchemy-tests",
                  "Description": "portfolio lifecycle test",
                  "IdempotencyToken": "flociScPortfolioLifecycle1",
                  "Tags": [
                    {"Key": "purpose", "Value": "lifecycle"},
                    {"Key": "alchemy::id", "Value": "TestPortfolio"}
                  ]
                }
                """);
        created.then().statusCode(200);
        String portfolioId = created.jsonPath().getString("PortfolioDetail.Id");
        String arn = created.jsonPath().getString("PortfolioDetail.ARN");
        assertTrue(portfolioId.startsWith("port-"));
        assertTrue(arn.contains(":catalog:"));
        created.then()
                .body("PortfolioDetail.DisplayName", equalTo("floci-sc-portfolio-lifecycle"))
                .body("PortfolioDetail.ProviderName", equalTo("alchemy-tests"))
                .body("PortfolioDetail.Description", equalTo("portfolio lifecycle test"))
                .body("Tags.Key", hasItem("purpose"))
                .body("Tags.Key", hasItem("alchemy::id"));

        invoke("CreatePortfolio", """
                {
                  "DisplayName": "floci-sc-portfolio-lifecycle",
                  "ProviderName": "alchemy-tests",
                  "Description": "portfolio lifecycle test",
                  "IdempotencyToken": "flociScPortfolioLifecycle1"
                }
                """)
                .then()
                .statusCode(200)
                .body("PortfolioDetail.Id", equalTo(portfolioId));

        invoke("DescribePortfolio", "{\"Id\":\"" + portfolioId + "\"}")
                .then()
                .statusCode(200)
                .body("PortfolioDetail.Id", equalTo(portfolioId))
                .body("PortfolioDetail.ARN", startsWith("arn:aws:catalog:"))
                .body("PortfolioDetail.DisplayName", equalTo("floci-sc-portfolio-lifecycle"))
                .body("PortfolioDetail.ProviderName", equalTo("alchemy-tests"))
                .body("PortfolioDetail.Description", equalTo("portfolio lifecycle test"))
                .body("Tags.Key", hasItem("purpose"))
                .body("Tags.Key", hasItem("alchemy::id"));

        invoke("ListPortfolios", "{}")
                .then()
                .statusCode(200)
                .body("PortfolioDetails.Id", hasItem(portfolioId));

        invoke("UpdatePortfolio", """
                {
                  "Id": "%s",
                  "ProviderName": "alchemy-tests-updated",
                  "Description": "updated description",
                  "AddTags": [
                    {"Key": "purpose", "Value": "lifecycle-updated"},
                    {"Key": "extra", "Value": "yes"}
                  ]
                }
                """.formatted(portfolioId))
                .then()
                .statusCode(200)
                .body("PortfolioDetail.Id", equalTo(portfolioId))
                .body("PortfolioDetail.ProviderName", equalTo("alchemy-tests-updated"))
                .body("PortfolioDetail.Description", equalTo("updated description"))
                .body("Tags.Key", hasItem("purpose"))
                .body("Tags.Key", hasItem("extra"))
                .body("Tags.Key", hasItem("alchemy::id"));

        Response described = invoke("DescribePortfolio", "{\"Id\":\"" + portfolioId + "\"}");
        described.then().statusCode(200);
        assertEquals("alchemy-tests-updated",
                described.jsonPath().getString("PortfolioDetail.ProviderName"));
        assertEquals("updated description",
                described.jsonPath().getString("PortfolioDetail.Description"));
        java.util.Map<String, String> tags = new java.util.LinkedHashMap<>();
        java.util.List<java.util.Map<String, String>> tagList = described.jsonPath().getList("Tags");
        for (java.util.Map<String, String> tag : tagList) {
            tags.put(tag.get("Key"), tag.get("Value"));
        }
        assertEquals("lifecycle-updated", tags.get("purpose"));
        assertEquals("yes", tags.get("extra"));
        assertEquals("TestPortfolio", tags.get("alchemy::id"));

        invoke("UpdatePortfolio", """
                {
                  "Id": "%s",
                  "RemoveTags": ["extra"]
                }
                """.formatted(portfolioId))
                .then()
                .statusCode(200)
                .body("Tags.Key", not(hasItem("extra")))
                .body("Tags.Key", hasItem("purpose"));

        invoke("DeletePortfolio", "{\"Id\":\"" + portfolioId + "\"}")
                .then()
                .statusCode(200);

        invoke("DescribePortfolio", "{\"Id\":\"" + portfolioId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        invoke("DeletePortfolio", "{\"Id\":\"" + portfolioId + "\"}")
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
