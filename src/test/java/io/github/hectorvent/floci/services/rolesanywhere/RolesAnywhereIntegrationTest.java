package io.github.hectorvent.floci.services.rolesanywhere;

import io.github.hectorvent.floci.services.rolesanywhere.model.Subject;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Verifies IAM Roles Anywhere restJson1 subjects and trust-anchor not-found. */
@QuarkusTest
class RolesAnywhereIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000002601";
    private static final String MISSING = "00000000-0000-0000-0000-000000000000";

    @Inject
    RolesAnywhereService service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listSubjectsReturnsAnEmptyArrayWhenNoneExist() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .when()
                .get("/subjects")
                .then()
                .statusCode(200)
                .body("subjects", hasSize(0));
    }

    @Test
    void getSubjectOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .when()
                .get("/subject/" + MISSING)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getTrustAnchorOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .when()
                .get("/trustanchor/" + MISSING)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void putSubjectIsListedAndFetched() {
        Subject subject = service.putSubject(EAST, "CN=alchemy-rolesanywhere-test");
        assertNotNull(subject.getSubjectId());

        given()
                .contentType("application/json")
                .header("Authorization", authDefault(EAST))
                .when()
                .get("/subjects")
                .then()
                .statusCode(200)
                .body("subjects.subjectId", hasItem(subject.getSubjectId()))
                .body("subjects.x509Subject", hasItem("CN=alchemy-rolesanywhere-test"));

        given()
                .contentType("application/json")
                .header("Authorization", authDefault(EAST))
                .when()
                .get("/subject/" + subject.getSubjectId())
                .then()
                .statusCode(200)
                .body("subject.subjectId", equalTo(subject.getSubjectId()))
                .body("subject.subjectArn", equalTo(subject.getSubjectArn()))
                .body("subject.x509Subject", equalTo("CN=alchemy-rolesanywhere-test"))
                .body("subject.enabled", equalTo(true))
                .body("subject.createdAt", notNullValue());
    }

    @Test
    void trustAnchorCreateGetListTagAndDeleteLifecycle() {
        String authorization = auth(ACCOUNT, EAST);
        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name": "alchemy-ra-anchor",
                          "source": {
                            "sourceType": "CERTIFICATE_BUNDLE",
                            "sourceData": { "x509CertificateData": "-----BEGIN CERTIFICATE-----\\nMIIB\\n-----END CERTIFICATE-----" }
                          },
                          "enabled": true,
                          "tags": [{"key": "Owner", "value": "floci"}]
                        }
                        """)
                .when()
                .post("/trustanchors")
                .then()
                .statusCode(200)
                .body("trustAnchor.name", equalTo("alchemy-ra-anchor"))
                .body("trustAnchor.enabled", equalTo(true))
                .body("trustAnchor.trustAnchorId", notNullValue())
                .body("trustAnchor.trustAnchorArn", notNullValue())
                .extract()
                .path("trustAnchor.trustAnchorArn");
        String id = arn.substring(arn.lastIndexOf('/') + 1);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/trustanchor/" + id)
                .then()
                .statusCode(200)
                .body("trustAnchor.trustAnchorArn", equalTo(arn))
                .body("trustAnchor.source.sourceType", equalTo("CERTIFICATE_BUNDLE"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/trustanchors")
                .then()
                .statusCode(200)
                .body("trustAnchors.trustAnchorId", hasItem(id));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/ListTagsForResource?resourceArn=" + arn)
                .then()
                .statusCode(200)
                .body("tags.key", hasItem("Owner"))
                .body("tags.value", hasItem("floci"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/trustanchor/" + id)
                .then()
                .statusCode(200)
                .body("trustAnchor.trustAnchorId", equalTo(id));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/trustanchor/" + id)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void profileCrlNotificationsAndMappingsLifecycle() {
        String authorization = auth(ACCOUNT, EAST);
        String roleArn = "arn:aws:iam::" + ACCOUNT + ":role/RolesAnywhereRole";
        String crl1 = java.util.Base64.getEncoder().encodeToString(
                "-----BEGIN X509 CRL-----\nMIIBCRL1\n-----END X509 CRL-----".getBytes());
        String crl2 = java.util.Base64.getEncoder().encodeToString(
                "-----BEGIN X509 CRL-----\nMIIBCRL2\n-----END X509 CRL-----".getBytes());

        String anchorArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name": "alchemy-ra-anchor-b",
                          "source": {
                            "sourceType": "CERTIFICATE_BUNDLE",
                            "sourceData": { "x509CertificateData": "-----BEGIN CERTIFICATE-----\\nMIIB\\n-----END CERTIFICATE-----" }
                          },
                          "notificationSettings": [
                            {"event": "CA_CERTIFICATE_EXPIRY", "enabled": true, "threshold": 40, "channel": "ALL"}
                          ]
                        }
                        """)
                .when()
                .post("/trustanchors")
                .then()
                .statusCode(200)
                .body("trustAnchor.notificationSettings.find { it.event == 'CA_CERTIFICATE_EXPIRY' }.threshold",
                        equalTo(40))
                .extract()
                .path("trustAnchor.trustAnchorArn");
        String anchorId = anchorArn.substring(anchorArn.lastIndexOf('/') + 1);

        String profileId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name": "alchemy-ra-profile",
                          "roleArns": ["%s"],
                          "durationSeconds": 3600,
                          "enabled": true
                        }
                        """.formatted(roleArn))
                .when()
                .post("/profiles")
                .then()
                .statusCode(200)
                .body("profile.durationSeconds", equalTo(3600))
                .body("profile.roleArns", hasItem(roleArn))
                .extract()
                .path("profile.profileId");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "certificateField": "x509Subject",
                          "mappingRules": [{"specifier": "CN"}]
                        }
                        """)
                .when()
                .put("/profiles/" + profileId + "/mappings")
                .then()
                .statusCode(200)
                .body("profile.attributeMappings.find { it.certificateField == 'x509Subject' }.mappingRules.specifier",
                        equalTo(java.util.List.of("CN")));

        String crlId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name": "alchemy-ra-crl",
                          "crlData": "%s",
                          "trustAnchorArn": "%s",
                          "enabled": true
                        }
                        """.formatted(crl1, anchorArn))
                .when()
                .post("/crls")
                .then()
                .statusCode(200)
                .body("crl.enabled", equalTo(true))
                .body("crl.trustAnchorArn", equalTo(anchorArn))
                .extract()
                .path("crl.crlId");

        given()
                .header("Authorization", authorization)
                .when()
                .post("/trustanchor/" + anchorId + "/disable")
                .then()
                .statusCode(200)
                .body("trustAnchor.enabled", equalTo(false));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "trustAnchorId": "%s",
                          "notificationSettings": [
                            {"event": "CA_CERTIFICATE_EXPIRY", "enabled": true, "threshold": 30, "channel": "ALL"}
                          ]
                        }
                        """.formatted(anchorId))
                .when()
                .patch("/put-notifications-settings")
                .then()
                .statusCode(200)
                .body("trustAnchor.notificationSettings.find { it.event == 'CA_CERTIFICATE_EXPIRY' }.threshold",
                        equalTo(30));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"crlData": "%s"}
                        """.formatted(crl2))
                .when()
                .patch("/crl/" + crlId)
                .then()
                .statusCode(200)
                .body("crl.crlData", equalTo(crl2));

        given()
                .header("Authorization", authorization)
                .when()
                .post("/crl/" + crlId + "/disable")
                .then()
                .statusCode(200)
                .body("crl.enabled", equalTo(false));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "durationSeconds": 7200,
                          "sessionPolicy": "{\\"Version\\":\\"2012-10-17\\"}",
                          "roleArns": ["%s"]
                        }
                        """.formatted(roleArn))
                .when()
                .patch("/profile/" + profileId)
                .then()
                .statusCode(200)
                .body("profile.durationSeconds", equalTo(7200));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "certificateField": "x509Subject",
                          "mappingRules": [{"specifier": "CN"}, {"specifier": "OU"}]
                        }
                        """)
                .when()
                .put("/profiles/" + profileId + "/mappings")
                .then()
                .statusCode(200)
                .body("profile.attributeMappings.find { it.certificateField == 'x509Subject' }.mappingRules.specifier",
                        hasItems("CN", "OU"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/profiles/" + profileId + "/mappings?certificateField=x509Subject")
                .then()
                .statusCode(200)
                .body("profile.attributeMappings.find { it.certificateField == 'x509Subject' }.mappingRules.specifier",
                        not(hasItem("OU")));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "trustAnchorId": "%s",
                          "notificationSettingKeys": [{"event": "CA_CERTIFICATE_EXPIRY", "channel": "ALL"}]
                        }
                        """.formatted(anchorId))
                .when()
                .patch("/reset-notifications-settings")
                .then()
                .statusCode(200)
                .body("trustAnchor.notificationSettings.find { it.event == 'CA_CERTIFICATE_EXPIRY' }.threshold",
                        not(equalTo(30)))
                .body("trustAnchor.notificationSettings.find { it.event == 'CA_CERTIFICATE_EXPIRY' }.threshold",
                        equalTo(45));

        given().header("Authorization", authorization).when().delete("/crl/" + crlId).then().statusCode(200);
        given().header("Authorization", authorization).when().get("/crl/" + crlId)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        given().header("Authorization", authorization).when().delete("/profile/" + profileId).then().statusCode(200);
        given().header("Authorization", authorization).when().delete("/trustanchor/" + anchorId).then().statusCode(200);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/rolesanywhere/aws4_request";
    }

    private static String authDefault(String region) {
        return auth("000000000000", region);
    }
}
