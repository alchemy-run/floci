package io.github.hectorvent.floci.services.glacier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.glacier.model.GlacierJob;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
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

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Amazon S3 Glacier restJson1. Public AWS paths live under {@code /{accountId}/...}.
 * {@link GlacierRoutingFilter} prefixes them with {@code /aws-glacier} so they do not
 * match S3's {@code /{bucket}/{key:.+}} catch-all.
 */
@Path(GlacierRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GlacierController {

    private final GlacierService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public GlacierController(GlacierService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @PUT
    @Path("/{accountId}/vaults/{vaultName}")
    @Consumes(MediaType.WILDCARD)
    public Response createVault(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            ObjectNode created = service.createVault(region, vaultName);
            return Response.status(201)
                    .header("Location", created.path("location").asText())
                    .build();
        });
    }

    @GET
    @Path("/{accountId}/vaults/{vaultName}")
    @Consumes(MediaType.WILDCARD)
    public Response describeVault(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.describeVault(region, vaultName)).build());
    }

    @DELETE
    @Path("/{accountId}/vaults/{vaultName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteVault(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.deleteVault(region, vaultName);
            return Response.noContent().build();
        });
    }

    @GET
    @Path("/{accountId}/vaults")
    @Consumes(MediaType.WILDCARD)
    public Response listVaults(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.listVaults(region)).build());
    }

    @GET
    @Path("/{accountId}/vaults/{vaultName}/jobs")
    @Consumes(MediaType.WILDCARD)
    public Response listJobs(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName,
            @QueryParam("statuscode") String statuscode,
            @QueryParam("completed") String completed) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(
                service.listJobs(region, vaultName, statuscode, completed)).build());
    }

    @POST
    @Path("/{accountId}/vaults/{vaultName}/jobs")
    public Response initiateJob(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName,
            String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            ObjectNode created = service.initiateJob(region, vaultName, parse(body));
            return Response.status(202)
                    .header("Location", created.path("location").asText())
                    .header("x-amz-job-id", created.path("jobId").asText())
                    .build();
        });
    }

    @GET
    @Path("/{accountId}/vaults/{vaultName}/jobs/{jobId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeJob(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName,
            @PathParam("jobId") String jobId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.describeJob(region, vaultName, jobId)).build());
    }

    @GET
    @Path("/{accountId}/vaults/{vaultName}/jobs/{jobId}/output")
    @Consumes(MediaType.WILDCARD)
    public Response getJobOutput(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName,
            @PathParam("jobId") String jobId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            GlacierJob job = service.getJob(region, vaultName, jobId);
            byte[] body = job.getJobOutput() == null
                    ? new byte[0]
                    : job.getJobOutput().getBytes(StandardCharsets.UTF_8);
            return Response.ok(body)
                    .type(MediaType.APPLICATION_JSON)
                    .header("x-amz-sha256-tree-hash", job.getChecksum() == null ? "" : job.getChecksum())
                    .build();
        });
    }

    @POST
    @Path("/{accountId}/vaults/{vaultName}/archives")
    @Consumes(MediaType.WILDCARD)
    public Response uploadArchive(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName,
            @HeaderParam("x-amz-archive-description") String description,
            @HeaderParam("x-amz-sha256-tree-hash") String checksum,
            byte[] body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            ObjectNode created = service.uploadArchive(region, vaultName, description, checksum, body);
            return Response.status(201)
                    .header("Location", created.path("location").asText())
                    .header("x-amz-sha256-tree-hash", created.path("checksum").asText())
                    .header("x-amz-archive-id", created.path("archiveId").asText())
                    .build();
        });
    }

    @DELETE
    @Path("/{accountId}/vaults/{vaultName}/archives/{archiveId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteArchive(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName,
            @PathParam("archiveId") String archiveId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.deleteArchive(region, vaultName, archiveId);
            return Response.noContent().build();
        });
    }

    @GET
    @Path("/{accountId}/vaults/{vaultName}/multipart-uploads")
    @Consumes(MediaType.WILDCARD)
    public Response listMultipartUploads(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.listMultipartUploads(region, vaultName)).build());
    }

    @POST
    @Path("/{accountId}/vaults/{vaultName}/multipart-uploads")
    @Consumes(MediaType.WILDCARD)
    public Response initiateMultipartUpload(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName,
            @HeaderParam("x-amz-archive-description") String description,
            @HeaderParam("x-amz-part-size") String partSize) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            ObjectNode created = service.initiateMultipartUpload(region, vaultName, description, partSize);
            return Response.status(201)
                    .header("Location", created.path("location").asText())
                    .header("x-amz-multipart-upload-id", created.path("uploadId").asText())
                    .build();
        });
    }

    @GET
    @Path("/{accountId}/vaults/{vaultName}/multipart-uploads/{uploadId}")
    @Consumes(MediaType.WILDCARD)
    public Response listParts(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName,
            @PathParam("uploadId") String uploadId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.listParts(region, vaultName, uploadId)).build());
    }

    @PUT
    @Path("/{accountId}/vaults/{vaultName}/multipart-uploads/{uploadId}")
    @Consumes(MediaType.WILDCARD)
    public Response uploadMultipartPart(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName,
            @PathParam("uploadId") String uploadId,
            @HeaderParam("Content-Range") String range,
            @HeaderParam("x-amz-sha256-tree-hash") String checksum,
            byte[] body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            ObjectNode uploaded = service.uploadMultipartPart(
                    region, vaultName, uploadId, range, checksum, body);
            return Response.ok()
                    .header("x-amz-sha256-tree-hash", uploaded.path("checksum").asText())
                    .build();
        });
    }

    @POST
    @Path("/{accountId}/vaults/{vaultName}/multipart-uploads/{uploadId}")
    @Consumes(MediaType.WILDCARD)
    public Response completeMultipartUpload(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName,
            @PathParam("uploadId") String uploadId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            ObjectNode created = service.completeMultipartUpload(region, vaultName, uploadId);
            return Response.status(201)
                    .header("Location", created.path("location").asText())
                    .header("x-amz-sha256-tree-hash", created.path("checksum").asText())
                    .header("x-amz-archive-id", created.path("archiveId").asText())
                    .build();
        });
    }

    @DELETE
    @Path("/{accountId}/vaults/{vaultName}/multipart-uploads/{uploadId}")
    @Consumes(MediaType.WILDCARD)
    public Response abortMultipartUpload(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName,
            @PathParam("uploadId") String uploadId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.abortMultipartUpload(region, vaultName, uploadId);
            return Response.noContent().build();
        });
    }

    @GET
    @Path("/{accountId}/vaults/{vaultName}/tags")
    @Consumes(MediaType.WILDCARD)
    public Response listTags(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.listTags(region, vaultName)).build());
    }

    @POST
    @Path("/{accountId}/vaults/{vaultName}/tags")
    public Response mutateTags(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName,
            @QueryParam("operation") String operation,
            String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            if ("remove".equalsIgnoreCase(operation)) {
                service.removeTags(region, vaultName, parse(body));
            } else {
                service.addTags(region, vaultName, parse(body));
            }
            return Response.noContent().build();
        });
    }

    @GET
    @Path("/{accountId}/vaults/{vaultName}/notification-configuration")
    @Consumes(MediaType.WILDCARD)
    public Response getVaultNotifications(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.getVaultNotifications(region, vaultName)).build());
    }

    @PUT
    @Path("/{accountId}/vaults/{vaultName}/notification-configuration")
    public Response setVaultNotifications(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName,
            String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.setVaultNotifications(region, vaultName, parse(body));
            return Response.noContent().build();
        });
    }

    @DELETE
    @Path("/{accountId}/vaults/{vaultName}/notification-configuration")
    @Consumes(MediaType.WILDCARD)
    public Response deleteVaultNotifications(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.deleteVaultNotifications(region, vaultName);
            return Response.noContent().build();
        });
    }

    @GET
    @Path("/{accountId}/vaults/{vaultName}/access-policy")
    @Consumes(MediaType.WILDCARD)
    public Response getVaultAccessPolicy(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.getVaultAccessPolicy(region, vaultName)).build());
    }

    @PUT
    @Path("/{accountId}/vaults/{vaultName}/access-policy")
    public Response setVaultAccessPolicy(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName,
            String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.setVaultAccessPolicy(region, vaultName, parse(body));
            return Response.noContent().build();
        });
    }

    @DELETE
    @Path("/{accountId}/vaults/{vaultName}/access-policy")
    @Consumes(MediaType.WILDCARD)
    public Response deleteVaultAccessPolicy(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.deleteVaultAccessPolicy(region, vaultName);
            return Response.noContent().build();
        });
    }

    @POST
    @Path("/{accountId}/vaults/{vaultName}/lock-policy")
    public Response initiateVaultLock(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName,
            String body) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            ObjectNode created = service.initiateVaultLock(region, vaultName, parse(body));
            return Response.status(201)
                    .header("x-amz-lock-id", created.path("lockId").asText())
                    .build();
        });
    }

    @GET
    @Path("/{accountId}/vaults/{vaultName}/lock-policy")
    @Consumes(MediaType.WILDCARD)
    public Response getVaultLock(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> Response.ok(service.getVaultLock(region, vaultName)).build());
    }

    @DELETE
    @Path("/{accountId}/vaults/{vaultName}/lock-policy")
    @Consumes(MediaType.WILDCARD)
    public Response abortVaultLock(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.abortVaultLock(region, vaultName);
            return Response.noContent().build();
        });
    }

    @POST
    @Path("/{accountId}/vaults/{vaultName}/lock-policy/{lockId}")
    @Consumes(MediaType.WILDCARD)
    public Response completeVaultLock(
            @Context HttpHeaders headers,
            @PathParam("vaultName") String vaultName,
            @PathParam("lockId") String lockId) {
        String region = regionResolver.resolveRegion(headers);
        return handle(() -> {
            service.completeVaultLock(region, vaultName, lockId);
            return Response.noContent().build();
        });
    }

    private Response handle(Supplier<Response> action) {
        try {
            return action.get();
        } catch (AwsException e) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("__type", e.jsonType());
            node.put("code", e.getErrorCode());
            node.put("type", e.getHttpStatus() >= 500 ? "Server" : "Client");
            node.put("message", e.getMessage());
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
                throw GlacierService.glacierError("InvalidParameterValueException",
                        "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw GlacierService.glacierError("InvalidParameterValueException",
                    "Request body is not valid JSON.", 400);
        }
    }
}
