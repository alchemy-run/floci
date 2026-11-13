package io.floci.aws.services.kendra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.iam.AssumeRolePolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.kms.KmsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.kendra.KendraStore.INDEX;
import static io.floci.aws.services.kendra.KendraStore.meta;
import static io.floci.aws.services.kendra.KendraStore.notFound;
import static io.floci.aws.services.kendra.KendraStore.text;
import static io.floci.aws.services.kendra.KendraStore.validation;
import static io.floci.aws.services.kendra.KendraStore.view;

/**
 * Kendra indexes: their editions, fields (document metadata configurations), capacity, user context policy and
 * token configurations, statistics, and tags on every taggable Kendra resource.
 *
 * <p>Amazon Kendra is closed to new customers. Floci treats its accounts as existing customers, so indexes can be
 * created; with {@code existing-customer: false} CreateIndex is refused as AWS refuses a new customer, before the
 * role is checked. An index is {@code CREATING} (or {@code UPDATING}, or {@code DELETING}) for a transition before it
 * is {@code ACTIVE} (or gone). Each account has at most five indexes of each edition.
 */
@ApplicationScoped
public class KendraIndexes {

    static final IamPolicyEvaluator POLICIES = new IamPolicyEvaluator(new ObjectMapper());
    static final AssumeRolePolicyEvaluator TRUST = new AssumeRolePolicyEvaluator(new ObjectMapper(), POLICIES);
    static final Set<String> EDITIONS = Set.of("DEVELOPER_EDITION", "ENTERPRISE_EDITION", "GEN_AI_ENTERPRISE_EDITION");
    static final int MAX_INDEXES_PER_EDITION = 5;
    static final int MAX_FIELDS = 500;

    /** The reserved fields every index has: name, type, and its search and relevance defaults. */
    private static final List<Object[]> RESERVED = List.of(
            new Object[] {"_authors", "STRING_LIST_VALUE", true, false, true, false},
            new Object[] {"_category", "STRING_VALUE", true, true, true, false},
            new Object[] {"_created_at", "DATE_VALUE", true, false, true, true},
            new Object[] {"_data_source_id", "STRING_VALUE", true, false, true, false},
            new Object[] {"_document_body", "STRING_VALUE", false, true, false, false},
            new Object[] {"_document_title", "STRING_VALUE", false, true, true, true},
            new Object[] {"_excerpt_page_number", "LONG_VALUE", false, false, true, false},
            new Object[] {"_faq_id", "STRING_VALUE", true, false, true, false},
            new Object[] {"_file_type", "STRING_VALUE", true, false, true, false},
            new Object[] {"_language_code", "STRING_VALUE", true, false, true, false},
            new Object[] {"_last_updated_at", "DATE_VALUE", true, false, true, true},
            new Object[] {"_source_uri", "STRING_VALUE", false, false, true, false},
            new Object[] {"_tenant_id", "STRING_VALUE", true, false, true, false},
            new Object[] {"_version", "STRING_VALUE", true, false, true, false},
            new Object[] {"_view_count", "LONG_VALUE", true, false, true, true});

    private final KendraStore store;
    private final IamService iam;
    private final KmsService kms;

    @Inject
    public KendraIndexes(KendraStore store, IamService iam, KmsService kms) {
        this.store = store;
        this.iam = iam;
        this.kms = kms;
    }

    KendraStore store() {
        return store;
    }

    ObjectMapper mapper() {
        return store.mapper();
    }

    long transition() {
        return store.config().transitionMillis();
    }

    // ---------------------------------------------------------------- pending statuses

    /** Marks a record as moving to a status after the transition (to {@code DELETED} to remove it). */
    void pending(ObjectNode record, String now, String then) {
        record.put("Status", now);
        meta(record).put("settleAt", System.currentTimeMillis() + transition()).put("settleTo", then);
    }

    /**
     * The record with its pending status settled, persisted when it changed; null when it has been deleted.
     */
    ObjectNode settle(String account, String region, String kind, String path, ObjectNode record) {
        if (record == null) {
            return null;
        }
        ObjectNode m = meta(record);
        if (m.has("settleAt") && m.get("settleAt").asLong() <= System.currentTimeMillis()) {
            String to = m.get("settleTo").asText();
            m.remove("settleAt");
            m.remove("settleTo");
            if ("DELETED".equals(to)) {
                store.delete(account, region, kind, path);
                return null;
            }
            record.put("Status", to);
            store.put(account, region, kind, path, record);
        }
        return record;
    }

