package io.github.hectorvent.floci.services.sagemaker;

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
 * SageMaker Runtime restJson1 paths ({@code /endpoints/{name}/invocations},
 * {@code /async-invocations}, {@code /invocations-response-stream}) collide
 * with S3's catch-all. SigV4 credential scope {@code sagemaker} is rewritten
 * onto an internal prefix the runtime controller owns. JSON 1.1 control-plane
 * posts ({@code SageMaker.*}) stay on {@code /}.
 */
@Provider
@PreMatching
@Priority(10)
public class SageMakerRuntimeRoutingFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/sagemaker-runtime";

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    private static final Pattern INVOKE_PATH = Pattern.compile(
            "/endpoints/[^/]+/(invocations|async-invocations|invocations-response-stream)");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isSageMaker(requestContext.getHeaderString("Authorization"))) {
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

    static String rewritePath(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        if (path.equals(INTERNAL_PREFIX) || path.startsWith(INTERNAL_PREFIX + "/")) {
            return path;
        }
        String normalized = stripTrailingSlash(path);
        if (INVOKE_PATH.matcher(normalized).matches()) {
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
