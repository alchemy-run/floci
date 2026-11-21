package io.floci.aws.services.mediaconnect;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MediaConnect: flows with their sources, outputs, entitlements, media streams and maintenance; starting a flow and
 * its relay carrying a transport stream to an output; gateways, bridges, router resources, offerings and tags.
 */
@QuarkusTest
class MediaConnectIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static RequestSpecification mc() {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/mediaconnect/aws4_request")
                .contentType("application/json");
    }

    /** An ARN in a path: RestAssured encodes it as the SDKs do. */
    static String path(String arn) {
        return arn;
    }

    static ValidatableResponse describe(String flowArn) {
        return mc().get("/v1/flows/" + path(flowArn)).then().log().ifValidationFails();
    }

    static String createFlow(String name, int port, String extra) {
        return mc().body("{\"name\":\"" + name + "\",\"source\":{\"name\":\"primary\",\"protocol\":\"rtp\","
                        + "\"whitelistCidr\":\"127.0.0.0/8\",\"ingestPort\":" + port + "}" + extra + "}")
                .post("/v1/flows").then().log().ifValidationFails().statusCode(201)
                .body("flow.status", equalTo("STANDBY")).extract().path("flow.flowArn");
    }

    static void awaitStatus(String flowArn, String status) {
        await().atMost(Duration.ofSeconds(10)).until(() -> status.equals(describe(flowArn).extract()
                .path("flow.status")));
    }

    @Test
    void flowsCarryTheirSourceOutputsAndEntitlements() {
        String arn = createFlow("lifecycle", 25010, ",\"flowTags\":{\"team\":\"video\"}");
        describe(arn).statusCode(200).body("flow.name", equalTo("lifecycle"))
                .body("flow.availabilityZone", equalTo("us-east-1a"))
                .body("flow.source.sourceArn", containsString(":source:"))
                .body("flow.source.ingestPort", equalTo(25010))
                .body("flow.source.transport.protocol", equalTo("rtp"))
                .body("flow.maintenance.maintenanceDay", notNullValue())
                .body("flow.outputs", hasSize(0));
        mc().body("{\"name\":\"lifecycle\"}").post("/v1/flows").then().statusCode(409);
        mc().get("/v1/flows/" + path(arn.replace("lifecycle", "missing"))).then().statusCode(404)
                .header("x-amzn-ErrorType", containsString("NotFoundException"));

        String output = mc().body("{\"outputs\":[{\"name\":\"east\",\"protocol\":\"rtp\",\"destination\":"
                        + "\"198.51.100.11\",\"port\":5010}]}").post("/v1/flows/" + path(arn) + "/outputs").then()
                .statusCode(201).body("outputs[0].name", equalTo("east")).extract().path("outputs[0].outputArn");
        mc().body("{\"outputs\":[{\"name\":\"bad\",\"protocol\":\"rtp\"}]}").post("/v1/flows/" + path(arn)
                + "/outputs").then().statusCode(400);
        mc().body("{\"port\":5020}").put("/v1/flows/" + path(arn) + "/outputs/" + path(output)).then()
                .statusCode(202).body("output.port", equalTo(5020));
        String source = describe(arn).extract().path("flow.source.sourceArn");
        mc().body("{\"whitelistCidr\":\"10.0.0.0/8\"}").put("/v1/flows/" + path(arn) + "/source/" + path(source))
                .then().statusCode(202).body("source.whitelistCidr", equalTo("10.0.0.0/8"));

        String entitlement = mc().body("{\"entitlements\":[{\"name\":\"partner\",\"subscribers\":"
                        + "[\"111122223333\"]}]}").post("/v1/flows/" + path(arn) + "/entitlements").then()
                .statusCode(200).body("entitlements[0].entitlementStatus", equalTo("ENABLED")).extract()
                .path("entitlements[0].entitlementArn");
        mc().body("{\"entitlements\":[{\"subscribers\":[\"nope\"]}]}").post("/v1/flows/" + path(arn)
                + "/entitlements").then().statusCode(400);
        mc().body("{\"entitlementStatus\":\"DISABLED\"}").put("/v1/flows/" + path(arn) + "/entitlements/"
                + path(entitlement)).then().statusCode(202).body("entitlement.entitlementStatus",
                equalTo("DISABLED"));
        mc().delete("/v1/flows/" + path(arn) + "/entitlements/" + path(entitlement)).then().statusCode(202);

        mc().body("{\"mediaStreams\":[{\"mediaStreamId\":1,\"mediaStreamName\":\"video\",\"mediaStreamType\":"
                + "\"video\"}]}").post("/v1/flows/" + path(arn) + "/mediaStreams").then().statusCode(201)
                .body("mediaStreams[0].clockRate", equalTo(90000)).body("mediaStreams[0].fmt", equalTo(96));
        mc().delete("/v1/flows/" + path(arn) + "/mediaStreams/video").then().statusCode(200);

        mc().body("{\"maintenance\":{\"maintenanceDay\":\"Tuesday\",\"maintenanceStartHour\":\"04:00\"}}")
                .put("/v1/flows/" + path(arn)).then().statusCode(202)
                .body("flow.maintenance.maintenanceDay", equalTo("Tuesday"));
        mc().get("/tags/" + path(arn)).then().statusCode(200).body("tags.team", equalTo("video"));
        mc().body("{\"tags\":{\"stage\":\"test\"}}").post("/tags/" + path(arn)).then().statusCode(204);
        mc().delete("/tags/" + path(arn) + "?tagKeys=team").then().statusCode(204);
        mc().get("/tags/" + path(arn)).then().body("tags.stage", equalTo("test")).body("tags.team", nullValue());

        mc().delete("/v1/flows/" + path(arn) + "/outputs/" + path(output)).then().statusCode(202);
        mc().get("/v1/flows?maxResults=20").then().statusCode(200).body("flows.name", hasItem("lifecycle"));
        mc().post("/v1/flows/stop/" + path(arn)).then().statusCode(400)
                .header("x-amzn-ErrorType", containsString("BadRequestException"));
        mc().delete("/v1/flows/" + path(arn)).then().statusCode(202).body("status", equalTo("DELETING"));
        await().atMost(Duration.ofSeconds(10)).until(() -> mc().get("/v1/flows/" + path(arn)).statusCode() == 404);
    }

    /** A transport stream: a PAT naming program 1 at PID 0x1000, its PMT with H.264 at 0x100 and AAC at 0x101. */
    static byte[] transportStream() {
        byte[] pat = packet(0, true, new byte[] {0, 0x00, (byte) 0xb0, 0x0d, 0x00, 0x01, (byte) 0xc1, 0x00, 0x00,
            0x00, 0x01, (byte) 0xf0, 0x00, 0, 0, 0, 0});
        byte[] pmt = packet(0x1000, true, new byte[] {0, 0x02, (byte) 0xb0, 0x17, 0x00, 0x01, (byte) 0xc1, 0x00,
            0x00, (byte) 0xe1, 0x00, (byte) 0xf0, 0x00, 0x1b, (byte) 0xe1, 0x00, (byte) 0xf0, 0x00, 0x0f,
            (byte) 0xe1, 0x01, (byte) 0xf0, 0x00, 0, 0, 0, 0});
        byte[] data = packet(0x100, false, new byte[] {1, 2, 3});
        byte[] stream = new byte[188 * 7];
        System.arraycopy(pat, 0, stream, 0, 188);
        System.arraycopy(pmt, 0, stream, 188, 188);
        for (int i = 2; i < 7; i++) {
            System.arraycopy(data, 0, stream, 188 * i, 188);
        }
        return stream;
    }

    static byte[] packet(int pid, boolean start, byte[] payload) {
        byte[] packet = new byte[188];
        Arrays.fill(packet, (byte) 0xff);
        packet[0] = 0x47;
        packet[1] = (byte) ((start ? 0x40 : 0) | (pid >> 8 & 0x1f));
        packet[2] = (byte) pid;
        packet[3] = 0x10;
        System.arraycopy(payload, 0, packet, 4, payload.length);
        return packet;
    }

    @Test
    void anActiveFlowRelaysItsSourceToItsOutputs() throws IOException {
        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket rtpReceiver = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket sender = new DatagramSocket()) {
            receiver.setSoTimeout(5000);
            rtpReceiver.setSoTimeout(5000);
            String arn = createFlow("relay", 25020, ",\"outputs\":[{\"name\":\"plain\",\"protocol\":\"udp\","
                    + "\"destination\":\"127.0.0.1\",\"port\":" + receiver.getLocalPort() + "},{\"name\":\"rtp\","
                    + "\"protocol\":\"rtp\",\"destination\":\"127.0.0.1\",\"port\":" + rtpReceiver.getLocalPort()
                    + "}]");
            mc().get("/v1/flows/" + path(arn) + "/source-metadata").then().statusCode(400);
            mc().post("/v1/flows/start/" + path(arn)).then().statusCode(202).body("status", equalTo("STARTING"));
            awaitStatus(arn, "ACTIVE");
            int port = describe(arn).extract().path("flow.source.localIngestPort");
            assertEquals(25020, port);
            byte[] stream = transportStream();
            sender.send(new DatagramPacket(stream, stream.length, InetAddress.getLoopbackAddress(), port));

            DatagramPacket plain = new DatagramPacket(new byte[2048], 2048);
            receiver.receive(plain);
            assertArrayEquals(stream, Arrays.copyOf(plain.getData(), plain.getLength()));
            DatagramPacket wrapped = new DatagramPacket(new byte[2048], 2048);
            rtpReceiver.receive(wrapped);
            assertEquals(stream.length + 12, wrapped.getLength());
            assertEquals((byte) 0x80, wrapped.getData()[0]);
            assertEquals(33, wrapped.getData()[1]);

            mc().get("/v1/flows/" + path(arn) + "/source-metadata").then().statusCode(200)
                    .body("transportMediaInfo.tsId", equalTo(1))
                    .body("transportMediaInfo.programs[0].programNumber", equalTo(1))
                    .body("transportMediaInfo.programs[0].pcrPid", equalTo(0x100))
                    .body("transportMediaInfo.programs[0].streams.codec", hasItem("h264"))
                    .body("transportMediaInfo.programs[0].streams.codec", hasItem("aac"))
                    .body("transportMediaInfo.programs[0].streams.streamType", hasItem("Video"));
            mc().get("/v1/flows/" + path(arn) + "/source-thumbnail").then().statusCode(200)
                    .body("thumbnailDetails.thumbnailMessages[0].code", equalTo("THUMBNAIL_DISABLED"));

            String source = describe(arn).extract().path("flow.source.sourceArn");
            mc().body("{\"whitelistCidr\":\"10.0.0.0/8\"}").put("/v1/flows/" + path(arn) + "/source/"
                    + path(source)).then().statusCode(202);
            sender.send(new DatagramPacket(stream, stream.length, InetAddress.getLoopbackAddress(), port));
            assertThrows(SocketTimeoutException.class, () -> receiver.receive(new DatagramPacket(new byte[2048],
                    2048)));

            mc().post("/v1/flows/stop/" + path(arn)).then().statusCode(202).body("status", equalTo("STOPPING"));
            awaitStatus(arn, "STANDBY");
            mc().delete("/v1/flows/" + path(arn)).then().statusCode(202);
        }
    }

    @Test
    void anEntitledFlowReceivesItsGrantersContent() throws IOException {
        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket sender = new DatagramSocket()) {
            receiver.setSoTimeout(5000);
            String granter = createFlow("granter", 25030, ",\"entitlements\":[{\"name\":\"self\",\"subscribers\":"
                    + "[\"000000000000\"]}]");
            String entitlement = describe(granter).extract().path("flow.entitlements[0].entitlementArn");
            mc().get("/v1/entitlements").then().statusCode(200).body("entitlements.entitlementArn",
                    hasItem(entitlement));
            String subscriber = mc().body("{\"name\":\"subscriber\",\"source\":{\"name\":\"entitled\","
                            + "\"entitlementArn\":\"" + entitlement + "\"},\"outputs\":[{\"name\":\"out\",\"protocol\":"
                            + "\"udp\",\"destination\":\"127.0.0.1\",\"port\":" + receiver.getLocalPort() + "}]}")
                    .post("/v1/flows").then().statusCode(201).extract().path("flow.flowArn");
            mc().get("/v1/flows").then().body("flows.find { it.name == 'subscriber' }.sourceType",
                    equalTo("ENTITLED"));
            mc().post("/v1/flows/start/" + path(granter)).then().statusCode(202);
            mc().post("/v1/flows/start/" + path(subscriber)).then().statusCode(202);
            awaitStatus(granter, "ACTIVE");
            awaitStatus(subscriber, "ACTIVE");
            byte[] stream = transportStream();
            sender.send(new DatagramPacket(stream, stream.length, InetAddress.getLoopbackAddress(), 25030));
            DatagramPacket received = new DatagramPacket(new byte[2048], 2048);
            receiver.receive(received);
            assertArrayEquals(stream, Arrays.copyOf(received.getData(), received.getLength()));
            for (String arn : new String[] {subscriber, granter}) {
                mc().post("/v1/flows/stop/" + path(arn)).then().statusCode(202);
                awaitStatus(arn, "STANDBY");
                mc().delete("/v1/flows/" + path(arn)).then().statusCode(202);
            }
        }
    }

    @Test
    void gatewaysBridgesAndInstances() {
        String gateway = mc().body("{\"name\":\"onprem\",\"egressCidrBlocks\":[\"203.0.113.0/24\"],\"networks\":"
                        + "[{\"name\":\"studio\",\"cidrBlock\":\"10.10.0.0/16\"}]}").post("/v1/gateways").then()
                .statusCode(201).body("gateway.gatewayState", equalTo("CREATING")).extract()
                .path("gateway.gatewayArn");
        await().atMost(Duration.ofSeconds(10)).until(() -> "ACTIVE".equals(mc().get("/v1/gateways/" + path(gateway))
                .then().extract().path("gateway.gatewayState")));
        String bridge = mc().body("{\"name\":\"in\",\"placementArn\":\"" + gateway + "\",\"ingressGatewayBridge\":"
                        + "{\"maxBitrate\":10000000,\"maxOutputs\":2},\"sources\":[{\"networkSource\":{\"name\":"
                        + "\"cam\",\"multicastIp\":\"239.1.1.1\",\"networkName\":\"studio\",\"port\":5000,"
                        + "\"protocol\":\"rtp\"}}]}").post("/v1/bridges").then().statusCode(201)
                .extract().path("bridge.bridgeArn");
        await().atMost(Duration.ofSeconds(10)).until(() -> "DEPLOYING".equals(mc().get("/v1/bridges/"
                + path(bridge)).then().extract().path("bridge.bridgeState")));
        mc().get("/v1/bridges/" + path(bridge)).then().body("bridge.bridgeMessages[0].code",
                equalTo("NO_AVAILABLE_INSTANCE"));
        mc().get("/v1/bridges?filterArn=" + path(gateway)).then().statusCode(200)
                .body("bridges[0].bridgeType", equalTo("ingress-gateway"));
        mc().get("/v1/gateway-instances").then().statusCode(200).body("instances", hasSize(0));
        mc().delete("/v1/gateways/" + path(gateway)).then().statusCode(409);
        mc().delete("/v1/bridges/" + path(bridge)).then().statusCode(200);
        await().atMost(Duration.ofSeconds(10)).until(() -> mc().get("/v1/bridges/" + path(bridge)).statusCode()
                == 404);
        mc().delete("/v1/gateways/" + path(gateway)).then().statusCode(200);
    }

    @Test
    void routerResourcesRouteOutputsToInputs() {
        String networkInterface = mc().body("{\"name\":\"public\",\"configuration\":{\"public\":{\"allowRules\":"
                        + "[{\"cidr\":\"127.0.0.0/8\"}]}}}").post("/v1/routerNetworkInterface").then()
                .statusCode(201).body("routerNetworkInterface.networkInterfaceType", equalTo("PUBLIC")).extract()
                .path("routerNetworkInterface.arn");
        String input = mc().body("{\"name\":\"in\",\"configuration\":{\"standard\":{\"networkInterfaceArn\":\""
                        + networkInterface + "\",\"protocolConfiguration\":{\"rtp\":{\"port\":25040}}}},"
                        + "\"maximumBitrate\":10000000,\"routingScope\":\"REGIONAL\",\"tier\":\"INPUT_100\"}")
                .post("/v1/routerInput").then().statusCode(201).body("routerInput.inputType", equalTo("STANDARD"))
                .extract().path("routerInput.arn");
        String output = mc().body("{\"name\":\"out\",\"configuration\":{\"standard\":{\"networkInterfaceArn\":\""
                        + networkInterface + "\",\"protocolConfiguration\":{\"rtp\":{\"destinationAddress\":"
                        + "\"127.0.0.1\",\"destinationPort\":25041}}}},\"maximumBitrate\":10000000,"
                        + "\"routingScope\":\"REGIONAL\",\"tier\":\"OUTPUT_100\"}")
                .post("/v1/routerOutput").then().statusCode(201).extract().path("routerOutput.arn");
        mc().put("/v1/routerOutput/takeRouterInput/" + path(output)).then().statusCode(202);
        mc().body("{\"routerInputArn\":\"" + input + "\"}").put("/v1/routerOutput/takeRouterInput/" + path(output))
                .then().statusCode(202).body("routedState", equalTo("ROUTING"));
        await().atMost(Duration.ofSeconds(10)).until(() -> "ROUTED".equals(mc().get("/v1/routerOutput/"
                + path(output)).then().extract().path("routerOutput.routedState")));
        mc().get("/v1/routerInput/" + path(input)).then().body("routerInput.routedOutputs", equalTo(1));
        mc().get("/v1/routerNetworkInterface/" + path(networkInterface)).then()
                .body("routerNetworkInterface.associatedInputCount", equalTo(1));
        mc().body("{\"filters\":[{\"inputTypes\":[\"STANDARD\"]}]}").post("/v1/routerInputs").then()
                .statusCode(200).body("routerInputs.arn", hasItem(input));
        mc().get("/v1/routerOutputs?arns=" + path(output) + "&arns="
                + path("arn:aws:mediaconnect:us-east-1:000000000000:routerOutput:aaaaaaaaaaaa")).then()
                .statusCode(200).body("routerOutputs", hasSize(1)).body("errors[0].code", equalTo("NOT_FOUND"));
        mc().body("{\"tags\":{\"k\":\"v\"}}").post("/tags/global/" + path(input)).then().statusCode(204);
        mc().get("/tags/global/" + path(input)).then().body("tags.k", equalTo("v"));
        mc().put("/v1/routerOutput/takeRouterInput/" + path(output)).then().body("routedState",
                equalTo("UNROUTED"));
        await().atMost(Duration.ofSeconds(10)).until(() -> "STANDBY".equals(mc().get("/v1/routerOutput/"
                + path(output)).then().extract().path("routerOutput.state")));
        mc().delete("/v1/routerOutput/" + path(output)).then().statusCode(200);
        await().atMost(Duration.ofSeconds(10)).until(() -> "STANDBY".equals(mc().get("/v1/routerInput/"
                + path(input)).then().extract().path("routerInput.state")));
        mc().delete("/v1/routerInput/" + path(input)).then().statusCode(200);
    }

    @Test
    void offeringsAreReserved() {
        String offering = mc().get("/v1/offerings").then().statusCode(200).body("offerings", hasSize(greaterThan(0)))
                .extract().path("offerings[0].offeringArn");
        String reservation = mc().body("{\"reservationName\":\"year\",\"start\":\"2026-01-01T00:00:00Z\"}")
                .post("/v1/offerings/" + path(offering)).then().statusCode(201)
                .body("reservation.reservationState", equalTo("ACTIVE"))
                .body("reservation.end", equalTo("2027-01-01T00:00:00Z")).extract()
                .path("reservation.reservationArn");
        mc().get("/v1/reservations/" + path(reservation)).then().statusCode(200);
        mc().get("/v1/reservations").then().body("reservations.reservationArn", hasItem(reservation));
    }
}
