package io.floci.aws.services.lexv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bots and what hangs off them: locales and their builds, numbered versions, aliases, replicas in other Regions,
 * tags and resource policies. A build checks the locale's intents, slots and slot types and keeps a copy of them as the
 * locale's model, which the runtime converses with and a new version snapshots.
 */
@ApplicationScoped
public class LexBots {

    private static final Pattern SLOT_REFERENCE = Pattern.compile("\\{([^}]*)\\}");

    private final LexStore store;
    private final ObjectMapper mapper;

    @Inject
    public LexBots(LexStore store) {
        this.store = store;
        this.mapper = store.mapper();
    }

    // ================================================================ bots

    public ObjectNode createBot(ObjectNode in, String region) {
        String name = in.path("botName").asText();
        LexStore.checkName("bot", name);
        LexStore.checkTags(in.get("botTags"));
        LexStore.checkTags(in.get("testBotAliasTags"));
        for (ObjectNode other : store.liveList(region, LexStore.BOT, "")) {
            if (name.equals(other.path("botName").asText())) {
                throw LexStore.conflict("A bot with the name " + name + " already exists.");
            }
        }
        if (store.liveList(region, LexStore.BOT, "").size() >= store.config().maxBots()) {
            throw LexStore.quota("You have reached the maximum number of bots in this Region.");
        }
        String type = in.path("botType").asText("Bot");
        checkMembers(region, type, in.get("botMembers"));
        String botId = LexStore.id();
        ObjectNode bot = store.record();
        bot.put("botId", botId);
        copy(in, bot, "botName", "description", "roleArn", "dataPrivacy", "idleSessionTTLInSeconds", "botMembers",
                "errorLogSettings");
        bot.put("botType", type);
        double now = LexStore.epoch();
        bot.put("creationDateTime", now);
        bot.put("lastUpdatedDateTime", now);
        store.settle(bot, "botStatus", "Creating", "Available");
        if (in.get("botTags") instanceof ObjectNode tags) {
            LexStore.tags(bot).setAll(tags);
        }
        LexStore.meta(bot).put("nextVersion", 1);
        store.put(region, LexStore.BOT, botId, bot);

        ObjectNode alias = store.record();
        alias.put("botAliasId", LexStore.TEST_ALIAS);
        alias.put("botAliasName", LexStore.TEST_ALIAS_NAME);
        alias.put("description", "test bot alias");
        alias.put("botVersion", LexStore.DRAFT);
        alias.put("botId", botId);
        alias.putObject("sentimentAnalysisSettings").put("detectSentiment", false);
        alias.put("creationDateTime", now);
        alias.put("lastUpdatedDateTime", now);
        alias.put("botAliasStatus", "Available");
        alias.putArray("botAliasHistoryEvents").addObject().put("botVersion", LexStore.DRAFT).put("startDate", now);
        if (in.get("testBotAliasTags") instanceof ObjectNode tags) {
            LexStore.tags(alias).setAll(tags);
        }
        store.put(region, LexStore.ALIAS, botId + "/" + LexStore.TEST_ALIAS, alias);

        ObjectNode out = LexStore.view(bot);
        out.remove("lastUpdatedDateTime");
        if (in.has("botTags")) {
            out.set("botTags", in.get("botTags"));
        }
        if (in.has("testBotAliasTags")) {
            out.set("testBotAliasTags", in.get("testBotAliasTags"));
        }
        return out;
    }

    public ObjectNode describeBot(ObjectNode in, String region) {
        ObjectNode bot = bot(region, in.path("botId").asText());
        ObjectNode out = LexStore.view(bot);
        out.set("failureReasons", failures(bot, "botStatus"));
        return out;
    }

    public ObjectNode updateBot(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        ObjectNode bot = existingBot(region, botId);
        requireSettled(bot, "botStatus", "bot " + botId);
        String name = in.path("botName").asText();
        LexStore.checkName("bot", name);
        for (ObjectNode other : store.liveList(region, LexStore.BOT, "")) {
            if (name.equals(other.path("botName").asText()) && !botId.equals(other.path("botId").asText())) {
                throw LexStore.conflict("A bot with the name " + name + " already exists.");
            }
        }
        String type = in.path("botType").asText(bot.path("botType").asText("Bot"));
        if (!type.equals(bot.path("botType").asText("Bot"))) {
            throw LexStore.validation("The bot type can't be changed.");
        }
        checkMembers(region, type, in.get("botMembers"));
        for (String field : List.of("description", "botMembers", "errorLogSettings")) {
            bot.remove(field);
        }
        copy(in, bot, "botName", "description", "roleArn", "dataPrivacy", "idleSessionTTLInSeconds", "botMembers",
                "errorLogSettings");
        bot.put("lastUpdatedDateTime", LexStore.epoch());
        store.settle(bot, "botStatus", "Updating", "Available");
        store.put(region, LexStore.BOT, botId, bot);
        return LexStore.view(bot);
    }

    public ObjectNode deleteBot(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        ObjectNode bot = existingBot(region, botId);
        if (!in.path("skipResourceInUseCheck").asBoolean(false)) {
            for (ObjectNode alias : store.liveList(region, LexStore.ALIAS, botId + "/")) {
                if (!LexStore.TEST_ALIAS.equals(alias.path("botAliasId").asText())
                        && alias.hasNonNull("botVersion")) {
                    throw LexStore.precondition("The bot " + botId + " is in use by the alias "
                            + alias.path("botAliasName").asText() + ". Set skipResourceInUseCheck to delete it.");
                }
            }
            for (ObjectNode network : store.liveList(region, LexStore.BOT, "")) {
                for (JsonNode member : network.path("botMembers")) {
                    if (botId.equals(member.path("botMemberId").asText())) {
                        throw LexStore.precondition("The bot " + botId + " is a member of the bot network "
                                + network.path("botName").asText() + ".");
                    }
                }
            }
        }
        for (String kind : List.of(LexStore.ALIAS, LexStore.VERSION, LexStore.LOCALE, LexStore.INTENT, LexStore.SLOT,
                LexStore.SLOT_TYPE, LexStore.VOCABULARY, LexStore.SESSION, LexStore.REPLICA,
                LexStore.RECOMMENDATION, LexStore.GENERATION, LexStore.ANALYZER)) {
            store.deleteAll(region, kind, botId + "/");
        }
        store.delete(region, LexStore.POLICY, store.botArn(region, botId));
        for (ObjectNode replica : store.list(region, LexStore.REPLICA, botId + "/")) {
            store.deleteAll(replica.path("replicaRegion").asText(), LexStore.BOT, botId);
        }
        store.markDeleted(bot, "botStatus", "Deleting");
        store.put(region, LexStore.BOT, botId, bot);
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", botId);
        out.put("botStatus", "Deleting");
        return out;
    }

