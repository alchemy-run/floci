package io.github.hectorvent.floci.services.route53profiles;

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
 * the body; stamp the header on Route 53 Profiles error responses.
 */
@Provider
@Priority(1)
public class Route53ProfilesErrorHeaderFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getPath();
        if (path == null || !isRoute53ProfilesPath(path)) {
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

    private static boolean isRoute53ProfilesPath(String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.startsWith("aws-route53profiles/")) {
            normalized = normalized.substring("aws-route53profiles/".length());
        }
        return "profile".equals(normalized)
                || "profiles".equals(normalized)
                || normalized.startsWith("profile/")
                || normalized.startsWith("profileassociation")
                || normalized.startsWith("profileresourceassociation");
    }
}
