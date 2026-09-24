package io.github.hectorvent.floci.services.bedrockagentcore;

import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreSandboxRuntime.ExecResult;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreSandboxRuntime.Sandbox;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * HTTP-level coverage of the AgentCore data plane the Alchemy bindings call: memory actors,
 * sessions, long-term records and extraction jobs, and the code interpreter and browser session
 * operations. The sandbox runtime and browser driver are mocked so the wire shapes can be checked
 * without Docker; their container behaviour is covered separately.
 */
@QuarkusTest
class BedrockAgentCoreDataPlaneIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    @InjectMock
    BedrockAgentCoreSandboxRuntime runtime;

    @InjectMock
    BedrockAgentCoreBrowserDriver driver;

    @BeforeEach
    void stubRuntime() {
        when(runtime.launch(any())).thenAnswer(call -> new Sandbox("c-" + UUID.randomUUID(), "localhost", 40001));
        when(runtime.isLive(anyString())).thenReturn(true);
        when(runtime.isTracked(anyString())).thenReturn(true);
        when(driver.awaitBrowserEndpoint(anyString(), anyInt(), any(Duration.class)))
                .thenReturn("ws://localhost:40001/devtools/browser/x");
    }

    private static String createMemory(String name) {
        return given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + SUFFIX + "\",\"eventExpiryDuration\":7}")
                .when().post("/memories/create")
                .then().statusCode(202)
                .extract().path("memory.id");
    }

    private static void createEvent(String memoryId, String actorId, String sessionId) {
        given().contentType(ContentType.JSON)
                .body("{\"actorId\":\"" + actorId + "\",\"sessionId\":\"" + sessionId + "\","
                        + "\"eventTimestamp\":1789300800,\"payload\":[]}")
                .when().post("/memories/" + memoryId + "/events")
                .then().statusCode(201);
    }

    @Test
    void listActorsAndSessionsFollowRecordedEvents() {
        String memoryId = createMemory("dpActors");
        createEvent(memoryId, "actor-list", "session-list");
        createEvent(memoryId, "actor-3", "session-3");

        given().contentType(ContentType.JSON).body("{}")
                .when().post("/memories/" + memoryId + "/actors")
                .then().statusCode(200)
                .body("actorSummaries.actorId", equalTo(List.of("actor-3", "actor-list")));

        given().contentType(ContentType.JSON).body("{}")
                .when().post("/memories/" + memoryId + "/actor/actor-3/sessions")
                .then().statusCode(200)
                .body("sessionSummaries", hasSize(1))
                .body("sessionSummaries[0].sessionId", equalTo("session-3"))
                .body("sessionSummaries[0].actorId", equalTo("actor-3"))
                .body("sessionSummaries[0].createdAt", notNullValue());
    }

    @Test
    void memoryRecordRoundTripMatchesTheModeledRoutes() {
        String memoryId = createMemory("dpRecords");
        String body = "{\"records\":["
                + "{\"requestIdentifier\":\"rec-1\",\"namespaces\":[\"facts/batch-actor\"],"
                + "\"content\":{\"text\":\"The user's favorite color is teal.\"},\"timestamp\":1789300800},"
                + "{\"requestIdentifier\":\"rec-2\",\"namespaces\":[\"facts/batch-actor\"],"
                + "\"content\":{\"text\":\"The user prefers window seats.\"},\"timestamp\":1789300801}]}";
        Response created = given().contentType(ContentType.JSON).body(body)
                .when().post("/memories/" + memoryId + "/memoryRecords/batchCreate");
        created.then().statusCode(201).body("successfulRecords", hasSize(2)).body("failedRecords", hasSize(0));
        String first = created.path("successfulRecords[0].memoryRecordId");
        String second = created.path("successfulRecords[1].memoryRecordId");

        given().when().get("/memories/" + memoryId + "/memoryRecord/" + first)
                .then().statusCode(200)
                .body("memoryRecord.memoryRecordId", equalTo(first))
                .body("memoryRecord.namespaces[0]", equalTo("facts/batch-actor"));

        given().contentType(ContentType.JSON)
                .body("{\"records\":[{\"memoryRecordId\":\"" + first + "\",\"timestamp\":1789300900,"
                        + "\"content\":{\"text\":\"The user's favorite color is green.\"}}]}")
                .when().post("/memories/" + memoryId + "/memoryRecords/batchUpdate")
                .then().statusCode(200).body("successfulRecords", hasSize(1));

        given().contentType(ContentType.JSON)
                .body("{\"namespace\":\"facts/batch-actor\",\"searchCriteria\":{\"searchQuery\":\"favorite color\","
                        + "\"topK\":3}}")
                .when().post("/memories/" + memoryId + "/retrieve")
                .then().statusCode(200)
                .body("memoryRecordSummaries", hasSize(1))
                .body("memoryRecordSummaries[0].memoryRecordId", equalTo(first))
                .body("memoryRecordSummaries[0].score", notNullValue());

        given().contentType(ContentType.JSON).body("{\"namespace\":\"facts/batch-actor\"}")
                .when().post("/memories/" + memoryId + "/memoryRecords")
                .then().statusCode(200).body("memoryRecordSummaries", hasSize(2));

        given().when().delete("/memories/" + memoryId + "/memoryRecords/" + first)
                .then().statusCode(200).body("memoryRecordId", equalTo(first));
        given().when().get("/memories/" + memoryId + "/memoryRecord/" + first)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        given().contentType(ContentType.JSON).body("{\"records\":[{\"memoryRecordId\":\"" + second + "\"}]}")
                .when().post("/memories/" + memoryId + "/memoryRecords/batchDelete")
                .then().statusCode(200).body("successfulRecords", hasSize(1));
    }

    @Test
    void extractionJobsListEmptyAndStartIsNotFound() {
        String memoryId = createMemory("dpJobs");
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/memories/" + memoryId + "/extractionJobs")
                .then().statusCode(200).body("jobs", hasSize(0));
        given().contentType(ContentType.JSON).body("{\"extractionJob\":{\"jobId\":\"job-1\"}}")
                .when().post("/memories/" + memoryId + "/extractionJobs/start")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void codeInterpreterSessionRunsCodeAndStreamsTheResult() {
        String interpreterId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "dpInterp" + SUFFIX, "networkConfiguration", Map.of("networkMode", "SANDBOX")))
                .when().put("/code-interpreters")
                .then().statusCode(202).extract().path("codeInterpreterId");

        String sessionId = given().contentType(ContentType.JSON).body("{\"sessionTimeoutSeconds\":300}")
                .when().put("/code-interpreters/" + interpreterId + "/sessions/start")
                .then().statusCode(200)
                .body("codeInterpreterIdentifier", equalTo(interpreterId))
                .body("createdAt", notNullValue())
                .extract().path("sessionId");

        when(runtime.exec(anyString(), eq(List.of("python3", "-c", "print(21 * 2)")), anyString(), anyInt()))
                .thenReturn(new ExecResult("42\n", "", 0, 10, false));
        Response invoked = given().contentType(ContentType.JSON)
                .header("x-amzn-code-interpreter-session-id", sessionId)
                .body("{\"name\":\"executeCode\",\"arguments\":{\"language\":\"python\",\"code\":\"print(21 * 2)\"}}")
                .when().post("/code-interpreters/" + interpreterId + "/tools/invoke");
        invoked.then().statusCode(200)
                .header("Content-Type", "application/vnd.amazon.eventstream")
                .header("x-amzn-code-interpreter-session-id", sessionId);
        String frame = new String(invoked.asByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(frame.contains(":event-type"), frame);
        assertTrue(frame.contains("result"), frame);
        assertTrue(frame.contains("\"stdout\":\"42\\n\""), frame);

        given().queryParam("sessionId", sessionId)
                .when().get("/code-interpreters/" + interpreterId + "/sessions/get")
                .then().statusCode(200).body("status", equalTo("READY"));
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/code-interpreters/" + interpreterId + "/sessions/list")
                .then().statusCode(200).body("items.size()", greaterThanOrEqualTo(1));

        given().queryParam("sessionId", sessionId)
                .when().put("/code-interpreters/" + interpreterId + "/sessions/stop")
                .then().statusCode(200).body("sessionId", equalTo(sessionId)).body("lastUpdatedAt", notNullValue());
        given().queryParam("sessionId", sessionId)
                .when().put("/code-interpreters/" + interpreterId + "/sessions/stop")
                .then().statusCode(409).body("__type", equalTo("ConflictException"));
    }

    @Test
    void browserSessionScreenshotAndStop() {
        String browserId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "dpBrowser" + SUFFIX, "networkConfiguration", Map.of("networkMode", "PUBLIC")))
                .when().put("/browsers")
                .then().statusCode(202).extract().path("browserId");

        String sessionId = given().contentType(ContentType.JSON).body("{}")
                .when().put("/browsers/" + browserId + "/sessions/start")
                .then().statusCode(200)
                .body("streams.automationStream.streamStatus", equalTo("ENABLED"))
                .extract().path("sessionId");

        when(driver.screenshot("localhost", 40001)).thenReturn("iVBORw0KGgo=");
        given().contentType(ContentType.JSON).header("x-amzn-browser-session-id", sessionId)
                .body("{\"action\":{\"screenshot\":{}}}")
                .when().post("/browsers/" + browserId + "/sessions/invoke")
                .then().statusCode(200)
                .header("x-amzn-browser-session-id", sessionId)
                .body("result.screenshot.status", equalTo("SUCCESS"))
                .body("result.screenshot.data", equalTo("iVBORw0KGgo="));

        given().queryParam("sessionId", sessionId)
                .when().get("/browsers/" + browserId + "/sessions/get")
                .then().statusCode(200).body("status", equalTo("READY"));
        String stopped = given().queryParam("sessionId", sessionId)
                .when().put("/browsers/" + browserId + "/sessions/stop")
                .then().statusCode(200).extract().path("sessionId");
        assertEquals(sessionId, stopped);
    }

    @Test
    void sessionOnUnknownInterpreterIsNotFound() {
        given().contentType(ContentType.JSON).body("{}")
                .when().put("/code-interpreters/nosuch-ABCDEFGHIJ/sessions/start")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }
}
