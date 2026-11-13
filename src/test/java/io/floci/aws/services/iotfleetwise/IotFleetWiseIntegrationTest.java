package io.floci.aws.services.iotfleetwise;

import com.amazon.ion.IonDatagram;
import com.amazon.ion.IonString;
import com.amazon.ion.IonStruct;
import com.amazon.ion.IonSystem;
import com.amazon.ion.system.IonSystemBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.xerial.snappy.Snappy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * IoT FleetWise end to end: the signal catalog, vehicle models, decoder manifests and state templates with their
 * validation; fleets and vehicles (AWS IoT things); and campaigns deployed to a vehicle whose Edge Agent this test
 * plays over MQTT, checking in and sending signals that land in S3 and on an IoT topic.
 */
@QuarkusTest
@TestProfile(IotFleetWiseIntegrationTest.Broker.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IotFleetWiseIntegrationTest {

    static final int PORT = 18852;

    /** A running MQTT broker for the agent, and S3 objects written as soon as data arrives. */
    public static class Broker implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iot.mqtt.enabled", "true",
                    "floci.services.iot.mqtt.auto-start", "true",
                    "floci.services.iot.mqtt.host", "127.0.0.1",
                    "floci.services.iot.mqtt.port", Integer.toString(PORT),
                    "floci.services.iotfleetwise.s3-flush-seconds", "1",
                    "floci.services.iotfleetwise.campaign-creation-millis", "300");
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";
    private static final String CATALOG_ARN = "arn:aws:iotfleetwise:us-east-1:" + ACCOUNT + ":signal-catalog/Signals";
    private static final String MODEL_ARN = "arn:aws:iotfleetwise:us-east-1:" + ACCOUNT + ":model-manifest/Sedan";
    private static final String DECODER_ARN = "arn:aws:iotfleetwise:us-east-1:" + ACCOUNT
            + ":decoder-manifest/SedanDecoder";
    private static final String BUCKET = "fleetwise-telemetry";
    private static final String OBD_SIGNAL = "{\"pidResponseLength\":1,\"serviceMode\":1,\"pid\":13,\"scaling\":1,"
            + "\"offset\":0,\"startByte\":0,\"byteLength\":1}";
    private static final String CAN_SIGNAL = "{\"messageId\":256,\"isBigEndian\":false,\"isSigned\":false,"
            + "\"startBit\":0,\"offset\":0,\"factor\":1,\"length\":8}";
    private static final String DBC = """
            VERSION ""

            BO_ 2364540158 EEC1: 8 Engine
             SG_ EngineSpeed : 24|16@1+ (0.125,0) [0|8031.875] "rpm" Vector__XXX
             SG_ Torque : 8|8@1- (1,-125) [-125|125] "%" Vector__XXX
            """;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static RequestSpecification fw(String region) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/" + region
                + "/iotfleetwise/aws4_request").contentType("application/x-amz-json-1.0");
    }

    static ValidatableResponse call(String operation, String body) {
        return call("us-east-1", operation, body);
    }

    static ValidatableResponse call(String region, String operation, String body) {
        return fw(region).header("X-Amz-Target", "IoTAutobahnControlPlane." + operation).body(body).post("/")
                .then();
    }

    static JsonNode json(ValidatableResponse response) {
        try {
            return MAPPER.readTree(response.extract().asString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode await(String what, Supplier<JsonNode> read, Predicate<JsonNode> done) {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = read.get();
            if (done.test(last)) {
                return last;
            }
            sleep(200);
        }
        fail(what + " did not happen: " + last);
        return last;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- modeling

    @Test
    @Order(1)
    void theSignalCatalogIsValidatedAndOnlyOneIsAllowed() {
        call("CreateSignalCatalog", "{\"name\":\"Orphan\",\"nodes\":[{\"sensor\":{\"fullyQualifiedName\":"
                + "\"Car.Speed\",\"dataType\":\"DOUBLE\"}}]}").statusCode(400)
                .body("__type", containsString("InvalidNodeException"));
        call("CreateSignalCatalog", "{\"name\":\"Untyped\",\"nodes\":[{\"branch\":{\"fullyQualifiedName\":\"Car\"}},"
                + "{\"sensor\":{\"fullyQualifiedName\":\"Car.Speed\"}}]}").statusCode(400)
                .body("__type", containsString("ValidationException"));
        call("CreateSignalCatalog", "{\"name\":\"Struct\",\"nodes\":[{\"branch\":{\"fullyQualifiedName\":"
                + "\"Car\"}},{\"sensor\":{\"fullyQualifiedName\":\"Car.Pose\",\"dataType\":\"STRUCT\"}}]}")
                .statusCode(400).body("__type", containsString("InvalidNodeException"));
        call("CreateSignalCatalog", "{\"name\":\"Signals\",\"description\":\"vehicle signals\",\"nodes\":["
                + "{\"branch\":{\"fullyQualifiedName\":\"Vehicle\"}},"
                + "{\"sensor\":{\"fullyQualifiedName\":\"Vehicle.Speed\",\"dataType\":\"DOUBLE\",\"unit\":\"km/h\"}},"
                + "{\"sensor\":{\"fullyQualifiedName\":\"Vehicle.EngineSpeed\",\"dataType\":\"DOUBLE\"}},"
                + "{\"sensor\":{\"fullyQualifiedName\":\"Vehicle.Torque\",\"dataType\":\"INT16\"}},"
                + "{\"sensor\":{\"fullyQualifiedName\":\"Vehicle.Gear\",\"dataType\":\"STRING\"}},"
                + "{\"attribute\":{\"fullyQualifiedName\":\"Vehicle.VIN\",\"dataType\":\"STRING\"}},"
                + "{\"attribute\":{\"fullyQualifiedName\":\"Vehicle.Make\",\"dataType\":\"STRING\"}}],"
                + "\"tags\":[{\"Key\":\"team\",\"Value\":\"fleet\"}]}").statusCode(200)
                .body("arn", equalTo(CATALOG_ARN));
        call("CreateSignalCatalog", "{\"name\":\"Second\",\"nodes\":[]}").statusCode(400)
                .body("__type", containsString("LimitExceededException"));
        call("GetSignalCatalog", "{\"name\":\"Signals\"}").statusCode(200).body("nodeCounts.totalNodes", equalTo(7))
                .body("nodeCounts.totalSensors", equalTo(4)).body("nodeCounts.totalAttributes", equalTo(2))
                .body("nodeCounts.totalBranches", equalTo(1));
        call("ListSignalCatalogNodes", "{\"name\":\"Signals\",\"signalNodeType\":\"ATTRIBUTE\"}").statusCode(200)
                .body("nodes", hasSize(2));
        call("UpdateSignalCatalog", "{\"name\":\"Signals\",\"nodesToAdd\":[{\"actuator\":{\"fullyQualifiedName\":"
                + "\"Vehicle.Horn\",\"dataType\":\"BOOLEAN\"}}],\"nodesToRemove\":[\"Vehicle.Make\"]}").statusCode(200);
        call("UpdateSignalCatalog", "{\"name\":\"Signals\",\"nodesToRemove\":[\"Vehicle\"]}").statusCode(400)
                .body("__type", containsString("InvalidSignalsException"));
        call("ListTagsForResource", "{\"ResourceARN\":\"" + CATALOG_ARN + "\"}").statusCode(200)
                .body("Tags[0].Key", equalTo("team"));

        String vss = "{\"Vehicle\":{\"type\":\"branch\",\"children\":{\"Speed\":{\"type\":\"sensor\","
                + "\"datatype\":\"float\",\"unit\":\"km/h\",\"min\":0,\"max\":300},\"Doors\":{\"type\":\"attribute\","
                + "\"datatype\":\"uint8[]\"}}}}";
        call("eu-central-1", "ImportSignalCatalog", "{\"name\":\"Imported\",\"vss\":{\"vssJson\":"
                + MAPPER.valueToTree(vss) + "}}").statusCode(200);
        JsonNode imported = json(call("eu-central-1", "ListSignalCatalogNodes", "{\"name\":\"Imported\"}")
                .statusCode(200));
        assertEquals(3, imported.path("nodes").size());
        assertTrue(imported.toString().contains("\"dataType\":\"UINT8_ARRAY\""), imported.toString());
        assertTrue(imported.toString().contains("\"dataType\":\"FLOAT\""), imported.toString());
        call("eu-central-1", "DeleteSignalCatalog", "{\"name\":\"Imported\"}").statusCode(200);
    }

    @Test
    @Order(2)
    void modelsAndDecodersAreValidatedAndActivated() {
        call("CreateModelManifest", "{\"name\":\"Bad\",\"signalCatalogArn\":\"" + CATALOG_ARN
                + "\",\"nodes\":[\"Vehicle.Nope\"]}").statusCode(400)
                .body("__type", containsString("InvalidSignalsException"))
                .body("invalidSignals[0].name", equalTo("Vehicle.Nope"));
        call("CreateModelManifest", "{\"name\":\"Sedan\",\"signalCatalogArn\":\"" + CATALOG_ARN + "\",\"nodes\":["
                + "\"Vehicle.Speed\",\"Vehicle.EngineSpeed\",\"Vehicle.Torque\",\"Vehicle.Gear\",\"Vehicle.VIN\"]}")
                .statusCode(200).body("arn", equalTo(MODEL_ARN));
        call("GetModelManifest", "{\"name\":\"Sedan\"}").statusCode(200).body("status", equalTo("DRAFT"));
        call("UpdateModelManifest", "{\"name\":\"Sedan\",\"status\":\"ACTIVE\"}").statusCode(200);
        call("UpdateModelManifest", "{\"name\":\"Sedan\",\"nodesToAdd\":[\"Vehicle.Horn\"]}").statusCode(400)
                .body("__type", containsString("ConflictException"));
        call("ListModelManifestNodes", "{\"name\":\"Sedan\"}").statusCode(200).body("nodes", hasSize(5));
        call("UpdateSignalCatalog", "{\"name\":\"Signals\",\"nodesToRemove\":[\"Vehicle.Speed\"]}").statusCode(400)
                .body("invalidSignals[0].reason", containsString("used by"));

        String obd = "{\"interfaceId\":\"obd0\",\"type\":\"OBD_INTERFACE\",\"obdInterface\":{\"name\":\"obd\","
                + "\"requestMessageId\":2015}}";
        String can = "{\"interfaceId\":\"can0\",\"type\":\"CAN_INTERFACE\",\"canInterface\":{\"name\":\"vcan0\"}}";
        JsonNode invalid = json(call("CreateDecoderManifest", "{\"name\":\"Broken\",\"modelManifestArn\":\""
                + MODEL_ARN + "\",\"networkInterfaces\":[" + obd + "],\"signalDecoders\":[{\"fullyQualifiedName\":"
                + "\"Vehicle.Horn\",\"type\":\"OBD_SIGNAL\",\"interfaceId\":\"obd0\",\"obdSignal\":" + OBD_SIGNAL + "},"
                + "{\"fullyQualifiedName\":\"Vehicle.Speed\",\"type\":\"CAN_SIGNAL\",\"interfaceId\":\"obd0\","
                + "\"canSignal\":" + CAN_SIGNAL + "}]}").statusCode(400));
        assertTrue(invalid.path("__type").asText().contains("DecoderManifestValidationException"));
        assertTrue(invalid.toString().contains("SIGNAL_NOT_IN_MODEL"), invalid.toString());
        assertTrue(invalid.toString().contains("NETWORK_INTERFACE_TYPE_INCOMPATIBLE_WITH_SIGNAL_DECODER_TYPE"),
                invalid.toString());

        call("CreateDecoderManifest", "{\"name\":\"SedanDecoder\",\"modelManifestArn\":\"" + MODEL_ARN
                + "\",\"networkInterfaces\":[" + obd + "," + can + "],\"signalDecoders\":[{\"fullyQualifiedName\":"
                + "\"Vehicle.Speed\",\"type\":\"OBD_SIGNAL\",\"interfaceId\":\"obd0\",\"obdSignal\":{"
                + "\"pidResponseLength\":1,\"serviceMode\":1,\"pid\":13,\"scaling\":1,\"offset\":0,\"startByte\":0,"
                + "\"byteLength\":1}}]}").statusCode(200).body("arn", equalTo(DECODER_ARN));
        call("UpdateDecoderManifest", "{\"name\":\"SedanDecoder\",\"status\":\"ACTIVE\"}").statusCode(200);
        call("GetDecoderManifest", "{\"name\":\"SedanDecoder\"}").statusCode(200).body("status", equalTo("INVALID"))
                .body("message", containsString("Vehicle.EngineSpeed"));
        call("UpdateDecoderManifest", "{\"name\":\"SedanDecoder\",\"status\":\"DRAFT\"}").statusCode(200);

        String dbc = Base64.getEncoder().encodeToString(DBC.getBytes(StandardCharsets.UTF_8));
        call("ImportDecoderManifest", "{\"name\":\"SedanDecoder\",\"networkFileDefinitions\":[{\"canDbc\":{"
                + "\"networkInterface\":\"can0\",\"canDbcFiles\":[\"" + dbc + "\"],\"signalsMap\":{\"EngineSpeed\":"
                + "\"Vehicle.EngineSpeed\",\"Torque\":\"Vehicle.Torque\"}}}]}").statusCode(200);
        JsonNode signals = json(call("ListDecoderManifestSignals", "{\"name\":\"SedanDecoder\"}").statusCode(200));
        assertEquals(3, signals.path("signalDecoders").size());
        JsonNode engine = signals.path("signalDecoders").get(1).path("canSignal");
        assertEquals(0x0CF004FE, engine.path("messageId").asLong());
        assertEquals(24, engine.path("startBit").asInt());
        assertEquals(0.125, engine.path("factor").asDouble());
        assertFalse(engine.path("isBigEndian").asBoolean());
        assertTrue(signals.path("signalDecoders").get(2).path("canSignal").path("isSigned").asBoolean());
        call("UpdateDecoderManifest", "{\"name\":\"SedanDecoder\",\"signalDecodersToAdd\":[{\"fullyQualifiedName\":"
                + "\"Vehicle.Gear\",\"type\":\"CAN_SIGNAL\",\"interfaceId\":\"can0\",\"canSignal\":{\"messageId\":"
                + "256,\"isBigEndian\":false,\"isSigned\":false,\"startBit\":0,\"offset\":0,\"factor\":1,"
                + "\"length\":8}}],\"status\":\"ACTIVE\"}").statusCode(200);
        call("GetDecoderManifest", "{\"name\":\"SedanDecoder\"}").statusCode(200).body("status", equalTo("ACTIVE"));
        call("ListDecoderManifestNetworkInterfaces", "{\"name\":\"SedanDecoder\"}").statusCode(200)
                .body("networkInterfaces", hasSize(2));
        call("UpdateDecoderManifest", "{\"name\":\"SedanDecoder\",\"signalDecodersToRemove\":[\"Vehicle.Gear\"]}")
                .statusCode(400).body("__type", containsString("ConflictException"));
        call("DeleteModelManifest", "{\"name\":\"Sedan\"}").statusCode(400)
                .body("__type", containsString("ConflictException"));

        call("CreateStateTemplate", "{\"name\":\"Live\",\"signalCatalogArn\":\"" + CATALOG_ARN
                + "\",\"stateTemplateProperties\":[\"Vehicle.Speed\"],\"dataExtraDimensions\":[\"Vehicle.VIN\"]}")
                .statusCode(200).body("id", matchesPattern("[A-Z0-9]{26}"));
        call("CreateStateTemplate", "{\"name\":\"Wrong\",\"signalCatalogArn\":\"" + CATALOG_ARN
                + "\",\"stateTemplateProperties\":[\"Vehicle.Speed\"],\"dataExtraDimensions\":[\"Vehicle.Speed\"]}")
                .statusCode(400).body("__type", containsString("InvalidSignalsException"));
        call("UpdateStateTemplate", "{\"identifier\":\"Live\",\"stateTemplatePropertiesToAdd\":[\"Vehicle.Gear\"]}")
                .statusCode(200);
        call("GetStateTemplate", "{\"identifier\":\"Live\"}").statusCode(200)
                .body("stateTemplateProperties", hasItem("Vehicle.Gear"));
    }

    @Test
    @Order(3)
    void vehiclesAreIotThingsAndJoinFleets() {
        call("CreateVehicle", "{\"vehicleName\":\"car1\",\"modelManifestArn\":\"" + MODEL_ARN
                + "\",\"decoderManifestArn\":\"" + DECODER_ARN + "\",\"attributes\":{\"Vehicle.Bogus\":\"x\"}}")
                .statusCode(400).body("message", containsString("Vehicle.Bogus"));
        String thingArn = json(call("CreateVehicle", "{\"vehicleName\":\"car1\",\"modelManifestArn\":\"" + MODEL_ARN
                + "\",\"decoderManifestArn\":\"" + DECODER_ARN + "\",\"attributes\":{\"Vehicle.VIN\":\"VIN1\"},"
                + "\"stateTemplates\":[{\"identifier\":\"Live\",\"stateTemplateUpdateStrategy\":{\"onChange\":{}}}]}")
                .statusCode(200)).path("thingArn").asText();
        assertEquals("arn:aws:iot:us-east-1:" + ACCOUNT + ":thing/car1", thingArn);
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/iot/aws4_request")
                .get("/things/car1").then().statusCode(200).body("thingName", equalTo("car1"));
        call("CreateVehicle", "{\"vehicleName\":\"car1\",\"modelManifestArn\":\"" + MODEL_ARN
                + "\",\"decoderManifestArn\":\"" + DECODER_ARN + "\"}").statusCode(400)
                .body("__type", containsString("ConflictException"));

        JsonNode batch = json(call("BatchCreateVehicle", "{\"vehicles\":[{\"vehicleName\":\"car2\","
                + "\"modelManifestArn\":\"" + MODEL_ARN + "\",\"decoderManifestArn\":\"" + DECODER_ARN
                + "\",\"attributes\":{\"Vehicle.VIN\":\"VIN2\"}},{\"vehicleName\":\"car3\",\"modelManifestArn\":\""
                + MODEL_ARN.replace("Sedan", "Nope") + "\",\"decoderManifestArn\":\"" + DECODER_ARN + "\"}]}")
                .statusCode(200));
        assertEquals(1, batch.path("vehicles").size());
        assertEquals("ResourceNotFoundException", batch.path("errors").get(0).path("code").asText());
        JsonNode updated = json(call("BatchUpdateVehicle", "{\"vehicles\":[{\"vehicleName\":\"car2\",\"attributes\":"
                + "{\"Vehicle.VIN\":\"VIN2b\"},\"attributeUpdateMode\":\"Merge\"},{\"vehicleName\":\"ghost\"}]}")
                .statusCode(200));
        assertEquals(1, updated.path("vehicles").size());
        assertEquals(404, updated.path("errors").get(0).path("code").asInt());
        call("ListVehicles", "{\"attributeNames\":[\"Vehicle.VIN\"],\"attributeValues\":[\"VIN2b\"]}").statusCode(200)
                .body("vehicleSummaries", hasSize(1)).body("vehicleSummaries[0].vehicleName", equalTo("car2"));
        call("ListVehicles", "{\"modelManifestArn\":\"" + MODEL_ARN + "\",\"maxResults\":1}").statusCode(200)
                .body("vehicleSummaries", hasSize(1)).body("nextToken", notNullValue());

        call("CreateFleet", "{\"fleetId\":\"Sedans\",\"signalCatalogArn\":\"" + CATALOG_ARN + "\"}").statusCode(200);
        call("AssociateVehicleFleet", "{\"fleetId\":\"Sedans\",\"vehicleName\":\"car1\"}").statusCode(200);
        call("AssociateVehicleFleet", "{\"fleetId\":\"Sedans\",\"vehicleName\":\"car2\"}").statusCode(200);
        call("ListVehiclesInFleet", "{\"fleetId\":\"Sedans\"}").statusCode(200).body("vehicles", hasSize(2));
        call("ListFleetsForVehicle", "{\"vehicleName\":\"car1\"}").statusCode(200).body("fleets[0]", equalTo("Sedans"));
        call("DisassociateVehicleFleet", "{\"fleetId\":\"Sedans\",\"vehicleName\":\"car2\"}").statusCode(200);
        call("DeleteFleet", "{\"fleetId\":\"Sedans\"}").statusCode(400).body("message", containsString("vehicles"));
        call("DeleteDecoderManifest", "{\"name\":\"SedanDecoder\"}").statusCode(400)
                .body("__type", containsString("ConflictException"));
        call("DeleteVehicle", "{\"vehicleName\":\"car2\"}").statusCode(200);
        call("GetVehicle", "{\"vehicleName\":\"car2\"}").statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    // ---------------------------------------------------------------- campaigns and the edge

    @Test
    @Order(4)
    void campaignsAreValidatedAgainstTheirDestinations() {
        String base = "{\"name\":\"%s\",\"signalCatalogArn\":\"" + CATALOG_ARN + "\",\"targetArn\":\""
                + "arn:aws:iotfleetwise:us-east-1:" + ACCOUNT + ":fleet/Sedans\",\"collectionScheme\":"
                + "{\"timeBasedCollectionScheme\":{\"periodMs\":10000}},\"signalsToCollect\":[{\"name\":"
                + "\"Vehicle.Speed\"}],\"dataDestinationConfigs\":[%s]}";
        s3().put("/" + BUCKET).then().statusCode(200);
        call("CreateCampaign", String.format(base, "NoPolicy", "{\"s3Config\":{\"bucketArn\":\"arn:aws:s3:::"
                + BUCKET + "\"}}")).statusCode(400).body("message", containsString("iotfleetwise.amazonaws.com"));
        s3().body("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"iotfleetwise.amazonaws.com\"},\"Action\":[\"s3:GetBucketLocation\",\"s3:PutObject\"],"
                + "\"Resource\":[\"arn:aws:s3:::" + BUCKET + "\",\"arn:aws:s3:::" + BUCKET + "/*\"]}]}")
                .put("/" + BUCKET + "?policy").then().statusCode(lessThan(300));
        call("CreateCampaign", String.format(base, "Timestream", "{\"timestreamConfig\":{\"timestreamTableArn\":"
                + "\"arn:aws:timestream:us-east-1:" + ACCOUNT + ":database/db/table/t\",\"executionRoleArn\":"
                + "\"arn:aws:iam::" + ACCOUNT + ":role/fw\"}}")).statusCode(400)
                .body("message", containsString("Timestream"));
        call("CreateCampaign", String.format(base, "Bad", "{\"s3Config\":{\"bucketArn\":\"arn:aws:s3:::" + BUCKET
                + "\"}}").replace("Vehicle.Speed\"}", "Vehicle.Nope\"}")).statusCode(400)
                .body("__type", containsString("InvalidSignalsException"));
        call("CreateCampaign", String.format(base, "Expr", "{\"s3Config\":{\"bucketArn\":\"arn:aws:s3:::" + BUCKET
                + "\"}}").replace("{\"timeBasedCollectionScheme\":{\"periodMs\":10000}}",
                "{\"conditionBasedCollectionScheme\":{\"expression\":\"$variable.`Vehicle.Speed` >\"}}"))
                .statusCode(400).body("message", containsString("expression"));
    }

    @Test
    @Order(5)
    void aRunningCampaignReachesTheVehicleAndItsDataReachesS3AndATopic() throws Exception {
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/iam/aws4_request")
                .contentType("application/x-www-form-urlencoded").formParam("Action", "CreateRole")
                .formParam("RoleName", "fleetwise-topic").formParam("Version", "2010-05-08")
                .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":"
                        + "\"Allow\",\"Principal\":{\"Service\":\"iotfleetwise.amazonaws.com\"},\"Action\":"
                        + "\"sts:AssumeRole\"}]}").post("/").then().statusCode(200);
        call("CreateCampaign", "{\"name\":\"Telemetry\",\"signalCatalogArn\":\"" + CATALOG_ARN + "\",\"targetArn\":\""
                + "arn:aws:iotfleetwise:us-east-1:" + ACCOUNT + ":fleet/Sedans\",\"collectionScheme\":"
                + "{\"conditionBasedCollectionScheme\":{\"expression\":\"$variable.`Vehicle.Speed` > 50 && "
                + "!($variable.`Vehicle.Gear` == 'P')\",\"minimumTriggerIntervalMs\":1000,\"triggerMode\":"
                + "\"RISING_EDGE\"}},\"signalsToCollect\":[{\"name\":\"Vehicle.Speed\",\"maxSampleCount\":10},"
                + "{\"name\":\"Vehicle.Gear\"}],\"dataExtraDimensions\":[\"Vehicle.VIN\"],\"dataDestinationConfigs\":"
                + "[{\"s3Config\":{\"bucketArn\":\"arn:aws:s3:::" + BUCKET + "\",\"dataFormat\":\"JSON\","
                + "\"storageCompressionFormat\":\"NONE\",\"prefix\":\"fleet\"}},{\"mqttTopicConfig\":{"
                + "\"mqttTopicArn\":\"arn:aws:iot:us-east-1:" + ACCOUNT + ":topic/fleet/telemetry\","
                + "\"executionRoleArn\":\"arn:aws:iam::" + ACCOUNT + ":role/fleetwise-topic\"}}]}").statusCode(200);
        call("GetCampaign", "{\"name\":\"Telemetry\"}").statusCode(200).body("compression", equalTo("SNAPPY"));
        await("the campaign to wait for approval", () -> json(call("GetCampaign", "{\"name\":\"Telemetry\"}")),
                c -> "WAITING_FOR_APPROVAL".equals(c.path("status").asText()));
        call("GetVehicleStatus", "{\"vehicleName\":\"car1\"}").statusCode(200)
                .body("campaigns[0].status", equalTo("CREATED"));
        call("UpdateCampaign", "{\"name\":\"Telemetry\",\"action\":\"RESUME\"}").statusCode(400)
                .body("__type", containsString("ConflictException"));

        String campaignArn = "arn:aws:iotfleetwise:us-east-1:" + ACCOUNT + ":campaign/Telemetry";
        try (Agent agent = Agent.connect("car1"); Agent sink = Agent.connect("sink")) {
            sink.subscribe("fleet/telemetry");
            call("UpdateCampaign", "{\"name\":\"Telemetry\",\"action\":\"APPROVE\"}").statusCode(200)
                    .body("status", equalTo("RUNNING"));
            call("GetVehicleStatus", "{\"vehicleName\":\"car1\"}").statusCode(200)
                    .body("campaigns[0].status", equalTo("READY"));

            // The agent's documents, retained for it.
            agent.subscribe("$aws/iotfleetwise/vehicles/car1/#");
            byte[] decoder = agent.await("$aws/iotfleetwise/vehicles/car1/decoder_manifests", bytes -> true);
            Map<Integer, IotFleetWiseProto.Field> manifest = IotFleetWiseProto.fields(decoder);
            assertEquals(DECODER_ARN, manifest.get(1).asString());
            List<IotFleetWiseProto.Field> obd = IotFleetWiseProto.read(decoder).stream()
                    .filter(field -> field.number() == 3).toList();
            assertEquals(1, obd.size());
            assertEquals(13, IotFleetWiseProto.fields(obd.get(0).asBytes()).get(5).asLong());
            assertEquals(3, IotFleetWiseProto.read(decoder).stream().filter(field -> field.number() == 2).count());
            byte[] schemes = agent.await("$aws/iotfleetwise/vehicles/car1/collection_schemes",
                    bytes -> IotFleetWiseProto.read(bytes).stream().anyMatch(field -> field.number() == 1));
            byte[] scheme = IotFleetWiseProto.read(schemes).stream().filter(field -> field.number() == 1)
                    .findFirst().orElseThrow().asBytes();
            Map<Integer, IotFleetWiseProto.Field> fields = IotFleetWiseProto.fields(scheme);
            assertEquals(campaignArn, fields.get(1).asString());
            assertEquals(DECODER_ARN, fields.get(2).asString());
            assertTrue(fields.get(12).asLong() == 1, "compression is on");
            Map<Integer, IotFleetWiseProto.Field> condition = IotFleetWiseProto.fields(fields.get(6).asBytes());
            assertEquals(1000, condition.get(1).asLong());
            assertEquals(1, condition.get(4).asLong());
            // The root of the condition tree is the && operator (6).
            Map<Integer, IotFleetWiseProto.Field> root = IotFleetWiseProto.fields(
                    IotFleetWiseProto.fields(condition.get(3).asBytes()).get(1).asBytes());
            assertEquals(6, root.get(3).asLong());
            List<IotFleetWiseProto.Field> collected = IotFleetWiseProto.read(scheme).stream()
                    .filter(field -> field.number() == 9).toList();
            assertEquals(2, collected.size());
            long speedId = IotFleetWiseProto.fields(collected.get(0).asBytes()).get(1).asLong();
            long gearId = IotFleetWiseProto.fields(collected.get(1).asBytes()).get(1).asLong();
            assertEquals(10, IotFleetWiseProto.fields(collected.get(0).asBytes()).get(2).asLong());

            // The agent checks in with the campaign enacted.
            agent.publish("$aws/iotfleetwise/vehicles/car1/checkins", new IotFleetWiseProto.Writer()
                    .string(1, campaignArn).string(1, DECODER_ARN).uint(2, System.currentTimeMillis()).bytes());
            await("the vehicle to be healthy", () -> json(call("GetVehicleStatus", "{\"vehicleName\":\"car1\"}")),
                    s -> "HEALTHY".equals(s.path("campaigns").get(0).path("status").asText()));

            // Then sends data, snappy-compressed as the campaign asks.
            long eventTime = System.currentTimeMillis();
            byte[] data = new IotFleetWiseProto.Writer().string(1, campaignArn).string(2, DECODER_ARN).uint(3, 42)
                    .uint(4, eventTime)
                    .message(5, new IotFleetWiseProto.Writer().sint(1, -5).uintAlways(2, speedId).dblAlways(3, 88.5))
                    .message(5, new IotFleetWiseProto.Writer().sint(1, 0).uintAlways(2, gearId).string(4, "D"))
                    .message(6, new IotFleetWiseProto.Writer().string(2, "P0420")).bytes();
            agent.publish("$aws/iotfleetwise/vehicles/car1/signals", Snappy.compress(data));

            byte[] forwarded = sink.await("fleet/telemetry", bytes -> true);
            JsonNode records = MAPPER.readTree(forwarded);
            assertEquals(2, records.size());
            assertEquals("Vehicle.Speed", records.get(0).path("name").asText());
            assertEquals(88.5, records.get(0).path("measure_value_DOUBLE").asDouble());
            assertEquals(eventTime - 5, records.get(0).path("time").asLong());
            assertEquals("VIN1", records.get(0).path("Vehicle.VIN").asText());
            assertEquals("D", records.get(1).path("measure_value_VARCHAR").asText());
            assertEquals("Telemetry", records.get(1).path("campaignName").asText());

            String listing = await("the processed data in S3", () -> MAPPER.valueToTree(s3().queryParam("prefix",
                    "fleet/").get("/" + BUCKET).then().statusCode(200).extract().asString()),
                    text -> text.asText().contains("processed-data/") && text.asText().contains("raw-data/"))
                    .asText();
            String processed = listing.substring(listing.indexOf("fleet/processed-data/year="));
            processed = processed.substring(0, processed.indexOf("</Key>"));
            assertTrue(processed.endsWith(".json"), processed);
            String body = s3().get("/" + BUCKET + "/" + processed).then().statusCode(200).extract().asString();
            assertEquals(2, body.trim().split("\n").length);
            assertTrue(body.contains("\"measure_value_DOUBLE\":88.5"), body);
            String raw = listing.substring(listing.indexOf("fleet/raw-data/car1/"));
            raw = raw.substring(0, raw.indexOf("</Key>"));
            assertEquals("fleet/raw-data/car1/42-" + eventTime + ".10n", raw);
            byte[] ion = s3().get("/" + BUCKET + "/" + raw).then().statusCode(200).extract().asByteArray();
            IonSystem system = IonSystemBuilder.standard().build();
            IonDatagram datagram = system.getLoader().load(ion);
            IonStruct struct = (IonStruct) datagram.get(0);
            assertEquals("car1", ((IonString) struct.get("vehicleName")).stringValue());
            assertTrue(struct.get("activeDtcCodes").toString().contains("P0420"));

            // Suspending withdraws the campaign from the vehicle.
            call("UpdateCampaign", "{\"name\":\"Telemetry\",\"action\":\"SUSPEND\"}").statusCode(200)
                    .body("status", equalTo("SUSPENDED"));
            agent.await("$aws/iotfleetwise/vehicles/car1/collection_schemes",
                    bytes -> IotFleetWiseProto.read(bytes).stream().noneMatch(field -> field.number() == 1));
            call("GetVehicleStatus", "{\"vehicleName\":\"car1\"}").statusCode(200)
                    .body("campaigns[0].status", equalTo("SUSPENDED"));
            call("UpdateCampaign", "{\"name\":\"Telemetry\",\"action\":\"RESUME\"}").statusCode(200)
                    .body("status", equalTo("RUNNING"));
            call("UpdateCampaign", "{\"name\":\"Telemetry\",\"action\":\"UPDATE\",\"description\":\"speeding\"}")
                    .statusCode(200);
            call("GetCampaign", "{\"name\":\"Telemetry\"}").statusCode(200).body("description", equalTo("speeding"));
            call("DeleteFleet", "{\"fleetId\":\"Sedans\"}").statusCode(400);
            call("DeleteCampaign", "{\"name\":\"Telemetry\"}").statusCode(200);
            agent.await("$aws/iotfleetwise/vehicles/car1/collection_schemes",
                    bytes -> IotFleetWiseProto.read(bytes).stream().noneMatch(field -> field.number() == 1));
        }
    }

    @Test
    @Order(6)
    void accountSettingsAreKept() {
        call("GetLoggingOptions", "{}").statusCode(200).body("cloudWatchLogDelivery.logType", equalTo("OFF"));
        call("PutLoggingOptions", "{\"cloudWatchLogDelivery\":{\"logType\":\"ERROR\"}}").statusCode(400);
        call("PutLoggingOptions", "{\"cloudWatchLogDelivery\":{\"logType\":\"ERROR\",\"logGroupName\":"
                + "\"/fleetwise\"}}").statusCode(200);
        call("GetLoggingOptions", "{}").statusCode(200).body("cloudWatchLogDelivery.logGroupName",
                equalTo("/fleetwise"));
        call("GetEncryptionConfiguration", "{}").statusCode(200).body("encryptionType",
                equalTo("FLEETWISE_DEFAULT_ENCRYPTION"));
        call("PutEncryptionConfiguration", "{\"encryptionType\":\"KMS_BASED_ENCRYPTION\"}").statusCode(400);
        call("PutEncryptionConfiguration", "{\"encryptionType\":\"KMS_BASED_ENCRYPTION\",\"kmsKeyId\":\"alias/fw\"}")
                .statusCode(200).body("encryptionStatus", equalTo("SUCCESS"));
        call("GetRegisterAccountStatus", "{}").statusCode(400).body("__type",
                containsString("ResourceNotFoundException"));
        call("RegisterAccount", "{\"timestreamResources\":{\"timestreamDatabaseName\":\"db\","
                + "\"timestreamTableName\":\"table\"}}").statusCode(400);
        call("RegisterAccount", "{}").statusCode(200).body("registerAccountStatus", equalTo("REGISTRATION_SUCCESS"));
        call("GetRegisterAccountStatus", "{}").statusCode(200).body("customerAccountId", equalTo(ACCOUNT));
        assertNotNull(call("ListCampaigns", "{}").statusCode(200).extract().path("campaignSummaries"));
    }

    private static RequestSpecification s3() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/s3/aws4_request");
    }

    /** The Edge Agent's side of the conversation: an MQTT client for one vehicle. */
    private static final class Agent implements AutoCloseable {
        private final MqttClient client;
        private final BlockingQueue<Object[]> messages = new LinkedBlockingQueue<>();
        private final List<Object[]> backlog = new ArrayList<>();

        private Agent(MqttClient client) {
            this.client = client;
        }

        static Agent connect(String clientId) throws MqttException {
            MqttClient client = new MqttClient("tcp://127.0.0.1:" + PORT, clientId, new MemoryPersistence());
            Agent agent = new Agent(client);
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    agent.messages.add(new Object[] {topic, message.getPayload()});
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(5);
            client.connect(options);
            return agent;
        }

        void subscribe(String topic) throws MqttException {
            client.subscribe(topic, 1);
        }

        void publish(String topic, byte[] payload) throws MqttException {
            client.publish(topic, payload, 1, false);
        }

        /**
         * The next payload on {@code topic} that satisfies {@code done}. Others are kept for later calls: the broker
         * sends retained messages in topic order, not the order they were published.
         */
        byte[] await(String topic, Predicate<byte[]> done) throws InterruptedException {
            for (Object[] message : new ArrayList<>(backlog)) {
                if (topic.equals(message[0]) && done.test((byte[]) message[1])) {
                    backlog.remove(message);
                    return (byte[]) message[1];
                }
            }
            long deadline = System.currentTimeMillis() + 15_000;
            List<String> seen = new ArrayList<>();
            while (System.currentTimeMillis() < deadline) {
                Object[] message = messages.poll(500, TimeUnit.MILLISECONDS);
                if (message == null) {
                    continue;
                }
                seen.add((String) message[0]);
                if (topic.equals(message[0]) && done.test((byte[]) message[1])) {
                    return (byte[]) message[1];
                }
                backlog.add(message);
            }
            throw new AssertionError("No matching publish on " + topic + "; saw " + seen);
        }

        @Override
        public void close() throws MqttException {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        }
    }
}
