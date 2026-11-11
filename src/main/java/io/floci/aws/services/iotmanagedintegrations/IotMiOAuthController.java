package io.floci.aws.services.iotmanagedintegrations;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * Where an end user's OAuth 2.0 authorization of a third-party cloud returns: managed integrations' redirect URI.
 * The code is exchanged for the account association's tokens and the user is sent on to the connector
 * destination's {@code oAuthCompleteRedirectUrl}.
 */
@Path(IotMiConnectors.CALLBACK_PATH)
public class IotMiOAuthController {

    private final IotMiConnectors connectors;

    @Inject
    public IotMiOAuthController(IotMiConnectors connectors) {
        this.connectors = connectors;
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response callback(@QueryParam("state") String state, @QueryParam("code") String code,
                             @QueryParam("error") String error) {
        if (state == null || state.isBlank()) {
            return Response.status(400).entity("The authorization is missing its state.").build();
        }
        String next = connectors.completeAuthorization(state, code, error);
        if (next == null) {
            return Response.status(404).entity("No account association is waiting for this authorization.").build();
        }
        return Response.seeOther(URI.create(next)).build();
    }

    @GET
    @Path("/complete")
    @Produces(MediaType.TEXT_PLAIN)
    public Response complete() {
        return Response.ok("The account is linked; you can close this page.").build();
    }
}
