package io.github.hectorvent.floci.services.sagemaker;

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
 * SageMaker Feature Store Runtime restJson1 paths ({@code /FeatureGroup/...},
 * {@code /BatchGetRecord}, {@code /BatchWriteRecord}) collide with S3's
 * catch-all. SigV4 credential scope {@code sagemaker} or Host
 * {@code featurestore-runtime.sagemaker.{region}.amazonaws.com} is rewritten
 * onto an internal prefix the Feature Store controller owns. JSON 1.1
 * control-plane posts ({@code SageMaker.*}) stay on {@code /}.
 *
 * <p>Function URL invocations are rewritten to {@code /lambda-url/{urlId}/...}
 * before this filter. Prefixing those paths would 404 the Lambda fixture the
 * Bindings suite probes at {@code /bindings}.
 */
@Provider
@PreMatching
@Priority(10)
public class SageMakerFeatureStoreRoutingFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/sagemaker-featurestore";

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    private static final Set<String> BATCH_PATHS = Set.of("/BatchGetRecord", "/BatchWriteRecord");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String host = requestContext.getHeaderString("Host");
        if (isLambdaUrlHost(host)) {
            return;
        }
        if (!isSageMaker(requestContext.getHeaderString("Authorization"))
                && !isFeatureStoreHost(host)) {
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

    static boolean isSageMaker(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        return matcher.find()
                && SageMakerService.SERVICE.equals(matcher.group(1).toLowerCase(Locale.ROOT));
    }

    static boolean isFeatureStoreHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String hostname = host.split(":")[0].toLowerCase(Locale.ROOT);
        return hostname.matches(
                "featurestore-runtime(-fips)?\\.sagemaker(-fips)?\\.[a-z0-9-]+\\.amazonaws\\.com");
    }

    static boolean isLambdaUrlHost(String host) {
        return host != null && host.toLowerCase(Locale.ROOT).contains(".lambda-url.");
    }

    static String rewritePath(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        if (path.equals(INTERNAL_PREFIX) || path.startsWith(INTERNAL_PREFIX + "/")) {
            return path;
        }
        String normalized = stripTrailingSlash(path);
        if (isLambdaUrlPath(normalized)) {
            return path;
        }
        if (normalized.startsWith("/FeatureGroup/") || BATCH_PATHS.contains(normalized)) {
            return INTERNAL_PREFIX + normalized;
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
