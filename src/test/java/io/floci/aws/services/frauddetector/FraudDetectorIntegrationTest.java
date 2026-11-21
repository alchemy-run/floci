package io.floci.aws.services.frauddetector;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fraud Detector end to end: a rule-based detector built from entity types, variables, labels, outcomes, a list and
 * an event type; predictions that evaluate its rules; stored events; a model trained on labeled events whose score
 * a rule reads; batch predictions from S3; and the conflicts that keep resources in use from being deleted.
 */
@QuarkusTest
class FraudDetectorIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/frauddetector/aws4_request";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static ValidatableResponse call(String action, String body) {
        return given().header("Authorization", AUTH).header("X-Amz-Target", "AWSHawksNestServiceFacade." + action)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then();
    }

    static ValidatableResponse ok(String action, String body) {
        return call(action, body).statusCode(200);
    }

    static void error(String action, String body, String type) {
        call(action, body).statusCode(greaterThan(399)).body("__type", containsString(type));
    }

    /** The event type, detector and active version the prediction tests share, named after {@code p}. */
    static void detector(String p) {
        ok("PutEntityType", "{\"name\":\"" + p + "_customer\"}");
        ok("CreateVariable", "{\"name\":\"" + p + "_email\",\"dataType\":\"STRING\",\"dataSource\":\"EVENT\","
                + "\"defaultValue\":\"unknown\",\"variableType\":\"EMAIL_ADDRESS\"}");
        ok("CreateVariable", "{\"name\":\"" + p + "_ip\",\"dataType\":\"STRING\",\"dataSource\":\"EVENT\","
                + "\"defaultValue\":\"0.0.0.0\",\"variableType\":\"IP_ADDRESS\"}");
        ok("CreateVariable", "{\"name\":\"" + p + "_amount\",\"dataType\":\"FLOAT\",\"dataSource\":\"EVENT\","
                + "\"defaultValue\":\"0.0\",\"variableType\":\"NUMERIC\"}");
        ok("PutOutcome", "{\"name\":\"" + p + "_review\"}");
        ok("PutOutcome", "{\"name\":\"" + p + "_block\"}");
        ok("PutLabel", "{\"name\":\"" + p + "_fraud\"}");
        ok("PutLabel", "{\"name\":\"" + p + "_legit\"}");
        ok("PutEventType", "{\"name\":\"" + p + "_purchase\",\"eventVariables\":[\"" + p + "_email\",\"" + p
                + "_ip\",\"" + p + "_amount\"],\"entityTypes\":[\"" + p + "_customer\"],\"labels\":[\"" + p
                + "_fraud\",\"" + p + "_legit\"],\"eventIngestion\":\"ENABLED\"}");
        ok("PutDetector", "{\"detectorId\":\"" + p + "_checkout\",\"eventTypeName\":\"" + p + "_purchase\"}");
        ok("CreateList", "{\"name\":\"" + p + "_blocked_ips\",\"variableType\":\"IP_ADDRESS\","
                + "\"elements\":[\"203.0.113.7\"]}");
    }

    static String prediction(String p, String eventId, String email, String ip, String amount) {
        return "{\"detectorId\":\"" + p + "_checkout\",\"eventId\":\"" + eventId + "\",\"eventTypeName\":\"" + p
                + "_purchase\",\"eventTimestamp\":\"" + Instant.now() + "\",\"entities\":[{\"entityType\":\"" + p
                + "_customer\",\"entityId\":\"cust-1\"}],\"eventVariables\":{\"" + p + "_email\":\"" + email
                + "\",\"" + p + "_ip\":\"" + ip + "\"" + (amount == null ? "" : ",\"" + p + "_amount\":\"" + amount
                + "\"") + "}}";
    }

    @Test
    void rulesScoreEvents() {
        String p = "rules";
        detector(p);
        error("CreateRule", "{\"ruleId\":\"bad\",\"detectorId\":\"" + p + "_checkout\",\"expression\":\"$" + p
                + "_email = 1\",\"language\":\"DETECTORPL\",\"outcomes\":[\"" + p + "_review\"]}", "Validation");
        error("CreateRule", "{\"ruleId\":\"bad\",\"detectorId\":\"" + p + "_checkout\",\"expression\":\"$nope > 1\","
                + "\"language\":\"DETECTORPL\",\"outcomes\":[\"" + p + "_review\"]}", "Validation");
        ok("CreateRule", "{\"ruleId\":\"blocked\",\"detectorId\":\"" + p + "_checkout\",\"expression\":\"$" + p
                + "_ip in @" + p + "_blocked_ips or ($" + p + "_amount > 1000 and $" + p + "_amount % 2 == 0)\","
                + "\"language\":\"DETECTORPL\",\"outcomes\":[\"" + p + "_block\"]}")
                .body("rule.ruleVersion", equalTo("1"));
        ok("CreateRule", "{\"ruleId\":\"high_risk\",\"detectorId\":\"" + p + "_checkout\",\"expression\":"
                + "\"regex_match(\\\".*@fraud\\\\.example$\\\", lowercase($" + p + "_email)) or $" + p
                + "_email == \\\"fraud@example.com\\\" # known address\",\"language\":\"DETECTORPL\","
                + "\"outcomes\":[\"" + p + "_review\"]}");
        String version = ok("CreateDetectorVersion", "{\"detectorId\":\"" + p + "_checkout\",\"rules\":["
                + "{\"detectorId\":\"" + p + "_checkout\",\"ruleId\":\"blocked\",\"ruleVersion\":\"1\"},"
                + "{\"detectorId\":\"" + p + "_checkout\",\"ruleId\":\"high_risk\",\"ruleVersion\":\"1\"}],"
                + "\"ruleExecutionMode\":\"ALL_MATCHED\"}").body("status", equalTo("DRAFT")).extract()
                .path("detectorVersionId");

        // Without an ACTIVE version, a prediction has nothing to run.
        error("GetEventPrediction", prediction(p, "e0", "a@b.c", "1.1.1.1", null), "ResourceNotFound");
        error("UpdateDetectorVersionStatus", "{\"detectorId\":\"" + p + "_checkout\",\"detectorVersionId\":\""
                + version + "\",\"status\":\"INACTIVE\"}", "Validation");
        ok("UpdateDetectorVersionStatus", "{\"detectorId\":\"" + p + "_checkout\",\"detectorVersionId\":\""
                + version + "\",\"status\":\"ACTIVE\"}");

        ok("GetEventPrediction", prediction(p, "e1", "Mallory@FRAUD.example", "1.1.1.1", "10"))
                .body("ruleResults.ruleId", contains("high_risk"))
                .body("ruleResults[0].outcomes", contains(p + "_review"));
        ok("GetEventPrediction", prediction(p, "e2", "fraud@example.com", "203.0.113.7", "10"))
                .body("ruleResults.ruleId", contains("blocked", "high_risk"));
        ok("GetEventPrediction", prediction(p, "e3", "ok@example.com", "1.1.1.1", "2000"))
                .body("ruleResults.ruleId", contains("blocked"));
        ok("GetEventPrediction", prediction(p, "e4", "ok@example.com", "1.1.1.1", "2001"))
                .body("ruleResults", empty());
        error("GetEventPrediction", prediction(p, "e5", "ok@example.com", "1.1.1.1", null).replace("_ip\"",
                "_unknown\""), "Validation");

        // The list is read at prediction time.
        ok("UpdateList", "{\"name\":\"" + p + "_blocked_ips\",\"elements\":[\"198.51.100.77\"],"
                + "\"updateMode\":\"APPEND\"}");
        ok("GetListElements", "{\"name\":\"" + p + "_blocked_ips\"}")
                .body("elements", hasItems("203.0.113.7", "198.51.100.77"));
        ok("GetEventPrediction", prediction(p, "e6", "ok@example.com", "198.51.100.77", null))
                .body("ruleResults.ruleId", contains("blocked"));

        // Predictions are audited with the values the rules saw.
        ok("ListEventPredictions", "{\"eventId\":{\"value\":\"e2\"}}").body("eventPredictionSummaries",
                hasSize(1));
        String at = ok("ListEventPredictions", "{\"eventId\":{\"value\":\"e2\"}}").extract()
                .path("eventPredictionSummaries[0].predictionTimestamp");
        ok("GetEventPredictionMetadata", "{\"eventId\":\"e2\",\"eventTypeName\":\"" + p + "_purchase\","
                + "\"detectorId\":\"" + p + "_checkout\",\"detectorVersionId\":\"" + version + "\","
                + "\"predictionTimestamp\":\"" + at + "\"}")
                .body("outcomes", hasItems(p + "_review", p + "_block"))
                .body("rules", hasSize(2))
                .body("rules[1].expressionWithValues", containsString("\"fraud@example.com\" == \"fraud@example.com\""))
                .body("eventVariables.find { it.name == '" + p + "_amount' }.value", equalTo("10"))
                .body("ruleExecutionMode", equalTo("ALL_MATCHED"));

        // With ingestion enabled, the prediction stored the event.
        ok("GetEvent", "{\"eventId\":\"e2\",\"eventTypeName\":\"" + p + "_purchase\"}")
                .body("event.eventVariables." + p + "_email", equalTo("fraud@example.com"));

        // FIRST_MATCHED stops at the first rule that matches.
        String second = ok("CreateDetectorVersion", "{\"detectorId\":\"" + p + "_checkout\",\"rules\":["
                + "{\"detectorId\":\"" + p + "_checkout\",\"ruleId\":\"blocked\",\"ruleVersion\":\"1\"},"
                + "{\"detectorId\":\"" + p + "_checkout\",\"ruleId\":\"high_risk\",\"ruleVersion\":\"1\"}]}")
                .extract().path("detectorVersionId");
        ok("UpdateDetectorVersionStatus", "{\"detectorId\":\"" + p + "_checkout\",\"detectorVersionId\":\""
                + second + "\",\"status\":\"ACTIVE\"}");
        ok("GetDetectorVersion", "{\"detectorId\":\"" + p + "_checkout\",\"detectorVersionId\":\"" + version
                + "\"}").body("status", equalTo("INACTIVE"));
        ok("GetEventPrediction", prediction(p, "e7", "fraud@example.com", "203.0.113.7", null))
                .body("ruleResults.ruleId", contains("blocked"));

        // In-use resources can't be deleted; the teardown order alchemy uses succeeds.
        error("DeleteDetectorVersion", "{\"detectorId\":\"" + p + "_checkout\",\"detectorVersionId\":\"" + second
                + "\"}", "Conflict");
        error("DeleteRule", "{\"rule\":{\"detectorId\":\"" + p + "_checkout\",\"ruleId\":\"blocked\","
                + "\"ruleVersion\":\"1\"}}", "Conflict");
        error("DeleteOutcome", "{\"name\":\"" + p + "_block\"}", "Conflict");
        error("DeleteList", "{\"name\":\"" + p + "_blocked_ips\"}", "Conflict");
        error("DeleteEventType", "{\"name\":\"" + p + "_purchase\"}", "Conflict");
        error("DeleteVariable", "{\"name\":\"" + p + "_email\"}", "Conflict");
        error("DeleteEntityType", "{\"name\":\"" + p + "_customer\"}", "Conflict");
        error("DeleteDetector", "{\"detectorId\":\"" + p + "_checkout\"}", "Conflict");
        for (String id : List.of(version, second)) {
            ok("UpdateDetectorVersionStatus", "{\"detectorId\":\"" + p + "_checkout\",\"detectorVersionId\":\""
                    + id + "\",\"status\":\"INACTIVE\"}");
            ok("DeleteDetectorVersion", "{\"detectorId\":\"" + p + "_checkout\",\"detectorVersionId\":\"" + id
                    + "\"}");
        }
        for (String rule : List.of("blocked", "high_risk")) {
            ok("DeleteRule", "{\"rule\":{\"detectorId\":\"" + p + "_checkout\",\"ruleId\":\"" + rule + "\","
                    + "\"ruleVersion\":\"1\"}}");
        }
        ok("DeleteDetector", "{\"detectorId\":\"" + p + "_checkout\"}");
        ok("DeleteEventType", "{\"name\":\"" + p + "_purchase\"}");
        for (String variable : List.of("email", "ip", "amount")) {
            ok("DeleteVariable", "{\"name\":\"" + p + "_" + variable + "\"}");
        }
        ok("DeleteList", "{\"name\":\"" + p + "_blocked_ips\"}");
        ok("DeleteEntityType", "{\"name\":\"" + p + "_customer\"}");
        error("GetDetectors", "{\"detectorId\":\"" + p + "_checkout\"}", "ResourceNotFound");
        error("GetVariables", "{\"name\":\"" + p + "_email\"}", "ResourceNotFound");
        // Deleting what's already gone succeeds.
        ok("DeleteDetector", "{\"detectorId\":\"" + p + "_checkout\"}");
        ok("DeleteVariable", "{\"name\":\"" + p + "_email\"}");
    }

    @Test
    void eventsAreStoredLabeledAndPurged() {
        String p = "events";
        detector(p);
        String event = "{\"eventId\":\"stored-1\",\"eventTypeName\":\"" + p + "_purchase\",\"eventTimestamp\":\""
                + Instant.now() + "\",\"entities\":[{\"entityType\":\"" + p + "_customer\",\"entityId\":\"c1\"}],"
                + "\"eventVariables\":{\"" + p + "_email\":\"a@example.com\"}}";
        error("GetEvent", "{\"eventId\":\"stored-1\",\"eventTypeName\":\"" + p + "_purchase\"}",
                "ResourceNotFound");
        ok("SendEvent", event);
        error("SendEvent", event, "Conflict");
        ok("UpdateEventLabel", "{\"eventId\":\"stored-1\",\"eventTypeName\":\"" + p + "_purchase\","
                + "\"assignedLabel\":\"" + p + "_fraud\",\"labelTimestamp\":\"" + Instant.now() + "\"}");
        error("UpdateEventLabel", "{\"eventId\":\"stored-1\",\"eventTypeName\":\"" + p + "_purchase\","
                + "\"assignedLabel\":\"nope\",\"labelTimestamp\":\"" + Instant.now() + "\"}", "Validation");
        ok("GetEvent", "{\"eventId\":\"stored-1\",\"eventTypeName\":\"" + p + "_purchase\"}")
                .body("event.currentLabel", equalTo(p + "_fraud"));
        ok("GetEventTypes", "{\"name\":\"" + p + "_purchase\"}")
                .body("eventTypes[0].ingestedEventStatistics.numberOfEvents", equalTo(1));
        ok("DeleteEvent", "{\"eventId\":\"stored-1\",\"eventTypeName\":\"" + p + "_purchase\"}");
        error("GetEvent", "{\"eventId\":\"stored-1\",\"eventTypeName\":\"" + p + "_purchase\"}",
                "ResourceNotFound");

        ok("SendEvent", event.replace("stored-1", "stored-2"));
        ok("DeleteEventsByEventType", "{\"eventTypeName\":\"" + p + "_purchase\"}")
                .body("eventsDeletionStatus", startsWith("IN_PROGRESS"));
        await().atMost(Duration.ofSeconds(10)).until(() -> "COMPLETE".equals(ok("GetDeleteEventsByEventTypeStatus",
                "{\"eventTypeName\":\"" + p + "_purchase\"}").extract().path("eventsDeletionStatus")));
        error("GetEvent", "{\"eventId\":\"stored-2\",\"eventTypeName\":\"" + p + "_purchase\"}",
                "ResourceNotFound");
    }

    @Test
    void modelsTrainOnLabeledEventsAndScorePredictions() {
        String p = "model";
        detector(p);
        Instant start = Instant.now().minusSeconds(60);
        for (int i = 0; i < 20; i++) {
            boolean fraud = i % 4 == 0;
            ok("SendEvent", "{\"eventId\":\"train-" + i + "\",\"eventTypeName\":\"" + p + "_purchase\","
                    + "\"eventTimestamp\":\"" + Instant.now() + "\",\"entities\":[{\"entityType\":\"" + p
                    + "_customer\",\"entityId\":\"c" + i + "\"}],\"eventVariables\":{\"" + p + "_email\":\""
                    + (fraud ? "bot" : "user" + i) + "@example.com\",\"" + p + "_ip\":\"" + (fraud ? "10.0.0.1"
                    : "192.0.2." + i) + "\",\"" + p + "_amount\":\"" + (fraud ? 900 + i : 10 + i) + "\"},"
                    + "\"assignedLabel\":\"" + p + (fraud ? "_fraud" : "_legit") + "\",\"labelTimestamp\":\""
                    + Instant.now() + "\"}");
        }
        ok("CreateModel", "{\"modelId\":\"" + p + "_ofi\",\"modelType\":\"ONLINE_FRAUD_INSIGHTS\","
                + "\"eventTypeName\":\"" + p + "_purchase\"}");
        String schema = "\"trainingDataSchema\":{\"modelVariables\":[\"" + p + "_ip\",\"" + p + "_amount\"],"
                + "\"labelSchema\":{\"labelMapper\":{\"FRAUD\":[\"" + p + "_fraud\"],\"LEGIT\":[\"" + p
                + "_legit\"]}}}";
        ok("CreateModelVersion", "{\"modelId\":\"" + p + "_ofi\",\"modelType\":\"ONLINE_FRAUD_INSIGHTS\","
                + "\"trainingDataSource\":\"INGESTED_EVENTS\"," + schema + ",\"ingestedEventsDetail\":"
                + "{\"ingestedEventsTimeWindow\":{\"startTime\":\"" + start + "\",\"endTime\":\""
                + Instant.now().plusSeconds(60) + "\"}}}")
                .body("modelVersionNumber", equalTo("1.0")).body("status", equalTo("TRAINING_IN_PROGRESS"));
        String model = "\"modelId\":\"" + p + "_ofi\",\"modelType\":\"ONLINE_FRAUD_INSIGHTS\"";
        await().atMost(Duration.ofSeconds(10)).until(() -> "TRAINING_COMPLETE".equals(ok("GetModelVersion",
                "{" + model + ",\"modelVersionNumber\":\"1.0\"}").extract().path("status")));
        ok("DescribeModelVersions", "{" + model + "}")
                .body("modelVersionDetails[0].trainingResult.trainingMetrics.auc", equalTo(1.0f))
                .body("modelVersionDetails[0].trainingResultV2.trainingMetricsV2.ofi.modelPerformance.auc",
                        notNullValue());
        ok("UpdateModelVersionStatus", "{" + model + ",\"modelVersionNumber\":\"1.0\",\"status\":\"ACTIVE\"}");
        ok("GetVariables", "{\"name\":\"" + p + "_ofi_insightscore\"}")
                .body("variables[0].dataSource", equalTo("MODEL_SCORE"));

        ok("CreateRule", "{\"ruleId\":\"risky\",\"detectorId\":\"" + p + "_checkout\",\"expression\":\"$" + p
                + "_ofi_insightscore > 700\",\"language\":\"DETECTORPL\",\"outcomes\":[\"" + p + "_review\"]}");
        String version = ok("CreateDetectorVersion", "{\"detectorId\":\"" + p + "_checkout\",\"rules\":["
                + "{\"detectorId\":\"" + p + "_checkout\",\"ruleId\":\"risky\",\"ruleVersion\":\"1\"}],"
                + "\"modelVersions\":[{" + model + ",\"modelVersionNumber\":\"1.0\"}]}").extract()
                .path("detectorVersionId");
        ok("UpdateDetectorVersionStatus", "{\"detectorId\":\"" + p + "_checkout\",\"detectorVersionId\":\""
                + version + "\",\"status\":\"ACTIVE\"}");

        float fraudScore = ok("GetEventPrediction", prediction(p, "score-1", "x@example.com", "10.0.0.1", "905"))
                .body("ruleResults.ruleId", contains("risky")).extract()
                .path("modelScores[0].scores." + p + "_ofi_insightscore");
        float legitScore = ok("GetEventPrediction", prediction(p, "score-2", "x@example.com", "192.0.2.3", "13"))
                .body("ruleResults", empty()).extract().path("modelScores[0].scores." + p + "_ofi_insightscore");
        assertTrue(fraudScore > 700 && legitScore < 300, fraudScore + " vs " + legitScore);

        // A model version used by an ACTIVE detector version stays.
        error("UpdateModelVersionStatus", "{" + model + ",\"modelVersionNumber\":\"1.0\",\"status\":"
                + "\"INACTIVE\"}", "Conflict");
        error("DeleteModel", "{" + model + "}", "Conflict");

        // A batch prediction reads events from S3 and writes the detector's outcomes back.
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/s3/"
                + "aws4_request").put("/" + p + "-batch").then().statusCode(200);
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/s3/"
                + "aws4_request").body("EVENT_ID,EVENT_TIMESTAMP,ENTITY_ID,ENTITY_TYPE," + p + "_ip," + p
                + "_amount\nb1," + Instant.now() + ",c1," + p + "_customer,10.0.0.1,905\nb2," + Instant.now()
                + ",c2," + p + "_customer,192.0.2.4,14\n").put("/" + p + "-batch/in.csv").then().statusCode(200);
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/iam/"
                + "aws4_request").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", p + "-batch-role")
                .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":"
                        + "\"Allow\",\"Principal\":{\"Service\":\"frauddetector.amazonaws.com\"},\"Action\":"
                        + "\"sts:AssumeRole\"}]}").post("/").then().statusCode(200);
        ok("CreateBatchPredictionJob", "{\"jobId\":\"" + p + "-job\",\"inputPath\":\"s3://" + p
                + "-batch/in.csv\",\"outputPath\":\"s3://" + p + "-batch/out\",\"eventTypeName\":\"" + p
                + "_purchase\",\"detectorName\":\"" + p + "_checkout\",\"iamRoleArn\":"
                + "\"arn:aws:iam::000000000000:role/" + p + "-batch-role\"}");
        await().atMost(Duration.ofSeconds(10)).until(() -> "COMPLETE".equals(ok("GetBatchPredictionJobs",
                "{\"jobId\":\"" + p + "-job\"}").extract().path("batchPredictions[0].status")));
        ok("GetBatchPredictionJobs", "{\"jobId\":\"" + p + "-job\"}")
                .body("batchPredictions[0].processedRecordsCount", equalTo(2));
        String output = given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/"
                + "us-east-1/s3/aws4_request").get("/" + p + "-batch/out/" + p + "-job_output.csv").then()
                .statusCode(200).extract().asString();
        List<String> lines = output.lines().toList();
        assertTrue(lines.get(1).startsWith("b1,") && lines.get(1).contains(p + "_review"), output);
        assertTrue(lines.get(2).startsWith("b2,") && !lines.get(2).contains(p + "_review"), output);

        // A job whose role doesn't exist fails.
        ok("CreateBatchPredictionJob", "{\"jobId\":\"" + p + "-norole\",\"inputPath\":\"s3://" + p
                + "-batch/in.csv\",\"outputPath\":\"s3://" + p + "-batch/out\",\"eventTypeName\":\"" + p
                + "_purchase\",\"detectorName\":\"" + p + "_checkout\",\"iamRoleArn\":"
                + "\"arn:aws:iam::000000000000:role/missing-role\"}");
        await().atMost(Duration.ofSeconds(10)).until(() -> "FAILED".equals(ok("GetBatchPredictionJobs",
                "{\"jobId\":\"" + p + "-norole\"}").extract().path("batchPredictions[0].status")));
    }

    @Test
    void tagsAndSettings() {
        ok("PutLabel", "{\"name\":\"tagged\",\"tags\":[{\"key\":\"team\",\"value\":\"risk\"}]}");
        String arn = ok("GetLabels", "{\"name\":\"tagged\"}").body("labels[0].arn",
                equalTo("arn:aws:frauddetector:us-east-1:000000000000:label/tagged")).extract()
                .path("labels[0].arn");
        ok("TagResource", "{\"resourceARN\":\"" + arn + "\",\"tags\":[{\"key\":\"alchemy::id\",\"value\":\"x\"}]}");
        ok("ListTagsForResource", "{\"resourceARN\":\"" + arn + "\"}").body("tags.key", hasItems("team",
                "alchemy::id"));
        ok("UntagResource", "{\"resourceARN\":\"" + arn + "\",\"tagKeys\":[\"team\"]}");
        ok("ListTagsForResource", "{\"resourceARN\":\"" + arn + "\"}").body("tags.key", not(hasItem("team")));
        ok("DeleteLabel", "{\"name\":\"tagged\"}");
        error("ListTagsForResource", "{\"resourceARN\":\"" + arn + "\"}", "ResourceNotFound");

        error("GetDetectors", "{\"detectorId\":\"does_not_exist_detector\"}", "ResourceNotFound");
        error("UpdateList", "{\"name\":\"does_not_exist_list\",\"elements\":[\"203.0.113.1\"],"
                + "\"updateMode\":\"REPLACE\"}", "ResourceNotFound");
        error("GetEvent", "{\"eventId\":\"x\",\"eventTypeName\":\"does_not_exist_event_type\"}", "ResourceNotFound");
        error("GetDetectors", "{\"maxResults\":1}", "Validation");

        ok("GetKMSEncryptionKey", "{}").body("kmsKey.kmsEncryptionKeyArn", equalTo("DEFAULT"));
        error("PutKMSEncryptionKey", "{\"kmsEncryptionKeyArn\":\"arn:aws:kms:us-east-1:000000000000:key/"
                + "00000000-0000-4000-8000-000000000000\"}", "ResourceNotFound");
    }
}
