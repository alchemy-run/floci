package io.floci.aws.services.ce;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.UsageLine;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.floci.aws.services.ce.CeRequests.checkTagCount;
import static io.floci.aws.services.ce.CeRequests.invalid;
import static io.floci.aws.services.ce.CeRequests.object;
import static io.floci.aws.services.ce.CeRequests.oneOf;
import static io.floci.aws.services.ce.CeRequests.page;
import static io.floci.aws.services.ce.CeRequests.present;
import static io.floci.aws.services.ce.CeRequests.strings;
import static io.floci.aws.services.ce.CeRequests.tagText;
import static io.floci.aws.services.ce.CeRequests.tags;
import static io.floci.aws.services.ce.CeRequests.text;

/**
 * AWS Cost Categories: rule-based groupings of cost that Cost Explorer filters and groups by. Each definition's
 * rules are evaluated in order against every usage line ({@code REGULAR} rules match a cost expression,
 * {@code INHERITED_VALUE} rules take the value of a tag or the linked account), falling back to the default value,
 * and the resulting value backs {@code CostCategories} filters, {@code COST_CATEGORY} group-bys and
 * {@code GetCostCategories}.
 */
@ApplicationScoped
public class CostCategoryService implements Resettable {

    static final String RULE_VERSION = "CostCategoryExpression.v1";
    private static final Pattern NAME = Pattern.compile("^(?! )[\\p{L}\\p{N}\\p{Z}_-]*(?<! )$");
    private static final Set<String> MATCH_OPTIONS = Set.of("EQUALS", "ABSENT", "STARTS_WITH", "ENDS_WITH",
            "CONTAINS", "CASE_SENSITIVE", "CASE_INSENSITIVE");
    private static final Set<String> RULE_DIMENSIONS = Set.of("LINKED_ACCOUNT", "LINKED_ACCOUNT_NAME", "SERVICE",
            "SERVICE_CODE", "BILLING_ENTITY", "CHARGE_TYPE", "RECORD_TYPE", "REGION", "USAGE_TYPE",
            "USAGE_TYPE_GROUP", "OPERATION", "RESOURCE_ID", "INSTANCE_TYPE", "PURCHASE_TYPE", "PAYER_ACCOUNT");
    private static final int MAX_CATEGORIES = 50;

    private final AccountAwareStorageBackend<ObjectNode> categories;
    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;

    @Inject
    public CostCategoryService(StorageFactory storageFactory, ObjectMapper mapper, RegionResolver regionResolver) {
        this(storageFactory.create("ce", "cost-categories.json", new TypeReference<Map<String, ObjectNode>>() {}),
                mapper, regionResolver);
    }

    CostCategoryService(AccountAwareStorageBackend<ObjectNode> categories, ObjectMapper mapper,
                        RegionResolver regionResolver) {
        this.categories = categories;
        this.mapper = mapper;
        this.regionResolver = regionResolver;
    }

    /** Whether {@code arn} names a cost category, so tagging requests route here. */
    static boolean owns(String arn) {
        return arn != null && arn.contains(":costcategory/");
    }

    public synchronized ObjectNode create(JsonNode request) {
        String account = categories.accountId();
        String name = name(request);
        List<ObjectNode> existing = categories.scanForAccount(account, key -> true);
        if (existing.stream().anyMatch(value -> name.equals(value.path("definition").path("Name").asText()))) {
            throw invalid("Failed to create Cost Category: Cost category name already exists.");
        }
        if (existing.size() >= MAX_CATEGORIES) {
            throw new AwsException("ServiceQuotaExceededException",
                    "The account has reached its limit of " + MAX_CATEGORIES + " cost categories.", 400);
        }
        ObjectNode definition = mapper.createObjectNode();
        String arn = regionResolver.buildGlobalArn("ce", account, "costcategory/" + UUID.randomUUID());
        definition.put("CostCategoryArn", arn);
        definition.put("EffectiveStart", effectiveStart(request));
        definition.put("Name", name);
        applyRules(definition, request);
        ObjectNode tags = tags(mapper, request, false);
        ObjectNode resource = mapper.createObjectNode();
        resource.set("definition", definition);
        resource.set("tags", tags);
        categories.putForAccount(account, arn, resource);
        return mapper.createObjectNode().put("CostCategoryArn", arn)
                .put("EffectiveStart", definition.path("EffectiveStart").asText());
    }

