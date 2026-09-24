package io.github.hectorvent.floci.services.lakeformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lakeformation.model.AddLFTagsToResourceRequest;
import io.github.hectorvent.floci.services.lakeformation.model.CreateLFTagRequest;
import io.github.hectorvent.floci.services.lakeformation.model.DeleteLFTagRequest;
import io.github.hectorvent.floci.services.lakeformation.model.DeregisterResourceRequest;
import io.github.hectorvent.floci.services.lakeformation.model.DescribeResourceRequest;
import io.github.hectorvent.floci.services.lakeformation.model.GetDataLakeSettingsRequest;
import io.github.hectorvent.floci.services.lakeformation.model.GetLFTagRequest;
import io.github.hectorvent.floci.services.lakeformation.model.GrantPermissionsRequest;
import io.github.hectorvent.floci.services.lakeformation.model.ListLFTagsRequest;
import io.github.hectorvent.floci.services.lakeformation.model.ListPermissionsRequest;
import io.github.hectorvent.floci.services.lakeformation.model.ListResourcesRequest;
import io.github.hectorvent.floci.services.lakeformation.model.PutDataLakeSettingsRequest;
import io.github.hectorvent.floci.services.lakeformation.model.RegisterResourceRequest;
import io.github.hectorvent.floci.services.lakeformation.model.RemoveLFTagsFromResourceRequest;
import io.github.hectorvent.floci.services.lakeformation.model.RevokePermissionsRequest;
import io.github.hectorvent.floci.services.lakeformation.model.UpdateLFTagRequest;
import io.github.hectorvent.floci.services.lakeformation.model.UpdateResourceRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LakeFormationController {

    private final LakeFormationService service;
    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;
    private final LakeFormationCatalogService catalog;
    private final LakeFormationDataAccessService dataAccess;

    @Inject
    public LakeFormationController(LakeFormationService service, ObjectMapper mapper, RegionResolver regionResolver,
                                   LakeFormationCatalogService catalog, LakeFormationDataAccessService dataAccess) {
        this.service = service;
        this.mapper = mapper;
        this.regionResolver = regionResolver;
        this.catalog = catalog;
        this.dataAccess = dataAccess;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode request = mapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("SerializationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("SerializationException", "Request body is not valid JSON.", 400);
        }
    }

    private Response handleResponse(Object responseModel) {
        if (responseModel == null) {
            return Response.ok()
                    .header("x-amzn-RequestId", "floci-" + System.currentTimeMillis())
                    .build();
        }
        return Response.ok()
                .header("x-amzn-RequestId", "floci-" + System.currentTimeMillis())
                .entity(responseModel)
                .build();
    }

    @POST
    @Path("/PutDataLakeSettings")
    public Response putDataLakeSettings(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.putDataLakeSettings(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), PutDataLakeSettingsRequest.class)));
    }

    @POST
    @Path("/GetDataLakeSettings")
    public Response getDataLakeSettings(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.getDataLakeSettings(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), GetDataLakeSettingsRequest.class)));
    }

    @POST
    @Path("/RegisterResource")
    public Response registerResource(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.registerResource(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), RegisterResourceRequest.class)));
    }

    @POST
    @Path("/UpdateResource")
    public Response updateResource(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.updateResource(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), UpdateResourceRequest.class)));
    }

    @POST
    @Path("/DeregisterResource")
    public Response deregisterResource(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.deregisterResource(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), DeregisterResourceRequest.class)));
    }

    @POST
    @Path("/ListResources")
    public Response listResources(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.listResources(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), ListResourcesRequest.class)));
    }

    @POST
    @Path("/DescribeResource")
    public Response describeResource(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.describeResource(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), DescribeResourceRequest.class)));
    }

    @POST
    @Path("/GrantPermissions")
    public Response grantPermissions(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.grantPermissions(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), GrantPermissionsRequest.class)));
    }

    @POST
    @Path("/RevokePermissions")
    public Response revokePermissions(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.revokePermissions(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), RevokePermissionsRequest.class)));
    }

    @POST
    @Path("/ListPermissions")
    public Response listPermissions(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.listPermissions(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), ListPermissionsRequest.class)));
    }

    @POST
    @Path("/CreateLFTag")
    public Response createLFTag(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.createLFTag(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), CreateLFTagRequest.class)));
    }

    @POST
    @Path("/GetLFTag")
    public Response getLFTag(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.getLFTag(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), GetLFTagRequest.class)));
    }

    @POST
    @Path("/UpdateLFTag")
    public Response updateLFTag(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.updateLFTag(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), UpdateLFTagRequest.class)));
    }

    @POST
    @Path("/DeleteLFTag")
    public Response deleteLFTag(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.deleteLFTag(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), DeleteLFTagRequest.class)));
    }

    @POST
    @Path("/ListLFTags")
    public Response listLFTags(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.listLFTags(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), ListLFTagsRequest.class)));
    }

    @POST
    @Path("/AddLFTagsToResource")
    public Response addLFTagsToResource(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.addLFTagsToResource(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), AddLFTagsToResourceRequest.class)));
    }

    @POST
    @Path("/RemoveLFTagsFromResource")
    public Response removeLFTagsFromResource(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.removeLFTagsFromResource(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), RemoveLFTagsFromResourceRequest.class)));
    }

    @POST
    @Path("/GetDataLakePrincipal")
    public Response getDataLakePrincipal(@Context HttpHeaders headers, String body) {
        parse(body);
        return handleResponse(catalog.getDataLakePrincipal(headers.getHeaderString("Authorization")));
    }

    @POST
    @Path("/GetEffectivePermissionsForPath")
    public Response getEffectivePermissionsForPath(@Context HttpHeaders headers, String body) {
        return handleResponse(dataAccess.getEffectivePermissionsForPath(regionResolver.resolveRegion(headers), parse(body)));
    }

    @POST
    @Path("/GetTemporaryGlueTableCredentials")
    public Response getTemporaryGlueTableCredentials(@Context HttpHeaders headers, String body) {
        return handleResponse(dataAccess.getTemporaryGlueTableCredentials(regionResolver.resolveRegion(headers),
                parse(body), headers.getHeaderString("Authorization")));
    }

    @POST
    @Path("/GetTemporaryGluePartitionCredentials")
    public Response getTemporaryGluePartitionCredentials(@Context HttpHeaders headers, String body) {
        return handleResponse(dataAccess.getTemporaryGluePartitionCredentials(regionResolver.resolveRegion(headers),
                parse(body), headers.getHeaderString("Authorization")));
    }

    @POST
    @Path("/GetTemporaryDataLocationCredentials")
    public Response getTemporaryDataLocationCredentials(@Context HttpHeaders headers, String body) {
        return handleResponse(dataAccess.getTemporaryDataLocationCredentials(regionResolver.resolveRegion(headers),
                parse(body), headers.getHeaderString("Authorization")));
    }

    @POST
    @Path("/GetResourceLFTags")
    public Response getResourceLFTags(@Context HttpHeaders headers, String body) {
        return handleResponse(catalog.getResourceLFTags(regionResolver.resolveRegion(headers), parse(body)));
    }

    @POST
    @Path("/SearchDatabasesByLFTags")
    public Response searchDatabasesByLFTags(@Context HttpHeaders headers, String body) {
        return handleResponse(catalog.search(regionResolver.resolveRegion(headers), parse(body), false));
    }

    @POST
    @Path("/SearchTablesByLFTags")
    public Response searchTablesByLFTags(@Context HttpHeaders headers, String body) {
        return handleResponse(catalog.search(regionResolver.resolveRegion(headers), parse(body), true));
    }

    @POST
    @Path("/CreateLFTagExpression")
    public Response createLFTagExpression(@Context HttpHeaders headers, String body) {
        return handleResponse(catalog.expression(regionResolver.resolveRegion(headers), "CreateLFTagExpression", parse(body)));
    }

    @POST
    @Path("/GetLFTagExpression")
    public Response getLFTagExpression(@Context HttpHeaders headers, String body) {
        return handleResponse(catalog.expression(regionResolver.resolveRegion(headers), "GetLFTagExpression", parse(body)));
    }

    @POST
    @Path("/UpdateLFTagExpression")
    public Response updateLFTagExpression(@Context HttpHeaders headers, String body) {
        return handleResponse(catalog.expression(regionResolver.resolveRegion(headers), "UpdateLFTagExpression", parse(body)));
    }

    @POST
    @Path("/DeleteLFTagExpression")
    public Response deleteLFTagExpression(@Context HttpHeaders headers, String body) {
        return handleResponse(catalog.expression(regionResolver.resolveRegion(headers), "DeleteLFTagExpression", parse(body)));
    }

    @POST
    @Path("/ListLFTagExpressions")
    public Response listLFTagExpressions(@Context HttpHeaders headers, String body) {
        return handleResponse(catalog.expression(regionResolver.resolveRegion(headers), "ListLFTagExpressions", parse(body)));
    }

    @POST
    @Path("/CreateDataCellsFilter")
    public Response createDataCellsFilter(@Context HttpHeaders headers, String body) {
        return handleResponse(catalog.dataCellsFilter(regionResolver.resolveRegion(headers), "CreateDataCellsFilter", parse(body)));
    }

    @POST
    @Path("/GetDataCellsFilter")
    public Response getDataCellsFilter(@Context HttpHeaders headers, String body) {
        return handleResponse(catalog.dataCellsFilter(regionResolver.resolveRegion(headers), "GetDataCellsFilter", parse(body)));
    }

    @POST
    @Path("/UpdateDataCellsFilter")
    public Response updateDataCellsFilter(@Context HttpHeaders headers, String body) {
        return handleResponse(catalog.dataCellsFilter(regionResolver.resolveRegion(headers), "UpdateDataCellsFilter", parse(body)));
    }

    @POST
    @Path("/DeleteDataCellsFilter")
    public Response deleteDataCellsFilter(@Context HttpHeaders headers, String body) {
        return handleResponse(catalog.dataCellsFilter(regionResolver.resolveRegion(headers), "DeleteDataCellsFilter", parse(body)));
    }

    @POST
    @Path("/ListDataCellsFilter")
    public Response listDataCellsFilter(@Context HttpHeaders headers, String body) {
        return handleResponse(catalog.dataCellsFilter(regionResolver.resolveRegion(headers), "ListDataCellsFilter", parse(body)));
    }

    @POST
    @Path("/CreateLakeFormationOptIn")
    public Response createLakeFormationOptIn(@Context HttpHeaders headers, String body) {
        return handleResponse(catalog.optIn(regionResolver.resolveRegion(headers), "CreateLakeFormationOptIn", parse(body), headers.getHeaderString("Authorization")));
    }

    @POST
    @Path("/DeleteLakeFormationOptIn")
    public Response deleteLakeFormationOptIn(@Context HttpHeaders headers, String body) {
        return handleResponse(catalog.optIn(regionResolver.resolveRegion(headers), "DeleteLakeFormationOptIn", parse(body), headers.getHeaderString("Authorization")));
    }

    @POST
    @Path("/ListLakeFormationOptIns")
    public Response listLakeFormationOptIns(@Context HttpHeaders headers, String body) {
        return handleResponse(catalog.optIn(regionResolver.resolveRegion(headers), "ListLakeFormationOptIns", parse(body), headers.getHeaderString("Authorization")));
    }
}
