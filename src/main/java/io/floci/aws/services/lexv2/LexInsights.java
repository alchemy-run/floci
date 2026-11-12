package io.floci.aws.services.lexv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * What Lex learns from a bot's conversations and descriptions. Analytics (sessions, intents, intent stages, intent
 * paths and utterances, as metrics or as data) and aggregated utterances are computed from the turns the runtime keeps.
 * Bot recommendations cluster the customer utterances of Lex-format transcripts in S3 into intents; resource generation
 * and element generation draft intents and sample utterances from a description or an intent; the bot analyzer reviews
 * a locale's design.
 */
@ApplicationScoped
public class LexInsights {

    private static final Set<String> STOP = Set.of("a", "an", "the", "i", "to", "my", "me", "you", "your", "is",
            "are", "am", "be", "it", "of", "for", "and", "or", "in", "on", "at", "with", "can", "could", "would",
            "please", "want", "like", "need", "do", "does", "how", "what", "i'd", "i'm", "we", "our",
            "this", "that", "some", "help", "hi", "hello", "hey", "there", "just", "get", "so", "about", "from",
            "id", "im");

    private final LexStore store;
    private final LexBots bots;
    private final Instance<S3Service> s3;
    private final ObjectMapper mapper;

    @Inject
    public LexInsights(LexStore store, LexBots bots, Instance<S3Service> s3) {
        this.store = store;
        this.bots = bots;
        this.s3 = s3;
        this.mapper = store.mapper();
    }

    // ================================================================ turns, sessions and intents

    private record Session(String key, List<JsonNode> turns) {
        long start() {
            return turns.get(0).path("timestamp").asLong();
        }

        long end() {
            return turns.get(turns.size() - 1).path("timestamp").asLong();
        }

        JsonNode first() {
            return turns.get(0);
        }

        String endState() {
            JsonNode last = turns.get(turns.size() - 1);
            String state = last.path("intentState").asText("");
            if ("Fulfilled".equals(state) || "ReadyForFulfillment".equals(state)) {
                return "Success";
            }
            return "Failed".equals(state) ? "Failure" : "Dropped";
        }

        String mode() {
            Set<String> modes = new HashSet<>();
            turns.forEach(t -> modes.add(t.path("inputMode").asText("Text")));
            return modes.size() > 1 ? "MultiMode" : modes.iterator().next();
        }
    }

    private record IntentRun(String name, int level, String endState, Session session, JsonNode first) {
    }

    private List<JsonNode> turns(String region, JsonNode in) {
        String botId = in.path("botId").asText();
        bots.bot(region, botId);
        double from = in.path("startDateTime").asDouble(0);
        double to = in.path("endDateTime").asDouble(Double.MAX_VALUE);
        if (in.has("startDateTime") && in.has("endDateTime") && to <= from) {
            throw LexStore.validation("endDateTime must be after startDateTime.");
        }
        List<JsonNode> turns = new ArrayList<>();
        for (ObjectNode turn : store.list(region, LexStore.TURN, botId + "/")) {
            double at = turn.path("timestamp").asLong() / 1000.0;
            if (at >= from && at <= to) {
                turns.add(turn);
            }
        }
        turns.sort(Comparator.comparingLong(t -> t.path("timestamp").asLong()));
        return turns;
    }

    private static List<Session> sessions(List<JsonNode> turns) {
        Map<String, List<JsonNode>> grouped = new LinkedHashMap<>();
        for (JsonNode turn : turns) {
            String key = turn.path("botAliasId").asText() + "/" + turn.path("localeId").asText() + "/"
                    + turn.path("sessionId").asText();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(turn);
        }
        List<Session> sessions = new ArrayList<>();
        grouped.forEach((key, list) -> sessions.add(new Session(key, list)));
        return sessions;
    }

    private static List<IntentRun> runs(Session session) {
        List<IntentRun> runs = new ArrayList<>();
        String current = null;
        JsonNode first = null;
        JsonNode last = null;
        for (JsonNode turn : session.turns()) {
            String name = turn.path("intent").asText("");
            if (name.isEmpty()) {
                continue;
            }
            boolean closed = last != null && Set.of("Fulfilled", "ReadyForFulfillment", "Failed")
                    .contains(last.path("intentState").asText());
            if (!name.equals(current) || closed) {
                if (current != null) {
                    runs.add(new IntentRun(current, runs.size() + 1, endOf(last, !closed), session, first));
                }
                current = name;
                first = turn;
            }
            last = turn;
        }
        if (current != null) {
            runs.add(new IntentRun(current, runs.size() + 1, endOf(last, false), session, first));
        }
        return runs;
    }

    private static String endOf(JsonNode last, boolean switched) {
        String state = last.path("intentState").asText("");
        if ("Fulfilled".equals(state) || "ReadyForFulfillment".equals(state)) {
            return "Success";
        }
        if ("Failed".equals(state)) {
            return "Failure";
        }
        return switched ? "Switched" : "Dropped";
    }

