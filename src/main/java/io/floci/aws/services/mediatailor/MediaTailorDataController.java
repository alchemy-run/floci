package io.floci.aws.services.mediatailor;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;
import java.util.Map;

/**
 * MediaTailor's playback and channel assembly hosts, reached at their hosts (rewritten here by
 * {@link MediaTailorHostFilter}) or path-style: sessions, manifests, ad segments and tracking for playback
 * configurations ({@link MediaTailorPlayback}), and running channels' outputs ({@link MediaTailorChannels}).
 */
@Path(MediaTailorStore.PATH)
public class MediaTailorDataController {

    private final MediaTailorStore store;
    private final MediaTailorPlayback playback;
    private final MediaTailorChannels channels;

    @Inject
    public MediaTailorDataController(MediaTailorStore store, MediaTailorPlayback playback,
                                     MediaTailorChannels channels) {
        this.store = store;
        this.playback = playback;
        this.channels = channels;
    }

    /** The account whose playback or channel assembly host this is: the host id is per account and Region. */
    private String account(String kind, String region, String id) {
        String recordKind = MediaTailorStore.PLAYBACK.equals(kind) ? MediaTailorStore.CONFIG : MediaTailorStore.CHANNEL;
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(recordKind)) {
            if (region.equals(entry.value().path("_").path("region").asText())
                    && id.equals(MediaTailorStore.hostId(entry.accountId(), region))) {
                return entry.accountId();
            }
        }
        return null;
    }

    @GET
    @Path("/{kind}/{region}/{id}/{path: .*}")
    public Response get(@PathParam("kind") String kind, @PathParam("region") String region,
                        @PathParam("id") String id, @PathParam("path") String path, @Context UriInfo uri,
                        @Context HttpHeaders headers) {
        return serve("GET", kind, region, id, path, null, uri, headers);
    }

    @HEAD
    @Path("/{kind}/{region}/{id}/{path: .*}")
    public Response head(@PathParam("kind") String kind, @PathParam("region") String region,
                         @PathParam("id") String id, @PathParam("path") String path, @Context UriInfo uri,
                         @Context HttpHeaders headers) {
        Response response = serve("GET", kind, region, id, path, null, uri, headers);
        return Response.fromResponse(response).entity(null).build();
    }

    @POST
    @Path("/{kind}/{region}/{id}/{path: .*}")
    public Response post(@PathParam("kind") String kind, @PathParam("region") String region,
                         @PathParam("id") String id, @PathParam("path") String path, @Context UriInfo uri,
                         @Context HttpHeaders headers, byte[] body) {
        return serve("POST", kind, region, id, path, body, uri, headers);
    }

    @OPTIONS
    @Path("/{kind}/{region}/{id}/{path: .*}")
    public Response options() {
        return Response.noContent().header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Methods", "GET, HEAD, POST, OPTIONS")
                .header("Access-Control-Allow-Headers", "*").build();
    }

    private Response serve(String method, String kind, String region, String id, String path, byte[] body,
                           UriInfo uri, HttpHeaders headers) {
        if (!MediaTailorStore.PLAYBACK.equals(kind) && !MediaTailorStore.ASSEMBLY.equals(kind)) {
            return text(404, "Not found.");
        }
        String account = account(kind, region, id);
        if (account == null) {
            return text(404, "No MediaTailor resources are served at " + id + ".");
        }
        Map<String, List<String>> query = uri.getQueryParameters();
        String forwarded = headers.getHeaderString("X-Forwarded-For");
        MediaTailorPlayback.Client client = new MediaTailorPlayback.Client(forwarded == null ? null
                : forwarded.split(",")[0].trim(), headers.getHeaderString("User-Agent"),
                headers.getHeaderString("Referer"), query, headers.getHeaderString("Host"));
        String full = "/" + path;
        MediaTailorPlayback.Reply reply = RequestScopes.callAs(account, region, () ->
                MediaTailorStore.PLAYBACK.equals(kind)
                        ? playback.handle(method, account, region, full, body, client)
                        : channels.handle(account, region, full, query));
        Response.ResponseBuilder response = Response.status(reply.status()).entity(reply.body())
                .type(reply.contentType());
        reply.headers().forEach(response::header);
        if (reply.status() == 200 && reply.contentType().contains("mpegurl")) {
            response.header("Cache-Control", "max-age=1");
        }
        return response.build();
    }

    private static Response text(int status, String message) {
        return Response.status(status).type(MediaType.TEXT_PLAIN).entity(message)
                .header("Access-Control-Allow-Origin", "*").build();
    }
}
