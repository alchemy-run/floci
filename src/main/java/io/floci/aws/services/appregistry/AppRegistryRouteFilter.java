package io.floci.aws.services.appregistry;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.List;

/**
 * Routes AppRegistry's REST paths ({@code /applications}, {@code /attribute-groups},
 * {@code /configuration}, {@code /sync}) to {@link AppRegistryController}: AppRegistry signs with
 * {@code servicecatalog}, and AppConfig, EMR Serverless and AppIntegrations share
 * {@code /applications}.
 */
@Provider
@PreMatching
@Priority(4990)
public class AppRegistryRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_appregistry";
    private static final List<String> ROOTS = List.of("/applications", "/attribute-groups", "/configuration",
            "/sync");

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        boolean ours = false;
        for (String root : ROOTS) {
            ours |= path.equals(root) || path.startsWith(root + "/");
        }
        if (!ours) {
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("servicecatalog"::equals)
                .orElse(host != null && host.startsWith("servicecatalog-appregistry."));
        if (signed) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
