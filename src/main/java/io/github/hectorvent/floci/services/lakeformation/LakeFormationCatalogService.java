package io.github.hectorvent.floci.services.lakeformation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.lakeformation.model.LFTag;
import io.github.hectorvent.floci.services.lakeformation.model.LFTagError;
import io.github.hectorvent.floci.services.lakeformation.model.LFTagPair;
import io.github.hectorvent.floci.services.lakeformation.model.Resource;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class LakeFormationCatalogService {

    private final LakeFormationStorage storage;
    private final AccountAwareStorageBackend<JsonNode> expressions;
    private final AccountAwareStorageBackend<JsonNode> filters;
    private final AccountAwareStorageBackend<JsonNode> optIns;
    private final GlueService glue;
    private final IamService iam;
    private final AccountResolver accounts;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    public LakeFormationCatalogService(LakeFormationStorage storage, StorageFactory factory, GlueService glue,
                                       IamService iam, AccountResolver accounts, RegionResolver regions,
                                       ObjectMapper mapper) {
        this.storage = storage;
        this.expressions = factory.create("lakeformation", "lakeformation-expressions.json", new TypeReference<>() {});
        this.filters = factory.create("lakeformation", "lakeformation-filters.json", new TypeReference<>() {});
        this.optIns = factory.create("lakeformation", "lakeformation-opt-ins.json", new TypeReference<>() {});
        this.glue = glue;
        this.iam = iam;
        this.accounts = accounts;
        this.regions = regions;
        this.mapper = mapper;
    }

    public Map<String, String> getDataLakePrincipal(String authorization) {
        String accessKey = authorization == null ? null : accounts.extractAccessKeyId(authorization);
        String identity = iam.resolveCallerArn(accessKey)
                .orElseGet(() -> AwsArnUtils.Arn.of("iam", "", regions.getAccountId(), "root").toString());
        return Map.of("Identity", identity);
    }

    public JsonNode getResourceLFTags(String region, JsonNode request) {
        String catalog = catalog(request, "CatalogId");
        ObjectNode resource = normalizeResource(request.path("Resource"), catalog, true);
        boolean assigned = request.path("ShowAssignedLFTags").asBoolean(false);
        ObjectNode response = mapper.createObjectNode();
        if (resource.has("Database")) {
            response.set("LFTagOnDatabase", mapper.valueToTree(tags(region, catalog, resource)));
            return response;
        }
        JsonNode target = resource.has("Table") ? resource.get("Table") : resource.get("TableWithColumns");
        ObjectNode database = databaseResource(catalog, text(target, "DatabaseName"));
        ObjectNode table = tableResource(catalog, text(target, "DatabaseName"), text(target, "Name"));
        List<LFTagPair> databaseTags = tags(region, catalog, database);
        List<LFTagPair> tableTags = tags(region, catalog, table);
        response.set("LFTagOnDatabase", mapper.valueToTree(assigned ? List.of() : databaseTags));
        List<LFTagPair> visibleTableTags = assigned
                ? (resource.has("Table") ? tableTags : List.of()) : mergeTags(databaseTags, tableTags);
        response.set("LFTagsOnTable", mapper.valueToTree(visibleTableTags));
        ArrayNode columns = response.putArray("LFTagsOnColumns");
        List<String> names = resource.has("TableWithColumns")
                ? selectedColumns(target) : columnNames(glue.getTable(text(target, "DatabaseName"), text(target, "Name")));
        for (String name : names) {
            List<LFTagPair> own = tags(region, catalog, columnResource(catalog, target, name));
            List<LFTagPair> effective = assigned ? own : mergeTags(mergeTags(databaseTags, tableTags), own);
            if (!effective.isEmpty()) {
                ObjectNode column = columns.addObject().put("Name", name);
                column.set("LFTags", mapper.valueToTree(effective));
            }
        }
        return response;
    }

    public List<LFTagError> changeTags(String region, String catalog, Resource resource,
                                       List<LFTagPair> tags, boolean remove) {
        ObjectNode request = mapper.createObjectNode().put("CatalogId", catalog);
        request.set("Resource", mapper.valueToTree(resource));
        request.set("LFTags", mapper.valueToTree(tags));
        return mapper.convertValue(changeTags(region, request, remove).get("Failures"), new TypeReference<>() {});
    }

    public synchronized JsonNode changeTags(String region, JsonNode request, boolean remove) {
        String catalog = catalog(request, "CatalogId");
        ObjectNode resource = normalizeResource(request.path("Resource"), catalog, true);
        JsonNode requestedTags = request.path("LFTags");
        requireArray(requestedTags, "LFTags");
        ObjectNode response = mapper.createObjectNode();
        ArrayNode failures = response.putArray("Failures");
        for (JsonNode tag : requestedTags) {
            try {
                LFTagPair pair = validateTag(region, catalog, tag, false);
                if (pair.getTagValues().size() != 1) {
                    throw invalid("A resource can have only one value for each LF-tag key");
                }
                List<ObjectNode> targets = new ArrayList<>();
                if (resource.has("TableWithColumns")) {
                    JsonNode table = resource.get("TableWithColumns");
                    for (String column : selectedColumns(table)) {
                        targets.add(columnResource(catalog, table, column));
                    }
                } else {
                    targets.add(resource);
                }
                for (ObjectNode target : targets) {
                    Resource typed = mapper.convertValue(target, Resource.class);
                    if (remove) {
                        storage.removeLFTagsFromResource(region, catalog, typed, List.of(pair));
                    } else {
                        storage.addLFTagsToResource(region, catalog, typed, List.of(pair));
                    }
                }
            } catch (AwsException error) {
                ObjectNode failure = failures.addObject();
                failure.set("LFTag", tag.deepCopy());
                failure.putObject("Error").put("ErrorCode", error.getErrorCode())
                        .put("ErrorMessage", error.getMessage());
            }
        }
        return response;
    }

    public JsonNode search(String region, JsonNode request, boolean tables) {
        String catalog = catalog(request, "CatalogId");
        requireLocalCatalog(catalog);
        List<LFTagPair> expression = validateExpression(region, catalog, request.path("Expression"));
        List<JsonNode> matches = new ArrayList<>();
        for (Database database : glue.getDatabases()) {
            ObjectNode databaseResource = databaseResource(catalog, database.getName());
            List<LFTagPair> databaseTags = tags(region, catalog, databaseResource);
            if (!tables) {
                if (matches(expression, databaseTags)) {
                    ObjectNode entry = mapper.createObjectNode();
                    entry.set("Database", databaseResource.get("Database"));
                    entry.set("LFTags", mapper.valueToTree(databaseTags));
                    matches.add(entry);
                }
            } else {
                for (Table table : glue.getTables(database.getName())) {
                    ObjectNode resource = tableResource(catalog, database.getName(), table.getName());
                    List<LFTagPair> effective = mergeTags(databaseTags, tags(region, catalog, resource));
                    ArrayNode columns = mapper.createArrayNode();
                    for (String name : columnNames(table)) {
                        List<LFTagPair> columnTags = mergeTags(effective,
                                tags(region, catalog, columnResource(catalog, resource.get("Table"), name)));
                        if (matches(expression, columnTags)) {
                            columns.addObject().put("Name", name).set("LFTags", mapper.valueToTree(columnTags));
                        }
                    }
                    if (matches(expression, effective) || !columns.isEmpty()) {
                        ObjectNode entry = mapper.createObjectNode();
                        entry.set("Table", resource.get("Table"));
                        entry.set("LFTagOnDatabase", mapper.valueToTree(databaseTags));
                        entry.set("LFTagsOnTable", mapper.valueToTree(effective));
                        entry.set("LFTagsOnColumns", columns);
                        matches.add(entry);
                    }
                }
            }
        }
        return page(region, request, tables ? "TableList" : "DatabaseList", matches);
    }

    public synchronized JsonNode expression(String region, String operation, JsonNode request) {
        String catalog = catalog(request, "CatalogId");
        String prefix = region + ":" + catalog + ":";
        if (operation.equals("ListLFTagExpressions")) {
            return page(region, request, "LFTagExpressions", expressions.scan(key -> key.startsWith(prefix)));
        }
        String name = text(request, "Name");
        String key = prefix + encode(name);
        JsonNode existing = expressions.get(key).orElse(null);
        return switch (operation) {
            case "GetLFTagExpression" -> requireFound(existing, "LF-tag expression").deepCopy();
            case "DeleteLFTagExpression" -> {
                requireFound(existing, "LF-tag expression");
                expressions.delete(key);
                yield mapper.createObjectNode();
            }
            case "CreateLFTagExpression", "UpdateLFTagExpression" -> {
                boolean create = operation.equals("CreateLFTagExpression");
                if (create && existing != null) {
                    throw alreadyExists("LF-tag expression");
                }
                if (!create) {
                    requireFound(existing, "LF-tag expression");
                }
                ObjectNode value = create ? mapper.createObjectNode() : (ObjectNode) existing.deepCopy();
                value.put("Name", name).put("CatalogId", catalog);
                if (create || request.has("Expression")) {
                    validateExpression(region, catalog, request.path("Expression"));
                    value.set("Expression", request.get("Expression").deepCopy());
                }
                if (request.has("Description")) {
                    value.set("Description", request.get("Description").deepCopy());
                }
                expressions.put(key, value);
                yield mapper.createObjectNode();
            }
            default -> throw invalid("Unsupported LF-tag expression operation");
        };
    }

    public synchronized JsonNode dataCellsFilter(String region, String operation, JsonNode request) {
        if (operation.equals("ListDataCellsFilter")) {
            JsonNode table = request.path("Table");
            String catalog = catalog(table, "CatalogId");
            if (!table.isMissingNode()) {
                normalizeResource(mapper.createObjectNode().set("Table", table), catalog, true);
            }
            String prefix = region + ":" + catalog + ":";
            List<JsonNode> values = filters.scan(key -> key.startsWith(prefix)).stream()
                    .filter(value -> table.isMissingNode() || (value.path("DatabaseName").equals(table.path("DatabaseName"))
                            && value.path("TableName").equals(table.path("Name"))))
                    .filter(this::filterTableExists).toList();
            return page(region, request, "DataCellsFilters", values);
        }
        boolean write = operation.equals("CreateDataCellsFilter") || operation.equals("UpdateDataCellsFilter");
        JsonNode data = write ? request.path("TableData") : request;
        String catalog = catalog(data, "TableCatalogId");
        String database = text(data, "DatabaseName");
        String table = text(data, "TableName");
        String name = text(data, "Name");
        String key = region + ":" + catalog + ":" + encode(database) + ":" + encode(table) + ":" + encode(name);
        JsonNode existing = filters.get(key).orElse(null);
        if (operation.equals("DeleteDataCellsFilter")) {
            requireFound(existing, "Data cells filter");
            filters.delete(key);
            return mapper.createObjectNode();
        }
        requireLocalCatalog(catalog);
        Table metadata = glue.getTable(database, table);
        if (operation.equals("GetDataCellsFilter")) {
            return mapper.createObjectNode().set("DataCellsFilter", requireFound(existing, "Data cells filter").deepCopy());
        }
        if (operation.equals("CreateDataCellsFilter") && existing != null) {
            throw alreadyExists("Data cells filter");
        }
        if (operation.equals("UpdateDataCellsFilter")) {
            requireFound(existing, "Data cells filter");
            if (data.has("VersionId") && !data.get("VersionId").equals(existing.get("VersionId"))) {
                throw new AwsException("ConcurrentModificationException", "Data cells filter version has changed", 400);
            }
        }
        validateFilter(data, metadata);
        ObjectNode value = (ObjectNode) data.deepCopy();
        value.put("TableCatalogId", catalog).put("VersionId", UUID.randomUUID().toString());
        filters.put(key, value);
        return mapper.createObjectNode();
    }

    public synchronized JsonNode optIn(String region, String operation, JsonNode request, String authorization) {
        if (operation.equals("ListLakeFormationOptIns")) {
            ObjectNode resource = request.has("Resource")
                    ? normalizeResource(request.get("Resource"), regions.getAccountId(), false) : null;
            List<JsonNode> values = optIns.scan(key -> key.startsWith(region + ":")).stream()
                    .filter(value -> !request.has("Principal") || request.get("Principal").equals(value.get("Principal")))
                    .filter(value -> resource == null || resource.equals(value.get("Resource")))
                    .filter(value -> resourceExists(value.get("Resource"))).toList();
            return page(region, request, "LakeFormationOptInsInfoList", values);
        }
        JsonNode principal = request.path("Principal");
        String identifier = text(principal, "DataLakePrincipalIdentifier");
        ObjectNode resource = normalizeResource(request.path("Resource"), regions.getAccountId(),
                operation.equals("CreateLakeFormationOptIn"));
        String key = region + ":" + encode(identifier) + ":" + encode(resource.toString());
        if (operation.equals("DeleteLakeFormationOptIn")) {
            requireFound(optIns.get(key).orElse(null), "Lake Formation opt-in");
            optIns.delete(key);
            return mapper.createObjectNode();
        }
        if (optIns.get(key).isPresent()) {
            throw alreadyExists("Lake Formation opt-in");
        }
        validatePrincipal(identifier);
        ObjectNode value = mapper.createObjectNode();
        value.set("Principal", principal.deepCopy());
        value.set("Resource", resource);
        if (request.has("Condition")) {
            value.set("Condition", request.get("Condition").deepCopy());
        }
        value.put("LastModified", Instant.now().getEpochSecond());
        value.put("LastUpdatedBy", getDataLakePrincipal(authorization).get("Identity"));
        optIns.put(key, value);
        return mapper.createObjectNode();
    }

    private void validatePrincipal(String identifier) {
        if ("IAM_ALLOWED_PRINCIPALS".equals(identifier)) {
            return;
        }
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(identifier);
            String resource = arn.resource();
            if ("iam".equals(arn.service()) && arn.region().isEmpty()) {
                String name = resource.substring(resource.lastIndexOf('/') + 1);
                if (resource.equals("root") && arn.accountId().matches("[0-9]{12}")) {
                    return;
                }
                if (resource.startsWith("role/") && iam.findRole(arn.accountId(), name)
                        .map(role -> role.getArn().equals(identifier)).orElse(false)) {
                    return;
                }
                if (resource.startsWith("user/") && iam.findUser(arn.accountId(), name)
                        .map(user -> user.getArn().equals(identifier)).orElse(false)) {
                    return;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid principal ARNs use Lake Formation's InvalidInputException.
        }
        throw invalid("Invalid principal: " + identifier);
    }

    private void validateFilter(JsonNode data, Table table) {
        JsonNode row = data.path("RowFilter");
        if (!row.isObject() || row.has("FilterExpression") == row.has("AllRowsWildcard")) {
            throw invalid("RowFilter must specify either FilterExpression or AllRowsWildcard");
        }
        if (row.has("FilterExpression")) {
            text(row, "FilterExpression");
        }
        if (data.has("ColumnNames") == data.has("ColumnWildcard")) {
            throw invalid("Specify either ColumnNames or ColumnWildcard");
        }
        JsonNode columns = data.has("ColumnNames") ? data.get("ColumnNames")
                : data.path("ColumnWildcard").path("ExcludedColumnNames");
        if (!columns.isMissingNode()) {
            validateColumns(columns, columnNames(table));
        }
    }

    private boolean filterTableExists(JsonNode value) {
        return resourceExists(tableResource(value.path("TableCatalogId").asText(),
                value.path("DatabaseName").asText(), value.path("TableName").asText()));
    }

    private boolean resourceExists(JsonNode resource) {
        try {
            normalizeResource(resource, regions.getAccountId(), true);
            return true;
        } catch (AwsException error) {
            if (!"EntityNotFoundException".equals(error.getErrorCode())) {
                throw error;
            }
            return false;
        }
    }

    private ObjectNode normalizeResource(JsonNode resource, String catalog, boolean observe) {
        if (!resource.isObject() || resource.size() != 1) {
            throw invalid("Resource must identify exactly one Data Catalog resource");
        }
        String kind = resource.fieldNames().next();
        JsonNode target = resource.get(kind);
        String actualCatalog = target.hasNonNull("CatalogId") ? text(target, "CatalogId") : catalog;
        requireLocalCatalog(actualCatalog);
        if (!actualCatalog.equals(catalog)) {
            throw invalid("Resource CatalogId must match CatalogId");
        }
        ObjectNode result = mapper.createObjectNode();
        ObjectNode normalized = result.putObject(kind).put("CatalogId", catalog);
        switch (kind) {
            case "Database" -> {
                String name = text(target, "Name");
                if (observe) {
                    name = glue.getDatabase(name).getName();
                }
                normalized.put("Name", name);
            }
            case "Table", "TableWithColumns" -> {
                String database = text(target, "DatabaseName");
                String name = text(target, "Name");
                if (observe) {
                    Table table = glue.getTable(database, name);
                    database = table.getDatabaseName();
                    name = table.getName();
                }
                normalized.put("DatabaseName", database).put("Name", name);
                if (kind.equals("TableWithColumns")) {
                    if (target.has("ColumnNames") == target.has("ColumnWildcard")) {
                        throw invalid("Specify either ColumnNames or ColumnWildcard");
                    }
                    if (target.has("ColumnNames")) {
                        normalized.set("ColumnNames", target.get("ColumnNames").deepCopy());
                    } else {
                        normalized.set("ColumnWildcard", target.get("ColumnWildcard").deepCopy());
                    }
                    if (observe) {
                        selectedColumns(normalized);
                    }
                }
            }
            default -> throw invalid("LF-tags and opt-ins require a database, table, or table columns resource");
        }
        return result;
    }

    private List<String> selectedColumns(JsonNode target) {
        List<String> available = columnNames(glue.getTable(text(target, "DatabaseName"), text(target, "Name")));
        if (target.has("ColumnNames")) {
            requireArray(target.get("ColumnNames"), "ColumnNames");
            return validateColumns(target.get("ColumnNames"), available);
        }
        JsonNode excluded = target.path("ColumnWildcard").path("ExcludedColumnNames");
        List<String> exclusions = excluded.isMissingNode() ? List.of() : validateColumns(excluded, available);
        return available.stream().filter(name -> !exclusions.contains(name)).toList();
    }

    private List<String> validateColumns(JsonNode columns, List<String> available) {
        if (!columns.isArray()) {
            throw invalid("Column names must be an array");
        }
        List<String> selected = new ArrayList<>();
        for (JsonNode column : columns) {
            if (!column.isTextual() || !available.contains(column.asText())) {
                throw invalid("Column does not exist: " + column.asText());
            }
            selected.add(column.asText());
        }
        return selected;
    }

    private List<String> columnNames(Table table) {
        List<String> names = new ArrayList<>();
        if (table.getStorageDescriptor() != null && table.getStorageDescriptor().getColumns() != null) {
            for (Column column : table.getStorageDescriptor().getColumns()) {
                names.add(column.getName());
            }
        }
        if (table.getPartitionKeys() != null) {
            for (Column column : table.getPartitionKeys()) {
                names.add(column.getName());
            }
        }
        return names;
    }

    private ObjectNode databaseResource(String catalog, String name) {
        ObjectNode resource = mapper.createObjectNode();
        resource.putObject("Database").put("CatalogId", catalog).put("Name", name);
        return resource;
    }

    private ObjectNode tableResource(String catalog, String database, String name) {
        ObjectNode resource = mapper.createObjectNode();
        resource.putObject("Table").put("CatalogId", catalog).put("DatabaseName", database).put("Name", name);
        return resource;
    }

    private ObjectNode columnResource(String catalog, JsonNode table, String name) {
        ObjectNode resource = mapper.createObjectNode();
        resource.putObject("TableWithColumns").put("CatalogId", catalog)
                .put("DatabaseName", text(table, "DatabaseName")).put("Name", text(table, "Name"))
                .putArray("ColumnNames").add(name);
        return resource;
    }

    private List<LFTagPair> tags(String region, String catalog, ObjectNode resource) {
        return storage.getResourceLFTags(region, catalog, mapper.convertValue(resource, Resource.class)).stream()
                .filter(pair -> storage.getLFTag(region, pair.getCatalogId(), pair.getTagKey())
                        .map(tag -> tag.getTagValues().containsAll(pair.getTagValues())).orElse(false)).toList();
    }

    private List<LFTagPair> mergeTags(List<LFTagPair> inherited, List<LFTagPair> assigned) {
        Map<String, LFTagPair> result = new LinkedHashMap<>();
        for (LFTagPair tag : inherited) {
            result.put(tag.getTagKey(), tag);
        }
        for (LFTagPair tag : assigned) {
            result.put(tag.getTagKey(), tag);
        }
        return new ArrayList<>(result.values());
    }

    private List<LFTagPair> validateExpression(String region, String catalog, JsonNode expression) {
        requireArray(expression, "Expression");
        List<LFTagPair> result = new ArrayList<>();
        for (JsonNode tag : expression) {
            result.add(validateTag(region, catalog, tag, true));
        }
        return result;
    }

    private LFTagPair validateTag(String region, String catalog, JsonNode requested, boolean wildcard) {
        String tagCatalog = requested.hasNonNull("CatalogId") ? text(requested, "CatalogId") : catalog;
        requireLocalCatalog(tagCatalog);
        String key = text(requested, "TagKey");
        requireArray(requested.path("TagValues"), "TagValues");
        LFTag tag = storage.getLFTag(region, tagCatalog, key)
                .orElseThrow(() -> notFound("LF-tag " + key));
        List<String> values = new ArrayList<>();
        for (JsonNode value : requested.get("TagValues")) {
            if (!value.isTextual() || (!tag.getTagValues().contains(value.asText())
                    && !(wildcard && "*".equals(value.asText())))) {
                throw invalid("Undefined value for LF-tag " + key);
            }
            values.add(value.asText());
        }
        LFTagPair pair = new LFTagPair();
        pair.setCatalogId(tagCatalog);
        pair.setTagKey(key);
        pair.setTagValues(values);
        return pair;
    }

    private boolean matches(List<LFTagPair> expression, List<LFTagPair> tags) {
        return expression.stream().allMatch(condition -> tags.stream().anyMatch(tag ->
                condition.getCatalogId().equals(tag.getCatalogId()) && condition.getTagKey().equals(tag.getTagKey())
                        && (condition.getTagValues().contains("*")
                        || condition.getTagValues().stream().anyMatch(tag.getTagValues()::contains))));
    }

    private JsonNode page(String region, JsonNode request, String field, List<JsonNode> values) {
        int limit = request.path("MaxResults").asInt(100);
        if (limit < 1 || limit > 1000) {
            throw invalid("MaxResults must be between 1 and 1000");
        }
        ObjectNode query = (ObjectNode) request.deepCopy();
        query.remove(List.of("NextToken", "MaxResults"));
        String scope = regions.getAccountId() + ":" + region + ":" + field + ":" + query;
        int offset = 0;
        if (request.hasNonNull("NextToken")) {
            try {
                String token = new String(Base64.getUrlDecoder().decode(text(request, "NextToken")), StandardCharsets.UTF_8);
                int split = token.lastIndexOf('\n');
                if (split < 0 || !token.substring(0, split).equals(scope)) {
                    throw new IllegalArgumentException("Wrong token scope");
                }
                offset = Integer.parseInt(token.substring(split + 1));
            } catch (IllegalArgumentException error) {
                throw invalid("Invalid NextToken");
            }
        }
        List<JsonNode> sorted = values.stream().sorted(Comparator.comparing(JsonNode::toString)).toList();
        if (offset < 0 || offset > sorted.size()) {
            throw invalid("Invalid NextToken");
        }
        int end = Math.min(sorted.size(), offset + limit);
        ObjectNode response = mapper.createObjectNode();
        response.set(field, mapper.valueToTree(sorted.subList(offset, end)));
        if (end < sorted.size()) {
            response.put("NextToken", encode(scope + "\n" + end));
        }
        return response;
    }

    private String catalog(JsonNode request, String field) {
        return request.hasNonNull(field) ? text(request, field) : regions.getAccountId();
    }

    private void requireLocalCatalog(String catalog) {
        if (!regions.getAccountId().equals(catalog)) {
            throw new AwsException("AccessDeniedException", "Cross-account catalog access requires a resource share", 400);
        }
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid(field + " is required");
        }
        return value.asText();
    }

    private static void requireArray(JsonNode node, String field) {
        if (!node.isArray() || node.isEmpty()) {
            throw invalid(field + " must be a nonempty array");
        }
    }

    private static JsonNode requireFound(JsonNode value, String resource) {
        if (value == null) {
            throw notFound(resource);
        }
        return value;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }

    private static AwsException notFound(String resource) {
        return new AwsException("EntityNotFoundException", resource + " not found", 400);
    }

    private static AwsException alreadyExists(String resource) {
        return new AwsException("AlreadyExistsException", resource + " already exists", 400);
    }
}
