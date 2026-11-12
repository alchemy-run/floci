package io.floci.aws.services.securitylake;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.Crawler;
import io.floci.aws.services.glue.model.CrawlerTargets;
import io.floci.aws.services.glue.model.Database;
import io.floci.aws.services.glue.model.S3Target;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.lakeformation.LakeFormationService;
import io.floci.aws.services.lakeformation.model.RegisterResourceRequest;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.NotificationConfiguration;
import io.floci.aws.services.s3.model.QueueNotification;
import io.floci.aws.services.sqs.SqsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Amazon Security Lake: a data lake per Region, the AWS and custom log sources it collects, the subscribers who read
 * it, their notifications, and the exception subscription.
 *
 * <p>Enabling the lake in a Region creates its S3 bucket with the lake's lifecycle rules, its Glue database, and
 * registers the bucket with Lake Formation under the metastore manager role; disabling it keeps the bucket and the
 * database, as AWS does. A log source gets a Glue table over its prefix, and a custom source also its Glue crawler
 * and a provider role that its identity can assume. A subscriber gets an IAM role its identity can assume to read the
 * bucket; an SQS notification creates the subscriber's queue, which the bucket notifies of new objects. An account
 * without a data lake gets {@code UnauthorizedException} from the subscriber and source APIs, as on AWS.
 */
@ApplicationScoped
public class SecurityLakeService implements Resettable {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> AWS_SOURCES = Set.of("ROUTE53", "VPC_FLOW", "SH_FINDINGS", "CLOUD_TRAIL_MGMT",
            "LAMBDA_EXECUTION", "S3_DATA", "EKS_AUDIT", "WAF");

    private final AccountAwareStorageBackend<ObjectNode> lakes;
    private final AccountAwareStorageBackend<ObjectNode> sources;
    private final AccountAwareStorageBackend<ObjectNode> subscribers;
    private final AccountAwareStorageBackend<ObjectNode> settings;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final Provider<S3Service> s3;
    private final Provider<GlueService> glue;
    private final Provider<IamService> iam;
    private final Provider<SqsService> sqs;
    private final Provider<LakeFormationService> lakeFormation;

