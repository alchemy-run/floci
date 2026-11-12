package io.floci.aws.services.pinpointsmsvoicev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.pinpointsmsvoicev2.SmsVoiceCatalog.Field;
import io.floci.aws.services.pinpointsmsvoicev2.SmsVoiceCatalog.RegistrationType;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registrations: the forms that let an account send from toll-free and 10DLC numbers and registered sender IDs. A
 * registration keeps numbered versions; a version's field values are checked against its type's field definitions,
 * and submitting a version reviews it: once every required field holds a valid value, it's approved after the
 * transition delay, the registration becomes {@code COMPLETE}, and the resources it's associated with may send.
 */
@ApplicationScoped
public class SmsVoiceRegistrations {

    private final SmsVoiceStore store;
    private final SmsVoiceService service;
    private final ObjectMapper mapper;
    private final Provider<S3Service> s3;

    @Inject
    public SmsVoiceRegistrations(SmsVoiceStore store, SmsVoiceService service, Provider<S3Service> s3) {
        this.store = store;
        this.service = service;
        this.mapper = store.mapper();
        this.s3 = s3;
    }

    private ObjectNode out() {
        return mapper.createObjectNode();
    }

    private static final String[] REGISTRATION_FIELDS = {"RegistrationArn", "RegistrationId", "RegistrationType",
        "RegistrationStatus", "CurrentVersionNumber", "ApprovedVersionNumber", "LatestDeniedVersionNumber",
        "AdditionalAttributes", "CreatedTimestamp"};

    private ObjectNode copy(ObjectNode record, String... fields) {
        ObjectNode view = out();
        for (String field : fields) {
            if (record.has(field) && !record.get(field).isNull()) {
                view.set(field, record.get(field).deepCopy());
            }
        }
        return view;
    }

    /** A registration, with any review that has come due applied. */
    ObjectNode registration(String region, String id) {
        ObjectNode record = service.require(region, SmsVoiceStore.REGISTRATION, id);
        review(region, record);
        return record;
    }

    private ObjectNode version(String region, String id, long number) {
        return store.get(region, SmsVoiceStore.VERSION, id + "/" + String.format("%06d", number))
                .orElseThrow(() -> SmsVoiceStore.validation("UNKNOWN_REGISTRATION_VERSION", "The registration "
                        + id + " has no version " + number + "."));
    }

    private void saveVersion(String region, String id, ObjectNode version) {
        store.put(region, SmsVoiceStore.VERSION, id + "/" + String.format("%06d", version.get("VersionNumber")
                .asLong()), version);
    }

    private ObjectNode newVersion(String region, String id, long number) {
        ObjectNode version = store.record();
        version.remove("CreatedTimestamp");
        version.put("VersionNumber", number).put("RegistrationVersionStatus", "DRAFT");
        version.putObject("RegistrationVersionStatusHistory").put("DraftTimestamp", SmsVoiceStore.now());
        saveVersion(region, id, version);
        return version;
    }

    private static void stamp(ObjectNode version, String status, double at) {
        version.put("RegistrationVersionStatus", status);
        String key = switch (status) {
            case "AWS_REVIEWING" -> "AwsReviewingTimestamp";
            case "REQUIRES_AUTHENTICATION" -> "RequiresAuthenticationTimestamp";
            default -> status.charAt(0) + status.substring(1).toLowerCase() + "Timestamp";
        };
        ((ObjectNode) version.get("RegistrationVersionStatusHistory")).put(key, at);
    }

    /** Moves a submitted version through review: SUBMITTED, REVIEWING, then APPROVED or DENIED. */
    private void review(String region, ObjectNode record) {
        String id = SmsVoiceStore.id(record);
        long number = record.path("CurrentVersionNumber").asLong();
        ObjectNode version = store.get(region, SmsVoiceStore.VERSION, id + "/" + String.format("%06d", number))
                .orElse(null);
        if (version == null) {
            return;
        }
        double step = store.config().transitionMillis() / 1000.0;
        double submitted = version.at("/RegistrationVersionStatusHistory/SubmittedTimestamp").asDouble(0);
        String status = version.path("RegistrationVersionStatus").asText();
        if (submitted == 0 || !(status.equals("SUBMITTED") || status.equals("REVIEWING")
                || status.equals("AWS_REVIEWING"))) {
            return;
        }
        double now = SmsVoiceStore.now();
        if (now >= submitted + step && status.equals("SUBMITTED")) {
            stamp(version, "REVIEWING", submitted + step);
            record.put("RegistrationStatus", "REVIEWING");
            status = "REVIEWING";
        }
        if (now >= submitted + 2 * step && status.equals("REVIEWING")) {
            List<ObjectNode> denied = denials(region, record, number);
            if (denied.isEmpty()) {
                stamp(version, "APPROVED", submitted + 2 * step);
                record.put("RegistrationStatus", "COMPLETE").put("ApprovedVersionNumber", number);
                ObjectNode previous = store.get(region, SmsVoiceStore.VERSION, id + "/"
                        + String.format("%06d", number - 1)).orElse(null);
                if (previous != null && "APPROVED".equals(previous.path("RegistrationVersionStatus").asText())) {
                    stamp(previous, "ARCHIVED", submitted + 2 * step);
                    saveVersion(region, id, previous);
                }
                approve(region, record);
            } else {
                stamp(version, "DENIED", submitted + 2 * step);
                ArrayNode reasons = version.putArray("DeniedReasons");
                denied.forEach(reasons::add);
                record.put("RegistrationStatus", record.has("ApprovedVersionNumber") ? "COMPLETE"
                        : "REQUIRES_UPDATES").put("LatestDeniedVersionNumber", number);
            }
        }
        saveVersion(region, id, version);
        service.save(record);
    }

