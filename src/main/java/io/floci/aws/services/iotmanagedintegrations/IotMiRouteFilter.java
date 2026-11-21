package io.floci.aws.services.iotmanagedintegrations;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes requests signed for {@code iotmanagedintegrations} to {@link IotMiController}: its paths
 * ({@code /destinations}, {@code /tags/<arn>} and others) are other REST services' paths too.
 */
@Provider
@PreMatching
@Priority(4970)
public class IotMiRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_iotmanagedintegrations";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("iotmanagedintegrations"::equals)
                .orElse(host != null && host.startsWith("api.iotmanagedintegrations."));
        if (signed && path.startsWith("/")) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
