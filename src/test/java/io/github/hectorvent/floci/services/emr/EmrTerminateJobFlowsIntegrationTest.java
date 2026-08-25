package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * TerminateJobFlows maps a missing job-flow id to ValidationException
 * ("Specified job flow ID not valid."), which distilled synthesizes as JobFlowNotFound.
 */
@QuarkusTest
class EmrTerminateJobFlowsIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "ElasticMapReduce.";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void terminateUnknownJobFlowIdIsValidationException() {
        given().contentType(CT).header("X-Amz-Target", PREFIX + "TerminateJobFlows")
                .body("{\"JobFlowIds\":[\"j-1K48XAOQ4XHCB\"]}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("Specified job flow ID not valid."));
    }
}
