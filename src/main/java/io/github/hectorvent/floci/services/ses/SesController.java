package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.AccountSuppressionAttributes;
import io.github.hectorvent.floci.services.ses.model.AccountVdmAttributes;
import io.github.hectorvent.floci.services.ses.model.ArchivingOptions;
import io.github.hectorvent.floci.services.ses.model.DashboardOptions;
import io.github.hectorvent.floci.services.ses.model.GuardianOptions;
import io.github.hectorvent.floci.services.ses.model.MultiRegionEndpoint;
import io.github.hectorvent.floci.services.ses.model.Tenant;
import io.github.hectorvent.floci.services.ses.model.TenantResource;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntry;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntryResult;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.Contact;
import io.github.hectorvent.floci.services.ses.model.ContactList;
import io.github.hectorvent.floci.services.ses.model.CustomVerificationEmailTemplate;
import io.github.hectorvent.floci.services.ses.model.DedicatedIpPool;
import io.github.hectorvent.floci.services.ses.model.Topic;
import io.github.hectorvent.floci.services.ses.model.TopicPreference;
import io.github.hectorvent.floci.services.ses.model.DeliveryOptions;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.EventDestination;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.ListManagementOptions;
import io.github.hectorvent.floci.services.ses.model.MessageHeader;
import io.github.hectorvent.floci.services.ses.model.MessageTag;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import io.github.hectorvent.floci.services.ses.model.SuppressionOptions;
import io.github.hectorvent.floci.services.ses.model.Tag;
import io.github.hectorvent.floci.services.ses.model.TrackingOptions;
import io.github.hectorvent.floci.services.ses.model.VdmOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * REST JSON controller for the AWS SES V2 API.
 * Implements the AWS SES V2 wire protocol at /v2/email/* for the operations
 * exposed by this controller.
 * Reuses the shared {@link SesService} for business logic shared with other SES
 * protocol handlers.
 *
 * Follows the same pattern as {@code LambdaController}: AwsExceptions are thrown
 * directly and converted by the global {@code AwsExceptionMapper}.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesController {

    private static final Logger LOG = Logger.getLogger(SesController.class);

    private final SesService sesService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesController(SesService sesService, RegionResolver regionResolver,
                           ObjectMapper objectMapper) {
        this.sesService = sesService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── Identities ────────────────────────────

    @POST
    @Path("/identities")
    public Response createEmailIdentity(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            String emailIdentity = request.path("EmailIdentity").asText(null);
            if (emailIdentity == null || emailIdentity.isBlank()) {
                throw new AwsException("BadRequestException", "EmailIdentity is required.", 400);
            }

            if (sesService.getIdentityVerificationAttributes(emailIdentity, region) != null) {
                throw new AwsException("AlreadyExistsException",
                        "Email identity " + emailIdentity + " already exist.", 400);
            }

            Identity identity = emailIdentity.contains("@")
                    ? sesService.verifyEmailIdentity(emailIdentity, region)
                    : sesService.verifyDomainIdentity(emailIdentity, region);

            List<Tag> parsedTags = parseTagsArray(request.path("Tags"));
            if (parsedTags != null) {
                sesService.setIdentityTags(emailIdentity, region, parsedTags);
            }
            JsonNode dkimSigning = request.path("DkimSigningAttributes");
            if (!dkimSigning.isMissingNode() && !dkimSigning.isNull()) {
                String nextLength = dkimSigning.path("NextSigningKeyLength").asText(null);
                if (nextLength != null && !nextLength.isBlank()) {
                    sesService.putEmailIdentityDkimSigningAttributes(
                            emailIdentity, "AWS_SES", nextLength, region);
                    identity = sesService.getIdentityVerificationAttributes(emailIdentity, region);
                }
            }

            ObjectNode result = objectMapper.createObjectNode();
            result.put("IdentityType", toV2IdentityType(identity.getIdentityType()));
            result.put("VerifiedForSendingStatus",
                    "Success".equals(identity.getVerificationStatus()));
            result.set("DkimAttributes", buildDkimAttributes(identity));

            LOG.infov("SES V2 CreateEmailIdentity: {0}", emailIdentity);
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/identities")
    public Response listEmailIdentities(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<Identity> identities = sesService.listIdentities(null, region);

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode items = result.putArray("EmailIdentities");
        for (Identity id : identities) {
            // Only a not-yet-verified domain can still transition (via its DKIM records),
            // so refresh just those; refreshing every identity would scan Route53 per call.
            Identity current = id;
            if ("Domain".equals(id.getIdentityType()) && !"Success".equals(id.getVerificationStatus())) {
                Identity refreshed = sesService.getIdentityVerificationAttributes(id.getIdentity(), region);
                if (refreshed != null) {
                    current = refreshed;
                }
            }
            ObjectNode item = objectMapper.createObjectNode();
            item.put("IdentityType", toV2IdentityType(current.getIdentityType()));
            item.put("IdentityName", current.getIdentity());
            item.put("SendingEnabled", "Success".equals(current.getVerificationStatus()));
            item.put("VerificationStatus", toV2Status(current.getVerificationStatus()));
            items.add(item);
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/identities/{emailIdentity}")
    public Response getEmailIdentity(@Context HttpHeaders headers,
                                     @PathParam("emailIdentity") String emailIdentity) {
        String region = regionResolver.resolveRegion(headers);
        Identity identity = sesService.getIdentityVerificationAttributes(emailIdentity, region);
        if (identity == null) {
            throw new AwsException("NotFoundException",
                    "Identity " + emailIdentity + " does not exist.", 404);
        }
        return Response.ok(buildFullIdentityResponse(identity)).build();
    }

    @DELETE
    @Path("/identities/{emailIdentity}")
    public Response deleteEmailIdentity(@Context HttpHeaders headers,
                                        @PathParam("emailIdentity") String emailIdentity) {
        String region = regionResolver.resolveRegion(headers);
        if (sesService.getIdentityVerificationAttributes(emailIdentity, region) == null) {
            throw new AwsException("NotFoundException",
                    "Email identity " + emailIdentity + " does not exist.", 404);
        }
        sesService.deleteIdentity(emailIdentity, region);
        LOG.infov("SES V2 DeleteEmailIdentity: {0}", emailIdentity);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ────────────────── Identity policies (sending authorization) ────────────────

    @POST
    @Path("/identities/{emailIdentity}/policies/{policyName}")
    public Response createEmailIdentityPolicy(@Context HttpHeaders headers,
                                              @PathParam("emailIdentity") String emailIdentity,
                                              @PathParam("policyName") String policyName, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            String policy = readPolicyBody(body);
            sesService.createEmailIdentityPolicy(emailIdentity, policyName, policy, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @PUT
    @Path("/identities/{emailIdentity}/policies/{policyName}")
    public Response updateEmailIdentityPolicy(@Context HttpHeaders headers,
                                              @PathParam("emailIdentity") String emailIdentity,
                                              @PathParam("policyName") String policyName, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            String policy = readPolicyBody(body);
            sesService.updateEmailIdentityPolicy(emailIdentity, policyName, policy, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/identities/{emailIdentity}/policies")
    public Response getEmailIdentityPolicies(@Context HttpHeaders headers,
                                             @PathParam("emailIdentity") String emailIdentity) {
        String region = regionResolver.resolveRegion(headers);
        Map<String, String> policies = sesService.getEmailIdentityPolicies(emailIdentity, region);
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode policiesNode = result.putObject("Policies");
        policies.forEach(policiesNode::put);
        return Response.ok(result).build();
    }

    @DELETE
    @Path("/identities/{emailIdentity}/policies/{policyName}")
    public Response deleteEmailIdentityPolicy(@Context HttpHeaders headers,
                                              @PathParam("emailIdentity") String emailIdentity,
                                              @PathParam("policyName") String policyName) {
        String region = regionResolver.resolveRegion(headers);
        sesService.deleteEmailIdentityPolicy(emailIdentity, policyName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private String readPolicyBody(String body) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (body == null || body.isBlank()) {
            throw new AwsException("BadRequestException", "Request body is required.", 400);
        }
        JsonNode request = objectMapper.readTree(body);
        requireJsonObject(request);
        JsonNode policyNode = request.path("Policy");
        if (policyNode.isMissingNode() || policyNode.isNull()) {
            // Verified against AWS: a missing/null required member is a Smithy validation error.
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'policy' failed to satisfy constraint: "
                            + "Member must not be null", 400);
        }
        if (!policyNode.isTextual()) {
            // Policy is a String; AWS rejects a non-string with an empty-bodied 400. Don't coerce
            // (asText would turn 123 into "123"); surface a serialization error instead.
            throw new AwsException("SerializationException", null, 400);
        }
        return policyNode.textValue();
    }

    // ──────────────────────── Identity DKIM ─────────────────────────

    @PUT
    @Path("/identities/{emailIdentity}/dkim")
    public Response putEmailIdentityDkimAttributes(@Context HttpHeaders headers,
                                                    @PathParam("emailIdentity") String emailIdentity,
                                                    String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            JsonNode signingEnabledNode = request.get("SigningEnabled");
            if (signingEnabledNode == null || !signingEnabledNode.isBoolean()) {
                throw new AwsException("BadRequestException",
                        "SigningEnabled must be present and must be a boolean", 400);
            }
            boolean signingEnabled = signingEnabledNode.booleanValue();
            sesService.setDkimAttributes(emailIdentity, signingEnabled, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @PUT
    @Path("/identities/{emailIdentity}/dkim/signing")
    public Response putEmailIdentityDkimSigningAttributes(@Context HttpHeaders headers,
                                                          @PathParam("emailIdentity") String emailIdentity,
                                                          String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body == null ? "{}" : body);
            requireJsonObject(request);
            String origin = request.path("SigningAttributesOrigin").asText(null);
            JsonNode signing = request.path("SigningAttributes");
            String nextLength = null;
            if (!signing.isMissingNode() && !signing.isNull()) {
                nextLength = signing.path("NextSigningKeyLength").asText(null);
            }
            sesService.putEmailIdentityDkimSigningAttributes(emailIdentity, origin, nextLength, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    // ────────────────── Identity MAIL FROM ──────────────────────────

    @PUT
    @Path("/identities/{emailIdentity}/mail-from")
    public Response putEmailIdentityMailFromAttributes(@Context HttpHeaders headers,
                                                        @PathParam("emailIdentity") String emailIdentity,
                                                        String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            JsonNode mailFromDomainNode = request.path("MailFromDomain");
            if (mailFromDomainNode.isMissingNode()) {
                throw new AwsException("BadRequestException",
                        "MailFromDomain is required (use an empty string to clear the existing setting).", 400);
            }
            if (!mailFromDomainNode.isNull() && !mailFromDomainNode.isTextual()) {
                throw new AwsException("BadRequestException",
                        "MailFromDomain must be a JSON string (or null).", 400);
            }
            String mailFromDomain = mailFromDomainNode.isNull()
                    ? ""
                    : mailFromDomainNode.asText("");
            JsonNode behaviorNode = request.path("BehaviorOnMxFailure");
            String behaviorV2 = null;
            if (!behaviorNode.isMissingNode() && !behaviorNode.isNull()) {
                if (!behaviorNode.isTextual()) {
                    throw new AwsException("BadRequestException",
                            "BehaviorOnMxFailure must be a JSON string.", 400);
                }
                behaviorV2 = behaviorNode.asText(null);
            }
            String behaviorV1 = v2BehaviorToV1(behaviorV2);
            sesService.setMailFromDomain(emailIdentity, mailFromDomain, behaviorV1, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    // ──────────────────── Identity Feedback ─────────────────────────

    @PUT
    @Path("/identities/{emailIdentity}/feedback")
    public Response putEmailIdentityFeedbackAttributes(@Context HttpHeaders headers,
                                                        @PathParam("emailIdentity") String emailIdentity,
                                                        String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            JsonNode emailForwardingEnabledNode = request.get("EmailForwardingEnabled");
            if (emailForwardingEnabledNode == null || !emailForwardingEnabledNode.isBoolean()) {
                throw new AwsException("BadRequestException",
                        "EmailForwardingEnabled must be present and must be a boolean", 400);
            }
            boolean emailForwardingEnabled = emailForwardingEnabledNode.booleanValue();
            sesService.setFeedbackForwardingEnabled(emailIdentity, emailForwardingEnabled, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    // ──────────────── Identity Configuration Set ────────────────────

    @PUT
    @Path("/identities/{emailIdentity}/configuration-set")
    public Response putEmailIdentityConfigurationSetAttributes(@Context HttpHeaders headers,
                                                               @PathParam("emailIdentity") String emailIdentity,
                                                               String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            String configurationSetName = null;
            if (body != null && !body.isEmpty()) {
                // Only a truly empty body is "no body". A non-empty body must be a JSON object; a
                // whitespace-only or otherwise unparseable body is a serialization error (verified
                // against real AWS: whitespace-only returns SerializationException and does not
                // clear). Within a valid object, an omitted or explicit-null ConfigurationSetName
                // clears the association (as does an empty body / {}).
                JsonNode request = objectMapper.readTree(body);
                if (request == null || !request.isObject()) {
                    throw new AwsException("SerializationException", null, 400);
                }
                JsonNode node = request.path("ConfigurationSetName");
                if (!node.isMissingNode() && !node.isNull()) {
                    if (!node.isTextual()) {
                        throw new AwsException("BadRequestException",
                                "ConfigurationSetName must be a JSON string.", 400);
                    }
                    configurationSetName = node.asText();
                }
            }
            sesService.setEmailIdentityConfigurationSet(emailIdentity, configurationSetName, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("SerializationException", null, 400);
        }
    }

    // ──────────────────────────── Send Email ────────────────────────────

    @POST
    @Path("/outbound-emails")
    public Response sendEmail(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (!sesService.isAccountSendingEnabled(region)) {
                throw new AwsException("SendingPausedException",
                        "Account sending is disabled.", 400);
            }

            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);

            // FromEmailAddress is optional per the AWS v2 contract. Each content type below
            // enforces the sender requirement the way AWS does: Raw can take its From from the
            // MIME message, while Simple and Templated require FromEmailAddress.
            String fromEmailAddress = request.path("FromEmailAddress").asText(null);

            JsonNode destination = requireObjectOrAbsent(request, "Destination");
            List<String> toAddresses = jsonArrayToList(destination.path("ToAddresses"));
            List<String> ccAddresses = jsonArrayToList(destination.path("CcAddresses"));
            List<String> bccAddresses = jsonArrayToList(destination.path("BccAddresses"));
            List<String> replyToAddresses = jsonArrayToList(request.path("ReplyToAddresses"));
            List<String> allDestinations = mergeLists(toAddresses, ccAddresses, bccAddresses);
            String configurationSetName = request.path("ConfigurationSetName").asText(null);
            List<MessageTag> emailTags = parseEmailTagsArray(request.path("EmailTags"), "EmailTags");
            ListManagementOptions listManagement =
                    parseListManagementOptions(request.path("ListManagementOptions"));

            JsonNode content = request.path("Content");
            String messageId;

            if (content.has("Raw")) {
                String rawData = content.path("Raw").path("Data").asText(null);
                if (rawData == null || rawData.isBlank()) {
                    throw new AwsException("BadRequestException",
                            "Content.Raw.Data is required.", 400);
                }
                if (allDestinations.isEmpty()) {
                    throw new AwsException("BadRequestException",
                            "At least one destination address is required.", 400);
                }
                messageId = sesService.sendRawEmail(fromEmailAddress, allDestinations, rawData,
                        configurationSetName, emailTags, listManagement, region);
            } else if (content.has("Simple")) {
                if (fromEmailAddress == null || fromEmailAddress.isBlank()) {
                    // AWS returns BadRequestException with a null message body here.
                    throw new AwsException("BadRequestException", null, 400);
                }
                JsonNode simple = content.path("Simple");
                String subject = simple.path("Subject").path("Data").asText("");
                String bodyText = simple.path("Body").path("Text").path("Data").asText(null);
                String bodyHtml = simple.path("Body").path("Html").path("Data").asText(null);
                List<MessageHeader> additionalHeaders =
                        parseHeadersArray(simple.path("Headers"), "content.simple.headers");
                messageId = sesService.sendEmail(fromEmailAddress, toAddresses, ccAddresses,
                        bccAddresses, replyToAddresses, subject, bodyText, bodyHtml,
                        configurationSetName, emailTags, additionalHeaders, listManagement, region);
            } else if (content.has("Template")) {
                if (fromEmailAddress == null || fromEmailAddress.isBlank()) {
                    throw new AwsException("BadRequestException", "Source cannot be empty", 400);
                }
                JsonNode template = content.path("Template");
                String templateName = template.path("TemplateName").asText(null);
                String templateArn = template.path("TemplateArn").asText(null);
                boolean hasName = templateName != null && !templateName.isBlank();
                boolean hasArn = templateArn != null && !templateArn.isBlank();
                boolean hasInline = template.has("TemplateContent");
                int selectorCount = (hasName ? 1 : 0) + (hasArn ? 1 : 0) + (hasInline ? 1 : 0);
                if (selectorCount > 1) {
                    throw new AwsException("BadRequestException",
                            "Content.Template must specify exactly one of TemplateName, TemplateArn, or TemplateContent.",
                            400);
                }
                if (selectorCount == 0) {
                    throw new AwsException("BadRequestException",
                            "Content.Template requires TemplateName, TemplateArn, or TemplateContent.", 400);
                }
                JsonNode templateData = parseTemplateData(template, "TemplateData");
                List<MessageHeader> additionalHeaders =
                        parseHeadersArray(template.path("Headers"), "content.template.headers");
                if (hasName || hasArn) {
                    String resolvedName = hasName
                            ? templateName
                            : SesService.templateNameFromArn(templateArn);
                    messageId = sesService.sendTemplatedEmail(fromEmailAddress, toAddresses, ccAddresses,
                            bccAddresses, replyToAddresses, resolvedName, templateData,
                            configurationSetName, emailTags, additionalHeaders, listManagement, region);
                } else {
                    JsonNode inline = template.path("TemplateContent");
                    String subject = inline.path("Subject").asText(null);
                    String text = inline.path("Text").asText(null);
                    String html = inline.path("Html").asText(null);
                    messageId = sesService.sendInlineTemplatedEmail(fromEmailAddress, toAddresses,
                            ccAddresses, bccAddresses, replyToAddresses,
                            subject, text, html, templateData,
                            configurationSetName, emailTags, additionalHeaders, listManagement, region);
                }
            } else {
                throw new AwsException("BadRequestException",
                        "Content must contain Raw, Simple, or Template.", 400);
            }

            ObjectNode result = objectMapper.createObjectNode();
            result.put("MessageId", messageId);

            LOG.infov("SES V2 SendEmail: from={0}, to={1}, messageId={2}",
                    fromEmailAddress, toAddresses, messageId);
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/outbound-bulk-emails")
    public Response sendBulkEmail(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (!sesService.isAccountSendingEnabled(region)) {
                throw new AwsException("SendingPausedException",
                        "Account sending is disabled.", 400);
            }

            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            String fromEmailAddress = request.path("FromEmailAddress").asText(null);
            if (fromEmailAddress == null || fromEmailAddress.isBlank()) {
                throw new AwsException("BadRequestException",
                        "FromEmailAddress is required.", 400);
            }
            List<String> replyToAddresses = jsonArrayToList(request.path("ReplyToAddresses"));
            String configurationSetName = request.path("ConfigurationSetName").asText(null);

            JsonNode template = request.path("DefaultContent").path("Template");
            if (template.isMissingNode() || template.isNull()) {
                throw new AwsException("BadRequestException",
                        "DefaultContent.Template is required.", 400);
            }
            String templateName = template.path("TemplateName").asText(null);
            String templateArn = template.path("TemplateArn").asText(null);
            boolean hasName = templateName != null && !templateName.isBlank();
            boolean hasArn = templateArn != null && !templateArn.isBlank();
            boolean hasInline = template.has("TemplateContent");
            int selectorCount = (hasName ? 1 : 0) + (hasArn ? 1 : 0) + (hasInline ? 1 : 0);
            if (selectorCount > 1) {
                throw new AwsException("BadRequestException",
                        "DefaultContent.Template must specify exactly one of TemplateName, TemplateArn, or TemplateContent.",
                        400);
            }
            if (selectorCount == 0) {
                throw new AwsException("BadRequestException",
                        "DefaultContent.Template requires TemplateName, TemplateArn, or TemplateContent.", 400);
            }

            String subject;
            String text;
            String html;
            if (hasInline) {
                JsonNode inline = template.path("TemplateContent");
                subject = inline.path("Subject").asText(null);
                text = inline.path("Text").asText(null);
                html = inline.path("Html").asText(null);
            } else {
                String resolvedName = hasName
                        ? templateName
                        : SesService.templateNameFromArn(templateArn);
                EmailTemplate stored = sesService.getTemplate(resolvedName, region);
                subject = stored.getSubject();
                text = stored.getTextPart();
                html = stored.getHtmlPart();
            }

            JsonNode defaultTemplateData = parseTemplateData(template, "TemplateData");
            List<MessageTag> defaultEmailTags = parseEmailTagsArray(request.path("DefaultEmailTags"), "DefaultEmailTags");
            List<MessageHeader> defaultHeaders =
                    parseHeadersArray(template.path("Headers"), "defaultContent.template.headers");

            JsonNode bulkEntries = request.path("BulkEmailEntries");
            if (!bulkEntries.isArray() || bulkEntries.isEmpty()) {
                throw new AwsException("BadRequestException",
                        "BulkEmailEntries must be a non-empty array.", 400);
            }

            List<BulkEmailEntry> entries = new ArrayList<>();
            int entryIndex = 1;
            for (JsonNode node : bulkEntries) {
                if (!node.isObject()) {
                    throw new AwsException("BadRequestException",
                            "BulkEmailEntries elements must be JSON objects.", 400);
                }
                JsonNode dest = requireObjectOrAbsent(node, "Destination");
                List<String> to = jsonArrayToList(dest.path("ToAddresses"));
                List<String> cc = jsonArrayToList(dest.path("CcAddresses"));
                List<String> bcc = jsonArrayToList(dest.path("BccAddresses"));
                JsonNode replacementContent = requireObjectOrAbsent(node, "ReplacementEmailContent");
                JsonNode replacementTemplate = requireObjectOrAbsent(replacementContent, "ReplacementTemplate");
                JsonNode replacementData = parseTemplateData(replacementTemplate, "ReplacementTemplateData");
                List<MessageTag> replacementTags = parseEmailTagsArray(node.path("ReplacementTags"), "ReplacementTags");
                List<MessageHeader> entryReplacementHeaders = parseHeadersArray(node.path("ReplacementHeaders"),
                        "bulkEmailEntries." + entryIndex + ".replacementHeaders");
                entries.add(new BulkEmailEntry(to, cc, bcc, replacementData, replacementTags, entryReplacementHeaders));
                entryIndex++;
            }

            List<BulkEmailEntryResult> results = sesService.sendBulkTemplatedEmail(fromEmailAddress,
                    replyToAddresses, subject, text, html,
                    defaultTemplateData, entries, configurationSetName,
                    defaultEmailTags, defaultHeaders, region);

            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode arr = response.putArray("BulkEmailEntryResults");
            for (BulkEmailEntryResult r : results) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("Status", r.getStatus().name());
                if (r.getMessageId() != null) {
                    item.put("MessageId", r.getMessageId());
                }
                if (r.getError() != null) {
                    item.put("Error", r.getError());
                }
                arr.add(item);
            }

            LOG.infov("SES V2 SendBulkEmail: from={0}, entries={1}",
                    fromEmailAddress, entries.size());
            return Response.ok(response).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    // ──────────────────────────── Templates ────────────────────────────

    // ──────────────── Custom verification email templates ────────────────

    @POST
    @Path("/custom-verification-email-templates")
    public Response createCustomVerificationEmailTemplate(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            CustomVerificationEmailTemplate t = parseCvet(objectMapper.readTree(body));
            sesService.createCustomVerificationEmailTemplate(t, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/custom-verification-email-templates")
    public Response listCustomVerificationEmailTemplates(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode items = result.putArray("CustomVerificationEmailTemplates");
        for (CustomVerificationEmailTemplate t : sesService.listCustomVerificationEmailTemplates(region)) {
            items.add(cvetJson(t, false));
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/custom-verification-email-templates/{templateName}")
    public Response getCustomVerificationEmailTemplate(@Context HttpHeaders headers,
                                                       @PathParam("templateName") String templateName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(
                    cvetJson(sesService.getCustomVerificationEmailTemplate(templateName, region), true)).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/custom-verification-email-templates/{templateName}")
    public Response updateCustomVerificationEmailTemplate(@Context HttpHeaders headers,
                                                          @PathParam("templateName") String templateName, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            CustomVerificationEmailTemplate t = parseCvet(objectMapper.readTree(body));
            t.setTemplateName(templateName);
            sesService.updateCustomVerificationEmailTemplate(t, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/custom-verification-email-templates/{templateName}")
    public Response deleteCustomVerificationEmailTemplate(@Context HttpHeaders headers,
                                                          @PathParam("templateName") String templateName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            sesService.deleteCustomVerificationEmailTemplate(templateName, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    private CustomVerificationEmailTemplate parseCvet(JsonNode request) {
        requireJsonObject(request);
        CustomVerificationEmailTemplate t = new CustomVerificationEmailTemplate();
        t.setTemplateName(request.path("TemplateName").asText(null));
        t.setFromEmailAddress(request.path("FromEmailAddress").asText(null));
        t.setTemplateSubject(request.path("TemplateSubject").asText(null));
        t.setTemplateContent(request.path("TemplateContent").asText(null));
        t.setSuccessRedirectionURL(request.path("SuccessRedirectionURL").asText(null));
        t.setFailureRedirectionURL(request.path("FailureRedirectionURL").asText(null));
        return t;
    }

    // List omits TemplateContent (matches AWS); Get includes it.
    private ObjectNode cvetJson(CustomVerificationEmailTemplate t, boolean includeContent) {
        ObjectNode o = objectMapper.createObjectNode();
        o.put("TemplateName", t.getTemplateName());
        o.put("FromEmailAddress", t.getFromEmailAddress());
        o.put("TemplateSubject", t.getTemplateSubject());
        if (includeContent) {
            o.put("TemplateContent", t.getTemplateContent());
        }
        o.put("SuccessRedirectionURL", t.getSuccessRedirectionURL());
        o.put("FailureRedirectionURL", t.getFailureRedirectionURL());
        return o;
    }

    @POST
    @Path("/templates")
    public Response createEmailTemplate(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            String templateName = request.path("TemplateName").asText(null);
            if (templateName == null || templateName.isBlank()) {
                throw new AwsException("BadRequestException", "TemplateName is required.", 400);
            }
            EmailTemplate template = parseTemplateContent(templateName, request.path("TemplateContent"));
            List<Tag> parsedTags = parseTagsArray(request.path("Tags"));
            if (parsedTags != null) {
                template.setTags(parsedTags);
            }
            sesService.createTemplate(template, region);
            LOG.infov("SES V2 CreateEmailTemplate: {0}", templateName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/templates")
    public Response listEmailTemplates(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<EmailTemplate> templates = sesService.listTemplates(region);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode items = result.putArray("TemplatesMetadata");
        for (EmailTemplate t : templates) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("TemplateName", t.getTemplateName());
            if (t.getCreatedTimestamp() != null) {
                item.put("CreatedTimestamp", t.getCreatedTimestamp().getEpochSecond());
            }
            items.add(item);
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/templates/{templateName}")
    public Response getEmailTemplate(@Context HttpHeaders headers,
                                      @PathParam("templateName") String templateName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            EmailTemplate template = sesService.getTemplate(templateName, region);
            return Response.ok(buildTemplateResponse(template)).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/templates/{templateName}")
    public Response updateEmailTemplate(@Context HttpHeaders headers,
                                         @PathParam("templateName") String templateName,
                                         String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            EmailTemplate template = parseTemplateContent(templateName, request.path("TemplateContent"));
            sesService.updateTemplate(template, region);
            LOG.infov("SES V2 UpdateEmailTemplate: {0}", templateName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/templates/{templateName}")
    public Response deleteEmailTemplate(@Context HttpHeaders headers,
                                         @PathParam("templateName") String templateName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            sesService.deleteTemplate(templateName, region);
            LOG.infov("SES V2 DeleteEmailTemplate: {0}", templateName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @POST
    @Path("/templates/{templateName}/render")
    public Response testRenderEmailTemplate(@Context HttpHeaders headers,
                                             @PathParam("templateName") String templateName,
                                             String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            JsonNode templateDataNode = request.path("TemplateData");
            if (!templateDataNode.isMissingNode() && !templateDataNode.isNull()
                    && !templateDataNode.isTextual()) {
                throw new AwsException("BadRequestException",
                        "TemplateData must be a JSON-encoded string.", 400);
            }
            String templateDataRaw = templateDataNode.asText("");
            String rendered = sesService.renderTestTemplate(templateName, templateDataRaw, region);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("RenderedTemplate", rendered);
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    // ──────────────────────── Configuration Sets ───────────────────────

    @POST
    @Path("/configuration-sets")
    public Response createConfigurationSet(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            String name = request.path("ConfigurationSetName").asText(null);
            if (name == null || name.isBlank()) {
                throw new AwsException("BadRequestException", "ConfigurationSetName is required.", 400);
            }
            ConfigurationSet cs = new ConfigurationSet(name);
            List<Tag> parsedTags = parseTagsArray(request.path("Tags"));
            if (parsedTags != null) {
                cs.setTags(parsedTags);
            }
            JsonNode suppressionNode = request.path("SuppressionOptions");
            if (!suppressionNode.isMissingNode() && !suppressionNode.isNull()) {
                if (!suppressionNode.isObject()) {
                    throw new AwsException("SerializationException", "Expected null", 400);
                }
                JsonNode reasonsNode = suppressionNode.path("SuppressedReasons");
                if (reasonsNode.isMissingNode() || reasonsNode.isNull()) {
                    throw new AwsException("InternalFailure",
                            "An internal failure has occurred.", 500);
                }
                SuppressionOptions options = new SuppressionOptions();
                options.setSuppressedReasons(parseSuppressedReasons(reasonsNode));
                cs.setSuppressionOptions(options);
            }
            JsonNode sendingNode = request.path("SendingOptions");
            if (!sendingNode.isMissingNode() && !sendingNode.isNull()) {
                if (!sendingNode.isObject()) {
                    throw new AwsException("SerializationException", "Expected null", 400);
                }
                cs.setSendingEnabled(parseSendingEnabled(sendingNode.path("SendingEnabled")));
            }
            JsonNode reputationNode = request.path("ReputationOptions");
            if (!reputationNode.isMissingNode() && !reputationNode.isNull()) {
                requireOptionObject(reputationNode);
                Boolean rme = parseReputationMetricsEnabled(reputationNode.path("ReputationMetricsEnabled"));
                if (rme != null) {
                    cs.setReputationMetricsEnabled(rme);
                }
            }
            JsonNode trackingNode = request.path("TrackingOptions");
            if (!trackingNode.isMissingNode() && !trackingNode.isNull()) {
                cs.setTrackingOptions(parseTrackingOptions(trackingNode));
            }
            JsonNode deliveryNode = request.path("DeliveryOptions");
            if (!deliveryNode.isMissingNode() && !deliveryNode.isNull()) {
                cs.setDeliveryOptions(parseDeliveryOptions(deliveryNode));
            }
            JsonNode archivingNode = request.path("ArchivingOptions");
            if (!archivingNode.isMissingNode() && !archivingNode.isNull()) {
                cs.setArchivingOptions(parseArchivingOptions(archivingNode));
            }
            JsonNode vdmNode = request.path("VdmOptions");
            if (!vdmNode.isMissingNode() && !vdmNode.isNull()) {
                cs.setVdmOptions(parseVdmOptions(vdmNode));
            }
            sesService.createConfigurationSet(cs, region);
            LOG.infov("SES V2 CreateConfigurationSet: {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/configuration-sets")
    public Response listConfigurationSets(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<ConfigurationSet> all = sesService.listConfigurationSets(region);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode arr = result.putArray("ConfigurationSets");
        for (ConfigurationSet cs : all) {
            arr.add(cs.getName());
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/configuration-sets/{configurationSetName}")
    public Response getConfigurationSet(@Context HttpHeaders headers,
                                         @PathParam("configurationSetName") String name) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ConfigurationSet cs = sesService.getConfigurationSet(name, region);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("ConfigurationSetName", cs.getName());
            ArrayNode tags = result.putArray("Tags");
            for (Tag t : cs.getTags()) {
                ObjectNode tagNode = objectMapper.createObjectNode();
                tagNode.put("Key", t.key());
                tagNode.put("Value", t.value());
                tags.add(tagNode);
            }
            if (cs.getSuppressionOptions() != null) {
                ObjectNode suppressionNode = result.putObject("SuppressionOptions");
                ArrayNode reasons = suppressionNode.putArray("SuppressedReasons");
                for (String r : cs.getSuppressionOptions().getSuppressedReasons()) {
                    reasons.add(r);
                }
            }
            ObjectNode sendingNode = result.putObject("SendingOptions");
            sendingNode.put("SendingEnabled", cs.isSendingEnabledEffective());
            // AWS always returns ReputationOptions (true by default), like SendingOptions.
            ObjectNode reputationNode = result.putObject("ReputationOptions");
            reputationNode.put("ReputationMetricsEnabled", cs.isReputationMetricsEnabledEffective());
            // The option models carry @JsonProperty/@JsonInclude(NON_NULL), so let
            // Jackson shape the response and omit unset members.
            if (cs.getTrackingOptions() != null) {
                result.set("TrackingOptions", objectMapper.valueToTree(cs.getTrackingOptions()));
            }
            if (cs.getDeliveryOptions() != null) {
                result.set("DeliveryOptions", objectMapper.valueToTree(cs.getDeliveryOptions()));
            }
            if (cs.getArchivingOptions() != null) {
                result.set("ArchivingOptions", objectMapper.valueToTree(cs.getArchivingOptions()));
            }
            if (cs.getVdmOptions() != null) {
                result.set("VdmOptions", objectMapper.valueToTree(cs.getVdmOptions()));
            }
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/suppression-options")
    public Response putConfigurationSetSuppressionOptions(@Context HttpHeaders headers,
                                                          @PathParam("configurationSetName") String name,
                                                          String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            List<String> reasons = parseSuppressedReasons(request.path("SuppressedReasons"));
            sesService.putConfigurationSetSuppressionOptions(name, reasons, region);
            LOG.infov("SES V2 PutConfigurationSetSuppressionOptions: {0} on {1}", reasons, name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/sending")
    public Response putConfigurationSetSendingOptions(@Context HttpHeaders headers,
                                                       @PathParam("configurationSetName") String name,
                                                       String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            // Reuse the AWS-aligned SendingEnabled deserialization shared with CreateConfigurationSet:
            // absent -> false, string -> true, null/number -> SerializationException. An empty body
            // / {} therefore disables sending (200). Verified against real AWS.
            boolean enabled = parseSendingEnabled(readOptionBody(body).path("SendingEnabled"));
            sesService.setConfigurationSetSendingEnabled(name, enabled, region);
            LOG.infov("SES V2 PutConfigurationSetSendingOptions: {0} on {1}", enabled, name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/reputation-options")
    public Response putConfigurationSetReputationOptions(@Context HttpHeaders headers,
                                                         @PathParam("configurationSetName") String name,
                                                         String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(body);
            Boolean enabled = parseReputationMetricsEnabled(request.path("ReputationMetricsEnabled"));
            boolean effectiveEnabled = enabled != null && enabled;
            sesService.setConfigurationSetReputationOptions(name, effectiveEnabled, region);
            LOG.infov("SES V2 PutConfigurationSetReputationOptions: {0} on {1}", effectiveEnabled, name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/tracking-options")
    public Response putConfigurationSetTrackingOptions(@Context HttpHeaders headers,
                                                       @PathParam("configurationSetName") String name,
                                                       String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(body);
            sesService.setConfigurationSetTrackingOptions(name, parseTrackingOptions(request), region);
            LOG.infov("SES V2 PutConfigurationSetTrackingOptions on {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/delivery-options")
    public Response putConfigurationSetDeliveryOptions(@Context HttpHeaders headers,
                                                       @PathParam("configurationSetName") String name,
                                                       String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(body);
            sesService.setConfigurationSetDeliveryOptions(name, parseDeliveryOptions(request), region);
            LOG.infov("SES V2 PutConfigurationSetDeliveryOptions on {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/archiving-options")
    public Response putConfigurationSetArchivingOptions(@Context HttpHeaders headers,
                                                        @PathParam("configurationSetName") String name,
                                                        String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(body);
            sesService.setConfigurationSetArchivingOptions(name, parseArchivingOptions(request), region);
            LOG.infov("SES V2 PutConfigurationSetArchivingOptions on {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/vdm-options")
    public Response putConfigurationSetVdmOptions(@Context HttpHeaders headers,
                                                  @PathParam("configurationSetName") String name,
                                                  String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(body);
            JsonNode vdmNode = request.path("VdmOptions");
            VdmOptions options = (vdmNode.isMissingNode() || vdmNode.isNull())
                    ? null : parseVdmOptions(vdmNode);
            sesService.setConfigurationSetVdmOptions(name, options, region);
            LOG.infov("SES V2 PutConfigurationSetVdmOptions on {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    /** Reject a non-object option block, mirroring the AWS deserialization layer. */
    private static void requireOptionObject(JsonNode node) {
        if (!node.isObject()) {
            throw new AwsException("SerializationException", "Expected null", 400);
        }
    }

    /** Parse a configuration-set option PUT body into a JSON object, treating an empty body as {}. */
    private JsonNode readOptionBody(String body) {
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            return request;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    private static Boolean parseReputationMetricsEnabled(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw new AwsException("BadRequestException",
                    "ReputationMetricsEnabled must be a boolean.", 400);
        }
        return node.booleanValue();
    }

    /** Read an optional string member, rejecting a non-string value the way the AWS deserialization layer does. */
    private static String parseOptionString(JsonNode node, String field) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new AwsException("BadRequestException", field + " must be a JSON string.", 400);
        }
        return node.asText();
    }

    private static TrackingOptions parseTrackingOptions(JsonNode node) {
        requireOptionObject(node);
        TrackingOptions t = new TrackingOptions();
        t.setCustomRedirectDomain(parseOptionString(node.path("CustomRedirectDomain"), "CustomRedirectDomain"));
        t.setHttpsPolicy(parseOptionString(node.path("HttpsPolicy"), "HttpsPolicy"));
        // An all-null block (e.g. an empty PUT body) clears the options rather
        // than persisting an empty object that GetConfigurationSet would echo.
        if (t.getCustomRedirectDomain() == null && t.getHttpsPolicy() == null) {
            return null;
        }
        return t;
    }

    private static DeliveryOptions parseDeliveryOptions(JsonNode node) {
        requireOptionObject(node);
        DeliveryOptions d = new DeliveryOptions();
        d.setTlsPolicy(parseOptionString(node.path("TlsPolicy"), "TlsPolicy"));
        d.setSendingPoolName(parseOptionString(node.path("SendingPoolName"), "SendingPoolName"));
        JsonNode max = node.path("MaxDeliverySeconds");
        if (!max.isMissingNode() && !max.isNull()) {
            if (!max.isNumber()) {
                throw new AwsException("BadRequestException",
                        "MaxDeliverySeconds must be a number.", 400);
            }
            if (!max.isIntegralNumber()) {
                throw new AwsException("BadRequestException",
                        "MaxDeliverySeconds must be an integer.", 400);
            }
            d.setMaxDeliverySeconds(max.asLong());
        }
        if (d.getTlsPolicy() == null && d.getSendingPoolName() == null && d.getMaxDeliverySeconds() == null) {
            return null;
        }
        return d;
    }

    private static ArchivingOptions parseArchivingOptions(JsonNode node) {
        requireOptionObject(node);
        ArchivingOptions a = new ArchivingOptions();
        a.setArchiveArn(parseOptionString(node.path("ArchiveArn"), "ArchiveArn"));
        if (a.getArchiveArn() == null) {
            return null;
        }
        return a;
    }

    private static VdmOptions parseVdmOptions(JsonNode node) {
        requireOptionObject(node);
        VdmOptions v = new VdmOptions();
        JsonNode dashboard = node.path("DashboardOptions");
        if (!dashboard.isMissingNode() && !dashboard.isNull()) {
            requireOptionObject(dashboard);
            String engagementMetrics = parseOptionString(dashboard.path("EngagementMetrics"), "EngagementMetrics");
            if (engagementMetrics != null) {
                DashboardOptions d = new DashboardOptions();
                d.setEngagementMetrics(engagementMetrics);
                v.setDashboardOptions(d);
            }
        }
        JsonNode guardian = node.path("GuardianOptions");
        if (!guardian.isMissingNode() && !guardian.isNull()) {
            requireOptionObject(guardian);
            String optimized = parseOptionString(guardian.path("OptimizedSharedDelivery"), "OptimizedSharedDelivery");
            if (optimized != null) {
                GuardianOptions g = new GuardianOptions();
                g.setOptimizedSharedDelivery(optimized);
                v.setGuardianOptions(g);
            }
        }
        // An all-null block (e.g. an empty PUT body) clears the options rather
        // than persisting an empty object that GetConfigurationSet would echo.
        if (v.getDashboardOptions() == null && v.getGuardianOptions() == null) {
            return null;
        }
        return v;
    }

    @DELETE
    @Path("/configuration-sets/{configurationSetName}")
    public Response deleteConfigurationSet(@Context HttpHeaders headers,
                                            @PathParam("configurationSetName") String name) {
        String region = regionResolver.resolveRegion(headers);
        try {
            sesService.deleteConfigurationSet(name, region);
            LOG.infov("SES V2 DeleteConfigurationSet: {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    // ──────────────── Configuration Set Event Destinations ────────────────

    @POST
    @Path("/configuration-sets/{configurationSetName}/event-destinations")
    public Response createConfigurationSetEventDestination(@Context HttpHeaders headers,
                                                           @PathParam("configurationSetName") String configurationSetName,
                                                           String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            String edName = request.path("EventDestinationName").asText(null);
            if (edName == null || edName.isBlank()) {
                throw new AwsException("BadRequestException", "EventDestinationName is required.", 400);
            }
            JsonNode edNode = request.path("EventDestination");
            if (!edNode.isObject()) {
                throw new AwsException("BadRequestException", "EventDestination is required.", 400);
            }
            EventDestination dest = objectMapper.treeToValue(edNode, EventDestination.class);
            sesService.createConfigurationSetEventDestination(configurationSetName, edName, dest, region);
            LOG.infov("SES V2 CreateConfigurationSetEventDestination: {0} on {1}", edName, configurationSetName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/configuration-sets/{configurationSetName}/event-destinations")
    public Response getConfigurationSetEventDestinations(@Context HttpHeaders headers,
                                                         @PathParam("configurationSetName") String configurationSetName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            List<EventDestination> dests =
                    sesService.getConfigurationSetEventDestinations(configurationSetName, region);
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode arr = result.putArray("EventDestinations");
            for (EventDestination ed : dests) {
                arr.add(objectMapper.valueToTree(ed));
            }
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/event-destinations/{eventDestinationName}")
    public Response updateConfigurationSetEventDestination(@Context HttpHeaders headers,
                                                           @PathParam("configurationSetName") String configurationSetName,
                                                           @PathParam("eventDestinationName") String eventDestinationName,
                                                           String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            JsonNode edNode = request.path("EventDestination");
            if (!edNode.isObject()) {
                throw new AwsException("BadRequestException", "EventDestination is required.", 400);
            }
            EventDestination dest = objectMapper.treeToValue(edNode, EventDestination.class);
            sesService.updateConfigurationSetEventDestination(configurationSetName, eventDestinationName, dest, region);
            LOG.infov("SES V2 UpdateConfigurationSetEventDestination: {0} on {1}",
                    eventDestinationName, configurationSetName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/configuration-sets/{configurationSetName}/event-destinations/{eventDestinationName}")
    public Response deleteConfigurationSetEventDestination(@Context HttpHeaders headers,
                                                           @PathParam("configurationSetName") String configurationSetName,
                                                           @PathParam("eventDestinationName") String eventDestinationName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            sesService.deleteConfigurationSetEventDestination(configurationSetName, eventDestinationName, region);
            LOG.infov("SES V2 DeleteConfigurationSetEventDestination: {0} on {1}",
                    eventDestinationName, configurationSetName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    // ──────────────────────── Dedicated IP Pools ────────────────────────

    @POST
    @Path("/dedicated-ip-pools")
    public Response createDedicatedIpPool(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            String poolName = readRequiredStringField(request, "PoolName");
            JsonNode scalingNode = request.path("ScalingMode");
            String scalingMode;
            if (scalingNode.isMissingNode() || scalingNode.isNull()) {
                scalingMode = null;
            } else if (!scalingNode.isTextual()) {
                throw new AwsException("BadRequestException",
                        "The ScalingMode parameter is invalid.", 400);
            } else {
                scalingMode = scalingNode.asText();
            }
            List<Tag> parsedTags = parseTagsArray(request.path("Tags"));
            sesService.createDedicatedIpPool(poolName, scalingMode, parsedTags, region);
            LOG.infov("SES V2 CreateDedicatedIpPool: {0}", poolName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/dedicated-ip-pools")
    public Response listDedicatedIpPools(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode pools = result.putArray("DedicatedIpPools");
        sesService.listDedicatedIpPools(region).forEach(pools::add);
        return Response.ok(result).build();
    }

    @GET
    @Path("/dedicated-ip-pools/{poolName}")
    public Response getDedicatedIpPool(@Context HttpHeaders headers,
                                       @PathParam("poolName") String poolName) {
        String region = regionResolver.resolveRegion(headers);
        DedicatedIpPool pool = sesService.getDedicatedIpPool(poolName, region);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("DedicatedIpPool", objectMapper.valueToTree(pool));
        return Response.ok(result).build();
    }

    @DELETE
    @Path("/dedicated-ip-pools/{poolName}")
    public Response deleteDedicatedIpPool(@Context HttpHeaders headers,
                                          @PathParam("poolName") String poolName) {
        String region = regionResolver.resolveRegion(headers);
        sesService.deleteDedicatedIpPool(poolName, region);
        LOG.infov("SES V2 DeleteDedicatedIpPool: {0}", poolName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/dedicated-ip-pools/{poolName}/scaling")
    public Response putDedicatedIpPoolScalingAttributes(@Context HttpHeaders headers,
                                                        @PathParam("poolName") String poolName,
                                                        String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body == null ? "{}" : body);
            requireJsonObject(request);
            String scalingMode = readRequiredStringField(request, "ScalingMode");
            sesService.putDedicatedIpPoolScalingAttributes(poolName, scalingMode, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    // ─────────────────────────── Contact lists ───────────────────────────

    @POST
    @Path("/contact-lists")
    public Response createContactList(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            // Read leniently; the service surfaces a missing ContactListName as the AWS Smithy
            // validation error rather than a custom "required" message.
            String name = request.path("ContactListName").asText(null);
            List<Topic> topics = parseTopicsArray(request.path("Topics"));
            List<Tag> tags = parseTagsArray(request.path("Tags"));
            String description = request.path("Description").asText(null);
            sesService.createContactList(name, description, topics, tags, region);
            LOG.infov("SES V2 CreateContactList: {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/contact-lists")
    public Response listContactLists(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode lists = result.putArray("ContactLists");
        for (ContactList cl : sesService.listContactLists(region)) {
            ObjectNode item = lists.addObject();
            item.put("ContactListName", cl.getContactListName());
            if (cl.getLastUpdatedTimestamp() != null) {
                item.put("LastUpdatedTimestamp", cl.getLastUpdatedTimestamp().getEpochSecond());
            }
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/contact-lists/{contactListName}")
    public Response getContactList(@Context HttpHeaders headers,
                                   @PathParam("contactListName") String contactListName) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(contactListJson(sesService.getContactList(contactListName, region))).build();
    }

    @PUT
    @Path("/contact-lists/{contactListName}")
    public Response updateContactList(@Context HttpHeaders headers,
                                      @PathParam("contactListName") String contactListName,
                                      String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            JsonNode topicsNode = request.path("Topics");
            // Treat absent or explicit-null Topics as "not provided" (keep existing), consistent
            // with Description; clearing is done via an explicit empty array [].
            List<Topic> topics = (topicsNode.isMissingNode() || topicsNode.isNull())
                    ? null : parseTopicsArray(topicsNode);
            JsonNode descNode = request.path("Description");
            boolean descriptionPresent = !descNode.isMissingNode() && !descNode.isNull();
            String description = descNode.asText(null);
            sesService.updateContactList(contactListName, description, descriptionPresent, topics, region);
            LOG.infov("SES V2 UpdateContactList: {0}", contactListName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/contact-lists/{contactListName}")
    public Response deleteContactList(@Context HttpHeaders headers,
                                      @PathParam("contactListName") String contactListName) {
        String region = regionResolver.resolveRegion(headers);
        sesService.deleteContactList(contactListName, region);
        LOG.infov("SES V2 DeleteContactList: {0}", contactListName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private List<Topic> parseTopicsArray(JsonNode topicsNode) {
        List<Topic> out = new ArrayList<>();
        if (topicsNode == null || topicsNode.isMissingNode() || topicsNode.isNull()) {
            return out;
        }
        if (!topicsNode.isArray()) {
            throw new AwsException("BadRequestException", "Topics must be an array.", 400);
        }
        for (JsonNode t : topicsNode) {
            out.add(new Topic(
                    t.path("TopicName").asText(null),
                    t.path("DisplayName").asText(null),
                    t.path("DefaultSubscriptionStatus").asText(null),
                    t.path("Description").asText(null)));
        }
        return out;
    }

    private ObjectNode contactListJson(ContactList cl) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("ContactListName", cl.getContactListName());
        if (cl.getDescription() != null) {
            result.put("Description", cl.getDescription());
        }
        ArrayNode topics = result.putArray("Topics");
        for (Topic t : cl.getTopics()) {
            ObjectNode to = topics.addObject();
            to.put("TopicName", t.getTopicName());
            to.put("DisplayName", t.getDisplayName());
            to.put("DefaultSubscriptionStatus", t.getDefaultSubscriptionStatus());
            if (t.getDescription() != null) {
                to.put("Description", t.getDescription());
            }
        }
        if (cl.getCreatedTimestamp() != null) {
            result.put("CreatedTimestamp", cl.getCreatedTimestamp().getEpochSecond());
        }
        if (cl.getLastUpdatedTimestamp() != null) {
            result.put("LastUpdatedTimestamp", cl.getLastUpdatedTimestamp().getEpochSecond());
        }
        ArrayNode tags = result.putArray("Tags");
        for (Tag tag : cl.getTags()) {
            ObjectNode tn = tags.addObject();
            tn.put("Key", tag.key());
            tn.put("Value", tag.value());
        }
        return result;
    }

    // ───────────────────────────── Contacts ─────────────────────────────

    @POST
    @Path("/contact-lists/{contactListName}/contacts")
    public Response createContact(@Context HttpHeaders headers,
                                  @PathParam("contactListName") String contactListName, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            String emailAddress = request.path("EmailAddress").asText(null);
            List<TopicPreference> prefs = parseTopicPreferences(request.path("TopicPreferences"));
            Boolean unsubscribeAll = parseUnsubscribeAll(request);
            String attributesData = parseAttributesData(request);
            sesService.createContact(contactListName, emailAddress, prefs, unsubscribeAll, attributesData, region);
            LOG.infov("SES V2 CreateContact: {0} in {1}", emailAddress, contactListName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/contact-lists/{contactListName}/contacts/list")
    public Response listContacts(@Context HttpHeaders headers,
                                 @PathParam("contactListName") String contactListName, String body) {
        // AWS uses POST .../contacts/list with Filter/PageSize/NextToken in the body; Floci returns
        // all contacts (filtering/pagination not yet implemented) but still rejects a malformed or
        // non-object body like the other v2 endpoints.
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body != null && !body.isBlank()) {
                requireJsonObject(objectMapper.readTree(body));
            }
            SesService.ContactsWithList listed = sesService.listContacts(contactListName, region);
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode arr = result.putArray("Contacts");
            for (Contact c : listed.contacts()) {
                arr.add(contactJson(c, listed.list(), false));
            }
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/contact-lists/{contactListName}/contacts/{emailAddress}")
    public Response getContact(@Context HttpHeaders headers,
                               @PathParam("contactListName") String contactListName,
                               @PathParam("emailAddress") String emailAddress) {
        String region = regionResolver.resolveRegion(headers);
        SesService.ContactWithList result = sesService.getContact(contactListName, emailAddress, region);
        return Response.ok(contactJson(result.contact(), result.list(), true)).build();
    }

    @PUT
    @Path("/contact-lists/{contactListName}/contacts/{emailAddress}")
    public Response updateContact(@Context HttpHeaders headers,
                                  @PathParam("contactListName") String contactListName,
                                  @PathParam("emailAddress") String emailAddress, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            JsonNode prefsNode = request.path("TopicPreferences");
            boolean prefsPresent = !prefsNode.isMissingNode() && !prefsNode.isNull();
            List<TopicPreference> prefs = prefsPresent ? parseTopicPreferences(prefsNode) : null;
            Boolean unsubscribeAll = parseUnsubscribeAll(request);
            String attributesData = parseAttributesData(request);
            sesService.updateContact(contactListName, emailAddress, prefs, prefsPresent,
                    unsubscribeAll, attributesData, region);
            LOG.infov("SES V2 UpdateContact: {0} in {1}", emailAddress, contactListName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/contact-lists/{contactListName}/contacts/{emailAddress}")
    public Response deleteContact(@Context HttpHeaders headers,
                                  @PathParam("contactListName") String contactListName,
                                  @PathParam("emailAddress") String emailAddress) {
        String region = regionResolver.resolveRegion(headers);
        sesService.deleteContact(contactListName, emailAddress, region);
        LOG.infov("SES V2 DeleteContact: {0} in {1}", emailAddress, contactListName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private List<TopicPreference> parseTopicPreferences(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new AwsException("BadRequestException", "TopicPreferences must be an array.", 400);
        }
        List<TopicPreference> out = new ArrayList<>();
        for (JsonNode p : node) {
            out.add(new TopicPreference(
                    p.path("TopicName").asText(null),
                    p.path("SubscriptionStatus").asText(null)));
        }
        return out;
    }

    // UnsubscribeAll is a Boolean; AWS coerces it the same way as any other SES v2 boolean
    // (see parseSendingEnabled): a JSON string coerces to true, a number/null/array/object is a
    // SerializationException. Absent leaves it unset.
    private static Boolean parseUnsubscribeAll(JsonNode request) {
        if (!request.has("UnsubscribeAll")) {
            return null;
        }
        return coerceBoolean(request.path("UnsubscribeAll"));
    }

    // AttributesData is a String; a non-string (number/boolean/array/object) is a
    // SerializationException. Absent or explicit null leaves it unset.
    private static String parseAttributesData(JsonNode request) {
        if (!request.has("AttributesData")) {
            return null;
        }
        JsonNode node = request.path("AttributesData");
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isNumber()) {
            throw new AwsException("SerializationException",
                    "NUMBER_VALUE can not be converted to a String", 400);
        }
        if (node.isBoolean()) {
            throw new AwsException("SerializationException",
                    (node.booleanValue() ? "TRUE_VALUE" : "FALSE_VALUE")
                            + " can not be converted to a String", 400);
        }
        throw unexpectedStartError(node);
    }

    private ObjectNode contactJson(Contact c, ContactList list, boolean full) {
        ObjectNode result = objectMapper.createObjectNode();
        if (full) {
            result.put("ContactListName", list.getContactListName());
        }
        result.put("EmailAddress", c.getEmailAddress());
        ArrayNode prefs = result.putArray("TopicPreferences");
        for (TopicPreference p : c.getTopicPreferences()) {
            ObjectNode po = prefs.addObject();
            po.put("TopicName", p.getTopicName());
            po.put("SubscriptionStatus", p.getSubscriptionStatus());
        }
        ArrayNode defaults = result.putArray("TopicDefaultPreferences");
        for (TopicPreference p : sesService.deriveTopicDefaultPreferences(c, list)) {
            ObjectNode po = defaults.addObject();
            po.put("TopicName", p.getTopicName());
            po.put("SubscriptionStatus", p.getSubscriptionStatus());
        }
        result.put("UnsubscribeAll", c.isUnsubscribeAll());
        if (full && c.getAttributesData() != null) {
            result.put("AttributesData", c.getAttributesData());
        }
        if (full && c.getCreatedTimestamp() != null) {
            result.put("CreatedTimestamp", c.getCreatedTimestamp().getEpochSecond());
        }
        if (c.getLastUpdatedTimestamp() != null) {
            result.put("LastUpdatedTimestamp", c.getLastUpdatedTimestamp().getEpochSecond());
        }
        return result;
    }

    // ──────────────────────────── Account ────────────────────────────

    @GET
    @Path("/account")
    public Response getAccount(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        long sentCount = sesService.getSentEmailCount(region);
        boolean sendingEnabled = sesService.isAccountSendingEnabled(region);
        AccountSuppressionAttributes suppression = sesService.getAccountSuppressionAttributes(region);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("DedicatedIpAutoWarmupEnabled", false);
        result.put("EnforcementStatus", "HEALTHY");
        result.put("ProductionAccessEnabled", true);
        result.put("SendingEnabled", sendingEnabled);

        ObjectNode sendQuota = result.putObject("SendQuota");
        sendQuota.put("Max24HourSend", 200.0);
        sendQuota.put("MaxSendRate", 1.0);
        sendQuota.put("SentLast24Hours", (double) sentCount);

        ObjectNode suppressionAttrs = result.putObject("SuppressionAttributes");
        ArrayNode reasons = suppressionAttrs.putArray("SuppressedReasons");
        for (String r : suppression.getSuppressedReasons()) {
            reasons.add(r);
        }

        AccountVdmAttributes vdm = sesService.getAccountVdmAttributes(region);
        ObjectNode vdmNode = result.putObject("VdmAttributes");
        vdmNode.put("VdmEnabled", vdm.getVdmEnabled() == null ? "DISABLED" : vdm.getVdmEnabled());
        if (vdm.getEngagementMetrics() != null) {
            ObjectNode dashboard = vdmNode.putObject("DashboardAttributes");
            dashboard.put("EngagementMetrics", vdm.getEngagementMetrics());
        }
        if (vdm.getOptimizedSharedDelivery() != null) {
            ObjectNode guardian = vdmNode.putObject("GuardianAttributes");
            guardian.put("OptimizedSharedDelivery", vdm.getOptimizedSharedDelivery());
        }

        return Response.ok(result).build();
    }

    @PUT
    @Path("/account/vdm")
    public Response putAccountVdmAttributes(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            requireJsonObject(request);
            JsonNode vdmNode = request.path("VdmAttributes");
            if (vdmNode.isMissingNode() || vdmNode.isNull() || !vdmNode.isObject()) {
                throw new AwsException("BadRequestException", "VdmAttributes is required.", 400);
            }
            AccountVdmAttributes attrs = new AccountVdmAttributes();
            attrs.setVdmEnabled(vdmNode.path("VdmEnabled").asText(null));
            JsonNode dashboard = vdmNode.path("DashboardAttributes");
            if (!dashboard.isMissingNode() && dashboard.isObject()) {
                attrs.setEngagementMetrics(dashboard.path("EngagementMetrics").asText(null));
            }
            JsonNode guardian = vdmNode.path("GuardianAttributes");
            if (!guardian.isMissingNode() && guardian.isObject()) {
                attrs.setOptimizedSharedDelivery(guardian.path("OptimizedSharedDelivery").asText(null));
            }
            sesService.putAccountVdmAttributes(region, attrs);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @PUT
    @Path("/account/suppression")
    public Response putAccountSuppressionAttributes(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            JsonNode reasonsNode = request.path("SuppressedReasons");
            List<String> reasons = new ArrayList<>();
            if (!reasonsNode.isMissingNode() && !reasonsNode.isNull()) {
                if (!reasonsNode.isArray()) {
                    throw new AwsException("BadRequestException", "SuppressedReasons must be an array.", 400);
                }
                for (JsonNode r : reasonsNode) {
                    if (r.isNull() || !r.isTextual()) {
                        throw new AwsException("BadRequestException",
                                "SuppressedReasons entries must be strings.", 400);
                    }
                    reasons.add(r.asText());
                }
            }
            sesService.putAccountSuppressionAttributes(region, reasons);
            LOG.infov("SES V2 PutAccountSuppressionAttributes: {0}", reasons);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @PUT
    @Path("/account/sending")
    public Response putAccountSendingAttributes(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            JsonNode sendingEnabledNode = request.get("SendingEnabled");
            if (sendingEnabledNode == null || !sendingEnabledNode.isBoolean()) {
                throw new AwsException("BadRequestException",
                        "SendingEnabled must be present and must be a boolean", 400);
            }
            sesService.setAccountSendingEnabled(region, sendingEnabledNode.booleanValue());
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    // ──────────────────── Suppression list ───────────────────────────

    @PUT
    @Path("/suppression/addresses")
    public Response putSuppressedDestination(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            String emailAddress = readRequiredStringField(request, "EmailAddress");
            String reason = readRequiredStringField(request, "Reason");
            sesService.putSuppressedDestination(region, emailAddress, reason);
            LOG.infov("SES V2 PutSuppressedDestination: {0} ({1})", emailAddress, reason);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    private static String readRequiredStringField(JsonNode request, String fieldName) {
        JsonNode node = request.path(fieldName);
        if (node.isMissingNode() || node.isNull() || !node.isTextual()) {
            throw new AwsException("BadRequestException", fieldName + " is required.", 400);
        }
        return node.asText();
    }

    @GET
    @Path("/suppression/addresses/{emailAddress}")
    public Response getSuppressedDestination(@Context HttpHeaders headers,
                                              @PathParam("emailAddress") String emailAddress) {
        String region = regionResolver.resolveRegion(headers);
        try {
            SuppressedDestination suppressed = sesService.getSuppressedDestination(region, emailAddress);
            ObjectNode result = objectMapper.createObjectNode();
            ObjectNode entry = result.putObject("SuppressedDestination");
            entry.put("EmailAddress", suppressed.getEmailAddress());
            entry.put("Reason", suppressed.getReason());
            if (suppressed.getLastUpdateTime() != null) {
                entry.put("LastUpdateTime", suppressed.getLastUpdateTime().getEpochSecond());
            }
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @DELETE
    @Path("/suppression/addresses/{emailAddress}")
    public Response deleteSuppressedDestination(@Context HttpHeaders headers,
                                                 @PathParam("emailAddress") String emailAddress) {
        String region = regionResolver.resolveRegion(headers);
        try {
            sesService.deleteSuppressedDestination(region, emailAddress);
            LOG.infov("SES V2 DeleteSuppressedDestination: {0}", emailAddress);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @GET
    @Path("/suppression/addresses")
    public Response listSuppressedDestinations(@Context HttpHeaders headers,
                                                @QueryParam("Reason") List<String> reasons) {
        String region = regionResolver.resolveRegion(headers);
        List<SuppressedDestination> entries;
        try {
            entries = sesService.listSuppressedDestinations(region, reasons);
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode summaries = result.putArray("SuppressedDestinationSummaries");
        for (SuppressedDestination s : entries) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("EmailAddress", s.getEmailAddress());
            item.put("Reason", s.getReason());
            if (s.getLastUpdateTime() != null) {
                item.put("LastUpdateTime", s.getLastUpdateTime().getEpochSecond());
            }
            summaries.add(item);
        }
        return Response.ok(result).build();
    }

    // ──────────────────────────── Tags ───────────────────────────────

    @POST
    @Path("/tags")
    public Response tagResource(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            String arn = request.path("ResourceArn").asText(null);
            if (arn == null || arn.isBlank()) {
                throw new AwsException("BadRequestException", "ResourceArn is required.", 400);
            }
            List<Tag> tags = parseTagsArray(request.path("Tags"));
            if (tags == null) {
                throw new AwsException("BadRequestException", "Tags must be an array.", 400);
            }
            sesService.tagResource(arn, region, tags);
            LOG.infov("SES V2 TagResource: {0}", arn);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/tags")
    public Response untagResource(@Context HttpHeaders headers,
                                   @QueryParam("ResourceArn") String arn,
                                   @QueryParam("TagKeys") List<String> tagKeys) {
        String region = regionResolver.resolveRegion(headers);
        try {
            sesService.untagResource(arn, region, tagKeys);
            LOG.infov("SES V2 UntagResource: {0}", arn);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @GET
    @Path("/tags")
    public Response listTagsForResource(@Context HttpHeaders headers,
                                         @QueryParam("ResourceArn") String arn) {
        String region = regionResolver.resolveRegion(headers);
        try {
            List<Tag> tags = sesService.listResourceTags(arn, region);
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode arr = result.putArray("Tags");
            for (Tag t : tags) {
                ObjectNode tagNode = objectMapper.createObjectNode();
                tagNode.put("Key", t.key());
                tagNode.put("Value", t.value());
                arr.add(tagNode);
            }
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    // ──────────────────────────── Tenants ────────────────────────────

    @POST
    @Path("/tenants")
    public Response createTenant(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body == null ? "{}" : body);
            requireJsonObject(request);
            String name = readRequiredStringField(request, "TenantName");
            List<Tag> tags = parseTagsArray(request.path("Tags"));
            List<String> reasons = null;
            String scope = null;
            JsonNode suppression = request.path("SuppressionAttributes");
            if (!suppression.isMissingNode() && suppression.isObject()) {
                JsonNode reasonsNode = suppression.path("SuppressedReasons");
                if (!reasonsNode.isMissingNode() && !reasonsNode.isNull()) {
                    reasons = jsonArrayToList(reasonsNode);
                }
                if (suppression.has("SuppressionScope") && !suppression.get("SuppressionScope").isNull()) {
                    scope = suppression.path("SuppressionScope").asText(null);
                }
            }
            Tenant tenant = sesService.createTenant(name, tags, reasons, scope, region);
            return Response.ok(buildTenantNode(tenant)).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/tenants/get")
    public Response getTenant(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body == null ? "{}" : body);
            Tenant tenant = sesService.getTenant(readRequiredStringField(request, "TenantName"), region);
            ObjectNode result = objectMapper.createObjectNode();
            result.set("Tenant", buildTenantNode(tenant));
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/tenants/list")
    public Response listTenants(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tenants = result.putArray("Tenants");
        for (Tenant tenant : sesService.listTenants(region)) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("TenantName", tenant.getTenantName());
            item.put("TenantId", tenant.getTenantId());
            item.put("TenantArn", tenant.getTenantArn());
            if (tenant.getCreatedTimestamp() != null) {
                item.put("CreatedTimestamp", tenant.getCreatedTimestamp().getEpochSecond());
            }
            tenants.add(item);
        }
        return Response.ok(result).build();
    }

    @POST
    @Path("/tenants/delete")
    public Response deleteTenant(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body == null ? "{}" : body);
            sesService.deleteTenant(readRequiredStringField(request, "TenantName"), region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @PUT
    @Path("/tenant/suppression")
    public Response putTenantSuppressionAttributes(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body == null ? "{}" : body);
            requireJsonObject(request);
            String name = readRequiredStringField(request, "TenantName");
            List<String> reasons = request.has("SuppressedReasons")
                    ? jsonArrayToList(request.path("SuppressedReasons")) : null;
            String scope = request.has("SuppressionScope")
                    ? request.path("SuppressionScope").asText(null) : null;
            sesService.putTenantSuppressionAttributes(name, reasons, scope, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/tenants/resources")
    public Response createTenantResourceAssociation(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body == null ? "{}" : body);
            sesService.createTenantResourceAssociation(
                    readRequiredStringField(request, "TenantName"),
                    readRequiredStringField(request, "ResourceArn"),
                    region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/tenants/resources/delete")
    public Response deleteTenantResourceAssociation(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body == null ? "{}" : body);
            sesService.deleteTenantResourceAssociation(
                    readRequiredStringField(request, "TenantName"),
                    readRequiredStringField(request, "ResourceArn"),
                    region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/tenants/resources/list")
    public Response listTenantResources(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body == null ? "{}" : body);
            String tenantName = readRequiredStringField(request, "TenantName");
            String resourceType = null;
            JsonNode filter = request.path("Filter");
            if (filter.isObject()) {
                resourceType = filter.path("RESOURCE_TYPE").asText(null);
            }
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode resources = result.putArray("TenantResources");
            for (TenantResource resource : sesService.listTenantResources(tenantName, resourceType, region)) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("ResourceType", resource.getResourceType());
                item.put("ResourceArn", resource.getResourceArn());
                resources.add(item);
            }
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    private ObjectNode buildTenantNode(Tenant tenant) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("TenantName", tenant.getTenantName());
        node.put("TenantId", tenant.getTenantId());
        node.put("TenantArn", tenant.getTenantArn());
        if (tenant.getCreatedTimestamp() != null) {
            node.put("CreatedTimestamp", tenant.getCreatedTimestamp().getEpochSecond());
        }
        node.put("SendingStatus", tenant.getSendingStatus());
        ArrayNode tags = node.putArray("Tags");
        if (tenant.getTags() != null) {
            for (Tag t : tenant.getTags()) {
                ObjectNode tagNode = objectMapper.createObjectNode();
                tagNode.put("Key", t.key());
                tagNode.put("Value", t.value());
                tags.add(tagNode);
            }
        }
        if (tenant.getSuppressedReasons() != null || tenant.getSuppressionScope() != null) {
            ObjectNode suppression = node.putObject("SuppressionAttributes");
            if (tenant.getSuppressedReasons() != null) {
                ArrayNode reasons = suppression.putArray("SuppressedReasons");
                tenant.getSuppressedReasons().forEach(reasons::add);
            }
            if (tenant.getSuppressionScope() != null) {
                suppression.put("SuppressionScope", tenant.getSuppressionScope());
            }
        }
        return node;
    }

    // ──────────────────── Multi-region endpoints ─────────────────────

    @POST
    @Path("/multi-region-endpoints")
    public Response createMultiRegionEndpoint(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body == null ? "{}" : body);
            requireJsonObject(request);
            String name = readRequiredStringField(request, "EndpointName");
            List<String> regions = new ArrayList<>();
            JsonNode details = request.path("Details");
            JsonNode routes = details.path("RoutesDetails");
            if (routes.isArray()) {
                for (JsonNode route : routes) {
                    String routeRegion = route.path("Region").asText(null);
                    if (routeRegion != null && !routeRegion.isBlank()) {
                        regions.add(routeRegion);
                    }
                }
            }
            List<Tag> tags = parseTagsArray(request.path("Tags"));
            MultiRegionEndpoint endpoint = sesService.createMultiRegionEndpoint(name, regions, tags, region);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("Status", endpoint.getStatus());
            result.put("EndpointId", endpoint.getEndpointId());
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/multi-region-endpoints")
    public Response listMultiRegionEndpoints(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode items = result.putArray("MultiRegionEndpoints");
        for (MultiRegionEndpoint endpoint : sesService.listMultiRegionEndpoints(region)) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("EndpointName", endpoint.getEndpointName());
            item.put("EndpointId", endpoint.getEndpointId());
            item.put("Status", endpoint.getStatus());
            items.add(item);
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/multi-region-endpoints/{endpointName}")
    public Response getMultiRegionEndpoint(@Context HttpHeaders headers,
                                           @PathParam("endpointName") String endpointName) {
        String region = regionResolver.resolveRegion(headers);
        MultiRegionEndpoint endpoint = sesService.getMultiRegionEndpoint(endpointName, region);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("EndpointName", endpoint.getEndpointName());
        result.put("EndpointId", endpoint.getEndpointId());
        result.put("Status", endpoint.getStatus());
        ArrayNode routes = result.putArray("Routes");
        for (String routeRegion : endpoint.getRegions()) {
            ObjectNode route = objectMapper.createObjectNode();
            route.put("Region", routeRegion);
            routes.add(route);
        }
        if (endpoint.getCreatedTimestamp() != null) {
            result.put("CreatedTimestamp", endpoint.getCreatedTimestamp().getEpochSecond());
        }
        if (endpoint.getLastUpdatedTimestamp() != null) {
            result.put("LastUpdatedTimestamp", endpoint.getLastUpdatedTimestamp().getEpochSecond());
        }
        return Response.ok(result).build();
    }

    @DELETE
    @Path("/multi-region-endpoints/{endpointName}")
    public Response deleteMultiRegionEndpoint(@Context HttpHeaders headers,
                                              @PathParam("endpointName") String endpointName) {
        String region = regionResolver.resolveRegion(headers);
        sesService.deleteMultiRegionEndpoint(endpointName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/outbound-custom-verification-emails")
    public Response sendCustomVerificationEmail(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body == null ? "{}" : body);
            requireJsonObject(request);
            String messageId = sesService.sendCustomVerificationEmail(
                    readRequiredStringField(request, "EmailAddress"),
                    readRequiredStringField(request, "TemplateName"),
                    request.path("ConfigurationSetName").asText(null),
                    region);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("MessageId", messageId);
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/deliverability-dashboard/blacklist-report")
    public Response getBlacklistReports(@QueryParam("BlacklistItemNames") List<String> blacklistItemNames) {
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode report = result.putObject("BlacklistReport");
        if (blacklistItemNames != null) {
            for (String item : blacklistItemNames) {
                if (item != null && !item.isBlank()) {
                    report.putArray(item);
                }
            }
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/insights/{messageId}")
    public Response getMessageInsights(@Context HttpHeaders headers,
                                       @PathParam("messageId") String messageId) {
        String region = regionResolver.resolveRegion(headers);
        if (!sesService.isVdmEnabled(region)) {
            throw new AwsException("NotFoundException",
                    "To use this feature you must enable Virtual Deliverability Manager.", 404);
        }
        if (messageId == null || messageId.isBlank() || !messageId.contains("-")) {
            throw new AwsException("BadRequestException",
                    "MessageId is not a valid SES message id.", 400);
        }
        throw new AwsException("NotFoundException",
                "Message " + messageId + " was not found.", 404);
    }

    @POST
    @Path("/metrics/batch")
    public Response batchGetMetricData(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (!sesService.isVdmEnabled(region)) {
                throw new AwsException("BadRequestException",
                        "To use this feature you must enable Virtual Deliverability Manager.", 400);
            }
            JsonNode request = objectMapper.readTree(body == null ? "{}" : body);
            requireJsonObject(request);
            JsonNode queries = request.path("Queries");
            if (!queries.isArray() || queries.isEmpty()) {
                throw new AwsException("BadRequestException", "Queries is required.", 400);
            }
            ArrayNode results = objectMapper.createArrayNode();
            for (JsonNode query : queries) {
                Instant start = parseMetricTimestamp(query.path("StartDate"));
                Instant end = parseMetricTimestamp(query.path("EndDate"));
                if (start != null && end != null && (!isMidnightUtc(start) || !isMidnightUtc(end))) {
                    throw new AwsException("BadRequestException",
                            "To get daily aggregated data you must not specify partial-day timestamps. "
                                    + "Please make your interval go from midnight to midnight UTC.", 400);
                }
                ObjectNode item = objectMapper.createObjectNode();
                item.put("Id", query.path("Id").asText(""));
                item.putArray("Timestamps");
                item.putArray("Values");
                results.add(item);
            }
            ObjectNode result = objectMapper.createObjectNode();
            result.set("Results", results);
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/deliverability-dashboard/statistics-report/{domain}")
    public Response getDomainStatisticsReport(@Context HttpHeaders headers,
                                              @PathParam("domain") String domain) {
        String region = regionResolver.resolveRegion(headers);
        Identity identity = sesService.getIdentityVerificationAttributes(domain, region);
        if (identity == null) {
            throw new AwsException("NotFoundException",
                    "Domain " + domain + " is not a verified identity.", 404);
        }
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode overall = result.putObject("OverallVolume");
        ObjectNode stats = overall.putObject("VolumeStatistics");
        stats.put("InboxRawCount", 0);
        stats.put("SpamRawCount", 0);
        result.putArray("DailyVolumes");
        return Response.ok(result).build();
    }

    private static Instant parseMetricTimestamp(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return Instant.ofEpochSecond(node.asLong());
        }
        if (node.isTextual()) {
            try {
                return Instant.parse(node.asText());
            } catch (Exception ignored) {
                return Instant.ofEpochSecond(Long.parseLong(node.asText()));
            }
        }
        return null;
    }

    private static boolean isMidnightUtc(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalTime().equals(LocalTime.MIDNIGHT);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode buildFullIdentityResponse(Identity identity) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("IdentityType", toV2IdentityType(identity.getIdentityType()));
        result.put("VerifiedForSendingStatus",
                "Success".equals(identity.getVerificationStatus()));
        result.put("VerificationStatus", toV2Status(identity.getVerificationStatus()));
        result.put("FeedbackForwardingStatus", identity.isFeedbackForwardingEnabled());

        result.set("DkimAttributes", buildDkimAttributes(identity));

        ObjectNode mailFromAttributes = result.putObject("MailFromAttributes");
        mailFromAttributes.put("BehaviorOnMxFailure", v1BehaviorToV2(identity.getBehaviorOnMxFailure()));
        String mailFromDomain = identity.getMailFromDomain();
        if (mailFromDomain != null && !mailFromDomain.isEmpty()) {
            mailFromAttributes.put("MailFromDomain", mailFromDomain);
            mailFromAttributes.put("MailFromDomainStatus", toV2Status(identity.getMailFromDomainStatus()));
        }

        if (identity.getConfigurationSetName() != null && !identity.getConfigurationSetName().isEmpty()) {
            result.put("ConfigurationSetName", identity.getConfigurationSetName());
        }

        result.putObject("Policies");
        ArrayNode tags = result.putArray("Tags");
        for (Tag t : identity.getTags()) {
            ObjectNode tagNode = objectMapper.createObjectNode();
            tagNode.put("Key", t.key());
            tagNode.put("Value", t.value());
            tags.add(tagNode);
        }

        return result;
    }

    private static String v1BehaviorToV2(String v1) {
        if ("RejectMessage".equals(v1)) {
            return "REJECT_MESSAGE";
        }
        return "USE_DEFAULT_VALUE";
    }

    private static String v2BehaviorToV1(String v2) {
        if (v2 == null) {
            return null;
        }
        if ("REJECT_MESSAGE".equals(v2)) {
            return "RejectMessage";
        }
        if ("USE_DEFAULT_VALUE".equals(v2)) {
            return "UseDefaultValue";
        }
        throw new AwsException("BadRequestException",
                "1 validation error detected: Value at 'behaviorOnMxFailure' failed to satisfy "
                        + "constraint: Member must satisfy enum value set: [REJECT_MESSAGE, USE_DEFAULT_VALUE]", 400);
    }

    private ObjectNode buildDkimAttributes(Identity identity) {
        ObjectNode dkim = objectMapper.createObjectNode();
        dkim.put("SigningEnabled", identity.isDkimEnabled());
        dkim.put("Status", toV2Status(identity.getDkimVerificationStatus()));
        ArrayNode tokens = dkim.putArray("Tokens");
        if (identity.getDkimTokens() != null) {
            for (String token : identity.getDkimTokens()) {
                tokens.add(token);
            }
        }
        if (identity.getNextSigningKeyLength() != null) {
            dkim.put("NextSigningKeyLength", identity.getNextSigningKeyLength());
        }
        if (identity.getCurrentSigningKeyLength() != null) {
            dkim.put("CurrentSigningKeyLength", identity.getCurrentSigningKeyLength());
        }
        if (identity.getSigningAttributesOrigin() != null) {
            dkim.put("SigningAttributesOrigin", identity.getSigningAttributesOrigin());
        }
        return dkim;
    }

    private static String toV2IdentityType(String v1Type) {
        return "EmailAddress".equals(v1Type) ? "EMAIL_ADDRESS" : "DOMAIN";
    }

    private static String toV2Status(String v1Status) {
        if (v1Status == null) return null;
        return switch (v1Status) {
            case "Success" -> "SUCCESS";
            case "NotStarted" -> "NOT_STARTED";
            case "Pending" -> "PENDING";
            case "Failed" -> "FAILED";
            case "TemporaryFailure" -> "TEMPORARY_FAILURE";
            default -> v1Status;
        };
    }

    private List<String> jsonArrayToList(JsonNode arrayNode) {
        if (arrayNode == null || arrayNode.isMissingNode() || !arrayNode.isArray()) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        arrayNode.forEach(node -> list.add(node.asText()));
        return list;
    }

    private List<String> mergeLists(List<String> to, List<String> cc, List<String> bcc) {
        List<String> all = new ArrayList<>(to);
        all.addAll(cc);
        all.addAll(bcc);
        return all;
    }

    private EmailTemplate parseTemplateContent(String templateName, JsonNode content) {
        String subject = content.path("Subject").asText(null);
        String text = content.path("Text").asText(null);
        String html = content.path("Html").asText(null);
        return new EmailTemplate(templateName, subject, text, html);
    }

    private ObjectNode buildTemplateResponse(EmailTemplate template) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("TemplateName", template.getTemplateName());
        ObjectNode content = result.putObject("TemplateContent");
        if (template.getSubject() != null) {
            content.put("Subject", template.getSubject());
        }
        if (template.getTextPart() != null) {
            content.put("Text", template.getTextPart());
        }
        if (template.getHtmlPart() != null) {
            content.put("Html", template.getHtmlPart());
        }
        ArrayNode tags = result.putArray("Tags");
        for (Tag t : template.getTags()) {
            ObjectNode tagNode = objectMapper.createObjectNode();
            tagNode.put("Key", t.key());
            tagNode.put("Value", t.value());
            tags.add(tagNode);
        }
        return result;
    }

    private JsonNode parseTemplateData(JsonNode parent, String fieldName) {
        if (parent == null || parent.isMissingNode() || parent.isNull()) {
            return objectMapper.createObjectNode();
        }
        if (!parent.isObject()) {
            throw new AwsException("BadRequestException",
                    "Parent of " + fieldName + " must be a JSON object.", 400);
        }
        JsonNode field = parent.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return objectMapper.createObjectNode();
        }
        if (!field.isTextual()) {
            throw new AwsException("BadRequestException",
                    fieldName + " must be a JSON-encoded string.", 400);
        }
        return parseTemplateData(field.asText(""));
    }

    private JsonNode parseTemplateData(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new AwsException("BadRequestException",
                    "Invalid TemplateData JSON: " + e.getMessage(), 400);
        }
        if (!node.isObject()) {
            throw new AwsException("BadRequestException",
                    "TemplateData must be a JSON object.", 400);
        }
        return node;
    }

    private static void requireJsonObject(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new AwsException("BadRequestException",
                    "Request body must be a JSON object.", 400);
        }
    }

    private static JsonNode requireObjectOrAbsent(JsonNode parent, String fieldName) {
        JsonNode child = parent.path(fieldName);
        if (!child.isMissingNode() && !child.isNull() && !child.isObject()) {
            throw new AwsException("BadRequestException",
                    fieldName + " must be a JSON object.", 400);
        }
        return child;
    }

    /**
     * Parse a JSON {@code Tags} array node into a list of tag records. Returns {@code null}
     * when the node is missing or null so callers can decide whether that is an error
     * (TagResource) or a no-op (CreateConfigurationSet / CreateEmailTemplate). Throws
     * {@code BadRequestException} when the node is present but not an array.
     */
    private List<Tag> parseTagsArray(JsonNode tagsNode) {
        if (tagsNode.isMissingNode() || tagsNode.isNull()) {
            return null;
        }
        if (!tagsNode.isArray()) {
            throw new AwsException("BadRequestException", "Tags must be an array.", 400);
        }
        List<Tag> out = new ArrayList<>();
        for (JsonNode t : tagsNode) {
            out.add(new Tag(
                    t.path("Key").asText(null),
                    t.path("Value").asText(null)));
        }
        return out;
    }

    /**
     * Parses a {@code SuppressedReasons} JSON array into a list, validating
     * structure only; reason values are validated by the service layer.
     * Structural violations reproduce the AWS deserialization-layer errors
     * (verified against real AWS SES V2 on 2026-06-13): a non-array node and
     * non-string scalar / container elements fail with
     * {@code SerializationException}, while {@code null} elements pass
     * deserialization and are rejected by the service-layer value validation,
     * exactly as AWS does. Missing / null yields an empty list for the PUT
     * path, which AWS treats as an explicit empty override.
     */
    private static List<String> parseSuppressedReasons(JsonNode reasonsNode) {
        List<String> reasons = new ArrayList<>();
        if (!reasonsNode.isMissingNode() && !reasonsNode.isNull()) {
            if (!reasonsNode.isArray()) {
                throw new AwsException("SerializationException", "Expected list or null", 400);
            }
            for (JsonNode r : reasonsNode) {
                if (r.isTextual() || r.isNull()) {
                    reasons.add(r.asText(null));
                } else if (r.isNumber()) {
                    throw new AwsException("SerializationException",
                            "NUMBER_VALUE can not be converted to a String", 400);
                } else if (r.isBoolean()) {
                    throw new AwsException("SerializationException",
                            (r.booleanValue() ? "TRUE_VALUE" : "FALSE_VALUE")
                                    + " can not be converted to a String", 400);
                } else {
                    throw unexpectedStartError(r);
                }
            }
        }
        return reasons;
    }

    /**
     * Reproduces the AWS deserialization behavior for {@code SendingEnabled}
     * (verified against real AWS SES V2 on 2026-06-13): a missing member
     * defaults to {@code false}, any string coerces to {@code true}, and
     * explicit {@code null} or non-boolean scalars fail with
     * {@code SerializationException}.
     */
    private static boolean parseSendingEnabled(JsonNode enabledNode) {
        if (enabledNode.isMissingNode()) {
            return false;
        }
        return coerceBoolean(enabledNode);
    }

    // AWS-verified Jackson coercion for a SES v2 boolean field: a JSON string coerces to true,
    // while a number/null/array/object is a SerializationException.
    private static boolean coerceBoolean(JsonNode node) {
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return true;
        }
        if (node.isNull()) {
            throw new AwsException("SerializationException", null, 400);
        }
        if (node.isNumber()) {
            throw new AwsException("SerializationException",
                    "NUMBER_VALUE can not be converted to a Boolean", 400);
        }
        throw unexpectedStartError(node);
    }

    private static AwsException unexpectedStartError(JsonNode node) {
        if (node.isArray()) {
            return new AwsException("SerializationException",
                    "Start of list found where not expected", 400);
        }
        return new AwsException("SerializationException",
                "Start of structure or map found where not expected.", 400);
    }

    /**
     * Parse a V2 SES {@code Content.Simple.Headers} / {@code Content.Template.Headers} array
     * (additional message headers, elements use {@code Name}/{@code Value}). Returns an empty
     * list when the node is absent so callers can pass it through unconditionally. Both members
     * are required: an entry that omits {@code Name} or {@code Value} is rejected the way AWS
     * does, with a Smithy constraint message anchored at {@code location} (e.g.
     * {@code content.simple.headers}) and the offending 1-based index.
     */
    private List<MessageHeader> parseHeadersArray(JsonNode headersNode, String location) {
        if (headersNode.isMissingNode() || headersNode.isNull()) {
            return List.of();
        }
        if (!headersNode.isArray()) {
            throw new AwsException("BadRequestException", "Headers must be an array.", 400);
        }
        List<MessageHeader> out = new ArrayList<>();
        int index = 1;
        for (JsonNode h : headersNode) {
            if (!h.isObject()) {
                throw new AwsException("BadRequestException",
                        "Headers entries must be JSON objects.", 400);
            }
            JsonNode nameNode = h.get("Name");
            JsonNode valueNode = h.get("Value");
            if (nameNode == null || nameNode.isNull()) {
                throw missingHeaderMember(location, index, "name");
            }
            if (valueNode == null || valueNode.isNull()) {
                throw missingHeaderMember(location, index, "value");
            }
            String name = nameNode.asText();
            if (name.isBlank()) {
                throw new AwsException("BadRequestException",
                        "The header name must be specified.", 400);
            }
            out.add(new MessageHeader(name, valueNode.asText()));
            index++;
        }
        return out;
    }

    private AwsException missingHeaderMember(String location, int index, String member) {
        return new AwsException("BadRequestException",
                "1 validation error detected: Value at '" + location + "." + index + ".member." + member
                        + "' failed to satisfy constraint: Member must not be null", 400);
    }

    /**
     * Parse a V2 SES {@code EmailTags} / {@code DefaultEmailTags} / {@code ReplacementTags}
     * array (per-message {@link MessageTag} list whose elements use {@code Name}/{@code Value},
     * distinct from the resource-tag {@link Tag} {@code Key}/{@code Value} shape). Note that
     * the per-entry name is {@code ReplacementTags} on the wire — only the top-level field
     * carries the {@code EmailTags} suffix. Returns an empty list when the node is absent so
     * callers can pass it through unconditionally.
     * The {@code fieldName} parameter is reported in the error message when the node is
     * present but not an array.
     */
    private static ListManagementOptions parseListManagementOptions(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new AwsException("BadRequestException", "ListManagementOptions must be an object.", 400);
        }
        JsonNode listNode = node.path("ContactListName");
        if (!listNode.isTextual() || listNode.textValue().isBlank()) {
            throw new AwsException("BadRequestException",
                    "ListManagementOptions.ContactListName is required.", 400);
        }
        JsonNode topicNode = node.path("TopicName");
        String topicName = null;
        if (!topicNode.isMissingNode() && !topicNode.isNull()) {
            if (!topicNode.isTextual()) {
                throw new AwsException("BadRequestException",
                        "ListManagementOptions.TopicName must be a string.", 400);
            }
            topicName = topicNode.textValue();
        }
        return new ListManagementOptions(listNode.textValue(), topicName);
    }

    private List<MessageTag> parseEmailTagsArray(JsonNode tagsNode, String fieldName) {
        if (tagsNode.isMissingNode() || tagsNode.isNull()) {
            return List.of();
        }
        if (!tagsNode.isArray()) {
            throw new AwsException("BadRequestException", fieldName + " must be an array.", 400);
        }
        List<MessageTag> out = new ArrayList<>();
        for (JsonNode t : tagsNode) {
            if (!t.isObject()) {
                throw new AwsException("BadRequestException",
                        fieldName + " entries must be JSON objects.", 400);
            }
            String name = t.path("Name").asText(null);
            String value = t.path("Value").asText(null);
            if (name == null || name.isBlank()) {
                throw new AwsException("BadRequestException",
                        "The tag name must be specified.", 400);
            }
            out.add(new MessageTag(name, value));
        }
        return out;
    }

    private static AwsException remapV1Exception(AwsException e) {
        return switch (e.getErrorCode()) {
            case "InvalidParameterValue", "InvalidTemplate", "ValidationError",
                 "InvalidRenderingParameter", "MissingRenderingAttribute" ->
                    new AwsException("BadRequestException", e.getMessage(), 400);
            case "TemplateDoesNotExist", "ConfigurationSetDoesNotExist",
                 "CustomVerificationEmailTemplateDoesNotExist", "FromEmailAddressNotVerified" ->
                    new AwsException("NotFoundException", e.getMessage(), 404);
            case "AlreadyExists", "ConfigurationSetAlreadyExists",
                 "CustomVerificationEmailTemplateAlreadyExists" ->
                    new AwsException("AlreadyExistsException", e.getMessage(), 400);
            case "ConfigurationSetSendingPausedException" ->
                    new AwsException("SendingPausedException", e.getMessage(), 400);
            default -> e;
        };
    }
}
