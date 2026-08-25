package io.github.hectorvent.floci.services.mediapackagev2;

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
 * MediaPackage v2 restJson1 paths such as {@code /channelGroup/{name}} collide with S3's
 * path-style {@code /{bucket}/{key:.+}} catch-all. Floci serves every service on one
 * port, so SigV4 credential scope {@code mediapackagev2} is rewritten onto an internal
 * prefix the MediaPackage v2 controller owns. Tag APIs stay on {@code /tags/{arn}} and
 * are dispatched by {@code SharedTagsController}.
 */
@Provider
@PreMatching
@Priority(10)
public class MediaPackageV2RoutingFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/aws-mediapackagev2";

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isMediaPackageV2(requestContext.getHeaderString("Authorization"))) {
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

    static boolean isMediaPackageV2(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        return matcher.find()
                && MediaPackageV2Service.SERVICE.equals(matcher.group(1).toLowerCase(Locale.ROOT));
    }

    /**
     * Prefixes mediapackagev2-signed paths onto {@link #INTERNAL_PREFIX}, stripping a
     * trailing slash so the SDK's {@code /channel/{name}/} routes match the same resource.
     * {@code /tags} is left alone for {@code SharedTagsController}.
     */
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
