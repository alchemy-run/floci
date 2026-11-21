package io.floci.aws.services.ce;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static io.floci.aws.services.ce.CeRequests.invalid;
import static io.floci.aws.services.ce.CeRequests.oneOf;
import static io.floci.aws.services.ce.CeRequests.page;
import static io.floci.aws.services.ce.CeRequests.present;
import static io.floci.aws.services.ce.CeRequests.strings;
import static io.floci.aws.services.ce.CeRequests.text;

/**
 * Cost allocation tags: every tag key seen on the account's usage in the last 12 months is listed, user-defined
 * keys as {@code UserDefined} and {@code aws:}-prefixed keys as {@code AWSGenerated}, and stays {@code Inactive}
 * until activated. Activation status persists per account, and backfills record a request that completes
 * immediately.
 */
@ApplicationScoped
public class CostAllocationTagService implements Resettable {

    private final CostExplorerService costs;
    private final ObjectMapper mapper;
    private final AccountAwareStorageBackend<ObjectNode> statuses;
    private final AccountAwareStorageBackend<ObjectNode> backfills;

    @Inject
    public CostAllocationTagService(CostExplorerService costs, ObjectMapper mapper, StorageFactory storageFactory) {
        this.costs = costs;
        this.mapper = mapper;
        TypeReference<Map<String, ObjectNode>> type = new TypeReference<>() {};
        this.statuses = storageFactory.create("ce", "cost-allocation-tags.json", type);
        this.backfills = storageFactory.create("ce", "cost-allocation-tag-backfills.json", type);
    }

    public synchronized ObjectNode list(JsonNode request, String region) {
        String status = present(request, "Status")
                ? oneOf(request, "Status", Set.of("Active", "Inactive"), null) : null;
        String type = present(request, "Type")
                ? oneOf(request, "Type", Set.of("AWSGenerated", "UserDefined"), null) : null;
        Set<String> keys = present(request, "TagKeys")
                ? new HashSet<>(strings(request, "TagKeys", 100, 1024)) : Set.of();
        List<ObjectNode> tags = new ArrayList<>();
        for (ObjectNode tag : known(region).values()) {
            if ((status == null || status.equals(tag.path("Status").asText()))
                    && (type == null || type.equals(tag.path("Type").asText()))
                    && (keys.isEmpty() || keys.contains(tag.path("TagKey").asText()))) {
                tags.add(tag);
            }
        }
        PaginatedResult<ObjectNode> paged = page(tags, tag -> tag.path("TagKey").asText(), request, "MaxResults",
                "NextToken", 1000, 1000);
        ObjectNode response = mapper.createObjectNode();
        response.putArray("CostAllocationTags").addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("NextToken", paged.nextToken());
        }
        return response;
    }

    public synchronized ObjectNode updateStatus(JsonNode request, String region) {
        JsonNode entries = request.path("CostAllocationTagsStatus");
        if (!entries.isArray() || entries.isEmpty() || entries.size() > 20) {
            throw invalid("CostAllocationTagsStatus must contain between 1 and 20 entries.");
        }
        Map<String, ObjectNode> known = known(region);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode errors = response.putArray("Errors");
        String account = statuses.accountId();
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        for (JsonNode entry : entries) {
            String key = text(entry, "TagKey", 1024);
            String status = oneOf(entry, "Status", Set.of("Active", "Inactive"), null);
            ObjectNode tag = known.get(key);
            if (tag == null) {
                errors.addObject().put("TagKey", key).put("Code", "TagKeysNotFoundException")
                        .put("Message", "The tag key " + key + " has not been used in the last 12 months.");
                continue;
            }
            if ("AWSGenerated".equals(tag.path("Type").asText()) && key.startsWith("aws:cloudformation")
                    && "Inactive".equals(status)) {
                errors.addObject().put("TagKey", key).put("Code", "NoDeactivationOfAWSGeneratedTags")
                        .put("Message", "This AWS-generated tag cannot be deactivated.");
                continue;
            }
            statuses.putForAccount(account, key, mapper.createObjectNode().put("Status", status)
                    .put("LastUpdatedDate", now));
        }
        return response;
    }

    public synchronized ObjectNode startBackfill(JsonNode request) {
        String from = text(request, "BackfillFrom", 40);
        LocalDate date;
        try {
            date = Instant.parse(from.length() == 10 ? from + "T00:00:00Z" : from)
                    .atOffset(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeParseException e) {
            throw invalid("BackfillFrom must be an ISO 8601 timestamp.");
        }
        YearMonth month = YearMonth.from(date);
        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        if (date.getDayOfMonth() != 1 || month.isAfter(current) || month.isBefore(current.minusMonths(12))) {
            throw invalid("BackfillFrom must be the first day of a month within the last 12 months.");
        }
        String account = backfills.accountId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        boolean recent = backfills.scanForAccount(account, key -> true).stream()
                .anyMatch(value -> Instant.parse(value.path("RequestedAt").asText())
                        .isAfter(now.minus(1, ChronoUnit.DAYS)));
        if (recent) {
            throw new AwsException("BackfillLimitExceededException",
                    "You can request a backfill once every 24 hours.", 400);
        }
        ObjectNode backfill = mapper.createObjectNode();
        backfill.put("BackfillFrom", month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString());
        backfill.put("RequestedAt", now.toString());
        backfill.put("CompletedAt", now.toString());
        backfill.put("BackfillStatus", "SUCCEEDED");
        backfill.put("LastUpdatedAt", now.toString());
        backfills.putForAccount(account, now.toString(), backfill);
        ObjectNode response = mapper.createObjectNode();
        response.set("BackfillRequest", backfill);
        return response;
    }

    public synchronized ObjectNode listBackfills(JsonNode request) {
        List<ObjectNode> requests = new ArrayList<>(backfills.scanForAccount(backfills.accountId(), key -> true));
        requests.sort(Comparator.comparing((ObjectNode value) -> value.path("RequestedAt").asText()).reversed());
        PaginatedResult<ObjectNode> paged = page(requests, value -> value.path("RequestedAt").asText(), request,
                "MaxResults", "NextToken", 1000, 1000);
        ObjectNode response = mapper.createObjectNode();
        response.putArray("BackfillRequests").addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("NextToken", paged.nextToken());
        }
        return response;
    }

    @Override
    public synchronized void clear() {
        statuses.clear();
        backfills.clear();
    }

    /** Tag keys seen on usage in the last 12 months, with their stored status, sorted by key. */
    private Map<String, ObjectNode> known(String region) {
        Instant end = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant start = end.minus(365, ChronoUnit.DAYS);
        Map<String, ObjectNode> tags = new TreeMap<>();
        String account = statuses.accountId();
        String lastUsed = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        for (UsageLine line : costs.lines(start, end, region)) {
            if (line.tags() == null) {
                continue;
            }
            for (String key : line.tags().keySet()) {
                tags.computeIfAbsent(key, k -> {
                    ObjectNode stored = statuses.getForAccount(account, k).orElse(null);
                    ObjectNode tag = mapper.createObjectNode();
                    tag.put("TagKey", k);
                    tag.put("Type", k.startsWith("aws:") ? "AWSGenerated" : "UserDefined");
                    tag.put("Status", stored == null ? "Inactive" : stored.path("Status").asText());
                    if (stored != null) {
                        tag.put("LastUpdatedDate", stored.path("LastUpdatedDate").asText());
                    }
                    tag.put("LastUsedDate", lastUsed);
                    return tag;
                });
            }
        }
        return tags;
    }
}
