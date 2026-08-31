package io.github.hectorvent.floci.services.ssmincidents;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Rest-json SDKs resolve the error code from {@code X-Amzn-Errortype} before
 * the body {@code __type}. Stamp the header on Incident Manager error responses.
 */
@Provider
@Priority(1)
public class SsmIncidentsErrorHeaderFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getPath();
        if (path == null || !isIncidentsPath(path)) {
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

    private static boolean isIncidentsPath(String path) {
        return path.contains("ReplicationSet")
                || path.contains("ResponsePlan")
                || path.contains("DeletionProtection")
                || path.contains("IncidentRecord")
                || path.contains("TimelineEvent")
                || path.contains("RelatedItem")
                || path.contains("IncidentFinding")
                || path.contains("startIncident")
                || path.contains("ResourcePolic");
    }
}
