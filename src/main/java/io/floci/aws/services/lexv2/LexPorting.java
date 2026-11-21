package io.floci.aws.services.lexv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Exports and imports. A bot or locale exports as the Lex JSON archive ({@code Manifest.json}, {@code Bot.json}, and
 * {@code BotLocale.json}, {@code Intent.json}, {@code Slot.json} and {@code SlotType.json} under each locale, which name
 * one another rather than use identifiers); a custom vocabulary as TSV; a test set as CSV. An import reads an archive
 * uploaded to the URL {@code CreateUploadUrl} gave into a new bot, a locale of an existing bot, or a custom vocabulary,
 * under the merge strategy; a test set imports from its CSV in S3. Archive passwords are accepted and not applied.
 */
@ApplicationScoped
public class LexPorting {

    private final LexStore store;
    private final LexBots bots;
    private final LexTesting testing;
    private final ObjectMapper mapper;

    @Inject
    public LexPorting(LexStore store, LexBots bots, LexTesting testing) {
        this.store = store;
        this.bots = bots;
        this.testing = testing;
        this.mapper = store.mapper();
    }

    // ================================================================ uploads

    public ObjectNode createUploadUrl(ObjectNode in, String region) {
        String importId = LexStore.id();
        ObjectNode out = mapper.createObjectNode();
        out.put("importId", importId);
        out.put("uploadUrl", store.reserveUpload(importId));
        return out;
    }

    // ================================================================ exports

    public ObjectNode createExport(ObjectNode in, String region) {
        JsonNode spec = in.path("resourceSpecification");
        String format = in.path("fileFormat").asText();
        String type = resourceType(spec);
        byte[] archive;
        String contentType;
        switch (type) {
            case "Bot" -> {
                requireFormat(format, "LexJson", type);
                JsonNode s = spec.path("botExportSpecification");
                archive = exportBot(region, s.path("botId").asText(), s.path("botVersion").asText(), null);
                contentType = "application/zip";
            }
            case "BotLocale" -> {
                requireFormat(format, "LexJson", type);
                JsonNode s = spec.path("botLocaleExportSpecification");
                archive = exportBot(region, s.path("botId").asText(), s.path("botVersion").asText(),
                        s.path("localeId").asText());
                contentType = "application/zip";
            }
            case "CustomVocabulary" -> {
                requireFormat(format, "TSV", type);
                JsonNode s = spec.path("customVocabularyExportSpecification");
                archive = exportVocabulary(region, s);
                contentType = "application/zip";
            }
            case "TestSet" -> {
                requireFormat(format, "CSV", type);
                String testSetId = spec.path("testSetExportSpecification").path("testSetId").asText();
                archive = zip(Map.of("TestSet.csv", testing.csv(region, testSetId)
                        .getBytes(StandardCharsets.UTF_8)));
                contentType = "application/zip";
            }
            default -> throw LexStore.validation("The resource specification names nothing to export.");
        }
        for (ObjectNode other : store.liveList(region, LexStore.EXPORT, "")) {
            if (other.path("resourceSpecification").equals(spec)
                    && "InProgress".equals(other.path("exportStatus").asText())) {
                throw LexStore.conflict("An export of this resource is already in progress.");
            }
        }
        String exportId = LexStore.id();
        ObjectNode export = store.record();
        export.put("exportId", exportId);
        export.set("resourceSpecification", spec.deepCopy());
        export.put("fileFormat", format);
        double now = LexStore.epoch();
        export.put("creationDateTime", now);
        export.put("lastUpdatedDateTime", now);
        store.settle(export, "exportStatus", "InProgress", "Completed");
        LexStore.meta(export).put("resourceType", type);
        LexStore.meta(export).put("url", store.putFile(archive, contentType, exportId + ".zip"));
        if (in.hasNonNull("filePassword")) {
            LexStore.meta(export).put("password", true);
        }
        store.put(region, LexStore.EXPORT, exportId, export);
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(export, out, "exportId", "resourceSpecification", "fileFormat", "exportStatus",
                "creationDateTime");
        return out;
    }

    private static void requireFormat(String format, String expected, String type) {
        if (!expected.equals(format)) {
            throw LexStore.validation("A " + type + " exports in the " + expected + " format.");
        }
    }

