package io.github.hectorvent.floci.services.lakeformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.lakeformation.model.DataLakeSettings;
import io.github.hectorvent.floci.services.lakeformation.model.DataLakePrincipal;
import io.github.hectorvent.floci.services.lakeformation.model.PrincipalResourcePermissions;
import io.github.hectorvent.floci.services.lakeformation.model.Resource;
import io.github.hectorvent.floci.services.lakeformation.model.ResourceInfo;
import io.github.hectorvent.floci.services.lakeformation.model.TableResource;
import io.github.hectorvent.floci.services.lakeformation.model.TableWithColumnsResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Lake Formation data-access operations: effective permissions for an S3 path and the
 * {@code lakeformation:GetDataAccess} credential-vending APIs. Decisions are derived from
 * the emulator's real state: registered locations, explicit grants, data lake settings and
 * the Glue Data Catalog. Vended credentials are genuine temporary sessions for the role the
 * location was registered with, scoped down to the vended S3 prefix.
 */
@ApplicationScoped
public class LakeFormationDataAccessService {

    private static final int MIN_DURATION_SECONDS = 900;
    private static final int MAX_DURATION_SECONDS = 43_200;
    private static final int DEFAULT_DURATION_SECONDS = 3_600;
    private static final String COLUMN_PERMISSION = "COLUMN_PERMISSION";
    private static final String CELL_FILTER_PERMISSION = "CELL_FILTER_PERMISSION";
    private static final Set<String> PERMISSION_TYPES = Set.of(
            COLUMN_PERMISSION, CELL_FILTER_PERMISSION, "NESTED_PERMISSION", "NESTED_CELL_PERMISSION");
    private static final Set<String> WRITE_PERMISSIONS = Set.of("INSERT", "DELETE", "ALTER", "DROP");
    private static final String SECRET_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String KEY_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final LakeFormationStorage storage;
    private final GlueService glue;
    private final IamService iam;
    private final AccountResolver accounts;
    private final RegionResolver regions;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public LakeFormationDataAccessService(LakeFormationStorage storage, GlueService glue, IamService iam,
                                          AccountResolver accounts, RegionResolver regions, ObjectMapper mapper) {
        this.storage = storage;
        this.glue = glue;
        this.iam = iam;
        this.accounts = accounts;
        this.regions = regions;
        this.mapper = mapper;
    }

    // --- GetEffectivePermissionsForPath ---

    public JsonNode getEffectivePermissionsForPath(String region, JsonNode request) {
        String catalog = catalog(request);
        String path = s3Path(requiredText(request, "ResourceArn"), "ResourceArn");
        int limit = request.hasNonNull("MaxResults") ? request.get("MaxResults").asInt() : 100;
        if (limit < 1 || limit > 1000) {
            throw invalid("MaxResults must be between 1 and 1000");
        }
        List<PrincipalResourcePermissions> matches = new ArrayList<>();
        // An unregistered path is not governed by Lake Formation, so nothing is effective there.
        if (registeredLocation(region, path).isPresent() && regions.getAccountId().equals(catalog)) {
            List<PrincipalResourcePermissions> grants = grants(region, catalog);
            for (PrincipalResourcePermissions grant : grants) {
                if (grantAppliesToPath(grant.getResource(), path)) {
                    matches.add(grant);
                }
            }
        }
        matches.sort(Comparator.comparing(this::sortKey));
        int offset = decodeOffset(request, path, matches.size());
        int end = Math.min(matches.size(), offset + limit);
        ObjectNode response = mapper.createObjectNode();
        response.set("Permissions", mapper.valueToTree(matches.subList(offset, end)));
        if (end < matches.size()) {
            response.put("NextToken", encodeOffset(path, end));
        }
        return response;
    }

