package io.floci.aws.services.rolesanywhere;

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
import io.floci.aws.services.acmpca.AcmPcaService;
import io.floci.aws.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CRLException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * IAM Roles Anywhere: trust anchors (certificate bundles or ACM Private CA authorities), profiles with their roles,
 * session policies and attribute mappings, certificate revocation lists, and the subjects that have created sessions.
 * Certificates and CRLs are parsed and checked as X.509. Sessions are {@link RolesAnywhereSessions}.
 */
@ApplicationScoped
public class RolesAnywhereService implements Resettable {

    static final String ANCHOR = "trust-anchor";
    static final String PROFILE = "profile";
    static final String CRL = "crl";
    static final String SUBJECT = "subject";
    private static final List<String> SUBJECT_FIELDS = List.of("C", "CN", "L", "O", "OU", "ST");

    private final AccountAwareStorageBackend<ObjectNode> store;
    private final ObjectMapper mapper;
    private final Provider<IamService> iam;
    private final Provider<AcmPcaService> pca;

    @Inject
    public RolesAnywhereService(StorageFactory storageFactory, ObjectMapper mapper, Provider<IamService> iam,
                                Provider<AcmPcaService> pca) {
        this.store = storageFactory.create("rolesanywhere", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.iam = iam;
        this.pca = pca;
    }

    @Override
    public void clear() {
        store.clear();
    }

    String account() {
        return store.accountId();
    }

    ObjectMapper mapper() {
        return mapper;
    }

    static String now() {
        return Instant.now().toString();
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String type, String id) {
        return new AwsException("ResourceNotFoundException", "The " + type.replace('-', ' ') + " " + id
                + " doesn't exist.", 404);
    }

    static String key(String region, String kind, String id) {
        return region + "/" + kind + "/" + id;
    }

    ObjectNode get(String region, String kind, String id) {
        return store.get(key(region, kind, id)).map(ObjectNode::deepCopy).orElseThrow(() -> notFound(kind, id));
    }

    void put(String region, String kind, String id, ObjectNode record) {
        store.put(key(region, kind, id), record);
    }

    List<ObjectNode> list(String region, String kind) {
        List<ObjectNode> found = new ArrayList<>();
        store.scan(k -> k.startsWith(region + "/" + kind + "/")).forEach(r -> found.add(r.deepCopy()));
        found.sort(Comparator.comparing(r -> r.path("createdAt").asText()));
        return found;
    }

    private String arn(String region, String type, String id) {
        return AwsArnUtils.Arn.of("rolesanywhere", region, account(), type + "/" + id).toString();
    }

    private static ObjectNode view(ObjectNode record) {
        ObjectNode view = record.deepCopy();
        view.remove("_");
        return view;
    }

    private ObjectNode record() {
        ObjectNode record = mapper.createObjectNode();
        record.putObject("_").putObject("tags");
        String now = now();
        record.put("createdAt", now).put("updatedAt", now);
        return record;
    }

    // ================================================================ X.509

    /** The certificates of a PEM bundle, failing unless each is a CA certificate. */
    static List<X509Certificate> certificates(String pem, boolean requireCa) {
        try {
            Collection<? extends Certificate> parsed = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
            List<X509Certificate> certificates = new ArrayList<>();
            for (Certificate certificate : parsed) {
                X509Certificate x509 = (X509Certificate) certificate;
                if (requireCa && x509.getBasicConstraints() < 0) {
                    throw validation("The certificate " + x509.getSubjectX500Principal().getName()
                            + " isn't a CA certificate.");
                }
                certificates.add(x509);
            }
            if (certificates.isEmpty()) {
                throw validation("The certificate bundle has no certificates.");
            }
            return certificates;
        } catch (CertificateException e) {
            throw validation("The certificate data isn't valid X.509: " + e.getMessage());
        }
    }

    /** The certificates a trust anchor trusts: its bundle, or its ACM Private CA's certificate and chain. */
    List<X509Certificate> anchorCertificates(String region, ObjectNode anchor) {
        JsonNode source = anchor.path("source");
        if ("AWS_ACM_PCA".equals(source.path("sourceType").asText())) {
            String arn = source.at("/sourceData/acmPcaArn").asText();
            ObjectNode ca = RequestScopes.callAs(AwsArnUtils.accountOrDefault(arn, account()),
                    AwsArnUtils.regionOrDefault(arn, region), () -> pca.get().caCertificate(mapper.createObjectNode()
                            .put("CertificateAuthorityArn", arn)));
            return certificates(ca.path("Certificate").asText() + "\n" + ca.path("CertificateChain").asText(""),
                    true);
        }
        return certificates(source.at("/sourceData/x509CertificateData").asText(), true);
    }

    static X509CRL crl(byte[] data) {
        try {
            byte[] der = data;
            String text = new String(data, StandardCharsets.US_ASCII);
            if (text.contains("-----BEGIN X509 CRL-----")) {
                String body = text.substring(text.indexOf("-----BEGIN X509 CRL-----") + 24,
                        text.indexOf("-----END X509 CRL-----")).replaceAll("\\s", "");
                der = Base64.getDecoder().decode(body);
            }
            return (X509CRL) CertificateFactory.getInstance("X.509").generateCRL(new ByteArrayInputStream(der));
        } catch (CRLException | CertificateException | IllegalArgumentException e) {
            throw validation("The CRL data isn't a valid X.509 CRL: " + e.getMessage());
        }
    }

    private void checkSource(JsonNode source, String region) {
        String type = source.path("sourceType").asText();
        switch (type) {
            case "CERTIFICATE_BUNDLE" -> certificates(source.at("/sourceData/x509CertificateData").asText(""), true);
            case "AWS_ACM_PCA" -> {
                String arn = source.at("/sourceData/acmPcaArn").asText("");
                if (!AwsArnUtils.isArnFor(arn, "acm-pca")) {
                    throw validation("acmPcaArn " + arn + " isn't an ACM Private CA ARN.");
                }
                ObjectNode anchor = mapper.createObjectNode();
                anchor.set("source", source);
                try {
                    anchorCertificates(region, anchor);
                } catch (AwsException e) {
                    throw validation("The private CA " + arn + " can't be used: " + e.getMessage());
                }
            }
            case "SELF_SIGNED_REPOSITORY" -> throw validation("SELF_SIGNED_REPOSITORY isn't a source you can create.");
            default -> throw validation("sourceType must be CERTIFICATE_BUNDLE or AWS_ACM_PCA.");
        }
    }

    // ================================================================ operations

    public ObjectNode handle(String operation, JsonNode in, String region) {
        return switch (operation) {
            case "CreateTrustAnchor" -> createTrustAnchor(in, region);
            case "GetTrustAnchor" -> anchorOut(get(region, ANCHOR, in.path("trustAnchorId").asText()));
            case "UpdateTrustAnchor" -> updateTrustAnchor(in, region);
            case "DeleteTrustAnchor" -> deleteTrustAnchor(in, region);
            case "EnableTrustAnchor" -> enable(region, ANCHOR, in.path("trustAnchorId").asText(), true);
            case "DisableTrustAnchor" -> enable(region, ANCHOR, in.path("trustAnchorId").asText(), false);
            case "ListTrustAnchors" -> listOut(in, region, ANCHOR, "trustAnchors");
            case "PutNotificationSettings" -> putNotifications(in, region);
            case "ResetNotificationSettings" -> resetNotifications(in, region);
            case "CreateProfile" -> createProfile(in, region);
            case "GetProfile" -> profileOut(get(region, PROFILE, in.path("profileId").asText()));
            case "UpdateProfile" -> updateProfile(in, region);
            case "DeleteProfile" -> deleteSimple(region, PROFILE, in.path("profileId").asText(), "profile");
            case "EnableProfile" -> enable(region, PROFILE, in.path("profileId").asText(), true);
            case "DisableProfile" -> enable(region, PROFILE, in.path("profileId").asText(), false);
            case "ListProfiles" -> listOut(in, region, PROFILE, "profiles");
            case "PutAttributeMapping" -> putMapping(in, region);
            case "DeleteAttributeMapping" -> deleteMapping(in, region);
            case "ImportCrl" -> importCrl(in, region);
            case "GetCrl" -> crlOut(get(region, CRL, in.path("crlId").asText()));
            case "UpdateCrl" -> updateCrl(in, region);
            case "DeleteCrl" -> deleteSimple(region, CRL, in.path("crlId").asText(), "crl");
            case "EnableCrl" -> enable(region, CRL, in.path("crlId").asText(), true);
            case "DisableCrl" -> enable(region, CRL, in.path("crlId").asText(), false);
            case "ListCrls" -> listOut(in, region, CRL, "crls");
            case "GetSubject" -> subjectOut(get(region, SUBJECT, in.path("subjectId").asText()));
            case "ListSubjects" -> listSubjects(in, region);
            case "TagResource" -> tag(in, true);
            case "UntagResource" -> tag(in, false);
            case "ListTagsForResource" -> listTags(in);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation + ".",
                    404);
        };
    }

