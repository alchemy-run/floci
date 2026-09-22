package io.github.hectorvent.floci.services.budgets;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class BudgetsIntegrationTest {
    private static final String TYPE = "application/x-amz-json-1.1";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/budgets/aws4_request";

    @BeforeAll
    static void configureRestAssured() { RestAssuredJsonUtils.configureAwsContentTypes(); }

    @Test
    void budgetLifecycle() {
        json("AWSBudgetServiceGateway.CreateBudget", "{\"AccountId\":\"000000000000\",\"Budget\":{\"BudgetName\":\"platform\",\"BudgetType\":\"COST\",\"TimeUnit\":\"MONTHLY\",\"BudgetLimit\":{\"Amount\":\"100\",\"Unit\":\"USD\"}}}")
                .statusCode(200);
        json("AWSBudgetServiceGateway.DescribeBudget", "{\"AccountId\":\"000000000000\",\"BudgetName\":\"platform\"}")
                .statusCode(200).body("Budget.BudgetName", equalTo("platform"));
        json("AWSBudgetServiceGateway.CreateNotification", "{\"AccountId\":\"000000000000\",\"BudgetName\":\"platform\",\"Notification\":{\"NotificationType\":\"ACTUAL\",\"ComparisonOperator\":\"GREATER_THAN\",\"Threshold\":80},\"Subscribers\":[{\"SubscriptionType\":\"EMAIL\",\"Address\":\"ops@example.com\"}]}")
                .statusCode(200);
        json("AWSBudgetServiceGateway.DescribeSubscribersForNotification", "{\"AccountId\":\"000000000000\",\"BudgetName\":\"platform\",\"Notification\":{\"NotificationType\":\"ACTUAL\",\"ComparisonOperator\":\"GREATER_THAN\",\"Threshold\":80}}")
                .statusCode(200).body("Subscribers[0].Address", equalTo("ops@example.com"));
    }


    @Test
    void crossAccountRequestsReturnAccessDenied() {
        String foreign = "210987654321";
        String caller = "123456789012";
        jsonAs(caller, "AWSBudgetServiceGateway.DescribeBudget",
                "{\"AccountId\":\"" + foreign + "\",\"BudgetName\":\"foreign\"}")
                .statusCode(400).body("__type", equalTo("AccessDeniedException"));
        jsonAs(caller, "AWSBudgetServiceGateway.ListTagsForResource",
                "{\"ResourceARN\":\"arn:aws:budgets::" + foreign + ":budget/foreign\"}")
                .statusCode(400).body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void missingBudgetReturnsNotFound() {
        json("AWSBudgetServiceGateway.DescribeBudget", "{\"AccountId\":\"000000000000\",\"BudgetName\":\"missing\"}")
                .statusCode(400).body("__type", equalTo("NotFoundException"));
    }

    @Test
    void actionHistoryIsGlobalAcrossSigningRegionsButIsolatedByAccountAndBudget() {
        String account = "123456789012";
        String otherAccount = "210987654321";
        String budgetName = "action-history-wire";
        String target = "AWSBudgetServiceGateway.";
        String identity = "{\"AccountId\":\"" + account + "\",\"BudgetName\":\"" + budgetName + "\"}";
        jsonAs(account, target + "CreateBudget", """
                {"AccountId":"%s","Budget":{"BudgetName":"%s","BudgetType":"COST","TimeUnit":"MONTHLY",
                "BudgetLimit":{"Amount":"100","Unit":"USD"}}}
                """.formatted(account, budgetName)).statusCode(200);
        try {
            String actionId = jsonAs(account, target + "CreateBudgetAction", """
                    {"AccountId":"%s","BudgetName":"%s","NotificationType":"ACTUAL",
                    "ActionType":"APPLY_IAM_POLICY","ApprovalModel":"MANUAL",
                    "ExecutionRoleArn":"arn:aws:iam::%s:role/BudgetExecutionRole",
                    "ActionThreshold":{"ActionThresholdType":"PERCENTAGE","ActionThresholdValue":100},
                    "Definition":{"IamActionDefinition":{"PolicyArn":"arn:aws:iam::aws:policy/ReadOnlyAccess","Users":["alice"]}},
                    "Subscribers":[{"SubscriptionType":"EMAIL","Address":"ops@example.com"}]}
                    """.formatted(account, budgetName, account)).statusCode(200).extract().path("ActionId");
            String historyRequest = """
                    {"AccountId":"%s","BudgetName":"%s","ActionId":"%s"}
                    """.formatted(account, budgetName, actionId);
            jsonAs(account, target + "DescribeBudgetActionHistories", historyRequest).statusCode(200)
                    .body("ActionHistories.size()", equalTo(1))
                    .body("ActionHistories[0].EventType", equalTo("CREATE_ACTION"))
                    .body("ActionHistories[0].Status", equalTo("STANDBY"))
                    .body("ActionHistories[0].Timestamp", instanceOf(Number.class))
                    .body("ActionHistories[0].ActionHistoryDetails.Message", not(emptyString()))
                    .body("ActionHistories[0].ActionHistoryDetails.Action.ActionId", equalTo(actionId))
                    .body("ActionHistories[0].ActionHistoryDetails.Action.BudgetName", equalTo(budgetName));
            String westAuth = "AWS4-HMAC-SHA256 Credential=" + account + "/20260904/us-west-2/budgets/aws4_request";
            given().contentType(TYPE).header("Authorization", westAuth)
                    .header("X-Amz-Target", target + "DescribeBudgetActionHistories").body(historyRequest).post("/").then()
                    .statusCode(200).body("ActionHistories[0].EventType", equalTo("CREATE_ACTION"));
            jsonAs(otherAccount, target + "DescribeBudgetActionHistories", historyRequest)
                    .statusCode(400).body("__type", equalTo("AccessDeniedException"));
            jsonAs(otherAccount, target + "DescribeBudgetActionHistories", historyRequest.replace(account, otherAccount))
                    .statusCode(400).body("__type", equalTo("NotFoundException"));
            jsonAs(account, target + "DescribeBudgetActionHistories", historyRequest.replace(budgetName, "wrong-budget"))
                    .statusCode(400).body("__type", equalTo("NotFoundException"));
            jsonAs(account, target + "DeleteBudgetAction", historyRequest).statusCode(200);
            jsonAs(account, target + "DescribeBudgetActionHistories", historyRequest)
                    .statusCode(400).body("__type", equalTo("NotFoundException"));
        } finally {
            jsonAs(account, target + "DeleteBudget", identity).statusCode(200);
        }
    }

    private static ValidatableResponse json(String target, String body) {
        return given().contentType(TYPE).header("Authorization", AUTH).header("X-Amz-Target", target).body(body).post("/").then();
    }

    private static ValidatableResponse jsonAs(String accountId, String target, String body) {
        String auth = "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260904/us-east-1/budgets/aws4_request";
        return given().contentType(TYPE).header("Authorization", auth).header("X-Amz-Target", target).body(body).post("/").then();
    }
}
