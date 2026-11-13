package io.floci.aws.services.resourcegroups;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.resource.ExplorerResource;
import io.floci.aws.core.resource.ResourceProvider;
import io.floci.aws.core.resource.SupportedResourceType;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.CapacityReservation;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.resourcegroups.model.ResourceGroup;
import io.floci.aws.services.resourcegroups.model.ResourceGroup.ResourceQuery;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * AWS Resource Groups: groups defined by a tag or CloudFormation stack query, by a service
 * configuration (EC2 capacity reservation pools hold members added with {@code GroupResources}),
 * or both; their tags; ad-hoc {@code SearchResources}; and the account's group lifecycle events
 * setting, which while {@code ACTIVE} publishes group state and membership changes to EventBridge.
 * Application groups (myApplications) cannot be created through this API, so the operations that
 * only apply to them (grouping statuses, tag-sync tasks) answer the way AWS answers for any other
 * group.
 */
@ApplicationScoped
public class ResourceGroupsService implements Resettable, ResourceProvider {
    static final String RESOURCE_TYPE = "AWS::EC2::CapacityReservation";
    static final String POOL_TYPE = "AWS::EC2::CapacityReservationPool";
    static final String GENERIC_TYPE = "AWS::ResourceGroups::Generic";
    private static final Set<String> CONFIGURATION_TYPES = Set.of(POOL_TYPE, GENERIC_TYPE,
            "AWS::EC2::HostManagement", "AWS::ResourceGroups::ApplicationGroup");
    static final int MAX_PAGES = 256;
    static final long PAGE_IDLE_MILLIS = 15 * 60 * 1000L;
    private static final String EVENT_SOURCE = "aws.resource-groups";

    private final AccountAwareStorageBackend<ResourceGroup> groups;
    private final AccountAwareStorageBackend<AccountSettings> settings;
    private final Ec2Service ec2;
    private final ResourceGroupsQueries queries;
    private final Instance<EventBridgeService> eventBridge;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final AtomicLong eventSequence = new AtomicLong();

    private record Page(String groupArn, String generation, List<String> filter,
                        List<ResourceGroupsQueries.Resource> remaining, long lastRead) {}

    /** The account's group lifecycle events setting. */
    @RegisterForReflection
    public record AccountSettings(String desiredStatus, String status) {
        static final AccountSettings DEFAULT = new AccountSettings("INACTIVE", "INACTIVE");
    }

    @Inject
    public ResourceGroupsService(StorageFactory storageFactory, Ec2Service ec2, ResourceGroupsQueries queries,
                                 Instance<EventBridgeService> eventBridge) {
        this(storageFactory.create("resource-groups", "resource-groups.json",
                        new TypeReference<Map<String, ResourceGroup>>() {}),
                storageFactory.create("resource-groups", "resource-groups-settings.json",
                        new TypeReference<Map<String, AccountSettings>>() {}),
                ec2, queries, eventBridge, Clock.systemUTC());
    }

    /** Capacity pools only: no query resolution and no lifecycle events. */
    public ResourceGroupsService(StorageFactory storageFactory, Ec2Service ec2) {
        this(storageFactory.create("resource-groups", "resource-groups.json",
                        new TypeReference<Map<String, ResourceGroup>>() {}),
                storageFactory.create("resource-groups", "resource-groups-settings.json",
                        new TypeReference<Map<String, AccountSettings>>() {}),
                ec2, new ResourceGroupsQueries(null, null, new ObjectMapper()), null, Clock.systemUTC());
    }

    ResourceGroupsService(AccountAwareStorageBackend<ResourceGroup> groups, Ec2Service ec2, Clock clock) {
        this(groups, AccountAwareStorageBackend.inMemory("000000000000"), ec2,
                new ResourceGroupsQueries(null, null, new ObjectMapper()), null, clock);
    }

    ResourceGroupsService(AccountAwareStorageBackend<ResourceGroup> groups,
                          AccountAwareStorageBackend<AccountSettings> settings, Ec2Service ec2,
                          ResourceGroupsQueries queries, Instance<EventBridgeService> eventBridge, Clock clock) {
        this.groups = groups;
        this.settings = settings;
        this.ec2 = ec2;
        this.queries = queries;
        this.eventBridge = eventBridge;
        this.clock = clock;
    }

    // ── Groups ─────────────────────────────────────────────────────────────

    public synchronized ResourceGroup create(String region, String account, JsonNode request) {
        return create(region, account, request, false);
    }

    /**
     * Creates a group an AWS service manages for the caller, such as AppRegistry's application
     * groups, whose names begin with {@code AWS_} as AWS reserves for them.
     */
    public synchronized ResourceGroup createServiceManaged(String region, String account, JsonNode request) {
        return create(region, account, request, true);
    }

    /** Deletes a service-managed group, if it exists. */
    public synchronized void deleteServiceManaged(String region, String account, String name) {
        String key = key(region, name);
        groups.getForAccount(account, key).ifPresent(group -> {
            groups.deleteForAccount(account, key);
            stateChange(region, account, group, "delete");
        });
    }

