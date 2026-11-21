package io.floci.aws.services.deadline;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes requests signed for {@code deadline} to {@link DeadlineController}: Deadline Cloud paths
 * ({@code /2023-10-12/farms}, {@code /2023-10-12/tags/{resourceArn}}, ...) are routed by signing name and host.
 */
@Provider
@PreMatching
@Priority(4984)
public class DeadlineRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_deadline";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("deadline"::equals)
                .orElse(host != null && (host.startsWith("deadline.") || host.startsWith("management.deadline.")
                        || host.startsWith("scheduling.deadline.")));
        if (signed && path.startsWith("/")) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
