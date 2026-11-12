package io.floci.aws.services.qbusiness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Amazon Q Business: applications, their indices, retrievers and data sources (with S3 crawls), documents and their
 * access control lists, users and groups, web experiences, plugins, data accessors, chat response and chat controls
 * configurations, subscriptions, the application's resource policy and tags. Chat and search are
 * {@link QBusinessChat}.
 */
@ApplicationScoped
public class QBusinessService {

    private static final Logger LOG = Logger.getLogger(QBusinessService.class);
    static final Set<String> PLUGIN_TYPES = Set.of("SERVICE_NOW", "SALESFORCE", "JIRA", "ZENDESK", "CUSTOM",
            "QUICKSIGHT", "SERVICENOW_NOW_PLATFORM", "JIRA_CLOUD", "SALESFORCE_CRM", "ZENDESK_SUITE",
            "ATLASSIAN_CONFLUENCE", "GOOGLE_CALENDAR", "MICROSOFT_TEAMS", "MICROSOFT_EXCHANGE", "PAGERDUTY_ADVANCE",
            "SMARTSHEET", "ASANA");
    private static final Set<String> SHAREABLE = Set.of("qbusiness:SearchRelevantContent", "qbusiness:ChatSync",
            "qbusiness:Chat", "qbusiness:ListConversations", "qbusiness:ListMessages", "qbusiness:GetDocumentContent",
            "qbusiness:CreateUser", "qbusiness:BatchPutDocument");
    private static final int MAX_DOCUMENT_BYTES = 50 * 1024 * 1024;

