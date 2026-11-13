package io.floci.aws.services.auditmanager;

import io.floci.aws.core.common.AwsArnUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What every Audit Manager account starts with: the control domains of the AWS Control Catalog that insights group
 * controls by, the standard controls (each mapped to an AWS Config managed rule or to manual evidence) and the
 * standard framework built from them, the keywords a control's data source can name, and the AWS services an
 * assessment can scope. Identifiers are derived from names, so they are the same in every account and Region.
 */
final class AuditManagerCatalog {
    private AuditManagerCatalog() {
    }

    /** A Control Catalog domain; its ARN is the {@code controlDomainId} insights take. */
    record Domain(String id, String name) {
        String arn(String partition) {
            return AwsArnUtils.Arn.global(partition, "controlcatalog", "", "domain/" + id).toString();
        }
    }

    /** A standard control: its data source and keyword, and the domain it belongs to. */
    record StandardControl(String id, String name, String description, String domain, String sourceType,
                           String keyword, String testingInformation) {
    }

    static final List<Domain> DOMAINS = List.of(
            domain("Asset management"),
            domain("Data protection"),
            domain("Identity & access management"),
            domain("Logging & monitoring"),
            domain("Network security"),
            domain("Resilience"));

    static final List<StandardControl> STANDARD_CONTROLS = List.of(
            control("S3 buckets block public read access", "Data protection", "AWS_Config",
                    "S3_BUCKET_PUBLIC_READ_PROHIBITED", "Checks that your S3 buckets don't allow public read access."),
            control("S3 buckets encrypt objects at rest", "Data protection", "AWS_Config",
                    "S3_BUCKET_SERVER_SIDE_ENCRYPTION_ENABLED",
                    "Checks that your S3 buckets have default encryption enabled."),
            control("EBS volumes are encrypted", "Data protection", "AWS_Config", "ENCRYPTED_VOLUMES",
                    "Checks that attached EBS volumes are encrypted."),
            control("Root user has MFA", "Identity & access management", "AWS_Config", "ROOT_ACCOUNT_MFA_ENABLED",
                    "Checks that the root user of the account requires multi-factor authentication."),
            control("IAM password policy is strong", "Identity & access management", "AWS_Config",
                    "IAM_PASSWORD_POLICY", "Checks that the account password policy meets the requirements set."),
            control("IAM users have no inline policies", "Identity & access management", "AWS_Config",
                    "IAM_USER_NO_POLICIES_CHECK", "Checks that no IAM user has policies attached directly."),
            control("CloudTrail is enabled", "Logging & monitoring", "AWS_Config", "CLOUD_TRAIL_ENABLED",
                    "Checks that the account has at least one CloudTrail trail."),
            control("CloudWatch log groups are encrypted", "Logging & monitoring", "AWS_Config",
                    "CLOUDWATCH_LOG_GROUP_ENCRYPTED", "Checks that CloudWatch Logs log groups are encrypted with KMS."),
            control("Security groups restrict SSH", "Network security", "AWS_Config", "INCOMING_SSH_DISABLED",
                    "Checks that security groups don't allow unrestricted incoming SSH traffic."),
            control("VPC flow logs are enabled", "Network security", "AWS_Config", "VPC_FLOW_LOGS_ENABLED",
                    "Checks that VPC flow logs are enabled for every VPC."),
            control("DynamoDB tables have point-in-time recovery", "Resilience", "AWS_Config",
                    "DYNAMODB_PITR_ENABLED", "Checks that DynamoDB tables have point-in-time recovery enabled."),
            control("RDS instances have backups", "Resilience", "AWS_Config", "DB_INSTANCE_BACKUP_ENABLED",
                    "Checks that RDS DB instances have backups enabled."),
            control("Asset inventory is maintained", "Asset management", "MANUAL", null,
                    "Upload the current inventory of systems in scope and who owns each."));

