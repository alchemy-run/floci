package io.github.hectorvent.floci.services.securityhub;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/** Disambiguates Security Hub's findings and membership paths from Macie. */
@Provider
@PreMatching
@Priority(5000)
public class SecurityHubRouteFilter implements ContainerRequestFilter {
    private static final String PREFIX = "/_securityhub";
    private static final List<String> SHARED_PATHS = List.of("/findings", "/members", "/invitations", "/administrator");

    @Override
    public void filter(ContainerRequestContext context) {
        URI uri = context.getUriInfo().getRequestUri();
        String path = uri.getRawPath();
        String decodedPath = uri.getPath();
        if (decodedPath.equals(PREFIX) || decodedPath.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-Errortype", "UnknownOperationException")
                    .entity(new AwsErrorResponse("UnknownOperationException", "Unknown operation")).build());
            return;
        }
        if (SHARED_PATHS.stream().noneMatch(shared -> path.equals(shared) || path.startsWith(shared + "/"))) {
            return;
        }
        Optional<String> service = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"));
        String host = context.getHeaderString("Host");
        boolean securityHub = service.map("securityhub"::equals)
                .orElse(host != null && host.startsWith("securityhub."));
        if (securityHub) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder()
                    .replacePath(PREFIX + path).buildFromEncoded());
        }
    }
}