    public synchronized ObjectNode describe(JsonNode request) {
        ObjectNode definition = definition(existing(text(request, "CostCategoryArn", 2048)));
        if (present(request, "EffectiveOn")
                && instant(text(request, "EffectiveOn", 25), "EffectiveOn")
                        .isBefore(Instant.parse(definition.path("EffectiveStart").asText()))) {
            throw notFound();
        }
        ObjectNode response = mapper.createObjectNode();
        response.set("CostCategory", definition);
        return response;
    }

    public synchronized ObjectNode update(JsonNode request) {
        String arn = text(request, "CostCategoryArn", 2048);
        ObjectNode resource = existing(arn).deepCopy();
        ObjectNode definition = (ObjectNode) resource.get("definition");
        if (present(request, "EffectiveStart")) {
            definition.put("EffectiveStart", effectiveStart(request));
        }
        definition.remove(List.of("DefaultValue", "SplitChargeRules"));
        applyRules(definition, request);
        categories.putForAccount(categories.accountId(), arn, resource);
        return mapper.createObjectNode().put("CostCategoryArn", arn)
                .put("EffectiveStart", definition.path("EffectiveStart").asText());
    }

    public synchronized ObjectNode delete(JsonNode request) {
        String arn = text(request, "CostCategoryArn", 2048);
        existing(arn);
        categories.deleteForAccount(categories.accountId(), arn);
        return mapper.createObjectNode().put("CostCategoryArn", arn)
                .put("EffectiveEnd", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    }

    public synchronized ObjectNode list(JsonNode request) {
        Instant on = present(request, "EffectiveOn")
                ? instant(text(request, "EffectiveOn", 25), "EffectiveOn") : Instant.now();
        Set<String> resourceTypes = present(request, "SupportedResourceTypes")
                ? new HashSet<>(strings(request, "SupportedResourceTypes", 10, 1024)) : Set.of();
        List<ObjectNode> references = new ArrayList<>();
        for (ObjectNode definition : active(on)) {
            if (!resourceTypes.isEmpty()) {
                continue;
            }
            ObjectNode reference = mapper.createObjectNode();
            reference.put("CostCategoryArn", definition.path("CostCategoryArn").asText());
            reference.put("Name", definition.path("Name").asText());
            reference.put("EffectiveStart", definition.path("EffectiveStart").asText());
            reference.put("NumberOfRules", definition.path("Rules").size());
            reference.set("ProcessingStatus", definition.path("ProcessingStatus").deepCopy());
            ArrayNode values = reference.putArray("Values");
            regularValues(definition).forEach(values::add);
            if (definition.hasNonNull("DefaultValue")) {
                reference.put("DefaultValue", definition.path("DefaultValue").asText());
            }
            references.add(reference);
        }
        PaginatedResult<ObjectNode> paged = page(references, value -> value.path("CostCategoryArn").asText(),
                request, "MaxResults", "NextToken", 100, 100);
        ObjectNode response = mapper.createObjectNode();
        response.putArray("CostCategoryReferences").addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("NextToken", paged.nextToken());
        }
        return response;
    }

