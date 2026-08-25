package io.github.hectorvent.floci.services.cloudtrail;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * CloudTrail Lake: event data stores plus StartQuery / DescribeQuery /
 * GetQueryResults / ListQueries / CancelQuery / GenerateQuery.
 */
@QuarkusTest
class CloudTrailLakeIntegrationTest {

    private static final String CT_TARGET = "CloudTrail_20131101.";
    private static final String JSON11 = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void lakeQueryLifecycle_startDescribeResultsListCancelGenerate() {
        String name = "lake-" + UUID.randomUUID().toString().substring(0, 8);

        String arn = invoke("CreateEventDataStore", """
                {
                  "Name": "%s",
                  "MultiRegionEnabled": false,
                  "RetentionPeriod": 7,
                  "TerminationProtectionEnabled": false,
                  "TagsList": [{"Key": "env", "Value": "test"}]
                }
                """.formatted(name))
            .then()
                .statusCode(200)
                .body("EventDataStoreArn", containsString("eventdatastore/"))
                .body("Name", equalTo(name))
                .body("Status", equalTo("ENABLED"))
                .body("RetentionPeriod", equalTo(7))
            .extract().path("EventDataStoreArn");

        String storeId = arn.substring(arn.lastIndexOf('/') + 1);

        invoke("GetEventDataStore", "{\"EventDataStore\":\"%s\"}".formatted(arn))
            .then()
                .statusCode(200)
                .body("EventDataStoreArn", equalTo(arn))
                .body("Name", equalTo(name));

        invoke("ListTags", "{\"ResourceIdList\":[\"%s\"]}".formatted(arn))
            .then()
                .statusCode(200)
                .body("ResourceTagList[0].ResourceId", equalTo(arn))
                .body("ResourceTagList[0].TagsList[0].Key", equalTo("env"));

        String queryId = invoke("StartQuery",
                "{\"QueryStatement\":\"SELECT eventID FROM %s LIMIT 1\"}".formatted(storeId))
            .then()
                .statusCode(200)
                .body("QueryId", notNullValue())
            .extract().path("QueryId");

        invoke("DescribeQuery", "{\"QueryId\":\"%s\"}".formatted(queryId))
            .then()
                .statusCode(200)
                .body("QueryStatus", equalTo("FINISHED"))
                .body("QueryId", equalTo(queryId));

        invoke("GetQueryResults", "{\"QueryId\":\"%s\"}".formatted(queryId))
            .then()
                .statusCode(200)
                .body("QueryStatus", equalTo("FINISHED"))
                .body("QueryResultRows.size()", greaterThanOrEqualTo(0));

        invoke("ListQueries", "{\"EventDataStore\":\"%s\",\"MaxResults\":10}".formatted(arn))
            .then()
                .statusCode(200)
                .body("Queries[0].QueryId", equalTo(queryId));

        invoke("CancelQuery", "{\"QueryId\":\"%s\"}".formatted(queryId))
            .then()
                .statusCode(400)
                .body(containsString("InactiveQueryException"));

        invoke("GenerateQuery", """
                {
                  "EventDataStores": ["%s"],
                  "Prompt": "How many events were recorded in the last day?"
                }
                """.formatted(arn))
            .then()
                .statusCode(200)
                .body("QueryStatement", containsString(storeId));

        invoke("DeleteEventDataStore", "{\"EventDataStore\":\"%s\"}".formatted(arn))
            .then()
                .statusCode(200);
    }

    @Test
    void getEventDataStore_unknown_isNotFound() {
        invoke("GetEventDataStore",
                "{\"EventDataStore\":\"00000000-0000-0000-0000-000000000000\"}")
            .then()
                .statusCode(400)
                .body(containsString("EventDataStoreNotFoundException"));
    }

    @Test
    void startQuery_missingStore_isNotFound() {
        invoke("StartQuery",
                "{\"QueryStatement\":\"SELECT eventID FROM 11111111-1111-1111-1111-111111111111 LIMIT 1\"}")
            .then()
                .statusCode(400)
                .body(containsString("EventDataStoreNotFoundException"));
    }

    private static io.restassured.response.Response invoke(String action, String body) {
        return given()
            .header("X-Amz-Target", CT_TARGET + action)
            .contentType(JSON11)
            .body(body)
        .when().post("/");
    }
}
