package io.github.hectorvent.floci.services.aiops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.aiops.model.InvestigationGroup;
import io.github.hectorvent.floci.services.aiops.model.InvestigationGroup.CrossAccountConfiguration;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * CloudWatch investigations (AIOps) restJson1 — investigation-group lifecycle and resource policy.
 *
 * <p>Literal {@code /investigationGroups} paths take JAX-RS precedence over S3's {@code /{bucket}}
 * catch-all. Tag APIs share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AiOpsController {

    private final AiOpsService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public AiOpsController(AiOpsService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
        }
    }

    @POST
    @Path("/investigationGroups")
    public Response createInvestigationGroup(@Context HttpHeaders headers, String body) {
        InvestigationGroup group = service.create(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", group.getArn());
        return Response.ok(response).build();
    }

    @GET
    @Path("/investigationGroups")
    @Consumes(MediaType.WILDCARD)
    public Response listInvestigationGroups(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        AiOpsService.Page page = service.list(
                regionResolver.resolveRegion(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode groups = response.putArray("investigationGroups");
        for (InvestigationGroup group : page.groups()) {
            ObjectNode summary = groups.addObject();
            summary.put("arn", group.getArn());
            summary.put("name", group.getName());
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/investigationGroups/{identifier: .+}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response getInvestigationGroupPolicy(
            @Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        String region = regionResolver.resolveRegion(headers);
        InvestigationGroup group = service.get(region, identifier);
        String policy = service.getPolicy(region, identifier);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("investigationGroupArn", group.getArn());
        response.put("policy", policy);
        return Response.ok(response).build();
    }

    @POST
    @Path("/investigationGroups/{identifier: .+}/policy")
    public Response putInvestigationGroupPolicy(
            @Context HttpHeaders headers, @PathParam("identifier") String identifier, String body) {
        InvestigationGroup group = service.putPolicy(
                regionResolver.resolveRegion(headers), identifier, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("investigationGroupArn", group.getArn());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/investigationGroups/{identifier: .+}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response deleteInvestigationGroupPolicy(
            @Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        service.deletePolicy(regionResolver.resolveRegion(headers), identifier);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/investigationGroups/{identifier: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response getInvestigationGroup(
            @Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        InvestigationGroup group = service.get(regionResolver.resolveRegion(headers), identifier);
        return Response.ok(toDetail(group)).build();
    }

    @PATCH
    @Path("/investigationGroups/{identifier: .+}")
    public Response updateInvestigationGroup(
            @Context HttpHeaders headers, @PathParam("identifier") String identifier, String body) {
        service.update(regionResolver.resolveRegion(headers), identifier, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/investigationGroups/{identifier: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteInvestigationGroup(
            @Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        service.delete(regionResolver.resolveRegion(headers), identifier);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode toDetail(InvestigationGroup group) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("createdBy", group.getCreatedBy());
        response.put("createdAt", group.getCreatedAt());
        response.put("lastModifiedBy", group.getLastModifiedBy());
        response.put("lastModifiedAt", group.getLastModifiedAt());
        response.put("name", group.getName());
        response.put("arn", group.getArn());
        response.put("roleArn", group.getRoleArn());
        ObjectNode encryption = response.putObject("encryptionConfiguration");
        encryption.put("type", group.getEncryptionType() == null ? "AWS_OWNED_KEY" : group.getEncryptionType());
        if (group.getKmsKeyId() != null) {
            encryption.put("kmsKeyId", group.getKmsKeyId());
        }
        response.put("retentionInDays", group.getRetentionInDays());
        if (group.getChatbotNotificationChannel() != null) {
            ObjectNode channel = response.putObject("chatbotNotificationChannel");
            for (Map.Entry<String, List<String>> entry : group.getChatbotNotificationChannel().entrySet()) {
                ArrayNode arns = channel.putArray(entry.getKey());
                if (entry.getValue() != null) {
                    entry.getValue().forEach(arns::add);
                }
            }
        }
        if (group.getTagKeyBoundaries() != null) {
            ArrayNode boundaries = response.putArray("tagKeyBoundaries");
            group.getTagKeyBoundaries().forEach(boundaries::add);
        }
        response.put("isCloudTrailEventHistoryEnabled", group.isCloudTrailEventHistoryEnabled());
        if (group.getCrossAccountConfigurations() != null) {
            ArrayNode configurations = response.putArray("crossAccountConfigurations");
            for (CrossAccountConfiguration configuration : group.getCrossAccountConfigurations()) {
                ObjectNode entry = configurations.addObject();
                if (configuration.getSourceRoleArn() != null) {
                    entry.put("sourceRoleArn", configuration.getSourceRoleArn());
                }
            }
        }
        return response;
    }
}
