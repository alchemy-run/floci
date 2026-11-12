package io.floci.aws.services.personalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.personalize.PersonalizeData.Field;
import io.floci.aws.services.personalize.PersonalizeModels.Recipe;
import io.floci.aws.services.personalize.PersonalizeModels.Scored;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Personalize's data plane: recording events, items, users, actions and action interactions into their datasets, and
 * answering recommendations, rankings and action recommendations from a campaign's or recommender's model, with the
 * user's latest interactions, filters, promotions and item metadata. Batch jobs answer each input line the same way.
 */
@ApplicationScoped
public class PersonalizeInference {

    private final PersonalizeStore store;
    private final ObjectMapper mapper;

    @Inject
    public PersonalizeInference(PersonalizeStore store) {
        this.store = store;
        this.mapper = store.mapper();
    }

    /** A model and what it answers for. */
    record Deployed(ObjectNode model, Recipe recipe, JsonNode config, String region, String groupArn) {
    }

    // ================================================================ datasets

    private String groupName(String groupArn) {
        String resource = groupArn.substring(groupArn.indexOf(":dataset-group/") + ":dataset-group/".length());
        return resource;
    }

    private ObjectNode datasetOf(String region, String groupArn, String type) {
        String id = groupName(groupArn) + "/" + type;
        return store.get(region, PersonalizeStore.DATASET, id)
                .map(dataset -> store.settle(region, PersonalizeStore.DATASET, dataset)).orElse(null);
    }

    private List<ObjectNode> rows(String region, String groupArn, String type) {
        ObjectNode dataset = datasetOf(region, groupArn, type);
        if (dataset == null) {
            return List.of();
        }
        return PersonalizeData.stored(store.data(store.account(), "dataset/" + PersonalizeStore.id(dataset))
                .orElse(null));
    }

    private Map<String, ObjectNode> keyed(String region, String groupArn, String type) {
        Map<String, ObjectNode> rows = new LinkedHashMap<>();
        String key = PersonalizeData.KEYS.get(type);
        for (ObjectNode row : rows(region, groupArn, type)) {
            rows.put(row.path(key).asText(), row);
        }
        return rows;
    }

    /** The interactions a recipe trains on, failing when there are too few. */
    List<PersonalizeModels.Interaction> trainingSet(String region, String groupArn, Recipe recipe, String eventType,
                                                    Double threshold) {
        boolean actions = "actions".equals(recipe.mode());
        String type = actions ? PersonalizeData.ACTION_INTERACTIONS : PersonalizeData.INTERACTIONS;
        if (datasetOf(region, groupArn, type) == null) {
            throw PersonalizeStore.invalid("The dataset group has no " + type + " dataset to train on.");
        }
        ArrayNode array = mapper.createArrayNode();
        rows(region, groupArn, type).forEach(array::add);
        List<PersonalizeModels.Interaction> interactions = PersonalizeModels.interactions(array, eventType, threshold,
                actions ? "ACTION_ID" : "ITEM_ID");
        int minimum = store.config().minTrainingInteractions();
        if (interactions.size() < minimum) {
            throw PersonalizeStore.invalid("Training needs at least " + minimum + " interactions"
                    + (eventType == null ? "" : " of type " + eventType) + "; the dataset group has "
                    + interactions.size() + ".");
        }
        return interactions;
    }

    // ================================================================ models

    private ObjectNode modelOf(String key) {
        return store.data(store.account(), key).orElseThrow(() -> PersonalizeStore.notFound(
                "The model isn't trained."));
    }

    Deployed campaign(String arn) {
        ObjectNode campaign = store.require(arn, PersonalizeStore.CAMPAIGN, "campaign");
        if (!"ACTIVE".equals(campaign.path("status").asText()) && !campaign.path("status").asText()
                .startsWith("UPDATE")) {
            throw PersonalizeStore.invalid("The campaign is " + campaign.path("status").asText() + ", not ACTIVE.");
        }
        String versionArn = campaign.get("solutionVersionArn").asText();
        String region = PersonalizeStore.region(campaign);
        if (campaign.at("/campaignConfig/syncWithLatestSolutionVersion").asBoolean(false)) {
            String solutionArn = versionArn.substring(0, versionArn.lastIndexOf('/'));
            for (ObjectNode version : store.all(region, PersonalizeStore.VERSION)) {
                if (solutionArn.equals(version.path("solutionArn").asText())
                        && "ACTIVE".equals(version.path("status").asText())) {
                    versionArn = version.get("solutionVersionArn").asText();
                }
            }
        }
        String id = versionArn.substring(versionArn.indexOf(":solution/") + ":solution/".length());
        ObjectNode model = modelOf("model/" + id);
        return new Deployed(model, PersonalizeService.recipe(model.get("recipeArn").asText()),
                campaign.path("campaignConfig"), region,
                PersonalizeStore.meta(campaign).path("datasetGroupArn").asText());
    }

