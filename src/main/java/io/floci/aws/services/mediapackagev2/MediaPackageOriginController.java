package io.floci.aws.services.mediapackagev2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * MediaPackage v2's ingest and egress endpoints, reached at their hosts ({@code <id>-<n>.ingest.mediapackagev2...}
 * and {@code <id>.egress.mediapackagev2...}, rewritten here by {@link MediaPackageHostFilter}) or path-style. Egress
 * serves origin endpoints' files; ingest is {@link MediaPackageIngestRoute}'s.
 */
@Path(MediaPackageOrigin.PATH)
public class MediaPackageOriginController {

    private final MediaPackageOrigin origin;
    private final MediaPackageStore store;

    @Inject
    public MediaPackageOriginController(MediaPackageOrigin origin, MediaPackageStore store) {
        this.origin = origin;
        this.store = store;
    }

    /** The account and channel an ingest host names: its id, before the pipeline number. */
    AccountAwareStorageBackend.AccountEntry<ObjectNode> channel(String region, String host, String group,
                                                                        String name) {
        String id = host.contains("-") ? host.substring(0, host.lastIndexOf('-')) : host;
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(MediaPackageStore.CHANNEL)) {
            ObjectNode channel = entry.value();
            if (id.equals(channel.path("_").path("ingestId").asText()) && region.equals(channel.path("_")
                    .path("region").asText()) && group.equals(channel.path("ChannelGroupName").asText())
                    && name.equals(channel.path("ChannelName").asText())) {
                return entry;
            }
        }
        throw MediaPackageStore.notFound("CHANNEL", "No channel takes ingest at " + host + ".");
    }

    private static Response error(AwsException e) {
        return Response.status(e.getHttpStatus()).type(MediaType.TEXT_PLAIN).entity(e.getMessage()).build();
    }

    @GET
    @Path("/egress/{region}/{host}/out/v1/{group}/{channel}/{endpoint}/{file: .+}")
    public Response get(@PathParam("region") String region, @PathParam("host") String host,
                        @PathParam("group") String group, @PathParam("channel") String channel,
                        @PathParam("endpoint") String endpoint, @PathParam("file") String file) {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(MediaPackageStore.GROUP)) {
            ObjectNode record = entry.value();
            if (host.equals(record.path("_").path("domainId").asText()) && region.equals(record.path("_")
                    .path("region").asText()) && group.equals(record.path("ChannelGroupName").asText())) {
                try {
                    MediaPackageOrigin.Served served = RequestScopes.callAs(entry.accountId(), region, () ->
                            origin.egress(entry.accountId(), region, group, channel, endpoint, file));
                    return Response.ok(served.body(), served.type()).header("Cache-Control", "max-age=2").build();
                } catch (AwsException e) {
                    return error(e);
                }
            }
        }
        return Response.status(404).type(MediaType.TEXT_PLAIN).entity("No channel group serves " + host + ".")
                .build();
    }
}
