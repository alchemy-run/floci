package io.floci.aws.services.finspace;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Amazon FinSpace's data API ({@code finspace-api}) as it answers since FinSpace reached end of support on
 * 2026-10-07: no account can create a FinSpace environment any more, and the data API refuses every call made
 * without one with a plain-text 403, {@code Failed to retrieve environment}, which SDKs read as
 * AccessDeniedException. FinSpace's control plane is gone from AWS's DNS, so floci serves none of it either.
 */
@Provider
@PreMatching
@Priority(4979)
public class FinspaceDataRouteFilter implements ContainerRequestFilter {

    static final String MESSAGE = "Failed to retrieve environment";

    @Override
    public void filter(ContainerRequestContext context) {
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("finspace-api"::equals)
                .orElse(host != null && host.startsWith("finspace-api."));
        if (signed) {
            context.abortWith(Response.status(403).type(MediaType.TEXT_PLAIN_TYPE)
                    .header("x-amzn-ErrorType", "AccessDeniedException").entity(MESSAGE).build());
        }
    }
}
