package io.floci.aws.services.kendra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.identitystore.IdentityStoreService;
import io.floci.aws.services.identitystore.model.Group;
import io.floci.aws.services.identitystore.model.User;
import io.floci.aws.services.kendra.KendraSearch.Entry;
import io.floci.aws.services.kendra.KendraSearch.Hit;
import io.floci.aws.services.kendra.KendraSearch.Parsed;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.ssoadmin.SsoAdminService;
import io.floci.aws.services.ssoadmin.model.SsoInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static io.floci.aws.services.kendra.KendraStore.QUERY;
import static io.floci.aws.services.kendra.KendraStore.SUGGESTIONS;
import static io.floci.aws.services.kendra.KendraStore.meta;
import static io.floci.aws.services.kendra.KendraStore.notFound;
import static io.floci.aws.services.kendra.KendraStore.text;
import static io.floci.aws.services.kendra.KendraStore.validation;

/**
 * Searching an index: Query, Retrieve, query suggestions, feedback and search analytics.
 *
 * <p>Who is searching decides what they see. With the {@code ATTRIBUTE_FILTER} policy every document is visible
 * unless the query names a user or groups (in {@code UserContext}, or as {@code _user_id} and {@code _group_ids}
 * filters); with {@code USER_TOKEN} the user and groups come from a token checked against the index's token
 * configuration (a JWT signed with HS256 or RS256 by a key in Secrets Manager or at a JWKS URL, or a JSON token), and
 * a query without one sees only documents without access control. Groups grow by the index's principal mappings and,
 * when the index resolves groups through {@code AWS_SSO}, the user's IAM Identity Center groups. A document's access
 * control list denies a matching {@code DENY}, allows a matching {@code ALLOW}, and otherwise hides the document from
 * users when it allows anyone at all; hierarchical lists are read from the deepest level up.
 *
 * <p>Queries are logged. Query suggestions are built from that log as on AWS: queries that found results, asked at
 * least {@code MinimumQueryCount} times by at least {@code MinimumNumberOfQueryingUsers} users within the look-back
 * window, after the last clear and without a blocked phrase, starting with what the user has typed. Search analytics
 * ({@code GetSnapshots}) report the log, clicks and relevance feedback over the interval's days.
 */
@ApplicationScoped
public class KendraQueries {

    static final int MAX_RESULTS = 100;
    static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final KendraIndexes indexes;
    private final KendraStore store;
    private final KendraDocuments documents;
    private final KendraContent content;
    private final IdentityStoreService identityStore;
    private final SsoAdminService ssoAdmin;
    private final SecretsManagerService secrets;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Inject
    public KendraQueries(KendraIndexes indexes, KendraStore store, KendraDocuments documents, KendraContent content,
                         IdentityStoreService identityStore, SsoAdminService ssoAdmin,
                         SecretsManagerService secrets) {
        this.indexes = indexes;
        this.store = store;
        this.documents = documents;
        this.content = content;
        this.identityStore = identityStore;
        this.ssoAdmin = ssoAdmin;
        this.secrets = secrets;
    }

    private ObjectMapper mapper() {
        return store.mapper();
    }

    // ---------------------------------------------------------------- who is searching

    /** The user and groups a query is made as; {@code filtered} when access control applies. */
    record Viewer(String user, Set<String> groups, boolean filtered) {
    }

    Viewer viewer(String account, String region, ObjectNode index, JsonNode userContext, JsonNode filter) {
        String indexId = index.path("Id").asText();
        String user = null;
        Set<String> groups = new LinkedHashSet<>();
        boolean filtered;
        if ("USER_TOKEN".equals(index.path("UserContextPolicy").asText())) {
            filtered = true;
            String token = userContext == null ? null : text(userContext, "Token");
            if (token != null) {
                JsonNode config = index.path("UserTokenConfigurations").path(0);
                String[] claims = claims(region, config, token);
                user = claims[0];
                if (claims[1] != null) {
                    for (String g : claims[1].split("\n")) {
                        if (!g.isEmpty()) {
                            groups.add(g);
                        }
                    }
                }
            }
        } else {
            filtered = false;
            if (userContext != null && !userContext.isEmpty()) {
                if (userContext.has("Token")) {
                    throw validation("The index uses the ATTRIBUTE_FILTER user context policy; it takes a UserId "
                            + "and Groups, not a Token.");
                }
                user = text(userContext, "UserId");
                userContext.path("Groups").forEach(g -> groups.add(g.asText()));
                userContext.path("DataSourceGroups").forEach(g -> groups.add(g.path("DataSourceId").asText() + "/"
                        + g.path("GroupId").asText()));
                filtered = true;
            }
            Set<String> users = new LinkedHashSet<>();
            KendraSearch.principals(filter, "_user_id", users);
            KendraSearch.principals(filter, "_group_ids", groups);
            if (!users.isEmpty()) {
                user = users.iterator().next();
            }
            filtered |= KendraSearch.names(filter, "_user_id") || KendraSearch.names(filter, "_group_ids");
        }
        if (filtered && user != null && "AWS_SSO".equals(index.path("UserGroupResolutionConfiguration")
                .path("UserGroupResolutionMode").asText())) {
            groups.addAll(identityCenterGroups(account, user));
        }
        return new Viewer(user, filtered ? content.groupsOf(account, region, indexId, user, groups) : groups,
                filtered);
    }

    private Set<String> identityCenterGroups(String account, String userName) {
        Set<String> names = new LinkedHashSet<>();
        List<SsoInstance> instances = ssoAdmin.listInstances(account);
        if (instances.isEmpty()) {
            return names;
        }
        String storeId = instances.get(0).identityStoreId();
        ObjectNode request = mapper().createObjectNode().put("IdentityStoreId", storeId);
        request.putArray("Filters").addObject().put("AttributePath", "UserName").put("AttributeValue", userName);
        try {
            for (User user : identityStore.listUsers(request).items()) {
                for (String groupId : identityStore.groupIdsForUser(storeId, user.userId())) {
                    Group group = identityStore.describeGroup(mapper().createObjectNode()
                            .put("IdentityStoreId", storeId).put("GroupId", groupId));
                    names.add(group.displayName() == null ? groupId : group.displayName());
                }
            }
        } catch (AwsException e) {
            return names;
        }
        return names;
    }

    /** The user ({@code [0]}) and newline-joined groups ({@code [1]}) a user token carries, once it's verified. */
    private String[] claims(String region, JsonNode config, String token) {
        if (config.has("JsonTokenTypeConfiguration")) {
            JsonNode json;
            try {
                json = mapper().readTree(token);
            } catch (IOException e) {
                throw validation("The user token is not valid JSON.");
            }
            JsonNode c = config.get("JsonTokenTypeConfiguration");
            return new String[] {json.path(c.path("UserNameAttributeField").asText()).asText(null),
                    joined(json.path(c.path("GroupAttributeField").asText()))};
        }
        JsonNode jwt = config.path("JwtTokenTypeConfiguration");
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw validation("The user token is not a JWT.");
        }
        JsonNode header;
        JsonNode payload;
        try {
            header = mapper().readTree(Base64.getUrlDecoder().decode(parts[0]));
            payload = mapper().readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (IOException | IllegalArgumentException e) {
            throw validation("The user token is not a JWT.");
        }
        if (!verify(region, jwt, header, parts)) {
            throw validation("The user token's signature is not valid.");
        }
        if (payload.has("exp") && payload.get("exp").asLong() < Instant.now().getEpochSecond()) {
            throw validation("The user token has expired.");
        }
        if (jwt.hasNonNull("Issuer") && !jwt.get("Issuer").asText().equals(payload.path("iss").asText())) {
            throw validation("The user token's issuer is not " + jwt.get("Issuer").asText() + ".");
        }
        String userField = jwt.hasNonNull("UserNameAttributeField") ? jwt.get("UserNameAttributeField").asText()
                : "sub";
        String user = payload.path(userField).asText(null);
        if (jwt.hasNonNull("ClaimRegex") && (user == null || !user.matches(jwt.get("ClaimRegex").asText()))) {
            throw validation("The user token's claim doesn't match " + jwt.get("ClaimRegex").asText() + ".");
        }
        String groupField = jwt.hasNonNull("GroupAttributeField") ? jwt.get("GroupAttributeField").asText()
                : "groups";
        return new String[] {user, joined(payload.path(groupField))};
    }

