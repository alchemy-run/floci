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
 *
 * <p>Function URL invocations are rewritten to {@code /lambda-url/{urlId}/...}
 * before this filter. Prefixing those paths would 404 the Lambda fixture the
 * Bindings suite probes at {@code /bindings}.
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
        String host = requestContext.getHeaderString("Host");
        if (isLambdaUrlHost(host)) {
            return;
        }
        if (!isPersonalize(requestContext.getHeaderString("Authorization"))
                && !isPersonalizeHost(host)) {
            return;
        }
        String contentType = requestContext.getHeaderString("Content-Type");
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("x-amz-json-1.1")) {
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

    static boolean isPersonalize(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        return matcher.find()
                && PersonalizeService.SERVICE.equals(matcher.group(1).toLowerCase(Locale.ROOT));
    }

    static boolean isPersonalizeHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String hostname = host.split(":")[0].toLowerCase(Locale.ROOT);
        return hostname.matches("personalize-events(-fips)?\\.[a-z0-9-]+\\.amazonaws\\.com")
                || hostname.matches("personalize-runtime(-fips)?\\.[a-z0-9-]+\\.amazonaws\\.com")
                || hostname.matches("personalize(-fips)?\\.[a-z0-9-]+\\.amazonaws\\.com");
    }

    static boolean isLambdaUrlHost(String host) {
        return host != null && host.toLowerCase(Locale.ROOT).contains(".lambda-url.");
    }

    static String rewritePath(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        String normalized = stripTrailingSlash(path);
        if (isLambdaUrlPath(normalized)) {
            return path;
        }
        if (normalized.equals(EVENTS_PREFIX)
                || normalized.startsWith(EVENTS_PREFIX + "/")
                || normalized.equals(RUNTIME_PREFIX)
                || normalized.startsWith(RUNTIME_PREFIX + "/")) {
            return path;
        }
        if (EVENT_PATHS.contains(normalized)) {
            return EVENTS_PREFIX + normalized;
        }
        if (RUNTIME_PATHS.contains(normalized)) {
            return RUNTIME_PREFIX + normalized;
        }
        return path;
    }

    static boolean isLambdaUrlPath(String path) {
        return "/lambda-url".equals(path) || path.startsWith("/lambda-url/");
    }

    static String stripTrailingSlash(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
