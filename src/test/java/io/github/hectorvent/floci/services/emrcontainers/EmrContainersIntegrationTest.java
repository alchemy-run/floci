package io.github.hectorvent.floci.services.emrcontainers;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Binding-surface operations Alchemy EMRContainers Bindings exercise: list
 * job templates (including the empty account), create/describe/delete a
 * template, and list virtual clusters (authorized round trip, often empty).
 */
@QuarkusTest
class EmrContainersIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String MISSING = "abcdefabcdefabcdefabcdef01";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listJobTemplatesReturnsArray() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/jobtemplates")
                .then()
                .statusCode(200)
                .body("templates", notNullValue());
    }

    @Test
    void describeMissingJobTemplateIsResourceNotFound() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/jobtemplates/" + MISSING)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteMissingJobTemplateIsValidationException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .delete("/jobtemplates/" + MISSING)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createDescribeListDeleteJobTemplateLifecycle() {
        String authorization = auth(EAST);
        String name = "emrc-jt-" + UUID.randomUUID().toString().substring(0, 8);
        String token = "token-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        String id = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name": "%s",
                          "clientToken": "%s",
                          "jobTemplateData": {
                            "executionRoleArn": "arn:aws:iam::000000000000:role/JobRole",
                            "releaseLabel": "emr-7.5.0-latest",
                            "jobDriver": {
                              "sparkSubmitJobDriver": {
                                "entryPoint": "s3://alchemy-test-emrc/scripts/etl.py"
                              }
                            }
                          },
                          "tags": {"Purpose": "alchemy-emrc-test"}
                        }
                        """.formatted(name, token))
                .when()
                .post("/jobtemplates")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("name", equalTo(name))
                .body("arn", startsWith("arn:aws:emr-containers:"))
                .body("arn", org.hamcrest.Matchers.containsString(":/jobtemplates/"))
                .extract()
                .path("id");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name": "%s",
                          "clientToken": "%s",
                          "jobTemplateData": {
                            "executionRoleArn": "arn:aws:iam::000000000000:role/JobRole",
                            "releaseLabel": "emr-7.5.0-latest",
                            "jobDriver": {
                              "sparkSubmitJobDriver": {
                                "entryPoint": "s3://alchemy-test-emrc/scripts/etl.py"
                              }
                            }
                          }
                        }
                        """.formatted(name, token))
                .when()
                .post("/jobtemplates")
                .then()
                .statusCode(200)
                .body("id", equalTo(id));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/jobtemplates/" + id)
                .then()
                .statusCode(200)
                .body("jobTemplate.id", equalTo(id))
                .body("jobTemplate.name", equalTo(name))
                .body("jobTemplate.jobTemplateData.releaseLabel", equalTo("emr-7.5.0-latest"))
                .body("jobTemplate.jobTemplateData.jobDriver.sparkSubmitJobDriver.entryPoint",
                        equalTo("s3://alchemy-test-emrc/scripts/etl.py"))
                .body("jobTemplate.tags.Purpose", equalTo("alchemy-emrc-test"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/jobtemplates")
                .then()
                .statusCode(200)
                .body("templates.id", hasItem(id));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/jobtemplates/" + id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/jobtemplates/" + id)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/jobtemplates")
                .then()
                .statusCode(200)
                .body("templates.id", not(hasItem(id)));
    }

    @Test
    void listVirtualClustersReturnsArray() {
        given()
                .header("Authorization", auth(EAST))
                .queryParam("states", "RUNNING")
                .when()
                .get("/virtualclusters")
                .then()
                .statusCode(200)
                .body("virtualClusters", notNullValue());
    }

    @Test
    void createListRunningVirtualCluster() {
        String authorization = auth(EAST);
        String name = "emrc-vc-" + UUID.randomUUID().toString().substring(0, 8);
        String token = "token-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        String id = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name": "%s",
                          "clientToken": "%s",
                          "containerProvider": {
                            "id": "eks-cluster",
                            "type": "EKS",
                            "info": {"eksInfo": {"namespace": "emr"}}
                          },
                          "tags": {"Purpose": "alchemy-emrc-test"}
                        }
                        """.formatted(name, token))
                .when()
                .post("/virtualclusters")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("arn", org.hamcrest.Matchers.containsString(":/virtualclusters/"))
                .extract()
                .path("id");

        given()
                .header("Authorization", authorization)
                .queryParam("states", "RUNNING")
                .when()
                .get("/virtualclusters")
                .then()
                .statusCode(200)
                .body("virtualClusters.id", hasItem(id));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/emr-containers/aws4_request";
    }
}
