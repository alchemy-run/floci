package io.floci.aws.services.notificationscontacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.ses.SesSentEmailService;
import io.floci.aws.services.ses.model.SentEmail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * User Notifications email contacts. A contact is {@code inactive} until it's activated with the code
 * {@code SendActivationCode} emails it; the code expires after {@code activation-code-seconds}, and a new one can be sent
 * a minute after the last. Email addresses are unique in an account. Floci's SES keeps the mail it sends, so the code is
 * read from {@code GET /_aws/ses}. Contacts are global: their ARNs name no Region, and every Region sees them.
 */
@ApplicationScoped
public class NotificationsContactsService {

    static final String SENDER = "no-reply@notifications.aws";
    /** Contacts are global; every Region's requests read and write them here. */
    static final String GLOBAL = "global";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9 _.@-]{1,64}");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final long RESEND_MILLIS = 60_000;

    private final NotificationsContactsStore store;
    private final ObjectMapper mapper;
    private final Provider<SesSentEmailService> mail;
    private final RegionResolver regionResolver;

    @Inject
    public NotificationsContactsService(NotificationsContactsStore store, Provider<SesSentEmailService> mail,
                                        RegionResolver regionResolver) {
        this.regionResolver = regionResolver;
        this.store = store;
        this.mapper = store.mapper();
        this.mail = mail;
    }

    private static String required(JsonNode in, String member) {
        String value = in.path(member).asText("");
        if (value.isBlank()) {
            throw NotificationsContactsStore.validation(member + " is required.");
        }
        return value;
    }

    /** The id an email contact ARN names. */
    private static String id(String arn) {
        int slash = arn.lastIndexOf('/');
        if (slash < 0 || !arn.contains(":emailcontact/")) {
            throw NotificationsContactsStore.validation("The ARN " + arn + " doesn't name an email contact.");
        }
        return arn.substring(slash + 1);
    }

    ObjectNode contact(String region, String arn) {
        return store.get(GLOBAL, NotificationsContactsStore.CONTACT, id(arn)).orElseThrow(() ->
                NotificationsContactsStore.notFound("The email contact " + arn + " doesn't exist."));
    }

    /** A contact by ARN in any Region of an account, for User Notifications' channels. */
    public Optional<ObjectNode> find(String account, String arn) {
        if (!arn.contains(":emailcontact/")) {
            return Optional.empty();
        }
        return store.getForAccount(account, GLOBAL, NotificationsContactsStore.CONTACT, arn.substring(
                arn.lastIndexOf('/') + 1)).map(NotificationsContactsStore::view);
    }

    private static ObjectNode view(ObjectNode record) {
        return NotificationsContactsStore.view(record);
    }

    public ObjectNode createEmailContact(JsonNode in, String region) {
        String name = required(in, "name");
        if (!NAME.matcher(name).matches()) {
            throw NotificationsContactsStore.validation("name must be 1-64 letters, digits, spaces and _.@- "
                    + "characters.");
        }
        String address = required(in, "emailAddress").trim();
        if (!EMAIL.matcher(address).matches() || address.length() > 254) {
            throw NotificationsContactsStore.validation("emailAddress isn't an email address.");
        }
        for (ObjectNode existing : store.list(GLOBAL, NotificationsContactsStore.CONTACT)) {
            if (existing.path("address").asText().equalsIgnoreCase(address)) {
                throw NotificationsContactsStore.conflict("An email contact for " + address + " already exists.");
            }
        }
        if (store.list(GLOBAL, NotificationsContactsStore.CONTACT).size() >= 1000) {
            throw NotificationsContactsStore.quota("The account has the maximum number of email contacts.");
        }
        String id = NotificationsContactsStore.random(27, "abcdefghijklmnopqrstuvwxyz0123456789");
        double now = NotificationsContactsStore.epoch();
        ObjectNode contact = store.record();
        contact.put("arn", regionResolver.buildGlobalArn(NotificationsContactsStore.SERVICE, store.account(),
                "emailcontact/" + id));
        contact.put("name", name);
        contact.put("address", address);
        contact.put("status", "inactive");
        contact.put("creationTime", now);
        contact.put("updateTime", now);
        NotificationsContactsStore.addTags(contact, in.path("tags"));
        store.put(GLOBAL, NotificationsContactsStore.CONTACT, id, contact);
        ObjectNode out = mapper.createObjectNode();
        out.put("arn", contact.path("arn").asText());
        return out;
    }

    public ObjectNode getEmailContact(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("emailContact", view(contact(region, required(in, "arn"))));
        return out;
    }

    public ObjectNode listEmailContacts(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(GLOBAL, NotificationsContactsStore.CONTACT).forEach(contact -> items.add(view(contact)));
        ObjectNode out = mapper.createObjectNode();
        NotificationsContactsStore.page(items, in, out, "emailContacts", 100);
        return out;
    }

    public ObjectNode deleteEmailContact(JsonNode in, String region) {
        String arn = required(in, "arn");
        contact(region, arn);
        store.delete(GLOBAL, NotificationsContactsStore.CONTACT, id(arn));
        return mapper.createObjectNode();
    }

    public ObjectNode sendActivationCode(JsonNode in, String region) {
        String arn = required(in, "arn");
        ObjectNode contact = contact(region, arn);
        if ("active".equals(contact.path("status").asText())) {
            throw NotificationsContactsStore.conflict("The email contact " + arn + " is already active.");
        }
        ObjectNode meta = NotificationsContactsStore.meta(contact);
        long now = System.currentTimeMillis();
        if (now - meta.path("codeSentAt").asLong(0) < RESEND_MILLIS) {
            throw NotificationsContactsStore.throttled("An activation code was sent less than a minute ago.");
        }
        String code = NotificationsContactsStore.random(7, "abcdefghijklmnopqrstuvwxyz0123456789");
        meta.put("code", code);
        meta.put("codeSentAt", now);
        meta.put("codeExpires", now + store.config().activationCodeSeconds() * 1000L);
        store.put(GLOBAL, NotificationsContactsStore.CONTACT, id(arn), contact);
        String address = contact.path("address").asText();
        String body = "Use this code to activate " + address + " as the AWS User Notifications contact "
                + contact.path("name").asText() + ": " + code + "\n\nThe code expires in "
                + store.config().activationCodeSeconds() / 60 + " minutes.";
        String messageId = UUID.randomUUID().toString();
        String mailRegion = region == null || region.isBlank() ? regionResolver.getDefaultRegion() : region;
        mail.get().record(mailRegion, messageId, new SentEmail(messageId, mailRegion,
                SENDER, List.of(address), List.of(), List.of(), List.of(),
                "AWS User Notifications email verification code", body, null));
        return mapper.createObjectNode();
    }

    public ObjectNode activateEmailContact(JsonNode in, String region) {
        String arn = required(in, "arn");
        String code = required(in, "code");
        ObjectNode contact = contact(region, arn);
        if ("active".equals(contact.path("status").asText())) {
            throw NotificationsContactsStore.conflict("The email contact " + arn + " is already active.");
        }
        ObjectNode meta = NotificationsContactsStore.meta(contact);
        if (!meta.hasNonNull("code") || !code.equals(meta.get("code").asText())) {
            throw NotificationsContactsStore.validation("The activation code isn't valid.");
        }
        if (System.currentTimeMillis() > meta.path("codeExpires").asLong(0)) {
            throw NotificationsContactsStore.validation("The activation code expired; send a new one.");
        }
        meta.remove(List.of("code", "codeExpires"));
        contact.put("status", "active");
        contact.put("updateTime", NotificationsContactsStore.epoch());
        store.put(GLOBAL, NotificationsContactsStore.CONTACT, id(arn), contact);
        return mapper.createObjectNode();
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        String arn = required(in, "arn");
        ObjectNode contact = contact(region, arn);
        NotificationsContactsStore.addTags(contact, in.path("tags"));
        store.put(GLOBAL, NotificationsContactsStore.CONTACT, id(arn), contact);
        return mapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        String arn = required(in, "arn");
        ObjectNode contact = contact(region, arn);
        in.path("tagKeys").forEach(key -> NotificationsContactsStore.tags(contact).remove(key.asText()));
        store.put(GLOBAL, NotificationsContactsStore.CONTACT, id(arn), contact);
        return mapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("tags", NotificationsContactsStore.tags(contact(region, required(in, "arn"))).deepCopy());
        return out;
    }
}
