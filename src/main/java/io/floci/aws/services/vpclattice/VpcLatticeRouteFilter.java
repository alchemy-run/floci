package io.floci.aws.services.vpclattice;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes requests signed for {@code vpc-lattice} to {@link VpcLatticeController}: its paths
 * ({@code /services/...}, {@code /targetgroups/...}, {@code /tags/...}) are shared with other services' REST APIs.
 */
@Provider
@PreMatching
@Priority(4943)
public class VpcLatticeRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_vpclattice";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("vpc-lattice"::equals)
                .orElse(host != null && host.startsWith("vpc-lattice."));
        if (signed && path.startsWith("/")) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
