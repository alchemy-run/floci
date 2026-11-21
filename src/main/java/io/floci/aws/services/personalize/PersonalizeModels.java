package io.floci.aws.services.personalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Amazon Personalize's recipes and the models Floci trains for them from a dataset group's interactions. Popularity
 * recipes count interactions; trending recipes weigh the last week against the weeks before; related-items recipes
 * score items by how often the same users interacted with both (cosine-normalized co-occurrence); user personalization
 * scores items by their co-occurrence with the user's history, backed off to popularity; personalized ranking reorders a
 * list by the same scores; user segmentation finds the users with the highest affinity for an item; next-best-action
 * ranks actions by how often they're taken. Training holds each user's last interaction out to measure the model.
 */
final class PersonalizeModels {

    /** A recipe: its name, type, what it computes, the event type it trains on, and its domain. */
    record Recipe(String name, String type, String mode, String eventType, String domain) {
        /** The recipe's ARN in a partition: recipes are global and belong to no account. */
        String arn(String partition) {
            return arnOf(partition, "recipe", name);
        }
    }

    static final Map<String, Recipe> RECIPES = new LinkedHashMap<>();

    static {
        for (Recipe recipe : List.of(
                new Recipe("aws-user-personalization", "USER_PERSONALIZATION", "personalization", null, null),
                new Recipe("aws-user-personalization-v2", "USER_PERSONALIZATION", "personalization", null, null),
                new Recipe("aws-popularity-count", "USER_PERSONALIZATION", "popularity", null, null),
                new Recipe("aws-trending-now", "USER_PERSONALIZATION", "trending", null, null),
                new Recipe("aws-personalized-ranking", "PERSONALIZED_RANKING", "ranking", null, null),
                new Recipe("aws-personalized-ranking-v2", "PERSONALIZED_RANKING", "ranking", null, null),
                new Recipe("aws-similar-items", "RELATED_ITEMS", "related", null, null),
                new Recipe("aws-sims", "RELATED_ITEMS", "related", null, null),
                new Recipe("aws-item-affinity", "USER_SEGMENTATION", "segmentation", null, null),
                new Recipe("aws-item-attribute-affinity", "USER_SEGMENTATION", "segmentation", null, null),
                new Recipe("aws-next-best-action", "PERSONALIZED_ACTIONS", "actions", null, null),
                new Recipe("aws-ecomm-customers-who-viewed-x-also-viewed", "RELATED_ITEMS", "related", "View",
                        "ECOMMERCE"),
                new Recipe("aws-ecomm-frequently-bought-together", "RELATED_ITEMS", "related", "Purchase",
                        "ECOMMERCE"),
                new Recipe("aws-ecomm-popular-items-by-purchases", "USER_PERSONALIZATION", "popularity", "Purchase",
                        "ECOMMERCE"),
                new Recipe("aws-ecomm-popular-items-by-views", "USER_PERSONALIZATION", "popularity", "View",
                        "ECOMMERCE"),
                new Recipe("aws-ecomm-recommended-for-you", "USER_PERSONALIZATION", "personalization", null,
                        "ECOMMERCE"),
                new Recipe("aws-ecomm-similar-items", "RELATED_ITEMS", "related", null, "ECOMMERCE"),
                new Recipe("aws-vod-because-you-watched-x", "RELATED_ITEMS", "related", "Watch", "VIDEO_ON_DEMAND"),
                new Recipe("aws-vod-more-like-x", "RELATED_ITEMS", "related", null, "VIDEO_ON_DEMAND"),
                new Recipe("aws-vod-most-popular", "USER_PERSONALIZATION", "popularity", "Watch", "VIDEO_ON_DEMAND"),
                new Recipe("aws-vod-top-picks", "USER_PERSONALIZATION", "personalization", null, "VIDEO_ON_DEMAND"),
                new Recipe("aws-vod-trending-now", "USER_PERSONALIZATION", "trending", null, "VIDEO_ON_DEMAND"))) {
            RECIPES.put(recipe.name(), recipe);
        }
    }

    /** A global Personalize ARN: {@code arn:<partition>:personalize:::<type>/<name>}. */
    static String arnOf(String partition, String type, String name) {
        return AwsArnUtils.Arn.global(partition, PersonalizeStore.SERVICE, "", type + "/" + name).toString();
    }

    /** The name a global Personalize ARN of a type names, or null when it names none. */
    static String nameOf(String arn, String type) {
        if (!AwsArnUtils.isArnFor(arn, PersonalizeStore.SERVICE)) {
            return null;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!parsed.accountId().isEmpty() || !parsed.resource().startsWith(type + "/")) {
            return null;
        }
        return parsed.resource().substring(type.length() + 1);
    }

    private static final long WEEK = 7L * 24 * 3600;

    private PersonalizeModels() {
    }

