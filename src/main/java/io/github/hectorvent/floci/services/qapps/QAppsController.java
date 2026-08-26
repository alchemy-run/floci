package io.github.hectorvent.floci.services.qapps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.qapps.model.QApp;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Amazon Q Apps (Smithy restJson1). {@link QAppsRoutingFilter} prefixes
 * qapps-signed paths so {@code /apps.list} does not collapse onto Amplify
 * {@code GET /apps}. Tag APIs share {@code /tags/{arn}}.
 */
@Path(QAppsRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QAppsController {

    private final QAppsService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public QAppsController(QAppsService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/apps.create")
    public Response createQApp(
            @Context HttpHeaders headers, @HeaderParam("instance-id") String instanceId, String body) {
        return run(() -> {
            QApp app = service.createQApp(region(headers), instanceId, parse(body));
            return Response.ok(summary(app)).build();
        });
    }

    @GET
    @Path("/apps.get")
    @Consumes(MediaType.WILDCARD)
    public Response getQApp(
            @Context HttpHeaders headers,
            @HeaderParam("instance-id") String instanceId,
            @QueryParam("appId") String appId) {
        return run(() -> {
            QApp app = service.getQApp(region(headers), instanceId, appId);
            ObjectNode response = summary(app);
            ObjectNode definition = objectMapper.createObjectNode();
            definition.put("appDefinitionVersion", String.valueOf(app.getAppVersion()));
            if (app.getCards() != null) {
                definition.set("cards", app.getCards());
            } else {
                definition.putArray("cards");
            }
            definition.put("canEdit", true);
            response.set("appDefinition", definition);
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/apps.update")
    public Response updateQApp(
            @Context HttpHeaders headers, @HeaderParam("instance-id") String instanceId, String body) {
        return run(() -> Response.ok(summary(service.updateQApp(region(headers), instanceId, parse(body)))).build());
    }

    @POST
    @Path("/apps.delete")
    public Response deleteQApp(
            @Context HttpHeaders headers, @HeaderParam("instance-id") String instanceId, String body) {
        return run(() -> {
            JsonNode request = parse(body);
            JsonNode appId = request.get("appId");
            service.deleteQApp(
                    region(headers),
                    instanceId,
                    appId != null && appId.isTextual() ? appId.textValue() : null);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/apps.list")
    @Consumes(MediaType.WILDCARD)
    public Response listQApps(
            @Context HttpHeaders headers,
            @HeaderParam("instance-id") String instanceId,
            @QueryParam("limit") Integer limit,
            @QueryParam("nextToken") String nextToken) {
        return run(() -> {
            QAppsService.Page page = service.listQApps(region(headers), instanceId, limit, nextToken);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode apps = response.putArray("apps");
            for (QApp app : page.apps()) {
                ObjectNode item = apps.addObject();
                item.put("appId", app.getAppId());
                item.put("appArn", app.getAppArn());
                item.put("title", app.getTitle());
                if (app.getDescription() != null) {
                    item.put("description", app.getDescription());
                }
                item.put("createdAt", app.getCreatedAt());
                item.put("canEdit", true);
                item.put("status", app.getStatus());
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/runtime.startQAppSession")
    public Response startQAppSession(@HeaderParam("instance-id") String instanceId, String body) {
        return run(() -> {
            service.requireKnownInstance(instanceId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/runtime.getQAppSession")
    @Consumes(MediaType.WILDCARD)
    public Response getQAppSession(
            @HeaderParam("instance-id") String instanceId, @QueryParam("sessionId") String sessionId) {
        return run(() -> {
            service.requireKnownInstance(instanceId);
            throw QAppsService.resourceNotFound(sessionId == null ? "" : sessionId);
        });
    }

    @GET
    @Path("/catalog.listCategories")
    @Consumes(MediaType.WILDCARD)
    public Response listCategories(@HeaderParam("instance-id") String instanceId) {
        return run(() -> {
            service.requireKnownInstance(instanceId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/catalog.createCategories")
    public Response batchCreateCategory(@HeaderParam("instance-id") String instanceId, String body) {
        return run(() -> {
            service.requireKnownInstance(instanceId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/catalog.createItem")
    public Response createLibraryItem(@HeaderParam("instance-id") String instanceId, String body) {
        return run(() -> {
            service.requireKnownInstance(instanceId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/apps.describeQAppPermissions")
    @Consumes(MediaType.WILDCARD)
    public Response describeQAppPermissions(
            @HeaderParam("instance-id") String instanceId, @QueryParam("appId") String appId) {
        return run(() -> {
            service.requireKnownInstance(instanceId);
            throw QAppsService.resourceNotFound(appId == null ? "" : appId);
        });
    }

    @POST
    @Path("/apps.predictQApp")
    public Response predictQApp(@HeaderParam("instance-id") String instanceId, String body) {
        return run(() -> {
            service.requireKnownInstance(instanceId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    private ObjectNode summary(QApp app) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("appId", app.getAppId());
        response.put("appArn", app.getAppArn());
        response.put("title", app.getTitle());
        if (app.getDescription() != null) {
            response.put("description", app.getDescription());
        }
        if (app.getInitialPrompt() != null) {
            response.put("initialPrompt", app.getInitialPrompt());
        }
        response.put("appVersion", app.getAppVersion());
        response.put("status", app.getStatus());
        response.put("createdAt", app.getCreatedAt());
        response.put("createdBy", app.getCreatedBy());
        response.put("updatedAt", app.getUpdatedAt());
        response.put("updatedBy", app.getUpdatedBy());
        return response;
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private Response run(Supplier<Response> action) {
        try {
            return action.get();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private Response error(AwsException exception) {
        Object entity = exception.getExtendedData() != null
                ? extended(exception)
                : new AwsErrorResponse(exception.jsonType(), exception.getMessage());
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(entity)
                .build();
    }

    private ObjectNode extended(AwsException exception) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("__type", exception.jsonType());
        node.put("message", exception.getMessage());
        for (Map.Entry<String, Object> entry : exception.getExtendedData().entrySet()) {
            node.set(entry.getKey(), objectMapper.valueToTree(entry.getValue()));
        }
        return node;
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
