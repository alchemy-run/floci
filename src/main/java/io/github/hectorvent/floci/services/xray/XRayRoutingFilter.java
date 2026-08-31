package io.github.hectorvent.floci.services.xray;

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
 * X-Ray restJson1 tag APIs share {@code /TagResource} with other services.
 * Requests signed for {@code xray} are rewritten onto an internal prefix the
 * controller owns.
 */
@Provider
@PreMatching
@Priority(10)
public class XRayRoutingFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/xray";

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isXRay(requestContext.getHeaderString("Authorization"))) {
            return;
        }
        URI original = requestContext.getUriInfo().getRequestUri();
        String path = original.getRawPath();
        if (path == null || path.equals(INTERNAL_PREFIX) || path.startsWith(INTERNAL_PREFIX + "/")) {
            return;
        }
        URI rewritten = UriBuilder.fromUri(original)
                .replacePath(INTERNAL_PREFIX + path)
                .build();
        requestContext.setRequestUri(rewritten);
    }

    static boolean isXRay(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        return matcher.find() && XRayService.SERVICE.equals(matcher.group(1).toLowerCase(Locale.ROOT));
    }
}
