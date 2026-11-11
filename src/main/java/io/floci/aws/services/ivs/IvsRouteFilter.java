package io.floci.aws.services.ivs;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes requests signed for {@code ivs} (IVS and IVS Real-Time) to {@link IvsController}: its paths
 * ({@code /CreateChannel}, {@code /tags/<arn>}) are other REST services' paths too.
 */
@Provider
@PreMatching
@Priority(4974)
public class IvsRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_ivs";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("ivs"::equals)
                .orElse(host != null && (host.startsWith("ivs.") || host.startsWith("ivsrealtime.")));
        if (signed && path.startsWith("/")) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
