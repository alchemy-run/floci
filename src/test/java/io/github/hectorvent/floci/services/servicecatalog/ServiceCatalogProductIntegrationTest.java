package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 1.1 Service Catalog product lifecycle used by Alchemy's Product resource:
 * create, describe (id + name), search, artifact update, tags, delete.
 */
@QuarkusTest
class ServiceCatalogProductIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicecatalog/aws4_request";
    private static final String TARGET = "AWS242ServiceCatalogService.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeProductAsAdmin_unknownId_returnsResourceNotFound() {
        invoke("DescribeProductAsAdmin", "{\"Id\":\"prod-doesnotexist1\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDescribeUpdateArtifactTagsAndDelete() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "floci-sc-product-" + suffix;
        String token = "flociScProduct" + suffix.replace("-", "");

        Response created = invoke("CreateProduct", """
                {
                  "Name": "%s",
                  "Owner": "alchemy-tests",
                  "Description": "product lifecycle test",
                  "ProductType": "CLOUD_FORMATION_TEMPLATE",
                  "IdempotencyToken": "%s",
                  "Tags": [
                    {"Key": "purpose", "Value": "lifecycle"},
                    {"Key": "alchemy::id", "Value": "TestProduct"}
                  ],
                  "ProvisioningArtifactParameters": {
                    "Name": "v1",
                    "Description": "initial version",
                    "Type": "CLOUD_FORMATION_TEMPLATE",
                    "Info": {"LoadTemplateFromURL": "https://example.s3.us-east-1.amazonaws.com/product-template.json"}
                  }
                }
                """.formatted(name, token));
        created.then()
                .statusCode(200)
                .body("ProductViewDetail.ProductViewSummary.Name", equalTo(name))
                .body("ProductViewDetail.ProductViewSummary.Owner", equalTo("alchemy-tests"))
                .body("ProductViewDetail.ProductViewSummary.Type", equalTo("CLOUD_FORMATION_TEMPLATE"))
                .body("ProductViewDetail.ProductARN", startsWith("arn:aws:catalog:"))
                .body("ProvisioningArtifactDetail.Name", equalTo("v1"))
                .body("ProvisioningArtifactDetail.Id", startsWith("pa-"));
        String productId = created.jsonPath().getString("ProductViewDetail.ProductViewSummary.ProductId");
        String artifactId = created.jsonPath().getString("ProvisioningArtifactDetail.Id");
        assertTrue(productId.startsWith("prod-"));
        assertTrue(artifactId.startsWith("pa-"));

        invoke("DescribeProductAsAdmin", "{\"Id\":\"" + productId + "\"}")
                .then()
                .statusCode(200)
                .body("ProductViewDetail.ProductViewSummary.ProductId", equalTo(productId))
                .body("ProductViewDetail.ProductViewSummary.Name", equalTo(name))
                .body("ProductViewDetail.ProductViewSummary.Owner", equalTo("alchemy-tests"))
                .body("ProductViewDetail.ProductViewSummary.Type", equalTo("CLOUD_FORMATION_TEMPLATE"))
                .body("ProvisioningArtifactSummaries", hasSize(1))
                .body("ProvisioningArtifactSummaries[0].Name", equalTo("v1"))
                .body("Tags.Key", hasItem("purpose"))
                .body("Tags.Key", hasItem("alchemy::id"));

        invoke("DescribeProductAsAdmin", "{\"Name\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("ProductViewDetail.ProductViewSummary.ProductId", equalTo(productId));

        invoke("SearchProductsAsAdmin", "{}")
                .then()
                .statusCode(200)
                .body("ProductViewDetails.ProductViewSummary.ProductId", hasItem(productId));

        invoke("ListProvisioningArtifacts", "{\"ProductId\":\"" + productId + "\"}")
                .then()
                .statusCode(200)
                .body("ProvisioningArtifactDetails", hasSize(1))
                .body("ProvisioningArtifactDetails[0].Id", equalTo(artifactId));

        invoke("UpdateProduct", """
                {
                  "Id": "%s",
                  "Owner": "alchemy-tests-updated",
                  "Description": "updated product description",
                  "SupportEmail": "support@example.com",
                  "AddTags": [{"Key": "purpose", "Value": "lifecycle-updated"}],
                  "RemoveTags": []
                }
                """.formatted(productId))
                .then()
                .statusCode(200)
                .body("ProductViewDetail.ProductViewSummary.ProductId", equalTo(productId))
                .body("ProductViewDetail.ProductViewSummary.Owner", equalTo("alchemy-tests-updated"))
                .body("ProductViewDetail.ProductViewSummary.SupportEmail", equalTo("support@example.com"));

        invoke("UpdateProvisioningArtifact", """
                {
                  "ProductId": "%s",
                  "ProvisioningArtifactId": "%s",
                  "Name": "v1",
                  "Description": "updated version description"
                }
                """.formatted(productId, artifactId))
                .then()
                .statusCode(200)
                .body("ProvisioningArtifactDetail.Description", equalTo("updated version description"));

        invoke("DescribeProductAsAdmin", "{\"Id\":\"" + productId + "\"}")
                .then()
                .statusCode(200)
                .body("ProductViewDetail.ProductViewSummary.ProductId", equalTo(productId))
                .body("ProductViewDetail.ProductViewSummary.Owner", equalTo("alchemy-tests-updated"))
                .body("ProductViewDetail.ProductViewSummary.SupportEmail", equalTo("support@example.com"))
                .body("ProvisioningArtifactSummaries[0].Description", equalTo("updated version description"));

        invoke("DeleteProduct", "{\"Id\":\"" + productId + "\"}")
                .then()
                .statusCode(200);

        invoke("DescribeProductAsAdmin", "{\"Id\":\"" + productId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createProduct_sameIdempotencyToken_returnsExisting() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "floci-sc-product-idem-" + suffix;
        String token = "flociScProductIdem" + suffix.replace("-", "");
        String body = """
                {
                  "Name": "%s",
                  "Owner": "alchemy-tests",
                  "ProductType": "CLOUD_FORMATION_TEMPLATE",
                  "IdempotencyToken": "%s",
                  "ProvisioningArtifactParameters": {
                    "Name": "v1",
                    "Info": {"LoadTemplateFromURL": "https://example.s3.us-east-1.amazonaws.com/t.json"}
                  }
                }
                """.formatted(name, token);

        String firstId = invoke("CreateProduct", body)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("ProductViewDetail.ProductViewSummary.ProductId");
        invoke("CreateProduct", body)
                .then()
                .statusCode(200)
                .body("ProductViewDetail.ProductViewSummary.ProductId", equalTo(firstId));

        invoke("DeleteProduct", "{\"Id\":\"" + firstId + "\"}")
                .then()
                .statusCode(200);
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
