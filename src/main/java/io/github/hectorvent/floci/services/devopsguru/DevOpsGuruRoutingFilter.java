package io.github.hectorvent.floci.services.devopsguru;

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
 * DevOps Guru restJson1 paths such as {@code /insights/{Id}} collide with SES
 * {@code /insights/{messageId}} and Audit Manager {@code /insights}. Floci serves
 * every service on one port, so SigV4 credential scope {@code devops-guru} is
 * rewritten onto an internal prefix the DevOps Guru controller owns.
 */
@Provider
@PreMatching
@Priority(10)
public class DevOpsGuruRoutingFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/devops-guru";

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isDevOpsGuru(requestContext.getHeaderString("Authorization"))) {
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

    static boolean isDevOpsGuru(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        return matcher.find()
                && DevOpsGuruService.SERVICE.equals(matcher.group(1).toLowerCase(Locale.ROOT));
    }
}