    Deployed recommender(String arn) {
        ObjectNode recommender = store.require(arn, PersonalizeStore.RECOMMENDER, "recommender");
        if (!"ACTIVE".equals(recommender.path("status").asText()) && !recommender.path("status").asText()
                .startsWith("UPDATE")) {
            throw PersonalizeStore.invalid("The recommender is " + recommender.path("status").asText()
                    + ", not ACTIVE.");
        }
        ObjectNode model = modelOf("model/recommender/" + PersonalizeStore.id(recommender));
        return new Deployed(model, PersonalizeService.recipe(model.get("recipeArn").asText()),
                recommender.path("recommenderConfig"), PersonalizeStore.region(recommender),
                recommender.get("datasetGroupArn").asText());
    }

    private Deployed deployed(JsonNode in) {
        String campaign = in.path("campaignArn").asText("");
        String recommender = in.path("recommenderArn").asText("");
        if (campaign.isEmpty() == recommender.isEmpty()) {
            throw PersonalizeStore.invalid("Give either a campaignArn or a recommenderArn.");
        }
        return campaign.isEmpty() ? recommender(recommender) : campaign(campaign);
    }

    /** The model with the user's history brought up to date with the interactions recorded since training. */
    private ObjectNode withHistory(Deployed deployed, String user) {
        ObjectNode model = deployed.model();
        if (user == null) {
            return model;
        }
        boolean actions = "actions".equals(deployed.recipe().mode());
        String type = actions ? PersonalizeData.ACTION_INTERACTIONS : PersonalizeData.INTERACTIONS;
        String field = actions ? "ACTION_ID" : "ITEM_ID";
        ArrayNode history = mapper.createArrayNode();
        for (ObjectNode row : rows(deployed.region(), deployed.groupArn(), type)) {
            if (user.equals(row.path("USER_ID").asText()) && model.path("popularity").has(row.path(field).asText())) {
                history.add(row.path(field).asText());
            }
        }
        ObjectNode copy = model.deepCopy();
        if (!history.isEmpty()) {
            ((ObjectNode) copy.get("users")).set(user, history);
        }
        return copy;
    }

    // ================================================================ filters

    private Predicate<String> filter(Deployed deployed, String filterArn, JsonNode values, String user,
                                     String current) {
        if (filterArn == null || filterArn.isBlank()) {
            return candidate -> true;
        }
        ObjectNode filter = store.require(filterArn, PersonalizeStore.FILTER, "filter");
        if (!"ACTIVE".equals(filter.path("status").asText())) {
            throw PersonalizeStore.invalid("The filter is " + filter.path("status").asText() + ", not ACTIVE.");
        }
        if (!deployed.groupArn().equals(filter.path("datasetGroupArn").asText())) {
            throw PersonalizeStore.invalid("The filter belongs to another dataset group.");
        }
        List<PersonalizeFilters.Clause> clauses = PersonalizeFilters.parse(filter.get("filterExpression").asText());
        Map<String, List<String>> given = PersonalizeFilters.values(values);
        for (String name : given.keySet()) {
            if (!PersonalizeFilters.parameters(clauses).contains(name)) {
                throw PersonalizeStore.invalid("The filter has no parameter $" + name + ".");
            }
        }
        boolean actions = "actions".equals(deployed.recipe().mode());
        Map<String, ObjectNode> catalog = keyed(deployed.region(), deployed.groupArn(),
                actions ? PersonalizeData.ACTIONS : PersonalizeData.ITEMS);
        ObjectNode userRow = user == null ? null : keyed(deployed.region(), deployed.groupArn(),
                PersonalizeData.USERS).get(user);
        List<ObjectNode> interactions = new ArrayList<>();
        if (user != null) {
            for (ObjectNode row : rows(deployed.region(), deployed.groupArn(), actions
                    ? PersonalizeData.ACTION_INTERACTIONS : PersonalizeData.INTERACTIONS)) {
                if (user.equals(row.path("USER_ID").asText())) {
                    interactions.add(row);
                }
            }
        }
        return PersonalizeFilters.predicate(clauses, new PersonalizeFilters.Context(catalog, userRow, interactions,
                current == null ? null : catalog.get(current), given));
    }

