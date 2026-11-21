package io.floci.aws.services.ssmcontacts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ses.SesService;
import io.floci.aws.services.ses.model.EmailContent;
import io.floci.aws.services.ses.model.SendEmailRequest;
import io.floci.aws.services.sns.SnsService;
import io.floci.aws.services.ssmincidents.SsmIncidentsService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Incident Manager contacts: contacts and their channels, engagement plans, on-call rotations with overrides, and
 * engagements that page people. Every operation needs the account's Incident Manager replication set.
 *
 * <p>Channels are activated with a code delivered to them, and engagements deliver pages for real: email through
 * SES (the {@code /_aws/ses} mailbox) and SMS and voice through SNS's phone inbox. A page carries an accept code.
 * A personal contact's plan delivers to its activated channels stage by stage, retrying on each channel's interval;
 * an escalation plan engages its target contacts stage by stage until an essential one reads a page; an on-call
 * schedule engages whoever its rotations have on call. Stages are timed from the engagement's start and delivered
 * on schedule.
 */
@ApplicationScoped
public class SsmContactsService implements Resettable {

    private static final Logger LOG = Logger.getLogger(SsmContactsService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_DELIVERIES_PER_TARGET = 10;

    private final AccountAwareStorageBackend<ObjectNode> contacts;
    private final AccountAwareStorageBackend<ObjectNode> channels;
    private final AccountAwareStorageBackend<ObjectNode> rotations;
    private final AccountAwareStorageBackend<ObjectNode> engagements;
    private final AccountAwareStorageBackend<ObjectNode> pages;
    private final ObjectMapper mapper;
    private final Provider<SsmIncidentsService> incidents;
    private final Provider<SesService> ses;
    private final Provider<SnsService> sns;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ssm-contacts-engagements");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public SsmContactsService(StorageFactory storageFactory, ObjectMapper mapper,
                              Provider<SsmIncidentsService> incidents, Provider<SesService> ses,
                              Provider<SnsService> sns) {
        this.contacts = storageFactory.create("ssm-contacts", "contacts.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.channels = storageFactory.create("ssm-contacts", "channels.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.rotations = storageFactory.create("ssm-contacts", "rotations.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.engagements = storageFactory.create("ssm-contacts", "engagements.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.pages = storageFactory.create("ssm-contacts", "pages.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.incidents = incidents;
        this.ses = ses;
        this.sns = sns;
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    @Override
    public void clear() {
        contacts.clear();
        channels.clear();
        rotations.clear();
        engagements.clear();
        pages.clear();
    }

    /** Offboarding: the account's replication set in the partition is gone, and its contacts data with it. */
    public void offboard(String partition) {
        for (AccountAwareStorageBackend<ObjectNode> store : List.of(contacts, channels, rotations, engagements,
                pages)) {
            for (String key : store.scan(k -> true).stream().map(SsmContactsService::arnOf).toList()) {
                if (key != null && partition.equals(AwsArnUtils.parse(key).partition())) {
                    store.delete(key);
                }
            }
        }
    }

    private static String arnOf(ObjectNode record) {
        for (String field : List.of("ContactChannelArn", "PageArn", "EngagementArn", "RotationArn", "ContactArn")) {
            if (record.hasNonNull(field)) {
                return record.get(field).asText();
            }
        }
        return null;
    }

    public ObjectNode handle(String operation, JsonNode in, String region) {
        if (!incidents.get().onboarded(region)) {
            throw validation("Account not found for the request");
        }
        return switch (operation) {
            case "CreateContact" -> createContact(in, region);
            case "GetContact" -> getContact(in, region);
            case "ListContacts" -> listContacts(in, region);
            case "UpdateContact" -> updateContact(in, region);
            case "DeleteContact" -> deleteContact(in, region);
            case "PutContactPolicy" -> putPolicy(in, region);
            case "GetContactPolicy" -> getPolicy(in, region);
            case "CreateContactChannel" -> createChannel(in, region);
            case "GetContactChannel" -> channelView(channel(in.path("ContactChannelId").asText(), region));
            case "ListContactChannels" -> listChannels(in, region);
            case "UpdateContactChannel" -> updateChannel(in, region);
            case "DeleteContactChannel" -> deleteChannel(in, region);
            case "SendActivationCode" -> sendActivationCode(in, region);
            case "ActivateContactChannel" -> activateChannel(in, region);
            case "DeactivateContactChannel" -> deactivateChannel(in, region);
            case "CreateRotation" -> createRotation(in, region);
            case "GetRotation" -> rotationView(rotation(in.path("RotationId").asText(), region));
            case "ListRotations" -> listRotations(in, region);
            case "UpdateRotation" -> updateRotation(in, region);
            case "DeleteRotation" -> deleteRotation(in, region);
            case "CreateRotationOverride" -> createOverride(in, region);
            case "GetRotationOverride" -> getOverride(in, region);
            case "ListRotationOverrides" -> listOverrides(in, region);
            case "DeleteRotationOverride" -> deleteOverride(in, region);
            case "ListRotationShifts" -> listShifts(in, region);
            case "ListPreviewRotationShifts" -> previewShifts(in);
            case "StartEngagement" -> mapper.createObjectNode().put("EngagementArn", startEngagement(
                    in.path("ContactId").asText(), in.path("Sender").asText(), in.path("Subject").asText(),
                    in.path("Content").asText(), in.path("IncidentId").asText(null), region, in));
            case "StopEngagement" -> stopEngagement(in, region);
            case "DescribeEngagement" -> describeEngagement(in, region);
            case "ListEngagements" -> listEngagements(in, region);
            case "DescribePage" -> describePage(in, region);
            case "ListPagesByContact" -> listPagesByContact(in, region);
            case "ListPagesByEngagement" -> listPagesByEngagement(in, region);
            case "ListPageReceipts" -> listReceipts(in, region);
            case "ListPageResolutions" -> listResolutions(in, region);
            case "AcceptPage" -> acceptPage(in, region);
            case "TagResource" -> tag(in, region, true);
            case "UntagResource" -> tag(in, region, false);
            case "ListTagsForResource" -> listTags(in, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation + ".",
                    400);
        };
    }

    // ---------- contacts ----------

    /** Whether the contact ARN names a contact of the calling account. Read by Incident Manager response plans. */
    public boolean contactExists(String contactArn) {
        if (!AwsArnUtils.isArnFor(contactArn, "ssm-contacts")) {
            return false;
        }
        return contacts.get(contactArn).isPresent();
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("ssm-contacts", region, contacts.accountId(), resource).toString();
    }

    private ObjectNode createContact(JsonNode in, String region) {
        String alias = in.path("Alias").asText();
        String arn = arn(region, "contact/" + alias);
        if (contacts.get(arn).isPresent()) {
            throw conflict("A contact with the alias " + alias + " already exists.");
        }
        ObjectNode contact = mapper.createObjectNode();
        contact.put("ContactArn", arn).put("Alias", alias).put("Type", in.path("Type").asText());
        if (in.hasNonNull("DisplayName")) {
            contact.put("DisplayName", in.get("DisplayName").asText());
        }
        contact.set("Plan", checkedPlan(contact, in.path("Plan"), region));
        contact.set("_tags", tagMap(in.path("Tags")));
        contacts.put(arn, contact);
        return mapper.createObjectNode().put("ContactArn", arn);
    }

    /** The plan, checked against the contact's type: its own channels, contacts to escalate to, or rotations. */
    private ObjectNode checkedPlan(ObjectNode contact, JsonNode plan, String region) {
        String type = contact.path("Type").asText();
        ObjectNode checked = mapper.createObjectNode();
        ArrayNode stages = checked.putArray("Stages");
        ArrayNode rotationIds = mapper.createArrayNode();
        if ("ONCALL_SCHEDULE".equals(type)) {
            if (plan.path("Stages").size() > 0) {
                throw validation("An on-call schedule's plan names rotations, not stages.");
            }
            if (plan.path("RotationIds").isEmpty()) {
                throw validation("An on-call schedule needs at least one rotation.");
            }
            for (JsonNode id : plan.path("RotationIds")) {
                rotationIds.add(rotation(id.asText(), region).path("RotationArn").asText());
            }
            checked.set("RotationIds", rotationIds);
            return checked;
        }
        if (plan.path("RotationIds").size() > 0) {
            throw validation("Only an on-call schedule's plan names rotations.");
        }
        for (JsonNode stage : plan.path("Stages")) {
            ObjectNode copy = stage.deepCopy();
            for (JsonNode target : stage.path("Targets")) {
                if ("PERSONAL".equals(type)) {
                    if (!target.has("ChannelTargetInfo")) {
                        throw validation("A personal contact's plan targets its own contact channels.");
                    }
                    ObjectNode channel = channel(target.path("ChannelTargetInfo").path("ContactChannelId").asText(),
                            region);
                    if (!contact.path("ContactArn").asText().equals(channel.path("ContactArn").asText())) {
                        throw validation("The contact channel " + channel.path("ContactChannelArn").asText()
                                + " belongs to another contact.");
                    }
                } else {
                    if (!target.has("ContactTargetInfo")) {
                        throw validation("An escalation plan targets contacts.");
                    }
                    ObjectNode targetContact = contact(target.path("ContactTargetInfo").path("ContactId").asText(),
                            region);
                    if ("ESCALATION".equals(targetContact.path("Type").asText())) {
                        throw validation("An escalation plan can't target another escalation plan.");
                    }
                }
            }
            stages.add(copy);
        }
        return checked;
    }

    private ObjectNode contact(String id, String region) {
        String arn = AwsArnUtils.looksLikeArn(id) ? id : arn(region, "contact/" + id);
        return contacts.get(arn).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("The contact " + id + " doesn't exist."));
    }

    private ObjectNode getContact(JsonNode in, String region) {
        ObjectNode contact = contact(in.path("ContactId").asText(), region);
        ObjectNode out = pick(contact, "ContactArn", "Alias", "DisplayName", "Type");
        out.set("Plan", contact.get("Plan").deepCopy());
        return out;
    }

    private ObjectNode listContacts(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode contact : contacts.scan(key -> true)) {
            if (!AwsArnUtils.parse(contact.path("ContactArn").asText()).region().equals(region)) {
                continue;
            }
            if (in.hasNonNull("AliasPrefix") && !contact.path("Alias").asText()
                    .startsWith(in.get("AliasPrefix").asText())) {
                continue;
            }
            if (in.hasNonNull("Type") && !in.get("Type").asText().equals(contact.path("Type").asText())) {
                continue;
            }
            items.add(pick(contact, "ContactArn", "Alias", "DisplayName", "Type"));
        }
        items.sort(Comparator.comparing(item -> item.path("Alias").asText()));
        return page(items, in, "Contacts");
    }

    private ObjectNode updateContact(JsonNode in, String region) {
        ObjectNode contact = contact(in.path("ContactId").asText(), region);
        if (in.hasNonNull("DisplayName")) {
            contact.put("DisplayName", in.get("DisplayName").asText());
        }
        if (in.has("Plan")) {
            contact.set("Plan", checkedPlan(contact, in.get("Plan"), region));
        }
        contacts.put(contact.path("ContactArn").asText(), contact);
        return mapper.createObjectNode();
    }

    private ObjectNode deleteContact(JsonNode in, String region) {
        ObjectNode contact = contact(in.path("ContactId").asText(), region);
        String arn = contact.path("ContactArn").asText();
        for (ObjectNode rotation : rotations.scan(key -> true)) {
            for (JsonNode member : rotation.path("ContactIds")) {
                if (arn.equals(member.asText())) {
                    throw conflict("The contact is a member of the rotation " + rotation.path("Name").asText()
                            + ". Remove it from the rotation first.");
                }
            }
        }
        for (ObjectNode other : contacts.scan(key -> true)) {
            for (JsonNode stage : other.path("Plan").path("Stages")) {
                for (JsonNode target : stage.path("Targets")) {
                    if (arn.equals(target.path("ContactTargetInfo").path("ContactId").asText())) {
                        throw conflict("The escalation plan of " + other.path("Alias").asText()
                                + " engages this contact. Remove it from that plan first.");
                    }
                }
            }
        }
        for (ObjectNode channel : channels.scan(key -> true)) {
            if (arn.equals(channel.path("ContactArn").asText())) {
                channels.delete(channel.path("ContactChannelArn").asText());
            }
        }
        contacts.delete(arn);
        return mapper.createObjectNode();
    }

    private ObjectNode putPolicy(JsonNode in, String region) {
        ObjectNode contact = contact(in.path("ContactArn").asText(), region);
        String policy = in.path("Policy").asText();
        try {
            if (!mapper.readTree(policy).has("Statement")) {
                throw validation("The policy needs a Statement.");
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw validation("The policy isn't valid JSON.");
        }
        contact.put("_policy", policy);
        contacts.put(contact.path("ContactArn").asText(), contact);
        return mapper.createObjectNode();
    }

    private ObjectNode getPolicy(JsonNode in, String region) {
        ObjectNode contact = contact(in.path("ContactArn").asText(), region);
        ObjectNode out = mapper.createObjectNode().put("ContactArn", contact.path("ContactArn").asText());
        if (contact.hasNonNull("_policy")) {
            out.put("Policy", contact.get("_policy").asText());
        }
        return out;
    }

    // ---------- channels ----------

    private ObjectNode createChannel(JsonNode in, String region) {
        ObjectNode contact = contact(in.path("ContactId").asText(), region);
        if (!"PERSONAL".equals(contact.path("Type").asText())) {
            throw validation("Only a personal contact has contact channels.");
        }
        String type = in.path("Type").asText();
        String address = in.path("DeliveryAddress").path("SimpleAddress").asText();
        requireAddress(type, address);
        String contactArn = contact.path("ContactArn").asText();
        for (ObjectNode existing : channels.scan(key -> true)) {
            if (contactArn.equals(existing.path("ContactArn").asText())
                    && type.equals(existing.path("Type").asText())
                    && address.equals(existing.path("DeliveryAddress").path("SimpleAddress").asText())) {
                throw conflict("The contact already has this " + type + " channel.");
            }
        }
        String arn = arn(region, "contact-channel/" + contact.path("Alias").asText() + "/" + UUID.randomUUID());
        ObjectNode channel = mapper.createObjectNode();
        channel.put("ContactArn", contactArn).put("ContactChannelArn", arn).put("Name", in.path("Name").asText())
                .put("Type", type).put("ActivationStatus", "NOT_ACTIVATED");
        channel.putObject("DeliveryAddress").put("SimpleAddress", address);
        channels.put(arn, channel);
        if (!in.path("DeferActivation").asBoolean(false)) {
            sendCode(channel, region);
        }
        return mapper.createObjectNode().put("ContactChannelArn", arn);
    }

    private static void requireAddress(String type, String address) {
        boolean valid = "EMAIL".equals(type) ? address.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
                : address.matches("\\+[1-9]\\d{6,14}");
        if (!valid) {
            throw validation("EMAIL".equals(type) ? "The email address " + address + " isn't valid."
                    : "The phone number " + address + " isn't in E.164 format, such as +12065550100.");
        }
    }

    private ObjectNode channel(String id, String region) {
        return channels.get(id).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("The contact channel " + id + " doesn't exist."));
    }

    private ObjectNode channelView(ObjectNode channel) {
        ObjectNode view = channel.deepCopy();
        view.remove("_code");
        return view;
    }

    private ObjectNode listChannels(JsonNode in, String region) {
        String contactArn = contact(in.path("ContactId").asText(), region).path("ContactArn").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode channel : channels.scan(key -> true)) {
            if (contactArn.equals(channel.path("ContactArn").asText())) {
                items.add(channelView(channel));
            }
        }
        items.sort(Comparator.comparing(item -> item.path("Name").asText()));
        return page(items, in, "ContactChannels");
    }

    private ObjectNode updateChannel(JsonNode in, String region) {
        ObjectNode channel = channel(in.path("ContactChannelId").asText(), region);
        if (in.hasNonNull("Name")) {
            channel.put("Name", in.get("Name").asText());
        }
        if (in.has("DeliveryAddress")) {
            String address = in.path("DeliveryAddress").path("SimpleAddress").asText();
            requireAddress(channel.path("Type").asText(), address);
            if (!address.equals(channel.path("DeliveryAddress").path("SimpleAddress").asText())) {
                // A new address has to be activated again.
                channel.putObject("DeliveryAddress").put("SimpleAddress", address);
                channel.put("ActivationStatus", "NOT_ACTIVATED").remove("_code");
            }
        }
        channels.put(channel.path("ContactChannelArn").asText(), channel);
        return mapper.createObjectNode();
    }

    private ObjectNode deleteChannel(JsonNode in, String region) {
        ObjectNode channel = channel(in.path("ContactChannelId").asText(), region);
        String arn = channel.path("ContactChannelArn").asText();
        contacts.get(channel.path("ContactArn").asText()).ifPresent(contact -> {
            for (JsonNode stage : contact.path("Plan").path("Stages")) {
                for (JsonNode target : stage.path("Targets")) {
                    if (arn.equals(target.path("ChannelTargetInfo").path("ContactChannelId").asText())) {
                        throw conflict("The contact's engagement plan uses this channel. Remove it from the plan "
                                + "first.");
                    }
                }
            }
        });
        channels.delete(arn);
        return mapper.createObjectNode();
    }

    private ObjectNode sendActivationCode(JsonNode in, String region) {
        ObjectNode channel = channel(in.path("ContactChannelId").asText(), region);
        if ("ACTIVATED".equals(channel.path("ActivationStatus").asText())) {
            throw validation("The contact channel is already activated.");
        }
        sendCode(channel, region);
        return mapper.createObjectNode();
    }

    private void sendCode(ObjectNode channel, String region) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        channel.put("_code", code);
        channels.put(channel.path("ContactChannelArn").asText(), channel);
        deliver(channel, "AWS Incident Manager contact channel activation",
                "Your activation code for the contact channel " + channel.path("Name").asText() + " is " + code
                        + ".", region);
    }

