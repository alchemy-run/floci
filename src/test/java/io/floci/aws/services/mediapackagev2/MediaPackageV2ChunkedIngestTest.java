package io.floci.aws.services.mediapackagev2;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An encoder's chunked HLS uploads, as ffmpeg makes them, reach a channel's ingest endpoint. */
@QuarkusTest
class MediaPackageV2ChunkedIngestTest {

    @TestHTTPResource("/")
    URL root;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private String put(String path, byte[] body) throws Exception {
        try (Socket socket = new Socket(root.getHost(), root.getPort())) {
            OutputStream out = socket.getOutputStream();
            out.write(("PUT " + path + " HTTP/1.1\r\nTransfer-Encoding: chunked\r\nUser-Agent: Lavf/62.12.103\r\n"
                    + "Accept: */*\r\nConnection: close\r\nHost: host.docker.internal:" + root.getPort()
                    + "\r\nIcy-MetaData: 1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write((Integer.toHexString(body.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void chunkedPlaylistsAndSegmentsAreIngested() throws Exception {
        MediaPackageV2IntegrationTest.mp().body("{\"ChannelGroupName\":\"chunked\"}").post("/channelGroup").then()
                .statusCode(200);
        String ingest = MediaPackageV2IntegrationTest.mp().body("{\"ChannelName\":\"c\"}")
                .post("/channelGroup/chunked/channel").then().statusCode(200).extract()
                .path("IngestEndpoints[0].Url");
        String manifest = MediaPackageV2IntegrationTest.mp().body("{\"OriginEndpointName\":\"e\",\"ContainerType\":"
                        + "\"TS\",\"HlsManifests\":[{\"ManifestName\":\"index\"}]}")
                .post("/channelGroup/chunked/channel/c/originEndpoint").then().statusCode(200).extract()
                .path("HlsManifests[0].Url");
        URI uri = URI.create(ingest);
        String id = uri.getHost().substring(0, uri.getHost().indexOf('.'));
        String base = MediaPackageOrigin.PATH + "/ingest/us-east-1/" + id + uri.getRawPath();
        String segment = put(base + "s_00000.ts", MediaPackageV2IntegrationTest.segment(7));
        assertTrue(segment.startsWith("HTTP/1.1 200"), segment);
        String playlist = put(base + "s.m3u8", ("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:1\n"
                + "#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:1.000000,\ns_00000.ts\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(playlist.startsWith("HTTP/1.1 200"), playlist);
        String served = MediaPackageV2IntegrationTest.at(manifest).get(MediaPackageV2IntegrationTest.path(manifest))
                .then().extract().asString();
        assertTrue(served.contains("index_1.m3u8"), served);
        assertEquals(200, MediaPackageV2IntegrationTest.mp().post("/channelGroup/chunked/channel/c/reset")
                .statusCode());
    }
}
