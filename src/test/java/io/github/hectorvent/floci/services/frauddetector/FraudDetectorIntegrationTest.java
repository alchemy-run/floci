package io.github.hectorvent.floci.services.frauddetector;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * JSON 1.1 Fraud Detector coverage used by Alchemy:
 * {@code GetDetectors}, {@code GetEvent}, and {@code UpdateList} typed
 * {@code ResourceNotFoundException} for missing identifiers, plus create
 * round-trips for detectors and lists.
 */
@QuarkusTest
class FraudDetectorIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/frauddetector/aws4_request";
    private static final String TARGET = "AWSHawksNestServiceFacade.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getDetectors_missingId_returnsResourceNotFound() {
        fraud("GetDetectors", "{\"detectorId\":\"does_not_exist_detector\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getEvent_missingEventType_returnsResourceNotFound() {
        fraud("GetEvent", "{\"eventId\":\"does-not-exist\",\"eventTypeName\":\"does_not_exist_event_type\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void updateList_missingName_returnsResourceNotFound() {
        fraud("UpdateList",
                "{\"name\":\"does_not_exist_list\",\"elements\":[\"203.0.113.1\"],\"updateMode\":\"REPLACE\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void putDetector_thenGetDetectors() {
        fraud("DeleteDetector", "{\"detectorId\":\"checkout-test\"}");

        fraud("PutDetector", "{\"detectorId\":\"checkout-test\",\"eventTypeName\":\"purchase\",\"description\":\"d\"}")
                .then()
                .statusCode(200);

        fraud("GetDetectors", "{\"detectorId\":\"checkout-test\"}")
                .then()
                .statusCode(200)
                .body("detectors[0].detectorId", equalTo("checkout-test"))
                .body("detectors[0].eventTypeName", equalTo("purchase"));

        fraud("DeleteDetector", "{\"detectorId\":\"checkout-test\"}")
                .then()
                .statusCode(200);

        fraud("GetDetectors", "{\"detectorId\":\"checkout-test\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createList_updateList_getListElements() {
        fraud("DeleteList", "{\"name\":\"blocked-ips\"}");

        fraud("CreateList",
                "{\"name\":\"blocked-ips\",\"variableType\":\"IP_ADDRESS\",\"elements\":[\"203.0.113.7\"]}")
                .then()
                .statusCode(200);

        fraud("GetListsMetadata", "{\"name\":\"blocked-ips\"}")
                .then()
                .statusCode(200)
                .body("lists[0].name", equalTo("blocked-ips"));

        fraud("UpdateList",
                "{\"name\":\"blocked-ips\",\"elements\":[\"198.51.100.77\"],\"updateMode\":\"APPEND\"}")
                .then()
                .statusCode(200);

        fraud("GetListElements", "{\"name\":\"blocked-ips\"}")
                .then()
                .statusCode(200)
                .body("elements", hasItem("203.0.113.7"))
                .body("elements", hasItem("198.51.100.77"));

        fraud("DeleteList", "{\"name\":\"blocked-ips\"}")
                .then()
                .statusCode(200);
    }

    private static Response fraud(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
