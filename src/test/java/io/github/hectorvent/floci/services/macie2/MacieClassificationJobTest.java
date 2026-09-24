package io.github.hectorvent.floci.services.macie2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MacieClassificationJobTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "123456789012";
    private static final String SSN_CSV = "name,ssn\nJane,SSN 123-45-6789\n";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Bucket> buckets = new ArrayList<>();
    private S3Service s3;
    private MacieService service;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Service.class);
        service = newService(Runnable::run);
        service.enableMacie(REGION);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private MacieService newService(java.util.concurrent.Executor jobExecutor) {
        return new MacieService(AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT), s3, jobExecutor);
    }

    @Test
    void oneTimeJobScansObjectsAndReportsSensitiveDataFindings() {
        bucket("hr-data", REGION, text("hr-data", "people.csv", SSN_CSV),
                text("hr-data", "notes.txt", "nothing sensitive here"),
                binary("hr-data", "photo.jpg"));

        ObjectNode request = job("scan", "hr-data");
        request.putObject("tags").put("env", "test");
        ObjectNode created = service.createClassificationJob(REGION, ACCOUNT, request);
        String jobId = created.path("jobId").asText();
        assertEquals(32, jobId.length());
        assertEquals("arn:aws:macie2:" + REGION + ":" + ACCOUNT + ":classification-job/" + jobId,
                created.path("jobArn").asText());

        ObjectNode job = service.describeClassificationJob(REGION, ACCOUNT, jobId);
        assertEquals("COMPLETE", job.path("jobStatus").asText());
        assertEquals("ONE_TIME", job.path("jobType").asText());
        assertEquals("RECOMMENDED", job.path("managedDataIdentifierSelector").asText());
        assertEquals(100, job.path("samplingPercentage").asInt());
        assertEquals("scan-token", job.path("clientToken").asText());
        assertEquals("test", job.path("tags").path("env").asText());
        assertEquals("NONE", job.path("lastRunErrorStatus").path("code").asText());
        assertEquals(1, job.path("statistics").path("numberOfRuns").asInt());
        assertEquals(0, job.path("statistics").path("approximateNumberOfObjectsToProcess").asInt());
        assertEquals("hr-data", job.path("s3JobDefinition").path("bucketDefinitions").get(0)
                .path("buckets").get(0).asText());
        Instant.parse(job.path("lastRunTime").asText());

        List<JsonNode> findings = findings();
        assertEquals(1, findings.size());
        JsonNode finding = findings.get(0);
        assertEquals("SensitiveData:S3Object/Personal", finding.path("type").asText());
        assertEquals("CLASSIFICATION", finding.path("category").asText());
        assertEquals("High", finding.path("severity").path("description").asText());
        assertEquals(3, finding.path("severity").path("score").asInt());
        assertFalse(finding.path("sample").asBoolean());
        JsonNode details = finding.path("classificationDetails");
        assertEquals(jobId, details.path("jobId").asText());
        assertEquals(created.path("jobArn").asText(), details.path("jobArn").asText());
        assertEquals("SENSITIVE_DATA_DISCOVERY_JOB", details.path("originType").asText());
        JsonNode sensitive = details.path("result").path("sensitiveData").get(0);
        assertEquals("PERSONAL_INFORMATION", sensitive.path("category").asText());
        assertEquals(1, sensitive.path("totalCount").asInt());
        JsonNode detection = sensitive.path("detections").get(0);
        assertEquals("USA_SOCIAL_SECURITY_NUMBER", detection.path("type").asText());
        assertEquals(1, detection.path("count").asInt());
        JsonNode range = detection.path("occurrences").path("lineRanges").get(0);
        assertEquals(2, range.path("start").asInt());
        assertEquals(10, range.path("startColumn").asInt());
        assertEquals("COMPLETE", details.path("result").path("status").path("code").asText());
        assertEquals(0, details.path("result").path("customDataIdentifiers").path("totalCount").asInt());
        JsonNode affected = finding.path("resourcesAffected");
        assertEquals("hr-data", affected.path("s3Bucket").path("name").asText());
        assertEquals("arn:aws:s3:::hr-data", affected.path("s3Bucket").path("arn").asText());
        assertEquals("people.csv", affected.path("s3Object").path("key").asText());
        assertEquals("hr-data/people.csv", affected.path("s3Object").path("path").asText());
        assertEquals("csv", affected.path("s3Object").path("extension").asText());
        assertEquals(SSN_CSV.length(), affected.path("s3Object").path("size").asInt());

        ObjectNode statistics = service.findingStatistics(REGION, ACCOUNT,
                mapper.createObjectNode().put("groupBy", "classificationDetails.jobId"));
        assertEquals(jobId, statistics.path("countsByGroup").get(0).path("groupKey").asText());
        assertEquals(1, statistics.path("countsByGroup").get(0).path("count").asInt());
    }

    @Test
    void customIdentifierJobAppliesKeywordsDistanceAndSeverityLevels() throws Exception {
        String identifierId = service.createResource(REGION, ACCOUNT, "custom-data-identifier", mapper.readTree("""
                {"name":"employee-ids","regex":"EMP-[0-9]{4}","keywords":["employee"],"maximumMatchDistance":20,
                 "severityLevels":[{"occurrencesThreshold":1,"severity":"LOW"},
                                   {"occurrencesThreshold":2,"severity":"HIGH"}]}
                """)).path("customDataIdentifierId").asText();
        bucket("staff", REGION, text("staff", "ids.txt",
                "employee EMP-1234, employee EMP-5678\n" + "x".repeat(40) + "\nEMP-9999\n"));

        ObjectNode request = job("custom", "staff");
        request.put("managedDataIdentifierSelector", "NONE");
        request.putArray("customDataIdentifierIds").add(identifierId);
        String jobId = service.createClassificationJob(REGION, ACCOUNT, request).path("jobId").asText();

        assertEquals("COMPLETE", service.describeClassificationJob(REGION, ACCOUNT, jobId).path("jobStatus").asText());
        JsonNode finding = findings().get(0);
        assertEquals("SensitiveData:S3Object/CustomIdentifier", finding.path("type").asText());
        assertEquals("High", finding.path("severity").path("description").asText());
        JsonNode custom = finding.path("classificationDetails").path("result").path("customDataIdentifiers");
        assertEquals(2, custom.path("totalCount").asInt());
        assertEquals("employee-ids", custom.path("detections").get(0).path("name").asText());
        assertTrue(custom.path("detections").get(0).path("arn").asText().endsWith(identifierId));
        assertEquals(0, finding.path("classificationDetails").path("result").path("sensitiveData").size());
    }

    @Test
    void unsupportedObjectsAreSkippedAndMissingBucketsReportAnError() {
        bucket("media", REGION, binary("media", "photo.jpg"));
        bucket("elsewhere", "us-west-2", text("elsewhere", "people.csv", SSN_CSV));

        String jobId = service.createClassificationJob(REGION, ACCOUNT, job("mixed", "media", "ghost", "elsewhere"))
                .path("jobId").asText();

        ObjectNode job = service.describeClassificationJob(REGION, ACCOUNT, jobId);
        assertEquals("COMPLETE", job.path("jobStatus").asText());
        assertEquals("ERROR", job.path("lastRunErrorStatus").path("code").asText());
        assertEquals(0, findings().size());
    }

    @Test
    void samplingPercentageAnalyzesAtMostThatShareOfObjects() {
        bucket("sampled", REGION, text("sampled", "a.csv", SSN_CSV), text("sampled", "b.csv", SSN_CSV),
                text("sampled", "c.csv", SSN_CSV), text("sampled", "d.csv", SSN_CSV));
        ObjectNode request = job("sample", "sampled");
        request.put("samplingPercentage", 50);

        service.createClassificationJob(REGION, ACCOUNT, request);

        assertEquals(2, findings().size());
    }

    @Test
    void bucketCriteriaAndScopingSelectBucketsAndObjects() throws Exception {
        Bucket tagged = bucket("logs-a", REGION, text("logs-a", "x.txt", SSN_CSV), text("logs-a", "y.log", SSN_CSV));
        tagged.getTags().put("team", "sec");
        bucket("logs-b", REGION, text("logs-b", "z.txt", SSN_CSV));
        bucket("data", REGION, text("data", "w.txt", SSN_CSV));

        ObjectNode request = (ObjectNode) mapper.readTree("""
                {"clientToken":"criteria-token","name":"criteria","jobType":"ONE_TIME",
                 "s3JobDefinition":{
                   "bucketCriteria":{"includes":{"and":[
                     {"simpleCriterion":{"key":"S3_BUCKET_NAME","comparator":"STARTS_WITH","values":["logs-"]}},
                     {"tagCriterion":{"comparator":"EQ","tagValues":[{"key":"team","value":"sec"}]}}]}},
                   "scoping":{"excludes":{"and":[
                     {"simpleScopeTerm":{"key":"OBJECT_EXTENSION","comparator":"EQ","values":["log"]}}]}}}}
                """);
        service.createClassificationJob(REGION, ACCOUNT, request);

        List<JsonNode> findings = findings();
        assertEquals(1, findings.size());
        assertEquals("logs-a/x.txt",
                findings.get(0).path("resourcesAffected").path("s3Object").path("path").asText());

        ObjectNode unsupported = (ObjectNode) mapper.readTree("""
                {"clientToken":"permission-token","name":"permission","jobType":"ONE_TIME",
                 "s3JobDefinition":{"bucketCriteria":{"includes":{"and":[
                   {"simpleCriterion":{"key":"S3_BUCKET_EFFECTIVE_PERMISSION","comparator":"EQ","values":["PUBLIC"]}}]}}}}
                """);
        assertError("ValidationException", () -> service.createClassificationJob(REGION, ACCOUNT, unsupported));
    }

    @Test
    void createValidatesTheRequestLikeMacie() {
        assertError("ValidationException", () -> service.createClassificationJob(REGION, ACCOUNT,
                (ObjectNode) job("no-token", "b").without("clientToken")));
        assertError("ValidationException", () -> service.createClassificationJob(REGION, ACCOUNT,
                job("type", "b").put("jobType", "WEEKLY")));
        assertError("ValidationException", () -> service.createClassificationJob(REGION, ACCOUNT,
                (ObjectNode) job("no-definition", "b").without("s3JobDefinition")));
        ObjectNode both = job("both", "b");
        both.withObject("/s3JobDefinition").putObject("bucketCriteria").putObject("includes").putArray("and");
        assertError("ValidationException", () -> service.createClassificationJob(REGION, ACCOUNT, both));
        ObjectNode oneTimeSchedule = job("one-time-schedule", "b");
        oneTimeSchedule.putObject("scheduleFrequency").putObject("dailySchedule");
        assertError("ValidationException", () -> service.createClassificationJob(REGION, ACCOUNT, oneTimeSchedule));
        assertError("ValidationException", () -> service.createClassificationJob(REGION, ACCOUNT,
                job("unscheduled", "b").put("jobType", "SCHEDULED")));
        ObjectNode badDay = job("bad-day", "b").put("jobType", "SCHEDULED");
        badDay.putObject("scheduleFrequency").putObject("monthlySchedule").put("dayOfMonth", 32);
        assertError("ValidationException", () -> service.createClassificationJob(REGION, ACCOUNT, badDay));
        ObjectNode recommendedWithIds = job("recommended-ids", "b");
        recommendedWithIds.putArray("managedDataIdentifierIds").add("EMAIL_ADDRESS");
        assertError("ValidationException", () -> service.createClassificationJob(REGION, ACCOUNT, recommendedWithIds));
        assertError("ValidationException", () -> service.createClassificationJob(REGION, ACCOUNT,
                job("include-no-ids", "b").put("managedDataIdentifierSelector", "INCLUDE")));
        assertError("ValidationException", () -> service.createClassificationJob(REGION, ACCOUNT,
                job("none-no-custom", "b").put("managedDataIdentifierSelector", "NONE")));
        assertError("ValidationException", () -> service.createClassificationJob(REGION, ACCOUNT,
                job("selector", "b").put("managedDataIdentifierSelector", "SOME")));
        assertError("ValidationException", () -> service.createClassificationJob(REGION, ACCOUNT,
                job("sampling", "b").put("samplingPercentage", 0)));
        ObjectNode foreign = job("foreign", "b");
        ((ObjectNode) foreign.path("s3JobDefinition").path("bucketDefinitions").get(0))
                .put("accountId", "999999999999");
        assertError("ValidationException", () -> service.createClassificationJob(REGION, ACCOUNT, foreign));
        ObjectNode missingIdentifier = job("missing-identifier", "b");
        missingIdentifier.putArray("customDataIdentifierIds").add("does-not-exist");
        assertError("ResourceNotFoundException",
                () -> service.createClassificationJob(REGION, ACCOUNT, missingIdentifier));

        MacieService disabled = newService(Runnable::run);
        try {
            assertError("AccessDeniedException", () -> disabled.createClassificationJob(REGION, ACCOUNT, job("x", "b")));
        } finally {
            disabled.shutdown();
        }
    }

    @Test
    void clientTokenMakesCreateIdempotent() {
        ObjectNode request = job("idempotent", "none");
        String first = service.createClassificationJob(REGION, ACCOUNT, request).path("jobId").asText();
        String second = service.createClassificationJob(REGION, ACCOUNT, request.deepCopy()).path("jobId").asText();

        assertEquals(first, second);
        assertEquals(1, service.listClassificationJobs(REGION, ACCOUNT, mapper.createObjectNode())
                .path("items").size());
        assertError("ConflictException", () -> service.createClassificationJob(REGION, ACCOUNT,
                request.deepCopy().put("name", "renamed")));
    }

    @Test
    void updateEnforcesMacieStatusTransitions() {
        String oneTime = service.createClassificationJob(REGION, ACCOUNT, job("done", "none"))
                .path("jobId").asText();
        assertError("ConflictException", () -> update(oneTime, "CANCELLED"));
        assertError("ConflictException", () -> update(oneTime, "USER_PAUSED"));
        assertError("ValidationException", () -> update(oneTime, "COMPLETE"));
        assertError("ValidationException", () -> update(oneTime, "PAUSED"));
        assertError("ResourceNotFoundException", () -> update("0123456789abcdef0123456789abcdef", "CANCELLED"));

        String scheduled = service.createClassificationJob(REGION, ACCOUNT, scheduled("nightly", "none"))
                .path("jobId").asText();
        assertEquals("IDLE", status(scheduled));
        assertError("ConflictException", () -> update(scheduled, "RUNNING"));
        update(scheduled, "USER_PAUSED");
        ObjectNode paused = service.describeClassificationJob(REGION, ACCOUNT, scheduled);
        assertEquals("USER_PAUSED", paused.path("jobStatus").asText());
        Instant pausedAt = Instant.parse(paused.path("userPausedDetails").path("jobPausedAt").asText());
        assertEquals(Duration.ofDays(30), Duration.between(pausedAt,
                Instant.parse(paused.path("userPausedDetails").path("jobExpiresAt").asText())));
        update(scheduled, "RUNNING");
        ObjectNode resumed = service.describeClassificationJob(REGION, ACCOUNT, scheduled);
        assertEquals("IDLE", resumed.path("jobStatus").asText());
        assertFalse(resumed.has("userPausedDetails"));
        update(scheduled, "CANCELLED");
        assertEquals("CANCELLED", status(scheduled));
        assertError("ConflictException", () -> update(scheduled, "CANCELLED"));
        assertError("ConflictException", () -> update(scheduled, "RUNNING"));
    }

    @Test
    void cancellingARunningJobStopsItWithoutFindings() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        useWorkerThread();
        S3Object people = text("slow", "people.csv", SSN_CSV);
        bucket("slow", REGION, people);
        when(s3.getObject("slow", "people.csv")).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return people;
        });

        String jobId = service.createClassificationJob(REGION, ACCOUNT, job("cancel", "slow"))
                .path("jobId").asText();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals("RUNNING", status(jobId));

        update(jobId, "CANCELLED");
        release.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals("CANCELLED", status(jobId));
        assertEquals(0, findings().size());
    }

    @Test
    void pausedJobResumesFromWhereItStopped() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        useWorkerThread();
        S3Object first = text("paused", "a.csv", SSN_CSV);
        bucket("paused", REGION, first, text("paused", "b.csv", SSN_CSV));
        when(s3.getObject("paused", "a.csv")).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return first;
        });

        String jobId = service.createClassificationJob(REGION, ACCOUNT, job("pause", "paused"))
                .path("jobId").asText();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        update(jobId, "USER_PAUSED");
        assertEquals("USER_PAUSED", status(jobId));
        release.countDown();
        update(jobId, "RUNNING");

        for (int i = 0; i < 100 && !"COMPLETE".equals(status(jobId)); i++) {
            Thread.sleep(50);
        }
        ObjectNode job = service.describeClassificationJob(REGION, ACCOUNT, jobId);
        assertEquals("COMPLETE", job.path("jobStatus").asText());
        assertFalse(job.has("userPausedDetails"));
        assertEquals(1, job.path("statistics").path("numberOfRuns").asInt());
        assertEquals(2, findings().size());
    }

    @Test
    void scheduledJobRunsWhenDueAndAnalyzesOnlyChangedObjects() {
        S3Object old = text("daily", "old.csv", SSN_CSV);
        old.setLastModified(Instant.now().minus(Duration.ofHours(1)));
        S3Object fresh = text("daily", "new.csv", SSN_CSV);
        fresh.setLastModified(Instant.now().plus(Duration.ofMinutes(1)));
        bucket("daily", REGION, old, fresh);

        String jobId = service.createClassificationJob(REGION, ACCOUNT, scheduled("daily", "daily"))
                .path("jobId").asText();
        assertEquals("IDLE", status(jobId));
        service.runDueJobs(Instant.now());
        ObjectNode job = service.describeClassificationJob(REGION, ACCOUNT, jobId);
        assertEquals(0, job.path("statistics").path("numberOfRuns").asInt());
        assertEquals(0, findings().size());

        service.runDueJobs(Instant.now().plus(Duration.ofDays(2)));

        job = service.describeClassificationJob(REGION, ACCOUNT, jobId);
        assertEquals("IDLE", job.path("jobStatus").asText());
        assertEquals(1, job.path("statistics").path("numberOfRuns").asInt());
        List<JsonNode> findings = findings();
        assertEquals(1, findings.size());
        assertEquals("new.csv", findings.get(0).path("resourcesAffected").path("s3Object").path("key").asText());
    }

    @Test
    void scheduledJobInitialRunAnalyzesExistingObjects() {
        S3Object old = text("initial", "old.csv", SSN_CSV);
        old.setLastModified(Instant.now().minus(Duration.ofHours(1)));
        bucket("initial", REGION, old);

        ObjectNode request = scheduled("initial", "initial").put("initialRun", true);
        String jobId = service.createClassificationJob(REGION, ACCOUNT, request).path("jobId").asText();

        ObjectNode job = service.describeClassificationJob(REGION, ACCOUNT, jobId);
        assertEquals("IDLE", job.path("jobStatus").asText());
        assertEquals(1, job.path("statistics").path("numberOfRuns").asInt());
        assertEquals(1, findings().size());
    }

    @Test
    void listFiltersSortsAndPaginatesJobSummaries() throws Exception {
        for (String name : List.of("alpha", "beta", "gamma")) {
            service.createClassificationJob(REGION, ACCOUNT, job(name, "none"));
        }
        service.createClassificationJob(REGION, ACCOUNT, scheduled("delta", "none"));

        ObjectNode scheduledOnly = list("""
                {"filterCriteria":{"includes":[{"key":"jobType","comparator":"EQ","values":["SCHEDULED"]}]}}
                """);
        assertEquals(1, scheduledOnly.path("items").size());
        JsonNode summary = scheduledOnly.path("items").get(0);
        assertEquals("delta", summary.path("name").asText());
        assertEquals("IDLE", summary.path("jobStatus").asText());
        assertTrue(summary.has("bucketDefinitions"));
        assertFalse(summary.has("s3JobDefinition"));

        ObjectNode excluded = list("""
                {"filterCriteria":{"excludes":[{"key":"name","comparator":"STARTS_WITH","values":["a","b"]}]},
                 "sortCriteria":{"attributeName":"name","orderBy":"DESC"}}
                """);
        assertEquals(List.of("gamma", "delta"), names(excluded));

        ObjectNode page = list("""
                {"sortCriteria":{"attributeName":"name"},"maxResults":2}
                """);
        assertEquals(List.of("alpha", "beta"), names(page));
        ObjectNode next = list("{\"sortCriteria\":{\"attributeName\":\"name\"},\"nextToken\":\""
                + page.path("nextToken").asText() + "\"}");
        assertEquals(List.of("delta", "gamma"), names(next));

        assertError("ValidationException", () -> list("""
                {"filterCriteria":{"includes":[{"key":"jobType","comparator":"CONTAINS","values":["S"]}]}}
                """));
        assertError("ValidationException", () -> list("""
                {"filterCriteria":{"includes":[{"key":"bucket","comparator":"EQ","values":["x"]}]}}
                """));
        assertError("ValidationException", () -> list("""
                {"sortCriteria":{"attributeName":"lastRunTime"}}
                """));
    }

    @Test
    void jobsAreTaggableResources() {
        String jobArn = service.createClassificationJob(REGION, ACCOUNT, job("tagged", "none"))
                .path("jobArn").asText();

        service.changeTags(REGION, ACCOUNT, jobArn, Map.of("owner", "team"), List.of());

        assertEquals(Map.of("owner", "team"), service.resourceTags(REGION, ACCOUNT, jobArn));
    }

    @Test
    void disablingMacieDeletesJobs() {
        String jobId = service.createClassificationJob(REGION, ACCOUNT, job("gone", "none")).path("jobId").asText();

        service.disableMacie(REGION, ACCOUNT);

        assertError("AccessDeniedException", () -> service.describeClassificationJob(REGION, ACCOUNT, jobId));
        service.enableMacie(REGION);
        assertError("ResourceNotFoundException", () -> service.describeClassificationJob(REGION, ACCOUNT, jobId));
    }

    @Test
    void schedulesStartAtMidnightUtcAndSkipMonthsWithoutTheDay() throws Exception {
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), MacieClassificationJobs.nextRun(
                mapper.readTree("{\"dailySchedule\":{}}"), Instant.parse("2026-01-31T10:00:00Z")));
        assertEquals(Instant.parse("2026-09-28T00:00:00Z"), MacieClassificationJobs.nextRun(
                mapper.readTree("{\"weeklySchedule\":{\"dayOfWeek\":\"MONDAY\"}}"),
                Instant.parse("2026-09-23T08:00:00Z")));
        assertEquals(Instant.parse("2026-03-31T00:00:00Z"), MacieClassificationJobs.nextRun(
                mapper.readTree("{\"monthlySchedule\":{\"dayOfMonth\":31}}"),
                Instant.parse("2026-01-31T05:00:00Z")));
    }

    @Test
    void onlyUtf8TextIsDecodedForClassification() {
        assertNull(MacieClassificationJobs.decodeText(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}));
        assertNull(MacieClassificationJobs.decodeText("a\u0000b".getBytes(StandardCharsets.UTF_8)));
        assertEquals("hello", MacieClassificationJobs.decodeText("\uFEFFhello".getBytes(StandardCharsets.UTF_8)));
    }

    private void useWorkerThread() {
        service.shutdown();
        executor = Executors.newSingleThreadExecutor();
        service = newService(executor);
        service.enableMacie(REGION);
    }

    private Bucket bucket(String name, String region, S3Object... objects) {
        Bucket bucket = new Bucket(name);
        bucket.setRegion(region);
        buckets.add(bucket);
        when(s3.listBuckets()).thenReturn(List.copyOf(buckets));
        when(s3.listObjects(name, null, null, Integer.MAX_VALUE)).thenReturn(List.of(objects));
        for (S3Object object : objects) {
            when(s3.getObject(name, object.getKey())).thenReturn(object);
        }
        return bucket;
    }

    private static S3Object text(String bucket, String key, String content) {
        return new S3Object(bucket, key, content.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static S3Object binary(String bucket, String key) {
        return new S3Object(bucket, key, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x10}, "image/jpeg");
    }

    private ObjectNode job(String name, String... bucketNames) {
        ObjectNode request = mapper.createObjectNode();
        request.put("clientToken", name + "-token");
        request.put("name", name);
        request.put("jobType", "ONE_TIME");
        ObjectNode definition = request.putObject("s3JobDefinition").putArray("bucketDefinitions").addObject();
        definition.put("accountId", ACCOUNT);
        ArrayNode names = definition.putArray("buckets");
        for (String bucketName : bucketNames) {
            names.add(bucketName);
        }
        return request;
    }

    private ObjectNode scheduled(String name, String... bucketNames) {
        ObjectNode request = job(name, bucketNames).put("jobType", "SCHEDULED");
        request.putObject("scheduleFrequency").putObject("dailySchedule");
        return request;
    }

    private void update(String jobId, String status) {
        service.updateClassificationJob(REGION, ACCOUNT, jobId, mapper.createObjectNode().put("jobStatus", status));
    }

    private String status(String jobId) {
        return service.describeClassificationJob(REGION, ACCOUNT, jobId).path("jobStatus").asText();
    }

    private ObjectNode list(String body) throws Exception {
        return service.listClassificationJobs(REGION, ACCOUNT, mapper.readTree(body));
    }

    private static List<String> names(ObjectNode page) {
        List<String> names = new ArrayList<>();
        page.path("items").forEach(item -> names.add(item.path("name").asText()));
        return names;
    }

    private List<JsonNode> findings() {
        List<JsonNode> ids = new ArrayList<>();
        service.listFindings(REGION, ACCOUNT, mapper.createObjectNode()).path("findingIds").forEach(ids::add);
        if (ids.isEmpty()) {
            return List.of();
        }
        ObjectNode request = mapper.createObjectNode();
        ArrayNode findingIds = request.putArray("findingIds");
        ids.forEach(findingIds::add);
        List<JsonNode> findings = new ArrayList<>();
        service.getFindings(REGION, ACCOUNT, request).path("findings").forEach(findings::add);
        findings.forEach(finding -> assertNotNull(finding.path("id").asText(null)));
        return findings;
    }

    private static void assertError(String code, Executable action) {
        AwsException error = assertThrows(AwsException.class, action);
        assertEquals(code, error.getErrorCode(), error.getMessage());
    }
}
