package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.glue.GlueSchemaInference.FileSchema;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Crawler;
import io.github.hectorvent.floci.services.glue.model.CrawlerTargets;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.S3Target;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * Executes Glue crawls over S3 targets: lists the objects under each include path, samples them
 * with the built-in classifiers, and groups compatible schemas into tables and partitions the
 * way the Glue crawler does. Catalog writes are applied by {@link GlueService}.
 */
@ApplicationScoped
public class GlueCrawlerRunner {

    private static final Logger LOG = Logger.getLogger(GlueCrawlerRunner.class);
    // Glue classifiers read the start of each object; larger objects are sampled.
    static final int SAMPLE_BYTES = 1024 * 1024;

    private final S3Service s3Service;
    private final FlociDuckClient duckClient;
    private final RequestContext requestContext;
    private final ExecutorService executor;

    @Inject
    public GlueCrawlerRunner(S3Service s3Service, FlociDuckClient duckClient, RequestContext requestContext) {
        this(s3Service, duckClient, requestContext, Executors.newVirtualThreadPerTaskExecutor());
    }

    GlueCrawlerRunner(S3Service s3Service, FlociDuckClient duckClient, RequestContext requestContext,
                      ExecutorService executor) {
        this.s3Service = s3Service;
        this.duckClient = duckClient;
        this.requestContext = requestContext;
        this.executor = executor;
    }

    /** A table the crawl produced, with the partitions found beneath its location. */
    record CrawledTable(Table table, List<Partition> partitions) {}

    private record SampledFile(String key, long size, FileSchema schema) {}

    private static final class Folder {
        private final String prefix;
        private final String name;
        private final List<SampledFile> files = new ArrayList<>();
        private final Map<String, Folder> children = new LinkedHashMap<>();

        private Folder(String prefix, String name) {
            this.prefix = prefix;
            this.name = name;
        }
    }

