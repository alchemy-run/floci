package io.github.hectorvent.floci.services.lakeformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * AWS Lake Formation restJson1.
 *
 * <p>Literal {@code /GetDataLakePrincipal}, {@code /ListLFTags} and peer paths
 * take JAX-RS precedence over S3's {@code /{bucket}} catch-all. Requests are
 * signed as {@code lakeformation}.
 *
 * @see <a href="https://docs.aws.amazon.com/lake-formation/latest/APIReference/API_Operations.html">Lake Formation API</a>
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LakeFormationController {

    private final LakeFormationService service;
    private final ObjectMapper objectMapper;

    @Inject
    public LakeFormationController(LakeFormationService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/GetDataLakePrincipal")
    @Consumes(MediaType.WILDCARD)
    public Response getDataLakePrincipal(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(
                service.getDataLakePrincipal(headers.getHeaderString("Authorization"))).build());
    }

    @POST
    @Path("/CreateLFTag")
    public Response createLfTag(String body) {
        return handle(body, request -> Response.ok(service.createLfTag(request)).build());
    }

    @POST
    @Path("/GetLFTag")
    @Consumes(MediaType.WILDCARD)
    public Response getLfTag(String body) {
        return handle(body, request -> Response.ok(service.getLfTag(request)).build());
    }

    @POST
    @Path("/UpdateLFTag")
    public Response updateLfTag(String body) {
        return handle(body, request -> Response.ok(service.updateLfTag(request)).build());
    }

    @POST
    @Path("/DeleteLFTag")
    @Consumes(MediaType.WILDCARD)
    public Response deleteLfTag(String body) {
        return handle(body, request -> Response.ok(service.deleteLfTag(request)).build());
    }

    @POST
    @Path("/ListLFTags")
    @Consumes(MediaType.WILDCARD)
    public Response listLfTags(String body) {
        return handle(body, request -> Response.ok(service.listLfTags(request)).build());
    }

    @POST
    @Path("/ListPermissions")
    @Consumes(MediaType.WILDCARD)
    public Response listPermissions(String body) {
        return handle(body, request -> Response.ok(service.listPermissions(request)).build());
    }

    @POST
    @Path("/GrantPermissions")
    public Response grantPermissions(String body) {
        return handle(body, request -> Response.ok(service.grantPermissions(request)).build());
    }

    @POST
    @Path("/RevokePermissions")
    public Response revokePermissions(String body) {
        return handle(body, request -> Response.ok(service.revokePermissions(request)).build());
    }

    @POST
    @Path("/SearchDatabasesByLFTags")
    public Response searchDatabasesByLfTags(String body) {
        return handle(body, request -> Response.ok(service.searchDatabasesByLfTags(request)).build());
    }

    @POST
    @Path("/SearchTablesByLFTags")
    public Response searchTablesByLfTags(String body) {
        return handle(body, request -> Response.ok(service.searchTablesByLfTags(request)).build());
    }

    @POST
    @Path("/GetResourceLFTags")
    public Response getResourceLfTags(String body) {
        return handle(body, request -> Response.ok(service.getResourceLfTags(request)).build());
    }

    @POST
    @Path("/AddLFTagsToResource")
    public Response addLfTagsToResource(String body) {
        return handle(body, request -> Response.ok(service.addLfTagsToResource(request)).build());
    }

    @POST
    @Path("/RemoveLFTagsFromResource")
    public Response removeLfTagsFromResource(String body) {
        return handle(body, request -> Response.ok(service.removeLfTagsFromResource(request)).build());
    }

    @POST
    @Path("/GetEffectivePermissionsForPath")
    public Response getEffectivePermissionsForPath(String body) {
        return handle(body, request -> Response.ok(service.getEffectivePermissionsForPath(request)).build());
    }

    @POST
    @Path("/GetTemporaryGlueTableCredentials")
    public Response getTemporaryGlueTableCredentials(String body) {
        return handle(body, request -> Response.ok(service.getTemporaryGlueTableCredentials(request)).build());
    }

    @POST
    @Path("/GetTemporaryGluePartitionCredentials")
    public Response getTemporaryGluePartitionCredentials(String body) {
        return handle(body, request -> Response.ok(service.getTemporaryGluePartitionCredentials(request)).build());
    }

    @POST
    @Path("/GetTemporaryDataLocationCredentials")
    public Response getTemporaryDataLocationCredentials(String body) {
        return handle(body, request -> Response.ok(service.getTemporaryDataLocationCredentials(request)).build());
    }

    @POST
    @Path("/RegisterResource")
    public Response registerResource(String body) {
        return handle(body, request -> Response.ok(service.registerResource(request)).build());
    }

    @POST
    @Path("/CreateLFTagExpression")
    public Response createLfTagExpression(String body) {
        return handle(body, request -> Response.ok(service.createLfTagExpression(request)).build());
    }

    @POST
    @Path("/GetLFTagExpression")
    @Consumes(MediaType.WILDCARD)
    public Response getLfTagExpression(String body) {
        return handle(body, request -> Response.ok(service.getLfTagExpression(request)).build());
    }

    @POST
    @Path("/UpdateLFTagExpression")
    public Response updateLfTagExpression(String body) {
        return handle(body, request -> Response.ok(service.updateLfTagExpression(request)).build());
    }

    @POST
    @Path("/DeleteLFTagExpression")
    @Consumes(MediaType.WILDCARD)
    public Response deleteLfTagExpression(String body) {
        return handle(body, request -> Response.ok(service.deleteLfTagExpression(request)).build());
    }

    @POST
    @Path("/ListLFTagExpressions")
    @Consumes(MediaType.WILDCARD)
    public Response listLfTagExpressions(String body) {
        return handle(body, request -> Response.ok(service.listLfTagExpressions(request)).build());
    }

    @POST
    @Path("/CreateDataCellsFilter")
    public Response createDataCellsFilter(String body) {
        return handle(body, request -> Response.ok(service.createDataCellsFilter(request)).build());
    }

    @POST
    @Path("/GetDataCellsFilter")
    @Consumes(MediaType.WILDCARD)
    public Response getDataCellsFilter(String body) {
        return handle(body, request -> Response.ok(service.getDataCellsFilter(request)).build());
    }

    @POST
    @Path("/UpdateDataCellsFilter")
    public Response updateDataCellsFilter(String body) {
        return handle(body, request -> Response.ok(service.updateDataCellsFilter(request)).build());
    }

    @POST
    @Path("/DeleteDataCellsFilter")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDataCellsFilter(String body) {
        return handle(body, request -> Response.ok(service.deleteDataCellsFilter(request)).build());
    }

    @POST
    @Path("/ListDataCellsFilter")
    @Consumes(MediaType.WILDCARD)
    public Response listDataCellsFilter(String body) {
        return handle(body, request -> Response.ok(service.listDataCellsFilter(request)).build());
    }

    @POST
    @Path("/CreateLakeFormationOptIn")
    public Response createLakeFormationOptIn(String body) {
        return handle(body, request -> Response.ok(service.createLakeFormationOptIn(request)).build());
    }

    @POST
    @Path("/DeleteLakeFormationOptIn")
    public Response deleteLakeFormationOptIn(String body) {
        return handle(body, request -> Response.ok(service.deleteLakeFormationOptIn(request)).build());
    }

    @POST
    @Path("/ListLakeFormationOptIns")
    @Consumes(MediaType.WILDCARD)
    public Response listLakeFormationOptIns(String body) {
        return handle(body, request -> Response.ok(service.listLakeFormationOptIns(request)).build());
    }

    @POST
    @Path("/DeregisterResource")
    @Consumes(MediaType.WILDCARD)
    public Response deregisterResource(String body) {
        return handle(body, request -> Response.ok(service.deregisterResource(request)).build());
    }

    @POST
    @Path("/DescribeResource")
    @Consumes(MediaType.WILDCARD)
    public Response describeResource(String body) {
        return handle(body, request -> Response.ok(service.describeResource(request)).build());
    }

    @POST
    @Path("/UpdateResource")
    public Response updateResource(String body) {
        return handle(body, request -> Response.ok(service.updateResource(request)).build());
    }

    @POST
    @Path("/ListResources")
    @Consumes(MediaType.WILDCARD)
    public Response listResources(String body) {
        return handle(body, request -> Response.ok(service.listResources(request)).build());
    }

    @POST
    @Path("/GetDataLakeSettings")
    @Consumes(MediaType.WILDCARD)
    public Response getDataLakeSettings(String body) {
        return handle(body, request -> Response.ok(service.getDataLakeSettings(request)).build());
    }

    @POST
    @Path("/PutDataLakeSettings")
    public Response putDataLakeSettings(String body) {
        return handle(body, request -> Response.ok(service.putDataLakeSettings(request)).build());
    }

    private Response handle(String body, Handler handler) {
        try {
            return handler.handle(parse(body));
        } catch (AwsException e) {
            return error(e);
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || request.isNull() || request.isMissingNode()) {
                return objectMapper.createObjectNode();
            }
            if (!request.isObject()) {
                throw new AwsException("InvalidInputException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidInputException", "Request body is not valid JSON.", 400);
        }
    }

    private Response error(AwsException exception) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("__type", exception.jsonType());
        node.put("Message", exception.getMessage());
        node.put("message", exception.getMessage());
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(node)
                .build();
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(JsonNode request);
    }
}
