package io.github.hectorvent.floci.services.ssmcontacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * JSON 1.1 handler for AWS Systems Manager Incident Manager contacts.
 * Dispatched from {@code AwsJson11Controller} under the {@code SSMContacts.} target prefix.
 */
@ApplicationScoped
public class SsmContactsJsonHandler {

    private final SsmContactsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public SsmContactsJsonHandler(SsmContactsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreateContact" -> ok(service.createContact(body, region));
                case "GetContact" -> ok(service.getContact(body, region));
                case "UpdateContact" -> ok(service.updateContact(body, region));
                case "DeleteContact" -> ok(service.deleteContact(body, region));
                case "ListContacts" -> ok(service.listContacts(body));
                case "CreateContactChannel" -> ok(service.createContactChannel(body, region));
                case "GetContactChannel" -> ok(service.getContactChannel(body));
                case "UpdateContactChannel" -> ok(service.updateContactChannel(body));
                case "DeleteContactChannel" -> ok(service.deleteContactChannel(body));
                case "ListContactChannels" -> ok(service.listContactChannels(body, region));
                case "CreateRotation" -> ok(service.createRotation(body, region));
                case "GetRotation" -> ok(service.getRotation(body));
                case "UpdateRotation" -> ok(service.updateRotation(body));
                case "DeleteRotation" -> ok(service.deleteRotation(body));
                case "ListRotations" -> ok(service.listRotations());
                case "ListTagsForResource" -> ok(service.listTagsForResource(body));
                case "TagResource" -> ok(service.tagResource(body));
                case "UntagResource" -> ok(service.untagResource(body));
                case "GetContactPolicy" -> ok(service.getContactPolicy(body, region));
                case "PutContactPolicy" -> ok(service.putContactPolicy(body, region));
                case "SendActivationCode" -> ok(service.sendActivationCode(body));
                case "ActivateContactChannel" -> ok(service.activateContactChannel(body));
                case "DeactivateContactChannel" -> ok(service.deactivateContactChannel(body));
                case "ListRotationShifts" -> ok(service.listRotationShifts(body));
                case "ListPreviewRotationShifts" -> ok(service.listPreviewRotationShifts(body));
                case "CreateRotationOverride" -> ok(service.createRotationOverride(body));
                case "GetRotationOverride" -> ok(service.getRotationOverride(body));
                case "DeleteRotationOverride" -> ok(service.deleteRotationOverride(body));
                case "ListRotationOverrides" -> ok(service.listRotationOverrides(body));
                case "DescribeEngagement" -> ok(service.describeEngagement(body));
                case "ListEngagements" -> ok(service.listEngagements());
                case "StartEngagement" -> ok(service.startEngagement(body, region));
                case "StopEngagement" -> ok(service.stopEngagement(body));
                case "DescribePage" -> ok(service.describePage(body));
                case "ListPagesByContact" -> ok(service.listPagesByContact(body, region));
                case "ListPagesByEngagement" -> ok(service.listPagesByEngagement(body));
                case "ListPageReceipts" -> ok(service.listPageReceipts(body));
                case "ListPageResolutions" -> ok(service.listPageResolutions(body));
                case "AcceptPage" -> ok(service.acceptPage(body));
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse("SSMContacts." + action);
            };
        } catch (AwsException e) {
            return error(e);
        }
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }

    private Response error(AwsException e) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("__type", e.jsonType());
        if (e.getMessage() != null) {
            node.put("message", e.getMessage());
        }
        if (e.getExtendedData() != null) {
            for (Map.Entry<String, Object> entry : e.getExtendedData().entrySet()) {
                if (entry.getValue() != null) {
                    node.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        }
        String fault = e.getHttpStatus() < 500 ? "Sender" : "Receiver";
        return Response.status(e.getHttpStatus())
                .header("x-amzn-query-error", e.getErrorCode() + ";" + fault)
                .entity(node)
                .build();
    }
}
