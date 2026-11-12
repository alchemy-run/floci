package io.floci.aws.services.lexv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The parts of a DRAFT locale's model: intents with their sample utterances, prompts, code hooks and contexts, the
 * slots each intent elicits, custom, extended, grammar and composite slot types, and the custom vocabulary. Every change
 * leaves the locale needing a build. Built-in intents and slot types are listed from {@link LexCatalog}.
 */
@ApplicationScoped
public class LexIntents {

    private static final List<String> INTENT_FIELDS = List.of("intentName", "intentDisplayName", "description",
            "parentIntentSignature", "sampleUtterances", "dialogCodeHook", "fulfillmentCodeHook",
            "intentConfirmationSetting", "intentClosingSetting", "inputContexts", "outputContexts",
            "kendraConfiguration", "initialResponseSetting", "qnAIntentConfiguration", "qInConnectIntentConfiguration");
    private static final List<String> SLOT_FIELDS = List.of("slotName", "description", "slotTypeId",
            "valueElicitationSetting", "obfuscationSetting", "multipleValuesSetting", "subSlotSetting");
    private static final List<String> SLOT_TYPE_FIELDS = List.of("slotTypeName", "description", "slotTypeValues",
            "valueSelectionSetting", "parentSlotTypeSignature", "externalSourceSetting", "compositeSlotTypeSetting");

    private final LexStore store;
    private final LexBots bots;
    private final ObjectMapper mapper;

    @Inject
    public LexIntents(LexStore store, LexBots bots) {
        this.store = store;
        this.bots = bots;
        this.mapper = store.mapper();
    }

    private static String localePath(JsonNode in) {
        return in.path("botId").asText() + "/" + in.path("botVersion").asText() + "/" + in.path("localeId").asText();
    }

    // ================================================================ intents

    public ObjectNode createIntent(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        String localeId = in.path("localeId").asText();
        bots.draftLocale(region, botId, in.path("botVersion").asText(), localeId);
        String path = localePath(in);
        String name = in.path("intentName").asText();
        LexStore.checkName("intent", name);
        for (ObjectNode other : store.liveList(region, LexStore.INTENT, path + "/")) {
            if (name.equals(other.path("intentName").asText())) {
                throw LexStore.conflict("An intent with the name " + name + " already exists.");
            }
        }
        if (store.liveList(region, LexStore.INTENT, path + "/").size() >= store.config().maxIntentsPerLocale()) {
            throw LexStore.quota("The locale has the maximum number of intents.");
        }
        checkIntent(region, path, in, null);
        String intentId = LexStore.id();
        ObjectNode intent = store.record();
        intent.put("intentId", intentId);
        BotsCopy.fields(in, intent, INTENT_FIELDS);
        intent.put("botId", botId);
        intent.put("botVersion", LexStore.DRAFT);
        intent.put("localeId", localeId);
        double now = LexStore.epoch();
        intent.put("creationDateTime", now);
        intent.put("lastUpdatedDateTime", now);
        store.put(region, LexStore.INTENT, path + "/" + intentId, intent);
        bots.changed(region, botId, localeId);
        ObjectNode out = LexStore.view(intent);
        out.remove("lastUpdatedDateTime");
        return out;
    }

    private void checkIntent(String region, String path, JsonNode in, String intentId) {
        String signature = in.path("parentIntentSignature").asText(null);
        if (signature != null) {
            if (!LexCatalog.BUILT_IN_INTENTS.containsKey(signature)) {
                throw LexStore.validation("The parent intent signature " + signature + " is not a built-in intent.");
            }
            if (LexStore.FALLBACK_SIGNATURE.equals(signature) && !LexStore.FALLBACK_ID.equals(intentId)) {
                throw LexStore.conflict("The locale already has a fallback intent.");
            }
            if ("AMAZON.KendraSearchIntent".equals(signature) && !in.has("kendraConfiguration")) {
                throw LexStore.validation("AMAZON.KendraSearchIntent needs a kendraConfiguration.");
            }
            if ("AMAZON.QnAIntent".equals(signature) && !in.has("qnAIntentConfiguration")) {
                throw LexStore.validation("AMAZON.QnAIntent needs a qnAIntentConfiguration.");
            }
            if (!LexStore.FALLBACK_SIGNATURE.equals(signature) && !"AMAZON.KendraSearchIntent".equals(signature)
                    && !"AMAZON.QnAIntent".equals(signature) && !"AMAZON.BedrockAgentIntent".equals(signature)
                    && !"AMAZON.QInConnectIntent".equals(signature)) {
                for (ObjectNode other : store.liveList(region, LexStore.INTENT, path + "/")) {
                    if (signature.equals(other.path("parentIntentSignature").asText())
                            && !other.path("intentId").asText().equals(intentId)) {
                        throw LexStore.conflict("The locale already has an intent based on " + signature + ".");
                    }
                }
            }
        }
        Set<String> utterances = new HashSet<>();
        for (JsonNode utterance : in.path("sampleUtterances")) {
            String text = utterance.path("utterance").asText().trim().toLowerCase(Locale.ROOT);
            if (!utterances.add(text)) {
                throw LexStore.validation("The sample utterance \"" + utterance.path("utterance").asText()
                        + "\" is duplicated.");
            }
        }
        for (JsonNode context : in.path("outputContexts")) {
            LexStore.checkName("context", context.path("name").asText());
        }
        for (JsonNode context : in.path("inputContexts")) {
            LexStore.checkName("context", context.path("name").asText());
        }
    }

