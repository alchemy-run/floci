package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AnomalyDetector;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
@Unremovable
public class CloudWatchAnomalyDetectorService {

    private static final Logger LOG = Logger.getLogger(CloudWatchAnomalyDetectorService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_MAX_RESULTS = 100;

    private final StorageBackend<String, AnomalyDetector> detectorStore;
    private final RegionResolver regionResolver;

    @Inject
    public CloudWatchAnomalyDetectorService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.detectorStore = storageFactory.create("cloudwatchmetrics", "cwanomalydetectors.json",
                new TypeReference<Map<String, AnomalyDetector>>() {});
        this.regionResolver = regionResolver;
    }

    CloudWatchAnomalyDetectorService(StorageBackend<String, AnomalyDetector> detectorStore,
                                     RegionResolver regionResolver) {
        this.detectorStore = detectorStore;
        this.regionResolver = regionResolver;
    }

    CloudWatchAnomalyDetectorService(RegionResolver regionResolver) {
        this(new InMemoryStorage<>(), regionResolver);
    }

    public record DescribeResult(List<AnomalyDetector> detectors, String nextToken) {}

    public void putAnomalyDetector(AnomalyDetector detector, String region) {
        if (detector.getAccountId() == null || detector.getAccountId().isBlank()) {
            detector.setAccountId(regionResolver.getAccountId());
        }
        if (detector.getStateValue() == null || detector.getStateValue().isBlank()) {
            detector.setStateValue("PENDING_TRAINING");
        }
        String key = region + "::" + identity(detector);
        detectorStore.put(key, detector);
        LOG.infov("PutAnomalyDetector: {0} in {1}", key, region);
    }

    public DescribeResult describeAnomalyDetectors(
            String namespace,
            String metricName,
            List<Dimension> dimensions,
            List<String> types,
            Integer maxResults,
            String nextToken,
            String region) {

        List<String> resolvedTypes = types == null || types.isEmpty()
                ? List.of("SINGLE_METRIC")
                : types;

        List<AnomalyDetector> matched = detectorStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(d -> matchesType(d, resolvedTypes))
                .filter(d -> namespace == null || namespace.isBlank() || namespace.equals(d.getNamespace()))
                .filter(d -> metricName == null || metricName.isBlank() || metricName.equals(d.getMetricName()))
                .filter(d -> dimensions == null || dimensions.isEmpty()
                        || dimKey(d.getDimensions()).equals(dimKey(dimensions)))
                .sorted(Comparator.comparing(CloudWatchAnomalyDetectorService::identity))
                .collect(Collectors.toList());

        int offset = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                offset = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidNextToken", "The NextToken value is not valid.", 400);
            }
            if (offset < 0 || offset > matched.size()) {
                throw new AwsException("InvalidNextToken", "The NextToken value is not valid.", 400);
            }
        }

        int limit = maxResults == null || maxResults <= 0
                ? DEFAULT_MAX_RESULTS
                : Math.min(maxResults, DEFAULT_MAX_RESULTS);
        int end = Math.min(offset + limit, matched.size());
        String token = end < matched.size() ? String.valueOf(end) : null;
        return new DescribeResult(matched.subList(offset, end), token);
    }

    public void deleteAnomalyDetector(AnomalyDetector selector, String region) {
        String key = region + "::" + identity(selector);
        if (detectorStore.get(key).isPresent()) {
            detectorStore.delete(key);
            LOG.infov("DeleteAnomalyDetector: {0} in {1}", key, region);
            return;
        }
        if (selector.isMetricMath() && selector.getMetricMathJson() != null) {
            JsonNode want = readJson(selector.getMetricMathJson());
            for (AnomalyDetector existing : detectorStore.scan(k -> k.startsWith(region + "::"))) {
                if (!existing.isMetricMath()) {
                    continue;
                }
                JsonNode have = readJson(existing.getMetricMathJson());
                if (want != null && want.equals(have)) {
                    detectorStore.delete(region + "::" + identity(existing));
                    return;
                }
            }
        }
        throw new AwsException("ResourceNotFoundException", "Anomaly detector not found.", 404);
    }

    static String identity(AnomalyDetector detector) {
        if (detector.isMetricMath()) {
            return "METRIC_MATH::" + (detector.getMetricMathJson() == null ? "" : detector.getMetricMathJson());
        }
        return "SINGLE_METRIC::"
                + nullToEmpty(detector.getNamespace()) + "::"
                + nullToEmpty(detector.getMetricName()) + "::"
                + dimKey(detector.getDimensions()) + "::"
                + nullToEmpty(detector.getStat());
    }

    static String dimKey(List<Dimension> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return "";
        }
        return dimensions.stream()
                .sorted(Comparator.comparing(Dimension::name))
                .map(d -> d.name() + "=" + d.value())
                .collect(Collectors.joining(","));
    }

    private static boolean matchesType(AnomalyDetector detector, List<String> types) {
        return detector.isMetricMath()
                ? types.contains("METRIC_MATH")
                : types.contains("SINGLE_METRIC");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
