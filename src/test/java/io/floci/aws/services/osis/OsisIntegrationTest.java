package io.floci.aws.services.osis;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenSearch Ingestion without an engine ({@code engine: none}): pipelines through their states, configuration
 * validation, blueprints, VPC pipelines and their endpoints, resource policies, tags, and the errors each makes.
 * Pipelines run on Data Prepper in {@link OsisDockerIntegrationTest}.
 */
@QuarkusTest
class OsisIntegrationTest {

    static final String API = "/2022-01-01/osis";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification osis() {
        return given().header("Authorization", auth("osis")).contentType("application/json");
    }

    static String role(String name, String service) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"" + service + "\"},\"Action\":\"sts:AssumeRole\"}]}";
        return given().header("Authorization", auth("iam")).formParam("Action", "CreateRole")
                .formParam("RoleName", name).formParam("AssumeRolePolicyDocument", trust).post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    private static RequestSpecification ec2(String action) {
        return given().header("Authorization", auth("ec2")).formParam("Action", action)
                .formParam("Version", "2016-11-15");
    }

    /** A VPC with one subnet and security group: {vpcId, subnetId, groupId}. */
    static String[] network(int octet) {
        String vpc = ec2("CreateVpc").formParam("CidrBlock", "10." + octet + ".0.0/16").post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateVpcResponse.vpc.vpcId");
        String subnet = ec2("CreateSubnet").formParam("VpcId", vpc).formParam("CidrBlock", "10." + octet + ".1.0/24")
                .post("/").then().statusCode(200).extract().xmlPath().getString("CreateSubnetResponse.subnet.subnetId");
        String group = ec2("CreateSecurityGroup").formParam("GroupName", "osis-" + octet)
                .formParam("GroupDescription", "osis").formParam("VpcId", vpc).post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateSecurityGroupResponse.groupId");
        return new String[] {vpc, subnet, group};
    }

    static String config(String role, String bucket) {
        return "version: \"2\"\nlog-pipeline:\n  source:\n    http:\n      path: \"/logs/ingest\"\n  sink:\n"
                + "    - s3:\n        aws:\n          sts_role_arn: \"" + role + "\"\n          region: \"us-east-1\"\n"
                + "        bucket: \"" + bucket + "\"\n        threshold:\n          event_collect_timeout: \"5s\"\n"
                + "        codec:\n          ndjson:\n";
    }

    static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    static String status(String name) {
        return osis().get(API + "/getPipeline/" + name).then().statusCode(200).extract().path("Pipeline.Status");
    }

    static void awaitStatus(String name, String expected) {
        await().atMost(Duration.ofSeconds(420)).pollInterval(Duration.ofMillis(250))
                .until(() -> expected.equals(status(name)));
    }

