package io.floci.aws.services.lambdaruntimes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.AwsArnUtils;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Lambda's generic resource-based policy operations (apiVersion 2026-07-09). Floci serves them for
 * customer-managed runtimes, which share them with consumers; any other resource answers
 * {@code ResourceNotFoundException}.
 */
@Path("/2026-07-09")
@Produces(MediaType.APPLICATION_JSON)
public class LambdaResourcePolicyController {

    private final LambdaRuntimesService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaResourcePolicyController(LambdaRuntimesService service, RegionResolver regionResolver,
                                          ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @PUT
    @Path("/resource-policy/{resourceArn: .+}")
    public Response putResourcePolicy(@Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn,
                                      String body) {
        JsonNode request = parse(body);
        LambdaRuntimesService.Policy policy = service.putPolicy(region(headers, resourceArn), requireRuntime(resourceArn),
                text(request, "Policy"), text(request, "RevisionId"));
        return Response.ok(policyNode(policy)).build();
    }

    @GET
    @Path("/resource-policy/{resourceArn: .+}")
    public Response getResourcePolicy(@Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn) {
        return Response.ok(policyNode(service.getPolicy(region(headers, resourceArn), requireRuntime(resourceArn))))
                .build();
    }

    @DELETE
    @Path("/resource-policy/{resourceArn: .+}")
    public Response deleteResourcePolicy(@Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn,
                                         @QueryParam("RevisionId") String revisionId) {
        service.deletePolicy(region(headers, resourceArn), requireRuntime(resourceArn), revisionId);
        return Response.noContent().build();
    }

    private static String requireRuntime(String resourceArn) {
        if (!LambdaRuntimesService.ownsArn(resourceArn)) {
            throw new AwsException("ResourceNotFoundException", "The resource you requested does not exist.", 404);
        }
        return resourceArn;
    }

    /** The ARN's own region, so a policy lands on the runtime the ARN names. */
    private String region(HttpHeaders headers, String resourceArn) {
        return AwsArnUtils.regionOrDefault(resourceArn, regionResolver.resolveRegion(headers));
    }

    private ObjectNode policyNode(LambdaRuntimesService.Policy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Policy", policy.policy());
        node.put("RevisionId", policy.revisionId());
        return node;
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException", "Malformed request body", 400);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
