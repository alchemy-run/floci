package io.github.hectorvent.floci.services.inspector2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.inspector2.model.CisScanConfiguration;
import io.github.hectorvent.floci.services.inspector2.model.CisTargets;
import io.github.hectorvent.floci.services.inspector2.model.InspectorFilter;
import io.github.hectorvent.floci.services.inspector2.model.InspectorState;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

@ApplicationScoped
public class Inspector2Service implements Resettable {
    private static final Set<String> RESOURCE_TYPES = Set.of(
            "EC2", "ECR", "LAMBDA", "LAMBDA_CODE", "CODE_REPOSITORY");
    private static final Set<String> ENCRYPTION_RESOURCE_TYPES = Set.of(
            "AWS_EC2_INSTANCE", "AWS_ECR_CONTAINER_IMAGE", "AWS_ECR_REPOSITORY", "AWS_LAMBDA_FUNCTION",
            "CODE_REPOSITORY", "Microsoft.Compute/virtualMachines",
            "Microsoft.ContainerRegistry/registry/containerImage", "Microsoft.Web/sites");
    private static final Map<String, List<String>> PERMISSION_OPERATIONS = permissionOperations();
    private static final Set<String> CIS_SECURITY_LEVELS = Set.of("LEVEL_1", "LEVEL_2");
    private static final Set<String> DAYS = Set.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");
    private static final String TIME_OF_DAY = "^([0-1]?[0-9]|2[0-3]):([0-5][0-9])$";
    /** Amazon Inspector's free trial lasts 15 days from activation of each scan type. */
    private static final double FREE_TRIAL_SECONDS = 15 * 24 * 60 * 60;

    private static Map<String, List<String>> permissionOperations() {
        Map<String, List<String>> operations = new LinkedHashMap<>();
        operations.put("EC2", List.of("ENABLE_SCANNING", "DISABLE_SCANNING"));
        operations.put("ECR", List.of("ENABLE_SCANNING", "DISABLE_SCANNING", "ENABLE_REPOSITORY", "DISABLE_REPOSITORY"));
        operations.put("LAMBDA", List.of("ENABLE_SCANNING", "DISABLE_SCANNING"));
        return java.util.Collections.unmodifiableMap(operations);
    }

    private final AccountAwareStorageBackend<InspectorState> states;
    private final OrganizationsService organizationsService;

    @Inject
    public Inspector2Service(StorageFactory storageFactory, OrganizationsService organizationsService) {
        this(storageFactory.create("inspector2", "inspector2-state.json",
                new TypeReference<Map<String, InspectorState>>() {}), organizationsService);
    }

    Inspector2Service(AccountAwareStorageBackend<InspectorState> states, OrganizationsService organizationsService) {
        this.states = states;
        this.organizationsService = organizationsService;
    }

    public InspectorState state(String region) {
        return states.get(region).orElseGet(InspectorState::new);
    }

    public synchronized String createFilter(String region, String accountId, JsonNode request) {
        requireAccountId(accountId);
        InspectorState state = stateForAccount(accountId, region);
        InspectorFilter filter = new InspectorFilter();
        applyFilterProperties(filter, request, true);
        requireUniqueFilterName(state, filter.getName(), null);
        filter.setTags(readTags(request.get("tags")));
        String arn = AwsArnUtils.Arn.of("inspector2", region, accountId,
                "owner/" + accountId + "/filter/" + UUID.randomUUID()).toString();
        filter.setArn(arn);
        filter.setOwnerId(accountId);
        double now = timestamp();
        filter.setCreatedAt(now);
        filter.setUpdatedAt(now);
        state.getFilters().put(arn, filter);
        states.putForAccount(accountId, region, state);
        return arn;
    }

    public synchronized String updateFilter(String region, String accountId, JsonNode request) {
        String arn = text(request, "filterArn", 1, 128, true);
        InspectorState state = stateForAccount(accountId, region);
        InspectorFilter filter = requireFilter(state, arn).copy();
        applyFilterProperties(filter, request, false);
        requireUniqueFilterName(state, filter.getName(), arn);
        filter.setUpdatedAt(timestamp());
        state.getFilters().put(arn, filter);
        states.putForAccount(accountId, region, state);
        return arn;
    }

    public synchronized String deleteFilter(String region, String accountId, JsonNode request) {
        String arn = text(request, "arn", 1, 128, true);
        InspectorState state = stateForAccount(accountId, region);
        requireFilter(state, arn);
        state.getFilters().remove(arn);
        states.putForAccount(accountId, region, state);
        return arn;
    }

