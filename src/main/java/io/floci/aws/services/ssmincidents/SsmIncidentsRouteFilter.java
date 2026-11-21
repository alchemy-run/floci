package io.floci.aws.services.ssmincidents;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes requests signed for {@code ssm-incidents} to {@link SsmIncidentsController}: its paths
 * ({@code /createResponsePlan}, {@code /tags/...}) are shared with other services' REST APIs.
 */
@Provider
@PreMatching
@Priority(4942)
public class SsmIncidentsRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_ssmincidents";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("ssm-incidents"::equals)
                .orElse(host != null && host.startsWith("ssm-incidents."));
        if (signed && path.startsWith("/")) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
