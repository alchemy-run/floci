package io.github.hectorvent.floci.services.notificationscontacts;

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
 * Notifications Contacts restJson1 paths such as {@code /2022-09-19/emailcontacts}
 * collide with S3 path-style routes. Requests signed for
 * {@code notifications-contacts} are rewritten onto an internal prefix the
 * controller owns. Tag APIs stay on {@code /tags/{arn}} for
 * {@code SharedTagsController}.
 */
@Provider
@PreMatching
@Priority(10)
public class NotificationsContactsRoutingFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/aws-notifications-contacts";

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isNotificationsContacts(requestContext.getHeaderString("Authorization"))) {
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

    static boolean isNotificationsContacts(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        return matcher.find()
                && NotificationsContactsService.SERVICE.equals(matcher.group(1).toLowerCase(Locale.ROOT));
    }

    static String rewritePath(String path) {
        // Literal /emailcontacts and /2022-09-19/emailcontacts already beat S3's
        // /{bucket} templates; do not prefix (the controller is mounted at /).
        return path;
    }

    static String stripTrailingSlash(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
