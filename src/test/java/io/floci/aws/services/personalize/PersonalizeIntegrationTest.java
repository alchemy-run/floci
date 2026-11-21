package io.floci.aws.services.personalize;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Personalize end to end: schemas, a dataset group and its datasets imported from CSV in S3, an event tracker,
 * solutions trained into versions with metrics, campaigns answering recommendations and rankings that follow the
 * interactions, filters, real-time events, exports, batch inference, recommenders and the checks along the way.
 */
@QuarkusTest
class PersonalizeIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification api(String action, String body) {
        return given().header("Authorization", auth("personalize"))
                .header("X-Amz-Target", "AmazonPersonalize." + action)
                .contentType("application/x-amz-json-1.1").body(body);
    }

    static RequestSpecification runtime(String body) {
        return given().header("Authorization", auth("personalize")).contentType("application/json").body(body);
    }

    static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static void put(String bucket, String key, String body) {
        given().header("Authorization", auth("s3")).put("/" + bucket).then().statusCode(anyOf(equalTo(200),
                equalTo(409)));
        given().header("Authorization", auth("s3")).contentType("application/octet-stream")
                .body(body.getBytes(StandardCharsets.UTF_8)).put("/" + bucket + "/" + key).then().statusCode(200);
    }

    static String read(String bucket, String key) {
        return given().header("Authorization", auth("s3")).get("/" + bucket + "/" + key).then().statusCode(200)
                .extract().asString();
    }

    static String role(String name) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"personalize.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        return given().header("Authorization", auth("iam")).formParam("Action", "CreateRole")
                .formParam("RoleName", name).formParam("AssumeRolePolicyDocument", trust).post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    static void awaitStatus(String action, String member, String arnField, String arn, String status) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> status.equals(
                api(action, "{\"" + arnField + "\":\"" + arn + "\"}").post("/").then().statusCode(200).extract()
                        .path(member + ".status")));
    }

    static String schema(String name, String fields) {
        return api("CreateSchema", "{\"name\":\"" + name + "\",\"schema\":" + json("{\"type\":\"record\",\"name\":\""
                + name + "\",\"namespace\":\"com.amazonaws.personalize.schema\",\"fields\":[" + fields + "],"
                + "\"version\":\"1.0\"}") + "}").post("/").then().statusCode(200).extract().path("schemaArn");
    }

    static final String INTERACTIONS = "{\"name\":\"USER_ID\",\"type\":\"string\"},{\"name\":\"ITEM_ID\",\"type\":"
            + "\"string\"},{\"name\":\"TIMESTAMP\",\"type\":\"long\"},{\"name\":\"EVENT_TYPE\",\"type\":\"string\"}";

    /** Twenty users in two tastes: u0-u9 read three of four books, u10-u19 play three of four albums. */
    static String interactionsCsv() {
        StringBuilder csv = new StringBuilder("USER_ID,ITEM_ID,TIMESTAMP,EVENT_TYPE\n");
        long time = 1_700_000_000L;
        for (int user = 0; user < 20; user++) {
            String prefix = user < 10 ? "b" : "m";
            for (int i = 0; i < 4; i++) {
                if (i == user % 4) {
                    continue;
                }
                csv.append("u").append(user).append(',').append(prefix).append(i + 1).append(',')
                        .append(time++).append(",click\n");
            }
        }
        return csv.toString();
    }

    static String dataset(String name, String schemaArn, String groupArn, String type) {
        String arn = api("CreateDataset", "{\"name\":\"" + name + "\",\"schemaArn\":\"" + schemaArn
                + "\",\"datasetGroupArn\":\"" + groupArn + "\",\"datasetType\":\"" + type + "\"}").post("/").then()
                .statusCode(200).extract().path("datasetArn");
        awaitStatus("DescribeDataset", "dataset", "datasetArn", arn, "ACTIVE");
        return arn;
    }

    static void importCsv(String job, String datasetArn, String location, String role) {
        String arn = api("CreateDatasetImportJob", "{\"jobName\":\"" + job + "\",\"datasetArn\":\"" + datasetArn
                + "\",\"dataSource\":{\"dataLocation\":\"" + location + "\"},\"roleArn\":\"" + role + "\"}")
                .post("/").then().statusCode(200).extract().path("datasetImportJobArn");
        awaitStatus("DescribeDatasetImportJob", "datasetImportJob", "datasetImportJobArn", arn, "ACTIVE");
    }

    static String trained(String name, String groupArn, String recipe) {
        String solution = api("CreateSolution", "{\"name\":\"" + name + "\",\"datasetGroupArn\":\"" + groupArn
                + "\",\"recipeArn\":\"arn:aws:personalize:::recipe/" + recipe + "\"}").post("/").then()
                .statusCode(200).extract().path("solutionArn");
        awaitStatus("DescribeSolution", "solution", "solutionArn", solution, "ACTIVE");
        String version = api("CreateSolutionVersion", "{\"solutionArn\":\"" + solution + "\"}").post("/").then()
                .statusCode(200).extract().path("solutionVersionArn");
        awaitStatus("DescribeSolutionVersion", "solutionVersion", "solutionVersionArn", version, "ACTIVE");
        String campaign = api("CreateCampaign", "{\"name\":\"" + name + "-campaign\",\"solutionVersionArn\":\""
                + version + "\"}").post("/").then().statusCode(200).extract().path("campaignArn");
        awaitStatus("DescribeCampaign", "campaign", "campaignArn", campaign, "ACTIVE");
        return campaign;
    }

    @Test
    void campaignsRecommendFromImportedInteractionsAndEvents() {
        String role = role("personalize-e2e");
        put("personalize-e2e", "interactions.csv", interactionsCsv());
        put("personalize-e2e", "items.csv", "ITEM_ID,GENRE\nb1,fiction\nb2,fiction\nb3,fiction\nb4,fiction\n"
                + "m1,jazz|music\nm2,music\nm3,music\nm4,music\n");

        api("CreateSchema", "{\"name\":\"broken\",\"schema\":\"{not json\"}").post("/").then().statusCode(400)
                .body("__type", containsString("InvalidInputException"));
        String interactionsSchema = schema("e2e-interactions", INTERACTIONS);
        String itemsSchema = schema("e2e-items", "{\"name\":\"ITEM_ID\",\"type\":\"string\"},"
                + "{\"name\":\"GENRE\",\"type\":\"string\",\"categorical\":true}");
        String group = api("CreateDatasetGroup", "{\"name\":\"e2e\",\"tags\":[{\"tagKey\":\"team\",\"tagValue\":"
                + "\"recs\"}]}").post("/").then().statusCode(200).extract().path("datasetGroupArn");
        api("CreateDatasetGroup", "{\"name\":\"e2e\"}").post("/").then().statusCode(400)
                .body("__type", containsString("ResourceAlreadyExistsException"));
        awaitStatus("DescribeDatasetGroup", "datasetGroup", "datasetGroupArn", group, "ACTIVE");
        api("CreateDataset", "{\"name\":\"wrong\",\"schemaArn\":\"" + itemsSchema + "\",\"datasetGroupArn\":\""
                + group + "\",\"datasetType\":\"Interactions\"}").post("/").then().statusCode(400)
                .body("message", containsString("needs the field USER_ID"));
        api("CreateEventTracker", "{\"name\":\"early\",\"datasetGroupArn\":\"" + group + "\"}").post("/").then()
                .statusCode(400).body("message", containsString("Interactions dataset"));

        String interactions = dataset("e2e-interactions", interactionsSchema, group, "Interactions");
        String items = dataset("e2e-items", itemsSchema, group, "Items");
        api("DescribeDataset", "{\"datasetArn\":\"" + interactions + "\"}").post("/").then().statusCode(200)
                .body("dataset.datasetType", equalTo("INTERACTIONS"));
        importCsv("e2e-interactions", interactions, "s3://personalize-e2e/interactions.csv", role);
        importCsv("e2e-items", items, "s3://personalize-e2e/items.csv", role);

        String tracker = api("CreateEventTracker", "{\"name\":\"e2e\",\"datasetGroupArn\":\"" + group + "\"}")
                .post("/").then().statusCode(200).extract().path("trackingId");
        awaitStatus("DescribeDataset", "dataset", "datasetArn", interactions, "ACTIVE");
        api("DescribeDataset", "{\"datasetArn\":\"" + interactions + "\"}").post("/").then()
                .body("dataset.trackingId", equalTo(tracker));

        String campaign = trained("e2e", group, "aws-user-personalization");
        String version = api("DescribeCampaign", "{\"campaignArn\":\"" + campaign + "\"}").post("/").then()
                .extract().path("campaign.solutionVersionArn");
        api("GetSolutionMetrics", "{\"solutionVersionArn\":\"" + version + "\"}").post("/").then().statusCode(200)
                .body("metrics.precision_at_5", notNullValue()).body("metrics.coverage", notNullValue());

        // u0 read b2, b3 and b4: readers of those read b1, so b1 leads and the albums trail.
        List<String> forReader = runtime("{\"campaignArn\":\"" + campaign + "\",\"userId\":\"u0\",\"numResults\":8}")
                .post("/recommendations").then().statusCode(200).body("recommendationId", startsWith("RID-"))
                .extract().path("itemList.itemId");
        assertEquals("b1", forReader.get(0));
        assertTrue(forReader.indexOf("m1") > forReader.indexOf("b4"));

        // Filters: drop what the user clicked, or keep a genre given at request time.
        String unseen = api("CreateFilter", "{\"name\":\"unseen\",\"datasetGroupArn\":\"" + group
                + "\",\"filterExpression\":" + json("EXCLUDE ItemID WHERE Interactions.EVENT_TYPE IN (\"click\")")
                + "}").post("/").then().statusCode(200).extract().path("filterArn");
        String genre = api("CreateFilter", "{\"name\":\"genre\",\"datasetGroupArn\":\"" + group
                + "\",\"filterExpression\":" + json("INCLUDE ItemID WHERE Items.GENRE IN ($GENRE)") + "}").post("/")
                .then().statusCode(200).extract().path("filterArn");
        api("CreateFilter", "{\"name\":\"bad\",\"datasetGroupArn\":\"" + group + "\",\"filterExpression\":"
                + json("KEEP ItemID WHERE Items.GENRE IN (\"x\")") + "}").post("/").then().statusCode(400);
        awaitStatus("DescribeFilter", "filter", "filterArn", unseen, "ACTIVE");
        awaitStatus("DescribeFilter", "filter", "filterArn", genre, "ACTIVE");
        List<String> fresh = runtime("{\"campaignArn\":\"" + campaign + "\",\"userId\":\"u0\",\"filterArn\":\""
                + unseen + "\"}").post("/recommendations").then().statusCode(200).extract().path("itemList.itemId");
        assertEquals("b1", fresh.get(0));
        assertTrue(!fresh.contains("b2") && !fresh.contains("b3") && !fresh.contains("b4"));
        runtime("{\"campaignArn\":\"" + campaign + "\",\"userId\":\"u0\",\"filterArn\":\"" + genre
                + "\",\"filterValues\":{\"GENRE\":" + json("\"jazz\"") + "}}").post("/recommendations").then()
                .statusCode(200).body("itemList.itemId", equalTo(List.of("m1")));

        // A new listener's clicks, sent in real time, steer their recommendations toward albums.
        runtime("{\"trackingId\":\"" + tracker + "\",\"userId\":\"u99\",\"sessionId\":\"s1\",\"eventList\":["
                + "{\"eventType\":\"click\",\"itemId\":\"m1\",\"sentAt\":1700001000},"
                + "{\"eventType\":\"click\",\"itemId\":\"m2\",\"sentAt\":1700001001}]}").post("/events").then()
                .statusCode(200);
        runtime("{\"trackingId\":\"not-a-tracker\",\"sessionId\":\"s1\",\"eventList\":[{\"eventType\":\"click\","
                + "\"itemId\":\"m1\",\"sentAt\":1700001000}]}").post("/events").then().statusCode(400)
                .body("__type", containsString("InvalidInputException"));
        List<String> forListener = runtime("{\"campaignArn\":\"" + campaign + "\",\"userId\":\"u99\","
                + "\"numResults\":3}").post("/recommendations").then().statusCode(200).extract()
                .path("itemList.itemId");
        assertTrue(forListener.get(0).startsWith("m"), String.valueOf(forListener));

        runtime("{\"datasetArn\":\"" + items + "\",\"items\":[{\"itemId\":\"m5\",\"properties\":"
                + json("{\"genre\":\"music\"}") + "}]}").post("/items").then().statusCode(200);
        runtime("{\"datasetArn\":\"" + items + "\",\"items\":[{\"itemId\":\"m6\",\"properties\":"
                + json("{\"mood\":\"calm\"}") + "}]}").post("/items").then().statusCode(400)
                .body("message", containsString("mood"));
        runtime("{\"datasetArn\":\"" + interactions + "\",\"users\":[{\"userId\":\"u1\"}]}").post("/users").then()
                .statusCode(400).body("__type", containsString("InvalidInputException"));

        // Ranking reorders a list for the user.
        String ranking = trained("e2e-rank", group, "aws-personalized-ranking");
        runtime("{\"campaignArn\":\"" + ranking + "\",\"userId\":\"u0\",\"inputList\":[\"m3\",\"b1\"]}")
                .post("/personalize-ranking").then().statusCode(200)
                .body("personalizedRanking[0].itemId", equalTo("b1"));
        runtime("{\"campaignArn\":\"" + ranking + "\",\"userId\":\"u0\"}").post("/recommendations").then()
                .statusCode(400).body("message", containsString("PERSONALIZED_RANKING"));

        // Export the interactions recorded through the events API, and run a batch inference job.
        String export = api("CreateDatasetExportJob", "{\"jobName\":\"e2e-export\",\"datasetArn\":\"" + interactions
                + "\",\"ingestionMode\":\"PUT\",\"roleArn\":\"" + role + "\",\"jobOutput\":{\"s3DataDestination\":"
                + "{\"path\":\"s3://personalize-e2e/exports/\"}}}").post("/").then().statusCode(200).extract()
                .path("datasetExportJobArn");
        awaitStatus("DescribeDatasetExportJob", "datasetExportJob", "datasetExportJobArn", export, "ACTIVE");
        String exported = read("personalize-e2e", "exports/e2e-export/interactions.csv");
        assertTrue(exported.startsWith("USER_ID,ITEM_ID,TIMESTAMP,EVENT_TYPE\n"), exported);
        assertTrue(exported.contains("u99,m1,1700001000,click") && !exported.contains("u0,"), exported);

        put("personalize-e2e", "batch/users.json", "{\"userId\":\"u0\"}\n{\"userId\":\"u15\"}\n");
        String batch = api("CreateBatchInferenceJob", "{\"jobName\":\"e2e-batch\",\"solutionVersionArn\":\"" + version
                + "\",\"numResults\":2,\"roleArn\":\"" + role + "\",\"jobInput\":{\"s3DataSource\":{\"path\":"
                + "\"s3://personalize-e2e/batch/users.json\"}},\"jobOutput\":{\"s3DataDestination\":{\"path\":"
                + "\"s3://personalize-e2e/batch-out/\"}}}").post("/").then().statusCode(200).extract()
                .path("batchInferenceJobArn");
        awaitStatus("DescribeBatchInferenceJob", "batchInferenceJob", "batchInferenceJobArn", batch, "ACTIVE");
        String[] lines = read("personalize-e2e", "batch-out/users.json.out").split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].contains("\"recommendedItems\":[\"b1\"") && lines[1].contains("\"recommendedItems\":[\"m"),
                String.join("\n", lines));

        // Tags, then teardown in dependency order.
        api("ListTagsForResource", "{\"resourceArn\":\"" + group + "\"}").post("/").then().statusCode(200)
                .body("tags.tagKey", hasItem("team"));
        api("TagResource", "{\"resourceArn\":\"" + campaign + "\",\"tags\":[{\"tagKey\":\"tier\",\"tagValue\":"
                + "\"gold\"}]}").post("/").then().statusCode(200);
        api("UntagResource", "{\"resourceArn\":\"" + campaign + "\",\"tagKeys\":[\"tier\"]}").post("/").then()
                .statusCode(200);
        api("ListTagsForResource", "{\"resourceArn\":\"" + campaign + "\"}").post("/").then()
                .body("tags.tagKey", not(hasItem("tier")));
        api("DeleteDatasetGroup", "{\"datasetGroupArn\":\"" + group + "\"}").post("/").then().statusCode(400)
                .body("__type", containsString("ResourceInUseException"));
        String solution = version.substring(0, version.lastIndexOf('/'));
        api("DeleteSolution", "{\"solutionArn\":\"" + solution + "\"}").post("/").then().statusCode(400)
                .body("__type", containsString("ResourceInUseException"));
        api("ListCampaigns", "{\"solutionArn\":\"" + solution + "\"}").post("/").then().statusCode(200)
                .body("campaigns.campaignArn", equalTo(List.of(campaign)));
    }

    @Test
    void trainingNeedsEnoughInteractionsAndRecommendersNeedADomain() {
        String interactionsSchema = schema("few-interactions", INTERACTIONS);
        String group = api("CreateDatasetGroup", "{\"name\":\"few\"}").post("/").then().statusCode(200).extract()
                .path("datasetGroupArn");
        awaitStatus("DescribeDatasetGroup", "datasetGroup", "datasetGroupArn", group, "ACTIVE");
        String interactions = dataset("few-interactions", interactionsSchema, group, "Interactions");
        put("personalize-few", "few.csv", "USER_ID,ITEM_ID,TIMESTAMP,EVENT_TYPE\nu1,i1,1,click\nu2,i1,2,click\n");
        importCsv("few", interactions, "s3://personalize-few/few.csv", role("personalize-few"));
        String solution = api("CreateSolution", "{\"name\":\"few\",\"datasetGroupArn\":\"" + group
                + "\",\"recipeArn\":\"arn:aws:personalize:::recipe/aws-popularity-count\"}").post("/").then()
                .statusCode(200).extract().path("solutionArn");
        awaitStatus("DescribeSolution", "solution", "solutionArn", solution, "ACTIVE");
        String version = api("CreateSolutionVersion", "{\"solutionArn\":\"" + solution + "\"}").post("/").then()
                .statusCode(200).extract().path("solutionVersionArn");
        awaitStatus("DescribeSolutionVersion", "solutionVersion", "solutionVersionArn", version, "CREATE FAILED");
        api("DescribeSolutionVersion", "{\"solutionVersionArn\":\"" + version + "\"}").post("/").then()
                .body("solutionVersion.failureReason", containsString("at least 10 interactions"));
        api("CreateCampaign", "{\"name\":\"few\",\"solutionVersionArn\":\"" + version + "\"}").post("/").then()
                .statusCode(400).body("message", containsString("not ACTIVE"));
        api("GetSolutionMetrics", "{\"solutionVersionArn\":\"" + version + "\"}").post("/").then().statusCode(400)
                .body("__type", containsString("ResourceInUseException"));
        api("CreateRecommender", "{\"name\":\"few\",\"datasetGroupArn\":\"" + group + "\",\"recipeArn\":"
                + "\"arn:aws:personalize:::recipe/aws-ecomm-popular-items-by-views\"}").post("/").then()
                .statusCode(400).body("message", containsString("domain"));
        api("DescribeSolutionVersion", "{\"solutionVersionArn\":\"" + solution + "/0123456789abcdef\"}").post("/")
                .then().statusCode(400).body("__type", containsString("ResourceNotFoundException"));
        api("ListRecipes", "{\"domain\":\"ECOMMERCE\"}").post("/").then().statusCode(200)
                .body("recipes.domain", everyItem(equalTo("ECOMMERCE")));

        // An ECOMMERCE dataset group trains a recommender from its View events.
        String domainSchema = api("CreateSchema", "{\"name\":\"shop-interactions\",\"domain\":\"ECOMMERCE\","
                + "\"schema\":" + json("{\"type\":\"record\",\"name\":\"Interactions\",\"namespace\":"
                + "\"com.amazonaws.personalize.schema\",\"fields\":[" + INTERACTIONS + "],\"version\":\"1.0\"}") + "}")
                .post("/").then().statusCode(200).extract().path("schemaArn");
        String shop = api("CreateDatasetGroup", "{\"name\":\"shop\",\"domain\":\"ECOMMERCE\"}").post("/").then()
                .statusCode(200).body("domain", equalTo("ECOMMERCE")).extract().path("datasetGroupArn");
        awaitStatus("DescribeDatasetGroup", "datasetGroup", "datasetGroupArn", shop, "ACTIVE");
        api("CreateDataset", "{\"name\":\"plain\",\"schemaArn\":\"" + interactionsSchema + "\",\"datasetGroupArn\":\""
                + shop + "\",\"datasetType\":\"Interactions\"}").post("/").then().statusCode(400);
        String shopInteractions = dataset("shop-interactions", domainSchema, shop, "Interactions");
        StringBuilder views = new StringBuilder("USER_ID,ITEM_ID,TIMESTAMP,EVENT_TYPE\n");
        for (int i = 0; i < 12; i++) {
            views.append("u").append(i).append(",hat,").append(100 + i).append(",View\n");
            if (i < 4) {
                views.append("u").append(i).append(",scarf,").append(200 + i).append(",View\n");
            }
            views.append("u").append(i).append(",boots,").append(300 + i).append(",Purchase\n");
        }
        put("personalize-shop", "views.csv", views.toString());
        importCsv("shop", shopInteractions, "s3://personalize-shop/views.csv", role("personalize-shop"));
        String recommender = api("CreateRecommender", "{\"name\":\"most-viewed\",\"datasetGroupArn\":\"" + shop
                + "\",\"recipeArn\":\"arn:aws:personalize:::recipe/aws-ecomm-popular-items-by-views\"}").post("/")
                .then().statusCode(200).extract().path("recommenderArn");
        awaitStatus("DescribeRecommender", "recommender", "recommenderArn", recommender, "ACTIVE");
        runtime("{\"recommenderArn\":\"" + recommender + "\",\"userId\":\"u1\"}").post("/recommendations").then()
                .statusCode(200).body("itemList.itemId", equalTo(List.of("hat", "scarf")));
        api("StopRecommender", "{\"recommenderArn\":\"" + recommender + "\"}").post("/").then().statusCode(200);
        awaitStatus("DescribeRecommender", "recommender", "recommenderArn", recommender, "INACTIVE");
        runtime("{\"recommenderArn\":\"" + recommender + "\",\"userId\":\"u1\"}").post("/recommendations").then()
                .statusCode(400).body("message", containsString("INACTIVE"));
        api("DeleteRecommender", "{\"recommenderArn\":\"" + recommender + "\"}").post("/").then().statusCode(200);
    }
}
