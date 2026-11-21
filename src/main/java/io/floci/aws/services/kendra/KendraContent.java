package io.floci.aws.services.kendra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.identitystore.IdentityStoreService;
import io.floci.aws.services.identitystore.model.Group;
import io.floci.aws.services.identitystore.model.User;
import io.floci.aws.services.ssoadmin.SsoAdminService;
import io.floci.aws.services.ssoadmin.model.SsoInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.kendra.KendraStore.ACCESS_CONTROL;
import static io.floci.aws.services.kendra.KendraStore.BLOCK_LIST;
import static io.floci.aws.services.kendra.KendraStore.EXPERIENCE;
import static io.floci.aws.services.kendra.KendraStore.FAQ;
import static io.floci.aws.services.kendra.KendraStore.FEATURED;
import static io.floci.aws.services.kendra.KendraStore.GROUP;
import static io.floci.aws.services.kendra.KendraStore.THESAURUS;
import static io.floci.aws.services.kendra.KendraStore.meta;
import static io.floci.aws.services.kendra.KendraStore.notFound;
import static io.floci.aws.services.kendra.KendraStore.text;
import static io.floci.aws.services.kendra.KendraStore.validation;
import static io.floci.aws.services.kendra.KendraStore.view;

/**
 * What an index holds besides documents: FAQs, a thesaurus, a query suggestions block list, featured results sets,
 * access control configurations, principal mappings (groups and their members), and search experiences.
 *
 * <p>FAQs, thesauri and block lists are read from S3 with the role they're given: FAQs as CSV (question, answer and
 * an optional URL), CSV with a header ({@code _question}, {@code _answer}, {@code _source_uri} and attribute
 * columns) or JSON ({@code FaqDocuments}); thesauri in Solr synonym format; block lists one phrase per line. A file
 * that can't be read or parsed leaves the resource {@code FAILED} with the reason. Principal mappings are
 * {@code PROCESSING} for a transition before they apply; a mapping with an ordering id below the group's latest one
 * fails. Experiences serve a search page at their endpoint and take Identity Center users and groups as entities.
 */
@ApplicationScoped
public class KendraContent {

    static final int MAX_FEATURED_DOCUMENTS = 4;
    static final int MAX_FEATURED_QUERIES = 49;
    static final int MAX_FEATURED_SETS = 50;

    private final KendraIndexes indexes;
    private final KendraStore store;
    private final KendraDocuments documents;
    private final IdentityStoreService identityStore;
    private final SsoAdminService ssoAdmin;

    @Inject
    public KendraContent(KendraIndexes indexes, KendraStore store, KendraDocuments documents,
                         IdentityStoreService identityStore, SsoAdminService ssoAdmin) {
        this.indexes = indexes;
        this.store = store;
        this.documents = documents;
        this.identityStore = identityStore;
        this.ssoAdmin = ssoAdmin;
    }

    private ObjectMapper mapper() {
        return store.mapper();
    }

    private ObjectNode empty() {
        return mapper().createObjectNode();
    }

    private ObjectNode require(String account, String region, String kind, String indexId, String id, String what) {
        indexes.index(account, region, indexId);
        ObjectNode record = indexes.find(account, region, kind, indexId + "/" + id);
        if (record == null) {
            throw notFound(what + " " + id + " doesn't exist.");
        }
        return record;
    }

