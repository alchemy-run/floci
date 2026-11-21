package io.floci.aws.services.mediaconvert;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * MediaConvert: queues, presets and job templates with tags; the input policy; jobs checked for their role,
 * waiting on a paused queue, canceled, and failing on a missing input; jobs queries and probes.
 */
@QuarkusTest
class MediaConvertIntegrationTest {

    static final String PREFIX = "/2017-08-29";
    static final String PRESET = "{\"containerSettings\":{\"container\":\"MP4\",\"mp4Settings\":{}},"
            + "\"videoDescription\":{\"width\":320,\"height\":180,\"codecSettings\":{\"codec\":\"H_264\","
            + "\"h264Settings\":{\"rateControlMode\":\"QVBR\",\"maxBitrate\":1000000}}},\"audioDescriptions\":"
            + "[{\"codecSettings\":{\"codec\":\"AAC\",\"aacSettings\":{\"bitrate\":64000,\"codingMode\":"
            + "\"CODING_MODE_2_0\",\"sampleRate\":48000}}}]}";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static RequestSpecification mc() {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/mediaconvert/aws4_request")
                .contentType("application/json");
    }

    static void role(String name) {
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/iam/"
                        + "aws4_request").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", name)
                .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":"
                        + "\"Allow\",\"Principal\":{\"Service\":\"mediaconvert.amazonaws.com\"},\"Action\":"
                        + "\"sts:AssumeRole\"}]}").post("/");
    }

    static String job(String queue, String input, String destination) {
        return mc().body("{\"role\":\"arn:aws:iam::000000000000:role/mediaconvert\",\"queue\":\"" + queue + "\","
                        + "\"userMetadata\":{\"k\":\"v\"},\"settings\":{\"inputs\":[{\"fileInput\":\"" + input
                        + "\"}],\"outputGroups\":[{\"name\":\"File Group\",\"outputGroupSettings\":{\"type\":"
                        + "\"FILE_GROUP_SETTINGS\",\"fileGroupSettings\":{\"destination\":\"" + destination
                        + "\"}},\"outputs\":[" + PRESET.replaceFirst("\\{", "{\"nameModifier\":\"_small\",")
                        + "]}]}}").post(PREFIX + "/jobs").then().log().ifValidationFails().statusCode(201)
                .body("job.status", equalTo("SUBMITTED")).extract().path("job.id");
    }

    static ValidatableResponse getJob(String id) {
        return mc().get(PREFIX + "/jobs/" + id).then().log().ifValidationFails();
    }

    @Test
    void queuesPresetsAndTemplates() {
        mc().get(PREFIX + "/queues/Default").then().statusCode(200).body("queue.type", equalTo("SYSTEM"))
                .body("queue.pricingPlan", equalTo("ON_DEMAND"));
        String queue = mc().body("{\"name\":\"q1\",\"description\":\"first\",\"tags\":{\"team\":\"video\"}}")
                .post(PREFIX + "/queues").then().statusCode(201).body("queue.status", equalTo("ACTIVE"))
                .body("queue.arn", containsString(":queues/q1")).extract().path("queue.arn");
        mc().body("{\"name\":\"q1\"}").post(PREFIX + "/queues").then().statusCode(409);
        mc().body("{\"description\":\"second\",\"status\":\"PAUSED\"}").put(PREFIX + "/queues/q1").then()
                .statusCode(200).body("queue.status", equalTo("PAUSED")).body("queue.description", equalTo("second"));
        mc().get(PREFIX + "/tags/" + queue).then().statusCode(200).body("resourceTags.tags.team", equalTo("video"));
        mc().body("{\"arn\":\"" + queue + "\",\"tags\":{\"extra\":\"yes\"}}").post(PREFIX + "/tags").then()
                .statusCode(200);
        mc().body("{\"tagKeys\":[\"team\"]}").put(PREFIX + "/tags/" + queue).then().statusCode(200);
        mc().get(PREFIX + "/tags/" + queue).then().body("resourceTags.tags.extra", equalTo("yes"))
                .body("resourceTags.tags.team", nullValue());
        mc().get(PREFIX + "/queues").then().statusCode(200).body("queues.name", hasItem("q1"));
        mc().delete(PREFIX + "/queues/Default").then().statusCode(400);
        mc().delete(PREFIX + "/queues/q1").then().statusCode(202);
        mc().get(PREFIX + "/queues/q1").then().statusCode(404).header("x-amzn-ErrorType",
                containsString("NotFoundException"));

        mc().body("{\"name\":\"small\",\"category\":\"web\",\"settings\":" + PRESET + "}").post(PREFIX + "/presets")
                .then().statusCode(201).body("preset.type", equalTo("CUSTOM"))
                .body("preset.settings.videoDescription.width", equalTo(320));
        mc().body("{\"name\":\"bad\",\"settings\":{\"videoDescription\":{\"codecSettings\":{\"codec\":\"NOPE\"}}}}")
                .post(PREFIX + "/presets").then().statusCode(400);
        mc().get(PREFIX + "/presets?category=web").then().body("presets.name", hasItem("small"));
        mc().get(PREFIX + "/presets?listBy=SYSTEM").then().body("presets.type", hasItem("SYSTEM"));
        mc().body("{\"description\":\"tiny\"}").put(PREFIX + "/presets/small").then().statusCode(200)
                .body("preset.description", equalTo("tiny"));

        mc().body("{\"name\":\"t1\",\"priority\":10,\"settings\":{\"outputGroups\":[{\"name\":\"File Group\","
                        + "\"outputGroupSettings\":{\"type\":\"FILE_GROUP_SETTINGS\",\"fileGroupSettings\":{}},"
                        + "\"outputs\":[{\"preset\":\"small\"}]}]}}").post(PREFIX + "/jobTemplates").then()
                .statusCode(201).body("jobTemplate.priority", equalTo(10))
                .body("jobTemplate.queue", containsString(":queues/Default"));
        mc().body("{\"name\":\"t2\",\"settings\":{\"outputGroups\":[{\"name\":\"g\",\"outputGroupSettings\":"
                + "{\"type\":\"FILE_GROUP_SETTINGS\"},\"outputs\":[{\"preset\":\"missing\"}]}]}}")
                .post(PREFIX + "/jobTemplates").then().statusCode(404);
        mc().body("{\"priority\":-5}").put(PREFIX + "/jobTemplates/t1").then().statusCode(200)
                .body("jobTemplate.priority", equalTo(-5));
        mc().get(PREFIX + "/jobTemplates").then().body("jobTemplates.name", hasItem("t1"));
        mc().delete(PREFIX + "/jobTemplates/t1").then().statusCode(202);
        mc().delete(PREFIX + "/presets/small").then().statusCode(202);
        mc().get(PREFIX + "/presets/System-Generic_Audio_Mp3_Stereo_128kbps").then().statusCode(200)
                .body("preset.type", equalTo("SYSTEM"));
    }

    @Test
    void jobsCheckTheirRoleAndWaitOnTheirQueue() {
        role("mediaconvert");
        mc().body("{\"role\":\"arn:aws:iam::000000000000:role/missing\",\"settings\":{\"inputs\":[{\"fileInput\":"
                        + "\"s3://in/a.mp4\"}],\"outputGroups\":[]}}").post(PREFIX + "/jobs").then().statusCode(400)
                .header("x-amzn-ErrorType", containsString("BadRequestException"));
        mc().body("{\"name\":\"held\",\"status\":\"PAUSED\"}").post(PREFIX + "/queues").then().statusCode(201);
        String held = job("held", "s3://mediaconvert-missing/in.mp4", "s3://mediaconvert-out/");
        String canceled = job("held", "s3://mediaconvert-missing/other.mp4", "s3://mediaconvert-out/");
        getJob(held).body("job.status", equalTo("SUBMITTED")).body("job.queue", containsString(":queues/held"));
        mc().delete(PREFIX + "/jobs/" + canceled).then().statusCode(202);
        getJob(canceled).body("job.status", equalTo("CANCELED"));
        mc().delete(PREFIX + "/jobs/" + canceled).then().statusCode(409);

        mc().body("{\"status\":\"ACTIVE\"}").put(PREFIX + "/queues/held").then().statusCode(200);
        await().atMost(Duration.ofSeconds(20)).until(() -> "ERROR".equals(getJob(held).extract()
                .path("job.status")));
        getJob(held).body("job.errorCode", equalTo(1030)).body("job.errorMessage", containsString("in.mp4"))
                .body("job.timing.finishTime", not(equalTo(null)));
        mc().get(PREFIX + "/jobs?queue=held&status=ERROR").then().statusCode(200).body("jobs.id", hasItem(held));
        mc().get(PREFIX + "/search?inputFile=s3://mediaconvert-missing/in.mp4").then().statusCode(200)
                .body("jobs.id", hasItem(held));
        String query = mc().body("{\"filterList\":[{\"key\":\"status\",\"values\":[\"CANCELED\"]}]}")
                .post(PREFIX + "/jobsQueries").then().statusCode(201).extract().path("id");
        await().atMost(Duration.ofSeconds(5)).until(() -> "COMPLETE".equals(mc().get(PREFIX + "/jobsQueries/"
                + query).then().extract().path("status")));
        mc().get(PREFIX + "/jobsQueries/" + query).then().body("jobs.id", hasItem(canceled));
        mc().delete(PREFIX + "/queues/held").then().statusCode(202);
    }

    @Test
    void policiesProbesAndVersions() {
        mc().get(PREFIX + "/policy").then().statusCode(200).body("policy.s3Inputs", equalTo("ALLOWED"));
        mc().body("{\"policy\":{\"httpInputs\":\"DISALLOWED\"}}").put(PREFIX + "/policy").then().statusCode(200)
                .body("policy.httpInputs", equalTo("DISALLOWED"));
        role("mediaconvert");
        mc().body("{\"role\":\"arn:aws:iam::000000000000:role/mediaconvert\",\"settings\":{\"inputs\":[{"
                        + "\"fileInput\":\"http://example.com/in.mp4\"}],\"outputGroups\":[{\"name\":\"g\","
                        + "\"outputGroupSettings\":{\"type\":\"FILE_GROUP_SETTINGS\",\"fileGroupSettings\":{"
                        + "\"destination\":\"s3://out/\"}},\"outputs\":[" + PRESET + "]}]}}").post(PREFIX + "/jobs")
                .then().statusCode(403);
        mc().delete(PREFIX + "/policy").then().statusCode(200);
        mc().body("{\"inputFiles\":[{\"fileUrl\":\"s3://mediaconvert-missing/in.mp4\"}]}").post(PREFIX + "/probe")
                .then().statusCode(404).header("x-amzn-ErrorType", containsString("NotFoundException"));
        mc().get(PREFIX + "/versions").then().statusCode(200).body("versions[0].version", not(equalTo(null)));
        mc().body("{}").post(PREFIX + "/endpoints").then().statusCode(200).body("endpoints[0].url",
                containsString("http"));
        mc().get(PREFIX + "/jobs/1234567890123-missing").then().statusCode(404);
    }
}
