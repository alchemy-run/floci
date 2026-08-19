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
        String apiId = extractApiId(host);
        if (apiId == null) {
            return;
        }
        URI original = requestContext.getUriInfo().getRequestUri();
        String path = original.getRawPath();
        if (alreadyPathStyle(path)) {
            return;
        }
        String rewrittenPath = graphqlPath(apiId, path);
        URI rewritten = UriBuilder.fromUri(original)
                .host("localhost")
                .replacePath(rewrittenPath)
                .build();
        LOG.debugv("Routing AppSync GraphQL: {0} -> {1}", host, rewritten.getPath());
        requestContext.setRequestUri(rewritten);
    }

    static String extractApiId(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String hostname = host.split(":")[0];
        if (!hostname.contains(".appsync-api.")) {
            return null;
        }
        String[] parts = hostname.split("\\.");
        if (parts.length < 3 || parts[0].isBlank() || !"appsync-api".equals(parts[1])) {
            return null;
        }
        return parts[0];
    }

    static String graphqlPath(String apiId, String path) {
        String normalized = path;
        if (normalized == null || normalized.isBlank() || "/".equals(normalized) || "/graphql".equals(normalized)) {
            normalized = "/graphql";
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return "/v1/apis/" + apiId + normalized;
    }

    static boolean alreadyPathStyle(String path) {
        return path != null && path.startsWith("/v1/apis/");
    }
}
