package io.floci.aws.services.kinesisvideo;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes requests signed for {@code kinesisvideo} to {@link KvsController}: its paths ({@code /createStream},
 * {@code /TagResource}, {@code /v1/get-ice-server-config} and the rest) could be other REST services' paths too.
 * Playback and signaling under {@code /_floci/kinesisvideo/} are left alone.
 */
@Provider
@PreMatching
@Priority(4966)
public class KvsRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_kinesisvideo";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map(KvsStore.SERVICE::equals)
                .orElse(host != null && host.contains(".kinesisvideo."));
        if (signed && path.startsWith("/") && !path.startsWith("/_floci/")) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
