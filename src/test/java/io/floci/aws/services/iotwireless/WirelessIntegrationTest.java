package io.floci.aws.services.iotwireless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * IoT Wireless over REST, and its network server driven by a simulated LoRa Basics Station gateway: an OTAA device
 * joins, sends a confirmed uplink delivered through its destination's IoT rule to SQS, and gets its queued downlink
 * and the ACK in RX1.
 */
@QuarkusTest
class WirelessIntegrationTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String ACCOUNT = "000000000000";
    static final String APP_KEY = "000102030405060708090a0b0c0d0e0f";

    @Inject
    IamService iam;

    @Inject
    S3Service s3;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static RequestSpecification iw() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/iotwireless"
                + "/aws4_request").contentType("application/json");
    }

    static JsonNode json(ValidatableResponse response) {
        try {
            return MAPPER.readTree(response.extract().asString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String role(String name) {
        return iam.createRole(name, "/", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"iotwireless.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}", null,
                3600, Map.of()).getArn();
    }

    static String queue(String name) {
        return given().contentType("application/x-www-form-urlencoded").formParam("Action", "CreateQueue")
                .formParam("QueueName", name).post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    static void ruleToQueue(String rule, String queueUrl) {
        given().contentType("application/json").body("{\"topicRulePayload\":{\"sql\":\"SELECT * FROM "
                + "'iotwireless/uplink'\",\"ruleDisabled\":false,\"actions\":[{\"sqs\":{\"roleArn\":"
                + "\"arn:aws:iam::000000000000:role/rule\",\"queueUrl\":\"" + queueUrl + "\"}}]}}")
                .put("/rules/" + rule).then().statusCode(200);
    }

    static JsonNode receive(String queueUrl) {
        for (int i = 0; i < 40; i++) {
            String body = given().contentType("application/x-www-form-urlencoded").formParam("Action",
                    "ReceiveMessage").formParam("QueueUrl", queueUrl).formParam("WaitTimeSeconds", "1").post("/")
                    .then().statusCode(200).extract().xmlPath()
                    .getString("ReceiveMessageResponse.ReceiveMessageResult.Message.Body");
            if (body != null && !body.isBlank()) {
                try {
                    return MAPPER.readTree(body);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        fail("nothing reached " + queueUrl);
        return null;
    }

    /** Profiles and a destination whose rule writes uplinks to a queue. */
    record Setup(String deviceProfile, String serviceProfile, String destination, String queueUrl) {
    }

    Setup setup(String name, boolean join) {
        String queueUrl = queue("iw-" + name);
        String rule = "iw_" + name.replace("-", "_");
        ruleToQueue(rule, queueUrl);
        String destination = "dest-" + name;
        iw().body("{\"Name\":\"" + destination + "\",\"ExpressionType\":\"RuleName\",\"Expression\":\"" + rule
                + "\",\"RoleArn\":\"" + role("iw-" + name) + "\"}").post("/destinations").then().statusCode(201);
        String deviceProfile = iw().body("{\"Name\":\"" + name + "\",\"LoRaWAN\":{\"MacVersion\":\"1.0.3\","
                + "\"RegParamsRevision\":\"RP002-1.0.1\",\"RfRegion\":\"US915\",\"SupportsJoin\":" + join + "}}")
                .post("/device-profiles").then().statusCode(201).extract().path("Id");
        String serviceProfile = iw().body("{\"Name\":\"" + name + "\",\"LoRaWAN\":{\"AddGwMetadata\":true}}")
                .post("/service-profiles").then().statusCode(201).extract().path("Id");
        return new Setup(deviceProfile, serviceProfile, destination, queueUrl);
    }

    // ---------------------------------------------------------------- control plane

    @Test
    void resourcesAreValidatedAndInUseResourcesKept() {
        Setup setup = setup("crud", false);
        iw().body("{\"Name\":\"bad\",\"ExpressionType\":\"RuleName\",\"Expression\":\"x\",\"RoleArn\":"
                + "\"arn:aws:iam::000000000000:role/missing\"}").post("/destinations").then().statusCode(400)
                .header("x-amzn-ErrorType", "ValidationException");
        iw().body("{\"Type\":\"LoRaWAN\",\"DestinationName\":\"" + setup.destination() + "\",\"LoRaWAN\":{\"DevEui\":"
                + "\"0102030405060708\",\"DeviceProfileId\":\"" + setup.deviceProfile() + "\",\"ServiceProfileId\":\""
                + setup.serviceProfile() + "\",\"OtaaV1_0_x\":{\"AppKey\":\"" + APP_KEY + "\",\"AppEui\":"
                + "\"0101010101010101\"}}}").post("/wireless-devices").then().statusCode(400)
                .body("Message", containsString("doesn't support join"));
        String device = iw().body("{\"Type\":\"LoRaWAN\",\"Name\":\"abp\",\"DestinationName\":\""
                + setup.destination() + "\",\"LoRaWAN\":{\"DevEui\":\"0102030405060708\",\"DeviceProfileId\":\""
                + setup.deviceProfile() + "\",\"ServiceProfileId\":\"" + setup.serviceProfile() + "\",\"AbpV1_0_x\":"
                + "{\"DevAddr\":\"26011bda\",\"SessionKeys\":{\"NwkSKey\":\"" + APP_KEY + "\",\"AppSKey\":\""
                + APP_KEY + "\"}}},\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}").post("/wireless-devices")
                .then().statusCode(201).extract().path("Id");
        iw().body("{\"Type\":\"LoRaWAN\",\"DestinationName\":\"" + setup.destination() + "\",\"LoRaWAN\":{\"DevEui\":"
                + "\"0102030405060708\",\"DeviceProfileId\":\"" + setup.deviceProfile() + "\",\"ServiceProfileId\":\""
                + setup.serviceProfile() + "\",\"AbpV1_0_x\":{\"DevAddr\":\"26011bdb\",\"SessionKeys\":{\"NwkSKey\":\""
                + APP_KEY + "\",\"AppSKey\":\"" + APP_KEY + "\"}}}}").post("/wireless-devices").then().statusCode(409);
        iw().get("/wireless-devices/0102030405060708?identifierType=DevEui").then().statusCode(200)
                .body("Id", equalTo(device)).body("LoRaWAN.AbpV1_0_x.DevAddr", equalTo("26011bda"));
        iw().delete("/device-profiles/" + setup.deviceProfile()).then().statusCode(409);
        iw().delete("/destinations/" + setup.destination()).then().statusCode(409);
        String arn = iw().get("/wireless-devices/" + device + "?identifierType=WirelessDeviceId").then()
                .extract().path("Arn");
        iw().get("/tags?resourceArn=" + arn).then().statusCode(200).body("Tags[0].Key", equalTo("env"));
        iw().body("{\"Description\":\"updated\",\"LoRaWAN\":{\"AbpV1_0_x\":{\"FCntStart\":10}}}")
                .patch("/wireless-devices/" + device).then().statusCode(204);
        iw().get("/wireless-devices?destinationName=" + setup.destination()).then().statusCode(200)
                .body("WirelessDeviceList.Id", hasItem(device));
        iw().delete("/wireless-devices/" + device).then().statusCode(204);
        iw().delete("/device-profiles/" + setup.deviceProfile()).then().statusCode(204);
        iw().get("/device-profiles/" + setup.deviceProfile()).then().statusCode(404)
                .header("x-amzn-ErrorType", "ResourceNotFoundException");

        String gateway = iw().body("{\"Name\":\"gw\",\"LoRaWAN\":{\"GatewayEui\":\"aa555a0000000999\","
                + "\"RfRegion\":\"US915\"}}").post("/wireless-gateways").then().statusCode(201).extract().path("Id");
        iw().body("{\"LoRaWAN\":{\"GatewayEui\":\"AA555A0000000999\",\"RfRegion\":\"US915\"}}")
                .post("/wireless-gateways").then().statusCode(409);
        iw().body("{\"MaxEirp\":27}").patch("/wireless-gateways/" + gateway).then().statusCode(204);
        iw().get("/wireless-gateways/aa555a0000000999?identifierType=GatewayEui").then().statusCode(200)
                .body("LoRaWAN.MaxEirp", equalTo(27.0f));
        iw().get("/wireless-gateways/" + gateway + "/statistics").then().statusCode(200)
                .body("ConnectionStatus", equalTo("Disconnected"));
        iw().get("/service-endpoint?serviceType=LNS").then().statusCode(200)
                .body("ServiceEndpoint", containsString("/_floci/iotwireless/us-east-1/lns"))
                .body("ServerTrust", containsString("BEGIN CERTIFICATE"));
    }

    // ---------------------------------------------------------------- network server

    /** A Basics Station gateway: the traffic WebSocket, and the messages the network server sent it. */
    static final class Station implements WebSocket.Listener {
        final LinkedBlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();
        WebSocket socket;

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    received.add(MAPPER.readTree(partial.toString()));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        JsonNode next(String type) throws InterruptedException {
            while (true) {
                JsonNode message = received.poll(10, TimeUnit.SECONDS);
                if (message == null) {
                    fail("no " + type + " message arrived");
                }
                if (type.equals(message.path("msgtype").asText())) {
                    return message;
                }
            }
        }

        void send(ObjectNode message) {
            socket.sendText(message.toString(), true).join();
        }
    }

    static Station connect(String eui) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String base = "ws://localhost:" + RestAssured.port + WirelessStation.PATH + "us-east-1/lns";
        CompletableFuture<String> info = new CompletableFuture<>();
        WebSocket router = client.newWebSocketBuilder().buildAsync(URI.create(base + "/router-info"),
                new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        info.complete(data.toString());
                        return null;
                    }
                }).get(10, TimeUnit.SECONDS);
        router.sendText("{\"router\":\"" + eui + "\"}", true).join();
        JsonNode reply = MAPPER.readTree(info.get(10, TimeUnit.SECONDS));
        assertNotNull(reply.get("uri"), reply.toString());
        Station station = new Station();
        station.socket = client.newWebSocketBuilder().buildAsync(URI.create(reply.get("uri").asText()), station)
                .get(10, TimeUnit.SECONDS);
        station.send(MAPPER.createObjectNode().put("msgtype", "version").put("station", "2.0.6(rpi/std)")
                .put("firmware", "1.0").put("package", "1.0.0").put("model", "rpi").put("protocol", 2));
        return station;
    }

    static ObjectNode upinfo(ObjectNode message) {
        message.put("DR", 3).put("Freq", 904_300_000).put("RefTime", 0.0);
        message.putObject("upinfo").put("rctx", 0).put("xtime", 12_345_678L).put("gpstime", 0).put("rssi", -56)
                .put("snr", 9.5);
        return message;
    }

    @Test
    void otaaDeviceJoinsSendsAndReceives() throws Exception {
        Setup setup = setup("otaa", true);
        String devEui = "0004a30b001c0530";
        String joinEui = "70b3d57ed0000001";
        String device = iw().body("{\"Type\":\"LoRaWAN\",\"DestinationName\":\"" + setup.destination()
                + "\",\"LoRaWAN\":{\"DevEui\":\"" + devEui + "\",\"DeviceProfileId\":\"" + setup.deviceProfile()
                + "\",\"ServiceProfileId\":\"" + setup.serviceProfile() + "\",\"OtaaV1_0_x\":{\"AppKey\":\"" + APP_KEY
                + "\",\"AppEui\":\"" + joinEui + "\"}}}").post("/wireless-devices").then().statusCode(201).extract()
                .path("Id");
        String gateway = iw().body("{\"LoRaWAN\":{\"GatewayEui\":\"aa555a0000000101\",\"RfRegion\":\"US915\"}}")
                .post("/wireless-gateways").then().statusCode(201).extract().path("Id");

        Station station = connect("aa555a0000000101");
        JsonNode config = station.next("router_config");
        assertEquals("US902", config.get("region").asText());
        iw().get("/wireless-gateways/" + gateway + "/statistics").then().statusCode(200)
                .body("ConnectionStatus", equalTo("Connected"));

        // Join: the device's request, the server's accept on RX1 with the join accept delay.
        byte[] appKey = LoRaWan.hex(APP_KEY);
        byte[] request = LoRaWan.buildJoinRequest(joinEui, devEui, 1, appKey);
        LoRaWan.JoinRequest join = LoRaWan.joinRequest(request);
        station.send(upinfo(MAPPER.createObjectNode().put("msgtype", "jreq").put("MHdr", 0)
                .put("JoinEui", "70-b3-d5-7e-d0-00-00-01").put("DevEui", "00-04-a3-0b-00-1c-05-30")
                .put("DevNonce", 1).put("MIC", join.mic())));
        JsonNode accept = station.next("dnmsg");
        assertEquals(5, accept.get("RxDelay").asInt());
        assertEquals(924_500_000L, accept.get("RX1Freq").asLong());
        byte[] open = LoRaWan.openJoinAccept(LoRaWan.hex(accept.get("pdu").asText()), appKey);
        assertArrayEquals(Arrays.copyOf(LoRaWan.cmac(appKey, Arrays.copyOf(open, 13)), 4),
                Arrays.copyOfRange(open, 13, 17));
        int joinNonce = (open[1] & 0xFF) | (open[2] & 0xFF) << 8 | (open[3] & 0xFF) << 16;
        int netId = (open[4] & 0xFF) | (open[5] & 0xFF) << 8 | (open[6] & 0xFF) << 16;
        String devAddr = LoRaWan.hex(LoRaWan.reverse(Arrays.copyOfRange(open, 7, 11)));
        LoRaWan.SessionKeys keys = LoRaWan.deriveV10(appKey, joinNonce, netId, 1);
        // A replayed DevNonce is refused.
        station.send(upinfo(MAPPER.createObjectNode().put("msgtype", "jreq").put("MHdr", 0)
                .put("JoinEui", joinEui).put("DevEui", devEui).put("DevNonce", 1).put("MIC", join.mic())));

        // Queue a downlink, then a confirmed uplink: it's delivered, acknowledged, and answered with the downlink.
        iw().body("{\"TransmitMode\":0,\"PayloadData\":\"" + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3})
                + "\",\"WirelessMetadata\":{\"LoRaWAN\":{\"FPort\":5}}}").post("/wireless-devices/" + device + "/data")
                .then().statusCode(202).body("MessageId", notNullValue());
        iw().get("/wireless-devices/" + device + "/data").then().statusCode(200)
                .body("DownlinkQueueMessagesList", hasSize(1));
        byte[] uplink = LoRaWan.buildUplink(true, devAddr, 0, 2, "hi".getBytes(StandardCharsets.UTF_8), keys, false);
        LoRaWan.DataFrame frame = LoRaWan.dataFrame(uplink);
        station.send(upinfo(MAPPER.createObjectNode().put("msgtype", "updf").put("MHdr", uplink[0] & 0xFF)
                .put("DevAddr", (int) Long.parseLong(devAddr, 16)).put("FCtrl", 0).put("FCnt", 0).put("FOpts", "")
                .put("FPort", 2).put("FRMPayload", LoRaWan.hex(frame.payload())).put("MIC", frame.mic())));
        JsonNode downlink = station.next("dnmsg");
        LoRaWan.DataFrame answer = LoRaWan.dataFrame(LoRaWan.hex(downlink.get("pdu").asText()));
        assertTrue(answer.ack());
        assertEquals(5, answer.fPort());
        assertArrayEquals(new byte[] {1, 2, 3}, LoRaWan.crypt(keys.appSKey(), 1, devAddr, 0, answer.payload()));
        assertEquals(answer.mic(), LoRaWan.downlinkMic(LoRaWan.hex(downlink.get("pdu").asText()), devAddr, 0, keys,
                false, 0));
        iw().get("/wireless-devices/" + device + "/data").then().statusCode(200)
                .body("DownlinkQueueMessagesList", empty());

        JsonNode delivered = receive(setup.queueUrl());
        assertEquals(device, delivered.get("WirelessDeviceId").asText());
        assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8),
                Base64.getDecoder().decode(delivered.get("PayloadData").asText()));
        JsonNode metadata = delivered.at("/WirelessMetadata/LoRaWAN");
        assertEquals("ConfirmedDataUp", metadata.get("MType").asText());
        assertEquals("aa555a0000000101", metadata.at("/Gateways/0/GatewayEui").asText());
        assertEquals(7, metadata.get("SpreadingFactor").asInt());

        iw().get("/wireless-devices/" + device + "/statistics").then().statusCode(200)
                .body("LoRaWAN.FPort", equalTo(2)).body("LoRaWAN.Gateways[0].Rssi", equalTo(-56.0f))
                .body("LastUplinkReceivedAt", notNullValue());
        // The same frame counter again is a replay.
        station.send(upinfo(MAPPER.createObjectNode().put("msgtype", "updf").put("MHdr", uplink[0] & 0xFF)
                .put("DevAddr", (int) Long.parseLong(devAddr, 16)).put("FCtrl", 0).put("FCnt", 0).put("FOpts", "")
                .put("FPort", 2).put("FRMPayload", LoRaWan.hex(frame.payload())).put("MIC", frame.mic())));
        station.socket.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
        for (int i = 0; i < 50; i++) {
            if ("Disconnected".equals(iw().get("/wireless-gateways/" + gateway + "/statistics").then().extract()
                    .path("ConnectionStatus"))) {
                return;
            }
            Thread.sleep(100);
        }
        fail("the gateway never disconnected");
    }

    @Test
    void testDeviceAndQueuesAndPositions() {
        Setup setup = setup("test-device", false);
        String device = iw().body("{\"Type\":\"LoRaWAN\",\"DestinationName\":\"" + setup.destination()
                + "\",\"LoRaWAN\":{\"DevEui\":\"1a2b3c4d5e6f7091\",\"DeviceProfileId\":\"" + setup.deviceProfile()
                + "\",\"ServiceProfileId\":\"" + setup.serviceProfile() + "\",\"AbpV1_0_x\":{\"DevAddr\":\"01020304\","
                + "\"SessionKeys\":{\"NwkSKey\":\"" + APP_KEY + "\",\"AppSKey\":\"" + APP_KEY + "\"}}}}")
                .post("/wireless-devices").then().statusCode(201).extract().path("Id");
        iw().post("/wireless-devices/" + device + "/test").then().statusCode(200)
                .body("Result", containsString("succeeded"));
        JsonNode delivered = receive(setup.queueUrl());
        assertEquals("Hello", new String(Base64.getDecoder().decode(delivered.get("PayloadData").asText()),
                StandardCharsets.UTF_8));

        for (int i = 0; i < 2; i++) {
            iw().body("{\"TransmitMode\":1,\"PayloadData\":\"SGVsbG8=\",\"WirelessMetadata\":{\"LoRaWAN\":"
                    + "{\"FPort\":1}}}").post("/wireless-devices/" + device + "/data").then().statusCode(202);
        }
        String first = iw().get("/wireless-devices/" + device + "/data").then().statusCode(200)
                .body("DownlinkQueueMessagesList", hasSize(2)).extract().path("DownlinkQueueMessagesList[0].MessageId");
        iw().delete("/wireless-devices/" + device + "/data?messageId=" + first).then().statusCode(204);
        iw().delete("/wireless-devices/" + device + "/data?messageId=*").then().statusCode(204);
        iw().get("/wireless-devices/" + device + "/data").then().statusCode(200)
                .body("DownlinkQueueMessagesList", empty());

        iw().get("/resource-positions/" + device + "?resourceType=WirelessDevice").then().statusCode(404);
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/iotwireless"
                + "/aws4_request").contentType("application/octet-stream")
                .body("{\"type\":\"Point\",\"coordinates\":[-122.33,47.61,10]}".getBytes(StandardCharsets.UTF_8))
                .patch("/resource-positions/" + device + "?resourceType=WirelessDevice").then().statusCode(204);
        String geoJson = iw().get("/resource-positions/" + device + "?resourceType=WirelessDevice").then()
                .statusCode(200).extract().asString();
        assertTrue(geoJson.contains("-122.33"), geoJson);
        iw().body("{\"WiFiAccessPoints\":[{\"MacAddress\":\"A0:EC:F9:1E:32:C1\",\"Rss\":-66}]}")
                .post("/position-estimate").then().statusCode(404)
                .header("x-amzn-ErrorType", "ResourceNotFoundException");
        iw().body("{\"Solvers\":{\"SemtechGnss\":{\"Status\":\"Enabled\",\"Fec\":\"ROSE\"}},\"Destination\":\""
                + setup.destination() + "\"}").put("/position-configurations/" + device
                + "?resourceType=WirelessDevice").then().statusCode(200);
        iw().get("/position-configurations?resourceType=WirelessDevice").then().statusCode(200)
                .body("PositionConfigurationList.ResourceIdentifier", hasItem(device));
    }

    @Test
    void settingsMulticastFuotaAndPartners() {
        iw().body("{\"Join\":{\"LoRaWAN\":{\"WirelessDeviceEventTopic\":\"Enabled\"}}}")
                .patch("/event-configurations-resource-types").then().statusCode(204);
        iw().get("/event-configurations-resource-types").then().statusCode(200)
                .body("Join.LoRaWAN.WirelessDeviceEventTopic", equalTo("Enabled"))
                .body("ConnectionStatus.LoRaWAN.WirelessGatewayEventTopic", equalTo("Disabled"));
        iw().body("{\"DefaultLogLevel\":\"INFO\"}").post("/log-levels").then().statusCode(200);
        iw().get("/log-levels").then().statusCode(200).body("DefaultLogLevel", equalTo("INFO"));
        iw().body("{\"SummaryMetric\":{\"Status\":\"Enabled\"}}").put("/metric-configuration").then().statusCode(204);
        iw().get("/metric-configuration").then().statusCode(200).body("SummaryMetric.Status", equalTo("Enabled"));
        iw().body("{\"SummaryMetricQueries\":[{\"QueryId\":\"q\",\"MetricName\":\"AwsAccountUplinkCount\","
                + "\"AggregationPeriod\":\"OneHour\"}]}").post("/metrics").then().statusCode(200)
                .body("SummaryMetricQueryResults[0].QueryStatus", equalTo("Succeeded"));

        String group = iw().body("{\"Name\":\"floor-1\",\"LoRaWAN\":{\"RfRegion\":\"US915\",\"DlClass\":\"ClassC\"}}")
                .post("/multicast-groups").then().statusCode(201).extract().path("Id");
        iw().get("/multicast-groups/" + group).then().statusCode(200).body("Status", equalTo("Pending"))
                .body("LoRaWAN.NumberOfDevicesRequested", equalTo(0));
        iw().body("{\"PayloadData\":\"AQ==\",\"WirelessMetadata\":{\"LoRaWAN\":{\"FPort\":1}}}")
                .post("/multicast-groups/" + group + "/data").then().statusCode(400);
        iw().body("{\"LoRaWAN\":{\"SessionStartTime\":\"2026-01-01T00:00:00Z\",\"SessionTimeout\":60}}")
                .put("/multicast-groups/" + group + "/session").then().statusCode(204);
        iw().get("/multicast-groups/" + group + "/session").then().statusCode(200)
                .body("LoRaWAN.DlFreq", equalTo(923_300_000));

        String fuota = iw().body("{\"Name\":\"fw\",\"FirmwareUpdateImage\":\"s3://fw/image.bin\","
                + "\"FirmwareUpdateRole\":\"arn:aws:iam::000000000000:role/fw\"}").post("/fuota-tasks").then()
                .statusCode(201).extract().path("Id");
        iw().put("/fuota-tasks/" + fuota).then().statusCode(400);
        iw().body("{\"MulticastGroupId\":\"" + group + "\"}").put("/fuota-tasks/" + fuota + "/multicast-group").then()
                .statusCode(204);
        iw().get("/fuota-tasks/" + fuota + "/multicast-groups").then().statusCode(200)
                .body("MulticastGroupList[0].Id", equalTo(group));

        iw().body("{\"Sidewalk\":{\"AmazonId\":\"amzn-1\",\"AppServerPrivateKey\":\"" + APP_KEY + APP_KEY + "\"}}")
                .post("/partner-accounts").then().statusCode(200).body("Arn", containsString("SidewalkAccount"));
        iw().get("/partner-accounts/amzn-1?partnerType=Sidewalk").then().statusCode(200)
                .body("Sidewalk.Fingerprint", notNullValue()).body("AccountLinked", equalTo(true));
        iw().body("{\"Name\":\"analyzer\",\"TraceContent\":{\"WirelessDeviceFrameInfo\":\"ENABLED\"}}")
                .post("/network-analyzer-configurations").then().statusCode(201);
        iw().get("/network-analyzer-configurations/analyzer").then().statusCode(200)
                .body("TraceContent.WirelessDeviceFrameInfo", equalTo("ENABLED"));
        iw().get("/network-analyzer-configurations").then().statusCode(200)
                .body("NetworkAnalyzerConfigurationList.Name", equalTo(List.of("analyzer")));
    }

    @Test
    void cupsHandsOutTheLnsAndTrust() throws Exception {
        iw().body("{\"LoRaWAN\":{\"GatewayEui\":\"aa555a0000000777\",\"RfRegion\":\"EU868\"}}")
                .post("/wireless-gateways").then().statusCode(201);
        byte[] response = given().contentType("application/json").body("{\"router\":\"aa555a0000000777\","
                + "\"cupsUri\":\"\",\"tcUri\":\"\",\"cupsCredCrc\":0,\"tcCredCrc\":0,\"station\":\"2.0.6\","
                + "\"model\":\"rpi\",\"package\":\"1.0.0\",\"keys\":[]}").post(WirelessStation.PATH
                + "eu-west-1/cups/update-info").then().statusCode(404).extract().asByteArray();
        assertNotNull(response);
        byte[] update = given().contentType("application/json").body("{\"router\":\"aa555a0000000777\","
                + "\"cupsUri\":\"\",\"tcUri\":\"\",\"cupsCredCrc\":0,\"tcCredCrc\":0,\"station\":\"2.0.6\","
                + "\"model\":\"rpi\",\"package\":\"1.0.0\",\"keys\":[]}").post(WirelessStation.PATH
                + "us-east-1/cups/update-info").then().statusCode(200).extract().asByteArray();
        int cupsLength = update[0] & 0xFF;
        String cups = new String(update, 1, cupsLength, StandardCharsets.UTF_8);
        int tcLength = update[1 + cupsLength] & 0xFF;
        String tc = new String(update, 2 + cupsLength, tcLength, StandardCharsets.UTF_8);
        assertTrue(cups.endsWith("/us-east-1/cups"), cups);
        assertTrue(tc.startsWith("ws://") && tc.endsWith("/us-east-1/lns"), tc);
        int credential = (update[2 + cupsLength + tcLength] & 0xFF) | (update[3 + cupsLength + tcLength] & 0xFF) << 8;
        assertTrue(credential > 100);
    }

    static byte[] updateInfo(String eui, String packageVersion) {
        return given().contentType("application/json").body("{\"router\":\"" + eui + "\",\"cupsUri\":\"\","
                + "\"tcUri\":\"\",\"cupsCredCrc\":0,\"tcCredCrc\":0,\"station\":\"2.0.6\",\"model\":\"rpi\","
                + "\"package\":\"" + packageVersion + "\",\"keys\":[]}").post(WirelessStation.PATH
                + "us-east-1/cups/update-info").then().statusCode(200).extract().asByteArray();
    }

    /** The update data at the end of a CUPS response. */
    static byte[] updateData(byte[] response) {
        int at = 0;
        at += 1 + (response[at] & 0xFF);
        at += 1 + (response[at] & 0xFF);
        at += 2 + ((response[at] & 0xFF) | (response[at + 1] & 0xFF) << 8);
        at += 2 + ((response[at] & 0xFF) | (response[at + 1] & 0xFF) << 8);
        int sigLength = (response[at] & 0xFF) | (response[at + 1] & 0xFF) << 8 | (response[at + 2] & 0xFF) << 16;
        at += 4 + sigLength;
        int length = (response[at] & 0xFF) | (response[at + 1] & 0xFF) << 8 | (response[at + 2] & 0xFF) << 16;
        return Arrays.copyOfRange(response, at + 4, at + 4 + length);
    }

    @Test
    void gatewayUpdatesAreDeliveredOverCups() {
        s3.createBucket("iw-firmware", "us-east-1");
        s3.putObject("iw-firmware", "station-2.bin", "new-firmware".getBytes(StandardCharsets.UTF_8),
                "application/octet-stream", Map.of());
        String gateway = iw().body("{\"LoRaWAN\":{\"GatewayEui\":\"aa555a0000000888\",\"RfRegion\":\"US915\"}}")
                .post("/wireless-gateways").then().statusCode(201).extract().path("Id");
        updateInfo("aa555a0000000888", "1.0.0");
        iw().get("/wireless-gateways/" + gateway + "/firmware-information").then().statusCode(200)
                .body("LoRaWAN.CurrentVersion.PackageVersion", equalTo("1.0.0"));
        String definition = iw().body("{\"AutoCreateTasks\":false,\"Name\":\"upgrade\",\"Update\":{"
                + "\"UpdateDataSource\":\"s3://iw-firmware/station-2.bin\",\"UpdateDataRole\":"
                + "\"arn:aws:iam::000000000000:role/fw\",\"LoRaWAN\":{\"UpdateSignature\":\"c2lnbmF0dXJl\","
                + "\"SigKeyCrc\":12345,\"CurrentVersion\":{\"PackageVersion\":\"1.0.0\",\"Model\":\"rpi\","
                + "\"Station\":\"2.0.6\"},\"UpdateVersion\":{\"PackageVersion\":\"2.0.0\",\"Model\":\"rpi\","
                + "\"Station\":\"2.0.6\"}}}}").post("/wireless-gateway-task-definitions").then().statusCode(201)
                .extract().path("Id");
        iw().body("{\"WirelessGatewayTaskDefinitionId\":\"" + definition + "\"}")
                .post("/wireless-gateways/" + gateway + "/tasks").then().statusCode(201)
                .body("Status", equalTo("PENDING"));
        byte[] response = updateInfo("aa555a0000000888", "1.0.0");
        assertEquals("new-firmware", new String(updateData(response), StandardCharsets.UTF_8));
        iw().get("/wireless-gateways/" + gateway + "/tasks").then().statusCode(200)
                .body("Status", equalTo("IN_PROGRESS"));
        updateInfo("aa555a0000000888", "2.0.0");
        iw().get("/wireless-gateways/" + gateway + "/tasks").then().statusCode(200)
                .body("Status", equalTo("COMPLETED"));
        iw().get("/wireless-gateway-task-definitions").then().statusCode(200)
                .body("TaskDefinitions.Id", hasItem(definition));
    }

    @Test
    void multicastSessionsGoOutThroughGateways() throws Exception {
        String gateway = iw().body("{\"LoRaWAN\":{\"GatewayEui\":\"aa555a0000000202\",\"RfRegion\":\"US915\"}}")
                .post("/wireless-gateways").then().statusCode(201).extract().path("Id");
        Station station = connect("aa555a0000000202");
        station.next("router_config");
        String group = iw().body("{\"LoRaWAN\":{\"RfRegion\":\"US915\",\"DlClass\":\"ClassC\",\"ParticipatingGateways\":"
                + "{\"GatewayList\":[\"" + gateway + "\"]}}}").post("/multicast-groups").then().statusCode(201)
                .extract().path("Id");
        iw().body("{\"LoRaWAN\":{\"SessionStartTime\":\"" + Instant.now().minusSeconds(5)
                + "\",\"SessionTimeout\":600,\"DlDr\":8,\"DlFreq\":923300000}}")
                .put("/multicast-groups/" + group + "/session").then().statusCode(204);
        iw().body("{\"PayloadData\":\"AQID\",\"WirelessMetadata\":{\"LoRaWAN\":{\"FPort\":10}}}")
                .post("/multicast-groups/" + group + "/data").then().statusCode(201).body("MessageId", notNullValue());
        JsonNode downlink = station.next("dnmsg");
        assertEquals(2, downlink.get("dC").asInt());
        assertEquals(923_300_000L, downlink.get("RX2Freq").asLong());
        LoRaWan.DataFrame frame = LoRaWan.dataFrame(LoRaWan.hex(downlink.get("pdu").asText()));
        assertEquals(10, frame.fPort());
        iw().get("/multicast-groups/" + group).then().statusCode(200).body("Status", equalTo("Session_InProgress"));
        station.socket.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
    }

    @Test
    void sidewalkDevicesAreProvisionedAndImported() {
        String profile = iw().body("{\"Name\":\"sidewalk\",\"Sidewalk\":{}}").post("/device-profiles").then()
                .statusCode(201).extract().path("Id");
        iw().get("/device-profiles/" + profile).then().statusCode(200)
                .body("Sidewalk.ApplicationServerPublicKey", notNullValue())
                .body("Sidewalk.DakCertificateMetadata", hasSize(1));
        String destination = "dest-sidewalk";
        iw().body("{\"Name\":\"" + destination + "\",\"ExpressionType\":\"MqttTopic\",\"Expression\":"
                + "\"sidewalk/uplinks\",\"RoleArn\":\"" + role("iw-sidewalk") + "\"}").post("/destinations").then()
                .statusCode(201);
        String device = iw().body("{\"Type\":\"Sidewalk\",\"DestinationName\":\"" + destination + "\",\"Sidewalk\":"
                + "{\"DeviceProfileId\":\"" + profile + "\",\"SidewalkManufacturingSn\":\"SN-0001\"}}")
                .post("/wireless-devices").then().statusCode(201).extract().path("Id");
        iw().get("/wireless-devices/SN-0001?identifierType=SidewalkManufacturingSn").then().statusCode(200)
                .body("Id", equalTo(device)).body("Sidewalk.Status", equalTo("PROVISIONED"))
                .body("Sidewalk.DeviceCertificates.SigningAlg", equalTo(List.of("Ed25519", "P256r1")));
        iw().post("/wireless-devices/" + device + "/test").then().statusCode(200);

        s3.createBucket("iw-import", "us-east-1");
        s3.putObject("iw-import", "devices.csv", "SidewalkManufacturingSn\nSN-0002\nSN-0003\nSN-0001\n"
                .getBytes(StandardCharsets.UTF_8), "text/csv", Map.of());
        String task = iw().body("{\"DestinationName\":\"" + destination + "\",\"Sidewalk\":{\"DeviceCreationFile\":"
                + "\"s3://iw-import/devices.csv\",\"Role\":\"arn:aws:iam::000000000000:role/import\"}}")
                .post("/wireless_device_import_task").then().statusCode(201).extract().path("Id");
        for (int i = 0; i < 50 && !"COMPLETE".equals(iw().get("/wireless_device_import_task/" + task).then()
                .extract().path("Status")); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        iw().get("/wireless_device_import_task/" + task).then().statusCode(200).body("Status", equalTo("COMPLETE"))
                .body("OnboardedImportedDeviceCount", equalTo(2)).body("FailedImportedDeviceCount", equalTo(1));
        iw().get("/wireless_device_import_task?id=" + task + "&status=FAILED").then().statusCode(200)
                .body("ImportedWirelessDeviceList[0].Sidewalk.SidewalkManufacturingSn", equalTo("SN-0001"));
    }
}
