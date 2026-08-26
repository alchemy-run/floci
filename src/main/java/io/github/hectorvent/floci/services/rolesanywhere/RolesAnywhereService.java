package io.github.hectorvent.floci.services.rolesanywhere;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.rolesanywhere.model.Crl;
import io.github.hectorvent.floci.services.rolesanywhere.model.Profile;
import io.github.hectorvent.floci.services.rolesanywhere.model.Subject;
import io.github.hectorvent.floci.services.rolesanywhere.model.TrustAnchor;
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
 * IAM Roles Anywhere restJson1 — trust anchors, profiles, CRLs, and subjects.
 *
 * <p>Subjects are the certificate-identity audit records ListSubjects / GetSubject
 * read. They are stored per account/region; Bindings only need list (possibly empty)
 * and a typed ResourceNotFoundException on a missing subject id.
 */
@ApplicationScoped
public class RolesAnywhereService implements Resettable {

    static final String SERVICE = "rolesanywhere";
    private static final String TOKEN_PREFIX = "rolesanywhere:v1:";
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 1000;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern NAME_PATTERN = Pattern.compile("[0-9a-zA-Z][0-9a-zA-Z_+=,.@-]{0,254}");
    private static final String[] DEFAULT_NOTIFICATION_EVENTS = {
            "CA_CERTIFICATE_EXPIRY", "END_ENTITY_CERTIFICATE_EXPIRY"
    };