    /** Why a version would be denied: its type's rules the field values break. */
    private List<ObjectNode> denials(String region, ObjectNode record, long number) {
        List<ObjectNode> reasons = new ArrayList<>();
        if (record.path("RegistrationType").asText().equals("US_TEN_DLC_CAMPAIGN_REGISTRATION")) {
            ObjectNode brand = values(region, SmsVoiceStore.id(record), number).get("campaignInfo.brandRegistrationId");
            String brandId = brand == null ? "" : brand.path("TextValue").asText();
            ObjectNode brandRecord = store.get(region, SmsVoiceStore.REGISTRATION, brandId).orElse(null);
            if (brandRecord == null || !"US_TEN_DLC_BRAND_REGISTRATION".equals(brandRecord.path("RegistrationType")
                    .asText()) || !"COMPLETE".equals(brandRecord.path("RegistrationStatus").asText())) {
                reasons.add(out().put("Reason", "BRAND_NOT_REGISTERED")
                        .put("ShortDescription", "The campaign's brand registration isn't COMPLETE."));
            }
        }
        return reasons;
    }

    /** Applies an approved registration: its associated numbers and sender IDs become registered. */
    private void approve(String region, ObjectNode record) {
        String id = SmsVoiceStore.id(record);
        for (ObjectNode association : store.list(region, SmsVoiceStore.ASSOCIATION, id + "/")) {
            String kind = association.path("_kind").asText();
            store.get(region, kind, association.path("ResourceId").asText()).ifPresent(resource -> {
                resource.put("RegistrationId", id);
                if (SmsVoiceStore.SENDER_ID.equals(kind)) {
                    resource.put("Registered", true);
                }
                service.save(resource);
            });
        }
    }

    // ================================================================ definitions

    public ObjectNode describeRegistrationTypeDefinitions(JsonNode in, String region) {
        List<RegistrationType> types = new ArrayList<>();
        if (in.path("RegistrationTypes").isArray() && !in.get("RegistrationTypes").isEmpty()) {
            in.get("RegistrationTypes").forEach(t -> types.add(SmsVoiceCatalog.registrationType(t.asText())));
        } else {
            types.addAll(SmsVoiceCatalog.REGISTRATION_TYPES.values());
        }
        List<JsonNode> views = new ArrayList<>();
        for (RegistrationType type : types) {
            boolean keep = true;
            for (JsonNode filter : in.path("Filters")) {
                Set<String> values = new HashSet<>();
                filter.path("Values").forEach(v -> values.add(v.asText()));
                keep &= switch (filter.path("Name").asText()) {
                    case "supported-association-resource-type" -> type.resourceType() != null
                            && values.contains(type.resourceType());
                    default -> values.contains(type.iso());
                };
            }
            if (!keep) {
                continue;
            }
            ObjectNode view = out().put("RegistrationType", type.name());
            view.putObject("DisplayHints").put("Title", type.title()).put("ShortDescription", type.title());
            ArrayNode associations = view.putArray("SupportedAssociations");
            if (type.resourceType() != null) {
                associations.addObject().put("ResourceType", type.resourceType()).put("IsoCountryCode", type.iso())
                        .put("AssociationBehavior", type.association())
                        .put("DisassociationBehavior", "DISASSOCIATE_ALL_ALLOWS_DELETE_REGISTRATION");
            }
            views.add(view);
        }
        ObjectNode out = out();
        SmsVoiceStore.page(views, in, out, "RegistrationTypeDefinitions");
        return out;
    }

