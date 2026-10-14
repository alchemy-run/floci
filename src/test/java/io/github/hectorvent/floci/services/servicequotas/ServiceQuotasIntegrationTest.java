package io.github.hectorvent.floci.services.servicequotas;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration tests for the Service Quotas service.
 * Protocol: JSON 1.1, Content-Type: application/x-amz-json-1.1,
 * X-Amz-Target: ServiceQuotasV20190624.&lt;Action&gt;
 */
@QuarkusTest
class ServiceQuotasIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicequotas/aws4_request";
    private static final String CONCURRENT_BUILDS_QUOTA = "L-2DC20C30";
    private static final String VPCS_PER_REGION = "L-F678F1CE";
    private static final String RDS_DB_INSTANCES = "L-7B6409FD";
    /** IAM "Groups per account": no test in this JVM submits an increase for it. */
    private static final String NEVER_REQUESTED_QUOTA = "L-F55AF5E4";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listServiceQuotas_codebuild_includesConcurrentlyRunningBuilds() {
        String quotaPath = "Quotas.find { it.QuotaCode == '" + CONCURRENT_BUILDS_QUOTA + "' }";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListServiceQuotas")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"codebuild\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(quotaPath + ".QuotaName", equalTo("Concurrently running builds"))
            .body(quotaPath + ".ServiceCode", equalTo("codebuild"))
            .body(quotaPath + ".ServiceName", equalTo("AWS CodeBuild"))
            .body(quotaPath + ".QuotaArn", equalTo(
                    "arn:aws:servicequotas:us-east-1:000000000000:codebuild/" + CONCURRENT_BUILDS_QUOTA))
            .body(quotaPath + ".Value", greaterThanOrEqualTo(60.0f))
            .body(quotaPath + ".Unit", equalTo("None"))
            .body(quotaPath + ".Adjustable", equalTo(true))
            .body(quotaPath + ".GlobalQuota", equalTo(false))
            .body(quotaPath + ".QuotaAppliedAtLevel", equalTo("ACCOUNT"));
    }

    @Test
    void listServiceQuotas_unknownServiceCode_returnsNoSuchResource() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListServiceQuotas")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"widgetfactory\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchResourceException"));
    }

    @Test
    void listServiceQuotas_missingServiceCode_returnsIllegalArgument() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListServiceQuotas")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IllegalArgumentException"));
    }

    @Test
    void listServiceQuotas_paginatesDeterministically() {
        Response first = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624.ListServiceQuotas")
                .header("Authorization", AUTH_HEADER)
                .body("{\"ServiceCode\":\"vpc\",\"MaxResults\":1}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Quotas.size()", equalTo(1))
                .body("NextToken", notNullValue())
                .extract().response();
        String firstCode = first.path("Quotas[0].QuotaCode");
        String nextToken = first.path("NextToken");
        assertEquals(VPCS_PER_REGION, firstCode);
        assertNotNull(nextToken);

        Response second = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624.ListServiceQuotas")
                .header("Authorization", AUTH_HEADER)
                .body("{\"ServiceCode\":\"vpc\",\"NextToken\":\"" + nextToken + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().response();
        List<String> remainingCodes = second.path("Quotas.QuotaCode");
        assertEquals(ServiceQuotasCatalog.service("vpc").orElseThrow().quotas().size() - 1, remainingCodes.size());
        assertFalse(remainingCodes.contains(VPCS_PER_REGION));
        assertNull(second.path("NextToken"));
    }

    @Test
    void listServiceQuotas_invalidNextToken_returnsInvalidPaginationToken() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListServiceQuotas")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"codebuild\",\"NextToken\":\"not_a_token!\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidPaginationTokenException"));
    }

    @Test
    void getServiceQuota_returnsConcurrentlyRunningBuilds() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.GetServiceQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"" + CONCURRENT_BUILDS_QUOTA + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Quota.QuotaCode", equalTo(CONCURRENT_BUILDS_QUOTA))
            .body("Quota.QuotaName", equalTo("Concurrently running builds"))
            .body("Quota.Value", greaterThanOrEqualTo(60.0f))
            .body("Quota.Adjustable", equalTo(true))
            .body("Quota.GlobalQuota", equalTo(false));
    }

    @Test
    void organizationsQuotaUsesAwsQuotaCode() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.GetServiceQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"organizations\",\"QuotaCode\":\"L-E619E033\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Quota.QuotaCode", equalTo("L-E619E033"))
            .body("Quota.QuotaName", equalTo("Default maximum number of accounts"))
            .body("Quota.Value", equalTo(10.0f))
            .body("Quota.GlobalQuota", equalTo(true));
    }

    @Test
    void listRequestedQuotaHistoryByQuotaReturnsEmptyHistoryForKnownQuota() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListRequestedServiceQuotaChangeHistoryByQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"iam\",\"QuotaCode\":\"" + NEVER_REQUESTED_QUOTA + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RequestedQuotas.size()", equalTo(0));
    }

    @Test
    void listRequestedQuotaHistoryByQuotaRejectsImpossibleNextToken() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListRequestedServiceQuotaChangeHistoryByQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"iam\",\"QuotaCode\":\"" + NEVER_REQUESTED_QUOTA + "\",\"NextToken\":\"MQ\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidPaginationTokenException"));
    }

    @Test
    void listRequestedQuotaHistoryByQuotaRejectsUnknownQuota() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListRequestedServiceQuotaChangeHistoryByQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"organizations\",\"QuotaCode\":\"L-DOESNOTEX\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchResourceException"));
    }

    @Test
    void requestOrganizationsQuotaIncreasePreservesGlobalQuota() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.RequestServiceQuotaIncrease")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"organizations\",\"QuotaCode\":\"L-E619E033\",\"DesiredValue\":100}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RequestedQuota.ServiceCode", equalTo("organizations"))
            .body("RequestedQuota.QuotaCode", equalTo("L-E619E033"))
            .body("RequestedQuota.GlobalQuota", equalTo(true));
    }

    @Test
    void getServiceQuota_unknownQuotaCode_returnsNoSuchResource() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.GetServiceQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"L-DOESNOTEX\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchResourceException"));
    }

    @Test
    void getAwsDefaultServiceQuota_matchesAppliedQuota() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.GetAWSDefaultServiceQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"" + CONCURRENT_BUILDS_QUOTA + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Quota.QuotaCode", equalTo(CONCURRENT_BUILDS_QUOTA))
            .body("Quota.QuotaName", equalTo("Concurrently running builds"));
    }

    /** Bindings.test.ts GetAWSDefaultServiceQuota reads vpc/L-F678F1CE (VPCs per Region, default 5). */
    @Test
    void getAwsDefaultServiceQuota_vpcsPerRegion_returnsAwsDefault() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.GetAWSDefaultServiceQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"" + VPCS_PER_REGION + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Quota.QuotaCode", equalTo(VPCS_PER_REGION))
            .body("Quota.QuotaName", equalTo("VPCs per Region"))
            .body("Quota.Value", equalTo(5.0f))
            .body("Quota.Adjustable", equalTo(true))
            .body("Quota.QuotaArn", equalTo("arn:aws:servicequotas:us-east-1::vpc/" + VPCS_PER_REGION));
    }

    @Test
    void getServiceQuota_subnetsPerVpc_returnsAppliedValue() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.GetServiceQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"L-407747CB\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Quota.QuotaName", equalTo("Subnets per VPC"))
            .body("Quota.Value", equalTo(200.0f))
            .body("Quota.QuotaArn", equalTo("arn:aws:servicequotas:us-east-1:000000000000:vpc/L-407747CB"));
    }

    @Test
    void listServices_returnsCatalogServicesWithPagination() {
        Response first = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624.ListServices")
                .header("Authorization", AUTH_HEADER)
                .body("{\"MaxResults\":2}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Services.size()", equalTo(2))
                .body("NextToken", notNullValue())
                .extract().response();

        Response all = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624.ListServices")
                .header("Authorization", AUTH_HEADER)
                .body("{}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Services.ServiceCode", hasItems("vpc", "lambda", "ec2", "iam"))
                .body("NextToken", nullValue())
                .extract().response();
        List<String> allCodes = all.path("Services.ServiceCode");
        List<String> firstCodes = first.path("Services.ServiceCode");
        assertEquals(allCodes.subList(0, 2), firstCodes);
    }

    @Test
    void requestHistoryRoundTripsThroughGetAndListOperations() {
        String requestId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624.RequestServiceQuotaIncrease")
                .header("Authorization", AUTH_HEADER)
                .body("{\"ServiceCode\":\"rds\",\"QuotaCode\":\"" + RDS_DB_INSTANCES + "\",\"DesiredValue\":50}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("RequestedQuota.Status", equalTo("PENDING"))
                .extract().path("RequestedQuota.Id");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.GetRequestedServiceQuotaChange")
            .header("Authorization", AUTH_HEADER)
            .body("{\"RequestId\":\"" + requestId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RequestedQuota.Id", equalTo(requestId))
            .body("RequestedQuota.QuotaCode", equalTo(RDS_DB_INSTANCES))
            .body("RequestedQuota.DesiredValue", equalTo(50.0f));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListRequestedServiceQuotaChangeHistoryByQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"rds\",\"QuotaCode\":\"" + RDS_DB_INSTANCES + "\",\"Status\":\"PENDING\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RequestedQuotas.Id", hasItems(requestId));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListRequestedServiceQuotaChangeHistory")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"rds\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RequestedQuotas.Id", hasItems(requestId));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.RequestServiceQuotaIncrease")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"rds\",\"QuotaCode\":\"" + RDS_DB_INSTANCES + "\",\"DesiredValue\":60}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceAlreadyExistsException"));
    }

    @Test
    void getRequestedServiceQuotaChange_unknownId_returnsNoSuchResource() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.GetRequestedServiceQuotaChange")
            .header("Authorization", AUTH_HEADER)
            .body("{\"RequestId\":\"0123456789abcdef0123456789abcdef\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchResourceException"));
    }

    @Test
    void listAwsDefaultServiceQuotas_matchesAppliedQuotas() {
        Response applied = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624.ListServiceQuotas")
                .header("Authorization", AUTH_HEADER)
                .body("{\"ServiceCode\":\"lambda\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().response();

        Response defaults = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624.ListAWSDefaultServiceQuotas")
                .header("Authorization", AUTH_HEADER)
                .body("{\"ServiceCode\":\"lambda\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().response();

        List<String> appliedCodes = applied.path("Quotas.QuotaCode");
        List<String> defaultCodes = defaults.path("Quotas.QuotaCode");
        assertEquals(appliedCodes, defaultCodes);
        assertEquals("L-B99A9384", appliedCodes.getFirst());
    }

    @Test
    void unknownAction_returnsUnknownOperation() {
        // Deliberately a name AWS will never define. This test previously used
        // RequestServiceQuotaIncrease, which then became supported and silently
        // inverted the test's premise. A synthetic name keeps the assertion honest
        // no matter which real operations get implemented later.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ThisOperationDoesNotExist")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