    /** The standard framework every account can assess against, one control set per domain. */
    static final String SAMPLE_FRAMEWORK_NAME = "AWS Audit Manager Sample Framework";
    static final String SAMPLE_FRAMEWORK_ID = nameId("framework:" + SAMPLE_FRAMEWORK_NAME);
    static final String SAMPLE_FRAMEWORK_DESCRIPTION = "A sample framework of standard controls, grouped by control "
            + "domain, that collects evidence from AWS Config rule evaluations.";

    static final Map<String, List<String>> KEYWORDS = Map.of(
            "AWS_Config", List.of("ACCESS_KEYS_ROTATED", "CLOUDWATCH_LOG_GROUP_ENCRYPTED", "CLOUD_TRAIL_ENABLED",
                    "DB_INSTANCE_BACKUP_ENABLED", "DYNAMODB_PITR_ENABLED", "EBS_SNAPSHOT_PUBLIC_RESTORABLE_CHECK",
                    "EC2_INSTANCE_NO_PUBLIC_IP", "ENCRYPTED_VOLUMES", "IAM_PASSWORD_POLICY", "IAM_ROOT_ACCESS_KEY_CHECK",
                    "IAM_USER_MFA_ENABLED", "IAM_USER_NO_POLICIES_CHECK", "INCOMING_SSH_DISABLED",
                    "KMS_CMK_NOT_SCHEDULED_FOR_DELETION", "LAMBDA_FUNCTION_PUBLIC_ACCESS_PROHIBITED",
                    "RDS_INSTANCE_PUBLIC_ACCESS_CHECK", "RDS_STORAGE_ENCRYPTED", "ROOT_ACCOUNT_MFA_ENABLED",
                    "S3_BUCKET_LOGGING_ENABLED", "S3_BUCKET_PUBLIC_READ_PROHIBITED",
                    "S3_BUCKET_PUBLIC_WRITE_PROHIBITED", "S3_BUCKET_SERVER_SIDE_ENCRYPTION_ENABLED",
                    "S3_BUCKET_VERSIONING_ENABLED", "SNS_ENCRYPTED_KMS", "SQS_QUEUE_ENCRYPTED", "VPC_FLOW_LOGS_ENABLED"),
            "AWS_Security_Hub", List.of("ACM.1", "CloudTrail.1", "CloudTrail.2", "DynamoDB.2", "EC2.2", "EC2.7",
                    "IAM.1", "IAM.3", "IAM.4", "IAM.6", "KMS.4", "Lambda.1", "RDS.2", "RDS.3", "S3.1", "S3.2", "S3.4",
                    "S3.8", "SNS.1", "SQS.1"),
            "AWS_Cloudtrail", List.of("cloudtrail_CreateTrail", "cloudtrail_DeleteTrail", "cloudtrail_StopLogging",
                    "ec2_AuthorizeSecurityGroupIngress", "ec2_CreateVpc", "ec2_RunInstances", "iam_AttachRolePolicy",
                    "iam_CreateAccessKey", "iam_CreateRole", "iam_CreateUser", "iam_DeleteRole", "iam_PutRolePolicy",
                    "kms_DisableKey", "kms_ScheduleKeyDeletion", "s3_CreateBucket", "s3_DeleteBucket",
                    "s3_PutBucketPolicy", "s3_PutBucketPublicAccessBlock"),
            "AWS_API_Call", List.of("cloudtrail_DescribeTrails", "config_DescribeConfigRules",
                    "dynamodb_DescribeContinuousBackups", "ec2_DescribeSecurityGroups", "ec2_DescribeVolumes",
                    "ec2_DescribeVpcs", "iam_GetAccountPasswordPolicy", "iam_GetAccountSummary", "iam_ListRoles",
                    "iam_ListUsers", "kms_ListKeys", "rds_DescribeDBInstances", "s3_GetBucketEncryption",
                    "s3_GetPublicAccessBlock", "s3_ListBuckets"),
            "MANUAL", List.of());

    /** An AWS service an assessment can scope: name, display name, description and category. */
    record Service(String name, String displayName, String description, String category) {
    }

