package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeListRuleNamesByTargetIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String TARGET =
            "arn:aws:lambda:us-east-1:000000000000:function:list-by-target-fn";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void putRuleAndTargetThenListByArn() {
        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.PutRule")
            .body("""
                {
                    "Name": "list-by-target-rule",
                    "EventPattern": "{\\"source\\":[\\"alchemy.test\\"]}",
                    "State": "ENABLED"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.PutTargets")
            .body("""
                {
                    "Rule": "list-by-target-rule",
                    "Targets": [{"Id": "fn", "Arn": "%s"}]
                }
                """.formatted(TARGET))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FailedEntryCount", equalTo(0));

        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.ListRuleNamesByTarget")
            .body("{\"TargetArn\":\"%s\"}".formatted(TARGET))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RuleNames", hasItem("list-by-target-rule"));

        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.ListRuleNamesByTarget")
            .body("{\"TargetArn\":\"%s:$LATEST\"}".formatted(TARGET))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RuleNames", hasItem("list-by-target-rule"));
    }

    @Test
    @Order(2)
    void unknownTargetReturnsEmptyList() {
        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.ListRuleNamesByTarget")
            .body("{\"TargetArn\":\"arn:aws:lambda:us-east-1:000000000000:function:missing\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RuleNames", empty());
    }
}