    private ResourceGroup create(String region, String account, JsonNode request, boolean serviceManaged) {
        ResourceGroupsRequestShapes.check("CreateGroup", request);
        String name = text(request, "Name", true);
        validName(name);
        if (!serviceManaged && name.toLowerCase(Locale.ROOT).startsWith("aws")) {
            throw bad("Group names beginning with AWS are reserved.");
        }
        ResourceQuery query = query(request.get("ResourceQuery"));
        List<Map<String, Object>> configuration = request.hasNonNull("Configuration")
                ? configuration(request.get("Configuration")) : List.of();
        if (query == null && configuration.isEmpty()) {
            throw bad("A group needs a ResourceQuery, a Configuration, or both.");
        }
        if (isPool(configuration) && query != null) {
            throw bad("A capacity reservation pool cannot have a resource query.");
        }
        Integer criticality = criticality(request);
        String owner = text(request, "Owner", false);
        String displayName = text(request, "DisplayName", false);
        if (isPool(configuration) && (criticality != null || owner != null || displayName != null)) {
            throw bad("Unsupported parameter for this operation");
        }
        Map<String, String> tags = tags(request.get("Tags"));
        String key = key(region, name);
        if (groups.getForAccount(account, key).isPresent()) {
            throw bad("Cannot create group: group already exists.");
        }
        ResourceGroup group = new ResourceGroup(name,
                AwsArnUtils.Arn.of("resource-groups", region, account, "group/" + name).toString(),
                text(request, "Description", false), tags, configuration, List.of(), UUID.randomUUID().toString(),
                query, criticality, owner, displayName);
        groups.putForAccount(account, key, group);
        stateChange(region, account, group, "create");
        return group;
    }

    public synchronized ResourceGroup get(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("GetGroup", request);
        return selected(region, account, request);
    }

    /** {@code UpdateGroup}: an omitted member keeps its value. */
    public synchronized ResourceGroup update(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("UpdateGroup", request);
        ResourceGroup group = selected(region, account, request);
        Integer criticality = criticality(request);
        String owner = text(request, "Owner", false);
        String displayName = text(request, "DisplayName", false);
        if (isPool(group.configuration()) && (criticality != null || owner != null || displayName != null)) {
            throw bad("Unsupported parameter for this operation");
        }
        ResourceGroup updated = group.withDetails(
                request.has("Description") ? text(request, "Description", false) : group.description(),
                criticality != null ? criticality : group.criticality(),
                owner != null ? owner : group.owner(),
                displayName != null ? displayName : group.displayName());
        groups.putForAccount(account, key(region, group.name()), updated);
        stateChange(region, account, updated, "update");
        return updated;
    }

    public List<String> capacityReservationMembers(String region, String account, String groupArn) {
        // EC2 holds its capacity lock; GroupResources calls EC2 while holding this service's monitor.
        // Read the immutable storage snapshot without acquiring that monitor in the reverse order.
        if (!AwsArnUtils.looksLikeArn(groupArn)) {
            throw bad("A capacity reservation group ARN is required.");
        }
        return selected(region, account, JsonNodeFactory.instance.objectNode().put("Group", groupArn)).members();
    }

    public synchronized ResourceGroup delete(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("DeleteGroup", request);
        ResourceGroup group = selected(region, account, request);
        groups.deleteForAccount(account, key(region, group.name()));
        pages.values().removeIf(page -> page.generation().equals(group.generation()));
        stateChange(region, account, group, "delete");
        return group;
    }

