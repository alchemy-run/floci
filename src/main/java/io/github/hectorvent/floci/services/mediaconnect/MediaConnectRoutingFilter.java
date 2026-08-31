package io.github.hectorvent.floci.services.mediaconnect;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MediaConnect restJson1 paths such as {@code /v1/flows} collide with S3's
 * path-style {@code /{bucket}/{key:.+}} catch-all. Floci serves every service on
 * one port, so SigV4 credential scope {@code mediaconnect} is rewritten onto an
 * internal prefix the MediaConnect controller owns. Tag APIs stay on
 * {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 */
@Provider
@PreMatching
@Priority(10)
public class MediaConnectRoutingFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/aws-mediaconnect";

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isMediaConnect(requestContext.getHeaderString("Authorization"))) {
            return;
        }
        URI original = requestContext.getUriInfo().getRequestUri();
        String path = original.getRawPath();
        String rewrittenPath = rewritePath(path);
        if (rewrittenPath == null || rewrittenPath.equals(path)) {
            return;
        }
        URI rewritten = UriBuilder.fromUri(original)
                .replacePath(rewrittenPath)
                .build();
        requestContext.setRequestUri(rewritten);
    }

    static boolean isMediaConnect(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        return matcher.find()
                && MediaConnectService.SERVICE.equals(matcher.group(1).toLowerCase(Locale.ROOT));
    }

    static String rewritePath(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        if (path.equals(INTERNAL_PREFIX) || path.startsWith(INTERNAL_PREFIX + "/")) {
            return path;
        }
        String normalized = stripTrailingSlash(path);
        if ("/tags".equals(normalized) || normalized.startsWith("/tags/")) {
            return path;
        }
        return INTERNAL_PREFIX + normalized;
    }

    static String stripTrailingSlash(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
