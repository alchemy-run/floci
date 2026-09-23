package io.github.hectorvent.floci.services.guardduty;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.guardduty.model.AdminAccount;
import io.github.hectorvent.floci.services.guardduty.model.Detector;
import io.github.hectorvent.floci.services.guardduty.model.DetectorAdditionalConfiguration;
import io.github.hectorvent.floci.services.guardduty.model.DetectorFeature;
import io.github.hectorvent.floci.services.guardduty.model.MemberAccount;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationAdditionalConfiguration;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationConfiguration;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationFeature;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.organizations.model.OrganizationAccount;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * GuardDuty detector lifecycle and organization configuration backed by the configured
 * Floci storage mode.
 *
 * <p>Organization state is shared across account partitions where AWS models it at organization
 * scope. Delegated-administrator status is therefore visible to requests made with the delegated
 * account's credentials, while detector and member resources remain scoped to their owning account.
 */
@ApplicationScoped
public class GuardDutyService {

    /**
     * The Terraform AWS provider matches this exact message to translate a
     * {@code BadRequestException} into resource removal from state.
     */
    static final String DETECTOR_NOT_FOUND_MESSAGE =
            "The request is rejected because the input detectorId is not owned by the current account.";

    /** Also matched verbatim by the Terraform AWS provider on delegated-admin delete. */
    static final String ADMIN_ALREADY_DISABLED_MESSAGE =
            "The request failed because the delegated administrator account has already been disabled "
                    + "and/or GuardDuty protection has been disabled.";

    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int MAX_RESULTS = 50;
    private static final String TOKEN_PREFIX = "guardduty:v1:";
    private static final Pattern ACCOUNT_ID_PATTERN = Pattern.compile("[0-9]{12}");
    private static final Set<String> FINDING_PUBLISHING_FREQUENCIES =
            Set.of("FIFTEEN_MINUTES", "ONE_HOUR", "SIX_HOURS");
    private static final Set<String> FEATURE_STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Set<String> ORG_AUTO_ENABLE_VALUES = Set.of("NEW", "NONE", "ALL");
    private static final Set<String> DETECTOR_FEATURE_NAMES = Set.of(
            "S3_DATA_EVENTS", "EKS_AUDIT_LOGS", "EBS_MALWARE_PROTECTION", "RDS_LOGIN_EVENTS",
            "LAMBDA_NETWORK_LOGS", "EKS_RUNTIME_MONITORING", "RUNTIME_MONITORING",
            "AI_PROTECTION", "AI_ANALYST");
    private static final Set<String> ORG_FEATURE_NAMES = Set.of(
            "S3_DATA_EVENTS", "EKS_AUDIT_LOGS", "EBS_MALWARE_PROTECTION", "RDS_LOGIN_EVENTS",
            "LAMBDA_NETWORK_LOGS", "EKS_RUNTIME_MONITORING", "RUNTIME_MONITORING", "AI_PROTECTION");
    private static final Set<String> ADDITIONAL_CONFIGURATION_NAMES =
            Set.of("EKS_ADDON_MANAGEMENT", "ECS_FARGATE_AGENT_MANAGEMENT", "EC2_AGENT_MANAGEMENT");

    private final StorageBackend<String, Detector> detectorStore;
    private final StorageBackend<String, AdminAccount> adminAccountStore;
    private final StorageBackend<String, MemberAccount> memberStore;
    private final S3Service s3Service;
    private final OrganizationsService organizationsService;

    @Inject
    public GuardDutyService(StorageFactory storageFactory, S3Service s3Service,
                            OrganizationsService organizationsService) {
        this(storageFactory.create(
                        "guardduty",
                        "guardduty-detectors.json",
                        new TypeReference<Map<String, Detector>>() {
                        }),
                storageFactory.create(
                        "guardduty",
                        "guardduty-admin-accounts.json",
                        new TypeReference<Map<String, AdminAccount>>() {
                        }),
                storageFactory.create(
                        "guardduty",
                        "guardduty-members.json",
                        new TypeReference<Map<String, MemberAccount>>() {
                        }), s3Service, organizationsService);
    }

    GuardDutyService(
            StorageBackend<String, Detector> detectorStore,
            StorageBackend<String, AdminAccount> adminAccountStore,
            StorageBackend<String, MemberAccount> memberStore) {
        this(detectorStore, adminAccountStore, memberStore, null, null);
    }

    GuardDutyService(
            StorageBackend<String, Detector> detectorStore,
            StorageBackend<String, AdminAccount> adminAccountStore,
            StorageBackend<String, MemberAccount> memberStore,
            S3Service s3Service) {
        this(detectorStore, adminAccountStore, memberStore, s3Service, null);
    }

    GuardDutyService(
            StorageBackend<String, Detector> detectorStore,
            StorageBackend<String, AdminAccount> adminAccountStore,
            StorageBackend<String, MemberAccount> memberStore,
            S3Service s3Service,
            OrganizationsService organizationsService) {
        this.detectorStore = detectorStore;
        this.adminAccountStore = adminAccountStore;
        this.memberStore = memberStore;
        this.s3Service = s3Service;
        this.organizationsService = organizationsService;
    }

    public synchronized Detector createDetector(String region, String accountId, JsonNode request) {
        boolean enable = requireBoolean(request, "enable");
        String frequency = readFindingPublishingFrequency(request, "SIX_HOURS");
        List<DetectorFeature> features = readDetectorFeatures(request);
        Map<String, String> tags = readTags(request);

        boolean detectorExists = detectorStore.scan(key -> key.startsWith(region + "::")).stream()
                .anyMatch(detector -> accountId.equals(accountIdFromServiceRole(detector.getServiceRole())));
        if (detectorExists) {
            throw badRequest("The request is rejected because a detector already exists for the current account.");
        }

        String now = isoTimestamp();
        Detector detector = new Detector(
                UUID.randomUUID().toString().replace("-", ""),
                enable ? "ENABLED" : "DISABLED",
                frequency,
                serviceRoleArn(accountId),
                now,
                now,
                tags,
                features,
                null);
        detectorStore.put(storageKey(region, detector.getId()), detector);
        return detector;
    }

    public Detector getDetector(String region, String detectorId) {
        return detectorStore.get(storageKey(region, detectorId)).orElseThrow(GuardDutyService::detectorNotFound);
    }

    public synchronized void updateDetector(String region, String detectorId, JsonNode request) {
        String key = storageKey(region, detectorId);
        Detector detector = detectorStore.get(key).orElseThrow(GuardDutyService::detectorNotFound);

        if (request.has("enable")) {
            detector.setStatus(requireBoolean(request, "enable") ? "ENABLED" : "DISABLED");
        }
        if (request.has("findingPublishingFrequency")) {
            detector.setFindingPublishingFrequency(readFindingPublishingFrequency(request, null));
        }
        if (request.has("features")) {
            detector.setFeatures(mergeDetectorFeatures(detector.getFeatures(), readDetectorFeatures(request)));
        }
        detector.setUpdatedAt(isoTimestamp());
        detectorStore.put(key, detector);
    }

    public synchronized void deleteDetector(String region, String detectorId) {
        String key = storageKey(region, detectorId);
        if (detectorStore.get(key).isEmpty()) {
            throw detectorNotFound();
        }
        detectorStore.delete(key);
        String memberPrefix = region + "::" + detectorId + "::";
        memberStore.keys().stream().filter(memberKey -> memberKey.startsWith(memberPrefix)).forEach(memberStore::delete);
    }

    public Page<String> listDetectorIds(String region, String accountId, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        List<Detector> detectors = detectorStore.scan(key -> key.startsWith(region + "::")).stream()
                .filter(detector -> accountId.equals(accountIdFromServiceRole(detector.getServiceRole())))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        detectors.sort(Comparator.comparing(Detector::getId));
        List<String> ids = detectors.stream().map(Detector::getId).toList();

        int offset = decodeOffset(nextToken, ids.size());
        int end = Math.min(offset + maxResults, ids.size());
        String responseToken = end < ids.size() ? encodeOffset(end) : null;
        return new Page<>(ids.subList(offset, end), responseToken);
    }

