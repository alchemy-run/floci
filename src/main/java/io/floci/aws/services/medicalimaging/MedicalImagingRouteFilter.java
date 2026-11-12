package io.floci.aws.services.medicalimaging;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes requests signed for {@code medical-imaging} to {@link MedicalImagingController}: HealthImaging paths
 * ({@code /datastore}, {@code /tags/{arn}}, ...) are shared with other services' REST APIs. Image set operations go to
 * the {@code runtime-medical-imaging} host, the rest to {@code medical-imaging}.
 */
@Provider
@PreMatching
@Priority(4945)
public class MedicalImagingRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_medicalimaging";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("medical-imaging"::equals)
                .orElse(host != null && (host.startsWith("medical-imaging.") || host.startsWith("runtime-medical-imaging.")));
        if (signed && path.startsWith("/")) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
