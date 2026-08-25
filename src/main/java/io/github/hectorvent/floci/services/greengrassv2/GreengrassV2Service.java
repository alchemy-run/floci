package io.github.hectorvent.floci.services.greengrassv2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.greengrassv2.model.ClientAssociation;
import io.github.hectorvent.floci.services.greengrassv2.model.ComponentVersion;
import io.github.hectorvent.floci.services.greengrassv2.model.Deployment;
import io.github.hectorvent.floci.services.greengrassv2.model.ThingConnectivity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IoT Greengrass V2 restJson1 — private component versions and deployment revisions.
 *
 * <p>Inline-recipe components become {@code DEPLOYABLE} immediately. Creating a
 * deployment for a target supersedes the previous latest revision (marks it
 * {@code INACTIVE}). {@code DeleteDeployment} requires a non-{@code ACTIVE}
 * revision. Tag APIs share {@code /tags/{arn}} via {@link TagHandler} using
 * ARN service {@code greengrass}.
 */
@ApplicationScoped
public class GreengrassV2Service implements TagHandler {

    static final String SERVICE = "greengrass";
    static final String RESOURCE_DEPLOYMENT = "deployment";
    static final String RESOURCE_COMPONENT = "component";
    static final String RESOURCE_CORE_DEVICE = "coreDevice";
    static final String RESOURCE_ARTIFACT = "artifact";
    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int MAX_RESULTS = 100;

