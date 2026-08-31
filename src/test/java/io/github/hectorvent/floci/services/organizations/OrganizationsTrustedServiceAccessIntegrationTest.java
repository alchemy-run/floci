package io.github.hectorvent.floci.services.organizations;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 Organizations coverage used by Alchemy TrustedServiceAccess:
 * {@code ListAWSServiceAccessForOrganization} enumerates enabled principals,
 * plus enable / disable round-trip.
 */
@QuarkusTest
class OrganizationsTrustedServiceAccessIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/organizations/aws4_request";
    private static final String TARGET = "AWSOrganizationsV20161128.";
    private static final String PRINCIPAL = "config.amazonaws.com";

    @Inject
    OrganizationsService service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void reset() {
        service.clear();
    }

    @Test
    void listAWSServiceAccessForOrganization_returnsArray() {
        invoke("ListAWSServiceAccessForOrganization", "{}")
                .then()
                .statusCode(200)
                .body("EnabledServicePrincipals", hasSize(0));
    }

    @Test
    void enableListAndDisableAWSServiceAccess_roundTrip() {
        invoke("EnableAWSServiceAccess", "{\"ServicePrincipal\":\"" + PRINCIPAL + "\"}")
                .then()
                .statusCode(200);

        Number dateEnabled = invoke("ListAWSServiceAccessForOrganization", "{}")
                .then()
                .statusCode(200)
                .body("EnabledServicePrincipals", hasSize(1))
                .body("EnabledServicePrincipals[0].ServicePrincipal", equalTo(PRINCIPAL))
                .body("EnabledServicePrincipals[0].DateEnabled", notNullValue())
                .extract()
                .path("EnabledServicePrincipals[0].DateEnabled");

        invoke("EnableAWSServiceAccess", "{\"ServicePrincipal\":\"" + PRINCIPAL + "\"}")
                .then()
                .statusCode(200);

        invoke("ListAWSServiceAccessForOrganization", "{}")
                .then()
                .statusCode(200)
                .body("EnabledServicePrincipals", hasSize(1))
                .body("EnabledServicePrincipals[0].DateEnabled", equalTo(dateEnabled));

        invoke("DisableAWSServiceAccess", "{\"ServicePrincipal\":\"" + PRINCIPAL + "\"}")
                .then()
                .statusCode(200);

        invoke("ListAWSServiceAccessForOrganization", "{}")
                .then()
                .statusCode(200)
                .body("EnabledServicePrincipals", hasSize(0));

        invoke("DisableAWSServiceAccess", "{\"ServicePrincipal\":\"" + PRINCIPAL + "\"}")
                .then()
                .statusCode(200);
    }

    @Test
    void enableAWSServiceAccess_missingPrincipal_invalidInput() {
        invoke("EnableAWSServiceAccess", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
    }

    private static Response invoke(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
