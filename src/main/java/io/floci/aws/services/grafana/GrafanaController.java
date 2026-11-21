package io.floci.aws.services.grafana;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.function.Supplier;

/** The Amazon Managed Grafana REST API, routed here by {@link GrafanaRouteFilter}. */
@Path(GrafanaRouteFilter.PREFIX)
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class GrafanaController {

    private final GrafanaService service;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public GrafanaController(GrafanaService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- workspaces

    @POST
    @Path("/workspaces")
    public Response createWorkspace(String body) {
        return reply(202, () -> service.createWorkspace(parse(body), region()));
    }

    @GET
    @Path("/workspaces")
    public Response listWorkspaces(@QueryParam("maxResults") String maxResults,
                                   @QueryParam("nextToken") String nextToken) {
        return reply(200, () -> service.listWorkspaces(maxResults, nextToken, region()));
    }

    @GET
    @Path("/workspaces/{workspaceId}")
    public Response describeWorkspace(@PathParam("workspaceId") String workspaceId) {
        return reply(200, () -> service.describeWorkspace(workspaceId, region()));
    }

    @PUT
    @Path("/workspaces/{workspaceId}")
    public Response updateWorkspace(@PathParam("workspaceId") String workspaceId, String body) {
        return reply(202, () -> service.updateWorkspace(workspaceId, parse(body), region()));
    }

    @DELETE
    @Path("/workspaces/{workspaceId}")
    public Response deleteWorkspace(@PathParam("workspaceId") String workspaceId) {
        return reply(202, () -> service.deleteWorkspace(workspaceId, region()));
    }

    // ---------------------------------------------------------------- authentication and configuration

    @GET
    @Path("/workspaces/{workspaceId}/authentication")
    public Response describeWorkspaceAuthentication(@PathParam("workspaceId") String workspaceId) {
        return reply(200, () -> service.describeWorkspaceAuthentication(workspaceId, region()));
    }

    @POST
    @Path("/workspaces/{workspaceId}/authentication")
    public Response updateWorkspaceAuthentication(@PathParam("workspaceId") String workspaceId, String body) {
        return reply(200, () -> service.updateWorkspaceAuthentication(workspaceId, parse(body), region()));
    }

    @GET
    @Path("/workspaces/{workspaceId}/configuration")
    public Response describeWorkspaceConfiguration(@PathParam("workspaceId") String workspaceId) {
        return reply(200, () -> service.describeWorkspaceConfiguration(workspaceId, region()));
    }

    @PUT
    @Path("/workspaces/{workspaceId}/configuration")
    public Response updateWorkspaceConfiguration(@PathParam("workspaceId") String workspaceId, String body) {
        return reply(202, () -> service.updateWorkspaceConfiguration(workspaceId, parse(body), region()));
    }

    // ---------------------------------------------------------------- permissions and licenses

    @GET
    @Path("/workspaces/{workspaceId}/permissions")
    public Response listPermissions(@PathParam("workspaceId") String workspaceId,
                                    @QueryParam("userType") String userType, @QueryParam("userId") String userId,
                                    @QueryParam("groupId") String groupId, @QueryParam("maxResults") String maxResults,
                                    @QueryParam("nextToken") String nextToken) {
        return reply(200, () -> service.listPermissions(workspaceId, userType, userId, groupId, maxResults, nextToken,
                region()));
    }

    @PATCH
    @Path("/workspaces/{workspaceId}/permissions")
    public Response updatePermissions(@PathParam("workspaceId") String workspaceId, String body) {
        return reply(200, () -> service.updatePermissions(workspaceId, parse(body), region()));
    }

    @POST
    @Path("/workspaces/{workspaceId}/licenses/{licenseType}")
    public Response associateLicense(@PathParam("workspaceId") String workspaceId,
                                     @PathParam("licenseType") String licenseType,
                                     @HeaderParam("Grafana-Token") String grafanaToken) {
        return reply(202, () -> service.associateLicense(workspaceId, licenseType, grafanaToken, region()));
    }

    @DELETE
    @Path("/workspaces/{workspaceId}/licenses/{licenseType}")
    public Response disassociateLicense(@PathParam("workspaceId") String workspaceId,
                                        @PathParam("licenseType") String licenseType) {
        return reply(202, () -> service.disassociateLicense(workspaceId, licenseType, region()));
    }

    // ---------------------------------------------------------------- service accounts, tokens and API keys

    @POST
    @Path("/workspaces/{workspaceId}/serviceaccounts")
    public Response createWorkspaceServiceAccount(@PathParam("workspaceId") String workspaceId, String body) {
        return reply(200, () -> service.createServiceAccount(workspaceId, parse(body), region()));
    }

    @GET
    @Path("/workspaces/{workspaceId}/serviceaccounts")
    public Response listWorkspaceServiceAccounts(@PathParam("workspaceId") String workspaceId,
                                                 @QueryParam("maxResults") String maxResults,
                                                 @QueryParam("nextToken") String nextToken) {
        return reply(200, () -> service.listServiceAccounts(workspaceId, maxResults, nextToken, region()));
    }

    @DELETE
    @Path("/workspaces/{workspaceId}/serviceaccounts/{serviceAccountId}")
    public Response deleteWorkspaceServiceAccount(@PathParam("workspaceId") String workspaceId,
                                                  @PathParam("serviceAccountId") String serviceAccountId) {
        return reply(200, () -> service.deleteServiceAccount(workspaceId, serviceAccountId, region()));
    }

    @POST
    @Path("/workspaces/{workspaceId}/serviceaccounts/{serviceAccountId}/tokens")
    public Response createWorkspaceServiceAccountToken(@PathParam("workspaceId") String workspaceId,
                                                       @PathParam("serviceAccountId") String serviceAccountId,
                                                       String body) {
        return reply(200, () -> service.createToken(workspaceId, serviceAccountId, parse(body), region()));
    }

    @GET
    @Path("/workspaces/{workspaceId}/serviceaccounts/{serviceAccountId}/tokens")
    public Response listWorkspaceServiceAccountTokens(@PathParam("workspaceId") String workspaceId,
                                                      @PathParam("serviceAccountId") String serviceAccountId,
                                                      @QueryParam("maxResults") String maxResults,
                                                      @QueryParam("nextToken") String nextToken) {
        return reply(200, () -> service.listTokens(workspaceId, serviceAccountId, maxResults, nextToken, region()));
    }

    @DELETE
    @Path("/workspaces/{workspaceId}/serviceaccounts/{serviceAccountId}/tokens/{tokenId}")
    public Response deleteWorkspaceServiceAccountToken(@PathParam("workspaceId") String workspaceId,
                                                       @PathParam("serviceAccountId") String serviceAccountId,
                                                       @PathParam("tokenId") String tokenId) {
        return reply(200, () -> service.deleteToken(workspaceId, serviceAccountId, tokenId, region()));
    }

    @POST
    @Path("/workspaces/{workspaceId}/apikeys")
    public Response createWorkspaceApiKey(@PathParam("workspaceId") String workspaceId, String body) {
        return reply(200, () -> service.createApiKey(workspaceId, parse(body), region()));
    }

    @DELETE
    @Path("/workspaces/{workspaceId}/apikeys/{keyName}")
    public Response deleteWorkspaceApiKey(@PathParam("workspaceId") String workspaceId,
                                          @PathParam("keyName") String keyName) {
        return reply(200, () -> service.deleteApiKey(workspaceId, keyName, region()));
    }

    // ---------------------------------------------------------------- versions and tags

    @GET
    @Path("/versions")
    public Response listVersions(@QueryParam("workspace-id") String workspaceId,
                                 @QueryParam("maxResults") String maxResults,
                                 @QueryParam("nextToken") String nextToken) {
        return reply(200, () -> service.listVersions(workspaceId, maxResults, nextToken, region()));
    }

    @GET
    @Path("/tags/{resourceArn: .+}")
    public Response listTagsForResource(@PathParam("resourceArn") String resourceArn) {
        return reply(200, () -> service.listTags(resourceArn, region()));
    }

    @POST
    @Path("/tags/{resourceArn: .+}")
    public Response tagResource(@PathParam("resourceArn") String resourceArn, String body) {
        return reply(200, () -> service.tagResource(resourceArn, parse(body), region()));
    }

    @DELETE
    @Path("/tags/{resourceArn: .+}")
    public Response untagResource(@PathParam("resourceArn") String resourceArn,
                                  @QueryParam("tagKeys") List<String> tagKeys) {
        return reply(200, () -> service.untagResource(resourceArn, tagKeys, region()));
    }

    // ---------------------------------------------------------------- shared

    private String region() {
        return regions.getRegion();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                throw GrafanaService.validation("The request body must be a JSON object.", "body");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw GrafanaService.validation("The request body is not valid JSON: " + e.getOriginalMessage(), "body");
        }
    }

    private Response reply(int status, Supplier<ObjectNode> call) {
        try {
            return Response.status(status).type(MediaType.APPLICATION_JSON).entity(call.get().toString()).build();
        } catch (AwsException e) {
            ObjectNode error = mapper.createObjectNode();
            error.put("message", e.getMessage());
            if (e.getExtendedData() != null) {
                e.getExtendedData().forEach((field, value) -> error.set(field, mapper.valueToTree(value)));
            }
            return Response.status(e.getHttpStatus()).type(MediaType.APPLICATION_JSON)
                    .header("x-amzn-ErrorType", e.getErrorCode()).entity(error.toString()).build();
        }
    }
}
