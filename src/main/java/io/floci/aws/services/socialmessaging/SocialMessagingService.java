package io.floci.aws.services.socialmessaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AWS End User Messaging Social (WhatsApp). Meta's side is simulated: a signup access token stands for a WhatsApp
 * Business Account with one phone number, which the two-step association links to the AWS account.
 *
 * <p>Message templates are checked as Meta checks them and reviewed (marketing templates stay {@code PENDING} for a
 * while). Media is read from and written to S3. A message must come from a phone number of a linked account, name an
 * approved template or existing media where it uses them, and is announced to the account's SNS event destinations
 * as a delivery status. Flows have Meta's lifecycle: drafts can change, published flows can only be deprecated, and
 * a flow with validation errors can't be published.
 */
@ApplicationScoped
public class SocialMessagingService implements Resettable {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> TEMPLATE_CATEGORIES = Set.of("MARKETING", "UTILITY", "AUTHENTICATION");
    private static final Set<String> MESSAGE_TYPES = Set.of("text", "template", "image", "document", "audio",
            "video", "sticker", "location", "contacts", "interactive", "reaction");
    private static final List<String[]> LIBRARY = List.of(
            new String[]{"verify_code", "AUTHENTICATION", "Your verification code is {{1}}."},
            new String[]{"order_shipped", "UTILITY", "Your order {{1}} has shipped and will arrive {{2}}."},
            new String[]{"appointment_reminder", "UTILITY", "Reminder: your appointment is on {{1}} at {{2}}."},
            new String[]{"payment_received", "UTILITY", "We received your payment of {{1}}. Thank you."},
            new String[]{"account_update", "UTILITY", "Your account details were updated on {{1}}."},
            new String[]{"delivery_failed", "UTILITY", "We couldn't deliver order {{1}}. Reply to reschedule."});

    private final AccountAwareStorageBackend<ObjectNode> accounts;
    private final AccountAwareStorageBackend<ObjectNode> signups;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final Provider<S3Service> s3;
    private final Provider<SnsService> sns;