    private final StorageBackend<String, Subject> subjects;
    private final StorageBackend<String, TrustAnchor> trustAnchors;
    private final StorageBackend<String, Profile> profiles;
    private final StorageBackend<String, Crl> crls;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public RolesAnywhereService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(
                storageFactory.create(SERVICE, "rolesanywhere-subjects.json",
                        new TypeReference<Map<String, Subject>>() {
                        }),
                storageFactory.create(SERVICE, "rolesanywhere-trust-anchors.json",
                        new TypeReference<Map<String, TrustAnchor>>() {
                        }),
                storageFactory.create(SERVICE, "rolesanywhere-profiles.json",
                        new TypeReference<Map<String, Profile>>() {
                        }),
                storageFactory.create(SERVICE, "rolesanywhere-crls.json",
                        new TypeReference<Map<String, Crl>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    RolesAnywhereService(
            StorageBackend<String, Subject> subjects,
            StorageBackend<String, TrustAnchor> trustAnchors,
            StorageBackend<String, Profile> profiles,
            StorageBackend<String, Crl> crls,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.subjects = subjects;
        this.trustAnchors = trustAnchors;
        this.profiles = profiles;
        this.crls = crls;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void clear() {
        subjects.clear();
        trustAnchors.clear();
        profiles.clear();
        crls.clear();
    }

    public Page<Subject> listSubjects(String region, String pageSizeValue, String nextToken) {
        List<Subject> items = subjects.scan(key -> key.startsWith(region + "::"));
        items.sort(Comparator.comparing(Subject::getSubjectId, Comparator.nullsLast(String::compareTo)));
        return page(items, parsePageSize(pageSizeValue), nextToken);
    }

    public Subject getSubject(String region, String subjectId) {
        String id = requireUuid(decode(subjectId), "subjectId");
        return subjects.get(storageKey(region, id)).orElseThrow(
                () -> resourceNotFound("Could not find Subject with ID " + id));
    }

    synchronized Subject putSubject(String region, String x509Subject) {
        String timestamp = now();
        String id = UUID.randomUUID().toString();
        Subject subject = new Subject();
        subject.setSubjectId(id);
        subject.setSubjectArn(arn(region, "subject/" + id));
        subject.setEnabled(true);
        subject.setX509Subject(x509Subject);
        subject.setCreatedAt(timestamp);
        subject.setUpdatedAt(timestamp);
        subject.setLastSeenAt(timestamp);
        subjects.put(storageKey(region, id), subject);
        return subject;
    }

    public synchronized TrustAnchor createTrustAnchor(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name);
        JsonNode source = requireObjectNode(request, "source");
        String timestamp = now();
        String id = UUID.randomUUID().toString();
        TrustAnchor anchor = new TrustAnchor();
        anchor.setTrustAnchorId(id);
        anchor.setTrustAnchorArn(arn(region, "trust-anchor/" + id));
        anchor.setName(name);
        anchor.setSource(source.deepCopy());
        anchor.setEnabled(optionalBoolean(request, "enabled", true));
        anchor.setCreatedAt(timestamp);
        anchor.setUpdatedAt(timestamp);
        anchor.setTags(readTagList(request.get("tags")));
        anchor.setNotificationSettings(mergeNotificationSettings(null, request.get("notificationSettings")));
        trustAnchors.put(storageKey(region, id), anchor);
        return anchor;
    }

    public TrustAnchor getTrustAnchor(String region, String trustAnchorId) {
        return requireTrustAnchor(region, trustAnchorId);
    }

    public Page<TrustAnchor> listTrustAnchors(String region, String pageSizeValue, String nextToken) {
        List<TrustAnchor> items = trustAnchors.scan(key -> key.startsWith(region + "::"));
        items.sort(Comparator.comparing(TrustAnchor::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, parsePageSize(pageSizeValue), nextToken);
    }

    public synchronized TrustAnchor updateTrustAnchor(String region, String trustAnchorId, JsonNode request) {
        requireObject(request, "Request body");
        TrustAnchor anchor = requireTrustAnchor(region, trustAnchorId);
        if (request.has("name") && !request.get("name").isNull()) {
            String name = requireText(request, "name");
            validateName(name);
            anchor.setName(name);
        }
        if (request.has("source") && !request.get("source").isNull()) {
            anchor.setSource(requireObjectNode(request, "source").deepCopy());
        }
        anchor.setUpdatedAt(now());
        trustAnchors.put(storageKey(region, anchor.getTrustAnchorId()), anchor);
        return anchor;
    }

    public synchronized TrustAnchor deleteTrustAnchor(String region, String trustAnchorId) {
        TrustAnchor anchor = requireTrustAnchor(region, trustAnchorId);
        trustAnchors.delete(storageKey(region, anchor.getTrustAnchorId()));
        return anchor;
    }

    public synchronized TrustAnchor setTrustAnchorEnabled(String region, String trustAnchorId, boolean enabled) {
        TrustAnchor anchor = requireTrustAnchor(region, trustAnchorId);
        anchor.setEnabled(enabled);
        anchor.setUpdatedAt(now());
        trustAnchors.put(storageKey(region, anchor.getTrustAnchorId()), anchor);
        return anchor;
    }

    public synchronized TrustAnchor putNotificationSettings(String region, JsonNode request) {
        requireObject(request, "Request body");
        TrustAnchor anchor = requireTrustAnchor(region, requireText(request, "trustAnchorId"));
        anchor.setNotificationSettings(
                mergeNotificationSettings(anchor.getNotificationSettings(), request.get("notificationSettings")));
        anchor.setUpdatedAt(now());
        trustAnchors.put(storageKey(region, anchor.getTrustAnchorId()), anchor);
        return anchor;
    }

    public synchronized TrustAnchor resetNotificationSettings(String region, JsonNode request) {
        requireObject(request, "Request body");
        TrustAnchor anchor = requireTrustAnchor(region, requireText(request, "trustAnchorId"));
        JsonNode keys = request.get("notificationSettingKeys");
        if (keys != null && keys.isArray()) {
            List<JsonNode> current = new ArrayList<>(anchor.getNotificationSettings());
            for (JsonNode key : keys) {
                if (key == null || !key.isObject()) {
                    continue;
                }
                String event = textOrNull(key, "event");
                String channel = textOrNull(key, "channel");
                current.removeIf(setting ->
                        event != null && event.equals(textOrNull(setting, "event"))
                                && (channel == null || channel.equals(textOrNull(setting, "channel"))));
            }
            anchor.setNotificationSettings(mergeNotificationSettings(current, null));
        }
        anchor.setUpdatedAt(now());
        trustAnchors.put(storageKey(region, anchor.getTrustAnchorId()), anchor);
        return anchor;
    }

    public synchronized Profile createProfile(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name);
        List<String> roleArns = requireStringList(request, "roleArns");
        String timestamp = now();
        String id = UUID.randomUUID().toString();
        Profile profile = new Profile();
        profile.setProfileId(id);
        profile.setProfileArn(arn(region, "profile/" + id));
        profile.setName(name);
        profile.setRequireInstanceProperties(optionalBoolean(request, "requireInstanceProperties", false));
        profile.setEnabled(optionalBoolean(request, "enabled", true));
        profile.setCreatedBy(arn(region, "profile/" + id));
        profile.setSessionPolicy(textOrNull(request, "sessionPolicy"));
        profile.setRoleArns(roleArns);
        profile.setManagedPolicyArns(optionalStringList(request, "managedPolicyArns"));
        profile.setCreatedAt(timestamp);
        profile.setUpdatedAt(timestamp);
        if (request.has("durationSeconds") && request.get("durationSeconds").isNumber()) {
            profile.setDurationSeconds(request.get("durationSeconds").intValue());
        }
        profile.setAcceptRoleSessionName(optionalBoolean(request, "acceptRoleSessionName", false));
        profile.setTags(readTagList(request.get("tags")));
        profiles.put(storageKey(region, id), profile);
        return profile;
    }

    public Profile getProfile(String region, String profileId) {
        return requireProfile(region, profileId);
    }

    public Page<Profile> listProfiles(String region, String pageSizeValue, String nextToken) {
        List<Profile> items = profiles.scan(key -> key.startsWith(region + "::"));
        items.sort(Comparator.comparing(Profile::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, parsePageSize(pageSizeValue), nextToken);
    }

    public synchronized Profile updateProfile(String region, String profileId, JsonNode request) {
        requireObject(request, "Request body");
        Profile profile = requireProfile(region, profileId);
        if (request.has("name") && !request.get("name").isNull()) {
            String name = requireText(request, "name");
            validateName(name);
            profile.setName(name);
        }
        if (request.has("sessionPolicy")) {
            profile.setSessionPolicy(textOrNull(request, "sessionPolicy"));
        }
        if (request.has("roleArns") && request.get("roleArns").isArray()) {
            profile.setRoleArns(requireStringList(request, "roleArns"));
        }
        if (request.has("managedPolicyArns") && request.get("managedPolicyArns").isArray()) {
            profile.setManagedPolicyArns(optionalStringList(request, "managedPolicyArns"));
        }
        if (request.has("durationSeconds") && request.get("durationSeconds").isNumber()) {
            profile.setDurationSeconds(request.get("durationSeconds").intValue());
        }
        if (request.has("acceptRoleSessionName") && request.get("acceptRoleSessionName").isBoolean()) {
            profile.setAcceptRoleSessionName(request.get("acceptRoleSessionName").booleanValue());
        }
        profile.setUpdatedAt(now());
        profiles.put(storageKey(region, profile.getProfileId()), profile);
        return profile;
    }

    public synchronized Profile deleteProfile(String region, String profileId) {
        Profile profile = requireProfile(region, profileId);
        profiles.delete(storageKey(region, profile.getProfileId()));
        return profile;
    }

    public synchronized Profile setProfileEnabled(String region, String profileId, boolean enabled) {
        Profile profile = requireProfile(region, profileId);
        profile.setEnabled(enabled);
        profile.setUpdatedAt(now());
        profiles.put(storageKey(region, profile.getProfileId()), profile);
        return profile;
    }

    public synchronized Profile putAttributeMapping(String region, String profileId, JsonNode request) {
        requireObject(request, "Request body");
        Profile profile = requireProfile(region, profileId);
        String certificateField = requireText(request, "certificateField");
        JsonNode rules = request.get("mappingRules");
        if (rules == null || !rules.isArray()) {
            throw validation("mappingRules must be an array.");
        }
        ObjectNode mapping = objectMapper.createObjectNode();
        mapping.put("certificateField", certificateField);
        mapping.set("mappingRules", rules.deepCopy());
        List<JsonNode> mappings = new ArrayList<>(profile.getAttributeMappings());
        mappings.removeIf(existing -> certificateField.equals(textOrNull(existing, "certificateField")));
        mappings.add(mapping);
        profile.setAttributeMappings(mappings);
        profile.setUpdatedAt(now());
        profiles.put(storageKey(region, profile.getProfileId()), profile);
        return profile;
    }

    public synchronized Profile deleteAttributeMapping(
            String region, String profileId, String certificateField, List<String> specifiers) {
        if (certificateField == null || certificateField.isBlank()) {
            throw validation("certificateField must be a string.");
        }
        Profile profile = requireProfile(region, profileId);
        List<JsonNode> mappings = new ArrayList<>(profile.getAttributeMappings());
        boolean removed = mappings.removeIf(existing ->
                certificateField.equals(textOrNull(existing, "certificateField")));
        if (!removed) {
            throw resourceNotFound("Could not find attribute mapping for " + certificateField);
        }
        profile.setAttributeMappings(mappings);
        profile.setUpdatedAt(now());
        profiles.put(storageKey(region, profile.getProfileId()), profile);
        return profile;
    }

    public synchronized Crl importCrl(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name);
        String crlData = requireText(request, "crlData");
        String trustAnchorArn = requireText(request, "trustAnchorArn");
        requireTrustAnchorByArn(region, trustAnchorArn);
        String timestamp = now();
        String id = UUID.randomUUID().toString();
        Crl crl = new Crl();
        crl.setCrlId(id);
        crl.setCrlArn(arn(region, "crl/" + id));
        crl.setName(name);
        crl.setCrlData(crlData);
        crl.setTrustAnchorArn(trustAnchorArn);
        crl.setEnabled(optionalBoolean(request, "enabled", true));
        crl.setCreatedAt(timestamp);
        crl.setUpdatedAt(timestamp);
        crl.setTags(readTagList(request.get("tags")));
        crls.put(storageKey(region, id), crl);
        return crl;
    }

    public Crl getCrl(String region, String crlId) {
        return requireCrl(region, crlId);
    }

    public Page<Crl> listCrls(String region, String pageSizeValue, String nextToken) {
        List<Crl> items = crls.scan(key -> key.startsWith(region + "::"));
        items.sort(Comparator.comparing(Crl::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, parsePageSize(pageSizeValue), nextToken);
    }

    public synchronized Crl updateCrl(String region, String crlId, JsonNode request) {
        requireObject(request, "Request body");
        Crl crl = requireCrl(region, crlId);
        if (request.has("name") && !request.get("name").isNull()) {
            String name = requireText(request, "name");
            validateName(name);
            crl.setName(name);
        }
        if (request.has("crlData") && request.get("crlData").isTextual()) {
            crl.setCrlData(request.get("crlData").textValue());
        }
        crl.setUpdatedAt(now());
        crls.put(storageKey(region, crl.getCrlId()), crl);
        return crl;
    }

    public synchronized Crl deleteCrl(String region, String crlId) {
        Crl crl = requireCrl(region, crlId);
        crls.delete(storageKey(region, crl.getCrlId()));
        return crl;
    }

    public synchronized Crl setCrlEnabled(String region, String crlId, boolean enabled) {
        Crl crl = requireCrl(region, crlId);
        crl.setEnabled(enabled);
        crl.setUpdatedAt(now());
        crls.put(storageKey(region, crl.getCrlId()), crl);
        return crl;
    }

    public Map<String, String> listTags(String region, String resourceArn) {
        return tagsOf(requireTagged(region, resourceArn));
    }

    public synchronized void tagResource(String region, JsonNode request) {
        requireObject(request, "Request body");
        String arn = requireText(request, "resourceArn");
        Tagged tagged = requireTagged(region, arn);
        tagged.tags().putAll(readTagList(request.get("tags")));
        persistTagged(region, tagged);
    }

    public synchronized void untagResource(String region, JsonNode request) {
        requireObject(request, "Request body");
        String arn = requireText(request, "resourceArn");
        Tagged tagged = requireTagged(region, arn);
        JsonNode keys = request.get("tagKeys");
        if (keys != null && keys.isArray()) {
            for (JsonNode key : keys) {
                if (key.isTextual()) {
                    tagged.tags().remove(key.textValue());
                }
            }
        }
        persistTagged(region, tagged);
    }

    ObjectNode subjectDetail(Subject subject) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("subjectArn", subject.getSubjectArn());
        node.put("subjectId", subject.getSubjectId());
        node.put("enabled", subject.isEnabled());
        if (subject.getX509Subject() != null) {
            node.put("x509Subject", subject.getX509Subject());
        }
        putTimestamp(node, "lastSeenAt", subject.getLastSeenAt());
        putTimestamp(node, "createdAt", subject.getCreatedAt());
        putTimestamp(node, "updatedAt", subject.getUpdatedAt());
        ArrayNode credentials = node.putArray("credentials");
        for (Subject.CredentialSummary credential : subject.getCredentials()) {
            ObjectNode entry = credentials.addObject();
            putTimestamp(entry, "seenAt", credential.getSeenAt());
            if (credential.getSerialNumber() != null) {
                entry.put("serialNumber", credential.getSerialNumber());
            }
            if (credential.getIssuer() != null) {
                entry.put("issuer", credential.getIssuer());
            }
            entry.put("enabled", credential.isEnabled());
            if (credential.getX509CertificateData() != null) {
                entry.put("x509CertificateData", credential.getX509CertificateData());
            }
            entry.put("failed", credential.isFailed());
        }
        ArrayNode properties = node.putArray("instanceProperties");
        for (Subject.InstanceProperty property : subject.getInstanceProperties()) {
            ObjectNode entry = properties.addObject();
            putTimestamp(entry, "seenAt", property.getSeenAt());
            entry.put("failed", property.isFailed());
            if (property.getProperties() != null) {
                ObjectNode map = entry.putObject("properties");
                property.getProperties().forEach(map::put);
            }
        }
        return node;
    }

    ObjectNode subjectSummary(Subject subject) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("subjectArn", subject.getSubjectArn());
        node.put("subjectId", subject.getSubjectId());
        node.put("enabled", subject.isEnabled());
        if (subject.getX509Subject() != null) {
            node.put("x509Subject", subject.getX509Subject());
        }
        putTimestamp(node, "lastSeenAt", subject.getLastSeenAt());
        putTimestamp(node, "createdAt", subject.getCreatedAt());
        putTimestamp(node, "updatedAt", subject.getUpdatedAt());
        return node;
    }

    ObjectNode trustAnchorDetail(TrustAnchor anchor) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("trustAnchorId", anchor.getTrustAnchorId());
        node.put("trustAnchorArn", anchor.getTrustAnchorArn());
        node.put("name", anchor.getName());
        node.put("enabled", anchor.isEnabled());
        if (anchor.getSource() != null) {
            node.set("source", anchor.getSource());
        }
        putTimestamp(node, "createdAt", anchor.getCreatedAt());
        putTimestamp(node, "updatedAt", anchor.getUpdatedAt());
        ArrayNode settings = node.putArray("notificationSettings");
        for (JsonNode setting : anchor.getNotificationSettings()) {
            settings.add(setting);
        }
        return node;
    }

