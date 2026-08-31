package io.github.hectorvent.floci.services.guardduty;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.guardduty.model.Detector;
import io.github.hectorvent.floci.services.guardduty.model.Filter;
import io.github.hectorvent.floci.services.guardduty.model.Finding;
import io.github.hectorvent.floci.services.guardduty.model.IpSet;
import io.github.hectorvent.floci.services.guardduty.model.ThreatIntelSet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Amazon GuardDuty restJson1: one detector per account/region, sample findings,
 * malware-scan settings, and organization/invitation read APIs.
 */
@ApplicationScoped
public class GuardDutyService implements TagHandler {

    static final String SERVICE = "guardduty";
    private static final String DEFAULT_SAMPLE_TYPE = "Recon:EC2/PortProbeUnprotectedPort";
    private static final Set<String> FREQUENCIES = Set.of("FIFTEEN_MINUTES", "ONE_HOUR", "SIX_HOURS");
    private static final String DETECTOR_EXISTS =
            "The request is rejected because a detector already exists for the current account.";
    private static final String DETECTOR_NOT_OWNED =
            "The request is rejected because the input detectorId is not owned by the current account.";
    private static final String RESOURCE_NOT_FOUND =
            "The request is rejected because the specified resource cannot be found.";
    private static final String FILTER_EXISTS =
            "The request is rejected because a filter with the specified name already exists.";
    private static final Set<String> FILTER_ACTIONS = Set.of("NOOP", "ARCHIVE");
    private static final Set<String> LIST_FORMATS =
            Set.of("TXT", "STIX", "OTX_CSV", "ALIEN_VAULT", "PROOF_POINT", "FIRE_EYE");

