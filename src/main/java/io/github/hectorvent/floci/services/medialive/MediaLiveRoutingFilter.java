package io.github.hectorvent.floci.services.medialive;

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
 * MediaLive restJson1 paths such as {@code /prod/inputs} collide with API Gateway
 * stage paths and S3's path-style catch-all. Floci serves every service on one
 * port, so SigV4 credential scope {@code medialive} is rewritten onto an internal
 * prefix the MediaLive controller owns. Tag APIs live under {@code /prod/tags}
 * and are rewritten with the rest of the service.
 */
@Provider
@PreMatching
@Priority(10)
public class MediaLiveRoutingFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/aws-medialive";

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isMediaLive(requestContext.getHeaderString("Authorization"))) {
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

    static boolean isMediaLive(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        return matcher.find()
                && MediaLiveService.SERVICE.equals(matcher.group(1).toLowerCase(Locale.ROOT));
    }

    /**
     * Prefixes medialive-signed paths onto {@link #INTERNAL_PREFIX}.
     */
    static String rewritePath(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        if (path.equals(INTERNAL_PREFIX) || path.startsWith(INTERNAL_PREFIX + "/")) {
            return path;
        }
        return INTERNAL_PREFIX + stripTrailingSlash(path);
    }

    static String stripTrailingSlash(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
