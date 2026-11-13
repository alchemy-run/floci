package io.floci.aws.services.lexv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Natural-language understanding over a built model. An utterance that matches one of an intent's sample utterances,
 * with each {@code {Slot}} filled by words its slot type resolves, scores 1.0 and fills those slots. Otherwise an intent
 * scores by how closely the words resemble its utterances, and the slots its slot types recognize in the words are
 * filled. Intents whose input contexts aren't all active are not candidates. Built-in intents also match their usual
 * phrases.
 */
final class LexNlu {

    private static final int MAX_SPAN = 8;

    record Candidate(JsonNode intent, double score, ObjectNode slots) {
    }

    private LexNlu() {
    }

    /** Every intent but the fallback, with its score and the slots the words fill, best first. */
    static List<Candidate> classify(ObjectMapper mapper, JsonNode model, String text, Set<String> activeContexts) {
        List<String> words = words(text);
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode intent : model.path("intents")) {
            String signature = intent.path("parentIntentSignature").asText("");
            if (LexStore.FALLBACK_SIGNATURE.equals(signature) || generative(signature)) {
                continue;
            }
            boolean eligible = true;
            for (JsonNode context : intent.path("inputContexts")) {
                eligible &= activeContexts.contains(context.path("name").asText());
            }
            if (!eligible) {
                continue;
            }
            List<String> utterances = new ArrayList<>();
            intent.path("sampleUtterances").forEach(u -> utterances.add(u.path("utterance").asText()));
            utterances.addAll(LexCatalog.BUILT_IN_UTTERANCES.getOrDefault(signature, List.of()));
            List<JsonNode> slots = slotsOf(model, intent);
            Candidate best = new Candidate(intent, 0, mapper.createObjectNode());
            for (String utterance : utterances) {
                Optional<ObjectNode> exact = match(mapper, model, slots, template(utterance), 0, words, 0);
                if (exact.isPresent()) {
                    best = new Candidate(intent, 1.0, exact.get());
                    break;
                }
                double score = similarity(template(utterance), words);
                if (score > best.score()) {
                    best = new Candidate(intent, score, best.slots());
                }
            }
            if (best.score() < 1.0) {
                best = new Candidate(intent, round(best.score()), extract(mapper, model, slots, words));
            }
            candidates.add(best);
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        return candidates;
    }

    static boolean generative(String signature) {
        return "AMAZON.KendraSearchIntent".equals(signature) || "AMAZON.QnAIntent".equals(signature)
                || "AMAZON.BedrockAgentIntent".equals(signature) || "AMAZON.QInConnectIntent".equals(signature);
    }

    static List<JsonNode> slotsOf(JsonNode model, JsonNode intent) {
        List<JsonNode> slots = new ArrayList<>();
        String intentId = intent.path("intentId").asText();
        for (JsonNode slot : model.path("slots")) {
            if (intentId.equals(slot.path("intentId").asText())) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private static double round(double score) {
        return Math.round(score * 100) / 100.0;
    }

    /** The words of an utterance, as typed. */
    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String word : text.strip().split("\\s+")) {
            String trimmed = word.replaceAll("^[\"'(\\[]+|[\"')\\],.!?;]+$", "");
            if (!trimmed.isEmpty()) {
                words.add(trimmed);
            }
        }
        return words;
    }

    /** The tokens of a sample utterance: words, and {@code {Slot}} references kept whole. */
    static List<String> template(String utterance) {
        List<String> tokens = new ArrayList<>();
        for (String word : utterance.replace("{", " {").replace("}", "} ").strip().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            tokens.add(word.startsWith("{") && word.endsWith("}") ? word : word.replaceAll("[,.!?;]+$", ""));
        }
        return tokens;
    }

    private static Optional<ObjectNode> match(ObjectMapper mapper, JsonNode model, List<JsonNode> slots,
                                              List<String> template, int t, List<String> words, int w) {
        if (t == template.size()) {
            return w == words.size() ? Optional.of(mapper.createObjectNode()) : Optional.empty();
        }
        String token = template.get(t);
        if (token.startsWith("{") && token.endsWith("}")) {
            String name = token.substring(1, token.length() - 1);
            JsonNode slot = null;
            for (JsonNode s : slots) {
                if (name.equals(s.path("slotName").asText())) {
                    slot = s;
                }
            }
            if (slot == null) {
                return Optional.empty();
            }
            for (int end = Math.min(words.size(), w + MAX_SPAN); end > w; end--) {
                String span = String.join(" ", words.subList(w, end));
                Optional<ObjectNode> value = slotValue(mapper, model, slot, span);
                if (value.isEmpty()) {
                    continue;
                }
                Optional<ObjectNode> rest = match(mapper, model, slots, template, t + 1, words, end);
                if (rest.isPresent()) {
                    rest.get().set(name, value.get());
                    return rest;
                }
            }
            return Optional.empty();
        }
        if (w >= words.size() || !same(token, words.get(w))) {
            return Optional.empty();
        }
        return match(mapper, model, slots, template, t + 1, words, w + 1);
    }

