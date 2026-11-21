package io.floci.aws.services.neptunegraph;

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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Neptune Analytics without a query engine ({@code query-engine: none}): graphs through their states, snapshots and
 * restores, private graph endpoints, tags, import tasks that read Gremlin CSV, openCypher CSV and N-Triples from S3,
 * export tasks, and the errors each makes. Queries run in {@link NeptuneGraphDockerIntegrationTest}.
 */
@QuarkusTest
class NeptuneGraphIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification ng() {
        return given().header("Authorization", auth("neptune-graph")).contentType("application/json");
    }

    static void bucket(String name) {
        given().header("Authorization", auth("s3")).put("/" + name).then().statusCode(200);
    }

    static void put(String bucket, String key, String body) {
        given().header("Authorization", auth("s3")).contentType("text/csv").body(body).put("/" + bucket + "/" + key)
                .then().statusCode(200);
    }

    static String get(String bucket, String key) {
        return given().header("Authorization", auth("s3")).get("/" + bucket + "/" + key).then().statusCode(200)
                .extract().asString();
    }

    static String role(String name, String service) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"" + service + "\"},\"Action\":\"sts:AssumeRole\"}]}";
        return given().header("Authorization", auth("iam")).formParam("Action", "CreateRole")
                .formParam("RoleName", name).formParam("AssumeRolePolicyDocument", trust).post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    static String status(String graph) {
        return ng().get("/graphs/" + graph).then().statusCode(200).extract().path("status");
    }

    static void awaitStatus(String graph, String expected) {
        await().atMost(Duration.ofSeconds(180)).pollInterval(Duration.ofMillis(250))
                .until(() -> expected.equals(status(graph)));
    }

    static String graph(String name) {
        String id = ng().body("{\"graphName\":\"" + name + "\",\"provisionedMemory\":16,\"publicConnectivity\":true,"
                        + "\"replicaCount\":0,\"deletionProtection\":false,\"tags\":{\"team\":\"graph\"}}")
                .post("/graphs").then().statusCode(201).body("status", equalTo("CREATING")).extract().path("id");
        awaitStatus(id, "AVAILABLE");
        return id;
    }

    static String awaitTask(String kind, String task) {
        await().atMost(Duration.ofSeconds(180)).pollInterval(Duration.ofMillis(250)).until(() -> List.of("SUCCEEDED",
                "FAILED", "CANCELLED").contains(ng().get("/" + kind + "/" + task).then().extract().path("status")));
        return ng().get("/" + kind + "/" + task).then().extract().path("status");
    }

    static void delete(String graph) {
        ng().queryParam("skipSnapshot", true).delete("/graphs/" + graph).then().statusCode(200);
        await().atMost(Duration.ofSeconds(60)).until(() -> ng().get("/graphs/" + graph).statusCode() == 404);
    }

    @Test
    void graphsSnapshotsEndpointsAndTags() {
        ng().get("/graphs/g-0123456789").then().statusCode(404)
                .header("x-amzn-ErrorType", containsString("ResourceNotFoundException"));
        ng().get("/snapshots/gs-0123456789").then().statusCode(404);
        ng().get("/importtasks/t-0123456789").then().statusCode(404);
        ng().get("/exporttasks/t-0123456789").then().statusCode(404);
        ng().body("{\"graphName\":\"Bad_Name\",\"provisionedMemory\":16}").post("/graphs").then().statusCode(400);
        ng().body("{\"graphName\":\"badmemory\",\"provisionedMemory\":17}").post("/graphs").then().statusCode(400);

        String id = graph("people");
        ng().body("{\"graphName\":\"people\",\"provisionedMemory\":16}").post("/graphs").then().statusCode(409);
        ng().get("/graphs/" + id).then().body("name", equalTo("people")).body("arn", containsString(":graph/" + id))
                .body("endpoint", containsString(id + ".us-east-1.neptune-graph.")).body("replicaCount", equalTo(0))
                .body("publicConnectivity", equalTo(true)).body("deletionProtection", equalTo(false));
        ng().get("/graphs/people").then().statusCode(400);
        ng().get("/graphs").then().body("graphs.id", hasItem(id));
        String arn = ng().get("/graphs/" + id).then().extract().path("arn");
        ng().get("/tags/" + arn).then().body("tags.team", equalTo("graph"));
        ng().body("{\"tags\":{\"stage\":\"test\"}}").post("/tags/" + arn).then().statusCode(200);
        ng().queryParam("tagKeys", "team").delete("/tags/" + arn).then().statusCode(200);
        ng().get("/tags/" + arn).then().body("tags.stage", equalTo("test")).body("tags.team", equalTo(null));

        ng().body("{\"deletionProtection\":true,\"provisionedMemory\":32}").patch("/graphs/" + id).then()
                .statusCode(200).body("status", equalTo("UPDATING"));
        awaitStatus(id, "AVAILABLE");
        ng().get("/graphs/" + id).then().body("provisionedMemory", equalTo(32));
        ng().queryParam("skipSnapshot", true).delete("/graphs/" + id).then().statusCode(400);
        ng().body("{\"deletionProtection\":false}").patch("/graphs/" + id).then().statusCode(200);
        awaitStatus(id, "AVAILABLE");

        ng().post("/graphs/" + id + "/stop").then().statusCode(200).body("status", equalTo("STOPPING"));
        awaitStatus(id, "STOPPED");
        ng().post("/graphs/" + id + "/stop").then().statusCode(409);
        ng().post("/graphs/" + id + "/start").then().statusCode(200).body("status", equalTo("STARTING"));
        awaitStatus(id, "AVAILABLE");

        String snapshot = ng().body("{\"graphIdentifier\":\"" + id + "\",\"snapshotName\":\"people-1\"}")
                .post("/snapshots").then().statusCode(201).body("status", equalTo("CREATING"))
                .body("sourceGraphId", equalTo(id)).extract().path("id");
        assertTrue(snapshot.startsWith("gs-"));
        await().atMost(Duration.ofSeconds(30)).until(() -> "AVAILABLE".equals(ng().get("/snapshots/" + snapshot)
                .then().extract().path("status")));
        awaitStatus(id, "AVAILABLE");
        ng().queryParam("graphIdentifier", id).get("/snapshots").then().body("graphSnapshots.id", hasItem(snapshot));
        String restored = ng().body("{\"graphName\":\"people-restored\",\"deletionProtection\":false}")
                .post("/snapshots/" + snapshot + "/restore").then().statusCode(201)
                .body("sourceSnapshotId", equalTo(snapshot)).extract().path("id");
        awaitStatus(restored, "AVAILABLE");

        ng().body("{\"vpcId\":\"vpc-1234\",\"subnetIds\":[\"subnet-1\"]}").post("/graphs/" + id + "/endpoints/")
                .then().statusCode(201).body("status", equalTo("CREATING")).body("vpcEndpointId", startsWith("vpce-"));
        ng().body("{\"vpcId\":\"vpc-1234\"}").post("/graphs/" + id + "/endpoints/").then().statusCode(409);
        await().atMost(Duration.ofSeconds(10)).until(() -> "AVAILABLE".equals(ng().get("/graphs/" + id
                + "/endpoints/vpc-1234").then().extract().path("status")));
        ng().get("/graphs/" + id + "/endpoints/").then().body("privateGraphEndpoints", hasSize(1));
        ng().delete("/graphs/" + id + "/endpoints/vpc-1234").then().statusCode(200);

        ng().body("{\"skipSnapshot\":false}").put("/graphs/" + id).then().statusCode(200)
                .body("status", equalTo("RESETTING"));
        awaitStatus(id, "AVAILABLE");
        ng().queryParam("graphIdentifier", id).get("/snapshots").then().body("graphSnapshots", hasSize(2));

        ng().header("graphIdentifier", id).body("{\"query\":\"RETURN 1\",\"language\":\"OPEN_CYPHER\"}")
                .post("/queries").then().statusCode(400).body("reason", equalTo("UNSUPPORTED_OPERATION"));
        ng().header("graphIdentifier", "g-0123456789").body("{\"query\":\"RETURN 1\",\"language\":\"OPEN_CYPHER\"}")
                .post("/queries").then().statusCode(403);
        ng().header("graphIdentifier", id).get("/summary").then().statusCode(200)
                .body("graphSummary.numNodes", equalTo(0));
        ng().header("graphIdentifier", id).queryParam("maxResults", 10).get("/queries").then().statusCode(200)
                .body("queries", hasSize(0));

        ng().delete("/snapshots/" + snapshot).then().statusCode(200).body("status", equalTo("DELETING"));
        await().atMost(Duration.ofSeconds(10)).until(() -> ng().get("/snapshots/" + snapshot).statusCode() == 404);
        ng().queryParam("skipSnapshot", false).delete("/graphs/" + id).then().statusCode(200)
                .body("status", equalTo("DELETING"));
        await().atMost(Duration.ofSeconds(30)).until(() -> ng().get("/graphs/" + id).statusCode() == 404);
        ng().get("/snapshots").then().body("graphSnapshots.name", hasItem(startsWith("people-final-snapshot-")));
        delete(restored);
    }

    @Test
    void importAndExportTasks() {
        bucket("ng-data");
        String role = role("ng-import", "neptune-graph.amazonaws.com");
        put("ng-data", "gremlin/nodes.csv", "~id,~label,name:String,age:Int,tags:String[]\n"
                + "p1,Person,Ada,36,math;code\np2,Person;Engineer,\"Grace, H\",85,\n");
        put("ng-data", "gremlin/edges.csv", "~id,~from,~to,~label,since:Int\ne1,p1,p2,KNOWS,1840\n");
        String task = ng().body("{\"graphName\":\"imported\",\"source\":\"s3://ng-data/gremlin/\",\"format\":\"CSV\","
                        + "\"roleArn\":\"" + role + "\",\"deletionProtection\":false,\"replicaCount\":0}")
                .post("/importtasks").then().statusCode(201).body("status", equalTo("INITIALIZING")).extract()
                .path("taskId");
        assertTrue("SUCCEEDED".equals(awaitTask("importtasks", task)));
        String graph = ng().get("/importtasks/" + task).then().body("importTaskDetails.statementCount", equalTo(3))
                .body("importTaskDetails.dictionaryEntryCount", equalTo(3)).extract().path("graphId");
        awaitStatus(graph, "AVAILABLE");
        ng().queryParam("graphIdentifier", graph).get("/importtasks").then().body("tasks.taskId", hasItem(task));

        put("ng-data", "cypher/nodes.csv", ":ID,:LABEL,name:String\nc1,City,Paris\n");
        put("ng-data", "cypher/edges.csv", ":START_ID,:END_ID,:TYPE\nc1,c1,TWINNED\n");
        String empty = graph("cypher");
        String cypher = ng().body("{\"source\":\"s3://ng-data/cypher/\",\"format\":\"OPEN_CYPHER\",\"roleArn\":\""
                + role + "\"}").post("/graphs/" + empty + "/importtasks").then().statusCode(201).extract()
                .path("taskId");
        assertTrue("SUCCEEDED".equals(awaitTask("importtasks", cypher)));

        put("ng-data", "rdf/data.nt", "<http://ex/a> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://ex/T> ."
                + "\n<http://ex/a> <http://ex/name> \"A\" .\n<http://ex/a> <http://ex/age> "
                + "\"3\"^^<http://www.w3.org/2001/XMLSchema#integer> .\n<http://ex/a> <http://ex/knows> _:b .\n");
        String rdf = ng().body("{\"graphName\":\"rdf\",\"source\":\"s3://ng-data/rdf/\",\"format\":\"NTRIPLES\","
                + "\"roleArn\":\"" + role + "\",\"deletionProtection\":false}").post("/importtasks").then()
                .statusCode(201).extract().path("taskId");
        assertTrue("SUCCEEDED".equals(awaitTask("importtasks", rdf)));

        put("ng-data", "bad/nodes.csv", "~id,~label,age:Int\nx,Thing,not-a-number\n");
        String bad = ng().body("{\"graphName\":\"bad\",\"source\":\"s3://ng-data/bad/\",\"format\":\"CSV\","
                + "\"roleArn\":\"" + role + "\",\"deletionProtection\":false}").post("/importtasks").then()
                .statusCode(201).extract().path("taskId");
        assertTrue("FAILED".equals(awaitTask("importtasks", bad)));
        ng().get("/importtasks/" + bad).then().body("statusReason", containsString("not-a-number"));
        String untrusted = role("ng-untrusted", "lambda.amazonaws.com");
        String denied = ng().body("{\"graphName\":\"denied\",\"source\":\"s3://ng-data/gremlin/\",\"roleArn\":\""
                + untrusted + "\",\"deletionProtection\":false}").post("/importtasks").then().statusCode(201)
                .extract().path("taskId");
        assertTrue("FAILED".equals(awaitTask("importtasks", denied)));
        ng().get("/importtasks/" + denied).then().body("statusReason", containsString("doesn't trust"));

        String export = ng().body("{\"graphIdentifier\":\"" + graph + "\",\"roleArn\":\"" + role + "\",\"format\":"
                        + "\"CSV\",\"destination\":\"s3://ng-data/export\",\"kmsKeyIdentifier\":\"arn:aws:kms:us-east-1:000000000000:key/11111111-2222-3333-4444-555555555555\"}")
                .post("/exporttasks").then().statusCode(201).body("status", equalTo("INITIALIZING")).extract()
                .path("taskId");
        assertTrue("SUCCEEDED".equals(awaitTask("exporttasks", export)));
        assertTrue(get("ng-data", "export/" + export + "/nodes/nodes.csv").startsWith(":ID,:LABEL"));
        assertTrue(get("ng-data", "export/" + export + "/edges/edges.csv").startsWith(":ID,:START_ID,:END_ID,:TYPE"));
        ng().queryParam("graphIdentifier", graph).get("/exporttasks").then().body("tasks.taskId", hasItem(export));
        ng().delete("/exporttasks/" + export).then().statusCode(409);

        for (String done : List.of(graph, empty)) {
            delete(done);
        }
        String rdfGraph = ng().get("/importtasks/" + rdf).then().extract().path("graphId");
        delete(rdfGraph);
        for (String failed : List.of(bad, denied)) {
            String failedGraph = ng().get("/importtasks/" + failed).then().extract().path("graphId");
            ng().get("/graphs/" + failedGraph).then().body("status", equalTo("FAILED"));
            delete(failedGraph);
        }
    }
}
