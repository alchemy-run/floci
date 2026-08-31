package io.github.hectorvent.floci.services.osis;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Rest-json SDKs resolve the error code from {@code X-Amzn-Errortype} before
 * the body {@code __type}. The shared {@code AwsExceptionMapper} only writes
 * the body; stamp the header for OSIS paths.
 */
@Provider
@Priority(1)
public class OsisErrorHeaderFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getPath();
        if (path == null || !path.contains("2022-01-01/osis")) {
            return;
        }
        Object entity = response.getEntity();
        if (entity instanceof AwsErrorResponse error) {
            response.getHeaders().putSingle("X-Amzn-Errortype", error.type());
        }
    }
}