    /** {@code ListGroups}: filters by resource type, configuration type, owner, display name and criticality. */
    public synchronized Map<String, Object> listGroups(String region, String account, JsonNode request,
                                                       Integer maxResults, String nextToken) {
        ResourceGroupsRequestShapes.check("ListGroups", request);
        int max = maxResults == null ? 50 : maxResults;
        if (max < 1 || max > 50) {
            throw bad("MaxResults must be between 1 and 50.");
        }
        Map<String, List<String>> filters = new LinkedHashMap<>();
        JsonNode filterNodes = request.path("Filters");
        if (filterNodes.isArray()) {
            for (JsonNode filter : filterNodes) {
                List<String> values = new ArrayList<>();
                filter.path("Values").forEach(value -> values.add(value.asText()));
                filters.put(text(filter, "Name", true), values);
            }
        }
        List<ResourceGroup> all = groups.scanForAccount(account, key -> key.startsWith(region + "::")).stream()
                .filter(group -> matches(group, filters))
                .sorted(Comparator.comparing(ResourceGroup::name))
                .toList();
        int start = offset(nextToken);
        int end = Math.min(all.size(), start + max);
        List<Map<String, Object>> identifiers = new ArrayList<>();
        List<Map<String, Object>> metadata = new ArrayList<>();
        for (ResourceGroup group : all.subList(Math.min(start, all.size()), end)) {
            Map<String, Object> identifier = new LinkedHashMap<>();
            identifier.put("GroupName", group.name());
            identifier.put("GroupArn", group.arn());
            putDetails(identifier, group);
            identifiers.add(identifier);
            metadata.add(groupMetadata(group));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("GroupIdentifiers", identifiers);
        response.put("Groups", metadata);
        if (end < all.size()) {
            response.put("NextToken", token(end));
        }
        return response;
    }

    private static boolean matches(ResourceGroup group, Map<String, List<String>> filters) {
        for (Map.Entry<String, List<String>> filter : filters.entrySet()) {
            List<String> values = filter.getValue();
            boolean match = switch (filter.getKey()) {
                case "resource-type" -> allowedTypes(group).stream().anyMatch(values::contains);
                case "configuration-type" -> configurationTypes(group).stream().anyMatch(values::contains);
                case "owner" -> group.owner() != null && values.contains(group.owner());
                case "display-name" -> group.displayName() != null && values.contains(group.displayName());
                case "criticality" -> group.criticality() != null
                        && values.contains(String.valueOf(group.criticality()));
                default -> throw bad("Unknown group filter " + filter.getKey() + ".");
            };
            if (!match) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> configurationTypes(ResourceGroup group) {
        Set<String> types = new LinkedHashSet<>();
        if (group.configuration() != null) {
            group.configuration().forEach(item -> types.add(String.valueOf(item.get("Type"))));
        }
        return types;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> allowedTypes(ResourceGroup group) {
        Set<String> types = new LinkedHashSet<>();
        if (group.configuration() != null) {
            for (Map<String, Object> item : group.configuration()) {
                Object parameters = item.get("Parameters");
                if (parameters instanceof List<?> list) {
                    for (Object parameter : list) {
                        Map<String, Object> entry = (Map<String, Object>) parameter;
                        if ("allowed-resource-types".equals(entry.get("Name"))
                                && entry.get("Values") instanceof List<?> values) {
                            values.forEach(value -> types.add(String.valueOf(value)));
                        }
                    }
                }
            }
        }
        return types;
    }

    // ── Queries and configurations ─────────────────────────────────────────

    public synchronized ResourceGroup getQuery(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("GetGroupQuery", request);
        ResourceGroup group = selected(region, account, request);
        if (group.resourceQuery() == null) {
            throw bad("The group " + group.name() + " has no resource query; it is defined by its configuration.");
        }
        return group;
    }

    public synchronized ResourceGroup updateQuery(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("UpdateGroupQuery", request);
        ResourceGroup group = selected(region, account, request);
        ResourceQuery query = query(request.get("ResourceQuery"));
        if (query == null) {
            throw bad("ResourceQuery is required.");
        }
        if (isPool(group.configuration())) {
            throw bad("A capacity reservation pool cannot have a resource query.");
        }
        ResourceGroup updated = group.withQuery(query);
        groups.putForAccount(account, key(region, group.name()), updated);
        stateChange(region, account, updated, "update");
        return updated;
    }

    public synchronized ResourceGroup getConfiguration(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("GetGroupConfiguration", request);
        return selected(region, account, request);
    }

    /**
     * {@code PutGroupConfiguration} replaces the configuration. A capacity reservation pool's
     * configuration is fixed: putting the same one again (in any order) is accepted, a different one
     * is refused. An empty configuration is only allowed on a group that keeps a query.
     */
    public synchronized void putConfiguration(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("PutGroupConfiguration", request);
        ResourceGroup group = selected(region, account, request);
        JsonNode node = request.get("Configuration");
        List<Map<String, Object>> configuration = node == null || node.isNull() || node.isArray() && node.isEmpty()
                ? List.of() : configuration(node);
        if (isPool(group.configuration())) {
            if (!fingerprint(configuration).equals(fingerprint(group.configuration()))) {
                throw bad("The configuration of a capacity reservation pool cannot be changed.");
            }
            return;
        }
        if (isPool(configuration)) {
            throw bad("A group cannot become a capacity reservation pool after it is created.");
        }
        if (configuration.isEmpty() && group.resourceQuery() == null) {
            throw bad("A group without a resource query needs a configuration.");
        }
        ResourceGroup updated = group.withConfiguration(configuration);
        groups.putForAccount(account, key(region, group.name()), updated);
        stateChange(region, account, updated, "update");
    }

    private String fingerprint(List<Map<String, Object>> configuration) {
        try {
            List<String> items = new ArrayList<>();
            for (Map<String, Object> item : configuration == null ? List.<Map<String, Object>>of() : configuration) {
                items.add(mapper.writeValueAsString(new TreeMap<>(item)));
            }
            items.sort(Comparator.naturalOrder());
            return String.join("|", items);
        } catch (Exception e) {
            throw new AwsException("InternalServerErrorException", "Could not compare configurations.", 500);
        }
    }

    // ── Membership ─────────────────────────────────────────────────────────

    public synchronized Map<String, Object> add(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("GroupResources", request);
        ResourceGroup group = selected(region, account, request);
        Set<String> requested = resourceArns(request);
        requireMembershipGroup(group, "GroupResources");
        List<String> succeeded = new ArrayList<>();
        List<Map<String, String>> failed = new ArrayList<>();
        for (String resource : requested) {
            String failure = isPool(group.configuration()) ? reservationFailure(region, account, resource)
                    : "ResourceArnValidationException";
            if (failure == null) {
                succeeded.add(resource);
            } else {
                failed.add(Map.of("ResourceArn", resource, "ErrorCode", failure,
                        "ErrorMessage", "The resource cannot be a member of this group."));
            }
        }
        // Validate every lookup before changing the stored group, including unexpected backend failures.
        Set<String> members = new LinkedHashSet<>(group.members());
        List<String> added = succeeded.stream().filter(arn -> !members.contains(arn)).toList();
        members.addAll(succeeded);
        groups.putForAccount(account, key(region, group.name()), group.withMembers(new ArrayList<>(members)));
        membershipChange(region, account, group, added, "add");
        return Map.of("Succeeded", succeeded, "Failed", failed, "Pending", List.of());
    }

    /** {@code UngroupResources}: a resource that is not a member is reported removed, as it already is. */
    public synchronized Map<String, Object> remove(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("UngroupResources", request);
        ResourceGroup group = selected(region, account, request);
        Set<String> requested = resourceArns(request);
        requireMembershipGroup(group, "UngroupResources");
        List<String> succeeded = new ArrayList<>();
        List<Map<String, String>> failed = new ArrayList<>();
        for (String resource : requested) {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(resource);
            boolean reservation = arn.service().equals("ec2") && arn.resource().startsWith("capacity-reservation/");
            if (isPool(group.configuration()) && !reservation || !isPool(group.configuration())) {
                failed.add(Map.of("ResourceArn", resource, "ErrorCode", "ResourceArnValidationException",
                        "ErrorMessage", "The resource cannot be a member of this group."));
            } else {
                succeeded.add(resource);
            }
        }
        List<String> removed = succeeded.stream().filter(group.members()::contains).toList();
        List<String> members = new ArrayList<>(group.members());
        members.removeAll(succeeded);
        groups.putForAccount(account, key(region, group.name()), group.withMembers(members));
        membershipChange(region, account, group, removed, "remove");
        return Map.of("Succeeded", succeeded, "Failed", failed, "Pending", List.of());
    }

    private static void requireMembershipGroup(ResourceGroup group, String operation) {
        if (!group.hasConfiguration()) {
            throw bad(operation + " is supported only for groups with a service configuration; " + group.name()
                    + " is defined by a resource query.");
        }
    }

    private static Set<String> resourceArns(JsonNode request) {
        JsonNode resources = request.get("ResourceArns");
        if (resources == null || !resources.isArray() || resources.isEmpty() || resources.size() > 10) {
            throw bad("ResourceArns must contain one to ten resource ARNs.");
        }
        Set<String> requested = new LinkedHashSet<>();
        for (JsonNode resource : resources) {
            if (!resource.isTextual() || resource.asText().length() > 2048
                    || !AwsArnUtils.isArn(resource.asText())) {
                throw bad("ResourceArns must contain valid resource ARNs.");
            }
            requested.add(resource.asText());
        }
        return requested;
    }

    private String reservationFailure(String region, String account, String resource) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resource);
        if (!arn.partition().equals(AwsRegions.partitionFor(region)) || !arn.service().equals("ec2")
                || !arn.resource().startsWith("capacity-reservation/")) {
            return "ResourceArnValidationException";
        }
        if (!arn.region().equals(region)) {
            return "ValidationException";
        }
        if (!arn.accountId().equals(account)
                || !arn.resource().matches("capacity-reservation/cr-[0-9a-f]{17}")) {
            return "ResourceGroupsValidationException";
        }
        List<CapacityReservation> found;
        try {
            found = ec2.describeCapacityReservations(region,
                    List.of(arn.resource().substring("capacity-reservation/".length())), Map.of());
        } catch (AwsException error) {
            if ("InvalidCapacityReservationId.NotFound".equals(error.getErrorCode())) {
                return "ResourceGroupsValidationException";
            }
            throw error;
        }
        if (found.size() != 1 || !resource.equals(found.getFirst().getCapacityReservationArn())
                || !account.equals(found.getFirst().getOwnerId())) {
            throw new AwsException("InternalServerErrorException", "Reservation lookup returned an inconsistent identity.", 500);
        }
        return "active".equals(found.getFirst().getState()) ? null : "ResourceGroupsValidationException";
    }

    /**
     * {@code ListGroupResources}: a configuration group's members, or what a query group's query
     * selects now. Tokens bind the group generation and filter to an ordered snapshot.
     */
    public synchronized Map<String, Object> list(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("ListGroupResources", request);
        ResourceGroup group = selected(region, account, request);
        int maximum = 50;
        if (request.has("MaxResults")) {
            JsonNode value = request.get("MaxResults");
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 50) {
                throw bad("MaxResults must be between 1 and 50.");
            }
            maximum = value.intValue();
        }
        List<String> filter = filter(request.get("Filters"), group);
        String token = text(request, "NextToken", false);
        if (token != null && (token.length() > 8192 || !token.matches("[a-zA-Z0-9+/]*={0,2}"))) {
            throw bad("NextToken must be a base64 token.");
        }
        long now = clock.millis();
        pages.values().removeIf(page -> now - page.lastRead() >= PAGE_IDLE_MILLIS);
        List<ResourceGroupsQueries.Resource> selected;
        List<Map<String, String>> errors = List.of();
        if (token == null) {
            if (group.resourceQuery() != null && !isPool(group.configuration())) {
                ResourceGroupsQueries.Result result = queries.resolve(group.resourceQuery(), region, account);
                selected = result.resources();
                errors = result.errors();
            } else {
                selected = group.members().stream()
                        .map(arn -> new ResourceGroupsQueries.Resource(arn, RESOURCE_TYPE)).toList();
            }
            if (!filter.isEmpty()) {
                selected = selected.stream().filter(resource -> filter.contains(resource.type())).toList();
            }
        } else {
            Page page = pages.get(token);
            if (page == null || !page.groupArn().equals(group.arn())
                    || !page.generation().equals(group.generation()) || !page.filter().equals(filter)) {
                throw bad("NextToken does not match this group and filter, or has expired.");
            }
            pages.remove(token);
            pages.put(token, new Page(page.groupArn(), page.generation(), page.filter(), page.remaining(), now));
            selected = group.resourceQuery() != null && !isPool(group.configuration()) ? page.remaining()
                    : page.remaining().stream().filter(resource -> group.members().contains(resource.arn())).toList();
        }
        List<Map<String, Object>> identifiers = selected.stream().limit(maximum)
                .map(ResourceGroupsQueries::identifier).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ResourceIdentifiers", identifiers);
        response.put("Resources", identifiers.stream().map(identifier -> Map.of("Identifier", identifier)).toList());
        response.put("QueryErrors", errors);
        if (selected.size() > maximum) {
            if (pages.size() >= MAX_PAGES) {
                pages.remove(pages.keySet().iterator().next());
            }
            String next = Base64.getEncoder().encodeToString(
                    UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
            pages.put(next, new Page(group.arn(), group.generation(), filter,
                    List.copyOf(selected.subList(maximum, selected.size())), now));
            response.put("NextToken", next);
        }
        return response;
    }

    /** {@code SearchResources}: runs a query without a group. */
    public Map<String, Object> search(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("SearchResources", request);
        ResourceQuery query = query(request.get("ResourceQuery"));
        if (query == null) {
            throw bad("ResourceQuery is required.");
        }
        int max = request.has("MaxResults") ? request.get("MaxResults").asInt() : 50;
        if (max < 1 || max > 50) {
            throw bad("MaxResults must be between 1 and 50.");
        }
        ResourceGroupsQueries.Result result = queries.resolve(query, region, account);
        int start = offset(text(request, "NextToken", false));
        int end = Math.min(result.resources().size(), start + max);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ResourceIdentifiers", result.resources().subList(Math.min(start, end), end).stream()
                .map(ResourceGroupsQueries::identifier).toList());
        response.put("QueryErrors", result.errors());
        if (end < result.resources().size()) {
            response.put("NextToken", token(end));
        }
        return response;
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    public Map<String, String> getTags(String region, String account, String arn) {
        ResourceGroupsRequestShapes.check("GetTags", JsonNodeFactory.instance.objectNode().put("Arn", arn));
        return byArn(region, account, arn).tags();
    }

    public synchronized Map<String, String> tag(String region, String account, String arn, JsonNode request) {
        ObjectNode checked = request.deepCopy();
        checked.put("Arn", arn);
        ResourceGroupsRequestShapes.check("Tag", checked);
        ResourceGroup group = byArn(region, account, arn);
        Map<String, String> added = tags(request.get("Tags"));
        if (added.isEmpty()) {
            throw bad("Tags must contain at least one tag.");
        }
        tagGroup(region, account, group, added);
        return added;
    }

    void tagGroup(String region, String account, ResourceGroup group, Map<String, String> added) {
        added.keySet().forEach(ResourceGroupsService::validTagKey);
        Map<String, String> merged = new LinkedHashMap<>(group.tags());
        merged.putAll(added);
        if (merged.size() > 50) {
            throw bad("A group can have at most 50 tags.");
        }
        groups.putForAccount(account, key(region, group.name()), group.withTags(merged));
    }

    public synchronized List<String> untag(String region, String account, String arn, JsonNode request) {
        ObjectNode checked = request.deepCopy();
        checked.put("Arn", arn);
        ResourceGroupsRequestShapes.check("Untag", checked);
        ResourceGroup group = byArn(region, account, arn);
        List<String> keys = new ArrayList<>();
        request.path("Keys").forEach(key -> keys.add(key.asText()));
        untagGroup(region, account, group, keys);
        return keys;
    }

    void untagGroup(String region, String account, ResourceGroup group, List<String> keys) {
        Map<String, String> remaining = new LinkedHashMap<>(group.tags());
        keys.forEach(remaining::remove);
        groups.putForAccount(account, key(region, group.name()), group.withTags(remaining));
    }

    ResourceGroup byArn(String region, String account, String arn) {
        return selected(region, account, JsonNodeFactory.instance.objectNode().put("Group", arn));
    }

    private static void validTagKey(String key) {
        if (key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
            throw bad("Tag keys beginning with aws: are reserved.");
        }
    }

    // ── Account settings and lifecycle events ──────────────────────────────

    public AccountSettings accountSettings(String account) {
        return settings.getForAccount(account, "settings").orElse(AccountSettings.DEFAULT);
    }

    public synchronized AccountSettings updateAccountSettings(String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("UpdateAccountSettings", request);
        String desired = text(request, "GroupLifecycleEventsDesiredStatus", false);
        if (desired == null) {
            return accountSettings(account);
        }
        AccountSettings updated = new AccountSettings(desired, desired);
        settings.putForAccount(account, "settings", updated);
        return updated;
    }

    private void stateChange(String region, String account, ResourceGroup group, String change) {
        if (!"ACTIVE".equals(accountSettings(account).status())) {
            return;
        }
        ObjectNode detail = eventDetail(group);
        detail.put("state-change", change);
        if (!"delete".equals(change)) {
            ObjectNode state = detail.putObject("new-state");
            if (group.description() != null) {
                state.put("description", group.description());
            }
            if (group.resourceQuery() != null) {
                state.putObject("resource-query").put("type", group.resourceQuery().type())
                        .put("query", group.resourceQuery().query());
            }
            state.set("group-configuration", mapper.valueToTree(group.configuration()));
            state.set("tags", mapper.valueToTree(group.tags()));
        }
        publish(region, "ResourceGroups Group State Change", detail, group.arn());
    }

    private void membershipChange(String region, String account, ResourceGroup group, List<String> resources,
                                  String change) {
        if (resources.isEmpty() || !"ACTIVE".equals(accountSettings(account).status())) {
            return;
        }
        ObjectNode detail = eventDetail(group);
        ArrayNode list = detail.putArray("resources");
        for (String arn : resources) {
            list.addObject().put("membership-change", change).put("arn", arn)
                    .put("resource-type", ResourceGroupsQueries.cloudFormationType(arn));
        }
        publish(region, "ResourceGroups Group Membership Change", detail, group.arn());
    }

    private ObjectNode eventDetail(ResourceGroup group) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("event-sequence", eventSequence.incrementAndGet());
        detail.putObject("group").put("arn", group.arn()).put("name", group.name());
        return detail;
    }

    private void publish(String region, String detailType, ObjectNode detail, String arn) {
        if (eventBridge == null || !eventBridge.isResolvable()) {
            return;
        }
        eventBridge.get().publishServiceEvent(EVENT_SOURCE, detailType, detail.toString(), arn, region);
    }

    // ── Application-group-only operations ──────────────────────────────────

    /** Grouping statuses exist only for application groups, which this API cannot create. */
    public void listGroupingStatuses(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("ListGroupingStatuses", request);
        ResourceGroup group = selected(region, account, request);
        throw bad("Grouping statuses are only available for an application group, and " + group.name()
                + " is not an application group.");
    }

    /** Tag-sync tasks run only on application groups, which this API cannot create. */
    public void startTagSyncTask(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("StartTagSyncTask", request);
        ResourceGroup group = selected(region, account, request);
        if (!AwsArnUtils.isArn(text(request, "RoleArn", true))) {
            throw bad("RoleArn must be an IAM role ARN.");
        }
        throw bad("Tag-sync tasks can only be started for an application group, and " + group.name()
                + " is not an application group.");
    }

    public void tagSyncTask(String operation, JsonNode request) {
        ResourceGroupsRequestShapes.check(operation, request);
        throw new AwsException("NotFoundException", "The tag-sync task " + text(request, "TaskArn", true)
                + " was not found.", 404);
    }

    public Map<String, Object> listTagSyncTasks(JsonNode request) {
        ResourceGroupsRequestShapes.check("ListTagSyncTasks", request);
        if (request.has("MaxResults") && (request.get("MaxResults").asInt() < 1 || request.get("MaxResults").asInt() > 50)) {
            throw bad("MaxResults must be between 1 and 50.");
        }
        return Map.of("TagSyncTasks", List.of());
    }

    // ── Resource discovery ─────────────────────────────────────────────────

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (AccountAwareStorageBackend.AccountEntry<ResourceGroup> entry : groups.scanAllAccountEntries(key -> true)) {
            ResourceGroup group = entry.value();
            resources.add(new ExplorerResource(group.arn(), "resource-groups:group", "resource-groups",
                    entry.key().substring(0, entry.key().indexOf("::")), entry.accountId(), Instant.now(),
                    new HashMap<>(group.tags() == null ? Map.of() : group.tags())));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("resource-groups:group", "resource-groups", true));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    Map<String, Object> groupMetadata(ResourceGroup group) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("GroupArn", group.arn());
        result.put("Name", group.name());
        putDetails(result, group);
        return result;
    }

