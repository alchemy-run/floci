package io.floci.aws.services.iotsitewise;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes requests signed for {@code iotsitewise}, or sent to its {@code api.}, {@code data.} and {@code monitor.}
 * hosts, to {@link SiteWiseController}: its paths ({@code /assets}, {@code /properties/latest}, {@code /tags} and
 * others) are other REST services' paths too.
 */
@Provider
@PreMatching
@Priority(4969)
public class SiteWiseRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_iotsitewise";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean hosted = host != null && (host.startsWith("api.iotsitewise.") || host.startsWith("data.iotsitewise.")
                || host.startsWith("monitor.iotsitewise."));
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("iotsitewise"::equals)
                .orElse(hosted);
        if (signed && path.startsWith("/") && !path.startsWith(SiteWisePortalController.PATH + "/")) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