    private final StorageBackend<String, ComponentVersion> components;
    private final StorageBackend<String, Deployment> deployments;
    private final StorageBackend<String, ThingConnectivity> connectivity;
    private final StorageBackend<String, ClientAssociation> associations;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public GreengrassV2Service(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(
                storageFactory.create(
                        "greengrassv2",
                        "greengrassv2-components.json",
                        new TypeReference<Map<String, ComponentVersion>>() {
                        }),
                storageFactory.create(
                        "greengrassv2",
                        "greengrassv2-deployments.json",
                        new TypeReference<Map<String, Deployment>>() {
                        }),
                storageFactory.create(
                        "greengrassv2",
                        "greengrassv2-connectivity.json",
                        new TypeReference<Map<String, ThingConnectivity>>() {
                        }),
                storageFactory.create(
                        "greengrassv2",
                        "greengrassv2-associations.json",
                        new TypeReference<Map<String, ClientAssociation>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    GreengrassV2Service(
            StorageBackend<String, ComponentVersion> components,
            StorageBackend<String, Deployment> deployments,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this(components, deployments, new InMemoryStorage<>(), new InMemoryStorage<>(),
                regionResolver, objectMapper);
    }

    GreengrassV2Service(
            StorageBackend<String, ComponentVersion> components,
            StorageBackend<String, Deployment> deployments,
            StorageBackend<String, ThingConnectivity> connectivity,
            StorageBackend<String, ClientAssociation> associations,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.components = components;
        this.deployments = deployments;
        this.connectivity = connectivity;
        this.associations = associations;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    public synchronized ComponentVersion createComponentVersion(String region, JsonNode request) {
        requireObject(request, "Request body");
        String recipe = decodeRecipe(request);
        RecipeIdentity identity = parseRecipeIdentity(recipe);
        String clientToken = textOrNull(request, "clientToken");
        if (clientToken != null) {
            Optional<ComponentVersion> existing = findComponentByClientToken(region, clientToken);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        String arn = componentVersionArn(region, identity.name(), identity.version());
        if (components.get(storageKey(region, arn)).isPresent()) {
            throw conflict(RESOURCE_COMPONENT, arn,
                    "Component version already exists: " + identity.name() + " " + identity.version());
        }
        long now = Instant.now().getEpochSecond();
        ComponentVersion version = new ComponentVersion();
        version.setArn(arn);
        version.setComponentName(identity.name());
        version.setComponentVersion(identity.version());
        version.setRecipe(recipe);
        version.setPublisher(identity.publisher());
        version.setDescription(identity.description());
        version.setComponentState("DEPLOYABLE");
        version.setCreationTimestamp(now);
        version.setPlatforms(identity.platforms());
        version.setTags(readTags(request.get("tags")));
        version.setRegion(region);
        version.setClientToken(clientToken);
        components.put(storageKey(region, arn), version);
        return version;
    }

    public ComponentVersion describeComponent(String region, String arn) {
        return requireComponent(region, arn);
    }

    public synchronized void deleteComponent(String region, String arn) {
        ComponentVersion version = requireComponent(region, arn);
        components.delete(storageKey(region, version.getArn()));
    }

    public Page<ComponentVersion> listComponents(String region, String scope, String nextToken, Integer maxResults) {
        if (scope != null && !scope.isBlank() && !"PRIVATE".equals(scope) && !"PUBLIC".equals(scope)) {
            throw validation("scope must be PRIVATE or PUBLIC.");
        }
        if ("PUBLIC".equals(scope)) {
            return new Page<>(List.of(), null);
        }
        Map<String, ComponentVersion> latestByName = new LinkedHashMap<>();
        for (ComponentVersion version : components.values()) {
            if (!region.equals(version.getRegion())) {
                continue;
            }
            ComponentVersion current = latestByName.get(version.getComponentName());
            if (current == null || version.getCreationTimestamp() >= current.getCreationTimestamp()) {
                latestByName.put(version.getComponentName(), version);
            }
        }
        List<ComponentVersion> latest = new ArrayList<>(latestByName.values());
        latest.sort(Comparator.comparing(ComponentVersion::getComponentName));
        return page(latest, nextToken, maxResults, ComponentVersion::getComponentName);
    }

    public Page<ComponentVersion> listComponentVersions(String region, String arn, String nextToken, Integer maxResults) {
        String name = componentNameFromArn(arn);
        List<ComponentVersion> versions = new ArrayList<>();
        for (ComponentVersion version : components.values()) {
            if (region.equals(version.getRegion()) && name.equals(version.getComponentName())) {
                versions.add(version);
            }
        }
        if (versions.isEmpty()) {
            throw notFound(RESOURCE_COMPONENT, name, "Component not found: " + name);
        }
        versions.sort(Comparator.comparing(ComponentVersion::getComponentVersion).reversed());
        return page(versions, nextToken, maxResults, ComponentVersion::getArn);
    }

    public synchronized Deployment createDeployment(String region, JsonNode request) {
        requireObject(request, "Request body");
        String targetArn = requireText(request, "targetArn");
        String clientToken = textOrNull(request, "clientToken");
        if (clientToken != null) {
            Optional<Deployment> existing = findDeploymentByClientToken(region, clientToken);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        int nextRevision = 1;
        for (Deployment existing : deployments.values()) {
            if (!region.equals(existing.getRegion()) || !targetArn.equals(existing.getTargetArn())) {
                continue;
            }
            existing.setLatestForTarget(false);
            if ("ACTIVE".equals(existing.getDeploymentStatus())) {
                existing.setDeploymentStatus("INACTIVE");
            }
            deployments.put(storageKey(region, existing.getDeploymentId()), existing);
            try {
                nextRevision = Math.max(nextRevision, Integer.parseInt(existing.getRevisionId()) + 1);
            } catch (NumberFormatException ignored) {
                nextRevision = Math.max(nextRevision, 1);
            }
        }
        long now = Instant.now().getEpochSecond();
        String deploymentId = UUID.randomUUID().toString();
        Deployment deployment = new Deployment();
        deployment.setDeploymentId(deploymentId);
        deployment.setTargetArn(targetArn);
        deployment.setDeploymentName(textOrNull(request, "deploymentName"));
        deployment.setRevisionId(Integer.toString(nextRevision));
        deployment.setDeploymentStatus("ACTIVE");
        JsonNode componentsNode = request.get("components");
        deployment.setComponents(componentsNode != null && componentsNode.isObject()
                ? componentsNode.deepCopy()
                : objectMapper.createObjectNode());
        deployment.setCreationTimestamp(now);
        deployment.setLatestForTarget(true);
        deployment.setParentTargetArn(textOrNull(request, "parentTargetArn"));
        deployment.setTags(readTags(request.get("tags")));
        deployment.setRegion(region);
        deployment.setClientToken(clientToken);
        deployments.put(storageKey(region, deploymentId), deployment);
        return deployment;
    }

    public Deployment getDeployment(String region, String deploymentId) {
        return requireDeployment(region, deploymentId);
    }

    public Page<Deployment> listDeployments(
            String region, String targetArn, String historyFilter, String parentTargetArn,
            String nextToken, Integer maxResults) {
        if (historyFilter != null && !historyFilter.isBlank()
                && !"ALL".equals(historyFilter) && !"LATEST_ONLY".equals(historyFilter)) {
            throw validation("historyFilter must be ALL or LATEST_ONLY.");
        }
        boolean latestOnly = historyFilter == null || historyFilter.isBlank() || "LATEST_ONLY".equals(historyFilter);
        List<Deployment> matches = new ArrayList<>();
        for (Deployment deployment : deployments.values()) {
            if (!region.equals(deployment.getRegion())) {
                continue;
            }
            if (targetArn != null && !targetArn.isBlank() && !targetArn.equals(deployment.getTargetArn())) {
                continue;
            }
            if (parentTargetArn != null && !parentTargetArn.isBlank()
                    && !parentTargetArn.equals(deployment.getParentTargetArn())) {
                continue;
            }
            if (latestOnly && !deployment.isLatestForTarget()) {
                continue;
            }
            matches.add(deployment);
        }
        matches.sort(Comparator.comparing(Deployment::getCreationTimestamp).reversed()
                .thenComparing(Deployment::getDeploymentId));
        return page(matches, nextToken, maxResults, Deployment::getDeploymentId);
    }

    public synchronized Deployment cancelDeployment(String region, String deploymentId) {
        Deployment deployment = requireDeployment(region, deploymentId);
        if (!"ACTIVE".equals(deployment.getDeploymentStatus())) {
            throw conflict(RESOURCE_DEPLOYMENT, deploymentId,
                    "Deployment " + deploymentId + " cannot be canceled in status "
                            + deployment.getDeploymentStatus() + ".");
        }
        deployment.setDeploymentStatus("CANCELED");
        deployments.put(storageKey(region, deploymentId), deployment);
        return deployment;
    }

    public synchronized void deleteDeployment(String region, String deploymentId) {
        Deployment deployment = requireDeployment(region, deploymentId);
        if ("ACTIVE".equals(deployment.getDeploymentStatus())) {
            throw conflict(RESOURCE_DEPLOYMENT, deploymentId,
                    "Deployment " + deploymentId + " is ACTIVE and must be canceled before it can be deleted.");
        }
        deployments.delete(storageKey(region, deploymentId));
    }

    public ObjectNode getComponent(String region, String arn, String recipeOutputFormat) {
        ComponentVersion version = requireComponent(region, arn);
        String format = recipeOutputFormat == null || recipeOutputFormat.isBlank()
                ? "JSON"
                : recipeOutputFormat;
        if (!"JSON".equals(format) && !"YAML".equals(format)) {
            throw validation("recipeOutputFormat must be JSON or YAML.");
        }
        ObjectNode out = objectMapper.createObjectNode();
        out.put("recipeOutputFormat", "JSON");
        byte[] recipeBytes = version.getRecipe() == null
                ? new byte[0]
                : version.getRecipe().getBytes(StandardCharsets.UTF_8);
        out.put("recipe", Base64.getEncoder().encodeToString(recipeBytes));
        putTags(out, version.getTags());
        return out;
    }

    public ObjectNode getComponentVersionArtifact(String region, String arn, String artifactName) {
        requireComponent(region, arn);
        String name = decode(artifactName);
        throw notFound(RESOURCE_ARTIFACT, name, "Artifact not found: " + name);
    }

    public ObjectNode listCoreDevices() {
        ObjectNode out = objectMapper.createObjectNode();
        out.putArray("coreDevices");
        return out;
    }

    public ObjectNode getCoreDevice(String coreDeviceThingName) {
        String name = decode(coreDeviceThingName);
        throw notFound(RESOURCE_CORE_DEVICE, name, "Core device not found: " + name);
    }

    public void deleteCoreDevice(String coreDeviceThingName) {
        String name = decode(coreDeviceThingName);
        throw notFound(RESOURCE_CORE_DEVICE, name, "Core device not found: " + name);
    }

    public ObjectNode listInstalledComponents() {
        ObjectNode out = objectMapper.createObjectNode();
        out.putArray("installedComponents");
        return out;
    }

    public ObjectNode listEffectiveDeployments() {
        ObjectNode out = objectMapper.createObjectNode();
        out.putArray("effectiveDeployments");
        return out;
    }

    public ObjectNode listClientDevices(String region, String coreDeviceThingName) {
        String core = decode(coreDeviceThingName);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode devices = out.putArray("associatedClientDevices");
        for (ClientAssociation association : associations.values()) {
            if (region.equals(association.getRegion()) && core.equals(association.getCoreDeviceThingName())) {
                ObjectNode item = devices.addObject();
                item.put("thingName", association.getThingName());
                item.put("associationTimestamp", association.getAssociationTimestamp());
            }
        }
        return out;
    }

    public synchronized ObjectNode batchAssociateClientDevices(
            String region, String coreDeviceThingName, JsonNode request) {
        String core = decode(coreDeviceThingName);
        ObjectNode out = objectMapper.createObjectNode();
        out.putArray("errorEntries");
        JsonNode entries = request == null ? null : request.get("entries");
        if (entries == null || !entries.isArray()) {
            return out;
        }
        long now = Instant.now().getEpochSecond();
        for (JsonNode entry : entries) {
            String thingName = textOrNull(entry, "thingName");
            if (thingName == null) {
                continue;
            }
            ClientAssociation association = new ClientAssociation();
            association.setCoreDeviceThingName(core);
            association.setThingName(thingName);
            association.setAssociationTimestamp(now);
            association.setRegion(region);
            associations.put(storageKey(region, core + "::" + thingName), association);
        }
        return out;
    }

    public synchronized ObjectNode batchDisassociateClientDevices(
            String region, String coreDeviceThingName, JsonNode request) {
        String core = decode(coreDeviceThingName);
        ObjectNode out = objectMapper.createObjectNode();
        out.putArray("errorEntries");
        JsonNode entries = request == null ? null : request.get("entries");
        if (entries == null || !entries.isArray()) {
            return out;
        }
        for (JsonNode entry : entries) {
            String thingName = textOrNull(entry, "thingName");
            if (thingName == null) {
                continue;
            }
            associations.delete(storageKey(region, core + "::" + thingName));
        }
        return out;
    }

    public ObjectNode getConnectivityInfo(String region, String thingName) {
        String name = decode(thingName);
        ThingConnectivity stored = connectivity.get(storageKey(region, name)).orElse(null);
        return toConnectivityResponse(stored);
    }

    public synchronized ObjectNode updateConnectivityInfo(String region, String thingName, JsonNode request) {
        String name = decode(thingName);
        requireObject(request, "Request body");
        JsonNode list = request.get("ConnectivityInfo");
        if (list == null) {
            list = request.get("connectivityInfo");
        }
        if (list == null || !list.isArray()) {
            throw validation("ConnectivityInfo is a required parameter.");
        }
        List<ThingConnectivity.Entry> entries = new ArrayList<>();
        for (JsonNode item : list) {
            if (item == null || !item.isObject()) {
                continue;
            }
            ThingConnectivity.Entry entry = new ThingConnectivity.Entry();
            entry.setId(fieldText(item, "id", "Id"));
            entry.setHostAddress(fieldText(item, "hostAddress", "HostAddress"));
            entry.setPortNumber(fieldInt(item, "portNumber", "PortNumber"));
            entry.setMetadata(fieldText(item, "metadata", "Metadata"));
            entries.add(entry);
        }
        ThingConnectivity stored = connectivity.get(storageKey(region, name)).orElseGet(ThingConnectivity::new);
        stored.setThingName(name);
        stored.setRegion(region);
        stored.setVersion(stored.getVersion() + 1);
        stored.setConnectivityInfo(entries);
        connectivity.put(storageKey(region, name), stored);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("Version", Integer.toString(stored.getVersion()));
        out.put("Message", "Connectivity information updated.");
        return out;
    }

    public void resolveComponentCandidates() {
        throw accessDenied(
                "ResolveComponentCandidates must be called from a Greengrass core device using a device certificate.");
    }

    ObjectNode toConnectivityResponse(ThingConnectivity stored) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode infos = out.putArray("ConnectivityInfo");
        if (stored != null) {
            for (ThingConnectivity.Entry entry : stored.getConnectivityInfo()) {
                ObjectNode item = infos.addObject();
                if (entry.getId() != null) {
                    item.put("Id", entry.getId());
                }
                if (entry.getHostAddress() != null) {
                    item.put("HostAddress", entry.getHostAddress());
                }
                if (entry.getPortNumber() != null) {
                    item.put("PortNumber", entry.getPortNumber());
                }
                if (entry.getMetadata() != null) {
                    item.put("Metadata", entry.getMetadata());
                }
            }
        }
        return out;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return new LinkedHashMap<>(requireTagged(arn).getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(arn);
        if (tags != null) {
            tagged.getTags().putAll(tags);
        }
        tagged.store();
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(arn);
        if (tagKeys != null) {
            tagKeys.forEach(tagged.getTags()::remove);
        }
        tagged.store();
    }

    ObjectNode toCreateComponentVersion(ComponentVersion version) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("arn", version.getArn());
        out.put("componentName", version.getComponentName());
        out.put("componentVersion", version.getComponentVersion());
        out.put("creationTimestamp", version.getCreationTimestamp());
        ObjectNode status = out.putObject("status");
        status.put("componentState", version.getComponentState());
        return out;
    }

    ObjectNode toDescribeComponent(ComponentVersion version) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("arn", version.getArn());
        out.put("componentName", version.getComponentName());
        out.put("componentVersion", version.getComponentVersion());
        out.put("creationTimestamp", version.getCreationTimestamp());
        if (version.getPublisher() != null) {
            out.put("publisher", version.getPublisher());
        }
        if (version.getDescription() != null) {
            out.put("description", version.getDescription());
        }
        ObjectNode status = out.putObject("status");
        status.put("componentState", version.getComponentState());
        if (version.getPlatforms() != null) {
            out.set("platforms", version.getPlatforms());
        }
        putTags(out, version.getTags());
        return out;
    }

    ObjectNode toComponentSummary(ComponentVersion latest) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("arn", componentArn(latest.getRegion(), latest.getComponentName()));
        out.put("componentName", latest.getComponentName());
        ObjectNode latestVersion = out.putObject("latestVersion");
        latestVersion.put("arn", latest.getArn());
        latestVersion.put("componentVersion", latest.getComponentVersion());
        latestVersion.put("creationTimestamp", latest.getCreationTimestamp());
        if (latest.getDescription() != null) {
            latestVersion.put("description", latest.getDescription());
        }
        if (latest.getPublisher() != null) {
            latestVersion.put("publisher", latest.getPublisher());
        }
        if (latest.getPlatforms() != null) {
            latestVersion.set("platforms", latest.getPlatforms());
        }
        return out;
    }

    ObjectNode toComponentVersionListItem(ComponentVersion version) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("componentName", version.getComponentName());
        out.put("componentVersion", version.getComponentVersion());
        out.put("arn", version.getArn());
        return out;
    }

    ObjectNode toCreateDeployment(Deployment deployment) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("deploymentId", deployment.getDeploymentId());
        return out;
    }

    ObjectNode toGetDeployment(Deployment deployment) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("targetArn", deployment.getTargetArn());
        out.put("revisionId", deployment.getRevisionId());
        out.put("deploymentId", deployment.getDeploymentId());
        if (deployment.getDeploymentName() != null) {
            out.put("deploymentName", deployment.getDeploymentName());
        }
        out.put("deploymentStatus", deployment.getDeploymentStatus());
        out.set("components", deployment.getComponents() == null
                ? objectMapper.createObjectNode()
                : deployment.getComponents());
        out.put("creationTimestamp", deployment.getCreationTimestamp());
        out.put("isLatestForTarget", deployment.isLatestForTarget());
        if (deployment.getParentTargetArn() != null) {
            out.put("parentTargetArn", deployment.getParentTargetArn());
        }
        putTags(out, deployment.getTags());
        return out;
    }