    @Inject
    public SocialMessagingService(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config,
                                  Provider<S3Service> s3, Provider<SnsService> sns) {
        this.accounts = storageFactory.create("social-messaging", "accounts.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.signups = storageFactory.create("social-messaging", "signups.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
        this.s3 = s3;
        this.sns = sns;
    }

    @Override
    public void clear() {
        accounts.clear();
        signups.clear();
    }

    public ObjectNode handle(String op, JsonNode in, String region) {
        return switch (op) {
            case "AssociateWhatsAppBusinessAccount" -> associate(in, region);
            case "GetLinkedWhatsAppBusinessAccount" ->
                    wrap("account", accountView(account(region, required(in, "id")), true));
            case "ListLinkedWhatsAppBusinessAccounts" -> listAccounts(region);
            case "DisassociateWhatsAppBusinessAccount" -> disassociate(in, region);
            case "PutWhatsAppBusinessAccountEventDestinations" -> putDestinations(in, region);
            case "GetLinkedWhatsAppBusinessAccountPhoneNumber" -> getPhone(in, region);
            case "UpdateLinkedWhatsAppBusinessAccountPhoneNumber" -> updatePhone(in, region);
            case "CreateWhatsAppMessageTemplate" -> createTemplate(in, region);
            case "CreateWhatsAppMessageTemplateFromLibrary" -> createFromLibrary(in, region);
            case "CreateWhatsAppMessageTemplateMedia" -> templateMedia(in, region);
            case "GetWhatsAppMessageTemplate" -> getTemplate(in, region);
            case "ListWhatsAppMessageTemplates" -> listTemplates(in, region);
            case "UpdateWhatsAppMessageTemplate" -> updateTemplate(in, region);
            case "DeleteWhatsAppMessageTemplate" -> deleteTemplate(in, region);
            case "ListWhatsAppTemplateLibrary" -> library(in);
            case "PostWhatsAppMessageMedia" -> postMedia(in, region);
            case "GetWhatsAppMessageMedia" -> getMedia(in, region);
            case "DeleteWhatsAppMessageMedia" -> deleteMedia(in, region);
            case "SendWhatsAppMessage" -> send(in, region);
            case "CreateWhatsAppFlow" -> createFlow(in, region);
            case "GetWhatsAppFlow" -> getFlow(in, region);
            case "ListWhatsAppFlows" -> listFlows(in, region);
            case "UpdateWhatsAppFlow" -> updateFlow(in, region);
            case "UpdateWhatsAppFlowAssets" -> updateFlowAssets(in, region);
            case "PublishWhatsAppFlow" -> flowStatus(in, region, "PUBLISHED");
            case "DeprecateWhatsAppFlow" -> flowStatus(in, region, "DEPRECATED");
            case "DeleteWhatsAppFlow" -> deleteFlow(in, region);
            case "GetWhatsAppFlowPreview" -> flowPreview(in, region);
            case "ListWhatsAppFlowAssets" -> flowAssets(in, region);
            case "PutWhatsAppBusinessPublicKey" -> putPublicKey(in, region);
            case "GetWhatsAppBusinessPublicKey" -> getPublicKey(in, region);
            case "GetWhatsAppCallPermission" -> callPermission(in, region);
            case "SendWhatsAppCallEvent" -> callEvent(in, region);
            case "CreateWhatsAppDataset" -> createDataset(in, region);
            case "SendWhatsAppConversionEvent" -> conversionEvent(in, region);
            case "TagResource" -> tag(in, region, true);
            case "UntagResource" -> tag(in, region, false);
            case "ListTagsForResource" -> listTags(in, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + op + ".", 404);
        };
    }

    // ---------- association (with Meta's side simulated) ----------

    private ObjectNode associate(JsonNode in, String region) {
        if (in.has("signupCallback")) {
            String token = in.path("signupCallback").path("accessToken").asText("");
            if (token.isBlank()) {
                throw invalid("signupCallback.accessToken is required.");
            }
            String digest = sha(token);
            String metaWaba = digits(digest, 0, 15);
            for (ObjectNode linked : accounts.scan(k -> k.startsWith(region + "/"))) {
                if (metaWaba.equals(linked.path("wabaId").asText())) {
                    throw new AwsException("ConflictException", "The WhatsApp Business Account " + metaWaba
                            + " is already linked.", 409);
                }
            }
            String id = "waba-" + digest.substring(0, 32);
            String inProgress = UUID.randomUUID().toString();
            ObjectNode phone = mapper.createObjectNode();
            String phoneId = "phone-number-id-" + digest.substring(32, 64);
            String number = "+1206555" + digits(digest, 15, 4);
            phone.put("arn", arn(region, "phone-number-id/" + phoneId)).put("phoneNumber", number)
                    .put("phoneNumberId", phoneId).put("metaPhoneNumberId", digits(digest, 20, 15))
                    .put("displayPhoneNumberName", "Floci " + digest.substring(0, 6))
                    .put("displayPhoneNumber", number).put("qualityRating", "GREEN");
            ObjectNode pending = mapper.createObjectNode().put("id", id).put("wabaId", metaWaba)
                    .put("accountName", "Floci Business " + digest.substring(0, 6));
            pending.putArray("phones").add(phone);
            signups.put(inProgress, pending);
            ObjectNode result = mapper.createObjectNode().put("statusCode", 200);
            ObjectNode callback = result.putObject("signupCallbackResult").put("associateInProgressToken", inProgress);
            ObjectNode incomplete = callback.putObject("linkedAccountsWithIncompleteSetup").putObject(id)
                    .put("accountName", pending.path("accountName").asText()).put("registrationStatus", "INCOMPLETE")
                    .put("wabaId", metaWaba);
            incomplete.putArray("unregisteredWhatsAppPhoneNumbers").add(phone.deepCopy());
            return result;
        }
        JsonNode finish = in.path("setupFinalization");
        String token = finish.path("associateInProgressToken").asText("");
        ObjectNode pending = signups.get(token).map(ObjectNode::deepCopy)
                .orElseThrow(() -> invalid("The associateInProgressToken isn't valid or has expired."));
        String id = pending.path("id").asText();
        if (finish.path("waba").hasNonNull("id") && !id.equals(finish.path("waba").path("id").asText())) {
            throw invalid("waba.id " + finish.path("waba").path("id").asText() + " isn't the account being set up.");
        }
        ArrayNode registered = mapper.createArrayNode();
        for (JsonNode requested : finish.path("phoneNumbers")) {
            JsonNode phone = null;
            for (JsonNode candidate : pending.path("phones")) {
                if (candidate.path("phoneNumberId").asText().equals(requested.path("id").asText())) {
                    phone = candidate;
                }
            }
            if (phone == null) {
                throw invalid("Phone number " + requested.path("id").asText() + " isn't part of this signup.");
            }
            if (!requested.path("twoFactorPin").asText().matches("\\d{6}")) {
                throw invalid("twoFactorPin must be six digits.");
            }
            ObjectNode copy = ((ObjectNode) phone).deepCopy();
            if (requested.hasNonNull("dataLocalizationRegion")) {
                copy.put("dataLocalizationRegion", requested.get("dataLocalizationRegion").asText());
            }
            registered.add(copy);
        }
        if (registered.isEmpty()) {
            throw invalid("Register at least one phone number.");
        }
        ArrayNode destinations = destinations(finish.path("waba").path("eventDestinations"), region);
        ObjectNode account = mapper.createObjectNode();
        account.put("arn", arn(region, "waba/" + id)).put("id", id).put("wabaId", pending.path("wabaId").asText())
                .put("registrationStatus", "COMPLETE").put("linkDate", Instant.now().getEpochSecond())
                .put("wabaName", pending.path("accountName").asText());
        account.set("eventDestinations", destinations);
        account.set("phoneNumbers", registered);
        ObjectNode meta = account.putObject("_");
        meta.set("tags", tags(finish.path("waba").path("tags")));
        for (String store : List.of("templates", "flows", "media", "datasets", "keys", "calls")) {
            meta.putObject(store);
        }
        accounts.put(key(region, id), account);
        signups.delete(token);
        return mapper.createObjectNode().put("statusCode", 200).put("linkedWhatsAppBusinessAccountId", id);
    }

    private ArrayNode destinations(JsonNode requested, String region) {
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode destination : requested) {
            String arn = destination.path("eventDestinationArn").asText();
            if (AwsArnUtils.isArnFor(arn, "sns")) {
                if (!sns.get().topicExists(arn, AwsArnUtils.parse(arn).region())) {
                    throw invalid("The SNS topic " + arn + " doesn't exist.");
                }
            } else if (!AwsArnUtils.isArnFor(arn, "connect")) {
                throw invalid("An event destination is an SNS topic or an Amazon Connect instance.");
            }
            out.add(destination.deepCopy());
        }
        return out;
    }