    public ObjectNode describeIntent(ObjectNode in, String region) {
        ObjectNode intent = intent(region, in);
        return LexStore.view(intent);
    }

    private ObjectNode intent(String region, JsonNode in) {
        String path = localePath(in);
        bots.locale(region, in.path("botId").asText(), in.path("botVersion").asText(), in.path("localeId").asText());
        String intentId = in.path("intentId").asText();
        return store.live(region, LexStore.INTENT, path + "/" + intentId)
                .orElseThrow(() -> LexStore.notFound("The intent " + intentId + " does not exist."));
    }

    private ObjectNode existingIntent(String region, JsonNode in) {
        String botId = in.path("botId").asText();
        bots.draftLocale(region, botId, in.path("botVersion").asText(), in.path("localeId").asText());
        String intentId = in.path("intentId").asText();
        return store.live(region, LexStore.INTENT, localePath(in) + "/" + intentId)
                .orElseThrow(() -> LexStore.precondition("Failed to retrieve resource since it does not exist: "
                        + "intent " + intentId + "."));
    }

    public ObjectNode updateIntent(ObjectNode in, String region) {
        ObjectNode intent = existingIntent(region, in);
        String intentId = intent.path("intentId").asText();
        String path = localePath(in);
        String name = in.path("intentName").asText();
        LexStore.checkName("intent", name);
        if (LexStore.FALLBACK_ID.equals(intentId)) {
            if (!LexStore.FALLBACK_SIGNATURE.equals(in.path("parentIntentSignature").asText(
                    LexStore.FALLBACK_SIGNATURE))) {
                throw LexStore.validation("The fallback intent's parent signature can't change.");
            }
            if (in.path("sampleUtterances").size() > 0) {
                throw LexStore.validation("The fallback intent can't have sample utterances.");
            }
        }
        for (ObjectNode other : store.liveList(region, LexStore.INTENT, path + "/")) {
            if (name.equals(other.path("intentName").asText()) && !intentId.equals(other.path("intentId").asText())) {
                throw LexStore.conflict("An intent with the name " + name + " already exists.");
            }
        }
        checkIntent(region, path, in, intentId);
        Set<String> slotIds = new HashSet<>();
        store.liveList(region, LexStore.SLOT, path + "/" + intentId + "/")
                .forEach(slot -> slotIds.add(slot.path("slotId").asText()));
        Set<Integer> priorities = new HashSet<>();
        for (JsonNode priority : in.path("slotPriorities")) {
            if (!slotIds.contains(priority.path("slotId").asText())) {
                throw LexStore.validation("The slot priority names slot " + priority.path("slotId").asText()
                        + ", which the intent does not have.");
            }
            if (!priorities.add(priority.path("priority").asInt())) {
                throw LexStore.validation("Each slot priority must be distinct.");
            }
        }
        for (String field : INTENT_FIELDS) {
            intent.remove(field);
        }
        BotsCopy.fields(in, intent, INTENT_FIELDS);
        if (LexStore.FALLBACK_ID.equals(intentId)) {
            intent.put("parentIntentSignature", LexStore.FALLBACK_SIGNATURE);
        }
        if (in.has("slotPriorities")) {
            intent.set("slotPriorities", in.get("slotPriorities").deepCopy());
        }
        intent.put("lastUpdatedDateTime", LexStore.epoch());
        store.put(region, LexStore.INTENT, path + "/" + intentId, intent);
        bots.changed(region, in.path("botId").asText(), in.path("localeId").asText());
        return LexStore.view(intent);
    }

