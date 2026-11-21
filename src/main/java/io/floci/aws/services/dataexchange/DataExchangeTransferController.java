package io.floci.aws.services.dataexchange;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The signed URLs Data Exchange jobs hand out: a PUT uploads the file an {@code IMPORT_ASSET_FROM_SIGNED_URL} job (or
 * the specification an {@code IMPORT_ASSET_FROM_API_GATEWAY_API} job) imports, and a GET downloads the asset an
 * {@code EXPORT_ASSET_TO_SIGNED_URL} job exported. URLs expire after an hour.
 */
@Path(DataExchangeTransferController.PATH)
public class DataExchangeTransferController {
    static final String PATH = "/_dataexchange-transfer";

    private final DataExchangeJobs jobs;

    @Inject
    public DataExchangeTransferController(DataExchangeJobs jobs) {
        this.jobs = jobs;
    }

    @PUT
    @Path("/{token}")
    @Consumes(MediaType.WILDCARD)
    public Response upload(@PathParam("token") String token, byte[] body) {
        return jobs.upload(token, body == null ? new byte[0] : body) ? Response.ok().build()
                : Response.status(403).entity("The signed URL is unknown or has expired.").type(MediaType.TEXT_PLAIN)
                        .build();
    }

    @GET
    @Path("/{token}")
    public Response download(@PathParam("token") String token) {
        return jobs.download(token).map(bytes -> Response.ok(bytes, MediaType.APPLICATION_OCTET_STREAM).build())
                .orElseGet(() -> Response.status(403).entity("The signed URL is unknown or has expired.")
                        .type(MediaType.TEXT_PLAIN).build());
    }
}
