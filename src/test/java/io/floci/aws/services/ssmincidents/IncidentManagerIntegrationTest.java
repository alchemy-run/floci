package io.floci.aws.services.ssmincidents;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Incident Manager end to end: onboarding with a replication set, contacts whose channels are activated with
 * codes delivered through SES and SNS, rotations and their shifts, a response plan that notifies an SNS topic and
 * engages an escalation plan, pages with accept codes, the incident timeline, and offboarding.
 */
@QuarkusTest
class IncidentManagerIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification incidents() {
        return given().header("Authorization", auth("ssm-incidents")).contentType("application/json");
    }

    static RequestSpecification contacts(String action, String body) {
        return given().header("Authorization", auth("ssm-contacts")).header("X-Amz-Target", "SSMContacts." + action)
                .contentType("application/x-amz-json-1.1").body(body);
    }

    static RequestSpecification query(String service) {
        return given().header("Authorization", auth(service)).contentType("application/x-www-form-urlencoded");
    }

    /** The six-digit code that follows a label such as "code is" or "Accept code:". */
    static String code(String text, String label) {
        Matcher matcher = Pattern.compile(Pattern.quote(label) + "\\s*(\\d{6})").matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** The newest email sent to an address, from the SES inspection mailbox. */
    static String lastEmailTo(String address) {
        List<Map<String, Object>> messages = given().get("/_aws/ses").then().statusCode(200).extract()
                .jsonPath().getList("messages");
        String found = null;
        for (Map<String, Object> message : messages) {
            if (String.valueOf(message.get("Destination")).contains(address)) {
                found = String.valueOf(message);
            }
        }
        return found;
    }

    static String lastSmsTo(String phone) {
        List<String> messages = given().queryParam("phone", phone).get("/_aws/sns").then().statusCode(200).extract()
                .jsonPath().getList("messages.Message");
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    @Test
    void incidentsEngageContacts() {
        // Before onboarding, contacts answer as AWS does for an account without Incident Manager.
        contacts("ListContacts", "{}").post("/").then().statusCode(400)
                .body("message", containsString("Account not found for the request"));
        incidents().queryParam("arn", "arn:aws:ssm-incidents::000000000000:replication-set/00000000-0000-4000-8000-000000000000").get("/getReplicationSet")
                .then().statusCode(404);

        String set = incidents().body("{\"regions\":{\"us-east-1\":{}},\"tags\":{\"team\":\"ops\"}}")
                .post("/createReplicationSet").then().statusCode(201).extract().path("arn");
        incidents().body("{\"regions\":{\"us-east-1\":{}}}").post("/createReplicationSet").then().statusCode(409);
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() -> "ACTIVE".equals(
                incidents().queryParam("arn", set).get("/getReplicationSet").then().extract()
                        .path("replicationSet.status")));
        incidents().queryParam("arn", set).get("/getReplicationSet").then()
                .body("replicationSet.regionMap.'us-east-1'.status", equalTo("ACTIVE"));

        // A personal contact with an email channel, activated with the code it was sent.
        String person = contacts("CreateContact", "{\"Alias\":\"ana\",\"DisplayName\":\"Ana\",\"Type\":\"PERSONAL\","
                + "\"Plan\":{\"Stages\":[]}}").post("/").then().statusCode(200).extract().path("ContactArn");
        String email = contacts("CreateContactChannel", "{\"ContactId\":\"" + person + "\",\"Name\":\"mail\","
                + "\"Type\":\"EMAIL\",\"DeliveryAddress\":{\"SimpleAddress\":\"ana@example.com\"}}").post("/").then()
                .statusCode(200).extract().path("ContactChannelArn");
        String activation = lastEmailTo("ana@example.com");
        assertTrue(activation != null && activation.contains("activation code"), String.valueOf(activation));
        contacts("ActivateContactChannel", "{\"ContactChannelId\":\"" + email + "\",\"ActivationCode\":\"000000x\"}")
                .post("/").then().statusCode(400);
        contacts("ActivateContactChannel", "{\"ContactChannelId\":\"" + email + "\",\"ActivationCode\":\""
                + code(activation, " is") + "\"}").post("/").then().statusCode(200);
        contacts("GetContactChannel", "{\"ContactChannelId\":\"" + email + "\"}").post("/").then()
                .body("ActivationStatus", equalTo("ACTIVATED"));

        // An SMS channel with deferred activation gets its code on request, through SNS.
        String sms = contacts("CreateContactChannel", "{\"ContactId\":\"" + person + "\",\"Name\":\"phone\","
                + "\"Type\":\"SMS\",\"DeliveryAddress\":{\"SimpleAddress\":\"+12065550142\"},\"DeferActivation\":"
                + "true}").post("/").then().statusCode(200).extract().path("ContactChannelArn");
        contacts("SendActivationCode", "{\"ContactChannelId\":\"" + sms + "\"}").post("/").then().statusCode(200);
        contacts("ActivateContactChannel", "{\"ContactChannelId\":\"" + sms + "\",\"ActivationCode\":\""
                + code(lastSmsTo("+12065550142"), " is") + "\"}").post("/").then().statusCode(200);
        contacts("UpdateContact", "{\"ContactId\":\"" + person + "\",\"Plan\":{\"Stages\":[{\"DurationInMinutes\":5,"
                + "\"Targets\":[{\"ChannelTargetInfo\":{\"ContactChannelId\":\"" + email + "\"}}]},"
                + "{\"DurationInMinutes\":5,\"Targets\":[{\"ChannelTargetInfo\":{\"ContactChannelId\":\"" + sms
                + "\"}}]}]}}").post("/").then().statusCode(200);
        contacts("DeleteContactChannel", "{\"ContactChannelId\":\"" + email + "\"}").post("/").then().statusCode(400)
                .body("__type", containsString("ConflictException"));

        // A daily rotation hands off between two people; an override replaces whoever is on call.
        String bo = contacts("CreateContact", "{\"Alias\":\"bo\",\"Type\":\"PERSONAL\",\"Plan\":{\"Stages\":[]}}")
                .post("/").then().statusCode(200).extract().path("ContactArn");
        long day = 24 * 3600;
        long start = Instant.parse("2031-03-03T09:00:00Z").getEpochSecond();
        String rotation = contacts("CreateRotation", "{\"Name\":\"primary\",\"ContactIds\":[\"" + person + "\",\""
                + bo + "\"],\"TimeZoneId\":\"UTC\",\"StartTime\":" + start + ",\"Recurrence\":{\"NumberOfOnCalls\":1,"
                + "\"RecurrenceMultiplier\":1,\"DailySettings\":[{\"HourOfDay\":9,\"MinuteOfHour\":0}]}}").post("/")
                .then().statusCode(200).extract().path("RotationArn");
        contacts("ListRotationShifts", "{\"RotationId\":\"" + rotation + "\",\"StartTime\":" + start
                + ",\"EndTime\":" + (start + 3 * day) + "}").post("/").then().statusCode(200)
                .body("RotationShifts", hasSize(3)).body("RotationShifts[0].ContactIds", equalTo(List.of(person)))
                .body("RotationShifts[1].ContactIds", equalTo(List.of(bo)))
                .body("RotationShifts[2].ContactIds", equalTo(List.of(person)));
        contacts("CreateRotationOverride", "{\"RotationId\":\"" + rotation + "\",\"NewContactIds\":[\"" + bo
                + "\"],\"StartTime\":" + (start + 3600) + ",\"EndTime\":" + (start + 7200) + "}").post("/").then()
                .statusCode(200);
        contacts("ListRotationShifts", "{\"RotationId\":\"" + rotation + "\",\"StartTime\":" + start
                + ",\"EndTime\":" + (start + day) + "}").post("/").then()
                .body("RotationShifts.Type", equalTo(List.of("REGULAR", "OVERRIDDEN", "REGULAR")))
                .body("RotationShifts[1].ShiftDetails.OverriddenContactIds", equalTo(List.of(person)));
        contacts("DeleteContact", "{\"ContactId\":\"" + bo + "\"}").post("/").then().statusCode(400);
        contacts("TagResource", "{\"ResourceARN\":\"" + rotation + "\",\"Tags\":[{\"Key\":\"alchemy::id\","
                + "\"Value\":\"Primary\"}]}").post("/").then().statusCode(200);
        contacts("ListTagsForResource", "{\"ResourceARN\":\"" + rotation + "\"}").post("/").then()
                .body("Tags.Key", equalTo(List.of("alchemy::id")));

        // An escalation plan engages Ana first, as an essential contact.
        String escalation = contacts("CreateContact", "{\"Alias\":\"sre\",\"Type\":\"ESCALATION\",\"Plan\":"
                + "{\"Stages\":[{\"DurationInMinutes\":5,\"Targets\":[{\"ContactTargetInfo\":{\"ContactId\":\""
                + person + "\",\"IsEssential\":true}}]},{\"DurationInMinutes\":5,\"Targets\":[{\"ContactTargetInfo\":"
                + "{\"ContactId\":\"" + bo + "\",\"IsEssential\":false}}]}]}}").post("/").then().statusCode(200)
                .extract().path("ContactArn");

        // A response plan notifies a topic (subscribed by a queue) and engages the escalation plan.
        String topic = xml(query("sns").formParam("Action", "CreateTopic").formParam("Name", "incidents"), "TopicArn");
        String queueUrl = xml(query("sqs").formParam("Action", "CreateQueue").formParam("QueueName", "incidents"),
                "QueueUrl");
        String queueArn = "arn:aws:sqs:us-east-1:000000000000:incidents";
        query("sns").formParam("Action", "Subscribe").formParam("TopicArn", topic).formParam("Protocol", "sqs")
                .formParam("Endpoint", queueArn).post("/").then().statusCode(200);
        incidents().body("{\"name\":\"db\",\"incidentTemplate\":{\"title\":\"x\",\"impact\":9}}")
                .post("/createResponsePlan").then().statusCode(400);
        String plan = incidents().body("{\"name\":\"db\",\"displayName\":\"Database\",\"incidentTemplate\":{\"title\":"
                + "\"Database down\",\"impact\":2,\"summary\":\"Primary unreachable\",\"notificationTargets\":"
                + "[{\"snsTopicArn\":\"" + topic + "\"}]},\"engagements\":[\"" + escalation + "\"]}")
                .post("/createResponsePlan").then().statusCode(201).extract().path("arn");

        String record = incidents().body("{\"responsePlanArn\":\"" + plan + "\",\"triggerDetails\":{\"source\":"
                + "\"floci.test\",\"timestamp\":" + Instant.now().getEpochSecond() + "}}").post("/startIncident")
                .then().statusCode(200).extract().path("incidentRecordArn");
        incidents().queryParam("arn", record).get("/getIncidentRecord").then().statusCode(200)
                .body("incidentRecord.title", equalTo("Database down")).body("incidentRecord.status", equalTo("OPEN"))
                .body("incidentRecord.incidentRecordSource.source", equalTo("floci.test"));
        String notification = xml(query("sqs").formParam("Action", "ReceiveMessage").formParam("QueueUrl", queueUrl)
                .formParam("WaitTimeSeconds", "1"), "Body");
        assertTrue(notification.contains("Database down"), notification);

        // The escalation plan's first stage pages Ana by email, with an accept code.
        String engagement = contacts("ListEngagements", "{\"IncidentId\":\"" + record + "\"}").post("/").then()
                .statusCode(200).body("Engagements", hasSize(1)).extract().path("Engagements[0].EngagementArn");
        String page = contacts("ListPagesByEngagement", "{\"EngagementId\":\"" + engagement + "\"}").post("/").then()
                .statusCode(200).body("Pages", hasSize(1)).body("Pages[0].ContactArn", equalTo(person)).extract()
                .path("Pages[0].PageArn");
        String paged = lastEmailTo("ana@example.com");
        assertTrue(paged.contains("Database down") && paged.contains("Accept code"), paged);
        contacts("ListPageResolutions", "{\"PageId\":\"" + page + "\"}").post("/").then()
                .body("PageResolutions.Type", equalTo(List.of("ESCALATION", "PERSONAL")))
                .body("PageResolutions[0].StageIndex", equalTo(0));
        contacts("AcceptPage", "{\"PageId\":\"" + page + "\",\"AcceptType\":\"READ\",\"AcceptCode\":\"111111x\","
                + "\"AcceptCodeValidation\":\"ENFORCE\"}").post("/").then().statusCode(400);
        contacts("AcceptPage", "{\"PageId\":\"" + page + "\",\"AcceptType\":\"READ\",\"AcceptCode\":\""
                + code(paged, "Accept code:") + "\",\"AcceptCodeValidation\":"
                + "\"ENFORCE\"}").post("/").then().statusCode(200);
        contacts("ListPageReceipts", "{\"PageId\":\"" + page + "\"}").post("/").then()
                .body("Receipts.ReceiptType", hasItems("SENT", "DELIVERED", "READ"));
        contacts("DescribePage", "{\"PageId\":\"" + page + "\"}").post("/").then().body("ReadTime", notNullValue());

        // Timeline and related items.
        String event = incidents().body("{\"incidentRecordArn\":\"" + record + "\",\"eventType\":\"Custom Event\","
                + "\"eventData\":\"\\\"failover\\\"\",\"eventTime\":" + Instant.now().getEpochSecond() + "}")
                .post("/createTimelineEvent").then().statusCode(201).extract().path("eventId");
        incidents().body("{\"incidentRecordArn\":\"" + record + "\",\"eventType\":\"Bogus\",\"eventData\":\"x\","
                + "\"eventTime\":1}").post("/createTimelineEvent").then().statusCode(400);
        incidents().body("{\"incidentRecordArn\":\"" + record + "\"}").post("/listTimelineEvents").then()
                .statusCode(200).body("eventSummaries.eventType", hasItems("SSM Incident Trigger",
                        "SSM Contacts Page for Incident", "Custom Event"));
        incidents().body("{\"incidentRecordArn\":\"" + record + "\",\"relatedItemsUpdate\":{\"itemToAdd\":"
                + "{\"identifier\":{\"type\":\"OTHER\",\"value\":{\"url\":\"https://runbook.example/db\"}},\"title\":"
                + "\"Runbook\"}}}").post("/updateRelatedItems").then().statusCode(204);
        incidents().body("{\"incidentRecordArn\":\"" + record + "\"}").post("/listRelatedItems").then()
                .body("relatedItems.title", equalTo(List.of("Runbook")));
        incidents().body("{\"incidentRecordArn\":\"" + record + "\",\"eventId\":\"" + event + "\"}")
                .post("/deleteTimelineEvent").then().statusCode(204);

        // Resolving notifies the topic again.
        incidents().body("{\"arn\":\"" + record + "\",\"status\":\"RESOLVED\"}").post("/updateIncidentRecord").then()
                .statusCode(204);
        incidents().queryParam("arn", record).get("/getIncidentRecord").then()
                .body("incidentRecord.resolvedTime", notNullValue());
        String resolved = xml(query("sqs").formParam("Action", "ReceiveMessage").formParam("QueueUrl", queueUrl)
                .formParam("WaitTimeSeconds", "1"), "Body");
        assertTrue(resolved.contains("RESOLVED"), resolved);
        incidents().body("{}").post("/listIncidentRecords").then()
                .body("incidentRecordSummaries.arn", hasItem(record));

        // Offboarding: deletion protection first, then everything goes.
        incidents().body("{\"arn\":\"" + set + "\",\"deletionProtected\":true}").post("/updateDeletionProtection")
                .then().statusCode(204);
        incidents().queryParam("arn", set).post("/deleteReplicationSet").then().statusCode(400);
        incidents().body("{\"arn\":\"" + set + "\",\"deletionProtected\":false}").post("/updateDeletionProtection")
                .then().statusCode(204);
        incidents().queryParam("arn", set).post("/deleteReplicationSet").then().statusCode(204);
        await().atMost(Duration.ofSeconds(10)).until(() -> incidents().queryParam("arn", set).get("/getReplicationSet")
                .then().extract().statusCode() == 404);
        incidents().queryParam("arn", plan).get("/getResponsePlan").then().statusCode(404);
        contacts("ListContacts", "{}").post("/").then().statusCode(400);
        assertEquals(0, incidents().body("{}").post("/listReplicationSets").then().extract()
                .jsonPath().getList("replicationSetArns").size());
    }

    static String xml(RequestSpecification spec, String element) {
        String body = spec.post("/").then().statusCode(200).extract().asString();
        String value = body.replaceAll("(?s).*<" + element + ">(.*?)</" + element + ">.*", "$1");
        return value.replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
    }
}
