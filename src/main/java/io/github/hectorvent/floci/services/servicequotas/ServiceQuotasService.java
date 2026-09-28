package io.github.hectorvent.floci.services.servicequotas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.servicequotas.ServiceQuotasCatalog.QuotaDefinition;
import io.github.hectorvent.floci.services.servicequotas.ServiceQuotasCatalog.ServiceDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Service Quotas emulation backed by {@link ServiceQuotasCatalog}, the static catalog of AWS
 * default quotas. Applied values equal the AWS defaults: the emulator has no approval process,
 * so submitted increase requests are recorded (and observable through the request-history
 * operations) but stay {@code PENDING} and never change a quota value.
 *
 * @see <a href="https://docs.aws.amazon.com/servicequotas/2019-06-24/apireference/Welcome.html">Service Quotas API</a>
 */
@ApplicationScoped
public class ServiceQuotasService implements Resettable {

    private static final Pattern SERVICE_CODE_PATTERN =
            Pattern.compile("[a-zA-Z][a-zA-Z0-9-]{1,63}");
    private static final Set<String> REQUEST_STATUSES = Set.of(
            "PENDING", "CASE_OPENED", "APPROVED", "DENIED", "CASE_CLOSED", "NOT_APPROVED", "INVALID_REQUEST");
    private static final Set<String> OPEN_REQUEST_STATUSES = Set.of("PENDING", "CASE_OPENED");
    private static final Set<String> APPLIED_LEVELS = Set.of("ACCOUNT", "RESOURCE", "ALL");
    private static final String NO_SUCH_QUOTA =
            "The request failed because the specified service quota does not exist.";

    private final ObjectMapper objectMapper;
    /** Increase requests keyed by {@code accountId/region}, then request id, in submission order. */
    private final Map<String, Map<String, RequestedChange>> requests = new ConcurrentHashMap<>();

    @Inject
    public ServiceQuotasService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void clear() {
        requests.clear();
    }

    // ── Catalog reads ─────────────────────────────────────────────────────────

