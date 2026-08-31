package io.github.hectorvent.floci.services.redshift;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedshiftEventSubscriptionIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String SUB = "alchemy-redshift-event-sub-it";
    private static final String TOPIC =
            "arn:aws:sns:us-east-1:000000000000:alchemy-redshift-alerts";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/redshift/aws4_request, "
                    + "SignedHeaders=content-type;host, Signature=test";

    @Test
    @Order(1)
    void describeEventSubscriptions_missing_subscriptionNotFound() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeEventSubscriptions")
            .formParam("SubscriptionName", "alchemy-nonexistent-redshift-sub-probe")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("SubscriptionNotFound"))
            .body(not(containsString("UnsupportedOperation")));
    }

    @Test
    @Order(2)
    void createEventSubscription_withFiltersAndTags() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateEventSubscription")
            .formParam("SubscriptionName", SUB)
            .formParam("SnsTopicArn", TOPIC)
            .formParam("SourceType", "cluster")
            .formParam("EventCategories.member.1", "monitoring")
            .formParam("EventCategories.member.2", "management")
            .formParam("Severity", "INFO")
            .formParam("Enabled", "true")
            .formParam("Tags.member.1.Key", "fixture")
            .formParam("Tags.member.1.Value", "redshift-event-subscription")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(SUB))
            .body(containsString(TOPIC))
            .body(containsString("cluster"))
            .body(containsString("monitoring"))
            .body(containsString("management"))
            .body(containsString("INFO"))
            .body(containsString("<Enabled>true</Enabled>"))
            .body(containsString("fixture"));
    }

    @Test
    @Order(3)
    void describeEventSubscription_returnsCreatedFilters() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeEventSubscriptions")
            .formParam("SubscriptionName", SUB)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeEventSubscriptionsResponse.DescribeEventSubscriptionsResult"
                    + ".EventSubscriptionsList.EventSubscription.CustSubscriptionId", equalTo(SUB))
            .body("DescribeEventSubscriptionsResponse.DescribeEventSubscriptionsResult"
                    + ".EventSubscriptionsList.EventSubscription.Status", equalTo("active"))
            .body("DescribeEventSubscriptionsResponse.DescribeEventSubscriptionsResult"
                    + ".EventSubscriptionsList.EventSubscription.SourceType", equalTo("cluster"))
            .body("DescribeEventSubscriptionsResponse.DescribeEventSubscriptionsResult"
                    + ".EventSubscriptionsList.EventSubscription.Severity", equalTo("INFO"))
            .body("DescribeEventSubscriptionsResponse.DescribeEventSubscriptionsResult"
                    + ".EventSubscriptionsList.EventSubscription.Enabled", equalTo("true"))
            .body(containsString("fixture"))
            .body(containsString("redshift-event-subscription"));
    }

    @Test
    @Order(4)
    void modifyEventSubscription_replacesFiltersInPlace() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "ModifyEventSubscription")
            .formParam("SubscriptionName", SUB)
            .formParam("SnsTopicArn", TOPIC)
            .formParam("SourceType", "cluster")
            .formParam("EventCategories.member.1", "monitoring")
            .formParam("Severity", "ERROR")
            .formParam("Enabled", "false")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Severity>ERROR</Severity>"))
            .body(containsString("<Enabled>false</Enabled>"))
            .body(containsString("monitoring"))
            .body(not(containsString("management")));
    }

    @Test
    @Order(5)
    void createTags_onEventSubscriptionArn() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateTags")
            .formParam("ResourceName",
                    "arn:aws:redshift:us-east-1:000000000000:eventsubscription:" + SUB)
            .formParam("Tags.member.1.Key", "env")
            .formParam("Tags.member.1.Value", "test")
        .when().post("/")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeEventSubscriptions")
            .formParam("SubscriptionName", SUB)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("env"))
            .body(containsString("test"));
    }

    @Test
    @Order(6)
    void deleteEventSubscription_thenDescribeIsNotFound() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DeleteEventSubscription")
            .formParam("SubscriptionName", SUB)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("UnsupportedOperation")));

        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeEventSubscriptions")
            .formParam("SubscriptionName", SUB)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("SubscriptionNotFound"));
    }
}
