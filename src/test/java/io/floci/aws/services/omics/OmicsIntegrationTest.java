package io.floci.aws.services.omics;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HealthOmics without a workflow engine ({@code engine: none}): references imported and indexed from FASTA, read sets
 * imported, uploaded in parts, exported and deleted, annotation and variant stores filled by import jobs, shares,
 * workflows and their versions, run groups, caches, configurations, and runs and batches. Runs execute in
 * {@link OmicsDockerIntegrationTest}.
 */
@QuarkusTest
class OmicsIntegrationTest {

    static final String FASTA = ">chr1 first\nACGTACGTAC\nGTACGT\n>chr2\nTTTTGGGG\n";
    static final String FASTQ = "@read1\nACGTAC\n+\nIIIIII\n@read2\nGGTT\n+\nIIII\n";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification omics(String account) {
        return given().header("Authorization", auth(account, "omics")).contentType("application/json");
    }

    static RequestSpecification omics() {
        return omics("000000000000");
    }

    static void put(String bucket, String key, byte[] body) {
        given().header("Authorization", auth("000000000000", "s3")).put("/" + bucket).then().statusCode(anyOf(equalTo(200),
                equalTo(409)));
        given().header("Authorization", auth("000000000000", "s3")).contentType("application/octet-stream")
                .body(body).put("/" + bucket + "/" + key).then().statusCode(200);
    }

