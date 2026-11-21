package io.floci.aws.services.docdbelastic;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes requests signed for {@code docdb-elastic} to {@link DocDbElasticController}: elastic cluster paths
 * ({@code /cluster}, {@code /tags}, {@code /pending-action}, ...) are shared with other services' REST APIs.
 */
@Provider
@PreMatching
@Priority(4982)
public class DocDbElasticRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_docdbelastic";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("docdb-elastic"::equals)
                .orElse(host != null && host.startsWith("docdb-elastic."));
        if (signed && path.startsWith("/")) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
