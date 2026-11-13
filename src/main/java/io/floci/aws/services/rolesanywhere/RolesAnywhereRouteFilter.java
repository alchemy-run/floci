package io.floci.aws.services.rolesanywhere;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes requests signed for {@code rolesanywhere} to {@link RolesAnywhereController}: its paths
 * ({@code /profiles}, {@code /sessions}, {@code /TagResource}) are shared with other services' REST APIs.
 */
@Provider
@PreMatching
@Priority(4939)
public class RolesAnywhereRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_rolesanywhere";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("rolesanywhere"::equals)
                .orElse(host != null && host.startsWith("rolesanywhere."));
        if (signed && path.startsWith("/")) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
