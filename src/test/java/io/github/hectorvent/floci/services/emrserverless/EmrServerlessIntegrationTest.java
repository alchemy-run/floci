package io.github.hectorvent.floci.services.emrserverless;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EmrServerlessIntegrationTest {

    private static final String JSON_CONTENT_TYPE = "application/json";
    private static String applicationId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private RequestSpecification givenReq() {
        return given()
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=TEST/20260819/us-east-1/emr-serverless/aws4_request, SignedHeaders=host;x-amz-date, Signature=test")
            .contentType(JSON_CONTENT_TYPE);
    }

    @Test
    @Order(1)
    void createApplicationValidation() {
        // Missing releaseLabel
        givenReq()
            .body("""
                {
                    "name": "my-test-app",
                    "type": "SPARK",
                    "clientToken": "test-token"
                }
                """)
        .when()
            .post("/applications")
        .then()
            .statusCode(400);

        // Missing type
        givenReq()
            .body("""
                {
                    "name": "my-test-app",
                    "releaseLabel": "emr-6.6.0",
                    "clientToken": "test-token"
                }
                """)
        .when()
            .post("/applications")
        .then()
            .statusCode(400);

        // Missing clientToken
        givenReq()
            .body("""
                {
                    "name": "my-test-app",
                    "releaseLabel": "emr-6.6.0",
                    "type": "SPARK"
                }
                """)
        .when()
            .post("/applications")
        .then()
            .statusCode(400);
    }

    @Test
    void applicationTypesUseAwsResponseCasing() {
        for (String type : new String[] {"SPARK", "HIVE"}) {
            String expected = type.equals("SPARK") ? "Spark" : "Hive";
            String id = givenReq().body("""
                    {"name":"type-contract-%s","releaseLabel":"emr-7.5.0",
                     "type":"%s","clientToken":"type-contract-%s"}
                    """.formatted(type, type, type))
                    .post("/applications").then().statusCode(200).extract().path("applicationId");
            try {
                givenReq().get("/applications/" + id).then().statusCode(200)
                        .body("application.type", equalTo(expected));
                givenReq().get("/applications").then().statusCode(200)
                        .body("applications.find { it.id == '" + id + "' }.type", equalTo(expected));
            } finally {
                givenReq().delete("/applications/" + id).then().statusCode(200);
            }
        }
        givenReq().body("""
                {"name":"invalid-type","releaseLabel":"emr-7.5.0",
                 "type":"INVALID","clientToken":"invalid-type-contract"}
                """)
                .post("/applications").then().statusCode(400);
    }

    @Test
    @Order(2)
    void createApplication() {
        applicationId = givenReq()
            .body("""
                {
                    "name": "my-test-app",
                    "releaseLabel": "emr-6.6.0",
                    "type": "SPARK",
                    "clientToken": "test-token"
                }
                """)
        .when()
            .post("/applications")
        .then()
            .statusCode(200)
            .body("applicationId", notNullValue())
            .body("arn", startsWith("arn:aws:emr-serverless:us-east-1:000000000000:/applications/"))
            .body("name", equalTo("my-test-app"))
            .extract().path("applicationId");
            
        // Test Idempotency
        String retryApplicationId = givenReq()
            .body("""
                {
                    "name": "my-test-app",
                    "releaseLabel": "emr-6.6.0",
                    "type": "SPARK",
                    "clientToken": "test-token"
                }
                """)
        .when()
            .post("/applications")
        .then()
            .statusCode(200)
            .extract().path("applicationId");
            
        assertEquals(applicationId, retryApplicationId, "Idempotent create should return the same application ID");
    }

    @Test
    @Order(3)
    void getApplication() {
        givenReq()
        .when()
            .get("/applications/" + applicationId)
        .then()
            .statusCode(200)
            .body("application.applicationId", equalTo(applicationId))
            .body("application.name", equalTo("my-test-app"))
            .body("application.state", equalTo("CREATED"));
    }

    @Test
    @Order(4)
    void listApplications() {
        givenReq()
        .when()
            .get("/applications")
        .then()
            .statusCode(200)
            .body("applications.size()", greaterThanOrEqualTo(1))
            .body("applications.find { it.id == '" + applicationId + "' }.name", equalTo("my-test-app"));
            
        // Create a second app to test pagination
        givenReq()
            .body("""
                {
                    "name": "my-second-app",
                    "releaseLabel": "emr-6.6.0",
                    "type": "SPARK",
                    "clientToken": "test-token-2"
                }
                """)
        .when()
            .post("/applications")
        .then()
            .statusCode(200);

        // Test pagination and state filtering
        givenReq()
            .queryParam("states", "CREATED")
            .queryParam("maxResults", 1)
        .when()
            .get("/applications")
        .then()
            .statusCode(200)
            .body("applications.size()", equalTo(1))
            .body("applications[0].state", equalTo("CREATED"))
            .body("nextToken", notNullValue());
    }

    @Test
    @Order(5)
    void startApplication() {
        givenReq()
        .when()
            .post("/applications/" + applicationId + "/start")
        .then()
            .statusCode(200);

        givenReq()
        .when()
            .get("/applications/" + applicationId)
        .then()
            .statusCode(200)
            .body("application.state", equalTo("STARTED"));
    }

    @Test
    @Order(6)
    void stopApplication() {
        givenReq()
        .when()
            .post("/applications/" + applicationId + "/stop")
        .then()
            .statusCode(200);

        givenReq()
        .when()
            .get("/applications/" + applicationId)
        .then()
            .statusCode(200)
            .body("application.state", equalTo("STOPPED"));
    }

    @Test
    @Order(7)
    void updateApplication() {
        givenReq()
            .body("""
                {
                    "releaseLabel": "emr-6.7.0",
                    "initialCapacity": {
                        "DRIVER": {
                            "workerCount": 2,
                            "workerConfiguration": {
                                "cpu": "4 vCPU",
                                "memory": "16 GB"
                            }
                        }
                    },
                    "monitoringConfiguration": {
                        "s3MonitoringConfiguration": {
                            "logUri": "s3://my-bucket/logs"
                        }
                    },
                    "interactiveConfiguration": {
                        "studioEnabled": true
                    }
                }
                """)
        .when()
            .patch("/applications/" + applicationId)
        .then()
            .statusCode(200)
            .body("application.applicationId", equalTo(applicationId))
            .body("application.arn", containsString("/applications/" + applicationId))
            .body("application.releaseLabel", equalTo("emr-6.7.0"))
            .body("application.initialCapacity.DRIVER.workerCount", equalTo(2))
            .body("application.interactiveConfiguration.studioEnabled", equalTo(true))
            .body("applicationId", nullValue());
            
        // Verify releaseLabel and new configurations updated
        givenReq()
        .when()
            .get("/applications/" + applicationId)
        .then()
            .statusCode(200)
            .body("application.releaseLabel", equalTo("emr-6.7.0"))
            .body("application.monitoringConfiguration", notNullValue())
            .body("application.interactiveConfiguration", notNullValue());
    }

    @Test
    void executionListsUseAwsEnvelopesAndValidateTheApplication() {
        String id = givenReq().body("""
                {"name":"execution-lists","releaseLabel":"emr-7.5.0",
                 "type":"SPARK","clientToken":"execution-lists"}
                """).post("/applications").then().statusCode(200).extract().path("applicationId");
        try {
            for (String collection : new String[] {"jobruns", "sessions"}) {
                String field = collection.equals("jobruns") ? "jobRuns" : "sessions";
                String path = "/applications/" + id + "/" + collection;
                givenReq().get(path).then().statusCode(200)
                        .body(field, empty()).body("nextToken", nullValue());
                for (String maxResults : new String[] {"0", "51", "invalid"}) {
                    givenReq().queryParam("maxResults", maxResults).get(path).then().statusCode(400)
                            .body("__type", equalTo("ValidationException"));
                }
                givenReq().queryParam("nextToken", "!").get(path).then().statusCode(400)
                        .body("__type", equalTo("ValidationException"));
                givenReq().get("/applications/0000000000000000/" + collection).then().statusCode(404)
                        .body("__type", equalTo("ResourceNotFoundException"));
            }
        } finally {
            givenReq().delete("/applications/" + id).then().statusCode(200);
        }
    }

    @Test
    void sessionPrerequisitesAndMissingExecutionRoleDoNotFabricateResources() {
        String executionRequest = """
                {"clientToken":"execution-probe",
                 "executionRoleArn":"arn:aws:iam::000000000000:role/execution-probe",
                 "jobDriver":{"sparkSubmit":{"entryPoint":"local:///example.py"}}}
                """;
        for (boolean sessionsEnabled : new boolean[] {false, true}) {
            String id = givenReq().body("""
                    {"name":"execution-probe-%s","releaseLabel":"emr-7.5.0",
                     "type":"SPARK","clientToken":"execution-probe-%s",
                     "tags":{"purpose":"envelope-regression"},
                     "interactiveConfiguration":{"sessionEnabled":%s}}
                    """.formatted(sessionsEnabled, sessionsEnabled, sessionsEnabled))
                    .post("/applications").then().statusCode(200).extract().path("applicationId");
            String path = "/applications/" + id;
            try {
                givenReq().get(path).then().statusCode(200)
                        .body("application.interactiveConfiguration.sessionEnabled", equalTo(sessionsEnabled));
                givenReq().body("""
                        {"clientToken":"update-probe","autoStopConfiguration":{"enabled":true,"idleTimeoutMinutes":5}}
                        """).patch(path).then().statusCode(200)
                        .body("application.applicationId", equalTo(id))
                        .body("application.tags.purpose", equalTo("envelope-regression"))
                        .body("application.autoStopConfiguration.idleTimeoutMinutes", equalTo(5));
                givenReq().body(executionRequest).post(path + "/sessions").then()
                        .statusCode(sessionsEnabled ? 501 : 400)
                        .body("__type", equalTo(sessionsEnabled
                                ? "UnsupportedOperationException" : "ValidationException"));
                givenReq().body("{}").post(path + "/sessions").then().statusCode(400)
                        .body("__type", equalTo("ValidationException"));
                givenReq().body(executionRequest).post(path + "/jobruns").then().statusCode(400)
                        .body("__type", equalTo("ValidationException"))
                        .body("message", containsString("execution role does not exist"));
                givenReq().queryParam("resourceId", "00abcdefabcdef01")
                        .queryParam("resourceType", "SPARK_DRIVER").get(path + "/dashboard").then()
                        .statusCode(501).body("__type", equalTo("UnsupportedOperationException"));
                givenReq().get(path + "/jobruns").then().statusCode(200).body("jobRuns", empty());
                givenReq().get(path + "/sessions").then().statusCode(200).body("sessions", empty());
                givenReq().get(path).then().statusCode(200).body("application.state", equalTo("CREATED"));
            } finally {
                givenReq().delete(path).then().statusCode(200);
            }
        }
        givenReq().body(executionRequest).post("/applications/0000000000000000/sessions").then()
                .statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        givenReq().body(executionRequest).post("/applications/0000000000000000/jobruns").then()
                .statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        givenReq().queryParam("resourceId", "00abcdefabcdef01").queryParam("resourceType", "SPARK_DRIVER")
                .get("/applications/0000000000000000/dashboard").then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void applicationsAndClientTokensAreIsolatedByAccountAndRegion() {
        String request = """
                {"name":"scope-regression","releaseLabel":"emr-7.5.0",
                 "type":"SPARK","clientToken":"scope-regression"}
                """;
        String[][] scopes = {{"111111111111", "us-east-1"}, {"111111111111", "us-west-2"},
                {"222222222222", "us-east-1"}};
        String[] ids = new String[scopes.length];
        try {
            for (int i = 0; i < scopes.length; i++) {
                ids[i] = givenScope(scopes[i][0], scopes[i][1]).body(request).post("/applications")
                        .then().statusCode(200).extract().path("applicationId");
                givenScope(scopes[i][0], scopes[i][1]).body(request).post("/applications")
                        .then().statusCode(200).body("applicationId", equalTo(ids[i]));
            }
            for (int i = 0; i < scopes.length; i++) {
                String account = scopes[i][0];
                String region = scopes[i][1];
                givenScope(account, region).get("/applications").then().statusCode(200)
                        .body("applications.id", hasItem(ids[i]));
                for (int j = 0; j < scopes.length; j++) {
                    if (i == j) {
                        continue;
                    }
                    assertNotEquals(ids[i], ids[j]);
                    String path = "/applications/" + ids[j];
                    givenScope(account, region).get("/applications").then().statusCode(200)
                            .body("applications.id", not(hasItem(ids[j])));
                    for (String suffix : new String[] {"", "/jobruns", "/sessions"}) {
                        givenScope(account, region).get(path + suffix).then().statusCode(404)
                                .body("__type", equalTo("ResourceNotFoundException"));
                    }
                    givenScope(account, region).body("{\"releaseLabel\":\"emr-7.6.0\"}")
                            .patch(path).then().statusCode(404);
                    givenScope(account, region).post(path + "/start").then().statusCode(404);
                    givenScope(account, region).post(path + "/stop").then().statusCode(404);
                    givenScope(account, region).delete(path).then().statusCode(404);
                }
            }
        } finally {
            for (int i = 0; i < scopes.length; i++) {
                if (ids[i] != null) {
                    givenScope(scopes[i][0], scopes[i][1]).delete("/applications/" + ids[i])
                            .then().statusCode(200);
                }
            }
        }
    }

    private RequestSpecification givenScope(String account, String region) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20260922/" + region + "/emr-serverless/aws4_request, SignedHeaders=host;x-amz-date, Signature=test")
                .contentType(JSON_CONTENT_TYPE);
    }

    @Test
    @Order(8)
    void deleteApplication() {
        givenReq()
        .when()
            .delete("/applications/" + applicationId)
        .then()
            .statusCode(200);

        // Verify it's gone
        givenReq()
        .when()
            .get("/applications/" + applicationId)
        .then()
            .statusCode(404);
    }
}
