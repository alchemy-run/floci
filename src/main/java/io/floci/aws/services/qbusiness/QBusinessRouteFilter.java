package io.floci.aws.services.qbusiness;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes requests signed for {@code qbusiness} or {@code qapps} to {@link QBusinessController}: their paths
 * ({@code /applications/...}, {@code /apps.list}, {@code /tags/...}) are shared with other services' REST APIs.
 */
@Provider
@PreMatching
@Priority(4942)
public class QBusinessRouteFilter implements ContainerRequestFilter {
    static final String QBUSINESS = "/_qbusiness-api";
    static final String QAPPS = "/_qapps-api";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.startsWith(QBUSINESS) || path.startsWith(QAPPS)) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        String scope = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .orElse(host != null && host.startsWith("qbusiness.") ? "qbusiness"
                        : host != null && host.startsWith("data.qapps.") ? "qapps" : "");
        String prefix = switch (scope) {
            case "qbusiness" -> QBUSINESS;
            case "qapps" -> QAPPS;
            default -> null;
        };
        if (prefix != null && path.startsWith("/")) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(prefix + path)
                    .buildFromEncoded());
        }
    }
}
