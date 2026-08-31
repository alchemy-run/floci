package io.github.hectorvent.floci.services.smsvoice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.0 Pinpoint SMS Voice V2 coverage used by Alchemy
 * OptOutList.test.ts: describe on a missing name is
 * ResourceNotFoundException; create / tags / delete round-trip.
 */
@QuarkusTest
class SmsVoiceOptOutListIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sms-voice/aws4_request";
    private static final String TARGET_PREFIX = "PinpointSMSVoiceV2.";
    private static final String MISSING = "alchemy-nonexistent-opt-out-list-probe";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeOptOutLists_missing_returnsResourceNotFoundException() {
        smsvoice("DescribeOptOutLists",
                "{\"OptOutListNames\":[\"" + MISSING + "\"]}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void optOutList_lifecycle_and_tags() {
        String name = "floci-smsvoice-ool-" + UUID.randomUUID().toString().substring(0, 8);

        String arn = smsvoice("CreateOptOutList", """
                {
                  "OptOutListName": "%s",
                  "Tags": [{"Key": "fixture", "Value": "smsvoice-opt-out-list"}]
                }
                """.formatted(name))
                .then()
                .statusCode(200)
                .body("OptOutListName", equalTo(name))
                .body("OptOutListArn", containsString(":opt-out-list/"))
                .body("CreatedTimestamp", notNullValue())
                .extract().path("OptOutListArn");

        smsvoice("DescribeOptOutLists",
                "{\"OptOutListNames\":[\"" + name + "\"]}")
                .then()
                .statusCode(200)
                .body("OptOutLists[0].OptOutListName", equalTo(name))
                .body("OptOutLists[0].OptOutListArn", equalTo(arn));

        smsvoice("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("fixture"))
                .body("Tags.Value", hasItem("smsvoice-opt-out-list"));

        smsvoice("TagResource", """
                {
                  "ResourceArn": "%s",
                  "Tags": [
                    {"Key": "fixture", "Value": "smsvoice-opt-out-list-v2"},
                    {"Key": "extra", "Value": "1"}
                  ]
                }
                """.formatted(arn))
                .then()
                .statusCode(200);

        smsvoice("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("fixture"))
                .body("Tags.Value", hasItem("smsvoice-opt-out-list-v2"))
                .body("Tags.Key", hasItem("extra"));

        smsvoice("DeleteOptOutList",
                "{\"OptOutListName\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("OptOutListName", equalTo(name));

        smsvoice("DescribeOptOutLists",
                "{\"OptOutListNames\":[\"" + name + "\"]}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createOptOutList_duplicateName_returnsConflictException() {
        String name = "floci-smsvoice-ool-dup-" + UUID.randomUUID().toString().substring(0, 8);
        smsvoice("CreateOptOutList", "{\"OptOutListName\":\"" + name + "\"}")
                .then()
                .statusCode(200);
        smsvoice("CreateOptOutList", "{\"OptOutListName\":\"" + name + "\"}")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void untagResource_removesKey() {
        String name = "floci-smsvoice-ool-untag-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = smsvoice("CreateOptOutList", """
                {
                  "OptOutListName": "%s",
                  "Tags": [
                    {"Key": "keep", "Value": "yes"},
                    {"Key": "drop", "Value": "no"}
                  ]
                }
                """.formatted(name))
                .then()
                .statusCode(200)
                .extract().path("OptOutListArn");

        smsvoice("UntagResource", """
                {"ResourceArn": "%s", "TagKeys": ["drop"]}
                """.formatted(arn))
                .then()
                .statusCode(200);

        smsvoice("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("keep"))
                .body("Tags.Key", not(hasItem("drop")));
    }

    private static Response smsvoice(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
