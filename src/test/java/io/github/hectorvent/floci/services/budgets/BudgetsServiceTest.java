package io.github.hectorvent.floci.services.budgets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.budgets.model.BudgetActionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BudgetsServiceTest {
    private static final String ACCOUNT = "123456789012";
    private final ObjectMapper mapper = new ObjectMapper();
    private BudgetsService service;

    @BeforeEach
    void setUp() {
        service = new BudgetsService(AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.<BudgetActionRecord>inMemory(ACCOUNT), mapper);
    }

    @Test
    void describeBudgetsUsesAwsThousandItemLimitAndResetClearsState() {
        service.createBudget(createBudget("platform"));
        ObjectNode list = mapper.createObjectNode().put("AccountId", ACCOUNT).put("MaxResults", 1000);
        assertEquals(1, service.describeBudgets(list).items().size());
        list.put("MaxResults", 1001);
        assertError("InvalidParameterException", () -> service.describeBudgets(list));
        service.clear();
        list.put("MaxResults", 1000);
        assertTrue(service.describeBudgets(list).items().isEmpty());
    }

    @Test
    void subscriberAndNotificationUpdatesPreserveAwsLifecycle() {
        service.createBudget(createBudget("notify"));
        ObjectNode create = notificationRequest("notify", 80);
        create.putArray("Subscribers").addObject().put("SubscriptionType", "EMAIL").put("Address", "first@example.com");
        service.createNotification(create);

        ObjectNode subscriber = notificationRequest("notify", 80);
        subscriber.set("Subscriber", mapper.createObjectNode().put("SubscriptionType", "EMAIL").put("Address", "second@example.com"));
        service.createSubscriber(subscriber);
        assertEquals(2, service.describeSubscribers(notificationRequest("notify", 80)).items().size());

        ObjectNode update = mapper.createObjectNode().put("AccountId", ACCOUNT).put("BudgetName", "notify");
        update.set("OldNotification", notification(80));
        update.set("NewNotification", notification(90));
        service.updateNotification(update);
        assertEquals(1, service.describeNotifications(update).items().size());
    }


    @Test
    void callerScopeRejectsCrossAccountIdsAndArns() {
        ObjectNode request = mapper.createObjectNode().put("AccountId", "210987654321");
        assertError("AccessDeniedException", () -> service.validateCallerScope(request, ACCOUNT));

        ObjectNode arnRequest = mapper.createObjectNode()
                .put("ResourceARN", "arn:aws:budgets::210987654321:budget/foreign");
        assertError("AccessDeniedException", () -> service.validateCallerScope(arnRequest, ACCOUNT));
    }

    @Test
    void budgetActionDefinitionsMustMatchTypeAndContainRequiredFields() {
        service.createBudget(createBudget("actions"));

        ObjectNode emptyIam = actionRequest("actions", "APPLY_IAM_POLICY");
        emptyIam.set("Definition", mapper.createObjectNode().set("IamActionDefinition", mapper.createObjectNode()));
        assertError("InvalidParameterException", () -> service.createBudgetAction(emptyIam));

        ObjectNode noIamTarget = actionRequest("actions", "APPLY_IAM_POLICY");
        noIamTarget.set("Definition", mapper.createObjectNode().set("IamActionDefinition",
                mapper.createObjectNode().put("PolicyArn", "arn:aws:iam::aws:policy/ReadOnlyAccess")));
        assertError("InvalidParameterException", () -> service.createBudgetAction(noIamTarget));

        ObjectNode wrongVariant = actionRequest("actions", "APPLY_IAM_POLICY");
        ObjectNode ssm = mapper.createObjectNode().put("ActionSubType", "STOP_EC2_INSTANCES").put("Region", "us-east-1");
        ssm.putArray("InstanceIds").add("i-12345678");
        wrongVariant.set("Definition", mapper.createObjectNode().set("SsmActionDefinition", ssm));
        assertError("InvalidParameterException", () -> service.createBudgetAction(wrongVariant));

        BudgetActionRecord record = service.createBudgetAction(actionRequest("actions", "APPLY_IAM_POLICY"));
        ObjectNode update = mapper.createObjectNode().put("AccountId", ACCOUNT).put("BudgetName", "actions")
                .put("ActionId", record.getActionId());
        update.set("Definition", mapper.createObjectNode().set("SsmActionDefinition", ssm));
        assertError("InvalidParameterException", () -> service.updateBudgetAction(update));
    }

    @Test
    void actionExecutionUsesAwsStatusAndHistoryEnums() {
        service.createBudget(createBudget("execute"));
        BudgetActionRecord record = service.createBudgetAction(actionRequest("execute", "APPLY_IAM_POLICY"));

        ObjectNode execute = mapper.createObjectNode().put("AccountId", ACCOUNT).put("BudgetName", "execute")
                .put("ActionId", record.getActionId()).put("ExecutionType", "APPROVE_BUDGET_ACTION");
        BudgetActionRecord executed = service.executeBudgetAction(execute);

        assertEquals("EXECUTION_SUCCESS", executed.getAction().path("Status").asText());
        JsonNode history = executed.getHistories().get(executed.getHistories().size() - 1);
        assertEquals("EXECUTE_ACTION", history.path("EventType").asText());
        assertEquals("EXECUTION_SUCCESS", history.path("Status").asText());
    }

    @Test
    void historiesRecordOnlySuccessfulStateChangesAndKeepSnapshots() {
        service.createBudget(createBudget("history"));
        BudgetActionRecord record = service.createBudgetAction(actionRequest("history", "APPLY_IAM_POLICY"));
        ObjectNode request = actionIdentity(record);
        JsonNode created = service.describeBudgetActionHistories(request).items().getFirst();
        assertEquals("CREATE_ACTION", created.path("EventType").asText());
        assertEquals("STANDBY", created.path("Status").asText());
        assertEquals(record.getAction(), created.path("ActionHistoryDetails").path("Action"));
        assertTrue(created.path("Timestamp").isNumber());
        assertFalse(created.path("ActionHistoryDetails").path("Message").asText().isBlank());

        service.updateBudgetAction(request);
        service.executeBudgetAction(request.deepCopy().put("ExecutionType", "RESET_BUDGET_ACTION"));
        assertEquals(1, service.describeBudgetActionHistories(request).items().size());
        ObjectNode update = request.deepCopy().put("ApprovalModel", "AUTOMATIC");
        service.updateBudgetAction(update);
        service.updateBudgetAction(update);
        assertError("InvalidParameterException", () -> service.updateBudgetAction(
                request.deepCopy().put("ApprovalModel", "INVALID")));
        assertError("InvalidParameterException", () -> service.executeBudgetAction(
                request.deepCopy().put("ExecutionType", "INVALID")));

        for (String execution : List.of("APPROVE_BUDGET_ACTION", "REVERSE_BUDGET_ACTION",
                "RESET_BUDGET_ACTION", "RETRY_BUDGET_ACTION")) {
            ObjectNode execute = request.deepCopy().put("ExecutionType", execution);
            service.executeBudgetAction(execute);
            service.executeBudgetAction(execute);
        }
        List<JsonNode> histories = service.describeBudgetActionHistories(request).items();
        assertEquals(List.of("CREATE_ACTION", "UPDATE_ACTION", "EXECUTE_ACTION", "EXECUTE_ACTION",
                        "EXECUTE_ACTION", "EXECUTE_ACTION"),
                histories.stream().map(node -> node.path("EventType").asText()).toList());
        assertEquals(List.of("STANDBY", "STANDBY", "EXECUTION_SUCCESS", "REVERSE_SUCCESS", "STANDBY",
                        "EXECUTION_SUCCESS"),
                histories.stream().map(node -> node.path("Status").asText()).toList());
        assertEquals("MANUAL", histories.getFirst().path("ActionHistoryDetails").path("Action").path("ApprovalModel").asText());
        assertEquals("AUTOMATIC", histories.get(1).path("ActionHistoryDetails").path("Action").path("ApprovalModel").asText());
        for (JsonNode history : histories) {
            assertEquals(history.path("Status"), history.path("ActionHistoryDetails").path("Action").path("Status"));
        }
        ((ObjectNode) histories.getFirst()).put("Status", "CHANGED_BY_CALLER");
        assertEquals("STANDBY", service.describeBudgetActionHistories(request).items().getFirst().path("Status").asText());
    }

    @Test
    void historyPaginationPreservesIdenticalEventsAndBindsTokensToActionAndFilter() {
        service.createBudget(createBudget("pages"));
        BudgetActionRecord record = service.createBudgetAction(actionRequest("pages", "APPLY_IAM_POLICY"));
        ObjectNode request = actionIdentity(record);
        for (int i = 0; i < 2; i++) {
            service.executeBudgetAction(request.deepCopy().put("ExecutionType", "APPROVE_BUDGET_ACTION"));
            service.executeBudgetAction(request.deepCopy().put("ExecutionType", "RESET_BUDGET_ACTION"));
        }
        for (JsonNode history : record.getHistories()) {
            ((ObjectNode) history).put("Timestamp", 100);
        }
        assertEquals(record.getHistories().get(1), record.getHistories().get(3));
        ObjectNode paged = request.deepCopy().put("MaxResults", 1);
        List<JsonNode> collected = new ArrayList<>();
        String token = null;
        String firstToken = null;
        for (int pageNumber = 0; pageNumber < 5; pageNumber++) {
            if (token != null) {
                paged.put("NextToken", token);
            }
            PaginatedResult<JsonNode> page = service.describeBudgetActionHistories(paged);
            assertEquals(1, page.items().size());
            collected.addAll(page.items());
            token = page.nextToken();
            if (pageNumber == 0) {
                firstToken = token;
            }
        }
        assertNull(token);
        assertEquals(record.getHistories(), collected);
        assertNotNull(firstToken);

        BudgetActionRecord other = service.createBudgetAction(actionRequest("pages", "APPLY_IAM_POLICY"));
        ObjectNode wrongAction = actionIdentity(other).put("NextToken", firstToken);
        assertError("InvalidNextTokenException", () -> service.describeBudgetActionHistories(wrongAction));
        ObjectNode wrongFilter = request.deepCopy().put("NextToken", firstToken);
        wrongFilter.putObject("TimePeriod").put("Start", 0);
        assertError("InvalidNextTokenException", () -> service.describeBudgetActionHistories(wrongFilter));
        for (String invalidToken : List.of("", "!invalid!", Base64.getUrlEncoder().encodeToString(
                "unrelated-cursor".getBytes(StandardCharsets.UTF_8)))) {
            assertError("InvalidNextTokenException", () -> service.describeBudgetActionHistories(
                    request.deepCopy().put("NextToken", invalidToken)));
        }
    }

    @Test
    void historyTimePeriodUsesInclusiveStartAndExclusiveEndAndValidatesInputs() {
        service.createBudget(createBudget("period"));
        BudgetActionRecord record = service.createBudgetAction(actionRequest("period", "APPLY_IAM_POLICY"));
        ObjectNode request = actionIdentity(record);
        service.updateBudgetAction(request.deepCopy().put("ApprovalModel", "AUTOMATIC"));
        service.executeBudgetAction(request.deepCopy().put("ExecutionType", "APPROVE_BUDGET_ACTION"));
        for (int index = 0; index < record.getHistories().size(); index++) {
            ((ObjectNode) record.getHistories().get(index)).put("Timestamp", 100 + index * 100);
        }
        request.putObject("TimePeriod").put("Start", 200).put("End", 300);
        assertEquals(List.of(record.getHistories().get(1)), service.describeBudgetActionHistories(request).items());
        request.putObject("TimePeriod").put("End", 200);
        assertEquals(List.of(record.getHistories().getFirst()), service.describeBudgetActionHistories(request).items());
        request.putObject("TimePeriod").put("Start", 300);
        assertEquals(List.of(record.getHistories().getLast()), service.describeBudgetActionHistories(request).items());
        request.putObject("TimePeriod").put("Start", 301);
        PaginatedResult<JsonNode> empty = service.describeBudgetActionHistories(request);
        assertTrue(empty.items().isEmpty());
        assertNull(empty.nextToken());
        request.putObject("TimePeriod").put("Start", 300).put("End", 200);
        assertError("InvalidParameterException", () -> service.describeBudgetActionHistories(request));
        request.putObject("TimePeriod").put("Start", 200).put("End", 200);
        assertError("InvalidParameterException", () -> service.describeBudgetActionHistories(request));
        request.putObject("TimePeriod").put("Start", "not-a-timestamp");
        assertError("InvalidParameterException", () -> service.describeBudgetActionHistories(request));
        request.put("TimePeriod", "invalid");
        assertError("InvalidParameterException", () -> service.describeBudgetActionHistories(request));
        request.remove("TimePeriod");
        for (int max : List.of(0, -1, 101)) {
            assertError("InvalidParameterException", () -> service.describeBudgetActionHistories(request.deepCopy().put("MaxResults", max)));
        }
        assertError("InvalidParameterException", () -> service.describeBudgetActionHistories(request.deepCopy().put("MaxResults", 1.5)));
        assertError("InvalidParameterException", () -> service.describeBudgetActionHistories(request.deepCopy().put("ActionId", "invalid")));
        assertError("NotFoundException", () -> service.describeBudgetActionHistories(
                request.deepCopy().put("ActionId", "00000000-0000-0000-0000-000000000000")));
        assertError("NotFoundException", () -> service.describeBudgetActionHistories(request.deepCopy().put("BudgetName", "other")));
        assertError("NotFoundException", () -> service.describeBudgetActionHistories(request.deepCopy().put("AccountId", "210987654321")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void historiesAndDeletionSurviveStorageReload(String mode, @TempDir Path directory) {
        StorageFactory first = storageFactory(mode, directory);
        ObjectNode request;
        List<String> expected;
        String token;
        try {
            BudgetsService original = new BudgetsService(first, mapper);
            original.createBudget(createBudget("durable"));
            BudgetActionRecord record = original.createBudgetAction(actionRequest("durable", "APPLY_IAM_POLICY"));
            request = actionIdentity(record);
            original.updateBudgetAction(request.deepCopy().put("ApprovalModel", "AUTOMATIC"));
            original.executeBudgetAction(request.deepCopy().put("ExecutionType", "APPROVE_BUDGET_ACTION"));
            // Compare persisted JSON, independent of Jackson's integral node width.
            expected = original.describeBudgetActionHistories(request).items().stream().map(JsonNode::toString).toList();
            token = original.describeBudgetActionHistories(request.deepCopy().put("MaxResults", 1)).nextToken();
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = storageFactory(mode, directory);
        try {
            BudgetsService reloaded = new BudgetsService(second, mapper);
            assertEquals(expected, reloaded.describeBudgetActionHistories(request).items().stream().map(JsonNode::toString).toList());
            assertEquals(expected.subList(1, 3), reloaded.describeBudgetActionHistories(
                    request.deepCopy().put("NextToken", token)).items().stream().map(JsonNode::toString).toList());
            reloaded.deleteBudgetAction(request);
            assertError("NotFoundException", () -> reloaded.describeBudgetActionHistories(request));
        } finally {
            second.shutdownAll();
        }
        StorageFactory third = storageFactory(mode, directory);
        try {
            BudgetsService reloaded = new BudgetsService(third, mapper);
            assertError("NotFoundException", () -> reloaded.describeBudgetActionHistories(request));
            BudgetActionRecord replacement = reloaded.createBudgetAction(actionRequest("durable", "APPLY_IAM_POLICY"));
            ObjectNode replacementRequest = actionIdentity(replacement);
            assertEquals(1, reloaded.describeBudgetActionHistories(replacementRequest).items().size());
            reloaded.deleteBudget(request);
            assertError("NotFoundException", () -> reloaded.describeBudgetActionHistories(replacementRequest));
            reloaded.clear();
        } finally {
            third.shutdownAll();
        }
    }

    private StorageFactory storageFactory(String mode, Path directory) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60_000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn(mode);
        when(access.storageFlushInterval(anyString())).thenReturn(60_000L);
        return new StorageFactory(config, access);
    }

    private ObjectNode actionIdentity(BudgetActionRecord record) {
        return mapper.createObjectNode().put("AccountId", record.getAccountId())
                .put("BudgetName", record.getBudgetName()).put("ActionId", record.getActionId());
    }

    private ObjectNode createBudget(String name) {
        ObjectNode request = mapper.createObjectNode().put("AccountId", ACCOUNT);
        ObjectNode budget = request.putObject("Budget");
        budget.put("BudgetName", name).put("BudgetType", "COST").put("TimeUnit", "MONTHLY");
        budget.putObject("BudgetLimit").put("Amount", "100").put("Unit", "USD");
        return request;
    }


    private ObjectNode actionRequest(String budgetName, String actionType) {
        ObjectNode request = mapper.createObjectNode().put("AccountId", ACCOUNT).put("BudgetName", budgetName)
                .put("NotificationType", "ACTUAL").put("ActionType", actionType).put("ApprovalModel", "MANUAL")
                .put("ExecutionRoleArn", "arn:aws:iam::" + ACCOUNT + ":role/BudgetExecutionRole");
        request.putObject("ActionThreshold").put("ActionThresholdType", "PERCENTAGE").put("ActionThresholdValue", 100);
        ObjectNode iam = mapper.createObjectNode().put("PolicyArn", "arn:aws:iam::aws:policy/ReadOnlyAccess");
        iam.putArray("Users").add("alice");
        request.set("Definition", mapper.createObjectNode().set("IamActionDefinition", iam));
        request.putArray("Subscribers").addObject().put("SubscriptionType", "EMAIL").put("Address", "ops@example.com");
        return request;
    }

    private ObjectNode notificationRequest(String name, double threshold) {
        ObjectNode request = mapper.createObjectNode().put("AccountId", ACCOUNT).put("BudgetName", name);
        request.set("Notification", notification(threshold));
        return request;
    }

    private ObjectNode notification(double threshold) {
        return mapper.createObjectNode().put("NotificationType", "ACTUAL")
                .put("ComparisonOperator", "GREATER_THAN").put("Threshold", threshold).put("ThresholdType", "PERCENTAGE");
    }

    private static void assertError(String code, Runnable operation) {
        AwsException error = assertThrows(AwsException.class, operation::run);
        assertEquals(code, error.getErrorCode());
    }
}
