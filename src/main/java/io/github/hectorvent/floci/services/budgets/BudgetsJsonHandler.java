package io.github.hectorvent.floci.services.budgets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.budgets.model.ActionHistory;
import io.github.hectorvent.floci.services.budgets.model.Budget;
import io.github.hectorvent.floci.services.budgets.model.BudgetAction;
import io.github.hectorvent.floci.services.budgets.model.Notification;
import io.github.hectorvent.floci.services.budgets.model.Spend;
import io.github.hectorvent.floci.services.budgets.model.Subscriber;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * JSON 1.1 handler for AWS Budgets operations.
 * Dispatches {@code X-Amz-Target: AWSBudgetServiceGateway.*} actions.
 */
@ApplicationScoped
public class BudgetsJsonHandler {

    private static final Logger LOG = Logger.getLogger(BudgetsJsonHandler.class);

    private final BudgetsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public BudgetsJsonHandler(BudgetsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Budgets action: {0}", action);
        return switch (action) {
            case "CreateBudget" -> handleCreateBudget(request);
            case "DescribeBudget" -> handleDescribeBudget(request);
            case "DescribeBudgets" -> handleDescribeBudgets();
            case "UpdateBudget" -> handleUpdateBudget(request);
            case "DeleteBudget" -> handleDeleteBudget(request);
            case "CreateNotification" -> handleCreateNotification(request);
            case "DescribeNotificationsForBudget" -> handleDescribeNotifications(request);
            case "UpdateNotification" -> handleUpdateNotification(request);
            case "DeleteNotification" -> handleDeleteNotification(request);
            case "CreateSubscriber" -> handleCreateSubscriber(request);
            case "DescribeSubscribersForNotification" -> handleDescribeSubscribers(request);
            case "UpdateSubscriber" -> handleUpdateSubscriber(request);
            case "DeleteSubscriber" -> handleDeleteSubscriber(request);
            case "CreateBudgetAction" -> handleCreateBudgetAction(request);
            case "DescribeBudgetAction" -> handleDescribeBudgetAction(request);
            case "DescribeBudgetActionsForBudget" -> handleDescribeActionsForBudget(request);
            case "DescribeBudgetActionsForAccount" -> handleDescribeActionsForAccount();
            case "UpdateBudgetAction" -> handleUpdateBudgetAction(request);
            case "DeleteBudgetAction" -> handleDeleteBudgetAction(request);
            case "DescribeBudgetActionHistories" -> handleDescribeActionHistories(request);
            case "DescribeBudgetPerformanceHistory" -> handleDescribePerformanceHistory(request);
            case "DescribeBudgetNotificationsForAccount" -> handleDescribeNotificationsForAccount();
            case "ExecuteBudgetAction" -> handleExecuteBudgetAction(request);
            case "ListTagsForResource" -> handleListTags(request);
            case "TagResource" -> handleTagResource(request);
            case "UntagResource" -> handleUntagResource(request);
            default -> throw new AwsException("UnknownOperationException",
                    "Unknown operation: AWSBudgetServiceGateway." + action, 400);
        };
    }

    private Response handleCreateBudget(JsonNode request) {
        Budget incoming = parseBudget(request.path("Budget"));
        List<Notification> notifications = parseNotificationsWithSubscribers(
                request.path("NotificationsWithSubscribers"));
        Map<String, String> tags = parseResourceTags(request.path("ResourceTags"));
        service.createBudget(incoming, notifications, tags);
        return empty();
    }

