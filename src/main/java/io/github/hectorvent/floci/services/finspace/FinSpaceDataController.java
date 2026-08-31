package io.github.hectorvent.floci.services.finspace;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Amazon FinSpace Data restJson1 — environment-scoped data-plane API.
 *
 * <p>The data API is fronted by API Gateway and signed as {@code finspace-api}.
 * Regular IAM credentials (and therefore the emulator's dummy keys) are not
 * environment-scoped, so AWS returns a plain-text 403
 * {@code Failed to retrieve environment} instead of a JSON error document.
 * Distilled's rest-json deserializer falls back to matching
 * {@code AccessDeniedException} by HTTP status.
 *
 * <p>Literal {@code /datasetsv2} paths take JAX-RS precedence over S3's
 * {@code /{bucket}} catch-all.
 */
@Path(FinSpaceRoutingFilter.INTERNAL_PREFIX)
public class FinSpaceDataController {

    static final String FAILED_TO_RETRIEVE_ENVIRONMENT = "Failed to retrieve environment";

    @GET
    @Path("/datasetsv2/{datasetId}")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response getDataset(@PathParam("datasetId") String datasetId) {
        return Response.status(403)
                .type(MediaType.TEXT_PLAIN)
                .entity(FAILED_TO_RETRIEVE_ENVIRONMENT)
                .build();
    }
}
