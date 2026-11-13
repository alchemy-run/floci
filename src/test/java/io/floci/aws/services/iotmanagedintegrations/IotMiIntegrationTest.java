package io.floci.aws.services.iotmanagedintegrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Managed integrations over REST: credential lockers, managed things and their onboarding material, capabilities,
 * certificates and logs, provisioning profiles, the Matter schema catalog, account settings, OTA tasks, tags, and
 * notifications delivered to a Kinesis data stream.
 */
@QuarkusTest
class IotMiIntegrationTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String ACCOUNT = "000000000000";
    static final String ZIGBEE = "Z:24FD5B0000015C63$I:83FED3407A939738";
    static final String REPORT = "{\"version\":\"1.0.0\",\"endpoints\":[{\"id\":\"1\",\"deviceTypes\":[\"OnOffLight\"],"
            + "\"capabilities\":[{\"id\":\"matter.OnOff@1.4\",\"name\":\"On/Off\",\"version\":\"6\",\"properties\":"
            + "[\"OnOff\"],\"actions\":[\"On\",\"Off\",\"Toggle\"],\"events\":[]}]}]}";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static RequestSpecification signed(String service) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/" + service
                + "/aws4_request");
    }

    static RequestSpecification mi() {
        return signed("iotmanagedintegrations").contentType("application/json");
    }

    static JsonNode json(ValidatableResponse response) {
        try {
            return MAPPER.readTree(response.extract().asString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- managed things

    @Test
    void managedThingsAreOnboardedValidatedAndDeleted() throws Exception {
        String locker = mi().body("{\"Name\":\"home\",\"Tags\":{\"team\":\"iot\"}}").post("/credential-lockers")
                .then().statusCode(200).extract().path("Id");
        mi().get("/credential-lockers/" + locker).then().statusCode(200).body("Name", equalTo("home"))
                .body("Arn", equalTo("arn:aws:iotmanagedintegrations:us-east-1:" + ACCOUNT + ":credential-locker/"
                        + locker)).body("Tags.team", equalTo("iot"));

        mi().body("{\"Role\":\"DEVICE\",\"AuthenticationMaterialType\":\"ZIGBEE_QR_BAR_CODE\","
                + "\"AuthenticationMaterial\":\"not-a-code\"}").post("/managed-things").then().statusCode(400)
                .header("x-amzn-ErrorType", "ValidationException");
        mi().body("{\"Role\":\"DEVICE\",\"AuthenticationMaterialType\":\"ZIGBEE_QR_BAR_CODE\","
                + "\"AuthenticationMaterial\":\"" + ZIGBEE + "\",\"CredentialLockerId\":\"nope\"}").post("/managed-things")
                .then().statusCode(404).header("x-amzn-ErrorType", "ResourceNotFoundException");
        mi().body("{\"Role\":\"DEVICE\",\"AuthenticationMaterialType\":\"ZIGBEE_QR_BAR_CODE\","
                + "\"AuthenticationMaterial\":\"" + ZIGBEE + "\",\"CapabilityReport\":{\"version\":\"1.0.0\","
                + "\"endpoints\":[{\"id\":\"1\",\"deviceTypes\":[],\"capabilities\":[{\"id\":\"acme.Thing@1.0\","
                + "\"name\":\"Thing\",\"version\":\"1\",\"properties\":[],\"actions\":[],\"events\":[]}]}]}}")
                .post("/managed-things").then().statusCode(400).body("Message", containsString("acme.Thing@1.0"));

        String create = "{\"Role\":\"DEVICE\",\"AuthenticationMaterialType\":\"ZIGBEE_QR_BAR_CODE\","
                + "\"AuthenticationMaterial\":\"" + ZIGBEE + "\",\"CredentialLockerId\":\"" + locker + "\","
                + "\"Name\":\"lamp\",\"Brand\":\"acme\",\"SerialNumber\":\"SN-1\",\"ClientToken\":\"lamp-1\","
                + "\"CapabilityReport\":" + REPORT + ",\"MetaData\":{\"room\":\"kitchen\"},\"Tags\":{\"a\":\"b\"}}";
        JsonNode created = json(mi().body(create).post("/managed-things").then().statusCode(200));
        String thing = created.path("Id").asText();
        assertTrue(thing.matches("[0-9a-f]{32}"), thing);
        mi().body(create).post("/managed-things").then().statusCode(200).body("Id", equalTo(thing));
        mi().body(create.replace("lamp-1", "lamp-2")).post("/managed-things").then().statusCode(409)
                .header("x-amzn-ErrorType", "ConflictException");

        mi().get("/managed-things/" + thing).then().statusCode(200).body("ProvisioningStatus", equalTo("UNASSOCIATED"))
                .body("Role", equalTo("DEVICE")).body("Name", equalTo("lamp")).body("Brand", equalTo("acme"))
                .body("CredentialLockerId", equalTo(locker)).body("MetaData.room", equalTo("kitchen"));
        mi().get("/managed-things-capabilities/" + thing).then().statusCode(200)
                .body("CapabilityReport.endpoints[0].capabilities[0].id", equalTo("matter.OnOff@1.4"));
        mi().get("/managed-things-metadata/" + thing).then().statusCode(200).body("MetaData.room", equalTo("kitchen"));
        mi().get("/managed-thing-schemas/" + thing).then().statusCode(200)
                .body("Items[0].CapabilityId", equalTo("matter.OnOff@1.4"))
                .body("Items[0].Schema.extrinsicId", equalTo("0x0006"));
        mi().get("/managed-thing-states/" + thing).then().statusCode(200).body("Endpoints", hasSize(0));
        mi().post("/managed-things-connectivity-data/" + thing).then().statusCode(200)
                .body("Connected", equalTo(false));
        mi().get("/managed-things-certificate/" + thing).then().statusCode(404);
        mi().body("{\"Endpoints\":[{\"endpointId\":\"1\",\"capabilities\":[{\"id\":\"matter.OnOff@1.4\","
                + "\"name\":\"On/Off\",\"version\":\"6\",\"actions\":[{\"name\":\"On\"}]}]}]}")
                .post("/managed-things-command/" + thing).then().statusCode(409)
                .body("Message", containsString("UNASSOCIATED"));

        mi().body("{\"Brand\":\"acme2\",\"MetaData\":{\"room\":\"hall\"}}").put("/managed-things/" + thing).then()
                .statusCode(200);
        mi().get("/managed-things/" + thing).then().body("Brand", equalTo("acme2")).body("MetaData.room",
                equalTo("hall"));
        mi().queryParam("SerialNumberFilter", "SN-1").get("/managed-things").then().statusCode(200)
                .body("Items", hasSize(1)).body("Items[0].Id", equalTo(thing));
        mi().queryParam("RoleFilter", "CONTROLLER").get("/managed-things").then().statusCode(200)
                .body("Items", hasSize(0));

        mi().body("{\"RuntimeLogConfigurations\":{\"LogLevel\":\"DEBUG\",\"UploadLog\":true}}")
                .put("/runtime-log-configurations/" + thing).then().statusCode(200);
        mi().get("/runtime-log-configurations/" + thing).then().statusCode(200)
                .body("RuntimeLogConfigurations.LogLevel", equalTo("DEBUG"));
        mi().delete("/runtime-log-configurations/" + thing).then().statusCode(200);
        mi().get("/runtime-log-configurations/" + thing).then().body("RuntimeLogConfigurations.LogLevel",
                equalTo("INFO"));

        String hub = mi().body("{\"Role\":\"CONTROLLER\",\"AuthenticationMaterialType\":\"WIFI_SETUP_QR_BAR_CODE\","
                + "\"AuthenticationMaterial\":\"MT:Y3K9042C00KA0648G00\"}").post("/managed-things").then()
                .statusCode(200).extract().path("Id");
        mi().get("/managed-things-certificate/" + hub).then().statusCode(200)
                .body("CertificatePem", containsString("BEGIN CERTIFICATE"));
        mi().body("{\"DiscoveryType\":\"ZIGBEE\",\"ControllerIdentifier\":\"" + hub + "\"}").post("/device-discoveries")
                .then().statusCode(200);
        mi().queryParam("StatusFilter", "FAILED").get("/device-discoveries").then().statusCode(200)
                .body("Items", hasSize(1));
        mi().body("{\"DiscoveryType\":\"ZIGBEE\",\"ControllerIdentifier\":\"" + thing + "\"}").post("/device-discoveries")
                .then().statusCode(400);

        mi().delete("/credential-lockers/" + locker).then().statusCode(409);
        mi().delete("/managed-things/" + thing).then().statusCode(200);
        mi().get("/managed-things/" + thing).then().statusCode(200).body("ProvisioningStatus",
                equalTo("DELETE_IN_PROGRESS"));
        long deadline = System.currentTimeMillis() + 10_000;
        while (mi().get("/managed-things/" + thing).statusCode() != 404) {
            if (System.currentTimeMillis() > deadline) {
                fail("The managed thing wasn't deleted");
            }
            Thread.sleep(200);
        }
        mi().delete("/credential-lockers/" + locker).then().statusCode(200);
        mi().delete("/managed-things/" + hub).then().statusCode(200);
    }

    @Test
    void provisioningProfilesIssueClaimCertificates() {
        JsonNode profile = json(mi().body("{\"ProvisioningType\":\"FLEET_PROVISIONING\",\"Name\":\"hubs\","
                + "\"Tags\":{\"k\":\"v\"}}").post("/provisioning-profiles").then().statusCode(200));
        assertTrue(profile.path("ClaimCertificate").asText().contains("BEGIN CERTIFICATE"));
        assertTrue(profile.path("ClaimCertificatePrivateKey").asText().contains("PRIVATE KEY"));
        assertEquals("CREATED", profile.path("Status").asText());
        String id = profile.path("Id").asText();
        mi().get("/provisioning-profiles/hubs").then().statusCode(200).body("Id", equalTo(id))
                .body("ClaimCertificatePrivateKey", equalTo(null));
        mi().body("{\"ProvisioningType\":\"FLEET_PROVISIONING\",\"Name\":\"hubs\"}").post("/provisioning-profiles")
                .then().statusCode(409);
        mi().body("{\"ProvisioningType\":\"JITR\"}").post("/provisioning-profiles").then().statusCode(400);
        String arn = profile.path("Arn").asText();
        mi().get("/tags/" + encode(arn)).then().statusCode(200).body("tags.k", equalTo("v"));
        mi().body("{\"Tags\":{\"x\":\"y\"}}").post("/tags/" + encode(arn)).then().statusCode(200);
        mi().queryParam("tagKeys", "k").delete("/tags/" + encode(arn)).then().statusCode(200);
        mi().get("/tags/" + encode(arn)).then().body("tags.x", equalTo("y")).body("tags.k", equalTo(null));
        mi().delete("/provisioning-profiles/" + id).then().statusCode(200);
        mi().get("/provisioning-profiles/" + id).then().statusCode(404);
    }

    // ---------------------------------------------------------------- schemas and settings

    @Test
    void theMatterSchemaCatalogIsServed() {
        JsonNode capabilities = json(mi().queryParam("NamespaceFilter", "matter").get("/schema-versions/capability")
                .then().statusCode(200));
        assertTrue(capabilities.path("Items").size() > 50, capabilities.toString());
        mi().queryParam("SchemaIdFilter", "matter.OnOff").get("/schema-versions/capability").then().statusCode(200)
                .body("Items", hasSize(1)).body("Items[0].SemanticVersion", equalTo("1.4"))
                .body("Items[0].Visibility", equalTo("PUBLIC"));
        mi().queryParam("MaxResults", 3).get("/schema-versions/capability").then().statusCode(200)
                .body("Items", hasSize(3)).body("NextToken", notNullValue());
        mi().get("/schema-versions/capability/matter.OnOff@1.4").then().statusCode(200)
                .body("Schema.$id", equalTo("/schema-versions/capability/matter.OnOff@1.4"))
                .body("Schema.properties.OnOff.extrinsicId", equalTo("0x0000"))
                .body("Schema.actions.name", hasItems("On", "Off", "Toggle"));
        mi().queryParam("Format", "ZCL").get("/schema-versions/capability/matter.OnOff@1.4").then().statusCode(200)
                .body("Schema.id", equalTo("0x0006")).body("Schema.commands.name",
                        hasItem("Toggle"));
        mi().queryParam("Format", "CONNECTOR").get("/schema-versions/capability/matter.ColorControl@$latest").then()
                .statusCode(200).body("Schema.clusterId", equalTo("0x0300"));
        mi().get("/schema-versions/definition/matter.uint16@1.4").then().statusCode(200)
                .body("Schema.maximum", equalTo(65535));
        mi().get("/schema-versions/capability/matter.Nope@1.4").then().statusCode(404);
    }

    @Test
    void accountSettingsAndEventLogsAreKept() {
        mi().get("/custom-endpoint").then().statusCode(404);
        String endpoint = mi().post("/custom-endpoint").then().statusCode(200).extract().path("EndpointAddress");
        mi().get("/custom-endpoint").then().statusCode(200).body("EndpointAddress", equalTo(endpoint));
        mi().get("/hub-configuration").then().statusCode(404);
        mi().body("{\"HubTokenTimerExpirySettingInSeconds\":3600}").put("/hub-configuration").then().statusCode(200);
        mi().get("/hub-configuration").then().statusCode(200).body("HubTokenTimerExpirySettingInSeconds",
                equalTo(3600));
        mi().get("/configuration/account/encryption").then().statusCode(200)
                .body("encryptionType", equalTo("MANAGED_INTEGRATIONS_DEFAULT_ENCRYPTION"))
                .body("configurationStatus.state", equalTo("ENABLED"));
        mi().body("{\"encryptionType\":\"CUSTOMER_KEY_ENCRYPTION\"}").post("/configuration/account/encryption").then()
                .statusCode(400);
        String log = mi().body("{\"ResourceType\":\"managed-thing\",\"EventLogLevel\":\"ERROR\"}")
                .post("/event-log-configurations").then().statusCode(200).extract().path("Id");
        mi().body("{\"ResourceType\":\"managed-thing\",\"EventLogLevel\":\"INFO\"}").post("/event-log-configurations")
                .then().statusCode(409);
        mi().body("{\"EventLogLevel\":\"DEBUG\"}").patch("/event-log-configurations/" + log).then().statusCode(200);
        mi().get("/event-log-configurations/" + log).then().body("EventLogLevel", equalTo("DEBUG"));
        mi().get("/event-log-configurations").then().body("EventLogConfigurationList", hasSize(greaterThan(0)));
        mi().delete("/event-log-configurations/" + log).then().statusCode(200);
    }

    // ---------------------------------------------------------------- notifications and OTA

    @Test
    void lifecycleAndOtaEventsReachTheKinesisDestination() throws Exception {
        String stream = "mi-events-" + Long.toString(System.nanoTime(), 36);
        kinesis("CreateStream", "{\"StreamName\":\"" + stream + "\",\"ShardCount\":1}");
        Thread.sleep(600);
        String streamArn = json(kinesis("DescribeStreamSummary", "{\"StreamName\":\"" + stream + "\"}"))
                .path("StreamDescriptionSummary").path("StreamARN").asText();
        String role = "mi-delivery-" + Long.toString(System.nanoTime(), 36);
        signed("iam").contentType("application/x-www-form-urlencoded").formParam("Action", "CreateRole")
                .formParam("Version", "2010-05-08").formParam("RoleName", role).formParam("AssumeRolePolicyDocument",
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                                + "\"iotmanagedintegrations.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}")
                .post("/").then().statusCode(200);
        String roleArn = "arn:aws:iam::" + ACCOUNT + ":role/" + role;

        mi().body("{\"Name\":\"events\",\"DeliveryDestinationArn\":\"" + streamArn.replace(stream, "missing")
                + "\",\"DeliveryDestinationType\":\"KINESIS\",\"RoleArn\":\"" + roleArn + "\"}").post("/destinations")
                .then().statusCode(400);
        mi().body("{\"Name\":\"events\",\"DeliveryDestinationArn\":\"" + streamArn + "\",\"DeliveryDestinationType\":"
                + "\"KINESIS\",\"RoleArn\":\"" + roleArn + "\",\"Description\":\"all\"}").post("/destinations")
                .then().statusCode(200).body("Name", equalTo("events"));
        mi().body("{\"EventType\":\"DEVICE_LIFE_CYCLE\",\"DestinationName\":\"nope\"}")
                .post("/notification-configurations").then().statusCode(404);
        for (String type : List.of("DEVICE_LIFE_CYCLE", "DEVICE_OTA")) {
            mi().body("{\"EventType\":\"" + type + "\",\"DestinationName\":\"events\"}")
                    .post("/notification-configurations").then().statusCode(200);
        }
        mi().body("{\"EventType\":\"DEVICE_OTA\",\"DestinationName\":\"events\"}").post("/notification-configurations")
                .then().statusCode(409);
        mi().delete("/destinations/events").then().statusCode(409);

        String thing = mi().body("{\"Role\":\"DEVICE\",\"AuthenticationMaterialType\":\"CUSTOM_PROTOCOL_QR_BAR_CODE\","
                + "\"AuthenticationMaterial\":\"custom-" + stream + "\",\"Name\":\"sensor\"}").post("/managed-things")
                .then().statusCode(200).extract().path("Id");
        String thingArn = "arn:aws:iotmanagedintegrations:us-east-1:" + ACCOUNT + ":managed-thing/" + thing;

        signed("s3").put("/" + stream).then().statusCode(200);
        signed("s3").body("firmware").put("/" + stream + "/fw.bin").then().statusCode(200);
        mi().body("{\"S3Url\":\"s3://" + stream + "/missing.bin\",\"OtaType\":\"ONE_TIME\",\"Target\":[\"" + thingArn
                + "\"]}").post("/ota-tasks").then().statusCode(400);
        String configuration = mi().body("{\"Name\":\"slow\",\"PushConfig\":{\"TimeoutConfig\":"
                + "{\"InProgressTimeoutInMinutes\":30}}}").post("/ota-task-configurations").then().statusCode(200)
                .extract().path("TaskConfigurationId");
        String task = mi().body("{\"S3Url\":\"s3://" + stream + "/fw.bin\",\"OtaType\":\"ONE_TIME\",\"Target\":[\""
                + thingArn + "\"],\"TaskConfigurationId\":\"" + configuration + "\",\"Description\":\"v2\"}")
                .post("/ota-tasks").then().statusCode(200).extract().path("TaskId");
        mi().get("/ota-tasks/" + task).then().statusCode(200).body("Status", equalTo("IN_PROGRESS"))
                .body("TaskProcessingDetails.numberOfQueuedThings", equalTo(1));
        mi().get("/ota-tasks/" + task + "/devices").then().statusCode(200)
                .body("ExecutionSummaries[0].ManagedThingId", equalTo(thing))
                .body("ExecutionSummaries[0].TaskExecutionSummary.Status", equalTo("QUEUED"));
        mi().delete("/ota-task-configurations/" + configuration).then().statusCode(409);
        String scheduled = mi().body("{\"S3Url\":\"s3://" + stream + "/fw.bin\",\"OtaType\":\"CONTINUOUS\","
                + "\"OtaTargetQueryString\":\"thingName:sensor\",\"OtaSchedulingConfig\":{\"StartTime\":\""
                + Instant.now().plusSeconds(3600) + "\"}}").post("/ota-tasks").then().statusCode(200).extract()
                .path("TaskId");
        mi().get("/ota-tasks/" + scheduled).then().body("Status", equalTo("SCHEDULED")).body("Target[0]",
                equalTo(thingArn));
        mi().delete("/ota-tasks/" + task).then().statusCode(200);
        mi().delete("/ota-tasks/" + scheduled).then().statusCode(200);
        mi().delete("/ota-task-configurations/" + configuration).then().statusCode(200);

        List<JsonNode> messages = records(stream, 4);
        List<String> types = new ArrayList<>();
        messages.forEach(message -> types.add(message.path("messageType").asText()));
        assertTrue(types.contains("DEVICE_LIFE_CYCLE") && types.contains("DEVICE_OTA"), types.toString());
        JsonNode lifecycle = messages.stream().filter(m -> "DEVICE_LIFE_CYCLE".equals(m.path("messageType").asText()))
                .findFirst().orElseThrow();
        assertEquals("aws.iotmanagedintegrations", lifecycle.path("source").asText());
        assertEquals(thingArn, lifecycle.path("resources").get(0).asText());
        assertEquals("UNASSOCIATED", lifecycle.path("payload").path("status").asText());
        assertEquals(thing, lifecycle.path("payload").path("deviceDetails").path("id").asText());
        JsonNode ota = messages.stream().filter(m -> "DEVICE_OTA".equals(m.path("messageType").asText()))
                .findFirst().orElseThrow();
        assertEquals("CREATE_OTA", ota.path("payload").path("operation").asText());

        mi().body("{\"DestinationName\":\"events\"}").put("/notification-configurations/DEVICE_OTA").then()
                .statusCode(200);
        mi().get("/notification-configurations").then().body("NotificationConfigurationList.EventType",
                hasItems("DEVICE_LIFE_CYCLE", "DEVICE_OTA"));
        for (String type : List.of("DEVICE_LIFE_CYCLE", "DEVICE_OTA")) {
            mi().delete("/notification-configurations/" + type).then().statusCode(200);
        }
        mi().delete("/destinations/events").then().statusCode(200);
        mi().delete("/managed-things/" + thing).then().statusCode(200);
    }

    static ValidatableResponse kinesis(String action, String body) {
        return signed("kinesis").header("X-Amz-Target", "Kinesis_20131202." + action)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then().statusCode(200);
    }

    /** Every record on the stream's first shard, decoded, once at least {@code count} have arrived. */
    static List<JsonNode> records(String stream, int count) throws Exception {
        String iterator = json(kinesis("GetShardIterator", "{\"StreamName\":\"" + stream + "\",\"ShardId\":"
                + "\"shardId-000000000000\",\"ShardIteratorType\":\"TRIM_HORIZON\"}")).path("ShardIterator").asText();
        List<JsonNode> messages = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 15_000;
        while (messages.size() < count && System.currentTimeMillis() < deadline) {
            JsonNode page = json(kinesis("GetRecords", "{\"ShardIterator\":\"" + iterator + "\"}"));
            for (JsonNode record : page.path("Records")) {
                messages.add(MAPPER.readTree(Base64.getDecoder().decode(record.path("Data").asText())));
            }
            iterator = page.path("NextShardIterator").asText();
            Thread.sleep(200);
        }
        return messages;
    }
}
