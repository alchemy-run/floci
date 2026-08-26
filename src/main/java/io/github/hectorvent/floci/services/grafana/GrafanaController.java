package io.github.hectorvent.floci.services.grafana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.grafana.model.GrafanaWorkspace;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Amazon Managed Grafana restJson1. Public AWS paths are {@code /versions}
 * and {@code /workspaces}; {@link GrafanaRoutingFilter} prefixes them so they
 * do not collide with S3 or AMP. Tag APIs share {@code /tags/{arn}}.
 */
@Path(GrafanaRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GrafanaController {

    private final GrafanaService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public GrafanaController(GrafanaService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @GET
    @Path("/versions")
    @Consumes(MediaType.WILDCARD)
    public Response listVersions(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken,
            @QueryParam("workspace-id") String workspaceId) {
        return Response.ok(service.listVersions(
                regionResolver.resolveRegion(headers), maxResults, nextToken, workspaceId)).build();
    }

    @POST
    @Path("/workspaces")
    public Response createWorkspace(@Context HttpHeaders headers, String body) {
        GrafanaWorkspace workspace = service.createWorkspace(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(service.workspaceResponse(workspace)).build();
    }

    @GET
    @Path("/workspaces")
    @Consumes(MediaType.WILDCARD)
    public Response listWorkspaces(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        return Response.ok(service.listWorkspacesPage(
                regionResolver.resolveRegion(headers), maxResults, nextToken)).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeWorkspace(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        GrafanaWorkspace workspace = service.describeWorkspace(
                regionResolver.resolveRegion(headers), workspaceId);
        return Response.ok(service.workspaceResponse(workspace)).build();
    }

    @PUT
    @Path("/workspaces/{workspaceId}")
    public Response updateWorkspace(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId, String body) {
        GrafanaWorkspace workspace = service.updateWorkspace(
                regionResolver.resolveRegion(headers), workspaceId, parse(body));
        return Response.ok(service.workspaceResponse(workspace)).build();
    }

    @DELETE
    @Path("/workspaces/{workspaceId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteWorkspace(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        GrafanaWorkspace workspace = service.deleteWorkspace(
                regionResolver.resolveRegion(headers), workspaceId);
        return Response.ok(service.workspaceResponse(workspace)).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}/authentication")
    @Consumes(MediaType.WILDCARD)
    public Response describeAuthentication(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        return Response.ok(service.describeAuthentication(
                regionResolver.resolveRegion(headers), workspaceId)).build();
    }

    @POST
    @Path("/workspaces/{workspaceId}/authentication")
    public Response updateAuthentication(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId, String body) {
        return Response.ok(service.updateAuthentication(
                regionResolver.resolveRegion(headers), workspaceId, parse(body))).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}/configuration")
    @Consumes(MediaType.WILDCARD)
    public Response describeConfiguration(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        return Response.ok(service.describeConfiguration(
                regionResolver.resolveRegion(headers), workspaceId)).build();
    }

    @PUT
    @Path("/workspaces/{workspaceId}/configuration")
    public Response updateConfiguration(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId, String body) {
        service.updateConfiguration(regionResolver.resolveRegion(headers), workspaceId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/workspaces/{workspaceId}/licenses/{licenseType}")
    @Consumes(MediaType.WILDCARD)
    public Response associateLicense(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @PathParam("licenseType") String licenseType,
            @HeaderParam("Grafana-Token") String grafanaToken) {
        GrafanaWorkspace workspace = service.associateLicense(
                regionResolver.resolveRegion(headers), workspaceId, licenseType, grafanaToken);
        return Response.ok(service.workspaceResponse(workspace)).build();
    }

    @DELETE
    @Path("/workspaces/{workspaceId}/licenses/{licenseType}")
    @Consumes(MediaType.WILDCARD)
    public Response disassociateLicense(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @PathParam("licenseType") String licenseType) {
        GrafanaWorkspace workspace = service.disassociateLicense(
                regionResolver.resolveRegion(headers), workspaceId, licenseType);
        return Response.ok(service.workspaceResponse(workspace)).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}/permissions")
    @Consumes(MediaType.WILDCARD)
    public Response listPermissions(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        return Response.ok(service.listPermissions(
                regionResolver.resolveRegion(headers), workspaceId)).build();
    }

    @PATCH
    @Path("/workspaces/{workspaceId}/permissions")
    public Response updatePermissions(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId, String body) {
        return Response.ok(service.updatePermissions(
                regionResolver.resolveRegion(headers), workspaceId, parse(body))).build();
    }

    @POST
    @Path("/workspaces/{workspaceId}/serviceaccounts")
    public Response createServiceAccount(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId, String body) {
        return Response.ok(service.createServiceAccount(
                regionResolver.resolveRegion(headers), workspaceId, parse(body))).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}/serviceaccounts")
    @Consumes(MediaType.WILDCARD)
    public Response listServiceAccounts(
            @Context HttpHeaders headers, @PathParam("workspaceId") String workspaceId) {
        return Response.ok(service.listServiceAccounts(
                regionResolver.resolveRegion(headers), workspaceId)).build();
    }

    @DELETE
    @Path("/workspaces/{workspaceId}/serviceaccounts/{serviceAccountId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteServiceAccount(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @PathParam("serviceAccountId") String serviceAccountId) {
        return Response.ok(service.deleteServiceAccount(
                regionResolver.resolveRegion(headers), workspaceId, serviceAccountId)).build();
    }

    @POST
    @Path("/workspaces/{workspaceId}/serviceaccounts/{serviceAccountId}/tokens")
    public Response createServiceAccountToken(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @PathParam("serviceAccountId") String serviceAccountId,
            String body) {
        return Response.ok(service.createServiceAccountToken(
                regionResolver.resolveRegion(headers), workspaceId, serviceAccountId, parse(body))).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}/serviceaccounts/{serviceAccountId}/tokens")
    @Consumes(MediaType.WILDCARD)
    public Response listServiceAccountTokens(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @PathParam("serviceAccountId") String serviceAccountId) {
        return Response.ok(service.listServiceAccountTokens(
                regionResolver.resolveRegion(headers), workspaceId, serviceAccountId)).build();
    }

    @DELETE
    @Path("/workspaces/{workspaceId}/serviceaccounts/{serviceAccountId}/tokens/{tokenId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteServiceAccountToken(
            @Context HttpHeaders headers,
            @PathParam("workspaceId") String workspaceId,
            @PathParam("serviceAccountId") String serviceAccountId,
            @PathParam("tokenId") String tokenId) {
        return Response.ok(service.deleteServiceAccountToken(
                regionResolver.resolveRegion(headers), workspaceId, serviceAccountId, tokenId)).build();
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
}
