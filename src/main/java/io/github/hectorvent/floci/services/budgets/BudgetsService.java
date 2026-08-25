package io.github.hectorvent.floci.services.budgets;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.budgets.model.ActionHistory;
import io.github.hectorvent.floci.services.budgets.model.Budget;
import io.github.hectorvent.floci.services.budgets.model.BudgetAction;
import io.github.hectorvent.floci.services.budgets.model.Notification;
import io.github.hectorvent.floci.services.budgets.model.Subscriber;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * AWS Budgets management plane. Budgets are global (no region in the ARN).
 *
 * @see <a href="https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_Operations_AWS_Budgets.html">AWS Budgets API</a>
 */
@ApplicationScoped
public class BudgetsService {

    private static final Logger LOG = Logger.getLogger(BudgetsService.class);
    private static final Set<String> TIME_UNITS = Set.of("DAILY", "MONTHLY", "QUARTERLY", "ANNUALLY", "CUSTOM");
    private static final Set<String> BUDGET_TYPES = Set.of(
            "USAGE", "COST", "RI_UTILIZATION", "RI_COVERAGE",
            "SAVINGS_PLANS_UTILIZATION", "SAVINGS_PLANS_COVERAGE");
    private static final Set<String> ACTION_TYPES = Set.of("APPLY_IAM_POLICY", "APPLY_SCP_POLICY", "RUN_SSM_DOCUMENTS");
    private static final Set<String> EXECUTION_TYPES = Set.of(
            "APPROVE_BUDGET_ACTION", "RETRY_BUDGET_ACTION",
            "REVERSE_BUDGET_ACTION", "RESET_BUDGET_ACTION");

    private final StorageBackend<String, Budget> budgetStore;
    private final StorageBackend<String, BudgetAction> actionStore;
    private final RegionResolver regionResolver;