    private static void putDetails(Map<String, Object> result, ResourceGroup group) {
        if (group.description() != null) {
            result.put("Description", group.description());
        }
        if (group.criticality() != null) {
            result.put("Criticality", group.criticality());
        }
        if (group.owner() != null) {
            result.put("Owner", group.owner());
        }
        if (group.displayName() != null) {
            result.put("DisplayName", group.displayName());
        }
    }

    private ResourceGroup selected(String region, String account, JsonNode request) {
        if (request.has("Group") && request.has("GroupName")) {
            throw bad("Specify Group or GroupName, not both.");
        }
        String selected = text(request, request.has("Group") ? "Group" : "GroupName", true);
        String name = selected;
        if (AwsArnUtils.looksLikeArn(selected)) {
            if (!AwsArnUtils.isArn(selected)) {
                throw bad("Invalid group ARN.");
            }
            AwsArnUtils.Arn arn = AwsArnUtils.parse(selected);
            if (!arn.partition().equals(AwsRegions.partitionFor(region)) || !arn.service().equals("resource-groups")
                    || !arn.region().equals(region) || !arn.accountId().equals(account)
                    || !arn.resource().startsWith("group/")) {
                throw bad("The group ARN must belong to the calling account and region.");
            }
            name = arn.resource().substring("group/".length());
        }
        validName(name);
        ResourceGroup group = groups.getForAccount(account, key(region, name))
                .orElseThrow(() -> new AwsException("NotFoundException", "The resource group was not found.", 404));
        String expected = AwsArnUtils.Arn.of("resource-groups", region, account, "group/" + name).toString();
        if (!group.arn().equals(expected)) {
            throw new AwsException("InternalServerErrorException", "Stored group identity is inconsistent.", 500);
        }
        return group;
    }