    private ObjectNode activateChannel(JsonNode in, String region) {
        ObjectNode channel = channel(in.path("ContactChannelId").asText(), region);
        String code = in.path("ActivationCode").asText();
        if (!channel.hasNonNull("_code") || !code.equals(channel.get("_code").asText())) {
            throw validation("The activation code isn't valid for this contact channel.");
        }
        channel.put("ActivationStatus", "ACTIVATED").remove("_code");
        channels.put(channel.path("ContactChannelArn").asText(), channel);
        return mapper.createObjectNode();
    }

    private ObjectNode deactivateChannel(JsonNode in, String region) {
        ObjectNode channel = channel(in.path("ContactChannelId").asText(), region);
        channel.put("ActivationStatus", "NOT_ACTIVATED").remove("_code");
        channels.put(channel.path("ContactChannelArn").asText(), channel);
        return mapper.createObjectNode();
    }

    /** Sends a message to a channel: email through SES, SMS and voice through SNS's phone inbox. */
    private void deliver(ObjectNode channel, String subject, String body, String region) {
        String address = channel.path("DeliveryAddress").path("SimpleAddress").asText();
        if ("EMAIL".equals(channel.path("Type").asText())) {
            ses.get().sendServiceEmail(SendEmailRequest.builder()
                    .source("no-reply@" + ServicePrincipals.of("ssm-contacts"))
                    .toAddresses(List.of(address))
                    .region(region)
                    .content(new EmailContent.Simple(subject, body, null, List.of()))
                    .build());
        } else {
            sns.get().publish(null, null, address, subject + "\n" + body, null, null, region);
        }
    }

