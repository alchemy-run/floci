package io.github.hectorvent.floci.services.servicequotas;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.servicequotas.model.AppliedQuota;
import io.github.hectorvent.floci.services.servicequotas.model.QuotaIncreaseRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AWS Service Quotas JSON 1.1 ({@code ServiceQuotasV20190624.*}).
 *
 * <p>Defaults are a static catalog of the quotas Alchemy's live suite reads.
 * Increase requests persist and are auto-approved so local reconcilers do not
 * wait on Support.
 */
@ApplicationScoped
public class ServiceQuotasService implements Resettable {

    static final String SERVICE = "servicequotas";

    private static final Map<String, ServiceDef> SERVICES = catalog();

    private final StorageBackend<String, QuotaIncreaseRequest> requests;
    private final StorageBackend<String, AppliedQuota> applied;
    private final RegionResolver regionResolver;

    @Inject
    public ServiceQuotasService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create(SERVICE, "servicequotas-requests.json",
                        new TypeReference<Map<String, QuotaIncreaseRequest>>() {
                        }),
                storageFactory.create(SERVICE, "servicequotas-applied.json",
                        new TypeReference<Map<String, AppliedQuota>>() {
                        }),
                regionResolver);
    }

    ServiceQuotasService(StorageBackend<String, QuotaIncreaseRequest> requests,
                         StorageBackend<String, AppliedQuota> applied,
                         RegionResolver regionResolver) {
        this.requests = requests;
        this.applied = applied;
        this.regionResolver = regionResolver;
    }

    public List<ServiceDef> listServices() {
        return new ArrayList<>(SERVICES.values());
    }

    public ServiceDef requireService(String serviceCode) {
        requireNonEmpty(serviceCode, "ServiceCode");
        ServiceDef service = SERVICES.get(serviceCode);
        if (service == null) {
            throw noSuchResource("The specified service does not exist.");
        }
        return service;
    }

    public QuotaDef requireQuota(String serviceCode, String quotaCode) {
        requireNonEmpty(quotaCode, "QuotaCode");
        QuotaDef quota = requireService(serviceCode).quotas().get(quotaCode);
        if (quota == null) {
            throw noSuchResource("The specified quota does not exist.");
        }
        return quota;
    }

    public QuotaSnapshot getDefaultQuota(String serviceCode, String quotaCode) {
        QuotaDef quota = requireQuota(serviceCode, quotaCode);
        return snapshot(quota, quota.defaultValue());
    }

    public QuotaSnapshot getAppliedQuota(String serviceCode, String quotaCode, String contextId) {
        QuotaDef quota = requireQuota(serviceCode, quotaCode);
        double value = applied.get(appliedKey(serviceCode, quotaCode, contextId))
                .map(AppliedQuota::getValue)
                .orElse(quota.defaultValue());
        return snapshot(quota, value);
    }

    public List<QuotaSnapshot> listAppliedQuotas(String serviceCode) {
        ServiceDef service = requireService(serviceCode);
        List<QuotaSnapshot> out = new ArrayList<>();
        for (QuotaDef quota : service.quotas().values()) {
            out.add(getAppliedQuota(serviceCode, quota.quotaCode(), null));
        }
        return out;
    }

    public List<QuotaSnapshot> listDefaultQuotas(String serviceCode) {
        ServiceDef service = requireService(serviceCode);
        List<QuotaSnapshot> out = new ArrayList<>();
        for (QuotaDef quota : service.quotas().values()) {
            out.add(snapshot(quota, quota.defaultValue()));
        }
        return out;
    }

    public synchronized QuotaIncreaseRequest requestIncrease(String serviceCode, String quotaCode,
                                                             Double desiredValue, String contextId) {
        if (desiredValue == null) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'DesiredValue' failed to satisfy constraint: Member must not be null.",
                    400);
        }
        QuotaSnapshot current = getAppliedQuota(serviceCode, quotaCode, contextId);
        if (desiredValue <= current.value()) {
            throw new AwsException("InvalidResourceStateException",
                    "The desired value must be greater than the currently applied quota value.",
                    405);
        }
        for (QuotaIncreaseRequest existing : requests.values()) {
            if (matchesQuota(existing, serviceCode, quotaCode, contextId)
                    && isOpen(existing.getStatus())) {
                throw new AwsException("ResourceAlreadyExistsException",
                        "A quota increase request already exists for this quota.",
                        400);
            }
        }

        long now = Instant.now().getEpochSecond();
        QuotaIncreaseRequest request = new QuotaIncreaseRequest();
        request.setId(UUID.randomUUID().toString());
        request.setServiceCode(current.serviceCode());
        request.setServiceName(current.serviceName());
        request.setQuotaCode(current.quotaCode());
        request.setQuotaName(current.quotaName());
        request.setQuotaArn(current.quotaArn());
        request.setDesiredValue(desiredValue);
        request.setStatus("APPROVED");
        request.setCreated(now);
        request.setLastUpdated(now);
        request.setUnit(current.unit());
        request.setGlobalQuota(current.globalQuota());
        request.setContextId(blankToNull(contextId));
        request.setRequester(accountId());
        requests.put(request.getId(), request);

        AppliedQuota override = new AppliedQuota();
        override.setServiceCode(serviceCode);
        override.setQuotaCode(quotaCode);
        override.setContextId(blankToNull(contextId));
        override.setValue(desiredValue);
        applied.put(appliedKey(serviceCode, quotaCode, contextId), override);
        return request;
    }

    public QuotaIncreaseRequest getRequest(String requestId) {
        requireNonEmpty(requestId, "RequestId");
        return requests.get(requestId).orElseThrow(() ->
                noSuchResource("The specified quota increase request does not exist."));
    }

    public List<QuotaIncreaseRequest> listHistory(String serviceCode, String quotaCode, String status) {
        List<QuotaIncreaseRequest> out = new ArrayList<>();
        for (QuotaIncreaseRequest request : requests.values()) {
            if (serviceCode != null && !serviceCode.isEmpty()
                    && !serviceCode.equals(request.getServiceCode())) {
                continue;
            }
            if (quotaCode != null && !quotaCode.isEmpty()
                    && !quotaCode.equals(request.getQuotaCode())) {
                continue;
            }
            if (status != null && !status.isEmpty()
                    && !status.equals(request.getStatus())) {
                continue;
            }
            out.add(request);
        }
        return out;
    }

    @Override
    public void clear() {
        requests.clear();
        applied.clear();
    }

    private QuotaSnapshot snapshot(QuotaDef quota, double value) {
        String region = region();
        String account = accountId();
        String arn = "arn:aws:servicequotas:" + region + ":" + account
                + ":quota/" + quota.serviceCode() + "/" + quota.quotaCode();
        return new QuotaSnapshot(
                quota.serviceCode(),
                quota.serviceName(),
                quota.quotaCode(),
                quota.quotaName(),
                arn,
                value,
                quota.unit(),
                quota.adjustable(),
                quota.globalQuota());
    }

    private String region() {
        return regionResolver != null ? regionResolver.getRegion() : "us-east-1";
    }

    private String accountId() {
        return regionResolver != null ? regionResolver.getAccountId() : "000000000000";
    }

    private static String appliedKey(String serviceCode, String quotaCode, String contextId) {
        String ctx = contextId == null ? "" : contextId;
        return serviceCode + "|" + quotaCode + "|" + ctx;
    }

    private static boolean matchesQuota(QuotaIncreaseRequest request, String serviceCode,
                                        String quotaCode, String contextId) {
        if (!serviceCode.equals(request.getServiceCode()) || !quotaCode.equals(request.getQuotaCode())) {
            return false;
        }
        String left = blankToNull(contextId);
        String right = blankToNull(request.getContextId());
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private static boolean isOpen(String status) {
        return "PENDING".equals(status) || "CASE_OPENED".equals(status);
    }

    private static void requireNonEmpty(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at '" + field
                            + "' failed to satisfy constraint: Member must not be null.",
                    400);
        }
    }

    private static AwsException noSuchResource(String message) {
        return new AwsException("NoSuchResourceException", message, 404);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    private static Map<String, ServiceDef> catalog() {
        Map<String, ServiceDef> services = new LinkedHashMap<>();
        services.put("vpc", service("vpc", "Amazon Virtual Private Cloud (Amazon VPC)",
                quota("vpc", "Amazon Virtual Private Cloud (Amazon VPC)",
                        "L-F678F1CE", "VPCs per Region", 5, "None", true, false),
                quota("vpc", "Amazon Virtual Private Cloud (Amazon VPC)",
                        "L-407747CB", "Subnets per VPC", 200, "None", true, false),
                quota("vpc", "Amazon Virtual Private Cloud (Amazon VPC)",
                        "L-A4707A72", "Internet gateways per Region", 5, "None", true, false)));
        services.put("lambda", service("lambda", "AWS Lambda",
                quota("lambda", "AWS Lambda",
                        "L-B99A9384", "Concurrent executions", 1000, "None", true, false),
                quota("lambda", "AWS Lambda",
                        "L-2CFC414F", "Function and layer storage", 75, "Gigabytes", true, false)));
        services.put("ec2", service("ec2", "Amazon Elastic Compute Cloud (Amazon EC2)",
                quota("ec2", "Amazon Elastic Compute Cloud (Amazon EC2)",
                        "L-0263D0A3", "EC2-VPC Elastic IPs", 5, "None", true, false)));
        return services;
    }

    private static ServiceDef service(String code, String name, QuotaDef... quotas) {
        Map<String, QuotaDef> map = new LinkedHashMap<>();
        for (QuotaDef quota : quotas) {
            map.put(quota.quotaCode(), quota);
        }
        return new ServiceDef(code, name, map);
    }

    private static QuotaDef quota(String serviceCode, String serviceName, String quotaCode,
                                  String quotaName, double defaultValue, String unit,
                                  boolean adjustable, boolean globalQuota) {
        return new QuotaDef(serviceCode, serviceName, quotaCode, quotaName,
                defaultValue, unit, adjustable, globalQuota);
    }

    record ServiceDef(String serviceCode, String serviceName, Map<String, QuotaDef> quotas) {
    }

    record QuotaDef(String serviceCode, String serviceName, String quotaCode, String quotaName,
                    double defaultValue, String unit, boolean adjustable, boolean globalQuota) {
    }

    record QuotaSnapshot(String serviceCode, String serviceName, String quotaCode, String quotaName,
                         String quotaArn, double value, String unit, boolean adjustable,
                         boolean globalQuota) {
    }
}