    public ObjectNode describeRegistrationSectionDefinitions(JsonNode in, String region) {
        RegistrationType type = SmsVoiceCatalog.registrationType(SmsVoiceService.req(in, "RegistrationType"));
        Set<String> wanted = new HashSet<>();
        in.path("SectionPaths").forEach(p -> wanted.add(p.asText()));
        Map<String, ObjectNode> sections = new LinkedHashMap<>();
        for (Field field : type.fields()) {
            if (!wanted.isEmpty() && !wanted.contains(field.section())) {
                continue;
            }
            sections.computeIfAbsent(field.section(), section -> {
                ObjectNode view = out().put("SectionPath", section);
                view.putObject("DisplayHints").put("Title", SmsVoiceCatalog.title(section))
                        .put("ShortDescription", SmsVoiceCatalog.title(section));
                return view;
            });
        }
        for (String section : wanted) {
            if (!sections.containsKey(section)) {
                throw SmsVoiceStore.validation("UNKNOWN_REGISTRATION_SECTION", "The registration type has no "
                        + "section " + section + ".");
            }
        }
        ObjectNode out = out().put("RegistrationType", type.name());
        SmsVoiceStore.page(new ArrayList<JsonNode>(sections.values()), in, out, "RegistrationSectionDefinitions");
        return out;
    }

    private static Field field(RegistrationType type, String path) {
        for (Field field : type.fields()) {
            if (field.fullPath().equals(path)) {
                return field;
            }
        }
        throw SmsVoiceStore.validation("UNKNOWN_REGISTRATION_FIELD", "The registration type " + type.name()
                + " has no field " + path + ".");
    }

    public ObjectNode describeRegistrationFieldDefinitions(JsonNode in, String region) {
        RegistrationType type = SmsVoiceCatalog.registrationType(SmsVoiceService.req(in, "RegistrationType"));
        String section = SmsVoiceService.opt(in, "SectionPath");
        Set<String> wanted = new HashSet<>();
        in.path("FieldPaths").forEach(p -> wanted.add(field(type, p.asText()).fullPath()));
        List<JsonNode> views = new ArrayList<>();
        for (Field field : type.fields()) {
            if ((section != null && !section.equals(field.section()))
                    || (!wanted.isEmpty() && !wanted.contains(field.fullPath()))) {
                continue;
            }
            ObjectNode view = out().put("SectionPath", field.section()).put("FieldPath", field.fullPath())
                    .put("FieldType", field.type()).put("FieldRequirement", field.requirement());
            view.putObject("DisplayHints").put("Title", SmsVoiceCatalog.title(field.path()))
                    .put("ShortDescription", SmsVoiceCatalog.title(field.path()));
            if ("SELECT".equals(field.type())) {
                ObjectNode select = view.putObject("SelectValidation").put("MinChoices", 1).put("MaxChoices", 1);
                ArrayNode options = select.putArray("Options");
                field.options().forEach(options::add);
            } else if ("TEXT".equals(field.type())) {
                view.putObject("TextValidation").put("MinLength", 1).put("MaxLength", field.maxLength())
                        .put("Pattern", "^(?!\\s*$)[\\s\\S]+$");
            }
            views.add(view);
        }
        ObjectNode out = out().put("RegistrationType", type.name());
        SmsVoiceStore.page(views, in, out, "RegistrationFieldDefinitions");
        return out;
    }

    // ================================================================ registrations

    public ObjectNode createRegistration(JsonNode in, String region) {
        RegistrationType type = SmsVoiceCatalog.registrationType(SmsVoiceService.req(in, "RegistrationType"));
        if (store.list(region, SmsVoiceStore.REGISTRATION).size() >= 100) {
            throw SmsVoiceStore.quota("REGISTRATIONS_PER_ACCOUNT", "The account has 100 registrations.");
        }
        String id = "registration-" + SmsVoiceStore.hex(32);
        ObjectNode record = store.record();
        record.put("RegistrationArn", store.arn(region, SmsVoiceStore.REGISTRATION + "/" + id))
                .put("RegistrationId", id).put("RegistrationType", type.name())
                .put("RegistrationStatus", "CREATED").put("CurrentVersionNumber", 1);
        record.putObject("AdditionalAttributes").put("ISO_COUNTRY_CODE", type.iso());
        SmsVoiceStore.addTags(record, in.path("Tags"));
        store.put(region, SmsVoiceStore.REGISTRATION, id, record);
        newVersion(region, id, 1);
        ObjectNode out = copy(record, "RegistrationArn", "RegistrationId", "RegistrationType", "RegistrationStatus",
                "CurrentVersionNumber", "AdditionalAttributes", "CreatedTimestamp");
        out.set("Tags", SmsVoiceStore.tagList(record, mapper));
        return out;
    }

