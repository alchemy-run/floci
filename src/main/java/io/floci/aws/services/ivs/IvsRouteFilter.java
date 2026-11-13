package io.floci.aws.services.ivs;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Routes requests signed for {@code ivs} to {@link IvsController} or, for IVS Real-Time, {@link IvsRealtimeController}:
 * their paths ({@code /CreateChannel}, {@code /CreateStage}, {@code /tags/<arn>}) are other REST services' paths too.
 * Both APIs sign as {@code ivs}; a Real-Time request is one of its operations, or a tag route whose ARN names one of
 * its resources.
 */
@Provider
@PreMatching
@Priority(4974)
public class IvsRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_ivs";
    static final String REALTIME_PREFIX = "/_ivsrealtime";
    private static final Set<String> REALTIME_OPERATIONS = realtimeOperations();

    private static Set<String> realtimeOperations() {
        Set<String> operations = new HashSet<>();
        for (Method method : IvsRealtimeController.class.getDeclaredMethods()) {
            Path path = method.getAnnotation(Path.class);
            if (path != null && !path.value().startsWith("/tags")) {
                operations.add(path.value());
            }
        }
        return operations;
    }

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (internal(path, PREFIX) || internal(path, REALTIME_PREFIX)) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("ivs"::equals)
                .orElse(host != null && (host.startsWith("ivs.") || host.startsWith("ivsrealtime.")));
        if (signed && path.startsWith("/")) {
            String prefix = realtime(path) ? REALTIME_PREFIX : PREFIX;
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(prefix + path)
                    .buildFromEncoded());
        }
    }

    private static boolean internal(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /** Whether a request is IVS Real-Time's: one of its operations, or a tag route of one of its resources. */
    static boolean realtime(String path) {
        if (path.startsWith("/tags/")) {
            String arn = URLDecoder.decode(path.substring("/tags/".length()), StandardCharsets.UTF_8);
            if (!AwsArnUtils.isArnFor(arn, "ivs")) {
                return false;
            }
            String resource = AwsArnUtils.parse(arn).resource();
            return IvsRealtimeService.TAGGED.contains(resource.substring(0, Math.max(0, resource.indexOf('/'))));
        }
        return REALTIME_OPERATIONS.contains(path);
    }
}
