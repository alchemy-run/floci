package io.github.hectorvent.floci.services.resourcegroups;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.resourcegroups.model.ResourceGroup;
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService;
import io.github.hectorvent.floci.services.resourcegroupstagging.model.ResourceTagMapping;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AWS Resource Groups restJson1 (2017-11-27).
 *
 * <p>Public paths ({@code /groups}, {@code /resources/search},
 * {@code /resources/{Arn}/tags}, …) are rewritten by
 * {@link ResourceGroupsRoutingFilter} using SigV4 credential scope
 * {@code resource-groups}.
 */
@ApplicationScoped
public class ResourceGroupsService implements Resettable {

    static final String SERVICE = "resource-groups";
    private static final String APPLICATION_GROUP_TYPE = "AWS::ResourceGroups::ApplicationGroup";
    private static final String CAPACITY_POOL_TYPE = "AWS::EC2::CapacityReservationPool";
    private static final String HOST_MGMT_TYPE = "AWS::EC2::HostManagement";
    /**
     * {@code TagSyncTaskArn} from the Resource Groups API. CancelTagSyncTask
     * does not document {@code NotFoundException}; malformed ARNs and missing
     * tasks both surface as {@code BadRequestException} so distilled maps a
     * typed error instead of {@code UnknownAwsError}.
     */
    private static final Pattern TAG_SYNC_TASK_ARN = Pattern.compile(
            "arn:aws(-[a-z]+)*:resource-groups:[a-z]{2}(-[a-z]+)+-\\d{1}:[0-9]{12}"
                    + ":group/[a-zA-Z0-9_.-]{1,150}/[a-z0-9]{26}/tag-sync-task/[a-z0-9]{26}");

    private final StorageBackend<String, ResourceGroup> store;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final ResourceGroupsTaggingService taggingService;

    private String gleDesiredStatus = "INACTIVE";
    private String gleStatus = "INACTIVE";

    @Inject
    public ResourceGroupsService(StorageFactory storageFactory, RegionResolver regionResolver,
                                 ObjectMapper objectMapper, ResourceGroupsTaggingService taggingService) {
        this(storageFactory.create("resource-groups", "resource-groups.json",
                        new TypeReference<Map<String, ResourceGroup>>() {
                        }),
                regionResolver, objectMapper, taggingService);
    }

    ResourceGroupsService(StorageBackend<String, ResourceGroup> store, RegionResolver regionResolver,
                          ObjectMapper objectMapper, ResourceGroupsTaggingService taggingService) {
        this.store = store;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.taggingService = taggingService;
    }

    @Override
    public synchronized void clear() {
        store.clear();
        gleDesiredStatus = "INACTIVE";
        gleStatus = "INACTIVE";
    }

    public synchronized ObjectNode createGroup(String region, JsonNode request) {
        requireObject(request);
        String name = requiredText(request, "Name");
        validateGroupName(name);
        if (store.get(storageKey(region, name)).isPresent()) {
            throw new AwsException("BadRequestException",
                    "Cannot create group: a group already exists with the name '" + name + "'.", 400);
        }
        JsonNode resourceQuery = request.get("ResourceQuery");
        JsonNode configuration = request.get("Configuration");
        boolean hasQuery = resourceQuery != null && !resourceQuery.isNull() && !resourceQuery.isMissingNode();
        boolean hasConfig = configuration != null && configuration.isArray() && !configuration.isEmpty();
        if (hasQuery && hasConfig) {
            throw new AwsException("BadRequestException",
                    "ResourceQuery and Configuration are mutually exclusive.", 400);
        }

        ResourceGroup group = new ResourceGroup();
        group.setName(name);
        group.setArn(groupArn(region, name));
        group.setDescription(optionalText(request, "Description"));
        if (hasQuery) {
            group.setResourceQuery(requireQuery(resourceQuery));
        }
        if (hasConfig) {
            group.setConfiguration(configuration.deepCopy());
        }
        Map<String, String> tags = readTagMap(request.get("Tags"));
        group.setTags(tags);
        store.put(storageKey(region, name), group);
        if (!tags.isEmpty()) {
            taggingService.tagResources(List.of(group.getArn()), tags, region);
        }
        return toCreateOutput(group);
    }

