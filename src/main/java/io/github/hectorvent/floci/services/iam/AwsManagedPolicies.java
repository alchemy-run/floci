package io.github.hectorvent.floci.services.iam;

import java.util.List;

/**
 * Catalog of commonly-used AWS managed policies seeded at startup.
 *
 * <p>Most documents stay a permissive wildcard — Floci does not model every
 * AWS managed-policy statement, and global IAM enforcement is off. Lambda
 * execution-role policies are the exception: Alchemy attaches
 * {@code AWSLambdaBasicExecutionRole} (and peers) to every function, and a
 * {@code Action:*, Resource:*} document would make role-session evaluation
 * of {@code ses:SendEmail} / {@code kms:GetKeyRotationStatus} always ALLOW.
 */
final class AwsManagedPolicies {

    static final String ARN_PREFIX = "arn:aws:iam::aws:policy";

    static final String PERMISSIVE_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":"
            + "[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}]}";

    /** AWS {@code AWSLambdaBasicExecutionRole} — CloudWatch Logs write only. */
    static final String LAMBDA_BASIC_EXECUTION_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":[\"logs:CreateLogGroup\",\"logs:CreateLogStream\",\"logs:PutLogEvents\"],"
            + "\"Resource\":\"arn:aws:logs:*:*:*\"}]}";

    /** AWS {@code AWSXRayDaemonWriteAccess} — X-Ray put/sampling only. */
    static final String XRAY_DAEMON_WRITE_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":[\"xray:PutTraceSegments\",\"xray:PutTelemetryRecords\","
            + "\"xray:GetSamplingRules\",\"xray:GetSamplingTargets\","
            + "\"xray:GetSamplingStatisticSummaries\"],\"Resource\":\"*\"}]}";

    record ManagedPolicyDef(String name, String path, String description, String document) {
        ManagedPolicyDef(String name, String path, String description) {
            this(name, path, description, PERMISSIVE_DOCUMENT);
        }

        String arn() {
            return ARN_PREFIX + path + name;
        }
    }

    static String documentFor(String name) {
        return POLICIES.stream()
                .filter(def -> def.name().equals(name))
                .map(ManagedPolicyDef::document)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown managed policy: " + name));
    }

