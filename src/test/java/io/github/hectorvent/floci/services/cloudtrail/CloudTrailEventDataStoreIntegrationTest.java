package io.github.hectorvent.floci.services.cloudtrail;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class CloudTrailEventDataStoreIntegrationTest {

    private static final String CT_TARGET = "CloudTrail_20131101.";
    private static final String JSON11 = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getEventDataStoreUnknownArn_rejectsWithEventDataStoreNotFoundException() {
        invoke("GetEventDataStore", """
                {"EventDataStore":"arn:aws:cloudtrail:us-east-1:000000000000:eventdatastore/00000000-0000-0000-0000-000000000000"}
                """)
            .then()
                .statusCode(400)
                .body(containsString("EventDataStoreNotFoundException"));
    }

    @Test
    void createListGetUpdateStopDeleteEventDataStore() {
        String name = "eds-" + UUID.randomUUID().toString().substring(0, 8);

        String created = invoke("CreateEventDataStore", String.format("""
                {
                  "Name": "%s",
                  "MultiRegionEnabled": false,
                  "RetentionPeriod": 7,
                  "TerminationProtectionEnabled": false,
                  "TagsList": [{"Key":"fixture","Value":"cloudtrail-eds"}]
                }
                """, name))
            .then()
                .statusCode(200)
                .body("Name", equalTo(name))
                .body("RetentionPeriod", equalTo(7))
                .body("TerminationProtectionEnabled", equalTo(false))
                .body("MultiRegionEnabled", equalTo(false))
                .extract().asString();

        String arn = created.replaceAll(".*\"EventDataStoreArn\":\"([^\"]+)\".*", "$1");

        invoke("GetEventDataStore", String.format("{\"EventDataStore\":\"%s\"}", arn))
            .then()
                .statusCode(200)
                .body("Name", equalTo(name))
                .body("RetentionPeriod", equalTo(7))
                .body("EventDataStoreArn", equalTo(arn));

        invoke("ListEventDataStores", "{}")
            .then()
                .statusCode(200)
                .body(containsString(name))
                .body(containsString(arn));

        invoke("UpdateEventDataStore", String.format("""
                {"EventDataStore":"%s","RetentionPeriod":14}
                """, arn))
            .then()
                .statusCode(200)
                .body("RetentionPeriod", equalTo(14))
                .body("EventDataStoreArn", equalTo(arn));

        invoke("AddTags", String.format("""
                {"ResourceId":"%s","TagsList":[{"Key":"team","Value":"audit"}]}
                """, arn))
            .then().statusCode(200);

        invoke("RemoveTags", String.format("""
                {"ResourceId":"%s","TagsList":[{"Key":"fixture"}]}
                """, arn))
            .then().statusCode(200);

        invoke("ListTags", String.format("{\"ResourceIdList\":[\"%s\"]}", arn))
            .then()
                .statusCode(200)
                .body("ResourceTagList[0].ResourceId", equalTo(arn))
                .body("ResourceTagList[0].TagsList.Key", hasItem("team"))
                .body("ResourceTagList[0].TagsList.Key", not(hasItem("fixture")));

        invoke("StopEventDataStoreIngestion", String.format("{\"EventDataStore\":\"%s\"}", arn))
            .then().statusCode(200);

        invoke("GetEventDataStore", String.format("{\"EventDataStore\":\"%s\"}", arn))
            .then()
                .statusCode(200)
                .body("Status", equalTo("STOPPED_INGESTION"));

        invoke("DeleteEventDataStore", String.format("{\"EventDataStore\":\"%s\"}", arn))
            .then().statusCode(200);

        invoke("GetEventDataStore", String.format("{\"EventDataStore\":\"%s\"}", arn))
            .then()
                .statusCode(200)
                .body("Status", equalTo("PENDING_DELETION"));

        invoke("RestoreEventDataStore", String.format("{\"EventDataStore\":\"%s\"}", arn))
            .then()
                .statusCode(200)
                .body("Status", equalTo("ENABLED"));

        invoke("DeleteEventDataStore", String.format("{\"EventDataStore\":\"%s\"}", arn))
            .then().statusCode(200);
    }

    @Test
    void createEventDataStoreDuplicateName_rejectsWithAlreadyExists() {
        String name = "eds-dup-" + UUID.randomUUID().toString().substring(0, 8);
        invoke("CreateEventDataStore", String.format("""
                {"Name":"%s","RetentionPeriod":7,"TerminationProtectionEnabled":false}
                """, name))
            .then().statusCode(200);

        invoke("CreateEventDataStore", String.format("""
                {"Name":"%s","RetentionPeriod":7,"TerminationProtectionEnabled":false}
                """, name))
            .then()
                .statusCode(400)
                .body(containsString("EventDataStoreAlreadyExistsException"));
    }

    private static io.restassured.response.Response invoke(String action, String body) {
        return given()
            .header("X-Amz-Target", CT_TARGET + action)
            .contentType(JSON11)
            .body(body)
        .when().post("/");
    }
}
