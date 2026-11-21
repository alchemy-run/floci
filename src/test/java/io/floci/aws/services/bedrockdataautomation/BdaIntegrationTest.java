package io.floci.aws.services.bedrockdataautomation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bedrock Data Automation end to end: blueprints with stages and versions, projects, libraries and their ingestion
 * jobs, asynchronous and synchronous invocations that read a PDF and write AWS's output layout with the matched
 * blueprint's fields, blueprint optimization, and tags.
 */
@QuarkusTest
class BdaIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";
    private static final String PROFILE = "arn:aws:bedrock:us-east-1:" + ACCOUNT
            + ":data-automation-profile/us.data-automation-v1";
    private static final String SCHEMA = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"description\":"
            + "\"Invoices\",\"class\":\"invoice\",\"type\":\"object\",\"definitions\":{},\"properties\":{"
            + "\"invoice_number\":{\"type\":\"string\",\"inferenceType\":\"explicit\",\"instruction\":\"The invoice "
            + "number\"},\"total_amount\":{\"type\":\"number\",\"inferenceType\":\"explicit\",\"instruction\":\"The "
            + "amount due\"}}}";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification control() {
        return given().contentType("application/json").urlEncodingEnabled(false)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request");
    }

    private static ValidatableResponse runtime(String action, String body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request")
                .header("X-Amz-Target", "AmazonBedrockKeystoneRuntimeService." + action).body(body).post("/").then();
    }

    private static String enc(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }

    private static String s3(String method, String path, byte[] body) {
        RequestSpecification request = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/s3/aws4_request");
        if (body != null) {
            request = request.body(body);
        }
        return request.request(method, path).then().statusCode(200).extract().asString();
    }

    /** A one-page PDF showing {@code lines}, its content stream Flate-compressed when asked. */
    static byte[] pdf(boolean compressed, String... lines) throws Exception {
        StringBuilder content = new StringBuilder("BT /F1 12 Tf 72 720 Td\n");
        for (String line : lines) {
            content.append('(').append(line.replace("(", "\\(").replace(")", "\\)")).append(") Tj 0 -16 Td\n");
        }
        content.append("ET");
        byte[] stream = content.toString().getBytes(StandardCharsets.ISO_8859_1);
        if (compressed) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
                deflater.write(stream);
            }
            stream = out.toByteArray();
        }
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        pdf.write(("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>"
                + "endobj\n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Resources<</Font<</F1 5 0 R>>>>"
                + "/Contents 4 0 R>>endobj\n4 0 obj<</Length " + stream.length + (compressed ? "/Filter/FlateDecode"
                : "") + ">>stream\n").getBytes(StandardCharsets.ISO_8859_1));
        pdf.write(stream);
        pdf.write(("\nendstream\nendobj\n5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\ntrailer"
                + "<</Size 6/Root 1 0 R>>\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1));
        return pdf.toByteArray();
    }

    private static String blueprint(String name) {
        return control().body("{\"blueprintName\":\"" + name + "\",\"type\":\"DOCUMENT\",\"schema\":"
                + MAPPER.valueToTree(SCHEMA) + ",\"tags\":[{\"key\":\"team\",\"value\":\"docs\"}]}")
                .put("/blueprints/").then().statusCode(200).extract().path("blueprint.blueprintArn");
    }

    @Test
    void blueprintsHaveStagesAndVersions() {
        String name = "bp-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = blueprint(name);
        assertTrue(arn.matches("arn:aws:bedrock:us-east-1:" + ACCOUNT + ":blueprint/[a-f0-9]{12}"), arn);
        control().body("{\"blueprintName\":\"" + name + "\",\"type\":\"DOCUMENT\",\"schema\":" + MAPPER.valueToTree(
                SCHEMA) + "}").put("/blueprints/").then().statusCode(409);
        control().body("{\"blueprintName\":\"bad\",\"type\":\"DOCUMENT\",\"schema\":\"{\\\"class\\\":\\\"x\\\","
                + "\\\"properties\\\":{\\\"a\\\":{\\\"type\\\":\\\"string\\\"}}}\"}").put("/blueprints/").then()
                .statusCode(400).body("message", containsString("inferenceType"));
        control().body("{}").post("/blueprints/" + enc(arn) + "/").then().statusCode(200)
                .body("blueprint.blueprintStage", equalTo("LIVE")).body("blueprint.blueprintName", equalTo(name));
        control().body("{}").post("/blueprints/" + enc(arn) + "/versions/").then().statusCode(200)
                .body("blueprint.blueprintVersion", equalTo("1"));
        String updated = SCHEMA.replace("The invoice number", "The number at the top right");
        control().body("{\"schema\":" + MAPPER.valueToTree(updated) + ",\"blueprintStage\":\"DEVELOPMENT\"}")
                .put("/blueprints/" + enc(arn) + "/").then().statusCode(200);
        control().body("{\"blueprintVersion\":\"1\"}").post("/blueprints/" + enc(arn) + "/").then().statusCode(200)
                .body("blueprint.schema", containsString("The invoice number"));
        control().body("{\"sourceStage\":\"DEVELOPMENT\",\"targetStage\":\"LIVE\"}")
                .put("/blueprints/" + enc(arn) + "/copy-stage").then().statusCode(200);
        control().body("{}").post("/blueprints/" + enc(arn) + "/").then().statusCode(200)
                .body("blueprint.schema", containsString("top right"));
        control().body("{\"blueprintArn\":\"" + arn + "\",\"blueprintStageFilter\":\"ALL\"}").post("/blueprints/")
                .then().statusCode(200).body("blueprints.blueprintStage", hasItem("DEVELOPMENT"))
                .body("blueprints.blueprintVersion", hasItem("1"));
        control().body("{\"resourceOwner\":\"SERVICE\"}").post("/blueprints/").then().statusCode(200)
                .body("blueprints.blueprintName", hasItem("Invoice"));
        control().body("{\"resourceARN\":\"" + arn + "\"}").post("/listTagsForResource").then().statusCode(200)
                .body("tags.key", hasItem("team"));
        control().body("{\"resourceARN\":\"" + arn + "\",\"tagKeys\":[\"team\"]}").post("/untagResource").then()
                .statusCode(200);
        control().body("{\"resourceARN\":\"" + arn + "\"}").post("/listTagsForResource").then().statusCode(200)
                .body("tags", hasSize(0));
        control().delete("/blueprints/" + enc(arn) + "/?blueprintVersion=1").then().statusCode(200);
        control().delete("/blueprints/" + enc(arn) + "/").then().statusCode(200);
        control().body("{}").post("/blueprints/" + enc(arn) + "/").then().statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));
        String missing = "arn:aws:bedrock:us-east-1:" + ACCOUNT + ":blueprint/abcdefabcdef";
        control().body("{\"sourceStage\":\"DEVELOPMENT\",\"targetStage\":\"LIVE\"}")
                .put("/blueprints/" + enc(missing) + "/copy-stage").then().statusCode(404);
    }

    @Test
    void anAsyncInvocationReadsThePdfAndFillsTheBlueprint() throws Exception {
        String bucket = "bda-" + UUID.randomUUID().toString().substring(0, 8);
        s3("PUT", "/" + bucket, null);
        s3("PUT", "/" + bucket + "/in/invoice.pdf", pdf(true, "ACME Corp Invoice", "Invoice Number: INV-2026-042",
                "Amount Due: $1,250.00", "Thank you for your business."));
        String blueprint = blueprint("bp-" + UUID.randomUUID().toString().substring(0, 8));
        String project = control().body("{\"projectName\":\"p-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"standardOutputConfiguration\":{\"document\":{\"extraction\":{\"granularity\":{\"types\":"
                + "[\"DOCUMENT\",\"PAGE\",\"LINE\"]},\"boundingBox\":{\"state\":\"DISABLED\"}},\"generativeField\":"
                + "{\"state\":\"DISABLED\"},\"outputFormat\":{\"textFormat\":{\"types\":[\"MARKDOWN\",\"PLAIN_TEXT\"]},"
                + "\"additionalFileFormat\":{\"state\":\"DISABLED\"}}}},\"customOutputConfiguration\":{\"blueprints\":"
                + "[{\"blueprintArn\":\"" + blueprint + "\"}]}}").put("/data-automation-projects/").then()
                .statusCode(200).body("status", equalTo("COMPLETED")).extract().path("projectArn");
        control().body("{}").post("/data-automation-projects/" + enc(project) + "/").then().statusCode(200)
                .body("project.customOutputConfiguration.blueprints[0].blueprintArn", equalTo(blueprint));

        String invocation = runtime("InvokeDataAutomationAsync", "{\"inputConfiguration\":{\"s3Uri\":\"s3://" + bucket
                + "/in/invoice.pdf\"},\"outputConfiguration\":{\"s3Uri\":\"s3://" + bucket + "/out/\"},"
                + "\"dataAutomationConfiguration\":{\"dataAutomationProjectArn\":\"" + project + "\"},"
                + "\"dataAutomationProfileArn\":\"" + PROFILE + "\"}").statusCode(200).extract().path("invocationArn");
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> "Success".equals(runtime(
                "GetDataAutomationStatus", "{\"invocationArn\":\"" + invocation + "\"}").extract().path("status")));
        String metadataUri = runtime("GetDataAutomationStatus", "{\"invocationArn\":\"" + invocation + "\"}")
                .extract().path("outputConfiguration.s3Uri");
        String id = invocation.substring(invocation.lastIndexOf('/') + 1);
        assertEquals("s3://" + bucket + "/out/" + id + "/job_metadata.json", metadataUri);
        JsonNode metadata = MAPPER.readTree(s3("GET", "/" + bucket + "/out/" + id + "/job_metadata.json", null));
        assertEquals("DOCUMENT", metadata.path("semantic_modality").asText());
        assertEquals("MATCH", metadata.path("output_metadata").path(0).path("segment_metadata").path(0)
                .path("custom_output_status").asText());
        JsonNode standard = MAPPER.readTree(s3("GET", "/" + bucket + "/out/" + id
                + "/0/standard_output/0/result.json", null));
        assertTrue(standard.path("document").path("representation").path("text").asText()
                .contains("Invoice Number: INV-2026-042"), standard.toString());
        assertEquals(1, standard.path("pages").size());
        assertTrue(standard.path("text_lines").size() >= 4, standard.toString());
        JsonNode custom = MAPPER.readTree(s3("GET", "/" + bucket + "/out/" + id + "/0/custom_output/0/result.json",
                null));
        assertEquals("INV-2026-042", custom.path("inference_result").path("invoice_number").asText());
        assertEquals(1250.0, custom.path("inference_result").path("total_amount").asDouble());
        assertEquals(blueprint, custom.path("matched_blueprint").path("arn").asText());

        String failed = runtime("InvokeDataAutomationAsync", "{\"inputConfiguration\":{\"s3Uri\":\"s3://" + bucket
                + "/in/missing.pdf\"},\"outputConfiguration\":{\"s3Uri\":\"s3://" + bucket + "/out/\"},"
                + "\"dataAutomationProfileArn\":\"" + PROFILE + "\"}").statusCode(200).extract().path("invocationArn");
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> "ClientError".equals(runtime(
                "GetDataAutomationStatus", "{\"invocationArn\":\"" + failed + "\"}").extract().path("status")));
        runtime("InvokeDataAutomationAsync", "{\"inputConfiguration\":{\"s3Uri\":\"s3://" + bucket + "/in/a.pdf\"},"
                + "\"outputConfiguration\":{\"s3Uri\":\"s3://" + bucket + "/out/\"},\"dataAutomationProfileArn\":"
                + "\"arn:aws:bedrock:us-east-1:" + ACCOUNT + ":data-automation-profile/eu.data-automation-v1\"}")
                .statusCode(400).body("__type", containsString("ValidationException"));
        runtime("GetDataAutomationStatus", "{\"invocationArn\":\"arn:aws:bedrock:us-east-1:" + ACCOUNT
                + ":data-automation-invocation/missing\"}").statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));

        control().body("{\"projectArn\":\"" + project + "\"}").delete("/data-automation-projects/" + enc(project)
                + "/").then().statusCode(200);
    }

    @Test
    void aSyncInvocationAnswersInline() throws Exception {
        String blueprint = blueprint("bp-" + UUID.randomUUID().toString().substring(0, 8));
        String bytes = Base64.getEncoder().encodeToString(pdf(false, "Invoice Number: A-1", "Amount Due: 9.50"));
        String body = runtime("InvokeDataAutomation", "{\"inputConfiguration\":{\"bytes\":\"" + bytes + "\"},"
                + "\"blueprints\":[{\"blueprintArn\":\"" + blueprint + "\"}],\"dataAutomationProfileArn\":\""
                + PROFILE + "\"}").statusCode(200).body("semanticModality", equalTo("DOCUMENT"))
                .body("outputSegments[0].customOutputStatus", equalTo("MATCH")).extract()
                .path("outputSegments[0].customOutput");
        assertEquals("A-1", MAPPER.readTree(body).path("inference_result").path("invoice_number").asText());
        runtime("InvokeDataAutomation", "{\"inputConfiguration\":{},\"dataAutomationProfileArn\":\"" + PROFILE
                + "\"}").statusCode(400).body("__type", containsString("ValidationException"));
    }

    @Test
    void librariesIngestVocabularies() {
        String library = control().body("{\"libraryName\":\"lib-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"libraryDescription\":\"terms\"}").put("/data-automation-libraries/").then().statusCode(200)
                .body("status", equalTo("ACTIVE")).extract().path("libraryArn");
        String bucket = "bdalib-" + UUID.randomUUID().toString().substring(0, 8);
        s3("PUT", "/" + bucket, null);
        String base = "/data-automation-libraries/" + enc(library);
        String job = control().body("{\"entityType\":\"VOCABULARY\",\"operationType\":\"UPSERT\",\"inputConfiguration\":"
                + "{\"inlinePayload\":{\"upsertEntitiesInfo\":[{\"vocabulary\":{\"entityId\":\"brands\",\"language\":"
                + "\"EN\",\"phrases\":[{\"text\":\"Alchemy\"},{\"text\":\"Floci\"}]}}]}},\"outputConfiguration\":"
                + "{\"s3Uri\":\"s3://" + bucket + "/reports/\"}}").put(base + "/library-ingestion-jobs/").then()
                .statusCode(200).extract().path("jobArn");
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> "COMPLETED".equals(control().body("{}")
                .post(base + "/library-ingestion-jobs/" + enc(job)).then().extract().path("job.jobStatus")));
        control().body("{}").post(base + "/library-ingestion-jobs/").then().statusCode(200).body("jobs", hasSize(1));
        control().body("{}").post(base + "/entityType/VOCABULARY/entities/").then().statusCode(200)
                .body("entities[0].vocabulary.numOfPhrases", equalTo(2));
        control().body("{}").post(base + "/entityType/VOCABULARY/entities/brands").then().statusCode(200)
                .body("entity.vocabulary.phrases.text", hasItem("Floci"));
        control().body("{}").post(base + "/entityType/VOCABULARY/entities/nope").then().statusCode(404);
        String report = s3("GET", "/" + bucket + "/reports/" + job.substring(job.lastIndexOf('/') + 1)
                + "/report.json", null);
        assertTrue(report.contains("brands"), report);
        control().body("{\"libraryDescription\":\"renamed\"}").put(base + "/").then().statusCode(200);
        control().body("{}").post(base + "/").then().statusCode(200).body("library.libraryDescription",
                equalTo("renamed"));
        control().delete(base + "/").then().statusCode(200).body("status", equalTo("DELETING"));
        control().body("{}").post(base + "/").then().statusCode(404);
    }

    @Test
    void optimizationRewritesInstructionsFromSamples() throws Exception {
        String bucket = "bdaopt-" + UUID.randomUUID().toString().substring(0, 8);
        s3("PUT", "/" + bucket, null);
        s3("PUT", "/" + bucket + "/samples/1.pdf", pdf(false, "Ref No. 7781", "Balance owed 40"));
        s3("PUT", "/" + bucket + "/samples/1.json", "{\"invoice_number\":\"7781\"}".getBytes(StandardCharsets.UTF_8));
        String blueprint = blueprint("bp-" + UUID.randomUUID().toString().substring(0, 8));
        control().body("{\"blueprint\":{\"blueprintArn\":\"" + blueprint + "\"},\"samples\":[],\"outputConfiguration\":"
                + "{\"s3Object\":{\"s3Uri\":\"s3://" + bucket + "/opt/\"}},\"dataAutomationProfileArn\":\"" + PROFILE
                + "\"}").post("/invokeBlueprintOptimizationAsync").then().statusCode(400);
        String invocation = control().body("{\"blueprint\":{\"blueprintArn\":\"" + blueprint + "\"},\"samples\":[{"
                + "\"assetS3Object\":{\"s3Uri\":\"s3://" + bucket + "/samples/1.pdf\"},\"groundTruthS3Object\":"
                + "{\"s3Uri\":\"s3://" + bucket + "/samples/1.json\"}}],\"outputConfiguration\":{\"s3Object\":"
                + "{\"s3Uri\":\"s3://" + bucket + "/opt/\"}},\"dataAutomationProfileArn\":\"" + PROFILE + "\"}")
                .post("/invokeBlueprintOptimizationAsync").then().statusCode(200).extract().path("invocationArn");
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> "Success".equals(control()
                .post("/getBlueprintOptimizationStatus/" + enc(invocation)).then().extract().path("status")));
        control().body("{}").post("/blueprints/" + enc(blueprint) + "/").then().statusCode(200)
                .body("blueprint.schema", containsString("Ref No."))
                .body("blueprint.optimizationSamples", hasSize(1));
        control().post("/getBlueprintOptimizationStatus/" + enc("arn:aws:bedrock:us-east-1:" + ACCOUNT
                + ":blueprint-optimization-invocation/00000000-0000-0000-0000-000000000000")).then().statusCode(404);
        control().body("{}").post("/blueprints/" + enc(blueprint) + "/").then()
                .body("blueprint.schema", not(containsString("The invoice number")));
    }
}
