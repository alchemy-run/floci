package io.github.hectorvent.floci.services.macie2;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/** Disambiguates Macie classification-job reads from IoT job reads. */
@Provider
@PreMatching
@Priority(5001)
public class MacieRouteFilter implements ContainerRequestFilter {
    private static final String PREFIX = "/_macie2";

    @Override
    public void filter(ContainerRequestContext context) {
        var uri = context.getUriInfo().getRequestUri();
        String decoded = uri.getPath();
        if (decoded.equals(PREFIX) || decoded.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-Errortype", "UnknownOperationException")
                    .entity(new AwsErrorResponse("UnknownOperationException", "Unknown operation")).build());
            return;
        }
        if (!"GET".equals(context.getMethod()) || !uri.getRawPath().startsWith("/jobs/")) {
            return;
        }
        String host = context.getHeaderString("Host");
        boolean macie = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("macie2"::equals).orElse(host != null && host.startsWith("macie2."));
        if (macie) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder()
                    .replacePath(PREFIX + uri.getRawPath()).buildFromEncoded());
        }
    }
}
