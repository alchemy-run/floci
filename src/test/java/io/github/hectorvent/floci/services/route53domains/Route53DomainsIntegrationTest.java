package io.github.hectorvent.floci.services.route53domains;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 Route 53 Domains coverage used by Alchemy Bindings.test.ts:
 * availability, list/prices/suggestions, and typed domain-not-found errors.
 */
@QuarkusTest
class Route53DomainsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/route53domains/aws4_request";
    private static final String TARGET_PREFIX = "Route53Domains_v20140515.";

    @Inject
    Route53DomainsService service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void reset() {
        service.clear();
    }

    @Test
    void checkDomainAvailability_unregisteredName_returnsAvailable() {
        domains("CheckDomainAvailability",
                "{\"DomainName\":\"alchemy-effect-r53d-probe-a32892.com\"}")
                .then()
                .statusCode(200)
                .body("Availability", equalTo("AVAILABLE"));
    }

    @Test
    void checkDomainAvailability_unsupportedTld_returnsUnsupportedTLD() {
        domains("CheckDomainAvailability",
                "{\"DomainName\":\"alchemy-effect-r53d-probe-a32892.invalidtld99\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("UnsupportedTLD"));
    }

    @Test
    void getDomainDetail_unknownDomain_returnsInvalidInputNotInAccount() {
        domains("GetDomainDetail", "{\"DomainName\":\"example.com\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInput"))
                .body("message", equalTo("Domain example.com not found in account."));
    }

    @Test
    void listDomains_emptyAccount_returnsEmptyList() {
        domains("ListDomains", "{\"MaxItems\":100}")
                .then()
                .statusCode(200)
                .body("Domains", hasSize(0));
    }

    @Test
    void retrieveDomainAuthCode_unknownDomain_returnsInvalidInputNotInAccount() {
        domains("RetrieveDomainAuthCode", "{\"DomainName\":\"example.com\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInput"))
                .body("message", equalTo("Domain example.com not found in account."));
    }

    @Test
    void updateDomainNameservers_unknownDomain_returnsInvalidInputNotInAccount() {
        domains("UpdateDomainNameservers",
                "{\"DomainName\":\"example.com\",\"Nameservers\":[{\"Name\":\"ns-1.awsdns-01.org\"}]}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInput"))
                .body("message", equalTo("Domain example.com not found in account."));
    }

    @Test
    void getDomainSuggestions_returnsList() {
        domains("GetDomainSuggestions",
                "{\"DomainName\":\"example.com\",\"SuggestionCount\":5,\"OnlyAvailable\":false}")
                .then()
                .statusCode(200)
                .body("SuggestionsList", hasSize(5));
    }

    @Test
    void listPrices_com_returnsRegistrationPrice() {
        domains("ListPrices", "{\"Tld\":\"com\"}")
                .then()
                .statusCode(200)
                .body("Prices", hasSize(1))
                .body("Prices[0].Name", equalTo("com"))
                .body("Prices[0].RegistrationPrice.Price", greaterThan(0f));
    }

    @Test
    void listOperations_emptyAccount_returnsEmptyList() {
        domains("ListOperations", "{\"MaxItems\":10}")
                .then()
                .statusCode(200)
                .body("Operations", hasSize(0));
    }

    @Test
    void getOperationDetail_unknownId_returnsInvalidInput() {
        domains("GetOperationDetail",
                "{\"OperationId\":\"00000000-0000-0000-0000-000000000000\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInput"));
    }

    @Test
    void renewDomain_unknownDomain_returnsInvalidInputNotInAccount() {
        domains("RenewDomain",
                "{\"DomainName\":\"example.com\",\"DurationInYears\":1,\"CurrentExpiryYear\":2030}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInput"))
                .body("message", equalTo("Domain example.com not found in account."));
    }

    @Test
    void registerDomain_unsupportedTld_returnsUnsupportedTLD() {
        domains("RegisterDomain", "{"
                + "\"DomainName\":\"alchemy-effect-r53d-probe-a32892.invalidtld99\","
                + "\"DurationInYears\":1,"
                + "\"AdminContact\":{},"
                + "\"RegistrantContact\":{},"
                + "\"TechContact\":{}}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("UnsupportedTLD"));
    }

    @Test
    void checkDomainTransferability_returnsVerdict() {
        domains("CheckDomainTransferability", "{\"DomainName\":\"example.com\"}")
                .then()
                .statusCode(200)
                .body("Transferability.Transferable", notNullValue());
    }

    @Test
    void registerDomain_supportedTld_roundTripDetailAndList() {
        String domain = "alchemy-floci-r53d-test.com";
        String operationId = domains("RegisterDomain", "{"
                + "\"DomainName\":\"" + domain + "\","
                + "\"DurationInYears\":1,"
                + "\"AdminContact\":{},"
                + "\"RegistrantContact\":{},"
                + "\"TechContact\":{}}")
                .then()
                .statusCode(200)
                .body("OperationId", notNullValue())
                .extract().path("OperationId");

        domains("CheckDomainAvailability", "{\"DomainName\":\"" + domain + "\"}")
                .then()
                .statusCode(200)
                .body("Availability", equalTo("UNAVAILABLE"));

        domains("GetDomainDetail", "{\"DomainName\":\"" + domain + "\"}")
                .then()
                .statusCode(200)
                .body("DomainName", equalTo(domain));

        domains("ListDomains", "{\"MaxItems\":100}")
                .then()
                .statusCode(200)
                .body("Domains.DomainName", org.hamcrest.Matchers.hasItem(domain));

        domains("GetOperationDetail", "{\"OperationId\":\"" + operationId + "\"}")
                .then()
                .statusCode(200)
                .body("Status", equalTo("SUCCESSFUL"))
                .body("Type", equalTo("REGISTER_DOMAIN"));

        domains("RetrieveDomainAuthCode", "{\"DomainName\":\"" + domain + "\"}")
                .then()
                .statusCode(200)
                .body("AuthCode", notNullValue());
    }

    private static Response domains(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
