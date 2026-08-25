package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AnomalyDetector;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON 1.0 / Query protocol encoding for CloudWatch anomaly detector APIs.
 * Kept out of the shared metrics handlers so other CloudWatch TDD agents
 * can rewrite those files without dropping these operations.
 */
public final class CloudWatchAnomalyDetectorActions {

    private CloudWatchAnomalyDetectorActions() {}

    public static Response handleJson(ObjectMapper mapper, String action, JsonNode request, String region) {
        return handleJson(service(), mapper, action, request, region);
    }

    public static Response handleJson(CloudWatchAnomalyDetectorService service, ObjectMapper mapper,
                                      String action, JsonNode request, String region) {
        return switch (action) {
            case "PutAnomalyDetector" -> {
                service.putAnomalyDetector(parseIdentity(request), region);
                yield Response.ok(mapper.createObjectNode()).build();
            }
            case "DescribeAnomalyDetectors" -> {
                List<String> types = new ArrayList<>();
                JsonNode typesNode = request.path("AnomalyDetectorTypes");
                if (typesNode.isArray()) {
                    typesNode.forEach(t -> types.add(t.asText()));
                }
                Integer maxResults = request.has("MaxResults") ? request.path("MaxResults").asInt() : null;
                CloudWatchAnomalyDetectorService.DescribeResult result = service.describeAnomalyDetectors(
                        textOrNull(request, "Namespace"),
                        textOrNull(request, "MetricName"),
                        parseDimensions(request.path("Dimensions")),
                        types,
                        maxResults,
                        textOrNull(request, "NextToken"),
                        region);
                ObjectNode response = mapper.createObjectNode();
                ArrayNode detectors = response.putArray("AnomalyDetectors");
                for (AnomalyDetector detector : result.detectors()) {
                    detectors.add(toJson(mapper, detector));
                }
                if (result.nextToken() != null) {
                    response.put("NextToken", result.nextToken());
                }
                yield Response.ok(response).build();
            }
            case "DeleteAnomalyDetector" -> {
                service.deleteAnomalyDetector(parseIdentity(request), region);
                yield Response.ok(mapper.createObjectNode()).build();
            }
            default -> throw new IllegalArgumentException("Unsupported anomaly detector action: " + action);
        };
    }

    public static Response handleQuery(String action, MultivaluedMap<String, String> params, String region) {
        return handleQuery(service(), action, params, region);
    }

    public static Response handleQuery(CloudWatchAnomalyDetectorService service, String action,
                                       MultivaluedMap<String, String> params, String region) {
        return switch (action) {
            case "PutAnomalyDetector" -> {
                service.putAnomalyDetector(parseIdentity(params), region);
                yield Response.ok(AwsQueryResponse.envelopeNoResult("PutAnomalyDetector", AwsNamespaces.CW)).build();
            }
            case "DescribeAnomalyDetectors" -> {
                List<String> types = new ArrayList<>();
                for (int i = 1; ; i++) {
                    String type = params.getFirst("AnomalyDetectorTypes.member." + i);
                    if (type == null) {
                        break;
                    }
                    types.add(type);
                }
                Integer maxResults = params.getFirst("MaxResults") != null
                        ? Integer.parseInt(params.getFirst("MaxResults"))
                        : null;
                CloudWatchAnomalyDetectorService.DescribeResult result = service.describeAnomalyDetectors(
                        params.getFirst("Namespace"),
                        params.getFirst("MetricName"),
                        parseDimensions(params, "Dimensions"),
                        types,
                        maxResults,
                        params.getFirst("NextToken"),
                        region);
                XmlBuilder xml = new XmlBuilder().start("AnomalyDetectors");
                for (AnomalyDetector detector : result.detectors()) {
                    toXml(xml, detector);
                }
                xml.end("AnomalyDetectors");
                if (result.nextToken() != null) {
                    xml.elem("NextToken", result.nextToken());
                }
                yield Response.ok(AwsQueryResponse.envelope("DescribeAnomalyDetectors", AwsNamespaces.CW, xml.build())).build();
            }
            case "DeleteAnomalyDetector" -> {
                service.deleteAnomalyDetector(parseIdentity(params), region);
                yield Response.ok(AwsQueryResponse.envelopeNoResult("DeleteAnomalyDetector", AwsNamespaces.CW)).build();
            }
            default -> throw new IllegalArgumentException("Unsupported anomaly detector action: " + action);
        };
    }

