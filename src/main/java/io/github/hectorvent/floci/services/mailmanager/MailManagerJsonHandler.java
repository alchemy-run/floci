package io.github.hectorvent.floci.services.mailmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * JSON 1.0 handler for SES Mail Manager. Dispatched from
 * {@code AwsJsonController} under the {@code MailManagerSvc.} target prefix.
 */
@ApplicationScoped
public class MailManagerJsonHandler {

    static final String TARGET_PREFIX = "MailManagerSvc.";

    private final MailManagerService service;
    private final ObjectMapper objectMapper;

    @Inject
    public MailManagerJsonHandler(MailManagerService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreateRuleSet" -> ok(service.createRuleSet(body, region));
                case "GetRuleSet" -> ok(service.getRuleSet(body, region));
                case "UpdateRuleSet" -> ok(service.updateRuleSet(body, region));
                case "DeleteRuleSet" -> ok(service.deleteRuleSet(body, region));
                case "ListRuleSets" -> ok(service.listRuleSets(body, region));
                case "CreateTrafficPolicy" -> ok(service.createTrafficPolicy(body, region));
                case "GetTrafficPolicy" -> ok(service.getTrafficPolicy(body, region));
                case "UpdateTrafficPolicy" -> ok(service.updateTrafficPolicy(body, region));
                case "DeleteTrafficPolicy" -> ok(service.deleteTrafficPolicy(body, region));
                case "ListTrafficPolicies" -> ok(service.listTrafficPolicies(body, region));
                case "CreateRelay" -> ok(service.createRelay(body, region));
                case "GetRelay" -> ok(service.getRelay(body, region));
                case "UpdateRelay" -> ok(service.updateRelay(body, region));
                case "DeleteRelay" -> ok(service.deleteRelay(body, region));
                case "ListRelays" -> ok(service.listRelays(body, region));
                case "CreateAddressList" -> ok(service.createAddressList(body, region));
                case "GetAddressList" -> ok(service.getAddressList(body, region));
                case "ListAddressLists" -> ok(service.listAddressLists(body, region));
                case "DeleteAddressList" -> ok(service.deleteAddressList(body, region));
                case "RegisterMemberToAddressList" -> ok(service.registerMember(body, region));
                case "DeregisterMemberFromAddressList" -> ok(service.deregisterMember(body, region));
                case "GetMemberOfAddressList" -> ok(service.getMember(body, region));
                case "ListMembersOfAddressList" -> ok(service.listMembers(body, region));
                case "ListAddressListImportJobs" -> ok(service.listAddressListImportJobs(body, region));
                case "CreateArchive" -> ok(service.createArchive(body, region));
                case "GetArchive" -> ok(service.getArchive(body, region));
                case "ListArchives" -> ok(service.listArchives(body, region));
                case "UpdateArchive" -> ok(service.updateArchive(body, region));
                case "DeleteArchive" -> ok(service.deleteArchive(body, region));
                case "StartArchiveSearch" -> ok(service.startArchiveSearch(body, region));
                case "GetArchiveSearch" -> ok(service.getArchiveSearch(body, region));
                case "GetArchiveSearchResults" -> ok(service.getArchiveSearchResults(body, region));
                case "ListArchiveSearches" -> ok(service.listArchiveSearches(body, region));
                case "ListArchiveExports" -> ok(service.listArchiveExports(body, region));
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
