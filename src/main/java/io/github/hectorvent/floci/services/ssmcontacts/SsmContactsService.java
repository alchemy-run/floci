package io.github.hectorvent.floci.services.ssmcontacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.ssmcontacts.model.ContactChannelRecord;
import io.github.hectorvent.floci.services.ssmcontacts.model.ContactRecord;
import io.github.hectorvent.floci.services.ssmcontacts.model.EngagementRecord;
import io.github.hectorvent.floci.services.ssmcontacts.model.PageRecord;
import io.github.hectorvent.floci.services.ssmcontacts.model.RotationOverrideRecord;
import io.github.hectorvent.floci.services.ssmcontacts.model.RotationRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * SSM Contacts JSON 1.1 ({@code SSMContacts.*}) — contacts, channels, rotations,
 * resource policies, and tags used by Alchemy SSMContacts resources.
 */
@ApplicationScoped
public class SsmContactsService implements Resettable {

    private static final Set<String> CONTACT_TYPES = Set.of("PERSONAL", "ESCALATION", "ONCALL_SCHEDULE");
    private static final Set<String> CHANNEL_TYPES = Set.of("SMS", "VOICE", "EMAIL");
    private static final Pattern ALIAS = Pattern.compile("^[a-z0-9._-]{1,255}$");
    private static final Pattern NAME = Pattern.compile("^[\\S\\s]{1,255}$");

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final ConcurrentHashMap<String, ContactRecord> contacts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ContactChannelRecord> channels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RotationRecord> rotations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RotationOverrideRecord> overrides = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EngagementRecord> engagements = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PageRecord> pages = new ConcurrentHashMap<>();

    @Inject
    public SsmContactsService(ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        contacts.clear();
        channels.clear();
        rotations.clear();
        overrides.clear();
        engagements.clear();
        pages.clear();
    }

    public synchronized ObjectNode createContact(JsonNode request, String region) {
        String alias = requireText(request, "Alias").toLowerCase();
        if (!ALIAS.matcher(alias).matches()) {
            throw validation("Alias is invalid.");
        }
        String type = requireText(request, "Type");
        if (!CONTACT_TYPES.contains(type)) {
            throw validation("Type is invalid.");
        }
        String arn = contactArn(region, alias);
        if (contacts.containsKey(arn) || findContactByAlias(alias) != null) {
            throw new AwsException("ConflictException", "Contact " + alias + " already exists.", 409);
        }
        ContactRecord contact = new ContactRecord();
        contact.setContactArn(arn);
        contact.setAlias(alias);
        contact.setType(type);
        if (request.hasNonNull("DisplayName")) {
            contact.setDisplayName(requireText(request, "DisplayName"));
        }
        contact.setPlan(copyOrEmptyPlan(request.get("Plan")));
        contact.setTags(readTagList(request.get("Tags")));
        contacts.put(arn, contact);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ContactArn", arn);
        return response;
    }

    public ObjectNode getContact(JsonNode request, String region) {
        ContactRecord contact = requireContact(requireText(request, "ContactId"), region);
        return contactDetail(contact);
    }

