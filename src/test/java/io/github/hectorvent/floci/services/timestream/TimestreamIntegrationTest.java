package io.github.hectorvent.floci.services.timestream;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * Timestream for LiveAnalytics answers like an account that was never onboarded: every
 * timestream-write and timestream-query operation, starting with endpoint discovery, is an
 * AccessDeniedException carrying the closure message SDKs match on.
 */
@QuarkusTest
class TimestreamIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TARGET = "Timestream_20181101.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body, String endpointHost) {
        return given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Host", endpointHost)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/timestream/"
                        + "aws4_request, SignedHeaders=host;x-amz-date;x-amz-target, Signature=dummy")
                .body(body)
                .when().post("/")
                .then();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ingest.timestream.us-east-1.amazonaws.com", "query.timestream.us-east-1.amazonaws.com"})
    void describeEndpointsReportsTheAccountIsNotOnboarded(String endpointHost) {
        call("DescribeEndpoints", "{}", endpointHost)
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", startsWith("Only existing Timestream for LiveAnalytics customers"))
                .body("message", equalTo(TimestreamJsonHandler.NOT_ONBOARDED_MESSAGE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateDatabase", "ListDatabases", "CreateTable", "WriteRecords", "TagResource"})
    void writeOperationsReportTheAccountIsNotOnboarded(String action) {
        call(action, "{\"DatabaseName\":\"metrics\"}", "ingest-cell1.timestream.us-east-1.amazonaws.com")
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", equalTo(TimestreamJsonHandler.NOT_ONBOARDED_MESSAGE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Query", "ListScheduledQueries", "CreateScheduledQuery", "DescribeAccountSettings",
            "PrepareQuery"})
    void queryOperationsReportTheAccountIsNotOnboarded(String action) {
        call(action, "{\"QueryString\":\"SELECT 1\"}", "query-cell1.timestream.us-east-1.amazonaws.com")
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", equalTo(TimestreamJsonHandler.NOT_ONBOARDED_MESSAGE));
    }

    @Test
    void anOperationNeitherApiDefinesIsUnknown() {
        call("CreateWidget", "{}", "ingest.timestream.us-east-1.amazonaws.com")
                .statusCode(404)
                .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void timestreamForInfluxDbIsStillRoutedSeparately() {
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonTimestreamInfluxDB.ListDbInstances")
                .body("{}")
                .when().post("/")
                .then()
                .statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonTimestreamInfluxDB.GetDbInstance")
                .body("{\"identifier\":\"!bad\"}")
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }
}