    // ================================================================ answers

    private static int count(JsonNode in, int fallback, int max) {
        int n = in.path("numResults").asInt(fallback);
        if (n < 1 || n > max) {
            throw PersonalizeStore.invalid("numResults must be from 1 to " + max + ".");
        }
        return n;
    }

    private ArrayNode items(Deployed deployed, List<Scored> scored, JsonNode metadataColumns, String promotion,
                            Set<String> promoted) {
        Map<String, ObjectNode> catalog = null;
        List<String> columns = new ArrayList<>();
        if (metadataColumns != null && metadataColumns.isObject() && !metadataColumns.isEmpty()) {
            if (!deployed.config().path("enableMetadataWithRecommendations").asBoolean(false)) {
                throw PersonalizeStore.invalid("Item metadata needs enableMetadataWithRecommendations.");
            }
            metadataColumns.path("ITEMS").forEach(column -> columns.add(column.asText()));
            catalog = keyed(deployed.region(), deployed.groupArn(), PersonalizeData.ITEMS);
        }
        ArrayNode list = mapper.createArrayNode();
        for (Scored item : scored) {
            ObjectNode entry = list.addObject().put("itemId", item.id()).put("score", item.score());
            if (promoted.contains(item.id())) {
                entry.put("promotionName", promotion);
            }
            if (catalog != null) {
                ObjectNode metadata = entry.putObject("metadata");
                ObjectNode row = catalog.get(item.id());
                for (String column : columns) {
                    JsonNode value = row == null ? null : row.get(column.toUpperCase(Locale.ROOT));
                    if (value != null && !value.isNull()) {
                        metadata.put(column, value.asText());
                    }
                }
            }
        }
        return list;
    }