    @Inject
    public SecurityLakeService(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config,
                               Provider<S3Service> s3, Provider<GlueService> glue, Provider<IamService> iam,
                               Provider<SqsService> sqs, Provider<LakeFormationService> lakeFormation) {
        this.lakes = storageFactory.create("securitylake", "data-lakes.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.sources = storageFactory.create("securitylake", "log-sources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.subscribers = storageFactory.create("securitylake", "subscribers.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.settings = storageFactory.create("securitylake", "settings.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
        this.s3 = s3;
        this.glue = glue;
        this.iam = iam;
        this.sqs = sqs;
        this.lakeFormation = lakeFormation;
    }

    @Override
    public void clear() {
        lakes.clear();
        sources.clear();
        subscribers.clear();
        settings.clear();
    }

    public ObjectNode handle(String operation, JsonNode in, String region) {
        return switch (operation) {
            case "CreateDataLake" -> createDataLake(in, region);
            case "UpdateDataLake" -> updateDataLake(in, region);
            case "DeleteDataLake" -> deleteDataLake(in, region);
            case "ListDataLakes" -> listDataLakes(in);
            case "CreateAwsLogSource" -> awsSources(in, region, true);
            case "DeleteAwsLogSource" -> awsSources(in, region, false);
            case "CreateCustomLogSource" -> createCustomSource(in, region);
            case "DeleteCustomLogSource" -> deleteCustomSource(in, region);
            case "ListLogSources" -> listLogSources(in, region);
            case "GetDataLakeSources" -> getDataLakeSources(in, region);
            case "ListDataLakeExceptions" -> listExceptions(in, region);
            case "CreateDataLakeExceptionSubscription" -> putExceptionSubscription(in, region, true);
            case "UpdateDataLakeExceptionSubscription" -> putExceptionSubscription(in, region, false);
            case "GetDataLakeExceptionSubscription" -> getExceptionSubscription(region);
            case "DeleteDataLakeExceptionSubscription" -> deleteExceptionSubscription(region);
            case "CreateDataLakeOrganizationConfiguration" -> organization(in, region, true);
            case "DeleteDataLakeOrganizationConfiguration" -> organization(in, region, false);
            case "GetDataLakeOrganizationConfiguration" -> getOrganization(region);
            case "RegisterDataLakeDelegatedAdministrator" -> delegate(in, region, true);
            case "DeregisterDataLakeDelegatedAdministrator" -> delegate(in, region, false);
            case "CreateSubscriber" -> createSubscriber(in, region);
            case "GetSubscriber" -> wrap("subscriber", view(subscriber(in, region)));
            case "UpdateSubscriber" -> updateSubscriber(in, region);
            case "DeleteSubscriber" -> deleteSubscriber(in, region);
            case "ListSubscribers" -> listSubscribers(in, region);
            case "CreateSubscriberNotification" -> putNotification(in, region, true);
            case "UpdateSubscriberNotification" -> putNotification(in, region, false);
            case "DeleteSubscriberNotification" -> deleteNotification(in, region);
            case "TagResource" -> tag(in, region, true);
            case "UntagResource" -> tag(in, region, false);
            case "ListTagsForResource" -> listTags(in, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation + ".",
                    404);
        };
    }

    /** The subscriber and source APIs answer an account without a data lake in the Region as AWS does. */
    public void checkOnboarded(String operation, String region) {
        if (Set.of("CreateDataLake", "UpdateDataLake", "DeleteDataLake", "ListDataLakes",
                "RegisterDataLakeDelegatedAdministrator", "DeregisterDataLakeDelegatedAdministrator",
                "TagResource", "UntagResource", "ListTagsForResource").contains(operation)) {
            return;
        }
        // Only an account that never had a data lake here is turned away; once offboarded, what it asks for
        // is simply not found.
        if (lakes.get(region).isEmpty() && settings.get("onboarded/" + region).isEmpty()) {
            throw new AwsException("UnauthorizedException", "Security Lake isn't enabled for this account in "
                    + region + ". Create a data lake first.", 401);
        }
    }

    // ---------- data lakes ----------

    private ObjectNode createDataLake(JsonNode in, String region) {
        String metastoreRole = in.path("metaStoreManagerRoleArn").asText();
        requireRole(metastoreRole, "metaStoreManagerRoleArn");
        for (JsonNode configuration : in.path("configurations")) {
            if (lakes.get(configuration.path("region").asText()).isPresent()) {
                throw conflict("Security Lake is already enabled in " + configuration.path("region").asText()
                        + ".");
            }
        }
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode configuration : in.path("configurations")) {
            String lakeRegion = configuration.path("region").asText();
            String account = lakes.accountId();
            String bucket = "aws-security-data-lake-" + lakeRegion + "-" + random(30);
            RequestScopes.runAs(account, lakeRegion, () -> {
                s3.get().createBucket(bucket, lakeRegion);
                applyLifecycle(bucket, configuration.path("lifecycleConfiguration"));
                Database database = new Database(databaseName(lakeRegion));
                database.setDescription("Security Lake data in " + lakeRegion);
                database.setLocationUri("s3://" + bucket + "/");
                try {
                    glue.get().createDatabase(database, null, lakeRegion);
                } catch (AwsException e) {
                    // Kept from an earlier data lake in this Region, as AWS keeps it.
                }
                RegisterResourceRequest registration = new RegisterResourceRequest();
                registration.setResourceArn(AwsArnUtils.Arn.global(AwsArnUtils.parse(metastoreRole).partition(),
                        "s3", "", bucket).toString());
                registration.setRoleArn(metastoreRole);
                try {
                    lakeFormation.get().registerResource(lakeRegion, registration);
                } catch (AwsException e) {
                    // Already registered.
                }
            });
            ObjectNode lake = mapper.createObjectNode();
            lake.put("region", lakeRegion)
                    .put("dataLakeArn", AwsArnUtils.Arn.of("securitylake", lakeRegion, account, "data-lake/default")
                            .toString())
                    .put("s3BucketArn", AwsArnUtils.Arn.global(AwsArnUtils.parse(metastoreRole).partition(), "s3",
                            "", bucket).toString())
                    .put("createStatus", "INITIALIZED");
            for (String field : List.of("encryptionConfiguration", "lifecycleConfiguration",
                    "replicationConfiguration")) {
                if (configuration.has(field)) {
                    lake.set(field, configuration.get(field).deepCopy());
                }
            }
            if (!lake.has("encryptionConfiguration")) {
                lake.putObject("encryptionConfiguration").put("kmsKeyId", "S3_MANAGED_KEY");
            }
            ObjectNode meta = lake.putObject("_");
            meta.put("ready", now() + step()).put("bucket", bucket).put("metastoreRole", metastoreRole);
            meta.set("tags", tagMap(in.path("tags")));
            lakes.put(lakeRegion, lake);
            settings.put("onboarded/" + lakeRegion, mapper.createObjectNode().put("since", now()));
            out.add(view(lake));
        }
        return wrap("dataLakes", out);
    }

    private void applyLifecycle(String bucket, JsonNode lifecycle) {
        if (lifecycle.isMissingNode() || lifecycle.isEmpty()) {
            s3.get().deleteBucketLifecycle(bucket);
            return;
        }
        StringBuilder xml = new StringBuilder("<LifecycleConfiguration><Rule><ID>SecurityLake</ID><Filter><Prefix>"
                + "</Prefix></Filter><Status>Enabled</Status>");
        for (JsonNode transition : lifecycle.path("transitions")) {
            xml.append("<Transition><Days>").append(transition.path("days").asInt()).append("</Days><StorageClass>")
                    .append(transition.path("storageClass").asText()).append("</StorageClass></Transition>");
        }
        if (lifecycle.path("expiration").has("days")) {
            xml.append("<Expiration><Days>").append(lifecycle.path("expiration").path("days").asInt())
                    .append("</Days></Expiration>");
        }
        s3.get().putBucketLifecycle(bucket, xml.append("</Rule></LifecycleConfiguration>").toString(), null);
    }

    private static String databaseName(String region) {
        return "amazon_security_lake_glue_db_" + region.replace('-', '_');
    }

    private ObjectNode lake(String region) {
        ObjectNode lake = lakes.get(region).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("There's no data lake in " + region + "."));
        if ("INITIALIZED".equals(lake.path("createStatus").asText())
                && now() >= lake.path("_").path("ready").asDouble()) {
            lake.put("createStatus", "COMPLETED");
            lakes.put(region, lake);
        }
        return lake;
    }

