package io.floci.aws.services.medialive;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * MediaLive: input security groups and push and pull inputs; channels created, checked, updated, scheduled and
 * deleted; multiplexes and programs; reservations; workflow monitor templates and signal maps; MediaLive Anywhere.
 */
@QuarkusTest
class MediaLiveIntegrationTest {

    static final String ENCODER = "{\"audioDescriptions\":[{\"audioSelectorName\":\"default\",\"name\":\"audio1\","
            + "\"codecSettings\":{\"aacSettings\":{\"bitrate\":96000,\"sampleRate\":48000}}}],\"videoDescriptions\":"
            + "[{\"name\":\"video1\",\"width\":640,\"height\":360,\"codecSettings\":{\"h264Settings\":{\"bitrate\":"
            + "800000,\"rateControlMode\":\"CBR\"}}}],\"outputGroups\":[{\"name\":\"udp\",\"outputGroupSettings\":"
            + "{\"udpGroupSettings\":{}},\"outputs\":[{\"outputName\":\"out\",\"videoDescriptionName\":\"video1\","
            + "\"audioDescriptionNames\":[\"audio1\"],\"outputSettings\":{\"udpOutputSettings\":{\"destination\":"
            + "{\"destinationRefId\":\"dest1\"},\"containerSettings\":{\"m2tsSettings\":{}}}}}]}],"
            + "\"timecodeConfig\":{\"source\":\"SYSTEMCLOCK\"}}";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static RequestSpecification ml() {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/medialive/aws4_request")
                .contentType("application/json");
    }

    static ValidatableResponse channel(String id) {
        return ml().get("/prod/channels/" + id).then().log().ifValidationFails();
    }

    static void awaitState(String id, String state) {
        await().atMost(Duration.ofSeconds(10)).until(() -> state.equals(channel(id).extract().path("state")));
    }

    static String pullInput(String name, String url) {
        return ml().body("{\"name\":\"" + name + "\",\"type\":\"MP4_FILE\",\"sources\":[{\"url\":\"" + url + "\"}]}")
                .post("/prod/inputs").then().statusCode(201).body("input.inputClass", equalTo("SINGLE_PIPELINE"))
                .extract().path("input.id");
    }

    @Test
    void securityGroupsAndInputs() {
        String group = ml().body("{\"whitelistRules\":[{\"cidr\":\"10.0.0.0/16\"}],\"tags\":{\"team\":\"live\"}}")
                .post("/prod/inputSecurityGroups").then().statusCode(200)
                .body("securityGroup.id", not(equalTo(null)))
                .body("securityGroup.arn", containsString(":inputSecurityGroup:"))
                .body("securityGroup.state", equalTo("IDLE")).extract().path("securityGroup.id");
        ml().body("{\"whitelistRules\":[{\"cidr\":\"not-a-cidr\"}]}").post("/prod/inputSecurityGroups").then()
                .statusCode(400);
        String push = ml().body("{\"name\":\"push\",\"type\":\"RTMP_PUSH\",\"inputSecurityGroups\":[\"" + group
                        + "\"],\"destinations\":[{\"streamName\":\"live/a\"},{\"streamName\":\"live/b\"}]}")
                .post("/prod/inputs").then().statusCode(201).body("input.inputClass", equalTo("STANDARD"))
                .body("input.destinations", hasSize(2)).body("input.destinations[0].url", startsWith("rtmp://"))
                .body("input.destinations[0].url", containsString("/live/a")).body("input.state",
                        equalTo("DETACHED")).extract().path("input.id");
        ml().body("{\"name\":\"nogroup\",\"type\":\"UDP_PUSH\"}").post("/prod/inputs").then().statusCode(400);
        ml().get("/prod/inputSecurityGroups/" + group).then().statusCode(200).body("inputs", hasItem(push))
                .body("state", equalTo("IN_USE")).body("tags.team", equalTo("live"));
        ml().delete("/prod/inputSecurityGroups/" + group).then().statusCode(400);
        ml().body("{\"whitelistRules\":[{\"cidr\":\"192.168.0.0/24\"}]}").put("/prod/inputSecurityGroups/" + group)
                .then().statusCode(200).body("securityGroup.whitelistRules[0].cidr", equalTo("192.168.0.0/24"));
        String pull = pullInput("pull", "s3://media/in.mp4");
        ml().body("{\"sources\":[{\"url\":\"s3://media/other.mp4\"}]}").put("/prod/inputs/" + pull).then()
                .statusCode(200).body("input.sources[0].url", equalTo("s3://media/other.mp4"));
        String partner = ml().post("/prod/inputs/" + push + "/partners").then().statusCode(201)
                .body("input.inputPartnerIds", hasItem(push)).extract().path("input.id");
        ml().get("/prod/inputs").then().body("inputs.id", hasItem(pull));
        ml().get("/prod/inputs/9999999").then().statusCode(404).header("x-amzn-ErrorType",
                containsString("NotFoundException"));
        for (String id : new String[] {push, pull, partner}) {
            ml().delete("/prod/inputs/" + id).then().statusCode(200);
        }
        ml().delete("/prod/inputSecurityGroups/" + group).then().statusCode(200);
        ml().get("/prod/inputDevices").then().statusCode(200).body("inputDevices", hasSize(0));
        ml().get("/prod/inputDevices/hd-123").then().statusCode(404);
    }

