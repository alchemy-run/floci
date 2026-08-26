package io.github.hectorvent.floci.services.smsvoice;

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
import io.github.hectorvent.floci.services.smsvoice.model.SmsVoiceConfigurationSet;
import io.github.hectorvent.floci.services.smsvoice.model.SmsVoiceEventDestination;
import io.github.hectorvent.floci.services.smsvoice.model.SmsVoiceOptOutList;
import io.github.hectorvent.floci.services.smsvoice.model.SmsVoiceOptedOutNumber;
import io.github.hectorvent.floci.services.smsvoice.model.SmsVoiceKeyword;
import io.github.hectorvent.floci.services.smsvoice.model.SmsVoicePhoneNumber;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Pinpoint SMS Voice V2 (awsJson1_0, {@code PinpointSMSVoiceV2.*}). Configuration
 * sets, event destinations, opt-out lists, carrier lookup, and resource tags
 * used by Alchemy's PinpointSMSVoiceV2 resources and bindings.
 */
@ApplicationScoped
public class SmsVoiceService implements Resettable {

    static final String SERVICE = "sms-voice";
    static final String DEFAULT_LIST_NAME = "Default";
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{1,14}$");
    private static final Set<String> MESSAGE_TYPES = Set.of("TRANSACTIONAL", "PROMOTIONAL");
    private static final Set<String> NUMBER_TYPES = Set.of(
            "SIMULATOR", "LONG_CODE", "TOLL_FREE", "TEN_DLC", "SHORT_CODE");
    private static final Set<String> NUMBER_CAPABILITIES = Set.of("SMS", "VOICE", "MMS");
    private static final int MAX_EVENT_DESTINATIONS = 5;
    private static final TypeReference<Map<String, SmsVoiceConfigurationSet>> CONFIGURATION_SET_MAP =
            new ConfigurationSetMap();
    private static final TypeReference<Map<String, SmsVoiceOptOutList>> OPT_OUT_LIST_MAP =
            new OptOutListMap();
    private static final TypeReference<Map<String, SmsVoicePhoneNumber>> PHONE_NUMBER_MAP =
            new PhoneNumberMap();

    private static final class ConfigurationSetMap
            extends TypeReference<Map<String, SmsVoiceConfigurationSet>> {
    }

    private static final class OptOutListMap extends TypeReference<Map<String, SmsVoiceOptOutList>> {
    }

    private static final class PhoneNumberMap extends TypeReference<Map<String, SmsVoicePhoneNumber>> {
    }

    private final StorageBackend<String, SmsVoiceConfigurationSet> configurationSets;
    private final StorageBackend<String, SmsVoiceOptOutList> optOutLists;
    private final StorageBackend<String, SmsVoicePhoneNumber> phoneNumbers;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final AtomicLong phoneSequence = new AtomicLong();

    @Inject
    public SmsVoiceService(StorageFactory storageFactory, RegionResolver regionResolver,
                           ObjectMapper objectMapper) {
        this(storageFactory.create("smsvoice", "smsvoice-configuration-sets.json", CONFIGURATION_SET_MAP),
                storageFactory.create("smsvoice", "smsvoice-opt-out-lists.json", OPT_OUT_LIST_MAP),
                storageFactory.create("smsvoice", "smsvoice-phone-numbers.json", PHONE_NUMBER_MAP),
                regionResolver, objectMapper);
    }