    private ObjectNode updateDataLake(JsonNode in, String region) {
        if (in.hasNonNull("metaStoreManagerRoleArn")) {
            requireRole(in.get("metaStoreManagerRoleArn").asText(), "metaStoreManagerRoleArn");
        }
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode configuration : in.path("configurations")) {
            ObjectNode lake = lake(configuration.path("region").asText());
            for (String field : List.of("encryptionConfiguration", "lifecycleConfiguration",
                    "replicationConfiguration")) {
                if (configuration.has(field)) {
                    lake.set(field, configuration.get(field).deepCopy());
                }
            }
            String bucket = lake.path("_").path("bucket").asText();
            RequestScopes.runAs(lakes.accountId(), lake.path("region").asText(),
                    () -> applyLifecycle(bucket, lake.path("lifecycleConfiguration")));
            lake.putObject("updateStatus").put("status", "COMPLETED").put("requestId", UUID.randomUUID().toString());
            lakes.put(lake.path("region").asText(), lake);
            out.add(view(lake));
        }
        return wrap("dataLakes", out);
    }

    private ObjectNode deleteDataLake(JsonNode in, String region) {
        for (JsonNode lakeRegion : in.path("regions")) {
            lake(lakeRegion.asText());
        }
        for (JsonNode lakeRegion : in.path("regions")) {
            String name = lakeRegion.asText();
            for (ObjectNode subscriber : subscribers.scan(k -> k.startsWith(name + "/"))) {
                throw conflict("The data lake in " + name + " still has the subscriber "
                        + subscriber.path("subscriberName").asText() + ". Delete it first.");
            }
        }
        for (JsonNode lakeRegion : in.path("regions")) {
            String name = lakeRegion.asText();
            for (ObjectNode source : sources.scan(k -> k.startsWith(name + "/"))) {
                dropSource(name, source);
            }
            // The bucket and the Glue database stay, as on AWS.
            lakes.delete(name);
        }
        return mapper.createObjectNode();
    }

    private ObjectNode listDataLakes(JsonNode in) {
        ArrayNode out = mapper.createArrayNode();
        List<String> wanted = new ArrayList<>();
        in.path("regions").forEach(r -> wanted.add(r.asText()));
        for (ObjectNode stored : lakes.scan(k -> true)) {
            String lakeRegion = stored.path("region").asText();
            if (wanted.isEmpty() || wanted.contains(lakeRegion)) {
                out.add(view(lake(lakeRegion)));
            }
        }
        return wrap("dataLakes", out);
    }

    // ---------- log sources ----------

    private ObjectNode awsSources(JsonNode in, String region, boolean enable) {
        ArrayNode failed = mapper.createArrayNode();
        for (JsonNode configuration : in.path("sources")) {
            String name = configuration.path("sourceName").asText();
            if (!AWS_SOURCES.contains(name)) {
                throw badRequest("sourceName " + name + " isn't an AWS log source.");
            }
            String version = configuration.path("sourceVersion").asText("2.0");
            for (JsonNode target : configuration.path("regions")) {
                String sourceRegion = target.asText();
                if (lakes.get(sourceRegion).isEmpty()) {
                    failed.add(sourceRegion);
                    continue;
                }
                String key = sourceKey(sourceRegion, "managed", name, version);
                if (enable && sources.get(key).isEmpty()) {
                    ObjectNode source = mapper.createObjectNode().put("type", "managed").put("region", sourceRegion)
                            .put("sourceName", name).put("sourceVersion", version);
                    source.set("accounts", configuration.path("accounts").isArray()
                            ? configuration.get("accounts").deepCopy() : mapper.createArrayNode()
                            .add(sources.accountId()));
                    String table = tableFor(sourceRegion, name.toLowerCase(Locale.ROOT), version,
                            "aws/" + name + "/" + version + "/");
                    source.put("table", table);
                    sources.put(key, source);
                } else if (!enable) {
                    sources.get(key).ifPresent(source -> dropSource(sourceRegion, source));
                }
            }
        }
        return mapper.createObjectNode().set("failed", failed);
    }

    /** Creates the source's Glue table over its prefix of the lake bucket, and returns its name. */
    private String tableFor(String region, String name, String version, String prefix) {
        String tableName = "amazon_security_lake_table_" + region.replace('-', '_') + "_"
                + name.replaceAll("[^a-z0-9]", "_") + "_" + version.replace('.', '_');
        String bucket = lake(region).path("_").path("bucket").asText();
        RequestScopes.runAs(lakes.accountId(), region, () -> {
            Table table = new Table();
            table.setName(tableName);
            table.setDatabaseName(databaseName(region));
            table.setTableType("EXTERNAL_TABLE");
            StorageDescriptor storage = new StorageDescriptor();
            storage.setLocation("s3://" + bucket + "/" + prefix);
            storage.setColumns(List.of(new Column("metadata", "struct<version:string,product:struct<name:string>>"),
                    new Column("time", "bigint"), new Column("class_uid", "int"), new Column("category_uid", "int"),
                    new Column("severity_id", "int")));
            table.setStorageDescriptor(storage);
            try {
                glue.get().createTable(databaseName(region), table);
            } catch (AwsException e) {
                // The table outlived an earlier source, as Security Lake keeps tables.
            }
        });
        return tableName;
    }

    private void dropSource(String region, ObjectNode source) {
        RequestScopes.runAs(lakes.accountId(), region, () -> {
            try {
                glue.get().deleteTable(databaseName(region), source.path("table").asText());
            } catch (AwsException e) {
                // Already gone.
            }
            if ("custom".equals(source.path("type").asText())) {
                try {
                    glue.get().deleteCrawler(source.path("crawler").asText(), region);
                } catch (AwsException e) {
                    // Already gone.
                }
                deleteRole(source.path("providerRole").asText());
            }
        });
        sources.delete(sourceKey(region, source.path("type").asText(), source.path("sourceName").asText(),
                source.path("sourceVersion").asText()));
    }

    private ObjectNode createCustomSource(JsonNode in, String region) {
        lake(region);
        String name = in.path("sourceName").asText();
        String version = in.path("sourceVersion").asText("1.0");
        String key = sourceKey(region, "custom", name, version);
        if (sources.get(key).isPresent()) {
            throw conflict("The custom source " + name + " " + version + " already exists.");
        }
        JsonNode configuration = in.path("configuration");
        String crawlerRole = configuration.path("crawlerConfiguration").path("roleArn").asText();
        requireRole(crawlerRole, "crawlerConfiguration.roleArn");
        JsonNode identity = configuration.path("providerIdentity");
        String bucket = lake(region).path("_").path("bucket").asText();
        String prefix = "ext/" + name + "/" + version + "/";
        String table = tableFor(region, name.toLowerCase(Locale.ROOT), version, prefix);
        String crawlerName = "SecurityLake-" + name + "-" + region;
        String account = lakes.accountId();
        String partition = AwsArnUtils.parse(crawlerRole).partition();
        RequestScopes.runAs(account, region, () -> {
            Crawler crawler = new Crawler();
            crawler.setName(crawlerName);
            crawler.setRole(crawlerRole);
            crawler.setDatabaseName(databaseName(region));
            CrawlerTargets targets = new CrawlerTargets();
            S3Target target = new S3Target();
            target.setPath("s3://" + bucket + "/" + prefix);
            targets.setS3Targets(List.of(target));
            crawler.setTargets(targets);
            try {
                glue.get().createCrawler(crawler, null, region);
            } catch (AwsException e) {
                // Kept from an earlier source with this name.
            }
        });
        String providerRole = roleName("AmazonSecurityLake-Provider-" + name + "-" + region);
        IamRole role = createAssumableRole(providerRole, identity, "Lets the custom source " + name
                + " write to Security Lake.", List.of("s3:PutObject", "s3:PutObjectAcl"),
                AwsArnUtils.Arn.global(partition, "s3", "", bucket + "/" + prefix + "*").toString());
        ObjectNode source = mapper.createObjectNode().put("type", "custom").put("region", region)
                .put("sourceName", name).put("sourceVersion", version).put("table", table)
                .put("crawler", crawlerName).put("providerRole", providerRole);
        source.set("eventClasses", in.path("eventClasses").deepCopy());
        ObjectNode resource = source.putObject("resource").put("sourceName", name).put("sourceVersion", version);
        resource.putObject("provider").put("location", "s3://" + bucket + "/" + prefix).put("roleArn",
                role.getArn());
        resource.putObject("attributes")
                .put("crawlerArn", AwsArnUtils.Arn.of("glue", region, account, "crawler/" + crawlerName).toString())
                .put("databaseArn", AwsArnUtils.Arn.of("glue", region, account, "database/"
                        + databaseName(region)).toString())
                .put("tableArn", AwsArnUtils.Arn.of("glue", region, account, "table/" + databaseName(region) + "/"
                        + table).toString());
        sources.put(key, source);
        return wrap("source", (ObjectNode) resource.deepCopy());
    }

    private ObjectNode deleteCustomSource(JsonNode in, String region) {
        String name = decode(in.path("sourceName").asText());
        String version = in.path("sourceVersion").asText(null);
        boolean found = false;
        for (ObjectNode source : sources.scan(k -> k.startsWith(region + "/custom/" + name + "/"))) {
            if (version == null || version.equals(source.path("sourceVersion").asText())) {
                dropSource(region, source);
                found = true;
            }
        }
        if (!found) {
            throw notFound("The custom source " + name + " doesn't exist.");
        }
        return mapper.createObjectNode();
    }

    private ObjectNode sourceResource(ObjectNode source) {
        ObjectNode resource = mapper.createObjectNode();
        if ("managed".equals(source.path("type").asText())) {
            resource.putObject("awsLogSource").put("sourceName", source.path("sourceName").asText())
                    .put("sourceVersion", source.path("sourceVersion").asText());
        } else {
            resource.set("customLogSource", source.path("resource").deepCopy());
        }
        return resource;
    }

    private ObjectNode listLogSources(JsonNode in, String region) {
        List<String> wanted = new ArrayList<>();
        in.path("regions").forEach(r -> wanted.add(r.asText()));
        ArrayNode out = mapper.createArrayNode();
        for (ObjectNode stored : lakes.scan(k -> true)) {
            String lakeRegion = stored.path("region").asText();
            if (!wanted.isEmpty() && !wanted.contains(lakeRegion)) {
                continue;
            }
            ObjectNode entry = mapper.createObjectNode().put("account", sources.accountId()).put("region", lakeRegion);
            ArrayNode list = entry.putArray("sources");
            for (ObjectNode source : sources.scan(k -> k.startsWith(lakeRegion + "/"))) {
                list.add(sourceResource(source));
            }
            out.add(entry);
        }
        return mapper.createObjectNode().set("sources", out);
    }

    private ObjectNode getDataLakeSources(JsonNode in, String region) {
        ObjectNode lake = lake(region);
        String bucket = lake.path("_").path("bucket").asText();
        ObjectNode out = mapper.createObjectNode().put("dataLakeArn", lake.path("dataLakeArn").asText());
        ArrayNode list = out.putArray("dataLakeSources");
        for (ObjectNode source : sources.scan(k -> k.startsWith(region + "/"))) {
            String prefix = "managed".equals(source.path("type").asText())
                    ? "aws/" + source.path("sourceName").asText() + "/" + source.path("sourceVersion").asText() + "/"
                    : "ext/" + source.path("sourceName").asText() + "/" + source.path("sourceVersion").asText() + "/";
            ObjectNode item = list.addObject().put("account", sources.accountId())
                    .put("sourceName", source.path("sourceName").asText());
            item.set("eventClasses", source.path("eventClasses").isArray() ? source.get("eventClasses").deepCopy()
                    : mapper.createArrayNode());
            item.putArray("sourceStatuses").addObject().put("resource", "s3://" + bucket + "/" + prefix)
                    .put("status", misconfigured(source) == null ? "COLLECTING" : "MISCONFIGURED");
        }
        return out;
    }

    /** Why a source can't collect, or null: a custom source's crawler role is gone. */
    private String misconfigured(ObjectNode source) {
        if (!"custom".equals(source.path("type").asText())) {
            return null;
        }
        String role = source.path("resource").path("provider").path("roleArn").asText();
        if (!roleExists(role)) {
            return "The provider role " + role + " no longer exists.";
        }
        return null;
    }

    private ObjectNode listExceptions(JsonNode in, String region) {
        List<String> wanted = new ArrayList<>();
        in.path("regions").forEach(r -> wanted.add(r.asText()));
        ArrayNode out = mapper.createArrayNode();
        for (ObjectNode source : sources.scan(k -> true)) {
            String sourceRegion = source.path("region").asText();
            String reason = misconfigured(source);
            if (reason != null && (wanted.isEmpty() || wanted.contains(sourceRegion))) {
                out.addObject().put("region", sourceRegion).put("exception", reason)
                        .put("remediation", "Recreate the custom source " + source.path("sourceName").asText()
                                + ".").put("timestamp", now());
            }
        }
        return mapper.createObjectNode().set("exceptions", out);
    }

    // ---------- exception subscription and organization ----------

    private ObjectNode putExceptionSubscription(JsonNode in, String region, boolean create) {
        String key = "exceptions/" + region;
        boolean exists = settings.get(key).isPresent();
        if (create && exists) {
            throw conflict("The exception subscription already exists.");
        }
        if (!create && !exists) {
            throw notFound("There's no exception subscription.");
        }
        ObjectNode subscription = mapper.createObjectNode()
                .put("subscriptionProtocol", in.path("subscriptionProtocol").asText())
                .put("notificationEndpoint", in.path("notificationEndpoint").asText())
                .put("exceptionTimeToLive", in.path("exceptionTimeToLive").asLong(30));
        settings.put(key, subscription);
        return mapper.createObjectNode();
    }

    private ObjectNode getExceptionSubscription(String region) {
        return settings.get("exceptions/" + region).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("There's no exception subscription."));
    }

    private ObjectNode deleteExceptionSubscription(String region) {
        getExceptionSubscription(region);
        settings.delete("exceptions/" + region);
        return mapper.createObjectNode();
    }

    private ObjectNode organization(JsonNode in, String region, boolean add) {
        ObjectNode current = settings.get("organization/" + region).map(ObjectNode::deepCopy)
                .orElseGet(() -> {
                    ObjectNode empty = mapper.createObjectNode();
                    empty.putArray("autoEnableNewAccount");
                    return empty;
                });
        ArrayNode entries = (ArrayNode) current.get("autoEnableNewAccount");
        for (JsonNode entry : in.path("autoEnableNewAccount")) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i).path("region").equals(entry.path("region"))) {
                    entries.remove(i);
                }
            }
            if (add) {
                entries.add(entry.deepCopy());
            }
        }
        settings.put("organization/" + region, current);
        return mapper.createObjectNode();
    }

    private ObjectNode getOrganization(String region) {
        return settings.get("organization/" + region).map(ObjectNode::deepCopy)
                .orElseGet(() -> {
                    ObjectNode empty = mapper.createObjectNode();
                    empty.putArray("autoEnableNewAccount");
                    return empty;
                });
    }

    private ObjectNode delegate(JsonNode in, String region, boolean register) {
        if (register) {
            String account = in.path("accountId").asText();
            if (!account.matches("\\d{12}")) {
                throw badRequest("accountId must be a 12-digit account id.");
            }
            settings.put("delegate", mapper.createObjectNode().put("accountId", account));
        } else {
            if (settings.get("delegate").isEmpty()) {
                throw notFound("No delegated administrator is registered.");
            }
            settings.delete("delegate");
        }
        return mapper.createObjectNode();
    }

    // ---------- subscribers ----------

    private ObjectNode createSubscriber(JsonNode in, String region) {
        ObjectNode lake = lake(region);
        String name = in.path("subscriberName").asText();
        for (ObjectNode existing : subscribers.scan(k -> k.startsWith(region + "/"))) {
            if (name.equals(existing.path("subscriberName").asText())) {
                throw conflict("A subscriber named " + name + " already exists.");
            }
        }
        requireSources(in.path("sources"), region);
        String id = UUID.randomUUID().toString();
        String account = subscribers.accountId();
        String bucketArn = lake.path("s3BucketArn").asText();
        IamRole role = createAssumableRole("AmazonSecurityLake-" + id, in.path("subscriberIdentity"),
                "Lets the Security Lake subscriber " + name + " read the data lake.",
                List.of("s3:GetObject", "s3:GetObjectVersion", "s3:ListBucket"), bucketArn + "*");
        ObjectNode subscriber = mapper.createObjectNode();
        subscriber.put("subscriberId", id).put("subscriberName", name)
                .put("subscriberArn", AwsArnUtils.Arn.of("securitylake", region, account, "subscriber/" + id)
                        .toString())
                .put("roleArn", role.getArn()).put("s3BucketArn", bucketArn).put("subscriberStatus", "ACTIVE")
                .put("createdAt", now()).put("updatedAt", now());
        subscriber.set("accessTypes", in.path("accessTypes").isArray() && !in.get("accessTypes").isEmpty()
                ? in.get("accessTypes").deepCopy() : mapper.createArrayNode().add("S3"));
        subscriber.set("sources", in.path("sources").deepCopy());
        subscriber.set("subscriberIdentity", in.path("subscriberIdentity").deepCopy());
        if (in.hasNonNull("subscriberDescription")) {
            subscriber.put("subscriberDescription", in.get("subscriberDescription").asText());
        }
        subscriber.putObject("_").set("tags", tagMap(in.path("tags")));
        subscribers.put(region + "/" + id, subscriber);
        return wrap("subscriber", view(subscriber));
    }

    private void requireSources(JsonNode requested, String region) {
        for (JsonNode source : requested) {
            String key;
            if (source.has("awsLogSource")) {
                key = sourceKey(region, "managed", source.path("awsLogSource").path("sourceName").asText(),
                        source.path("awsLogSource").path("sourceVersion").asText("2.0"));
            } else {
                key = sourceKey(region, "custom", source.path("customLogSource").path("sourceName").asText(),
                        source.path("customLogSource").path("sourceVersion").asText("1.0"));
            }
            if (sources.get(key).isEmpty()) {
                throw badRequest("The log source " + key.substring(key.indexOf('/') + 1) + " isn't enabled in "
                        + region + ".");
            }
        }
    }

    private ObjectNode subscriber(JsonNode in, String region) {
        String id = decode(in.path("subscriberId").asText());
        return subscribers.get(region + "/" + id).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("Subscriber " + id + " doesn't exist."));
    }

    private ObjectNode updateSubscriber(JsonNode in, String region) {
        ObjectNode subscriber = subscriber(in, region);
        if (in.has("sources")) {
            requireSources(in.get("sources"), region);
            subscriber.set("sources", in.get("sources").deepCopy());
        }
        for (String field : List.of("subscriberDescription", "subscriberName")) {
            if (in.hasNonNull(field)) {
                subscriber.put(field, in.get(field).asText());
            }
        }
        if (in.has("subscriberIdentity")) {
            subscriber.set("subscriberIdentity", in.get("subscriberIdentity").deepCopy());
            String roleName = AwsArnUtils.parse(subscriber.path("roleArn").asText()).resource();
            iam.get().updateAssumeRolePolicy(roleName.substring(roleName.lastIndexOf('/') + 1),
                    trust(in.get("subscriberIdentity")));
        }
        subscriber.put("updatedAt", now());
        subscribers.put(region + "/" + subscriber.path("subscriberId").asText(), subscriber);
        return wrap("subscriber", view(subscriber));
    }

    private ObjectNode deleteSubscriber(JsonNode in, String region) {
        ObjectNode subscriber = subscriber(in, region);
        removeNotification(subscriber, region);
        String roleArn = subscriber.path("roleArn").asText();
        deleteRole(roleArn.substring(roleArn.lastIndexOf('/') + 1));
        subscribers.delete(region + "/" + subscriber.path("subscriberId").asText());
        return mapper.createObjectNode();
    }

    private ObjectNode listSubscribers(JsonNode in, String region) {
        ArrayNode out = mapper.createArrayNode();
        subscribers.scan(k -> k.startsWith(region + "/")).stream()
                .sorted(Comparator.comparingDouble(s -> s.path("createdAt").asDouble()))
                .forEach(subscriber -> out.add(view(subscriber)));
        return wrap("subscribers", out);
    }

    // ---------- notifications ----------

    private ObjectNode putNotification(JsonNode in, String region, boolean create) {
        ObjectNode subscriber = subscriber(in, region);
        if (create && subscriber.hasNonNull("subscriberEndpoint")) {
            throw conflict("The subscriber already has a notification.");
        }
        if (!create && !subscriber.hasNonNull("subscriberEndpoint")) {
            throw notFound("The subscriber has no notification.");
        }
        removeNotification(subscriber, region);
        JsonNode configuration = in.path("configuration");
        String endpoint;
        if (configuration.has("httpsNotificationConfiguration")) {
            JsonNode https = configuration.get("httpsNotificationConfiguration");
            endpoint = https.path("endpoint").asText();
            if (!endpoint.startsWith("https://")) {
                throw badRequest("The notification endpoint must be an HTTPS URL.");
            }
            requireRole(https.path("targetRoleArn").asText(), "targetRoleArn");
        } else {
            String id = subscriber.path("subscriberId").asText();
            String queueName = "AmazonSecurityLake-" + id + "-Main-Queue";
            String account = subscribers.accountId();
            String bucketArn = subscriber.path("s3BucketArn").asText();
            String queueArn = AwsArnUtils.Arn.of("sqs", region, account, queueName).toString();
            String policy = mapper.createObjectNode().put("Version", "2012-10-17").set("Statement",
                    mapper.createArrayNode().add(mapper.createObjectNode().put("Effect", "Allow")
                            .put("Action", "sqs:SendMessage").put("Resource", queueArn)
                            .set("Principal", mapper.createObjectNode().put("Service", ServicePrincipals.of("s3")))))
                    .toString();
            sqs.get().createQueue(queueName, Map.of("Policy", policy), region);
            String bucket = AwsArnUtils.parse(bucketArn).resource();
            NotificationConfiguration notifications = s3.get().getBucketNotificationConfiguration(bucket);
            List<QueueNotification> queues = new ArrayList<>(notifications.getQueueConfigurations());
            queues.add(new QueueNotification("SecurityLake-" + id, queueArn, List.of("s3:ObjectCreated:*")));
            notifications.setQueueConfigurations(queues);
            s3.get().putBucketNotificationConfiguration(bucket, notifications);
            endpoint = queueArn;
        }
        subscriber.put("subscriberEndpoint", endpoint).put("updatedAt", now());
        subscribers.put(region + "/" + subscriber.path("subscriberId").asText(), subscriber);
        return mapper.createObjectNode().put("subscriberEndpoint", endpoint);
    }

    private ObjectNode deleteNotification(JsonNode in, String region) {
        ObjectNode subscriber = subscriber(in, region);
        if (!subscriber.hasNonNull("subscriberEndpoint")) {
            throw notFound("The subscriber has no notification.");
        }
        removeNotification(subscriber, region);
        subscriber.remove("subscriberEndpoint");
        subscribers.put(region + "/" + subscriber.path("subscriberId").asText(), subscriber);
        return mapper.createObjectNode();
    }

    /** Removes an SQS notification's queue and the bucket's notification to it. */
    private void removeNotification(ObjectNode subscriber, String region) {
        String endpoint = subscriber.path("subscriberEndpoint").asText("");
        if (!AwsArnUtils.isArnFor(endpoint, "sqs")) {
            return;
        }
        String bucket = AwsArnUtils.parse(subscriber.path("s3BucketArn").asText()).resource();
        try {
            NotificationConfiguration notifications = s3.get().getBucketNotificationConfiguration(bucket);
            List<QueueNotification> queues = new ArrayList<>(notifications.getQueueConfigurations());
            queues.removeIf(queue -> endpoint.equals(queue.queueArn()));
            notifications.setQueueConfigurations(queues);
            s3.get().putBucketNotificationConfiguration(bucket, notifications);
        } catch (AwsException e) {
            // The bucket is gone.
        }
        try {
            String queueName = AwsArnUtils.parse(endpoint).resource();
            sqs.get().deleteQueue(sqs.get().getQueueUrl(queueName, region), region);
        } catch (AwsException e) {
            // Already gone.
        }
    }

    // ---------- IAM ----------

    private void requireRole(String arn, String field) {
        if (!roleExists(arn)) {
            throw badRequest(field + " " + arn + " isn't an existing IAM role.");
        }
    }

    private boolean roleExists(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "iam") || !AwsArnUtils.parse(arn).resource().startsWith("role/")) {
            return false;
        }
        String resource = AwsArnUtils.parse(arn).resource();
        try {
            return IamService.roleArnMatches(arn, iam.get().getRole(resource.substring(resource.lastIndexOf('/')
                    + 1)));
        } catch (AwsException e) {
            return false;
        }
    }

    private String trust(JsonNode identity) {
        String principal = identity.path("principal").asText();
        ObjectNode statement = mapper.createObjectNode().put("Effect", "Allow").put("Action", "sts:AssumeRole");
        if (principal.matches("\\d{12}") || AwsArnUtils.isArn(principal)) {
            statement.putObject("Principal").put("AWS", principal);
        } else {
            statement.putObject("Principal").put("Service", principal);
        }
        statement.putObject("Condition").putObject("StringEquals").put("sts:ExternalId",
                identity.path("externalId").asText());
        return mapper.createObjectNode().put("Version", "2012-10-17")
                .set("Statement", mapper.createArrayNode().add(statement)).toString();
    }

    private IamRole createAssumableRole(String name, JsonNode identity, String description, List<String> actions,
                                        String resource) {
        IamRole role = iam.get().createRole(name, "/service-role/", trust(identity), description, 3600, Map.of());
        ObjectNode policy = mapper.createObjectNode().put("Version", "2012-10-17");
        policy.putArray("Statement").addObject().put("Effect", "Allow").put("Resource", resource)
                .set("Action", mapper.valueToTree(actions));
        iam.get().putRolePolicy(name, "SecurityLakeAccess", policy.toString());
        return role;
    }

    /** An IAM role name, at most 64 characters: a longer one is cut and given a hash of the whole for uniqueness. */
    private static String roleName(String wanted) {
        if (wanted.length() <= 64) {
            return wanted;
        }
        String hash = String.format("%08x", wanted.hashCode());
        return wanted.substring(0, 55) + "-" + hash;
    }

    private void deleteRole(String name) {
        try {
            iam.get().deleteRolePolicy(name, "SecurityLakeAccess");
        } catch (AwsException e) {
            // Already gone.
        }
        try {
            iam.get().deleteRole(name);
        } catch (AwsException e) {
            // Already gone.
        }
    }

    // ---------- tags ----------

    private ObjectNode tagged(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "securitylake")) {
            throw badRequest("resourceArn isn't a Security Lake ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (parsed.resource().startsWith("data-lake/")) {
            return lake(parsed.region());
        }
        if (parsed.resource().startsWith("subscriber/")) {
            String id = parsed.resource().substring("subscriber/".length());
            return subscribers.get(parsed.region() + "/" + id).map(ObjectNode::deepCopy)
                    .orElseThrow(() -> notFound("Subscriber " + id + " doesn't exist."));
        }
        throw notFound("Resource " + arn + " doesn't exist.");
    }

    private void saveTagged(ObjectNode record) {
        if (record.has("subscriberId")) {
            String region = AwsArnUtils.parse(record.path("subscriberArn").asText()).region();
            subscribers.put(region + "/" + record.path("subscriberId").asText(), record);
        } else {
            lakes.put(record.path("region").asText(), record);
        }
    }

    private ObjectNode tag(JsonNode in, String region, boolean add) {
        ObjectNode record = tagged(decode(in.path("resourceArn").asText()), region);
        ObjectNode tags = record.withObject("/_/tags");
        if (add) {
            tags.setAll(tagMap(in.path("tags")));
            if (tags.size() > 50) {
                throw badRequest("A resource can have at most 50 tags.");
            }
        } else {
            in.path("tagKeys").forEach(key -> tags.remove(key.asText()));
        }
        saveTagged(record);
        return mapper.createObjectNode();
    }

    private ObjectNode listTags(JsonNode in, String region) {
        ObjectNode record = tagged(decode(in.path("resourceArn").asText()), region);
        ArrayNode tags = mapper.createArrayNode();
        record.path("_").path("tags").fields().forEachRemaining(entry -> tags.addObject().put("key", entry.getKey())
                .put("value", entry.getValue().asText()));
        return mapper.createObjectNode().set("tags", tags);
    }

    // ---------- shared ----------

    private ObjectNode tagMap(JsonNode tags) {
        ObjectNode map = mapper.createObjectNode();
        for (JsonNode tag : tags) {
            map.put(tag.path("key").asText(), tag.path("value").asText());
        }
        return map;
    }

    private ObjectNode view(ObjectNode record) {
        ObjectNode view = record.deepCopy();
        view.remove("_");
        return view;
    }

    private ObjectNode wrap(String field, JsonNode value) {
        ObjectNode out = mapper.createObjectNode();
        out.set(field, value);
        return out;
    }

    private static String sourceKey(String region, String type, String name, String version) {
        return region + "/" + type + "/" + name + "/" + version;
    }

    private double step() {
        return config.services().securitylake().transitionMillis() / 1000.0;
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static String random(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length; i++) {
            out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    private static String decode(String value) {
        return value.contains("%") ? URLDecoder.decode(value, StandardCharsets.UTF_8) : value;
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }
}