    ObjectNode toDeploymentSummary(Deployment deployment) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("targetArn", deployment.getTargetArn());
        out.put("revisionId", deployment.getRevisionId());
        out.put("deploymentId", deployment.getDeploymentId());
        if (deployment.getDeploymentName() != null) {
            out.put("deploymentName", deployment.getDeploymentName());
        }
        out.put("creationTimestamp", deployment.getCreationTimestamp());
        out.put("deploymentStatus", deployment.getDeploymentStatus());
        out.put("isLatestForTarget", deployment.isLatestForTarget());
        if (deployment.getParentTargetArn() != null) {
            out.put("parentTargetArn", deployment.getParentTargetArn());
        }
        return out;
    }

    ObjectNode toCancelDeployment(Deployment deployment) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("message", "Deployment " + deployment.getDeploymentId() + " canceled.");
        return out;
    }

    private ComponentVersion requireComponent(String region, String arn) {
        String decoded = decode(arn);
        return components.get(storageKey(region, decoded)).orElseThrow(
                () -> notFound(RESOURCE_COMPONENT, decoded, "Component version not found: " + decoded));
    }

    private Deployment requireDeployment(String region, String deploymentId) {
        String id = decode(deploymentId);
        return deployments.get(storageKey(region, id)).orElseThrow(
                () -> notFound(RESOURCE_DEPLOYMENT, id, "Deployment not found: " + id));
    }

    private Tagged requireTagged(String arn) {
        String decoded = decode(arn);
        Optional<ComponentVersion> component = findComponentByArn(decoded);
        if (component.isPresent()) {
            ComponentVersion version = component.get();
            return new Tagged(version.getTags(), () ->
                    components.put(storageKey(version.getRegion(), version.getArn()), version));
        }
        Optional<Deployment> deployment = findDeploymentByArn(decoded);
        if (deployment.isPresent()) {
            Deployment found = deployment.get();
            return new Tagged(found.getTags(), () ->
                    deployments.put(storageKey(found.getRegion(), found.getDeploymentId()), found));
        }
        throw notFound("Resource", decoded, "Resource not found: " + decoded);
    }

    private Optional<ComponentVersion> findComponentByArn(String arn) {
        for (ComponentVersion version : components.values()) {
            if (arn.equals(version.getArn())) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }

    private Optional<Deployment> findDeploymentByArn(String arn) {
        for (Deployment deployment : deployments.values()) {
            if (arn.equals(deploymentArn(deployment))) {
                return Optional.of(deployment);
            }
        }
        return Optional.empty();
    }

    private Optional<ComponentVersion> findComponentByClientToken(String region, String clientToken) {
        for (ComponentVersion version : components.values()) {
            if (region.equals(version.getRegion()) && clientToken.equals(version.getClientToken())) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }

    private Optional<Deployment> findDeploymentByClientToken(String region, String clientToken) {
        for (Deployment deployment : deployments.values()) {
            if (region.equals(deployment.getRegion()) && clientToken.equals(deployment.getClientToken())) {
                return Optional.of(deployment);
            }
        }
        return Optional.empty();
    }

    private String componentVersionArn(String region, String name, String version) {
        return regionResolver.buildArn(SERVICE, region, "components:" + name + ":versions:" + version);
    }

    private String componentArn(String region, String name) {
        return regionResolver.buildArn(SERVICE, region, "components:" + name);
    }

    private String deploymentArn(Deployment deployment) {
        return regionResolver.buildArn(SERVICE, deployment.getRegion(), "deployments:" + deployment.getDeploymentId());
    }

    private String decodeRecipe(JsonNode request) {
        JsonNode node = request.get("inlineRecipe");
        if (node == null || node.isNull()) {
            throw validation("inlineRecipe is a required parameter.");
        }
        String raw = node.asText();
        if (raw == null || raw.isBlank()) {
            throw validation("inlineRecipe is a required parameter.");
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")
                || trimmed.startsWith("RecipeFormatVersion")
                || trimmed.startsWith("ComponentName")) {
            return raw;
        }
        try {
            return new String(Base64.getDecoder().decode(trimmed), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw validation("inlineRecipe is not valid base64.");
        }
    }

    private RecipeIdentity parseRecipeIdentity(String recipe) {
        String name;
        String version;
        String publisher = null;
        String description = null;
        JsonNode platforms = objectMapper.createArrayNode();
        try {
            JsonNode parsed = objectMapper.readTree(recipe);
            if (parsed != null && parsed.isObject()) {
                name = textOrNull(parsed, "ComponentName");
                version = textOrNull(parsed, "ComponentVersion");
                publisher = textOrNull(parsed, "ComponentPublisher");
                description = textOrNull(parsed, "ComponentDescription");
                platforms = platformsFromManifests(parsed.get("Manifests"));
            } else {
                name = yamlValue(recipe, "ComponentName");
                version = yamlValue(recipe, "ComponentVersion");
                publisher = yamlValue(recipe, "ComponentPublisher");
                description = yamlValue(recipe, "ComponentDescription");
            }
        } catch (Exception e) {
            name = yamlValue(recipe, "ComponentName");
            version = yamlValue(recipe, "ComponentVersion");
            publisher = yamlValue(recipe, "ComponentPublisher");
            description = yamlValue(recipe, "ComponentDescription");
        }
        if (name == null || version == null) {
            throw validation("the inline recipe must declare ComponentName and ComponentVersion.");
        }
        return new RecipeIdentity(name, version, publisher, description, platforms);
    }

    private JsonNode platformsFromManifests(JsonNode manifests) {
        ArrayNode platforms = objectMapper.createArrayNode();
        if (manifests == null || !manifests.isArray()) {
            return platforms;
        }
        for (JsonNode manifest : manifests) {
            JsonNode platform = manifest.get("Platform");
            if (platform == null || !platform.isObject()) {
                continue;
            }
            ObjectNode entry = platforms.addObject();
            ObjectNode attributes = entry.putObject("attributes");
            platform.fields().forEachRemaining(field -> {
                if (field.getValue() != null && field.getValue().isTextual()) {
                    attributes.put(field.getKey(), field.getValue().asText());
                }
            });
        }
        return platforms;
    }

    private static String yamlValue(String recipe, String key) {
        Matcher matcher = Pattern.compile("^" + key + ":[ \\t]*['\"]?([^'\"#\\s]+)", Pattern.MULTILINE)
                .matcher(recipe);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String componentNameFromArn(String arn) {
        String decoded = decode(arn);
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decoded);
        } catch (IllegalArgumentException e) {
            throw validation("arn is not a valid Greengrass component ARN.");
        }
        if (!SERVICE.equals(parsed.service()) || !parsed.resource().startsWith("components:")) {
            throw validation("arn is not a Greengrass component ARN.");
        }
        String rest = parsed.resource().substring("components:".length());
        int versions = rest.indexOf(":versions:");
        return versions >= 0 ? rest.substring(0, versions) : rest;
    }

    private <T> Page<T> page(List<T> items, String nextToken, Integer maxResults, java.util.function.Function<T, String> id) {
        int limit = maxResults == null ? DEFAULT_MAX_RESULTS : maxResults;
        if (limit < 1 || limit > MAX_RESULTS) {
            throw validation("maxResults must be between 1 and " + MAX_RESULTS + ".");
        }
        int start = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            String marker = decodeToken(nextToken);
            start = -1;
            for (int i = 0; i < items.size(); i++) {
                if (marker.equals(id.apply(items.get(i)))) {
                    start = i + 1;
                    break;
                }
            }
            if (start < 0) {
                throw validation("Invalid nextToken.");
            }
        }
        int end = Math.min(items.size(), start + limit);
        String token = end < items.size() ? encodeToken(id.apply(items.get(end - 1))) : null;
        return new Page<>(items.subList(start, end), token);
    }

    private static String encodeToken(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("greengrassv2:v1:" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeToken(String token) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith("greengrassv2:v1:")) {
                throw new IllegalArgumentException("bad prefix");
            }
            return decoded.substring("greengrassv2:v1:".length());
        } catch (RuntimeException e) {
            throw validation("Invalid nextToken.");
        }
    }

    private void putTags(ObjectNode out, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        ObjectNode tagsNode = out.putObject("tags");
        tags.forEach(tagsNode::put);
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isObject()) {
            return tags;
        }
        tagsNode.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && !entry.getValue().isNull()) {
                tags.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return tags;
    }

    private static void requireObject(JsonNode request, String label) {
        if (request == null || !request.isObject()) {
            throw validation(label + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw validation(field + " is a required parameter.");
        }
        return value;
    }

    private static String textOrNull(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
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

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    static AwsException notFound(String resourceType, String resourceId, String message) {
        return new AwsException(
                "ResourceNotFoundException",
                message,
                404,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    static AwsException conflict(String resourceType, String resourceId, String message) {
        return new AwsException(
                "ConflictException",
                message,
                409,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException accessDenied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }

    private static String fieldText(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isTextual()) {
                String text = value.asText();
                if (!text.isBlank()) {
                    return text;
                }
            } else if (value.isNumber()) {
                return value.asText();
            }
        }
        return null;
    }

    private static Integer fieldInt(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isInt() || value.isLong()) {
                return value.intValue();
            }
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    private record RecipeIdentity(
            String name, String version, String publisher, String description, JsonNode platforms) {
    }

    private record Tagged(Map<String, String> tags, Runnable persister) {
        private Map<String, String> getTags() {
            return tags;
        }

        private void store() {
            persister.run();
        }
    }
}
