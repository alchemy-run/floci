package io.floci.aws.services.pinpointsmsvoicev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SMS and Voice end to end: numbers sending through configuration sets whose events reach SNS, two-way numbers
 * receiving each other's messages, keywords answering them and STOP opting the sender out, pools that check their
 * members, protect configurations, registrations that gate toll-free sending, spend limits and verified numbers.
 */
@QuarkusTest
class SmsVoiceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification sms(String action, String body) {
        return given().header("Authorization", auth("sms-voice")).header("X-Amz-Target", "PinpointSMSVoiceV2."
                + action).contentType("application/x-amz-json-1.0").body(body);
    }

    static RequestSpecification sqs(String action, String body) {
        return given().header("Authorization", auth("sqs")).header("X-Amz-Target", "AmazonSQS." + action)
                .contentType("application/x-amz-json-1.0").body(body);
    }

    /** An SNS topic whose raw messages land on an SQS queue; returns {topicArn, queueUrl}. */
    static String[] topicWithQueue(String name) {
        String topic = given().header("Authorization", auth("sns")).formParam("Action", "CreateTopic")
                .formParam("Name", name).post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateTopicResponse.CreateTopicResult.TopicArn");
        String url = sqs("CreateQueue", "{\"QueueName\":\"" + name + "\"}").post("/").then().statusCode(200)
                .extract().path("QueueUrl");
        String queueArn = sqs("GetQueueAttributes", "{\"QueueUrl\":\"" + url + "\",\"AttributeNames\":[\"QueueArn\"]}")
                .post("/").then().extract().path("Attributes.QueueArn");
        given().header("Authorization", auth("sns")).formParam("Action", "Subscribe").formParam("TopicArn", topic)
                .formParam("Protocol", "sqs").formParam("Endpoint", queueArn)
                .formParam("Attributes.entry.1.key", "RawMessageDelivery")
                .formParam("Attributes.entry.1.value", "true").post("/").then().statusCode(200);
        return new String[] {topic, url};
    }

    /** Waits until the queue holds at least {@code count} messages, then returns their bodies as JSON. */
    static List<JsonNode> drain(String url, int count) {
        List<JsonNode> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until(() -> {
            String body = sqs("ReceiveMessage", "{\"QueueUrl\":\"" + url + "\",\"MaxNumberOfMessages\":10}")
                    .post("/").then().statusCode(200).extract().asString();
            for (JsonNode message : MAPPER.readTree(body).path("Messages")) {
                received.add(MAPPER.readTree(message.path("Body").asText()));
                sqs("DeleteMessage", "{\"QueueUrl\":\"" + url + "\",\"ReceiptHandle\":\""
                        + message.path("ReceiptHandle").asText() + "\"}").post("/");
            }
            return received.size() >= count;
        });
        return received;
    }

    static String number(String body) {
        String id = sms("RequestPhoneNumber", body).post("/").then().statusCode(200).extract().path("PhoneNumberId");
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).until(() -> "ACTIVE".equals(
                sms("DescribePhoneNumbers", "{\"PhoneNumberIds\":[\"" + id + "\"]}").post("/").then().extract()
                        .path("PhoneNumbers[0].Status")));
        return id;
    }

    static String phoneOf(String id) {
        return sms("DescribePhoneNumbers", "{\"PhoneNumberIds\":[\"" + id + "\"]}").post("/").then().extract()
                .path("PhoneNumbers[0].PhoneNumber");
    }

    static final String SIMULATOR = "{\"IsoCountryCode\":\"US\",\"MessageType\":\"TRANSACTIONAL\","
            + "\"NumberCapabilities\":[\"SMS\"],\"NumberType\":\"SIMULATOR\"}";

    @Test
    void sendsReachEventDestinationsAndOwnNumbersAnswerKeywords() {
        String[] events = topicWithQueue("sms-events");
        sms("CreateConfigurationSet", "{\"ConfigurationSetName\":\"e2e\",\"Tags\":[{\"Key\":\"team\",\"Value\":"
                + "\"sms\"}]}").post("/").then().statusCode(200).body("ConfigurationSetArn",
                containsString(":configuration-set/e2e"));
        sms("CreateConfigurationSet", "{\"ConfigurationSetName\":\"e2e\"}").post("/").then().statusCode(400)
                .body("__type", containsString("ConflictException")).body("Reason", equalTo("RESOURCE_ALREADY_EXISTS"));
        sms("CreateEventDestination", "{\"ConfigurationSetName\":\"e2e\",\"EventDestinationName\":\"missing\","
                + "\"MatchingEventTypes\":[\"ALL\"],\"SnsDestination\":{\"TopicArn\":"
                + "\"arn:aws:sns:us-east-1:000000000000:nope\"}}").post("/").then().statusCode(400)
                .body("__type", containsString("ValidationException"));
        sms("CreateEventDestination", "{\"ConfigurationSetName\":\"e2e\",\"EventDestinationName\":\"sns\","
                + "\"MatchingEventTypes\":[\"TEXT_ALL\"],\"SnsDestination\":{\"TopicArn\":\"" + events[0] + "\"}}")
                .post("/").then().statusCode(200).body("EventDestination.Enabled", equalTo(true));
        sms("SetDefaultMessageType", "{\"ConfigurationSetName\":\"e2e\",\"MessageType\":\"PROMOTIONAL\"}").post("/")
                .then().statusCode(200);
        sms("DescribeConfigurationSets", "{\"Filters\":[{\"Name\":\"default-message-type\",\"Values\":"
                + "[\"PROMOTIONAL\"]}]}").post("/").then().statusCode(200)
                .body("ConfigurationSets.ConfigurationSetName", hasItem("e2e"));
        sms("DescribeConfigurationSets", "{\"ConfigurationSetNames\":[\"nope\"]}").post("/").then().statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));

        // Two numbers, each two-way to its own topic.
        String[] inboxA = topicWithQueue("sms-inbox-a");
        String[] inboxB = topicWithQueue("sms-inbox-b");
        String a = number(SIMULATOR);
        String b = number(SIMULATOR);
        sms("UpdatePhoneNumber", "{\"PhoneNumberId\":\"" + a + "\",\"TwoWayEnabled\":true}").post("/").then()
                .statusCode(400).body("Reason", equalTo("TWO_WAY_CHANNEL_NOT_PRESENT"));
        sms("UpdatePhoneNumber", "{\"PhoneNumberId\":\"" + a + "\",\"TwoWayEnabled\":true,\"TwoWayChannelArn\":\""
                + inboxA[0] + "\"}").post("/").then().statusCode(200).body("TwoWayEnabled", equalTo(true));
        sms("UpdatePhoneNumber", "{\"PhoneNumberId\":\"" + b + "\",\"TwoWayEnabled\":true,\"TwoWayChannelArn\":\""
                + inboxB[0] + "\"}").post("/").then().statusCode(200);
        String numberA = phoneOf(a);
        String numberB = phoneOf(b);

        // A message from A to B through the configuration set: events, then B's inbox.
        String messageId = sms("SendTextMessage", "{\"DestinationPhoneNumber\":\"" + numberB + "\","
                + "\"OriginationIdentity\":\"" + a + "\",\"MessageBody\":\"hello there\",\"ConfigurationSetName\":"
                + "\"e2e\",\"Context\":{\"order\":\"42\"}}").post("/").then().statusCode(200).extract().path("MessageId");
        List<JsonNode> sent = drain(events[1], 2);
        assertEquals(Set.of("TEXT_SUCCESSFUL", "TEXT_DELIVERED"), sent.stream().map(e -> e.path("eventType")
                .asText()).collect(Collectors.toSet()));
        assertEquals(messageId, sent.get(0).path("messageId").asText());
        assertEquals("PROMOTIONAL", sent.get(0).path("messageType").asText());
        assertEquals("42", sent.get(0).at("/context/order").asText());
        JsonNode arrived = drain(inboxB[1], 1).get(0);
        assertEquals(numberA, arrived.path("originationNumber").asText());
        assertEquals("hello there", arrived.path("messageBody").asText());
        assertEquals(messageId, arrived.path("previousPublishedMessageId").asText());

        // B asks A for INFO: A's keyword answers into B's inbox.
        sms("PutKeyword", "{\"OriginationIdentity\":\"" + a + "\",\"Keyword\":\"INFO\",\"KeywordMessage\":"
                + "\"Visit alchemy.run\"}").post("/").then().statusCode(200).body("KeywordAction",
                equalTo("AUTOMATIC_RESPONSE"));
        sms("SendTextMessage", "{\"DestinationPhoneNumber\":\"" + numberA + "\",\"OriginationIdentity\":\"" + b
                + "\",\"MessageBody\":\"info please\"}").post("/").then().statusCode(200);
        assertEquals("INFO", drain(inboxA[1], 1).get(0).path("messageKeyword").asText());
        assertEquals("Visit alchemy.run", drain(inboxB[1], 1).get(0).path("messageBody").asText());
        sms("DeleteKeyword", "{\"OriginationIdentity\":\"" + a + "\",\"Keyword\":\"STOP\"}").post("/").then()
                .statusCode(400).body("Reason", equalTo("RESOURCE_DELETION_NOT_ALLOWED"));

        // B replies STOP: B is opted out of A's list, and A can no longer send to B.
        sms("SendTextMessage", "{\"DestinationPhoneNumber\":\"" + numberA + "\",\"OriginationIdentity\":\"" + b
                + "\",\"MessageBody\":\"STOP\"}").post("/").then().statusCode(200);
        drain(inboxA[1], 1);
        await().atMost(Duration.ofSeconds(10)).until(() -> sms("DescribeOptedOutNumbers", "{\"OptOutListName\":"
                + "\"Default\"}").post("/").then().extract().jsonPath().getList("OptedOutNumbers.OptedOutNumber")
                .contains(numberB));
        sms("DescribeOptedOutNumbers", "{\"OptOutListName\":\"Default\",\"Filters\":[{\"Name\":"
                + "\"end-user-opted-out\",\"Values\":[\"true\"]}]}").post("/").then()
                .body("OptedOutNumbers.OptedOutNumber", hasItem(numberB));
        sms("SendTextMessage", "{\"DestinationPhoneNumber\":\"" + numberB + "\",\"OriginationIdentity\":\"" + a
                + "\",\"MessageBody\":\"still there?\"}").post("/").then().statusCode(400)
                .body("Reason", equalTo("DESTINATION_PHONE_NUMBER_OPTED_OUT"));
        sms("DeleteOptedOutNumber", "{\"OptOutListName\":\"Default\",\"OptedOutNumber\":\"" + numberB + "\"}")
                .post("/").then().statusCode(200).body("EndUserOptedOut", equalTo(true));

        // Voice needs the capability; feedback needs it enabled.
        sms("SendVoiceMessage", "{\"DestinationPhoneNumber\":\"" + numberB + "\",\"OriginationIdentity\":\"" + a
                + "\",\"MessageBody\":\"hi\"}").post("/").then().statusCode(400)
                .body("Reason", equalTo("VOICE_CAPABILITY_NOT_AVAILABLE"));
        sms("PutMessageFeedback", "{\"MessageId\":\"" + messageId + "\",\"MessageFeedbackStatus\":\"RECEIVED\"}")
                .post("/").then().statusCode(400).body("__type", containsString("ValidationException"));
        sms("PutMessageFeedback", "{\"MessageId\":\"nope\",\"MessageFeedbackStatus\":\"RECEIVED\"}").post("/")
                .then().statusCode(400).body("__type", containsString("ResourceNotFoundException"));

        // Pools check their members' settings.
        String list = sms("CreateOptOutList", "{\"OptOutListName\":\"vip\"}").post("/").then().statusCode(200)
                .extract().path("OptOutListName");
        String pool = sms("CreatePool", "{\"OriginationIdentity\":\"" + a + "\",\"MessageType\":\"TRANSACTIONAL\"}")
                .post("/").then().statusCode(200).body("TwoWayEnabled", equalTo(true)).extract().path("PoolId");
        String c = number(SIMULATOR.replace("}", ",\"OptOutListName\":\"" + list + "\"}"));
        sms("AssociateOriginationIdentity", "{\"PoolId\":\"" + pool + "\",\"OriginationIdentity\":\"" + c + "\"}")
                .post("/").then().statusCode(400).body("Reason", equalTo("OPT_OUT_LIST_MISMATCH"));
        sms("DisassociateOriginationIdentity", "{\"PoolId\":\"" + pool + "\",\"OriginationIdentity\":\"" + a + "\"}")
                .post("/").then().statusCode(400).body("Reason", equalTo("LAST_PHONE_NUMBER"));
        sms("DeleteOptOutList", "{\"OptOutListName\":\"vip\"}").post("/").then().statusCode(400)
                .body("Reason", equalTo("RESOURCE_DELETION_NOT_ALLOWED"));
        sms("SendTextMessage", "{\"DestinationPhoneNumber\":\"" + numberB + "\",\"OriginationIdentity\":\"" + pool
                + "\",\"MessageBody\":\"from the pool\"}").post("/").then().statusCode(200);
        assertEquals(numberA, drain(inboxB[1], 1).get(0).path("originationNumber").asText());
        sms("ListPoolOriginationIdentities", "{\"PoolId\":\"" + pool + "\"}").post("/").then().statusCode(200)
                .body("OriginationIdentities.PhoneNumber", equalTo(List.of(numberA)));

        // Tags, release.
        String arn = sms("DescribePhoneNumbers", "{\"PhoneNumberIds\":[\"" + c + "\"]}").post("/").then().extract()
                .path("PhoneNumbers[0].PhoneNumberArn");
        sms("TagResource", "{\"ResourceArn\":\"" + arn + "\",\"Tags\":[{\"Key\":\"k\",\"Value\":\"v\"}]}").post("/")
                .then().statusCode(200);
        sms("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}").post("/").then().body("Tags.Key", hasItem("k"));
        sms("UpdatePhoneNumber", "{\"PhoneNumberId\":\"" + c + "\",\"DeletionProtectionEnabled\":true}").post("/")
                .then().statusCode(200);
        sms("ReleasePhoneNumber", "{\"PhoneNumberId\":\"" + c + "\"}").post("/").then().statusCode(400)
                .body("Reason", equalTo("DELETION_PROTECTION_ENABLED"));
        sms("UpdatePhoneNumber", "{\"PhoneNumberId\":\"" + c + "\",\"DeletionProtectionEnabled\":false}").post("/")
                .then().statusCode(200);
        sms("ReleasePhoneNumber", "{\"PhoneNumberId\":\"" + c + "\"}").post("/").then().statusCode(200)
                .body("Status", equalTo("DELETED"));
        sms("DescribePhoneNumbers", "{\"PhoneNumberIds\":[\"" + c + "\"]}").post("/").then().statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void registrationsProtectConfigurationsAndSpendLimitsGateSending() {
        // A toll-free number can't send until its registration is approved.
        String tollFree = number("{\"IsoCountryCode\":\"US\",\"MessageType\":\"TRANSACTIONAL\","
                + "\"NumberCapabilities\":[\"SMS\",\"VOICE\"],\"NumberType\":\"TOLL_FREE\"}");
        String send = "{\"DestinationPhoneNumber\":\"+12065550142\",\"OriginationIdentity\":\"" + tollFree
                + "\",\"MessageBody\":\"hello\"}";
        sms("SendTextMessage", send).post("/").then().statusCode(400).body("Reason",
                equalTo("REGISTRATION_NOT_COMPLETE"));
        String registration = sms("CreateRegistration", "{\"RegistrationType\":\"US_TOLL_FREE_REGISTRATION\"}")
                .post("/").then().statusCode(200).body("RegistrationStatus", equalTo("CREATED")).extract()
                .path("RegistrationId");
        sms("DescribeRegistrationFieldDefinitions", "{\"RegistrationType\":\"US_TOLL_FREE_REGISTRATION\","
                + "\"SectionPath\":\"messagingUseCase\"}").post("/").then().statusCode(200)
                .body("RegistrationFieldDefinitions.FieldPath", hasItem("messagingUseCase.monthlyMessageVolume"));
        sms("SubmitRegistrationVersion", "{\"RegistrationId\":\"" + registration + "\"}").post("/").then()
                .statusCode(400).body("message", containsString("companyInfo.companyName"));
        String[][] text = {{"companyInfo.companyName", "Alchemy"}, {"companyInfo.website", "https://alchemy.run"},
            {"companyInfo.address1", "1 Main St"}, {"companyInfo.city", "Seattle"}, {"companyInfo.isoCountryCode", "US"},
            {"contactInfo.firstName", "Sam"}, {"contactInfo.lastName", "G"},
            {"contactInfo.supportEmail", "help@alchemy.run"}, {"contactInfo.supportPhoneNumber", "+12065550100"},
            {"messagingUseCase.useCaseDescription", "Sign-in codes"}, {"messageSamples.messageSample1", "Code 1234"},
            {"optInDetails.optInDescription", "Users opt in at sign-up"}};
        for (String[] field : text) {
            sms("PutRegistrationFieldValue", "{\"RegistrationId\":\"" + registration + "\",\"FieldPath\":\""
                    + field[0] + "\",\"TextValue\":\"" + field[1] + "\"}").post("/").then().statusCode(200);
        }
        sms("PutRegistrationFieldValue", "{\"RegistrationId\":\"" + registration + "\",\"FieldPath\":"
                + "\"contactInfo.supportEmail\",\"TextValue\":\"not-an-email\"}").post("/").then().statusCode(400)
                .body("Reason", equalTo("FIELD_VALIDATION_FAILED"));
        sms("PutRegistrationFieldValue", "{\"RegistrationId\":\"" + registration + "\",\"FieldPath\":"
                + "\"messagingUseCase.monthlyMessageVolume\",\"SelectChoices\":[\"1,000\"]}").post("/").then()
                .statusCode(200);
        sms("PutRegistrationFieldValue", "{\"RegistrationId\":\"" + registration + "\",\"FieldPath\":"
                + "\"messagingUseCase.useCaseCategory\",\"SelectChoices\":[\"TWO_FACTOR_AUTHENTICATION\"]}").post("/")
                .then().statusCode(200);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0};
        String attachment = sms("CreateRegistrationAttachment", "{\"AttachmentBody\":\""
                + Base64.getEncoder().encodeToString(png) + "\"}").post("/").then().statusCode(200)
                .body("AttachmentStatus", equalTo("UPLOAD_COMPLETE")).extract().path("RegistrationAttachmentId");
        sms("PutRegistrationFieldValue", "{\"RegistrationId\":\"" + registration + "\",\"FieldPath\":"
                + "\"optInDetails.optInImage\",\"RegistrationAttachmentId\":\"" + attachment + "\"}").post("/").then()
                .statusCode(200);
        sms("SubmitRegistrationVersion", "{\"RegistrationId\":\"" + registration + "\"}").post("/").then()
                .statusCode(400).body("Reason", equalTo("INVALID_REGISTRATION_ASSOCIATION"));
        sms("CreateRegistrationAssociation", "{\"RegistrationId\":\"" + registration + "\",\"ResourceId\":\""
                + tollFree + "\"}").post("/").then().statusCode(200).body("ResourceType", equalTo("TOLL_FREE"));
        sms("SubmitRegistrationVersion", "{\"RegistrationId\":\"" + registration + "\"}").post("/").then()
                .statusCode(200).body("RegistrationVersionStatus", equalTo("SUBMITTED"));
        sms("PutRegistrationFieldValue", "{\"RegistrationId\":\"" + registration + "\",\"FieldPath\":"
                + "\"companyInfo.city\",\"TextValue\":\"Portland\"}").post("/").then().statusCode(400)
                .body("Reason", equalTo("EDIT_REGISTRATION_FIELD_VALUES_NOT_ALLOWED"));
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).until(() -> "COMPLETE".equals(
                sms("DescribeRegistrations", "{\"RegistrationIds\":[\"" + registration + "\"]}").post("/").then()
                        .extract().path("Registrations[0].RegistrationStatus")));
        sms("DescribeRegistrationVersions", "{\"RegistrationId\":\"" + registration + "\"}").post("/").then()
                .body("RegistrationVersions[0].RegistrationVersionStatus", equalTo("APPROVED"));
        sms("SendTextMessage", send).post("/").then().statusCode(200);

        // A protect configuration blocks a country; a number override lets one number through.
        String protect = sms("CreateProtectConfiguration", "{}").post("/").then().statusCode(200).extract()
                .path("ProtectConfigurationId");
        sms("UpdateProtectConfigurationCountryRuleSet", "{\"ProtectConfigurationId\":\"" + protect + "\","
                + "\"NumberCapability\":\"SMS\",\"CountryRuleSetUpdates\":{\"US\":{\"ProtectStatus\":\"BLOCK\"}}}")
                .post("/").then().statusCode(200).body("CountryRuleSet.US.ProtectStatus", equalTo("BLOCK"));
        sms("SetAccountDefaultProtectConfiguration", "{\"ProtectConfigurationId\":\"" + protect + "\"}").post("/")
                .then().statusCode(200);
        sms("SendTextMessage", send).post("/").then().statusCode(400)
                .body("Reason", equalTo("DESTINATION_COUNTRY_BLOCKED_BY_PROTECT_CONFIGURATION"));
        sms("PutProtectConfigurationRuleSetNumberOverride", "{\"ProtectConfigurationId\":\"" + protect + "\","
                + "\"DestinationPhoneNumber\":\"+12065550142\",\"Action\":\"ALLOW\"}").post("/").then().statusCode(200)
                .body("IsoCountryCode", equalTo("US"));
        sms("SendTextMessage", send).post("/").then().statusCode(200);
        sms("DeleteProtectConfiguration", "{\"ProtectConfigurationId\":\"" + protect + "\"}").post("/").then()
                .statusCode(400).body("Reason", equalTo("PROTECT_CONFIGURATION_IS_ACCOUNT_DEFAULT"));
        sms("DeleteAccountDefaultProtectConfiguration", "{}").post("/").then().statusCode(200);
        sms("DescribeAccountAttributes", "{}").post("/").then().statusCode(200)
                .body("AccountAttributes.Name", not(hasItem("DEFAULT_PROTECT_CONFIGURATION_ID")));

        // A spend limit of zero stops paid sends.
        sms("SetTextMessageSpendLimitOverride", "{\"MonthlyLimit\":99999999}").post("/").then().statusCode(400)
                .body("Reason", equalTo("REQUESTED_SPEND_LIMIT_HIGHER_THAN_SERVICE_LIMIT"));
        sms("SetTextMessageSpendLimitOverride", "{\"MonthlyLimit\":0}").post("/").then().statusCode(200);
        sms("SendTextMessage", send).post("/").then().statusCode(400)
                .body("Reason", equalTo("MONTHLY_SPEND_LIMIT_REACHED_FOR_TEXT"));
        sms("DescribeSpendLimits", "{}").post("/").then().statusCode(200)
                .body("SpendLimits.find { it.Name == 'TEXT_MESSAGE_MONTHLY_SPEND_LIMIT' }.EnforcedLimit", equalTo(0));
        sms("DeleteTextMessageSpendLimitOverride", "{}").post("/").then().statusCode(200).body("MonthlyLimit",
                equalTo(0));
        sms("SendTextMessage", send.replace("\"MessageBody\"", "\"MaxPrice\":\"0.001\",\"MessageBody\""))
                .post("/").then().statusCode(400).body("Reason", equalTo("PRICE_OVER_THRESHOLD"));

        // A verified destination number's code arrives at an own two-way number.
        String[] inbox = topicWithQueue("sms-verify");
        String own = number(SIMULATOR);
        sms("UpdatePhoneNumber", "{\"PhoneNumberId\":\"" + own + "\",\"TwoWayEnabled\":true,\"TwoWayChannelArn\":\""
                + inbox[0] + "\"}").post("/").then().statusCode(200);
        String verified = sms("CreateVerifiedDestinationNumber", "{\"DestinationPhoneNumber\":\"" + phoneOf(own)
                + "\"}").post("/").then().statusCode(200).body("Status", equalTo("PENDING")).extract()
                .path("VerifiedDestinationNumberId");
        sms("VerifyDestinationNumber", "{\"VerifiedDestinationNumberId\":\"" + verified + "\","
                + "\"VerificationCode\":\"000000\"}").post("/").then().statusCode(400)
                .body("Reason", equalTo("VERIFICATION_CODE_MISMATCH"));
        sms("SendDestinationNumberVerificationCode", "{\"VerifiedDestinationNumberId\":\"" + verified + "\","
                + "\"VerificationChannel\":\"TEXT\",\"OriginationIdentity\":\"" + own + "\"}").post("/").then()
                .statusCode(200);
        String body = drain(inbox[1], 1).get(0).path("messageBody").asText();
        String code = body.replaceAll("\\D", "");
        assertTrue(code.length() == 6, body);
        sms("VerifyDestinationNumber", "{\"VerifiedDestinationNumberId\":\"" + verified + "\",\"VerificationCode\":\""
                + code + "\"}").post("/").then().statusCode(200).body("Status", equalTo("VERIFIED"));

        // Notify resolves its template.
        String notify = sms("CreateNotifyConfiguration", "{\"DisplayName\":\"Alchemy\",\"UseCase\":"
                + "\"CODE_VERIFICATION\",\"EnabledChannels\":[\"SMS\"]}").post("/").then().statusCode(200)
                .body("Status", equalTo("PENDING")).extract().path("NotifyConfigurationId");
        await().atMost(Duration.ofSeconds(20)).until(() -> "ACTIVE".equals(sms("DescribeNotifyConfigurations",
                "{\"NotifyConfigurationIds\":[\"" + notify + "\"]}").post("/").then().extract()
                .path("NotifyConfigurations[0].Status")));
        sms("SendNotifyTextMessage", "{\"NotifyConfigurationId\":\"" + notify + "\",\"DestinationPhoneNumber\":"
                + "\"+12065550142\",\"TemplateVariables\":{\"code\":\"482913\"}}").post("/").then().statusCode(200)
                .body("ResolvedMessageBody", equalTo("Your Alchemy verification code is 482913. It expires in 10 "
                        + "minutes."));
        sms("SendNotifyVoiceMessage", "{\"NotifyConfigurationId\":\"" + notify + "\",\"DestinationPhoneNumber\":"
                + "\"+12065550142\",\"TemplateVariables\":{\"code\":\"482913\"}}").post("/").then().statusCode(400)
                .body("Reason", equalTo("CHANNEL_NOT_ENABLED"));
        sms("CarrierLookup", "{\"PhoneNumber\":\"+18335550100\"}").post("/").then().statusCode(200)
                .body("PhoneNumberType", equalTo("OTHER")).body("IsoCountryCode", equalTo("US"));
        sms("CarrierLookup", "{\"PhoneNumber\":\"+1234\"}").post("/").then().statusCode(200)
                .body("PhoneNumberType", equalTo("INVALID"));
    }
}
