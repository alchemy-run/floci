package io.floci.aws.services.mailmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Set;

/** JSON 1.0 dispatch for Amazon SES Mail Manager ({@code X-Amz-Target: MailManagerSvc.<Action>}). */
@ApplicationScoped
public class MailManagerJsonHandler {

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/mailmanager-request-shapes.json", "Mail Manager request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : Set.of("integer", "long", "float", "double")
                            .contains(type) ? "value" : "size";
                    return violation(path, min != null && size < min
                            ? "Member must have " + what + " greater than or equal to " + min
                            : "Member must have " + what + " less than or equal to " + max);
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return violation(path, "Member must satisfy regular expression pattern: " + pattern);
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return violation(path, "Member must satisfy enum value set: [" + String.join(", ", values)
                            + "]");
                }

                @Override
                public AwsException required(String path) {
                    return violation(path, "Member must not be null");
                }
            });

    private final MailManagerResources resources;
    private final MailManagerArchives archives;

    @Inject
    public MailManagerJsonHandler(MailManagerResources resources, MailManagerArchives archives) {
        this.resources = resources;
        this.archives = archives;
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return MailManagerStore.validation("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    public Response handle(String action, JsonNode request, String region) {
        SHAPES.check(action, request);
        ObjectNode result = switch (action) {
            case "CreateAddonInstance" -> resources.createAddonInstance(request, region);
            case "CreateAddonSubscription" -> resources.createAddonSubscription(request, region);
            case "CreateAddressList" -> resources.createAddressList(request, region);
            case "CreateAddressListImportJob" -> resources.createAddressListImportJob(request, region);
            case "CreateArchive" -> resources.createArchive(request, region);
            case "CreateIngressPoint" -> resources.createIngressPoint(request, region);
            case "CreateRelay" -> resources.createRelay(request, region);
            case "CreateRuleSet" -> resources.createRuleSet(request, region);
            case "CreateTrafficPolicy" -> resources.createTrafficPolicy(request, region);
            case "DeleteAddonInstance" -> resources.deleteAddonInstance(request, region);
            case "DeleteAddonSubscription" -> resources.deleteAddonSubscription(request, region);
            case "DeleteAddressList" -> resources.deleteAddressList(request, region);
            case "DeleteArchive" -> resources.deleteArchive(request, region);
            case "DeleteIngressPoint" -> resources.deleteIngressPoint(request, region);
            case "DeleteRelay" -> resources.deleteRelay(request, region);
            case "DeleteRuleSet" -> resources.deleteRuleSet(request, region);
            case "DeleteTrafficPolicy" -> resources.deleteTrafficPolicy(request, region);
            case "DeregisterMemberFromAddressList" -> resources.deregisterMemberFromAddressList(request, region);
            case "GetAddonInstance" -> resources.getAddonInstance(request, region);
            case "GetAddonSubscription" -> resources.getAddonSubscription(request, region);
            case "GetAddressList" -> resources.getAddressList(request, region);
            case "GetAddressListImportJob" -> resources.getAddressListImportJob(request, region);
            case "GetArchive" -> resources.getArchive(request, region);
            case "GetArchiveExport" -> archives.getArchiveExport(request, region);
            case "GetArchiveMessage" -> archives.getArchiveMessage(request, region);
            case "GetArchiveMessageContent" -> archives.getArchiveMessageContent(request, region);
            case "GetArchiveSearch" -> archives.getArchiveSearch(request, region);
            case "GetArchiveSearchResults" -> archives.getArchiveSearchResults(request, region);
            case "GetIngressPoint" -> resources.getIngressPoint(request, region);
            case "GetMemberOfAddressList" -> resources.getMemberOfAddressList(request, region);
            case "GetRelay" -> resources.getRelay(request, region);
            case "GetRuleSet" -> resources.getRuleSet(request, region);
            case "GetTrafficPolicy" -> resources.getTrafficPolicy(request, region);
            case "ListAddonInstances" -> resources.listAddonInstances(request, region);
            case "ListAddonSubscriptions" -> resources.listAddonSubscriptions(request, region);
            case "ListAddressListImportJobs" -> resources.listAddressListImportJobs(request, region);
            case "ListAddressLists" -> resources.listAddressLists(request, region);
            case "ListArchiveExports" -> archives.listArchiveExports(request, region);
            case "ListArchiveSearches" -> archives.listArchiveSearches(request, region);
            case "ListArchives" -> resources.listArchives(request, region);
            case "ListIngressPoints" -> resources.listIngressPoints(request, region);
            case "ListMembersOfAddressList" -> resources.listMembersOfAddressList(request, region);
            case "ListRelays" -> resources.listRelays(request, region);
            case "ListRuleSets" -> resources.listRuleSets(request, region);
            case "ListTagsForResource" -> resources.listTagsForResource(request, region);
            case "ListTrafficPolicies" -> resources.listTrafficPolicies(request, region);
            case "RegisterMemberToAddressList" -> resources.registerMemberToAddressList(request, region);
            case "StartAddressListImportJob" -> resources.startAddressListImportJob(request, region);
            case "StartArchiveExport" -> archives.startArchiveExport(request, region);
            case "StartArchiveSearch" -> archives.startArchiveSearch(request, region);
            case "StopAddressListImportJob" -> resources.stopAddressListImportJob(request, region);
            case "StopArchiveExport" -> archives.stopArchiveExport(request, region);
            case "StopArchiveSearch" -> archives.stopArchiveSearch(request, region);
            case "TagResource" -> resources.tagResource(request, region);
            case "UntagResource" -> resources.untagResource(request, region);
            case "UpdateArchive" -> resources.updateArchive(request, region);
            case "UpdateIngressPoint" -> resources.updateIngressPoint(request, region);
            case "UpdateRelay" -> resources.updateRelay(request, region);
            case "UpdateRuleSet" -> resources.updateRuleSet(request, region);
            case "UpdateTrafficPolicy" -> resources.updateTrafficPolicy(request, region);
            default -> null;
        };
        if (result == null) {
            return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                    "Unknown operation: MailManagerSvc." + action)).build();
        }
        return Response.ok(result).build();
    }
}