    public ObjectNode getRecommendations(JsonNode in) {
        Deployed deployed = deployed(in);
        String type = deployed.recipe().type();
        String user = in.path("userId").asText(null);
        String item = in.path("itemId").asText(null);
        if ("PERSONALIZED_RANKING".equals(type) || "PERSONALIZED_ACTIONS".equals(type)
                || "USER_SEGMENTATION".equals(type)) {
            throw PersonalizeStore.invalid("A " + type + " model doesn't answer GetRecommendations.");
        }
        if ("RELATED_ITEMS".equals(type) && item == null) {
            throw PersonalizeStore.invalid("A RELATED_ITEMS model needs an itemId.");
        }
        if ("USER_PERSONALIZATION".equals(type) && user == null) {
            throw PersonalizeStore.invalid("A USER_PERSONALIZATION model needs a userId.");
        }
        int n = count(in, 25, 500);
        ObjectNode model = withHistory(deployed, user);
        Predicate<String> keep = filter(deployed, in.path("filterArn").asText(null), in.path("filterValues"), user,
                item);
        List<Scored> scored = PersonalizeModels.recommend(model, user, item, n, keep);
        String promotionName = null;
        Set<String> promoted = new HashSet<>();
        JsonNode promotions = in.path("promotions");
        if (promotions.isArray() && !promotions.isEmpty()) {
            JsonNode promotion = promotions.get(0);
            promotionName = promotion.path("name").asText("promotion");
            int share = (int) Math.ceil(n * promotion.path("percentPromotedItems").asInt(10) / 100.0);
            Predicate<String> promotedKeep = keep.and(filter(deployed, promotion.path("filterArn").asText(null),
                    promotion.path("filterValues"), user, item));
            List<Scored> picks = PersonalizeModels.recommend(model, user, item, share, promotedKeep);
            picks.forEach(pick -> promoted.add(pick.id()));
            List<Scored> rest = new ArrayList<>(PersonalizeModels.recommend(model, user, item, n + picks.size(),
                    keep.and(candidate -> !promoted.contains(candidate))));
            List<Scored> merged = new ArrayList<>();
            int step = picks.isEmpty() ? n : Math.max(1, n / picks.size());
            int p = 0;
            for (int i = 0; merged.size() < n && (p < picks.size() || !rest.isEmpty()); i++) {
                if (p < picks.size() && (i % step == 0 || rest.isEmpty())) {
                    merged.add(picks.get(p++));
                } else if (!rest.isEmpty()) {
                    merged.add(rest.remove(0));
                }
            }
            scored = merged;
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("itemList", items(deployed, scored, in.path("metadataColumns"), promotionName, promoted));
        out.put("recommendationId", "RID-" + UUID.randomUUID());
        return out;
    }

    public ObjectNode getPersonalizedRanking(JsonNode in) {
        Deployed deployed = campaign(in.path("campaignArn").asText());
        if (!"PERSONALIZED_RANKING".equals(deployed.recipe().type())) {
            throw PersonalizeStore.invalid("GetPersonalizedRanking needs a PERSONALIZED_RANKING campaign.");
        }
        String user = in.path("userId").asText();
        List<String> input = new ArrayList<>();
        in.path("inputList").forEach(item -> input.add(item.asText()));
        if (input.size() > 500) {
            throw PersonalizeStore.invalid("inputList can have at most 500 items.");
        }
        ObjectNode model = withHistory(deployed, user);
        List<Scored> ranked = PersonalizeModels.rank(model, user, input, filter(deployed,
                in.path("filterArn").asText(null), in.path("filterValues"), user, null));
        ObjectNode out = mapper.createObjectNode();
        out.set("personalizedRanking", items(deployed, ranked, in.path("metadataColumns"), null, Set.of()));
        out.put("recommendationId", "RID-" + UUID.randomUUID());
        return out;
    }

    public ObjectNode getActionRecommendations(JsonNode in) {
        Deployed deployed = campaign(in.path("campaignArn").asText());
        if (!"PERSONALIZED_ACTIONS".equals(deployed.recipe().type())) {
            throw PersonalizeStore.invalid("GetActionRecommendations needs a PERSONALIZED_ACTIONS campaign.");
        }
        String user = in.path("userId").asText(null);
        if (user == null) {
            throw PersonalizeStore.invalid("GetActionRecommendations needs a userId.");
        }
        int n = count(in, 5, 100);
        ObjectNode model = withHistory(deployed, user);
        Predicate<String> keep = filter(deployed, in.path("filterArn").asText(null), in.path("filterValues"), user,
                null);
        List<Scored> scored = PersonalizeModels.recommend(model, user, null, n, keep);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("actionList");
        scored.forEach(action -> list.addObject().put("actionId", action.id()).put("score", action.score()));
        out.put("recommendationId", "RID-" + UUID.randomUUID());
        return out;
    }

    // ================================================================ batch

    private Deployed versionModel(JsonNode job) {
        String arn = job.get("solutionVersionArn").asText();
        ObjectNode version = store.require(arn, PersonalizeStore.VERSION, "solution version");
        ObjectNode model = modelOf("model/" + PersonalizeStore.id(version));
        return new Deployed(model, PersonalizeService.recipe(model.get("recipeArn").asText()),
                job.path("batchInferenceJobConfig"), PersonalizeStore.region(version),
                version.get("datasetGroupArn").asText());
    }

    /** The answer to one line of a batch inference job's input. */
    ObjectNode batchLine(JsonNode job, JsonNode request) {
        Deployed deployed = versionModel(job);
        int n = job.path("numResults").asInt(25);
        String user = request.path("userId").asText(null);
        String item = request.path("itemId").asText(null);
        Predicate<String> keep = filter(deployed, job.path("filterArn").asText(null), request.path("filterValues"),
                user, item);
        ObjectNode model = withHistory(deployed, user);
        List<Scored> scored;
        switch (deployed.recipe().type()) {
            case "PERSONALIZED_RANKING" -> {
                if (user == null || !request.path("itemList").isArray()) {
                    throw PersonalizeStore.invalid("A ranking line needs a userId and an itemList.");
                }
                List<String> input = new ArrayList<>();
                request.path("itemList").forEach(entry -> input.add(entry.asText()));
                scored = PersonalizeModels.rank(model, user, input, keep);
            }
            case "RELATED_ITEMS" -> {
                if (item == null) {
                    throw PersonalizeStore.invalid("A related items line needs an itemId.");
                }
                scored = PersonalizeModels.recommend(model, null, item, n, keep);
            }
            case "PERSONALIZED_ACTIONS" -> {
                if (user == null) {
                    throw PersonalizeStore.invalid("An actions line needs a userId.");
                }
                scored = PersonalizeModels.recommend(model, user, null, n, keep);
            }
            default -> {
                if (user == null) {
                    throw PersonalizeStore.invalid("A user personalization line needs a userId.");
                }
                scored = PersonalizeModels.recommend(model, user, null, n, keep);
            }
        }
        ObjectNode output = mapper.createObjectNode();
        ArrayNode ids = output.putArray("PERSONALIZED_ACTIONS".equals(deployed.recipe().type())
                ? "recommendedActions" : "recommendedItems");
        ArrayNode scores = output.putArray("scores");
        scored.forEach(entry -> {
            ids.add(entry.id());
            scores.add(entry.score());
        });
        if ("THEME_GENERATION".equals(job.path("batchInferenceJobMode").asText())) {
            String nameField = job.at("/themeGenerationConfig/fieldsForThemeGeneration/itemName").asText();
            ObjectNode row = keyed(deployed.region(), deployed.groupArn(), PersonalizeData.ITEMS).get(item);
            String title = row == null || !row.hasNonNull(nameField.toUpperCase(Locale.ROOT)) ? item
                    : row.get(nameField.toUpperCase(Locale.ROOT)).asText();
            output.put("theme", "More like " + title);
            ArrayNode relevance = output.putArray("itemsThemeRelevanceScores");
            scored.forEach(entry -> relevance.add(entry.score()));
        }
        return output;
    }

    /** The answer to one line of a batch segment job's input: the users most likely to like the items. */
    ObjectNode segmentLine(JsonNode job, JsonNode request) {
        Deployed deployed = versionModel(job);
        int n = job.path("numResults").asInt(25);
        List<String> items = new ArrayList<>();
        if (request.hasNonNull("itemId")) {
            items.add(request.get("itemId").asText());
        } else if (request.hasNonNull("itemAttributes")) {
            List<PersonalizeFilters.Clause> clauses = PersonalizeFilters.parse("INCLUDE ItemID WHERE "
                    + request.get("itemAttributes").asText());
            Map<String, ObjectNode> catalog = keyed(deployed.region(), deployed.groupArn(), PersonalizeData.ITEMS);
            Predicate<String> match = PersonalizeFilters.predicate(clauses, new PersonalizeFilters.Context(catalog,
                    null, List.of(), null, Map.of()));
            catalog.keySet().stream().filter(match).forEach(items::add);
        } else {
            throw PersonalizeStore.invalid("A segment line needs an itemId or itemAttributes.");
        }
        Map<String, Double> scores = new HashMap<>();
        for (String item : items) {
            for (Scored user : PersonalizeModels.segment(deployed.model(), item, Integer.MAX_VALUE)) {
                scores.merge(user.id(), user.score(), Double::sum);
            }
        }
        List<Scored> ranked = new ArrayList<>();
        scores.forEach((user, score) -> ranked.add(new Scored(user, score)));
        ranked.sort((a, b) -> Double.compare(b.score(), a.score()) != 0 ? Double.compare(b.score(), a.score())
                : a.id().compareTo(b.id()));
        ObjectNode output = mapper.createObjectNode();
        ArrayNode users = output.putArray("usersList");
        ranked.stream().limit(n).forEach(user -> users.add(user.id()));
        return output;
    }

    /** A metric attribution's metrics over interactions. */
    ObjectNode attributionReport(String region, String groupArn, ArrayNode metrics, List<ObjectNode> rows) {
        Map<String, ObjectNode> catalog = keyed(region, groupArn, PersonalizeData.ITEMS);
        ObjectNode report = mapper.createObjectNode();
        ObjectNode values = report.putObject("metrics");
        for (JsonNode metric : metrics) {
            String eventType = metric.path("eventType").asText();
            String expression = metric.path("expression").asText();
            double value = 0;
            for (ObjectNode row : rows) {
                if (!eventType.equalsIgnoreCase(row.path("EVENT_TYPE").asText())) {
                    continue;
                }
                if (expression.toUpperCase(Locale.ROOT).startsWith("SAMPLECOUNT")) {
                    value++;
                } else {
                    String field = expression.substring(expression.indexOf('.') + 1, expression.length() - 1)
                            .toUpperCase(Locale.ROOT);
                    ObjectNode item = catalog.get(row.path("ITEM_ID").asText());
                    value += item == null ? 0 : item.path(field).asDouble(0);
                }
            }
            values.put(metric.path("metricName").asText(), value);
        }
        report.put("datasetGroupArn", groupArn).put("generatedAt", PersonalizeStore.now());
        return report;
    }

    // ================================================================ events

    private ObjectNode tracker(String trackingId) {
        for (String region : regions()) {
            for (ObjectNode tracker : store.all(region, PersonalizeStore.TRACKER)) {
                if (trackingId.equals(tracker.path("trackingId").asText())) {
                    return tracker;
                }
            }
        }
        return null;
    }

    private Set<String> regions() {
        Set<String> regions = new HashSet<>();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(PersonalizeStore.TRACKER)) {
            if (entry.accountId().equals(store.account())) {
                regions.add(PersonalizeStore.region(entry.value()));
            }
        }
        return regions;
    }