    private ObjectNode wrap(String name, ObjectNode value) {
        ObjectNode out = mapper.createObjectNode();
        out.set(name, value);
        return out;
    }

    private ObjectNode anchorOut(ObjectNode anchor) {
        return wrap("trustAnchor", view(anchor));
    }

    private ObjectNode profileOut(ObjectNode profile) {
        return wrap("profile", view(profile));
    }

    private ObjectNode crlOut(ObjectNode crl) {
        return wrap("crl", view(crl));
    }

    private ObjectNode subjectOut(ObjectNode subject) {
        return wrap("subject", view(subject));
    }

    private ObjectNode wrapKind(String kind, ObjectNode record) {
        return switch (kind) {
            case ANCHOR -> anchorOut(record);
            case PROFILE -> profileOut(record);
            default -> crlOut(record);
        };
    }

    private ObjectNode enable(String region, String kind, String id, boolean enabled) {
        ObjectNode record = get(region, kind, id);
        record.put("enabled", enabled).put("updatedAt", now());
        put(region, kind, id, record);
        return wrapKind(kind, record);
    }

    private ObjectNode listOut(JsonNode in, String region, String kind, String name) {
        List<ObjectNode> all = list(region, kind);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode items = out.putArray(name);
        page(all, in, out).forEach(record -> items.add(view(record)));
        return out;
    }