    static boolean isAnomalyDetectorAction(String action) {
        return "PutAnomalyDetector".equals(action)
                || "DescribeAnomalyDetectors".equals(action)
                || "DeleteAnomalyDetector".equals(action);
    }

    private static CloudWatchAnomalyDetectorService service() {
        return CDI.current().select(CloudWatchAnomalyDetectorService.class).get();
    }

    private static AnomalyDetector parseIdentity(JsonNode request) {
        AnomalyDetector detector = new AnomalyDetector();
        JsonNode metricMath = request.path("MetricMathAnomalyDetector");
        if (metricMath.isObject()) {
            detector.setMetricMath(true);
            detector.setMetricMathJson(metricMath.toString());
            applyConfiguration(detector, request);
            return detector;
        }
        JsonNode single = request.path("SingleMetricAnomalyDetector");
        JsonNode source = single.isObject() ? single : request;
        detector.setNamespace(firstText(source, request, "Namespace"));
        detector.setMetricName(firstText(source, request, "MetricName"));
        detector.setStat(firstText(source, request, "Stat"));
        List<Dimension> dims = parseDimensions(source.path("Dimensions"));
        if (dims.isEmpty()) {
            dims = parseDimensions(request.path("Dimensions"));
        }
        detector.setDimensions(dims);
        detector.setAccountId(textOrNull(source, "AccountId"));
        applyConfiguration(detector, request);
        return detector;
    }

    private static void applyConfiguration(AnomalyDetector detector, JsonNode request) {
        JsonNode config = request.path("Configuration");
        if (config.isObject()) {
            detector.setMetricTimezone(textOrNull(config, "MetricTimezone"));
            JsonNode ranges = config.path("ExcludedTimeRanges");
            if (ranges.isArray()) {
                List<AnomalyDetector.ExcludedTimeRange> excluded = new ArrayList<>();
                for (JsonNode rangeNode : ranges) {
                    AnomalyDetector.ExcludedTimeRange range = new AnomalyDetector.ExcludedTimeRange();
                    range.setStartTime(rangeNode.path("StartTime").asLong());
                    range.setEndTime(rangeNode.path("EndTime").asLong());
                    excluded.add(range);
                }
                detector.setExcludedTimeRanges(excluded);
            }
        }
        JsonNode characteristics = request.path("MetricCharacteristics");
        if (characteristics.isObject() && characteristics.has("PeriodicSpikes")) {
            detector.setPeriodicSpikes(characteristics.path("PeriodicSpikes").asBoolean());
        }
    }

    private static AnomalyDetector parseIdentity(MultivaluedMap<String, String> params) {
        AnomalyDetector detector = new AnomalyDetector();
        String nestedNamespace = params.getFirst("SingleMetricAnomalyDetector.Namespace");
        detector.setNamespace(nestedNamespace != null ? nestedNamespace : params.getFirst("Namespace"));
        String nestedMetric = params.getFirst("SingleMetricAnomalyDetector.MetricName");
        detector.setMetricName(nestedMetric != null ? nestedMetric : params.getFirst("MetricName"));
        String nestedStat = params.getFirst("SingleMetricAnomalyDetector.Stat");
        detector.setStat(nestedStat != null ? nestedStat : params.getFirst("Stat"));
        detector.setAccountId(params.getFirst("SingleMetricAnomalyDetector.AccountId"));
        List<Dimension> nested = parseDimensions(params, "SingleMetricAnomalyDetector.Dimensions");
        detector.setDimensions(nested.isEmpty() ? parseDimensions(params, "Dimensions") : nested);
        return detector;
    }