    private Map<String, Field> schemaOf(ObjectNode dataset) {
        ObjectNode schema = store.require(dataset.get("schemaArn").asText(), PersonalizeStore.SCHEMA, "schema");
        return PersonalizeData.fields(mapper, schema.get("schema").asText());
    }

    private void properties(ObjectNode row, Map<String, Field> fields, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        JsonNode properties;
        try {
            properties = mapper.readTree(json);
        } catch (Exception e) {
            throw PersonalizeStore.invalid("The properties aren't valid JSON.");
        }
        if (!properties.isObject()) {
            throw PersonalizeStore.invalid("The properties must be a JSON object.");
        }
        properties.properties().forEach(entry -> {
            Field field = fields.get(PersonalizeData.fieldName(entry.getKey()));
            if (field == null) {
                throw PersonalizeStore.invalid("The property " + entry.getKey() + " isn't a field of the dataset's "
                        + "schema.");
            }
            PersonalizeData.set(row, field, entry.getValue());
        });
    }

    private void record(ObjectNode dataset, List<ObjectNode> rows) {
        String key = "dataset/" + PersonalizeStore.id(dataset);
        ObjectNode data = store.data(store.account(), key).orElse(null);
        store.putData(store.account(), key, PersonalizeData.append(mapper, data,
                dataset.get("datasetType").asText(), rows, "put", false));
    }

