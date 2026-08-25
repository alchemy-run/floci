package io.github.hectorvent.floci.services.amazonmq;

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
 * Amazon MQ restJson1 tag paths {@code /v1/tags/{arn}} collide with AppSync.
 * Floci serves every service on one port, so SigV4 credential scope {@code mq}
 * on those paths is rewritten onto an internal prefix the MQ controller owns.
 * Broker and configuration paths stay on {@code /v1/...} because they do not
 * collide and existing unsigned RestAssured tests hit them directly.
 */
@Provider
@PreMatching
@Priority(10)
public class AmazonMqRoutingFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/aws-mq";

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isMq(requestContext.getHeaderString("Authorization"))) {
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

    static boolean isMq(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        return matcher.find()
                && AmazonMqService.SERVICE.equals(matcher.group(1).toLowerCase(Locale.ROOT));
    }

    /**
     * Prefixes mq-signed {@code /v1/tags} paths onto {@link #INTERNAL_PREFIX}.
     * Other mq paths (brokers, configurations, engine types) are left on the
     * public URI so they continue to match {@link AmazonMqController}.
     */
    static String rewritePath(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        if (path.equals(INTERNAL_PREFIX) || path.startsWith(INTERNAL_PREFIX + "/")) {
            return path;
        }
        String normalized = stripTrailingSlash(path);
        if ("/v1/tags".equals(normalized) || normalized.startsWith("/v1/tags/")) {
            return INTERNAL_PREFIX + normalized;
        }
        return path;
    }

    static String stripTrailingSlash(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
