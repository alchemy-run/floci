package io.github.hectorvent.floci.services.keyspacesstreams;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.0 Keyspaces Streams coverage used by Alchemy TableStreams.test.ts:
 * GetStream on a missing ARN is ResourceNotFoundException; ListStreams on an
 * unknown table returns an empty list; an enabled CDC stream round-trips
 * GetStream / GetShardIterator / GetRecords.
 */
@QuarkusTest
class KeyspacesStreamsIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/cassandra/aws4_request";
    private static final String TARGET_PREFIX = "KeyspacesStreams.";
    private static final String MISSING_ARN =
            "arn:aws:cassandra:us-east-1:000000000000:/keyspace/alchemy_nonexistent_ks/table/nonexistent_tbl/stream/2024-01-01T00:00:00.000";

    @Inject
    KeyspacesStreamsService service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void reset() {
        service.clear();
    }

    @Test
    void getStream_missingStream_returnsResourceNotFoundException() {
        streams("GetStream", "{\"streamArn\":\"" + MISSING_ARN + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listStreams_unknownTable_returnsEmptyList() {
        streams("ListStreams",
                "{\"keyspaceName\":\"alchemy_nonexistent_ks\",\"tableName\":\"nonexistent_tbl\"}")
                .then()
                .statusCode(200)
                .body("streams.size()", equalTo(0));
    }

    @Test
    void enabledStream_roundTripsGetStreamIteratorAndRecords() {
        var stream = service.enableStream("alchemy_streams_test_ks", "orders",
                "NEW_AND_OLD_IMAGES", "us-east-1", "000000000000");

        streams("ListStreams",
                "{\"keyspaceName\":\"alchemy_streams_test_ks\",\"tableName\":\"orders\"}")
                .then()
                .statusCode(200)
                .body("streams.size()", greaterThanOrEqualTo(1))
                .body("streams[0].streamArn", equalTo(stream.getStreamArn()));

        String shardId = streams("GetStream", "{\"streamArn\":\"" + stream.getStreamArn() + "\"}")
                .then()
                .statusCode(200)
                .body("streamArn", equalTo(stream.getStreamArn()))
                .body("streamStatus", equalTo("ENABLED"))
                .body("streamViewType", equalTo("NEW_AND_OLD_IMAGES"))
                .body("keyspaceName", equalTo("alchemy_streams_test_ks"))
                .body("tableName", equalTo("orders"))
                .body("shards.size()", greaterThanOrEqualTo(1))
                .extract().path("shards[0].shardId");

        String iterator = streams("GetShardIterator",
                "{\"streamArn\":\"" + stream.getStreamArn() + "\","
                        + "\"shardId\":\"" + shardId + "\","
                        + "\"shardIteratorType\":\"TRIM_HORIZON\"}")
                .then()
                .statusCode(200)
                .body("shardIterator", notNullValue())
                .extract().path("shardIterator");

        streams("GetRecords", "{\"shardIterator\":\"" + iterator + "\"}")
                .then()
                .statusCode(200)
                .body("changeRecords.size()", equalTo(0))
                .body("nextShardIterator", notNullValue());
    }

    private static Response streams(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
