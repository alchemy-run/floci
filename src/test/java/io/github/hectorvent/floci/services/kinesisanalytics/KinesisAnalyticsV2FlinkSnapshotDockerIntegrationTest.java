package io.github.hectorvent.floci.services.kinesisanalytics;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real-Docker regression for CreateApplicationSnapshot against a RUNNING Flink 1.20 job
 * (Flink's TopSpeedWindowing example). The savepoint used to fail because the savepoints volume
 * was root-owned while Flink runs as the unprivileged {@code flink} user, and because the
 * application reported RUNNING while its tasks were still deploying.
 *
 * <p>Set {@code FLOCI_TEST_FLINK_DOCKER=1}; {@code FLOCI_TEST_FLINK_JAR} may point at a local copy
 * of the example JAR, otherwise it is downloaded from Maven Central.
 */
@QuarkusTest
@TestProfile(KinesisAnalyticsV2FlinkSnapshotDockerIntegrationTest.RealFlinkProfile.class)
@Timeout(300)
@EnabledIfEnvironmentVariable(named = "FLOCI_TEST_FLINK_DOCKER", matches = "1")
class KinesisAnalyticsV2FlinkSnapshotDockerIntegrationTest {

    public static final class RealFlinkProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.kinesis-analytics.mock", "false");
        }
    }

    private static final String JAR_URL = "https://repo1.maven.org/maven2/org/apache/flink/flink-examples-streaming/"
            + "1.20.0/flink-examples-streaming-1.20.0-TopSpeedWindowing.jar";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String APP = "floci-flink-snapshot-docker-it";
    private static final String BUCKET = "floci-flink-snapshot-docker-it";

    @Test
    void snapshotOfARunningFlinkJobBecomesReady() throws Exception {
        RestAssuredJsonUtils.configureAwsContentTypes();
        given().when().put("/" + BUCKET).then().statusCode(200);
        given().contentType("application/java-archive").body(jar())
                .when().put("/" + BUCKET + "/job.jar").then().statusCode(200);

        String timestamp = kda("CreateApplication", """
                {"ApplicationName":"%s","RuntimeEnvironment":"FLINK-1_20",
                 "ServiceExecutionRole":"arn:aws:iam::000000000000:role/flink",
                 "ApplicationConfiguration":{
                   "ApplicationCodeConfiguration":{"CodeContentType":"ZIPFILE","CodeContent":{
                     "S3ContentLocation":{"BucketARN":"arn:aws:s3:::%s","FileKey":"job.jar"}}},
                   "ApplicationSnapshotConfiguration":{"SnapshotsEnabled":true}}}
                """.formatted(APP, BUCKET)).statusCode(200)
                .extract().jsonPath(new JsonPathConfig(JsonPathConfig.NumberReturnType.BIG_DECIMAL))
                .getString("ApplicationDetail.CreateTimestamp");
        try {
            kda("StartApplication", "{\"ApplicationName\":\"" + APP + "\"}").statusCode(200);
            await("application RUNNING", () -> kda("DescribeApplication", "{\"ApplicationName\":\"" + APP + "\"}")
                    .statusCode(200).extract().path("ApplicationDetail.ApplicationStatus"), "RUNNING");

            kda("CreateApplicationSnapshot", "{\"ApplicationName\":\"" + APP + "\",\"SnapshotName\":\"s1\"}")
                    .statusCode(200);
            String status = await("snapshot READY", () -> kda("DescribeApplicationSnapshot",
                    "{\"ApplicationName\":\"" + APP + "\",\"SnapshotName\":\"s1\"}").statusCode(200)
                    .extract().path("SnapshotDetails.SnapshotStatus"), "READY");
            assertEquals("READY", status);
        } finally {
            kda("DeleteApplication", "{\"ApplicationName\":\"" + APP + "\",\"CreateTimestamp\":" + timestamp + "}");
        }
    }

    private static ValidatableResponse kda(String action, String body) {
        return given().header("X-Amz-Target", "KinesisAnalytics_20180523." + action)
                .contentType(CONTENT_TYPE).body(body).when().post("/").then();
    }

    private static String await(String what, java.util.function.Supplier<String> probe, String wanted)
            throws InterruptedException {
        String last = null;
        for (int i = 0; i < 90; i++) {
            last = probe.get();
            if (wanted.equals(last) || "FAILED".equals(last)) {
                break;
            }
            Thread.sleep(2000);
        }
        if (!wanted.equals(last)) {
            fail("Timed out waiting for " + what + "; last status " + last);
        }
        return last;
    }

    private static byte[] jar() throws Exception {
        String local = System.getenv("FLOCI_TEST_FLINK_JAR");
        if (local != null && !local.isBlank()) {
            return Files.readAllBytes(Path.of(local));
        }
        HttpResponse<byte[]> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(JAR_URL)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, resp.statusCode(), "downloading " + JAR_URL);
        return resp.body();
    }
}