    /** Runs {@code work} off the request thread as {@code accountId} in {@code region}. */
    void launch(String accountId, String region, Runnable work) {
        executor.execute(() -> RequestScopes.runAs(accountId, () -> {
            String previousRegion = requestContext == null ? null : requestContext.getRegion();
            if (requestContext != null) {
                requestContext.setRegion(region);
            }
            try {
                work.run();
            } finally {
                if (requestContext != null) {
                    requestContext.setRegion(previousRegion);
                }
            }
        }));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    List<CrawledTable> crawl(Crawler crawler, String accountId, BooleanSupplier cancelled) {
        CrawlerTargets targets = crawler.getTargets();
        if (targets == null || hasNonS3Targets(targets)) {
            throw new AwsException("InvalidInputException",
                    "Floci crawls S3 targets only; JDBC, DynamoDB, catalog, MongoDB, Delta, Hudi and Iceberg targets are not crawled.",
                    400);
        }
        List<CrawledTable> tables = new ArrayList<>();
        for (S3Target target : targets.getS3Targets()) {
            if (cancelled.getAsBoolean()) {
                return tables;
            }
            crawlTarget(crawler, target, accountId, cancelled, tables);
        }
        return tables;
    }

    private static boolean hasNonS3Targets(CrawlerTargets targets) {
        return notEmpty(targets.getJdbcTargets()) || notEmpty(targets.getDynamoDBTargets())
                || notEmpty(targets.getCatalogTargets()) || notEmpty(targets.getMongoDBTargets())
                || notEmpty(targets.getDeltaTargets()) || notEmpty(targets.getHudiTargets())
                || notEmpty(targets.getIcebergTargets()) || !notEmpty(targets.getS3Targets());
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    private void crawlTarget(Crawler crawler, S3Target target, String accountId, BooleanSupplier cancelled,
                             List<CrawledTable> tables) {
        String path = target.getPath();
        if (path == null || !path.startsWith("s3://") || path.length() <= "s3://".length()) {
            throw new AwsException("InvalidInputException", "Invalid S3 target path: " + path, 400);
        }
        String withoutScheme = path.substring("s3://".length());
        int slash = withoutScheme.indexOf('/');
        String bucket = slash < 0 ? withoutScheme : withoutScheme.substring(0, slash);
        String keyPrefix = slash < 0 ? "" : withoutScheme.substring(slash + 1);
        String folderPrefix = keyPrefix.isEmpty() || keyPrefix.endsWith("/") ? keyPrefix : keyPrefix + "/";
        String rootName = folderPrefix.isEmpty()
                ? bucket
                : folderPrefix.substring(folderPrefix.lastIndexOf('/', folderPrefix.length() - 2) + 1,
                        folderPrefix.length() - 1);

        List<PathMatcher> exclusions = new ArrayList<>();
        if (target.getExclusions() != null) {
            for (String exclusion : target.getExclusions()) {
                exclusions.add(FileSystems.getDefault().getPathMatcher("glob:" + exclusion));
            }
        }
        int sampleSize = target.getSampleSize() == null || target.getSampleSize() < 1
                ? Integer.MAX_VALUE : target.getSampleSize();

        List<S3Object> objects = new ArrayList<>(s3Service.listObjects(bucket, folderPrefix, null, Integer.MAX_VALUE));
        objects.sort(Comparator.comparing(S3Object::getKey));
        Folder root = new Folder(folderPrefix, rootName);
        Map<Folder, Integer> sampled = new LinkedHashMap<>();
        for (S3Object object : objects) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            String relative = object.getKey().substring(folderPrefix.length());
            if (relative.isEmpty() || relative.endsWith("/") || object.getSize() == 0 || excluded(relative, exclusions)) {
                continue;
            }
            String[] segments = relative.split("/");
            String fileName = segments[segments.length - 1];
            if (fileName.startsWith("_") || fileName.startsWith(".")) {
                continue;
            }
            Folder folder = root;
            StringBuilder prefix = new StringBuilder(folderPrefix);
            for (int i = 0; i < segments.length - 1; i++) {
                prefix.append(segments[i]).append('/');
                String childPrefix = prefix.toString();
                String childName = segments[i];
                folder = folder.children.computeIfAbsent(childName, ignored -> new Folder(childPrefix, childName));
            }
            int count = sampled.getOrDefault(folder, 0);
            FileSchema schema = null;
            if (count < sampleSize) {
                schema = classify(bucket, object, accountId);
                sampled.put(folder, count + 1);
            }
            folder.files.add(new SampledFile(object.getKey(), object.getSize(), schema));
        }
        plan(crawler, bucket, root, root.name, tables);
    }

    private static boolean excluded(String relative, List<PathMatcher> exclusions) {
        Path path = Path.of(relative);
        for (PathMatcher matcher : exclusions) {
            if (matcher.matches(path)) {
                return true;
            }
        }
        return false;
    }

    private FileSchema classify(String bucket, S3Object object, String accountId) {
        byte[] sample;
        boolean truncated;
        try (InputStream in = s3Service.openObjectStream(bucket, object.getKey(), null)) {
            sample = in.readNBytes(SAMPLE_BYTES);
            truncated = object.getSize() > sample.length;
        } catch (IOException e) {
            LOG.warnv("Glue crawler could not read s3://{0}/{1}: {2}", bucket, object.getKey(), e.getMessage());
            return null;
        }
        String classification = GlueSchemaInference.classificationFor(object.getKey(), object.getContentType(), sample);
        if (classification == null) {
            return null;
        }
        return switch (classification) {
            case GlueSchemaInference.PARQUET -> parquetSchema(bucket, object, accountId);
            case GlueSchemaInference.JSON -> GlueSchemaInference.inferJson(sample, truncated);
            default -> GlueSchemaInference.inferCsv(sample, truncated);
        };
    }

    private FileSchema parquetSchema(String bucket, S3Object object, String accountId) {
        String uri = "s3://" + bucket + "/" + object.getKey();
        try {
            List<Map<String, Object>> rows = duckClient.query(
                    "DESCRIBE SELECT * FROM read_parquet('" + uri.replace("'", "''") + "')", null, accountId);
            List<Map.Entry<String, String>> columns = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object name = row.get("column_name");
                Object type = row.get("column_type");
                if (name != null && type != null) {
                    columns.add(new AbstractMap.SimpleEntry<>(name.toString(), type.toString()));
                }
            }
            return columns.isEmpty() ? null : GlueSchemaInference.parquetSchema(columns, object.getSize());
        } catch (RuntimeException e) {
            LOG.warnv("Glue crawler could not read the Parquet schema of {0}: {1}", uri, e.getMessage());
            return null;
        }
    }

    private void plan(Crawler crawler, String bucket, Folder folder, String name, List<CrawledTable> tables) {
        List<SampledFile> subtree = subtreeFiles(folder);
        if (subtree.stream().noneMatch(file -> file.schema() != null)) {
            return;
        }
        boolean homogeneous = homogeneous(subtree);
        if (!folder.files.isEmpty() && folder.files.stream().anyMatch(file -> file.schema() != null)) {
            if (homogeneous) {
                tables.add(table(crawler, bucket, folder, name, subtree));
                return;
            }
            tables.add(table(crawler, bucket, folder, name, folder.files));
            for (Folder child : folder.children.values()) {
                plan(crawler, bucket, child, child.name, tables);
            }
            return;
        }
        List<Folder> populated = folder.children.values().stream()
                .filter(child -> subtreeFiles(child).stream().anyMatch(file -> file.schema() != null))
                .toList();
        if (populated.size() == 1 && !populated.getFirst().name.contains("=")) {
            plan(crawler, bucket, populated.getFirst(), populated.getFirst().name, tables);
            return;
        }
        if (homogeneous) {
            tables.add(table(crawler, bucket, folder, name, subtree));
            return;
        }
        for (Folder child : populated) {
            plan(crawler, bucket, child, child.name, tables);
        }
    }

    private static List<SampledFile> subtreeFiles(Folder folder) {
        List<SampledFile> files = new ArrayList<>(folder.files);
        for (Folder child : folder.children.values()) {
            files.addAll(subtreeFiles(child));
        }
        return files;
    }

