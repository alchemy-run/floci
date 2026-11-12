package io.floci.aws.services.mediatailor;

import io.floci.aws.services.kinesisvideo.KvsTranscoder;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ads transcoded for the content: an MP4 creative of another size is conditioned with ffmpeg to each track's size,
 * bitrate and segment duration, as TS for TS content and as fragmented MP4 with its own init section for CMAF
 * content; ffprobe reads the stitched ad segments.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@Tag("docker")
class MediaTailorDockerIntegrationTest {

    @Inject
    KvsTranscoder ffmpeg;

    @BeforeAll
    static void start() throws IOException {
        MediaTailorIntegrationTest.start();
    }

    @AfterAll
    static void stop() {
        MediaTailorIntegrationTest.stop();
    }

    private void content(String name, boolean fragmented) {
        Map<String, byte[]> files = ffmpeg.run(new LinkedHashMap<>(), "ffmpeg -hide_banner -loglevel error -f lavfi "
                + "-i testsrc=size=640x360:rate=25 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 12 -c:v libx264 "
                + "-g 50 -keyint_min 50 -sc_threshold 0 -pix_fmt yuv420p -b:v 600k -c:a aac -f hls -hls_time 2 "
                + "-hls_list_size 0 -hls_playlist_type vod" + (fragmented ? " -hls_segment_type fmp4 "
                + "-hls_fmp4_init_filename init.mp4 -hls_segment_filename 's%d.m4s'" : " -hls_segment_filename "
                + "'s%d.ts'") + " media.m3u8");
        files.forEach((file, bytes) -> MediaTailorIntegrationTest.FILES.put("/" + name + "/" + file, () -> bytes));
        StringBuilder media = new StringBuilder();
        int segment = 0;
        for (String line : new String(files.get("media.m3u8"), StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith("#EXTINF")) {
                if (segment == 2) {
                    media.append("#EXT-X-CUE-OUT:4.000\n");
                } else if (segment == 4) {
                    media.append("#EXT-X-CUE-IN\n");
                }
                segment++;
            }
            media.append(line).append('\n');
        }
        MediaTailorIntegrationTest.serve("/" + name + "/media.m3u8", media.toString());
        MediaTailorIntegrationTest.serve("/" + name + "/index.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,"
                + "RESOLUTION=640x360,CODECS=\"avc1.64001e,mp4a.40.2\"\nmedia.m3u8\n");
    }

    private void ad() {
        if (MediaTailorIntegrationTest.FILES.containsKey("/creative/ad.mp4")) {
            return;
        }
        byte[] clip = ffmpeg.run(new LinkedHashMap<>(), "ffmpeg -hide_banner -loglevel error -f lavfi "
                + "-i testsrc2=size=1280x720:rate=30 -f lavfi -i sine=frequency=880:sample_rate=44100 -t 4 "
                + "-c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart ad.mp4").get("ad.mp4");
        MediaTailorIntegrationTest.FILES.put("/creative/ad.mp4", () -> clip);
        MediaTailorIntegrationTest.serve("/creative/vast.xml", "<VAST version=\"4.0\"><Ad id=\"mp4\"><InLine>"
                + "<AdSystem>Test</AdSystem><AdTitle>mp4</AdTitle><Impression><![CDATA["
                + MediaTailorIntegrationTest.base + "/beacon/transcoded]]></Impression><Creatives><Creative "
                + "id=\"c\"><Linear><Duration>00:00:04</Duration><MediaFiles><MediaFile delivery=\"progressive\" "
                + "type=\"video/mp4\" width=\"1280\" height=\"720\" bitrate=\"2000\"><![CDATA["
                + MediaTailorIntegrationTest.base + "/creative/ad.mp4]]></MediaFile></MediaFiles></Linear>"
                + "</Creative></Creatives></InLine></Ad></VAST>");
    }

