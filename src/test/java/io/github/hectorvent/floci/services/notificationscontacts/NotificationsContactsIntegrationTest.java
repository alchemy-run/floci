package io.github.hectorvent.floci.services.notificationscontacts;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies notifications-contacts restJson1 email-contact lifecycle and tags. */
@QuarkusTest
class NotificationsContactsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String TIMESTAMP_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*Z";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void emailContactCreateGetListTagReplaceDeleteLifecycle() {
        String authorization = auth("000000000401", EAST);
        String arn = create(authorization, """
                {
                  "name":"alchemy-test-email-contact",
                  "emailAddress":"sam+alchemy-test-contact-a@alchemy.run",
                  "tags":{"purpose":"alchemy-test"}
                }
                """);
        assertTrue(arn.contains(":emailcontact/"));
        assertTrue(arn.startsWith("arn:aws:notifications-contacts::000000000401:emailcontact/"));

        Response created = get(authorization, arn);
        created.then()
                .statusCode(200)
                .body("emailContact.arn", equalTo(arn))
                .body("emailContact.name", equalTo("alchemy-test-email-contact"))
                .body("emailContact.address", equalTo("sam+alchemy-test-contact-a@alchemy.run"))
                .body("emailContact.status", equalTo("inactive"));
        assertTrue(((String) created.path("emailContact.creationTime")).matches(TIMESTAMP_PATTERN));
        assertTrue(((String) created.path("emailContact.updateTime")).matches(TIMESTAMP_PATTERN));

        List<Map<String, Object>> listed = list(authorization).path("emailContacts");
        assertEquals(1, listed.size());
        assertEquals(arn, listed.getFirst().get("arn"));
        assertEquals("inactive", listed.getFirst().get("status"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.purpose", equalTo("alchemy-test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"updated\":\"true\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.purpose", equalTo("alchemy-test"))
                .body("tags.updated", equalTo("true"));

        given()
                .header("Authorization", authorization)
                .queryParam("tagKeys", "updated")
                .when()
                .delete("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.purpose", equalTo("alchemy-test"))
                .body("tags.updated", equalTo(null));

        String replacedArn = create(authorization, """
                {
                  "name":"alchemy-test-email-contact",
                  "emailAddress":"sam+alchemy-test-contact-b@alchemy.run",
                  "tags":{"purpose":"alchemy-test"}
                }
                """);
        assertNotEquals(arn, replacedArn);
        get(authorization, replacedArn)
                .then()
                .statusCode(200)
                .body("emailContact.address", equalTo("sam+alchemy-test-contact-b@alchemy.run"))
                .body("emailContact.status", equalTo("inactive"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/emailcontacts/" + encode(arn))
                .then()
                .statusCode(200);

        get(authorization, arn)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("EmailContact"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/emailcontacts/" + encode(replacedArn))
                .then()
                .statusCode(200);

        get(authorization, replacedArn)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void duplicateEmailAddressReturnsConflict() {
        String authorization = auth("000000000402", EAST);
        String arn = create(authorization, """
                {"name":"first","emailAddress":"dup@alchemy.run"}
                """);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"name\":\"second\",\"emailAddress\":\"dup@alchemy.run\"}")
                .when()
                .post("/2022-09-19/emailcontacts")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"))
                .body("resourceType", equalTo("EmailContact"));

        get(authorization, arn)
                .then()
                .statusCode(200)
                .body("emailContact.name", equalTo("first"));
    }

    @Test
    void emailContactsAreIsolatedByAccount() {
        String firstAuth = auth("000000000403", EAST);
        String secondAuth = auth("000000000404", EAST);

        String firstArn = create(firstAuth, "{\"name\":\"shared\",\"emailAddress\":\"shared@alchemy.run\"}");
        String secondArn = create(secondAuth, "{\"name\":\"shared\",\"emailAddress\":\"shared@alchemy.run\"}");

        assertNotEquals(firstArn, secondArn);
        get(firstAuth, firstArn).then().body("emailContact.arn", equalTo(firstArn));
        get(secondAuth, secondArn).then().body("emailContact.arn", equalTo(secondArn));
        assertEquals(1, ((List<?>) list(firstAuth).path("emailContacts")).size());
        assertEquals(1, ((List<?>) list(secondAuth).path("emailContacts")).size());
        get(firstAuth, secondArn).then().statusCode(404);
    }

    @Test
    void getSendAndRejectBogusActivationCodeForBindings() {
        String authorization = auth("000000000407", EAST);
        String arn = create(authorization,
                "{\"name\":\"bindings-contact\",\"emailAddress\":\"bindings@alchemy.run\"}");

        Response observed = get(authorization, arn);
        observed.then()
                .statusCode(200)
                .body("emailContact.arn", equalTo(arn))
                .body("emailContact.status", equalTo("inactive"));
        assertTrue(((String) observed.path("emailContact.arn")).contains(":emailcontact/"));

        given()
                .header("Authorization", authorization)
                .when()
                .post("/2022-10-31/emailcontacts/" + encode(arn) + "/activate/send")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .put("/emailcontacts/" + encode(arn) + "/activate/000000")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ValidationException"))
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void sendActivationCodeThenActivateTransitionsToActive() {
        String authorization = auth("000000000405", EAST);
        String arn = create(authorization, "{\"name\":\"activate-me\",\"emailAddress\":\"activate@alchemy.run\"}");

        given()
                .header("Authorization", authorization)
                .when()
                .post("/2022-10-31/emailcontacts/" + encode(arn) + "/activate/send")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .put("/emailcontacts/" + encode(arn) + "/activate/not-the-code")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ValidationException"))
                .body("__type", equalTo("ValidationException"));

        get(authorization, arn).then().body("emailContact.status", equalTo("inactive"));

        String code = arn.substring(arn.lastIndexOf('/') + 1).substring(0, 8);
        given()
                .header("Authorization", authorization)
                .when()
                .put("/emailcontacts/" + encode(arn) + "/activate/" + encode(code))
                .then()
                .statusCode(200);

        get(authorization, arn).then().body("emailContact.status", equalTo("active"));

        given()
                .header("Authorization", authorization)
                .when()
                .post("/2022-10-31/emailcontacts/" + encode(arn) + "/activate/send")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ConflictException"))
                .body("__type", equalTo("ConflictException"))
                .body("resourceType", equalTo("EmailContact"));
    }

    @Test
    void missingContactIsResourceNotFound() {
        String authorization = auth("000000000406", EAST);
        String arn = "arn:aws:notifications-contacts::000000000406:emailcontact/aaaaaaaaaaaaaaaaaaaaaaaaaaa";
        get(authorization, arn)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("EmailContact"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/notifications-contacts/aws4_request";
    }

    private static String create(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/2022-09-19/emailcontacts")
                .then()
                .statusCode(201)
                .body("arn", startsWith("arn:aws:notifications-contacts::"))
                .body("arn", notNullValue())
                .extract().path("arn");
    }

    private static Response get(String authorization, String arn) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get("/emailcontacts/" + encode(arn));
    }

    private static Response list(String authorization) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get("/emailcontacts")
                .then()
                .statusCode(200)
                .extract().response();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
