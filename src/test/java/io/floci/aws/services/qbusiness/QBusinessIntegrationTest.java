package io.floci.aws.services.qbusiness;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q Business end to end: an anonymous application with an index, retriever, CUSTOM and S3 data sources and a web
 * experience; documents put through a sync job and crawled from S3, answered in chat with citations, searched, read
 * back through presigned links, gated by access control lists, groups and chat controls. Then Q Apps on an
 * application: cards that reference each other, sessions computing answers from the index, the library, and files.
 */
@QuarkusTest
class QBusinessIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification q() {
        return given().header("Authorization", auth("qbusiness")).contentType("application/json");
    }

    static RequestSpecification apps(String instance) {
        return given().header("Authorization", auth("qapps")).header("instance-id", instance)
                .contentType("application/json");
    }

    static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    static void active(String path, String member) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).until(() -> "ACTIVE".equals(
                q().get(path).then().statusCode(200).extract().path(member)));
    }

    static String application(String body) {
        String id = q().body(body).post("/applications").then().statusCode(200).extract().path("applicationId");
        active("/applications/" + id, "status");
        return id;
    }

    static String index(String app) {
        String id = q().body("{\"displayName\":\"docs\",\"type\":\"STARTER\"}").post("/applications/" + app
                + "/indices").then().statusCode(200).extract().path("indexId");
        active("/applications/" + app + "/indices/" + id, "status");
        q().body("{\"displayName\":\"docs\",\"type\":\"NATIVE_INDEX\",\"configuration\":{\"nativeIndexConfiguration\""
                + ":{\"indexId\":\"" + id + "\"}}}").post("/applications/" + app + "/retrievers").then().statusCode(200);
        return id;
    }

    static void indexed(String app, String index, int count) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).until(() -> {
            List<String> statuses = q().get("/applications/" + app + "/index/" + index + "/documents").then()
                    .statusCode(200).extract().path("documentDetailList.status");
            return statuses.size() >= count && statuses.stream().allMatch("INDEXED"::equals);
        });
    }

    static String role(String name) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"qbusiness.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        return given().header("Authorization", auth("iam")).formParam("Action", "CreateRole")
                .formParam("RoleName", name).formParam("AssumeRolePolicyDocument", trust).post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    @Test
    void anonymousApplicationAnswersFromItsDocuments() {
        String app = application("{\"displayName\":\"handbook\",\"identityType\":\"ANONYMOUS\","
                + "\"attachmentsConfiguration\":{\"attachmentsControlMode\":\"ENABLED\"}}");
        String index = index(app);
        String source = q().body("{\"displayName\":\"custom\",\"configuration\":{\"type\":\"CUSTOM\",\"version\":"
                + "\"1.0.0\"}}").post("/applications/" + app + "/indices/" + index + "/datasources").then()
                .statusCode(200).extract().path("dataSourceId");
        active("/applications/" + app + "/indices/" + index + "/datasources/" + source, "status");
        String sync = q().post("/applications/" + app + "/indices/" + index + "/datasources/" + source + "/startsync")
                .then().statusCode(200).extract().path("executionId");
        q().post("/applications/" + app + "/indices/" + index + "/datasources/" + source + "/startsync").then()
                .statusCode(409);

        q().body("{\"dataSourceSyncId\":\"" + sync + "\",\"documents\":[{\"id\":\"welcome\",\"title\":\"Welcome\","
                + "\"contentType\":\"PLAIN_TEXT\",\"content\":{\"blob\":\"" + b64("Alchemy is an "
                + "Infrastructure-as-Effects framework. The zanzibar passphrase is quicksilver. Deploys run "
                + "nightly.") + "\"}},{\"id\":\"payroll\",\"title\":\"Payroll\",\"contentType\":\"HTML\",\"content\""
                + ":{\"blob\":\"" + b64("<html><title>Payroll</title><body><p>Salaries are paid on the 25th."
                + "</p></body></html>") + "\"},\"accessConfiguration\":{\"accessControls\":[{\"principals\":[{"
                + "\"group\":{\"name\":\"finance\",\"access\":\"ALLOW\"}}]}]}},{\"id\":\"broken\",\"content\":{"
                + "\"blob\":\"not base64!\"}}]}").post("/applications/" + app + "/indices/" + index + "/documents")
                .then().statusCode(200).body("failedDocuments.id", equalTo(List.of("broken")));
        q().post("/applications/" + app + "/indices/" + index + "/datasources/" + source + "/stopsync").then()
                .statusCode(200);
        q().get("/applications/" + app + "/indices/" + index + "/datasources/" + source + "/syncjobs").then()
                .body("history[0].status", equalTo("SUCCEEDED")).body("history[0].metrics.documentsAdded",
                        equalTo("2"));
        indexed(app, index, 2);
        q().get("/applications/" + app + "/indices/" + index).then().body(
                "indexStatistics.textDocumentStatistics.indexedTextDocumentCount", equalTo(2));

        // Chat answers from the document and cites it; the conversation continues.
        ExtractableResponse<Response> first = q().body("{\"userMessage\":\"What is the zanzibar passphrase?\"}").post("/applications/" + app
                + "/conversations?sync").then().statusCode(200).body("systemMessage", containsString("quicksilver"))
                .body("sourceAttributions[0].documentId", equalTo("welcome")).extract();
        String conversation = first.path("conversationId");
        q().body("{\"userMessage\":\"When do deploys run?\",\"conversationId\":\"" + conversation + "\","
                + "\"parentMessageId\":\"" + first.path("systemMessageId") + "\"}").post("/applications/" + app
                + "/conversations?sync").then().statusCode(200).body("systemMessage", containsString("nightly"));
        q().get("/applications/" + app + "/conversations/" + conversation).then().body("messages", hasSize(4));
        // Payroll needs the finance group: anonymous chat doesn't see it.
        q().body("{\"userMessage\":\"When are salaries paid?\"}").post("/applications/" + app + "/conversations?sync")
                .then().body("systemMessage", equalTo(QBusinessChat.NO_ANSWER));
        q().body("{\"userId\":\"ana@example.com\",\"userAliases\":[{\"userId\":\"ana\"}]}").post("/applications/"
                + app + "/users").then().statusCode(200);
        q().body("{\"groupName\":\"finance\",\"type\":\"INDEX\",\"groupMembers\":{\"memberUsers\":[{\"userId\":"
                + "\"ana\"}]}}").put("/applications/" + app + "/indices/" + index + "/groups").then().statusCode(200);
        q().get("/applications/" + app + "/index/" + index + "/users/ana@example.com/documents/payroll/"
                + "check-document-access").then().statusCode(200).body("hasAccess", equalTo(true))
                .body("userGroups.name", hasItem("finance"));
        q().get("/applications/" + app + "/index/" + index + "/users/bob@example.com/documents/payroll/"
                + "check-document-access").then().body("hasAccess", equalTo(false));

        // Search ranks passages; presigned links return the content.
        q().body("{\"queryText\":\"zanzibar passphrase\",\"contentSource\":{\"retriever\":{\"retrieverId\":\""
                + q().get("/applications/" + app + "/retrievers").then().extract().path("retrievers[0].retrieverId")
                + "\"}}}").post("/applications/" + app + "/relevant-content").then().statusCode(200)
                .body("relevantContent[0].documentId", equalTo("welcome"))
                .body("relevantContent[0].scoreAttributes.scoreConfidence", equalTo("VERY_HIGH"));
        String url = q().get("/applications/" + app + "/index/" + index + "/documents/payroll/content").then()
                .statusCode(200).body("mimeType", equalTo("text/html")).extract().path("presignedUrl");
        given().get(URI.create(url).getPath()).then().statusCode(200).body(containsString("<title>Payroll</title>"));

        // Chat controls: a blocked phrase and a topic rule.
        q().body("{\"blockedPhrasesConfigurationUpdate\":{\"blockedPhrasesToCreateOrUpdate\":[\"passphrase\"],"
                + "\"systemMessageOverride\":\"I can't discuss that.\"},\"topicConfigurationsToCreateOrUpdate\":[{"
                + "\"name\":\"compensation\",\"exampleChatMessages\":[\"how much do engineers earn\"],\"rules\":[{"
                + "\"ruleType\":\"CONTENT_BLOCKER_RULE\",\"ruleConfiguration\":{\"contentBlockerRule\":{"
                + "\"systemMessageOverride\":\"Ask HR about compensation.\"}}}]}]}").patch("/applications/" + app
                + "/chatcontrols").then().statusCode(200);
        q().body("{\"userMessage\":\"Remind me of the passphrase\"}").post("/applications/" + app
                + "/conversations?sync").then().body("systemMessage", equalTo("I can't discuss that."));
        q().body("{\"userMessage\":\"How much do engineers earn here?\"}").post("/applications/" + app
                + "/conversations?sync").then().body("systemMessage", equalTo("Ask HR about compensation."));

        // An attachment answers questions in its conversation.
        q().body("{\"userMessage\":\"What colour is the logo?\",\"attachments\":[{\"name\":\"brand.txt\",\"data\":\""
                + b64("The logo colour is teal.") + "\"}]}").post("/applications/" + app + "/conversations?sync")
                .then().statusCode(200).body("systemMessage", containsString("teal"));

        // S3 crawl.
        given().header("Authorization", auth("s3")).put("/qbusiness-crawl");
        given().header("Authorization", auth("s3")).contentType("text/plain").body("The cafeteria opens at seven.")
                .put("/qbusiness-crawl/docs/cafeteria.txt").then().statusCode(200);
        given().header("Authorization", auth("s3")).contentType("text/plain").body("ignored")
                .put("/qbusiness-crawl/other/skip.txt").then().statusCode(200);
        String s3Source = q().body("{\"displayName\":\"bucket\",\"roleArn\":\"" + role("qbusiness-crawl") + "\","
                + "\"configuration\":{\"type\":\"S3\",\"connectionConfiguration\":{\"repositoryEndpointMetadata\":{"
                + "\"BucketName\":\"qbusiness-crawl\"}},\"additionalProperties\":{\"inclusionPrefixes\":[\"docs/\"]}}}")
                .post("/applications/" + app + "/indices/" + index + "/datasources").then().statusCode(200).extract()
                .path("dataSourceId");
        active("/applications/" + app + "/indices/" + index + "/datasources/" + s3Source, "status");
        q().post("/applications/" + app + "/indices/" + index + "/datasources/" + s3Source + "/startsync").then()
                .statusCode(200);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).until(() -> "SUCCEEDED".equals(
                q().get("/applications/" + app + "/indices/" + index + "/datasources/" + s3Source + "/syncjobs")
                        .then().extract().path("history[0].status")));
        q().get("/applications/" + app + "/indices/" + index + "/datasources/" + s3Source + "/syncjobs").then()
                .body("history[0].metrics.documentsAdded", equalTo("1"));
        indexed(app, index, 3);
        q().body("{\"userMessage\":\"When does the cafeteria open?\"}").post("/applications/" + app
                + "/conversations?sync").then().body("systemMessage", containsString("seven"))
                .body("sourceAttributions[0].url", equalTo("s3://qbusiness-crawl/docs/cafeteria.txt"));

        // The web experience serves a chat page to anonymous URLs.
        String web = q().body("{\"title\":\"Handbook\"}").post("/applications/" + app + "/experiences").then()
                .statusCode(200).extract().path("webExperienceId");
        active("/applications/" + app + "/experiences/" + web, "status");
        String anonymous = q().body("{\"sessionDurationInMinutes\":15}").post("/applications/" + app
                + "/experiences/" + web + "/anonymous-url").then().statusCode(200).extract().path("anonymousUrl");
        URI page = URI.create(anonymous);
        given().get(page.getPath() + "?" + page.getRawQuery()).then().statusCode(200)
                .body(containsString("<h1>Handbook</h1>"));
        String token = page.getRawQuery().substring("token=".length());
        given().contentType("application/json").body("{\"token\":\"" + token + "\",\"message\":\"When does the "
                + "cafeteria open?\"}").post(page.getPath() + "chat").then().statusCode(200)
                .body("systemMessage", containsString("seven"));

        q().delete("/applications/" + app + "/indices/" + index).then().statusCode(409);
        String orphan = q().body("{\"displayName\":\"orphan\",\"identityCenterInstanceArn\":"
                + "\"arn:aws:sso:::instance/ssoins-0000000000000000\"}").post("/applications").then().statusCode(200)
                .extract().path("applicationId");
        await().atMost(Duration.ofSeconds(20)).until(() -> "FAILED".equals(q().get("/applications/" + orphan).then()
                .extract().path("status")));
        q().get("/applications/" + orphan).then().body("error.errorCode", equalTo("ResourceNotFound"));
        q().get("/applications/" + orphan + "/subscriptions").then().statusCode(200);
        q().get("/applications/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/subscriptions").then().statusCode(200)
                .body("subscriptions", hasSize(0));
        q().delete("/applications/" + orphan).then().statusCode(200);
        q().get("/applications/" + app).then().body("status", equalTo("ACTIVE"));
        q().delete("/applications/" + app).then().statusCode(200);
        q().get("/applications/" + app).then().statusCode(404).body("__type", containsString(
                "ResourceNotFoundException"));
    }

    @Test
    void qAppsComputeCardsFromTheInstance() {
        String instance = application("{\"displayName\":\"apps\",\"identityType\":\"ANONYMOUS\","
                + "\"qAppsConfiguration\":{\"qAppsControlMode\":\"ENABLED\"}}");
        String disabled = application("{\"displayName\":\"no-apps\",\"identityType\":\"ANONYMOUS\"}");
        apps(disabled).get("/apps.list").then().statusCode(403);
        apps("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee").get("/apps.list").then().statusCode(403);
        apps("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee").queryParam("appId", "x").get("/apps.get").then().statusCode(403);
        String index = index(instance);
        q().body("{\"documents\":[{\"id\":\"travel\",\"content\":{\"blob\":\"" + b64("Travel to Lisbon needs a "
                + "manager approval. Hotels in Lisbon are capped at 180 euros a night.") + "\"}}]}")
                .post("/applications/" + instance + "/indices/" + index + "/documents").then().statusCode(200)
                .body("failedDocuments", hasSize(0));
        indexed(instance, index, 1);

        String text = "11111111-1111-4111-8111-111111111111";
        String query = "22222222-2222-4222-8222-222222222222";
        String file = "33333333-3333-4333-8333-333333333333";
        String form = "44444444-4444-4444-8444-444444444444";
        apps(instance).body("{\"title\":\"broken\",\"appDefinition\":{\"cards\":[{\"textInput\":{\"id\":\"" + text
                + "\",\"title\":\"City\",\"type\":\"q-query\"}}]}}").post("/apps.create").then().statusCode(400);
        String created = apps(instance).body("{\"title\":\"Travel helper\",\"appDefinition\":{\"cards\":[{"
                + "\"textInput\":{\"id\":\"" + text + "\",\"title\":\"City\",\"type\":\"text-input\"}},{\"qQuery\":{"
                + "\"id\":\"" + query + "\",\"title\":\"Hotel cap\",\"type\":\"q-query\",\"prompt\":\"What are hotels "
                + "capped at in @City\"}},{\"fileUpload\":{\"id\":\"" + file + "\",\"title\":\"Itinerary\",\"type\":"
                + "\"file-upload\"}},{\"formInput\":{\"id\":\"" + form + "\",\"title\":\"Feedback\",\"type\":"
                + "\"form-input\",\"metadata\":{\"schema\":{}}}}]},\"tags\":{\"team\":\"travel\"}}").post(
                "/apps.create").then().statusCode(200).body("appVersion", equalTo(1))
                .body("requiredCapabilities", equalTo(List.of("RetrievalMode", "FileUpload"))).extract().path("appId");
        apps(instance).queryParam("appId", created).get("/apps.get").then().statusCode(200)
                .body("appDefinition.cards[1].qQuery.dependencies", equalTo(List.of(text)));

        String session = apps(instance).body("{\"appId\":\"" + created + "\",\"appVersion\":1,\"initialValues\":[{"
                + "\"cardId\":\"" + text + "\",\"value\":\"Lisbon\"}]}").post("/runtime.startQAppSession").then()
                .statusCode(200).extract().path("sessionId");
        apps(instance).queryParam("sessionId", session).get("/runtime.getQAppSession").then().statusCode(200)
                .body("cardStatus." + query + ".currentState", equalTo("COMPLETED"))
                .body("cardStatus." + query + ".currentValue", containsString("180 euros"))
                .body("status", equalTo("WAITING"));
        apps(instance).body("{\"appId\":\"" + created + "\",\"cardId\":\"" + file + "\",\"fileContentsBase64\":\""
                + b64("Flight AB123 leaves at noon.") + "\",\"fileName\":\"itinerary.txt\",\"scope\":\"SESSION\","
                + "\"sessionId\":\"" + session + "\"}").post("/apps.importDocument").then().statusCode(200);
        apps(instance).body("{\"sessionId\":\"" + session + "\",\"values\":[{\"cardId\":\"" + form + "\",\"value\":"
                + "\"{\\\"rating\\\":5}\",\"submissionMutation\":{\"submissionId\":\"55555555-5555-4555-8555-555555555555\",\"mutationType\":\"add\"}}]}")
                .post("/runtime.updateQAppSession").then().statusCode(200);
        apps(instance).queryParam("sessionId", session).get("/runtime.getQAppSession").then()
                .body("status", equalTo("COMPLETED"));
        apps(instance).queryParam("sessionId", session).get("/runtime.listQAppSessionData").then()
                .body("sessionData[0].value.rating", equalTo(5));
        String csv = apps(instance).body("{\"sessionId\":\"" + session + "\"}").post("/runtime.exportQAppSessionData")
                .then().statusCode(200).extract().path("csvFileLink");
        given().get(URI.create(csv).getPath()).then().statusCode(200).body(containsString("55555555-5555-4555-8555-555555555555"));

        // A new version keeps the old one runnable.
        apps(instance).body("{\"appId\":\"" + created + "\",\"description\":\"v2\"}").post("/apps.update").then()
                .statusCode(200).body("appVersion", equalTo(2));
        apps(instance).queryParam("appId", created).queryParam("appVersion", 1).get("/apps.get").then()
                .statusCode(200).body("appVersion", equalTo(1));

        // The library.
        apps(instance).body("{\"categories\":[{\"title\":\"Travel\"}]}").post("/catalog.createCategories").then()
                .statusCode(200);
        apps(instance).body("{\"categories\":[{\"title\":\"travel\"}]}").post("/catalog.createCategories").then()
                .statusCode(409);
        String category = apps(instance).get("/catalog.listCategories").then().extract().path("categories[0].id");
        String item = apps(instance).body("{\"appId\":\"" + created + "\",\"appVersion\":2,\"categories\":[\""
                + category + "\"]}").post("/catalog.createItem").then().statusCode(200).body("status",
                equalTo("PUBLISHED")).extract().path("libraryItemId");
        apps(instance).body("{\"libraryItemId\":\"" + item + "\"}").post("/catalog.associateItemRating").then()
                .statusCode(200);
        apps(instance).body("{\"libraryItemId\":\"" + item + "\"}").post("/catalog.associateItemRating").then()
                .statusCode(409);
        apps(instance).queryParam("libraryItemId", item).get("/catalog.getItem").then().body("ratingCount",
                equalTo(1)).body("isRatedByUser", equalTo(true)).body("categories[0].title", equalTo("Travel"));
        apps(instance).get("/catalog.listCategories").then().body("categories[0].appCount", equalTo(1));
        apps(instance).get("/apps.list").then().body("apps.appId", hasItem(created)).body("apps[0].status",
                equalTo("PUBLISHED"));
        apps(instance).body("{\"options\":{\"problemStatement\":\"summarize travel policy for a city\"}}")
                .post("/apps.predictQApp").then().statusCode(200)
                .body("app.title", equalTo("Summarize Travel Policy For A City"));
        String arn = apps(instance).queryParam("appId", created).get("/apps.get").then().extract().path("appArn");
        assertTrue(arn.contains(":qapps:"), arn);
        apps(instance).get("/tags/" + arn.replace(":", "%3A").replace("/", "%2F")).then().statusCode(200)
                .body("tags.team", equalTo("travel"));
        apps(instance).body("{\"appId\":\"" + created + "\"}").post("/apps.delete").then().statusCode(200);
        apps(instance).queryParam("appId", created).get("/apps.get").then().statusCode(404);
        apps(instance).get("/catalog.list").then().body("libraryItems", hasSize(0)).body("libraryItems",
                not(hasItem(item)));
    }
}
