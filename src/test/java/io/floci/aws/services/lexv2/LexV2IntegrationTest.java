package io.floci.aws.services.lexv2;

import io.floci.aws.core.common.AwsEventStreamDecoder;
import io.floci.aws.core.common.AwsEventStreamEncoder;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lex V2: bots, locales and builds, intents, slots and slot types, versions and aliases, conversations through the
 * runtime (classification, slot elicitation, confirmation, closing responses, sessions, RecognizeUtterance), export
 * and import, the Test Workbench, analytics, recommendations, the bot analyzer, policies and replicas.
 */
@QuarkusTest
class LexV2IntegrationTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/lex";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    private static ValidatableResponse lex(String method, String path, String body) {
        RequestSpecification spec = given().header("Authorization", auth("lex")).contentType("application/json");
        if (body != null) {
            spec = spec.body(body);
        }
        return spec.request(method, path).then().log().ifValidationFails();
    }

    private static String createBot(String name) {
        String botId = lex("PUT", "/bots/", "{\"botName\":\"" + name + "\",\"roleArn\":\"" + ROLE
                + "\",\"dataPrivacy\":{\"childDirected\":false},\"idleSessionTTLInSeconds\":300,"
                + "\"botTags\":{\"team\":\"chat\"}}").statusCode(202).body("botStatus", equalTo("Creating"))
                .extract().path("botId");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", "/bots/" + botId + "/", null)
                .statusCode(200).body("botStatus", equalTo("Available")));
        return botId;
    }

    private static String locale(String botId) {
        return "/bots/" + botId + "/botversions/DRAFT/botlocales/en_US/";
    }

    /** A pizza bot: Greet, and OrderPizza with a required Size slot, a confirmation and a closing response. */
    private static String pizzaBot(String name) {
        String botId = createBot(name);
        lex("PUT", "/bots/" + botId + "/botversions/DRAFT/botlocales/", "{\"localeId\":\"en_US\","
                + "\"nluIntentConfidenceThreshold\":0.4}").statusCode(202);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", locale(botId), null)
                .statusCode(200).body("botLocaleStatus", equalTo("NotBuilt")));
        String sizeType = lex("PUT", locale(botId) + "slottypes/", "{\"slotTypeName\":\"Size\",\"slotTypeValues\":["
                + "{\"sampleValue\":{\"value\":\"small\"},\"synonyms\":[{\"value\":\"tiny\"}]},"
                + "{\"sampleValue\":{\"value\":\"large\"},\"synonyms\":[{\"value\":\"big\"}]}],"
                + "\"valueSelectionSetting\":{\"resolutionStrategy\":\"TopResolution\"}}").statusCode(200)
                .extract().path("slotTypeId");
        lex("PUT", locale(botId) + "intents/", "{\"intentName\":\"Greet\",\"sampleUtterances\":[{\"utterance\":"
                + "\"hello\"},{\"utterance\":\"hi\"},{\"utterance\":\"hey there\"}],\"intentClosingSetting\":{"
                + "\"closingResponse\":{\"messageGroups\":[{\"message\":{\"plainTextMessage\":{\"value\":"
                + "\"Hi [name]!\"}}}]}}}").statusCode(200);
        String orderId = lex("PUT", locale(botId) + "intents/", "{\"intentName\":\"OrderPizza\",\"sampleUtterances\":["
                + "{\"utterance\":\"order a pizza\"},{\"utterance\":\"I want a {Size} pizza\"}],"
                + "\"intentConfirmationSetting\":{\"promptSpecification\":{\"messageGroups\":[{\"message\":{"
                + "\"plainTextMessage\":{\"value\":\"A {Size} pizza, right?\"}}}],\"maxRetries\":2},"
                + "\"declinationResponse\":{\"messageGroups\":[{\"message\":{\"plainTextMessage\":{\"value\":"
                + "\"Cancelled.\"}}}]}},\"intentClosingSetting\":{\"closingResponse\":{\"messageGroups\":[{"
                + "\"message\":{\"plainTextMessage\":{\"value\":\"Your {Size} pizza is coming.\"}}}]}},"
                + "\"outputContexts\":[{\"name\":\"ordered\",\"timeToLiveInSeconds\":300,\"turnsToLive\":3}]}")
                .statusCode(200).extract().path("intentId");
        lex("PUT", locale(botId) + "intents/" + orderId + "/slots/", "{\"slotName\":\"Size\",\"slotTypeId\":\""
                + sizeType + "\",\"valueElicitationSetting\":{\"slotConstraint\":\"Required\","
                + "\"promptSpecification\":{\"messageGroups\":[{\"message\":{\"plainTextMessage\":{\"value\":"
                + "\"What size?\"}}}],\"maxRetries\":1}}}").statusCode(200);
        lex("PUT", locale(botId) + "intents/", "{\"intentName\":\"TrackOrder\",\"sampleUtterances\":["
                + "{\"utterance\":\"where is my order\"}],\"inputContexts\":[{\"name\":\"ordered\"}]}")
                .statusCode(200);
        build(botId);
        return botId;
    }

    private static void build(String botId) {
        lex("POST", locale(botId), null).statusCode(202).body("botLocaleStatus", equalTo("Building"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", locale(botId), null)
                .statusCode(200).body("botLocaleStatus", equalTo("Built")));
    }

    private static ValidatableResponse say(String botId, String alias, String session, String text) {
        return lex("POST", "/bots/" + botId + "/botAliases/" + alias + "/botLocales/en_US/sessions/" + session
                + "/text", "{\"text\":\"" + text + "\"}").statusCode(200);
    }

    @Test
    void buildsAndConversesWithTheDraft() {
        String botId = pizzaBot("ConverseBot");
        lex("GET", locale(botId), null).body("intentsCount", equalTo(4)).body("slotTypesCount", equalTo(1));
        lex("PUT", "/bots/", "{\"botName\":\"ConverseBot\",\"roleArn\":\"" + ROLE + "\",\"dataPrivacy\":{"
                + "\"childDirected\":false},\"idleSessionTTLInSeconds\":300}").statusCode(409)
                .header("x-amzn-ErrorType", "ConflictException");

        say(botId, "TSTALIASID", "sa", "hello").body("sessionState.intent.name", equalTo("Greet"))
                .body("sessionState.intent.state", equalTo("ReadyForFulfillment"))
                .body("sessionState.dialogAction.type", equalTo("Close"))
                .body("messages[0].content", equalTo("Hi [name]!"))
                .body("interpretations[0].nluConfidence.score", equalTo(1.0f))
                .body("interpretations.intent.name", hasItem("FallbackIntent"));
        say(botId, "TSTALIASID", "sb", "purple monkey dishwasher")
                .body("sessionState.intent.name", equalTo("FallbackIntent"))
                .body("interpretations[0].intent.name", equalTo("FallbackIntent"))
                .body("interpretations[0].nluConfidence", nullValue());

        say(botId, "TSTALIASID", "sc", "order a pizza")
                .body("sessionState.dialogAction.type", equalTo("ElicitSlot"))
                .body("sessionState.dialogAction.slotToElicit", equalTo("Size"))
                .body("sessionState.intent.state", equalTo("InProgress"))
                .body("messages[0].content", equalTo("What size?"));
        say(botId, "TSTALIASID", "sc", "enormous").body("sessionState.dialogAction.slotToElicit", equalTo("Size"));
        say(botId, "TSTALIASID", "sc", "tiny")
                .body("sessionState.dialogAction.type", equalTo("ConfirmIntent"))
                .body("sessionState.intent.slots.Size.value.interpretedValue", equalTo("small"))
                .body("sessionState.intent.slots.Size.value.originalValue", equalTo("tiny"))
                .body("messages[0].content", equalTo("A small pizza, right?"));
        say(botId, "TSTALIASID", "sc", "yes")
                .body("sessionState.intent.confirmationState", equalTo("Confirmed"))
                .body("sessionState.intent.state", equalTo("ReadyForFulfillment"))
                .body("messages[0].content", equalTo("Your small pizza is coming."))
                .body("sessionState.activeContexts[0].name", equalTo("ordered"));
        say(botId, "TSTALIASID", "sc", "where is my order").body("sessionState.intent.name", equalTo("TrackOrder"));
        say(botId, "TSTALIASID", "sd", "where is my order")
                .body("sessionState.intent.name", equalTo("FallbackIntent"));

        say(botId, "TSTALIASID", "se", "I want a big pizza")
                .body("sessionState.dialogAction.type", equalTo("ConfirmIntent"))
                .body("sessionState.intent.slots.Size.value.interpretedValue", equalTo("large"));
        say(botId, "TSTALIASID", "se", "no").body("sessionState.intent.state", equalTo("Failed"))
                .body("sessionState.intent.confirmationState", equalTo("Denied"))
                .body("messages[0].content", equalTo("Cancelled."));
    }

    @Test
    void failsBuildsThatCantBeBuilt() {
        String botId = createBot("BrokenBot");
        lex("PUT", "/bots/" + botId + "/botversions/DRAFT/botlocales/", "{\"localeId\":\"en_US\","
                + "\"nluIntentConfidenceThreshold\":0.4}").statusCode(202);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", locale(botId), null)
                .body("botLocaleStatus", equalTo("NotBuilt")));
        String intentId = lex("PUT", locale(botId) + "intents/", "{\"intentName\":\"Book\",\"sampleUtterances\":["
                + "{\"utterance\":\"book a {Room}\"}]}").statusCode(200).extract().path("intentId");
        lex("POST", locale(botId), null).statusCode(202);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", locale(botId), null)
                .body("botLocaleStatus", equalTo("Failed"))
                .body("failureReasons[0]", containsString("references slot Room")));
        lex("PUT", locale(botId) + "intents/" + intentId + "/slots/", "{\"slotName\":\"Room\",\"slotTypeId\":"
                + "\"AMAZON.AlphaNumeric\",\"valueElicitationSetting\":{\"slotConstraint\":\"Optional\"}}")
                .statusCode(200);
        build(botId);
        lex("PUT", locale(botId) + "intents/" + intentId + "/slots/", "{\"slotName\":\"Room\",\"slotTypeId\":"
                + "\"NOSUCHTYPE\",\"valueElicitationSetting\":{\"slotConstraint\":\"Optional\"}}").statusCode(409);
        lex("DELETE", locale(botId) + "intents/FALLBCKINT/", null).statusCode(400);
        lex("GET", "/bots/BOGUSBOT01/", null).statusCode(404).header("x-amzn-ErrorType",
                "ResourceNotFoundException");
        lex("DELETE", "/bots/BOGUSBOT01/", null).statusCode(412).header("x-amzn-ErrorType",
                "PreconditionFailedException");
    }

    @Test
    void versionsAliasesAndSessions() throws IOException {
        String botId = pizzaBot("SessionBot");
        lex("PUT", "/bots/" + botId + "/botversions/", "{\"botVersionLocaleSpecification\":{\"en_US\":{"
                + "\"sourceBotVersion\":\"DRAFT\"}}}").statusCode(202).body("botVersion", equalTo("1"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", "/bots/" + botId + "/botversions/1/",
                null).statusCode(200).body("botStatus", equalTo("Available")));
        lex("POST", "/bots/" + botId + "/botversions/", "{}").body("botVersionSummaries.botVersion",
                contains("DRAFT", "1"));
        lex("GET", "/bots/" + botId + "/botversions/1/botlocales/en_US/intents/FALLBCKINT/", null).statusCode(200)
                .body("botVersion", equalTo("1"));
        String aliasId = lex("PUT", "/bots/" + botId + "/botaliases/", "{\"botAliasName\":\"Live\",\"botVersion\":"
                + "\"1\",\"sentimentAnalysisSettings\":{\"detectSentiment\":true},\"tags\":{\"stage\":\"prod\"}}")
                .statusCode(202).body("botAliasStatus", equalTo("Creating")).extract().path("botAliasId");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", "/bots/" + botId + "/botaliases/"
                + aliasId + "/", null).statusCode(200).body("botAliasStatus", equalTo("Available"))
                .body("botAliasHistoryEvents[0].botVersion", equalTo("1")));
        lex("POST", "/bots/" + botId + "/botaliases/", "{}").body("botAliasSummaries.botAliasName",
                containsInAnyOrder("TestBotAlias", "Live"));
        lex("DELETE", "/bots/" + botId + "/botversions/1/", null).statusCode(412);

        say(botId, aliasId, "s1", "hello great thanks").body("sessionState.intent.name", equalTo("Greet"))
                .body("interpretations[0].sentimentResponse.sentiment", equalTo("POSITIVE"));
        String sessions = "/bots/" + botId + "/botAliases/" + aliasId + "/botLocales/en_US/sessions/";
        given().header("Authorization", auth("lex")).header("ResponseContentType", "text/plain; charset=utf-8")
                .contentType("application/json").body("{\"sessionState\":{\"dialogAction\":{\"type\":"
                        + "\"ElicitIntent\"},\"sessionAttributes\":{\"name\":\"Ada\"}},\"messages\":[{\"contentType\":"
                        + "\"PlainText\",\"content\":\"How can I help?\"}]}")
                .post(sessions + "s2").then().statusCode(200).header("x-amz-lex-session-id", "s2");
        lex("GET", sessions + "s2", null).statusCode(200).body("sessionState.sessionAttributes.name", equalTo("Ada"))
                .body("messages[0].content", equalTo("How can I help?"));
        say(botId, aliasId, "s2", "hi").body("messages[0].content", equalTo("Hi Ada!"));
        lex("DELETE", sessions + "s2", null).statusCode(200).body("sessionId", equalTo("s2"));
        lex("GET", sessions + "s2", null).statusCode(404).header("x-amzn-ErrorType", "ResourceNotFoundException");

        ExtractableResponse<Response> utterance = given().header("Authorization", auth("lex")).contentType("text/plain; charset=utf-8")
                .header("Response-Content-Type", "text/plain; charset=utf-8").body("order a pizza".getBytes(
                        StandardCharsets.UTF_8)).post(sessions + "s3/utterance").then().statusCode(200)
                .header("x-amz-lex-input-mode", "Text").header("x-amz-lex-session-id", "s3").extract();
        assertTrue(unheader(utterance.header("x-amz-lex-session-state")).contains("\"slotToElicit\":\"Size\""));
        assertEquals("order a pizza", unheader(utterance.header("x-amz-lex-input-transcript")));
        assertTrue(unheader(utterance.header("x-amz-lex-messages")).contains("What size?"));
        given().header("Authorization", auth("lex")).contentType("text/plain; charset=utf-8")
                .header("Response-Content-Type", "audio/mpeg").body("hi".getBytes(StandardCharsets.UTF_8))
                .post(sessions + "s4/utterance").then().statusCode(400)
                .body("message", containsString("no voice settings"));

        lex("PUT", "/bots/" + botId + "/botaliases/" + aliasId + "/", "{\"botAliasName\":\"Live\","
                + "\"botAliasLocaleSettings\":{\"en_US\":{\"enabled\":false}},\"botVersion\":\"1\"}")
                .statusCode(202);
        lex("POST", sessions + "s5/text", "{\"text\":\"hi\"}").statusCode(400)
                .body("message", containsString("not enabled"));
    }

    private static String unheader(String value) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value)))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void exportsAndImports() throws IOException {
        String botId = pizzaBot("ExportBot");
        String exportId = lex("PUT", "/exports/", "{\"resourceSpecification\":{\"botExportSpecification\":{"
                + "\"botId\":\"" + botId + "\",\"botVersion\":\"DRAFT\"}},\"fileFormat\":\"LexJson\"}")
                .statusCode(202).body("exportStatus", equalTo("InProgress")).extract().path("exportId");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", "/exports/" + exportId + "/", null)
                .body("exportStatus", equalTo("Completed")).body("downloadUrl", notNullValue()));
        String url = lex("GET", "/exports/" + exportId + "/", null).extract().path("downloadUrl");
        byte[] archive = given().get(URI.create(url).getPath()).then().statusCode(200).extract().asByteArray();
        Map<String, String> files = unzip(archive);
        assertTrue(files.containsKey("ExportBot/BotLocales/en_US/Intents/OrderPizza/Slots/Size/Slot.json"),
                files.keySet().toString());
        assertTrue(files.get("ExportBot/BotLocales/en_US/Intents/OrderPizza/Slots/Size/Slot.json")
                .contains("\"slotTypeName\" : \"Size\""));

        String importId = lex("POST", "/createuploadurl/", null).statusCode(200).extract().path("importId");
        String upload = lex("POST", "/createuploadurl/", null).extract().path("uploadUrl");
        String secondId = upload.substring(upload.lastIndexOf('/') + 1);
        given().body(archive).put(URI.create(upload).getPath()).then().statusCode(200);
        lex("PUT", "/imports/", "{\"importId\":\"" + importId + "\",\"resourceSpecification\":{"
                + "\"botImportSpecification\":{\"botName\":\"Nothing\",\"roleArn\":\"" + ROLE + "\","
                + "\"dataPrivacy\":{\"childDirected\":false}}},\"mergeStrategy\":\"FailOnConflict\"}").statusCode(404);
        lex("PUT", "/imports/", "{\"importId\":\"" + secondId + "\",\"resourceSpecification\":{"
                + "\"botImportSpecification\":{\"botName\":\"ExportBot\",\"roleArn\":\"" + ROLE + "\","
                + "\"dataPrivacy\":{\"childDirected\":false}}},\"mergeStrategy\":\"FailOnConflict\"}").statusCode(202);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", "/imports/" + secondId + "/", null)
                .body("importStatus", equalTo("Failed")).body("failureReasons[0]", containsString("already")));

        String third = lex("POST", "/createuploadurl/", null).extract().path("uploadUrl");
        String thirdId = third.substring(third.lastIndexOf('/') + 1);
        given().body(archive).put(URI.create(third).getPath()).then().statusCode(200);
        lex("PUT", "/imports/", "{\"importId\":\"" + thirdId + "\",\"resourceSpecification\":{"
                + "\"botImportSpecification\":{\"botName\":\"ImportedBot\",\"roleArn\":\"" + ROLE + "\","
                + "\"dataPrivacy\":{\"childDirected\":false}}},\"mergeStrategy\":\"FailOnConflict\"}").statusCode(202);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", "/imports/" + thirdId + "/", null)
                .body("importStatus", equalTo("Completed")));
        String copy = lex("GET", "/imports/" + thirdId + "/", null).extract().path("importedResourceId");
        lex("POST", locale(copy) + "intents/", "{}").body("intentSummaries.intentName",
                containsInAnyOrder("FallbackIntent", "Greet", "OrderPizza", "TrackOrder"));
        build(copy);
        say(copy, "TSTALIASID", "sx", "I want a tiny pizza")
                .body("sessionState.intent.slots.Size.value.interpretedValue", equalTo("small"));
        lex("POST", "/imports/", "{}").body("importSummaries.importedResourceType", hasItem("Bot"));
    }

    private static Map<String, String> unzip(byte[] archive) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private static void s3Put(String bucket, String key, String content) {
        given().header("Authorization", auth("s3")).put("/" + bucket).then().statusCode(anyOf(equalTo(200), equalTo(409)));
        given().header("Authorization", auth("s3")).contentType("application/octet-stream")
                .body(content.getBytes(StandardCharsets.UTF_8)).put("/" + bucket + "/" + key).then().statusCode(200);
    }

    @Test
    void runsTestSets() {
        String botId = pizzaBot("WorkbenchBot");
        s3Put("lex-tests", "input/pizza.csv", "Line #,Conversation #,Source,Input,Expected Output Intent,"
                + "Expected Output Slot 1\n"
                + "1,,User,hello,Greet,\n"
                + "2,,User,I want a big pizza,OrderPizza,Size = large\n"
                + "3,1,User,order a pizza,OrderPizza,\n"
                + "4,1,Agent,What size?,,\n"
                + "5,1,User,tiny,OrderPizza,Size = small\n"
                + "6,,User,book a flight,BookFlight,\n");
        String importId = lex("POST", "/createuploadurl/", null).extract().path("importId");
        lex("PUT", "/imports/", "{\"importId\":\"" + importId + "\",\"resourceSpecification\":{"
                + "\"testSetImportResourceSpecification\":{\"testSetName\":\"Pizza\",\"roleArn\":\"" + ROLE + "\","
                + "\"storageLocation\":{\"s3BucketName\":\"lex-tests\",\"s3Path\":\"sets/\"},\"importInputLocation\":{"
                + "\"s3BucketName\":\"lex-tests\",\"s3Path\":\"input/pizza.csv\"},\"modality\":\"Text\"}},"
                + "\"mergeStrategy\":\"FailOnConflict\"}").statusCode(202);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", "/imports/" + importId + "/", null)
                .body("importStatus", equalTo("Completed")));
        String testSetId = lex("GET", "/imports/" + importId + "/", null).extract().path("importedResourceId");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", "/testsets/" + testSetId, null)
                .body("status", equalTo("Ready")).body("numTurns", equalTo(6)));
        lex("POST", "/testsets/" + testSetId + "/records", "{}").body("testSetRecords", hasSize(6))
                .body("testSetRecords[3].turnSpecification.agentTurn.agentPrompt", equalTo("What size?"));

        String executionId = lex("POST", "/testsets/" + testSetId + "/testexecutions", "{\"target\":{"
                + "\"botAliasTarget\":{\"botId\":\"" + botId + "\",\"botAliasId\":\"TSTALIASID\",\"localeId\":"
                + "\"en_US\"}},\"apiMode\":\"NonStreaming\"}").statusCode(202).extract().path("testExecutionId");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", "/testexecutions/" + executionId,
                null).body("testExecutionStatus", equalTo("Completed")));
        lex("POST", "/testexecutions/" + executionId + "/results", "{\"resultFilterBy\":{\"resultTypeFilter\":"
                + "\"OverallTestResults\"}}").statusCode(200)
                .body("testExecutionResults.overallTestResults.items.find { !it.multiTurnConversation }"
                        + ".endToEndResultCounts.Matched", equalTo(2))
                .body("testExecutionResults.overallTestResults.items.find { !it.multiTurnConversation }"
                        + ".endToEndResultCounts.Mismatched", equalTo(1))
                .body("testExecutionResults.overallTestResults.items.find { it.multiTurnConversation }"
                        + ".endToEndResultCounts.Matched", equalTo(2));
        lex("POST", "/testexecutions/" + executionId + "/results", "{\"resultFilterBy\":{\"resultTypeFilter\":"
                + "\"UtteranceLevelResults\"}}").statusCode(200)
                .body("testExecutionResults.utteranceLevelTestResults.items[3].turnResult.agent.actualAgentPrompt",
                        equalTo("What size?"));
        String artifacts = lex("GET", "/testexecutions/" + executionId + "/artifacturl", null).statusCode(200)
                .extract().path("downloadArtifactsUrl");
        given().get(URI.create(artifacts).getPath()).then().statusCode(200);

        String reportId = lex("POST", "/testsets/" + testSetId + "/testsetdiscrepancy", "{\"target\":{"
                + "\"botAliasTarget\":{\"botId\":\"" + botId + "\",\"botAliasId\":\"TSTALIASID\",\"localeId\":"
                + "\"en_US\"}}}").statusCode(202).extract().path("testSetDiscrepancyReportId");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", "/testsetdiscrepancy/" + reportId,
                null).body("testSetDiscrepancyReportStatus", equalTo("Completed"))
                .body("testSetDiscrepancyTopErrors.intentDiscrepancies[0].intentName", equalTo("BookFlight")));

        long now = System.currentTimeMillis() / 1000;
        String generationId = lex("PUT", "/testsetgenerations", "{\"testSetName\":\"FromLogs\",\"roleArn\":\""
                + ROLE + "\",\"storageLocation\":{\"s3BucketName\":\"lex-tests\",\"s3Path\":\"generated/\"},"
                + "\"generationDataSource\":{\"conversationLogsDataSource\":{\"botId\":\"" + botId + "\","
                + "\"botAliasId\":\"TSTALIASID\",\"localeId\":\"en_US\",\"filter\":{\"startTime\":" + (now - 3600)
                + ",\"endTime\":" + (now + 3600) + ",\"inputMode\":\"Text\"}}}}").statusCode(202)
                .extract().path("testSetGenerationId");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", "/testsetgenerations/"
                + generationId, null).body("testSetGenerationStatus", equalTo("Ready"))
                .body("testSetId", notNullValue()));
        lex("POST", "/testsets", "{}").body("testSets.testSetName", hasItems("Pizza", "FromLogs"));
    }

    @Test
    void analyticsFromConversations() {
        String botId = pizzaBot("AnalyticsBot");
        say(botId, "TSTALIASID", "a1", "hello");
        say(botId, "TSTALIASID", "a2", "order a pizza");
        say(botId, "TSTALIASID", "a2", "tiny");
        say(botId, "TSTALIASID", "a2", "yes");
        say(botId, "TSTALIASID", "a3", "purple monkey dishwasher");
        say(botId, "TSTALIASID", "a4", "order a pizza");
        long now = System.currentTimeMillis() / 1000;
        String window = "\"startDateTime\":" + (now - 3600) + ",\"endDateTime\":" + (now + 60);
        lex("POST", "/bots/" + botId + "/analytics/sessionmetrics", "{" + window + ",\"metrics\":[{\"name\":\"Count\","
                + "\"statistic\":\"Sum\"}],\"groupBy\":[{\"name\":\"ConversationEndState\"}]}").statusCode(200)
                .body("results.find { it.groupByKeys[0].value == 'Success' }.metricsResults[0].value",
                        equalTo(3.0f))
                .body("results.find { it.groupByKeys[0].value == 'Dropped' }.metricsResults[0].value",
                        equalTo(1.0f));
        lex("POST", "/bots/" + botId + "/analytics/intentmetrics", "{" + window + ",\"metrics\":[{\"name\":"
                + "\"Count\",\"statistic\":\"Sum\"}],\"groupBy\":[{\"name\":\"IntentName\"}]}").statusCode(200)
                .body("results.find { it.groupByKeys[0].value == 'OrderPizza' }.metricsResults[0].value",
                        equalTo(2.0f));
        lex("POST", "/bots/" + botId + "/analytics/utterancemetrics", "{" + window + ",\"metrics\":[{\"name\":"
                + "\"Missed\",\"statistic\":\"Sum\"}]}").statusCode(200)
                .body("results[0].metricsResults[0].value", equalTo(1.0f));
        lex("POST", "/bots/" + botId + "/analytics/intentstagemetrics", "{" + window + ",\"metrics\":[{\"name\":"
                + "\"Success\",\"statistic\":\"Sum\"}],\"groupBy\":[{\"name\":\"IntentStageName\"}]}").statusCode(200)
                .body("results.find { it.groupByKeys[0].value == 'Size' }.metricsResults[0].value", equalTo(1.0f));
        lex("POST", "/bots/" + botId + "/analytics/intentpaths", "{" + window + ",\"intentPath\":\"/\"}")
                .statusCode(200).body("nodeSummaries.intentName", hasItems("Greet", "OrderPizza", "FallbackIntent"));
        lex("POST", "/bots/" + botId + "/analytics/sessions", "{" + window + ",\"sortBy\":{\"name\":\"NumberOfTurns\","
                + "\"order\":\"Descending\"}}").statusCode(200).body("sessions[0].sessionId", equalTo("a2"))
                .body("sessions[0].numberOfTurns", equalTo(3));
        lex("POST", "/bots/" + botId + "/analytics/utterances", "{" + window + ",\"filters\":[{\"name\":"
                + "\"UtteranceState\",\"operator\":\"EQ\",\"values\":[\"Missed\"]}]}").statusCode(200)
                .body("utterances", hasSize(1)).body("utterances[0].utterance", equalTo("purple monkey dishwasher"));
        lex("POST", "/bots/" + botId + "/aggregatedutterances/", "{\"localeId\":\"en_US\",\"botAliasId\":"
                + "\"TSTALIASID\",\"aggregationDuration\":{\"relativeAggregationDuration\":{\"timeDimension\":"
                + "\"Hours\",\"timeValue\":1}},\"sortBy\":{\"attribute\":\"HitCount\",\"order\":\"Descending\"}}")
                .statusCode(200).body("aggregatedUtterancesSummaries[0].utterance", equalTo("order a pizza"))
                .body("aggregatedUtterancesSummaries[0].hitCount", equalTo(2));
        lex("DELETE", "/bots/" + botId + "/utterances/?sessionId=a3", null).statusCode(204);
        lex("POST", "/bots/" + botId + "/analytics/utterances", "{" + window + ",\"filters\":[{\"name\":"
                + "\"UtteranceState\",\"operator\":\"EQ\",\"values\":[\"Missed\"]}]}").body("utterances", hasSize(0));
    }

    @Test
    void recommendationsGenerationAndAnalysis() {
        String botId = pizzaBot("DesignBot");
        s3Put("lex-transcripts", "calls/1.json", transcript("I need to check my account balance"));
        s3Put("lex-transcripts", "calls/2.json", transcript("what is my account balance"));
        s3Put("lex-transcripts", "calls/3.json", transcript("I want to reset my password"));
        String recommendationId = lex("PUT", locale(botId) + "botrecommendations/", "{\"transcriptSourceSetting\":{"
                + "\"s3BucketTranscriptSource\":{\"s3BucketName\":\"lex-transcripts\",\"pathFormat\":{"
                + "\"objectPrefixes\":[\"calls\"]},\"transcriptFormat\":\"Lex\"}}}").statusCode(202)
                .extract().path("botRecommendationId");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", locale(botId) + "botrecommendations/"
                + recommendationId + "/", null).body("botRecommendationStatus", equalTo("Available"))
                .body("botRecommendationResults.statistics.intents.discoveredIntentCount", equalTo(2)));
        List<String> intents = lex("POST", locale(botId) + "botrecommendations/" + recommendationId + "/intents",
                "{}").statusCode(200).extract().path("summaryList.intentId");
        lex("POST", locale(botId) + "botrecommendations/" + recommendationId + "/associatedtranscripts",
                "{\"filters\":[{\"name\":\"IntentId\",\"values\":[\"" + intents.get(0) + "\"]}]}").statusCode(200)
                .body("associatedTranscripts", hasSize(2));

        lex("POST", locale(botId) + "generate", "{\"intentId\":\"FALLBCKINT\"}").statusCode(412);
        lex("PUT", locale(botId), "{\"nluIntentConfidenceThreshold\":0.4,\"generativeAISettings\":{"
                + "\"buildtimeSettings\":{\"descriptiveBotBuilder\":{\"enabled\":true},\"sampleUtteranceGeneration\":{"
                + "\"enabled\":true}}}}").statusCode(202);
        String greetId = lex("POST", locale(botId) + "intents/", "{\"filters\":[{\"name\":\"IntentName\",\"values\":["
                + "\"Greet\"],\"operator\":\"EQ\"}]}").extract().path("intentSummaries[0].intentId");
        lex("POST", locale(botId) + "generate", "{\"intentId\":\"" + greetId + "\"}").statusCode(202)
                .body("sampleUtterances.utterance", hasItem("I want to hello"));
        String generationId = lex("PUT", locale(botId) + "startgeneration", "{\"generationInputPrompt\":\"A bot for a "
                + "pizza shop. Customers can order a pizza, track a delivery and cancel an order. It should also "
                + "answer questions about opening hours for the shop.\"}").statusCode(202).extract()
                .path("generationId");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", locale(botId) + "generations/"
                + generationId, null).body("generationStatus", equalTo("Complete"))
                .body("generatedBotLocaleUrl", notNullValue()));

        String requestId = lex("POST", "/bots/" + botId + "/botanalyzer/", "{\"analysisScope\":\"BotLocale\","
                + "\"localeId\":\"en_US\",\"botVersion\":\"DRAFT\"}").statusCode(202).extract()
                .path("botAnalyzerRequestId");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("POST", "/bots/" + botId
                + "/botanalyzer/describe/" + requestId + "/", "{}").body("botAnalyzerStatus", equalTo("Available"))
                .body("botAnalyzerRecommendationList.issueDescription", hasItem(containsString(
                        "has 3 sample utterances"))));
        lex("POST", "/bots/" + botId + "/botanalyzer/history/", "{}").body("botAnalyzerHistoryList", hasSize(1));
        lex("DELETE", "/bots/" + botId + "/botanalyzer/" + requestId + "/", null).statusCode(204);
    }

    private static String transcript(String request) {
        return "{\"Participants\":[{\"ParticipantId\":\"AGENT\",\"ParticipantRole\":\"AGENT\"},{\"ParticipantId\":"
                + "\"CUSTOMER\",\"ParticipantRole\":\"CUSTOMER\"}],\"Version\":\"1.1.0\",\"ContentMetadata\":{"
                + "\"Output\":\"Raw\"},\"CustomerMetadata\":{\"ContactId\":\"c\"},\"Transcript\":[{\"ParticipantId\":"
                + "\"AGENT\",\"Id\":\"1\",\"Content\":\"How can I help?\"},{\"ParticipantId\":\"CUSTOMER\",\"Id\":"
                + "\"2\",\"Content\":\"" + request + "\"}]}";
    }

    @Test
    void tagsPoliciesAndReplicas() {
        String botId = createBot("PolicyBot");
        String arn = "arn:aws:lex:us-east-1:000000000000:bot/" + botId;
        String encoded = arn.replace(":", "%3A").replace("/", "%2F");
        lex("GET", "/tags/" + encoded, null).statusCode(200).body("tags.team", equalTo("chat"));
        lex("POST", "/tags/" + encoded, "{\"tags\":{\"env\":\"dev\"}}").statusCode(200);
        lex("DELETE", "/tags/" + encoded + "?tagKeys=team", null).statusCode(200);
        lex("GET", "/tags/" + encoded, null).body("tags.env", equalTo("dev")).body("tags.team", nullValue());

        lex("POST", "/policy/" + encoded + "/statements/", "{\"statementId\":\"connect\",\"effect\":\"Allow\","
                + "\"principal\":[{\"service\":\"connect.amazonaws.com\"}],\"action\":[\"lex:RecognizeText\"]}")
                .statusCode(200).body("revisionId", equalTo("1"));
        lex("GET", "/policy/" + encoded + "/", null).statusCode(200).body("policy", containsString(
                "connect.amazonaws.com"));
        lex("DELETE", "/policy/" + encoded + "/?expectedRevisionId=9", null).statusCode(412);
        lex("DELETE", "/policy/" + encoded + "/statements/connect/?expectedRevisionId=1", null).statusCode(204);
        lex("GET", "/policy/" + encoded + "/", null).body("policy", containsString("\"Statement\":[]"));

        lex("PUT", "/bots/" + botId + "/replicas/", "{\"replicaRegion\":\"us-west-2\"}").statusCode(202)
                .body("botReplicaStatus", equalTo("Enabling"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", "/bots/" + botId
                + "/replicas/us-west-2/", null).body("botReplicaStatus", equalTo("Enabled")));
        lex("POST", "/bots/" + botId + "/replicas/", "{}").body("botReplicaSummaries.replicaRegion",
                contains("us-west-2"));
        lex("POST", "/bots/" + botId + "/replicas/us-west-2/botaliases/", "{}").statusCode(200)
                .body("botAliasReplicaSummaries.botAliasId", hasItem("TSTALIASID"));
        lex("DELETE", "/bots/" + botId + "/replicas/us-west-2/", null).statusCode(202);

        lex("POST", "/builtins/locales/en_US/intents/", "{}").statusCode(200)
                .body("builtInIntentSummaries.intentSignature", hasItem("AMAZON.FallbackIntent"));
        lex("POST", "/builtins/locales/en_US/slottypes/", "{\"maxResults\":20}").statusCode(200)
                .body("builtInSlotTypeSummaries.slotTypeSignature", hasItem("AMAZON.Number"));
        lex("DELETE", "/bots/" + botId + "/", null).statusCode(202).body("botStatus", equalTo("Deleting"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lex("GET", "/bots/" + botId + "/", null)
                .statusCode(404));
    }

    @Test
    void customVocabulary() {
        String botId = pizzaBot("VocabBot");
        lex("PUT", locale(botId) + "customvocabulary/DEFAULT/batchcreate", "{\"customVocabularyItemList\":["
                + "{\"phrase\":\"margherita\",\"weight\":3},{\"phrase\":\"calzone\"}]}").statusCode(200)
                .body("resources", hasSize(2)).body("errors", hasSize(0));
        lex("PUT", locale(botId) + "customvocabulary/DEFAULT/batchcreate", "{\"customVocabularyItemList\":["
                + "{\"phrase\":\"calzone\"}]}").body("errors[0].errorCode", equalTo("RESOURCE_ALREADY_EXISTS"));
        String itemId = lex("POST", locale(botId) + "customvocabulary/DEFAULT/list", "{}").statusCode(200)
                .body("customVocabularyItems", hasSize(2)).extract().path("customVocabularyItems[0].itemId");
        lex("PUT", locale(botId) + "customvocabulary/DEFAULT/batchupdate", "{\"customVocabularyItemList\":["
                + "{\"itemId\":\"" + itemId + "\",\"phrase\":\"marinara\"}]}").body("resources[0].phrase",
                equalTo("marinara"));
        lex("GET", locale(botId) + "customvocabulary/DEFAULT/metadata", null).statusCode(200)
                .body("customVocabularyStatus", equalTo("Ready"));
        lex("GET", locale(botId), null).body("botLocaleStatus", equalTo("NotBuilt"));
        lex("DELETE", locale(botId) + "customvocabulary", null).statusCode(202);
        lex("GET", locale(botId) + "customvocabulary/DEFAULT/metadata", null).statusCode(404);
    }

    @Inject
    Vertx vertx;

    @TestHTTPResource("/")
    URI base;

    private static byte[] event(String type, String json) throws IOException {
        LinkedHashMap<String, String> inner = new LinkedHashMap<>();
        inner.put(":message-type", "event");
        inner.put(":event-type", type);
        inner.put(":content-type", "application/json");
        byte[] message = AwsEventStreamEncoder.encodeMessage(inner, json.getBytes(StandardCharsets.UTF_8));
        LinkedHashMap<String, String> envelope = new LinkedHashMap<>();
        envelope.put(":date", "20260101T000000Z");
        envelope.put(":chunk-signature", "00");
        return AwsEventStreamEncoder.encodeMessage(envelope, message);
    }

    @Test
    void streamsAConversationOverHttp2() throws Exception {
        String botId = pizzaBot("StreamBot");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(event("ConfigurationEvent", "{\"responseContentType\":\"text/plain; charset=utf-8\","
                + "\"welcomeMessages\":[{\"contentType\":\"PlainText\",\"content\":\"Welcome!\"}]}"));
        body.writeBytes(event("TextInputEvent", "{\"text\":\"order a pizza\"}"));
        body.writeBytes(event("TextInputEvent", "{\"text\":\"tiny\"}"));
        body.writeBytes(event("DTMFInputEvent", "{\"inputCharacter\":\"1\"}"));
        body.writeBytes(event("DTMFInputEvent", "{\"inputCharacter\":\"#\"}"));
        body.writeBytes(event("DisconnectionEvent", "{}"));
        HttpClient client = vertx.createHttpClient(new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(false));
        try {
            String path = "/bots/" + botId + "/botAliases/TSTALIASID/botLocales/en_US/sessions/stream-1/conversation";
            Buffer response = client.request(HttpMethod.POST, base.getPort(), base.getHost(), path)
                    .compose(request -> request.putHeader("Authorization", auth("lex"))
                            .putHeader("Content-Type", "application/vnd.amazon.eventstream")
                            .send(Buffer.buffer(body.toByteArray()))
                            .compose(r -> {
                                assertEquals(200, r.statusCode());
                                assertEquals(HttpVersion.HTTP_2, r.version());
                                return r.body();
                            }))
                    .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
            AwsEventStreamDecoder decoder = new AwsEventStreamDecoder(new ByteArrayInputStream(response.getBytes()));
            List<String> events = new ArrayList<>();
            StringBuilder payloads = new StringBuilder();
            AwsEventStreamDecoder.Message message;
            while ((message = decoder.next()) != null) {
                events.add(message.header(":event-type"));
                payloads.append(new String(message.payload(), StandardCharsets.UTF_8)).append('\n');
            }
            assertEquals(List.of("TextResponseEvent", "IntentResultEvent", "TextResponseEvent", "IntentResultEvent",
                    "TextResponseEvent", "IntentResultEvent", "TextResponseEvent"), events, payloads.toString());
            String all = payloads.toString();
            assertTrue(all.contains("Welcome!"), all);
            assertTrue(all.contains("\"slotToElicit\":\"Size\""), all);
            assertTrue(all.contains("A small pizza, right?"), all);
            assertTrue(all.contains("\"inputMode\":\"DTMF\""), all);
            assertTrue(all.contains("\"eventId\":\"RESPONSE-7\""), all);
        } finally {
            client.close();
        }
    }
}
