package io.github.hectorvent.floci.services.servicequotas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.ServiceQuotasStartupValidator;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.servicequotas.ServiceQuotasCatalog.QuotaDefinition;
import io.github.hectorvent.floci.services.servicequotas.ServiceQuotasCatalog.ServiceDefinition;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
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
import java.util.zip.CRC32;

/**
 * Service Quotas emulation backed by a generated in-memory catalog.
 *
 * <p>Every service code resolves to a quota list: a curated set with real AWS
 * quota codes where tooling depends on them (CodeBuild's {@code L-2DC20C30}
 * "Concurrently running builds", Lambda's {@code L-B99A9384} "Concurrent
 * executions"), plus a deterministic generic set for any other service code.
 * Unmodeled values are deliberately generous so local pipelines that gate on
 * quota headroom never stall on a limit the emulator does not enforce.
 * Applied EC2 Standard On-Demand vCPU values use local configuration; AWS defaults
 * retain the catalog value.
 *
 * @see <a href="https://docs.aws.amazon.com/servicequotas/2019-06-24/apireference/Welcome.html">Service Quotas API</a>
 */
@ApplicationScoped
public class ServiceQuotasService implements Resettable {

    private static final Pattern SERVICE_CODE_PATTERN =
            Pattern.compile("[a-zA-Z][a-zA-Z0-9-]{1,63}");

    private static final Map<String, String> SERVICE_NAMES = Map.ofEntries(
            Map.entry("codebuild", "AWS CodeBuild"),
            Map.entry("codepipeline", "AWS CodePipeline"),
            Map.entry("lambda", "AWS Lambda"),
            Map.entry("cloudformation", "AWS CloudFormation"),
            Map.entry("organizations", "AWS Organizations"),
            Map.entry("iam", "AWS Identity and Access Management (IAM)"),
            Map.entry("kms", "AWS Key Management Service (AWS KMS)"),
            Map.entry("s3", "Amazon Simple Storage Service (Amazon S3)"),
            Map.entry("sns", "Amazon Simple Notification Service (Amazon SNS)"),
            Map.entry("sqs", "Amazon Simple Queue Service (Amazon SQS)"),
            Map.entry("ec2", "Amazon Elastic Compute Cloud (Amazon EC2)"),
            Map.entry("vpc", "Amazon Virtual Private Cloud (Amazon VPC)"),
            Map.entry("dynamodb", "Amazon DynamoDB"),
            Map.entry("logs", "Amazon CloudWatch Logs"),
            Map.entry("events", "Amazon EventBridge (CloudWatch Events)"));

    private static final Map<String, List<QuotaDefinition>> CURATED_QUOTAS = Map.of(
            "codebuild", List.of(
                    new QuotaDefinition("L-2DC20C30", "Concurrently running builds", GENERIC_QUOTA_VALUE, false)),
            "lambda", List.of(
                    new QuotaDefinition("L-B99A9384", "Concurrent executions", GENERIC_QUOTA_VALUE, false)),
            "organizations", List.of(
                    new QuotaDefinition("L-E619E033", "Maximum number of accounts", 50.0, true)),
            "ec2", List.of(
                    new QuotaDefinition("L-1216C47A",
                            "Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances", 5.0, false)),
            // AWS default values: tooling such as Terraform's aws_servicequotas_service_quota
            // compares its desired value against these before requesting an increase.
            "vpc", List.of(
                    new QuotaDefinition("L-FE5A380F", "NAT gateways per Availability Zone", 5.0, false),
                    new QuotaDefinition("L-2AEEBF1A", "Rules per network ACL", 20.0, false)));

    private static final List<String> GENERIC_QUOTA_NAMES = List.of(
            "Resources per Region",
            "Rate of requests per second",
            "Concurrent operations");

    private final ObjectMapper objectMapper;
    /** Increase requests keyed by {@code accountId/region}, then request id, in submission order. */
    private final Map<String, Map<String, RequestedChange>> requests = new ConcurrentHashMap<>();
    private final int standardOnDemandVcpus;
    private final StorageBackend<String, ObjectNode> requestedQuotaStore;
    private final Object openRequestLock = new Object();