    private static ObjectNode toJson(ObjectMapper mapper, AnomalyDetector detector) {
        ObjectNode node = mapper.createObjectNode();
        if (detector.isMetricMath()) {
            try {
                node.set("MetricMathAnomalyDetector", mapper.readTree(detector.getMetricMathJson()));
            } catch (Exception e) {
                node.putObject("MetricMathAnomalyDetector");
            }
        } else {
            if (detector.getNamespace() != null) {
                node.put("Namespace", detector.getNamespace());
            }
            if (detector.getMetricName() != null) {
                node.put("MetricName", detector.getMetricName());
            }
            writeDimensions(node.putArray("Dimensions"), detector.getDimensions());
            if (detector.getStat() != null) {
                node.put("Stat", detector.getStat());
            }
            ObjectNode single = node.putObject("SingleMetricAnomalyDetector");
            if (detector.getAccountId() != null) {
                single.put("AccountId", detector.getAccountId());
            }
            if (detector.getNamespace() != null) {
                single.put("Namespace", detector.getNamespace());
            }
            if (detector.getMetricName() != null) {
                single.put("MetricName", detector.getMetricName());
            }
            writeDimensions(single.putArray("Dimensions"), detector.getDimensions());
            if (detector.getStat() != null) {
                single.put("Stat", detector.getStat());
            }
        }
        if (detector.getStateValue() != null) {
            node.put("StateValue", detector.getStateValue());
        }
        boolean hasConfig = detector.getMetricTimezone() != null
                || (detector.getExcludedTimeRanges() != null && !detector.getExcludedTimeRanges().isEmpty());
        if (hasConfig) {
            ObjectNode config = node.putObject("Configuration");
            if (detector.getMetricTimezone() != null) {
                config.put("MetricTimezone", detector.getMetricTimezone());
            }
            if (detector.getExcludedTimeRanges() != null && !detector.getExcludedTimeRanges().isEmpty()) {
                ArrayNode ranges = config.putArray("ExcludedTimeRanges");
                for (AnomalyDetector.ExcludedTimeRange range : detector.getExcludedTimeRanges()) {
                    ranges.addObject()
                            .put("StartTime", range.getStartTime())
                            .put("EndTime", range.getEndTime());
                }
            }
        }
        if (detector.getPeriodicSpikes() != null) {
            node.putObject("MetricCharacteristics").put("PeriodicSpikes", detector.getPeriodicSpikes());
        }
        return node;
    }

    private static void toXml(XmlBuilder xml, AnomalyDetector detector) {
        xml.start("member");
        if (!detector.isMetricMath()) {
            xml.elem("Namespace", detector.getNamespace())
                    .elem("MetricName", detector.getMetricName())
                    .elem("Stat", detector.getStat());
            xml.start("Dimensions");
            writeDimensionXml(xml, detector.getDimensions());
            xml.end("Dimensions");
            xml.start("SingleMetricAnomalyDetector")
                    .elem("AccountId", detector.getAccountId())
                    .elem("Namespace", detector.getNamespace())
                    .elem("MetricName", detector.getMetricName())
                    .elem("Stat", detector.getStat());
            xml.start("Dimensions");
            writeDimensionXml(xml, detector.getDimensions());
            xml.end("Dimensions").end("SingleMetricAnomalyDetector");
        }
        xml.elem("StateValue", detector.getStateValue());
        xml.end("member");
    }

    private static void writeDimensions(ArrayNode array, List<Dimension> dimensions) {
        if (dimensions == null) {
            return;
        }
        for (Dimension dimension : dimensions) {
            array.addObject().put("Name", dimension.name()).put("Value", dimension.value());
        }
    }

    private static void writeDimensionXml(XmlBuilder xml, List<Dimension> dimensions) {
        if (dimensions == null) {
            return;
        }
        for (Dimension dimension : dimensions) {
            xml.start("member").elem("Name", dimension.name()).elem("Value", dimension.value()).end("member");
        }
    }

    private static List<Dimension> parseDimensions(JsonNode node) {
        List<Dimension> dims = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return dims;
        }
        for (JsonNode d : node) {
            dims.add(new Dimension(d.path("Name").asText(), d.path("Value").asText()));
        }
        return dims;
    }

    private static List<Dimension> parseDimensions(MultivaluedMap<String, String> params, String prefix) {
        List<Dimension> dims = new ArrayList<>();
        for (int i = 1; ; i++) {
            String name = params.getFirst(prefix + ".member." + i + ".Name");
            if (name == null) {
                break;
            }
            dims.add(new Dimension(name, params.getFirst(prefix + ".member." + i + ".Value")));
        }
        return dims;
    }

    private static String firstText(JsonNode preferred, JsonNode fallback, String field) {
        String value = textOrNull(preferred, field);
        return value != null ? value : textOrNull(fallback, field);
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.path(field).isNull()) {
            return null;
        }
        String value = node.path(field).asText();
        return value == null || value.isBlank() ? null : value;
    }
}
