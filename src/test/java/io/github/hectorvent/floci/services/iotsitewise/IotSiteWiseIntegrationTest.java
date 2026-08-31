package io.github.hectorvent.floci.services.iotsitewise;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * restJson1 IoT SiteWise coverage used by Alchemy IoTSiteWise.test.ts:
 * DescribeAssetModel on a missing id returns ResourceNotFoundException;
 * asset model + asset + gateway create/update/tag/delete round-trip.
 */
@QuarkusTest
class IotSiteWiseIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String MISSING = "12345678-1234-4123-8123-123456789012";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeAssetModel_missing_returnsResourceNotFoundException() {
        given()
                .header("Authorization", auth())
                .when()
                .get("/asset-models/" + MISSING)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void assetModelAndAsset_createUpdateTagDelete_roundTrip() {
        String authorization = auth();
        String modelId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(modelBody("pump-model", "pump model v1"))
                .when()
                .post("/asset-models")
                .then()
                .statusCode(200)
                .body("assetModelId", notNullValue())
                .body("assetModelArn", notNullValue())
                .body("assetModelStatus.state", equalTo("ACTIVE"))
                .extract()
                .path("assetModelId");

        String modelArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/asset-models/" + modelId)
                .then()
                .statusCode(200)
                .body("assetModelDescription", equalTo("pump model v1"))
                .body("assetModelStatus.state", equalTo("ACTIVE"))
                .body("assetModelProperties.name", hasItems("SerialNumber", "Temperature"))
                .extract()
                .path("assetModelArn");

        given()
                .header("Authorization", authorization)
                .queryParam("resourceArn", modelArn)
                .when()
                .get("/tags")
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("iotsitewise"));