    static final List<ManagedPolicyDef> POLICIES = List.of(
        // General access policies
        new ManagedPolicyDef("AdministratorAccess", "/",
                "Provides full access to AWS services and resources."),
        new ManagedPolicyDef("PowerUserAccess", "/",
                "Provides full access to AWS services and resources, but does not allow management of Users and groups."),
        new ManagedPolicyDef("ReadOnlyAccess", "/",
                "Provides read-only access to AWS services and resources."),
        new ManagedPolicyDef("SecurityAudit", "/",
                "The security audit template grants access to read security configuration metadata. "
                + "It is useful for software that audits the configuration of an AWS account."),
        new ManagedPolicyDef("IAMFullAccess", "/",
                "Provides full access to IAM."),
        new ManagedPolicyDef("AmazonS3FullAccess", "/",
                "Provides full access to all buckets via the AWS Management Console."),
        new ManagedPolicyDef("AmazonS3ReadOnlyAccess", "/",
                "Provides read-only access to all buckets via the AWS Management Console."),
        new ManagedPolicyDef("AmazonDynamoDBFullAccess", "/",
                "Provides full access to Amazon DynamoDB via the AWS Management Console."),
        new ManagedPolicyDef("AmazonEC2FullAccess", "/",
                "Provides full access to Amazon EC2 via the AWS Management Console."),
        new ManagedPolicyDef("AmazonEC2ContainerRegistryReadOnly", "/",
                "Provides read-only access to Amazon EC2 Container Registry repositories."),
        new ManagedPolicyDef("AmazonSQSFullAccess", "/",
                "Provides full access to Amazon SQS via the AWS Management Console."),
        new ManagedPolicyDef("AmazonSNSFullAccess", "/",
                "Provides full access to Amazon SNS via the AWS Management Console."),
        new ManagedPolicyDef("AmazonVPCFullAccess", "/",
                "Provides full access to Amazon VPC via the AWS Management Console."),
        new ManagedPolicyDef("CloudWatchFullAccess", "/",
                "Provides full access to CloudWatch."),
        new ManagedPolicyDef("CloudWatchAgentServerPolicy", "/",
                "Provides permissions required to use the CloudWatch agent on servers."),
        new ManagedPolicyDef("AWSLambdaFullAccess", "/",
                "Provides full access to Lambda, S3, DynamoDB, CloudWatch Metrics and Logs."),
        new ManagedPolicyDef("AWSCloudFormationReadOnlyAccess", "/",
                "Provides access to AWS CloudFormation via the AWS Management Console."),
        new ManagedPolicyDef("AWSCloudFormationFullAccess", "/",
                "Provides full access to AWS CloudFormation."),
        new ManagedPolicyDef("AWSXRayDaemonWriteAccess", "/",
                "Allows write permissions to the AWS X-Ray daemon.",
                XRAY_DAEMON_WRITE_DOCUMENT),
        new ManagedPolicyDef("AmazonElasticFileSystemClientFullAccess", "/",
                "Provides root client access to an Amazon EFS file system."),
        // Attached by the roles `cdk bootstrap` creates, so without it the CDKToolkit stack
        // rolls back and no CDK app can be deployed.
        new ManagedPolicyDef("AmazonAthenaFullAccess", "/",
                "Provide full access to Amazon Athena and scoped access to the dependencies "
                + "needed to enable querying, writing results, and data management."),
        new ManagedPolicyDef("AmazonRedshiftFullAccess", "/",
                "Provides full access to Amazon Redshift via the AWS Management Console."),
        new ManagedPolicyDef("AmazonS3TablesReadOnlyAccess", "/",
                "Provides read only access to all S3 table buckets."),
        new ManagedPolicyDef("AWSCloudTrail_FullAccess", "/",
                "Provides full access to AWS CloudTrail."),
        new ManagedPolicyDef("AWSCloudTrail_ReadOnlyAccess", "/",
                "Provides read-only access to AWS CloudTrail."),

        // Lambda execution role policies
        new ManagedPolicyDef("AWSLambdaBasicExecutionRole", "/service-role/",
                "Provides write permissions to CloudWatch Logs.",
                LAMBDA_BASIC_EXECUTION_DOCUMENT),
        new ManagedPolicyDef("AWSLambdaBasicDurableExecutionRolePolicy", "/service-role/",
                "Provides write permissions to CloudWatch Logs and read/write permissions to durable execution APIs for Lambda durable functions.",
                LAMBDA_BASIC_EXECUTION_DOCUMENT),
        new ManagedPolicyDef("AWSLambdaDynamoDBExecutionRole", "/service-role/",
                "Provides list and read access to DynamoDB streams and write permissions to CloudWatch Logs."),
        new ManagedPolicyDef("AWSLambdaKinesisExecutionRole", "/service-role/",
                "Provides list and read access to Kinesis streams and write permissions to CloudWatch Logs."),
        new ManagedPolicyDef("AWSLambdaMSKExecutionRole", "/service-role/",
                "Provides permissions required to access an MSK cluster within a VPC, manage network interfaces, and write to CloudWatch Logs."),
        new ManagedPolicyDef("AWSLambdaSQSQueueExecutionRole", "/service-role/",
                "Provides receive message, delete message, and read attribute access to SQS queues, and write permissions to CloudWatch Logs."),
        new ManagedPolicyDef("AWSLambdaVPCAccessExecutionRole", "/service-role/",
                "Provides minimum permissions for a Lambda function to execute while accessing a resource within a VPC."),

        // ECS / EKS execution role policies
        new ManagedPolicyDef("AmazonECSTaskExecutionRolePolicy", "/service-role/",
                "Provides the Amazon ECS container agent and Fargate agent permissions to make AWS API calls on your behalf."),
        new ManagedPolicyDef("AmazonEKSFargatePodExecutionRolePolicy", "/",
                "Provides access to other AWS service resources required to run Amazon EKS pods on AWS Fargate."),

        // EKS cluster and node group policies (required by the EKS console/SDK and
        // the terraform-aws-modules/eks module — see #1092).
        new ManagedPolicyDef("AmazonEKSClusterPolicy", "/",
                "Provides Kubernetes the permissions it requires to manage resources on your behalf."),
        new ManagedPolicyDef("AmazonEKSServicePolicy", "/",
                "This policy allows Amazon Elastic Container Service for Kubernetes to create and manage the necessary resources to operate EKS Clusters."),
        new ManagedPolicyDef("AmazonEKSVPCResourceController", "/",
                "Policy used by VPC Resource Controller to manage ENI and IPs for worker nodes."),
        new ManagedPolicyDef("AmazonEKSWorkerNodePolicy", "/",
                "This policy allows Amazon EKS worker nodes to connect to Amazon EKS Clusters."),
        new ManagedPolicyDef("AmazonEKS_CNI_Policy", "/",
                "Provides the Amazon VPC CNI Plugin the permissions it requires to modify the IP address configuration on your EKS worker nodes."),

        // RDS execution role policy
        new ManagedPolicyDef("AmazonRDSEnhancedMonitoringRole", "/service-role/",
                "Provides permissions required for Amazon RDS Enhanced Monitoring."),

        // Glue job/crawler execution role — Alchemy Job/Crawler/Bindings attach this ARN.
        new ManagedPolicyDef("AWSGlueServiceRole", "/service-role/",
                "Policy for AWS Glue service role."),

        // Batch unmanaged CE service role — Alchemy Batch Bindings attach this ARN.
        new ManagedPolicyDef("AWSBatchServiceRole", "/service-role/",
                "Provides access to AWS Batch resources to create and manage compute environments and job queues."),

        // AWS Backup service roles — Alchemy Backup Bindings attach these ARNs.
        new ManagedPolicyDef("AWSBackupServiceRolePolicyForBackup", "/service-role/",
                "Provides AWS Backup permission to create backups of all supported resource types."),
        new ManagedPolicyDef("AWSBackupServiceRolePolicyForRestores", "/service-role/",
                "Provides AWS Backup permission to restore backups of all supported resource types."),

        // S3 Object Lambda execution role policy
        new ManagedPolicyDef("AmazonS3ObjectLambdaExecutionRolePolicy", "/service-role/",
                "Provides write permissions to CloudWatch Logs for S3 Object Lambda access points."),

        // CloudWatch Lambda execution role policies
        new ManagedPolicyDef("CloudWatchLambdaInsightsExecutionRolePolicy", "/",
                "Allows Lambda Insights to create and write to CloudWatch Logs log groups for Lambda Insights monitoring."),
        new ManagedPolicyDef("CloudWatchLambdaApplicationSignalsExecutionRolePolicy", "/",
                "Provides write access to X-Ray and CloudWatch Application Signals log group."),

        // API Gateway execution role policy
        new ManagedPolicyDef("AmazonAPIGatewayPushToCloudWatchLogs", "/service-role/",
                "Allows API Gateway to push logs to CloudWatch Logs."),

        // Config execution role policy
        new ManagedPolicyDef("AWSConfigRulesExecutionRole", "/service-role/",
                "Allows AWS Config Rules Lambda functions to call AWS services and read the configuration of AWS resources."),

        // MSK replicator execution role policy
        new ManagedPolicyDef("AWSMSKReplicatorExecutionRole", "/service-role/",
                "Grants permissions to Amazon MSK Replicator to replicate data between MSK Clusters."),

        // SSM Automation execution role policies
        new ManagedPolicyDef("AWS-SSM-DiagnosisAutomation-ExecutionRolePolicy", "/",
                "Provides permissions for AWS Systems Manager diagnosis automation execution."),
        new ManagedPolicyDef("AWS-SSM-RemediationAutomation-ExecutionRolePolicy", "/",
                "Provides permissions for AWS Systems Manager remediation automation execution."),
        new ManagedPolicyDef("AmazonSSMManagedInstanceCore", "/",
                "Provides permissions required for instances to use AWS Systems Manager core service functionality."),

        // SageMaker execution role policies
        new ManagedPolicyDef("AmazonSageMakerGeospatialExecutionRole", "/service-role/",
                "Provides full access to Amazon SageMaker Geospatial capabilities and related services."),
        new ManagedPolicyDef("AmazonSageMakerCanvasEMRServerlessExecutionRolePolicy", "/",
                "Provides access for Amazon SageMaker Canvas to manage EMR Serverless resources."),

        // SageMaker Studio execution role policies
        new ManagedPolicyDef("SageMakerStudioBedrockFunctionExecutionRolePolicy", "/service-role/",
                "Provides permissions for SageMaker Studio Bedrock function execution role."),
        new ManagedPolicyDef("SageMakerStudioDomainExecutionRolePolicy", "/service-role/",
                "Provides permissions for the SageMaker Studio domain execution role."),
        new ManagedPolicyDef("SageMakerStudioQueryExecutionRolePolicy", "/service-role/",
                "Provides permissions for SageMaker Studio query execution role."),

        // Amazon DataZone execution role policy
        new ManagedPolicyDef("AmazonDataZoneDomainExecutionRolePolicy", "/service-role/",
                "Provides permissions for the Amazon DataZone domain execution role."),

        // Amazon Bedrock policies
        new ManagedPolicyDef("AmazonBedrockFullAccess", "/",
                "Provides full access to Amazon Bedrock as well as limited access to related services "
                + "that are required by it"),
        new ManagedPolicyDef("AmazonBedrockReadOnly", "/",
                "Provides read only access to Amazon Bedrock"),
        new ManagedPolicyDef("AmazonBedrockAgentCoreMemoryBedrockModelInferenceExecutionRolePolicy", "/",
                "Provides Bedrock Model inference permissions to Bedrock agent core memory."),

        // AWS Partner Central execution role policy
        new ManagedPolicyDef("AWSPartnerCentralSellingResourceSnapshotJobExecutionRolePolicy", "/",
                "Provides permissions for AWS Partner Central resource snapshot job execution role."),

        // CloudWatch investigations (AIOps) assistant role — Alchemy InvestigationGroup
        // fixtures attach this ARN at create time.
        new ManagedPolicyDef("AIOpsAssistantPolicy", "/",
                "Provides permissions for CloudWatch investigations to access telemetry during investigations."),

        // AWS Budgets action execution role — Alchemy Budgets Bindings attach this ARN.
        new ManagedPolicyDef("AWSBudgetsActionsWithAWSResourceControlAccess", "/",
                "Provides full access to AWS Budgets Actions including using Budgets Actions to control AWS resources."),
        // IAM policy the fixture budget action applies to target roles.
        new ManagedPolicyDef("AWSDenyAll", "/",
                "AWS managed policy that denies all actions on all resources.")
    );

    private AwsManagedPolicies() {}
}