    private String s3Text(JsonNode path) {
        return new String(documents.read(text(path, "Bucket"), text(path, "Key")), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- FAQs

    public ObjectNode createFaq(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.activeIndex(account, region, indexId);
        ObjectNode earlier = store.repeated(account, region, "CreateFaq", in);
        if (earlier != null) {
            return earlier;
        }
        indexes.role(text(in, "RoleArn"));
        String id = KendraStore.uuid();
        ObjectNode faq = store.record();
        double now = KendraStore.epoch();
        faq.put("Id", id).put("IndexId", indexId).put("Name", text(in, "Name"));
        if (in.has("Description")) {
            faq.put("Description", text(in, "Description"));
        }
        faq.put("CreatedAt", now).put("UpdatedAt", now);
        faq.set("S3Path", in.get("S3Path").deepCopy());
        faq.put("RoleArn", text(in, "RoleArn"))
                .put("FileFormat", in.hasNonNull("FileFormat") ? text(in, "FileFormat") : "CSV")
                .put("LanguageCode", in.hasNonNull("LanguageCode") ? text(in, "LanguageCode") : "en");
        meta(faq).set("tags", KendraStore.tagsOf(mapper(), in.get("Tags")));
        try {
            meta(faq).set("entries", faqEntries(s3Text(in.get("S3Path")), faq.path("FileFormat").asText()));
            indexes.pending(faq, "CREATING", "ACTIVE");
        } catch (AwsException | IllegalArgumentException e) {
            faq.put("ErrorMessage", e.getMessage());
            meta(faq).putArray("entries");
            indexes.pending(faq, "CREATING", "FAILED");
        }
        indexes.save(account, region, FAQ, indexId + "/" + id, faq);
        ObjectNode response = empty().put("Id", id);
        store.remember(account, region, "CreateFaq", in, response);
        return response;
    }

    ArrayNode faqEntries(String content, String format) {
        ArrayNode entries = mapper().createArrayNode();
        if ("JSON".equals(format)) {
            JsonNode doc;
            try {
                doc = mapper().readTree(content);
            } catch (IOException e) {
                throw new IllegalArgumentException("The FAQ file is not valid JSON.");
            }
            for (JsonNode item : doc.path("FaqDocuments")) {
                if (!item.hasNonNull("Question") || !item.hasNonNull("Answer")) {
                    throw new IllegalArgumentException("Each FAQ document needs a Question and an Answer.");
                }
                ObjectNode entry = entries.addObject();
                entry.put("question", item.get("Question").asText()).put("answer", item.get("Answer").asText());
                ObjectNode attributes = entry.putObject("attributes");
                item.path("Attributes").fields().forEachRemaining(e -> {
                    if ("_source_uri".equals(e.getKey())) {
                        entry.put("uri", e.getValue().asText());
                    }
                    attributes.set(e.getKey(), attributeValue(e.getValue()));
                });
                if (item.has("AccessControlList")) {
                    entry.set("acl", item.get("AccessControlList").deepCopy());
                }
            }
            return entries;
        }
        List<List<String>> rows = KendraText.csv(content);
        List<String> header = null;
        if ("CSV_WITH_HEADER".equals(format)) {
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("The FAQ file has no header row.");
            }
            header = rows.remove(0).stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
            if (!header.contains("_question") || !header.contains("_answer")) {
                throw new IllegalArgumentException("The FAQ header needs _question and _answer columns.");
            }
        }
        for (List<String> row : rows) {
            ObjectNode entry = mapper().createObjectNode();
            ObjectNode attributes = entry.putObject("attributes");
            if (header == null) {
                if (row.size() < 2) {
                    throw new IllegalArgumentException("Each FAQ row needs a question and an answer.");
                }
                entry.put("question", row.get(0)).put("answer", row.get(1));
                if (row.size() > 2 && !row.get(2).isEmpty()) {
                    entry.put("uri", row.get(2));
                    attributes.putObject("_source_uri").put("StringValue", row.get(2));
                }
            } else {
                for (int c = 0; c < header.size() && c < row.size(); c++) {
                    String column = header.get(c);
                    switch (column) {
                        case "_question" -> entry.put("question", row.get(c));
                        case "_answer" -> entry.put("answer", row.get(c));
                        default -> {
                            if ("_source_uri".equals(column)) {
                                entry.put("uri", row.get(c));
                            }
                            if (!row.get(c).isEmpty()) {
                                attributes.putObject(column).put("StringValue", row.get(c));
                            }
                        }
                    }
                }
            }
            if (entry.path("question").asText("").isEmpty() || entry.path("answer").asText("").isEmpty()) {
                throw new IllegalArgumentException("Each FAQ row needs a question and an answer.");
            }
            entries.add(entry);
        }
        return entries;
    }

    private ObjectNode attributeValue(JsonNode value) {
        ObjectNode out = mapper().createObjectNode();
        if (value.isArray()) {
            ArrayNode list = out.putArray("StringListValue");
            value.forEach(v -> list.add(v.asText()));
        } else if (value.isIntegralNumber()) {
            out.put("LongValue", value.asLong());
        } else {
            out.put("StringValue", value.asText());
        }
        return out;
    }

    public ObjectNode describeFaq(JsonNode in, String region) {
        ObjectNode faq = require(store.account(), region, FAQ, text(in, "IndexId"), text(in, "Id"), "FAQ");
        return view(faq);
    }

    public ObjectNode listFaqs(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.index(account, region, indexId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode faq : indexes.all(account, region, FAQ, indexId + "/")) {
            ObjectNode item = empty();
            for (String field : List.of("Id", "Name", "Status", "CreatedAt", "UpdatedAt", "FileFormat",
                    "LanguageCode")) {
                if (faq.has(field)) {
                    item.set(field, faq.get(field));
                }
            }
            items.add(item);
        }
        ObjectNode out = empty();
        KendraStore.page(items, in, out, "FaqSummaryItems", 100);
        return out;
    }

    public ObjectNode deleteFaq(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        ObjectNode faq = require(account, region, FAQ, indexId, text(in, "Id"), "FAQ");
        if ("CREATING".equals(faq.path("Status").asText()) || "UPDATING".equals(faq.path("Status").asText())) {
            throw KendraStore.conflict("FAQ " + text(in, "Id") + " is " + faq.path("Status").asText() + ".");
        }
        indexes.pending(faq, "DELETING", "DELETED");
        store.put(account, region, FAQ, indexId + "/" + text(in, "Id"), faq);
        return empty();
    }

    /** The question-answer pairs of an index's active FAQs. */
    List<KendraSearch.Faq> faqs(String account, String region, String indexId) {
        List<KendraSearch.Faq> faqs = new ArrayList<>();
        for (ObjectNode faq : indexes.all(account, region, FAQ, indexId + "/")) {
            if (!"ACTIVE".equals(faq.path("Status").asText())) {
                continue;
            }
            int n = 0;
            for (JsonNode entry : meta(faq).path("entries")) {
                ObjectNode attributes = entry.path("attributes").isObject()
                        ? ((ObjectNode) entry.get("attributes")).deepCopy() : empty();
                attributes.putObject("_faq_id").put("StringValue", faq.path("Id").asText());
                attributes.putObject("_language_code").put("StringValue", faq.path("LanguageCode").asText("en"));
                faqs.add(new KendraSearch.Faq(faq.path("Id").asText(), faq.path("Id").asText() + "_" + n++,
                        entry.path("question").asText(), entry.path("answer").asText(), attributes,
                        entry.path("uri").asText(null)));
            }
        }
        return faqs;
    }

    // ---------------------------------------------------------------- thesauri

    public ObjectNode createThesaurus(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.activeIndex(account, region, indexId);
        ObjectNode earlier = store.repeated(account, region, "CreateThesaurus", in);
        if (earlier != null) {
            return earlier;
        }
        if (!indexes.all(account, region, THESAURUS, indexId + "/").isEmpty()) {
            throw KendraStore.quota("An index can have one thesaurus.");
        }
        indexes.role(text(in, "RoleArn"));
        String id = KendraStore.uuid();
        ObjectNode thesaurus = store.record();
        double now = KendraStore.epoch();
        thesaurus.put("Id", id).put("IndexId", indexId).put("Name", text(in, "Name"));
        if (in.has("Description")) {
            thesaurus.put("Description", text(in, "Description"));
        }
        thesaurus.put("CreatedAt", now).put("UpdatedAt", now).put("RoleArn", text(in, "RoleArn"));
        thesaurus.set("SourceS3Path", in.get("SourceS3Path").deepCopy());
        meta(thesaurus).set("tags", KendraStore.tagsOf(mapper(), in.get("Tags")));
        loadThesaurus(thesaurus, "CREATING", "FAILED");
        indexes.save(account, region, THESAURUS, indexId + "/" + id, thesaurus);
        ObjectNode response = empty().put("Id", id);
        store.remember(account, region, "CreateThesaurus", in, response);
        return response;
    }

    private void loadThesaurus(ObjectNode thesaurus, String pendingStatus, String failedStatus) {
        try {
            byte[] bytes = documents.read(text(thesaurus.get("SourceS3Path"), "Bucket"),
                    text(thesaurus.get("SourceS3Path"), "Key"));
            ArrayNode rules = synonymRules(new String(bytes, StandardCharsets.UTF_8));
            Set<String> terms = new LinkedHashSet<>();
            rules.forEach(rule -> {
                rule.path("from").forEach(t -> terms.add(t.asText()));
                rule.path("to").forEach(t -> terms.add(t.asText()));
            });
            meta(thesaurus).set("rules", rules);
            thesaurus.put("FileSizeBytes", bytes.length).put("TermCount", terms.size())
                    .put("SynonymRuleCount", rules.size());
            thesaurus.remove("ErrorMessage");
            indexes.pending(thesaurus, pendingStatus, "ACTIVE");
        } catch (AwsException | IllegalArgumentException e) {
            thesaurus.put("ErrorMessage", e.getMessage());
            indexes.pending(thesaurus, pendingStatus, failedStatus);
        }
    }

    /** Solr synonym rules: {@code a, b, c} (equivalent) or {@code a, b => c, d} (explicit). */
    ArrayNode synonymRules(String text) {
        ArrayNode rules = mapper().createArrayNode();
        int line = 0;
        for (String raw : text.split("\r?\n")) {
            line++;
            String rule = raw.strip();
            if (rule.isEmpty() || rule.startsWith("#")) {
                continue;
            }
            ObjectNode r = rules.addObject();
            String[] sides = rule.split("=>", -1);
            if (sides.length > 2) {
                throw new IllegalArgumentException("Line " + line + " of the thesaurus has more than one =>.");
            }
            ArrayNode from = r.putArray("from");
            for (String term : sides[0].split(",")) {
                if (!term.isBlank()) {
                    from.add(term.strip().toLowerCase(Locale.ROOT));
                }
            }
            if (sides.length == 2) {
                ArrayNode to = r.putArray("to");
                for (String term : sides[1].split(",")) {
                    if (!term.isBlank()) {
                        to.add(term.strip().toLowerCase(Locale.ROOT));
                    }
                }
                if (to.isEmpty() || from.isEmpty()) {
                    throw new IllegalArgumentException("Line " + line + " of the thesaurus has an empty side.");
                }
            } else if (from.size() < 2) {
                throw new IllegalArgumentException("Line " + line + " of the thesaurus has a single term.");
            }
        }
        return rules;
    }

    public ObjectNode describeThesaurus(JsonNode in, String region) {
        return view(require(store.account(), region, THESAURUS, text(in, "IndexId"), text(in, "Id"), "Thesaurus"));
    }

    public ObjectNode updateThesaurus(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        ObjectNode thesaurus = require(account, region, THESAURUS, indexId, text(in, "Id"), "Thesaurus");
        if (in.hasNonNull("Name")) {
            thesaurus.put("Name", text(in, "Name"));
        }
        if (in.has("Description")) {
            thesaurus.put("Description", text(in, "Description"));
        }
        if (in.hasNonNull("RoleArn")) {
            indexes.role(text(in, "RoleArn"));
            thesaurus.put("RoleArn", text(in, "RoleArn"));
        }
        if (in.has("SourceS3Path")) {
            thesaurus.set("SourceS3Path", in.get("SourceS3Path").deepCopy());
        }
        thesaurus.put("UpdatedAt", KendraStore.epoch());
        loadThesaurus(thesaurus, "UPDATING", "ACTIVE_BUT_UPDATE_FAILED");
        store.put(account, region, THESAURUS, indexId + "/" + text(in, "Id"), thesaurus);
        return empty();
    }

    public ObjectNode listThesauri(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.index(account, region, indexId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode t : indexes.all(account, region, THESAURUS, indexId + "/")) {
            ObjectNode item = empty();
            for (String field : List.of("Id", "Name", "Status", "CreatedAt", "UpdatedAt")) {
                item.set(field, t.get(field));
            }
            items.add(item);
        }
        ObjectNode out = empty();
        KendraStore.page(items, in, out, "ThesaurusSummaryItems", 100);
        return out;
    }

    public ObjectNode deleteThesaurus(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        ObjectNode thesaurus = require(account, region, THESAURUS, indexId, text(in, "Id"), "Thesaurus");
        indexes.pending(thesaurus, "DELETING", "DELETED");
        store.put(account, region, THESAURUS, indexId + "/" + text(in, "Id"), thesaurus);
        return empty();
    }

    /** The synonyms the index's active thesaurus gives each stem. */
    KendraSearch.Synonyms synonyms(String account, String region, String indexId) {
        Map<String, List<List<String>>> alternatives = new HashMap<>();
        Set<String> replaced = new LinkedHashSet<>();
        for (ObjectNode thesaurus : indexes.all(account, region, THESAURUS, indexId + "/")) {
            if (!thesaurus.path("Status").asText().startsWith("ACTIVE")) {
                continue;
            }
            for (JsonNode rule : meta(thesaurus).path("rules")) {
                List<List<String>> from = phrases(rule.path("from"));
                if (rule.has("to")) {
                    List<List<String>> to = phrases(rule.path("to"));
                    for (List<String> f : from) {
                        if (f.size() == 1) {
                            alternatives.computeIfAbsent(f.get(0), k -> new ArrayList<>()).addAll(to);
                            if (to.stream().noneMatch(t -> t.equals(f))) {
                                replaced.add(f.get(0));
                            }
                        }
                    }
                } else {
                    for (List<String> f : from) {
                        if (f.size() == 1) {
                            for (List<String> other : from) {
                                if (!other.equals(f)) {
                                    alternatives.computeIfAbsent(f.get(0), k -> new ArrayList<>()).add(other);
                                }
                            }
                        }
                    }
                }
            }
        }
        return new KendraSearch.Synonyms(alternatives, replaced);
    }

    private static List<List<String>> phrases(JsonNode terms) {
        List<List<String>> phrases = new ArrayList<>();
        terms.forEach(t -> {
            List<String> stems = KendraAnalyzer.tokens(t.asText()).stream().map(KendraAnalyzer.Token::stem).toList();
            if (!stems.isEmpty()) {
                phrases.add(stems);
            }
        });
        return phrases;
    }

    // ---------------------------------------------------------------- query suggestions block lists

    public ObjectNode createQuerySuggestionsBlockList(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.activeIndex(account, region, indexId);
        ObjectNode earlier = store.repeated(account, region, "CreateQuerySuggestionsBlockList", in);
        if (earlier != null) {
            return earlier;
        }
        if (!indexes.all(account, region, BLOCK_LIST, indexId + "/").isEmpty()) {
            throw KendraStore.quota("An index can have one query suggestions block list.");
        }
        indexes.role(text(in, "RoleArn"));
        String id = KendraStore.uuid();
        ObjectNode list = store.record();
        double now = KendraStore.epoch();
        list.put("IndexId", indexId).put("Id", id).put("Name", text(in, "Name"));
        if (in.has("Description")) {
            list.put("Description", text(in, "Description"));
        }
        list.put("CreatedAt", now).put("UpdatedAt", now).put("RoleArn", text(in, "RoleArn"));
        list.set("SourceS3Path", in.get("SourceS3Path").deepCopy());
        meta(list).set("tags", KendraStore.tagsOf(mapper(), in.get("Tags")));
        loadBlockList(list, "CREATING", "FAILED");
        indexes.save(account, region, BLOCK_LIST, indexId + "/" + id, list);
        ObjectNode response = empty().put("Id", id);
        store.remember(account, region, "CreateQuerySuggestionsBlockList", in, response);
        return response;
    }

    private void loadBlockList(ObjectNode list, String pendingStatus, String failedStatus) {
        try {
            byte[] bytes = documents.read(text(list.get("SourceS3Path"), "Bucket"),
                    text(list.get("SourceS3Path"), "Key"));
            ArrayNode phrases = mapper().createArrayNode();
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\r?\n")) {
                if (!line.isBlank()) {
                    phrases.add(line.strip().toLowerCase(Locale.ROOT));
                }
            }
            meta(list).set("phrases", phrases);
            list.put("ItemCount", phrases.size()).put("FileSizeBytes", bytes.length);
            list.remove("ErrorMessage");
            indexes.pending(list, pendingStatus, "ACTIVE");
        } catch (AwsException e) {
            list.put("ErrorMessage", e.getMessage());
            indexes.pending(list, pendingStatus, failedStatus);
        }
    }