    public synchronized String createResource(String region, String detectorId, String kind, JsonNode request) {
        Detector detector = getDetector(region, detectorId);
        String token = request.has("clientToken") ? requireText(request, "clientToken") : null;
        if (token != null) {
            for (Map.Entry<String, JsonNode> entry : detector.getResources().entrySet()) {
                if (entry.getKey().startsWith(kind + "/") && token.equals(entry.getValue().path("_clientToken").asText())) {
                    if (!request.equals(entry.getValue().get("_request"))) {
                        throw badRequest("clientToken was already used with different parameters.");
                    }
                    return entry.getKey().substring(kind.length() + 1);
                }
            }
        }
        String name = requireText(request, "name");
        if (!name.matches("[A-Za-z0-9_.-]{3,64}")) {
            throw badRequest("name must contain 3 to 64 letters, digits, dots, hyphens, or underscores.");
        }
        String id = "filter".equals(kind) ? name : UUID.randomUUID().toString().replace("-", "");
        if (detector.getResources().containsKey(kind + "/" + id)) {
            throw badRequest("A resource with this name already exists.");
        }
        ObjectNode resource = object();
        resource.put("name", name);
        if ("filter".equals(kind)) {
            resource.put("action", "NOOP");
            long filterCount = detector.getResources().keySet().stream().filter(key -> key.startsWith("filter/")).count();
            resource.put("rank", filterCount + 1);
        } else {
            resource.put("format", requireText(request, "format"));
            resource.put("location", requireText(request, "location"));
            resource.put("status", requireBoolean(request, "activate") ? "ACTIVE" : "INACTIVE");
        }
        applyResourceSettings(region, detector, kind, resource, request, true);
        Map<String, String> tags = readTags(request);
        ObjectNode tagNode = resource.putObject("tags");
        if (tags != null) {
            tags.forEach(tagNode::put);
        }
        if (token != null) {
            resource.put("_clientToken", token);
            resource.set("_request", request.deepCopy());
        }
        saveResource(region, detector, kind + "/" + id, resource);
        return id;
    }

    public ObjectNode getResource(String region, String detectorId, String kind, String id) {
        ObjectNode resource = resource(getDetector(region, detectorId), kind, id).deepCopy();
        resource.remove(List.of("_clientToken", "_request", "_addresses", "_sourceETag"));
        return resource;
    }

    public Page<String> listResources(String region, String detectorId, String kind,
                                       String maxResults, String nextToken) {
        List<String> ids = getDetector(region, detectorId).getResources().keySet().stream()
                .filter(key -> key.startsWith(kind + "/"))
                .map(key -> key.substring(kind.length() + 1)).sorted().toList();
        return page(ids, maxResults, nextToken);
    }

    public synchronized void updateResource(String region, String detectorId, String kind, String id, JsonNode request) {
        Detector detector = getDetector(region, detectorId);
        ObjectNode resource = resource(detector, kind, id).deepCopy();
        applyResourceSettings(region, detector, kind, resource, request, false);
        saveResource(region, detector, kind + "/" + id, resource);
    }

    public synchronized void deleteResource(String region, String detectorId, String kind, String id) {
        Detector detector = getDetector(region, detectorId);
        resource(detector, kind, id);
        Map<String, JsonNode> resources = new LinkedHashMap<>(detector.getResources());
        resources.remove(kind + "/" + id);
        detector.setResources(resources);
        detectorStore.put(storageKey(region, detectorId), detector);
    }

    private void applyResourceSettings(String region, Detector detector, String kind, ObjectNode resource,
                                       JsonNode request, boolean create) {
        if ("filter".equals(kind)) {
            if (request.has("description")) {
                String description = requireText(request, "description");
                if (description.length() > 512) {
                    throw badRequest("description must not exceed 512 characters.");
                }
                resource.put("description", description);
            }
            if (request.has("action")) {
                String action = requireText(request, "action");
                if (!Set.of("NOOP", "ARCHIVE").contains(action)) {
                    throw badRequest("action must be NOOP or ARCHIVE.");
                }
                resource.put("action", action);
            }
            if (request.has("rank")) {
                JsonNode rank = request.get("rank");
                long maximum = detector.getResources().keySet().stream().filter(key -> key.startsWith("filter/")).count()
                        + (create ? 1 : 0);
                if (!rank.isIntegralNumber() || rank.asLong() < 1 || rank.asLong() > Math.min(100, maximum)) {
                    throw badRequest("rank must be between 1 and the number of filters, up to 100.");
                }
                resource.set("rank", rank);
            }
            if (create || request.has("findingCriteria")) {
                JsonNode criteria = request.get("findingCriteria");
                if (criteria == null) {
                    throw badRequest("findingCriteria is required.");
                }
                validateFindingCriteria(criteria);
                resource.set("findingCriteria", criteria.deepCopy());
            }
        } else {
            if (!Set.of("ipset", "threatintelset").contains(kind)) {
                throw badRequest("Unsupported detector resource: " + kind);
            }
            if (request.has("name")) {
                String name = requireText(request, "name");
                if (!name.matches("[A-Za-z0-9_.-]{3,64}")) {
                    throw badRequest("Invalid set name.");
                }
                resource.put("name", name);
            }
            if (request.has("location")) {
                resource.put("location", requireText(request, "location"));
            }
            if (request.has("activate")) {
                resource.put("status", requireBoolean(request, "activate") ? "ACTIVE" : "INACTIVE");
            }
            if (!"TXT".equals(resource.path("format").asText())) {
                throw badRequest("Only TXT IP and threat intelligence sets are supported by Floci.");
            }
            if (create || request.has("location") || "ACTIVE".equals(resource.path("status").asText())) {
                loadIpAddresses(region, detector, resource, request);
            }
        }
    }

    private void loadIpAddresses(String region, Detector detector, ObjectNode resource, JsonNode request) {
        String owner = accountIdFromServiceRole(detector.getServiceRole());
        if (request.has("expectedBucketOwner") && !owner.equals(requireText(request, "expectedBucketOwner"))) {
            throw badRequest("The source bucket owner does not match expectedBucketOwner.");
        }
        URI location;
        try {
            location = URI.create(resource.path("location").asText());
        } catch (IllegalArgumentException e) {
            throw badRequest("location must be an S3 HTTPS URL.");
        }
        if (!"https".equals(location.getScheme()) || location.getHost() == null
                || location.getUserInfo() != null || location.getQuery() != null || location.getFragment() != null
                || location.getPort() != -1) {
            throw badRequest("location must be an S3 HTTPS URL without credentials, query, or fragment.");
        }
        String host = location.getHost();
        String path = location.getPath();
        String bucket;
        String key;
        if (path == null || !path.startsWith("/")) {
            throw badRequest("location must identify an S3 object.");
        }
        if (host.equals("s3.amazonaws.com") || host.equals("s3." + region + ".amazonaws.com")) {
            String[] parts = path.substring(1).split("/", 2);
            if (parts.length != 2) {
                throw badRequest("location must identify an S3 object.");
            }
            bucket = parts[0];
            key = parts[1];
        } else {
            String suffix = ".s3." + region + ".amazonaws.com";
            if (!host.endsWith(suffix)) {
                suffix = ".s3.amazonaws.com";
            }
            if (!host.endsWith(suffix)) {
                throw badRequest("location must identify an S3 bucket in the detector region.");
            }
            bucket = host.substring(0, host.length() - suffix.length());
            key = path.isEmpty() ? "" : path.substring(1);
        }
        if (bucket.isBlank() || key.isBlank() || s3Service == null) {
            throw badRequest("The source S3 object is unavailable.");
        }
        // S3 internal reads can cross account partitions; verify ownership before reading bytes.
        if (s3Service.listBuckets().stream().noneMatch(candidate -> bucket.equals(candidate.getName()))
                || !region.equals(s3Service.getBucketRegion(bucket))) {
            throw badRequest("The source bucket must belong to this account and detector region.");
        }
        S3Object source;
        try {
            source = s3Service.getObject(bucket, key);
        } catch (AwsException e) {
            throw badRequest("The source S3 object could not be read: " + e.getErrorCode());
        }
        if (source.getData() == null || source.getData().length > 35 * 1024 * 1024) {
            throw badRequest("The IP list is unavailable or exceeds 35 MiB.");
        }
        List<String> addresses = new ArrayList<>();
        for (String line : new String(source.getData(), StandardCharsets.UTF_8).split("\\R")) {
            String address = line.strip();
            if (address.isEmpty()) {
                continue;
            }
            try {
                String[] cidr = address.split("/", -1);
                InetAddress parsed = InetAddress.ofLiteral(cidr[0]);
                if (cidr.length > 2 || (cidr.length == 2 && (Integer.parseInt(cidr[1]) < 0
                        || Integer.parseInt(cidr[1]) > parsed.getAddress().length * 8))) {
                    throw new IllegalArgumentException("Invalid prefix length");
                }
            } catch (IllegalArgumentException e) {
                throw badRequest("The TXT list contains an invalid IP address or CIDR: " + address);
            }
            addresses.add(address);
        }
        if (addresses.isEmpty()) {
            throw badRequest("The TXT list must contain at least one IP address.");
        }
        ArrayNode entries = resource.putArray("_addresses");
        addresses.forEach(entries::add);
        resource.put("_sourceETag", source.getETag());
    }