    public ObjectNode listBots(ObjectNode in, String region) {
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode bot : store.liveList(region, LexStore.BOT, "")) {
            if (!LexStore.filtered(in.path("filters"), Map.of("BotName", bot.path("botName").asText(),
                    "BotType", bot.path("botType").asText("Bot")))) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            copy(bot, summary, "botId", "botName", "description", "botStatus", "lastUpdatedDateTime", "botType");
            latestVersion(region, bot.path("botId").asText()).ifPresent(v -> summary.put("latestBotVersion", v));
            summaries.add(summary);
        }
        LexStore.sort(summaries, in.path("sortBy"), Map.of("BotName", "botName"), "botName");
        ObjectNode out = mapper.createObjectNode();
        LexStore.page(summaries, in, out, "botSummaries", 10);
        return out;
    }

    private void checkMembers(String region, String type, JsonNode members) {
        if (!"BotNetwork".equals(type)) {
            if (members != null && !members.isEmpty()) {
                throw LexStore.validation("Only a bot network has bot members.");
            }
            return;
        }
        if (members == null || members.isEmpty()) {
            throw LexStore.validation("A bot network needs at least one bot member.");
        }
        for (JsonNode member : members) {
            String memberId = member.path("botMemberId").asText();
            ObjectNode bot = store.live(region, LexStore.BOT, memberId)
                    .orElseThrow(() -> LexStore.validation("The bot member " + memberId + " does not exist."));
            if ("BotNetwork".equals(bot.path("botType").asText())) {
                throw LexStore.validation("A bot network can't be a member of a bot network.");
            }
            String aliasId = member.path("botMemberAliasId").asText();
            if (store.live(region, LexStore.ALIAS, memberId + "/" + aliasId).isEmpty()) {
                throw LexStore.validation("The alias " + aliasId + " of bot member " + memberId + " does not exist.");
            }
        }
    }

    Optional<String> latestVersion(String region, String botId) {
        String latest = null;
        for (ObjectNode version : store.liveList(region, LexStore.VERSION, botId + "/")) {
            String v = version.path("botVersion").asText();
            if (latest == null || Integer.parseInt(v) > Integer.parseInt(latest)) {
                latest = v;
            }
        }
        return Optional.ofNullable(latest);
    }

    /** A bot that exists; {@code ResourceNotFoundException} otherwise. */
    ObjectNode bot(String region, String botId) {
        return store.live(region, LexStore.BOT, botId)
                .orElseThrow(() -> LexStore.notFound("The bot " + botId + " does not exist."));
    }

    /** A bot a change applies to; {@code PreconditionFailedException} when it does not exist. */
    ObjectNode existingBot(String region, String botId) {
        ObjectNode bot = store.live(region, LexStore.BOT, botId)
                .orElseThrow(() -> LexStore.precondition("Failed to retrieve resource since it does not exist: bot "
                        + botId + "."));
        if ("Deleting".equals(bot.path("botStatus").asText())) {
            throw LexStore.precondition("The bot " + botId + " is being deleted.");
        }
        return bot;
    }

    private static void requireSettled(ObjectNode record, String field, String what) {
        String status = record.path(field).asText();
        if (Set.of("Creating", "Versioning", "Importing", "Updating", "Deleting", "Building").contains(status)) {
            throw LexStore.conflict("The " + what + " is " + status + "; try again when it finishes.");
        }
    }

    private ArrayNode failures(ObjectNode record, String field) {
        ArrayNode reasons = mapper.createArrayNode();
        if ("Failed".equals(record.path(field).asText())) {
            record.path("_").path("failureReasons").forEach(reasons::add);
        }
        return reasons;
    }

    void touch(String region, String botId) {
        store.live(region, LexStore.BOT, botId).ifPresent(bot -> {
            bot.put("lastUpdatedDateTime", LexStore.epoch());
            store.put(region, LexStore.BOT, botId, bot);
        });
    }

    // ================================================================ locales

    public ObjectNode createBotLocale(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        ObjectNode bot = existingBot(region, botId);
        String localeId = in.path("localeId").asText();
        LexCatalog.checkLocale(localeId);
        String path = botId + "/" + LexStore.DRAFT + "/" + localeId;
        if (store.live(region, LexStore.LOCALE, path).isPresent()) {
            throw LexStore.conflict("The locale " + localeId + " already exists for bot " + botId + ".");
        }
        if ("BotNetwork".equals(bot.path("botType").asText())) {
            checkNetworkLocale(region, bot, localeId);
        }
        ObjectNode locale = store.record();
        locale.put("botId", botId);
        locale.put("botVersion", LexStore.DRAFT);
        locale.put("localeId", localeId);
        locale.put("localeName", LexCatalog.LOCALES.get(localeId));
        copy(in, locale, "description", "nluIntentConfidenceThreshold", "voiceSettings", "unifiedSpeechSettings",
                "audioFillerSettings", "speechRecognitionSettings", "generativeAISettings",
                "speechDetectionSensitivity", "speakerDiarizationSettings");
        double now = LexStore.epoch();
        locale.put("creationDateTime", now);
        locale.put("lastUpdatedDateTime", now);
        store.settle(locale, "botLocaleStatus", "Creating", "NotBuilt");
        store.put(region, LexStore.LOCALE, path, locale);

        ObjectNode fallback = store.record();
        fallback.put("intentId", LexStore.FALLBACK_ID);
        fallback.put("intentName", LexStore.FALLBACK);
        fallback.put("description", "Default intent when no other intent matches");
        fallback.put("parentIntentSignature", LexStore.FALLBACK_SIGNATURE);
        fallback.putObject("dialogCodeHook").put("enabled", false);
        fallback.putObject("fulfillmentCodeHook").put("enabled", false);
        fallback.put("botId", botId);
        fallback.put("botVersion", LexStore.DRAFT);
        fallback.put("localeId", localeId);
        fallback.put("creationDateTime", now);
        fallback.put("lastUpdatedDateTime", now);
        store.put(region, LexStore.INTENT, path + "/" + LexStore.FALLBACK_ID, fallback);
        touch(region, botId);

        ObjectNode out = LexStore.view(locale);
        out.remove("lastUpdatedDateTime");
        return out;
    }

    private void checkNetworkLocale(String region, ObjectNode network, String localeId) {
        for (JsonNode member : network.path("botMembers")) {
            String path = member.path("botMemberId").asText() + "/" + member.path("botMemberVersion").asText() + "/"
                    + localeId;
            if (store.live(region, LexStore.LOCALE, path).isEmpty()) {
                throw LexStore.validation("The bot member " + member.path("botMemberName").asText()
                        + " has no locale " + localeId + ".");
            }
        }
    }

    public ObjectNode describeBotLocale(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        String version = in.path("botVersion").asText();
        String localeId = in.path("localeId").asText();
        ObjectNode locale = locale(region, botId, version, localeId);
        ObjectNode out = LexStore.view(locale);
        String prefix = botId + "/" + version + "/" + localeId + "/";
        out.put("intentsCount", store.liveList(region, LexStore.INTENT, prefix).size());
        out.put("slotTypesCount", store.liveList(region, LexStore.SLOT_TYPE, prefix).size());
        out.set("failureReasons", failures(locale, "botLocaleStatus"));
        out.set("botLocaleHistoryEvents", locale.path("_").path("history").isArray()
                ? locale.path("_").get("history").deepCopy() : mapper.createArrayNode());
        out.putArray("recommendedActions");
        if ("Failed".equals(locale.path("botLocaleStatus").asText())) {
            out.withArray("recommendedActions").add("Fix the failure reasons and build the locale again.");
        }
        return out;
    }

    public ObjectNode updateBotLocale(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        existingBot(region, botId);
        String localeId = in.path("localeId").asText();
        String path = botId + "/" + LexStore.DRAFT + "/" + localeId;
        ObjectNode locale = store.live(region, LexStore.LOCALE, path)
                .orElseThrow(() -> LexStore.precondition("Failed to retrieve resource since it does not exist: locale "
                        + localeId + " of bot " + botId + "."));
        requireSettled(locale, "botLocaleStatus", "locale " + localeId);
        for (String field : List.of("description", "voiceSettings", "unifiedSpeechSettings", "audioFillerSettings",
                "speechRecognitionSettings", "generativeAISettings", "speechDetectionSensitivity",
                "speakerDiarizationSettings")) {
            locale.remove(field);
        }
        copy(in, locale, "description", "nluIntentConfidenceThreshold", "voiceSettings", "unifiedSpeechSettings",
                "audioFillerSettings", "speechRecognitionSettings", "generativeAISettings",
                "speechDetectionSensitivity", "speakerDiarizationSettings");
        locale.put("lastUpdatedDateTime", LexStore.epoch());
        locale.put("botLocaleStatus", "NotBuilt");
        store.put(region, LexStore.LOCALE, path, locale);
        touch(region, botId);
        ObjectNode out = LexStore.view(locale);
        out.putArray("failureReasons");
        out.putArray("recommendedActions");
        return out;
    }

    public ObjectNode deleteBotLocale(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        existingBot(region, botId);
        String localeId = in.path("localeId").asText();
        String path = botId + "/" + LexStore.DRAFT + "/" + localeId;
        ObjectNode locale = store.live(region, LexStore.LOCALE, path)
                .orElseThrow(() -> LexStore.precondition("Failed to retrieve resource since it does not exist: locale "
                        + localeId + " of bot " + botId + "."));
        if ("Building".equals(locale.path("botLocaleStatus").asText())) {
            throw LexStore.conflict("The locale " + localeId + " is building; try again when it finishes.");
        }
        for (String kind : List.of(LexStore.INTENT, LexStore.SLOT, LexStore.SLOT_TYPE, LexStore.VOCABULARY)) {
            store.deleteAll(region, kind, path + "/");
            store.deleteAll(region, kind, path);
        }
        store.markDeleted(locale, "botLocaleStatus", "Deleting");
        store.put(region, LexStore.LOCALE, path, locale);
        touch(region, botId);
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", botId);
        out.put("botVersion", LexStore.DRAFT);
        out.put("localeId", localeId);
        out.put("botLocaleStatus", "Deleting");
        return out;
    }

    public ObjectNode listBotLocales(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        String version = in.path("botVersion").asText();
        bot(region, botId);
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode locale : store.liveList(region, LexStore.LOCALE, botId + "/" + version + "/")) {
            if (!LexStore.filtered(in.path("filters"), Map.of("BotLocaleName", locale.path("localeName").asText()))) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            copy(locale, summary, "localeId", "localeName", "description", "botLocaleStatus", "lastUpdatedDateTime",
                    "lastBuildSubmittedDateTime");
            summaries.add(summary);
        }
        LexStore.sort(summaries, in.path("sortBy"), Map.of("BotLocaleName", "localeName"), "localeName");
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", botId);
        out.put("botVersion", version);
        LexStore.page(summaries, in, out, "botLocaleSummaries", 10);
        return out;
    }

    /** A locale of a version that exists; {@code ResourceNotFoundException} otherwise. */
    ObjectNode locale(String region, String botId, String version, String localeId) {
        bot(region, botId);
        return store.live(region, LexStore.LOCALE, botId + "/" + version + "/" + localeId)
                .orElseThrow(() -> LexStore.notFound("The locale " + localeId + " of version " + version + " of bot "
                        + botId + " does not exist."));
    }

    /** The DRAFT locale a change to its intents, slots or slot types applies to. */
    ObjectNode draftLocale(String region, String botId, String version, String localeId) {
        if (!LexStore.DRAFT.equals(version)) {
            throw LexStore.validation("Only the DRAFT version of a bot can be changed.");
        }
        existingBot(region, botId);
        return store.live(region, LexStore.LOCALE, botId + "/" + version + "/" + localeId)
                .orElseThrow(() -> LexStore.precondition("Failed to retrieve parent resource since it does not "
                        + "exist: locale " + localeId + " of bot " + botId + "."));
    }

    /** Records that a DRAFT locale's model changed: it needs another build before the change takes effect. */
    void changed(String region, String botId, String localeId) {
        String path = botId + "/" + LexStore.DRAFT + "/" + localeId;
        store.live(region, LexStore.LOCALE, path).ifPresent(locale -> {
            if (!"Building".equals(locale.path("botLocaleStatus").asText())) {
                locale.put("botLocaleStatus", "NotBuilt");
            }
            locale.put("lastUpdatedDateTime", LexStore.epoch());
            store.put(region, LexStore.LOCALE, path, locale);
        });
        touch(region, botId);
    }

    public ObjectNode buildBotLocale(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        existingBot(region, botId);
        String localeId = in.path("localeId").asText();
        if (!LexStore.DRAFT.equals(in.path("botVersion").asText())) {
            throw LexStore.validation("Only the DRAFT version of a bot can be built.");
        }
        String path = botId + "/" + LexStore.DRAFT + "/" + localeId;
        ObjectNode locale = store.live(region, LexStore.LOCALE, path)
                .orElseThrow(() -> LexStore.precondition("Failed to retrieve resource since it does not exist: locale "
                        + localeId + " of bot " + botId + "."));
        String status = locale.path("botLocaleStatus").asText();
        if ("Building".equals(status) || "Creating".equals(status) || "Deleting".equals(status)) {
            throw LexStore.conflict("The locale " + localeId + " is " + status + "; try again when it finishes.");
        }
        ObjectNode model = snapshot(region, path, locale);
        List<String> failures = check(model);
        double now = LexStore.epoch();
        locale.put("lastBuildSubmittedDateTime", now);
        ObjectNode meta = LexStore.meta(locale);
        meta.putArray("failureReasons");
        if (failures.isEmpty()) {
            meta.set("model", model);
        } else {
            failures.forEach(meta.withArray("failureReasons")::add);
        }
        ArrayNode history = meta.has("history") ? (ArrayNode) meta.get("history") : meta.putArray("history");
        history.addObject().put("event", failures.isEmpty() ? "Built" : "Failed").put("eventDate", now);
        locale.put("botLocaleStatus", "Building");
        meta.put("settleAt", LexStore.millis() + store.config().buildMillis()).put("settleField", "botLocaleStatus")
                .put("settled", failures.isEmpty() ? "Built" : "Failed");
        store.put(region, LexStore.LOCALE, path, locale);
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", botId);
        out.put("botVersion", LexStore.DRAFT);
        out.put("localeId", localeId);
        out.put("botLocaleStatus", "Building");
        out.put("lastBuildSubmittedDateTime", now);
        return out;
    }

    /** A locale's intents, slots, slot types and custom vocabulary as they stand, with the locale's own settings. */
    ObjectNode snapshot(String region, String path, ObjectNode locale) {
        ObjectNode model = mapper.createObjectNode();
        model.set("locale", LexStore.view(locale));
        ArrayNode intents = model.putArray("intents");
        store.liveList(region, LexStore.INTENT, path + "/").forEach(i -> intents.add(LexStore.view(i)));
        ArrayNode slots = model.putArray("slots");
        store.liveList(region, LexStore.SLOT, path + "/").forEach(s -> slots.add(LexStore.view(s)));
        ArrayNode types = model.putArray("slotTypes");
        store.liveList(region, LexStore.SLOT_TYPE, path + "/").forEach(t -> types.add(LexStore.view(t)));
        store.live(region, LexStore.VOCABULARY, path).ifPresent(v -> model.set("vocabulary", LexStore.view(v)));
        return model;
    }

    /** The reasons a model can't be built: missing utterances, prompts, slot types or values. */
    static List<String> check(ObjectNode model) {
        List<String> failures = new ArrayList<>();
        Set<String> typeIds = new HashSet<>();
        for (JsonNode type : model.path("slotTypes")) {
            typeIds.add(type.path("slotTypeId").asText());
            boolean hasValues = type.path("slotTypeValues").size() > 0;
            boolean derived = type.hasNonNull("parentSlotTypeSignature") || type.has("compositeSlotTypeSetting")
                    || type.has("externalSourceSetting");
            if (!hasValues && !derived) {
                failures.add("Slot type " + type.path("slotTypeName").asText() + " must have at least one value.");
            }
            for (JsonNode sub : type.path("compositeSlotTypeSetting").path("subSlots")) {
                String subType = sub.path("slotTypeId").asText();
                if (!subType.startsWith("AMAZON.") && model.path("slotTypes").findValues("slotTypeId").stream()
                        .noneMatch(t -> t.asText().equals(subType))) {
                    failures.add("Sub-slot " + sub.path("name").asText() + " of composite slot type "
                            + type.path("slotTypeName").asText() + " references a slot type that does not exist.");
                }
            }
        }
        int custom = 0;
        for (JsonNode intent : model.path("intents")) {
            String intentId = intent.path("intentId").asText();
            String intentName = intent.path("intentName").asText();
            String signature = intent.path("parentIntentSignature").asText("");
            if (!LexStore.FALLBACK_ID.equals(intentId)) {
                custom++;
            }
            Set<String> slotNames = new LinkedHashSet<>();
            for (JsonNode slot : model.path("slots")) {
                if (!intentId.equals(slot.path("intentId").asText())) {
                    continue;
                }
                String slotName = slot.path("slotName").asText();
                slotNames.add(slotName);
                String typeId = slot.path("slotTypeId").asText("");
                if (!typeId.isEmpty() && !typeId.startsWith("AMAZON.") && !typeIds.contains(typeId)) {
                    failures.add("Slot " + slotName + " of intent " + intentName + " references slot type "
                            + typeId + ", which does not exist.");
                }
                JsonNode setting = slot.path("valueElicitationSetting");
                if ("Required".equals(setting.path("slotConstraint").asText())
                        && !setting.has("promptSpecification") && !slot.has("subSlotSetting")) {
                    failures.add("Required slot " + slotName + " of intent " + intentName
                            + " must have a prompt.");
                }
            }
            if (signature.isEmpty() && intent.path("sampleUtterances").isEmpty()) {
                failures.add("Intent " + intentName + " must have at least one sample utterance.");
            }
            for (JsonNode utterance : intent.path("sampleUtterances")) {
                Matcher matcher = SLOT_REFERENCE.matcher(utterance.path("utterance").asText());
                while (matcher.find()) {
                    if (!slotNames.contains(matcher.group(1))) {
                        failures.add("The sample utterance \"" + utterance.path("utterance").asText()
                                + "\" of intent " + intentName + " references slot " + matcher.group(1)
                                + ", which the intent does not have.");
                    }
                }
            }
        }
        if (custom == 0) {
            failures.add("The bot locale must have at least one intent other than the fallback intent.");
        }
        return failures;
    }

    /** The model a locale of a version converses with: its last successful build. */
    Optional<ObjectNode> model(String region, String botId, String version, String localeId) {
        return store.live(region, LexStore.LOCALE, botId + "/" + version + "/" + localeId)
                .map(locale -> locale.path("_").path("model"))
                .filter(JsonNode::isObject)
                .map(model -> (ObjectNode) model);
    }

    // ================================================================ versions

    public ObjectNode createBotVersion(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        ObjectNode bot = existingBot(region, botId);
        requireSettled(bot, "botStatus", "bot " + botId);
        JsonNode spec = in.path("botVersionLocaleSpecification");
        if (!spec.isObject() || spec.isEmpty()) {
            throw LexStore.validation("botVersionLocaleSpecification must name at least one locale.");
        }
        int number = LexStore.meta(bot).path("nextVersion").asInt(1);
        String version = String.valueOf(number);
        List<String[]> copies = new ArrayList<>();
        spec.fields().forEachRemaining(entry -> {
            String localeId = entry.getKey();
            String source = entry.getValue().path("sourceBotVersion").asText(LexStore.DRAFT);
            ObjectNode locale = store.live(region, LexStore.LOCALE, botId + "/" + source + "/" + localeId)
                    .orElseThrow(() -> LexStore.precondition("The locale " + localeId + " of version " + source
                            + " of bot " + botId + " does not exist."));
            if (!"Built".equals(locale.path("botLocaleStatus").asText())
                    || !locale.path("_").path("model").isObject()) {
                throw LexStore.precondition("The locale " + localeId + " of version " + source + " of bot " + botId
                        + " is not built. Build it before creating a version.");
            }
            copies.add(new String[] {localeId, source});
        });
        double now = LexStore.epoch();
        for (String[] copy : copies) {
            ObjectNode model = model(region, botId, copy[1], copy[0]).orElseThrow();
            writeModel(region, botId, version, copy[0], model, now);
        }
        ObjectNode record = store.record();
        record.put("botId", botId);
        copy(bot, record, "botName", "roleArn", "dataPrivacy", "idleSessionTTLInSeconds", "botType", "botMembers");
        record.put("botVersion", version);
        if (in.hasNonNull("description")) {
            record.put("description", in.get("description").asText());
        }
        record.put("creationDateTime", now);
        record.set("botVersionLocaleSpecification", spec.deepCopy());
        store.settle(record, "botStatus", "Creating", "Available");
        store.put(region, LexStore.VERSION, botId + "/" + version, record);
        LexStore.meta(bot).put("nextVersion", number + 1);
        store.settle(bot, "botStatus", "Versioning", "Available");
        store.put(region, LexStore.BOT, botId, bot);
        replicate(region, botId);
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", botId);
        if (in.hasNonNull("description")) {
            out.put("description", in.get("description").asText());
        }
        out.put("botVersion", version);
        out.set("botVersionLocaleSpecification", spec.deepCopy());
        out.put("botStatus", "Creating");
        out.put("creationDateTime", now);
        return out;
    }

    /** Writes a built model as the locale, intents, slots and slot types of a version, and keeps it to converse with. */
    void writeModel(String region, String botId, String version, String localeId, ObjectNode model, double now) {
        String path = botId + "/" + version + "/" + localeId;
        ObjectNode locale = store.record();
        locale.setAll(retarget((ObjectNode) model.get("locale"), version));
        locale.put("botLocaleStatus", "Built");
        locale.put("creationDateTime", now);
        locale.put("lastUpdatedDateTime", now);
        ObjectNode kept = model.deepCopy();
        LexStore.meta(locale).set("model", kept);
        store.put(region, LexStore.LOCALE, path, locale);
        for (JsonNode intent : model.path("intents")) {
            ObjectNode record = store.record();
            record.setAll(retarget((ObjectNode) intent, version));
            store.put(region, LexStore.INTENT, path + "/" + intent.path("intentId").asText(), record);
        }
        for (JsonNode slot : model.path("slots")) {
            ObjectNode record = store.record();
            record.setAll(retarget((ObjectNode) slot, version));
            store.put(region, LexStore.SLOT, path + "/" + slot.path("intentId").asText() + "/"
                    + slot.path("slotId").asText(), record);
        }
        for (JsonNode type : model.path("slotTypes")) {
            ObjectNode record = store.record();
            record.setAll(retarget((ObjectNode) type, version));
            store.put(region, LexStore.SLOT_TYPE, path + "/" + type.path("slotTypeId").asText(), record);
        }
        if (model.get("vocabulary") instanceof ObjectNode vocabulary) {
            ObjectNode record = store.record();
            record.setAll(retarget(vocabulary, version));
            store.put(region, LexStore.VOCABULARY, path, record);
        }
    }

    private static ObjectNode retarget(ObjectNode node, String version) {
        ObjectNode copy = node.deepCopy();
        copy.put("botVersion", version);
        return copy;
    }

    public ObjectNode describeBotVersion(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        String version = in.path("botVersion").asText();
        bot(region, botId);
        ObjectNode record = store.live(region, LexStore.VERSION, botId + "/" + version)
                .orElseThrow(() -> LexStore.notFound("The version " + version + " of bot " + botId
                        + " does not exist."));
        ObjectNode out = LexStore.view(record);
        out.remove("botVersionLocaleSpecification");
        out.set("failureReasons", failures(record, "botStatus"));
        ArrayNode parents = out.putArray("parentBotNetworks");
        for (ObjectNode network : store.liveList(region, LexStore.BOT, "")) {
            for (JsonNode member : network.path("botMembers")) {
                if (botId.equals(member.path("botMemberId").asText())
                        && version.equals(member.path("botMemberVersion").asText())) {
                    parents.addObject().put("botId", network.path("botId").asText()).put("botVersion",
                            LexStore.DRAFT);
                }
            }
        }
        return out;
    }

    public ObjectNode listBotVersions(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        ObjectNode bot = bot(region, botId);
        List<ObjectNode> summaries = new ArrayList<>();
        ObjectNode draft = mapper.createObjectNode();
        draft.put("botName", bot.path("botName").asText());
        draft.put("botVersion", LexStore.DRAFT);
        if (bot.hasNonNull("description")) {
            draft.put("description", bot.get("description").asText());
        }
        draft.put("botStatus", bot.path("botStatus").asText());
        draft.put("creationDateTime", bot.path("creationDateTime").asDouble());
        summaries.add(draft);
        for (ObjectNode version : store.liveList(region, LexStore.VERSION, botId + "/")) {
            ObjectNode summary = mapper.createObjectNode();
            copy(version, summary, "botName", "botVersion", "description", "botStatus", "creationDateTime");
            summaries.add(summary);
        }
        boolean descending = "Descending".equals(in.path("sortBy").path("order").asText());
        summaries.sort((a, b) -> {
            int c = Integer.compare(order(a), order(b));
            return descending ? -c : c;
        });
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", botId);
        LexStore.page(summaries, in, out, "botVersionSummaries", 10);
        return out;
    }

    private static int order(JsonNode summary) {
        String version = summary.path("botVersion").asText();
        return LexStore.DRAFT.equals(version) ? 0 : Integer.parseInt(version);
    }

    public ObjectNode deleteBotVersion(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        String version = in.path("botVersion").asText();
        existingBot(region, botId);
        ObjectNode record = store.live(region, LexStore.VERSION, botId + "/" + version)
                .orElseThrow(() -> LexStore.precondition("Failed to retrieve resource since it does not exist: "
                        + "version " + version + " of bot " + botId + "."));
        if (!in.path("skipResourceInUseCheck").asBoolean(false)) {
            for (ObjectNode alias : store.liveList(region, LexStore.ALIAS, botId + "/")) {
                if (version.equals(alias.path("botVersion").asText())) {
                    throw LexStore.precondition("The version " + version + " is in use by the alias "
                            + alias.path("botAliasName").asText() + ". Set skipResourceInUseCheck to delete it.");
                }
            }
        }
        String prefix = botId + "/" + version + "/";
        for (String kind : List.of(LexStore.LOCALE, LexStore.INTENT, LexStore.SLOT, LexStore.SLOT_TYPE,
                LexStore.VOCABULARY)) {
            store.deleteAll(region, kind, prefix);
        }
        store.markDeleted(record, "botStatus", "Deleting");
        store.put(region, LexStore.VERSION, botId + "/" + version, record);
        replicate(region, botId);
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", botId);
        out.put("botVersion", version);
        out.put("botStatus", "Deleting");
        return out;
    }

    // ================================================================ aliases

    public ObjectNode createBotAlias(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        existingBot(region, botId);
        String name = in.path("botAliasName").asText();
        LexStore.checkName("bot alias", name);
        LexStore.checkTags(in.get("tags"));
        for (ObjectNode other : store.liveList(region, LexStore.ALIAS, botId + "/")) {
            if (name.equals(other.path("botAliasName").asText())) {
                throw LexStore.conflict("An alias with the name " + name + " already exists for bot " + botId + ".");
            }
        }
        if (store.liveList(region, LexStore.ALIAS, botId + "/").size() > store.config().maxAliasesPerBot()) {
            throw LexStore.quota("The bot " + botId + " has the maximum number of aliases.");
        }
        checkAliasVersion(region, botId, in, false);
        String aliasId = LexStore.id();
        ObjectNode alias = store.record();
        alias.put("botAliasId", aliasId);
        copy(in, alias, "botAliasName", "description", "botVersion", "botAliasLocaleSettings",
                "conversationLogSettings", "sentimentAnalysisSettings");
        alias.put("botId", botId);
        double now = LexStore.epoch();
        alias.put("creationDateTime", now);
        alias.put("lastUpdatedDateTime", now);
        ArrayNode history = alias.putArray("botAliasHistoryEvents");
        if (in.hasNonNull("botVersion")) {
            history.addObject().put("botVersion", in.get("botVersion").asText()).put("startDate", now);
        }
        store.settle(alias, "botAliasStatus", "Creating", "Available");
        if (in.get("tags") instanceof ObjectNode tags) {
            LexStore.tags(alias).setAll(tags);
        }
        store.put(region, LexStore.ALIAS, botId + "/" + aliasId, alias);
        replicate(region, botId);
        ObjectNode out = LexStore.view(alias);
        out.remove(List.of("lastUpdatedDateTime", "botAliasHistoryEvents"));
        if (in.has("tags")) {
            out.set("tags", in.get("tags"));
        }
        return out;
    }

    private void checkAliasVersion(String region, String botId, JsonNode in, boolean testAlias) {
        String version = in.path("botVersion").asText(null);
        if (version == null) {
            if (in.path("botAliasLocaleSettings").isObject() && !in.path("botAliasLocaleSettings").isEmpty()
                    && !testAlias) {
                throw LexStore.validation("Locale settings need a bot version.");
            }
            return;
        }
        if (testAlias) {
            if (!LexStore.DRAFT.equals(version)) {
                throw LexStore.validation("The test bot alias always uses the DRAFT version.");
            }
        } else {
            if (LexStore.DRAFT.equals(version)) {
                throw LexStore.validation("Only the test bot alias can use the DRAFT version.");
            }
            if (store.live(region, LexStore.VERSION, botId + "/" + version).isEmpty()) {
                throw LexStore.precondition("The version " + version + " of bot " + botId + " does not exist.");
            }
        }
        in.path("botAliasLocaleSettings").fieldNames().forEachRemaining(localeId -> {
            if (store.live(region, LexStore.LOCALE, botId + "/" + version + "/" + localeId).isEmpty()) {
                throw LexStore.validation("The version " + version + " of bot " + botId + " has no locale "
                        + localeId + ".");
            }
        });
    }

    public ObjectNode describeBotAlias(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        bot(region, botId);
        ObjectNode alias = alias(region, botId, in.path("botAliasId").asText());
        ObjectNode out = LexStore.view(alias);
        ArrayNode parents = out.putArray("parentBotNetworks");
        for (ObjectNode network : store.liveList(region, LexStore.BOT, "")) {
            for (JsonNode member : network.path("botMembers")) {
                if (botId.equals(member.path("botMemberId").asText())
                        && alias.path("botAliasId").asText().equals(member.path("botMemberAliasId").asText())) {
                    parents.addObject().put("botId", network.path("botId").asText()).put("botVersion",
                            LexStore.DRAFT);
                }
            }
        }
        return out;
    }

    ObjectNode alias(String region, String botId, String aliasId) {
        return store.live(region, LexStore.ALIAS, botId + "/" + aliasId)
                .orElseThrow(() -> LexStore.notFound("The alias " + aliasId + " of bot " + botId
                        + " does not exist."));
    }

    public ObjectNode updateBotAlias(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        existingBot(region, botId);
        String aliasId = in.path("botAliasId").asText();
        String path = botId + "/" + aliasId;
        ObjectNode alias = store.live(region, LexStore.ALIAS, path)
                .orElseThrow(() -> LexStore.precondition("Failed to retrieve resource since it does not exist: "
                        + "alias " + aliasId + " of bot " + botId + "."));
        requireSettled(alias, "botAliasStatus", "alias " + aliasId);
        boolean testAlias = LexStore.TEST_ALIAS.equals(aliasId);
        String name = in.path("botAliasName").asText();
        LexStore.checkName("bot alias", name);
        if (testAlias && !LexStore.TEST_ALIAS_NAME.equals(name)) {
            throw LexStore.validation("The test bot alias can't be renamed.");
        }
        for (ObjectNode other : store.liveList(region, LexStore.ALIAS, botId + "/")) {
            if (name.equals(other.path("botAliasName").asText())
                    && !aliasId.equals(other.path("botAliasId").asText())) {
                throw LexStore.conflict("An alias with the name " + name + " already exists for bot " + botId + ".");
            }
        }
        checkAliasVersion(region, botId, in, testAlias);
        String before = alias.path("botVersion").asText(null);
        String after = testAlias ? LexStore.DRAFT : in.path("botVersion").asText(null);
        double now = LexStore.epoch();
        if (!Objects.equals(before, after)) {
            ArrayNode history = alias.withArray("botAliasHistoryEvents");
            for (JsonNode event : history) {
                if (!event.has("endDate")) {
                    ((ObjectNode) event).put("endDate", now);
                }
            }
            if (after != null) {
                history.addObject().put("botVersion", after).put("startDate", now);
            }
        }
        for (String field : List.of("description", "botVersion", "botAliasLocaleSettings", "conversationLogSettings",
                "sentimentAnalysisSettings")) {
            alias.remove(field);
        }
        copy(in, alias, "botAliasName", "description", "botVersion", "botAliasLocaleSettings",
                "conversationLogSettings", "sentimentAnalysisSettings");
        if (testAlias) {
            alias.put("botVersion", LexStore.DRAFT);
        }
        alias.put("lastUpdatedDateTime", now);
        store.put(region, LexStore.ALIAS, path, alias);
        replicate(region, botId);
        ObjectNode out = LexStore.view(alias);
        out.remove("botAliasHistoryEvents");
        return out;
    }

    public ObjectNode deleteBotAlias(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        existingBot(region, botId);
        String aliasId = in.path("botAliasId").asText();
        if (LexStore.TEST_ALIAS.equals(aliasId)) {
            throw LexStore.validation("The test bot alias can't be deleted.");
        }
        String path = botId + "/" + aliasId;
        ObjectNode alias = store.live(region, LexStore.ALIAS, path)
                .orElseThrow(() -> LexStore.precondition("Failed to retrieve resource since it does not exist: "
                        + "alias " + aliasId + " of bot " + botId + "."));
        if (!in.path("skipResourceInUseCheck").asBoolean(false)) {
            for (ObjectNode network : store.liveList(region, LexStore.BOT, "")) {
                for (JsonNode member : network.path("botMembers")) {
                    if (botId.equals(member.path("botMemberId").asText())
                            && aliasId.equals(member.path("botMemberAliasId").asText())) {
                        throw LexStore.precondition("The alias " + aliasId + " is used by the bot network "
                                + network.path("botName").asText() + ".");
                    }
                }
            }
        }
        store.deleteAll(region, LexStore.SESSION, path + "/");
        store.delete(region, LexStore.POLICY, store.aliasArn(region, botId, aliasId));
        store.markDeleted(alias, "botAliasStatus", "Deleting");
        store.put(region, LexStore.ALIAS, path, alias);
        replicate(region, botId);
        ObjectNode out = mapper.createObjectNode();
        out.put("botAliasId", aliasId);
        out.put("botId", botId);
        out.put("botAliasStatus", "Deleting");
        return out;
    }

    public ObjectNode listBotAliases(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        bot(region, botId);
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode alias : store.liveList(region, LexStore.ALIAS, botId + "/")) {
            ObjectNode summary = mapper.createObjectNode();
            copy(alias, summary, "botAliasId", "botAliasName", "description", "botVersion", "botAliasStatus",
                    "creationDateTime", "lastUpdatedDateTime");
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        LexStore.page(summaries, in, out, "botAliasSummaries", 10);
        out.put("botId", botId);
        return out;
    }

    // ================================================================ replicas

    public ObjectNode createBotReplica(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        ObjectNode bot = existingBot(region, botId);
        String replicaRegion = in.path("replicaRegion").asText();
        if (replicaRegion.equals(region)) {
            throw LexStore.validation("The replica Region must differ from the bot's Region.");
        }
        if (store.live(region, LexStore.REPLICA, botId + "/" + replicaRegion).isPresent()) {
            throw LexStore.conflict("The bot " + botId + " already has a replica in " + replicaRegion + ".");
        }
        if (bot.path("_").path("sourceRegion").isTextual()) {
            throw LexStore.precondition("The bot " + botId + " is itself a replica.");
        }
        ObjectNode replica = store.record();
        replica.put("botId", botId);
        replica.put("replicaRegion", replicaRegion);
        replica.put("sourceRegion", region);
        replica.put("creationDateTime", LexStore.epoch());
        store.settle(replica, "botReplicaStatus", "Enabling", "Enabled");
        store.put(region, LexStore.REPLICA, botId + "/" + replicaRegion, replica);
        replicate(region, botId);
        return LexStore.view(replica);
    }

    public ObjectNode describeBotReplica(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        String replicaRegion = in.path("replicaRegion").asText();
        ObjectNode replica = store.live(region, LexStore.REPLICA, botId + "/" + replicaRegion)
                .orElseThrow(() -> LexStore.notFound("The bot " + botId + " has no replica in " + replicaRegion
                        + "."));
        ObjectNode out = LexStore.view(replica);
        out.putArray("failureReasons");
        return out;
    }

    public ObjectNode deleteBotReplica(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        existingBot(region, botId);
        String replicaRegion = in.path("replicaRegion").asText();
        String path = botId + "/" + replicaRegion;
        ObjectNode replica = store.live(region, LexStore.REPLICA, path)
                .orElseThrow(() -> LexStore.precondition("The bot " + botId + " has no replica in " + replicaRegion
                        + "."));
        for (String kind : List.of(LexStore.ALIAS, LexStore.VERSION, LexStore.LOCALE, LexStore.INTENT, LexStore.SLOT,
                LexStore.SLOT_TYPE, LexStore.VOCABULARY, LexStore.SESSION)) {
            store.deleteAll(replicaRegion, kind, botId + "/");
        }
        store.delete(replicaRegion, LexStore.BOT, botId);
        store.markDeleted(replica, "botReplicaStatus", "Deleting");
        store.put(region, LexStore.REPLICA, path, replica);
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", botId);
        out.put("replicaRegion", replicaRegion);
        out.put("botReplicaStatus", "Deleting");
        return out;
    }

    public ObjectNode listBotReplicas(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        bot(region, botId);
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", botId);
        out.put("sourceRegion", region);
        ArrayNode summaries = out.putArray("botReplicaSummaries");
        for (ObjectNode replica : store.liveList(region, LexStore.REPLICA, botId + "/")) {
            ObjectNode summary = summaries.addObject();
            copy(replica, summary, "replicaRegion", "creationDateTime", "botReplicaStatus");
            summary.putArray("failureReasons");
        }
        return out;
    }

    public ObjectNode listBotAliasReplicas(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        String replicaRegion = in.path("replicaRegion").asText();
        requireReplica(region, botId, replicaRegion);
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode alias : store.liveList(replicaRegion, LexStore.ALIAS, botId + "/")) {
            ObjectNode summary = mapper.createObjectNode();
            copy(alias, summary, "botAliasId", "botVersion", "creationDateTime", "lastUpdatedDateTime");
            summary.put("botAliasReplicationStatus", "Available");
            summary.putArray("failureReasons");
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", botId);
        out.put("sourceRegion", region);
        out.put("replicaRegion", replicaRegion);
        LexStore.page(summaries, in, out, "botAliasReplicaSummaries", 10);
        return out;
    }

    public ObjectNode listBotVersionReplicas(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        String replicaRegion = in.path("replicaRegion").asText();
        requireReplica(region, botId, replicaRegion);
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode version : store.liveList(replicaRegion, LexStore.VERSION, botId + "/")) {
            ObjectNode summary = mapper.createObjectNode();
            copy(version, summary, "botVersion", "creationDateTime");
            summary.put("botVersionReplicationStatus", "Available");
            summary.putArray("failureReasons");
            summaries.add(summary);
        }
        boolean descending = "Descending".equals(in.path("sortBy").path("order").asText());
        summaries.sort((a, b) -> {
            int c = Integer.compare(order(a), order(b));
            return descending ? -c : c;
        });
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", botId);
        out.put("sourceRegion", region);
        out.put("replicaRegion", replicaRegion);
        LexStore.page(summaries, in, out, "botVersionReplicaSummaries", 10);
        return out;
    }

    private void requireReplica(String region, String botId, String replicaRegion) {
        bot(region, botId);
        if (store.live(region, LexStore.REPLICA, botId + "/" + replicaRegion).isEmpty()) {
            throw LexStore.notFound("The bot " + botId + " has no replica in " + replicaRegion + ".");
        }
    }

    /**
     * Copies a bot's numbered versions and aliases to each of its replica Regions, where the runtime serves them as it
     * does in the source Region. The replica's DRAFT is not replicated, as on AWS.
     */
    void replicate(String region, String botId) {
        List<ObjectNode> replicas = store.liveList(region, LexStore.REPLICA, botId + "/");
        if (replicas.isEmpty()) {
            return;
        }
        ObjectNode bot = store.live(region, LexStore.BOT, botId).orElse(null);
        if (bot == null) {
            return;
        }
        for (ObjectNode replica : replicas) {
            if ("Deleting".equals(replica.path("botReplicaStatus").asText())) {
                continue;
            }
            String target = replica.path("replicaRegion").asText();
            for (String kind : List.of(LexStore.ALIAS, LexStore.VERSION, LexStore.LOCALE, LexStore.INTENT,
                    LexStore.SLOT, LexStore.SLOT_TYPE, LexStore.VOCABULARY)) {
                store.deleteAll(target, kind, botId + "/");
            }
            ObjectNode copy = bot.deepCopy();
            LexStore.meta(copy).put("sourceRegion", region);
            copy.put("botStatus", "Available");
            store.put(target, LexStore.BOT, botId, copy);
            for (String kind : List.of(LexStore.VERSION, LexStore.LOCALE, LexStore.INTENT, LexStore.SLOT,
                    LexStore.SLOT_TYPE, LexStore.VOCABULARY, LexStore.ALIAS)) {
                for (ObjectNode record : store.liveList(region, kind, botId + "/")) {
                    String path = record.path("_").path("path").asText();
                    if (!kind.equals(LexStore.ALIAS) && !kind.equals(LexStore.VERSION)
                            && path.startsWith(botId + "/" + LexStore.DRAFT + "/")) {
                        continue;
                    }
                    store.put(target, kind, path, record.deepCopy());
                }
            }
        }
    }

    // ================================================================ tags

    public ObjectNode tagResource(ObjectNode in, String region) {
        String arn = in.path("resourceARN").asText();
        Tagged tagged = tagged(arn);
        LexStore.checkTags(in.get("tags"));
        LexStore.tags(tagged.record()).setAll((ObjectNode) in.get("tags"));
        store.put(tagged.region(), tagged.kind(), tagged.path(), tagged.record());
        return mapper.createObjectNode();
    }

    public ObjectNode untagResource(ObjectNode in, String region) {
        String arn = in.path("resourceARN").asText();
        Tagged tagged = tagged(arn);
        ObjectNode tags = LexStore.tags(tagged.record());
        in.path("tagKeys").forEach(key -> tags.remove(key.asText()));
        store.put(tagged.region(), tagged.kind(), tagged.path(), tagged.record());
        return mapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(ObjectNode in, String region) {
        Tagged tagged = tagged(in.path("resourceARN").asText());
        ObjectNode out = mapper.createObjectNode();
        out.set("tags", LexStore.tags(tagged.record()).deepCopy());
        return out;
    }

    private record Tagged(String region, String kind, String path, ObjectNode record) {
    }

    private Tagged tagged(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (RuntimeException e) {
            throw LexStore.validation("The resource ARN " + arn + " is not valid.");
        }
        if (!LexStore.SERVICE.equals(parsed.service())) {
            throw LexStore.validation("The resource ARN " + arn + " is not a Lex ARN.");
        }
        String[] parts = parsed.resource().split("/");
        String kind;
        String path;
        if (parts.length == 2 && "bot".equals(parts[0])) {
            kind = LexStore.BOT;
            path = parts[1];
        } else if (parts.length == 3 && "bot-alias".equals(parts[0])) {
            kind = LexStore.ALIAS;
            path = parts[1] + "/" + parts[2];
        } else if (parts.length == 2 && "test-set".equals(parts[0])) {
            kind = LexStore.TEST_SET;
            path = parts[1];
        } else {
            throw LexStore.validation("The resource ARN " + arn + " can't be tagged.");
        }
        String region = parsed.region();
        ObjectNode record = store.live(region, kind, path)
                .filter(r -> !LexStore.meta(r).has("deleteAt"))
                .orElseThrow(() -> LexStore.notFound("The resource " + arn + " does not exist."));
        return new Tagged(region, kind, path, record);
    }

    // ================================================================ resource policies

    public ObjectNode createResourcePolicy(ObjectNode in, String region) {
        String arn = in.path("resourceArn").asText();
        requirePolicyTarget(arn);
        if (store.get(region, LexStore.POLICY, arn).isPresent()) {
            throw LexStore.precondition("The resource " + arn + " already has a policy.");
        }
        ObjectNode document = parsePolicy(in.path("policy").asText());
        return savePolicy(region, arn, document, 1);
    }

    public ObjectNode describeResourcePolicy(ObjectNode in, String region) {
        String arn = in.path("resourceArn").asText();
        ObjectNode policy = store.get(region, LexStore.POLICY, arn)
                .orElseThrow(() -> LexStore.notFound("The resource " + arn + " has no policy."));
        ObjectNode out = mapper.createObjectNode();
        out.put("resourceArn", arn);
        out.put("policy", policy.path("policy").asText());
        out.put("revisionId", policy.path("revisionId").asText());
        return out;
    }

    public ObjectNode updateResourcePolicy(ObjectNode in, String region) {
        String arn = in.path("resourceArn").asText();
        ObjectNode policy = currentPolicy(region, arn, in);
        ObjectNode document = parsePolicy(in.path("policy").asText());
        return savePolicy(region, arn, document, policy.path("revisionId").asInt() + 1);
    }

    public ObjectNode deleteResourcePolicy(ObjectNode in, String region) {
        String arn = in.path("resourceArn").asText();
        ObjectNode policy = currentPolicy(region, arn, in);
        store.delete(region, LexStore.POLICY, arn);
        ObjectNode out = mapper.createObjectNode();
        out.put("resourceArn", arn);
        out.put("revisionId", policy.path("revisionId").asText());
        return out;
    }

    public ObjectNode createResourcePolicyStatement(ObjectNode in, String region) {
        String arn = in.path("resourceArn").asText();
        requirePolicyTarget(arn);
        Optional<ObjectNode> existing = store.get(region, LexStore.POLICY, arn);
        ObjectNode document;
        int revision;
        if (existing.isPresent()) {
            ObjectNode policy = currentPolicy(region, arn, in);
            document = parsePolicy(policy.path("policy").asText());
            revision = policy.path("revisionId").asInt() + 1;
        } else {
            if (in.hasNonNull("expectedRevisionId")) {
                throw LexStore.precondition("The resource " + arn + " has no policy.");
            }
            document = mapper.createObjectNode();
            document.put("Version", "2012-10-17");
            document.putArray("Statement");
            revision = 1;
        }
        String sid = in.path("statementId").asText();
        for (JsonNode statement : document.path("Statement")) {
            if (sid.equals(statement.path("Sid").asText())) {
                throw LexStore.conflict("The policy already has a statement with the ID " + sid + ".");
            }
        }
        ObjectNode statement = document.withArray("Statement").addObject();
        statement.put("Sid", sid);
        statement.put("Effect", in.path("effect").asText());
        ObjectNode principal = statement.putObject("Principal");
        for (JsonNode p : in.path("principal")) {
            if (p.hasNonNull("service")) {
                principal.withArray("Service").add(p.get("service").asText());
            }
            if (p.hasNonNull("arn")) {
                principal.withArray("AWS").add(p.get("arn").asText());
            }
        }
        ArrayNode actions = statement.putArray("Action");
        in.path("action").forEach(a -> actions.add(a.asText()));
        statement.put("Resource", arn);
        if (in.get("condition") instanceof ObjectNode condition && !condition.isEmpty()) {
            statement.set("Condition", condition.deepCopy());
        }
        return savePolicy(region, arn, document, revision);
    }

    public ObjectNode deleteResourcePolicyStatement(ObjectNode in, String region) {
        String arn = in.path("resourceArn").asText();
        ObjectNode policy = currentPolicy(region, arn, in);
        ObjectNode document = parsePolicy(policy.path("policy").asText());
        String sid = in.path("statementId").asText();
        ArrayNode statements = document.withArray("Statement");
        boolean removed = false;
        for (int i = statements.size() - 1; i >= 0; i--) {
            if (sid.equals(statements.get(i).path("Sid").asText())) {
                statements.remove(i);
                removed = true;
            }
        }
        if (!removed) {
            throw LexStore.notFound("The policy has no statement with the ID " + sid + ".");
        }
        return savePolicy(region, arn, document, policy.path("revisionId").asInt() + 1);
    }

    private ObjectNode currentPolicy(String region, String arn, JsonNode in) {
        ObjectNode policy = store.get(region, LexStore.POLICY, arn)
                .orElseThrow(() -> LexStore.notFound("The resource " + arn + " has no policy."));
        String expected = in.path("expectedRevisionId").asText(null);
        if (expected != null && !expected.equals(policy.path("revisionId").asText())) {
            throw LexStore.precondition("The policy's revision is " + policy.path("revisionId").asText()
                    + ", not " + expected + ".");
        }
        return policy;
    }

    private ObjectNode savePolicy(String region, String arn, ObjectNode document, int revision) {
        ObjectNode policy = store.record();
        policy.put("policy", document.toString());
        policy.put("revisionId", String.valueOf(revision));
        store.put(region, LexStore.POLICY, arn, policy);
        ObjectNode out = mapper.createObjectNode();
        out.put("resourceArn", arn);
        out.put("revisionId", String.valueOf(revision));
        return out;
    }

    private ObjectNode parsePolicy(String text) {
        try {
            JsonNode parsed = mapper.readTree(text);
            if (!(parsed instanceof ObjectNode document) || !document.path("Statement").isArray()) {
                throw LexStore.validation("The policy must be a JSON document with a Statement list.");
            }
            for (JsonNode statement : document.path("Statement")) {
                String effect = statement.path("Effect").asText();
                if (!"Allow".equals(effect) && !"Deny".equals(effect)) {
                    throw LexStore.validation("Each policy statement needs an Effect of Allow or Deny.");
                }
            }
            return document;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw LexStore.validation("The policy is not valid JSON.");
        }
    }

    private void requirePolicyTarget(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (RuntimeException e) {
            throw LexStore.validation("The resource ARN " + arn + " is not valid.");
        }
        String[] parts = parsed.resource().split("/");
        boolean exists = (parts.length == 2 && "bot".equals(parts[0])
                && store.live(parsed.region(), LexStore.BOT, parts[1]).isPresent())
                || (parts.length == 3 && "bot-alias".equals(parts[0])
                && store.live(parsed.region(), LexStore.ALIAS, parts[1] + "/" + parts[2]).isPresent());
        if (!exists) {
            throw LexStore.notFound("The resource " + arn + " does not exist.");
        }
    }

    // ================================================================ helpers

    static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            JsonNode value = from.get(field);
            if (value != null && !value.isNull()) {
                to.set(field, value.deepCopy());
            }
        }
    }
}
