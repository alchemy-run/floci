package io.github.hectorvent.floci.services.iotfleetwise;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.0 IoT FleetWise coverage used by Alchemy IoTFleetWise.test.ts:
 * GetSignalCatalog / GetStateTemplate on a missing name return
 * ResourceNotFoundException; GetVehicleStatus / ListVehiclesInFleet round-trip.
 */
@QuarkusTest
class IotFleetWiseIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String TARGET = "IoTAutobahnControlPlane.";
    private static final String EAST = "us-east-1";
    private static final String MODEL_ARN =
            "arn:aws:iotfleetwise:us-east-1:000000000401:model-manifest/bindings-model";
    private static final String DECODER_ARN =
            "arn:aws:iotfleetwise:us-east-1:000000000401:decoder-manifest/bindings-decoder";
    private static final String CATALOG_ARN =
            "arn:aws:iotfleetwise:us-east-1:000000000401:signal-catalog/bindings-catalog";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getSignalCatalog_missing_returnsResourceNotFoundException() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetSignalCatalog")
                .header("Authorization", auth("000000000401", EAST))
                .body("{\"name\":\"alchemy-nonexistent-catalog-probe\"}")
                .when()
                .post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo("alchemy-nonexistent-catalog-probe"))
                .body("resourceType", equalTo("signalCatalog"));
    }

    @Test
    void getStateTemplate_missing_returnsResourceNotFoundException() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetStateTemplate")
                .header("Authorization", auth("000000000401", EAST))
                .body("{\"identifier\":\"alchemy-nonexistent-state-template-probe\"}")
                .when()
                .post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo("alchemy-nonexistent-state-template-probe"))
                .body("resourceType", equalTo("stateTemplate"));
    }

    @Test
    void getVehicleStatusOnANonexistentVehicleFailsWithResourceNotFoundException() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetVehicleStatus")
                .header("Authorization", auth("000000000401", EAST))
                .body("{\"vehicleName\":\"alchemy-nonexistent-vehicle-probe\"}")
                .when()
                .post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo("alchemy-nonexistent-vehicle-probe"))
                .body("resourceType", equalTo("vehicle"));
    }

    @Test
    void listVehiclesInFleetOnANonexistentFleetFailsWithResourceNotFoundException() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListVehiclesInFleet")
                .header("Authorization", auth("000000000402", EAST))
                .body("{\"fleetId\":\"alchemy-nonexistent-fleet-probe\"}")
                .when()
                .post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo("alchemy-nonexistent-fleet-probe"))
                .body("resourceType", equalTo("fleet"));
    }

    @Test
    void getVehicleStatusAndListVehiclesInFleetRoundTripThroughAssociate() {
        String authorization = auth("000000000403", EAST);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateVehicle")
                .header("Authorization", authorization)
                .body("""
                        {
                          "vehicleName":"bindings-vehicle",
                          "modelManifestArn":"%s",
                          "decoderManifestArn":"%s",
                          "attributes":{"Vehicle.VIN":"1HGBH41JXMN109186"}
                        }
                        """.formatted(MODEL_ARN, DECODER_ARN))
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("vehicleName", equalTo("bindings-vehicle"))
                .body("arn", startsWith("arn:aws:iotfleetwise:" + EAST + ":"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetVehicleStatus")
                .header("Authorization", authorization)
                .body("{\"vehicleName\":\"bindings-vehicle\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("campaigns", hasSize(0));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateFleet")
                .header("Authorization", authorization)
                .body("""
                        {"fleetId":"bindings-fleet","signalCatalogArn":"%s"}
                        """.formatted(CATALOG_ARN))
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("id", equalTo("bindings-fleet"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListVehiclesInFleet")
                .header("Authorization", authorization)
                .body("{\"fleetId\":\"bindings-fleet\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("vehicles", hasSize(0));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "AssociateVehicleFleet")
                .header("Authorization", authorization)
                .body("{\"vehicleName\":\"bindings-vehicle\",\"fleetId\":\"bindings-fleet\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListVehiclesInFleet")
                .header("Authorization", authorization)
                .body("{\"fleetId\":\"bindings-fleet\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("vehicles", hasSize(1))
                .body("vehicles[0]", equalTo("bindings-vehicle"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/iotfleetwise/aws4_request";
    }
}