    ObjectNode find(String account, String region, String kind, String path) {
        return settle(account, region, kind, path, store.get(account, region, kind, path).orElse(null));
    }

    List<ObjectNode> all(String account, String region, String kind, String pathPrefix) {
        List<ObjectNode> found = new ArrayList<>();
        for (ObjectNode record : store.list(account, region, kind, pathPrefix)) {
            ObjectNode settled = settle(account, region, kind, meta(record).path("path").asText(), record);
            if (settled != null) {
                found.add(settled);
            }
        }
        return found;
    }

    /** Stores a new record under its path, remembering the path for listings. */
    void save(String account, String region, String kind, String path, ObjectNode record) {
        meta(record).put("path", path);
        store.put(account, region, kind, path, record);
    }

    // ---------------------------------------------------------------- indexes

    ObjectNode index(String account, String region, String id) {
        ObjectNode index = find(account, region, INDEX, id);
        if (index == null) {
            throw notFound("Index " + id + " doesn't exist.");
        }
        return index;
    }

    /** An index that serves requests: not being created or deleted. */
    ObjectNode activeIndex(String account, String region, String id) {
        ObjectNode index = index(account, region, id);
        String status = index.path("Status").asText();
        if ("CREATING".equals(status)) {
            throw KendraStore.validation("Index " + id + " is still being created.");
        }
        if ("DELETING".equals(status)) {
            throw notFound("Index " + id + " is being deleted.");
        }
        if ("FAILED".equals(status)) {
            throw KendraStore.validation("Index " + id + " failed: " + index.path("ErrorMessage").asText());
        }
        return index;
    }

    public ObjectNode createIndex(JsonNode in, String region) {
        String account = store.account();
        if (!store.config().existingCustomer()) {
            throw new AwsException("NotAuthorizedException", "Amazon Kendra is no longer accepting new customers. "
                    + "For more information, see "
                    + "https://docs.aws.amazon.com/kendra/latest/dg/kendra-availability-change.html", 400);
        }
        ObjectNode earlier = store.repeated(account, region, "CreateIndex", in);
        if (earlier != null) {
            return earlier;
        }
        String edition = in.hasNonNull("Edition") ? in.get("Edition").asText() : "ENTERPRISE_EDITION";
        role(text(in, "RoleArn"));
        if (in.has("ServerSideEncryptionConfiguration")) {
            String key = text(in.get("ServerSideEncryptionConfiguration"), "KmsKeyId");
            if (key != null && kms.findKeyArn(key, region).isEmpty()) {
                throw validation("The KMS key " + key + " doesn't exist.");
            }
        }
        tokenConfigurations(in.get("UserTokenConfigurations"));
        long sameEdition = all(account, region, INDEX, "").stream()
                .filter(i -> edition.equals(i.path("Edition").asText())).count();
        if (sameEdition >= MAX_INDEXES_PER_EDITION) {
            throw KendraStore.quota("You have reached the maximum of " + MAX_INDEXES_PER_EDITION + " "
                    + edition + " indexes.");
        }
        String id = KendraStore.uuid();
        ObjectNode index = store.record();
        index.put("Name", text(in, "Name")).put("Id", id).put("Edition", edition).put("RoleArn", text(in, "RoleArn"));
        if (in.has("ServerSideEncryptionConfiguration")) {
            index.set("ServerSideEncryptionConfiguration", in.get("ServerSideEncryptionConfiguration").deepCopy());
        }
        if (in.hasNonNull("Description")) {
            index.put("Description", in.get("Description").asText());
        }
        double now = KendraStore.epoch();
        index.put("CreatedAt", now).put("UpdatedAt", now);
        index.set("DocumentMetadataConfigurations", reservedFields());
        if (!"DEVELOPER_EDITION".equals(edition)) {
            index.putObject("CapacityUnits").put("StorageCapacityUnits", 0).put("QueryCapacityUnits", 0);
        }
        if (in.has("UserTokenConfigurations")) {
            index.set("UserTokenConfigurations", in.get("UserTokenConfigurations").deepCopy());
        }
        index.put("UserContextPolicy", in.hasNonNull("UserContextPolicy") ? in.get("UserContextPolicy").asText()
                : "ATTRIBUTE_FILTER");
        if (in.has("UserGroupResolutionConfiguration")) {
            index.set("UserGroupResolutionConfiguration", in.get("UserGroupResolutionConfiguration").deepCopy());
        }
        meta(index).set("tags", KendraStore.tagsOf(mapper(), in.get("Tags")));
        pending(index, "CREATING", "ACTIVE");
        save(account, region, INDEX, id, index);
        ObjectNode response = mapper().createObjectNode().put("Id", id);
        store.remember(account, region, "CreateIndex", in, response);
        return response;
    }

