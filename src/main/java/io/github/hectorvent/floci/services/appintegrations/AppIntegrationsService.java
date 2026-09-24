package io.github.hectorvent.floci.services.appintegrations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appintegrations.model.DataIntegration;
import io.github.hectorvent.floci.services.appintegrations.model.EventIntegration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Amazon AppIntegrations (signing name {@code app-integrations}, API version 2020-07-29).
 * Event integrations are addressed by name; applications and data integrations by generated id.
 * Identifiers and ARNs are resolved within the caller's account and region.
 */
@ApplicationScoped
public class AppIntegrationsService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(AppIntegrationsService.class);

    static final String SERVICE = "app-integrations";
    private static final String EVENT_INTEGRATION_RESOURCE = "event-integration";
    private static final String DATA_INTEGRATION_RESOURCE = "data-integration";

    private static final List<String> APPLICATION_FIELDS = List.of("Name", "Description",
            "ApplicationSourceConfig", "Subscriptions", "Publications", "Permissions", "IsService",
            "InitializationTimeout", "ApplicationConfig", "IframeConfig", "ApplicationType");

    private final StorageBackend<String, EventIntegration> eventIntegrations;
    private final StorageBackend<String, DataIntegration> dataIntegrations;
    private final StorageBackend<String, ObjectNode> applications;
    private final StorageBackend<String, ObjectNode> creationRequests;
    private final RegionResolver regionResolver;

    @Inject
    public AppIntegrationsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.eventIntegrations = storageFactory.create("appintegrations",
                "appintegrations-event-integrations.json",
                new TypeReference<Map<String, EventIntegration>>() {});
        this.dataIntegrations = storageFactory.create("appintegrations",
                "appintegrations-data-integrations.json",
                new TypeReference<Map<String, DataIntegration>>() {});
        this.applications = storageFactory.create("appintegrations",
                "appintegrations-applications.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.creationRequests = storageFactory.create("appintegrations",
                "appintegrations-creation-requests.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regionResolver = regionResolver;
    }

    public synchronized ObjectNode createIdempotently(String operation, JsonNode request, String region,
                                                       Supplier<ObjectNode> create) {
        if (request == null || !request.isObject()) {
            throw new AwsException("InvalidRequestException", "Request must be an object", 400);
        }
        JsonNode token = request.get("ClientToken");
        if (token == null || token.isNull()) {
            return create.get();
        }
        if (!token.isTextual() || token.asText().isBlank() || token.asText().length() > 2048) {
            throw new AwsException("InvalidRequestException", "Invalid ClientToken", 400);
        }
        ObjectNode parameters = ((ObjectNode) request).deepCopy();
        parameters.remove("ClientToken");
        String requestKey = key(region, operation + "/" + token.asText());
        Optional<ObjectNode> previous = creationRequests.get(requestKey);
        if (previous.isPresent()) {
            if (!parameters.equals(previous.get().get("Request"))) {
                throw new AwsException("InvalidRequestException",
                        "ClientToken has already been used with different parameters", 400);
            }
            return ((ObjectNode) previous.get().get("Response")).deepCopy();
        }
        ObjectNode response = create.get();
        ObjectNode stored = JsonNodeFactory.instance.objectNode();
        stored.set("Request", parameters);
        stored.set("Response", response.deepCopy());
        creationRequests.put(requestKey, stored);
        return response;
    }

    public synchronized ObjectNode createApplication(JsonNode request, String region) {
        return createIdempotently("CreateApplication", request, region, () -> {
            validateApplication(request, true);
            String namespace = request.path("Namespace").asText();
            if (listApplications(region, null).stream()
                    .anyMatch(app -> namespace.equals(app.path("Namespace").asText()))) {
                throw new AwsException("InvalidRequestException", "Namespace already in use", 400);
            }
            String id = UUID.randomUUID().toString();
            ObjectNode application = JsonNodeFactory.instance.objectNode();
            copyApplicationFields(request, application);
            application.put("Id", id);
            application.put("Arn", regionResolver.buildArn(SERVICE, region, "application/" + id));
            application.put("Namespace", namespace);
            double now = Instant.now().toEpochMilli() / 1000.0;
            application.put("CreatedTime", now);
            application.put("LastModifiedTime", now);
            application.set("Tags", request.hasNonNull("Tags")
                    ? request.get("Tags").deepCopy() : JsonNodeFactory.instance.objectNode());
            applications.put(key(region, id), application);
            ObjectNode response = JsonNodeFactory.instance.objectNode();
            response.put("Id", id);
            response.put("Arn", application.path("Arn").asText());
            return response;
        });
    }

    public ObjectNode getApplication(String identifier, String region) {
        String id = resolveIdentifier(identifier, "application", region);
        return applications.get(key(region, id)).map(ObjectNode::deepCopy)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Application " + identifier + " does not exist.", 404));
    }

    public synchronized void updateApplication(String identifier, JsonNode request, String region) {
        ObjectNode application = getApplication(identifier, region);
        validateApplication(request, false);
        copyApplicationFields(request, application);
        application.put("LastModifiedTime", Instant.now().toEpochMilli() / 1000.0);
        applications.put(key(region, application.path("Id").asText()), application);
    }

    public synchronized void deleteApplication(String identifier, String region) {
        ObjectNode application = getApplication(identifier, region);
        applications.delete(key(region, application.path("Id").asText()));
    }

    public List<ObjectNode> listApplications(String region, String applicationType) {
        if (applicationType != null && !List.of("STANDARD", "SERVICE", "MCP_SERVER").contains(applicationType)) {
            throw new AwsException("InvalidRequestException", "Invalid ApplicationType", 400);
        }
        return applications.scan(k -> k.startsWith(region + "::")).stream()
                .filter(app -> applicationType == null
                        || applicationType.equals(app.path("ApplicationType").asText("STANDARD")))
                .map(ObjectNode::deepCopy).toList();
    }

    public List<ObjectNode> listApplicationAssociations(String identifier, String region) {
        getApplication(identifier, region);
        // Only consuming AWS services can create associations; no such producer is implemented.
        return List.of();
    }

    public List<ObjectNode> listDataIntegrationAssociations(String identifier, String region) {
        getDataIntegration(identifier, region);
        return List.of();
    }

    public AwsException dataIntegrationAssociationWriteDenied(String identifier, String action, String region) {
        DataIntegration integration = getDataIntegration(identifier, region);
        // Ordinary signed credentials cannot satisfy the service-managed resource policy.
        return new AwsException("AccessDeniedException", "Not authorized to perform: app-integrations:"
                + action + " on resource: " + integration.getArn()
                + " with an explicit deny in a resource-based policy", 403);
    }

    private void copyApplicationFields(JsonNode request, ObjectNode application) {
        for (String field : APPLICATION_FIELDS) {
            if (request.hasNonNull(field)) {
                application.set(field, request.get(field).deepCopy());
            }
        }
    }

    private void validateApplication(JsonNode request, boolean creating) {
        if (!request.isObject()) {
            throw new AwsException("InvalidRequestException", "Request must be an object", 400);
        }
        if (creating || request.has("Name")) {
            requireText(request, "Name", 255);
            if (!request.path("Name").asText().matches("[a-zA-Z0-9/._ -]+")) {
                throw new AwsException("InvalidRequestException", "Invalid Name", 400);
            }
        }
        if (creating) {
            requireText(request, "Namespace", 211);
            if (!request.path("Namespace").asText().matches("[a-zA-Z0-9/._-]+")) {
                throw new AwsException("InvalidRequestException", "Invalid Namespace", 400);
            }
        }
        if (request.has("Description")) {
            requireText(request, "Description", 1000);
        }
        if (creating || request.has("ApplicationSourceConfig")) {
            JsonNode source = request.path("ApplicationSourceConfig").path("ExternalUrlConfig");
            requireText(source, "AccessUrl", 1000);
            validateUrl(source.path("AccessUrl").asText());
            if (source.has("ApprovedOrigins")) {
                requireStringList(source.get("ApprovedOrigins"), "ApprovedOrigins");
                if (source.get("ApprovedOrigins").isEmpty() || source.get("ApprovedOrigins").size() > 50) {
                    throw new AwsException("InvalidRequestException", "ApprovedOrigins must contain 1 to 50 URLs", 400);
                }
                source.get("ApprovedOrigins").forEach(origin -> {
                    if (origin.asText().length() > 128) {
                        throw new AwsException("InvalidRequestException", "Approved origin is too long", 400);
                    }
                    validateUrl(origin.asText());
                });
            }
        }
        if (request.has("Permissions")) {
            requireStringList(request.get("Permissions"), "Permissions");
        }
        if (request.has("Tags")) {
            JsonNode tags = request.get("Tags");
            if (!tags.isObject()) {
                throw new AwsException("InvalidRequestException", "Tags must be a map", 400);
            }
            tags.forEach(value -> {
                if (!value.isTextual()) {
                    throw new AwsException("InvalidRequestException", "Tag values must be strings", 400);
                }
            });
        }
    }

    private void requireText(JsonNode request, String field, int maxLength) {
        JsonNode value = request.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maxLength) {
            throw new AwsException("InvalidRequestException", "Invalid " + field, 400);
        }
    }

    private void requireStringList(JsonNode value, String field) {
        if (!value.isArray()) {
            throw new AwsException("InvalidRequestException", field + " must be a list", 400);
        }
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new AwsException("InvalidRequestException", "Invalid " + field + " entry", 400);
            }
        }
    }

    private void validateUrl(String value) {
        if (!value.matches("\\w+://.*")) {
            throw new AwsException("InvalidRequestException", "Invalid application URL: " + value, 400);
        }
    }

    public synchronized EventIntegration createEventIntegration(String name, String description, JsonNode eventFilter,
                                                   String eventBridgeBus, Map<String, String> tags,
                                                   String region) {
        requireMember(name, "Name");
        requireMember(eventBridgeBus, "EventBridgeBus");
        if (eventFilter == null || !eventFilter.isObject()
                || !eventFilter.path("Source").isTextual() || eventFilter.path("Source").asText().isBlank()) {
            throw new AwsException("InvalidRequestException", "EventFilter.Source is required", 400);
        }
        if (eventIntegrations.get(key(region, name)).isPresent()) {
            throw new AwsException("DuplicateResourceException",
                    "An event integration named " + name + " already exists.", 409);
        }

        EventIntegration integration = new EventIntegration();
        integration.setName(name);
        integration.setDescription(description);
        integration.setEventIntegrationArn(
                regionResolver.buildArn(SERVICE, region, EVENT_INTEGRATION_RESOURCE + "/" + name));
        integration.setEventBridgeBus(eventBridgeBus);
        integration.setEventFilterSource(eventFilter.get("Source").asText());
        integration.setTags(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());
        integration.setAccountId(regionResolver.getAccountId());

        eventIntegrations.put(key(region, name), integration);
        LOG.infov("Created AppIntegrations event integration: {0}", name);
        return integration;
    }

    public EventIntegration getEventIntegration(String name, String region) {
        String resolved = resolveIdentifier(name, EVENT_INTEGRATION_RESOURCE, region);
        return eventIntegrations.get(key(region, resolved))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Event integration " + name + " does not exist.", 404));
    }

    /**
     * {@code Description} is the only updatable member. An absent member leaves the stored
     * value alone, which is how AWS treats an optional member the caller did not send.
     */
    public synchronized EventIntegration updateEventIntegration(String name, Optional<String> description, String region) {
        EventIntegration integration = getEventIntegration(name, region);
        description.ifPresent(integration::setDescription);
        eventIntegrations.put(key(region, integration.getName()), integration);
        return integration;
    }

    public synchronized void deleteEventIntegration(String name, String region) {
        EventIntegration integration = getEventIntegration(name, region);
        eventIntegrations.delete(key(region, integration.getName()));
        LOG.infov("Deleted AppIntegrations event integration: {0}", integration.getName());
    }

    public List<EventIntegration> listEventIntegrations(String region) {
        String prefix = region + "::";
        return eventIntegrations.scan(k -> k.startsWith(prefix));
    }

    public synchronized DataIntegration createDataIntegration(String name, String description, String kmsKey,
                                                 String sourceUri, JsonNode scheduleConfig,
                                                 JsonNode fileConfiguration, JsonNode objectConfiguration,
                                                 Map<String, String> tags, String region) {
        requireMember(name, "Name");
        requireMember(kmsKey, "KmsKey");
        for (DataIntegration existing : listDataIntegrations(region)) {
            if (name.equals(existing.getName())) {
                throw new AwsException("DuplicateResourceException",
                        "A data integration named " + name + " already exists.", 409);
            }
        }

        String id = UUID.randomUUID().toString();
        DataIntegration integration = new DataIntegration();
        integration.setId(id);
        integration.setArn(regionResolver.buildArn(SERVICE, region, DATA_INTEGRATION_RESOURCE + "/" + id));
        integration.setName(name);
        integration.setDescription(description);
        integration.setKmsKey(kmsKey);
        integration.setSourceUri(sourceUri);
        integration.setScheduleConfiguration(scheduleConfig == null ? null : scheduleConfig.deepCopy());
        integration.setFileConfiguration(fileConfiguration == null ? null : fileConfiguration.deepCopy());
        integration.setObjectConfiguration(objectConfiguration == null ? null : objectConfiguration.deepCopy());
        integration.setTags(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());
        integration.setAccountId(regionResolver.getAccountId());

        dataIntegrations.put(key(region, id), integration);
        LOG.infov("Created AppIntegrations data integration: {0}", id);
        return integration;
    }

    public DataIntegration getDataIntegration(String identifier, String region) {
        String id = resolveIdentifier(identifier, DATA_INTEGRATION_RESOURCE, region);
        return dataIntegrations.get(key(region, id))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Data integration " + identifier + " does not exist.", 404));
    }

    public synchronized DataIntegration updateDataIntegration(String identifier, Optional<String> name,
                                                               Optional<String> description, String region) {
        DataIntegration integration = getDataIntegration(identifier, region);
        name.ifPresent(value -> {
            requireMember(value, "Name");
            if (listDataIntegrations(region).stream().anyMatch(other -> value.equals(other.getName())
                    && !integration.getId().equals(other.getId()))) {
                throw new AwsException("DuplicateResourceException",
                        "A data integration named " + value + " already exists.", 409);
            }
        });
        name.ifPresent(integration::setName);
        description.ifPresent(integration::setDescription);
        dataIntegrations.put(key(region, integration.getId()), integration);
        return integration;
    }

    public synchronized void deleteDataIntegration(String identifier, String region) {
        DataIntegration integration = getDataIntegration(identifier, region);
        dataIntegrations.delete(key(region, integration.getId()));
        LOG.infov("Deleted AppIntegrations data integration: {0}", integration.getId());
    }

    public List<DataIntegration> listDataIntegrations(String region) {
        String prefix = region + "::";
        return dataIntegrations.scan(k -> k.startsWith(prefix));
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    /** AppIntegrations models no {@code responseCode} on either tag write, so both answer 200. */
    @Override
    public int tagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public int untagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Map<String, String> tags = tagsOf(findByArn(arn, region));
        return tags != null ? new LinkedHashMap<>(tags) : Map.of();
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Object resource = findByArn(arn, region);
        Map<String, String> updated = new LinkedHashMap<>(tagsOf(resource));
        updated.putAll(tags);
        setTags(resource, updated);
        store(region, resource);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Object resource = findByArn(arn, region);
        Map<String, String> tags = new LinkedHashMap<>(tagsOf(resource));
        if (tagKeys != null) {
            tagKeys.forEach(tags::remove);
        }
        setTags(resource, tags);
        store(region, resource);
    }

    private Object findByArn(String arn, String region) {
        String prefix = region + "::";
        for (EventIntegration event : eventIntegrations.scan(k -> k.startsWith(prefix))) {
            if (arn.equals(event.getEventIntegrationArn())) {
                return event;
            }
        }
        for (DataIntegration data : dataIntegrations.scan(k -> k.startsWith(prefix))) {
            if (arn.equals(data.getArn())) {
                return data;
            }
        }
        for (ObjectNode application : applications.scan(k -> k.startsWith(prefix))) {
            if (arn.equals(application.path("Arn").asText())) {
                return application.deepCopy();
            }
        }
        throw new AwsException("ResourceNotFoundException", "Resource " + arn + " does not exist.", 404);
    }

    private Map<String, String> tagsOf(Object resource) {
        if (resource instanceof ObjectNode application) {
            Map<String, String> tags = new LinkedHashMap<>();
            application.path("Tags").fields().forEachRemaining(entry ->
                    tags.put(entry.getKey(), entry.getValue().asText()));
            return tags;
        }
        Map<String, String> tags = resource instanceof EventIntegration event
                ? event.getTags() : ((DataIntegration) resource).getTags();
        return tags == null ? Map.of() : tags;
    }

    private void setTags(Object resource, Map<String, String> tags) {
        if (resource instanceof ObjectNode application) {
            ObjectNode values = application.putObject("Tags");
            tags.forEach(values::put);
        } else if (resource instanceof EventIntegration event) {
            event.setTags(tags);
        } else {
            ((DataIntegration) resource).setTags(tags);
        }
    }

    private void store(String region, Object resource) {
        if (resource instanceof ObjectNode application) {
            applications.put(key(region, application.path("Id").asText()), application);
        } else if (resource instanceof EventIntegration event) {
            eventIntegrations.put(key(region, event.getName()), event);
        } else {
            DataIntegration data = (DataIntegration) resource;
            dataIntegrations.put(key(region, data.getId()), data);
        }
    }

    /**
     * Both the bare identifier and the resource's own ARN are accepted, as AWS does.
     * An ARN naming a different AppIntegrations resource type is rejected rather than
     * silently resolving against the wrong store.
     */
    private String resolveIdentifier(String identifier, String expectedResourceType, String region) {
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("InvalidRequestException", "Identifier is required", 400);
        }
        if (!identifier.startsWith("arn:")) {
            return identifier;
        }
        String resource;
        try {
            resource = AwsArnUtils.parse(identifier).resource();
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidRequestException", "Invalid ARN: " + identifier, 400);
        }
        int slash = resource.indexOf('/');
        if (slash < 0 || slash == resource.length() - 1
                || !expectedResourceType.equals(resource.substring(0, slash))) {
            throw new AwsException("InvalidRequestException", "Invalid ARN: " + identifier, 400);
        }
        if (!identifier.equals(regionResolver.buildArn(SERVICE, region, resource))) {
            throw new AwsException("ResourceNotFoundException", "Resource " + identifier + " does not exist.", 404);
        }
        return resource.substring(slash + 1);
    }

    private void requireMember(String value, String member) {
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidRequestException", member + " is required", 400);
        }
    }

    private String key(String region, String identifier) {
        return region + "::" + identifier;
    }
}