    private ObjectNode account(String region, String id) {
        return accounts.get(key(region, decode(id))).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("The linked WhatsApp Business Account " + id + " doesn't exist."));
    }

    /** Get returns the registered phone numbers; the List summaries leave them out. */
    private ObjectNode accountView(ObjectNode account, boolean withPhones) {
        ObjectNode view = account.deepCopy();
        view.remove("_");
        if (!withPhones) {
            view.remove("phoneNumbers");
        }
        return view;
    }

    private void save(String region, ObjectNode account) {
        accounts.put(key(region, account.path("id").asText()), account);
    }

    private ObjectNode listAccounts(String region) {
        ArrayNode out = mapper.createArrayNode();
        accounts.scan(k -> k.startsWith(region + "/")).forEach(a -> out.add(accountView(a, false)));
        return wrap("linkedAccounts", out);
    }

    private ObjectNode disassociate(JsonNode in, String region) {
        ObjectNode account = account(region, required(in, "id"));
        accounts.delete(key(region, account.path("id").asText()));
        return mapper.createObjectNode();
    }

    private ObjectNode putDestinations(JsonNode in, String region) {
        ObjectNode account = account(region, required(in, "id"));
        account.set("eventDestinations", destinations(in.path("eventDestinations"), region));
        save(region, account);
        return mapper.createObjectNode();
    }

    // ---------- phone numbers ----------

    /** The linked account and phone number with this phone number id. */
    private record Phone(ObjectNode account, ObjectNode phone) {
    }

    private Phone phone(String region, String id) {
        String phoneId = decode(id);
        if (AwsArnUtils.isArn(phoneId)) {
            phoneId = phoneId.substring(phoneId.lastIndexOf('/') + 1);
        }
        for (ObjectNode account : accounts.scan(k -> k.startsWith(region + "/"))) {
            for (JsonNode phone : account.path("phoneNumbers")) {
                if (phoneId.equals(phone.path("phoneNumberId").asText())) {
                    return new Phone(account.deepCopy(), ((ObjectNode) phone).deepCopy());
                }
            }
        }
        throw notFound("The phone number " + id + " isn't part of a linked WhatsApp Business Account.");
    }

    private ObjectNode getPhone(JsonNode in, String region) {
        Phone phone = phone(region, required(in, "id"));
        ObjectNode out = mapper.createObjectNode();
        ObjectNode detail = phone.phone().deepCopy();
        detail.remove("callSettings");
        out.set("phoneNumber", detail);
        out.put("linkedWhatsAppBusinessAccountId", phone.account().path("id").asText());
        if (phone.phone().has("callSettings")) {
            out.set("callSettings", phone.phone().get("callSettings").deepCopy());
        }
        return out;
    }

    private ObjectNode updatePhone(JsonNode in, String region) {
        Phone phone = phone(region, required(in, "id"));
        JsonNode settings = in.path("callSettings");
        for (JsonNode entry : settings.path("callHours").path("weeklyOperatingHours")) {
            int open = entry.path("openTime").path("hours").asInt() * 60 + entry.path("openTime").path("minutes")
                    .asInt();
            int close = entry.path("closeTime").path("hours").asInt() * 60 + entry.path("closeTime")
                    .path("minutes").asInt();
            if (close <= open) {
                throw invalid("A closing time must come after its opening time.");
            }
        }
        for (JsonNode phoneNode : phone.account().withArray("/phoneNumbers")) {
            if (phoneNode.path("phoneNumberId").equals(phone.phone().path("phoneNumberId"))) {
                ((ObjectNode) phoneNode).set("callSettings", settings.deepCopy());
            }
        }
        save(region, phone.account());
        return mapper.createObjectNode().put("phoneNumberId", phone.phone().path("phoneNumberId").asText());
    }

    // ---------- templates ----------

    private ObjectNode meta(ObjectNode account, String store) {
        return account.withObject("/_/" + store);
    }

    private ObjectNode createTemplate(JsonNode in, String region) {
        ObjectNode account = account(region, required(in, "id"));
        JsonNode definition = json(in.path("templateDefinition").asText(), "templateDefinition");
        String name = definition.path("name").asText();
        String language = definition.path("language").asText("en_US");
        String category = definition.path("category").asText();
        if (!name.matches("[a-z0-9_]{1,512}")) {
            throw invalid("A template name is lowercase letters, digits and underscores.");
        }
        if (!TEMPLATE_CATEGORIES.contains(category)) {
            throw invalid("category must be MARKETING, UTILITY or AUTHENTICATION.");
        }
        boolean body = false;
        for (JsonNode component : definition.path("components")) {
            body |= "BODY".equalsIgnoreCase(component.path("type").asText())
                    && (component.hasNonNull("text") || "AUTHENTICATION".equals(category));
        }
        if (!body) {
            throw invalid("A template needs a BODY component with text.");
        }
        return storeTemplate(region, account, name, language, category, definition, "API");
    }

    private ObjectNode storeTemplate(String region, ObjectNode account, String name, String language,
                                     String category, JsonNode definition, String source) {
        ObjectNode templates = meta(account, "templates");
        for (JsonNode existing : templates) {
            if (name.equals(existing.path("templateName").asText())
                    && language.equals(existing.path("templateLanguage").asText())) {
                throw new AwsException("ConflictException", "A template named " + name + " in " + language
                        + " already exists.", 409);
            }
        }
        String id = digits(sha(UUID.randomUUID().toString()), 0, 16);
        double reviewDone = now() + ("MARKETING".equals(category) ? config.services().socialmessaging()
                .reviewMillis() / 1000.0 : 0);
        ObjectNode template = templates.putObject(id).put("metaTemplateId", id).put("templateName", name)
                .put("templateLanguage", language).put("templateCategory", category).put("templateSource", source)
                .put("templateQualityScore", "UNKNOWN").put("_review", reviewDone);
        template.set("definition", definition.deepCopy());
        save(region, account);
        return mapper.createObjectNode().put("metaTemplateId", id).put("templateStatus", status(template))
                .put("category", category);
    }

    private static String status(JsonNode template) {
        if (template.hasNonNull("_status")) {
            return template.get("_status").asText();
        }
        return now() >= template.path("_review").asDouble() ? "APPROVED" : "PENDING";
    }

    private ObjectNode createFromLibrary(JsonNode in, String region) {
        ObjectNode account = account(region, required(in, "id"));
        JsonNode requested = in.path("metaLibraryTemplate");
        String[] entry = LIBRARY.stream().filter(t -> t[0].equals(requested.path("libraryTemplateName").asText()))
                .findFirst().orElseThrow(() -> notFound("The library template "
                        + requested.path("libraryTemplateName").asText() + " doesn't exist."));
        ObjectNode definition = mapper.createObjectNode().put("name", requested.path("templateName").asText())
                .put("language", requested.path("templateLanguage").asText()).put("category", entry[1]);
        definition.putArray("components").addObject().put("type", "BODY").put("text", entry[2]);
        if (!requested.path("templateName").asText().matches("[a-z0-9_]{1,512}")) {
            throw invalid("A template name is lowercase letters, digits and underscores.");
        }
        return storeTemplate(region, account, requested.path("templateName").asText(),
                requested.path("templateLanguage").asText(), entry[1], definition, "LIBRARY");
    }

    private ObjectNode templateMedia(JsonNode in, String region) {
        account(region, required(in, "id"));
        byte[] bytes = readS3(in.path("sourceS3File"));
        return mapper.createObjectNode().put("metaHeaderHandle", "4::" + Base64.getEncoder()
                .encodeToString(sha(Base64.getEncoder().encodeToString(bytes)).substring(0, 24)
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private ObjectNode template(ObjectNode account, JsonNode in) {
        ObjectNode templates = meta(account, "templates");
        String id = in.path("metaTemplateId").asText(null);
        if (id != null && templates.has(id)) {
            return (ObjectNode) templates.get(id);
        }
        String name = in.path("templateName").asText(null);
        String language = in.path("templateLanguageCode").asText(null);
        for (JsonNode template : templates) {
            if (name != null && name.equals(template.path("templateName").asText())
                    && (language == null || language.equals(template.path("templateLanguage").asText()))) {
                return (ObjectNode) template;
            }
        }
        throw notFound("The template " + (id != null ? id : name) + " doesn't exist.");
    }

    private ObjectNode templateSummary(JsonNode template) {
        ObjectNode summary = ((ObjectNode) template).deepCopy();
        summary.remove("definition");
        summary.remove("_review");
        summary.remove("_status");
        summary.put("templateStatus", status(template));
        return summary;
    }

    private ObjectNode getTemplate(JsonNode in, String region) {
        ObjectNode template = template(account(region, required(in, "id")), in);
        ObjectNode document = template.path("definition").deepCopy();
        document.put("id", template.path("metaTemplateId").asText()).put("status", status(template));
        return mapper.createObjectNode().put("template", document.toString());
    }

    private ObjectNode listTemplates(JsonNode in, String region) {
        ArrayNode out = mapper.createArrayNode();
        meta(account(region, required(in, "id")), "templates").forEach(t -> out.add(templateSummary(t)));
        return wrap("templates", out);
    }

    private ObjectNode updateTemplate(JsonNode in, String region) {
        ObjectNode account = account(region, required(in, "id"));
        ObjectNode template = template(account, in);
        if ("PENDING".equals(status(template))) {
            throw invalid("A template can't be edited while it's in review.");
        }
        ObjectNode definition = (ObjectNode) template.get("definition");
        if (in.hasNonNull("templateCategory")) {
            if (!TEMPLATE_CATEGORIES.contains(in.get("templateCategory").asText())) {
                throw invalid("templateCategory must be MARKETING, UTILITY or AUTHENTICATION.");
            }
            definition.put("category", in.get("templateCategory").asText());
            template.put("templateCategory", in.get("templateCategory").asText());
        }
        if (in.hasNonNull("templateComponents")) {
            definition.set("components", json(in.get("templateComponents").asText(), "templateComponents"));
        }
        if (in.hasNonNull("parameterFormat")) {
            definition.put("parameter_format", in.get("parameterFormat").asText());
        }
        template.put("_review", now() + ("MARKETING".equals(template.path("templateCategory").asText())
                ? config.services().socialmessaging().reviewMillis() / 1000.0 : 0));
        save(region, account);
        return mapper.createObjectNode();
    }

    private ObjectNode deleteTemplate(JsonNode in, String region) {
        ObjectNode account = account(region, required(in, "id"));
        String name = required(in, "templateName");
        ObjectNode templates = meta(account, "templates");
        List<String> remove = new ArrayList<>();
        templates.fields().forEachRemaining(entry -> {
            boolean sameName = name.equals(entry.getValue().path("templateName").asText());
            boolean sameId = !in.hasNonNull("metaTemplateId") || in.get("metaTemplateId").asText()
                    .equals(entry.getKey()) || in.path("deleteAllLanguages").asBoolean(false);
            if (sameName && sameId) {
                remove.add(entry.getKey());
            }
        });
        if (remove.isEmpty()) {
            throw notFound("The template " + name + " doesn't exist.");
        }
        remove.forEach(templates::remove);
        save(region, account);
        return mapper.createObjectNode();
    }

    private ObjectNode library(JsonNode in) {
        ArrayNode out = mapper.createArrayNode();
        String category = in.path("filters").path("templateCategory").asText(null);
        int max = in.path("maxResults").asInt(LIBRARY.size());
        for (String[] entry : LIBRARY) {
            if ((category == null || category.equals(entry[1])) && out.size() < max) {
                out.addObject().put("templateName", entry[0]).put("templateLanguage", "en_US")
                        .put("templateCategory", entry[1]).put("templateBody", entry[2]).put("templateId", entry[0]);
            }
        }
        return wrap("metaLibraryTemplates", out);
    }

    // ---------- media ----------

    private byte[] readS3(JsonNode file) {
        String bucket = file.path("bucketName").asText();
        String key = file.path("key").asText();
        try (S3Service.ObjectRead read = s3.get().openObject(bucket, key, null)) {
            return read.body().readAllBytes();
        } catch (AwsException e) {
            throw invalid("The S3 object s3://" + bucket + "/" + key + " doesn't exist.");
        } catch (IOException e) {
            throw new AwsException("InternalServiceException", "The S3 object couldn't be read.", 500);
        }
    }

    private ObjectNode postMedia(JsonNode in, String region) {
        Phone phone = phone(region, required(in, "originationPhoneNumberId"));
        if (!in.has("sourceS3File")) {
            throw invalid("sourceS3File is required; Floci doesn't fetch presigned URLs.");
        }
        byte[] bytes = readS3(in.path("sourceS3File"));
        if (bytes.length > 100L * 1024 * 1024) {
            throw invalid("Media is at most 100 MB.");
        }
        String key = in.path("sourceS3File").path("key").asText();
        String id = digits(sha(UUID.randomUUID().toString()), 0, 16);
        meta(phone.account(), "media").putObject(id).put("mimeType", mimeType(key)).put("fileSize", bytes.length)
                .put("phoneNumberId", phone.phone().path("phoneNumberId").asText())
                .put("data", Base64.getEncoder().encodeToString(bytes));
        save(region, phone.account());
        return mapper.createObjectNode().put("mediaId", id);
    }

    private static String mimeType(String key) {
        String lower = key.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".ogg")) {
            return "audio/ogg";
        }
        return "application/octet-stream";
    }

    private ObjectNode media(Phone phone, String mediaId) {
        JsonNode media = meta(phone.account(), "media").get(mediaId);
        if (media == null) {
            throw notFound("The media " + mediaId + " doesn't exist.");
        }
        return (ObjectNode) media;
    }

    private ObjectNode getMedia(JsonNode in, String region) {
        Phone phone = phone(region, required(in, "originationPhoneNumberId"));
        ObjectNode media = media(phone, required(in, "mediaId"));
        if (!in.path("metadataOnly").asBoolean(false) && in.has("destinationS3File")) {
            JsonNode file = in.get("destinationS3File");
            s3.get().putObject(file.path("bucketName").asText(), file.path("key").asText(),
                    Base64.getDecoder().decode(media.path("data").asText()), media.path("mimeType").asText(),
                    Map.of());
        }
        return mapper.createObjectNode().put("mimeType", media.path("mimeType").asText())
                .put("fileSize", media.path("fileSize").asLong());
    }

    private ObjectNode deleteMedia(JsonNode in, String region) {
        Phone phone = phone(region, required(in, "originationPhoneNumberId"));
        media(phone, required(in, "mediaId"));
        meta(phone.account(), "media").remove(in.path("mediaId").asText());
        save(region, phone.account());
        return mapper.createObjectNode().put("success", true);
    }

    // ---------- messages ----------

    private ObjectNode send(JsonNode in, String region) {
        Phone phone = phone(region, required(in, "originationPhoneNumberId"));
        if (!in.path("metaApiVersion").asText().matches("v\\d+\\.\\d+")) {
            throw invalid("metaApiVersion must look like v20.0.");
        }
        JsonNode message = json(in.path("message").asText(), "message");
        if (!"whatsapp".equals(message.path("messaging_product").asText())) {
            throw invalid("messaging_product must be whatsapp.");
        }
        String to = message.path("to").asText().replace("+", "");
        if (!to.matches("\\d{7,15}")) {
            throw invalid("to must be a phone number.");
        }
        String type = message.path("type").asText("text");
        if (!MESSAGE_TYPES.contains(type)) {
            throw invalid("Message type " + type + " isn't supported.");
        }
        if ("template".equals(type)) {
            ObjectNode request = mapper.createObjectNode()
                    .put("templateName", message.path("template").path("name").asText())
                    .put("templateLanguageCode", message.path("template").path("language").path("code").asText());
            ObjectNode template = template(phone.account(), request);
            if (!"APPROVED".equals(status(template))) {
                throw invalid("The template " + template.path("templateName").asText() + " isn't approved yet.");
            }
        }
        JsonNode mediaId = message.path(type).path("id");
        if (Set.of("image", "document", "audio", "video", "sticker").contains(type) && !mediaId.isMissingNode()) {
            media(phone, mediaId.asText());
        }
        String id = "wamid." + Base64.getEncoder().encodeToString(UUID.randomUUID().toString()
                .getBytes(StandardCharsets.UTF_8));
        announce(phone, id, to, region);
        return mapper.createObjectNode().put("messageId", id);
    }

    /** Publishes the message's delivery status to the account's SNS event destinations, as WhatsApp webhooks. */
    private void announce(Phone phone, String messageId, String to, String region) {
        long now = Instant.now().getEpochSecond();
        ObjectNode entry = mapper.createObjectNode().put("id", phone.account().path("wabaId").asText());
        ObjectNode value = entry.putArray("changes").addObject().put("field", "messages").putObject("value")
                .put("messaging_product", "whatsapp");
        value.putObject("metadata").put("display_phone_number", phone.phone().path("displayPhoneNumber").asText())
                .put("phone_number_id", phone.phone().path("metaPhoneNumberId").asText());
        value.putArray("statuses").addObject().put("id", messageId).put("status", "sent")
                .put("timestamp", String.valueOf(now)).put("recipient_id", to);
        ObjectNode event = mapper.createObjectNode().put("aws_account_id", accounts.accountId())
                .put("message_timestamp", Instant.now().toString()).put("whatsAppWebhookEntry", entry.toString());
        event.putObject("context").set("MetaWabaIds", mapper.createArrayNode().addObject()
                .put("wabaId", phone.account().path("wabaId").asText())
                .put("arn", phone.account().path("arn").asText()));
        for (JsonNode destination : phone.account().path("eventDestinations")) {
            String arn = destination.path("eventDestinationArn").asText();
            if (AwsArnUtils.isArnFor(arn, "sns")) {
                try {
                    sns.get().publish(arn, null, event.toString(), null, AwsArnUtils.parse(arn).region());
                } catch (AwsException e) {
                    // The destination went away; delivery is best effort, as on AWS.
                }
            }
        }
    }

    // ---------- flows ----------

    private ObjectNode createFlow(JsonNode in, String region) {
        ObjectNode account = account(region, required(in, "id"));
        if (in.path("categories").isEmpty()) {
            throw invalid("A flow needs at least one category.");
        }
        ObjectNode flows = meta(account, "flows");
        String json = null;
        if (in.hasNonNull("cloneFlowId")) {
            JsonNode source = flows.get(in.get("cloneFlowId").asText());
            if (source == null) {
                throw notFound("The flow " + in.get("cloneFlowId").asText() + " doesn't exist.");
            }
            json = source.path("_json").asText(null);
        }
        if (in.hasNonNull("flowJson")) {
            json = new String(Base64.getDecoder().decode(in.get("flowJson").asText()), StandardCharsets.UTF_8);
        }
        String id = digits(sha(UUID.randomUUID().toString()), 0, 16);
        ObjectNode flow = flows.putObject(id).put("flowId", id).put("flowName", required(in, "flowName"))
                .put("flowStatus", "DRAFT");
        flow.set("flowCategories", in.get("categories").deepCopy());
        if (in.hasNonNull("endpointUri")) {
            flow.put("endpointUri", in.get("endpointUri").asText());
        }
        ArrayNode errors = setFlowJson(flow, json);
        if (in.path("publish").asBoolean(false)) {
            if (!errors.isEmpty() || json == null) {
                throw invalid("The flow can't be published: " + (json == null ? "it has no flow JSON" : errors));
            }
            flow.put("flowStatus", "PUBLISHED");
        }
        save(region, account);
        ObjectNode out = mapper.createObjectNode().put("flowId", id);
        out.set("validationErrors", errors);
        return out;
    }

    /** Stores the flow JSON and its validation errors: a version and a non-empty list of screens with ids. */
    private ArrayNode setFlowJson(ObjectNode flow, String json) {
        ArrayNode errors = mapper.createArrayNode();
        if (json != null) {
            flow.put("_json", json);
            try {
                JsonNode parsed = mapper.readTree(json);
                if (!parsed.hasNonNull("version")) {
                    errors.add("MISSING_REQUIRED_PROPERTY: version");
                } else {
                    flow.put("jsonVersion", parsed.get("version").asText());
                }
                if (!parsed.path("screens").isArray() || parsed.path("screens").isEmpty()) {
                    errors.add("MISSING_REQUIRED_PROPERTY: screens");
                }
                for (JsonNode screen : parsed.path("screens")) {
                    if (!screen.hasNonNull("id")) {
                        errors.add("MISSING_REQUIRED_PROPERTY: screens[].id");
                    }
                }
            } catch (IOException e) {
                errors.add("INVALID_JSON: " + e.getMessage());
            }
        }
        flow.set("validationErrors", errors);
        return errors;
    }

    private ObjectNode flow(ObjectNode account, String id) {
        JsonNode flow = meta(account, "flows").get(id);
        if (flow == null) {
            throw notFound("The flow " + id + " doesn't exist.");
        }
        return (ObjectNode) flow;
    }

    private ObjectNode flowView(ObjectNode flow) {
        ObjectNode view = flow.deepCopy();
        view.remove("_json");
        return view;
    }

    private ObjectNode getFlow(JsonNode in, String region) {
        ObjectNode account = account(region, required(in, "id"));
        ObjectNode view = flowView(flow(account, required(in, "flowId")));
        view.set("categories", view.remove("flowCategories"));
        view.putObject("whatsAppBusinessAccount").put("id", account.path("wabaId").asText())
                .put("name", account.path("wabaName").asText());
        return view;
    }

    private ObjectNode listFlows(JsonNode in, String region) {
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode flow : meta(account(region, required(in, "id")), "flows")) {
            ObjectNode summary = flowView((ObjectNode) flow);
            summary.remove("endpointUri");
            summary.remove("jsonVersion");
            out.add(summary);
        }
        return wrap("flows", out);
    }

    private ObjectNode updateFlow(JsonNode in, String region) {
        ObjectNode account = account(region, required(in, "id"));
        ObjectNode flow = flow(account, required(in, "flowId"));
        if (in.hasNonNull("flowName")) {
            flow.put("flowName", in.get("flowName").asText());
        }
        if (in.has("categories")) {
            flow.set("flowCategories", in.get("categories").deepCopy());
        }
        if (in.hasNonNull("endpointUri")) {
            flow.put("endpointUri", in.get("endpointUri").asText());
        }
        save(region, account);
        return mapper.createObjectNode();
    }

    private ObjectNode updateFlowAssets(JsonNode in, String region) {
        ObjectNode account = account(region, required(in, "id"));
        ObjectNode flow = flow(account, required(in, "flowId"));
        if (!"DRAFT".equals(flow.path("flowStatus").asText())) {
            throw invalid("Only a draft flow's JSON can change; this flow is " + flow.path("flowStatus").asText()
                    + ".");
        }
        ArrayNode errors = setFlowJson(flow, new String(Base64.getDecoder().decode(required(in, "flowJson")),
                StandardCharsets.UTF_8));
        save(region, account);
        ObjectNode out = mapper.createObjectNode();
        out.set("validationErrors", errors);
        return out;
    }

    private ObjectNode flowStatus(JsonNode in, String region, String target) {
        ObjectNode account = account(region, required(in, "id"));
        ObjectNode flow = flow(account, required(in, "flowId"));
        String current = flow.path("flowStatus").asText();
        if ("PUBLISHED".equals(target)) {
            if (!"DRAFT".equals(current)) {
                throw invalid("Only a draft flow can be published; this flow is " + current + ".");
            }
            if (!flow.has("_json") || !flow.path("validationErrors").isEmpty()) {
                throw invalid("The flow can't be published until its flow JSON is valid.");
            }
        } else if (!"PUBLISHED".equals(current)) {
            throw invalid("Only a published flow can be deprecated; this flow is " + current + ".");
        }
        flow.put("flowStatus", target);
        save(region, account);
        return mapper.createObjectNode();
    }

    private ObjectNode deleteFlow(JsonNode in, String region) {
        ObjectNode account = account(region, required(in, "id"));
        ObjectNode flow = flow(account, required(in, "flowId"));
        if (!"DRAFT".equals(flow.path("flowStatus").asText())) {
            throw invalid("Only a draft flow can be deleted; deprecate a published one.");
        }
        meta(account, "flows").remove(flow.path("flowId").asText());
        save(region, account);
        return mapper.createObjectNode();
    }

    private ObjectNode flowPreview(JsonNode in, String region) {
        ObjectNode flow = flow(account(region, required(in, "id")), required(in, "flowId"));
        ObjectNode out = mapper.createObjectNode().put("flowId", flow.path("flowId").asText());
        out.putObject("preview").put("previewUrl", "https://business.facebook.com/wa/manage/flows/"
                        + flow.path("flowId").asText() + "/preview/?token=" + UUID.randomUUID())
                .put("expiresAt", Instant.now().plusSeconds(30L * 86400).toString());
        return out;
    }

    private ObjectNode flowAssets(JsonNode in, String region) {
        ObjectNode flow = flow(account(region, required(in, "id")), required(in, "flowId"));
        ArrayNode assets = mapper.createArrayNode();
        if (flow.has("_json")) {
            assets.addObject().put("name", "flow.json").put("assetType", "FLOW_JSON")
                    .put("downloadUrl", "data:application/json;base64," + Base64.getEncoder()
                            .encodeToString(flow.path("_json").asText().getBytes(StandardCharsets.UTF_8)));
        }
        return wrap("flowAssets", assets);
    }

    // ---------- keys, calls and conversions ----------

    private ObjectNode putPublicKey(JsonNode in, String region) {
        Phone phone = phone(region, required(in, "originationPhoneNumberId"));
        String pem = in.path("businessPublicKey").asText(null);
        if (pem == null) {
            throw invalid("businessPublicKey is required.");
        }
        try {
            String body = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
            RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
            if (key.getModulus().bitLength() < 2048) {
                throw invalid("The business public key must be an RSA key of at least 2048 bits.");
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("businessPublicKey isn't a PEM-encoded RSA public key.");
        }
        meta(phone.account(), "keys").put(phone.phone().path("phoneNumberId").asText(), pem);
        save(region, phone.account());
        return mapper.createObjectNode();
    }

    private ObjectNode getPublicKey(JsonNode in, String region) {
        Phone phone = phone(region, required(in, "originationPhoneNumberId"));
        JsonNode key = meta(phone.account(), "keys").get(phone.phone().path("phoneNumberId").asText());
        if (key == null) {
            throw notFound("The phone number has no business public key.");
        }
        return mapper.createObjectNode().put("businessPublicKey", key.asText())
                .put("businessPublicKeySignatureStatus", "VALID");
    }

    private ObjectNode callPermission(JsonNode in, String region) {
        Phone phone = phone(region, required(in, "originationPhoneNumberId"));
        boolean enabled = phone.phone().path("callSettings").path("callEnabled").asBoolean(false);
        ObjectNode out = mapper.createObjectNode();
        out.putObject("permission").put("status", enabled ? "no_permission" : "disabled");
        ArrayNode actions = out.putArray("actions");
        for (String action : List.of("send_call_permission_request", "start_call")) {
            actions.addObject().put("actionName", action).put("canPerformAction", enabled)
                    .putArray("limits").addObject().put("timePeriod", "PT24H").put("maxAllowed", 1)
                    .put("currentUsage", 0);
        }
        return out;
    }

    private ObjectNode callEvent(JsonNode in, String region) {
        Phone phone = phone(region, required(in, "originationPhoneNumberId"));
        if (!phone.phone().path("callSettings").path("callEnabled").asBoolean(false)) {
            throw invalid("Calling isn't enabled on this phone number.");
        }
        JsonNode event = json(required(in, "callEvent"), "callEvent");
        if (!event.hasNonNull("action")) {
            throw invalid("A call event needs an action.");
        }
        return mapper.createObjectNode().put("callId", "wacid." + UUID.randomUUID());
    }

    private ObjectNode createDataset(JsonNode in, String region) {
        ObjectNode account = account(region, required(in, "id"));
        String id = digits(sha(UUID.randomUUID().toString()), 0, 15);
        meta(account, "datasets").put(id, Instant.now().getEpochSecond());
        account.put("datasetId", id);
        save(region, account);
        return mapper.createObjectNode().put("datasetId", id);
    }

    private ObjectNode conversionEvent(JsonNode in, String region) {
        ObjectNode account = account(region, required(in, "id"));
        if (!meta(account, "datasets").has(required(in, "datasetId"))) {
            throw notFound("The dataset " + in.path("datasetId").asText() + " doesn't exist.");
        }
        JsonNode data = json(required(in, "eventData"), "eventData");
        if (!data.isObject() && !data.isArray()) {
            throw invalid("eventData must be a JSON object or array.");
        }
        return mapper.createObjectNode().put("requestId", UUID.randomUUID().toString());
    }

    // ---------- tags ----------

    private ObjectNode tagged(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "social-messaging")) {
            throw invalid("resourceArn isn't an End User Messaging Social ARN.");
        }
        String resource = AwsArnUtils.parse(arn).resource();
        if (resource.startsWith("waba/")) {
            return account(region, resource.substring("waba/".length()));
        }
        throw notFound("The resource " + arn + " doesn't exist.");
    }

    private ObjectNode tag(JsonNode in, String region, boolean add) {
        ObjectNode account = tagged(decode(required(in, "resourceArn")), region);
        ObjectNode tags = account.withObject("/_/tags");
        if (add) {
            tags.setAll(tags(in.path("tags")));
        } else {
            in.path("tagKeys").forEach(key -> tags.remove(key.asText()));
        }
        save(region, account);
        return mapper.createObjectNode().put("statusCode", 200);
    }

    private ObjectNode listTags(JsonNode in, String region) {
        ObjectNode account = tagged(decode(required(in, "resourceArn")), region);
        ArrayNode tags = mapper.createArrayNode();
        account.path("_").path("tags").fields().forEachRemaining(e -> tags.addObject().put("key", e.getKey())
                .put("value", e.getValue().asText()));
        ObjectNode out = mapper.createObjectNode().put("statusCode", 200);
        out.set("tags", tags);
        return out;
    }

    // ---------- shared ----------

    private ObjectNode tags(JsonNode list) {
        ObjectNode map = mapper.createObjectNode();
        for (JsonNode tag : list) {
            map.put(tag.path("key").asText(), tag.path("value").asText(""));
        }
        return map;
    }

    /** A JSON document passed as a blob (base64 on the wire). */
    private JsonNode json(String base64, String field) {
        try {
            return mapper.readTree(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            throw invalid(field + " must be a JSON document.");
        }
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("social-messaging", region, accounts.accountId(), resource).toString();
    }

    private ObjectNode wrap(String field, JsonNode value) {
        ObjectNode out = mapper.createObjectNode();
        out.set(field, value);
        return out;
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Decimal digits derived from a hex digest, as Meta's numeric ids are. */
    private static String digits(String hex, int from, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = from; out.length() < count; i++) {
            out.append(Character.digit(hex.charAt(i % hex.length()), 16) % 10);
        }
        if (out.charAt(0) == '0') {
            out.setCharAt(0, '1');
        }
        return out.toString();
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static String key(String region, String id) {
        return region + "/" + id;
    }

    private static String decode(String value) {
        return value.contains("%") ? URLDecoder.decode(value, StandardCharsets.UTF_8) : value;
    }

    private static String required(JsonNode in, String field) {
        String value = in.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParametersException", message, 400);
    }
}
