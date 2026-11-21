package io.floci.aws.services.glacier;

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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The Amazon Glacier REST API, routed here by {@link GlacierRouteFilter}. Errors are written as Glacier writes them,
 * {@code {"code", "message", "type"}}.
 */
@Path(GlacierRouteFilter.PREFIX + "/{accountId}")
@Consumes(MediaType.WILDCARD)
public class GlacierController {

    private final GlacierService service;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public GlacierController(GlacierService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- vaults

    @GET
    @Path("/vaults")
    public Response listVaults(@PathParam("accountId") String account, @QueryParam("marker") String marker,
                               @QueryParam("limit") String limit) {
        return json(() -> service.listVaults(account, region(), marker, limit));
    }

    @PUT
    @Path("/vaults/{vault}")
    public Response createVault(@PathParam("accountId") String account, @PathParam("vault") String vault) {
        return reply(() -> service.createVault(account, region(), vault));
    }

    @GET
    @Path("/vaults/{vault}")
    public Response describeVault(@PathParam("accountId") String account, @PathParam("vault") String vault) {
        return json(() -> service.describeVault(account, region(), vault));
    }

    @DELETE
    @Path("/vaults/{vault}")
    public Response deleteVault(@PathParam("accountId") String account, @PathParam("vault") String vault) {
        return reply(() -> service.deleteVault(account, region(), vault));
    }

    // ---------------------------------------------------------------- tags

    @POST
    @Path("/vaults/{vault}/tags")
    public Response vaultTags(@PathParam("accountId") String account, @PathParam("vault") String vault,
                         @QueryParam("operation") String operation, String body) {
        String action = "add".equals(operation) ? "AddTagsToVault"
                : "remove".equals(operation) ? "RemoveTagsFromVault" : "";
        return reply(() -> switch (action) {
            case "AddTagsToVault" -> service.addTags(account, region(), vault, parse(body));
            case "RemoveTagsFromVault" -> service.removeTags(account, region(), vault, parse(body));
            default -> throw GlacierStore.invalid("Invalid operation: " + operation + ". Expected add or remove.");
        });
    }

    @GET
    @Path("/vaults/{vault}/tags")
    public Response listTagsForVault(@PathParam("accountId") String account, @PathParam("vault") String vault) {
        return json(() -> service.listTags(account, region(), vault));
    }

    // ---------------------------------------------------------------- access policy

    @PUT
    @Path("/vaults/{vault}/access-policy")
    public Response setVaultAccessPolicy(@PathParam("accountId") String account, @PathParam("vault") String vault,
                                    String body) {
        return reply(() -> service.setAccessPolicy(account, region(), vault, parse(body)));
    }

    @GET
    @Path("/vaults/{vault}/access-policy")
    public Response getVaultAccessPolicy(@PathParam("accountId") String account, @PathParam("vault") String vault) {
        return json(() -> service.getAccessPolicy(account, region(), vault));
    }

    @DELETE
    @Path("/vaults/{vault}/access-policy")
    public Response deleteVaultAccessPolicy(@PathParam("accountId") String account, @PathParam("vault") String vault) {
        return reply(() -> service.deleteAccessPolicy(account, region(), vault));
    }

    // ---------------------------------------------------------------- notifications

    @PUT
    @Path("/vaults/{vault}/notification-configuration")
    public Response setVaultNotifications(@PathParam("accountId") String account, @PathParam("vault") String vault,
                                     String body) {
        return reply(() -> service.setNotifications(account, region(), vault, parse(body)));
    }

    @GET
    @Path("/vaults/{vault}/notification-configuration")
    public Response getVaultNotifications(@PathParam("accountId") String account, @PathParam("vault") String vault) {
        return json(() -> service.getNotifications(account, region(), vault));
    }

    @DELETE
    @Path("/vaults/{vault}/notification-configuration")
    public Response deleteVaultNotifications(@PathParam("accountId") String account, @PathParam("vault") String vault) {
        return reply(() -> service.deleteNotifications(account, region(), vault));
    }

    // ---------------------------------------------------------------- vault lock

    @POST
    @Path("/vaults/{vault}/lock-policy")
    public Response initiateVaultLock(@PathParam("accountId") String account, @PathParam("vault") String vault,
                                 String body) {
        return reply(() -> service.initiateLock(account, region(), vault, parse(body)));
    }

    @GET
    @Path("/vaults/{vault}/lock-policy")
    public Response getVaultLock(@PathParam("accountId") String account, @PathParam("vault") String vault) {
        return json(() -> service.getLock(account, region(), vault));
    }

    @DELETE
    @Path("/vaults/{vault}/lock-policy")
    public Response abortVaultLock(@PathParam("accountId") String account, @PathParam("vault") String vault) {
        return reply(() -> service.abortLock(account, region(), vault));
    }

    @POST
    @Path("/vaults/{vault}/lock-policy/{lockId}")
    public Response completeVaultLock(@PathParam("accountId") String account, @PathParam("vault") String vault,
                                 @PathParam("lockId") String lockId) {
        return reply(() -> service.completeLock(account, region(), vault, lockId));
    }

    // ---------------------------------------------------------------- archives

    @POST
    @Path("/vaults/{vault}/archives")
    public Response uploadArchive(@PathParam("accountId") String account, @PathParam("vault") String vault,
                                  @HeaderParam("x-amz-archive-description") String description,
                                  @HeaderParam("x-amz-sha256-tree-hash") String checksum, byte[] body) {
        return reply(() -> service.uploadArchive(account, region(), vault, description, checksum,
                body == null ? new byte[0] : body));
    }

    @DELETE
    @Path("/vaults/{vault}/archives/{archiveId}")
    public Response deleteArchive(@PathParam("accountId") String account, @PathParam("vault") String vault,
                                  @PathParam("archiveId") String archiveId) {
        return reply(() -> service.deleteArchive(account, region(), vault, archiveId));
    }

    // ---------------------------------------------------------------- multipart uploads

    @POST
    @Path("/vaults/{vault}/multipart-uploads")
    public Response initiateMultipartUpload(@PathParam("accountId") String account, @PathParam("vault") String vault,
                                            @HeaderParam("x-amz-archive-description") String description,
                                            @HeaderParam("x-amz-part-size") String partSize) {
        return reply(() -> service.initiateMultipartUpload(account, region(), vault, description, partSize));
    }

    @GET
    @Path("/vaults/{vault}/multipart-uploads")
    public Response listMultipartUploads(@PathParam("accountId") String account, @PathParam("vault") String vault,
                                         @QueryParam("marker") String marker, @QueryParam("limit") String limit) {
        return json(() -> service.listMultipartUploads(account, region(), vault, marker, limit));
    }

    @PUT
    @Path("/vaults/{vault}/multipart-uploads/{uploadId}")
    public Response uploadMultipartPart(@PathParam("accountId") String account, @PathParam("vault") String vault,
                               @PathParam("uploadId") String uploadId, @HeaderParam("Content-Range") String range,
                               @HeaderParam("x-amz-sha256-tree-hash") String checksum, byte[] body) {
        return reply(() -> service.uploadPart(account, region(), vault, uploadId, range, checksum,
                body == null ? new byte[0] : body));
    }

    @POST
    @Path("/vaults/{vault}/multipart-uploads/{uploadId}")
    public Response completeMultipartUpload(@PathParam("accountId") String account, @PathParam("vault") String vault,
                                            @PathParam("uploadId") String uploadId,
                                            @HeaderParam("x-amz-archive-size") String archiveSize,
                                            @HeaderParam("x-amz-sha256-tree-hash") String checksum) {
        return reply(() -> service.completeMultipartUpload(account, region(), vault, uploadId, archiveSize,
                checksum));
    }

    @DELETE
    @Path("/vaults/{vault}/multipart-uploads/{uploadId}")
    public Response abortMultipartUpload(@PathParam("accountId") String account, @PathParam("vault") String vault,
                                         @PathParam("uploadId") String uploadId) {
        return reply(() -> service.abortMultipartUpload(account, region(), vault, uploadId));
    }

    @GET
    @Path("/vaults/{vault}/multipart-uploads/{uploadId}")
    public Response listParts(@PathParam("accountId") String account, @PathParam("vault") String vault,
                              @PathParam("uploadId") String uploadId, @QueryParam("marker") String marker,
                              @QueryParam("limit") String limit) {
        return json(() -> service.listParts(account, region(), vault, uploadId, marker, limit));
    }

    // ---------------------------------------------------------------- jobs

    @POST
    @Path("/vaults/{vault}/jobs")
    public Response initiateJob(@PathParam("accountId") String account, @PathParam("vault") String vault,
                                String body) {
        return reply(() -> service.initiateJob(account, region(), vault, parse(body)));
    }

    @GET
    @Path("/vaults/{vault}/jobs")
    public Response listJobs(@PathParam("accountId") String account, @PathParam("vault") String vault,
                             @QueryParam("limit") String limit, @QueryParam("marker") String marker,
                             @QueryParam("statuscode") String statusCode, @QueryParam("completed") String completed) {
        return json(() -> service.listJobs(account, region(), vault, limit, marker, statusCode, completed));
    }

    @GET
    @Path("/vaults/{vault}/jobs/{jobId}")
    public Response describeJob(@PathParam("accountId") String account, @PathParam("vault") String vault,
                                @PathParam("jobId") String jobId) {
        return json(() -> service.describeJob(account, region(), vault, jobId));
    }

    @GET
    @Path("/vaults/{vault}/jobs/{jobId}/output")
    public Response getJobOutput(@PathParam("accountId") String account, @PathParam("vault") String vault,
                                 @PathParam("jobId") String jobId, @HeaderParam("Range") String range) {
        return reply(() -> service.getJobOutput(account, region(), vault, jobId, range));
    }

    // ---------------------------------------------------------------- Region-wide settings

    @GET
    @Path("/policies/data-retrieval")
    public Response getDataRetrievalPolicy(@PathParam("accountId") String account) {
        return json(() -> service.getDataRetrievalPolicy(account, region()));
    }

    @PUT
    @Path("/policies/data-retrieval")
    public Response setDataRetrievalPolicy(@PathParam("accountId") String account, String body) {
        return reply(() -> service.setDataRetrievalPolicy(account, region(), parse(body)));
    }

    @GET
    @Path("/provisioned-capacity")
    public Response listProvisionedCapacity(@PathParam("accountId") String account) {
        return json(() -> service.listProvisionedCapacity(account, region()));
    }

    @POST
    @Path("/provisioned-capacity")
    public Response purchaseProvisionedCapacity(@PathParam("accountId") String account) {
        return reply(() -> service.purchaseProvisionedCapacity(account, region()));
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
                throw GlacierStore.invalid("The request body must be a JSON object.");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw GlacierStore.invalid("The request body is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private Response json(Supplier<ObjectNode> call) {
        return reply(() -> new GlacierService.Reply(200, Map.of(), call.get()));
    }

    private Response reply(Supplier<GlacierService.Reply> call) {
        GlacierService.Reply reply;
        try {
            reply = call.get();
        } catch (AwsException e) {
            ObjectNode error = mapper.createObjectNode();
            error.put("code", e.getErrorCode()).put("message", e.getMessage())
                    .put("type", e.getHttpStatus() >= 500 ? "Server" : "Client");
            return Response.status(e.getHttpStatus()).type(MediaType.APPLICATION_JSON)
                    .header("x-amzn-ErrorType", e.getErrorCode()).entity(error.toString()).build();
        }
        Response.ResponseBuilder builder = Response.status(reply.status());
        reply.headers().forEach(builder::header);
        if (reply.body() instanceof byte[] bytes) {
            if (!reply.headers().containsKey("Content-Type")) {
                builder.type(MediaType.APPLICATION_OCTET_STREAM);
            }
            builder.entity(bytes);
        } else if (reply.body() != null) {
            builder.type(MediaType.APPLICATION_JSON).entity(reply.body().toString());
        }
        return builder.build();
    }
}