    public ObjectNode describeQuerySuggestionsBlockList(JsonNode in, String region) {
        return view(require(store.account(), region, BLOCK_LIST, text(in, "IndexId"), text(in, "Id"),
                "Query suggestions block list"));
    }

    public ObjectNode updateQuerySuggestionsBlockList(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        ObjectNode list = require(account, region, BLOCK_LIST, indexId, text(in, "Id"),
                "Query suggestions block list");
        if (in.hasNonNull("Name")) {
            list.put("Name", text(in, "Name"));
        }
        if (in.has("Description")) {
            list.put("Description", text(in, "Description"));
        }
        if (in.hasNonNull("RoleArn")) {
            indexes.role(text(in, "RoleArn"));
            list.put("RoleArn", text(in, "RoleArn"));
        }
        if (in.has("SourceS3Path")) {
            list.set("SourceS3Path", in.get("SourceS3Path").deepCopy());
        }
        list.put("UpdatedAt", KendraStore.epoch());
        loadBlockList(list, "UPDATING", "ACTIVE_BUT_UPDATE_FAILED");
        store.put(account, region, BLOCK_LIST, indexId + "/" + text(in, "Id"), list);
        return empty();
    }

    public ObjectNode listQuerySuggestionsBlockLists(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.index(account, region, indexId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode list : indexes.all(account, region, BLOCK_LIST, indexId + "/")) {
            ObjectNode item = empty();
            for (String field : List.of("Id", "Name", "Status", "CreatedAt", "UpdatedAt", "ItemCount")) {
                if (list.has(field)) {
                    item.set(field, list.get(field));
                }
            }
            items.add(item);
        }
        ObjectNode out = empty();
        KendraStore.page(items, in, out, "BlockListSummaryItems", 100);
        return out;
    }

