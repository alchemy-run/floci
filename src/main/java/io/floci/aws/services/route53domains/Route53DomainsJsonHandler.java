package io.floci.aws.services.route53domains;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/** Amazon Route 53 Domains JSON 1.1 handler, under the {@code Route53Domains_v20140515.} target prefix. */
@ApplicationScoped
public class Route53DomainsJsonHandler {
    private final Route53DomainsService service;

    @Inject
    public Route53DomainsJsonHandler(Route53DomainsService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region) {
        try {
            Object body = switch (action) {
                case "CheckDomainAvailability" -> service.checkAvailability(request);
                case "CheckDomainTransferability" -> service.checkTransferability(request);
                case "GetDomainSuggestions" -> service.suggestions(request);
                case "ListPrices" -> service.listPrices(request);
                case "GetDomainPrices" -> service.domainPrices(request);
                case "RegisterDomain" -> service.register(request);
                case "TransferDomain" -> service.transferIn(request);
                case "GetDomainDetail" -> service.detail(request);
                case "ListDomains" -> service.listDomains(request);
                case "UpdateDomainNameservers" -> service.updateNameservers(request);
                case "UpdateDomainContact" -> service.updateContact(request);
                case "UpdateDomainContactPrivacy" -> service.updatePrivacy(request);
                case "EnableDomainAutoRenew" -> service.autoRenew(request, true);
                case "DisableDomainAutoRenew" -> service.autoRenew(request, false);
                case "EnableDomainTransferLock" -> service.transferLock(request, true);
                case "DisableDomainTransferLock" -> service.transferLock(request, false);
                case "RenewDomain" -> service.renew(request);
                case "DeleteDomain" -> service.delete(request);
                case "RetrieveDomainAuthCode" -> service.authCode(request);
                case "PushDomain" -> service.push(request);
                case "AssociateDelegationSignerToDomain" -> service.associateDelegationSigner(request);
                case "DisassociateDelegationSignerFromDomain" -> service.disassociateDelegationSigner(request);
                case "TransferDomainToAnotherAwsAccount" -> service.transferToAccount(request);
                case "CancelDomainTransferToAnotherAwsAccount" -> service.cancelTransferToAccount(request);
                case "AcceptDomainTransferFromAnotherAwsAccount" -> service.acceptTransfer(request);
                case "RejectDomainTransferFromAnotherAwsAccount" -> service.rejectTransfer(request);
                case "ListTagsForDomain" -> service.listTags(request);
                case "UpdateTagsForDomain" -> service.updateTags(request);
                case "DeleteTagsForDomain" -> service.deleteTags(request);
                case "GetContactReachabilityStatus" -> service.reachability(request);
                case "ResendContactReachabilityEmail" -> service.resendReachability(request);
                case "GetOperationDetail" -> service.operationDetail(request);
                case "ListOperations" -> service.listOperations(request);
                case "ResendOperationAuthorization" -> service.operationAuthorization(request);
                case "ViewBilling" -> service.viewBilling(request);
                default -> null;
            };
            if (body == null) {
                return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                        "Operation " + action + " is not supported.")).build();
            }
            return Response.ok(body).build();
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }
}