    @Inject
    public BudgetsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("budgets", "budgets.json",
                        new TypeReference<Map<String, Budget>>() {}),
                storageFactory.create("budgets", "budget-actions.json",
                        new TypeReference<Map<String, BudgetAction>>() {}),
                regionResolver);
    }

    BudgetsService(StorageBackend<String, Budget> budgetStore,
                   StorageBackend<String, BudgetAction> actionStore,
                   RegionResolver regionResolver) {
        this.budgetStore = budgetStore;
        this.actionStore = actionStore;
        this.regionResolver = regionResolver;
    }

    public Budget createBudget(Budget incoming, List<Notification> notifications, Map<String, String> tags) {
        validateBudget(incoming, true);
        if (budgetStore.get(incoming.getBudgetName()).isPresent()) {
            throw new AwsException("DuplicateRecordException",
                    "Budget already exists: " + incoming.getBudgetName(), 409);
        }
        incoming.setLastUpdatedTime(nowSeconds());
        if (notifications != null) {
            for (Notification notification : notifications) {
                validateNotification(notification, true);
            }
            incoming.setNotifications(notifications);
        }
        if (tags != null && !tags.isEmpty()) {
            incoming.getTags().putAll(tags);
        }
        budgetStore.put(incoming.getBudgetName(), incoming);
        LOG.infov("Created budget {0}", incoming.getBudgetName());
        return incoming;
    }

    public Budget describeBudget(String budgetName) {
        return requireBudget(budgetName);
    }

    public List<Budget> describeBudgets() {
        return new ArrayList<>(budgetStore.scan(key -> true));
    }

    public Budget updateBudget(Budget incoming) {
        validateBudget(incoming, false);
        Budget existing = requireBudget(incoming.getBudgetName());
        existing.setBudgetLimit(incoming.getBudgetLimit());
        existing.setCostFilters(incoming.getCostFilters());
        existing.setTimeUnit(incoming.getTimeUnit() != null ? incoming.getTimeUnit() : existing.getTimeUnit());
        existing.setBudgetType(incoming.getBudgetType() != null ? incoming.getBudgetType() : existing.getBudgetType());
        existing.setBillingViewArn(incoming.getBillingViewArn());
        existing.setLastUpdatedTime(nowSeconds());
        budgetStore.put(existing.getBudgetName(), existing);
        return existing;
    }

    public void deleteBudget(String budgetName) {
        requireBudget(budgetName);
        budgetStore.delete(budgetName);
        for (BudgetAction action : new ArrayList<>(actionStore.scan(key -> true))) {
            if (budgetName.equals(action.getBudgetName())) {
                actionStore.delete(actionKey(action.getBudgetName(), action.getActionId()));
            }
        }
        LOG.infov("Deleted budget {0}", budgetName);
    }

    public void createNotification(String budgetName, Notification notification) {
        Budget budget = requireBudget(budgetName);
        validateNotification(notification, true);
        if (findNotification(budget, notification).isPresent()) {
            throw new AwsException("DuplicateRecordException",
                    "Notification already exists on budget " + budgetName, 409);
        }
        budget.getNotifications().add(notification);
        budget.setLastUpdatedTime(nowSeconds());
        budgetStore.put(budgetName, budget);
    }

    public List<Notification> describeNotifications(String budgetName) {
        return new ArrayList<>(requireBudget(budgetName).getNotifications());
    }

    public void deleteNotification(String budgetName, Notification identity) {
        Budget budget = requireBudget(budgetName);
        Notification existing = findNotification(budget, identity)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Notification does not exist on budget " + budgetName, 404));
        budget.getNotifications().remove(existing);
        budget.setLastUpdatedTime(nowSeconds());
        budgetStore.put(budgetName, budget);
    }

    public void updateNotification(String budgetName, Notification oldIdentity, Notification incoming) {
        Budget budget = requireBudget(budgetName);
        Notification existing = findNotification(budget, oldIdentity)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Notification does not exist on budget " + budgetName, 404));
        validateNotificationIdentity(incoming);
        if (!existing.sameIdentity(incoming) && findNotification(budget, incoming).isPresent()) {
            throw new AwsException("DuplicateRecordException",
                    "Notification already exists on budget " + budgetName, 409);
        }
        existing.setNotificationType(incoming.getNotificationType());
        existing.setComparisonOperator(incoming.getComparisonOperator());
        existing.setThreshold(incoming.getThreshold());
        existing.setThresholdType(incoming.getThresholdType());
        if (incoming.getNotificationState() != null) {
            existing.setNotificationState(incoming.getNotificationState());
        }
        budget.setLastUpdatedTime(nowSeconds());
        budgetStore.put(budgetName, budget);
    }

    public void createSubscriber(String budgetName, Notification identity, Subscriber subscriber) {
        Budget budget = requireBudget(budgetName);
        Notification notification = findNotification(budget, identity)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Notification does not exist on budget " + budgetName, 404));
        validateSubscriber(subscriber);
        if (findSubscriber(notification, subscriber).isPresent()) {
            throw new AwsException("DuplicateRecordException",
                    "Subscriber already exists on the notification", 409);
        }
        notification.getSubscribers().add(subscriber);
        budget.setLastUpdatedTime(nowSeconds());
        budgetStore.put(budgetName, budget);
    }

    public List<Subscriber> describeSubscribers(String budgetName, Notification identity) {
        Budget budget = requireBudget(budgetName);
        Notification notification = findNotification(budget, identity)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Notification does not exist on budget " + budgetName, 404));
        return new ArrayList<>(notification.getSubscribers());
    }

    public void deleteSubscriber(String budgetName, Notification identity, Subscriber subscriber) {
        Budget budget = requireBudget(budgetName);
        Notification notification = findNotification(budget, identity)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Notification does not exist on budget " + budgetName, 404));
        Subscriber existing = findSubscriber(notification, subscriber)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Subscriber does not exist on the notification", 404));
        notification.getSubscribers().remove(existing);
        if (notification.getSubscribers().isEmpty()) {
            budget.getNotifications().remove(notification);
        }
        budget.setLastUpdatedTime(nowSeconds());
        budgetStore.put(budgetName, budget);
    }

    public void updateSubscriber(String budgetName, Notification identity,
                                 Subscriber oldSubscriber, Subscriber incoming) {
        Budget budget = requireBudget(budgetName);
        Notification notification = findNotification(budget, identity)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Notification does not exist on budget " + budgetName, 404));
        Subscriber existing = findSubscriber(notification, oldSubscriber)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Subscriber does not exist on the notification", 404));
        validateSubscriber(incoming);
        existing.setSubscriptionType(incoming.getSubscriptionType());
        existing.setAddress(incoming.getAddress());
        budget.setLastUpdatedTime(nowSeconds());
        budgetStore.put(budgetName, budget);
    }

    public BudgetAction createBudgetAction(BudgetAction incoming, Map<String, String> tags) {
        requireBudget(incoming.getBudgetName());
        validateAction(incoming, true);
        incoming.setActionId(UUID.randomUUID().toString());
        incoming.setStatus("STANDBY");
        if (incoming.getApprovalModel() == null || incoming.getApprovalModel().isEmpty()) {
            incoming.setApprovalModel("MANUAL");
        }
        if (tags != null && !tags.isEmpty()) {
            incoming.getTags().putAll(tags);
        }
        recordHistory(incoming, "CREATE_ACTION", "Action created");
        actionStore.put(actionKey(incoming.getBudgetName(), incoming.getActionId()), incoming);
        LOG.infov("Created budget action {0} on {1}", incoming.getActionId(), incoming.getBudgetName());
        return incoming;
    }

    public BudgetAction describeBudgetAction(String budgetName, String actionId) {
        requireBudget(budgetName);
        return requireAction(budgetName, actionId);
    }

    public List<BudgetAction> describeBudgetActionsForBudget(String budgetName) {
        requireBudget(budgetName);
        List<BudgetAction> result = new ArrayList<>();
        for (BudgetAction action : actionStore.scan(key -> true)) {
            if (budgetName.equals(action.getBudgetName())) {
                result.add(action);
            }
        }
        return result;
    }

    public List<BudgetAction> describeBudgetActionsForAccount() {
        return new ArrayList<>(actionStore.scan(key -> true));
    }

    public BudgetAction updateBudgetAction(String budgetName, String actionId, BudgetAction patch) {
        BudgetAction existing = requireAction(budgetName, actionId);
        if (patch.getNotificationType() != null) {
            existing.setNotificationType(patch.getNotificationType());
        }
        if (patch.getActionThresholdType() != null) {
            existing.setActionThresholdType(patch.getActionThresholdType());
        }
        if (patch.getActionThresholdValue() != 0 || patch.getActionThresholdType() != null) {
            existing.setActionThresholdValue(patch.getActionThresholdValue());
        }
        if (patch.getExecutionRoleArn() != null) {
            existing.setExecutionRoleArn(patch.getExecutionRoleArn());
        }
        if (patch.getApprovalModel() != null) {
            existing.setApprovalModel(patch.getApprovalModel());
        }
        if (patch.getSubscribers() != null && !patch.getSubscribers().isEmpty()) {
            existing.setSubscribers(patch.getSubscribers());
        }
        copyDefinition(patch, existing);
        recordHistory(existing, "UPDATE_ACTION", "Action updated");
        actionStore.put(actionKey(budgetName, actionId), existing);
        return existing;
    }

    public BudgetAction deleteBudgetAction(String budgetName, String actionId) {
        BudgetAction existing = requireAction(budgetName, actionId);
        actionStore.delete(actionKey(budgetName, actionId));
        return existing;
    }

    public List<ActionHistory> describeBudgetActionHistories(String budgetName, String actionId) {
        return new ArrayList<>(requireAction(budgetName, actionId).getHistories());
    }

    public String executeBudgetAction(String budgetName, String actionId, String executionType) {
        BudgetAction action = requireAction(budgetName, actionId);
        requireNonEmpty(executionType, "ExecutionType");
        if (!EXECUTION_TYPES.contains(executionType)) {
            throw new AwsException("InvalidParameterException",
                    "ExecutionType must be one of " + EXECUTION_TYPES, 400);
        }
        if ("RESET_BUDGET_ACTION".equals(executionType) && "STANDBY".equals(action.getStatus())) {
            throw new AwsException("InvalidParameterException",
                    "Action is in STANDBY and cannot be reset.", 400);
        }
        recordHistory(action, "EXECUTE_ACTION", "Executed " + executionType);
        if ("RESET_BUDGET_ACTION".equals(executionType)) {
            action.setStatus("STANDBY");
        }
        actionStore.put(actionKey(budgetName, actionId), action);
        return executionType;
    }

    public Map<String, String> listTags(String resourceArn) {
        return new HashMap<>(resolveTagged(resourceArn).tags());
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        Tagged target = resolveTagged(resourceArn);
        if (tags != null) {
            target.tags().putAll(tags);
        }
        persistTagged(target);
    }

    public void untagResource(String resourceArn, List<String> keys) {
        Tagged target = resolveTagged(resourceArn);
        if (keys != null) {
            for (String key : keys) {
                target.tags().remove(key);
            }
        }
        persistTagged(target);
    }

    public String budgetArn(String budgetName) {
        return "arn:aws:budgets::" + regionResolver.getAccountId() + ":budget/" + budgetName;
    }

    public String actionArn(String budgetName, String actionId) {
        return budgetArn(budgetName) + "/action/" + actionId;
    }

    public String currentAccountId() {
        return regionResolver.getAccountId();
    }

    private Budget requireBudget(String budgetName) {
        requireNonEmpty(budgetName, "BudgetName");
        return budgetStore.get(budgetName).orElseThrow(() -> new AwsException(
                "NotFoundException", "Budget does not exist: " + budgetName, 404));
    }

    private BudgetAction requireAction(String budgetName, String actionId) {
        requireNonEmpty(budgetName, "BudgetName");
        requireNonEmpty(actionId, "ActionId");
        return actionStore.get(actionKey(budgetName, actionId)).orElseThrow(() -> new AwsException(
                "NotFoundException", "Action " + actionId + " does not exist on budget " + budgetName, 404));
    }

    private Optional<Notification> findNotification(Budget budget, Notification identity) {
        for (Notification candidate : budget.getNotifications()) {
            if (candidate.sameIdentity(identity)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Optional<Subscriber> findSubscriber(Notification notification, Subscriber identity) {
        for (Subscriber candidate : notification.getSubscribers()) {
            if (candidate.sameAs(identity)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private void validateBudget(Budget budget, boolean creating) {
        if (budget == null) {
            throw new AwsException("InvalidParameterException", "Budget is required.", 400);
        }
        requireNonEmpty(budget.getBudgetName(), "BudgetName");
        if (budget.getBudgetName().length() > 100) {
            throw new AwsException("InvalidParameterException",
                    "BudgetName must be at most 100 characters long.", 400);
        }
        if (creating || budget.getBudgetType() != null) {
            requireNonEmpty(budget.getBudgetType(), "BudgetType");
            if (!BUDGET_TYPES.contains(budget.getBudgetType())) {
                throw new AwsException("InvalidParameterException",
                        "BudgetType must be one of " + BUDGET_TYPES, 400);
            }
        }
        if (creating || budget.getTimeUnit() != null) {
            requireNonEmpty(budget.getTimeUnit(), "TimeUnit");
            if (!TIME_UNITS.contains(budget.getTimeUnit())) {
                throw new AwsException("InvalidParameterException",
                        "TimeUnit must be one of " + TIME_UNITS, 400);
            }
        }
    }

    private void validateNotification(Notification notification, boolean requireSubscriber) {
        validateNotificationIdentity(notification);
        if (requireSubscriber && (notification.getSubscribers() == null
                || notification.getSubscribers().isEmpty())) {
            throw new AwsException("InvalidParameterException",
                    "At least one Subscriber is required.", 400);
        }
        if (notification.getSubscribers() != null) {
            for (Subscriber subscriber : notification.getSubscribers()) {
                validateSubscriber(subscriber);
            }
        }
    }

    private void validateNotificationIdentity(Notification notification) {
        if (notification == null) {
            throw new AwsException("InvalidParameterException", "Notification is required.", 400);
        }
        requireNonEmpty(notification.getNotificationType(), "NotificationType");
        requireNonEmpty(notification.getComparisonOperator(), "ComparisonOperator");
    }

    private void validateSubscriber(Subscriber subscriber) {
        if (subscriber == null) {
            throw new AwsException("InvalidParameterException", "Subscriber is required.", 400);
        }
        requireNonEmpty(subscriber.getSubscriptionType(), "SubscriptionType");
        requireNonEmpty(subscriber.getAddress(), "Address");
    }

    private void validateAction(BudgetAction action, boolean creating) {
        if (action == null) {
            throw new AwsException("InvalidParameterException", "Action is required.", 400);
        }
        requireNonEmpty(action.getBudgetName(), "BudgetName");
        requireNonEmpty(action.getNotificationType(), "NotificationType");
        requireNonEmpty(action.getActionType(), "ActionType");
        if (!ACTION_TYPES.contains(action.getActionType())) {
            throw new AwsException("InvalidParameterException",
                    "ActionType must be one of " + ACTION_TYPES, 400);
        }
        requireNonEmpty(action.getActionThresholdType(), "ActionThresholdType");
        requireNonEmpty(action.getExecutionRoleArn(), "ExecutionRoleArn");
        requireNonEmpty(action.getApprovalModel(), "ApprovalModel");
        if (creating && (action.getSubscribers() == null || action.getSubscribers().isEmpty())) {
            throw new AwsException("InvalidParameterException",
                    "At least one Subscriber is required.", 400);
        }
        if ("APPLY_IAM_POLICY".equals(action.getActionType())
                && (action.getIamPolicyArn() == null || action.getIamPolicyArn().isEmpty())) {
            throw new AwsException("InvalidParameterException",
                    "Definition.IamActionDefinition.PolicyArn is required.", 400);
        }
    }

    private void copyDefinition(BudgetAction from, BudgetAction to) {
        if (from.getIamPolicyArn() != null) {
            to.setIamPolicyArn(from.getIamPolicyArn());
            to.setIamRoles(from.getIamRoles());
            to.setIamGroups(from.getIamGroups());
            to.setIamUsers(from.getIamUsers());
        }
        if (from.getScpPolicyId() != null) {
            to.setScpPolicyId(from.getScpPolicyId());
            to.setScpTargetIds(from.getScpTargetIds());
        }
        if (from.getSsmActionSubType() != null) {
            to.setSsmActionSubType(from.getSsmActionSubType());
            to.setSsmRegion(from.getSsmRegion());
            to.setSsmInstanceIds(from.getSsmInstanceIds());
        }
    }

    private void recordHistory(BudgetAction action, String eventType, String message) {
        ActionHistory history = new ActionHistory();
        history.setTimestamp(nowSeconds());
        history.setStatus(action.getStatus());
        history.setEventType(eventType);
        history.setMessage(message);
        history.setAction(action.snapshot());
        action.getHistories().add(history);
    }

    private Tagged resolveTagged(String resourceArn) {
        requireNonEmpty(resourceArn, "ResourceARN");
        String prefix = "arn:aws:budgets::";
        int budgetIdx = resourceArn.indexOf(":budget/");
        if (!resourceArn.startsWith(prefix) || budgetIdx < 0) {
            throw new AwsException("InvalidParameterException",
                    "ResourceARN is not a valid budgets ARN.", 400);
        }
        String rest = resourceArn.substring(budgetIdx + ":budget/".length());
        int actionIdx = rest.indexOf("/action/");
        if (actionIdx < 0) {
            Budget budget = requireBudget(rest);
            return new Tagged(budget, null);
        }
        String budgetName = rest.substring(0, actionIdx);
        String actionId = rest.substring(actionIdx + "/action/".length());
        BudgetAction action = requireAction(budgetName, actionId);
        return new Tagged(null, action);
    }

    private void persistTagged(Tagged tagged) {
        if (tagged.budget() != null) {
            budgetStore.put(tagged.budget().getBudgetName(), tagged.budget());
        } else {
            BudgetAction action = tagged.action();
            actionStore.put(actionKey(action.getBudgetName(), action.getActionId()), action);
        }
    }

    private static String actionKey(String budgetName, String actionId) {
        return budgetName + "/" + actionId;
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static void requireNonEmpty(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new AwsException("InvalidParameterException",
                    "1 validation error detected: Value at '" + field
                            + "' failed to satisfy constraint: Member must not be null.", 400);
        }
    }

    private record Tagged(Budget budget, BudgetAction action) {
        Map<String, String> tags() {
            return budget != null ? budget.getTags() : action.getTags();
        }
    }
}