    public ObjectNode deleteQuerySuggestionsBlockList(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        ObjectNode list = require(account, region, BLOCK_LIST, indexId, text(in, "Id"),
                "Query suggestions block list");
        indexes.pending(list, "DELETING", "DELETED");
        store.put(account, region, BLOCK_LIST, indexId + "/" + text(in, "Id"), list);
        return empty();
    }

    /** The phrases blocked from the index's query suggestions. */
    List<String> blockedPhrases(String account, String region, String indexId) {
        List<String> phrases = new ArrayList<>();
        for (ObjectNode list : indexes.all(account, region, BLOCK_LIST, indexId + "/")) {
            if (list.path("Status").asText().startsWith("ACTIVE")) {
                meta(list).path("phrases").forEach(p -> phrases.add(p.asText()));
            }
        }
        return phrases;
    }

    // ---------------------------------------------------------------- featured results

    public ObjectNode createFeaturedResultsSet(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.activeIndex(account, region, indexId);
        ObjectNode earlier = store.repeated(account, region, "CreateFeaturedResultsSet", in);
        if (earlier != null) {
            return earlier;
        }
        List<ObjectNode> sets = indexes.all(account, region, FEATURED, indexId + "/");
        if (sets.size() >= MAX_FEATURED_SETS) {
            throw KendraStore.quota("An index can have at most " + MAX_FEATURED_SETS + " featured results sets.");
        }
        String name = text(in, "FeaturedResultsSetName");
        if (sets.stream().anyMatch(s -> name.equals(s.path("FeaturedResultsSetName").asText()))) {
            throw KendraStore.conflict("A featured results set named " + name + " already exists.");
        }
        checkFeatured(in, sets, null);
        String id = KendraStore.uuid();
        ObjectNode set = store.record();
        long now = System.currentTimeMillis();
        set.put("FeaturedResultsSetId", id).put("FeaturedResultsSetName", name);
        if (in.has("Description")) {
            set.put("Description", text(in, "Description"));
        }
        set.put("Status", in.hasNonNull("Status") ? text(in, "Status") : "ACTIVE");
        set.set("QueryTexts", in.has("QueryTexts") ? in.get("QueryTexts").deepCopy() : mapper().createArrayNode());
        set.set("FeaturedDocuments", in.has("FeaturedDocuments") ? in.get("FeaturedDocuments").deepCopy()
                : mapper().createArrayNode());
        set.put("LastUpdatedTimestamp", now).put("CreationTimestamp", now);
        meta(set).set("tags", KendraStore.tagsOf(mapper(), in.get("Tags")));
        indexes.save(account, region, FEATURED, indexId + "/" + id, set);
        ObjectNode response = empty();
        response.set("FeaturedResultsSet", view(set));
        store.remember(account, region, "CreateFeaturedResultsSet", in, response);
        return response;
    }

    private void checkFeatured(JsonNode in, List<ObjectNode> sets, String self) {
        if (in.path("FeaturedDocuments").size() > MAX_FEATURED_DOCUMENTS) {
            throw validation("A featured results set can feature at most " + MAX_FEATURED_DOCUMENTS
                    + " documents.");
        }
        if (in.path("QueryTexts").size() > MAX_FEATURED_QUERIES) {
            throw validation("A featured results set can have at most " + MAX_FEATURED_QUERIES + " queries.");
        }
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (JsonNode query : in.path("QueryTexts")) {
            String q = query.asText().strip().toLowerCase(Locale.ROOT);
            for (ObjectNode set : sets) {
                if (set.path("FeaturedResultsSetId").asText().equals(self)) {
                    continue;
                }
                for (JsonNode other : set.path("QueryTexts")) {
                    if (other.asText().strip().toLowerCase(Locale.ROOT).equals(q)) {
                        conflicts.add(Map.<String, Object>of("QueryText", query.asText(), "SetName",
                                set.path("FeaturedResultsSetName").asText(), "SetId",
                                set.path("FeaturedResultsSetId").asText()));
                    }
                }
            }
        }
        if (!conflicts.isEmpty()) {
            throw new AwsException("FeaturedResultsConflictException", "Each query can be featured by one "
                    + "featured results set; these queries already are.", 400,
                    Map.<String, Object>of("ConflictingItems", conflicts));
        }
    }