    private static String resourceType(JsonNode spec) {
        if (spec.has("botExportSpecification") || spec.has("botImportSpecification")) {
            return "Bot";
        }
        if (spec.has("botLocaleExportSpecification") || spec.has("botLocaleImportSpecification")) {
            return "BotLocale";
        }
        if (spec.has("customVocabularyExportSpecification") || spec.has("customVocabularyImportSpecification")) {
            return "CustomVocabulary";
        }
        if (spec.has("testSetExportSpecification") || spec.has("testSetImportResourceSpecification")) {
            return "TestSet";
        }
        return "";
    }

    public ObjectNode describeExport(ObjectNode in, String region) {
        ObjectNode export = export(region, in.path("exportId").asText());
        ObjectNode out = LexStore.view(export);
        out.putArray("failureReasons");
        if ("Completed".equals(export.path("exportStatus").asText())) {
            out.put("downloadUrl", export.path("_").path("url").asText());
        }
        return out;
    }

    private ObjectNode export(String region, String exportId) {
        return store.live(region, LexStore.EXPORT, exportId)
                .orElseThrow(() -> LexStore.notFound("The export " + exportId + " does not exist."));
    }

    public ObjectNode updateExport(ObjectNode in, String region) {
        ObjectNode export = export(region, in.path("exportId").asText());
        if ("InProgress".equals(export.path("exportStatus").asText())) {
            throw LexStore.conflict("The export is still in progress.");
        }
        LexStore.meta(export).put("password", in.hasNonNull("filePassword"));
        export.put("lastUpdatedDateTime", LexStore.epoch());
        store.put(region, LexStore.EXPORT, export.path("exportId").asText(), export);
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(export, out, "exportId", "resourceSpecification", "fileFormat", "exportStatus",
                "creationDateTime", "lastUpdatedDateTime");
        return out;
    }

    public ObjectNode deleteExport(ObjectNode in, String region) {
        String exportId = in.path("exportId").asText();
        ObjectNode export = store.live(region, LexStore.EXPORT, exportId)
                .orElseThrow(() -> LexStore.precondition("The export " + exportId + " does not exist."));
        store.markDeleted(export, "exportStatus", "Deleting");
        store.put(region, LexStore.EXPORT, exportId, export);
        ObjectNode out = mapper.createObjectNode();
        out.put("exportId", exportId);
        out.put("exportStatus", "Deleting");
        return out;
    }

