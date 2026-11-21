package io.floci.aws.services.ivs;

import io.floci.aws.core.common.AwsException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * A stage's WHIP endpoint: a WHIP client (OBS, a hardware encoder, a browser) publishes to a stage by POSTing its SDP
 * offer with a participant token as its bearer token. The token is verified as IVS does (signed by IVS, or by an
 * imported public key, unexpired, for a stage that exists, of a participant not disconnected) and must allow
 * publishing; the offer must carry H.264 video. The exchange is then the IVS media server's: its answer, and the
 * session's resource for trickle ICE and teardown, served here.
 */
@Path(IvsRealtimeWhip.PATH)
public class IvsRealtimeWhip {

    static final String PATH = "/_floci/ivsrealtime/whip";

    private final IvsRealtimeService service;
    private final IvsRealtimeLive live;
    private final IvsMediaServer media;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Inject
    public IvsRealtimeWhip(IvsRealtimeService service, IvsRealtimeLive live, IvsMediaServer media) {
        this.service = service;
        this.live = live;
        this.media = media;
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    public Response publish(@HeaderParam("Authorization") String authorization, String offer) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim() : null;
        IvsRealtimeService.Grant grant;
        try {
            grant = service.verify(token, null);
        } catch (AwsException e) {
            return text(401, e.getMessage());
        }
        if (!grant.publish()) {
            live.publishError(grant, "INSUFFICIENT_CAPABILITIES", "WHIP");
            return text(403, "The participant token does not allow publishing.");
        }
        if (offer == null || !offer.contains("m=video") || !offer.toUpperCase().contains("H264")) {
            live.publishError(grant, "INVALID_VIDEO_CODEC", "WHIP");
            return text(400, "The offer must include an H.264 video track.");
        }
        IvsMediaServer.Endpoint server = media.endpoint();
        if (server == null) {
            live.ensureMedia();
            return Response.status(503).header("Retry-After", "2").type(MediaType.TEXT_PLAIN)
                    .entity("The IVS media server is starting.").build();
        }
        String path = live.expectWhip(grant);
        HttpResponse<String> answer = send(HttpRequest.newBuilder(URI.create(server.webrtc() + "/" + path + "/whip"))
                .header("Content-Type", "application/sdp")
                .POST(HttpRequest.BodyPublishers.ofString(offer, StandardCharsets.UTF_8)));
        if (answer == null) {
            return text(502, "The IVS media server did not answer.");
        }
        Response.ResponseBuilder response = Response.status(answer.statusCode()).entity(answer.body());
        answer.headers().firstValue("Content-Type").ifPresent(type -> response.header("Content-Type", type));
        answer.headers().firstValue("ETag").ifPresent(tag -> response.header("ETag", tag));
        answer.headers().allValues("Link").forEach(link -> response.header("Link", link));
        answer.headers().firstValue("Location").ifPresent(location -> response.header("Location", PATH
                + "/session/" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                        location.getBytes(StandardCharsets.UTF_8))));
        return response.build();
    }

    @PATCH
    @Path("/session/{session}")
    @Consumes(MediaType.WILDCARD)
    public Response trickle(@PathParam("session") String session, @HeaderParam("Content-Type") String type,
                            @HeaderParam("If-Match") String ifMatch, String body) {
        URI target = target(session);
        if (target == null) {
            return text(404, "No such WHIP session.");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .header("Content-Type", type == null ? "application/trickle-ice-sdpfrag" : type)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        HttpResponse<String> answer = send(request);
        return answer == null ? text(502, "The IVS media server did not answer.")
                : Response.status(answer.statusCode()).entity(answer.body()).build();
    }

    @DELETE
    @Path("/session/{session}")
    public Response stop(@PathParam("session") String session) {
        URI target = target(session);
        if (target == null) {
            return text(404, "No such WHIP session.");
        }
        HttpResponse<String> answer = send(HttpRequest.newBuilder(target).DELETE());
        return answer == null ? text(502, "The IVS media server did not answer.")
                : Response.status(answer.statusCode()).build();
    }

    /** The media server's resource of a session ID this endpoint handed out. */
    private URI target(String session) {
        IvsMediaServer.Endpoint server = media.endpoint();
        if (server == null) {
            return null;
        }
        try {
            String location = new String(Base64.getUrlDecoder().decode(session), StandardCharsets.UTF_8);
            if (!location.startsWith("/" + IvsRealtimeLive.STAGE_PATHS)) {
                return null;
            }
            return URI.create(server.webrtc() + location);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Response text(int status, String message) {
        return Response.status(status).type(MediaType.TEXT_PLAIN).entity(message).build();
    }
}
