package io.github.hectorvent.floci.services.organizations;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 Organizations coverage used by Alchemy DelegatedAdministrator:
 * list returns an array, and register / list-services / deregister round-trip
 * a member account.
 */
@QuarkusTest
class OrganizationsDelegatedAdministratorIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/organizations/aws4_request";
    private static final String TARGET = "AWSOrganizationsV20161128.";
    private static final String MEMBER = "222222222222";
    private static final String PRINCIPAL = "config.amazonaws.com";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listDelegatedAdministrators_returnsArray() {
        ensureOrganization();
        invoke("ListDelegatedAdministrators", "{}")
                .then()
                .statusCode(200)
                .body("DelegatedAdministrators", notNullValue());
    }

    @Test
    void registerListServicesAndDeregisterDelegatedAdministrator() {
        ensureOrganization();
        invoke("DeregisterDelegatedAdministrator",
                "{\"AccountId\":\"" + MEMBER + "\",\"ServicePrincipal\":\"" + PRINCIPAL + "\"}");

        invoke("ListDelegatedAdministrators", "{}")
                .then()
                .statusCode(200)
                .body("DelegatedAdministrators", notNullValue());

        invoke("RegisterDelegatedAdministrator",
                "{\"AccountId\":\"" + MEMBER + "\",\"ServicePrincipal\":\"" + PRINCIPAL + "\"}")
                .then()
                .statusCode(200);

        invoke("RegisterDelegatedAdministrator",
                "{\"AccountId\":\"" + MEMBER + "\",\"ServicePrincipal\":\"" + PRINCIPAL + "\"}")
                .then()
                .statusCode(409)
                .body("__type", equalTo("AccountAlreadyRegisteredException"));

        invoke("ListDelegatedAdministrators", "{}")
                .then()
                .statusCode(200)
                .body("DelegatedAdministrators.find { it.Id == '" + MEMBER + "' }.Id", equalTo(MEMBER))
                .body("DelegatedAdministrators.find { it.Id == '" + MEMBER + "' }.Arn", notNullValue())
                .body("DelegatedAdministrators.find { it.Id == '" + MEMBER + "' }.Status", equalTo("ACTIVE"));

        invoke("ListDelegatedAdministrators", "{\"ServicePrincipal\":\"" + PRINCIPAL + "\"}")
                .then()
                .statusCode(200)
                .body("DelegatedAdministrators.find { it.Id == '" + MEMBER + "' }.Id", equalTo(MEMBER));

        invoke("ListDelegatedServicesForAccount", "{\"AccountId\":\"" + MEMBER + "\"}")
                .then()
                .statusCode(200)
                .body("DelegatedServices", hasSize(1))
                .body("DelegatedServices[0].ServicePrincipal", equalTo(PRINCIPAL))
                .body("DelegatedServices[0].DelegationEnabledDate", notNullValue());

        invoke("DeregisterDelegatedAdministrator",
                "{\"AccountId\":\"" + MEMBER + "\",\"ServicePrincipal\":\"" + PRINCIPAL + "\"}")
                .then()
                .statusCode(200);

        invoke("ListDelegatedServicesForAccount", "{\"AccountId\":\"" + MEMBER + "\"}")
                .then()
                .statusCode(409)
                .body("__type", equalTo("AccountNotRegisteredException"));
    }

    @Test
    void registerDelegatedAdministrator_managementAccount_constraintViolation() {
        ensureOrganization();
        String master = invoke("DescribeOrganization", "{}")
                .then()
                .statusCode(200)
                .extract()
                .path("Organization.MasterAccountId");
        invoke("RegisterDelegatedAdministrator",
                "{\"AccountId\":\"" + master + "\",\"ServicePrincipal\":\"" + PRINCIPAL + "\"}")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConstraintViolationException"));
    }

    private static void ensureOrganization() {
        Response created = invoke("CreateOrganization", "{}");
        int status = created.statusCode();
        if (status != 200 && status != 409) {
            created.then().statusCode(200);
        }
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
