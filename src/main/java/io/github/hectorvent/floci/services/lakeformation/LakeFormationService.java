package io.github.hectorvent.floci.services.lakeformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.lakeformation.model.RegisteredResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local AWS Lake Formation stub. LF-tags, grants, registered locations,
 * expressions, data cells filters and opt-ins are in-memory. Binding
 * operations used by Alchemy {@code Bindings.test.ts} match AWS restJson1
 * field names and typed errors.
 *
 * @see <a href="https://docs.aws.amazon.com/lake-formation/latest/APIReference/API_Operations.html">Lake Formation API</a>
 */
@ApplicationScoped
public class LakeFormationService implements Resettable {

    static final String SERVICE = "lakeformation";
    private static final String SERVICE_LINKED_ROLE =
            "arn:aws:iam::%s:role/aws-service-role/lakeformation.amazonaws.com/AWSServiceRoleForLakeFormationDataAccess";

    static final class LfTag {
        String catalogId;
        String tagKey;
        final List<String> tagValues = new ArrayList<>();
    }

    static final class Grant {
        JsonNode principal;
        JsonNode resource;
        final List<String> permissions = new ArrayList<>();
        final List<String> permissionsWithGrantOption = new ArrayList<>();
    }

    static final class LfTagExpression {
        String catalogId;
        String name;
        String description;
        JsonNode expression;
    }

    static final class DataCellsFilter {
        JsonNode tableData;
    }

    static final class LakeFormationOptIn {
        JsonNode principal;
        JsonNode resource;
    }

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final AccountResolver accountResolver;
    private final ConcurrentHashMap<String, LfTag> tags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Grant> grants = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RegisteredResource> locations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<LfTag>> resourceTags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LfTagExpression> expressions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DataCellsFilter> filters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LakeFormationOptIn> optIns = new ConcurrentHashMap<>();
    private final AtomicReference<JsonNode> dataLakeSettings = new AtomicReference<>();