    // ================================================================ training

    /** One interaction a model learns from. */
    record Interaction(String user, String item, long timestamp, String eventType, double value) {
    }

    static List<Interaction> interactions(JsonNode rows, String eventType, Double threshold, String itemField) {
        List<Interaction> list = new ArrayList<>();
        for (JsonNode row : rows) {
            String type = row.path("EVENT_TYPE").asText("");
            if (eventType != null && !eventType.isBlank() && !eventType.equalsIgnoreCase(type)) {
                continue;
            }
            double value = row.path("EVENT_VALUE").asDouble(1.0);
            if (threshold != null && row.has("EVENT_VALUE") && value < threshold) {
                continue;
            }
            String item = row.path(itemField).asText("");
            String user = row.path("USER_ID").asText("");
            if (item.isEmpty()) {
                continue;
            }
            list.add(new Interaction(user, item, row.path("TIMESTAMP").asLong(0), type, value));
        }
        list.sort(Comparator.comparingLong(Interaction::timestamp));
        return list;
    }

    /** Trains a model: popularity, trending scores, each user's history and item co-occurrence. */
    static ObjectNode train(ObjectMapper mapper, String mode, List<Interaction> interactions) {
        Map<String, Double> popularity = new HashMap<>();
        Map<String, List<String>> history = new LinkedHashMap<>();
        long latest = interactions.stream().mapToLong(Interaction::timestamp).max().orElse(0);
        Map<String, Double> recent = new HashMap<>();
        Map<String, Double> earlier = new HashMap<>();
        for (Interaction interaction : interactions) {
            popularity.merge(interaction.item(), 1.0, Double::sum);
            if (!interaction.user().isEmpty()) {
                history.computeIfAbsent(interaction.user(), user -> new ArrayList<>()).add(interaction.item());
            }
            (interaction.timestamp() >= latest - WEEK ? recent : earlier).merge(interaction.item(), 1.0,
                    Double::sum);
        }
        Map<String, Map<String, Double>> cooccurrence = new HashMap<>();
        for (List<String> items : history.values()) {
            List<String> distinct = new ArrayList<>(new LinkedHashSet<>(items));
            for (String a : distinct) {
                for (String b : distinct) {
                    if (!a.equals(b)) {
                        cooccurrence.computeIfAbsent(a, key -> new HashMap<>()).merge(b, 1.0, Double::sum);
                    }
                }
            }
        }
        ObjectNode model = mapper.createObjectNode();
        model.put("mode", mode);
        ObjectNode pop = model.putObject("popularity");
        popularity.forEach(pop::put);
        ObjectNode trend = model.putObject("trending");
        popularity.keySet().forEach(item -> trend.put(item, recent.getOrDefault(item, 0.0)
                / (1.0 + earlier.getOrDefault(item, 0.0))));
        ObjectNode users = model.putObject("users");
        history.forEach((user, items) -> {
            ArrayNode list = users.putArray(user);
            items.forEach(list::add);
        });
        ObjectNode co = model.putObject("cooccurrence");
        cooccurrence.forEach((item, counts) -> {
            ObjectNode row = co.putObject(item);
            counts.forEach(row::put);
        });
        return model;
    }

    // ================================================================ inference

    /** A scored candidate. */
    record Scored(String id, double score) {
    }

    private static double similarity(JsonNode model, String a, String b) {
        double together = model.path("cooccurrence").path(a).path(b).asDouble(0);
        if (together == 0) {
            return 0;
        }
        double pa = model.path("popularity").path(a).asDouble(1);
        double pb = model.path("popularity").path(b).asDouble(1);
        return together / Math.sqrt(pa * pb);
    }

    private static List<String> items(JsonNode model) {
        List<String> items = new ArrayList<>();
        model.path("popularity").fieldNames().forEachRemaining(items::add);
        return items;
    }

    private static double maxPopularity(JsonNode model) {
        double max = 1;
        for (JsonNode count : model.path("popularity")) {
            max = Math.max(max, count.asDouble());
        }
        return max;
    }

    /** How much a user would like an item: co-occurrence with their history, plus a little popularity. */
    static double affinity(JsonNode model, String user, String item) {
        double score = 0;
        for (JsonNode seen : model.path("users").path(user)) {
            score += similarity(model, seen.asText(), item);
        }
        return score + 0.01 * model.path("popularity").path(item).asDouble(0) / maxPopularity(model);
    }

