package io.floci.aws.services.synthetics;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes requests signed for {@code synthetics} to {@link SyntheticsController}: its paths
 * ({@code /canary/...}, {@code /group/...}, {@code /tags/...}) are shared with other services' REST APIs.
 */
@Provider
@PreMatching
@Priority(4943)
public class SyntheticsRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_synthetics";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("synthetics"::equals)
                .orElse(host != null && host.startsWith("synthetics."));
        if (signed && path.startsWith("/")) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