    public ObjectNode getGroup(String region, JsonNode request) {
        ResourceGroup group = requireGroup(region, groupIdentifier(request));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Group", toGroup(group));
        return response;
    }

    public ObjectNode deleteGroup(String region, JsonNode request) {
        ResourceGroup group = requireGroup(region, groupIdentifier(request));
        store.delete(storageKey(region, group.getName()));
        taggingService.deleteResources(List.of(group.getArn()), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Group", toGroup(group));
        return response;
    }

    public ObjectNode updateGroup(String region, JsonNode request) {
        requireObject(request);
        ResourceGroup group = requireGroup(region, groupIdentifier(request));
        if (request.has("Description") && !request.get("Description").isNull()) {
            group.setDescription(request.get("Description").asText());
        } else if (request.has("Description")) {
            group.setDescription("");
        }
        store.put(storageKey(region, group.getName()), group);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Group", toGroup(group));
        return response;
    }

    public ObjectNode getGroupQuery(String region, JsonNode request) {
        ResourceGroup group = requireGroup(region, groupIdentifier(request));
        if (group.getResourceQuery() == null) {
            throw new AwsException("BadRequestException",
                    "The specified group is not a query-based group.", 400);
        }
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode groupQuery = response.putObject("GroupQuery");
        groupQuery.put("GroupName", group.getName());
        groupQuery.set("ResourceQuery", group.getResourceQuery());
        return response;
    }

    public ObjectNode updateGroupQuery(String region, JsonNode request) {
        requireObject(request);
        ResourceGroup group = requireGroup(region, groupIdentifier(request));
        JsonNode query = request.get("ResourceQuery");
        if (query == null || query.isNull() || query.isMissingNode()) {
            throw new AwsException("BadRequestException", "ResourceQuery is required.", 400);
        }
        group.setResourceQuery(requireQuery(query));
        store.put(storageKey(region, group.getName()), group);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode groupQuery = response.putObject("GroupQuery");
        groupQuery.put("GroupName", group.getName());
        groupQuery.set("ResourceQuery", group.getResourceQuery());
        return response;
    }

    public ObjectNode getGroupConfiguration(String region, JsonNode request) {
        ResourceGroup group = requireGroup(region, groupIdentifier(request));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("GroupConfiguration", toGroupConfiguration(group));
        return response;
    }

    public ObjectNode putGroupConfiguration(String region, JsonNode request) {
        requireObject(request);
        ResourceGroup group = requireGroup(region, groupIdentifier(request));
        JsonNode configuration = request.get("Configuration");
        if (configuration != null && configuration.isArray()) {
            group.setConfiguration(configuration.deepCopy());
        } else {
            group.setConfiguration(null);
        }
        store.put(storageKey(region, group.getName()), group);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listGroups(String region, JsonNode request) {
        List<ResourceGroup> groups = store.scan(key -> key.startsWith(region + "::"));
        groups.sort(Comparator.comparing(ResourceGroup::getName, Comparator.nullsLast(String::compareTo)));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode identifiers = response.putArray("GroupIdentifiers");
        ArrayNode groupList = response.putArray("Groups");
        for (ResourceGroup group : groups) {
            ObjectNode identifier = identifiers.addObject();
            identifier.put("GroupName", group.getName());
            identifier.put("GroupArn", group.getArn());
            if (group.getDescription() != null) {
                identifier.put("Description", group.getDescription());
            }
            groupList.add(toGroup(group));
        }
        return response;
    }

    public ObjectNode getTags(String region, String arn) {
        ResourceGroup group = requireGroupByArn(region, arn);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Arn", group.getArn());
        response.set("Tags", tagsToObject(group.getTags()));
        return response;
    }

    public synchronized ObjectNode tag(String region, String arn, JsonNode request) {
        requireObject(request);
        ResourceGroup group = requireGroupByArn(region, arn);
        Map<String, String> incoming = readTagMap(request.get("Tags"));
        group.getTags().putAll(incoming);
        store.put(storageKey(region, group.getName()), group);
        if (!incoming.isEmpty()) {
            taggingService.tagResources(List.of(group.getArn()), incoming, region);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Arn", group.getArn());
        response.set("Tags", tagsToObject(group.getTags()));
        return response;
    }

    public synchronized ObjectNode untag(String region, String arn, JsonNode request) {
        requireObject(request);
        ResourceGroup group = requireGroupByArn(region, arn);
        List<String> keys = toStringList(request.get("Keys"));
        for (String key : keys) {
            group.getTags().remove(key);
        }
        store.put(storageKey(region, group.getName()), group);
        if (!keys.isEmpty()) {
            taggingService.untagResources(List.of(group.getArn()), keys, region);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Arn", group.getArn());
        ArrayNode keyNode = response.putArray("Keys");
        keys.forEach(keyNode::add);
        return response;
    }

    public ObjectNode listGroupResources(String region, JsonNode request) {
        ResourceGroup group = requireGroup(region, groupIdentifier(request));
        List<ResourceIdentifier> members = resolveMembers(region, group);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode resources = response.putArray("Resources");
        ArrayNode identifiers = response.putArray("ResourceIdentifiers");
        for (ResourceIdentifier member : members) {
            ObjectNode item = resources.addObject();
            ObjectNode identifier = item.putObject("Identifier");
            identifier.put("ResourceArn", member.arn());
            if (member.type() != null) {
                identifier.put("ResourceType", member.type());
            }
            ObjectNode listed = identifiers.addObject();
            listed.put("ResourceArn", member.arn());
            if (member.type() != null) {
                listed.put("ResourceType", member.type());
            }
        }
        return response;
    }

    public synchronized ObjectNode groupResources(String region, JsonNode request) {
        return mutateMembership(region, request, true);
    }

    public synchronized ObjectNode ungroupResources(String region, JsonNode request) {
        return mutateMembership(region, request, false);
    }

    public ObjectNode listGroupingStatuses(String region, JsonNode request) {
        requireObject(request);
        String identifier = text(request, "Group");
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("BadRequestException", "Group is required.", 400);
        }
        ResourceGroup group = findGroup(region, identifier);
        if (group == null) {
            throw new AwsException("NotFoundException",
                    "Group not found: " + identifier, 404);
        }
        if (!isApplicationGroup(group)) {
            throw new AwsException("BadRequestException",
                    "Cannot list grouping statuses for a group that is not an application group.", 400);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Group", group.getName());
        response.putArray("GroupingStatuses");
        return response;
    }

    public ObjectNode searchResources(String region, JsonNode request) {
        requireObject(request);
        JsonNode query = request.get("ResourceQuery");
        if (query == null || query.isNull() || query.isMissingNode()) {
            throw new AwsException("BadRequestException", "ResourceQuery is required.", 400);
        }
        requireQuery(query);
        List<ResourceIdentifier> matches = searchByQuery(region, query);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode identifiers = response.putArray("ResourceIdentifiers");
        for (ResourceIdentifier match : matches) {
            ObjectNode item = identifiers.addObject();
            item.put("ResourceArn", match.arn());
            if (match.type() != null) {
                item.put("ResourceType", match.type());
            }
        }
        return response;
    }

    public ObjectNode getAccountSettings() {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode settings = response.putObject("AccountSettings");
        settings.put("GroupLifecycleEventsDesiredStatus", gleDesiredStatus);
        settings.put("GroupLifecycleEventsStatus", gleStatus);
        return response;
    }

    public synchronized ObjectNode updateAccountSettings(JsonNode request) {
        requireObject(request);
        String desired = optionalText(request, "GroupLifecycleEventsDesiredStatus");
        if (desired != null && !desired.isBlank()) {
            gleDesiredStatus = desired;
            gleStatus = desired;
        }
        return getAccountSettings();
    }

    public ObjectNode listTagSyncTasks() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("TagSyncTasks");
        return response;
    }

    public ObjectNode startTagSyncTask(String region, JsonNode request) {
        requireObject(request);
        String identifier = text(request, "Group");
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("BadRequestException", "Group is required.", 400);
        }
        ResourceGroup group = requireGroup(region, identifier);
        if (!isApplicationGroup(group)) {
            throw new AwsException("BadRequestException",
                    "Tag-sync tasks can only be started on an application group.", 400);
        }
        throw new AwsException("ForbiddenException",
                "Group Lifecycle Events is in maintenance mode and is closed to new customers.", 403);
    }

    public ObjectNode getTagSyncTask(JsonNode request) {
        requireObject(request);
        String taskArn = text(request, "TaskArn");
        if (taskArn == null || taskArn.isBlank()) {
            throw new AwsException("BadRequestException", "TaskArn is required.", 400);
        }
        throw new AwsException("NotFoundException", "Tag-sync task not found: " + taskArn, 404);
    }

    public ObjectNode cancelTagSyncTask(JsonNode request) {
        requireObject(request);
        String taskArn = requireTagSyncTaskArn(request);
        throw new AwsException("BadRequestException",
                "Cannot cancel tag-sync task: the TaskArn is invalid or the task does not exist: "
                        + taskArn, 400);
    }

    private ObjectNode mutateMembership(String region, JsonNode request, boolean add) {
        requireObject(request);
        String identifier = text(request, "Group");
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("BadRequestException", "Group is required.", 400);
        }
        ResourceGroup group = requireGroup(region, identifier);
        List<String> arns = toStringList(request.get("ResourceArns"));
        if (arns.isEmpty()) {
            throw new AwsException("BadRequestException", "ResourceArns is required.", 400);
        }
        if (!supportsExplicitMembership(group)) {
            throw new AwsException("BadRequestException",
                    "GroupResources is only supported for AWS::EC2::CapacityReservationPool, "
                            + "AWS::EC2::HostManagement, and AWS::ResourceGroups::ApplicationGroup groups.",
                    400);
        }
        Set<String> members = new LinkedHashSet<>(group.getMembers());
        List<String> succeeded = new ArrayList<>();
        List<Failed> failed = new ArrayList<>();
        for (String arn : arns) {
            if (!isValidMemberArn(group, arn)) {
                failed.add(new Failed(arn, "ResourceArnValidationException",
                        "The resource is not a valid resource type for this group."));
                continue;
            }
            if (add) {
                members.add(arn);
            } else {
                members.remove(arn);
            }
            succeeded.add(arn);
        }
        group.setMembers(new ArrayList<>(members));
        store.put(storageKey(region, group.getName()), group);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode succeededNode = response.putArray("Succeeded");
        succeeded.forEach(succeededNode::add);
        ArrayNode failedNode = response.putArray("Failed");
        for (Failed item : failed) {
            ObjectNode node = failedNode.addObject();
            node.put("ResourceArn", item.arn());
            node.put("ErrorCode", item.code());
            node.put("ErrorMessage", item.message());
        }
        response.putArray("Pending");
        return response;
    }

    private List<ResourceIdentifier> resolveMembers(String region, ResourceGroup group) {
        if (group.getResourceQuery() != null) {
            return searchByQuery(region, group.getResourceQuery());
        }
        List<ResourceIdentifier> members = new ArrayList<>();
        for (String arn : group.getMembers()) {
            members.add(new ResourceIdentifier(arn, cloudFormationType(arn)));
        }
        return members;
    }

    private List<ResourceIdentifier> searchByQuery(String region, JsonNode resourceQuery) {
        String type = text(resourceQuery, "Type");
        String queryText = text(resourceQuery, "Query");
        if (!"TAG_FILTERS_1_0".equals(type) || queryText == null || queryText.isBlank()) {
            return List.of();
        }
        JsonNode query;
        try {
            query = objectMapper.readTree(queryText);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", "Query is not valid JSON.", 400);
        }
        List<ResourceGroupsTaggingService.TagFilter> tagFilters = new ArrayList<>();
        JsonNode tagFiltersNode = query.get("TagFilters");
        if (tagFiltersNode != null && tagFiltersNode.isArray()) {
            for (JsonNode filter : tagFiltersNode) {
                String key = text(filter, "Key");
                if (key == null || key.isBlank()) {
                    continue;
                }
                tagFilters.add(new ResourceGroupsTaggingService.TagFilter(key, toStringList(filter.get("Values"))));
            }
        }
        List<String> typeFilters = taggingTypeFilters(query.get("ResourceTypeFilters"));
        ResourceGroupsTaggingService.PageResult result = taggingService.getResources(
                List.of(), tagFilters, typeFilters, null, 0, region);
        List<ResourceIdentifier> matches = new ArrayList<>();
        for (ResourceTagMapping mapping : result.items()) {
            matches.add(new ResourceIdentifier(mapping.getResourceArn(),
                    cloudFormationType(mapping.getResourceArn())));
        }
        return matches;
    }

    private List<String> taggingTypeFilters(JsonNode resourceTypeFilters) {
        List<String> filters = new ArrayList<>();
        if (resourceTypeFilters == null || !resourceTypeFilters.isArray()) {
            return filters;
        }
        for (JsonNode node : resourceTypeFilters) {
            String value = node.asText();
            if (value == null || value.isBlank() || "AWS::AllSupported".equals(value)) {
                continue;
            }
            String[] parts = value.split("::");
            if (parts.length == 3) {
                filters.add(parts[1].toLowerCase(Locale.ROOT) + ":" + parts[2].toLowerCase(Locale.ROOT));
            }
        }
        return filters;
    }

    private boolean supportsExplicitMembership(ResourceGroup group) {
        return hasConfigurationType(group, CAPACITY_POOL_TYPE)
                || hasConfigurationType(group, HOST_MGMT_TYPE)
                || isApplicationGroup(group);
    }

    private boolean isApplicationGroup(ResourceGroup group) {
        return hasConfigurationType(group, APPLICATION_GROUP_TYPE);
    }

    private boolean hasConfigurationType(ResourceGroup group, String type) {
        JsonNode configuration = group.getConfiguration();
        if (configuration == null || !configuration.isArray()) {
            return false;
        }
        for (JsonNode item : configuration) {
            if (type.equals(text(item, "Type"))) {
                return true;
            }
        }
        return false;
    }

    private boolean isValidMemberArn(ResourceGroup group, String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (hasConfigurationType(group, CAPACITY_POOL_TYPE)) {
                return "ec2".equals(parsed.service())
                        && parsed.resource().startsWith("capacity-reservation/");
            }
            if (hasConfigurationType(group, HOST_MGMT_TYPE)) {
                return "ec2".equals(parsed.service()) && parsed.resource().startsWith("dedicated-host/");
            }
            return parsed.service() != null && !parsed.service().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String cloudFormationType(String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            String service = parsed.service();
            String resource = parsed.resource();
            String resourceType = resource.contains("/")
                    ? resource.substring(0, resource.indexOf('/'))
                    : resource;
            if ("s3".equals(service)) {
                return "AWS::S3::Bucket";
            }
            if (service == null || service.isBlank()) {
                return null;
            }
            return "AWS::" + capitalizeService(service) + "::" + toPascal(resourceType);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String capitalizeService(String service) {
        if ("lambda".equals(service)) {
            return "Lambda";
        }
        if ("ec2".equals(service)) {
            return "EC2";
        }
        if ("dynamodb".equals(service)) {
            return "DynamoDB";
        }
        return toPascal(service);
    }

    private static String toPascal(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String[] parts = value.split("[-_]");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }
        return out.toString();
    }

    private ResourceGroup requireGroup(String region, String identifier) {
        ResourceGroup group = findGroup(region, identifier);
        if (group == null) {
            throw new AwsException("NotFoundException",
                    "Group not found: " + identifier, 404);
        }
        return group;
    }

    private ResourceGroup requireGroupByArn(String region, String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("BadRequestException", "Arn is required.", 400);
        }
        ResourceGroup group = findGroup(region, arn);
        if (group == null) {
            throw new AwsException("NotFoundException", "Group not found: " + arn, 404);
        }
        return group;
    }

    private ResourceGroup findGroup(String region, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        String name = identifier;
        if (identifier.startsWith("arn:")) {
            try {
                AwsArnUtils.Arn parsed = AwsArnUtils.parse(identifier);
                String resource = parsed.resource();
                if (resource.startsWith("group/")) {
                    name = resource.substring("group/".length());
                    int slash = name.indexOf('/');
                    if (slash >= 0) {
                        name = name.substring(0, slash);
                    }
                }
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return store.get(storageKey(region, name)).orElse(null);
    }

    private String groupIdentifier(JsonNode request) {
        requireObject(request);
        String group = text(request, "Group");
        if (group != null && !group.isBlank()) {
            return group;
        }
        String groupName = text(request, "GroupName");
        if (groupName != null && !groupName.isBlank()) {
            return groupName;
        }
        throw new AwsException("BadRequestException", "Group is required.", 400);
    }

    private JsonNode requireQuery(JsonNode resourceQuery) {
        if (!resourceQuery.isObject()) {
            throw new AwsException("BadRequestException", "ResourceQuery must be an object.", 400);
        }
        String type = text(resourceQuery, "Type");
        String query = text(resourceQuery, "Query");
        if (type == null || type.isBlank() || query == null) {
            throw new AwsException("BadRequestException", "ResourceQuery.Type and Query are required.", 400);
        }
        return resourceQuery.deepCopy();
    }

    private ObjectNode toCreateOutput(ResourceGroup group) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Group", toGroup(group));
        if (group.getResourceQuery() != null) {
            response.set("ResourceQuery", group.getResourceQuery());
        }
        if (!group.getTags().isEmpty()) {
            response.set("Tags", tagsToObject(group.getTags()));
        }
        if (group.getConfiguration() != null) {
            response.set("GroupConfiguration", toGroupConfiguration(group));
        }
        return response;
    }

    private ObjectNode toGroup(ResourceGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("GroupArn", group.getArn());
        node.put("Name", group.getName());
        if (group.getDescription() != null) {
            node.put("Description", group.getDescription());
        }
        return node;
    }

    private ObjectNode toGroupConfiguration(ResourceGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        if (group.getConfiguration() != null) {
            node.set("Configuration", group.getConfiguration());
        } else {
            node.putArray("Configuration");
        }
        node.put("Status", "UPDATE_COMPLETE");
        return node;
    }

    private ObjectNode tagsToObject(Map<String, String> tags) {
        ObjectNode node = objectMapper.createObjectNode();
        tags.forEach(node::put);
        return node;
    }

    private Map<String, String> readTagMap(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return tags;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue() != null && !field.getValue().isNull()) {
                    tags.put(field.getKey(), field.getValue().asText());
                }
            }
        }
        return tags;
    }

    private List<String> toStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    private void validateGroupName(String name) {
        if (name.length() > 128) {
            throw new AwsException("BadRequestException", "Group name must be 128 characters or fewer.", 400);
        }
        if (name.regionMatches(true, 0, "AWS", 0, 3)) {
            throw new AwsException("BadRequestException", "Group names cannot start with 'AWS'.", 400);
        }
    }

    private String requireTagSyncTaskArn(JsonNode request) {
        String taskArn = text(request, "TaskArn");
        if (taskArn == null || taskArn.isBlank()) {
            throw new AwsException("BadRequestException", "TaskArn is required.", 400);
        }
        if (taskArn.length() < 12 || taskArn.length() > 1600
                || !TAG_SYNC_TASK_ARN.matcher(taskArn).matches()) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'taskArn' failed to satisfy constraint: "
                            + "Member must satisfy regular expression pattern for TagSyncTaskArn.",
                    400);
        }
        return taskArn;
    }

    private String requiredText(JsonNode request, String field) {
        String value = text(request, field);
        if (value == null || value.isBlank()) {
            throw new AwsException("BadRequestException", field + " is required.", 400);
        }
        return value;
    }

    private String optionalText(JsonNode request, String field) {
        return text(request, field);
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new AwsException("BadRequestException", "Request body must be a JSON object.", 400);
        }
    }

    private String groupArn(String region, String name) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), "group/" + name).toString();
    }

    private static String storageKey(String region, String name) {
        return region + "::" + name;
    }

    private record ResourceIdentifier(String arn, String type) {
    }

    private record Failed(String arn, String code, String message) {
    }
}