    @Test
    void channelsAreCheckedUpdatedScheduledAndDeleted() {
        String input = pullInput("channel-in", "s3://media/in.mp4");
        String body = "{\"name\":\"live\",\"channelClass\":\"SINGLE_PIPELINE\",\"roleArn\":"
                + "\"arn:aws:iam::000000000000:role/medialive\",\"inputAttachments\":[{\"inputAttachmentName\":"
                + "\"main\",\"inputId\":\"" + input + "\"}],\"destinations\":[{\"id\":\"dest1\",\"settings\":[{"
                + "\"url\":\"udp://127.0.0.1:5000\"}]}],\"encoderSettings\":" + ENCODER + "}";
        ml().body(body.replace("\"dest1\",\"settings\"", "\"other\",\"settings\"")).post("/prod/channels").then()
                .statusCode(422).header("x-amzn-ErrorType", containsString("UnprocessableEntityException"));
        String id = ml().body(body).post("/prod/channels").then().statusCode(201)
                .body("channel.state", equalTo("CREATING")).body("channel.arn", containsString(":channel:"))
                .extract().path("channel.id");
        awaitState(id, "IDLE");
        ml().get("/prod/inputs/" + input).then().body("state", equalTo("ATTACHED"))
                .body("attachedChannels", hasItem(id));
        ml().delete("/prod/inputs/" + input).then().statusCode(409);
        ml().body("{\"logLevel\":\"ERROR\"}").put("/prod/channels/" + id).then().statusCode(200)
                .body("channel.logLevel", equalTo("ERROR"));
        ml().get("/prod/channels/" + id + "/schedule").then().statusCode(200).body("scheduleActions", hasSize(0));
        ml().body("{\"creates\":{\"scheduleActions\":[{\"actionName\":\"switch\",\"scheduleActionStartSettings\":"
                + "{\"fixedModeScheduleActionStartSettings\":{\"time\":\"" + Instant.now().plusSeconds(3600)
                + "\"}},\"scheduleActionSettings\":{\"inputSwitchSettings\":{\"inputAttachmentNameReference\":"
                + "\"main\"}}}]}}").put("/prod/channels/" + id + "/schedule").then().statusCode(200)
                .body("creates.scheduleActions", hasSize(1));
        ml().body("{\"creates\":{\"scheduleActions\":[{\"actionName\":\"bad\",\"scheduleActionStartSettings\":"
                + "{\"fixedModeScheduleActionStartSettings\":{\"time\":\"" + Instant.now().plusSeconds(3600)
                + "\"}},\"scheduleActionSettings\":{\"inputSwitchSettings\":{\"inputAttachmentNameReference\":"
                + "\"missing\"}}}]}}").put("/prod/channels/" + id + "/schedule").then().statusCode(422);
        ml().get("/prod/channels/" + id + "/schedule").then().body("scheduleActions[0].actionName", equalTo("switch"));
        ml().delete("/prod/channels/" + id + "/schedule").then().statusCode(200);
        ml().get("/prod/channels/" + id + "/alerts").then().statusCode(200).body("alerts", hasSize(0));
        ml().get("/prod/channels/" + id + "/thumbnails?pipelineId=0&thumbnailType=CURRENT_ACTIVE").then()
                .statusCode(400);
        ml().post("/prod/channels/" + id + "/stop").then().statusCode(409);
        ml().get("/prod/channels").then().body("channels.id", hasItem(id));
        ml().get("/prod/tags/arn:aws:medialive:us-east-1:000000000000:channel:" + id).then().statusCode(200);
        ml().body("{\"tags\":{\"k\":\"v\"}}").post("/prod/tags/arn:aws:medialive:us-east-1:000000000000:channel:" + id)
                .then().statusCode(204);
        channel(id).body("tags.k", equalTo("v"));
        ml().delete("/prod/channels/" + id).then().statusCode(200).body("state", equalTo("DELETING"));
        await().atMost(Duration.ofSeconds(10)).until(() -> ml().get("/prod/channels/" + id).statusCode() == 404);
        ml().get("/prod/inputs/" + input).then().body("state", equalTo("DETACHED"));
        ml().delete("/prod/inputs/" + input).then().statusCode(200);
    }