        String assetId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"assetName\":\"pump-1\",\"assetModelId\":\"" + modelId
                        + "\",\"assetDescription\":\"pump model v1\",\"tags\":{\"fixture\":\"iotsitewise\"}}")
                .when()
                .post("/assets")
                .then()
                .statusCode(200)
                .body("assetId", notNullValue())
                .body("assetArn", notNullValue())
                .body("assetStatus.state", equalTo("ACTIVE"))
                .extract()
                .path("assetId");

        given()
                .header("Authorization", authorization)
                .queryParam("excludeProperties", true)
                .when()
                .get("/assets/" + assetId)
                .then()
                .statusCode(200)
                .body("assetDescription", equalTo("pump model v1"))
                .body("assetModelId", equalTo(modelId))
                .body("assetStatus.state", equalTo("ACTIVE"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"assetModelName\":\"pump-model\",\"assetModelDescription\":\"pump model v2\","
                        + "\"assetModelProperties\":["
                        + "{\"name\":\"SerialNumber\",\"dataType\":\"STRING\","
                        + "\"type\":{\"attribute\":{\"defaultValue\":\"unknown\"}}},"
                        + "{\"name\":\"Temperature\",\"dataType\":\"DOUBLE\",\"unit\":\"Celsius\","
                        + "\"type\":{\"measurement\":{}}}"
                        + "]}")
                .when()
                .put("/asset-models/" + modelId)
                .then()
                .statusCode(200)
                .body("assetModelStatus.state", equalTo("ACTIVE"));

        given()
                .header("Authorization", authorization)
                .queryParam("excludeProperties", true)
                .when()
                .get("/asset-models/" + modelId)
                .then()
                .statusCode(200)
                .body("assetModelDescription", equalTo("pump model v2"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"assetName\":\"pump-1\",\"assetDescription\":\"pump model v2\"}")
                .when()
                .put("/assets/" + assetId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .queryParam("excludeProperties", true)
                .when()
                .get("/assets/" + assetId)
                .then()
                .statusCode(200)
                .body("assetDescription", equalTo("pump model v2"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/assets/" + assetId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/assets/" + assetId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/asset-models/" + modelId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/asset-models/" + modelId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void gateway_createDescribeReplaceDelete_roundTrip() {
        String authorization = auth();
        Response first = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(gatewayBody("edge-a", "AlchemyIoTSiteWiseCoreA"))
                .when()
                .post("/20200301/gateways")
                .then()
                .statusCode(200)
                .body("gatewayId", notNullValue())
                .body("gatewayArn", notNullValue())
                .extract()
                .response();
        String firstId = first.path("gatewayId");
        String firstArn = first.path("gatewayArn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/20200301/gateways/" + firstId)
                .then()
                .statusCode(200)
                .body("gatewayPlatform.greengrassV2.coreDeviceThingName",
                        equalTo("AlchemyIoTSiteWiseCoreA"));

        given()
                .header("Authorization", authorization)
                .queryParam("resourceArn", firstArn)
                .when()
                .get("/tags")
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("iotsitewise-gateway"));

        String secondId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(gatewayBody("edge-b", "AlchemyIoTSiteWiseCoreB"))
                .when()
                .post("/20200301/gateways")
                .then()
                .statusCode(200)
                .body("gatewayId", not(equalTo(firstId)))
                .extract()
                .path("gatewayId");
        assertNotEquals(firstId, secondId);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/20200301/gateways/" + secondId)
                .then()
                .statusCode(200)
                .body("gatewayPlatform.greengrassV2.coreDeviceThingName",
                        equalTo("AlchemyIoTSiteWiseCoreB"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/20200301/gateways/" + firstId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/20200301/gateways/" + firstId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/20200301/gateways/" + secondId)
                .then()
                .statusCode(200);
    }

    @Test
    void bindings_ingestAndQuery_roundTrip() {
        String authorization = auth();
        String modelId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(modelBody("bindings-pump-model", "alchemy iotsitewise bindings fixture"))
                .when()
                .post("/asset-models")
                .then()
                .statusCode(200)
                .extract()
                .path("assetModelId");

        String propertyId = given()
                .header("Authorization", authorization)
                .when()
                .get("/asset-models/" + modelId)
                .then()
                .statusCode(200)
                .body("assetModelProperties.name", hasItems("Temperature"))
                .extract()
                .path("assetModelProperties.find { it.name == 'Temperature' }.id");

        String assetId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"assetName\":\"bindings-pump\",\"assetModelId\":\"" + modelId + "\"}")
                .when()
                .post("/assets")
                .then()
                .statusCode(200)
                .extract()
                .path("assetId");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/assets/" + assetId)
                .then()
                .statusCode(200)
                .body("assetProperties.name", hasItems("Temperature"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/assets/" + assetId + "/properties")
                .then()
                .statusCode(200)
                .body("assetPropertySummaries.size()", greaterThanOrEqualTo(1));

        long now = System.currentTimeMillis() / 1000;
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"entries\":[{\"entryId\":\"temp-" + now + "\",\"assetId\":\"" + assetId
                        + "\",\"propertyId\":\"" + propertyId
                        + "\",\"propertyValues\":[{\"value\":{\"doubleValue\":23.5},"
                        + "\"timestamp\":{\"timeInSeconds\":" + now + "},\"quality\":\"GOOD\"}]}]}")
                .when()
                .post("/properties")
                .then()
                .statusCode(200)
                .body("errorEntries.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .queryParam("assetId", assetId)
                .queryParam("propertyId", propertyId)
                .when()
                .get("/properties/latest")
                .then()
                .statusCode(200)
                .body("propertyValue.value.doubleValue", equalTo(23.5f))
                .body("propertyValue.timestamp.timeInSeconds", equalTo((int) now));

        given()
                .header("Authorization", authorization)
                .queryParam("assetId", assetId)
                .queryParam("propertyId", propertyId)
                .queryParam("startDate", now - 600)
                .queryParam("endDate", now + 1)
                .queryParam("timeOrdering", "DESCENDING")
                .when()
                .get("/properties/history")
                .then()
                .statusCode(200)
                .body("assetPropertyValueHistory.size()", greaterThanOrEqualTo(1));

        given()
                .header("Authorization", authorization)
                .queryParam("assetId", assetId)
                .queryParam("propertyId", propertyId)
                .queryParam("aggregateTypes", "AVERAGE")
                .queryParam("resolution", "1m")
                .queryParam("startDate", now - 3600)
                .queryParam("endDate", now + 1)
                .when()
                .get("/properties/aggregates")
                .then()
                .statusCode(200)
                .body("aggregatedValues.size()", greaterThanOrEqualTo(0));

        given()
                .header("Authorization", authorization)
                .queryParam("assetId", assetId)
                .queryParam("propertyId", propertyId)
                .queryParam("startTimeInSeconds", now - 3600)
                .queryParam("endTimeInSeconds", now + 1)
                .queryParam("intervalInSeconds", 60)
                .queryParam("quality", "GOOD")
                .queryParam("type", "LINEAR_INTERPOLATION")
                .when()
                .get("/properties/interpolated")
                .then()
                .statusCode(200)
                .body("interpolatedAssetPropertyValues.size()", greaterThanOrEqualTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"queryStatement\":\"SELECT asset_id, asset_name FROM asset WHERE asset_id = '"
                        + assetId + "'\"}")
                .when()
                .post("/queries/execution")
                .then()
                .statusCode(200)
                .body("rows.size()", greaterThanOrEqualTo(1))
                .body("rows[0].data[0].scalarValue", equalTo(assetId));

        given().header("Authorization", authorization).when().delete("/assets/" + assetId).then().statusCode(200);
        given().header("Authorization", authorization).when().delete("/asset-models/" + modelId).then().statusCode(200);
    }

    private static String modelBody(String name, String description) {
        return "{"
                + "\"assetModelName\":\"" + name + "\","
                + "\"assetModelDescription\":\"" + description + "\","
                + "\"assetModelProperties\":["
                + "{\"name\":\"SerialNumber\",\"dataType\":\"STRING\","
                + "\"type\":{\"attribute\":{\"defaultValue\":\"unknown\"}}},"
                + "{\"name\":\"Temperature\",\"dataType\":\"DOUBLE\",\"unit\":\"Celsius\","
                + "\"type\":{\"measurement\":{}}}"
                + "],"
                + "\"tags\":{\"fixture\":\"iotsitewise\"}"
                + "}";
    }

    private static String gatewayBody(String name, String thing) {
        return "{"
                + "\"gatewayName\":\"" + name + "\","
                + "\"gatewayPlatform\":{\"greengrassV2\":{\"coreDeviceThingName\":\"" + thing + "\"}},"
                + "\"tags\":{\"fixture\":\"iotsitewise-gateway\"}"
                + "}";
    }

    private static String auth() {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + EAST + "/iotsitewise/aws4_request";
    }
}
