package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.floci.aws.services.iotsitewise.SiteWiseIntegrationTest.await;
import static io.floci.aws.services.iotsitewise.SiteWiseIntegrationTest.awaitAsset;
import static io.floci.aws.services.iotsitewise.SiteWiseIntegrationTest.awaitModel;
import static io.floci.aws.services.iotsitewise.SiteWiseIntegrationTest.json;
import static io.floci.aws.services.iotsitewise.SiteWiseIntegrationTest.propertyId;
import static io.floci.aws.services.iotsitewise.SiteWiseIntegrationTest.sw;
import static io.floci.aws.services.iotsitewise.SiteWiseIntegrationTest.value;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ExecuteQuery run on the floci-duck sidecar over the Region's assets, properties and time series. */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@Tag("docker")
class SiteWiseQueryDockerIntegrationTest {

    @Inject
    FlociDuckClient duck;

    @Inject
    IamService iam;

    @Inject
    S3Service s3;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static List<List<String>> rows(JsonNode result) {
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode row : result.get("rows")) {
            List<String> cells = new ArrayList<>();
            row.get("data").forEach(d -> cells.add(d.has("nullValue") ? null : d.get("scalarValue").asText()));
            rows.add(cells);
        }
        return rows;
    }

    static JsonNode query(String statement) {
        return json(sw().body("{\"queryStatement\":\"" + statement.replace("\"", "\\\"") + "\"}")
                .post("/queries/execution").then().statusCode(200));
    }

    @Test
    void queriesJoinViewsAndRewriteFunctions() {
        // Start the sidecar before the first query, which would otherwise wait for it past the client's timeout.
        duck.query("SELECT 1", null);
        String modelId = sw().body("{\"assetModelName\":\"query-tank\",\"assetModelProperties\":["
                + "{\"name\":\"Capacity\",\"dataType\":\"INTEGER\",\"type\":{\"attribute\":{\"defaultValue\":\"500\"}}},"
                + "{\"name\":\"Level\",\"dataType\":\"DOUBLE\",\"type\":{\"measurement\":{}}}]}").post("/asset-models")
                .then().statusCode(202).extract().path("assetModelId");
        awaitModel(modelId);
        String level = propertyId(json(sw().get("/asset-models/" + modelId).then()), "Level");
        String tank = sw().body("{\"assetName\":\"tank-q\",\"assetModelId\":\"" + modelId
                + "\",\"assetDescription\":\"the query tank\"}").post("/assets").then().statusCode(202).extract()
                .path("assetId");
        awaitAsset(tank);
        long now = Instant.now().getEpochSecond();
        sw().body("{\"entries\":[" + value("a", tank, level, now - 20, 1.5) + "," + value("b", tank, level, now - 10,
                2.5) + "]}").post("/properties").then().statusCode(200);

        JsonNode one = query("SELECT asset_id, asset_name FROM asset WHERE asset_id = '" + tank + "'");
        assertEquals("asset_id", one.at("/columns/0/name").asText());
        assertEquals("STRING", one.at("/columns/0/type/scalarType").asText());
        assertEquals(List.of(List.of(tank, "tank-q")), rows(one));

        JsonNode joined = query("SELECT a.asset_name, p.property_name, r.double_value FROM asset a, asset_property p,"
                + " raw_time_series r WHERE a.asset_name = 'tank-q' AND p.property_name = 'Level'"
                + " ORDER BY r.event_timestamp");
        assertEquals(List.of(List.of("tank-q", "Level", "1.5"), List.of("tank-q", "Level", "2.5")), rows(joined));
        assertEquals("DOUBLE", joined.at("/columns/2/type/scalarType").asText());

        JsonNode attribute = query("SELECT p.int_attribute_value FROM asset_property p WHERE p.asset_id = '" + tank
                + "' AND p.property_type = 'attribute'");
        assertEquals("INT", attribute.at("/columns/0/type/scalarType").asText());
        assertEquals(List.of(List.of("500")), rows(attribute));

        JsonNode aggregated = query("SELECT COUNT(*), MAX(r.double_value), STDDEV(r.double_value) FROM raw_time_series r"
                + " WHERE r.asset_id = '" + tank + "' AND r.property_id = '" + level + "'");
        assertEquals("2", rows(aggregated).get(0).get(0));
        assertEquals("2.5", rows(aggregated).get(0).get(1));

        JsonNode functions = query("SELECT STR_REPLACE(a.asset_name, 'tank', 'vat'), SUBSTR(a.asset_description, 5),"
                + " date_add(DAY, 1, TIMESTAMP '2025-01-01 00:00:00'), a.asset_name || '!' FROM asset a"
                + " WHERE a.asset_id = '" + tank + "'");
        List<String> row = rows(functions).get(0);
        assertEquals("vat-q", row.get(0));
        assertEquals("query tank", row.get(1));
        assertEquals("TIMESTAMP", functions.at("/columns/2/type/scalarType").asText());
        assertEquals("tank-q!", row.get(3));

        JsonNode latest = query("SELECT l.double_value FROM latest_value_time_series l WHERE l.asset_id = '" + tank
                + "' AND l.property_id = '" + level + "'");
        assertEquals(List.of(List.of("2.5")), rows(latest));

        JsonNode aggregates = query("SELECT resolution, count_value FROM precomputed_aggregates WHERE asset_id = '"
                + tank + "' AND property_id = '" + level + "' AND resolution = '1d'");
        assertEquals(List.of(List.of("1d", "2")), rows(aggregates));

        JsonNode paged = json(sw().body("{\"queryStatement\":\"SELECT r.double_value FROM raw_time_series r WHERE"
                + " r.asset_id = '" + tank + "' ORDER BY r.event_timestamp\",\"maxResults\":1}")
                .post("/queries/execution").then().statusCode(200));
        assertEquals(1, paged.get("rows").size());
        sw().body("{\"queryStatement\":\"SELECT r.double_value FROM raw_time_series r WHERE r.asset_id = '" + tank
                + "' ORDER BY r.event_timestamp\",\"maxResults\":1,\"nextToken\":\"" + paged.get("nextToken").asText()
                + "\"}").post("/queries/execution").then().statusCode(200)
                .body("rows[0].data[0].scalarValue", equalTo("2.5"));

        sw().body("{\"queryStatement\":\"SELECT nope FROM asset\"}").post("/queries/execution").then()
                .statusCode(400).header("x-amzn-ErrorType", "ValidationException")
                .body("message", containsString("nope"));
    }

    @Test
    void workspacePipelinesRunTheirTasksAsContainers() {
        String role = iam.createRole("sitewise-pipeline", "/", "{\"Version\":\"2012-10-17\",\"Statement\":[]}", null,
                3600, Map.of()).getArn();
        sw().body("{\"workspaceName\":\"jobs\",\"encryptionConfiguration\":{\"encryptionType\":"
                + "\"SITEWISE_DEFAULT_ENCRYPTION\"}}").post("/workspaces").then().statusCode(200);
        sw().body("{\"taskName\":\"check\",\"taskConfiguration\":{\"containerTaskConfiguration\":{\"ecrUri\":"
                + "\"public.ecr.aws/docker/library/python:3-alpine\",\"taskExecutionRole\":\"" + role + "\","
                + "\"processingType\":\"GENERIC_COMPUTE_PROCESSING\",\"processingUnit\":\"UNITS_2\",\"command\":"
                + "[\"python3\",\"-c\",\"import os,sys; sys.exit(0 if os.environ.get('MODE') == 'ok' else 3)\"]}}}")
                .post("/workspaces/jobs/tasks").then().statusCode(200);
        sw().body("{\"pipelineName\":\"chain\",\"environmentVariables\":{\"MODE\":\"ok\"},\"computations\":["
                + "{\"computeNodeName\":\"first\",\"taskName\":\"check\"},{\"computeNodeName\":\"second\","
                + "\"taskName\":\"check\",\"dependsOn\":[\"first\"]}]}").post("/workspaces/jobs/pipelines").then()
                .statusCode(200);
        String ok = sw().body("{}").post("/workspaces/jobs/pipelines/chain/executions").then().statusCode(202)
                .extract().path("pipelineExecutionId");
        String bad = sw().body("{\"executionEnvironmentVariableOverrides\":{\"computeNodes\":{\"first\":"
                + "{\"MODE\":\"broken\"}}}}").post("/workspaces/jobs/pipelines/chain/executions").then()
                .statusCode(202).extract().path("pipelineExecutionId");
        for (String id : List.of(ok, bad)) {
            for (int i = 0; i < 240; i++) {
                String state = sw().get("/workspaces/jobs/pipelines/chain/executions/" + id).then().statusCode(200)
                        .extract().path("status.state");
                if ("SUCCEEDED".equals(state) || "FAILED".equals(state)) {
                    break;
                }
                SiteWiseIntegrationTest.sleep(500);
            }
        }
        sw().get("/workspaces/jobs/pipelines/chain/executions/" + ok).then().statusCode(200)
                .body("status.state", equalTo("SUCCEEDED"))
                .body("computeNodeExecutionDetails.status.state", equalTo(List.of("SUCCEEDED", "SUCCEEDED")));
        sw().get("/workspaces/jobs/pipelines/chain/executions/" + bad).then().statusCode(200)
                .body("status.state", equalTo("FAILED"))
                .body("computeNodeExecutionDetails[0].status.stateDetails.message", containsString("code 3"))
                .body("computeNodeExecutionDetails[1].status.state", equalTo("FAILED"));
    }

    @Test
    void workspaceQueriesTheAssistantAndParquetImports() {
        duck.query("SELECT 1", null);
        sw().body("{\"workspaceName\":\"analytics\",\"encryptionConfiguration\":{\"encryptionType\":"
                + "\"SITEWISE_DEFAULT_ENCRYPTION\"}}").post("/workspaces").then().statusCode(200);
        String queryId = sw().body("{\"queryStatement\":\"SELECT 1 + 1 AS two\"}")
                .post("/workspaces/analytics/queries").then().statusCode(202).extract().path("queryId");
        await(() -> sw().get("/workspaces/analytics/queries/" + queryId).then().statusCode(200).extract()
                .path("status"), "COMPLETED");
        sw().get("/workspaces/analytics/queries/" + queryId + "/results").then().statusCode(200)
                .body("columnInfo[0].name", equalTo("two")).body("rows[0][0]", equalTo("2"));

        byte[] reply = sw().body("{\"message\":\"Which assets are there?\",\"enableTrace\":true}")
                .post("/assistant/invocation").then().statusCode(200)
                .header("x-amz-iotsitewise-assistant-conversation-id", notNullValue()).extract().asByteArray();
        String text = new String(reply, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("output") && text.contains("message"), text);

        s3.createBucket("sitewise-parquet", "us-east-1");
        String role = iam.createRole("sitewise-parquet", "/", "{\"Version\":\"2012-10-17\",\"Statement\":[]}", null,
                3600, Map.of()).getArn();
        long now = Instant.now().getEpochSecond();
        duck.execute("COPY (SELECT '/pq/flow' AS ALIAS, CAST(" + (now - 30) + " AS BIGINT) AS TIMESTAMP_SECONDS,"
                + " 'DOUBLE' AS DATA_TYPE, '6.5' AS VALUE) TO 's3://sitewise-parquet/flow.parquet' (FORMAT PARQUET)",
                null, null, "000000000000");
        String jobId = sw().body("{\"jobName\":\"pq\",\"jobRoleArn\":\"" + role + "\",\"files\":[{\"bucket\":"
                + "\"sitewise-parquet\",\"key\":\"flow.parquet\"}],\"errorReportLocation\":{\"bucket\":"
                + "\"sitewise-parquet\",\"prefix\":\"errors/\"},\"jobConfiguration\":{\"fileFormat\":"
                + "{\"parquet\":{}}}}").post("/jobs").then().statusCode(202).extract().path("jobId");
        await(() -> sw().get("/jobs/" + jobId).then().statusCode(200).extract().path("jobStatus"), "COMPLETED");
        sw().get("/properties/latest?propertyAlias=/pq/flow").then().statusCode(200)
                .body("propertyValue.value.doubleValue", equalTo(6.5f));
    }
}
