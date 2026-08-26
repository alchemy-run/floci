package io.github.hectorvent.floci.services.applicationsignals;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Alchemy {@code test/AWS/ApplicationSignals/Bindings.test.ts}: discovery
 * reads return well-formed empty pages, the fixture SLO is listable and
 * readable, budget reports succeed, exclusion windows add/list/remove, and
 * instrumentation-configuration status is an empty event history.
 */
@QuarkusTest
class ApplicationSignalsBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final Map<String, String> PROBE = Map.of(
            "Type", "Service",
            "Name", "alchemy-test-appsignals-bindings-probe",
            "Environment", "alchemy-test-env");
    private static final String SLI = """
            "SliConfig": {
              "SliMetricConfig": {
                "MetricDataQueries": [
                  {
                    "Id": "m1",
                    "MetricStat": {
                      "Metric": {
                        "Namespace": "Alchemy/Test",
                        "MetricName": "AppSignalsBindingsLatency",
                        "Dimensions": [{"Name": "Fixture", "Value": "bindings"}]
                      },
                      "Period": 60,
                      "Stat": "Average"
                    },
                    "ReturnData": true
                  }
                ]
              },
              "MetricThreshold": 2000,
              "ComparisonOperator": "LessThanOrEqualTo"
            }
            """;
    private static final String GOAL = """
            "Goal": {
              "Interval": {"RollingInterval": {"DurationUnit": "DAY", "Duration": 7}},
              "AttainmentGoal": 99,
              "WarningThreshold": 50
            }
            """;
    private static final String IC_BODY = """
            {
              "InstrumentationType":"PROBE",
              "Service":"alchemy-test-appsignals-bindings-ic",
              "Environment":"alchemy-test-env",
              "SignalType":"SNAPSHOT",
              "Location":{
                "CodeLocation":{
                  "Language":"Python",
                  "CodeUnit":"app.main",
                  "MethodName":"handler",
                  "FilePath":"app/main.py",
                  "LineNumber":10
                }
              },
              "CaptureConfiguration":{
                "CodeCapture":{
                  "CaptureLocals":["x"],
                  "CaptureLimits":{"MaxHits":1}
                }
              }
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listServicesReturnsEmptySummaries() {
        long end = Instant.now().getEpochSecond();
        long start = end - 3600;
        given()
                .header("Authorization", auth("000000000921", EAST))
                .queryParam("StartTime", start)
                .queryParam("EndTime", end)
                .when()
                .get("/services")
                .then()
                .statusCode(200)
                .body("ServiceSummaries", hasSize(0))
                .body("StartTime", notNullValue())
                .body("EndTime", notNullValue());
    }

    @Test
    void getServiceForUnknownKeyAttributesReturnsEmptyService() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000922", EAST))
                .queryParam("StartTime", Instant.now().getEpochSecond() - 3600)
                .queryParam("EndTime", Instant.now().getEpochSecond())
                .body("""
                        {
                          "KeyAttributes": {
                            "Type":"%s",
                            "Name":"%s",
                            "Environment":"%s"
                          }
                        }
                        """.formatted(PROBE.get("Type"), PROBE.get("Name"), PROBE.get("Environment")))
                .when()
                .post("/service")
                .then()
                .statusCode(200)
                .body("Service.KeyAttributes", equalTo(null));
    }

    @Test
    void listDependenciesDependentsAndOperationsReturnEmptyPages() {
        String authorization = auth("000000000923", EAST);
        String body = """
                {
                  "KeyAttributes": {
                    "Type":"%s",
                    "Name":"%s",
                    "Environment":"%s"
                  }
                }
                """.formatted(PROBE.get("Type"), PROBE.get("Name"), PROBE.get("Environment"));
        long end = Instant.now().getEpochSecond();
        long start = end - 3600;
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .queryParam("StartTime", start)
                .queryParam("EndTime", end)
                .body(body)
                .when()
                .post("/service-dependencies")
                .then()
                .statusCode(200)
                .body("ServiceDependencies", hasSize(0));
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .queryParam("StartTime", start)
                .queryParam("EndTime", end)
                .body(body)
                .when()
                .post("/service-dependents")
                .then()
                .statusCode(200)
                .body("ServiceDependents", hasSize(0));
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .queryParam("StartTime", start)
                .queryParam("EndTime", end)
                .body(body)
                .when()
                .post("/service-operations")
                .then()
                .statusCode(200)
                .body("ServiceOperations", hasSize(0));
    }

    @Test
    void listServiceStatesAndEntityEventsAndAuditFindingsReturnEmptyPages() {
        String authorization = auth("000000000924", EAST);
        long end = Instant.now().getEpochSecond();
        long start = end - 3600;
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StartTime\":%d,\"EndTime\":%d}".formatted(start, end))
                .when()
                .post("/service/states")
                .then()
                .statusCode(200)
                .body("ServiceStates", hasSize(0));
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "StartTime":%d,
                          "EndTime":%d,
                          "Entity": {
                            "Type":"%s",
                            "Name":"%s",
                            "Environment":"%s"
                          }
                        }
                        """.formatted(start, end, PROBE.get("Type"), PROBE.get("Name"), PROBE.get("Environment")))
                .when()
                .post("/events")
                .then()
                .statusCode(200)
                .body("ChangeEvents", hasSize(0));
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .queryParam("StartTime", start)
                .queryParam("EndTime", end)
                .body("""
                        {
                          "AuditTargets": [
                            {
                              "Type":"service",
                              "Data": {
                                "Service": {
                                  "Type":"%s",
                                  "Name":"%s",
                                  "Environment":"%s"
                                }
                              }
                            }
                          ]
                        }
                        """.formatted(PROBE.get("Type"), PROBE.get("Name"), PROBE.get("Environment")))
                .when()
                .post("/auditFindings")
                .then()
                .statusCode(200)
                .body("AuditFindings", hasSize(0));
    }

    @Test
    void listAndGetSloAndBudgetReportForFixtureSlo() {
        String authorization = auth("000000000925", EAST);
        String name = "BindingsSlo";
        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name": "%s",
                          "Description": "alchemy application-signals bindings fixture slo",
                          %s,
                          %s
                        }
                        """.formatted(name, SLI, GOAL))
                .when()
                .post("/slo")
                .then()
                .statusCode(200)
                .body("Slo.Name", equalTo(name))
                .body("Slo.Goal.AttainmentGoal", equalTo(99))
                .extract()
                .path("Slo.Arn");
        assertTrue(arn.contains(":slo/"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/slos")
                .then()
                .statusCode(200)
                .body("SloSummaries.size()", greaterThanOrEqualTo(1))
                .body("SloSummaries.Name", hasItem(name));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/slo/" + encode(arn))
                .then()
                .statusCode(200)
                .body("Slo.Arn", equalTo(arn))
                .body("Slo.Goal.AttainmentGoal", equalTo(99));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Timestamp\":%d,\"SloIds\":[\"%s\"]}"
                        .formatted(Instant.now().getEpochSecond(), arn))
                .when()
                .post("/budget-report")
                .then()
                .statusCode(200)
                .body("Errors", hasSize(0))
                .body("Reports", hasSize(1))
                .body("Reports[0].BudgetStatus", notNullValue())
                .body("Reports[0].Arn", equalTo(arn));
    }

    @Test
    void exclusionWindowsAddObserveAndRemove() {
        String authorization = auth("000000000926", EAST);
        String name = "BindingsSloWindows";
        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name": "%s",
                          %s,
                          %s
                        }
                        """.formatted(name, SLI, GOAL))
                .when()
                .post("/slo")
                .then()
                .statusCode(200)
                .extract()
                .path("Slo.Arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/slo/" + encode(arn) + "/exclusion-windows")
                .then()
                .statusCode(200)
                .body("ExclusionWindows", hasSize(0));

        long start = Instant.now().getEpochSecond() + 3600;
        String window = """
                {
                  "StartTime": %d,
                  "Window": {"DurationUnit": "HOUR", "Duration": 1},
                  "Reason": "alchemy bindings test"
                }
                """.formatted(start);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"SloIds\":[\"%s\"],\"AddExclusionWindows\":[%s]}".formatted(arn, window))
                .when()
                .patch("/exclusion-windows")
                .then()
                .statusCode(200)
                .body("Errors", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/slo/" + encode(arn) + "/exclusion-windows")
                .then()
                .statusCode(200)
                .body("ExclusionWindows", hasSize(1));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"SloIds\":[\"%s\"],\"RemoveExclusionWindows\":[%s]}".formatted(arn, window))
                .when()
                .patch("/exclusion-windows")
                .then()
                .statusCode(200)
                .body("Errors", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/slo/" + encode(arn) + "/exclusion-windows")
                .then()
                .statusCode(200)
                .body("ExclusionWindows", hasSize(0));
    }

    @Test
    void getInstrumentationConfigurationStatusReturnsEmptyEvents() {
        String authorization = auth("000000000927", EAST);
        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(IC_BODY)
                .when()
                .post("/create-instrumentation-configuration")
                .then()
                .statusCode(200)
                .extract()
                .response();
        String hash = created.path("LocationHash");
        assertEquals(16, hash.length());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "InstrumentationType":"PROBE",
                          "Service":"alchemy-test-appsignals-bindings-ic",
                          "Environment":"alchemy-test-env",
                          "SignalType":"SNAPSHOT",
                          "LocationIdentifier":{"LocationHash":"%s"}
                        }
                        """.formatted(hash))
                .when()
                .post("/get-instrumentation-configuration-status")
                .then()
                .statusCode(200)
                .body("Events", hasSize(0))
                .body("Status", equalTo("READY"))
                .body("Location.CodeLocation.FilePath", equalTo("app/main.py"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/application-signals/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
