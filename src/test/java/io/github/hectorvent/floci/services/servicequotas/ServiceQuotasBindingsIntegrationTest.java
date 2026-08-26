package io.github.hectorvent.floci.services.servicequotas;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 coverage for the Alchemy Service Quotas bindings suite.
 */
@QuarkusTest
class ServiceQuotasBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicequotas/aws4_request";

    @Inject
    ServiceQuotasService service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void reset() {
        service.clear();
    }

    @Test
    void getServiceQuota_bogusQuota_returnsNoSuchResource() {
        sq("GetServiceQuota", "{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"L-00000000\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NoSuchResourceException"));
    }

    @Test
    void getServiceQuota_lambdaConcurrentExecutions_returnsAppliedValue() {
        sq("GetServiceQuota", "{\"ServiceCode\":\"lambda\",\"QuotaCode\":\"L-B99A9384\"}")
                .then()
                .statusCode(200)
                .body("Quota.QuotaCode", equalTo("L-B99A9384"))
                .body("Quota.Value", equalTo(1000.0f));
    }

    @Test
    void getAWSDefaultServiceQuota_vpcPerRegion_returnsDefault() {
        sq("GetAWSDefaultServiceQuota", "{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"L-F678F1CE\"}")
                .then()
                .statusCode(200)
                .body("Quota.QuotaCode", equalTo("L-F678F1CE"))
                .body("Quota.Value", equalTo(5.0f));
    }

    @Test
    void listServices_returnsCatalogCodes() {
        sq("ListServices", "{\"MaxResults\":20}")
                .then()
                .statusCode(200)
                .body("Services.size()", greaterThan(0))
                .body("Services.ServiceCode", hasItem("vpc"))
                .body("Services.ServiceCode", hasItem("lambda"));
    }

    @Test
    void listServiceQuotas_vpc_returnsAppliedQuotas() {
        sq("ListServiceQuotas", "{\"ServiceCode\":\"vpc\",\"MaxResults\":20}")
                .then()
                .statusCode(200)
                .body("Quotas.size()", greaterThan(0))
                .body("Quotas.QuotaCode", hasItem("L-F678F1CE"));
    }

    @Test
    void listRequestedServiceQuotaChangeHistoryByQuota_empty_returnsZero() {
        sq("ListRequestedServiceQuotaChangeHistoryByQuota",
                "{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"L-F678F1CE\"}")
                .then()
                .statusCode(200)
                .body("RequestedQuotas.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void requestServiceQuotaIncrease_bogusQuota_returnsNoSuchResource() {
        sq("RequestServiceQuotaIncrease",
                "{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"L-00000000\",\"DesiredValue\":1}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NoSuchResourceException"));
    }

    @Test
    void requestServiceQuotaIncrease_knownQuota_autoApprovesAndApplies() {
        String id = sq("RequestServiceQuotaIncrease",
                "{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"L-F678F1CE\",\"DesiredValue\":10}")
                .then()
                .statusCode(200)
                .body("RequestedQuota.Id", notNullValue())
                .body("RequestedQuota.Status", equalTo("APPROVED"))
                .extract().path("RequestedQuota.Id");

        sq("GetRequestedServiceQuotaChange", "{\"RequestId\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("RequestedQuota.Id", equalTo(id))
                .body("RequestedQuota.DesiredValue", equalTo(10.0f));

        sq("GetServiceQuota", "{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"L-F678F1CE\"}")
                .then()
                .statusCode(200)
                .body("Quota.Value", equalTo(10.0f));

        sq("ListRequestedServiceQuotaChangeHistoryByQuota",
                "{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"L-F678F1CE\"}")
                .then()
                .statusCode(200)
                .body("RequestedQuotas.size()", greaterThan(0));
    }

    private static Response sq(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", ServiceQuotasJsonHandler.TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
