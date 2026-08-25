package io.github.hectorvent.floci.services.b2bi;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.startsWith;

/**
 * StartTransformerJob / GetTransformerJob plus the Transformation Completed
 * EventBridge event that Alchemy's Bindings.test.ts observes via a Lambda
 * EventSource (here: an SQS rule target, same putEvents path).
 */
@QuarkusTest
class B2biTransformerJobIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/b2bi/aws4_request";
    private static final String EB_CT = "application/x-amz-json-1.1";
    private static final String SAMPLE_850 = String.join("\n",
            "ISA*00*          *00*          *ZZ*SENDERID       *ZZ*RECEIVERID     *210101*1253*U*00401*000000001*0*T*>~",
            "GS*PO*SENDERID*RECEIVERID*20210101*1253*1*X*004010~",
            "ST*850*0001~",
            "BEG*00*SA*XX-1234**20210101~",
            "SE*4*0001~",
            "GE*1*1~",
            "IEA*1*000000001~",
            "");

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void startTransformerJob_succeedsAndPublishesTransformationCompleted() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "floci-b2bi-job-" + suffix;
        String queueName = "floci-b2bi-job-events-" + suffix;
        String ruleName = "floci-b2bi-job-rule-" + suffix;
        String transformerName = "floci-b2bi-job-tr-" + suffix;
        String inputKey = "job-input/sample-850.edi";

        given().when().put("/" + bucket).then().statusCode(200);
        given().contentType("text/plain").body(SAMPLE_850)
                .when().put("/" + bucket + "/" + inputKey)
                .then().statusCode(200);

        String transformerId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.CreateTransformer")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "name": "%s",
                          "inputConversion": {
                            "fromFormat": "X12",
                            "formatOptions": {
                              "x12": { "transactionSet": "X12_850", "version": "VERSION_4010" }
                            }
                          },
                          "mapping": {
                            "templateLanguage": "JSONATA",
                            "template": "{ \\"orderId\\": \\"test\\" }"
                          }
                        }
                        """.formatted(transformerName))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("transformerId", startsWith("tr-"))
                .extract().path("transformerId");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.UpdateTransformer")
                .header("Authorization", AUTH)
                .body("{\"transformerId\":\"" + transformerId + "\",\"status\":\"active\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("status", equalTo("active"));

        String queueUrl = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", queueName)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

        String queueArn = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueAttributes")
                .formParam("QueueUrl", queueUrl)
                .formParam("AttributeName.1", "QueueArn")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().xmlPath().getString("**.find { it.Name == 'QueueArn' }.Value");

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body("""
                        {
                          "Name": "%s",
                          "EventPattern": "{\\"source\\":[\\"aws.b2bi\\"],\\"detail-type\\":[\\"Transformation Completed\\",\\"Transformation Failed\\"]}",
                          "State": "ENABLED"
                        }
                        """.formatted(ruleName))
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("""
                        {
                          "Rule": "%s",
                          "Targets": [{"Id": "1", "Arn": "%s"}]
                        }
                        """.formatted(ruleName, queueArn))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("FailedEntryCount", equalTo(0));

        String jobId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.StartTransformerJob")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "transformerId": "%s",
                          "inputFile": { "bucketName": "%s", "key": "%s" },
                          "outputLocation": { "bucketName": "%s", "key": "job-output/" }
                        }
                        """.formatted(transformerId, bucket, inputKey, bucket))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("transformerJobId", org.hamcrest.Matchers.notNullValue())
                .extract().path("transformerJobId");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.GetTransformerJob")
                .header("Authorization", AUTH)
                .body("{\"transformerId\":\"" + transformerId
                        + "\",\"transformerJobId\":\"" + jobId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("status", equalTo("succeeded"))
                .body("outputFiles.size()", greaterThan(0))
                .body("outputFiles[0].bucketName", equalTo(bucket))
                .body("outputFiles[0].key", containsString(jobId));

        String messageBody = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MaxNumberOfMessages", "1")
                .formParam("WaitTimeSeconds", "0")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().xmlPath()
                .getString("ReceiveMessageResponse.ReceiveMessageResult.Message.Body");

        org.junit.jupiter.api.Assertions.assertNotNull(messageBody,
                "Expected a Transformation Completed event on the target queue");
        org.junit.jupiter.api.Assertions.assertTrue(messageBody.contains("aws.b2bi"),
                "Expected source aws.b2bi in: " + messageBody);
        org.junit.jupiter.api.Assertions.assertTrue(
                messageBody.contains("Transformation Completed"),
                "Expected detail-type Transformation Completed in: " + messageBody);
        org.junit.jupiter.api.Assertions.assertTrue(messageBody.contains(jobId),
                "Expected transformer-job-id " + jobId + " in: " + messageBody);
        org.junit.jupiter.api.Assertions.assertTrue(messageBody.contains("transformer-job-id"),
                "Expected kebab-case transformer-job-id in: " + messageBody);
    }

    @Test
    void getTransformerJob_missing_returnsResourceNotFound() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.GetTransformerJob")
                .header("Authorization", AUTH)
                .body("{\"transformerId\":\"tr-missing\",\"transformerJobId\":\"missing-job\"}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void testMapping_jsonataObjectConstructor() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.TestMapping")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "inputFileContent": "{\\"customer\\":\\"acme\\"}",
                          "mappingTemplate": "{ \\"name\\": customer }",
                          "fileFormat": "JSON"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("mappedFileContent", containsString("acme"));
    }
}
