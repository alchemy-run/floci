package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesDeliverabilityV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    @Test
    @Order(1)
    void getBlacklistReports_returnsEmptyEntries() {
        given().header("Authorization", AUTH)
                .queryParam("BlacklistItemNames", "192.0.2.1")
                .when().get("/v2/email/deliverability-dashboard/blacklist-report")
                .then().statusCode(200)
                .body("BlacklistReport", hasKey("192.0.2.1"));
    }

    @Test
    @Order(2)
    void getMessageInsights_withoutVdm_isNotFound() {
        given().header("Authorization", AUTH)
                .when().get("/v2/email/insights/00000000-0000-0000-0000-000000000000")
                .then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(3)
    void batchGetMetricData_withoutVdm_isBadRequest() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"Queries\":[{\"Id\":\"sends\",\"Namespace\":\"VDM\",\"Metric\":\"SEND\","
                        + "\"StartDate\":0,\"EndDate\":86400}]}")
                .when().post("/v2/email/metrics/batch")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(4)
    void getDomainStatisticsReport_unknownDomain_isNotFound() {
        given().header("Authorization", AUTH)
                .when().get("/v2/email/deliverability-dashboard/statistics-report/missing.floci.test")
                .then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }
}