    public ObjectNode describeRegistrations(JsonNode in, String region) {
        List<ObjectNode> records = new ArrayList<>();
        if (in.path("RegistrationIds").isArray() && !in.get("RegistrationIds").isEmpty()) {
            in.get("RegistrationIds").forEach(id -> records.add(registration(region, id.asText())));
        } else {
            for (ObjectNode record : store.list(region, SmsVoiceStore.REGISTRATION)) {
                review(region, record);
                records.add(record);
            }
        }
        List<JsonNode> views = new ArrayList<>();
        for (ObjectNode record : records) {
            boolean keep = true;
            for (JsonNode filter : in.path("Filters")) {
                Set<String> values = new HashSet<>();
                filter.path("Values").forEach(v -> values.add(v.asText()));
                keep &= values.contains(record.path("registration-type".equals(filter.path("Name").asText())
                        ? "RegistrationType" : "RegistrationStatus").asText());
            }
            if (keep) {
                views.add(copy(record, REGISTRATION_FIELDS));
            }
        }
        ObjectNode out = out();
        SmsVoiceStore.page(views, in, out, "Registrations");
        return out;
    }

    public ObjectNode deleteRegistration(JsonNode in, String region) {
        ObjectNode record = registration(region, SmsVoiceService.req(in, "RegistrationId"));
        String id = SmsVoiceStore.id(record);
        String status = record.path("RegistrationStatus").asText();
        if (status.equals("SUBMITTED") || status.equals("REVIEWING") || status.equals("AWS_REVIEWING")) {
            throw SmsVoiceStore.conflict("RESOURCE_DELETION_NOT_ALLOWED", SmsVoiceStore.REGISTRATION, id,
                    "A registration under review can't be deleted.");
        }
        for (ObjectNode association : store.list(region, SmsVoiceStore.ASSOCIATION, id + "/")) {
            String kind = association.path("_kind").asText();
            if (store.get(region, kind, association.path("ResourceId").asText()).isPresent()) {
                throw SmsVoiceStore.conflict("PHONE_NUMBER_ASSOCIATED_TO_REGISTRATION", SmsVoiceStore.REGISTRATION,
                        id, "Release the registration's associated resources first.");
            }
        }
        for (String kind : List.of(SmsVoiceStore.VERSION, SmsVoiceStore.FIELD, SmsVoiceStore.ASSOCIATION)) {
            for (ObjectNode child : store.list(region, kind, id + "/")) {
                store.delete(region, kind, SmsVoiceStore.id(child));
            }
        }
        store.delete(region, SmsVoiceStore.REGISTRATION, id);
        record.put("RegistrationStatus", "DELETED");
        return copy(record, REGISTRATION_FIELDS);
    }

    // ---------------------------------------------------------------- versions

    private ObjectNode versionOut(ObjectNode record, ObjectNode version) {
        ObjectNode out = copy(record, "RegistrationArn", "RegistrationId");
        out.setAll(copy(version, "VersionNumber", "RegistrationVersionStatus", "RegistrationVersionStatusHistory"));
        return out;
    }

    public ObjectNode createRegistrationVersion(JsonNode in, String region) {
        ObjectNode record = registration(region, SmsVoiceService.req(in, "RegistrationId"));
        String id = SmsVoiceStore.id(record);
        long current = record.path("CurrentVersionNumber").asLong();
        ObjectNode version = version(region, id, current);
        String status = version.path("RegistrationVersionStatus").asText();
        if (status.equals("DRAFT") || status.equals("SUBMITTED") || status.equals("REVIEWING")) {
            throw SmsVoiceStore.conflict("CREATE_REGISTRATION_VERSION_NOT_ALLOWED", SmsVoiceStore.REGISTRATION, id,
                    "The registration's version " + current + " is " + status + ".");
        }
        ObjectNode next = newVersion(region, id, current + 1);
        for (ObjectNode value : store.list(region, SmsVoiceStore.FIELD, id + "/" + String.format("%06d", current)
                + "/")) {
            ObjectNode copy = value.deepCopy();
            copy.remove("DeniedReason");
            store.put(region, SmsVoiceStore.FIELD, id + "/" + String.format("%06d", current + 1) + "/"
                    + value.get("FieldPath").asText(), copy);
        }
        record.put("CurrentVersionNumber", current + 1);
        service.save(record);
        return versionOut(record, next);
    }

    public ObjectNode discardRegistrationVersion(JsonNode in, String region) {
        ObjectNode record = registration(region, SmsVoiceService.req(in, "RegistrationId"));
        String id = SmsVoiceStore.id(record);
        long current = record.path("CurrentVersionNumber").asLong();
        ObjectNode version = version(region, id, current);
        if (!"DRAFT".equals(version.path("RegistrationVersionStatus").asText()) || current == 1) {
            throw SmsVoiceStore.conflict("DISCARD_REGISTRATION_VERSION_NOT_ALLOWED", SmsVoiceStore.REGISTRATION, id,
                    "Only a later DRAFT version can be discarded.");
        }
        stamp(version, "DISCARDED", SmsVoiceStore.now());
        saveVersion(region, id, version);
        return versionOut(record, version);
    }

