package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.core.common.RequestHost;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Optional;

/**
 * Routes a domain endpoint host ({@code <domain>.<region>.es.<suffix>}, see
 * {@link OpenSearchDataPlaneEndpoint}) to the path-style data-plane proxy
 * {@code /_floci/opensearch-domain/<region>/<domain>/<path>}. The raw client path and query are
 * carried over unchanged (and the {@code Host} header is left alone), so the proxy can rebuild
 * the exact request the client signed.
 */
@Provider
@PreMatching
@Priority(5)
public class OpenSearchDataPlaneRoutingFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(OpenSearchDataPlaneRoutingFilter.class);

    @Override
    public void filter(ContainerRequestContext requestContext) {
        URI original = requestContext.getUriInfo().getRequestUri();
        String host = RequestHost.of(requestContext.getHeaderString("Host"), original);
        Optional<OpenSearchDataPlaneEndpoint.Target> target = OpenSearchDataPlaneEndpoint.parse(host);
        if (target.isEmpty()) {
            return;
        }
        String path = original.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (path.startsWith(OpenSearchDataPlaneController.BASE_PREFIX)) {
            return;
        }
        String rewrittenPath = proxyPath(target.get(), path);
        URI rewritten = UriBuilder.fromUri(original)
                .host("localhost")
                .replacePath(rewrittenPath)
                .build();
        LOG.debugv("Routing OpenSearch data plane: {0}{1} -> {2}", host, path, rewrittenPath);
        requestContext.setRequestUri(rewritten);
    }

    static String proxyPath(OpenSearchDataPlaneEndpoint.Target target, String rawPath) {
        String path = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
        return OpenSearchDataPlaneController.BASE_PREFIX + target.region() + "/" + target.domainName() + path;
    }
}
