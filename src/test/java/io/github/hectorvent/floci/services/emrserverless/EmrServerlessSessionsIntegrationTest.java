package io.github.hectorvent.floci.services.emrserverless;

import io.github.hectorvent.floci.testing.EmrServerlessRequests;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

/**
 * An application echoes its engine as AWS spells it, lists no interactive sessions, refuses a
 * StartSession its configuration does not allow, and denies GetResourceDashboard to every caller.
 */
@QuarkusTest
class EmrServerlessSessionsIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification emr() {
        return EmrServerlessRequests.emrServerless("us-east-1").urlEncodingEnabled(false);
    }

    private static String createApplication() {
        String applicationId = emr()
                .body("""
                        { "name": "sessions-%s", "releaseLabel": "emr-7.5.0", "type": "SPARK", "clientToken": "%s" }
                        """.formatted(UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID()))
                .post("/applications")
                .then().statusCode(200)
                .extract().path("applicationId");
        emr().get("/applications/" + applicationId).then().statusCode(200)
                .body("application.type", equalTo("Spark"));
        return applicationId;
    }

    @Test
    void anApplicationListsNoSessions() {
        String applicationId = createApplication();
        emr().get("/applications/" + applicationId + "/sessions").then().statusCode(200)
                .body("sessions", empty());
    }

    @Test
    void startSessionNeedsSessionsEnabled() {
        String applicationId = createApplication();
        emr().body("""
                        { "clientToken": "%s", "executionRoleArn": "arn:aws:iam::000000000000:role/emr-serverless-job" }
                        """.formatted(UUID.randomUUID()))
                .post("/applications/" + applicationId + "/sessions")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getResourceDashboardIsDeniedToEveryCaller() {
        String applicationId = createApplication();
        emr().get("/applications/" + applicationId + "/dashboard?resourceId=abc&resourceType=SESSION")
                .then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }
}
