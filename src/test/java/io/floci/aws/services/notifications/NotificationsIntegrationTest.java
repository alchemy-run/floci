package io.floci.aws.services.notifications;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AWS User Notifications and its contacts: email contacts activated with the code emailed to them; configurations
 * whose event rules put managed EventBridge rules in their Regions; an S3 event that becomes a notification event,
 * aggregated and emailed to an active contact; hubs; managed notification configurations and their channels;
 * organization access; tags.
 */
@QuarkusTest
class NotificationsIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service, String region) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/" + region + "/" + service + "/aws4_request";
    }

    static RequestSpecification un() {
        return given().header("Authorization", auth("notifications", "us-east-1")).contentType("application/json");
    }

    static RequestSpecification contacts() {
        return given().header("Authorization", auth("notifications-contacts", "us-east-1"))
                .contentType("application/json");
    }

    /** An ARN in a request path: RestAssured encodes it as an SDK does. */
    static String path(String arn) {
        return arn;
    }

    /** The mail Floci's SES kept for an address. */
    static List<Map<String, Object>> mail(String address) {
        List<Map<String, Object>> messages = given().get("/_aws/ses").then().statusCode(200).extract().jsonPath()
                .getList("messages");
        return messages.stream().filter(message -> String.valueOf(message.get("Destination")).contains(address))
                .toList();
    }

    static String activeContact(String name, String address) {
        String arn = contacts().body("{\"name\":\"" + name + "\",\"emailAddress\":\"" + address + "\"}")
                .post("/2022-09-19/emailcontacts").then().statusCode(201).extract().path("arn");
        contacts().post("/2022-10-31/emailcontacts/" + path(arn) + "/activate/send").then().statusCode(200);
        String body = String.valueOf(mail(address).get(0).get("Body"));
        String code = body.replaceAll("(?s).*: ([a-z0-9]{7})\\b.*", "$1");
        contacts().put("/emailcontacts/" + path(arn) + "/activate/" + code).then().statusCode(200);
        return arn;
    }

    @Test
    void emailContactsActivateWithTheirCode() {
        String arn = contacts().body("{\"name\":\"on-call\",\"emailAddress\":\"oncall@example.com\",\"tags\":{\"team\":"
                        + "\"ops\"}}").post("/2022-09-19/emailcontacts").then().statusCode(201)
                .body("arn", containsString(":notifications-contacts::000000000000:emailcontact/")).extract()
                .path("arn");
        contacts().body("{\"name\":\"other\",\"emailAddress\":\"oncall@example.com\"}")
                .post("/2022-09-19/emailcontacts").then().statusCode(409);
        contacts().get("/emailcontacts/" + path(arn)).then().statusCode(200)
                .body("emailContact.status", equalTo("inactive")).body("emailContact.address",
                        equalTo("oncall@example.com")).body("emailContact.creationTime", containsString("T"));
        contacts().get("/emailcontacts").then().body("emailContacts.arn", hasItem(arn));
        contacts().get("/tags/" + path(arn)).then().body("tags.team", equalTo("ops"));
        contacts().put("/emailcontacts/" + path(arn) + "/activate/123456").then().statusCode(400);

        contacts().post("/2022-10-31/emailcontacts/" + path(arn) + "/activate/send").then().statusCode(200);
        contacts().post("/2022-10-31/emailcontacts/" + path(arn) + "/activate/send").then().statusCode(429);
        List<Map<String, Object>> sent = mail("oncall@example.com");
        assertEquals(1, sent.size(), sent.toString());
        assertEquals("no-reply@notifications.aws", sent.get(0).get("Source"));
        String code = String.valueOf(sent.get(0).get("Body")).replaceAll("(?s).*: ([a-z0-9]{7})\\b.*", "$1");
        String wrong = code.equals("0000000") ? "0000001" : "0000000";
        contacts().put("/emailcontacts/" + path(arn) + "/activate/" + wrong).then().statusCode(400)
                .header("x-amzn-ErrorType", containsString("ValidationException"));
        contacts().put("/emailcontacts/" + path(arn) + "/activate/" + code).then().statusCode(200);
        contacts().get("/emailcontacts/" + path(arn)).then().body("emailContact.status", equalTo("active"));
        contacts().put("/emailcontacts/" + path(arn) + "/activate/" + code).then().statusCode(409);
        contacts().post("/2022-10-31/emailcontacts/" + path(arn) + "/activate/send").then().statusCode(409);
        contacts().delete("/emailcontacts/" + path(arn)).then().statusCode(200);
        contacts().get("/emailcontacts/" + path(arn)).then().statusCode(404)
                .header("x-amzn-ErrorType", containsString("ResourceNotFoundException"));
    }

    @Test
    void s3EventsBecomeNotificationsEmailedToContacts() {
        String contact = activeContact("s3-watch", "s3watch@example.com");
        String config = un().body("{\"name\":\"uploads\",\"description\":\"New objects\",\"aggregationDuration\":"
                        + "\"SHORT\",\"tags\":{\"team\":\"data\"}}").post("/notification-configurations").then()
                .statusCode(201).body("status", equalTo("INACTIVE")).extract().path("arn");
        assertTrue(config.startsWith("arn:aws:notifications::000000000000:configuration/"), config);
        un().body("{\"name\":\"uploads\",\"description\":\"x\"}").post("/notification-configurations").then()
                .statusCode(409);
        String rule = un().body("{\"notificationConfigurationArn\":\"" + config + "\",\"source\":\"aws.s3\","
                        + "\"eventType\":\"Object Created\",\"eventPattern\":\"{\\\"detail\\\":{\\\"bucket\\\":"
                        + "{\\\"name\\\":[\\\"un-uploads\\\"]}}}\",\"regions\":[\"us-west-2\"]}").post("/event-rules")
                .then().statusCode(201).body("statusSummaryByRegion.'us-west-2'.status", equalTo("CREATING"))
                .extract().path("arn");
        assertTrue(rule.startsWith(config + "/rule/"), rule);
        await().atMost(Duration.ofSeconds(10)).until(() -> "ACTIVE".equals(un().get("/notification-configurations/"
                + path(config)).then().extract().path("status")));
        String managed = un().get("/event-rules/" + path(rule)).then().statusCode(200)
                .body("source", equalTo("aws.s3")).body("regions", equalTo(List.of("us-west-2"))).extract()
                .path("managedRules[0]");
        assertTrue(managed.startsWith("arn:aws:events:us-west-2:000000000000:rule/AWSUserNotificationsManagedRule-"),
                managed);
        given().header("Authorization", auth("events", "us-west-2")).header("X-Amz-Target", "AWSEvents.ListRules")
                .contentType("application/x-amz-json-1.1").body("{\"NamePrefix\":\"AWSUserNotificationsManagedRule\"}")
                .post("/").then().statusCode(200).body("Rules.Arn", hasItem(managed));
        un().body("{\"notificationConfigurationArn\":\"" + config + "\"}").post("/channels/associate/" + path(contact))
                .then().statusCode(201);
        un().body("{\"notificationConfigurationArn\":\"" + config + "\"}").post("/channels/associate/" + path(contact))
                .then().statusCode(409);
        un().queryParam("notificationConfigurationArn", config).get("/channels").then().body("channels",
                hasItem(contact));

        String s3 = auth("s3", "us-west-2");
        given().header("Authorization", s3).body("<CreateBucketConfiguration><LocationConstraint>us-west-2"
                + "</LocationConstraint></CreateBucketConfiguration>").put("/un-uploads").then().statusCode(200);
        given().header("Authorization", s3).body("<NotificationConfiguration><EventBridgeConfiguration/>"
                + "</NotificationConfiguration>").put("/un-uploads?notification").then().statusCode(200);
        given().header("Authorization", s3).body("one").put("/un-uploads/a.txt").then().statusCode(200);
        given().header("Authorization", s3).body("two").put("/un-uploads/b.txt").then().statusCode(200);

        await().atMost(Duration.ofSeconds(10)).until(() -> un().queryParam("includeChildEvents", true)
                .get("/notification-events").then().extract().jsonPath().getList("notificationEvents").size() == 2);
        JsonPath events = un().get("/notification-events").then().statusCode(200).extract().jsonPath();
        assertEquals(1, events.getList("notificationEvents").size(), events.prettify());
        assertEquals("AGGREGATE", events.getString("notificationEvents[0].aggregationEventType"));
        assertEquals(2, events.getInt("notificationEvents[0].aggregationSummary.eventCount"));
        assertEquals("Object Created", events.getString(
                "notificationEvents[0].notificationEvent.sourceEventMetadata.eventType"));
        String aggregate = events.getString("notificationEvents[0].arn");
        un().queryParam("aggregateNotificationEventArn", aggregate).get("/notification-events").then()
                .body("notificationEvents", hasSize(1)).body("notificationEvents[0].aggregationEventType",
                        equalTo("CHILD"));
        un().get("/notification-events/" + path(aggregate)).then().statusCode(200)
                .body("content.sourceEventMetadata.eventOriginRegion", equalTo("us-west-2"))
                .body("content.sourceEventMetadata.relatedResources[0].arn", containsString("un-uploads"))
                .body("content.messageComponents.headline", containsString("Object Created"));
        List<Map<String, Object>> sent = mail("s3watch@example.com");
        assertEquals(2, sent.size(), sent.toString());
        assertTrue(String.valueOf(sent.get(1).get("Subject")).contains("Object Created"), sent.toString());

        un().body("{\"regions\":[\"us-west-2\",\"us-east-2\"]}").put("/event-rules/" + path(rule)).then()
                .statusCode(200).body("statusSummaryByRegion.'us-east-2'.status", equalTo("UPDATING"));
        un().get("/event-rules/" + path(rule)).then().body("managedRules", hasSize(2));
        un().body("{\"name\":\"uploads-renamed\",\"aggregationDuration\":\"NONE\"}")
                .put("/notification-configurations/" + path(config)).then().statusCode(200);
        un().queryParam("eventRuleSource", "aws.s3").get("/notification-configurations").then()
                .body("notificationConfigurations.name", hasItem("uploads-renamed"));
        un().get("/tags/" + path(config)).then().body("tags.team", equalTo("data"));
        un().body("{\"notificationConfigurationArn\":\"" + config + "\"}").post("/channels/disassociate/"
                + path(contact)).then().statusCode(200);
        un().delete("/event-rules/" + path(rule)).then().statusCode(200);
        given().header("Authorization", auth("events", "us-west-2")).header("X-Amz-Target", "AWSEvents.ListRules")
                .contentType("application/x-amz-json-1.1").body("{\"NamePrefix\":\"AWSUserNotificationsManagedRule\"}")
                .post("/").then().statusCode(200).body("Rules.Arn", not(hasItem(managed)));
        un().delete("/notification-configurations/" + path(config)).then().statusCode(200);
        await().atMost(Duration.ofSeconds(10)).until(() -> un().get("/notification-configurations/" + path(config))
                .statusCode() == 404);
    }

    @Test
    void hubsManagedNotificationsAndOrganizations() {
        un().body("{\"notificationHubRegion\":\"us-west-2\"}").post("/notification-hubs").then().statusCode(201)
                .body("statusSummary.status", equalTo("REGISTERING"));
        await().atMost(Duration.ofSeconds(10)).until(() -> un().get("/notification-hubs").then().extract().jsonPath()
                .getList("notificationHubs.findAll { it.statusSummary.status == 'ACTIVE' }").size() == 1);
        un().delete("/notification-hubs/us-west-2").then().statusCode(409);
        un().body("{\"notificationHubRegion\":\"us-east-2\"}").post("/notification-hubs").then().statusCode(201);
        un().body("{\"notificationHubRegion\":\"us-east-2\"}").post("/notification-hubs").then().statusCode(201);
        await().atMost(Duration.ofSeconds(10)).until(() -> un().get("/notification-hubs").then().extract().jsonPath()
                .getList("notificationHubs.findAll { it.statusSummary.status == 'ACTIVE' }").size() == 2);
        un().delete("/notification-hubs/us-east-2").then().statusCode(200)
                .body("statusSummary.status", equalTo("DEREGISTERING"));
        await().atMost(Duration.ofSeconds(10)).until(() -> !un().get("/notification-hubs").then().extract()
                .jsonPath().getList("notificationHubs.notificationHubRegion").contains("us-east-2"));
        un().delete("/notification-hubs/eu-west-3").then().statusCode(404);

        JsonPath managed = un().get("/managed-notification-configurations").then().statusCode(200).extract()
                .jsonPath();
        assertTrue(managed.getList("managedNotificationConfigurations").size() > 0);
        String arn = managed.getString("managedNotificationConfigurations[0].arn");
        assertTrue(arn.contains(":managed-notification-configuration/category/AWS-Health/sub-category/"), arn);
        un().get("/managed-notification-configurations/" + path(arn)).then().statusCode(200)
                .body("category", equalTo("AWS-Health"));
        un().queryParam("managedNotificationConfigurationArn", arn)
                .get("/channels/list-managed-notification-channel-associations").then().statusCode(200)
                .body("channelAssociations[0].channelIdentifier", equalTo("ACCOUNT_PRIMARY"));
        un().body("{\"managedNotificationConfigurationArn\":\"" + arn + "\"}")
                .put("/contacts/associate-managed-notification/ACCOUNT_ALTERNATE_OPERATIONS").then().statusCode(201);
        un().body("{\"managedNotificationConfigurationArn\":\"" + arn + "\"}")
                .put("/contacts/associate-managed-notification/ACCOUNT_ALTERNATE_OPERATIONS").then().statusCode(409);
        String contact = activeContact("managed", "managed@example.com");
        un().body("{\"managedNotificationConfigurationArn\":\"" + arn + "\",\"isSensitiveEventsSubscribed\":true}")
                .put("/channels/associate-managed-notification/" + path(contact)).then().statusCode(201);
        un().queryParam("managedNotificationConfigurationArn", arn)
                .get("/channels/list-managed-notification-channel-associations").then()
                .body("channelAssociations.channelIdentifier", hasItem(contact))
                .body("channelAssociations.channelType", hasItem("EMAIL"));
        un().queryParam("channelIdentifier", contact).get("/managed-notification-configurations").then()
                .body("managedNotificationConfigurations", hasSize(1));
        un().body("{\"managedNotificationConfigurationArn\":\"" + arn + "\"}")
                .put("/channels/disassociate-managed-notification/" + path(contact)).then().statusCode(200);
        un().get("/managed-notification-events").then().statusCode(200).body("managedNotificationEvents", hasSize(0));
        un().get("/managed-notification-events/" + path(arn + "/event/" + "0".repeat(27))).then()
                .statusCode(404);

        un().get("/organization/access").then().statusCode(200)
                .body("notificationsAccessForOrganization.accessStatus", equalTo("DISABLED"));
        String config = un().body("{\"name\":\"org-wide\",\"description\":\"For the organization\"}")
                .post("/notification-configurations").then().statusCode(201).extract().path("arn");
        un().body("{\"notificationConfigurationArn\":\"" + config + "\"}")
                .post("/organizational-units/associate/ou-abcd-12345678").then().statusCode(409);
        un().post("/organization/access").then().statusCode(200);
        un().body("{\"notificationConfigurationArn\":\"" + config + "\"}")
                .post("/organizational-units/associate/ou-abcd-12345678").then().statusCode(201);
        un().queryParam("notificationConfigurationArn", config).get("/organizational-units").then()
                .body("organizationalUnits", equalTo(List.of("ou-abcd-12345678")));
        un().queryParam("notificationConfigurationArn", config).get("/list-member-accounts").then()
                .body("memberAccounts[0].organizationalUnitId", equalTo("ou-abcd-12345678"));
        un().get("/notification-configurations/" + path(config)).then().body("subtype", equalTo("ADMIN_MANAGED"));
        un().delete("/organization/access").then().statusCode(200);
        un().delete("/notification-configurations/" + path(config)).then().statusCode(200);
    }
}
