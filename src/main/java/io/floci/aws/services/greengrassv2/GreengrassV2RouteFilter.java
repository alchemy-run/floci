package io.floci.aws.services.greengrassv2;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes requests signed for {@code greengrass} to {@link GreengrassV2Controller}: its tag paths
 * ({@code /tags/<arn>}) are other REST services' paths too. Unsigned requests under {@code /greengrass/}, a
 * nucleus's, are routed there as well.
 */
@Provider
@PreMatching
@Priority(4976)
public class GreengrassV2RouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_greengrass";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("greengrass"::equals)
                .orElse(host != null && host.startsWith("greengrass."));
        // A nucleus calls the data plane under /greengrass/ with its certificate rather than a signature.
        if ((signed && path.startsWith("/")) || path.startsWith("/greengrass/")) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
