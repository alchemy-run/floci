package io.github.hectorvent.floci.services.amazonmq;

import io.github.hectorvent.floci.core.common.RequestHost;
import io.github.hectorvent.floci.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;

/**
 * Amazon MQ and MSK both define their configuration APIs on {@code /v1/configurations}; AWS
 * separates them by hostname, Floci serves both on one port. Requests signed for {@code mq}
 * (or addressed to an {@code mq.} host) are rewritten onto {@link #INTERNAL_PREFIX}, which
 * {@link AmazonMqController} binds; everything else keeps reaching MSK. The underscore keeps the
 * internal path clear of S3 path-style routes, since bucket names cannot contain one.
 */
@Provider
@PreMatching
@Priority(5000)
public class AmazonMqRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_amazonmq";
    private static final String CONFIGURATIONS = "v1/configurations";

    @Override
    public void filter(ContainerRequestContext ctx) {
        UriInfo uriInfo = ctx.getUriInfo();
        String path = uriInfo.getPath();
        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (!relative.equals(CONFIGURATIONS) && !relative.startsWith(CONFIGURATIONS + "/")) {
            return;
        }
        if (!isMqRequest(ctx)) {
            return;
        }
        URI rewritten = uriInfo.getRequestUriBuilder()
                .replacePath(INTERNAL_PREFIX + "/" + relative)
                .build();
        ctx.setRequestUri(rewritten);
    }

    static boolean isMqRequest(ContainerRequestContext ctx) {
        boolean signedForMq = SigV4CredentialScope.serviceName(ctx.getHeaderString("Authorization"))
                .map("mq"::equals)
                .orElse(false);
        if (signedForMq) {
            return true;
        }
        String host = RequestHost.of(ctx);
        return host != null && (host.startsWith("mq.") || host.startsWith("mq-fips."));
    }
}