    @Test
    void pipelinesMoveThroughTheirStates() {
        String role = role("osis-states", "osis-pipelines.amazonaws.com");
        String created = osis().body("{\"PipelineName\":\"states\",\"MinUnits\":1,\"MaxUnits\":2,"
                        + "\"PipelineConfigurationBody\":" + json(config(role, "archive")) + ",\"Tags\":[{\"Key\":"
                        + "\"team\",\"Value\":\"logs\"}],\"LogPublishingOptions\":{\"IsLoggingEnabled\":true,"
                        + "\"CloudWatchLogDestination\":{\"LogGroup\":\"/aws/vendedlogs/states\"}}}")
                .post(API + "/createPipeline").then().statusCode(200)
                .body("Pipeline.Status", equalTo("CREATING"))
                .body("Pipeline.PipelineArn", equalTo("arn:aws:osis:us-east-1:000000000000:pipeline/states"))
                .body("Pipeline.IngestEndpointUrls[0]", containsString("states-"))
                .body("Pipeline.IngestEndpointUrls[0]", containsString(".us-east-1.osis."))
                .body("Pipeline.Destinations[0].ServiceName", equalTo("S3"))
                .body("Pipeline.Destinations[0].Endpoint", equalTo("archive"))
                .extract().path("Pipeline.PipelineName");
        assertEquals("states", created);
        osis().body("{\"PipelineName\":\"states\",\"MinUnits\":1,\"MaxUnits\":1,\"PipelineConfigurationBody\":"
                + json(config(role, "archive")) + "}").post(API + "/createPipeline").then().statusCode(409)
                .body("__type", containsString("ResourceAlreadyExistsException"));
        osis().body("{\"MinUnits\":2}").put(API + "/updatePipeline/states").then().statusCode(409)
                .body("__type", containsString("ConflictException"));
        awaitStatus("states", "ACTIVE");
        osis().get(API + "/getPipelineChangeProgress/states").then().statusCode(200)
                .body("ChangeProgressStatuses[0].Status", equalTo("COMPLETED"))
                .body("ChangeProgressStatuses[0].TotalNumberOfStages", equalTo(3));
        osis().get(API + "/listPipelines").then().statusCode(200)
                .body("Pipelines.PipelineName", hasItem("states"))
                .body("Pipelines.find { it.PipelineName == 'states' }.Tags[0].Key", equalTo("team"));

        osis().body("{\"MinUnits\":2,\"MaxUnits\":4,\"PipelineConfigurationBody\":"
                + json(config(role, "archive-2")) + "}").put(API + "/updatePipeline/states").then().statusCode(200)
                .body("Pipeline.Status", equalTo("UPDATING"));
        awaitStatus("states", "ACTIVE");
        osis().get(API + "/getPipeline/states").then().statusCode(200).body("Pipeline.MinUnits", equalTo(2))
                .body("Pipeline.MaxUnits", equalTo(4)).body("Pipeline.Destinations[0].Endpoint", equalTo("archive-2"))
                .body("Pipeline.LogPublishingOptions.CloudWatchLogDestination.LogGroup",
                        equalTo("/aws/vendedlogs/states"));

        osis().body("{\"MinUnits\":5,\"MaxUnits\":4}").put(API + "/updatePipeline/states").then().statusCode(400)
                .body("message", containsString("MinUnits"));
        osis().put(API + "/startPipeline/states").then().statusCode(409);
        osis().put(API + "/stopPipeline/states").then().statusCode(200).body("Pipeline.Status", equalTo("STOPPING"));
        awaitStatus("states", "STOPPED");
        osis().put(API + "/stopPipeline/states").then().statusCode(409);
        osis().put(API + "/startPipeline/states").then().statusCode(200).body("Pipeline.Status",
                equalTo("STARTING"));
        awaitStatus("states", "ACTIVE");

        String arn = "arn:aws:osis:us-east-1:000000000000:pipeline/states";
        osis().queryParam("arn", arn).body("{\"Tags\":[{\"Key\":\"env\",\"Value\":\"dev\"}]}")
                .post(API + "/tagResource").then().statusCode(200);
        osis().queryParam("arn", arn).get(API + "/listTagsForResource").then().statusCode(200)
                .body("Tags.Key", hasItem("env")).body("Tags.Key", hasItem("team"));
        osis().queryParam("arn", arn).body("{\"TagKeys\":[\"team\"]}").post(API + "/untagResource/").then()
                .statusCode(200);
        osis().queryParam("arn", arn).get(API + "/listTagsForResource/").then().statusCode(200)
                .body("Tags.Key", not(hasItem("team")));

        osis().delete(API + "/deletePipeline/states").then().statusCode(200);
        assertEquals("DELETING", status("states"));
        await().atMost(Duration.ofSeconds(30)).until(() -> osis().get(API + "/getPipeline/states").then().extract()
                .statusCode() == 404);
        osis().get(API + "/getPipeline/states").then().statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void configurationsAndRolesAreValidated() {
        String role = role("osis-validate", "osis-pipelines.amazonaws.com");
        osis().body("{\"PipelineConfigurationBody\":" + json(config(role, "b")) + "}")
                .post(API + "/validatePipeline").then().statusCode(200).body("isValid", equalTo(true))
                .body("Errors", empty());
        String bad = "version: \"2\"\nbad-pipeline:\n  source:\n    http:\n      path: \"/logs\"\n  buffer:\n"
                + "    bounded_blocking:\n  processor:\n    - not_a_processor:\n  sink:\n"
                + "    - not-a-real-sink-plugin:\n        some_option: true\n    - pipeline:\n        name: missing\n";
        osis().body("{\"PipelineConfigurationBody\":" + json(bad) + "}").post(API + "/validatePipeline").then()
                .statusCode(200).body("isValid", equalTo(false)).body("Errors", hasSize(4))
                .body("Errors.Message", hasItem(containsString("not-a-real-sink-plugin")))
                .body("Errors.Message", hasItem(containsString("not_a_processor")))
                .body("Errors.Message", hasItem(containsString("buffer")))
                .body("Errors.Message", hasItem(containsString("undefined sub-pipeline missing")));
        osis().body("{\"PipelineConfigurationBody\":\"version: [\"}").post(API + "/validatePipeline").then()
                .statusCode(200).body("isValid", equalTo(false))
                .body("Errors[0].Message", containsString("isn't valid YAML"));
        osis().body("{\"PipelineName\":\"invalid\",\"MinUnits\":1,\"MaxUnits\":1,\"PipelineConfigurationBody\":"
                        + json(bad) + "}").post(API + "/createPipeline").then().statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("not-a-real-sink-plugin"));

        String untrusted = role("osis-untrusted", "lambda.amazonaws.com");
        osis().body("{\"PipelineName\":\"untrusted\",\"MinUnits\":1,\"MaxUnits\":1,\"PipelineConfigurationBody\":"
                        + json(config(untrusted, "b")) + "}").post(API + "/createPipeline").then().statusCode(400)
                .body("message", containsString("doesn't trust osis-pipelines.amazonaws.com"));
        osis().body("{\"PipelineName\":\"norole\",\"MinUnits\":1,\"MaxUnits\":1,\"PipelineConfigurationBody\":"
                        + json(config("arn:aws:iam::000000000000:role/absent", "b")) + "}").post(API + "/createPipeline")
                .then().statusCode(400).body("message", containsString("absent"));
        String unroled = config(role, "b").replace("          sts_role_arn: \"" + role + "\"\n", "");
        osis().body("{\"PipelineName\":\"unroled\",\"MinUnits\":1,\"MaxUnits\":1,\"PipelineConfigurationBody\":"
                        + json(unroled) + "}").post(API + "/createPipeline").then().statusCode(400)
                .body("message", containsString("PipelineRoleArn"));
        osis().body("{\"PipelineName\":\"pipeline-role\",\"MinUnits\":1,\"MaxUnits\":1,\"PipelineRoleArn\":\"" + role
                        + "\",\"PipelineConfigurationBody\":" + json(unroled) + "}").post(API + "/createPipeline")
                .then().statusCode(200).body("Pipeline.PipelineRoleArn", equalTo(role));
        osis().body("{\"PipelineName\":\"badlogs\",\"MinUnits\":1,\"MaxUnits\":1,\"PipelineConfigurationBody\":"
                        + json(config(role, "b")) + ",\"LogPublishingOptions\":{\"IsLoggingEnabled\":true,"
                        + "\"CloudWatchLogDestination\":{\"LogGroup\":\"/my/logs\"}}}").post(API + "/createPipeline")
                .then().statusCode(400).body("message", containsString("vendedlogs"));
        osis().body("{\"PipelineName\":\"Bad_Name\",\"MinUnits\":1,\"MaxUnits\":1,\"PipelineConfigurationBody\":"
                + json(config(role, "b")) + "}").post(API + "/createPipeline").then().statusCode(400);
        osis().body("{\"PipelineName\":\"units\",\"MinUnits\":1,\"MaxUnits\":200,\"PipelineConfigurationBody\":"
                + json(config(role, "b")) + "}").post(API + "/createPipeline").then().statusCode(400)
                .body("message", containsString("MaxUnits"));
        awaitStatus("pipeline-role", "ACTIVE");
        osis().delete(API + "/deletePipeline/pipeline-role").then().statusCode(200);
    }