    static String role(String name) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"omics.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        return given().header("Authorization", auth("000000000000", "iam")).formParam("Action", "CreateRole")
                .formParam("RoleName", name).formParam("AssumeRolePolicyDocument", trust).post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    static String zip(String name, String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    static String hex(String algorithm, byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
    }

    /** Imports the FASTA into a new reference store; returns {storeId, referenceId, referenceArn}. */
    static String[] reference(String name) {
        put("omics-inputs", name + ".fa", FASTA.getBytes(StandardCharsets.UTF_8));
        String role = role("omics-" + name);
        String store = omics().body("{\"name\":\"" + name + "\",\"tags\":{\"team\":\"genomics\"}}")
                .post("/referencestore").then().statusCode(200).extract().path("id");
        String job = omics().body("{\"roleArn\":\"" + role + "\",\"sources\":[{\"sourceFile\":"
                        + "\"s3://omics-inputs/" + name + ".fa\",\"name\":\"" + name + "\"}]}")
                .post("/referencestore/" + store + "/importjob").then().statusCode(200)
                .body("status", equalTo("SUBMITTED")).extract().path("id");
        await().atMost(Duration.ofSeconds(20)).until(() -> "COMPLETED".equals(omics()
                .get("/referencestore/" + store + "/importjob/" + job).then().extract().path("status")));
        String id = omics().get("/referencestore/" + store + "/importjob/" + job).then().statusCode(200)
                .extract().path("sources[0].referenceId");
        String arn = omics().get("/referencestore/" + store + "/reference/" + id + "/metadata").then()
                .statusCode(200).extract().path("arn");
        return new String[] {store, id, arn};
    }

    @Test
    void referencesAreImportedAndIndexed() throws Exception {
        String[] reference = reference("hg-mini");
        String store = reference[0];
        String id = reference[1];
        assertEquals(10, store.length());
        omics().get("/referencestore/" + store + "/reference/" + id + "/metadata").then().statusCode(200)
                .body("status", equalTo("ACTIVE")).body("name", equalTo("hg-mini"))
                .body("md5", equalTo(hex("MD5", FASTA.getBytes(StandardCharsets.UTF_8))))
                .body("files.source.contentLength", equalTo(FASTA.length()))
                .body("files.source.totalParts", equalTo(1)).body("creationType", equalTo("IMPORT"))
                .body("arn", containsString(":referenceStore/" + store + "/reference/" + id));
        String index = new String(omics().queryParam("partNumber", 1).queryParam("file", "INDEX")
                .get("/referencestore/" + store + "/reference/" + id).then().statusCode(200).extract().asByteArray(),
                StandardCharsets.UTF_8);
        assertEquals("chr1\t16\t12\t10\t11\nchr2\t8\t36\t8\t9\n", index);
        byte[] part = omics().queryParam("partNumber", 1).header("Range", "0-4")
                .get("/referencestore/" + store + "/reference/" + id).then().statusCode(200).extract().asByteArray();
        assertEquals(">chr1", new String(part, StandardCharsets.UTF_8));
        omics().queryParam("partNumber", 2).get("/referencestore/" + store + "/reference/" + id).then()
                .statusCode(416);
        omics().body("{\"filter\":{\"name\":\"hg-mini\"}}").post("/referencestore/" + store + "/references").then()
                .statusCode(200).body("references", hasSize(1)).body("references[0].files", equalTo(null));
        omics().body("{}").post("/referencestores").then().statusCode(200)
                .body("referenceStores.id", hasItem(store));
        omics().get("/tags/arn:aws:omics:us-east-1:000000000000:referenceStore/" + store).then().statusCode(200)
                .body("tags.team", equalTo("genomics"));

        put("omics-inputs", "bad.fa", "not fasta".getBytes(StandardCharsets.UTF_8));
        String role = role("omics-bad-fasta");
        String job = omics().body("{\"roleArn\":\"" + role + "\",\"sources\":[{\"sourceFile\":"
                + "\"s3://omics-inputs/bad.fa\",\"name\":\"bad\"}]}").post("/referencestore/" + store + "/importjob")
                .then().statusCode(200).extract().path("id");
        await().atMost(Duration.ofSeconds(20)).until(() -> "FAILED".equals(omics()
                .get("/referencestore/" + store + "/importjob/" + job).then().extract().path("status")));
        omics().get("/referencestore/" + store + "/importjob/" + job).then()
                .body("sources[0].statusMessage", containsString("isn't FASTA"));
        omics().body("{\"roleArn\":\"arn:aws:iam::000000000000:role/absent\",\"sources\":[{\"sourceFile\":"
                + "\"s3://omics-inputs/hg-mini.fa\",\"name\":\"x\"}]}").post("/referencestore/" + store + "/importjob")
                .then().statusCode(400);

        omics().delete("/referencestore/" + store).then().statusCode(409);
        omics().delete("/referencestore/" + store + "/reference/" + id).then().statusCode(200);
        omics().delete("/referencestore/" + store).then().statusCode(200);
        omics().get("/referencestore/" + store).then().statusCode(404);
    }

    @Test
    void readSetsAreImportedUploadedExportedAndDeleted() throws Exception {
        String store = omics().body("{\"name\":\"reads\",\"eTagAlgorithmFamily\":\"SHA256up\"}")
                .post("/sequencestore").then().statusCode(200).body("status", equalTo("ACTIVE"))
                .body("eTagAlgorithmFamily", equalTo("SHA256up")).extract().path("id");
        byte[] gz = gzip(FASTQ);
        put("omics-inputs", "sample.fastq.gz", gz);
        String role = role("omics-reads");
        String job = omics().body("{\"roleArn\":\"" + role + "\",\"sources\":[{\"sourceFiles\":{\"source1\":"
                        + "\"s3://omics-inputs/sample.fastq.gz\"},\"sourceFileType\":\"FASTQ\",\"subjectId\":\"s1\","
                        + "\"sampleId\":\"sample-1\",\"name\":\"imported\",\"tags\":{\"run\":\"1\"}}]}")
                .post("/sequencestore/" + store + "/importjob").then().statusCode(200).extract().path("id");
        await().atMost(Duration.ofSeconds(20)).until(() -> "COMPLETED".equals(omics()
                .get("/sequencestore/" + store + "/importjob/" + job).then().extract().path("status")));
        String imported = omics().get("/sequencestore/" + store + "/importjob/" + job).then()
                .extract().path("sources[0].readSetId");
        omics().get("/sequencestore/" + store + "/readset/" + imported + "/metadata").then().statusCode(200)
                .body("status", equalTo("ACTIVE")).body("sequenceInformation.totalReadCount", equalTo(2))
                .body("sequenceInformation.totalBaseCount", equalTo(10))
                .body("etag.algorithm", equalTo("FASTQ_SHA256up")).body("etag.source1", equalTo(hex("SHA-256", gz)))
                .body("files.source1.contentLength", equalTo(gz.length)).body("creationType", equalTo("IMPORT"));
        byte[] read = omics().queryParam("partNumber", 1).get("/sequencestore/" + store + "/readset/" + imported)
                .then().statusCode(200).extract().asByteArray();
        assertTrue(Arrays.equals(gz, read));

        String upload = omics().body("{\"sourceFileType\":\"FASTQ\",\"subjectId\":\"s2\",\"sampleId\":\"sample-2\","
                        + "\"name\":\"uploaded\"}").post("/sequencestore/" + store + "/upload").then().statusCode(200)
                .extract().path("uploadId");
        byte[] first = "@r1\nACGT\n+\nIIII\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = "@r2\nGGCCAA\n+\nIIIIII\n".getBytes(StandardCharsets.UTF_8);
        String c1 = omics().contentType("application/octet-stream").queryParam("partSource", "SOURCE1")
                .queryParam("partNumber", 1).body(first).put("/sequencestore/" + store + "/upload/" + upload + "/part")
                .then().statusCode(200).extract().path("checksum");
        String c2 = omics().contentType("application/octet-stream").queryParam("partSource", "SOURCE1")
                .queryParam("partNumber", 2).body(second).put("/sequencestore/" + store + "/upload/" + upload
                        + "/part").then().statusCode(200).extract().path("checksum");
        omics().body("{\"partSource\":\"SOURCE1\"}").post("/sequencestore/" + store + "/upload/" + upload + "/parts")
                .then().statusCode(200).body("parts", hasSize(2)).body("parts[1].partSize", equalTo(second.length));
        omics().body("{\"parts\":[{\"partNumber\":1,\"partSource\":\"SOURCE1\",\"checksum\":\"" + c2 + "\"}]}")
                .post("/sequencestore/" + store + "/upload/" + upload + "/complete").then().statusCode(400);
        String uploaded = omics().body("{\"parts\":[{\"partNumber\":1,\"partSource\":\"SOURCE1\",\"checksum\":\"" + c1
                        + "\"},{\"partNumber\":2,\"partSource\":\"SOURCE1\",\"checksum\":\"" + c2 + "\"}]}")
                .post("/sequencestore/" + store + "/upload/" + upload + "/complete").then().statusCode(200)
                .extract().path("readSetId");
        omics().get("/sequencestore/" + store + "/readset/" + uploaded + "/metadata").then().statusCode(200)
                .body("creationType", equalTo("UPLOAD")).body("sequenceInformation.totalReadCount", equalTo(2))
                .body("sequenceInformation.totalBaseCount", equalTo(10));
        omics().body("{}").post("/sequencestore/" + store + "/uploads").then().statusCode(200)
                .body("uploads", empty());
        omics().body("{\"filter\":{\"creationType\":\"UPLOAD\"}}").post("/sequencestore/" + store + "/readsets")
                .then().statusCode(200).body("readSets", hasSize(1)).body("readSets[0].id", equalTo(uploaded));

        given().header("Authorization", auth("000000000000", "s3")).put("/omics-exports");
        String export = omics().body("{\"destination\":\"s3://omics-exports/run1/\",\"roleArn\":\"" + role
                + "\",\"sources\":[{\"readSetId\":\"" + uploaded + "\"},{\"readSetId\":\"" + imported + "\"}]}")
                .post("/sequencestore/" + store + "/exportjob").then().statusCode(200).extract().path("id");
        await().atMost(Duration.ofSeconds(20)).until(() -> List.of("COMPLETED", "COMPLETED_WITH_FAILURES", "FAILED")
                .contains(omics().get("/sequencestore/" + store + "/exportjob/" + export).then().extract()
                        .<String>path("status")));
        omics().get("/sequencestore/" + store + "/exportjob/" + export).then().body("status", equalTo("COMPLETED"));
        String exported = given().header("Authorization", auth("000000000000", "s3"))
                .get("/omics-exports/run1/" + uploaded + "/" + uploaded + "_1.fastq").then().statusCode(200)
                .extract().asString();
        assertEquals(new String(first, StandardCharsets.UTF_8) + new String(second, StandardCharsets.UTF_8),
                exported);

        String activation = omics().body("{\"sources\":[{\"readSetId\":\"" + imported + "\"}]}")
                .post("/sequencestore/" + store + "/activationjob").then().statusCode(200).extract().path("id");
        await().atMost(Duration.ofSeconds(20)).until(() -> "COMPLETED".equals(omics()
                .get("/sequencestore/" + store + "/activationjob/" + activation).then().extract().path("status")));

        String accessPoint = omics().get("/sequencestore/" + store).then().statusCode(200)
                .extract().path("s3Access.s3AccessPointArn");
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":"
                + "\"arn:aws:iam::000000000000:root\"},\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";
        omics().body("{\"s3AccessPolicy\":\"" + policy.replace("\"", "\\\"") + "\"}")
                .put("/s3accesspolicy/" + accessPoint).then().statusCode(200).body("storeId", equalTo(store));
        omics().get("/s3accesspolicy/" + accessPoint).then().statusCode(200).body("storeType",
                equalTo("SEQUENCE_STORE")).body("s3AccessPolicy", containsString("s3:GetObject"));
        omics().delete("/s3accesspolicy/" + accessPoint).then().statusCode(200);
        omics().get("/s3accesspolicy/" + accessPoint).then().statusCode(404);

        omics().delete("/sequencestore/" + store).then().statusCode(409);
        omics().body("{\"ids\":[\"" + imported + "\",\"" + uploaded + "\",\"9999999999\"]}")
                .post("/sequencestore/" + store + "/readset/batch/delete").then().statusCode(200)
                .body("errors", hasSize(1)).body("errors[0].id", equalTo("9999999999"));
        omics().delete("/sequencestore/" + store).then().statusCode(200);
    }

    @Test
    void analyticsStoresImportRecordsAndShare() {
        String[] reference = reference("variants-ref");
        String role = role("omics-analytics");
        omics().body("{\"name\":\"bad_variants\",\"reference\":{\"referenceArn\":\"arn:aws:omics:us-east-1:"
                + "000000000000:referenceStore/1111111111/reference/2222222222\"}}").post("/variantStore").then()
                .statusCode(400);
        omics().body("{\"name\":\"variants\",\"reference\":{\"referenceArn\":\"" + reference[2] + "\"}}")
                .post("/variantStore").then().statusCode(200).body("status", equalTo("CREATING"));
        await().atMost(Duration.ofSeconds(20)).until(() -> "ACTIVE".equals(omics().get("/variantStore/variants")
                .then().extract().path("status")));
        String vcf = "##fileformat=VCFv4.2\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                + "chr1\t3\t.\tG\tA\t50\tPASS\t.\nchr2\t5\t.\tG\tT\t40\tPASS\t.\n";
        put("omics-inputs", "calls.vcf", vcf.getBytes(StandardCharsets.UTF_8));
        String job = omics().body("{\"destinationName\":\"variants\",\"roleArn\":\"" + role + "\",\"items\":[{"
                + "\"source\":\"s3://omics-inputs/calls.vcf\"}]}").post("/import/variant").then().statusCode(200)
                .extract().path("jobId");
        await().atMost(Duration.ofSeconds(20)).until(() -> "COMPLETED".equals(omics().get("/import/variant/" + job)
                .then().extract().path("status")));
        omics().get("/import/variant/" + job).then().body("statusMessage", equalTo("2 records imported."));
        omics().get("/variantStore/variants").then().body("storeSizeBytes", equalTo(vcf.length()));
        omics().delete("/variantStore/variants").then().statusCode(409);

        omics().body("{\"name\":\"genes\",\"storeFormat\":\"TSV\",\"storeOptions\":{\"tsvStoreOptions\":"
                + "{\"annotationType\":\"GENERIC\"}}}").post("/annotationStore").then().statusCode(200)
                .body("versionName", equalTo("default"));
        await().atMost(Duration.ofSeconds(20)).until(() -> "ACTIVE".equals(omics().get("/annotationStore/genes")
                .then().extract().path("status")));
        omics().body("{\"versionName\":\"v_two\"}").post("/annotationStore/genes/version").then().statusCode(200);
        omics().get("/annotationStore/genes").then().body("numVersions", equalTo(2)).body("storeFormat",
                equalTo("TSV"));
        omics().body("{}").post("/annotationStore/genes/versions").then().statusCode(200)
                .body("annotationStoreVersions.versionName", hasItem("v_two"));
        omics().body("{\"versions\":[\"v_two\",\"nope\"]}").post("/annotationStore/genes/versions/delete").then()
                .statusCode(200).body("errors", hasSize(1));

        String storeArn = omics().get("/variantStore/variants").then().extract().path("storeArn");
        String share = omics().body("{\"resourceArn\":\"" + storeArn + "\",\"principalSubscriber\":\"222233334444\","
                + "\"shareName\":\"to-partner\"}").post("/share").then().statusCode(200)
                .body("status", equalTo("PENDING")).extract().path("shareId");
        omics().post("/share/" + share).then().statusCode(403);
        omics("222233334444").post("/share/" + share).then().statusCode(200).body("status", equalTo("ACTIVE"));
        omics().body("{\"resourceOwner\":\"SELF\"}").post("/shares").then().statusCode(200)
                .body("shares.shareId", hasItem(share));
        omics("222233334444").body("{\"resourceOwner\":\"OTHER\"}").post("/shares").then().statusCode(200)
                .body("shares.shareId", hasItem(share));
        omics().delete("/share/" + share).then().statusCode(200).body("status", equalTo("DELETED"));

        omics().queryParam("force", true).delete("/variantStore/variants").then().statusCode(200);
        omics().delete("/annotationStore/genes").then().statusCode(200);
        await().atMost(Duration.ofSeconds(20)).until(() -> omics().get("/variantStore/variants").then().extract()
                .statusCode() == 404);
    }

    static final String WDL = "version 1.0\nworkflow Greet {\n  input {\n    String who\n    Int times = 1\n"
            + "    File? notes\n  }\n  call Hello { input: who = who }\n  output { String greeting = Hello.out }\n}\n"
            + "task Hello {\n  input { String who }\n  command { echo \"hello ~{who}\" }\n"
            + "  output { String out = read_string(stdout()) }\n}\n";

    @Test
    void workflowsRunGroupsAndRuns() throws Exception {
        String workflow = omics().body("{\"name\":\"greet\",\"engine\":\"WDL\",\"definitionZip\":\""
                        + zip("main.wdl", WDL) + "\",\"requestId\":\"wf-1\",\"tags\":{\"k\":\"v\"}}")
                .post("/workflow").then().statusCode(201).body("status", equalTo("CREATING")).extract().path("id");
        await().atMost(Duration.ofSeconds(20)).until(() -> "ACTIVE".equals(omics().get("/workflow/" + workflow)
                .then().extract().path("status")));
        omics().get("/workflow/" + workflow).then().statusCode(200).body("main", equalTo("main.wdl"))
                .body("parameterTemplate.who.optional", equalTo(false))
                .body("parameterTemplate.times.optional", equalTo(true))
                .body("parameterTemplate.notes.optional", equalTo(true))
                .body("digest", containsString("sha256:")).body("tags.k", equalTo("v"));
        omics().body("{\"definitionZip\":\"" + zip("readme.txt", "x") + "\",\"requestId\":\"wf-2\"}")
                .post("/workflow").then().statusCode(400);
        omics().body("{\"versionName\":\"v2\",\"definitionZip\":\"" + zip("main.wdl", WDL) + "\",\"requestId\":"
                + "\"wf-v2\"}").post("/workflow/" + workflow + "/version").then().statusCode(201);
        await().atMost(Duration.ofSeconds(20)).until(() -> "ACTIVE".equals(omics().get("/workflow/" + workflow
                + "/version/v2").then().extract().path("status")));
        omics().get("/workflow/" + workflow + "/version").then().statusCode(200).body("items.versionName",
                hasItem("v2"));
        omics().get("/workflow").then().statusCode(200).body("items.id", hasItem(workflow));

        String group = omics().body("{\"name\":\"batch\",\"maxCpus\":4,\"maxRuns\":1,\"requestId\":\"rg-1\"}")
                .post("/runGroup").then().statusCode(201).extract().path("id");
        omics().body("{\"maxCpus\":8}").post("/runGroup/" + group).then().statusCode(202);
        omics().get("/runGroup/" + group).then().statusCode(200).body("maxCpus", equalTo(8)).body("maxRuns",
                equalTo(1));
        String cache = omics().body("{\"cacheS3Location\":\"s3://omics-cache/\",\"requestId\":\"rc-1\"}")
                .post("/runCache").then().statusCode(201).body("status", equalTo("ACTIVE")).extract().path("id");
        omics().get("/runCache/" + cache).then().statusCode(200).body("cacheBehavior", equalTo("CACHE_ON_FAILURE"));

        String role = role("omics-runs");
        omics().body("{\"workflowId\":\"" + workflow + "\",\"roleArn\":\"" + role + "\",\"outputUri\":"
                + "\"s3://omics-out/\",\"requestId\":\"run-0\"}").post("/run").then().statusCode(400)
                .body("message", containsString("who"));
        String run = omics().body("{\"workflowId\":\"" + workflow + "\",\"roleArn\":\"" + role + "\",\"outputUri\":"
                        + "\"s3://omics-out/\",\"requestId\":\"run-1\",\"runGroupId\":\"" + group + "\",\"name\":"
                        + "\"first\",\"parameters\":{\"who\":\"floci\"}}").post("/run").then().statusCode(201)
                .body("status", equalTo("PENDING")).extract().path("id");
        await().atMost(Duration.ofSeconds(20)).until(() -> "FAILED".equals(omics().get("/run/" + run).then()
                .extract().path("status")));
        omics().get("/run/" + run).then().statusCode(200).body("failureReason", equalTo("ENGINE_DISABLED"))
                .body("runOutputUri", equalTo("s3://omics-out/" + run)).body("runGroupId", equalTo(group))
                .body("logLocation.runLogStream", equalTo("run/" + run));
        omics().queryParam("runGroupId", group).get("/run").then().statusCode(200).body("items.id", hasItem(run));
        omics().get("/run/" + run + "/task").then().statusCode(200).body("items", empty());
        omics().post("/run/" + run + "/cancel").then().statusCode(409);

        String batch = omics().body("{\"batchName\":\"b\",\"requestId\":\"batch-1\",\"defaultRunSetting\":{"
                + "\"workflowId\":\"" + workflow + "\",\"roleArn\":\"" + role + "\",\"outputUri\":\"s3://omics-out/\","
                + "\"parameters\":{\"who\":\"a\"}},\"batchRunSettings\":{\"inlineSettings\":[{\"runSettingId\":"
                + "\"one\"},{\"runSettingId\":\"two\",\"parameters\":{\"who\":\"b\"}}]}}").post("/runBatch").then()
                .statusCode(201).extract().path("id");
        await().atMost(Duration.ofSeconds(30)).until(() -> "PROCESSED".equals(omics().get("/runBatch/" + batch)
                .then().extract().path("status")));
        omics().get("/runBatch/" + batch).then().body("totalRuns", equalTo(2))
                .body("runSummary.failedRunCount", equalTo(2))
                .body("submissionSummary.successfulStartSubmissionCount", equalTo(2));
        omics().get("/runBatch/" + batch + "/run").then().statusCode(200).body("runs.runSettingId", hasItem("two"));
        omics().body("{\"batchId\":\"" + batch + "\"}").post("/runBatch/delete").then().statusCode(202);
        omics().delete("/runBatch/" + batch).then().statusCode(202);

        omics().delete("/run/" + run).then().statusCode(202);
        omics().get("/run/" + run).then().statusCode(404);
        omics().delete("/runCache/" + cache).then().statusCode(202);
        omics().delete("/runGroup/" + group).then().statusCode(202);
        omics().delete("/workflow/" + workflow + "/version/v2").then().statusCode(202);
        omics().delete("/workflow/" + workflow).then().statusCode(202);
        omics().get("/workflow/" + workflow).then().statusCode(404);
        omics().get("/workflow").then().statusCode(200).body("items.id", not(hasItem(workflow)));
    }
}
