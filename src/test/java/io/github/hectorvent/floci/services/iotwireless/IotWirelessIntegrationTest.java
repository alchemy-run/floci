package io.github.hectorvent.floci.services.iotwireless;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * restJson1 IoT Wireless coverage used by Alchemy IoTWireless Bindings.test.ts:
 * destination/profile/device/gateway lifecycle, queued downlinks, statistics,
 * positions, GetServiceEndpoint, GetPositionEstimate, TestWirelessDevice.
 */
@QuarkusTest
class IotWirelessIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000004801";
    private static final String ROLE_ARN = "arn:aws:iam::000000004801:role/IotWirelessDelivery";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getServiceProfileOnABogusIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth())
                .when()
                .get("/service-profiles/00000000-0000-0000-0000-000000000000")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getDestinationOnANonexistentNameFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth())
                .when()
                .get("/destinations/alchemy-nonexistent-destination")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void destinationCreateGetListDeleteRoundTrip() {
        String name = "alchemy-iotw-dest";
        given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("""
                        {
                          "Name":"%s",
                          "ExpressionType":"RuleName",
                          "Expression":"alchemy_iot_wireless_bindings_rule",
                          "RoleArn":"%s",
                          "Tags":[{"Key":"fixture","Value":"iot-wireless-bindings"}]
                        }
                        """.formatted(name, ROLE_ARN))
                .when()
                .post("/destinations")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name))
                .body("Arn", notNullValue());

        given()
                .header("Authorization", auth())
                .when()
                .get("/destinations/" + name)
                .then()
                .statusCode(200)
                .body("ExpressionType", equalTo("RuleName"))
                .body("Expression", equalTo("alchemy_iot_wireless_bindings_rule"))
                .body("RoleArn", equalTo(ROLE_ARN));

        given()
                .header("Authorization", auth())
                .when()
                .get("/destinations")
                .then()
                .statusCode(200)
                .body("DestinationList.find { it.Name == '" + name + "' }.Arn", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("""
                        {
                          "ExpressionType":"RuleName",
                          "Expression":"alchemy_iot_wireless_test_rule_v2"
                        }
                        """)
                .when()
                .patch("/destinations/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", auth())
                .when()
                .get("/destinations/" + name)
                .then()
                .statusCode(200)
                .body("Expression", equalTo("alchemy_iot_wireless_test_rule_v2"));

        given()
                .header("Authorization", auth())
                .when()
                .delete("/destinations/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", auth())
                .when()
                .get("/destinations/" + name)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deviceAndBindingsRoundTrip() {
        String dest = "alchemy-iotw-bind-dest";
        given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("""
                        {
                          "Name":"%s",
                          "ExpressionType":"RuleName",
                          "Expression":"alchemy_iot_wireless_bindings_rule",
                          "RoleArn":"%s"
                        }
                        """.formatted(dest, ROLE_ARN))
                .when()
                .post("/destinations")
                .then()
                .statusCode(200);

        String serviceProfileId = given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("{\"Name\":\"BindingsFleet\",\"LoRaWAN\":{\"AddGwMetadata\":true}}")
                .when()
                .post("/service-profiles")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .extract()
                .path("Id");

        String deviceProfileId = given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("""
                        {
                          "Name":"BindingsModel",
                          "LoRaWAN":{
                            "MacVersion":"1.0.3",
                            "RegParamsRevision":"RP002-1.0.1",
                            "RfRegion":"US915",
                            "MaxEirp":10,
                            "SupportsJoin":false
                          }
                        }
                        """)
                .when()
                .post("/device-profiles")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .extract()
                .path("Id");

        String deviceId = given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("""
                        {
                          "Type":"LoRaWAN",
                          "Name":"BindingsSensor",
                          "DestinationName":"%s",
                          "Positioning":"Enabled",
                          "LoRaWAN":{
                            "DevEui":"1a2b3c4d5e6f7091",
                            "DeviceProfileId":"%s",
                            "ServiceProfileId":"%s",
                            "AbpV1_0_x":{
                              "DevAddr":"01020304",
                              "SessionKeys":{
                                "NwkSKey":"000102030405060708090a0b0c0d0e0f",
                                "AppSKey":"0f0e0d0c0b0a09080706050403020100"
                              }
                            }
                          }
                        }
                        """.formatted(dest, deviceProfileId, serviceProfileId))
                .when()
                .post("/wireless-devices")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .extract()
                .path("Id");

        String gatewayId = given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("""
                        {
                          "Name":"BindingsGateway",
                          "LoRaWAN":{"GatewayEui":"aa555a0000000201","RfRegion":"US915"}
                        }
                        """)
                .when()
                .post("/wireless-gateways")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .extract()
                .path("Id");

        given()
                .header("Authorization", auth())
                .when()
                .get("/service-endpoint?serviceType=LNS")
                .then()
                .statusCode(200)
                .body("ServiceType", equalTo("LNS"))
                .body("ServiceEndpoint", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("""
                        {
                          "TransmitMode":1,
                          "PayloadData":"SGVsbG8sIFdvcmxkIQ==",
                          "WirelessMetadata":{"LoRaWAN":{"FPort":1}}
                        }
                        """)
                .when()
                .post("/wireless-devices/" + deviceId + "/data")
                .then()
                .statusCode(200)
                .body("MessageId", notNullValue());

        given()
                .header("Authorization", auth())
                .when()
                .get("/wireless-devices/" + deviceId + "/data")
                .then()
                .statusCode(200)
                .body("DownlinkQueueMessagesList.size()", greaterThanOrEqualTo(1));

        given()
                .header("Authorization", auth())
                .when()
                .delete("/wireless-devices/" + deviceId + "/data?messageId=*")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", auth())
                .when()
                .get("/wireless-devices/" + deviceId + "/data")
                .then()
                .statusCode(200)
                .body("DownlinkQueueMessagesList.size()", equalTo(0));

        given()
                .header("Authorization", auth())
                .when()
                .get("/wireless-devices/" + deviceId + "/statistics")
                .then()
                .statusCode(200)
                .body("WirelessDeviceId", equalTo(deviceId));

        given()
                .header("Authorization", auth())
                .when()
                .get("/wireless-gateways/" + gatewayId + "/statistics")
                .then()
                .statusCode(200)
                .body("WirelessGatewayId", equalTo(gatewayId))
                .body("ConnectionStatus", equalTo("Connected"));

        given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("{\"type\":\"Point\",\"coordinates\":[-122.33,47.61,10]}")
                .when()
                .patch("/resource-positions/" + deviceId + "?resourceType=WirelessDevice")
                .then()
                .statusCode(200);

        String geoJson = given()
                .header("Authorization", auth())
                .when()
                .get("/resource-positions/" + deviceId + "?resourceType=WirelessDevice")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertTrue(geoJson.contains("-122.33"));

        given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("""
                        {
                          "WiFiAccessPoints":[
                            {"MacAddress":"A0:EC:F9:1E:32:C1","Rss":-66}
                          ]
                        }
                        """)
                .when()
                .post("/position-estimate")
                .then()
                .statusCode(200)
                .body("type", equalTo("Point"));

        given()
                .header("Authorization", auth())
                .when()
                .post("/wireless-devices/" + deviceId + "/test")
                .then()
                .statusCode(200)
                .body("Result", notNullValue());

        given()
                .header("Authorization", auth())
                .when()
                .get("/wireless-devices/" + deviceId + "?identifierType=WirelessDeviceId")
                .then()
                .statusCode(200)
                .body("DestinationName", equalTo(dest))
                .body("LoRaWAN.DevEui", equalTo("1a2b3c4d5e6f7091"));
    }

    @Test
    void tagsRoundTripOnDestination() {
        String name = "alchemy-iotw-tagged";
        String arn = given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("""
                        {
                          "Name":"%s",
                          "ExpressionType":"RuleName",
                          "Expression":"tagged_rule",
                          "RoleArn":"%s",
                          "Tags":[{"Key":"fixture","Value":"iot-wireless-bindings"}]
                        }
                        """.formatted(name, ROLE_ARN))
                .when()
                .post("/destinations")
                .then()
                .statusCode(200)
                .extract()
                .path("Arn");

        given()
                .header("Authorization", auth())
                .when()
                .get("/tags?resourceArn=" + arn)
                .then()
                .statusCode(200)
                .body("Tags.find { it.Key == 'fixture' }.Value", equalTo("iot-wireless-bindings"));

        given()
                .contentType("application/json")
                .header("Authorization", auth())
                .body("{\"Tags\":[{\"Key\":\"phase\",\"Value\":\"two\"}]}")
                .when()
                .post("/tags?resourceArn=" + arn)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", auth())
                .when()
                .get("/tags?resourceArn=" + arn)
                .then()
                .statusCode(200)
                .body("Tags.find { it.Key == 'phase' }.Value", equalTo("two"));
    }

    private static String auth() {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260205/" + EAST
                + "/iotwireless/aws4_request";
    }
}
