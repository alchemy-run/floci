package io.floci.aws.services.observabilityadmin;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CloudWatch Observability Admin: telemetry evaluation auditing Floci's resources, a VPC flow-log rule that turns on
 * flow logs, enrichment's Resource Explorer managed view, telemetry pipelines checked, tested and reading S3 objects
 * into CloudWatch Logs, organization centralization copying log events to another Region, and integrations.
 */
@QuarkusTest
class ObservabilityAdminIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification obs(String account) {
        return given().header("Authorization", auth(account, "observabilityadmin")).contentType("application/json");
    }

    static RequestSpecification obs() {
        return obs("000000000000");
    }

    static String role(String account, String name, String service) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"" + service + "\"},\"Action\":\"sts:AssumeRole\"}]}";
        return given().header("Authorization", auth(account, "iam")).formParam("Action", "CreateRole")
                .formParam("RoleName", name).formParam("AssumeRolePolicyDocument", trust).post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    static RequestSpecification ec2(String account, String action) {
        return given().header("Authorization", auth(account, "ec2")).formParam("Action", action)
                .formParam("Version", "2016-11-15");
    }

    static RequestSpecification logs(String account, String region, String action, String body) {
        return given().header("Authorization", auth(account, "logs").replace("us-east-1", region))
                .header("X-Amz-Target", "Logs_20140328." + action).contentType("application/x-amz-json-1.1")
                .body(body);
    }

    static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    static void awaitStatus(String account, String operation, String expected) {
        await().atMost(Duration.ofSeconds(20)).until(() -> expected.equals(obs(account).post("/" + operation)
                .then().extract().path("Status")));
    }

    @Test
    void evaluationAuditsResourcesAndRulesConfigureThem() {
        String vpc = ec2("000000000000", "CreateVpc").formParam("CidrBlock", "10.71.0.0/16").post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateVpcResponse.vpc.vpcId");
        obs().post("/ListResourceTelemetry").then().statusCode(400).body("message",
                containsString("isn't running"));
        obs().body("{\"RuleName\":\"early\",\"Rule\":{\"ResourceType\":\"AWS::EC2::VPC\",\"TelemetryType\":"
                + "\"Logs\"}}").post("/CreateTelemetryRule").then().statusCode(400);
        obs().post("/StartTelemetryEvaluation").then().statusCode(200);
        awaitStatus("000000000000", "GetTelemetryEvaluationStatus", "RUNNING");
        obs().post("/GetTelemetryEvaluationStatus").then().body("HomeRegion", equalTo("us-east-1"))
                .body("RegionStatuses[0].Status", equalTo("RUNNING"));
        obs().body("{\"ResourceTypes\":[\"AWS::EC2::VPC\"],\"ResourceIdentifierPrefix\":\"" + vpc + "\"}")
                .post("/ListResourceTelemetry").then().statusCode(200)
                .body("TelemetryConfigurations[0].ResourceIdentifier", equalTo(vpc))
                .body("TelemetryConfigurations[0].TelemetryConfigurationState.Logs", equalTo("Disabled"))
                .body("TelemetryConfigurations[0].TelemetrySourceType", equalTo("VPC_FLOW_LOGS"));

        String rule = "{\"ResourceType\":\"AWS::EC2::VPC\",\"TelemetryType\":\"Logs\",\"TelemetrySourceTypes\":"
                + "[\"VPC_FLOW_LOGS\"],\"DestinationConfiguration\":{\"DestinationType\":\"cloud-watch-logs\","
                + "\"RetentionInDays\":30}}";
        String arn = obs().body("{\"RuleName\":\"vpc-flow\",\"Rule\":" + rule + ",\"Tags\":{\"team\":\"net\"}}")
                .post("/CreateTelemetryRule").then().statusCode(200).extract().path("RuleArn");
        assertEquals("arn:aws:observabilityadmin:us-east-1:000000000000:telemetry-rule/vpc-flow", arn);
        obs().body("{\"RuleName\":\"vpc-flow\",\"Rule\":" + rule + "}").post("/CreateTelemetryRule").then()
                .statusCode(409);
        obs().body("{\"RuleName\":\"twin\",\"Rule\":" + rule + "}").post("/CreateTelemetryRule").then()
                .statusCode(409).body("message", containsString("vpc-flow"));
        obs().body("{\"RuleName\":\"mismatch\",\"Rule\":{\"ResourceType\":\"AWS::EC2::Instance\","
                + "\"TelemetryType\":\"Logs\",\"TelemetrySourceTypes\":[\"VPC_FLOW_LOGS\"]}}")
                .post("/CreateTelemetryRule").then().statusCode(400);

        await().atMost(Duration.ofSeconds(20)).until(() -> "Enabled".equals(obs().body("{\"ResourceTypes\":"
                + "[\"AWS::EC2::VPC\"],\"ResourceIdentifierPrefix\":\"" + vpc + "\"}").post("/ListResourceTelemetry")
                .then().extract().path("TelemetryConfigurations[0].TelemetryConfigurationState.Logs")));
        String flowLogs = ec2("000000000000", "DescribeFlowLogs").formParam("Filter.1.Name", "resource-id")
                .formParam("Filter.1.Value.1", vpc).post("/").then().statusCode(200).extract().asString();
        assertTrue(flowLogs.contains("<logGroupName>/aws/vpc</logGroupName>"), flowLogs);
        assertTrue(flowLogs.contains("CloudWatchTelemetryRuleManaged"), flowLogs);
        logs("000000000000", "us-east-1", "DescribeLogGroups", "{\"logGroupNamePrefix\":\"/aws/vpc\"}").post("/")
                .then().statusCode(200).body("logGroups[0].retentionInDays", equalTo(30));

        obs().body("{\"RuleIdentifier\":\"vpc-flow\",\"Rule\":" + rule.replace("30", "60") + "}")
                .post("/UpdateTelemetryRule").then().statusCode(200).body("RuleArn", equalTo(arn));
        obs().body("{\"RuleIdentifier\":\"" + arn + "\"}").post("/GetTelemetryRule").then().statusCode(200)
                .body("TelemetryRule.DestinationConfiguration.RetentionInDays", equalTo(60))
                .body("HomeRegion", equalTo("us-east-1")).body("RegionStatuses[0].Status", equalTo("ACTIVE"));
        obs().body("{\"RuleNamePrefix\":\"vpc\"}").post("/ListTelemetryRules").then().statusCode(200)
                .body("TelemetryRuleSummaries.RuleName", hasItem("vpc-flow"))
                .body("TelemetryRuleSummaries[0].ResourceType", equalTo("AWS::EC2::VPC"));
        obs().body("{\"ResourceARN\":\"" + arn + "\",\"Tags\":{\"env\":\"dev\"}}").post("/TagResource").then()
                .statusCode(200);
        obs().body("{\"ResourceARN\":\"" + arn + "\",\"TagKeys\":[\"team\"]}").post("/UntagResource").then()
                .statusCode(200);
        obs().body("{\"ResourceARN\":\"" + arn + "\"}").post("/ListTagsForResource").then().statusCode(200)
                .body("Tags.env", equalTo("dev")).body("Tags.team", equalTo(null));

        obs().post("/GetTelemetryEnrichmentStatus").then().statusCode(404);
        String view = obs().post("/StartTelemetryEnrichment").then().statusCode(202)
                .body("Status", equalTo("Running")).extract().path("AwsResourceExplorerManagedViewArn");
        assertTrue(view.contains(":managed-view/AWSManagedViewForCloudWatchTelemetry/"), view);
        given().header("Authorization", auth("000000000000", "resource-explorer-2")).contentType("application/json")
                .body("{}").post("/ListManagedViews").then().statusCode(200).body("ManagedViews", hasItem(view));
        given().header("Authorization", auth("000000000000", "resource-explorer-2")).contentType("application/json")
                .body("{\"ManagedViewArn\":\"" + view + "\"}").post("/GetManagedView").then().statusCode(200)
                .body("ManagedView.TrustedService", equalTo("observabilityadmin.amazonaws.com"));

        obs().body("{\"RuleIdentifier\":\"vpc-flow\"}").post("/DeleteTelemetryRule").then().statusCode(200);
        obs().body("{\"RuleIdentifier\":\"vpc-flow\"}").post("/GetTelemetryRule").then().statusCode(404);
        obs().post("/StopTelemetryEvaluation").then().statusCode(200);
        awaitStatus("000000000000", "GetTelemetryEvaluationStatus", "STOPPED");
        obs().post("/GetTelemetryEnrichmentStatus").then().statusCode(200).body("Status", equalTo("Impaired"));
        obs().post("/StopTelemetryEnrichment").then().statusCode(202).body("Status", equalTo("Stopped"));
        given().header("Authorization", auth("000000000000", "resource-explorer-2")).contentType("application/json")
                .body("{}").post("/ListManagedViews").then().statusCode(200).body("ManagedViews", not(hasItem(view)));
    }

    static final String LOG_PIPELINE = """
            pipeline:
              source:
                cloudwatch_logs:
                  log_event_metadata:
                    data_source_name: "my_app"
                    data_source_type: "default"
              processor:
                - parse_json: {}
                - add_entries:
                    entries:
                      - key: "severity"
                        value: "high"
                        when: "level == 'ERROR'"
                      - key: "severity"
                        value: "low"
                        when_else: "level == 'ERROR'"
                - drop_events:
                    when: "path =~ '^/health'"
                - delete_entries:
                    with_keys: ["secret"]
                - move_keys:
                    entries:
                      - from_key: "user.id"
                        to_key: "user_id"
              sink:
                - cloudwatch_logs:
                    log_group: "@original"
            """;

    @Test
    void pipelinesAreCheckedAndRunTheirProcessors() {
        String bad = LOG_PIPELINE.replace("- delete_entries:", "- grok:\n        match:\n          msg: [\"%{WORD:w}\"]\n"
                + "    - csv: {}\n    - delete_entries:").replace("\"@original\"", "\"elsewhere\"");
        List<String> reasons = obs().body("{\"Configuration\":{\"Body\":" + json(bad) + "}}")
                .post("/ValidateTelemetryPipelineConfiguration").then().statusCode(200).extract()
                .path("Errors.Reason");
        assertTrue(reasons.contains("PARSER_POSITION") && reasons.contains("TOO_MANY_PARSERS")
                && reasons.contains("INVALID_SINK"), reasons.toString());
        obs().body("{\"Configuration\":{\"Body\":" + json(LOG_PIPELINE) + "}}")
                .post("/ValidateTelemetryPipelineConfiguration").then().statusCode(200).body("Errors", empty());
        obs().body("{\"Configuration\":{\"Body\":" + json(LOG_PIPELINE.replace("level == 'ERROR'\"\n",
                "level === 'ERROR'\"\n")) + "}}").post("/ValidateTelemetryPipelineConfiguration").then()
                .statusCode(200).body("Errors[0].Reason", equalTo("INVALID_CONDITION"));

        obs().body("{\"SignalType\":\"LOG\",\"Configuration\":{\"Body\":" + json(LOG_PIPELINE) + "},\"Records\":["
                        + "{\"Type\":\"STRING\",\"Data\":" + json("{\"level\":\"ERROR\",\"path\":\"/pay\",\"secret\":"
                        + "\"x\",\"user\":{\"id\":7}}") + "},"
                        + "{\"Type\":\"STRING\",\"Data\":" + json("{\"level\":\"INFO\",\"path\":\"/healthz\"}") + "},"
                        + "{\"Type\":\"STRING\",\"Data\":" + json("{\"level\":\"INFO\",\"path\":\"/pay\"}") + "},"
                        + "{\"Type\":\"STRING\",\"Data\":\"not json\"}]}")
                .post("/TestTelemetryPipeline").then().statusCode(200).body("Results", hasSize(4))
                .body("Results[0].Record.Type", equalTo("JSON"))
                .body("Results[0].Record.Data", equalTo("{\"level\":\"ERROR\",\"path\":\"/pay\",\"user\":{},"
                        + "\"severity\":\"high\",\"user_id\":7}"))
                .body("Results[1].Record", equalTo(null)).body("Results[1].Error", equalTo(null))
                .body("Results[2].Record.Data", containsString("\"severity\":\"low\""))
                .body("Results[3].Error.Message", containsString("isn't JSON"));

        String grok = "pipeline:\n  source:\n    cloudwatch_logs:\n      log_event_metadata:\n"
                + "        data_source_name: \"app\"\n  processor:\n    - grok:\n        match:\n"
                + "          message: [\"%{IP:client} %{WORD:verb} %{URIPATH:path} %{NUMBER:status}\"]\n"
                + "    - convert_entry_type:\n        key: \"status\"\n        type: \"integer\"\n"
                + "    - uppercase_string:\n        with_keys: [\"verb\"]\n  sink:\n    - cloudwatch_logs:\n"
                + "        log_group: \"@original\"\n";
        obs().body("{\"Configuration\":{\"Body\":" + json(grok) + "},\"Records\":[{\"Type\":\"STRING\",\"Data\":"
                        + "\"10.0.0.7 get /orders 503\"}]}").post("/TestTelemetryPipeline").then().statusCode(200)
                .body("Results[0].Record.Data", equalTo("{\"client\":\"10.0.0.7\",\"verb\":\"GET\",\"path\":"
                        + "\"/orders\",\"status\":503}"));

        String arn = obs().body("{\"Name\":\"app-logs\",\"Configuration\":{\"Body\":" + json(LOG_PIPELINE)
                        + "},\"Tags\":{\"team\":\"obs\"}}").post("/CreateTelemetryPipeline").then().statusCode(200)
                .extract().path("Arn");
        assertEquals("arn:aws:observabilityadmin:us-east-1:000000000000:telemetry-pipeline/app-logs", arn);
        obs().body("{\"Name\":\"app-logs\",\"Configuration\":{\"Body\":" + json(LOG_PIPELINE) + "}}")
                .post("/CreateTelemetryPipeline").then().statusCode(409);
        obs().body("{\"Name\":\"broken\",\"Configuration\":{\"Body\":" + json(bad) + "}}")
                .post("/CreateTelemetryPipeline").then().statusCode(400).body("Errors.Reason", hasItem(
                        "TOO_MANY_PARSERS"));
        await().atMost(Duration.ofSeconds(20)).until(() -> "ACTIVE".equals(obs().body("{\"PipelineIdentifier\":"
                + "\"app-logs\"}").post("/GetTelemetryPipeline").then().extract().path("Pipeline.Status")));
        obs().post("/ListTelemetryPipelines").then().statusCode(200)
                .body("PipelineSummaries.find { it.Name == 'app-logs' }.ConfigurationSummary.ProcessorCount",
                        equalTo(5))
                .body("PipelineSummaries.find { it.Name == 'app-logs' }.ConfigurationSummary.Sources[0].Type",
                        equalTo("cloudwatch_logs"))
                .body("PipelineSummaries.find { it.Name == 'app-logs' }.Tags.team", equalTo("obs"));
        obs().body("{\"PipelineIdentifier\":\"" + arn + "\",\"Configuration\":{\"Body\":" + json(LOG_PIPELINE
                .replace("    - drop_events:\n        when: \"path =~ '^/health'\"\n", "")) + "}}")
                .post("/UpdateTelemetryPipeline").then().statusCode(200);
        obs().body("{\"PipelineIdentifier\":\"app-logs\"}").post("/DeleteTelemetryPipeline").then().statusCode(409);
        await().atMost(Duration.ofSeconds(20)).until(() -> "ACTIVE".equals(obs().body("{\"PipelineIdentifier\":"
                + "\"app-logs\"}").post("/GetTelemetryPipeline").then().extract().path("Pipeline.Status")));
        obs().body("{\"PipelineIdentifier\":\"app-logs\"}").post("/DeleteTelemetryPipeline").then().statusCode(200);
        await().atMost(Duration.ofSeconds(20)).until(() -> obs().body("{\"PipelineIdentifier\":\"app-logs\"}")
                .post("/GetTelemetryPipeline").then().extract().statusCode() == 404);
    }

    @Test
    void s3SourcePipelinesWriteToCloudWatchLogs() {
        String account = "000000000000";
        String queueUrl = given().header("Authorization", auth(account, "sqs"))
                .header("X-Amz-Target", "AmazonSQS.CreateQueue").contentType("application/x-amz-json-1.0")
                .body("{\"QueueName\":\"pipeline-objects\"}").post("/").then().statusCode(200).extract()
                .path("QueueUrl");
        given().header("Authorization", auth(account, "s3")).put("/pipeline-objects").then().statusCode(200);
        given().header("Authorization", auth(account, "s3")).header("x-amz-skip-destination-validation", "true")
                .contentType("application/xml").queryParam("notification", "")
                .body("<NotificationConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                        + "<QueueConfiguration><Id>p</Id><Queue>arn:aws:sqs:us-east-1:000000000000:pipeline-objects"
                        + "</Queue><Event>s3:ObjectCreated:*</Event></QueueConfiguration></NotificationConfiguration>")
                .put("/pipeline-objects").then().statusCode(200);
        logs(account, "us-east-1", "CreateLogGroup", "{\"logGroupName\":\"/pipelines/orders\"}").post("/").then()
                .statusCode(200);
        String role = role(account, "pipeline-reader", "telemetry-pipelines.observabilityadmin.amazonaws.com");
        String untrusted = role(account, "pipeline-untrusted", "lambda.amazonaws.com");
        String body = "pipeline:\n  source:\n    s3:\n      notification_type: \"sqs\"\n      sqs:\n"
                + "        queue_url: \"" + queueUrl + "\"\n      codec:\n        ndjson: {}\n      aws:\n"
                + "        region: \"us-east-1\"\n        sts_role_arn: \"" + role + "\"\n  processor:\n"
                + "    - add_entries:\n        entries:\n          - key: \"pipeline\"\n            value: \"orders\"\n"
                + "  sink:\n    - cloudwatch_logs:\n        log_group: \"/pipelines/orders\"\n";
        obs().body("{\"Name\":\"untrusted\",\"Configuration\":{\"Body\":" + json(body.replace(role, untrusted)) + "}}")
                .post("/CreateTelemetryPipeline").then().statusCode(400).body("Errors[0].Reason",
                        equalTo("INVALID_ROLE"));
        obs().body("{\"Name\":\"orders\",\"Configuration\":{\"Body\":" + json(body.replace("/pipelines/orders\"",
                "/pipelines/missing\"")) + "}}").post("/CreateTelemetryPipeline").then().statusCode(400)
                .body("Errors[0].Reason", equalTo("LOG_GROUP_NOT_FOUND"));
        obs().body("{\"Name\":\"orders\",\"Configuration\":{\"Body\":" + json(body) + "}}")
                .post("/CreateTelemetryPipeline").then().statusCode(200);
        await().atMost(Duration.ofSeconds(20)).until(() -> "ACTIVE".equals(obs().body("{\"PipelineIdentifier\":"
                + "\"orders\"}").post("/GetTelemetryPipeline").then().extract().path("Pipeline.Status")));
        given().header("Authorization", auth(account, "s3")).contentType("text/plain")
                .body("{\"order\":1}\n{\"order\":2}\n").put("/pipeline-objects/batch-1.ndjson").then().statusCode(200);
        await().atMost(Duration.ofSeconds(30)).until(() -> logs(account, "us-east-1", "GetLogEvents",
                "{\"logGroupName\":\"/pipelines/orders\",\"logStreamName\":\"orders\",\"startFromHead\":true}")
                .post("/").then().extract().statusCode() == 200 && logs(account, "us-east-1", "GetLogEvents",
                "{\"logGroupName\":\"/pipelines/orders\",\"logStreamName\":\"orders\",\"startFromHead\":true}")
                .post("/").then().extract().<List<Object>>path("events").size() == 2);
        logs(account, "us-east-1", "GetLogEvents", "{\"logGroupName\":\"/pipelines/orders\",\"logStreamName\":"
                + "\"orders\",\"startFromHead\":true}").post("/").then().statusCode(200)
                .body("events[0].message", equalTo("{\"order\":1,\"pipeline\":\"orders\"}"));
        obs().body("{\"PipelineIdentifier\":\"orders\"}").post("/DeleteTelemetryPipeline").then().statusCode(200);
    }

    private static RequestSpecification organizations(String account, String action, String body) {
        return given().header("Authorization", auth(account, "organizations"))
                .header("X-Amz-Target", "AWSOrganizationsV20161128." + action)
                .contentType("application/x-amz-json-1.1").body(body);
    }

    @Test
    void organizationCentralizationCopiesLogEvents() {
        String management = "171717171717";
        obs(management).post("/StartTelemetryEvaluationForOrganization").then().statusCode(400)
                .body("__type", containsString("AccessDeniedException"));
        organizations(management, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}").post("/").then().statusCode(200);
        obs(management).post("/StartTelemetryEvaluationForOrganization").then().statusCode(400)
                .body("message", containsString("Trusted access"));
        organizations(management, "EnableAWSServiceAccess", "{\"ServicePrincipal\":"
                + "\"observabilityadmin.amazonaws.com\"}").post("/").then().statusCode(200);
        obs(management).post("/StartTelemetryEvaluationForOrganization").then().statusCode(200);
        awaitStatus(management, "GetTelemetryEvaluationStatusForOrganization", "RUNNING");
        obs(management).body("{\"AccountIdentifiers\":[\"" + management + "\"]}")
                .post("/ListResourceTelemetryForOrganization").then().statusCode(200);
        String orgRule = obs(management).body("{\"RuleName\":\"org-vpc\",\"Rule\":{\"ResourceType\":"
                        + "\"AWS::EC2::VPC\",\"TelemetryType\":\"Logs\",\"TelemetrySourceTypes\":[\"VPC_FLOW_LOGS\"],"
                        + "\"DestinationConfiguration\":{\"DestinationType\":\"cloud-watch-logs\"},\"Scope\":"
                        + "\"AccountId = '" + management + "'\"}}").post("/CreateTelemetryRuleForOrganization").then()
                .statusCode(200).extract().path("RuleArn");
        assertTrue(orgRule.endsWith(":organization-telemetry-rule/org-vpc"), orgRule);
        obs(management).body("{}").post("/ListTelemetryRulesForOrganization").then().statusCode(200)
                .body("TelemetryRuleSummaries.RuleName", hasItem("org-vpc"));
        obs(management).body("{\"RuleName\":\"bad-scope\",\"Rule\":{\"ResourceType\":\"AWS::EC2::VPC\","
                + "\"TelemetryType\":\"Logs\",\"Scope\":\"AccountId ==\"}}").post("/CreateTelemetryRuleForOrganization")
                .then().statusCode(400);

        String rule = "{\"Source\":{\"Regions\":[\"us-east-1\"],\"SourceLogsConfiguration\":{"
                + "\"LogGroupSelectionCriteria\":\"LogGroupName LIKE '/central/%'\",\"EncryptedLogGroupStrategy\":"
                + "\"SKIP\"}},\"Destination\":{\"Region\":\"us-west-2\",\"Account\":\"" + management + "\","
                + "\"DestinationLogsConfiguration\":{\"LogGroupNameConfiguration\":{\"LogGroupNamePattern\":"
                + "\"/collected/${source.accountId}${source.logGroup}\"}}}}";
        String arn = obs(management).body("{\"RuleName\":\"collect\",\"Rule\":" + rule + "}")
                .post("/CreateCentralizationRuleForOrganization").then().statusCode(200).extract().path("RuleArn");
        assertTrue(arn.endsWith(":organization-centralization-rule/collect"), arn);
        await().atMost(Duration.ofSeconds(20)).until(() -> "Healthy".equals(obs(management)
                .body("{\"RuleIdentifier\":\"collect\"}").post("/GetCentralizationRuleForOrganization").then()
                .extract().path("RuleHealth")));
        obs(management).body("{}").post("/ListCentralizationRulesForOrganization").then().statusCode(200)
                .body("CentralizationRuleSummaries[0].DestinationRegion", equalTo("us-west-2"));

        logs(management, "us-east-1", "CreateLogGroup", "{\"logGroupName\":\"/central/app\"}").post("/").then()
                .statusCode(200);
        logs(management, "us-east-1", "CreateLogStream", "{\"logGroupName\":\"/central/app\",\"logStreamName\":"
                + "\"s1\"}").post("/").then().statusCode(200);
        logs(management, "us-east-1", "PutLogEvents", "{\"logGroupName\":\"/central/app\",\"logStreamName\":\"s1\","
                + "\"logEvents\":[{\"timestamp\":" + System.currentTimeMillis() + ",\"message\":\"hello central\"}]}")
                .post("/").then().statusCode(200);
        logs(management, "us-west-2", "GetLogEvents", "{\"logGroupName\":\"/collected/" + management
                + "/central/app\",\"logStreamName\":\"" + management + "_us-east-1_s1\",\"startFromHead\":true}")
                .post("/").then().statusCode(200).body("events[0].message", equalTo("hello central"));

        obs(management).body("{\"RuleIdentifier\":\"collect\"}").post("/DeleteCentralizationRuleForOrganization")
                .then().statusCode(200);
        obs(management).body("{\"RuleIdentifier\":\"org-vpc\"}").post("/DeleteTelemetryRuleForOrganization").then()
                .statusCode(200);
        obs(management).post("/StopTelemetryEvaluationForOrganization").then().statusCode(200);
        obs().post("/ListCentralizationRulesForOrganization").then().statusCode(400);
    }

    @Test
    void integrationsOwnTheirResources() {
        String role = role("000000000000", "telemetry-tables", "observabilityadmin.amazonaws.com");
        obs().body("{\"RoleArn\":\"" + role + "\",\"Encryption\":{\"SseAlgorithm\":\"aws:kms\"}}")
                .post("/CreateS3TableIntegration").then().statusCode(400);
        String arn = obs().body("{\"RoleArn\":\"" + role + "\",\"Encryption\":{\"SseAlgorithm\":\"AES256\"}}")
                .post("/CreateS3TableIntegration").then().statusCode(200).extract().path("Arn");
        String bucket = obs().body("{\"Arn\":\"" + arn + "\"}").post("/GetS3TableIntegration").then().statusCode(200)
                .body("Status", equalTo("ACTIVE")).body("Encryption.SseAlgorithm", equalTo("AES256"))
                .extract().path("DestinationTableBucketArn");
        assertTrue(bucket.startsWith("arn:aws:s3tables:us-east-1:000000000000:bucket/cloudwatch-"), bucket);
        obs().body("{\"RoleArn\":\"" + role + "\",\"Encryption\":{\"SseAlgorithm\":\"AES256\"}}")
                .post("/CreateS3TableIntegration").then().statusCode(402);
        obs().post("/ListS3TableIntegrations").then().statusCode(200).body("IntegrationSummaries.Arn", hasItem(arn));
        obs().body("{\"Arn\":\"" + arn + "\"}").post("/DeleteS3TableIntegration").then().statusCode(204);
        await().atMost(Duration.ofSeconds(20)).until(() -> obs().body("{\"Arn\":\"" + arn + "\"}")
                .post("/GetS3TableIntegration").then().extract().statusCode() == 404);

        String dataset = obs().body("{\"RoleArn\":\"" + role + "\",\"Tags\":{\"k\":\"v\"}}")
                .post("/CreateDatasetIntegration").then().statusCode(201).body("RoleArn", equalTo(role))
                .extract().path("Arn");
        assertTrue(dataset.contains(":dataset-integration/"), dataset);
        obs().body("{\"Arn\":\"" + dataset + "\",\"RoleArn\":\"" + role + "\"}").post("/UpdateDatasetIntegration")
                .then().statusCode(200);
        obs().post("/ListDatasetIntegrations").then().statusCode(200)
                .body("DatasetIntegrationSummaries.Arn", hasItem(dataset));
        obs().body("{\"ResourceARN\":\"" + dataset + "\"}").post("/ListTagsForResource").then().statusCode(200)
                .body("Tags.k", equalTo("v"));
        obs().body("{\"Arn\":\"" + dataset + "\"}").post("/DeleteDatasetIntegration").then().statusCode(204);
        obs().body("{\"Arn\":\"" + dataset + "\"}").post("/GetDatasetIntegration").then().statusCode(404);
        obs().body("{\"RoleArn\":\"arn:aws:iam::000000000000:role/absent\"}").post("/CreateDatasetIntegration")
                .then().statusCode(400).body("message", startsWith("The role"));
    }
}
