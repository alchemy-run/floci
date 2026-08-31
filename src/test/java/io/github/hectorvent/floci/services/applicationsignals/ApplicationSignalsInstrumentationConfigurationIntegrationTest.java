package io.github.hectorvent.floci.services.applicationsignals;

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
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the Application Signals restJson1 instrumentation-configuration lifecycle. */
@QuarkusTest
class ApplicationSignalsInstrumentationConfigurationIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String SERVICE = "alchemy-test-appsignals-ic";
    private static final String ENVIRONMENT = "alchemy-test-env";
    private static final String CREATE_BODY = """
            {
              "InstrumentationType":"PROBE",
              "Service":"%s",
              "Environment":"%s",
              "SignalType":"SNAPSHOT",
              "Location":{
                "CodeLocation":{
                  "Language":"Python",
                  "CodeUnit":"app.main",
                  "MethodName":"handler",
                  "FilePath":"app/main.py",
                  "LineNumber":%d
                }
              },
              "CaptureConfiguration":{
                "CodeCapture":{
                  "CaptureLocals":["x"],
                  "CaptureLimits":{"MaxHits":5}
                }
              },
              "Description":"alchemy instrumentation configuration test",
              "Tags":[{"Key":"fixture","Value":"application-signals-ic"}]
            }
            """.formatted(SERVICE, ENVIRONMENT, 10);
    private static final String IDENTITY = """
            "InstrumentationType":"PROBE",
            "Service":"%s",
            "Environment":"%s",
            "SignalType":"SNAPSHOT"
            """.formatted(SERVICE, ENVIRONMENT);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getMissingConfigurationReturnsResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000801", EAST))
                .body("""
                        {
                          %s,
                          "LocationIdentifier":{"LocationHash":"0123456789abcdef"}
                        }
                        """.formatted(IDENTITY))
                .when()
                .post("/get-instrumentation-configuration")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("ResourceType", equalTo("instrumentationConfig"));
    }

    @Test
    void createGetTagReplaceDeleteLifecycle() {
        String authorization = auth("000000000802", EAST);

        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(CREATE_BODY)
                .when()
                .post("/create-instrumentation-configuration")
                .then()
                .statusCode(200)
                .body("ARN", notNullValue())
                .body("LocationHash", notNullValue())
                .body("Service", equalTo(SERVICE))
                .body("Description", equalTo("alchemy instrumentation configuration test"))
                .extract()
                .response();

        String arn = created.path("ARN");
        String hash = created.path("LocationHash");
        assertTrue(arn.contains(":instrumentationConfig/"));
        assertEquals(16, hash.length());
        assertTrue(arn.endsWith("/" + hash));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          %s,
                          "LocationIdentifier":{"LocationHash":"%s"}
                        }
                        """.formatted(IDENTITY, hash))
                .when()
                .post("/get-instrumentation-configuration")
                .then()
                .statusCode(200)
                .body("Configuration.ARN", equalTo(arn))
                .body("Configuration.LocationHash", equalTo(hash))
                .body("Configuration.Location.CodeLocation.LineNumber", equalTo(10))
                .body("Configuration.CreatedAt", notNullValue());

        List<Map<String, String>> tags = given()
                .header("Authorization", authorization)
                .queryParam("ResourceArn", arn)
                .when()
                .get("/tags")
                .then()
                .statusCode(200)
                .extract()
                .path("Tags");
        assertEquals("application-signals-ic", tagValue(tags, "fixture"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ResourceArn":"%s",
                          "Tags":[{"Key":"updated","Value":"true"}]
                        }
                        """.formatted(arn))
                .when()
                .post("/tag-resource")
                .then()
                .statusCode(200);

        List<Map<String, String>> updated = given()
                .header("Authorization", authorization)
                .queryParam("ResourceArn", arn)
                .when()
                .get("/tags")
                .then()
                .statusCode(200)
                .extract()
                .path("Tags");
        assertEquals("true", tagValue(updated, "updated"));
        assertEquals("application-signals-ic", tagValue(updated, "fixture"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(CREATE_BODY)
                .when()
                .post("/create-instrumentation-configuration")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        Response replaced = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(CREATE_BODY.replace("\"LineNumber\":10", "\"LineNumber\":20"))
                .when()
                .post("/create-instrumentation-configuration")
                .then()
                .statusCode(200)
                .body("ARN", startsWith("arn:aws:application-signals:"))
                .extract()
                .response();
        String replacedHash = replaced.path("LocationHash");
        assertNotEquals(hash, replacedHash);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          %s,
                          "LocationIdentifier":{"LocationHash":"%s"}
                        }
                        """.formatted(IDENTITY, hash))
                .when()
                .post("/delete-instrumentation-configuration")
                .then()
                .statusCode(200)
                .body("DeletionStatus", equalTo("DELETED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          %s,
                          "LocationIdentifier":{"LocationHash":"%s"}
                        }
                        """.formatted(IDENTITY, hash))
                .when()
                .post("/get-instrumentation-configuration")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          %s,
                          "LocationIdentifier":{"LocationHash":"%s"}
                        }
                        """.formatted(IDENTITY, replacedHash))
                .when()
                .post("/delete-instrumentation-configuration")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          %s,
                          "LocationIdentifier":{"LocationHash":"%s"}
                        }
                        """.formatted(IDENTITY, replacedHash))
                .when()
                .post("/delete-instrumentation-configuration")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getByCodeLocationMatchesCreatedHash() {
        String authorization = auth("000000000803", EAST);
        String hash = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(CREATE_BODY)
                .when()
                .post("/create-instrumentation-configuration")
                .then()
                .statusCode(200)
                .extract()
                .path("LocationHash");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          %s,
                          "LocationIdentifier":{
                            "CodeLocation":{
                              "Language":"Python",
                              "CodeUnit":"app.main",
                              "MethodName":"handler",
                              "FilePath":"app/main.py",
                              "LineNumber":10
                            }
                          }
                        }
                        """.formatted(IDENTITY))
                .when()
                .post("/get-instrumentation-configuration")
                .then()
                .statusCode(200)
                .body("Configuration.LocationHash", equalTo(hash));
    }

    private static String tagValue(List<Map<String, String>> tags, String key) {
        return tags.stream()
                .filter(tag -> key.equals(tag.get("Key")))
                .map(tag -> tag.get("Value"))
                .findFirst()
                .orElse(null);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/application-signals/aws4_request";
    }
}