    public synchronized ObjectNode updateContact(JsonNode request, String region) {
        ContactRecord contact = requireContact(requireText(request, "ContactId"), region);
        if (request.hasNonNull("DisplayName")) {
            contact.setDisplayName(request.get("DisplayName").asText());
        }
        if (request.has("Plan") && !request.get("Plan").isNull()) {
            contact.setPlan(copyOrEmptyPlan(request.get("Plan")));
        }
        contacts.put(contact.getContactArn(), contact);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deleteContact(JsonNode request, String region) {
        ContactRecord contact = requireContact(requireText(request, "ContactId"), region);
        channels.values().removeIf(channel -> contact.getContactArn().equals(channel.getContactArn()));
        contacts.remove(contact.getContactArn());
        return objectMapper.createObjectNode();
    }

    public ObjectNode listContacts(JsonNode request) {
        String aliasPrefix = request.hasNonNull("AliasPrefix") ? request.get("AliasPrefix").asText() : null;
        String type = request.hasNonNull("Type") ? request.get("Type").asText() : null;
        List<ContactRecord> matches = new ArrayList<>();
        for (ContactRecord contact : contacts.values()) {
            if (aliasPrefix != null && (contact.getAlias() == null || !contact.getAlias().startsWith(aliasPrefix))) {
                continue;
            }
            if (type != null && !type.equals(contact.getType())) {
                continue;
            }
            matches.add(contact);
        }
        matches.sort(Comparator.comparing(ContactRecord::getAlias, Comparator.nullsLast(String::compareTo)));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Contacts");
        for (ContactRecord contact : matches) {
            ObjectNode summary = list.addObject();
            summary.put("ContactArn", contact.getContactArn());
            summary.put("Alias", contact.getAlias());
            if (contact.getDisplayName() != null) {
                summary.put("DisplayName", contact.getDisplayName());
            }
            summary.put("Type", contact.getType());
        }
        return response;
    }

    public synchronized ObjectNode createContactChannel(JsonNode request, String region) {
        ContactRecord contact = requireContact(requireText(request, "ContactId"), region);
        String name = requireText(request, "Name");
        if (!NAME.matcher(name).matches()) {
            throw validation("Name is invalid.");
        }
        String type = requireText(request, "Type");
        if (!CHANNEL_TYPES.contains(type)) {
            throw validation("Type is invalid.");
        }
        if (!request.has("DeliveryAddress") || request.get("DeliveryAddress") == null
                || !request.get("DeliveryAddress").isObject()) {
            throw validation("DeliveryAddress is required.");
        }
        for (ContactChannelRecord existing : channels.values()) {
            if (contact.getContactArn().equals(existing.getContactArn())
                    && name.equals(existing.getName())
                    && type.equals(existing.getType())) {
                throw new AwsException("ConflictException", "Contact channel already exists.", 409);
            }
        }
        boolean defer = request.path("DeferActivation").asBoolean(false);
        String channelArn = "arn:aws:ssm-contacts:" + region + ":" + account()
                + ":contact-channel/" + UUID.randomUUID();
        ContactChannelRecord channel = new ContactChannelRecord();
        channel.setContactChannelArn(channelArn);
        channel.setContactArn(contact.getContactArn());
        channel.setName(name);
        channel.setType(type);
        channel.setDeliveryAddress(request.get("DeliveryAddress").deepCopy());
        channel.setActivationStatus(defer ? "NOT_ACTIVATED" : "ACTIVATED");
        channel.setActivationCode(defer ? "000000" : null);
        channels.put(channelArn, channel);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ContactChannelArn", channelArn);
        return response;
    }

    public ObjectNode getContactChannel(JsonNode request) {
        ContactChannelRecord channel = requireChannel(requireText(request, "ContactChannelId"));
        return channelDetail(channel);
    }

    public synchronized ObjectNode updateContactChannel(JsonNode request) {
        ContactChannelRecord channel = requireChannel(requireText(request, "ContactChannelId"));
        if (request.hasNonNull("Name")) {
            channel.setName(requireText(request, "Name"));
        }
        if (request.has("DeliveryAddress") && request.get("DeliveryAddress").isObject()) {
            channel.setDeliveryAddress(request.get("DeliveryAddress").deepCopy());
        }
        channels.put(channel.getContactChannelArn(), channel);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deleteContactChannel(JsonNode request) {
        ContactChannelRecord channel = requireChannel(requireText(request, "ContactChannelId"));
        channels.remove(channel.getContactChannelArn());
        return objectMapper.createObjectNode();
    }

    public ObjectNode listContactChannels(JsonNode request, String region) {
        ContactRecord contact = requireContact(requireText(request, "ContactId"), region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ContactChannels");
        channels.values().stream()
                .filter(channel -> contact.getContactArn().equals(channel.getContactArn()))
                .sorted(Comparator.comparing(ContactChannelRecord::getName, Comparator.nullsLast(String::compareTo)))
                .forEach(channel -> list.add(channelDetail(channel)));
        return response;
    }

    public synchronized ObjectNode createRotation(JsonNode request, String region) {
        String name = requireText(request, "Name");
        if (findRotationByName(name) != null) {
            throw new AwsException("ConflictException", "Rotation " + name + " already exists.", 409);
        }
        if (!request.has("Recurrence") || request.get("Recurrence") == null || !request.get("Recurrence").isObject()) {
            throw validation("Recurrence is required.");
        }
        List<String> contactIds = readStringArray(request, "ContactIds");
        String arn = "arn:aws:ssm-contacts:" + region + ":" + account() + ":rotation/" + name;
        RotationRecord rotation = new RotationRecord();
        rotation.setRotationArn(arn);
        rotation.setName(name);
        rotation.setContactIds(contactIds);
        rotation.setTimeZoneId(requireText(request, "TimeZoneId"));
        rotation.setRecurrence(request.get("Recurrence").deepCopy());
        rotation.setStartTime(readEpoch(request, "StartTime"));
        if (rotation.getStartTime() == null) {
            rotation.setStartTime(System.currentTimeMillis() / 1000);
        }
        rotation.setTags(readTagList(request.get("Tags")));
        rotations.put(arn, rotation);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("RotationArn", arn);
        return response;
    }

    public ObjectNode getRotation(JsonNode request) {
        RotationRecord rotation = requireRotation(requireText(request, "RotationId"));
        return rotationDetail(rotation);
    }

    public synchronized ObjectNode updateRotation(JsonNode request) {
        RotationRecord rotation = requireRotation(requireText(request, "RotationId"));
        if (request.has("ContactIds") && request.get("ContactIds").isArray()) {
            rotation.setContactIds(readStringArray(request, "ContactIds"));
        }
        if (request.hasNonNull("TimeZoneId")) {
            rotation.setTimeZoneId(requireText(request, "TimeZoneId"));
        }
        if (request.hasNonNull("StartTime")) {
            rotation.setStartTime(readEpoch(request, "StartTime"));
        }
        if (!request.has("Recurrence") || request.get("Recurrence") == null || !request.get("Recurrence").isObject()) {
            throw validation("Recurrence is required.");
        }
        rotation.setRecurrence(request.get("Recurrence").deepCopy());
        rotations.put(rotation.getRotationArn(), rotation);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deleteRotation(JsonNode request) {
        RotationRecord rotation = requireRotation(requireText(request, "RotationId"));
        overrides.values().removeIf(override -> rotation.getRotationArn().equals(override.getRotationArn()));
        rotations.remove(rotation.getRotationArn());
        return objectMapper.createObjectNode();
    }

    public ObjectNode listRotations() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Rotations");
        rotations.values().stream()
                .sorted(Comparator.comparing(RotationRecord::getName, Comparator.nullsLast(String::compareTo)))
                .forEach(rotation -> list.add(rotationDetail(rotation)));
        return response;
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        Map<String, String> tags = tagsOf(requireText(request, "ResourceARN"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Tags");
        tags.forEach((key, value) -> {
            ObjectNode tag = list.addObject();
            tag.put("Key", key);
            tag.put("Value", value);
        });
        return response;
    }

    public synchronized ObjectNode tagResource(JsonNode request) {
        String arn = requireText(request, "ResourceARN");
        Map<String, String> current = new LinkedHashMap<>(tagsOf(arn));
        current.putAll(readTagList(request.get("Tags")));
        writeTags(arn, current);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request) {
        String arn = requireText(request, "ResourceARN");
        Map<String, String> current = new LinkedHashMap<>(tagsOf(arn));
        if (request.has("TagKeys") && request.get("TagKeys").isArray()) {
            for (JsonNode key : request.get("TagKeys")) {
                current.remove(key.asText());
            }
        }
        writeTags(arn, current);
        return objectMapper.createObjectNode();
    }

    public ObjectNode getContactPolicy(JsonNode request, String region) {
        ContactRecord contact = requireContact(requireText(request, "ContactArn"), region);
        if (contact.getPolicy() == null || contact.getPolicy().isBlank()) {
            throw notFound(contact.getContactArn(), "contact");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ContactArn", contact.getContactArn());
        response.put("Policy", contact.getPolicy());
        return response;
    }

    public synchronized ObjectNode putContactPolicy(JsonNode request, String region) {
        ContactRecord contact = requireContact(requireText(request, "ContactArn"), region);
        contact.setPolicy(requireText(request, "Policy"));
        contacts.put(contact.getContactArn(), contact);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode sendActivationCode(JsonNode request) {
        ContactChannelRecord channel = requireChannel(requireText(request, "ContactChannelId"));
        channel.setActivationCode("000000");
        channels.put(channel.getContactChannelArn(), channel);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode activateContactChannel(JsonNode request) {
        ContactChannelRecord channel = requireChannel(requireText(request, "ContactChannelId"));
        requireText(request, "ActivationCode");
        channel.setActivationStatus("ACTIVATED");
        channels.put(channel.getContactChannelArn(), channel);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deactivateContactChannel(JsonNode request) {
        ContactChannelRecord channel = requireChannel(requireText(request, "ContactChannelId"));
        channel.setActivationStatus("NOT_ACTIVATED");
        channels.put(channel.getContactChannelArn(), channel);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listRotationShifts(JsonNode request) {
        RotationRecord rotation = requireRotationArn(requireText(request, "RotationId"));
        long start = optionalEpoch(request, "StartTime", System.currentTimeMillis() / 1000);
        long end = requireEpoch(request, "EndTime");
        ObjectNode response = objectMapper.createObjectNode();
        response.set("RotationShifts", shifts(rotation.getContactIds(), start, end));
        return response;
    }

    public ObjectNode listPreviewRotationShifts(JsonNode request) {
        List<String> members = readStringArray(request, "Members");
        if (members.isEmpty()) {
            throw validation("Members is required.");
        }
        if (!request.has("Recurrence") || request.get("Recurrence") == null
                || !request.get("Recurrence").isObject()) {
            throw validation("Recurrence is required.");
        }
        requireText(request, "TimeZoneId");
        long start = optionalEpoch(request, "StartTime", System.currentTimeMillis() / 1000);
        long end = requireEpoch(request, "EndTime");
        ObjectNode response = objectMapper.createObjectNode();
        response.set("RotationShifts", shifts(members, start, end));
        return response;
    }

    public synchronized ObjectNode createRotationOverride(JsonNode request) {
        RotationRecord rotation = requireRotationArn(requireText(request, "RotationId"));
        List<String> newContactIds = readStringArray(request, "NewContactIds");
        if (newContactIds.isEmpty()) {
            throw validation("NewContactIds is required.");
        }
        RotationOverrideRecord override = new RotationOverrideRecord();
        override.setRotationOverrideId(UUID.randomUUID().toString());
        override.setRotationArn(rotation.getRotationArn());
        override.setNewContactIds(newContactIds);
        override.setStartTime(requireEpoch(request, "StartTime"));
        override.setEndTime(requireEpoch(request, "EndTime"));
        override.setCreateTime(System.currentTimeMillis() / 1000);
        overrides.put(override.getRotationOverrideId(), override);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("RotationOverrideId", override.getRotationOverrideId());
        return response;
    }

    public ObjectNode getRotationOverride(JsonNode request) {
        RotationRecord rotation = requireRotationArn(requireText(request, "RotationId"));
        String overrideId = requireText(request, "RotationOverrideId");
        RotationOverrideRecord override = overrides.get(overrideId);
        if (override == null || !rotation.getRotationArn().equals(override.getRotationArn())) {
            throw notFound(overrideId, "rotation-override");
        }
        ObjectNode response = overrideNode(override);
        response.put("RotationArn", override.getRotationArn());
        return response;
    }

    public ObjectNode listRotationOverrides(JsonNode request) {
        RotationRecord rotation = requireRotationArn(requireText(request, "RotationId"));
        long start = requireEpoch(request, "StartTime");
        long end = requireEpoch(request, "EndTime");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("RotationOverrides");
        for (RotationOverrideRecord override : overrides.values()) {
            if (!rotation.getRotationArn().equals(override.getRotationArn())) {
                continue;
            }
            if (override.getEndTime() <= start || override.getStartTime() >= end) {
                continue;
            }
            list.add(overrideNode(override));
        }
        return response;
    }

    public synchronized ObjectNode deleteRotationOverride(JsonNode request) {
        getRotationOverride(request);
        overrides.remove(requireText(request, "RotationOverrideId"));
        return objectMapper.createObjectNode();
    }

    public ObjectNode describeEngagement(JsonNode request) {
        String engagementId = requireText(request, "EngagementId");
        EngagementRecord engagement = engagements.get(engagementId);
        if (engagement == null) {
            throw notFound(engagementId, "engagement");
        }
        return engagementDetail(engagement);
    }

    public ObjectNode listEngagements() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Engagements");
        for (EngagementRecord engagement : engagements.values()) {
            ObjectNode node = list.addObject();
            node.put("EngagementArn", engagement.getEngagementArn());
            node.put("ContactArn", engagement.getContactArn());
            node.put("Sender", engagement.getSender());
            if (engagement.getIncidentId() != null) {
                node.put("IncidentId", engagement.getIncidentId());
            }
            node.put("StartTime", engagement.getStartTime());
            if (engagement.getStopTime() != null) {
                node.put("StopTime", engagement.getStopTime());
            }
        }
        return response;
    }

    public synchronized ObjectNode startEngagement(JsonNode request, String region) {
        ContactRecord contact = requireContact(requireText(request, "ContactId"), region);
        String uuid = UUID.randomUUID().toString();
        String engagementArn = "arn:aws:ssm-contacts:" + region + ":" + account()
                + ":engagement/" + contact.getAlias() + "/" + uuid;
        long now = System.currentTimeMillis() / 1000;
        EngagementRecord engagement = new EngagementRecord();
        engagement.setEngagementArn(engagementArn);
        engagement.setContactArn(contact.getContactArn());
        engagement.setSender(requireText(request, "Sender"));
        engagement.setSubject(requireText(request, "Subject"));
        engagement.setContent(requireText(request, "Content"));
        if (request.hasNonNull("PublicSubject")) {
            engagement.setPublicSubject(request.get("PublicSubject").asText());
        }
        if (request.hasNonNull("PublicContent")) {
            engagement.setPublicContent(request.get("PublicContent").asText());
        }
        if (request.hasNonNull("IncidentId")) {
            engagement.setIncidentId(request.get("IncidentId").asText());
        }
        engagement.setStartTime(now);
        engagements.put(engagementArn, engagement);

        PageRecord page = new PageRecord();
        page.setPageArn("arn:aws:ssm-contacts:" + region + ":" + account()
                + ":page/" + contact.getAlias() + "/" + uuid);
        page.setEngagementArn(engagementArn);
        page.setContactArn(contact.getContactArn());
        page.setSender(engagement.getSender());
        page.setSubject(engagement.getSubject());
        page.setContent(engagement.getContent());
        page.setPublicSubject(engagement.getPublicSubject());
        page.setPublicContent(engagement.getPublicContent());
        page.setIncidentId(engagement.getIncidentId());
        page.setSentTime(now);
        page.setDeliveryTime(now);
        pages.put(page.getPageArn(), page);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("EngagementArn", engagementArn);
        return response;
    }

    public synchronized ObjectNode stopEngagement(JsonNode request) {
        String engagementId = requireText(request, "EngagementId");
        EngagementRecord engagement = engagements.get(engagementId);
        if (engagement == null) {
            throw notFound(engagementId, "engagement");
        }
        engagement.setStopTime(System.currentTimeMillis() / 1000);
        engagements.put(engagement.getEngagementArn(), engagement);
        return objectMapper.createObjectNode();
    }

    public ObjectNode describePage(JsonNode request) {
        PageRecord page = requirePage(requireText(request, "PageId"));
        ObjectNode node = objectMapper.createObjectNode();
        node.put("PageArn", page.getPageArn());
        node.put("EngagementArn", page.getEngagementArn());
        node.put("ContactArn", page.getContactArn());
        node.put("Sender", page.getSender());
        node.put("Subject", page.getSubject());
        node.put("Content", page.getContent());
        if (page.getPublicSubject() != null) {
            node.put("PublicSubject", page.getPublicSubject());
        }
        if (page.getPublicContent() != null) {
            node.put("PublicContent", page.getPublicContent());
        }
        if (page.getIncidentId() != null) {
            node.put("IncidentId", page.getIncidentId());
        }
        node.put("SentTime", page.getSentTime());
        if (page.getReadTime() != null) {
            node.put("ReadTime", page.getReadTime());
        }
        if (page.getDeliveryTime() != null) {
            node.put("DeliveryTime", page.getDeliveryTime());
        }
        return node;
    }

    public ObjectNode listPagesByContact(JsonNode request, String region) {
        ContactRecord contact = requireContact(requireText(request, "ContactId"), region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Pages");
        for (PageRecord page : pages.values()) {
            if (contact.getContactArn().equals(page.getContactArn())) {
                list.add(pageSummary(page));
            }
        }
        return response;
    }

    public ObjectNode listPagesByEngagement(JsonNode request) {
        String engagementId = requireText(request, "EngagementId");
        if (!engagements.containsKey(engagementId)) {
            throw notFound(engagementId, "engagement");
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Pages");
        for (PageRecord page : pages.values()) {
            if (engagementId.equals(page.getEngagementArn())) {
                list.add(pageSummary(page));
            }
        }
        return response;
    }

    public ObjectNode listPageReceipts(JsonNode request) {
        PageRecord page = requirePage(requireText(request, "PageId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode receipts = response.putArray("Receipts");
        ObjectNode sent = receipts.addObject();
        sent.put("ReceiptType", "SENT");
        sent.put("ReceiptTime", page.getSentTime());
        if (page.getDeliveryTime() != null) {
            ObjectNode delivered = receipts.addObject();
            delivered.put("ReceiptType", "DELIVERED");
            delivered.put("ReceiptTime", page.getDeliveryTime());
        }
        return response;
    }

    public ObjectNode listPageResolutions(JsonNode request) {
        PageRecord page = requirePage(requireText(request, "PageId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode resolutions = response.putArray("PageResolutions");
        ObjectNode contact = resolutions.addObject();
        contact.put("ContactArn", page.getContactArn());
        contact.put("Type", "PERSONAL");
        contact.put("StageIndex", 0);
        return response;
    }

    public synchronized ObjectNode acceptPage(JsonNode request) {
        PageRecord page = requirePage(requireText(request, "PageId"));
        page.setAccepted(true);
        page.setReadTime(System.currentTimeMillis() / 1000);
        pages.put(page.getPageArn(), page);
        return objectMapper.createObjectNode();
    }

    private ObjectNode contactDetail(ContactRecord contact) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ContactArn", contact.getContactArn());
        response.put("Alias", contact.getAlias());
        if (contact.getDisplayName() != null) {
            response.put("DisplayName", contact.getDisplayName());
        }
        response.put("Type", contact.getType());
        response.set("Plan", planOf(contact));
        return response;
    }

    private ObjectNode channelDetail(ContactChannelRecord channel) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ContactArn", channel.getContactArn());
        response.put("ContactChannelArn", channel.getContactChannelArn());
        response.put("Name", channel.getName());
        response.put("Type", channel.getType());
        if (channel.getDeliveryAddress() != null) {
            response.set("DeliveryAddress", channel.getDeliveryAddress());
        }
        if (channel.getActivationStatus() != null) {
            response.put("ActivationStatus", channel.getActivationStatus());
        }
        return response;
    }

    private ObjectNode rotationDetail(RotationRecord rotation) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("RotationArn", rotation.getRotationArn());
        response.put("Name", rotation.getName());
        ArrayNode contactIds = response.putArray("ContactIds");
        if (rotation.getContactIds() != null) {
            rotation.getContactIds().forEach(contactIds::add);
        }
        response.put("StartTime", rotation.getStartTime() == null ? 0L : rotation.getStartTime());
        response.put("TimeZoneId", rotation.getTimeZoneId());
        if (rotation.getRecurrence() != null) {
            response.set("Recurrence", rotation.getRecurrence());
        }
        return response;
    }

    private JsonNode planOf(ContactRecord contact) {
        return contact.getPlan() != null ? contact.getPlan() : emptyPlan();
    }

    private JsonNode copyOrEmptyPlan(JsonNode plan) {
        if (plan == null || plan.isNull() || !plan.isObject()) {
            return emptyPlan();
        }
        return plan.deepCopy();
    }

    private ObjectNode emptyPlan() {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.putArray("Stages");
        return plan;
    }

    private ContactRecord requireContact(String id, String region) {
        if (id == null || id.isBlank()) {
            throw validation("ContactId is required.");
        }
        ContactRecord byArn = contacts.get(id);
        if (byArn != null) {
            return byArn;
        }
        String alias = id;
        if (id.startsWith("arn:")) {
            int slash = id.lastIndexOf('/');
            alias = slash >= 0 ? id.substring(slash + 1) : id;
            ContactRecord named = findContactByAlias(alias);
            if (named != null && named.getContactArn().equals(id)) {
                return named;
            }
            throw notFound(id, "contact");
        }
        ContactRecord named = findContactByAlias(alias);
        if (named != null) {
            return named;
        }
        ContactRecord generated = contacts.get(contactArn(region, alias));
        if (generated != null) {
            return generated;
        }
        throw notFound(id, "contact");
    }

    private ContactChannelRecord requireChannel(String id) {
        if (id == null || id.isBlank()) {
            throw validation("ContactChannelId is required.");
        }
        ContactChannelRecord channel = channels.get(id);
        if (channel == null) {
            throw notFound(id, "contact-channel");
        }
        return channel;
    }

    private RotationRecord requireRotation(String id) {
        if (id == null || id.isBlank()) {
            throw validation("RotationId is required.");
        }
        RotationRecord rotation = rotations.get(id);
        if (rotation != null) {
            return rotation;
        }
        RotationRecord named = findRotationByName(id.startsWith("arn:") ? lastSegment(id) : id);
        if (named != null && (id.equals(named.getRotationArn()) || id.equals(named.getName()))) {
            return named;
        }
        throw notFound(id, "rotation");
    }

    /**
     * Rotation data-plane APIs (shifts/overrides) report an unresolvable ARN as
     * {@code ValidationException} ("Invalid resource Arn") rather than
     * {@code ResourceNotFoundException}.
     */
    private RotationRecord requireRotationArn(String id) {
        try {
            return requireRotation(id);
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                throw invalidRotationArn(id);
            }
            throw e;
        }
    }

    private PageRecord requirePage(String pageId) {
        if (pageId == null || pageId.isBlank()) {
            throw validation("PageId is required.");
        }
        PageRecord page = pages.get(pageId);
        if (page == null) {
            throw notFound(pageId, "page");
        }
        return page;
    }

    private ArrayNode shifts(List<String> contactIds, long start, long end) {
        ArrayNode list = objectMapper.createArrayNode();
        if (contactIds == null || contactIds.isEmpty() || end <= start) {
            return list;
        }
        ObjectNode shift = list.addObject();
        ArrayNode ids = shift.putArray("ContactIds");
        contactIds.forEach(ids::add);
        shift.put("StartTime", start);
        shift.put("EndTime", Math.min(end, start + 86400));
        shift.put("Type", "REGULAR");
        return list;
    }

    private ObjectNode overrideNode(RotationOverrideRecord override) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("RotationOverrideId", override.getRotationOverrideId());
        ArrayNode ids = node.putArray("NewContactIds");
        if (override.getNewContactIds() != null) {
            override.getNewContactIds().forEach(ids::add);
        }
        node.put("StartTime", override.getStartTime());
        node.put("EndTime", override.getEndTime());
        node.put("CreateTime", override.getCreateTime());
        return node;
    }

    private ObjectNode engagementDetail(EngagementRecord engagement) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ContactArn", engagement.getContactArn());
        node.put("EngagementArn", engagement.getEngagementArn());
        node.put("Sender", engagement.getSender());
        node.put("Subject", engagement.getSubject());
        node.put("Content", engagement.getContent());
        if (engagement.getPublicSubject() != null) {
            node.put("PublicSubject", engagement.getPublicSubject());
        }
        if (engagement.getPublicContent() != null) {
            node.put("PublicContent", engagement.getPublicContent());
        }
        if (engagement.getIncidentId() != null) {
            node.put("IncidentId", engagement.getIncidentId());
        }
        node.put("StartTime", engagement.getStartTime());
        if (engagement.getStopTime() != null) {
            node.put("StopTime", engagement.getStopTime());
        }
        return node;
    }

    private ObjectNode pageSummary(PageRecord page) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("PageArn", page.getPageArn());
        node.put("EngagementArn", page.getEngagementArn());
        node.put("ContactArn", page.getContactArn());
        node.put("Sender", page.getSender());
        if (page.getIncidentId() != null) {
            node.put("IncidentId", page.getIncidentId());
        }
        node.put("SentTime", page.getSentTime());
        if (page.getDeliveryTime() != null) {
            node.put("DeliveryTime", page.getDeliveryTime());
        }
        if (page.getReadTime() != null) {
            node.put("ReadTime", page.getReadTime());
        }
        return node;
    }

    private ContactRecord findContactByAlias(String alias) {
        for (ContactRecord contact : contacts.values()) {
            if (alias.equals(contact.getAlias())) {
                return contact;
            }
        }
        return null;
    }

    private RotationRecord findRotationByName(String name) {
        for (RotationRecord rotation : rotations.values()) {
            if (name.equals(rotation.getName())) {
                return rotation;
            }
        }
        return null;
    }

    private Map<String, String> tagsOf(String arn) {
        ContactRecord contact = contacts.get(arn);
        if (contact != null) {
            return contact.getTags() == null ? Map.of() : contact.getTags();
        }
        RotationRecord rotation = rotations.get(arn);
        if (rotation != null) {
            return rotation.getTags() == null ? Map.of() : rotation.getTags();
        }
        throw notFound(arn, resourceTypeOf(arn));
    }

    private void writeTags(String arn, Map<String, String> tags) {
        ContactRecord contact = contacts.get(arn);
        if (contact != null) {
            contact.setTags(tags);
            contacts.put(arn, contact);
            return;
        }
        RotationRecord rotation = rotations.get(arn);
        if (rotation != null) {
            rotation.setTags(tags);
            rotations.put(arn, rotation);
            return;
        }
        throw notFound(arn, resourceTypeOf(arn));
    }

    private Map<String, String> readTagList(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isArray()) {
            throw validation("Tags must be an array.");
        }
        for (JsonNode tag : tagsNode) {
            if (tag == null || !tag.isObject() || !tag.hasNonNull("Key") || !tag.hasNonNull("Value")) {
                throw validation("Tags contains an invalid key or value.");
            }
            tags.put(tag.get("Key").asText(), tag.get("Value").asText());
        }
        return tags;
    }

    private List<String> readStringArray(JsonNode parent, String field) {
        List<String> values = new ArrayList<>();
        if (!parent.has(field) || parent.get(field).isNull()) {
            return values;
        }
        JsonNode array = parent.get(field);
        if (!array.isArray()) {
            throw validation(field + " must be an array.");
        }
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static Long readEpoch(JsonNode parent, String field) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (value.isNumber()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText());
            } catch (NumberFormatException ignored) {
                throw validation(field + " must be epoch seconds.");
            }
        }
        throw validation(field + " must be epoch seconds.");
    }

    private static long requireEpoch(JsonNode parent, String field) {
        Long value = readEpoch(parent, field);
        if (value == null) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static long optionalEpoch(JsonNode parent, String field, long fallback) {
        Long value = readEpoch(parent, field);
        return value == null ? fallback : value;
    }

    private String contactArn(String region, String alias) {
        return "arn:aws:ssm-contacts:" + region + ":" + account() + ":contact/" + alias;
    }

    private String account() {
        return regionResolver.getAccountId();
    }

    private static String lastSegment(String arn) {
        int slash = arn.lastIndexOf('/');
        return slash >= 0 ? arn.substring(slash + 1) : arn;
    }

    private static String resourceTypeOf(String arn) {
        if (arn.contains(":rotation/")) {
            return "rotation";
        }
        if (arn.contains(":contact-channel/")) {
            return "contact-channel";
        }
        if (arn.contains(":engagement/")) {
            return "engagement";
        }
        if (arn.contains(":page/")) {
            return "page";
        }
        return "contact";
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw validation(field + " is required.");
        }
        return value.asText();
    }

    private static AwsException notFound(String id, String type) {
        return new AwsException(
                "ResourceNotFoundException",
                "Resource " + id + " was not found.",
                404,
                Map.of("ResourceId", id, "ResourceType", type));
    }

    private static AwsException invalidRotationArn(String rotationId) {
        return new AwsException(
                "ValidationException",
                "Invalid value provided - Invalid resource Arn " + rotationId,
                400);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
