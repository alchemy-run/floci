package io.floci.aws.services.iotmanagedintegrations;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.floci.aws.services.iotmanagedintegrations.IotMiIntegrationTest.ACCOUNT;
import static io.floci.aws.services.iotmanagedintegrations.IotMiIntegrationTest.MAPPER;
import static io.floci.aws.services.iotmanagedintegrations.IotMiIntegrationTest.json;
import static io.floci.aws.services.iotmanagedintegrations.IotMiIntegrationTest.kinesis;
import static io.floci.aws.services.iotmanagedintegrations.IotMiIntegrationTest.mi;
import static io.floci.aws.services.iotmanagedintegrations.IotMiIntegrationTest.records;
import static io.floci.aws.services.iotmanagedintegrations.IotMiIntegrationTest.signed;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A cloud-to-cloud integration end to end, with a real connector Lambda function: an end user's OAuth 2.0
 * authorization against a token endpoint this test serves, the connector's ActivateUser, DiscoverDevices and
 * SendCommand operations, and the connector's events back through SendConnectorEvent, turning discovered devices
 * into managed things whose commands and state flow through the Matter capability catalog.
 */
@QuarkusTest
@Tag("docker")
class IotMiConnectorDockerIntegrationTest {

    private static final String CONNECTOR_CODE = """
            exports.handler = async (event) => {
              console.log("CONNECTOR " + JSON.stringify(event));
              const op = event.payload.operationName;
              if (op === "AWS.ActivateUser") {
                return { header: { responseCode: 200 },
                         payload: { responseMessage: "activated", userId: "user-" + event.header.auth.token } };
              }
              if (op === "AWS.SendCommand" && event.payload.connectorDeviceId === "broken-plug") {
                return { header: { responseCode: 503 }, payload: { responseMessage: "device offline" } };
              }
              return { header: { responseCode: 200 }, payload: { responseMessage: "accepted" } };
            };
            """;

    @Inject
    Vertx vertx;