    public ObjectNode submitRegistrationVersion(JsonNode in, String region) {
        ObjectNode record = registration(region, SmsVoiceService.req(in, "RegistrationId"));
        String id = SmsVoiceStore.id(record);
        long current = record.path("CurrentVersionNumber").asLong();
        ObjectNode version = version(region, id, current);
        if (!"DRAFT".equals(version.path("RegistrationVersionStatus").asText())) {
            throw SmsVoiceStore.conflict("REGISTRATION_ALREADY_SUBMITTED", SmsVoiceStore.REGISTRATION, id,
                    "The version is " + version.path("RegistrationVersionStatus").asText() + ".");
        }
        RegistrationType type = SmsVoiceCatalog.registrationType(record.get("RegistrationType").asText());
        Map<String, ObjectNode> values = values(region, id, current);
        List<String> missing = new ArrayList<>();
        for (Field field : type.fields()) {
            if ("REQUIRED".equals(field.requirement()) && !values.containsKey(field.fullPath())) {
                missing.add(field.fullPath());
            }
        }
        if (!missing.isEmpty()) {
            throw SmsVoiceStore.validation("FIELD_VALIDATION_FAILED", "The registration is missing required fields: "
                    + String.join(", ", missing) + ".");
        }
        if ("ASSOCIATE_BEFORE_SUBMIT".equals(type.association())
                && store.list(region, SmsVoiceStore.ASSOCIATION, id + "/").isEmpty()) {
            throw SmsVoiceStore.validation("INVALID_REGISTRATION_ASSOCIATION", "Associate a " + type.resourceType()
                    + " resource before submitting.");
        }
        stamp(version, "SUBMITTED", SmsVoiceStore.now());
        boolean awsReview = in.path("AwsReview").asBoolean(false);
        version.put("AwsReview", awsReview);
        saveVersion(region, id, version);
        record.put("RegistrationStatus", "SUBMITTED");
        service.save(record);
        return versionOut(record, version).put("AwsReview", awsReview);
    }

    public ObjectNode describeRegistrationVersions(JsonNode in, String region) {
        ObjectNode record = registration(region, SmsVoiceService.req(in, "RegistrationId"));
        String id = SmsVoiceStore.id(record);
        Set<Long> wanted = new HashSet<>();
        in.path("VersionNumbers").forEach(n -> wanted.add(n.asLong()));
        List<JsonNode> views = new ArrayList<>();
        List<ObjectNode> versions = new ArrayList<>(store.list(region, SmsVoiceStore.VERSION, id + "/"));
        versions.sort((a, b) -> Long.compare(b.path("VersionNumber").asLong(), a.path("VersionNumber").asLong()));
        for (ObjectNode version : versions) {
            if (!wanted.isEmpty() && !wanted.contains(version.path("VersionNumber").asLong())) {
                continue;
            }
            boolean keep = true;
            for (JsonNode filter : in.path("Filters")) {
                Set<String> values = new HashSet<>();
                filter.path("Values").forEach(v -> values.add(v.asText()));
                keep &= values.contains(version.path("RegistrationVersionStatus").asText());
            }
            if (keep) {
                views.add(copy(version, "VersionNumber", "RegistrationVersionStatus",
                        "RegistrationVersionStatusHistory", "DeniedReasons", "Feedback"));
            }
        }
        ObjectNode out = copy(record, "RegistrationArn", "RegistrationId");
        SmsVoiceStore.page(views, in, out, "RegistrationVersions");
        return out;
    }

    // ---------------------------------------------------------------- field values

    private Map<String, ObjectNode> values(String region, String id, long number) {
        Map<String, ObjectNode> values = new LinkedHashMap<>();
        for (ObjectNode value : store.list(region, SmsVoiceStore.FIELD, id + "/" + String.format("%06d", number)
                + "/")) {
            values.put(value.get("FieldPath").asText(), value);
        }
        return values;
    }

    private ObjectNode editable(String region, ObjectNode record) {
        long current = record.path("CurrentVersionNumber").asLong();
        ObjectNode version = version(region, SmsVoiceStore.id(record), current);
        if (!"DRAFT".equals(version.path("RegistrationVersionStatus").asText())) {
            throw SmsVoiceStore.conflict("EDIT_REGISTRATION_FIELD_VALUES_NOT_ALLOWED", SmsVoiceStore.REGISTRATION,
                    SmsVoiceStore.id(record), "Field values change only in a DRAFT version; create a new version.");
        }
        return version;
    }

