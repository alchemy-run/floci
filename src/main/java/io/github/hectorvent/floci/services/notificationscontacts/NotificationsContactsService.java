package io.github.hectorvent.floci.services.notificationscontacts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.notificationscontacts.model.EmailContact;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS User Notifications Contacts restJson1 — email contacts.
 *
 * <p>Tag APIs share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}
 * using ARN service {@code notifications-contacts}.
 */
@ApplicationScoped
public class NotificationsContactsService implements TagHandler {

    static final String SERVICE = "notifications-contacts";
    private static final String RESOURCE_TYPE = "EmailContact";
    private static final String TOKEN_PREFIX = "notificationscontacts:v1:";
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 100;
    private static final int ID_LENGTH = 27;
    private static final Pattern EMAIL_PATTERN = Pattern.compile(".+@.+");
    private static final Pattern NAME_PATTERN = Pattern.compile(".*[\\w\\-.~]+.*");
    private static final Pattern TAG_KEY_PATTERN = Pattern.compile("(?!aws:).{1,128}", Pattern.CASE_INSENSITIVE);

    private final StorageBackend<String, EmailContact> store;
    private final RegionResolver regionResolver;

    @Inject
    public NotificationsContactsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create(
                "notificationscontacts",
                "notificationscontacts-email-contacts.json",
                new TypeReference<Map<String, EmailContact>>() {
                }),
                regionResolver);
    }

    NotificationsContactsService(StorageBackend<String, EmailContact> store, RegionResolver regionResolver) {
        this.store = store;
        this.regionResolver = regionResolver;
    }

    public synchronized EmailContact createEmailContact(JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name);
        String emailAddress = requireText(request, "emailAddress");
        validateEmail(emailAddress);
        Map<String, String> tags = readTags(request);

        EmailContact existing = findByAddress(emailAddress);
        if (existing != null) {
            throw conflict(existing.getId(), "An email contact already exists for this email address.");
        }

        String id = newId();
        String now = timestamp();
        EmailContact contact = new EmailContact();
        contact.setId(id);
        contact.setArn(arn(id));
        contact.setName(name);
        contact.setAddress(emailAddress);
        contact.setStatus("inactive");
        contact.setCreationTime(now);
        contact.setUpdateTime(now);
        contact.setActivationCode(newActivationCode(id));
        contact.setTags(tags);
        store.put(id, contact);
        return contact;
    }

    public EmailContact getEmailContact(String arn) {
        return requireContact(arn);
    }

    public synchronized void deleteEmailContact(String arn) {
        EmailContact contact = requireContact(arn);
        store.delete(contact.getId());
    }

    public Page listEmailContacts(String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        List<EmailContact> contacts = new ArrayList<>(store.scan(key -> true));
        contacts.sort(Comparator.comparing(EmailContact::getName, Comparator.nullsLast(String::compareTo))
                .thenComparing(EmailContact::getId));
        int offset = decodeOffset(nextToken, contacts.size());
        int end = Math.min(offset + maxResults, contacts.size());
        String responseToken = end < contacts.size() ? encodeOffset(end) : null;
        return new Page(contacts.subList(offset, end), responseToken);
    }

    public synchronized EmailContact sendActivationCode(String arn) {
        EmailContact contact = requireContact(arn);
        if ("active".equals(contact.getStatus())) {
            throw conflict(contact.getId(), "The email contact is already activated.");
        }
        contact.setActivationCode(newActivationCode(contact.getId()));
        contact.setUpdateTime(timestamp());
        store.put(contact.getId(), contact);
        return contact;
    }

    public synchronized EmailContact activateEmailContact(String arn, String code) {
        EmailContact contact = requireContact(arn);
        if (code == null || code.isBlank() || !code.equals(contact.getActivationCode())) {
            throw validation("Activation code is invalid.");
        }
        if ("active".equals(contact.getStatus())) {
            throw conflict(contact.getId(), "The email contact is already activated.");
        }
        contact.setStatus("active");
        contact.setUpdateTime(timestamp());
        store.put(contact.getId(), contact);
        return contact;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireContact(arn).getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        EmailContact contact = requireContact(arn);
        Map<String, String> current = new LinkedHashMap<>(contact.getTags());
        if (tags != null) {
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                validateTag(entry.getKey(), entry.getValue());
                current.put(entry.getKey(), entry.getValue());
            }
        }
        if (current.size() > 200) {
            throw validation("Tags must contain at most 200 entries.");
        }
        contact.setTags(current);
        contact.setUpdateTime(timestamp());
        store.put(contact.getId(), contact);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        EmailContact contact = requireContact(arn);
        Map<String, String> current = new LinkedHashMap<>(contact.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        contact.setTags(current);
        contact.setUpdateTime(timestamp());
        store.put(contact.getId(), contact);
    }

    private EmailContact requireContact(String arn) {
        String decoded = decode(arn);
        String id = idFromArn(decoded);
        EmailContact contact = store.get(id).orElse(null);
        if (contact == null) {
            throw notFound(decoded);
        }
        return contact;
    }

    private EmailContact findByAddress(String emailAddress) {
        for (EmailContact contact : store.scan(key -> true)) {
            if (emailAddress.equals(contact.getAddress())) {
                return contact;
            }
        }
        return null;
    }

    private String arn(String id) {
        return "arn:aws:" + SERVICE + "::" + regionResolver.getAccountId() + ":emailcontact/" + id;
    }

    private static String idFromArn(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("Invalid email contact ARN.");
        }
        if (!SERVICE.equals(parsed.service()) || !parsed.resource().startsWith("emailcontact/")) {
            throw validation("Invalid email contact ARN.");
        }
        String id = parsed.resource().substring("emailcontact/".length());
        if (id.length() != ID_LENGTH) {
            throw validation("Invalid email contact ARN.");
        }
        return id;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, ID_LENGTH);
    }

    /** Deterministic 8-char code derived from the contact id so tests can activate over HTTP. */
    private static String newActivationCode(String id) {
        return id.substring(0, 8);
    }

    private static String timestamp() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static void validateName(String name) {
        if (name.length() < 1 || name.length() > 64 || !NAME_PATTERN.matcher(name).matches()) {
            throw validation("name must be 1 to 64 characters matching [\\w-.~]+.");
        }
    }

    private static void validateEmail(String email) {
        if (email.length() < 6 || email.length() > 254 || !EMAIL_PATTERN.matcher(email).matches()) {
            throw validation("emailAddress must be a valid email address.");
        }
    }

    private static Map<String, String> readTags(JsonNode request) {
        if (!request.has("tags") || request.get("tags").isNull()) {
            return new LinkedHashMap<>();
        }
        JsonNode tagsNode = request.get("tags");
        if (!tagsNode.isObject() || tagsNode.size() > 200) {
            throw validation("tags must be an object with at most 200 entries.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode valueNode = entry.getValue();
            if (!valueNode.isTextual()) {
                throw validation("tags values must be strings.");
            }
            validateTag(entry.getKey(), valueNode.textValue());
            tags.put(entry.getKey(), valueNode.textValue());
        });
        return tags;
    }

    private static void validateTag(String key, String value) {
        if (key == null || !TAG_KEY_PATTERN.matcher(key).matches()
                || (value != null && value.length() > 256)) {
            throw validation("tags contains an invalid key or value.");
        }
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static int parseMaxResults(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > MAX_RESULTS) {
                throw validation("maxResults must be between 1 and 100.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw validation("maxResults must be an integer between 1 and 100.");
        }
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw validation("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset > resultSize) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static AwsException conflict(String resourceId, String message) {
        return new AwsException(
                "ConflictException",
                message,
                409,
                Map.of("resourceId", resourceId, "resourceType", RESOURCE_TYPE));
    }

    private static AwsException notFound(String resourceId) {
        return new AwsException(
                "ResourceNotFoundException",
                "Email contact " + resourceId + " does not exist.",
                404,
                Map.of("resourceId", resourceId, "resourceType", RESOURCE_TYPE));
    }

    private static AwsException validation(String message) {
        return new AwsException(
                "ValidationException",
                message,
                400,
                Map.of("reason", "fieldValidationFailed"));
    }

    public record Page(List<EmailContact> contacts, String nextToken) {
        public Page {
            contacts = List.copyOf(contacts);
        }
    }
}
