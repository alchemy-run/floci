package io.floci.aws.services.pinpointsmsvoicev2;

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

/** JSON 1.0 dispatch for AWS End User Messaging SMS and Voice ({@code X-Amz-Target: PinpointSMSVoiceV2.<Action>}). */
@ApplicationScoped
public class SmsVoiceJsonHandler {

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/pinpointsmsvoicev2-request-shapes.json", "SMS and Voice request shapes",
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

    private final SmsVoiceService service;
    private final SmsVoiceMessaging messaging;
    private final SmsVoiceRegistrations registrations;

    @Inject
    public SmsVoiceJsonHandler(SmsVoiceService service, SmsVoiceMessaging messaging,
                               SmsVoiceRegistrations registrations) {
        this.service = service;
        this.messaging = messaging;
        this.registrations = registrations;
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return SmsVoiceStore.validation("INVALID_PARAMETER", "1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    public Response handle(String action, JsonNode request, String region) {
        SHAPES.check(action, request);
        ObjectNode result = switch (action) {
            case "AssociateOriginationIdentity" -> service.associateOriginationIdentity(request, region);
            case "AssociateProtectConfiguration" -> service.associateProtectConfiguration(request, region);
            case "CarrierLookup" -> service.carrierLookup(request, region);
            case "CreateConfigurationSet" -> service.createConfigurationSet(request, region);
            case "CreateEventDestination" -> service.createEventDestination(request, region);
            case "CreateNotifyConfiguration" -> service.createNotifyConfiguration(request, region);
            case "CreateOptOutList" -> service.createOptOutList(request, region);
            case "CreatePool" -> service.createPool(request, region);
            case "CreateProtectConfiguration" -> service.createProtectConfiguration(request, region);
            case "CreateRcsAgent" -> service.createRcsAgent(request, region);
            case "CreateRegistration" -> registrations.createRegistration(request, region);
            case "CreateRegistrationAssociation" -> registrations.createRegistrationAssociation(request, region);
            case "CreateRegistrationAttachment" -> registrations.createRegistrationAttachment(request, region);
            case "CreateRegistrationVersion" -> registrations.createRegistrationVersion(request, region);
            case "CreateVerifiedDestinationNumber" -> service.createVerifiedDestinationNumber(request, region);
            case "DeleteAccountDefaultProtectConfiguration" -> service.deleteAccountDefaultProtectConfiguration(request, region);
            case "DeleteConfigurationSet" -> service.deleteConfigurationSet(request, region);
            case "DeleteDefaultMessageType" -> service.deleteDefaultMessageType(request, region);
            case "DeleteDefaultSenderId" -> service.deleteDefaultSenderId(request, region);
            case "DeleteEventDestination" -> service.deleteEventDestination(request, region);
            case "DeleteKeyword" -> service.deleteKeyword(request, region);
            case "DeleteMediaMessageSpendLimitOverride" -> service.deleteMediaMessageSpendLimitOverride(request, region);
            case "DeleteNotifyConfiguration" -> service.deleteNotifyConfiguration(request, region);
            case "DeleteNotifyMessageSpendLimitOverride" -> service.deleteNotifyMessageSpendLimitOverride(request, region);
            case "DeleteOptOutList" -> service.deleteOptOutList(request, region);
            case "DeleteOptedOutNumber" -> service.deleteOptedOutNumber(request, region);
            case "DeletePool" -> service.deletePool(request, region);
            case "DeleteProtectConfiguration" -> service.deleteProtectConfiguration(request, region);
            case "DeleteProtectConfigurationRuleSetNumberOverride" -> service.deleteProtectConfigurationRuleSetNumberOverride(request, region);
            case "DeleteRcsAgent" -> service.deleteRcsAgent(request, region);
            case "DeleteRcsMessageSpendLimitOverride" -> service.deleteRcsMessageSpendLimitOverride(request, region);
            case "DeleteRegistration" -> registrations.deleteRegistration(request, region);
            case "DeleteRegistrationAttachment" -> registrations.deleteRegistrationAttachment(request, region);
            case "DeleteRegistrationFieldValue" -> registrations.deleteRegistrationFieldValue(request, region);
            case "DeleteResourcePolicy" -> service.deleteResourcePolicy(request, region);
            case "DeleteTextMessageSpendLimitOverride" -> service.deleteTextMessageSpendLimitOverride(request, region);
            case "DeleteVerifiedDestinationNumber" -> service.deleteVerifiedDestinationNumber(request, region);
            case "DeleteVoiceMessageSpendLimitOverride" -> service.deleteVoiceMessageSpendLimitOverride(request, region);
            case "DescribeAccountAttributes" -> service.describeAccountAttributes(request, region);
            case "DescribeAccountLimits" -> service.describeAccountLimits(request, region);
            case "DescribeConfigurationSets" -> service.describeConfigurationSets(request, region);
            case "DescribeKeywords" -> service.describeKeywords(request, region);
            case "DescribeNotifyConfigurations" -> service.describeNotifyConfigurations(request, region);
            case "DescribeNotifyTemplates" -> service.describeNotifyTemplates(request, region);
            case "DescribeOptOutLists" -> service.describeOptOutLists(request, region);
            case "DescribeOptedOutNumbers" -> service.describeOptedOutNumbers(request, region);
            case "DescribePhoneNumbers" -> service.describePhoneNumbers(request, region);
            case "DescribePools" -> service.describePools(request, region);
            case "DescribeProtectConfigurations" -> service.describeProtectConfigurations(request, region);
            case "DescribeRcsAgentCountryLaunchStatus" -> service.describeRcsAgentCountryLaunchStatus(request, region);
            case "DescribeRcsAgents" -> service.describeRcsAgents(request, region);
            case "DescribeRegistrationAttachments" -> registrations.describeRegistrationAttachments(request, region);
            case "DescribeRegistrationFieldDefinitions" -> registrations.describeRegistrationFieldDefinitions(request, region);
            case "DescribeRegistrationFieldValues" -> registrations.describeRegistrationFieldValues(request, region);
            case "DescribeRegistrationSectionDefinitions" -> registrations.describeRegistrationSectionDefinitions(request, region);
            case "DescribeRegistrationTypeDefinitions" -> registrations.describeRegistrationTypeDefinitions(request, region);
            case "DescribeRegistrationVersions" -> registrations.describeRegistrationVersions(request, region);
            case "DescribeRegistrations" -> registrations.describeRegistrations(request, region);
            case "DescribeSenderIds" -> service.describeSenderIds(request, region);
            case "DescribeSpendLimits" -> service.describeSpendLimits(request, region);
            case "DescribeVerifiedDestinationNumbers" -> service.describeVerifiedDestinationNumbers(request, region);
            case "DisassociateOriginationIdentity" -> service.disassociateOriginationIdentity(request, region);
            case "DisassociateProtectConfiguration" -> service.disassociateProtectConfiguration(request, region);
            case "DiscardRegistrationVersion" -> registrations.discardRegistrationVersion(request, region);
            case "GetProtectConfigurationCountryRuleSet" -> service.getProtectConfigurationCountryRuleSet(request, region);
            case "GetResourcePolicy" -> service.getResourcePolicy(request, region);
            case "ListAvailablePhoneNumbers" -> service.listAvailablePhoneNumbers(request, region);
            case "ListNotifyCountries" -> service.listNotifyCountries(request, region);
            case "ListPoolOriginationIdentities" -> service.listPoolOriginationIdentities(request, region);
            case "ListProtectConfigurationRuleSetNumberOverrides" -> service.listProtectConfigurationRuleSetNumberOverrides(request, region);
            case "ListRegistrationAssociations" -> registrations.listRegistrationAssociations(request, region);
            case "ListTagsForResource" -> service.listTagsForResource(request, region);
            case "PutKeyword" -> service.putKeyword(request, region);
            case "PutMessageFeedback" -> messaging.putMessageFeedback(request, region);
            case "PutOptedOutNumber" -> service.putOptedOutNumber(request, region);
            case "PutProtectConfigurationRuleSetNumberOverride" -> service.putProtectConfigurationRuleSetNumberOverride(request, region);
            case "PutRegistrationFieldValue" -> registrations.putRegistrationFieldValue(request, region);
            case "PutResourcePolicy" -> service.putResourcePolicy(request, region);
            case "ReleasePhoneNumber" -> service.releasePhoneNumber(request, region);
            case "ReleaseSenderId" -> service.releaseSenderId(request, region);
            case "RequestPhoneNumber" -> service.requestPhoneNumber(request, region);
            case "RequestSenderId" -> service.requestSenderId(request, region);
            case "SendDestinationNumberVerificationCode" -> messaging.sendDestinationNumberVerificationCode(request, region);
            case "SendMediaMessage" -> messaging.sendMediaMessage(request, region);
            case "SendNotifyTextMessage" -> messaging.sendNotifyTextMessage(request, region);
            case "SendNotifyVoiceMessage" -> messaging.sendNotifyVoiceMessage(request, region);
            case "SendRcsMessage" -> messaging.sendRcsMessage(request, region);
            case "SendTextMessage" -> messaging.sendTextMessage(request, region);
            case "SendVoiceMessage" -> messaging.sendVoiceMessage(request, region);
            case "SetAccountDefaultProtectConfiguration" -> service.setAccountDefaultProtectConfiguration(request, region);
            case "SetDefaultMessageFeedbackEnabled" -> service.setDefaultMessageFeedbackEnabled(request, region);
            case "SetDefaultMessageType" -> service.setDefaultMessageType(request, region);
            case "SetDefaultSenderId" -> service.setDefaultSenderId(request, region);
            case "SetMediaMessageSpendLimitOverride" -> service.setMediaMessageSpendLimitOverride(request, region);
            case "SetNotifyMessageSpendLimitOverride" -> service.setNotifyMessageSpendLimitOverride(request, region);
            case "SetRcsMessageSpendLimitOverride" -> service.setRcsMessageSpendLimitOverride(request, region);
            case "SetTextMessageSpendLimitOverride" -> service.setTextMessageSpendLimitOverride(request, region);
            case "SetVoiceMessageSpendLimitOverride" -> service.setVoiceMessageSpendLimitOverride(request, region);
            case "SubmitRegistrationVersion" -> registrations.submitRegistrationVersion(request, region);
            case "TagResource" -> service.tagResource(request, region);
            case "UntagResource" -> service.untagResource(request, region);
            case "UpdateEventDestination" -> service.updateEventDestination(request, region);
            case "UpdateNotifyConfiguration" -> service.updateNotifyConfiguration(request, region);
            case "UpdatePhoneNumber" -> service.updatePhoneNumber(request, region);
            case "UpdatePool" -> service.updatePool(request, region);
            case "UpdateProtectConfiguration" -> service.updateProtectConfiguration(request, region);
            case "UpdateProtectConfigurationCountryRuleSet" -> service.updateProtectConfigurationCountryRuleSet(request, region);
            case "UpdateRcsAgent" -> service.updateRcsAgent(request, region);
            case "UpdateSenderId" -> service.updateSenderId(request, region);
            case "VerifyDestinationNumber" -> service.verifyDestinationNumber(request, region);
            default -> null;
        };
        if (result == null) {
            return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                    "Unknown operation: PinpointSMSVoiceV2." + action)).build();
        }
        return Response.ok(result).build();
    }
}