    private static List<Scored> top(Map<String, Double> scores, int n) {
        List<Scored> list = new ArrayList<>();
        scores.forEach((id, score) -> list.add(new Scored(id, score)));
        list.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(Scored::id));
        return normalize(list.subList(0, Math.min(n, list.size())));
    }

    /** Scores that sum to 1 over what's returned, as Personalize's do. */
    static List<Scored> normalize(List<Scored> list) {
        double total = list.stream().mapToDouble(scored -> Math.max(0, scored.score())).sum();
        List<Scored> normalized = new ArrayList<>();
        for (Scored scored : list) {
            normalized.add(new Scored(scored.id(), total == 0 ? 1.0 / list.size()
                    : Math.round(Math.max(0, scored.score()) / total * 1e7) / 1e7));
        }
        return normalized;
    }

    /** Recommendations for a user, an item, or neither, from a model, among the candidates the filter keeps. */
    static List<Scored> recommend(JsonNode model, String user, String item, int n, Predicate<String> keep) {
        String mode = model.path("mode").asText();
        Map<String, Double> scores = new HashMap<>();
        boolean knownUser = user != null && model.path("users").has(user);
        for (String candidate : items(model)) {
            if (!keep.test(candidate) || candidate.equals(item)) {
                continue;
            }
            double score = switch (mode) {
                case "popularity" -> model.path("popularity").path(candidate).asDouble();
                case "trending" -> model.path("trending").path(candidate).asDouble();
                case "related" -> item == null ? 0 : similarity(model, item, candidate)
                        + 0.001 * model.path("popularity").path(candidate).asDouble() / maxPopularity(model);
                default -> knownUser ? affinity(model, user, candidate)
                        : model.path("popularity").path(candidate).asDouble();
            };
            if (score >= 0) {
                scores.put(candidate, score);
            }
        }
        return top(scores, n);
    }

    /** The given items, best first for the user. */
    static List<Scored> rank(JsonNode model, String user, Collection<String> items, Predicate<String> keep) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (String candidate : items) {
            if (keep.test(candidate)) {
                scores.put(candidate, affinity(model, user, candidate));
            }
        }
        return top(scores, scores.size());
    }

    /** The users with the highest affinity for an item. */
    static List<Scored> segment(JsonNode model, String item, int n) {
        Map<String, Double> scores = new HashMap<>();
        model.path("users").properties().forEach(entry -> {
            double score = 0;
            for (JsonNode seen : entry.getValue()) {
                score += seen.asText().equals(item) ? 1 : similarity(model, seen.asText(), item);
            }
            if (score > 0) {
                scores.put(entry.getKey(), score);
            }
        });
        return top(scores, n);
    }

    // ================================================================ evaluation

    /** Holds each user's last interaction out, trains on the rest, and measures how well the model finds it. */
    static ObjectNode metrics(ObjectMapper mapper, String mode, List<Interaction> interactions) {
        Map<String, Interaction> heldOut = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Interaction interaction : interactions) {
            counts.merge(interaction.user(), 1, Integer::sum);
            heldOut.put(interaction.user(), interaction);
        }
        heldOut.keySet().removeIf(user -> counts.get(user) < 2 || user.isEmpty());
        List<Interaction> training = new ArrayList<>(interactions);
        training.removeAll(heldOut.values());
        ObjectNode model = train(mapper, mode, training);
        Set<String> covered = new HashSet<>();
        double[] precision = new double[3];
        double[] ndcg = new double[3];
        int[] cutoffs = {5, 10, 25};
        double mrr = 0;
        for (Interaction held : heldOut.values()) {
            String anchor = null;
            JsonNode history = model.path("users").path(held.user());
            if ("related".equals(mode) && !history.isEmpty()) {
                anchor = history.get(history.size() - 1).asText();
            }
            List<Scored> top = recommend(model, "related".equals(mode) ? null : held.user(), anchor, 25,
                    candidate -> true);
            top.forEach(scored -> covered.add(scored.id()));
            int rank = -1;
            for (int i = 0; i < top.size(); i++) {
                if (top.get(i).id().equals(held.item())) {
                    rank = i + 1;
                }
            }
            if (rank > 0) {
                mrr += 1.0 / rank;
                for (int c = 0; c < cutoffs.length; c++) {
                    if (rank <= cutoffs[c]) {
                        precision[c] += 1.0 / cutoffs[c];
                        ndcg[c] += 1.0 / (Math.log(rank + 1) / Math.log(2));
                    }
                }
            }
        }
        int users = Math.max(1, heldOut.size());
        ObjectNode metrics = mapper.createObjectNode();
        long catalog = interactions.stream().map(Interaction::item).distinct().count();
        metrics.put("coverage", round(catalog == 0 ? 0 : (double) covered.size() / catalog));
        metrics.put("mean_reciprocal_rank_at_25", round(mrr / users));
        for (int c = 0; c < cutoffs.length; c++) {
            metrics.put("normalized_discounted_cumulative_gain_at_" + cutoffs[c], round(ndcg[c] / users));
            metrics.put("precision_at_" + cutoffs[c], round(precision[c] / users));
        }
        return metrics;
    }

    private static double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }
}
