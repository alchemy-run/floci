package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ConfigResourceIntegrationTest {

    private static final String REGION = "us-west-1";
    private static final String TYPE = "Example::Config::Widget";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void emptyStateAndMissingPrerequisitesReturnModeledResponses() {
        String account = "910000000001";
        call(account, "ListDiscoveredResources", Map.of("resourceType", "AWS::S3::Bucket"))
                .statusCode(200).body("resourceIdentifiers", empty());
        call(account, "GetDiscoveredResourceCounts", Map.of())
                .statusCode(200).body("totalDiscoveredResources", equalTo(0)).body("resourceCounts", empty());
        call(account, "SelectResourceConfig", Map.of("Expression", "SELECT resourceId WHERE resourceType = 'AWS::S3::Bucket'"))
                .statusCode(200).body("Results", empty()).body("QueryInfo.SelectFields[0].FieldName", equalTo("resourceId"));
        call(account, "BatchGetResourceConfig", Map.of("resourceKeys", List.of(key("missing"))))
                .statusCode(200).body("baseConfigurationItems", empty())
                .body("unprocessedResourceKeys[0].resourceId", equalTo("missing"));
        call(account, "GetResourceConfigHistory", key("missing"))
                .statusCode(400).body("__type", equalTo("ResourceNotDiscoveredException"));
        call(account, "PutResourceConfig", put("missing", "{}"))
                .statusCode(400).body("__type", equalTo("NoRunningConfigurationRecorderException"));
        call(account, "DeleteResourceConfig", delete("missing"))
                .statusCode(400).body("__type", equalTo("NoRunningConfigurationRecorderException"));
        call(account, "StartResourceEvaluation", evaluation("{}", "empty-evaluation"))
                .statusCode(400).body("__type", equalTo("InvalidParameterValueException"));
        call(account, "GetResourceEvaluationSummary", Map.of("ResourceEvaluationId", "missing"))
                .statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
        call(account, "ListResourceEvaluations", Map.of("Filters", Map.of("EvaluationMode", "PROACTIVE")))
                .statusCode(200).body("ResourceEvaluations", empty());
    }

    @Test
    void recordedStateDrivesDiscoveryQueriesCountsBatchAndHistory() {
        String account = "910000000002";
        recorder(account, "recorder");
        channel(account);
        call(account, "StartConfigurationRecorder", Map.of("ConfigurationRecorderName", "recorder")).statusCode(200);
        try {
            call(account, "PutResourceConfig", put("a", "{\"color\":\"blue\",\"size\":2}" )).statusCode(200);
            call(account, "PutResourceConfig", put("b", "{\"color\":\"red\",\"size\":10}" )).statusCode(200);
            call(account, "PutResourceConfig", put("a", "{\"color\":\"green\",\"size\":3}" )).statusCode(200);
            String token = call(account, "ListDiscoveredResources", Map.of("resourceType", TYPE, "limit", 1))
                    .statusCode(200).body("resourceIdentifiers", hasSize(1))
                    .body("resourceIdentifiers[0].resourceId", equalTo("a"))
                    .extract().path("nextToken");
            assertNotNull(token);
            call(account, "ListDiscoveredResources", Map.of("resourceType", TYPE, "limit", 1, "nextToken", token))
                    .statusCode(200).body("resourceIdentifiers[0].resourceId", equalTo("b"))
                    .body("nextToken", nullValue());
            call(account, "ListDiscoveredResources", Map.of("resourceType", "Other::Config::Widget", "nextToken", token))
                    .statusCode(400).body("__type", equalTo("InvalidNextTokenException"));
            call(account, "ListDiscoveredResources", Map.of("resourceType", TYPE, "resourceIds", List.of("b")))
                    .statusCode(200).body("resourceIdentifiers*.resourceId", equalTo(List.of("b")));
            call(account, "ListDiscoveredResources", Map.of("resourceType", TYPE, "resourceName", "name-a"))
                    .statusCode(200).body("resourceIdentifiers*.resourceId", equalTo(List.of("a")));
            call(account, "GetDiscoveredResourceCounts", Map.of("resourceTypes", List.of(TYPE)))
                    .statusCode(200).body("totalDiscoveredResources", equalTo(2))
                    .body("resourceCounts[0].count", equalTo(2));
            call(account, "GetDiscoveredResourceCounts", Map.of("resourceTypes", List.of("Other::Config::Widget")))
                    .statusCode(200).body("totalDiscoveredResources", equalTo(0));
            call(account, "BatchGetResourceConfig", Map.of("resourceKeys", List.of(key("a"), key("missing"))))
                    .statusCode(200).body("baseConfigurationItems", hasSize(1))
                    .body("baseConfigurationItems[0].configuration", equalTo("{\"color\":\"green\",\"size\":3}"))
                    .body("baseConfigurationItems[0].tags", nullValue())
                    .body("unprocessedResourceKeys[0].resourceId", equalTo("missing"));
            call(account, "SelectResourceConfig", Map.of("Expression",
                    "SELECT resourceId, configuration.color WHERE resourceType = '" + TYPE + "' AND configuration.color = 'green'"))
                    .statusCode(200).body("Results", equalTo(List.of("{\"resourceId\":\"a\",\"configuration\":{\"color\":\"green\"}}")));
            String queryToken = call(account, "SelectResourceConfig", Map.of("Expression",
                    "SELECT resourceId ORDER BY configuration.size DESC", "Limit", 1))
                    .statusCode(200).body("Results", equalTo(List.of("{\"resourceId\":\"b\"}")))
                    .extract().path("NextToken");
            call(account, "SelectResourceConfig", Map.of("Expression", "SELECT resourceId ORDER BY configuration.size DESC",
                    "NextToken", queryToken, "Limit", 1))
                    .statusCode(200).body("Results", equalTo(List.of("{\"resourceId\":\"a\"}")));
            call(account, "SelectResourceConfig", Map.of("Expression", "SELECT COUNT(*) WHERE resourceType = '" + TYPE + "'"))
                    .statusCode(200).body("Results", equalTo(List.of("{\"COUNT(*)\":2}")));
            String historyToken = call(account, "GetResourceConfigHistory", Map.of("resourceType", TYPE, "resourceId", "a", "limit", 1))
                    .statusCode(200).body("configurationItems[0].configurationStateId", equalTo("2"))
                    .body("configurationItems[0].tags.owner", equalTo("config-test"))
                    .extract().path("nextToken");
            call(account, "GetResourceConfigHistory", Map.of("resourceType", TYPE, "resourceId", "a", "nextToken", historyToken, "limit", 1))
                    .statusCode(200).body("configurationItems[0].configurationStateId", equalTo("1"));
            call(account, "GetResourceConfigHistory", Map.of("resourceType", TYPE, "resourceId", "a", "chronologicalOrder", "Forward"))
                    .statusCode(200).body("configurationItems*.configurationStateId", equalTo(List.of("1", "2")));
            call(account, "GetResourceConfigHistory", Map.of("resourceType", TYPE, "resourceId", "a", "laterTime", 1))
                    .statusCode(200).body("configurationItems", empty());
            call(account, "StopConfigurationRecorder", Map.of("ConfigurationRecorderName", "recorder")).statusCode(200);
            call(account, "PutResourceConfig", put("a", "{}"))
                    .statusCode(400).body("__type", equalTo("NoRunningConfigurationRecorderException"));
            call(account, "DeleteResourceConfig", delete("a"))
                    .statusCode(400).body("__type", equalTo("NoRunningConfigurationRecorderException"));
            call(account, "GetResourceConfigHistory", key("a"))
                    .statusCode(200).body("configurationItems", hasSize(2));
            call(account, "StartConfigurationRecorder", Map.of("ConfigurationRecorderName", "recorder")).statusCode(200);
            call(account, "DeleteResourceConfig", delete("a")).statusCode(200);
            call(account, "DeleteResourceConfig", delete("a")).statusCode(200);
            call(account, "ListDiscoveredResources", Map.of("resourceType", TYPE)).statusCode(200)
                    .body("resourceIdentifiers*.resourceId", equalTo(List.of("b")));
            call(account, "ListDiscoveredResources", Map.of("resourceType", TYPE, "includeDeletedResources", true))
                    .statusCode(200).body("resourceIdentifiers", hasSize(2))
                    .body("resourceIdentifiers[0].resourceDeletionTime", notNullValue());
            call(account, "GetResourceConfigHistory", key("a")).statusCode(200)
                    .body("configurationItems", hasSize(3))
                    .body("configurationItems[0].configurationItemStatus", equalTo("ResourceDeleted"));
            call(account, "BatchGetResourceConfig", Map.of("resourceKeys", List.of(key("a"))))
                    .statusCode(200).body("baseConfigurationItems", empty()).body("unprocessedResourceKeys", hasSize(1));
            call(account, "SelectResourceConfig", Map.of("Expression", "SELECT resourceId"))
                    .statusCode(200).body("Results", equalTo(List.of("{\"resourceId\":\"b\"}")));
            call(account, "GetDiscoveredResourceCounts", Map.of()).statusCode(200).body("totalDiscoveredResources", equalTo(1));
            call(account, "PutResourceConfig", Map.of("ResourceType", "Other::Config::Widget", "ResourceId", "other",
                    "SchemaVersionId", "1", "Configuration", "{}" )).statusCode(200);
            String countToken = call(account, "GetDiscoveredResourceCounts", Map.of("limit", 1))
                    .statusCode(200).body("totalDiscoveredResources", equalTo(2)).body("resourceCounts", hasSize(1))
                    .extract().path("nextToken");
            call(account, "GetDiscoveredResourceCounts", Map.of("limit", 1, "nextToken", countToken))
                    .statusCode(200).body("totalDiscoveredResources", equalTo(2))
                    .body("resourceCounts[0].resourceType", equalTo("Other::Config::Widget")).body("nextToken", nullValue());
            call("910000000020", "ListDiscoveredResources", Map.of("resourceType", TYPE, "nextToken", token))
                    .statusCode(400).body("__type", equalTo("InvalidNextTokenException"));
            call(account, "DeleteResourceConfig", Map.of("ResourceType", "Other::Config::Widget", "ResourceId", "other"))
                    .statusCode(200);
            call(account, "DeleteResourceConfig", delete("b")).statusCode(200);
        } finally {
            cleanupRecorder(account, "recorder");
        }
    }

    @Test
    void recorderRoundTripsRoleGroupsModeTagsAndProtectsIdentity() {
        String account = "910000000003";
        Map<String, Object> group = Map.of("allSupported", false, "includeGlobalResourceTypes", false,
                "exclusionByResourceTypes", Map.of("resourceTypes", List.of("AWS::EC2::Instance")),
                "recordingStrategy", Map.of("useOnly", "EXCLUSION_BY_RESOURCE_TYPES"));
        Map<String, Object> mode = Map.of("recordingFrequency", "DAILY", "recordingModeOverrides", List.of(
                Map.of("description", "bucket changes", "resourceTypes", List.of("AWS::S3::Bucket"), "recordingFrequency", "CONTINUOUS")));
        String role = "arn:aws:iam::" + account + ":role/aws-service-role/config.amazonaws.com/AWSServiceRoleForConfig";
        call(account, "PutConfigurationRecorder", Map.of("ConfigurationRecorder", Map.of("name", "owned", "roleARN", role,
                "recordingGroup", group, "recordingMode", mode), "Tags", List.of(Map.of("Key", "owner", "Value", "test"))))
                .statusCode(200);
        String arn = call(account, "DescribeConfigurationRecorders", Map.of("ConfigurationRecorderNames", List.of("owned")))
                .statusCode(200).body("ConfigurationRecorders[0].roleARN", equalTo(role))
                .body("ConfigurationRecorders[0].recordingGroup", equalTo(group))
                .body("ConfigurationRecorders[0].recordingMode", equalTo(mode))
                .extract().path("ConfigurationRecorders[0].arn");
        assertTrue(arn.contains(":configuration-recorder/owned/"));
        try {
            call(account, "ListTagsForResource", Map.of("ResourceArn", arn)).statusCode(200)
                    .body("Tags", equalTo(List.of(Map.of("Key", "owner", "Value", "test"))));
            call(account, "StartConfigurationRecorder", Map.of("ConfigurationRecorderName", "owned"))
                    .statusCode(400).body("__type", equalTo("NoAvailableDeliveryChannelException"));
            recorderFailure(account, "foreign", "MaxNumberOfConfigurationRecordersExceededException");
            channel(account);
            call(account, "StartConfigurationRecorder", Map.of("ConfigurationRecorderName", "owned")).statusCode(200);
            call(account, "StopConfigurationRecorder", Map.of("ConfigurationRecorderName", "foreign"))
                    .statusCode(400).body("__type", equalTo("NoSuchConfigurationRecorderException"));
            call(account, "DeleteConfigurationRecorder", Map.of("ConfigurationRecorderName", "foreign"))
                    .statusCode(400).body("__type", equalTo("NoSuchConfigurationRecorderException"));
            call(account, "PutConfigurationRecorder", Map.of("ConfigurationRecorder", Map.of("name", "owned",
                    "roleARN", "arn:aws:iam::" + account + ":role/updated",
                    "recordingGroup", Map.of("resourceTypes", List.of("AWS::S3::Bucket")),
                    "recordingMode", Map.of("recordingFrequency", "CONTINUOUS")),
                    "Tags", List.of(Map.of("Key", "owner", "Value", "ignored-update")))).statusCode(200);
            call(account, "DescribeConfigurationRecorders", Map.of()).statusCode(200)
                    .body("ConfigurationRecorders[0].arn", equalTo(arn))
                    .body("ConfigurationRecorders[0].roleARN", equalTo("arn:aws:iam::" + account + ":role/updated"))
                    .body("ConfigurationRecorders[0].recordingGroup.resourceTypes", equalTo(List.of("AWS::S3::Bucket")))
                    .body("ConfigurationRecorders[0].recordingMode.recordingFrequency", equalTo("CONTINUOUS"));
            call(account, "ListTagsForResource", Map.of("ResourceArn", arn)).statusCode(200)
                    .body("Tags", equalTo(List.of(Map.of("Key", "owner", "Value", "test"))));
            call(account, "DescribeConfigurationRecorderStatus", Map.of()).statusCode(200)
                    .body("ConfigurationRecordersStatus[0].recording", equalTo(true));
        } finally {
            cleanupRecorder(account, "owned");
        }
        call(account, "ListTagsForResource", Map.of("ResourceArn", arn)).statusCode(200).body("Tags", empty());
        recorder(account, "successor");
        try {
            call(account, "DeleteConfigurationRecorder", Map.of("ConfigurationRecorderName", "owned"))
                    .statusCode(400).body("__type", equalTo("NoSuchConfigurationRecorderException"));
            call(account, "DescribeConfigurationRecorderStatus", Map.of()).statusCode(200)
                    .body("ConfigurationRecordersStatus[0].name", equalTo("successor"))
                    .body("ConfigurationRecordersStatus[0].recording", equalTo(false));
        } finally {
            call(account, "DeleteConfigurationRecorder", Map.of("ConfigurationRecorderName", "successor")).statusCode(200);
        }
    }

    @Test
    void accountAndRegionIsolationIncludesRecorderRunState() {
        String first = "910000000004";
        String second = "910000000005";
        recorder(first, "recorder");
        recorder(second, "recorder");
        channel(first);
        channel(second);
        call(first, "StartConfigurationRecorder", Map.of("ConfigurationRecorderName", "recorder")).statusCode(200);
        try {
            call(first, "PutResourceConfig", put("same", "{\"owner\":1}" )).statusCode(200);
            call(second, "PutResourceConfig", put("same", "{\"owner\":2}" ))
                    .statusCode(400).body("__type", equalTo("NoRunningConfigurationRecorderException"));
            call(second, "GetDiscoveredResourceCounts", Map.of()).statusCode(200).body("totalDiscoveredResources", equalTo(0));
            call(first, "us-east-2", "GetDiscoveredResourceCounts", Map.of()).statusCode(200)
                    .body("totalDiscoveredResources", equalTo(0));
            call(first, "us-east-2", "DeleteResourceConfig", delete("same"))
                    .statusCode(400).body("__type", equalTo("NoRunningConfigurationRecorderException"));
            call(second, "StartConfigurationRecorder", Map.of("ConfigurationRecorderName", "recorder")).statusCode(200);
            call(second, "PutResourceConfig", put("same", "{\"owner\":2}" )).statusCode(200);
            call(second, "StopConfigurationRecorder", Map.of("ConfigurationRecorderName", "recorder")).statusCode(200);
            call(first, "DeleteResourceConfig", delete("same")).statusCode(200);
            call(second, "BatchGetResourceConfig", Map.of("resourceKeys", List.of(key("same"))))
                    .statusCode(200).body("baseConfigurationItems[0].configuration", equalTo("{\"owner\":2}"));
        } finally {
            cleanupRecorder(first, "recorder");
            cleanupRecorder(second, "recorder");
        }
    }

    @Test
    void proactiveEvaluationRequiresApplicableRulesAndEvaluatesConfiguration() {
        String account = "910000000006";
        recorder(account, "recorder");
        Map<String, Object> source = Map.of("Owner", "AWS", "SourceIdentifier", "S3_BUCKET_VERSIONING_ENABLED");
        call(account, "PutConfigRule", Map.of("ConfigRule", Map.of("ConfigRuleName", "versioning", "Source", source)))
                .statusCode(200);
        try {
            call(account, "StartResourceEvaluation", evaluation("{}", "detective-only"))
                    .statusCode(400).body("__type", equalTo("InvalidParameterValueException"));
            call(account, "ListResourceEvaluations", Map.of()).statusCode(200).body("ResourceEvaluations", empty());
            call(account, "PutConfigRule", Map.of("ConfigRule", Map.of("ConfigRuleName", "versioning", "Source", source,
                    "Scope", Map.of("ComplianceResourceTypes", List.of("AWS::S3::Bucket")),
                    "EvaluationModes", List.of(Map.of("Mode", "PROACTIVE")))))
                    .statusCode(200);
            call(account, "StartResourceEvaluation", evaluation("not-json", "malformed"))
                    .statusCode(400).body("__type", equalTo("InvalidParameterValueException"));
            call(account, "StartResourceEvaluation", evaluation("{\"VersioningConfiguration\":{\"Status\":\"Wrong\"}}", "invalid-status"))
                    .statusCode(400).body("__type", equalTo("InvalidParameterValueException"));
            Map<String, Object> compliant = evaluation("{\"VersioningConfiguration\":{\"Status\":\"Enabled\"}}", "compliant");
            String id = call(account, "StartResourceEvaluation", compliant).statusCode(200).extract().path("ResourceEvaluationId");
            call(account, "StartResourceEvaluation", compliant).statusCode(200).body("ResourceEvaluationId", equalTo(id));
            call(account, "StartResourceEvaluation", evaluation("{}", "compliant"))
                    .statusCode(400).body("__type", equalTo("IdempotentParameterMismatch"));
            call(account, "GetResourceEvaluationSummary", Map.of("ResourceEvaluationId", id)).statusCode(200)
                    .body("EvaluationStatus.Status", equalTo("SUCCEEDED")).body("Compliance", equalTo("COMPLIANT"))
                    .body("ResourceDetails.ResourceId", equalTo("bucket-probe"));
            String failed = call(account, "StartResourceEvaluation", evaluation("{}", "noncompliant"))
                    .statusCode(200).extract().path("ResourceEvaluationId");
            call(account, "GetResourceEvaluationSummary", Map.of("ResourceEvaluationId", failed)).statusCode(200)
                    .body("Compliance", equalTo("NON_COMPLIANT"));
            String token = call(account, "ListResourceEvaluations", Map.of("Limit", 1,
                    "Filters", Map.of("EvaluationMode", "PROACTIVE", "EvaluationContextIdentifier", "config-test")))
                    .statusCode(200).body("ResourceEvaluations", hasSize(1)).extract().path("NextToken");
            call(account, "ListResourceEvaluations", Map.of("Limit", 1, "NextToken", token,
                    "Filters", Map.of("EvaluationMode", "PROACTIVE", "EvaluationContextIdentifier", "config-test")))
                    .statusCode(200).body("ResourceEvaluations", hasSize(1)).body("NextToken", nullValue());
            call(account, "ListResourceEvaluations", Map.of("Filters", Map.of("TimeWindow", Map.of("EndTime", 1))))
                    .statusCode(200).body("ResourceEvaluations", empty());
            call(account, "ListResourceEvaluations", Map.of("Filters", Map.of("EvaluationMode", "DETECTIVE")))
                    .statusCode(200).body("ResourceEvaluations", empty());
            call("910000000007", "GetResourceEvaluationSummary", Map.of("ResourceEvaluationId", id))
                    .statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
            call(account, "us-east-2", "GetResourceEvaluationSummary", Map.of("ResourceEvaluationId", id))
                    .statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
            call(account, "StartResourceEvaluation", Map.of("EvaluationMode", "PROACTIVE", "ResourceDetails",
                    Map.of("ResourceId", "instance", "ResourceType", "AWS::EC2::Instance", "ResourceConfiguration", "{}")))
                    .statusCode(400).body("__type", equalTo("InvalidParameterValueException"));
            call(account, "PutConfigRule", Map.of("ConfigRule", Map.of("ConfigRuleName", "versioning",
                    "Source", Map.of("Owner", "AWS", "SourceIdentifier", "REQUIRED_TAGS"),
                    "EvaluationModes", List.of(Map.of("Mode", "PROACTIVE"))))).statusCode(200);
            call(account, "StartResourceEvaluation", evaluation("{}", "unsupported-rule"))
                    .statusCode(400).body("__type", equalTo("InvalidParameterValueException"));
            call(account, "ListResourceEvaluations", Map.of()).statusCode(200).body("ResourceEvaluations", hasSize(2));
        } finally {
            call(account, "DeleteConfigRule", Map.of("ConfigRuleName", "versioning")).statusCode(200);
            call(account, "DeleteConfigurationRecorder", Map.of("ConfigurationRecorderName", "recorder")).statusCode(200);
        }
    }

    @Test
    void invalidRequestsNeverBecomeEmptySuccess() {
        String account = "910000000008";
        for (String expression : List.of("DELETE resourceId", "SELECT resourceId WHERE resourceType LIKE 'AWS%'",
                "SELECT resourceId WHERE resourceType = 'x' OR resourceId = 'y'", "SELECT resourceId WHERE resourceType = 'x' AND")) {
            call(account, "SelectResourceConfig", Map.of("Expression", expression))
                    .statusCode(400).body("__type", equalTo("InvalidExpressionException"));
        }
        call(account, "SelectResourceConfig", Map.of("Expression", "SELECT resourceId", "Limit", 101))
                .statusCode(400).body("__type", equalTo("InvalidLimitException"));
        call(account, "ListDiscoveredResources", Map.of("resourceType", TYPE, "nextToken", "bad"))
                .statusCode(400).body("__type", equalTo("InvalidNextTokenException"));
        call(account, "ListDiscoveredResources", Map.of("resourceType", TYPE, "resourceName", "a", "resourceIds", List.of("a")))
                .statusCode(400).body("__type", equalTo("ValidationException"));
        call(account, "BatchGetResourceConfig", Map.of("resourceKeys", List.of()))
                .statusCode(400).body("__type", equalTo("ValidationException"));
        call(account, "GetResourceConfigHistory", Map.of("resourceType", TYPE, "resourceId", "a", "earlierTime", 2, "laterTime", 1))
                .statusCode(400).body("__type", equalTo("InvalidTimeRangeException"));
        call(account, "ListResourceEvaluations", Map.of("Filters", Map.of("TimeWindow", Map.of("StartTime", 2, "EndTime", 1))))
                .statusCode(400).body("__type", equalTo("InvalidTimeRangeException"));
        recorder(account, "recorder");
        channel(account);
        call(account, "StartConfigurationRecorder", Map.of("ConfigurationRecorderName", "recorder")).statusCode(200);
        try {
            call(account, "PutResourceConfig", put("a", "broken-json"))
                    .statusCode(400).body("__type", equalTo("ValidationException"));
            call(account, "PutResourceConfig", Map.of("ResourceId", "a", "ResourceType", "AWS::S3::Bucket",
                    "SchemaVersionId", "1", "Configuration", "{}"))
                    .statusCode(400).body("__type", equalTo("ValidationException"));
            call(account, "GetDiscoveredResourceCounts", Map.of()).statusCode(200).body("totalDiscoveredResources", equalTo(0));
        } finally {
            cleanupRecorder(account, "recorder");
        }
    }

    private static Map<String, Object> put(String id, String configuration) {
        return Map.of("ResourceType", TYPE, "ResourceId", id, "ResourceName", "name-" + id,
                "SchemaVersionId", "1.0", "Configuration", configuration, "Tags", Map.of("owner", "config-test"));
    }

    private static Map<String, String> delete(String id) {
        return Map.of("ResourceType", TYPE, "ResourceId", id);
    }

    private static Map<String, String> key(String id) {
        return Map.of("resourceType", TYPE, "resourceId", id);
    }

    private static Map<String, Object> evaluation(String configuration, String token) {
        return Map.of("EvaluationMode", "PROACTIVE", "ClientToken", token,
                "EvaluationContext", Map.of("EvaluationContextIdentifier", "config-test"),
                "ResourceDetails", Map.of("ResourceId", "bucket-probe", "ResourceType", "AWS::S3::Bucket",
                        "ResourceConfiguration", configuration, "ResourceConfigurationSchemaType", "CFN_RESOURCE_SCHEMA"));
    }

    private static void recorder(String account, String name) {
        call(account, "PutConfigurationRecorder", Map.of("ConfigurationRecorder", Map.of("name", name,
                "roleARN", "arn:aws:iam::" + account + ":role/config", "recordingGroup", Map.of("allSupported", true))))
                .statusCode(200);
    }

    private static void recorderFailure(String account, String name, String code) {
        call(account, "PutConfigurationRecorder", Map.of("ConfigurationRecorder", Map.of("name", name,
                "roleARN", "arn:aws:iam::" + account + ":role/config")))
                .statusCode(400).body("__type", equalTo(code));
    }

    private static void channel(String account) {
        call(account, "PutDeliveryChannel", Map.of("DeliveryChannel", Map.of("name", "channel", "s3BucketName", "config-bucket")))
                .statusCode(200);
    }

    private static void cleanupRecorder(String account, String name) {
        call(account, "StopConfigurationRecorder", Map.of("ConfigurationRecorderName", name)).statusCode(200);
        call(account, "DeleteDeliveryChannel", Map.of("DeliveryChannelName", "channel")).statusCode(200);
        call(account, "DeleteConfigurationRecorder", Map.of("ConfigurationRecorderName", name)).statusCode(200);
    }

    private static ValidatableResponse call(String account, String operation, Object body) {
        return call(account, REGION, operation, body);
    }

    private static ValidatableResponse call(String account, String region, String operation, Object body) {
        return given().header("X-Amz-Target", "StarlingDoveService." + operation)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20260921/" + region
                        + "/config/aws4_request, SignedHeaders=host, Signature=abc")
                .contentType("application/x-amz-json-1.1").body(body).post("/").then();
    }
}
