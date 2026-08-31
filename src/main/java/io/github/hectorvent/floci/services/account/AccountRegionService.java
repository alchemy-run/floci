package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AWS Account Management region opt-in ({@code GetRegionOptStatus},
 * {@code EnableRegion}, {@code DisableRegion}, {@code ListRegions}).
 *
 * <p>Default-enabled commercial Regions start as {@code ENABLED_BY_DEFAULT} and
 * cannot be disabled. Opt-in Regions start as {@code DISABLED} and flip
 * immediately to {@code ENABLED}/{@code DISABLED} (the emulator skips the
 * multi-minute {@code ENABLING}/{@code DISABLING} transitions).
 */
@ApplicationScoped
public class AccountRegionService {

    static final String ENABLED_BY_DEFAULT = "ENABLED_BY_DEFAULT";
    static final String ENABLED = "ENABLED";
    static final String DISABLED = "DISABLED";
    static final String ENABLING = "ENABLING";
    static final String DISABLING = "DISABLING";

    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    private static final Pattern REGION_NAME = Pattern.compile("[a-z]{2}(-[a-z0-9]+)+-\\d+");
    private static final Set<String> STATUSES = Set.of(
            ENABLED_BY_DEFAULT, ENABLED, DISABLED, ENABLING, DISABLING);
    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int MAX_RESULTS_CAP = 50;

    /**
     * Regions AWS reports as {@code ENABLED_BY_DEFAULT}. These cannot be
     * opted out of.
     */
    static final List<String> DEFAULT_ENABLED_REGIONS = List.of(
            "us-east-1", "us-east-2", "us-west-1", "us-west-2",
            "ap-south-1", "ap-northeast-1", "ap-northeast-2", "ap-northeast-3",
            "ap-southeast-1", "ap-southeast-2",
            "ca-central-1",
            "eu-central-1", "eu-west-1", "eu-west-2", "eu-west-3", "eu-north-1",
            "sa-east-1");

    /**
     * Opt-in Regions. New accounts see these as {@code DISABLED} until
     * {@code EnableRegion} is called.
     */
    static final List<String> OPT_IN_REGIONS = List.of(
            "af-south-1",
            "ap-east-1", "ap-east-2", "ap-south-2",
            "ap-southeast-3", "ap-southeast-4", "ap-southeast-5", "ap-southeast-6",
            "ap-southeast-7",
            "ca-west-1",
            "eu-south-1", "eu-south-2", "eu-central-2",
            "il-central-1",
            "me-south-1", "me-central-1",
            "mx-central-1");

    private static final List<String> ALL_REGIONS;

    static {
        List<String> all = new ArrayList<>(DEFAULT_ENABLED_REGIONS.size() + OPT_IN_REGIONS.size());
        all.addAll(DEFAULT_ENABLED_REGIONS);
        all.addAll(OPT_IN_REGIONS);
        ALL_REGIONS = List.copyOf(all);
    }

    private static final Set<String> DEFAULT_ENABLED_SET = Set.copyOf(DEFAULT_ENABLED_REGIONS);
    private static final Set<String> KNOWN_REGIONS = Set.copyOf(ALL_REGIONS);

    private final StorageBackend<String, String> store;
    private final RegionResolver regionResolver;