    private static <T> List<T> page(List<T> items, JsonNode in, ObjectNode out) {
        int start = 0;
        if (in.hasNonNull("nextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(in.get("nextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("The nextToken is not valid.");
            }
        }
        int end = Math.min(items.size(), start + in.path("pageSize").asInt(50));
        if (end < items.size()) {
            out.put("nextToken", Base64.getUrlEncoder().withoutPadding().encodeToString(String.valueOf(end)
                    .getBytes(StandardCharsets.UTF_8)));
        }
        return items.subList(Math.min(start, items.size()), end);
    }

    // ---------------------------------------------------------------- trust anchors

    private ArrayNode defaultNotifications() {
        ArrayNode settings = mapper.createArrayNode();
        for (String event : List.of("CA_CERTIFICATE_EXPIRY", "END_ENTITY_CERTIFICATE_EXPIRY")) {
            settings.addObject().put("enabled", true).put("event", event).put("threshold", 45).put("channel", "ALL")
                    .put("configuredBy", ServicePrincipals.of("rolesanywhere"));
        }
        return settings;
    }

    private void applyNotifications(ObjectNode anchor, JsonNode updates) {
        ArrayNode settings = (ArrayNode) anchor.get("notificationSettings");
        for (JsonNode update : updates) {
            int threshold = update.path("threshold").asInt(45);
            if (update.has("threshold") && (threshold < 1 || threshold > 360)) {
                throw validation("A notification threshold is 1 to 360 days.");
            }
            for (JsonNode setting : settings) {
                if (setting.path("event").asText().equals(update.path("event").asText())) {
                    ((ObjectNode) setting).put("enabled", update.path("enabled").asBoolean(true))
                            .put("threshold", threshold).put("channel", update.path("channel").asText("ALL"))
                            .put("configuredBy", AwsArnUtils.Arn.global(AwsArnUtils.parse(anchor.get("trustAnchorArn")
                                    .asText()).partition(), "iam", account(), "root").toString());
                }
            }
        }
    }