    SmsVoiceService(StorageBackend<String, SmsVoiceConfigurationSet> configurationSets,
                    StorageBackend<String, SmsVoiceOptOutList> optOutLists,
                    StorageBackend<String, SmsVoicePhoneNumber> phoneNumbers,
                    RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.configurationSets = configurationSets;
        this.optOutLists = optOutLists;
        this.phoneNumbers = phoneNumbers;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void clear() {
        configurationSets.clear();
        optOutLists.clear();
        phoneNumbers.clear();
    }

    public synchronized ObjectNode createConfigurationSet(JsonNode request, String region) {
        String token = textOrNull(request, "ClientToken");
        if (token != null) {
            Optional<SmsVoiceConfigurationSet> existing = setsInRegion(region).stream()
                    .filter(set -> token.equals(set.getClientToken()))
                    .findFirst();
            if (existing.isPresent()) {
                return createResponse(existing.get());
            }
        }
        String name = requireName(request, "ConfigurationSetName");
        if (findByName(region, name).isPresent()) {
            throw conflict("Configuration set " + name + " already exists.");
        }
        SmsVoiceConfigurationSet set = new SmsVoiceConfigurationSet();
        set.setConfigurationSetName(name);
        set.setConfigurationSetArn(arn(region, name));
        set.setRegion(region);
        set.setClientToken(token);
        set.setCreatedTimestamp(nowSeconds());
        set.setTags(readTags(request));
        configurationSets.put(storageKey(region, name), set);
        return createResponse(set);
    }

    public synchronized ObjectNode deleteConfigurationSet(JsonNode request, String region) {
        SmsVoiceConfigurationSet set = requireSet(region, requireName(request, "ConfigurationSetName"));
        configurationSets.delete(storageKey(region, set.getConfigurationSetName()));
        return configurationSetNode(set);
    }

    public synchronized ObjectNode describeConfigurationSets(JsonNode request, String region) {
        List<SmsVoiceConfigurationSet> selected;
        JsonNode namesNode = request.get("ConfigurationSetNames");
        if (namesNode != null && namesNode.isArray() && namesNode.size() > 0) {
            selected = new ArrayList<>();
            for (JsonNode nameNode : namesNode) {
                String identifier = nameNode.asText();
                if (identifier == null || identifier.isBlank()) {
                    throw invalid("ConfigurationSetNames contains an empty value.");
                }
                selected.add(requireSet(region, identifier));
            }
        } else {
            selected = setsInRegion(region);
        }
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode items = out.putArray("ConfigurationSets");
        for (SmsVoiceConfigurationSet set : selected) {
            items.add(configurationSetNode(set));
        }
        return out;
    }

    public synchronized ObjectNode createEventDestination(JsonNode request, String region) {
        SmsVoiceConfigurationSet set = requireSet(region, requireName(request, "ConfigurationSetName"));
        String destinationName = requireName(request, "EventDestinationName");
        if (findDestination(set, destinationName).isPresent()) {
            throw conflict("Event destination " + destinationName + " already exists.");
        }
        if (set.getEventDestinations().size() >= MAX_EVENT_DESTINATIONS) {
            throw quota("The configuration set already has the maximum of "
                    + MAX_EVENT_DESTINATIONS + " event destinations.");
        }
        List<String> matching = requireEventTypes(request, true);
        DestinationTarget target = requireDestination(request, true);
        SmsVoiceEventDestination destination = new SmsVoiceEventDestination();
        destination.setEventDestinationName(destinationName);
        destination.setEnabled(true);
        destination.setMatchingEventTypes(matching);
        applyDestination(destination, target);
        set.getEventDestinations().add(destination);
        configurationSets.put(storageKey(region, set.getConfigurationSetName()), set);
        return eventDestinationResponse(set, destination);
    }

    public synchronized ObjectNode updateEventDestination(JsonNode request, String region) {
        SmsVoiceConfigurationSet set = requireSet(region, requireName(request, "ConfigurationSetName"));
        SmsVoiceEventDestination destination = requireDestination(
                set, requireName(request, "EventDestinationName"));
        if (request.has("Enabled") && !request.get("Enabled").isNull()) {
            destination.setEnabled(request.get("Enabled").asBoolean());
        }
        if (request.has("MatchingEventTypes") && !request.get("MatchingEventTypes").isNull()) {
            destination.setMatchingEventTypes(requireEventTypes(request, true));
        }
        DestinationTarget target = requireDestination(request, false);
        if (target != null) {
            applyDestination(destination, target);
        }
        configurationSets.put(storageKey(region, set.getConfigurationSetName()), set);
        return eventDestinationResponse(set, destination);
    }

    public synchronized ObjectNode deleteEventDestination(JsonNode request, String region) {
        SmsVoiceConfigurationSet set = requireSet(region, requireName(request, "ConfigurationSetName"));
        SmsVoiceEventDestination destination = requireDestination(
                set, requireName(request, "EventDestinationName"));
        set.getEventDestinations().removeIf(
                item -> destination.getEventDestinationName().equals(item.getEventDestinationName()));
        configurationSets.put(storageKey(region, set.getConfigurationSetName()), set);
        return eventDestinationResponse(set, destination);
    }

    public synchronized ObjectNode setDefaultMessageType(JsonNode request, String region) {
        SmsVoiceConfigurationSet set = requireSet(region, requireName(request, "ConfigurationSetName"));
        String messageType = requireText(request, "MessageType");
        if (!MESSAGE_TYPES.contains(messageType)) {
            throw invalid("MessageType " + messageType + " is not supported.");
        }
        set.setDefaultMessageType(messageType);
        configurationSets.put(storageKey(region, set.getConfigurationSetName()), set);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ConfigurationSetArn", set.getConfigurationSetArn());
        out.put("ConfigurationSetName", set.getConfigurationSetName());
        out.put("MessageType", messageType);
        return out;
    }

    public synchronized ObjectNode deleteDefaultMessageType(JsonNode request, String region) {
        SmsVoiceConfigurationSet set = requireSet(region, requireName(request, "ConfigurationSetName"));
        String previous = set.getDefaultMessageType();
        if (previous == null) {
            throw notFound("Default message type not found on configuration set "
                    + set.getConfigurationSetName() + ".");
        }
        set.setDefaultMessageType(null);
        configurationSets.put(storageKey(region, set.getConfigurationSetName()), set);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ConfigurationSetArn", set.getConfigurationSetArn());
        out.put("ConfigurationSetName", set.getConfigurationSetName());
        out.put("MessageType", previous);
        return out;
    }

    public synchronized ObjectNode createOptOutList(JsonNode request, String region) {
        String name = requireName(request, "OptOutListName");
        if (findOptOutList(region, name).isPresent()) {
            throw conflict("An opt-out list with name " + name + " already exists.");
        }
        SmsVoiceOptOutList list = newOptOutList(region, name, false);
        list.setTags(readTags(request));
        optOutLists.put(storageKey(region, name), list);
        ObjectNode out = optOutListNode(list);
        writeTags(out.putArray("Tags"), list.getTags());
        return out;
    }

    public synchronized ObjectNode describeOptOutLists(JsonNode request, String region) {
        JsonNode namesNode = request.get("OptOutListNames");
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode items = out.putArray("OptOutLists");
        if (namesNode != null && namesNode.isArray() && namesNode.size() > 0) {
            for (JsonNode nameNode : namesNode) {
                String identifier = nameNode.asText();
                if (identifier == null || identifier.isBlank()) {
                    throw invalid("OptOutListNames contains an empty value.");
                }
                items.add(optOutListNode(requireOptOutList(region, identifier)));
            }
            return out;
        }
        ensureDefaultList(region);
        for (SmsVoiceOptOutList list : listsInRegion(region)) {
            items.add(optOutListNode(list));
        }
        return out;
    }

    public synchronized ObjectNode deleteOptOutList(JsonNode request, String region) {
        SmsVoiceOptOutList list = requireOptOutList(region, requireName(request, "OptOutListName"));
        if (list.isServiceManaged()) {
            throw conflict("The Default opt-out list cannot be modified or deleted.");
        }
        optOutLists.delete(storageKey(region, list.getOptOutListName()));
        return optOutListNode(list);
    }

    public synchronized ObjectNode putOptedOutNumber(JsonNode request, String region) {
        SmsVoiceOptOutList list = requireOptOutList(region, requireName(request, "OptOutListName"));
        String number = requireE164(request, "OptedOutNumber");
        SmsVoiceOptedOutNumber existing = list.getNumbers().get(number);
        if (existing == null) {
            existing = new SmsVoiceOptedOutNumber();
            existing.setOptedOutNumber(number);
            existing.setOptedOutTimestamp(nowSeconds());
            existing.setEndUserOptedOut(false);
            list.getNumbers().put(number, existing);
            optOutLists.put(storageKey(region, list.getOptOutListName()), list);
        }
        return optedOutResult(list, existing);
    }

    public synchronized ObjectNode describeOptedOutNumbers(JsonNode request, String region) {
        SmsVoiceOptOutList list = requireOptOutList(region, requireName(request, "OptOutListName"));
        ObjectNode out = objectMapper.createObjectNode();
        out.put("OptOutListArn", list.getOptOutListArn());
        out.put("OptOutListName", list.getOptOutListName());
        ArrayNode items = out.putArray("OptedOutNumbers");
        JsonNode filterNode = request.get("OptedOutNumbers");
        if (filterNode == null || !filterNode.isArray() || filterNode.isEmpty()) {
            for (SmsVoiceOptedOutNumber number : list.getNumbers().values()) {
                items.add(optedOutNode(number));
            }
            return out;
        }
        for (JsonNode item : filterNode) {
            String raw = item.asText();
            SmsVoiceOptedOutNumber number = list.getNumbers().get(raw);
            if (number == null) {
                throw notFound("Opted out number " + raw + " was not found.");
            }
            items.add(optedOutNode(number));
        }
        return out;
    }

    public synchronized ObjectNode deleteOptedOutNumber(JsonNode request, String region) {
        SmsVoiceOptOutList list = requireOptOutList(region, requireName(request, "OptOutListName"));
        String number = requireE164(request, "OptedOutNumber");
        SmsVoiceOptedOutNumber existing = list.getNumbers().remove(number);
        if (existing == null) {
            throw notFound("Opted out number " + number + " was not found.");
        }
        optOutLists.put(storageKey(region, list.getOptOutListName()), list);
        return optedOutResult(list, existing);
    }

    public synchronized ObjectNode describeKeywords(JsonNode request, String region) {
        SmsVoicePhoneNumber phone = requirePhone(region, requireText(request, "OriginationIdentity"));
        List<String> filter = stringList(request.get("Keywords"));
        ObjectNode out = objectMapper.createObjectNode();
        out.put("OriginationIdentityArn", phone.getPhoneNumberArn());
        out.put("OriginationIdentity", phone.getPhoneNumberId());
        ArrayNode items = out.putArray("Keywords");
        for (SmsVoiceKeyword keyword : phone.getKeywords().values()) {
            if (filter.isEmpty() || filter.contains(keyword.getKeyword())) {
                items.add(keywordNode(keyword));
            }
        }
        return out;
    }

    public ObjectNode carrierLookup(JsonNode request) {
        String phone = requireText(request, "PhoneNumber");
        String e164 = phone.startsWith("+") ? phone : "+" + phone;
        ObjectNode out = objectMapper.createObjectNode();
        out.put("E164PhoneNumber", e164);
        out.put("PhoneNumberType", "MOBILE");
        if (e164.startsWith("+1") && e164.length() == 12) {
            out.put("DialingCountryCode", "1");
            out.put("IsoCountryCode", "US");
            out.put("Country", "United States");
        }
        return out;
    }

    public ObjectNode putMessageFeedback(JsonNode request) {
        requireText(request, "MessageId");
        requireText(request, "MessageFeedbackStatus");
        throw notFound("The message was not found.");
    }

    public synchronized ObjectNode requestPhoneNumber(JsonNode request, String region) {
        String token = textOrNull(request, "ClientToken");
        if (token != null) {
            Optional<SmsVoicePhoneNumber> existing = phonesInRegion(region).stream()
                    .filter(phone -> token.equals(phone.getClientToken()))
                    .findFirst();
            if (existing.isPresent()) {
                return requestPhoneNumberResponse(existing.get());
            }
        }
        String iso = requireText(request, "IsoCountryCode");
        if (iso.length() != 2) {
            throw invalid("IsoCountryCode must be a two-character ISO country code.");
        }
        String messageType = requireText(request, "MessageType");
        if (!MESSAGE_TYPES.contains(messageType)) {
            throw invalid("MessageType " + messageType + " is not supported.");
        }
        String numberType = requireText(request, "NumberType");
        if (!NUMBER_TYPES.contains(numberType)) {
            throw invalid("NumberType " + numberType + " is not supported.");
        }
        List<String> capabilities = requireCapabilities(request);
        String id = "phone-" + UUID.randomUUID().toString().replace("-", "");
        SmsVoicePhoneNumber phone = new SmsVoicePhoneNumber();
        phone.setPhoneNumberId(id);
        phone.setPhoneNumberArn(regionResolver.buildArn(SERVICE, region, "phone-number/" + id));
        phone.setPhoneNumber(nextE164(iso));
        phone.setStatus("ACTIVE");
        phone.setIsoCountryCode(iso.toUpperCase());
        phone.setMessageType(messageType);
        phone.setNumberCapabilities(capabilities);
        phone.setNumberType(numberType);
        phone.setMonthlyLeasingPrice(monthlyPrice(numberType));
        phone.setTwoWayEnabled(false);
        phone.setSelfManagedOptOutsEnabled(false);
        String optOut = textOrNull(request, "OptOutListName");
        phone.setOptOutListName(optOut == null ? DEFAULT_LIST_NAME : optOut);
        phone.setInternationalSendingEnabled(boolOrDefault(request, "InternationalSendingEnabled", false));
        phone.setDeletionProtectionEnabled(boolOrDefault(request, "DeletionProtectionEnabled", false));
        phone.setPoolId(textOrNull(request, "PoolId"));
        phone.setRegistrationId(textOrNull(request, "RegistrationId"));
        phone.setRegion(region);
        phone.setClientToken(token);
        phone.setCreatedTimestamp(nowSeconds());
        phone.setTags(readTags(request));
        seedDefaultKeywords(phone);
        phoneNumbers.put(storageKey(region, id), phone);
        return requestPhoneNumberResponse(phone);
    }

    public synchronized ObjectNode describePhoneNumbers(JsonNode request, String region) {
        List<SmsVoicePhoneNumber> selected;
        JsonNode idsNode = request.get("PhoneNumberIds");
        if (idsNode != null && idsNode.isArray() && idsNode.size() > 0) {
            selected = new ArrayList<>();
            for (JsonNode idNode : idsNode) {
                String identifier = idNode.asText();
                if (identifier == null || identifier.isBlank()) {
                    throw invalid("PhoneNumberIds contains an empty value.");
                }
                selected.add(requirePhone(region, identifier));
            }
        } else {
            selected = phonesInRegion(region);
        }
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode items = out.putArray("PhoneNumbers");
        for (SmsVoicePhoneNumber phone : selected) {
            items.add(phoneNumberNode(phone));
        }
        return out;
    }

    public synchronized ObjectNode updatePhoneNumber(JsonNode request, String region) {
        SmsVoicePhoneNumber phone = requirePhone(region, requireText(request, "PhoneNumberId"));
        if (request.has("TwoWayEnabled") && !request.get("TwoWayEnabled").isNull()) {
            phone.setTwoWayEnabled(request.get("TwoWayEnabled").asBoolean());
        }
        if (request.has("TwoWayChannelArn")) {
            phone.setTwoWayChannelArn(textOrNull(request, "TwoWayChannelArn"));
        }
        if (request.has("TwoWayChannelRole")) {
            phone.setTwoWayChannelRole(textOrNull(request, "TwoWayChannelRole"));
        }
        if (request.has("SelfManagedOptOutsEnabled") && !request.get("SelfManagedOptOutsEnabled").isNull()) {
            phone.setSelfManagedOptOutsEnabled(request.get("SelfManagedOptOutsEnabled").asBoolean());
        }
        if (request.hasNonNull("OptOutListName")) {
            phone.setOptOutListName(request.get("OptOutListName").asText());
        }
        if (request.has("InternationalSendingEnabled") && !request.get("InternationalSendingEnabled").isNull()) {
            phone.setInternationalSendingEnabled(request.get("InternationalSendingEnabled").asBoolean());
        }
        if (request.has("DeletionProtectionEnabled") && !request.get("DeletionProtectionEnabled").isNull()) {
            phone.setDeletionProtectionEnabled(request.get("DeletionProtectionEnabled").asBoolean());
        }
        phoneNumbers.put(storageKey(region, phone.getPhoneNumberId()), phone);
        return phoneNumberResult(phone);
    }

    public synchronized ObjectNode releasePhoneNumber(JsonNode request, String region) {
        SmsVoicePhoneNumber phone = requirePhone(region, requireText(request, "PhoneNumberId"));
        if (phone.isDeletionProtectionEnabled()) {
            throw conflict("Phone number " + phone.getPhoneNumberId()
                    + " has deletion protection enabled.");
        }
        phoneNumbers.delete(storageKey(region, phone.getPhoneNumberId()));
        ObjectNode out = phoneNumberResult(phone);
        out.put("Status", "DELETED");
        return out;
    }

    public synchronized ObjectNode sendTextMessage(JsonNode request, String region) {
        return sendMessage(request, region, "SMS", false);
    }

    public synchronized ObjectNode sendVoiceMessage(JsonNode request, String region) {
        return sendMessage(request, region, "VOICE", true);
    }

    public synchronized ObjectNode sendMediaMessage(JsonNode request, String region) {
        return sendMessage(request, region, "MMS", true);
    }

    public synchronized ObjectNode putKeyword(JsonNode request, String region) {
        SmsVoicePhoneNumber phone = requirePhone(region, requireText(request, "OriginationIdentity"));
        String keyword = requireText(request, "Keyword");
        String message = requireText(request, "KeywordMessage");
        String action = textOrNull(request, "KeywordAction");
        if (action == null) {
            action = "AUTOMATIC_RESPONSE";
        }
        SmsVoiceKeyword stored = keyword(keyword, message, action);
        phone.getKeywords().put(keyword, stored);
        phoneNumbers.put(storageKey(region, phone.getPhoneNumberId()), phone);
        ObjectNode out = keywordNode(stored);
        out.put("OriginationIdentityArn", phone.getPhoneNumberArn());
        out.put("OriginationIdentity", phone.getPhoneNumberId());
        return out;
    }

    public synchronized ObjectNode deleteKeyword(JsonNode request, String region) {
        SmsVoicePhoneNumber phone = requirePhone(region, requireText(request, "OriginationIdentity"));
        String keyword = requireText(request, "Keyword");
        SmsVoiceKeyword existing = phone.getKeywords().remove(keyword);
        if (existing == null) {
            throw notFound("Keyword " + keyword + " was not found.");
        }
        phoneNumbers.put(storageKey(region, phone.getPhoneNumberId()), phone);
        ObjectNode out = keywordNode(existing);
        out.put("OriginationIdentityArn", phone.getPhoneNumberArn());
        out.put("OriginationIdentity", phone.getPhoneNumberId());
        return out;
    }

    public synchronized ObjectNode tagResource(JsonNode request, String region) {
        Tagged tagged = requireTagged(region, requireText(request, "ResourceArn"));
        tagged.tags().putAll(readTags(request));
        persistTagged(region, tagged);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request, String region) {
        Tagged tagged = requireTagged(region, requireText(request, "ResourceArn"));
        JsonNode keys = request.get("TagKeys");
        if (keys != null && keys.isArray()) {
            for (JsonNode key : keys) {
                tagged.tags().remove(key.asText());
            }
        }
        persistTagged(region, tagged);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listTagsForResource(JsonNode request, String region) {
        Tagged tagged = requireTagged(region, requireText(request, "ResourceArn"));
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ResourceArn", tagged.arn());
        writeTags(out.putArray("Tags"), tagged.tags());
        return out;
    }

    private ObjectNode createResponse(SmsVoiceConfigurationSet set) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ConfigurationSetArn", set.getConfigurationSetArn());
        out.put("ConfigurationSetName", set.getConfigurationSetName());
        writeTags(out.putArray("Tags"), set.getTags());
        out.put("CreatedTimestamp", set.getCreatedTimestamp());
        return out;
    }

    private ObjectNode configurationSetNode(SmsVoiceConfigurationSet set) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ConfigurationSetArn", set.getConfigurationSetArn());
        out.put("ConfigurationSetName", set.getConfigurationSetName());
        ArrayNode destinations = out.putArray("EventDestinations");
        for (SmsVoiceEventDestination destination : set.getEventDestinations()) {
            destinations.add(eventDestinationNode(destination));
        }
        if (set.getDefaultMessageType() != null) {
            out.put("DefaultMessageType", set.getDefaultMessageType());
        }
        if (set.getDefaultSenderId() != null) {
            out.put("DefaultSenderId", set.getDefaultSenderId());
        }
        if (set.getDefaultMessageFeedbackEnabled() != null) {
            out.put("DefaultMessageFeedbackEnabled", set.getDefaultMessageFeedbackEnabled());
        }
        out.put("CreatedTimestamp", set.getCreatedTimestamp());
        if (set.getProtectConfigurationId() != null) {
            out.put("ProtectConfigurationId", set.getProtectConfigurationId());
        }
        return out;
    }

