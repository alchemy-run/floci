package io.floci.aws.services.omics;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs on their engines: a WDL workflow on Cromwell reads an S3 input and writes a file and a value, a Nextflow
 * workflow publishes a file, and a CWL tool writes its stdout; each run's outputs land under its output URI, its tasks
 * are listed, and its engine's log goes to CloudWatch Logs.
 */
@QuarkusTest
@TestProfile(OmicsEngineProfile.class)
@Tag("docker")
class OmicsDockerIntegrationTest {

    static final String WDL = "version 1.0\nworkflow Greet {\n  input {\n    String who\n    File notes\n  }\n"
            + "  call Hello { input: who = who, notes = notes }\n"
            + "  output {\n    String greeting = Hello.out\n    File report = Hello.report\n  }\n}\n"
            + "task Hello {\n  input {\n    String who\n    File notes\n  }\n"
            + "  command <<<\n    echo \"hello ~{who}\"\n    cat ~{notes} > report.txt\n  >>>\n"
            + "  output {\n    String out = read_string(stdout())\n    File report = \"report.txt\"\n  }\n"
            + "  runtime {\n    docker: \"ubuntu:22.04\"\n    cpus: 2\n    memory: \"4 GiB\"\n  }\n}\n";

    static final String NEXTFLOW = "nextflow.enable.dsl=2\nparams.who = 'world'\n"
            + "process HELLO {\n  publishDir '/mnt/workflow/pubdir', mode: 'copy'\n  input:\n  val who\n"
            + "  output:\n  path 'hello.txt'\n  script:\n  \"\"\"\n  echo hello $who > hello.txt\n  \"\"\"\n}\n"
            + "workflow {\n  HELLO(params.who)\n}\n";

    static final String CWL = "cwlVersion: v1.2\nclass: CommandLineTool\nbaseCommand: [echo]\ninputs:\n"
            + "  who:\n    type: string\n    inputBinding:\n      position: 1\nstdout: hello.txt\noutputs:\n"
            + "  out:\n    type: stdout\n";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String s3(String path) {
        return given().header("Authorization", OmicsIntegrationTest.auth("000000000000", "s3")).get(path).then()
                .statusCode(200).extract().asString();
    }

    static String run(String name, String engine, String main, String definition, String parameters)
            throws Exception {
        String workflow = OmicsIntegrationTest.omics().body("{\"name\":\"" + name + "\",\"engine\":\"" + engine
                        + "\",\"main\":\"" + main + "\",\"definitionZip\":\"" + OmicsIntegrationTest.zip(main,
                        definition) + "\",\"requestId\":\"" + name + "\"}").post("/workflow").then().statusCode(201)
                .extract().path("id");
        await().atMost(Duration.ofSeconds(20)).until(() -> "ACTIVE".equals(OmicsIntegrationTest.omics()
                .get("/workflow/" + workflow).then().extract().path("status")));
        String role = OmicsIntegrationTest.role("omics-engine-" + name);
        given().header("Authorization", OmicsIntegrationTest.auth("000000000000", "s3")).put("/omics-runs");
        String run = OmicsIntegrationTest.omics().body("{\"workflowId\":\"" + workflow + "\",\"roleArn\":\"" + role
                        + "\",\"outputUri\":\"s3://omics-runs/" + name + "/\",\"requestId\":\"" + name + "-run\","
                        + "\"parameters\":" + parameters + "}").post("/run").then().statusCode(201).extract()
                .path("id");
        await().atMost(Duration.ofMinutes(8)).pollInterval(Duration.ofSeconds(3)).until(() -> {
            String status = OmicsIntegrationTest.omics().get("/run/" + run).then().extract().path("status");
            return "COMPLETED".equals(status) || "FAILED".equals(status);
        });
        OmicsIntegrationTest.omics().get("/run/" + run).then().statusCode(200).body("status", equalTo("COMPLETED"))
                .body("startTime", containsString("T")).body("stopTime", containsString("T"));
        return run;
    }

    @Test
    void wdlRunsOnCromwell() throws Exception {
        OmicsIntegrationTest.put("omics-inputs", "notes.txt", "notes from s3".getBytes(StandardCharsets.UTF_8));
        String run = run("wdl", "WDL", "main.wdl", WDL, "{\"who\":\"floci\",\"notes\":\"s3://omics-inputs/notes.txt\"}");
        String outputs = s3("/omics-runs/wdl/" + run + "/logs/outputs.json");
        assertTrue(outputs.contains("\"Greet.greeting\":\"hello floci\""), outputs);
        assertTrue(outputs.contains("s3://omics-runs/wdl/" + run + "/out/report.txt"), outputs);
        assertEquals("notes from s3", s3("/omics-runs/wdl/" + run + "/out/report.txt"));
        String task = OmicsIntegrationTest.omics().get("/run/" + run + "/task").then().statusCode(200)
                .body("items", hasSize(1)).body("items[0].name", equalTo("Greet.Hello"))
                .body("items[0].status", equalTo("COMPLETED")).body("items[0].cpus", equalTo(2))
                .extract().path("items[0].taskId");
        OmicsIntegrationTest.omics().get("/run/" + run + "/task/" + task).then().statusCode(200)
                .body("imageDetails.image", equalTo("ubuntu:22.04"));
        String events = given().header("Authorization", OmicsIntegrationTest.auth("000000000000", "logs"))
                .header("X-Amz-Target", "Logs_20140328.GetLogEvents").contentType("application/x-amz-json-1.1")
                .body("{\"logGroupName\":\"/aws/omics/WorkflowLog\",\"logStreamName\":\"run/" + run + "/engine\","
                        + "\"startFromHead\":true}").post("/").then().statusCode(200).extract().asString();
        assertTrue(events.contains("Succeeded"), events);
    }

    @Test
    void nextflowRunsPublishFiles() throws Exception {
        String run = run("nf", "NEXTFLOW", "main.nf", NEXTFLOW, "{\"who\":\"nextflow\"}");
        assertEquals("hello nextflow\n", s3("/omics-runs/nf/" + run + "/out/hello.txt"));
        OmicsIntegrationTest.omics().get("/run/" + run + "/task").then().statusCode(200)
                .body("items[0].name", containsString("HELLO")).body("items[0].status", equalTo("COMPLETED"));
    }

    @Test
    void cwlRunsWriteOutputs() throws Exception {
        String run = run("cwl", "CWL", "main.cwl", CWL, "{\"who\":\"cwl\"}");
        assertEquals("cwl\n", s3("/omics-runs/cwl/" + run + "/out/hello.txt"));
        assertTrue(s3("/omics-runs/cwl/" + run + "/logs/outputs.json").contains("hello.txt"));
        OmicsIntegrationTest.omics().get("/run/" + run + "/task").then().statusCode(200)
                .body("items[0].status", equalTo("COMPLETED"));
    }
}