    /**
     * Lists the resources a cost category is associated with. Associations attach a category to billing views
     * and dashboards, none of which Floci creates on its own, so the list is empty for an existing category.
     */
    public synchronized ObjectNode listResourceAssociations(JsonNode request) {
        if (present(request, "CostCategoryArn")) {
            existing(text(request, "CostCategoryArn", 2048));
        }
        PaginatedResult<ObjectNode> paged = page(List.of(), value -> value.path("ResourceArn").asText(), request,
                "MaxResults", "NextToken", 100, 100);
        ObjectNode response = mapper.createObjectNode();
        response.putArray("CostCategoryResourceAssociations").addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("NextToken", paged.nextToken());
        }
        return response;
    }

    public synchronized ObjectNode listTags(JsonNode request) {
        JsonNode tags = existing(text(request, "ResourceArn", 2048)).path("tags");
        ObjectNode response = mapper.createObjectNode();
        ArrayNode output = response.putArray("ResourceTags");
        tags.properties().forEach(entry -> output.addObject()
                .put("Key", entry.getKey()).put("Value", entry.getValue().textValue()));
        return response;
    }

    public synchronized void tagResource(JsonNode request) {
        String arn = text(request, "ResourceArn", 2048);
        ObjectNode resource = existing(arn).deepCopy();
        ObjectNode current = (ObjectNode) resource.get("tags");
        current.setAll(tags(mapper, request, true));
        checkTagCount(current);
        categories.putForAccount(categories.accountId(), arn, resource);
    }

    public synchronized void untagResource(JsonNode request) {
        String arn = text(request, "ResourceArn", 2048);
        ObjectNode resource = existing(arn).deepCopy();
        ObjectNode current = (ObjectNode) resource.get("tags");
        for (String key : strings(request, "ResourceTagKeys", 200, 128)) {
            current.remove(tagText(key, true));
        }
        categories.putForAccount(categories.accountId(), arn, resource);
    }

    /** Names of the categories in effect at any point of {@code [start, end)}. */
    synchronized List<String> namesEffectiveDuring(Instant end) {
        return active(end.minusSeconds(1)).stream().map(value -> value.path("Name").asText()).sorted().toList();
    }

    /**
     * A resolver over the categories in effect at {@code on}, applying each category's rules in order to a usage
     * line. A category whose rules reference itself, directly or through another category, resolves to its
     * default value instead of recursing.
     */
    synchronized FilterExpressionEvaluator.CategoryResolver resolver(Instant on) {
        Map<String, ObjectNode> byName = new LinkedHashMap<>();
        for (ObjectNode definition : active(on)) {
            byName.put(definition.path("Name").asText(), definition);
        }
        return new Resolver(byName);
    }

    @Override
    public synchronized void clear() {
        categories.clear();
    }

    private static final class Resolver implements FilterExpressionEvaluator.CategoryResolver {
        private final Map<String, ObjectNode> byName;
        private final Set<String> evaluating = new HashSet<>();

        Resolver(Map<String, ObjectNode> byName) {
            this.byName = byName;
        }

        @Override
        public String value(String category, UsageLine line) {
            ObjectNode definition = byName.get(category);
            if (definition == null) {
                return null;
            }
            String fallback = definition.hasNonNull("DefaultValue") ? definition.get("DefaultValue").asText() : null;
            if (!evaluating.add(category)) {
                return fallback;
            }
            try {
                for (JsonNode rule : definition.path("Rules")) {
                    if ("INHERITED_VALUE".equals(rule.path("Type").asText("REGULAR"))) {
                        JsonNode inherited = rule.path("InheritedValue");
                        String inheritedValue = "TAG".equals(inherited.path("DimensionName").asText())
                                ? line.tags() == null ? null : line.tags().get(inherited.path("DimensionKey").asText())
                                : line.linkedAccountId();
                        if (inheritedValue != null && !inheritedValue.isEmpty()) {
                            return inheritedValue;
                        }
                    } else if (FilterExpressionEvaluator.matches(rule.path("Rule"), line, this)) {
                        return rule.path("Value").asText();
                    }
                }
                return fallback;
            } finally {
                evaluating.remove(category);
            }
        }
    }

    private List<ObjectNode> active(Instant on) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode resource : categories.scanForAccount(categories.accountId(), key -> true)) {
            ObjectNode definition = definition(resource);
            if (!Instant.parse(definition.path("EffectiveStart").asText()).isAfter(on)) {
                result.add(definition);
            }
        }
        result.sort(Comparator.comparing(value -> value.path("Name").asText()));
        return result;
    }

    private static Set<String> regularValues(JsonNode definition) {
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode rule : definition.path("Rules")) {
            if (rule.hasNonNull("Value")) {
                values.add(rule.get("Value").asText());
            }
        }
        return values;
    }

    private void applyRules(ObjectNode definition, JsonNode request) {
        if (!RULE_VERSION.equals(text(request, "RuleVersion", 1024))) {
            throw invalid("RuleVersion must be " + RULE_VERSION + ".");
        }
        JsonNode rules = request.path("Rules");
        if (!rules.isArray() || rules.isEmpty() || rules.size() > 500) {
            throw invalid("Rules must contain between 1 and 500 rules.");
        }
        for (JsonNode rule : rules) {
            validateRule(rule);
        }
        definition.put("RuleVersion", RULE_VERSION);
        definition.set("Rules", rules.deepCopy());
        if (present(request, "DefaultValue")) {
            definition.put("DefaultValue", value(request, "DefaultValue"));
        }
        if (present(request, "SplitChargeRules")) {
            JsonNode splits = request.get("SplitChargeRules");
            if (!splits.isArray() || splits.isEmpty() || splits.size() > 10) {
                throw invalid("SplitChargeRules must contain between 1 and 10 rules.");
            }
            Set<String> values = regularValues(definition);
            for (JsonNode split : splits) {
                validateSplit(split, values);
            }
            definition.set("SplitChargeRules", splits.deepCopy());
        }
        ArrayNode status = definition.putArray("ProcessingStatus");
        status.addObject().put("Component", "COST_EXPLORER").put("Status", "APPLIED");
    }

    private void validateRule(JsonNode rule) {
        String type = oneOf(rule, "Type", Set.of("REGULAR", "INHERITED_VALUE"), "REGULAR");
        if ("INHERITED_VALUE".equals(type)) {
            if (present(rule, "Value") || present(rule, "Rule")) {
                throw invalid("INHERITED_VALUE rules take an InheritedValue instead of a Value and Rule.");
            }
            ObjectNode inherited = object(rule, "InheritedValue");
            String dimension = oneOf(inherited, "DimensionName", Set.of("LINKED_ACCOUNT_NAME", "TAG"), null);
            if ("TAG".equals(dimension)) {
                text(inherited, "DimensionKey", 1024);
            }
            return;
        }
        value(rule, "Value");
        validateExpression(object(rule, "Rule"));
    }

    private void validateExpression(ObjectNode expression) {
        if (expression.size() != 1) {
            throw invalid("A cost category rule expression must contain exactly one operator.");
        }
        String operator = expression.fieldNames().next();
        switch (operator) {
            case "And", "Or" -> {
                JsonNode children = expression.get(operator);
                if (!children.isArray() || children.size() < 2) {
                    throw invalid(operator + " must combine at least two expressions.");
                }
                for (JsonNode child : children) {
                    if (!(child instanceof ObjectNode nested)) {
                        throw invalid(operator + " must contain expression objects.");
                    }
                    validateExpression(nested);
                }
            }
            case "Not" -> validateExpression(object(expression, "Not"));
            case "Dimensions", "Tags", "CostCategories" -> {
                ObjectNode values = object(expression, operator);
                String key = text(values, "Key", 1024);
                if ("Dimensions".equals(operator) && !RULE_DIMENSIONS.contains(key)) {
                    throw invalid("Dimension " + key + " is not supported in cost category rules.");
                }
                if (present(values, "MatchOptions")) {
                    for (String option : strings(values, "MatchOptions", 10, 1024)) {
                        if (!MATCH_OPTIONS.contains(option)) {
                            throw invalid("MatchOption " + option + " is not supported in cost category rules.");
                        }
                    }
                }
                if (present(values, "Values")) {
                    strings(values, "Values", 10000, 1024);
                }
            }
            default -> throw invalid("Unsupported expression operator: " + operator);
        }
    }

    private void validateSplit(JsonNode split, Set<String> values) {
        String source = text(split, "Source", 1024);
        if (!values.contains(source)) {
            throw invalid("SplitChargeRules Source must be a value of this cost category.");
        }
        List<String> targets = strings(split, "Targets", 500, 1024);
        if (targets.isEmpty() || targets.contains(source) || !values.containsAll(targets)) {
            throw invalid("SplitChargeRules Targets must be other values of this cost category.");
        }
        String method = oneOf(split, "Method", Set.of("FIXED", "PROPORTIONAL", "EVEN"), null);
        if ("FIXED".equals(method)) {
            JsonNode parameters = split.path("Parameters");
            if (!parameters.isArray() || parameters.size() != 1) {
                throw invalid("FIXED split charge rules require one ALLOCATION_PERCENTAGES parameter.");
            }
            JsonNode parameter = parameters.get(0);
            oneOf(parameter, "Type", Set.of("ALLOCATION_PERCENTAGES"), null);
            List<String> percentages = strings(parameter, "Values", 500, 1024);
            if (percentages.size() != targets.size()) {
                throw invalid("ALLOCATION_PERCENTAGES must give one percentage per target.");
            }
            double total = 0;
            for (String percentage : percentages) {
                try {
                    total += Double.parseDouble(percentage);
                } catch (NumberFormatException e) {
                    throw invalid("ALLOCATION_PERCENTAGES values must be numbers.");
                }
            }
            if (Math.abs(total - 100.0) > 0.0001) {
                throw invalid("ALLOCATION_PERCENTAGES values must add up to 100.");
            }
        }
    }

    private String name(JsonNode request) {
        String name = text(request, "Name", 50);
        if (name.isEmpty() || !NAME.matcher(name).matches()) {
            throw invalid("Name must be 1 to 50 letters, numbers, spaces, hyphens or underscores, without leading "
                    + "or trailing spaces.");
        }
        return name;
    }

    private static String value(JsonNode parent, String field) {
        String value = text(parent, field, 50);
        if (value.isEmpty() || !NAME.matcher(value).matches()) {
            throw invalid(field + " must be 1 to 50 letters, numbers, spaces, hyphens or underscores.");
        }
        return value;
    }

    /** The first day of a month, at most 12 months back; the current month when omitted. */
    private static String effectiveStart(JsonNode request) {
        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        if (!present(request, "EffectiveStart")) {
            return current.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        }
        Instant start = instant(text(request, "EffectiveStart", 25), "EffectiveStart");
        LocalDate date = start.atOffset(ZoneOffset.UTC).toLocalDate();
        if (date.getDayOfMonth() != 1 || !start.equals(date.atStartOfDay().toInstant(ZoneOffset.UTC))) {
            throw invalid("EffectiveStart must be the beginning of a month.");
        }
        YearMonth month = YearMonth.from(date);
        if (month.isAfter(current) || month.isBefore(current.minusMonths(12))) {
            throw invalid("EffectiveStart must be the current month or one of the previous 12 months.");
        }
        return start.toString();
    }

    private static Instant instant(String value, String field) {
        try {
            return value.length() == 10 ? LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC)
                    : Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(field + " must be an ISO 8601 date or timestamp.");
        }
    }

    private ObjectNode existing(String arn) {
        return categories.getForAccount(categories.accountId(), arn).orElseThrow(CostCategoryService::notFound);
    }

    private static ObjectNode definition(ObjectNode resource) {
        return resource.get("definition").deepCopy();
    }

    private static AwsException notFound() {
        return new AwsException("ResourceNotFoundException", "The specified cost category does not exist.", 400);
    }
}
