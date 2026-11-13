package io.floci.aws.services.healthlake;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HealthLake's Data Transformation Agent: profiles that turn C-CDA documents (Velocity templates) or CSV tables (a
 * YAML mapping) into FHIR R4, kept as an editable draft (version 0) and published as immutable versions; jobs that
 * convert S3 input with a published profile into FHIR in S3 (or, through a FHIR import job, into a data store); the
 * synchronous conversion of one input; and the agent that edits a profile in conversation. floci's agent is
 * deterministic: it drafts a CSV mapping from a profile's sample data by recognizing common column names, shows,
 * publishes and explains a profile, and lists the C-CDA sections a profile's templates cover.
 */
@ApplicationScoped
public class HealthLakeTransformService implements Resettable {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> FORMATS = Set.of("CCDA", "CSV");
    static final String CSV_MAPPING_FILE = "mapping.yaml";
    private static final Map<String, String> CCDA_SECTIONS = Map.of(
            "2.16.840.1.113883.10.20.22.2.5.1", "Problems",
            "2.16.840.1.113883.10.20.22.2.6.1", "Allergies",
            "2.16.840.1.113883.10.20.22.2.1.1", "Medications",
            "2.16.840.1.113883.10.20.22.2.3.1", "Results",
            "2.16.840.1.113883.10.20.22.2.4.1", "Vital Signs",
            "2.16.840.1.113883.10.20.22.2.2.1", "Immunizations",
            "2.16.840.1.113883.10.20.22.2.7.1", "Procedures",
            "2.16.840.1.113883.10.20.22.2.22.1", "Encounters");

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final Instance<S3Service> s3;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "healthlake-transform");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public HealthLakeTransformService(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config,
                                      Instance<S3Service> s3) {
        this.storage = storageFactory.create("healthlake", "transformation.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
        this.s3 = s3;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public void clear() {
        storage.clear();
    }

    private String account() {
        return storage.accountId();
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static String profileKey(String region, String id) {
        return region + "/profile/" + id;
    }

    // ---------------------------------------------------------------- profiles

    public synchronized ObjectNode createProfile(JsonNode input, String region) {
        String account = account();
        String clientToken = input.path("ClientToken").asText(null);
        if (clientToken != null) {
            for (ObjectNode existing : storage.scanForAccount(account, key -> key.startsWith(region + "/profile/"))) {
                if (clientToken.equals(existing.path("_clientToken").asText(null))) {
                    return profileSummary(existing, 0);
                }
            }
        }
        String format = format(input.path("SourceFormat").asText(null));
        String name = text(input, "ProfileName");
        JsonNode source = input.path("Source");
        List<String> given = new ArrayList<>();
        source.fieldNames().forEachRemaining(given::add);
        if (given.size() != 1) {
            throw validation("Source takes exactly one of StarterProfile, ExistingVersionedProfileId, ProfileMapping "
                    + "and SampleData.");
        }
        ObjectNode mapping;
        String sample = null;
        switch (given.get(0)) {
            case "StarterProfile" -> {
                String starter = text(source.get("StarterProfile"), "StarterProfileName");
                if (!"CCDA".equals(format) || !HealthLakeCcdaMapper.STARTER.equals(starter)) {
                    throw validation("The starter profile is " + HealthLakeCcdaMapper.STARTER + ", for CCDA.");
                }
                mapping = mapper.valueToTree(HealthLakeCcdaMapper.starter());
            }
            case "ExistingVersionedProfileId" -> {
                JsonNode existing = source.get("ExistingVersionedProfileId");
                ObjectNode from = profile(region, text(existing, "ProfileId"));
                if (!format.equals(from.path("SourceFormat").asText())) {
                    throw validation("Profile " + from.path("ProfileId").asText() + " converts "
                            + from.path("SourceFormat").asText() + ", not " + format + ".");
                }
                mapping = version(from, existing.path("Version").asInt(0)).path("mapping").deepCopy();
            }
            case "ProfileMapping" -> {
                mapping = (ObjectNode) source.get("ProfileMapping").path("ProfileMapping").deepCopy();
                check(format, mapping, true);
            }
            case "SampleData" -> {
                if (!"CSV".equals(format)) {
                    throw validation("A profile starts from sample data only for CSV.");
                }
                sample = text(source.get("SampleData"), "S3Uri");
                if (!sample.startsWith("s3://")) {
                    throw validation("SampleData.S3Uri must be an S3 URI.");
                }
                mapping = mapper.createObjectNode();
            }
            default -> throw validation("Unknown profile source " + given.get(0) + ".");
        }
        String id = HexFormat.of().formatHex(bytes(16));
        ObjectNode profile = mapper.createObjectNode();
        profile.put("ProfileId", id).put("ProfileName", name).put("SourceFormat", format)
                .put("TargetFormat", "FHIR_R4").put("_created", now());
        if (input.hasNonNull("ProfileDescription")) {
            profile.put("ProfileDescription", input.get("ProfileDescription").asText());
        }
        if (input.hasNonNull("KmsKeyId")) {
            profile.put("KmsKeyId", input.get("KmsKeyId").asText());
        }
        if (sample != null) {
            profile.put("_sample", sample);
        }
        if (clientToken != null) {
            profile.put("_clientToken", clientToken);
        }
        profile.set("_tags", input.path("Tags").isObject() ? input.get("Tags").deepCopy() : mapper.createObjectNode());
        ObjectNode draft = profile.putObject("_versions").putObject("0");
        draft.set("mapping", mapping);
        draft.put("LastUpdatedAt", now());
        storage.putForAccount(account, profileKey(region, id), profile);
        return profileSummary(profile, 0);
    }

    private static String format(String format) {
        if (format == null || !FORMATS.contains(format)) {
            throw validation("SourceFormat must be CCDA or CSV.");
        }
        return format;
    }

    /** Checks a mapping as its format reads it: a CSV YAML file, or C-CDA templates. */
    private static void check(String format, JsonNode mapping, boolean allowEmpty) {
        if (!mapping.isObject()) {
            throw validation("ProfileMapping maps file paths to their content.");
        }
        if (mapping.isEmpty()) {
            if (allowEmpty) {
                return;
            }
            throw validation("The profile's mapping is empty.");
        }
        if ("CSV".equals(format)) {
            csvProfile(mapping);
            return;
        }
        boolean template = false;
        for (Iterator<String> it = mapping.fieldNames(); it.hasNext(); ) {
            String file = it.next();
            template |= file.startsWith("Resources/") && file.endsWith(".vm");
        }
        if (!template) {
            throw validation("A C-CDA profile has at least one template under Resources/, such as "
                    + "Resources/Patient.vm.");
        }
    }

    /** A CSV profile's YAML: the mapping's one YAML file, parsed and checked. */
    static JsonNode csvProfile(JsonNode mapping) {
        String yaml = null;
        for (Iterator<Map.Entry<String, JsonNode>> it = mapping.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> file = it.next();
            String name = file.getKey().toLowerCase(Locale.ROOT);
            if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                if (yaml != null) {
                    throw validation("A CSV profile has one YAML mapping file.");
                }
                yaml = file.getValue().asText();
            }
        }
        if (yaml == null) {
            throw validation("A CSV profile's mapping is a YAML file, such as " + CSV_MAPPING_FILE + ".");
        }
        try {
            return HealthLakeCsvMapper.profile(yaml);
        } catch (IllegalArgumentException e) {
            throw validation(e.getMessage());
        }
    }

    private ObjectNode profile(String region, String id) {
        return storage.getForAccount(account(), profileKey(region, id))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Profile " + id + " not found.", 400));
    }

    private JsonNode version(ObjectNode profile, int version) {
        JsonNode found = profile.path("_versions").get(Integer.toString(version));
        if (found == null) {
            throw new AwsException("ResourceNotFoundException", "Profile " + profile.path("ProfileId").asText()
                    + " has no version " + version + ".", 400);
        }
        return found;
    }

    private static int latest(ObjectNode profile) {
        int latest = 0;
        for (Iterator<String> it = profile.path("_versions").fieldNames(); it.hasNext(); ) {
            latest = Math.max(latest, Integer.parseInt(it.next()));
        }
        return latest;
    }

    private ObjectNode profileSummary(ObjectNode profile, int version) {
        ObjectNode response = mapper.createObjectNode();
        response.put("ProfileId", profile.path("ProfileId").asText()).put("Version", version)
                .put("SourceFormat", profile.path("SourceFormat").asText()).put("TargetFormat", "FHIR_R4")
                .put("ProfileName", profile.path("ProfileName").asText())
                .put("LastUpdatedAt", version(profile, version).path("LastUpdatedAt").asDouble());
        return response;
    }

    public synchronized ObjectNode getProfile(JsonNode input, String region) {
        ObjectNode profile = profile(region, text(input, "ProfileId"));
        int version = input.hasNonNull("ProfileVersion") ? input.get("ProfileVersion").asInt() : latest(profile);
        JsonNode stored = version(profile, version);
        ObjectNode response = profileSummary(profile, version);
        response.set("ProfileMapping", stored.path("mapping").deepCopy());
        if (profile.hasNonNull("ProfileDescription")) {
            response.put("ProfileDescription", profile.get("ProfileDescription").asText());
        }
        if (stored.hasNonNull("ChangeDescription")) {
            response.put("ChangeDescription", stored.get("ChangeDescription").asText());
        }
        return response;
    }

    public synchronized ObjectNode updateProfile(JsonNode input, String region) {
        ObjectNode profile = profile(region, text(input, "ProfileId"));
        if (!input.path("ProfileMapping").isObject()) {
            throw validation("ProfileMapping is required.");
        }
        check(profile.path("SourceFormat").asText(), input.get("ProfileMapping"), false);
        ObjectNode draft = (ObjectNode) profile.path("_versions").get("0");
        draft.set("mapping", input.get("ProfileMapping").deepCopy());
        draft.put("LastUpdatedAt", now());
        if (input.hasNonNull("ChangeDescription")) {
            draft.put("ChangeDescription", input.get("ChangeDescription").asText());
        }
        storage.putForAccount(account(), profileKey(region, profile.path("ProfileId").asText()), profile);
        ObjectNode response = profileSummary(profile, 0);
        response.remove("Version");
        return response;
    }

    /** Publishes the draft (or a version) as the next immutable version. */
    public synchronized ObjectNode publishProfile(JsonNode input, String region) {
        ObjectNode profile = profile(region, text(input, "ProfileId"));
        String format = format(input.path("SourceFormat").asText(null));
        if (!format.equals(profile.path("SourceFormat").asText())) {
            throw validation("Profile " + profile.path("ProfileId").asText() + " converts "
                    + profile.path("SourceFormat").asText() + ", not " + format + ".");
        }
        int from = input.path("FromExistingVersion").asInt(0);
        JsonNode source = version(profile, from);
        check(format, source.path("mapping"), false);
        int next = latest(profile) + 1;
        ObjectNode published = ((ObjectNode) profile.path("_versions")).putObject(Integer.toString(next));
        published.set("mapping", source.path("mapping").deepCopy());
        published.put("LastUpdatedAt", now());
        if (input.hasNonNull("ChangeDescription")) {
            published.put("ChangeDescription", input.get("ChangeDescription").asText());
        }
        storage.putForAccount(account(), profileKey(region, profile.path("ProfileId").asText()), profile);
        return profileSummary(profile, next);
    }

    public synchronized ObjectNode listProfiles(JsonNode input, String region) {
        String format = format(input.path("SourceFormat").asText(null));
        List<ObjectNode> items = new ArrayList<>();
        List<ObjectNode> profiles = new ArrayList<>(storage.scanForAccount(account(), key -> key.startsWith(region
                + "/profile/")));
        profiles.sort((a, b) -> Double.compare(a.path("_created").asDouble(), b.path("_created").asDouble()));
        for (ObjectNode profile : profiles) {
            if (!format.equals(profile.path("SourceFormat").asText())) {
                continue;
            }
            ObjectNode summary = profileSummary(profile, latest(profile));
            if (profile.hasNonNull("ProfileDescription")) {
                summary.put("ProfileDescription", profile.get("ProfileDescription").asText());
            }
            items.add(summary);
        }
        return page(items, input, "Items");
    }

    public synchronized ObjectNode listProfileVersions(JsonNode input, String region) {
        ObjectNode profile = profile(region, text(input, "ProfileId"));
        List<ObjectNode> items = new ArrayList<>();
        for (int version = latest(profile); version >= 0; version--) {
            JsonNode stored = profile.path("_versions").get(Integer.toString(version));
            if (stored == null) {
                continue;
            }
            ObjectNode summary = profileSummary(profile, version);
            if (stored.hasNonNull("ChangeDescription")) {
                summary.put("ChangeDescription", stored.get("ChangeDescription").asText());
            }
            items.add(summary);
        }
        return page(items, input, "Items");
    }

    public synchronized ObjectNode deleteProfile(JsonNode input, String region) {
        ObjectNode profile = profile(region, text(input, "ProfileId"));
        storage.deleteForAccount(account(), profileKey(region, profile.path("ProfileId").asText()));
        ObjectNode response = mapper.createObjectNode();
        response.put("ProfileId", profile.path("ProfileId").asText())
                .put("ProfileName", profile.path("ProfileName").asText()).put("DeletionTime", now());
        return response;
    }

    /** The mapping of a profile's latest published version, for a job; a profile with none cannot run one. */
    synchronized Published published(String region, String profileId) {
        ObjectNode profile = profile(region, profileId);
        int version = latest(profile);
        if (version < 1) {
            throw validation("Profile " + profileId + " has no published version; publish it before running a job.");
        }
        return new Published(profileId, profile.path("ProfileName").asText(), profile.path("SourceFormat").asText(),
                version, version(profile, version).path("mapping").deepCopy());
    }

    /** A published profile version, as a job reads it. */
    record Published(String profileId, String profileName, String format, int version, JsonNode mapping) {
    }

    // ---------------------------------------------------------------- conversion

    /** What a conversion made: its resources, the files it read and failed on, and its warnings. */
    record Conversion(List<JsonNode> resources, int filesScanned, int filesConverted, int filesFailed,
                      List<String> warnings, ObjectNode drift) {
    }

    /**
     * Converts input files with a published profile: C-CDA documents one at a time, CSV files together as the
     * tables they are named after. {@code scope} namespaces generated CSV ids.
     */
    Conversion convert(Published profile, Map<String, String> files, String scope, boolean provenance,
                       boolean drift) {
        List<JsonNode> resources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int converted = 0;
        int failed = 0;
        ObjectNode report = mapper.createObjectNode();
        if ("CCDA".equals(profile.format())) {
            Map<String, String> templates = new LinkedHashMap<>();
            profile.mapping().fields().forEachRemaining(entry -> templates.put(entry.getKey(), entry.getValue()
                    .asText()));
            ArrayNode results = report.putArray("fileDriftResults");
            for (Map.Entry<String, String> file : files.entrySet()) {
                try {
                    List<JsonNode> made = HealthLakeCcdaMapper.convert(templates, file.getValue());
                    resources.addAll(made);
                    if (provenance) {
                        resources.addAll(provenance(file.getKey(), made));
                    }
                    converted++;
                    if (drift) {
                        results.add(ccdaDrift(file.getValue(), templates, made));
                    }
                } catch (IllegalArgumentException e) {
                    failed++;
                    warnings.add(file.getKey() + ": " + e.getMessage());
                }
            }
        } else {
            JsonNode yaml = csvProfile(profile.mapping());
            Map<String, HealthLakeCsvMapper.Table> tables = new LinkedHashMap<>();
            for (Map.Entry<String, String> file : files.entrySet()) {
                String name = file.getKey().substring(file.getKey().lastIndexOf('/') + 1);
                if (!name.toLowerCase(Locale.ROOT).endsWith(".csv") || file.getValue().isEmpty()) {
                    continue;
                }
                String table = name.substring(0, name.length() - 4);
                if (tables.containsKey(table)) {
                    throw new IllegalArgumentException("Two CSV files are named " + name + ".");
                }
                tables.put(table, HealthLakeCsvMapper.parse(file.getValue()));
            }
            HealthLakeCsvMapper.Result result = HealthLakeCsvMapper.convert(yaml, tables, scope);
            resources.addAll(result.resources());
            warnings.addAll(result.warnings());
            converted = tables.size();
            if (provenance) {
                resources.addAll(provenance(String.join(",", tables.keySet()), result.resources()));
            }
            if (drift) {
                report = csvDrift(yaml, tables, result);
            }
        }
        return new Conversion(resources, files.size(), converted, failed, warnings, drift ? report : null);
    }

    /** The Device that made a conversion, the DocumentReference of its source, and a Provenance naming both. */
    private List<JsonNode> provenance(String source, List<JsonNode> made) {
        List<JsonNode> out = new ArrayList<>();
        String deviceId = UUID.nameUUIDFromBytes("floci-healthlake-data-transformation-agent"
                .getBytes(StandardCharsets.UTF_8)).toString();
        ObjectNode device = mapper.createObjectNode();
        device.put("resourceType", "Device").put("id", deviceId);
        device.putArray("deviceName").addObject().put("name", "HealthLake Data Transformation Agent")
                .put("type", "manufacturer-name");
        out.add(device);
        String documentId = UUID.nameUUIDFromBytes(("source|" + source).getBytes(StandardCharsets.UTF_8)).toString();
        ObjectNode document = mapper.createObjectNode();
        document.put("resourceType", "DocumentReference").put("id", documentId).put("status", "current");
        document.putArray("content").addObject().putObject("attachment").put("title", source);
        out.add(document);
        ObjectNode provenance = mapper.createObjectNode();
        provenance.put("resourceType", "Provenance").put("id", UUID.randomUUID().toString())
                .put("recorded", Instant.now().toString());
        ArrayNode targets = provenance.putArray("target");
        made.forEach(resource -> targets.addObject().put("reference", resource.path("resourceType").asText() + "/"
                + resource.path("id").asText()));
        provenance.putArray("agent").addObject().putObject("who").put("reference", "Device/" + deviceId);
        provenance.putArray("entity").addObject().put("role", "source").putObject("what")
                .put("reference", "DocumentReference/" + documentId);
        out.add(provenance);
        return out;
    }

    private ObjectNode ccdaDrift(String xml, Map<String, String> templates, List<JsonNode> made) {
        ObjectNode result = mapper.createObjectNode();
        Map<String, Object> document = HealthLakeCcdaMapper.parse(xml);
        Object root = document.get("ClinicalDocument");
        String oid = root instanceof Map<?, ?> map ? templateRoot(map.get("templateId")) : null;
        if (oid != null) {
            result.put("documentOid", oid);
        }
        String all = String.join("\n", templates.values());
        Set<String> present = new LinkedHashSet<>();
        collectSectionIds(root, present);
        ObjectNode unknown = result.putObject("unknownSections");
        int covered = 0;
        for (String id : present) {
            if (all.contains(id)) {
                covered++;
            } else {
                unknown.put(id, unknown.path(id).asInt(0) + 1);
            }
        }
        double rate = present.isEmpty() ? 1.0 : (double) covered / present.size();
        result.put("sectionCoverageRate", rate).put("overallCoverageRate", rate)
                .put("resourceAccuracy", made.isEmpty() ? 0.0 : 1.0);
        result.putObject("unknownEntries");
        result.putObject("missingExpectedResources");
        return result;
    }

    private static String templateRoot(Object templateId) {
        for (Object template : HealthLakeVelocityHelpers.list(templateId)) {
            if (template instanceof Map<?, ?> t && t.get("root") != null) {
                return String.valueOf(t.get("root"));
            }
        }
        return null;
    }

    private static void collectSectionIds(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("section".equals(entry.getKey())) {
                    for (Object section : HealthLakeVelocityHelpers.list(entry.getValue())) {
                        if (section instanceof Map<?, ?> s) {
                            String id = templateRoot(s.get("templateId"));
                            if (id != null) {
                                out.add(id);
                            }
                        }
                    }
                }
                collectSectionIds(entry.getValue(), out);
            }
        } else if (node instanceof List<?> items) {
            items.forEach(item -> collectSectionIds(item, out));
        }
    }

    private ObjectNode csvDrift(JsonNode yaml, Map<String, HealthLakeCsvMapper.Table> tables,
                                HealthLakeCsvMapper.Result result) {
        Set<String> declared = new LinkedHashSet<>();
        Map<String, Set<String>> mapped = new LinkedHashMap<>();
        for (JsonNode table : yaml.path("tables")) {
            String name = table.path("tableName").asText();
            declared.add(name);
            Set<String> columns = mapped.computeIfAbsent(name, key -> new LinkedHashSet<>());
            columns.add(table.path("primaryKeyColumn").asText());
            for (JsonNode resource : table.path("resourceMappings")) {
                for (String kind : List.of("fieldMappings", "arrayFieldMappings")) {
                    resource.path(kind).forEach(field -> {
                        if (field.hasNonNull("sourceColumn")) {
                            columns.add(field.get("sourceColumn").asText());
                        }
                        field.path("sourceColumns").forEach(column -> columns.add(column.asText()));
                    });
                }
                for (JsonNode aggregation : resource.path("aggregationMappings")) {
                    Set<String> child = mapped.computeIfAbsent(aggregation.path("childTable").asText(),
                            key -> new LinkedHashSet<>());
                    child.add(aggregation.path("joinColumn").asText());
                    aggregation.path("childFieldMappings").forEach(field -> {
                        if (field.hasNonNull("sourceColumn")) {
                            child.add(field.get("sourceColumn").asText());
                        }
                    });
                }
            }
        }
        ObjectNode report = mapper.createObjectNode();
        report.put("sourceFormat", "CSV");
        ArrayNode unmappedTables = mapper.createArrayNode();
        ArrayNode unmappedColumns = mapper.createArrayNode();
        int totalColumns = 0;
        int mappedColumns = 0;
        int processed = 0;
        ObjectNode perTable = mapper.createObjectNode();
        for (Map.Entry<String, HealthLakeCsvMapper.Table> table : tables.entrySet()) {
            Set<String> columns = mapped.get(table.getKey());
            if (columns == null) {
                unmappedTables.add(table.getKey());
            } else if (declared.contains(table.getKey())) {
                processed++;
                perTable.put(table.getKey(), table.getValue().rows().size());
            }
            for (String column : table.getValue().header()) {
                totalColumns++;
                if (columns != null && columns.contains(column)) {
                    mappedColumns++;
                } else {
                    unmappedColumns.addObject().put("table", table.getKey()).put("column", column);
                }
            }
        }
        ObjectNode summary = report.putObject("summary");
        summary.put("totalTablesInProfile", declared.size()).put("totalTablesInInput", tables.size())
                .put("tablesProcessed", processed).put("tablesUnmapped", unmappedTables.size())
                .put("totalColumnsInInput", totalColumns).put("columnsMapped", mappedColumns)
                .put("columnsUnmapped", totalColumns - mappedColumns).put("totalRowsProcessed", result.rows())
                .put("totalResourcesGenerated", result.resources().size())
                .put("tablesCoverageRate", tables.isEmpty() ? 1.0 : (double) processed / tables.size())
                .put("columnsCoverageRate", totalColumns == 0 ? 1.0 : (double) mappedColumns / totalColumns);
        summary.set("perTableRowCounts", perTable);
        report.set("unmappedTables", unmappedTables);
        report.set("unmappedColumns", unmappedColumns);
        ArrayNode warnings = report.putArray("warnings");
        result.warnings().forEach(warnings::add);
        return report;
    }

    /** The synchronous conversion of one input: a C-CDA document, or CSV tables by name. */
    public synchronized ObjectNode transformData(JsonNode input, String region) {
        Published profile = published(region, text(input, "ProfileId"));
        JsonNode data = input.path("InputData");
        Map<String, String> files = new LinkedHashMap<>();
        if ("CCDA".equals(profile.format())) {
            files.put("input.xml", text(data, "CcdaInput"));
        } else {
            if (!data.path("CsvInput").isObject()) {
                throw validation("InputData.CsvInput maps table names to CSV content.");
            }
            data.get("CsvInput").fields().forEachRemaining(entry -> files.put(entry.getKey() + ".csv",
                    entry.getValue().asText()));
        }
        boolean drift = input.path("DriftDetectionEnabled").asBoolean(false);
        Conversion conversion;
        try {
            conversion = convert(profile, files, "sync|" + account() + "|" + region, input.path("ProvenanceEnabled")
                    .asBoolean(true), drift);
        } catch (IllegalArgumentException e) {
            throw validation(e.getMessage());
        }
        if (conversion.filesFailed() > 0) {
            throw validation(String.join("; ", conversion.warnings()));
        }
        ObjectNode bundle = mapper.createObjectNode();
        bundle.put("resourceType", "Bundle").put("type", "CCDA".equals(profile.format()) ? "collection"
                : "transaction");
        ArrayNode entries = bundle.putArray("entry");
        conversion.resources().forEach(resource -> {
            ObjectNode entry = entries.addObject();
            entry.set("resource", resource);
            if ("CSV".equals(profile.format())) {
                entry.putObject("request").put("method", "PUT").put("url", resource.path("resourceType").asText()
                        + "/" + resource.path("id").asText());
            }
        });
        ObjectNode response = mapper.createObjectNode();
        response.put("TransformedData", bundle.toString());
        if (drift) {
            response.put("DriftReport", conversion.drift().toString());
        }
        return response;
    }

    // ---------------------------------------------------------------- jobs

    private static String jobKey(String region, String id) {
        return region + "/transformation-job/" + id;
    }

    public synchronized ObjectNode startJob(JsonNode input, String region) {
        String account = account();
        String clientToken = text(input, "ClientToken");
        for (ObjectNode existing : storage.scanForAccount(account, key -> key.startsWith(region
                + "/transformation-job/"))) {
            if (clientToken.equals(existing.path("_clientToken").asText(null))) {
                return jobStarted(existing);
            }
        }
        Published profile = published(region, text(input, "ProfileId"));
        JsonNode inputConfig = input.path("InputDataConfig");
        String source = text(inputConfig, "S3Uri");
        if (!source.startsWith("s3://")) {
            throw validation("InputDataConfig.S3Uri must be an S3 URI.");
        }
        String format = inputConfig.path("SourceFormat").asText(profile.format());
        if (!profile.format().equals(format)) {
            throw validation("Profile " + profile.profileId() + " converts " + profile.format() + ", not " + format
                    + ".");
        }
        JsonNode output = input.path("OutputDataConfig").path("S3Configuration");
        if (!text(output, "S3Uri").startsWith("s3://")) {
            throw validation("OutputDataConfig.S3Configuration.S3Uri must be an S3 URI.");
        }
        text(output, "KmsKeyId");
        String role = text(input, "DataAccessRoleArn");
        if (!AwsArnUtils.isArnFor(role, "iam")) {
            throw validation("DataAccessRoleArn must be an IAM role ARN.");
        }
        String jobId = HexFormat.of().formatHex(bytes(16));
        ObjectNode job = mapper.createObjectNode();
        job.put("JobId", jobId).put("JobStatus", "SUBMITTED").put("SubmitTime", now())
                .put("DataAccessRoleArn", role).put("ProfileId", profile.profileId())
                .put("ProfileName", profile.profileName()).put("ProfileVersion", profile.version())
                .put("DriftDetectionEnabled", input.path("DriftDetectionEnabled").asBoolean(false))
                .put("ProvenanceEnabled", input.path("ProvenanceEnabled").asBoolean(true))
                .put("_clientToken", clientToken).put("_format", format);
        if (input.hasNonNull("JobName")) {
            job.put("JobName", input.get("JobName").asText());
        }
        ObjectNode inputCopy = inputConfig.deepCopy();
        inputCopy.put("SourceFormat", format);
        job.set("InputDataConfig", inputCopy);
        job.set("OutputDataConfig", input.get("OutputDataConfig").deepCopy());
        storage.putForAccount(account, jobKey(region, jobId), job);
        executor.execute(() -> runJob(account, region, jobId, profile));
        return jobStarted(job);
    }

    private ObjectNode jobStarted(ObjectNode job) {
        ObjectNode response = mapper.createObjectNode();
        response.put("JobId", job.path("JobId").asText()).put("JobStatus", job.path("JobStatus").asText());
        return response;
    }

    private void runJob(String account, String region, String jobId, Published profile) {
        String key = jobKey(region, jobId);
        ObjectNode job;
        synchronized (this) {
            job = storage.getForAccount(account, key).orElse(null);
            if (job == null) {
                return;
            }
            job.put("JobStatus", "IN_PROGRESS");
            storage.putForAccount(account, key, job);
        }
        String status;
        String message = null;
        ObjectNode report = mapper.createObjectNode();
        try {
            long delay = config.services().healthlake().transitionMillis();
            if (delay > 0) {
                Thread.sleep(delay);
            }
            Map<String, String> files = read(job.path("InputDataConfig").path("S3Uri").asText(),
                    job.path("_format").asText());
            Conversion conversion = convert(profile, files, "job|" + account + "|" + region,
                    job.path("ProvenanceEnabled").asBoolean(true), job.path("DriftDetectionEnabled").asBoolean());
            String output = job.path("OutputDataConfig").path("S3Configuration").path("S3Uri").asText();
            write(output, jobId, conversion);
            report.put("TotalFilesScanned", conversion.filesScanned()).put("TotalFilesConverted",
                    conversion.filesConverted()).put("TotalFilesFailed", conversion.filesFailed())
                    .put("TotalResourcesGenerated", conversion.resources().size());
            status = conversion.filesFailed() == 0 ? "COMPLETED" : conversion.filesConverted() == 0 ? "FAILED"
                    : "COMPLETED_WITH_ERRORS";
            if (!conversion.warnings().isEmpty()) {
                message = String.join("; ", conversion.warnings().subList(0, Math.min(5,
                        conversion.warnings().size())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (IllegalArgumentException | IllegalStateException | AwsException e) {
            status = "FAILED";
            message = e.getMessage();
        }
        synchronized (this) {
            ObjectNode current = storage.getForAccount(account, key).orElse(job);
            current.put("JobStatus", status).put("EndTime", now());
            if (message != null) {
                current.put("Message", message);
            }
            current.set("JobProgressReport", report);
            storage.putForAccount(account, key, current);
        }
    }

    /** The input files under an S3 URI: XML documents for C-CDA, CSV files for CSV, by key. */
    Map<String, String> read(String uri, String format) {
        String[] location = uri.substring("s3://".length()).split("/", 2);
        String prefix = location.length > 1 ? location[1] : "";
        List<S3Object> objects;
        try {
            objects = s3.get().listObjects(location[0], prefix, null, 1000);
        } catch (AwsException e) {
            throw new IllegalStateException("Unable to read " + uri + ": " + e.getMessage());
        }
        Map<String, String> files = new LinkedHashMap<>();
        for (S3Object object : objects) {
            String name = object.getKey().toLowerCase(Locale.ROOT);
            boolean wanted = "CSV".equals(format) ? name.endsWith(".csv") : name.endsWith(".xml");
            if (!wanted) {
                continue;
            }
            byte[] data = s3.get().getObject(location[0], object.getKey()).getData();
            files.put(object.getKey(), data == null ? "" : new String(data, StandardCharsets.UTF_8));
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("No " + ("CSV".equals(format) ? "CSV" : "C-CDA XML") + " files were found "
                    + "at " + uri + ".");
        }
        return files;
    }

    /** Writes a conversion to S3: an NDJSON file per resource type, and the drift report when there is one. */
    private void write(String output, String jobId, Conversion conversion) {
        String[] location = output.substring("s3://".length()).split("/", 2);
        String prefix = location.length > 1 && !location[1].isEmpty() ? (location[1].endsWith("/") ? location[1]
                : location[1] + "/") : "";
        Map<String, StringBuilder> byType = new LinkedHashMap<>();
        conversion.resources().forEach(resource -> byType.computeIfAbsent(resource.path("resourceType").asText(),
                type -> new StringBuilder()).append(resource.toString()).append('\n'));
        try {
            for (Map.Entry<String, StringBuilder> entry : byType.entrySet()) {
                s3.get().putObject(location[0], prefix + jobId + "/" + entry.getKey() + ".ndjson",
                        entry.getValue().toString().getBytes(StandardCharsets.UTF_8), "application/x-ndjson",
                        Map.of());
            }
            if (conversion.drift() != null) {
                s3.get().putObject(location[0], prefix + jobId + "/drift-report.json",
                        conversion.drift().toString().getBytes(StandardCharsets.UTF_8), "application/json", Map.of());
            }
        } catch (AwsException e) {
            throw new IllegalStateException("Unable to write to " + output + ": " + e.getMessage());
        }
    }

    public synchronized ObjectNode describeJob(JsonNode input, String region) {
        String jobId = text(input, "JobId");
        ObjectNode job = storage.getForAccount(account(), jobKey(region, jobId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Job " + jobId + " not found.", 400));
        ObjectNode response = mapper.createObjectNode();
        response.set("TransformationJobProperties", view(job));
        return response;
    }

    public synchronized ObjectNode listJobs(JsonNode input, String region) {
        List<ObjectNode> jobs = new ArrayList<>(storage.scanForAccount(account(), key -> key.startsWith(region
                + "/transformation-job/")));
        jobs.sort((a, b) -> Double.compare(b.path("SubmitTime").asDouble(), a.path("SubmitTime").asDouble()));
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode job : jobs) {
            if (input.hasNonNull("JobStatus") && !input.get("JobStatus").asText().equals(job.path("JobStatus")
                    .asText())) {
                continue;
            }
            if (input.hasNonNull("JobName") && !input.get("JobName").asText().equals(job.path("JobName").asText())) {
                continue;
            }
            double submitted = job.path("SubmitTime").asDouble();
            if ((input.hasNonNull("SubmittedBefore") && submitted >= input.get("SubmittedBefore").asDouble())
                    || (input.hasNonNull("SubmittedAfter") && submitted <= input.get("SubmittedAfter").asDouble())) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            for (String field : List.of("JobId", "JobStatus", "SubmitTime", "JobName", "EndTime")) {
                if (job.has(field)) {
                    summary.set(field, job.get(field));
                }
            }
            summary.put("SourceFormat", job.path("_format").asText());
            items.add(summary);
        }
        return page(items, input, "Items");
    }

    // ---------------------------------------------------------------- the agent

    /** An agent turn: the events it streams, in order. */
    record AgentTurn(List<Map.Entry<String, ObjectNode>> events) {
    }

    public synchronized AgentTurn updateProfileWithAgent(JsonNode input, String region) {
        ObjectNode profile = profile(region, text(input, "ProfileId"));
        String body = text(input.path("InputMessage"), "Body");
        String type = input.path("InputMessage").path("Type").asText("normal");
        if (!Set.of("normal", "confirmation_response").contains(type)) {
            throw validation("InputMessage.Type must be normal or confirmation_response.");
        }
        String conversation = input.path("ConversationId").asText(null);
        if (conversation == null) {
            conversation = "conv-" + UUID.randomUUID();
        }
        List<Map.Entry<String, ObjectNode>> events = new ArrayList<>();
        String lower = body.toLowerCase(Locale.ROOT);
        ObjectNode draft = (ObjectNode) profile.path("_versions").get("0");
        boolean csv = "CSV".equals(profile.path("SourceFormat").asText());
        String reply;
        List<String> options;
        if (lower.contains("publish")) {
            events.add(event("ProgressEvent", "Publishing the draft."));
            ObjectNode request = mapper.createObjectNode();
            request.put("ProfileId", profile.path("ProfileId").asText())
                    .put("SourceFormat", profile.path("SourceFormat").asText())
                    .put("ChangeDescription", "Published by the agent");
            int version = publishProfile(request, region).path("Version").asInt();
            reply = "I published the draft as version " + version + ".";
            options = List.of("Show the mapping");
        } else if (lower.contains("show") || lower.contains("view")) {
            reply = "The draft's mapping:\n" + mappingText(draft.path("mapping"));
            options = List.of("Publish the profile");
        } else if (csv && (draft.path("mapping").isEmpty() || lower.contains("generate"))) {
            String sample = profile.path("_sample").asText(null);
            if (sample == null) {
                reply = "This profile has no sample data to analyze. Create it from SampleData, or give its mapping "
                        + "with UpdateDataTransformationProfile.";
                options = List.of();
            } else {
                events.add(event("ProgressEvent", "Reading the sample data at " + sample + "."));
                Map<String, String> files = read(sample, "CSV");
                events.add(event("ProgressEvent", "Analyzing " + files.size() + " CSV file(s)."));
                Draft drafted = HealthLakeCsvAgent.draft(files);
                ObjectNode mapping = mapper.createObjectNode();
                mapping.put(CSV_MAPPING_FILE, drafted.yaml());
                draft.set("mapping", mapping);
                draft.put("LastUpdatedAt", now()).put("ChangeDescription", "Drafted by the agent from sample data");
                storage.putForAccount(account(), profileKey(region, profile.path("ProfileId").asText()), profile);
                events.add(event("TextMessageEvent", drafted.summary()));
                reply = "I drafted a mapping for " + String.join(", ", drafted.tables()) + ". Review it, then "
                        + "publish it.";
                options = List.of("Show the mapping", "Publish the profile");
            }
        } else if (!csv) {
            Map<String, String> templates = new LinkedHashMap<>();
            draft.path("mapping").fields().forEachRemaining(entry -> templates.put(entry.getKey(),
                    entry.getValue().asText()));
            String all = String.join("\n", templates.values());
            List<String> covered = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            CCDA_SECTIONS.forEach((oid, name) -> (all.contains(oid) ? covered : missing).add(name));
            events.add(event("ProgressEvent", "Reviewing the profile's C-CDA templates."));
            reply = "This profile's templates cover " + (covered.isEmpty() ? "no sections" : String.join(", ",
                    covered)) + ". floci's agent does not write templates; add one under Resources/ with "
                    + "UpdateDataTransformationProfile for a section to map.";
            options = missing;
        } else {
            reply = "I can show the mapping, publish the draft, or draft a mapping again from the sample data.";
            options = List.of("Show the mapping", "Publish the profile", "Generate the mapping");
        }
        ObjectNode end = mapper.createObjectNode();
        end.put("FinalResponseMessage", reply).put("ConversationId", conversation);
        ArrayNode list = end.putArray("Options");
        options.forEach(list::add);
        events.add(Map.entry("EndOfOutputEvent", end));
        return new AgentTurn(events);
    }

    /** A drafted CSV mapping: its YAML, the tables it maps, and what the agent says about it. */
    record Draft(String yaml, List<String> tables, String summary) {
    }

    private Map.Entry<String, ObjectNode> event(String type, String message) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("Message", message);
        return Map.entry(type, payload);
    }

    private String mappingText(JsonNode mapping) {
        StringBuilder out = new StringBuilder();
        mapping.fields().forEachRemaining(entry -> out.append("--- ").append(entry.getKey()).append('\n')
                .append(entry.getValue().asText()).append('\n'));
        return out.toString();
    }

    // ---------------------------------------------------------------- shared

    private ObjectNode view(ObjectNode record) {
        ObjectNode view = record.deepCopy();
        List<String> hidden = new ArrayList<>();
        view.fieldNames().forEachRemaining(field -> {
            if (field.startsWith("_")) {
                hidden.add(field);
            }
        });
        view.remove(hidden);
        return view;
    }

    private ObjectNode page(List<ObjectNode> items, JsonNode input, String field) {
        int limit = input.path("MaxResults").asInt(100);
        if (limit < 1 || limit > 500) {
            throw validation("MaxResults is 1 to 500.");
        }
        int start = 0;
        String token = input.path("NextToken").asText(null);
        if (token != null && !token.isEmpty()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("NextToken is not valid.");
            }
        }
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray(field);
        int end = Math.min(items.size(), start + limit);
        for (int i = start; i < end; i++) {
            list.add(items.get(i));
        }
        if (end < items.size()) {
            response.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Integer.toString(end).getBytes(StandardCharsets.UTF_8)));
        }
        return response;
    }

    private static String text(JsonNode input, String field) {
        JsonNode value = input == null ? null : input.get(field);
        if (value == null || value.isNull() || value.asText().isEmpty()) {
            throw validation(field + " is required.");
        }
        return value.asText();
    }

    private static byte[] bytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static String yaml(Object value) {
        try {
            return YAML.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
