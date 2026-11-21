package io.floci.aws.services.iotwireless;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes requests signed for {@code iotwireless} to {@link WirelessController}: its paths ({@code /destinations},
 * {@code /tags}, {@code /metrics} and others) are other REST services' paths too. Gateways' unsigned Basics Station
 * traffic under {@link WirelessStation#PATH} is left alone.
 */
@Provider
@PreMatching
@Priority(4968)
public class WirelessRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_iotwireless";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("iotwireless"::equals)
                .orElse(host != null && host.startsWith("api.iotwireless."));
        if (signed && path.startsWith("/") && !path.startsWith(WirelessStation.PATH)) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
