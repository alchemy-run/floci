package io.floci.aws.services.iotwireless;

import io.floci.aws.core.common.AwsException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/** The CUPS endpoint gateways post {@code update-info} to; see {@link WirelessStation#updateInfo}. */
@Path(WirelessStation.PATH + "{region}/cups")
public class WirelessCupsController {

    private final WirelessStation station;

    @Inject
    public WirelessCupsController(WirelessStation station) {
        this.station = station;
    }

    @POST
    @Path("/update-info")
    @Consumes(MediaType.WILDCARD)
    public Response updateInfo(@PathParam("region") String region, String body, @Context UriInfo uri,
                               @Context HttpHeaders headers) {
        String host = headers.getHeaderString("Host");
        if (host == null) {
            host = uri.getBaseUri().getAuthority();
        }
        try {
            byte[] out = station.updateInfo(region, body, uri.getBaseUri().getScheme(), host);
            if (out == null) {
                return Response.status(404).entity("The gateway isn't registered in " + region + ".").build();
            }
            return Response.ok(out, MediaType.APPLICATION_OCTET_STREAM).build();
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus()).entity(e.getMessage()).build();
        }
    }
}
