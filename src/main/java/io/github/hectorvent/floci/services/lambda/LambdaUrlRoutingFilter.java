package io.github.hectorvent.floci.services.lambda;

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
 * Routes requests based on the Host header for Lambda Function URLs.
 *
 * Rewrites http://<urlId>.lambda-url.<region>.localhost:4566/path
 * to /lambda-url/<urlId>/path
 */
@Provider
@PreMatching
@Priority(5) // Run early
public class LambdaUrlRoutingFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(LambdaUrlRoutingFilter.class);

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        URI originalUri = requestContext.getUriInfo().getRequestUri();
        String path = originalUri == null || originalUri.getRawPath() == null
                ? "/"
                : originalUri.getRawPath();
        // Already rewritten (or a client used path-style /lambda-url/{urlId}/...).
        if ("/lambda-url".equals(path) || path.startsWith("/lambda-url/")) {
            return;
        }

        String host = requestContext.getHeaderString("Host");
        if (host == null || !host.contains(".lambda-url.")) {
            String uriHost = originalUri == null ? null : originalUri.getHost();
            if (uriHost != null && uriHost.contains(".lambda-url.")) {
                host = uriHost;
            }
        }
        if (host == null || !host.contains(".lambda-url.")) {
            return;
        }

        // Pattern: <urlId>.lambda-url.<region>.<anything>
        String[] parts = host.split("\\.");
        if (parts.length >= 3) {
            String urlId = parts[0];
            URI newUri = UriBuilder.fromUri(originalUri)
                    .host("localhost") // Normalize host
                    .replacePath("/lambda-url/" + urlId + path)
                    .build();

            LOG.debugv("Routing Lambda URL: {0} -> {1}", host, newUri.getPath());
            requestContext.setRequestUri(newUri);
        }
    }
}
