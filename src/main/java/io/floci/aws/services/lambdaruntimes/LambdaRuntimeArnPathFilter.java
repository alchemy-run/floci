package io.floci.aws.services.lambdaruntimes;

import io.floci.aws.core.common.AwsArnUtils;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code RuntimeName} label may be the runtime's ARN, whose resource part
 * ({@code runtime/<name>}) holds a slash that no single path segment can match. Rewrites
 * {@code /2026-10-01/runtimes/<arn>/...} to {@code /2026-10-01/runtimes/<name>/...} before
 * routing, so every runtime route accepts a name or an ARN.
 */
@Provider
@PreMatching
public class LambdaRuntimeArnPathFilter implements ContainerRequestFilter {

    private static final Pattern RUNTIME_ARN_PATH = Pattern.compile(
            "^/2026-10-01/runtimes/arn:" + AwsArnUtils.PARTITION_REGEX
                    + ":lambda:[a-z0-9-]+:\\d{12}:runtime/([^/]+)(/.*)?$");

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getPath();
        Matcher matcher = RUNTIME_ARN_PATH.matcher(path);
        if (!matcher.matches()) {
            return;
        }
        String rest = matcher.group(2) == null ? "" : matcher.group(2);
        context.setRequestUri(UriBuilder.fromUri(context.getUriInfo().getRequestUri())
                .replacePath("/2026-10-01/runtimes/" + matcher.group(1) + rest)
                .build());
    }
}