    private ObjectNode createTrustAnchor(JsonNode in, String region) {
        checkSource(in.path("source"), region);
        String id = UUID.randomUUID().toString();
        ObjectNode anchor = record();
        anchor.put("trustAnchorId", id).put("trustAnchorArn", arn(region, "trust-anchor", id))
                .put("name", in.path("name").asText()).put("enabled", in.path("enabled").asBoolean(false));
        anchor.set("source", in.get("source").deepCopy());
        anchor.set("notificationSettings", defaultNotifications());
        applyNotifications(anchor, in.path("notificationSettings"));
        addTags(anchor, in.path("tags"));
        put(region, ANCHOR, id, anchor);
        return anchorOut(anchor);
    }

    private ObjectNode updateTrustAnchor(JsonNode in, String region) {
        ObjectNode anchor = get(region, ANCHOR, in.path("trustAnchorId").asText());
        if (in.has("source")) {
            checkSource(in.get("source"), region);
            anchor.set("source", in.get("source").deepCopy());
        }
        if (in.hasNonNull("name")) {
            anchor.put("name", in.get("name").asText());
        }
        anchor.put("updatedAt", now());
        put(region, ANCHOR, anchor.get("trustAnchorId").asText(), anchor);
        return anchorOut(anchor);
    }

    private ObjectNode deleteTrustAnchor(JsonNode in, String region) {
        ObjectNode anchor = get(region, ANCHOR, in.path("trustAnchorId").asText());
        String arn = anchor.get("trustAnchorArn").asText();
        for (ObjectNode crl : list(region, CRL)) {
            if (arn.equals(crl.path("trustAnchorArn").asText())) {
                store.delete(key(region, CRL, crl.get("crlId").asText()));
            }
        }
        store.delete(key(region, ANCHOR, anchor.get("trustAnchorId").asText()));
        return anchorOut(anchor);
    }

    private ObjectNode putNotifications(JsonNode in, String region) {
        ObjectNode anchor = get(region, ANCHOR, in.path("trustAnchorId").asText());
        applyNotifications(anchor, in.path("notificationSettings"));
        anchor.put("updatedAt", now());
        put(region, ANCHOR, anchor.get("trustAnchorId").asText(), anchor);
        return anchorOut(anchor);
    }

    private ObjectNode resetNotifications(JsonNode in, String region) {
        ObjectNode anchor = get(region, ANCHOR, in.path("trustAnchorId").asText());
        Set<String> events = new LinkedHashSet<>();
        in.path("notificationSettingKeys").forEach(k -> events.add(k.path("event").asText()));
        ArrayNode settings = (ArrayNode) anchor.get("notificationSettings");
        for (JsonNode setting : settings) {
            if (events.contains(setting.path("event").asText())) {
                ((ObjectNode) setting).put("enabled", true).put("threshold", 45).put("channel", "ALL")
                        .put("configuredBy", ServicePrincipals.of("rolesanywhere"));
            }
        }
        anchor.put("updatedAt", now());
        put(region, ANCHOR, anchor.get("trustAnchorId").asText(), anchor);
        return anchorOut(anchor);
    }

    // ---------------------------------------------------------------- profiles

    private void checkRoles(JsonNode roleArns, String region) {
        if (roleArns.size() > 250) {
            throw validation("A profile has at most 250 roles.");
        }
        for (JsonNode arn : roleArns) {
            String value = arn.asText();
            if (!AwsArnUtils.isArnFor(value, "iam") || !AwsArnUtils.parse(value).resource().startsWith("role/")) {
                throw validation("roleArns has " + value + ", which isn't a role ARN.");
            }
        }
    }

    private static void checkDuration(JsonNode in) {
        if (in.has("durationSeconds")) {
            int seconds = in.get("durationSeconds").asInt();
            if (seconds < 900 || seconds > 43200) {
                throw validation("durationSeconds must be from 900 to 43200.");
            }
        }
    }

