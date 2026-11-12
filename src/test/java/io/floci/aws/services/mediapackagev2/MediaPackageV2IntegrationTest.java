package io.floci.aws.services.mediapackagev2;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MediaPackage v2: channel groups, channels and origin endpoints with policies and tags; HLS ingested by PUT and
 * served from a TS origin endpoint; resets; harvest jobs writing a window to S3.
 */
@QuarkusTest
class MediaPackageV2IntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static RequestSpecification mp() {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/mediapackagev2/aws4_request")
                .contentType("application/json");
    }

    /** A request to a URL the service gave, by its path and Host. */
    static RequestSpecification at(String url) {
        URI uri = URI.create(url);
        return given().header("Host", uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort()));
    }

    static String path(String url) {
        return URI.create(url).getRawPath();
    }

    public static byte[] segment(int seed) {
        byte[] data = new byte[188 * 4];
        for (int i = 0; i < data.length; i += 188) {
            data[i] = 0x47;
            data[i + 1] = (byte) seed;
        }
        return data;
    }

    @Test
    void groupsChannelsAndEndpoints() {
        mp().body("{\"ChannelGroupName\":\"live\",\"Description\":\"first\",\"tags\":{\"team\":\"video\"}}")
                .post("/channelGroup").then().statusCode(200).body("EgressDomain", containsString(".mediapackagev2."))
                .body("Arn", containsString(":channelGroup/live"));
        mp().body("{\"ChannelGroupName\":\"live\"}").post("/channelGroup").then().statusCode(409)
                .header("x-amzn-ErrorType", containsString("ConflictException"));
        mp().body("{\"ChannelName\":\"feed\",\"InputType\":\"HLS\"}").post("/channelGroup/live/channel").then()
                .statusCode(200).body("IngestEndpoints", hasSize(2))
                .body("IngestEndpoints[0].Url", containsString("/in/v1/live/1/feed/"));
        mp().body("{\"OriginEndpointName\":\"ts\",\"ContainerType\":\"TS\",\"StartoverWindowSeconds\":600,"
                        + "\"HlsManifests\":[{\"ManifestName\":\"index\"}]}")
                .post("/channelGroup/live/channel/feed/originEndpoint").then().statusCode(200)
                .body("HlsManifests[0].Url", containsString("/out/v1/live/feed/ts/index.m3u8"))
                .body("Segment.SegmentName", equalTo("segment"));
        mp().body("{\"OriginEndpointName\":\"bad\",\"ContainerType\":\"TS\",\"DashManifests\":[{\"ManifestName\":"
                + "\"d\"}]}").post("/channelGroup/live/channel/feed/originEndpoint").then().statusCode(400);
        mp().body("{\"ContainerType\":\"CMAF\"}").put("/channelGroup/live/channel/feed/originEndpoint/ts").then()
                .statusCode(400).body("ValidationExceptionType", equalTo("CONTAINER_TYPE_IMMUTABLE"));
        mp().body("{\"ContainerType\":\"TS\",\"Description\":\"v2\"}").put(
                "/channelGroup/live/channel/feed/originEndpoint/ts").then().statusCode(200)
                .body("Description", equalTo("v2"));
        mp().body("{\"Policy\":\"{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[{\\\"Sid\\\":\\\"A\\\","
                + "\\\"Effect\\\":\\\"Allow\\\",\\\"Principal\\\":\\\"*\\\",\\\"Action\\\":\\\"mediapackagev2:"
                + "GetObject\\\",\\\"Resource\\\":\\\"*\\\"}]}\"}").post(
                "/channelGroup/live/channel/feed/originEndpoint/ts/policy").then().statusCode(200);
        mp().get("/channelGroup/live/channel/feed/originEndpoint/ts/policy").then().statusCode(200)
                .body("Policy", containsString("\"Sid\":\"A\""));
        mp().get("/channelGroup/live/channel/feed/policy").then().statusCode(404)
                .body("ResourceTypeNotFound", equalTo("CHANNEL"));
        String arn = mp().get("/channelGroup/live").then().extract().path("Arn");
        mp().get("/tags/" + arn).then().statusCode(200).body("tags.team", equalTo("video"));
        mp().get("/channelGroup").then().body("Items.ChannelGroupName", hasItem("live"));
        mp().get("/channelGroup/live/channel/feed/originEndpoint").then().body("Items.OriginEndpointName",
                hasItem("ts"));
        mp().delete("/channelGroup/live").then().statusCode(409);
        mp().delete("/channelGroup/live/channel/feed/originEndpoint/ts").then().statusCode(200);
        mp().delete("/channelGroup/live/channel/feed/").then().statusCode(200);
        mp().delete("/channelGroup/live").then().statusCode(200);
        mp().get("/channelGroup/live").then().statusCode(404).body("ResourceTypeNotFound", equalTo("CHANNEL_GROUP"));
    }

    @Test
    void ingestedHlsIsServedAndHarvested() {
        mp().body("{\"ChannelGroupName\":\"origin\"}").post("/channelGroup").then().statusCode(200);
        String ingest = mp().body("{\"ChannelName\":\"cam\"}").post("/channelGroup/origin/channel").then()
                .statusCode(200).extract().path("IngestEndpoints[0].Url");
        String manifest = mp().body("{\"OriginEndpointName\":\"play\",\"ContainerType\":\"TS\","
                        + "\"StartoverWindowSeconds\":600,\"HlsManifests\":[{\"ManifestName\":\"index\","
                        + "\"ChildManifestName\":\"child\"}]}").post("/channelGroup/origin/channel/cam/originEndpoint")
                .then().statusCode(200).extract().path("HlsManifests[0].Url");
        at(manifest).get(path(manifest)).then().statusCode(404);

        at(ingest).body(segment(1)).put(path(ingest) + "index_0_00000.ts").then().statusCode(200);
        at(ingest).body(segment(2)).put(path(ingest) + "index_0_00001.ts").then().statusCode(200);
        at(ingest).body("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:2.0,\n"
                + "index_0_00000.ts\n#EXTINF:2.0,\nindex_0_00001.ts\n").put(path(ingest) + "index_0.m3u8").then()
                .statusCode(200);
        at(ingest).body("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nindex_0.m3u8\n")
                .put(path(ingest) + "index.m3u8").then().statusCode(200);

        String multivariant = at(manifest).get(path(manifest)).then().statusCode(200).extract().asString();
        assertTrue(multivariant.contains("BANDWIDTH=800000,RESOLUTION=640x360"), multivariant);
        assertTrue(multivariant.contains("child_1.m3u8"), multivariant);
        String base = path(manifest).substring(0, path(manifest).lastIndexOf('/') + 1);
        String child = at(manifest).get(base + "child_1.m3u8").then().statusCode(200).extract().asString();
        assertTrue(child.contains("#EXT-X-MEDIA-SEQUENCE:0"), child);
        assertTrue(child.contains("segment_1_1.ts"), child);
        byte[] served = at(manifest).get(base + "segment_1_1.ts").then().statusCode(200).extract().asByteArray();
        assertArrayEquals(segment(2), served);

        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/s3/"
                + "aws4_request").put("/mp-harvest").then().statusCode(200);
        double now = System.currentTimeMillis() / 1000.0;
        String job = mp().body("{\"HarvestJobName\":\"clip\",\"HarvestedManifests\":{\"HlsManifests\":[{"
                        + "\"ManifestName\":\"index\"}]},\"ScheduleConfiguration\":{\"StartTime\":" + (now - 30)
                        + ",\"EndTime\":" + (now + 1) + "},\"Destination\":{\"S3Destination\":{\"BucketName\":"
                        + "\"mp-harvest\",\"DestinationPath\":\"clips/\"}}}")
                .post("/channelGroup/origin/channel/cam/originEndpoint/play/harvestJob").then().statusCode(200)
                .body("Status", equalTo("QUEUED")).extract().path("HarvestJobName");
        await().atMost(Duration.ofSeconds(10)).until(() -> "COMPLETED".equals(mp().get(
                "/channelGroup/origin/channel/cam/originEndpoint/play/harvestJob/" + job).then().extract()
                .path("Status")));
        String listing = given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/"
                + "us-east-1/s3/aws4_request").get("/mp-harvest?list-type=2&prefix=clips/").then().statusCode(200)
                .extract().asString();
        assertTrue(listing.contains("<Key>clips/index.m3u8</Key>"), listing);
        assertTrue(listing.contains("<Key>clips/child_1.m3u8</Key>"), listing);
        assertTrue(listing.contains("<Key>clips/segment_1_0.ts</Key>"), listing);
        String vod = new String(given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/"
                + "us-east-1/s3/aws4_request").get("/mp-harvest/clips/child_1.m3u8").then().statusCode(200).extract()
                .asByteArray(), StandardCharsets.UTF_8);
        assertTrue(vod.contains("#EXT-X-ENDLIST"), vod);
        mp().put("/channelGroup/origin/channel/cam/originEndpoint/play/harvestJob/" + job).then().statusCode(409);
        mp().get("/channelGroup/origin/harvestJob?Status=COMPLETED").then().body("Items.HarvestJobName",
                hasItem("clip"));
        mp().body("{\"HarvestedManifests\":{\"HlsManifests\":[{\"ManifestName\":\"index\"}]},"
                + "\"ScheduleConfiguration\":{\"StartTime\":" + (now - 7200) + ",\"EndTime\":" + (now - 7000)
                + "},\"Destination\":{\"S3Destination\":{\"BucketName\":\"mp-harvest\",\"DestinationPath\":\"x/\"}}}")
                .post("/channelGroup/origin/channel/cam/originEndpoint/play/harvestJob").then().statusCode(400);

        mp().post("/channelGroup/origin/channel/cam/reset").then().statusCode(200).body("ResetAt",
                not(equalTo(null)));
        at(manifest).get(path(manifest)).then().statusCode(404);
        mp().delete("/channelGroup/origin/channel/cam/originEndpoint/play").then().statusCode(200);
        mp().delete("/channelGroup/origin/channel/cam/").then().statusCode(200);
        mp().delete("/channelGroup/origin").then().statusCode(200);
    }
}