    private boolean grantAppliesToPath(Resource resource, String path) {
        if (resource == null) {
            return false;
        }
        if (resource.getDataLocation() != null && resource.getDataLocation().getResourceArn() != null) {
            String location = tryS3Path(resource.getDataLocation().getResourceArn());
            return location != null && covers(location, path);
        }
        if (resource.getDatabase() != null) {
            String location = databaseLocation(resource.getDatabase().getName());
            return location != null && covers(path, location);
        }
        if (resource.getTable() != null) {
            TableResource table = resource.getTable();
            if (table.getTableWildcard() != null) {
                return tablesIn(table.getDatabaseName()).stream()
                        .map(this::tableLocation).anyMatch(location -> location != null && covers(path, location));
            }
            String location = tableLocation(table.getDatabaseName(), table.getName());
            return location != null && covers(path, location);
        }
        if (resource.getTableWithColumns() != null) {
            TableWithColumnsResource table = resource.getTableWithColumns();
            String location = tableLocation(table.getDatabaseName(), table.getName());
            return location != null && covers(path, location);
        }
        return false;
    }

    // --- GetTemporaryGlueTableCredentials / GetTemporaryGluePartitionCredentials ---

    public JsonNode getTemporaryGlueTableCredentials(String region, JsonNode request, String authorization) {
        TableTarget target = resolveTable(region, request);
        String location = tableLocation(target.table());
        if (location == null) {
            throw invalid("Table " + target.qualifiedName() + " does not have an Amazon S3 location");
        }
        String vended = location;
        if (request.hasNonNull("S3Path")) {
            vended = s3Path(requiredText(request, "S3Path"), "S3Path");
            if (!covers(location, vended)) {
                throw invalid("S3Path must be within the table location");
            }
        }
        ObjectNode response = authorizeTableAccess(region, request, authorization, target, vended);
        response.putArray("VendedS3Path").add("s3://" + vended);
        return response;
    }

