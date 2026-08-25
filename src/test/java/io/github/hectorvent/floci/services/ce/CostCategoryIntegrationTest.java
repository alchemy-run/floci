package io.github.hectorvent.floci.services.ce;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for Cost Explorer cost-category management.
 * Protocol: JSON 1.1 — {@code X-Amz-Target: AWSInsightsIndexService.&lt;Action&gt;}
 */
@QuarkusTest
class CostCategoryIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ce/aws4_request";
    private static final String RULES =
            "[{\"Value\":\"tagged\",\"Type\":\"REGULAR\","
                    + "\"Rule\":{\"Tags\":{\"Key\":\"CostCenter\",\"Values\":[\"alchemy-test\"],"
                    + "\"MatchOptions\":[\"EQUALS\"]}}}]";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeCostCategoryDefinition_missingArn_returnsResourceNotFound() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.DescribeCostCategoryDefinition")
            .header("Authorization", AUTH)
            .body("{\"CostCategoryArn\":\"arn:aws:ce::000000000000:costcategory/00000000-0000-0000-0000-000000000000\"}")
        .when()
            .post("/")
        .then()
            .statusCode(anyOf(is(400), is(404)))
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void lifecycle_createUpdateListTagDelete() {
        String name = "alchemy-test-cost-category";
        String createBody = "{"
                + "\"Name\":\"" + name + "\","
                + "\"RuleVersion\":\"CostCategoryExpression.v1\","
                + "\"Rules\":" + RULES + ","
                + "\"DefaultValue\":\"other\","
                + "\"ResourceTags\":[{\"Key\":\"fixture\",\"Value\":\"cost-explorer-cost-category\"}]"
                + "}";

        String arn = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.CreateCostCategoryDefinition")
            .header("Authorization", AUTH)
            .body(createBody)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CostCategoryArn", containsString(":costcategory/"))
            .body("EffectiveStart", notNullValue())
            .extract().path("CostCategoryArn");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.DescribeCostCategoryDefinition")
            .header("Authorization", AUTH)
            .body("{\"CostCategoryArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CostCategory.Name", equalTo(name))
            .body("CostCategory.DefaultValue", equalTo("other"))
            .body("CostCategory.Rules[0].Value", equalTo("tagged"))
            .body("CostCategory.Rules[0].Rule.Tags.Values", hasItem("alchemy-test"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.ListTagsForResource")
            .header("Authorization", AUTH)
            .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTags.Key", hasItem("fixture"))
            .body("ResourceTags.Value", hasItem("cost-explorer-cost-category"));

        String updatedRules =
                "[{\"Value\":\"tagged\",\"Type\":\"REGULAR\","
                        + "\"Rule\":{\"Tags\":{\"Key\":\"CostCenter\",\"Values\":[\"alchemy-test-updated\"],"
                        + "\"MatchOptions\":[\"EQUALS\"]}}}]";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.UpdateCostCategoryDefinition")
            .header("Authorization", AUTH)
            .body("{\"CostCategoryArn\":\"" + arn + "\","
                    + "\"RuleVersion\":\"CostCategoryExpression.v1\","
                    + "\"Rules\":" + updatedRules + ","
                    + "\"DefaultValue\":\"uncategorized\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CostCategoryArn", equalTo(arn));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.DescribeCostCategoryDefinition")
            .header("Authorization", AUTH)
            .body("{\"CostCategoryArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CostCategory.DefaultValue", equalTo("uncategorized"))
            .body("CostCategory.Rules[0].Rule.Tags.Values", hasItem("alchemy-test-updated"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.ListCostCategoryDefinitions")
            .header("Authorization", AUTH)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CostCategoryReferences.Name", hasItem(name))
            .body("CostCategoryReferences.CostCategoryArn", hasItem(arn));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.DeleteCostCategoryDefinition")
            .header("Authorization", AUTH)
            .body("{\"CostCategoryArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CostCategoryArn", equalTo(arn))
            .body("EffectiveEnd", notNullValue());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.DescribeCostCategoryDefinition")
            .header("Authorization", AUTH)
            .body("{\"CostCategoryArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(anyOf(is(400), is(404)))
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void tagAndUntagCostCategory() {
        String name = "alchemy-test-cost-category-tags";
        String arn = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.CreateCostCategoryDefinition")
            .header("Authorization", AUTH)
            .body("{\"Name\":\"" + name + "\",\"RuleVersion\":\"CostCategoryExpression.v1\","
                    + "\"Rules\":" + RULES + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CostCategoryArn");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.TagResource")
            .header("Authorization", AUTH)
            .body("{\"ResourceArn\":\"" + arn + "\",\"ResourceTags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.ListTagsForResource")
            .header("Authorization", AUTH)
            .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTags.Key", hasItem("env"))
            .body("ResourceTags.Value", hasItem("test"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.UntagResource")
            .header("Authorization", AUTH)
            .body("{\"ResourceArn\":\"" + arn + "\",\"ResourceTagKeys\":[\"env\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.ListTagsForResource")
            .header("Authorization", AUTH)
            .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTags.Key", not(hasItem("env")));
    }
}
