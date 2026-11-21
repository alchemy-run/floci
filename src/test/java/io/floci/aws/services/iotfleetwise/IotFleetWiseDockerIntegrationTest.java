package io.floci.aws.services.iotfleetwise;

import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.iot.IotService;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.floci.aws.services.iotfleetwise.IotFleetWiseIntegrationTest.call;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A campaign's processed data written as gzip-compressed Parquet, IoT FleetWise's default, by the floci-duck
 * sidecar, and read back with DuckDB.
 */
@QuarkusTest
@TestProfile(IotFleetWiseDockerIntegrationTest.FastFlush.class)
@Tag("docker")
class IotFleetWiseDockerIntegrationTest {

    /** The sidecars, with campaign data written to S3 a second after it arrives. */
    public static class FastFlush extends SidecarContainersProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.services.iotfleetwise.s3-flush-seconds", "1");
            overrides.put("floci.services.iotfleetwise.campaign-creation-millis", "100");
            return overrides;
        }
    }

    private static final String ACCOUNT = "000000000000";
    private static final String PREFIX = "arn:aws:iotfleetwise:us-east-1:" + ACCOUNT + ":";
    private static final String BUCKET = "fleetwise-parquet";

    @Inject
    IotService iot;

    @Inject
    FlociDuckClient duck;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification s3() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/s3/aws4_request");
    }

    @Test
    void processedDataIsGzipParquet() throws Exception {
        call("CreateSignalCatalog", "{\"name\":\"Signals\",\"nodes\":[{\"branch\":{\"fullyQualifiedName\":"
                + "\"Vehicle\"}},{\"sensor\":{\"fullyQualifiedName\":\"Vehicle.Speed\",\"dataType\":\"DOUBLE\"}},"
                + "{\"sensor\":{\"fullyQualifiedName\":\"Vehicle.Odometer\",\"dataType\":\"UINT32\"}}]}")
                .statusCode(200);
        call("CreateModelManifest", "{\"name\":\"Model\",\"signalCatalogArn\":\"" + PREFIX + "signal-catalog/Signals"
                + "\",\"nodes\":[\"Vehicle.Speed\",\"Vehicle.Odometer\"]}").statusCode(200);
        call("UpdateModelManifest", "{\"name\":\"Model\",\"status\":\"ACTIVE\"}").statusCode(200);
        String obd = "{\"pidResponseLength\":1,\"serviceMode\":1,\"pid\":%d,\"scaling\":1,\"offset\":0,"
                + "\"startByte\":0,\"byteLength\":1}";
        call("CreateDecoderManifest", "{\"name\":\"Decoder\",\"modelManifestArn\":\"" + PREFIX
                + "model-manifest/Model\",\"networkInterfaces\":[{\"interfaceId\":\"obd\",\"type\":\"OBD_INTERFACE\","
                + "\"obdInterface\":{\"name\":\"obd\",\"requestMessageId\":2015}}],\"signalDecoders\":["
                + "{\"fullyQualifiedName\":\"Vehicle.Speed\",\"type\":\"OBD_SIGNAL\",\"interfaceId\":\"obd\","
                + "\"obdSignal\":" + String.format(obd, 13) + "},{\"fullyQualifiedName\":\"Vehicle.Odometer\","
                + "\"type\":\"OBD_SIGNAL\",\"interfaceId\":\"obd\",\"obdSignal\":" + String.format(obd, 166) + "}]}")
                .statusCode(200);
        call("UpdateDecoderManifest", "{\"name\":\"Decoder\",\"status\":\"ACTIVE\"}").statusCode(200);
        call("CreateVehicle", "{\"vehicleName\":\"truck\",\"modelManifestArn\":\"" + PREFIX + "model-manifest/Model"
                + "\",\"decoderManifestArn\":\"" + PREFIX + "decoder-manifest/Decoder\"}").statusCode(200);
        s3().put("/" + BUCKET).then().statusCode(200);
        s3().body("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"iotfleetwise.amazonaws.com\"},\"Action\":[\"s3:GetBucketLocation\",\"s3:PutObject\"],"
                + "\"Resource\":[\"arn:aws:s3:::" + BUCKET + "\",\"arn:aws:s3:::" + BUCKET + "/*\"]}]}")
                .put("/" + BUCKET + "?policy").then().statusCode(lessThan(300));
        call("CreateCampaign", "{\"name\":\"Trips\",\"signalCatalogArn\":\"" + PREFIX + "signal-catalog/Signals\","
                + "\"targetArn\":\"" + PREFIX + "vehicle/truck\",\"collectionScheme\":{\"timeBasedCollectionScheme\":"
                + "{\"periodMs\":10000}},\"signalsToCollect\":[{\"name\":\"Vehicle.*\"}],\"compression\":\"OFF\","
                + "\"dataDestinationConfigs\":[{\"s3Config\":{\"bucketArn\":\"arn:aws:s3:::" + BUCKET + "\"}}]}")
                .statusCode(200);
        Thread.sleep(300);
        call("UpdateCampaign", "{\"name\":\"Trips\",\"action\":\"APPROVE\"}").statusCode(200)
                .body("status", equalTo("RUNNING"));

        Map<String, Integer> ids = new HashMap<>();
        for (String name : List.of("Vehicle.Speed", "Vehicle.Odometer")) {
            ids.put(name, ids.size() + 2);
        }
        byte[] data = new IotFleetWiseProto.Writer().string(1, PREFIX + "campaign/Trips")
                .string(2, PREFIX + "decoder-manifest/Decoder").uint(3, 7).uint(4, 1_700_000_000_000L)
                .message(5, new IotFleetWiseProto.Writer().uintAlways(2, ids.get("Vehicle.Speed")).dblAlways(3, 61.5))
                .message(5, new IotFleetWiseProto.Writer().uintAlways(2, ids.get("Vehicle.Odometer"))
                        .dblAlways(3, 120034))
                .bytes();
        iot.publish(IotFleetWiseEdge.PREFIX + "truck/signals", data, false, 0, "us-east-1", "truck");

        String key = null;
        long deadline = System.currentTimeMillis() + 60_000;
        while (key == null && System.currentTimeMillis() < deadline) {
            String listing = s3().queryParam("prefix", "processed-data/").get("/" + BUCKET).then().statusCode(200)
                    .extract().asString();
            int at = listing.indexOf("processed-data/year=");
            if (at >= 0 && listing.contains(".gz.parquet")) {
                key = listing.substring(at, listing.indexOf("</Key>", at));
            } else {
                Thread.sleep(500);
            }
        }
        if (key == null) {
            fail("No Parquet object was written");
        }
        assertTrue(key.matches("processed-data/year=\\d{4}/month=\\d{2}/date=\\d{2}/hour=\\d{2}/part-0000-"
                + "[0-9a-f-]{36}\\.gz\\.parquet"), key);
        List<Map<String, Object>> rows = duck.query("SELECT name, measure_value_DOUBLE, measure_value_BIGINT, "
                + "vehicleName FROM read_parquet('s3://" + BUCKET + "/" + key + "') ORDER BY name", null);
        assertEquals(2, rows.size());
        assertEquals("Vehicle.Odometer", rows.get(0).get("name"));
        assertEquals(120034L, ((Number) rows.get(0).get("measure_value_BIGINT")).longValue());
        assertEquals(61.5, ((Number) rows.get(1).get("measure_value_DOUBLE")).doubleValue());
        assertEquals("truck", rows.get(1).get("vehicleName"));
    }
}