    @Test
    void blueprintsAreValidConfigurations() {
        List<String> names = osis().post(API + "/listPipelineBlueprints").then().statusCode(200)
                .body("Blueprints[0].PipelineConfigurationBody", equalTo(null))
                .extract().path("Blueprints.BlueprintName");
        assertTrue(names.size() >= 5, names.toString());
        for (String name : names) {
            String body = osis().get(API + "/getPipelineBlueprint/" + name).then().statusCode(200)
                    .body("Blueprint.BlueprintName", equalTo(name)).body("Format", equalTo("YAML"))
                    .extract().path("Blueprint.PipelineConfigurationBody");
            osis().body("{\"PipelineConfigurationBody\":" + json(body) + "}").post(API + "/validatePipeline").then()
                    .statusCode(200).body("isValid", equalTo(true));
        }
        osis().queryParam("format", "JSON").get(API + "/getPipelineBlueprint/" + names.get(0)).then().statusCode(200)
                .body("Format", equalTo("JSON")).body("Blueprint.PipelineConfigurationBody", startsWith("{"));
        osis().get(API + "/getPipelineBlueprint/AWS-Nope").then().statusCode(404);
    }

    @Test
    void vpcPipelinesTakeEndpointsAndPolicies() {
        String role = role("osis-vpc", "osis-pipelines.amazonaws.com");
        String[] pipelineNet = network(61);
        String[] endpointNet = network(62);
        osis().body("{\"PipelineName\":\"vpc\",\"MinUnits\":1,\"MaxUnits\":1,\"PipelineConfigurationBody\":"
                        + json(config(role, "b")) + ",\"VpcOptions\":{\"SubnetIds\":[\"" + pipelineNet[1] + "\"],"
                        + "\"SecurityGroupIds\":[\"" + pipelineNet[2] + "\"]}}").post(API + "/createPipeline").then()
                .statusCode(200).body("Pipeline.VpcEndpoints[0].VpcId", equalTo(pipelineNet[0]))
                .body("Pipeline.VpcEndpoints[0].VpcEndpointId", startsWith("vpce-"));
        osis().body("{\"PipelineName\":\"public\",\"MinUnits\":1,\"MaxUnits\":1,\"PipelineConfigurationBody\":"
                + json(config(role, "b")) + "}").post(API + "/createPipeline").then().statusCode(200);
        osis().body("{\"PipelineName\":\"badsubnet\",\"MinUnits\":1,\"MaxUnits\":1,\"PipelineConfigurationBody\":"
                + json(config(role, "b")) + ",\"VpcOptions\":{\"SubnetIds\":[\"subnet-0000000000000000\"]}}")
                .post(API + "/createPipeline").then().statusCode(400).body("message", containsString("subnet"));
        awaitStatus("vpc", "ACTIVE");
        awaitStatus("public", "ACTIVE");
        String vpcArn = "arn:aws:osis:us-east-1:000000000000:pipeline/vpc";

        osis().body("{\"PipelineArn\":\"arn:aws:osis:us-east-1:000000000000:pipeline/public\",\"VpcOptions\":"
                        + "{\"SubnetIds\":[\"" + endpointNet[1] + "\"]}}").post(API + "/createPipelineEndpoint").then()
                .statusCode(400).body("message", equalTo("The pipeline is not configured for VPC."));
        osis().body("{\"PipelineArn\":\"" + vpcArn + "\",\"VpcOptions\":{\"SubnetIds\":[\"" + pipelineNet[1]
                        + "\"]}}").post(API + "/createPipelineEndpoint").then().statusCode(400)
                .body("message", containsString("same VPC"));
        String endpoint = osis().body("{\"PipelineArn\":\"" + vpcArn + "\",\"VpcOptions\":{\"SubnetIds\":[\""
                        + endpointNet[1] + "\"],\"SecurityGroupIds\":[\"" + endpointNet[2] + "\"]}}")
                .post(API + "/createPipelineEndpoint").then().statusCode(200).body("Status", equalTo("CREATING"))
                .body("VpcId", equalTo(endpointNet[0])).extract().path("EndpointId");
        assertEquals(20, endpoint.length());
        await().atMost(Duration.ofSeconds(30)).until(() -> "ACTIVE".equals(osis().get(API + "/listPipelineEndpoints")
                .then().extract().path("PipelineEndpoints.find { it.EndpointId == '" + endpoint + "' }.Status")));
        osis().get(API + "/listPipelineEndpoints").then().statusCode(200)
                .body("PipelineEndpoints.find { it.EndpointId == '" + endpoint + "' }.IngestEndpointUrl",
                        containsString(endpoint.toLowerCase()));
        osis().get(API + "/listPipelineEndpointConnections").then().statusCode(200)
                .body("PipelineEndpointConnections.find { it.EndpointId == '" + endpoint + "' }.VpcEndpointOwner",
                        equalTo("000000000000"));

        osis().get(API + "/resourcePolicy/" + vpcArn).then().statusCode(200).body("Policy", equalTo("{}"));
        osis().get(API + "/resourcePolicy/arn:aws:osis:us-east-1:000000000000:pipeline/never").then()
                .statusCode(200).body("Policy", equalTo("{}"));
        String rootPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":"
                + "{\"AWS\":[\"arn:aws:iam::111122223333:root\"]},\"Action\":\"osis:CreatePipelineEndpoint\","
                + "\"Resource\":\"" + vpcArn + "\"}]}";
        osis().body("{\"Policy\":" + json(rootPolicy) + "}").put(API + "/resourcePolicy/" + vpcArn).then()
                .statusCode(400).body("message", equalTo("Invalid resource policy."));
        String ingestPolicy = rootPolicy.replace("arn:aws:iam::111122223333:root", "111122223333")
                .replace("osis:CreatePipelineEndpoint", "osis:Ingest");
        osis().body("{\"Policy\":" + json(ingestPolicy) + "}").put(API + "/resourcePolicy/" + vpcArn).then()
                .statusCode(400);
        String policy = rootPolicy.replace("arn:aws:iam::111122223333:root", "111122223333");
        osis().body("{\"Policy\":" + json(policy) + "}").put(API + "/resourcePolicy/" + vpcArn).then()
                .statusCode(200).body("ResourceArn", equalTo(vpcArn));
        osis().get(API + "/resourcePolicy/" + vpcArn).then().statusCode(200)
                .body("Policy", containsString("osis:CreatePipelineEndpoint"));

        osis().body("{\"PipelineArn\":\"" + vpcArn + "\",\"EndpointIds\":[\"" + endpoint + "\"]}")
                .post(API + "/revokePipelineEndpointConnections").then().statusCode(200)
                .body("PipelineArn", equalTo(vpcArn));
        await().atMost(Duration.ofSeconds(30)).until(() -> "REVOKED".equals(osis()
                .get(API + "/listPipelineEndpointConnections").then().extract()
                .path("PipelineEndpointConnections.find { it.EndpointId == '" + endpoint + "' }.Status")));

        osis().delete(API + "/deletePipelineEndpoint/" + endpoint).then().statusCode(200);
        await().atMost(Duration.ofSeconds(30)).until(() -> osis().get(API + "/listPipelineEndpoints").then()
                .extract().path("PipelineEndpoints.find { it.EndpointId == '" + endpoint + "' }") == null);
        osis().delete(API + "/deletePipelineEndpoint/" + endpoint).then().statusCode(400);
        osis().delete(API + "/resourcePolicy/" + vpcArn).then().statusCode(200);
        osis().delete(API + "/resourcePolicy/" + vpcArn).then().statusCode(404);
        osis().delete(API + "/deletePipeline/vpc").then().statusCode(200);
        osis().delete(API + "/deletePipeline/public").then().statusCode(200);
    }
}
