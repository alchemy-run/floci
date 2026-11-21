package io.floci.aws.services.kendra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.services.scheduler.SchedulerExpressionParser;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.floci.aws.services.kendra.KendraStore.DATA_SOURCE;
import static io.floci.aws.services.kendra.KendraStore.SYNC_JOB;
import static io.floci.aws.services.kendra.KendraStore.meta;
import static io.floci.aws.services.kendra.KendraStore.notFound;
import static io.floci.aws.services.kendra.KendraStore.text;
import static io.floci.aws.services.kendra.KendraStore.validation;
import static io.floci.aws.services.kendra.KendraStore.view;

/**
 * Data sources and their sync jobs. A sync runs in the background: {@code SYNCING} while it reads the source,
 * {@code SYNCING_INDEXING} while it puts what it read, then {@code SUCCEEDED} (or {@code FAILED} with the reason,
 * {@code ABORTED} when stopped), with counts of the documents it scanned, added, modified, deleted and failed. Each
 * sync compares the source against the previous one, so unchanged documents aren't put again and documents gone from
 * the source are deleted from the index.
 *
 * <p>Floci syncs S3 buckets (inclusion prefixes and glob patterns, metadata files, and an access control list file),
 * websites (seed URLs or sitemaps, followed to the crawl depth within the crawl mode's hosts, with basic
 * authentication from Secrets Manager) and MySQL or PostgreSQL databases (the configured table's id, body, title,
 * field-mapped and allowed-groups columns, with change detection over the change-detecting columns). Software
 * connectors (SharePoint, Salesforce, Confluence and the rest) need the outside system; their syncs fail as they do
 * when Kendra can't reach it. A {@code CUSTOM} data source's sync stays {@code SYNCING} until it's stopped, counting
 * the documents BatchPutDocument and BatchDeleteDocument put against it. Sources with a {@code Schedule} sync when
 * their cron expression comes due.
 */
@ApplicationScoped
public class KendraSources {

    private static final Logger LOG = Logger.getLogger(KendraSources.class);
    static final Map<String, String> CONFIGURATIONS = Map.ofEntries(
            Map.entry("S3", "S3Configuration"), Map.entry("SHAREPOINT", "SharePointConfiguration"),
            Map.entry("DATABASE", "DatabaseConfiguration"), Map.entry("SALESFORCE", "SalesforceConfiguration"),
            Map.entry("ONEDRIVE", "OneDriveConfiguration"), Map.entry("SERVICENOW", "ServiceNowConfiguration"),
            Map.entry("CONFLUENCE", "ConfluenceConfiguration"), Map.entry("GOOGLEDRIVE", "GoogleDriveConfiguration"),
            Map.entry("WEBCRAWLER", "WebCrawlerConfiguration"), Map.entry("WORKDOCS", "WorkDocsConfiguration"),
            Map.entry("FSX", "FsxConfiguration"), Map.entry("SLACK", "SlackConfiguration"),
            Map.entry("BOX", "BoxConfiguration"), Map.entry("QUIP", "QuipConfiguration"),
            Map.entry("JIRA", "JiraConfiguration"), Map.entry("GITHUB", "GitHubConfiguration"),
            Map.entry("ALFRESCO", "AlfrescoConfiguration"), Map.entry("TEMPLATE", "TemplateConfiguration"));
    static final Set<String> RUNNING = Set.of("SYNCING", "SYNCING_INDEXING", "STOPPING");