    @Inject
    public AccountRegionService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create(
                "account",
                "account-region-opt.json",
                new TypeReference<Map<String, String>>() {
                }), regionResolver);
    }

    AccountRegionService(StorageBackend<String, String> store, RegionResolver regionResolver) {
        this.store = store;
        this.regionResolver = regionResolver;
    }

    public synchronized RegionOpt getRegionOptStatus(JsonNode request) {
        requireObject(request, "Request body");
        String regionName = requireRegionName(request);
        String accountId = resolveTargetAccount(request);
        return new RegionOpt(regionName, currentStatus(accountId, regionName));
    }

    public synchronized void enableRegion(JsonNode request) {
        requireObject(request, "Request body");
        String regionName = requireRegionName(request);
        String accountId = resolveTargetAccount(request);
        String status = currentStatus(accountId, regionName);
        if (ENABLED_BY_DEFAULT.equals(status)) {
            throw validation("The specified Region is enabled by default and cannot be modified.");
        }
        if (ENABLING.equals(status) || DISABLING.equals(status)) {
            throw conflict("The specified Region is currently being updated.");
        }
        if (ENABLED.equals(status)) {
            return;
        }
        save(accountId, regionName, ENABLED);
    }

    public synchronized void disableRegion(JsonNode request) {
        requireObject(request, "Request body");
        String regionName = requireRegionName(request);
        String accountId = resolveTargetAccount(request);
        String status = currentStatus(accountId, regionName);
        if (ENABLED_BY_DEFAULT.equals(status)) {
            throw validation("The specified Region is enabled by default and cannot be disabled.");
        }
        if (ENABLING.equals(status) || DISABLING.equals(status)) {
            throw conflict("The specified Region is currently being updated.");
        }
        if (DISABLED.equals(status)) {
            return;
        }
        save(accountId, regionName, DISABLED);
    }

    public synchronized RegionPage listRegions(JsonNode request) {
        requireObject(request, "Request body");
        String accountId = resolveTargetAccount(request);
        Set<String> filter = parseStatusFilter(request);
        int maxResults = parseMaxResults(request);
        int start = parseOffset(request.get("NextToken"));

        List<RegionOpt> matched = new ArrayList<>();
        for (String regionName : ALL_REGIONS) {
            String status = currentStatus(accountId, regionName);
            if (filter == null || filter.contains(status)) {
                matched.add(new RegionOpt(regionName, status));
            }
        }
        if (start > matched.size()) {
            start = matched.size();
        }
        int end = Math.min(start + maxResults, matched.size());
        List<RegionOpt> page = List.copyOf(matched.subList(start, end));
        String nextToken = end < matched.size() ? Integer.toString(end) : null;
        return new RegionPage(page, nextToken);
    }

    private String currentStatus(String accountId, String regionName) {
        if (!KNOWN_REGIONS.contains(regionName)) {
            throw validation("The specified RegionName is not valid.");
        }
        Optional<String> stored = load(accountId, regionName);
        if (stored.isPresent()) {
            return stored.get();
        }
        return DEFAULT_ENABLED_SET.contains(regionName) ? ENABLED_BY_DEFAULT : DISABLED;
    }

    private String resolveTargetAccount(JsonNode request) {
        if (!request.has("AccountId") || request.get("AccountId").isNull()) {
            return regionResolver.getAccountId();
        }
        String accountId = requireText(request, "AccountId");
        if (!ACCOUNT_ID.matcher(accountId).matches()) {
            throw validation("AccountId must be a 12-digit identifier.");
        }
        return accountId;
    }

    private Optional<String> load(String accountId, String regionName) {
        if (store instanceof AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<String> typed = (AccountAwareStorageBackend<String>) aware;
            return typed.getForAccount(accountId, regionName);
        }
        return store.get(accountId + "/" + regionName);
    }

    private void save(String accountId, String regionName, String status) {
        if (store instanceof AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<String> typed = (AccountAwareStorageBackend<String>) aware;
            typed.putForAccount(accountId, regionName, status);
            return;
        }
        store.put(accountId + "/" + regionName, status);
    }

    private static String requireRegionName(JsonNode request) {
        String regionName = requireText(request, "RegionName");
        if (regionName.length() < 1 || regionName.length() > 50) {
            throw validation("RegionName must be between 1 and 50 characters.");
        }
        if (!REGION_NAME.matcher(regionName).matches()) {
            throw validation("The specified RegionName is not valid.");
        }
        return regionName;
    }

    private static Set<String> parseStatusFilter(JsonNode request) {
        if (!request.has("RegionOptStatusContains") || request.get("RegionOptStatusContains").isNull()) {
            return null;
        }
        JsonNode node = request.get("RegionOptStatusContains");
        if (!node.isArray()) {
            throw validation("RegionOptStatusContains must be a list of Region opt-in statuses.");
        }
        Map<String, Boolean> unique = new LinkedHashMap<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw validation("RegionOptStatusContains must be a list of Region opt-in statuses.");
            }
            String status = item.textValue();
            if (!STATUSES.contains(status)) {
                throw validation("RegionOptStatusContains contains an invalid Region opt-in status.");
            }
            unique.put(status, Boolean.TRUE);
        }
        return unique.isEmpty() ? null : unique.keySet();
    }

    private static int parseMaxResults(JsonNode request) {
        if (!request.has("MaxResults") || request.get("MaxResults").isNull()) {
            return DEFAULT_MAX_RESULTS;
        }
        JsonNode node = request.get("MaxResults");
        if (!node.isNumber() && !node.isTextual()) {
            throw validation("MaxResults must be an integer between 1 and 50.");
        }
        int value;
        try {
            value = node.isNumber() ? node.intValue() : Integer.parseInt(node.textValue());
        } catch (NumberFormatException e) {
            throw validation("MaxResults must be an integer between 1 and 50.");
        }
        if (value < 1 || value > MAX_RESULTS_CAP) {
            throw validation("MaxResults must be an integer between 1 and 50.");
        }
        return value;
    }

    private static int parseOffset(JsonNode tokenNode) {
        if (tokenNode == null || tokenNode.isNull()) {
            return 0;
        }
        if (!tokenNode.isTextual() && !tokenNode.isNumber()) {
            throw validation("NextToken is invalid.");
        }
        String token = tokenNode.isTextual() ? tokenNode.textValue() : tokenNode.asText();
        if (token.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(token);
            if (offset < 0) {
                throw validation("NextToken is invalid.");
            }
            return offset;
        } catch (NumberFormatException e) {
            throw validation("NextToken is invalid.");
        }
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    record RegionOpt(String regionName, String regionOptStatus) {
    }

    record RegionPage(List<RegionOpt> regions, String nextToken) {
    }
}
