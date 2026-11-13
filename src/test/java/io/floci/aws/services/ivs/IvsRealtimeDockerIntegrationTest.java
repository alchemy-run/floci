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
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static io.floci.aws.services.ivs.IvsIntegrationTest.ivs;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * IVS Real-Time stages published to for real: an encoder publishes over RTMP with an ingest configuration's stream key
 * and another over WHIP with a participant token, they join the stage's session and are recorded individually to
 * S3, one is replicated into a second stage, and a composition mixes the stage into a channel, which goes live, and
 * into a composite recording in S3, with the IVS events EventBridge receives.
 */
@Tag("docker")
@QuarkusTest
@TestProfile(IvsRealtimeDockerIntegrationTest.RealMediaProfile.class)
class IvsRealtimeDockerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUCKET = "ivs-realtime-media";

    public static class RealMediaProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ivs.mock", "false",
                    "floci.services.ivs.rtmp-port", "19351",
                    "floci.services.ivs.rtmps-port", "19371",
                    "floci.services.ivs.srt-port", "19401",
                    "floci.services.ivs.webrtc-port", "18289",
                    "floci.services.ivs.host-port-base", "18600",
                    "floci.services.ivs.host-port-max", "18699",
                    "floci.services.ivs.poll-millis", "500",
                    "floci.services.ivs.composition-idle-seconds", "60");
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
        Assumptions.assumeTrue(available, "Docker daemon must be available for IVS Real-Time media tests");
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
                + version).body(body).post("/").then().statusCode(200).extract().asString();
        return MAPPER.readTree(response.isEmpty() ? "{}" : response);
    }

    private static JsonNode call(String operation, String body) {
        try {
            return MAPPER.readTree(ivs(operation, body).extract().asString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    /** Runs ffmpeg in the media server's container, in the background or to its end: its exit code then. */
    private int ffmpeg(List<String> output, int seconds, boolean wait) throws InterruptedException {
        return ffmpeg(output, seconds, wait, null);
    }

    /** The media server's log and each background publisher's output, for a failure's message. */
    private String diagnostics() {
        StringBuilder out = new StringBuilder();
        try {
            ExecCreateCmdResponse exec = docker.execCreateCmd(IvsMediaServer.CONTAINER).withAttachStdout(true)
                    .withAttachStderr(true).withCmd("sh", "-c", "tail -n 20 /tmp/pub-*.log").exec();
            docker.execStartCmd(exec.getId()).exec(new ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                    out.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                }
            }).awaitCompletion(10, TimeUnit.SECONDS);
            docker.logContainerCmd(IvsMediaServer.CONTAINER).withStdOut(true).withStdErr(true).withTail(60)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            out.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    }).awaitCompletion(10, TimeUnit.SECONDS);
        } catch (InterruptedException | RuntimeException e) {
            out.append(e);
        }
        return out.toString();
    }

    private int ffmpeg(List<String> output, int seconds, boolean wait, String log) throws InterruptedException {
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-loglevel", "error", "-re", "-f",
                "lavfi", "-i", "testsrc=size=640x360:rate=30", "-f", "lavfi", "-i", "sine=frequency=440", "-t",
                Integer.toString(seconds), "-c:v", "libx264", "-profile:v", "baseline", "-bf", "0", "-preset",
                "ultrafast", "-g", "30", "-pix_fmt", "yuv420p"));
        command.addAll(output);
        String[] argv = command.toArray(String[]::new);
        if (log != null) {
            argv = new String[] {"sh", "-c", String.join(" ", command.stream().map(a -> "'" + a + "'").toList())
                    + " > /tmp/pub-" + log + ".log 2>&1"};
        }
        ExecCreateCmdResponse exec = docker.execCreateCmd(IvsMediaServer.CONTAINER).withAttachStdout(true)
                .withAttachStderr(true).withCmd(argv).exec();
        ResultCallback.Adapter<Frame> callback = docker.execStartCmd(exec.getId())
                .exec(new ResultCallback.Adapter<>());
        if (!wait) {
            return 0;
        }
        callback.awaitCompletion(seconds + 30L, TimeUnit.SECONDS);
        Long code = docker.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
        return code == null ? -1 : code.intValue();
    }

    private static List<String> rtmp(String url) {
        return List.of("-c:a", "aac", "-f", "flv", url);
    }

    private static JsonNode participants(String stage, String session) {
        return call("ListParticipants", "{\"stageArn\":\"" + stage + "\",\"sessionId\":\"" + session + "\"}")
                .path("participants");
    }

    private static JsonNode participant(String stage, String session, String userId) {
        for (JsonNode participant : participants(stage, session)) {
            if (userId.equals(participant.path("userId").asText())) {
                return participant;
            }
        }
        return null;
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void stagesArePublishedRecordedReplicatedAndComposed() throws Exception {
        signed("s3").put("/" + BUCKET).then().statusCode(200);
        String queueUrl = json("AmazonSQS.CreateQueue", "sqs", "{\"QueueName\":\"ivs-realtime-events\"}")
                .path("QueueUrl").asText();
        String queueArn = json("AmazonSQS.GetQueueAttributes", "sqs", "{\"QueueUrl\":\"" + queueUrl
                + "\",\"AttributeNames\":[\"QueueArn\"]}").path("Attributes").path("QueueArn").asText();
        json("AWSEvents.PutRule", "events", "{\"Name\":\"ivs-rt\",\"EventPattern\":\"{\\\"source\\\":[\\\"aws.ivs\\\"]}\"}");
        json("AWSEvents.PutTargets", "events", "{\"Rule\":\"ivs-rt\",\"Targets\":[{\"Id\":\"q\",\"Arn\":\"" + queueArn
                + "\"}]}");

        String storage = call("CreateStorageConfiguration", "{\"s3\":{\"bucketName\":\"" + BUCKET + "\"}}")
                .path("storageConfiguration").path("arn").asText();
        String stage = call("CreateStage", "{\"name\":\"studio\",\"autoParticipantRecordingConfiguration\":{"
                + "\"storageConfigurationArn\":\"" + storage + "\",\"thumbnailConfiguration\":{\"recordingMode\":"
                + "\"INTERVAL\",\"targetIntervalSeconds\":2,\"storage\":[\"SEQUENTIAL\",\"LATEST\"]},"
                + "\"hlsConfiguration\":{\"targetSegmentDurationSeconds\":2}}}").path("stage").path("arn").asText();
        String overflow = call("CreateStage", "{\"name\":\"overflow\"}").path("stage").path("arn").asText();
        JsonNode ingest = call("CreateIngestConfiguration", "{\"name\":\"obs\",\"ingestProtocol\":\"RTMP\","
                + "\"insecureIngest\":true,\"stageArn\":\"" + stage + "\",\"userId\":\"encoder\",\"attributes\":"
                + "{\"seat\":\"1\"}}").path("ingestConfiguration");
        JsonNode secure = call("CreateIngestConfiguration", "{\"ingestProtocol\":\"RTMPS\",\"stageArn\":\"" + stage
                + "\",\"userId\":\"secure\"}").path("ingestConfiguration");
        String whipToken = call("CreateParticipantToken", "{\"stageArn\":\"" + stage + "\",\"userId\":\"whip\","
                + "\"capabilities\":[\"PUBLISH\"]}").path("participantToken").path("token").asText();

        await("the media server", 90, () -> docker.listContainersCmd().withNameFilter(List.of(
                IvsMediaServer.CONTAINER)).exec().isEmpty() ? null : Boolean.TRUE);
        // An RTMPS-only key refuses plain RTMP; it publishes over RTMPS.
        assertTrue(await("a refused plain RTMP publish", 60, () -> {
            try {
                return ffmpeg(rtmp("rtmp://127.0.0.1:1935/app/" + secure.path("streamKey").asText()), 3, true) != 0
                        ? Boolean.TRUE : null;
            } catch (InterruptedException e) {
                return null;
            }
        }));
        ffmpeg(rtmp("rtmp://127.0.0.1:1935/app/" + ingest.path("streamKey").asText()), 45, false, "rtmp");
        ffmpeg(rtmp("rtmps://127.0.0.1:1936/app/" + secure.path("streamKey").asText()), 45, false, "rtmps");
        ffmpeg(List.of("-c:a", "libopus", "-ar", "48000", "-ac", "2", "-f", "whip", "-authorization", whipToken,
                "http://host.docker.internal:" + RestAssured.port + IvsRealtimeWhip.PATH), 45, false, "whip");

        String session;
        try {
            session = await("three publishers in the stage's session", 60, () -> {
                String active = call("GetStage", "{\"arn\":\"" + stage + "\"}").path("stage").path("activeSessionId")
                        .asText("");
                if (active.isEmpty()) {
                    return null;
                }
                int published = 0;
                for (JsonNode participant : participants(stage, active)) {
                    published += participant.path("published").asBoolean() ? 1 : 0;
                }
                return published == 3 ? active : null;
            });
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "\n" + diagnostics(), e);
        }
        JsonNode encoder = participant(stage, session, "encoder");
        assertEquals("CONNECTED", encoder.path("state").asText());
        assertEquals(ingest.path("arn").asText(), encoder.path("ingestConfigurationArn").asText());
        JsonNode encoderDetail = call("GetParticipant", "{\"stageArn\":\"" + stage + "\",\"sessionId\":\"" + session
                + "\",\"participantId\":\"" + encoder.path("participantId").asText() + "\"}").path("participant");
        assertEquals("RTMP", encoderDetail.path("protocol").asText());
        assertEquals("1", encoderDetail.path("attributes").path("seat").asText());
        assertEquals("RTMPS", call("GetParticipant", "{\"stageArn\":\"" + stage + "\",\"sessionId\":\"" + session
                + "\",\"participantId\":\"" + participant(stage, session, "secure").path("participantId").asText()
                + "\"}").path("participant").path("protocol").asText());
        String whipParticipant = participant(stage, session, "whip").path("participantId").asText();
        assertEquals("WHIP", call("GetParticipant", "{\"stageArn\":\"" + stage + "\",\"sessionId\":\"" + session
                + "\",\"participantId\":\"" + whipParticipant + "\"}").path("participant").path("protocol").asText());
        assertEquals("ACTIVE", call("GetIngestConfiguration", "{\"arn\":\"" + ingest.path("arn").asText() + "\"}")
                .path("ingestConfiguration").path("state").asText());
        String prefix = await("the encoder's recording", 30, () -> {
            JsonNode participant = call("GetParticipant", "{\"stageArn\":\"" + stage + "\",\"sessionId\":\""
                    + session + "\",\"participantId\":\"" + encoder.path("participantId").asText() + "\"}")
                    .path("participant");
            return "ACTIVE".equals(participant.path("recordingState").asText())
                    ? participant.path("recordingS3Prefix").asText() : null;
        });

        // Replication: the encoder appears in the overflow stage too, as a replica.
        ivs("StartParticipantReplication", "{\"sourceStageArn\":\"" + stage + "\",\"destinationStageArn\":\""
                + overflow + "\",\"participantId\":\"" + encoder.path("participantId").asText() + "\"}")
                .statusCode(200);
        String overflowSession = await("the replica publishing", 60, () -> {
            String active = call("GetStage", "{\"arn\":\"" + overflow + "\"}").path("stage")
                    .path("activeSessionId").asText("");
            JsonNode replica = active.isEmpty() ? null : participant(overflow, active, "encoder");
            return replica != null && replica.path("published").asBoolean()
                    && "REPLICA".equals(replica.path("replicationType").asText()) ? active : null;
        });
        ivs("ListParticipantReplicas", "{\"sourceStageArn\":\"" + stage + "\",\"participantId\":\""
                + encoder.path("participantId").asText() + "\"}").statusCode(200)
                .body("replicas[0].replicationState", equalTo("ACTIVE"))
                .body("replicas[0].destinationSessionId", equalTo(overflowSession));
        ivs("StopParticipantReplication", "{\"sourceStageArn\":\"" + stage + "\",\"destinationStageArn\":\""
                + overflow + "\",\"participantId\":\"" + encoder.path("participantId").asText() + "\"}")
                .statusCode(200);
        await("the replica leaving", 30, () -> "DISCONNECTED".equals(participant(overflow, overflowSession,
                "encoder").path("state").asText()) ? Boolean.TRUE : null);

        // A composition mixes the stage into a channel, which goes live, and into an S3 recording.
        JsonNode channel = call("CreateChannel", "{\"name\":\"mix\",\"type\":\"STANDARD\"}");
        String channelArn = channel.path("channel").path("arn").asText();
        String encoderConfiguration = call("CreateEncoderConfiguration", "{\"name\":\"small\",\"video\":{\"width\":"
                + "640,\"height\":360,\"framerate\":15,\"bitrate\":800000}}").path("encoderConfiguration")
                .path("arn").asText();
        JsonNode composition = call("StartComposition", "{\"stageArn\":\"" + stage + "\",\"layout\":{\"grid\":{"
                + "\"gridGap\":4}},\"destinations\":[{\"channel\":{\"channelArn\":\"" + channelArn + "\","
                + "\"encoderConfigurationArn\":\"" + encoderConfiguration + "\"}},{\"s3\":{\"storageConfigurationArn\""
                + ":\"" + storage + "\",\"encoderConfigurationArns\":[\"" + encoderConfiguration + "\"],"
                + "\"thumbnailConfigurations\":[{\"targetIntervalSeconds\":2,\"storage\":[\"SEQUENTIAL\","
                + "\"LATEST\"]}]}}]}").path("composition");
        String compositionArn = composition.path("arn").asText();
        String compositePrefix = composition.path("destinations").path(1).path("detail").path("s3")
                .path("recordingPrefix").asText();
        await("the composition going active", 60, () -> "ACTIVE".equals(call("GetComposition", "{\"arn\":\""
                + compositionArn + "\"}").path("composition").path("state").asText()) ? Boolean.TRUE : null);
        await("the channel going live from the composition", 60, () -> "LIVE".equals(call("GetStream",
                "{\"channelArn\":\"" + channelArn + "\"}").path("stream").path("state").asText()) ? Boolean.TRUE
                : null);
        await("composite segments in S3", 60, () -> signed("s3").queryParam("list-type", "2")
                .queryParam("prefix", compositePrefix + "/media/hls/").get("/" + BUCKET).then().extract().asString()
                .contains(".ts") ? Boolean.TRUE : null);
        ivs("StopComposition", "{\"arn\":\"" + compositionArn + "\"}").statusCode(200);
        await("the composition stopping", 60, () -> "STOPPED".equals(call("GetComposition", "{\"arn\":\""
                + compositionArn + "\"}").path("composition").path("state").asText()) ? Boolean.TRUE : null);
        String rendition = "360p15-" + encoderConfiguration.substring(encoderConfiguration.lastIndexOf('/') + 1);
        signed("s3").get("/" + BUCKET + "/" + compositePrefix + "/media/hls/multivariant.m3u8").then().statusCode(200)
                .body(containsString(rendition + "/playlist.m3u8"));
        signed("s3").get("/" + BUCKET + "/" + compositePrefix + "/media/hls/" + rendition + "/playlist.m3u8").then()
                .statusCode(200).body(containsString("#EXT-X-PLAYLIST-TYPE:VOD"));
        signed("s3").get("/" + BUCKET + "/" + compositePrefix + "/media/latest_thumbnail/640x360/thumb.jpg").then()
                .statusCode(200);
        signed("s3").get("/" + BUCKET + "/" + compositePrefix + "/events/recording-ended.json").then()
                .statusCode(200).body(containsString("RECORDING_ENDED"));

        // The publishers end: they leave, the session ends, and each recording lands in S3.
        await("the publishers leaving", 90, () -> {
            String active = call("GetStage", "{\"arn\":\"" + stage + "\"}").path("stage").path("activeSessionId")
                    .asText("");
            return active.isEmpty() ? Boolean.TRUE : null;
        });
        assertEquals("INACTIVE", call("GetIngestConfiguration", "{\"arn\":\"" + ingest.path("arn").asText() + "\"}")
                .path("ingestConfiguration").path("state").asText());
        ivs("ListParticipantEvents", "{\"stageArn\":\"" + stage + "\",\"sessionId\":\"" + session
                + "\",\"participantId\":\"" + encoder.path("participantId").asText() + "\"}").statusCode(200)
                .body("events.name", hasItems("JOINED", "PUBLISH_STARTED",
                        "REPLICATION_STARTED", "REPLICATION_STOPPED", "PUBLISH_STOPPED", "LEFT"));
        await("the encoder's recording ending", 90, () -> "STOPPED".equals(call("GetParticipant", "{\"stageArn\":\""
                + stage + "\",\"sessionId\":\"" + session + "\",\"participantId\":\""
                + encoder.path("participantId").asText() + "\"}").path("participant").path("recordingState")
                .asText()) ? Boolean.TRUE : null);
        String playlist = signed("s3").get("/" + BUCKET + "/" + prefix + "/media/hls/high/playlist.m3u8").then()
                .statusCode(200).extract().asString();
        assertTrue(playlist.contains("#EXT-X-PROGRAM-DATE-TIME") && playlist.contains("#EXT-X-ENDLIST"), playlist);
        signed("s3").get("/" + BUCKET + "/" + prefix + "/media/hls/multivariant.m3u8").then().statusCode(200);
        signed("s3").get("/" + BUCKET + "/" + prefix + "/media/thumbnails/high/1.jpg").then().statusCode(200);
        signed("s3").get("/" + BUCKET + "/" + prefix + "/media/latest_thumbnail/high/thumb.jpg").then()
                .statusCode(200);
        signed("s3").get("/" + BUCKET + "/" + prefix + "/events/recording-ended.json").then().statusCode(200)
                .body(containsString("RECORDING_ENDED"));

        List<String> events = new ArrayList<>();
        await("the stage, recording and composition events", 30, () -> {
            try {
                JsonNode messages = json("AmazonSQS.ReceiveMessage", "sqs", "{\"QueueUrl\":\"" + queueUrl
                        + "\",\"MaxNumberOfMessages\":10}").path("Messages");
                for (JsonNode message : messages) {
                    JsonNode event = MAPPER.readTree(message.path("Body").asText());
                    events.add(event.path("detail-type").asText() + ":" + event.path("detail").path("event_name")
                            .asText());
                }
            } catch (Exception e) {
                return null;
            }
            return events.contains("IVS Participant Recording State Change:Recording End") ? Boolean.TRUE : null;
        });
        assertTrue(events.containsAll(List.of("IVS Stage Update:Participant Published",
                "IVS Stage Update:Participant Unpublished", "IVS Stage Update:Participant Publish Error",
                "IVS Stage Update:Participant Replication Start", "IVS Stage Update:Participant Replication End",
                "IVS Participant Recording State Change:Recording Start",
                "IVS Composition State Change:Session Start", "IVS Composition State Change:Destination Start",
                "IVS Composition State Change:Destination End", "IVS Composition State Change:Session End")),
                events.toString());

        ivs("DeleteChannel", "{\"arn\":\"" + channelArn + "\"}").statusCode(200);
        ivs("DeleteStage", "{\"arn\":\"" + stage + "\"}").statusCode(200);
        ivs("DeleteStage", "{\"arn\":\"" + overflow + "\"}").statusCode(200);
    }
}