    public ObjectNode putRegistrationFieldValue(JsonNode in, String region) {
        ObjectNode record = registration(region, SmsVoiceService.req(in, "RegistrationId"));
        ObjectNode version = editable(region, record);
        RegistrationType type = SmsVoiceCatalog.registrationType(record.get("RegistrationType").asText());
        Field field = field(type, SmsVoiceService.req(in, "FieldPath"));
        ObjectNode value = store.record();
        value.remove("CreatedTimestamp");
        value.put("FieldPath", field.fullPath());
        switch (field.type()) {
            case "SELECT" -> {
                JsonNode choices = in.path("SelectChoices");
                if (!choices.isArray() || choices.size() != 1 || !field.options().contains(choices.get(0).asText())) {
                    throw SmsVoiceStore.validation("FIELD_VALIDATION_FAILED", field.fullPath() + " takes one of "
                            + String.join(", ", field.options()) + ".");
                }
                value.set("SelectChoices", choices.deepCopy());
            }
            case "ATTACHMENT" -> {
                String attachment = SmsVoiceService.req(in, "RegistrationAttachmentId");
                ObjectNode found = service.require(region, SmsVoiceStore.ATTACHMENT, attachment);
                if (!"UPLOAD_COMPLETE".equals(found.path("AttachmentStatus").asText())) {
                    throw SmsVoiceStore.validation("FIELD_VALIDATION_FAILED", "The attachment didn't upload.");
                }
                value.put("RegistrationAttachmentId", attachment);
            }
            default -> {
                String text = SmsVoiceService.req(in, "TextValue");
                if (text.length() > field.maxLength()) {
                    throw SmsVoiceStore.validation("FIELD_VALIDATION_FAILED", field.fullPath() + " takes at most "
                            + field.maxLength() + " characters.");
                }
                if (field.path().toLowerCase().contains("email") && !text.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                    throw SmsVoiceStore.validation("FIELD_VALIDATION_FAILED", field.fullPath()
                            + " must be an email address.");
                }
                if (field.path().equals("supportPhoneNumber")) {
                    SmsVoiceCatalog.e164(text, field.fullPath());
                }
                if (field.path().equals("website") && !text.matches("https?://\\S+")) {
                    throw SmsVoiceStore.validation("FIELD_VALIDATION_FAILED", field.fullPath() + " must be a URL.");
                }
                value.put("TextValue", text);
            }
        }
        String id = SmsVoiceStore.id(record);
        long number = version.get("VersionNumber").asLong();
        store.put(region, SmsVoiceStore.FIELD, id + "/" + String.format("%06d", number) + "/" + field.fullPath(),
                value);
        ObjectNode out = copy(record, "RegistrationArn", "RegistrationId").put("VersionNumber", number);
        out.setAll(copy(value, "FieldPath", "SelectChoices", "TextValue", "RegistrationAttachmentId"));
        return out;
    }

    public ObjectNode deleteRegistrationFieldValue(JsonNode in, String region) {
        ObjectNode record = registration(region, SmsVoiceService.req(in, "RegistrationId"));
        ObjectNode version = editable(region, record);
        RegistrationType type = SmsVoiceCatalog.registrationType(record.get("RegistrationType").asText());
        Field field = field(type, SmsVoiceService.req(in, "FieldPath"));
        String id = SmsVoiceStore.id(record);
        long number = version.get("VersionNumber").asLong();
        String key = id + "/" + String.format("%06d", number) + "/" + field.fullPath();
        ObjectNode value = store.get(region, SmsVoiceStore.FIELD, key).orElseThrow(() ->
                SmsVoiceStore.validation("REGISTRATION_FIELD_CANNOT_BE_DELETED", field.fullPath() + " has no value."));
        store.delete(region, SmsVoiceStore.FIELD, key);
        ObjectNode out = copy(record, "RegistrationArn", "RegistrationId").put("VersionNumber", number);
        out.setAll(copy(value, "FieldPath", "SelectChoices", "TextValue", "RegistrationAttachmentId"));
        return out;
    }

    public ObjectNode describeRegistrationFieldValues(JsonNode in, String region) {
        ObjectNode record = registration(region, SmsVoiceService.req(in, "RegistrationId"));
        String id = SmsVoiceStore.id(record);
        long number = in.hasNonNull("VersionNumber") ? in.get("VersionNumber").asLong()
                : record.path("CurrentVersionNumber").asLong();
        version(region, id, number);
        String section = SmsVoiceService.opt(in, "SectionPath");
        Set<String> wanted = new HashSet<>();
        in.path("FieldPaths").forEach(p -> wanted.add(p.asText()));
        List<JsonNode> views = new ArrayList<>();
        values(region, id, number).forEach((path, value) -> {
            if ((section == null || path.startsWith(section + ".")) && (wanted.isEmpty() || wanted.contains(path))) {
                views.add(copy(value, "FieldPath", "SelectChoices", "TextValue", "RegistrationAttachmentId",
                        "DeniedReason", "Feedback"));
            }
        });
        ObjectNode out = copy(record, "RegistrationArn", "RegistrationId").put("VersionNumber", number);
        SmsVoiceStore.page(views, in, out, "RegistrationFieldValues");
        return out;
    }

    // ---------------------------------------------------------------- associations