    private Response handleDescribeBudget(JsonNode request) {
        Budget budget = service.describeBudget(stringOrNull(request, "BudgetName"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Budget", serializeBudget(budget));
        return Response.ok(response).build();
    }

    private Response handleDescribeBudgets() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Budgets");
        for (Budget budget : service.describeBudgets()) {
            arr.add(serializeBudget(budget));
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateBudget(JsonNode request) {
        service.updateBudget(parseBudget(request.path("NewBudget")));
        return empty();
    }

    private Response handleDeleteBudget(JsonNode request) {
        service.deleteBudget(stringOrNull(request, "BudgetName"));
        return empty();
    }

    private Response handleCreateNotification(JsonNode request) {
        Notification notification = parseNotification(request.path("Notification"));
        notification.setSubscribers(parseSubscribers(request.path("Subscribers")));
        service.createNotification(stringOrNull(request, "BudgetName"), notification);
        return empty();
    }

    private Response handleDescribeNotifications(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Notifications");
        for (Notification notification : service.describeNotifications(stringOrNull(request, "BudgetName"))) {
            arr.add(serializeNotification(notification, false));
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateNotification(JsonNode request) {
        service.updateNotification(
                stringOrNull(request, "BudgetName"),
                parseNotification(request.path("OldNotification")),
                parseNotification(request.path("NewNotification")));
        return empty();
    }

    private Response handleDeleteNotification(JsonNode request) {
        service.deleteNotification(
                stringOrNull(request, "BudgetName"),
                parseNotification(request.path("Notification")));
        return empty();
    }

    private Response handleCreateSubscriber(JsonNode request) {
        service.createSubscriber(
                stringOrNull(request, "BudgetName"),
                parseNotification(request.path("Notification")),
                parseSubscriber(request.path("Subscriber")));
        return empty();
    }

    private Response handleDescribeSubscribers(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Subscribers");
        for (Subscriber subscriber : service.describeSubscribers(
                stringOrNull(request, "BudgetName"),
                parseNotification(request.path("Notification")))) {
            arr.add(serializeSubscriber(subscriber));
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateSubscriber(JsonNode request) {
        service.updateSubscriber(
                stringOrNull(request, "BudgetName"),
                parseNotification(request.path("Notification")),
                parseSubscriber(request.path("OldSubscriber")),
                parseSubscriber(request.path("NewSubscriber")));
        return empty();
    }

    private Response handleDeleteSubscriber(JsonNode request) {
        service.deleteSubscriber(
                stringOrNull(request, "BudgetName"),
                parseNotification(request.path("Notification")),
                parseSubscriber(request.path("Subscriber")));
        return empty();
    }

    private Response handleCreateBudgetAction(JsonNode request) {
        BudgetAction incoming = parseAction(request);
        Map<String, String> tags = parseResourceTags(request.path("ResourceTags"));
        BudgetAction created = service.createBudgetAction(incoming, tags);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AccountId", accountId(request));
        response.put("BudgetName", created.getBudgetName());
        response.put("ActionId", created.getActionId());
        return Response.ok(response).build();
    }

    private Response handleDescribeBudgetAction(JsonNode request) {
        BudgetAction action = service.describeBudgetAction(
                stringOrNull(request, "BudgetName"), stringOrNull(request, "ActionId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AccountId", accountId(request));
        response.put("BudgetName", action.getBudgetName());
        response.set("Action", serializeAction(action));
        return Response.ok(response).build();
    }

    private Response handleDescribeActionsForBudget(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Actions");
        for (BudgetAction action : service.describeBudgetActionsForBudget(stringOrNull(request, "BudgetName"))) {
            arr.add(serializeAction(action));
        }
        return Response.ok(response).build();
    }

    private Response handleDescribeActionsForAccount() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Actions");
        for (BudgetAction action : service.describeBudgetActionsForAccount()) {
            arr.add(serializeAction(action));
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateBudgetAction(JsonNode request) {
        String budgetName = stringOrNull(request, "BudgetName");
        String actionId = stringOrNull(request, "ActionId");
        BudgetAction old = service.describeBudgetAction(budgetName, actionId);
        ObjectNode oldNode = serializeAction(old);
        BudgetAction patch = parseAction(request);
        patch.setBudgetName(budgetName);
        BudgetAction updated = service.updateBudgetAction(budgetName, actionId, patch);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AccountId", accountId(request));
        response.put("BudgetName", budgetName);
        response.set("OldAction", oldNode);
        response.set("NewAction", serializeAction(updated));
        return Response.ok(response).build();
    }

    private Response handleDeleteBudgetAction(JsonNode request) {
        BudgetAction deleted = service.deleteBudgetAction(
                stringOrNull(request, "BudgetName"), stringOrNull(request, "ActionId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AccountId", accountId(request));
        response.put("BudgetName", deleted.getBudgetName());
        response.set("Action", serializeAction(deleted));
        return Response.ok(response).build();
    }

    private Response handleDescribeActionHistories(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("ActionHistories");
        for (ActionHistory history : service.describeBudgetActionHistories(
                stringOrNull(request, "BudgetName"), stringOrNull(request, "ActionId"))) {
            arr.add(serializeHistory(history));
        }
        return Response.ok(response).build();
    }

    private Response handleDescribePerformanceHistory(JsonNode request) {
        Budget budget = service.describeBudget(stringOrNull(request, "BudgetName"));
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode history = response.putObject("BudgetPerformanceHistory");
        history.put("BudgetName", budget.getBudgetName());
        if (budget.getBudgetType() != null) {
            history.put("BudgetType", budget.getBudgetType());
        }
        if (budget.getTimeUnit() != null) {
            history.put("TimeUnit", budget.getTimeUnit());
        }
        history.putArray("BudgetedAndActualAmountsList");
        return Response.ok(response).build();
    }

    private Response handleDescribeNotificationsForAccount() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("BudgetNotificationsForAccount");
        for (Budget budget : service.describeBudgets()) {
            ObjectNode entry = arr.addObject();
            entry.put("BudgetName", budget.getBudgetName());
            ArrayNode notifications = entry.putArray("Notifications");
            for (Notification notification : budget.getNotifications()) {
                notifications.add(serializeNotification(notification, false));
            }
        }
        return Response.ok(response).build();
    }

    private Response handleExecuteBudgetAction(JsonNode request) {
        String budgetName = stringOrNull(request, "BudgetName");
        String actionId = stringOrNull(request, "ActionId");
        String executionType = stringOrNull(request, "ExecutionType");
        String executed = service.executeBudgetAction(budgetName, actionId, executionType);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AccountId", accountId(request));
        response.put("BudgetName", budgetName);
        response.put("ActionId", actionId);
        response.put("ExecutionType", executed);
        return Response.ok(response).build();
    }

    private Response handleListTags(JsonNode request) {
        Map<String, String> tags = service.listTags(stringOrNull(request, "ResourceARN"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("ResourceTags");
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tag = arr.addObject();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue() == null ? "" : entry.getValue());
        }
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request) {
        service.tagResource(stringOrNull(request, "ResourceARN"),
                parseResourceTags(request.path("ResourceTags")));
        return empty();
    }

    private Response handleUntagResource(JsonNode request) {
        List<String> keys = new ArrayList<>();
        JsonNode keysNode = request.path("ResourceTagKeys");
        if (keysNode.isArray()) {
            for (JsonNode key : keysNode) {
                keys.add(key.asText());
            }
        }
        service.untagResource(stringOrNull(request, "ResourceARN"), keys);
        return empty();
    }

    private Budget parseBudget(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new AwsException("InvalidParameterException", "Budget is required.", 400);
        }
        Budget budget = new Budget();
        budget.setBudgetName(stringOrNull(node, "BudgetName"));
        budget.setBudgetType(stringOrNull(node, "BudgetType"));
        budget.setTimeUnit(stringOrNull(node, "TimeUnit"));
        budget.setBillingViewArn(stringOrNull(node, "BillingViewArn"));
        JsonNode limit = node.path("BudgetLimit");
        if (limit.isObject() && !limit.isEmpty()) {
            budget.setBudgetLimit(new Spend(
                    textOrNumber(limit, "Amount"),
                    stringOrNull(limit, "Unit")));
        }
        JsonNode filters = node.path("CostFilters");
        if (filters.isObject() && !filters.isEmpty()) {
            Map<String, List<String>> costFilters = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = filters.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                List<String> values = new ArrayList<>();
                if (field.getValue().isArray()) {
                    for (JsonNode value : field.getValue()) {
                        values.add(value.asText());
                    }
                }
                costFilters.put(field.getKey(), values);
            }
            budget.setCostFilters(costFilters);
        }
        return budget;
    }

    private List<Notification> parseNotificationsWithSubscribers(JsonNode node) {
        List<Notification> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode entry : node) {
            Notification notification = parseNotification(entry.path("Notification"));
            notification.setSubscribers(parseSubscribers(entry.path("Subscribers")));
            result.add(notification);
        }
        return result;
    }

    private Notification parseNotification(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new AwsException("InvalidParameterException", "Notification is required.", 400);
        }
        Notification notification = new Notification();
        notification.setNotificationType(stringOrNull(node, "NotificationType"));
        notification.setComparisonOperator(stringOrNull(node, "ComparisonOperator"));
        if (node.has("Threshold") && !node.get("Threshold").isNull()) {
            notification.setThreshold(node.get("Threshold").asDouble());
        }
        notification.setThresholdType(stringOrNull(node, "ThresholdType"));
        notification.setNotificationState(stringOrNull(node, "NotificationState"));
        return notification;
    }

    private List<Subscriber> parseSubscribers(JsonNode node) {
        List<Subscriber> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode entry : node) {
            result.add(parseSubscriber(entry));
        }
        return result;
    }

    private Subscriber parseSubscriber(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new AwsException("InvalidParameterException", "Subscriber is required.", 400);
        }
        return new Subscriber(stringOrNull(node, "SubscriptionType"), stringOrNull(node, "Address"));
    }

    private BudgetAction parseAction(JsonNode node) {
        BudgetAction action = new BudgetAction();
        action.setBudgetName(stringOrNull(node, "BudgetName"));
        action.setNotificationType(stringOrNull(node, "NotificationType"));
        action.setActionType(stringOrNull(node, "ActionType"));
        action.setExecutionRoleArn(stringOrNull(node, "ExecutionRoleArn"));
        action.setApprovalModel(stringOrNull(node, "ApprovalModel"));
        JsonNode threshold = node.path("ActionThreshold");
        if (threshold.isObject() && !threshold.isEmpty()) {
            if (threshold.has("ActionThresholdValue") && !threshold.get("ActionThresholdValue").isNull()) {
                action.setActionThresholdValue(threshold.get("ActionThresholdValue").asDouble());
            }
            action.setActionThresholdType(stringOrNull(threshold, "ActionThresholdType"));
        }
        JsonNode definition = node.path("Definition");
        if (definition.isObject() && !definition.isEmpty()) {
            JsonNode iam = definition.path("IamActionDefinition");
            if (iam.isObject() && !iam.isEmpty()) {
                action.setIamPolicyArn(stringOrNull(iam, "PolicyArn"));
                action.setIamRoles(stringList(iam.path("Roles")));
                action.setIamGroups(stringList(iam.path("Groups")));
                action.setIamUsers(stringList(iam.path("Users")));
            }
            JsonNode scp = definition.path("ScpActionDefinition");
            if (scp.isObject() && !scp.isEmpty()) {
                action.setScpPolicyId(stringOrNull(scp, "PolicyId"));
                action.setScpTargetIds(stringList(scp.path("TargetIds")));
            }
            JsonNode ssm = definition.path("SsmActionDefinition");
            if (ssm.isObject() && !ssm.isEmpty()) {
                action.setSsmActionSubType(stringOrNull(ssm, "ActionSubType"));
                action.setSsmRegion(stringOrNull(ssm, "Region"));
                action.setSsmInstanceIds(stringList(ssm.path("InstanceIds")));
            }
        }
        JsonNode subscribers = node.path("Subscribers");
        if (subscribers.isArray()) {
            action.setSubscribers(parseSubscribers(subscribers));
        }
        return action;
    }

    private ObjectNode serializeBudget(Budget budget) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("BudgetName", budget.getBudgetName());
        if (budget.getBudgetType() != null) {
            out.put("BudgetType", budget.getBudgetType());
        }
        if (budget.getTimeUnit() != null) {
            out.put("TimeUnit", budget.getTimeUnit());
        }
        if (budget.getBillingViewArn() != null) {
            out.put("BillingViewArn", budget.getBillingViewArn());
        }
        if (budget.getLastUpdatedTime() > 0) {
            out.put("LastUpdatedTime", budget.getLastUpdatedTime());
        }
        Spend limit = budget.getBudgetLimit();
        String unit = "USD";
        if (limit != null) {
            ObjectNode limitNode = out.putObject("BudgetLimit");
            if (limit.getAmount() != null) {
                limitNode.put("Amount", limit.getAmount());
            }
            if (limit.getUnit() != null) {
                limitNode.put("Unit", limit.getUnit());
                unit = limit.getUnit();
            }
        }
        if (budget.getCostFilters() != null && !budget.getCostFilters().isEmpty()) {
            ObjectNode filters = out.putObject("CostFilters");
            for (Map.Entry<String, List<String>> entry : budget.getCostFilters().entrySet()) {
                ArrayNode values = filters.putArray(entry.getKey());
                if (entry.getValue() != null) {
                    for (String value : entry.getValue()) {
                        values.add(value);
                    }
                }
            }
        }
        ObjectNode calculated = out.putObject("CalculatedSpend");
        ObjectNode actual = calculated.putObject("ActualSpend");
        actual.put("Amount", "0");
        actual.put("Unit", unit);
        return out;
    }

    private ObjectNode serializeNotification(Notification notification, boolean includeSubscribers) {
        ObjectNode out = objectMapper.createObjectNode();
        if (notification.getNotificationType() != null) {
            out.put("NotificationType", notification.getNotificationType());
        }
        if (notification.getComparisonOperator() != null) {
            out.put("ComparisonOperator", notification.getComparisonOperator());
        }
        putNumber(out, "Threshold", notification.getThreshold());
        out.put("ThresholdType", notification.resolvedThresholdType());
        if (notification.getNotificationState() != null) {
            out.put("NotificationState", notification.getNotificationState());
        }
        if (includeSubscribers) {
            ArrayNode subscribers = out.putArray("Subscribers");
            for (Subscriber subscriber : notification.getSubscribers()) {
                subscribers.add(serializeSubscriber(subscriber));
            }
        }
        return out;
    }

    private ObjectNode serializeSubscriber(Subscriber subscriber) {
        ObjectNode out = objectMapper.createObjectNode();
        if (subscriber.getSubscriptionType() != null) {
            out.put("SubscriptionType", subscriber.getSubscriptionType());
        }
        if (subscriber.getAddress() != null) {
            out.put("Address", subscriber.getAddress());
        }
        return out;
    }

    private ObjectNode serializeAction(BudgetAction action) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ActionId", action.getActionId());
        out.put("BudgetName", action.getBudgetName());
        if (action.getNotificationType() != null) {
            out.put("NotificationType", action.getNotificationType());
        }
        if (action.getActionType() != null) {
            out.put("ActionType", action.getActionType());
        }
        ObjectNode threshold = out.putObject("ActionThreshold");
        putNumber(threshold, "ActionThresholdValue", action.getActionThresholdValue());
        if (action.getActionThresholdType() != null) {
            threshold.put("ActionThresholdType", action.getActionThresholdType());
        }
        ObjectNode definition = out.putObject("Definition");
        if (action.getIamPolicyArn() != null) {
            ObjectNode iam = definition.putObject("IamActionDefinition");
            iam.put("PolicyArn", action.getIamPolicyArn());
            putStringArray(iam, "Roles", action.getIamRoles());
            putStringArray(iam, "Groups", action.getIamGroups());
            putStringArray(iam, "Users", action.getIamUsers());
        }
        if (action.getScpPolicyId() != null) {
            ObjectNode scp = definition.putObject("ScpActionDefinition");
            scp.put("PolicyId", action.getScpPolicyId());
            putStringArray(scp, "TargetIds", action.getScpTargetIds());
        }
        if (action.getSsmActionSubType() != null) {
            ObjectNode ssm = definition.putObject("SsmActionDefinition");
            ssm.put("ActionSubType", action.getSsmActionSubType());
            if (action.getSsmRegion() != null) {
                ssm.put("Region", action.getSsmRegion());
            }
            putStringArray(ssm, "InstanceIds", action.getSsmInstanceIds());
        }
        if (action.getExecutionRoleArn() != null) {
            out.put("ExecutionRoleArn", action.getExecutionRoleArn());
        }
        if (action.getApprovalModel() != null) {
            out.put("ApprovalModel", action.getApprovalModel());
        }
        if (action.getStatus() != null) {
            out.put("Status", action.getStatus());
        }
        ArrayNode subscribers = out.putArray("Subscribers");
        for (Subscriber subscriber : action.getSubscribers()) {
            subscribers.add(serializeSubscriber(subscriber));
        }
        return out;
    }

    private ObjectNode serializeHistory(ActionHistory history) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("Timestamp", history.getTimestamp());
        if (history.getStatus() != null) {
            out.put("Status", history.getStatus());
        }
        if (history.getEventType() != null) {
            out.put("EventType", history.getEventType());
        }
        ObjectNode details = out.putObject("ActionHistoryDetails");
        details.put("Message", history.getMessage() == null ? "" : history.getMessage());
        if (history.getAction() != null) {
            details.set("Action", serializeAction(history.getAction()));
        }
        return out;
    }

    private Response empty() {
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private String accountId(JsonNode request) {
        String fromRequest = stringOrNull(request, "AccountId");
        return fromRequest != null ? fromRequest : service.currentAccountId();
    }

    private static void putNumber(ObjectNode node, String field, double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && !Double.isNaN(value)) {
            node.put(field, (long) value);
        } else {
            node.put(field, value);
        }
    }

    private static void putStringArray(ObjectNode node, String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        ArrayNode arr = node.putArray(field);
        for (String value : values) {
            arr.add(value);
        }
    }

    private static List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode value : node) {
                result.add(value.asText());
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static Map<String, String> parseResourceTags(JsonNode node) {
        Map<String, String> out = new HashMap<>();
        if (node != null && node.isArray()) {
            for (JsonNode entry : node) {
                String key = stringOrNull(entry, "Key");
                String value = stringOrNull(entry, "Value");
                if (key != null) {
                    out.put(key, value == null ? "" : value);
                }
            }
        }
        return out;
    }

    private static String stringOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : null;
    }

    private static String textOrNumber(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}