    /** Whether a value passes analytics filters ({@code EQ}, {@code GT}, {@code LT}). */
    private static boolean passes(JsonNode filters, Function<String, String> field) {
        for (JsonNode filter : filters) {
            String value = field.apply(filter.path("name").asText());
            if (value == null) {
                continue;
            }
            String operator = filter.path("operator").asText("EQ");
            boolean any = false;
            for (JsonNode wanted : filter.path("values")) {
                String w = wanted.asText();
                any |= switch (operator) {
                    case "GT" -> number(value) > number(w);
                    case "LT" -> number(value) < number(w);
                    default -> value.equalsIgnoreCase(w);
                };
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    private static double number(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String common(JsonNode turn, String name) {
        return switch (name) {
            case "BotAliasId" -> turn.path("botAliasId").asText();
            case "BotVersion" -> turn.path("botVersion").asText();
            case "LocaleId" -> turn.path("localeId").asText();
            case "Modality" -> turn.path("inputMode").asText();
            case "Channel" -> turn.path("channel").asText();
            case "SessionId" -> turn.path("sessionId").asText();
            case "OriginatingRequestId" -> turn.path("requestId").asText();
            default -> null;
        };
    }

    private static long bin(long millis, String interval) {
        long size = "OneHour".equals(interval) ? 3_600_000L : 86_400_000L;
        return millis - Math.floorMod(millis, size);
    }

    /** Groups items into result rows by their bin and group-by keys, in the order the request asks for. */
    private <T> ObjectNode metrics(JsonNode in, List<T> items, Function<T, Long> startTime,
                                   Function<T, Long> utteranceTime, Map<String, Function<T, String>> groupFields,
                                   Map<String, Function<List<T>, double[]>> metricValues, String groupKey,
                                   Function<List<T>, ObjectNode> attributes) {
        Map<String, List<T>> groups = new TreeMap<>();
        Map<String, ObjectNode> keys = new LinkedHashMap<>();
        for (T item : items) {
            ArrayNode binKeys = mapper.createArrayNode();
            for (JsonNode bin : in.path("binBy")) {
                String name = bin.path("name").asText();
                long time = "UtteranceTimestamp".equals(name) ? utteranceTime.apply(item) : startTime.apply(item);
                binKeys.addObject().put("name", name).put("value", bin(time, bin.path("interval").asText()));
            }
            ArrayNode groupBy = mapper.createArrayNode();
            for (JsonNode group : in.path("groupBy")) {
                String name = group.path("name").asText();
                Function<T, String> field = groupFields.get(name);
                groupBy.addObject().put("name", name).put("value", field == null ? "" : field.apply(item));
            }
            String key = binKeys + "|" + groupBy;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
            keys.computeIfAbsent(key, k -> {
                ObjectNode row = mapper.createObjectNode();
                row.set("binKeys", binKeys);
                row.set(groupKey, groupBy);
                return row;
            });
        }
        List<ObjectNode> rows = new ArrayList<>();
        groups.forEach((key, members) -> {
            ObjectNode row = keys.get(key).deepCopy();
            ArrayNode results = row.putArray("metricsResults");
            for (JsonNode metric : in.path("metrics")) {
                String name = metric.path("name").asText();
                String statistic = metric.path("statistic").asText("Sum");
                double[] values = metricValues.getOrDefault(name, l -> new double[0]).apply(members);
                double value = switch (statistic) {
                    case "Avg" -> values.length == 0 ? 0 : Arrays.stream(values).average().orElse(0);
                    case "Max" -> Arrays.stream(values).max().orElse(0);
                    default -> Arrays.stream(values).sum();
                };
                results.addObject().put("name", name).put("statistic", statistic).put("value", value);
            }
            if (attributes != null && in.path("attributes").size() > 0) {
                row.putArray("attributeResults").add(attributes.apply(members));
            }
            rows.add(row);
        });
        JsonNode ordering = null;
        int metricIndex = -1;
        for (int i = 0; i < in.path("metrics").size(); i++) {
            if (in.path("metrics").get(i).has("order")) {
                ordering = in.path("metrics").get(i);
                metricIndex = i;
                break;
            }
        }
        if (ordering != null) {
            int index = metricIndex;
            Comparator<ObjectNode> byMetric = Comparator.comparingDouble(r -> r.path("metricsResults").get(index)
                    .path("value").asDouble());
            rows.sort("Descending".equals(ordering.path("order").asText()) ? byMetric.reversed() : byMetric);
        } else if (in.path("binBy").size() > 0) {
            Comparator<ObjectNode> byBin = Comparator.comparingLong(r -> r.path("binKeys").get(0).path("value")
                    .asLong());
            rows.sort("Descending".equals(in.path("binBy").get(0).path("order").asText()) ? byBin.reversed()
                    : byBin);
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", in.path("botId").asText());
        LexStore.page(rows, in, out, "results", 100);
        return out;
    }

    private static <T> double[] ones(List<T> items, Function<T, Boolean> test) {
        double[] values = new double[items.size()];
        for (int i = 0; i < items.size(); i++) {
            values[i] = test.apply(items.get(i)) ? 1 : 0;
        }
        return values;
    }

    // ================================================================ metrics

    public ObjectNode listSessionMetrics(ObjectNode in, String region) {
        List<Session> sessions = new ArrayList<>();
        for (Session session : sessions(turns(region, in))) {
            if (sessionPasses(in.path("filters"), session)) {
                sessions.add(session);
            }
        }
        Map<String, Function<List<Session>, double[]>> metrics = new LinkedHashMap<>();
        metrics.put("Count", l -> ones(l, s -> true));
        metrics.put("Success", l -> ones(l, s -> "Success".equals(s.endState())));
        metrics.put("Failure", l -> ones(l, s -> "Failure".equals(s.endState())));
        metrics.put("Dropped", l -> ones(l, s -> "Dropped".equals(s.endState())));
        metrics.put("Duration", l -> l.stream().mapToDouble(s -> (s.end() - s.start()) / 1000.0).toArray());
        metrics.put("TurnsPerConversation", l -> l.stream().mapToDouble(s -> s.turns().size()).toArray());
        metrics.put("Concurrency", l -> new double[] {l.size()});
        return metrics(in, sessions, Session::start, Session::start, Map.of(
                "ConversationEndState", Session::endState,
                "LocaleId", s -> s.first().path("localeId").asText()), metrics, "groupByKeys", null);
    }

    private boolean sessionPasses(JsonNode filters, Session session) {
        List<String> path = new ArrayList<>();
        runs(session).forEach(r -> path.add(r.name()));
        return passes(filters, name -> switch (name) {
            case "Duration" -> String.valueOf((session.end() - session.start()) / 1000);
            case "ConversationEndState" -> session.endState();
            case "IntentPath" -> "/" + String.join("/", path);
            default -> common(session.first(), name);
        });
    }

    public ObjectNode listIntentMetrics(ObjectNode in, String region) {
        List<IntentRun> runs = new ArrayList<>();
        for (Session session : sessions(turns(region, in))) {
            for (IntentRun run : runs(session)) {
                if (passes(in.path("filters"), name -> switch (name) {
                    case "IntentName" -> run.name();
                    case "IntentEndState" -> run.endState();
                    default -> common(run.first(), name);
                })) {
                    runs.add(run);
                }
            }
        }
        Map<String, Function<List<IntentRun>, double[]>> metrics = new LinkedHashMap<>();
        metrics.put("Count", l -> ones(l, r -> true));
        for (String state : List.of("Success", "Failure", "Switched", "Dropped")) {
            metrics.put(state, l -> ones(l, r -> state.equals(r.endState())));
        }
        return metrics(in, runs, r -> r.session().start(), r -> r.first().path("timestamp").asLong(), Map.of(
                "IntentName", IntentRun::name,
                "IntentEndState", IntentRun::endState,
                "IntentLevel", r -> String.valueOf(r.level())), metrics, "groupByKeys", null);
    }

    private record Stage(String name, String outcome, String switchedTo, JsonNode turn, Session session) {
    }

    public ObjectNode listIntentStageMetrics(ObjectNode in, String region) {
        List<Stage> stages = new ArrayList<>();
        for (Session session : sessions(turns(region, in))) {
            List<JsonNode> turns = session.turns();
            for (int i = 0; i < turns.size(); i++) {
                JsonNode turn = turns.get(i);
                String action = turn.path("dialogAction").asText();
                String name;
                if ("ElicitSlot".equals(action)) {
                    name = turn.path("slotToElicit").asText();
                } else if ("ConfirmIntent".equals(action)) {
                    name = "Confirmation";
                } else {
                    continue;
                }
                String outcome;
                String switched = null;
                if (i + 1 >= turns.size()) {
                    outcome = "Dropped";
                } else {
                    JsonNode next = turns.get(i + 1);
                    if (!next.path("intent").asText().equals(turn.path("intent").asText())) {
                        outcome = "Failed";
                        switched = next.path("intent").asText();
                    } else if ("Failed".equals(next.path("intentState").asText())) {
                        outcome = "Failed";
                    } else if (action.equals(next.path("dialogAction").asText())
                            && turn.path("slotToElicit").asText().equals(next.path("slotToElicit").asText())) {
                        outcome = "Retry";
                    } else {
                        outcome = "Success";
                    }
                }
                Stage stage = new Stage(name, outcome, switched, turn, session);
                if (passes(in.path("filters"), filter -> switch (filter) {
                    case "IntentName" -> turn.path("intent").asText();
                    case "IntentStageName" -> stage.name();
                    default -> common(turn, filter);
                })) {
                    stages.add(stage);
                }
            }
        }
        Map<String, Function<List<Stage>, double[]>> metrics = new LinkedHashMap<>();
        metrics.put("Count", l -> ones(l, s -> true));
        for (String outcome : List.of("Success", "Failed", "Dropped", "Retry")) {
            metrics.put(outcome, l -> ones(l, s -> outcome.equals(s.outcome())));
        }
        return metrics(in, stages, s -> s.session().start(), s -> s.turn().path("timestamp").asLong(), Map.of(
                "IntentStageName", Stage::name,
                "SwitchedToIntent", s -> s.switchedTo() == null ? "" : s.switchedTo()), metrics, "groupByKeys",
                null);
    }

    public ObjectNode listUtteranceMetrics(ObjectNode in, String region) {
        List<JsonNode> turns = new ArrayList<>();
        for (JsonNode turn : turns(region, in)) {
            if (!turn.path("utterance").asText().isEmpty() && utterancePasses(in.path("filters"), turn)) {
                turns.add(turn);
            }
        }
        Map<String, Function<List<JsonNode>, double[]>> metrics = new LinkedHashMap<>();
        metrics.put("Count", l -> ones(l, t -> true));
        metrics.put("Missed", l -> ones(l, t -> t.path("missed").asBoolean()));
        metrics.put("Detected", l -> ones(l, t -> !t.path("missed").asBoolean()));
        metrics.put("UtteranceTimestamp", l -> l.stream().mapToDouble(t -> t.path("timestamp").asLong()).toArray());
        return metrics(in, turns, t -> t.path("timestamp").asLong(), t -> t.path("timestamp").asLong(), Map.of(
                "UtteranceText", t -> t.path("utterance").asText(),
                "UtteranceState", t -> t.path("missed").asBoolean() ? "Missed" : "Detected"), metrics,
                "groupByKeys", l -> {
                    ObjectNode attribute = mapper.createObjectNode();
                    String intent = l.get(l.size() - 1).path("intent").asText(null);
                    if (intent != null) {
                        attribute.put("lastUsedIntent", intent);
                    }
                    return attribute;
                });
    }

    private static boolean utterancePasses(JsonNode filters, JsonNode turn) {
        return passes(filters, name -> switch (name) {
            case "UtteranceState" -> turn.path("missed").asBoolean() ? "Missed" : "Detected";
            case "UtteranceText" -> turn.path("utterance").asText();
            default -> common(turn, name);
        });
    }

    public ObjectNode listIntentPaths(ObjectNode in, String region) {
        String path = in.path("intentPath").asText("/");
        List<String> prefix = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                prefix.add(part);
            }
        }
        Map<String, int[]> nodes = new LinkedHashMap<>();
        for (Session session : sessions(turns(region, in))) {
            if (!passes(in.path("filters"), name -> common(session.first(), name))) {
                continue;
            }
            List<String> names = new ArrayList<>();
            runs(session).forEach(r -> names.add(r.name()));
            if (names.size() <= prefix.size() || !names.subList(0, prefix.size()).equals(prefix)) {
                continue;
            }
            String next = names.get(prefix.size());
            int[] counts = nodes.computeIfAbsent(next, k -> new int[2]);
            counts[0]++;
            if (names.size() > prefix.size() + 1) {
                counts[1]++;
            }
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode summaries = out.putArray("nodeSummaries");
        String base = prefix.isEmpty() ? "" : "/" + String.join("/", prefix);
        nodes.forEach((name, counts) -> {
            ObjectNode node = summaries.addObject();
            node.put("intentName", name);
            node.put("intentPath", base + "/" + name);
            node.put("intentCount", counts[0]);
            node.put("intentLevel", prefix.size() + 1);
            node.put("nodeType", counts[1] > 0 ? "Inner" : "Exit");
        });
        return out;
    }

    // ================================================================ data

    public ObjectNode listSessionAnalyticsData(ObjectNode in, String region) {
        List<ObjectNode> specs = new ArrayList<>();
        for (Session session : sessions(turns(region, in))) {
            if (!sessionPasses(in.path("filters"), session)) {
                continue;
            }
            JsonNode first = session.first();
            ObjectNode spec = mapper.createObjectNode();
            spec.put("botAliasId", first.path("botAliasId").asText());
            spec.put("botVersion", first.path("botVersion").asText());
            spec.put("localeId", first.path("localeId").asText());
            spec.put("channel", first.path("channel").asText());
            spec.put("sessionId", first.path("sessionId").asText());
            spec.put("conversationStartTime", session.start() / 1000.0);
            spec.put("conversationEndTime", session.end() / 1000.0);
            spec.put("conversationDurationSeconds", (session.end() - session.start()) / 1000);
            spec.put("conversationEndState", session.endState());
            spec.put("mode", session.mode());
            spec.put("numberOfTurns", session.turns().size());
            ArrayNode samples = spec.putArray("invokedIntentSamples");
            Set<String> seen = new LinkedHashSet<>();
            runs(session).forEach(r -> seen.add(r.name()));
            seen.forEach(name -> samples.addObject().put("intentName", name));
            spec.put("originatingRequestId", first.path("requestId").asText());
            specs.add(spec);
        }
        JsonNode sortBy = in.path("sortBy");
        String field = switch (sortBy.path("name").asText("ConversationStartTime")) {
            case "NumberOfTurns" -> "numberOfTurns";
            case "Duration" -> "conversationDurationSeconds";
            default -> "conversationStartTime";
        };
        Comparator<ObjectNode> order = Comparator.comparingDouble(s -> s.path(field).asDouble());
        specs.sort("Descending".equals(sortBy.path("order").asText()) ? order.reversed() : order);
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", in.path("botId").asText());
        LexStore.page(specs, in, out, "sessions", 100);
        return out;
    }

    public ObjectNode listUtteranceAnalyticsData(ObjectNode in, String region) {
        List<ObjectNode> specs = new ArrayList<>();
        for (Session session : sessions(turns(region, in))) {
            for (JsonNode turn : session.turns()) {
                if (turn.path("utterance").asText().isEmpty() || !utterancePasses(in.path("filters"), turn)) {
                    continue;
                }
                ObjectNode spec = mapper.createObjectNode();
                spec.put("botAliasId", turn.path("botAliasId").asText());
                spec.put("botVersion", turn.path("botVersion").asText());
                spec.put("localeId", turn.path("localeId").asText());
                spec.put("sessionId", turn.path("sessionId").asText());
                spec.put("channel", turn.path("channel").asText());
                spec.put("mode", turn.path("inputMode").asText());
                spec.put("conversationStartTime", session.start() / 1000.0);
                spec.put("conversationEndTime", session.end() / 1000.0);
                spec.put("utterance", turn.path("utterance").asText());
                spec.put("utteranceTimestamp", turn.path("timestamp").asLong() / 1000.0);
                spec.put("utteranceUnderstood", !turn.path("missed").asBoolean());
                spec.put("inputType", "Speech".equals(turn.path("inputMode").asText()) ? "audio/l16" : "text/plain");
                spec.put("outputType", "text/plain; charset=utf-8");
                if (turn.hasNonNull("intent")) {
                    spec.put("associatedIntentName", turn.get("intent").asText());
                }
                if (turn.hasNonNull("slotToElicit")) {
                    spec.put("associatedSlotName", turn.get("slotToElicit").asText());
                }
                if (turn.hasNonNull("intentState")) {
                    spec.put("intentState", turn.get("intentState").asText());
                }
                spec.put("dialogActionType", turn.path("dialogAction").asText());
                if (turn.path("slots").isObject() && !turn.path("slots").isEmpty()) {
                    spec.put("slotsFilledInSession", String.join(",", iterable(turn.path("slots").fieldNames())));
                }
                spec.put("utteranceRequestId", turn.path("requestId").asText().replace("-", "")
                        .substring(0, 10).toUpperCase(Locale.ROOT));
                ArrayNode responses = spec.putArray("botResponses");
                for (JsonNode message : turn.path("botResponses")) {
                    ObjectNode response = responses.addObject();
                    LexBots.copy(message, response, "content", "contentType", "imageResponseCard");
                }
                specs.add(spec);
            }
        }
        Comparator<ObjectNode> order = Comparator.comparingDouble(s -> s.path("utteranceTimestamp").asDouble());
        specs.sort("Descending".equals(in.path("sortBy").path("order").asText()) ? order.reversed() : order);
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", in.path("botId").asText());
        LexStore.page(specs, in, out, "utterances", 100);
        return out;
    }

    private static List<String> iterable(Iterator<String> names) {
        List<String> list = new ArrayList<>();
        names.forEachRemaining(list::add);
        return list;
    }

    public ObjectNode listAggregatedUtterances(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        bots.bot(region, botId);
        boolean alias = in.hasNonNull("botAliasId");
        boolean version = in.hasNonNull("botVersion");
        if (alias == version) {
            throw LexStore.validation("Give either botAliasId or botVersion.");
        }
        JsonNode duration = in.path("aggregationDuration").path("relativeAggregationDuration");
        String dimension = duration.path("timeDimension").asText();
        int amount = duration.path("timeValue").asInt();
        Set<Integer> allowed = switch (dimension) {
            case "Hours" -> Set.of(1, 3, 6, 12, 24);
            case "Days" -> Set.of(1, 3, 7);
            default -> Set.of(1, 2);
        };
        if (!allowed.contains(amount)) {
            throw LexStore.validation("A " + dimension + " aggregation duration can be " + allowed + ".");
        }
        long span = switch (dimension) {
            case "Hours" -> 3_600_000L;
            case "Days" -> 86_400_000L;
            default -> 604_800_000L;
        } * amount;
        long now = LexStore.millis();
        long from = now - span;
        String localeId = in.path("localeId").asText();
        Map<String, ObjectNode> summaries = new LinkedHashMap<>();
        for (ObjectNode turn : store.list(region, LexStore.TURN, botId + "/")) {
            long at = turn.path("timestamp").asLong();
            if (at < from || !localeId.equals(turn.path("localeId").asText())
                    || (alias && !in.get("botAliasId").asText().equals(turn.path("botAliasId").asText()))
                    || (version && !in.get("botVersion").asText().equals(turn.path("botVersion").asText()))) {
                continue;
            }
            String utterance = turn.path("utterance").asText();
            if (utterance.isEmpty()) {
                continue;
            }
            String key = utterance.toLowerCase(Locale.ROOT).trim();
            ObjectNode summary = summaries.computeIfAbsent(key, k -> {
                ObjectNode s = mapper.createObjectNode();
                s.put("utterance", utterance);
                s.put("hitCount", 0);
                s.put("missedCount", 0);
                s.put("utteranceFirstRecordedInAggregationDuration", at / 1000.0);
                s.put("containsDataFromDeletedResources", false);
                return s;
            });
            String counter = turn.path("missed").asBoolean() ? "missedCount" : "hitCount";
            summary.put(counter, summary.path(counter).asInt() + 1);
            summary.put("utteranceLastRecordedInAggregationDuration", at / 1000.0);
        }
        List<ObjectNode> list = new ArrayList<>();
        for (ObjectNode summary : summaries.values()) {
            if (LexStore.filtered(in.path("filters"), Map.of("Utterance", summary.path("utterance").asText()))) {
                list.add(summary);
            }
        }
        JsonNode sortBy = in.path("sortBy");
        String field = "MissedCount".equals(sortBy.path("attribute").asText()) ? "missedCount" : "hitCount";
        Comparator<ObjectNode> order = Comparator.comparingInt(s -> s.path(field).asInt());
        list.sort("Ascending".equals(sortBy.path("order").asText()) ? order : order.reversed());
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", botId);
        LexBots.copy(in, out, "botAliasId", "botVersion", "localeId", "aggregationDuration");
        out.put("aggregationWindowStartTime", from / 1000.0);
        out.put("aggregationWindowEndTime", now / 1000.0);
        out.put("aggregationLastRefreshedDateTime", now / 1000.0);
        LexStore.page(list, in, out, "aggregatedUtterancesSummaries", 100);
        return out;
    }

    public ObjectNode deleteUtterances(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        String localeId = in.path("localeId").asText(null);
        String sessionId = in.path("sessionId").asText(null);
        for (ObjectNode turn : store.list(region, LexStore.TURN, botId + "/")) {
            if ((localeId == null || localeId.equals(turn.path("localeId").asText()))
                    && (sessionId == null || sessionId.equals(turn.path("sessionId").asText()))) {
                store.delete(region, LexStore.TURN, turn.path("_").path("path").asText());
            }
        }
        return mapper.createObjectNode();
    }

    // ================================================================ bot recommendations

    public ObjectNode startBotRecommendation(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        String localeId = in.path("localeId").asText();
        bots.draftLocale(region, botId, in.path("botVersion").asText(), localeId);
        String prefix = botId + "/" + LexStore.DRAFT + "/" + localeId + "/";
        for (ObjectNode other : store.liveList(region, LexStore.RECOMMENDATION, prefix)) {
            if (Set.of("Processing", "Downloading", "Updating").contains(other.path("botRecommendationStatus")
                    .asText())) {
                throw LexStore.conflict("The locale has a bot recommendation in progress.");
            }
        }
        JsonNode source = in.path("transcriptSourceSetting").path("s3BucketTranscriptSource");
        List<String> transcripts = transcripts(source);
        List<ObjectNode> intents = cluster(transcripts);
        String recommendationId = LexStore.id();
        ObjectNode record = store.record();
        record.put("botId", botId);
        record.put("botVersion", LexStore.DRAFT);
        record.put("localeId", localeId);
        record.put("botRecommendationId", recommendationId);
        double now = LexStore.epoch();
        record.put("creationDateTime", now);
        record.put("lastUpdatedDateTime", now);
        LexBots.copy(in, record, "transcriptSourceSetting", "encryptionSetting");
        if (transcripts.isEmpty()) {
            LexStore.meta(record).putArray("failureReasons").add("No Lex-format transcripts were found in s3://"
                    + source.path("s3BucketName").asText() + ".");
            store.settle(record, "botRecommendationStatus", "Processing", "Failed");
        } else {
            store.settle(record, "botRecommendationStatus", "Processing", "Available");
        }
        LexStore.meta(record).set("intents", mapper.valueToTree(intents));
        LexStore.meta(record).set("transcripts", mapper.valueToTree(transcripts));
        store.put(region, LexStore.RECOMMENDATION, prefix + recommendationId, record);
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(record, out, "botId", "botVersion", "localeId", "botRecommendationStatus",
                "botRecommendationId", "creationDateTime", "transcriptSourceSetting", "encryptionSetting");
        return out;
    }

    /** The Lex-format transcripts (JSON with a {@code Transcript} list) under the source's prefixes and dates. */
    private List<String> transcripts(JsonNode source) {
        String bucket = source.path("s3BucketName").asText();
        List<String> prefixes = new ArrayList<>();
        source.path("pathFormat").path("objectPrefixes").forEach(p -> prefixes.add(p.asText()));
        if (prefixes.isEmpty()) {
            prefixes.add("");
        }
        JsonNode range = source.path("transcriptFilter").path("lexTranscriptFilter").path("dateRangeFilter");
        double from = range.path("startDateTime").asDouble(0);
        double to = range.path("endDateTime").asDouble(Double.MAX_VALUE);
        List<String> found = new ArrayList<>();
        S3Service service = s3.get();
        for (String prefix : prefixes) {
            List<S3Object> objects;
            try {
                objects = service.listObjects(bucket, prefix, null, 1000);
            } catch (AwsException e) {
                throw LexStore.notFound("The transcript bucket " + bucket + " can't be read: " + e.getMessage());
            }
            for (S3Object object : objects) {
                if (!object.getKey().endsWith(".json")) {
                    continue;
                }
                double modified = object.getLastModified() == null ? 0 : object.getLastModified().toEpochMilli()
                        / 1000.0;
                if (modified < from || modified > to) {
                    continue;
                }
                String text = new String(service.getObject(bucket, object.getKey()).getData(),
                        StandardCharsets.UTF_8);
                try {
                    if (mapper.readTree(text).path("Transcript").isArray()) {
                        found.add(text);
                    }
                } catch (IOException e) {
                    // not a transcript
                }
            }
        }
        return found;
    }

    /** Groups transcripts by what each customer first asks: one recommended intent per group of alike requests. */
    private List<ObjectNode> cluster(List<String> transcripts) {
        List<Set<String>> keys = new ArrayList<>();
        List<ObjectNode> intents = new ArrayList<>();
        for (int t = 0; t < transcripts.size(); t++) {
            JsonNode transcript;
            try {
                transcript = mapper.readTree(transcripts.get(t));
            } catch (IOException e) {
                continue;
            }
            Set<String> agents = new HashSet<>();
            for (JsonNode participant : transcript.path("Participants")) {
                if ("AGENT".equalsIgnoreCase(participant.path("ParticipantRole").asText())) {
                    agents.add(participant.path("ParticipantId").asText());
                }
            }
            String first = null;
            for (JsonNode line : transcript.path("Transcript")) {
                if (!agents.contains(line.path("ParticipantId").asText())
                        && !"AGENT".equalsIgnoreCase(line.path("ParticipantId").asText())) {
                    first = line.path("Content").asText();
                    break;
                }
            }
            if (first == null || first.isBlank()) {
                continue;
            }
            Set<String> words = content(first);
            int match = -1;
            for (int i = 0; i < keys.size(); i++) {
                if (jaccard(keys.get(i), words) >= 0.3) {
                    match = i;
                    break;
                }
            }
            if (match < 0) {
                keys.add(new LinkedHashSet<>(words));
                ObjectNode intent = mapper.createObjectNode();
                intent.put("intentId", LexStore.id());
                intent.putArray("utterances");
                intent.putArray("transcripts");
                intents.add(intent);
                match = keys.size() - 1;
            } else {
                keys.get(match).addAll(words);
            }
            ObjectNode intent = intents.get(match);
            if (!contains(intent.withArray("utterances"), first)) {
                intent.withArray("utterances").add(first);
            }
            intent.withArray("transcripts").add(t);
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < intents.size(); i++) {
            String name = name(keys.get(i), i);
            while (!names.add(name)) {
                name = name + (i + 1);
            }
            intents.get(i).put("intentName", name);
        }
        return intents;
    }

    private static boolean contains(ArrayNode array, String value) {
        for (JsonNode node : array) {
            if (node.asText().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> content(String text) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : LexValues.normalize(text).split("\\s+")) {
            String w = word.replaceAll("[^a-z0-9]", "");
            if (w.length() > 1 && !STOP.contains(w)) {
                words.add(w);
            }
        }
        return words;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> common = new HashSet<>(a);
        common.retainAll(b);
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return (double) common.size() / all.size();
    }

    private static String name(Set<String> words, int index) {
        StringBuilder name = new StringBuilder();
        int taken = 0;
        for (String word : words) {
            if (taken == 2) {
                break;
            }
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            taken++;
        }
        return name.isEmpty() ? "RecommendedIntent" + (index + 1) : name.toString();
    }

    private ObjectNode recommendation(String region, JsonNode in) {
        String path = in.path("botId").asText() + "/" + in.path("botVersion").asText() + "/"
                + in.path("localeId").asText() + "/" + in.path("botRecommendationId").asText();
        return store.live(region, LexStore.RECOMMENDATION, path)
                .orElseThrow(() -> LexStore.notFound("The bot recommendation "
                        + in.path("botRecommendationId").asText() + " does not exist."));
    }

    public ObjectNode describeBotRecommendation(ObjectNode in, String region) {
        ObjectNode record = recommendation(region, in);
        ObjectNode out = LexStore.view(record);
        ArrayNode reasons = out.putArray("failureReasons");
        if ("Failed".equals(record.path("botRecommendationStatus").asText())) {
            record.path("_").path("failureReasons").forEach(reasons::add);
        }
        if ("Available".equals(record.path("botRecommendationStatus").asText())) {
            ObjectNode results = out.putObject("botRecommendationResults");
            results.put("botLocaleExportUrl", store.putFile(localeArchive(record.path("localeId").asText(),
                    record.path("_").path("intents")), "application/zip", "BotLocale.zip"));
            Map<String, byte[]> files = new LinkedHashMap<>();
            JsonNode transcripts = record.path("_").path("transcripts");
            for (int i = 0; i < transcripts.size(); i++) {
                files.put("transcripts/transcript-" + (i + 1) + ".json", transcripts.get(i).asText()
                        .getBytes(StandardCharsets.UTF_8));
            }
            results.put("associatedTranscriptsUrl", store.putFile(LexPorting.zip(files), "application/zip",
                    "AssociatedTranscripts.zip"));
            ObjectNode statistics = results.putObject("statistics");
            statistics.putObject("intents").put("discoveredIntentCount", record.path("_").path("intents").size());
            statistics.putObject("slotTypes").put("discoveredSlotTypeCount", 0);
        }
        return out;
    }

    /** A Lex JSON locale archive with one intent per entry and its utterances. */
    private byte[] localeArchive(String localeId, JsonNode intents) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        ObjectNode manifest = mapper.createObjectNode();
        manifest.putObject("metadata").put("schemaVersion", "1").put("fileFormat", "LexJson")
                .put("resourceType", "BotLocale");
        files.put("Manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
        String base = "BotLocales/" + localeId + "/";
        ObjectNode locale = mapper.createObjectNode();
        locale.put("name", LexCatalog.LOCALES.getOrDefault(localeId, localeId));
        locale.put("identifier", localeId);
        locale.put("nluConfidenceThreshold", 0.4);
        files.put(base + "BotLocale.json", locale.toString().getBytes(StandardCharsets.UTF_8));
        for (JsonNode intent : intents) {
            ObjectNode doc = mapper.createObjectNode();
            doc.put("name", intent.path("intentName").asText());
            doc.put("identifier", intent.path("intentId").asText());
            ArrayNode utterances = doc.putArray("sampleUtterances");
            intent.path("utterances").forEach(u -> utterances.addObject().put("utterance", u.asText()));
            files.put(base + "Intents/" + intent.path("intentName").asText() + "/Intent.json",
                    doc.toString().getBytes(StandardCharsets.UTF_8));
        }
        return LexPorting.zip(files);
    }

    public ObjectNode listBotRecommendations(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        bots.locale(region, botId, in.path("botVersion").asText(), in.path("localeId").asText());
        String prefix = botId + "/" + in.path("botVersion").asText() + "/" + in.path("localeId").asText() + "/";
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode record : store.liveList(region, LexStore.RECOMMENDATION, prefix)) {
            ObjectNode summary = mapper.createObjectNode();
            LexBots.copy(record, summary, "botRecommendationStatus", "botRecommendationId", "creationDateTime",
                    "lastUpdatedDateTime");
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(in, out, "botId", "botVersion", "localeId");
        LexStore.page(summaries, in, out, "botRecommendationSummaries", 10);
        return out;
    }

    public ObjectNode updateBotRecommendation(ObjectNode in, String region) {
        ObjectNode record = recommendation(region, in);
        String status = record.path("botRecommendationStatus").asText();
        if (!"Available".equals(status)) {
            throw LexStore.conflict("The bot recommendation is " + status + ".");
        }
        record.set("encryptionSetting", in.path("encryptionSetting").deepCopy());
        record.put("lastUpdatedDateTime", LexStore.epoch());
        store.settle(record, "botRecommendationStatus", "Updating", "Available");
        store.put(region, LexStore.RECOMMENDATION, record.path("_").path("path").asText(), record);
        ObjectNode out = LexStore.view(record);
        return out;
    }

    public ObjectNode stopBotRecommendation(ObjectNode in, String region) {
        ObjectNode record = recommendation(region, in);
        String status = record.path("botRecommendationStatus").asText();
        if (!"Processing".equals(status) && !"Downloading".equals(status)) {
            throw LexStore.precondition("A bot recommendation can be stopped only while it is processing.");
        }
        store.settle(record, "botRecommendationStatus", "Stopping", "Stopped");
        store.put(region, LexStore.RECOMMENDATION, record.path("_").path("path").asText(), record);
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(record, out, "botId", "botVersion", "localeId", "botRecommendationStatus",
                "botRecommendationId");
        return out;
    }

    public ObjectNode listRecommendedIntents(ObjectNode in, String region) {
        ObjectNode record = recommendation(region, in);
        requireAvailable(record);
        List<ObjectNode> summaries = new ArrayList<>();
        for (JsonNode intent : record.path("_").path("intents")) {
            ObjectNode summary = mapper.createObjectNode();
            summary.put("intentId", intent.path("intentId").asText());
            summary.put("intentName", intent.path("intentName").asText());
            summary.put("sampleUtterancesCount", intent.path("utterances").size());
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(in, out, "botId", "botVersion", "localeId", "botRecommendationId");
        LexStore.page(summaries, in, out, "summaryList", 10);
        return out;
    }

    private static void requireAvailable(ObjectNode record) {
        if (!"Available".equals(record.path("botRecommendationStatus").asText())) {
            throw LexStore.notFound("The bot recommendation has no results; it is "
                    + record.path("botRecommendationStatus").asText() + ".");
        }
    }

    public ObjectNode searchAssociatedTranscripts(ObjectNode in, String region) {
        ObjectNode record = recommendation(region, in);
        requireAvailable(record);
        Set<String> intentIds = new HashSet<>();
        for (JsonNode filter : in.path("filters")) {
            if ("IntentId".equals(filter.path("name").asText())) {
                filter.path("values").forEach(v -> intentIds.add(v.asText()));
            }
        }
        List<String> matched = new ArrayList<>();
        JsonNode transcripts = record.path("_").path("transcripts");
        for (JsonNode intent : record.path("_").path("intents")) {
            if (!intentIds.isEmpty() && !intentIds.contains(intent.path("intentId").asText())) {
                continue;
            }
            intent.path("transcripts").forEach(i -> matched.add(transcripts.get(i.asInt()).asText()));
        }
        if ("Descending".equals(in.path("searchOrder").asText())) {
            Collections.reverse(matched);
        }
        int start = in.path("nextIndex").asInt(0);
        int max = in.path("maxResults").asInt(100);
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(in, out, "botId", "botVersion", "localeId", "botRecommendationId");
        ArrayNode list = out.putArray("associatedTranscripts");
        for (int i = start; i < Math.min(matched.size(), start + max); i++) {
            list.addObject().put("transcript", matched.get(i));
        }
        if (start + max < matched.size()) {
            out.put("nextIndex", start + max);
        }
        out.put("totalResults", Math.max(1, Math.min(1000, matched.size())));
        return out;
    }

    // ================================================================ generation

    public ObjectNode startBotResourceGeneration(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        String localeId = in.path("localeId").asText();
        ObjectNode locale = bots.draftLocale(region, botId, in.path("botVersion").asText(), localeId);
        JsonNode builder = locale.path("generativeAISettings").path("buildtimeSettings")
                .path("descriptiveBotBuilder");
        if (!builder.path("enabled").asBoolean(false)) {
            throw LexStore.precondition("Turn on the descriptive bot builder in the locale's generative AI "
                    + "settings first.");
        }
        String prefix = botId + "/" + LexStore.DRAFT + "/" + localeId + "/";
        for (ObjectNode other : store.liveList(region, LexStore.GENERATION, prefix)) {
            if ("InProgress".equals(other.path("generationStatus").asText())) {
                throw LexStore.conflict("The locale has a generation in progress.");
            }
        }
        String prompt = in.path("generationInputPrompt").asText();
        List<ObjectNode> intents = describe(prompt);
        String generationId = LexStore.id();
        ObjectNode record = store.record();
        record.put("generationInputPrompt", prompt);
        record.put("generationId", generationId);
        record.put("botId", botId);
        record.put("botVersion", LexStore.DRAFT);
        record.put("localeId", localeId);
        double now = LexStore.epoch();
        record.put("creationDateTime", now);
        record.put("lastUpdatedDateTime", now);
        String model = builder.path("bedrockModelSpecification").path("modelArn").asText(null);
        if (model != null) {
            record.put("modelArn", model);
        }
        LexStore.meta(record).put("url", store.putFile(localeArchive(localeId, mapper.valueToTree(intents)),
                "application/zip", "GeneratedBotLocale.zip"));
        store.settle(record, "generationStatus", "InProgress", intents.isEmpty() ? "Failed" : "Complete");
        if (intents.isEmpty()) {
            LexStore.meta(record).putArray("failureReasons").add("The description names nothing the bot does.");
        }
        store.put(region, LexStore.GENERATION, prefix + generationId, record);
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(record, out, "generationInputPrompt", "generationId", "botId", "botVersion", "localeId",
                "generationStatus", "creationDateTime");
        return out;
    }

    /** Intents drafted from a description: one per thing it says users do, with utterances that ask for it. */
    private List<ObjectNode> describe(String prompt) {
        List<ObjectNode> intents = new ArrayList<>();
        Set<String> names = new HashSet<>();
        String normalized = prompt.replaceAll("(?i)\\b(and|then|also)\\b", ",");
        for (String clause : normalized.split("[.;,\\n]+")) {
            String text = clause.trim().replaceAll("(?i)^(the bot|it|users?|customers?)\\s+(should|can|will|"
                    + "must|lets?|helps?|allows?)\\s+((users?|customers?|people)\\s+)?(to\\s+)?", "")
                    .replaceAll("(?i)^(to|help|let|allow)\\s+", "").trim();
            Set<String> words = content(text);
            if (words.size() < 1 || text.split("\\s+").length < 2 || text.length() > 120) {
                continue;
            }
            String name = name(words, intents.size());
            if (!names.add(name)) {
                continue;
            }
            String action = text.toLowerCase(Locale.ROOT);
            ObjectNode intent = mapper.createObjectNode();
            intent.put("intentId", LexStore.id());
            intent.put("intentName", name);
            ArrayNode utterances = intent.putArray("utterances");
            for (String form : List.of("%s", "I want to %s", "I would like to %s", "can you %s", "help me %s")) {
                utterances.add(String.format(form, action));
            }
            intents.add(intent);
        }
        return intents;
    }

    public ObjectNode describeBotResourceGeneration(ObjectNode in, String region) {
        String path = in.path("botId").asText() + "/" + in.path("botVersion").asText() + "/"
                + in.path("localeId").asText() + "/" + in.path("generationId").asText();
        ObjectNode record = store.live(region, LexStore.GENERATION, path)
                .orElseThrow(() -> LexStore.notFound("The generation " + in.path("generationId").asText()
                        + " does not exist."));
        ObjectNode out = LexStore.view(record);
        ArrayNode reasons = out.putArray("failureReasons");
        if ("Failed".equals(record.path("generationStatus").asText())) {
            record.path("_").path("failureReasons").forEach(reasons::add);
        }
        if ("Complete".equals(record.path("generationStatus").asText())) {
            out.put("generatedBotLocaleUrl", record.path("_").path("url").asText());
        }
        return out;
    }

    public ObjectNode listBotResourceGenerations(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        bots.locale(region, botId, in.path("botVersion").asText(), in.path("localeId").asText());
        String prefix = botId + "/" + in.path("botVersion").asText() + "/" + in.path("localeId").asText() + "/";
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode record : store.liveList(region, LexStore.GENERATION, prefix)) {
            ObjectNode summary = mapper.createObjectNode();
            LexBots.copy(record, summary, "generationId", "generationStatus", "creationDateTime",
                    "lastUpdatedDateTime");
            summaries.add(summary);
        }
        LexStore.sort(summaries, in.path("sortBy"), Map.of("creationStartTime", "creationDateTime",
                "lastUpdatedTime", "lastUpdatedDateTime"), "creationDateTime");
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(in, out, "botId", "botVersion", "localeId");
        LexStore.page(summaries, in, out, "generationSummaries", 10);
        return out;
    }

    public ObjectNode generateBotElement(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        String localeId = in.path("localeId").asText();
        ObjectNode locale = bots.draftLocale(region, botId, in.path("botVersion").asText(), localeId);
        if (!locale.path("generativeAISettings").path("buildtimeSettings").path("sampleUtteranceGeneration")
                .path("enabled").asBoolean(false)) {
            throw LexStore.precondition("Turn on sample utterance generation in the locale's generative AI "
                    + "settings first.");
        }
        String intentId = in.path("intentId").asText();
        ObjectNode intent = store.live(region, LexStore.INTENT, botId + "/" + LexStore.DRAFT + "/" + localeId + "/"
                + intentId).orElseThrow(() -> LexStore.notFound("The intent " + intentId + " does not exist."));
        Set<String> existing = new HashSet<>();
        List<String> bases = new ArrayList<>();
        intent.path("sampleUtterances").forEach(u -> {
            existing.add(u.path("utterance").asText().toLowerCase(Locale.ROOT));
            bases.add(u.path("utterance").asText());
        });
        if (bases.isEmpty()) {
            bases.add(intent.path("intentName").asText().replaceAll("([a-z])([A-Z])", "$1 $2")
                    .toLowerCase(Locale.ROOT));
        }
        Set<String> generated = new LinkedHashSet<>();
        for (String base : bases) {
            String core = base.replaceAll("(?i)^(i want to|i would like to|can you|please|i need to)\\s+", "");
            for (String form : List.of("I want to %s", "I would like to %s", "can you %s", "please %s",
                    "I need to %s", "%s please")) {
                String candidate = String.format(form, core);
                if (!existing.contains(candidate.toLowerCase(Locale.ROOT))) {
                    generated.add(candidate);
                }
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("botId", botId);
        out.put("botVersion", LexStore.DRAFT);
        out.put("localeId", localeId);
        out.put("intentId", intentId);
        ArrayNode list = out.putArray("sampleUtterances");
        generated.stream().limit(10).forEach(u -> list.addObject().put("utterance", u));
        return out;
    }

    // ================================================================ bot analyzer

    public ObjectNode startBotAnalyzer(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        bots.bot(region, botId);
        String localeId = in.path("localeId").asText(null);
        if (localeId == null) {
            throw LexStore.validation("A BotLocale analysis needs a localeId.");
        }
        String version = in.path("botVersion").asText(LexStore.DRAFT);
        ObjectNode locale = bots.locale(region, botId, version, localeId);
        for (ObjectNode other : store.liveList(region, LexStore.ANALYZER, botId + "/")) {
            if ("Processing".equals(other.path("botAnalyzerStatus").asText())) {
                throw LexStore.conflict("The bot has an analysis in progress.");
            }
        }
        ObjectNode model = bots.snapshot(region, botId + "/" + version + "/" + localeId, locale);
        List<ObjectNode> issues = analyze(model, localeId);
        String requestId = LexStore.uuid();
        ObjectNode record = store.record();
        record.put("botId", botId);
        record.put("botVersion", version);
        record.put("localeId", localeId);
        record.put("botAnalyzerRequestId", requestId);
        record.put("creationDateTime", LexStore.epoch());
        store.settle(record, "botAnalyzerStatus", "Processing", "Available");
        LexStore.meta(record).set("issues", mapper.valueToTree(issues));
        store.put(region, LexStore.ANALYZER, botId + "/" + requestId, record);
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(record, out, "botId", "botVersion", "localeId", "botAnalyzerStatus", "botAnalyzerRequestId",
                "creationDateTime");
        return out;
    }

    /** A locale design's issues: thin or overlapping utterances, slots without prompts or mentions, unused types. */
    private List<ObjectNode> analyze(ObjectNode model, String localeId) {
        List<ObjectNode> issues = new ArrayList<>();
        Map<String, String> owners = new LinkedHashMap<>();
        Set<String> usedTypes = new HashSet<>();
        for (JsonNode intent : model.path("intents")) {
            String intentId = intent.path("intentId").asText();
            String name = intent.path("intentName").asText();
            if (intent.hasNonNull("parentIntentSignature")) {
                continue;
            }
            int count = intent.path("sampleUtterances").size();
            if (count < 5) {
                issues.add(issue(localeId, intentId, null, count == 0 ? "High" : "Medium", "The intent " + name
                        + " has " + count + " sample utterances.", "Add at least 5 varied sample utterances to "
                        + name + "."));
            }
            for (JsonNode utterance : intent.path("sampleUtterances")) {
                String key = LexValues.normalize(utterance.path("utterance").asText());
                String other = owners.putIfAbsent(key, name);
                if (other != null && !other.equals(name)) {
                    issues.add(issue(localeId, intentId, null, "High", "The utterance \""
                            + utterance.path("utterance").asText() + "\" belongs to both " + other + " and " + name
                            + ".", "Keep the utterance in only one intent."));
                }
            }
            for (JsonNode slot : LexNlu.slotsOf(model, intent)) {
                String slotName = slot.path("slotName").asText();
                usedTypes.add(slot.path("slotTypeId").asText());
                JsonNode setting = slot.path("valueElicitationSetting");
                if ("Required".equals(setting.path("slotConstraint").asText())
                        && setting.path("promptSpecification").path("messageGroups").isEmpty()) {
                    issues.add(issue(localeId, intentId, slot.path("slotId").asText(), "High", "The required slot "
                            + slotName + " has no prompt.", "Add a prompt that asks for " + slotName + "."));
                }
                boolean mentioned = false;
                for (JsonNode utterance : intent.path("sampleUtterances")) {
                    mentioned |= utterance.path("utterance").asText().contains("{" + slotName + "}");
                }
                if (!mentioned) {
                    issues.add(issue(localeId, intentId, slot.path("slotId").asText(), "Low", "No sample utterance "
                            + "of " + name + " mentions the slot " + slotName + ".", "Add utterances like \"... {"
                            + slotName + "} ...\" so users can give it up front."));
                }
            }
        }
        for (JsonNode type : model.path("slotTypes")) {
            if (!usedTypes.contains(type.path("slotTypeId").asText())) {
                issues.add(issue(localeId, null, null, "Low", "The slot type " + type.path("slotTypeName").asText()
                        + " is not used by any slot.", "Use the slot type in a slot or delete it."));
            }
        }
        return issues;
    }

    private ObjectNode issue(String localeId, String intentId, String slotId, String priority, String description,
                             String fix) {
        ObjectNode issue = mapper.createObjectNode();
        ObjectNode location = issue.putObject("issueLocation");
        location.put("botLocale", localeId);
        if (intentId != null) {
            location.put("intentId", intentId);
        }
        if (slotId != null) {
            location.put("slotId", slotId);
        }
        issue.put("priority", priority);
        issue.put("issueDescription", description);
        issue.put("proposedFix", fix);
        return issue;
    }

    private ObjectNode analysis(String region, JsonNode in) {
        String botId = in.path("botId").asText();
        String requestId = in.path("botAnalyzerRequestId").asText();
        return store.live(region, LexStore.ANALYZER, botId + "/" + requestId)
                .orElseThrow(() -> LexStore.notFound("The analysis " + requestId + " does not exist."));
    }

    public ObjectNode describeBotAnalyzerRecommendation(ObjectNode in, String region) {
        ObjectNode record = analysis(region, in);
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(record, out, "botId", "botVersion", "localeId", "botAnalyzerStatus", "creationDateTime");
        List<ObjectNode> issues = new ArrayList<>();
        if ("Available".equals(record.path("botAnalyzerStatus").asText())) {
            record.path("_").path("issues").forEach(i -> issues.add((ObjectNode) i));
        }
        LexStore.page(issues, in, out, "botAnalyzerRecommendationList", 100);
        return out;
    }

    public ObjectNode listBotAnalyzerHistory(ObjectNode in, String region) {
        String botId = in.path("botId").asText();
        bots.bot(region, botId);
        List<ObjectNode> history = new ArrayList<>();
        for (ObjectNode record : store.liveList(region, LexStore.ANALYZER, botId + "/")) {
            if (in.hasNonNull("localeId") && !in.get("localeId").asText().equals(record.path("localeId").asText())) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            LexBots.copy(record, summary, "botAnalyzerStatus", "creationDateTime", "botAnalyzerRequestId");
            history.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(in, out, "botId", "localeId", "botVersion");
        LexStore.page(history, in, out, "botAnalyzerHistoryList", 10);
        return out;
    }

    public ObjectNode stopBotAnalyzer(ObjectNode in, String region) {
        ObjectNode record = analysis(region, in);
        if ("Processing".equals(record.path("botAnalyzerStatus").asText())) {
            store.settle(record, "botAnalyzerStatus", "Stopping", "Stopped");
            store.put(region, LexStore.ANALYZER, record.path("_").path("path").asText(), record);
        }
        ObjectNode out = mapper.createObjectNode();
        LexBots.copy(record, out, "botId", "botVersion", "localeId", "botAnalyzerStatus", "botAnalyzerRequestId");
        return out;
    }

    public ObjectNode deleteBotAnalyzerRecommendation(ObjectNode in, String region) {
        ObjectNode record = analysis(region, in);
        store.delete(region, LexStore.ANALYZER, record.path("_").path("path").asText());
        return mapper.createObjectNode();
    }
}
