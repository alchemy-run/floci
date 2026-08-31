package io.github.hectorvent.floci.services.globalaccelerator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for AWS Global Accelerator. Dispatched from
 * {@code AwsJson11Controller} under the {@code GlobalAccelerator_V20180706.}
 * target prefix.
 */
@ApplicationScoped
public class GlobalAcceleratorJsonHandler {

    private static final Logger LOG = Logger.getLogger(GlobalAcceleratorJsonHandler.class);

    private final GlobalAcceleratorService service;
    private final ObjectMapper objectMapper;

    @Inject
    public GlobalAcceleratorJsonHandler(GlobalAcceleratorService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Global Accelerator action: {0} region={1}", action, region);
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        return switch (action) {
            case "CreateAccelerator" -> ok(service.createAccelerator(body));
            case "DescribeAccelerator" -> ok(service.describeAccelerator(body));
            case "UpdateAccelerator" -> ok(service.updateAccelerator(body));
            case "DeleteAccelerator" -> ok(service.deleteAccelerator(body));
            case "ListAccelerators" -> ok(service.listAccelerators(body));
            case "DescribeAcceleratorAttributes" -> ok(service.describeAcceleratorAttributes(body));
            case "UpdateAcceleratorAttributes" -> ok(service.updateAcceleratorAttributes(body));
            case "CreateListener" -> ok(service.createListener(body));
            case "DescribeListener" -> ok(service.describeListener(body));
            case "UpdateListener" -> ok(service.updateListener(body));
            case "DeleteListener" -> ok(service.deleteListener(body));
            case "ListListeners" -> ok(service.listListeners(body));
            case "CreateEndpointGroup" -> ok(service.createEndpointGroup(body));
            case "DescribeEndpointGroup" -> ok(service.describeEndpointGroup(body));
            case "UpdateEndpointGroup" -> ok(service.updateEndpointGroup(body));
            case "DeleteEndpointGroup" -> ok(service.deleteEndpointGroup(body));
            case "ListEndpointGroups" -> ok(service.listEndpointGroups(body));
            case "AddEndpoints" -> ok(service.addEndpoints(body));
            case "RemoveEndpoints" -> ok(service.removeEndpoints(body));
            case "TagResource" -> ok(service.tagResource(body));
            case "UntagResource" -> ok(service.untagResource(body));
            case "ListTagsForResource" -> ok(service.listTagsForResource(body));
            default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(
                    GlobalAcceleratorService.TARGET_PREFIX + action);
        };
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