    ObjectNode profileDetail(Profile profile) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("profileId", profile.getProfileId());
        node.put("profileArn", profile.getProfileArn());
        node.put("name", profile.getName());
        node.put("requireInstanceProperties", profile.isRequireInstanceProperties());
        node.put("enabled", profile.isEnabled());
        if (profile.getCreatedBy() != null) {
            node.put("createdBy", profile.getCreatedBy());
        }
        if (profile.getSessionPolicy() != null) {
            node.put("sessionPolicy", profile.getSessionPolicy());
        }
        ArrayNode roleArns = node.putArray("roleArns");
        profile.getRoleArns().forEach(roleArns::add);
        ArrayNode managed = node.putArray("managedPolicyArns");
        profile.getManagedPolicyArns().forEach(managed::add);
        putTimestamp(node, "createdAt", profile.getCreatedAt());
        putTimestamp(node, "updatedAt", profile.getUpdatedAt());
        if (profile.getDurationSeconds() != null) {
            node.put("durationSeconds", profile.getDurationSeconds());
        }
        node.put("acceptRoleSessionName", profile.isAcceptRoleSessionName());
        ArrayNode mappings = node.putArray("attributeMappings");
        for (JsonNode mapping : profile.getAttributeMappings()) {
            mappings.add(mapping);
        }
        return node;
    }

    ObjectNode crlDetail(Crl crl) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("crlId", crl.getCrlId());
        node.put("crlArn", crl.getCrlArn());
        node.put("name", crl.getName());
        node.put("enabled", crl.isEnabled());
        if (crl.getCrlData() != null) {
            node.put("crlData", crl.getCrlData());
        }
        node.put("trustAnchorArn", crl.getTrustAnchorArn());
        putTimestamp(node, "createdAt", crl.getCreatedAt());
        putTimestamp(node, "updatedAt", crl.getUpdatedAt());
        return node;
    }

    ArrayNode tagArray(Map<String, String> tags) {
        ArrayNode array = objectMapper.createArrayNode();
        tags.forEach((key, value) -> {
            ObjectNode tag = array.addObject();
            tag.put("key", key);
            tag.put("value", value);
        });
        return array;
    }

    private TrustAnchor requireTrustAnchor(String region, String trustAnchorId) {
        String id = requireUuid(decode(trustAnchorId), "trustAnchorId");
        return trustAnchors.get(storageKey(region, id)).orElseThrow(
                () -> resourceNotFound("Could not find TrustAnchor with ID " + id));
    }

    private TrustAnchor requireTrustAnchorByArn(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("trustAnchorArn is invalid.");
        }
        if (!SERVICE.equals(parsed.service()) || !parsed.resource().startsWith("trust-anchor/")) {
            throw validation("trustAnchorArn is invalid.");
        }
        String id = parsed.resource().substring("trust-anchor/".length());
        TrustAnchor anchor = requireTrustAnchor(region, id);
        if (!arn.equals(anchor.getTrustAnchorArn())) {
            throw resourceNotFound("Could not find TrustAnchor with ARN " + arn);
        }
        return anchor;
    }

    private Profile requireProfile(String region, String profileId) {
        String id = requireUuid(decode(profileId), "profileId");
        return profiles.get(storageKey(region, id)).orElseThrow(
                () -> resourceNotFound("Could not find Profile with ID " + id));
    }

    private Crl requireCrl(String region, String crlId) {
        String id = requireUuid(decode(crlId), "crlId");
        return crls.get(storageKey(region, id)).orElseThrow(
                () -> resourceNotFound("Could not find Crl with ID " + id));
    }

    private Tagged requireTagged(String region, String resourceArn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw validation("resourceArn is invalid.");
        }
        if (!SERVICE.equals(parsed.service())) {
            throw validation("resourceArn is invalid.");
        }
        String resource = parsed.resource();
        if (resource.startsWith("trust-anchor/")) {
            TrustAnchor anchor = requireTrustAnchor(region, resource.substring("trust-anchor/".length()));
            return new Tagged("trust-anchor", anchor.getTrustAnchorId(), anchor.getTags());
        }
        if (resource.startsWith("profile/")) {
            Profile profile = requireProfile(region, resource.substring("profile/".length()));
            return new Tagged("profile", profile.getProfileId(), profile.getTags());
        }
        if (resource.startsWith("crl/")) {
            Crl crl = requireCrl(region, resource.substring("crl/".length()));
            return new Tagged("crl", crl.getCrlId(), crl.getTags());
        }
        throw resourceNotFound("Could not find resource " + resourceArn);
    }

    private void persistTagged(String region, Tagged tagged) {
        switch (tagged.kind()) {
            case "trust-anchor" -> {
                TrustAnchor anchor = requireTrustAnchor(region, tagged.id());
                anchor.setTags(tagged.tags());
                trustAnchors.put(storageKey(region, anchor.getTrustAnchorId()), anchor);
            }
            case "profile" -> {
                Profile profile = requireProfile(region, tagged.id());
                profile.setTags(tagged.tags());
                profiles.put(storageKey(region, profile.getProfileId()), profile);
            }
            case "crl" -> {
                Crl crl = requireCrl(region, tagged.id());
                crl.setTags(tagged.tags());
                crls.put(storageKey(region, crl.getCrlId()), crl);
            }
            default -> throw resourceNotFound("Could not find resource " + tagged.id());
        }
    }

    private static Map<String, String> tagsOf(Tagged tagged) {
        return tagged.tags() == null ? Map.of() : Map.copyOf(tagged.tags());
    }

    private List<JsonNode> mergeNotificationSettings(List<JsonNode> existing, JsonNode incoming) {
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        for (String event : DEFAULT_NOTIFICATION_EVENTS) {
            ObjectNode setting = objectMapper.createObjectNode();
            setting.put("enabled", true);
            setting.put("event", event);
            setting.put("threshold", 45);
            setting.put("channel", "ALL");
            setting.put("configuredBy", "rolesanywhere.amazonaws.com");
            byKey.put(event + "|ALL", setting);
        }
        if (existing != null) {
            for (JsonNode setting : existing) {
                String event = textOrNull(setting, "event");
                String channel = textOrNull(setting, "channel");
                if (event != null) {
                    byKey.put(event + "|" + (channel == null ? "ALL" : channel), setting);
                }
            }
        }
        if (incoming != null && incoming.isArray()) {
            for (JsonNode setting : incoming) {
                requireObject(setting, "notificationSettings members");
                String event = requireText(setting, "event");
                String channel = textOrNull(setting, "channel");
                if (channel == null) {
                    channel = "ALL";
                }
                ObjectNode copy = setting.deepCopy();
                if (!copy.has("channel") || copy.get("channel").isNull()) {
                    copy.put("channel", channel);
                }
                copy.put("configuredBy", regionResolver.getAccountId());
                byKey.put(event + "|" + channel, copy);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private Map<String, String> readTagList(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isArray()) {
            throw validation("tags must be an array.");
        }
        for (JsonNode tag : tagsNode) {
            requireObject(tag, "tags members");
            tags.put(requireText(tag, "key"), requireText(tag, "value"));
        }
        return tags;
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), resource).toString();
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static void putTimestamp(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
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

    private static String requireUuid(String value, String field) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw validation(field + " must be a UUID.");
        }
        return value;
    }

    private static void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw validation("name is invalid.");
        }
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static JsonNode requireObjectNode(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        requireObject(value, field);
        return value;
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static String textOrNull(JsonNode parent, String field) {
        if (parent == null || !parent.has(field)) {
            return null;
        }
        JsonNode value = parent.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static boolean optionalBoolean(JsonNode parent, String field, boolean defaultValue) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw validation(field + " must be a boolean.");
        }
        return value.booleanValue();
    }

    private static List<String> requireStringList(JsonNode parent, String field) {
        JsonNode array = parent.get(field);
        if (array == null || !array.isArray() || array.isEmpty()) {
            throw validation(field + " must be a non-empty array.");
        }
        return readStringList(array, field);
    }

    private static List<String> optionalStringList(JsonNode parent, String field) {
        JsonNode array = parent.get(field);
        if (array == null || array.isNull()) {
            return new ArrayList<>();
        }
        if (!array.isArray()) {
            throw validation(field + " must be an array.");
        }
        return readStringList(array, field);
    }

    private static List<String> readStringList(JsonNode array, String field) {
        List<String> values = new ArrayList<>(array.size());
        for (JsonNode value : array) {
            if (!value.isTextual()) {
                throw validation(field + " members must be strings.");
            }
            values.add(value.textValue());
        }
        return values;
    }

    private static int parsePageSize(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PAGE_SIZE;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > MAX_PAGE_SIZE) {
                throw validation("pageSize must be between 1 and 1000.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw validation("pageSize must be an integer between 1 and 1000.");
        }
    }

    private static <T> Page<T> page(List<T> items, int pageSize, String nextToken) {
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + pageSize, items.size());
        String responseToken = end < items.size() ? encodeOffset(end) : null;
        return new Page<>(items.subList(offset, end), responseToken);
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

    static AwsException resourceNotFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    private record Tagged(String kind, String id, Map<String, String> tags) {
    }
}