    private HttpServer tokens;
    private final List<String> exchanges = new ArrayList<>();

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void startTokenEndpoint() throws Exception {
        tokens = vertx.createHttpServer().requestHandler(request -> request.body().onSuccess(body -> {
            synchronized (exchanges) {
                exchanges.add(body.toString() + " auth=" + request.getHeader("Authorization"));
            }
            if (body.toString().contains("code=good-code")) {
                request.response().putHeader("Content-Type", "application/json")
                        .end("{\"access_token\":\"tok-1\",\"refresh_token\":\"refresh-1\",\"token_type\":\"bearer\"}");
            } else {
                request.response().setStatusCode(400).end("{\"error\":\"invalid_grant\"}");
            }
        })).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void stopTokenEndpoint() throws Exception {
        tokens.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static JsonNode await(String what, Supplier<JsonNode> read, Predicate<JsonNode> done) {
        long deadline = System.currentTimeMillis() + 60_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = read.get();
            if (done.test(last)) {
                return last;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        fail(what + " did not happen: " + last);
        return last;
    }

    @Test
    void aConnectorsDevicesAreDiscoveredCommandedAndReportState() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String function = "connector-" + suffix;
        given().contentType("application/json").body("{\"FunctionName\":\"" + function + "\",\"Runtime\":"
                + "\"nodejs20.x\",\"Role\":\"arn:aws:iam::" + ACCOUNT + ":role/lambda-role\",\"Handler\":"
                + "\"index.handler\",\"Timeout\":10,\"Code\":{\"ZipFile\":\"" + zip(CONNECTOR_CODE) + "\"}}")
                .post("/2015-03-31/functions").then().statusCode(201);
        String functionArn = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:" + function;

        // Notifications for the integration's events.
        String stream = "mi-c2c-" + suffix;
        kinesis("CreateStream", "{\"StreamName\":\"" + stream + "\",\"ShardCount\":1}");
        Thread.sleep(600);
        String streamArn = json(kinesis("DescribeStreamSummary", "{\"StreamName\":\"" + stream + "\"}"))
                .path("StreamDescriptionSummary").path("StreamARN").asText();
        signed("iam").contentType("application/x-www-form-urlencoded").formParam("Action", "CreateRole")
                .formParam("Version", "2010-05-08").formParam("RoleName", "mi-" + suffix)
                .formParam("AssumeRolePolicyDocument", "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":"
                        + "{\"Service\":\"iotmanagedintegrations.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}")
                .post("/").then().statusCode(200);
        mi().body("{\"Name\":\"c2c-" + suffix + "\",\"DeliveryDestinationArn\":\"" + streamArn + "\","
                + "\"DeliveryDestinationType\":\"KINESIS\",\"RoleArn\":\"arn:aws:iam::" + ACCOUNT + ":role/mi-" + suffix
                + "\"}").post("/destinations").then().statusCode(200);
        for (String type : List.of("ACCOUNT_ASSOCIATION", "DEVICE_DISCOVERY_STATUS", "DEVICE_COMMAND", "DEVICE_STATE",
                "DEVICE_EVENT", "CONNECTOR_ERROR_REPORT")) {
            mi().body("{\"EventType\":\"" + type + "\",\"DestinationName\":\"c2c-" + suffix + "\"}")
                    .post("/notification-configurations").then().statusCode(200);
        }

        // The connector and how end users authorize it.
        mi().body("{\"Name\":\"acme\",\"EndpointConfig\":{\"lambda\":{\"arn\":\"" + functionArn.replace(suffix, "nope")
                + "\"}}}").post("/cloud-connectors").then().statusCode(400);
        String connector = mi().body("{\"Name\":\"acme\",\"EndpointConfig\":{\"lambda\":{\"arn\":\"" + functionArn
                + "\"}},\"Description\":\"acme cloud\"}").post("/cloud-connectors").then().statusCode(200).extract()
                .path("Id");
        JsonNode secret = json(signed("secretsmanager").header("X-Amz-Target", "secretsmanager.CreateSecret")
                .contentType("application/x-amz-json-1.1").body("{\"Name\":\"acme-oauth-" + suffix
                        + "\",\"SecretString\":\"{\\\"client_id\\\":\\\"cid\\\",\\\"client_secret\\\":\\\"csec\\\"}\"}")
                .post("/").then().statusCode(200));
        String tokenUrl = "http://127.0.0.1:" + tokens.actualPort() + "/token";
        String destination = mi().body("{\"Name\":\"acme-users\",\"CloudConnectorId\":\"" + connector + "\","
                + "\"AuthType\":\"OAUTH\",\"AuthConfig\":{\"oAuth\":{\"authUrl\":\"https://auth.acme.example/authorize\","
                + "\"tokenUrl\":\"" + tokenUrl + "\",\"scope\":\"devices\",\"tokenEndpointAuthenticationScheme\":"
                + "\"HTTP_BASIC\",\"oAuthCompleteRedirectUrl\":\"https://app.example/linked\"}},\"SecretsManager\":"
                + "{\"arn\":\"" + secret.path("ARN").asText() + "\",\"versionId\":\"" + secret.path("VersionId").asText()
                + "\"}}").post("/connector-destinations").then().statusCode(200).extract().path("Id");
        mi().delete("/cloud-connectors/" + connector).then().statusCode(409);

        // An end user links their account: the authorization fails once, then succeeds.
        JsonNode association = json(mi().body("{\"ConnectorDestinationId\":\"" + destination + "\",\"Name\":\"alice\"}")
                .post("/account-associations").then().statusCode(200));
        String associationId = association.path("AccountAssociationId").asText();
        assertEquals("ASSOCIATION_IN_PROGRESS", association.path("AssociationState").asText());
        URI authorization = URI.create(association.path("OAuthAuthorizationUrl").asText());
        assertEquals("auth.acme.example", authorization.getHost());
        assertTrue(authorization.getQuery().contains("client_id=cid"), authorization.toString());
        assertTrue(authorization.getQuery().contains("scope=devices"), authorization.toString());
        mi().body("{\"DiscoveryType\":\"CLOUD\",\"AccountAssociationId\":\"" + associationId + "\"}")
                .post("/device-discoveries").then().statusCode(409);
        String state = query(authorization, "state");
        given().redirects().follow(false).queryParam("state", state).queryParam("code", "bad-code")
                .get(IotMiConnectors.CALLBACK_PATH).then().statusCode(303)
                .header("Location", equalTo("https://app.example/linked"));
        mi().get("/account-associations/" + associationId).then().body("AssociationState",
                equalTo("ASSOCIATION_FAILED"));
        String refreshed = mi().post("/account-associations/" + associationId + "/refresh").then().statusCode(200)
                .extract().path("OAuthAuthorizationUrl");
        given().redirects().follow(false).queryParam("state", query(URI.create(refreshed), "state"))
                .queryParam("code", "good-code").get(IotMiConnectors.CALLBACK_PATH).then().statusCode(303);
        mi().get("/account-associations/" + associationId).then().body("AssociationState",
                equalTo("ASSOCIATION_SUCCEEDED"));
        synchronized (exchanges) {
            assertTrue(exchanges.stream().anyMatch(e -> e.contains("grant_type=authorization_code")
                    && e.contains("auth=Basic " + Base64.getEncoder().encodeToString("cid:csec".getBytes(
                    StandardCharsets.UTF_8)))), exchanges.toString());
        }

        // Discovery: the connector is asked, then reports the user's devices.
        String discovery = mi().body("{\"DiscoveryType\":\"CLOUD\",\"AccountAssociationId\":\"" + associationId
                + "\"}").post("/device-discoveries").then().statusCode(200).extract().path("Id");
        mi().get("/device-discoveries/" + discovery).then().body("Status", equalTo("RUNNING"));
        JsonNode discoverRequest = await("the connector's DiscoverDevices", () -> connectorLog(function,
                "AWS.DiscoverDevices"), request -> request != null);
        assertEquals("OAuth2.0", discoverRequest.path("header").path("auth").path("type").asText());
        assertEquals("tok-1", discoverRequest.path("header").path("auth").path("token").asText());
        assertEquals(discovery, discoverRequest.path("payload").path("deviceDiscoveryId").asText());
        assertEquals(connector, discoverRequest.path("payload").path("connectorId").asText());
        assertEquals("user-tok-1", discoverRequest.path("payload").path("userId").asText());

        String report = "{\"version\":\"1.0.0\",\"endpoints\":[{\"id\":\"1\",\"deviceTypes\":[\"OnOffPlug\"],"
                + "\"clusters\":[{\"id\":\"0x0006\",\"revision\":6,\"attributes\":[{\"id\":\"0x0000\"},{\"id\":"
                + "\"0x4001\"}],\"commands\":[\"0x00\",\"0x01\",\"0x02\"]}]}]}";
        mi().body("{\"Operation\":\"DEVICE_DISCOVERY\",\"OperationVersion\":\"1.0\",\"StatusCode\":200,"
                + "\"DeviceDiscoveryId\":\"" + discovery + "\",\"UserId\":\"user-tok-1\",\"Devices\":["
                + "{\"ConnectorDeviceId\":\"plug-1\",\"ConnectorDeviceName\":\"Kitchen plug\",\"CapabilityReport\":"
                + report + "},{\"ConnectorDeviceId\":\"broken-plug\",\"CapabilityReport\":" + report + "}]}")
                .post("/connector-event/" + connector).then().statusCode(200).body("ConnectorId", equalTo(connector));
        mi().get("/device-discoveries/" + discovery).then().body("Status", equalTo("SUCCEEDED"));
        JsonNode discovered = json(mi().get("/device-discoveries/" + discovery + "/devices").then().statusCode(200));
        assertEquals(2, discovered.path("Items").size());
        assertEquals("DISCOVERED", discovered.path("Items").get(0).path("Modification").asText());
        String plug = discovered.path("Items").get(0).path("ManagedThingId").asText();
        String broken = discovered.path("Items").get(1).path("ManagedThingId").asText();
        mi().get("/managed-things/" + plug).then().body("ProvisioningStatus", equalTo("ACTIVATED"))
                .body("ConnectorDeviceId", equalTo("plug-1")).body("ConnectorDestinationId", equalTo(destination))
                .body("Name", equalTo("Kitchen plug"));
        JsonNode capabilities = json(mi().get("/managed-things-capabilities/" + plug).then().statusCode(200));
        JsonNode capability = capabilities.path("CapabilityReport").path("endpoints").get(0).path("capabilities")
                .get(0);
        assertEquals("matter.OnOff@1.4", capability.path("id").asText());
        assertEquals("[\"OnOff\",\"OnTime\"]", capability.path("properties").toString());
        assertEquals("[\"Off\",\"On\",\"Toggle\"]", capability.path("actions").toString());
        mi().queryParam("ManagedThingId", plug).get("/managed-thing-associations").then()
                .body("Items[0].ManagedThingAssociationStatus", equalTo("ASSOCIATED"))
                .body("Items[0].AccountAssociationId", equalTo(associationId));
        mi().post("/managed-things-connectivity-data/" + plug).then().body("Connected", equalTo(true));

        // A command reaches the connector as Matter cluster commands; the response updates the state.
        String trace = mi().body("{\"Endpoints\":[{\"endpointId\":\"1\",\"capabilities\":[{\"id\":\"matter.OnOff@1.4\","
                + "\"name\":\"On/Off\",\"version\":\"6\",\"actions\":[{\"name\":\"On\"},{\"name\":\"UpdateState\","
                + "\"parameters\":{\"OnTime\":30}}]}]}]}").post("/managed-things-command/" + plug).then()
                .statusCode(200).extract().path("TraceId");
        mi().body("{\"Endpoints\":[{\"endpointId\":\"1\",\"capabilities\":[{\"id\":\"matter.OnOff@1.4\","
                + "\"name\":\"On/Off\",\"version\":\"6\",\"actions\":[{\"name\":\"Explode\"}]}]}]}")
                .post("/managed-things-command/" + plug).then().statusCode(400);
        JsonNode command = await("the connector's SendCommand", () -> connectorLog(function, "AWS.SendCommand"),
                request -> request != null);
        assertEquals("plug-1", command.path("payload").path("connectorDeviceId").asText());
        assertEquals(trace, command.path("payload").path("traceId").asText());
        JsonNode cluster = command.path("payload").path("endpoints").get(0).path("clusters").get(0);
        assertEquals("0x0006", cluster.path("id").asText());
        assertTrue(cluster.path("commands").get(0).has("0x0001"), cluster.toString());
        assertEquals(30, cluster.path("commands").get(1).path("0xff01").path("0x4001").asInt());

        mi().body("{\"Operation\":\"DEVICE_COMMAND_RESPONSE\",\"ConnectorDeviceId\":\"plug-1\",\"TraceId\":\"" + trace
                + "\",\"StatusCode\":200,\"MatterEndpoint\":{\"id\":\"1\",\"clusters\":[{\"id\":\"0x0006\","
                + "\"attributes\":{\"0x0000\":true,\"0x4001\":30}}]}}").post("/connector-event/" + connector).then()
                .statusCode(200);
        JsonNode thingState = json(mi().get("/managed-thing-states/" + plug).then().statusCode(200));
        JsonNode properties = thingState.path("Endpoints").get(0).path("capabilities").get(0).path("properties");
        assertTrue(properties.path("OnOff").asBoolean(), thingState.toString());
        assertEquals(30, properties.path("OnTime").asInt());
        mi().body("{\"Operation\":\"DEVICE_EVENT\",\"ConnectorDeviceId\":\"plug-1\",\"MatterEndpoint\":{\"id\":\"1\","
                + "\"clusters\":[{\"id\":\"0x0006\",\"attributes\":{\"0x0000\":false}}]}}")
                .post("/connector-event/" + connector).then().statusCode(200);
        mi().get("/managed-thing-states/" + plug).then().body("Endpoints[0].capabilities[0].properties.OnOff",
                equalTo(false));

        // A device the connector can't reach fails its command.
        mi().body("{\"Endpoints\":[{\"endpointId\":\"1\",\"capabilities\":[{\"id\":\"matter.OnOff@1.4\","
                + "\"name\":\"On/Off\",\"version\":\"6\",\"actions\":[{\"name\":\"Toggle\"}]}]}]}")
                .post("/managed-things-command/" + broken).then().statusCode(200);

        List<JsonNode> events = records(stream, 9);
        List<String> types = new ArrayList<>();
        events.forEach(event -> types.add(event.path("messageType").asText()));
        for (String type : List.of("ACCOUNT_ASSOCIATION", "DEVICE_DISCOVERY_STATUS", "DEVICE_COMMAND", "DEVICE_STATE",
                "DEVICE_EVENT", "CONNECTOR_ERROR_REPORT")) {
            assertTrue(types.contains(type), type + " in " + types);
        }
        List<String> results = new ArrayList<>();
        events.stream().filter(event -> "DEVICE_COMMAND".equals(event.path("messageType").asText()))
                .forEach(event -> results.add(event.path("payload").path("result").asText()));
        assertTrue(results.contains("succeeded") && results.contains("failed"), results.toString());

        // Unlinking the account deactivates the user and drops the devices' association.
        mi().delete("/account-associations/" + associationId).then().statusCode(200);
        await("the connector's DeactivateUser", () -> connectorLog(function, "AWS.DeactivateUser"),
                request -> request != null);
        mi().queryParam("ManagedThingId", plug).get("/managed-thing-associations").then().body("Items", hasSize(0));
        for (String thing : List.of(plug, broken)) {
            mi().delete("/managed-things/" + thing).then().statusCode(200);
        }
        for (String type : List.of("ACCOUNT_ASSOCIATION", "DEVICE_DISCOVERY_STATUS", "DEVICE_COMMAND", "DEVICE_STATE",
                "DEVICE_EVENT", "CONNECTOR_ERROR_REPORT")) {
            mi().delete("/notification-configurations/" + type).then().statusCode(200);
        }
        mi().delete("/destinations/c2c-" + suffix).then().statusCode(200);
        given().delete("/2015-03-31/functions/" + function);
    }

    private static String query(URI uri, String name) {
        for (String part : uri.getRawQuery().split("&")) {
            if (part.startsWith(name + "=")) {
                return part.substring(name.length() + 1);
            }
        }
        throw new AssertionError(name + " not in " + uri);
    }

    /** The last request of an operation the connector logged, or null. */
    private static JsonNode connectorLog(String function, String operation) {
        JsonNode streams = json(signed("logs").header("X-Amz-Target", "Logs_20140328.DescribeLogStreams")
                .contentType("application/x-amz-json-1.1").body("{\"logGroupName\":\"/aws/lambda/" + function + "\"}")
                .post("/").then());
        JsonNode found = null;
        for (JsonNode logStream : streams.path("logStreams")) {
            JsonNode events = json(signed("logs").header("X-Amz-Target", "Logs_20140328.GetLogEvents")
                    .contentType("application/x-amz-json-1.1").body("{\"logGroupName\":\"/aws/lambda/" + function
                            + "\",\"logStreamName\":\"" + logStream.path("logStreamName").asText()
                            + "\",\"startFromHead\":true}").post("/").then());
            for (JsonNode event : events.path("events")) {
                String message = event.path("message").asText();
                int at = message.indexOf("CONNECTOR ");
                if (at >= 0 && message.contains("\"" + operation + "\"")) {
                    try {
                        found = MAPPER.readTree(message.substring(at + "CONNECTOR ".length()).trim());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        return found;
    }

    private static String zip(String code) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(code.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