    private final KendraIndexes indexes;
    private final KendraStore store;
    private final KendraDocuments documents;
    private final S3Service s3;
    private final SecretsManagerService secrets;
    private final ExecutorService executor = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "kendra-sync");
        thread.setDaemon(true);
        return thread;
    });
    /** Sync jobs running in this process, by account, Region and job path; the value says whether to stop. */
    private final Map<String, Boolean> running = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Inject
    public KendraSources(KendraIndexes indexes, KendraStore store, KendraDocuments documents, S3Service s3,
                         SecretsManagerService secrets) {
        this.indexes = indexes;
        this.store = store;
        this.documents = documents;
        this.s3 = s3;
        this.secrets = secrets;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private ObjectMapper mapper() {
        return store.mapper();
    }

    // ---------------------------------------------------------------- data sources

    public ObjectNode createDataSource(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        ObjectNode index = indexes.activeIndex(account, region, indexId);
        ObjectNode earlier = store.repeated(account, region, "CreateDataSource", in);
        if (earlier != null) {
            return earlier;
        }
        String type = text(in, "Type");
        check(type, in);
        List<ObjectNode> existing = indexes.all(account, region, DATA_SOURCE, indexId + "/");
        String name = text(in, "Name");
        if (existing.stream().anyMatch(d -> name.equals(d.path("Name").asText()))) {
            throw KendraStore.conflict("A data source named " + name + " already exists in the index.");
        }
        int limit = "DEVELOPER_EDITION".equals(index.path("Edition").asText()) ? 5 : 50;
        if (existing.size() >= limit) {
            throw KendraStore.quota("The index can have at most " + limit + " data sources.");
        }
        String id = KendraStore.uuid();
        ObjectNode source = store.record();
        double now = KendraStore.epoch();
        source.put("Id", id).put("IndexId", indexId).put("Name", name).put("Type", type);
        copy(in, source, "Configuration", "VpcConfiguration", "Description", "Schedule", "RoleArn",
                "CustomDocumentEnrichmentConfiguration");
        source.put("CreatedAt", now).put("UpdatedAt", now)
                .put("LanguageCode", in.hasNonNull("LanguageCode") ? text(in, "LanguageCode") : "en");
        meta(source).set("tags", KendraStore.tagsOf(mapper(), in.get("Tags")));
        meta(source).putObject("seen");
        if (source.hasNonNull("Schedule")) {
            meta(source).put("scheduledFrom", System.currentTimeMillis());
        }
        indexes.pending(source, "CREATING", "ACTIVE");
        indexes.save(account, region, DATA_SOURCE, indexId + "/" + id, source);
        ObjectNode response = mapper().createObjectNode().put("Id", id);
        store.remember(account, region, "CreateDataSource", in, response);
        return response;
    }

    private void check(String type, JsonNode in) {
        JsonNode configuration = in.get("Configuration");
        if ("CUSTOM".equals(type)) {
            if (configuration != null && !configuration.isEmpty()) {
                throw validation("A CUSTOM data source has no Configuration.");
            }
            if (in.hasNonNull("RoleArn")) {
                throw validation("A CUSTOM data source has no RoleArn.");
            }
            if (in.hasNonNull("Schedule")) {
                throw validation("A CUSTOM data source has no Schedule.");
            }
            return;
        }
        String wanted = CONFIGURATIONS.get(type);
        if (configuration == null || !configuration.has(wanted)) {
            throw validation("A " + type + " data source needs Configuration." + wanted + ".");
        }
        configuration.fieldNames().forEachRemaining(field -> {
            if (!field.equals(wanted)) {
                throw validation("A " + type + " data source can't have Configuration." + field + ".");
            }
        });
        if (!in.hasNonNull("RoleArn")) {
            throw validation("A " + type + " data source needs a RoleArn.");
        }
        indexes.role(text(in, "RoleArn"));
        schedule(text(in, "Schedule"));
        if ("WEBCRAWLER".equals(type)) {
            JsonNode urls = configuration.get(wanted).path("Urls");
            if (!urls.has("SeedUrlConfiguration") && !urls.has("SiteMapsConfiguration")) {
                throw validation("A web crawler needs seed URLs or sitemaps.");
            }
        }
    }

    private static void schedule(String schedule) {
        if (schedule == null || schedule.isEmpty()) {
            return;
        }
        try {
            SchedulerExpressionParser.nextCronFire(schedule, Instant.now(), "UTC");
        } catch (RuntimeException e) {
            throw validation("The schedule " + schedule + " is not a valid cron expression.");
        }
    }

    private static void copy(JsonNode in, ObjectNode out, String... names) {
        for (String name : names) {
            if (in.hasNonNull(name)) {
                out.set(name, in.get(name).deepCopy());
            }
        }
    }

    ObjectNode source(String account, String region, String indexId, String id) {
        indexes.index(account, region, indexId);
        ObjectNode source = indexes.find(account, region, DATA_SOURCE, indexId + "/" + id);
        if (source == null) {
            throw notFound("Data source " + id + " doesn't exist.");
        }
        return source;
    }

    public ObjectNode describeDataSource(JsonNode in, String region) {
        return view(source(store.account(), region, text(in, "IndexId"), text(in, "Id")));
    }

    public ObjectNode listDataSources(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.index(account, region, indexId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode source : indexes.all(account, region, DATA_SOURCE, indexId + "/")) {
            ObjectNode item = mapper().createObjectNode();
            for (String field : List.of("Name", "Id", "Type", "CreatedAt", "UpdatedAt", "Status", "LanguageCode")) {
                item.set(field, source.get(field));
            }
            items.add(item);
        }
        ObjectNode out = mapper().createObjectNode();
        KendraStore.page(items, in, out, "SummaryItems", 100);
        return out;
    }

    public ObjectNode updateDataSource(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        String id = text(in, "Id");
        ObjectNode source = source(account, region, indexId, id);
        String status = source.path("Status").asText();
        if (!"ACTIVE".equals(status) && !"FAILED".equals(status)) {
            throw KendraStore.conflict("Data source " + id + " is " + status + ".");
        }
        ObjectNode merged = source.deepCopy();
        copy(in, merged, "Name", "Configuration", "VpcConfiguration", "Description", "Schedule", "RoleArn",
                "LanguageCode", "CustomDocumentEnrichmentConfiguration");
        if (in.has("Schedule") && in.get("Schedule").asText("").isEmpty()) {
            merged.remove("Schedule");
        }
        check(merged.path("Type").asText(), merged);
        merged.put("UpdatedAt", KendraStore.epoch());
        if (merged.hasNonNull("Schedule") && !merged.path("Schedule").equals(source.path("Schedule"))) {
            meta(merged).put("scheduledFrom", System.currentTimeMillis());
        }
        indexes.pending(merged, "UPDATING", "ACTIVE");
        store.put(account, region, DATA_SOURCE, indexId + "/" + id, merged);
        return mapper().createObjectNode();
    }

    public ObjectNode deleteDataSource(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        String id = text(in, "Id");
        ObjectNode source = source(account, region, indexId, id);
        if ("DELETING".equals(source.path("Status").asText())) {
            return mapper().createObjectNode();
        }
        running.replaceAll((key, stop) -> key.startsWith(account + "/" + region + "/" + indexId + "/" + id + "/")
                ? Boolean.TRUE : stop);
        indexes.pending(source, "DELETING", "DELETED");
        store.put(account, region, DATA_SOURCE, indexId + "/" + id, source);
        store.deleteAll(account, region, SYNC_JOB, indexId + "/" + id + "/");
        documents.deleteBySource(account, region, indexId, id);
        return mapper().createObjectNode();
    }

    // ---------------------------------------------------------------- sync jobs

    public ObjectNode startDataSourceSyncJob(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        String id = text(in, "Id");
        ObjectNode source = source(account, region, indexId, id);
        indexes.activeIndex(account, region, indexId);
        return start(account, region, source);
    }

    private synchronized ObjectNode start(String account, String region, ObjectNode source) {
        String indexId = source.path("IndexId").asText();
        String id = source.path("Id").asText();
        if (!"ACTIVE".equals(source.path("Status").asText())) {
            throw KendraStore.conflict("Data source " + id + " is " + source.path("Status").asText()
                    + "; it can sync once it is ACTIVE.");
        }
        for (ObjectNode job : store.list(account, region, SYNC_JOB, indexId + "/" + id + "/")) {
            if (RUNNING.contains(job.path("Status").asText())) {
                throw KendraStore.inUse("Data source " + id + " is already syncing (" + job.path("ExecutionId")
                        .asText() + ").");
            }
        }
        String executionId = KendraStore.uuid();
        ObjectNode job = store.record();
        job.put("ExecutionId", executionId).put("StartTime", KendraStore.epoch()).put("Status", "SYNCING");
        ObjectNode metrics = job.putObject("Metrics");
        for (String m : List.of("DocumentsAdded", "DocumentsModified", "DocumentsDeleted", "DocumentsFailed",
                "DocumentsScanned")) {
            metrics.put(m, "0");
        }
        String path = indexId + "/" + id + "/" + executionId;
        String key = account + "/" + region + "/" + path;
        boolean connector = !"CUSTOM".equals(source.path("Type").asText());
        if (connector) {
            running.put(key, false);
        }
        indexes.save(account, region, SYNC_JOB, path, job);
        if (connector) {
            executor.submit(() -> RequestScopes.runAs(account, region, () -> run(account, region, indexId, id,
                    executionId, key)));
        }
        return mapper().createObjectNode().put("ExecutionId", executionId);
    }

    public ObjectNode stopDataSourceSyncJob(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        String id = text(in, "Id");
        ObjectNode source = source(account, region, indexId, id);
        for (ObjectNode job : store.list(account, region, SYNC_JOB, indexId + "/" + id + "/")) {
            if (!RUNNING.contains(job.path("Status").asText())) {
                continue;
            }
            String path = meta(job).path("path").asText();
            if ("CUSTOM".equals(source.path("Type").asText())) {
                job.put("Status", "SUCCEEDED").put("EndTime", KendraStore.epoch());
            } else {
                job.put("Status", "STOPPING");
                running.computeIfPresent(account + "/" + region + "/" + path, (k, v) -> Boolean.TRUE);
                if (!running.containsKey(account + "/" + region + "/" + path)) {
                    job.put("Status", "ABORTED").put("EndTime", KendraStore.epoch());
                }
            }
            store.put(account, region, SYNC_JOB, path, job);
            return mapper().createObjectNode();
        }
        throw validation("Data source " + id + " has no sync job running.");
    }

    public ObjectNode listDataSourceSyncJobs(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        String id = text(in, "Id");
        source(account, region, indexId, id);
        String status = text(in, "StatusFilter");
        JsonNode range = in.get("StartTimeFilter");
        List<ObjectNode> jobs = new ArrayList<>();
        for (ObjectNode job : store.list(account, region, SYNC_JOB, indexId + "/" + id + "/")) {
            double start = job.path("StartTime").asDouble();
            if (status != null && !status.equals(job.path("Status").asText())) {
                continue;
            }
            if (range != null && (range.has("StartTime") && start < range.get("StartTime").asDouble()
                    || range.has("EndTime") && start > range.get("EndTime").asDouble())) {
                continue;
            }
            jobs.add(view(job));
        }
        Collections.reverse(jobs);
        ObjectNode out = mapper().createObjectNode();
        KendraStore.page(jobs, in, out, "History", 10);
        return out;
    }

    /** A document a sync read from its source: its id, the request it's put with, and its version. */
    record Found(String id, ObjectNode request, String version) {
    }

    private boolean stopping(String key) {
        return Boolean.TRUE.equals(running.get(key));
    }

    private void run(String account, String region, String indexId, String sourceId, String executionId,
                     String key) {
        String path = indexId + "/" + sourceId + "/" + executionId;
        try {
            ObjectNode source = indexes.find(account, region, DATA_SOURCE, indexId + "/" + sourceId);
            if (source == null) {
                return;
            }
            List<Found> found = switch (source.path("Type").asText()) {
                case "S3" -> crawlS3(region, source, key);
                case "WEBCRAWLER" -> crawlWeb(region, source, key);
                case "DATABASE" -> crawlDatabase(region, source, key);
                default -> throw new SyncFailure("ConnectionFailed", "Amazon Kendra couldn't connect to the "
                        + source.path("Type").asText() + " data source: Floci can't reach the external system.");
            };
            update(account, region, path, job -> {
                job.put("Status", "SYNCING_INDEXING");
                job.with("Metrics").put("DocumentsScanned", String.valueOf(found.size()));
            });
            ObjectNode index = indexes.index(account, region, indexId);
            ObjectNode seen = meta(source).has("seen") ? (ObjectNode) meta(source).get("seen")
                    : mapper().createObjectNode();
            ObjectNode now = mapper().createObjectNode();
            long added = 0;
            long modified = 0;
            long failed = 0;
            for (Found f : found) {
                if (stopping(key)) {
                    break;
                }
                now.put(f.id(), f.version());
                if (f.version().equals(seen.path(f.id()).asText(null))
                        && store.document(account, region, indexId, f.id()).isPresent()) {
                    continue;
                }
                try {
                    documents.ingest(account, region, index, f.request(),
                            source.get("CustomDocumentEnrichmentConfiguration"), sourceId);
                    if (seen.has(f.id())) {
                        modified++;
                    } else {
                        added++;
                    }
                } catch (AwsException e) {
                    failed++;
                    now.remove(f.id());
                    LOG.debugf("Kendra sync %s: %s failed: %s", executionId, f.id(), e.getMessage());
                }
            }
            long deleted = 0;
            if (!stopping(key)) {
                for (String gone : documents.idsBySource(account, region, indexId, sourceId)) {
                    if (!now.has(gone)) {
                        store.deleteDocument(account, region, indexId, gone);
                        deleted++;
                    }
                }
            }
            ObjectNode latest = indexes.find(account, region, DATA_SOURCE, indexId + "/" + sourceId);
            if (latest != null) {
                if (stopping(key)) {
                    seen.setAll(now);
                    meta(latest).set("seen", seen);
                } else {
                    meta(latest).set("seen", now);
                }
                store.put(account, region, DATA_SOURCE, indexId + "/" + sourceId, latest);
            }
            long a = added;
            long m = modified;
            long d = deleted;
            long x = failed;
            boolean aborted = stopping(key);
            update(account, region, path, job -> {
                ObjectNode metrics = job.with("Metrics");
                metrics.put("DocumentsAdded", String.valueOf(a)).put("DocumentsModified", String.valueOf(m))
                        .put("DocumentsDeleted", String.valueOf(d)).put("DocumentsFailed", String.valueOf(x));
                job.put("Status", aborted ? "ABORTED" : x > 0 && a + m == 0 && !found.isEmpty() ? "INCOMPLETE"
                        : "SUCCEEDED").put("EndTime", KendraStore.epoch());
            });
        } catch (SyncFailure e) {
            update(account, region, path, job -> job.put("Status", "FAILED").put("EndTime", KendraStore.epoch())
                    .put("ErrorMessage", e.getMessage()).put("ErrorCode", "InvalidRequest")
                    .put("DataSourceErrorCode", e.code));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Kendra sync %s failed", executionId);
            update(account, region, path, job -> job.put("Status", "FAILED").put("EndTime", KendraStore.epoch())
                    .put("ErrorMessage", String.valueOf(e.getMessage())).put("ErrorCode", "InternalError"));
        } finally {
            running.remove(key);
        }
    }

    private interface JobUpdate {
        void apply(ObjectNode job);
    }

    private synchronized void update(String account, String region, String path, JobUpdate change) {
        store.get(account, region, SYNC_JOB, path).ifPresent(job -> {
            change.apply(job);
            store.put(account, region, SYNC_JOB, path, job);
        });
    }

    /** A sync that can't read its source, with Kendra's data source error code. */
    static final class SyncFailure extends RuntimeException {
        final String code;

        SyncFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    // ---------------------------------------------------------------- S3

    private List<Found> crawlS3(String region, ObjectNode source, String key) {
        JsonNode config = source.path("Configuration").path("S3Configuration");
        String bucket = config.path("BucketName").asText();
        List<S3Object> objects;
        try {
            objects = s3.listObjects(bucket, null, null, Integer.MAX_VALUE);
        } catch (AwsException e) {
            throw new SyncFailure("S3BucketNotFound", "Unable to list s3://" + bucket + ": " + e.getMessage());
        }
        String metadataPrefix = config.path("DocumentsMetadataConfiguration").path("S3Prefix").asText(null);
        String aclKey = config.path("AccessControlListConfiguration").path("KeyPath").asText(null);
        JsonNode acl = null;
        if (aclKey != null) {
            try {
                acl = mapper().readTree(s3.getObject(bucket, aclKey).getData());
            } catch (AwsException | IOException e) {
                throw new SyncFailure("InvalidAclFile", "Unable to read the access control list s3://" + bucket
                        + "/" + aclKey + ".");
            }
        }
        List<String> prefixes = strings(config.path("InclusionPrefixes"));
        List<PathMatcher> include = globs(config.path("InclusionPatterns"));
        List<PathMatcher> exclude = globs(config.path("ExclusionPatterns"));
        Map<String, S3Object> byKey = new LinkedHashMap<>();
        objects.forEach(o -> byKey.put(o.getKey(), o));
        List<Found> found = new ArrayList<>();
        for (S3Object object : objects) {
            if (stopping(key)) {
                break;
            }
            String k = object.getKey();
            if (k.endsWith("/") || k.endsWith(".metadata.json") || k.equals(aclKey)
                    || metadataPrefix != null && k.startsWith(metadataPrefix)) {
                continue;
            }
            if (!prefixes.isEmpty() && prefixes.stream().noneMatch(k::startsWith)) {
                continue;
            }
            if (!include.isEmpty() && include.stream().noneMatch(m -> m.matches(Path.of(k)))
                    || exclude.stream().anyMatch(m -> m.matches(Path.of(k)))) {
                continue;
            }
            ObjectNode request = mapper().createObjectNode();
            String id = "s3://" + bucket + "/" + k;
            request.putObject("S3Path").put("Bucket", bucket).put("Key", k);
            String contentType = KendraText.contentType(k);
            String metadataKey = (metadataPrefix == null ? "" : metadataPrefix.endsWith("/") ? metadataPrefix
                    : metadataPrefix + "/") + k + ".metadata.json";
            S3Object metadataObject = byKey.get(metadataKey);
            String version = object.getETag();
            if (metadataObject != null) {
                version += "/" + metadataObject.getETag();
                try {
                    JsonNode metadata = mapper().readTree(s3.getObject(bucket, metadataKey).getData());
                    if (metadata.hasNonNull("DocumentId")) {
                        id = metadata.get("DocumentId").asText();
                    }
                    if (metadata.hasNonNull("Title")) {
                        request.put("Title", metadata.get("Title").asText());
                    }
                    if (metadata.hasNonNull("ContentType")) {
                        contentType = metadata.get("ContentType").asText();
                    }
                    if (metadata.has("AccessControlList")) {
                        request.set("AccessControlList", metadata.get("AccessControlList").deepCopy());
                    }
                    ArrayNode attributes = request.putArray("Attributes");
                    ObjectNode index = indexes.index(store.account(), region, source.path("IndexId").asText());
                    Map<String, ObjectNode> fields = KendraIndexes.fields(index);
                    metadata.path("Attributes").fields().forEachRemaining(e -> attributes.addObject()
                            .put("Key", e.getKey()).set("Value", typed(e.getValue(), fields.get(e.getKey()))));
                } catch (AwsException | IOException e) {
                    LOG.debugf("Kendra: unreadable metadata %s: %s", metadataKey, e.getMessage());
                }
            }
            if (contentType == null) {
                continue;
            }
            request.put("ContentType", contentType);
            if (acl != null && !request.has("AccessControlList")) {
                for (JsonNode rule : acl) {
                    String prefix = rule.path("keyPrefix").asText();
                    if (id.startsWith(prefix) || ("s3://" + bucket + "/" + k).startsWith(prefix)) {
                        request.set("AccessControlList", rule.path("aclEntries").deepCopy());
                    }
                }
            }
            languageOf(source, request);
            request.put("Id", id);
            found.add(new Found(id, request, version));
        }
        return found;
    }

    private void languageOf(ObjectNode source, ObjectNode request) {
        String language = source.path("LanguageCode").asText("en");
        ArrayNode attributes = request.has("Attributes") ? (ArrayNode) request.get("Attributes")
                : request.putArray("Attributes");
        for (JsonNode a : attributes) {
            if ("_language_code".equals(a.path("Key").asText())) {
                return;
            }
        }
        attributes.addObject().put("Key", "_language_code").putObject("Value").put("StringValue", language);
    }

    /** A metadata file's attribute value, as the index field's type. */
    private ObjectNode typed(JsonNode value, ObjectNode field) {
        ObjectNode out = mapper().createObjectNode();
        String type = field == null ? null : field.path("Type").asText();
        if ("STRING_LIST_VALUE".equals(type) || type == null && value.isArray()) {
            ArrayNode list = out.putArray("StringListValue");
            if (value.isArray()) {
                value.forEach(v -> list.add(v.asText()));
            } else {
                list.add(value.asText());
            }
        } else if ("LONG_VALUE".equals(type) || type == null && value.isIntegralNumber()) {
            out.put("LongValue", value.asLong());
        } else if ("DATE_VALUE".equals(type)) {
            Double date = value.isNumber() ? value.asDouble() : KendraSearch.dateOf(value.asText());
            out.put("DateValue", date == null ? 0 : date);
        } else {
            out.put("StringValue", value.asText());
        }
        return out;
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<PathMatcher> globs(JsonNode patterns) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (JsonNode p : patterns) {
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + p.asText()));
            } catch (IllegalArgumentException e) {
                throw new SyncFailure("InvalidPattern", "The pattern " + p.asText() + " is not a valid glob.");
            }
        }
        return matchers;
    }

    // ---------------------------------------------------------------- web

    private List<Found> crawlWeb(String region, ObjectNode source, String key) {
        JsonNode config = source.path("Configuration").path("WebCrawlerConfiguration");
        int depth = config.path("CrawlDepth").asInt(2);
        int maxLinks = config.path("MaxLinksPerPage").asInt(100);
        double maxMegabytes = config.path("MaxContentSizePerPageInMegaBytes").asDouble(50);
        List<Pattern> include = regexes(config.path("UrlInclusionPatterns"));
        List<Pattern> exclude = regexes(config.path("UrlExclusionPatterns"));
        Map<String, String> auth = new HashMap<>();
        for (JsonNode basic : config.path("AuthenticationConfiguration").path("BasicAuthentication")) {
            JsonNode secret = secret(region, basic.path("Credentials").asText());
            auth.put(basic.path("Host").asText().toLowerCase(Locale.ROOT) + ":" + basic.path("Port").asInt(),
                    "Basic " + Base64.getEncoder().encodeToString((secret.path("username").asText() + ":"
                            + secret.path("password").asText()).getBytes(StandardCharsets.UTF_8)));
        }
        String mode = config.path("Urls").path("SeedUrlConfiguration").path("WebCrawlerMode").asText("HOST_ONLY");
        Deque<Object[]> queue = new ArrayDeque<>();
        Set<String> seeds = new LinkedHashSet<>();
        for (JsonNode seed : config.path("Urls").path("SeedUrlConfiguration").path("SeedUrls")) {
            seeds.add(seed.asText());
            queue.add(new Object[] {seed.asText(), 0});
        }
        for (JsonNode sitemap : config.path("Urls").path("SiteMapsConfiguration").path("SiteMaps")) {
            String xml = fetchText(sitemap.asText(), auth);
            Matcher loc = Pattern.compile("(?s)<loc>\\s*(.*?)\\s*</loc>").matcher(xml);
            while (loc.find()) {
                String url = KendraText.decode(loc.group(1));
                seeds.add(url);
                queue.add(new Object[] {url, depth});
            }
        }
        Set<String> visited = new LinkedHashSet<>();
        List<Found> found = new ArrayList<>();
        while (!queue.isEmpty() && !stopping(key) && visited.size() < 10_000) {
            Object[] next = queue.poll();
            String url = stripFragment((String) next[0]);
            int level = (Integer) next[1];
            if (!visited.add(url) || !allowedUrl(url, include, exclude) || !inScope(url, seeds, mode)) {
                continue;
            }
            HttpResponse<byte[]> response;
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "amazon-kendra").GET();
                URI uri = URI.create(url);
                int port = uri.getPort() > 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
                String header = auth.get(uri.getHost().toLowerCase(Locale.ROOT) + ":" + port);
                if (header != null) {
                    request.header("Authorization", header);
                }
                response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException | IllegalArgumentException e) {
                if (seeds.contains(url) && found.isEmpty() && queue.isEmpty()) {
                    throw new SyncFailure("UrlUnreachable", "Unable to reach " + url + ": " + e.getMessage());
                }
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (response.statusCode() >= 400) {
                continue;
            }
            byte[] body = response.body();
            if (body.length > maxMegabytes * 1024 * 1024) {
                continue;
            }
            String mime = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            String contentType = mime.contains("html") ? "HTML" : mime.contains("pdf") ? "PDF"
                    : mime.contains("json") ? "JSON" : mime.contains("xml") ? "XML" : mime.contains("csv") ? "CSV"
                    : mime.startsWith("text/") ? "PLAIN_TEXT" : KendraText.contentType(URI.create(url).getPath());
            if (contentType == null) {
                continue;
            }
            ObjectNode request = mapper().createObjectNode();
            request.put("Id", url).put("Blob", Base64.getEncoder().encodeToString(body))
                    .put("ContentType", contentType);
            request.putArray("Attributes").addObject().put("Key", "_source_uri").putObject("Value")
                    .put("StringValue", url);
            languageOf(source, request);
            found.add(new Found(url, request, sha256(body)));
            if ("HTML".equals(contentType) && level < depth) {
                int links = 0;
                for (String href : KendraText.links(new String(body, StandardCharsets.UTF_8))) {
                    if (links >= maxLinks) {
                        break;
                    }
                    try {
                        URI resolved = URI.create(url).resolve(href.replace(" ", "%20"));
                        if (resolved.getScheme() != null && resolved.getScheme().startsWith("http")) {
                            queue.add(new Object[] {resolved.toString(), level + 1});
                            links++;
                        }
                    } catch (IllegalArgumentException e) {
                        // Not a URL.
                    }
                }
            }
        }
        return found;
    }

    private String fetchText(String url, Map<String, String> auth) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new SyncFailure("UrlUnreachable", "The sitemap " + url + " answered " + response.statusCode()
                        + ".");
            }
            return response.body();
        } catch (IOException | IllegalArgumentException e) {
            throw new SyncFailure("UrlUnreachable", "Unable to reach the sitemap " + url + ".");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SyncFailure("UrlUnreachable", "Interrupted fetching " + url + ".");
        }
    }

    private static String stripFragment(String url) {
        int hash = url.indexOf('#');
        return hash < 0 ? url : url.substring(0, hash);
    }

    private static boolean allowedUrl(String url, List<Pattern> include, List<Pattern> exclude) {
        if (exclude.stream().anyMatch(p -> p.matcher(url).find())) {
            return false;
        }
        return include.isEmpty() || include.stream().anyMatch(p -> p.matcher(url).find());
    }

    private static boolean inScope(String url, Set<String> seeds, String mode) {
        if (seeds.contains(url) || "EVERYTHING".equals(mode)) {
            return true;
        }
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        for (String seed : seeds) {
            String seedHost = URI.create(seed).getHost();
            if (seedHost == null) {
                continue;
            }
            if (host.equalsIgnoreCase(seedHost) || "SUBDOMAINS".equals(mode)
                    && host.toLowerCase(Locale.ROOT).endsWith("." + seedHost.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<Pattern> regexes(JsonNode patterns) {
        List<Pattern> out = new ArrayList<>();
        for (JsonNode p : patterns) {
            try {
                out.add(Pattern.compile(p.asText()));
            } catch (IllegalArgumentException e) {
                throw new SyncFailure("InvalidPattern", "The pattern " + p.asText() + " is not a valid regex.");
            }
        }
        return out;
    }

    private JsonNode secret(String region, String arn) {
        try {
            return mapper().readTree(secrets.getSecretValue(arn, null, null, region).getSecretString());
        } catch (AwsException | IOException | IllegalArgumentException e) {
            throw new SyncFailure("InvalidCredentials", "Unable to read the secret " + arn + ".");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- databases

    private List<Found> crawlDatabase(String region, ObjectNode source, String key) {
        JsonNode config = source.path("Configuration").path("DatabaseConfiguration");
        JsonNode connection = config.path("ConnectionConfiguration");
        JsonNode columns = config.path("ColumnConfiguration");
        String engine = config.path("DatabaseEngineType").asText();
        boolean mysql = engine.contains("MYSQL");
        String url = (mysql ? "jdbc:mysql://" : "jdbc:postgresql://") + connection.path("DatabaseHost").asText()
                + ":" + connection.path("DatabasePort").asInt() + "/" + connection.path("DatabaseName").asText()
                + (mysql ? "?useSSL=false&allowPublicKeyRetrieval=true" : "?sslmode=disable");
        JsonNode secret = secret(region, connection.path("SecretArn").asText());
        Properties props = new Properties();
        props.setProperty("user", secret.path("username").asText());
        props.setProperty("password", secret.path("password").asText());
        props.setProperty("connectTimeout", mysql ? "5000" : "5");
        boolean quoted = "DOUBLE_QUOTES".equals(config.path("SqlConfiguration")
                .path("QueryIdentifiersEnclosingOption").asText());
        String table = connection.path("TableName").asText();
        String sql = "SELECT * FROM " + (quoted ? "\"" + table.replace("\"", "\"\"") + "\"" : table);
        ObjectNode index = indexes.index(store.account(), region, source.path("IndexId").asText());
        Map<String, ObjectNode> fields = KendraIndexes.fields(index);
        List<Found> found = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, props); Statement s = c.createStatement();
             ResultSet rows = s.executeQuery(sql)) {
            ResultSetMetaData meta = rows.getMetaData();
            Map<String, Integer> byName = new HashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                byName.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), i);
            }
            String idColumn = column(byName, columns.path("DocumentIdColumnName").asText());
            String dataColumn = column(byName, columns.path("DocumentDataColumnName").asText());
            while (rows.next() && !stopping(key)) {
                String id = rows.getString(byName.get(idColumn));
                if (id == null) {
                    continue;
                }
                ObjectNode request = mapper().createObjectNode();
                String body = rows.getString(byName.get(dataColumn));
                request.put("Id", id).put("Blob", Base64.getEncoder().encodeToString((body == null ? "" : body)
                        .getBytes(StandardCharsets.UTF_8))).put("ContentType", "PLAIN_TEXT");
                if (columns.hasNonNull("DocumentTitleColumnName")) {
                    String title = rows.getString(byName.get(column(byName, columns.get("DocumentTitleColumnName")
                            .asText())));
                    if (title != null) {
                        request.put("Title", title);
                    }
                }
                ArrayNode attributes = request.putArray("Attributes");
                for (JsonNode mapping : columns.path("FieldMappings")) {
                    String value = rows.getString(byName.get(column(byName, mapping.path("DataSourceFieldName")
                            .asText())));
                    String field = mapping.path("IndexFieldName").asText();
                    if (value != null) {
                        attributes.addObject().put("Key", field).set("Value", typed(
                                mapper().getNodeFactory().textNode(value), fields.get(field)));
                    }
                }
                if (config.has("AclConfiguration")) {
                    String allowed = rows.getString(byName.get(column(byName, config.path("AclConfiguration")
                            .path("AllowedGroupsColumnName").asText())));
                    if (allowed != null) {
                        ArrayNode acl = request.putArray("AccessControlList");
                        for (String group : allowed.split(",")) {
                            if (!group.isBlank()) {
                                acl.addObject().put("Name", group.strip()).put("Type", "GROUP")
                                        .put("Access", "ALLOW");
                            }
                        }
                    }
                }
                StringBuilder version = new StringBuilder();
                for (JsonNode change : columns.path("ChangeDetectingColumns")) {
                    version.append(rows.getString(byName.get(column(byName, change.asText())))).append('\u0000');
                }
                languageOf(source, request);
                found.add(new Found(id, request, sha256(version.toString().getBytes(StandardCharsets.UTF_8))));
            }
        } catch (SQLException e) {
            throw new SyncFailure("DatabaseConnectionFailed", "Unable to read " + table + " from "
                    + connection.path("DatabaseHost").asText() + ": " + e.getMessage());
        }
        return found;
    }

    private static String column(Map<String, Integer> byName, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!byName.containsKey(lower)) {
            throw new SyncFailure("ColumnNotFound", "The table has no column " + name + ".");
        }
        return lower;
    }

    // ---------------------------------------------------------------- schedules and restarts

    /**
     * Starts the syncs whose schedule came due since the last sweep, and restarts syncs a restart of Floci
     * interrupted.
     */
    void sweep(Instant now) {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(DATA_SOURCE)) {
            ObjectNode source = entry.value();
            String region = entry.key().substring(0, entry.key().indexOf('/'));
            String account = entry.accountId();
            RequestScopes.runAs(account, region, () -> sweepSource(account, region, source, now));
        }
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(SYNC_JOB)) {
            ObjectNode job = entry.value();
            String region = entry.key().substring(0, entry.key().indexOf('/'));
            String path = meta(job).path("path").asText();
            String key = entry.accountId() + "/" + region + "/" + path;
            if (RUNNING.contains(job.path("Status").asText()) && !running.containsKey(key)) {
                String[] parts = path.split("/");
                ObjectNode source = store.get(entry.accountId(), region, DATA_SOURCE, parts[0] + "/" + parts[1])
                        .orElse(null);
                if (source == null || "CUSTOM".equals(source.path("Type").asText())) {
                    continue;
                }
                running.put(key, "STOPPING".equals(job.path("Status").asText()));
                executor.submit(() -> RequestScopes.runAs(entry.accountId(), region, () -> run(entry.accountId(),
                        region, parts[0], parts[1], parts[2], key)));
            }
        }
    }

    private void sweepSource(String account, String region, ObjectNode source, Instant now) {
        String schedule = source.path("Schedule").asText("");
        if (schedule.isEmpty() || !"ACTIVE".equals(source.path("Status").asText())) {
            return;
        }
        long from = meta(source).path("scheduledFrom").asLong(now.toEpochMilli());
        Instant due;
        try {
            due = SchedulerExpressionParser.nextCronFire(schedule, Instant.ofEpochMilli(from), "UTC");
        } catch (RuntimeException e) {
            return;
        }
        if (due.isAfter(now)) {
            return;
        }
        meta(source).put("scheduledFrom", now.toEpochMilli());
        String path = source.path("IndexId").asText() + "/" + source.path("Id").asText();
        store.put(account, region, DATA_SOURCE, path, source);
        try {
            start(account, region, source);
        } catch (AwsException e) {
            LOG.debugf("Kendra: scheduled sync of %s skipped: %s", path, e.getMessage());
        }
    }
}