    private final StorageBackend<String, Detector> detectors;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    public GuardDutyService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("guardduty", "guardduty-detectors.json",
                        new TypeReference<Map<String, Detector>>() {
                        }),
                regionResolver);
    }

    GuardDutyService(StorageBackend<String, Detector> detectors, RegionResolver regionResolver) {
        this.detectors = detectors;
        this.regionResolver = regionResolver;
    }

    public Optional<Detector> findDetector(String region) {
        return detectors.get(region);
    }

    public List<String> listDetectorIds(String region) {
        return detectors.get(region).map(d -> List.of(d.getDetectorId())).orElseGet(List::of);
    }

    public synchronized Detector createDetector(String region, JsonNode request) {
        requireObject(request, "Request body");
        if (detectors.get(region).isPresent()) {
            throw badRequest(DETECTOR_EXISTS);
        }
        boolean enable = !request.hasNonNull("enable") || request.get("enable").asBoolean();
        String frequency = textOrNull(request, "findingPublishingFrequency");
        if (frequency == null) {
            frequency = "SIX_HOURS";
        }
        requireFrequency(frequency);
        String now = now();
        String detectorId = newId();
        String account = regionResolver.getAccountId();
        Detector detector = new Detector();
        detector.setDetectorId(detectorId);
        detector.setArn(detectorArn(region, account, detectorId));
        detector.setCreatedAt(now);
        detector.setUpdatedAt(now);
        detector.setStatus(enable ? "ENABLED" : "DISABLED");
        detector.setFindingPublishingFrequency(frequency);
        detector.setServiceRole("arn:aws:iam::" + account
                + ":role/aws-service-role/guardduty.amazonaws.com/AWSServiceRoleForAmazonGuardDuty");
        detector.setTags(readTags(request.get("tags")));
        detectors.put(region, detector);
        return detector;
    }

    public Detector getDetector(String region, String detectorId) {
        return requireDetector(region, detectorId);
    }

    public synchronized void updateDetector(String region, String detectorId, JsonNode request) {
        Detector detector = requireDetector(region, detectorId);
        requireObject(request, "Request body");
        if (request.hasNonNull("enable")) {
            detector.setStatus(request.get("enable").asBoolean() ? "ENABLED" : "DISABLED");
        }
        String frequency = textOrNull(request, "findingPublishingFrequency");
        if (frequency != null) {
            requireFrequency(frequency);
            detector.setFindingPublishingFrequency(frequency);
        }
        detector.setUpdatedAt(now());
        detectors.put(region, detector);
    }

    public synchronized void deleteDetector(String region, String detectorId) {
        requireDetector(region, detectorId);
        detectors.delete(region);
    }

    public List<String> listFilterNames(String region, String detectorId) {
        return new ArrayList<>(requireDetector(region, detectorId).getFilters().keySet());
    }

    public Filter getFilter(String region, String detectorId, String filterName) {
        return requireFilter(requireDetector(region, detectorId), filterName);
    }

    public synchronized Filter createFilter(String region, String detectorId, JsonNode request) {
        Detector detector = requireDetector(region, detectorId);
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        if (detector.getFilters().containsKey(name)) {
            throw badRequest(FILTER_EXISTS);
        }
        Filter filter = new Filter();
        filter.setName(name);
        filter.setDescription(textOrNull(request, "description"));
        filter.setAction(requireAction(textOrNull(request, "action"), "NOOP"));
        filter.setRank(readRank(request, detector.getFilters().size() + 1));
        filter.setFindingCriteria(readCriteria(request.get("findingCriteria")));
        filter.setTags(readTags(request.get("tags")));
        detector.getFilters().put(name, filter);
        detector.setUpdatedAt(now());
        detectors.put(region, detector);
        return filter;
    }

    public synchronized Filter updateFilter(String region, String detectorId, String filterName, JsonNode request) {
        Detector detector = requireDetector(region, detectorId);
        Filter filter = requireFilter(detector, filterName);
        requireObject(request, "Request body");
        String description = textOrNull(request, "description");
        if (description != null) {
            filter.setDescription(description);
        }
        if (request.hasNonNull("action")) {
            filter.setAction(requireAction(request.get("action").asText(), filter.getAction()));
        }
        if (request.hasNonNull("rank")) {
            filter.setRank(readRank(request, filter.getRank() == null ? 1 : filter.getRank()));
        }
        if (request.has("findingCriteria") && !request.get("findingCriteria").isNull()) {
            filter.setFindingCriteria(readCriteria(request.get("findingCriteria")));
        }
        detector.setUpdatedAt(now());
        detectors.put(region, detector);
        return filter;
    }

    public synchronized void deleteFilter(String region, String detectorId, String filterName) {
        Detector detector = requireDetector(region, detectorId);
        if (detector.getFilters().remove(decode(filterName)) == null) {
            throw badRequest(RESOURCE_NOT_FOUND);
        }
        detector.setUpdatedAt(now());
        detectors.put(region, detector);
    }

    public List<String> listIpSetIds(String region, String detectorId) {
        return new ArrayList<>(requireDetector(region, detectorId).getIpSets().keySet());
    }

    public IpSet getIpSet(String region, String detectorId, String ipSetId) {
        return requireIpSet(requireDetector(region, detectorId), ipSetId);
    }

    public synchronized IpSet createIpSet(String region, String detectorId, JsonNode request) {
        Detector detector = requireDetector(region, detectorId);
        requireObject(request, "Request body");
        IpSet ipSet = new IpSet();
        ipSet.setIpSetId(newId());
        ipSet.setName(optionalName(request, ipSet.getIpSetId()));
        ipSet.setFormat(requireFormat(request));
        ipSet.setLocation(requireLocation(request));
        ipSet.setExpectedBucketOwner(textOrNull(request, "expectedBucketOwner"));
        ipSet.setStatus(statusForActivate(readActivate(request, true)));
        ipSet.setTags(readTags(request.get("tags")));
        detector.getIpSets().put(ipSet.getIpSetId(), ipSet);
        detector.setUpdatedAt(now());
        detectors.put(region, detector);
        return ipSet;
    }

    public synchronized IpSet updateIpSet(String region, String detectorId, String ipSetId, JsonNode request) {
        Detector detector = requireDetector(region, detectorId);
        IpSet ipSet = requireIpSet(detector, ipSetId);
        requireObject(request, "Request body");
        String name = textOrNull(request, "name");
        if (name != null) {
            ipSet.setName(name);
        }
        String location = textOrNull(request, "location");
        if (location != null) {
            ipSet.setLocation(location);
        }
        String owner = textOrNull(request, "expectedBucketOwner");
        if (owner != null) {
            ipSet.setExpectedBucketOwner(owner);
        }
        if (request.hasNonNull("activate")) {
            ipSet.setStatus(statusForActivate(request.get("activate").asBoolean()));
        }
        detector.setUpdatedAt(now());
        detectors.put(region, detector);
        return ipSet;
    }

    public synchronized void deleteIpSet(String region, String detectorId, String ipSetId) {
        Detector detector = requireDetector(region, detectorId);
        if (detector.getIpSets().remove(decode(ipSetId)) == null) {
            throw badRequest(RESOURCE_NOT_FOUND);
        }
        detector.setUpdatedAt(now());
        detectors.put(region, detector);
    }

    public List<String> listThreatIntelSetIds(String region, String detectorId) {
        return new ArrayList<>(requireDetector(region, detectorId).getThreatIntelSets().keySet());
    }

    public ThreatIntelSet getThreatIntelSet(String region, String detectorId, String threatIntelSetId) {
        return requireThreatIntelSet(requireDetector(region, detectorId), threatIntelSetId);
    }

    public synchronized ThreatIntelSet createThreatIntelSet(String region, String detectorId, JsonNode request) {
        Detector detector = requireDetector(region, detectorId);
        requireObject(request, "Request body");
        ThreatIntelSet set = new ThreatIntelSet();
        set.setThreatIntelSetId(newId());
        set.setName(optionalName(request, set.getThreatIntelSetId()));
        set.setFormat(requireFormat(request));
        set.setLocation(requireLocation(request));
        set.setExpectedBucketOwner(textOrNull(request, "expectedBucketOwner"));
        set.setStatus(statusForActivate(readActivate(request, true)));
        set.setTags(readTags(request.get("tags")));
        detector.getThreatIntelSets().put(set.getThreatIntelSetId(), set);
        detector.setUpdatedAt(now());
        detectors.put(region, detector);
        return set;
    }

    public synchronized ThreatIntelSet updateThreatIntelSet(
            String region, String detectorId, String threatIntelSetId, JsonNode request) {
        Detector detector = requireDetector(region, detectorId);
        ThreatIntelSet set = requireThreatIntelSet(detector, threatIntelSetId);
        requireObject(request, "Request body");
        String name = textOrNull(request, "name");
        if (name != null) {
            set.setName(name);
        }
        String location = textOrNull(request, "location");
        if (location != null) {
            set.setLocation(location);
        }
        String owner = textOrNull(request, "expectedBucketOwner");
        if (owner != null) {
            set.setExpectedBucketOwner(owner);
        }
        if (request.hasNonNull("activate")) {
            set.setStatus(statusForActivate(request.get("activate").asBoolean()));
        }
        detector.setUpdatedAt(now());
        detectors.put(region, detector);
        return set;
    }

    public synchronized void deleteThreatIntelSet(String region, String detectorId, String threatIntelSetId) {
        Detector detector = requireDetector(region, detectorId);
        if (detector.getThreatIntelSets().remove(decode(threatIntelSetId)) == null) {
            throw badRequest(RESOURCE_NOT_FOUND);
        }
        detector.setUpdatedAt(now());
        detectors.put(region, detector);
    }

    public synchronized List<Finding> createSampleFindings(String region, String detectorId, JsonNode request) {
        Detector detector = requireDetector(region, detectorId);
        List<String> types = readStringList(request, "findingTypes");
        if (types.isEmpty()) {
            types = List.of(DEFAULT_SAMPLE_TYPE);
        }
        String now = now();
        String account = regionResolver.getAccountId();
        List<Finding> created = new ArrayList<>();
        for (String type : types) {
            Finding finding = new Finding();
            finding.setId(newId());
            finding.setType(type);
            finding.setTitle(titleFor(type));
            finding.setSeverity(2);
            finding.setArchived(false);
            finding.setCreatedAt(now);
            finding.setUpdatedAt(now);
            finding.setAccountId(account);
            finding.setRegion(region);
            finding.setDetectorId(detectorId);
            detector.getFindings().put(finding.getId(), finding);
            created.add(finding);
        }
        detector.setUpdatedAt(now);
        detectors.put(region, detector);
        return created;
    }

    public List<Finding> listFindings(String region, String detectorId) {
        Detector detector = requireDetector(region, detectorId);
        return new ArrayList<>(detector.getFindings().values());
    }

    public List<Finding> getFindings(String region, String detectorId, JsonNode request) {
        Detector detector = requireDetector(region, detectorId);
        List<String> ids = readStringList(request, "findingIds");
        List<Finding> findings = new ArrayList<>();
        for (String id : ids) {
            Finding finding = detector.getFindings().get(id);
            if (finding != null) {
                findings.add(finding);
            }
        }
        return findings;
    }

    public Map<String, Integer> findingsCountBySeverity(String region, String detectorId) {
        Detector detector = requireDetector(region, detectorId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Finding finding : detector.getFindings().values()) {
            String key = Integer.toString((int) finding.getSeverity());
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    public synchronized int archiveFindings(String region, String detectorId, JsonNode request) {
        Detector detector = requireDetector(region, detectorId);
        List<String> ids = readStringList(request, "findingIds");
        int archived = 0;
        String now = now();
        for (String id : ids) {
            Finding finding = detector.getFindings().get(id);
            if (finding != null) {
                finding.setArchived(true);
                finding.setUpdatedAt(now);
                archived++;
            }
        }
        detector.setUpdatedAt(now);
        detectors.put(region, detector);
        return archived;
    }

    public synchronized int unarchiveFindings(String region, String detectorId, JsonNode request) {
        Detector detector = requireDetector(region, detectorId);
        List<String> ids = readStringList(request, "findingIds");
        int restored = 0;
        String now = now();
        for (String id : ids) {
            Finding finding = detector.getFindings().get(id);
            if (finding != null) {
                finding.setArchived(false);
                finding.setUpdatedAt(now);
                restored++;
            }
        }
        detector.setUpdatedAt(now);
        detectors.put(region, detector);
        return restored;
    }

    public Detector requireDetector(String region, String detectorId) {
        if (detectorId == null || detectorId.isBlank()) {
            throw badRequest("detectorId is a required parameter.");
        }
        Detector detector = detectors.get(region).orElseThrow(() -> badRequest(DETECTOR_NOT_OWNED));
        if (!detectorId.equals(detector.getDetectorId())) {
            throw badRequest(DETECTOR_NOT_OWNED);
        }
        return detector;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Tagged tagged = requireTagged(region, arn);
        return Map.copyOf(tagged.tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(region, arn);
        if (tags != null) {
            tagged.tags().putAll(tags);
        }
        tagged.detector().setUpdatedAt(now());
        detectors.put(region, tagged.detector());
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(region, arn);
        if (tagKeys != null) {
            tagKeys.forEach(tagged.tags()::remove);
        }
        tagged.detector().setUpdatedAt(now());
        detectors.put(region, tagged.detector());
    }

    private Tagged requireTagged(String region, String arn) {
        ResourceRef ref = parseResourceArn(arn);
        Detector detector = requireDetector(region, ref.detectorId());
        return switch (ref.kind()) {
            case DETECTOR -> new Tagged(detector, detector.getTags());
            case FILTER -> new Tagged(detector, requireFilter(detector, ref.childId()).getTags());
            case IPSET -> new Tagged(detector, requireIpSet(detector, ref.childId()).getTags());
            case THREAT_INTEL_SET -> new Tagged(detector, requireThreatIntelSet(detector, ref.childId()).getTags());
        };
    }

    private String detectorArn(String region, String accountId, String detectorId) {
        return AwsArnUtils.Arn.of(SERVICE, region, accountId, "detector/" + detectorId).toString();
    }

    private static ResourceRef parseResourceArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw badRequest("resourceArn is a required parameter.");
        }
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decode(arn));
        } catch (IllegalArgumentException e) {
            throw badRequest("resourceArn is invalid.");
        }
        if (!SERVICE.equals(parsed.service()) || !parsed.resource().startsWith("detector/")) {
            throw badRequest("resourceArn is invalid.");
        }
        String resource = parsed.resource().substring("detector/".length());
        if (resource.isBlank()) {
            throw badRequest("resourceArn is invalid.");
        }
        int slash = resource.indexOf('/');
        if (slash < 0) {
            return new ResourceRef(resource, ResourceKind.DETECTOR, null);
        }
        String detectorId = resource.substring(0, slash);
        String remainder = resource.substring(slash + 1);
        if (remainder.startsWith("filter/")) {
            String name = remainder.substring("filter/".length());
            if (name.isBlank()) {
                throw badRequest("resourceArn is invalid.");
            }
            return new ResourceRef(detectorId, ResourceKind.FILTER, decode(name));
        }
        if (remainder.startsWith("ipset/")) {
            String id = remainder.substring("ipset/".length());
            if (id.isBlank() || id.contains("/")) {
                throw badRequest("resourceArn is invalid.");
            }
            return new ResourceRef(detectorId, ResourceKind.IPSET, id);
        }
        if (remainder.startsWith("threatintelset/")) {
            String id = remainder.substring("threatintelset/".length());
            if (id.isBlank() || id.contains("/")) {
                throw badRequest("resourceArn is invalid.");
            }
            return new ResourceRef(detectorId, ResourceKind.THREAT_INTEL_SET, id);
        }
        throw badRequest("resourceArn is invalid.");
    }

    private static Filter requireFilter(Detector detector, String filterName) {
        String name = decode(filterName);
        if (name == null || name.isBlank()) {
            throw badRequest("filterName is a required parameter.");
        }
        Filter filter = detector.getFilters().get(name);
        if (filter == null) {
            throw badRequest(RESOURCE_NOT_FOUND);
        }
        return filter;
    }

    private static IpSet requireIpSet(Detector detector, String ipSetId) {
        String id = decode(ipSetId);
        if (id == null || id.isBlank()) {
            throw badRequest("ipSetId is a required parameter.");
        }
        IpSet ipSet = detector.getIpSets().get(id);
        if (ipSet == null) {
            throw badRequest(RESOURCE_NOT_FOUND);
        }
        return ipSet;
    }

    private static ThreatIntelSet requireThreatIntelSet(Detector detector, String threatIntelSetId) {
        String id = decode(threatIntelSetId);
        if (id == null || id.isBlank()) {
            throw badRequest("threatIntelSetId is a required parameter.");
        }
        ThreatIntelSet set = detector.getThreatIntelSets().get(id);
        if (set == null) {
            throw badRequest(RESOURCE_NOT_FOUND);
        }
        return set;
    }

    private Map<String, Object> readCriteria(JsonNode node) {
        if (node == null || node.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!node.isObject()) {
            throw badRequest("findingCriteria must be an object.");
        }
        return objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw badRequest(field + " is a required parameter.");
        }
        return value;
    }

    private static String requireAction(String action, String fallback) {
        if (action == null || action.isBlank()) {
            return fallback;
        }
        if (!FILTER_ACTIONS.contains(action)) {
            throw badRequest("action is invalid.");
        }
        return action;
    }

    private static int readRank(JsonNode request, int fallback) {
        if (request == null || !request.hasNonNull("rank")) {
            return fallback;
        }
        JsonNode rank = request.get("rank");
        if (!rank.isNumber() || rank.asInt() < 1) {
            throw badRequest("rank is invalid.");
        }
        return rank.asInt();
    }

    private static String requireFormat(JsonNode request) {
        String format = textOrNull(request, "format");
        if (format == null) {
            throw badRequest("format is a required parameter.");
        }
        if (!LIST_FORMATS.contains(format)) {
            throw badRequest("format is invalid.");
        }
        return format;
    }

    private static String requireLocation(JsonNode request) {
        String location = textOrNull(request, "location");
        if (location == null) {
            throw badRequest("location is a required parameter.");
        }
        return location;
    }

    private static String optionalName(JsonNode request, String fallback) {
        String name = textOrNull(request, "name");
        return name == null ? fallback : name;
    }

    private static boolean readActivate(JsonNode request, boolean fallback) {
        if (request == null || !request.hasNonNull("activate")) {
            return fallback;
        }
        return request.get("activate").asBoolean();
    }

    private static String statusForActivate(boolean activate) {
        return activate ? "ACTIVE" : "INACTIVE";
    }

    static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private record ResourceRef(String detectorId, ResourceKind kind, String childId) {
    }

    private record Tagged(Detector detector, Map<String, String> tags) {
    }

    private enum ResourceKind {
        DETECTOR,
        FILTER,
        IPSET,
        THREAT_INTEL_SET
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isObject()) {
            throw badRequest("tags must be a map.");
        }
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            tags.put(entry.getKey(), value == null || value.isNull() ? "" : value.asText());
        });
        return tags;
    }

    private static List<String> readStringList(JsonNode request, String field) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return List.of();
        }
        JsonNode node = request.get(field);
        if (!node.isArray()) {
            throw badRequest(field + " must be an array.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static void requireFrequency(String frequency) {
        if (!FREQUENCIES.contains(frequency)) {
            throw badRequest("findingPublishingFrequency is invalid.");
        }
    }

    private static void requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw badRequest(field + " must be a JSON object.");
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String titleFor(String type) {
        if (DEFAULT_SAMPLE_TYPE.equals(type)) {
            return "Unprotected port on EC2 instance is being probed.";
        }
        return type;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }
}
