package io.github.hectorvent.floci.services.appregistry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appregistry.model.Application;
import io.github.hectorvent.floci.services.appregistry.model.Application.AssociatedResource;
import io.github.hectorvent.floci.services.appregistry.model.AttributeGroup;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationService;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS Service Catalog AppRegistry restJson1 — applications, attribute groups, and associations.
 *
 * <p>Tag APIs share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}
 * using ARN service {@code servicecatalog}.
 */
@ApplicationScoped
public class AppRegistryService implements TagHandler {

    static final String SERVICE = "servicecatalog-appregistry";
    static final String SIGNING_NAME = "servicecatalog";
    static final String SIGNING_SCOPE = SIGNING_NAME;
    static final String ARN_SERVICE = "servicecatalog";

    private static final String TOKEN_PREFIX = "appregistry:v1:";
    private static final int DEFAULT_MAX_RESULTS = 25;
    private static final int MAX_RESULTS = 100;
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9][-.a-zA-Z0-9_]{0,255}");
    private static final Set<String> RESOURCE_TYPES = Set.of("CFN_STACK", "RESOURCE_TAG_VALUE");
    private static final Set<String> ASSOCIATION_OPTIONS = Set.of("APPLY_APPLICATION_TAG", "SKIP_APPLICATION_TAG");

    private final StorageBackend<String, Application> applications;
    private final StorageBackend<String, AttributeGroup> attributeGroups;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final CloudFormationService cloudFormation;

    @Inject
    public AppRegistryService(
            StorageFactory storageFactory,
            RegionResolver regionResolver,
            ObjectMapper objectMapper,
            CloudFormationService cloudFormation) {
        this(
                storageFactory.create(
                        "appregistry",
                        "appregistry-applications.json",
                        new TypeReference<Map<String, Application>>() {
                        }),
                storageFactory.create(
                        "appregistry",
                        "appregistry-attribute-groups.json",
                        new TypeReference<Map<String, AttributeGroup>>() {
                        }),
                regionResolver,
                objectMapper,
                cloudFormation);
    }

    AppRegistryService(
            StorageBackend<String, Application> applications,
            StorageBackend<String, AttributeGroup> attributeGroups,
            RegionResolver regionResolver,
            ObjectMapper objectMapper,
            CloudFormationService cloudFormation) {
        this.applications = applications;
        this.attributeGroups = attributeGroups;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.cloudFormation = cloudFormation;
    }

    public synchronized Application createApplication(String region, JsonNode request) {
        throw new AwsException(
                "AccessDeniedException",
                "User is not authorized to perform: servicecatalog:CreateApplication because "
                        + "AWS Service Catalog AppRegistry is in maintenance mode and is no longer "
                        + "open to new customers.",
                403);
    }

    synchronized Application putApplication(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name);
        String clientToken = requireText(request, "clientToken");
        Application existingByName = findApplicationByName(region, name);
        if (existingByName != null) {
            if (clientToken.equals(existingByName.getClientToken())) {
                return existingByName;
            }
            throw conflict("An application with name " + name + " already exists.");
        }
        String now = now();
        String id = newId();
        String account = regionResolver.getAccountId();
        Application application = new Application();
        application.setId(id);
        application.setName(name);
        application.setArn(applicationArn(region, account, id));
        application.setDescription(optionalText(request, "description"));
        application.setCreationTime(now);
        application.setLastUpdateTime(now);
        application.setClientToken(clientToken);
        Map<String, String> tags = readTags(request.get("tags"));
        tags.put("aws:servicecatalog:applicationName", name);
        application.setTags(tags);
        applications.put(applicationKey(region, id), application);
        return application;
    }

    public Application getApplication(String region, String specifier) {
        return requireApplication(region, specifier);
    }

    public synchronized Application updateApplication(String region, String specifier, JsonNode request) {
        requireObject(request, "Request body");
        Application application = requireApplication(region, specifier);
        boolean changed = false;
        if (request.has("name") && !request.get("name").isNull()) {
            String name = requireText(request, "name");
            validateName(name);
            Application clash = findApplicationByName(region, name);
            if (clash != null && !clash.getId().equals(application.getId())) {
                throw conflict("An application with name " + name + " already exists.");
            }
            application.setName(name);
            application.getTags().put("aws:servicecatalog:applicationName", name);
            changed = true;
        }
        if (request.has("description")) {
            application.setDescription(textOrNull(request, "description"));
            changed = true;
        }
        if (changed) {
            application.setLastUpdateTime(now());
            applications.put(applicationKey(region, application.getId()), application);
        }
        return application;
    }

    public synchronized Application deleteApplication(String region, String specifier) {
        Application application = requireApplication(region, specifier);
        if (!application.getAssociatedAttributeGroupIds().isEmpty()
                || !application.getAssociatedResources().isEmpty()) {
            throw conflict("Application " + application.getName()
                    + " cannot be deleted because it still has associations.");
        }
        applications.delete(applicationKey(region, application.getId()));
        return application;
    }

    public Page<Application> listApplications(String region, String maxResultsValue, String nextToken) {
        List<Application> items = applications.scan(key -> key.startsWith(regionPrefix(region)));
        items.sort(Comparator.comparing(Application::getName, Comparator.nullsLast(String::compareTo))
                .thenComparing(Application::getId));
        return page(items, parseMaxResults(maxResultsValue), nextToken);
    }

    public synchronized AttributeGroup createAttributeGroup(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name);
        String attributes = requireAttributes(request);
        String clientToken = requireText(request, "clientToken");
        AttributeGroup existingByName = findAttributeGroupByName(region, name);
        if (existingByName != null) {
            if (clientToken.equals(existingByName.getClientToken())) {
                return existingByName;
            }
            throw conflict("An attribute group with name " + name + " already exists.");
        }
        String now = now();
        String id = newId();
        String account = regionResolver.getAccountId();
        AttributeGroup group = new AttributeGroup();
        group.setId(id);
        group.setName(name);
        group.setArn(attributeGroupArn(region, account, id));
        group.setDescription(optionalText(request, "description"));
        group.setAttributes(attributes);
        group.setCreationTime(now);
        group.setLastUpdateTime(now);
        group.setCreatedBy(callerPrincipal());
        group.setClientToken(clientToken);
        group.setTags(readTags(request.get("tags")));
        attributeGroups.put(attributeGroupKey(region, id), group);
        return group;
    }

    public AttributeGroup getAttributeGroup(String region, String specifier) {
        return requireAttributeGroup(region, specifier);
    }

    public synchronized AttributeGroup updateAttributeGroup(String region, String specifier, JsonNode request) {
        requireObject(request, "Request body");
        AttributeGroup group = requireAttributeGroup(region, specifier);
        boolean changed = false;
        if (request.has("name") && !request.get("name").isNull()) {
            String name = requireText(request, "name");
            validateName(name);
            AttributeGroup clash = findAttributeGroupByName(region, name);
            if (clash != null && !clash.getId().equals(group.getId())) {
                throw conflict("An attribute group with name " + name + " already exists.");
            }
            group.setName(name);
            changed = true;
        }
        if (request.has("description")) {
            group.setDescription(textOrNull(request, "description"));
            changed = true;
        }
        if (request.has("attributes") && !request.get("attributes").isNull()) {
            group.setAttributes(requireAttributes(request));
            changed = true;
        }
        if (changed) {
            group.setLastUpdateTime(now());
            attributeGroups.put(attributeGroupKey(region, group.getId()), group);
        }
        return group;
    }

    public synchronized AttributeGroup deleteAttributeGroup(String region, String specifier) {
        AttributeGroup group = requireAttributeGroup(region, specifier);
        for (Application application : applications.scan(key -> key.startsWith(regionPrefix(region)))) {
            if (application.getAssociatedAttributeGroupIds().contains(group.getId())) {
                throw conflict("Attribute group " + group.getName()
                        + " cannot be deleted because it is associated with an application.");
            }
        }
        attributeGroups.delete(attributeGroupKey(region, group.getId()));
        return group;
    }

    public Page<AttributeGroup> listAttributeGroups(String region, String maxResultsValue, String nextToken) {
        List<AttributeGroup> items = attributeGroups.scan(key -> key.startsWith(regionPrefix(region)));
        items.sort(Comparator.comparing(AttributeGroup::getName, Comparator.nullsLast(String::compareTo))
                .thenComparing(AttributeGroup::getId));
        return page(items, parseMaxResults(maxResultsValue), nextToken);
    }

    public synchronized AssociateAttributeGroupResult associateAttributeGroup(
            String region, String applicationSpecifier, String attributeGroupSpecifier) {
        Application application = requireApplication(region, applicationSpecifier);
        AttributeGroup group = requireAttributeGroup(region, attributeGroupSpecifier);
        if (!application.getAssociatedAttributeGroupIds().add(group.getId())) {
            throw conflict("Attribute group " + group.getId()
                    + " is already associated with application " + application.getId() + ".");
        }
        application.setLastUpdateTime(now());
        applications.put(applicationKey(region, application.getId()), application);
        return new AssociateAttributeGroupResult(application.getArn(), group.getArn());
    }

    public synchronized AssociateAttributeGroupResult disassociateAttributeGroup(
            String region, String applicationSpecifier, String attributeGroupSpecifier) {
        Application application = requireApplication(region, applicationSpecifier);
        AttributeGroup group = requireAttributeGroup(region, attributeGroupSpecifier);
        if (!application.getAssociatedAttributeGroupIds().remove(group.getId())) {
            throw resourceNotFound("Association between application " + application.getId()
                    + " and attribute group " + group.getId() + " was not found.");
        }
        application.setLastUpdateTime(now());
        applications.put(applicationKey(region, application.getId()), application);
        return new AssociateAttributeGroupResult(application.getArn(), group.getArn());
    }

    public Page<String> listAssociatedAttributeGroups(
            String region, String applicationSpecifier, String maxResultsValue, String nextToken) {
        Application application = requireApplication(region, applicationSpecifier);
        List<String> ids = new ArrayList<>(application.getAssociatedAttributeGroupIds());
        ids.sort(String::compareTo);
        return page(ids, parseMaxResults(maxResultsValue), nextToken);
    }

    public Page<AttributeGroup> listAttributeGroupsForApplication(
            String region, String applicationSpecifier, String maxResultsValue, String nextToken) {
        Application application = requireApplication(region, applicationSpecifier);
        List<AttributeGroup> groups = new ArrayList<>();
        for (String id : application.getAssociatedAttributeGroupIds()) {
            attributeGroups.get(attributeGroupKey(region, id)).ifPresent(groups::add);
        }
        groups.sort(Comparator.comparing(AttributeGroup::getName, Comparator.nullsLast(String::compareTo))
                .thenComparing(AttributeGroup::getId));
        return page(groups, parseMaxResults(maxResultsValue), nextToken);
    }

    public synchronized AssociateResourceResult associateResource(
            String region, String applicationSpecifier, String resourceType, String resource, JsonNode request) {
        Application application = requireApplication(region, applicationSpecifier);
        String type = requireResourceType(resourceType);
        String specifier = decode(resource);
        if (specifier == null || specifier.isBlank()) {
            throw validation("resource is required.");
        }
        List<String> options = readOptions(request);
        AssociatedResource existing = findAssociatedResource(application, type, specifier);
        if (existing != null) {
            throw conflict("Resource " + specifier + " is already associated with application "
                    + application.getId() + ".");
        }
        AssociatedResource link = resolveResource(region, type, specifier);
        link.setOptions(options);
        link.setAssociationTime(now());
        application.getAssociatedResources().add(link);
        application.setLastUpdateTime(now());
        applications.put(applicationKey(region, application.getId()), application);
        return new AssociateResourceResult(application.getArn(), link.getArn(), link.getOptions());
    }

    public AssociatedResource getAssociatedResource(
            String region, String applicationSpecifier, String resourceType, String resource) {
        Application application = requireApplication(region, applicationSpecifier);
        String type = requireResourceType(resourceType);
        AssociatedResource link = findAssociatedResource(application, type, decode(resource));
        if (link == null) {
            throw resourceNotFound("Resource " + resource + " is not associated with application "
                    + application.getId() + ".");
        }
        return link;
    }

    public synchronized AssociateResourceResult disassociateResource(
            String region, String applicationSpecifier, String resourceType, String resource) {
        Application application = requireApplication(region, applicationSpecifier);
        String type = requireResourceType(resourceType);
        String specifier = decode(resource);
        AssociatedResource link = findAssociatedResource(application, type, specifier);
        if (link == null) {
            throw resourceNotFound("Resource " + resource + " is not associated with application "
                    + application.getId() + ".");
        }
        application.getAssociatedResources().remove(link);
        application.setLastUpdateTime(now());
        applications.put(applicationKey(region, application.getId()), application);
        return new AssociateResourceResult(application.getArn(), link.getArn(), link.getOptions());
    }

    public Page<AssociatedResource> listAssociatedResources(
            String region, String applicationSpecifier, String maxResultsValue, String nextToken) {
        Application application = requireApplication(region, applicationSpecifier);
        List<AssociatedResource> items = new ArrayList<>(application.getAssociatedResources());
        items.sort(Comparator.comparing(AssociatedResource::getName, Comparator.nullsLast(String::compareTo))
                .thenComparing(AssociatedResource::getArn, Comparator.nullsLast(String::compareTo)));
        return page(items, parseMaxResults(maxResultsValue), nextToken);
    }

    @Override
    public String serviceKey() {
        return ARN_SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(taggedResource(region, arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = taggedResource(region, arn);
        Map<String, String> current = tagged.tags();
        if (tags != null) {
            current.putAll(tags);
        }
        persistTagged(region, tagged);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = taggedResource(region, arn);
        if (tagKeys != null) {
            for (String key : tagKeys) {
                if (key != null && key.toLowerCase().startsWith("aws:")) {
                    throw validation("Customers cannot remove tag keys starting with aws:.");
                }
                tagged.tags().remove(key);
            }
        }
        persistTagged(region, tagged);
    }

    private Tagged taggedResource(String region, String arn) {
        String decoded = decode(arn);
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decoded);
        } catch (IllegalArgumentException e) {
            throw resourceNotFound("Resource " + decoded + " was not found.");
        }
        if (!ARN_SERVICE.equals(parsed.service())) {
            throw resourceNotFound("Resource " + decoded + " was not found.");
        }
        String resource = parsed.resource();
        if (resource.startsWith("/")) {
            resource = resource.substring(1);
        }
        if (resource.startsWith("applications/")) {
            String id = resource.substring("applications/".length());
            Application application = requireApplication(region, id);
            return new Tagged(application, null);
        }
        if (resource.startsWith("attribute-groups/")) {
            String id = resource.substring("attribute-groups/".length());
            AttributeGroup group = requireAttributeGroup(region, id);
            return new Tagged(null, group);
        }
        throw resourceNotFound("Resource " + decoded + " was not found.");
    }

    private void persistTagged(String region, Tagged tagged) {
        String now = now();
        if (tagged.application() != null) {
            tagged.application().setLastUpdateTime(now);
            applications.put(applicationKey(region, tagged.application().getId()), tagged.application());
        } else {
            tagged.attributeGroup().setLastUpdateTime(now);
            attributeGroups.put(attributeGroupKey(region, tagged.attributeGroup().getId()), tagged.attributeGroup());
        }
    }

    private record Tagged(Application application, AttributeGroup attributeGroup) {
        Map<String, String> tags() {
            return application != null ? application.getTags() : attributeGroup.getTags();
        }
    }

    private Application requireApplication(String region, String specifier) {
        String decoded = decode(specifier);
        if (decoded == null || decoded.isBlank()) {
            throw validation("application is required.");
        }
        String id = idFromArn(decoded, "applications/");
        if (id != null) {
            return applications.get(applicationKey(region, id)).orElseThrow(() -> resourceNotFound(
                    "Application " + decoded + " was not found."));
        }
        Application byId = applications.get(applicationKey(region, decoded)).orElse(null);
        if (byId != null) {
            return byId;
        }
        Application byName = findApplicationByName(region, decoded);
        if (byName != null) {
            return byName;
        }
        throw resourceNotFound("Application " + decoded + " was not found.");
    }

    private AttributeGroup requireAttributeGroup(String region, String specifier) {
        String decoded = decode(specifier);
        if (decoded == null || decoded.isBlank()) {
            throw validation("attributeGroup is required.");
        }
        String id = idFromArn(decoded, "attribute-groups/");
        if (id != null) {
            return attributeGroups.get(attributeGroupKey(region, id)).orElseThrow(() -> resourceNotFound(
                    "Attribute group " + decoded + " was not found."));
        }
        AttributeGroup byId = attributeGroups.get(attributeGroupKey(region, decoded)).orElse(null);
        if (byId != null) {
            return byId;
        }
        AttributeGroup byName = findAttributeGroupByName(region, decoded);
        if (byName != null) {
            return byName;
        }
        throw resourceNotFound("Attribute group " + decoded + " was not found.");
    }

    private Application findApplicationByName(String region, String name) {
        for (Application application : applications.scan(key -> key.startsWith(regionPrefix(region)))) {
            if (name.equals(application.getName())) {
                return application;
            }
        }
        return null;
    }

    private AttributeGroup findAttributeGroupByName(String region, String name) {
        for (AttributeGroup group : attributeGroups.scan(key -> key.startsWith(regionPrefix(region)))) {
            if (name.equals(group.getName())) {
                return group;
            }
        }
        return null;
    }

    private AssociatedResource findAssociatedResource(Application application, String type, String specifier) {
        if (specifier == null) {
            return null;
        }
        for (AssociatedResource link : application.getAssociatedResources()) {
            if (!type.equals(link.getResourceType())) {
                continue;
            }
            if (specifier.equals(link.getName()) || specifier.equals(link.getArn())) {
                return link;
            }
        }
        return null;
    }

    private AssociatedResource resolveResource(String region, String type, String specifier) {
        AssociatedResource link = new AssociatedResource();
        link.setResourceType(type);
        if ("CFN_STACK".equals(type)) {
            Stack stack = requireCloudFormationStack(region, specifier);
            link.setName(stack.getStackName());
            link.setArn(stack.getStackId());
            return link;
        }
        link.setName(specifier);
        link.setArn("arn:aws:resource-groups:" + region + ":" + regionResolver.getAccountId()
                + ":tagValue/" + specifier);
        return link;
    }

    private Stack requireCloudFormationStack(String region, String specifier) {
        try {
            List<Stack> stacks = cloudFormation.describeStacks(specifier, region);
            if (stacks == null || stacks.isEmpty() || stacks.get(0) == null) {
                throw resourceNotFound("CloudFormation stack " + specifier + " was not found.");
            }
            return stacks.get(0);
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            throw resourceNotFound("CloudFormation stack " + specifier + " was not found.");
        }
    }

    private String idFromArn(String specifier, String resourcePrefix) {
        if (!specifier.startsWith("arn:")) {
            return null;
        }
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(specifier);
        } catch (IllegalArgumentException e) {
            throw resourceNotFound("Resource " + specifier + " was not found.");
        }
        if (!ARN_SERVICE.equals(parsed.service())) {
            throw resourceNotFound("Resource " + specifier + " was not found.");
        }
        String resource = parsed.resource();
        if (resource.startsWith("/")) {
            resource = resource.substring(1);
        }
        if (!resource.startsWith(resourcePrefix)) {
            throw resourceNotFound("Resource " + specifier + " was not found.");
        }
        return resource.substring(resourcePrefix.length());
    }

    private String applicationArn(String region, String account, String id) {
        return AwsArnUtils.Arn.of(ARN_SERVICE, region, account, "/applications/" + id).toString();
    }

    private String attributeGroupArn(String region, String account, String id) {
        return AwsArnUtils.Arn.of(ARN_SERVICE, region, account, "/attribute-groups/" + id).toString();
    }

    private String callerPrincipal() {
        return "arn:aws:iam::" + regionResolver.getAccountId() + ":root";
    }

    private static String applicationKey(String region, String id) {
        return region + "::" + id;
    }

    private static String attributeGroupKey(String region, String id) {
        return region + "::" + id;
    }

    private static String regionPrefix(String region) {
        return region + "::";
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
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

    private static void validateName(String name) {
        if (name.length() < 1 || name.length() > 256 || !NAME_PATTERN.matcher(name).matches()) {
            throw validation("name must match [a-zA-Z0-9][-.a-zA-Z0-9_]{0,255}.");
        }
    }

    private String requireAttributes(JsonNode request) {
        String attributes = requireText(request, "attributes");
        try {
            JsonNode node = objectMapper.readTree(attributes);
            if (node == null || !node.isObject()) {
                throw validation("attributes must be a JSON object encoded as a string.");
            }
        } catch (JsonProcessingException e) {
            throw validation("attributes must be a JSON object encoded as a string.");
        }
        return attributes;
    }

    private static String requireResourceType(String resourceType) {
        String decoded = decode(resourceType);
        if (decoded == null || !RESOURCE_TYPES.contains(decoded)) {
            throw validation("resourceType must be CFN_STACK or RESOURCE_TAG_VALUE.");
        }
        return decoded;
    }

    private static List<String> readOptions(JsonNode request) {
        if (request == null || !request.has("options") || request.get("options").isNull()) {
            return List.of("APPLY_APPLICATION_TAG");
        }
        JsonNode array = request.get("options");
        if (!array.isArray()) {
            throw validation("options must be an array.");
        }
        List<String> options = new ArrayList<>();
        for (JsonNode value : array) {
            if (value == null || !value.isTextual() || !ASSOCIATION_OPTIONS.contains(value.textValue())) {
                throw validation("options members must be APPLY_APPLICATION_TAG or SKIP_APPLICATION_TAG.");
            }
            if (!options.contains(value.textValue())) {
                options.add(value.textValue());
            }
        }
        return options;
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isObject() || tagsNode.size() > 50) {
            throw validation("tags must be an object with at most 50 entries.");
        }
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode valueNode = entry.getValue();
            if (valueNode == null || !valueNode.isTextual()) {
                throw validation("tags contains an invalid key or value.");
            }
            tags.put(entry.getKey(), valueNode.textValue());
        });
        return tags;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " is required.");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        return textOrNull(parent, field);
    }

    private static String textOrNull(JsonNode parent, String field) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static int parseMaxResults(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > MAX_RESULTS) {
                throw validation("maxResults must be between 1 and " + MAX_RESULTS + ".");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw validation("maxResults must be an integer between 1 and " + MAX_RESULTS + ".");
        }
    }

    private static <T> Page<T> page(List<T> items, int maxResults, String nextToken) {
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + maxResults, items.size());
        String responseToken = end < items.size() ? encodeOffset(end) : null;
        return new Page<>(items.subList(offset, end), responseToken);
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw validation("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 0 || offset > resultSize) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static AwsException resourceNotFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    public record AssociateAttributeGroupResult(String applicationArn, String attributeGroupArn) {
    }

    public record AssociateResourceResult(String applicationArn, String resourceArn, List<String> options) {
        public AssociateResourceResult {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }
}
