package io.floci.aws.services.ivs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static io.floci.aws.services.ivs.IvsIntegrationTest.ivs;
import static io.floci.aws.services.ivs.IvsIntegrationTest.p384;
import static io.floci.aws.services.ivs.IvsIntegrationTest.publicKeyPem;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * IVS channels broadcast to for real: ffmpeg publishes a test pattern with the channel's stream key, the stream goes
 * live with its session, health and ingest configuration, a private channel's HLS plays with a signed playback
 * token until the viewer is revoked, and the recording lands in S3 in IVS's layout, with its events.
 */
@Tag("docker")
@QuarkusTest
@TestProfile(IvsDockerIntegrationTest.RealMediaProfile.class)
class IvsDockerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static class RealMediaProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ivs.mock", "false",
                    "floci.services.ivs.rtmp-port", "19350",
                    "floci.services.ivs.srt-port", "19400",
                    "floci.services.ivs.rtmps-port", "19370",
                    "floci.services.ivs.webrtc-port", "18189",
                    "floci.services.ivs.host-port-base", "18500",
                    "floci.services.ivs.host-port-max", "18599",
                    "floci.services.ivs.poll-millis", "500");
        }
    }

    @Inject
    DockerClient docker;

    @BeforeEach
    void requireDocker() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        boolean available;
        try {
            docker.pingCmd().exec();
            available = true;
        } catch (RuntimeException e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "Docker daemon must be available for IVS media tests");
    }

    @AfterEach
    void stopMedia() {
        try {
            docker.removeContainerCmd(IvsMediaServer.CONTAINER).withForce(true).exec();
        } catch (RuntimeException ignored) {
            // already gone
        }
    }

    private static RequestSpecification signed(String service) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/"
                + service + "/aws4_request");
    }

    private static JsonNode json(String target, String service, String body) throws Exception {
        String version = "sqs".equals(service) ? "1.0" : "1.1";
        String response = signed(service).header("X-Amz-Target", target).contentType("application/x-amz-json-"
                + version)
                .body(body).post("/").then().statusCode(200).extract().asString();
        return MAPPER.readTree(response.isEmpty() ? "{}" : response);
    }

    private static <T> T await(String what, int seconds, Supplier<T> attempt) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
        while (System.currentTimeMillis() < deadline) {
            T value = attempt.get();
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail(what + " did not happen within " + seconds + "s");
        return null;
    }

    /** Runs ffmpeg in the media server's container, publishing a test pattern for some seconds. */
    private int publish(String streamKey, int seconds, boolean wait) throws InterruptedException {
        ExecCreateCmdResponse exec = docker.execCreateCmd(IvsMediaServer.CONTAINER).withAttachStdout(true)
                .withAttachStderr(true).withCmd("ffmpeg", "-nostdin", "-loglevel", "error", "-re", "-f", "lavfi", "-i",
                        "testsrc=size=640x360:rate=30", "-f", "lavfi", "-i", "sine=frequency=440", "-t",
                        Integer.toString(seconds), "-c:v", "libx264", "-preset", "ultrafast", "-g", "30", "-c:a",
                        "aac", "-f", "flv", "rtmp://127.0.0.1:1935/app/" + streamKey).exec();
        ResultCallback.Adapter<Frame> callback = docker.execStartCmd(exec.getId())
                .exec(new ResultCallback.Adapter<>());
        if (!wait) {
            return 0;
        }
        callback.awaitCompletion(seconds + 30L, TimeUnit.SECONDS);
        Long code = docker.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
        return code == null ? -1 : code.intValue();
    }

    private static String token(KeyPair pair, String channelArn, String viewer, int version) throws Exception {
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String header = url.encodeToString("{\"alg\":\"ES384\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = url.encodeToString(("{\"aws:channel-arn\":\"" + channelArn + "\",\"aws:viewer-id\":\""
                + viewer + "\",\"aws:viewer-session-version\":" + version + ",\"exp\":"
                + (System.currentTimeMillis() / 1000 + 600) + "}").getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA384withECDSAinP1363Format");
        signer.initSign(pair.getPrivate());
        signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return header + "." + claims + "." + url.encodeToString(signer.sign());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void aBroadcastPlaysPrivatelyAndIsRecorded() throws Exception {
        signed("s3").put("/ivs-live-recordings").then().statusCode(200);
        String queueUrl = json("AmazonSQS.CreateQueue", "sqs", "{\"QueueName\":\"ivs-events\"}").path("QueueUrl")
                .asText();
        String queueArn = json("AmazonSQS.GetQueueAttributes", "sqs", "{\"QueueUrl\":\"" + queueUrl
                + "\",\"AttributeNames\":[\"QueueArn\"]}").path("Attributes").path("QueueArn").asText();
        json("AWSEvents.PutRule", "events", "{\"Name\":\"ivs\",\"EventPattern\":\"{\\\"source\\\":[\\\"aws.ivs\\\"]}\"}");
        json("AWSEvents.PutTargets", "events", "{\"Rule\":\"ivs\",\"Targets\":[{\"Id\":\"q\",\"Arn\":\"" + queueArn
                + "\"}]}");

        KeyPair viewerKey = p384();
        ivs("ImportPlaybackKeyPair", "{\"publicKeyMaterial\":\"" + publicKeyPem(viewerKey).replace("\n", "\\n")
                + "\"}").statusCode(200);
        String recording = ivs("CreateRecordingConfiguration", "{\"destinationConfiguration\":{\"s3\":{\"bucketName\":"
                + "\"ivs-live-recordings\"}},\"thumbnailConfiguration\":{\"recordingMode\":\"INTERVAL\","
                + "\"targetIntervalSeconds\":2,\"storage\":[\"SEQUENTIAL\",\"LATEST\"]}}").statusCode(200)
                .extract().path("recordingConfiguration.arn");
        JsonNode created = MAPPER.readTree(ivs("CreateChannel", "{\"name\":\"studio\",\"authorized\":true,"
                + "\"recordingConfigurationArn\":\"" + recording + "\"}").statusCode(200).extract().asString());
        String channelArn = created.path("channel").path("arn").asText();
        String streamKey = created.path("streamKey").path("value").asText();
        String playbackUrl = created.path("channel").path("playbackUrl").asText();
        String playbackPath = playbackUrl.substring(playbackUrl.indexOf("/api/video/v1/"));

        await("the media server", 90, () -> docker.listContainersCmd().withNameFilter(List.of(
                IvsMediaServer.CONTAINER)).exec().isEmpty() ? null : Boolean.TRUE);
        assertNotEquals(0, await("a refused key", 60, () -> {
            try {
                int code = publish("sk_us-east-1_wrong", 2, true);
                return code == 0 ? null : code;
            } catch (InterruptedException e) {
                return null;
            }
        }));
        publish(streamKey, 25, false);

        JsonNode stream = await("the stream going live", 60, () -> {
            String body = ivs("GetStream", "{\"channelArn\":\"" + channelArn + "\"}").extract().asString();
            try {
                JsonNode parsed = MAPPER.readTree(body);
                return "LIVE".equals(parsed.path("stream").path("state").asText()) ? parsed.path("stream") : null;
            } catch (Exception e) {
                return null;
            }
        });
        assertEquals("HEALTHY", stream.path("health").asText());
        String streamId = stream.path("streamId").asText();
        assertTrue(streamId.startsWith("st-"), streamId);
        ivs("ListStreams", "{}").statusCode(200).body("streams[0].channelArn", equalTo(channelArn));
        ivs("PutMetadata", "{\"channelArn\":\"" + channelArn + "\",\"metadata\":\"{\\\"quiz\\\":1}\"}").statusCode(200);
        ivs("InsertAdBreak", "{\"channelArn\":\"" + channelArn + "\",\"durationSeconds\":10}").statusCode(409);
        ivs("UpdateChannel", "{\"arn\":\"" + channelArn + "\",\"name\":\"other\"}").statusCode(409);
        ivs("DeleteChannel", "{\"arn\":\"" + channelArn + "\"}").statusCode(409);

        given().get(playbackPath).then().statusCode(403).body(containsString("playback token"));
        given().queryParam("token", "not.a.token").get(playbackPath).then().statusCode(403);
        String token = token(viewerKey, channelArn, "viewer-1", 1);
        String multivariant = await("the multivariant playlist", 30, () -> {
            Response response = given().queryParam("token", token).get(playbackPath);
            return response.statusCode() == 200 ? response.asString() : null;
        });
        String variant = multivariant.lines().filter(line -> line.startsWith(IvsPlaybackController.HLS_PREFIX))
                .findFirst().orElseThrow();
        String media = await("a media playlist with segments", 30, () -> {
            String body = given().urlEncodingEnabled(false).get(variant).asString();
            return body.contains(".ts") ? body : null;
        });
        String segment = media.lines().filter(line -> line.contains(".ts")).findFirst().orElseThrow();
        String segmentUrl = variant.substring(0, variant.lastIndexOf('/') + 1) + segment;
        byte[] bytes = given().urlEncodingEnabled(false).get(segmentUrl).then().statusCode(200).extract()
                .asByteArray();
        assertTrue(bytes.length > 1000 && bytes[0] == 0x47, "an MPEG-TS segment");

        ivs("StartViewerSessionRevocation", "{\"channelArn\":\"" + channelArn + "\",\"viewerId\":\"viewer-1\","
                + "\"viewerSessionVersionsLessThanOrEqualTo\":1}").statusCode(200);
        given().urlEncodingEnabled(false).get(segmentUrl).then().statusCode(403);
        given().queryParam("token", token).get(playbackPath).then().statusCode(403);
        given().queryParam("token", token(viewerKey, channelArn, "viewer-1", 2)).get(playbackPath).then()
                .statusCode(200);

        await("the stream ending", 60, () -> ivs("GetStream", "{\"channelArn\":\"" + channelArn + "\"}").extract()
                .statusCode() == 404 ? Boolean.TRUE : null);
        JsonNode session = MAPPER.readTree(ivs("GetStreamSession", "{\"channelArn\":\"" + channelArn + "\"}")
                .statusCode(200).extract().asString()).path("streamSession");
        assertEquals(streamId, session.path("streamId").asText());
        assertTrue(session.hasNonNull("endTime"));
        assertEquals(640, session.path("ingestConfiguration").path("video").path("videoWidth").asInt());
        assertEquals("avc1", session.path("ingestConfiguration").path("video").path("codec").asText());
        assertEquals("mp4a.40.2", session.path("ingestConfiguration").path("audio").path("codec").asText());
        List<String> sessionEvents = new ArrayList<>();
        session.path("truncatedEvents").forEach(event -> sessionEvents.add(event.path("name").asText()));
        assertTrue(sessionEvents.containsAll(List.of("Session Created", "Stream Start", "Recording Start",
                "Session Ended", "Stream End")), sessionEvents.toString());
        ivs("ListStreamSessions", "{\"channelArn\":\"" + channelArn + "\"}").statusCode(200)
                .body("streamSessions", hasSize(1));

        String channelId = channelArn.substring(channelArn.lastIndexOf('/') + 1);
        String ended = await("the recording", 120, () -> {
            List<String> keys = signed("s3").queryParam("list-type", "2").queryParam("prefix", "ivs/v1/000000000000/"
                    + channelId + "/").get("/ivs-live-recordings").then().extract().xmlPath()
                    .getList("ListBucketResult.Contents.Key");
            return keys.stream().filter(key -> key.endsWith("events/recording-ended.json")).findFirst().orElse(null);
        });
        String prefix = ended.substring(0, ended.indexOf("/events/"));
        JsonNode metadata = MAPPER.readTree(signed("s3").get("/ivs-live-recordings/" + ended).asString());
        assertEquals("RECORDING_ENDED", metadata.path("recording_status").asText());
        assertTrue(metadata.path("media").path("hls").path("duration_ms").asLong() > 10_000, metadata.toString());
        String rendition = metadata.path("media").path("hls").path("renditions").path(0).path("path").asText();
        assertEquals("360p30", rendition);
        String master = signed("s3").get("/ivs-live-recordings/" + prefix + "/media/hls/master.m3u8").then()
                .statusCode(200).extract().asString();
        assertTrue(master.contains(rendition + "/playlist.m3u8"), master);
        String playlist = signed("s3").get("/ivs-live-recordings/" + prefix + "/media/hls/" + rendition
                + "/playlist.m3u8").then().statusCode(200).extract().asString();
        assertTrue(playlist.contains("#EXT-X-ENDLIST") && playlist.contains("0.ts"), playlist);
        signed("s3").get("/ivs-live-recordings/" + prefix + "/media/hls/" + rendition + "/byte-range-variant.m3u8")
                .then().statusCode(200).body(containsString("#EXT-X-BYTERANGE"));
        signed("s3").get("/ivs-live-recordings/" + prefix + "/media/thumbnails/thumb1.jpg").then().statusCode(200);
        signed("s3").get("/ivs-live-recordings/" + prefix + "/media/latest_thumbnail/thumb.jpg").then().statusCode(200);
        signed("s3").get("/ivs-live-recordings/" + prefix + "/events/recording-started.json").then().statusCode(200)
                .body(containsString("RECORDING_STARTED"));

        List<String> events = new ArrayList<>();
        await("the stream and recording events", 30, () -> {
            try {
                JsonNode messages = json("AmazonSQS.ReceiveMessage", "sqs", "{\"QueueUrl\":\"" + queueUrl
                        + "\",\"MaxNumberOfMessages\":10}").path("Messages");
                for (JsonNode message : messages) {
                    JsonNode event = MAPPER.readTree(message.path("Body").asText());
                    JsonNode detail = event.path("detail");
                    events.add(event.path("detail-type").asText() + ":" + detail.path("event_name").asText(
                            detail.path("recording_status").asText()));
                }
            } catch (Exception e) {
                return null;
            }
            return events.contains("IVS Recording State Change:Recording End") ? Boolean.TRUE : null;
        });
        assertTrue(events.containsAll(List.of("IVS Stream State Change:Session Created",
                "IVS Stream State Change:Stream Start", "IVS Stream State Change:Stream End",
                "IVS Recording State Change:Recording Start")), events.toString());

        ivs("DeleteChannel", "{\"arn\":\"" + channelArn + "\"}").statusCode(200);
    }
}