    // ---------- rotations ----------

    private ObjectNode createRotation(JsonNode in, String region) {
        String name = in.path("Name").asText();
        String arn = arn(region, "rotation/" + name);
        if (rotations.get(arn).isPresent()) {
            throw conflict("A rotation named " + name + " already exists.");
        }
        ObjectNode rotation = mapper.createObjectNode();
        rotation.put("RotationArn", arn).put("Name", name);
        applyRotation(rotation, in, region, true);
        rotation.set("_tags", tagMap(in.path("Tags")));
        rotation.putArray("_overrides");
        rotations.put(arn, rotation);
        return mapper.createObjectNode().put("RotationArn", arn);
    }

    private void applyRotation(ObjectNode rotation, JsonNode in, String region, boolean create) {
        if (in.has("ContactIds") || create) {
            ArrayNode members = mapper.createArrayNode();
            for (JsonNode id : in.path("ContactIds")) {
                ObjectNode member = contact(id.asText(), region);
                if (!"PERSONAL".equals(member.path("Type").asText())) {
                    throw validation("A rotation's members are personal contacts.");
                }
                members.add(member.path("ContactArn").asText());
            }
            if (members.isEmpty() || members.size() > 30) {
                throw validation("A rotation has 1 to 30 members.");
            }
            rotation.set("ContactIds", members);
        }
        if (in.hasNonNull("TimeZoneId")) {
            try {
                ZoneId.of(in.get("TimeZoneId").asText());
            } catch (DateTimeException e) {
                throw validation("The time zone " + in.get("TimeZoneId").asText() + " isn't valid.");
            }
            rotation.put("TimeZoneId", in.get("TimeZoneId").asText());
        }
        if (in.has("StartTime")) {
            rotation.put("StartTime", seconds(in.get("StartTime")));
        } else if (create) {
            rotation.put("StartTime", Instant.now().getEpochSecond());
        }
        JsonNode recurrence = in.path("Recurrence");
        int kinds = (recurrence.path("DailySettings").isEmpty() ? 0 : 1)
                + (recurrence.path("WeeklySettings").isEmpty() ? 0 : 1)
                + (recurrence.path("MonthlySettings").isEmpty() ? 0 : 1);
        if (kinds != 1) {
            throw validation("A rotation's recurrence has exactly one of DailySettings, WeeklySettings or "
                    + "MonthlySettings.");
        }
        if (recurrence.path("NumberOfOnCalls").asInt() > rotation.path("ContactIds").size()) {
            throw validation("NumberOfOnCalls can't be more than the rotation's members.");
        }
        rotation.set("Recurrence", recurrence.deepCopy());
    }

