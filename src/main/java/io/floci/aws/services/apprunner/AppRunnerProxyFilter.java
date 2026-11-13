package io.floci.aws.services.apprunner;

import io.floci.aws.core.common.RequestHost;
import io.floci.aws.core.common.RequestUriRewrite;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;

/**
 * Routes requests addressed to an App Runner service ({@code <id>.<region>.awsapprunner.com}) to
 * {@link AppRunnerProxyController}, which forwards them to the service's container. Runs after the
 * Amplify filter (16) and before the S3 virtual-host filter.
 */
@Provider
@PreMatching
@Priority(17)
public class AppRunnerProxyFilter implements ContainerRequestFilter {
    static final String HOST_PROPERTY = AppRunnerProxyFilter.class.getName() + ".host";
    static final String PATH_PROPERTY = AppRunnerProxyFilter.class.getName() + ".path";

    @Override
    public void filter(ContainerRequestContext request) {
        URI uri = request.getUriInfo().getRequestUri();
        String path = uri.getRawPath();
        if (path != null && (path.startsWith("/_floci/") || path.startsWith("/_apprunner/"))) {
            return;
        }
        String host = RequestHost.of(request);
        if (host == null || !host.toLowerCase().replaceAll(":\\d+$", "").endsWith(".awsapprunner.com")) {
            return;
        }
        request.setProperty(HOST_PROPERTY, host);
        request.setProperty(PATH_PROPERTY, (path == null || path.isEmpty() ? "/" : path)
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        StringBuilder rewritten = new StringBuilder();
        if (uri.getScheme() != null) {
            rewritten.append(uri.getScheme()).append("://").append(uri.getRawAuthority());
        }
        rewritten.append("/_apprunner/proxy");
        RequestUriRewrite.apply(request, URI.create(rewritten.toString()));
    }
}
