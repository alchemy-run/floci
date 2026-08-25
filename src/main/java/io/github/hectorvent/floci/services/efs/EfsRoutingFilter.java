package io.github.hectorvent.floci.services.efs;

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
 * EFS restJson1 paths live under {@code /2015-02-01/...}. Floci serves every
 * service on one port, so those paths would otherwise match S3's
 * {@code /{bucket}/{key}} catch-all. SigV4 credential scope
 * {@code elasticfilesystem} is rewritten onto an internal prefix the EFS
 * controller owns.
 */
@Provider
@PreMatching
@Priority(10)
public class EfsRoutingFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/aws-efs";
    static final String SERVICE = "elasticfilesystem";

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        URI original = requestContext.getUriInfo().getRequestUri();
        String path = original.getRawPath();
        boolean dated = path != null && (path.startsWith("/2015-02-01")
                || path.startsWith(INTERNAL_PREFIX + "/"));
        if (!dated && !isEfs(requestContext.getHeaderString("Authorization"))) {
            return;
        }
        String rewrittenPath = rewritePath(path);
        if (rewrittenPath == null || rewrittenPath.equals(path)) {
            return;
        }
        URI rewritten = UriBuilder.fromUri(original)
                .replacePath(rewrittenPath)
                .build();
        requestContext.setRequestUri(rewritten);
    }

    static boolean isEfs(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        return matcher.find()
                && SERVICE.equals(matcher.group(1).toLowerCase(Locale.ROOT));
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
        if (!normalized.startsWith("/2015-02-01")) {
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
