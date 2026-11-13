package io.floci.aws.services.mediatailor;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes requests signed for {@code mediatailor} to {@link MediaTailorController}: MediaTailor paths
 * ({@code /channel/{name}}, {@code /tags/{arn}}, ...) are shared with other services' REST APIs.
 */
@Provider
@PreMatching
@Priority(4944)
public class MediaTailorRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_mediatailor";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("mediatailor"::equals)
                .orElse(host != null && host.startsWith("api.mediatailor."));
        if (signed && path.startsWith("/")) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