    private String stitched(String name) {
        Map<String, Object> config = MediaTailorIntegrationTest.config("{\"Name\":\"" + name + "\","
                + "\"AdDecisionServerUrl\":\"" + MediaTailorIntegrationTest.base + "/creative/vast.xml\","
                + "\"VideoContentSourceUrl\":\"" + MediaTailorIntegrationTest.base + "/" + name + "\"}");
        String master = MediaTailorIntegrationTest.manifestPrefix(config) + "index.m3u8";
        String child = MediaTailorIntegrationTest.get(master).lines().filter(line -> line.endsWith(".m3u8"))
                .findFirst().orElseThrow();
        return MediaTailorIntegrationTest.get(URI.create(master).resolve(child).toString());
    }

    private static byte[] fetch(String url) {
        return MediaTailorIntegrationTest.at(url).get(MediaTailorIntegrationTest.path(url)).then().statusCode(200)
                .extract().asByteArray();
    }

    @Test
    void mp4AdsAreTranscodedToTsForTsContent() {
        content("tscontent", false);
        ad();
        String media = stitched("tscontent");
        List<String> ads = MediaTailorIntegrationTest.lines(media, "/v1/segment/");
        assertEquals(2, ads.size(), media);
        assertEquals(2, MediaTailorIntegrationTest.discontinuities(media), media);
        assertTrue(media.contains(MediaTailorIntegrationTest.base + "/tscontent/s0.ts"), media);
        long impressions = MediaTailorIntegrationTest.hits("/beacon/transcoded");
        Map<String, byte[]> probed = ffmpeg.run(new LinkedHashMap<>(Map.of("a.ts", fetch(ads.get(0)), "b.ts",
                fetch(ads.get(1)))), "ffprobe -v error -show_entries stream=codec_name,width,height -of csv=p=0 a.ts "
                + "> probe.txt && ffprobe -v error -show_entries format=duration -of csv=p=0 b.ts >> probe.txt");
        String streams = new String(probed.get("probe.txt"), StandardCharsets.UTF_8);
        assertTrue(streams.contains("h264,640,360") && streams.contains("aac"), streams);
        await().atMost(Duration.ofSeconds(10)).until(() -> MediaTailorIntegrationTest.hits("/beacon/transcoded")
                == impressions + 1);
        MediaTailorIntegrationTest.mt().delete("/playbackConfiguration/tscontent").then().statusCode(lessThan(300));
    }

    @Test
    void mp4AdsAreTranscodedToFragmentedMp4ForCmafContent() {
        content("cmafcontent", true);
        ad();
        String media = stitched("cmafcontent");
        List<String> ads = MediaTailorIntegrationTest.lines(media, "/v1/segment/").stream()
                .filter(line -> !line.startsWith("#")).toList();
        assertEquals(2, ads.size(), media);
        String map = media.lines().filter(line -> line.startsWith("#EXT-X-MAP") && line.contains("/v1/segment/"))
                .findFirst().orElseThrow(() -> new AssertionError(media));
        String init = map.replaceAll(".*URI=\"([^\"]+)\".*", "$1");
        assertTrue(media.lines().anyMatch(line -> line.startsWith("#EXT-X-MAP") && line.contains("/cmafcontent/")),
                media);
        Map<String, byte[]> probed = ffmpeg.run(new LinkedHashMap<>(Map.of("init.mp4", fetch(init), "a.m4s",
                fetch(ads.get(0)))), "cat init.mp4 a.m4s > whole.mp4 && ffprobe -v error -show_entries "
                + "stream=codec_name,width,height -of csv=p=0 whole.mp4 > probe.txt");
        String streams = new String(probed.get("probe.txt"), StandardCharsets.UTF_8);
        assertTrue(streams.contains("h264,640,360") && streams.contains("aac"), streams);
        MediaTailorIntegrationTest.mt().delete("/playbackConfiguration/cmafcontent").then()
                .statusCode(lessThan(300));
    }
}
