package io.github.hectorvent.floci.services.macie2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.macie2.MacieDataClassifier.Detection;
import io.github.hectorvent.floci.services.macie2.MacieDataClassifier.Occurrence;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

import static io.github.hectorvent.floci.services.macie2.MacieService.object;
import static io.github.hectorvent.floci.services.macie2.MacieService.readTags;
import static io.github.hectorvent.floci.services.macie2.MacieService.requireText;
import static io.github.hectorvent.floci.services.macie2.MacieService.strings;
import static io.github.hectorvent.floci.services.macie2.MacieService.tagsNode;
import static io.github.hectorvent.floci.services.macie2.MacieService.text;
import static io.github.hectorvent.floci.services.macie2.MacieService.validation;

/** Request validation, S3 selection, schedules, listing, and finding documents for classification jobs. */
final class MacieClassificationJobs {

    static final String KIND = "classification-job";
    static final String ONE_TIME = "ONE_TIME";
    static final String SCHEDULED = "SCHEDULED";
    /** A paused job (or scheduled run) that is not resumed within 30 days expires. */
    static final Duration PAUSE_EXPIRY = Duration.ofDays(30);
    /** Objects above this size are skipped instead of being loaded into memory. */
    static final long MAX_OBJECT_BYTES = 20L * 1024 * 1024;