    private ArrayNode reservedFields() {
        ArrayNode fields = mapper().createArrayNode();
        for (Object[] r : RESERVED) {
            ObjectNode field = fields.addObject();
            field.put("Name", (String) r[0]).put("Type", (String) r[1]);
            ObjectNode relevance = field.putObject("Relevance");
            if ("DATE_VALUE".equals(r[1])) {
                relevance.put("Freshness", "_last_updated_at".equals(r[0])).put("Importance", 1)
                        .put("Duration", "25920000s").put("RankOrder", "ASCENDING");
            } else if ("LONG_VALUE".equals(r[1])) {
                relevance.put("Importance", 1).put("RankOrder", "ASCENDING");
            } else {
                relevance.put("Importance", 1);
                relevance.putObject("ValueImportanceMap");
            }
            field.putObject("Search").put("Facetable", (Boolean) r[2]).put("Searchable", (Boolean) r[3])
                    .put("Displayable", (Boolean) r[4]).put("Sortable", (Boolean) r[5]);
        }
        return fields;
    }

    public ObjectNode describeIndex(JsonNode in, String region) {
        String account = store.account();
        ObjectNode index = index(account, region, text(in, "Id"));
        ObjectNode out = view(index);
        out.set("IndexStatistics", statistics(account, region, index.path("Id").asText()));
        return out;
    }

    /** The index's statistics: its indexed documents and their text bytes, and its FAQ question-answer pairs. */
    ObjectNode statistics(String account, String region, String indexId) {
        int documents = 0;
        long bytes = 0;
        long now = System.currentTimeMillis();
        for (ObjectNode document : store.documents(account, region, indexId)) {
            if (meta(document).path("readyAt").asLong() <= now && !meta(document).has("failure")) {
                documents++;
                bytes += document.path("Text").asText("").getBytes(StandardCharsets.UTF_8).length;
            }
        }
        int answers = 0;
        for (ObjectNode faq : all(account, region, KendraStore.FAQ, indexId + "/")) {
            if ("ACTIVE".equals(faq.path("Status").asText())) {
                answers += meta(faq).path("entries").size();
            }
        }
        ObjectNode stats = mapper().createObjectNode();
        stats.putObject("FaqStatistics").put("IndexedQuestionAnswersCount", answers);
        stats.putObject("TextDocumentStatistics").put("IndexedTextDocumentsCount", documents)
                .put("IndexedTextBytes", bytes);
        return stats;
    }

