package io.floci.aws.services.kinesisvideo;

import io.floci.aws.core.common.RequestScopes;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

/**
 * Playback of Kinesis Video HLS and DASH sessions: the playlists and manifest of a session's fragments and the
 * segments they name, at the URL {@code GetHLSStreamingSessionURL} or {@code GetDASHStreamingSessionURL} gave, until
 * the session expires.
 */
@Path(KvsArchive.PLAYBACK)
public class KvsPlaybackController {

    private final KvsArchive archive;

    @Inject
    public KvsPlaybackController(KvsArchive archive) {
        this.archive = archive;
    }

    @GET
    @Path("/{kind}/{token}/{file}")
    public Response get(@PathParam("kind") String kind, @PathParam("token") String token,
                        @PathParam("file") String file, @QueryParam("FragmentNumber") String number) {
        KvsArchive.Session session = archive.session(token);
        if (session == null || !session.kind().equals(kind)) {
            return Response.status(403).type("text/plain").entity("The streaming session has expired or doesn't "
                    + "exist.").build();
        }
        return RequestScopes.callAs(session.account(), session.region(), () -> serve(session, token, file, number));
    }

    private Response serve(KvsArchive.Session session, String token, String file, String number) {
        try {
            return switch (file) {
                case "getHLSMasterPlaylist.m3u8" -> Response.ok(archive.hlsMaster(token),
                        "application/vnd.apple.mpegurl").build();
                case "getHLSMediaPlaylist.m3u8" -> Response.ok(archive.hlsMedia(session),
                        "application/vnd.apple.mpegurl").build();
                case "getDASHManifest.mpd" -> Response.ok(archive.dashManifest(session), "application/dash+xml")
                        .build();
                case "getMP4InitFragment.mp4" -> media(archive.segment(session, number, "init"), "video/mp4");
                case "getMP4MediaFragment.mp4" -> media(archive.segment(session, number, "media"), "video/mp4");
                case "getTSFragment.ts" -> media(archive.segment(session, number, "ts"), "video/mp2t");
                default -> Response.status(404).build();
            };
        } catch (KvsTranscoder.TranscodeException e) {
            return Response.status(500).type("text/plain").entity(e.getMessage()).build();
        }
    }

    private static Response media(byte[] bytes, String type) {
        return bytes == null ? Response.status(404).type("text/plain").entity("The fragment isn't in the session.")
                .build() : Response.ok(bytes, type).build();
    }

}