    private ResourceQuery query(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        requireObject(node);
        ResourceQuery query = new ResourceQuery(text(node, "Type", true), text(node, "Query", true));
        queries.validate(query);
        return query;
    }

    private static boolean isPool(List<Map<String, Object>> configuration) {
        return configuration != null && configuration.stream().anyMatch(item -> POOL_TYPE.equals(item.get("Type")));
    }

    private static Integer criticality(JsonNode request) {
        JsonNode value = request.get("Criticality");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || value.intValue() < 1 || value.intValue() > 10) {
            throw bad("Criticality must be between 1 and 10.");
        }
        return value.intValue();
    }

    /**
     * A capacity reservation pool takes exactly AWS's two items. Any other configuration is a list
     * of known types, each with optional {@code Parameters} of {@code Name} and string {@code Values}.
     */
    private static List<Map<String, Object>> configuration(JsonNode configuration) {
        if (configuration == null || !configuration.isArray() || configuration.isEmpty()) {
            throw bad("Configuration must list at least one item.");
        }
        Set<String> types = new LinkedHashSet<>();
        for (JsonNode item : configuration) {
            requireObject(item);
            types.add(text(item, "Type", true));
        }
        if (types.size() != configuration.size()) {
            throw bad("Configuration types must be distinct.");
        }
        if (types.contains(POOL_TYPE)) {
            return poolConfiguration(configuration);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode item : configuration) {
            String type = text(item, "Type", true);
            if (!CONFIGURATION_TYPES.contains(type) || "AWS::ResourceGroups::ApplicationGroup".equals(type)) {
                throw bad("Unsupported configuration type " + type + ".");
            }
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("Type", type);
            JsonNode parameters = item.get("Parameters");
            if (parameters != null && !parameters.isNull()) {
                if (!parameters.isArray()) {
                    throw bad("Parameters must be a list.");
                }
                List<Map<String, Object>> list = new ArrayList<>();
                for (JsonNode parameter : parameters) {
                    requireObject(parameter);
                    List<String> values = new ArrayList<>();
                    JsonNode valueNodes = parameter.get("Values");
                    if (valueNodes != null && valueNodes.isArray()) {
                        for (JsonNode value : valueNodes) {
                            if (!value.isTextual()) {
                                throw bad("Parameter values must be strings.");
                            }
                            values.add(value.asText());
                        }
                    }
                    list.add(Map.of("Name", text(parameter, "Name", true), "Values", values));
                }
                stored.put("Parameters", list);
            }
            if ("AWS::EC2::HostManagement".equals(type) && !hasParameter(stored, "any-host-based-license-configuration")
                    && !hasParameter(stored, "allowed-host-based-license-configurations")) {
                throw bad("AWS::EC2::HostManagement needs any-host-based-license-configuration or "
                        + "allowed-host-based-license-configurations.");
            }
            items.add(stored);
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private static boolean hasParameter(Map<String, Object> item, String name) {
        Object parameters = item.get("Parameters");
        return parameters instanceof List<?> list
                && list.stream().anyMatch(parameter -> name.equals(((Map<String, Object>) parameter).get("Name")));
    }

    private static List<Map<String, Object>> poolConfiguration(JsonNode configuration) {
        if (configuration.size() != 2) {
            throw bad("A capacity reservation pool Configuration is required.");
        }
        for (JsonNode item : configuration) {
            String type = text(item, "Type", true);
            JsonNode parameters = item.get("Parameters");
            if (POOL_TYPE.equals(type)) {
                if (parameters != null && (!parameters.isArray() || !parameters.isEmpty())) {
                    throw bad("CapacityReservationPool does not accept these parameters.");
                }
            } else if (GENERIC_TYPE.equals(type)) {
                if (parameters == null || !parameters.isArray() || parameters.size() != 1) {
                    throw bad("The allowed-resource-types parameter is required.");
                }
                JsonNode parameter = parameters.get(0);
                requireObject(parameter);
                JsonNode values = parameter.get("Values");
                if (!"allowed-resource-types".equals(text(parameter, "Name", true))
                        || values == null || !values.isArray() || values.size() != 1
                        || !values.get(0).isTextual() || !RESOURCE_TYPE.equals(values.get(0).asText())) {
                    throw bad("Only CapacityReservation resources are supported.");
                }
            } else {
                throw bad("Only capacity reservation pool configurations are supported.");
            }
        }
        return List.of(Map.of("Type", POOL_TYPE), Map.of("Type", GENERIC_TYPE, "Parameters",
                List.of(Map.of("Name", "allowed-resource-types", "Values", List.of(RESOURCE_TYPE)))));
    }

    /** The resource-type filter of {@code ListGroupResources}; a pool only lists capacity reservations. */
    private static List<String> filter(JsonNode filters, ResourceGroup group) {
        if (filters == null || filters.isArray() && filters.isEmpty()) {
            return List.of();
        }
        if (!filters.isArray() || filters.size() != 1) {
            throw bad("Only one resource-type filter is supported.");
        }
        JsonNode filter = filters.get(0);
        requireObject(filter);
        JsonNode values = filter.get("Values");
        if (!"resource-type".equals(text(filter, "Name", true)) || values == null
                || !values.isArray() || values.isEmpty() || values.size() > 5) {
            throw bad("Invalid resource-type filter.");
        }
        List<String> types = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || isPool(group.configuration()) && !RESOURCE_TYPE.equals(value.asText())) {
                throw bad("The resource type is not allowed by the group configuration.");
            }
            types.add(value.asText());
        }
        return types.stream().distinct().sorted().collect(Collectors.toList());
    }

    private static Map<String, String> tags(JsonNode tags) {
        Map<String, String> result = new LinkedHashMap<>();
        if (tags == null || tags.isNull()) {
            return result;
        }
        if (!tags.isObject() || tags.size() > 50) {
            throw bad("Tags must be an object containing at most 50 entries.");
        }
        tags.fields().forEachRemaining(entry -> {
            validTagKey(entry.getKey());
            result.put(entry.getKey(), entry.getValue().asText());
        });
        return result;
    }

    private static String token(int offset) {
        return Base64.getEncoder().encodeToString(("o:" + offset).getBytes(StandardCharsets.US_ASCII));
    }

    private static int offset(String token) {
        if (token == null) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.US_ASCII);
            if (!decoded.startsWith("o:")) {
                throw bad("Invalid NextToken.");
            }
            return Math.max(0, Integer.parseInt(decoded.substring(2)));
        } catch (IllegalArgumentException e) {
            throw bad("Invalid NextToken.");
        }
    }

    private static void validName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,300}")) {
            throw bad("Invalid resource group name.");
        }
    }

    private static String key(String region, String name) {
        return region + "::" + name;
    }

    private static String text(JsonNode object, String field, boolean required) {
        JsonNode value = object.get(field);
        if ((value == null || value.isNull()) && !required) {
            return null;
        }
        if (value == null || !value.isTextual() || required && value.asText().isEmpty()) {
            throw bad(field + " must be a string.");
        }
        return value.asText();
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw bad("A JSON object is required.");
        }
    }

    private static AwsException bad(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    @Override
    public synchronized void clear() {
        groups.clear();
        settings.clear();
        pages.clear();
    }
}