    private void checkSessionPolicy(JsonNode in) {
        if (in.hasNonNull("sessionPolicy")) {
            try {
                JsonNode policy = mapper.readTree(in.get("sessionPolicy").asText());
                if (!policy.has("Statement")) {
                    throw validation("The session policy has no Statement.");
                }
            } catch (IOException e) {
                throw validation("The session policy isn't valid JSON.");
            }
        }
    }

    private ArrayNode defaultMappings() {
        ArrayNode mappings = mapper.createArrayNode();
        for (String field : List.of("x509Subject", "x509Issuer")) {
            ArrayNode rules = mappings.addObject().put("certificateField", field).putArray("mappingRules");
            SUBJECT_FIELDS.forEach(specifier -> rules.addObject().put("specifier", specifier));
        }
        ArrayNode san = mappings.addObject().put("certificateField", "x509SAN").putArray("mappingRules");
        List.of("DNS", "URI", "Name").forEach(specifier -> san.addObject().put("specifier", specifier));
        return mappings;
    }

    private ObjectNode createProfile(JsonNode in, String region) {
        checkRoles(in.path("roleArns"), region);
        checkDuration(in);
        checkSessionPolicy(in);
        String id = UUID.randomUUID().toString();
        ObjectNode profile = record();
        profile.put("profileId", id).put("profileArn", arn(region, "profile", id)).put("name", in.path("name").asText())
                .put("requireInstanceProperties", in.path("requireInstanceProperties").asBoolean(false))
                .put("enabled", in.path("enabled").asBoolean(false))
                .put("createdBy", AwsArnUtils.Arn.global(AwsArnUtils.parse(arn(region, "profile", id)).partition(),
                        "iam", account(), "root").toString())
                .put("durationSeconds", in.path("durationSeconds").asInt(3600))
                .put("acceptRoleSessionName", in.path("acceptRoleSessionName").asBoolean(false));
        if (in.hasNonNull("sessionPolicy")) {
            profile.put("sessionPolicy", in.get("sessionPolicy").asText());
        }
        profile.set("roleArns", in.get("roleArns").deepCopy());
        profile.set("managedPolicyArns", in.path("managedPolicyArns").isArray() ? in.get("managedPolicyArns")
                .deepCopy() : mapper.createArrayNode());
        profile.set("attributeMappings", defaultMappings());
        addTags(profile, in.path("tags"));
        put(region, PROFILE, id, profile);
        return profileOut(profile);
    }

    private ObjectNode updateProfile(JsonNode in, String region) {
        ObjectNode profile = get(region, PROFILE, in.path("profileId").asText());
        if (in.has("roleArns")) {
            checkRoles(in.get("roleArns"), region);
            profile.set("roleArns", in.get("roleArns").deepCopy());
        }
        checkDuration(in);
        checkSessionPolicy(in);
        for (String member : List.of("name", "sessionPolicy")) {
            if (in.hasNonNull(member)) {
                profile.put(member, in.get(member).asText());
            }
        }
        if (in.has("durationSeconds")) {
            profile.put("durationSeconds", in.get("durationSeconds").asInt());
        }
        if (in.has("acceptRoleSessionName")) {
            profile.put("acceptRoleSessionName", in.get("acceptRoleSessionName").asBoolean());
        }
        if (in.has("managedPolicyArns")) {
            profile.set("managedPolicyArns", in.get("managedPolicyArns").deepCopy());
        }
        profile.put("updatedAt", now());
        put(region, PROFILE, profile.get("profileId").asText(), profile);
        return profileOut(profile);
    }

    private ObjectNode deleteSimple(String region, String kind, String id, String name) {
        ObjectNode record = get(region, kind, id);
        store.delete(key(region, kind, id));
        return wrap(name, view(record));
    }

    private ObjectNode putMapping(JsonNode in, String region) {
        ObjectNode profile = get(region, PROFILE, in.path("profileId").asText());
        String field = in.path("certificateField").asText();
        ArrayNode mappings = (ArrayNode) profile.get("attributeMappings");
        ObjectNode mapping = null;
        for (JsonNode existing : mappings) {
            if (field.equals(existing.path("certificateField").asText())) {
                mapping = (ObjectNode) existing;
            }
        }
        if (mapping == null) {
            mapping = mappings.addObject().put("certificateField", field);
            mapping.putArray("mappingRules");
        }
        mapping.set("mappingRules", in.get("mappingRules").deepCopy());
        profile.put("updatedAt", now());
        put(region, PROFILE, profile.get("profileId").asText(), profile);
        return profileOut(profile);
    }

