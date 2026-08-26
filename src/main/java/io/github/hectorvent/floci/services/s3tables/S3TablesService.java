package io.github.hectorvent.floci.services.s3tables;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.s3tables.model.Table;
import io.github.hectorvent.floci.services.s3tables.model.TableBucket;
import io.github.hectorvent.floci.services.s3tables.model.TableNamespace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class S3TablesService {
    private static final Logger LOG = Logger.getLogger(S3TablesService.class);
    static final String SERVICE = "s3tables";

    private final StorageBackend<String, TableBucket> store;
    private final RegionResolver regionResolver;

    @Inject
    public S3TablesService(StorageFactory factory, RegionResolver regionResolver) {
        this.store = factory.create("s3tables", "s3tables.json",
                new TypeReference<Map<String, TableBucket>>() {});
        this.regionResolver = regionResolver;
    }

    public TableBucket createTableBucket(String name, Object encryptionConfiguration, String region) {
        requireName(name, "name");
        String key = storageKey(region, name);
        if (store.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "A table bucket already exists with the name " + name + ".", 409);
        }
        String now = now();
        String accountId = regionResolver.getAccountId();
        String arn = buildBucketArn(region, name);
        TableBucket bucket = new TableBucket(name, arn, accountId, now, UUID.randomUUID().toString());
        bucket.setEncryptionConfiguration(encryptionConfiguration);
        store.put(key, bucket);
        LOG.infov("Created table bucket {0} in {1}", name, region);
        return bucket;
    }

    public TableBucket getTableBucket(String tableBucketArn, String region) {
        BucketRef ref = resolveBucket(tableBucketArn, region);
        return requireBucket(ref);
    }

    public List<TableBucket> listTableBuckets(String region, String prefix) {
        return store.scan(k -> k.startsWith(region + "::")).stream()
                .filter(b -> prefix == null || prefix.isBlank() || b.getName().startsWith(prefix))
                .sorted(Comparator.comparing(TableBucket::getName))
                .toList();
    }

    public void deleteTableBucket(String tableBucketArn, String region) {
        BucketRef ref = resolveBucket(tableBucketArn, region);
        TableBucket bucket = requireBucket(ref);
        if (!bucket.getNamespaces().isEmpty() || !bucket.getTables().isEmpty()) {
            // Live AWS returns BadRequestException (400), not ConflictException,
            // when a table bucket still contains namespaces or tables.
            throw new AwsException("BadRequestException",
                    "The specified table bucket is not empty.", 400);
        }
        store.delete(ref.key());
        LOG.infov("Deleted table bucket {0}", bucket.getName());
    }

    public TableNamespace createNamespace(String tableBucketArn, List<String> namespace, String region) {
        String ns = requireSingleNamespace(namespace);
        BucketRef ref = resolveBucket(tableBucketArn, region);
        TableBucket bucket = requireBucket(ref);
        if (bucket.getNamespaces().containsKey(ns)) {
            throw new AwsException("ConflictException",
                    "The namespace " + ns + " already exists.", 409);
        }
        String accountId = regionResolver.getAccountId();
        TableNamespace created = new TableNamespace(
                ns, UUID.randomUUID().toString(), now(), accountId, accountId);
        bucket.getNamespaces().put(ns, created);
        store.put(ref.key(), bucket);
        LOG.infov("Created namespace {0} in {1}", ns, bucket.getName());
        return created;
    }

    public TableNamespace getNamespace(String tableBucketArn, String namespace, String region) {
        BucketRef ref = resolveBucket(tableBucketArn, region);
        TableBucket bucket = requireBucket(ref);
        TableNamespace ns = bucket.getNamespaces().get(namespace);
        if (ns == null) {
            throw new AwsException("NotFoundException",
                    "The specified namespace does not exist.", 404);
        }
        return ns;
    }

    public List<TableNamespace> listNamespaces(String tableBucketArn, String region) {
        BucketRef ref = resolveBucket(tableBucketArn, region);
        TableBucket bucket = requireBucket(ref);
        return bucket.getNamespaces().values().stream()
                .sorted(Comparator.comparing(TableNamespace::getName))
                .toList();
    }

    public void deleteNamespace(String tableBucketArn, String namespace, String region) {
        BucketRef ref = resolveBucket(tableBucketArn, region);
        TableBucket bucket = requireBucket(ref);
        if (!bucket.getNamespaces().containsKey(namespace)) {
            throw new AwsException("NotFoundException",
                    "The specified namespace does not exist.", 404);
        }
        boolean hasTables = bucket.getTables().values().stream()
                .anyMatch(t -> namespace.equals(t.getNamespace()));
        if (hasTables) {
            throw new AwsException("ConflictException",
                    "The namespace " + namespace + " is not empty.", 409);
        }
        bucket.getNamespaces().remove(namespace);
        store.put(ref.key(), bucket);
        LOG.infov("Deleted namespace {0} from {1}", namespace, bucket.getName());
    }

    public Table createTable(String tableBucketArn, String namespace, String name, String format,
                             Object metadata, String region) {
        requireName(name, "name");
        requireName(namespace, "namespace");
        BucketRef ref = resolveBucket(tableBucketArn, region);
        TableBucket bucket = requireBucket(ref);
        if (!bucket.getNamespaces().containsKey(namespace)) {
            throw new AwsException("NotFoundException",
                    "The specified namespace does not exist.", 404);
        }
        String tableKey = TableBucket.tableKey(namespace, name);
        if (bucket.getTables().containsKey(tableKey)) {
            throw new AwsException("ConflictException",
                    "The table " + name + " already exists.", 409);
        }
        String resolvedFormat = format == null || format.isBlank() ? "ICEBERG" : format;
        if (!"ICEBERG".equals(resolvedFormat)) {
            throw new AwsException("BadRequestException",
                    "Unsupported table format: " + resolvedFormat + ".", 400);
        }
        String now = now();
        String accountId = regionResolver.getAccountId();
        String tableId = UUID.randomUUID().toString();
        Table table = new Table();
        table.setName(name);
        table.setNamespace(namespace);
        table.setTableArn(bucket.getArn() + "/table/" + tableId);
        table.setVersionToken(UUID.randomUUID().toString());
        table.setWarehouseLocation("s3://" + bucket.getName() + "--table-s3/" + namespace + "/" + name);
        table.setFormat(resolvedFormat);
        table.setType("customer");
        table.setCreatedAt(now);
        table.setCreatedBy(accountId);
        table.setModifiedAt(now);
        table.setModifiedBy(accountId);
        table.setOwnerAccountId(accountId);
        table.setMetadata(metadata);
        bucket.getTables().put(tableKey, table);
        store.put(ref.key(), bucket);
        LOG.infov("Created table {0} in {1}/{2}", name, bucket.getName(), namespace);
        return table;
    }

    public Table getTable(String tableBucketArn, String namespace, String name, String tableArn, String region) {
        if (tableArn != null && !tableArn.isBlank()) {
            return getTableByArn(tableArn, region);
        }
        requireName(name, "name");
        requireName(namespace, "namespace");
        BucketRef ref = resolveBucket(tableBucketArn, region);
        TableBucket bucket = requireBucket(ref);
        Table table = bucket.getTables().get(TableBucket.tableKey(namespace, name));
        if (table == null) {
            throw new AwsException("NotFoundException", "The specified table does not exist.", 404);
        }
        return table;
    }

    public List<Table> listTables(String tableBucketArn, String namespace, String region) {
        BucketRef ref = resolveBucket(tableBucketArn, region);
        TableBucket bucket = requireBucket(ref);
        return bucket.getTables().values().stream()
                .filter(t -> namespace == null || namespace.isBlank() || namespace.equals(t.getNamespace()))
                .sorted(Comparator.comparing(Table::getNamespace).thenComparing(Table::getName))
                .toList();
    }

    public void deleteTable(String tableBucketArn, String namespace, String name, String region) {
        requireName(name, "name");
        requireName(namespace, "namespace");
        BucketRef ref = resolveBucket(tableBucketArn, region);
        TableBucket bucket = requireBucket(ref);
        String tableKey = TableBucket.tableKey(namespace, name);
        if (!bucket.getTables().containsKey(tableKey)) {
            throw new AwsException("NotFoundException", "The specified table does not exist.", 404);
        }
        bucket.getTables().remove(tableKey);
        store.put(ref.key(), bucket);
        LOG.infov("Deleted table {0} from {1}/{2}", name, bucket.getName(), namespace);
    }

    public Table getTableMetadataLocation(String tableBucketArn, String namespace, String name, String region) {
        return getTable(tableBucketArn, namespace, name, null, region);
    }

    public Table updateTableMetadataLocation(String tableBucketArn, String namespace, String name,
                                             String versionToken, String metadataLocation, String region) {
        if (versionToken == null || versionToken.isBlank()) {
            throw new AwsException("BadRequestException", "versionToken is required.", 400);
        }
        if (metadataLocation == null || metadataLocation.isBlank()) {
            throw new AwsException("BadRequestException", "metadataLocation is required.", 400);
        }
        BucketRef ref = resolveBucket(tableBucketArn, region);
        TableBucket bucket = requireBucket(ref);
        Table table = bucket.getTables().get(TableBucket.tableKey(namespace, name));
        if (table == null) {
            throw new AwsException("NotFoundException", "The specified table does not exist.", 404);
        }
        if (!versionToken.equals(table.getVersionToken())) {
            throw new AwsException("ConflictException",
                    "The provided versionToken does not match the current table version.", 409);
        }
        String warehouse = table.getWarehouseLocation();
        if (warehouse == null || !metadataLocation.startsWith(warehouse)) {
            throw new AwsException("BadRequestException",
                    "metadataLocation must begin with the table warehouse location.", 400);
        }
        String lower = metadataLocation.toLowerCase();
        if (!lower.endsWith(".metadata.json") && !lower.endsWith(".metadata.json.gz")) {
            throw new AwsException("BadRequestException",
                    "metadataLocation must end with .metadata.json or .metadata.json.gz.", 400);
        }
        table.setMetadataLocation(metadataLocation);
        table.setVersionToken(UUID.randomUUID().toString());
        table.setModifiedAt(now());
        table.setModifiedBy(regionResolver.getAccountId());
        store.put(ref.key(), bucket);
        return table;
    }

    record BucketPath(String bucketArn, String bucketName, List<String> extra) {}

    BucketPath parseBucketPath(String rest) {
        if (rest == null || rest.isBlank()) {
            throw new AwsException("BadRequestException", "tableBucketARN is required.", 400);
        }
        String decoded = decodeArn(rest);
        int idx = decoded.indexOf(":bucket/");
        if (idx < 0) {
            throw new AwsException("BadRequestException", "Invalid table bucket ARN.", 400);
        }
        String after = decoded.substring(idx + ":bucket/".length());
        int slash = after.indexOf('/');
        String bucketName;
        List<String> extra;
        if (slash < 0) {
            bucketName = after;
            extra = List.of();
        } else {
            bucketName = after.substring(0, slash);
            extra = splitExtra(after.substring(slash + 1));
        }
        if (bucketName.isBlank()) {
            throw new AwsException("BadRequestException", "Invalid table bucket ARN.", 400);
        }
        String bucketArn = decoded.substring(0, idx + ":bucket/".length()) + bucketName;
        return new BucketPath(bucketArn, bucketName, extra);
    }

    private Table getTableByArn(String tableArn, String region) {
        BucketPath path = parseBucketPath(tableArn);
        if (path.extra().size() < 2 || !"table".equals(path.extra().get(0))) {
            throw new AwsException("BadRequestException", "Invalid table ARN.", 400);
        }
        String tableIdSuffix = path.extra().get(1);
        BucketRef ref = new BucketRef(storageKey(regionFromArn(path.bucketArn(), region), path.bucketName()),
                path.bucketName());
        TableBucket bucket = requireBucket(ref);
        return bucket.getTables().values().stream()
                .filter(t -> t.getTableArn() != null && t.getTableArn().endsWith("/table/" + tableIdSuffix))
                .findFirst()
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "The specified table does not exist.", 404));
    }

    private BucketRef resolveBucket(String tableBucketArn, String region) {
        BucketPath path = parseBucketPath(tableBucketArn);
        String resolvedRegion = regionFromArn(path.bucketArn(), region);
        return new BucketRef(storageKey(resolvedRegion, path.bucketName()), path.bucketName());
    }

    private TableBucket requireBucket(BucketRef ref) {
        return store.get(ref.key()).orElseThrow(() ->
                new AwsException("NotFoundException",
                        "The specified table bucket does not exist.", 404));
    }

    private String buildBucketArn(String region, String bucketName) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(),
                "bucket/" + bucketName).toString();
    }

    private static String storageKey(String region, String bucketName) {
        return region + "::" + bucketName;
    }

    private static String regionFromArn(String arn, String fallback) {
        try {
            String region = AwsArnUtils.parse(arn).region();
            return region == null || region.isBlank() ? fallback : region;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    static String decodeArn(String arn) {
        if (arn == null || arn.isEmpty()) {
            return arn;
        }
        try {
            String decoded = arn;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return arn;
        }
    }

    private static List<String> splitExtra(String extra) {
        if (extra == null || extra.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : extra.split("/")) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return List.copyOf(parts);
    }

    private static String requireSingleNamespace(List<String> namespace) {
        if (namespace == null || namespace.isEmpty() || namespace.get(0) == null || namespace.get(0).isBlank()) {
            throw new AwsException("BadRequestException", "namespace is required.", 400);
        }
        return namespace.get(0);
    }

    private static void requireName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AwsException("BadRequestException", field + " is required.", 400);
        }
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    private record BucketRef(String key, String name) {}
}
