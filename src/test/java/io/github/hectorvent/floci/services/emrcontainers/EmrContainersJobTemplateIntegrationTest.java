package io.github.hectorvent.floci.services.emrcontainers;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies EMR on EKS restJson1 job-template lifecycle and typed errors. */
@QuarkusTest
class EmrContainersJobTemplateIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final String WELL_FORMED_MISSING_ID = "abcdefabcdefabcdefabcdef01";
    private static final String TEMPLATE_BODY = """
            {
              "name":"lifecycle-template",
              "clientToken":"token-lifecycle-1",
              "jobTemplateData":{
                "executionRoleArn":"arn:aws:iam::000000000000:role/JobRole",
                "releaseLabel":"emr-7.5.0-latest",
                "jobDriver":{
                  "sparkSubmitJobDriver":{
                    "entryPoint":"s3://alchemy-test-emrc/scripts/etl.py",
                    "sparkSubmitParameters":"--conf spark.executor.instances=1"
                  }
                },
                "jobTags":{"Origin":"alchemy-test"}
              },
              "tags":{"Purpose":"alchemy-emrc-test"}
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeOnANonexistentJobTemplateFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000501", EAST))
                .when()
                .get("/jobtemplates/" + WELL_FORMED_MISSING_ID)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteOnANonexistentJobTemplateFailsWithValidationException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000501", EAST))
                .when()
                .delete("/jobtemplates/" + WELL_FORMED_MISSING_ID)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void listReturnsAWellFormedArrayOfJobTemplates() {
        String authorization = auth("000000000502", EAST);
        Response listed = list(authorization);
        List<Map<String, Object>> templates = listed.path("templates");
        assertTrue(templates.isEmpty() || templates.stream().allMatch(template ->
                template.get("id") instanceof String
                        && template.get("name") instanceof String
                        && String.valueOf(template.get("arn")).contains(":/jobtemplates/")));
    }

    @Test
    void jobTemplateCreateDescribeListDeleteLifecycle() {
        String authorization = auth("000000000503", EAST);
        String id = create(authorization, TEMPLATE_BODY);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/jobtemplates/" + id)
                .then()
                .statusCode(200)
                .body("jobTemplate.id", equalTo(id))
                .body("jobTemplate.name", equalTo("lifecycle-template"))
                .body("jobTemplate.arn", notNullValue())
                .body("jobTemplate.jobTemplateData.releaseLabel", equalTo("emr-7.5.0-latest"))
                .body("jobTemplate.jobTemplateData.jobDriver.sparkSubmitJobDriver.entryPoint",
                        equalTo("s3://alchemy-test-emrc/scripts/etl.py"))
                .body("jobTemplate.tags.Purpose", equalTo("alchemy-emrc-test"));

        Response described = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/jobtemplates/" + id)
                .then()
                .statusCode(200)
                .extract().response();
        assertTrue(described.path("jobTemplate.arn").toString().contains(":/jobtemplates/"));

        List<Map<String, Object>> templates = list(authorization).path("templates");
        assertEquals(1, templates.size());
        assertEquals(id, templates.getFirst().get("id"));
        assertEquals("lifecycle-template", templates.getFirst().get("name"));
        assertTrue(String.valueOf(templates.getFirst().get("arn")).contains(":/jobtemplates/"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/jobtemplates/" + id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/jobtemplates/" + id)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/jobtemplates/" + id)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createWithTheSameClientTokenIsIdempotent() {
        String authorization = auth("000000000504", EAST);
        String first = create(authorization, TEMPLATE_BODY);
        String second = create(authorization, TEMPLATE_BODY);
        assertEquals(first, second);
    }

    @Test
    void jobTemplatesAreIsolatedByAccountAndRegion() {
        String firstAuth = auth("000000000505", EAST);
        String secondAuth = auth("000000000506", EAST);
        String westAuth = auth("000000000505", WEST);

        String first = create(firstAuth, TEMPLATE_BODY);
        String second = create(secondAuth, TEMPLATE_BODY);
        String west = create(westAuth, TEMPLATE_BODY);
        assertNotEquals(first, second);
        assertNotEquals(first, west);

        given()
                .header("Authorization", firstAuth)
                .when()
                .get("/jobtemplates/" + first)
                .then()
                .statusCode(200)
                .body("jobTemplate.id", equalTo(first));
        given()
                .header("Authorization", firstAuth)
                .when()
                .get("/jobtemplates/" + second)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        given()
                .header("Authorization", westAuth)
                .when()
                .get("/jobtemplates/" + first)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/emr-containers/aws4_request";
    }

    private static String create(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/jobtemplates")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("name", equalTo("lifecycle-template"))
                .body("arn", notNullValue())
                .extract().path("id");
    }

    private static Response list(String authorization) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get("/jobtemplates")
                .then()
                .statusCode(200)
                .extract().response();
    }
}