    private SmsVoiceConfigurationSet requireSet(String region, String nameOrArn) {
        return findByNameOrArn(region, nameOrArn)
                .orElseThrow(() -> notFound("Configuration set " + nameOrArn + " was not found."));
    }

    private Tagged requireTagged(String region, String arn) {
        for (SmsVoiceConfigurationSet set : setsInRegion(region)) {
            if (arn.equals(set.getConfigurationSetArn())) {
                return Tagged.configurationSet(set);
            }
        }
        for (SmsVoiceOptOutList list : listsInRegion(region)) {
            if (arn.equals(list.getOptOutListArn())) {
                return Tagged.optOutList(list);
            }
        }
        for (SmsVoicePhoneNumber phone : phonesInRegion(region)) {
            if (arn.equals(phone.getPhoneNumberArn())) {
                return Tagged.phoneNumber(phone);
            }
        }
        throw notFound("Resource " + arn + " was not found.");
    }

    private void ensureDefaultList(String region) {
        if (findOptOutList(region, DEFAULT_LIST_NAME).isEmpty()) {
            optOutLists.put(storageKey(region, DEFAULT_LIST_NAME),
                    newOptOutList(region, DEFAULT_LIST_NAME, true));
        }
    }

    private SmsVoiceOptOutList newOptOutList(String region, String name, boolean serviceManaged) {
        SmsVoiceOptOutList list = new SmsVoiceOptOutList();
        list.setOptOutListName(name);
        list.setOptOutListArn(regionResolver.buildArn(SERVICE, region, "opt-out-list/" + name));
        list.setRegion(region);
        list.setCreatedTimestamp(nowSeconds());
        list.setServiceManaged(serviceManaged);
        return list;
    }

