package io.github.hectorvent.floci.services.apprunner;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Observability configuration lifecycle over JSON 1.0
 * ({@code Content-Type: application/x-amz-json-1.0}, {@code X-Amz-Target: AppRunner.&lt;Action&gt;}).
 */
@QuarkusTest
class AppRunnerObservabilityConfigurationIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/apprunner/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeObservabilityConfigurationOnANonexistentArnFailsWithResourceNotFoundException() {
        post("DescribeObservabilityConfiguration", """
                {"ObservabilityConfigurationArn":"arn:aws:apprunner:us-east-1:000000000000:observabilityconfiguration/missing/1/00000000000000000000000000000000"}
                """)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDescribeListReplaceAndDeleteObservabilityConfiguration() {
        String name = "floci-test-obs";
        String createdArn = post("CreateObservabilityConfiguration", """
                {
                    "ObservabilityConfigurationName": "%s",
                    "TraceConfiguration": {"Vendor": "AWSXRAY"},
                    "Tags": [{"Key": "alchemy::id", "Value": "Obs"}]
                }
                """.formatted(name))
                .then()
                .statusCode(200)
                .body("ObservabilityConfiguration.ObservabilityConfigurationName", equalTo(name))
                .body("ObservabilityConfiguration.ObservabilityConfigurationArn",
                        containsString(":observabilityconfiguration/" + name + "/"))
                .body("ObservabilityConfiguration.ObservabilityConfigurationRevision", equalTo(1))
                .body("ObservabilityConfiguration.TraceConfiguration.Vendor", equalTo("AWSXRAY"))
                .body("ObservabilityConfiguration.Status", equalTo("ACTIVE"))
                .body("ObservabilityConfiguration.Latest", equalTo(true))
                .extract().path("ObservabilityConfiguration.ObservabilityConfigurationArn");

        post("DescribeObservabilityConfiguration", """
                {"ObservabilityConfigurationArn": "%s"}
                """.formatted(createdArn))
                .then()
                .statusCode(200)
                .body("ObservabilityConfiguration.Status", equalTo("ACTIVE"))
                .body("ObservabilityConfiguration.TraceConfiguration.Vendor", equalTo("AWSXRAY"));

        post("ListObservabilityConfigurations", """
                {"ObservabilityConfigurationName": "%s", "LatestOnly": true}
                """.formatted(name))
                .then()
                .statusCode(200)
                .body("ObservabilityConfigurationSummaryList", hasSize(1))
                .body("ObservabilityConfigurationSummaryList[0].ObservabilityConfigurationArn", equalTo(createdArn));

        post("ListTagsForResource", """
                {"ResourceArn": "%s"}
                """.formatted(createdArn))
                .then()
                .statusCode(200)
                .body("Tags[0].Key", equalTo("alchemy::id"))
                .body("Tags[0].Value", equalTo("Obs"));

        post("TagResource", """
                {"ResourceArn": "%s", "Tags": [{"Key": "env", "Value": "test"}]}
                """.formatted(createdArn))
                .then()
                .statusCode(200);

        post("ListTagsForResource", """
                {"ResourceArn": "%s"}
                """.formatted(createdArn))
                .then()
                .statusCode(200)
                .body("Tags", hasSize(2));

        String revision2Arn = post("CreateObservabilityConfiguration", """
                {
                    "ObservabilityConfigurationName": "%s",
                    "TraceConfiguration": {"Vendor": "AWSXRAY"}
                }
                """.formatted(name))
                .then()
                .statusCode(200)
                .body("ObservabilityConfiguration.ObservabilityConfigurationRevision", equalTo(2))
                .body("ObservabilityConfiguration.Latest", equalTo(true))
                .extract().path("ObservabilityConfiguration.ObservabilityConfigurationArn");

        post("DescribeObservabilityConfiguration", """
                {"ObservabilityConfigurationArn": "%s"}
                """.formatted(createdArn))
                .then()
                .statusCode(200)
                .body("ObservabilityConfiguration.Latest", equalTo(false))
                .body("ObservabilityConfiguration.Status", equalTo("ACTIVE"));

        post("ListObservabilityConfigurations", """
                {"ObservabilityConfigurationName": "%s", "LatestOnly": false}
                """.formatted(name))
                .then()
                .statusCode(200)
                .body("ObservabilityConfigurationSummaryList", hasSize(2));

        post("DeleteObservabilityConfiguration", """
                {"ObservabilityConfigurationArn": "%s"}
                """.formatted(createdArn))
                .then()
                .statusCode(200)
                .body("ObservabilityConfiguration.Status", equalTo("INACTIVE"))
                .body("ObservabilityConfiguration.DeletedAt", notNullValue());

        post("DeleteObservabilityConfiguration", """
                {"ObservabilityConfigurationArn": "%s"}
                """.formatted(revision2Arn))
                .then()
                .statusCode(200)
                .body("ObservabilityConfiguration.Status", equalTo("INACTIVE"));

        post("ListObservabilityConfigurations", """
                {"ObservabilityConfigurationName": "%s"}
                """.formatted(name))
                .then()
                .statusCode(200)
                .body("ObservabilityConfigurationSummaryList", hasSize(0))
                .body("NextToken", nullValue());

        post("DeleteObservabilityConfiguration", """
                {"ObservabilityConfigurationArn": "%s"}
                """.formatted(revision2Arn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static Response post(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