    private ObjectNode rotation(String id, String region) {
        String arn = AwsArnUtils.looksLikeArn(id) ? id : arn(region, "rotation/" + id);
        if (!AwsArnUtils.isArnFor(arn, "ssm-contacts") || !AwsArnUtils.parse(arn).resource().startsWith("rotation/")) {
            throw validation("Invalid value provided - Invalid resource Arn " + id);
        }
        return rotations.get(arn).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("The rotation " + id + " doesn't exist."));
    }

    private ObjectNode rotationView(ObjectNode rotation) {
        ObjectNode view = rotation.deepCopy();
        view.remove("_tags");
        view.remove("_overrides");
        return view;
    }

    private ObjectNode listRotations(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode rotation : rotations.scan(key -> true)) {
            if (AwsArnUtils.parse(rotation.path("RotationArn").asText()).region().equals(region)
                    && (!in.hasNonNull("RotationNamePrefix")
                    || rotation.path("Name").asText().startsWith(in.get("RotationNamePrefix").asText()))) {
                items.add(rotationView(rotation));
            }
        }
        items.sort(Comparator.comparing(item -> item.path("Name").asText()));
        return page(items, in, "Rotations");
    }

    private ObjectNode updateRotation(JsonNode in, String region) {
        ObjectNode rotation = rotation(in.path("RotationId").asText(), region);
        applyRotation(rotation, in, region, false);
        rotations.put(rotation.path("RotationArn").asText(), rotation);
        return mapper.createObjectNode();
    }

    private ObjectNode deleteRotation(JsonNode in, String region) {
        ObjectNode rotation = rotation(in.path("RotationId").asText(), region);
        String arn = rotation.path("RotationArn").asText();
        for (ObjectNode contact : contacts.scan(key -> true)) {
            for (JsonNode id : contact.path("Plan").path("RotationIds")) {
                if (arn.equals(id.asText())) {
                    throw conflict("The on-call schedule " + contact.path("Alias").asText()
                            + " uses this rotation.");
                }
            }
        }
        rotations.delete(arn);
        return mapper.createObjectNode();
    }

    private ObjectNode createOverride(JsonNode in, String region) {
        ObjectNode rotation = rotation(in.path("RotationId").asText(), region);
        long start = seconds(in.get("StartTime"));
        long end = seconds(in.get("EndTime"));
        if (end <= start) {
            throw validation("The override's EndTime must be after its StartTime.");
        }
        ArrayNode members = mapper.createArrayNode();
        for (JsonNode id : in.path("NewContactIds")) {
            String arn = contact(id.asText(), region).path("ContactArn").asText();
            boolean inRotation = false;
            for (JsonNode member : rotation.path("ContactIds")) {
                inRotation |= arn.equals(member.asText());
            }
            if (!inRotation) {
                throw validation("An override's contacts must be members of the rotation.");
            }
            members.add(arn);
        }
        String id = UUID.randomUUID().toString();
        ObjectNode override = mapper.createObjectNode().put("RotationOverrideId", id)
                .put("RotationArn", rotation.path("RotationArn").asText()).put("StartTime", start)
                .put("EndTime", end).put("CreateTime", Instant.now().getEpochSecond());
        override.set("NewContactIds", members);
        ((ArrayNode) rotation.get("_overrides")).add(override);
        rotations.put(rotation.path("RotationArn").asText(), rotation);
        return mapper.createObjectNode().put("RotationOverrideId", id);
    }

    private ObjectNode override(ObjectNode rotation, String id) {
        for (JsonNode override : rotation.path("_overrides")) {
            if (id.equals(override.path("RotationOverrideId").asText())) {
                return (ObjectNode) override;
            }
        }
        throw notFound("The rotation override " + id + " doesn't exist.");
    }

    private ObjectNode getOverride(JsonNode in, String region) {
        return override(rotation(in.path("RotationId").asText(), region), in.path("RotationOverrideId").asText())
                .deepCopy();
    }

    private ObjectNode listOverrides(JsonNode in, String region) {
        ObjectNode rotation = rotation(in.path("RotationId").asText(), region);
        long from = seconds(in.get("StartTime"));
        long to = seconds(in.get("EndTime"));
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode override : rotation.path("_overrides")) {
            if (override.path("EndTime").asLong() > from && override.path("StartTime").asLong() < to) {
                ObjectNode item = ((ObjectNode) override).deepCopy();
                item.remove("RotationArn");
                items.add(item);
            }
        }
        return page(items, in, "RotationOverrides");
    }

    private ObjectNode deleteOverride(JsonNode in, String region) {
        ObjectNode rotation = rotation(in.path("RotationId").asText(), region);
        ArrayNode overrides = (ArrayNode) rotation.get("_overrides");
        override(rotation, in.path("RotationOverrideId").asText());
        for (int i = overrides.size() - 1; i >= 0; i--) {
            if (in.path("RotationOverrideId").asText().equals(overrides.get(i).path("RotationOverrideId").asText())) {
                overrides.remove(i);
            }
        }
        rotations.put(rotation.path("RotationArn").asText(), rotation);
        return mapper.createObjectNode();
    }

    private List<RotationShifts.Shift> shiftsOf(ObjectNode rotation, Instant from, Instant to) {
        List<RotationShifts.Override> overrides = new ArrayList<>();
        for (JsonNode override : rotation.path("_overrides")) {
            overrides.add(new RotationShifts.Override(Instant.ofEpochSecond(override.path("StartTime").asLong()),
                    Instant.ofEpochSecond(override.path("EndTime").asLong()), strings(override.path("NewContactIds"))));
        }
        return RotationShifts.between(strings(rotation.path("ContactIds")),
                Instant.ofEpochSecond(rotation.path("StartTime").asLong()), ZoneId.of(rotation.path("TimeZoneId")
                        .asText()), rotation.path("Recurrence"), overrides, from, to);
    }

    private ObjectNode listShifts(JsonNode in, String region) {
        ObjectNode rotation = rotation(in.path("RotationId").asText(), region);
        Instant from = in.has("StartTime") ? Instant.ofEpochSecond(seconds(in.get("StartTime"))) : Instant.now();
        Instant to = Instant.ofEpochSecond(seconds(in.get("EndTime")));
        requireWindow(from, to);
        return page(shiftNodes(shiftsOf(rotation, from, to)), in, "RotationShifts");
    }

    private ObjectNode previewShifts(JsonNode in) {
        Instant from = in.has("StartTime") ? Instant.ofEpochSecond(seconds(in.get("StartTime"))) : Instant.now();
        Instant to = Instant.ofEpochSecond(seconds(in.get("EndTime")));
        requireWindow(from, to);
        Instant start = in.has("RotationStartTime") ? Instant.ofEpochSecond(seconds(in.get("RotationStartTime")))
                : from;
        ZoneId zone;
        try {
            zone = ZoneId.of(in.path("TimeZoneId").asText());
        } catch (DateTimeException e) {
            throw validation("The time zone " + in.path("TimeZoneId").asText() + " isn't valid.");
        }
        List<RotationShifts.Override> overrides = new ArrayList<>();
        for (JsonNode override : in.path("Overrides")) {
            overrides.add(new RotationShifts.Override(Instant.ofEpochSecond(seconds(override.get("StartTime"))),
                    Instant.ofEpochSecond(seconds(override.get("EndTime"))), strings(override.path("NewMembers"))));
        }
        return page(shiftNodes(RotationShifts.between(strings(in.path("Members")), start, zone,
                in.path("Recurrence"), overrides, from, to)), in, "RotationShifts");
    }

    private static void requireWindow(Instant from, Instant to) {
        if (!to.isAfter(from)) {
            throw validation("EndTime must be after StartTime.");
        }
        if (to.getEpochSecond() - from.getEpochSecond() > 400L * 24 * 3600) {
            throw validation("The time range can't be longer than 400 days.");
        }
    }

    private List<ObjectNode> shiftNodes(List<RotationShifts.Shift> shifts) {
        List<ObjectNode> out = new ArrayList<>();
        for (RotationShifts.Shift shift : shifts) {
            ObjectNode node = mapper.createObjectNode().put("StartTime", shift.start().getEpochSecond())
                    .put("EndTime", shift.end().getEpochSecond())
                    .put("Type", shift.overridden().isEmpty() ? "REGULAR" : "OVERRIDDEN");
            node.set("ContactIds", mapper.valueToTree(shift.contacts()));
            if (!shift.overridden().isEmpty()) {
                node.putObject("ShiftDetails").set("OverriddenContactIds", mapper.valueToTree(shift.overridden()));
            }
            out.add(node);
        }
        return out;
    }

    // ---------- engagements and pages ----------

    /**
     * Engages a contact and returns the engagement ARN. Pages are delivered as the contact's plan reaches them;
     * also called when Incident Manager starts an incident whose response plan engages the contact.
     */
    public String startEngagement(String contactId, String sender, String subject, String content, String incidentId,
                                  String region) {
        return startEngagement(contactId, sender, subject, content, incidentId, region, mapper.createObjectNode());
    }

    private String startEngagement(String contactId, String sender, String subject, String content,
                                   String incidentId, String region, JsonNode in) {
        ObjectNode contact = contact(contactId, region);
        String token = in.path("IdempotencyToken").asText(null);
        if (token != null) {
            for (ObjectNode existing : engagements.scan(key -> true)) {
                if (token.equals(existing.path("_").path("token").asText())) {
                    return existing.path("EngagementArn").asText();
                }
            }
        }
        String arn = arn(region, "engagement/" + contact.path("Alias").asText() + "/" + UUID.randomUUID());
        long now = Instant.now().getEpochSecond();
        ObjectNode engagement = mapper.createObjectNode();
        engagement.put("EngagementArn", arn).put("ContactArn", contact.path("ContactArn").asText())
                .put("Sender", sender).put("Subject", subject).put("Content", content).put("StartTime", now);
        for (String field : List.of("PublicSubject", "PublicContent")) {
            if (in.hasNonNull(field)) {
                engagement.put(field, in.get(field).asText());
            }
        }
        if (incidentId != null) {
            engagement.put("IncidentId", incidentId);
        }
        ObjectNode meta = engagement.putObject("_");
        meta.put("region", region).put("started", Instant.now().toEpochMilli());
        if (token != null) {
            meta.put("token", token);
        }
        meta.putObject("pages");
        meta.putArray("done");
        engagements.put(arn, engagement);
        advance(arn);
        return arn;
    }

    /** One step of an engagement's schedule: page a contact, or deliver its page to one of its channels. */
    private record Step(long atMillis, String key, String contactArn, String channelArn, ArrayNode resolution,
                        int stage, boolean escalating) {
    }

    /** Applies every step of the engagement that is due, and schedules the next one. */
    private synchronized void advance(String engagementArn) {
        ObjectNode engagement = engagements.get(engagementArn).map(ObjectNode::deepCopy).orElse(null);
        if (engagement == null) {
            return;
        }
        ObjectNode meta = (ObjectNode) engagement.get("_");
        String region = meta.path("region").asText();
        long started = meta.path("started").asLong();
        long now = Instant.now().toEpochMilli();
        List<Step> steps = new ArrayList<>();
        ObjectNode root = contacts.get(engagement.path("ContactArn").asText()).orElse(null);
        if (root != null) {
            expand(root, started, mapper.createArrayNode(), steps, region, 0, false);
        }
        steps.sort(Comparator.comparingLong(Step::atMillis));
        List<String> done = new ArrayList<>();
        meta.path("done").forEach(key -> done.add(key.asText()));
        long next = Long.MAX_VALUE;
        for (Step step : steps) {
            if (done.contains(step.key())) {
                continue;
            }
            if (engagement.has("StopTime") || (step.escalating() && meta.has("acknowledged")
                    && step.atMillis() > meta.path("acknowledged").asLong())) {
                continue;
            }
            if (step.atMillis() > now) {
                next = Math.min(next, step.atMillis());
                continue;
            }
            apply(engagement, step, region);
            ((ArrayNode) meta.get("done")).add(step.key());
            done.add(step.key());
        }
        engagements.put(engagementArn, engagement);
        if (next != Long.MAX_VALUE && !engagement.has("StopTime")) {
            String account = engagements.accountId();
            timer.schedule(() -> {
                try {
                    RequestScopes.runAs(account, region, () -> advance(engagementArn));
                } catch (RuntimeException e) {
                    LOG.debugv("SSM Contacts engagement {0} failed to advance: {1}", engagementArn, e.getMessage());
                }
            }, Math.max(0, next - now), TimeUnit.MILLISECONDS);
        }
    }

    /** The steps a contact's plan takes, starting at {@code at}, for the resolution chain that led to it. */
    private void expand(ObjectNode contact, long at, ArrayNode chain, List<Step> steps, String region, int depth,
                        boolean escalating) {
        if (depth > 4) {
            return;
        }
        String arn = contact.path("ContactArn").asText();
        String type = contact.path("Type").asText();
        ArrayNode resolution = chain.deepCopy();
        switch (type) {
            case "PERSONAL" -> {
                resolution.addObject().put("ContactArn", arn).put("Type", type);
                steps.add(new Step(at, "page:" + arn, arn, null, resolution, 0, escalating));
                long offset = at;
                int stageIndex = 0;
                for (JsonNode stage : contact.path("Plan").path("Stages")) {
                    long duration = stage.path("DurationInMinutes").asLong() * 60_000;
                    for (JsonNode target : stage.path("Targets")) {
                        String channel = target.path("ChannelTargetInfo").path("ContactChannelId").asText();
                        long retry = target.path("ChannelTargetInfo").path("RetryIntervalInMinutes").asLong(0) * 60_000;
                        for (int k = 0; k < MAX_DELIVERIES_PER_TARGET; k++) {
                            long when = offset + k * retry;
                            if (k > 0 && (retry <= 0 || when >= offset + Math.max(duration, 1))) {
                                break;
                            }
                            steps.add(new Step(when, "deliver:" + arn + ":" + channel + ":" + stageIndex + ":" + k
                                    + ":" + at, arn, channel, resolution, stageIndex, escalating || k > 0));
                        }
                    }
                    offset += duration;
                    stageIndex++;
                }
            }
            case "ESCALATION" -> {
                long offset = at;
                int stageIndex = 0;
                for (JsonNode stage : contact.path("Plan").path("Stages")) {
                    ArrayNode stageChain = chain.deepCopy();
                    stageChain.addObject().put("ContactArn", arn).put("Type", type).put("StageIndex", stageIndex);
                    final long stageAt = offset;
                    for (JsonNode target : stage.path("Targets")) {
                        contacts.get(target.path("ContactTargetInfo").path("ContactId").asText()).ifPresent(
                                next -> expand(next, stageAt, stageChain, steps, region, depth + 1, true));
                    }
                    offset += stage.path("DurationInMinutes").asLong() * 60_000;
                    stageIndex++;
                }
            }
            case "ONCALL_SCHEDULE" -> {
                resolution.addObject().put("ContactArn", arn).put("Type", type);
                Instant when = Instant.ofEpochMilli(at);
                for (JsonNode rotationArn : contact.path("Plan").path("RotationIds")) {
                    rotations.get(rotationArn.asText()).ifPresent(rotation -> {
                        for (RotationShifts.Shift shift : shiftsOf(rotation, when, when.plusSeconds(1))) {
                            for (String member : shift.contacts()) {
                                contacts.get(member).ifPresent(next -> expand(next, at, resolution, steps, region,
                                        depth + 1, escalating));
                            }
                        }
                    });
                }
            }
            default -> { }
        }
    }

    private void apply(ObjectNode engagement, Step step, String region) {
        ObjectNode pageMap = engagement.withObject("/_/pages");
        String pageArn = pageMap.path(step.contactArn()).asText(null);
        ObjectNode contact = contacts.get(step.contactArn()).orElse(null);
        if (contact == null) {
            return;
        }
        if (pageArn == null) {
            pageArn = arn(region, "page/" + contact.path("Alias").asText() + "/" + UUID.randomUUID());
            ObjectNode page = mapper.createObjectNode();
            page.put("PageArn", pageArn).put("EngagementArn", engagement.path("EngagementArn").asText())
                    .put("ContactArn", step.contactArn()).put("Sender", engagement.path("Sender").asText())
                    .put("Subject", engagement.path("Subject").asText())
                    .put("Content", engagement.path("Content").asText())
                    .put("SentTime", step.atMillis() / 1000);
            for (String field : List.of("PublicSubject", "PublicContent", "IncidentId")) {
                if (engagement.hasNonNull(field)) {
                    page.put(field, engagement.get(field).asText());
                }
            }
            ObjectNode meta = page.putObject("_");
            meta.put("acceptCode", String.format("%06d", RANDOM.nextInt(1_000_000)));
            meta.set("resolution", step.resolution());
            meta.put("escalating", step.escalating());
            meta.putArray("receipts");
            pages.put(pageArn, page);
            pageMap.put(step.contactArn(), pageArn);
        }
        if (step.channelArn() == null) {
            return;
        }
        ObjectNode page = pages.get(pageArn).map(ObjectNode::deepCopy).orElse(null);
        ObjectNode channel = channels.get(step.channelArn()).orElse(null);
        if (page == null || channel == null || page.has("ReadTime")) {
            return;
        }
        ArrayNode receipts = (ArrayNode) page.withArray("/_/receipts");
        long time = Instant.now().getEpochSecond();
        if (!"ACTIVATED".equals(channel.path("ActivationStatus").asText())) {
            receipts.addObject().put("ContactChannelArn", step.channelArn()).put("ReceiptType", "ERROR")
                    .put("ReceiptInfo", "The contact channel isn't activated.").put("ReceiptTime", time);
        } else {
            String code = page.path("_").path("acceptCode").asText();
            try {
                deliver(channel, engagement.path("Subject").asText(), engagement.path("Content").asText()
                        + "\n\nAccept code: " + code, region);
                receipts.addObject().put("ContactChannelArn", step.channelArn()).put("ReceiptType", "SENT")
                        .put("ReceiptTime", time);
                receipts.addObject().put("ContactChannelArn", step.channelArn()).put("ReceiptType", "DELIVERED")
                        .put("ReceiptTime", time);
                if (!page.has("DeliveryTime")) {
                    page.put("DeliveryTime", time);
                }
            } catch (AwsException e) {
                receipts.addObject().put("ContactChannelArn", step.channelArn()).put("ReceiptType", "ERROR")
                        .put("ReceiptInfo", e.getMessage()).put("ReceiptTime", time);
            }
        }
        pages.put(pageArn, page);
    }

    private ObjectNode engagement(String id) {
        ObjectNode engagement = engagements.get(id).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("The engagement " + id + " doesn't exist."));
        advance(id);
        return engagements.get(id).map(ObjectNode::deepCopy).orElse(engagement);
    }

    private ObjectNode stopEngagement(JsonNode in, String region) {
        ObjectNode engagement = engagement(in.path("EngagementId").asText());
        if (!engagement.has("StopTime")) {
            long now = Instant.now().getEpochSecond();
            engagement.put("StopTime", now);
            if (in.hasNonNull("Reason")) {
                ((ObjectNode) engagement.get("_")).put("reason", in.get("Reason").asText());
            }
            engagements.put(engagement.path("EngagementArn").asText(), engagement);
            for (JsonNode pageArn : engagement.path("_").path("pages")) {
                pages.get(pageArn.asText()).map(ObjectNode::deepCopy).ifPresent(page -> {
                    ObjectNode receipt = ((ArrayNode) page.withArray("/_/receipts")).addObject()
                            .put("ReceiptType", "STOP").put("ReceiptTime", now);
                    if (in.hasNonNull("Reason")) {
                        receipt.put("ReceiptInfo", in.get("Reason").asText());
                    }
                    pages.put(page.path("PageArn").asText(), page);
                });
            }
        }
        return mapper.createObjectNode();
    }

    private ObjectNode describeEngagement(JsonNode in, String region) {
        ObjectNode engagement = engagement(in.path("EngagementId").asText());
        ObjectNode view = engagement.deepCopy();
        view.remove("_");
        return view;
    }

    private ObjectNode listEngagements(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        JsonNode range = in.path("TimeRangeValue");
        for (ObjectNode stored : engagements.scan(key -> true)) {
            ObjectNode engagement = engagement(stored.path("EngagementArn").asText());
            if (in.hasNonNull("IncidentId") && !in.get("IncidentId").asText()
                    .equals(engagement.path("IncidentId").asText())) {
                continue;
            }
            long start = engagement.path("StartTime").asLong();
            if (range.has("StartTime") && start < seconds(range.get("StartTime"))
                    || range.has("EndTime") && start > seconds(range.get("EndTime"))) {
                continue;
            }
            items.add(pick(engagement, "EngagementArn", "ContactArn", "Sender", "IncidentId", "StartTime",
                    "StopTime"));
        }
        items.sort(Comparator.comparingLong((ObjectNode item) -> item.path("StartTime").asLong()).reversed());
        return page(items, in, "Engagements");
    }

    private ObjectNode pageRecord(String id) {
        ObjectNode page = pages.get(id).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("The page " + id + " doesn't exist."));
        advance(page.path("EngagementArn").asText());
        return pages.get(id).map(ObjectNode::deepCopy).orElse(page);
    }

    private ObjectNode describePage(JsonNode in, String region) {
        ObjectNode page = pageRecord(in.path("PageId").asText());
        ObjectNode view = page.deepCopy();
        view.remove("_");
        return view;
    }

    private ObjectNode pageSummary(ObjectNode page) {
        return pick(page, "PageArn", "EngagementArn", "ContactArn", "Sender", "IncidentId", "SentTime",
                "DeliveryTime", "ReadTime");
    }

    private ObjectNode listPagesByContact(JsonNode in, String region) {
        String contactArn = contact(in.path("ContactId").asText(), region).path("ContactArn").asText();
        for (ObjectNode engagement : engagements.scan(key -> true)) {
            advance(engagement.path("EngagementArn").asText());
        }
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode page : pages.scan(key -> true)) {
            if (contactArn.equals(page.path("ContactArn").asText())) {
                items.add(pageSummary(page));
            }
        }
        items.sort(Comparator.comparingLong((ObjectNode item) -> item.path("SentTime").asLong()).reversed());
        return page(items, in, "Pages");
    }

    private ObjectNode listPagesByEngagement(JsonNode in, String region) {
        ObjectNode engagement = engagement(in.path("EngagementId").asText());
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode pageArn : engagement.path("_").path("pages")) {
            pages.get(pageArn.asText()).ifPresent(page -> items.add(pageSummary(page)));
        }
        return page(items, in, "Pages");
    }

    private ObjectNode listReceipts(JsonNode in, String region) {
        ObjectNode page = pageRecord(in.path("PageId").asText());
        List<ObjectNode> items = new ArrayList<>();
        page.path("_").path("receipts").forEach(receipt -> items.add((ObjectNode) receipt.deepCopy()));
        return page(items, in, "Receipts");
    }

    private ObjectNode listResolutions(JsonNode in, String region) {
        ObjectNode page = pageRecord(in.path("PageId").asText());
        List<ObjectNode> items = new ArrayList<>();
        page.path("_").path("resolution").forEach(entry -> items.add((ObjectNode) entry.deepCopy()));
        return page(items, in, "PageResolutions");
    }

    private ObjectNode acceptPage(JsonNode in, String region) {
        ObjectNode page = pageRecord(in.path("PageId").asText());
        if ("ENFORCE".equals(in.path("AcceptCodeValidation").asText("IGNORE"))
                && !in.path("AcceptCode").asText().equals(page.path("_").path("acceptCode").asText())) {
            throw validation("The accept code isn't valid for this page.");
        }
        long now = Instant.now().getEpochSecond();
        String type = in.path("AcceptType").asText();
        ObjectNode receipt = ((ArrayNode) page.withArray("/_/receipts")).addObject().put("ReceiptType", type)
                .put("ReceiptTime", now);
        if (in.hasNonNull("ContactChannelId")) {
            receipt.put("ContactChannelArn", in.get("ContactChannelId").asText());
        }
        if (in.hasNonNull("Note")) {
            receipt.put("ReceiptInfo", in.get("Note").asText());
        }
        if ("DELIVERED".equals(type) && !page.has("DeliveryTime")) {
            page.put("DeliveryTime", now);
        }
        if ("READ".equals(type)) {
            page.put("ReadTime", now);
            if (!page.has("DeliveryTime")) {
                page.put("DeliveryTime", now);
            }
            acknowledge(page);
        }
        pages.put(page.path("PageArn").asText(), page);
        return mapper.createObjectNode();
    }

    /** A read page stops escalation when the contact that read it is essential to its stage, or is the root. */
    private void acknowledge(ObjectNode page) {
        engagements.get(page.path("EngagementArn").asText()).map(ObjectNode::deepCopy).ifPresent(engagement -> {
            boolean essential = true;
            JsonNode chain = page.path("_").path("resolution");
            for (JsonNode link : chain) {
                if ("ESCALATION".equals(link.path("Type").asText())) {
                    essential = isEssential(link.path("ContactArn").asText(), link.path("StageIndex").asInt(),
                            page.path("ContactArn").asText());
                }
            }
            ObjectNode meta = (ObjectNode) engagement.get("_");
            if (essential && !meta.has("acknowledged")) {
                meta.put("acknowledged", Instant.now().toEpochMilli());
                engagements.put(engagement.path("EngagementArn").asText(), engagement);
            }
        });
    }

    private boolean isEssential(String escalationArn, int stageIndex, String contactArn) {
        ObjectNode escalation = contacts.get(escalationArn).orElse(null);
        if (escalation == null) {
            return false;
        }
        JsonNode stage = escalation.path("Plan").path("Stages").path(stageIndex);
        for (JsonNode target : stage.path("Targets")) {
            if (contactArn.equals(target.path("ContactTargetInfo").path("ContactId").asText())) {
                return target.path("ContactTargetInfo").path("IsEssential").asBoolean(false);
            }
        }
        return false;
    }

    // ---------- tags ----------

    private ObjectNode tagged(String arn, String region) {
        if (AwsArnUtils.isArnFor(arn, "ssm-contacts") && AwsArnUtils.parse(arn).resource().startsWith("rotation/")) {
            return rotation(arn, region);
        }
        return contact(arn, region);
    }

    private ObjectNode tag(JsonNode in, String region, boolean add) {
        String arn = in.path("ResourceARN").asText();
        ObjectNode resource = tagged(arn, region);
        ObjectNode tags = resource.withObject("/_tags");
        if (add) {
            tags.setAll(tagMap(in.path("Tags")));
            if (tags.size() > 50) {
                throw validation("A resource can have at most 50 tags.");
            }
        } else {
            in.path("TagKeys").forEach(key -> tags.remove(key.asText()));
        }
        if (resource.has("RotationArn")) {
            rotations.put(resource.path("RotationArn").asText(), resource);
        } else {
            contacts.put(resource.path("ContactArn").asText(), resource);
        }
        return mapper.createObjectNode();
    }

    private ObjectNode listTags(JsonNode in, String region) {
        ObjectNode resource = tagged(in.path("ResourceARN").asText(), region);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode tags = out.putArray("Tags");
        resource.path("_tags").fields().forEachRemaining(entry -> tags.addObject().put("Key", entry.getKey())
                .put("Value", entry.getValue().asText()));
        return out;
    }

    // ---------- shared ----------

    private ObjectNode tagMap(JsonNode tags) {
        ObjectNode map = mapper.createObjectNode();
        for (JsonNode tag : tags) {
            map.put(tag.path("Key").asText(), tag.path("Value").asText());
        }
        return map;
    }

    private ObjectNode pick(ObjectNode record, String... fields) {
        ObjectNode out = mapper.createObjectNode();
        for (String field : fields) {
            if (record.hasNonNull(field)) {
                out.set(field, record.get(field).deepCopy());
            }
        }
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(value -> out.add(value.asText()));
        return out;
    }

    /** A JSON 1.1 timestamp: epoch seconds, possibly fractional, or an ISO-8601 string. */
    private static long seconds(JsonNode value) {
        if (value == null || value.isNull()) {
            throw validation("A required timestamp is missing.");
        }
        if (value.isNumber()) {
            return (long) value.asDouble();
        }
        try {
            return Instant.parse(value.asText()).getEpochSecond();
        } catch (DateTimeException e) {
            return (long) Double.parseDouble(value.asText());
        }
    }

    private ObjectNode page(List<ObjectNode> items, JsonNode in, String field) {
        int max = in.path("MaxResults").asInt(100);
        int start = 0;
        if (in.hasNonNull("NextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(in.get("NextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("The NextToken is not valid.");
            }
        }
        start = Math.max(0, Math.min(start, items.size()));
        int end = Math.min(items.size(), start + Math.max(1, max));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode array = out.putArray(field);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 400);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
