package io.github.hectorvent.floci.services.synthetics;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Rest-json SDKs resolve the error code from {@code X-Amzn-Errortype} before
 * the body {@code __type}. The shared {@code AwsExceptionMapper} only writes
 * the body; stamp the header on Synthetics error responses.
 */
@Provider
@Priority(1)
public class SyntheticsErrorHeaderFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getPath();
        if (path == null || !isSyntheticsPath(path)) {
            return;
        }
        if (response.getHeaders().getFirst("X-Amzn-Errortype") != null) {
            return;
        }
        Object entity = response.getEntity();
        if (entity instanceof AwsErrorResponse error) {
            response.getHeaders().putSingle("X-Amzn-Errortype", error.type());
        } else if (entity instanceof JsonNode node && node.hasNonNull("__type")) {
            response.getHeaders().putSingle("X-Amzn-Errortype", node.get("__type").asText());
        }
    }

    private static boolean isSyntheticsPath(String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.startsWith("synthetics/")) {
            normalized = normalized.substring("synthetics/".length());
        } else if ("synthetics".equals(normalized)) {
            return true;
        }
        return normalized.startsWith("canary")
                || normalized.startsWith("canaries")
                || normalized.startsWith("group")
                || normalized.startsWith("groups")
                || normalized.startsWith("runtime-versions")
                || normalized.startsWith("resource/");
    }
}
