package io.github.hectorvent.floci.services.lambda.microvm;

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
 * Routes MicroVM endpoint traffic by Host header. A client (test host, Lambda
 * container, or workerd) calls
 * {@code https://<microvmId>.lambda-microvm.<region>.localhost.floci.io/path};
 * DNS resolves that to Floci, TLS terminates at the gateway, and this filter
 * rewrites the request to the path-style proxy at
 * {@code /_floci/microvm-endpoint/<microvmId>/path}
 * (see {@link MicrovmEndpointProxyController}).
 */
@Provider
@PreMatching
@Priority(5)
public class MicrovmEndpointRoutingFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(MicrovmEndpointRoutingFilter.class);

    static final String PROXY_PATH_PREFIX = "/_floci/microvm-endpoint/";

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        // HTTP/2 carries the authority in the :authority pseudo-header, which
        // surfaces on the request URI rather than a Host header.
        String host = requestContext.getHeaderString("Host");
        if (host == null || host.isBlank()) {
            host = requestContext.getUriInfo().getRequestUri().getHost();
        }
        String microvmId = extractMicrovmId(host);
        if (microvmId == null) {
            return;
        }
        URI original = requestContext.getUriInfo().getRequestUri();
        String path = original.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        if (path.startsWith(PROXY_PATH_PREFIX)) {
            return;
        }
        URI rewritten = UriBuilder.fromUri(original)
                .host("localhost")
                .replacePath(PROXY_PATH_PREFIX + microvmId + path)
                .build();
        LOG.debugv("Routing MicroVM endpoint: {0}{1} -> {2}", host, path, rewritten.getPath());
        requestContext.setRequestUri(rewritten);
    }

    /** {@code <microvmId>.lambda-microvm.<region>.<suffix>} → microvmId, else null. */
    static String extractMicrovmId(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String hostname = host.split(":")[0];
        int idx = hostname.indexOf(".lambda-microvm.");
        if (idx <= 0) {
            return null;
        }
        String id = hostname.substring(0, idx);
        return id.contains(".") ? null : id;
    }
}