    public ObjectNode describeFeaturedResultsSet(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        ObjectNode set = require(account, region, FEATURED, indexId, text(in, "FeaturedResultsSetId"),
                "Featured results set");
        ObjectNode out = view(set);
        out.remove("FeaturedDocuments");
        ArrayNode with = out.putArray("FeaturedDocumentsWithMetadata");
        ArrayNode missing = out.putArray("FeaturedDocumentsMissing");
        for (JsonNode featured : set.path("FeaturedDocuments")) {
            String id = featured.path("Id").asText();
            ObjectNode document = store.document(account, region, indexId, id).orElse(null);
            if (document == null) {
                missing.addObject().put("Id", id);
            } else {
                ObjectNode item = with.addObject().put("Id", id);
                if (document.has("Title")) {
                    item.put("Title", document.get("Title").asText());
                }
                JsonNode uri = document.path("Attributes").path("_source_uri").path("StringValue");
                if (!uri.isMissingNode()) {
                    item.put("URI", uri.asText());
                }
            }
        }
        return out;
    }

    public ObjectNode updateFeaturedResultsSet(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        String id = text(in, "FeaturedResultsSetId");
        ObjectNode set = require(account, region, FEATURED, indexId, id, "Featured results set");
        checkFeatured(in, indexes.all(account, region, FEATURED, indexId + "/"), id);
        if (in.hasNonNull("FeaturedResultsSetName")) {
            set.put("FeaturedResultsSetName", text(in, "FeaturedResultsSetName"));
        }
        if (in.has("Description")) {
            set.put("Description", text(in, "Description"));
        }
        if (in.hasNonNull("Status")) {
            set.put("Status", text(in, "Status"));
        }
        if (in.has("QueryTexts")) {
            set.set("QueryTexts", in.get("QueryTexts").deepCopy());
        }
        if (in.has("FeaturedDocuments")) {
            set.set("FeaturedDocuments", in.get("FeaturedDocuments").deepCopy());
        }
        set.put("LastUpdatedTimestamp", System.currentTimeMillis());
        store.put(account, region, FEATURED, indexId + "/" + id, set);
        ObjectNode out = empty();
        out.set("FeaturedResultsSet", view(set));
        return out;
    }

    public ObjectNode listFeaturedResultsSets(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.index(account, region, indexId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode set : indexes.all(account, region, FEATURED, indexId + "/")) {
            ObjectNode item = empty();
            for (String field : List.of("FeaturedResultsSetId", "FeaturedResultsSetName", "Status",
                    "LastUpdatedTimestamp", "CreationTimestamp")) {
                item.set(field, set.get(field));
            }
            items.add(item);
        }
        ObjectNode out = empty();
        KendraStore.page(items, in, out, "FeaturedResultsSetSummaryItems", 100);
        return out;
    }

    public ObjectNode batchDeleteFeaturedResultsSet(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.index(account, region, indexId);
        ObjectNode out = empty();
        ArrayNode errors = out.putArray("Errors");
        for (JsonNode id : in.path("FeaturedResultsSetIds")) {
            if (indexes.find(account, region, FEATURED, indexId + "/" + id.asText()) == null) {
                errors.addObject().put("Id", id.asText()).put("ErrorCode", "InvalidRequest")
                        .put("ErrorMessage", "Featured results set " + id.asText() + " doesn't exist.");
            } else {
                store.delete(account, region, FEATURED, indexId + "/" + id.asText());
            }
        }
        return out;
    }