    @Inject
    public LakeFormationService(ObjectMapper objectMapper, RegionResolver regionResolver,
                                AccountResolver accountResolver) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.accountResolver = accountResolver;
    }

    @Override
    public void clear() {
        tags.clear();
        grants.clear();
        locations.clear();
        resourceTags.clear();
        expressions.clear();
        filters.clear();
        optIns.clear();
        dataLakeSettings.set(null);
    }

    public ObjectNode getDataLakePrincipal(String authorization) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Identity", callerIdentity(authorization));
        return response;
    }

    public ObjectNode createLfTag(JsonNode request) {
        String catalogId = catalogId(request);
        String tagKey = requireText(request, "TagKey");
        List<String> values = requireStringList(request, "TagValues");
        String key = tagKey(catalogId, tagKey);
        if (tags.containsKey(key)) {
            throw alreadyExists("Tag key already exists: " + tagKey);
        }
        LfTag tag = new LfTag();
        tag.catalogId = catalogId;
        tag.tagKey = tagKey;
        tag.tagValues.addAll(values);
        tags.put(key, tag);
        return objectMapper.createObjectNode();
    }

    public ObjectNode getLfTag(JsonNode request) {
        String catalogId = catalogId(request);
        String tagKey = requireText(request, "TagKey");
        LfTag tag = findTag(catalogId, tagKey);
        if (tag == null) {
            throw notFound("Tag key does not exist: " + tagKey);
        }
        return toLfTag(tag);
    }

    public ObjectNode updateLfTag(JsonNode request) {
        String catalogId = catalogId(request);
        String tagKey = requireText(request, "TagKey");
        LfTag tag = findTag(catalogId, tagKey);
        if (tag == null) {
            throw notFound("Tag key does not exist: " + tagKey);
        }
        for (String value : stringList(request, "TagValuesToDelete")) {
            tag.tagValues.remove(value);
        }
        for (String value : stringList(request, "TagValuesToAdd")) {
            if (!tag.tagValues.contains(value)) {
                tag.tagValues.add(value);
            }
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteLfTag(JsonNode request) {
        String catalogId = catalogId(request);
        String tagKey = requireText(request, "TagKey");
        if (tags.remove(tagKey(catalogId, tagKey)) == null) {
            throw notFound("Tag key does not exist: " + tagKey);
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listLfTags(JsonNode request) {
        String catalogId = optionalText(request, "CatalogId");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("LFTags");
        for (LfTag tag : tags.values()) {
            if (catalogId != null && !catalogId.equals(tag.catalogId)) {
                continue;
            }
            list.add(toLfTag(tag));
        }
        return response;
    }

    public ObjectNode listPermissions(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("PrincipalResourcePermissions");
        JsonNode resourceFilter = request.get("Resource");
        JsonNode principalFilter = request.get("Principal");
        for (Grant grant : grants.values()) {
            if (resourceFilter != null && resourceFilter.isObject()
                    && !resourceMatches(grant.resource, resourceFilter)) {
                continue;
            }
            if (principalFilter != null && principalFilter.isObject()
                    && !principalMatches(grant.principal, principalFilter)) {
                continue;
            }
            list.add(toGrant(grant));
        }
        return response;
    }

    public ObjectNode grantPermissions(JsonNode request) {
        JsonNode principal = request.get("Principal");
        JsonNode resource = request.get("Resource");
        if (principal == null || !principal.isObject()) {
            throw invalid("Principal is required.");
        }
        if (resource == null || !resource.isObject()) {
            throw invalid("Resource is required.");
        }
        String key = grantKey(principal, resource);
        Grant grant = grants.get(key);
        if (grant == null) {
            grant = new Grant();
            grant.principal = principal.deepCopy();
            grant.resource = resource.deepCopy();
            grants.put(key, grant);
        }
        union(grant.permissions, stringList(request, "Permissions"));
        union(grant.permissionsWithGrantOption, stringList(request, "PermissionsWithGrantOption"));
        return objectMapper.createObjectNode();
    }

    public ObjectNode revokePermissions(JsonNode request) {
        JsonNode principal = request.get("Principal");
        JsonNode resource = request.get("Resource");
        if (principal == null || !principal.isObject() || resource == null || !resource.isObject()) {
            throw invalid("Principal and Resource are required.");
        }
        String key = grantKey(principal, resource);
        Grant grant = grants.get(key);
        if (grant == null) {
            throw notFound("Permissions not found.");
        }
        grant.permissions.removeAll(stringList(request, "Permissions"));
        grant.permissionsWithGrantOption.removeAll(stringList(request, "PermissionsWithGrantOption"));
        if (grant.permissions.isEmpty() && grant.permissionsWithGrantOption.isEmpty()) {
            grants.remove(key);
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode searchDatabasesByLfTags(JsonNode request) {
        requireExpression(request);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("DatabaseList");
        return response;
    }

    public ObjectNode searchTablesByLfTags(JsonNode request) {
        requireExpression(request);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("TableList");
        return response;
    }

    public ObjectNode getResourceLfTags(JsonNode request) {
        JsonNode resource = request.get("Resource");
        if (resource == null || !resource.isObject()) {
            throw invalid("Resource is required.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode onDatabase = response.putArray("LFTagOnDatabase");
        ArrayNode onTable = response.putArray("LFTagsOnTable");
        response.putArray("LFTagsOnColumns");
        List<LfTag> assigned = resourceTags.get(resourceKey(resource));
        if (assigned != null) {
            ArrayNode target = resource.path("Database").isObject() ? onDatabase : onTable;
            for (LfTag tag : assigned) {
                target.add(toLfTag(tag));
            }
        }
        return response;
    }

    public ObjectNode addLfTagsToResource(JsonNode request) {
        JsonNode resource = request.get("Resource");
        if (resource == null || !resource.isObject()) {
            throw invalid("Resource is required.");
        }
        JsonNode lfTags = request.get("LFTags");
        if (lfTags == null || !lfTags.isArray() || lfTags.isEmpty()) {
            throw invalid("LFTags is required.");
        }
        String catalogId = catalogId(request);
        List<LfTag> assigned = resourceTags.computeIfAbsent(resourceKey(resource), k -> new ArrayList<>());
        for (JsonNode node : lfTags) {
            String tagKey = requireText(node, "TagKey");
            String tagCatalog = optionalText(node, "CatalogId");
            if (tagCatalog == null) {
                tagCatalog = catalogId;
            }
            LfTag definition = findTag(tagCatalog, tagKey);
            if (definition == null) {
                throw notFound("Tag key does not exist: " + tagKey);
            }
            LfTag copy = new LfTag();
            copy.catalogId = definition.catalogId;
            copy.tagKey = tagKey;
            copy.tagValues.addAll(stringList(node, "TagValues"));
            assigned.removeIf(existing -> existing.tagKey.equals(tagKey));
            assigned.add(copy);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Failures");
        return response;
    }

    public ObjectNode removeLfTagsFromResource(JsonNode request) {
        JsonNode resource = request.get("Resource");
        if (resource == null || !resource.isObject()) {
            throw invalid("Resource is required.");
        }
        List<LfTag> assigned = resourceTags.get(resourceKey(resource));
        if (assigned != null) {
            for (String tagKey : tagKeys(request.get("LFTags"))) {
                assigned.removeIf(existing -> existing.tagKey.equals(tagKey));
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Failures");
        return response;
    }

    public ObjectNode getEffectivePermissionsForPath(JsonNode request) {
        requireText(request, "ResourceArn");
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Permissions");
        return response;
    }

    public ObjectNode getTemporaryGlueTableCredentials(JsonNode request) {
        requireText(request, "TableArn");
        throw notFound("Table not found.");
    }

    public ObjectNode getTemporaryGluePartitionCredentials(JsonNode request) {
        requireText(request, "TableArn");
        JsonNode partition = request.get("Partition");
        if (partition == null || !partition.isObject()) {
            throw invalid("Partition is required.");
        }
        throw notFound("Partition not found.");
    }

    public ObjectNode getTemporaryDataLocationCredentials(JsonNode request) {
        JsonNode locationsNode = request.get("DataLocations");
        if (locationsNode == null || !locationsNode.isArray() || locationsNode.isEmpty()) {
            throw invalid("DataLocations is required.");
        }
        for (JsonNode location : locationsNode) {
            String arn = location.asText();
            if (!locations.containsKey(normalizeArn(arn))) {
                throw notFound("Resource does not exist: " + arn);
            }
        }
        throw notFound("Resource does not exist.");
    }

    public ObjectNode registerResource(JsonNode request) {
        String resourceArn = requireText(request, "ResourceArn");
        String key = normalizeArn(resourceArn);
        if (locations.containsKey(key)) {
            throw alreadyExists("Resource is already registered: " + resourceArn);
        }
        locations.put(key, newRegistered(request, resourceArn));
        return objectMapper.createObjectNode();
    }

    public ObjectNode updateResource(JsonNode request) {
        String resourceArn = requireText(request, "ResourceArn");
        RegisteredResource existing = locations.get(normalizeArn(resourceArn));
        if (existing == null) {
            throw notFound("Resource does not exist: " + resourceArn);
        }
        if (existing.isServiceLinkedRole() && optionalText(request, "RoleArn") != null) {
            throw invalid("Resource managed by Service Linked Role");
        }
        applyRegistration(existing, request);
        existing.setLastModifiedEpochSeconds(Instant.now().getEpochSecond());
        return objectMapper.createObjectNode();
    }

    public ObjectNode deregisterResource(JsonNode request) {
        String resourceArn = requireText(request, "ResourceArn");
        String key = normalizeArn(resourceArn);
        RegisteredResource location = locations.get(key);
        if (location == null) {
            throw notFound("Resource does not exist: " + resourceArn);
        }
        boolean lastSlr = isServiceLinked(location) && slrCount() == 1;
        locations.remove(key);
        if (lastSlr) {
            throw invalid("Must manually delete service-linked role to deregister last S3 location");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode describeResource(JsonNode request) {
        String resourceArn = requireText(request, "ResourceArn");
        RegisteredResource location = locations.get(normalizeArn(resourceArn));
        if (location == null) {
            throw notFound("Resource does not exist: " + resourceArn);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ResourceInfo", toResourceInfo(location));
        return response;
    }

    public ObjectNode listResources(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ResourceInfoList");
        for (RegisteredResource location : locations.values()) {
            list.add(toResourceInfo(location));
        }
        return response;
    }

    public ObjectNode getDataLakeSettings(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        JsonNode stored = dataLakeSettings.get();
        if (stored != null && stored.isObject()) {
            response.set("DataLakeSettings", stored.deepCopy());
            return response;
        }
        ObjectNode settings = response.putObject("DataLakeSettings");
        settings.putArray("DataLakeAdmins");
        settings.putArray("CreateDatabaseDefaultPermissions");
        settings.putArray("CreateTableDefaultPermissions");
        settings.put("AllowExternalDataFiltering", false);
        settings.put("AllowFullTableExternalDataAccess", false);
        settings.putArray("TrustedResourceOwners");
        settings.putArray("ExternalDataFilteringAllowList");
        settings.putArray("AuthorizedSessionTagValueList");
        return response;
    }

    public ObjectNode putDataLakeSettings(JsonNode request) {
        JsonNode settings = request.get("DataLakeSettings");
        if (settings == null || !settings.isObject()) {
            throw invalid("DataLakeSettings is required.");
        }
        dataLakeSettings.set(settings.deepCopy());
        return objectMapper.createObjectNode();
    }

    public ObjectNode createLfTagExpression(JsonNode request) {
        String name = requireText(request, "Name");
        JsonNode expression = request.get("Expression");
        if (expression == null || !expression.isArray()) {
            throw invalid("Expression is required.");
        }
        String catalogId = catalogId(request);
        String key = tagKey(catalogId, name);
        if (expressions.containsKey(key)) {
            throw alreadyExists("LF-tag expression already exists: " + name);
        }
        LfTagExpression stored = new LfTagExpression();
        stored.catalogId = catalogId;
        stored.name = name;
        stored.description = optionalText(request, "Description");
        stored.expression = expression.deepCopy();
        expressions.put(key, stored);
        return objectMapper.createObjectNode();
    }

    public ObjectNode getLfTagExpression(JsonNode request) {
        LfTagExpression stored = requireExpression(request, false);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Name", stored.name);
        response.put("CatalogId", stored.catalogId);
        if (stored.description != null) {
            response.put("Description", stored.description);
        }
        response.set("Expression", stored.expression);
        return response;
    }

    public ObjectNode updateLfTagExpression(JsonNode request) {
        LfTagExpression stored = requireExpression(request, false);
        JsonNode expression = request.get("Expression");
        if (expression != null && expression.isArray()) {
            stored.expression = expression.deepCopy();
        }
        String description = optionalText(request, "Description");
        if (description != null) {
            stored.description = description;
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteLfTagExpression(JsonNode request) {
        requireExpression(request, true);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listLfTagExpressions(JsonNode request) {
        String catalogId = optionalText(request, "CatalogId");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("LFTagExpressions");
        for (LfTagExpression stored : expressions.values()) {
            if (catalogId != null && !catalogId.equals(stored.catalogId)) {
                continue;
            }
            ObjectNode node = list.addObject();
            node.put("Name", stored.name);
            node.put("CatalogId", stored.catalogId);
            if (stored.description != null) {
                node.put("Description", stored.description);
            }
            node.set("Expression", stored.expression);
        }
        return response;
    }

    public ObjectNode createDataCellsFilter(JsonNode request) {
        JsonNode tableData = request.get("TableData");
        if (tableData == null || !tableData.isObject()) {
            throw invalid("TableData is required.");
        }
        String key = filterKey(tableData);
        if (filters.containsKey(key)) {
            throw alreadyExists("Data cells filter already exists.");
        }
        DataCellsFilter filter = new DataCellsFilter();
        filter.tableData = tableData.deepCopy();
        filters.put(key, filter);
        return objectMapper.createObjectNode();
    }

    public ObjectNode getDataCellsFilter(JsonNode request) {
        DataCellsFilter filter = filters.get(filterKey(request));
        if (filter == null) {
            throw notFound("Data cells filter not found.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("DataCellsFilter", filter.tableData);
        return response;
    }

    public ObjectNode updateDataCellsFilter(JsonNode request) {
        JsonNode tableData = request.get("TableData");
        if (tableData == null || !tableData.isObject()) {
            throw invalid("TableData is required.");
        }
        String key = filterKey(tableData);
        DataCellsFilter filter = filters.get(key);
        if (filter == null) {
            throw notFound("Data cells filter not found.");
        }
        filter.tableData = tableData.deepCopy();
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteDataCellsFilter(JsonNode request) {
        if (filters.remove(filterKey(request)) == null) {
            throw notFound("Data cells filter not found.");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listDataCellsFilter(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("DataCellsFilters");
        for (DataCellsFilter filter : filters.values()) {
            list.add(filter.tableData);
        }
        return response;
    }

    public ObjectNode createLakeFormationOptIn(JsonNode request) {
        JsonNode principal = request.get("Principal");
        JsonNode resource = request.get("Resource");
        if (principal == null || !principal.isObject() || resource == null || !resource.isObject()) {
            throw invalid("Principal and Resource are required.");
        }
        String key = optInKey(principal, resource);
        if (optIns.containsKey(key)) {
            throw alreadyExists("Opt-in already exists.");
        }
        LakeFormationOptIn optIn = new LakeFormationOptIn();
        optIn.principal = principal.deepCopy();
        optIn.resource = resource.deepCopy();
        optIns.put(key, optIn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteLakeFormationOptIn(JsonNode request) {
        JsonNode principal = request.get("Principal");
        JsonNode resource = request.get("Resource");
        if (principal == null || !principal.isObject() || resource == null || !resource.isObject()) {
            throw invalid("Principal and Resource are required.");
        }
        Iterator<LakeFormationOptIn> iterator = optIns.values().iterator();
        boolean removed = false;
        while (iterator.hasNext()) {
            LakeFormationOptIn optIn = iterator.next();
            if (principalMatches(optIn.principal, principal) && resourceMatches(optIn.resource, resource)) {
                iterator.remove();
                removed = true;
            }
        }
        if (!removed) {
            throw notFound("Opt-in not found.");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listLakeFormationOptIns(JsonNode request) {
        JsonNode principal = request.get("Principal");
        JsonNode resource = request.get("Resource");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("LakeFormationOptInsInfoList");
        for (LakeFormationOptIn optIn : optIns.values()) {
            if (principal != null && principal.isObject() && !principalMatches(optIn.principal, principal)) {
                continue;
            }
            if (resource != null && resource.isObject() && !resourceMatches(optIn.resource, resource)) {
                continue;
            }
            ObjectNode node = list.addObject();
            node.set("Principal", optIn.principal);
            node.set("Resource", optIn.resource);
        }
        return response;
    }

    private ObjectNode toLfTag(LfTag tag) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("CatalogId", tag.catalogId);
        node.put("TagKey", tag.tagKey);
        ArrayNode values = node.putArray("TagValues");
        for (String value : tag.tagValues) {
            values.add(value);
        }
        return node;
    }

    private ObjectNode toGrant(Grant grant) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("Principal", grant.principal);
        node.set("Resource", grant.resource);
        ArrayNode permissions = node.putArray("Permissions");
        for (String permission : grant.permissions) {
            permissions.add(permission);
        }
        ArrayNode withGrant = node.putArray("PermissionsWithGrantOption");
        for (String permission : grant.permissionsWithGrantOption) {
            withGrant.add(permission);
        }
        return node;
    }

    private ObjectNode toResourceInfo(RegisteredResource location) {
        ObjectNode info = objectMapper.createObjectNode();
        info.put("ResourceArn", location.getResourceArn());
        if (location.getRoleArn() != null) {
            info.put("RoleArn", location.getRoleArn());
        }
        info.put("LastModified", location.getLastModifiedEpochSeconds());
        info.put("WithFederation", location.isWithFederation());
        info.put("HybridAccessEnabled", location.isHybridAccessEnabled());
        info.put("WithPrivilegedAccess", location.isWithPrivilegedAccess());
        return info;
    }

    private RegisteredResource newRegistered(JsonNode request, String resourceArn) {
        RegisteredResource location = new RegisteredResource();
        location.setResourceArn(resourceArn);
        location.setLastModifiedEpochSeconds(Instant.now().getEpochSecond());
        applyRegistration(location, request);
        return location;
    }

    private static boolean isServiceLinked(RegisteredResource location) {
        if (location.isServiceLinkedRole()) {
            return true;
        }
        String roleArn = location.getRoleArn();
        return roleArn != null && roleArn.contains("/aws-service-role/lakeformation.amazonaws.com/");
    }

    private long slrCount() {
        return locations.values().stream().filter(LakeFormationService::isServiceLinked).count();
    }

    private void applyRegistration(RegisteredResource location, JsonNode request) {
        String roleArn = optionalText(request, "RoleArn");
        boolean useSlr = request.path("UseServiceLinkedRole").asBoolean(roleArn == null);
        if (roleArn != null) {
            location.setRoleArn(roleArn);
            location.setServiceLinkedRole(false);
        } else if (useSlr) {
            location.setRoleArn(SERVICE_LINKED_ROLE.formatted(regionResolver.getAccountId()));
            location.setServiceLinkedRole(true);
        }
        if (request.has("HybridAccessEnabled")) {
            location.setHybridAccessEnabled(request.path("HybridAccessEnabled").asBoolean(false));
        }
        if (request.has("WithFederation")) {
            location.setWithFederation(request.path("WithFederation").asBoolean(false));
        }
        if (request.has("WithPrivilegedAccess")) {
            location.setWithPrivilegedAccess(request.path("WithPrivilegedAccess").asBoolean(false));
        }
        String owner = optionalText(request, "ExpectedResourceOwnerAccount");
        if (owner != null) {
            location.setExpectedResourceOwnerAccount(owner);
        }
    }

    private String callerIdentity(String authorization) {
        String accountId = regionResolver.getAccountId();
        String accessKeyId = accountResolver.extractAccessKeyId(authorization);
        if (accessKeyId != null && accessKeyId.toUpperCase(Locale.ROOT).startsWith("ASIA")) {
            return "arn:aws:sts::" + accountId + ":assumed-role/floci/" + accessKeyId;
        }
        if (accessKeyId != null && !accessKeyId.isBlank()) {
            return "arn:aws:iam::" + accountId + ":user/" + accessKeyId;
        }
        return "arn:aws:iam::" + accountId + ":root";
    }

    private String catalogId(JsonNode request) {
        if (request != null && request.hasNonNull("CatalogId")) {
            String catalogId = request.get("CatalogId").asText();
            if (catalogId != null && !catalogId.isBlank()) {
                return catalogId;
            }
        }
        return regionResolver.getAccountId();
    }

    private LfTag findTag(String catalogId, String tagKey) {
        LfTag tag = tags.get(tagKey(catalogId, tagKey));
        if (tag != null) {
            return tag;
        }
        for (LfTag candidate : tags.values()) {
            if (tagKey.equals(candidate.tagKey)) {
                return candidate;
            }
        }
        return null;
    }

    private LfTagExpression requireExpression(JsonNode request, boolean delete) {
        String name = requireText(request, "Name");
        String catalogId = catalogId(request);
        String key = tagKey(catalogId, name);
        if (delete) {
            LfTagExpression removed = expressions.remove(key);
            if (removed == null) {
                throw notFound("LF-tag expression not found: " + name);
            }
            return removed;
        }
        LfTagExpression stored = expressions.get(key);
        if (stored == null) {
            throw notFound("LF-tag expression not found: " + name);
        }
        return stored;
    }

    private static String tagKey(String catalogId, String tagKey) {
        return catalogId + ":" + tagKey;
    }

    private static String grantKey(JsonNode principal, JsonNode resource) {
        return principalIdentifier(principal) + "|" + resourceKey(resource);
    }

    private static String optInKey(JsonNode principal, JsonNode resource) {
        return grantKey(principal, resource);
    }

    private static String filterKey(JsonNode node) {
        return nullToEmpty(optionalText(node, "TableCatalogId")) + "/"
                + nullToEmpty(optionalText(node, "DatabaseName")) + "/"
                + nullToEmpty(optionalText(node, "TableName")) + "/"
                + nullToEmpty(optionalText(node, "Name"));
    }

    private static String resourceKey(JsonNode resource) {
        String database = nestedName(resource, "Database");
        if (database != null) {
            return "DATABASE:" + database;
        }
        String table = nestedName(resource, "Table");
        if (table != null) {
            return "TABLE:" + nestedName(resource.path("Table"), "DatabaseName") + "/" + table;
        }
        return resource.toString();
    }

    private static String nestedName(JsonNode resource, String field) {
        JsonNode node = resource.get(field);
        if (node == null || !node.isObject()) {
            return optionalText(resource, "Name");
        }
        return optionalText(node, "Name");
    }

    private static boolean resourceMatches(JsonNode stored, JsonNode filter) {
        String storedDb = nestedName(stored, "Database");
        String filterDb = nestedName(filter, "Database");
        if (filterDb != null) {
            return filterDb.equals(storedDb);
        }
        String storedTable = nestedName(stored, "Table");
        String filterTable = nestedName(filter, "Table");
        if (filterTable != null) {
            return filterTable.equals(storedTable);
        }
        return stored.equals(filter);
    }

    private static boolean principalMatches(JsonNode stored, JsonNode filter) {
        String storedId = principalIdentifier(stored);
        String filterId = principalIdentifier(filter);
        return filterId != null && filterId.equals(storedId);
    }

    private static String principalIdentifier(JsonNode principal) {
        return optionalText(principal, "DataLakePrincipalIdentifier");
    }

    private static String normalizeArn(String arn) {
        return arn.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void union(List<String> target, List<String> extra) {
        for (String value : extra) {
            if (!target.contains(value)) {
                target.add(value);
            }
        }
    }

    private void requireExpression(JsonNode request) {
        JsonNode expression = request.get("Expression");
        if (expression == null || !expression.isArray() || expression.isEmpty()) {
            throw invalid("Expression is required.");
        }
        for (JsonNode condition : expression) {
            requireText(condition, "TagKey");
        }
    }

    private static List<String> tagKeys(JsonNode lfTags) {
        List<String> keys = new ArrayList<>();
        if (lfTags == null || !lfTags.isArray()) {
            return keys;
        }
        for (JsonNode node : lfTags) {
            String tagKey = optionalText(node, "TagKey");
            if (tagKey != null) {
                keys.add(tagKey);
            }
        }
        return keys;
    }

    private static String requireText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode request, String field) {
        if (request == null) {
            return null;
        }
        JsonNode value = request.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static List<String> requireStringList(JsonNode request, String field) {
        List<String> values = stringList(request, field);
        if (values.isEmpty()) {
            throw invalid(field + " is required.");
        }
        return values;
    }

    private static List<String> stringList(JsonNode request, String field) {
        List<String> values = new ArrayList<>();
        if (request == null) {
            return values;
        }
        JsonNode node = request.get(field);
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static AwsException notFound(String message) {
        return new AwsException("EntityNotFoundException", message, 400);
    }

    private static AwsException alreadyExists(String message) {
        return new AwsException("AlreadyExistsException", message, 400);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }
}