    private ObjectNode deleteMapping(JsonNode in, String region) {
        ObjectNode profile = get(region, PROFILE, in.path("profileId").asText());
        String field = in.path("certificateField").asText();
        Set<String> specifiers = new LinkedHashSet<>();
        in.path("specifiers").forEach(s -> specifiers.add(s.asText()));
        ArrayNode mappings = (ArrayNode) profile.get("attributeMappings");
        for (int i = mappings.size() - 1; i >= 0; i--) {
            JsonNode mapping = mappings.get(i);
            if (!field.equals(mapping.path("certificateField").asText())) {
                continue;
            }
            if (specifiers.isEmpty()) {
                mappings.remove(i);
                continue;
            }
            ArrayNode rules = (ArrayNode) mapping.get("mappingRules");
            for (int r = rules.size() - 1; r >= 0; r--) {
                if (specifiers.contains(rules.get(r).path("specifier").asText())) {
                    rules.remove(r);
                }
            }
        }
        profile.put("updatedAt", now());
        put(region, PROFILE, profile.get("profileId").asText(), profile);
        return profileOut(profile);
    }

    // ---------------------------------------------------------------- CRLs

    private ObjectNode anchorByArn(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "rolesanywhere")) {
            throw validation("trustAnchorArn " + arn + " isn't a trust anchor ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        return get(parsed.region(), ANCHOR, parsed.resource().substring(parsed.resource().indexOf('/') + 1));
    }

    private byte[] crlBytes(JsonNode data) {
        try {
            return Base64.getDecoder().decode(data.asText());
        } catch (IllegalArgumentException e) {
            return data.asText().getBytes(StandardCharsets.UTF_8);
        }
    }

    private ObjectNode importCrl(JsonNode in, String region) {
        ObjectNode anchor = anchorByArn(in.path("trustAnchorArn").asText());
        byte[] data = crlBytes(in.get("crlData"));
        crl(data);
        String id = UUID.randomUUID().toString();
        ObjectNode crl = record();
        crl.put("crlId", id).put("crlArn", arn(region, "crl", id)).put("name", in.path("name").asText())
                .put("enabled", in.path("enabled").asBoolean(false))
                .put("crlData", Base64.getEncoder().encodeToString(data))
                .put("trustAnchorArn", anchor.get("trustAnchorArn").asText());
        addTags(crl, in.path("tags"));
        put(region, CRL, id, crl);
        return crlOut(crl);
    }

    private ObjectNode updateCrl(JsonNode in, String region) {
        ObjectNode crl = get(region, CRL, in.path("crlId").asText());
        if (in.hasNonNull("crlData")) {
            byte[] data = crlBytes(in.get("crlData"));
            crl(data);
            crl.put("crlData", Base64.getEncoder().encodeToString(data));
        }
        if (in.hasNonNull("name")) {
            crl.put("name", in.get("name").asText());
        }
        crl.put("updatedAt", now());
        put(region, CRL, crl.get("crlId").asText(), crl);
        return crlOut(crl);
    }

    // ---------------------------------------------------------------- subjects

    private ObjectNode listSubjects(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode items = out.putArray("subjects");
        for (ObjectNode subject : page(list(region, SUBJECT), in, out)) {
            ObjectNode summary = view(subject);
            summary.remove("credentials");
            summary.remove("instanceProperties");
            items.add(summary);
        }
        return out;
    }

    /** Records a certificate's use, successful or not, under its subject. */
    ObjectNode recordSubject(String region, X509Certificate certificate, boolean failed) {
        String x509Subject = certificate.getSubjectX500Principal().getName();
        ObjectNode subject = null;
        for (ObjectNode existing : list(region, SUBJECT)) {
            if (x509Subject.equals(existing.path("x509Subject").asText())) {
                subject = existing;
            }
        }
        if (subject == null) {
            String id = UUID.randomUUID().toString();
            subject = record();
            subject.put("subjectId", id).put("subjectArn", arn(region, "subject", id)).put("enabled", true)
                    .put("x509Subject", x509Subject);
            subject.putArray("credentials");
            subject.putArray("instanceProperties");
        }
        String now = now();
        subject.put("lastSeenAt", now).put("updatedAt", now);
        ArrayNode credentials = (ArrayNode) subject.get("credentials");
        String serial = certificate.getSerialNumber().toString();
        ObjectNode credential = null;
        for (JsonNode existing : credentials) {
            if (serial.equals(existing.path("serialNumber").asText())) {
                credential = (ObjectNode) existing;
            }
        }
        if (credential == null) {
            credential = credentials.addObject().put("serialNumber", serial).put("issuer",
                    certificate.getIssuerX500Principal().getName()).put("enabled", true);
            try {
                credential.put("x509CertificateData", "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64,
                        "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(certificate.getEncoded())
                        + "\n-----END CERTIFICATE-----\n");
            } catch (CertificateEncodingException e) {
                credential.putNull("x509CertificateData");
            }
        }
        credential.put("seenAt", now).put("failed", failed);
        put(region, SUBJECT, subject.get("subjectId").asText(), subject);
        return subject;
    }

    // ---------------------------------------------------------------- tags

    private static void addTags(ObjectNode record, JsonNode tags) {
        ObjectNode map = (ObjectNode) record.get("_").get("tags");
        for (JsonNode tag : tags) {
            String key = tag.path("key").asText();
            if (key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw validation("Tag keys can't start with aws:.");
            }
            map.put(key, tag.path("value").asText());
        }
        if (map.size() > 200) {
            throw new AwsException("TooManyTagsException", "A resource has at most 200 tags.", 400);
        }
    }

    private String[] locate(String encoded) {
        String arn = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        if (!AwsArnUtils.isArnFor(arn, "rolesanywhere")) {
            throw validation("The ARN " + arn + " isn't a Roles Anywhere ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        String type = resource.substring(0, resource.indexOf('/'));
        String kind = switch (type) {
            case "trust-anchor" -> ANCHOR;
            case "profile" -> PROFILE;
            case "crl" -> CRL;
            default -> throw notFound("resource", arn);
        };
        if (!parsed.accountId().equals(account())) {
            throw notFound("resource", arn);
        }
        return new String[] {parsed.region(), kind, resource.substring(resource.indexOf('/') + 1)};
    }

    private ObjectNode tag(JsonNode in, boolean add) {
        String[] at = locate(in.path("resourceArn").asText());
        ObjectNode record = get(at[0], at[1], at[2]);
        if (add) {
            addTags(record, in.path("tags"));
        } else {
            in.path("tagKeys").forEach(k -> ((ObjectNode) record.get("_").get("tags")).remove(k.asText()));
        }
        put(at[0], at[1], at[2], record);
        return mapper.createObjectNode();
    }

    private ObjectNode listTags(JsonNode in) {
        String[] at = locate(in.path("resourceArn").asText());
        ObjectNode record = get(at[0], at[1], at[2]);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode tags = out.putArray("tags");
        record.get("_").get("tags").properties().forEach(t -> tags.addObject().put("key", t.getKey())
                .put("value", t.getValue().asText()));
        return out;
    }

    /** A role exists and trusts IAM Roles Anywhere. */
    boolean roleTrustsRolesAnywhere(String roleArn) {
        String resource = AwsArnUtils.parse(roleArn).resource();
        return iam.get().findRole(AwsArnUtils.accountOrDefault(roleArn, account()),
                resource.substring(resource.lastIndexOf('/') + 1)).map(role -> {
                    String trust = role.getAssumeRolePolicyDocument() == null ? ""
                            : URLDecoder.decode(role.getAssumeRolePolicyDocument(), StandardCharsets.UTF_8);
                    return trust.contains(ServicePrincipals.of("rolesanywhere"));
                }).orElse(false);
    }
}
