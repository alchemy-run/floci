package io.github.hectorvent.floci.services.cloudfront.edge;

import io.github.hectorvent.floci.services.cloudfront.CloudFrontService;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;

/**
 * Routes {@code {distributionId}.cloudfront.net} — and any alternate domain
 * name attached to a distribution — to the emulated CloudFront edge.
 *
 * <p>Mirrors the execute-api and AppSync virtual-host filters: the client keeps
 * addressing the AWS hostname, the request is rewritten onto a path-style
 * internal route, and the original Host stays on the request so the edge can
 * build a faithful event object.
 *
 * <p>Runs before {@link io.github.hectorvent.floci.services.s3.S3VirtualHostFilter}
 * (which is unprioritized) so a distribution domain is never mistaken for a
 * virtual-hosted bucket.
 */
@Provider
@PreMatching
@Priority(4)
public class CloudFrontEdgeRoutingFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(CloudFrontEdgeRoutingFilter.class);

    static final String EDGE_PREFIX = "/_floci/cloudfront";

    private final CloudFrontService service;

    @Inject
    public CloudFrontEdgeRoutingFilter(CloudFrontService service) {
        this.service = service;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        URI original = requestContext.getUriInfo().getRequestUri();
        String path = original.getRawPath() == null ? "/" : original.getRawPath();
        if (path.startsWith(EDGE_PREFIX) || path.startsWith("/2020-05-31/") || path.startsWith("/_floci/")) {
            return;
        }
        String host = resolveHost(requestContext.getHeaderString("Host"), original);
        if (host == null) {
            return;
        }
        Distribution distribution = service.findDistributionByHost(stripPort(host));
        if (distribution == null) {
            return;
        }
        URI rewritten = UriBuilder.fromUri(original)
                .host("localhost")
                .replacePath(EDGE_PREFIX + "/" + distribution.getId() + path)
                .build();
        LOG.debugv("Routing CloudFront Host {0}{1} -> {2}", host, path, rewritten.getPath());
        requestContext.setRequestUri(rewritten);
    }

    /** HTTP/1.1 {@code Host}, else the request URI authority (HTTP/2 {@code :authority}). */
    static String resolveHost(String hostHeader, URI requestUri) {
        if (hostHeader != null && !hostHeader.isBlank()) {
            return hostHeader;
        }
        return requestUri != null ? requestUri.getAuthority() : null;
    }

    static String stripPort(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        int colon = host.lastIndexOf(':');
        if (colon > 0) {
            String maybePort = host.substring(colon + 1);
            if (!maybePort.isEmpty() && maybePort.chars().allMatch(Character::isDigit)) {
                return host.substring(0, colon);
            }
        }
        return host;
    }
}
