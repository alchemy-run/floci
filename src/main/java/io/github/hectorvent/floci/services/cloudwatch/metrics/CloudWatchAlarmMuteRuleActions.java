package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AlarmMuteRule;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON 1.0 / Query encoding for CloudWatch alarm mute rule APIs.
 * Kept out of the shared metrics handlers so other CloudWatch TDD agents
 * can rewrite those files without dropping dashboard operations.
 */
public final class CloudWatchAlarmMuteRuleActions {

    private CloudWatchAlarmMuteRuleActions() {}

    public static Response handleJson(CloudWatchMetricsService service, ObjectMapper mapper,
                                      String action, JsonNode request, String region) {
        return switch (action) {
            case "PutAlarmMuteRule" -> {
                AlarmMuteRule rule = new AlarmMuteRule();
                rule.setName(textOrNull(request, "Name"));
                rule.setDescription(textOrNull(request, "Description"));
                JsonNode schedule = request.path("Rule").path("Schedule");
                if (schedule.isObject()) {
                    rule.setScheduleExpression(textOrNull(schedule, "Expression"));
                    rule.setScheduleDuration(textOrNull(schedule, "Duration"));
                    rule.setScheduleTimezone(textOrNull(schedule, "Timezone"));
                }
                JsonNode alarmNames = request.path("MuteTargets").path("AlarmNames");
                if (alarmNames.isArray()) {
                    List<String> names = new ArrayList<>();
                    alarmNames.forEach(n -> names.add(n.asText()));
                    rule.setAlarmNames(names);
                }
                service.putAlarmMuteRule(rule, region);
                yield Response.ok(mapper.createObjectNode()).build();
            }
            case "GetAlarmMuteRule" -> {
                AlarmMuteRule rule = service.getAlarmMuteRule(
                        textOrNull(request, "AlarmMuteRuleName"), region);
                yield Response.ok(toJson(mapper, rule)).build();
            }
            case "ListAlarmMuteRules" -> {
                List<String> statuses = new ArrayList<>();
                JsonNode statusesNode = request.path("Statuses");
                if (statusesNode.isArray()) {
                    statusesNode.forEach(s -> statuses.add(s.asText()));
                }
                List<AlarmMuteRule> rules = service.listAlarmMuteRules(
                        textOrNull(request, "AlarmName"), statuses, region);
                ObjectNode response = mapper.createObjectNode();
                ArrayNode summaries = response.putArray("AlarmMuteRuleSummaries");
                for (AlarmMuteRule rule : rules) {
                    summaries.add(toJson(mapper, rule));
                }
                yield Response.ok(response).build();
            }
            case "DeleteAlarmMuteRule" -> {
                service.deleteAlarmMuteRule(textOrNull(request, "AlarmMuteRuleName"), region);
                yield Response.ok(mapper.createObjectNode()).build();
            }
            default -> throw new IllegalArgumentException("Unsupported mute rule action: " + action);
        };
    }

    public static Response handleQuery(CloudWatchMetricsService service, String action,
                                       MultivaluedMap<String, String> params, String region) {
        return switch (action) {
            case "PutAlarmMuteRule" -> {
                AlarmMuteRule rule = new AlarmMuteRule();
                rule.setName(params.getFirst("Name"));
                rule.setDescription(params.getFirst("Description"));
                service.putAlarmMuteRule(rule, region);
                yield Response.ok(AwsQueryResponse.envelopeNoResult("PutAlarmMuteRule", AwsNamespaces.CW)).build();
            }
            case "GetAlarmMuteRule" -> {
                AlarmMuteRule rule = service.getAlarmMuteRule(params.getFirst("AlarmMuteRuleName"), region);
                XmlBuilder xml = new XmlBuilder()
                        .elem("Name", rule.getName())
                        .elem("AlarmMuteRuleArn", rule.getAlarmMuteRuleArn())
                        .elem("Description", rule.getDescription());
                yield Response.ok(AwsQueryResponse.envelope("GetAlarmMuteRule", AwsNamespaces.CW, xml.build())).build();
            }
            case "ListAlarmMuteRules" -> {
                List<AlarmMuteRule> rules = service.listAlarmMuteRules(params.getFirst("AlarmName"), List.of(), region);
                XmlBuilder xml = new XmlBuilder().start("AlarmMuteRuleSummaries");
                for (AlarmMuteRule rule : rules) {
                    xml.start("member")
                            .elem("Name", rule.getName())
                            .elem("AlarmMuteRuleArn", rule.getAlarmMuteRuleArn())
                            .end("member");
                }
                xml.end("AlarmMuteRuleSummaries");
                yield Response.ok(AwsQueryResponse.envelope("ListAlarmMuteRules", AwsNamespaces.CW, xml.build())).build();
            }
            case "DeleteAlarmMuteRule" -> {
                service.deleteAlarmMuteRule(params.getFirst("AlarmMuteRuleName"), region);
                yield Response.ok(AwsQueryResponse.envelopeNoResult("DeleteAlarmMuteRule", AwsNamespaces.CW)).build();
            }
            default -> throw new IllegalArgumentException("Unsupported mute rule action: " + action);
        };
    }

    private static ObjectNode toJson(ObjectMapper mapper, AlarmMuteRule rule) {
        ObjectNode node = mapper.createObjectNode();
        if (rule.getName() != null) {
            node.put("Name", rule.getName());
        }
        if (rule.getAlarmMuteRuleArn() != null) {
            node.put("AlarmMuteRuleArn", rule.getAlarmMuteRuleArn());
        }
        if (rule.getDescription() != null) {
            node.put("Description", rule.getDescription());
        }
        if (rule.getScheduleExpression() != null) {
            ObjectNode schedule = node.putObject("Rule").putObject("Schedule");
            schedule.put("Expression", rule.getScheduleExpression());
            if (rule.getScheduleDuration() != null) {
                schedule.put("Duration", rule.getScheduleDuration());
            }
            if (rule.getScheduleTimezone() != null) {
                schedule.put("Timezone", rule.getScheduleTimezone());
            }
        }
        if (rule.getAlarmNames() != null && !rule.getAlarmNames().isEmpty()) {
            ArrayNode names = node.putObject("MuteTargets").putArray("AlarmNames");
            rule.getAlarmNames().forEach(names::add);
        }
        if (rule.getStartDate() != null) {
            node.put("StartDate", rule.getStartDate());
        }
        if (rule.getExpireDate() != null) {
            node.put("ExpireDate", rule.getExpireDate());
        }
        if (rule.getLastUpdatedTimestamp() > 0) {
            node.put("LastUpdatedTimestamp", rule.getLastUpdatedTimestamp());
        }
        node.put("Status", rule.status(Instant.now().getEpochSecond()));
        if (rule.getMuteType() != null) {
            node.put("MuteType", rule.getMuteType());
        }
        return node;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.path(field).isNull()) {
            return null;
        }
        String value = node.path(field).asText();
        return value == null || value.isBlank() ? null : value;
    }
}
