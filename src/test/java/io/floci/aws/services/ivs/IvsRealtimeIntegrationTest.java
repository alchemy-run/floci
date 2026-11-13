package io.floci.aws.services.ivs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import static io.floci.aws.services.ivs.IvsIntegrationTest.ivs;
import static io.floci.aws.services.ivs.IvsIntegrationTest.p384;
import static io.floci.aws.services.ivs.IvsIntegrationTest.publicKeyPem;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amazon IVS Real-Time's control plane, without a media server: stages and participant tokens, the WHIP endpoint's
 * token checks, encoder, storage and ingest configurations, public keys and self-signed tokens, compositions, empty
 * sessions and replication, and tags beside IVS's.
 */
@QuarkusTest
class IvsRealtimeIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static JsonNode payload(String token) throws Exception {
        return MAPPER.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private static String signed(KeyPair pair, String kid, ObjectNode payload) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String input = encoder.encodeToString(("{\"alg\":\"ES384\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}")
                .getBytes(StandardCharsets.UTF_8)) + "." + encoder.encodeToString(payload.toString()
                .getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA384withECDSAinP1363Format");
        signature.initSign(pair.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + encoder.encodeToString(signature.sign());
    }

    private static int whip(String token, String offer) {
        return given().config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                        .encodeContentTypeAs("application/sdp", ContentType.TEXT)))
                .header("Authorization", "Bearer " + token).contentType("application/sdp").body(offer)
                .post(IvsRealtimeWhip.PATH).then().extract().statusCode();
    }

    @Test
    void stagesTokensAndConfigurations() throws Exception {
        ivs("GetStage", "{\"arn\":\"arn:aws:ivs:us-east-1:" + ACCOUNT + ":stage/abcdABCDefgh\"}").statusCode(404)
                .header("x-amzn-ErrorType", "ResourceNotFoundException");
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/s3/"
                + "aws4_request").put("/ivs-realtime-recordings").then().statusCode(200);
        ivs("CreateStorageConfiguration", "{\"s3\":{\"bucketName\":\"ivs-realtime-missing\"}}").statusCode(404);
        String storage = ivs("CreateStorageConfiguration", "{\"name\":\"rec\",\"s3\":{\"bucketName\":"
                + "\"ivs-realtime-recordings\"}}").statusCode(200)
                .body("storageConfiguration.arn", containsString(":storage-configuration/"))
                .extract().path("storageConfiguration.arn");

        JsonNode created = MAPPER.readTree(ivs("CreateStage", "{\"name\":\"studio\",\"tags\":{\"team\":\"live\"},"
                + "\"autoParticipantRecordingConfiguration\":{\"storageConfigurationArn\":\"" + storage + "\"},"
                + "\"participantTokenConfigurations\":[{\"userId\":\"host\",\"capabilities\":[\"PUBLISH\"]}]}")
                .statusCode(200).extract().asString());
        String stage = created.path("stage").path("arn").asText();
        assertTrue(stage.matches("arn:aws:ivs:us-east-1:" + ACCOUNT + ":stage/[A-Za-z0-9]{12}"), stage);
        assertTrue(created.path("stage").path("endpoints").path("whip").asText().endsWith(IvsRealtimeWhip.PATH));
        assertTrue(created.path("stage").path("endpoints").path("rtmp").asText().matches("rtmp://.+:1935/app/"));
        assertEquals("AUDIO_VIDEO", created.path("stage").path("autoParticipantRecordingConfiguration")
                .path("mediaTypes").path(0).asText());
        JsonNode hostToken = created.path("participantTokens").path(0);
        assertEquals("host", hostToken.path("userId").asText());
        JsonNode claims = payload(hostToken.path("token").asText());
        assertEquals(stage, claims.path("resource").asText());
        assertEquals(hostToken.path("participantId").asText(), claims.path("jti").asText());
        assertTrue(claims.path("capabilities").path("allow_publish").asBoolean());
        assertTrue(!claims.path("capabilities").path("allow_subscribe").asBoolean(false));
        assertEquals("1.0", claims.path("version").asText());

        String token = ivs("CreateParticipantToken", "{\"stageArn\":\"" + stage + "\",\"userId\":\"guest\","
                + "\"duration\":30,\"attributes\":{\"name\":\"Guest\"}}").statusCode(200)
                .body("participantToken.duration", equalTo(30))
                .body("participantToken.capabilities", hasItem("SUBSCRIBE"))
                .body("participantToken.participantId", matchesPattern("[A-Za-z0-9]{12}"))
                .extract().path("participantToken.token");
        JsonNode guest = payload(token);
        assertEquals(30 * 60, guest.path("exp").asLong() - guest.path("iat").asLong());
        assertEquals("Guest", guest.path("attributes").path("name").asText());

        // The WHIP endpoint checks the bearer token before the media server is involved.
        assertEquals(401, whip("not-a-token", "v=0"));
        String subscriber = ivs("CreateParticipantToken", "{\"stageArn\":\"" + stage + "\",\"capabilities\":"
                + "[\"SUBSCRIBE\"]}").statusCode(200).extract().path("participantToken.token");
        assertEquals(403, whip(subscriber, "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 H264/90000\r\n"));
        assertEquals(400, whip(token, "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\n"));
        assertEquals(503, whip(token, "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 H264/90000\r\n"));

        // A self-signed token is admitted with its imported key, and refused once the key is deleted.
        KeyPair pair = p384();
        String publicKey = ivs("ImportPublicKey", "{\"name\":\"signer\",\"publicKeyMaterial\":\""
                + publicKeyPem(pair).replace("\n", "\\n") + "\"}").statusCode(200)
                .body("publicKey.fingerprint", matchesPattern("([0-9a-f]{2}:){15}[0-9a-f]{2}"))
                .extract().path("publicKey.arn");
        ivs("ImportPublicKey", "{\"publicKeyMaterial\":\"" + publicKeyPem(pair).replace("\n", "\\n") + "\"}")
                .statusCode(409);
        ObjectNode self = MAPPER.createObjectNode();
        self.put("exp", Instant.now().getEpochSecond() + 600).put("iat", Instant.now().getEpochSecond())
                .put("jti", "selfSigned-1").put("resource", stage).put("topic", stage.substring(stage.lastIndexOf('/')
                        + 1)).put("events_url", "wss://x").put("whip_url", "https://x").put("version", "1.0")
                .putObject("capabilities").put("allow_publish", true);
        String selfSigned = signed(pair, publicKey, self);
        assertEquals(503, whip(selfSigned, "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 H264/90000\r\n"));
        assertEquals(401, whip(signed(pair, "arn:aws:ivs:us-east-1:" + ACCOUNT + ":public-key/nope", self), "v=0"));
        self.put("exp", Instant.now().getEpochSecond() - 1);
        assertEquals(401, whip(signed(pair, publicKey, self), "v=0"));
        ivs("DeletePublicKey", "{\"arn\":\"" + publicKey + "\"}").statusCode(200);
        assertEquals(401, whip(selfSigned, "v=0"));

        // The refused publishers joined, failed to publish and left, each in a session of its own.
        JsonNode sessions = MAPPER.readTree(ivs("ListStageSessions", "{\"stageArn\":\"" + stage + "\"}")
                .statusCode(200).body("stageSessions", hasSize(2)).extract().asString()).path("stageSessions");
        String firstSession = sessions.path(1).path("sessionId").asText();
        assertTrue(firstSession.matches("st-[A-Za-z0-9]{13}"), firstSession);
        String subscriberId = payload(subscriber).path("jti").asText();
        ivs("ListParticipants", "{\"stageArn\":\"" + stage + "\",\"sessionId\":\"" + firstSession + "\"}")
                .statusCode(200).body("participants", hasSize(1))
                .body("participants[0].participantId", equalTo(subscriberId))
                .body("participants[0].state", equalTo("DISCONNECTED"))
                .body("participants[0].published", equalTo(false));
        ivs("ListParticipantEvents", "{\"stageArn\":\"" + stage + "\",\"sessionId\":\"" + firstSession
                + "\",\"participantId\":\"" + subscriberId + "\"}").statusCode(200)
                .body("events.name", contains("JOINED", "PUBLISH_ERROR", "LEFT"))
                .body("events[1].errorCode", equalTo("INSUFFICIENT_CAPABILITIES"));
        ivs("GetStage", "{\"arn\":\"" + stage + "\"}").statusCode(200).body("stage.activeSessionId", nullValue());
        ivs("GetStageSession", "{\"stageArn\":\"" + stage + "\",\"sessionId\":\"st-0000AbCd00000\"}").statusCode(404);
        ivs("GetStageSession", "{\"stageArn\":\"" + stage + "\",\"sessionId\":\"st-0000AbCd0000\"}").statusCode(400);
        ivs("ListParticipants", "{\"stageArn\":\"" + stage + "\",\"sessionId\":\"" + firstSession + "\","
                + "\"filterByUserId\":\"a\",\"filterByPublished\":true}").statusCode(400);
        String overflow = ivs("CreateStage", "{\"name\":\"overflow\"}").statusCode(200).extract().path("stage.arn");
        ivs("StartParticipantReplication", "{\"sourceStageArn\":\"" + stage + "\",\"destinationStageArn\":\""
                + overflow + "\",\"participantId\":\"abcDEF123456\"}").statusCode(404);
        ivs("StopParticipantReplication", "{\"sourceStageArn\":\"" + stage + "\",\"destinationStageArn\":\""
                + overflow + "\",\"participantId\":\"abcDEF123456\"}").statusCode(404);
        ivs("ListParticipantReplicas", "{\"sourceStageArn\":\"" + stage + "\",\"participantId\":\"abcDEF123456\"}")
                .statusCode(200).body("replicas", hasSize(0));
        ivs("DisconnectParticipant", "{\"stageArn\":\"" + stage + "\",\"participantId\":\"abcDEF123456\"}")
                .statusCode(404);

        // Ingest configurations: RTMP needs insecureIngest; keys name the account, Region and configuration.
        ivs("CreateIngestConfiguration", "{\"ingestProtocol\":\"RTMP\",\"stageArn\":\"" + stage + "\"}")
                .statusCode(400);
        String ingest = ivs("CreateIngestConfiguration", "{\"name\":\"obs\",\"ingestProtocol\":\"RTMPS\","
                + "\"stageArn\":\"" + stage + "\",\"userId\":\"encoder\",\"redundantIngest\":true}").statusCode(200)
                .body("ingestConfiguration.state", equalTo("INACTIVE"))
                .body("ingestConfiguration.streamKey", startsWith("rt_" + ACCOUNT + "_us-east-1_"))
                .body("ingestConfiguration.redundantIngestCredentials", hasSize(2))
                .extract().path("ingestConfiguration.arn");
        ivs("ListIngestConfigurations", "{\"filterByStageArn\":\"" + stage + "\"}").statusCode(200)
                .body("ingestConfigurations.arn", hasItem(ingest));
        ivs("UpdateIngestConfiguration", "{\"arn\":\"" + ingest + "\",\"stageArn\":\"" + overflow + "\"}")
                .statusCode(200).body("ingestConfiguration.stageArn", equalTo(overflow));
        ivs("DeleteIngestConfiguration", "{\"arn\":\"" + ingest + "\"}").statusCode(200);

        // Encoder configurations and compositions; a storage configuration in use cannot be deleted.
        String encoder = ivs("CreateEncoderConfiguration", "{\"name\":\"hd\",\"video\":{\"width\":640,"
                + "\"height\":360}}").statusCode(200).body("encoderConfiguration.video.framerate", equalTo(30.0f))
                .body("encoderConfiguration.video.bitrate", equalTo(2500000)).extract().path("encoderConfiguration.arn");
        ivs("StartComposition", "{\"stageArn\":\"" + stage + "\",\"destinations\":[{\"s3\":{"
                + "\"storageConfigurationArn\":\"" + storage + "\",\"encoderConfigurationArns\":[]}}]}")
                .statusCode(400);
        JsonNode composition = MAPPER.readTree(ivs("StartComposition", "{\"stageArn\":\"" + stage + "\","
                + "\"idempotencyToken\":\"once\",\"layout\":{\"pip\":{\"pipPosition\":\"TOP_LEFT\"}},\"destinations\":"
                + "[{\"name\":\"rec\",\"s3\":{\"storageConfigurationArn\":\"" + storage + "\",\"encoderConfigurationArns\""
                + ":[\"" + encoder + "\"],\"thumbnailConfigurations\":[{\"targetIntervalSeconds\":5}]}}]}")
                .statusCode(200).extract().asString()).path("composition");
        String compositionArn = composition.path("arn").asText();
        String destination = composition.path("destinations").path(0).path("id").asText();
        assertEquals("TOP_LEFT", composition.path("layout").path("pip").path("pipPosition").asText());
        assertEquals(stage.substring(stage.lastIndexOf('/') + 1) + "/" + compositionArn.substring(
                compositionArn.lastIndexOf('/') + 1) + "/" + destination + "/composite",
                composition.path("destinations").path(0).path("detail").path("s3").path("recordingPrefix").asText());
        ivs("StartComposition", "{\"stageArn\":\"" + stage + "\",\"idempotencyToken\":\"once\",\"destinations\":[{"
                + "\"s3\":{\"storageConfigurationArn\":\"" + storage + "\",\"encoderConfigurationArns\":[\"" + encoder
                + "\"]}}]}").statusCode(200).body("composition.arn", equalTo(compositionArn));
        ivs("GetComposition", "{\"arn\":\"" + compositionArn + "\"}").statusCode(200)
                .body("composition.state", equalTo("ACTIVE"))
                .body("composition.destinations[0].state", equalTo("ACTIVE"));
        ivs("ListCompositions", "{\"filterByEncoderConfigurationArn\":\"" + encoder + "\"}").statusCode(200)
                .body("compositions.arn", hasItem(compositionArn));
        ivs("DeleteEncoderConfiguration", "{\"arn\":\"" + encoder + "\"}").statusCode(409);
        ivs("DeleteStorageConfiguration", "{\"arn\":\"" + storage + "\"}").statusCode(409);
        ivs("StopComposition", "{\"arn\":\"" + compositionArn + "\"}").statusCode(200);
        ivs("GetComposition", "{\"arn\":\"" + compositionArn + "\"}").statusCode(200)
                .body("composition.state", equalTo("STOPPED")).body("composition.endTime", containsString("T"));
        ivs("DeleteEncoderConfiguration", "{\"arn\":\"" + encoder + "\"}").statusCode(200);

        // Tags: Real-Time's resources and IVS's share the tag routes.
        String auth = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/ivs/aws4_request";
        // The ARN is encoded once, as the SDKs send it; RestAssured would encode it again.
        String encoded = URLEncoder.encode(stage, StandardCharsets.UTF_8);
        given().urlEncodingEnabled(false).header("Authorization", auth).contentType("application/json")
                .body("{\"tags\":{\"env\":\"dev\"}}").post("/tags/" + encoded).then().statusCode(200);
        given().urlEncodingEnabled(false).header("Authorization", auth).get("/tags/" + encoded).then()
                .statusCode(200).body("tags.env", equalTo("dev")).body("tags.team", equalTo("live"));
        String channel = ivs("CreateChannel", "{\"tags\":{\"k\":\"v\"}}").statusCode(200).extract().path("channel.arn");
        given().urlEncodingEnabled(false).header("Authorization", auth)
                .get("/tags/" + URLEncoder.encode(channel, StandardCharsets.UTF_8))
                .then().statusCode(200).body("tags.k", equalTo("v"));

        ivs("UpdateStage", "{\"arn\":\"" + stage + "\",\"name\":\"renamed\",\"autoParticipantRecordingConfiguration\""
                + ":{\"storageConfigurationArn\":\"\"}}").statusCode(200).body("stage.name", equalTo("renamed"))
                .body("stage.endpoints.whip", endsWith(IvsRealtimeWhip.PATH));
        ivs("DeleteStorageConfiguration", "{\"arn\":\"" + storage + "\"}").statusCode(200);
        ivs("DeleteStage", "{\"arn\":\"" + stage + "\"}").statusCode(200);
        ivs("DeleteStage", "{\"arn\":\"" + overflow + "\"}").statusCode(200);
        ivs("DeleteChannel", "{\"arn\":\"" + channel + "\"}").statusCode(200);
        assertEquals(401, whip(token, "v=0"));
    }
}
