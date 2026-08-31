package io.github.hectorvent.floci.services.healthlake;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * FHIR data-store CRUD matching Alchemy's HealthLake FHIRDatastore resource:
 * describe-not-found, create ACTIVE R4, list by name, in-place tags, delete.
 */
@QuarkusTest
class HealthLakeDatastoreIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/healthlake/aws4_request";
    private static final String TARGET = "HealthLake.";
    private static final String MISSING = "0123456789abcdef0123456789abcdef";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeFHIRDatastore_missing_returnsResourceNotFoundException() {
        healthlake("DescribeFHIRDatastore", "{\"DatastoreId\":\"" + MISSING + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void datastoreLifecycleCreateListTagUpdateDelete() {
        String name = "floci-hl-ds-" + UUID.randomUUID().toString().substring(0, 8);

        String datastoreId = healthlake("CreateFHIRDatastore", """
                {
                  "DatastoreName": "%s",
                  "DatastoreTypeVersion": "R4",
                  "Tags": [{"Key": "fixture", "Value": "healthlake-datastore"}]
                }
                """.formatted(name))
                .then()
                .statusCode(200)
                .body("DatastoreId", notNullValue())
                .body("DatastoreStatus", equalTo("ACTIVE"))
                .body("DatastoreArn", startsWith("arn:aws:healthlake:"))
                .body("DatastoreEndpoint", startsWith("https://healthlake."))
                .extract().path("DatastoreId");

        String arn = healthlake("DescribeFHIRDatastore", "{\"DatastoreId\":\"" + datastoreId + "\"}")
                .then()
                .statusCode(200)
                .body("DatastoreProperties.DatastoreId", equalTo(datastoreId))
                .body("DatastoreProperties.DatastoreName", equalTo(name))
                .body("DatastoreProperties.DatastoreStatus", equalTo("ACTIVE"))
                .body("DatastoreProperties.DatastoreTypeVersion", equalTo("R4"))
                .extract().path("DatastoreProperties.DatastoreArn");

        healthlake("ListFHIRDatastores", "{\"Filter\":{\"DatastoreName\":\"" + name + "\"}}")
                .then()
                .statusCode(200)
                .body("DatastorePropertiesList.DatastoreId", hasItem(datastoreId));

        healthlake("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("fixture"));

        healthlake("TagResource", """
                {"ResourceARN":"%s","Tags":[{"Key":"updated","Value":"true"}]}
                """.formatted(arn))
                .then()
                .statusCode(200);

        healthlake("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("fixture"))
                .body("Tags.Key", hasItem("updated"));

        healthlake("UpdateFHIRDatastore", """
                {"DatastoreId":"%s","DatastoreName":"%s-renamed"}
                """.formatted(datastoreId, name))
                .then()
                .statusCode(200)
                .body("DatastoreProperties.DatastoreId", equalTo(datastoreId))
                .body("DatastoreProperties.DatastoreName", equalTo(name + "-renamed"));

        healthlake("DeleteFHIRDatastore", "{\"DatastoreId\":\"" + datastoreId + "\"}")
                .then()
                .statusCode(200)
                .body("DatastoreId", equalTo(datastoreId))
                .body("DatastoreStatus", equalTo("DELETING"));

        healthlake("DescribeFHIRDatastore", "{\"DatastoreId\":\"" + datastoreId + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        healthlake("ListFHIRDatastores", "{\"Filter\":{\"DatastoreName\":\"" + name + "-renamed\"}}")
                .then()
                .statusCode(200)
                .body("DatastorePropertiesList.DatastoreId", not(hasItem(datastoreId)));
    }

    private static Response healthlake(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