    public ObjectNode createRegistrationAssociation(JsonNode in, String region) {
        ObjectNode record = registration(region, SmsVoiceService.req(in, "RegistrationId"));
        String id = SmsVoiceStore.id(record);
        RegistrationType type = SmsVoiceCatalog.registrationType(record.get("RegistrationType").asText());
        if (type.resourceType() == null) {
            throw SmsVoiceStore.validation("INVALID_REGISTRATION_ASSOCIATION", "A " + type.name()
                    + " has no associations.");
        }
        ObjectNode resource = service.member(region, SmsVoiceService.req(in, "ResourceId"), type.iso());
        String kind = SmsVoiceService.kind(resource);
        boolean fits = SmsVoiceStore.SENDER_ID.equals(kind) ? type.resourceType().equals("SENDER_ID")
                : type.resourceType().equals(resource.path("NumberType").asText());
        if (!fits || !type.iso().equals(resource.path("IsoCountryCode").asText())) {
            throw SmsVoiceStore.validation("INVALID_REGISTRATION_ASSOCIATION", "A " + type.name() + " associates "
                    + type.resourceType() + " resources in " + type.iso() + ".");
        }
        String resourceId = SmsVoiceStore.id(resource);
        for (ObjectNode other : store.everywhere(SmsVoiceStore.ASSOCIATION).stream()
                .filter(entry -> entry.accountId().equals(store.account())).map(entry -> entry.value()).toList()) {
            if (resourceId.equals(other.path("ResourceId").asText())) {
                throw SmsVoiceStore.conflict("RESOURCE_ALREADY_ASSOCIATED", kind, resourceId,
                        resourceId + " is already associated with a registration.");
            }
        }
        if (store.list(region, SmsVoiceStore.ASSOCIATION, id + "/").size() >= 10) {
            throw SmsVoiceStore.quota("ASSOCIATIONS_PER_REGISTRATION", "A registration has at most 10 associations.");
        }
        ObjectNode association = store.record();
        association.put("ResourceArn", SmsVoiceService.arnOf(resource)).put("ResourceId", resourceId)
                .put("ResourceType", type.resourceType()).put("IsoCountryCode", type.iso()).put("_kind", kind);
        if (resource.has("PhoneNumber")) {
            association.put("PhoneNumber", resource.get("PhoneNumber").asText());
        }
        store.put(region, SmsVoiceStore.ASSOCIATION, id + "/" + resourceId, association);
        if ("COMPLETE".equals(record.path("RegistrationStatus").asText())) {
            approve(region, record);
        }
        ObjectNode out = copy(record, "RegistrationArn", "RegistrationId", "RegistrationType");
        out.setAll(copy(association, "ResourceArn", "ResourceId", "ResourceType", "IsoCountryCode", "PhoneNumber"));
        return out;
    }

    public ObjectNode listRegistrationAssociations(JsonNode in, String region) {
        ObjectNode record = registration(region, SmsVoiceService.req(in, "RegistrationId"));
        List<JsonNode> views = new ArrayList<>();
        for (ObjectNode association : store.list(region, SmsVoiceStore.ASSOCIATION, SmsVoiceStore.id(record) + "/")) {
            boolean keep = true;
            for (JsonNode filter : in.path("Filters")) {
                Set<String> values = new HashSet<>();
                filter.path("Values").forEach(v -> values.add(v.asText()));
                keep &= values.contains(association.path("resource-type".equals(filter.path("Name").asText())
                        ? "ResourceType" : "IsoCountryCode").asText());
            }
            if (keep) {
                views.add(copy(association, "ResourceArn", "ResourceId", "ResourceType", "IsoCountryCode",
                        "PhoneNumber"));
            }
        }
        ObjectNode out = copy(record, "RegistrationArn", "RegistrationId", "RegistrationType");
        SmsVoiceStore.page(views, in, out, "RegistrationAssociations");
        return out;
    }

    // ---------------------------------------------------------------- attachments

    private static final String[] ATTACHMENT_FIELDS = {"RegistrationAttachmentArn", "RegistrationAttachmentId",
        "AttachmentStatus", "AttachmentUploadErrorReason", "CreatedTimestamp", "AttachmentUrl"};

