package io.github.hectorvent.floci.services.qapps;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.qapps.model.QApp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amazon Q Apps restJson1 — Q App lifecycle. Tag APIs share {@code /tags/{arn}}
 * via {@link TagHandler} using ARN service {@code qapps}.
 * GetQApp/DeleteQApp return ResourceNotFoundException for missing apps.
 */
@ApplicationScoped
public class QAppsService implements TagHandler {

    static final String SERVICE = "qapps";
    private static final String USER = "floci";
    private static final Pattern TITLE_REF = Pattern.compile("@([^@\\s]+(?:\\s+[^@\\s]+)*)");
    private static final Set<String> CARD_KEYS =
            Set.of("textInput", "qQuery", "qPlugin", "fileUpload", "formInput");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final StorageBackend<String, QApp> apps;
    private final RegionResolver regionResolver;

    @Inject
    public QAppsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create(SERVICE, "qapps.json", new TypeReference<Map<String, QApp>>() {
        }), regionResolver);
    }

    QAppsService(StorageBackend<String, QApp> apps, RegionResolver regionResolver) {
        this.apps = apps;
        this.regionResolver = regionResolver;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    public synchronized QApp createQApp(String region, String instanceId, JsonNode request) {
        requireInstanceId(instanceId);
        requireObject(request, "Request body");
        String title = requireText(request, "title");
        validateTitle(title);
        JsonNode definition = requireObjectField(request, "appDefinition");
        ConvertedDefinition converted = convertDefinition(definition);
        String description = textOrNull(request, "description");
        Map<String, String> tags = readTags(request);

        String appId = UUID.randomUUID().toString();
        String now = timestamp();
        QApp app = new QApp();
        app.setAppId(appId);
        app.setAppArn(arn(region, instanceId, appId));
        app.setInstanceId(instanceId);
        app.setTitle(title);
        app.setDescription(description);
        app.setInitialPrompt(converted.initialPrompt());
        app.setAppVersion(1);
        app.setStatus("PUBLISHED");
        app.setCreatedAt(now);
        app.setCreatedBy(USER);
        app.setUpdatedAt(now);
        app.setUpdatedBy(USER);
        app.setCards(converted.cards());
        app.setTags(tags);
        apps.put(storageKey(region, instanceId, appId), app);
        return app;
    }

    public QApp getQApp(String region, String instanceId, String appId) {
        requireInstanceId(instanceId);
        requireAppId(appId);
        return requireApp(region, instanceId, appId);
    }

    public synchronized QApp updateQApp(String region, String instanceId, JsonNode request) {
        requireInstanceId(instanceId);
        requireObject(request, "Request body");
        String appId = requireText(request, "appId");
        QApp current = requireApp(region, instanceId, appId);

        String title = current.getTitle();
        if (request.has("title") && !request.get("title").isNull()) {
            title = requireText(request, "title");
            validateTitle(title);
        }
        String description = current.getDescription();
        if (request.has("description")) {
            description = textOrNull(request, "description");
        }
        String initialPrompt = current.getInitialPrompt();
        JsonNode cards = current.getCards();
        if (request.has("appDefinition") && !request.get("appDefinition").isNull()) {
            ConvertedDefinition converted = convertDefinition(requireObjectField(request, "appDefinition"));
            initialPrompt = converted.initialPrompt();
            cards = converted.cards();
        }

        QApp updated = copy(current);
        updated.setTitle(title);
        updated.setDescription(description);
        updated.setInitialPrompt(initialPrompt);
        updated.setCards(cards);
        updated.setAppVersion(current.getAppVersion() + 1);
        updated.setUpdatedAt(timestamp());
        updated.setUpdatedBy(USER);
        apps.put(storageKey(region, instanceId, appId), updated);
        return updated;
    }

    public synchronized void deleteQApp(String region, String instanceId, String appId) {
        requireInstanceId(instanceId);
        requireAppId(appId);
        String key = storageKey(region, instanceId, appId);
        if (apps.get(key).isEmpty()) {
            throw resourceNotFound(appId);
        }
        apps.delete(key);
    }

    public void requireKnownInstance(String instanceId) {
        requireInstanceId(instanceId);
        if (!instanceKnown(instanceId)) {
            throw instanceNotFound(instanceId);
        }
    }

    /**
     * ListQApps and PredictQApp are not modeled with ResourceNotFoundException.
     * Live AWS rejects callers who are not Identity Center users of a Q Business
     * instance with UnauthorizedException ("Unauthorized").
     */
    public void requireAuthorizedInstance(String instanceId) {
        requireInstanceId(instanceId);
        if (!instanceKnown(instanceId)) {
            throw unauthorized();
        }
    }

    public Page listQApps(String region, String instanceId, Integer limit, String nextToken) {
        requireAuthorizedInstance(instanceId);
        int maxResults = limit == null ? 100 : limit;
        if (maxResults < 1 || maxResults > 100) {
            throw validation("limit must be between 1 and 100.");
        }
        String prefix = region + "::" + instanceId + "::";
        List<QApp> matches = new ArrayList<>(apps.scan(key -> key.startsWith(prefix)));
        matches.sort(Comparator.comparing(QApp::getCreatedAt).thenComparing(QApp::getAppId));
        int offset = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                offset = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw validation("nextToken is invalid.");
            }
            if (offset < 0 || offset > matches.size()) {
                throw validation("nextToken is invalid.");
            }
        }
        int end = Math.min(offset + maxResults, matches.size());
        String responseToken = end < matches.size() ? String.valueOf(end) : null;
        return new Page(matches.subList(offset, end), responseToken);
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireAppByArn(region, arn).getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        QApp app = requireAppByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(app.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        if (current.size() > 50) {
            throw validation("A resource can have at most 50 tags.");
        }
        QApp updated = copy(app);
        updated.setTags(current);
        apps.put(storageKey(region, app.getInstanceId(), app.getAppId()), updated);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        QApp app = requireAppByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(app.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        QApp updated = copy(app);
        updated.setTags(current);
        apps.put(storageKey(region, app.getInstanceId(), app.getAppId()), updated);
    }

    private QApp requireApp(String region, String instanceId, String appId) {
        return apps.get(storageKey(region, instanceId, appId)).orElseThrow(() -> resourceNotFound(appId));
    }

    private QApp requireAppByArn(String region, String arn) {
        AppRef ref = parseAppArn(arn);
        return requireApp(region, ref.instanceId(), ref.appId());
    }

    private AppRef parseAppArn(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw resourceNotFound(arn);
        }
        if (!SERVICE.equals(parsed.service()) || parsed.resource() == null) {
            throw resourceNotFound(arn);
        }
        String resource = parsed.resource();
        String prefix = "application/";
        if (!resource.startsWith(prefix)) {
            throw resourceNotFound(arn);
        }
        String rest = resource.substring(prefix.length());
        int split = rest.indexOf("/qapp/");
        if (split < 1) {
            throw resourceNotFound(arn);
        }
        String instanceId = rest.substring(0, split);
        String appId = rest.substring(split + "/qapp/".length());
        if (instanceId.isBlank() || appId.isBlank()) {
            throw resourceNotFound(arn);
        }
        return new AppRef(instanceId, appId);
    }

    private String arn(String region, String instanceId, String appId) {
        return regionResolver.buildArn(SERVICE, region, "application/" + instanceId + "/qapp/" + appId);
    }

    private static String storageKey(String region, String instanceId, String appId) {
        return region + "::" + instanceId + "::" + appId;
    }

    private static String timestamp() {
        return Instant.now().toString();
    }

    private static QApp copy(QApp source) {
        QApp copy = new QApp();
        copy.setAppId(source.getAppId());
        copy.setAppArn(source.getAppArn());
        copy.setInstanceId(source.getInstanceId());
        copy.setTitle(source.getTitle());
        copy.setDescription(source.getDescription());
        copy.setInitialPrompt(source.getInitialPrompt());
        copy.setAppVersion(source.getAppVersion());
        copy.setStatus(source.getStatus());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setUpdatedBy(source.getUpdatedBy());
        copy.setCards(source.getCards());
        copy.setTags(source.getTags());
        return copy;
    }

    private static ConvertedDefinition convertDefinition(JsonNode definition) {
        requireObject(definition, "appDefinition");
        JsonNode cardsNode = definition.get("cards");
        if (cardsNode == null || !cardsNode.isArray() || cardsNode.isEmpty()) {
            throw validation("appDefinition.cards must be a non-empty array.");
        }
        Map<String, String> titlesById = new LinkedHashMap<>();
        for (JsonNode card : cardsNode) {
            ObjectNode inner = innerCard(card);
            String id = requireText(inner, "id");
            String title = requireText(inner, "title");
            titlesById.put(id, title);
        }
        ArrayNode converted = JsonNodeFactory.instance.arrayNode();
        for (JsonNode card : cardsNode) {
            converted.add(convertCard(card, titlesById));
        }
        return new ConvertedDefinition(textOrNull(definition, "initialPrompt"), converted);
    }

    private static ObjectNode convertCard(JsonNode card, Map<String, String> titlesById) {
        requireObject(card, "card");
        String kind = cardKind(card);
        ObjectNode inner = innerCard(card);
        ObjectNode copy = inner.deepCopy();
        if (!copy.has("dependencies") || !copy.get("dependencies").isArray()) {
            copy.set("dependencies", dependenciesFor(copy, titlesById));
        }
        if ("qQuery".equals(kind) && (!copy.has("outputSource") || copy.get("outputSource").isNull())) {
            copy.put("outputSource", "llm");
        }
        ObjectNode wrapper = JsonNodeFactory.instance.objectNode();
        wrapper.set(kind, copy);
        return wrapper;
    }

    private static ArrayNode dependenciesFor(ObjectNode inner, Map<String, String> titlesById) {
        ArrayNode dependencies = JsonNodeFactory.instance.arrayNode();
        JsonNode prompt = inner.get("prompt");
        if (prompt == null || !prompt.isTextual()) {
            return dependencies;
        }
        Matcher matcher = TITLE_REF.matcher(prompt.textValue());
        while (matcher.find()) {
            String title = matcher.group(1).trim();
            for (Map.Entry<String, String> entry : titlesById.entrySet()) {
                if (title.equals(entry.getValue()) && !entry.getKey().equals(textOrNull(inner, "id"))) {
                    dependencies.add(entry.getKey());
                }
            }
        }
        return dependencies;
    }

    private static ObjectNode innerCard(JsonNode card) {
        String kind = cardKind(card);
        JsonNode inner = card.get(kind);
        requireObject(inner, kind);
        return (ObjectNode) inner;
    }

    private static String cardKind(JsonNode card) {
        requireObject(card, "card");
        String found = null;
        var fields = card.fieldNames();
        while (fields.hasNext()) {
            String name = fields.next();
            if (!CARD_KEYS.contains(name) || card.get(name) == null || card.get(name).isNull()) {
                continue;
            }
            if (found != null) {
                throw validation("A card must specify exactly one card type.");
            }
            found = name;
        }
        if (found == null) {
            throw validation("A card must specify textInput, qQuery, qPlugin, fileUpload, or formInput.");
        }
        return found;
    }

    private boolean instanceKnown(String instanceId) {
        String needle = "::" + instanceId + "::";
        return !apps.scan(key -> key.contains(needle)).isEmpty();
    }

    private static void requireInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw validation("instance-id is required.");
        }
    }

    private static void requireAppId(String appId) {
        if (appId == null || appId.isBlank()) {
            throw validation("appId is required.");
        }
        if (!UUID_PATTERN.matcher(appId).matches()) {
            throw validation("appId must be a UUID.");
        }
    }

    private static void validateTitle(String title) {
        if (title.length() > 100) {
            throw validation("title must contain at most 100 characters.");
        }
    }

    private static Map<String, String> readTags(JsonNode request) {
        if (request == null || !request.has("tags") || request.get("tags").isNull()) {
            return new LinkedHashMap<>();
        }
        JsonNode tagsNode = request.get("tags");
        if (!tagsNode.isObject() || tagsNode.size() > 50) {
            throw validation("tags must be an object with at most 50 entries.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (entry.getKey().isBlank() || value == null || !value.isTextual()) {
                throw validation("tags contains an invalid key or value.");
            }
            tags.put(entry.getKey(), value.textValue());
        });
        return tags;
    }

    private static JsonNode requireObjectField(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        requireObject(value, field);
        return value;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw validation(field + " is required.");
        }
        String text = value.textValue();
        if (text == null || text.isBlank()) {
            throw validation(field + " is required.");
        }
        return text;
    }

    private static String textOrNull(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        String text = value.textValue();
        return text == null || text.isBlank() ? null : text;
    }

    static AwsException resourceNotFound(String resourceId) {
        return new AwsException(
                "ResourceNotFoundException",
                "The specified Q App does not exist.",
                404,
                Map.of("resourceId", resourceId, "resourceType", "QApp"));
    }

    static AwsException instanceNotFound(String instanceId) {
        return new AwsException(
                "ResourceNotFoundException",
                "Application " + instanceId + " could not be found.",
                404,
                Map.of("resourceId", instanceId, "resourceType", "Application"));
    }

    static AwsException unauthorized() {
        return new AwsException("UnauthorizedException", "Unauthorized", 401);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public record Page(List<QApp> apps, String nextToken) {
        public Page {
            apps = List.copyOf(apps);
        }
    }

    private record ConvertedDefinition(String initialPrompt, JsonNode cards) {
    }

    private record AppRef(String instanceId, String appId) {
    }
}
