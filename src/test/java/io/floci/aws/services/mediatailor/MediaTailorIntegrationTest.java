package io.floci.aws.services.mediatailor;

import com.sun.net.httpserver.HttpServer;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MediaTailor: playback configurations, prefetch schedules, functions and channel assembly's resources; ad insertion
 * in VOD and live HLS with ads from a VAST or VMAP ad decision server (an HTTP server in the test, which also serves
 * the origin, the ads and the beacons), server- and client-side reporting, prefetched ads, and a running channel's
 * output with its ad breaks filled by a playback configuration.
 */
@QuarkusTest
class MediaTailorIntegrationTest {

    static HttpServer server;
    static String base;
    static final Map<String, Supplier<byte[]>> FILES = new ConcurrentHashMap<>();
    static final List<String> HITS = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws IOException {
        RestAssuredJsonUtils.configureAwsContentTypes();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            HITS.add(exchange.getRequestURI().toString());
            Supplier<byte[]> file = FILES.get(path);
            byte[] body = file == null ? new byte[0] : file.get();
            exchange.sendResponseHeaders(file == null ? (path.startsWith("/beacon/") ? 200 : 404) : 200,
                    body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    static void serve(String path, String text) {
        FILES.put(path, () -> text.getBytes(StandardCharsets.UTF_8));
    }

    static RequestSpecification mt() {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/mediatailor/aws4_request")
                .contentType("application/json");
    }

    /** A request to a URL MediaTailor gave, by its path and Host. */
    static RequestSpecification at(String url) {
        URI uri = URI.create(url);
        return given().header("Host", uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort()));
    }

    static String path(String url) {
        return URI.create(url).getRawPath();
    }

    static String get(String url) {
        URI uri = URI.create(url);
        return at(url).get(uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())).then()
                .statusCode(200).extract().asString();
    }

    static byte[] segment(int seed) {
        byte[] data = new byte[188 * 4];
        for (int i = 0; i < data.length; i += 188) {
            data[i] = 0x47;
            data[i + 1] = (byte) seed;
        }
        return data;
    }

    static String vast(String id, String adPlaylist, double seconds, String beacons) {
        return "<VAST version=\"3.0\"><Ad id=\"" + id + "\" sequence=\"1\"><InLine><AdSystem>Test</AdSystem><AdTitle>"
                + id + "</AdTitle><Impression><![CDATA[" + beacons + "/impression?ad=" + id + "]]></Impression>"
                + "<Creatives><Creative id=\"c-" + id + "\"><Linear><Duration>00:00:0" + (int) seconds + "</Duration>"
                + "<TrackingEvents><Tracking event=\"start\"><![CDATA[" + beacons + "/start?ad=" + id + "]]></Tracking>"
                + "<Tracking event=\"midpoint\"><![CDATA[" + beacons + "/midpoint?ad=" + id + "]]></Tracking>"
                + "<Tracking event=\"complete\"><![CDATA[" + beacons + "/complete?ad=" + id + "]]></Tracking>"
                + "</TrackingEvents><MediaFiles><MediaFile delivery=\"streaming\" type=\"application/x-mpegURL\" "
                + "width=\"640\" height=\"360\"><![CDATA[" + adPlaylist + "]]></MediaFile></MediaFiles></Linear>"
                + "</Creative></Creatives></InLine></Ad></VAST>";
    }