    public ObjectNode createRegistrationAttachment(JsonNode in, String region) {
        byte[] body;
        String url = SmsVoiceService.opt(in, "AttachmentUrl");
        if (in.hasNonNull("AttachmentBody") == (url != null)) {
            throw SmsVoiceStore.validation("PARAMETERS_CANNOT_BE_USED_TOGETHER", "Give either AttachmentBody or "
                    + "AttachmentUrl.");
        }
        if (url != null) {
            if (!url.startsWith("s3://") || url.indexOf('/', 5) < 0) {
                throw SmsVoiceStore.validation("INVALID_PARAMETER", "AttachmentUrl must be an s3:// URL.");
            }
            String path = url.substring(5);
            try {
                S3Object object = s3.get().getObject(path.substring(0, path.indexOf('/')),
                        path.substring(path.indexOf('/') + 1));
                body = object.getData() == null ? new byte[0] : object.getData();
            } catch (AwsException e) {
                throw SmsVoiceStore.validation("RESOURCE_NOT_ACCESSIBLE", "The attachment " + url
                        + " can't be read: " + e.getMessage());
            }
        } else {
            try {
                body = Base64.getDecoder().decode(in.get("AttachmentBody").asText());
            } catch (IllegalArgumentException e) {
                body = in.get("AttachmentBody").asText().getBytes(StandardCharsets.UTF_8);
            }
        }
        if (body.length > 500 * 1024) {
            throw SmsVoiceStore.validation("MAXIMUM_SIZE_EXCEEDED", "Attachments are at most 500 KB.");
        }
        boolean image = body.length > 4 && ((body[0] & 0xff) == 0x89 && body[1] == 'P'
                || (body[0] & 0xff) == 0xff && (body[1] & 0xff) == 0xd8
                || body[0] == '%' && body[1] == 'P' && body[2] == 'D' && body[3] == 'F');
        if (!image) {
            throw SmsVoiceStore.validation("ATTACHMENT_TYPE_NOT_SUPPORTED", "Attachments are PNG, JPEG or PDF files.");
        }
        if (store.list(region, SmsVoiceStore.ATTACHMENT).size() >= 100) {
            throw SmsVoiceStore.quota("REGISTRATION_ATTACHMENTS_PER_ACCOUNT", "The account has 100 attachments.");
        }
        String id = "attachment-" + SmsVoiceStore.hex(32);
        ObjectNode record = store.record();
        record.put("RegistrationAttachmentArn", store.arn(region, SmsVoiceStore.ATTACHMENT + "/" + id))
                .put("RegistrationAttachmentId", id).put("AttachmentStatus", "UPLOAD_COMPLETE");
        if (url != null) {
            record.put("AttachmentUrl", url);
        }
        SmsVoiceStore.addTags(record, in.path("Tags"));
        store.put(region, SmsVoiceStore.ATTACHMENT, id, record);
        ObjectNode out = copy(record, "RegistrationAttachmentArn", "RegistrationAttachmentId", "AttachmentStatus",
                "CreatedTimestamp");
        out.set("Tags", SmsVoiceStore.tagList(record, mapper));
        return out;
    }

    public ObjectNode describeRegistrationAttachments(JsonNode in, String region) {
        List<ObjectNode> records = new ArrayList<>();
        if (in.path("RegistrationAttachmentIds").isArray() && !in.get("RegistrationAttachmentIds").isEmpty()) {
            in.get("RegistrationAttachmentIds").forEach(id -> records.add(service.require(region,
                    SmsVoiceStore.ATTACHMENT, id.asText())));
        } else {
            records.addAll(store.list(region, SmsVoiceStore.ATTACHMENT));
        }
        List<JsonNode> views = new ArrayList<>();
        for (ObjectNode record : records) {
            boolean keep = true;
            for (JsonNode filter : in.path("Filters")) {
                Set<String> values = new HashSet<>();
                filter.path("Values").forEach(v -> values.add(v.asText()));
                keep &= values.contains(record.path("AttachmentStatus").asText());
            }
            if (keep) {
                views.add(copy(record, ATTACHMENT_FIELDS));
            }
        }
        ObjectNode out = out();
        SmsVoiceStore.page(views, in, out, "RegistrationAttachments");
        return out;
    }

    public ObjectNode deleteRegistrationAttachment(JsonNode in, String region) {
        ObjectNode record = service.require(region, SmsVoiceStore.ATTACHMENT,
                SmsVoiceService.req(in, "RegistrationAttachmentId"));
        String id = SmsVoiceStore.id(record);
        for (AccountEntryView value : fieldValues()) {
            if (id.equals(value.value().path("RegistrationAttachmentId").asText())) {
                throw SmsVoiceStore.conflict("RESOURCE_DELETION_NOT_ALLOWED", SmsVoiceStore.ATTACHMENT, id,
                        "A registration field uses the attachment.");
            }
        }
        store.delete(region, SmsVoiceStore.ATTACHMENT, id);
        record.put("AttachmentStatus", "DELETED");
        return copy(record, "RegistrationAttachmentArn", "RegistrationAttachmentId", "AttachmentStatus",
                "AttachmentUploadErrorReason", "CreatedTimestamp");
    }

    private record AccountEntryView(ObjectNode value) {
    }

    private List<AccountEntryView> fieldValues() {
        return store.everywhere(SmsVoiceStore.FIELD).stream()
                .filter(entry -> entry.accountId().equals(store.account()))
                .map(entry -> new AccountEntryView(entry.value())).toList();
    }
}
