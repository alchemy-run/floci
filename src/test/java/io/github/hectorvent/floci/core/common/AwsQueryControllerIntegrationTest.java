package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class AwsQueryControllerIntegrationTest {

    @Test
    void missingActionParameterReturns400MissingAction() {
        given()
            .contentType("application/x-www-form-urlencoded")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .contentType("application/xml")
            .body("ErrorResponse.Error.Code", equalTo("MissingAction"))
            .body("ErrorResponse.Error.Message", equalTo("The request must contain the parameter Action"));
    }

    @Test
    void autoScalingActionsFallbackWithoutAuthorizationHeader() {
        // These actions used to miss AUTOSCALING_ACTIONS and fall through to SQS.
        for (String action : new String[] {
                "ExecutePolicy",
                "SetInstanceHealth",
                "SetInstanceProtection",
                "EnterStandby",
                "ExitStandby",
                "CancelInstanceRefresh",
                "RollbackInstanceRefresh",
                "PutScheduledUpdateGroupAction",
                "DescribeScheduledActions",
                "DeleteScheduledAction"
        }) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("AutoScalingGroupName", "missing-asg")
                .formParam("InstanceId", "i-00000000000000000")
                .formParam("InstanceIds.member.1", "i-00000000000000000")
                .formParam("HealthStatus", "Unhealthy")
                .formParam("PolicyName", "missing-policy")
                .formParam("ScheduledActionName", "missing-action")
            .when()
                .post("/")
            .then()
                .contentType("application/xml")
                .body(not(containsString("not supported by SQS")))
                .body(containsString("autoscaling.amazonaws.com"));
        }
    }

    @Test
    void ec2ActionFallbackWithoutAuthorizationHeader() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeRegions")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeRegionsResponse.regionInfo.item.size()", greaterThan(0));
    }
}