    public synchronized PaginatedResult<InspectorFilter> listFilters(
            String region, String accountId, JsonNode request) {
        requireObject(request);
        String action = request.has("action") ? filterAction(request) : null;
        JsonNode arns = request.get("arns");
        if (arns != null && !arns.isArray()) {
            throw validation("arns must be a list.");
        }
        List<String> requestedArns = new ArrayList<>();
        if (arns != null) {
            for (JsonNode arn : arns) {
                if (!arn.isTextual() || arn.textValue().isEmpty() || arn.textValue().length() > 128) {
                    throw validation("arns must contain filter ARNs.");
                }
                requestedArns.add(arn.textValue());
            }
        }
        Integer maxResults = null;
        if (request.has("maxResults")) {
            JsonNode value = request.get("maxResults");
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw validation("maxResults must be an integer.");
            }
            maxResults = value.intValue();
        }
        String token = text(request, "nextToken", 1, 1000000, false);
        List<InspectorFilter> filters = stateForAccount(accountId, region).getFilters().values().stream()
                .filter(filter -> arns == null || requestedArns.contains(filter.getArn()))
                .filter(filter -> action == null || action.equals(filter.getAction()))
                .map(InspectorFilter::copy)
                .toList();
        return Pagination.paginate(filters, InspectorFilter::getArn, maxResults, token, 100,
                "ValidationException");
    }

    public synchronized Map<String, String> listTags(String region, String accountId, String arn) {
        if (isCisScanConfigurationArn(arn)) {
            return new LinkedHashMap<>(requireCisScanConfiguration(stateForAccount(accountId, region), arn).getTags());
        }
        return listFilterTags(region, accountId, arn);
    }

    public synchronized void tagResource(String region, String accountId, String arn, Map<String, String> tags) {
        if (!isCisScanConfigurationArn(arn)) {
            tagFilter(region, accountId, arn, tags);
            return;
        }
        InspectorState state = stateForAccount(accountId, region);
        CisScanConfiguration configuration = requireCisScanConfiguration(state, arn).copy();
        validateTags(tags);
        configuration.getTags().putAll(tags);
        validateTags(configuration.getTags());
        state.getCisScanConfigurations().put(arn, configuration);
        states.putForAccount(accountId, region, state);
    }

    public synchronized void untagResource(String region, String accountId, String arn, List<String> keys) {
        if (!isCisScanConfigurationArn(arn)) {
            untagFilter(region, accountId, arn, keys);
            return;
        }
        InspectorState state = stateForAccount(accountId, region);
        CisScanConfiguration configuration = requireCisScanConfiguration(state, arn).copy();
        for (String key : keys) {
            validateTagKey(key);
        }
        keys.forEach(configuration.getTags()::remove);
        state.getCisScanConfigurations().put(arn, configuration);
        states.putForAccount(accountId, region, state);
    }

    public synchronized Map<String, String> listFilterTags(String region, String accountId, String arn) {
        return new LinkedHashMap<>(requireFilter(stateForAccount(accountId, region), arn).getTags());
    }

    public synchronized void tagFilter(String region, String accountId, String arn, Map<String, String> tags) {
        InspectorState state = stateForAccount(accountId, region);
        InspectorFilter filter = requireFilter(state, arn).copy();
        validateTags(tags);
        filter.getTags().putAll(tags);
        validateTags(filter.getTags());
        state.getFilters().put(arn, filter);
        states.putForAccount(accountId, region, state);
    }

    public synchronized void untagFilter(String region, String accountId, String arn, List<String> keys) {
        InspectorState state = stateForAccount(accountId, region);
        InspectorFilter filter = requireFilter(state, arn).copy();
        for (String key : keys) {
            validateTagKey(key);
        }
        keys.forEach(filter.getTags()::remove);
        state.getFilters().put(arn, filter);
        states.putForAccount(accountId, region, state);
    }

    public synchronized Map<String, Object> getEc2DeepInspectionConfiguration(String region, String accountId) {
        InspectorState state = stateForAccount(accountId, region);
        if (!"ENABLED".equals(state.getEc2Status())) {
            throw accessDenied("EC2 scanning is not enabled for the invoking account.");
        }
        return Map.of("status", state.getDeepInspectionStatus(),
                "packagePaths", List.of(), "orgPackagePaths", List.of());
    }

    public synchronized String createCisScanConfiguration(String region, String accountId, JsonNode request) {
        requireObject(request);
        CisScanConfiguration configuration = new CisScanConfiguration();
        configuration.setScanName(text(request, "scanName", 1, 128, true));
        configuration.setSecurityLevel(cisSecurityLevel(request));
        configuration.setSchedule(cisSchedule(request.get("schedule")));
        configuration.setTargets(cisTargets(region, accountId, request.get("targets"), null));
        configuration.setTags(readTags(request.get("tags")));
        InspectorState state = requireEnabledAccount(accountId, region);
        String arn = AwsArnUtils.Arn.of("inspector2", region, accountId,
                "owner/" + accountId + "/cis-configuration/" + UUID.randomUUID()).toString();
        configuration.setScanConfigurationArn(arn);
        configuration.setOwnerId(accountId);
        state.getCisScanConfigurations().put(arn, configuration);
        states.putForAccount(accountId, region, state);
        return arn;
    }

    public synchronized String updateCisScanConfiguration(String region, String accountId, JsonNode request) {
        requireObject(request);
        String arn = text(request, "scanConfigurationArn", 1, 2048, true);
        InspectorState state = requireEnabledAccount(accountId, region);
        CisScanConfiguration configuration = requireCisScanConfiguration(state, arn).copy();
        if (request.hasNonNull("scanName")) {
            configuration.setScanName(text(request, "scanName", 1, 128, true));
        }
        if (request.hasNonNull("securityLevel")) {
            configuration.setSecurityLevel(cisSecurityLevel(request));
        }
        if (request.hasNonNull("schedule")) {
            configuration.setSchedule(cisSchedule(request.get("schedule")));
        }
        if (request.hasNonNull("targets")) {
            configuration.setTargets(cisTargets(region, accountId, request.get("targets"),
                    configuration.getTargets()));
        }
        state.getCisScanConfigurations().put(arn, configuration);
        states.putForAccount(accountId, region, state);
        return arn;
    }

    public synchronized String deleteCisScanConfiguration(String region, String accountId, JsonNode request) {
        requireObject(request);
        String arn = text(request, "scanConfigurationArn", 1, 2048, true);
        InspectorState state = requireEnabledAccount(accountId, region);
        requireCisScanConfiguration(state, arn);
        state.getCisScanConfigurations().remove(arn);
        states.putForAccount(accountId, region, state);
        return arn;
    }

    public synchronized Map<String, Object> listCisScanConfigurations(
            String region, String accountId, JsonNode request) {
        requireObject(request);
        optionalObject(request, "filterCriteria");
        JsonNode criteria = request.path("filterCriteria");
        List<JsonNode> nameFilters = cisStringFilters(criteria, "scanNameFilters");
        List<JsonNode> arnFilters = cisStringFilters(criteria, "scanConfigurationArnFilters");
        List<JsonNode> tagFilters = cisTagFilters(criteria);
        optionalEnum(request, "sortBy", Set.of("SCAN_NAME", "SCAN_CONFIGURATION_ARN"));
        optionalEnum(request, "sortOrder", Set.of("ASC", "DESC"));
        Integer maxResults = maxResults(request, 100);
        String token = text(request, "nextToken", 1, 1000000, false);
        InspectorState state = requireEnabledAccount(accountId, region);

        Comparator<CisScanConfiguration> byArn = Comparator.comparing(CisScanConfiguration::getScanConfigurationArn);
        Comparator<CisScanConfiguration> order = "SCAN_CONFIGURATION_ARN".equals(request.path("sortBy").asText(null))
                ? byArn
                : Comparator.comparing(CisScanConfiguration::getScanName).thenComparing(byArn);
        if ("DESC".equals(request.path("sortOrder").asText(null))) {
            order = order.reversed();
        }
        List<CisScanConfiguration> matches = state.getCisScanConfigurations().values().stream()
                .filter(configuration -> matchesCisStringFilters(configuration.getScanName(), nameFilters))
                .filter(configuration -> matchesCisStringFilters(configuration.getScanConfigurationArn(), arnFilters))
                .filter(configuration -> matchesTargetResourceTagFilters(configuration, tagFilters))
                .sorted(order)
                .map(CisScanConfiguration::copy)
                .toList();
        // The cursor is the position in the requested sort order, which may be descending.
        List<Integer> positions = IntStream.range(0, matches.size()).boxed().toList();
        PaginatedResult<Integer> page = Pagination.paginate(positions,
                position -> String.format(Locale.ROOT, "%010d", position), maxResults, token, 100,
                "ValidationException");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scanConfigurations", page.items().stream().map(matches::get).toList());
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return response;
    }

    private InspectorState requireEnabledAccount(String accountId, String region) {
        InspectorState state = stateForAccount(accountId, region);
        if (!"ENABLED".equals(state.getStatus())) {
            throw accessDenied("Invoking account is not enabled.");
        }
        return state;
    }

    private static CisScanConfiguration requireCisScanConfiguration(InspectorState state, String arn) {
        CisScanConfiguration configuration = state.getCisScanConfigurations().get(arn);
        if (configuration == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified CIS scan configuration was not found.", 404);
        }
        return configuration;
    }

    private static boolean isCisScanConfigurationArn(String arn) {
        return arn != null && arn.contains(":owner/") && arn.contains("/cis-configuration/");
    }

    private static String cisSecurityLevel(JsonNode request) {
        String level = text(request, "securityLevel", 1, 16, true);
        if (!CIS_SECURITY_LEVELS.contains(level)) {
            throw validation("securityLevel must be LEVEL_1 or LEVEL_2.");
        }
        return level;
    }

    /** Validates the Schedule union and returns it in its canonical wire shape. */
    private static JsonNode cisSchedule(JsonNode schedule) {
        if (schedule == null || !schedule.isObject()) {
            throw validation("schedule is required.");
        }
        List<String> members = new ArrayList<>();
        schedule.fieldNames().forEachRemaining(name -> {
            if (!schedule.get(name).isNull()) {
                members.add(name);
            }
        });
        if (members.size() != 1) {
            throw validation("schedule must specify exactly one of oneTime, daily, weekly, or monthly.");
        }
        String member = members.getFirst();
        JsonNode value = schedule.get(member);
        if (!value.isObject()) {
            throw validation("schedule." + member + " must be an object.");
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        switch (member) {
            case "oneTime" -> result.putObject("oneTime");
            case "daily" -> result.putObject("daily")
                    .set("startTime", cisTime(value.get("startTime"), "schedule.daily.startTime"));
            case "weekly" -> {
                ObjectNode weekly = result.putObject("weekly");
                weekly.set("startTime", cisTime(value.get("startTime"), "schedule.weekly.startTime"));
                weekly.set("days", cisDays(value.get("days")));
            }
            case "monthly" -> {
                ObjectNode monthly = result.putObject("monthly");
                monthly.set("startTime", cisTime(value.get("startTime"), "schedule.monthly.startTime"));
                monthly.put("day", cisDay(value.get("day"), "schedule.monthly.day"));
            }
            default -> throw validation("schedule must specify exactly one of oneTime, daily, weekly, or monthly.");
        }
        return result;
    }

    private static ObjectNode cisTime(JsonNode time, String field) {
        if (time == null || !time.isObject()) {
            throw validation(field + " is required.");
        }
        String timeOfDay = text(time, "timeOfDay", 1, 5, true);
        if (!timeOfDay.matches(TIME_OF_DAY)) {
            throw validation(field + ".timeOfDay must be a time of day in HH:MM format.");
        }
        String timezone = text(time, "timezone", 1, 50, true);
        if (!ZoneId.getAvailableZoneIds().contains(timezone)) {
            throw validation(field + ".timezone must be a valid time zone.");
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("timeOfDay", timeOfDay);
        result.put("timezone", timezone);
        return result;
    }

    private static ArrayNode cisDays(JsonNode days) {
        if (days == null || !days.isArray() || days.isEmpty() || days.size() > 7) {
            throw validation("schedule.weekly.days must contain between 1 and 7 days.");
        }
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode day : days) {
            String value = cisDay(day, "schedule.weekly.days");
            if (!seen.add(value)) {
                throw validation("schedule.weekly.days must not contain duplicates.");
            }
            result.add(value);
        }
        return result;
    }

    private static String cisDay(JsonNode day, String field) {
        if (day == null || !day.isTextual() || !DAYS.contains(day.textValue())) {
            throw validation(field + " must be one of SUN, MON, TUE, WED, THU, FRI, or SAT.");
        }
        return day.textValue();
    }

    /** Validates CIS targets; on update, members that are omitted keep their current value. */
    private CisTargets cisTargets(String region, String callerAccountId, JsonNode targets, CisTargets current) {
        if (targets == null || !targets.isObject()) {
            throw validation("targets must be an object.");
        }
        List<String> accountIds = current == null ? null : current.getAccountIds();
        JsonNode accountsNode = targets.get("accountIds");
        if (accountsNode != null && !accountsNode.isNull()) {
            accountIds = cisAccountIds(region, callerAccountId, accountsNode);
        } else if (current == null) {
            throw validation("targets.accountIds is required.");
        }
        Map<String, List<String>> resourceTags = current == null ? null : current.getTargetResourceTags();
        JsonNode tagsNode = targets.get("targetResourceTags");
        if (tagsNode != null && !tagsNode.isNull()) {
            resourceTags = cisTargetResourceTags(tagsNode);
        } else if (current == null) {
            throw validation("targets.targetResourceTags is required.");
        }
        return new CisTargets(accountIds, resourceTags);
    }

    private List<String> cisAccountIds(String region, String callerAccountId, JsonNode accountIds) {
        if (!accountIds.isArray() || accountIds.isEmpty() || accountIds.size() > 10000) {
            throw validation("targets.accountIds must contain between 1 and 10000 entries.");
        }
        List<String> result = new ArrayList<>(accountIds.size());
        for (JsonNode node : accountIds) {
            String accountId = node.isTextual() ? node.textValue() : null;
            if (!"SELF".equals(accountId)) {
                requireAccountId(accountId);
                authorizeAccountAccess(region, callerAccountId, accountId);
            }
            result.add(accountId);
        }
        return result;
    }

    private static Map<String, List<String>> cisTargetResourceTags(JsonNode tags) {
        if (!tags.isObject() || tags.isEmpty() || tags.size() > 5) {
            throw validation("targets.targetResourceTags must contain between 1 and 5 tag keys.");
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        tags.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (key.isEmpty() || key.length() > 128) {
                throw validation("targets.targetResourceTags keys must contain 1 to 128 characters.");
            }
            JsonNode values = entry.getValue();
            if (!values.isArray() || values.isEmpty() || values.size() > 5) {
                throw validation("targets.targetResourceTags values must contain between 1 and 5 entries.");
            }
            List<String> list = new ArrayList<>(values.size());
            for (JsonNode value : values) {
                if (!value.isTextual() || value.textValue().isEmpty() || value.textValue().length() > 256) {
                    throw validation("targets.targetResourceTags values must contain 1 to 256 characters.");
                }
                list.add(value.textValue());
            }
            result.put(key, list);
        });
        return result;
    }

    private static List<JsonNode> cisStringFilters(JsonNode criteria, String field) {
        JsonNode filters = criteria.get(field);
        if (filters == null || filters.isNull()) {
            return List.of();
        }
        if (!filters.isArray() || filters.isEmpty() || filters.size() > 10) {
            throw validation("filterCriteria." + field + " must contain between 1 and 10 filters.");
        }
        List<JsonNode> result = new ArrayList<>(filters.size());
        for (JsonNode filter : filters) {
            if (!filter.isObject()
                    || !Set.of("EQUALS", "PREFIX", "NOT_EQUALS").contains(filter.path("comparison").asText(""))
                    || !filter.path("value").isTextual() || filter.path("value").textValue().isEmpty()
                    || filter.path("value").textValue().length() > 1024) {
                throw validation("filterCriteria." + field + " contains an invalid filter.");
            }
            result.add(filter);
        }
        return result;
    }

    private static List<JsonNode> cisTagFilters(JsonNode criteria) {
        JsonNode filters = criteria.get("targetResourceTagFilters");
        if (filters == null || filters.isNull()) {
            return List.of();
        }
        if (!filters.isArray() || filters.isEmpty() || filters.size() > 10) {
            throw validation("filterCriteria.targetResourceTagFilters must contain between 1 and 10 filters.");
        }
        List<JsonNode> result = new ArrayList<>(filters.size());
        for (JsonNode filter : filters) {
            if (!filter.isObject() || !"EQUALS".equals(filter.path("comparison").asText(""))
                    || !filter.path("key").isTextual() || filter.path("key").textValue().isEmpty()
                    || !filter.path("value").isTextual()) {
                throw validation("filterCriteria.targetResourceTagFilters contains an invalid filter.");
            }
            result.add(filter);
        }
        return result;
    }

    /** Positive comparisons are alternatives; NOT_EQUALS comparisons all have to hold. */
    private static boolean matchesCisStringFilters(String value, List<JsonNode> filters) {
        if (filters.isEmpty()) {
            return true;
        }
        boolean hasPositive = false;
        boolean positiveMatch = false;
        for (JsonNode filter : filters) {
            String expected = filter.get("value").textValue();
            switch (filter.get("comparison").textValue()) {
                case "EQUALS" -> {
                    hasPositive = true;
                    positiveMatch |= expected.equals(value);
                }
                case "PREFIX" -> {
                    hasPositive = true;
                    positiveMatch |= value != null && value.startsWith(expected);
                }
                default -> {
                    if (expected.equals(value)) {
                        return false;
                    }
                }
            }
        }
        return !hasPositive || positiveMatch;
    }

    private static boolean matchesTargetResourceTagFilters(CisScanConfiguration configuration, List<JsonNode> filters) {
        if (filters.isEmpty()) {
            return true;
        }
        Map<String, List<String>> tags = configuration.getTargets().getTargetResourceTags();
        for (JsonNode filter : filters) {
            List<String> values = tags.get(filter.get("key").textValue());
            if (values != null && values.contains(filter.get("value").textValue())) {
                return true;
            }
        }
        return false;
    }

    public synchronized Map<String, Object> listFindings(String region, String accountId, JsonNode request) {
        requireObject(request);
        optionalObject(request, "filterCriteria");
        optionalObject(request, "sortCriteria");
        // Floci runs no vulnerability scanner, so no findings are ever produced.
        return page("findings", List.<String>of(), id -> id,
                maxResults(request, 100), text(request, "nextToken", 1, 1000000, false), 100);
    }

    public synchronized Map<String, Object> listCoverage(String region, String accountId, JsonNode request) {
        requireObject(request);
        optionalObject(request, "filterCriteria");
        // No scanner means no resource is ever reported as covered.
        return page("coveredResources", List.<String>of(), id -> id,
                maxResults(request, 200), text(request, "nextToken", 1, 1000000, false), 200);
    }

    public synchronized Map<String, Object> searchVulnerabilities(JsonNode request) {
        requireObject(request);
        JsonNode criteria = request.get("filterCriteria");
        if (criteria == null || !criteria.isObject()) {
            throw validation("filterCriteria is required.");
        }
        JsonNode ids = criteria.get("vulnerabilityIds");
        if (ids == null || !ids.isArray() || ids.size() != 1) {
            throw validation("filterCriteria.vulnerabilityIds must contain exactly 1 vulnerability ID.");
        }
        String id = ids.get(0).isTextual() ? ids.get(0).textValue() : null;
        if (id == null || !id.matches("^CVE-[12][0-9]{3}-[0-9]{1,10}$")) {
            throw validation("filterCriteria.vulnerabilityIds must contain CVE identifiers.");
        }
        List<Map<String, Object>> matches = VulnerabilityCatalog.lookup(id).map(List::of).orElse(List.of());
        return page("vulnerabilities", matches, entry -> (String) entry.get("id"),
                null, text(request, "nextToken", 1, 1000000, false), 100);
    }

    public synchronized Map<String, Object> listUsageTotals(String region, String callerAccountId, JsonNode request) {
        requireObject(request);
        Integer maxResults = maxResults(request, 500);
        String token = text(request, "nextToken", 1, 1000000, false);
        JsonNode accountIds = request.get("accountIds");
        if (accountIds != null && (!accountIds.isArray() || accountIds.isEmpty() || accountIds.size() > 7000)) {
            throw validation("accountIds must contain between 1 and 7000 account IDs.");
        }
        if (accountIds != null) {
            for (JsonNode accountId : accountIds) {
                requireAccountId(accountId.isTextual() ? accountId.textValue() : null);
                authorizeAccountAccess(region, callerAccountId, accountId.textValue());
            }
        }
        // Floci does not meter scans, so there is no usage to total.
        return page("totals", List.<String>of(), id -> id, maxResults, token, 500);
    }

    public synchronized Map<String, Object> listAccountPermissions(
            String region, String callerAccountId, JsonNode request) {
        requireObject(request);
        String service = text(request, "service", 1, 16, false);
        if (service != null && !PERMISSION_OPERATIONS.containsKey(service)) {
            throw validation("service must be one of EC2, ECR, or LAMBDA.");
        }
        Integer maxResults = maxResults(request, 1024);
        String token = text(request, "nextToken", 1, 1000000, false);
        List<Map<String, Object>> permissions = new ArrayList<>();
        if (!isManagedMember(region, callerAccountId)) {
            PERMISSION_OPERATIONS.forEach((permissionService, operations) -> {
                if (service == null || service.equals(permissionService)) {
                    for (String operation : operations) {
                        Map<String, Object> permission = new LinkedHashMap<>();
                        permission.put("service", permissionService);
                        permission.put("operation", operation);
                        permissions.add(permission);
                    }
                }
            });
        }
        return page("permissions", permissions,
                permission -> permission.get("service") + ":" + permission.get("operation"),
                maxResults, token, 1024);
    }

    public synchronized Map<String, Object> batchGetFreeTrialInfo(
            String region, String callerAccountId, JsonNode request) {
        requireObject(request);
        JsonNode accountIds = request.get("accountIds");
        if (accountIds == null || !accountIds.isArray()) {
            throw validation("accountIds is required.");
        }
        List<Map<String, Object>> accounts = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        double now = timestamp();
        for (JsonNode node : accountIds) {
            String accountId = node.isTextual() ? node.textValue() : null;
            requireAccountId(accountId);
            try {
                authorizeAccountAccess(region, callerAccountId, accountId);
            } catch (AwsException e) {
                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("accountId", accountId);
                failure.put("code", "ACCESS_DENIED");
                failure.put("message", e.getMessage());
                failed.add(failure);
                continue;
            }
            List<Map<String, Object>> trials = new ArrayList<>();
            stateForAccount(accountId, region).getFreeTrialStarts().forEach((type, start) -> {
                double end = start + FREE_TRIAL_SECONDS;
                Map<String, Object> trial = new LinkedHashMap<>();
                trial.put("type", type);
                trial.put("start", start);
                trial.put("end", end);
                trial.put("status", now < end ? "ACTIVE" : "INACTIVE");
                trials.add(trial);
            });
            Map<String, Object> account = new LinkedHashMap<>();
            account.put("accountId", accountId);
            account.put("freeTrialInfo", trials);
            accounts.add(account);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accounts", accounts);
        response.put("failedAccounts", failed);
        return response;
    }

    public synchronized Map<String, Object> getConfiguration(String region, String callerAccountId, JsonNode request) {
        requireObject(request);
        String accountId = text(request, "accountId", 12, 12, false);
        if (accountId != null) {
            requireAccountId(accountId);
            authorizeAccountAccess(region, callerAccountId, accountId);
        }
        // UpdateConfiguration is not supported, so no ECR or EC2 scan configuration has been set.
        return Map.of();
    }

    public Map<String, Object> getEncryptionKey(String scanType, String resourceType) {
        if (scanType == null || !Set.of("NETWORK", "PACKAGE", "CODE").contains(scanType)) {
            throw validation("scanType must be one of NETWORK, PACKAGE, or CODE.");
        }
        if (resourceType == null || !ENCRYPTION_RESOURCE_TYPES.contains(resourceType)) {
            throw validation("resourceType is invalid.");
        }
        // UpdateEncryptionKey is not supported, so no customer managed key is ever configured.
        throw new AwsException("ResourceNotFoundException",
                "No customer managed key is configured for the specified scan type and resource type.", 404);
    }

    public synchronized Map<String, Object> listCisScans(String region, String accountId, JsonNode request) {
        requireObject(request);
        optionalObject(request, "filterCriteria");
        optionalEnum(request, "detailLevel", Set.of("ORGANIZATION", "MEMBER"));
        optionalEnum(request, "sortBy", Set.of("STATUS", "SCHEDULED_BY", "SCAN_START_DATE", "FAILED_CHECKS"));
        optionalEnum(request, "sortOrder", Set.of("ASC", "DESC"));
        Integer maxResults = maxResults(request, 100);
        String token = text(request, "nextToken", 1, 1000000, false);
        if (!"ENABLED".equals(stateForAccount(accountId, region).getStatus())) {
            throw accessDenied("Invoking account is not enabled.");
        }
        // Floci does not run CIS benchmark checks against instances, so no scan is ever started.
        return page("scans", List.<String>of(), id -> id, maxResults, token, 100);
    }

    public synchronized Map<String, Object> listMembers(String region, String callerAccountId, JsonNode request) {
        requireObject(request);
        JsonNode onlyAssociatedNode = request.get("onlyAssociated");
        if (onlyAssociatedNode != null && !onlyAssociatedNode.isNull() && !onlyAssociatedNode.isBoolean()) {
            throw validation("onlyAssociated must be a boolean.");
        }
        boolean onlyAssociated = onlyAssociatedNode == null || onlyAssociatedNode.isNull()
                || onlyAssociatedNode.booleanValue();
        Integer maxResults = maxResults(request, 50);
        String token = text(request, "nextToken", 1, 1000000, false);
        List<Map<String, Object>> members = new ArrayList<>();
        String managementAccountId = managementAccountFor(callerAccountId).orElse(null);
        if (managementAccountId != null && callerAccountId.equals(
                stateForAccount(managementAccountId, region).getAdminAccountId())) {
            for (var account : organizationsService.listAccounts(managementAccountId)) {
                if (callerAccountId.equals(account.getId())) {
                    continue;
                }
                boolean associated = !"DISABLED".equals(stateForAccount(account.getId(), region).getStatus());
                if (onlyAssociated && !associated) {
                    continue;
                }
                Map<String, Object> member = new LinkedHashMap<>();
                member.put("accountId", account.getId());
                member.put("relationshipStatus", associated ? "ENABLED" : "CREATED");
                member.put("delegatedAdminAccountId", callerAccountId);
                members.add(member);
            }
        }
        return page("members", members, member -> (String) member.get("accountId"), maxResults, token, 50);
    }

    public synchronized Map<String, Object> getDelegatedAdminAccount(String region, String callerAccountId) {
        String managementAccountId = managementAccountFor(callerAccountId)
                .orElseThrow(() -> accessDenied("The caller is not a member of an AWS Organizations organization."));
        String adminAccountId = stateForAccount(managementAccountId, region).getAdminAccountId();
        if (adminAccountId == null) {
            throw new AwsException("ResourceNotFoundException",
                    "No delegated administrator is configured for the organization.", 404);
        }
        Map<String, Object> delegatedAdmin = new LinkedHashMap<>();
        delegatedAdmin.put("accountId", adminAccountId);
        delegatedAdmin.put("relationshipStatus", "ENABLED");
        return Map.of("delegatedAdmin", delegatedAdmin);
    }

    public Map<String, Object> getFindingsReportStatus(JsonNode request) {
        requireObject(request);
        String reportId = text(request, "reportId", 36, 36, false);
        if (reportId != null && !reportId.matches(
                "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw validation("reportId must be a findings report ID.");
        }
        // CreateFindingsReport is not supported, so no findings report exists.
        throw new AwsException("ResourceNotFoundException", "The specified findings report was not found.", 404);
    }

    /** True for an organization account whose Inspector settings are managed by another account. */
    private boolean isManagedMember(String region, String accountId) {
        return managementAccountFor(accountId)
                .map(managementAccountId -> stateForAccount(managementAccountId, region).getAdminAccountId())
                .filter(adminAccountId -> !adminAccountId.equals(accountId))
                .isPresent();
    }

    private static <T> Map<String, Object> page(String key, List<T> items, java.util.function.Function<T, String> cursor,
                                                Integer maxResults, String token, int pageSize) {
        PaginatedResult<T> page = Pagination.paginate(items, cursor, maxResults, token, pageSize,
                "ValidationException");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(key, page.items());
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return response;
    }

    private static Integer maxResults(JsonNode request, int max) {
        JsonNode value = request.get("maxResults");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > max) {
            throw validation("maxResults must be between 1 and " + max + ".");
        }
        return value.intValue();
    }

    private static void optionalObject(JsonNode request, String name) {
        JsonNode value = request.get(name);
        if (value != null && !value.isNull() && !value.isObject()) {
            throw validation(name + " must be an object.");
        }
    }

    private static void optionalEnum(JsonNode request, String name, Set<String> allowed) {
        String value = text(request, name, 1, 64, false);
        if (value != null && !allowed.contains(value)) {
            throw validation(name + " must be one of " + String.join(", ", new java.util.TreeSet<>(allowed)) + ".");
        }
    }

    private static InspectorFilter requireFilter(InspectorState state, String arn) {
        InspectorFilter filter = state.getFilters().get(arn);
        if (filter == null) {
            throw new AwsException("ResourceNotFoundException", "The specified filter was not found.", 404);
        }
        return filter;
    }

    private static void requireUniqueFilterName(InspectorState state, String name, String arn) {
        boolean duplicate = state.getFilters().values().stream()
                .anyMatch(filter -> name.equals(filter.getName()) && !filter.getArn().equals(arn));
        if (duplicate) {
            throw new AwsException("BadRequestException", "A filter with this name already exists.", 400);
        }
    }

    private static void applyFilterProperties(InspectorFilter filter, JsonNode request, boolean create) {
        requireObject(request);
        if (create || request.has("name")) {
            filter.setName(text(request, "name", 1, 128, true));
        }
        if (create || request.has("action")) {
            filter.setAction(filterAction(request));
        }
        if (create || request.has("filterCriteria")) {
            JsonNode criteria = request.get("filterCriteria");
            if (criteria == null || !criteria.isObject()) {
                throw validation("filterCriteria must be an object.");
            }
            for (JsonNode values : criteria) {
                if (!values.isArray()) {
                    throw validation("filterCriteria members must be lists.");
                }
                for (JsonNode value : values) {
                    if (!value.isObject()) {
                        throw validation("filterCriteria lists must contain objects.");
                    }
                }
            }
            filter.setCriteria(criteria.deepCopy());
        }
        if (request.has("description")) {
            filter.setDescription(text(request, "description", 1, 512, true));
        }
        if (request.has("reason")) {
            filter.setReason(text(request, "reason", 1, 512, true));
        }
    }

    private static String filterAction(JsonNode request) {
        String action = text(request, "action", 1, 8, true);
        if (!Set.of("NONE", "SUPPRESS").contains(action)) {
            throw validation("action must be NONE or SUPPRESS.");
        }
        return action;
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node != null) {
            if (!node.isObject()) {
                throw validation("tags must be a string map.");
            }
            node.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual()) {
                    throw validation("Tag values must be strings.");
                }
                tags.put(entry.getKey(), entry.getValue().textValue());
            });
        }
        validateTags(tags);
        return tags;
    }

    private static void validateTags(Map<String, String> tags) {
        if (tags.size() > 50) {
            throw validation("A resource can have at most 50 tags.");
        }
        tags.forEach((key, value) -> {
            validateTagKey(key);
            if (value == null || value.length() > 256) {
                throw validation("Tag values must contain at most 256 characters.");
            }
        });
    }

    private static void validateTagKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 128 || key.startsWith("aws:")) {
            throw validation("Tag keys must contain 1 to 128 characters and must not start with aws:.");
        }
    }

    private static String text(JsonNode request, String name, int min, int max, boolean required) {
        requireObject(request);
        JsonNode value = request.get(name);
        if (value == null && !required) {
            return null;
        }
        if (value == null || !value.isTextual() || value.textValue().length() < min
                || value.textValue().length() > max) {
            throw validation(name + " must be a string of length " + min + " to " + max + ".");
        }
        return value.textValue();
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw validation("Request must be a JSON object.");
        }
    }

    private static double timestamp() {
        Instant now = Instant.now();
        return now.getEpochSecond() + now.getNano() / 1_000_000_000.0;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public InspectorState delegatedAdminState(String region, String callerAccountId) {
        requireManagementAccount(callerAccountId);
        return stateForAccount(callerAccountId, region);
    }

    public synchronized void enableDelegatedAdmin(String region, String callerAccountId, String accountId) {
        requireAccountId(accountId);
        String managementAccountId = requireManagementAccount(callerAccountId);
        requireSameOrganization(managementAccountId, accountId);

        InspectorState management = stateForAccount(managementAccountId, region);
        if (management.getAdminAccountId() != null && !management.getAdminAccountId().equals(accountId)) {
            throw new AwsException("ConflictException",
                    "A different delegated administrator is already configured.", 409);
        }
        management.setAdminAccountId(accountId);
        states.putForAccount(managementAccountId, region, management);

        InspectorState delegated = stateForAccount(accountId, region);
        delegated.setAdminAccountId(accountId);
        for (String resourceType : RESOURCE_TYPES) {
            delegated.setResourceStatus(resourceType, "ENABLED");
            delegated.getFreeTrialStarts().putIfAbsent(resourceType, timestamp());
        }
        delegated.setStatus("ENABLED");
        delegated.setDeepInspectionStatus("ACTIVATED");
        delegated.setEnablingPollsRemaining(0);
        states.putForAccount(accountId, region, delegated);
    }

    public synchronized void disableDelegatedAdmin(String region, String callerAccountId, String accountId) {
        requireAccountId(accountId);
        String managementAccountId = requireManagementAccount(callerAccountId);
        requireSameOrganization(managementAccountId, accountId);
        InspectorState management = stateForAccount(managementAccountId, region);
        if (!accountId.equals(management.getAdminAccountId())) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified delegated administrator was not found.", 404);
        }
        management.setAdminAccountId(null);
        states.putForAccount(managementAccountId, region, management);
        InspectorState delegated = stateForAccount(accountId, region);
        delegated.setAdminAccountId(null);
        states.putForAccount(accountId, region, delegated);
    }

    /**
     * Reports an account's scan status. Enabling and disabling are asynchronous in AWS: a
     * resource type that was just enabled or disabled is observed once in its transitional
     * state (ENABLING or DISABLING) before it settles.
     */
    public synchronized InspectorState accountStatus(String region, String callerAccountId, String accountId) {
        requireAccountId(accountId);
        authorizeAccountAccess(region, callerAccountId, accountId);
        InspectorState state = stateForAccount(accountId, region);
        boolean enabling = hasResourceStatus(state, "ENABLING");
        boolean disabling = hasResourceStatus(state, "DISABLING");
        if (!enabling && !disabling) {
            return copyState(state);
        }
        if (enabling) {
            if (state.getEnablingPollsRemaining() > 0) {
                state.setEnablingPollsRemaining(state.getEnablingPollsRemaining() - 1);
            } else {
                settle(state, "ENABLING", "ENABLED", "ACTIVATED");
            }
        }
        if (disabling) {
            if (state.getDisablingPollsRemaining() > 0) {
                state.setDisablingPollsRemaining(state.getDisablingPollsRemaining() - 1);
            } else {
                settle(state, "DISABLING", "DISABLED", "DEACTIVATED");
            }
        }
        state.setStatus(overallStatus(state));
        states.putForAccount(accountId, region, state);
        return copyState(state);
    }

    public synchronized StatusChange enable(String region, String callerAccountId, JsonNode request) {
        List<String> resourceTypes = resourceTypes(request);
        List<String> accountIds = accountIds(request, callerAccountId);

        for (String accountId : accountIds) {
            authorizeAccountAccess(region, callerAccountId, accountId);
        }

        Map<String, InspectorState> result = new LinkedHashMap<>();
        List<FailedAccount> failed = new ArrayList<>();
        for (String accountId : accountIds) {
            InspectorState state = stateForAccount(accountId, region);
            boolean disabling = resourceTypes.stream()
                    .anyMatch(resourceType -> "DISABLING".equals(state.resourceStatus(resourceType)));
            if (disabling) {
                failed.add(new FailedAccount(accountId, copyState(state), "DISABLE_IN_PROGRESS",
                        "A requested resource type is still being disabled for this account."));
                continue;
            }
            boolean changed = false;
            for (String resourceType : resourceTypes) {
                String current = state.resourceStatus(resourceType);
                if (!"ENABLED".equals(current) && !"ENABLING".equals(current)) {
                    state.setResourceStatus(resourceType, "ENABLING");
                    state.getFreeTrialStarts().putIfAbsent(resourceType, timestamp());
                    changed = true;
                }
            }
            if (changed) {
                state.setStatus(overallStatus(state));
                state.setEnablingPollsRemaining(1);
                states.putForAccount(accountId, region, state);
            }
            result.put(accountId, copyState(state));
        }
        return new StatusChange(result, failed);
    }

    /**
     * Disables scanning of the requested resource types (all of them when none are named).
     * Types that are still enabling are rejected per account with {@code ENABLE_IN_PROGRESS},
     * and types that are not enabled are left untouched.
     */
    public synchronized StatusChange disable(String region, String callerAccountId, JsonNode request) {
        requireObject(request);
        List<String> resourceTypes = disableResourceTypes(request);
        List<String> accountIds = accountIds(request, callerAccountId);

        for (String accountId : accountIds) {
            authorizeAccountAccess(region, callerAccountId, accountId);
        }

        Map<String, InspectorState> result = new LinkedHashMap<>();
        List<FailedAccount> failed = new ArrayList<>();
        for (String accountId : accountIds) {
            InspectorState state = stateForAccount(accountId, region);
            boolean enabling = resourceTypes.stream()
                    .anyMatch(resourceType -> "ENABLING".equals(state.resourceStatus(resourceType)));
            if (enabling) {
                failed.add(new FailedAccount(accountId, copyState(state), "ENABLE_IN_PROGRESS",
                        "A requested resource type is still being enabled for this account."));
                continue;
            }
            boolean changed = false;
            for (String resourceType : resourceTypes) {
                if ("ENABLED".equals(state.resourceStatus(resourceType))) {
                    state.setResourceStatus(resourceType, "DISABLING");
                    changed = true;
                }
            }
            if (changed) {
                state.setStatus(overallStatus(state));
                state.setDisablingPollsRemaining(1);
                states.putForAccount(accountId, region, state);
            }
            result.put(accountId, copyState(state));
        }
        return new StatusChange(result, failed);
    }

    private static boolean hasResourceStatus(InspectorState state, String status) {
        return RESOURCE_TYPES.stream().anyMatch(type -> status.equals(state.resourceStatus(type)));
    }

    private static void settle(InspectorState state, String from, String to, String deepInspectionStatus) {
        for (String resourceType : RESOURCE_TYPES) {
            if (from.equals(state.resourceStatus(resourceType))) {
                state.setResourceStatus(resourceType, to);
                if ("EC2".equals(resourceType)) {
                    state.setDeepInspectionStatus(deepInspectionStatus);
                }
            }
        }
    }

    private static List<String> disableResourceTypes(JsonNode request) {
        JsonNode resourceTypes = request.get("resourceTypes");
        if (resourceTypes == null || resourceTypes.isNull() || (resourceTypes.isArray() && resourceTypes.isEmpty())) {
            return List.copyOf(RESOURCE_TYPES);
        }
        if (!resourceTypes.isArray() || resourceTypes.size() > 5) {
            throw validation("resourceTypes must contain at most 5 resource types.");
        }
        List<String> result = new ArrayList<>(resourceTypes.size());
        for (JsonNode resourceType : resourceTypes) {
            if (!resourceType.isTextual() || !RESOURCE_TYPES.contains(resourceType.textValue())) {
                throw validation("resourceTypes contains an invalid resource type.");
            }
            if (!result.contains(resourceType.textValue())) {
                result.add(resourceType.textValue());
            }
        }
        return result;
    }

    public synchronized InspectorState updateOrganizationConfiguration(
            String region, String callerAccountId, JsonNode request) {
        InspectorState state = requireAdministrator(region, callerAccountId);
        JsonNode autoEnable = request.get("autoEnable");
        if (autoEnable == null || !autoEnable.isObject()) {
            throw new AwsException("ValidationException", "autoEnable is required.", 400);
        }

        boolean ec2 = requiredBoolean(autoEnable, "ec2");
        boolean ecr = requiredBoolean(autoEnable, "ecr");
        boolean lambda = optionalBoolean(autoEnable, "lambda");
        boolean lambdaCode = optionalBoolean(autoEnable, "lambdaCode");
        boolean codeRepository = optionalBoolean(autoEnable, "codeRepository");

        state.setAutoEnableEc2(ec2);
        state.setAutoEnableEcr(ecr);
        state.setAutoEnableLambda(lambda);
        state.setAutoEnableLambdaCode(lambdaCode);
        state.setAutoEnableCodeRepository(codeRepository);
        states.putForAccount(callerAccountId, region, state);
        return copyState(state);
    }

    public InspectorState organizationConfiguration(String region, String callerAccountId) {
        return copyState(requireAdministrator(region, callerAccountId));
    }

    private InspectorState requireAdministrator(String region, String callerAccountId) {
        String managementAccountId = managementAccountFor(callerAccountId)
                .orElseThrow(() -> new AwsException("AccessDeniedException",
                        "Only the delegated Amazon Inspector administrator can manage organization configuration.", 403));
        InspectorState management = stateForAccount(managementAccountId, region);
        if (management.getAdminAccountId() == null || !callerAccountId.equals(management.getAdminAccountId())) {
            throw new AwsException("AccessDeniedException",
                    "Only the delegated Amazon Inspector administrator can manage organization configuration.", 403);
        }
        return stateForAccount(callerAccountId, region);
    }

    private void authorizeAccountAccess(String region, String callerAccountId, String targetAccountId) {
        if (callerAccountId.equals(targetAccountId)) {
            return;
        }
        String managementAccountId = managementAccountFor(callerAccountId)
                .orElseThrow(() -> accessDenied("The caller cannot manage the requested Amazon Inspector account."));
        requireSameOrganization(managementAccountId, targetAccountId);
        InspectorState management = stateForAccount(managementAccountId, region);
        if (!callerAccountId.equals(management.getAdminAccountId())) {
            throw accessDenied("Only the delegated Amazon Inspector administrator can manage member accounts.");
        }
    }

    private String requireManagementAccount(String callerAccountId) {
        String managementAccountId = managementAccountFor(callerAccountId)
                .orElseThrow(() -> accessDenied("Only the AWS Organizations management account can perform this operation."));
        if (!callerAccountId.equals(managementAccountId)) {
            throw accessDenied("Only the AWS Organizations management account can perform this operation.");
        }
        return managementAccountId;
    }

    private void requireSameOrganization(String managementAccountId, String accountId) {
        String targetManagementAccount = managementAccountFor(accountId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified AWS account is not a member of the organization.", 404));
        if (!managementAccountId.equals(targetManagementAccount)) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified AWS account is not a member of the organization.", 404);
        }
    }

    private java.util.Optional<String> managementAccountFor(String accountId) {
        return organizationsService.findManagementAccountForResource(accountId);
    }

    private InspectorState stateForAccount(String accountId, String region) {
        return states.getForAccount(accountId, region).orElseGet(InspectorState::new);
    }

    private static List<String> resourceTypes(JsonNode request) {
        JsonNode resourceTypes = request.get("resourceTypes");
        if (resourceTypes == null || !resourceTypes.isArray() || resourceTypes.isEmpty() || resourceTypes.size() > 5) {
            throw new AwsException("ValidationException",
                    "resourceTypes must contain between 1 and 5 resource types.", 400);
        }
        List<String> result = new ArrayList<>(resourceTypes.size());
        for (JsonNode resourceType : resourceTypes) {
            if (!resourceType.isTextual() || !RESOURCE_TYPES.contains(resourceType.textValue())) {
                throw new AwsException("ValidationException", "resourceTypes contains an invalid resource type.", 400);
            }
            if (!result.contains(resourceType.textValue())) {
                result.add(resourceType.textValue());
            }
        }
        return result;
    }

    private static List<String> accountIds(JsonNode request, String callerAccountId) {
        JsonNode accountIds = request.get("accountIds");
        if (accountIds != null && (!accountIds.isArray() || accountIds.size() > 100)) {
            throw new AwsException("ValidationException", "accountIds must contain at most 100 account IDs.", 400);
        }
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of(callerAccountId);
        }
        List<String> result = new ArrayList<>(accountIds.size());
        for (JsonNode accountId : accountIds) {
            requireAccountId(accountId.asText(null));
            result.add(accountId.asText());
        }
        return result;
    }

    private static String overallStatus(InspectorState state) {
        if (hasResourceStatus(state, "ENABLING")) {
            return "ENABLING";
        }
        if (hasResourceStatus(state, "ENABLED")) {
            return "ENABLED";
        }
        return hasResourceStatus(state, "DISABLING") ? "DISABLING" : "DISABLED";
    }

    private static InspectorState copyState(InspectorState source) {
        InspectorState copy = new InspectorState();
        copy.setAdminAccountId(source.getAdminAccountId());
        copy.setStatus(source.getStatus());
        copy.setEnablingPollsRemaining(source.getEnablingPollsRemaining());
        copy.setDisablingPollsRemaining(source.getDisablingPollsRemaining());
        copy.setEc2Status(source.getEc2Status());
        copy.setEcrStatus(source.getEcrStatus());
        copy.setLambdaStatus(source.getLambdaStatus());
        copy.setLambdaCodeStatus(source.getLambdaCodeStatus());
        copy.setCodeRepositoryStatus(source.getCodeRepositoryStatus());
        copy.setAutoEnableEc2(source.isAutoEnableEc2());
        copy.setAutoEnableEcr(source.isAutoEnableEcr());
        copy.setAutoEnableLambda(source.isAutoEnableLambda());
        copy.setAutoEnableLambdaCode(source.isAutoEnableLambdaCode());
        copy.setAutoEnableCodeRepository(source.isAutoEnableCodeRepository());
        copy.setDeepInspectionStatus(source.getDeepInspectionStatus());
        source.getFilters().forEach((arn, filter) -> copy.getFilters().put(arn, filter.copy()));
        source.getCisScanConfigurations().forEach((arn, configuration) ->
                copy.getCisScanConfigurations().put(arn, configuration.copy()));
        copy.getFreeTrialStarts().putAll(source.getFreeTrialStarts());
        return copy;
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new AwsException("ValidationException", "autoEnable." + field + " is required.", 400);
        }
        return value.booleanValue();
    }

    private static boolean optionalBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return false;
        }
        if (!value.isBoolean()) {
            throw new AwsException("ValidationException", "autoEnable." + field + " must be a boolean.", 400);
        }
        return value.booleanValue();
    }

    private static AwsException accessDenied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }

    @Override
    public void clear() {
        states.clear();
    }

    static void requireAccountId(String accountId) {
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw new AwsException("ValidationException",
                    "accountId must be a 12 digit AWS account ID.", 400);
        }
    }

    /** An account an Enable or Disable request could not change, with the AWS error code. */
    public record FailedAccount(String accountId, InspectorState state, String errorCode, String errorMessage) {}

    /** The outcome of an Enable or Disable request, per account. */
    public record StatusChange(Map<String, InspectorState> accounts, List<FailedAccount> failedAccounts) {}
}