    private SmsVoiceOptOutList requireOptOutList(String region, String nameOrArn) {
        return findOptOutListByNameOrArn(region, nameOrArn)
                .orElseThrow(() -> notFound("Opt-out list " + nameOrArn + " was not found."));
    }

    private Optional<SmsVoiceOptOutList> findOptOutList(String region, String name) {
        return optOutLists.get(storageKey(region, name));
    }

    private Optional<SmsVoiceOptOutList> findOptOutListByNameOrArn(String region, String nameOrArn) {
        Optional<SmsVoiceOptOutList> byName = findOptOutList(region, nameOrArn);
        if (byName.isPresent()) {
            return byName;
        }
        return listsInRegion(region).stream()
                .filter(list -> nameOrArn.equals(list.getOptOutListArn()))
                .findFirst();
    }

    private List<SmsVoiceOptOutList> listsInRegion(String region) {
        List<SmsVoiceOptOutList> out = new ArrayList<>();
        for (SmsVoiceOptOutList list : optOutLists.values()) {
            if (region.equals(list.getRegion())) {
                out.add(list);
            }
        }
        out.sort(Comparator.comparing(SmsVoiceOptOutList::getOptOutListName));
        return out;
    }

    private ObjectNode optOutListNode(SmsVoiceOptOutList list) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("OptOutListArn", list.getOptOutListArn());
        out.put("OptOutListName", list.getOptOutListName());
        out.put("CreatedTimestamp", list.getCreatedTimestamp());
        return out;
    }

    private ObjectNode optedOutNode(SmsVoiceOptedOutNumber number) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("OptedOutNumber", number.getOptedOutNumber());
        out.put("OptedOutTimestamp", number.getOptedOutTimestamp());
        out.put("EndUserOptedOut", number.isEndUserOptedOut());
        return out;
    }

    private ObjectNode optedOutResult(SmsVoiceOptOutList list, SmsVoiceOptedOutNumber number) {
        ObjectNode out = optedOutNode(number);
        out.put("OptOutListArn", list.getOptOutListArn());
        out.put("OptOutListName", list.getOptOutListName());
        return out;
    }

    private String requireE164(JsonNode request, String field) {
        String value = requireText(request, field);
        if (!E164.matcher(value).matches()) {
            throw invalid(field + " must be an E.164 phone number.");
        }
        return value;
    }

    private void persistTagged(String region, Tagged tagged) {
        if (tagged.set() != null) {
            configurationSets.put(storageKey(region, tagged.set().getConfigurationSetName()), tagged.set());
        } else if (tagged.list() != null) {
            optOutLists.put(storageKey(region, tagged.list().getOptOutListName()), tagged.list());
        } else {
            phoneNumbers.put(storageKey(region, tagged.phone().getPhoneNumberId()), tagged.phone());
        }
    }

    private record Tagged(SmsVoiceConfigurationSet set, SmsVoiceOptOutList list, SmsVoicePhoneNumber phone) {
        static Tagged configurationSet(SmsVoiceConfigurationSet set) {
            return new Tagged(set, null, null);
        }

        static Tagged optOutList(SmsVoiceOptOutList list) {
            return new Tagged(null, list, null);
        }

        static Tagged phoneNumber(SmsVoicePhoneNumber phone) {
            return new Tagged(null, null, phone);
        }

        Map<String, String> tags() {
            if (set != null) {
                return set.getTags();
            }
            if (list != null) {
                return list.getTags();
            }
            return phone.getTags();
        }

        String arn() {
            if (set != null) {
                return set.getConfigurationSetArn();
            }
            if (list != null) {
                return list.getOptOutListArn();
            }
            return phone.getPhoneNumberArn();
        }
    }

    private Optional<SmsVoiceConfigurationSet> findByName(String region, String name) {
        return configurationSets.get(storageKey(region, name));
    }

    private Optional<SmsVoiceConfigurationSet> findByNameOrArn(String region, String nameOrArn) {
        Optional<SmsVoiceConfigurationSet> byName = findByName(region, nameOrArn);
        if (byName.isPresent()) {
            return byName;
        }
        return setsInRegion(region).stream()
                .filter(set -> nameOrArn.equals(set.getConfigurationSetArn()))
                .findFirst();
    }

    private List<SmsVoiceConfigurationSet> setsInRegion(String region) {
        List<SmsVoiceConfigurationSet> out = new ArrayList<>();
        for (SmsVoiceConfigurationSet set : configurationSets.values()) {
            if (region.equals(set.getRegion())) {
                out.add(set);
            }
        }
        out.sort(Comparator.comparing(SmsVoiceConfigurationSet::getConfigurationSetName));
        return out;
    }

    private String requireName(JsonNode request, String field) {
        String name = requireText(request, field);
        if (!NAME_PATTERN.matcher(name).matches() && !name.startsWith("arn:")) {
            throw invalid(field + " must match [A-Za-z0-9_-]{1,64} or be a configuration set ARN.");
        }
        return name;
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode list = request == null ? null : request.get("Tags");
        if (list == null || !list.isArray()) {
            return tags;
        }
        for (JsonNode tag : list) {
            String key = textOrNull(tag, "Key");
            if (key == null) {
                continue;
            }
            String value = textOrNull(tag, "Value");
            tags.put(key, value == null ? "" : value);
        }
        return tags;
    }

    private static void writeTags(ArrayNode tags, Map<String, String> source) {
        source.forEach((key, value) -> {
            ObjectNode tag = tags.addObject();
            tag.put("Key", key);
            tag.put("Value", value);
        });
    }

    private String arn(String region, String name) {
        return regionResolver.buildArn(SERVICE, region, "configuration-set/" + name);
    }

    private static String storageKey(String region, String name) {
        return region + ":" + name;
    }

    private static long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException quota(String message) {
        return new AwsException("ServiceQuotaExceededException", message, 402);
    }

    private ObjectNode eventDestinationResponse(SmsVoiceConfigurationSet set,
                                                SmsVoiceEventDestination destination) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ConfigurationSetArn", set.getConfigurationSetArn());
        out.put("ConfigurationSetName", set.getConfigurationSetName());
        out.set("EventDestination", eventDestinationNode(destination));
        return out;
    }

    private ObjectNode eventDestinationNode(SmsVoiceEventDestination destination) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("EventDestinationName", destination.getEventDestinationName());
        node.put("Enabled", destination.isEnabled());
        ArrayNode types = node.putArray("MatchingEventTypes");
        for (String type : destination.getMatchingEventTypes()) {
            types.add(type);
        }
        if (destination.getCloudWatchLogsIamRoleArn() != null
                || destination.getCloudWatchLogsLogGroupArn() != null) {
            ObjectNode cw = node.putObject("CloudWatchLogsDestination");
            if (destination.getCloudWatchLogsIamRoleArn() != null) {
                cw.put("IamRoleArn", destination.getCloudWatchLogsIamRoleArn());
            }
            if (destination.getCloudWatchLogsLogGroupArn() != null) {
                cw.put("LogGroupArn", destination.getCloudWatchLogsLogGroupArn());
            }
        }
        if (destination.getKinesisFirehoseIamRoleArn() != null
                || destination.getKinesisFirehoseDeliveryStreamArn() != null) {
            ObjectNode firehose = node.putObject("KinesisFirehoseDestination");
            if (destination.getKinesisFirehoseIamRoleArn() != null) {
                firehose.put("IamRoleArn", destination.getKinesisFirehoseIamRoleArn());
            }
            if (destination.getKinesisFirehoseDeliveryStreamArn() != null) {
                firehose.put("DeliveryStreamArn", destination.getKinesisFirehoseDeliveryStreamArn());
            }
        }
        if (destination.getSnsTopicArn() != null) {
            node.putObject("SnsDestination").put("TopicArn", destination.getSnsTopicArn());
        }
        return node;
    }

    private Optional<SmsVoiceEventDestination> findDestination(SmsVoiceConfigurationSet set, String name) {
        return set.getEventDestinations().stream()
                .filter(destination -> name.equals(destination.getEventDestinationName()))
                .findFirst();
    }

    private SmsVoiceEventDestination requireDestination(SmsVoiceConfigurationSet set, String name) {
        return findDestination(set, name)
                .orElseThrow(() -> notFound("Event destination " + name + " was not found."));
    }

    private List<String> requireEventTypes(JsonNode request, boolean required) {
        JsonNode node = request.get("MatchingEventTypes");
        if (node == null || node.isNull() || node.isMissingNode()) {
            if (required) {
                throw invalid("MatchingEventTypes is required.");
            }
            return List.of();
        }
        if (!node.isArray() || node.isEmpty()) {
            throw invalid("MatchingEventTypes must contain at least one event type.");
        }
        List<String> types = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                types.add(item.asText());
            }
        }
        if (types.isEmpty()) {
            throw invalid("MatchingEventTypes must contain at least one event type.");
        }
        return types;
    }

    private DestinationTarget requireDestination(JsonNode request, boolean required) {
        String cwRole = nestedText(request, "CloudWatchLogsDestination", "IamRoleArn");
        String cwGroup = nestedText(request, "CloudWatchLogsDestination", "LogGroupArn");
        String kfRole = nestedText(request, "KinesisFirehoseDestination", "IamRoleArn");
        String kfStream = nestedText(request, "KinesisFirehoseDestination", "DeliveryStreamArn");
        String snsTopic = nestedText(request, "SnsDestination", "TopicArn");
        boolean cw = cwRole != null || cwGroup != null;
        boolean kf = kfRole != null || kfStream != null;
        boolean sns = snsTopic != null;
        int count = (cw ? 1 : 0) + (kf ? 1 : 0) + (sns ? 1 : 0);
        if (count == 0) {
            if (required) {
                throw invalid("Exactly one of CloudWatchLogsDestination, KinesisFirehoseDestination, "
                        + "or SnsDestination is required.");
            }
            return null;
        }
        if (count > 1) {
            throw invalid("Exactly one of CloudWatchLogsDestination, KinesisFirehoseDestination, "
                    + "or SnsDestination is required.");
        }
        if (cw && (cwRole == null || cwGroup == null)) {
            throw invalid("CloudWatchLogsDestination requires IamRoleArn and LogGroupArn.");
        }
        if (kf && (kfRole == null || kfStream == null)) {
            throw invalid("KinesisFirehoseDestination requires IamRoleArn and DeliveryStreamArn.");
        }
        return new DestinationTarget(cwRole, cwGroup, kfRole, kfStream, snsTopic);
    }

    private static void applyDestination(SmsVoiceEventDestination destination, DestinationTarget target) {
        destination.clearDestinations();
        destination.setCloudWatchLogsIamRoleArn(target.cwRole());
        destination.setCloudWatchLogsLogGroupArn(target.cwGroup());
        destination.setKinesisFirehoseIamRoleArn(target.kfRole());
        destination.setKinesisFirehoseDeliveryStreamArn(target.kfStream());
        destination.setSnsTopicArn(target.snsTopic());
    }

    private static String nestedText(JsonNode request, String object, String field) {
        if (request == null || !request.hasNonNull(object)) {
            return null;
        }
        return textOrNull(request.get(object), field);
    }

    private record DestinationTarget(
            String cwRole,
            String cwGroup,
            String kfRole,
            String kfStream,
            String snsTopic) {
    }

    private SmsVoicePhoneNumber requirePhone(String region, String idOrArn) {
        return findPhone(region, idOrArn)
                .orElseThrow(() -> notFound("Phone number " + idOrArn + " was not found."));
    }

    private Optional<SmsVoicePhoneNumber> findPhone(String region, String idOrArn) {
        String id = phoneId(idOrArn);
        Optional<SmsVoicePhoneNumber> byId = phoneNumbers.get(storageKey(region, id));
        if (byId.isPresent()) {
            return byId;
        }
        return phonesInRegion(region).stream()
                .filter(phone -> idOrArn.equals(phone.getPhoneNumberArn())
                        || idOrArn.equals(phone.getPhoneNumberId()))
                .findFirst();
    }

    private static String phoneId(String idOrArn) {
        if (idOrArn != null && idOrArn.startsWith("arn:")) {
            int slash = idOrArn.lastIndexOf('/');
            if (slash >= 0 && slash < idOrArn.length() - 1) {
                return idOrArn.substring(slash + 1);
            }
        }
        return idOrArn;
    }

    private List<SmsVoicePhoneNumber> phonesInRegion(String region) {
        List<SmsVoicePhoneNumber> out = new ArrayList<>();
        for (SmsVoicePhoneNumber phone : phoneNumbers.values()) {
            if (region.equals(phone.getRegion())) {
                out.add(phone);
            }
        }
        out.sort(Comparator.comparing(SmsVoicePhoneNumber::getPhoneNumberId));
        return out;
    }

    private ObjectNode requestPhoneNumberResponse(SmsVoicePhoneNumber phone) {
        ObjectNode out = phoneNumberResult(phone);
        writeTags(out.putArray("Tags"), phone.getTags());
        return out;
    }

    private ObjectNode phoneNumberResult(SmsVoicePhoneNumber phone) {
        ObjectNode out = phoneNumberNode(phone);
        if (phone.getRegistrationId() == null) {
            out.remove("RegistrationId");
        }
        return out;
    }

    private ObjectNode phoneNumberNode(SmsVoicePhoneNumber phone) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("PhoneNumberArn", phone.getPhoneNumberArn());
        out.put("PhoneNumberId", phone.getPhoneNumberId());
        out.put("PhoneNumber", phone.getPhoneNumber());
        out.put("Status", phone.getStatus());
        out.put("IsoCountryCode", phone.getIsoCountryCode());
        out.put("MessageType", phone.getMessageType());
        ArrayNode capabilities = out.putArray("NumberCapabilities");
        for (String capability : phone.getNumberCapabilities()) {
            capabilities.add(capability);
        }
        out.put("NumberType", phone.getNumberType());
        out.put("MonthlyLeasingPrice", phone.getMonthlyLeasingPrice());
        out.put("TwoWayEnabled", phone.isTwoWayEnabled());
        if (phone.getTwoWayChannelArn() != null) {
            out.put("TwoWayChannelArn", phone.getTwoWayChannelArn());
        }
        if (phone.getTwoWayChannelRole() != null) {
            out.put("TwoWayChannelRole", phone.getTwoWayChannelRole());
        }
        out.put("SelfManagedOptOutsEnabled", phone.isSelfManagedOptOutsEnabled());
        out.put("OptOutListName", phone.getOptOutListName());
        out.put("InternationalSendingEnabled", phone.isInternationalSendingEnabled());
        out.put("DeletionProtectionEnabled", phone.isDeletionProtectionEnabled());
        if (phone.getPoolId() != null) {
            out.put("PoolId", phone.getPoolId());
        }
        if (phone.getRegistrationId() != null) {
            out.put("RegistrationId", phone.getRegistrationId());
        }
        out.put("CreatedTimestamp", phone.getCreatedTimestamp());
        return out;
    }

    private List<String> requireCapabilities(JsonNode request) {
        JsonNode node = request.get("NumberCapabilities");
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw invalid("NumberCapabilities is required.");
        }
        List<String> capabilities = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isTextual() || item.asText().isBlank()) {
                continue;
            }
            String capability = item.asText();
            if (!NUMBER_CAPABILITIES.contains(capability)) {
                throw invalid("NumberCapabilities contains unsupported value " + capability + ".");
            }
            capabilities.add(capability);
        }
        if (capabilities.isEmpty()) {
            throw invalid("NumberCapabilities is required.");
        }
        return capabilities;
    }

    private String nextE164(String iso) {
        long seq = phoneSequence.incrementAndGet();
        if ("GB".equalsIgnoreCase(iso)) {
            return String.format("+4477009%06d", seq % 1_000_000);
        }
        return String.format("+1555%07d", seq % 10_000_000);
    }

    private static String monthlyPrice(String numberType) {
        return "SIMULATOR".equals(numberType) ? "1.00" : "2.00";
    }

    private static boolean boolOrDefault(JsonNode request, String field, boolean fallback) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return fallback;
        }
        return request.get(field).asBoolean();
    }
}