    private static final List<String> SELECTORS = List.of("ALL", "EXCLUDE", "INCLUDE", "NONE", "RECOMMENDED");
    private static final List<String> DAYS = List.of(
            "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
    private static final int MAX_BUCKETS = 1000;
    private static final int MAX_CUSTOM_IDENTIFIERS = 30;
    private static final int MAX_ALLOW_LISTS = 10;

    private MacieClassificationJobs() {}

    /** One S3 bucket selected by a job, read as the account that owns it. */
    record Target(String accountId, Bucket bucket) {}

    /** One S3 object in scope for a run. */
    record Candidate(Target target, String key, long size, Instant lastModified) {
        String id() {
            return target.accountId() + "/" + target.bucket().getName() + "/" + key;
        }
    }

    /** Validates a CreateClassificationJob request and returns the stored job definition. */
    static ObjectNode definition(JsonNode request, String callerAccountId, Predicate<String> memberAccount,
                                 Predicate<String> customIdentifierExists, Predicate<String> allowListExists) {
        ObjectNode job = object();
        String name = text(request, "name", true);
        requireText(name, "name", 1, 500);
        job.put("name", name);
        String description = text(request, "description", false);
        if (description != null) {
            requireText(description, "description", 0, 200);
            job.put("description", description);
        }
        String jobType = text(request, "jobType", true);
        if (!ONE_TIME.equals(jobType) && !SCHEDULED.equals(jobType)) {
            throw validation("jobType must be ONE_TIME or SCHEDULED.");
        }
        job.put("jobType", jobType);
        job.set("s3JobDefinition", s3JobDefinition(request.get("s3JobDefinition"), callerAccountId, memberAccount));

        JsonNode sampling = request.get("samplingPercentage");
        if (sampling != null && !sampling.isNull()) {
            if (!sampling.isIntegralNumber() || sampling.asInt() < 1 || sampling.asInt() > 100) {
                throw validation("samplingPercentage must be an integer between 1 and 100.");
            }
            job.put("samplingPercentage", sampling.asInt());
        } else {
            job.put("samplingPercentage", 100);
        }

        JsonNode initialRun = request.get("initialRun");
        if (initialRun != null && !initialRun.isNull()) {
            if (!initialRun.isBoolean()) {
                throw validation("initialRun must be a boolean.");
            }
            job.put("initialRun", initialRun.booleanValue());
        } else if (SCHEDULED.equals(jobType)) {
            job.put("initialRun", false);
        }

        JsonNode schedule = request.get("scheduleFrequency");
        boolean hasSchedule = schedule != null && !schedule.isNull();
        if (ONE_TIME.equals(jobType) && hasSchedule) {
            throw validation("scheduleFrequency can't be specified for a ONE_TIME job.");
        }
        if (SCHEDULED.equals(jobType)) {
            if (!hasSchedule) {
                throw validation("scheduleFrequency is required for a SCHEDULED job.");
            }
            validateSchedule(schedule);
            job.set("scheduleFrequency", schedule.deepCopy());
        }

        String selector = text(request, "managedDataIdentifierSelector", false);
        selector = selector == null ? "RECOMMENDED" : selector;
        if (!SELECTORS.contains(selector)) {
            throw validation("managedDataIdentifierSelector must be ALL, EXCLUDE, INCLUDE, NONE, or RECOMMENDED.");
        }
        List<String> managedIds = strings(request.path("managedDataIdentifierIds"), "managedDataIdentifierIds");
        boolean listsManaged = "INCLUDE".equals(selector) || "EXCLUDE".equals(selector);
        if (listsManaged && managedIds.isEmpty()) {
            throw validation("managedDataIdentifierIds is required when managedDataIdentifierSelector is "
                    + selector + ".");
        }
        if (!listsManaged && !managedIds.isEmpty()) {
            throw validation("managedDataIdentifierIds can't be specified when managedDataIdentifierSelector is "
                    + selector + ".");
        }
        for (String id : managedIds) {
            if (!id.matches("[A-Z0-9_]{1,128}")) {
                throw validation("managedDataIdentifierIds contains an invalid identifier: " + id);
            }
            if ("INCLUDE".equals(selector) && MacieDataClassifier.managed(id) == null) {
                throw validation("Floci does not implement the managed data identifier " + id
                        + "; see ListManagedDataIdentifiers for the supported identifiers.");
            }
        }
        job.put("managedDataIdentifierSelector", selector);
        if (!managedIds.isEmpty()) {
            ArrayNode ids = job.putArray("managedDataIdentifierIds");
            managedIds.forEach(ids::add);
        }

        List<String> customIds = strings(request.path("customDataIdentifierIds"), "customDataIdentifierIds");
        if (customIds.size() > MAX_CUSTOM_IDENTIFIERS) {
            throw validation("A job can use at most " + MAX_CUSTOM_IDENTIFIERS + " custom data identifiers.");
        }
        for (String id : customIds) {
            if (!customIdentifierExists.test(id)) {
                throw MacieService.notFound("The custom data identifier " + id + " does not exist.");
            }
        }
        if ("NONE".equals(selector) && customIds.isEmpty()) {
            throw validation("customDataIdentifierIds must contain at least one identifier when "
                    + "managedDataIdentifierSelector is NONE.");
        }
        ArrayNode custom = job.putArray("customDataIdentifierIds");
        customIds.forEach(custom::add);

        List<String> allowListIds = strings(request.path("allowListIds"), "allowListIds");
        if (allowListIds.size() > MAX_ALLOW_LISTS) {
            throw validation("A job can use at most " + MAX_ALLOW_LISTS + " allow lists.");
        }
        for (String id : allowListIds) {
            if (!allowListExists.test(id)) {
                throw MacieService.notFound("The allow list " + id + " does not exist.");
            }
        }
        ArrayNode allow = job.putArray("allowListIds");
        allowListIds.forEach(allow::add);

        job.set("tags", tagsNode(readTags(request.path("tags"))));
        return job;
    }

    private static ObjectNode s3JobDefinition(JsonNode node, String callerAccountId, Predicate<String> memberAccount) {
        if (node == null || !node.isObject()) {
            throw validation("s3JobDefinition is required.");
        }
        boolean definitions = node.hasNonNull("bucketDefinitions");
        boolean criteria = node.hasNonNull("bucketCriteria");
        if (definitions == criteria) {
            throw validation("s3JobDefinition must specify either bucketDefinitions or bucketCriteria, but not both.");
        }
        ObjectNode result = object();
        if (definitions) {
            JsonNode list = node.get("bucketDefinitions");
            if (!list.isArray() || list.isEmpty()) {
                throw validation("bucketDefinitions must contain at least one bucket definition.");
            }
            ArrayNode out = result.putArray("bucketDefinitions");
            int total = 0;
            for (JsonNode definition : list) {
                if (!definition.isObject()) {
                    throw validation("Each bucket definition must be an object.");
                }
                String accountId = text(definition, "accountId", true);
                if (!accountId.matches("\\d{12}")) {
                    throw validation("accountId must be a 12 digit account ID.");
                }
                if (!accountId.equals(callerAccountId) && !memberAccount.test(accountId)) {
                    throw validation("Account " + accountId
                            + " is neither the current account nor an associated Macie member account.");
                }
                List<String> buckets = strings(definition.path("buckets"), "buckets");
                if (buckets.isEmpty() || buckets.stream().anyMatch(String::isBlank)) {
                    throw validation("buckets must contain at least one bucket name.");
                }
                total += buckets.size();
                ObjectNode copy = out.addObject().put("accountId", accountId);
                ArrayNode names = copy.putArray("buckets");
                buckets.forEach(names::add);
            }
            if (total > MAX_BUCKETS) {
                throw validation("A job can analyze at most " + MAX_BUCKETS + " buckets.");
            }
        } else {
            JsonNode bucketCriteria = node.get("bucketCriteria");
            validateBucketCriteria(bucketCriteria);
            result.set("bucketCriteria", bucketCriteria.deepCopy());
        }
        if (node.hasNonNull("scoping")) {
            validateScoping(node.get("scoping"));
            result.set("scoping", node.get("scoping").deepCopy());
        }
        return result;
    }

    private static void validateBucketCriteria(JsonNode criteria) {
        if (!criteria.isObject() || (!criteria.hasNonNull("includes") && !criteria.hasNonNull("excludes"))) {
            throw validation("bucketCriteria must specify includes, excludes, or both.");
        }
        for (String mode : List.of("includes", "excludes")) {
            if (!criteria.hasNonNull(mode)) {
                continue;
            }
            JsonNode terms = criteria.get(mode).path("and");
            if (!terms.isArray() || terms.isEmpty()) {
                throw validation("bucketCriteria." + mode + ".and must contain at least one condition.");
            }
            for (JsonNode term : terms) {
                boolean simple = term.hasNonNull("simpleCriterion");
                if (simple == term.hasNonNull("tagCriterion")) {
                    throw validation("Each bucket condition must specify simpleCriterion or tagCriterion.");
                }
                if (simple) {
                    JsonNode criterion = term.get("simpleCriterion");
                    String key = text(criterion, "key", true);
                    String comparator = text(criterion, "comparator", true);
                    List<String> allowed = switch (key) {
                        case "ACCOUNT_ID" -> List.of("EQ", "NE");
                        case "S3_BUCKET_NAME" -> List.of("EQ", "NE", "STARTS_WITH", "CONTAINS");
                        case "S3_BUCKET_EFFECTIVE_PERMISSION", "S3_BUCKET_SHARED_ACCESS" -> throw validation(
                                "Floci does not evaluate the " + key + " bucket criterion.");
                        default -> throw validation("Unsupported bucket criterion key: " + key);
                    };
                    if (!allowed.contains(comparator)) {
                        throw validation("Comparator " + comparator + " isn't valid for " + key + ".");
                    }
                    if (strings(criterion.path("values"), "values").isEmpty()) {
                        throw validation("Bucket criterion values cannot be empty.");
                    }
                } else {
                    validateTagCondition(term.get("tagCriterion"), "tagValues");
                }
            }
        }
    }

    private static void validateScoping(JsonNode scoping) {
        if (!scoping.isObject()) {
            throw validation("scoping must be an object.");
        }
        for (String mode : List.of("includes", "excludes")) {
            if (!scoping.hasNonNull(mode)) {
                continue;
            }
            JsonNode terms = scoping.get(mode).path("and");
            if (!terms.isArray() || terms.isEmpty()) {
                throw validation("scoping." + mode + ".and must contain at least one condition.");
            }
            for (JsonNode term : terms) {
                boolean simple = term.hasNonNull("simpleScopeTerm");
                if (simple == term.hasNonNull("tagScopeTerm")) {
                    throw validation("Each scope condition must specify simpleScopeTerm or tagScopeTerm.");
                }
                if (!simple) {
                    JsonNode tag = term.get("tagScopeTerm");
                    if (!"TAG".equals(text(tag, "key", true))) {
                        throw validation("tagScopeTerm.key must be TAG.");
                    }
                    String target = text(tag, "target", false);
                    if (target != null && !"S3_OBJECT".equals(target)) {
                        throw validation("tagScopeTerm.target must be S3_OBJECT.");
                    }
                    validateTagCondition(tag, "tagValues");
                    continue;
                }
                JsonNode criterion = term.get("simpleScopeTerm");
                String key = text(criterion, "key", true);
                String comparator = text(criterion, "comparator", true);
                List<String> values = strings(criterion.path("values"), "values");
                if (values.isEmpty()) {
                    throw validation("Scope term values cannot be empty.");
                }
                List<String> allowed = switch (key) {
                    case "OBJECT_EXTENSION" -> List.of("EQ", "NE");
                    case "OBJECT_KEY" -> List.of("EQ", "NE", "STARTS_WITH");
                    case "OBJECT_LAST_MODIFIED_DATE", "OBJECT_SIZE" -> List.of("EQ", "NE", "GT", "GTE", "LT", "LTE");
                    default -> throw validation("Unsupported scope term key: " + key);
                };
                if (!allowed.contains(comparator)) {
                    throw validation("Comparator " + comparator + " isn't valid for " + key + ".");
                }
                for (String value : values) {
                    try {
                        if ("OBJECT_SIZE".equals(key)) {
                            Long.parseLong(value);
                        } else if ("OBJECT_LAST_MODIFIED_DATE".equals(key)) {
                            Instant.parse(value);
                        }
                    } catch (NumberFormatException | DateTimeParseException e) {
                        throw validation("Invalid value for " + key + ": " + value);
                    }
                }
            }
        }
    }

    private static void validateTagCondition(JsonNode condition, String field) {
        String comparator = text(condition, "comparator", true);
        if (!"EQ".equals(comparator) && !"NE".equals(comparator)) {
            throw validation("Tag conditions support the EQ and NE comparators only.");
        }
        JsonNode pairs = condition.path(field);
        if (!pairs.isArray() || pairs.isEmpty()) {
            throw validation(field + " must contain at least one tag.");
        }
        for (JsonNode pair : pairs) {
            text(pair, "key", true);
            text(pair, "value", false);
        }
    }

    static void validateSchedule(JsonNode schedule) {
        if (!schedule.isObject()) {
            throw validation("scheduleFrequency must be an object.");
        }
        int count = 0;
        for (String kind : List.of("dailySchedule", "weeklySchedule", "monthlySchedule")) {
            if (schedule.hasNonNull(kind)) {
                count++;
            }
        }
        if (count != 1) {
            throw validation("scheduleFrequency must specify exactly one of dailySchedule, weeklySchedule, "
                    + "or monthlySchedule.");
        }
        if (schedule.hasNonNull("weeklySchedule")
                && !DAYS.contains(schedule.get("weeklySchedule").path("dayOfWeek").asText())) {
            throw validation("weeklySchedule.dayOfWeek must be a day of the week, such as MONDAY.");
        }
        if (schedule.hasNonNull("monthlySchedule")) {
            JsonNode day = schedule.get("monthlySchedule").path("dayOfMonth");
            if (!day.isIntegralNumber() || day.asInt() < 1 || day.asInt() > 31) {
                throw validation("monthlySchedule.dayOfMonth must be an integer between 1 and 31.");
            }
        }
    }

    /**
     * The first scheduled start strictly after {@code after}. Runs start at 00:00 UTC; a monthly
     * day that a month doesn't have skips that month, as Macie does.
     */
    static Instant nextRun(JsonNode schedule, Instant after) {
        LocalDate date = after.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
        if (schedule.hasNonNull("weeklySchedule")) {
            DayOfWeek day = DayOfWeek.valueOf(schedule.get("weeklySchedule").path("dayOfWeek").asText());
            while (date.getDayOfWeek() != day) {
                date = date.plusDays(1);
            }
        } else if (schedule.hasNonNull("monthlySchedule")) {
            int day = schedule.get("monthlySchedule").path("dayOfMonth").asInt();
            YearMonth month = YearMonth.from(date);
            while (day > month.lengthOfMonth() || month.atDay(day).isBefore(date)) {
                month = month.plusMonths(1);
            }
            date = month.atDay(day);
        }
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    static boolean bucketSelected(JsonNode criteria, String accountId, Bucket bucket) {
        JsonNode includes = criteria.get("includes");
        JsonNode excludes = criteria.get("excludes");
        boolean included = includes == null || includes.isNull() || allBucketTermsMatch(includes, accountId, bucket);
        boolean excluded = excludes != null && !excludes.isNull() && allBucketTermsMatch(excludes, accountId, bucket);
        return included && !excluded;
    }

    private static boolean allBucketTermsMatch(JsonNode block, String accountId, Bucket bucket) {
        for (JsonNode term : block.path("and")) {
            boolean match;
            if (term.hasNonNull("simpleCriterion")) {
                JsonNode criterion = term.get("simpleCriterion");
                String actual = "ACCOUNT_ID".equals(criterion.path("key").asText()) ? accountId : bucket.getName();
                match = compareStrings(criterion.path("comparator").asText(),
                        strings(criterion.path("values"), "values"), actual);
            } else {
                JsonNode tag = term.get("tagCriterion");
                boolean any = tagsMatch(tag.path("tagValues"), bucket.getTags());
                match = "EQ".equals(tag.path("comparator").asText()) == any;
            }
            if (!match) {
                return false;
            }
        }
        return true;
    }

    static boolean objectInScope(JsonNode scoping, String key, long size, Instant lastModified,
                                 Map<String, String> tags) {
        if (scoping == null || scoping.isNull() || scoping.isMissingNode()) {
            return true;
        }
        JsonNode includes = scoping.get("includes");
        JsonNode excludes = scoping.get("excludes");
        boolean included = includes == null || includes.isNull()
                || allScopeTermsMatch(includes, key, size, lastModified, tags);
        boolean excluded = excludes != null && !excludes.isNull()
                && allScopeTermsMatch(excludes, key, size, lastModified, tags);
        return included && !excluded;
    }

    static boolean scopingUsesTags(JsonNode scoping) {
        return scoping != null && scoping.findValue("tagScopeTerm") != null;
    }

    private static boolean allScopeTermsMatch(JsonNode block, String key, long size, Instant lastModified,
                                              Map<String, String> tags) {
        for (JsonNode term : block.path("and")) {
            boolean match;
            if (term.hasNonNull("tagScopeTerm")) {
                JsonNode tag = term.get("tagScopeTerm");
                boolean any = tagsMatch(tag.path("tagValues"), tags);
                match = "EQ".equals(tag.path("comparator").asText()) == any;
            } else {
                JsonNode criterion = term.get("simpleScopeTerm");
                String comparator = criterion.path("comparator").asText();
                List<String> values = strings(criterion.path("values"), "values");
                match = switch (criterion.path("key").asText()) {
                    case "OBJECT_EXTENSION" -> compareStrings(comparator,
                            values.stream().map(v -> v.startsWith(".") ? v.substring(1) : v)
                                    .map(v -> v.toLowerCase(Locale.ROOT)).toList(),
                            extension(key).toLowerCase(Locale.ROOT));
                    case "OBJECT_KEY" -> compareStrings(comparator, values, key);
                    case "OBJECT_SIZE" -> compareNumbers(comparator,
                            values.stream().map(Long::parseLong).toList(), size);
                    case "OBJECT_LAST_MODIFIED_DATE" -> lastModified != null && compareNumbers(comparator,
                            values.stream().map(v -> Instant.parse(v).toEpochMilli()).toList(),
                            lastModified.toEpochMilli());
                    default -> false;
                };
            }
            if (!match) {
                return false;
            }
        }
        return true;
    }

    private static boolean tagsMatch(JsonNode pairs, Map<String, String> tags) {
        Map<String, String> actual = tags == null ? Map.of() : tags;
        for (JsonNode pair : pairs) {
            String key = pair.path("key").asText();
            JsonNode value = pair.get("value");
            if (actual.containsKey(key) && (value == null || value.isNull()
                    || value.asText().equals(actual.get(key)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean compareStrings(String comparator, List<String> values, String actual) {
        return switch (comparator) {
            case "EQ" -> values.contains(actual);
            case "NE" -> !values.contains(actual);
            case "STARTS_WITH" -> values.stream().anyMatch(actual::startsWith);
            case "CONTAINS" -> values.stream().anyMatch(actual::contains);
            default -> false;
        };
    }

    private static boolean compareNumbers(String comparator, List<Long> values, long actual) {
        return switch (comparator) {
            case "EQ" -> values.contains(actual);
            case "NE" -> !values.contains(actual);
            case "GT" -> values.stream().anyMatch(v -> actual > v);
            case "GTE" -> values.stream().anyMatch(v -> actual >= v);
            case "LT" -> values.stream().anyMatch(v -> actual < v);
            case "LTE" -> values.stream().anyMatch(v -> actual <= v);
            default -> false;
        };
    }

    static String extension(String key) {
        String name = key.substring(key.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    // --- ListClassificationJobs ---

    static void validateListRequest(JsonNode request) {
        JsonNode filter = request.get("filterCriteria");
        if (filter != null && !filter.isNull()) {
            if (!filter.isObject()) {
                throw validation("filterCriteria must be an object.");
            }
            for (String mode : List.of("includes", "excludes")) {
                JsonNode terms = filter.get(mode);
                if (terms == null || terms.isNull()) {
                    continue;
                }
                if (!terms.isArray()) {
                    throw validation("filterCriteria." + mode + " must be an array.");
                }
                for (JsonNode term : terms) {
                    String key = text(term, "key", true);
                    String comparator = text(term, "comparator", true);
                    List<String> allowed = switch (key) {
                        case "jobType", "jobStatus" -> List.of("EQ", "NE");
                        case "name" -> List.of("EQ", "NE", "CONTAINS", "STARTS_WITH");
                        case "createdAt" -> List.of("EQ", "NE", "GT", "GTE", "LT", "LTE");
                        default -> throw validation("Unsupported filter key: " + key);
                    };
                    if (!allowed.contains(comparator)) {
                        throw validation("Comparator " + comparator + " isn't valid for " + key + ".");
                    }
                    List<String> values = strings(term.path("values"), "values");
                    if (values.isEmpty()) {
                        throw validation("Filter values cannot be empty.");
                    }
                    if ("createdAt".equals(key)) {
                        for (String value : values) {
                            try {
                                Instant.parse(value);
                            } catch (DateTimeParseException e) {
                                throw validation("createdAt filter values must be ISO 8601 timestamps.");
                            }
                        }
                    }
                }
            }
        }
        JsonNode sort = request.get("sortCriteria");
        if (sort != null && !sort.isNull()) {
            String attribute = text(sort, "attributeName", false);
            if (attribute != null && !List.of("createdAt", "jobStatus", "name", "jobType").contains(attribute)) {
                throw validation("sortCriteria.attributeName must be createdAt, jobStatus, name, or jobType.");
            }
            String order = text(sort, "orderBy", false);
            if (order != null && !"ASC".equals(order) && !"DESC".equals(order)) {
                throw validation("sortCriteria.orderBy must be ASC or DESC.");
            }
        }
    }

    static boolean listed(JsonNode job, JsonNode filter) {
        if (filter == null || filter.isNull()) {
            return true;
        }
        JsonNode includes = filter.get("includes");
        if (includes != null) {
            for (JsonNode term : includes) {
                if (!filterTermMatches(job, term)) {
                    return false;
                }
            }
        }
        JsonNode excludes = filter.get("excludes");
        if (excludes != null) {
            for (JsonNode term : excludes) {
                if (filterTermMatches(job, term)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean filterTermMatches(JsonNode job, JsonNode term) {
        String key = term.path("key").asText();
        String comparator = term.path("comparator").asText();
        List<String> values = strings(term.path("values"), "values");
        if ("createdAt".equals(key)) {
            return compareNumbers(comparator, values.stream().map(v -> Instant.parse(v).toEpochMilli()).toList(),
                    Instant.parse(job.path("createdAt").asText()).toEpochMilli());
        }
        return compareStrings(comparator, values, job.path(key).asText());
    }

    static Comparator<JsonNode> listOrder(JsonNode sort) {
        String attribute = sort == null ? null : sort.path("attributeName").asText(null);
        String order = sort == null ? null : sort.path("orderBy").asText(null);
        Comparator<JsonNode> comparator;
        if (attribute == null || "createdAt".equals(attribute)) {
            comparator = Comparator.comparing((JsonNode job) -> Instant.parse(job.path("createdAt").asText()));
        } else {
            comparator = Comparator.comparing((JsonNode job) -> job.path(attribute).asText());
        }
        comparator = comparator.thenComparing((JsonNode job) -> job.path("jobId").asText());
        return "DESC".equals(order) ? comparator.reversed() : comparator;
    }

    static ObjectNode summary(JsonNode job) {
        ObjectNode summary = object();
        JsonNode definition = job.path("s3JobDefinition");
        if (definition.has("bucketDefinitions")) {
            summary.set("bucketDefinitions", definition.get("bucketDefinitions").deepCopy());
        }
        if (definition.has("bucketCriteria")) {
            summary.set("bucketCriteria", definition.get("bucketCriteria").deepCopy());
        }
        for (String field : List.of("createdAt", "jobId", "jobStatus", "jobType", "name")) {
            summary.put(field, job.path(field).asText());
        }
        for (String field : List.of("lastRunErrorStatus", "userPausedDetails")) {
            if (job.has(field)) {
                summary.set(field, job.get(field).deepCopy());
            }
        }
        return summary;
    }

    // --- Object content and findings ---

    /** Decodes object bytes as UTF-8 text, or returns null for binary content Floci does not parse. */
    static String decodeText(byte[] data) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        if (text.indexOf('\u0000') >= 0) {
            return null;
        }
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /**
     * Builds a SensitiveData finding for one object, or returns null when nothing reportable was
     * detected. Custom identifier detections below their lowest severity threshold are dropped.
     */
    static ObjectNode finding(String id, String region, String accountId, JsonNode job, Candidate candidate,
                              S3Object object, MacieDataClassifier.Evaluation evaluation,
                              Map<String, JsonNode> customSeverityLevels, Instant now) {
        Map<String, List<Detection>> managed = new TreeMap<>();
        List<Detection> custom = new ArrayList<>();
        int score = 0;
        boolean additional = false;
        for (Detection detection : evaluation.detections()) {
            String severity;
            if (detection.rule().custom()) {
                severity = customSeverity(customSeverityLevels.get(detection.rule().id()), detection.count());
                if (severity == null) {
                    continue;
                }
                custom.add(detection);
            } else {
                severity = MacieDataClassifier.managedSeverity(detection.rule().id(), detection.count());
                managed.computeIfAbsent(detection.rule().category(), ignored -> new ArrayList<>()).add(detection);
            }
            score = Math.max(score, score(severity));
            additional |= detection.count() > detection.occurrences().size();
        }
        if (managed.isEmpty() && custom.isEmpty()) {
            return null;
        }
        Set<String> categories = new TreeSet<>(managed.keySet());
        if (!custom.isEmpty()) {
            categories.add("CUSTOM_IDENTIFIER");
        }
        String type = categories.size() > 1 ? "SensitiveData:S3Object/Multiple"
                : switch (categories.iterator().next()) {
                    case MacieDataClassifier.FINANCIAL -> "SensitiveData:S3Object/Financial";
                    case MacieDataClassifier.CREDENTIALS -> "SensitiveData:S3Object/Credentials";
                    case MacieDataClassifier.PERSONAL -> "SensitiveData:S3Object/Personal";
                    default -> "SensitiveData:S3Object/CustomIdentifier";
                };
        String title = switch (type) {
            case "SensitiveData:S3Object/Financial" -> "The S3 object contains financial information.";
            case "SensitiveData:S3Object/Credentials" -> "The S3 object contains credentials data.";
            case "SensitiveData:S3Object/Personal" -> "The S3 object contains personal information.";
            case "SensitiveData:S3Object/CustomIdentifier" ->
                    "The S3 object contains content that matches the detection criteria of a custom data identifier.";
            default -> "The S3 object contains multiple categories of sensitive data.";
        };

        ObjectNode result = object();
        result.put("additionalOccurrences", additional);
        ArrayNode sensitiveData = result.putArray("sensitiveData");
        long total = 0;
        List<String> identifiers = new ArrayList<>();
        for (Map.Entry<String, List<Detection>> entry : managed.entrySet()) {
            ObjectNode item = sensitiveData.addObject();
            item.put("category", entry.getKey());
            ArrayNode detections = item.putArray("detections");
            long categoryTotal = 0;
            for (Detection detection : entry.getValue()) {
                detections.addObject().put("type", detection.rule().id()).put("count", detection.count())
                        .set("occurrences", occurrences(detection));
                categoryTotal += detection.count();
                identifiers.add(detection.rule().id());
            }
            item.put("totalCount", categoryTotal);
            total += categoryTotal;
        }
        ObjectNode customNode = result.putObject("customDataIdentifiers");
        ArrayNode customDetections = customNode.putArray("detections");
        long customTotal = 0;
        for (Detection detection : custom) {
            customDetections.addObject().put("arn", detection.rule().arn()).put("name", detection.rule().name())
                    .put("count", detection.count()).set("occurrences", occurrences(detection));
            customTotal += detection.count();
            identifiers.add(detection.rule().name());
        }
        customNode.put("totalCount", customTotal);
        total += customTotal;
        result.put("mimeType", mimeType(object.getContentType()));
        result.put("sizeClassified", candidate.size());
        result.set("status", object().put("code", evaluation.partial() ? "PARTIAL" : "COMPLETE"));

        ObjectNode classification = object();
        classification.put("jobArn", job.path("jobArn").asText());
        classification.put("jobId", job.path("jobId").asText());
        classification.put("originType", "SENSITIVE_DATA_DISCOVERY_JOB");
        classification.set("result", result);

        String severity = score >= 3 ? "High" : score == 2 ? "Medium" : "Low";
        ObjectNode finding = object();
        finding.put("id", id);
        finding.put("accountId", accountId);
        finding.put("region", region);
        finding.put("partition", "aws");
        finding.put("schemaVersion", "1.0");
        finding.put("type", type);
        finding.put("category", "CLASSIFICATION");
        finding.put("title", title);
        finding.put("description", "The object contains " + total + " occurrence" + (total == 1 ? "" : "s")
                + " of sensitive data detected by: " + String.join(", ", identifiers) + ".");
        finding.set("severity", object().put("description", severity).put("score", score));
        finding.put("createdAt", now.toString());
        finding.put("updatedAt", now.toString());
        finding.put("count", 1);
        finding.put("archived", false);
        finding.put("sample", false);
        finding.set("classificationDetails", classification);
        finding.set("resourcesAffected", resourcesAffected(candidate, object));
        return finding;
    }

    private static ObjectNode resourcesAffected(Candidate candidate, S3Object object) {
        Bucket bucket = candidate.target().bucket();
        String bucketArn = "arn:aws:s3:::" + bucket.getName();
        ObjectNode affected = object();
        ObjectNode s3Bucket = affected.putObject("s3Bucket");
        s3Bucket.put("arn", bucketArn);
        s3Bucket.put("name", bucket.getName());
        if (bucket.getCreationDate() != null) {
            s3Bucket.put("createdAt", bucket.getCreationDate().toString());
        }
        s3Bucket.putObject("owner").put("id", candidate.target().accountId());
        s3Bucket.set("tags", keyValuePairs(bucket.getTags()));

        ObjectNode s3Object = affected.putObject("s3Object");
        s3Object.put("bucketArn", bucketArn);
        s3Object.put("key", candidate.key());
        s3Object.put("path", bucket.getName() + "/" + candidate.key());
        s3Object.put("extension", extension(candidate.key()));
        if (object.getETag() != null) {
            s3Object.put("eTag", object.getETag().replace("\"", ""));
        }
        if (object.getLastModified() != null) {
            s3Object.put("lastModified", object.getLastModified().toString());
        }
        s3Object.put("size", candidate.size());
        s3Object.put("storageClass", object.getStorageClass() == null ? "STANDARD" : object.getStorageClass());
        if (object.getVersionId() != null) {
            s3Object.put("versionId", object.getVersionId());
        }
        ObjectNode encryption = s3Object.putObject("serverSideEncryption");
        encryption.put("encryptionType", object.getServerSideEncryption() == null
                ? "NONE" : object.getServerSideEncryption());
        if (object.getSseKmsKeyId() != null) {
            encryption.put("kmsMasterKeyId", object.getSseKmsKeyId());
        }
        s3Object.set("tags", keyValuePairs(object.getTags()));
        return affected;
    }

    private static ArrayNode keyValuePairs(Map<String, String> tags) {
        ArrayNode pairs = object().arrayNode();
        if (tags != null) {
            new TreeMap<>(tags).forEach((key, value) -> pairs.addObject().put("key", key).put("value", value));
        }
        return pairs;
    }

    private static ObjectNode occurrences(Detection detection) {
        ObjectNode occurrences = object();
        ArrayNode lines = occurrences.putArray("lineRanges");
        for (Occurrence occurrence : detection.occurrences()) {
            lines.addObject().put("start", occurrence.line()).put("end", occurrence.line())
                    .put("startColumn", occurrence.startColumn());
        }
        return occurrences;
    }

    private static String mimeType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "text/plain";
        }
        String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return type.isEmpty() || "application/octet-stream".equals(type) ? "text/plain" : type;
    }

    /** Severity for a custom identifier count; null below the lowest threshold. Macie defaults to MEDIUM at 1. */
    static String customSeverity(JsonNode levels, long count) {
        if (levels == null || !levels.isArray() || levels.isEmpty()) {
            return "Medium";
        }
        String severity = null;
        long best = 0;
        for (JsonNode level : levels) {
            long threshold = level.path("occurrencesThreshold").asLong();
            if (count >= threshold && threshold >= best) {
                best = threshold;
                String value = level.path("severity").asText().toLowerCase(Locale.ROOT);
                severity = Character.toUpperCase(value.charAt(0)) + value.substring(1);
            }
        }
        return severity;
    }

    private static int score(String severity) {
        return switch (severity) {
            case "High" -> 3;
            case "Medium" -> 2;
            default -> 1;
        };
    }
}
