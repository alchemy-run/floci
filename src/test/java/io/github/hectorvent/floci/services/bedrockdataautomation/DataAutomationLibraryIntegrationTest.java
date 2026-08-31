package io.github.hectorvent.floci.services.bedrockdataautomation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies Bedrock Data Automation restJson1 library lifecycle, tags, and not-found. */
@QuarkusTest
class DataAutomationLibraryIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getDataAutomationLibraryOnANonexistentArnFailsWithResourceNotFoundException() {
        String arn = "arn:aws:bedrock:" + EAST
                + ":000000000000:data-automation-library/nonexistentalchemyprobe0";
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .when()
                .post("/data-automation-libraries/" + encode(arn) + "/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createUpdateTagAndDeleteLibraryLifecycle() {
        String authorization = auth(EAST);
        String name = "lifecycle-lib-" + UUID.randomUUID().toString().substring(0, 8);

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "libraryName":"%s",
                          "libraryDescription":"alchemy test library",
                          "tags":[
                            {"key":"Environment","value":"test"},
                            {"key":"alchemy::id","value":"TestLibrary"}
                          ]
                        }
                        """.formatted(name))
                .when()
                .put("/data-automation-libraries/")
                .then()
                .statusCode(200)
                .body("libraryArn", notNullValue())
                .body("status", equalTo("ACTIVE"))
                .extract().path("libraryArn");

        given()
                .header("Authorization", authorization)
                .when()
                .post("/data-automation-libraries/" + encode(arn) + "/")
                .then()
                .statusCode(200)
                .body("library.libraryArn", equalTo(arn))
                .body("library.libraryName", equalTo(name))
                .body("library.libraryDescription", equalTo("alchemy test library"))
                .body("library.status", equalTo("ACTIVE"))
                .body("library.creationTime", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/data-automation-libraries/")
                .then()
                .statusCode(200)
                .body("libraries.libraryArn", hasItem(arn))
                .body("libraries.libraryName", hasItem(name));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceARN\":\"" + arn + "\"}")
                .when()
                .post("/listTagsForResource")
                .then()
                .statusCode(200)
                .body("tags.key", hasItem("Environment"))
                .body("tags.value", hasItem("test"))
                .body("tags.key", hasItem("alchemy::id"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "libraryDescription":"alchemy test library (updated)"
                        }
                        """)
                .when()
                .put("/data-automation-libraries/" + encode(arn) + "/")
                .then()
                .statusCode(200)
                .body("libraryArn", equalTo(arn))
                .body("status", equalTo("ACTIVE"));

        given()
                .header("Authorization", authorization)
                .when()
                .post("/data-automation-libraries/" + encode(arn) + "/")
                .then()
                .statusCode(200)
                .body("library.libraryDescription", equalTo("alchemy test library (updated)"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/data-automation-libraries/" + encode(arn) + "/")
                .then()
                .statusCode(200)
                .body("status", equalTo("DELETING"));

        given()
                .header("Authorization", authorization)
                .when()
                .post("/data-automation-libraries/" + encode(arn) + "/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void customLibraryNameConflictAndReplacement() {
        String authorization = auth(EAST);
        String firstName = "alchemy-test-library-a-" + UUID.randomUUID().toString().substring(0, 8);
        String secondName = "alchemy-test-library-b-" + UUID.randomUUID().toString().substring(0, 8);

        String firstArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"libraryName\":\"" + firstName + "\"}")
                .when()
                .put("/data-automation-libraries/")
                .then()
                .statusCode(200)
                .body("status", equalTo("ACTIVE"))
                .extract().path("libraryArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"libraryName\":\"" + firstName + "\"}")
                .when()
                .put("/data-automation-libraries/")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        String secondArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"libraryName\":\"" + secondName + "\"}")
                .when()
                .put("/data-automation-libraries/")
                .then()
                .statusCode(200)
                .extract().path("libraryArn");

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/data-automation-libraries/" + encode(firstArn) + "/")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/data-automation-libraries/")
                .then()
                .statusCode(200)
                .body("libraries.libraryArn", hasItem(secondArn))
                .body("libraries.libraryArn", not(hasItem(firstArn)));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/bedrock/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
