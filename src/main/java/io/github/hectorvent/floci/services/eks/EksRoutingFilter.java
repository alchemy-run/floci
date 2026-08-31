package io.github.hectorvent.floci.services.eks;

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
 * EKS restJson1 paths such as {@code /clusters}, {@code /access-policies},
 * {@code /cluster-versions}, and {@code /addons/supported-versions} collide with
 * S3's path-style catch-all. Floci serves every service on one port, so SigV4
 * credential scope {@code eks} (or Host {@code eks.{region}}) is rewritten onto
 * an internal prefix the EKS controller owns. Tag APIs stay on {@code /tags/{arn}}
 * and are dispatched by {@code SharedTagsController}.
 *
 * <p>Function URL invocations are rewritten to {@code /lambda-url/{urlId}/...}
 * before this filter. Prefixing those paths with {@code /aws-eks} 404s the
 * Lambda fixture the Bindings suite probes at {@code /bindings}.
 */
@Provider
@PreMatching
@Priority(10)
public class EksRoutingFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/aws-eks";

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String host = requestContext.getHeaderString("Host");
        if (isLambdaUrlHost(host)) {
            return;
        }
        if (!isEks(requestContext.getHeaderString("Authorization"))
                && !isEksHost(host)) {
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

    static boolean isEks(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        return matcher.find()
                && EksService.SERVICE.equals(matcher.group(1).toLowerCase(Locale.ROOT));
    }

    /**
     * Control-plane {@code eks.{region}.amazonaws.com} (and FIPS).
     */
    static boolean isEksHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String hostname = host.split(":")[0].toLowerCase(Locale.ROOT);
        return hostname.matches("eks(-fips)?\\.[a-z0-9-]+\\.amazonaws\\.com")
                || hostname.matches("fips\\.eks\\.[a-z0-9-]+\\.amazonaws\\.com");
    }

    static boolean isLambdaUrlHost(String host) {
        return host != null && host.toLowerCase(Locale.ROOT).contains(".lambda-url.");
    }

    /**
     * Prefixes eks-signed paths onto {@link #INTERNAL_PREFIX}, stripping a trailing
     * slash so RestAssured and the SDK hit the same resource. {@code /tags} is left
     * alone for {@code SharedTagsController}. Function URL invocations stay on
     * {@code /lambda-url/{urlId}/...}.
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
        if (isLambdaUrlPath(normalized)) {
            return path;
        }
        return INTERNAL_PREFIX + normalized;
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
