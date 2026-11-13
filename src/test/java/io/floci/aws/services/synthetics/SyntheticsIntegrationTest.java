package io.floci.aws.services.synthetics;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetics control plane: runtime versions, canary validation, the backing Lambda layer and function a canary
 * creates (or the CREATE_FAILED state when Lambda can't assume its role), updates, groups, tags and deletion.
 * Canary runs are covered by {@link SyntheticsDockerIntegrationTest}.
 */
@QuarkusTest
class SyntheticsIntegrationTest {

    static final String ACCOUNT = "000000000000";
    static final String SCRIPT = """
            const synthetics = require("Synthetics");
            const log = require("SyntheticsLogger");
            exports.handler = async function () {
              return await synthetics.executeStep("heartbeat", async function () { log.info("heartbeat ok"); });
            };
            """;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static RequestSpecification synthetics() {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/synthetics/aws4_request")
                .contentType("application/json");
    }

    static String zip(String path, String script) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(path));
            zip.write(script.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    /** A role Lambda can assume, as the Synthetics console creates for a canary. */
    static String role(String name) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":"
                + "{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/iam/"
                        + "aws4_request").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("Version", "2010-05-08").formParam("RoleName", name)
                .formParam("AssumeRolePolicyDocument", trust).post("/").then().statusCode(200);
        return "arn:aws:iam::" + ACCOUNT + ":role/" + name;
    }

    static void bucket(String name) {
        given().put("/" + name).then().statusCode(200);
    }

    static String canaryBody(String name, String roleArn, String bucket, String schedule, String code) {
        return "{\"Name\":\"" + name + "\",\"Code\":{\"Handler\":\"index.handler\",\"ZipFile\":\"" + code + "\"},"
                + "\"ArtifactS3Location\":\"s3://" + bucket + "/" + name + "\",\"ExecutionRoleArn\":\"" + roleArn
                + "\",\"Schedule\":{\"Expression\":\"" + schedule + "\"},\"RuntimeVersion\":\"syn-nodejs-puppeteer-16.1\","
                + "\"ProvisionedResourceCleanup\":\"AUTOMATIC\",\"Tags\":{\"team\":\"web\"}}";
    }

    static ValidatableResponse get(String name) {
        return synthetics().get("/canary/" + name).then();
    }

    static void awaitState(String name, String state) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                get(name).statusCode(200).body("Canary.Status.State", equalTo(state)));
    }

    @Test
    void runtimeVersions() {
        List<String> names = synthetics().body("{}").post("/runtime-versions").then().statusCode(200).extract()
                .path("RuntimeVersions.VersionName");
        assertTrue(names.contains("syn-nodejs-puppeteer-16.1"));
        assertTrue(names.contains("syn-python-selenium-14.0"));
        synthetics().body("{}").post("/runtime-versions").then()
                .body("RuntimeVersions.find { it.VersionName == 'syn-nodejs-puppeteer-7.0' }.DeprecationDate",
                        notNullValue())
                .body("RuntimeVersions.find { it.VersionName == 'syn-nodejs-puppeteer-16.1' }.DeprecationDate",
                        equalTo(null));
    }

    @Test
    void createValidatesTheCanary() {
        String role = role("synthetics-validation");
        bucket("synthetics-validation");
        String code = zip("nodejs/node_modules/index.js", SCRIPT);
        synthetics().body(canaryBody("deprecated", role, "synthetics-validation", "rate(5 minutes)", code)
                        .replace("syn-nodejs-puppeteer-16.1", "syn-nodejs-puppeteer-7.0")).post("/canary").then()
                .statusCode(400).body("message", containsString("deprecated"));
        synthetics().body(canaryBody("slow", role, "synthetics-validation", "rate(2 hours)", code)).post("/canary")
                .then().statusCode(400).body("message", containsString("rate(1 hour)"));
        synthetics().body(canaryBody("noscript", role, "synthetics-validation", "rate(5 minutes)",
                zip("nodejs/node_modules/other.js", SCRIPT))).post("/canary").then().statusCode(400)
                .body("message", containsString("index.handler"));
        synthetics().body(canaryBody("toolong", role, "synthetics-validation", "rate(1 minute)", code)
                        .replace("\"ProvisionedResourceCleanup\"", "\"RunConfig\":{\"TimeoutInSeconds\":120},"
                                + "\"ProvisionedResourceCleanup\"")).post("/canary").then().statusCode(400)
                .body("message", containsString("frequency"));
        synthetics().get("/canary/missing").then().statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void aRoleLambdaCantAssumeFailsTheCreate() {
        bucket("synthetics-norole");
        synthetics().body(canaryBody("norole", "arn:aws:iam::" + ACCOUNT + ":role/missing", "synthetics-norole",
                "rate(5 minutes)", zip("nodejs/node_modules/index.js", SCRIPT))).post("/canary").then()
                .statusCode(200).body("Canary.Status.State", equalTo("CREATING"));
        awaitState("norole", "ERROR");
        get("norole").body("Canary.Status.StateReasonCode", equalTo("CREATE_FAILED"))
                .body("Canary.Status.StateReason", containsString("cannot be assumed by Lambda"));
        synthetics().post("/canary/norole/start").then().statusCode(409);
        synthetics().delete("/canary/norole").then().statusCode(200);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> get("norole").statusCode(404));
    }

    @Test
    void canaryLifecycleGroupsAndTags() {
        String role = role("synthetics-lifecycle");
        bucket("synthetics-lifecycle");
        String created = synthetics().body(canaryBody("heartbeat", role, "synthetics-lifecycle", "rate(5 minutes)",
                        zip("nodejs/node_modules/index.js", SCRIPT))).post("/canary").then().statusCode(200)
                .body("Canary.Status.State", equalTo("CREATING")).extract().path("Canary.Id");
        synthetics().body(canaryBody("heartbeat", role, "synthetics-lifecycle", "rate(5 minutes)",
                zip("nodejs/node_modules/index.js", SCRIPT))).post("/canary").then().statusCode(400);
        awaitState("heartbeat", "READY");
        String engine = get("heartbeat")
                .body("Canary.RunConfig.TimeoutInSeconds", equalTo(300))
                .body("Canary.RunConfig.MemoryInMB", equalTo(1000))
                .body("Canary.SuccessRetentionPeriodInDays", equalTo(31))
                .body("Canary.Code.SourceLocationArn", containsString(":layer:cwsyn-heartbeat-" + created + ":1"))
                .body("Canary.Tags.team", equalTo("web")).extract().path("Canary.EngineArn");
        assertEquals("cwsyn-heartbeat-" + created, SyntheticsEngine.functionNameOf(engine));
        assertTrue(engine.endsWith(":1"), engine);
        given().get("/2015-03-31/functions/cwsyn-heartbeat-" + created).then().statusCode(200)
                .body("Configuration.Runtime", equalTo("nodejs22.x"))
                .body("Configuration.Role", equalTo(role))
                .body("Configuration.Layers.Arn", hasItem(containsString(":layer:cwsyn-heartbeat-" + created)));
        synthetics().post("/canary/heartbeat/stop").then().statusCode(409);

        // Update the schedule: the canary passes through UPDATING and runs on a new backing function version.
        synthetics().body("{\"Schedule\":{\"Expression\":\"rate(10 minutes)\"},\"RunConfig\":"
                + "{\"TimeoutInSeconds\":60}}").patch("/canary/heartbeat").then().statusCode(200);
        get("heartbeat").body("Canary.Status.State", equalTo("UPDATING"));
        awaitState("heartbeat", "READY");
        get("heartbeat").body("Canary.Schedule.Expression", equalTo("rate(10 minutes)"))
                .body("Canary.RunConfig.TimeoutInSeconds", equalTo(60))
                .body("Canary.EngineArn", not(equalTo(engine)));
        synthetics().body("{\"Names\":[\"heartbeat\"]}").post("/canaries").then().statusCode(200)
                .body("Canaries.Name", equalTo(List.of("heartbeat")));
        synthetics().body("{}").post("/canary/heartbeat/runs").then().statusCode(200)
                .body("CanaryRuns.size()", equalTo(0));

        // Groups are global; they hold canaries by ARN.
        String canaryArn = "arn:aws:synthetics:us-east-1:" + ACCOUNT + ":canary:heartbeat";
        String groupArn = synthetics().body("{\"Name\":\"Web\",\"Tags\":{\"env\":\"dev\"}}").post("/group").then()
                .statusCode(200).body("Group.Arn", startsWith("arn:aws:synthetics:us-east-1:" + ACCOUNT + ":group:"))
                .extract().path("Group.Arn");
        synthetics().body("{\"Name\":\"web\"}").post("/group").then().statusCode(409);
        String groupId = groupArn.substring(groupArn.lastIndexOf(':') + 1);
        for (String identifier : List.of("Web", groupId, groupArn)) {
            synthetics().get("/group/{id}", identifier).then().statusCode(200)
                    .body("Group.Name", equalTo("Web")).body("Group.Tags.env", equalTo("dev"));
        }
        synthetics().body("{\"ResourceArn\":\"" + canaryArn.replace("heartbeat", "missing") + "\"}")
                .patch("/group/Web/associate").then().statusCode(404);
        synthetics().body("{\"ResourceArn\":\"" + canaryArn + "\"}").patch("/group/Web/associate").then()
                .statusCode(200);
        synthetics().body("{\"ResourceArn\":\"" + canaryArn + "\"}").patch("/group/Web/associate").then()
                .statusCode(200);
        synthetics().body("{}").post("/group/Web/resources").then().statusCode(200)
                .body("Resources", equalTo(List.of(canaryArn)));
        synthetics().body("{}").post("/resource/{id}/groups", canaryArn).then().statusCode(200)
                .body("Groups.Name", equalTo(List.of("Web")));
        synthetics().body("{}").post("/groups").then().statusCode(200).body("Groups.Name", hasItem("Web"));

        // Tags on canaries and groups.
        synthetics().body("{\"Tags\":{\"owner\":\"sre\"}}").post("/tags/{id}", canaryArn).then()
                .statusCode(200);
        synthetics().queryParam("tagKeys", "team").delete("/tags/{id}", canaryArn).then().statusCode(200);
        synthetics().get("/tags/{id}", canaryArn).then().statusCode(200)
                .body("Tags", equalTo(Map.of("owner", "sre")));
        synthetics().body("{\"Tags\":{\"owner\":\"sre\"}}").post("/tags/{id}", groupArn).then().statusCode(200);
        synthetics().get("/tags/{id}", groupArn).then().statusCode(200).body("Tags.owner", equalTo("sre"));
        synthetics().get("/tags/{id}", canaryArn.replace("heartbeat", "missing")).then().statusCode(404)
                .body("__type", containsString("NotFoundException"));

        // Deleting the canary removes its backing function and layer and takes it out of its groups.
        synthetics().delete("/canary/heartbeat").then().statusCode(200);
        get("heartbeat").body("Canary.Status.State", equalTo("DELETING"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> get("heartbeat").statusCode(404));
        given().get("/2015-03-31/functions/cwsyn-heartbeat-" + created).then().statusCode(404);
        synthetics().body("{}").post("/group/Web/resources").then().body("Resources.size()", equalTo(0));
        synthetics().body("{\"ResourceArn\":\"" + canaryArn + "\"}").patch("/group/Web/disassociate").then()
                .statusCode(404);
        synthetics().delete("/group/" + groupId).then().statusCode(200);
        synthetics().get("/group/Web").then().statusCode(404);
    }
}
