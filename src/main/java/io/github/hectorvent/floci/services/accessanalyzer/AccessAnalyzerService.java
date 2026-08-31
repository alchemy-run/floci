package io.github.hectorvent.floci.services.accessanalyzer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.accessanalyzer.model.Analyzer;
import io.github.hectorvent.floci.services.accessanalyzer.model.ArchiveRule;
import io.github.hectorvent.floci.services.accessanalyzer.model.Criterion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * IAM Access Analyzer restJson1 lifecycle, archive rules, policy checks, and findings APIs.
 *
 * <p>Analyzers are isolated by account and region; one analyzer of each type is allowed per Region.
 */
@ApplicationScoped
public class AccessAnalyzerService implements TagHandler {

    static final String SERVICE = "access-analyzer";
    private static final String RESOURCE_ANALYZER = "AWS::AccessAnalyzer::Analyzer";
    private static final String RESOURCE_ARCHIVE_RULE = "AWS::AccessAnalyzer::ArchiveRule";
    private static final String RESOURCE_FINDING = "AWS::AccessAnalyzer::Finding";
    private static final String TOKEN_PREFIX = "accessanalyzer:v1:";
    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int MAX_RESULTS = 1000;
    private static final int DEFAULT_UNUSED_ACCESS_AGE = 90;
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,254}");
    private static final Pattern RULE_NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,254}");
    private static final Set<String> ANALYZER_TYPES = Set.of(
            "ACCOUNT",
            "ORGANIZATION",
            "ACCOUNT_UNUSED_ACCESS",
            "ORGANIZATION_UNUSED_ACCESS",
            "ACCOUNT_INTERNAL_ACCESS",
            "ORGANIZATION_INTERNAL_ACCESS");
    private static final Set<String> UNUSED_TYPES = Set.of(
            "ACCOUNT_UNUSED_ACCESS", "ORGANIZATION_UNUSED_ACCESS");

    private final StorageBackend<String, Analyzer> store;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public AccessAnalyzerService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create(
                "accessanalyzer",
                "accessanalyzer-analyzers.json",
                new TypeReference<Map<String, Analyzer>>() {
                }), regionResolver, objectMapper);
    }

    AccessAnalyzerService(StorageBackend<String, Analyzer> store, RegionResolver regionResolver) {
        this(store, regionResolver, new ObjectMapper());
    }

    AccessAnalyzerService(
            StorageBackend<String, Analyzer> store, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.store = store;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized Analyzer createAnalyzer(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "analyzerName");
        validateName(name, "analyzerName");
        String type = requireText(request, "type");
        if (!ANALYZER_TYPES.contains(type)) {
            throw validation("type is invalid.", "fieldValidationFailed");
        }
        String key = storageKey(region, name);
        if (store.get(key).isPresent()) {
            throw conflict(name, RESOURCE_ANALYZER, "Analyzer " + name + " already exists.");
        }
        for (Analyzer existing : store.scan(k -> k.startsWith(region + "::"))) {
            if (type.equals(existing.getType())) {
                throw new AwsException(
                        "ServiceQuotaExceededException",
                        "An analyzer of type " + type + " already exists in this Region.",
                        402,
                        Map.of("resourceId", type, "resourceType", RESOURCE_ANALYZER));
            }
        }

        Analyzer analyzer = new Analyzer();
        analyzer.setName(name);
        analyzer.setArn(analyzerArn(region, name));
        analyzer.setType(type);
        analyzer.setStatus("ACTIVE");
        analyzer.setCreatedAt(now());
        analyzer.setTags(readTags(request.get("tags")));
        analyzer.setConfiguration(readConfiguration(type, request.get("configuration")));
        analyzer.setArchiveRules(new LinkedHashMap<>());
        readInlineArchiveRules(request, analyzer);
        store.put(key, analyzer);
        return analyzer;
    }

    public Analyzer getAnalyzer(String region, String analyzerName) {
        return requireAnalyzer(region, analyzerName);
    }

    public synchronized void deleteAnalyzer(String region, String analyzerName) {
        Analyzer analyzer = requireAnalyzer(region, analyzerName);
        store.delete(storageKey(region, analyzer.getName()));
    }

    public synchronized Analyzer updateAnalyzer(String region, String analyzerName, JsonNode request) {
        requireObject(request, "Request body");
        Analyzer analyzer = requireAnalyzer(region, analyzerName);
        if (request.has("configuration") && !request.get("configuration").isNull()) {
            if (UNUSED_TYPES.contains(analyzer.getType()) && unusedAccessAgeChanged(analyzer, request.get("configuration"))) {
                throw validation("Cannot update unused access age", "other");
            }
            analyzer.setConfiguration(readConfiguration(analyzer.getType(), request.get("configuration")));
        }
        store.put(storageKey(region, analyzer.getName()), analyzer);
        return analyzer;
    }

    public Page<Analyzer> listAnalyzers(String region, String type, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        List<Analyzer> analyzers = store.scan(key -> key.startsWith(region + "::"));
        if (type != null && !type.isBlank()) {
            analyzers.removeIf(analyzer -> !type.equals(analyzer.getType()));
        }
        analyzers.sort(Comparator.comparing(Analyzer::getName, Comparator.nullsLast(String::compareTo)));
        return page(analyzers, maxResults, nextToken);
    }

    public synchronized ArchiveRule createArchiveRule(String region, String analyzerName, JsonNode request) {
        requireObject(request, "Request body");
        Analyzer analyzer = requireAnalyzer(region, analyzerName);
        String ruleName = requireText(request, "ruleName");
        validateRuleName(ruleName);
        Map<String, Criterion> filter = readFilter(request.get("filter"));
        Map<String, ArchiveRule> rules = rulesOf(analyzer);
        if (rules.containsKey(ruleName)) {
            throw conflict(ruleName, RESOURCE_ARCHIVE_RULE, "Archive rule " + ruleName + " already exists.");
        }
        ArchiveRule rule = new ArchiveRule();
        String timestamp = now();
        rule.setRuleName(ruleName);
        rule.setFilter(filter);
        rule.setCreatedAt(timestamp);
        rule.setUpdatedAt(timestamp);
        rules.put(ruleName, rule);
        analyzer.setArchiveRules(rules);
        store.put(storageKey(region, analyzer.getName()), analyzer);
        return rule;
    }

    public ArchiveRule getArchiveRule(String region, String analyzerName, String ruleName) {
        Analyzer analyzer = requireAnalyzer(region, analyzerName);
        ArchiveRule rule = rulesOf(analyzer).get(decode(ruleName));
        if (rule == null) {
            throw resourceNotFound(decode(ruleName), RESOURCE_ARCHIVE_RULE,
                    "Archive rule " + decode(ruleName) + " does not exist.");
        }
        return rule;
    }

    public synchronized ArchiveRule updateArchiveRule(
            String region, String analyzerName, String ruleName, JsonNode request) {
        requireObject(request, "Request body");
        Analyzer analyzer = requireAnalyzer(region, analyzerName);
        String decoded = decode(ruleName);
        Map<String, ArchiveRule> rules = rulesOf(analyzer);
        ArchiveRule rule = rules.get(decoded);
        if (rule == null) {
            throw resourceNotFound(decoded, RESOURCE_ARCHIVE_RULE, "Archive rule " + decoded + " does not exist.");
        }
        rule.setFilter(readFilter(request.get("filter")));
        rule.setUpdatedAt(now());
        store.put(storageKey(region, analyzer.getName()), analyzer);
        return rule;
    }

    public synchronized void deleteArchiveRule(String region, String analyzerName, String ruleName) {
        Analyzer analyzer = requireAnalyzer(region, analyzerName);
        String decoded = decode(ruleName);
        Map<String, ArchiveRule> rules = rulesOf(analyzer);
        if (!rules.containsKey(decoded)) {
            throw resourceNotFound(decoded, RESOURCE_ARCHIVE_RULE, "Archive rule " + decoded + " does not exist.");
        }
        rules.remove(decoded);
        analyzer.setArchiveRules(rules);
        store.put(storageKey(region, analyzer.getName()), analyzer);
    }

    public Page<ArchiveRule> listArchiveRules(
            String region, String analyzerName, String maxResultsValue, String nextToken) {
        Analyzer analyzer = requireAnalyzer(region, analyzerName);
        List<ArchiveRule> rules = new ArrayList<>(rulesOf(analyzer).values());
        rules.sort(Comparator.comparing(ArchiveRule::getRuleName, Comparator.nullsLast(String::compareTo)));
        return page(rules, parseMaxResults(maxResultsValue), nextToken);
    }

    public void applyArchiveRule(String region, JsonNode request) {
        requireObject(request, "Request body");
        Analyzer analyzer = requireAnalyzerByArn(region, requireText(request, "analyzerArn"));
        String ruleName = requireText(request, "ruleName");
        if (!rulesOf(analyzer).containsKey(ruleName)) {
            throw resourceNotFound(ruleName, RESOURCE_ARCHIVE_RULE, "Archive rule " + ruleName + " does not exist.");
        }
    }

    public ObjectNode validatePolicy(JsonNode request) {
        requireObject(request, "Request body");
        String document = requireText(request, "policyDocument");
        requireText(request, "policyType");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode findings = response.putArray("findings");
        try {
            JsonNode policy = objectMapper.readTree(document);
            if (policy == null || !policy.isObject() || !policy.has("Statement")) {
                findings.add(errorFinding("The policy document is missing a Statement."));
            }
        } catch (Exception e) {
            findings.add(errorFinding("The policy document is not valid JSON."));
        }
        return response;
    }

    public ObjectNode checkNoNewAccess(JsonNode request) {
        requireObject(request, "Request body");
        requireText(request, "policyType");
        Set<String> existing = actionsOf(requireText(request, "existingPolicyDocument"));
        Set<String> news = actionsOf(requireText(request, "newPolicyDocument"));
        boolean pass = existing.containsAll(news);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("result", pass ? "PASS" : "FAIL");
        return response;
    }

    public ObjectNode checkAccessNotGranted(JsonNode request) {
        requireObject(request, "Request body");
        requireText(request, "policyType");
        Set<String> granted = actionsOf(requireText(request, "policyDocument"));
        Set<String> checked = new HashSet<>();
        JsonNode access = request.get("access");
        if (access == null || !access.isArray() || access.isEmpty()) {
            throw validation("access must be a non-empty array.", "fieldValidationFailed");
        }
        for (JsonNode entry : access) {
            requireObject(entry, "access members");
            JsonNode actions = entry.get("actions");
            if (actions != null && actions.isArray()) {
                for (JsonNode action : actions) {
                    if (action.isTextual()) {
                        checked.add(action.textValue());
                    }
                }
            }
        }
        boolean grantedAny = checked.stream().anyMatch(granted::contains);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("result", grantedAny ? "FAIL" : "PASS");
        return response;
    }

    public ObjectNode checkNoPublicAccess(JsonNode request) {
        requireObject(request, "Request body");
        requireText(request, "resourceType");
        boolean pub = isPublicPolicy(requireText(request, "policyDocument"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("result", pub ? "FAIL" : "PASS");
        return response;
    }

    public ObjectNode listFindingsV2(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireAnalyzerByArn(region, requireText(request, "analyzerArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("findings");
        return response;
    }

    public void getFindingV2(String region, String id, String analyzerArn) {
        if (analyzerArn == null || analyzerArn.isBlank()) {
            throw validation("analyzerArn must be a string.", "fieldValidationFailed");
        }
        requireAnalyzerByArn(region, analyzerArn);
        String findingId = decode(id);
        throw resourceNotFound(findingId, RESOURCE_FINDING, "Finding " + findingId + " does not exist.");
    }

    public ObjectNode listAnalyzedResources(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireAnalyzerByArn(region, requireText(request, "analyzerArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("analyzedResources");
        return response;
    }

    public ObjectNode getFindingsStatistics(String region, JsonNode request) {
        requireObject(request, "Request body");
        Analyzer analyzer = requireAnalyzerByArn(region, requireText(request, "analyzerArn"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode statistics = response.putArray("findingsStatistics");
        ObjectNode entry = statistics.addObject();
        ObjectNode counts = UNUSED_TYPES.contains(analyzer.getType())
                ? entry.putObject("unusedAccessFindingsStatistics")
                : entry.putObject("externalAccessFindingsStatistics");
        counts.put("totalActiveFindings", 0);
        counts.put("totalArchivedFindings", 0);
        counts.put("totalResolvedFindings", 0);
        response.put("lastUpdatedAt", now());
        return response;
    }

    public ObjectNode listPolicyGenerations() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("policyGenerations");
        return response;
    }

    public ObjectNode startPolicyGeneration(JsonNode request) {
        requireObject(request, "Request body");
        JsonNode details = request.get("policyGenerationDetails");
        requireObject(details, "policyGenerationDetails");
        requireText(details, "principalArn");
        if (!request.has("cloudTrailDetails") || request.get("cloudTrailDetails").isNull()) {
            throw validation("Missing cloudTrailDetails", "fieldValidationFailed");
        }
        throw validation("CloudTrail details are incomplete.", "other");
    }

    public void getGeneratedPolicy(String jobId) {
        throw validation("Job id " + decode(jobId) + " is invalid.", "other");
    }

    public void cancelPolicyGeneration(String jobId) {
        throw validation("Job id " + decode(jobId) + " is invalid.", "other");
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Analyzer analyzer = requireAnalyzerByArn(region, arn);
        return analyzer.getTags() == null ? Map.of() : Map.copyOf(analyzer.getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Analyzer analyzer = requireAnalyzerByArn(region, arn);
        Map<String, String> current = analyzer.getTags() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(analyzer.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        analyzer.setTags(current);
        store.put(storageKey(region, analyzer.getName()), analyzer);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Analyzer analyzer = requireAnalyzerByArn(region, arn);
        if (analyzer.getTags() != null && tagKeys != null) {
            tagKeys.forEach(analyzer.getTags()::remove);
        }
        store.put(storageKey(region, analyzer.getName()), analyzer);
    }

    private Analyzer requireAnalyzer(String region, String analyzerName) {
        String name = decode(analyzerName);
        validateName(name, "analyzerName");
        return store.get(storageKey(region, name)).orElseThrow(
                () -> resourceNotFound(name, RESOURCE_ANALYZER, "Analyzer " + name + " does not exist."));
    }

    private Analyzer requireAnalyzerByArn(String region, String arn) {
        String decoded = decode(arn);
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decoded);
        } catch (IllegalArgumentException e) {
            throw validation("analyzerArn is invalid.", "fieldValidationFailed");
        }
        if (!SERVICE.equals(parsed.service()) || !parsed.resource().startsWith("analyzer/")) {
            throw validation("analyzerArn is invalid.", "fieldValidationFailed");
        }
        String name = parsed.resource().substring("analyzer/".length());
        Analyzer analyzer = requireAnalyzer(region, name);
        if (!decoded.equals(analyzer.getArn())) {
            throw resourceNotFound(name, RESOURCE_ANALYZER, "Analyzer " + name + " does not exist.");
        }
        return analyzer;
    }

    private String analyzerArn(String region, String name) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), "analyzer/" + name).toString();
    }

    private static boolean unusedAccessAgeChanged(Analyzer analyzer, JsonNode configuration) {
        Integer current = unusedAccessAge(analyzer.getConfiguration());
        Integer requested = unusedAccessAge(configuration);
        return requested != null && !requested.equals(current);
    }

    private static Integer unusedAccessAge(JsonNode configuration) {
        if (configuration == null || !configuration.isObject() || !configuration.has("unusedAccess")) {
            return null;
        }
        JsonNode unusedAccess = configuration.get("unusedAccess");
        if (unusedAccess == null || !unusedAccess.isObject() || !unusedAccess.has("unusedAccessAge")) {
            return null;
        }
        JsonNode age = unusedAccess.get("unusedAccessAge");
        return age != null && age.isNumber() ? age.intValue() : null;
    }

    private JsonNode readConfiguration(String type, JsonNode configuration) {
        if (UNUSED_TYPES.contains(type)) {
            ObjectNode unused = objectMapper.createObjectNode();
            int age = DEFAULT_UNUSED_ACCESS_AGE;
            if (configuration != null && configuration.isObject() && configuration.has("unusedAccess")) {
                JsonNode unusedAccess = configuration.get("unusedAccess");
                requireObject(unusedAccess, "configuration.unusedAccess");
                if (unusedAccess.has("unusedAccessAge") && unusedAccess.get("unusedAccessAge").isNumber()) {
                    age = unusedAccess.get("unusedAccessAge").intValue();
                }
                if (age < 1 || age > 365) {
                    throw validation("unusedAccessAge must be between 1 and 365.", "fieldValidationFailed");
                }
                unused.set("unusedAccess", unusedAccess.deepCopy());
                if (!unused.get("unusedAccess").has("unusedAccessAge")) {
                    ((ObjectNode) unused.get("unusedAccess")).put("unusedAccessAge", age);
                }
                return unused;
            }
            ObjectNode nested = unused.putObject("unusedAccess");
            nested.put("unusedAccessAge", age);
            return unused;
        }
        if (configuration == null || configuration.isNull()) {
            return null;
        }
        requireObject(configuration, "configuration");
        return configuration.deepCopy();
    }

    private void readInlineArchiveRules(JsonNode request, Analyzer analyzer) {
        if (!request.has("archiveRules") || request.get("archiveRules").isNull()) {
            return;
        }
        JsonNode rulesNode = request.get("archiveRules");
        if (!rulesNode.isArray()) {
            throw validation("archiveRules must be an array.", "fieldValidationFailed");
        }
        Map<String, ArchiveRule> rules = rulesOf(analyzer);
        String timestamp = now();
        for (JsonNode entry : rulesNode) {
            requireObject(entry, "archiveRules members");
            String ruleName = requireText(entry, "ruleName");
            validateRuleName(ruleName);
            ArchiveRule rule = new ArchiveRule();
            rule.setRuleName(ruleName);
            rule.setFilter(readFilter(entry.get("filter")));
            rule.setCreatedAt(timestamp);
            rule.setUpdatedAt(timestamp);
            rules.put(ruleName, rule);
        }
        analyzer.setArchiveRules(rules);
    }

    private Map<String, Criterion> readFilter(JsonNode filter) {
        requireObject(filter, "filter");
        Map<String, Criterion> criteria = new LinkedHashMap<>();
        filter.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            requireObject(value, "filter criteria");
            Criterion criterion = new Criterion();
            if (value.has("eq") && !value.get("eq").isNull()) {
                criterion.setEq(readStringList(value, "eq"));
            }
            if (value.has("neq") && !value.get("neq").isNull()) {
                criterion.setNeq(readStringList(value, "neq"));
            }
            if (value.has("contains") && !value.get("contains").isNull()) {
                criterion.setContains(readStringList(value, "contains"));
            }
            if (value.has("exists") && !value.get("exists").isNull()) {
                JsonNode exists = value.get("exists");
                if (!exists.isBoolean()) {
                    throw validation("exists must be a boolean.", "fieldValidationFailed");
                }
                criterion.setExists(exists.booleanValue());
            }
            criteria.put(entry.getKey(), criterion);
        });
        return criteria;
    }

    private static List<String> readStringList(JsonNode parent, String field) {
        JsonNode array = parent.get(field);
        if (array == null || !array.isArray()) {
            throw validation(field + " must be an array.", "fieldValidationFailed");
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonNode value : array) {
            if (!value.isTextual()) {
                throw validation(field + " members must be strings.", "fieldValidationFailed");
            }
            values.add(value.textValue());
        }
        return values;
    }

    private static Map<String, ArchiveRule> rulesOf(Analyzer analyzer) {
        return analyzer.getArchiveRules() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(analyzer.getArchiveRules());
    }

    private Set<String> actionsOf(String document) {
        try {
            JsonNode policy = objectMapper.readTree(document);
            Set<String> actions = new HashSet<>();
            for (JsonNode statement : statementsOf(policy)) {
                if (!isAllow(statement)) {
                    continue;
                }
                collectStrings(statement.get("Action"), actions);
            }
            return actions;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw validation("policyDocument is not valid JSON.", "cannotParse");
        }
    }

    private boolean isPublicPolicy(String document) {
        try {
            JsonNode policy = objectMapper.readTree(document);
            for (JsonNode statement : statementsOf(policy)) {
                if (!isAllow(statement)) {
                    continue;
                }
                JsonNode principal = statement.get("Principal");
                if (principal == null || principal.isNull()) {
                    continue;
                }
                if (principal.isTextual() && "*".equals(principal.textValue())) {
                    return true;
                }
                if (principal.isObject()) {
                    JsonNode aws = principal.get("AWS");
                    if (aws != null && containsWildcard(aws)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw validation("policyDocument is not valid JSON.", "cannotParse");
        }
    }

    private static List<JsonNode> statementsOf(JsonNode policy) {
        if (policy == null || !policy.isObject()) {
            throw validation("policyDocument must be a JSON object.", "cannotParse");
        }
        JsonNode statement = policy.get("Statement");
        List<JsonNode> statements = new ArrayList<>();
        if (statement == null || statement.isNull()) {
            return statements;
        }
        if (statement.isObject()) {
            statements.add(statement);
        } else if (statement.isArray()) {
            statement.forEach(statements::add);
        }
        return statements;
    }

    private static boolean isAllow(JsonNode statement) {
        JsonNode effect = statement.get("Effect");
        return effect != null && effect.isTextual() && "Allow".equalsIgnoreCase(effect.textValue());
    }

    private static void collectStrings(JsonNode node, Set<String> values) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            values.add(node.textValue());
        } else if (node.isArray()) {
            for (JsonNode value : node) {
                if (value.isTextual()) {
                    values.add(value.textValue());
                }
            }
        }
    }

    private static boolean containsWildcard(JsonNode node) {
        if (node.isTextual()) {
            return "*".equals(node.textValue());
        }
        if (node.isArray()) {
            for (JsonNode value : node) {
                if (value.isTextual() && "*".equals(value.textValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private ObjectNode errorFinding(String details) {
        ObjectNode finding = objectMapper.createObjectNode();
        finding.put("findingDetails", details);
        finding.put("findingType", "ERROR");
        finding.put("issueCode", "INVALID_POLICY");
        finding.put("learnMoreLink", "https://docs.aws.amazon.com/IAM/latest/UserGuide/access_policies.html");
        finding.putArray("locations");
        return finding;
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        if (tagsNode == null || tagsNode.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!tagsNode.isObject() || tagsNode.size() > 50) {
            throw validation("tags must be an object with at most 50 entries.", "fieldValidationFailed");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || !value.isTextual()) {
                throw validation("tags contains an invalid key or value.", "fieldValidationFailed");
            }
            tags.put(entry.getKey(), value.textValue());
        });
        return tags;
    }

    private static String storageKey(String region, String name) {
        return region + "::" + name;
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static void validateName(String name, String field) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw validation(field + " must match [A-Za-z][A-Za-z0-9_.-]* and contain at most 255 characters.",
                    "fieldValidationFailed");
        }
    }

    private static void validateRuleName(String ruleName) {
        if (ruleName == null || !RULE_NAME_PATTERN.matcher(ruleName).matches()) {
            throw validation("ruleName must match [A-Za-z][A-Za-z0-9_.-]* and contain at most 255 characters.",
                    "fieldValidationFailed");
        }
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.", "fieldValidationFailed");
        }
    }

    private static JsonNode requireObjectNode(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        requireObject(value, field);
        return value;
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " must be a string.", "fieldValidationFailed");
        }
        return value.textValue();
    }

    private static int parseMaxResults(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > MAX_RESULTS) {
                throw validation("maxResults must be between 1 and 1000.", "fieldValidationFailed");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw validation("maxResults must be an integer between 1 and 1000.", "fieldValidationFailed");
        }
    }

    private static <T> Page<T> page(List<T> items, int maxResults, String nextToken) {
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + maxResults, items.size());
        String responseToken = end < items.size() ? encodeOffset(end) : null;
        return new Page<>(items.subList(offset, end), responseToken);
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw validation("nextToken is invalid.", "fieldValidationFailed");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset > resultSize) {
                throw validation("nextToken is invalid.", "fieldValidationFailed");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.", "fieldValidationFailed");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    static AwsException resourceNotFound(String resourceId, String resourceType, String message) {
        return new AwsException(
                "ResourceNotFoundException",
                message,
                404,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    private static AwsException conflict(String resourceId, String resourceType, String message) {
        return new AwsException(
                "ConflictException",
                message,
                409,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    static AwsException validation(String message, String reason) {
        return new AwsException("ValidationException", message, 400, Map.of("reason", reason));
    }

    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