    public ObjectNode listServices(String nextToken, Integer maxResults) {
        Page<ServiceDefinition> page = paginate(ServiceQuotasCatalog.services(), nextToken, maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("Services");
        for (ServiceDefinition service : page.items()) {
            ObjectNode node = array.addObject();
            node.put("ServiceCode", service.serviceCode());
            node.put("ServiceName", service.serviceName());
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public ObjectNode listServiceQuotas(String serviceCode, String quotaCodeFilter, String appliedLevel,
                                        String nextToken, Integer maxResults, String region, String accountId) {
        ServiceDefinition service = requireService(serviceCode);
        requireAppliedLevel(appliedLevel);
        List<QuotaDefinition> quotas = service.quotas();
        if (quotaCodeFilter != null && !quotaCodeFilter.isEmpty()) {
            quotas = quotas.stream().filter(q -> q.quotaCode().equals(quotaCodeFilter)).toList();
        }
        // Every catalog quota is applied at the account level.
        if ("RESOURCE".equals(appliedLevel)) {
            quotas = List.of();
        }
        return quotaPage(service, quotas, nextToken, maxResults, region, accountId);
    }

    public ObjectNode listAwsDefaultServiceQuotas(String serviceCode, String nextToken, Integer maxResults,
                                                  String region) {
        ServiceDefinition service = requireService(serviceCode);
        return quotaPage(service, service.quotas(), nextToken, maxResults, region, null);
    }

    public ObjectNode getServiceQuota(String serviceCode, String quotaCode, String region, String accountId) {
        ServiceDefinition service = requireService(serviceCode);
        QuotaDefinition quota = requireQuota(service, quotaCode);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Quota", quotaNode(service, quota, region, accountId));
        return response;
    }

    public ObjectNode getAwsDefaultServiceQuota(String serviceCode, String quotaCode, String region) {
        ServiceDefinition service = requireService(serviceCode);
        QuotaDefinition quota = requireQuota(service, quotaCode);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Quota", quotaNode(service, quota, region, null));
        return response;
    }

    List<QuotaDefinition> quotasFor(String serviceCode) {
        return requireService(serviceCode).quotas();
    }

    private ObjectNode quotaPage(ServiceDefinition service, List<QuotaDefinition> quotas, String nextToken,
                                 Integer maxResults, String region, String accountId) {
        Page<QuotaDefinition> page = paginate(quotas, nextToken, maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("Quotas");
        for (QuotaDefinition quota : page.items()) {
            array.add(quotaNode(service, quota, region, accountId));
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    /**
     * Applied quotas carry the account in their ARN; AWS default quotas do not
     * ({@code arn:aws:servicequotas:<region>::<service>/<quota>}).
     */
    private ObjectNode quotaNode(ServiceDefinition service, QuotaDefinition quota, String region,
                                 String accountId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ServiceCode", service.serviceCode());
        node.put("ServiceName", service.serviceName());
        node.put("QuotaArn", quotaArn(service, quota, region, accountId));
        node.put("QuotaCode", quota.quotaCode());
        node.put("QuotaName", quota.quotaName());
        node.put("Value", quota.defaultValue());
        node.put("Unit", quota.unit());
        node.put("Adjustable", quota.adjustable());
        node.put("GlobalQuota", quota.globalQuota());
        if (accountId != null) {
            node.put("QuotaAppliedAtLevel", "ACCOUNT");
        }
        return node;
    }

    private static String quotaArn(ServiceDefinition service, QuotaDefinition quota, String region,
                                   String accountId) {
        return "arn:aws:servicequotas:" + region + ":" + (accountId == null ? "" : accountId) + ":"
                + service.serviceCode() + "/" + quota.quotaCode();
    }

    // ── Increase requests ─────────────────────────────────────────────────────

    public ObjectNode requestServiceQuotaIncrease(String serviceCode, String quotaCode, Double desiredValue,
                                                  String contextId, String region, String accountId) {
        ServiceDefinition service = requireService(serviceCode);
        if (quotaCode == null || quotaCode.isEmpty()) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: QuotaCode must not be empty.", 400);
        }
        if (desiredValue == null) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: DesiredValue must not be null.", 400);
        }
        if (desiredValue < 0 || desiredValue > 10000000000.0) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: DesiredValue must be between 0 and 10000000000.", 400);
        }
        QuotaDefinition quota = requireQuota(service, quotaCode);
        if (desiredValue <= quota.defaultValue()) {
            throw new AwsException("IllegalArgumentException",
                    "The requested value must be greater than the current quota value of "
                            + formatValue(quota.defaultValue()) + ".", 400);
        }

        Map<String, RequestedChange> scope = requests.computeIfAbsent(scopeKey(accountId, region),
                ignored -> new LinkedHashMap<>());
        RequestedChange change;
        synchronized (scope) {
            boolean open = scope.values().stream().anyMatch(existing ->
                    existing.serviceCode().equals(service.serviceCode())
                            && existing.quotaCode().equals(quota.quotaCode())
                            && Objects.equals(existing.contextId(), contextId)
                            && OPEN_REQUEST_STATUSES.contains(existing.status()));
            if (open) {
                throw new AwsException("ResourceAlreadyExistsException",
                        "A quota increase request for this quota is already open.", 400);
            }
            long now = Instant.now().getEpochSecond();
            change = new RequestedChange(UUID.randomUUID().toString().replace("-", ""),
                    service.serviceCode(), service.serviceName(), quota.quotaCode(), quota.quotaName(),
                    quotaArn(service, quota, region, accountId), desiredValue, "PENDING",
                    "{\"accountId\":\"" + accountId + "\"}", quota.unit(), quota.globalQuota(),
                    contextId == null ? "ACCOUNT" : "RESOURCE", contextId, now, now);
            scope.put(change.id(), change);
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.set("RequestedQuota", requestNode(change));
        return response;
    }

    public ObjectNode getRequestedServiceQuotaChange(String requestId, String region, String accountId) {
        if (requestId == null || requestId.isEmpty()) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: RequestId must not be empty.", 400);
        }
        RequestedChange change = snapshot(accountId, region).stream()
                .filter(candidate -> candidate.id().equals(requestId))
                .findFirst()
                .orElseThrow(() -> new AwsException("NoSuchResourceException",
                        "The request failed because the specified quota increase request does not exist.", 400));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("RequestedQuota", requestNode(change));
        return response;
    }

    public ObjectNode listRequestedServiceQuotaChangeHistory(String serviceCode, String status,
                                                             String requestedAtLevel, String nextToken,
                                                             Integer maxResults, String region,
                                                             String accountId) {
        ServiceDefinition service = serviceCode == null ? null : requireService(serviceCode);
        requireStatus(status);
        requireAppliedLevel(requestedAtLevel);
        List<RequestedChange> matching = snapshot(accountId, region).stream()
                .filter(change -> service == null || change.serviceCode().equals(service.serviceCode()))
                .filter(change -> matchesFilters(change, status, requestedAtLevel))
                .toList();
        return requestPage(matching, nextToken, maxResults);
    }

    public ObjectNode listRequestedServiceQuotaChangeHistoryByQuota(String serviceCode, String quotaCode,
                                                                    String status, String requestedAtLevel,
                                                                    String nextToken, Integer maxResults,
                                                                    String region, String accountId) {
        ServiceDefinition service = requireService(serviceCode);
        QuotaDefinition quota = requireQuota(service, quotaCode);
        requireStatus(status);
        requireAppliedLevel(requestedAtLevel);
        List<RequestedChange> matching = snapshot(accountId, region).stream()
                .filter(change -> change.serviceCode().equals(service.serviceCode())
                        && change.quotaCode().equals(quota.quotaCode()))
                .filter(change -> matchesFilters(change, status, requestedAtLevel))
                .toList();
        return requestPage(matching, nextToken, maxResults);
    }

    private static boolean matchesFilters(RequestedChange change, String status, String requestedAtLevel) {
        return (status == null || change.status().equals(status))
                && (requestedAtLevel == null || "ALL".equals(requestedAtLevel)
                        || change.requestedAtLevel().equals(requestedAtLevel));
    }

    /** Most recent request first. */
    private List<RequestedChange> snapshot(String accountId, String region) {
        Map<String, RequestedChange> scope = requests.get(scopeKey(accountId, region));
        if (scope == null) {
            return List.of();
        }
        List<RequestedChange> copy;
        synchronized (scope) {
            copy = new ArrayList<>(scope.values());
        }
        return copy.reversed();
    }

    private ObjectNode requestPage(List<RequestedChange> changes, String nextToken, Integer maxResults) {
        Page<RequestedChange> page = paginate(changes, nextToken, maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("RequestedQuotas");
        for (RequestedChange change : page.items()) {
            array.add(requestNode(change));
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    private ObjectNode requestNode(RequestedChange change) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", change.id());
        node.put("ServiceCode", change.serviceCode());
        node.put("ServiceName", change.serviceName());
        node.put("QuotaCode", change.quotaCode());
        node.put("QuotaName", change.quotaName());
        node.put("DesiredValue", change.desiredValue());
        node.put("Status", change.status());
        node.put("Created", change.created());
        node.put("LastUpdated", change.lastUpdated());
        node.put("Requester", change.requester());
        node.put("QuotaArn", change.quotaArn());
        node.put("GlobalQuota", change.globalQuota());
        node.put("Unit", change.unit());
        node.put("QuotaRequestedAtLevel", change.requestedAtLevel());
        if (change.contextId() != null) {
            ObjectNode context = node.putObject("QuotaContext");
            context.put("ContextId", change.contextId());
            context.put("ContextScope", "RESOURCE");
        }
        return node;
    }

    private static String scopeKey(String accountId, String region) {
        return accountId + "/" + region;
    }

    private static String formatValue(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * The model constrains ServiceCode to at most 63 characters matching
     * {@code [a-zA-Z][a-zA-Z0-9-]{1,63}}; a well-formed code outside the catalog is an unknown service.
     */
    private static ServiceDefinition requireService(String serviceCode) {
        if (serviceCode == null || serviceCode.isEmpty()) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: ServiceCode must not be empty.", 400);
        }
        if (serviceCode.length() > 63 || !SERVICE_CODE_PATTERN.matcher(serviceCode).matches()) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: ServiceCode must match [a-zA-Z][a-zA-Z0-9-]{1,63} "
                            + "and be at most 63 characters.", 400);
        }
        return ServiceQuotasCatalog.service(serviceCode).orElseThrow(() -> new AwsException(
                "NoSuchResourceException", "The request failed because the specified service does not exist.", 400));
    }

    private static QuotaDefinition requireQuota(ServiceDefinition service, String quotaCode) {
        if (quotaCode == null || quotaCode.isEmpty()) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: QuotaCode must not be empty.", 400);
        }
        return service.quotas().stream()
                .filter(q -> q.quotaCode().equals(quotaCode))
                .findFirst()
                .orElseThrow(() -> new AwsException("NoSuchResourceException", NO_SUCH_QUOTA, 400));
    }

    private static void requireStatus(String status) {
        if (status != null && !REQUEST_STATUSES.contains(status)) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: Status must be one of " + REQUEST_STATUSES + ".", 400);
        }
    }

    private static void requireAppliedLevel(String level) {
        if (level != null && !APPLIED_LEVELS.contains(level)) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: level must be one of ACCOUNT, RESOURCE, ALL.", 400);
        }
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    private static <T> Page<T> paginate(List<T> items, String nextToken, Integer maxResults) {
        if (maxResults != null && (maxResults < 1 || maxResults > 100)) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: MaxResults must be between 1 and 100.", 400);
        }
        int start = decodeToken(nextToken);
        if (start < 0 || start > items.size()) {
            throw new AwsException("InvalidPaginationTokenException", "Invalid NextToken.", 400);
        }
        int pageSize = maxResults == null ? 100 : maxResults;
        int end = Math.min(items.size(), start + pageSize);
        String next = (end < items.size()) ? encodeToken(end) : null;
        return new Page<>(items.subList(start, end), next);
    }

    private static String encodeToken(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeToken(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            return Integer.parseInt(new String(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private record Page<T>(List<T> items, String nextToken) {
    }

    private record RequestedChange(String id, String serviceCode, String serviceName, String quotaCode,
                                   String quotaName, String quotaArn, double desiredValue, String status,
                                   String requester, String unit, boolean globalQuota,
                                   String requestedAtLevel, String contextId, long created,
                                   long lastUpdated) {
    }
}
