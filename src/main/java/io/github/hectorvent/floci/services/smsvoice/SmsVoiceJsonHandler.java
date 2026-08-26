package io.github.hectorvent.floci.services.smsvoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * JSON 1.0 handler for Pinpoint SMS Voice V2 (End User Messaging SMS).
 * Dispatched from {@code AwsJsonController} under the
 * {@code PinpointSMSVoiceV2.} target prefix.
 */
@ApplicationScoped
public class SmsVoiceJsonHandler {

    static final String TARGET_PREFIX = "PinpointSMSVoiceV2.";

    private final SmsVoiceService service;
    private final ObjectMapper objectMapper;

    @Inject
    public SmsVoiceJsonHandler(SmsVoiceService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreateConfigurationSet" -> ok(service.createConfigurationSet(body, region));
                case "DeleteConfigurationSet" -> ok(service.deleteConfigurationSet(body, region));
                case "DescribeConfigurationSets" -> ok(service.describeConfigurationSets(body, region));
                case "CreateEventDestination" -> ok(service.createEventDestination(body, region));
                case "UpdateEventDestination" -> ok(service.updateEventDestination(body, region));
                case "DeleteEventDestination" -> ok(service.deleteEventDestination(body, region));
                case "SetDefaultMessageType" -> ok(service.setDefaultMessageType(body, region));
                case "DeleteDefaultMessageType" -> ok(service.deleteDefaultMessageType(body, region));
                case "CreateOptOutList" -> ok(service.createOptOutList(body, region));
                case "DescribeOptOutLists" -> ok(service.describeOptOutLists(body, region));
                case "DeleteOptOutList" -> ok(service.deleteOptOutList(body, region));
                case "PutOptedOutNumber" -> ok(service.putOptedOutNumber(body, region));
                case "DescribeOptedOutNumbers" -> ok(service.describeOptedOutNumbers(body, region));
                case "DeleteOptedOutNumber" -> ok(service.deleteOptedOutNumber(body, region));
                case "DescribeKeywords" -> ok(service.describeKeywords(body, region));
                case "CarrierLookup" -> ok(service.carrierLookup(body));
                case "PutMessageFeedback" -> ok(service.putMessageFeedback(body));
                case "RequestPhoneNumber" -> ok(service.requestPhoneNumber(body, region));
                case "DescribePhoneNumbers" -> ok(service.describePhoneNumbers(body, region));
                case "UpdatePhoneNumber" -> ok(service.updatePhoneNumber(body, region));
                case "ReleasePhoneNumber" -> ok(service.releasePhoneNumber(body, region));
                case "SendTextMessage" -> ok(service.sendTextMessage(body, region));
                case "SendVoiceMessage" -> ok(service.sendVoiceMessage(body, region));
                case "SendMediaMessage" -> ok(service.sendMediaMessage(body, region));
                case "PutKeyword" -> ok(service.putKeyword(body, region));
                case "DeleteKeyword" -> ok(service.deleteKeyword(body, region));
                case "TagResource" -> ok(service.tagResource(body, region));
                case "UntagResource" -> ok(service.untagResource(body, region));
                case "ListTagsForResource" -> ok(service.listTagsForResource(body, region));
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(TARGET_PREFIX + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
