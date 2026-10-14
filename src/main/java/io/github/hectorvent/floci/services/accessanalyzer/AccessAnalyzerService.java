package io.github.hectorvent.floci.services.accessanalyzer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.accessanalyzer.model.Analyzer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

@ApplicationScoped
public class AccessAnalyzerService implements Resettable {
    private static final Pattern ANALYZER_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");
    private static final Set<String> ANALYZER_TYPES = Set.of(
            "ACCOUNT",
            "ORGANIZATION",
            "ACCOUNT_UNUSED_ACCESS",
            "ORGANIZATION_UNUSED_ACCESS",
            "ACCOUNT_INTERNAL_ACCESS",
            "ORGANIZATION_INTERNAL_ACCESS");

    private final AccountAwareStorageBackend<Analyzer> analyzers;
    private final RegionResolver regionResolver;

    @Inject
    public AccessAnalyzerService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.analyzers = storageFactory.create("accessanalyzer", "accessanalyzer-analyzers.json",
                new TypeReference<Map<String, Analyzer>>() {});
        this.regionResolver = regionResolver;
    }

    public synchronized Analyzer createAnalyzer(JsonNode request, String region) {
        String name = requireAnalyzerName(request);
        String type = requireAnalyzerType(text(request, "type"));
        String key = storageKey(region, name);
        if (analyzers.get(key).isPresent()) {
            throw resourceError("ConflictException", "An analyzer with the specified name already exists.",
                    409, name, "AWS::AccessAnalyzer::Analyzer");
        }

        List<Analyzer> inRegion = analyzers.scan(candidate -> candidate.startsWith(region + "::"));
        long sameType = inRegion.stream().filter(analyzer -> type.equals(analyzer.getType())).count();
        if (sameType >= analyzerLimit(type)) {
            throw resourceError("ServiceQuotaExceededException",
                    "The analyzer quota for this account or organization in the Region has been exceeded.",
                    402, name, "AWS::AccessAnalyzer::Analyzer");
        }

        Analyzer analyzer = new Analyzer();
        analyzer.setName(name);
        analyzer.setType(type);
        analyzer.setStatus("ACTIVE");
        analyzer.setCreatedAt(Instant.now().toString());
        analyzer.setArn(regionResolver.buildArn("access-analyzer", region, "analyzer/" + name));
        analyzer.setTags(readTags(request.get("tags")));
        analyzer.setConfiguration(configuration(type, request.get("configuration")));
        if (request.has("archiveRules")) {
            if (!request.get("archiveRules").isArray()) {
                throw validation("archiveRules must be a list.");
            }
            for (JsonNode rule : request.get("archiveRules")) {
                String ruleName = text(rule, "ruleName");
                validateAnalyzerName(ruleName);
                if (analyzer.getArchiveRules().containsKey(ruleName)) {
                    throw validation("archiveRules contains a duplicate ruleName.");
                }
                analyzer.getArchiveRules().put(ruleName, archiveRule(ruleName, rule.get("filter"), null));
            }
        }
        analyzers.put(key, analyzer);
        return analyzer;
    }

    public PaginatedResult<Analyzer> listAnalyzers(String region, String type, Integer maxResults, String nextToken) {
        String requestedType = type == null || type.isBlank() ? null : requireAnalyzerType(type);
        List<Analyzer> matching = analyzers.scan(key -> key.startsWith(region + "::")).stream()
                .filter(analyzer -> requestedType == null || requestedType.equals(analyzer.getType()))
                .toList();
        return paginate(matching, Analyzer::getName, maxResults, nextToken);
    }

    public synchronized void deleteAnalyzer(String region, String analyzerName) {
        validateAnalyzerName(analyzerName);
        String key = storageKey(region, analyzerName);
        if (analyzers.get(key).isEmpty()) {
            throw notFound(analyzerName, "AWS::AccessAnalyzer::Analyzer");
        }
        analyzers.delete(key);
    }

    public Analyzer getAnalyzer(String region, String name) {
        validateAnalyzerName(name);
        return analyzers.get(storageKey(region, name)).orElseThrow(() -> notFound(name, "AWS::AccessAnalyzer::Analyzer"));
    }

    public Analyzer getAnalyzerByArn(String region, String arn) {
        if (arn == null || !arn.contains(":analyzer/")) {
            throw validation("analyzerArn must be an analyzer ARN.");
        }
        String name = arn.substring(arn.indexOf(":analyzer/") + 10);
        Analyzer analyzer = getAnalyzer(region, name);
        if (!analyzer.getArn().equals(arn)) {
            throw notFound(name, "AWS::AccessAnalyzer::Analyzer");
        }
        return analyzer;
    }

    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(getAnalyzerByArn(region, arn).getTags());
    }

    public synchronized void tag(String region, String arn, Map<String, String> tags) {
        Analyzer analyzer = getAnalyzerByArn(region, arn);
        Map<String, String> merged = new LinkedHashMap<>(analyzer.getTags());
        tags.forEach((key, value) -> {
            validateTag(key, value);
            merged.put(key, value);
        });
        if (merged.size() > 50) {
            throw validation("An analyzer can have at most 50 tags.");
        }
        analyzer.setTags(merged);
        analyzers.put(storageKey(region, analyzer.getName()), analyzer);
    }

    public synchronized void untag(String region, String arn, List<String> keys) {
        Analyzer analyzer = getAnalyzerByArn(region, arn);
        if (keys == null || keys.isEmpty() || keys.size() > 50) {
            throw validation("tagKeys must contain 1-50 keys.");
        }
        keys.forEach(key -> validateTag(key, ""));
        Map<String, String> tags = new LinkedHashMap<>(analyzer.getTags());
        keys.forEach(tags::remove);
        analyzer.setTags(tags);
        analyzers.put(storageKey(region, analyzer.getName()), analyzer);
    }

    public JsonNode getArchiveRule(String region, String name, String ruleName) {
        validateAnalyzerName(ruleName);
        JsonNode rule = getAnalyzer(region, name).getArchiveRules().get(ruleName);
        if (rule == null) {
            throw notFound(ruleName, "AWS::AccessAnalyzer::ArchiveRule");
        }
        return rule.deepCopy();
    }

    public PaginatedResult<JsonNode> listArchiveRules(String region, String name,
                                                     Integer maxResults, String nextToken) {
        return paginate(List.copyOf(getAnalyzer(region, name).getArchiveRules().values()),
                rule -> rule.path("ruleName").asText(), maxResults, nextToken);
    }

    public synchronized void createArchiveRule(String region, String name, JsonNode request) {
        Analyzer analyzer = getAnalyzer(region, name);
        String ruleName = text(request, "ruleName");
        validateAnalyzerName(ruleName);
        if (analyzer.getArchiveRules().containsKey(ruleName)) {
            throw resourceError("ConflictException", "The archive rule already exists.",
                    409, ruleName, "AWS::AccessAnalyzer::ArchiveRule");
        }
        analyzer.getArchiveRules().put(ruleName, archiveRule(ruleName, request.get("filter"), null));
        analyzers.put(storageKey(region, name), analyzer);
    }

    public synchronized void updateArchiveRule(String region, String name, String ruleName, JsonNode request) {
        JsonNode previous = getArchiveRule(region, name, ruleName);
        Analyzer analyzer = getAnalyzer(region, name);
        analyzer.getArchiveRules().put(ruleName, archiveRule(ruleName, request.get("filter"), previous));
        analyzers.put(storageKey(region, name), analyzer);
    }

    public synchronized void deleteArchiveRule(String region, String name, String ruleName) {
        getArchiveRule(region, name, ruleName);
        Analyzer analyzer = getAnalyzer(region, name);
        analyzer.getArchiveRules().remove(ruleName);
        analyzers.put(storageKey(region, name), analyzer);
    }

    public void applyArchiveRule(String region, JsonNode request) {
        Analyzer analyzer = getAnalyzerByArn(region, text(request, "analyzerArn"));
        getArchiveRule(region, analyzer.getName(), text(request, "ruleName"));
        // There are no recorded findings: scans and preview generation are explicitly unsupported.
    }

    private static JsonNode archiveRule(String name, JsonNode filter, JsonNode previous) {
        validateFilter(filter);
        String now = Instant.now().toString();
        ObjectNode rule = JsonNodeFactory.instance.objectNode();
        rule.put("ruleName", name);
        rule.set("filter", filter.deepCopy());
        rule.put("createdAt", previous == null ? now : previous.path("createdAt").asText());
        rule.put("updatedAt", now);
        return rule;
    }

    private static void validateFilter(JsonNode filter) {
        if (filter == null || !filter.isObject() || filter.isEmpty()) {
            throw validation("filter must be a non-empty map of criteria.");
        }
        filter.fields().forEachRemaining(entry -> {
            JsonNode criterion = entry.getValue();
            if (entry.getKey().isBlank() || !criterion.isObject() || criterion.isEmpty()) {
                throw validation("Each filter criterion must contain a comparison.");
            }
            criterion.fields().forEachRemaining(comparison -> {
                String operator = comparison.getKey();
                JsonNode value = comparison.getValue();
                if ("exists".equals(operator)) {
                    if (!value.isBoolean()) {
                        throw validation("exists must be a boolean.");
                    }
                } else if (Set.of("eq", "neq", "contains").contains(operator)) {
                    if (!value.isArray() || value.isEmpty()) {
                        throw validation(operator + " must be a non-empty string list.");
                    }
                    for (JsonNode item : value) {
                        if (!item.isTextual() || item.asText().isEmpty()) {
                            throw validation(operator + " must contain non-empty strings.");
                        }
                    }
                } else {
                    throw validation("Unknown filter comparison: " + operator);
                }
            });
        });
    }

    private static JsonNode configuration(String type, JsonNode configuration) {
        if (configuration != null && !configuration.isObject()) {
            throw validation("configuration must be an object.");
        }
        if (!type.endsWith("_UNUSED_ACCESS")) {
            if (configuration != null) {
                throw validation("Configuration is only supported for unused-access analyzers in Floci.");
            }
            return null;
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        int age = 90;
        if (configuration != null) {
            JsonNode unused = configuration.get("unusedAccess");
            if (configuration.size() != 1 || unused == null || !unused.isObject()) {
                throw validation("Unused-access analyzers require configuration.unusedAccess.");
            }
            if (unused.has("unusedAccessAge")) {
                JsonNode value = unused.get("unusedAccessAge");
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1
                        || value.intValue() > 365) {
                    throw validation("unusedAccessAge must be an integer between 1 and 365.");
                }
                age = value.intValue();
            }
            if (unused.size() > (unused.has("unusedAccessAge") ? 1 : 0)) {
                throw validation("Unused-access analysis rules are not supported by Floci.");
            }
        }
        result.putObject("unusedAccess").put("unusedAccessAge", age);
        return result;
    }

    static AwsException notFound(String id, String type) {
        return resourceError("ResourceNotFoundException", "The specified " + type + " could not be found.",
                404, id, type);
    }

    private static AwsException resourceError(String code, String message, int status, String id, String type) {
        return new AwsException(code, message, status, Map.of("resourceId", id, "resourceType", type));
    }

    static AwsException unsupported(String operation) {
        return validation(operation + " is not supported by Floci; no analysis has been performed.");
    }

    private static int analyzerLimit(String type) {
        return switch (type) {
            case "ORGANIZATION", "ORGANIZATION_UNUSED_ACCESS" -> 5;
            case "ACCOUNT", "ACCOUNT_UNUSED_ACCESS", "ACCOUNT_INTERNAL_ACCESS",
                    "ORGANIZATION_INTERNAL_ACCESS" -> 1;
            default -> throw new IllegalArgumentException("Unsupported analyzer type: " + type);
        };
    }

    @Override
    public void clear() {
        analyzers.clear();
    }

    private static String requireAnalyzerName(JsonNode request) {
        String value = text(request, "analyzerName");
        validateAnalyzerName(value);
        return value;
    }

    private static void validateAnalyzerName(String value) {
        if (value == null || value.length() < 1 || value.length() > 255 || !ANALYZER_NAME.matcher(value).matches()) {
            throw validation("analyzerName must be 1-255 characters and match [A-Za-z][A-Za-z0-9_.-]*.");
        }
    }

    private static String requireAnalyzerType(String value) {
        if (value == null || !ANALYZER_TYPES.contains(value)) {
            throw validation("type must be a valid analyzer type.");
        }
        return value;
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return tags;
        }
        if (!node.isObject()) {
            throw validation("tags must be an object.");
        }
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            validateTag(key, value.isTextual() ? value.textValue() : null);
            tags.put(key, value.textValue());
        });
        if (tags.size() > 50) {
            throw validation("An analyzer can have at most 50 tags.");
        }
        return tags;
    }

    private static void validateTag(String key, String value) {
        if (key == null || key.isEmpty() || key.length() > 128 || key.startsWith("aws:")
                || value == null || value.length() > 256) {
            throw validation("tags contain an invalid key or value.");
        }
    }

    static String text(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private static String storageKey(String region, String analyzerName) {
        return region + "::" + analyzerName;
    }

    static Integer parseMaxResults(String value) {
        try {
            return Pagination.parseMaxResults(value, "ValidationException");
        } catch (AwsException error) {
            throw validation(error.getMessage());
        }
    }

    private static <T> PaginatedResult<T> paginate(List<T> items, Function<T, String> key,
                                                  Integer maxResults, String nextToken) {
        try {
            return Pagination.paginate(items, key, maxResults, nextToken, 100, 1000, "ValidationException");
        } catch (AwsException error) {
            throw validation(error.getMessage());
        }
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400, Map.of("reason", "other"));
    }
}