    private final QBusinessStore store;
    private final ObjectMapper mapper;
    private final Provider<S3Service> s3;
    private final Provider<IamService> iam;
    private final Vertx vertx;
    private final ExecutorService worker = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "qbusiness");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public QBusinessService(QBusinessStore store, Provider<S3Service> s3, Provider<IamService> iam, Vertx vertx) {
        this.store = store;
        this.mapper = store.mapper();
        this.s3 = s3;
        this.iam = iam;
        this.vertx = vertx;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    // ================================================================ helpers

    ObjectNode out() {
        return mapper.createObjectNode();
    }

    static String req(JsonNode in, String member) {
        String value = in.path(member).asText("");
        if (value.isBlank()) {
            throw QBusinessStore.validation(member + " is required.");
        }
        return value;
    }

    static String opt(JsonNode in, String member) {
        return in.hasNonNull(member) && !in.get(member).asText().isBlank() ? in.get(member).asText() : null;
    }

    void save(ObjectNode record) {
        store.putForAccount(QBusinessStore.owner(record), QBusinessStore.region(record),
                QBusinessStore.meta(record).path("kind").asText(), QBusinessStore.id(record), record);
    }

    ObjectNode copy(ObjectNode record, String... fields) {
        ObjectNode view = out();
        for (String field : fields) {
            if (record.has(field) && !record.get(field).isNull()) {
                view.set(field, record.get(field).deepCopy());
            }
        }
        return view;
    }

    ObjectNode page(JsonNode in, List<ObjectNode> views, String name) {
        ObjectNode out = out();
        QBusinessStore.page(new ArrayList<JsonNode>(views), in, out, name);
        return out;
    }

    ObjectNode created() {
        ObjectNode record = store.record();
        double now = QBusinessStore.now();
        record.put("createdAt", now).put("updatedAt", now);
        return record;
    }

    String arn(String region, String resource) {
        return store.arn(region, resource);
    }

    /** Runs work after the transition delay, off the event loop, as the account in the Region. */
    void later(String account, String region, Runnable task) {
        vertx.setTimer(Math.max(1, store.config().transitionMillis()), id -> worker.submit(() -> {
            try {
                RequestScopes.runAs(account, region, task);
            } catch (RuntimeException e) {
                LOG.warnv("Q Business work failed: {0}", e.getMessage());
            }
        }));
    }

    void checkRole(String roleArn, String region) {
        if (roleArn == null) {
            return;
        }
        if (!AwsArnUtils.isArnFor(roleArn, "iam") || !AwsArnUtils.parse(roleArn).resource().startsWith("role/")) {
            throw QBusinessStore.validation("The role " + roleArn + " isn't a role ARN.");
        }
        String resource = AwsArnUtils.parse(roleArn).resource();
        try {
            RequestScopes.callAs(AwsArnUtils.accountOrDefault(roleArn, store.account()), region, () ->
                    iam.get().getRole(resource.substring(resource.lastIndexOf('/') + 1)));
        } catch (AwsException e) {
            throw QBusinessStore.validation("The role " + roleArn + " can't be used: " + e.getMessage());
        }
    }

    byte[] readS3(String bucket, String key) {
        try {
            S3Object object = s3.get().getObject(bucket, key);
            return object.getData() == null ? new byte[0] : object.getData();
        } catch (AwsException e) {
            throw QBusinessStore.validation("s3://" + bucket + "/" + key + " can't be read: " + e.getMessage());
        }
    }

    // ================================================================ applications

    private static final String[] APP_FIELDS = {"displayName", "applicationId", "applicationArn", "identityType",
        "iamIdentityProviderArn", "identityCenterApplicationArn", "roleArn", "status", "description",
        "encryptionConfiguration", "createdAt", "updatedAt", "error", "attachmentsConfiguration",
        "qAppsConfiguration", "personalizationConfiguration", "autoSubscriptionConfiguration", "clientIdsForOIDC",
        "quickSightConfiguration"};

    ObjectNode application(String region, String id) {
        return store.find(region, QBusinessStore.APPLICATION, id)
                .orElseThrow(() -> QBusinessStore.notFound("application", id));
    }

    /** An application that's ACTIVE (or being updated), ready for data and chat. */
    ObjectNode activeApplication(String region, String id) {
        ObjectNode app = application(region, id);
        String status = app.path("status").asText();
        if (!status.equals("ACTIVE") && !status.equals("UPDATING")) {
            throw QBusinessStore.conflict("application", id, "The application is " + status + ".");
        }
        return app;
    }

    public ObjectNode createApplication(JsonNode in, String region) {
        String identity = opt(in, "identityType") == null ? "AWS_IAM_IDC" : in.get("identityType").asText();
        String idc = opt(in, "identityCenterInstanceArn");
        switch (identity) {
            case "AWS_IAM_IDC" -> {
                if (idc == null) {
                    throw QBusinessStore.validation("An AWS_IAM_IDC application needs identityCenterInstanceArn.");
                }
                if (!AwsArnUtils.isArnFor(idc, "sso") || !AwsArnUtils.parse(idc).resource().startsWith("instance/")) {
                    throw QBusinessStore.validation("identityCenterInstanceArn " + idc + " isn't an IAM Identity "
                            + "Center instance ARN.");
                }
            }
            case "AWS_IAM_IDP_SAML", "AWS_IAM_IDP_OIDC" -> {
                if (opt(in, "iamIdentityProviderArn") == null) {
                    throw QBusinessStore.validation("A " + identity + " application needs iamIdentityProviderArn.");
                }
            }
            case "AWS_QUICKSIGHT_IDP" -> {
                if (!in.has("quickSightConfiguration")) {
                    throw QBusinessStore.validation("A QuickSight application needs quickSightConfiguration.");
                }
            }
            default -> {
            }
        }
        checkRole(opt(in, "roleArn"), region);
        for (ObjectNode existing : store.list(region, QBusinessStore.APPLICATION)) {
            if (existing.path("displayName").asText().equals(in.path("displayName").asText())
                    && in.hasNonNull("clientToken") && in.get("clientToken").asText()
                    .equals(QBusinessStore.meta(existing).path("clientToken").asText())) {
                return copy(existing, "applicationId", "applicationArn");
            }
        }
        if (store.list(region, QBusinessStore.APPLICATION).size() >= 50) {
            throw QBusinessStore.quota("The account has 50 applications.");
        }
        String id = UUID.randomUUID().toString();
        ObjectNode record = created();
        record.put("displayName", req(in, "displayName")).put("applicationId", id)
                .put("applicationArn", arn(region, "application/" + id)).put("identityType", identity);
        for (String member : List.of("iamIdentityProviderArn", "roleArn", "description")) {
            if (opt(in, member) != null) {
                record.put(member, in.get(member).asText());
            }
        }
        for (String member : List.of("encryptionConfiguration", "quickSightConfiguration", "clientIdsForOIDC",
                "personalizationConfiguration")) {
            if (in.has(member)) {
                record.set(member, in.get(member).deepCopy());
            }
        }
        if (idc != null) {
            record.put("identityCenterApplicationArn", AwsArnUtils.Arn.global(AwsArnUtils.parse(idc).partition(),
                    "sso", store.account(), "application/" + AwsArnUtils.parse(idc).resource().substring(9) + "/apl-"
                    + QBusinessStore.hex(16)).toString());
            QBusinessStore.meta(record).put("identityCenterInstanceArn", idc);
        }
        record.putObject("attachmentsConfiguration").put("attachmentsControlMode",
                in.at("/attachmentsConfiguration/attachmentsControlMode").asText("DISABLED"));
        record.putObject("qAppsConfiguration").put("qAppsControlMode",
                in.at("/qAppsConfiguration/qAppsControlMode").asText("DISABLED"));
        if (!record.has("personalizationConfiguration")) {
            record.putObject("personalizationConfiguration").put("personalizationControlMode", "ENABLED");
        }
        if (opt(in, "clientToken") != null) {
            QBusinessStore.meta(record).put("clientToken", in.get("clientToken").asText());
        }
        store.transition(record, "status", "CREATING", "ACTIVE");
        QBusinessStore.addTags(record, in.path("tags"));
        store.put(region, QBusinessStore.APPLICATION, id, record);
        ObjectNode controls = store.record();
        controls.put("responseScope", "ENTERPRISE_CONTENT_ONLY");
        controls.putObject("blockedPhrases").putArray("blockedPhrases");
        controls.putArray("topicConfigurations");
        controls.putObject("creatorModeConfiguration").put("creatorModeControl", "ENABLED");
        controls.putObject("orchestrationConfiguration").put("control", "DISABLED");
        controls.putObject("hallucinationReductionConfiguration").put("hallucinationReductionControl", "DISABLED");
        store.put(region, "chat-controls", id, controls);
        return copy(record, "applicationId", "applicationArn");
    }

    public ObjectNode getApplication(JsonNode in, String region) {
        return copy(application(region, req(in, "applicationId")), APP_FIELDS);
    }

    public ObjectNode listApplications(JsonNode in, String region) {
        List<ObjectNode> views = new ArrayList<>();
        store.all(region, QBusinessStore.APPLICATION).forEach(app -> views.add(copy(app, "displayName",
                "applicationId", "createdAt", "updatedAt", "status", "identityType", "quickSightConfiguration")));
        return page(in, views, "applications");
    }

    public ObjectNode updateApplication(JsonNode in, String region) {
        ObjectNode record = application(region, req(in, "applicationId"));
        if (opt(in, "identityCenterInstanceArn") != null) {
            if (!"AWS_IAM_IDC".equals(record.path("identityType").asText())) {
                throw QBusinessStore.validation("Only an AWS_IAM_IDC application has an IAM Identity Center "
                        + "instance.");
            }
            QBusinessStore.meta(record).put("identityCenterInstanceArn", in.get("identityCenterInstanceArn").asText());
        }
        checkRole(opt(in, "roleArn"), region);
        for (String member : List.of("displayName", "description", "roleArn")) {
            if (in.has(member)) {
                record.put(member, in.get(member).asText());
            }
        }
        for (String member : List.of("attachmentsConfiguration", "qAppsConfiguration", "personalizationConfiguration",
                "autoSubscriptionConfiguration")) {
            if (in.has(member)) {
                record.set(member, in.get(member).deepCopy());
            }
        }
        record.put("updatedAt", QBusinessStore.now());
        store.transition(record, "status", "UPDATING", "ACTIVE");
        save(record);
        return out();
    }

    public ObjectNode deleteApplication(JsonNode in, String region) {
        ObjectNode record = application(region, req(in, "applicationId"));
        String id = QBusinessStore.id(record);
        if ("DELETING".equals(record.path("status").asText())) {
            throw QBusinessStore.conflict("application", id, "The application is already being deleted.");
        }
        for (String kind : List.of(QBusinessStore.INDEX, QBusinessStore.RETRIEVER, QBusinessStore.DATA_SOURCE,
                QBusinessStore.SYNC_JOB, QBusinessStore.DOCUMENT, QBusinessStore.GROUP, QBusinessStore.USER,
                QBusinessStore.WEB, QBusinessStore.PLUGIN, QBusinessStore.ACCESSOR, QBusinessStore.RESPONSE_CONFIG,
                QBusinessStore.SUBSCRIPTION, QBusinessStore.CONVERSATION, QBusinessStore.MESSAGE,
                QBusinessStore.ATTACHMENT, QBusinessStore.QAPP, QBusinessStore.SESSION, QBusinessStore.LIBRARY,
                QBusinessStore.CATEGORY, QBusinessStore.FILE)) {
            for (ObjectNode child : store.list(region, kind, id + "/")) {
                store.delete(region, kind, QBusinessStore.id(child));
            }
        }
        store.delete(region, "chat-controls", id);
        store.delete(region, QBusinessStore.APPLICATION, id);
        return out();
    }

    // ================================================================ indices

    private static final String[] INDEX_FIELDS = {"applicationId", "indexId", "displayName", "indexArn", "status",
        "type", "description", "createdAt", "updatedAt", "capacityConfiguration", "documentAttributeConfigurations",
        "error", "indexStatistics"};

    ObjectNode index(String region, String appId, String indexId) {
        application(region, appId);
        return store.find(region, QBusinessStore.INDEX, appId + "/" + indexId)
                .orElseThrow(() -> QBusinessStore.notFound("index", indexId));
    }

    ObjectNode activeIndex(String region, String appId, String indexId) {
        ObjectNode index = index(region, appId, indexId);
        if (!"ACTIVE".equals(index.path("status").asText()) && !"UPDATING".equals(index.path("status").asText())) {
            throw QBusinessStore.conflict("index", indexId, "The index is " + index.path("status").asText() + ".");
        }
        return index;
    }

    public ObjectNode createIndex(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        activeApplication(region, appId);
        if (!store.list(region, QBusinessStore.INDEX, appId + "/").isEmpty()) {
            throw QBusinessStore.quota("An application has one index.");
        }
        String id = UUID.randomUUID().toString();
        ObjectNode record = created();
        String type = opt(in, "type") == null ? "ENTERPRISE" : in.get("type").asText();
        int units = in.at("/capacityConfiguration/units").asInt(1);
        if ("STARTER".equals(type) && units > 1) {
            throw QBusinessStore.validation("A STARTER index has one capacity unit.");
        }
        record.put("applicationId", appId).put("indexId", id).put("displayName", req(in, "displayName"))
                .put("indexArn", arn(region, "application/" + appId + "/index/" + id)).put("type", type);
        if (opt(in, "description") != null) {
            record.put("description", in.get("description").asText());
        }
        record.putObject("capacityConfiguration").put("units", units);
        ArrayNode attributes = record.putArray("documentAttributeConfigurations");
        for (String[] attribute : new String[][] {{"_authors", "STRING_LIST"}, {"_category", "STRING"},
            {"_created_at", "DATE"}, {"_data_source_id", "STRING"}, {"_document_title", "STRING"},
            {"_last_updated_at", "DATE"}, {"_source_uri", "STRING"}, {"_file_type", "STRING"}}) {
            attributes.addObject().put("name", attribute[0]).put("type", attribute[1]).put("search", "ENABLED");
        }
        store.transition(record, "status", "CREATING", "ACTIVE");
        QBusinessStore.addTags(record, in.path("tags"));
        store.put(region, QBusinessStore.INDEX, appId + "/" + id, record);
        return copy(record, "indexId", "indexArn");
    }

    public ObjectNode getIndex(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        ObjectNode record = index(region, appId, req(in, "indexId"));
        ObjectNode view = copy(record, INDEX_FIELDS);
        long bytes = 0;
        int count = 0;
        for (ObjectNode document : documents(region, appId, QBusinessStore.id(record).substring(appId.length() + 1))) {
            if ("INDEXED".equals(document.path("status").asText()) || "UPDATED".equals(document.path("status")
                    .asText())) {
                count++;
                bytes += QBusinessStore.meta(document).path("textBytes").asLong();
            }
        }
        view.putObject("indexStatistics").putObject("textDocumentStatistics").put("indexedTextBytes", bytes)
                .put("indexedTextDocumentCount", count);
        return view;
    }

    public ObjectNode listIndices(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        application(region, appId);
        List<ObjectNode> views = new ArrayList<>();
        store.all(region, QBusinessStore.INDEX).stream().filter(i -> appId.equals(i.path("applicationId").asText()))
                .forEach(i -> views.add(copy(i, "displayName", "indexId", "createdAt", "updatedAt", "status")));
        return page(in, views, "indices");
    }

    public ObjectNode updateIndex(JsonNode in, String region) {
        ObjectNode record = index(region, req(in, "applicationId"), req(in, "indexId"));
        for (String member : List.of("displayName", "description")) {
            if (in.has(member)) {
                record.put(member, in.get(member).asText());
            }
        }
        if (in.has("capacityConfiguration")) {
            if ("STARTER".equals(record.path("type").asText()) && in.at("/capacityConfiguration/units").asInt(1) > 1) {
                throw QBusinessStore.validation("A STARTER index has one capacity unit.");
            }
            record.set("capacityConfiguration", in.get("capacityConfiguration").deepCopy());
        }
        if (in.path("documentAttributeConfigurations").isArray()) {
            ArrayNode attributes = (ArrayNode) record.get("documentAttributeConfigurations");
            for (JsonNode update : in.get("documentAttributeConfigurations")) {
                boolean replaced = false;
                for (JsonNode existing : attributes) {
                    if (existing.path("name").asText().equals(update.path("name").asText())) {
                        ((ObjectNode) existing).setAll((ObjectNode) update.deepCopy());
                        replaced = true;
                    }
                }
                if (!replaced) {
                    attributes.add(update.deepCopy());
                }
            }
            if (attributes.size() > 500) {
                throw QBusinessStore.quota("An index has at most 500 document attributes.");
            }
        }
        record.put("updatedAt", QBusinessStore.now());
        store.transition(record, "status", "UPDATING", "ACTIVE");
        save(record);
        return out();
    }

    public ObjectNode deleteIndex(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        ObjectNode record = index(region, appId, req(in, "indexId"));
        String indexId = record.get("indexId").asText();
        for (ObjectNode retriever : store.list(region, QBusinessStore.RETRIEVER, appId + "/")) {
            if (indexId.equals(retriever.at("/configuration/nativeIndexConfiguration/indexId").asText())) {
                throw QBusinessStore.conflict("index", indexId, "The retriever " + retriever.get("retrieverId")
                        .asText() + " uses the index; delete it first.");
            }
        }
        String prefix = appId + "/" + indexId + "/";
        for (String kind : List.of(QBusinessStore.DATA_SOURCE, QBusinessStore.SYNC_JOB, QBusinessStore.DOCUMENT,
                QBusinessStore.GROUP)) {
            for (ObjectNode child : store.list(region, kind, prefix)) {
                store.delete(region, kind, QBusinessStore.id(child));
            }
        }
        store.delete(region, QBusinessStore.INDEX, appId + "/" + indexId);
        return out();
    }

    // ================================================================ retrievers

    private static final String[] RETRIEVER_FIELDS = {"applicationId", "retrieverId", "retrieverArn", "type",
        "status", "displayName", "configuration", "roleArn", "createdAt", "updatedAt"};

    ObjectNode retriever(String region, String appId, String id) {
        application(region, appId);
        return store.find(region, QBusinessStore.RETRIEVER, appId + "/" + id)
                .orElseThrow(() -> QBusinessStore.notFound("retriever", id));
    }

    private void checkRetriever(JsonNode in, String type, String region, String appId) {
        JsonNode config = in.path("configuration");
        if ("NATIVE_INDEX".equals(type)) {
            String indexId = config.at("/nativeIndexConfiguration/indexId").asText("");
            if (indexId.isEmpty()) {
                throw QBusinessStore.validation("A NATIVE_INDEX retriever needs nativeIndexConfiguration.indexId.");
            }
            index(region, appId, indexId);
        } else if (config.at("/kendraIndexConfiguration/indexId").asText("").isEmpty()) {
            throw QBusinessStore.validation("A KENDRA_INDEX retriever needs kendraIndexConfiguration.indexId.");
        }
    }

    public ObjectNode createRetriever(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        activeApplication(region, appId);
        String type = req(in, "type");
        checkRetriever(in, type, region, appId);
        if ("KENDRA_INDEX".equals(type)) {
            checkRole(req(in, "roleArn"), region);
        }
        if (!store.list(region, QBusinessStore.RETRIEVER, appId + "/").isEmpty()) {
            throw QBusinessStore.quota("An application has one retriever.");
        }
        String id = UUID.randomUUID().toString();
        ObjectNode record = created();
        record.put("applicationId", appId).put("retrieverId", id)
                .put("retrieverArn", arn(region, "application/" + appId + "/retriever/" + id)).put("type", type)
                .put("displayName", req(in, "displayName")).put("status", "ACTIVE");
        record.set("configuration", in.get("configuration").deepCopy());
        if (opt(in, "roleArn") != null) {
            record.put("roleArn", in.get("roleArn").asText());
        }
        QBusinessStore.addTags(record, in.path("tags"));
        store.put(region, QBusinessStore.RETRIEVER, appId + "/" + id, record);
        return copy(record, "retrieverId", "retrieverArn");
    }

    public ObjectNode getRetriever(JsonNode in, String region) {
        return copy(retriever(region, req(in, "applicationId"), req(in, "retrieverId")), RETRIEVER_FIELDS);
    }

    public ObjectNode listRetrievers(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        application(region, appId);
        List<ObjectNode> views = new ArrayList<>();
        store.all(region, QBusinessStore.RETRIEVER).stream()
                .filter(r -> appId.equals(r.path("applicationId").asText()))
                .forEach(r -> views.add(copy(r, "applicationId", "retrieverId", "type", "status", "displayName")));
        return page(in, views, "retrievers");
    }

    public ObjectNode updateRetriever(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        ObjectNode record = retriever(region, appId, req(in, "retrieverId"));
        if (in.has("configuration")) {
            checkRetriever(in, record.get("type").asText(), region, appId);
            record.set("configuration", in.get("configuration").deepCopy());
        }
        for (String member : List.of("displayName", "roleArn")) {
            if (in.has(member)) {
                record.put(member, in.get(member).asText());
            }
        }
        record.put("updatedAt", QBusinessStore.now());
        save(record);
        return out();
    }

    public ObjectNode deleteRetriever(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        ObjectNode record = retriever(region, appId, req(in, "retrieverId"));
        store.delete(region, QBusinessStore.RETRIEVER, QBusinessStore.id(record));
        return out();
    }

    // ================================================================ data sources

    private static final String[] SOURCE_FIELDS = {"applicationId", "indexId", "dataSourceId", "dataSourceArn",
        "displayName", "type", "configuration", "vpcConfiguration", "createdAt", "updatedAt", "description", "status",
        "syncSchedule", "roleArn", "error", "documentEnrichmentConfiguration", "mediaExtractionConfiguration"};
    private static final Set<String> CONNECTORS = Set.of("CUSTOM", "S3", "S3D", "WEBCRAWLERV2", "SHAREPOINTV2",
            "CONFLUENCEV2", "JIRAV2", "GITHUBV2", "SLACK", "SALESFORCEV2", "SERVICENOWV2", "BOX", "DROPBOX",
            "GOOGLEDRIVEV2", "ONEDRIVEV2", "MSTEAMS", "ZENDESK", "QUIP", "GITLAB", "AURORAMYSQL", "AURORAPOSTGRESQL",
            "MYSQL", "POSTGRESQL", "ORACLE", "SQLSERVER", "AMAZONRDSMYSQL", "AMAZONRDSPOSTGRESQL", "FSXWINDOWS",
            "WORKDOCS", "ALFRESCO", "AEM", "EXCHANGEONLINE");

    ObjectNode dataSource(String region, String appId, String indexId, String id) {
        index(region, appId, indexId);
        return store.find(region, QBusinessStore.DATA_SOURCE, appId + "/" + indexId + "/" + id)
                .orElseThrow(() -> QBusinessStore.notFound("data-source", id));
    }

    private String connectorType(JsonNode configuration) {
        String type = configuration.path("type").asText("");
        if (!CONNECTORS.contains(type)) {
            throw QBusinessStore.validation("The data source configuration's type " + type + " isn't a connector.");
        }
        if (type.startsWith("S3") && configuration.at("/connectionConfiguration/repositoryEndpointMetadata/BucketName")
                .asText("").isEmpty()) {
            throw QBusinessStore.validation("An S3 data source names "
                    + "connectionConfiguration.repositoryEndpointMetadata.BucketName.");
        }
        return type;
    }

    public ObjectNode createDataSource(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        String indexId = req(in, "indexId");
        activeIndex(region, appId, indexId);
        String type = connectorType(in.path("configuration"));
        if (!"CUSTOM".equals(type)) {
            checkRole(req(in, "roleArn"), region);
        } else {
            checkRole(opt(in, "roleArn"), region);
        }
        String schedule = opt(in, "syncSchedule");
        if (schedule != null && !schedule.matches("cron\\(.+\\)|rate\\(.+\\)") && !schedule.isEmpty()) {
            throw QBusinessStore.validation("syncSchedule must be a cron(...) or rate(...) expression.");
        }
        if (store.list(region, QBusinessStore.DATA_SOURCE, appId + "/" + indexId + "/").size() >= 50) {
            throw QBusinessStore.quota("An index has at most 50 data sources.");
        }
        String id = UUID.randomUUID().toString();
        ObjectNode record = created();
        record.put("applicationId", appId).put("indexId", indexId).put("dataSourceId", id)
                .put("dataSourceArn", arn(region, "application/" + appId + "/index/" + indexId + "/data-source/" + id))
                .put("displayName", req(in, "displayName")).put("type", type);
        for (String member : List.of("description", "syncSchedule", "roleArn")) {
            if (opt(in, member) != null) {
                record.put(member, in.get(member).asText());
            }
        }
        for (String member : List.of("configuration", "vpcConfiguration", "documentEnrichmentConfiguration",
                "mediaExtractionConfiguration")) {
            if (in.has(member)) {
                record.set(member, in.get(member).deepCopy());
            }
        }
        store.transition(record, "status", "CREATING", "ACTIVE");
        QBusinessStore.addTags(record, in.path("tags"));
        store.put(region, QBusinessStore.DATA_SOURCE, appId + "/" + indexId + "/" + id, record);
        return copy(record, "dataSourceId", "dataSourceArn");
    }

    public ObjectNode getDataSource(JsonNode in, String region) {
        return copy(dataSource(region, req(in, "applicationId"), req(in, "indexId"), req(in, "dataSourceId")),
                SOURCE_FIELDS);
    }

    public ObjectNode listDataSources(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        String indexId = req(in, "indexId");
        index(region, appId, indexId);
        List<ObjectNode> views = new ArrayList<>();
        store.all(region, QBusinessStore.DATA_SOURCE).stream()
                .filter(d -> appId.equals(d.path("applicationId").asText()) && indexId.equals(d.path("indexId")
                        .asText()))
                .forEach(d -> views.add(copy(d, "displayName", "dataSourceId", "type", "createdAt", "updatedAt",
                        "status")));
        return page(in, views, "dataSources");
    }

    public ObjectNode updateDataSource(JsonNode in, String region) {
        ObjectNode record = dataSource(region, req(in, "applicationId"), req(in, "indexId"), req(in, "dataSourceId"));
        if (in.has("configuration")) {
            if (!connectorType(in.get("configuration")).equals(record.get("type").asText())) {
                throw QBusinessStore.validation("A data source's connector type can't change.");
            }
            record.set("configuration", in.get("configuration").deepCopy());
        }
        checkRole(opt(in, "roleArn"), region);
        for (String member : List.of("displayName", "description", "syncSchedule", "roleArn")) {
            if (in.has(member)) {
                record.put(member, in.get(member).asText());
            }
        }
        for (String member : List.of("vpcConfiguration", "documentEnrichmentConfiguration",
                "mediaExtractionConfiguration")) {
            if (in.has(member)) {
                record.set(member, in.get(member).deepCopy());
            }
        }
        record.put("updatedAt", QBusinessStore.now());
        store.transition(record, "status", "UPDATING", "ACTIVE");
        save(record);
        return out();
    }

    public ObjectNode deleteDataSource(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        String indexId = req(in, "indexId");
        ObjectNode record = dataSource(region, appId, indexId, req(in, "dataSourceId"));
        String id = record.get("dataSourceId").asText();
        for (ObjectNode document : documents(region, appId, indexId)) {
            if (id.equals(QBusinessStore.meta(document).path("dataSourceId").asText())) {
                store.delete(region, QBusinessStore.DOCUMENT, QBusinessStore.id(document));
            }
        }
        for (ObjectNode job : store.list(region, QBusinessStore.SYNC_JOB, appId + "/" + indexId + "/" + id + "/")) {
            store.delete(region, QBusinessStore.SYNC_JOB, QBusinessStore.id(job));
        }
        store.delete(region, QBusinessStore.DATA_SOURCE, QBusinessStore.id(record));
        return out();
    }

    // ---------------------------------------------------------------- sync jobs

    private List<ObjectNode> jobs(String region, ObjectNode source) {
        return store.list(region, QBusinessStore.SYNC_JOB, QBusinessStore.id(source) + "/");
    }

    public ObjectNode startDataSourceSyncJob(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        String indexId = req(in, "indexId");
        activeIndex(region, appId, indexId);
        ObjectNode source = dataSource(region, appId, indexId, req(in, "dataSourceId"));
        if (!"ACTIVE".equals(source.path("status").asText())) {
            throw QBusinessStore.conflict("data-source", source.get("dataSourceId").asText(), "The data source is "
                    + source.path("status").asText() + ".");
        }
        for (ObjectNode job : jobs(region, source)) {
            String status = job.path("status").asText();
            if (status.equals("SYNCING") || status.equals("STOPPING") || status.equals("SYNCING_INDEXING")) {
                throw QBusinessStore.conflict("data-source", source.get("dataSourceId").asText(),
                        "The data source is already syncing.");
            }
        }
        String id = UUID.randomUUID().toString();
        ObjectNode job = store.record();
        job.put("executionId", id).put("startTime", QBusinessStore.now()).put("status", "SYNCING");
        ObjectNode metrics = job.putObject("metrics");
        for (String metric : List.of("documentsAdded", "documentsModified", "documentsDeleted", "documentsFailed",
                "documentsScanned")) {
            metrics.put(metric, "0");
        }
        String key = QBusinessStore.id(source) + "/" + id;
        store.put(region, QBusinessStore.SYNC_JOB, key, job);
        if (!"CUSTOM".equals(source.path("type").asText())) {
            String account = store.account();
            later(account, region, () -> crawl(region, appId, indexId, source.get("dataSourceId").asText(), key));
        }
        return out().put("executionId", id);
    }

    /** Runs a connector's sync: S3 objects under the configured prefixes become documents; others fail. */
    private void crawl(String region, String appId, String indexId, String sourceId, String key) {
        ObjectNode job = store.get(region, QBusinessStore.SYNC_JOB, key).orElse(null);
        ObjectNode source = store.get(region, QBusinessStore.DATA_SOURCE, appId + "/" + indexId + "/" + sourceId)
                .orElse(null);
        if (job == null || source == null || !"SYNCING".equals(job.path("status").asText())) {
            return;
        }
        String type = source.path("type").asText();
        if (!type.startsWith("S3")) {
            job.put("status", "FAILED").put("endTime", QBusinessStore.now()).put("dataSourceErrorCode",
                    "ConnectorUnavailable");
            job.putObject("error").put("errorCode", "InvalidRequest").put("errorMessage", "Floci syncs S3 data "
                    + "sources; the " + type + " connector reaches a system Floci doesn't emulate.");
            store.put(region, QBusinessStore.SYNC_JOB, key, job);
            return;
        }
        JsonNode configuration = source.path("configuration");
        String bucket = configuration.at("/connectionConfiguration/repositoryEndpointMetadata/BucketName").asText();
        List<String> includes = strings(configuration.at("/additionalProperties/inclusionPrefixes"));
        List<String> excludes = strings(configuration.at("/additionalProperties/exclusionPatterns"));
        int added = 0;
        int modified = 0;
        int failed = 0;
        int scanned = 0;
        Set<String> seen = new HashSet<>();
        try {
            for (S3Object object : s3.get().listObjects(bucket, "", null, 10000)) {
                String objectKey = object.getKey();
                if (objectKey.endsWith("/") || (!includes.isEmpty() && includes.stream().noneMatch(objectKey::startsWith))
                        || excludes.stream().anyMatch(pattern -> objectKey.matches(glob(pattern)))) {
                    continue;
                }
                scanned++;
                String documentId = "s3://" + bucket + "/" + objectKey;
                seen.add(documentId);
                ObjectNode document = mapper.createObjectNode().put("id", documentId).put("title",
                        objectKey.substring(objectKey.lastIndexOf('/') + 1));
                document.putObject("content").putObject("s3").put("bucket", bucket).put("key", objectKey);
                String contentType = typeOf(objectKey);
                if (contentType != null) {
                    document.put("contentType", contentType);
                }
                boolean existed = store.get(region, QBusinessStore.DOCUMENT, appId + "/" + indexId + "/"
                        + documentId).isPresent();
                String error = putDocument(region, appId, indexId, document, sourceId, true);
                if (error != null) {
                    failed++;
                } else if (existed) {
                    modified++;
                } else {
                    added++;
                }
            }
        } catch (AwsException e) {
            job.put("status", "FAILED").put("endTime", QBusinessStore.now());
            job.putObject("error").put("errorCode", "ResourceNotFound").put("errorMessage", e.getMessage());
            store.put(region, QBusinessStore.SYNC_JOB, key, job);
            return;
        }
        int deleted = 0;
        for (ObjectNode document : documents(region, appId, indexId)) {
            if (sourceId.equals(QBusinessStore.meta(document).path("dataSourceId").asText())
                    && !seen.contains(document.path("documentId").asText())) {
                store.delete(region, QBusinessStore.DOCUMENT, QBusinessStore.id(document));
                deleted++;
            }
        }
        ObjectNode current = store.get(region, QBusinessStore.SYNC_JOB, key).orElse(job);
        ObjectNode metrics = (ObjectNode) current.get("metrics");
        metrics.put("documentsAdded", String.valueOf(added)).put("documentsModified", String.valueOf(modified))
                .put("documentsDeleted", String.valueOf(deleted)).put("documentsFailed", String.valueOf(failed))
                .put("documentsScanned", String.valueOf(scanned));
        boolean stopped = "STOPPING".equals(current.path("status").asText());
        current.put("status", stopped ? "ABORTED" : failed > 0 ? "INCOMPLETE" : "SUCCEEDED")
                .put("endTime", QBusinessStore.now());
        store.put(region, QBusinessStore.SYNC_JOB, key, current);
    }

    private static String glob(String pattern) {
        return pattern.replace(".", "\\.").replace("**", "\u0000").replace("*", "[^/]*").replace("\u0000", ".*")
                .replace("?", ".");
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }

    static String typeOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        Map<String, String> types = Map.ofEntries(Map.entry(".pdf", "PDF"), Map.entry(".html", "HTML"),
                Map.entry(".htm", "HTML"), Map.entry(".docx", "MS_WORD"), Map.entry(".txt", "PLAIN_TEXT"),
                Map.entry(".pptx", "PPT"), Map.entry(".rtf", "RTF"), Map.entry(".xml", "XML"),
                Map.entry(".xslt", "XSLT"), Map.entry(".xlsx", "MS_EXCEL"), Map.entry(".csv", "CSV"),
                Map.entry(".json", "JSON"), Map.entry(".md", "MD"));
        for (Map.Entry<String, String> entry : types.entrySet()) {
            if (lower.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public ObjectNode stopDataSourceSyncJob(JsonNode in, String region) {
        ObjectNode source = dataSource(region, req(in, "applicationId"), req(in, "indexId"), req(in, "dataSourceId"));
        for (ObjectNode job : jobs(region, source)) {
            if ("SYNCING".equals(job.path("status").asText())) {
                job.put("status", "CUSTOM".equals(source.path("type").asText()) ? "SUCCEEDED" : "STOPPING");
                if ("CUSTOM".equals(source.path("type").asText())) {
                    job.put("endTime", QBusinessStore.now());
                }
                save(job);
                return out();
            }
        }
        throw QBusinessStore.conflict("data-source", source.get("dataSourceId").asText(),
                "The data source isn't syncing.");
    }

    public ObjectNode listDataSourceSyncJobs(JsonNode in, String region) {
        ObjectNode source = dataSource(region, req(in, "applicationId"), req(in, "indexId"), req(in, "dataSourceId"));
        String status = opt(in, "statusFilter");
        double start = in.path("startTime").asDouble(0);
        double end = in.path("endTime").asDouble(Double.MAX_VALUE);
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode job : jobs(region, source)) {
            double at = job.path("startTime").asDouble();
            if ((status == null || status.equals(job.path("status").asText())) && at >= start && at <= end) {
                views.add(copy(job, "executionId", "startTime", "endTime", "status", "error", "dataSourceErrorCode",
                        "metrics"));
            }
        }
        return page(in, views, "history");
    }

    /** Counts a CUSTOM data source's sync job's documents. */
    private void countSync(String region, String appId, String indexId, String syncId, String metric, int n) {
        for (ObjectNode job : store.list(region, QBusinessStore.SYNC_JOB, appId + "/" + indexId + "/")) {
            if (syncId.equals(job.path("executionId").asText())) {
                if (!"SYNCING".equals(job.path("status").asText())) {
                    throw QBusinessStore.conflict("data-source", syncId, "The sync job " + syncId + " isn't running.");
                }
                ObjectNode metrics = (ObjectNode) job.get("metrics");
                metrics.put(metric, String.valueOf(Integer.parseInt(metrics.path(metric).asText("0")) + n));
                metrics.put("documentsScanned", String.valueOf(Integer.parseInt(metrics.path("documentsScanned")
                        .asText("0")) + n));
                save(job);
                return;
            }
        }
        throw QBusinessStore.notFound("data-source", syncId);
    }

    private String syncSource(String region, String appId, String indexId, String syncId) {
        for (ObjectNode job : store.list(region, QBusinessStore.SYNC_JOB, appId + "/" + indexId + "/")) {
            if (syncId.equals(job.path("executionId").asText())) {
                String id = QBusinessStore.id(job);
                String[] parts = id.split("/");
                return parts[2];
            }
        }
        throw QBusinessStore.notFound("data-source", syncId);
    }

    // ================================================================ documents

    List<ObjectNode> documents(String region, String appId, String indexId) {
        List<ObjectNode> documents = new ArrayList<>();
        for (ObjectNode document : store.list(region, QBusinessStore.DOCUMENT, appId + "/" + indexId + "/")) {
            documents.add(store.settle(document));
        }
        return documents;
    }

    /** Indexes one document; returns why it failed, or null. */
    private String putDocument(String region, String appId, String indexId, JsonNode document, String sourceId,
                               boolean crawled) {
        String id = document.path("id").asText("");
        if (id.isEmpty() || id.length() > 1825) {
            return "A document needs an id of at most 1825 characters.";
        }
        byte[] content;
        String uri = null;
        JsonNode body = document.path("content");
        if (body.has("blob")) {
            try {
                content = Base64.getDecoder().decode(body.get("blob").asText());
            } catch (IllegalArgumentException e) {
                return "The document's blob isn't base64.";
            }
        } else if (body.has("s3")) {
            try {
                content = readS3(body.at("/s3/bucket").asText(), body.at("/s3/key").asText());
            } catch (AwsException e) {
                return e.getMessage();
            }
            uri = "s3://" + body.at("/s3/bucket").asText() + "/" + body.at("/s3/key").asText();
        } else {
            return "A document needs content: a blob or an S3 object.";
        }
        if (content.length > MAX_DOCUMENT_BYTES) {
            return "The document is larger than 50 MB.";
        }
        String type = document.path("contentType").asText(crawled ? "PLAIN_TEXT" : "PLAIN_TEXT");
        String text;
        try {
            text = QBusinessSearch.text(content, type);
        } catch (AwsException e) {
            return e.getMessage();
        }
        if (text.isBlank()) {
            return "The document has no text to index.";
        }
        String key = appId + "/" + indexId + "/" + id;
        ObjectNode existing = store.get(region, QBusinessStore.DOCUMENT, key).orElse(null);
        ObjectNode record = existing == null ? created() : existing;
        record.put("documentId", id).put("updatedAt", QBusinessStore.now());
        record.remove("error");
        ObjectNode meta = QBusinessStore.meta(record);
        String title = document.path("title").asText(QBusinessSearch.title(content, type));
        meta.put("title", title == null ? id : title).put("contentType", type).put("textBytes",
                text.getBytes(StandardCharsets.UTF_8).length).put("raw", Base64.getEncoder().encodeToString(content));
        if (uri != null) {
            meta.put("uri", uri);
        }
        if (sourceId != null) {
            meta.put("dataSourceId", sourceId);
        }
        ArrayNode attributes = mapper.createArrayNode();
        document.path("attributes").forEach(attributes::add);
        attributes.addObject().put("name", "_document_title").putObject("value").put("stringValue",
                meta.get("title").asText());
        if (sourceId != null) {
            attributes.addObject().put("name", "_data_source_id").putObject("value").put("stringValue", sourceId);
        }
        meta.set("attributes", attributes);
        meta.set("access", document.path("accessConfiguration").deepCopy());
        ArrayNode passages = meta.putArray("passages");
        QBusinessSearch.passages(text).forEach(passages::add);
        store.transition(record, "status", existing == null ? "RECEIVED" : "PROCESSING",
                existing == null ? "INDEXED" : "UPDATED");
        store.put(region, QBusinessStore.DOCUMENT, key, record);
        return null;
    }

    public ObjectNode batchPutDocument(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        String indexId = req(in, "indexId");
        activeIndex(region, appId, indexId);
        checkRole(opt(in, "roleArn"), region);
        JsonNode documents = in.path("documents");
        if (documents.size() > 10) {
            throw QBusinessStore.validation("BatchPutDocument takes at most 10 documents.");
        }
        String syncId = opt(in, "dataSourceSyncId");
        String sourceId = syncId == null ? null : syncSource(region, appId, indexId, syncId);
        ObjectNode index = index(region, appId, indexId);
        int capacity = index.at("/capacityConfiguration/units").asInt(1) * ("STARTER".equals(index.path("type")
                .asText()) ? 5000 : 20000);
        int count = store.list(region, QBusinessStore.DOCUMENT, appId + "/" + indexId + "/").size();
        ObjectNode out = out();
        ArrayNode failed = out.putArray("failedDocuments");
        int indexed = 0;
        for (JsonNode document : documents) {
            String error = ++count > capacity ? "The index is at its capacity of " + capacity + " documents."
                    : putDocument(region, appId, indexId, document, sourceId, false);
            if (error != null) {
                ObjectNode failure = failed.addObject().put("id", document.path("id").asText());
                failure.putObject("error").put("errorCode", "InvalidRequest").put("errorMessage", error);
                if (sourceId != null) {
                    failure.put("dataSourceId", sourceId);
                }
            } else {
                indexed++;
            }
        }
        if (syncId != null) {
            countSync(region, appId, indexId, syncId, "documentsAdded", indexed);
        }
        return out;
    }

    public ObjectNode batchDeleteDocument(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        String indexId = req(in, "indexId");
        activeIndex(region, appId, indexId);
        String syncId = opt(in, "dataSourceSyncId");
        ObjectNode out = out();
        ArrayNode failed = out.putArray("failedDocuments");
        int deleted = 0;
        for (JsonNode document : in.path("documents")) {
            String id = document.path("documentId").asText();
            String key = appId + "/" + indexId + "/" + id;
            if (store.get(region, QBusinessStore.DOCUMENT, key).isEmpty()) {
                failed.addObject().put("id", id).putObject("error").put("errorCode", "ResourceNotFound")
                        .put("errorMessage", "The document " + id + " isn't in the index.");
                continue;
            }
            store.delete(region, QBusinessStore.DOCUMENT, key);
            deleted++;
        }
        if (syncId != null) {
            countSync(region, appId, indexId, syncId, "documentsDeleted", deleted);
        }
        return out;
    }

    public ObjectNode listDocuments(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        String indexId = req(in, "indexId");
        index(region, appId, indexId);
        Set<String> sources = new HashSet<>();
        in.path("dataSourceIds").forEach(id -> sources.add(id.asText()));
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode document : documents(region, appId, indexId)) {
            if (sources.isEmpty() || sources.contains(QBusinessStore.meta(document).path("dataSourceId").asText())) {
                views.add(copy(document, "documentId", "status", "error", "createdAt", "updatedAt"));
            }
        }
        return page(in, views, "documentDetailList");
    }

    ObjectNode document(String region, String appId, String indexId, String id) {
        index(region, appId, indexId);
        return store.find(region, QBusinessStore.DOCUMENT, appId + "/" + indexId + "/" + id)
                .orElseThrow(() -> QBusinessStore.notFound("document", id));
    }

    public ObjectNode getDocumentContent(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        String indexId = req(in, "indexId");
        ObjectNode document = document(region, appId, indexId, req(in, "documentId"));
        String format = opt(in, "outputFormat") == null ? "RAW" : in.get("outputFormat").asText();
        String type = QBusinessStore.meta(document).path("contentType").asText();
        String mime = "EXTRACTED".equals(format) ? "text/plain" : mime(type);
        String token = QBusinessContentLinks.sign(store.account(), region, appId, indexId,
                document.get("documentId").asText(), format, store.config().presignedUrlSeconds());
        return out().put("presignedUrl", store.endpoint() + QBusinessContentLinks.PATH + token)
                .put("mimeType", mime);
    }

    static String mime(String type) {
        return switch (type) {
            case "PDF" -> "application/pdf";
            case "HTML" -> "text/html";
            case "MS_WORD" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "PPT" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "MS_EXCEL" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "RTF" -> "application/rtf";
            case "XML", "XSLT" -> "application/xml";
            case "CSV" -> "text/csv";
            case "JSON" -> "application/json";
            case "MD" -> "text/markdown";
            default -> "text/plain";
        };
    }

    // ================================================================ users and groups

    ObjectNode user(String region, String appId, String userId) {
        application(region, appId);
        return store.get(region, QBusinessStore.USER, appId + "/" + userId)
                .orElseThrow(() -> QBusinessStore.notFound("user", userId));
    }

    public ObjectNode createUser(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        activeApplication(region, appId);
        String userId = req(in, "userId");
        if (store.get(region, QBusinessStore.USER, appId + "/" + userId).isPresent()) {
            throw QBusinessStore.conflict("user", userId, "The user " + userId + " already exists.");
        }
        ObjectNode record = created();
        record.put("userId", userId);
        ArrayNode aliases = record.putArray("userAliases");
        in.path("userAliases").forEach(alias -> aliases.add(alias.deepCopy()));
        store.put(region, QBusinessStore.USER, appId + "/" + userId, record);
        return out();
    }

    public ObjectNode getUser(JsonNode in, String region) {
        return copy(user(region, req(in, "applicationId"), req(in, "userId")), "userAliases");
    }

    public ObjectNode updateUser(JsonNode in, String region) {
        ObjectNode record = user(region, req(in, "applicationId"), req(in, "userId"));
        ArrayNode aliases = (ArrayNode) record.get("userAliases");
        ObjectNode out = out();
        ArrayNode added = out.putArray("userAliasesAdded");
        ArrayNode updated = out.putArray("userAliasesUpdated");
        ArrayNode deleted = out.putArray("userAliasesDeleted");
        for (JsonNode alias : in.path("userAliasesToUpdate")) {
            boolean found = false;
            for (JsonNode existing : aliases) {
                if (sameScope(existing, alias)) {
                    ((ObjectNode) existing).put("userId", alias.path("userId").asText());
                    updated.add(alias.deepCopy());
                    found = true;
                }
            }
            if (!found) {
                aliases.add(alias.deepCopy());
                added.add(alias.deepCopy());
            }
        }
        for (JsonNode alias : in.path("userAliasesToDelete")) {
            for (int i = aliases.size() - 1; i >= 0; i--) {
                if (sameScope(aliases.get(i), alias)) {
                    deleted.add(aliases.remove(i));
                }
            }
        }
        if (aliases.size() > 100) {
            throw QBusinessStore.quota("A user has at most 100 aliases.");
        }
        record.put("updatedAt", QBusinessStore.now());
        save(record);
        return out;
    }

    private static boolean sameScope(JsonNode a, JsonNode b) {
        return a.path("indexId").asText("").equals(b.path("indexId").asText(""))
                && a.path("dataSourceId").asText("").equals(b.path("dataSourceId").asText(""));
    }

    public ObjectNode deleteUser(JsonNode in, String region) {
        ObjectNode record = user(region, req(in, "applicationId"), req(in, "userId"));
        store.delete(region, QBusinessStore.USER, QBusinessStore.id(record));
        return out();
    }

    private String groupKey(String appId, String indexId, String sourceId, String name) {
        return appId + "/" + indexId + "/" + (sourceId == null ? "_" : sourceId) + "/" + name;
    }

    public ObjectNode putGroup(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        String indexId = req(in, "indexId");
        activeIndex(region, appId, indexId);
        String type = req(in, "type");
        String sourceId = opt(in, "dataSourceId");
        if ("DATASOURCE".equals(type) && sourceId == null) {
            throw QBusinessStore.validation("A DATASOURCE group names its dataSourceId.");
        }
        if (sourceId != null) {
            dataSource(region, appId, indexId, sourceId);
        }
        JsonNode members = in.path("groupMembers");
        ArrayNode users = mapper.createArrayNode();
        ArrayNode groups = mapper.createArrayNode();
        members.path("memberUsers").forEach(users::add);
        members.path("memberGroups").forEach(groups::add);
        if (members.has("s3PathForGroupMembers")) {
            checkRole(req(in, "roleArn"), region);
            try {
                JsonNode file = mapper.readTree(readS3(members.at("/s3PathForGroupMembers/bucket").asText(),
                        members.at("/s3PathForGroupMembers/key").asText()));
                file.path("memberUsers").forEach(users::add);
                file.path("memberGroups").forEach(groups::add);
            } catch (IOException e) {
                throw QBusinessStore.validation("The group members file isn't JSON.");
            }
        }
        String name = req(in, "groupName");
        String key = groupKey(appId, indexId, sourceId, name);
        ObjectNode record = store.get(region, QBusinessStore.GROUP, key).orElseGet(this::created);
        record.put("groupName", name).put("type", type).put("updatedAt", QBusinessStore.now());
        record.set("memberUsers", users);
        record.set("memberGroups", groups);
        ArrayNode history = record.has("statusHistory") ? (ArrayNode) record.get("statusHistory")
                : record.putArray("statusHistory");
        history.addObject().put("status", "PROCESSING").put("lastUpdatedAt", QBusinessStore.now());
        store.transition(record, "groupStatus", "PROCESSING", "SUCCEEDED");
        store.put(region, QBusinessStore.GROUP, key, record);
        return out();
    }

    private ObjectNode group(String region, String appId, String indexId, String sourceId, String name) {
        index(region, appId, indexId);
        return store.find(region, QBusinessStore.GROUP, groupKey(appId, indexId, sourceId, name))
                .orElseThrow(() -> QBusinessStore.notFound("group", name));
    }

    public ObjectNode getGroup(JsonNode in, String region) {
        ObjectNode record = group(region, req(in, "applicationId"), req(in, "indexId"), opt(in, "dataSourceId"),
                req(in, "groupName"));
        ObjectNode out = out();
        out.putObject("status").put("status", record.path("groupStatus").asText())
                .put("lastUpdatedAt", record.path("updatedAt").asDouble());
        ArrayNode history = out.putArray("statusHistory");
        record.path("statusHistory").forEach(history::add);
        if ("SUCCEEDED".equals(record.path("groupStatus").asText())) {
            history.addObject().put("status", "SUCCEEDED").put("lastUpdatedAt", record.path("updatedAt").asDouble());
        }
        return out;
    }

    public ObjectNode listGroups(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        String indexId = req(in, "indexId");
        index(region, appId, indexId);
        double before = in.path("updatedEarlierThan").asDouble(Double.MAX_VALUE);
        String sourceId = opt(in, "dataSourceId");
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode record : store.list(region, QBusinessStore.GROUP, appId + "/" + indexId + "/"
                + (sourceId == null ? "_" : sourceId) + "/")) {
            if (record.path("updatedAt").asDouble() <= before) {
                views.add(out().put("groupName", record.get("groupName").asText()));
            }
        }
        return page(in, views, "items");
    }

    public ObjectNode deleteGroup(JsonNode in, String region) {
        ObjectNode record = group(region, req(in, "applicationId"), req(in, "indexId"), opt(in, "dataSourceId"),
                req(in, "groupName"));
        store.delete(region, QBusinessStore.GROUP, QBusinessStore.id(record));
        return out();
    }

    /** The ids a user goes by in an index: their own and their aliases there. */
    Set<String> identities(String region, String appId, String indexId, String userId) {
        Set<String> ids = new LinkedHashSet<>();
        if (userId == null) {
            return ids;
        }
        ids.add(userId);
        store.get(region, QBusinessStore.USER, appId + "/" + userId).ifPresent(user -> user.path("userAliases")
                .forEach(alias -> {
                    if (alias.path("indexId").asText(indexId).equals(indexId)) {
                        ids.add(alias.path("userId").asText());
                    }
                }));
        return ids;
    }

    /** Every group a user belongs to in an index, directly or through nested groups. */
    Set<String> groups(String region, String appId, String indexId, Set<String> ids) {
        Set<String> groups = new LinkedHashSet<>();
        List<ObjectNode> all = new ArrayList<>();
        store.list(region, QBusinessStore.GROUP, appId + "/" + indexId + "/").forEach(g -> all.add(store.settle(g)));
        Deque<String> pending = new ArrayDeque<>();
        for (ObjectNode group : all) {
            for (JsonNode member : group.path("memberUsers")) {
                if (ids.contains(member.path("userId").asText())) {
                    pending.add(group.get("groupName").asText());
                }
            }
        }
        while (!pending.isEmpty()) {
            String name = pending.poll();
            if (!groups.add(name)) {
                continue;
            }
            for (ObjectNode group : all) {
                for (JsonNode member : group.path("memberGroups")) {
                    if (name.equals(member.path("groupName").asText())) {
                        pending.add(group.get("groupName").asText());
                    }
                }
            }
        }
        return groups;
    }

    /** Whether a user may read a document. */
    boolean readable(String region, String appId, String indexId, ObjectNode document, String userId) {
        Set<String> ids = identities(region, appId, indexId, userId);
        Set<String> groups = groups(region, appId, indexId, ids);
        JsonNode access = QBusinessStore.meta(document).path("access");
        if (ids.isEmpty()) {
            return QBusinessSearch.allowed(access, null, groups);
        }
        for (String id : ids) {
            if (QBusinessSearch.allowed(access, id, groups)) {
                return true;
            }
        }
        return false;
    }

    public ObjectNode checkDocumentAccess(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        String indexId = req(in, "indexId");
        String userId = req(in, "userId");
        ObjectNode document = document(region, appId, indexId, req(in, "documentId"));
        Set<String> ids = identities(region, appId, indexId, userId);
        ObjectNode out = out().put("hasAccess", readable(region, appId, indexId, document, userId));
        ArrayNode groups = out.putArray("userGroups");
        groups(region, appId, indexId, ids).forEach(name -> groups.addObject().put("name", name).put("type", "INDEX"));
        ArrayNode aliases = out.putArray("userAliases");
        ids.stream().skip(1).forEach(id -> aliases.addObject().put("id", id).put("type", "INDEX"));
        JsonNode access = QBusinessStore.meta(document).path("access");
        ObjectNode acl = out.putObject("documentAcl");
        ObjectNode allow = acl.putObject("allowlist").put("memberRelation", access.path("memberRelation").asText("OR"));
        ObjectNode deny = acl.putObject("denyList").put("memberRelation", "OR");
        ArrayNode allowConditions = allow.putArray("conditions");
        ArrayNode denyConditions = deny.putArray("conditions");
        for (JsonNode control : access.path("accessControls")) {
            ObjectNode allowed = mapper.createObjectNode().put("memberRelation", control.path("memberRelation")
                    .asText("OR"));
            ObjectNode denied = mapper.createObjectNode().put("memberRelation", "OR");
            for (JsonNode principal : control.path("principals")) {
                boolean isUser = principal.has("user");
                JsonNode body = isUser ? principal.get("user") : principal.get("group");
                ObjectNode target = "DENY".equals(body.path("access").asText()) ? denied : allowed;
                ArrayNode list = target.has(isUser ? "users" : "groups") ? (ArrayNode) target.get(isUser ? "users"
                        : "groups") : target.putArray(isUser ? "users" : "groups");
                list.addObject().put(isUser ? "id" : "name", body.path(isUser ? "id" : "name").asText())
                        .put("type", body.path("membershipType").asText("INDEX"));
            }
            if (allowed.size() > 1) {
                allowConditions.add(allowed);
            }
            if (denied.size() > 1) {
                denyConditions.add(denied);
            }
        }
        return out;
    }

    // ================================================================ web experiences

    private static final String[] WEB_FIELDS = {"applicationId", "webExperienceId", "webExperienceArn",
        "defaultEndpoint", "status", "createdAt", "updatedAt", "title", "subtitle", "welcomeMessage",
        "samplePromptsControlMode", "origins", "roleArn", "identityProviderConfiguration",
        "authenticationConfiguration", "error", "browserExtensionConfiguration", "customizationConfiguration"};

    ObjectNode web(String region, String appId, String id) {
        application(region, appId);
        return store.find(region, QBusinessStore.WEB, appId + "/" + id)
                .orElseThrow(() -> QBusinessStore.notFound("web-experience", id));
    }

    public ObjectNode createWebExperience(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        ObjectNode app = activeApplication(region, appId);
        checkRole(opt(in, "roleArn"), region);
        for (JsonNode origin : in.path("origins")) {
            if (!origin.asText().matches("https?://[^/\\s]+")) {
                throw QBusinessStore.validation("An origin is a scheme and host, such as https://example.com.");
            }
        }
        String id = UUID.randomUUID().toString();
        ObjectNode record = created();
        record.put("applicationId", appId).put("webExperienceId", id)
                .put("webExperienceArn", arn(region, "application/" + appId + "/web-experience/" + id))
                .put("defaultEndpoint", QBusinessContentLinks.webEndpoint(store.endpoint(), region, id))
                .put("samplePromptsControlMode", in.path("samplePromptsControlMode").asText("ENABLED"));
        for (String member : List.of("title", "subtitle", "welcomeMessage", "roleArn")) {
            if (opt(in, member) != null) {
                record.put(member, in.get(member).asText());
            }
        }
        for (String member : List.of("origins", "identityProviderConfiguration", "browserExtensionConfiguration",
                "customizationConfiguration")) {
            if (in.has(member)) {
                record.set(member, in.get(member).deepCopy());
            }
        }
        boolean needsAuth = app.path("identityType").asText().startsWith("AWS_IAM_IDP")
                && !in.has("identityProviderConfiguration");
        store.transition(record, "status", "CREATING", needsAuth ? "PENDING_AUTH_CONFIG" : "ACTIVE");
        QBusinessStore.addTags(record, in.path("tags"));
        store.put(region, QBusinessStore.WEB, appId + "/" + id, record);
        return copy(record, "webExperienceId", "webExperienceArn");
    }

    public ObjectNode getWebExperience(JsonNode in, String region) {
        return copy(web(region, req(in, "applicationId"), req(in, "webExperienceId")), WEB_FIELDS);
    }

    public ObjectNode listWebExperiences(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        application(region, appId);
        List<ObjectNode> views = new ArrayList<>();
        store.all(region, QBusinessStore.WEB).stream().filter(w -> appId.equals(w.path("applicationId").asText()))
                .forEach(w -> views.add(copy(w, "webExperienceId", "createdAt", "updatedAt", "defaultEndpoint",
                        "status")));
        return page(in, views, "webExperiences");
    }

    public ObjectNode updateWebExperience(JsonNode in, String region) {
        ObjectNode record = web(region, req(in, "applicationId"), req(in, "webExperienceId"));
        checkRole(opt(in, "roleArn"), region);
        for (String member : List.of("title", "subtitle", "welcomeMessage", "samplePromptsControlMode", "roleArn")) {
            if (in.has(member)) {
                record.put(member, in.get(member).asText());
            }
        }
        for (String member : List.of("origins", "identityProviderConfiguration", "authenticationConfiguration",
                "browserExtensionConfiguration", "customizationConfiguration")) {
            if (in.has(member)) {
                record.set(member, in.get(member).deepCopy());
            }
        }
        if ("PENDING_AUTH_CONFIG".equals(record.path("status").asText()) && (in.has("identityProviderConfiguration")
                || in.has("authenticationConfiguration"))) {
            record.put("status", "ACTIVE");
        }
        record.put("updatedAt", QBusinessStore.now());
        save(record);
        return out();
    }

    public ObjectNode deleteWebExperience(JsonNode in, String region) {
        ObjectNode record = web(region, req(in, "applicationId"), req(in, "webExperienceId"));
        store.delete(region, QBusinessStore.WEB, QBusinessStore.id(record));
        return out();
    }

    public ObjectNode createAnonymousWebExperienceUrl(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        ObjectNode app = activeApplication(region, appId);
        if (!"ANONYMOUS".equals(app.path("identityType").asText())) {
            throw QBusinessStore.validation("Anonymous URLs are for ANONYMOUS applications.");
        }
        ObjectNode web = web(region, appId, req(in, "webExperienceId"));
        if (!"ACTIVE".equals(web.path("status").asText())) {
            throw QBusinessStore.conflict("web-experience", web.get("webExperienceId").asText(),
                    "The web experience is " + web.path("status").asText() + ".");
        }
        int minutes = in.path("sessionDurationInMinutes").asInt(15);
        if (minutes < 15 || minutes > 60) {
            throw QBusinessStore.validation("sessionDurationInMinutes must be from 15 to 60.");
        }
        String token = QBusinessContentLinks.signSession(store.account(), region, appId,
                web.get("webExperienceId").asText(), minutes * 60L);
        return out().put("anonymousUrl", web.get("defaultEndpoint").asText() + "?token=" + token);
    }

    // ================================================================ plugins

    private static final String[] PLUGIN_FIELDS = {"applicationId", "pluginId", "displayName", "type", "serverUrl",
        "authConfiguration", "customPluginConfiguration", "buildStatus", "pluginArn", "state", "createdAt",
        "updatedAt"};

    ObjectNode plugin(String region, String appId, String id) {
        application(region, appId);
        return store.find(region, QBusinessStore.PLUGIN, appId + "/" + id)
                .orElseThrow(() -> QBusinessStore.notFound("plugin", id));
    }

    /** The OpenAPI document a custom plugin carries, parsed from JSON or YAML. */
    JsonNode openApi(JsonNode configuration) {
        JsonNode schema = configuration.path("apiSchema");
        String text;
        if (schema.has("payload")) {
            text = schema.get("payload").asText();
        } else if (schema.has("s3")) {
            text = new String(readS3(schema.at("/s3/bucket").asText(), schema.at("/s3/key").asText()),
                    StandardCharsets.UTF_8);
        } else {
            throw QBusinessStore.validation("A custom plugin's apiSchema has a payload or an S3 location.");
        }
        try {
            JsonNode document = new ObjectMapper(new YAMLFactory()).readTree(text);
            if (document == null || !document.path("openapi").asText("").startsWith("3")
                    || !document.path("paths").isObject()) {
                throw QBusinessStore.validation("The API schema must be an OpenAPI 3 document with paths.");
            }
            return document;
        } catch (IOException e) {
            throw QBusinessStore.validation("The API schema can't be parsed: " + e.getMessage());
        }
    }

    private void checkPlugin(JsonNode in, String type) {
        if (!PLUGIN_TYPES.contains(type)) {
            throw QBusinessStore.validation("The plugin type " + type + " isn't known.");
        }
        if ("CUSTOM".equals(type)) {
            if (!in.has("customPluginConfiguration")) {
                throw QBusinessStore.validation("A CUSTOM plugin needs customPluginConfiguration.");
            }
            openApi(in.get("customPluginConfiguration"));
        } else if (!"QUICKSIGHT".equals(type) && opt(in, "serverUrl") == null) {
            throw QBusinessStore.validation("A " + type + " plugin needs its serverUrl.");
        }
    }

    public ObjectNode createPlugin(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        activeApplication(region, appId);
        String type = req(in, "type");
        checkPlugin(in, type);
        if (in.at("/authConfiguration/basicAuthConfiguration").isObject()) {
            checkRole(in.at("/authConfiguration/basicAuthConfiguration/roleArn").asText(), region);
        }
        if (store.list(region, QBusinessStore.PLUGIN, appId + "/").size() >= 25) {
            throw QBusinessStore.quota("An application has at most 25 plugins.");
        }
        String id = UUID.randomUUID().toString();
        ObjectNode record = created();
        record.put("applicationId", appId).put("pluginId", id).put("displayName", req(in, "displayName"))
                .put("type", type).put("pluginArn", arn(region, "application/" + appId + "/plugin/" + id))
                .put("state", "ENABLED");
        if (opt(in, "serverUrl") != null) {
            record.put("serverUrl", in.get("serverUrl").asText());
        }
        record.set("authConfiguration", in.get("authConfiguration").deepCopy());
        if (in.has("customPluginConfiguration")) {
            record.set("customPluginConfiguration", in.get("customPluginConfiguration").deepCopy());
        }
        store.transition(record, "buildStatus", "CREATE_IN_PROGRESS", "READY");
        QBusinessStore.addTags(record, in.path("tags"));
        store.put(region, QBusinessStore.PLUGIN, appId + "/" + id, record);
        return copy(record, "pluginId", "pluginArn", "buildStatus");
    }

    public ObjectNode getPlugin(JsonNode in, String region) {
        return copy(plugin(region, req(in, "applicationId"), req(in, "pluginId")), PLUGIN_FIELDS);
    }

    public ObjectNode listPlugins(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        application(region, appId);
        List<ObjectNode> views = new ArrayList<>();
        store.all(region, QBusinessStore.PLUGIN).stream().filter(p -> appId.equals(p.path("applicationId").asText()))
                .forEach(p -> views.add(copy(p, "pluginId", "displayName", "type", "serverUrl", "state",
                        "buildStatus", "createdAt", "updatedAt")));
        return page(in, views, "plugins");
    }

    public ObjectNode updatePlugin(JsonNode in, String region) {
        ObjectNode record = plugin(region, req(in, "applicationId"), req(in, "pluginId"));
        if (in.has("customPluginConfiguration")) {
            openApi(in.get("customPluginConfiguration"));
            record.set("customPluginConfiguration", in.get("customPluginConfiguration").deepCopy());
        }
        for (String member : List.of("displayName", "state", "serverUrl")) {
            if (in.has(member)) {
                record.put(member, in.get(member).asText());
            }
        }
        if (in.has("authConfiguration")) {
            record.set("authConfiguration", in.get("authConfiguration").deepCopy());
        }
        record.put("updatedAt", QBusinessStore.now());
        store.transition(record, "buildStatus", "UPDATE_IN_PROGRESS", "READY");
        save(record);
        return out();
    }

    public ObjectNode deletePlugin(JsonNode in, String region) {
        ObjectNode record = plugin(region, req(in, "applicationId"), req(in, "pluginId"));
        store.delete(region, QBusinessStore.PLUGIN, QBusinessStore.id(record));
        return out();
    }

    /** A plugin's actions: a custom plugin's OpenAPI operations, or a built-in type's catalog. */
    List<ObjectNode> actions(ObjectNode plugin) {
        List<ObjectNode> actions = new ArrayList<>();
        if ("CUSTOM".equals(plugin.path("type").asText())) {
            JsonNode document = openApi(plugin.get("customPluginConfiguration"));
            document.path("paths").properties().forEach(path -> path.getValue().properties().forEach(method -> {
                if (!Set.of("get", "post", "put", "patch", "delete").contains(method.getKey())) {
                    return;
                }
                JsonNode operation = method.getValue();
                String id = operation.path("operationId").asText(method.getKey() + " " + path.getKey());
                actions.add(out().put("actionIdentifier", id).put("displayName", operation.path("summary").asText(id))
                        .put("description", operation.path("description").asText(operation.path("summary")
                                .asText(id)))
                        .put("instructionExample", operation.path("summary").asText(id)));
            }));
        } else {
            for (String[] action : typeActions(plugin.path("type").asText())) {
                actions.add(out().put("actionIdentifier", action[0]).put("displayName", action[1])
                        .put("description", action[1]).put("instructionExample", action[2]));
            }
        }
        return actions;
    }

    static List<String[]> typeActions(String type) {
        return switch (type) {
            case "JIRA", "JIRA_CLOUD" -> List.<String[]>of(new String[] {"jira-create-issue", "Create issue",
                "Create a Jira issue for the login bug"}, new String[] {"jira-read-issue", "Read issue",
                "What's the status of PROJ-12?"});
            case "SERVICE_NOW", "SERVICENOW_NOW_PLATFORM" -> List.<String[]>of(new String[] {"servicenow-create-incident",
                "Create incident", "Open an incident for the outage"});
            case "SALESFORCE", "SALESFORCE_CRM" -> List.<String[]>of(new String[] {"salesforce-create-case", "Create case",
                "Create a case for Acme"});
            case "ZENDESK", "ZENDESK_SUITE" -> List.<String[]>of(new String[] {"zendesk-create-ticket", "Create ticket",
                "Create a ticket for the refund"});
            case "MICROSOFT_TEAMS" -> List.<String[]>of(new String[] {"teams-send-message", "Send message",
                "Tell the team the release is out"});
            case "MICROSOFT_EXCHANGE" -> List.<String[]>of(new String[] {"exchange-send-email", "Send email",
                "Email the report to Sam"});
            case "GOOGLE_CALENDAR" -> List.<String[]>of(new String[] {"calendar-create-event", "Create event",
                "Schedule a review on Friday"});
            case "ASANA" -> List.<String[]>of(new String[] {"asana-create-task", "Create task", "Add a task to review"});
            case "SMARTSHEET" -> List.<String[]>of(new String[] {"smartsheet-read-sheet", "Read sheet",
                "What's in the budget sheet?"});
            case "PAGERDUTY_ADVANCE" -> List.<String[]>of(new String[] {"pagerduty-list-incidents", "List incidents",
                "Which incidents are open?"});
            case "ATLASSIAN_CONFLUENCE" -> List.<String[]>of(new String[] {"confluence-create-page", "Create page",
                "Write up the runbook"});
            default -> List.<String[]>of(new String[] {"quicksight-ask", "Ask a dashboard", "Show last month's sales"});
        };
    }

    public ObjectNode listPluginActions(JsonNode in, String region) {
        return page(in, actions(plugin(region, req(in, "applicationId"), req(in, "pluginId"))), "items");
    }

    public ObjectNode listPluginTypeActions(JsonNode in, String region) {
        String type = req(in, "pluginType");
        if (!PLUGIN_TYPES.contains(type) || "CUSTOM".equals(type)) {
            throw QBusinessStore.validation("The plugin type " + type + " has no built-in actions.");
        }
        List<ObjectNode> actions = new ArrayList<>();
        for (String[] action : typeActions(type)) {
            actions.add(out().put("actionIdentifier", action[0]).put("displayName", action[1])
                    .put("description", action[1]).put("instructionExample", action[2]));
        }
        return page(in, actions, "items");
    }

    public ObjectNode listPluginTypeMetadata(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (String type : new TreeSet<>(PLUGIN_TYPES)) {
            if ("CUSTOM".equals(type)) {
                continue;
            }
            String category = switch (type) {
                case "SALESFORCE", "SALESFORCE_CRM" -> "Customer relationship management (CRM)";
                case "JIRA", "JIRA_CLOUD", "ASANA", "SMARTSHEET", "ATLASSIAN_CONFLUENCE" -> "Project management";
                case "MICROSOFT_TEAMS", "MICROSOFT_EXCHANGE" -> "Communication";
                case "GOOGLE_CALENDAR", "QUICKSIGHT" -> "Productivity";
                default -> "Ticketing and incident management";
            };
            items.add(out().put("type", type).put("category", category).put("description", type.replace('_', ' ')
                    .toLowerCase(Locale.ROOT) + " actions"));
        }
        return page(in, items, "items");
    }

    // ================================================================ data accessors

    private static final String[] ACCESSOR_FIELDS = {"displayName", "dataAccessorId", "dataAccessorArn",
        "applicationId", "idcApplicationArn", "principal", "actionConfigurations", "authenticationDetail",
        "createdAt", "updatedAt"};

    ObjectNode accessor(String region, String appId, String id) {
        application(region, appId);
        return store.get(region, QBusinessStore.ACCESSOR, appId + "/" + id)
                .orElseThrow(() -> QBusinessStore.notFound("data-accessor", id));
    }

    private void checkActions(JsonNode actions) {
        for (JsonNode action : actions) {
            if (!SHAREABLE.contains(action.path("action").asText())) {
                throw QBusinessStore.validation("A data accessor can't be granted " + action.path("action").asText()
                        + ".");
            }
        }
    }

    public ObjectNode createDataAccessor(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        ObjectNode app = activeApplication(region, appId);
        if (!"AWS_IAM_IDC".equals(app.path("identityType").asText())) {
            throw QBusinessStore.validation("Data accessors are for AWS_IAM_IDC applications.");
        }
        checkRole(req(in, "principal"), region);
        checkActions(in.path("actionConfigurations"));
        String id = UUID.randomUUID().toString();
        ObjectNode record = created();
        record.put("displayName", req(in, "displayName")).put("dataAccessorId", id)
                .put("dataAccessorArn", arn(region, "application/" + appId + "/data-accessor/" + id))
                .put("applicationId", appId).put("principal", in.get("principal").asText())
                .put("idcApplicationArn", app.path("identityCenterApplicationArn").asText().replaceAll("apl-[0-9a-f]+$",
                        "apl-" + QBusinessStore.hex(16)));
        record.set("actionConfigurations", in.get("actionConfigurations").deepCopy());
        if (in.has("authenticationDetail")) {
            record.set("authenticationDetail", in.get("authenticationDetail").deepCopy());
        }
        QBusinessStore.addTags(record, in.path("tags"));
        store.put(region, QBusinessStore.ACCESSOR, appId + "/" + id, record);
        return copy(record, "dataAccessorId", "idcApplicationArn", "dataAccessorArn");
    }

    public ObjectNode getDataAccessor(JsonNode in, String region) {
        return copy(accessor(region, req(in, "applicationId"), req(in, "dataAccessorId")), ACCESSOR_FIELDS);
    }

    public ObjectNode listDataAccessors(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        application(region, appId);
        List<ObjectNode> views = new ArrayList<>();
        store.list(region, QBusinessStore.ACCESSOR, appId + "/").forEach(a -> views.add(copy(a, "displayName",
                "dataAccessorId", "dataAccessorArn", "idcApplicationArn", "principal", "authenticationDetail",
                "createdAt", "updatedAt")));
        return page(in, views, "dataAccessors");
    }

    public ObjectNode updateDataAccessor(JsonNode in, String region) {
        ObjectNode record = accessor(region, req(in, "applicationId"), req(in, "dataAccessorId"));
        checkActions(in.path("actionConfigurations"));
        record.set("actionConfigurations", in.get("actionConfigurations").deepCopy());
        if (in.has("authenticationDetail")) {
            record.set("authenticationDetail", in.get("authenticationDetail").deepCopy());
        }
        if (in.has("displayName")) {
            record.put("displayName", in.get("displayName").asText());
        }
        record.put("updatedAt", QBusinessStore.now());
        save(record);
        return out();
    }

    public ObjectNode deleteDataAccessor(JsonNode in, String region) {
        ObjectNode record = accessor(region, req(in, "applicationId"), req(in, "dataAccessorId"));
        store.delete(region, QBusinessStore.ACCESSOR, QBusinessStore.id(record));
        return out();
    }

    // ================================================================ chat response configurations

    ObjectNode responseConfig(String region, String appId, String id) {
        application(region, appId);
        return store.find(region, QBusinessStore.RESPONSE_CONFIG, appId + "/" + id)
                .orElseThrow(() -> QBusinessStore.notFound("chat-response-configuration", id));
    }

    private static String summary(JsonNode configurations) {
        JsonNode all = configurations.at("/ALL/instructionCollection");
        List<String> parts = new ArrayList<>();
        all.properties().forEach(entry -> parts.add(entry.getKey() + ": " + entry.getValue().asText()));
        return String.join("; ", parts);
    }

    public ObjectNode createChatResponseConfiguration(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        activeApplication(region, appId);
        if (store.list(region, QBusinessStore.RESPONSE_CONFIG, appId + "/").size() >= 5) {
            throw QBusinessStore.quota("An application has at most 5 chat response configurations.");
        }
        String id = UUID.randomUUID().toString();
        ObjectNode record = created();
        record.put("chatResponseConfigurationId", id).put("chatResponseConfigurationArn",
                arn(region, "application/" + appId + "/chat-response-configuration/" + id))
                .put("displayName", req(in, "displayName"));
        ObjectNode detail = record.putObject("inUseConfiguration");
        detail.set("responseConfigurations", in.get("responseConfigurations").deepCopy());
        detail.put("responseConfigurationSummary", summary(in.get("responseConfigurations"))).put("status", "ACTIVE")
                .put("updatedAt", QBusinessStore.now());
        record.put("responseConfigurationSummary", detail.get("responseConfigurationSummary").asText());
        store.transition(record, "status", "CREATING", "ACTIVE");
        QBusinessStore.addTags(record, in.path("tags"));
        store.put(region, QBusinessStore.RESPONSE_CONFIG, appId + "/" + id, record);
        return copy(record, "chatResponseConfigurationId", "chatResponseConfigurationArn");
    }

    public ObjectNode getChatResponseConfiguration(JsonNode in, String region) {
        return copy(responseConfig(region, req(in, "applicationId"), req(in, "chatResponseConfigurationId")),
                "chatResponseConfigurationId", "chatResponseConfigurationArn", "displayName", "createdAt",
                "inUseConfiguration", "lastUpdateConfiguration");
    }

    public ObjectNode listChatResponseConfigurations(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        application(region, appId);
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode record : store.list(region, QBusinessStore.RESPONSE_CONFIG, appId + "/")) {
            views.add(copy(store.settle(record), "chatResponseConfigurationId", "chatResponseConfigurationArn",
                    "displayName", "responseConfigurationSummary", "status", "createdAt", "updatedAt"));
        }
        return page(in, views, "chatResponseConfigurations");
    }

    public ObjectNode updateChatResponseConfiguration(JsonNode in, String region) {
        ObjectNode record = responseConfig(region, req(in, "applicationId"), req(in, "chatResponseConfigurationId"));
        if (in.has("displayName")) {
            record.put("displayName", in.get("displayName").asText());
        }
        ObjectNode detail = record.putObject("lastUpdateConfiguration");
        detail.set("responseConfigurations", in.get("responseConfigurations").deepCopy());
        detail.put("responseConfigurationSummary", summary(in.get("responseConfigurations"))).put("status", "ACTIVE")
                .put("updatedAt", QBusinessStore.now());
        record.set("inUseConfiguration", detail.deepCopy());
        record.put("responseConfigurationSummary", detail.get("responseConfigurationSummary").asText())
                .put("updatedAt", QBusinessStore.now());
        store.transition(record, "status", "UPDATING", "ACTIVE");
        save(record);
        return out();
    }

    public ObjectNode deleteChatResponseConfiguration(JsonNode in, String region) {
        ObjectNode record = responseConfig(region, req(in, "applicationId"), req(in, "chatResponseConfigurationId"));
        store.delete(region, QBusinessStore.RESPONSE_CONFIG, QBusinessStore.id(record));
        return out();
    }

    // ================================================================ chat controls

    ObjectNode controls(String region, String appId) {
        application(region, appId);
        return store.get(region, "chat-controls", appId).orElseThrow(() -> QBusinessStore.notFound("application",
                appId));
    }

    public ObjectNode getChatControlsConfiguration(JsonNode in, String region) {
        ObjectNode record = controls(region, req(in, "applicationId"));
        ObjectNode out = copy(record, "responseScope", "blockedPhrases", "creatorModeConfiguration",
                "hallucinationReductionConfiguration");
        out.set("orchestrationConfiguration", record.get("orchestrationConfiguration").deepCopy());
        List<ObjectNode> topics = new ArrayList<>();
        record.path("topicConfigurations").forEach(topic -> topics.add((ObjectNode) topic));
        ObjectNode paged = page(in, topics, "topicConfigurations");
        out.setAll(paged);
        return out;
    }

    public ObjectNode updateChatControlsConfiguration(JsonNode in, String region) {
        ObjectNode record = controls(region, req(in, "applicationId"));
        if (in.has("responseScope")) {
            record.put("responseScope", in.get("responseScope").asText());
        }
        for (String member : List.of("orchestrationConfiguration", "creatorModeConfiguration",
                "hallucinationReductionConfiguration")) {
            if (in.has(member)) {
                record.set(member, in.get(member).deepCopy());
            }
        }
        JsonNode phrases = in.path("blockedPhrasesConfigurationUpdate");
        if (phrases.isObject()) {
            ObjectNode blocked = (ObjectNode) record.get("blockedPhrases");
            ArrayNode list = (ArrayNode) blocked.get("blockedPhrases");
            Set<String> current = new LinkedHashSet<>();
            list.forEach(p -> current.add(p.asText()));
            phrases.path("blockedPhrasesToCreateOrUpdate").forEach(p -> current.add(p.asText()));
            phrases.path("blockedPhrasesToDelete").forEach(p -> current.remove(p.asText()));
            if (current.size() > 20) {
                throw QBusinessStore.quota("An application has at most 20 blocked phrases.");
            }
            list.removeAll();
            current.forEach(list::add);
            if (phrases.has("systemMessageOverride")) {
                blocked.put("systemMessageOverride", phrases.get("systemMessageOverride").asText());
            }
        }
        ArrayNode topics = (ArrayNode) record.get("topicConfigurations");
        for (JsonNode topic : in.path("topicConfigurationsToDelete")) {
            for (int i = topics.size() - 1; i >= 0; i--) {
                if (topics.get(i).path("name").asText().equals(topic.path("name").asText())) {
                    topics.remove(i);
                }
            }
        }
        for (JsonNode topic : in.path("topicConfigurationsToCreateOrUpdate")) {
            for (int i = topics.size() - 1; i >= 0; i--) {
                if (topics.get(i).path("name").asText().equals(topic.path("name").asText())) {
                    topics.remove(i);
                }
            }
            topics.add(topic.deepCopy());
        }
        if (topics.size() > 10) {
            throw QBusinessStore.quota("An application has at most 10 topic configurations.");
        }
        store.put(region, "chat-controls", QBusinessStore.id(record), record);
        return out();
    }

    public ObjectNode deleteChatControlsConfiguration(JsonNode in, String region) {
        ObjectNode record = controls(region, req(in, "applicationId"));
        record.put("responseScope", "ENTERPRISE_CONTENT_ONLY");
        record.putObject("blockedPhrases").putArray("blockedPhrases");
        record.putArray("topicConfigurations");
        record.putObject("creatorModeConfiguration").put("creatorModeControl", "ENABLED");
        record.putObject("orchestrationConfiguration").put("control", "DISABLED");
        record.putObject("hallucinationReductionConfiguration").put("hallucinationReductionControl", "DISABLED");
        store.put(region, "chat-controls", QBusinessStore.id(record), record);
        return out();
    }

    // ================================================================ subscriptions

    ObjectNode subscription(String region, String appId, String id) {
        application(region, appId);
        return store.get(region, QBusinessStore.SUBSCRIPTION, appId + "/" + id)
                .orElseThrow(() -> QBusinessStore.notFound("subscription", id));
    }

    private ObjectNode subscriptionOut(ObjectNode record) {
        return copy(record, "subscriptionArn", "currentSubscription", "nextSubscription");
    }

    public ObjectNode createSubscription(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        ObjectNode app = activeApplication(region, appId);
        if (!"AWS_IAM_IDC".equals(app.path("identityType").asText())) {
            throw QBusinessStore.validation("Subscriptions are for applications that use IAM Identity Center.");
        }
        JsonNode principal = in.path("principal");
        if (principal.has("user") == principal.has("group")) {
            throw QBusinessStore.validation("A subscription's principal is a user or a group.");
        }
        for (ObjectNode existing : store.list(region, QBusinessStore.SUBSCRIPTION, appId + "/")) {
            if (existing.path("principal").equals(principal)) {
                throw QBusinessStore.conflict("subscription", existing.get("subscriptionId").asText(),
                        "The principal is already subscribed.");
            }
        }
        String id = QBusinessStore.hex(32);
        ObjectNode record = created();
        record.put("subscriptionId", id).put("subscriptionArn", arn(region, "application/" + appId + "/subscription/"
                + id));
        record.set("principal", principal.deepCopy());
        record.putObject("currentSubscription").put("type", req(in, "type"));
        record.putObject("nextSubscription").put("type", req(in, "type"));
        store.put(region, QBusinessStore.SUBSCRIPTION, appId + "/" + id, record);
        return subscriptionOut(record).put("subscriptionId", id);
    }

    public ObjectNode updateSubscription(JsonNode in, String region) {
        ObjectNode record = subscription(region, req(in, "applicationId"), req(in, "subscriptionId"));
        String type = req(in, "type");
        String current = record.at("/currentSubscription/type").asText();
        if ("Q_BUSINESS".equals(type) && "Q_LITE".equals(current)) {
            ((ObjectNode) record.get("currentSubscription")).put("type", type);
        }
        ((ObjectNode) record.get("nextSubscription")).put("type", type);
        save(record);
        return subscriptionOut(record);
    }

    public ObjectNode cancelSubscription(JsonNode in, String region) {
        ObjectNode record = subscription(region, req(in, "applicationId"), req(in, "subscriptionId"));
        ObjectNode out = subscriptionOut(record);
        out.remove("nextSubscription");
        store.delete(region, QBusinessStore.SUBSCRIPTION, QBusinessStore.id(record));
        return out;
    }

    public ObjectNode listSubscriptions(JsonNode in, String region) {
        String appId = req(in, "applicationId");
        application(region, appId);
        List<ObjectNode> views = new ArrayList<>();
        store.list(region, QBusinessStore.SUBSCRIPTION, appId + "/").forEach(s -> views.add(copy(s, "subscriptionId",
                "subscriptionArn", "principal", "currentSubscription", "nextSubscription")));
        return page(in, views, "subscriptions");
    }

    // ================================================================ policy

    private ArrayNode statements(ObjectNode app) {
        ObjectNode meta = QBusinessStore.meta(app);
        if (!meta.has("statements")) {
            meta.putArray("statements");
        }
        return (ArrayNode) meta.get("statements");
    }

    private String policy(ObjectNode app) {
        ObjectNode policy = mapper.createObjectNode().put("Version", "2012-10-17");
        policy.set("Statement", statements(app).deepCopy());
        return policy.toString();
    }

    public ObjectNode associatePermission(JsonNode in, String region) {
        ObjectNode app = activeApplication(region, req(in, "applicationId"));
        String sid = req(in, "statementId");
        String principal = req(in, "principal");
        if (!AwsArnUtils.isArnFor(principal, "iam")) {
            throw QBusinessStore.validation("The principal must be an IAM role ARN.");
        }
        ArrayNode statements = statements(app);
        for (JsonNode statement : statements) {
            if (sid.equals(statement.path("Sid").asText())) {
                throw QBusinessStore.conflict("application", sid, "The policy already has the statement " + sid + ".");
            }
        }
        ObjectNode statement = statements.addObject().put("Sid", sid).put("Effect", "Allow");
        statement.putObject("Principal").put("AWS", principal);
        ArrayNode actions = statement.putArray("Action");
        for (JsonNode action : in.path("actions")) {
            if (!action.asText().startsWith("qbusiness:")) {
                throw QBusinessStore.validation("The action " + action.asText() + " isn't a Q Business action.");
            }
            actions.add(action.asText());
        }
        statement.put("Resource", app.get("applicationArn").asText());
        if (in.path("conditions").isArray() && !in.get("conditions").isEmpty()) {
            ObjectNode condition = statement.putObject("Condition");
            for (JsonNode c : in.get("conditions")) {
                ObjectNode operator = condition.has(c.path("conditionOperator").asText())
                        ? (ObjectNode) condition.get(c.path("conditionOperator").asText())
                        : condition.putObject(c.path("conditionOperator").asText());
                operator.set(c.path("conditionKey").asText(), c.path("conditionValues").deepCopy());
            }
        }
        save(app);
        return out().put("statement", statement.toString());
    }

    public ObjectNode disassociatePermission(JsonNode in, String region) {
        ObjectNode app = application(region, req(in, "applicationId"));
        String sid = req(in, "statementId");
        ArrayNode statements = statements(app);
        for (int i = 0; i < statements.size(); i++) {
            if (sid.equals(statements.get(i).path("Sid").asText())) {
                statements.remove(i);
                save(app);
                return out();
            }
        }
        throw QBusinessStore.notFound("application", sid);
    }

    public ObjectNode getPolicy(JsonNode in, String region) {
        return out().put("policy", policy(application(region, req(in, "applicationId"))));
    }

    // ================================================================ tags

    /** A taggable record by its ARN. */
    ObjectNode byArn(String encoded) {
        String arn = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        if (!AwsArnUtils.isArnFor(arn, QBusinessStore.SERVICE) && !AwsArnUtils.isArnFor(arn, "qapps")) {
            throw QBusinessStore.validation("The ARN " + arn + " isn't a Q Business ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String[] parts = parsed.resource().split("/");
        String region = parsed.region();
        String kind;
        String id;
        if (parts.length == 2 && parts[0].equals("application")) {
            kind = QBusinessStore.APPLICATION;
            id = parts[1];
        } else if (parts.length == 4 && parts[2].equals("index")) {
            kind = QBusinessStore.INDEX;
            id = parts[1] + "/" + parts[3];
        } else if (parts.length == 6 && parts[4].equals("data-source")) {
            kind = QBusinessStore.DATA_SOURCE;
            id = parts[1] + "/" + parts[3] + "/" + parts[5];
        } else if (parts.length == 4) {
            kind = switch (parts[2]) {
                case "retriever" -> QBusinessStore.RETRIEVER;
                case "web-experience" -> QBusinessStore.WEB;
                case "plugin" -> QBusinessStore.PLUGIN;
                case "data-accessor" -> QBusinessStore.ACCESSOR;
                case "chat-response-configuration" -> QBusinessStore.RESPONSE_CONFIG;
                case "qapp" -> QBusinessStore.QAPP;
                case "qapp-session" -> QBusinessStore.SESSION;
                default -> throw QBusinessStore.notFound("resource", arn);
            };
            id = parts[1] + "/" + parts[3];
        } else {
            throw QBusinessStore.notFound("resource", arn);
        }
        if (!parsed.accountId().equals(store.account())) {
            throw QBusinessStore.notFound("resource", arn);
        }
        return store.get(region, kind, id).orElseThrow(() -> QBusinessStore.notFound("resource", arn));
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        ObjectNode record = byArn(req(in, "resourceARN"));
        QBusinessStore.addTags(record, in.path("tags"));
        save(record);
        return out();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        ObjectNode record = byArn(req(in, "resourceARN"));
        in.path("tagKeys").forEach(key -> QBusinessStore.tags(record).remove(key.asText()));
        save(record);
        return out();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        ObjectNode out = out();
        out.set("tags", QBusinessStore.tagList(byArn(req(in, "resourceARN")), mapper));
        return out;
    }
}
