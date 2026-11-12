package io.floci.aws.services.xray;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lambda functions with active tracing, on real containers: each sampled invocation records the Lambda service's and
 * the function's segments, the runtime receives the trace header, the function's own subsegment sent to the X-Ray
 * daemon joins the trace, and a failing function faults its trace with the error as the cause.
 */
@QuarkusTest
@TestProfile(XRayLambdaDockerIntegrationTest.DaemonProfile.class)
@Tag("docker")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class XRayLambdaDockerIntegrationTest {

    /** Runs the X-Ray daemon on its own port so the traced function can reach it. */
    public static final class DaemonProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.xray.daemon-port", "42719");
        }
    }

    private static final String ACCOUNT = "000000000000";
    private static final String TRACED = """
            const dgram = require("dgram");
            exports.handler = async () => {
              const header = process.env._X_AMZN_TRACE_ID || "";
              const fields = Object.fromEntries(header.split(";").map((p) => p.split("=")));
              const [host, port] = process.env.AWS_XRAY_DAEMON_ADDRESS.split(":");
              const now = Date.now() / 1000;
              const segment = JSON.stringify({ type: "subsegment", name: "custom-work",
                id: require("crypto").randomBytes(8).toString("hex"), trace_id: fields.Root,
                parent_id: fields.Parent, start_time: now - 0.01, end_time: now });
              const socket = dgram.createSocket("udp4");
              await new Promise((resolve, reject) => socket.send(
                '{"format": "json", "version": 1}\\n' + segment, Number(port), host,
                (error) => (socket.close(), error ? reject(error) : resolve())));
              return { root: fields.Root, sampled: fields.Sampled };
            };
            """;
    private static final String FAILING = """
            exports.handler = async () => { const e = new Error("card declined"); e.name = "PaymentError"; throw e; };
            """;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String zip(String script) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(script.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static void function(String name, String script) throws Exception {
        given().contentType("application/json").body("{\"FunctionName\":\"" + name + "\",\"Runtime\":\"nodejs20.x\","
                        + "\"Handler\":\"index.handler\",\"Role\":\"arn:aws:iam::" + ACCOUNT + ":role/lambda-role\","
                        + "\"Timeout\":30,\"TracingConfig\":{\"Mode\":\"Active\"},\"Code\":{\"ZipFile\":\"" + zip(script)
                        + "\"}}").post("/2015-03-31/functions").then().statusCode(201);
    }

    private static ValidatableResponse xray(String path, String body) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20260101/us-east-1/xray/aws4_request").contentType("application/json").body(body).post(path)
                .then();
    }

    @Test
    void tracedInvocationsRecordTheirSegments() throws Exception {
        function("xray-traced", TRACED);
        function("xray-failing", FAILING);
        try {
            String root = given().body("{}").post("/2015-03-31/functions/xray-traced/invocations").then()
                    .statusCode(200).body("sampled", equalTo("1")).extract().path("root");
            given().body("{}").post("/2015-03-31/functions/xray-failing/invocations").then().statusCode(200)
                    .header("X-Amz-Function-Error", "Unhandled");

            double now = System.currentTimeMillis() / 1000.0;
            String window = "\"StartTime\":" + (now - 600) + ",\"EndTime\":" + (now + 60);
            List<String> traced = xray("/TraceSummaries", "{" + window
                    + ",\"FilterExpression\":\"service(\\\"xray-traced\\\")\"}").statusCode(200).extract()
                    .path("TraceSummaries.Id");
            assertEquals(List.of(root), traced);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> xray("/Traces", "{\"TraceIds\":[\"" + root
                    + "\"]}").statusCode(200).body("Traces[0].Segments.Document", hasItem(containsString(
                    "custom-work"))));
            xray("/Traces", "{\"TraceIds\":[\"" + root + "\"]}")
                    .body("Traces[0].Segments.Document", hasItem(containsString("\"origin\":\"AWS::Lambda\"")))
                    .body("Traces[0].Segments.Document", hasItem(containsString("\"Invocation\"")));
            xray("/TraceSummaries", "{" + window + ",\"FilterExpression\":\"service(\\\"xray-failing\\\") { fault }\"}")
                    .statusCode(200).body("TraceSummaries[0].HasFault", equalTo(true))
                    .body("TraceSummaries[0].FaultRootCauses.Services.flatten().EntityPath.flatten().Exceptions"
                            + ".flatten().Name", hasItem("PaymentError"));
            List<String> names = xray("/ServiceGraph", "{" + window + "}").extract().path("Services.Name");
            assertTrue(names.contains("xray-traced"), names.toString());
            assertFalse(names.isEmpty());
        } finally {
            given().delete("/2015-03-31/functions/xray-traced").then().statusCode(204);
            given().delete("/2015-03-31/functions/xray-failing").then().statusCode(204);
        }
    }
}
