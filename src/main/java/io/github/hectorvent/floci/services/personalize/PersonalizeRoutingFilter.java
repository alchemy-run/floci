package io.github.hectorvent.floci.services.personalize;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Personalize events and runtime restJson1 paths ({@code /events}, {@code /items},
 * {@code /recommendations}, …) collide with Application Signals, FIS, DevOps Guru,
 * and S3's catch-all. SigV4 credential scope {@code personalize} is rewritten onto
 * internal prefixes the Personalize controllers own.
 */
@Provider
@PreMatching
@Priority(10)
public class PersonalizeRoutingFilter implements ContainerRequestFilter {

    static final String EVENTS_PREFIX = "/personalize-events";
    static final String RUNTIME_PREFIX = "/personalize-runtime";

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    private static final Set<String> EVENT_PATHS = Set.of(
            "/events", "/items", "/users", "/actions", "/action-interactions");
    private static final Set<String> RUNTIME_PATHS = Set.of(
            "/recommendations", "/personalize-ranking", "/action-recommendations");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isPersonalize(requestContext.getHeaderString("Authorization"))) {
            return;
        }
        URI original = requestContext.getUriInfo().getRequestUri();
        String path = original.getRawPath();
        if (path == null
                || path.startsWith(EVENTS_PREFIX + "/")
                || path.startsWith(RUNTIME_PREFIX + "/")) {
            return;
        }
        String prefix = null;
        if (EVENT_PATHS.contains(path)) {
            prefix = EVENTS_PREFIX;
        } else if (RUNTIME_PATHS.contains(path)) {
            prefix = RUNTIME_PREFIX;
        }
        if (prefix == null) {
            return;
        }
        URI rewritten = UriBuilder.fromUri(original)
                .replacePath(prefix + path)
                .build();
        requestContext.setRequestUri(rewritten);
    }

    static boolean isPersonalize(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        return matcher.find()
                && PersonalizeService.SERVICE.equals(matcher.group(1).toLowerCase(Locale.ROOT));
    }
}