    @Test
    void multiplexesReservationsMonitorsAndAnywhere() {
        String multiplex = ml().body("{\"name\":\"mux\",\"requestId\":\"r1\",\"availabilityZones\":[\"us-east-1a\","
                        + "\"us-east-1b\"],\"multiplexSettings\":{\"transportStreamBitrate\":1000000,"
                        + "\"transportStreamId\":1}}").post("/prod/multiplexes").then().statusCode(201)
                .body("multiplex.destinations", hasSize(2)).extract().path("multiplex.id");
        ml().body("{\"programName\":\"p1\",\"requestId\":\"r2\",\"multiplexProgramSettings\":{\"programNumber\":1}}")
                .post("/prod/multiplexes/" + multiplex + "/programs").then().statusCode(201)
                .body("multiplexProgram.packetIdentifiersMap.pmtPid", equalTo(481));
        ml().get("/prod/multiplexes/" + multiplex + "/programs").then().body("multiplexPrograms.programName",
                hasItem("p1"));
        ml().delete("/prod/multiplexes/" + multiplex + "/programs/p1").then().statusCode(200);
        await().atMost(Duration.ofSeconds(10)).until(() -> "IDLE".equals(ml().get("/prod/multiplexes/" + multiplex)
                .then().extract().path("state")));
        ml().delete("/prod/multiplexes/" + multiplex).then().statusCode(202);

        String offering = ml().get("/prod/offerings?resourceType=CHANNEL").then().statusCode(200).extract()
                .path("offerings[0].offeringId");
        String reservation = ml().body("{\"count\":1,\"name\":\"year\",\"requestId\":\"r3\"}")
                .post("/prod/offerings/" + offering + "/purchase").then().statusCode(201)
                .body("reservation.state", equalTo("ACTIVE")).extract().path("reservation.reservationId");
        ml().delete("/prod/reservations/" + reservation).then().statusCode(400);

        String group = ml().body("{\"name\":\"alarms\",\"requestId\":\"r4\"}")
                .post("/prod/cloudwatch-alarm-template-groups").then().statusCode(201)
                .body("id", startsWith("awg-")).extract().path("id");
        ml().body("{\"name\":\"bitrate\",\"requestId\":\"r5\",\"groupIdentifier\":\"alarms\",\"targetResourceType\":"
                + "\"MEDIALIVE_CHANNEL\",\"metricName\":\"NetworkIn\",\"treatMissingData\":\"missing\","
                + "\"comparisonOperator\":\"LessThanThreshold\",\"statistic\":\"Average\",\"period\":60,"
                + "\"evaluationPeriods\":1,\"threshold\":1}").post("/prod/cloudwatch-alarm-templates").then()
                .statusCode(201).body("groupId", equalTo(group));
        ml().get("/prod/cloudwatch-alarm-template-groups").then().body("cloudWatchAlarmTemplateGroups[0]"
                + ".templateCount", equalTo(1));
        String map = ml().body("{\"name\":\"map\",\"requestId\":\"r6\",\"discoveryEntryPointArn\":"
                        + "\"arn:aws:medialive:us-east-1:000000000000:channel:1234567\","
                        + "\"cloudWatchAlarmTemplateGroupIdentifiers\":[\"" + group + "\"]}")
                .post("/prod/signal-maps").then().statusCode(201).body("status", equalTo("CREATE_IN_PROGRESS"))
                .extract().path("id");
        await().atMost(Duration.ofSeconds(10)).until(() -> "CREATE_COMPLETE".equals(ml().get("/prod/signal-maps/"
                + map).then().extract().path("status")));
        ml().post("/prod/signal-maps/" + map + "/monitor-deployment").then().statusCode(202)
                .body("monitorDeployment.status", equalTo("DEPLOYMENT_IN_PROGRESS"));
        ml().delete("/prod/cloudwatch-alarm-template-groups/" + group).then().statusCode(409);

        String network = ml().body("{\"name\":\"net\",\"ipPools\":[{\"cidr\":\"10.1.0.0/24\"}]}")
                .post("/prod/networks").then().statusCode(201).extract().path("id");
        String cluster = ml().body("{\"name\":\"onprem\",\"clusterType\":\"ON_PREMISES\",\"networkSettings\":"
                        + "{\"defaultRoute\":\"lan\",\"interfaceMappings\":[{\"logicalInterfaceName\":\"lan\","
                        + "\"networkId\":\"" + network + "\"}]}}").post("/prod/clusters").then().statusCode(201)
                .extract().path("id");
        String node = ml().body("{\"name\":\"n1\",\"role\":\"ACTIVE\"}").post("/prod/clusters/" + cluster + "/nodes")
                .then().statusCode(201).body("state", equalTo("CREATED")).extract().path("id");
        ml().body("{\"state\":\"ACTIVE\"}").put("/prod/clusters/" + cluster + "/nodes/" + node + "/state").then()
                .statusCode(400);
        ml().delete("/prod/clusters/" + cluster).then().statusCode(409);
        ml().delete("/prod/clusters/" + cluster + "/nodes/" + node).then().statusCode(202);
        ml().delete("/prod/networks/" + network).then().statusCode(409);
        ml().delete("/prod/clusters/" + cluster).then().statusCode(202);
        ml().get("/prod/sdiSources").then().statusCode(200);
    }
}
