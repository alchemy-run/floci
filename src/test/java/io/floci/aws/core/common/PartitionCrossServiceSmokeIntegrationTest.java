package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.PartitionMatrix;
import io.floci.aws.testing.PartitionMatrix.PartitionCase;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One resource per service, created by a request signed in each non-commercial partition, must
 * come back with an ARN (or a host) of that partition and region. The literal gate only catches
 * {@code arn:aws:} written in source; this catches a service that mints through the shared
 * helpers with the wrong region source. A service is skipped only in a partition the published
 * data says does not offer it.
 *
 * <p>{@link #COVERED_PACKAGES} names the service packages this class covers;
 * {@code PartitionSmokeInventoryTest} requires every package that mints ARNs or hosts to be here
 * or in {@code partition/smoke-exemptions.tsv}.
 */
@QuarkusTest
class PartitionCrossServiceSmokeIntegrationTest {

    static final Set<String> COVERED_PACKAGES = Set.of(
            "accessanalyzer", "acm", "acmpca", "aiops", "amazonmq", "amplify", "appflow", "appregistry", "apprunner", "auditmanager", "b2bi", "backupsearch", "bedrockdataautomation", "chatbot", "codeconnections", "databrew", "dataexchange", "datazone", "deadline", "devopsguru", "directoryservice", "docdbelastic", "ecrpublic", "emrcontainers", "entityresolution", "fms", "forecast", "fsx", "glacier", "grafana", "greengrassv2", "healthlake", "imagebuilder", "internetmonitor", "iotfleetwise", "iotmanagedintegrations", "iotsitewise", "iotwireless", "kendra", "keyspaces", "kinesisvideo", "lexv2", "licensemanager", "airflowserverless", "mailmanager", "mediaconnect", "mediaconvert", "medialive", "mediapackagev2", "mediatailor", "medicalimaging", "neptunegraph", "notifications", "notificationscontacts", "osis", "observabilityadmin", "omics", "opensearchserverless", "personalize", "pinpointsmsvoicev2", "qbusiness", "ivs", "ivschat", "dax", "bedrockagent", "comprehend", "location", "polly", "rekognition",
            "textract", "transcribe", "translate", "apigateway", "apigatewayv2", "applicationautoscaling", "appsync",
            "aps", "autoscaling", "backup", "batch", "bcmdataexports", "bedrock", "bedrockagentcorecontrol", "ce",
            "cloudfront", "cloudmap", "cloudwatch", "codebuild", "codedeploy", "codepipeline", "cognito",
            "configservice", "connect", "controlcatalog", "datasync", "dlm", "dms", "dsql", "dynamodb", "ec2", "ecr",
            "ecs", "efs", "eks", "elasticache", "elasticbeanstalk", "elb", "elbv2", "emr", "emrserverless",
            "eventbridge", "eventbridgev2", "firehose", "fis", "glue", "iot", "kinesis", "kinesisanalytics",
            "lambda", "lambdaweb", "memorydb", "msk", "mwaa", "oam", "opensearch", "organizations",
            "paymentcryptography", "pipes", "quicksight", "ram", "rds", "redshift", "resourceexplorer2", "resourcegroups",
            "s3tables", "s3vectors", "scheduler", "secretsmanager", "ses", "sns", "sqs", "stepfunctions",
            "timestreaminfluxdb", "transfer", "verifiedpermissions", "wafv2");

    private static final String JSON_1_0 = "application/x-amz-json-1.0";
    private static final String JSON_1_1 = "application/x-amz-json-1.1";
    private static final String JSON = "application/json";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void quickSightDashboardArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "quicksight");
        String id = unique("smoke-dashboard", partitionCase);
        String path = "/accounts/000000000000/dashboards/" + id;
        String arn = signed(region, "quicksight").contentType(JSON)
                .body("{\"Name\":\"Smoke dashboard\",\"Definition\":{}}")
                .post(path).then().statusCode(202).extract().jsonPath().getString("Arn");
        cleanup.register(() -> signed(region, "quicksight").delete(path));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void resourceGroupArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "resource-groups");
        String name = unique("smoke-pool", partitionCase);
        String body = "{\"Name\":\"" + name + "\",\"Configuration\":["
                + "{\"Type\":\"AWS::EC2::CapacityReservationPool\"},"
                + "{\"Type\":\"AWS::ResourceGroups::Generic\",\"Parameters\":["
                + "{\"Name\":\"allowed-resource-types\",\"Values\":[\"AWS::EC2::CapacityReservation\"]}]}]}";
        String arn = signed(region, "resource-groups").contentType(JSON).body(body)
                .when().post("/groups").then().statusCode(200).extract().path("Group.GroupArn");
        cleanup.register(() -> signed(region, "resource-groups").contentType(JSON)
                .body("{\"Group\":\"" + arn + "\"}").when().post("/delete-group"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void paymentCryptographyKeyArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "payment-cryptography");
        String arn = signed(region, "payment-cryptography")
            .header("X-Amz-Target", "PaymentCryptographyControlPlane.CreateKey")
            .contentType("application/x-amz-json-1.0")
            .body("{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\","
                + "\"KeyClass\":\"SYMMETRIC_KEY\","
                + "\"KeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\","
                + "\"KeyModesOfUse\":{\"Encrypt\":true,\"Decrypt\":true}},"
                + "\"Exportable\":true}")
        .when().post("/").then().statusCode(200)
            .extract().path("Key.KeyArn");
        cleanup.register(() -> signed(region, "payment-cryptography")
            .header("X-Amz-Target", "PaymentCryptographyControlPlane.DeleteKey")
            .contentType("application/x-amz-json-1.0")
            .body("{\"KeyIdentifier\":\"" + arn + "\"}")
        .when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void snsTopicArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "sns");
        String name = unique("smoke-topic", partitionCase);
        String arn = signed(region, "sns")
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", name)
        .when().post("/").then().statusCode(200)
            .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");
        cleanup.register(() -> signed(region, "sns")
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteTopic")
            .formParam("TopicArn", arn)
        .when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void kinesisStreamArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "kinesis");
        String name = unique("smoke-stream", partitionCase);
        json11(region, "kinesis", "Kinesis_20131202.CreateStream",
                "{\"StreamName\":\"" + name + "\",\"ShardCount\":1}")
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> json11(region, "kinesis", "Kinesis_20131202.DeleteStream",
                "{\"StreamName\":\"" + name + "\"}").when().post("/"));
        String arn = json11(region, "kinesis", "Kinesis_20131202.DescribeStreamSummary",
                "{\"StreamName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("StreamDescriptionSummary.StreamARN");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void firehoseDeliveryStreamArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "firehose");
        String name = unique("smoke-firehose", partitionCase);
        String arn = json11(region, "firehose", "Firehose_20150804.CreateDeliveryStream",
                "{\"DeliveryStreamName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("DeliveryStreamARN");
        cleanup.register(() -> json11(region, "firehose", "Firehose_20150804.DeleteDeliveryStream",
                "{\"DeliveryStreamName\":\"" + name + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void httpApiEndpointHost(PartitionCase partitionCase) {
        String region = offered(partitionCase, "apigateway");
        String name = unique("smoke-http-api", partitionCase);
        Response created = signed(region, "apigateway")
            .contentType(JSON)
            .body("{\"name\":\"" + name + "\",\"protocolType\":\"HTTP\"}")
        .when().post("/v2/apis").then().statusCode(201).extract().response();
        String apiId = created.jsonPath().getString("apiId");
        cleanup.register(() -> signed(region, "apigateway").when().delete("/v2/apis/" + apiId));
        String host = URI.create(created.jsonPath().getString("apiEndpoint")).getHost();
        PartitionMatrix.assertHostIn(partitionCase, host);
        assertEquals(apiId + ".execute-api." + region + "." + partitionCase.dnsSuffix(), host);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void elbv2TargetGroupArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "elasticloadbalancing");
        String name = unique("smoke-tg", partitionCase);
        String arn = signed(region, "elasticloadbalancing")
            .formParam("Action", "CreateTargetGroup")
            .formParam("Version", "2015-12-01")
            .formParam("Name", name)
            .formParam("Protocol", "HTTP")
            .formParam("Port", "80")
            .formParam("VpcId", "vpc-00000001")
            .formParam("TargetType", "ip")
        .when().post("/").then().statusCode(200)
            .extract().xmlPath()
            .getString("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
        cleanup.register(() -> signed(region, "elasticloadbalancing")
            .formParam("Action", "DeleteTargetGroup")
            .formParam("Version", "2015-12-01")
            .formParam("TargetGroupArn", arn)
        .when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void elbClassicLoadBalancerArnInAcmInUseBy(PartitionCase partitionCase) {
        String region = offered(partitionCase, "acm");
        offered(partitionCase, "elasticloadbalancing");
        String certificateArn = json11(region, "acm", "CertificateManager.RequestCertificate",
                "{\"DomainName\":\"" + unique("smoke-elb", partitionCase) + ".example.com\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("CertificateArn");
        cleanup.register(() -> json11(region, "acm", "CertificateManager.DeleteCertificate",
                "{\"CertificateArn\":\"" + certificateArn + "\"}").when().post("/"));
        String name = unique("elb", partitionCase);
        signed(region, "elasticloadbalancing")
            .formParam("Action", "CreateLoadBalancer")
            .formParam("Version", "2012-06-01")
            .formParam("LoadBalancerName", name)
            .formParam("AvailabilityZones.member.1", region + "a")
            .formParam("Listeners.member.1.Protocol", "HTTPS")
            .formParam("Listeners.member.1.LoadBalancerPort", "443")
            .formParam("Listeners.member.1.InstancePort", "8080")
            .formParam("Listeners.member.1.SSLCertificateId", certificateArn)
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> signed(region, "elasticloadbalancing")
            .formParam("Action", "DeleteLoadBalancer")
            .formParam("Version", "2012-06-01")
            .formParam("LoadBalancerName", name)
        .when().post("/"));
        String arn = json11(region, "acm", "CertificateManager.DescribeCertificate",
                "{\"CertificateArn\":\"" + certificateArn + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Certificate.InUseBy[0]");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void glueSchemaRegistryArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "glue");
        String name = unique("smoke-registry", partitionCase);
        String arn = json11(region, "glue", "AWSGlue.CreateRegistry", "{\"RegistryName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("RegistryArn");
        cleanup.register(() -> json11(region, "glue", "AWSGlue.DeleteRegistry",
                "{\"RegistryId\":{\"RegistryName\":\"" + name + "\"}}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void iotThingArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "iot");
        String name = unique("smoke-thing", partitionCase);
        String arn = signed(region, "iot")
            .contentType(JSON)
            .body("{}")
        .when().post("/things/" + name).then().statusCode(200)
            .extract().jsonPath().getString("thingArn");
        cleanup.register(() -> signed(region, "iot").when().delete("/things/" + name));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void acmCertificateArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "acm");
        String domain = unique("smoke-cert", partitionCase) + ".example.com";
        String arn = json11(region, "acm", "CertificateManager.RequestCertificate",
                "{\"DomainName\":\"" + domain + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("CertificateArn");
        cleanup.register(() -> json11(region, "acm", "CertificateManager.DeleteCertificate",
                "{\"CertificateArn\":\"" + arn + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void codeBuildProjectArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "codebuild");
        String name = unique("smoke-project", partitionCase);
        String body = """
                {
                  "name": "%s",
                  "source": {"type": "S3", "location": "smoke-bucket/source.zip"},
                  "artifacts": {"type": "NO_ARTIFACTS"},
                  "environment": {"type": "LINUX_CONTAINER", "image": "aws/codebuild/standard:7.0",
                                  "computeType": "BUILD_GENERAL1_SMALL"},
                  "serviceRole": "arn:%s:iam::000000000000:role/codebuild-role"
                }
                """.formatted(name, partitionCase.partition());
        String arn = json11(region, "codebuild", "CodeBuild_20161006.CreateProject", body)
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("project.arn");
        cleanup.register(() -> json11(region, "codebuild", "CodeBuild_20161006.DeleteProject",
                "{\"name\":\"" + name + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void batchComputeEnvironmentArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "batch");
        String name = unique("smoke-ce", partitionCase);
        String arn = signed(region, "batch")
            .contentType(JSON)
            .body("{\"computeEnvironmentName\":\"" + name + "\",\"type\":\"MANAGED\","
                    + "\"computeResources\":{\"type\":\"FARGATE\",\"maxvCpus\":4}}")
        .when().post("/v1/createcomputeenvironment").then().statusCode(200)
            .extract().jsonPath().getString("computeEnvironmentArn");
        cleanup.register(() -> {
            signed(region, "batch")
                .contentType(JSON)
                .body("{\"computeEnvironment\":\"" + name + "\",\"state\":\"DISABLED\"}")
            .when().post("/v1/updatecomputeenvironment");
            signed(region, "batch")
                .contentType(JSON)
                .body("{\"computeEnvironment\":\"" + name + "\"}")
            .when().post("/v1/deletecomputeenvironment");
        });
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void efsFileSystemArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "elasticfilesystem");
        Response created = signed(region, "elasticfilesystem")
            .contentType(JSON)
            .body("{\"CreationToken\":\"" + unique("smoke-efs", partitionCase) + "\"}")
        .when().post("/2015-02-01/file-systems").then().statusCode(201).extract().response();
        String fileSystemId = created.jsonPath().getString("FileSystemId");
        cleanup.register(() -> signed(region, "elasticfilesystem")
            .when().delete("/2015-02-01/file-systems/" + fileSystemId));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("FileSystemArn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void sqsQueueArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "sqs");
        String name = unique("smoke-queue", partitionCase);
        String queueUrl = json10(region, "sqs", "AmazonSQS.CreateQueue", "{\"QueueName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("QueueUrl");
        cleanup.register(() -> json10(region, "sqs", "AmazonSQS.DeleteQueue",
                "{\"QueueUrl\":\"" + queueUrl + "\"}").when().post("/"));
        String arn = json10(region, "sqs", "AmazonSQS.GetQueueAttributes",
                "{\"QueueUrl\":\"" + queueUrl + "\",\"AttributeNames\":[\"QueueArn\"]}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Attributes.QueueArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void secretsManagerSecretArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "secretsmanager");
        String name = unique("smoke-secret", partitionCase);
        String arn = json11(region, "secretsmanager", "secretsmanager.CreateSecret",
                "{\"Name\":\"" + name + "\",\"SecretString\":\"smoke\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("ARN");
        cleanup.register(() -> json11(region, "secretsmanager", "secretsmanager.DeleteSecret",
                "{\"SecretId\":\"" + arn + "\",\"ForceDeleteWithoutRecovery\":true}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void dynamoDbTableArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "dynamodb");
        String name = unique("smoke-table", partitionCase);
        String body = """
                {
                  "TableName": "%s",
                  "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
                  "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
                  "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(name);
        String arn = json10(region, "dynamodb", "DynamoDB_20120810.CreateTable", body)
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("TableDescription.TableArn");
        cleanup.register(() -> json10(region, "dynamodb", "DynamoDB_20120810.DeleteTable",
                "{\"TableName\":\"" + name + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void cloudWatchAlarmArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "monitoring");
        String name = unique("smoke-alarm", partitionCase);
        signed(region, "monitoring")
            .formParam("Action", "PutMetricAlarm")
            .formParam("Version", "2010-08-01")
            .formParam("AlarmName", name)
            .formParam("Namespace", "Smoke")
            .formParam("MetricName", "Requests")
            .formParam("Statistic", "Sum")
            .formParam("Period", "60")
            .formParam("EvaluationPeriods", "1")
            .formParam("Threshold", "1")
            .formParam("ComparisonOperator", "GreaterThanThreshold")
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> signed(region, "monitoring")
            .formParam("Action", "DeleteAlarms")
            .formParam("Version", "2010-08-01")
            .formParam("AlarmNames.member.1", name)
        .when().post("/"));
        String arn = signed(region, "monitoring")
            .formParam("Action", "DescribeAlarms")
            .formParam("Version", "2010-08-01")
            .formParam("AlarmNames.member.1", name)
        .when().post("/").then().statusCode(200)
            .extract().xmlPath()
            .getString("DescribeAlarmsResponse.DescribeAlarmsResult.MetricAlarms.member.AlarmArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void cloudWatchDashboardArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "monitoring");
        String name = unique("smoke-dashboard", partitionCase);
        signed(region, "monitoring")
            .formParam("Action", "PutDashboard")
            .formParam("Version", "2010-08-01")
            .formParam("DashboardName", name)
            .formParam("DashboardBody", "{\"widgets\":[]}")
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> signed(region, "monitoring")
            .formParam("Action", "DeleteDashboards")
            .formParam("Version", "2010-08-01")
            .formParam("DashboardNames.member.1", name)
        .when().post("/"));
        String arn = signed(region, "monitoring")
            .formParam("Action", "GetDashboard")
            .formParam("Version", "2010-08-01")
            .formParam("DashboardName", name)
        .when().post("/").then().statusCode(200)
            .extract().xmlPath().getString("GetDashboardResponse.GetDashboardResult.DashboardArn");
        PartitionMatrix.assertGlobalArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void cloudWatchLogGroupArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "logs");
        String name = unique("smoke-log-group", partitionCase);
        json11(region, "logs", "Logs_20140328.CreateLogGroup", "{\"logGroupName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> json11(region, "logs", "Logs_20140328.DeleteLogGroup",
                "{\"logGroupName\":\"" + name + "\"}").when().post("/"));
        String arn = json11(region, "logs", "Logs_20140328.DescribeLogGroups",
                "{\"logGroupNamePrefix\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("logGroups[0].arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void eventBridgeEventBusArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "events");
        String name = unique("smoke-bus", partitionCase);
        String arn = json11(region, "events", "AWSEvents.CreateEventBus", "{\"Name\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("EventBusArn");
        cleanup.register(() -> json11(region, "events", "AWSEvents.DeleteEventBus",
                "{\"Name\":\"" + name + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void ecsClusterArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ecs");
        String name = unique("smoke-cluster", partitionCase);
        String arn = json11(region, "ecs", "AmazonEC2ContainerServiceV20141113.CreateCluster",
                "{\"clusterName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("cluster.clusterArn");
        cleanup.register(() -> json11(region, "ecs", "AmazonEC2ContainerServiceV20141113.DeleteCluster",
                "{\"cluster\":\"" + name + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void rdsParameterGroupArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "rds");
        String name = unique("smoke-pg", partitionCase);
        String arn = signed(region, "rds")
            .formParam("Action", "CreateDBParameterGroup")
            .formParam("Version", "2014-10-31")
            .formParam("DBParameterGroupName", name)
            .formParam("DBParameterGroupFamily", "postgres16")
            .formParam("Description", "smoke")
        .when().post("/").then().statusCode(200)
            .extract().xmlPath()
            .getString("CreateDBParameterGroupResponse.CreateDBParameterGroupResult.DBParameterGroup.DBParameterGroupArn");
        cleanup.register(() -> signed(region, "rds")
            .formParam("Action", "DeleteDBParameterGroup")
            .formParam("Version", "2014-10-31")
            .formParam("DBParameterGroupName", name)
        .when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void ec2IpamArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ec2");
        Response created = signed(region, "ec2")
            .formParam("Action", "CreateIpam")
            .formParam("Version", "2016-11-15")
        .when().post("/").then().statusCode(200).extract().response();
        String ipamId = created.xmlPath().getString("CreateIpamResponse.ipam.ipamId");
        cleanup.register(() -> signed(region, "ec2")
            .formParam("Action", "DeleteIpam")
            .formParam("Version", "2016-11-15")
            .formParam("IpamId", ipamId)
            .formParam("Cascade", "true")
        .when().post("/"));
        PartitionMatrix.assertGlobalArnIn(partitionCase, created.xmlPath().getString("CreateIpamResponse.ipam.ipamArn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void apiGatewayDomainNameArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "apigateway");
        String domain = unique("smoke-domain", partitionCase) + ".example.com";
        String body = """
                {
                  "domainName": "%s",
                  "endpointConfiguration": {"types": ["REGIONAL"]},
                  "regionalCertificateArn": "arn:%s:acm:%s:000000000000:certificate/smoke"
                }
                """.formatted(domain, partitionCase.partition(), region);
        String arn = signed(region, "apigateway")
            .contentType(JSON)
            .body(body)
        .when().post("/domainnames").then().statusCode(201)
            .extract().jsonPath().getString("domainNameArn");
        cleanup.register(() -> signed(region, "apigateway").when().delete("/domainnames/" + domain));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void sesTenantArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ses");
        String name = unique("smoke-tenant", partitionCase);
        String arn = signed(region, "ses")
            .contentType(JSON)
            .body("{\"TenantName\":\"" + name + "\"}")
        .when().post("/v2/email/tenants").then().statusCode(200)
            .extract().jsonPath().getString("TenantArn");
        cleanup.register(() -> signed(region, "ses")
            .contentType(JSON)
            .body("{\"TenantName\":\"" + name + "\"}")
        .when().post("/v2/email/tenants/delete"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }


    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void dsqlClusterArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "dsql");
        Response created = signed(region, "dsql")
            .contentType(JSON)
            .body("{\"deletionProtectionEnabled\":false}")
        .when().post("/cluster").then().statusCode(200).extract().response();
        String identifier = created.jsonPath().getString("identifier");
        cleanup.register(() -> signed(region, "dsql").when().delete("/cluster/" + identifier));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("arn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void cognitoUserPoolArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "cognito-idp");
        String name = unique("smoke-pool", partitionCase);
        Response created = json11(region, "cognito-idp", "AWSCognitoIdentityProviderService.CreateUserPool",
                "{\"PoolName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200).extract().response();
        String poolId = created.jsonPath().getString("UserPool.Id");
        cleanup.register(() -> json11(region, "cognito-idp", "AWSCognitoIdentityProviderService.DeleteUserPool",
                "{\"UserPoolId\":\"" + poolId + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("UserPool.Arn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void appSyncGraphqlApiArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "appsync");
        Response created = signed(region, "appsync")
            .contentType(JSON)
            .body("{\"name\":\"" + unique("smoke-api", partitionCase) + "\",\"authenticationType\":\"API_KEY\"}")
        .when().post("/v1/apis").then().statusCode(200).extract().response();
        String apiId = created.jsonPath().getString("graphqlApi.apiId");
        cleanup.register(() -> signed(region, "appsync").when().delete("/v1/apis/" + apiId));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("graphqlApi.arn"));
    }



    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void mskClusterArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "kafka");
        String arn = signed(region, "kafka")
            .contentType(JSON)
            .body("{\"clusterName\":\"" + unique("smoke-msk", partitionCase) + "\",\"kafkaVersion\":\"3.6.0\"}")
        .when().post("/v1/clusters").then().statusCode(200)
            .extract().jsonPath().getString("clusterArn");
        cleanup.register(() -> signed(region, "kafka").when().delete("/v1/clusters/{arn}", arn));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }





    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void cloudFrontFunctionArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "cloudfront");
        String name = unique("smoke-function", partitionCase);
        String code = Base64.getEncoder().encodeToString(
                "function handler(event) { return event.request; }".getBytes(StandardCharsets.UTF_8));
        String body = """
                <CreateFunctionRequest xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <Name>%s</Name>
                  <FunctionConfig><Comment>smoke</Comment><Runtime>cloudfront-js-2.0</Runtime></FunctionConfig>
                  <FunctionCode>%s</FunctionCode>
                </CreateFunctionRequest>
                """.formatted(name, code);
        Response created = signed(region, "cloudfront")
            .contentType("application/xml")
            .body(body)
        .when().post("/2020-05-31/function").then().statusCode(201).extract().response();
        String etag = created.header("ETag");
        cleanup.register(() -> signed(region, "cloudfront")
            .header("If-Match", etag)
        .when().delete("/2020-05-31/function/" + name));
        PartitionMatrix.assertGlobalArnIn(partitionCase,
                created.xmlPath().getString("FunctionSummary.FunctionMetadata.FunctionARN"));
    }


    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void oamSinkArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "oam");
        String arn = signed(region, "oam")
            .contentType(JSON)
            .body("{\"Name\":\"" + unique("smoke-sink", partitionCase) + "\"}")
        .when().post("/CreateSink").then().statusCode(200)
            .extract().jsonPath().getString("Arn");
        cleanup.register(() -> signed(region, "oam")
            .contentType(JSON)
            .body("{\"Identifier\":\"" + arn + "\"}")
        .when().post("/DeleteSink"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void schedulerScheduleGroupArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "scheduler");
        String name = unique("smoke-group", partitionCase);
        String arn = signed(region, "scheduler")
            .contentType(JSON)
            .body("{}")
        .when().post("/schedule-groups/" + name).then().statusCode(200)
            .extract().jsonPath().getString("ScheduleGroupArn");
        cleanup.register(() -> signed(region, "scheduler").when().delete("/schedule-groups/" + name));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void backupVaultArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "backup");
        String name = unique("smoke-vault", partitionCase);
        String arn = signed(region, "backup")
            .contentType(JSON)
            .body("{}")
        .when().put("/backup-vaults/" + name).then().statusCode(200)
            .extract().jsonPath().getString("BackupVaultArn");
        cleanup.register(() -> signed(region, "backup").when().delete("/backup-vaults/" + name));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void wafv2RegionalIpSetArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "wafv2");
        String name = unique("smoke-ipset", partitionCase);
        Response created = json11(region, "wafv2", "AWSWAF_20190729.CreateIPSet",
                "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\",\"IPAddressVersion\":\"IPV4\",\"Addresses\":[]}")
        .when().post("/").then().statusCode(200).extract().response();
        String id = created.jsonPath().getString("Summary.Id");
        String lockToken = created.jsonPath().getString("Summary.LockToken");
        cleanup.register(() -> json11(region, "wafv2", "AWSWAF_20190729.DeleteIPSet",
                "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\",\"Id\":\"" + id
                        + "\",\"LockToken\":\"" + lockToken + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("Summary.ARN"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void s3TablesTableBucketArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "s3tables");
        String name = "smoke-" + Long.toString(System.nanoTime(), 36);
        String arn = signed(region, "s3tables")
            .contentType(JSON)
            .body("{\"name\":\"" + name + "\"}")
        .when().put("/buckets").then().statusCode(200)
            .extract().jsonPath().getString("arn");
        cleanup.register(() -> signed(region, "s3tables").when().delete("/buckets/{arn}", arn));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }


    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void costExplorerAnomalyMonitorArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ce");
        String body = """
                {"AnomalyMonitor": {"MonitorName": "%s", "MonitorType": "DIMENSIONAL",
                                    "MonitorDimension": "SERVICE"}}
                """.formatted(unique("smoke-monitor", partitionCase));
        String arn = json11(region, "ce", "AWSInsightsIndexService.CreateAnomalyMonitor", body)
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("MonitorArn");
        cleanup.register(() -> json11(region, "ce", "AWSInsightsIndexService.DeleteAnomalyMonitor",
                "{\"MonitorArn\":\"" + arn + "\"}").when().post("/"));
        PartitionMatrix.assertGlobalArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void autoScalingLaunchConfigurationArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "autoscaling");
        String name = unique("smoke-lc", partitionCase);
        signed(region, "autoscaling")
            .formParam("Action", "CreateLaunchConfiguration")
            .formParam("Version", "2011-01-01")
            .formParam("LaunchConfigurationName", name)
            .formParam("ImageId", "ami-12345678")
            .formParam("InstanceType", "t3.micro")
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> signed(region, "autoscaling")
            .formParam("Action", "DeleteLaunchConfiguration")
            .formParam("Version", "2011-01-01")
            .formParam("LaunchConfigurationName", name)
        .when().post("/"));
        String arn = signed(region, "autoscaling")
            .formParam("Action", "DescribeLaunchConfigurations")
            .formParam("Version", "2011-01-01")
            .formParam("LaunchConfigurationNames.member.1", name)
        .when().post("/").then().statusCode(200)
            .extract().xmlPath().getString("DescribeLaunchConfigurationsResponse.DescribeLaunchConfigurationsResult"
                    + ".LaunchConfigurations.member.LaunchConfigurationARN");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void applicationAutoScalingScalableTargetArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "application-autoscaling");
        String resourceId = "table/" + unique("smoke-table", partitionCase);
        String target = """
                "ServiceNamespace": "dynamodb", "ResourceId": "%s",
                "ScalableDimension": "dynamodb:table:ReadCapacityUnits"
                """.formatted(resourceId);
        String arn = json11(region, "application-autoscaling", "AnyScaleFrontendService.RegisterScalableTarget",
                "{" + target + ", \"MinCapacity\": 1, \"MaxCapacity\": 5}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("ScalableTargetARN");
        cleanup.register(() -> json11(region, "application-autoscaling",
                "AnyScaleFrontendService.DeregisterScalableTarget", "{" + target + "}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void codeDeployOnPremisesInstanceArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "codedeploy");
        String name = unique("smoke-instance", partitionCase);
        json11(region, "codedeploy", "CodeDeploy_20141006.RegisterOnPremisesInstance",
                "{\"instanceName\":\"" + name + "\",\"iamUserArn\":\"arn:" + partitionCase.partition()
                        + ":iam::000000000000:user/codedeploy-agent\"}")
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> json11(region, "codedeploy", "CodeDeploy_20141006.DeregisterOnPremisesInstance",
                "{\"instanceName\":\"" + name + "\"}").when().post("/"));
        String arn = json11(region, "codedeploy", "CodeDeploy_20141006.GetOnPremisesInstance",
                "{\"instanceName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("instanceInfo.instanceArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void configRuleArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "config");
        String name = unique("smoke-rule", partitionCase);
        String body = """
                {"ConfigRule": {"ConfigRuleName": "%s",
                                "Source": {"Owner": "AWS", "SourceIdentifier": "S3_BUCKET_VERSIONING_ENABLED"}}}
                """.formatted(name);
        json11(region, "config", "StarlingDoveService.PutConfigRule", body)
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> json11(region, "config", "StarlingDoveService.DeleteConfigRule",
                "{\"ConfigRuleName\":\"" + name + "\"}").when().post("/"));
        String arn = json11(region, "config", "StarlingDoveService.DescribeConfigRules",
                "{\"ConfigRuleNames\":[\"" + name + "\"]}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("ConfigRules[0].ConfigRuleArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }



    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void lambdaWebFunctionArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "lambda");
        String message = signed(region, "lambda").when().get("/2025-03-07/web-functions/smoke-missing-web-function")
            .then().statusCode(404).extract().jsonPath().getString("message");
        String arn = message.substring("The function ".length(), message.indexOf(" does not exist."));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void dlmLifecyclePolicyArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "dlm");
        String body = """
                {"ExecutionRoleArn": "arn:%s:iam::000000000000:role/dlm",
                 "Description": "smoke", "State": "ENABLED",
                 "PolicyDetails": {"PolicyType": "EBS_SNAPSHOT_MANAGEMENT", "ResourceTypes": ["VOLUME"],
                   "TargetTags": [{"Key": "backup", "Value": "true"}],
                   "Schedules": [{"Name": "daily", "CreateRule": {"Interval": 24, "IntervalUnit": "HOURS"},
                                  "RetainRule": {"Count": 7}}]}}
                """.formatted(partitionCase.partition());
        String policyId = signed(region, "dlm")
            .contentType(JSON)
            .body(body)
        .when().post("/policies").then().statusCode(200)
            .extract().jsonPath().getString("PolicyId");
        cleanup.register(() -> signed(region, "dlm").when().delete("/policies/" + policyId));
        String arn = signed(region, "dlm").when().get("/policies/" + policyId)
            .then().statusCode(200).extract().jsonPath().getString("Policy.PolicyArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }


    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void cloudMapNamespaceArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "servicediscovery");
        String target = "Route53AutoNaming_v20170314.";
        String operationId = json11(region, "servicediscovery", target + "CreateHttpNamespace",
                "{\"Name\":\"" + unique("smoke-ns", partitionCase) + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("OperationId");
        String getOperation = "{\"OperationId\":\"" + operationId + "\"}";
        cleanup.register(() -> json11(region, "servicediscovery", target + "DeleteNamespace",
                "{\"Id\":\"" + json11(region, "servicediscovery", target + "GetOperation", getOperation)
                        .when().post("/").jsonPath().getString("Operation.Targets.NAMESPACE") + "\"}")
                .when().post("/"));
        String namespaceId = json11(region, "servicediscovery", target + "GetOperation", getOperation)
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Operation.Targets.NAMESPACE");
        String arn = json11(region, "servicediscovery", target + "GetNamespace", "{\"Id\":\"" + namespaceId + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Namespace.Arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void eventBridgeV2EventBusArn(PartitionCase partitionCase) throws Exception {
        String region = offered(partitionCase, "events");
        ObjectMapper cbor = new ObjectMapper(new CBORFactory());
        byte[] body = signed(region, "events")
            .header("smithy-protocol", "rpc-v2-cbor")
            .contentType("application/cbor")
            .accept("application/cbor")
            .body(cbor.writeValueAsBytes(Map.of("Name", unique("smoke-busv2", partitionCase))))
        .when().post("/service/AWSEventsV2/operation/CreateEventBus").then().statusCode(200)
            .extract().asByteArray();
        PartitionMatrix.assertArnIn(partitionCase, cbor.readTree(body).path("EventBusArn").asText());
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void accessAnalyzerArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "access-analyzer");
        String name = unique("smoke-analyzer", partitionCase);
        String arn = signed(region, "access-analyzer")
            .contentType(JSON)
            .body("{\"analyzerName\":\"" + name + "\",\"type\":\"ACCOUNT\"}")
        .when().put("/analyzer").then().statusCode(200)
            .extract().jsonPath().getString("arn");
        cleanup.register(() -> signed(region, "access-analyzer").when().delete("/analyzer/" + name));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }


    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void bedrockGuardrailArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "bedrock");
        String body = """
                {"name": "%s", "blockedInputMessaging": "blocked", "blockedOutputsMessaging": "blocked",
                 "contentPolicyConfig": {"filtersConfig": [
                   {"type": "HATE", "inputStrength": "HIGH", "outputStrength": "HIGH"}]}}
                """.formatted(unique("smoke-guardrail", partitionCase));
        Response created = signed(region, "bedrock")
            .contentType(JSON)
            .body(body)
        .when().post("/guardrails").then().statusCode(202).extract().response();
        String guardrailId = created.jsonPath().getString("guardrailId");
        cleanup.register(() -> signed(region, "bedrock").when().delete("/guardrails/" + guardrailId));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("guardrailArn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void connectInstanceArnAndServiceRole(PartitionCase partitionCase) {
        String region = offered(partitionCase, "connect");
        String body = """
                {"IdentityManagementType": "CONNECT_MANAGED", "InstanceAlias": "%s",
                 "InboundCallsEnabled": true, "OutboundCallsEnabled": false}
                """.formatted(unique("smoke-connect", partitionCase));
        Response created = signed(region, "connect")
            .contentType(JSON)
            .body(body)
        .when().put("/instance").then().statusCode(200).extract().response();
        String instanceId = created.jsonPath().getString("Id");
        cleanup.register(() -> signed(region, "connect").when().delete("/instance/" + instanceId));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("Arn"));
        String serviceRole = signed(region, "connect").when().get("/instance/" + instanceId)
            .then().statusCode(200).extract().jsonPath().getString("Instance.ServiceRole");
        PartitionMatrix.assertGlobalArnIn(partitionCase, serviceRole);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void controlCatalogControlArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "controlcatalog");
        String arn = signed(region, "controlcatalog")
            .contentType(JSON)
            .body("{}")
        .when().post("/list-controls").then().statusCode(200)
            .extract().jsonPath().getString("Controls[0].Arn");
        PartitionMatrix.assertGlobalArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void dataSyncAgentArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "datasync");
        String arn = json11(region, "datasync", "FmrsService.CreateAgent",
                "{\"ActivationKey\":\"AAAAA-1AAAA-BB1CC-DDDDD-EEEEE\",\"AgentName\":\""
                        + unique("smoke-agent", partitionCase) + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("AgentArn");
        cleanup.register(() -> json11(region, "datasync", "FmrsService.DeleteAgent",
                "{\"AgentArn\":\"" + arn + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void emrClusterArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "elasticmapreduce");
        String body = """
                {"Name": "%s", "ReleaseLabel": "emr-7.5.0",
                 "Instances": {"KeepJobFlowAliveWhenNoSteps": true, "InstanceGroups": [
                   {"Name": "master", "InstanceRole": "MASTER", "InstanceType": "m5.xlarge", "InstanceCount": 1}]}}
                """.formatted(unique("smoke-emr", partitionCase));
        Response created = json11(region, "elasticmapreduce", "ElasticMapReduce.RunJobFlow", body)
        .when().post("/").then().statusCode(200).extract().response();
        String clusterId = created.jsonPath().getString("JobFlowId");
        cleanup.register(() -> json11(region, "elasticmapreduce", "ElasticMapReduce.TerminateJobFlows",
                "{\"JobFlowIds\":[\"" + clusterId + "\"]}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("ClusterArn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void emrServerlessApplicationArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "emr-serverless");
        String body = """
                {"name": "%s", "releaseLabel": "emr-7.0.0", "type": "SPARK", "clientToken": "%s"}
                """.formatted(unique("smoke-app", partitionCase), unique("token", partitionCase));
        Response created = signed(region, "emr-serverless")
            .contentType(JSON)
            .body(body)
        .when().post("/applications").then().statusCode(200).extract().response();
        String applicationId = created.jsonPath().getString("applicationId");
        cleanup.register(() -> signed(region, "emr-serverless").when().delete("/applications/" + applicationId));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("arn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void bcmDataExportsExportArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "bcm-data-exports");
        String target = "AWSBillingAndCostManagementDataExports.";
        String body = "{\"Export\":{\"Name\":\"" + unique("smoke-export", partitionCase) + "\","
                + "\"DataQuery\":{\"QueryStatement\":\"SELECT * FROM COST_AND_USAGE_REPORT\"},"
                + "\"DestinationConfigurations\":{\"S3Destination\":{\"S3Bucket\":\"smoke-bucket\","
                + "\"S3Prefix\":\"out/\",\"S3Region\":\"" + region + "\",\"S3OutputConfigurations\":{"
                + "\"Format\":\"PARQUET\",\"Compression\":\"PARQUET\",\"OutputType\":\"CUSTOM\","
                + "\"Overwrite\":\"OVERWRITE_REPORT\"}}},\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";
        String arn = json11(region, "bcm-data-exports", target + "CreateExport", body)
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("ExportArn");
        cleanup.register(() -> json11(region, "bcm-data-exports", target + "DeleteExport",
                "{\"ExportArn\":\"" + arn + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void fisExperimentTemplateArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "fis");
        Response created = signed(region, "fis")
            .contentType(JSON)
            .body("{\"clientToken\":\"" + unique("smoke-token", partitionCase) + "\",\"description\":\"smoke\","
                    + "\"roleArn\":\"arn:" + partitionCase.partition() + ":iam::000000000000:role/fis-role\","
                    + "\"stopConditions\":[{\"source\":\"none\"}],\"targets\":{},"
                    + "\"actions\":{\"wait\":{\"actionId\":\"aws:fis:wait\",\"parameters\":{\"duration\":\"PT1M\"}}}}")
        .when().post("/experimentTemplates").then().statusCode(200).extract().response();
        String templateId = created.jsonPath().getString("experimentTemplate.id");
        cleanup.register(() -> signed(region, "fis").when().delete("/experimentTemplates/" + templateId));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("experimentTemplate.arn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void pipesPipeArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "pipes");
        String name = unique("smoke-pipe", partitionCase);
        String queuePrefix = "arn:" + partitionCase.partition() + ":sqs:" + region + ":000000000000:";
        String arn = signed(region, "pipes")
            .contentType(JSON)
            .body("{\"Source\":\"" + queuePrefix + "smoke-source\",\"Target\":\"" + queuePrefix + "smoke-target\","
                    + "\"RoleArn\":\"arn:" + partitionCase.partition() + ":iam::000000000000:role/pipe-role\","
                    + "\"DesiredState\":\"STOPPED\"}")
        .when().post("/v1/pipes/" + name).then().statusCode(200)
            .extract().jsonPath().getString("Arn");
        cleanup.register(() -> signed(region, "pipes").when().delete("/v1/pipes/" + name));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void s3VectorsVectorBucketArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "s3vectors");
        String body = "{\"vectorBucketName\":\"" + unique("smoke-vectors", partitionCase) + "\"}";
        String arn = signed(region, "s3vectors")
            .contentType(JSON)
            .body(body)
        .when().post("/CreateVectorBucket").then().statusCode(200)
            .extract().jsonPath().getString("vectorBucketArn");
        cleanup.register(() -> signed(region, "s3vectors").contentType(JSON).body(body)
            .when().post("/DeleteVectorBucket"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }


    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void transferServerArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "transfer");
        String serverId = json11(region, "transfer", "TransferService.CreateServer", "{}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("ServerId");
        String byId = "{\"ServerId\":\"" + serverId + "\"}";
        cleanup.register(() -> json11(region, "transfer", "TransferService.DeleteServer", byId).when().post("/"));
        String arn = json11(region, "transfer", "TransferService.DescribeServer", byId)
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Server.Arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void verifiedPermissionsPolicyStoreArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "verifiedpermissions");
        Response created = json10(region, "verifiedpermissions", "VerifiedPermissions.CreatePolicyStore",
                "{\"validationSettings\":{\"mode\":\"OFF\"}}")
        .when().post("/").then().statusCode(200).extract().response();
        String policyStoreId = created.jsonPath().getString("policyStoreId");
        cleanup.register(() -> json10(region, "verifiedpermissions", "VerifiedPermissions.DeletePolicyStore",
                "{\"policyStoreId\":\"" + policyStoreId + "\"}").when().post("/"));
        PartitionMatrix.assertGlobalArnIn(partitionCase, created.jsonPath().getString("arn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void codePipelineWebhookArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "codepipeline");
        String name = unique("smoke-webhook", partitionCase);
        String arn = json11(region, "codepipeline", "CodePipeline_20150709.PutWebhook",
                "{\"webhook\":{\"name\":\"" + name + "\"}}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("webhook.arn");
        cleanup.register(() -> json11(region, "codepipeline", "CodePipeline_20150709.DeleteWebhook",
                "{\"name\":\"" + name + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void dmsEndpointArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "dms");
        String arn = json11(region, "dms", "AmazonDMSv20160101.CreateEndpoint",
                "{\"EndpointIdentifier\":\"" + unique("smoke-dms", partitionCase)
                        + "\",\"EndpointType\":\"source\",\"EngineName\":\"mysql\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Endpoint.EndpointArn");
        cleanup.register(() -> json11(region, "dms", "AmazonDMSv20160101.DeleteEndpoint",
                "{\"EndpointArn\":\"" + arn + "\"}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void ecrRepositoryArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ecr");
        String name = unique("smoke-repo", partitionCase);
        String target = "AmazonEC2ContainerRegistry_V20150921.";
        String arn = json11(region, "ecr", target + "CreateRepository", "{\"repositoryName\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("repository.repositoryArn");
        cleanup.register(() -> json11(region, "ecr", target + "DeleteRepository",
                "{\"repositoryName\":\"" + name + "\",\"force\":true}").when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void elasticBeanstalkApplicationArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "elasticbeanstalk");
        String name = unique("smoke-app", partitionCase);
        String arn = signed(region, "elasticbeanstalk")
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateApplication")
            .formParam("ApplicationName", name)
        .when().post("/").then().statusCode(200)
            .extract().xmlPath()
            .getString("CreateApplicationResponse.CreateApplicationResult.Application.ApplicationArn");
        cleanup.register(() -> signed(region, "elasticbeanstalk")
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteApplication")
            .formParam("ApplicationName", name)
        .when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void kinesisAnalyticsApplicationArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "kinesisanalytics");
        String name = unique("smoke-flink", partitionCase);
        String target = "KinesisAnalytics_20180523.";
        Response created = json11(region, "kinesisanalytics", target + "CreateApplication",
                "{\"ApplicationName\":\"" + name + "\",\"RuntimeEnvironment\":\"FLINK-1_18\","
                        + "\"ServiceExecutionRole\":\"arn:" + partitionCase.partition()
                        + ":iam::000000000000:role/flink-role\"}")
        .when().post("/").then().statusCode(200).extract().response();
        // DeleteApplication matches CreateTimestamp to the second, and the default float extraction rounds it.
        String createTimestamp = created.jsonPath(new JsonPathConfig(JsonPathConfig.NumberReturnType.BIG_DECIMAL))
            .getString("ApplicationDetail.CreateTimestamp");
        cleanup.register(() -> json11(region, "kinesisanalytics", target + "DeleteApplication",
                "{\"ApplicationName\":\"" + name + "\",\"CreateTimestamp\":" + createTimestamp + "}")
            .when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, created.jsonPath().getString("ApplicationDetail.ApplicationARN"));
    }


    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void resourceExplorerViewArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "resource-explorer-2");
        String arn = signed(region, "resource-explorer-2")
            .contentType(JSON)
            .body("{\"ViewName\":\"" + unique("smoke-view", partitionCase) + "\"}")
        .when().post("/CreateView").then().statusCode(200)
            .extract().jsonPath().getString("View.ViewArn");
        cleanup.register(() -> signed(region, "resource-explorer-2").contentType(JSON)
            .body("{\"ViewArn\":\"" + arn + "\"}").when().post("/DeleteView"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }



    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void mwaaEnvironmentArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "airflow");
        String name = unique("smoke-airflow", partitionCase);
        String partition = partitionCase.partition();
        String arn = signed(region, "airflow")
            .contentType(JSON)
            .body("{\"ExecutionRoleArn\":\"arn:" + partition + ":iam::000000000000:role/airflow-role\","
                    + "\"SourceBucketArn\":\"arn:" + partition + ":s3:::smoke-dags\",\"DagS3Path\":\"dags\","
                    + "\"NetworkConfiguration\":{\"SubnetIds\":[\"subnet-1\",\"subnet-2\"],"
                    + "\"SecurityGroupIds\":[\"sg-1\"]}}")
        .when().put("/environments/" + name).then().statusCode(200)
            .extract().jsonPath().getString("Arn");
        cleanup.register(() -> signed(region, "airflow").when().delete("/environments/" + name));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void organizationArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "organizations");
        // An account holds one organization, so this case uses an account no other test class signs with.
        String auth = "AWS4-HMAC-SHA256 Credential=000000004272/20260215/" + region
                + "/organizations/aws4_request, SignedHeaders=host;x-amz-date, Signature=abc";
        String target = "AWSOrganizationsV20161128.";
        Response created = given().header("Authorization", auth)
            .header("X-Amz-Target", target + "CreateOrganization")
            .contentType(JSON_1_1)
            .body("{\"FeatureSet\":\"ALL\"}")
        .when().post("/").then().statusCode(200).extract().response();
        cleanup.register(() -> given().header("Authorization", auth)
            .header("X-Amz-Target", target + "DeleteOrganization")
            .contentType(JSON_1_1)
            .body("{}")
        .when().post("/"));
        PartitionMatrix.assertGlobalArnIn(partitionCase, created.jsonPath().getString("Organization.Arn"));
        PartitionMatrix.assertGlobalArnIn(partitionCase, created.jsonPath().getString("Organization.MasterAccountArn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void redshiftSnapshotCopyGrantKeyArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "redshift");
        String name = unique("smoke-grant", partitionCase);
        String arn = signed(region, "redshift")
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateSnapshotCopyGrant")
            .formParam("Version", "2012-12-01")
            .formParam("SnapshotCopyGrantName", name)
        .when().post("/").then().statusCode(200)
            .extract().xmlPath()
            .getString("CreateSnapshotCopyGrantResponse.CreateSnapshotCopyGrantResult.SnapshotCopyGrant.KmsKeyId");
        cleanup.register(() -> signed(region, "redshift")
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteSnapshotCopyGrant")
            .formParam("Version", "2012-12-01")
            .formParam("SnapshotCopyGrantName", name)
        .when().post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void lambdaFunctionArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "lambda");
        String name = unique("smoke-fn", partitionCase);
        // By name, before the create: a failure reading the response then leaves nothing behind.
        cleanup.register(() -> signed(region, "lambda").when().delete("/2015-03-31/functions/" + name));
        String arn = signed(region, "lambda")
            .contentType(JSON)
            .body("{\"FunctionName\":\"" + name + "\",\"Runtime\":\"nodejs20.x\",\"Handler\":\"index.handler\","
                    + "\"Role\":\"arn:" + partitionCase.partition() + ":iam::000000000000:role/lambda-role\"}")
        .when().post("/2015-03-31/functions").then().statusCode(201)
            .extract().jsonPath().getString("FunctionArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void stepFunctionsStateMachineArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "states");
        String name = unique("smoke-sm", partitionCase);
        String definition = "{\\\"StartAt\\\":\\\"Done\\\","
                + "\\\"States\\\":{\\\"Done\\\":{\\\"Type\\\":\\\"Pass\\\",\\\"End\\\":true}}}";
        // By the ARN the name gives, before the create: a failure reading the response leaves nothing behind.
        cleanup.register(() -> json10(region, "states", "AWSStepFunctions.DeleteStateMachine",
                "{\"stateMachineArn\":\"arn:" + partitionCase.partition() + ":states:" + region + ":"
                        + PartitionMatrix.ACCOUNT + ":stateMachine:" + name + "\"}").when().post("/"));
        String arn = json10(region, "states", "AWSStepFunctions.CreateStateMachine",
                "{\"name\":\"" + name + "\",\"definition\":\"" + definition + "\","
                        + "\"roleArn\":\"arn:" + partitionCase.partition() + ":iam::000000000000:role/sfn-role\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("stateMachineArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    /**
     * The case's region, or the test is skipped where the partition does not publish the service.
     * A service no partition lists (the newer ones {@code endpoints.json} omits) runs everywhere,
     * the same reading strict partition mode applies: silence there is not absence.
     */
    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void appRunnerAutoScalingArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "apprunner");
        String name = "smoke-" + Long.toString(System.nanoTime(), 36);
        String arn = signed(region, "apprunner").header("X-Amz-Target", "AppRunner.CreateAutoScalingConfiguration")
                .contentType(JSON_1_0).body("{\"AutoScalingConfigurationName\":\"" + name + "\"}")
                .post("/").then().statusCode(200).extract().path("AutoScalingConfiguration.AutoScalingConfigurationArn");
        cleanup.register(() -> signed(region, "apprunner")
                .header("X-Amz-Target", "AppRunner.DeleteAutoScalingConfiguration").contentType(JSON_1_0)
                .body("{\"AutoScalingConfigurationArn\":\"" + arn + "\"}").post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void codeConnectionsConnectionArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "codeconnections");
        String arn = signed(region, "codeconnections")
                .header("X-Amz-Target", "com.amazonaws.codeconnections.CodeConnections_20231201.CreateConnection")
                .contentType(JSON_1_0).body("{\"ConnectionName\":\"smoke\",\"ProviderType\":\"GitHub\"}")
                .post("/").then().statusCode(200).extract().path("ConnectionArn");
        cleanup.register(() -> signed(region, "codeconnections")
                .header("X-Amz-Target", "com.amazonaws.codeconnections.CodeConnections_20231201.DeleteConnection")
                .contentType(JSON_1_0).body("{\"ConnectionArn\":\"" + arn + "\"}").post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void dataBrewRecipeArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "databrew");
        String name = unique("smoke-recipe", partitionCase);
        signed(region, "databrew").contentType(JSON).body("{\"Name\":\"" + name + "\",\"Steps\":[]}")
                .post("/recipes").then().statusCode(200);
        cleanup.register(() -> signed(region, "databrew").delete("/recipes/" + name + "/recipeVersion/LATEST_WORKING"));
        String arn = signed(region, "databrew").get("/recipes/" + name).then().statusCode(200).extract()
                .path("ResourceArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void dataExchangeDataSetArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "dataexchange");
        String id = signed(region, "dataexchange").contentType(JSON)
                .body("{\"AssetType\":\"S3_SNAPSHOT\",\"Name\":\"smoke\",\"Description\":\"smoke\"}")
                .post("/v1/data-sets").then().statusCode(201).extract().path("Id");
        cleanup.register(() -> signed(region, "dataexchange").delete("/v1/data-sets/" + id));
        String arn = signed(region, "dataexchange").get("/v1/data-sets/" + id).then().statusCode(200).extract()
                .path("Arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void dataZoneDomainArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "datazone");
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":"
                + "{\"Service\":\"datazone.amazonaws.com\"},\"Action\":[\"sts:AssumeRole\",\"sts:TagSession\"]}]}";
        String roleName = unique("smoke-datazone", partitionCase);
        String role = signed(region, "iam").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", trust)
                .post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.register(() -> signed(region, "iam").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteRole").formParam("RoleName", roleName).post("/"));
        String id = signed(region, "datazone").contentType(JSON)
                .body("{\"name\":\"" + unique("smoke", partitionCase) + "\",\"domainExecutionRole\":\"" + role
                        + "\"}")
                .post("/v2/domains").then().statusCode(201).extract().path("id");
        cleanup.register(() -> signed(region, "datazone").delete("/v2/domains/" + id));
        String arn = signed(region, "datazone").get("/v2/domains/" + id).then().statusCode(200).extract()
                .path("arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void deadlineFarmArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "deadline");
        String id = signed(region, "deadline").contentType(JSON)
                .body("{\"displayName\":\"" + unique("smoke", partitionCase) + "\",\"tags\":{\"smoke\":\"yes\"}}")
                .post("/2023-10-12/farms").then().statusCode(200).extract().path("farmId");
        cleanup.register(() -> signed(region, "deadline").delete("/2023-10-12/farms/" + id));
        String arn = "arn:" + partitionCase.partition() + ":deadline:" + region + ":" + PartitionMatrix.ACCOUNT + ":farm/" + id;
        String tag = signed(region, "deadline").get("/2023-10-12/tags/" + arn).then().statusCode(200).extract()
                .path("tags.smoke");
        assertEquals("yes", tag);
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void devOpsGuruChannelTopic(PartitionCase partitionCase) {
        String region = offered(partitionCase, "devops-guru");
        String topic = signed(region, "sns").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateTopic").formParam("Name", unique("smoke-guru", partitionCase))
                .post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateTopicResponse.CreateTopicResult.TopicArn");
        cleanup.register(() -> signed(region, "sns").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteTopic").formParam("TopicArn", topic).post("/"));
        String id = signed(region, "devops-guru").contentType(JSON)
                .body("{\"Config\":{\"Sns\":{\"TopicArn\":\"" + topic + "\"}}}")
                .put("/channels").then().statusCode(200).extract().path("Id");
        cleanup.register(() -> signed(region, "devops-guru").delete("/channels/" + id));
        PartitionMatrix.assertArnIn(partitionCase, topic);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void docDbElasticClusterArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "docdb-elastic");
        String arn = "arn:" + partitionCase.partition() + ":docdb-elastic:" + region + ":" + PartitionMatrix.ACCOUNT
                + ":cluster/00000000-0000-0000-0000-000000000000";
        signed(region, "docdb-elastic").get("/clusters").then().statusCode(200);
        String missing = signed(region, "docdb-elastic").urlEncodingEnabled(false).get("/cluster/" + arn).then()
                .statusCode(404).extract().path("resourceId");
        assertEquals(arn, missing);
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void ecrPublicRegistryArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ecr-public");
        String arn = signed(region, "ecr-public").header("X-Amz-Target", "SpencerFrontendService.DescribeRegistries")
                .contentType(JSON_1_1).body("{}").post("/").then().statusCode(200).extract()
                .path("registries[0].registryArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void emrContainersJobTemplateArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "emr-containers");
        String roleName = unique("smoke-emrc", partitionCase);
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":"
                + "{\"Service\":\"pods.eks.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        String role = signed(region, "iam").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", trust).post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.register(() -> signed(region, "iam").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteRole").formParam("RoleName", roleName).post("/"));
        String arn = signed(region, "emr-containers").contentType(JSON).body("{\"name\":\"" + roleName
                        + "\",\"clientToken\":\"" + roleName + "\",\"jobTemplateData\":{\"executionRoleArn\":\""
                        + role + "\",\"releaseLabel\":\"emr-7.5.0-latest\",\"jobDriver\":{\"sparkSubmitJobDriver\":"
                        + "{\"entryPoint\":\"local:///app.py\"}}}}")
                .post("/jobtemplates").then().statusCode(200).extract().path("arn");
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        cleanup.register(() -> signed(region, "emr-containers").delete("/jobtemplates/" + id));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void entityResolutionSchemaMappingArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "entityresolution");
        String name = unique("smoke-er", partitionCase);
        String arn = signed(region, "entityresolution").contentType(JSON).body("{\"schemaName\":\"" + name
                        + "\",\"mappedInputFields\":[{\"fieldName\":\"id\",\"type\":\"UNIQUE_ID\"},"
                        + "{\"fieldName\":\"email\",\"type\":\"EMAIL_ADDRESS\",\"matchKey\":\"email\"}]}")
                .post("/schemas").then().statusCode(200).extract().path("schemaArn");
        cleanup.register(() -> signed(region, "entityresolution").delete("/schemas/" + name));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void firewallManagerRefusesAnAccountWithNoAdministrator(PartitionCase partitionCase) {
        String region = offered(partitionCase, "fms");
        String type = signed(region, "fms").header("X-Amz-Target", "AWSFMS_20180101.GetAdminAccount")
                .contentType(JSON_1_1).body("{}").post("/").then().statusCode(400).extract().path("__type");
        assertTrue(type.contains("ResourceNotFoundException"), type);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void fsxMissingFileSystem(PartitionCase partitionCase) {
        String region = offered(partitionCase, "fsx");
        String type = signed(region, "fsx").header("X-Amz-Target", "AWSSimbaAPIService_v20180301.DescribeFileSystems")
                .contentType(JSON_1_1).body("{\"FileSystemIds\":[\"fs-00000000000000000\"]}").post("/").then()
                .statusCode(400).extract().path("__type");
        assertTrue(type.contains("FileSystemNotFound"), type);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void forecastDatasetGroupArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "forecast");
        String arn = signed(region, "forecast").header("X-Amz-Target", "AmazonForecast.CreateDatasetGroup")
                .contentType(JSON_1_1).body("{\"DatasetGroupName\":\"partition_smoke\",\"Domain\":\"CUSTOM\"}")
                .post("/").then().statusCode(200).extract().path("DatasetGroupArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
        signed(region, "forecast").header("X-Amz-Target", "AmazonForecast.DeleteDatasetGroup").contentType(JSON_1_1)
                .body("{\"DatasetGroupArn\":\"" + arn + "\"}").post("/").then().statusCode(200);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void neptuneGraphArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "neptune-graph");
        String id = signed(region, "neptune-graph").contentType("application/json")
                .body("{\"graphName\":\"partition-" + region + "\",\"provisionedMemory\":16,"
                        + "\"deletionProtection\":false}")
                .post("/graphs").then().statusCode(201).extract().path("id");
        cleanup.register(() -> signed(region, "neptune-graph").queryParam("skipSnapshot", true)
                .delete("/graphs/" + id));
        String arn = signed(region, "neptune-graph").get("/graphs/" + id).then().statusCode(200).extract().path("arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void notificationConfigurationArnIsGlobal(PartitionCase partitionCase) {
        String region = offered(partitionCase, "notifications");
        String arn = signed(region, "notifications").contentType("application/json")
                .body("{\"name\":\"" + unique("partition", partitionCase) + "\",\"description\":\"smoke\"}")
                .post("/notification-configurations").then().statusCode(201).extract().path("arn");
        cleanup.register(() -> signed(region, "notifications").delete("/notification-configurations/" + arn));
        PartitionMatrix.assertGlobalArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void notificationsEmailContactArnIsGlobal(PartitionCase partitionCase) {
        String region = offered(partitionCase, "notifications-contacts");
        String arn = signed(region, "notifications-contacts").contentType("application/json")
                .body("{\"name\":\"partition\",\"emailAddress\":\"" + unique("partition", partitionCase)
                        + "@example.com\"}")
                .post("/2022-09-19/emailcontacts").then().statusCode(201).extract().path("arn");
        cleanup.register(() -> signed(region, "notifications-contacts").delete("/emailcontacts/" + arn));
        PartitionMatrix.assertGlobalArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void openSearchServerlessCollectionArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "aoss");
        String policy = "{\\\"Rules\\\":[{\\\"ResourceType\\\":\\\"collection\\\",\\\"Resource\\\":"
                + "[\\\"collection/partition*\\\"]}],\\\"AWSOwnedKey\\\":true}";
        json10(region, "aoss", "OpenSearchServerless.CreateSecurityPolicy", "{\"type\":\"encryption\",\"name\":"
                + "\"partition-enc\",\"policy\":\"" + policy + "\"}").post("/").then().statusCode(200);
        String arn = json10(region, "aoss", "OpenSearchServerless.CreateCollection",
                "{\"name\":\"partition-c\"}").post("/").then().statusCode(200)
                .extract().path("createCollectionDetail.arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void qBusinessApplicationAndQAppArns(PartitionCase partitionCase) {
        String region = offered(partitionCase, "qbusiness");
        String arn = signed(region, "qbusiness").contentType("application/json").body("{\"displayName\":"
                + "\"partition\",\"identityType\":\"ANONYMOUS\",\"qAppsConfiguration\":{\"qAppsControlMode\":"
                + "\"ENABLED\"}}").post("/applications").then().statusCode(200).extract().path("applicationArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        cleanup.register(() -> signed(region, "qbusiness").delete("/applications/" + id));
        await().atMost(Duration.ofSeconds(20)).until(() -> "ACTIVE".equals(signed(region, "qbusiness")
                .get("/applications/" + id).then().extract().path("status")));
        String app = signed(region, "qapps").header("instance-id", id).contentType("application/json")
                .body("{\"title\":\"partition\",\"appDefinition\":{\"cards\":[{\"textInput\":{\"id\":"
                        + "\"11111111-1111-4111-8111-111111111111\",\"title\":\"Input\",\"type\":\"text-input\"}}]}}")
                .post("/apps.create").then().statusCode(200).extract().path("appArn");
        PartitionMatrix.assertArnIn(partitionCase, app);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void smsVoiceConfigurationSetAndOptOutListArns(PartitionCase partitionCase) {
        String region = offered(partitionCase, "sms-voice");
        String name = unique("partition", partitionCase);
        String arn = json10(region, "sms-voice", "PinpointSMSVoiceV2.CreateConfigurationSet",
                "{\"ConfigurationSetName\":\"" + name + "\"}").post("/").then().statusCode(200).extract()
                .path("ConfigurationSetArn");
        cleanup.register(() -> json10(region, "sms-voice", "PinpointSMSVoiceV2.DeleteConfigurationSet",
                "{\"ConfigurationSetName\":\"" + name + "\"}").post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
        String list = json10(region, "sms-voice", "PinpointSMSVoiceV2.DescribeOptOutLists", "{}").post("/").then()
                .statusCode(200).extract().path("OptOutLists[0].OptOutListArn");
        PartitionMatrix.assertArnIn(partitionCase, list);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void personalizeDatasetGroupAndRecipeArns(PartitionCase partitionCase) {
        String region = offered(partitionCase, "personalize");
        String arn = json11(region, "personalize", "AmazonPersonalize.CreateDatasetGroup",
                "{\"name\":\"partition\"}").post("/").then().statusCode(200).extract().path("datasetGroupArn");
        cleanup.register(() -> json11(region, "personalize", "AmazonPersonalize.DeleteDatasetGroup",
                "{\"datasetGroupArn\":\"" + arn + "\"}").post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
        String recipe = json11(region, "personalize", "AmazonPersonalize.ListRecipes", "{}").post("/").then()
                .statusCode(200).extract().path("recipes[0].recipeArn");
        PartitionMatrix.assertGlobalArnIn(partitionCase, recipe);
        String algorithm = json11(region, "personalize", "AmazonPersonalize.DescribeRecipe", "{\"recipeArn\":\""
                + recipe + "\"}").post("/").then().statusCode(200).extract().path("recipe.algorithmArn");
        PartitionMatrix.assertGlobalArnIn(partitionCase, algorithm);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void omicsSequenceStoreArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "omics");
        String id = signed(region, "omics").contentType("application/json").body("{\"name\":\"partition\"}")
                .post("/sequencestore").then().statusCode(200).extract().path("id");
        String arn = signed(region, "omics").get("/sequencestore/" + id).then().statusCode(200).extract().path("arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
        String accessPoint = signed(region, "omics").get("/sequencestore/" + id).then().extract()
                .path("s3Access.s3AccessPointArn");
        PartitionMatrix.assertArnIn(partitionCase, accessPoint);
        signed(region, "omics").delete("/sequencestore/" + id).then().statusCode(200);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void observabilityAdminPipelineArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "observabilityadmin");
        String body = "pipeline:\\n  source:\\n    cloudwatch_logs:\\n      log_event_metadata:\\n"
                + "        data_source_name: app\\n  sink:\\n    - cloudwatch_logs:\\n        log_group: '@original'\\n";
        String arn = signed(region, "observabilityadmin").contentType("application/json")
                .body("{\"Name\":\"partition\",\"Configuration\":{\"Body\":\"" + body + "\"}}")
                .post("/CreateTelemetryPipeline").then().statusCode(200).extract().path("Arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
        await().atMost(Duration.ofSeconds(30)).until(() -> "ACTIVE".equals(signed(region, "observabilityadmin")
                .contentType("application/json").body("{\"PipelineIdentifier\":\"partition\"}")
                .post("/GetTelemetryPipeline").then().extract().path("Pipeline.Status")));
        signed(region, "observabilityadmin").contentType("application/json")
                .body("{\"PipelineIdentifier\":\"partition\"}").post("/DeleteTelemetryPipeline").then()
                .statusCode(200);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void osisPipelineArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "osis");
        String config = "version: \\\"2\\\"\\nlogs:\\n  source:\\n    http:\\n      path: /logs\\n  sink:\\n"
                + "    - opensearch:\\n        hosts: [\\\"https://localhost:9200\\\"]\\n";
        String arn = signed(region, "osis").contentType("application/json")
                .body("{\"PipelineName\":\"partition\",\"MinUnits\":1,\"MaxUnits\":1,"
                        + "\"PipelineConfigurationBody\":\"" + config + "\"}")
                .post("/2022-01-01/osis/createPipeline").then().statusCode(200).extract().path("Pipeline.PipelineArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
        await().atMost(Duration.ofSeconds(30)).until(() -> "ACTIVE".equals(signed(region, "osis")
                .get("/2022-01-01/osis/getPipeline/partition").then().extract().path("Pipeline.Status")));
        signed(region, "osis").delete("/2022-01-01/osis/deletePipeline/partition").then().statusCode(200);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void medicalImagingDatastoreArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "medical-imaging");
        String id = signed(region, "medical-imaging").contentType("application/json")
                .body("{\"datastoreName\":\"partition\",\"clientToken\":\"partition-" + region + "\"}")
                .post("/datastore").then().statusCode(200).extract().path("datastoreId");
        String arn = signed(region, "medical-imaging").get("/datastore/" + id).then().statusCode(200).extract()
                .path("datastoreProperties.datastoreArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void mediaTailorPlaybackConfigurationArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "mediatailor");
        String arn = signed(region, "mediatailor").contentType("application/json")
                .body("{\"Name\":\"partition\",\"VideoContentSourceUrl\":\"https://origin.example.com\"}")
                .put("/playbackConfiguration").then().statusCode(200).extract().path("PlaybackConfigurationArn");
        cleanup.register(() -> signed(region, "mediatailor").delete("/playbackConfiguration/partition"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void mediaPackageV2ChannelGroupArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "mediapackagev2");
        String arn = signed(region, "mediapackagev2").contentType("application/json")
                .body("{\"ChannelGroupName\":\"partition\"}").post("/channelGroup").then().statusCode(200)
                .extract().path("Arn");
        cleanup.register(() -> signed(region, "mediapackagev2").delete("/channelGroup/partition"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void mediaLiveInputSecurityGroupArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "medialive");
        String id = signed(region, "medialive").contentType("application/json")
                .body("{\"whitelistRules\":[{\"cidr\":\"10.0.0.0/16\"}]}").post("/prod/inputSecurityGroups").then()
                .statusCode(200).extract().path("securityGroup.id");
        cleanup.register(() -> signed(region, "medialive").delete("/prod/inputSecurityGroups/" + id));
        String arn = signed(region, "medialive").get("/prod/inputSecurityGroups/" + id).then().statusCode(200)
                .extract().path("arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void mediaConvertQueueArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "mediaconvert");
        String name = unique("smoke-mcv", partitionCase);
        String arn = signed(region, "mediaconvert").contentType("application/json")
                .body("{\"name\":\"" + name + "\"}").post("/2017-08-29/queues").then().statusCode(201)
                .extract().path("queue.arn");
        cleanup.register(() -> signed(region, "mediaconvert").delete("/2017-08-29/queues/" + name));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void mediaConnectFlowArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "mediaconnect");
        String arn = signed(region, "mediaconnect").contentType("application/json")
                .body("{\"name\":\"" + unique("smoke-mc", partitionCase) + "\"}").post("/v1/flows").then()
                .statusCode(201).extract().path("flow.flowArn");
        cleanup.register(() -> signed(region, "mediaconnect").delete("/v1/flows/" + arn));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void mailManagerRuleSetArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ses");
        String id = json10(region, "ses", "MailManagerSvc.CreateRuleSet",
                "{\"RuleSetName\":\"" + unique("smoke-mm", partitionCase) + "\",\"Rules\":[]}")
                .post("/").then().statusCode(200).extract().path("RuleSetId");
        cleanup.register(() -> json10(region, "ses", "MailManagerSvc.DeleteRuleSet",
                "{\"RuleSetId\":\"" + id + "\"}").post("/"));
        String arn = json10(region, "ses", "MailManagerSvc.GetRuleSet", "{\"RuleSetId\":\"" + id + "\"}")
                .post("/").then().statusCode(200).extract().path("RuleSetArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void airflowServerlessWorkflowArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "airflow-serverless");
        String bucket = unique("smoke-mwaas", partitionCase);
        signed(region, "s3").contentType("application/xml").body("<CreateBucketConfiguration><LocationConstraint>"
                + region + "</LocationConstraint></CreateBucketConfiguration>").put("/" + bucket).then()
                .statusCode(200);
        signed(region, "s3").body("d:\n  tasks:\n    a:\n      operator: airflow.operators.empty.EmptyOperator\n")
                .put("/" + bucket + "/w.yaml").then().statusCode(200);
        String arn = json10(region, "airflow-serverless", "AmazonMWAAServerless.CreateWorkflow",
                "{\"Name\":\"smoke\",\"DefinitionS3Location\":{\"Bucket\":\"" + bucket + "\",\"ObjectKey\":"
                        + "\"w.yaml\"},\"RoleArn\":\"arn:" + partitionCase.partition() + ":iam::000000000000:role/r\"}")
                .post("/").then().statusCode(200).extract().path("WorkflowArn");
        cleanup.register(() -> json10(region, "airflow-serverless", "AmazonMWAAServerless.DeleteWorkflow",
                "{\"WorkflowArn\":\"" + arn + "\"}").post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void licenseConfigurationArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "license-manager");
        String arn = json11(region, "license-manager", "AWSLicenseManager.CreateLicenseConfiguration",
                "{\"Name\":\"" + unique("smoke-lm", partitionCase) + "\",\"LicenseCountingType\":\"vCPU\"}")
                .post("/").then().statusCode(200).extract().path("LicenseConfigurationArn");
        cleanup.register(() -> json11(region, "license-manager", "AWSLicenseManager.DeleteLicenseConfiguration",
                "{\"LicenseConfigurationArn\":\"" + arn + "\"}").post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
        assertTrue(arn.contains(":" + region + ":"), arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void lexBotArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "lex");
        String name = unique("smoke-lex", partitionCase);
        String botId = signed(region, "lex").contentType(JSON).body("{\"botName\":\"" + name + "\",\"roleArn\":\"arn:"
                + partitionCase.partition() + ":iam::000000000000:role/lex\",\"dataPrivacy\":{\"childDirected\":false},"
                + "\"idleSessionTTLInSeconds\":300,\"botTags\":{\"smoke\":\"yes\"}}").put("/bots/").then().statusCode(202)
                .extract().path("botId");
        cleanup.register(() -> signed(region, "lex").queryParam("skipResourceInUseCheck", true)
                .delete("/bots/" + botId + "/"));
        String arn = "arn:" + partitionCase.partition() + ":lex:" + region + ":000000000000:bot/" + botId;
        String tag = signed(region, "lex").get("/tags/" + arn.replace(":", "%3A").replace("/", "%2F")).then()
                .statusCode(200).extract().path("tags.smoke");
        assertEquals("yes", tag);
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void kinesisVideoStreamArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "kinesisvideo");
        String name = unique("smoke-kvs", partitionCase);
        String arn = signed(region, "kinesisvideo").contentType(JSON).body("{\"StreamName\":\"" + name + "\"}")
                .post("/createStream").then().statusCode(200).extract().path("StreamARN");
        cleanup.register(() -> signed(region, "kinesisvideo").contentType(JSON).body("{\"StreamARN\":\"" + arn
                + "\"}").post("/deleteStream"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void keyspacesKeyspaceArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "cassandra");
        String name = unique("smoke_ks", partitionCase).replace('-', '_');
        String arn = signed(region, "cassandra").header("X-Amz-Target", "KeyspacesService.CreateKeyspace")
                .contentType("application/x-amz-json-1.0").body("{\"keyspaceName\":\"" + name + "\"}").post("/")
                .then().statusCode(200).extract().path("resourceArn");
        cleanup.register(() -> signed(region, "cassandra").header("X-Amz-Target", "KeyspacesService.DeleteKeyspace")
                .contentType("application/x-amz-json-1.0").body("{\"keyspaceName\":\"" + name + "\"}").post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void kendraIndexArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "kendra");
        String roleName = unique("smoke-kendra", partitionCase);
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":"
                + "{\"Service\":\"kendra.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        String role = signed(region, "iam").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", trust).post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.register(() -> signed(region, "iam").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteRole").formParam("RoleName", roleName).post("/"));
        String id = signed(region, "kendra").header("X-Amz-Target", "AWSKendraFrontendService.CreateIndex")
                .contentType(JSON_1_1).body("{\"Name\":\"" + roleName + "\",\"Edition\":\"DEVELOPER_EDITION\","
                        + "\"RoleArn\":\"" + role + "\"}").post("/").then().statusCode(200).extract().path("Id");
        cleanup.register(() -> signed(region, "kendra").header("X-Amz-Target", "AWSKendraFrontendService.DeleteIndex")
                .contentType(JSON_1_1).body("{\"Id\":\"" + id + "\"}").post("/"));
        String arn = role.substring(0, role.indexOf(":iam:")) + ":kendra:" + region + ":"
                + role.split(":")[4] + ":index/" + id;
        PartitionMatrix.assertArnIn(partitionCase, arn);
        signed(region, "kendra").header("X-Amz-Target", "AWSKendraFrontendService.TagResource").contentType(JSON_1_1)
                .body("{\"ResourceARN\":\"" + arn + "\",\"Tags\":[{\"Key\":\"smoke\",\"Value\":\"yes\"}]}")
                .post("/").then().statusCode(200);
        String key = signed(region, "kendra").header("X-Amz-Target", "AWSKendraFrontendService.ListTagsForResource")
                .contentType(JSON_1_1).body("{\"ResourceARN\":\"" + arn + "\"}").post("/").then().statusCode(200)
                .extract().path("Tags[0].Key");
        assertTrue("smoke".equals(key), key);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void glacierVaultArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "glacier");
        signed(region, "glacier").put("/-/vaults/partition-smoke").then().statusCode(201);
        String arn = signed(region, "glacier").get("/-/vaults/partition-smoke").then().statusCode(200)
                .extract().path("VaultARN");
        PartitionMatrix.assertArnIn(partitionCase, arn);
        signed(region, "glacier").delete("/-/vaults/partition-smoke").then().statusCode(204);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void grafanaMissingWorkspace(PartitionCase partitionCase) {
        String region = offered(partitionCase, "grafana");
        String type = signed(region, "grafana").get("/workspaces/g-0000000000").then().statusCode(404).extract()
                .header("x-amzn-ErrorType");
        assertTrue(type.contains("ResourceNotFoundException"), type);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void greengrassComponentArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "greengrass");
        String recipe = Base64.getEncoder().encodeToString(("{\"RecipeFormatVersion\":\"2020-01-25\","
                + "\"ComponentName\":\"com.example.Partition\",\"ComponentVersion\":\"1.0.0\"}")
                .getBytes(StandardCharsets.UTF_8));
        String arn = signed(region, "greengrass").contentType("application/json").body("{\"inlineRecipe\":\""
                + recipe + "\"}").post("/greengrass/v2/createComponentVersion").then().statusCode(201).extract()
                .path("arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
        signed(region, "greengrass").delete("/greengrass/v2/components/" + arn).then().statusCode(204);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void healthLakeDatastoreArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "healthlake");
        String arn = signed(region, "healthlake").header("X-Amz-Target", "HealthLake.CreateFHIRDatastore")
                .contentType("application/x-amz-json-1.0").body("{\"DatastoreTypeVersion\":\"R4\"}").post("/")
                .then().statusCode(200).extract().path("DatastoreArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void iotManagedIntegrationsCredentialLockerArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "iotmanagedintegrations");
        String id = signed(region, "iotmanagedintegrations").contentType("application/json").body("{}")
                .post("/credential-lockers").then().statusCode(200).extract().path("Id");
        String arn = signed(region, "iotmanagedintegrations").get("/credential-lockers/" + id).then().statusCode(200)
                .extract().path("Arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void iotWirelessServiceProfileArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "iotwireless");
        String arn = signed(region, "iotwireless").contentType("application/json").body("{}")
                .post("/service-profiles").then().statusCode(201).extract().path("Arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void iotSiteWiseGatewayArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "iotsitewise");
        String arn = signed(region, "iotsitewise").contentType("application/json").body("{\"gatewayName\":\"smoke\","
                + "\"gatewayPlatform\":{\"siemensIE\":{\"iotCoreThingName\":\"smoke-" + partitionCase.partition()
                + "\"}}}").post("/20200301/gateways").then().statusCode(201).extract().path("gatewayArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void iotFleetWiseSignalCatalogArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "iotfleetwise");
        String arn = signed(region, "iotfleetwise").header("X-Amz-Target", "IoTAutobahnControlPlane.CreateSignalCatalog")
                .contentType("application/x-amz-json-1.0").body("{\"name\":\"smoke\",\"nodes\":[{\"branch\":"
                        + "{\"fullyQualifiedName\":\"Vehicle\"}}]}").post("/")
                .then().statusCode(200).extract().path("arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
        signed(region, "iotfleetwise").header("X-Amz-Target", "IoTAutobahnControlPlane.CreateFleet")
                .contentType("application/x-amz-json-1.0").body("{\"fleetId\":\"smoke\",\"signalCatalogArn\":\""
                        + arn + "\"}").post("/").then().statusCode(200);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void ivsChannelArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ivs");
        String arn = signed(region, "ivs").contentType("application/json").body("{}").post("/CreatePlaybackRestrictionPolicy")
                .then().statusCode(200).extract().path("playbackRestrictionPolicy.arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
        signed(region, "ivs").contentType("application/json").body("{\"arn\":\"" + arn + "\"}")
                .post("/GetPlaybackRestrictionPolicy").then().statusCode(200);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void ivsRealtimeStageArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ivs");
        String arn = signed(region, "ivs").contentType("application/json").body("{}").post("/CreateStage")
                .then().statusCode(200).extract().path("stage.arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
        signed(region, "ivs").contentType("application/json").body("{\"arn\":\"" + arn + "\"}")
                .post("/GetStage").then().statusCode(200);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void imageBuilderComponentArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "imagebuilder");
        String arn = signed(region, "imagebuilder").contentType("application/json")
                .body("{\"name\":\"smoke\",\"semanticVersion\":\"1.0.0\",\"platform\":\"Linux\",\"clientToken\":"
                        + "\"smoke\",\"data\":\"name: smoke\\nschemaVersion: 1.0\\nphases:\\n  - name: build\\n"
                        + "    steps:\\n      - name: Hello\\n        action: ExecuteBash\\n        inputs:\\n"
                        + "          commands: [echo hello]\\n\"}")
                .put("/CreateComponent").then().statusCode(200).extract().path("componentBuildVersionArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
        signed(region, "imagebuilder").queryParam("componentBuildVersionArn", arn).get("/GetComponent")
                .then().statusCode(200);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void internetMonitorMonitorArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "internetmonitor");
        String name = "smoke-" + partitionCase.partition();
        String arn = signed(region, "internetmonitor").contentType("application/json")
                .body("{\"MonitorName\":\"" + name + "\",\"MaxCityNetworksToMonitor\":1}")
                .post("/v20210603/Monitors").then().statusCode(200).extract().path("Arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
        signed(region, "internetmonitor").get("/v20210603/Monitors/" + name).then().statusCode(200);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void ivsChatRoomArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ivschat");
        String arn = signed(region, "ivschat").contentType("application/json").body("{}").post("/CreateRoom")
                .then().statusCode(200).extract().path("arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
        signed(region, "ivschat").contentType("application/json").body("{\"identifier\":\"" + arn + "\"}")
                .post("/GetRoom").then().statusCode(200);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void directoryServiceAccessUrl(PartitionCase partitionCase) {
        String region = offered(partitionCase, "ds");
        String limit = signed(region, "ds").header("X-Amz-Target", "DirectoryService_20150416.GetDirectoryLimits")
                .contentType(JSON_1_1).body("{}").post("/").then().statusCode(200).extract()
                .path("DirectoryLimits.CloudOnlyMicrosoftADLimit").toString();
        assertEquals("20", limit);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void daxClusterArnAndEndpoint(PartitionCase partitionCase) {
        String region = offered(partitionCase, "dax");
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":"
                + "{\"Service\":\"dax.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        String roleName = unique("smoke-dax", partitionCase);
        String role = signed(region, "iam").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", trust)
                .post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.register(() -> signed(region, "iam").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteRole").formParam("RoleName", roleName).post("/"));
        String name = "smoke" + Long.toString(System.nanoTime(), 36);
        String endpoint = signed(region, "dax").header("X-Amz-Target", "AmazonDAXV3.CreateCluster")
                .contentType(JSON_1_1).body("{\"ClusterName\":\"" + name + "\",\"NodeType\":\"dax.t3.small\","
                        + "\"ReplicationFactor\":1,\"IamRoleArn\":\"" + role + "\"}")
                .post("/").then().statusCode(200).extract().path("Cluster.ClusterDiscoveryEndpoint.Address");
        cleanup.register(() -> signed(region, "dax").header("X-Amz-Target", "AmazonDAXV3.DeleteCluster")
                .contentType(JSON_1_1).body("{\"ClusterName\":\"" + name + "\"}").post("/"));
        String arn = signed(region, "dax").header("X-Amz-Target", "AmazonDAXV3.DescribeClusters")
                .contentType(JSON_1_1).body("{\"ClusterNames\":[\"" + name + "\"]}")
                .post("/").then().statusCode(200).extract().path("Clusters[0].ClusterArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
        assertTrue(endpoint.endsWith("." + region + "." + AwsRegions.dnsSuffixFor(region)), endpoint);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void chatbotCustomActionArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "chatbot");
        String name = unique("smoke-action", partitionCase);
        String arn = signed(region, "chatbot").contentType(JSON).body("{\"ActionName\":\"" + name
                + "\",\"Definition\":{\"CommandText\":\"aws s3 ls\"}}").post("/create-custom-action").then()
                .statusCode(200).extract().path("CustomActionArn");
        cleanup.register(() -> signed(region, "chatbot").contentType(JSON).body("{\"CustomActionArn\":\"" + arn
                + "\"}").post("/delete-custom-action"));
        PartitionMatrix.assertGlobalArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void bedrockDataAutomationLibraryArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "bedrock");
        String name = unique("smoke-library", partitionCase);
        String arn = signed(region, "bedrock").contentType(JSON).body("{\"libraryName\":\"" + name + "\"}")
                .put("/data-automation-libraries/").then().statusCode(200).extract().path("libraryArn");
        cleanup.register(() -> signed(region, "bedrock").urlEncodingEnabled(false)
                .delete("/data-automation-libraries/" + URLEncoder.encode(arn, StandardCharsets.UTF_8) + "/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void backupSearchJobArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "backup-search");
        String arn = signed(region, "backup-search").contentType(JSON)
                .body("{\"SearchScope\":{\"BackupResourceTypes\":[\"S3\"]}}").put("/search-jobs").then()
                .statusCode(200).extract().path("SearchJobArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void b2biProfileArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "b2bi");
        String id = signed(region, "b2bi").header("X-Amz-Target", "B2BI.CreateProfile").contentType(JSON_1_0)
                .body("{\"name\":\"smoke\",\"businessName\":\"Smoke\",\"phone\":\"+15555550100\","
                        + "\"logging\":\"DISABLED\"}").post("/").then().statusCode(200).extract().path("profileId");
        cleanup.register(() -> signed(region, "b2bi").header("X-Amz-Target", "B2BI.DeleteProfile")
                .contentType(JSON_1_0).body("{\"profileId\":\"" + id + "\"}").post("/"));
        String arn = signed(region, "b2bi").header("X-Amz-Target", "B2BI.GetProfile").contentType(JSON_1_0)
                .body("{\"profileId\":\"" + id + "\"}").post("/").then().statusCode(200).extract().path("profileArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void auditManagerControlArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "auditmanager");
        signed(region, "auditmanager").contentType(JSON).body("{}").post("/account/registerAccount").then()
                .statusCode(200);
        String name = unique("smoke-control", partitionCase);
        String id = signed(region, "auditmanager").contentType(JSON).body("{\"name\":\"" + name
                + "\",\"controlMappingSources\":[{\"sourceName\":\"manual\",\"sourceSetUpOption\":"
                + "\"Procedural_Controls_Mapping\",\"sourceType\":\"MANUAL\"}]}").post("/controls").then()
                .statusCode(200).extract().path("control.id");
        cleanup.register(() -> signed(region, "auditmanager").delete("/controls/" + id));
        String arn = signed(region, "auditmanager").get("/controls/" + id).then().statusCode(200).extract()
                .path("control.arn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void appRegistryApplicationArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "servicecatalog");
        String name = unique("smoke-app", partitionCase);
        String arn = signed(region, "servicecatalog").contentType(JSON).body("{\"name\":\"" + name + "\"}")
                .post("/applications").then().statusCode(201).extract().path("application.arn");
        cleanup.register(() -> signed(region, "servicecatalog").delete("/applications/" + name));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void appFlowConnectorProfileArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "appflow");
        String name = unique("smoke-profile", partitionCase);
        String arn = signed(region, "appflow").contentType(JSON)
                .body("{\"connectorProfileName\":\"" + name + "\",\"connectorType\":\"Salesforce\","
                        + "\"connectionMode\":\"Public\",\"connectorProfileConfig\":{\"connectorProfileProperties\":"
                        + "{},\"connectorProfileCredentials\":{\"Salesforce\":{\"accessToken\":\"t\"}}}}")
                .post("/create-connector-profile").then().statusCode(200).extract().path("connectorProfileArn");
        cleanup.register(() -> signed(region, "appflow").contentType(JSON)
                .body("{\"connectorProfileName\":\"" + name + "\"}").post("/delete-connector-profile"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void amplifyAppArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "amplify");
        String appId = signed(region, "amplify").contentType(JSON).body("{\"name\":\"smoke\"}")
                .post("/apps").then().statusCode(200).extract().path("app.appId");
        cleanup.register(() -> signed(region, "amplify").delete("/apps/" + appId));
        String arn = signed(region, "amplify").get("/apps/" + appId).then().statusCode(200)
                .extract().path("app.appArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void investigationGroupArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "aiops");
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":"
                + "{\"Service\":\"aiops.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        String roleName = unique("smoke-aiops", partitionCase);
        String role = signed(region, "iam").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", trust)
                .post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        cleanup.register(() -> signed(region, "iam").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteRole").formParam("RoleName", roleName).post("/"));
        String arn = signed(region, "aiops").contentType(JSON)
                .body("{\"name\":\"smoke\",\"roleArn\":\"" + role + "\"}")
                .post("/investigationGroups").then().statusCode(201).extract().path("arn");
        cleanup.register(() -> signed(region, "aiops").delete("/investigationGroups/smoke"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void privateCaArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "acm-pca");
        String arn = json11(region, "acm-pca", "ACMPrivateCA.CreateCertificateAuthority",
                "{\"CertificateAuthorityConfiguration\":{\"KeyAlgorithm\":\"EC_prime256v1\",\"SigningAlgorithm\":"
                        + "\"SHA256WITHECDSA\",\"Subject\":{\"CommonName\":\"smoke.floci.test\"}},"
                        + "\"CertificateAuthorityType\":\"ROOT\"}")
                .post("/").then().statusCode(200).extract().path("CertificateAuthorityArn");
        cleanup.register(() -> json11(region, "acm-pca", "ACMPrivateCA.DeleteCertificateAuthority",
                "{\"CertificateAuthorityArn\":\"" + arn + "\",\"PermanentDeletionTimeInDays\":7}").post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void bedrockAgentArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "bedrock");
        String name = unique("smoke-agent", partitionCase);
        Response created = signed(region, "bedrock").contentType(JSON)
                .body("{\"agentName\":\"" + name + "\",\"foundationModel\":\"anthropic.claude-v2\"}")
                .put("/agents/").then().statusCode(202).extract().response();
        String id = created.path("agent.agentId");
        cleanup.register(() -> signed(region, "bedrock").delete("/agents/" + id + "/"));
        PartitionMatrix.assertArnIn(partitionCase, created.path("agent.agentArn"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void comprehendJobArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "comprehend");
        String role = "arn:" + partitionCase.partition() + ":iam::" + PartitionMatrix.ACCOUNT + ":role/smoke";
        String arn = json11(region, "comprehend", "Comprehend_20171127.StartSentimentDetectionJob",
                "{\"InputDataConfig\":{\"S3Uri\":\"s3://smoke-in/docs\"},\"OutputDataConfig\":{\"S3Uri\":"
                        + "\"s3://smoke-out/\"},\"DataAccessRoleArn\":\"" + role + "\",\"LanguageCode\":\"en\"}")
                .post("/").then().statusCode(200).extract().path("JobArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void locationMapArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "geo");
        String name = unique("smoke-map", partitionCase);
        String arn = signed(region, "geo").contentType(JSON)
                .body("{\"MapName\":\"" + name + "\",\"Configuration\":{\"Style\":\"VectorEsriStreets\"}}")
                .post("/maps/v0/maps").then().statusCode(200).extract().path("MapArn");
        cleanup.register(() -> signed(region, "geo").delete("/maps/v0/maps/" + name));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void pollyLexiconArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "polly");
        String name = "smoke" + Long.toString(System.nanoTime(), 36).substring(0, 8);
        String lexicon = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><lexicon version=\"1.0\" "
                + "xmlns=\"http://www.w3.org/2005/01/pronunciation-lexicon\" alphabet=\"ipa\" xml:lang=\"en-US\">"
                + "<lexeme><grapheme>IaC</grapheme><alias>infrastructure as code</alias></lexeme></lexicon>";
        signed(region, "polly").contentType(JSON).body(Map.of("Content", lexicon))
                .put("/v1/lexicons/" + name).then().statusCode(200);
        cleanup.register(() -> signed(region, "polly").delete("/v1/lexicons/" + name));
        String arn = signed(region, "polly").get("/v1/lexicons/" + name).then().statusCode(200)
                .extract().path("LexiconAttributes.LexiconArn");
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void rekognitionCollectionArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "rekognition");
        String id = unique("smoke-collection", partitionCase);
        String arn = json11(region, "rekognition", "RekognitionService.CreateCollection",
                "{\"CollectionId\":\"" + id + "\"}").post("/").then().statusCode(200).extract().path("CollectionArn");
        cleanup.register(() -> json11(region, "rekognition", "RekognitionService.DeleteCollection",
                "{\"CollectionId\":\"" + id + "\"}").post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void textractAdapterArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "textract");
        String name = unique("smoke-adapter", partitionCase);
        String id = json11(region, "textract", "Textract.CreateAdapter",
                "{\"AdapterName\":\"" + name + "\",\"FeatureTypes\":[\"QUERIES\"]}")
                .post("/").then().statusCode(200).extract().path("AdapterId");
        cleanup.register(() -> json11(region, "textract", "Textract.DeleteAdapter",
                "{\"AdapterId\":\"" + id + "\"}").post("/"));
        // Textract returns no ARN; the adapter's ARN in the case's partition names it.
        String arn = "arn:" + partitionCase.partition() + ":textract:" + region + ":" + PartitionMatrix.ACCOUNT
                + ":/adapters/" + id;
        json11(region, "textract", "Textract.ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}")
                .post("/").then().statusCode(200);
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void transcribeVocabularyFilterArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "transcribe");
        String name = unique("smoke-filter", partitionCase);
        json11(region, "transcribe", "Transcribe.CreateVocabularyFilter",
                "{\"VocabularyFilterName\":\"" + name + "\",\"LanguageCode\":\"en-US\",\"Words\":[\"smoke\"]}")
                .post("/").then().statusCode(200);
        cleanup.register(() -> json11(region, "transcribe", "Transcribe.DeleteVocabularyFilter",
                "{\"VocabularyFilterName\":\"" + name + "\"}").post("/"));
        String arn = "arn:" + partitionCase.partition() + ":transcribe:" + region + ":" + PartitionMatrix.ACCOUNT
                + ":vocabulary-filter/" + name;
        String listed = json11(region, "transcribe", "Transcribe.ListTagsForResource",
                "{\"ResourceArn\":\"" + arn + "\"}").post("/").then().statusCode(200).extract().path("ResourceArn");
        PartitionMatrix.assertArnIn(partitionCase, listed);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void translateTerminologyArn(PartitionCase partitionCase) {
        String region = offered(partitionCase, "translate");
        String name = unique("smoke-terms", partitionCase);
        String file = Base64.getEncoder().encodeToString("en,fr\nhello,bonjour\n".getBytes(StandardCharsets.UTF_8));
        String arn = json11(region, "translate", "AWSShineFrontendService_20170701.ImportTerminology",
                "{\"Name\":\"" + name + "\",\"MergeStrategy\":\"OVERWRITE\",\"TerminologyData\":{\"File\":\""
                        + file + "\",\"Format\":\"CSV\"}}")
                .post("/").then().statusCode(200).extract().path("TerminologyProperties.Arn");
        cleanup.register(() -> json11(region, "translate", "AWSShineFrontendService_20170701.DeleteTerminology",
                "{\"Name\":\"" + name + "\"}").post("/"));
        PartitionMatrix.assertArnIn(partitionCase, arn);
    }

    private static String offered(PartitionCase partitionCase, String signingName) {
        assumeTrue(!AwsPartitions.publishesSomewhere(signingName)
                        || AwsPartitions.byId(partitionCase.partition()).offersSigningName(signingName),
                partitionCase.partition() + " does not offer " + signingName);
        return partitionCase.region();
    }

    /** The host is in the case's partition and names the case's region, not another one of that partition. */
    private static void assertRegionalHostIn(PartitionCase partitionCase, String host) {
        PartitionMatrix.assertHostIn(partitionCase, host);
        assertTrue(host.contains("." + partitionCase.region() + "."),
                host + " should name the region " + partitionCase.region());
    }

    private static String unique(String prefix, PartitionCase partitionCase) {
        return prefix + "-" + partitionCase.partition() + "-" + Long.toString(System.nanoTime(), 36);
    }

    private static RequestSpecification signed(String region, String signingName) {
        return given().header("Authorization", PartitionMatrix.sigV4Auth(region, signingName));
    }

    private static RequestSpecification json10(String region, String signingName, String target, String body) {
        return signed(region, signingName)
            .header("X-Amz-Target", target)
            .contentType(JSON_1_0)
            .body(body);
    }

    private static RequestSpecification json11(String region, String signingName, String target, String body) {
        return signed(region, signingName)
            .header("X-Amz-Target", target)
            .contentType(JSON_1_1)
            .body(body);
    }
}