    public ObjectNode deleteIntent(ObjectNode in, String region) {
        ObjectNode intent = existingIntent(region, in);
        String intentId = intent.path("intentId").asText();
        if (LexStore.FALLBACK_ID.equals(intentId)) {
            throw LexStore.validation("The fallback intent can't be deleted.");
        }
        String path = localePath(in);
        store.deleteAll(region, LexStore.SLOT, path + "/" + intentId + "/");
        store.delete(region, LexStore.INTENT, path + "/" + intentId);
        bots.changed(region, in.path("botId").asText(), in.path("localeId").asText());
        return mapper.createObjectNode();
    }

    public ObjectNode listIntents(ObjectNode in, String region) {
        bots.locale(region, in.path("botId").asText(), in.path("botVersion").asText(), in.path("localeId").asText());
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode intent : store.liveList(region, LexStore.INTENT, localePath(in) + "/")) {
            if (!LexStore.filtered(in.path("filters"), Map.of("IntentName", intent.path("intentName").asText()))) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            LexBots.copy(intent, summary, "intentId", "intentName", "intentDisplayName", "description",
                    "parentIntentSignature", "inputContexts", "outputContexts", "lastUpdatedDateTime");
            summaries.add(summary);
        }
        LexStore.sort(summaries, in.path("sortBy"), Map.of("IntentName", "intentName", "LastUpdatedDateTime",
                "lastUpdatedDateTime"), "intentName");
        ObjectNode out = header(in);
        LexStore.page(summaries, in, out, "intentSummaries", 10);
        return out;
    }

    private ObjectNode header(JsonNode in) {
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", in.path("botId").asText());
        out.put("botVersion", in.path("botVersion").asText());
        out.put("localeId", in.path("localeId").asText());
        return out;
    }

    // ================================================================ slots

    public ObjectNode createSlot(ObjectNode in, String region) {
        ObjectNode intent = existingIntent(region, in);
        String intentId = intent.path("intentId").asText();
        if (LexStore.FALLBACK_ID.equals(intentId)) {
            throw LexStore.validation("The fallback intent can't have slots.");
        }
        String path = localePath(in);
        String name = in.path("slotName").asText();
        LexStore.checkName("slot", name);
        String prefix = path + "/" + intentId + "/";
        for (ObjectNode other : store.liveList(region, LexStore.SLOT, prefix)) {
            if (name.equals(other.path("slotName").asText())) {
                throw LexStore.conflict("A slot with the name " + name + " already exists in the intent.");
            }
        }
        checkSlot(region, path, in);
        String slotId = LexStore.id();
        ObjectNode slot = store.record();
        slot.put("slotId", slotId);
        BotsCopy.fields(in, slot, SLOT_FIELDS);
        slot.put("botId", in.path("botId").asText());
        slot.put("botVersion", LexStore.DRAFT);
        slot.put("localeId", in.path("localeId").asText());
        slot.put("intentId", intentId);
        double now = LexStore.epoch();
        slot.put("creationDateTime", now);
        slot.put("lastUpdatedDateTime", now);
        store.put(region, LexStore.SLOT, prefix + slotId, slot);

        ArrayNode priorities = intent.withArray("slotPriorities");
        int next = 1;
        for (JsonNode priority : priorities) {
            next = Math.max(next, priority.path("priority").asInt() + 1);
        }
        priorities.addObject().put("priority", next).put("slotId", slotId);
        intent.put("lastUpdatedDateTime", now);
        store.put(region, LexStore.INTENT, path + "/" + intentId, intent);
        bots.changed(region, in.path("botId").asText(), in.path("localeId").asText());
        ObjectNode out = LexStore.view(slot);
        out.remove("lastUpdatedDateTime");
        return out;
    }

    private void checkSlot(String region, String path, JsonNode in) {
        String typeId = in.path("slotTypeId").asText(null);
        boolean composite = in.has("subSlotSetting");
        if (typeId == null && !composite) {
            throw LexStore.validation("A slot needs a slotTypeId.");
        }
        if (typeId != null && !typeId.startsWith("AMAZON.")) {
            ObjectNode type = store.live(region, LexStore.SLOT_TYPE, path + "/" + typeId)
                    .orElseThrow(() -> LexStore.precondition("The slot type " + typeId + " does not exist."));
            if (composite != type.has("compositeSlotTypeSetting")) {
                throw LexStore.validation(composite ? "A slot with sub-slots needs a composite slot type."
                        : "A composite slot type needs a slot with subSlotSetting.");
            }
        } else if (typeId != null && !LexCatalog.BUILT_IN_SLOT_TYPES.containsKey(typeId)) {
            throw LexStore.validation("The slot type " + typeId + " is not a built-in slot type.");
        }
        JsonNode setting = in.path("valueElicitationSetting");
        JsonNode prompt = setting.path("promptSpecification");
        if (prompt.isObject() && prompt.path("messageGroups").isEmpty()) {
            throw LexStore.validation("A slot prompt needs at least one message group.");
        }
        if (in.path("multipleValuesSetting").path("allowMultipleValues").asBoolean(false) && typeId != null
                && typeId.startsWith("AMAZON.") && !"AMAZON.AlphaNumeric".equals(typeId)) {
            throw LexStore.validation("Only custom slot types can take multiple values.");
        }
    }

    public ObjectNode describeSlot(ObjectNode in, String region) {
        intent(region, in);
        String slotId = in.path("slotId").asText();
        ObjectNode slot = store.live(region, LexStore.SLOT, localePath(in) + "/" + in.path("intentId").asText() + "/"
                + slotId).orElseThrow(() -> LexStore.notFound("The slot " + slotId + " does not exist."));
        return LexStore.view(slot);
    }

    public ObjectNode updateSlot(ObjectNode in, String region) {
        existingIntent(region, in);
        String path = localePath(in);
        String intentId = in.path("intentId").asText();
        String slotId = in.path("slotId").asText();
        String key = path + "/" + intentId + "/" + slotId;
        ObjectNode slot = store.live(region, LexStore.SLOT, key)
                .orElseThrow(() -> LexStore.precondition("Failed to retrieve resource since it does not exist: slot "
                        + slotId + "."));
        String name = in.path("slotName").asText();
        LexStore.checkName("slot", name);
        for (ObjectNode other : store.liveList(region, LexStore.SLOT, path + "/" + intentId + "/")) {
            if (name.equals(other.path("slotName").asText()) && !slotId.equals(other.path("slotId").asText())) {
                throw LexStore.conflict("A slot with the name " + name + " already exists in the intent.");
            }
        }
        checkSlot(region, path, in);
        for (String field : SLOT_FIELDS) {
            slot.remove(field);
        }
        BotsCopy.fields(in, slot, SLOT_FIELDS);
        slot.put("lastUpdatedDateTime", LexStore.epoch());
        store.put(region, LexStore.SLOT, key, slot);
        bots.changed(region, in.path("botId").asText(), in.path("localeId").asText());
        return LexStore.view(slot);
    }

    public ObjectNode deleteSlot(ObjectNode in, String region) {
        ObjectNode intent = existingIntent(region, in);
        String path = localePath(in);
        String intentId = intent.path("intentId").asText();
        String slotId = in.path("slotId").asText();
        String key = path + "/" + intentId + "/" + slotId;
        ObjectNode slot = store.live(region, LexStore.SLOT, key)
                .orElseThrow(() -> LexStore.precondition("Failed to retrieve resource since it does not exist: slot "
                        + slotId + "."));
        String reference = "{" + slot.path("slotName").asText() + "}";
        for (JsonNode utterance : intent.path("sampleUtterances")) {
            if (utterance.path("utterance").asText().contains(reference)) {
                throw LexStore.conflict("The slot " + slot.path("slotName").asText()
                        + " is used in the sample utterance \"" + utterance.path("utterance").asText() + "\".");
            }
        }
        store.delete(region, LexStore.SLOT, key);
        ArrayNode priorities = intent.withArray("slotPriorities");
        for (int i = priorities.size() - 1; i >= 0; i--) {
            if (slotId.equals(priorities.get(i).path("slotId").asText())) {
                priorities.remove(i);
            }
        }
        intent.put("lastUpdatedDateTime", LexStore.epoch());
        store.put(region, LexStore.INTENT, path + "/" + intentId, intent);
        bots.changed(region, in.path("botId").asText(), in.path("localeId").asText());
        return mapper.createObjectNode();
    }

    public ObjectNode listSlots(ObjectNode in, String region) {
        intent(region, in);
        List<ObjectNode> summaries = new ArrayList<>();
        String intentId = in.path("intentId").asText();
        for (ObjectNode slot : store.liveList(region, LexStore.SLOT, localePath(in) + "/" + intentId + "/")) {
            if (!LexStore.filtered(in.path("filters"), Map.of("SlotName", slot.path("slotName").asText()))) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            LexBots.copy(slot, summary, "slotId", "slotName", "description", "slotTypeId", "lastUpdatedDateTime");
            JsonNode setting = slot.path("valueElicitationSetting");
            summary.put("slotConstraint", setting.path("slotConstraint").asText("Optional"));
            if (setting.has("promptSpecification")) {
                summary.set("valueElicitationPromptSpecification", setting.get("promptSpecification").deepCopy());
            }
            summaries.add(summary);
        }
        LexStore.sort(summaries, in.path("sortBy"), Map.of("SlotName", "slotName", "LastUpdatedDateTime",
                "lastUpdatedDateTime"), "slotName");
        ObjectNode out = header(in);
        out.put("intentId", intentId);
        LexStore.page(summaries, in, out, "slotSummaries", 10);
        return out;
    }

    // ================================================================ slot types

    public ObjectNode createSlotType(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        String localeId = in.path("localeId").asText();
        bots.draftLocale(region, botId, in.path("botVersion").asText(), localeId);
        String path = localePath(in);
        String name = in.path("slotTypeName").asText();
        LexStore.checkName("slot type", name);
        for (ObjectNode other : store.liveList(region, LexStore.SLOT_TYPE, path + "/")) {
            if (name.equals(other.path("slotTypeName").asText())) {
                throw LexStore.conflict("A slot type with the name " + name + " already exists.");
            }
        }
        checkSlotType(region, path, in);
        String slotTypeId = LexStore.id();
        ObjectNode type = store.record();
        type.put("slotTypeId", slotTypeId);
        BotsCopy.fields(in, type, SLOT_TYPE_FIELDS);
        type.put("botId", botId);
        type.put("botVersion", LexStore.DRAFT);
        type.put("localeId", localeId);
        double now = LexStore.epoch();
        type.put("creationDateTime", now);
        type.put("lastUpdatedDateTime", now);
        store.put(region, LexStore.SLOT_TYPE, path + "/" + slotTypeId, type);
        bots.changed(region, botId, localeId);
        ObjectNode out = LexStore.view(type);
        out.remove("lastUpdatedDateTime");
        return out;
    }

    private void checkSlotType(String region, String path, JsonNode in) {
        String parent = in.path("parentSlotTypeSignature").asText(null);
        boolean composite = in.has("compositeSlotTypeSetting");
        boolean grammar = in.has("externalSourceSetting");
        if (parent != null && !"AMAZON.AlphaNumeric".equals(parent)) {
            throw LexStore.validation("Only AMAZON.AlphaNumeric can be extended.");
        }
        if ((composite ? 1 : 0) + (grammar ? 1 : 0) + (parent != null ? 1 : 0) > 1) {
            throw LexStore.validation("A slot type is custom, extended, composite or a grammar, not several.");
        }
        if (!composite && !grammar && !in.has("valueSelectionSetting")) {
            throw LexStore.validation("A slot type needs a valueSelectionSetting.");
        }
        if (parent != null && !in.path("valueSelectionSetting").has("regexFilter")
                && in.path("slotTypeValues").isEmpty()) {
            throw LexStore.validation("An extended AMAZON.AlphaNumeric slot type needs a regex filter or values.");
        }
        String regex = in.path("valueSelectionSetting").path("regexFilter").path("pattern").asText(null);
        if (regex != null) {
            try {
                Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                throw LexStore.validation("The regex filter " + regex + " is not valid.");
            }
        }
        Set<String> values = new HashSet<>();
        for (JsonNode value : in.path("slotTypeValues")) {
            String sample = value.path("sampleValue").path("value").asText();
            if (!values.add(sample.toLowerCase(Locale.ROOT))) {
                throw LexStore.validation("The slot type value " + sample + " is duplicated.");
            }
        }
        for (JsonNode sub : in.path("compositeSlotTypeSetting").path("subSlots")) {
            String typeId = sub.path("slotTypeId").asText();
            if (typeId.startsWith("AMAZON.") ? !LexCatalog.BUILT_IN_SLOT_TYPES.containsKey(typeId)
                    : store.live(region, LexStore.SLOT_TYPE, path + "/" + typeId).isEmpty()) {
                throw LexStore.validation("The sub-slot " + sub.path("name").asText() + " references slot type "
                        + typeId + ", which does not exist.");
            }
        }
    }

    public ObjectNode describeSlotType(ObjectNode in, String region) {
        bots.locale(region, in.path("botId").asText(), in.path("botVersion").asText(), in.path("localeId").asText());
        String slotTypeId = in.path("slotTypeId").asText();
        ObjectNode type = store.live(region, LexStore.SLOT_TYPE, localePath(in) + "/" + slotTypeId)
                .orElseThrow(() -> LexStore.notFound("The slot type " + slotTypeId + " does not exist."));
        return LexStore.view(type);
    }

    public ObjectNode updateSlotType(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        String localeId = in.path("localeId").asText();
        bots.draftLocale(region, botId, in.path("botVersion").asText(), localeId);
        String path = localePath(in);
        String slotTypeId = in.path("slotTypeId").asText();
        ObjectNode type = store.live(region, LexStore.SLOT_TYPE, path + "/" + slotTypeId)
                .orElseThrow(() -> LexStore.precondition("Failed to retrieve resource since it does not exist: "
                        + "slot type " + slotTypeId + "."));
        String name = in.path("slotTypeName").asText();
        LexStore.checkName("slot type", name);
        for (ObjectNode other : store.liveList(region, LexStore.SLOT_TYPE, path + "/")) {
            if (name.equals(other.path("slotTypeName").asText())
                    && !slotTypeId.equals(other.path("slotTypeId").asText())) {
                throw LexStore.conflict("A slot type with the name " + name + " already exists.");
            }
        }
        checkSlotType(region, path, in);
        for (String field : SLOT_TYPE_FIELDS) {
            type.remove(field);
        }
        BotsCopy.fields(in, type, SLOT_TYPE_FIELDS);
        type.put("lastUpdatedDateTime", LexStore.epoch());
        store.put(region, LexStore.SLOT_TYPE, path + "/" + slotTypeId, type);
        bots.changed(region, botId, localeId);
        return LexStore.view(type);
    }

    public ObjectNode deleteSlotType(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        String localeId = in.path("localeId").asText();
        bots.draftLocale(region, botId, in.path("botVersion").asText(), localeId);
        String path = localePath(in);
        String slotTypeId = in.path("slotTypeId").asText();
        store.live(region, LexStore.SLOT_TYPE, path + "/" + slotTypeId)
                .orElseThrow(() -> LexStore.precondition("Failed to retrieve resource since it does not exist: "
                        + "slot type " + slotTypeId + "."));
        if (!in.path("skipResourceInUseCheck").asBoolean(false)) {
            for (ObjectNode slot : store.liveList(region, LexStore.SLOT, path + "/")) {
                if (slotTypeId.equals(slot.path("slotTypeId").asText())) {
                    throw LexStore.precondition("The slot type " + slotTypeId + " is used by the slot "
                            + slot.path("slotName").asText() + ". Set skipResourceInUseCheck to delete it.");
                }
            }
            for (ObjectNode type : store.liveList(region, LexStore.SLOT_TYPE, path + "/")) {
                for (JsonNode sub : type.path("compositeSlotTypeSetting").path("subSlots")) {
                    if (slotTypeId.equals(sub.path("slotTypeId").asText())) {
                        throw LexStore.precondition("The slot type " + slotTypeId + " is used by the composite "
                                + "slot type " + type.path("slotTypeName").asText() + ".");
                    }
                }
            }
        }
        store.delete(region, LexStore.SLOT_TYPE, path + "/" + slotTypeId);
        bots.changed(region, botId, localeId);
        return mapper.createObjectNode();
    }

    public ObjectNode listSlotTypes(ObjectNode in, String region) {
        bots.locale(region, in.path("botId").asText(), in.path("botVersion").asText(), in.path("localeId").asText());
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode type : store.liveList(region, LexStore.SLOT_TYPE, localePath(in) + "/")) {
            String category = category(type);
            if (!LexStore.filtered(in.path("filters"), Map.of("SlotTypeName", type.path("slotTypeName").asText(),
                    "ExternalSourceType", "ExternalGrammar".equals(category) ? "S3" : ""))) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            LexBots.copy(type, summary, "slotTypeId", "slotTypeName", "description", "parentSlotTypeSignature",
                    "lastUpdatedDateTime");
            summary.put("slotTypeCategory", category);
            summaries.add(summary);
        }
        LexStore.sort(summaries, in.path("sortBy"), Map.of("SlotTypeName", "slotTypeName", "LastUpdatedDateTime",
                "lastUpdatedDateTime"), "slotTypeName");
        ObjectNode out = header(in);
        LexStore.page(summaries, in, out, "slotTypeSummaries", 10);
        return out;
    }

    static String category(JsonNode type) {
        if (type.has("compositeSlotTypeSetting")) {
            return "Composite";
        }
        if (type.has("externalSourceSetting")) {
            return "ExternalGrammar";
        }
        if (type.hasNonNull("parentSlotTypeSignature")) {
            return "Extended";
        }
        return "Custom";
    }

    // ================================================================ built-ins

    public ObjectNode listBuiltInIntents(ObjectNode in, String region) {
        String localeId = in.path("localeId").asText();
        LexCatalog.checkLocale(localeId);
        List<ObjectNode> summaries = new ArrayList<>();
        LexCatalog.BUILT_IN_INTENTS.forEach((signature, description) -> summaries.add(mapper.createObjectNode()
                .put("intentSignature", signature).put("description", description)));
        LexStore.sort(summaries, in.path("sortBy"), Map.of("IntentSignature", "intentSignature"), "intentSignature");
        ObjectNode out = mapper.createObjectNode();
        LexStore.page(summaries, in, out, "builtInIntentSummaries", 20);
        out.put("localeId", localeId);
        return out;
    }

    public ObjectNode listBuiltInSlotTypes(ObjectNode in, String region) {
        String localeId = in.path("localeId").asText();
        LexCatalog.checkLocale(localeId);
        List<ObjectNode> summaries = new ArrayList<>();
        LexCatalog.BUILT_IN_SLOT_TYPES.forEach((signature, description) -> summaries.add(mapper.createObjectNode()
                .put("slotTypeSignature", signature).put("description", description)));
        LexStore.sort(summaries, in.path("sortBy"), Map.of("SlotTypeSignature", "slotTypeSignature"),
                "slotTypeSignature");
        ObjectNode out = mapper.createObjectNode();
        LexStore.page(summaries, in, out, "builtInSlotTypeSummaries", 20);
        out.put("localeId", localeId);
        return out;
    }

    // ================================================================ custom vocabulary

    private ObjectNode vocabulary(String region, JsonNode in, boolean create) {
        String botId = in.path("botId").asText();
        String version = in.path("botVersion").asText();
        String localeId = in.path("localeId").asText();
        if (create) {
            bots.draftLocale(region, botId, version, localeId);
        } else {
            bots.locale(region, botId, version, localeId);
        }
        String path = localePath(in);
        return store.live(region, LexStore.VOCABULARY, path).orElseGet(() -> {
            if (!create) {
                throw LexStore.notFound("The locale " + localeId + " has no custom vocabulary.");
            }
            ObjectNode vocabulary = store.record();
            vocabulary.put("botId", botId);
            vocabulary.put("botVersion", version);
            vocabulary.put("localeId", localeId);
            vocabulary.put("customVocabularyStatus", "Ready");
            double now = LexStore.epoch();
            vocabulary.put("creationDateTime", now);
            vocabulary.put("lastUpdatedDateTime", now);
            vocabulary.putArray("items");
            return vocabulary;
        });
    }

    public ObjectNode batchCreateCustomVocabularyItem(ObjectNode in, String region) {
        ObjectNode vocabulary = vocabulary(region, in, true);
        ArrayNode items = vocabulary.withArray("items");
        ObjectNode out = header(in);
        ArrayNode errors = out.putArray("errors");
        ArrayNode created = out.putArray("resources");
        Set<String> seen = new HashSet<>();
        for (JsonNode item : in.path("customVocabularyItemList")) {
            String phrase = item.path("phrase").asText();
            String key = phrase.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                errors.addObject().put("itemId", phrase).put("errorMessage", "The phrase is duplicated.")
                        .put("errorCode", "DUPLICATE_INPUT");
                continue;
            }
            boolean exists = false;
            for (JsonNode existing : items) {
                exists |= key.equals(existing.path("phrase").asText().toLowerCase(Locale.ROOT));
            }
            if (exists) {
                errors.addObject().put("itemId", phrase).put("errorMessage", "The phrase already exists.")
                        .put("errorCode", "RESOURCE_ALREADY_EXISTS");
                continue;
            }
            ObjectNode entry = items.addObject();
            entry.put("itemId", LexStore.id());
            entry.put("phrase", phrase);
            if (item.hasNonNull("weight")) {
                entry.put("weight", item.get("weight").asInt());
            }
            if (item.hasNonNull("displayAs")) {
                entry.put("displayAs", item.get("displayAs").asText());
            }
            created.add(entry.deepCopy());
        }
        saveVocabulary(region, in, vocabulary);
        return out;
    }

    public ObjectNode batchUpdateCustomVocabularyItem(ObjectNode in, String region) {
        ObjectNode vocabulary = vocabulary(region, in, true);
        ArrayNode items = vocabulary.withArray("items");
        ObjectNode out = header(in);
        ArrayNode errors = out.putArray("errors");
        ArrayNode updated = out.putArray("resources");
        Set<String> seen = new HashSet<>();
        for (JsonNode item : in.path("customVocabularyItemList")) {
            String itemId = item.path("itemId").asText();
            if (!seen.add(itemId)) {
                errors.addObject().put("itemId", itemId).put("errorMessage", "The item is duplicated.")
                        .put("errorCode", "DUPLICATE_INPUT");
                continue;
            }
            ObjectNode found = null;
            for (JsonNode existing : items) {
                if (itemId.equals(existing.path("itemId").asText())) {
                    found = (ObjectNode) existing;
                }
            }
            if (found == null) {
                errors.addObject().put("itemId", itemId).put("errorMessage", "The item does not exist.")
                        .put("errorCode", "RESOURCE_DOES_NOT_EXIST");
                continue;
            }
            found.put("phrase", item.path("phrase").asText());
            found.remove(List.of("weight", "displayAs"));
            if (item.hasNonNull("weight")) {
                found.put("weight", item.get("weight").asInt());
            }
            if (item.hasNonNull("displayAs")) {
                found.put("displayAs", item.get("displayAs").asText());
            }
            updated.add(found.deepCopy());
        }
        saveVocabulary(region, in, vocabulary);
        return out;
    }

    public ObjectNode batchDeleteCustomVocabularyItem(ObjectNode in, String region) {
        ObjectNode vocabulary = vocabulary(region, in, true);
        ArrayNode items = vocabulary.withArray("items");
        ObjectNode out = header(in);
        ArrayNode errors = out.putArray("errors");
        ArrayNode deleted = out.putArray("resources");
        for (JsonNode item : in.path("customVocabularyItemList")) {
            String itemId = item.path("itemId").asText();
            int index = -1;
            for (int i = 0; i < items.size(); i++) {
                if (itemId.equals(items.get(i).path("itemId").asText())) {
                    index = i;
                }
            }
            if (index < 0) {
                errors.addObject().put("itemId", itemId).put("errorMessage", "The item does not exist.")
                        .put("errorCode", "RESOURCE_DOES_NOT_EXIST");
                continue;
            }
            deleted.add(items.remove(index));
        }
        saveVocabulary(region, in, vocabulary);
        return out;
    }

    private void saveVocabulary(String region, JsonNode in, ObjectNode vocabulary) {
        vocabulary.put("lastUpdatedDateTime", LexStore.epoch());
        store.put(region, LexStore.VOCABULARY, localePath(in), vocabulary);
        bots.changed(region, in.path("botId").asText(), in.path("localeId").asText());
    }

    public ObjectNode listCustomVocabularyItems(ObjectNode in, String region) {
        ObjectNode vocabulary = vocabulary(region, in, false);
        List<ObjectNode> items = new ArrayList<>();
        vocabulary.path("items").forEach(item -> items.add((ObjectNode) item));
        ObjectNode out = header(in);
        LexStore.page(items, in, out, "customVocabularyItems", 10);
        return out;
    }

    public ObjectNode describeCustomVocabularyMetadata(ObjectNode in, String region) {
        ObjectNode vocabulary = vocabulary(region, in, false);
        ObjectNode out = header(in);
        LexBots.copy(vocabulary, out, "customVocabularyStatus", "creationDateTime", "lastUpdatedDateTime");
        return out;
    }

    public ObjectNode deleteCustomVocabulary(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        bots.draftLocale(region, botId, in.path("botVersion").asText(), in.path("localeId").asText());
        String path = localePath(in);
        if (store.live(region, LexStore.VOCABULARY, path).isEmpty()) {
            throw LexStore.precondition("The locale has no custom vocabulary.");
        }
        store.delete(region, LexStore.VOCABULARY, path);
        bots.changed(region, botId, in.path("localeId").asText());
        ObjectNode out = header(in);
        out.put("customVocabularyStatus", "Deleting");
        return out;
    }

    /** Copies request members onto a record. */
    static final class BotsCopy {
        private BotsCopy() {
        }

        static void fields(JsonNode from, ObjectNode to, List<String> fields) {
            LexBots.copy(from, to, fields.toArray(String[]::new));
        }
    }
}
