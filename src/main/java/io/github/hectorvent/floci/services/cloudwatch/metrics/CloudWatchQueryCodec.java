package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.Set;

/** Query's member lists and ISO timestamps, over the same CloudWatch operations as JSON. */
final class CloudWatchQueryCodec {
    private static final Set<String> NUMBERS = Set.of("Period", "EvaluationPeriods", "DatapointsToAlarm", "Threshold",
            "SampleCount", "Sum", "Minimum", "Maximum", "MaxRecords", "MaxResults", "MaxContributorCount",
            "ActionsSuppressorWaitPeriod", "ActionsSuppressorExtensionPeriod", "StorageResolution");
    private static final Set<String> INTEGERS = Set.of("Period", "EvaluationPeriods", "DatapointsToAlarm",
            "MaxRecords", "MaxResults", "MaxContributorCount", "ActionsSuppressorWaitPeriod",
            "ActionsSuppressorExtensionPeriod", "StorageResolution");
    private static final Set<String> BOOLEANS = Set.of("ActionsEnabled", "ReturnData", "ApplyOnTransformedLogs");
    private static final Set<String> TIMESTAMPS = Set.of("Timestamp", "StateUpdatedTimestamp", "StateTransitionedTimestamp",
            "AlarmConfigurationUpdatedTimestamp", "LastUpdatedTimestamp", "StartDate", "ExpireDate",
            "StartTime", "EndTime");

    private CloudWatchQueryCodec() {}

    static Response handle(CloudWatchMetricsJsonHandler handler, String action,
                           MultivaluedMap<String, String> params, String region) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        params.forEach((path, values) -> {
            if (!path.equals("Action") && !path.equals("Version") && !values.isEmpty()) {
                insert(request, path.split("\\."), 0, values.getFirst(), path);
            }
        });
        Response response = handler.handle(action, request, region);
        JsonNode entity = (JsonNode) response.getEntity();
        XmlBuilder xml = new XmlBuilder();
        entity.properties().forEach(field -> append(xml, field.getKey(), field.getValue()));
        return Response.status(response.getStatus()).entity(AwsQueryResponse.envelope(action, AwsNamespaces.CW, xml.build())).build();
    }

    private static void insert(ObjectNode target, String[] parts, int index, String value, String path) {
        String name = parts[index];
        if (!name.matches("[A-Za-z][A-Za-z0-9]*")) throw CloudWatchMetadataService.invalid("Invalid Query parameter");
        if (index == parts.length - 1) {
            if (BOOLEANS.contains(name)) target.put(name, Boolean.parseBoolean(value));
            else if (NUMBERS.contains(name) || (name.equals("Value") && path.startsWith("MetricData.")
                    && !path.contains("Dimensions."))) {
                try {
                    if (INTEGERS.contains(name)) target.put(name, Long.parseLong(value));
                    else target.put(name, Double.parseDouble(value));
                } catch (NumberFormatException e) {
                    throw CloudWatchMetadataService.invalid("Invalid number for " + name);
                }
            } else if (TIMESTAMPS.contains(name)) target.put(name,
                    CloudWatchMetadataService.epoch(JsonNodeFactory.instance.textNode(value)));
            else target.put(name, value);
        } else if (parts[index + 1].equals("member")) {
            if (index + 2 >= parts.length) throw CloudWatchMetadataService.invalid("Missing Query member index");
            int member;
            try {
                member = Integer.parseInt(parts[index + 2]) - 1;
            } catch (NumberFormatException e) {
                throw CloudWatchMetadataService.invalid("Invalid Query member index");
            }
            if (member < 0 || member > 1000) throw CloudWatchMetadataService.invalid("Invalid Query member index");
            ArrayNode array = target.withArray(name);
            while (array.size() <= member) array.addNull();
            if (index + 3 == parts.length) array.set(member, JsonNodeFactory.instance.textNode(value));
            else {
                if (array.get(member).isNull()) array.set(member, JsonNodeFactory.instance.objectNode());
                insert((ObjectNode) array.get(member), parts, index + 3, value, path);
            }
        } else {
            if (!target.has(name)) target.set(name, JsonNodeFactory.instance.objectNode());
            insert((ObjectNode) target.get(name), parts, index + 1, value, path);
        }
    }

    private static void append(XmlBuilder xml, String name, JsonNode node) {
        if (node.isNull() || node.isMissingNode()) return;
        if (node.isContainerNode()) {
            xml.start(name);
            if (node.isArray()) node.forEach(member -> append(xml, "member", member));
            else node.properties().forEach(field -> append(xml, field.getKey(), field.getValue()));
            xml.end(name);
        } else if (TIMESTAMPS.contains(name) && node.isNumber()) {
            xml.elem(name, Instant.ofEpochMilli((long) (node.asDouble() * 1000)).toString());
        } else xml.elem(name, node.asText());
    }
}
