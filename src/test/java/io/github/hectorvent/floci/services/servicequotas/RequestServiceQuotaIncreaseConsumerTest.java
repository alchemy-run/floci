package io.github.hectorvent.floci.services.servicequotas;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Wire-level tests for {@code ServiceQuotasV20190624.RequestServiceQuotaIncrease}.
 *
 * <p>These drive the real HTTP route with the real {@code X-Amz-Target} header, so the
 * dispatch case itself is under test: remove the case from
 * {@link ServiceQuotasJsonHandler} and every test here fails on
 * {@code UnknownOperationException}. A green run therefore proves the operation is
 * reachable by name, which a service-level test could not (CS-001).
 *
 * <p><strong>Known limitation asserted here deliberately:</strong> the emulator has no approval
 * process, so {@code Status} is always {@code PENDING} and never advances, and the applied quota
 * value never changes. Requests are recorded and observable through
 * {@code GetRequestedServiceQuotaChange} and the request-history operations. Every test uses a
 * distinct quota because an open request blocks a second one for the same quota, as in AWS.
 */
@QuarkusTest
class RequestServiceQuotaIncreaseConsumerTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicequotas/aws4_request";
    private static final String TARGET = "ServiceQuotasV20190624.RequestServiceQuotaIncrease";
    private static final String CONCURRENT_BUILDS_QUOTA = "L-2DC20C30";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static io.restassured.response.Response request(String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/");
    }

    @Test
    void requestIncrease_curatedQuota_returnsFullyPopulatedPendingRequest() {
        request("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"" + CONCURRENT_BUILDS_QUOTA
                + "\",\"DesiredValue\":9000}")
        .then()
            .statusCode(200)
            .body("RequestedQuota.Id", matchesRegex("[0-9a-f]{32}"))
            .body("RequestedQuota.ServiceCode", equalTo("codebuild"))
            .body("RequestedQuota.ServiceName", equalTo("AWS CodeBuild"))
            .body("RequestedQuota.QuotaCode", equalTo(CONCURRENT_BUILDS_QUOTA))
            .body("RequestedQuota.QuotaName", equalTo("Concurrently running builds"))
            .body("RequestedQuota.QuotaArn", equalTo(
                    "arn:aws:servicequotas:us-east-1:000000000000:codebuild/" + CONCURRENT_BUILDS_QUOTA))
            .body("RequestedQuota.DesiredValue", equalTo(9000.0f))
            .body("RequestedQuota.Status", equalTo("PENDING"))
            .body("RequestedQuota.Unit", equalTo("None"))
            .body("RequestedQuota.GlobalQuota", equalTo(false))
            .body("RequestedQuota.QuotaRequestedAtLevel", equalTo("ACCOUNT"))
            .body("RequestedQuota.Requester", notNullValue())
            .body("RequestedQuota.Created", notNullValue())
            .body("RequestedQuota.LastUpdated", notNullValue());
    }

    @Test
    void requestIncrease_unknownService_returnsNoSuchResource() {
        request("{\"ServiceCode\":\"widgetfactory\",\"QuotaCode\":\"L-12345678\",\"DesiredValue\":1}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchResourceException"));
    }

    /** An open request for the quota blocks another submission, as in AWS. */
    @Test
    void requestIncrease_sameQuotaTwice_rejectsTheSecondWithResourceAlreadyExists() {
        String body = "{\"ServiceCode\":\"lambda\",\"QuotaCode\":\"L-B99A9384\",\"DesiredValue\":2000}";
        request(body).then().statusCode(200);
        request(body)
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceAlreadyExistsException"));
    }

    @Test
    void requestIncrease_withContextId_emitsQuotaContext() {
        request("{\"ServiceCode\":\"lambda\",\"QuotaCode\":\"L-9FEE3D26\",\"DesiredValue\":600,"
                + "\"ContextId\":\"arn:aws:lambda:us-east-1:000000000000:function:fn\"}")
        .then()
            .statusCode(200)
            .body("RequestedQuota.QuotaContext.ContextId",
                    equalTo("arn:aws:lambda:us-east-1:000000000000:function:fn"))
            .body("RequestedQuota.QuotaContext.ContextScope", equalTo("RESOURCE"))
            .body("RequestedQuota.QuotaRequestedAtLevel", equalTo("RESOURCE"));
    }

    /** An unmodelled-by-the-emulator field must be absent, not null-valued or invented. */
    @Test
    void requestIncrease_withoutContextId_omitsQuotaContextAndCaseId() {
        request("{\"ServiceCode\":\"ec2\",\"QuotaCode\":\"L-0263D0A3\",\"DesiredValue\":10}")
        .then()
            .statusCode(200)
            .body("RequestedQuota.QuotaContext", nullValue())
            .body("RequestedQuota.CaseId", nullValue());
    }

    @Test
    void requestIncrease_desiredValueNotAboveCurrentValue_returnsIllegalArgument() {
        request("{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"L-A4707A72\",\"DesiredValue\":5}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IllegalArgumentException"));
    }

    @Test
    void requestIncrease_unknownQuotaCode_returnsNoSuchResource() {
        request("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"L-DEADBEEF\",\"DesiredValue\":1}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchResourceException"));
    }

    @Test
    void requestIncrease_missingQuotaCode_returnsIllegalArgument() {
        request("{\"ServiceCode\":\"codebuild\",\"DesiredValue\":1}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IllegalArgumentException"));
    }

    @Test
    void requestIncrease_missingServiceCode_returnsIllegalArgument() {
        request("{\"QuotaCode\":\"" + CONCURRENT_BUILDS_QUOTA + "\",\"DesiredValue\":1}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IllegalArgumentException"));
    }

    @Test
    void requestIncrease_missingDesiredValue_returnsIllegalArgument() {
        request("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"" + CONCURRENT_BUILDS_QUOTA + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IllegalArgumentException"));
    }

    @Test
    void requestIncrease_negativeDesiredValue_returnsIllegalArgument() {
        request("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"" + CONCURRENT_BUILDS_QUOTA
                + "\",\"DesiredValue\":-1}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IllegalArgumentException"));
    }

    /** Packet bounds are min=0 max=10000000000; one past the top must be rejected. */
    @Test
    void requestIncrease_desiredValueAboveModelledMaximum_returnsIllegalArgument() {
        request("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"" + CONCURRENT_BUILDS_QUOTA
                + "\",\"DesiredValue\":10000000001}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IllegalArgumentException"));
    }
}
