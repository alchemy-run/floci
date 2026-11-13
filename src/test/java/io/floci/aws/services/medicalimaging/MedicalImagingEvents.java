package io.floci.aws.services.medicalimaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

/** A queue an EventBridge rule sends HealthImaging's events to, and the detail types it received. */
final class MedicalImagingEvents {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MedicalImagingEvents() {
    }

    private static String auth(String service) {
        return MedicalImagingIntegrationTest.auth(service);
    }

    static String queue(String name) {
        String url = given().header("Authorization", auth("sqs")).formParam("Action", "CreateQueue")
                .formParam("QueueName", name).post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
        String arn = given().header("Authorization", auth("sqs")).formParam("Action", "GetQueueAttributes")
                .formParam("QueueUrl", url).formParam("AttributeName.1", "QueueArn").post("/").then().statusCode(200)
                .extract().xmlPath().getString("**.find { it.Name == 'QueueArn' }.Value");
        events("PutRule", "{\"Name\":\"" + name + "\",\"EventPattern\":\"{\\\"source\\\":[\\\"aws.medical-imaging\\\"]}\"}");
        events("PutTargets", "{\"Rule\":\"" + name + "\",\"Targets\":[{\"Id\":\"1\",\"Arn\":\"" + arn + "\"}]}");
        return url;
    }

    private static void events(String action, String body) {
        given().header("Authorization", auth("events")).header("X-Amz-Target", "AWSEvents." + action)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then().statusCode(200);
    }

    /** The detail types of the events the queue has, once they include the expected ones. */
    static List<String> detailTypes(String url, List<String> expected) {
        List<String> found = new ArrayList<>();
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            List<String> bodies = given().header("Authorization", auth("sqs")).formParam("Action", "ReceiveMessage")
                    .formParam("QueueUrl", url).formParam("MaxNumberOfMessages", "10").post("/").then()
                    .statusCode(200).extract().xmlPath()
                    .getList("ReceiveMessageResponse.ReceiveMessageResult.Message.Body", String.class);
            bodies.forEach(body -> add(found, body));
            return found.containsAll(expected);
        });
        return found;
    }

    private static void add(List<String> found, String body) {
        try {
            JsonNode event = MAPPER.readTree(body);
            found.add(event.path("detail-type").asText());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
