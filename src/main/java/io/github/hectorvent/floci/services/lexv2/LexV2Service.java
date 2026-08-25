package io.github.hectorvent.floci.services.lexv2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lexv2.model.Bot;
import io.github.hectorvent.floci.services.lexv2.model.Bot.Alias;
import io.github.hectorvent.floci.services.lexv2.model.Bot.Intent;
import io.github.hectorvent.floci.services.lexv2.model.Bot.Locale;
import io.github.hectorvent.floci.services.lexv2.model.Bot.SlotType;
import io.github.hectorvent.floci.services.lexv2.model.Bot.Version;
import io.github.hectorvent.floci.services.lexv2.model.LexSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Amazon Lex Model Building V2 + Runtime V2. Bots, locales, intents, versions,
 * aliases, tags, sessions, and RecognizeText/RecognizeUtterance NLU used by
 * Alchemy {@code RecognizeText.test.ts}.
 */
@ApplicationScoped
public class LexV2Service implements TagHandler {

    private static final Logger LOG = Logger.getLogger(LexV2Service.class);

    static final String SERVICE = "lex";
    static final String DRAFT = "DRAFT";
    static final String FALLBACK_NAME = "FallbackIntent";
    static final String FALLBACK_SIGNATURE = "AMAZON.FallbackIntent";
    private static final String BOT_RESOURCE = "bot/";
    private static final String ALIAS_RESOURCE = "bot-alias/";
    private static final Map<String, String> LOCALE_NAMES = Map.of(
            "en_US", "English (US)",
            "en_GB", "English (UK)",
            "es_US", "Spanish (US)",
            "es_ES", "Spanish (Spain)",
            "fr_FR", "French",
            "de_DE", "German",
            "it_IT", "Italian",
            "ja_JP", "Japanese");

    private final StorageBackend<String, Bot> bots;
    private final StorageBackend<String, LexSession> sessions;
    private final RegionResolver regionResolver;
    private final Instance<LambdaService> lambdaService;
    private final ObjectMapper objectMapper;

    @Inject
    public LexV2Service(StorageFactory storageFactory, RegionResolver regionResolver,
                        Instance<LambdaService> lambdaService, ObjectMapper objectMapper) {
        this(storageFactory.create("lex", "lex-bots.json", new TypeReference<Map<String, Bot>>() {
              }),
                storageFactory.create("lex", "lex-sessions.json", new TypeReference<Map<String, LexSession>>() {
                }),
                regionResolver, lambdaService, objectMapper);
    }

