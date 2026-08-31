package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.services.opensearch.model.Domain;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routes {@code search-{domain}-{id}.{region}.es.amazonaws.com} (and {@code .aos.})
 * Host headers onto the path-style OpenSearch data plane.
 *
 * <p>Alchemy's DomainRead/DomainWrite bindings {@code fetch()} the advertised
 * {@code Endpoint} hostname; S3's {@code /{bucket}/{key}} catch-all would
 * otherwise swallow {@code /songs/_doc/1}.
 */
@Provider
@PreMatching
@Priority(5)
public class OpenSearchRoutingFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_floci/opensearch";

    private static final Logger LOG = Logger.getLogger(OpenSearchRoutingFilter.class);

    /**
     * {@code search-mydomain-a1b2c3.us-east-1.es.amazonaws.com} — the unique
     * suffix is the 6-hex {@code VolumeId} Floci assigns at create.
     */
    private static final Pattern DATA_PLANE_HOST = Pattern.compile(
            "(?i)^(?:search-)?(.+)-([0-9a-f]{6})\\.([a-z0-9-]+)\\.(es|aos)\\.amazonaws\\.com$");

    private final OpenSearchService service;

    @Inject
    public OpenSearchRoutingFilter(OpenSearchService service) {
        this.service = service;
    }

    OpenSearchRoutingFilter() {
        this.service = null;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        URI original = requestContext.getUriInfo().getRequestUri();
        String host = resolveHost(requestContext.getHeaderString("Host"), original);
        String domainName = resolveDomainName(host);
        if (domainName == null) {
            return;
        }
        String path = original.getRawPath();
        if (alreadyPathStyle(path)) {
            return;
        }
        String rewrittenPath = rewritePath(domainName, path);
        URI rewritten = UriBuilder.fromUri(original)
                .host("localhost")
                .replacePath(rewrittenPath)
                .build();
        LOG.debugv("Routing OpenSearch data plane: {0} {1} -> {2}", host, path, rewritten.getPath());
        requestContext.setRequestUri(rewritten);
    }

    String resolveDomainName(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String hostname = host.split(":")[0].toLowerCase(Locale.ROOT);
        if (service != null) {
            Domain domain = service.findByEndpointHost(hostname);
            if (domain != null) {
                return domain.getDomainName();
            }
        }
        return extractDomainName(hostname);
    }

    static String extractDomainName(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String hostname = host.split(":")[0];
        Matcher matcher = DATA_PLANE_HOST.matcher(hostname);
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1);
    }

    static boolean isDataPlaneHost(String host) {
        return extractDomainName(host) != null;
    }

    static boolean alreadyPathStyle(String path) {
        return path != null && (path.equals(INTERNAL_PREFIX) || path.startsWith(INTERNAL_PREFIX + "/"));
    }

    static String rewritePath(String domainName, String path) {
        String rest = path == null || path.isBlank() ? "/" : path;
        if (!rest.startsWith("/")) {
            rest = "/" + rest;
        }
        return INTERNAL_PREFIX + "/" + domainName + rest;
    }

    static String resolveHost(String hostHeader, URI requestUri) {
        if (hostHeader != null && !hostHeader.isBlank()) {
            return hostHeader;
        }
        return requestUri == null ? null : requestUri.getHost();
    }
}
