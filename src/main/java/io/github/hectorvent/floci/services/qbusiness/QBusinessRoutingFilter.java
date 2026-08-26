package io.github.hectorvent.floci.services.qbusiness;

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
 * Q Business, AppConfig, AppIntegrations, and AppRegistry all use {@code /applications}
 * on restJson1. Floci serves every service on one port, so SigV4 credential scope
 * {@code qbusiness} is rewritten onto an internal prefix that the Q Business
 * controller owns.
 */
@Provider
@PreMatching
@Priority(10)
public class QBusinessRoutingFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/qbusiness";

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isQBusiness(requestContext.getHeaderString("Authorization"))) {
            return;
        }
        URI original = requestContext.getUriInfo().getRequestUri();
        String path = original.getRawPath();
        if (path == null || path.startsWith(INTERNAL_PREFIX + "/")) {
            return;
        }
        if (!"/applications".equals(path) && !path.startsWith("/applications/")) {
            return;
        }
        URI rewritten = UriBuilder.fromUri(original)
                .replacePath(INTERNAL_PREFIX + path)
                .build();
        requestContext.setRequestUri(rewritten);
    }

    static boolean isQBusiness(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        return matcher.find()
                && QBusinessService.SERVICE.equals(matcher.group(1).toLowerCase(Locale.ROOT));
    }
}
