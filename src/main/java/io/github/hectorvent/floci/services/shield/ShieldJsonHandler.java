package io.github.hectorvent.floci.services.shield;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.services.shield.model.ShieldProtection;
import io.github.hectorvent.floci.services.shield.model.ShieldProtectionGroup;
import io.github.hectorvent.floci.services.shield.model.ShieldSubscription;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * JSON 1.1 handler for AWS Shield. Dispatched from
 * {@code AwsJson11Controller} under the {@code AWSShield_20160616.} target prefix.
 */
@ApplicationScoped
public class ShieldJsonHandler {

    private final ShieldService service;
    private final ObjectMapper objectMapper;

    @Inject
    public ShieldJsonHandler(ShieldService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "GetSubscriptionState" -> getSubscriptionState();
                case "DescribeAttackStatistics" -> describeAttackStatistics();
                case "ListAttacks" -> listAttacks();
                case "DescribeAttack" -> describeAttack(body);
                case "DescribeDRTAccess" -> describeDRTAccess();
                case "ListResourcesInProtectionGroup" -> listResourcesInProtectionGroup(body);
                case "CreateSubscription" -> {
                    service.createSubscription();
                    yield ok();
                }
                case "DescribeSubscription" -> describeSubscription();
                case "UpdateSubscription" -> {
                    service.updateSubscription(body);
                    yield ok();
                }
                case "DeleteSubscription" -> {
                    service.deleteSubscription();
                    yield ok();
                }
                case "CreateProtection" -> createProtection(body);
                case "DescribeProtection" -> describeProtection(body);
                case "ListProtections" -> listProtections();
                case "DeleteProtection" -> {
                    service.deleteProtection(text(body, "ProtectionId"));
                    yield ok();
                }
                case "CreateProtectionGroup" -> {
                    service.createProtectionGroup(body);
                    yield ok();
                }
                case "DescribeProtectionGroup" -> describeProtectionGroup(body);
                case "UpdateProtectionGroup" -> {
                    service.updateProtectionGroup(body);
                    yield ok();
                }
                case "ListProtectionGroups" -> listProtectionGroups();
                case "DeleteProtectionGroup" -> {
                    service.deleteProtectionGroup(text(body, "ProtectionGroupId"));
                    yield ok();
                }
                case "ListTagsForResource" -> listTags(body);
                case "TagResource" -> {
                    service.tagResource(text(body, "ResourceARN"), body);
                    yield ok();
                }
                case "UntagResource" -> {
                    service.untagResource(text(body, "ResourceARN"), body);
                    yield ok();
                }
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(
                        "AWSShield_20160616." + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private Response getSubscriptionState() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("SubscriptionState", service.subscriptionState());
        return Response.ok(response).build();
    }

    private Response describeAttackStatistics() {
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        long toExclusive = todayUtc.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long fromInclusive = todayUtc.minusYears(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode timeRange = response.putObject("TimeRange");
        timeRange.put("FromInclusive", fromInclusive);
        timeRange.put("ToExclusive", toExclusive);
        response.putArray("DataItems");
        return Response.ok(response).build();
    }

    private Response listAttacks() {
        if (service.findSubscription().isEmpty()) {
            throw ShieldService.invalidOperationNoSubscription();
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("AttackSummaries");
        return Response.ok(response).build();
    }

    private Response describeAttack(JsonNode request) {
        service.requireAttackId(request);
        return ok();
    }

    private Response describeDRTAccess() {
        throw ShieldService.drtAccessNotFound();
    }

    private Response listResourcesInProtectionGroup(JsonNode request) {
        ShieldProtectionGroup group = service.requireProtectionGroup(text(request, "ProtectionGroupId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arns = response.putArray("ResourceArns");
        for (String arn : group.getMembers()) {
            arns.add(arn);
        }
        return Response.ok(response).build();
    }

    private Response describeSubscription() {
        ShieldSubscription subscription = service.requireSubscription();
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode body = response.putObject("Subscription");
        if (subscription.getSubscriptionArn() != null) {
            body.put("SubscriptionArn", subscription.getSubscriptionArn());
        }
        if (subscription.getAutoRenew() != null) {
            body.put("AutoRenew", subscription.getAutoRenew());
        }
        if (subscription.getStartTime() != null) {
            body.put("StartTime", subscription.getStartTime());
        }
        if (subscription.getEndTime() != null) {
            body.put("EndTime", subscription.getEndTime());
        }
        if (subscription.getTimeCommitmentInSeconds() != null) {
            body.put("TimeCommitmentInSeconds", subscription.getTimeCommitmentInSeconds());
        }
        if (subscription.getProactiveEngagementStatus() != null) {
            body.put("ProactiveEngagementStatus", subscription.getProactiveEngagementStatus());
        }
        return Response.ok(response).build();
    }

    private Response createProtection(JsonNode request) {
        ShieldProtection protection = service.createProtection(request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ProtectionId", protection.getId());
        return Response.ok(response).build();
    }

    private Response describeProtection(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Protection", protectionNode(service.describeProtection(request)));
        return Response.ok(response).build();
    }

    private Response listProtections() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Protections");
        for (ShieldProtection protection : service.listProtections()) {
            list.add(protectionNode(protection));
        }
        return Response.ok(response).build();
    }

    private Response describeProtectionGroup(JsonNode request) {
        ShieldProtectionGroup group = service.requireProtectionGroup(text(request, "ProtectionGroupId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ProtectionGroup", protectionGroupNode(group));
        return Response.ok(response).build();
    }

    private Response listProtectionGroups() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ProtectionGroups");
        for (ShieldProtectionGroup group : service.listProtectionGroups()) {
            list.add(protectionGroupNode(group));
        }
        return Response.ok(response).build();
    }

    private Response listTags(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tags = response.putArray("Tags");
        for (Map.Entry<String, String> entry : service.listTags(text(request, "ResourceARN")).entrySet()) {
            ObjectNode tag = tags.addObject();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue());
        }
        return Response.ok(response).build();
    }

    private ObjectNode protectionNode(ShieldProtection protection) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("Id", protection.getId());
        body.put("Name", protection.getName());
        body.put("ResourceArn", protection.getResourceArn());
        body.put("ProtectionArn", protection.getProtectionArn());
        ArrayNode health = body.putArray("HealthCheckIds");
        for (String id : protection.getHealthCheckIds()) {
            health.add(id);
        }
        return body;
    }

    private ObjectNode protectionGroupNode(ShieldProtectionGroup group) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("ProtectionGroupId", group.getProtectionGroupId());
        body.put("ProtectionGroupArn", group.getProtectionGroupArn());
        body.put("Aggregation", group.getAggregation());
        body.put("Pattern", group.getPattern());
        if (group.getResourceType() != null) {
            body.put("ResourceType", group.getResourceType());
        }
        ArrayNode members = body.putArray("Members");
        for (String member : group.getMembers()) {
            members.add(member);
        }
        return body;
    }

    private static String text(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            return null;
        }
        return request.get(field).asText();
    }

    private Response ok() {
        return Response.ok(objectMapper.createObjectNode()).build();
    }
}