    LexV2Service(StorageBackend<String, Bot> bots, StorageBackend<String, LexSession> sessions,
                 RegionResolver regionResolver, Instance<LambdaService> lambdaService,
                 ObjectMapper objectMapper) {
        this.bots = bots;
        this.sessions = sessions;
        this.regionResolver = regionResolver;
        this.lambdaService = lambdaService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    // --- Bots ---

    public synchronized ObjectNode createBot(String region, JsonNode request) {
        requireObject(request, "Request body");
        String botName = requireText(request, "botName");
        String roleArn = requireText(request, "roleArn");
        JsonNode privacy = request.get("dataPrivacy");
        if (privacy == null || !privacy.isObject()) {
            throw validation("dataPrivacy is required.");
        }
        boolean childDirected = booleanValue(privacy, "childDirected", false);
        int ttl = optionalInt(request, "idleSessionTTLInSeconds", 300, 60, 86_400);
        if (findBotByName(region, botName).isPresent()) {
            throw conflict("Bot with name " + botName + " already exists.");
        }
        String botId = newId();
        while (bots.get(storageKey(region, botId)).isPresent()) {
            botId = newId();
        }
        long now = nowEpoch();
        Bot bot = new Bot();
        bot.setBotId(botId);
        bot.setBotName(botName);
        bot.setDescription(optionalText(request, "description"));
        bot.setRoleArn(roleArn);
        bot.setChildDirected(childDirected);
        bot.setIdleSessionTTLInSeconds(ttl);
        bot.setBotStatus("Available");
        bot.setRegion(region);
        bot.setAccountId(regionResolver.getAccountId());
        bot.setCreationDateTime(now);
        bot.setLastUpdatedDateTime(now);
        bot.setTags(readTags(request.get("botTags")));
        bot.getLocales().put(DRAFT, new LinkedHashMap<>());
        bots.put(storageKey(region, botId), bot);
        return toBot(bot);
    }

    public ObjectNode describeBot(String region, String botId) {
        return toBot(requireBot(region, botId));
    }

    public synchronized ObjectNode updateBot(String region, String botId, JsonNode request) {
        requireObject(request, "Request body");
        Bot bot = requireBot(region, botId);
        String botName = requireText(request, "botName");
        Optional<Bot> clash = findBotByName(region, botName);
        if (clash.isPresent() && !botId.equals(clash.get().getBotId())) {
            throw conflict("Bot with name " + botName + " already exists.");
        }
        bot.setBotName(botName);
        bot.setRoleArn(requireText(request, "roleArn"));
        JsonNode privacy = request.get("dataPrivacy");
        if (privacy != null && privacy.isObject()) {
            bot.setChildDirected(booleanValue(privacy, "childDirected", bot.isChildDirected()));
        }
        bot.setIdleSessionTTLInSeconds(optionalInt(request, "idleSessionTTLInSeconds",
                bot.getIdleSessionTTLInSeconds(), 60, 86_400));
        if (request.has("description")) {
            bot.setDescription(optionalText(request, "description"));
        }
        bot.setLastUpdatedDateTime(nowEpoch());
        bots.put(storageKey(botRegion(bot, region), botId), bot);
        return toBot(bot);
    }

    public synchronized ObjectNode deleteBot(String region, String botId) {
        Bot bot = findBot(region, botId).orElseThrow(() -> precondition("Bot " + botId + " does not exist."));
        bots.delete(storageKey(botRegion(bot, region), botId));
        for (LexSession session : sessions.scan(key -> true)) {
            if (botId.equals(session.getBotId())) {
                sessions.delete(sessionKey(session));
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("botId", botId);
        response.put("botStatus", "Deleting");
        return response;
    }

    public ObjectNode listBots(String region, JsonNode request) {
        requireObject(request, "Request body");
        List<Bot> all = new ArrayList<>(bots.scan(key -> key.startsWith(region + "::")));
        all.sort(Comparator.comparing(Bot::getBotName, Comparator.nullsLast(String::compareTo)));
        JsonNode filters = request.get("filters");
        if (filters != null && filters.isArray()) {
            for (JsonNode filter : filters) {
                String name = text(filter, "name");
                String operator = text(filter, "operator");
                List<String> values = stringList(filter.get("values"));
                if ("BotName".equals(name) && !values.isEmpty()) {
                    all = all.stream().filter(bot -> matches(bot.getBotName(), operator, values)).toList();
                }
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("botSummaries");
        for (Bot bot : all) {
            ObjectNode summary = summaries.addObject();
            summary.put("botId", bot.getBotId());
            summary.put("botName", bot.getBotName());
            putOptional(summary, "description", bot.getDescription());
            summary.put("botStatus", bot.getBotStatus());
            summary.put("lastUpdatedDateTime", bot.getLastUpdatedDateTime());
        }
        return response;
    }

    // --- Locales ---

    public synchronized ObjectNode createBotLocale(String region, String botId, String botVersion, JsonNode request) {
        requireDraft(botVersion);
        requireObject(request, "Request body");
        Bot bot = requireBot(region, botId);
        String localeId = requireText(request, "localeId");
        Map<String, Locale> draft = draftLocales(bot);
        if (draft.containsKey(localeId)) {
            throw conflict("Locale " + localeId + " already exists.");
        }
        long now = nowEpoch();
        Locale locale = new Locale();
        locale.setLocaleId(localeId);
        locale.setLocaleName(LOCALE_NAMES.getOrDefault(localeId, localeId));
        locale.setDescription(optionalText(request, "description"));
        locale.setNluIntentConfidenceThreshold(optionalDouble(request, "nluIntentConfidenceThreshold", 0.4));
        applyVoice(locale, request.get("voiceSettings"));
        locale.setBotLocaleStatus("NotBuilt");
        locale.setCreationDateTime(now);
        locale.setLastUpdatedDateTime(now);
        locale.getIntents().put(FALLBACK_NAME, fallbackIntent(now));
        draft.put(localeId, locale);
        persist(bot, region);
        return toLocale(bot, DRAFT, locale);
    }

    public ObjectNode describeBotLocale(String region, String botId, String botVersion, String localeId) {
        return toLocale(requireBot(region, botId), botVersion, requireLocale(requireBot(region, botId), botVersion, localeId));
    }

    public synchronized ObjectNode updateBotLocale(String region, String botId, String botVersion,
                                                   String localeId, JsonNode request) {
        requireDraft(botVersion);
        requireObject(request, "Request body");
        Bot bot = requireBot(region, botId);
        Locale locale = requireLocale(bot, DRAFT, localeId);
        if (request.has("description")) {
            locale.setDescription(optionalText(request, "description"));
        }
        locale.setNluIntentConfidenceThreshold(optionalDouble(request, "nluIntentConfidenceThreshold",
                locale.getNluIntentConfidenceThreshold()));
        if (request.has("voiceSettings")) {
            applyVoice(locale, request.get("voiceSettings"));
        }
        locale.setLastUpdatedDateTime(nowEpoch());
        persist(bot, region);
        return toLocale(bot, DRAFT, locale);
    }

    public synchronized ObjectNode deleteBotLocale(String region, String botId, String botVersion, String localeId) {
        requireDraft(botVersion);
        Bot bot = findBot(region, botId).orElseThrow(() -> precondition("Bot " + botId + " does not exist."));
        Map<String, Locale> draft = draftLocales(bot);
        if (draft.remove(localeId) == null) {
            throw precondition("Locale " + localeId + " does not exist.");
        }
        persist(bot, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("botId", botId);
        response.put("botVersion", DRAFT);
        response.put("localeId", localeId);
        response.put("botLocaleStatus", "Deleting");
        return response;
    }

    public synchronized ObjectNode buildBotLocale(String region, String botId, String botVersion, String localeId) {
        requireDraft(botVersion);
        Bot bot = requireBot(region, botId);
        Locale locale = requireLocale(bot, DRAFT, localeId);
        locale.setBotLocaleStatus("Built");
        locale.setLastUpdatedDateTime(nowEpoch());
        persist(bot, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("botId", botId);
        response.put("botVersion", DRAFT);
        response.put("localeId", localeId);
        response.put("botLocaleStatus", "Built");
        return response;
    }

    // --- Intents ---

    public synchronized ObjectNode createIntent(String region, String botId, String botVersion,
                                                String localeId, JsonNode request) {
        requireDraft(botVersion);
        requireObject(request, "Request body");
        Bot bot = requireBot(region, botId);
        Locale locale = requireLocale(bot, DRAFT, localeId);
        String intentName = requireText(request, "intentName");
        if (findIntentByName(locale, intentName).isPresent()) {
            throw conflict("Intent " + intentName + " already exists.");
        }
        long now = nowEpoch();
        Intent intent = new Intent();
        intent.setIntentId(newId());
        applyIntent(intent, request);
        intent.setCreationDateTime(now);
        intent.setLastUpdatedDateTime(now);
        locale.getIntents().put(intent.getIntentId(), intent);
        persist(bot, region);
        return toIntent(bot, DRAFT, localeId, intent);
    }

    public ObjectNode describeIntent(String region, String botId, String botVersion, String localeId, String intentId) {
        Bot bot = requireBot(region, botId);
        Locale locale = requireLocale(bot, botVersion, localeId);
        return toIntent(bot, botVersion, localeId, requireIntent(locale, intentId));
    }

    public ObjectNode listIntents(String region, String botId, String botVersion, String localeId, JsonNode request) {
        Bot bot = requireBot(region, botId);
        Locale locale = requireLocale(bot, botVersion, localeId);
        List<Intent> intents = new ArrayList<>(locale.getIntents().values());
        JsonNode filters = request == null ? null : request.get("filters");
        if (filters != null && filters.isArray()) {
            for (JsonNode filter : filters) {
                String name = text(filter, "name");
                String operator = text(filter, "operator");
                List<String> values = stringList(filter.get("values"));
                if ("IntentName".equals(name) && !values.isEmpty()) {
                    intents = intents.stream()
                            .filter(intent -> matches(intent.getIntentName(), operator, values))
                            .toList();
                }
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("intentSummaries");
        for (Intent intent : intents) {
            ObjectNode summary = summaries.addObject();
            summary.put("intentId", intent.getIntentId());
            summary.put("intentName", intent.getIntentName());
            putOptional(summary, "description", intent.getDescription());
            putOptional(summary, "parentIntentSignature", intent.getParentIntentSignature());
            summary.put("lastUpdatedDateTime", intent.getLastUpdatedDateTime());
        }
        return response;
    }

    public synchronized ObjectNode updateIntent(String region, String botId, String botVersion,
                                                String localeId, String intentId, JsonNode request) {
        requireDraft(botVersion);
        requireObject(request, "Request body");
        Bot bot = requireBot(region, botId);
        Locale locale = requireLocale(bot, DRAFT, localeId);
        Intent intent = requireIntent(locale, intentId);
        applyIntent(intent, request);
        intent.setLastUpdatedDateTime(nowEpoch());
        persist(bot, region);
        return toIntent(bot, DRAFT, localeId, intent);
    }

    public synchronized void deleteIntent(String region, String botId, String botVersion,
                                          String localeId, String intentId) {
        requireDraft(botVersion);
        Bot bot = findBot(region, botId).orElseThrow(() -> precondition("Bot " + botId + " does not exist."));
        Locale locale = draftLocales(bot).get(localeId);
        if (locale == null || locale.getIntents().remove(intentId) == null) {
            throw precondition("Intent " + intentId + " does not exist.");
        }
        persist(bot, region);
    }

    // --- Slot types ---

    public synchronized ObjectNode createSlotType(String region, String botId, String botVersion,
                                                  String localeId, JsonNode request) {
        requireDraft(botVersion);
        requireObject(request, "Request body");
        Bot bot = requireBot(region, botId);
        Locale locale = requireLocale(bot, DRAFT, localeId);
        String slotTypeName = requireText(request, "slotTypeName");
        if (findSlotTypeByName(locale, slotTypeName).isPresent()) {
            throw conflict("Slot type " + slotTypeName + " already exists.");
        }
        long now = nowEpoch();
        SlotType slotType = new SlotType();
        slotType.setSlotTypeId(newId());
        applySlotType(slotType, request);
        slotType.setCreationDateTime(now);
        slotType.setLastUpdatedDateTime(now);
        locale.getSlotTypes().put(slotType.getSlotTypeId(), slotType);
        persist(bot, region);
        return toSlotType(bot, DRAFT, localeId, slotType);
    }

    public ObjectNode describeSlotType(String region, String botId, String botVersion, String localeId,
                                       String slotTypeId) {
        Bot bot = requireBot(region, botId);
        Locale locale = requireLocale(bot, botVersion, localeId);
        return toSlotType(bot, botVersion, localeId, requireSlotType(locale, slotTypeId));
    }

    public ObjectNode listSlotTypes(String region, String botId, String botVersion, String localeId, JsonNode request) {
        Bot bot = requireBot(region, botId);
        Locale locale = requireLocale(bot, botVersion, localeId);
        List<SlotType> types = new ArrayList<>(locale.getSlotTypes().values());
        JsonNode filters = request == null ? null : request.get("filters");
        if (filters != null && filters.isArray()) {
            for (JsonNode filter : filters) {
                String name = text(filter, "name");
                String operator = text(filter, "operator");
                List<String> values = stringList(filter.get("values"));
                if ("SlotTypeName".equals(name) && !values.isEmpty()) {
                    types = types.stream()
                            .filter(slotType -> matches(slotType.getSlotTypeName(), operator, values))
                            .toList();
                }
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("slotTypeSummaries");
        for (SlotType slotType : types) {
            ObjectNode summary = summaries.addObject();
            summary.put("slotTypeId", slotType.getSlotTypeId());
            summary.put("slotTypeName", slotType.getSlotTypeName());
            putOptional(summary, "description", slotType.getDescription());
            summary.put("lastUpdatedDateTime", slotType.getLastUpdatedDateTime());
        }
        return response;
    }

    public synchronized ObjectNode updateSlotType(String region, String botId, String botVersion,
                                                  String localeId, String slotTypeId, JsonNode request) {
        requireDraft(botVersion);
        requireObject(request, "Request body");
        Bot bot = requireBot(region, botId);
        Locale locale = requireLocale(bot, DRAFT, localeId);
        SlotType slotType = requireSlotType(locale, slotTypeId);
        applySlotType(slotType, request);
        slotType.setLastUpdatedDateTime(nowEpoch());
        persist(bot, region);
        return toSlotType(bot, DRAFT, localeId, slotType);
    }

    public synchronized void deleteSlotType(String region, String botId, String botVersion,
                                            String localeId, String slotTypeId) {
        requireDraft(botVersion);
        Bot bot = findBot(region, botId).orElseThrow(() -> precondition("Bot " + botId + " does not exist."));
        Locale locale = draftLocales(bot).get(localeId);
        if (locale == null || locale.getSlotTypes().remove(slotTypeId) == null) {
            throw precondition("Slot type " + slotTypeId + " does not exist.");
        }
        persist(bot, region);
    }

    // --- Versions ---

    public synchronized ObjectNode createBotVersion(String region, String botId, JsonNode request) {
        requireObject(request, "Request body");
        Bot bot = requireBot(region, botId);
        JsonNode spec = request.get("botVersionLocaleSpecification");
        if (spec == null || !spec.isObject()) {
            throw validation("botVersionLocaleSpecification is required.");
        }
        String versionId = String.valueOf(bot.getNextVersion());
        bot.setNextVersion(bot.getNextVersion() + 1);
        Map<String, Locale> snapshot = new LinkedHashMap<>();
        spec.fields().forEachRemaining(entry -> {
            Locale source = draftLocales(bot).get(entry.getKey());
            if (source == null) {
                throw notFound("Locale " + entry.getKey() + " does not exist.");
            }
            snapshot.put(entry.getKey(), source.copy());
        });
        bot.getLocales().put(versionId, snapshot);
        Version version = new Version();
        version.setBotVersion(versionId);
        version.setDescription(optionalText(request, "description"));
        version.setBotStatus("Available");
        version.setCreationDateTime(nowEpoch());
        bot.getVersions().put(versionId, version);
        persist(bot, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("botId", botId);
        response.put("botVersion", versionId);
        putOptional(response, "description", version.getDescription());
        response.put("botStatus", "Available");
        response.put("creationDateTime", version.getCreationDateTime());
        if (spec.isObject()) {
            response.set("botVersionLocaleSpecification", spec);
        }
        return response;
    }

    public ObjectNode describeBotVersion(String region, String botId, String botVersion) {
        Bot bot = requireBot(region, botId);
        Version version = bot.getVersions().get(botVersion);
        if (version == null) {
            throw notFound("Bot version " + botVersion + " does not exist.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("botId", botId);
        response.put("botName", bot.getBotName());
        response.put("botVersion", version.getBotVersion());
        putOptional(response, "description", version.getDescription());
        response.put("roleArn", bot.getRoleArn());
        response.putObject("dataPrivacy").put("childDirected", bot.isChildDirected());
        response.put("idleSessionTTLInSeconds", bot.getIdleSessionTTLInSeconds());
        response.put("botStatus", version.getBotStatus());
        response.put("creationDateTime", version.getCreationDateTime());
        return response;
    }

    public synchronized ObjectNode deleteBotVersion(String region, String botId, String botVersion) {
        Bot bot = findBot(region, botId).orElseThrow(() -> precondition("Bot " + botId + " does not exist."));
        if (DRAFT.equals(botVersion) || bot.getVersions().remove(botVersion) == null) {
            throw precondition("Bot version " + botVersion + " does not exist.");
        }
        bot.getLocales().remove(botVersion);
        persist(bot, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("botId", botId);
        response.put("botVersion", botVersion);
        response.put("botStatus", "Deleting");
        return response;
    }

    // --- Aliases ---

    public synchronized ObjectNode createBotAlias(String region, String botId, JsonNode request) {
        requireObject(request, "Request body");
        Bot bot = requireBot(region, botId);
        String name = requireText(request, "botAliasName");
        if (findAliasByName(bot, name).isPresent()) {
            throw conflict("Bot alias " + name + " already exists.");
        }
        long now = nowEpoch();
        Alias alias = new Alias();
        alias.setBotAliasId(newId());
        alias.setBotAliasName(name);
        alias.setDescription(optionalText(request, "description"));
        alias.setBotVersion(optionalText(request, "botVersion"));
        alias.setBotAliasStatus("Available");
        alias.setBotAliasLocaleSettings(readMap(request.get("botAliasLocaleSettings")));
        alias.setTags(readTags(request.get("tags")));
        alias.setCreationDateTime(now);
        alias.setLastUpdatedDateTime(now);
        bot.getAliases().put(alias.getBotAliasId(), alias);
        persist(bot, region);
        return toAlias(bot, alias);
    }

    public ObjectNode describeBotAlias(String region, String botId, String botAliasId) {
        Bot bot = requireBot(region, botId);
        return toAlias(bot, requireAlias(bot, botAliasId));
    }

    public ObjectNode listBotAliases(String region, String botId, JsonNode request) {
        Bot bot = requireBot(region, botId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("botId", botId);
        ArrayNode summaries = response.putArray("botAliasSummaries");
        for (Alias alias : bot.getAliases().values()) {
            ObjectNode summary = summaries.addObject();
            summary.put("botAliasId", alias.getBotAliasId());
            summary.put("botAliasName", alias.getBotAliasName());
            putOptional(summary, "description", alias.getDescription());
            putOptional(summary, "botVersion", alias.getBotVersion());
            summary.put("botAliasStatus", alias.getBotAliasStatus());
            summary.put("creationDateTime", alias.getCreationDateTime());
            summary.put("lastUpdatedDateTime", alias.getLastUpdatedDateTime());
        }
        return response;
    }

    public synchronized ObjectNode updateBotAlias(String region, String botId, String botAliasId, JsonNode request) {
        requireObject(request, "Request body");
        Bot bot = requireBot(region, botId);
        Alias alias = requireAlias(bot, botAliasId);
        alias.setBotAliasName(requireText(request, "botAliasName"));
        if (request.has("description")) {
            alias.setDescription(optionalText(request, "description"));
        }
        if (request.has("botVersion")) {
            alias.setBotVersion(optionalText(request, "botVersion"));
        }
        if (request.has("botAliasLocaleSettings")) {
            alias.setBotAliasLocaleSettings(readMap(request.get("botAliasLocaleSettings")));
        }
        alias.setLastUpdatedDateTime(nowEpoch());
        persist(bot, region);
        return toAlias(bot, alias);
    }

    public synchronized ObjectNode deleteBotAlias(String region, String botId, String botAliasId) {
        Bot bot = findBot(region, botId).orElseThrow(() -> precondition("Bot " + botId + " does not exist."));
        if (bot.getAliases().remove(botAliasId) == null) {
            throw precondition("Bot alias " + botAliasId + " does not exist.");
        }
        persist(bot, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("botId", botId);
        response.put("botAliasId", botAliasId);
        response.put("botAliasStatus", "Deleting");
        return response;
    }

    // --- Runtime ---

    public ObjectNode recognizeText(String region, String botId, String botAliasId, String localeId,
                                    String sessionId, JsonNode request) {
        String text = request == null ? "" : optionalText(request, "text");
        if (text == null) {
            text = "";
        }
        JsonNode incomingState = request == null ? null : request.get("sessionState");
        return recognize(region, botId, botAliasId, localeId, sessionId, text, incomingState);
    }

    public ObjectNode recognize(String region, String botId, String botAliasId, String localeId,
                                String sessionId, String text, JsonNode incomingState) {
        Bot bot = requireBot(region, botId);
        Alias alias = requireAlias(bot, botAliasId);
        String version = alias.getBotVersion() == null || alias.getBotVersion().isBlank()
                ? DRAFT
                : alias.getBotVersion();
        Locale locale = requireLocale(bot, version, localeId);
        LexSession session = sessions.get(sessionKey(botId, botAliasId, localeId, sessionId))
                .orElseGet(() -> newSession(botId, botAliasId, localeId, sessionId));
        if (incomingState != null && incomingState.isObject()) {
            mergeSessionState(session, incomingState);
        }

        Match match = matchIntent(locale, text);
        Intent intent = match.intent;
        ObjectNode sessionState = objectMapper.createObjectNode();
        if (session.getSessionState() != null && !session.getSessionState().isEmpty()) {
            sessionState = objectMapper.valueToTree(session.getSessionState());
        }
        ObjectNode intentNode = sessionState.putObject("intent");
        intentNode.put("name", intent.getIntentName());
        intentNode.putObject("slots");
        intentNode.put("confirmationState", "None");
        String state = intent.isFulfillmentCodeHook() ? "ReadyForFulfillment" : "ReadyForFulfillment";
        intentNode.put("state", state);

        ArrayNode interpretations = objectMapper.createArrayNode();
        ObjectNode interpretation = interpretations.addObject();
        interpretation.putObject("nluConfidence").put("score", match.score);
        interpretation.set("intent", intentNode.deepCopy());
        interpretation.put("interpretationSource", "Lex");

        ArrayNode messages = objectMapper.createArrayNode();
        if (intent.isFulfillmentCodeHook()) {
            ObjectNode hookResponse = invokeFulfillment(bot, alias, localeId, sessionId, text,
                    sessionState, interpretations);
            if (hookResponse != null) {
                if (hookResponse.has("sessionState") && hookResponse.get("sessionState").isObject()) {
                    sessionState = (ObjectNode) hookResponse.get("sessionState");
                }
                if (hookResponse.has("messages") && hookResponse.get("messages").isArray()) {
                    messages = (ArrayNode) hookResponse.get("messages");
                }
            }
        }

        session.setSessionState(objectMapper.convertValue(sessionState, new TypeReference<>() {
        }));
        session.setMessages(objectMapper.convertValue(messages, new TypeReference<>() {
        }));
        sessions.put(sessionKey(session), session);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("messages", messages);
        response.set("sessionState", sessionState);
        response.set("interpretations", interpretations);
        response.put("sessionId", sessionId);
        ObjectNode member = response.putObject("recognizedBotMember");
        member.put("botId", botId);
        member.put("botName", bot.getBotName());
        return response;
    }

    public ObjectNode getSession(String region, String botId, String botAliasId, String localeId, String sessionId) {
        requireBot(region, botId);
        requireAlias(requireBot(region, botId), botAliasId);
        LexSession session = sessions.get(sessionKey(botId, botAliasId, localeId, sessionId))
                .orElseThrow(() -> notFound("Session " + sessionId + " does not exist."));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("sessionId", sessionId);
        response.set("sessionState", objectMapper.valueToTree(session.getSessionState()));
        response.set("messages", objectMapper.valueToTree(session.getMessages()));
        return response;
    }

    public ObjectNode putSession(String region, String botId, String botAliasId, String localeId,
                                 String sessionId, JsonNode request) {
        requireObject(request, "Request body");
        requireBot(region, botId);
        requireAlias(requireBot(region, botId), botAliasId);
        JsonNode state = request.get("sessionState");
        if (state == null || !state.isObject()) {
            throw validation("sessionState is required.");
        }
        LexSession session = sessions.get(sessionKey(botId, botAliasId, localeId, sessionId))
                .orElseGet(() -> newSession(botId, botAliasId, localeId, sessionId));
        session.setSessionState(objectMapper.convertValue(state, new TypeReference<>() {
        }));
        if (request.has("messages") && request.get("messages").isArray()) {
            session.setMessages(objectMapper.convertValue(request.get("messages"), new TypeReference<>() {
            }));
        }
        if (request.has("requestAttributes") && request.get("requestAttributes").isObject()) {
            session.setRequestAttributes(readStringMap(request.get("requestAttributes")));
        }
        sessions.put(sessionKey(session), session);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("sessionId", sessionId);
        return response;
    }

    public ObjectNode deleteSession(String region, String botId, String botAliasId, String localeId, String sessionId) {
        requireBot(region, botId);
        sessions.delete(sessionKey(botId, botAliasId, localeId, sessionId));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("botId", botId);
        response.put("botAliasId", botAliasId);
        response.put("localeId", localeId);
        response.put("sessionId", sessionId);
        return response;
    }

    // --- Tags ---

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return tagsOf(requireTagged(region, arn));
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = tagsOf(tagged);
        if (tags != null) {
            current.putAll(tags);
        }
        persistTagged(region, tagged, current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = tagsOf(tagged);
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        persistTagged(region, tagged, current);
    }

    // --- mapping ---

    private ObjectNode toBot(Bot bot) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("botId", bot.getBotId());
        node.put("botName", bot.getBotName());
        putOptional(node, "description", bot.getDescription());
        node.put("roleArn", bot.getRoleArn());
        node.putObject("dataPrivacy").put("childDirected", bot.isChildDirected());
        node.put("idleSessionTTLInSeconds", bot.getIdleSessionTTLInSeconds());
        node.put("botStatus", bot.getBotStatus());
        node.put("creationDateTime", bot.getCreationDateTime());
        node.put("lastUpdatedDateTime", bot.getLastUpdatedDateTime());
        if (bot.getTags() != null && !bot.getTags().isEmpty()) {
            ObjectNode tags = node.putObject("botTags");
            bot.getTags().forEach(tags::put);
        }
        return node;
    }

    private ObjectNode toLocale(Bot bot, String botVersion, Locale locale) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("botId", bot.getBotId());
        node.put("botVersion", botVersion);
        node.put("localeId", locale.getLocaleId());
        node.put("localeName", locale.getLocaleName());
        putOptional(node, "description", locale.getDescription());
        node.put("nluIntentConfidenceThreshold", locale.getNluIntentConfidenceThreshold());
        node.put("botLocaleStatus", locale.getBotLocaleStatus());
        node.put("creationDateTime", locale.getCreationDateTime());
        node.put("lastUpdatedDateTime", locale.getLastUpdatedDateTime());
        if (locale.getVoiceId() != null) {
            ObjectNode voice = node.putObject("voiceSettings");
            voice.put("voiceId", locale.getVoiceId());
            putOptional(voice, "engine", locale.getVoiceEngine());
        }
        return node;
    }

    private ObjectNode toIntent(Bot bot, String botVersion, String localeId, Intent intent) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("botId", bot.getBotId());
        node.put("botVersion", botVersion);
        node.put("localeId", localeId);
        node.put("intentId", intent.getIntentId());
        node.put("intentName", intent.getIntentName());
        putOptional(node, "description", intent.getDescription());
        putOptional(node, "parentIntentSignature", intent.getParentIntentSignature());
        ArrayNode utterances = node.putArray("sampleUtterances");
        for (String utterance : intent.getSampleUtterances()) {
            utterances.addObject().put("utterance", utterance);
        }
        node.putObject("dialogCodeHook").put("enabled", intent.isDialogCodeHook());
        node.putObject("fulfillmentCodeHook").put("enabled", intent.isFulfillmentCodeHook());
        node.put("creationDateTime", intent.getCreationDateTime());
        node.put("lastUpdatedDateTime", intent.getLastUpdatedDateTime());
        return node;
    }

    private ObjectNode toSlotType(Bot bot, String botVersion, String localeId, SlotType slotType) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("botId", bot.getBotId());
        node.put("botVersion", botVersion);
        node.put("localeId", localeId);
        node.put("slotTypeId", slotType.getSlotTypeId());
        node.put("slotTypeName", slotType.getSlotTypeName());
        putOptional(node, "description", slotType.getDescription());
        if (slotType.getSlotTypeValues() != null && !slotType.getSlotTypeValues().isEmpty()) {
            node.set("slotTypeValues", objectMapper.valueToTree(slotType.getSlotTypeValues()));
        }
        if (slotType.getValueSelectionSetting() != null && !slotType.getValueSelectionSetting().isEmpty()) {
            node.set("valueSelectionSetting", objectMapper.valueToTree(slotType.getValueSelectionSetting()));
        }
        putOptional(node, "parentSlotTypeSignature", slotType.getParentSlotTypeSignature());
        node.put("creationDateTime", slotType.getCreationDateTime());
        node.put("lastUpdatedDateTime", slotType.getLastUpdatedDateTime());
        return node;
    }

    private ObjectNode toAlias(Bot bot, Alias alias) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("botId", bot.getBotId());
        node.put("botAliasId", alias.getBotAliasId());
        node.put("botAliasName", alias.getBotAliasName());
        putOptional(node, "description", alias.getDescription());
        putOptional(node, "botVersion", alias.getBotVersion());
        node.put("botAliasStatus", alias.getBotAliasStatus());
        node.put("creationDateTime", alias.getCreationDateTime());
        node.put("lastUpdatedDateTime", alias.getLastUpdatedDateTime());
        if (alias.getBotAliasLocaleSettings() != null && !alias.getBotAliasLocaleSettings().isEmpty()) {
            node.set("botAliasLocaleSettings", objectMapper.valueToTree(alias.getBotAliasLocaleSettings()));
        }
        return node;
    }

    private void applyIntent(Intent intent, JsonNode request) {
        intent.setIntentName(requireText(request, "intentName"));
        if (request.has("description")) {
            intent.setDescription(optionalText(request, "description"));
        }
        intent.setParentIntentSignature(optionalText(request, "parentIntentSignature"));
        intent.setSampleUtterances(readUtterances(request.get("sampleUtterances")));
        intent.setDialogCodeHook(enabled(request.get("dialogCodeHook")));
        intent.setFulfillmentCodeHook(enabled(request.get("fulfillmentCodeHook")));
    }

    private void applySlotType(SlotType slotType, JsonNode request) {
        slotType.setSlotTypeName(requireText(request, "slotTypeName"));
        if (request.has("description")) {
            slotType.setDescription(optionalText(request, "description"));
        }
        slotType.setParentSlotTypeSignature(optionalText(request, "parentSlotTypeSignature"));
        if (request.has("slotTypeValues") && request.get("slotTypeValues").isArray()) {
            slotType.setSlotTypeValues(objectMapper.convertValue(request.get("slotTypeValues"),
                    new TypeReference<List<Map<String, Object>>>() {
                    }));
        }
        if (request.has("valueSelectionSetting") && request.get("valueSelectionSetting").isObject()) {
            slotType.setValueSelectionSetting(readMap(request.get("valueSelectionSetting")));
        }
    }

    private void applyVoice(Locale locale, JsonNode voice) {
        if (voice == null || voice.isNull()) {
            locale.setVoiceId(null);
            locale.setVoiceEngine(null);
            return;
        }
        if (!voice.isObject()) {
            throw validation("voiceSettings must be an object.");
        }
        locale.setVoiceId(optionalText(voice, "voiceId"));
        locale.setVoiceEngine(optionalText(voice, "engine"));
    }

    private Intent fallbackIntent(long now) {
        Intent intent = new Intent();
        intent.setIntentId(newId());
        intent.setIntentName(FALLBACK_NAME);
        intent.setParentIntentSignature(FALLBACK_SIGNATURE);
        intent.setCreationDateTime(now);
        intent.setLastUpdatedDateTime(now);
        return intent;
    }

    private Match matchIntent(Locale locale, String text) {
        String normalized = normalize(text);
        Intent fallback = null;
        Intent best = null;
        double bestScore = 0;
        for (Intent intent : locale.getIntents().values()) {
            if (FALLBACK_NAME.equals(intent.getIntentName())
                    || FALLBACK_SIGNATURE.equals(intent.getParentIntentSignature())) {
                fallback = intent;
                continue;
            }
            for (String utterance : intent.getSampleUtterances()) {
                String candidate = normalize(utterance);
                double score = 0;
                if (candidate.equals(normalized)) {
                    score = 1.0;
                } else if (!candidate.isEmpty() && (normalized.contains(candidate) || candidate.contains(normalized))) {
                    score = 0.7;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = intent;
                }
            }
        }
        double threshold = locale.getNluIntentConfidenceThreshold();
        if (best != null && bestScore >= threshold) {
            return new Match(best, bestScore);
        }
        if (fallback == null) {
            fallback = fallbackIntent(nowEpoch());
        }
        return new Match(fallback, 0);
    }

    private ObjectNode invokeFulfillment(Bot bot, Alias alias, String localeId, String sessionId,
                                         String text, ObjectNode sessionState, ArrayNode interpretations) {
        String lambdaArn = lambdaArn(alias, localeId);
        if (lambdaArn == null || lambdaService == null || !lambdaService.isResolvable()) {
            if (sessionState.has("intent") && sessionState.get("intent").isObject()) {
                ((ObjectNode) sessionState.get("intent")).put("state", "Fulfilled");
            }
            sessionState.putObject("dialogAction").put("type", "Close");
            return null;
        }
        try {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("messageVersion", "1.0");
            event.put("invocationSource", "FulfillmentCodeHook");
            event.put("inputMode", "Text");
            event.put("responseContentType", "text/plain; charset=utf-8");
            event.put("sessionId", sessionId);
            event.put("inputTranscript", text);
            ObjectNode botNode = event.putObject("bot");
            botNode.put("id", bot.getBotId());
            botNode.put("name", bot.getBotName());
            botNode.put("aliasId", alias.getBotAliasId());
            botNode.put("aliasName", alias.getBotAliasName());
            botNode.put("localeId", localeId);
            botNode.put("version", alias.getBotVersion() == null ? DRAFT : alias.getBotVersion());
            event.set("sessionState", sessionState);
            event.set("interpretations", interpretations);
            InvokeResult result = lambdaService.get().invoke(
                    bot.getRegion(), lambdaArn,
                    objectMapper.writeValueAsBytes(event), InvocationType.RequestResponse);
            if (result.getFunctionError() != null) {
                LOG.warnv("Lex fulfillment hook failed: {0}", result.getFunctionError());
                return null;
            }
            byte[] payload = result.getPayload();
            if (payload == null || payload.length == 0) {
                return null;
            }
            JsonNode parsed = objectMapper.readTree(payload);
            return parsed != null && parsed.isObject() ? (ObjectNode) parsed : null;
        } catch (Exception e) {
            LOG.warnv("Lex fulfillment hook invoke failed: {0}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String lambdaArn(Alias alias, String localeId) {
        Object settings = alias.getBotAliasLocaleSettings().get(localeId);
        if (!(settings instanceof Map<?, ?> map)) {
            return null;
        }
        Object spec = map.get("codeHookSpecification");
        if (!(spec instanceof Map<?, ?> specMap)) {
            return null;
        }
        Object hook = specMap.get("lambdaCodeHook");
        if (!(hook instanceof Map<?, ?> hookMap)) {
            return null;
        }
        Object arn = hookMap.get("lambdaARN");
        return arn == null ? null : arn.toString();
    }

    private void mergeSessionState(LexSession session, JsonNode incoming) {
        Map<String, Object> current = session.getSessionState();
        Map<String, Object> next = objectMapper.convertValue(incoming, new TypeReference<>() {
        });
        current.putAll(next);
        session.setSessionState(current);
    }

    private LexSession newSession(String botId, String botAliasId, String localeId, String sessionId) {
        LexSession session = new LexSession();
        session.setBotId(botId);
        session.setBotAliasId(botAliasId);
        session.setLocaleId(localeId);
        session.setSessionId(sessionId);
        return session;
    }

    private Bot requireBot(String region, String botId) {
        return findBot(region, botId).orElseThrow(() -> notFound("Bot " + botId + " does not exist."));
    }

    private Optional<Bot> findBot(String region, String botId) {
        Optional<Bot> exact = bots.get(storageKey(region, botId));
        if (exact.isPresent()) {
            return exact;
        }
        return bots.scan(key -> true).stream().filter(bot -> botId.equals(bot.getBotId())).findFirst();
    }

    private Optional<Bot> findBotByName(String region, String botName) {
        return bots.scan(key -> key.startsWith(region + "::")).stream()
                .filter(bot -> botName.equals(bot.getBotName()))
                .findFirst();
    }

    private Locale requireLocale(Bot bot, String botVersion, String localeId) {
        Map<String, Locale> locales = bot.getLocales().get(botVersion);
        if (locales == null) {
            throw notFound("Bot version " + botVersion + " does not exist.");
        }
        Locale locale = locales.get(localeId);
        if (locale == null) {
            throw notFound("Locale " + localeId + " does not exist.");
        }
        return locale;
    }

    private Map<String, Locale> draftLocales(Bot bot) {
        return bot.getLocales().computeIfAbsent(DRAFT, key -> new LinkedHashMap<>());
    }

    private Intent requireIntent(Locale locale, String intentId) {
        Intent intent = locale.getIntents().get(intentId);
        if (intent == null) {
            throw notFound("Intent " + intentId + " does not exist.");
        }
        return intent;
    }

    private Optional<Intent> findIntentByName(Locale locale, String intentName) {
        return locale.getIntents().values().stream()
                .filter(intent -> intentName.equals(intent.getIntentName()))
                .findFirst();
    }

    private SlotType requireSlotType(Locale locale, String slotTypeId) {
        SlotType slotType = locale.getSlotTypes().get(slotTypeId);
        if (slotType == null) {
            throw notFound("Slot type " + slotTypeId + " does not exist.");
        }
        return slotType;
    }

    private Optional<SlotType> findSlotTypeByName(Locale locale, String slotTypeName) {
        return locale.getSlotTypes().values().stream()
                .filter(slotType -> slotTypeName.equals(slotType.getSlotTypeName()))
                .findFirst();
    }

    private Alias requireAlias(Bot bot, String botAliasId) {
        Alias alias = bot.getAliases().get(botAliasId);
        if (alias == null) {
            throw notFound("Bot alias " + botAliasId + " does not exist.");
        }
        return alias;
    }

    private Optional<Alias> findAliasByName(Bot bot, String name) {
        return bot.getAliases().values().stream()
                .filter(alias -> name.equals(alias.getBotAliasName()))
                .findFirst();
    }

    private void persist(Bot bot, String region) {
        bots.put(storageKey(botRegion(bot, region), bot.getBotId()), bot);
    }

    private record Tagged(Bot bot, Alias alias) {
    }

    private Tagged requireTagged(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw notFound("Resource " + arn + " does not exist.");
        }
        if (!SERVICE.equals(parsed.service()) || parsed.resource() == null) {
            throw notFound("Resource " + arn + " does not exist.");
        }
        String resource = parsed.resource();
        String taggedRegion = parsed.region() == null || parsed.region().isBlank() ? region : parsed.region();
        if (resource.startsWith(BOT_RESOURCE) && !resource.startsWith(ALIAS_RESOURCE)) {
            String botId = resource.substring(BOT_RESOURCE.length());
            return new Tagged(requireBot(taggedRegion, botId), null);
        }
        if (resource.startsWith(ALIAS_RESOURCE)) {
            String rest = resource.substring(ALIAS_RESOURCE.length());
            int slash = rest.indexOf('/');
            if (slash <= 0 || slash == rest.length() - 1) {
                throw notFound("Resource " + arn + " does not exist.");
            }
            String botId = rest.substring(0, slash);
            String aliasId = rest.substring(slash + 1);
            Bot bot = requireBot(taggedRegion, botId);
            return new Tagged(bot, requireAlias(bot, aliasId));
        }
        throw notFound("Resource " + arn + " does not exist.");
    }

    private Map<String, String> tagsOf(Tagged tagged) {
        if (tagged.alias != null) {
            if (tagged.alias.getTags() == null) {
                tagged.alias.setTags(new LinkedHashMap<>());
            }
            return tagged.alias.getTags();
        }
        if (tagged.bot.getTags() == null) {
            tagged.bot.setTags(new LinkedHashMap<>());
        }
        return tagged.bot.getTags();
    }

    private void persistTagged(String region, Tagged tagged, Map<String, String> tags) {
        if (tagged.alias != null) {
            tagged.alias.setTags(tags);
        } else {
            tagged.bot.setTags(tags);
        }
        persist(tagged.bot, region);
    }

    private static void requireDraft(String botVersion) {
        if (!DRAFT.equals(botVersion)) {
            throw validation("Only the DRAFT bot version can be modified.");
        }
    }

    private static String botRegion(Bot bot, String fallback) {
        return bot.getRegion() == null || bot.getRegion().isBlank() ? fallback : bot.getRegion();
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static String sessionKey(LexSession session) {
        return sessionKey(session.getBotId(), session.getBotAliasId(), session.getLocaleId(), session.getSessionId());
    }

    private static String sessionKey(String botId, String botAliasId, String localeId, String sessionId) {
        return botId + "::" + botAliasId + "::" + localeId + "::" + sessionId;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(java.util.Locale.ROOT);
    }

    private static long nowEpoch() {
        return Instant.now().getEpochSecond();
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static boolean matches(String value, String operator, List<String> values) {
        if (value == null) {
            return false;
        }
        String op = operator == null ? "EQ" : operator;
        return switch (op) {
            case "CO" -> values.stream().anyMatch(value::contains);
            case "NE" -> values.stream().noneMatch(value::equals);
            default -> values.contains(value);
        };
    }

    private static boolean enabled(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return false;
        }
        return booleanValue(node, "enabled", false);
    }

    private static List<String> readUtterances(JsonNode node) {
        List<String> utterances = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return utterances;
        }
        for (JsonNode item : node) {
            if (item.isTextual()) {
                utterances.add(item.asText());
            } else if (item.isObject() && item.has("utterance")) {
                utterances.add(item.get("utterance").asText());
            }
        }
        return utterances;
    }

    private Map<String, Object> readMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(node, new TypeReference<>() {
        });
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return tags;
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && !entry.getValue().isNull()) {
                tags.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return tags;
    }

    private static Map<String, String> readStringMap(JsonNode node) {
        return readTags(node);
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (item != null && !item.isNull()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static void requireObject(JsonNode request, String label) {
        if (request == null || !request.isObject()) {
            throw validation(label + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        String value = optionalText(parent, field);
        if (value == null || value.isBlank()) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        String value = parent.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String text(JsonNode parent, String field) {
        return optionalText(parent, field);
    }

    private static boolean booleanValue(JsonNode parent, String field, boolean defaultValue) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return defaultValue;
        }
        JsonNode value = parent.get(field);
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        return Boolean.parseBoolean(value.asText());
    }

    private static int optionalInt(JsonNode parent, String field, int defaultValue, int min, int max) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return defaultValue;
        }
        JsonNode value = parent.get(field);
        int parsed;
        try {
            parsed = value.isNumber() ? value.intValue() : Integer.parseInt(value.asText());
        } catch (NumberFormatException e) {
            throw validation(field + " must be an integer.");
        }
        if (parsed < min || parsed > max) {
            throw validation(field + " must be between " + min + " and " + max + ".");
        }
        return parsed;
    }

    private static double optionalDouble(JsonNode parent, String field, double defaultValue) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return defaultValue;
        }
        JsonNode value = parent.get(field);
        try {
            return value.isNumber() ? value.doubleValue() : Double.parseDouble(value.asText());
        } catch (NumberFormatException e) {
            throw validation(field + " must be a number.");
        }
    }

    private static void putOptional(ObjectNode parent, String field, String value) {
        if (value != null) {
            parent.put(field, value);
        }
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException precondition(String message) {
        return new AwsException("PreconditionFailedException", message, 412);
    }

    private record Match(Intent intent, double score) {
    }
}
