package io.floci.aws.services.socialmessaging;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * End User Messaging Social: a two-step WhatsApp signup, templates reviewed before they can be sent, a message
 * announced to an SNS event destination, media through S3, the flow lifecycle, business keys and tags.
 */
@QuarkusTest
class SocialMessagingIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification social() {
        return given().header("Authorization", auth("social-messaging")).contentType("application/json");
    }

    static RequestSpecification query(String service) {
        return given().header("Authorization", auth(service)).contentType("application/x-www-form-urlencoded");
    }

    static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    static String xml(RequestSpecification spec, String element) {
        String body = spec.post("/").then().statusCode(200).extract().asString();
        return body.replaceAll("(?s).*<" + element + ">(.*?)</" + element + ">.*", "$1");
    }

    @Test
    void whatsAppBusinessMessaging() throws Exception {
        String topic = xml(query("sns").formParam("Action", "CreateTopic").formParam("Name", "whatsapp"), "TopicArn");
        String queueUrl = xml(query("sqs").formParam("Action", "CreateQueue").formParam("QueueName", "whatsapp"),
                "QueueUrl");
        query("sns").formParam("Action", "Subscribe").formParam("TopicArn", topic).formParam("Protocol", "sqs")
                .formParam("Endpoint", "arn:aws:sqs:us-east-1:000000000000:whatsapp").post("/").then()
                .statusCode(200);

        // Signup: the callback lists the account to finish; finalization links it.
        String token = social().body("{\"signupCallback\":{\"accessToken\":\"meta-token-1\"}}")
                .post("/v1/whatsapp/signup").then().statusCode(200)
                .extract().path("signupCallbackResult.associateInProgressToken");
        String wabaId = social().body("{\"signupCallback\":{\"accessToken\":\"meta-token-1\"}}")
                .post("/v1/whatsapp/signup").then().statusCode(200).extract()
                .path("signupCallbackResult.linkedAccountsWithIncompleteSetup.keySet()[0]");
        String phoneId = social().body("{\"signupCallback\":{\"accessToken\":\"meta-token-1\"}}")
                .post("/v1/whatsapp/signup").then().extract().path("signupCallbackResult."
                        + "linkedAccountsWithIncompleteSetup." + "'" + wabaId + "'"
                        + ".unregisteredWhatsAppPhoneNumbers[0].phoneNumberId");
        social().body("{\"setupFinalization\":{\"associateInProgressToken\":\"" + token + "\",\"phoneNumbers\":[{\"id\":"
                + "\"" + phoneId + "\",\"twoFactorPin\":\"12\"}]}}").post("/v1/whatsapp/signup").then()
                .statusCode(400);
        social().body("{\"setupFinalization\":{\"associateInProgressToken\":\"" + token + "\",\"phoneNumbers\":[{\"id\":"
                + "\"" + phoneId + "\",\"twoFactorPin\":\"123456\"}],\"waba\":{\"eventDestinations\":[{"
                + "\"eventDestinationArn\":\"" + topic + "\"}],\"tags\":[{\"key\":\"team\",\"value\":\"support\"}]}}}")
                .post("/v1/whatsapp/signup").then().statusCode(200)
                .body("linkedWhatsAppBusinessAccountId", equalTo(wabaId));
        social().body("{\"signupCallback\":{\"accessToken\":\"meta-token-1\"}}").post("/v1/whatsapp/signup").then()
                .statusCode(409);
        String arn = social().queryParam("id", wabaId).get("/v1/whatsapp/waba/details").then().statusCode(200)
                .body("account.registrationStatus", equalTo("COMPLETE")).extract().path("account.arn");
        social().queryParam("id", phoneId).get("/v1/whatsapp/waba/phone/details").then().statusCode(200)
                .body("linkedWhatsAppBusinessAccountId", equalTo(wabaId))
                .body("phoneNumber.phoneNumber", startsWith("+1206555"));

        // Templates are reviewed before they can be sent.
        social().body("{\"id\":\"" + wabaId + "\",\"templateDefinition\":\"" + b64("{\"name\":\"Bad Name\","
                + "\"category\":\"MARKETING\",\"components\":[]}") + "\"}").post("/v1/whatsapp/template/put").then()
                .statusCode(400);
        String template = social().body("{\"id\":\"" + wabaId + "\",\"templateDefinition\":\"" + b64("{\"name\":"
                + "\"spring_sale\",\"language\":\"en_US\",\"category\":\"MARKETING\",\"components\":[{\"type\":"
                + "\"BODY\",\"text\":\"20% off this week\"}]}") + "\"}").post("/v1/whatsapp/template/put").then()
                .statusCode(200).body("templateStatus", equalTo("PENDING")).extract().path("metaTemplateId");
        String templateMessage = b64("{\"messaging_product\":\"whatsapp\",\"to\":\"+12065550111\",\"type\":"
                + "\"template\",\"template\":{\"name\":\"spring_sale\",\"language\":{\"code\":\"en_US\"}}}");
        social().body("{\"originationPhoneNumberId\":\"" + phoneId + "\",\"metaApiVersion\":\"v20.0\",\"message\":\""
                + templateMessage + "\"}").post("/v1/whatsapp/send").then().statusCode(400)
                .body("message", containsString("isn't approved"));
        await().atMost(Duration.ofSeconds(10)).until(() -> "APPROVED".equals(social().queryParam("id", wabaId)
                .get("/v1/whatsapp/template/list").then().extract().path("templates[0].templateStatus")));
        social().queryParam("id", wabaId).queryParam("metaTemplateId", template).get("/v1/whatsapp/template")
                .then().statusCode(200).body("template", containsString("20% off"));
        String messageId = social().body("{\"originationPhoneNumberId\":\"" + phoneId + "\",\"metaApiVersion\":"
                + "\"v20.0\",\"message\":\"" + templateMessage + "\"}").post("/v1/whatsapp/send").then()
                .statusCode(200).extract().path("messageId");
        await().atMost(Duration.ofSeconds(10)).until(() -> query("sqs").formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl).post("/").then().extract().asString().contains(messageId));
        social().body("{\"originationPhoneNumberId\":\"phone-number-id-0123456789abcdef0123456789abcdef\","
                + "\"metaApiVersion\":\"v20.0\",\"message\":\"" + templateMessage + "\"}").post("/v1/whatsapp/send")
                .then().statusCode(404);

        // Library templates.
        social().queryParam("id", wabaId).body("{\"filters\":{\"templateCategory\":\"AUTHENTICATION\"}}")
                .post("/v1/whatsapp/template/library").then().statusCode(200)
                .body("metaLibraryTemplates", hasSize(1));
        social().body("{\"id\":\"" + wabaId + "\",\"metaLibraryTemplate\":{\"templateName\":\"login_code\","
                + "\"libraryTemplateName\":\"verify_code\",\"templateCategory\":\"AUTHENTICATION\","
                + "\"templateLanguage\":\"en_US\"}}").post("/v1/whatsapp/template/create").then().statusCode(200)
                .body("templateStatus", equalTo("APPROVED"));

        // Media goes through S3 both ways.
        given().header("Authorization", auth("s3")).put("/wa-media").then().statusCode(200);
        byte[] image = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        given().header("Authorization", auth("s3")).body(image).put("/wa-media/logo.png").then().statusCode(200);
        String mediaId = social().body("{\"originationPhoneNumberId\":\"" + phoneId + "\",\"sourceS3File\":"
                + "{\"bucketName\":\"wa-media\",\"key\":\"logo.png\"}}").post("/v1/whatsapp/media").then()
                .statusCode(200).extract().path("mediaId");
        social().body("{\"originationPhoneNumberId\":\"" + phoneId + "\",\"mediaId\":\"" + mediaId + "\","
                + "\"destinationS3File\":{\"bucketName\":\"wa-media\",\"key\":\"copy.png\"}}")
                .post("/v1/whatsapp/media/get").then().statusCode(200).body("mimeType", equalTo("image/png"))
                .body("fileSize", equalTo(image.length));
        assertArrayEquals(image, given().header("Authorization", auth("s3")).get("/wa-media/copy.png").then()
                .statusCode(200).extract().asByteArray());
        social().queryParam("mediaId", mediaId).queryParam("originationPhoneNumberId", phoneId)
                .delete("/v1/whatsapp/media").then().statusCode(200).body("success", equalTo(true));

        // Flows: a flow with errors can't be published; a published one can only be deprecated.
        String flow = social().body("{\"id\":\"" + wabaId + "\",\"flowName\":\"signup\",\"categories\":[\"SIGN_UP\"],"
                + "\"flowJson\":\"" + b64("{\"screens\":[]}") + "\"}").post("/v1/whatsapp/flow/create").then()
                .statusCode(200).body("validationErrors", hasSize(2)).extract().path("flowId");
        social().body("{\"id\":\"" + wabaId + "\",\"flowId\":\"" + flow + "\"}").post("/v1/whatsapp/flow/publish")
                .then().statusCode(400);
        social().body("{\"id\":\"" + wabaId + "\",\"flowId\":\"" + flow + "\",\"flowJson\":\"" + b64("{\"version\":"
                + "\"6.0\",\"screens\":[{\"id\":\"WELCOME\"}]}") + "\"}").post("/v1/whatsapp/flow/assets/update")
                .then().statusCode(200).body("validationErrors", hasSize(0));
        social().body("{\"id\":\"" + wabaId + "\",\"flowId\":\"" + flow + "\"}").post("/v1/whatsapp/flow/publish")
                .then().statusCode(200);
        social().queryParam("id", wabaId).queryParam("flowId", flow).get("/v1/whatsapp/flow").then()
                .body("flowStatus", equalTo("PUBLISHED")).body("jsonVersion", equalTo("6.0"));
        social().queryParam("id", wabaId).queryParam("flowId", flow).delete("/v1/whatsapp/flow").then()
                .statusCode(400);
        social().body("{\"id\":\"" + wabaId + "\",\"flowId\":\"" + flow + "\"}").post("/v1/whatsapp/flow/deprecate")
                .then().statusCode(200);

        // Business public keys are RSA keys of at least 2048 bits.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(generator.generateKeyPair()
                .getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
        social().body("{\"originationPhoneNumberId\":\"" + phoneId + "\",\"businessPublicKey\":\"nope\"}")
                .put("/v1/whatsapp/business-public-key").then().statusCode(400);
        social().body("{\"originationPhoneNumberId\":\"" + phoneId + "\",\"businessPublicKey\":\"" + pem.replace("\n",
                "\\n") + "\"}").put("/v1/whatsapp/business-public-key").then().statusCode(200);
        social().queryParam("originationPhoneNumberId", phoneId).get("/v1/whatsapp/business-public-key").then()
                .body("businessPublicKeySignatureStatus", equalTo("VALID"));

        // Tags and disassociation.
        social().queryParam("resourceArn", arn).get("/v1/tags/list").then()
                .body("tags.key", equalTo(List.of("team")));
        social().queryParam("id", wabaId).delete("/v1/whatsapp/waba/disassociate").then().statusCode(200);
        social().queryParam("id", wabaId).get("/v1/whatsapp/waba/details").then().statusCode(404);
    }
}
