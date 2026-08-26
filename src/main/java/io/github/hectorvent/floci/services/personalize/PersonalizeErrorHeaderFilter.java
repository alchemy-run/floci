package io.github.hectorvent.floci.services.personalize;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Rest-json SDKs resolve the error code from {@code X-Amzn-Errortype} before
 * the body {@code __type}. JSON 1.1 errors from {@code JsonErrorResponseUtils}
 * only write the body; stamp the header so Alchemy's distilled client (inside
 * the Bindings Lambda) decodes {@code InvalidInputException} /
 * {@code ResourceNotFoundException} instead of an untagged failure.
 */
@Provider
@Priority(1)
public class PersonalizeErrorHeaderFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (!isPersonalizeRequest(request)) {
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

    static boolean isPersonalizeRequest(ContainerRequestContext request) {
        if (PersonalizeRoutingFilter.isLambdaUrlHost(request.getHeaderString("Host"))) {
            return false;
        }
        if (PersonalizeRoutingFilter.isPersonalize(request.getHeaderString("Authorization"))
                || PersonalizeRoutingFilter.isPersonalizeHost(request.getHeaderString("Host"))) {
            return true;
        }
        String target = request.getHeaderString("X-Amz-Target");
        if (target != null && target.startsWith("AmazonPersonalize.")) {
            return true;
        }
        String path = request.getUriInfo() != null ? request.getUriInfo().getPath() : null;
        return path != null && (path.contains("personalize-events") || path.contains("personalize-runtime"));
    }
}
