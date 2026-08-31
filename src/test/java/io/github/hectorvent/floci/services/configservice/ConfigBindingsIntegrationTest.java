package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "StarlingDoveService.";
    private static final String RULE = "bindings-eval-rule";
    private static final String RESOURCE_TYPE = "MyCompany::Alchemy::Fixture";
    private static final String RESOURCE_ID = "alchemy-config-bindings-fixture";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static io.restassured.response.Response post(String action, String body) {
        return given()
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .contentType(CONTENT_TYPE)
                .body(body)
                .when()
                .post("/");
    }

    @Test
    @Order(1)
    void putAndStartRecorder() {
        post("PutConfigurationRecorder", """
                {
                    "ConfigurationRecorder": {
                        "name": "default",
                        "roleARN": "arn:aws:iam::000000000000:role/config-role",
                        "recordingGroup": { "allSupported": true }
                    }
                }
                """).then().statusCode(200);

        post("StartConfigurationRecorder", """
                {"ConfigurationRecorderName": "default"}
                """).then().statusCode(200);
    }

    @Test
    @Order(2)
    void selectResourceConfigReturnsResultsArray() {
        post("SelectResourceConfig", """
                {"Expression": "SELECT resourceId WHERE resourceType = 'AWS::S3::Bucket'"}
                """)
                .then()
                .statusCode(200)
                .body("Results", notNullValue())
                .body("Results", hasSize(greaterThanOrEqualTo(0)));
    }

    @Test
    @Order(3)
    void listDiscoveredResourcesEmptyUntilPut() {
        post("ListDiscoveredResources", """
                {"resourceType": "AWS::S3::Bucket"}
                """)
                .then()
                .statusCode(200)
                .body("resourceIdentifiers", notNullValue());
    }

    @Test
    @Order(4)
    void getDiscoveredResourceCounts() {
        post("GetDiscoveredResourceCounts", "{}")
                .then()
                .statusCode(200)
                .body("totalDiscoveredResources", notNullValue())
                .body("resourceCounts", notNullValue());
    }

    @Test
    @Order(5)
    void batchGetUnknownResourceIsUnprocessed() {
        post("BatchGetResourceConfig", """
                {
                    "resourceKeys": [
                        {"resourceType": "AWS::S3::Bucket", "resourceId": "alchemy-nonexistent-bucket-probe"}
                    ]
                }
                """)
                .then()
                .statusCode(200)
                .body("baseConfigurationItems", hasSize(0))
                .body("unprocessedResourceKeys", hasSize(1))
                .body("unprocessedResourceKeys[0].resourceId", equalTo("alchemy-nonexistent-bucket-probe"));
    }

    @Test
    @Order(6)
    void getResourceConfigHistoryUnknownThrows() {
        post("GetResourceConfigHistory", """
                {"resourceType": "AWS::S3::Bucket", "resourceId": "alchemy-nonexistent-bucket-probe"}
                """)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotDiscoveredException"));
    }

    @Test
    @Order(7)
    void complianceReadsReturnEmptyCollections() {
        post("DescribeComplianceByResource", """
                {"ResourceType": "AWS::S3::Bucket"}
                """)
                .then()
                .statusCode(200)
                .body("ComplianceByResources", notNullValue());

        post("GetComplianceDetailsByResource", """
                {"ResourceType": "AWS::S3::Bucket", "ResourceId": "alchemy-nonexistent-bucket-probe"}
                """)
                .then()
                .statusCode(200)
                .body("EvaluationResults", notNullValue());

        post("GetComplianceSummaryByConfigRule", "{}")
                .then()
                .statusCode(200)
                .body("ComplianceSummary", notNullValue());

        post("GetComplianceSummaryByResourceType", "{}")
                .then()
                .statusCode(200)
                .body("ComplianceSummariesByResourceType", notNullValue());
    }

    @Test
    @Order(8)
    void putEvaluationsTestModeSucceeds() {
        post("PutEvaluations", """
                {
                    "ResultToken": "alchemy-config-bindings-test-token",
                    "TestMode": true,
                    "Evaluations": [
                        {
                            "ComplianceResourceType": "AWS::S3::Bucket",
                            "ComplianceResourceId": "alchemy-nonexistent-bucket-probe",
                            "ComplianceType": "COMPLIANT",
                            "OrderingTimestamp": 1
                        }
                    ]
                }
                """)
                .then()
                .statusCode(200)
                .body("FailedEvaluations", notNullValue());
    }

    @Test
    @Order(9)
    void putExternalEvaluationRejectsManagedRule() {
        post("PutConfigRule", """
                {
                    "ConfigRule": {
                        "ConfigRuleName": "%s",
                        "Source": {
                            "Owner": "AWS",
                            "SourceIdentifier": "S3_BUCKET_VERSIONING_ENABLED"
                        }
                    }
                }
                """.formatted(RULE)).then().statusCode(200);

        post("PutExternalEvaluation", """
                {
                    "ConfigRuleName": "%s",
                    "ExternalEvaluation": {
                        "ComplianceResourceType": "AWS::S3::Bucket",
                        "ComplianceResourceId": "alchemy-nonexistent-bucket-probe",
                        "ComplianceType": "NOT_APPLICABLE",
                        "OrderingTimestamp": 1
                    }
                }
                """.formatted(RULE))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));

        post("GetComplianceDetailsByConfigRule", """
                {"ConfigRuleName": "%s"}
                """.formatted(RULE))
                .then()
                .statusCode(200)
                .body("EvaluationResults", notNullValue());
    }

    @Test
    @Order(10)
    void putAndDeleteCustomResourceConfig() {
        post("PutResourceConfig", """
                {
                    "ResourceType": "%s",
                    "SchemaVersionId": "1.0",
                    "ResourceId": "%s",
                    "Configuration": "{\\"fixture\\":true}"
                }
                """.formatted(RESOURCE_TYPE, RESOURCE_ID))
                .then()
                .statusCode(200);

        post("ListDiscoveredResources", """
                {"resourceType": "%s"}
                """.formatted(RESOURCE_TYPE))
                .then()
                .statusCode(200)
                .body("resourceIdentifiers", hasSize(1))
                .body("resourceIdentifiers[0].resourceId", equalTo(RESOURCE_ID));

        post("GetDiscoveredResourceCounts", "{}")
                .then()
                .statusCode(200)
                .body("totalDiscoveredResources", greaterThanOrEqualTo(1));

        post("BatchGetResourceConfig", """
                {
                    "resourceKeys": [
                        {"resourceType": "%s", "resourceId": "%s"}
                    ]
                }
                """.formatted(RESOURCE_TYPE, RESOURCE_ID))
                .then()
                .statusCode(200)
                .body("baseConfigurationItems", hasSize(1))
                .body("unprocessedResourceKeys", hasSize(0));

        post("GetResourceConfigHistory", """
                {"resourceType": "%s", "resourceId": "%s"}
                """.formatted(RESOURCE_TYPE, RESOURCE_ID))
                .then()
                .statusCode(200)
                .body("configurationItems", hasSize(1));

        post("DeleteResourceConfig", """
                {"ResourceType": "%s", "ResourceId": "%s"}
                """.formatted(RESOURCE_TYPE, RESOURCE_ID))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(11)
    void putResourceConfigWithoutRunningRecorderFails() {
        post("StopConfigurationRecorder", """
                {"ConfigurationRecorderName": "default"}
                """).then().statusCode(200);

        post("PutResourceConfig", """
                {
                    "ResourceType": "%s",
                    "SchemaVersionId": "1.0",
                    "ResourceId": "%s",
                    "Configuration": "{}"
                }
                """.formatted(RESOURCE_TYPE, RESOURCE_ID))
                .then()
                .statusCode(400)
                .body("__type", equalTo("NoRunningConfigurationRecorderException"));

        post("DeleteResourceConfig", """
                {"ResourceType": "%s", "ResourceId": "%s"}
                """.formatted(RESOURCE_TYPE, RESOURCE_ID))
                .then()
                .statusCode(400)
                .body("__type", equalTo("NoRunningConfigurationRecorderException"));

        post("StartConfigurationRecorder", """
                {"ConfigurationRecorderName": "default"}
                """).then().statusCode(200);
    }

    @Test
    @Order(12)
    void startAndGetResourceEvaluation() {
        String id = post("StartResourceEvaluation", """
                {
                    "EvaluationMode": "PROACTIVE",
                    "ResourceDetails": {
                        "ResourceId": "alchemy-config-bindings-proactive",
                        "ResourceType": "AWS::S3::Bucket",
                        "ResourceConfiguration": "{\\"BucketName\\":\\"alchemy-config-bindings-proactive\\"}",
                        "ResourceConfigurationSchemaType": "CFN_RESOURCE_SCHEMA"
                    }
                }
                """)
                .then()
                .statusCode(200)
                .body("ResourceEvaluationId", notNullValue())
                .extract()
                .path("ResourceEvaluationId");

        post("GetResourceEvaluationSummary", """
                {"ResourceEvaluationId": "%s"}
                """.formatted(id))
                .then()
                .statusCode(200)
                .body("ResourceEvaluationId", equalTo(id))
                .body("EvaluationStatus.Status", equalTo("SUCCEEDED"));

        post("ListResourceEvaluations", """
                {"Filters": {"EvaluationMode": "PROACTIVE"}}
                """)
                .then()
                .statusCode(200)
                .body("ResourceEvaluations", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(13)
    void getResourceEvaluationSummaryUnknownThrows() {
        post("GetResourceEvaluationSummary", """
                {"ResourceEvaluationId": "missing-eval"}
                """)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }
}
