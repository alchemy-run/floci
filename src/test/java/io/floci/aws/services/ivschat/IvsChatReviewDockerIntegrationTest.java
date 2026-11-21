package io.floci.aws.services.ivschat;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.floci.aws.services.ivschat.IvsChatIntegrationTest.chat;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A room's message review handler, a real Lambda function: it edits the messages it allows, denies with a reason,
 * and when it fails or answers invalidly the room's fallback result decides.
 */
@QuarkusTest
@Tag("docker")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class IvsChatReviewDockerIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String HANDLER = """
            exports.handler = async (event) => {
              if (event.Content.includes("boom")) throw new Error("boom");
              if (event.Content.includes("deny")) return { ReviewResult: "DENY", Attributes: { Reason: "moderated" } };
              if (event.Content.includes("invalid")) return { ReviewResult: "MAYBE" };
              return { ReviewResult: "ALLOW", Content: event.Content + " [ok]",
                       Attributes: { reviewer: event.Sender.UserId + "@" + event.RoomArn.split("/")[1] } };
            };
            """;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String zip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(HANDLER.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static String send(IvsChatIntegrationTest.Client client, String content) {
        client.send("{\"Action\":\"SEND_MESSAGE\",\"Content\":\"" + content + "\"}");
        return content;
    }

    @Test
    void theHandlerReviewsEveryMessage() throws Exception {
        String function = given().contentType("application/json").body("""
                {"FunctionName": "ivschat-review", "Runtime": "nodejs20.x", "Handler": "index.handler",
                 "Role": "arn:aws:iam::%s:role/lambda-role", "Timeout": 30, "Code": {"ZipFile": "%s"}}
                """.formatted(ACCOUNT, zip())).post("/2015-03-31/functions").then().statusCode(201)
                .extract().path("FunctionArn");
        try {
            String allowing = chat("CreateRoom", "{\"messageReviewHandler\":{\"uri\":\"" + function + "\"}}")
                    .statusCode(200).extract().path("arn");
            String denying = chat("CreateRoom", "{\"messageReviewHandler\":{\"uri\":\"" + function
                    + "\",\"fallbackResult\":\"DENY\"}}").statusCode(200).extract().path("arn");
            IvsChatIntegrationTest.Client client = IvsChatIntegrationTest.Client.connect(
                    chat("CreateChatToken", "{\"roomIdentifier\":\"" + allowing + "\",\"userId\":\"ann\","
                            + "\"capabilities\":[\"SEND_MESSAGE\"]}").statusCode(200).extract().path("token"));

            send(client, "hello");
            JsonNode allowed = client.next();
            assertEquals("MESSAGE", allowed.path("Type").asText(), allowed.toString());
            assertEquals("hello [ok]", allowed.path("Content").asText());
            assertEquals("ann@" + allowing.substring(allowing.lastIndexOf('/') + 1),
                    allowed.path("Attributes").path("reviewer").asText());

            send(client, "please deny");
            JsonNode denied = client.next();
            assertEquals(406, denied.path("ErrorCode").asInt(), denied.toString());
            assertEquals("moderated", denied.path("ErrorMessage").asText());

            // A failing handler, and an invalid answer, fall back to ALLOW: the original message is delivered.
            send(client, "boom");
            assertEquals("boom", client.next().path("Content").asText());
            send(client, "invalid");
            assertEquals("invalid", client.next().path("Content").asText());

            IvsChatIntegrationTest.Client strict = IvsChatIntegrationTest.Client.connect(
                    chat("CreateChatToken", "{\"roomIdentifier\":\"" + denying + "\",\"userId\":\"bo\","
                            + "\"capabilities\":[\"SEND_MESSAGE\"]}").statusCode(200).extract().path("token"));
            send(strict, "boom");
            assertEquals(406, strict.next().path("ErrorCode").asInt());

            chat("DeleteRoom", "{\"identifier\":\"" + allowing + "\"}").statusCode(200);
            chat("DeleteRoom", "{\"identifier\":\"" + denying + "\"}").statusCode(200);
        } finally {
            given().delete("/2015-03-31/functions/ivschat-review").then().statusCode(204);
        }
    }
}