    public ObjectNode listExports(ObjectNode in, String region) {
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode export : store.liveList(region, LexStore.EXPORT, "")) {
            JsonNode spec = export.path("resourceSpecification");
            JsonNode target = spec.elements().hasNext() ? spec.elements().next() : mapper.createObjectNode();
            if (!matches(in, target)) {
                continue;
            }
            if (!LexStore.filtered(in.path("filters"), Map.of("ExportResourceType",
                    export.path("_").path("resourceType").asText()))) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            LexBots.copy(export, summary, "exportId", "resourceSpecification", "fileFormat", "exportStatus",
                    "creationDateTime", "lastUpdatedDateTime");
            summaries.add(summary);
        }
        LexStore.sort(summaries, in.path("sortBy"), Map.of("LastUpdatedDateTime", "lastUpdatedDateTime"),
                "lastUpdatedDateTime");
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(in, out, "botId", "botVersion", "localeId");
        LexStore.page(summaries, in, out, "exportSummaries", 10);
        return out;
    }

    private static boolean matches(JsonNode in, JsonNode target) {
        for (String field : List.of("botId", "botVersion", "localeId")) {
            if (in.hasNonNull(field) && !in.get(field).asText().equals(target.path(field).asText())) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- the Lex JSON archive

    private byte[] exportBot(String region, String botId, String version, String onlyLocale) {
        ObjectNode bot = bots.bot(region, botId);
        Map<String, byte[]> files = new LinkedHashMap<>();
        String root = bot.path("botName").asText() + "/";
        ObjectNode manifest = mapper.createObjectNode();
        manifest.putObject("metadata").put("schemaVersion", "1").put("fileFormat", "LexJson")
                .put("resourceType", onlyLocale == null ? "Bot" : "BotLocale");
        files.put("Manifest.json", json(manifest));
        if (onlyLocale == null) {
            ObjectNode doc = mapper.createObjectNode();
            doc.put("name", bot.path("botName").asText());
            doc.put("version", version);
            doc.put("identifier", botId);
            LexBots.copy(bot, doc, "description", "dataPrivacy", "idleSessionTTLInSeconds", "botType", "botMembers",
                    "errorLogSettings");
            files.put(root + "Bot.json", json(doc));
        }
        List<ObjectNode> locales = store.liveList(region, LexStore.LOCALE, botId + "/" + version + "/");
        boolean found = false;
        for (ObjectNode locale : locales) {
            String localeId = locale.path("localeId").asText();
            if (onlyLocale != null && !onlyLocale.equals(localeId)) {
                continue;
            }
            found = true;
            String base = root + "BotLocales/" + localeId + "/";
            ObjectNode localeDoc = mapper.createObjectNode();
            localeDoc.put("name", locale.path("localeName").asText());
            localeDoc.put("identifier", localeId);
            localeDoc.put("version", version);
            LexBots.copy(locale, localeDoc, "description", "voiceSettings", "unifiedSpeechSettings",
                    "audioFillerSettings", "speechRecognitionSettings", "generativeAISettings",
                    "speechDetectionSensitivity", "speakerDiarizationSettings");
            localeDoc.set("nluConfidenceThreshold", locale.path("nluIntentConfidenceThreshold").deepCopy());
            files.put(base + "BotLocale.json", json(localeDoc));
            String path = botId + "/" + version + "/" + localeId;
            Map<String, String> typeNames = new HashMap<>();
            List<ObjectNode> types = store.liveList(region, LexStore.SLOT_TYPE, path + "/");
            types.forEach(t -> typeNames.put(t.path("slotTypeId").asText(), t.path("slotTypeName").asText()));
            for (ObjectNode type : types) {
                ObjectNode doc = named(type, "slotTypeName", "slotTypeId");
                for (JsonNode sub : doc.path("compositeSlotTypeSetting").path("subSlots")) {
                    rename((ObjectNode) sub, typeNames);
                }
                files.put(base + "SlotTypes/" + type.path("slotTypeName").asText() + "/SlotType.json", json(doc));
            }
            for (ObjectNode intent : store.liveList(region, LexStore.INTENT, path + "/")) {
                String intentId = intent.path("intentId").asText();
                String intentBase = base + "Intents/" + intent.path("intentName").asText() + "/";
                Map<String, String> slotNames = new HashMap<>();
                List<ObjectNode> slots = store.liveList(region, LexStore.SLOT, path + "/" + intentId + "/");
                slots.forEach(s -> slotNames.put(s.path("slotId").asText(), s.path("slotName").asText()));
                ObjectNode doc = named(intent, "intentName", "intentId");
                if (doc.get("slotPriorities") instanceof ArrayNode priorities) {
                    for (JsonNode priority : priorities) {
                        ObjectNode p = (ObjectNode) priority;
                        p.put("slotName", slotNames.getOrDefault(p.path("slotId").asText(), p.path("slotId").asText()));
                        p.remove("slotId");
                    }
                }
                files.put(intentBase + "Intent.json", json(doc));
                for (ObjectNode slot : slots) {
                    ObjectNode slotDoc = named(slot, "slotName", "slotId");
                    slotDoc.remove("intentId");
                    rename(slotDoc, typeNames);
                    slotDoc.path("subSlotSetting").path("slotSpecifications").forEach(spec ->
                            rename((ObjectNode) spec, typeNames));
                    files.put(intentBase + "Slots/" + slot.path("slotName").asText() + "/Slot.json", json(slotDoc));
                }
            }
            store.live(region, LexStore.VOCABULARY, path).ifPresent(vocabulary -> {
                ObjectNode doc = mapper.createObjectNode();
                ArrayNode items = doc.putArray("customVocabularyItems");
                vocabulary.path("items").forEach(item -> {
                    ObjectNode copy = item.deepCopy();
                    copy.remove("itemId");
                    items.add(copy);
                });
                files.put(base + "CustomVocabulary/CustomVocabulary.json", json(doc));
            });
        }
        if (onlyLocale != null && !found) {
            throw LexStore.notFound("The version " + version + " of bot " + botId + " has no locale " + onlyLocale
                    + ".");
        }
        return zip(files);
    }

    private byte[] exportVocabulary(String region, JsonNode spec) {
        String path = spec.path("botId").asText() + "/" + spec.path("botVersion").asText() + "/"
                + spec.path("localeId").asText();
        ObjectNode vocabulary = store.live(region, LexStore.VOCABULARY, path)
                .orElseThrow(() -> LexStore.notFound("The locale has no custom vocabulary."));
        StringBuilder tsv = new StringBuilder("phrase\tweight\tdisplayAs\n");
        for (JsonNode item : vocabulary.path("items")) {
            tsv.append(item.path("phrase").asText()).append('\t')
                    .append(item.hasNonNull("weight") ? item.get("weight").asText() : "").append('\t')
                    .append(item.path("displayAs").asText("")).append('\n');
        }
        return zip(Map.of("CustomVocabulary.tsv", tsv.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static ObjectNode named(ObjectNode record, String nameField, String idField) {
        ObjectNode doc = LexStore.view(record);
        doc.put("name", record.path(nameField).asText());
        doc.put("identifier", record.path(idField).asText());
        doc.remove(List.of(nameField, idField, "botId", "botVersion", "localeId", "creationDateTime",
                "lastUpdatedDateTime"));
        return doc;
    }

    private static void rename(ObjectNode node, Map<String, String> typeNames) {
        if (node.hasNonNull("slotTypeId")) {
            String id = node.get("slotTypeId").asText();
            node.put("slotTypeName", typeNames.getOrDefault(id, id));
            node.remove("slotTypeId");
        }
    }

    private byte[] json(JsonNode node) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] zip(Map<String, byte[]> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    static Map<String, byte[]> unzip(byte[] archive) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    files.put(entry.getName(), zip.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw LexStore.validation("The uploaded file is not a valid zip archive.");
        }
        if (files.isEmpty()) {
            throw LexStore.validation("The uploaded file is not a valid zip archive.");
        }
        return files;
    }

    // ================================================================ imports

    public ObjectNode startImport(ObjectNode in, String region) {
        String importId = in.path("importId").asText();
        JsonNode spec = in.path("resourceSpecification");
        String type = resourceType(spec);
        String strategy = in.path("mergeStrategy").asText();
        if (store.live(region, LexStore.IMPORT, importId).isPresent()) {
            throw LexStore.conflict("The import " + importId + " has already started.");
        }
        ObjectNode record = store.record();
        record.put("importId", importId);
        record.set("resourceSpecification", spec.deepCopy());
        record.put("mergeStrategy", strategy);
        double now = LexStore.epoch();
        record.put("creationDateTime", now);
        record.put("lastUpdatedDateTime", now);
        LexStore.meta(record).put("resourceType", type);
        List<String> failures = new ArrayList<>();
        try {
            switch (type) {
                case "Bot" -> importBot(region, record, spec.path("botImportSpecification"), strategy,
                        archive(importId));
                case "BotLocale" -> importLocale(region, record, spec.path("botLocaleImportSpecification"), strategy,
                        archive(importId));
                case "CustomVocabulary" -> importVocabulary(region, record,
                        spec.path("customVocabularyImportSpecification"), strategy, archive(importId));
                case "TestSet" -> {
                    String testSetId = testing.importTestSet(region, spec.path("testSetImportResourceSpecification"));
                    record.put("importedResourceId", testSetId);
                    record.put("importedResourceName", spec.path("testSetImportResourceSpecification")
                            .path("testSetName").asText());
                }
                default -> throw LexStore.validation("The resource specification names nothing to import.");
            }
        } catch (AwsException e) {
            if (!"ImportFailure".equals(e.getErrorCode())) {
                throw e;
            }
            failures.add(e.getMessage());
        }
        LexStore.meta(record).set("failureReasons", mapper.valueToTree(failures));
        store.settle(record, "importStatus", "InProgress", failures.isEmpty() ? "Completed" : "Failed");
        store.put(region, LexStore.IMPORT, importId, record);
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(record, out, "importId", "resourceSpecification", "mergeStrategy", "importStatus",
                "creationDateTime");
        return out;
    }

    private Map<String, byte[]> archive(String importId) {
        byte[] bytes = store.uploaded(importId)
                .orElseThrow(() -> LexStore.notFound("Nothing was uploaded for the import " + importId + "."));
        return unzip(bytes);
    }

    private static AwsException failure(String message) {
        return new AwsException("ImportFailure", message, 400);
    }

    private JsonNode read(Map<String, byte[]> files, String name) {
        try {
            return mapper.readTree(files.get(name));
        } catch (IOException e) {
            throw failure("The file " + name + " is not valid JSON.");
        }
    }

    private void importBot(String region, ObjectNode record, JsonNode spec, String strategy,
                           Map<String, byte[]> files) {
        String botJson = files.keySet().stream().filter(name -> name.endsWith("/Bot.json") || "Bot.json".equals(name))
                .findFirst().orElseThrow(() -> failure("The archive has no Bot.json."));
        String root = botJson.substring(0, botJson.length() - "Bot.json".length());
        JsonNode doc = read(files, botJson);
        String name = spec.path("botName").asText();
        ObjectNode existing = null;
        for (ObjectNode bot : store.liveList(region, LexStore.BOT, "")) {
            if (name.equals(bot.path("botName").asText()) && !LexStore.meta(bot).has("deleteAt")) {
                existing = bot;
            }
        }
        String botId;
        if (existing != null) {
            if ("FailOnConflict".equals(strategy)) {
                throw failure("A bot with the name " + name + " already exists.");
            }
            botId = existing.path("botId").asText();
            existing.put("roleArn", spec.path("roleArn").asText());
            existing.set("dataPrivacy", spec.path("dataPrivacy").deepCopy());
            if (spec.hasNonNull("idleSessionTTLInSeconds")) {
                existing.put("idleSessionTTLInSeconds", spec.get("idleSessionTTLInSeconds").asInt());
            }
            existing.put("lastUpdatedDateTime", LexStore.epoch());
            store.settle(existing, "botStatus", "Importing", "Available");
            store.put(region, LexStore.BOT, botId, existing);
        } else {
            ObjectNode create = mapper.createObjectNode();
            create.put("botName", name);
            if (doc.hasNonNull("description")) {
                create.put("description", doc.get("description").asText());
            }
            create.put("roleArn", spec.path("roleArn").asText());
            create.set("dataPrivacy", spec.path("dataPrivacy").deepCopy());
            create.put("idleSessionTTLInSeconds", spec.path("idleSessionTTLInSeconds")
                    .asInt(doc.path("idleSessionTTLInSeconds").asInt(300)));
            if (doc.hasNonNull("botType")) {
                create.put("botType", doc.get("botType").asText());
            }
            if (doc.has("botMembers")) {
                create.set("botMembers", doc.get("botMembers").deepCopy());
            }
            LexBots.copy(spec, create, "botTags", "testBotAliasTags", "errorLogSettings");
            botId = bots.createBot(create, region).path("botId").asText();
            ObjectNode bot = store.live(region, LexStore.BOT, botId).orElseThrow();
            store.settle(bot, "botStatus", "Importing", "Available");
            store.put(region, LexStore.BOT, botId, bot);
        }
        for (String file : files.keySet()) {
            if (file.startsWith(root + "BotLocales/") && file.endsWith("/BotLocale.json")) {
                String localeRoot = file.substring(0, file.length() - "BotLocale.json".length());
                writeLocale(region, botId, read(files, file), localeRoot, files, strategy, null);
            }
        }
        record.put("importedResourceId", botId);
        record.put("importedResourceName", name);
    }

    private void importLocale(String region, ObjectNode record, JsonNode spec, String strategy,
                              Map<String, byte[]> files) {
        String botId = spec.path("botId").asText();
        bots.existingBot(region, botId);
        if (!LexStore.DRAFT.equals(spec.path("botVersion").asText())) {
            throw LexStore.validation("A locale imports into the DRAFT version.");
        }
        String localeId = spec.path("localeId").asText();
        LexCatalog.checkLocale(localeId);
        String file = files.keySet().stream().filter(name -> name.endsWith("/BotLocale.json")
                        || "BotLocale.json".equals(name))
                .filter(name -> name.contains("/" + localeId + "/") || "BotLocale.json".equals(name))
                .findFirst()
                .or(() -> files.keySet().stream().filter(name -> name.endsWith("BotLocale.json")).findFirst())
                .orElseThrow(() -> failure("The archive has no BotLocale.json."));
        String localeRoot = file.substring(0, file.length() - "BotLocale.json".length());
        String path = botId + "/" + LexStore.DRAFT + "/" + localeId;
        if ("FailOnConflict".equals(strategy) && store.live(region, LexStore.LOCALE, path).isPresent()) {
            throw failure("The bot " + botId + " already has the locale " + localeId + ".");
        }
        writeLocale(region, botId, read(files, file), localeRoot, files, strategy, spec);
        record.put("importedResourceId", botId);
        record.put("importedResourceName", localeId);
    }

    /** Writes an archive's locale into a bot's DRAFT: replacing it, or with Append adding only what's missing. */
    private void writeLocale(String region, String botId, JsonNode localeDoc, String localeRoot,
                             Map<String, byte[]> files, String strategy, JsonNode overrides) {
        String localeId = overrides != null ? overrides.path("localeId").asText()
                : localeDoc.path("identifier").asText();
        LexCatalog.checkLocale(localeId);
        String path = botId + "/" + LexStore.DRAFT + "/" + localeId;
        boolean append = "Append".equals(strategy);
        Optional<ObjectNode> existing = store.live(region, LexStore.LOCALE, path);
        if (existing.isPresent() && !append) {
            for (String kind : List.of(LexStore.INTENT, LexStore.SLOT, LexStore.SLOT_TYPE, LexStore.VOCABULARY)) {
                store.deleteAll(region, kind, path + "/");
                store.deleteAll(region, kind, path);
            }
        }
        double now = LexStore.epoch();
        ObjectNode locale = existing.filter(l -> append).orElseGet(store::record);
        if (existing.isEmpty() || !append) {
            locale.put("botId", botId);
            locale.put("botVersion", LexStore.DRAFT);
            locale.put("localeId", localeId);
            locale.put("localeName", LexCatalog.LOCALES.get(localeId));
            LexBots.copy(localeDoc, locale, "description", "voiceSettings", "unifiedSpeechSettings",
                    "audioFillerSettings", "speechRecognitionSettings", "generativeAISettings",
                    "speechDetectionSensitivity", "speakerDiarizationSettings");
            locale.put("nluIntentConfidenceThreshold", localeDoc.path("nluConfidenceThreshold").asDouble(0.4));
            locale.put("creationDateTime", existing.map(l -> l.path("creationDateTime").asDouble()).orElse(now));
        }
        if (overrides != null) {
            LexBots.copy(overrides, locale, "nluIntentConfidenceThreshold", "voiceSettings",
                    "speechRecognitionSettings", "speechDetectionSensitivity", "unifiedSpeechSettings",
                    "audioFillerSettings", "speakerDiarizationSettings");
        }
        locale.put("lastUpdatedDateTime", now);
        locale.put("botLocaleStatus", "NotBuilt");
        LexStore.meta(locale).remove("model");
        store.put(region, LexStore.LOCALE, path, locale);

        Map<String, String> typeIds = new HashMap<>();
        for (ObjectNode type : store.liveList(region, LexStore.SLOT_TYPE, path + "/")) {
            typeIds.put(type.path("slotTypeName").asText(), type.path("slotTypeId").asText());
        }
        List<String> typeFiles = new ArrayList<>();
        for (String file : files.keySet()) {
            if (file.startsWith(localeRoot + "SlotTypes/") && file.endsWith("/SlotType.json")) {
                typeFiles.add(file);
            }
        }
        for (String file : typeFiles) {
            JsonNode doc = read(files, file);
            String name = doc.path("name").asText();
            if (typeIds.containsKey(name)) {
                continue;
            }
            typeIds.put(name, LexStore.id());
        }
        for (String file : typeFiles) {
            JsonNode doc = read(files, file);
            String name = doc.path("name").asText();
            String id = typeIds.get(name);
            if (store.live(region, LexStore.SLOT_TYPE, path + "/" + id).isPresent()) {
                continue;
            }
            ObjectNode type = entity(doc, "slotTypeId", id, "slotTypeName", botId, localeId, now);
            for (JsonNode sub : type.path("compositeSlotTypeSetting").path("subSlots")) {
                unname((ObjectNode) sub, typeIds);
            }
            store.put(region, LexStore.SLOT_TYPE, path + "/" + id, type);
        }
        Map<String, String> intentIds = new HashMap<>();
        for (ObjectNode intent : store.liveList(region, LexStore.INTENT, path + "/")) {
            intentIds.put(intent.path("intentName").asText(), intent.path("intentId").asText());
        }
        boolean fallback = intentIds.containsValue(LexStore.FALLBACK_ID);
        for (String file : files.keySet()) {
            if (!file.startsWith(localeRoot + "Intents/") || !file.endsWith("/Intent.json")) {
                continue;
            }
            JsonNode doc = read(files, file);
            String name = doc.path("name").asText();
            if (intentIds.containsKey(name)) {
                continue;
            }
            boolean isFallback = LexStore.FALLBACK_SIGNATURE.equals(doc.path("parentIntentSignature").asText());
            if (isFallback && fallback) {
                continue;
            }
            String intentId = isFallback ? LexStore.FALLBACK_ID : LexStore.id();
            fallback |= isFallback;
            ObjectNode intent = entity(doc, "intentId", intentId, "intentName", botId, localeId, now);
            String intentRoot = file.substring(0, file.length() - "Intent.json".length());
            Map<String, String> slotIds = new HashMap<>();
            for (String slotFile : files.keySet()) {
                if (!slotFile.startsWith(intentRoot + "Slots/") || !slotFile.endsWith("/Slot.json")) {
                    continue;
                }
                JsonNode slotDoc = read(files, slotFile);
                String slotId = LexStore.id();
                slotIds.put(slotDoc.path("name").asText(), slotId);
                ObjectNode slot = entity(slotDoc, "slotId", slotId, "slotName", botId, localeId, now);
                slot.put("intentId", intentId);
                unname(slot, typeIds);
                slot.path("subSlotSetting").path("slotSpecifications").forEach(spec ->
                        unname((ObjectNode) spec, typeIds));
                store.put(region, LexStore.SLOT, path + "/" + intentId + "/" + slotId, slot);
            }
            if (intent.get("slotPriorities") instanceof ArrayNode priorities) {
                for (JsonNode priority : priorities) {
                    ObjectNode p = (ObjectNode) priority;
                    p.put("slotId", slotIds.getOrDefault(p.path("slotName").asText(), p.path("slotName").asText()));
                    p.remove("slotName");
                }
            }
            store.put(region, LexStore.INTENT, path + "/" + intentId, intent);
            intentIds.put(name, intentId);
        }
        if (!fallback) {
            ObjectNode intent = store.record();
            intent.put("intentId", LexStore.FALLBACK_ID);
            intent.put("intentName", LexStore.FALLBACK);
            intent.put("description", "Default intent when no other intent matches");
            intent.put("parentIntentSignature", LexStore.FALLBACK_SIGNATURE);
            intent.put("botId", botId);
            intent.put("botVersion", LexStore.DRAFT);
            intent.put("localeId", localeId);
            intent.put("creationDateTime", now);
            intent.put("lastUpdatedDateTime", now);
            store.put(region, LexStore.INTENT, path + "/" + LexStore.FALLBACK_ID, intent);
        }
        String vocabularyFile = localeRoot + "CustomVocabulary/CustomVocabulary.json";
        if (files.containsKey(vocabularyFile)) {
            writeVocabulary(region, botId, localeId, read(files, vocabularyFile).path("customVocabularyItems"),
                    append);
        }
        bots.touch(region, botId);
    }

    private ObjectNode entity(JsonNode doc, String idField, String id, String nameField, String botId,
                              String localeId, double now) {
        ObjectNode entity = store.record();
        entity.put(idField, id);
        entity.put(nameField, doc.path("name").asText());
        doc.fields().forEachRemaining(e -> {
            if (!"name".equals(e.getKey()) && !"identifier".equals(e.getKey())) {
                entity.set(e.getKey(), e.getValue().deepCopy());
            }
        });
        entity.put("botId", botId);
        entity.put("botVersion", LexStore.DRAFT);
        entity.put("localeId", localeId);
        entity.put("creationDateTime", now);
        entity.put("lastUpdatedDateTime", now);
        return entity;
    }

    private static void unname(ObjectNode node, Map<String, String> typeIds) {
        if (node.hasNonNull("slotTypeName")) {
            String name = node.get("slotTypeName").asText();
            node.put("slotTypeId", name.startsWith("AMAZON.") ? name : typeIds.getOrDefault(name, name));
            node.remove("slotTypeName");
        }
    }

    private void importVocabulary(String region, ObjectNode record, JsonNode spec, String strategy,
                                  Map<String, byte[]> files) {
        String botId = spec.path("botId").asText();
        String localeId = spec.path("localeId").asText();
        bots.draftLocale(region, botId, spec.path("botVersion").asText(), localeId);
        ArrayNode items = mapper.createArrayNode();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            if (file.getKey().endsWith(".tsv")) {
                String[] lines = new String(file.getValue(), StandardCharsets.UTF_8).split("\r?\n");
                for (int i = 0; i < lines.length; i++) {
                    String[] cells = lines[i].split("\t", -1);
                    if (lines[i].isBlank() || (i == 0 && "phrase".equalsIgnoreCase(cells[0].trim()))) {
                        continue;
                    }
                    ObjectNode item = items.addObject().put("phrase", cells[0].trim());
                    if (cells.length > 1 && !cells[1].isBlank()) {
                        try {
                            item.put("weight", Integer.parseInt(cells[1].trim()));
                        } catch (NumberFormatException e) {
                            throw failure("Line " + (i + 1) + " has a weight that is not a number.");
                        }
                    }
                    if (cells.length > 2 && !cells[2].isBlank()) {
                        item.put("displayAs", cells[2].trim());
                    }
                }
            } else if (file.getKey().endsWith("CustomVocabulary.json")) {
                read(files, file.getKey()).path("customVocabularyItems").forEach(items::add);
            }
        }
        if (items.isEmpty()) {
            throw failure("The archive has no custom vocabulary.");
        }
        String path = botId + "/" + LexStore.DRAFT + "/" + localeId;
        if ("FailOnConflict".equals(strategy) && store.live(region, LexStore.VOCABULARY, path).isPresent()) {
            throw failure("The locale already has a custom vocabulary.");
        }
        writeVocabulary(region, botId, localeId, items, "Append".equals(strategy));
        bots.changed(region, botId, localeId);
        record.put("importedResourceId", botId);
        record.put("importedResourceName", localeId);
    }

    private void writeVocabulary(String region, String botId, String localeId, JsonNode items, boolean append) {
        String path = botId + "/" + LexStore.DRAFT + "/" + localeId;
        ObjectNode vocabulary = store.live(region, LexStore.VOCABULARY, path).filter(v -> append)
                .orElseGet(() -> {
                    ObjectNode v = store.record();
                    v.put("botId", botId);
                    v.put("botVersion", LexStore.DRAFT);
                    v.put("localeId", localeId);
                    v.put("customVocabularyStatus", "Ready");
                    v.put("creationDateTime", LexStore.epoch());
                    v.putArray("items");
                    return v;
                });
        ArrayNode list = vocabulary.withArray("items");
        for (JsonNode item : items) {
            String phrase = item.path("phrase").asText();
            boolean present = false;
            for (JsonNode existing : list) {
                present |= phrase.equalsIgnoreCase(existing.path("phrase").asText());
            }
            if (present) {
                continue;
            }
            ObjectNode entry = list.addObject();
            entry.put("itemId", LexStore.id());
            entry.put("phrase", phrase);
            if (item.hasNonNull("weight")) {
                entry.put("weight", item.get("weight").asInt());
            }
            if (item.hasNonNull("displayAs")) {
                entry.put("displayAs", item.get("displayAs").asText());
            }
        }
        vocabulary.put("lastUpdatedDateTime", LexStore.epoch());
        store.put(region, LexStore.VOCABULARY, path, vocabulary);
    }

    public ObjectNode describeImport(ObjectNode in, String region) {
        String importId = in.path("importId").asText();
        ObjectNode record = store.live(region, LexStore.IMPORT, importId)
                .orElseThrow(() -> LexStore.notFound("The import " + importId + " does not exist."));
        ObjectNode out = LexStore.view(record);
        ArrayNode reasons = out.putArray("failureReasons");
        if ("Failed".equals(record.path("importStatus").asText())) {
            record.path("_").path("failureReasons").forEach(reasons::add);
        }
        return out;
    }

    public ObjectNode deleteImport(ObjectNode in, String region) {
        String importId = in.path("importId").asText();
        ObjectNode record = store.live(region, LexStore.IMPORT, importId)
                .orElseThrow(() -> LexStore.precondition("The import " + importId + " does not exist."));
        store.markDeleted(record, "importStatus", "Deleting");
        store.put(region, LexStore.IMPORT, importId, record);
        ObjectNode out = mapper.createObjectNode();
        out.put("importId", importId);
        out.put("importStatus", "Deleting");
        return out;
    }

    public ObjectNode listImports(ObjectNode in, String region) {
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode record : store.liveList(region, LexStore.IMPORT, "")) {
            JsonNode spec = record.path("resourceSpecification");
            JsonNode target = spec.elements().hasNext() ? spec.elements().next() : mapper.createObjectNode();
            if (!matches(in, target)) {
                continue;
            }
            String type = record.path("_").path("resourceType").asText();
            if (!LexStore.filtered(in.path("filters"), Map.of("ImportResourceType", type))) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            LexBots.copy(record, summary, "importId", "importedResourceId", "importedResourceName", "importStatus",
                    "mergeStrategy", "creationDateTime", "lastUpdatedDateTime");
            summary.put("importedResourceType", type);
            summaries.add(summary);
        }
        LexStore.sort(summaries, in.path("sortBy"), Map.of("LastUpdatedDateTime", "lastUpdatedDateTime"),
                "lastUpdatedDateTime");
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(in, out, "botId", "botVersion", "localeId");
        LexStore.page(summaries, in, out, "importSummaries", 10);
        return out;
    }
}
