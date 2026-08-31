package io.github.hectorvent.floci.services.apprunner;

import io.github.hectorvent.floci.services.apprunner.model.AppRunnerServiceRecord;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;

/**
 * Routes {@code {serviceId}.{region}.awsapprunner.com} (and the local
 * {@code *.awsapprunner.com.localhost} form) onto the internal proxy path.
 */
@Provider
@PreMatching
@Priority(5)
public class AppRunnerRoutingFilter implements ContainerRequestFilter {

    static final String PROXY_PREFIX = "/_floci/apprunner";

    private static final Logger LOG = Logger.getLogger(AppRunnerRoutingFilter.class);

    private final Instance<AppRunnerService> service;

    @Inject
    public AppRunnerRoutingFilter(Instance<AppRunnerService> service) {
        this.service = service;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        URI original = requestContext.getUriInfo().getRequestUri();
        String path = original.getRawPath() == null ? "/" : original.getRawPath();
        if (path.startsWith(PROXY_PREFIX) || path.startsWith("/_floci/")) {
            return;
        }
        String host = requestContext.getHeaderString("Host");
        if (host == null || host.isBlank()) {
            host = original.getHost();
        }
        if (host == null || !host.contains("awsapprunner.com")) {
            return;
        }
        AppRunnerServiceRecord record = service.get().findByHost(host);
        if (record == null) {
            return;
        }
        URI rewritten = UriBuilder.fromUri(original)
                .host("localhost")
                .replacePath(PROXY_PREFIX + "/" + record.getServiceId() + (path.startsWith("/") ? path : "/" + path))
                .build();
        LOG.debugv("Routing App Runner Host {0}{1} -> {2}", host, path, rewritten.getPath());
        requestContext.setRequestUri(rewritten);
    }
}