    private static String joined(JsonNode groups) {
        if (groups.isMissingNode() || groups.isNull()) {
            return null;
        }
        if (groups.isArray()) {
            List<String> names = new ArrayList<>();
            groups.forEach(g -> names.add(g.asText()));
            return String.join("\n", names);
        }
        return groups.asText();
    }

    private boolean verify(String region, JsonNode jwt, JsonNode header, String[] parts) {
        String alg = header.path("alg").asText();
        byte[] signed = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        byte[] signature;
        try {
            signature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String keys;
        if ("SECRET_MANAGER".equals(jwt.path("KeyLocation").asText())) {
            try {
                keys = secrets.getSecretValue(jwt.path("SecretManagerArn").asText(), null, null, region)
                        .getSecretString();
            } catch (AwsException e) {
                throw validation("Unable to read the token key " + jwt.path("SecretManagerArn").asText() + ": "
                        + e.getMessage());
            }
        } else {
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(jwt.path("URL")
                        .asText())).timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                keys = response.body();
            } catch (IOException | IllegalArgumentException e) {
                throw validation("Unable to fetch the token keys from " + jwt.path("URL").asText() + ".");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw validation("Unable to fetch the token keys from " + jwt.path("URL").asText() + ".");
            }
        }
        if (keys == null) {
            return false;
        }
        JsonNode jwks = null;
        try {
            jwks = mapper().readTree(keys);
        } catch (IOException e) {
            jwks = null;
        }
        try {
            if (alg.startsWith("HS")) {
                List<byte[]> secretsToTry = new ArrayList<>();
                if (jwks != null && jwks.has("keys")) {
                    for (JsonNode key : jwks.get("keys")) {
                        if ("oct".equals(key.path("kty").asText())) {
                            secretsToTry.add(Base64.getUrlDecoder().decode(key.path("k").asText()));
                        }
                    }
                } else {
                    secretsToTry.add(keys.getBytes(StandardCharsets.UTF_8));
                }
                String mac = "Hmac" + "SHA" + alg.substring(2);
                for (byte[] secret : secretsToTry) {
                    Mac hmac = Mac.getInstance(mac);
                    hmac.init(new SecretKeySpec(secret, mac));
                    if (MessageDigest.isEqual(hmac.doFinal(signed), signature)) {
                        return true;
                    }
                }
                return false;
            }
            if (alg.startsWith("RS") && jwks != null) {
                String kid = header.path("kid").asText(null);
                for (JsonNode key : jwks.path("keys")) {
                    if (!"RSA".equals(key.path("kty").asText()) || kid != null && key.has("kid")
                            && !kid.equals(key.get("kid").asText())) {
                        continue;
                    }
                    PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                            new BigInteger(1, Base64.getUrlDecoder().decode(key.path("n").asText())),
                            new BigInteger(1, Base64.getUrlDecoder().decode(key.path("e").asText()))));
                    Signature verifier = Signature.getInstance("SHA" + alg.substring(2) + "withRSA");
                    verifier.initVerify(publicKey);
                    verifier.update(signed);
                    if (verifier.verify(signature)) {
                        return true;
                    }
                }
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
        return false;
    }

    /** Whether a viewer may see a document (or FAQ) with these access control lists. */
    static boolean allowed(Viewer viewer, JsonNode acl, JsonNode hierarchy, String dataSourceId) {
        if (!viewer.filtered()) {
            return true;
        }
        List<JsonNode> levels = new ArrayList<>();
        if (hierarchy != null && hierarchy.isArray()) {
            hierarchy.forEach(level -> levels.add(level.path("PrincipalList")));
        }
        if (acl != null && acl.isArray() && !acl.isEmpty()) {
            levels.add(acl);
        }
        boolean anyAllow = false;
        for (int i = levels.size() - 1; i >= 0; i--) {
            boolean allow = false;
            boolean deny = false;
            for (JsonNode principal : levels.get(i)) {
                boolean isAllow = "ALLOW".equals(principal.path("Access").asText());
                anyAllow |= isAllow;
                if (matches(viewer, principal, dataSourceId)) {
                    if (isAllow) {
                        allow = true;
                    } else {
                        deny = true;
                    }
                }
            }
            if (deny) {
                return false;
            }
            if (allow) {
                return true;
            }
        }
        return !anyAllow;
    }

    private static boolean matches(Viewer viewer, JsonNode principal, String dataSourceId) {
        String name = principal.path("Name").asText();
        String scope = principal.path("DataSourceId").asText(null);
        if (scope != null && dataSourceId != null && !scope.equals(dataSourceId)) {
            return false;
        }
        if ("USER".equals(principal.path("Type").asText())) {
            return name.equals(viewer.user());
        }
        return viewer.groups().contains(name) || scope != null && viewer.groups().contains(scope + "/" + name);
    }

    // ---------------------------------------------------------------- the searchable set

    /** An index's documents a query can see: indexed, in the query's language, visible to the viewer. */
    private List<Entry> entries(String account, String region, ObjectNode index, Viewer viewer, JsonNode filter) {
        String indexId = index.path("Id").asText();
        boolean languageFiltered = KendraSearch.names(filter, "_language_code");
        List<Entry> entries = new ArrayList<>();
        for (ObjectNode document : documents.searchable(account, region, indexId)) {
            ObjectNode attributes = (ObjectNode) document.path("Attributes");
            if (!languageFiltered && !"en".equals(attributes.path("_language_code").path("StringValue")
                    .asText("en"))) {
                continue;
            }
            JsonNode[] acl = content.accessLists(account, region, indexId, document);
            if (!allowed(viewer, acl[0], acl[1], meta(document).path("dataSourceId").asText(null))) {
                continue;
            }
            List<List<String>> table = null;
            if (document.has("Table")) {
                table = new ArrayList<>();
                for (JsonNode row : document.get("Table")) {
                    List<String> cells = new ArrayList<>();
                    row.forEach(c -> cells.add(c.asText()));
                    table.add(cells);
                }
            }
            Entry entry = new Entry(document, document.path("Id").asText(), document.path("Title").asText(null),
                    document.path("Text").asText(""), attributes, table);
            if (KendraSearch.filter(filter, entry)) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private Map<String, Double> feedbackBoosts(String account, String region, String indexId, String normalized) {
        Map<String, Double> boosts = new HashMap<>();
        for (ObjectNode logged : store.list(account, region, QUERY, indexId + "/")) {
            if (!normalized.equals(logged.path("normalized").asText())) {
                continue;
            }
            for (JsonNode click : logged.path("clicks")) {
                boosts.merge(click.path("documentId").asText(), 0.1, Double::sum);
            }
            for (JsonNode feedback : logged.path("relevance")) {
                boosts.merge(feedback.path("documentId").asText(),
                        "RELEVANT".equals(feedback.path("value").asText()) ? 0.2 : -0.3, Double::sum);
            }
        }
        Map<String, Double> factors = new HashMap<>();
        boosts.forEach((id, b) -> factors.put(id, Math.max(0.3, 1 + Math.min(0.5, b))));
        return factors;
    }

    private static String normalize(String text) {
        return text == null ? "" : WHITESPACE.matcher(text.strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    private List<ObjectNode> relevance(Map<String, ObjectNode> fields, JsonNode overrides) {
        Map<String, ObjectNode> tuned = new LinkedHashMap<>();
        fields.forEach((name, field) -> {
            if (field.has("Relevance")) {
                tuned.put(name, field);
            }
        });
        if (overrides != null) {
            for (JsonNode override : overrides) {
                String name = override.path("Name").asText();
                if (!fields.containsKey(name)) {
                    throw validation("The relevance override names " + name + ", which is not a field of the index.");
                }
                ObjectNode copy = fields.get(name).deepCopy();
                copy.set("Relevance", override.get("Relevance").deepCopy());
                tuned.put(name, copy);
            }
        }
        return new ArrayList<>(tuned.values());
    }

    // ---------------------------------------------------------------- Query

    public ObjectNode query(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        ObjectNode index = indexes.activeIndex(account, region, indexId);
        String queryText = text(in, "QueryText");
        JsonNode filter = in.get("AttributeFilter");
        if ((queryText == null || queryText.isBlank()) && (filter == null || filter.isEmpty())) {
            throw validation("A query needs QueryText or an AttributeFilter.");
        }
        if (queryText != null && queryText.length() > 1000) {
            throw validation("QueryText can be at most 1000 characters.");
        }
        int pageSize = in.hasNonNull("PageSize") ? in.get("PageSize").asInt() : 10;
        int pageNumber = in.hasNonNull("PageNumber") ? in.get("PageNumber").asInt() : 1;
        if (pageSize < 1 || pageSize > MAX_RESULTS || pageNumber < 1) {
            throw validation("PageSize must be between 1 and 100 and PageNumber at least 1.");
        }
        Map<String, ObjectNode> fields = KendraIndexes.fields(index);
        Viewer viewer = viewer(account, region, index, in.get("UserContext"), filter);
        List<Entry> entries = entries(account, region, index, viewer, filter);
        Parsed parsed = KendraSearch.parse(queryText == null ? "" : queryText,
                content.synonyms(account, region, indexId));
        String normalized = normalize(queryText);
        List<Hit> hits;
        if (queryText == null || queryText.isBlank()) {
            hits = new ArrayList<>();
            entries.forEach(e -> hits.add(new Hit(e, 1, 1)));
        } else {
            hits = KendraSearch.score(parsed, entries, fields, relevance(fields,
                    in.get("DocumentRelevanceOverrideConfigurations")), feedbackBoosts(account, region, indexId,
                    normalized));
        }
        sort(hits, in, fields);
        String queryId = KendraStore.uuid();
        ObjectNode out = mapper().createObjectNode();
        out.put("QueryId", queryId);
        String typeFilter = text(in, "QueryResultTypeFilter");
        ArrayNode results = out.putArray("ResultItems");
        ObjectNode log = mapper().createObjectNode();
        ObjectNode resultDocs = log.putObject("results");
        double best = hits.isEmpty() ? 0 : hits.stream().mapToDouble(Hit::score).max().orElse(0);
        boolean instantAnswer = false;

        ObjectNode featured = pageNumber == 1 ? content.featuredFor(account, region, indexId, queryText) : null;
        Set<String> featuredIds = new HashSet<>();
        ArrayNode featuredItems = out.putArray("FeaturedResultsItems");
        if (featured != null) {
            for (JsonNode f : featured.path("FeaturedDocuments")) {
                String docId = f.path("Id").asText();
                Entry entry = entries.stream().filter(e -> e.id.equals(docId)).findFirst().orElse(null);
                if (entry == null) {
                    continue;
                }
                featuredIds.add(docId);
                String resultId = queryId + "-" + KendraStore.uuid();
                resultDocs.put(resultId, docId);
                ObjectNode item = featuredItems.addObject();
                item.put("Id", resultId).put("Type", "DOCUMENT").putArray("AdditionalAttributes");
                documentFields(item, entry, parsed, fields, in.get("RequestedDocumentAttributes"));
                item.put("FeedbackToken", feedbackToken(queryId, resultId));
            }
        }

        if (pageNumber == 1 && queryText != null && !queryText.isBlank()) {
            if (allows(typeFilter, "ANSWER") && KendraAnalyzer.isQuestion(queryText)) {
                KendraSearch.TableAnswer table = KendraSearch.table(parsed, hits);
                if (table != null) {
                    String resultId = queryId + "-" + KendraStore.uuid();
                    resultDocs.put(resultId, table.hit().entry().id);
                    ObjectNode item = results.addObject();
                    item.put("Id", resultId).put("Type", "ANSWER").put("Format", "TABLE")
                            .putArray("AdditionalAttributes");
                    documentFields(item, table.hit().entry(), parsed, fields, in.get("RequestedDocumentAttributes"));
                    item.set("TableExcerpt", KendraSearch.tableExcerpt(mapper(), table, parsed));
                    item.putObject("ScoreAttributes").put("ScoreConfidence", KendraSearch.confidence(table.hit(),
                            best));
                    item.put("FeedbackToken", feedbackToken(queryId, resultId));
                    instantAnswer = true;
                } else {
                    KendraSearch.Answer answer = KendraSearch.answer(parsed, hits);
                    if (answer != null) {
                        String resultId = queryId + "-" + KendraStore.uuid();
                        Entry entry = answer.hit().entry();
                        resultDocs.put(resultId, entry.id);
                        ObjectNode item = results.addObject();
                        item.put("Id", resultId).put("Type", "ANSWER").put("Format", "TEXT");
                        List<KendraSearch.Mark> marks = KendraSearch.marks(entry.bodyTokens, parsed);
                        ObjectNode passage = KendraSearch.highlighted(mapper(), entry.text, answer.sentence().begin(),
                                answer.sentence().end(), marks, "", "", answer.top());
                        ObjectNode attr = item.putArray("AdditionalAttributes").addObject();
                        attr.put("Key", "AnswerText").put("ValueType", "TEXT_WITH_HIGHLIGHTS_VALUE");
                        attr.putObject("Value").set("TextWithHighlightsValue", passage);
                        documentFields(item, entry, parsed, fields, in.get("RequestedDocumentAttributes"));
                        item.set("DocumentExcerpt", passage.deepCopy());
                        item.putObject("ScoreAttributes").put("ScoreConfidence", answer.strength() >= 0.99
                                ? "VERY_HIGH" : "HIGH");
                        item.put("FeedbackToken", feedbackToken(queryId, resultId));
                        instantAnswer = true;
                    }
                }
            }
            if (allows(typeFilter, "QUESTION_ANSWER")) {
                List<KendraSearch.Faq> faqs = content.faqs(account, region, indexId).stream()
                        .filter(f -> KendraSearch.filter(filter, faqEntry(f))).toList();
                List<Map.Entry<KendraSearch.Faq, Double>> matches = KendraSearch.faqs(queryText, faqs);
                for (Map.Entry<KendraSearch.Faq, Double> match : matches.subList(0, Math.min(3, matches.size()))) {
                    KendraSearch.Faq faq = match.getKey();
                    String resultId = queryId + "-" + KendraStore.uuid();
                    resultDocs.put(resultId, faq.id());
                    ObjectNode item = results.addObject();
                    item.put("Id", resultId).put("Type", "QUESTION_ANSWER").put("Format", "TEXT");
                    ArrayNode additional = item.putArray("AdditionalAttributes");
                    List<KendraAnalyzer.Token> qTokens = KendraAnalyzer.tokens(faq.question());
                    ObjectNode question = KendraSearch.highlighted(mapper(), faq.question(), 0,
                            faq.question().length(), KendraSearch.marks(qTokens, parsed), "", "", null);
                    List<KendraAnalyzer.Token> aTokens = KendraAnalyzer.tokens(faq.answer());
                    ObjectNode answerText = KendraSearch.highlighted(mapper(), faq.answer(), 0,
                            faq.answer().length(), KendraSearch.marks(aTokens, parsed), "", "", null);
                    ObjectNode q = additional.addObject().put("Key", "QuestionText")
                            .put("ValueType", "TEXT_WITH_HIGHLIGHTS_VALUE");
                    q.putObject("Value").set("TextWithHighlightsValue", question);
                    ObjectNode a = additional.addObject().put("Key", "AnswerText")
                            .put("ValueType", "TEXT_WITH_HIGHLIGHTS_VALUE");
                    a.putObject("Value").set("TextWithHighlightsValue", answerText);
                    item.put("DocumentId", faq.id());
                    item.set("DocumentTitle", question.deepCopy());
                    item.set("DocumentExcerpt", answerText.deepCopy());
                    if (faq.uri() != null) {
                        item.put("DocumentURI", faq.uri());
                    }
                    item.set("DocumentAttributes", attributeList(faq.attributes(), fields,
                            in.get("RequestedDocumentAttributes")));
                    item.putObject("ScoreAttributes").put("ScoreConfidence", match.getValue() >= 0.9 ? "VERY_HIGH"
                            : match.getValue() >= 0.7 ? "HIGH" : "MEDIUM");
                    item.put("FeedbackToken", feedbackToken(queryId, resultId));
                    instantAnswer = true;
                }
            }
        }

        out.set("FacetResults", KendraSearch.facets(mapper(), in.get("Facets"),
                hits.stream().map(Hit::entry).toList(), fields));
        List<Hit> documentsOnly = hits.stream().filter(h -> !featuredIds.contains(h.entry().id)).toList();
        List<Collapsed> collapsed = collapse(documentsOnly, in.get("CollapseConfiguration"), fields);
        int total = collapsed.size();
        out.put("TotalNumberOfResults", total);
        if (allows(typeFilter, "DOCUMENT")) {
            int from = Math.min(Math.min(total, MAX_RESULTS), (pageNumber - 1) * pageSize);
            int to = Math.min(Math.min(total, MAX_RESULTS), from + pageSize);
            for (Collapsed c : collapsed.subList(from, to)) {
                Entry entry = c.hit().entry();
                String resultId = queryId + "-" + KendraStore.uuid();
                resultDocs.put(resultId, entry.id);
                ObjectNode item = results.addObject();
                item.put("Id", resultId).put("Type", "DOCUMENT").put("Format", "TEXT")
                        .putArray("AdditionalAttributes");
                documentFields(item, entry, parsed, fields, in.get("RequestedDocumentAttributes"));
                item.putObject("ScoreAttributes").put("ScoreConfidence", KendraSearch.confidence(c.hit(), best));
                item.put("FeedbackToken", feedbackToken(queryId, resultId));
                if (c.key() != null) {
                    ObjectNode detail = item.putObject("CollapsedResultDetail");
                    detail.set("DocumentAttribute", c.key());
                    ArrayNode expanded = detail.putArray("ExpandedResults");
                    for (Hit e : c.expanded()) {
                        String expandedId = queryId + "-" + KendraStore.uuid();
                        resultDocs.put(expandedId, e.entry().id);
                        ObjectNode x = expanded.addObject().put("Id", expandedId);
                        documentFields(x, e.entry(), parsed, fields, in.get("RequestedDocumentAttributes"));
                    }
                }
            }
        }
        ArrayNode warnings = out.putArray("Warnings");
        if (parsed.invalid()) {
            warnings.addObject().put("Code", "QUERY_LANGUAGE_INVALID_SYNTAX").put("Message",
                    "The query uses query syntax that isn't valid; it was searched as natural language.");
        }
        if (in.path("SpellCorrectionConfiguration").path("IncludeQuerySpellCheckSuggestions").asBoolean(false)
                && queryText != null) {
            out.set("SpellCorrectedQueries", KendraSearch.spellCorrections(mapper(), queryText, entries));
        } else {
            out.putArray("SpellCorrectedQueries");
        }
        log.put("QueryId", queryId).put("text", queryText == null ? "" : queryText).put("normalized", normalized)
                .put("at", System.currentTimeMillis()).put("total", total + results.size() - countType(results, "DOCUMENT"))
                .put("instantAnswer", instantAnswer);
        String user = viewer.user() != null ? viewer.user() : text(in, "VisitorId");
        if (user != null) {
            log.put("user", user);
        }
        log.putArray("clicks");
        log.putArray("relevance");
        log.set("confidences", confidences(results));
        indexes.save(account, region, QUERY, indexId + "/" + queryId, log);
        return out;
    }

    private static int countType(ArrayNode results, String type) {
        int n = 0;
        for (JsonNode r : results) {
            if (type.equals(r.path("Type").asText())) {
                n++;
            }
        }
        return n;
    }

    private ArrayNode confidences(ArrayNode results) {
        ArrayNode out = mapper().createArrayNode();
        for (int i = 0; i < Math.min(10, results.size()); i++) {
            out.add(results.get(i).path("ScoreAttributes").path("ScoreConfidence").asText("NOT_AVAILABLE"));
        }
        return out;
    }

    private static Entry faqEntry(KendraSearch.Faq faq) {
        return new Entry(KendraSearch.ObjectMapperHolder.MAPPER.createObjectNode(), faq.id(), faq.question(),
                faq.answer(), faq.attributes(), null);
    }

    private static boolean allows(String typeFilter, String type) {
        return typeFilter == null || typeFilter.equals(type);
    }

    private static String feedbackToken(String queryId, String resultId) {
        return "AYADe" + Base64.getUrlEncoder().withoutPadding().encodeToString((queryId + "." + resultId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private void documentFields(ObjectNode item, Entry entry, Parsed parsed, Map<String, ObjectNode> fields,
                                JsonNode requested) {
        item.put("DocumentId", entry.id);
        String title = entry.title == null ? entry.id : entry.title;
        List<KendraAnalyzer.Token> titleTokens = entry.title == null ? KendraAnalyzer.tokens(title)
                : entry.titleTokens;
        item.set("DocumentTitle", KendraSearch.highlighted(mapper(), title, 0, title.length(),
                KendraSearch.marks(titleTokens, parsed), "", "", null));
        item.set("DocumentExcerpt", KendraSearch.excerpt(mapper(), entry.text, KendraSearch.marks(entry.bodyTokens,
                parsed)));
        JsonNode uri = entry.attributes.path("_source_uri").path("StringValue");
        if (!uri.isMissingNode()) {
            item.put("DocumentURI", uri.asText());
        }
        item.set("DocumentAttributes", attributeList(entry.attributes, fields, requested));
    }

    private ArrayNode attributeList(ObjectNode attributes, Map<String, ObjectNode> fields, JsonNode requested) {
        Set<String> wanted = null;
        if (requested != null && requested.isArray()) {
            wanted = new HashSet<>();
            for (JsonNode r : requested) {
                wanted.add(r.asText());
            }
        }
        ArrayNode list = mapper().createArrayNode();
        Set<String> chosen = wanted;
        attributes.fields().forEachRemaining(e -> {
            ObjectNode field = fields.get(e.getKey());
            boolean show = chosen != null ? chosen.contains(e.getKey())
                    : field == null || field.path("Search").path("Displayable").asBoolean(true);
            if (show) {
                ObjectNode a = list.addObject().put("Key", e.getKey());
                a.set("Value", e.getValue().deepCopy());
            }
        });
        return list;
    }

    private static void sort(List<Hit> hits, JsonNode in, Map<String, ObjectNode> fields) {
        List<JsonNode> sorts = new ArrayList<>();
        if (in.has("SortingConfigurations")) {
            in.get("SortingConfigurations").forEach(sorts::add);
        } else if (in.has("SortingConfiguration")) {
            sorts.add(in.get("SortingConfiguration"));
        }
        if (sorts.isEmpty()) {
            return;
        }
        Comparator<Hit> comparator = null;
        for (JsonNode s : sorts) {
            Comparator<Hit> next = sortBy(s, fields);
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        hits.sort(comparator.thenComparing(Comparator.comparingDouble(Hit::score).reversed()));
    }

    private static Comparator<Hit> sortBy(JsonNode sorting, Map<String, ObjectNode> fields) {
        String key = sorting.path("DocumentAttributeKey").asText();
        if (!"_document_relevance".equals(key)) {
            ObjectNode field = fields.get(key);
            if (field == null || !field.path("Search").path("Sortable").asBoolean(false)) {
                throw validation("The document attribute " + key + " is not sortable.");
            }
        }
        boolean descending = "DESC".equals(sorting.path("SortOrder").asText());
        if ("_document_relevance".equals(key)) {
            Comparator<Hit> byScore = Comparator.comparingDouble(Hit::score);
            return descending ? byScore.reversed() : byScore;
        }
        return (a, b) -> {
            Comparable<Object> x = sortValue(a.entry(), key);
            Comparable<Object> y = sortValue(b.entry(), key);
            if (x == null || y == null) {
                return x == null && y == null ? 0 : x == null ? 1 : -1;
            }
            int c = x.compareTo(y);
            return descending ? -c : c;
        };
    }

    @SuppressWarnings("unchecked")
    private static Comparable<Object> sortValue(Entry entry, String key) {
        if ("_document_title".equals(key)) {
            return entry.title == null ? null : (Comparable<Object>) (Object) entry.title.toLowerCase(Locale.ROOT);
        }
        JsonNode value = entry.attributes.get(key);
        if (value == null) {
            return null;
        }
        if (value.has("LongValue")) {
            return (Comparable<Object>) (Object) value.get("LongValue").asDouble();
        }
        if (value.has("DateValue")) {
            return (Comparable<Object>) (Object) value.get("DateValue").asDouble();
        }
        return (Comparable<Object>) (Object) value.path("StringValue").asText("").toLowerCase(Locale.ROOT);
    }

    /** A result after collapsing: the kept document, the group's key, and the documents expanded under it. */
    record Collapsed(Hit hit, JsonNode key, List<Hit> expanded) {
    }

    private List<Collapsed> collapse(List<Hit> hits, JsonNode config, Map<String, ObjectNode> fields) {
        List<Collapsed> out = new ArrayList<>();
        if (config == null || config.isNull()) {
            hits.forEach(h -> out.add(new Collapsed(h, null, List.of())));
            return out;
        }
        String key = config.path("DocumentAttributeKey").asText();
        if (!fields.containsKey(key)) {
            throw validation("The collapse key " + key + " is not a field of the index.");
        }
        String missing = config.path("MissingAttributeKeyStrategy").asText("COLLAPSE");
        boolean expand = config.path("Expand").asBoolean(false);
        int maxItems = config.path("ExpandConfiguration").path("MaxResultItemsToExpand").asInt(10);
        int perItem = config.path("ExpandConfiguration").path("MaxExpandedResultsPerItem").asInt(1);
        Map<String, List<Hit>> groups = new LinkedHashMap<>();
        Map<String, JsonNode> keys = new HashMap<>();
        List<Object[]> order = new ArrayList<>();
        for (Hit hit : hits) {
            JsonNode value = hit.entry().attributes.get(key);
            if (value == null) {
                if ("IGNORE".equals(missing)) {
                    continue;
                }
                if ("EXPAND".equals(missing)) {
                    order.add(new Object[] {null, hit});
                    continue;
                }
            }
            String group = value == null ? "\u0000missing" : value.toString();
            if (!groups.containsKey(group)) {
                order.add(new Object[] {group, null});
                ObjectNode attribute = mapper().createObjectNode().put("Key", key);
                attribute.set("Value", value == null ? mapper().createObjectNode() : value.deepCopy());
                keys.put(group, attribute);
            }
            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(hit);
        }
        Comparator<Hit> within = null;
        for (JsonNode s : config.path("SortingConfigurations")) {
            Comparator<Hit> next = sortBy(s, fields);
            within = within == null ? next : within.thenComparing(next);
        }
        int expandedItems = 0;
        for (Object[] o : order) {
            if (o[0] == null) {
                out.add(new Collapsed((Hit) o[1], null, List.of()));
                continue;
            }
            List<Hit> members = new ArrayList<>(groups.get((String) o[0]));
            if (within != null) {
                members.sort(within);
            }
            List<Hit> expanded = List.of();
            if (expand && expandedItems < maxItems && members.size() > 1) {
                expanded = members.subList(1, Math.min(members.size(), 1 + perItem));
                expandedItems++;
            }
            out.add(new Collapsed(members.get(0), keys.get((String) o[0]), expanded));
        }
        return out;
    }

    // ---------------------------------------------------------------- Retrieve

    public ObjectNode retrieve(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        ObjectNode index = indexes.activeIndex(account, region, indexId);
        String queryText = text(in, "QueryText");
        if (queryText == null || queryText.isBlank()) {
            throw validation("Retrieve needs QueryText.");
        }
        int pageSize = in.hasNonNull("PageSize") ? in.get("PageSize").asInt() : 10;
        int pageNumber = in.hasNonNull("PageNumber") ? in.get("PageNumber").asInt() : 1;
        if (pageSize < 1 || pageSize > MAX_RESULTS || pageNumber < 1) {
            throw validation("PageSize must be between 1 and 100 and PageNumber at least 1.");
        }
        JsonNode filter = in.get("AttributeFilter");
        Map<String, ObjectNode> fields = KendraIndexes.fields(index);
        Viewer viewer = viewer(account, region, index, in.get("UserContext"), filter);
        List<Entry> entries = entries(account, region, index, viewer, filter);
        Parsed parsed = KendraSearch.parse(queryText, content.synonyms(account, region, indexId));
        List<Hit> hits = KendraSearch.score(parsed, entries, fields, relevance(fields,
                in.get("DocumentRelevanceOverrideConfigurations")), Map.of());
        List<KendraSearch.Passage> passages = KendraSearch.passages(parsed, hits);
        String queryId = KendraStore.uuid();
        ObjectNode out = mapper().createObjectNode();
        out.put("QueryId", queryId);
        ArrayNode items = out.putArray("ResultItems");
        int from = Math.min(Math.min(passages.size(), MAX_RESULTS), (pageNumber - 1) * pageSize);
        int to = Math.min(Math.min(passages.size(), MAX_RESULTS), from + pageSize);
        double best = passages.isEmpty() ? 0 : passages.get(0).score();
        for (KendraSearch.Passage passage : passages.subList(from, to)) {
            Entry entry = passage.entry();
            ObjectNode item = items.addObject();
            item.put("Id", queryId + "-" + KendraStore.uuid()).put("DocumentId", entry.id);
            if (entry.title != null) {
                item.put("DocumentTitle", entry.title);
            }
            item.put("Content", entry.text.substring(passage.begin(), passage.end()));
            JsonNode uri = entry.attributes.path("_source_uri").path("StringValue");
            if (!uri.isMissingNode()) {
                item.put("DocumentURI", uri.asText());
            }
            item.set("DocumentAttributes", attributeList(entry.attributes, fields,
                    in.get("RequestedDocumentAttributes")));
            Hit asHit = new Hit(entry, passage.score(), passage.coverage());
            item.putObject("ScoreAttributes").put("ScoreConfidence", KendraSearch.confidence(asHit, best));
        }
        return out;
    }

    // ---------------------------------------------------------------- feedback

    public ObjectNode submitFeedback(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.activeIndex(account, region, indexId);
        String queryId = text(in, "QueryId");
        String path = indexId + "/" + queryId;
        ObjectNode logged = store.get(account, region, QUERY, path).orElse(null);
        if (logged == null) {
            throw notFound("Query " + queryId + " doesn't exist.");
        }
        JsonNode results = logged.path("results");
        for (JsonNode click : in.path("ClickFeedbackItems")) {
            String resultId = click.path("ResultId").asText();
            if (!results.has(resultId)) {
                throw validation("Result " + resultId + " isn't a result of query " + queryId + ".");
            }
            ((ArrayNode) logged.get("clicks")).addObject().put("resultId", resultId)
                    .put("documentId", results.get(resultId).asText())
                    .put("at", click.path("ClickTime").asDouble());
        }
        for (JsonNode feedback : in.path("RelevanceFeedbackItems")) {
            String resultId = feedback.path("ResultId").asText();
            if (!results.has(resultId)) {
                throw validation("Result " + resultId + " isn't a result of query " + queryId + ".");
            }
            ((ArrayNode) logged.get("relevance")).addObject().put("resultId", resultId)
                    .put("documentId", results.get(resultId).asText())
                    .put("value", feedback.path("RelevanceValue").asText());
        }
        store.put(account, region, QUERY, path, logged);
        return mapper().createObjectNode();
    }

    // ---------------------------------------------------------------- query suggestions

    private ObjectNode suggestionsConfig(String account, String region, String indexId) {
        ObjectNode config = store.get(account, region, SUGGESTIONS, indexId).orElse(null);
        if (config == null) {
            config = store.record();
            config.put("Mode", "ENABLED").put("QueryLogLookBackWindowInDays", 180)
                    .put("IncludeQueriesWithoutUserInformation", true).put("MinimumNumberOfQueryingUsers", 1)
                    .put("MinimumQueryCount", 1);
            ObjectNode attributes = config.putObject("AttributeSuggestionsConfig");
            attributes.putArray("SuggestableConfigList");
            attributes.put("AttributeSuggestionsMode", "INACTIVE");
        }
        return config;
    }

    public ObjectNode describeQuerySuggestionsConfig(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.activeIndex(account, region, indexId);
        ObjectNode config = suggestionsConfig(account, region, indexId);
        ObjectNode out = KendraStore.view(config);
        boolean updating = meta(config).path("updatingUntil").asLong() > System.currentTimeMillis();
        out.put("Status", updating ? "UPDATING" : "ACTIVE");
        List<String> built = buildSuggestions(account, region, indexId, config);
        out.put("LastSuggestionsBuildTime", KendraStore.epoch());
        out.put("TotalSuggestionsCount", built.size());
        if (meta(config).has("lastClear")) {
            out.put("LastClearTime", meta(config).get("lastClear").asDouble());
        }
        return out;
    }

    public ObjectNode updateQuerySuggestionsConfig(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.activeIndex(account, region, indexId);
        ObjectNode config = suggestionsConfig(account, region, indexId);
        if (in.hasNonNull("Mode")) {
            config.put("Mode", text(in, "Mode"));
        }
        if (in.hasNonNull("QueryLogLookBackWindowInDays")) {
            int days = in.get("QueryLogLookBackWindowInDays").asInt();
            if (days < 1 || days > 365) {
                throw validation("QueryLogLookBackWindowInDays must be between 1 and 365.");
            }
            config.put("QueryLogLookBackWindowInDays", days);
        }
        if (in.hasNonNull("IncludeQueriesWithoutUserInformation")) {
            config.put("IncludeQueriesWithoutUserInformation", in.get("IncludeQueriesWithoutUserInformation")
                    .asBoolean());
        }
        if (in.hasNonNull("MinimumNumberOfQueryingUsers")) {
            config.put("MinimumNumberOfQueryingUsers", in.get("MinimumNumberOfQueryingUsers").asInt());
        }
        if (in.hasNonNull("MinimumQueryCount")) {
            config.put("MinimumQueryCount", in.get("MinimumQueryCount").asInt());
        }
        if (in.has("AttributeSuggestionsConfig")) {
            JsonNode update = in.get("AttributeSuggestionsConfig");
            ObjectNode attributes = (ObjectNode) config.get("AttributeSuggestionsConfig");
            if (update.has("SuggestableConfigList")) {
                attributes.set("SuggestableConfigList", update.get("SuggestableConfigList").deepCopy());
            }
            if (update.hasNonNull("AttributeSuggestionsMode")) {
                attributes.put("AttributeSuggestionsMode", update.get("AttributeSuggestionsMode").asText());
            }
        }
        meta(config).put("updatingUntil", System.currentTimeMillis() + indexes.transition());
        store.put(account, region, SUGGESTIONS, indexId, config);
        return mapper().createObjectNode();
    }

    public ObjectNode clearQuerySuggestions(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.activeIndex(account, region, indexId);
        ObjectNode config = suggestionsConfig(account, region, indexId);
        meta(config).put("lastClear", KendraStore.epoch());
        store.put(account, region, SUGGESTIONS, indexId, config);
        return mapper().createObjectNode();
    }

    /** The query suggestions the index's log supports now, most asked first. */
    private List<String> buildSuggestions(String account, String region, String indexId, ObjectNode config) {
        long now = System.currentTimeMillis();
        long since = now - config.path("QueryLogLookBackWindowInDays").asLong(180) * 86_400_000L;
        double cleared = meta(config).path("lastClear").asDouble(0) * 1000;
        boolean anonymous = config.path("IncludeQueriesWithoutUserInformation").asBoolean(true);
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Set<String>> users = new HashMap<>();
        Map<String, String> forms = new HashMap<>();
        for (ObjectNode logged : store.list(account, region, QUERY, indexId + "/")) {
            long at = logged.path("at").asLong();
            String normalized = logged.path("normalized").asText();
            if (at < since || at <= cleared || normalized.isEmpty() || logged.path("total").asInt() == 0) {
                continue;
            }
            String user = logged.path("user").asText(null);
            if (user == null && !anonymous) {
                continue;
            }
            counts.merge(normalized, 1, Integer::sum);
            users.computeIfAbsent(normalized, k -> new HashSet<>()).add(user == null ? "\u0000" + at : user);
            forms.putIfAbsent(normalized, normalized);
        }
        int minimumCount = config.path("MinimumQueryCount").asInt(1);
        int minimumUsers = config.path("MinimumNumberOfQueryingUsers").asInt(1);
        List<String> blocked = content.blockedPhrases(account, region, indexId);
        List<String> built = new ArrayList<>();
        counts.forEach((query, count) -> {
            if (count >= minimumCount && users.get(query).size() >= minimumUsers && query.length() <= 60
                    && blocked.stream().noneMatch(query::contains)) {
                built.add(query);
            }
        });
        built.sort(Comparator.<String>comparingInt(counts::get).reversed().thenComparing(Comparator.naturalOrder()));
        return built;
    }

    public ObjectNode getQuerySuggestions(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        ObjectNode index = indexes.activeIndex(account, region, indexId);
        String typed = normalize(text(in, "QueryText"));
        if (typed.length() < 1) {
            throw validation("QueryText must have at least one character.");
        }
        int max = in.hasNonNull("MaxSuggestionsCount") ? in.get("MaxSuggestionsCount").asInt() : 10;
        ObjectNode config = suggestionsConfig(account, region, indexId);
        Set<String> types = new HashSet<>();
        in.path("SuggestionTypes").forEach(t -> types.add(t.asText()));
        if (types.isEmpty()) {
            types.add("QUERY");
        }
        ObjectNode out = mapper().createObjectNode();
        out.put("QuerySuggestionsId", KendraStore.uuid());
        ArrayNode suggestions = out.putArray("Suggestions");
        if (types.contains("QUERY") && "ENABLED".equals(config.path("Mode").asText())) {
            for (String suggestion : buildSuggestions(account, region, indexId, config)) {
                if (suggestions.size() >= max) {
                    break;
                }
                int at = suggestion.startsWith(typed) ? 0 : suggestion.indexOf(" " + typed);
                if (at < 0 || suggestion.equals(typed)) {
                    continue;
                }
                int begin = at == 0 ? 0 : at + 1;
                ObjectNode s = suggestions.addObject().put("Id", KendraStore.uuid());
                ObjectNode text = s.putObject("Value").putObject("Text").put("Text", suggestion);
                text.putArray("Highlights").addObject().put("BeginOffset", begin).put("EndOffset",
                        begin + typed.length());
                s.putArray("SourceDocuments");
            }
        }
        if (types.contains("DOCUMENT_ATTRIBUTES")) {
            attributeSuggestions(account, region, index, in, config, typed, max, suggestions);
        }
        return out;
    }

    private void attributeSuggestions(String account, String region, ObjectNode index, JsonNode in,
                                      ObjectNode config, String typed, int max, ArrayNode suggestions) {
        JsonNode attributeConfig = config.path("AttributeSuggestionsConfig");
        if (!"ACTIVE".equals(attributeConfig.path("AttributeSuggestionsMode").asText())) {
            return;
        }
        Set<String> suggestable = new LinkedHashSet<>();
        for (JsonNode c : attributeConfig.path("SuggestableConfigList")) {
            if (c.path("Suggestable").asBoolean(false)) {
                suggestable.add(c.path("AttributeName").asText());
            }
        }
        JsonNode get = in.path("AttributeSuggestionsConfig");
        if (get.has("SuggestionAttributes")) {
            Set<String> wanted = new LinkedHashSet<>();
            get.get("SuggestionAttributes").forEach(a -> wanted.add(a.asText()));
            suggestable.retainAll(wanted);
        }
        Set<String> additional = new LinkedHashSet<>();
        get.path("AdditionalResponseAttributes").forEach(a -> additional.add(a.asText()));
        JsonNode filter = get.get("AttributeFilter");
        Viewer viewer = viewer(account, region, index, get.get("UserContext"), filter);
        Map<String, List<Entry>> byValue = new TreeMap<>();
        Map<String, List<String>> attributesOf = new HashMap<>();
        for (Entry entry : entries(account, region, index, viewer, filter)) {
            for (String name : suggestable) {
                for (String value : KendraSearch.stringValues(entry.attributes.get(name))) {
                    String lower = value.toLowerCase(Locale.ROOT);
                    if (lower.startsWith(typed) || lower.contains(" " + typed)) {
                        byValue.computeIfAbsent(lower, k -> new ArrayList<>()).add(entry);
                        attributesOf.computeIfAbsent(lower, k -> new ArrayList<>()).add(name);
                    }
                }
            }
        }
        for (Map.Entry<String, List<Entry>> e : byValue.entrySet()) {
            if (suggestions.size() >= max) {
                break;
            }
            String value = e.getKey();
            int at = value.startsWith(typed) ? 0 : value.indexOf(" " + typed) + 1;
            ObjectNode s = suggestions.addObject().put("Id", KendraStore.uuid());
            ObjectNode text = s.putObject("Value").putObject("Text").put("Text", value);
            text.putArray("Highlights").addObject().put("BeginOffset", at).put("EndOffset", at + typed.length());
            ArrayNode sources = s.putArray("SourceDocuments");
            for (Entry entry : e.getValue()) {
                ObjectNode source = sources.addObject().put("DocumentId", entry.id);
                ArrayNode names = source.putArray("SuggestionAttributes");
                new LinkedHashSet<>(attributesOf.get(value)).forEach(names::add);
                ArrayNode extra = source.putArray("AdditionalAttributes");
                for (String name : additional) {
                    JsonNode v = entry.attributes.get(name);
                    if (v != null) {
                        extra.addObject().put("Key", name).set("Value", v.deepCopy());
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- analytics

    public ObjectNode getSnapshots(JsonNode in, String region) {
        String account = store.account();
        String indexId = text(in, "IndexId");
        indexes.activeIndex(account, region, indexId);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        LocalDate[] range = switch (text(in, "Interval")) {
            case "THIS_WEEK" -> new LocalDate[] {thisWeek, today};
            case "ONE_WEEK_AGO" -> new LocalDate[] {thisWeek.minusWeeks(1), thisWeek};
            case "TWO_WEEKS_AGO" -> new LocalDate[] {thisWeek.minusWeeks(2), thisWeek.minusWeeks(1)};
            case "THIS_MONTH" -> new LocalDate[] {today.withDayOfMonth(1), today};
            case "ONE_MONTH_AGO" -> new LocalDate[] {today.withDayOfMonth(1).minusMonths(1), today.withDayOfMonth(1)};
            case "TWO_MONTHS_AGO" -> new LocalDate[] {today.withDayOfMonth(1).minusMonths(2),
                    today.withDayOfMonth(1).minusMonths(1)};
            default -> throw validation("The interval " + text(in, "Interval") + " is not valid.");
        };
        long start = range[0].atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long end = range[1].atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        List<ObjectNode> logs = new ArrayList<>();
        for (ObjectNode logged : store.list(account, region, QUERY, indexId + "/")) {
            long at = logged.path("at").asLong();
            if (at >= start && at < end) {
                logs.add(logged);
            }
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> header;
        String metric = text(in, "MetricType");
        switch (metric) {
            case "QUERIES_BY_COUNT" -> {
                header = List.of("query_content", "count", "click_through_rate", "zero_click_rate",
                        "average_click_rank", "instant_answer_rate", "average_confidence");
                for (Map.Entry<String, List<ObjectNode>> q : byQuery(logs).entrySet()) {
                    List<ObjectNode> asked = q.getValue();
                    rows.add(List.of(q.getKey(), String.valueOf(asked.size()), rate(asked, l -> clicked(l)),
                            rate(asked, l -> !clicked(l)), averageClickRank(asked),
                            rate(asked, l -> l.path("instantAnswer").asBoolean()), averageConfidence(asked)));
                }
            }
            case "QUERIES_BY_ZERO_CLICK_RATE" -> {
                header = List.of("query_content", "zero_click_count", "zero_click_rate", "instant_answer_rate",
                        "average_confidence");
                for (Map.Entry<String, List<ObjectNode>> q : byQuery(logs).entrySet()) {
                    List<ObjectNode> asked = q.getValue();
                    long zero = asked.stream().filter(l -> !clicked(l)).count();
                    if (zero > 0) {
                        rows.add(List.of(q.getKey(), String.valueOf(zero), rate(asked, l -> !clicked(l)),
                                rate(asked, l -> l.path("instantAnswer").asBoolean()), averageConfidence(asked)));
                    }
                }
            }
            case "QUERIES_BY_ZERO_RESULT_RATE" -> {
                header = List.of("query_content", "zero_result_count", "zero_result_rate", "query_share");
                for (Map.Entry<String, List<ObjectNode>> q : byQuery(logs).entrySet()) {
                    List<ObjectNode> asked = q.getValue();
                    long zero = asked.stream().filter(l -> l.path("total").asInt() == 0).count();
                    if (zero > 0) {
                        rows.add(List.of(q.getKey(), String.valueOf(zero), rate(asked, l -> l.path("total")
                                .asInt() == 0), ratio(asked.size(), logs.size())));
                    }
                }
            }
            case "DOCS_BY_CLICK_COUNT" -> {
                header = List.of("document_id", "click_count", "relevant_count", "not_relevant_count");
                Map<String, long[]> docs = new HashMap<>();
                for (ObjectNode logged : logs) {
                    logged.path("clicks").forEach(c -> docs.computeIfAbsent(c.path("documentId").asText(),
                            k -> new long[3])[0]++);
                    logged.path("relevance").forEach(r -> docs.computeIfAbsent(r.path("documentId").asText(),
                            k -> new long[3])["RELEVANT".equals(r.path("value").asText()) ? 1 : 2]++);
                }
                docs.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                        .limit(100).forEach(e -> rows.add(List.of(e.getKey(), String.valueOf(e.getValue()[0]),
                                String.valueOf(e.getValue()[1]), String.valueOf(e.getValue()[2]))));
            }
            case "AGG_QUERY_DOC_METRICS" -> {
                header = List.of("total_queries", "total_documents", "click_through_rate", "zero_click_rate",
                        "zero_result_rate", "instant_answer_rate");
                rows.add(List.of(String.valueOf(logs.size()), String.valueOf(documents.searchable(account, region,
                        indexId).size()), rate(logs, this::clicked), rate(logs, l -> !clicked(l)),
                        rate(logs, l -> l.path("total").asInt() == 0),
                        rate(logs, l -> l.path("instantAnswer").asBoolean())));
            }
            case "TREND_QUERY_DOC_METRICS" -> {
                header = List.of("date", "total_queries", "click_through_rate", "zero_click_rate",
                        "zero_result_rate", "instant_answer_rate");
                for (LocalDate day = range[0]; day.isBefore(range[1]); day = day.plusDays(1)) {
                    long from = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                    long to = from + 86_400_000L;
                    List<ObjectNode> dayLogs = logs.stream().filter(l -> l.path("at").asLong() >= from
                            && l.path("at").asLong() < to).toList();
                    rows.add(List.of(day.toString(), String.valueOf(dayLogs.size()), rate(dayLogs, this::clicked),
                            rate(dayLogs, l -> !clicked(l)), rate(dayLogs, l -> l.path("total").asInt() == 0),
                            rate(dayLogs, l -> l.path("instantAnswer").asBoolean())));
                }
            }
            default -> throw validation("The metric type " + metric + " is not valid.");
        }
        if (metric.startsWith("QUERIES_BY")) {
            rows.sort((a, b) -> Long.compare(Long.parseLong(b.get(1)), Long.parseLong(a.get(1))));
            if (rows.size() > 100) {
                rows.subList(100, rows.size()).clear();
            }
        }
        List<JsonNode> data = new ArrayList<>();
        for (List<String> row : rows) {
            ArrayNode r = mapper().createArrayNode();
            row.forEach(r::add);
            data.add(r);
        }
        ObjectNode out = mapper().createObjectNode();
        out.putObject("SnapShotTimeFilter").put("StartTime", start / 1000.0).put("EndTime", end / 1000.0);
        ArrayNode headerNode = out.putArray("SnapshotsDataHeader");
        header.forEach(headerNode::add);
        KendraStore.page(data, in, out, "SnapshotsData", 100);
        return out;
    }

    private boolean clicked(ObjectNode logged) {
        return !logged.path("clicks").isEmpty();
    }

    private static Map<String, List<ObjectNode>> byQuery(List<ObjectNode> logs) {
        Map<String, List<ObjectNode>> grouped = new LinkedHashMap<>();
        for (ObjectNode logged : logs) {
            String text = logged.path("normalized").asText();
            if (!text.isEmpty()) {
                grouped.computeIfAbsent(text, k -> new ArrayList<>()).add(logged);
            }
        }
        return grouped;
    }

    private static String rate(List<ObjectNode> logs, Predicate<ObjectNode> which) {
        return ratio(logs.stream().filter(which).count(), logs.size());
    }

    private static String ratio(long part, long whole) {
        return whole == 0 ? "0" : String.format(Locale.ROOT, "%.4f", (double) part / whole);
    }

    private static String averageClickRank(List<ObjectNode> logs) {
        double sum = 0;
        int n = 0;
        for (ObjectNode logged : logs) {
            List<String> order = new ArrayList<>();
            logged.path("results").fieldNames().forEachRemaining(order::add);
            for (JsonNode click : logged.path("clicks")) {
                sum += order.indexOf(click.path("resultId").asText()) + 1;
                n++;
            }
        }
        return n == 0 ? "0" : String.format(Locale.ROOT, "%.2f", sum / n);
    }

    private static String averageConfidence(List<ObjectNode> logs) {
        Map<String, Double> scale = Map.of("VERY_HIGH", 1.0, "HIGH", 0.75, "MEDIUM", 0.5, "LOW", 0.25);
        double sum = 0;
        int n = 0;
        for (ObjectNode logged : logs) {
            for (JsonNode c : logged.path("confidences")) {
                Double v = scale.get(c.asText());
                if (v != null) {
                    sum += v;
                    n++;
                }
            }
        }
        return n == 0 ? "0" : String.format(Locale.ROOT, "%.4f", sum / n);
    }
}
