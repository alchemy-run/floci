package io.github.hectorvent.floci.services.appsync;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;

/**
 * Routes {@code {apiId}.appsync-api.{region}.amazonaws.com} Host headers to
 * the path-style GraphQL data-plane endpoint.
 */
@Provider
@PreMatching
@Priority(5)
public class AppSyncRoutingFilter implements ContainerRequestFilter {
    private static final Logger LOG = Logger.getLogger(AppSyncRoutingFilter.class);

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String host = requestContext.getHeaderString("Host");
        if (host == null || !host.contains(".appsync-api.")) {
            return;
        }
        String[] parts = host.split("\\.");
        if (parts.length < 3) {
            return;
        }
        String apiId = parts[0];
        URI original = requestContext.getUriInfo().getRequestUri();
        String path = original.getRawPath();
        if (path == null || path.isBlank() || "/".equals(path) || path.equals("/graphql")) {
            path = "/graphql";
        }
        URI rewritten = UriBuilder.fromUri(original)
                .host("localhost")
                .replacePath("/v1/apis/" + apiId + path)
                .build();
        LOG.debugv("Routing AppSync GraphQL: {0} -> {1}", host, rewritten.getPath());
        requestContext.setRequestUri(rewritten);
    }
}
