package io.floci.aws.services.auditmanager;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.List;

/**
 * Routes Audit Manager's REST paths to {@link AuditManagerController} when a request is signed for
 * {@code auditmanager}: other services serve {@code /controls}, {@code /insights}, {@code /settings},
 * {@code /services} and {@code /notifications} too. Tags stay on the shared {@code /tags} routes.
 */
@Provider
@PreMatching
@Priority(4989)
public class AuditManagerRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_auditmanager";
    private static final List<String> ROOTS = List.of("/account", "/assessments", "/assessmentFrameworks",
            "/assessmentFrameworkShareRequests", "/assessmentReports", "/controls", "/dataSourceKeywords",
            "/delegations", "/evidenceFileUploadUrl", "/insights", "/notifications", "/services", "/settings");

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
                .map("auditmanager"::equals)
                .orElse(host != null && host.startsWith("auditmanager."));
        if (signed) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
