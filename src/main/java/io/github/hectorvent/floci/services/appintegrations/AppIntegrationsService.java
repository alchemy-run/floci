package io.github.hectorvent.floci.services.appintegrations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appintegrations.model.Application;
import io.github.hectorvent.floci.services.appintegrations.model.DataIntegration;
import io.github.hectorvent.floci.services.appintegrations.model.DataIntegrationAssociation;
import io.github.hectorvent.floci.services.appintegrations.model.EventIntegration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon AppIntegrations restJson1 — applications, data integrations, event integrations.
 *
 * <p>Tag APIs share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}
 * using ARN service {@code app-integrations}.
 */
@ApplicationScoped
public class AppIntegrationsService implements TagHandler {

    static final String SERVICE = "app-integrations";
    private static final String TOKEN_PREFIX = "appintegrations:v1:";
    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int MAX_RESULTS = 50;
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9/._\\-]{1,255}");

    private final StorageBackend<String, Application> store;
    private final StorageBackend<String, DataIntegration> dataIntegrations;
    private final StorageBackend<String, EventIntegration> eventIntegrations;
    private final RegionResolver regionResolver;

    @Inject
    public AppIntegrationsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(
                storageFactory.create(
                        "appintegrations",
                        "appintegrations-applications.json",
                        new TypeReference<Map<String, Application>>() {
                        }),
                storageFactory.create(
                        "appintegrations",
                        "appintegrations-data-integrations.json",
                        new TypeReference<Map<String, DataIntegration>>() {
                        }),
                storageFactory.create(
                        "appintegrations",
                        "appintegrations-event-integrations.json",
                        new TypeReference<Map<String, EventIntegration>>() {
                        }),
                regionResolver);
    }

    AppIntegrationsService(StorageBackend<String, Application> store, RegionResolver regionResolver) {
        this(store, new InMemoryStorage<>(), new InMemoryStorage<>(), regionResolver);
    }

    AppIntegrationsService(
            StorageBackend<String, Application> store,
            StorageBackend<String, DataIntegration> dataIntegrations,
            RegionResolver regionResolver) {
        this(store, dataIntegrations, new InMemoryStorage<>(), regionResolver);
    }

    AppIntegrationsService(
            StorageBackend<String, Application> store,
            StorageBackend<String, DataIntegration> dataIntegrations,
            StorageBackend<String, EventIntegration> eventIntegrations,
            RegionResolver regionResolver) {
        this.store = store;
        this.dataIntegrations = dataIntegrations;
        this.eventIntegrations = eventIntegrations;
        this.regionResolver = regionResolver;
    }

    AppIntegrationsService(StorageBackend<String, Application> store) {
        this(store, new RegionResolver("us-east-1", "000000000000"));
    }

    public synchronized Application createApplication(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        String namespace = requireText(request, "Namespace");
        JsonNode sourceConfig = requireObjectNode(request, "ApplicationSourceConfig");
        JsonNode urlConfig = requireObjectNode(sourceConfig, "ExternalUrlConfig");
        String accessUrl = requireText(urlConfig, "AccessUrl");
        String account = regionResolver.getAccountId();

        for (Application existing : store.scan(key -> key.startsWith(prefix(account, region)))) {
            if (namespace.equals(existing.getNamespace())) {
                throw new AwsException("InvalidRequestException", "Namespace already in use", 400);
            }
        }

        long now = now();
        String id = UUID.randomUUID().toString();
        Application application = new Application();
        application.setId(id);
        application.setArn(arn(region, account, "application/" + id));
        application.setName(name);
        application.setNamespace(namespace);
        application.setDescription(optionalText(request, "Description"));
        application.setAccessUrl(accessUrl);
        application.setApprovedOrigins(readStringList(urlConfig, "ApprovedOrigins"));
        application.setPermissions(readStringList(request, "Permissions"));
        application.setTags(readStringMap(request, "Tags"));
        application.setCreatedTime(now);
        application.setLastModifiedTime(now);
        store.put(storageKey(account, region, id), application);
        return application;
    }

    public Application getApplication(String region, String identifier) {
        return requireApplication(region, identifier);
    }

    public synchronized Application updateApplication(String region, String identifier, JsonNode request) {
        requireObject(request, "Request body");
        Application application = requireApplication(region, identifier);
        boolean changed = false;
        if (request.has("Name") && !request.get("Name").isNull()) {
            application.setName(requireText(request, "Name"));
            changed = true;
        }
        if (request.has("Description")) {
            application.setDescription(textOrNull(request, "Description"));
            changed = true;
        }
        if (request.has("ApplicationSourceConfig") && !request.get("ApplicationSourceConfig").isNull()) {
            JsonNode sourceConfig = requireObjectNode(request, "ApplicationSourceConfig");
            JsonNode urlConfig = requireObjectNode(sourceConfig, "ExternalUrlConfig");
            application.setAccessUrl(requireText(urlConfig, "AccessUrl"));
            if (urlConfig.has("ApprovedOrigins")) {
                application.setApprovedOrigins(readStringList(urlConfig, "ApprovedOrigins"));
            }
            changed = true;
        }
        if (request.has("Permissions")) {
            application.setPermissions(readStringList(request, "Permissions"));
            changed = true;
        }
        if (changed) {
            application.setLastModifiedTime(now());
            store.put(storageKey(regionResolver.getAccountId(), region, application.getId()), application);
        }
        return application;
    }

    public synchronized void deleteApplication(String region, String identifier) {
        Application application = requireApplication(region, identifier);
        store.delete(storageKey(regionResolver.getAccountId(), region, application.getId()));
    }

    public Page<Application> listApplications(String region, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        String account = regionResolver.getAccountId();
        List<Application> applications = store.scan(key -> key.startsWith(prefix(account, region)));
        applications.sort(Comparator.comparing(Application::getName, Comparator.nullsLast(String::compareTo))
                .thenComparing(Application::getId));
        return page(applications, maxResults, nextToken);
    }

    public synchronized DataIntegration createDataIntegration(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        validateDataIntegrationName(name);
        String kmsKey = requireText(request, "KmsKey");
        String account = regionResolver.getAccountId();
        String clientToken = optionalText(request, "ClientToken");

        if (clientToken != null) {
            for (DataIntegration existing : dataIntegrations.scan(key -> key.startsWith(prefix(account, region)))) {
                if (clientToken.equals(existing.getClientToken())) {
                    if (!name.equals(existing.getName()) || !kmsKey.equals(existing.getKmsKey())) {
                        throw new AwsException(
                                "InvalidRequestException",
                                "ClientToken was already used with a different request.",
                                400);
                    }
                    return existing;
                }
            }
        }

        for (DataIntegration existing : dataIntegrations.scan(key -> key.startsWith(prefix(account, region)))) {
            if (name.equals(existing.getName())) {
                throw new AwsException(
                        "DuplicateResourceException",
                        "A data integration named " + name + " already exists.",
                        409);
            }
        }

        String id = UUID.randomUUID().toString();
        DataIntegration integration = new DataIntegration();
        integration.setId(id);
        integration.setArn(arn(region, account, "data-integration/" + id));
        integration.setName(name);
        integration.setDescription(optionalText(request, "Description"));
        integration.setKmsKey(kmsKey);
        integration.setClientToken(clientToken);
        integration.setSourceURI(optionalText(request, "SourceURI"));
        if (request.has("ScheduleConfig") && !request.get("ScheduleConfig").isNull()) {
            integration.setScheduleConfiguration(requireObjectNode(request, "ScheduleConfig").deepCopy());
        }
        if (request.has("FileConfiguration") && !request.get("FileConfiguration").isNull()) {
            integration.setFileConfiguration(requireObjectNode(request, "FileConfiguration").deepCopy());
        }
        if (request.has("ObjectConfiguration") && !request.get("ObjectConfiguration").isNull()) {
            integration.setObjectConfiguration(requireObjectNode(request, "ObjectConfiguration").deepCopy());
        }
        integration.setTags(readStringMap(request, "Tags"));
        integration.setAssociations(new ArrayList<>());
        dataIntegrations.put(storageKey(account, region, id), integration);
        return integration;
    }

    public DataIntegration getDataIntegration(String region, String identifier) {
        return requireDataIntegration(region, identifier);
    }

    public synchronized DataIntegration updateDataIntegration(String region, String identifier, JsonNode request) {
        requireObject(request, "Request body");
        DataIntegration integration = requireDataIntegration(region, identifier);
        String account = regionResolver.getAccountId();
        boolean changed = false;
        if (request.has("Name") && !request.get("Name").isNull()) {
            String name = requireText(request, "Name");
            validateDataIntegrationName(name);
            for (DataIntegration existing : dataIntegrations.scan(key -> key.startsWith(prefix(account, region)))) {
                if (name.equals(existing.getName()) && !existing.getId().equals(integration.getId())) {
                    throw new AwsException(
                            "DuplicateResourceException",
                            "A data integration named " + name + " already exists.",
                            409);
                }
            }
            integration.setName(name);
            changed = true;
        }
        if (request.has("Description") && !request.get("Description").isNull()) {
            String description = requireText(request, "Description");
            if (description.length() > 1000) {
                throw new AwsException("InvalidRequestException", "Description must be at most 1000 characters.", 400);
            }
            integration.setDescription(description);
            changed = true;
        }
        if (changed) {
            dataIntegrations.put(storageKey(account, region, integration.getId()), integration);
        }
        return integration;
    }

    public synchronized void deleteDataIntegration(String region, String identifier) {
        DataIntegration integration = requireDataIntegration(region, identifier);
        if (integration.getAssociations() != null && !integration.getAssociations().isEmpty()) {
            throw new AwsException(
                    "InvalidRequestException",
                    "DataIntegration has associations and cannot be deleted.",
                    400);
        }
        dataIntegrations.delete(storageKey(regionResolver.getAccountId(), region, integration.getId()));
    }

    public Page<DataIntegration> listDataIntegrations(String region, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        String account = regionResolver.getAccountId();
        List<DataIntegration> items = dataIntegrations.scan(key -> key.startsWith(prefix(account, region)));
        items.sort(Comparator.comparing(DataIntegration::getName, Comparator.nullsLast(String::compareTo))
                .thenComparing(DataIntegration::getId));
        return page(items, maxResults, nextToken);
    }

    public void denyCreateDataIntegrationAssociation(String region, String identifier) {
        DataIntegration integration = requireDataIntegration(region, identifier);
        String account = regionResolver.getAccountId();
        throw new AwsException(
                "AccessDeniedException",
                "User: arn:aws:iam::" + account + ":root is not authorized to perform: "
                        + "app-integrations:CreateDataIntegrationAssociation on resource: "
                        + integration.getArn()
                        + " with an explicit deny in a resource-based policy",
                403);
    }

    public synchronized EventIntegration createEventIntegration(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        validateDataIntegrationName(name);
        String bus = requireText(request, "EventBridgeBus");
        JsonNode eventFilter = requireObjectNode(request, "EventFilter");
        requireText(eventFilter, "Source");
        String account = regionResolver.getAccountId();
        if (eventIntegrations.get(storageKey(account, region, name)).isPresent()) {
            throw new AwsException(
                    "DuplicateResourceException",
                    "An event integration with name " + name + " already exists.",
                    409);
        }

        EventIntegration integration = new EventIntegration();
        integration.setName(name);
        integration.setEventIntegrationArn(arn(region, account, "event-integration/" + name));
        integration.setDescription(optionalText(request, "Description"));
        integration.setEventBridgeBus(bus);
        integration.setEventFilter(eventFilter);
        integration.setTags(readStringMap(request, "Tags"));
        eventIntegrations.put(storageKey(account, region, name), integration);
        return integration;
    }

    public EventIntegration getEventIntegration(String region, String name) {
        return requireEventIntegration(region, name);
    }

    public synchronized EventIntegration updateEventIntegration(String region, String name, JsonNode request) {
        requireObject(request, "Request body");
        EventIntegration integration = requireEventIntegration(region, name);
        if (request.has("Description") && !request.get("Description").isNull()) {
            integration.setDescription(requireText(request, "Description"));
        }
        eventIntegrations.put(
                storageKey(regionResolver.getAccountId(), region, integration.getName()), integration);
        return integration;
    }

    public synchronized void deleteEventIntegration(String region, String name) {
        EventIntegration integration = requireEventIntegration(region, name);
        eventIntegrations.delete(storageKey(regionResolver.getAccountId(), region, integration.getName()));
    }

    public Page<EventIntegration> listEventIntegrations(String region, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        String account = regionResolver.getAccountId();
        List<EventIntegration> items = eventIntegrations.scan(key -> key.startsWith(prefix(account, region)));
        items.sort(Comparator.comparing(EventIntegration::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResults, nextToken);
    }

    public Page<JsonNode> listEventIntegrationAssociations(
            String region, String name, String maxResultsValue, String nextToken) {
        requireEventIntegration(region, name);
        return page(List.of(), parseMaxResults(maxResultsValue), nextToken);
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        TaggedResource resource = requireTagged(region, arn);
        return Map.copyOf(resource.tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        TaggedResource resource = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(resource.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        resource.applyTags(current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        TaggedResource resource = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(resource.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        resource.applyTags(current);
    }

    private Application requireApplication(String region, String identifier) {
        String decoded = decode(identifier);
        String account = regionResolver.getAccountId();
        String id = decoded;
        if (decoded.startsWith("arn:")) {
            AwsArnUtils.Arn parsed;
            try {
                parsed = AwsArnUtils.parse(decoded);
            } catch (IllegalArgumentException e) {
                throw notFound("Application " + decoded + " not found.");
            }
            if (!SERVICE.equals(parsed.service()) || !parsed.resource().startsWith("application/")) {
                throw notFound("Application " + decoded + " not found.");
            }
            id = parsed.resource().substring("application/".length());
        }
        Application application = store.get(storageKey(account, region, id)).orElse(null);
        if (application == null) {
            throw notFound("Application " + decoded + " not found.");
        }
        if (decoded.startsWith("arn:") && !decoded.equals(application.getArn())) {
            throw notFound("Application " + decoded + " not found.");
        }
        return application;
    }

    private DataIntegration requireDataIntegration(String region, String identifier) {
        String decoded = decode(identifier);
        String account = regionResolver.getAccountId();
        String id = decoded;
        if (decoded.startsWith("arn:")) {
            AwsArnUtils.Arn parsed;
            try {
                parsed = AwsArnUtils.parse(decoded);
            } catch (IllegalArgumentException e) {
                throw notFound("Data integration " + decoded + " does not exist.");
            }
            if (!SERVICE.equals(parsed.service()) || !parsed.resource().startsWith("data-integration/")) {
                throw notFound("Data integration " + decoded + " does not exist.");
            }
            id = parsed.resource().substring("data-integration/".length());
        }
        DataIntegration integration = dataIntegrations.get(storageKey(account, region, id)).orElse(null);
        if (integration == null) {
            throw notFound("Data integration " + decoded + " does not exist.");
        }
        return integration;
    }

    private TaggedResource requireTagged(String region, String arn) {
        String decoded = decode(arn);
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decoded);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidRequestException", "Invalid resource ARN: " + decoded, 400);
        }
        if (!SERVICE.equals(parsed.service())) {
            throw notFound("Resource " + decoded + " does not exist.");
        }
        String resource = parsed.resource();
        if (resource.startsWith("application/")) {
            Application application = requireApplication(region, decoded);
            return new TaggedResource() {
                @Override
                public Map<String, String> tags() {
                    return application.getTags() == null ? Map.of() : application.getTags();
                }

                @Override
                public void applyTags(Map<String, String> tags) {
                    application.setTags(tags);
                    application.setLastModifiedTime(now());
                    store.put(storageKey(regionResolver.getAccountId(), region, application.getId()), application);
                }
            };
        }
        if (resource.startsWith("data-integration/")) {
            DataIntegration integration = requireDataIntegration(region, decoded);
            return new TaggedResource() {
                @Override
                public Map<String, String> tags() {
                    return integration.getTags() == null ? Map.of() : integration.getTags();
                }

                @Override
                public void applyTags(Map<String, String> tags) {
                    integration.setTags(tags);
                    dataIntegrations.put(
                            storageKey(regionResolver.getAccountId(), region, integration.getId()), integration);
                }
            };
        }
        if (resource.startsWith("event-integration/")) {
            EventIntegration integration = requireEventIntegration(region, decoded);
            return new TaggedResource() {
                @Override
                public Map<String, String> tags() {
                    return integration.getTags() == null ? Map.of() : integration.getTags();
                }

                @Override
                public void applyTags(Map<String, String> tags) {
                    integration.setTags(tags);
                    eventIntegrations.put(
                            storageKey(regionResolver.getAccountId(), region, integration.getName()),
                            integration);
                }
            };
        }
        throw notFound("Resource " + decoded + " does not exist.");
    }

    private EventIntegration requireEventIntegration(String region, String name) {
        String decoded = decode(name);
        String account = regionResolver.getAccountId();
        String eventName = decoded;
        if (decoded.startsWith("arn:")) {
            AwsArnUtils.Arn parsed;
            try {
                parsed = AwsArnUtils.parse(decoded);
            } catch (IllegalArgumentException e) {
                throw notFound("Event integration " + decoded + " does not exist.");
            }
            if (!SERVICE.equals(parsed.service()) || !parsed.resource().startsWith("event-integration/")) {
                throw notFound("Event integration " + decoded + " does not exist.");
            }
            eventName = parsed.resource().substring("event-integration/".length());
        }
        EventIntegration integration = eventIntegrations.get(storageKey(account, region, eventName)).orElse(null);
        if (integration == null) {
            throw notFound("Event integration " + decoded + " does not exist.");
        }
        if (decoded.startsWith("arn:") && !decoded.equals(integration.getEventIntegrationArn())) {
            throw notFound("Event integration " + decoded + " does not exist.");
        }
        return integration;
    }

    private interface TaggedResource {
        Map<String, String> tags();

        void applyTags(Map<String, String> tags);
    }

    private String arn(String region, String account, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, resource).toString();
    }

    private static String storageKey(String account, String region, String id) {
        return account + "::" + region + "::" + id;
    }

    private static String prefix(String account, String region) {
        return account + "::" + region + "::";
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private static void validateDataIntegrationName(String name) {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("InvalidRequestException", "Name must match [a-zA-Z0-9/._-]{1,255}.", 400);
        }
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static void requireObject(JsonNode request, String label) {
        if (request == null || !request.isObject()) {
            throw new AwsException("InvalidRequestException", label + " must be a JSON object.", 400);
        }
    }

    private static JsonNode requireObjectNode(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        requireObject(value, field);
        return value;
    }

    private static String requireText(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw new AwsException("InvalidRequestException", field + " is required.", 400);
        }
        return node.asText();
    }

    private static String optionalText(JsonNode request, String field) {
        return textOrNull(request, field);
    }

    private static String textOrNull(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new AwsException("InvalidRequestException", field + " must be a string.", 400);
        }
        return node.asText();
    }

    private static Map<String, String> readStringMap(JsonNode request, String field) {
        JsonNode node = request.get(field);
        Map<String, String> map = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return map;
        }
        if (!node.isObject()) {
            throw new AwsException("InvalidRequestException", field + " must be a map.", 400);
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && !value.isNull()) {
                map.put(entry.getKey(), value.asText());
            }
        });
        return map;
    }

    private static List<String> readStringList(JsonNode request, String field) {
        JsonNode node = request.get(field);
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (!node.isArray()) {
            throw new AwsException("InvalidRequestException", field + " must be a list.", 400);
        }
        for (JsonNode item : node) {
            if (item != null && !item.isNull()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static int parseMaxResults(String maxResultsValue) {
        if (maxResultsValue == null || maxResultsValue.isBlank()) {
            return DEFAULT_MAX_RESULTS;
        }
        int maxResults;
        try {
            maxResults = Integer.parseInt(maxResultsValue);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidRequestException", "maxResults must be an integer.", 400);
        }
        if (maxResults < 1 || maxResults > MAX_RESULTS) {
            throw new AwsException("InvalidRequestException",
                    "maxResults must be between 1 and " + MAX_RESULTS + ".", 400);
        }
        return maxResults;
    }

    private static <T> Page<T> page(List<T> items, int maxResults, String nextToken) {
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + maxResults, items.size());
        String responseToken = end < items.size() ? encodeOffset(end) : null;
        return new Page<>(items.subList(offset, end), responseToken);
    }

    private static String encodeOffset(int offset) {
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeOffset(String nextToken, int size) {
        if (nextToken == null || nextToken.isBlank()) {
            return 0;
        }
        if (!nextToken.startsWith(TOKEN_PREFIX)) {
            throw new AwsException("InvalidRequestException", "Invalid nextToken.", 400);
        }
        try {
            String encoded = nextToken.substring(TOKEN_PREFIX.length());
            int offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
            if (offset < 0 || offset > size) {
                throw new AwsException("InvalidRequestException", "Invalid nextToken.", 400);
            }
            return offset;
        } catch (AwsException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AwsException("InvalidRequestException", "Invalid nextToken.", 400);
        }
    }

    static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    public record Page<T>(List<T> items, String nextToken) {
    }
}
