package io.floci.aws.services.mediapackagev2;

import io.floci.aws.services.apigateway.ApiGatewayExecuteApiRoutingFilter;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routes MediaPackage v2's ingest and egress hosts, {@code <id>-<n>.ingest.mediapackagev2.<region>....} and
 * {@code <id>.egress.mediapackagev2.<region>....}, to {@link MediaPackageOriginController}.
 */
@Provider
@PreMatching
@Priority(6)
public class MediaPackageHostFilter implements ContainerRequestFilter {

    private static final Pattern HOST = Pattern.compile(
            "(?i)([a-z0-9-]+)\\.(ingest|egress)\\.mediapackagev2\\.([a-z0-9-]+)\\..*");

    @Override
    public void filter(ContainerRequestContext context) {
        URI original = context.getUriInfo().getRequestUri();
        String host = ApiGatewayExecuteApiRoutingFilter.resolveHost(context.getHeaderString("Host"), original);
        if (host == null) {
            return;
        }
        Matcher matcher = HOST.matcher(host.split(":")[0]);
        if (!matcher.matches() || original.getRawPath().startsWith(MediaPackageOrigin.PATH)) {
            return;
        }
        String path = MediaPackageOrigin.PATH + "/" + matcher.group(2).toLowerCase() + "/" + matcher.group(3) + "/"
                + matcher.group(1) + original.getRawPath();
        context.setRequestUri(UriBuilder.fromUri(original).host("localhost").replacePath(path).build());
    }
}