    public ObjectNode listIndices(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode index : all(store.account(), region, INDEX, "")) {
            ObjectNode item = mapper().createObjectNode();
            item.put("Name", index.path("Name").asText()).put("Id", index.path("Id").asText())
                    .put("Edition", index.path("Edition").asText())
                    .put("CreatedAt", index.path("CreatedAt").asDouble())
                    .put("UpdatedAt", index.path("UpdatedAt").asDouble())
                    .put("Status", index.path("Status").asText());
            items.add(item);
        }
        ObjectNode out = mapper().createObjectNode();
        KendraStore.page(items, in, out, "IndexConfigurationSummaryItems", 100);
        return out;
    }

    public ObjectNode updateIndex(JsonNode in, String region) {
        String account = store.account();
        String id = text(in, "Id");
        ObjectNode index = index(account, region, id);
        String status = index.path("Status").asText();
        if (!"ACTIVE".equals(status) && !"FAILED".equals(status)) {
            throw KendraStore.conflict("Index " + id + " is " + status + "; it can be updated once it is ACTIVE.");
        }
        if (in.hasNonNull("Name")) {
            index.put("Name", in.get("Name").asText());
        }
        if (in.hasNonNull("RoleArn")) {
            role(in.get("RoleArn").asText());
            index.put("RoleArn", in.get("RoleArn").asText());
        }
        if (in.has("Description")) {
            index.put("Description", in.get("Description").asText());
        }
        if (in.has("DocumentMetadataConfigurationUpdates")) {
            index.set("DocumentMetadataConfigurations", mergeFields(index.path("DocumentMetadataConfigurations"),
                    in.get("DocumentMetadataConfigurationUpdates")));
        }
        if (in.has("CapacityUnits")) {
            if ("DEVELOPER_EDITION".equals(index.path("Edition").asText())) {
                throw validation("A DEVELOPER_EDITION index has no capacity units to change.");
            }
            index.set("CapacityUnits", in.get("CapacityUnits").deepCopy());
        }
        if (in.has("UserTokenConfigurations")) {
            tokenConfigurations(in.get("UserTokenConfigurations"));
            index.set("UserTokenConfigurations", in.get("UserTokenConfigurations").deepCopy());
        }
        if (in.hasNonNull("UserContextPolicy")) {
            index.put("UserContextPolicy", in.get("UserContextPolicy").asText());
        }
        if (in.has("UserGroupResolutionConfiguration")) {
            index.set("UserGroupResolutionConfiguration", in.get("UserGroupResolutionConfiguration").deepCopy());
        }
        index.put("UpdatedAt", KendraStore.epoch());
        pending(index, "UPDATING", "ACTIVE");
        store.put(account, region, INDEX, id, index);
        return mapper().createObjectNode();
    }

    private ArrayNode mergeFields(JsonNode current, JsonNode updates) {
        Map<String, ObjectNode> byName = new LinkedHashMap<>();
        current.forEach(f -> byName.put(f.path("Name").asText(), (ObjectNode) f.deepCopy()));
        for (JsonNode update : updates) {
            String name = update.path("Name").asText();
            ObjectNode existing = byName.get(name);
            if (existing != null && !existing.path("Type").asText().equals(update.path("Type").asText())) {
                throw validation("The type of the field " + name + " is " + existing.path("Type").asText()
                        + " and can't be changed.");
            }
            if (existing == null && name.startsWith("_")) {
                throw validation("Field names starting with _ are reserved: " + name + ".");
            }
            ObjectNode merged = existing == null ? mapper().createObjectNode() : existing;
            merged.put("Name", name).put("Type", update.path("Type").asText());
            if (update.has("Relevance")) {
                merged.set("Relevance", update.get("Relevance").deepCopy());
            } else if (!merged.has("Relevance")) {
                merged.putObject("Relevance").put("Importance", 1);
            }
            if (update.has("Search")) {
                JsonNode search = update.get("Search");
                if (search.path("Sortable").asBoolean(false) && "STRING_LIST_VALUE".equals(merged.path("Type")
                        .asText())) {
                    throw validation("A STRING_LIST_VALUE field can't be sortable: " + name + ".");
                }
                merged.set("Search", search.deepCopy());
            } else if (!merged.has("Search")) {
                merged.putObject("Search").put("Facetable", false).put("Searchable", false)
                        .put("Displayable", true).put("Sortable", false);
            }
            byName.put(name, merged);
        }
        if (byName.size() > MAX_FIELDS + RESERVED.size()) {
            throw KendraStore.quota("An index can have at most " + MAX_FIELDS + " custom fields.");
        }
        ArrayNode out = mapper().createArrayNode();
        byName.values().forEach(out::add);
        return out;
    }

    /** The index's fields by name. */
    static Map<String, ObjectNode> fields(ObjectNode index) {
        Map<String, ObjectNode> fields = new LinkedHashMap<>();
        index.path("DocumentMetadataConfigurations").forEach(f -> fields.put(f.path("Name").asText(),
                (ObjectNode) f));
        return fields;
    }

    public ObjectNode deleteIndex(JsonNode in, String region) {
        String account = store.account();
        String id = text(in, "Id");
        ObjectNode index = index(account, region, id);
        if ("DELETING".equals(index.path("Status").asText())) {
            return mapper().createObjectNode();
        }
        pending(index, "DELETING", "DELETED");
        store.put(account, region, INDEX, id, index);
        for (String kind : List.of(KendraStore.DATA_SOURCE, KendraStore.SYNC_JOB, KendraStore.FAQ,
                KendraStore.THESAURUS, KendraStore.EXPERIENCE, KendraStore.FEATURED, KendraStore.BLOCK_LIST,
                KendraStore.ACCESS_CONTROL, KendraStore.GROUP, KendraStore.QUERY, KendraStore.SUGGESTIONS)) {
            store.deleteAll(account, region, kind, id + "/");
        }
        store.deleteDocuments(account, region, id, d -> true);
        return mapper().createObjectNode();
    }

    // ---------------------------------------------------------------- checks

    /** A role Kendra assumes: it must exist and trust {@code kendra.amazonaws.com}. */
    void role(String arn) {
        IamRole role = null;
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            String name = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
            role = iam.findRole(parsed.accountId(), name).filter(found -> arn.equals(found.getArn())).orElse(null);
        } catch (IllegalArgumentException | NullPointerException e) {
            role = null;
        }
        if (role == null || !TRUST.allowsService(role.getAssumeRolePolicyDocument(), ServicePrincipals.of(
                KendraStore.SERVICE))) {
            throw validation("Amazon Kendra can't assume the role " + arn + ". Make sure it exists and trusts "
                    + ServicePrincipals.of(KendraStore.SERVICE) + ".");
        }
    }

    private static void tokenConfigurations(JsonNode configs) {
        if (configs == null || configs.isNull()) {
            return;
        }
        if (configs.size() > 1) {
            throw validation("An index can have at most one user token configuration.");
        }
        for (JsonNode config : configs) {
            JsonNode jwt = config.get("JwtTokenTypeConfiguration");
            if (jwt != null) {
                String location = jwt.path("KeyLocation").asText();
                if ("URL".equals(location) && !jwt.hasNonNull("URL")) {
                    throw validation("A JWT token configuration with KeyLocation URL needs a URL.");
                }
                if ("SECRET_MANAGER".equals(location) && !jwt.hasNonNull("SecretManagerArn")) {
                    throw validation("A JWT token configuration with KeyLocation SECRET_MANAGER needs a "
                            + "SecretManagerArn.");
                }
            }
            if (jwt == null && !config.has("JsonTokenTypeConfiguration")) {
                throw validation("A user token configuration needs a JWT or JSON token type configuration.");
            }
        }
    }

    // ---------------------------------------------------------------- tags

    /** The record an ARN names: an index, or a resource under one. */
    ObjectNode resourceOf(String account, String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("The ARN " + arn + " is not valid.");
        }
        String[] parts = parsed.resource().split("/");
        if (!account.equals(parsed.accountId()) || parts.length < 2 || !"index".equals(parts[0])) {
            throw notFound("The resource " + arn + " doesn't exist.");
        }
        ObjectNode found;
        if (parts.length == 2) {
            found = find(account, parsed.region(), INDEX, parts[1]);
        } else if (parts.length == 4 && Set.of(KendraStore.DATA_SOURCE, KendraStore.FAQ, KendraStore.THESAURUS,
                KendraStore.FEATURED, KendraStore.BLOCK_LIST).contains(parts[2])) {
            found = find(account, parsed.region(), parts[2], parts[1] + "/" + parts[3]);
        } else {
            found = null;
        }
        if (found == null) {
            throw notFound("The resource " + arn + " doesn't exist.");
        }
        meta(found).put("kind", parts.length == 2 ? INDEX : parts[2])
                .put("path", parts.length == 2 ? parts[1] : parts[1] + "/" + parts[3]);
        return found;
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        String account = store.account();
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(text(in, "ResourceARN"));
        ObjectNode record = resourceOf(account, region, text(in, "ResourceARN"));
        ObjectNode tags = meta(record).has("tags") ? (ObjectNode) meta(record).get("tags")
                : meta(record).putObject("tags");
        tags.setAll(KendraStore.tagsOf(mapper(), in.get("Tags")));
        if (tags.size() > 200) {
            throw validation("A resource can have at most 200 tags.");
        }
        store.put(account, parsed.region(), meta(record).path("kind").asText(), meta(record).path("path").asText(),
                record);
        return mapper().createObjectNode();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        String account = store.account();
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(text(in, "ResourceARN"));
        ObjectNode record = resourceOf(account, region, text(in, "ResourceARN"));
        if (meta(record).has("tags")) {
            ObjectNode tags = (ObjectNode) meta(record).get("tags");
            in.path("TagKeys").forEach(key -> tags.remove(key.asText()));
        }
        store.put(account, parsed.region(), meta(record).path("kind").asText(), meta(record).path("path").asText(),
                record);
        return mapper().createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        ObjectNode record = resourceOf(store.account(), region, text(in, "ResourceARN"));
        ObjectNode out = mapper().createObjectNode();
        out.set("Tags", KendraStore.tagList(mapper(), meta(record).get("tags")));
        return out;
    }
}
