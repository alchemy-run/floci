package io.github.hectorvent.floci.services.budgets;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for AWS Budgets JSON 1.1 operations used by Alchemy
 * Budget / BudgetAction resources and the Bindings suite.
 *
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1,
 * X-Amz-Target: AWSBudgetServiceGateway.&lt;Action&gt;
 */
@QuarkusTest
@TestProfile(BudgetsIntegrationTest.IsolatedProfile.class)
class BudgetsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/budgets/aws4_request";
    private static final String ACCOUNT = "000000000000";

    public static final class IsolatedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.storage.mode", "memory");
        }
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String budgetBody(String name) {
        return "{\"AccountId\":\"" + ACCOUNT + "\",\"Budget\":{"
                + "\"BudgetName\":\"" + name + "\","
                + "\"BudgetType\":\"COST\","
                + "\"TimeUnit\":\"MONTHLY\","
                + "\"BudgetLimit\":{\"Amount\":\"1000000\",\"Unit\":\"USD\"}},"
                + "\"NotificationsWithSubscribers\":[{"
                + "\"Notification\":{\"NotificationType\":\"ACTUAL\",\"ComparisonOperator\":\"GREATER_THAN\","
                + "\"Threshold\":80,\"ThresholdType\":\"PERCENTAGE\"},"
                + "\"Subscribers\":[{\"SubscriptionType\":\"EMAIL\",\"Address\":\"budget-test@example.com\"}]}],"
                + "\"ResourceTags\":[{\"Key\":\"fixture\",\"Value\":\"bindings\"}]}";
    }

    private static void createBudget(String name) {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.CreateBudget")
            .header("Authorization", AUTH).body(budgetBody(name))
        .when().post("/")
        .then()
            .statusCode(200);
    }

    private static String createAction(String budgetName) {
        String body = "{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"" + budgetName + "\","
                + "\"NotificationType\":\"ACTUAL\",\"ActionType\":\"APPLY_IAM_POLICY\","
                + "\"ActionThreshold\":{\"ActionThresholdValue\":100,\"ActionThresholdType\":\"PERCENTAGE\"},"
                + "\"Definition\":{\"IamActionDefinition\":{"
                + "\"PolicyArn\":\"arn:aws:iam::aws:policy/AWSDenyAll\","
                + "\"Roles\":[\"alchemy-test-budgets-bind-target\"]}},"
                + "\"ExecutionRoleArn\":\"arn:aws:iam::" + ACCOUNT + ":role/alchemy-test-budgets-bind-exec\","
                + "\"ApprovalModel\":\"MANUAL\","
                + "\"Subscribers\":[{\"SubscriptionType\":\"EMAIL\",\"Address\":\"budget-test@example.com\"}]}";
        return given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.CreateBudgetAction")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ActionId", notNullValue())
            .extract().path("ActionId");
    }

    @Test
    void createAndDescribeBudget_returnsLimitAndTimeUnit() {
        createBudget("describe-target");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.DescribeBudget")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"describe-target\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Budget.BudgetName", equalTo("describe-target"))
            .body("Budget.BudgetLimit.Amount", equalTo("1000000"))
            .body("Budget.BudgetLimit.Unit", equalTo("USD"))
            .body("Budget.TimeUnit", equalTo("MONTHLY"))
            .body("Budget.BudgetType", equalTo("COST"));
    }

    @Test
    void createBudget_duplicate_returnsDuplicateRecord() {
        createBudget("dup-budget");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.CreateBudget")
            .header("Authorization", AUTH).body(budgetBody("dup-budget"))
        .when().post("/")
        .then()
            .statusCode(409)
            .body("__type", equalTo("DuplicateRecordException"));
    }

    @Test
    void describeBudget_missing_returnsNotFound() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.DescribeBudget")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"missing-budget\"}")
        .when().post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void describeNotificationsAndSubscribers_roundTrip() {
        createBudget("notif-budget");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.DescribeNotificationsForBudget")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"notif-budget\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Notifications.Threshold", hasItem(80));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.DescribeSubscribersForNotification")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"notif-budget\","
                    + "\"Notification\":{\"NotificationType\":\"ACTUAL\",\"ComparisonOperator\":\"GREATER_THAN\","
                    + "\"Threshold\":80,\"ThresholdType\":\"PERCENTAGE\"}}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Subscribers.size()", equalTo(1))
            .body("Subscribers.SubscriptionType", hasItem("EMAIL"));
    }

    @Test
    void describeBudgetPerformanceHistory_emptyPeriods() {
        createBudget("history-budget");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.DescribeBudgetPerformanceHistory")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"history-budget\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("BudgetPerformanceHistory.BudgetName", equalTo("history-budget"))
            .body("BudgetPerformanceHistory.BudgetedAndActualAmountsList.size()", equalTo(0));
    }

    @Test
    void createBudgetAction_listsStandbyAndCreateHistory() {
        createBudget("action-budget");
        String actionId = createAction("action-budget");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.DescribeBudgetActionsForBudget")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"action-budget\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Actions.size()", greaterThanOrEqualTo(1))
            .body("Actions[0].ActionType", equalTo("APPLY_IAM_POLICY"))
            .body("Actions[0].Status", equalTo("STANDBY"))
            .body("Actions[0].ActionId", equalTo(actionId));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.DescribeBudgetActionHistories")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"action-budget\",\"ActionId\":\"" + actionId + "\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ActionHistories.size()", greaterThanOrEqualTo(1))
            .body("ActionHistories.EventType", hasItem("CREATE_ACTION"));
    }

    @Test
    void executeBudgetAction_resetOnStandby_returnsInvalidParameter() {
        createBudget("exec-budget");
        String actionId = createAction("exec-budget");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.ExecuteBudgetAction")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"exec-budget\","
                    + "\"ActionId\":\"" + actionId + "\",\"ExecutionType\":\"RESET_BUDGET_ACTION\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void listTagsForResource_returnsCreateTags() {
        createBudget("tag-budget");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.ListTagsForResource")
            .header("Authorization", AUTH)
            .body("{\"ResourceARN\":\"arn:aws:budgets::" + ACCOUNT + ":budget/tag-budget\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ResourceTags.Key", hasItem("fixture"))
            .body("ResourceTags.Value", hasItem("bindings"));
    }

    @Test
    void deleteBudget_isIdempotentAfterGone() {
        createBudget("delete-budget");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.DeleteBudget")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"delete-budget\"}")
        .when().post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.DeleteBudget")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"delete-budget\"}")
        .when().post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void updateBudget_replacesLimit_keepsNotifications() {
        createBudget("update-limit");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.UpdateBudget")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"NewBudget\":{"
                    + "\"BudgetName\":\"update-limit\",\"BudgetType\":\"COST\","
                    + "\"TimeUnit\":\"MONTHLY\",\"BudgetLimit\":{\"Amount\":\"250\",\"Unit\":\"USD\"}}}")
        .when().post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.DescribeBudget")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"update-limit\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Budget.BudgetLimit.Amount", equalTo("250"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.DescribeNotificationsForBudget")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"update-limit\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Notifications", hasSize(1));
    }

    @Test
    void createAndDeleteSubscriber_swapsAddress() {
        createBudget("swap-sub");
        String notification = "{\"NotificationType\":\"ACTUAL\",\"ComparisonOperator\":\"GREATER_THAN\","
                + "\"Threshold\":80,\"ThresholdType\":\"PERCENTAGE\"}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.CreateSubscriber")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"swap-sub\","
                    + "\"Notification\":" + notification + ","
                    + "\"Subscriber\":{\"SubscriptionType\":\"EMAIL\",\"Address\":\"budget-test-updated@example.com\"}}")
        .when().post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.DeleteSubscriber")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"swap-sub\","
                    + "\"Notification\":" + notification + ","
                    + "\"Subscriber\":{\"SubscriptionType\":\"EMAIL\",\"Address\":\"budget-test@example.com\"}}")
        .when().post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.DescribeSubscribersForNotification")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"swap-sub\","
                    + "\"Notification\":" + notification + "}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Subscribers", hasSize(1))
            .body("Subscribers[0].Address", equalTo("budget-test-updated@example.com"));
    }

    @Test
    void deleteNotification_emptiesList() {
        createBudget("drop-notif");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.DeleteNotification")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"drop-notif\","
                    + "\"Notification\":{\"NotificationType\":\"ACTUAL\",\"ComparisonOperator\":\"GREATER_THAN\","
                    + "\"Threshold\":80,\"ThresholdType\":\"PERCENTAGE\"}}")
        .when().post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.DescribeNotificationsForBudget")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\",\"BudgetName\":\"drop-notif\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Notifications", hasSize(0));
    }

    @Test
    void tagResource_updatesTeam() {
        createBudget("tag-update");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.TagResource")
            .header("Authorization", AUTH)
            .body("{\"ResourceARN\":\"arn:aws:budgets::" + ACCOUNT + ":budget/tag-update\","
                    + "\"ResourceTags\":[{\"Key\":\"Team\",\"Value\":\"alchemy-test-updated\"}]}")
        .when().post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.ListTagsForResource")
            .header("Authorization", AUTH)
            .body("{\"ResourceARN\":\"arn:aws:budgets::" + ACCOUNT + ":budget/tag-update\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ResourceTags.find { it.Key == 'Team' }.Value", equalTo("alchemy-test-updated"));
    }

    @Test
    void describeBudgets_includesCreated() {
        createBudget("listed-budget");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBudgetServiceGateway.DescribeBudgets")
            .header("Authorization", AUTH)
            .body("{\"AccountId\":\"" + ACCOUNT + "\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Budgets.BudgetName", hasItem("listed-budget"));
    }
}
