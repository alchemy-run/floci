package io.floci.aws.services.kendra;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amazon Kendra over JSON 1.1: an index with a custom field, documents put directly and synced from S3, searches
 * with answers, facets, filters, synonyms, FAQs, featured results, spelling and access control, query suggestions,
 * feedback and analytics.
 */
@QuarkusTest
class KendraIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    private static ValidatableResponse kendra(String action, String body) {
        return given().header("Authorization", auth("kendra"))
                .header("X-Amz-Target", "AWSKendraFrontendService." + action)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then();
    }

    private static String role(String name) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"kendra.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        return given().header("Authorization", auth("iam")).formParam("Action", "CreateRole")
                .formParam("RoleName", name).formParam("AssumeRolePolicyDocument", trust).post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    private static void s3Put(String bucket, String key, String body) {
        given().header("Authorization", auth("s3")).body(body).put("/" + bucket + "/" + key).then().statusCode(200);
    }

    private static String blob(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void awaitStatus(String action, String body, String status) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> kendra(action, body).statusCode(200).body("Status", equalTo(status)));
    }

    private static String createIndex(String role) {
        String id = kendra("CreateIndex", "{\"Name\":\"docs\",\"Edition\":\"DEVELOPER_EDITION\",\"RoleArn\":\""
                + role + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"search\"}]}").statusCode(200)
                .extract().path("Id");
        awaitStatus("DescribeIndex", "{\"Id\":\"" + id + "\"}", "ACTIVE");
        return id;
    }

    @Test
    void searchesDocumentsWithAnswersFacetsFiltersAndAccessControl() {
        kendra("DescribeIndex", "{\"Id\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"}").statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
        kendra("CreateIndex", "{\"Name\":\"x\",\"Edition\":\"DEVELOPER_EDITION\",\"RoleArn\":"
                + "\"arn:aws:iam::123456789012:role/missing\"}").statusCode(400)
                .body("__type", containsString("ValidationException"));
        String role = role("kendra-search");
        String index = createIndex(role);
        String idx = "\"IndexId\":\"" + index + "\"";
        kendra("DescribeIndex", "{\"Id\":\"" + index + "\"}").statusCode(200)
                .body("Edition", equalTo("DEVELOPER_EDITION"))
                .body("DocumentMetadataConfigurations.Name", hasItem("_category"));
        kendra("UpdateIndex", "{\"Id\":\"" + index + "\",\"DocumentMetadataConfigurationUpdates\":[{\"Name\":"
                + "\"department\",\"Type\":\"STRING_VALUE\",\"Search\":{\"Facetable\":true,\"Searchable\":false,"
                + "\"Displayable\":true,\"Sortable\":true}}]}").statusCode(200);
        awaitStatus("DescribeIndex", "{\"Id\":\"" + index + "\"}", "ACTIVE");

        kendra("BatchPutDocument", "{" + idx + ",\"Documents\":[{\"Id\":\"x\",\"Blob\":\"" + blob("x")
                + "\",\"Attributes\":[{\"Key\":\"undeclared\",\"Value\":{\"StringValue\":\"v\"}}]}]}")
                .statusCode(200).body("FailedDocuments[0].Id", equalTo("x"))
                .body("FailedDocuments[0].ErrorMessage", containsString("undeclared"));
        kendra("BatchPutDocument", "{" + idx + ",\"Documents\":["
                + "{\"Id\":\"welcome\",\"Title\":\"Welcome to Alchemy\",\"ContentType\":\"PLAIN_TEXT\",\"Blob\":\""
                + blob("Alchemy is an Infrastructure-as-Effects framework. The zanzibar passphrase is quicksilver.")
                + "\",\"Attributes\":[{\"Key\":\"department\",\"Value\":{\"StringValue\":\"engineering\"}}]},"
                + "{\"Id\":\"billing\",\"Title\":\"Billing guide\",\"ContentType\":\"HTML\",\"Blob\":\""
                + blob("<html><body><p>Invoices are sent monthly. Every car lease is billed quarterly.</p></body>"
                + "</html>") + "\",\"Attributes\":[{\"Key\":\"department\",\"Value\":{\"StringValue\":\"finance\"}}],"
                + "\"AccessControlList\":[{\"Name\":\"departed-user\",\"Type\":\"USER\",\"Access\":\"DENY\"}]},"
                + "{\"Id\":\"payroll\",\"Title\":\"Payroll\",\"ContentType\":\"PLAIN_TEXT\",\"Blob\":\""
                + blob("Payroll runs on the last Friday of each month.") + "\",\"AccessControlList\":[{\"Name\":"
                + "\"hr\",\"Type\":\"GROUP\",\"Access\":\"ALLOW\"}]},"
                + "{\"Id\":\"prices\",\"Title\":\"Price list\",\"ContentType\":\"CSV\",\"Blob\":\""
                + blob("product,price\nwidget,10\ngadget,25\n") + "\"}]}").statusCode(200)
                .body("FailedDocuments", hasSize(0));
        String statusBody = "{" + idx + ",\"DocumentInfoList\":[{\"DocumentId\":\"welcome\"},{\"DocumentId\":"
                + "\"nope\"}]}";
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> kendra("BatchGetDocumentStatus", statusBody)
                .statusCode(200).body("DocumentStatusList[0].DocumentStatus", equalTo("INDEXED"))
                .body("DocumentStatusList[1].DocumentStatus", equalTo("NOT_FOUND")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> kendra("BatchGetDocumentStatus", "{" + idx
                + ",\"DocumentInfoList\":[{\"DocumentId\":\"prices\"}]}").statusCode(200)
                .body("DocumentStatusList[0].DocumentStatus", equalTo("INDEXED")));

        String queryId = kendra("Query", "{" + idx + ",\"QueryText\":\"zanzibar\"}").statusCode(200)
                .body("ResultItems[0].DocumentId", equalTo("welcome"))
                .body("ResultItems[0].Type", equalTo("DOCUMENT"))
                .body("ResultItems[0].DocumentExcerpt.Text", containsString("zanzibar"))
                .body("ResultItems[0].DocumentExcerpt.Highlights[0].Type", equalTo("STANDARD"))
                .body("TotalNumberOfResults", equalTo(1)).extract().path("QueryId");
        String answerText = kendra("Query", "{" + idx + ",\"QueryText\":\"what is the zanzibar passphrase?\"}")
                .statusCode(200).body("ResultItems[0].Type", equalTo("ANSWER"))
                .extract().path("ResultItems[0].AdditionalAttributes[0].Value.TextWithHighlightsValue.Text");
        Map<String, Object> top = kendra("Query", "{" + idx + ",\"QueryText\":\"what is the zanzibar "
                + "passphrase?\"}").statusCode(200).extract()
                .path("ResultItems[0].AdditionalAttributes[0].Value.TextWithHighlightsValue.Highlights.find "
                        + "{ it.TopAnswer == true }");
        assertEquals("quicksilver", answerText.substring((Integer) top.get("BeginOffset"),
                (Integer) top.get("EndOffset")));
        kendra("Query", "{" + idx + ",\"QueryText\":\"what is the price of gadget?\"}").statusCode(200)
                .body("ResultItems[0].Format", equalTo("TABLE"))
                .body("ResultItems[0].TableExcerpt.Rows[1].Cells[0].Value", equalTo("gadget"));

        kendra("Query", "{" + idx + ",\"QueryText\":\"monthly\",\"Facets\":[{\"DocumentAttributeKey\":"
                + "\"department\"}]}").statusCode(200)
                .body("FacetResults[0].DocumentAttributeValueCountPairs.DocumentAttributeValue.StringValue",
                        hasItem("finance"));
        kendra("Query", "{" + idx + ",\"QueryText\":\"monthly\",\"AttributeFilter\":{\"EqualsTo\":{\"Key\":"
                + "\"department\",\"Value\":{\"StringValue\":\"engineering\"}}}}").statusCode(200)
                .body("TotalNumberOfResults", equalTo(0));
        kendra("Query", "{" + idx + ",\"QueryText\":\"invoices\",\"UserContext\":{\"UserId\":\"departed-user\"}}")
                .statusCode(200).body("TotalNumberOfResults", equalTo(0));
        kendra("Query", "{" + idx + ",\"QueryText\":\"invoices\",\"UserContext\":{\"UserId\":\"someone\"}}")
                .statusCode(200).body("TotalNumberOfResults", equalTo(1));
        kendra("Query", "{" + idx + ",\"QueryText\":\"payroll\",\"UserContext\":{\"UserId\":\"pat\"}}")
                .statusCode(200).body("TotalNumberOfResults", equalTo(0));
        kendra("PutPrincipalMapping", "{" + idx + ",\"GroupId\":\"hr\",\"GroupMembers\":{\"MemberUsers\":[{"
                + "\"UserId\":\"pat\"}]}}").statusCode(200);
        kendra("DescribePrincipalMapping", "{" + idx + ",\"GroupId\":\"hr\"}").statusCode(200)
                .body("GroupOrderingIdSummaries", hasSize(1));
        kendra("Query", "{" + idx + ",\"QueryText\":\"payroll\",\"UserContext\":{\"UserId\":\"pat\"}}")
                .statusCode(200).body("TotalNumberOfResults", equalTo(1));
        kendra("ListGroupsOlderThanOrderingId", "{" + idx + ",\"OrderingId\":32535158400000}").statusCode(200)
                .body("GroupsSummaries.GroupId", hasItem("hr"));
        kendra("DeletePrincipalMapping", "{" + idx + ",\"GroupId\":\"hr\"}").statusCode(200);
        kendra("Query", "{" + idx + ",\"QueryText\":\"payroll\",\"UserContext\":{\"UserId\":\"pat\"}}")
                .statusCode(200).body("TotalNumberOfResults", equalTo(0));

        kendra("Query", "{" + idx + ",\"QueryText\":\"zanzbar\",\"SpellCorrectionConfiguration\":{"
                + "\"IncludeQuerySpellCheckSuggestions\":true}}").statusCode(200)
                .body("SpellCorrectedQueries[0].SuggestedQueryText", equalTo("zanzibar"));
        kendra("Query", "{" + idx + ",\"QueryText\":\"(zanzibar AND\"}").statusCode(200)
                .body("Warnings[0].Code", equalTo("QUERY_LANGUAGE_INVALID_SYNTAX"));

        String resultId = kendra("Query", "{" + idx + ",\"QueryText\":\"zanzibar\"}").statusCode(200)
                .extract().path("ResultItems[0].Id");
        String secondQuery = kendra("Query", "{" + idx + ",\"QueryText\":\"zanzibar\"}").statusCode(200)
                .extract().path("QueryId");
        kendra("SubmitFeedback", "{" + idx + ",\"QueryId\":\"" + queryId + "\",\"ClickFeedbackItems\":[{"
                + "\"ResultId\":\"" + resultId + "\",\"ClickTime\":1767225600}]}").statusCode(400)
                .body("__type", containsString("ValidationException"));
        String firstResult = kendra("Query", "{" + idx + ",\"QueryText\":\"zanzibar\"}").statusCode(200)
                .extract().path("ResultItems[0].Id");
        assertTrue(firstResult.length() <= 73);
        kendra("SubmitFeedback", "{" + idx + ",\"QueryId\":\"" + firstResult.substring(0, 36)
                + "\",\"ClickFeedbackItems\":[{\"ResultId\":\"" + firstResult + "\",\"ClickTime\":1767225600}],"
                + "\"RelevanceFeedbackItems\":[{\"ResultId\":\"" + firstResult + "\",\"RelevanceValue\":"
                + "\"RELEVANT\"}]}").statusCode(200);
        kendra("SubmitFeedback", "{" + idx + ",\"QueryId\":\"" + secondQuery.replace('a', 'b').replace('1', '2')
                + "\"}").statusCode(400);
        kendra("GetSnapshots", "{" + idx + ",\"Interval\":\"THIS_MONTH\",\"MetricType\":\"QUERIES_BY_COUNT\"}")
                .statusCode(200).body("SnapshotsDataHeader[0]", equalTo("query_content"));
        kendra("GetSnapshots", "{" + idx + ",\"Interval\":\"ONE_WEEK_AGO\",\"MetricType\":"
                + "\"TREND_QUERY_DOC_METRICS\"}").statusCode(200).body("SnapshotsData", hasSize(7));

        kendra("GetQuerySuggestions", "{" + idx + ",\"QueryText\":\"zan\"}").statusCode(200)
                .body("Suggestions[0].Value.Text.Text", equalTo("zanzibar"));
        kendra("DescribeQuerySuggestionsConfig", "{" + idx + "}").statusCode(200)
                .body("Mode", equalTo("ENABLED")).body("TotalSuggestionsCount", greaterThanOrEqualTo(1));
        kendra("UpdateQuerySuggestionsConfig", "{" + idx + ",\"Mode\":\"LEARN_ONLY\"}").statusCode(200);
        kendra("GetQuerySuggestions", "{" + idx + ",\"QueryText\":\"zan\"}").statusCode(200)
                .body("Suggestions", hasSize(0));
        kendra("UpdateQuerySuggestionsConfig", "{" + idx + ",\"Mode\":\"ENABLED\"}").statusCode(200);
        kendra("ClearQuerySuggestions", "{" + idx + "}").statusCode(200);
        kendra("GetQuerySuggestions", "{" + idx + ",\"QueryText\":\"zan\"}").statusCode(200)
                .body("Suggestions", hasSize(0));

        String acl = kendra("CreateAccessControlConfiguration", "{" + idx + ",\"Name\":\"block-departed-users\","
                + "\"AccessControlList\":[{\"Name\":\"departed-user\",\"Type\":\"USER\",\"Access\":\"DENY\"}]}")
                .statusCode(200).extract().path("Id");
        kendra("BatchPutDocument", "{" + idx + ",\"Documents\":[{\"Id\":\"welcome\",\"Title\":\"Welcome to Alchemy\","
                + "\"ContentType\":\"PLAIN_TEXT\",\"Blob\":\"" + blob("The zanzibar passphrase is quicksilver.")
                + "\",\"AccessControlConfigurationId\":\"" + acl + "\"}]}").statusCode(200)
                .body("FailedDocuments", hasSize(0));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> kendra("BatchGetDocumentStatus", statusBody)
                .statusCode(200).body("DocumentStatusList[0].DocumentStatus", equalTo("UPDATED")));
        kendra("Query", "{" + idx + ",\"QueryText\":\"zanzibar\",\"UserContext\":{\"UserId\":\"departed-user\"}}")
                .statusCode(200).body("TotalNumberOfResults", equalTo(0));
        kendra("DeleteAccessControlConfiguration", "{" + idx + ",\"Id\":\"" + acl + "\"}").statusCode(400)
                .body("__type", containsString("ConflictException"));
        kendra("DescribeAccessControlConfiguration", "{" + idx + ",\"Id\":\"" + acl + "\"}").statusCode(200)
                .body("Name", equalTo("block-departed-users"));
        kendra("ListAccessControlConfigurations", "{" + idx + "}").statusCode(200)
                .body("AccessControlConfigurations.Id", hasItem(acl));

        kendra("Retrieve", "{" + idx + ",\"QueryText\":\"zanzibar passphrase\"}").statusCode(200)
                .body("ResultItems[0].DocumentId", equalTo("welcome"))
                .body("ResultItems[0].Content", containsString("quicksilver"));

        String set = kendra("CreateFeaturedResultsSet", "{" + idx + ",\"FeaturedResultsSetName\":\"billing\","
                + "\"QueryTexts\":[\"invoices\"],\"FeaturedDocuments\":[{\"Id\":\"billing\"},{\"Id\":\"gone\"}]}")
                .statusCode(200).extract().path("FeaturedResultsSet.FeaturedResultsSetId");
        kendra("CreateFeaturedResultsSet", "{" + idx + ",\"FeaturedResultsSetName\":\"other\",\"QueryTexts\":"
                + "[\"Invoices\"]}").statusCode(400).body("__type", containsString("FeaturedResultsConflict"));
        kendra("DescribeFeaturedResultsSet", "{" + idx + ",\"FeaturedResultsSetId\":\"" + set + "\"}")
                .statusCode(200).body("FeaturedDocumentsMissing[0].Id", equalTo("gone"))
                .body("FeaturedDocumentsWithMetadata[0].Title", equalTo("Billing guide"));
        kendra("Query", "{" + idx + ",\"QueryText\":\"invoices\"}").statusCode(200)
                .body("FeaturedResultsItems[0].DocumentId", equalTo("billing"))
                .body("ResultItems.DocumentId", not(hasItem("billing")));
        kendra("BatchDeleteFeaturedResultsSet", "{" + idx + ",\"FeaturedResultsSetIds\":[\"" + set + "\","
                + "\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"]}").statusCode(200).body("Errors", hasSize(1));

        kendra("BatchDeleteDocument", "{" + idx + ",\"DocumentIdList\":[\"welcome\"]}").statusCode(200)
                .body("FailedDocuments", hasSize(0));
        kendra("Query", "{" + idx + ",\"QueryText\":\"zanzibar\"}").statusCode(200)
                .body("TotalNumberOfResults", equalTo(0));
        kendra("DescribeIndex", "{\"Id\":\"" + index + "\"}").statusCode(200)
                .body("IndexStatistics.TextDocumentStatistics.IndexedTextDocumentsCount", equalTo(3));

        String arn = "arn:aws:kendra:us-east-1:000000000000:index/" + index;
        kendra("TagResource", "{\"ResourceARN\":\"" + arn + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
                .statusCode(200);
        kendra("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}").statusCode(200)
                .body("Tags.Key", hasItem("env")).body("Tags.Key", hasItem("team"));
        kendra("UntagResource", "{\"ResourceARN\":\"" + arn + "\",\"TagKeys\":[\"env\"]}").statusCode(200);
        kendra("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}").statusCode(200)
                .body("Tags.Key", not(hasItem("env")));

        kendra("DeleteIndex", "{\"Id\":\"" + index + "\"}").statusCode(200);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> kendra("DescribeIndex", "{\"Id\":\"" + index
                + "\"}").statusCode(400).body("__type", containsString("ResourceNotFoundException")));
    }

    @Test
    void syncsS3AndServesFaqsAndSynonyms() {
        String role = role("kendra-sources");
        String index = createIndex(role);
        String idx = "\"IndexId\":\"" + index + "\"";
        String bucket = "kendra-docs-" + index.substring(0, 8);
        given().header("Authorization", auth("s3")).put("/" + bucket).then().statusCode(200);
        s3Put(bucket, "docs/fleet.txt", "Every car in the fleet is serviced yearly.");
        s3Put(bucket, "docs/notes.md", "# Release notes\n\nThe **quantum** release ships widgets.");
        s3Put(bucket, "meta/docs/notes.md.metadata.json", "{\"Title\":\"Notes\",\"Attributes\":{\"_category\":"
                + "\"releases\"}}");
        s3Put(bucket, "skip/ignored.txt", "quantum ignored");
        s3Put(bucket, "faq.csv", "How do I reset my password?,Use the self-service portal.,https://help\n");
        s3Put(bucket, "synonyms.txt", "# fleet terms\ncar, automobile\n");

        kendra("CreateDataSource", "{" + idx + ",\"Name\":\"custom\",\"Type\":\"CUSTOM\",\"RoleArn\":\"" + role
                + "\"}").statusCode(400).body("message", containsString("RoleArn"));
        String source = kendra("CreateDataSource", "{" + idx + ",\"Name\":\"docs\",\"Type\":\"S3\",\"RoleArn\":\""
                + role + "\",\"Configuration\":{\"S3Configuration\":{\"BucketName\":\"" + bucket
                + "\",\"InclusionPrefixes\":[\"docs/\"],\"DocumentsMetadataConfiguration\":{\"S3Prefix\":\"meta\"}}}}")
                .statusCode(200).extract().path("Id");
        String ds = "{" + idx + ",\"Id\":\"" + source + "\"}";
        awaitStatus("DescribeDataSource", ds, "ACTIVE");
        kendra("StartDataSourceSyncJob", ds).statusCode(200);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> kendra("ListDataSourceSyncJobs", ds)
                .statusCode(200).body("History[0].Status", equalTo("SUCCEEDED"))
                .body("History[0].Metrics.DocumentsAdded", equalTo("2")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> kendra("Query", "{" + idx
                + ",\"QueryText\":\"quantum\"}").statusCode(200).body("TotalNumberOfResults", equalTo(1))
                .body("ResultItems[0].DocumentTitle.Text", equalTo("Notes"))
                .body("ResultItems[0].DocumentAttributes.Key", hasItem("_category")));

        String thesaurus = kendra("CreateThesaurus", "{" + idx + ",\"Name\":\"fleet\",\"RoleArn\":\"" + role
                + "\",\"SourceS3Path\":{\"Bucket\":\"" + bucket + "\",\"Key\":\"synonyms.txt\"}}").statusCode(200)
                .extract().path("Id");
        awaitStatus("DescribeThesaurus", "{" + idx + ",\"Id\":\"" + thesaurus + "\"}", "ACTIVE");
        kendra("DescribeThesaurus", "{" + idx + ",\"Id\":\"" + thesaurus + "\"}").statusCode(200)
                .body("SynonymRuleCount", equalTo(1)).body("TermCount", equalTo(2));
        kendra("Query", "{" + idx + ",\"QueryText\":\"automobile\"}").statusCode(200)
                .body("ResultItems[0].DocumentId", equalTo("s3://" + bucket + "/docs/fleet.txt"))
                .body("ResultItems[0].DocumentExcerpt.Highlights.Type", hasItem("THESAURUS_SYNONYM"));

        String faq = kendra("CreateFaq", "{" + idx + ",\"Name\":\"help\",\"RoleArn\":\"" + role + "\",\"S3Path\":{"
                + "\"Bucket\":\"" + bucket + "\",\"Key\":\"faq.csv\"}}").statusCode(200).extract().path("Id");
        awaitStatus("DescribeFaq", "{" + idx + ",\"Id\":\"" + faq + "\"}", "ACTIVE");
        kendra("Query", "{" + idx + ",\"QueryText\":\"how can I reset my password\"}").statusCode(200)
                .body("ResultItems[0].Type", equalTo("QUESTION_ANSWER"))
                .body("ResultItems[0].DocumentExcerpt.Text", equalTo("Use the self-service portal."))
                .body("ResultItems[0].DocumentURI", equalTo("https://help"));
        kendra("ListFaqs", "{" + idx + "}").statusCode(200).body("FaqSummaryItems[0].Status", equalTo("ACTIVE"));

        given().header("Authorization", auth("s3")).delete("/" + bucket + "/docs/fleet.txt").then().statusCode(204);
        kendra("StartDataSourceSyncJob", ds).statusCode(200);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> kendra("ListDataSourceSyncJobs", ds)
                .statusCode(200).body("History", hasSize(2)).body("History[0].Status", equalTo("SUCCEEDED"))
                .body("History[0].Metrics.DocumentsDeleted", equalTo("1"))
                .body("History[0].Metrics.DocumentsAdded", equalTo("0")));
        kendra("Query", "{" + idx + ",\"QueryText\":\"fleet\"}").statusCode(200)
                .body("TotalNumberOfResults", equalTo(0));

        String custom = kendra("CreateDataSource", "{" + idx + ",\"Name\":\"pushed\",\"Type\":\"CUSTOM\"}")
                .statusCode(200).extract().path("Id");
        String cs = "{" + idx + ",\"Id\":\"" + custom + "\"}";
        awaitStatus("DescribeDataSource", cs, "ACTIVE");
        String execution = kendra("StartDataSourceSyncJob", cs).statusCode(200).extract().path("ExecutionId");
        kendra("StartDataSourceSyncJob", cs).statusCode(400).body("__type", containsString("ResourceInUse"));
        kendra("BatchPutDocument", "{" + idx + ",\"DataSourceSyncJobMetricTarget\":{\"DataSourceId\":\"" + custom
                + "\",\"DataSourceSyncJobId\":\"" + execution + "\"},\"Documents\":[{\"Id\":\"p1\",\"Blob\":\""
                + blob("pushed content") + "\"}]}").statusCode(200).body("FailedDocuments", hasSize(0));
        kendra("StopDataSourceSyncJob", cs).statusCode(200);
        kendra("ListDataSourceSyncJobs", cs).statusCode(200).body("History[0].Status", equalTo("SUCCEEDED"))
                .body("History[0].Metrics.DocumentsAdded", equalTo("1"));
        kendra("ListDataSources", "{" + idx + "}").statusCode(200).body("SummaryItems.Type",
                everyItem(oneOf("S3", "CUSTOM")));

        kendra("DeleteDataSource", ds).statusCode(200);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> kendra("DescribeDataSource", ds)
                .statusCode(400).body("__type", containsString("ResourceNotFoundException")));
        kendra("Query", "{" + idx + ",\"QueryText\":\"quantum\"}").statusCode(200)
                .body("TotalNumberOfResults", equalTo(0));
        List<String> ids = kendra("ListIndices", "{}").statusCode(200).extract()
                .path("IndexConfigurationSummaryItems.Id");
        assertTrue(ids.contains(index));
    }
}