    private static boolean homogeneous(List<SampledFile> files) {
        FileSchema first = null;
        for (SampledFile file : files) {
            if (file.schema() == null) {
                continue;
            }
            if (first == null) {
                first = file.schema();
            } else if (!GlueSchemaInference.similar(first, file.schema())) {
                return false;
            }
        }
        return true;
    }

    private CrawledTable table(Crawler crawler, String bucket, Folder folder, String name, List<SampledFile> files) {
        String classification = null;
        List<Column> columns = new ArrayList<>();
        Map<String, String> serdeParameters = new LinkedHashMap<>();
        Map<String, String> formatParameters = new LinkedHashMap<>();
        long records = 0;
        long sampledBytes = 0;
        long totalBytes = 0;
        long objectCount = 0;
        int depth = Integer.MAX_VALUE;
        for (SampledFile file : files) {
            totalBytes += file.size();
            objectCount++;
            depth = Math.min(depth, relativeDirectories(folder, file.key()).size());
            FileSchema schema = file.schema();
            if (schema == null || (classification != null && !classification.equals(schema.classification()))) {
                continue;
            }
            if (classification == null) {
                classification = schema.classification();
                serdeParameters.putAll(schema.serdeParameters());
                formatParameters.putAll(schema.tableParameters());
                columns = schema.columns();
            } else {
                columns = GlueSchemaInference.mergeColumns(columns, schema.columns());
            }
            records += schema.records();
            sampledBytes += schema.sampledBytes();
        }
        if (GlueSchemaInference.JSON.equals(classification)) {
            serdeParameters.put("paths", String.join(",", columns.stream().map(Column::getName).sorted().toList()));
        }

        List<Column> partitionKeys = new ArrayList<>();
        List<String> firstDirectories = relativeDirectories(folder, files.getFirst().key());
        for (int i = 0; i < depth; i++) {
            String segment = firstDirectories.get(i);
            int equals = segment.indexOf('=');
            String key = equals > 0 ? segment.substring(0, equals).toLowerCase(Locale.ROOT) : "partition_" + i;
            partitionKeys.add(new Column(key, "string"));
        }

        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("classification", classification);
        parameters.put("compressionType", "none");
        parameters.put("typeOfData", "file");
        parameters.put("CrawlerSchemaSerializerVersion", "1.0");
        parameters.put("CrawlerSchemaDeserializerVersion", "1.0");
        parameters.put("UPDATED_BY_CRAWLER", crawler.getName());
        parameters.put("objectCount", Long.toString(objectCount));
        parameters.put("sizeKey", Long.toString(totalBytes));
        if (records > 0) {
            parameters.put("recordCount", Long.toString(records));
            parameters.put("averageRecordSize", Long.toString(Math.max(1, sampledBytes / records)));
        }
        parameters.putAll(formatParameters);

        String location = "s3://" + bucket + "/" + folder.prefix;
        StorageDescriptor descriptor = descriptor(columns, location, classification, serdeParameters, parameters);

        Table table = new Table();
        table.setName(GlueSchemaInference.tableName(crawler.getTablePrefix(), name));
        table.setOwner("owner");
        table.setTableType("EXTERNAL_TABLE");
        table.setPartitionKeys(partitionKeys);
        table.setStorageDescriptor(descriptor);
        table.setParameters(parameters);

        Map<List<String>, String> partitionLocations = new LinkedHashMap<>();
        if (depth > 0) {
            for (SampledFile file : files) {
                List<String> directories = relativeDirectories(folder, file.key());
                List<String> values = new ArrayList<>();
                for (int i = 0; i < depth; i++) {
                    String segment = directories.get(i);
                    int equals = segment.indexOf('=');
                    values.add(equals > 0 ? segment.substring(equals + 1) : segment);
                }
                partitionLocations.putIfAbsent(values,
                        location + String.join("/", directories.subList(0, depth)) + "/");
            }
        }
        List<Partition> partitions = new ArrayList<>();
        for (Map.Entry<List<String>, String> entry : partitionLocations.entrySet()) {
            Partition partition = new Partition();
            partition.setValues(entry.getKey());
            partition.setStorageDescriptor(descriptor(descriptor.getColumns(), entry.getValue(), classification,
                    serdeParameters, parameters));
            partition.setParameters(new LinkedHashMap<>(parameters));
            partitions.add(partition);
        }
        return new CrawledTable(table, partitions);
    }

    private static StorageDescriptor descriptor(List<Column> columns, String location, String classification,
                                                Map<String, String> serdeParameters, Map<String, String> parameters) {
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setColumns(new ArrayList<>(columns));
        descriptor.setLocation(location);
        descriptor.setCompressed(false);
        descriptor.setNumberOfBuckets(-1);
        descriptor.setParameters(new LinkedHashMap<>(parameters));
        GlueSchemaInference.applyFormat(descriptor, classification, serdeParameters);
        return descriptor;
    }

    private static List<String> relativeDirectories(Folder folder, String key) {
        String relative = key.substring(folder.prefix.length());
        String[] segments = relative.split("/");
        return Arrays.asList(segments).subList(0, segments.length - 1);
    }
}
