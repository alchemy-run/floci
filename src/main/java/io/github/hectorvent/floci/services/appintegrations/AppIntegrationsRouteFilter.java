package io.github.hectorvent.floci.services.appintegrations;

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
import java.util.Optional;

/** Disambiguates AWS's /applications routes from AppConfig and EMR Serverless. */
@Provider
@PreMatching
@Priority(5000)
public class AppIntegrationsRouteFilter implements ContainerRequestFilter {

    static final String APPLICATIONS_PATH = "/_appintegrations/applications";

    @Override
    public void filter(ContainerRequestContext context) {
        URI uri = context.getUriInfo().getRequestUri();
        String path = uri.getRawPath();
        String decodedPath = uri.getPath();
        if (decodedPath.equals(APPLICATIONS_PATH) || decodedPath.startsWith(APPLICATIONS_PATH + "/")) {
            context.abortWith(Response.status(404).type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-Errortype", "UnknownOperationException")
                    .entity(new AwsErrorResponse("UnknownOperationException", "Unknown operation"))
                    .build());
            return;
        }
        if (!path.equals("/applications") && !path.startsWith("/applications/")) {
            return;
        }
        Optional<String> service = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"));
        String host = context.getHeaderString("Host");
        boolean matches = service.map(AppIntegrationsService.SERVICE::equals)
                .orElse(host != null && host.startsWith("app-integrations."));
        if (matches) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder()
                    .replacePath(APPLICATIONS_PATH + path.substring("/applications".length()))
                    .buildFromEncoded());
        }
    }
}