    static final List<Service> SERVICES = List.of(
            service("acm", "AWS Certificate Manager", "Provision and manage TLS certificates.", "Security"),
            service("apigateway", "Amazon API Gateway", "Create, publish and secure APIs.", "Networking"),
            service("cloudfront", "Amazon CloudFront", "Deliver content through a global CDN.", "Networking"),
            service("cloudtrail", "AWS CloudTrail", "Record API activity in your account.", "Management"),
            service("cloudwatch", "Amazon CloudWatch", "Monitor resources and applications.", "Management"),
            service("config", "AWS Config", "Record and evaluate resource configurations.", "Management"),
            service("dynamodb", "Amazon DynamoDB", "A managed NoSQL database.", "Database"),
            service("ec2", "Amazon EC2", "Virtual servers in the cloud.", "Compute"),
            service("ecr", "Amazon ECR", "A managed container image registry.", "Containers"),
            service("ecs", "Amazon ECS", "Run containers on managed clusters.", "Containers"),
            service("eks", "Amazon EKS", "Run Kubernetes on AWS.", "Containers"),
            service("elasticloadbalancing", "Elastic Load Balancing", "Distribute traffic across targets.",
                    "Networking"),
            service("guardduty", "Amazon GuardDuty", "Detect threats in your accounts.", "Security"),
            service("iam", "AWS Identity and Access Management", "Manage access to AWS resources.", "Security"),
            service("kms", "AWS Key Management Service", "Create and control encryption keys.", "Security"),
            service("lambda", "AWS Lambda", "Run code without managing servers.", "Compute"),
            service("logs", "Amazon CloudWatch Logs", "Store and query log data.", "Management"),
            service("rds", "Amazon RDS", "Managed relational databases.", "Database"),
            service("route53", "Amazon Route 53", "DNS and domain registration.", "Networking"),
            service("s3", "Amazon S3", "Object storage.", "Storage"),
            service("secretsmanager", "AWS Secrets Manager", "Store and rotate secrets.", "Security"),
            service("securityhub", "AWS Security Hub", "Aggregate security findings and checks.", "Security"),
            service("sns", "Amazon SNS", "Publish and deliver messages.", "Application integration"),
            service("sqs", "Amazon SQS", "Managed message queues.", "Application integration"),
            service("ssm", "AWS Systems Manager", "Operate resources at scale.", "Management"),
            service("vpc", "Amazon VPC", "Isolated virtual networks.", "Networking"));

    /** The display name a data source type appears under in {@code controlSources} and {@code evidenceSources}. */
    static String sourceDisplayName(String sourceType) {
        return switch (sourceType) {
            case "AWS_Cloudtrail" -> "AWS CloudTrail";
            case "AWS_Config" -> "AWS Config";
            case "AWS_Security_Hub" -> "AWS Security Hub";
            case "AWS_API_Call" -> "AWS API calls";
            case "Common_Control" -> "Common control";
            case "Core_Control" -> "Core control";
            default -> "Manual";
        };
    }

    static Optional<StandardControl> standardControl(String id) {
        return STANDARD_CONTROLS.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    static Optional<Domain> domainByName(String name) {
        return DOMAINS.stream().filter(d -> d.name().equals(name)).findFirst();
    }

    /** The domain a {@code controlDomainId} names: its ARN or bare id. */
    static Optional<Domain> domain(String partition, String controlDomainId) {
        return DOMAINS.stream()
                .filter(d -> d.id().equals(controlDomainId) || d.arn(partition).equals(controlDomainId))
                .findFirst();
    }

    static String nameId(String name) {
        return UUID.nameUUIDFromBytes(("auditmanager:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Domain domain(String name) {
        return new Domain(nameId("domain:" + name).replace("-", "").substring(0, 25), name);
    }

    private static StandardControl control(String name, String domain, String sourceType, String keyword,
                                           String description) {
        return new StandardControl(nameId("control:" + name), name, description, domain, sourceType, keyword,
                "AWS_Config".equals(sourceType) ? "Review the AWS Config evaluations of " + keyword + "."
                        : "Review the evidence uploaded for this control.");
    }

    private static Service service(String name, String displayName, String description, String category) {
        return new Service(name, displayName, description, category);
    }
}
