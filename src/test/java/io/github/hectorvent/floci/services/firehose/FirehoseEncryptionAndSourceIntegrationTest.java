package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirehoseEncryptionAndSourceIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "Firehose_20150804.";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/firehose-delivery-role";
    private static final String BUCKET_ARN = "arn:aws:s3:::firehose-sse-archive";
    private static final String KINESIS_ARN = "arn:aws:kinesis:us-east-1:000000000000:stream/clickstream";
    private static final String DIRECT_STREAM = "test-sse-direct-stream";
    private static final String CREATE_ENCRYPTED_STREAM = "test-sse-create-encrypted-stream";
    private static final String KINESIS_STREAM = "test-kinesis-source-stream";
    private static final String CMK_ARN = "arn:aws:kms:us-east-1:000000000000:key/11111111-1111-1111-1111-111111111111";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createDirectPutReportsDisabledEncryption() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "DeliveryStreamType": "DirectPut",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "%s"
                      }
                    }
                    """.formatted(DIRECT_STREAM, ROLE_ARN, BUCKET_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + DIRECT_STREAM + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.DeliveryStreamType", equalTo("DirectPut"))
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.Status", equalTo("DISABLED"))
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.KeyType", nullValue())
            .body("DeliveryStreamDescription.Source", nullValue());
    }

    @Test
    @Order(2)
    void startAndStopDeliveryStreamEncryption() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "StartDeliveryStreamEncryption")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "DeliveryStreamEncryptionConfigurationInput": { "KeyType": "AWS_OWNED_CMK" }
                    }
                    """.formatted(DIRECT_STREAM))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + DIRECT_STREAM + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.Status", equalTo("ENABLED"))
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.KeyType", equalTo("AWS_OWNED_CMK"))
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.KeyARN", nullValue());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "StopDeliveryStreamEncryption")
            .body("{ \"DeliveryStreamName\": \"" + DIRECT_STREAM + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + DIRECT_STREAM + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.Status", equalTo("DISABLED"))
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.KeyType", nullValue());
    }

    @Test
    @Order(3)
    void createWithEncryptionInputEnablesSse() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "%s"
                      },
                      "DeliveryStreamEncryptionConfigurationInput": { "KeyType": "AWS_OWNED_CMK" }
                    }
                    """.formatted(CREATE_ENCRYPTED_STREAM, ROLE_ARN, BUCKET_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + CREATE_ENCRYPTED_STREAM + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.Status", equalTo("ENABLED"))
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.KeyType", equalTo("AWS_OWNED_CMK"));
    }

    @Test
    @Order(4)
    void customerManagedCmkRequiresKeyArn() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "StartDeliveryStreamEncryption")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "DeliveryStreamEncryptionConfigurationInput": { "KeyType": "CUSTOMER_MANAGED_CMK" }
                    }
                    """.formatted(DIRECT_STREAM))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "StartDeliveryStreamEncryption")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "DeliveryStreamEncryptionConfigurationInput": {
                        "KeyType": "CUSTOMER_MANAGED_CMK",
                        "KeyARN": "%s"
                      }
                    }
                    """.formatted(DIRECT_STREAM, CMK_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + DIRECT_STREAM + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.Status", equalTo("ENABLED"))
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.KeyType", equalTo("CUSTOMER_MANAGED_CMK"))
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.KeyARN", equalTo(CMK_ARN));
    }

    @Test
    @Order(5)
    void createKinesisStreamAsSourcePersistsSourceDescription() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "DeliveryStreamType": "KinesisStreamAsSource",
                      "KinesisStreamSourceConfiguration": {
                        "KinesisStreamARN": "%s",
                        "RoleARN": "%s"
                      },
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "%s"
                      }
                    }
                    """.formatted(KINESIS_STREAM, KINESIS_ARN, ROLE_ARN, ROLE_ARN, BUCKET_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + KINESIS_STREAM + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.DeliveryStreamType", equalTo("KinesisStreamAsSource"))
            .body("DeliveryStreamDescription.Source.KinesisStreamSourceDescription.KinesisStreamARN", equalTo(KINESIS_ARN))
            .body("DeliveryStreamDescription.Source.KinesisStreamSourceDescription.RoleARN", equalTo(ROLE_ARN))
            .body("DeliveryStreamDescription.Source.KinesisStreamSourceDescription.DeliveryStartTimestamp", notNullValue())
            .body("DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration.Status", equalTo("DISABLED"));
    }

    @Test
    @Order(6)
    void kinesisSourceRejectsEncryption() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "StartDeliveryStreamEncryption")
            .body("""
                    {
                      "DeliveryStreamName": "%s",
                      "DeliveryStreamEncryptionConfigurationInput": { "KeyType": "AWS_OWNED_CMK" }
                    }
                    """.formatted(KINESIS_STREAM))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "test-kinesis-encrypted-rejected",
                      "DeliveryStreamType": "KinesisStreamAsSource",
                      "KinesisStreamSourceConfiguration": {
                        "KinesisStreamARN": "%s",
                        "RoleARN": "%s"
                      },
                      "DeliveryStreamEncryptionConfigurationInput": { "KeyType": "AWS_OWNED_CMK" },
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "%s"
                      }
                    }
                    """.formatted(KINESIS_ARN, ROLE_ARN, ROLE_ARN, BUCKET_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }

    @Test
    @Order(7)
    void kinesisSourceTypeRequiresSourceConfiguration() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
            .body("""
                    {
                      "DeliveryStreamName": "test-kinesis-missing-source",
                      "DeliveryStreamType": "KinesisStreamAsSource",
                      "ExtendedS3DestinationConfiguration": {
                        "RoleARN": "%s",
                        "BucketARN": "%s"
                      }
                    }
                    """.formatted(ROLE_ARN, BUCKET_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }

    @Test
    @Order(8)
    void listDeliveryStreamsPaginatesAndFiltersByType() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "ListDeliveryStreams")
            .body("{ \"Limit\": 1 }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamNames.size()", equalTo(1))
            .body("HasMoreDeliveryStreams", equalTo(true));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "ListDeliveryStreams")
            .body("{ \"DeliveryStreamType\": \"KinesisStreamAsSource\", \"Limit\": 100 }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamNames", org.hamcrest.Matchers.hasItem(KINESIS_STREAM))
            .body("HasMoreDeliveryStreams", equalTo(false));
    }

    @Test
    @Order(9)
    void startEncryptionOnMissingStreamIsNotFound() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "StartDeliveryStreamEncryption")
            .body("""
                    {
                      "DeliveryStreamName": "does-not-exist",
                      "DeliveryStreamEncryptionConfigurationInput": { "KeyType": "AWS_OWNED_CMK" }
                    }
                    """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
