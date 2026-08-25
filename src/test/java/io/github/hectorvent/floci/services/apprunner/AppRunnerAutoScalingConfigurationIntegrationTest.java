package io.github.hectorvent.floci.services.apprunner;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Wire-level coverage for App Runner auto scaling configurations — the
 * operations Alchemy {@code AutoScalingConfiguration.test.ts} exercises.
 */
@QuarkusTest
class AppRunnerAutoScalingConfigurationIntegrationTest {

    private static final String TARGET = "AppRunner.";
    private static final String MISSING_ARN =
            "arn:aws:apprunner:us-west-2:000000000000:autoscalingconfiguration/"
                    + "alchemy-nonexistent-probe/1/00000000000000000000000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification call(String action) {
        return given()
                .header("X-Amz-Target", TARGET + action)
                .contentType(CONTENT_TYPE_AWS_JSON_1_0);
    }

    @Test
    void describeMissingArnFailsWithResourceNotFoundException() {
        call("DescribeAutoScalingConfiguration")
                .body("{\"AutoScalingConfigurationArn\":\"" + MISSING_ARN + "\"}")
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createReviseListDeleteAndTagAnAutoScalingConfiguration() {
        String name = "asc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String renamed = name + "b";

        String arn = call("CreateAutoScalingConfiguration")
                .body("""
                        {
                          "AutoScalingConfigurationName": "%s",
                          "MaxConcurrency": 50,
                          "MinSize": 1,
                          "MaxSize": 3,
                          "Tags": [{"Key": "env", "Value": "test"}]
                        }
                        """.formatted(name))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("AutoScalingConfiguration.AutoScalingConfigurationName", equalTo(name))
                .body("AutoScalingConfiguration.AutoScalingConfigurationRevision", equalTo(1))
                .body("AutoScalingConfiguration.MaxConcurrency", equalTo(50))
                .body("AutoScalingConfiguration.MinSize", equalTo(1))
                .body("AutoScalingConfiguration.MaxSize", equalTo(3))
                .body("AutoScalingConfiguration.Status", equalTo("active"))
                .body("AutoScalingConfiguration.Latest", equalTo(true))
                .body("AutoScalingConfiguration.AutoScalingConfigurationArn",
                        startsWith("arn:aws:apprunner:"))
                .extract().path("AutoScalingConfiguration.AutoScalingConfigurationArn");

        call("DescribeAutoScalingConfiguration")
                .body("{\"AutoScalingConfigurationArn\":\"" + arn + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("AutoScalingConfiguration.Status", equalTo("active"))
                .body("AutoScalingConfiguration.MaxConcurrency", equalTo(50));

        call("ListTagsForResource")
                .body("{\"ResourceArn\":\"" + arn + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Tags[0].Key", equalTo("env"))
                .body("Tags[0].Value", equalTo("test"));

        call("TagResource")
                .body("""
                        {"ResourceArn":"%s","Tags":[{"Key":"team","Value":"platform"}]}
                        """.formatted(arn))
                .when().post("/")
                .then()
                .statusCode(200);

        call("UntagResource")
                .body("""
                        {"ResourceArn":"%s","TagKeys":["env"]}
                        """.formatted(arn))
                .when().post("/")
                .then()
                .statusCode(200);

        call("ListTagsForResource")
                .body("{\"ResourceArn\":\"" + arn + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Tags.size()", equalTo(1))
                .body("Tags[0].Key", equalTo("team"));

        String revisedArn = call("CreateAutoScalingConfiguration")
                .body("""
                        {
                          "AutoScalingConfigurationName": "%s",
                          "MaxConcurrency": 80,
                          "MinSize": 1,
                          "MaxSize": 3
                        }
                        """.formatted(name))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("AutoScalingConfiguration.AutoScalingConfigurationRevision", equalTo(2))
                .body("AutoScalingConfiguration.MaxConcurrency", equalTo(80))
                .body("AutoScalingConfiguration.Latest", equalTo(true))
                .body("AutoScalingConfiguration.AutoScalingConfigurationArn", not(equalTo(arn)))
                .extract().path("AutoScalingConfiguration.AutoScalingConfigurationArn");

        call("ListAutoScalingConfigurations")
                .body("""
                        {"AutoScalingConfigurationName":"%s","LatestOnly":true}
                        """.formatted(name))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("AutoScalingConfigurationSummaryList.size()", equalTo(1))
                .body("AutoScalingConfigurationSummaryList[0].AutoScalingConfigurationArn",
                        equalTo(revisedArn))
                .body("AutoScalingConfigurationSummaryList[0].AutoScalingConfigurationRevision",
                        equalTo(2))
                .body("AutoScalingConfigurationSummaryList[0].Status", equalTo("active"));

        String namePartial = arn.substring(0, arn.indexOf(name) + name.length());

        call("DeleteAutoScalingConfiguration")
                .body("""
                        {
                          "AutoScalingConfigurationArn": "%s",
                          "DeleteAllRevisions": true
                        }
                        """.formatted(arn))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));

        call("DeleteAutoScalingConfiguration")
                .body("""
                        {
                          "AutoScalingConfigurationArn": "%s",
                          "DeleteAllRevisions": true
                        }
                        """.formatted(namePartial))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("AutoScalingConfiguration.Status", equalTo("inactive"));

        call("ListAutoScalingConfigurations")
                .body("""
                        {"AutoScalingConfigurationName":"%s","LatestOnly":true}
                        """.formatted(name))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("AutoScalingConfigurationSummaryList[0].Status", equalTo("inactive"));

        call("CreateAutoScalingConfiguration")
                .body("""
                        {
                          "AutoScalingConfigurationName": "%s",
                          "MaxConcurrency": 50,
                          "MinSize": 1,
                          "MaxSize": 3
                        }
                        """.formatted(renamed))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("AutoScalingConfiguration.AutoScalingConfigurationName", equalTo(renamed))
                .body("AutoScalingConfiguration.AutoScalingConfigurationRevision", greaterThan(0));

        String renamedPartial = "arn:aws:apprunner:us-east-1:000000000000:autoscalingconfiguration/" + renamed;
        call("DeleteAutoScalingConfiguration")
                .body("""
                        {
                          "AutoScalingConfigurationArn": "%s",
                          "DeleteAllRevisions": true
                        }
                        """.formatted(renamedPartial))
                .when().post("/")
                .then()
                .statusCode(200);
    }
}
