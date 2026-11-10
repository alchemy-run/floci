package io.floci.aws.services.ce;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cost categories over JSON 1.1: definition lifecycle and tags, and the category values the rules assign to a
 * running, tagged EC2 instance through {@code GetCostAndUsage} filters and group-bys and {@code GetCostCategories}.
 */
@QuarkusTest
class CostCategoryIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ce/aws4_request";
    private static final String EC2_AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ec2/aws4_request";
    private static final String TEAM = "team-" + UUID.randomUUID().toString().substring(0, 8);
    private static String instanceId;
    private static int port;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** Launches the tagged instance on first use: Quarkus points RestAssured at the app only once a test runs. */
    private static synchronized String instance() {
        if (instanceId == null) {
            port = RestAssured.port;
            instanceId = launch();
        }
        return instanceId;
    }

    private static String launch() {
        return given().header("Authorization", EC2_AUTH)
                .formParam("Action", "RunInstances").formParam("ImageId", "ami-ce-category")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", "1").formParam("MaxCount", "1")
                .formParam("TagSpecification.1.ResourceType", "instance")
                .formParam("TagSpecification.1.Tag.1.Key", "CostTeam")
                .formParam("TagSpecification.1.Tag.1.Value", TEAM)
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    @AfterAll
    static void terminate() {
        if (instanceId == null) {
            return;
        }
        // Quarkus resets RestAssured's port between tests, so reach the app on the port the instance was made on.
        given().port(port).header("Authorization", EC2_AUTH).formParam("Action", "TerminateInstances")
                .formParam("InstanceId.1", instanceId).post("/");
    }

    static ValidatableResponse call(String action, String body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSInsightsIndexService." + action)
                .header("Authorization", AUTH)
                .body(body).post("/").then();
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private static String tomorrow() {
        return LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();
    }

    @Test
    void definitionLifecycleAndTags() {
        instance();
        String name = "lifecycle-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = call("CreateCostCategoryDefinition", "{\"Name\":\"" + name + "\",\"RuleVersion\":"
                + "\"CostCategoryExpression.v1\",\"Rules\":[{\"Value\":\"tagged\",\"Type\":\"REGULAR\",\"Rule\":"
                + "{\"Tags\":{\"Key\":\"CostCenter\",\"Values\":[\"a\"],\"MatchOptions\":[\"EQUALS\"]}}}],"
                + "\"DefaultValue\":\"other\",\"ResourceTags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
                .statusCode(200).body("EffectiveStart", startsWith(today().substring(0, 8) + "01T00:00:00"))
                .extract().path("CostCategoryArn");
        assertTrue(arn.startsWith("arn:aws:ce::000000000000:costcategory/"), arn);
        call("CreateCostCategoryDefinition", "{\"Name\":\"" + name + "\",\"RuleVersion\":"
                + "\"CostCategoryExpression.v1\",\"Rules\":[{\"Value\":\"x\",\"Rule\":{\"Dimensions\":{\"Key\":"
                + "\"SERVICE\",\"Values\":[\"AmazonEC2\"]}}}]}").statusCode(400)
                .body("__type", containsString("ValidationException"));

        call("DescribeCostCategoryDefinition", "{\"CostCategoryArn\":\"" + arn + "\"}").statusCode(200)
                .body("CostCategory.Name", equalTo(name))
                .body("CostCategory.DefaultValue", equalTo("other"))
                .body("CostCategory.Rules[0].Rule.Tags.Values", hasItem("a"))
                .body("CostCategory.ProcessingStatus[0].Status", equalTo("APPLIED"));
        call("ListCostCategoryDefinitions", "{}").statusCode(200)
                .body("CostCategoryReferences.find { it.CostCategoryArn == '" + arn + "' }.NumberOfRules",
                        equalTo(1))
                .body("CostCategoryReferences.find { it.CostCategoryArn == '" + arn + "' }.Values", hasItem("tagged"));
        call("ListCostCategoryResourceAssociations", "{\"CostCategoryArn\":\"" + arn + "\"}").statusCode(200)
                .body("CostCategoryResourceAssociations", hasSize(0));

        call("UpdateCostCategoryDefinition", "{\"CostCategoryArn\":\"" + arn + "\",\"RuleVersion\":"
                + "\"CostCategoryExpression.v1\",\"Rules\":[{\"Value\":\"tagged\",\"Rule\":{\"Tags\":{\"Key\":"
                + "\"CostCenter\",\"Values\":[\"b\"]}}}]}").statusCode(200);
        call("DescribeCostCategoryDefinition", "{\"CostCategoryArn\":\"" + arn + "\"}").statusCode(200)
                .body("CostCategory.Rules[0].Rule.Tags.Values", hasItem("b"))
                .body("CostCategory", not(hasKey("DefaultValue")));

        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}").statusCode(200)
                .body("ResourceTags.Key", hasItem("env"));
        call("TagResource", "{\"ResourceArn\":\"" + arn + "\",\"ResourceTags\":[{\"Key\":\"owner\",\"Value\":"
                + "\"me\"}]}").statusCode(200);
        call("UntagResource", "{\"ResourceArn\":\"" + arn + "\",\"ResourceTagKeys\":[\"env\"]}").statusCode(200);
        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}").statusCode(200)
                .body("ResourceTags.Key", hasItem("owner")).body("ResourceTags.Key", not(hasItem("env")));

        call("DeleteCostCategoryDefinition", "{\"CostCategoryArn\":\"" + arn + "\"}").statusCode(200)
                .body("CostCategoryArn", equalTo(arn));
        call("DescribeCostCategoryDefinition", "{\"CostCategoryArn\":\"" + arn + "\"}").statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
        call("DeleteCostCategoryDefinition", "{\"CostCategoryArn\":\"" + arn + "\"}").statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void rejectsInvalidRules() {
        instance();
        call("CreateCostCategoryDefinition", "{\"Name\":\"bad-version\",\"RuleVersion\":\"v2\",\"Rules\":"
                + "[{\"Value\":\"x\",\"Rule\":{\"Dimensions\":{\"Key\":\"SERVICE\",\"Values\":[\"a\"]}}}]}")
                .statusCode(400).body("message", containsString("RuleVersion"));
        call("CreateCostCategoryDefinition", "{\"Name\":\"bad-dimension\",\"RuleVersion\":"
                + "\"CostCategoryExpression.v1\",\"Rules\":[{\"Value\":\"x\",\"Rule\":{\"Dimensions\":{\"Key\":"
                + "\"AZ\",\"Values\":[\"a\"]}}}]}").statusCode(400).body("message", containsString("AZ"));
        call("CreateCostCategoryDefinition", "{\"Name\":\"bad-start\",\"RuleVersion\":"
                + "\"CostCategoryExpression.v1\",\"EffectiveStart\":\"" + today().substring(0, 8) + "15T00:00:00Z\","
                + "\"Rules\":[{\"Value\":\"x\",\"Rule\":{\"Dimensions\":{\"Key\":\"SERVICE\",\"Values\":[\"a\"]}}}]}")
                .statusCode(400).body("message", containsString("beginning of a month"));
        call("CreateCostCategoryDefinition", "{\"Name\":\"bad-split\",\"RuleVersion\":"
                + "\"CostCategoryExpression.v1\",\"Rules\":[{\"Value\":\"x\",\"Rule\":{\"Dimensions\":{\"Key\":"
                + "\"SERVICE\",\"Values\":[\"a\"]}}}],\"SplitChargeRules\":[{\"Source\":\"x\",\"Targets\":[\"y\"],"
                + "\"Method\":\"EVEN\"}]}").statusCode(400).body("message", containsString("Targets"));
    }

    @Test
    void rulesAssignValuesToUsage() {
        instance();
        String name = "teams-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = call("CreateCostCategoryDefinition", "{\"Name\":\"" + name + "\",\"RuleVersion\":"
                + "\"CostCategoryExpression.v1\",\"Rules\":["
                + "{\"Value\":\"Compute\",\"Rule\":{\"And\":[{\"Dimensions\":{\"Key\":\"SERVICE\",\"Values\":"
                + "[\"AmazonEC2\"]}},{\"Tags\":{\"Key\":\"CostTeam\",\"Values\":[\"" + TEAM.substring(0, 5) + "\"],"
                + "\"MatchOptions\":[\"STARTS_WITH\"]}}]}},"
                + "{\"Type\":\"INHERITED_VALUE\",\"InheritedValue\":{\"DimensionName\":\"TAG\","
                + "\"DimensionKey\":\"CostTeam\"}}],\"DefaultValue\":\"Shared\"}")
                .statusCode(200).extract().path("CostCategoryArn");
        String period = "\"TimePeriod\":{\"Start\":\"" + today() + "\",\"End\":\"" + tomorrow() + "\"}";

        call("GetCostAndUsage", "{" + period + ",\"Granularity\":\"DAILY\",\"Metrics\":[\"UnblendedCost\"],"
                + "\"GroupBy\":[{\"Type\":\"COST_CATEGORY\",\"Key\":\"" + name + "\"}]}").statusCode(200)
                .body("ResultsByTime[0].Groups.Keys.flatten()", hasItems(name + "$Compute", name + "$Shared"));
        String amount = call("GetCostAndUsage", "{" + period + ",\"Granularity\":\"DAILY\",\"Metrics\":"
                + "[\"UnblendedCost\"],\"Filter\":{\"CostCategories\":{\"Key\":\"" + name + "\",\"Values\":"
                + "[\"Compute\"]}},\"GroupBy\":[{\"Type\":\"DIMENSION\",\"Key\":\"SERVICE\"}]}").statusCode(200)
                .body("ResultsByTime[0].Groups.Keys.flatten()", everyItem(equalTo("AmazonEC2")))
                .extract().path("ResultsByTime[0].Total.UnblendedCost.Amount");
        assertTrue(Double.parseDouble(amount) > 0, amount);
        call("GetCostAndUsage", "{" + period + ",\"Granularity\":\"DAILY\",\"Metrics\":[\"UnblendedCost\"],"
                + "\"GroupBy\":[{\"Type\":\"TAG\",\"Key\":\"CostTeam\"}]}").statusCode(200)
                .body("ResultsByTime[0].Groups.Keys.flatten()", hasItem("CostTeam$" + TEAM));

        call("GetCostCategories", "{" + period + "}").statusCode(200)
                .body("CostCategoryNames", hasItem(name));
        call("GetCostCategories", "{" + period + ",\"CostCategoryName\":\"" + name + "\"}").statusCode(200)
                .body("CostCategoryValues", hasItems("Compute", "Shared"));
        call("GetDimensionValues", "{" + period + ",\"Dimension\":\"SERVICE\",\"Filter\":{\"CostCategories\":"
                + "{\"Key\":\"" + name + "\",\"Values\":[\"Compute\"]}}}").statusCode(200)
                .body("DimensionValues.Value", equalTo(List.of("AmazonEC2")));

        call("DeleteCostCategoryDefinition", "{\"CostCategoryArn\":\"" + arn + "\"}").statusCode(200);
        call("GetCostCategories", "{" + period + "}").statusCode(200)
                .body("CostCategoryNames", not(hasItem(name)));
    }
}
