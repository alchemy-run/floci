package io.github.hectorvent.floci.services.socialmessaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.socialmessaging.model.LinkedWhatsAppBusinessAccount;
import io.github.hectorvent.floci.services.socialmessaging.model.WhatsAppEventDestination;
import io.github.hectorvent.floci.services.socialmessaging.model.WhatsAppPhoneNumber;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AWS End User Messaging Social restJson1 — linked WhatsApp Business Accounts
 * plus the template/flow/message/media operations used by Alchemy
 * {@code LinkedWhatsAppBusinessAccount.test.ts} and {@code Bindings.test.ts}.
 */
@ApplicationScoped
public class SocialMessagingService implements Resettable {

    static final String SERVICE = "social-messaging";

    private static final TypeReference<Map<String, LinkedWhatsAppBusinessAccount>> ACCOUNT_MAP =
            new AccountMap();

    private static final class AccountMap extends TypeReference<Map<String, LinkedWhatsAppBusinessAccount>> {
    }

    private final StorageBackend<String, LinkedWhatsAppBusinessAccount> accounts;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SocialMessagingService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("socialmessaging", "socialmessaging-accounts.json", ACCOUNT_MAP),
                regionResolver, objectMapper);
    }

    SocialMessagingService(
            StorageBackend<String, LinkedWhatsAppBusinessAccount> accounts,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.accounts = accounts;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void clear() {
        accounts.clear();
    }

    public synchronized ObjectNode associateAccount(String region, JsonNode request) {
        JsonNode setup = request == null ? null : request.get("setupFinalization");
        if (setup == null || !setup.isObject()) {
            throw invalid("setupFinalization is required.");
        }
        JsonNode wabaNode = setup.get("waba");
        String requestedId = wabaNode != null && wabaNode.isObject() ? optionalText(wabaNode, "id") : null;
        String id = requestedId != null ? requestedId : "waba-" + newId();
        LinkedWhatsAppBusinessAccount existing = accounts.get(id).orElse(null);
        if (existing != null) {
            return associateResponse(existing);
        }

        LinkedWhatsAppBusinessAccount account = new LinkedWhatsAppBusinessAccount();
        account.setId(id);
        account.setArn(regionResolver.buildArn(SERVICE, region, "waba/" + id));
        account.setWabaId(id.startsWith("waba-") ? id.substring("waba-".length()) : id);
        String name = wabaNode != null ? optionalText(wabaNode, "accountName") : null;
        account.setWabaName(name != null ? name : id);
        account.setRegistrationStatus("COMPLETE");
        account.setLinkDate(Instant.now().getEpochSecond());
        account.setRegion(region);
        if (wabaNode != null) {
            account.setTags(readTags(wabaNode.get("tags")));
        }
        account.setPhoneNumbers(readPhones(region, setup.get("phoneNumbers"), id));
        accounts.put(id, account);
        return associateResponse(account);
    }

    public ObjectNode listAccounts() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("linkedAccounts");
        for (LinkedWhatsAppBusinessAccount account : accounts.values()) {
            list.add(toAccount(account, false));
        }
        return response;
    }

    public ObjectNode getAccount(String id) {
        return objectMapper.createObjectNode().set("account", toAccount(requireAccount(id), true));
    }

    public synchronized ObjectNode disassociateAccount(String id) {
        LinkedWhatsAppBusinessAccount account = requireAccount(id);
        accounts.delete(account.getId());
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode putEventDestinations(JsonNode request) {
        requireObject(request);
        LinkedWhatsAppBusinessAccount account = requireAccount(requireText(request, "id"));
        JsonNode destinations = request.get("eventDestinations");
        List<WhatsAppEventDestination> parsed = new ArrayList<>();
        if (destinations != null && destinations.isArray()) {
            for (JsonNode node : destinations) {
                if (node == null || !node.isObject()) {
                    continue;
                }
                String arn = optionalText(node, "eventDestinationArn");
                if (arn == null) {
                    throw invalid("eventDestinationArn is required.");
                }
                WhatsAppEventDestination destination = new WhatsAppEventDestination();
                destination.setEventDestinationArn(arn);
                destination.setRoleArn(optionalText(node, "roleArn"));
                parsed.add(destination);
            }
        }
        account.setEventDestinations(parsed);
        accounts.put(account.getId(), account);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTags(String resourceArn) {
        LinkedWhatsAppBusinessAccount account = requireAccountByArn(resourceArn);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tags = response.putArray("tags");
        for (Map.Entry<String, String> entry : account.getTags().entrySet()) {
            ObjectNode tag = tags.addObject();
            tag.put("key", entry.getKey());
            tag.put("value", entry.getValue());
        }
        response.put("statusCode", 200);
        return response;
    }

    public synchronized ObjectNode tagResource(JsonNode request) {
        requireObject(request);
        LinkedWhatsAppBusinessAccount account = requireAccountByArn(requireText(request, "resourceArn"));
        Map<String, String> tags = new LinkedHashMap<>(account.getTags());
        tags.putAll(readTags(request.get("tags")));
        account.setTags(tags);
        accounts.put(account.getId(), account);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("statusCode", 200);
        return response;
    }

    public ObjectNode listTemplates(String id) {
        requireAccount(id);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("templates");
        return response;
    }

    public ObjectNode listFlows(String id) {
        requireAccount(id);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("flows");
        return response;
    }

    public ObjectNode getPhoneNumber(String id) {
        PhoneMatch match = requirePhone(id);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("phoneNumber", toPhone(match.phone()));
        response.put("linkedWhatsAppBusinessAccountId", match.account().getId());
        return response;
    }

    public ObjectNode sendMessage(JsonNode request) {
        requireObject(request);
        requirePhone(requireText(request, "originationPhoneNumberId"));
        requireText(request, "metaApiVersion");
        if (!request.hasNonNull("message")) {
            throw invalid("message is required.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("messageId", "wamid." + newId());
        return response;
    }

    public ObjectNode getMessageMedia(JsonNode request) {
        requireObject(request);
        requireText(request, "mediaId");
        requirePhone(requireText(request, "originationPhoneNumberId"));
        throw notFound("WhatsApp media " + optionalText(request, "mediaId") + " not found.");
    }

    private ObjectNode associateResponse(LinkedWhatsAppBusinessAccount account) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("linkedWhatsAppBusinessAccountId", account.getId());
        response.put("statusCode", 200);
        return response;
    }

    private ObjectNode toAccount(LinkedWhatsAppBusinessAccount account, boolean includePhones) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", account.getArn());
        node.put("id", account.getId());
        node.put("wabaId", account.getWabaId());
        node.put("registrationStatus", account.getRegistrationStatus());
        node.put("linkDate", account.getLinkDate());
        node.put("wabaName", account.getWabaName());
        ArrayNode destinations = node.putArray("eventDestinations");
        for (WhatsAppEventDestination destination : account.getEventDestinations()) {
            ObjectNode item = destinations.addObject();
            item.put("eventDestinationArn", destination.getEventDestinationArn());
            if (destination.getRoleArn() != null) {
                item.put("roleArn", destination.getRoleArn());
            }
        }
        if (includePhones) {
            ArrayNode phones = node.putArray("phoneNumbers");
            for (WhatsAppPhoneNumber phone : account.getPhoneNumbers()) {
                phones.add(toPhone(phone));
            }
        }
        return node;
    }

    private ObjectNode toPhone(WhatsAppPhoneNumber phone) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", phone.getArn());
        node.put("phoneNumber", phone.getPhoneNumber());
        node.put("phoneNumberId", phone.getPhoneNumberId());
        node.put("metaPhoneNumberId", phone.getMetaPhoneNumberId());
        node.put("displayPhoneNumberName", phone.getDisplayPhoneNumberName());
        node.put("displayPhoneNumber", phone.getDisplayPhoneNumber());
        node.put("qualityRating", phone.getQualityRating());
        if (phone.getDataLocalizationRegion() != null) {
            node.put("dataLocalizationRegion", phone.getDataLocalizationRegion());
        }
        return node;
    }

    private List<WhatsAppPhoneNumber> readPhones(String region, JsonNode phoneNodes, String accountId) {
        List<WhatsAppPhoneNumber> phones = new ArrayList<>();
        int index = 0;
        if (phoneNodes != null && phoneNodes.isArray()) {
            for (JsonNode phoneNode : phoneNodes) {
                if (phoneNode == null || !phoneNode.isObject()) {
                    continue;
                }
                phones.add(createPhone(region, accountId, optionalText(phoneNode, "id"),
                        optionalText(phoneNode, "dataLocalizationRegion"), index));
                index++;
            }
        }
        if (phones.isEmpty()) {
            phones.add(createPhone(region, accountId, null, null, 0));
        }
        return phones;
    }

    private WhatsAppPhoneNumber createPhone(
            String region, String accountId, String requestedId, String localization, int index) {
        String phoneId = requestedId != null ? requestedId : "phone-number-id-" + accountId + "-" + index;
        String e164 = "+1555555" + String.format("%04d", index);
        WhatsAppPhoneNumber phone = new WhatsAppPhoneNumber();
        phone.setPhoneNumberId(phoneId);
        phone.setArn(regionResolver.buildArn(SERVICE, region, "phone-number-id/" + phoneId));
        phone.setPhoneNumber(e164);
        phone.setDisplayPhoneNumber(e164);
        phone.setMetaPhoneNumberId(phoneId);
        phone.setDisplayPhoneNumberName("Floci");
        phone.setQualityRating("GREEN");
        phone.setDataLocalizationRegion(localization);
        return phone;
    }

    private LinkedWhatsAppBusinessAccount requireAccount(String id) {
        String accountId = requireId(id, "id");
        return accounts.get(accountId).orElseThrow(
                () -> notFound("WhatsApp Business Account " + accountId + " not found."));
    }

    private LinkedWhatsAppBusinessAccount requireAccountByArn(String resourceArn) {
        String arn = requireId(resourceArn, "resourceArn");
        for (LinkedWhatsAppBusinessAccount account : accounts.values()) {
            if (arn.equals(account.getArn())) {
                return account;
            }
        }
        throw notFound("Resource " + arn + " not found.");
    }

    private PhoneMatch requirePhone(String id) {
        String phoneId = requireId(id, "id");
        for (LinkedWhatsAppBusinessAccount account : accounts.values()) {
            for (WhatsAppPhoneNumber phone : account.getPhoneNumbers()) {
                if (phoneId.equals(phone.getPhoneNumberId())) {
                    return new PhoneMatch(account, phone);
                }
            }
        }
        throw notFound("WhatsApp phone number " + phoneId + " not found.");
    }

    private Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || !node.isArray()) {
            return tags;
        }
        for (JsonNode tag : node) {
            if (tag == null || !tag.isObject()) {
                continue;
            }
            String key = optionalText(tag, "key");
            if (key == null) {
                continue;
            }
            String value = optionalText(tag, "value");
            tags.put(key, value == null ? "" : value);
        }
        return tags;
    }

    private static String requireId(String id, String field) {
        if (id == null || id.isBlank()) {
            throw invalid(field + " is required.");
        }
        return id;
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw invalid("Request body must be a JSON object.");
        }
    }

    private static String requireText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParametersException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private record PhoneMatch(LinkedWhatsAppBusinessAccount account, WhatsAppPhoneNumber phone) {
    }
}
