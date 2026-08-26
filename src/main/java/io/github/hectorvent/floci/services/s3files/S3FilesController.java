package io.github.hectorvent.floci.services.s3files;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
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

import java.util.List;
import java.util.function.Supplier;

/**
 * Amazon S3 Files restJson1. Public AWS paths are {@code /file-systems},
 * {@code /access-points} and {@code /resource-tags/{resourceId}}.
 * {@link S3FilesRoutingFilter} prefixes SigV4-{@code s3files} traffic so it
 * does not collide with S3 path-style routes.
 */
@Path(S3FilesRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class S3FilesController {

    private final S3FilesService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public S3FilesController(
            S3FilesService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @PUT
    @Path("/file-systems")
    public Response createFileSystem(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.createFileSystem(region, parse(body))).build());
    }

    @GET
    @Path("/file-systems")
    @Consumes(MediaType.WILDCARD)
    public Response listFileSystems(
            @Context HttpHeaders headers,
            @QueryParam("bucket") String bucket,
            @QueryParam("maxResults") Integer maxResults) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.listFileSystems(region, bucket, maxResults)).build());
    }

    @GET
    @Path("/file-systems/{fileSystemId}")
    @Consumes(MediaType.WILDCARD)
    public Response getFileSystem(
            @Context HttpHeaders headers, @PathParam("fileSystemId") String fileSystemId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.getFileSystem(region, fileSystemId)).build());
    }

    @DELETE
    @Path("/file-systems/{fileSystemId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteFileSystem(
            @Context HttpHeaders headers,
            @PathParam("fileSystemId") String fileSystemId,
            @QueryParam("forceDelete") Boolean forceDelete) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.deleteFileSystem(region, fileSystemId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/file-systems/{fileSystemId}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response getFileSystemPolicy(
            @Context HttpHeaders headers, @PathParam("fileSystemId") String fileSystemId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.getFileSystemPolicy(region, fileSystemId)).build());
    }

    @PUT
    @Path("/file-systems/{fileSystemId}/policy")
    public Response putFileSystemPolicy(
            @Context HttpHeaders headers,
            @PathParam("fileSystemId") String fileSystemId,
            String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(
                service.putFileSystemPolicy(region, fileSystemId, parse(body))).build());
    }

    @DELETE
    @Path("/file-systems/{fileSystemId}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response deleteFileSystemPolicy(
            @Context HttpHeaders headers, @PathParam("fileSystemId") String fileSystemId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.deleteFileSystemPolicy(region, fileSystemId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @PUT
    @Path("/access-points")
    public Response createAccessPoint(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.createAccessPoint(region, parse(body))).build());
    }

    @GET
    @Path("/access-points")
    @Consumes(MediaType.WILDCARD)
    public Response listAccessPoints(
            @Context HttpHeaders headers,
            @QueryParam("fileSystemId") String fileSystemId,
            @QueryParam("maxResults") Integer maxResults) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.listAccessPoints(region, fileSystemId, maxResults)).build());
    }

    @GET
    @Path("/access-points/{accessPointId}")
    @Consumes(MediaType.WILDCARD)
    public Response getAccessPoint(
            @Context HttpHeaders headers, @PathParam("accessPointId") String accessPointId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.getAccessPoint(region, accessPointId)).build());
    }

    @DELETE
    @Path("/access-points/{accessPointId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAccessPoint(
            @Context HttpHeaders headers, @PathParam("accessPointId") String accessPointId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.deleteAccessPoint(region, accessPointId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/resource-tags/{resourceId}")
    @Consumes(MediaType.WILDCARD)
    public Response listTagsForResource(
            @Context HttpHeaders headers, @PathParam("resourceId") String resourceId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.listTagsForResource(region, resourceId)).build());
    }

    @POST
    @Path("/resource-tags/{resourceId}")
    public Response tagResource(
            @Context HttpHeaders headers, @PathParam("resourceId") String resourceId, String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.tagResource(region, resourceId, parse(body))).build());
    }

    @DELETE
    @Path("/resource-tags/{resourceId}")
    @Consumes(MediaType.WILDCARD)
    public Response untagResource(
            @Context HttpHeaders headers,
            @PathParam("resourceId") String resourceId,
            @QueryParam("tagKeys") List<String> tagKeys) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.untagResource(region, resourceId, tagKeys)).build());
    }

    private Response handle(Supplier<Response> action) {
        try {
            return action.get();
        } catch (AwsException e) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("__type", e.jsonType());
            node.put("message", e.getMessage());
            if (e.getExtendedData() != null) {
                e.getExtendedData().forEach((k, v) -> node.set(k, objectMapper.valueToTree(v)));
            } else {
                node.put("errorCode", e.getErrorCode());
            }
            return Response.status(e.getHttpStatus())
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-Errortype", e.jsonType())
                    .entity(node)
                    .build();
        }
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
