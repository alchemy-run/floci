package io.github.hectorvent.floci.services.efs;

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
 * Amazon EFS restJson1. Public AWS paths live under {@code /2015-02-01}.
 * {@link EfsRoutingFilter} prefixes them with {@code /aws-efs} so they do not
 * match S3's {@code /{bucket}/{key:.+}} catch-all.
 */
@Path(EfsRoutingFilter.INTERNAL_PREFIX + "/2015-02-01")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EfsController {

    private final EfsService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public EfsController(EfsService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/file-systems")
    public Response createFileSystem(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.createFileSystem(region, parse(body))).build());
    }

    @GET
    @Path("/file-systems")
    @Consumes(MediaType.WILDCARD)
    public Response describeFileSystems(
            @Context HttpHeaders headers,
            @QueryParam("FileSystemId") String fileSystemId,
            @QueryParam("CreationToken") String creationToken) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(
                service.describeFileSystems(region, fileSystemId, creationToken)).build());
    }

    @PUT
    @Path("/file-systems/{fileSystemId}")
    public Response updateFileSystem(
            @Context HttpHeaders headers,
            @PathParam("fileSystemId") String fileSystemId,
            String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(
                service.updateFileSystem(region, fileSystemId, parse(body))).build());
    }

    @DELETE
    @Path("/file-systems/{fileSystemId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteFileSystem(
            @Context HttpHeaders headers, @PathParam("fileSystemId") String fileSystemId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.deleteFileSystem(region, fileSystemId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/file-systems/{fileSystemId}/backup-policy")
    @Consumes(MediaType.WILDCARD)
    public Response describeBackupPolicy(
            @Context HttpHeaders headers, @PathParam("fileSystemId") String fileSystemId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.describeBackupPolicy(region, fileSystemId)).build());
    }

    @PUT
    @Path("/file-systems/{fileSystemId}/backup-policy")
    public Response putBackupPolicy(
            @Context HttpHeaders headers,
            @PathParam("fileSystemId") String fileSystemId,
            String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(
                service.putBackupPolicy(region, fileSystemId, parse(body))).build());
    }

    @GET
    @Path("/file-systems/{fileSystemId}/lifecycle-configuration")
    @Consumes(MediaType.WILDCARD)
    public Response describeLifecycleConfiguration(
            @Context HttpHeaders headers, @PathParam("fileSystemId") String fileSystemId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(
                service.describeLifecycleConfiguration(region, fileSystemId)).build());
    }

    @PUT
    @Path("/file-systems/{fileSystemId}/lifecycle-configuration")
    public Response putLifecycleConfiguration(
            @Context HttpHeaders headers,
            @PathParam("fileSystemId") String fileSystemId,
            String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(
                service.putLifecycleConfiguration(region, fileSystemId, parse(body))).build());
    }

    @GET
    @Path("/file-systems/{fileSystemId}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response describeFileSystemPolicy(
            @Context HttpHeaders headers, @PathParam("fileSystemId") String fileSystemId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(
                service.describeFileSystemPolicy(region, fileSystemId)).build());
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
    @Path("/file-systems/{fileSystemId}/protection")
    public Response updateFileSystemProtection(
            @Context HttpHeaders headers,
            @PathParam("fileSystemId") String fileSystemId,
            String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(
                service.updateFileSystemProtection(region, fileSystemId, parse(body))).build());
    }

    @GET
    @Path("/file-systems/replication-configurations")
    @Consumes(MediaType.WILDCARD)
    public Response describeReplicationConfigurations(
            @Context HttpHeaders headers,
            @QueryParam("FileSystemId") String fileSystemId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(
                service.describeReplicationConfigurations(region, fileSystemId)).build());
    }

    @POST
    @Path("/access-points")
    public Response createAccessPoint(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.createAccessPoint(region, parse(body))).build());
    }

    @GET
    @Path("/access-points")
    @Consumes(MediaType.WILDCARD)
    public Response describeAccessPoints(
            @Context HttpHeaders headers,
            @QueryParam("AccessPointId") String accessPointId,
            @QueryParam("FileSystemId") String fileSystemId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(
                service.describeAccessPoints(region, accessPointId, fileSystemId)).build());
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

    @POST
    @Path("/mount-targets")
    public Response createMountTarget(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.createMountTarget(region, parse(body))).build());
    }

    @GET
    @Path("/mount-targets")
    @Consumes(MediaType.WILDCARD)
    public Response describeMountTargets(
            @Context HttpHeaders headers,
            @QueryParam("FileSystemId") String fileSystemId,
            @QueryParam("MountTargetId") String mountTargetId,
            @QueryParam("AccessPointId") String accessPointId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(
                service.describeMountTargets(region, fileSystemId, mountTargetId, accessPointId)).build());
    }

    @DELETE
    @Path("/mount-targets/{mountTargetId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteMountTarget(
            @Context HttpHeaders headers, @PathParam("mountTargetId") String mountTargetId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.deleteMountTarget(region, mountTargetId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/mount-targets/{mountTargetId}/security-groups")
    @Consumes(MediaType.WILDCARD)
    public Response describeMountTargetSecurityGroups(
            @Context HttpHeaders headers, @PathParam("mountTargetId") String mountTargetId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(
                service.describeMountTargetSecurityGroups(region, mountTargetId)).build());
    }

    @PUT
    @Path("/mount-targets/{mountTargetId}/security-groups")
    public Response modifyMountTargetSecurityGroups(
            @Context HttpHeaders headers,
            @PathParam("mountTargetId") String mountTargetId,
            String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.modifyMountTargetSecurityGroups(region, mountTargetId, parse(body));
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
        return handle(() -> {
            service.tagResource(region, resourceId, parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @DELETE
    @Path("/resource-tags/{resourceId}")
    @Consumes(MediaType.WILDCARD)
    public Response untagResource(
            @Context HttpHeaders headers,
            @PathParam("resourceId") String resourceId,
            @QueryParam("tagKeys") List<String> tagKeys) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.untagResource(region, resourceId, tagKeys);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/tags/{fileSystemId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeTags(
            @Context HttpHeaders headers, @PathParam("fileSystemId") String fileSystemId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.describeTags(region, fileSystemId)).build());
    }

    @POST
    @Path("/create-tags/{fileSystemId}")
    public Response createTags(
            @Context HttpHeaders headers, @PathParam("fileSystemId") String fileSystemId, String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.createTags(region, fileSystemId, parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/delete-tags/{fileSystemId}")
    public Response deleteTags(
            @Context HttpHeaders headers, @PathParam("fileSystemId") String fileSystemId, String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.deleteTags(region, fileSystemId, parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
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
                node.put("ErrorCode", e.getErrorCode());
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
                throw new AwsException("BadRequest", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("BadRequest", "Request body is not valid JSON.", 400);
        }
    }
}