    private ObjectNode activeDataset(String region, String groupArn, String type, String label) {
        ObjectNode dataset = datasetOf(region, groupArn, type);
        if (dataset == null) {
            throw PersonalizeStore.notFound("The dataset group has no " + label + " dataset.");
        }
        if (!"ACTIVE".equals(dataset.path("status").asText())) {
            throw PersonalizeStore.inUse("The " + label + " dataset is " + dataset.path("status").asText() + ".");
        }
        return dataset;
    }

    private static double seconds(JsonNode date) {
        return date.isNumber() ? date.asDouble() : Instant.parse(date.asText()).toEpochMilli() / 1000.0;
    }

    public ObjectNode putEvents(JsonNode in) {
        ObjectNode tracker = tracker(in.path("trackingId").asText());
        if (tracker == null) {
            throw PersonalizeStore.invalid("The trackingId " + in.path("trackingId").asText()
                    + " doesn't belong to an event tracker.");
        }
        ObjectNode dataset = activeDataset(PersonalizeStore.region(tracker), tracker.get("datasetGroupArn").asText(),
                PersonalizeData.INTERACTIONS, "Interactions");
        Map<String, Field> fields = schemaOf(dataset);
        String user = in.path("userId").asText(null);
        List<ObjectNode> rows = new ArrayList<>();
        for (JsonNode event : in.path("eventList")) {
            ObjectNode row = mapper.createObjectNode();
            String item = event.path("itemId").asText(null);
            if (item == null && fields.containsKey("ITEM_ID") && !event.path("impression").isArray()) {
                throw PersonalizeStore.invalid("An event needs an itemId.");
            }
            row.put("USER_ID", user == null ? in.path("sessionId").asText() : user);
            if (item != null) {
                row.put("ITEM_ID", item);
            }
            row.put("TIMESTAMP", (long) seconds(event.path("sentAt")));
            row.put("EVENT_TYPE", event.path("eventType").asText());
            if (event.has("eventValue")) {
                row.put("EVENT_VALUE", event.get("eventValue").asDouble());
            }
            if (event.hasNonNull("recommendationId")) {
                row.put("RECOMMENDATION_ID", event.get("recommendationId").asText());
            }
            if (event.path("impression").isArray()) {
                List<String> shown = new ArrayList<>();
                event.get("impression").forEach(id -> shown.add(id.asText()));
                row.put("IMPRESSION", String.join("|", shown));
            }
            if (event.hasNonNull("eventId")) {
                row.put("EVENT_ID", event.get("eventId").asText());
            }
            if (event.has("metricAttribution")) {
                row.put("EVENT_ATTRIBUTION_SOURCE", event.at("/metricAttribution/eventAttributionSource").asText());
            }
            properties(row, fields, event.path("properties").asText(null));
            rows.add(row);
        }
        record(dataset, rows);
        return mapper.createObjectNode();
    }