    private static ObjectNode resource(Detector detector, String kind, String id) {
        JsonNode resource = detector.getResources().get(kind + "/" + id);
        if (!(resource instanceof ObjectNode object)) {
            throw badRequest("The requested " + kind + " does not exist for this detector.");
        }
        return object;
    }

    private void saveResource(String region, Detector detector, String key, JsonNode resource) {
        Map<String, JsonNode> resources = new LinkedHashMap<>(detector.getResources());
        resources.put(key, resource);
        detector.setResources(resources);
        detectorStore.put(storageKey(region, detector.getId()), detector);
    }

    public synchronized void createSampleFindings(String region, String detectorId, JsonNode request) {
        Detector detector = getDetector(region, detectorId);
        if (!"ENABLED".equals(detector.getStatus())) {
            throw badRequest("Sample findings require an enabled detector.");
        }
        List<String> types = stringList(request, "findingTypes", 1, 50);
        for (String type : types) {
            if (!"Recon:EC2/PortProbeUnprotectedPort".equals(type)) {
                throw badRequest("Sample finding type is not supported by Floci: " + type);
            }
        }
        Map<String, JsonNode> findings = new LinkedHashMap<>(detector.getFindings());
        String account = accountIdFromServiceRole(detector.getServiceRole());
        for (String type : types.stream().distinct().toList()) {
            String id = UUID.randomUUID().toString().replace("-", "");
            String now = isoTimestamp();
            ObjectNode finding = object();
            finding.put("id", id);
            finding.put("accountId", account);
            finding.put("arn", "arn:aws:guardduty:" + region + ":" + account
                    + ":detector/" + detectorId + "/finding/" + id);
            finding.put("partition", "aws");
            finding.put("region", region);
            finding.put("type", type);
            finding.put("schemaVersion", "2.0");
            finding.put("severity", 2.0);
            finding.put("title", "[SAMPLE] Unprotected port on an EC2 instance is being probed.");
            finding.put("description", "Sample finding created by CreateSampleFindings; no threat was detected.");
            finding.put("createdAt", now);
            finding.put("updatedAt", now);
            ObjectNode resource = finding.putObject("resource");
            resource.put("resourceType", "Instance");
            resource.putObject("instanceDetails").put("instanceId", "i-99999999")
                    .put("instanceType", "m3.xlarge").put("instanceState", "running");
            ObjectNode details = finding.putObject("service");
            details.put("serviceName", "guardduty");
            details.put("detectorId", detectorId);
            details.put("archived", false);
            details.put("count", 1);
            details.put("resourceRole", "TARGET");
            details.put("eventFirstSeen", now);
            details.put("eventLastSeen", now);
            details.putObject("additionalInfo").put("type", "default").put("value", "{\"sample\":true}");
            ObjectNode action = details.putObject("action");
            action.put("actionType", "PORT_PROBE");
            ObjectNode probe = action.putObject("portProbeAction");
            probe.put("blocked", false);
            ObjectNode probeDetail = probe.putArray("portProbeDetails").addObject();
            probeDetail.putObject("localPortDetails").put("port", 22).put("portName", "SSH");
            probeDetail.putObject("remoteIpDetails").put("ipAddressV4", "198.51.100.1");
            List<JsonNode> filters = detector.getResources().entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith("filter/"))
                    .map(Map.Entry::getValue).sorted(Comparator.comparingInt(filter -> filter.path("rank").asInt()))
                    .toList();
            for (JsonNode filter : filters) {
                if (matchesFinding(finding, filter.path("findingCriteria"))) {
                    details.put("archived", "ARCHIVE".equals(filter.path("action").asText()));
                    break;
                }
            }
            findings.put(id, finding);
        }
        detector.setFindings(findings);
        detectorStore.put(storageKey(region, detectorId), detector);
    }

    public ObjectNode listFindings(String region, String detectorId, JsonNode request) {
        List<JsonNode> findings = matchingFindings(getDetector(region, detectorId), request);
        Page<JsonNode> page = page(findings, optionalText(request, "maxResults"), optionalText(request, "nextToken"));
        ObjectNode response = object();
        ArrayNode ids = response.putArray("findingIds");
        page.items().forEach(finding -> ids.add(finding.path("id").asText()));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return response;
    }

    public ObjectNode getFindings(String region, String detectorId, JsonNode request) {
        Detector detector = getDetector(region, detectorId);
        List<String> ids = stringList(request, "findingIds", 1, 50);
        ObjectNode response = object();
        ArrayNode findings = response.putArray("findings");
        for (String id : ids.stream().distinct().toList()) {
            JsonNode finding = detector.getFindings().get(id);
            if (finding != null) {
                findings.add(finding.deepCopy());
            }
        }
        return response;
    }

    public synchronized void updateFindings(String region, String detectorId, JsonNode request, Boolean archived) {
        Detector detector = getDetector(region, detectorId);
        List<String> ids = stringList(request, "findingIds", 1, 50);
        String feedback = archived == null ? requireText(request, "feedback") : null;
        if (feedback != null && !Set.of("USEFUL", "NOT_USEFUL").contains(feedback)) {
            throw badRequest("feedback must be USEFUL or NOT_USEFUL.");
        }
        Map<String, JsonNode> findings = new LinkedHashMap<>(detector.getFindings());
        for (String id : ids) {
            if (!findings.containsKey(id)) {
                throw badRequest("The finding ID does not belong to this detector: " + id);
            }
        }
        for (String id : ids) {
            ObjectNode finding = (ObjectNode) findings.get(id).deepCopy();
            ObjectNode details = (ObjectNode) finding.get("service");
            if (archived != null) {
                details.put("archived", archived);
            } else {
                details.put("userFeedback", feedback);
            }
            finding.put("updatedAt", isoTimestamp());
            findings.put(id, finding);
        }
        detector.setFindings(findings);
        detectorStore.put(storageKey(region, detectorId), detector);
    }

    public ObjectNode getFindingsStatistics(String region, String detectorId, JsonNode request) {
        Detector detector = getDetector(region, detectorId);
        List<String> types = stringList(request, "findingStatisticTypes", 1, 1);
        if (!types.equals(List.of("COUNT_BY_SEVERITY")) || request.has("groupBy")) {
            throw badRequest("Only COUNT_BY_SEVERITY statistics are supported by Floci.");
        }
        ObjectNode response = object();
        ObjectNode counts = response.putObject("findingStatistics").putObject("countBySeverity");
        for (JsonNode finding : matchingFindings(detector, request)) {
            String severity = finding.path("severity").asText();
            counts.put(severity, counts.path(severity).asInt() + 1);
        }
        return response;
    }

    public ObjectNode getUsageStatistics(String region, String detectorId, JsonNode request) {
        Detector detector = getDetector(region, detectorId);
        String resultField = switch (requireText(request, "usageStatisticsType")) {
            case "SUM_BY_ACCOUNT" -> "sumByAccount";
            case "SUM_BY_DATA_SOURCE" -> "sumByDataSource";
            case "SUM_BY_RESOURCE" -> "sumByResource";
            case "TOP_RESOURCES" -> "topResources";
            case "SUM_BY_FEATURES" -> "sumByFeature";
            case "TOP_ACCOUNTS_BY_FEATURE" -> "topAccountsByFeature";
            default -> throw badRequest("Invalid usageStatisticsType.");
        };
        JsonNode criteria = request.get("usageCriteria");
        requireObject(criteria, "usageCriteria");
        if (criteria.has("accountIds")) {
            String owner = accountIdFromServiceRole(detector.getServiceRole());
            for (String account : stringList(criteria, "accountIds", 1, 50)) {
                if (!owner.equals(account)) {
                    throw badRequest("Usage for other accounts is not available to this detector.");
                }
            }
        }
        if (criteria.has("dataSources")) {
            for (String source : stringList(criteria, "dataSources", 1, 50)) {
                if (!Set.of("FLOW_LOGS", "CLOUD_TRAIL", "DNS_LOGS", "S3_LOGS", "KUBERNETES_AUDIT_LOGS",
                        "EC2_MALWARE_SCAN").contains(source)) {
                    throw badRequest("Invalid usage data source: " + source);
                }
            }
        }
        for (String field : List.of("resources", "features")) {
            if (criteria.has(field)) {
                stringList(criteria, field, 1, 50);
                throw badRequest("Usage filtering by " + field + " is not supported by Floci.");
            }
        }
        if (request.has("unit") && !"USD".equals(requireText(request, "unit"))) {
            throw badRequest("unit must be USD.");
        }
        page(List.of(), optionalText(request, "maxResults"), optionalText(request, "nextToken"));
        // Explicit samples do not ingest security telemetry or incur metered usage.
        ObjectNode response = object();
        response.putObject("usageStatistics").putArray(resultField);
        return response;
    }

    public ObjectNode listCoverage(String region, String detectorId, JsonNode request) {
        Detector detector = getDetector(region, detectorId);
        if (detector.getFeatures() != null && detector.getFeatures().stream().anyMatch(feature ->
                Set.of("RUNTIME_MONITORING", "EKS_RUNTIME_MONITORING").contains(feature.getName())
                        && "ENABLED".equals(feature.getStatus()))) {
            throw badRequest("Runtime monitoring coverage is unavailable: Floci does not run GuardDuty agents.");
        }
        if (request.has("filterCriteria") || request.has("sortCriteria")) {
            throw badRequest("Coverage filtering and sorting are not supported by Floci.");
        }
        page(List.of(), optionalText(request, "maxResults"), optionalText(request, "nextToken"));
        ObjectNode response = object();
        response.putArray("resources");
        return response;
    }

    public ObjectNode getRemainingFreeTrialDays(String region, String detectorId, JsonNode request) {
        Detector detector = getDetector(region, detectorId);
        String owner = accountIdFromServiceRole(detector.getServiceRole());
        List<String> accounts = stringList(request, "accountIds", 1, 50);
        ObjectNode response = object();
        response.putArray("accounts");
        ArrayNode unprocessed = response.putArray("unprocessedAccounts");
        for (String account : accounts) {
            if (!ACCOUNT_ID_PATTERN.matcher(account).matches()) {
                throw badRequest("accountIds must contain 12-digit account IDs.");
            }
            boolean associated = owner.equals(account) || memberStore.get(region + "::" + detectorId + "::" + account)
                    .filter(member -> "Enabled".equals(member.relationshipStatus())).isPresent();
            unprocessed.addObject().put("accountId", account).put("result", associated
                    ? "Free-trial entitlement is unavailable in Floci; no AWS billing enrollment exists."
                    : "The account is not associated with this detector.");
        }
        return response;
    }

    public synchronized ObjectNode inviteMembers(String region, String detectorId, JsonNode request) {
        Detector detector = getDetector(region, detectorId);
        List<String> accountIds = stringList(request, "accountIds", 1, 50);
        if (!request.has("disableEmailNotification") || !requireBoolean(request, "disableEmailNotification")) {
            throw badRequest("Floci cannot send GuardDuty invitation emails; disableEmailNotification must be true.");
        }
        if (!"ENABLED".equals(detector.getStatus())) {
            throw badRequest("Inviting members requires an enabled detector.");
        }
        for (String accountId : accountIds) {
            if (!ACCOUNT_ID_PATTERN.matcher(accountId).matches()) {
                throw badRequest("accountIds must contain 12-digit account IDs.");
            }
        }
        ObjectNode response = object();
        ArrayNode unprocessed = response.putArray("unprocessedAccounts");
        for (String accountId : accountIds) {
            String key = region + "::" + detectorId + "::" + accountId;
            MemberAccount member = memberStore.get(key).orElse(null);
            if (member == null || "Enabled".equals(member.relationshipStatus())
                    || accountId.equals(accountIdFromServiceRole(detector.getServiceRole()))) {
                unprocessed.addObject().put("accountId", accountId)
                        .put("result", "The account is not an eligible, created member of this detector.");
                continue;
            }
            String now = isoTimestamp();
            memberStore.put(key, new MemberAccount(member.accountId(), member.email(), "Invited",
                    member.administratorId(), detectorId, now, now, UUID.randomUUID().toString().replace("-", "")));
        }
        return response;
    }

    public Page<MemberAccount> listInvitations(String region, String accountId, String maxResults, String nextToken) {
        return page(invitations(region, accountId), maxResults, nextToken);
    }

    private List<MemberAccount> invitations(String region, String accountId) {
        List<MemberAccount> allMembers;
        if (memberStore instanceof AccountAwareStorageBackend<MemberAccount> accountAware) {
            allMembers = accountAware.scanAllAccountEntries(key -> key.startsWith(region + "::")).stream()
                    .map(AccountAwareStorageBackend.AccountEntry::value).toList();
        } else {
            allMembers = memberStore.scan(key -> key.startsWith(region + "::"));
        }
        return allMembers.stream()
                .filter(member -> accountId.equals(member.accountId()) && "Invited".equals(member.relationshipStatus()))
                .sorted(Comparator.comparing(MemberAccount::administratorId)).toList();
    }

    public int getInvitationsCount(String region, String accountId) {
        return invitations(region, accountId).size();
    }

    /**
     * Returns the administrator that manages the caller's detector, or {@code null} for a standalone
     * account. Only an established ({@code Enabled}) membership counts; pending invitations do not.
     */
    public MemberAccount getAdministratorAccount(String region, String detectorId, String accountId) {
        getDetector(region, detectorId);
        return allMembers(region).stream()
                .filter(member -> accountId.equals(member.accountId())
                        && "Enabled".equals(member.relationshipStatus())
                        && !accountId.equals(member.administratorId()))
                .min(Comparator.comparing(MemberAccount::administratorId))
                .orElse(null);
    }

    public ObjectNode getMalwareScanSettings(String region, String detectorId) {
        JsonNode stored = getDetector(region, detectorId).getMalwareScanSettings();
        if (stored instanceof ObjectNode settings) {
            return settings.deepCopy();
        }
        ObjectNode defaults = object();
        defaults.put("ebsSnapshotPreservation", "NO_RETENTION");
        return defaults;
    }

    public synchronized void updateMalwareScanSettings(String region, String detectorId, JsonNode request) {
        String key = storageKey(region, detectorId);
        Detector detector = detectorStore.get(key).orElseThrow(GuardDutyService::detectorNotFound);
        ObjectNode settings = getMalwareScanSettings(region, detectorId);
        if (request.has("ebsSnapshotPreservation")) {
            String preservation = requireText(request, "ebsSnapshotPreservation");
            if (!Set.of("NO_RETENTION", "RETENTION_WITH_FINDING").contains(preservation)) {
                throw badRequest("ebsSnapshotPreservation must be NO_RETENTION or RETENTION_WITH_FINDING.");
            }
            settings.put("ebsSnapshotPreservation", preservation);
        }
        if (request.has("scanResourceCriteria")) {
            JsonNode criteria = request.get("scanResourceCriteria");
            requireObject(criteria, "scanResourceCriteria");
            ObjectNode normalized = object();
            for (String side : List.of("include", "exclude")) {
                if (!criteria.has(side)) {
                    continue;
                }
                JsonNode conditions = criteria.get(side);
                requireObject(conditions, "scanResourceCriteria." + side);
                for (Map.Entry<String, JsonNode> condition : conditions.properties()) {
                    if (!"EC2_INSTANCE_TAG".equals(condition.getKey())) {
                        throw badRequest("Unsupported scan criterion: " + condition.getKey());
                    }
                    requireObject(condition.getValue(), "scan condition");
                    JsonNode pairs = condition.getValue().get("mapEquals");
                    if (pairs == null || !pairs.isArray() || pairs.isEmpty()) {
                        throw badRequest("mapEquals must contain at least one tag condition.");
                    }
                    for (JsonNode pair : pairs) {
                        requireObject(pair, "mapEquals member");
                        String tagKey = requireText(pair, "key");
                        if (tagKey.isEmpty() || tagKey.length() > 128) {
                            throw badRequest("mapEquals key must contain 1 to 128 characters.");
                        }
                        if (pair.has("value") && (!pair.get("value").isTextual()
                                || pair.get("value").textValue().length() > 256)) {
                            throw badRequest("mapEquals value must be a string of at most 256 characters.");
                        }
                    }
                }
                normalized.set(side, conditions.deepCopy());
            }
            settings.set("scanResourceCriteria", normalized);
        }
        detector.setMalwareScanSettings(settings);
        detector.setUpdatedAt(isoTimestamp());
        detectorStore.put(key, detector);
    }

    /** Floci runs no Extended Threat Detection analysis, so a detector never has investigations. */
    public ObjectNode listInvestigations(String region, String detectorId, JsonNode request) {
        getDetector(region, detectorId);
        if (request.has("maxResults")) {
            JsonNode maxResults = request.get("maxResults");
            if (!maxResults.isIntegralNumber() || maxResults.asInt() < 1 || maxResults.asInt() > 50) {
                throw badRequest("maxResults must be between 1 and 50.");
            }
        }
        if (request.has("sortCriteria")) {
            JsonNode sort = request.get("sortCriteria");
            requireObject(sort, "sortCriteria");
            if (sort.has("orderBy") && !Set.of("ASC", "DESC").contains(requireText(sort, "orderBy"))) {
                throw badRequest("orderBy must be ASC or DESC.");
            }
        }
        decodeOffset(optionalText(request, "nextToken"), 0);
        ObjectNode response = object();
        response.putArray("investigations");
        return response;
    }

    /**
     * Aggregates GuardDuty enablement across the caller's organization. Only the enabled GuardDuty
     * delegated administrator of an organization may call this, as in AWS.
     */
    public ObjectNode getOrganizationStatistics(String region, String accountId) {
        boolean delegated = organizationAdminAccounts(region).stream()
                .anyMatch(account -> accountId.equals(account.getAdminAccountId())
                        && "ENABLED".equals(account.getAdminStatus()));
        if (!delegated) {
            throw badRequest("The request is rejected because the current account is not the GuardDuty "
                    + "delegated administrator account of an organization.");
        }
        List<OrganizationAccount> organizationAccounts;
        try {
            organizationAccounts = organizationsService == null ? List.of() : organizationsService.listAccounts(accountId);
        } catch (AwsException e) {
            organizationAccounts = List.of();
        }
        if (organizationAccounts.stream().noneMatch(account -> accountId.equals(account.getId()))) {
            throw badRequest("The request is rejected because the current account is not a member of an organization.");
        }
        Map<String, OrganizationAccount> byId = new LinkedHashMap<>();
        organizationAccounts.forEach(account -> byId.put(account.getId(), account));

        Map<String, Detector> detectorsByAccount = new LinkedHashMap<>();
        for (Detector detector : allDetectors(region)) {
            detectorsByAccount.putIfAbsent(accountIdFromServiceRole(detector.getServiceRole()), detector);
        }
        Detector adminDetector = detectorsByAccount.get(accountId);
        List<String> associated = new ArrayList<>();
        associated.add(accountId);
        if (adminDetector != null) {
            allMembers(region).stream()
                    .filter(member -> accountId.equals(member.administratorId())
                            && adminDetector.getId().equals(member.detectorId())
                            && "Enabled".equals(member.relationshipStatus())
                            && byId.containsKey(member.accountId()))
                    .map(MemberAccount::accountId)
                    .distinct()
                    .forEach(associated::add);
        }
        List<String> active = associated.stream()
                .filter(id -> "ACTIVE".equals(byId.get(id).getStatus())).toList();
        List<Detector> enabled = active.stream().map(detectorsByAccount::get)
                .filter(detector -> detector != null && "ENABLED".equals(detector.getStatus())).toList();

        Map<String, Integer> featureCounts = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> additionalCounts = new LinkedHashMap<>();
        for (Detector detector : enabled) {
            if (detector.getFeatures() == null) {
                continue;
            }
            for (DetectorFeature feature : detector.getFeatures()) {
                if (!"ENABLED".equals(feature.getStatus())) {
                    continue;
                }
                featureCounts.merge(feature.getName(), 1, Integer::sum);
                Map<String, Integer> additional =
                        additionalCounts.computeIfAbsent(feature.getName(), name -> new LinkedHashMap<>());
                if (feature.getAdditionalConfiguration() != null) {
                    for (DetectorAdditionalConfiguration configuration : feature.getAdditionalConfiguration()) {
                        if ("ENABLED".equals(configuration.getStatus())) {
                            additional.merge(configuration.getName(), 1, Integer::sum);
                        }
                    }
                }
            }
        }

        ObjectNode response = object();
        ObjectNode details = response.putObject("organizationDetails");
        details.put("updatedAt", Instant.now().getEpochSecond());
        ObjectNode statistics = details.putObject("organizationStatistics");
        statistics.put("totalAccountsCount", organizationAccounts.size());
        statistics.put("memberAccountsCount", associated.size());
        statistics.put("activeAccountsCount", active.size());
        statistics.put("enabledAccountsCount", enabled.size());
        ArrayNode byFeature = statistics.putArray("countByFeature");
        featureCounts.forEach((name, count) -> {
            ObjectNode feature = byFeature.addObject();
            feature.put("name", name);
            feature.put("enabledAccountsCount", count);
            ArrayNode additional = feature.putArray("additionalConfiguration");
            additionalCounts.getOrDefault(name, Map.of()).forEach((configuration, configurationCount) ->
                    additional.addObject().put("name", configuration).put("enabledAccountsCount", configurationCount));
        });
        return response;
    }

    private List<MemberAccount> allMembers(String region) {
        if (memberStore instanceof AccountAwareStorageBackend<MemberAccount> accountAware) {
            return accountAware.scanAllAccountEntries(key -> key.startsWith(region + "::")).stream()
                    .map(AccountAwareStorageBackend.AccountEntry::value).toList();
        }
        return memberStore.scan(key -> key.startsWith(region + "::"));
    }

    private List<Detector> allDetectors(String region) {
        List<Detector> detectors;
        if (detectorStore instanceof AccountAwareStorageBackend<Detector> accountAware) {
            detectors = accountAware.scanAllAccountEntries(key -> key.startsWith(region + "::")).stream()
                    .map(AccountAwareStorageBackend.AccountEntry::value).toList();
        } else {
            detectors = detectorStore.scan(key -> key.startsWith(region + "::"));
        }
        return detectors.stream().sorted(Comparator.comparing(Detector::getId)).toList();
    }

    private static List<JsonNode> matchingFindings(Detector detector, JsonNode request) {
        JsonNode criteria = request.path("findingCriteria");
        validateFindingCriteria(criteria);
        Comparator<JsonNode> comparator = Comparator.comparing(finding -> finding.path("id").asText());
        if (request.has("sortCriteria")) {
            JsonNode sort = request.get("sortCriteria");
            requireObject(sort, "sortCriteria");
            String field = requireText(sort, "attributeName");
            if (!Set.of("severity", "createdAt", "updatedAt", "type").contains(field)) {
                throw badRequest("Unsupported finding sort attribute: " + field);
            }
            comparator = "severity".equals(field)
                    ? Comparator.comparingDouble(finding -> finding.path(field).asDouble())
                    : Comparator.comparing(finding -> finding.path(field).asText());
            String order = requireText(sort, "orderBy");
            if (!Set.of("ASC", "DESC").contains(order)) {
                throw badRequest("orderBy must be ASC or DESC.");
            }
            if ("DESC".equals(order)) {
                comparator = comparator.reversed();
            }
            comparator = comparator.thenComparing(finding -> finding.path("id").asText());
        }
        return detector.getFindings().values().stream()
                .filter(finding -> matchesFinding(finding, criteria)).sorted(comparator).toList();
    }

    private static void validateFindingCriteria(JsonNode criteria) {
        if (criteria.isMissingNode()) {
            return;
        }
        requireObject(criteria, "findingCriteria");
        JsonNode criterion = criteria.path("criterion");
        requireObject(criterion, "criterion");
        criterion.fields().forEachRemaining(entry -> {
            if (!Set.of("id", "type", "severity", "accountId", "region", "service.archived",
                    "service.resourceRole", "resource.resourceType", "resource.instanceDetails.instanceId")
                    .contains(entry.getKey())) {
                throw badRequest("Unsupported finding criterion: " + entry.getKey());
            }
            requireObject(entry.getValue(), "condition");
            if (entry.getValue().isEmpty()) {
                throw badRequest("A finding condition must not be empty.");
            }
            entry.getValue().fields().forEachRemaining(condition -> {
                switch (condition.getKey()) {
                    case "eq", "equals", "neq", "notEquals" -> {
                        stringList(entry.getValue(), condition.getKey(), 1, 50);
                    }
                    case "gt", "greaterThan", "gte", "greaterThanOrEqual", "lt", "lessThan",
                            "lte", "lessThanOrEqual" -> {
                        if (!condition.getValue().isNumber() || !"severity".equals(entry.getKey())) {
                            throw badRequest("Numeric comparisons require a numeric severity condition.");
                        }
                    }
                    default -> throw badRequest("Unsupported finding condition: " + condition.getKey());
                }
            });
        });
    }

    private static boolean matchesFinding(JsonNode finding, JsonNode criteria) {
        for (Map.Entry<String, JsonNode> entry : criteria.path("criterion").properties()) {
            JsonNode value = finding;
            for (String part : entry.getKey().split("\\.")) {
                value = value.path(part);
            }
            for (Map.Entry<String, JsonNode> condition : entry.getValue().properties()) {
                JsonNode expected = condition.getValue();
                boolean matches = switch (condition.getKey()) {
                    case "eq", "equals" -> containsText(expected, value.asText());
                    case "neq", "notEquals" -> !containsText(expected, value.asText());
                    case "gt", "greaterThan" -> value.asDouble() > expected.asDouble();
                    case "gte", "greaterThanOrEqual" -> value.asDouble() >= expected.asDouble();
                    case "lt", "lessThan" -> value.asDouble() < expected.asDouble();
                    case "lte", "lessThanOrEqual" -> value.asDouble() <= expected.asDouble();
                    default -> false;
                };
                if (!matches) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean containsText(JsonNode values, String text) {
        for (JsonNode value : values) {
            if (text.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> stringList(JsonNode request, String field, int minimum, int maximum) {
        JsonNode values = request.get(field);
        if (values == null || !values.isArray() || values.size() < minimum || values.size() > maximum) {
            throw badRequest(field + " must contain between " + minimum + " and " + maximum + " values.");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw badRequest(field + " must contain nonempty strings.");
            }
            result.add(value.asText());
        }
        return result;
    }

    private static String optionalText(JsonNode request, String field) {
        return request.has(field) ? request.get(field).asText() : null;
    }

    private static <T> Page<T> page(List<T> items, String maxResults, String nextToken) {
        int limit = parseMaxResults(maxResults);
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + limit, items.size());
        return new Page<>(items.subList(offset, end), end < items.size() ? encodeOffset(end) : null);
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    public OrganizationConfiguration describeOrganizationConfiguration(String region, String detectorId) {
        Detector detector = getDetector(region, detectorId);
        OrganizationConfiguration configuration = detector.getOrganizationConfiguration();
        if (configuration == null) {
            return new OrganizationConfiguration(false, "NONE", List.of());
        }
        return configuration;
    }

    public synchronized void updateOrganizationConfiguration(String region, String detectorId, JsonNode request) {
        String key = storageKey(region, detectorId);
        Detector detector = detectorStore.get(key).orElseThrow(GuardDutyService::detectorNotFound);

        OrganizationConfiguration current = detector.getOrganizationConfiguration();
        boolean autoEnable = current != null && Boolean.TRUE.equals(current.getAutoEnable());
        String members = current == null ? "NONE" : current.getAutoEnableOrganizationMembers();
        List<OrganizationFeature> features = current == null ? List.of() : current.getFeatures();

        if (request.has("autoEnableOrganizationMembers")) {
            members = requireText(request, "autoEnableOrganizationMembers");
            if (!ORG_AUTO_ENABLE_VALUES.contains(members)) {
                throw badRequest("autoEnableOrganizationMembers must be one of NEW, ALL, or NONE.");
            }
            autoEnable = !"NONE".equals(members);
        } else if (request.has("autoEnable")) {
            autoEnable = requireBoolean(request, "autoEnable");
            members = autoEnable ? "NEW" : "NONE";
        }
        if (request.has("features")) {
            features = mergeOrganizationFeatures(features, readOrganizationFeatures(request));
        }

        detector.setOrganizationConfiguration(new OrganizationConfiguration(autoEnable, members, features));
        detectorStore.put(key, detector);
    }

    public synchronized void enableOrganizationAdminAccount(String region, JsonNode request) {
        String adminAccountId = readAdminAccountId(request);
        List<AdminAccount> existing = organizationAdminAccounts(region);
        if (!existing.isEmpty() && !existing.get(0).getAdminAccountId().equals(adminAccountId)) {
            throw badRequest("The request is rejected because the organization already has a "
                    + "delegated administrator account for GuardDuty.");
        }
        adminAccountStore.put(storageKey(region, adminAccountId), new AdminAccount(adminAccountId, "ENABLED"));
    }

    public synchronized void disableOrganizationAdminAccount(String region, JsonNode request) {
        String adminAccountId = readAdminAccountId(request);
        String key = storageKey(region, adminAccountId);
        if (adminAccountStore.get(key).isEmpty()) {
            throw badRequest(ADMIN_ALREADY_DISABLED_MESSAGE);
        }
        adminAccountStore.delete(key);
    }

    public Page<AdminAccount> listOrganizationAdminAccounts(
            String region, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        List<AdminAccount> accounts = new ArrayList<>(organizationAdminAccounts(region));
        accounts.sort(Comparator.comparing(AdminAccount::getAdminAccountId));

        int offset = decodeOffset(nextToken, accounts.size());
        int end = Math.min(offset + maxResults, accounts.size());
        String responseToken = end < accounts.size() ? encodeOffset(end) : null;
        return new Page<>(accounts.subList(offset, end), responseToken);
    }

    public synchronized void createMembers(String region, String detectorId, JsonNode request) {
        Detector detector = getDetector(region, detectorId);
        JsonNode details = request.get("accountDetails");
        if (details == null || !details.isArray() || details.size() < 1 || details.size() > 50) {
            throw badRequest("accountDetails must contain between 1 and 50 accounts.");
        }
        String administratorId = accountIdFromServiceRole(detector.getServiceRole());
        boolean organizationDelegatedAdministrator = organizationAdminAccounts(region).stream()
                .anyMatch(account -> administratorId.equals(account.getAdminAccountId())
                        && "ENABLED".equals(account.getAdminStatus()));
        String relationshipStatus = organizationDelegatedAdministrator ? "Enabled" : "Created";
        String now = Instant.now().toString();
        List<MemberWrite> writes = new ArrayList<>(details.size());
        for (JsonNode detail : details) {
            String accountId = requireText(detail, "accountId");
            if (!ACCOUNT_ID_PATTERN.matcher(accountId).matches()) {
                throw badRequest("accountId must be a 12-digit account ID.");
            }
            String email = requireText(detail, "email");
            if (!validMemberEmail(email)) {
                throw badRequest("email must be a valid GuardDuty member email address.");
            }
            String key = region + "::" + detectorId + "::" + accountId;
            MemberAccount existing = memberStore.get(key).orElse(null);
            writes.add(new MemberWrite(key, new MemberAccount(
                    accountId,
                    email,
                    existing == null ? relationshipStatus : existing.relationshipStatus(),
                    administratorId,
                    detectorId,
                    existing == null ? null : existing.invitedAt(),
                    now,
                    existing == null ? null : existing.invitationId())));
        }
        for (MemberWrite write : writes) {
            memberStore.put(write.key(), write.member());
        }
    }

    public Page<MemberAccount> listMembers(String region, String detectorId, String maxResults,
                                           String nextToken, String onlyAssociated) {
        Detector detector = getDetector(region, detectorId);
        int limit = parseMaxResults(maxResults);
        if (onlyAssociated != null && !onlyAssociated.equalsIgnoreCase("true")
                && !onlyAssociated.equalsIgnoreCase("false")) {
            throw badRequest("onlyAssociated must be true or false.");
        }
        String prefix = region + "::" + detectorId + "::";
        List<MemberAccount> members = memberStore.scan(key -> key.startsWith(prefix)).stream()
                .filter(member -> !"true".equalsIgnoreCase(onlyAssociated)
                        || isAssociatedRelationship(member.relationshipStatus()))
                .sorted(Comparator.comparing(MemberAccount::accountId)).toList();
        int offset = decodeOffset(nextToken, members.size());
        int end = Math.min(members.size(), offset + limit);
        return new Page<>(members.subList(offset, end), end < members.size() ? encodeOffset(end) : null);
    }

    public List<MemberAccount> listMembers(String region, String detectorId) {
        return listMembers(region, detectorId, null, null, null).items();
    }

    private static boolean isAssociatedRelationship(String status) {
        return "Enabled".equals(status) || "Invited".equals(status) || "EmailVerificationInProgress".equals(status);
    }

    private static boolean validMemberEmail(String email) {
        if (email == null || email.length() < 6 || email.length() > 64 || !email.chars().allMatch(ch -> ch < 128)) {
            return false;
        }
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
            return false;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        if (local.startsWith(".") || local.matches(".*[\\s\"'()<>\\[\\]:,\\\\|%&].*")) {
            return false;
        }
        if (!domain.matches("[A-Za-z0-9.-]+") || !domain.contains(".")
                || domain.startsWith(".") || domain.endsWith(".") || domain.startsWith("-") || domain.endsWith("-")) {
            return false;
        }
        return true;
    }
    public Map<String, String> listTags(String arn) {
        DetectorRef ref = parseDetectorArn(arn);
        Detector detector = detectorFromArn(arn);
        if (ref.resourceKey() == null) {
            return detector.getTags() == null ? Map.of() : detector.getTags();
        }
        JsonNode resource = detector.getResources().get(ref.resourceKey());
        if (resource == null) {
            throw badRequest("The requested detector resource does not exist.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        resource.path("tags").fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        return tags;
    }

    public synchronized void tagResource(String arn, Map<String, String> tags) {
        Map<String, String> merged = new LinkedHashMap<>(listTags(arn));
        merged.putAll(tags);
        if (merged.size() > 50) {
            throw badRequest("A resource may have at most 50 tags.");
        }
        saveTags(arn, merged);
    }

    public synchronized void untagResource(String arn, List<String> tagKeys) {
        Map<String, String> remaining = new LinkedHashMap<>(listTags(arn));
        tagKeys.forEach(remaining::remove);
        saveTags(arn, remaining);
    }

    private void saveTags(String arn, Map<String, String> tags) {
        DetectorRef ref = parseDetectorArn(arn);
        Detector detector = detectorFromArn(arn);
        if (ref.resourceKey() == null) {
            detector.setTags(tags);
            detectorStore.put(ref.key(), detector);
        } else {
            ObjectNode resource = (ObjectNode) detector.getResources().get(ref.resourceKey()).deepCopy();
            ObjectNode tagNode = resource.putObject("tags");
            tags.forEach(tagNode::put);
            saveResource(ref.region(), detector, ref.resourceKey(), resource);
        }
    }

    private Detector detectorFromArn(String arn) {
        DetectorRef ref = parseDetectorArn(arn);
        Detector detector = detectorStore.get(ref.key()).orElseThrow(GuardDutyService::detectorNotFound);
        if (!ref.accountId().equals(accountIdFromServiceRole(detector.getServiceRole()))) {
            throw detectorNotFound();
        }
        return detector;
    }

    private static DetectorRef parseDetectorArn(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw badRequest("The request is rejected because an invalid resource ARN is specified: " + arn);
        }
        String prefix = "detector/";
        String resource = parsed.resource();
        if (!resource.startsWith(prefix) || resource.length() == prefix.length()) {
            throw badRequest("The request is rejected because an invalid resource ARN is specified: " + arn);
        }
        String[] parts = resource.split("/", -1);
        if (!"guardduty".equals(parsed.service()) || !ACCOUNT_ID_PATTERN.matcher(parsed.accountId()).matches()
                || (parts.length != 2 && parts.length != 4) || parts[1].isBlank()
                || (parts.length == 4 && (!Set.of("filter", "ipset", "threatintelset").contains(parts[2])
                || parts[3].isBlank()))) {
            throw badRequest("The request contains an invalid GuardDuty resource ARN.");
        }
        return new DetectorRef(parsed.region(), parts[1], parsed.accountId(),
                parts.length == 4 ? parts[2] + "/" + parts[3] : null);
    }

    private static List<DetectorFeature> mergeDetectorFeatures(
            List<DetectorFeature> current, List<DetectorFeature> submitted) {
        List<DetectorFeature> merged = current == null ? new ArrayList<>() : new ArrayList<>(current);
        for (DetectorFeature update : submitted) {
            DetectorFeature existing = merged.stream()
                    .filter(feature -> feature.getName().equals(update.getName()))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                merged.add(update);
            } else {
                existing.setStatus(update.getStatus());
                existing.setUpdatedAt(update.getUpdatedAt());
                if (update.getAdditionalConfiguration() != null) {
                    existing.setAdditionalConfiguration(update.getAdditionalConfiguration());
                }
            }
        }
        return merged;
    }

    private static List<OrganizationFeature> mergeOrganizationFeatures(
            List<OrganizationFeature> current, List<OrganizationFeature> submitted) {
        List<OrganizationFeature> merged = current == null ? new ArrayList<>() : new ArrayList<>(current);
        for (OrganizationFeature update : submitted) {
            OrganizationFeature existing = merged.stream()
                    .filter(feature -> feature.getName().equals(update.getName()))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                merged.add(update);
            } else {
                existing.setAutoEnable(update.getAutoEnable());
                if (update.getAdditionalConfiguration() != null) {
                    existing.setAdditionalConfiguration(update.getAdditionalConfiguration());
                }
            }
        }
        return merged;
    }

    private static List<DetectorFeature> readDetectorFeatures(JsonNode request) {
        if (!request.has("features")) {
            return null;
        }
        JsonNode featuresNode = request.get("features");
        if (!featuresNode.isArray()) {
            throw badRequest("features must be an array.");
        }
        long now = Instant.now().getEpochSecond();
        List<DetectorFeature> features = new ArrayList<>(featuresNode.size());
        for (JsonNode featureNode : featuresNode) {
            requireObject(featureNode, "features member");
            String name = requireText(featureNode, "name");
            if (!DETECTOR_FEATURE_NAMES.contains(name)) {
                throw badRequest("features contains an unsupported feature name: " + name);
            }
            String status = requireText(featureNode, "status");
            if (!FEATURE_STATUSES.contains(status)) {
                throw badRequest("features contains an invalid status: " + status);
            }
            features.add(new DetectorFeature(
                    name, status, now, readDetectorAdditionalConfiguration(featureNode, now)));
        }
        return features;
    }

    private static List<DetectorAdditionalConfiguration> readDetectorAdditionalConfiguration(
            JsonNode featureNode, long now) {
        if (!featureNode.has("additionalConfiguration")) {
            return null;
        }
        JsonNode configurationNode = featureNode.get("additionalConfiguration");
        if (!configurationNode.isArray()) {
            throw badRequest("additionalConfiguration must be an array.");
        }
        List<DetectorAdditionalConfiguration> configurations = new ArrayList<>(configurationNode.size());
        for (JsonNode node : configurationNode) {
            requireObject(node, "additionalConfiguration member");
            String name = requireText(node, "name");
            if (!ADDITIONAL_CONFIGURATION_NAMES.contains(name)) {
                throw badRequest("additionalConfiguration contains an unsupported name: " + name);
            }
            String status = requireText(node, "status");
            if (!FEATURE_STATUSES.contains(status)) {
                throw badRequest("additionalConfiguration contains an invalid status: " + status);
            }
            configurations.add(new DetectorAdditionalConfiguration(name, status, now));
        }
        return configurations;
    }

    private static List<OrganizationFeature> readOrganizationFeatures(JsonNode request) {
        JsonNode featuresNode = request.get("features");
        if (!featuresNode.isArray()) {
            throw badRequest("features must be an array.");
        }
        List<OrganizationFeature> features = new ArrayList<>(featuresNode.size());
        for (JsonNode featureNode : featuresNode) {
            requireObject(featureNode, "features member");
            String name = requireText(featureNode, "name");
            if (!ORG_FEATURE_NAMES.contains(name)) {
                throw badRequest("features contains an unsupported feature name: " + name);
            }
            String autoEnable = requireText(featureNode, "autoEnable");
            if (!ORG_AUTO_ENABLE_VALUES.contains(autoEnable)) {
                throw badRequest("features contains an invalid autoEnable value: " + autoEnable);
            }
            features.add(new OrganizationFeature(
                    name, autoEnable, readOrganizationAdditionalConfiguration(featureNode)));
        }
        return features;
    }

    private static List<OrganizationAdditionalConfiguration> readOrganizationAdditionalConfiguration(
            JsonNode featureNode) {
        if (!featureNode.has("additionalConfiguration")) {
            return null;
        }
        JsonNode configurationNode = featureNode.get("additionalConfiguration");
        if (!configurationNode.isArray()) {
            throw badRequest("additionalConfiguration must be an array.");
        }
        List<OrganizationAdditionalConfiguration> configurations = new ArrayList<>(configurationNode.size());
        for (JsonNode node : configurationNode) {
            requireObject(node, "additionalConfiguration member");
            String name = requireText(node, "name");
            if (!ADDITIONAL_CONFIGURATION_NAMES.contains(name)) {
                throw badRequest("additionalConfiguration contains an unsupported name: " + name);
            }
            String autoEnable = requireText(node, "autoEnable");
            if (!ORG_AUTO_ENABLE_VALUES.contains(autoEnable)) {
                throw badRequest("additionalConfiguration contains an invalid autoEnable value: " + autoEnable);
            }
            configurations.add(new OrganizationAdditionalConfiguration(name, autoEnable));
        }
        return configurations;
    }

    private static String readFindingPublishingFrequency(JsonNode request, String defaultValue) {
        if (!request.has("findingPublishingFrequency")) {
            return defaultValue;
        }
        String frequency = requireText(request, "findingPublishingFrequency");
        if (!FINDING_PUBLISHING_FREQUENCIES.contains(frequency)) {
            throw badRequest("findingPublishingFrequency must be one of FIFTEEN_MINUTES, ONE_HOUR, or SIX_HOURS.");
        }
        return frequency;
    }

    private static Map<String, String> readTags(JsonNode request) {
        if (!request.has("tags")) {
            return null;
        }
        JsonNode tagsNode = request.get("tags");
        if (!tagsNode.isObject() || tagsNode.size() > 50) {
            throw badRequest("tags must be an object with at most 50 entries.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode valueNode = entry.getValue();
            if (!valueNode.isTextual()) {
                throw badRequest("tags contains a non-string value.");
            }
            tags.put(entry.getKey(), valueNode.textValue());
        });
        return tags;
    }

    private List<AdminAccount> organizationAdminAccounts(String region) {
        String prefix = region + "::";
        if (adminAccountStore instanceof AccountAwareStorageBackend<AdminAccount> accountAware) {
            return accountAware.scanAllAccountEntries(key -> key.startsWith(prefix)).stream()
                    .map(AccountAwareStorageBackend.AccountEntry::value)
                    .toList();
        }
        return adminAccountStore.scan(key -> key.startsWith(prefix));
    }

    private static String readAdminAccountId(JsonNode request) {
        String adminAccountId = requireText(request, "adminAccountId");
        if (!ACCOUNT_ID_PATTERN.matcher(adminAccountId).matches()) {
            throw badRequest("adminAccountId must be a 12-digit account ID.");
        }
        return adminAccountId;
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static String serviceRoleArn(String accountId) {
        return "arn:aws:iam::" + accountId
                + ":role/aws-service-role/guardduty.amazonaws.com/AWSServiceRoleForAmazonGuardDuty";
    }

    private static String isoTimestamp() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw badRequest(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw badRequest(field + " must be a string.");
        }
        return value.textValue();
    }

    private static boolean requireBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw badRequest(field + " must be a boolean.");
        }
        return value.booleanValue();
    }

    private static int parseMaxResults(String value) {
        if (value == null) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > MAX_RESULTS) {
                throw badRequest("maxResults must be between 1 and " + MAX_RESULTS + ".");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw badRequest("maxResults must be an integer between 1 and " + MAX_RESULTS + ".");
        }
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw badRequest("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset >= resultSize) {
                throw badRequest("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw badRequest("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static AwsException detectorNotFound() {
        return badRequest(DETECTOR_NOT_FOUND_MESSAGE);
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    private static String accountIdFromServiceRole(String serviceRole) {
        if (serviceRole == null) {
            return "000000000000";
        }
        String[] parts = serviceRole.split(":", 6);
        return parts.length > 4 && ACCOUNT_ID_PATTERN.matcher(parts[4]).matches() ? parts[4] : "000000000000";
    }


    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    private record DetectorRef(String region, String detectorId, String accountId, String resourceKey) {
        String key() {
            return storageKey(region, detectorId);
        }
    }

    private record MemberWrite(String key, MemberAccount member) {
    }
}
