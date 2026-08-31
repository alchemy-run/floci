package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * JSON 1.0 metric-stream coverage matching Alchemy {@code MetricStream.test.ts}:
 * PutMetricStream, GetMetricStream, ListMetricStreams, TagResource, StartMetricStreams,
 * DeleteMetricStream, and ResourceNotFoundException after delete.
 */
@QuarkusTest
class CloudWatchMetricStreamIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TARGET = "GraniteServiceVersion20100801.";
    private static final String REGION = "us-east-1";
    private static final String LIST_ACCOUNT = "000000000851";
    private static final String CRUD_ACCOUNT = "000000000852";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listMetricStreamsReturnsEmptyEntriesArray() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListMetricStreams")
                .header("Authorization", auth(LIST_ACCOUNT))
                .body("{}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Entries.size()", equalTo(0));
    }

    @Test
    void putGetListTagStartDeleteMetricStream() {
        String name = "alchemy-test-metricstream-list";
        String firehoseArn = "arn:aws:firehose:" + REGION + ":" + CRUD_ACCOUNT + ":deliverystream/ms-dest";
        String roleArn = "arn:aws:iam::" + CRUD_ACCOUNT + ":role/ms-role";
        String authorization = auth(CRUD_ACCOUNT);

        String arn = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "PutMetricStream")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name": "%s",
                          "FirehoseArn": "%s",
                          "RoleArn": "%s",
                          "OutputFormat": "json"
                        }
                        """.formatted(name, firehoseArn, roleArn))
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Arn", containsString(":metric-stream/" + name))
                .extract().path("Arn");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetMetricStream")
                .header("Authorization", authorization)
                .body("{\"Name\":\"" + name + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name))
                .body("Arn", equalTo(arn))
                .body("FirehoseArn", equalTo(firehoseArn))
                .body("RoleArn", equalTo(roleArn))
                .body("OutputFormat", equalTo("json"))
                .body("State", equalTo("running"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "TagResource")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ResourceARN": "%s",
                          "Tags": [{"Key":"alchemy:app","Value":"test"}]
                        }
                        """.formatted(arn))
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListTagsForResource")
                .header("Authorization", authorization)
                .body("{\"ResourceARN\":\"" + arn + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("alchemy:app"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListMetricStreams")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Entries.Name", hasItem(name))
                .body("Entries.Arn", hasItem(arn));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "StartMetricStreams")
                .header("Authorization", authorization)
                .body("{\"Names\":[\"" + name + "\"]}")
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DeleteMetricStream")
                .header("Authorization", authorization)
                .body("{\"Name\":\"" + name + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetMetricStream")
                .header("Authorization", authorization)
                .body("{\"Name\":\"" + name + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String account) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260101/" + REGION + "/monitoring/aws4_request";
    }
}
