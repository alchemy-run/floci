package io.github.hectorvent.floci.services.b2bi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.services.b2bi.model.B2biCapability;
import io.github.hectorvent.floci.services.b2bi.model.B2biPartnership;
import io.github.hectorvent.floci.services.b2bi.model.B2biProfile;
import io.github.hectorvent.floci.services.b2bi.model.B2biTransformer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 1.0 handler for AWS B2BI. Dispatched from {@code AwsJsonController}
 * under the {@code B2BI.} target prefix.
 */
@ApplicationScoped
public class B2biJsonHandler {

    private final B2biService service;
    private final ObjectMapper objectMapper;

    @Inject
    public B2biJsonHandler(B2biService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreateTransformer" -> createTransformer(body, region);
                case "GetTransformer" -> getTransformer(body);
                case "UpdateTransformer" -> updateTransformer(body);
                case "DeleteTransformer" -> deleteTransformer(body);
                case "ListTransformers" -> listTransformers();
                case "CreateCapability" -> createCapability(body, region);
                case "GetCapability" -> getCapability(body);
                case "UpdateCapability" -> updateCapability(body);
                case "DeleteCapability" -> deleteCapability(body);
                case "ListCapabilities" -> listCapabilities();
                case "CreateProfile" -> createProfile(body, region);
                case "GetProfile" -> getProfile(body);
                case "UpdateProfile" -> updateProfile(body);
                case "DeleteProfile" -> deleteProfile(body);
                case "ListProfiles" -> listProfiles();
                case "CreatePartnership" -> createPartnership(body, region);
                case "GetPartnership" -> getPartnership(body);
                case "UpdatePartnership" -> updatePartnership(body);
                case "DeletePartnership" -> deletePartnership(body);
                case "ListPartnerships" -> listPartnerships(body);
                case "TagResource" -> tagResource(body);
                case "UntagResource" -> untagResource(body);
                case "ListTagsForResource" -> listTagsForResource(body);
                case "TestMapping" -> Response.ok(service.testMapping(body)).build();
                case "TestParsing" -> Response.ok(service.testParsing(body)).build();
                case "TestConversion" -> Response.ok(service.testConversion(body)).build();
                case "CreateStarterMappingTemplate" -> Response.ok(service.createStarterMappingTemplate(body)).build();
                case "GenerateMapping" -> Response.ok(service.generateMapping(body)).build();
                case "StartTransformerJob" -> Response.ok(service.startTransformerJob(body, region)).build();
                case "GetTransformerJob" -> Response.ok(service.getTransformerJob(body)).build();
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse("B2BI." + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private Response createTransformer(JsonNode req, String region) {
        B2biTransformer transformer = service.createTransformer(
                region, B2biService.textOrNull(req, "name"), req);
        return Response.ok(transformerNode(transformer)).build();
    }

    private Response getTransformer(JsonNode req) {
        return Response.ok(transformerNode(service.getTransformer(
                B2biService.textOrNull(req, "transformerId")))).build();
    }

    private Response updateTransformer(JsonNode req) {
        return Response.ok(transformerNode(service.updateTransformer(
                B2biService.textOrNull(req, "transformerId"), req))).build();
    }

    private Response deleteTransformer(JsonNode req) {
        service.deleteTransformer(B2biService.textOrNull(req, "transformerId"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response listTransformers() {
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode items = resp.putArray("transformers");
        for (B2biTransformer transformer : service.listTransformers()) {
            items.add(transformerSummary(transformer));
        }
        return Response.ok(resp).build();
    }

    private Response createCapability(JsonNode req, String region) {
        B2biCapability capability = service.createCapability(
                region,
                B2biService.textOrNull(req, "name"),
                B2biService.textOrNull(req, "type"),
                req);
        return Response.ok(capabilityNode(capability)).build();
    }

    private Response getCapability(JsonNode req) {
        return Response.ok(capabilityNode(service.getCapability(
                B2biService.textOrNull(req, "capabilityId")))).build();
    }

    private Response updateCapability(JsonNode req) {
        return Response.ok(capabilityNode(service.updateCapability(
                B2biService.textOrNull(req, "capabilityId"), req))).build();
    }

    private Response deleteCapability(JsonNode req) {
        service.deleteCapability(B2biService.textOrNull(req, "capabilityId"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response listCapabilities() {
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode items = resp.putArray("capabilities");
        for (B2biCapability capability : service.listCapabilities()) {
            items.add(capabilitySummary(capability));
        }
        return Response.ok(resp).build();
    }

    private Response createProfile(JsonNode req, String region) {
        return Response.ok(profileNode(service.createProfile(region, req))).build();
    }

    private Response getProfile(JsonNode req) {
        return Response.ok(profileNode(service.getProfile(
                B2biService.textOrNull(req, "profileId")))).build();
    }

    private Response updateProfile(JsonNode req) {
        return Response.ok(profileNode(service.updateProfile(
                B2biService.textOrNull(req, "profileId"), req))).build();
    }

    private Response deleteProfile(JsonNode req) {
        service.deleteProfile(B2biService.textOrNull(req, "profileId"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response listProfiles() {
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode items = resp.putArray("profiles");
        for (B2biProfile profile : service.listProfiles()) {
            items.add(profileSummary(profile));
        }
        return Response.ok(resp).build();
    }

    private Response createPartnership(JsonNode req, String region) {
        return Response.ok(partnershipNode(service.createPartnership(region, req))).build();
    }

    private Response getPartnership(JsonNode req) {
        return Response.ok(partnershipNode(service.getPartnership(
                B2biService.textOrNull(req, "partnershipId")))).build();
    }

    private Response updatePartnership(JsonNode req) {
        return Response.ok(partnershipNode(service.updatePartnership(
                B2biService.textOrNull(req, "partnershipId"), req))).build();
    }

    private Response deletePartnership(JsonNode req) {
        service.deletePartnership(B2biService.textOrNull(req, "partnershipId"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response listPartnerships(JsonNode req) {
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode items = resp.putArray("partnerships");
        for (B2biPartnership partnership : service.listPartnerships(
                B2biService.textOrNull(req, "profileId"))) {
            items.add(partnershipSummary(partnership));
        }
        return Response.ok(resp).build();
    }

    private Response tagResource(JsonNode req) {
        service.tagResource(resourceArn(req), parseTagMap(req.path("Tags")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response untagResource(JsonNode req) {
        List<String> keys = new ArrayList<>();
        req.path("TagKeys").forEach(n -> keys.add(n.asText()));
        service.untagResource(resourceArn(req), keys);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response listTagsForResource(JsonNode req) {
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode tags = resp.putArray("Tags");
        service.listTags(resourceArn(req)).forEach((k, v) -> {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", k);
            tag.put("Value", v);
            tags.add(tag);
        });
        return Response.ok(resp).build();
    }

    private ObjectNode transformerNode(B2biTransformer transformer) {
        ObjectNode node = transformerSummary(transformer);
        node.put("transformerArn", transformer.getTransformerArn());
        return node;
    }

    private ObjectNode transformerSummary(B2biTransformer transformer) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("transformerId", transformer.getTransformerId());
        node.put("name", transformer.getName());
        node.put("status", transformer.getStatus());
        node.put("createdAt", transformer.getCreatedAt());
        putOptional(node, "modifiedAt", transformer.getModifiedAt());
        putOptional(node, "fileFormat", transformer.getFileFormat());
        putOptional(node, "mappingTemplate", transformer.getMappingTemplate());
        putOptional(node, "sampleDocument", transformer.getSampleDocument());
        setIfPresent(node, "ediType", transformer.getEdiType());
        setIfPresent(node, "inputConversion", transformer.getInputConversion());
        setIfPresent(node, "mapping", transformer.getMapping());
        setIfPresent(node, "outputConversion", transformer.getOutputConversion());
        setIfPresent(node, "sampleDocuments", transformer.getSampleDocuments());
        return node;
    }

    private ObjectNode capabilityNode(B2biCapability capability) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("capabilityId", capability.getCapabilityId());
        node.put("capabilityArn", capability.getCapabilityArn());
        node.put("name", capability.getName());
        node.put("type", capability.getType());
        setIfPresent(node, "configuration", capability.getConfiguration());
        setIfPresent(node, "instructionsDocuments", capability.getInstructionsDocuments());
        node.put("createdAt", capability.getCreatedAt());
        putOptional(node, "modifiedAt", capability.getModifiedAt());
        return node;
    }

    private ObjectNode capabilitySummary(B2biCapability capability) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("capabilityId", capability.getCapabilityId());
        node.put("name", capability.getName());
        node.put("type", capability.getType());
        node.put("createdAt", capability.getCreatedAt());
        putOptional(node, "modifiedAt", capability.getModifiedAt());
        return node;
    }

    private ObjectNode profileNode(B2biProfile profile) {
        ObjectNode node = profileSummary(profile);
        node.put("profileArn", profile.getProfileArn());
        putOptional(node, "phone", profile.getPhone());
        putOptional(node, "email", profile.getEmail());
        return node;
    }

    private ObjectNode profileSummary(B2biProfile profile) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("profileId", profile.getProfileId());
        node.put("name", profile.getName());
        node.put("businessName", profile.getBusinessName());
        putOptional(node, "logging", profile.getLogging());
        putOptional(node, "logGroupName", profile.getLogGroupName());
        node.put("createdAt", profile.getCreatedAt());
        putOptional(node, "modifiedAt", profile.getModifiedAt());
        return node;
    }

    private ObjectNode partnershipNode(B2biPartnership partnership) {
        ObjectNode node = partnershipSummary(partnership);
        node.put("partnershipArn", partnership.getPartnershipArn());
        putOptional(node, "email", partnership.getEmail());
        putOptional(node, "phone", partnership.getPhone());
        return node;
    }

    private ObjectNode partnershipSummary(B2biPartnership partnership) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("profileId", partnership.getProfileId());
        node.put("partnershipId", partnership.getPartnershipId());
        putOptional(node, "name", partnership.getName());
        ArrayNode capabilities = node.putArray("capabilities");
        if (partnership.getCapabilities() != null) {
            for (String capabilityId : partnership.getCapabilities()) {
                capabilities.add(capabilityId);
            }
        }
        setIfPresent(node, "capabilityOptions", partnership.getCapabilityOptions());
        putOptional(node, "tradingPartnerId", partnership.getTradingPartnerId());
        node.put("createdAt", partnership.getCreatedAt());
        putOptional(node, "modifiedAt", partnership.getModifiedAt());
        return node;
    }

    private void setIfPresent(ObjectNode node, String field, JsonNode value) {
        if (value != null && !value.isNull()) {
            node.set(field, value);
        }
    }

    private void putOptional(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static String resourceArn(JsonNode req) {
        String arn = B2biService.textOrNull(req, "ResourceARN");
        if (arn == null) {
            arn = B2biService.textOrNull(req, "resourceARN");
        }
        return arn;
    }

    private static Map<String, String> parseTagMap(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || !tagsNode.isArray()) {
            return tags;
        }
        for (JsonNode tag : tagsNode) {
            String key = B2biService.textOrNull(tag, "Key");
            if (key == null) {
                key = B2biService.textOrNull(tag, "key");
            }
            String value = B2biService.textOrNull(tag, "Value");
            if (value == null) {
                value = B2biService.textOrNull(tag, "value");
            }
            if (key != null) {
                tags.put(key, value != null ? value : "");
            }
        }
        return tags;
    }
}
