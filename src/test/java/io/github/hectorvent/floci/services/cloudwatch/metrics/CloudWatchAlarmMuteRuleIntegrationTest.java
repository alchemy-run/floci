package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * Wire-protocol coverage for CloudWatch alarm mute rules (JSON 1.0 /
 * GraniteServiceVersion20100801), matching Alchemy {@code AlarmMuteRule.test.ts}.
 */
@QuarkusTest
class CloudWatchAlarmMuteRuleIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TARGET = "GraniteServiceVersion20100801";
    private static final String LIST_ACCOUNT = "000000000831";
    private static final String CRUD_ACCOUNT = "000000000832";
    private static final String REGION = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listAlarmMuteRulesReturnsEmptySummariesArray() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".ListAlarmMuteRules")
                .header("Authorization", auth(LIST_ACCOUNT))
                .body("{}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("AlarmMuteRuleSummaries", hasSize(0));
    }

    @Test
    void putGetListDeleteAlarmMuteRule() {
        String name = "NightlyMute";
        String authorization = auth(CRUD_ACCOUNT);
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".PutAlarmMuteRule")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name": "%s",
                          "Description": "Mute alarms overnight",
                          "Rule": {
                            "Schedule": {
                              "Expression": "0 2 * * SUN",
                              "Duration": "PT1H",
                              "Timezone": "UTC"
                            }
                          },
                          "MuteTargets": { "AlarmNames": ["WebServerCPUAlarm"] }
                        }
                        """.formatted(name))
                .when()
                .post("/")
                .then()
                .statusCode(200);

        String arn = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".GetAlarmMuteRule")
                .header("Authorization", authorization)
                .body("{\"AlarmMuteRuleName\":\"" + name + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name))
                .body("AlarmMuteRuleArn", containsString(":alarm-mute-rule:" + name))
                .body("MuteType", equalTo("RECURRING"))
                .body("Rule.Schedule.Duration", equalTo("PT1H"))
                .extract().path("AlarmMuteRuleArn");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".ListAlarmMuteRules")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("AlarmMuteRuleSummaries.AlarmMuteRuleArn", hasItem(containsString(":alarm-mute-rule:")))
                .body("AlarmMuteRuleSummaries.AlarmMuteRuleArn", hasItem(arn));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".DeleteAlarmMuteRule")
                .header("Authorization", authorization)
                .body("{\"AlarmMuteRuleName\":\"" + name + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".GetAlarmMuteRule")
                .header("Authorization", authorization)
                .body("{\"AlarmMuteRuleName\":\"" + name + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String account) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260101/" + REGION + "/monitoring/aws4_request";
    }
}