    private ObjectNode datasetFor(JsonNode in, String type, String label) {
        ObjectNode dataset = store.require(in.path("datasetArn").asText(), PersonalizeStore.DATASET, "dataset");
        if (!type.equals(dataset.path("datasetType").asText())) {
            throw PersonalizeStore.invalid("The dataset is a " + dataset.path("datasetType").asText()
                    + " dataset, not " + label + ".");
        }
        if (!"ACTIVE".equals(dataset.path("status").asText())) {
            throw PersonalizeStore.inUse("The dataset is " + dataset.path("status").asText() + ".");
        }
        return dataset;
    }

    private ObjectNode putThings(JsonNode in, String type, String label, String list, String idMember) {
        ObjectNode dataset = datasetFor(in, type, label);
        Map<String, Field> fields = schemaOf(dataset);
        String key = PersonalizeData.KEYS.get(type);
        List<ObjectNode> rows = new ArrayList<>();
        for (JsonNode thing : in.path(list)) {
            ObjectNode row = mapper.createObjectNode();
            row.put(key, thing.path(idMember).asText());
            properties(row, fields, thing.path("properties").asText(null));
            rows.add(row);
        }
        record(dataset, rows);
        return mapper.createObjectNode();
    }

    public ObjectNode putItems(JsonNode in) {
        return putThings(in, PersonalizeData.ITEMS, "an Items dataset", "items", "itemId");
    }

    public ObjectNode putUsers(JsonNode in) {
        return putThings(in, PersonalizeData.USERS, "a Users dataset", "users", "userId");
    }

    public ObjectNode putActions(JsonNode in) {
        return putThings(in, PersonalizeData.ACTIONS, "an Actions dataset", "actions", "actionId");
    }

    public ObjectNode putActionInteractions(JsonNode in) {
        ObjectNode tracker = tracker(in.path("trackingId").asText());
        if (tracker == null) {
            throw PersonalizeStore.notFound("The trackingId " + in.path("trackingId").asText()
                    + " doesn't belong to an event tracker.");
        }
        ObjectNode dataset = activeDataset(PersonalizeStore.region(tracker), tracker.get("datasetGroupArn").asText(),
                PersonalizeData.ACTION_INTERACTIONS, "Action interactions");
        Map<String, Field> fields = schemaOf(dataset);
        List<ObjectNode> rows = new ArrayList<>();
        for (JsonNode interaction : in.path("actionInteractions")) {
            ObjectNode row = mapper.createObjectNode();
            row.put("ACTION_ID", interaction.path("actionId").asText());
            row.put("USER_ID", interaction.path("userId").asText(interaction.path("sessionId").asText()));
            row.put("TIMESTAMP", (long) seconds(interaction.path("timestamp")));
            row.put("EVENT_TYPE", interaction.path("eventType").asText());
            if (interaction.hasNonNull("recommendationId")) {
                row.put("RECOMMENDATION_ID", interaction.get("recommendationId").asText());
            }
            if (interaction.path("impression").isArray()) {
                List<String> shown = new ArrayList<>();
                interaction.get("impression").forEach(id -> shown.add(id.asText()));
                row.put("IMPRESSION", String.join("|", shown));
            }
            if (interaction.hasNonNull("eventId")) {
                row.put("EVENT_ID", interaction.get("eventId").asText());
            }
            properties(row, fields, interaction.path("properties").asText(null));
            rows.add(row);
        }
        record(dataset, rows);
        return mapper.createObjectNode();
    }
}
