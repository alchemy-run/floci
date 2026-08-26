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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 coverage for Alchemy {@code ServiceQuotaIncreaseRequest}:
 * {@code GetServiceQuota} typed {@code NoSuchResourceException} for an unknown
 * quota code, already-satisfied VPC defaults so a desired value of 1 never
 * submits, and empty PENDING history so reconcile does not adopt a request.
 */
@QuarkusTest
class ServiceQuotaIncreaseRequestIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicequotas/aws4_request";
    private static final String VPCS_PER_REGION = "L-F678F1CE";
    private static final String SUBNETS_PER_VPC = "L-407747CB";

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
    void getServiceQuota_vpcsPerRegion_isAlreadySatisfiedAtOne() {
        sq("GetServiceQuota", "{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"" + VPCS_PER_REGION + "\"}")
                .then()
                .statusCode(200)
                .body("Quota.ServiceCode", equalTo("vpc"))
                .body("Quota.QuotaCode", equalTo(VPCS_PER_REGION))
                .body("Quota.QuotaName", notNullValue())
                .body("Quota.Value", greaterThanOrEqualTo(1.0f));
    }

    @Test
    void getServiceQuota_subnetsPerVpc_isAlreadySatisfiedAtOne() {
        sq("GetServiceQuota", "{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"" + SUBNETS_PER_VPC + "\"}")
                .then()
                .statusCode(200)
                .body("Quota.QuotaCode", equalTo(SUBNETS_PER_VPC))
                .body("Quota.QuotaName", notNullValue())
                .body("Quota.Value", greaterThanOrEqualTo(1.0f));
    }

    @Test
    void listHistoryByQuota_pending_isEmptyWhenSatisfied() {
        sq("ListRequestedServiceQuotaChangeHistoryByQuota",
                "{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"" + VPCS_PER_REGION + "\",\"Status\":\"PENDING\"}")
                .then()
                .statusCode(200)
                .body("RequestedQuotas.size()", equalTo(0));
    }

    @Test
    void requestServiceQuotaIncrease_atOrBelowApplied_isRejected() {
        sq("RequestServiceQuotaIncrease",
                "{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"" + VPCS_PER_REGION + "\",\"DesiredValue\":1}")
                .then()
                .statusCode(405)
                .body("__type", equalTo("InvalidResourceStateException"));
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