    /** A slot filled from words: a scalar, a list when the slot takes multiple values, or empty. */
    static Optional<ObjectNode> slotValue(ObjectMapper mapper, JsonNode model, JsonNode slot, String text) {
        String typeId = slot.path("slotTypeId").asText(null);
        boolean multiple = slot.path("multipleValuesSetting").path("allowMultipleValues").asBoolean(false);
        if (multiple) {
            String[] parts = text.split("\\s*(?:,|\\band\\b|\\bor\\b)\\s*");
            if (parts.length > 1) {
                ObjectNode list = mapper.createObjectNode();
                list.put("shape", "List");
                List<String> interpreted = new ArrayList<>();
                for (String part : parts) {
                    if (part.isBlank()) {
                        continue;
                    }
                    Optional<ObjectNode> v = LexValues.resolve(mapper, model, typeId, part);
                    if (v.isEmpty()) {
                        return Optional.empty();
                    }
                    ObjectNode item = list.withArray("values").addObject();
                    item.put("shape", "Scalar");
                    item.set("value", v.get());
                    interpreted.add(v.get().path("interpretedValue").asText());
                }
                ObjectNode value = list.putObject("value");
                value.put("originalValue", text);
                value.put("interpretedValue", String.join(" ", interpreted));
                value.putArray("resolvedValues");
                return Optional.of(list);
            }
        }
        return LexValues.resolve(mapper, model, typeId, text).map(v -> {
            ObjectNode scalar = mapper.createObjectNode();
            scalar.put("shape", "Scalar");
            scalar.set("value", v);
            return scalar;
        });
    }

    /** Slots whose (closed) slot types recognize some of the words, longest span first. */
    private static ObjectNode extract(ObjectMapper mapper, JsonNode model, List<JsonNode> slots, List<String> words) {
        ObjectNode found = mapper.createObjectNode();
        Set<Integer> used = new HashSet<>();
        for (JsonNode slot : slots) {
            String typeId = slot.path("slotTypeId").asText("");
            if (typeId.startsWith("AMAZON.") && LexValues.open(typeId)) {
                continue;
            }
            boolean custom = !typeId.startsWith("AMAZON.");
            search:
            for (int length = Math.min(MAX_SPAN, words.size()); length >= 1; length--) {
                for (int start = 0; start + length <= words.size(); start++) {
                    boolean free = true;
                    for (int i = start; i < start + length; i++) {
                        free &= !used.contains(i);
                    }
                    if (!free) {
                        continue;
                    }
                    String span = String.join(" ", words.subList(start, start + length));
                    if (custom && !exactValue(model, typeId, span)) {
                        continue;
                    }
                    Optional<ObjectNode> value = slotValue(mapper, model, slot, span);
                    if (value.isPresent()) {
                        found.set(slot.path("slotName").asText(), value.get());
                        for (int i = start; i < start + length; i++) {
                            used.add(i);
                        }
                        break search;
                    }
                }
            }
        }
        return found;
    }

    private static boolean exactValue(JsonNode model, String typeId, String span) {
        String normalized = LexValues.normalize(span);
        for (JsonNode type : model.path("slotTypes")) {
            if (!typeId.equals(type.path("slotTypeId").asText())) {
                continue;
            }
            for (JsonNode value : type.path("slotTypeValues")) {
                if (LexValues.normalize(value.path("sampleValue").path("value").asText()).equals(normalized)) {
                    return true;
                }
                for (JsonNode synonym : value.path("synonyms")) {
                    if (LexValues.normalize(synonym.path("value").asText()).equals(normalized)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean same(String a, String b) {
        String x = stem(a);
        String y = stem(b);
        return x.equals(y);
    }

    private static String stem(String word) {
        String w = word.toLowerCase(Locale.ROOT).replace("’", "'");
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) {
            w = w.substring(0, w.length() - 1);
        }
        return w;
    }

    /** How alike a sample utterance and the words are, from 0 to just under 1. */
    private static double similarity(List<String> template, List<String> words) {
        List<String> plain = new ArrayList<>();
        for (String token : template) {
            if (!token.startsWith("{")) {
                plain.add(stem(token));
            }
        }
        List<String> said = new ArrayList<>();
        words.forEach(w -> said.add(stem(w)));
        if (plain.isEmpty() || said.isEmpty()) {
            return 0;
        }
        List<String> remaining = new ArrayList<>(said);
        int common = 0;
        for (String token : plain) {
            if (remaining.remove(token)) {
                common++;
            }
        }
        double slotsInTemplate = template.size() - plain.size();
        double dice = 2.0 * common / (plain.size() + Math.max(1, said.size() - slotsInTemplate));
        double chars = similarity(String.join(" ", plain), String.join(" ", said));
        return Math.min(0.99, Math.max(dice, 0.7 * chars));
    }

    /** One minus the edit distance between two strings over the longer length. */
    static double similarity(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return 1.0 - (double) previous[b.length()] / Math.max(a.length(), b.length());
    }
}
