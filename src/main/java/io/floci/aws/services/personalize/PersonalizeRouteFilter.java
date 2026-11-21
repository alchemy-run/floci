package io.floci.aws.services.personalize;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes the Personalize runtime and events REST APIs, requests signed for {@code personalize} to a path other than
 * {@code /}, to {@link PersonalizeController}: their paths ({@code /events}, {@code /items}, {@code /users}, ...) are
 * shared with other services' REST APIs. The control plane stays JSON 1.1 on {@code POST /}.
 */
@Provider
@PreMatching
@Priority(4943)
public class PersonalizeRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_personalize";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("personalize"::equals)
                .orElse(host != null && (host.startsWith("personalize-runtime.")
                        || host.startsWith("personalize-events.")));
        if (signed && path.length() > 1 && context.getHeaderString("X-Amz-Target") == null) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