    /** An HLS ad of {@code count} one-second segments. */
    static String hlsAd(String name, int count, int seed) {
        StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n"
                + "#EXT-X-MEDIA-SEQUENCE:0\n");
        for (int i = 0; i < count; i++) {
            byte[] bytes = segment(seed + i);
            FILES.put("/ads/" + name + "/" + i + ".ts", () -> bytes);
            playlist.append("#EXTINF:1.000,\n").append(i).append(".ts\n");
        }
        serve("/ads/" + name + "/index.m3u8", playlist.append("#EXT-X-ENDLIST\n").toString());
        return base + "/ads/" + name + "/index.m3u8";
    }

    static String vodContent(String name, String markers) {
        StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n"
                + "#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        for (int i = 0; i < 6; i++) {
            byte[] bytes = segment(100 + i);
            FILES.put("/" + name + "/seg" + i + ".ts", () -> bytes);
            if (markers != null && i == 2) {
                playlist.append("#EXT-X-CUE-OUT:4.000\n");
            }
            if (markers != null && i == 4) {
                playlist.append("#EXT-X-CUE-IN\n");
            }
            playlist.append("#EXTINF:2.000,\nseg").append(i).append(".ts\n");
        }
        serve("/" + name + "/media.m3u8", playlist.append("#EXT-X-ENDLIST\n").toString());
        serve("/" + name + "/index.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,"
                + "CODECS=\"avc1.4d401e,mp4a.40.2\"\nmedia.m3u8\n");
        return base + "/" + name;
    }

    static Map<String, Object> config(String body) {
        return mt().body(body).put("/playbackConfiguration").then().statusCode(200).extract().path("$");
    }

    static String manifestPrefix(Map<String, Object> config) {
        return (String) ((Map<?, ?>) config.get("HlsConfiguration")).get("ManifestEndpointPrefix");
    }

    static List<String> lines(String playlist, String contains) {
        return playlist.lines().filter(line -> line.contains(contains)).toList();
    }

    static long discontinuities(String playlist) {
        return playlist.lines().filter("#EXT-X-DISCONTINUITY"::equals).count();
    }

    static long hits(String fragment) {
        return HITS.stream().filter(hit -> hit.contains(fragment)).count();
    }

    // ================================================================ control plane

    @Test
    void playbackConfigurationsPrefetchSchedulesAndFunctions() {
        Map<String, Object> created = config("{\"Name\":\"cfg\",\"AdDecisionServerUrl\":\"https://ads.example.com/"
                + "vast?ip=[session.client_ip]\",\"VideoContentSourceUrl\":\"https://origin.example.com/live\","
                + "\"SlateAdUrl\":\"https://origin.example.com/slate.mp4\",\"tags\":{\"Environment\":\"test\"}}");
        assertTrue(((String) created.get("PlaybackConfigurationArn")).endsWith(":playbackConfiguration/cfg"));
        assertTrue(((String) created.get("PlaybackEndpointPrefix")).contains(".mediatailor.us-east-1."));
        assertTrue(((String) created.get("SessionInitializationEndpointPrefix")).contains("/v1/session/"));
        assertTrue(manifestPrefix(created).contains("/v1/master/"));
        mt().get("/playbackConfiguration/cfg").then().statusCode(200)
                .body("SlateAdUrl", equalTo("https://origin.example.com/slate.mp4"))
                .body("tags.Environment", equalTo("test"))
                .body("LogConfiguration.PercentEnabled", equalTo(0))
                .body("AvailSuppression.Mode", equalTo("OFF"))
                .body("DashConfiguration.MpdLocation", equalTo("EMT_DEFAULT"))
                .body("ManifestProcessingRules.AdMarkerPassthrough.Enabled", equalTo(false));
        config("{\"Name\":\"cfg\",\"AdDecisionServerUrl\":\"https://ads.example.com/v2\",\"VideoContentSourceUrl\":"
                + "\"https://origin.example.com/live\",\"PersonalizationThresholdSeconds\":2,\"ManifestProcessingRules\":"
                + "{\"AdMarkerPassthrough\":{\"Enabled\":true}},\"AvailSuppression\":{\"Mode\":\"BEHIND_LIVE_EDGE\","
                + "\"Value\":\"00:00:30\"}}");
        mt().body("{\"PlaybackConfigurationName\":\"cfg\",\"PercentEnabled\":10}").put(
                "/configureLogs/playbackConfiguration").then().statusCode(200).body("PercentEnabled", equalTo(10));
        mt().get("/playbackConfiguration/cfg").then().statusCode(200).body("SlateAdUrl", nullValue())
                .body("PersonalizationThresholdSeconds", equalTo(2)).body("AvailSuppression.Value",
                        equalTo("00:00:30")).body("LogConfiguration.PercentEnabled", equalTo(10));
        mt().body("{\"Name\":\"bad\",\"AvailSuppression\":{\"Mode\":\"BEHIND_LIVE_EDGE\",\"Value\":\"30s\"}}")
                .put("/playbackConfiguration").then().statusCode(400)
                .header("x-amzn-ErrorType", containsString("BadRequestException"));
        String arn = (String) created.get("PlaybackConfigurationArn");
        mt().body("{\"tags\":{\"Purpose\":\"test\"}}").post("/tags/" + arn).then().statusCode(lessThan(300));
        mt().queryParam("tagKeys", "Environment").delete("/tags/" + arn).then().statusCode(lessThan(300));
        mt().get("/tags/" + arn).then().statusCode(200).body("tags.Purpose", equalTo("test"))
                .body("tags.Environment", nullValue());
        mt().get("/playbackConfigurations").then().statusCode(200).body("Items.Name", hasItem("cfg"));

        double now = System.currentTimeMillis() / 1000.0;
        mt().body("{\"Retrieval\":{\"EndTime\":" + (now + 600) + "},\"Consumption\":{\"EndTime\":" + (now + 1200)
                        + "}}").post("/prefetchSchedule/cfg/pre").then().statusCode(200)
                .body("Arn", containsString(":prefetchSchedule/cfg/pre")).body("ScheduleType", equalTo("SINGLE"));
        mt().body("{\"Retrieval\":{\"EndTime\":" + (now + 600) + "},\"Consumption\":{\"EndTime\":" + (now + 1200)
                + "}}").post("/prefetchSchedule/cfg/pre").then().statusCode(400);
        mt().body("{\"Retrieval\":{\"EndTime\":" + (now + 600) + "},\"Consumption\":{\"EndTime\":" + (now + 100)
                + "}}").post("/prefetchSchedule/cfg/early").then().statusCode(400);
        mt().body("{\"Retrieval\":{\"EndTime\":" + (now + 600) + "},\"Consumption\":{\"EndTime\":" + (now + 1200)
                + "}}").post("/prefetchSchedule/missing/pre").then().statusCode(404)
                .header("x-amzn-ErrorType", containsString("NotFoundException"));
        mt().get("/prefetchSchedule/cfg/pre").then().statusCode(200).body("Name", equalTo("pre"));
        mt().body("{}").post("/prefetchSchedule/cfg").then().statusCode(200).body("Items.Name", hasItem("pre"));
        mt().delete("/prefetchSchedule/cfg/pre").then().statusCode(lessThan(300));
        mt().get("/prefetchSchedule/cfg/pre").then().statusCode(404)
                .header("x-amzn-ErrorType", containsString("NotFoundException"));
        mt().delete("/prefetchSchedule/cfg/pre").then().statusCode(404);

        mt().body("{\"FunctionType\":\"CUSTOM_OUTPUT\",\"CustomOutputConfiguration\":{\"Runtime\":\"JSONATA\"}}").put("/function/f1")
                .then().statusCode(200).body("Arn", containsString(":function/f1"));
        mt().body("{\"FunctionType\":\"HTTP_REQUEST\"}").put("/function/f2").then().statusCode(400);
        mt().get("/functions").then().statusCode(200).body("Items.FunctionId", hasItem("f1"));
        mt().delete("/function/f1").then().statusCode(lessThan(300));
        mt().get("/function/f1").then().statusCode(404);

        mt().delete("/playbackConfiguration/cfg").then().statusCode(lessThan(300));
        mt().delete("/playbackConfiguration/cfg").then().statusCode(lessThan(300));
        mt().get("/playbackConfiguration/cfg").then().statusCode(404)
                .header("x-amzn-ErrorType", containsString("NotFoundException"))
                .body(containsString("not found"));
    }

    @Test
    void channelAssemblyResources() {
        String source = vodContent("ca-src", null);
        mt().body("{\"HttpConfiguration\":{\"BaseUrl\":\"" + base + "\"}}").post("/sourceLocation/loc").then()
                .statusCode(200).body("Arn", containsString(":sourceLocation/loc"));
        mt().body("{\"HttpConfiguration\":{\"BaseUrl\":\"" + base + "\"}}").post("/sourceLocation/loc").then()
                .statusCode(400);
        mt().body("{\"HttpPackageConfigurations\":[{\"Path\":\"/ca-src/index.m3u8\",\"SourceGroup\":\"hls\","
                + "\"Type\":\"HLS\"}]}").post("/sourceLocation/loc/vodSource/vod").then().statusCode(200)
                .body("Arn", containsString(":vodSource/loc/vod"));
        mt().get("/sourceLocation/loc/vodSource/vod").then().statusCode(200).body("AdBreakOpportunities", empty());
        mt().body("{\"HttpPackageConfigurations\":[{\"Path\":\"/missing.m3u8\",\"SourceGroup\":\"hls\","
                + "\"Type\":\"HLS\"}]}").post("/sourceLocation/loc/vodSource/broken").then().statusCode(200);
        mt().body("{\"HttpPackageConfigurations\":[{\"Path\":\"/live.m3u8\",\"SourceGroup\":\"hls\","
                + "\"Type\":\"HLS\"}]}").post("/sourceLocation/loc/liveSource/live").then().statusCode(200);
        mt().get("/sourceLocation/loc/vodSources").then().statusCode(200).body("Items.VodSourceName", hasItem("vod"));
        mt().get("/sourceLocation/loc/liveSources").then().statusCode(200).body("Items", hasSize(1));
        mt().get("/sourceLocations").then().statusCode(200).body("Items.SourceLocationName", hasItem("loc"));

        mt().body("{\"PlaybackMode\":\"LOOP\",\"Outputs\":[{\"ManifestName\":\"index\",\"SourceGroup\":\"hls\","
                        + "\"HlsPlaylistSettings\":{}}],\"tags\":{\"team\":\"video\"}}").post("/channel/ch").then()
                .statusCode(200).body("ChannelState", equalTo("STOPPED"))
                .body("Outputs[0].PlaybackUrl", containsString("/v1/channel/ch/index.m3u8"))
                .body("Outputs[0].PlaybackUrl", containsString(".channel-assembly.mediatailor."));
        mt().body("{\"PlaybackMode\":\"LOOP\",\"FillerSlate\":{\"SourceLocationName\":\"loc\",\"VodSourceName\":"
                + "\"vod\"},\"Outputs\":[{\"ManifestName\":\"i\",\"SourceGroup\":\"hls\",\"HlsPlaylistSettings\":{}}]}")
                .post("/channel/bad").then().statusCode(400);
        mt().body("{\"Policy\":\"{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[]}\"}")
                .put("/channel/ch/policy").then().statusCode(200);
        mt().get("/channel/ch/policy").then().statusCode(200).body("Policy", containsString("Statement"));
        mt().delete("/channel/ch/policy").then().statusCode(lessThan(300));
        mt().get("/channel/ch/policy").then().statusCode(404);

        mt().body("{\"SourceLocationName\":\"loc\",\"VodSourceName\":\"vod\",\"ScheduleConfiguration\":{"
                        + "\"Transition\":{\"Type\":\"RELATIVE\",\"RelativePosition\":\"AFTER_PROGRAM\"}},\"AdBreaks\":[{"
                        + "\"OffsetMillis\":4000,\"Slate\":{\"SourceLocationName\":\"loc\",\"VodSourceName\":\"vod\"}}]}")
                .post("/channel/ch/program/p1").then().statusCode(200).body("DurationMillis", equalTo(12000))
                .body("Arn", containsString(":program/ch/p1"));
        mt().body("{\"SourceLocationName\":\"loc\",\"VodSourceName\":\"broken\",\"ScheduleConfiguration\":{"
                + "\"Transition\":{\"Type\":\"RELATIVE\",\"RelativePosition\":\"AFTER_PROGRAM\"}}}")
                .post("/channel/ch/program/p2").then().statusCode(400).body(containsString("couldn't read"));
        mt().body("{\"SourceLocationName\":\"loc\",\"VodSourceName\":\"vod\",\"ScheduleConfiguration\":{"
                + "\"Transition\":{\"Type\":\"ABSOLUTE\",\"ScheduledStartTimeMillis\":1}}}")
                .post("/channel/ch/program/p3").then().statusCode(400);
        mt().body("{\"SourceLocationName\":\"loc\",\"VodSourceName\":\"vod\",\"ScheduleConfiguration\":{"
                        + "\"Transition\":{\"Type\":\"RELATIVE\",\"RelativePosition\":\"AFTER_PROGRAM\"},\"ClipRange\":"
                        + "{\"EndOffsetMillis\":4000}}}")
                .post("/channel/ch/program/clip").then().statusCode(200).body("DurationMillis", equalTo(4000));
        mt().get("/channel/ch/program/p1").then().statusCode(200).body("ProgramName", equalTo("p1"));
        mt().body("{\"AdBreaks\":[],\"ScheduleConfiguration\":{\"Transition\":{}}}").put("/channel/ch/program/p1")
                .then().statusCode(200);
        mt().get("/channel/ch/schedule?durationMinutes=1").then().statusCode(200)
                .body("Items.ProgramName", hasItem("p1")).body("Items.ProgramName", hasItem("clip"));
        mt().get("/channel/missing/schedule").then().statusCode(404)
                .header("x-amzn-ErrorType", containsString("NotFoundException"));
        String channelArn = mt().get("/channel/ch").then().extract().path("Arn");
        mt().queryParam("resourceArn", channelArn).get("/alerts").then().statusCode(200).body("Items", empty());
        mt().get("/tags/" + channelArn).then().statusCode(200).body("tags.team", equalTo("video"));

        mt().put("/channel/ch/start").then().statusCode(200);
        mt().get("/channel/ch").then().body("ChannelState", equalTo("RUNNING"));
        mt().delete("/channel/ch").then().statusCode(400);
        mt().body("{\"Outputs\":[{\"ManifestName\":\"index\",\"SourceGroup\":\"hls\",\"HlsPlaylistSettings\":{}}]}")
                .put("/channel/ch").then().statusCode(400);
        mt().put("/channel/ch/stop").then().statusCode(200);
        mt().delete("/sourceLocation/loc/vodSource/vod").then().statusCode(400);
        mt().delete("/channel/ch/program/p1").then().statusCode(lessThan(300));
        mt().get("/channel/ch/program/p1").then().statusCode(404);
        mt().delete("/channel/ch").then().statusCode(lessThan(300));
        mt().get("/channel/ch").then().statusCode(404);
        mt().delete("/sourceLocation/loc").then().statusCode(400);
        mt().delete("/sourceLocation/loc/vodSource/vod").then().statusCode(lessThan(300));
        mt().delete("/sourceLocation/loc/vodSource/broken").then().statusCode(lessThan(300));
        mt().delete("/sourceLocation/loc/liveSource/live").then().statusCode(lessThan(300));
        mt().delete("/sourceLocation/loc").then().statusCode(lessThan(300));
        assertTrue(source.startsWith(base));
    }

    @Test
    void listAlertsTakesOnlyChannelAssemblyResources() {
        String arn = (String) config("{\"Name\":\"alerts\",\"VideoContentSourceUrl\":\"https://origin.example.com\"}")
                .get("PlaybackConfigurationArn");
        mt().queryParam("resourceArn", arn).get("/alerts").then().statusCode(400)
                .header("x-amzn-ErrorType", containsString("BadRequestException"));
        mt().delete("/playbackConfiguration/alerts").then().statusCode(lessThan(300));
    }

    // ================================================================ ad insertion

    @Test
    void vodAvailsAreFilledAndServerSideBeaconsFire() {
        String content = vodContent("vod1", "cue");
        String ad = hlsAd("vod1", 4, 1);
        serve("/ads/vod1.xml", vast("ad1", ad, 4, base + "/beacon"));
        Map<String, Object> config = config("{\"Name\":\"vod\",\"AdDecisionServerUrl\":\"" + base
                + "/ads/vod1.xml?session=[session.id]&duration=[session.avail_duration_secs]\","
                + "\"VideoContentSourceUrl\":\"" + content + "\",\"AdConditioningConfiguration\":{"
                + "\"StreamingMediaFileConditioning\":\"NONE\"}}");
        String master = get(manifestPrefix(config) + "index.m3u8");
        assertTrue(master.contains("BANDWIDTH=800000,RESOLUTION=640x360"), master);
        String child = master.lines().filter(line -> line.endsWith(".m3u8")).findFirst().orElseThrow();
        assertTrue(child.contains("manifest/"), child);
        String mediaUrl = URI.create(manifestPrefix(config) + "index.m3u8").resolve(child).toString();
        String media = get(mediaUrl);
        List<String> ads = lines(media, "/v1/segment/");
        assertEquals(4, ads.size(), media);
        assertTrue(media.contains(base + "/vod1/seg0.ts") && media.contains(base + "/vod1/seg5.ts"), media);
        assertFalse(media.contains("seg2.ts") || media.contains("seg3.ts"), media);
        assertFalse(media.contains("CUE-OUT"), media);
        assertEquals(2, discontinuities(media), media);
        assertTrue(media.contains("#EXT-X-ENDLIST"), media);
        assertTrue(hits("/ads/vod1.xml?session=") > 0 && hits("duration=4") > 0, HITS.toString());

        byte[] first = at(ads.get(0)).get(path(ads.get(0))).then().statusCode(200).extract().asByteArray();
        assertArrayEquals(segment(1), first);
        await().atMost(Duration.ofSeconds(10)).until(() -> hits("/beacon/impression?ad=ad1") == 1
                && hits("/beacon/start?ad=ad1") == 1);
        assertEquals(0, hits("/beacon/complete?ad=ad1"));
        for (String segment : ads.subList(1, 4)) {
            at(segment).get(path(segment)).then().statusCode(200);
        }
        at(ads.get(0)).get(path(ads.get(0))).then().statusCode(200);
        await().atMost(Duration.ofSeconds(10)).until(() -> hits("/beacon/midpoint?ad=ad1") == 1
                && hits("/beacon/complete?ad=ad1") == 1);
        assertEquals(1, hits("/beacon/impression?ad=ad1"));
        mt().delete("/playbackConfiguration/vod").then().statusCode(lessThan(300));
    }

    @Test
    void liveWindowsKeepTheirSequenceAcrossAds() {
        int[] edge = {10};
        FILES.put("/live/media.m3u8", () -> {
            StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n"
                    + "#EXT-X-MEDIA-SEQUENCE:" + (edge[0] - 4) + "\n");
            for (int msn = edge[0] - 4; msn <= edge[0]; msn++) {
                if (msn == 10) {
                    playlist.append("#EXT-X-CUE-OUT:4\n");
                }
                if (msn == 11) {
                    playlist.append("#EXT-X-CUE-OUT-CONT:2/4\n");
                }
                if (msn == 12) {
                    playlist.append("#EXT-X-CUE-IN\n");
                }
                playlist.append("#EXTINF:2.0,\nseg").append(msn).append(".ts\n");
            }
            return playlist.toString().getBytes(StandardCharsets.UTF_8);
        });
        String ad = hlsAd("live", 4, 50);
        serve("/ads/live.xml", vast("live1", ad, 4, base + "/beacon"));
        Map<String, Object> config = config("{\"Name\":\"live\",\"AdDecisionServerUrl\":\"" + base + "/ads/live.xml\","
                + "\"VideoContentSourceUrl\":\"" + base + "/live\",\"AdConditioningConfiguration\":{"
                + "\"StreamingMediaFileConditioning\":\"NONE\"},\"ManifestProcessingRules\":{\"AdMarkerPassthrough\":"
                + "{\"Enabled\":true}}}");
        String sessionUrl = manifestPrefix(config) + "media.m3u8";
        String first = get(sessionUrl);
        assertTrue(first.contains("#EXT-X-MEDIA-SEQUENCE:6"), first);
        assertEquals(2, lines(first, "/v1/segment/").size(), first);
        assertTrue(first.contains("#EXT-X-CUE-OUT:4"), first);
        String id = lines(first, "/v1/segment/").get(0).replaceAll(".*/live/([^/]+)/.*", "$1");
        String sessionMedia = manifestPrefix(config) + "media.m3u8?aws.sessionId=" + id;
        edge[0] = 12;
        String second = get(sessionMedia);
        assertTrue(second.contains("#EXT-X-MEDIA-SEQUENCE:8"), second);
        assertEquals(4, lines(second, "/v1/segment/").size(), second);
        assertTrue(second.contains(base + "/live/seg12.ts"), second);
        assertFalse(second.contains("seg10.ts") || second.contains("seg11.ts"), second);
        assertEquals(2, discontinuities(second), second);
        assertTrue(second.contains("#EXT-X-DISCONTINUITY-SEQUENCE:0"), second);
        edge[0] = 17;
        String third = get(sessionMedia);
        assertTrue(third.contains("#EXT-X-MEDIA-SEQUENCE:15"), third);
        assertTrue(third.contains("#EXT-X-DISCONTINUITY-SEQUENCE:2"), third);
        assertEquals(0, lines(third, "/v1/segment/").size(), third);
        assertEquals(1, hits("/ads/live.xml"));
        mt().delete("/playbackConfiguration/live").then().statusCode(lessThan(300));
    }

    @Test
    void explicitSessionsReportClientSide() {
        String content = vodContent("vod2", "cue");
        String ad = hlsAd("vod2", 4, 20);
        serve("/ads/vod2.xml", vast("ad2", ad, 4, base + "/beacon2"));
        Map<String, Object> config = config("{\"Name\":\"client\",\"AdDecisionServerUrl\":\"" + base
                + "/ads/vod2.xml?device=[player_params.deviceType]\",\"VideoContentSourceUrl\":\"" + content + "\","
                + "\"AdConditioningConfiguration\":{\"StreamingMediaFileConditioning\":\"NONE\"}}");
        String init = (String) config.get("SessionInitializationEndpointPrefix");
        Map<String, Object> session = at(init).contentType("application/json")
                .body("{\"reportingMode\":\"client\",\"adsParams\":{\"deviceType\":\"tv\"}}")
                .post(path(init) + "index.m3u8").then().statusCode(200).extract().path("$");
        String manifest = (String) session.get("manifestUrl");
        String tracking = (String) session.get("trackingUrl");
        assertTrue(manifest.contains("/v1/master/") && manifest.contains("aws.sessionId="), manifest);
        String host = (String) config.get("PlaybackEndpointPrefix");
        String master = get(URI.create(host).resolve(manifest).toString());
        String child = master.lines().filter(line -> line.endsWith(".m3u8")).findFirst().orElseThrow();
        String media = get(URI.create(host).resolve(manifest).resolve(child).toString());
        List<String> ads = lines(media, "/v1/segment/");
        assertEquals(4, ads.size(), media);
        assertEquals(1, hits("/ads/vod2.xml?device=tv"));
        ads.forEach(segment -> at(segment).get(path(segment)).then().statusCode(200));
        Map<String, Object> avails = at(host).get(URI.create(host).resolve(tracking).getRawPath()).then()
                .statusCode(200).extract().path("$");
        List<?> list = (List<?>) avails.get("avails");
        assertEquals(1, list.size(), avails.toString());
        Map<?, ?> avail = (Map<?, ?>) list.get(0);
        assertEquals(4.0, ((Number) avail.get("durationInSeconds")).doubleValue());
        assertEquals(4.0, ((Number) avail.get("startTimeInSeconds")).doubleValue());
        Map<?, ?> first = (Map<?, ?>) ((List<?>) avail.get("ads")).get(0);
        assertEquals("ad2", first.get("adId"));
        String events = first.get("trackingEvents").toString();
        assertTrue(events.contains("impression") && events.contains(base + "/beacon2/complete?ad=ad2"), events);
        assertEquals(0, hits("/beacon2/"));
        mt().delete("/playbackConfiguration/client").then().statusCode(lessThan(300));
    }

    @Test
    void vodWithoutMarkersPlaysVmapBreaks() {
        String content = vodContent("vod3", null);
        String ad = hlsAd("vod3", 2, 70);
        String inline = vast("v", ad, 2, base + "/beacon3");
        serve("/ads/vmap.xml", "<vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\" version=\"1.0\">"
                + breakXml("start", inline) + breakXml("00:00:04.000", inline) + breakXml("end", inline)
                + "</vmap:VMAP>");
        Map<String, Object> config = config("{\"Name\":\"vmap\",\"AdDecisionServerUrl\":\"" + base
                + "/ads/vmap.xml\",\"VideoContentSourceUrl\":\"" + content + "\",\"AdConditioningConfiguration\":{"
                + "\"StreamingMediaFileConditioning\":\"NONE\"}}");
        String media = get(manifestPrefix(config) + "media.m3u8");
        List<String> uris = media.lines().filter(line -> !line.startsWith("#")).toList();
        assertEquals(12, uris.size(), media);
        assertTrue(uris.get(0).contains("/v1/segment/") && uris.get(1).contains("/v1/segment/"), media);
        assertEquals(base + "/vod3/seg0.ts", uris.get(2), media);
        assertTrue(uris.get(4).contains("/v1/segment/"), media);
        assertEquals(base + "/vod3/seg2.ts", uris.get(6), media);
        assertTrue(uris.get(11).contains("/v1/segment/"), media);
        mt().delete("/playbackConfiguration/vmap").then().statusCode(lessThan(300));
    }

    static String breakXml(String offset, String vast) {
        return "<vmap:AdBreak timeOffset=\"" + offset + "\" breakType=\"linear\"><vmap:AdSource><vmap:VASTAdData>"
                + vast + "</vmap:VASTAdData></vmap:AdSource></vmap:AdBreak>";
    }

    @Test
    void prefetchedAdsFillMatchingAvails() {
        String content = vodContent("vod4", "cue");
        String ad = hlsAd("vod4", 4, 90);
        serve("/ads/pre.xml", vast("pre", ad, 4, base + "/beacon4"));
        config("{\"Name\":\"prefetch\",\"AdDecisionServerUrl\":\"" + base
                + "/ads/pre.xml?scene=[player_params.scene]\",\"VideoContentSourceUrl\":\"" + content + "\","
                + "\"AdConditioningConfiguration\":{\"StreamingMediaFileConditioning\":\"NONE\"}}");
        double now = System.currentTimeMillis() / 1000.0;
        mt().body("{\"Retrieval\":{\"EndTime\":" + (now + 60) + ",\"DynamicVariables\":{\"player_params.scene\":"
                + "\"car\"}},\"Consumption\":{\"EndTime\":" + (now + 120) + ",\"AvailMatchingCriteria\":[{"
                + "\"DynamicVariable\":\"player_params.scene\",\"Operator\":\"EQUALS\"}]}}")
                .post("/prefetchSchedule/prefetch/cars").then().statusCode(200);
        await().atMost(Duration.ofSeconds(10)).until(() -> hits("/ads/pre.xml?scene=car") == 1);
        String prefix = mt().get("/playbackConfiguration/prefetch").then().extract()
                .path("HlsConfiguration.ManifestEndpointPrefix");
        String media = get(prefix + "media.m3u8?ads.scene=car");
        assertEquals(4, lines(media, "/v1/segment/").size(), media);
        assertEquals(1, hits("/ads/pre.xml?scene=car"));
        String other = get(prefix + "media.m3u8?ads.scene=bike");
        assertEquals(4, lines(other, "/v1/segment/").size(), other);
        assertEquals(1, hits("/ads/pre.xml?scene=bike"));
        mt().delete("/playbackConfiguration/prefetch").then().statusCode(lessThan(300));
        mt().get("/prefetchSchedule/prefetch/cars").then().statusCode(404);
    }

    @Test
    void runningChannelsServeTheirScheduleWithFillableBreaks() {
        vodContent("show", null);
        StringBuilder slate = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n"
                + "#EXT-X-PLAYLIST-TYPE:VOD\n");
        byte[] slateBytes = segment(200);
        FILES.put("/slate/s0.ts", () -> slateBytes);
        slate.append("#EXTINF:2.000,\ns0.ts\n#EXT-X-ENDLIST\n");
        serve("/slate/media.m3u8", slate.toString());
        serve("/slate/index.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nmedia.m3u8\n");
        mt().body("{\"HttpConfiguration\":{\"BaseUrl\":\"" + base + "\"}}").post("/sourceLocation/tv").then()
                .statusCode(200);
        mt().body("{\"HttpPackageConfigurations\":[{\"Path\":\"show/index.m3u8\",\"SourceGroup\":\"hls\","
                + "\"Type\":\"HLS\"}]}").post("/sourceLocation/tv/vodSource/show").then().statusCode(200);
        mt().body("{\"HttpPackageConfigurations\":[{\"Path\":\"slate/index.m3u8\",\"SourceGroup\":\"hls\","
                + "\"Type\":\"HLS\"}]}").post("/sourceLocation/tv/vodSource/slate").then().statusCode(200);
        String playback = mt().body("{\"PlaybackMode\":\"LOOP\",\"Outputs\":[{\"ManifestName\":\"index\","
                        + "\"SourceGroup\":\"hls\",\"HlsPlaylistSettings\":{\"ManifestWindowSeconds\":60,"
                        + "\"AdMarkupType\":[\"DATERANGE\",\"SCTE35_ENHANCED\"]}}]}").post("/channel/tv").then()
                .statusCode(200).extract().path("Outputs[0].PlaybackUrl");
        mt().body("{\"SourceLocationName\":\"tv\",\"VodSourceName\":\"show\",\"ScheduleConfiguration\":{"
                + "\"Transition\":{\"Type\":\"RELATIVE\",\"RelativePosition\":\"AFTER_PROGRAM\"}},\"AdBreaks\":[{"
                + "\"OffsetMillis\":2000,\"Slate\":{\"SourceLocationName\":\"tv\",\"VodSourceName\":\"slate\"}}]}")
                .post("/channel/tv/program/show").then().statusCode(200);
        at(playback).get(path(playback)).then().statusCode(404);
        mt().put("/channel/tv/start").then().statusCode(200);
        String multivariant = get(playback);
        assertTrue(multivariant.contains("index_0.m3u8"), multivariant);
        String trackUrl = URI.create(playback).resolve("index_0.m3u8").toString();
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500)).until(() ->
                get(trackUrl).contains(base + "/show/seg1.ts"));
        String track = get(trackUrl);
        assertTrue(track.contains("#EXT-X-PROGRAM-DATE-TIME:"), track);
        assertTrue(track.contains(base + "/show/seg0.ts"), track);
        assertTrue(track.contains("SCTE35-OUT=0xFC") && track.contains("PLANNED-DURATION=2.000"), track);
        assertTrue(track.contains("#EXT-X-CUE-OUT:2.000") && track.contains(base + "/slate/s0.ts"), track);
        mt().get("/channel/tv/schedule?durationMinutes=1").then().statusCode(200)
                .body("Items[0].ProgramName", equalTo("show"))
                .body("Items[0].ApproximateDurationSeconds", equalTo(14))
                .body("Items[0].ScheduleAdBreaks", hasSize(1));

        String ad = hlsAd("channel", 2, 150);
        serve("/ads/channel.xml", vast("ch", ad, 2, base + "/beacon5"));
        Map<String, Object> config = config("{\"Name\":\"tv\",\"AdDecisionServerUrl\":\"" + base
                + "/ads/channel.xml\",\"VideoContentSourceUrl\":\"" + playback.substring(0, playback.lastIndexOf('/'))
                + "\",\"AdConditioningConfiguration\":{\"StreamingMediaFileConditioning\":\"NONE\"}}");
        String master = get(manifestPrefix(config) + "index.m3u8");
        String child = master.lines().filter(line -> line.endsWith(".m3u8")).findFirst().orElseThrow();
        String stitched = get(URI.create(manifestPrefix(config) + "index.m3u8").resolve(child).toString());
        assertEquals(2, lines(stitched, "/v1/segment/").size(), stitched);
        assertFalse(stitched.contains("/slate/s0.ts"), stitched);
        assertTrue(stitched.contains(base + "/show/seg0.ts"), stitched);

        mt().put("/channel/tv/stop").then().statusCode(200);
        at(playback).get(path(playback)).then().statusCode(404);
        mt().delete("/playbackConfiguration/tv").then().statusCode(lessThan(300));
        mt().delete("/channel/tv/program/show").then().statusCode(lessThan(300));
        mt().delete("/channel/tv").then().statusCode(lessThan(300));
        mt().delete("/sourceLocation/tv/vodSource/show").then().statusCode(lessThan(300));
        mt().delete("/sourceLocation/tv/vodSource/slate").then().statusCode(lessThan(300));
        mt().delete("/sourceLocation/tv").then().statusCode(lessThan(300));
    }
}