    /** The active featured results set a query's text is featured by, or null. */
    ObjectNode featuredFor(String account, String region, String indexId, String queryText) {
        if (queryText == null) {
            return null;
        }
        String q = queryText.strip().toLowerCase(Locale.ROOT);
        for (ObjectNode set : indexes.all(account, region, FEATURED, indexId + "/")) {
            if (!"ACTIVE".equals(set.path("Status").asText())) {
                continue;
            }
            for (JsonNode text : set.path("QueryTexts")) {
                if (text.asText().strip().toLowerCase(Locale.ROOT).equals(q)) {
                    return set;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- access control configurations

    public ObjectNode createAccessControlConfiguration(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.activeIndex(account, region, indexId);
        ObjectNode earlier = store.repeated(account, region, "CreateAccessControlConfiguration", in);
        if (earlier != null) {
            return earlier;
        }
        String id = KendraStore.uuid();
        ObjectNode config = store.record();
        config.put("Id", id).put("Name", text(in, "Name"));
        if (in.has("Description")) {
            config.put("Description", text(in, "Description"));
        }
        if (in.has("AccessControlList")) {
            config.set("AccessControlList", in.get("AccessControlList").deepCopy());
        }
        if (in.has("HierarchicalAccessControlList")) {
            config.set("HierarchicalAccessControlList", in.get("HierarchicalAccessControlList").deepCopy());
        }
        indexes.save(account, region, ACCESS_CONTROL, indexId + "/" + id, config);
        ObjectNode response = empty().put("Id", id);
        store.remember(account, region, "CreateAccessControlConfiguration", in, response);
        return response;
    }

    public ObjectNode describeAccessControlConfiguration(JsonNode in, String region) {
        ObjectNode config = require(store.account(), region, ACCESS_CONTROL, text(in, "IndexId"), text(in, "Id"),
                "Access control configuration");
        ObjectNode out = view(config);
        out.remove("Id");
        return out;
    }

    public ObjectNode updateAccessControlConfiguration(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        ObjectNode config = require(account, region, ACCESS_CONTROL, indexId, text(in, "Id"),
                "Access control configuration");
        if (in.hasNonNull("Name")) {
            config.put("Name", text(in, "Name"));
        }
        if (in.has("Description")) {
            config.put("Description", text(in, "Description"));
        }
        if (in.has("AccessControlList")) {
            config.set("AccessControlList", in.get("AccessControlList").deepCopy());
        }
        if (in.has("HierarchicalAccessControlList")) {
            config.set("HierarchicalAccessControlList", in.get("HierarchicalAccessControlList").deepCopy());
        }
        store.put(account, region, ACCESS_CONTROL, indexId + "/" + text(in, "Id"), config);
        return empty();
    }

    public ObjectNode deleteAccessControlConfiguration(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        String id = text(in, "Id");
        require(account, region, ACCESS_CONTROL, indexId, id, "Access control configuration");
        for (ObjectNode document : store.documents(account, region, indexId)) {
            if (id.equals(document.path("AccessControlConfigurationId").asText(null))) {
                throw KendraStore.conflict("Access control configuration " + id + " is used by document "
                        + document.path("Id").asText() + ".");
            }
        }
        store.delete(account, region, ACCESS_CONTROL, indexId + "/" + id);
        return empty();
    }

    public ObjectNode listAccessControlConfigurations(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.index(account, region, indexId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode config : indexes.all(account, region, ACCESS_CONTROL, indexId + "/")) {
            items.add(empty().put("Id", config.path("Id").asText()));
        }
        ObjectNode out = empty();
        KendraStore.page(items, in, out, "AccessControlConfigurations", 100);
        return out;
    }

    /** The access control lists a document is governed by: its own, or its access control configuration's. */
    JsonNode[] accessLists(String account, String region, String indexId, ObjectNode document) {
        String configId = document.path("AccessControlConfigurationId").asText(null);
        if (configId != null) {
            ObjectNode config = indexes.find(account, region, ACCESS_CONTROL, indexId + "/" + configId);
            if (config != null) {
                return new JsonNode[] {config.get("AccessControlList"), config.get("HierarchicalAccessControlList")};
            }
        }
        return new JsonNode[] {document.get("AccessControlList"), document.get("HierarchicalAccessControlList")};
    }

    // ---------------------------------------------------------------- principal mappings

    private static String groupPath(String indexId, String dataSourceId, String groupId) {
        return indexId + "/" + (dataSourceId == null ? "-" : dataSourceId) + "/" + groupId;
    }

    public ObjectNode putPrincipalMapping(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.activeIndex(account, region, indexId);
        String dataSourceId = text(in, "DataSourceId");
        if (dataSourceId != null && indexes.find(account, region, KendraStore.DATA_SOURCE,
                indexId + "/" + dataSourceId) == null) {
            throw notFound("Data source " + dataSourceId + " doesn't exist.");
        }
        String groupId = text(in, "GroupId");
        JsonNode members = in.path("GroupMembers");
        ArrayNode users = mapper().createArrayNode();
        ArrayNode groups = mapper().createArrayNode();
        members.path("MemberUsers").forEach(u -> users.add(u.path("UserId").asText()));
        members.path("MemberGroups").forEach(g -> groups.addObject().put("GroupId", g.path("GroupId").asText())
                .put("DataSourceId", g.path("DataSourceId").asText(null)));
        if (members.has("S3PathforGroupMembers")) {
            String role = text(in, "RoleArn");
            if (role == null) {
                throw validation("A RoleArn is needed to read group members from S3.");
            }
            indexes.role(role);
            JsonNode file;
            try {
                file = mapper().readTree(s3Text(members.get("S3PathforGroupMembers")));
            } catch (IOException e) {
                throw validation("The group members file is not valid JSON.");
            }
            file.path("memberUsers").forEach(u -> users.add(u.path("userId").asText()));
            file.path("memberGroups").forEach(g -> groups.addObject().put("GroupId", g.path("groupId").asText())
                    .put("DataSourceId", g.path("dataSourceId").asText(null)));
        }
        if (users.size() + groups.size() > 1000 && !members.has("S3PathforGroupMembers")) {
            throw validation("A group can list at most 1000 members inline; put larger groups in S3.");
        }
        String path = groupPath(indexId, dataSourceId, groupId);
        ObjectNode group = store.get(account, region, GROUP, path).orElse(null);
        if (group == null) {
            group = store.record();
            group.put("IndexId", indexId).put("GroupId", groupId);
            if (dataSourceId != null) {
                group.put("DataSourceId", dataSourceId);
            }
            group.putArray("GroupOrderingIdSummaries");
            meta(group).put("path", path);
        }
        settleSummaries(group);
        long ordering = in.hasNonNull("OrderingId") ? in.get("OrderingId").asLong() : System.currentTimeMillis();
        double now = KendraStore.epoch();
        ObjectNode summary = mapper().createObjectNode();
        summary.put("LastUpdatedAt", now).put("ReceivedAt", now).put("OrderingId", ordering);
        long latest = meta(group).path("orderingId").asLong(-1);
        if (ordering < latest) {
            summary.put("Status", "FAILED").put("FailureReason", "The ordering id " + ordering
                    + " is lower than the group's latest ordering id " + latest + ".");
        } else {
            summary.put("Status", "PROCESSING");
            meta(group).put("orderingId", ordering).put("deleted", false);
            meta(group).set("users", users);
            meta(group).set("groups", groups);
            summary.put("_settleAt", System.currentTimeMillis() + indexes.transition()).put("_settleTo",
                    "SUCCEEDED");
        }
        addSummary(group, summary);
        store.put(account, region, GROUP, path, group);
        return empty();
    }

    private static void addSummary(ObjectNode group, ObjectNode summary) {
        ArrayNode summaries = (ArrayNode) group.get("GroupOrderingIdSummaries");
        summaries.add(summary);
        while (summaries.size() > 10) {
            summaries.remove(0);
        }
    }

    private static boolean settleSummaries(ObjectNode group) {
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (JsonNode s : group.path("GroupOrderingIdSummaries")) {
            ObjectNode summary = (ObjectNode) s;
            if (summary.has("_settleAt") && summary.get("_settleAt").asLong() <= now) {
                summary.put("Status", summary.get("_settleTo").asText()).put("LastUpdatedAt", KendraStore.epoch());
                summary.remove("_settleAt");
                summary.remove("_settleTo");
                changed = true;
            }
        }
        return changed;
    }

    private ObjectNode group(String account, String region, String indexId, String dataSourceId, String groupId) {
        indexes.index(account, region, indexId);
        String path = groupPath(indexId, dataSourceId, groupId);
        ObjectNode group = store.get(account, region, GROUP, path).orElse(null);
        if (group == null) {
            throw notFound("Group " + groupId + " doesn't exist in the index.");
        }
        if (settleSummaries(group)) {
            store.put(account, region, GROUP, path, group);
        }
        return group;
    }

    public ObjectNode describePrincipalMapping(JsonNode in, String region) {
        ObjectNode group = group(store.account(), region, text(in, "IndexId"), text(in, "DataSourceId"),
                text(in, "GroupId"));
        ObjectNode out = view(group);
        for (JsonNode s : out.path("GroupOrderingIdSummaries")) {
            ((ObjectNode) s).remove(List.of("_settleAt", "_settleTo"));
        }
        return out;
    }

    public ObjectNode deletePrincipalMapping(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        ObjectNode group = group(account, region, indexId, text(in, "DataSourceId"), text(in, "GroupId"));
        long ordering = in.hasNonNull("OrderingId") ? in.get("OrderingId").asLong() : System.currentTimeMillis();
        double now = KendraStore.epoch();
        ObjectNode summary = mapper().createObjectNode();
        summary.put("LastUpdatedAt", now).put("ReceivedAt", now).put("OrderingId", ordering);
        long latest = meta(group).path("orderingId").asLong(-1);
        if (ordering < latest) {
            summary.put("Status", "FAILED").put("FailureReason", "The ordering id " + ordering
                    + " is lower than the group's latest ordering id " + latest + ".");
        } else {
            summary.put("Status", "DELETING").put("_settleAt", System.currentTimeMillis() + indexes.transition())
                    .put("_settleTo", "DELETED");
            meta(group).put("orderingId", ordering).put("deleted", true);
            meta(group).putArray("users");
            meta(group).putArray("groups");
        }
        addSummary(group, summary);
        store.put(account, region, GROUP, meta(group).path("path").asText(), group);
        return empty();
    }

    public ObjectNode listGroupsOlderThanOrderingId(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.activeIndex(account, region, indexId);
        String dataSourceId = text(in, "DataSourceId");
        long ordering = in.path("OrderingId").asLong();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode group : store.list(account, region, GROUP, indexId + "/" + (dataSourceId == null ? "-"
                : dataSourceId) + "/")) {
            if (!meta(group).path("deleted").asBoolean(false) && meta(group).path("orderingId").asLong() < ordering) {
                items.add(empty().put("GroupId", group.path("GroupId").asText())
                        .put("OrderingId", meta(group).path("orderingId").asLong()));
            }
        }
        ObjectNode out = empty();
        KendraStore.page(items, in, out, "GroupsSummaries", 10);
        return out;
    }

    /**
     * The groups a user belongs to by the index's principal mappings, beyond the ones given: groups listing the
     * user, and groups listing those groups, to any depth. Data-source groups are {@code <dataSourceId>/<group>}.
     */
    Set<String> groupsOf(String account, String region, String indexId, String userId, Set<String> given) {
        Set<String> groups = new LinkedHashSet<>(given);
        List<ObjectNode> mappings = store.list(account, region, GROUP, indexId + "/");
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ObjectNode group : mappings) {
                if (meta(group).path("deleted").asBoolean(false)) {
                    continue;
                }
                String name = group.hasNonNull("DataSourceId") ? group.get("DataSourceId").asText() + "/"
                        + group.path("GroupId").asText() : group.path("GroupId").asText();
                if (groups.contains(name)) {
                    continue;
                }
                boolean member = false;
                if (userId != null) {
                    for (JsonNode u : meta(group).path("users")) {
                        member |= u.asText().equals(userId);
                    }
                }
                for (JsonNode g : meta(group).path("groups")) {
                    String inner = g.hasNonNull("DataSourceId") ? g.get("DataSourceId").asText() + "/"
                            + g.path("GroupId").asText() : g.path("GroupId").asText();
                    member |= groups.contains(inner);
                }
                if (member) {
                    groups.add(name);
                    changed = true;
                }
            }
        }
        return groups;
    }

    // ---------------------------------------------------------------- experiences

    public ObjectNode createExperience(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.activeIndex(account, region, indexId);
        ObjectNode earlier = store.repeated(account, region, "CreateExperience", in);
        if (earlier != null) {
            return earlier;
        }
        if (in.hasNonNull("RoleArn")) {
            indexes.role(text(in, "RoleArn"));
        }
        checkContentSources(account, region, indexId, in.get("Configuration"));
        String id = KendraStore.uuid();
        ObjectNode experience = store.record();
        double now = KendraStore.epoch();
        experience.put("Id", id).put("IndexId", indexId).put("Name", text(in, "Name"));
        ArrayNode endpoints = experience.putArray("Endpoints");
        endpoints.addObject().put("EndpointType", "HOME").put("Endpoint",
                store.emulator().effectiveBaseUrl().replaceAll("/+$", "") + KendraExperienceController.PATH
                        + region + "/" + indexId + "/" + id + "/");
        if (in.has("Configuration")) {
            experience.set("Configuration", in.get("Configuration").deepCopy());
        }
        experience.put("CreatedAt", now).put("UpdatedAt", now);
        if (in.has("Description")) {
            experience.put("Description", text(in, "Description"));
        }
        if (in.hasNonNull("RoleArn")) {
            experience.put("RoleArn", text(in, "RoleArn"));
        }
        meta(experience).putObject("entities");
        indexes.pending(experience, "CREATING", "ACTIVE");
        indexes.save(account, region, EXPERIENCE, indexId + "/" + id, experience);
        ObjectNode response = empty().put("Id", id);
        store.remember(account, region, "CreateExperience", in, response);
        return response;
    }

    private void checkContentSources(String account, String region, String indexId, JsonNode configuration) {
        if (configuration == null) {
            return;
        }
        JsonNode sources = configuration.path("ContentSourceConfiguration");
        for (JsonNode id : sources.path("DataSourceIds")) {
            if (indexes.find(account, region, KendraStore.DATA_SOURCE, indexId + "/" + id.asText()) == null) {
                throw notFound("Data source " + id.asText() + " doesn't exist.");
            }
        }
        for (JsonNode id : sources.path("FaqIds")) {
            if (indexes.find(account, region, FAQ, indexId + "/" + id.asText()) == null) {
                throw notFound("FAQ " + id.asText() + " doesn't exist.");
            }
        }
    }

    public ObjectNode describeExperience(JsonNode in, String region) {
        return view(require(store.account(), region, EXPERIENCE, text(in, "IndexId"), text(in, "Id"),
                "Experience"));
    }

    ObjectNode experience(String account, String region, String indexId, String id) {
        return require(account, region, EXPERIENCE, indexId, id, "Experience");
    }

    public ObjectNode updateExperience(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        ObjectNode experience = require(account, region, EXPERIENCE, indexId, text(in, "Id"), "Experience");
        if (in.hasNonNull("Name")) {
            experience.put("Name", text(in, "Name"));
        }
        if (in.hasNonNull("RoleArn")) {
            indexes.role(text(in, "RoleArn"));
            experience.put("RoleArn", text(in, "RoleArn"));
        }
        if (in.has("Configuration")) {
            checkContentSources(account, region, indexId, in.get("Configuration"));
            experience.set("Configuration", in.get("Configuration").deepCopy());
        }
        if (in.has("Description")) {
            experience.put("Description", text(in, "Description"));
        }
        experience.put("UpdatedAt", KendraStore.epoch());
        store.put(account, region, EXPERIENCE, indexId + "/" + text(in, "Id"), experience);
        return empty();
    }

    public ObjectNode deleteExperience(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        ObjectNode experience = require(account, region, EXPERIENCE, indexId, text(in, "Id"), "Experience");
        indexes.pending(experience, "DELETING", "DELETED");
        store.put(account, region, EXPERIENCE, indexId + "/" + text(in, "Id"), experience);
        return empty();
    }

    public ObjectNode listExperiences(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.index(account, region, indexId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode experience : indexes.all(account, region, EXPERIENCE, indexId + "/")) {
            ObjectNode item = empty();
            for (String field : List.of("Name", "Id", "CreatedAt", "Status", "Endpoints")) {
                item.set(field, experience.get(field));
            }
            items.add(item);
        }
        ObjectNode out = empty();
        KendraStore.page(items, in, out, "SummaryItems", 100);
        return out;
    }

    private String identityStoreId(String account) {
        List<SsoInstance> instances = ssoAdmin.listInstances(account);
        return instances.isEmpty() ? null : instances.get(0).identityStoreId();
    }

    /** The display data of an Identity Center user or group, or null when it doesn't exist. */
    private ObjectNode displayData(String storeId, String entityId, String type) {
        ObjectNode request = empty().put("IdentityStoreId", storeId);
        ObjectNode data = empty();
        try {
            if ("USER".equals(type)) {
                User user = identityStore.describeUser(request.put("UserId", entityId));
                ObjectNode attributes = user.attributes();
                if (attributes.has("UserName")) {
                    data.put("UserName", attributes.get("UserName").asText());
                    data.put("IdentifiedUserName", attributes.get("UserName").asText());
                }
                if (attributes.path("Name").has("GivenName")) {
                    data.put("FirstName", attributes.path("Name").get("GivenName").asText());
                }
                if (attributes.path("Name").has("FamilyName")) {
                    data.put("LastName", attributes.path("Name").get("FamilyName").asText());
                }
            } else {
                Group group = identityStore.describeGroup(request.put("GroupId", entityId));
                if (group.displayName() != null) {
                    data.put("GroupName", group.displayName());
                }
            }
        } catch (AwsException e) {
            return null;
        }
        return data;
    }

    public ObjectNode associateEntitiesToExperience(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        String id = text(in, "Id");
        ObjectNode experience = require(account, region, EXPERIENCE, indexId, id, "Experience");
        String storeId = identityStoreId(account);
        ObjectNode entities = (ObjectNode) meta(experience).get("entities");
        ObjectNode out = empty();
        ArrayNode failed = out.putArray("FailedEntityList");
        for (JsonNode entity : in.path("EntityList")) {
            String entityId = entity.path("EntityId").asText();
            String type = entity.path("EntityType").asText();
            ObjectNode display = storeId == null ? null : displayData(storeId, entityId, type);
            if (display == null) {
                failed.addObject().put("EntityId", entityId).put("ErrorMessage", storeId == null
                        ? "IAM Identity Center is not enabled for the account."
                        : "The " + type.toLowerCase(Locale.ROOT) + " " + entityId + " doesn't exist in IAM "
                        + "Identity Center.");
                continue;
            }
            double now = KendraStore.epoch();
            ObjectNode record = entities.has(entityId) ? (ObjectNode) entities.get(entityId)
                    : entities.putObject(entityId).put("CreatedAt", now);
            record.put("EntityType", type).put("UpdatedAt", now);
        }
        store.put(account, region, EXPERIENCE, indexId + "/" + id, experience);
        return out;
    }

    public ObjectNode disassociateEntitiesFromExperience(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        String id = text(in, "Id");
        ObjectNode experience = require(account, region, EXPERIENCE, indexId, id, "Experience");
        ObjectNode entities = (ObjectNode) meta(experience).get("entities");
        ObjectNode out = empty();
        ArrayNode failed = out.putArray("FailedEntityList");
        for (JsonNode entity : in.path("EntityList")) {
            String entityId = entity.path("EntityId").asText();
            if (entities.remove(entityId) == null) {
                failed.addObject().put("EntityId", entityId).put("ErrorMessage", "The entity " + entityId
                        + " isn't associated with the experience.");
            }
        }
        store.put(account, region, EXPERIENCE, indexId + "/" + id, experience);
        return out;
    }

    public ObjectNode associatePersonasToEntities(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        String id = text(in, "Id");
        ObjectNode experience = require(account, region, EXPERIENCE, indexId, id, "Experience");
        ObjectNode entities = (ObjectNode) meta(experience).get("entities");
        ObjectNode out = empty();
        ArrayNode failed = out.putArray("FailedEntityList");
        for (JsonNode persona : in.path("Personas")) {
            String entityId = persona.path("EntityId").asText();
            if (!entities.has(entityId)) {
                failed.addObject().put("EntityId", entityId).put("ErrorMessage", "The entity " + entityId
                        + " isn't associated with the experience.");
                continue;
            }
            ((ObjectNode) entities.get(entityId)).put("Persona", persona.path("Persona").asText())
                    .put("UpdatedAt", KendraStore.epoch());
        }
        store.put(account, region, EXPERIENCE, indexId + "/" + id, experience);
        return out;
    }

    public ObjectNode disassociatePersonasFromEntities(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        String id = text(in, "Id");
        ObjectNode experience = require(account, region, EXPERIENCE, indexId, id, "Experience");
        ObjectNode entities = (ObjectNode) meta(experience).get("entities");
        ObjectNode out = empty();
        ArrayNode failed = out.putArray("FailedEntityList");
        for (JsonNode entityId : in.path("EntityIds")) {
            JsonNode entity = entities.get(entityId.asText());
            if (entity == null || !entity.has("Persona")) {
                failed.addObject().put("EntityId", entityId.asText()).put("ErrorMessage", "The entity "
                        + entityId.asText() + " has no persona.");
                continue;
            }
            ((ObjectNode) entity).remove("Persona");
        }
        store.put(account, region, EXPERIENCE, indexId + "/" + id, experience);
        return out;
    }

    public ObjectNode listEntityPersonas(JsonNode in, String region) {
        ObjectNode experience = require(store.account(), region, EXPERIENCE, text(in, "IndexId"), text(in, "Id"),
                "Experience");
        List<ObjectNode> items = new ArrayList<>();
        meta(experience).path("entities").fields().forEachRemaining(e -> {
            if (e.getValue().has("Persona")) {
                items.add(empty().put("EntityId", e.getKey()).put("Persona", e.getValue().get("Persona").asText())
                        .put("CreatedAt", e.getValue().path("CreatedAt").asDouble())
                        .put("UpdatedAt", e.getValue().path("UpdatedAt").asDouble()));
            }
        });
        ObjectNode out = empty();
        KendraStore.page(items, in, out, "SummaryItems", 100);
        return out;
    }

    public ObjectNode listExperienceEntities(JsonNode in, String region) {
        String account = store.account();
        ObjectNode experience = require(account, region, EXPERIENCE, text(in, "IndexId"), text(in, "Id"),
                "Experience");
        String storeId = identityStoreId(account);
        List<ObjectNode> items = new ArrayList<>();
        meta(experience).path("entities").fields().forEachRemaining(e -> {
            ObjectNode item = empty().put("EntityId", e.getKey())
                    .put("EntityType", e.getValue().path("EntityType").asText());
            ObjectNode display = storeId == null ? null : displayData(storeId, e.getKey(),
                    e.getValue().path("EntityType").asText());
            if (display != null) {
                item.set("DisplayData", display);
            }
            items.add(item);
        });
        ObjectNode out = empty();
        KendraStore.page(items, in, out, "SummaryItems", 10);
        return out;
    }
}
