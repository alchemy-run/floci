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
class ConfigRuleIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "StarlingDoveService.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void putConfigRule() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ConfigRule": {
                        "ConfigRuleName": "rule-crud-test",
                        "Source": {
                            "Owner": "AWS",
                            "SourceIdentifier": "S3_ACCESS_POINT_PUBLIC_ACCESS_BLOCKS"
                        }
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void describeConfigRules() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["rule-crud-test"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigRules", hasSize(1))
            .body("ConfigRules[0].ConfigRuleName", equalTo("rule-crud-test"))
            .body("ConfigRules[0].ConfigRuleArn", notNullValue())
            .body("ConfigRules[0].ConfigRuleId", notNullValue())
            .body("ConfigRules[0].ConfigRuleState", equalTo("ACTIVE"))
            .body("ConfigRules[0].Source.Owner", equalTo("AWS"))
            .body("ConfigRules[0].Source.SourceIdentifier", equalTo("S3_ACCESS_POINT_PUBLIC_ACCESS_BLOCKS"));

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigRules", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(3)
    void describeComplianceByConfigRule() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeComplianceByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["rule-crud-test"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ComplianceByConfigRules", hasSize(1))
            .body("ComplianceByConfigRules[0].ConfigRuleName", equalTo("rule-crud-test"))
            .body("ComplianceByConfigRules[0].Compliance.ComplianceType", equalTo("INSUFFICIENT_DATA"));
    }

    @Test
    @Order(4)
    void putConfigRuleUpdate() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ConfigRule": {
                        "ConfigRuleName": "rule-crud-test",
                        "Source": {
                            "Owner": "CUSTOM_LAMBDA",
                            "SourceIdentifier": "arn:aws:lambda:us-east-1:123456789012:function:my-rule"
                        }
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["rule-crud-test"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigRules[0].Source.Owner", equalTo("CUSTOM_LAMBDA"));
    }

    @Test
    @Order(5)
    void describeConfigRuleEvaluationStatus() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRuleEvaluationStatus")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["rule-crud-test"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigRulesEvaluationStatus", hasSize(1))
            .body("ConfigRulesEvaluationStatus[0].ConfigRuleName", equalTo("rule-crud-test"))
            .body("ConfigRulesEvaluationStatus[0].ConfigRuleArn", notNullValue())
            .body("ConfigRulesEvaluationStatus[0].ConfigRuleId", notNullValue())
            .body("ConfigRulesEvaluationStatus[0].FirstEvaluationStarted", equalTo(true));
    }

    @Test
    @Order(6)
    void startConfigRulesEvaluation() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "StartConfigRulesEvaluation")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["rule-crud-test"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(7)
    void startConfigRulesEvaluationNonexistent() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "StartConfigRulesEvaluation")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["no-such-rule"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchConfigRuleException"));
    }

    @Test
    @Order(8)
    void deleteConfigRule() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleName": "rule-crud-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(9)
    void deleteNonexistentConfigRule() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleName": "no-such-rule"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchConfigRuleException"));
    }

    @Test
    @Order(10)
    void describeConfigRulesNonexistent() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["alchemy-nonexistent-config-rule-probe"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchConfigRuleException"));
    }

    @Test
    @Order(11)
    void putConfigRulePersistsDescriptionScopeAndCreateTimeTags() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ConfigRule": {
                        "ConfigRuleName": "rule-desc-scope-tags",
                        "Description": "buckets must have versioning enabled",
                        "Source": {
                            "Owner": "AWS",
                            "SourceIdentifier": "S3_BUCKET_VERSIONING_ENABLED"
                        },
                        "Scope": {
                            "ComplianceResourceTypes": ["AWS::S3::Bucket"]
                        }
                    },
                    "Tags": [
                        {"Key": "Environment", "Value": "test"},
                        {"Key": "alchemy::id", "Value": "VersioningRule"}
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["rule-desc-scope-tags"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigRules", hasSize(1))
            .body("ConfigRules[0].ConfigRuleName", equalTo("rule-desc-scope-tags"))
            .body("ConfigRules[0].Description", equalTo("buckets must have versioning enabled"))
            .body("ConfigRules[0].Scope.ComplianceResourceTypes", hasItem("AWS::S3::Bucket"))
            .body("ConfigRules[0].ConfigRuleArn", containsString(":config-rule/"))
            .body("ConfigRules[0].ConfigRuleId", startsWith("config-rule-"));

        String arn = given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["rule-desc-scope-tags"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .path("ConfigRules[0].ConfigRuleArn");

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceArn": "%s"}
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.find { it.Key == 'Environment' }.Value", equalTo("test"))
            .body("Tags.find { it.Key == 'alchemy::id' }.Value", equalTo("VersioningRule"));

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ConfigRule": {
                        "ConfigRuleName": "rule-desc-scope-tags",
                        "Description": "buckets must have versioning enabled (v2)",
                        "Source": {
                            "Owner": "AWS",
                            "SourceIdentifier": "S3_BUCKET_VERSIONING_ENABLED"
                        },
                        "Scope": {
                            "ComplianceResourceTypes": ["AWS::S3::Bucket"]
                        }
                    },
                    "Tags": [{"Key": "Environment", "Value": "prod"}]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["rule-desc-scope-tags"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigRules[0].Description", equalTo("buckets must have versioning enabled (v2)"))
            .body("ConfigRules[0].ConfigRuleArn", equalTo(arn));

        // Subsequent Put ignores Tags; create-time tags stay until TagResource.
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceArn": "%s"}
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.find { it.Key == 'Environment' }.Value", equalTo("test"));
    }
}