    public JsonNode getTemporaryGluePartitionCredentials(String region, JsonNode request, String authorization) {
        TableTarget target = resolveTable(region, request);
        JsonNode values = request.path("Partition").path("Values");
        if (!values.isArray() || values.isEmpty()) {
            throw invalid("Partition.Values is required");
        }
        List<String> partitionValues = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw invalid("Partition values must be strings");
            }
            partitionValues.add(value.asText());
        }
        int keys = target.table().getPartitionKeys() == null ? 0 : target.table().getPartitionKeys().size();
        if (keys != partitionValues.size()) {
            throw invalid("The number of partition values does not match the table's partition keys");
        }
        Partition partition = glue.getPartition(target.database(), target.name(), partitionValues);
        String location = partition.getStorageDescriptor() == null ? null
                : tryS3Path(partition.getStorageDescriptor().getLocation());
        if (location == null) {
            throw invalid("Partition does not have an Amazon S3 location");
        }
        return authorizeTableAccess(region, request, authorization, target, location);
    }

    private TableTarget resolveTable(String region, JsonNode request) {
        String tableArn = requiredText(request, "TableArn");
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(tableArn);
        } catch (IllegalArgumentException e) {
            throw invalid("TableArn must be a valid Glue table ARN");
        }
        String[] parts = arn.resource().split("/", 3);
        if (!"glue".equals(arn.service()) || parts.length != 3 || !"table".equals(parts[0])
                || parts[1].isBlank() || parts[2].isBlank() || arn.accountId().isBlank()) {
            throw invalid("TableArn must be a valid Glue table ARN");
        }
        if (!arn.region().isEmpty() && !arn.region().equals(region)) {
            throw invalid("Lake Formation does not vend credentials for a table in another Region");
        }
        if (!regions.getAccountId().equals(arn.accountId())) {
            throw new AwsException("AccessDeniedException",
                    "Insufficient Lake Formation permission(s) on " + tableArn, 400);
        }
        validateDuration(request);
        Table table = glue.getTable(parts[1], parts[2]);
        return new TableTarget(arn.accountId(), table.getDatabaseName() != null ? table.getDatabaseName() : parts[1],
                table.getName(), table);
    }

    private ObjectNode authorizeTableAccess(String region, JsonNode request, String authorization,
                                      TableTarget target, String vendedPath) {
        List<String> requested = requestedPermissions(request.path("Permissions"));
        Set<String> supported = supportedPermissionTypes(request.path("SupportedPermissionTypes"));
        ResourceInfo registration = registeredLocation(region, vendedPath)
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "The location of table " + target.qualifiedName()
                                + " is not registered with Lake Formation", 400));
        Caller caller = caller(authorization);
        Access access = tableAccess(region, target, caller);
        if (!access.fullGranted(requested)) {
            if (!access.partialGranted(requested)) {
                throw new AwsException("AccessDeniedException",
                        "Insufficient Lake Formation permission(s) on " + target.qualifiedName(), 400);
            }
            String needed = access.cellFiltered() ? CELL_FILTER_PERMISSION : COLUMN_PERMISSION;
            if (!supported.contains(needed)) {
                throw new AwsException("PermissionTypeMismatchException",
                        "The caller's permissions on " + target.qualifiedName() + " require " + needed
                                + ", which is not in SupportedPermissionTypes", 400);
            }
            requireExternalFiltering(region, caller);
        } else {
            requireFullTableAccess(region);
        }
        boolean write = requested.stream().anyMatch(p -> "ALL".equals(p) || WRITE_PERMISSIONS.contains(p));
        return mint(registration.getRoleArn(), List.of(vendedPath), write, request, caller);
    }

    // --- GetTemporaryDataLocationCredentials ---

    public JsonNode getTemporaryDataLocationCredentials(String region, JsonNode request, String authorization) {
        JsonNode requestedLocations = request.path("DataLocations");
        if (!requestedLocations.isArray() || requestedLocations.isEmpty()) {
            throw invalid("DataLocations must be a nonempty list");
        }
        String scope = request.hasNonNull("CredentialsScope") ? request.get("CredentialsScope").asText() : "READ";
        if (!"READ".equals(scope) && !"READWRITE".equals(scope)) {
            throw invalid("CredentialsScope must be READ or READWRITE");
        }
        validateDuration(request);
        List<String> original = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (JsonNode location : requestedLocations) {
            if (!location.isTextual() || location.asText().isBlank()) {
                throw invalid("DataLocations entries must be nonempty strings");
            }
            original.add(location.asText());
            paths.add(s3Path(location.asText(), "DataLocations"));
        }
        String roleArn = null;
        for (String path : paths) {
            ResourceInfo registration = registeredLocation(region, path)
                    .orElseThrow(() -> new AwsException("EntityNotFoundException",
                            "Data location s3://" + path + " is not registered with Lake Formation", 400));
            if (roleArn != null && !roleArn.equals(registration.getRoleArn())) {
                throw new AwsException("ConflictException",
                        "The requested data locations are registered with different roles", 400);
            }
            roleArn = registration.getRoleArn();
        }
        Caller caller = caller(authorization);
        boolean write = "READWRITE".equals(scope);
        for (String path : paths) {
            List<TableTarget> tables = tablesAssociatedWith(path);
            if (tables.isEmpty()) {
                throw new AwsException("EntityNotFoundException",
                        "No Glue table is associated with data location s3://" + path, 400);
            }
            List<String> needed = write ? List.of("SELECT", "INSERT", "DELETE") : List.of("SELECT");
            boolean allowed = tables.stream()
                    .anyMatch(table -> tableAccess(region, table, caller).fullGranted(needed));
            if (!allowed) {
                throw new AwsException("AccessDeniedException",
                        "Insufficient Lake Formation permission(s) on the table associated with s3://" + path, 400);
            }
        }
        requireFullTableAccess(region);
        ObjectNode response = mapper.createObjectNode();
        response.set("Credentials", mint(roleArn, paths, write, request, caller));
        ArrayNode accessible = response.putArray("AccessibleDataLocations");
        original.forEach(accessible::add);
        response.put("CredentialsScope", scope);
        return response;
    }

    private List<TableTarget> tablesAssociatedWith(String path) {
        List<TableTarget> result = new ArrayList<>();
        for (Database database : glue.getDatabases()) {
            for (Table table : glue.getTables(database.getName())) {
                String location = tableLocation(table);
                if (location != null && (covers(location, path) || covers(path, location))) {
                    result.add(new TableTarget(regions.getAccountId(), database.getName(), table.getName(), table));
                }
            }
        }
        return result;
    }

    // --- authorization ---

    private Access tableAccess(String region, TableTarget target, Caller caller) {
        Set<String> full = new LinkedHashSet<>();
        Set<String> partial = new LinkedHashSet<>();
        boolean cellFiltered = false;
        for (PrincipalResourcePermissions grant : grants(region, target.catalog())) {
            if (grant.getPrincipal() == null || grant.getPermissions() == null
                    || !caller.identifiers().contains(grant.getPrincipal().getDataLakePrincipalIdentifier())) {
                continue;
            }
            Resource resource = grant.getResource();
            if (resource == null) {
                continue;
            }
            if (resource.getTable() != null && sameTable(resource.getTable().getCatalogId(),
                    resource.getTable().getDatabaseName(), target)
                    && (resource.getTable().getTableWildcard() != null
                    || target.name().equalsIgnoreCase(resource.getTable().getName()))) {
                full.addAll(grant.getPermissions());
            } else if (resource.getTableWithColumns() != null) {
                TableWithColumnsResource columns = resource.getTableWithColumns();
                if (sameTable(columns.getCatalogId(), columns.getDatabaseName(), target)
                        && target.name().equalsIgnoreCase(columns.getName())) {
                    boolean allColumns = columns.getColumnWildcard() != null
                            && (columns.getColumnWildcard().getExcludedColumnNames() == null
                            || columns.getColumnWildcard().getExcludedColumnNames().isEmpty());
                    (allColumns ? full : partial).addAll(grant.getPermissions());
                }
            } else if (resource.getDataCellsFilter() != null) {
                var filter = resource.getDataCellsFilter();
                if (sameTable(filter.getTableCatalogId(), filter.getDatabaseName(), target)
                        && target.name().equalsIgnoreCase(filter.getTableName())) {
                    partial.addAll(grant.getPermissions());
                    cellFiltered = true;
                }
            }
        }
        return new Access(full, partial, cellFiltered);
    }

    private static boolean sameTable(String catalogId, String database, TableTarget target) {
        return (catalogId == null || catalogId.equals(target.catalog()))
                && database != null && database.equalsIgnoreCase(target.database());
    }

    private void requireFullTableAccess(String region) {
        DataLakeSettings settings = settings(region);
        if (!Boolean.TRUE.equals(settings.getAllowFullTableExternalDataAccess())) {
            throw new AwsException("AccessDeniedException",
                    "Credential vending for full table access is not enabled in the data lake settings", 400);
        }
    }

    private void requireExternalFiltering(String region, Caller caller) {
        DataLakeSettings settings = settings(region);
        boolean listed = settings.getExternalDataFilteringAllowList() != null
                && settings.getExternalDataFilteringAllowList().stream()
                .map(DataLakePrincipal::getDataLakePrincipalIdentifier)
                .anyMatch(id -> caller.accountId().equals(id) || caller.identifiers().contains(id));
        if (!Boolean.TRUE.equals(settings.getAllowExternalDataFiltering()) || !listed) {
            throw new AwsException("AccessDeniedException",
                    "External data filtering is not allowed for the caller in the data lake settings", 400);
        }
    }

    private DataLakeSettings settings(String region) {
        return storage.getDataLakeSettings(region, regions.getAccountId()).orElseGet(DataLakeSettings::new);
    }

    private Caller caller(String authorization) {
        String accountId = regions.getAccountId();
        String accessKey = authorization == null ? null : accounts.extractAccessKeyId(authorization);
        String arn = iam.resolveCallerArn(accessKey)
                .orElseGet(() -> AwsArnUtils.Arn.of("iam", "", accountId, "root").toString());
        List<String> identifiers = new ArrayList<>();
        identifiers.add(arn);
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if ("sts".equals(parsed.service()) && parsed.resource().startsWith("assumed-role/")) {
                String roleName = parsed.resource().split("/")[1];
                identifiers.add(iam.findRole(parsed.accountId(), roleName).map(IamRole::getArn)
                        .orElseGet(() -> AwsArnUtils.Arn.of("iam", "", parsed.accountId(), "role/" + roleName)
                                .toString()));
            }
        } catch (IllegalArgumentException ignored) {
            // A non-ARN identity only matches grants made to that literal identifier.
        }
        return new Caller(accountId, identifiers);
    }

    // --- credential minting ---

    private ObjectNode mint(String roleArn, List<String> paths, boolean write, JsonNode request, Caller caller) {
        int duration = request.hasNonNull("DurationSeconds")
                ? request.get("DurationSeconds").asInt() : DEFAULT_DURATION_SECONDS;
        Instant expiration = Instant.now().plusSeconds(duration);
        String accessKeyId = "ASIA" + randomString(KEY_CHARS, 16);
        String secret = randomString(SECRET_CHARS, 40);
        String token = randomString(SECRET_CHARS, 200);
        iam.registerSession(accessKeyId, secret, token, roleArn, expiration, scopeDownPolicy(paths, write),
                caller.accountId());
        ObjectNode credentials = mapper.createObjectNode();
        credentials.put("AccessKeyId", accessKeyId);
        credentials.put("SecretAccessKey", secret);
        credentials.put("SessionToken", token);
        credentials.put("Expiration", expiration.getEpochSecond());
        return credentials;
    }

    private String scopeDownPolicy(List<String> paths, boolean write) {
        ObjectNode policy = mapper.createObjectNode().put("Version", "2012-10-17");
        ArrayNode statements = policy.putArray("Statement");
        ObjectNode objects = statements.addObject().put("Effect", "Allow");
        ArrayNode objectActions = objects.putArray("Action").add("s3:GetObject").add("s3:GetObjectVersion");
        if (write) {
            objectActions.add("s3:PutObject").add("s3:DeleteObject");
        }
        ArrayNode objectResources = objects.putArray("Resource");
        ObjectNode buckets = statements.addObject().put("Effect", "Allow");
        buckets.putArray("Action").add("s3:ListBucket").add("s3:GetBucketLocation");
        ArrayNode bucketResources = buckets.putArray("Resource");
        Set<String> bucketArns = new LinkedHashSet<>();
        for (String path : paths) {
            objectResources.add("arn:aws:s3:::" + path + "/*");
            bucketArns.add("arn:aws:s3:::" + path.split("/", 2)[0]);
        }
        bucketArns.forEach(bucketResources::add);
        return policy.toString();
    }

    private String randomString(String alphabet, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // --- catalog and location helpers ---

    private List<PrincipalResourcePermissions> grants(String region, String catalog) {
        return storage.listPermissions(region, catalog, null, null, null, false, null, null);
    }

    private Optional<ResourceInfo> registeredLocation(String region, String path) {
        return storage.listResources(region, null, null, null).stream()
                .filter(info -> {
                    String registered = tryS3Path(info.getResourceArn());
                    return registered != null && covers(registered, path);
                })
                .max(Comparator.comparingInt(info -> info.getResourceArn().length()));
    }

    private String databaseLocation(String name) {
        try {
            return tryS3Path(glue.getDatabase(name).getLocationUri());
        } catch (AwsException e) {
            return null;
        }
    }

    private List<Table> tablesIn(String database) {
        try {
            return glue.getTables(database);
        } catch (AwsException e) {
            return List.of();
        }
    }

    private String tableLocation(String database, String name) {
        try {
            return tableLocation(glue.getTable(database, name));
        } catch (AwsException e) {
            return null;
        }
    }

    private String tableLocation(Table table) {
        return table.getStorageDescriptor() == null ? null : tryS3Path(table.getStorageDescriptor().getLocation());
    }

    /** {@code true} when {@code child} is {@code parent} or a key prefix below it. */
    static boolean covers(String parent, String child) {
        return child.equals(parent) || child.startsWith(parent + "/");
    }

    /** Normalizes {@code arn:aws:s3:::bucket/prefix} or {@code s3://bucket/prefix} to {@code bucket/prefix}. */
    static String tryS3Path(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String path;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("arn:")) {
            try {
                AwsArnUtils.Arn arn = AwsArnUtils.parse(value);
                if (!"s3".equals(arn.service())) {
                    return null;
                }
                path = arn.resource();
            } catch (IllegalArgumentException e) {
                return null;
            }
        } else if (lower.startsWith("s3://") || lower.startsWith("s3a://") || lower.startsWith("s3n://")) {
            path = value.substring(value.indexOf("://") + 3);
        } else {
            return null;
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isBlank() || path.startsWith("/") || path.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        return path;
    }

    private static String s3Path(String value, String field) {
        String path = tryS3Path(value);
        if (path == null) {
            throw invalid(field + " must be an Amazon S3 location");
        }
        return path;
    }

    private static List<String> requestedPermissions(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of("SELECT");
        }
        if (!node.isArray() || node.isEmpty()) {
            throw invalid("Permissions must be a nonempty list");
        }
        List<String> permissions = new ArrayList<>();
        for (JsonNode permission : node) {
            if (!permission.isTextual() || permission.asText().isBlank()) {
                throw invalid("Permissions must contain nonempty strings");
            }
            permissions.add(permission.asText());
        }
        return permissions;
    }

    private static Set<String> supportedPermissionTypes(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return Set.of(COLUMN_PERMISSION);
        }
        if (!node.isArray()) {
            throw invalid("SupportedPermissionTypes must be a list");
        }
        Set<String> types = new LinkedHashSet<>();
        for (JsonNode type : node) {
            if (!PERMISSION_TYPES.contains(type.asText())) {
                throw invalid("Unsupported permission type: " + type.asText());
            }
            types.add(type.asText());
        }
        return types;
    }

    private static void validateDuration(JsonNode request) {
        if (request.hasNonNull("DurationSeconds")) {
            JsonNode duration = request.get("DurationSeconds");
            if (!duration.canConvertToInt() || duration.asInt() < MIN_DURATION_SECONDS
                    || duration.asInt() > MAX_DURATION_SECONDS) {
                throw invalid("DurationSeconds must be between " + MIN_DURATION_SECONDS + " and "
                        + MAX_DURATION_SECONDS);
            }
        }
    }

    private String catalog(JsonNode request) {
        return request.hasNonNull("CatalogId") ? requiredText(request, "CatalogId") : regions.getAccountId();
    }

    private String sortKey(PrincipalResourcePermissions permission) {
        return mapper.valueToTree(permission).toString();
    }

    private int decodeOffset(JsonNode request, String scope, int size) {
        if (!request.hasNonNull("NextToken")) {
            return 0;
        }
        try {
            String token = new String(Base64.getUrlDecoder().decode(request.get("NextToken").asText()),
                    StandardCharsets.UTF_8);
            int split = token.lastIndexOf('\n');
            if (split < 0 || !token.substring(0, split).equals(scope)) {
                throw new IllegalArgumentException("Wrong token scope");
            }
            int offset = Integer.parseInt(token.substring(split + 1));
            if (offset < 0 || offset > size) {
                throw new IllegalArgumentException("Offset out of range");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid NextToken");
        }
    }

    private static String encodeOffset(String scope, int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((scope + "\n" + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static String requiredText(JsonNode request, String field) {
        JsonNode value = request.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid(field + " is required");
        }
        return value.asText();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }

    private record TableTarget(String catalog, String database, String name, Table table) {
        String qualifiedName() {
            return database + "." + name;
        }
    }

    private record Caller(String accountId, List<String> identifiers) {}

    private record Access(Set<String> full, Set<String> partial, boolean cellFiltered) {
        boolean fullGranted(List<String> requested) {
            return full.contains("ALL") || full.containsAll(requested);
        }

        boolean partialGranted(List<String> requested) {
            Set<String> combined = new LinkedHashSet<>(full);
            combined.addAll(partial);
            return !partial.isEmpty() && (combined.contains("ALL") || combined.containsAll(requested));
        }
    }
}
