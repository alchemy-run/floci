package io.floci.aws.services.ivs;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Amazon IVS's control plane over REST, without a media server: channels and their stream keys, playback key pairs,
 * playback restriction policies, recording and ad configurations, an idle channel's streams, viewer session
 * revocations and tags.
 */
@QuarkusTest
class IvsIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static ValidatableResponse ivs(String operation, String body) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/ivs/"
                + "aws4_request").contentType("application/json").body(body).post("/" + operation).then();
    }

    static String publicKeyPem(KeyPair pair) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
    }

    static KeyPair p384() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        return generator.generateKeyPair();
    }

    @Test
    void channelsKeysAndConfigurations() throws Exception {
        ivs("GetChannel", "{\"arn\":\"arn:aws:ivs:us-east-1:000000000000:channel/abcdABCDefgh\"}").statusCode(404)
                .header("x-amzn-ErrorType", "ResourceNotFoundException").body("exceptionMessage", containsString(
                        "not found"));
        ivs("CreateChannel", "{\"type\":\"PREMIUM\"}").statusCode(400)
                .header("x-amzn-ErrorType", "ValidationException");

        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/s3/aws4_request")
                .put("/ivs-recordings").then().statusCode(200);
        String recording = ivs("CreateRecordingConfiguration", "{\"name\":\"archive\",\"destinationConfiguration\":"
                + "{\"s3\":{\"bucketName\":\"ivs-recordings\"}},\"recordingReconnectWindowSeconds\":120,"
                + "\"thumbnailConfiguration\":{\"recordingMode\":\"INTERVAL\",\"targetIntervalSeconds\":30}}")
                .statusCode(200).body("recordingConfiguration.arn", containsString(":recording-configuration/"))
                .extract().path("recordingConfiguration.arn");
        ivs("GetRecordingConfiguration", "{\"arn\":\"" + recording + "\"}").statusCode(200)
                .body("recordingConfiguration.state", equalTo("ACTIVE"))
                .body("recordingConfiguration.recordingReconnectWindowSeconds", equalTo(120))
                .body("recordingConfiguration.thumbnailConfiguration.targetIntervalSeconds", equalTo(30))
                .body("recordingConfiguration.thumbnailConfiguration.storage", hasItem("SEQUENTIAL"));
        String failed = ivs("CreateRecordingConfiguration", "{\"destinationConfiguration\":{\"s3\":{\"bucketName\":"
                + "\"ivs-no-such-bucket\"}}}").statusCode(200).extract().path("recordingConfiguration.arn");
        ivs("GetRecordingConfiguration", "{\"arn\":\"" + failed + "\"}").body("recordingConfiguration.state",
                equalTo("CREATE_FAILED"));

        String policy = ivs("CreatePlaybackRestrictionPolicy", "{\"name\":\"geo\",\"allowedCountries\":[\"US\",\"CA\"],"
                + "\"allowedOrigins\":[\"https://example.com\"]}").statusCode(200)
                .body("playbackRestrictionPolicy.enableStrictOriginEnforcement", equalTo(false))
                .extract().path("playbackRestrictionPolicy.arn");
        ivs("CreatePlaybackRestrictionPolicy", "{\"allowedCountries\":[\"usa\"]}").statusCode(400);

        String created = ivs("CreateChannel", "{\"name\":\"live\",\"latencyMode\":\"LOW\",\"type\":\"BASIC\","
                + "\"recordingConfigurationArn\":\"" + recording + "\",\"playbackRestrictionPolicyArn\":\"" + policy
                + "\",\"tags\":{\"team\":\"video\"}}").statusCode(200)
                .body("channel.arn", containsString(":channel/")).body("channel.ingestEndpoint", containsString(":1935"))
                .body("channel.playbackUrl", containsString("/api/video/v1/us-east-1.000000000000.channel."))
                .body("channel.srt.passphrase", matchesPattern("[A-Za-z0-9]{32}"))
                .body("streamKey.value", startsWith("sk_us-east-1_"))
                .body("channel.tags.team", equalTo("video")).body("streamKey.tags.size()", equalTo(0))
                .extract().path("channel.arn");
        String streamKey = ivs("ListStreamKeys", "{\"channelArn\":\"" + created + "\"}").statusCode(200)
                .body("streamKeys", hasSize(1)).extract().path("streamKeys[0].arn");
        ivs("CreateStreamKey", "{\"channelArn\":\"" + created + "\"}").statusCode(402)
                .header("x-amzn-ErrorType", "ServiceQuotaExceededException");
        ivs("BatchGetChannel", "{\"arns\":[\"" + created + "\",\"arn:aws:ivs:us-east-1:000000000000:channel/"
                + "nopeNOPEnope\"]}").statusCode(200).body("channels", hasSize(1)).body("errors", hasSize(1));
        ivs("BatchGetStreamKey", "{\"arns\":[\"" + streamKey + "\"]}").statusCode(200).body("streamKeys", hasSize(1));
        ivs("ListChannels", "{\"filterByName\":\"li\"}").statusCode(200).body("channels.arn", hasItem(created));
        ivs("ListChannels", "{\"filterByName\":\"li\",\"filterByRecordingConfigurationArn\":\"" + recording + "\"}")
                .statusCode(409);
        ivs("UpdateChannel", "{\"arn\":\"" + created + "\",\"name\":\"live-b\",\"latencyMode\":\"NORMAL\","
                + "\"authorized\":true}").statusCode(200).body("channel.name", equalTo("live-b"))
                .body("channel.authorized", equalTo(true)).body("channel.type", equalTo("BASIC"));

        // An idle channel: its streams answer as an idle IVS channel does.
        ivs("GetStream", "{\"channelArn\":\"" + created + "\"}").statusCode(404)
                .header("x-amzn-ErrorType", "ChannelNotBroadcasting");
        ivs("GetStreamSession", "{\"channelArn\":\"" + created + "\"}").statusCode(404);
        ivs("ListStreamSessions", "{\"channelArn\":\"" + created + "\"}").statusCode(200)
                .body("streamSessions", hasSize(0));
        ivs("ListStreams", "{}").statusCode(200).body("streams", hasSize(0));
        ivs("PutMetadata", "{\"channelArn\":\"" + created + "\",\"metadata\":\"hello\"}").statusCode(404)
                .header("x-amzn-ErrorType", "ChannelNotBroadcasting");
        ivs("StopStream", "{\"channelArn\":\"" + created + "\"}").statusCode(404);
        ivs("InsertAdBreak", "{\"channelArn\":\"" + created + "\",\"durationSeconds\":30}").statusCode(404);
        ivs("StartViewerSessionRevocation", "{\"channelArn\":\"" + created + "\",\"viewerId\":\"v1\"}")
                .statusCode(200);
        ivs("BatchStartViewerSessionRevocation", "{\"viewerSessions\":[{\"channelArn\":\"" + created
                + "\",\"viewerId\":\"v2\"},{\"channelArn\":\"arn:aws:ivs:us-east-1:000000000000:channel/nopeNOPEnope\","
                + "\"viewerId\":\"v3\"}]}").statusCode(200).body("errors", hasSize(1));

        ivs("DeleteRecordingConfiguration", "{\"arn\":\"" + recording + "\"}").statusCode(409);
        ivs("DeletePlaybackRestrictionPolicy", "{\"arn\":\"" + policy + "\"}").statusCode(409);

        KeyPair pair = p384();
        String keyPair = ivs("ImportPlaybackKeyPair", "{\"name\":\"viewer\",\"publicKeyMaterial\":\""
                + publicKeyPem(pair).replace("\n", "\\n") + "\"}").statusCode(200)
                .body("keyPair.fingerprint", matchesPattern("([0-9a-f]{2}:){15}[0-9a-f]{2}"))
                .extract().path("keyPair.arn");
        ivs("ImportPlaybackKeyPair", "{\"publicKeyMaterial\":\"" + publicKeyPem(pair).replace("\n", "\\n") + "\"}")
                .statusCode(409);
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        ivs("ImportPlaybackKeyPair", "{\"publicKeyMaterial\":\"" + publicKeyPem(rsa.generateKeyPair())
                .replace("\n", "\\n") + "\"}").statusCode(400);
        ivs("ListPlaybackKeyPairs", "{}").statusCode(200).body("keyPairs.arn", hasItem(keyPair));

        String ad = ivs("CreateAdConfiguration", "{\"name\":\"ads\",\"mediaTailorPlaybackConfigurations\":[{"
                + "\"playbackConfigurationArn\":\"arn:aws:mediatailor:us-east-1:000000000000:playbackConfiguration/"
                + "ads\"}]}").statusCode(200).extract().path("adConfiguration.arn");
        ivs("CreateAdConfiguration", "{\"mediaTailorPlaybackConfigurations\":[{\"playbackConfigurationArn\":\"nope\"}]}")
                .statusCode(400);

        String encoded = URLEncoder.encode(created, StandardCharsets.UTF_8);
        String auth = "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/ivs/aws4_request";
        given().header("Authorization", auth).contentType("application/json").body("{\"tags\":{\"stage\":\"one\"}}")
                .post("/tags/" + encoded).then().statusCode(200);
        given().header("Authorization", auth).queryParam("tagKeys", "team").delete("/tags/" + encoded).then()
                .statusCode(200);
        given().header("Authorization", auth).get("/tags/" + encoded).then().statusCode(200)
                .body("tags.stage", equalTo("one")).body("tags.team", nullValue());

        // Playback of an idle channel: nothing to play.
        given().get("/api/video/v1/us-east-1.000000000000.channel." + created.substring(created.lastIndexOf('/') + 1)
                + ".m3u8").then().statusCode(403);

        ivs("UpdateChannel", "{\"arn\":\"" + created + "\",\"recordingConfigurationArn\":\"\","
                + "\"playbackRestrictionPolicyArn\":\"\",\"authorized\":false}").statusCode(200);
        given().get("/api/video/v1/us-east-1.000000000000.channel." + created.substring(created.lastIndexOf('/') + 1)
                + ".m3u8").then().statusCode(404);
        ivs("DeleteChannel", "{\"arn\":\"" + created + "\"}").statusCode(200);
        ivs("GetStreamKey", "{\"arn\":\"" + streamKey + "\"}").statusCode(404);
        for (String[] resource : new String[][] {{"DeleteRecordingConfiguration", recording},
            {"DeletePlaybackRestrictionPolicy", policy}, {"DeletePlaybackKeyPair", keyPair},
            {"DeleteAdConfiguration", ad}}) {
            ivs(resource[0], "{\"arn\":\"" + resource[1] + "\"}").statusCode(200);
        }
        ivs("GetPlaybackKeyPair", "{\"arn\":\"" + keyPair + "\"}").statusCode(404);
    }
}
