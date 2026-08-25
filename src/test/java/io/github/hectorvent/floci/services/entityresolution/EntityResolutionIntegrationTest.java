package io.github.hectorvent.floci.services.entityresolution;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/** Verifies Entity Resolution restJson1 schema, workflow, job, and match APIs. */
@QuarkusTest
class EntityResolutionIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getSchemaMappingOnANonexistentNameFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .when()
                .get("/schemas/does-not-exist")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void schemaMatchingWorkflowJobsMatchIdAndUniqueIdLifecycle() {
        String authorization = auth(EAST);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String schemaName = "schema-" + suffix;
        String workflowName = "match-" + suffix;

        String schemaArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "schemaName":"%s",
                          "mappedInputFields":[
                            {"fieldName":"id","type":"UNIQUE_ID"},
                            {"fieldName":"email","type":"EMAIL_ADDRESS","matchKey":"email"},
                            {"fieldName":"name","type":"NAME","matchKey":"name"}
                          ],
                          "tags":{"Environment":"test"}
                        }
                        """.formatted(schemaName))
                .when()
                .post("/schemas")
                .then()
                .statusCode(200)
                .body("schemaName", equalTo(schemaName))
                .body("schemaArn", notNullValue())
                .extract().path("schemaArn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/schemas/" + schemaName)
                .then()
                .statusCode(200)
                .body("schemaName", equalTo(schemaName))
                .body("schemaArn", equalTo(schemaArn))
                .body("hasWorkflows", equalTo(false))
                .body("mappedInputFields.size()", equalTo(3));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(schemaArn))
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"));

        String glueTableArn = "arn:aws:glue:" + EAST + ":000000000000:table/db/customers";
        String workflowArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "workflowName":"%s",
                          "inputSourceConfig":[
                            {"inputSourceARN":"%s","schemaName":"%s"}
                          ],
                          "outputSourceConfig":[
                            {"outputS3Path":"s3://bucket/matches/","output":[{"name":"id"},{"name":"email"}]}
                          ],
                          "resolutionTechniques":{
                            "resolutionType":"RULE_MATCHING",
                            "ruleBasedProperties":{
                              "rules":[{"ruleName":"ByEmail","matchingKeys":["email"]}],
                              "attributeMatchingModel":"ONE_TO_ONE"
                            }
                          },
                          "roleArn":"arn:aws:iam::000000000000:role/er",
                          "tags":{"Environment":"test"}
                        }
                        """.formatted(workflowName, glueTableArn, schemaName))
                .when()
                .post("/matchingworkflows")
                .then()
                .statusCode(200)
                .body("workflowName", equalTo(workflowName))
                .body("workflowArn", notNullValue())
                .extract().path("workflowArn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/matchingworkflows/" + workflowName)
                .then()
                .statusCode(200)
                .body("workflowArn", equalTo(workflowArn))
                .body("roleArn", equalTo("arn:aws:iam::000000000000:role/er"))
                .body("resolutionTechniques.resolutionType", equalTo("RULE_MATCHING"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/matchingworkflows/" + workflowName + "/jobs")
                .then()
                .statusCode(200)
                .body("jobs", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/matchingworkflows/" + workflowName + "/jobs/00000000000000000000000000000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"record":{"id":"1","email":"jane@example.com","name":"Jane Doe"}}
                        """)
                .when()
                .post("/matchingworkflows/" + workflowName + "/matches")
                .then()
                .statusCode(200)
                .body("matchId", nullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "records":[
                            {
                              "inputSourceARN":"%s",
                              "uniqueId":"1",
                              "recordAttributeMap":{"id":"1","email":"jane@example.com","name":"Jane Doe"}
                            },
                            {
                              "inputSourceARN":"%s",
                              "uniqueId":"2",
                              "recordAttributeMap":{"id":"2","email":"jane@example.com","name":"Jane D"}
                            }
                          ]
                        }
                        """.formatted(glueTableArn, glueTableArn))
                .when()
                .post("/matchingworkflows/" + workflowName + "/generateMatches")
                .then()
                .statusCode(200)
                .body("matchGroups.size()", greaterThanOrEqualTo(1))
                .body("matchGroups[0].matchRule", equalTo("ByEmail"))
                .body("failedRecords.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .header("uniqueIds", "ghost-1,ghost-2")
                .when()
                .delete("/matchingworkflows/" + workflowName + "/uniqueids")
                .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("errors.size()", equalTo(2));

        String sourceNs = "source-" + suffix;
        String targetNs = "target-" + suffix;
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "idNamespaceName":"%s",
                          "type":"SOURCE",
                          "inputSourceConfig":[{"inputSourceARN":"%s","schemaName":"%s"}],
                          "idMappingWorkflowProperties":[{
                            "idMappingType":"RULE_BASED",
                            "ruleBasedProperties":{
                              "ruleDefinitionTypes":["TARGET"],
                              "attributeMatchingModel":"ONE_TO_ONE",
                              "recordMatchingModels":["ONE_SOURCE_TO_ONE_TARGET"]
                            }
                          }],
                          "roleArn":"arn:aws:iam::000000000000:role/er"
                        }
                        """.formatted(sourceNs, glueTableArn, schemaName))
                .when()
                .post("/idnamespaces")
                .then()
                .statusCode(200)
                .body("type", equalTo("SOURCE"));

        String sourceArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/idnamespaces/" + sourceNs)
                .then()
                .statusCode(200)
                .body("idNamespaceName", equalTo(sourceNs))
                .extract().path("idNamespaceArn");

        String targetArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "idNamespaceName":"%s",
                          "type":"TARGET",
                          "inputSourceConfig":[{"inputSourceARN":"%s"}],
                          "idMappingWorkflowProperties":[{
                            "idMappingType":"RULE_BASED",
                            "ruleBasedProperties":{
                              "rules":[{"ruleName":"ByEmail","matchingKeys":["email"]}],
                              "ruleDefinitionTypes":["TARGET"],
                              "attributeMatchingModel":"ONE_TO_ONE",
                              "recordMatchingModels":["ONE_SOURCE_TO_ONE_TARGET"]
                            }
                          }],
                          "roleArn":"arn:aws:iam::000000000000:role/er"
                        }
                        """.formatted(targetNs, workflowArn))
                .when()
                .post("/idnamespaces")
                .then()
                .statusCode(200)
                .body("type", equalTo("TARGET"))
                .extract().path("idNamespaceArn");

        String mappingName = "idmap-" + suffix;
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "workflowName":"%s",
                          "inputSourceConfig":[
                            {"inputSourceARN":"%s","type":"SOURCE"},
                            {"inputSourceARN":"%s","type":"TARGET"}
                          ],
                          "idMappingTechniques":{
                            "idMappingType":"RULE_BASED",
                            "ruleBasedProperties":{
                              "ruleDefinitionType":"TARGET",
                              "attributeMatchingModel":"ONE_TO_ONE",
                              "recordMatchingModel":"ONE_SOURCE_TO_ONE_TARGET"
                            }
                          },
                          "outputSourceConfig":[{"outputS3Path":"s3://bucket/idmapping/"}],
                          "roleArn":"arn:aws:iam::000000000000:role/er"
                        }
                        """.formatted(mappingName, sourceArn, targetArn))
                .when()
                .post("/idmappingworkflows")
                .then()
                .statusCode(200)
                .body("workflowName", equalTo(mappingName));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/idmappingworkflows/" + mappingName + "/jobs")
                .then()
                .statusCode(200)
                .body("jobs", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/idmappingworkflows/" + mappingName + "/jobs/00000000000000000000000000000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/idmappingworkflows/" + mappingName)
                .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .when()
                .delete("/idnamespaces/" + sourceNs)
                .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .when()
                .delete("/idnamespaces/" + targetNs)
                .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .when()
                .delete("/matchingworkflows/" + workflowName)
                .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .when()
                .delete("/schemas/" + schemaName)
                .then()
                .statusCode(200);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260205/" + region
                + "/entityresolution/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
