package io.floci.aws.services.neptunegraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graphs on their Neo4j engine: openCypher through ExecuteQuery in Neptune Analytics' result format, query errors,
 * the graph summary, data imported by an import task, exports (CSV and Parquet), snapshots restored into new graphs,
 * resets, and data kept across a stop and start.
 */
@QuarkusTest
@TestProfile(NeptuneGraphEngineProfile.class)
@Tag("docker")
class NeptuneGraphDockerIntegrationTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static JsonNode query(String graph, String query, String parameters) {
        String body = "{\"query\":" + MAPPER.valueToTree(query) + ",\"language\":\"OPEN_CYPHER\""
                + (parameters == null ? "" : ",\"parameters\":" + parameters) + "}";
        String result = NeptuneGraphIntegrationTest.ng().header("graphIdentifier", graph).body(body).post("/queries")
                .then().statusCode(200).extract().asString();
        try {
            return MAPPER.readTree(result);
        } catch (IOException e) {
            throw new AssertionError(result, e);
        }
    }

    @Test
    void openCypherQueriesSummariesSnapshotsAndRestarts() {
        String graph = NeptuneGraphIntegrationTest.graph("cypher-engine");
        query(graph, "CREATE (a:Person {name: $name, born: 1815})-[:KNOWS {since: 1833}]->(b:Person {name: 'Charles'})",
                "{\"name\":\"Ada\"}");
        JsonNode names = query(graph, "MATCH (n:Person) RETURN n.name AS name ORDER BY name", null);
        assertEquals("[{\"name\":\"Ada\"},{\"name\":\"Charles\"}]", names.path("results").toString());
        JsonNode node = query(graph, "MATCH (n {name: 'Ada'}) RETURN n", null).path("results").path(0).path("n");
        assertEquals("node", node.path("~entityType").asText(), node.toString());
        assertEquals("Person", node.path("~labels").path(0).asText());
        assertEquals(1815, node.path("~properties").path("born").asInt());
        JsonNode edge = query(graph, "MATCH ()-[r]->() RETURN r", null).path("results").path(0).path("r");
        assertEquals("KNOWS", edge.path("~type").asText(), edge.toString());
        assertEquals(node.path("~id").asText(), edge.path("~start").asText());
        NeptuneGraphIntegrationTest.ng().header("graphIdentifier", graph)
                .body("{\"query\":\"MATCH (n RETURN n\",\"language\":\"OPEN_CYPHER\"}").post("/queries").then()
                .statusCode(400).body("reason", equalTo("MALFORMED_QUERY"));
        String plan = NeptuneGraphIntegrationTest.ng().header("graphIdentifier", graph)
                .body("{\"query\":\"MATCH (n) RETURN n\",\"language\":\"OPEN_CYPHER\",\"explainMode\":\"STATIC\"}")
                .post("/queries").then().statusCode(200).extract().asString();
        assertTrue(plan.contains("operatorType") || plan.contains("Produce"), plan);
        NeptuneGraphIntegrationTest.ng().header("graphIdentifier", graph).queryParam("mode", "DETAILED")
                .get("/summary").then().statusCode(200).body("graphSummary.numNodes", equalTo(2))
                .body("graphSummary.numEdges", equalTo(1)).body("graphSummary.nodeLabels", hasItem("Person"))
                .body("graphSummary.edgeLabels", hasItem("KNOWS"))
                .body("graphSummary.nodeStructures[0].nodeProperties", hasItem("name"));

        String snapshot = NeptuneGraphIntegrationTest.ng().body("{\"graphIdentifier\":\"" + graph + "\","
                + "\"snapshotName\":\"engine-snap\"}").post("/snapshots").then().statusCode(201).extract().path("id");
        await().atMost(Duration.ofSeconds(60)).until(() -> "AVAILABLE".equals(NeptuneGraphIntegrationTest.ng()
                .get("/snapshots/" + snapshot).then().extract().path("status")));
        NeptuneGraphIntegrationTest.awaitStatus(graph, "AVAILABLE");
        String restored = NeptuneGraphIntegrationTest.ng().body("{\"graphName\":\"engine-restored\","
                        + "\"deletionProtection\":false}").post("/snapshots/" + snapshot + "/restore").then()
                .statusCode(201).extract().path("id");
        NeptuneGraphIntegrationTest.awaitStatus(restored, "AVAILABLE");
        assertEquals(names.path("results"), query(restored, "MATCH (n:Person) RETURN n.name AS name ORDER BY name",
                null).path("results"));
        assertEquals(1, query(restored, "MATCH ()-[r:KNOWS {since: 1833}]->() RETURN count(r) AS c", null)
                .path("results").path(0).path("c").asInt());

        NeptuneGraphIntegrationTest.ng().post("/graphs/" + graph + "/stop").then().statusCode(200);
        NeptuneGraphIntegrationTest.awaitStatus(graph, "STOPPED");
        NeptuneGraphIntegrationTest.ng().post("/graphs/" + graph + "/start").then().statusCode(200);
        NeptuneGraphIntegrationTest.awaitStatus(graph, "AVAILABLE");
        assertEquals(2, query(graph, "MATCH (n) RETURN count(n) AS c", null).path("results").path(0).path("c")
                .asInt());
        NeptuneGraphIntegrationTest.ng().body("{\"skipSnapshot\":true}").put("/graphs/" + graph).then()
                .statusCode(200);
        NeptuneGraphIntegrationTest.awaitStatus(graph, "AVAILABLE");
        assertEquals(0, query(graph, "MATCH (n) RETURN count(n) AS c", null).path("results").path(0).path("c")
                .asInt());
        NeptuneGraphIntegrationTest.delete(graph);
        NeptuneGraphIntegrationTest.delete(restored);
    }

    @Test
    void importsAndExports() {
        NeptuneGraphIntegrationTest.bucket("ng-engine");
        String role = NeptuneGraphIntegrationTest.role("ng-engine", "neptune-graph.amazonaws.com");
        NeptuneGraphIntegrationTest.put("ng-engine", "in/nodes.csv", ":ID,:LABEL,name:String,age:Long\n"
                + "a,Person,Ada,36\nb,Person;Engineer,Grace,85\n");
        NeptuneGraphIntegrationTest.put("ng-engine", "in/edges.csv", ":START_ID,:END_ID,:TYPE,since:Long\n"
                + "a,b,KNOWS,1840\n");
        String task = NeptuneGraphIntegrationTest.ng().body("{\"graphName\":\"engine-import\",\"source\":"
                        + "\"s3://ng-engine/in/\",\"format\":\"OPEN_CYPHER\",\"roleArn\":\"" + role + "\","
                        + "\"deletionProtection\":false,\"replicaCount\":0}").post("/importtasks").then()
                .statusCode(201).extract().path("taskId");
        assertEquals("SUCCEEDED", NeptuneGraphIntegrationTest.awaitTask("importtasks", task));
        String graph = NeptuneGraphIntegrationTest.ng().get("/importtasks/" + task).then().extract().path("graphId");
        NeptuneGraphIntegrationTest.awaitStatus(graph, "AVAILABLE");
        JsonNode known = query(graph, "MATCH (a)-[k:KNOWS]->(b:Engineer) RETURN a.name AS a, b.name AS b, "
                + "a.`~id` AS id, k.since AS since", null).path("results").path(0);
        assertEquals("Ada", known.path("a").asText(), known.toString());
        assertEquals("Grace", known.path("b").asText());
        assertEquals("a", known.path("id").asText());
        assertEquals(1840, known.path("since").asInt());
        NeptuneGraphIntegrationTest.ng().body("{\"source\":\"s3://ng-engine/in/\",\"format\":\"OPEN_CYPHER\","
                + "\"roleArn\":\"" + role + "\"}").post("/graphs/" + graph + "/importtasks").then().statusCode(400);

        String kms = "arn:aws:kms:us-east-1:000000000000:key/11111111-2222-3333-4444-555555555555";
        String csv = NeptuneGraphIntegrationTest.ng().body("{\"graphIdentifier\":\"" + graph + "\",\"roleArn\":\""
                + role + "\",\"format\":\"CSV\",\"destination\":\"s3://ng-engine/out\",\"kmsKeyIdentifier\":\"" + kms
                + "\",\"exportFilter\":{\"vertexFilter\":{\"Person\":{\"properties\":{\"fullName\":{"
                + "\"sourcePropertyName\":\"name\"}}}}}}").post("/exporttasks").then().statusCode(201).extract()
                .path("taskId");
        assertEquals("SUCCEEDED", NeptuneGraphIntegrationTest.awaitTask("exporttasks", csv));
        NeptuneGraphIntegrationTest.ng().get("/exporttasks/" + csv).then()
                .body("exportTaskDetails.numVerticesWritten", equalTo(2))
                .body("exportTaskDetails.numEdgesWritten", equalTo(1));
        String nodes = NeptuneGraphIntegrationTest.get("ng-engine", "out/" + csv + "/nodes/nodes.csv");
        assertTrue(nodes.startsWith(":ID,:LABEL,fullName:String\n") && nodes.contains("a,Person,Ada"), nodes);
        assertTrue(NeptuneGraphIntegrationTest.get("ng-engine", "out/" + csv + "/edges/edges.csv").contains(
                ",a,b,KNOWS"));

        String parquet = NeptuneGraphIntegrationTest.ng().body("{\"graphIdentifier\":\"" + graph + "\",\"roleArn\":\""
                + role + "\",\"format\":\"PARQUET\",\"destination\":\"s3://ng-engine/parquet/\",\"kmsKeyIdentifier\":"
                + "\"" + kms + "\"}").post("/exporttasks").then().statusCode(201).extract().path("taskId");
        assertEquals("SUCCEEDED", NeptuneGraphIntegrationTest.awaitTask("exporttasks", parquet));
        NeptuneGraphIntegrationTest.ng().get("/exporttasks/" + parquet).then().body("parquetType",
                equalTo("COLUMNAR"));
        byte[] file = RestAssured.given().header("Authorization", NeptuneGraphIntegrationTest.auth(
                "s3")).get("/ng-engine/parquet/" + parquet + "/nodes/nodes.parquet").then().statusCode(200).extract()
                .asByteArray();
        assertEquals("PAR1", new String(file, 0, 4), "a Parquet file");
        NeptuneGraphIntegrationTest.ng().get("/exporttasks/" + parquet).then().body("destination",
                containsString("parquet"));
        NeptuneGraphIntegrationTest.delete(graph);
    }
}
