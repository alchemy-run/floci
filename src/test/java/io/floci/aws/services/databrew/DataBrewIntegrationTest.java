package io.floci.aws.services.databrew;

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
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AWS Glue DataBrew over REST: datasets read from S3, recipe versions, project sessions that preview steps over the
 * sample, recipe and profile job runs that write transformed data and data quality reports back to S3, schedules
 * and tags.
 */
@QuarkusTest
class DataBrewIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/databrew/aws4_request";
    private static final String S3_AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/s3/aws4_request";
    private static final String ROLE = "arn:aws:iam::000000000000:role/databrew";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification brew() {
        return given().header("Authorization", AUTH).contentType("application/json");
    }

    private static ValidatableResponse post(String path, String body) {
        return brew().body(body).post(path).then();
    }

    private static String bucket(String csv) {
        String bucket = "databrew-" + UUID.randomUUID().toString().substring(0, 8);
        given().header("Authorization", S3_AUTH).put("/" + bucket).then().statusCode(200);
        put(bucket, "raw/people.csv", csv);
        return bucket;
    }

    private static void put(String bucket, String key, String body) {
        given().header("Authorization", S3_AUTH).body(body.getBytes(StandardCharsets.UTF_8))
                .put("/" + bucket + "/" + key).then().statusCode(200);
    }

    private static String get(String bucket, String key) {
        return given().header("Authorization", S3_AUTH).get("/" + bucket + "/" + key).then().statusCode(200)
                .extract().asString();
    }

    private static List<String> keys(String bucket, String prefix) {
        return given().header("Authorization", S3_AUTH).queryParam("list-type", "2").queryParam("prefix", prefix)
                .get("/" + bucket).then().statusCode(200).extract().xmlPath().getList("ListBucketResult.Contents.Key");
    }

    private static String dataset(String bucket, String key) {
        String name = "people-" + UUID.randomUUID().toString().substring(0, 6);
        post("/datasets", "{\"Name\":\"" + name + "\",\"Format\":\"CSV\",\"FormatOptions\":{\"Csv\":{\"Delimiter\":"
                + "\",\",\"HeaderRow\":true}},\"Input\":{\"S3InputDefinition\":{\"Bucket\":\"" + bucket + "\",\"Key\":\""
                + key + "\"}}}").statusCode(200).body("Name", equalTo(name));
        return name;
    }

    private static String awaitRun(String job, String runId) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).until(() -> {
            String state = brew().get("/jobs/" + job + "/jobRun/" + runId).then().statusCode(200).extract()
                    .path("State");
            return List.of("SUCCEEDED", "FAILED", "STOPPED", "TIMEOUT").contains(state);
        });
        return brew().get("/jobs/" + job + "/jobRun/" + runId).then().extract().path("State");
    }

    @Test
    void recipeVersionsAndDeletion() {
        String name = "recipe-" + UUID.randomUUID().toString().substring(0, 6);
        post("/recipes", "{\"Name\":\"" + name + "\",\"Steps\":[{\"Action\":{\"Operation\":\"UPPER_CASE\","
                + "\"Parameters\":{\"sourceColumn\":\"name\"}}}],\"Tags\":{\"team\":\"data\"}}").statusCode(200);
        post("/recipes", "{\"Name\":\"" + name + "\",\"Steps\":[]}").statusCode(409);
        brew().get("/recipes/" + name).then().statusCode(200).body("RecipeVersion", equalTo("0.1"));
        post("/recipes/" + name + "/publishRecipe", "{\"Description\":\"first\"}").statusCode(200);
        brew().get("/recipes/" + name).then().statusCode(200).body("RecipeVersion", equalTo("1.0"))
                .body("Description", equalTo("first")).body("PublishedBy", notNullValue());
        brew().body("{\"Steps\":[{\"Action\":{\"Operation\":\"LOWER_CASE\",\"Parameters\":{\"sourceColumn\":"
                + "\"name\"}}}]}").put("/recipes/" + name).then().statusCode(200);
        brew().queryParam("recipeVersion", "LATEST_WORKING").get("/recipes/" + name).then().statusCode(200)
                .body("RecipeVersion", equalTo("1.1")).body("Steps[0].Action.Operation", equalTo("LOWER_CASE"));
        brew().get("/recipes/" + name).then().body("Steps[0].Action.Operation", equalTo("UPPER_CASE"));
        post("/recipes/" + name + "/publishRecipe", "{}").statusCode(200);
        brew().queryParam("name", name).get("/recipeVersions").then().statusCode(200)
                .body("Recipes.RecipeVersion", equalTo(List.of("1.0", "2.0")));
        brew().queryParam("recipeVersion", "LATEST_WORKING").get("/recipes").then().statusCode(200)
                .body("Recipes.find { it.Name == '" + name + "' }.RecipeVersion", equalTo("2.1"));
        brew().delete("/recipes/" + name + "/recipeVersion/LATEST_WORKING").then().statusCode(409);
        post("/recipes/" + name + "/batchDeleteRecipeVersion", "{\"RecipeVersions\":[\"1.0\",\"9.0\","
                + "\"LATEST_WORKING\"]}").statusCode(200).body("Errors.RecipeVersion", equalTo(List.of("9.0",
                "LATEST_WORKING")));
        brew().delete("/recipes/" + name + "/recipeVersion/2.0").then().statusCode(200);
        brew().delete("/recipes/" + name + "/recipeVersion/LATEST_WORKING").then().statusCode(200);
        brew().get("/recipes/" + name).then().statusCode(404).body("__type", containsString("ResourceNotFound"));
    }

    @Test
    void recipeJobTransformsTheDatasetIntoS3() throws Exception {
        String bucket = bucket("id,name,score\n1,alice,10\n2,bob,\n3,carol,7\n");
        String datasetName = dataset(bucket, "raw/people.csv");
        String recipe = "transform-" + UUID.randomUUID().toString().substring(0, 6);
        post("/recipes", "{\"Name\":\"" + recipe + "\",\"Steps\":["
                + "{\"Action\":{\"Operation\":\"UPPER_CASE\",\"Parameters\":{\"sourceColumn\":\"name\"}}},"
                + "{\"Action\":{\"Operation\":\"REMOVE_VALUES\",\"Parameters\":{\"sourceColumn\":\"score\"}},"
                + "\"ConditionExpressions\":[{\"Condition\":\"IS_MISSING\",\"TargetColumn\":\"score\"}]}]}")
                .statusCode(200);
        String job = "recipe-job-" + UUID.randomUUID().toString().substring(0, 6);
        post("/recipeJobs", "{\"Name\":\"" + job + "\",\"DatasetName\":\"" + datasetName + "\",\"RecipeReference\":"
                + "{\"Name\":\"" + recipe + "\"},\"RoleArn\":\"" + ROLE + "\",\"Outputs\":[{\"Location\":"
                + "{\"Bucket\":\"" + bucket + "\",\"Key\":\"curated/\"},\"Format\":\"CSV\"}]}").statusCode(400)
                .body("message", containsString("no published version"));
        post("/recipes/" + recipe + "/publishRecipe", "{}").statusCode(200);
        post("/recipeJobs", "{\"Name\":\"" + job + "\",\"DatasetName\":\"" + datasetName + "\",\"RecipeReference\":"
                + "{\"Name\":\"" + recipe + "\",\"RecipeVersion\":\"LATEST_PUBLISHED\"},\"RoleArn\":\"" + ROLE
                + "\",\"Outputs\":[{\"Location\":{\"Bucket\":\"" + bucket + "\",\"Key\":\"curated/\"},\"Format\":"
                + "\"CSV\",\"Overwrite\":true},{\"Location\":{\"Bucket\":\"" + bucket + "\",\"Key\":\"json\"},"
                + "\"Format\":\"JSON\",\"PartitionColumns\":[\"name\"]}],\"Tags\":{\"env\":\"test\"}}")
                .statusCode(200);
        brew().get("/jobs/" + job).then().statusCode(200).body("Type", equalTo("RECIPE"))
                .body("MaxCapacity", equalTo(5)).body("Timeout", equalTo(2880))
                .body("RecipeReference.RecipeVersion", equalTo("LATEST_PUBLISHED"));

        String runId = brew().post("/jobs/" + job + "/startJobRun").then().statusCode(200).extract().path("RunId");
        brew().get("/jobs/" + job + "/jobRun/" + runId).then().statusCode(200)
                .body("State", oneOf("STARTING", "RUNNING"));
        assertEquals("SUCCEEDED", awaitRun(job, runId));
        brew().get("/jobs/" + job + "/jobRun/" + runId).then()
                .body("LogGroupName", equalTo("/aws-glue-databrew/jobs/" + job))
                .body("CompletedOn", notNullValue());
        brew().get("/jobs/" + job + "/jobRuns").then().statusCode(200).body("JobRuns.RunId", hasItem(runId));

        List<String> csv = keys(bucket, "curated/");
        assertEquals(1, csv.size(), csv.toString());
        assertEquals("id,name,score\n1,ALICE,10\n3,CAROL,7\n", get(bucket, csv.get(0)));
        List<String> json = keys(bucket, "json/");
        assertTrue(json.stream().anyMatch(key -> key.startsWith("json/name=ALICE/")), json.toString());
        JsonNode carol = MAPPER.readTree(get(bucket, json.stream().filter(key -> key.contains("name=CAROL"))
                .findFirst().orElseThrow()).strip());
        assertEquals("7", carol.path("score").asText());
        assertTrue(!carol.has("name"), carol.toString());

        brew().delete("/datasets/" + datasetName).then().statusCode(409);
        brew().delete("/jobs/" + job).then().statusCode(200);
        brew().get("/jobs/" + job).then().statusCode(404);
    }

    @Test
    void profileJobWritesStatisticsAndValidationReports() throws Exception {
        String bucket = bucket("id,name\n1,alice\n2,\n2,\n");
        String datasetName = dataset(bucket, "raw/people.csv");
        String datasetArn = brew().get("/datasets/" + datasetName).then().extract().path("ResourceArn");
        String ruleset = "quality-" + UUID.randomUUID().toString().substring(0, 6);
        post("/rulesets", "{\"Name\":\"" + ruleset + "\",\"TargetArn\":\"arn:aws:databrew:us-east-1:000000000000:"
                + "dataset/missing\",\"Rules\":[{\"Name\":\"r\",\"CheckExpression\":\"AGG(ROW_COUNT) > :val1\","
                + "\"SubstitutionMap\":{\":val1\":\"0\"}}]}").statusCode(400);
        post("/rulesets", "{\"Name\":\"" + ruleset + "\",\"TargetArn\":\"" + datasetArn + "\",\"Rules\":["
                + "{\"Name\":\"no-missing-names\",\"CheckExpression\":\"AGG(MISSING_VALUES_PERCENTAGE) == :val1\","
                + "\"SubstitutionMap\":{\":val1\":\"0\"},\"ColumnSelectors\":[{\"Name\":\"name\"}]},"
                + "{\"Name\":\"rows\",\"CheckExpression\":\"AGG(ROW_COUNT) == :val1\",\"SubstitutionMap\":"
                + "{\":val1\":\"3\"}}]}").statusCode(200);
        String rulesetArn = brew().get("/rulesets/" + ruleset).then().statusCode(200).extract().path("ResourceArn");
        brew().queryParam("targetArn", datasetArn).get("/rulesets").then().statusCode(200)
                .body("Rulesets[0].RuleCount", equalTo(2));

        String job = "profile-" + UUID.randomUUID().toString().substring(0, 6);
        post("/profileJobs", "{\"Name\":\"" + job + "\",\"DatasetName\":\"" + datasetName + "\",\"RoleArn\":\""
                + ROLE + "\",\"OutputLocation\":{\"Bucket\":\"" + bucket + "\",\"Key\":\"profiles\"},"
                + "\"ValidationConfigurations\":[{\"RulesetArn\":\"" + rulesetArn + "\"}],"
                + "\"JobSample\":{\"Mode\":\"FULL_DATASET\"}}").statusCode(200);
        brew().delete("/rulesets/" + ruleset).then().statusCode(409);
        String runId = brew().post("/jobs/" + job + "/startJobRun").then().statusCode(200).extract().path("RunId");
        assertEquals("SUCCEEDED", awaitRun(job, runId));
        JsonNode profile = MAPPER.readTree(get(bucket, "profiles/" + datasetName + "_" + runId + ".json"));
        assertEquals(3, profile.path("rowsCount").asInt());
        assertEquals(1, profile.path("duplicateRowsCount").asInt());
        assertEquals(2, profile.path("columns").get(1).path("missingValuesCount").asInt());
        JsonNode report = MAPPER.readTree(get(bucket, "profiles/" + datasetName + "_" + runId
                + "_dq-validation-report.json"));
        JsonNode rules = report.path("rulesetResults").get(0).path("ruleResults");
        assertEquals("FAILED", rules.get(0).path("status").asText());
        assertEquals("SUCCEEDED", rules.get(1).path("status").asText());
    }

    @Test
    void runsStopAndFailures() {
        String bucket = bucket("id,name\n1,alice\n");
        String datasetName = dataset(bucket, "raw/people.csv");
        String job = "stop-" + UUID.randomUUID().toString().substring(0, 6);
        post("/profileJobs", "{\"Name\":\"" + job + "\",\"DatasetName\":\"" + datasetName + "\",\"RoleArn\":\""
                + ROLE + "\",\"OutputLocation\":{\"Bucket\":\"" + bucket + "\"}}").statusCode(200);
        String runId = brew().post("/jobs/" + job + "/startJobRun").then().statusCode(200).extract().path("RunId");
        brew().post("/jobs/" + job + "/jobRun/" + runId + "/stopJobRun").then().statusCode(200);
        brew().get("/jobs/" + job + "/jobRun/" + runId).then().body("State", equalTo("STOPPING"));
        brew().delete("/jobs/" + job).then().statusCode(409);
        assertEquals("STOPPED", awaitRun(job, runId));
        brew().post("/jobs/" + job + "/jobRun/" + runId + "/stopJobRun").then().statusCode(400);

        // A recipe that names a missing column fails the run with the recipe step in the message.
        String recipe = "broken-" + UUID.randomUUID().toString().substring(0, 6);
        post("/recipes", "{\"Name\":\"" + recipe + "\",\"Steps\":[{\"Action\":{\"Operation\":\"UPPER_CASE\","
                + "\"Parameters\":{\"sourceColumn\":\"nope\"}}}]}").statusCode(200);
        post("/recipes/" + recipe + "/publishRecipe", "{}").statusCode(200);
        String failing = "fail-" + UUID.randomUUID().toString().substring(0, 6);
        post("/recipeJobs", "{\"Name\":\"" + failing + "\",\"DatasetName\":\"" + datasetName + "\","
                + "\"RecipeReference\":{\"Name\":\"" + recipe + "\",\"RecipeVersion\":\"1.0\"},\"RoleArn\":\"" + ROLE
                + "\",\"MaxRetries\":1,\"Outputs\":[{\"Location\":{\"Bucket\":\"" + bucket + "\",\"Key\":\"out/\"}}]}")
                .statusCode(200);
        String failedRun = brew().post("/jobs/" + failing + "/startJobRun").then().statusCode(200).extract()
                .path("RunId");
        assertEquals("FAILED", awaitRun(failing, failedRun));
        brew().get("/jobs/" + failing + "/jobRun/" + failedRun).then()
                .body("ErrorMessage", containsString("Column nope does not exist"))
                .body("Attempt", equalTo(1));

        post("/profileJobs", "{\"Name\":\"" + job + "x\",\"DatasetName\":\"nope\",\"RoleArn\":\"" + ROLE + "\","
                + "\"OutputLocation\":{\"Bucket\":\"" + bucket + "\"}}").statusCode(404);
        String missingSource = dataset(bucket, "raw/absent.csv");
        post("/profileJobs", "{\"Name\":\"" + job + "y\",\"DatasetName\":\"" + missingSource + "\",\"RoleArn\":\""
                + ROLE + "\",\"OutputLocation\":{\"Bucket\":\"" + bucket + "\"}}").statusCode(400)
                .body("message", containsString("cannot be read"));
    }

    @Test
    void projectSessionsPreviewAndEditTheRecipe() throws Exception {
        String bucket = bucket("id,name\n1,alice\n2,bob\n3,carol\n");
        String datasetName = dataset(bucket, "raw/people.csv");
        String recipe = "session-" + UUID.randomUUID().toString().substring(0, 6);
        post("/recipes", "{\"Name\":\"" + recipe + "\",\"Steps\":[]}").statusCode(200);
        String project = "project-" + UUID.randomUUID().toString().substring(0, 6);
        post("/projects", "{\"Name\":\"" + project + "\",\"DatasetName\":\"" + datasetName + "\",\"RecipeName\":\""
                + recipe + "\",\"RoleArn\":\"" + ROLE + "\",\"Sample\":{\"Type\":\"FIRST_N\",\"Size\":2}}")
                .statusCode(200);
        post("/projects", "{\"Name\":\"" + project + "2\",\"DatasetName\":\"" + datasetName + "\",\"RecipeName\":\""
                + recipe + "\",\"RoleArn\":\"" + ROLE + "\"}").statusCode(409);
        brew().get("/recipes/" + recipe).then().body("ProjectName", equalTo(project));

        brew().body("{\"Preview\":true}").put("/projects/" + project + "/sendProjectSessionAction").then()
                .statusCode(409);
        String session = brew().body("{}").put("/projects/" + project + "/startProjectSession").then().statusCode(200)
                .extract().path("ClientSessionId");
        brew().body("{}").put("/projects/" + project + "/startProjectSession").then().statusCode(409);
        brew().get("/projects/" + project).then().body("SessionStatus", equalTo("READY"));

        String result = brew().body("{\"Preview\":true,\"ClientSessionId\":\"" + session + "\",\"RecipeStep\":"
                + "{\"Action\":{\"Operation\":\"UPPER_CASE\",\"Parameters\":{\"sourceColumn\":\"name\"}}}}")
                .put("/projects/" + project + "/sendProjectSessionAction").then().statusCode(200)
                .body("ActionId", equalTo(1)).extract().path("Result");
        JsonNode frame = MAPPER.readTree(result);
        assertEquals("ALICE", frame.path("rows").get(0).get(1).asText());
        assertEquals(2, frame.path("rows").size());
        brew().get("/recipes/" + recipe).then().body("Steps", hasSize(0));

        brew().body("{\"ClientSessionId\":\"" + session + "\",\"RecipeStep\":{\"Action\":{\"Operation\":"
                + "\"LOWER_CASE\",\"Parameters\":{\"sourceColumn\":\"name\"}}}}")
                .put("/projects/" + project + "/sendProjectSessionAction").then().statusCode(200)
                .body("ActionId", equalTo(2));
        brew().queryParam("recipeVersion", "LATEST_WORKING").get("/recipes/" + recipe).then()
                .body("Steps[0].Action.Operation", equalTo("LOWER_CASE"));
        brew().body("{\"ClientSessionId\":\"other\",\"Preview\":true}")
                .put("/projects/" + project + "/sendProjectSessionAction").then().statusCode(409);

        String job = "project-job-" + UUID.randomUUID().toString().substring(0, 6);
        post("/recipeJobs", "{\"Name\":\"" + job + "\",\"ProjectName\":\"" + project + "\",\"RoleArn\":\"" + ROLE
                + "\",\"Outputs\":[{\"Location\":{\"Bucket\":\"" + bucket + "\",\"Key\":\"project/\"},"
                + "\"CompressionFormat\":\"GZIP\"}]}").statusCode(200);
        brew().queryParam("projectName", project).get("/jobs").then().body("Jobs.Name", equalTo(List.of(job)));
        String runId = brew().post("/jobs/" + job + "/startJobRun").then().extract().path("RunId");
        assertEquals("SUCCEEDED", awaitRun(job, runId));
        assertTrue(keys(bucket, "project/").get(0).endsWith(".csv.gz"));

        brew().body("{\"RoleArn\":\"" + ROLE + "\",\"Sample\":{\"Type\":\"RANDOM\",\"Size\":250}}")
                .put("/projects/" + project).then().statusCode(200);
        brew().get("/projects/" + project).then().body("Sample.Type", equalTo("RANDOM"));
        brew().delete("/projects/" + project).then().statusCode(409);
        brew().delete("/jobs/" + job).then().statusCode(200);
        brew().delete("/projects/" + project).then().statusCode(200);
        brew().get("/recipes/" + recipe).then().body("ProjectName", equalTo(null));
    }

    @Test
    void datasetsFromFoldersAndPathParameters() {
        String bucket = bucket("id\n1\n");
        put(bucket, "sales/region=eu/part.csv", "id,amount\n1,5\n");
        put(bucket, "sales/region=us/part.csv", "id,amount\n2,7\n");
        String name = "sales-" + UUID.randomUUID().toString().substring(0, 6);
        post("/datasets", "{\"Name\":\"" + name + "\",\"Input\":{\"S3InputDefinition\":{\"Bucket\":\"" + bucket
                + "\",\"Key\":\"sales/region=<region>/part.csv\"}},\"PathOptions\":{\"Parameters\":{\"region\":"
                + "{\"Name\":\"region\",\"Type\":\"String\",\"CreateColumn\":true,\"Filter\":{\"Expression\":"
                + "\"== :r\",\"ValuesMap\":{\":r\":\"us\"}}}}}}").statusCode(200);
        String recipe = "noop-" + UUID.randomUUID().toString().substring(0, 6);
        post("/recipes", "{\"Name\":\"" + recipe + "\",\"Steps\":[{\"Action\":{\"Operation\":\"RENAME\","
                + "\"Parameters\":{\"sourceColumn\":\"amount\",\"targetColumn\":\"total\"}}}]}").statusCode(200);
        post("/recipes/" + recipe + "/publishRecipe", "{}").statusCode(200);
        String job = "sales-" + UUID.randomUUID().toString().substring(0, 6);
        post("/recipeJobs", "{\"Name\":\"" + job + "\",\"DatasetName\":\"" + name + "\",\"RecipeReference\":{"
                + "\"Name\":\"" + recipe + "\",\"RecipeVersion\":\"1.0\"},\"RoleArn\":\"" + ROLE + "\",\"Outputs\":"
                + "[{\"Location\":{\"Bucket\":\"" + bucket + "\",\"Key\":\"report/\"}}]}").statusCode(200);
        String runId = brew().post("/jobs/" + job + "/startJobRun").then().extract().path("RunId");
        assertEquals("SUCCEEDED", awaitRun(job, runId));
        assertEquals("id,total,region\n2,7,us\n", get(bucket, keys(bucket, "report/").get(0)));
    }

    @Test
    void schedulesAndTags() {
        String bucket = bucket("id\n1\n");
        String datasetName = dataset(bucket, "raw/people.csv");
        String job = "scheduled-" + UUID.randomUUID().toString().substring(0, 6);
        post("/profileJobs", "{\"Name\":\"" + job + "\",\"DatasetName\":\"" + datasetName + "\",\"RoleArn\":\""
                + ROLE + "\",\"OutputLocation\":{\"Bucket\":\"" + bucket + "\"}}").statusCode(200);
        String schedule = "nightly-" + UUID.randomUUID().toString().substring(0, 6);
        post("/schedules", "{\"Name\":\"" + schedule + "\",\"CronExpression\":\"0 3 * * ? *\"}").statusCode(400);
        post("/schedules", "{\"Name\":\"" + schedule + "\",\"CronExpression\":\"cron(0 3 * * ? *)\","
                + "\"JobNames\":[\"missing\"]}").statusCode(400);
        post("/schedules", "{\"Name\":\"" + schedule + "\",\"CronExpression\":\"cron(0 3 * * ? *)\","
                + "\"JobNames\":[\"" + job + "\"],\"Tags\":{\"env\":\"test\"}}").statusCode(200);
        brew().queryParam("jobName", job).get("/schedules").then().body("Schedules.Name", equalTo(List.of(schedule)));
        brew().body("{\"CronExpression\":\"cron(30 4 * * ? *)\"}").put("/schedules/" + schedule).then()
                .statusCode(200);
        brew().get("/schedules/" + schedule).then().body("CronExpression", equalTo("cron(30 4 * * ? *)"))
                .body("JobNames", hasSize(0));

        String arn = brew().get("/schedules/" + schedule).then().extract().path("ResourceArn");
        String encoded = URLEncoder.encode(arn, StandardCharsets.UTF_8);
        brew().urlEncodingEnabled(false).body("{\"Tags\":{\"owner\":\"me\"}}").post("/tags/" + encoded).then()
                .statusCode(200);
        brew().urlEncodingEnabled(false).get("/tags/" + encoded).then().statusCode(200)
                .body("Tags.env", equalTo("test")).body("Tags.owner", equalTo("me"));
        brew().urlEncodingEnabled(false).delete("/tags/" + encoded + "?tagKeys=env").then().statusCode(200);
        brew().urlEncodingEnabled(false).get("/tags/" + encoded).then().body("Tags.env", equalTo(null))
                .body("Tags.owner", equalTo("me"));
        brew().urlEncodingEnabled(false).get("/tags/" + URLEncoder.encode(arn + "x", StandardCharsets.UTF_8))
                .then().statusCode(404);
        brew().delete("/schedules/" + schedule).then().statusCode(200);
        brew().get("/schedules/" + schedule).then().statusCode(404);
    }
}
