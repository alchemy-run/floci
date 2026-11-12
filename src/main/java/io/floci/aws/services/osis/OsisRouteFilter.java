package io.floci.aws.services.osis;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes OpenSearch Ingestion's control plane, every path under {@code /2022-01-01/osis/}, to {@link OsisController}.
 */
@Provider
@PreMatching
@Priority(4945)
public class OsisRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_osis";
    private static final String API = "/2022-01-01/osis/";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        if (path.startsWith(API)) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
