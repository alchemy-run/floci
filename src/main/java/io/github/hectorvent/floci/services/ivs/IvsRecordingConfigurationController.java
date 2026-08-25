package io.github.hectorvent.floci.services.ivs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ivs.model.RecordingConfiguration;
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
 * Amazon IVS restJson1 recording-configuration operations.
 *
 * <p>Literal {@code /CreateRecordingConfiguration} and peer paths take JAX-RS
 * precedence over S3's {@code /{bucket}} catch-all.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IvsRecordingConfigurationController {

    private final IvsRecordingConfigurationService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public IvsRecordingConfigurationController(
            IvsRecordingConfigurationService service,
            ObjectMapper objectMapper,
            RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/CreateRecordingConfiguration")
    public Response create(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            RecordingConfiguration config = service.create(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("recordingConfiguration", toRecording(config));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/GetRecordingConfiguration")
    @Consumes(MediaType.WILDCARD)
    public Response get(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            RecordingConfiguration config = service.get(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("recordingConfiguration", toRecording(config));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/DeleteRecordingConfiguration")
    @Consumes(MediaType.WILDCARD)
    public Response delete(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.delete(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/ListRecordingConfigurations")
    @Consumes(MediaType.WILDCARD)
    public Response list(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            IvsRecordingConfigurationService.Page page =
                    service.list(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode configs = response.putArray("recordingConfigurations");
            for (RecordingConfiguration config : page.items()) {
                configs.add(toSummary(config));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    private ObjectNode toRecording(RecordingConfiguration config) {
        ObjectNode node = toSummary(config);
        node.put("recordingReconnectWindowSeconds", config.getRecordingReconnectWindowSeconds());
        if (config.getThumbnailRecordingMode() != null) {
            ObjectNode thumb = node.putObject("thumbnailConfiguration");
            thumb.put("recordingMode", config.getThumbnailRecordingMode());
            if (config.getThumbnailTargetIntervalSeconds() != null) {
                thumb.put("targetIntervalSeconds", config.getThumbnailTargetIntervalSeconds());
            }
            putOptional(thumb, "resolution", config.getThumbnailResolution());
            if (config.getThumbnailStorage() != null && !config.getThumbnailStorage().isEmpty()) {
                ArrayNode storage = thumb.putArray("storage");
                config.getThumbnailStorage().forEach(storage::add);
            }
        }
        if (config.getRenditionSelection() != null || (config.getRenditions() != null
                && !config.getRenditions().isEmpty())) {
            ObjectNode rendition = node.putObject("renditionConfiguration");
            putOptional(rendition, "renditionSelection", config.getRenditionSelection());
            if (config.getRenditions() != null && !config.getRenditions().isEmpty()) {
                ArrayNode list = rendition.putArray("renditions");
                config.getRenditions().forEach(list::add);
            }
        }
        return node;
    }

    private ObjectNode toSummary(RecordingConfiguration config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", config.getArn());
        putOptional(node, "name", config.getName());
        ObjectNode dest = node.putObject("destinationConfiguration");
        dest.putObject("s3").put("bucketName", config.getBucketName());
        node.put("state", config.getState());
        putTags(node, config.getTags());
        return node;
    }

    private void putTags(ObjectNode parent, java.util.Map<String, String> tags) {
        ObjectNode tagsNode = parent.putObject("tags");
        if (tags != null) {
            tags.forEach(tagsNode::put);
        }
    }

    private static void putOptional(ObjectNode parent, String field, String value) {
        if (value != null) {
            parent.put(field, value);
        }
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

    private static Response error(AwsException exception) {
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(new AwsErrorResponse(exception.jsonType(), exception.getMessage()))
                .build();
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(JsonNode request);
    }
}
