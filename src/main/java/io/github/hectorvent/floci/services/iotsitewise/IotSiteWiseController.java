package io.github.hectorvent.floci.services.iotsitewise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iotsitewise.model.Asset;
import io.github.hectorvent.floci.services.iotsitewise.model.AssetModel;
import io.github.hectorvent.floci.services.iotsitewise.model.Gateway;
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

/**
 * AWS IoT SiteWise restJson1.
 *
 * <p>Literal {@code /asset-models}, {@code /assets} and {@code /20200301/gateways} paths
 * take JAX-RS precedence over S3's {@code /{bucket}} catch-all. Tag APIs share
 * {@code /tags} and are dispatched by {@code SharedTagsController}. Requests are signed
 * as {@code iotsitewise}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IotSiteWiseController {

    private final IotSiteWiseService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public IotSiteWiseController(
            IotSiteWiseService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/asset-models")
    public Response createAssetModel(@Context HttpHeaders headers, String body) {
        return handle(() -> {
            AssetModel model = service.createAssetModel(regionResolver.resolveRegion(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("assetModelId", model.getId());
            response.put("assetModelArn", model.getArn());
            response.set("assetModelStatus", status("ACTIVE"));
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/asset-models/{assetModelId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeAssetModel(
            @Context HttpHeaders headers,
            @PathParam("assetModelId") String assetModelId,
            @QueryParam("excludeProperties") Boolean excludeProperties) {
        return handle(() -> {
            AssetModel model = service.describeAssetModel(regionResolver.resolveRegion(headers), assetModelId);
            return Response.ok(toDescribe(model, Boolean.TRUE.equals(excludeProperties))).build();
        });
    }

    @PUT
    @Path("/asset-models/{assetModelId}")
    public Response updateAssetModel(
            @Context HttpHeaders headers, @PathParam("assetModelId") String assetModelId, String body) {
        return handle(() -> {
            AssetModel model = service.updateAssetModel(
                    regionResolver.resolveRegion(headers), assetModelId, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.set("assetModelStatus", status("ACTIVE"));
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/asset-models/{assetModelId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAssetModel(
            @Context HttpHeaders headers, @PathParam("assetModelId") String assetModelId) {
        return handle(() -> {
            service.deleteAssetModel(regionResolver.resolveRegion(headers), assetModelId);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("assetModelStatus", status("DELETING"));
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/asset-models")
    @Consumes(MediaType.WILDCARD)
    public Response listAssetModels(@Context HttpHeaders headers) {
        return handle(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode summaries = response.putArray("assetModelSummaries");
            for (AssetModel model : service.listAssetModels(regionResolver.resolveRegion(headers))) {
                summaries.add(toModelSummary(model));
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/assets")
    public Response createAsset(@Context HttpHeaders headers, String body) {
        return handle(() -> {
            Asset asset = service.createAsset(regionResolver.resolveRegion(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("assetId", asset.getId());
            response.put("assetArn", asset.getArn());
            response.set("assetStatus", status("ACTIVE"));
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/assets/{assetId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeAsset(
            @Context HttpHeaders headers,
            @PathParam("assetId") String assetId,
            @QueryParam("excludeProperties") Boolean excludeProperties) {
        return handle(() -> {
            Asset asset = service.describeAsset(regionResolver.resolveRegion(headers), assetId);
            return Response.ok(toDescribe(asset, Boolean.TRUE.equals(excludeProperties))).build();
        });
    }

    @PUT
    @Path("/assets/{assetId}")
    public Response updateAsset(
            @Context HttpHeaders headers, @PathParam("assetId") String assetId, String body) {
        return handle(() -> {
            service.updateAsset(regionResolver.resolveRegion(headers), assetId, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.set("assetStatus", status("ACTIVE"));
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/assets/{assetId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAsset(@Context HttpHeaders headers, @PathParam("assetId") String assetId) {
        return handle(() -> {
            service.deleteAsset(regionResolver.resolveRegion(headers), assetId);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("assetStatus", status("DELETING"));
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/assets")
    @Consumes(MediaType.WILDCARD)
    public Response listAssets(
            @Context HttpHeaders headers, @QueryParam("assetModelId") String assetModelId) {
        return handle(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode summaries = response.putArray("assetSummaries");
            for (Asset asset : service.listAssets(regionResolver.resolveRegion(headers), assetModelId)) {
                summaries.add(toAssetSummary(asset));
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/assets/{assetId}/properties")
    @Consumes(MediaType.WILDCARD)
    public Response listAssetProperties(
            @Context HttpHeaders headers, @PathParam("assetId") String assetId) {
        return handle(() -> Response.ok(service.listAssetProperties(regionResolver.resolveRegion(headers), assetId))
                .build());
    }

    @POST
    @Path("/properties")
    public Response batchPut(@Context HttpHeaders headers, String body) {
        return handle(() -> Response.ok(service.batchPut(regionResolver.resolveRegion(headers), parse(body))).build());
    }

    @GET
    @Path("/properties/latest")
    @Consumes(MediaType.WILDCARD)
    public Response getLatest(
            @Context HttpHeaders headers,
            @QueryParam("assetId") String assetId,
            @QueryParam("propertyId") String propertyId) {
        return handle(() -> Response.ok(service.getLatest(regionResolver.resolveRegion(headers), assetId, propertyId))
                .build());
    }

    @GET
    @Path("/properties/history")
    @Consumes(MediaType.WILDCARD)
    public Response getHistory(
            @Context HttpHeaders headers,
            @QueryParam("assetId") String assetId,
            @QueryParam("propertyId") String propertyId,
            @QueryParam("startDate") String startDate,
            @QueryParam("endDate") String endDate,
            @QueryParam("timeOrdering") String timeOrdering) {
        return handle(() -> Response.ok(service.getHistory(
                        regionResolver.resolveRegion(headers), assetId, propertyId, startDate, endDate, timeOrdering))
                .build());
    }

    @GET
    @Path("/properties/aggregates")
    @Consumes(MediaType.WILDCARD)
    public Response getAggregates(
            @Context HttpHeaders headers,
            @QueryParam("assetId") String assetId,
            @QueryParam("propertyId") String propertyId,
            @QueryParam("aggregateTypes") List<String> aggregateTypes,
            @QueryParam("startDate") String startDate,
            @QueryParam("endDate") String endDate) {
        return handle(() -> Response.ok(service.getAggregates(
                        regionResolver.resolveRegion(headers),
                        assetId,
                        propertyId,
                        aggregateTypes,
                        startDate,
                        endDate))
                .build());
    }

    @GET
    @Path("/properties/interpolated")
    @Consumes(MediaType.WILDCARD)
    public Response getInterpolated(
            @Context HttpHeaders headers,
            @QueryParam("assetId") String assetId,
            @QueryParam("propertyId") String propertyId,
            @QueryParam("startTimeInSeconds") String startTimeInSeconds,
            @QueryParam("endTimeInSeconds") String endTimeInSeconds,
            @QueryParam("quality") String quality) {
        return handle(() -> Response.ok(service.getInterpolated(
                        regionResolver.resolveRegion(headers),
                        assetId,
                        propertyId,
                        startTimeInSeconds,
                        endTimeInSeconds,
                        quality))
                .build());
    }

    @POST
    @Path("/queries/execution")
    public Response executeQuery(@Context HttpHeaders headers, String body) {
        return handle(() -> Response.ok(service.executeQuery(regionResolver.resolveRegion(headers), parse(body)))
                .build());
    }

    @POST
    @Path("/20200301/gateways")
    public Response createGateway(@Context HttpHeaders headers, String body) {
        return handle(() -> {
            Gateway gateway = service.createGateway(regionResolver.resolveRegion(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("gatewayId", gateway.getId());
            response.put("gatewayArn", gateway.getArn());
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/20200301/gateways/{gatewayId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeGateway(
            @Context HttpHeaders headers, @PathParam("gatewayId") String gatewayId) {
        return handle(() -> Response.ok(
                toDescribe(service.describeGateway(regionResolver.resolveRegion(headers), gatewayId)))
                .build());
    }

    @PUT
    @Path("/20200301/gateways/{gatewayId}")
    public Response updateGateway(
            @Context HttpHeaders headers, @PathParam("gatewayId") String gatewayId, String body) {
        return handle(() -> {
            service.updateGateway(regionResolver.resolveRegion(headers), gatewayId, parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @DELETE
    @Path("/20200301/gateways/{gatewayId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteGateway(
            @Context HttpHeaders headers, @PathParam("gatewayId") String gatewayId) {
        return handle(() -> {
            service.deleteGateway(regionResolver.resolveRegion(headers), gatewayId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/20200301/gateways")
    @Consumes(MediaType.WILDCARD)
    public Response listGateways(@Context HttpHeaders headers) {
        return handle(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode summaries = response.putArray("gatewaySummaries");
            for (Gateway gateway : service.listGateways(regionResolver.resolveRegion(headers))) {
                summaries.add(toGatewaySummary(gateway));
            }
            return Response.ok(response).build();
        });
    }

    private ObjectNode toDescribe(AssetModel model, boolean excludeProperties) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("assetModelId", model.getId());
        response.put("assetModelArn", model.getArn());
        response.put("assetModelName", model.getName());
        response.put("assetModelType", model.getType());
        response.put("assetModelDescription", model.getDescription() == null ? "" : model.getDescription());
        if (excludeProperties) {
            response.putArray("assetModelProperties");
            response.putArray("assetModelHierarchies");
        } else {
            response.set("assetModelProperties", arrayOrEmpty(model.getProperties()));
            response.set("assetModelHierarchies", arrayOrEmpty(model.getHierarchies()));
            if (model.getCompositeModels() != null && model.getCompositeModels().isArray()
                    && !model.getCompositeModels().isEmpty()) {
                response.set("assetModelCompositeModels", model.getCompositeModels());
            }
        }
        response.put("assetModelCreationDate", model.getCreationDate());
        response.put("assetModelLastUpdateDate", model.getLastUpdateDate());
        response.set("assetModelStatus", status("ACTIVE"));
        return response;
    }

    private ObjectNode toModelSummary(AssetModel model) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("id", model.getId());
        summary.put("arn", model.getArn());
        summary.put("name", model.getName());
        summary.put("assetModelType", model.getType());
        if (model.getDescription() != null) {
            summary.put("description", model.getDescription());
        }
        summary.put("creationDate", model.getCreationDate());
        summary.put("lastUpdateDate", model.getLastUpdateDate());
        summary.set("status", status("ACTIVE"));
        return summary;
    }

    private ObjectNode toDescribe(Asset asset, boolean excludeProperties) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("assetId", asset.getId());
        response.put("assetArn", asset.getArn());
        response.put("assetName", asset.getName());
        response.put("assetModelId", asset.getModelId());
        if (excludeProperties) {
            response.putArray("assetProperties");
            response.putArray("assetHierarchies");
        } else {
            response.set("assetProperties", arrayOrEmpty(asset.getProperties()));
            response.set("assetHierarchies", arrayOrEmpty(asset.getHierarchies()));
        }
        response.put("assetCreationDate", asset.getCreationDate());
        response.put("assetLastUpdateDate", asset.getLastUpdateDate());
        response.set("assetStatus", status("ACTIVE"));
        if (asset.getDescription() != null) {
            response.put("assetDescription", asset.getDescription());
        }
        return response;
    }

    private ObjectNode toAssetSummary(Asset asset) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("id", asset.getId());
        summary.put("arn", asset.getArn());
        summary.put("name", asset.getName());
        summary.put("assetModelId", asset.getModelId());
        summary.put("creationDate", asset.getCreationDate());
        summary.put("lastUpdateDate", asset.getLastUpdateDate());
        summary.set("status", status("ACTIVE"));
        summary.set("hierarchies", arrayOrEmpty(asset.getHierarchies()));
        if (asset.getDescription() != null) {
            summary.put("description", asset.getDescription());
        }
        return summary;
    }

    private ObjectNode toDescribe(Gateway gateway) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("gatewayId", gateway.getId());
        response.put("gatewayName", gateway.getName());
        response.put("gatewayArn", gateway.getArn());
        if (gateway.getPlatform() != null) {
            response.set("gatewayPlatform", gateway.getPlatform());
        }
        if (gateway.getVersion() != null) {
            response.put("gatewayVersion", gateway.getVersion());
        }
        response.putArray("gatewayCapabilitySummaries");
        response.put("creationDate", gateway.getCreationDate());
        response.put("lastUpdateDate", gateway.getLastUpdateDate());
        return response;
    }

    private ObjectNode toGatewaySummary(Gateway gateway) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("gatewayId", gateway.getId());
        summary.put("gatewayName", gateway.getName());
        if (gateway.getPlatform() != null) {
            summary.set("gatewayPlatform", gateway.getPlatform());
        }
        if (gateway.getVersion() != null) {
            summary.put("gatewayVersion", gateway.getVersion());
        }
        summary.put("creationDate", gateway.getCreationDate());
        summary.put("lastUpdateDate", gateway.getLastUpdateDate());
        return summary;
    }

    private ObjectNode status(String state) {
        ObjectNode status = objectMapper.createObjectNode();
        status.put("state", state);
        return status;
    }

    private ArrayNode arrayOrEmpty(JsonNode node) {
        if (node != null && node.isArray()) {
            return (ArrayNode) node;
        }
        return objectMapper.createArrayNode();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("InvalidRequestException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidRequestException", "Request body is not valid JSON.", 400);
        }
    }

    private Response handle(Handler handler) {
        try {
            return handler.handle();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private static Response error(AwsException exception) {
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(new AwsErrorResponse(exception.jsonType(), exception.getMessage()))
                .build();
    }

    @FunctionalInterface
    private interface Handler {
        Response handle();
    }
}
