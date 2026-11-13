package io.floci.aws.services.greengrassv2;

import io.floci.aws.testing.IotMqttTlsProfile;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A nucleus's Fleet Status report over MQTT, on its reserved {@code $aws/things/<thing>/greengrassv2/health/json}
 * topic, registers the core device as a report through the data plane does.
 */
@QuarkusTest
@TestProfile(IotMqttTlsProfile.class)
class GreengrassV2FleetStatusMqttIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification gg() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/"
                + "greengrass/aws4_request");
    }

    @Test
    void aFleetStatusReportOverMqttRegistersTheCoreDevice() throws Exception {
        MqttClient client = new MqttClient("tcp://127.0.0.1:" + IotMqttTlsProfile.PLAIN_PORT, "gg-mqtt-core",
                new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        client.connect(options);
        try {
            client.publish("$aws/things/gg-mqtt-core/greengrassv2/health/json", ("{\"ggcVersion\":\"2.14.3\","
                    + "\"platform\":\"linux\",\"architecture\":\"aarch64\",\"thing\":\"gg-mqtt-core\","
                    + "\"messageType\":\"COMPLETE\",\"overallDeviceStatus\":\"UNHEALTHY\",\"components\":"
                    + "[{\"componentName\":\"com.example.Broken\",\"version\":\"1.0.0\",\"lifecycleState\":"
                    + "\"BROKEN\",\"isRoot\":true}]}").getBytes(StandardCharsets.UTF_8), 1, false);
        } finally {
            client.disconnect();
            client.close();
        }
        for (int attempt = 0; attempt < 50; attempt++) {
            int status = gg().get("/greengrass/v2/coreDevices/gg-mqtt-core").then().extract().statusCode();
            if (status == 200) {
                gg().get("/greengrass/v2/coreDevices/gg-mqtt-core").then().body("status", equalTo("UNHEALTHY"))
                        .body("architecture", equalTo("aarch64"));
                gg().get("/greengrass/v2/coreDevices/gg-mqtt-core/installedComponents").then().statusCode(200)
                        .body("installedComponents[0].lifecycleState", equalTo("BROKEN"));
                return;
            }
            Thread.sleep(100);
        }
        fail("The Fleet Status report over MQTT did not register the core device");
    }
}