    @Inject
    public ServiceQuotasService(StorageFactory storageFactory, ObjectMapper objectMapper, EmulatorConfig config) {
        this.objectMapper = objectMapper;
        this.standardOnDemandVcpus = config.services().servicequotas().standardOnDemandVcpus();
        ServiceQuotasStartupValidator.validateStandardOnDemandVcpus(standardOnDemandVcpus);
        this.requestedQuotaStore = storageFactory.create("servicequotas",
                "servicequotas-requested-changes.json",
                new TypeReference<Map<String, ObjectNode>>() {});
    }

    public ObjectNode listServiceQuotas(String serviceCode, String quotaCodeFilter, String nextToken,
                                        Integer maxResults, String region, String accountId) {
        return listQuotas(serviceCode, quotaCodeFilter, nextToken, maxResults, region, accountId, true);
    }

    public ObjectNode listAwsDefaultServiceQuotas(String serviceCode, String quotaCodeFilter, String nextToken,
                                                  Integer maxResults, String region, String accountId) {
        return listQuotas(serviceCode, quotaCodeFilter, nextToken, maxResults, region, accountId, false);
    }

    private ObjectNode listQuotas(String serviceCode, String quotaCodeFilter, String nextToken,
                                  Integer maxResults, String region, String accountId, boolean applied) {
        requireServiceCode(serviceCode);
        List<QuotaDefinition> quotas = quotasFor(serviceCode);
        if (quotaCodeFilter != null && !quotaCodeFilter.isEmpty()) {
            quotas = quotas.stream().filter(q -> q.quotaCode().equals(quotaCodeFilter)).toList();
        }
        Page<QuotaDefinition> page = paginate(quotas, nextToken, maxResults);

    // ── Catalog reads ─────────────────────────────────────────────────────────

    public ObjectNode listServices(String nextToken, Integer maxResults) {
        Page<ServiceDefinition> page = paginate(ServiceQuotasCatalog.services(), nextToken, maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("Quotas");
        for (QuotaDefinition quota : page.items()) {
            array.add(quotaNode(serviceCode, quota, region, accountId, applied));
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public ObjectNode getServiceQuota(String serviceCode, String quotaCode, String region, String accountId) {
        return getQuota(serviceCode, quotaCode, region, accountId, true);
    }

    public ObjectNode getAwsDefaultServiceQuota(String serviceCode, String quotaCode, String region, String accountId) {
        return getQuota(serviceCode, quotaCode, region, accountId, false);
    }

    private ObjectNode getQuota(String serviceCode, String quotaCode, String region, String accountId, boolean applied) {
        requireServiceCode(serviceCode);
        if (quotaCode == null || quotaCode.isEmpty()) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: QuotaCode must not be empty.", 400);
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
        response.set("Quota", quotaNode(serviceCode, quota, region, accountId, applied));
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
    static String syntheticQuotaCode(String serviceCode, String quotaName) {
        CRC32 crc = new CRC32();
        crc.update((serviceCode + "/" + quotaName).getBytes(StandardCharsets.UTF_8));
        return "L-%08X".formatted(crc.getValue());
    }

    private ObjectNode quotaNode(String serviceCode, QuotaDefinition quota, String region, String accountId,
                                  boolean applied) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ServiceCode", serviceCode);
        node.put("ServiceName", SERVICE_NAMES.getOrDefault(serviceCode, serviceCode));
        node.put("QuotaArn", AwsArnUtils.Arn.of("servicequotas", region, accountId,
                serviceCode + "/" + quota.quotaCode()).toString());
        node.put("QuotaCode", quota.quotaCode());
        node.put("QuotaName", quota.quotaName());
        double value = applied && "ec2".equals(serviceCode) && "L-1216C47A".equals(quota.quotaCode())
                ? standardOnDemandVcpus : quota.value();
        node.put("Value", value);
        node.put("Unit", "None");
        node.put("Adjustable", true);
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
        return Base64Url.encode(Integer.toString(offset));
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

    public ObjectNode listRequestedServiceQuotaChangeHistoryByQuota(String serviceCode, String quotaCode,
                                                                    String nextToken, Integer maxResults,
                                                                    String region) {
        requireServiceCode(serviceCode);
        if (quotaCode == null || quotaCode.isBlank()) {
            throw new AwsException("IllegalArgumentException", "Invalid input: QuotaCode must not be empty.", 400);
        }
        boolean exists = quotasFor(serviceCode).stream().anyMatch(quota -> quota.quotaCode().equals(quotaCode));
        if (!exists) {
            throw new AwsException("NoSuchResourceException",
                    "The request failed because the specified service quota does not exist.", 400);
        }
        List<ObjectNode> matching = new ArrayList<>();
        for (ObjectNode requested : requestedQuotaStore.scan(key -> key.startsWith(region + "/"))) {
            if (serviceCode.equals(requested.path("ServiceCode").asText())
                    && quotaCode.equals(requested.path("QuotaCode").asText())) {
                matching.add(requested);
            }
        }
        Page<ObjectNode> page = paginate(matching, nextToken, maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("RequestedQuotas");
        for (ObjectNode requested : page.items()) {
            array.add(requested.deepCopy());
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    record QuotaDefinition(String quotaCode, String quotaName, double value, boolean globalQuota) {
    }

    private record Page<T>(List<T> items, String nextToken) {
    }

    public ObjectNode requestServiceQuotaIncrease(String serviceCode, String quotaCode, Double desiredValue,
                                                  String contextId, String region, String accountId) {
        requireServiceCode(serviceCode);
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
        QuotaDefinition quota = quotasFor(serviceCode).stream()
                .filter(q -> q.quotaCode().equals(quotaCode))
                .findFirst()
                .orElseThrow(() -> new AwsException("NoSuchResourceException",
                        "The request failed because the specified service quota does not exist.", 400));

        String id = UUID.randomUUID().toString();

        long now = Instant.now().getEpochSecond();

        ObjectNode requestedQuota = objectMapper.createObjectNode();
        requestedQuota.put("Id", id);
        requestedQuota.put("ServiceCode", serviceCode);
        requestedQuota.put("ServiceName", SERVICE_NAMES.getOrDefault(serviceCode, serviceCode));
        requestedQuota.put("QuotaCode", quotaCode);
        requestedQuota.put("QuotaName", quota.quotaName());
        requestedQuota.put("QuotaArn", AwsArnUtils.Arn.of("servicequotas", region, accountId,
                serviceCode + "/" + quotaCode).toString());
        requestedQuota.put("DesiredValue", desiredValue);
        requestedQuota.put("Status", "PENDING");
        requestedQuota.put("Requester", "floci-emulator");
        requestedQuota.put("Unit", "None");
        requestedQuota.put("GlobalQuota", quota.globalQuota());
        requestedQuota.put("QuotaRequestedAtLevel", "ACCOUNT");
        requestedQuota.put("Created", now);
        requestedQuota.put("LastUpdated", now);
        if (contextId != null) {
            ObjectNode context = objectMapper.createObjectNode();
            context.put("ContextId", contextId);
            context.put("ContextScope", "RESOURCE");
            requestedQuota.set("QuotaContext", context);
        }

        storeOpenRequest(region, serviceCode, quotaCode, id, requestedQuota);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("RequestedQuota", requestedQuota);
        return response;
    }

    /**
     * AWS allows only one open increase request per quota, so the duplicate check and the
     * write must stand or fall as a unit: concurrent callers for the same quota must not
     * both pass the check.
     */
    private void storeOpenRequest(String region, String serviceCode, String quotaCode, String id,
                                  ObjectNode requestedQuota) {
        synchronized (openRequestLock) {
            boolean hasOpen = requestedQuotaStore.scan(key -> key.startsWith(region + "/")).stream()
                    .anyMatch(r -> serviceCode.equals(r.path("ServiceCode").asText())
                            && quotaCode.equals(r.path("QuotaCode").asText())
                            && "PENDING".equals(r.path("Status").asText()));
            if (hasOpen) {
                throw new AwsException("ResourceAlreadyExistsException",
                        "Only one open service quota increase request is allowed per quota.", 400);
            }
            requestedQuotaStore.put(region + "/" + id, requestedQuota.deepCopy());
        }
    }

    public ObjectNode getRequestedServiceQuotaChange(String requestId, String region) {
        if (requestId == null || requestId.isEmpty()) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: RequestId must not be empty.", 400);
        }
        ObjectNode requestedQuota = requestedQuotaStore.get(region + "/" + requestId)
                .orElseThrow(() -> new AwsException("NoSuchResourceException",
                        "The request failed because the specified request ID does not exist.", 400));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("RequestedQuota", requestedQuota.deepCopy());
        return response;
    }
}
