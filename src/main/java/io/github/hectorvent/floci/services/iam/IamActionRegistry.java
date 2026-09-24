package io.github.hectorvent.floci.services.iam;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps (credentialScope, httpMethod, requestPath) → IAM action string.
 *
 * For Query-protocol services (SQS, SNS, IAM, STS, ...) the Action form
 * parameter is mapped directly to {@code <service>:<Action>}.
 *
 * For REST-JSON services the first matching rule wins (specific before wildcard).
 */
@ApplicationScoped
public class IamActionRegistry {

    private static final Logger LOG = Logger.getLogger(IamActionRegistry.class);

    private record ActionRule(String service, String method, Pattern pathPattern, String action) {}

    private static final List<ActionRule> RULES = List.of(
        // ── S3 ─────────────────────────────────────────────────────────────────
        rule("s3", "GET",    "^/?$",                              "s3:ListAllMyBuckets"),
        rule("s3", "PUT",    "^/[^/]+/?$",                       "s3:CreateBucket"),
        rule("s3", "DELETE", "^/[^/]+/?$",                       "s3:DeleteBucket"),
        rule("s3", "HEAD",   "^/[^/]+/?$",                       "s3:ListBucket"),
        rule("s3", "GET",    "^/[^/]+/?$",                       "s3:ListBucket"),
        rule("s3", "GET",    "^/[^/]+/.+",                       "s3:GetObject"),
        rule("s3", "PUT",    "^/[^/]+/.+",                       "s3:PutObject"),
        rule("s3", "DELETE", "^/[^/]+/.+",                       "s3:DeleteObject"),
        rule("s3", "HEAD",   "^/[^/]+/.+",                       "s3:GetObject"),

        // ── Lambda ──────────────────────────────────────────────────────────────
        rule("lambda", "GET",    ".*/functions$",                          "lambda:ListFunctions"),
        rule("lambda", "POST",   ".*/functions$",                          "lambda:CreateFunction"),
        rule("lambda", "GET",    ".*/functions/[^/]+$",                    "lambda:GetFunction"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/code$",               "lambda:UpdateFunctionCode"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/configuration$",      "lambda:UpdateFunctionConfiguration"),
        rule("lambda", "DELETE", ".*/functions/[^/]+$",                    "lambda:DeleteFunction"),
        rule("lambda", "POST",   ".*/functions/[^/]+/invocations$",        "lambda:InvokeFunction"),
        rule("lambda", "GET",    ".*/functions/[^/]+/aliases$",            "lambda:ListAliases"),
        rule("lambda", "POST",   ".*/functions/[^/]+/aliases$",            "lambda:CreateAlias"),
        rule("lambda", "GET",    ".*/functions/[^/]+/aliases/[^/]+$",      "lambda:GetAlias"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/aliases/[^/]+$",      "lambda:UpdateAlias"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/aliases/[^/]+$",      "lambda:DeleteAlias"),
        rule("lambda", "GET",    ".*/functions/[^/]+/policy$",             "lambda:GetPolicy"),
        rule("lambda", "POST",   ".*/functions/[^/]+/policy$",             "lambda:AddPermission"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/policy/.+",           "lambda:RemovePermission"),
        rule("lambda", "GET",    ".*/event-source-mappings$",              "lambda:ListEventSourceMappings"),
        rule("lambda", "POST",   ".*/event-source-mappings$",              "lambda:CreateEventSourceMapping"),
        rule("lambda", "DELETE", ".*/event-source-mappings/[^/]+$",        "lambda:DeleteEventSourceMapping"),
        rule("lambda", "GET",    ".*/functions/[^/]+/url$",                "lambda:GetFunctionUrlConfig"),
        rule("lambda", "POST",   ".*/functions/[^/]+/url$",                "lambda:CreateFunctionUrlConfig"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/url$",                "lambda:UpdateFunctionUrlConfig"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/url$",                "lambda:DeleteFunctionUrlConfig"),

        // ── DynamoDB (JSON 1.1, action from X-Amz-Target handled separately) ──
        // Handled via Query-style action extraction in the filter

        // ── RDS Data API ───────────────────────────────────────────────────────
        rule("rds-data", "POST", "^/Execute/?$",              "rds-data:ExecuteStatement"),
        rule("rds-data", "POST", "^/ExecuteSql/?$",           "rds-data:ExecuteSql"),
        rule("rds-data", "POST", "^/BatchExecute/?$",         "rds-data:BatchExecuteStatement"),
        rule("rds-data", "POST", "^/BeginTransaction/?$",     "rds-data:BeginTransaction"),
        rule("rds-data", "POST", "^/CommitTransaction/?$",    "rds-data:CommitTransaction"),
        rule("rds-data", "POST", "^/RollbackTransaction/?$",  "rds-data:RollbackTransaction"),

        // ── API Gateway ────────────────────────────────────────────────────────
        rule("apigateway", "GET",    ".*/account$",                       "apigateway:GET"),
        rule("apigateway", "PATCH",  ".*/account$",                       "apigateway:PATCH"),
        rule("apigateway", "GET",    ".*/restapis$",                        "apigateway:GET"),
        rule("apigateway", "POST",   ".*/restapis$",                        "apigateway:POST"),
        rule("apigateway", "GET",    ".*/restapis/.+",                      "apigateway:GET"),
        rule("apigateway", "PUT",    ".*/restapis/.+",                      "apigateway:PUT"),
        rule("apigateway", "PATCH",  ".*/restapis/.+",                      "apigateway:PATCH"),
        rule("apigateway", "DELETE", ".*/restapis/.+",                      "apigateway:DELETE"),
        rule("apigateway", "POST",   ".*/restapis/.+",                      "apigateway:POST"),

        // ── EMR Serverless ─────────────────────────────────────────────────────
        rule("emr-serverless", "GET", ".*/applications$", "emr-serverless:ListApplications"),
        rule("emr-serverless", "POST", ".*/applications$", "emr-serverless:CreateApplication"),
        rule("emr-serverless", "GET", ".*/applications/[^/]+$", "emr-serverless:GetApplication"),
        rule("emr-serverless", "PATCH", ".*/applications/[^/]+$", "emr-serverless:UpdateApplication"),
        rule("emr-serverless", "DELETE", ".*/applications/[^/]+$", "emr-serverless:DeleteApplication"),
        rule("emr-serverless", "POST", ".*/applications/[^/]+/start$", "emr-serverless:StartApplication"),
        rule("emr-serverless", "POST", ".*/applications/[^/]+/stop$", "emr-serverless:StopApplication"),
        rule("emr-serverless", "GET", ".*/applications/[^/]+/dashboard$", "emr-serverless:GetResourceDashboard"),
        rule("emr-serverless", "GET", ".*/applications/[^/]+/jobruns$", "emr-serverless:ListJobRuns"),
        rule("emr-serverless", "POST", ".*/applications/[^/]+/jobruns$", "emr-serverless:StartJobRun"),
        rule("emr-serverless", "GET", ".*/applications/[^/]+/jobruns/[^/]+$", "emr-serverless:GetJobRun"),
        rule("emr-serverless", "DELETE", ".*/applications/[^/]+/jobruns/[^/]+$", "emr-serverless:CancelJobRun"),
        rule("emr-serverless", "GET", ".*/applications/[^/]+/jobruns/[^/]+/attempts$", "emr-serverless:ListJobRunAttempts"),
        rule("emr-serverless", "GET", ".*/applications/[^/]+/jobruns/[^/]+/dashboard$", "emr-serverless:GetDashboardForJobRun"),
        rule("emr-serverless", "GET", ".*/applications/[^/]+/sessions$", "emr-serverless:ListSessions"),
        rule("emr-serverless", "POST", ".*/applications/[^/]+/sessions$", "emr-serverless:StartSession"),
        rule("emr-serverless", "GET", ".*/applications/[^/]+/sessions/[^/]+$", "emr-serverless:GetSession"),
        rule("emr-serverless", "DELETE", ".*/applications/[^/]+/sessions/[^/]+$", "emr-serverless:TerminateSession"),
        rule("emr-serverless", "GET", ".*/applications/[^/]+/sessions/[^/]+/endpoint$", "emr-serverless:GetSessionEndpoint"),
        rule("emr-serverless", "GET", ".*/tags/.+$", "emr-serverless:ListTagsForResource"),
        rule("emr-serverless", "POST", ".*/tags/.+$", "emr-serverless:TagResource"),
        rule("emr-serverless", "DELETE", ".*/tags/.+$", "emr-serverless:UntagResource"),

        // GuardDuty REST-JSON operations.
        rule("guardduty", "GET", "^/detector$", "guardduty:ListDetectors"),
        rule("guardduty", "POST", "^/detector$", "guardduty:CreateDetector"),
        rule("guardduty", "GET", "^/detector/[^/]+$", "guardduty:GetDetector"),
        rule("guardduty", "POST", "^/detector/[^/]+$", "guardduty:UpdateDetector"),
        rule("guardduty", "DELETE", "^/detector/[^/]+$", "guardduty:DeleteDetector"),
        rule("guardduty", "POST", "^/detector/[^/]+/filter$", "guardduty:CreateFilter"),
        rule("guardduty", "GET", "^/detector/[^/]+/filter$", "guardduty:ListFilters"),
        rule("guardduty", "GET", "^/detector/[^/]+/filter/[^/]+$", "guardduty:GetFilter"),
        rule("guardduty", "POST", "^/detector/[^/]+/filter/[^/]+$", "guardduty:UpdateFilter"),
        rule("guardduty", "DELETE", "^/detector/[^/]+/filter/[^/]+$", "guardduty:DeleteFilter"),
        rule("guardduty", "POST", "^/detector/[^/]+/ipset$", "guardduty:CreateIPSet"),
        rule("guardduty", "GET", "^/detector/[^/]+/ipset$", "guardduty:ListIPSets"),
        rule("guardduty", "GET", "^/detector/[^/]+/ipset/[^/]+$", "guardduty:GetIPSet"),
        rule("guardduty", "POST", "^/detector/[^/]+/ipset/[^/]+$", "guardduty:UpdateIPSet"),
        rule("guardduty", "DELETE", "^/detector/[^/]+/ipset/[^/]+$", "guardduty:DeleteIPSet"),
        rule("guardduty", "POST", "^/detector/[^/]+/threatintelset$", "guardduty:CreateThreatIntelSet"),
        rule("guardduty", "GET", "^/detector/[^/]+/threatintelset$", "guardduty:ListThreatIntelSets"),
        rule("guardduty", "GET", "^/detector/[^/]+/threatintelset/[^/]+$", "guardduty:GetThreatIntelSet"),
        rule("guardduty", "POST", "^/detector/[^/]+/threatintelset/[^/]+$", "guardduty:UpdateThreatIntelSet"),
        rule("guardduty", "DELETE", "^/detector/[^/]+/threatintelset/[^/]+$", "guardduty:DeleteThreatIntelSet"),
        rule("guardduty", "POST", "^/detector/[^/]+/findings/create$", "guardduty:CreateSampleFindings"),
        rule("guardduty", "POST", "^/detector/[^/]+/findings$", "guardduty:ListFindings"),
        rule("guardduty", "POST", "^/detector/[^/]+/findings/get$", "guardduty:GetFindings"),
        rule("guardduty", "POST", "^/detector/[^/]+/findings/statistics$", "guardduty:GetFindingsStatistics"),
        rule("guardduty", "POST", "^/detector/[^/]+/findings/archive$", "guardduty:ArchiveFindings"),
        rule("guardduty", "POST", "^/detector/[^/]+/findings/unarchive$", "guardduty:UnarchiveFindings"),
        rule("guardduty", "POST", "^/detector/[^/]+/findings/feedback$", "guardduty:UpdateFindingsFeedback"),
        rule("guardduty", "POST", "^/detector/[^/]+/usage/statistics$", "guardduty:GetUsageStatistics"),
        rule("guardduty", "POST", "^/detector/[^/]+/coverage$", "guardduty:ListCoverage"),
        rule("guardduty", "POST", "^/detector/[^/]+/freeTrial/daysRemaining$", "guardduty:GetRemainingFreeTrialDays"),
        rule("guardduty", "GET", "^/detector/[^/]+/member$", "guardduty:ListMembers"),
        rule("guardduty", "POST", "^/detector/[^/]+/member$", "guardduty:CreateMembers"),
        rule("guardduty", "POST", "^/detector/[^/]+/member/invite$", "guardduty:InviteMembers"),
        rule("guardduty", "GET", "^/detector/[^/]+/admin$", "guardduty:DescribeOrganizationConfiguration"),
        rule("guardduty", "POST", "^/detector/[^/]+/admin$", "guardduty:UpdateOrganizationConfiguration"),
        rule("guardduty", "GET", "^/invitation$", "guardduty:ListInvitations"),
        rule("guardduty", "GET", "^/invitation/count$", "guardduty:GetInvitationsCount"),
        rule("guardduty", "POST", "^/admin/enable$", "guardduty:EnableOrganizationAdminAccount"),
        rule("guardduty", "POST", "^/admin/disable$", "guardduty:DisableOrganizationAdminAccount"),
        rule("guardduty", "GET", "^/admin$", "guardduty:ListOrganizationAdminAccounts"),
        rule("guardduty", "GET", "^/organization/statistics$", "guardduty:GetOrganizationStatistics"),
        rule("guardduty", "GET", "^/detector/[^/]+/administrator$", "guardduty:GetAdministratorAccount"),
        rule("guardduty", "GET", "^/detector/[^/]+/malware-scan-settings$", "guardduty:GetMalwareScanSettings"),
        rule("guardduty", "POST", "^/detector/[^/]+/malware-scan-settings$", "guardduty:UpdateMalwareScanSettings"),
        rule("guardduty", "POST", "^/detector/[^/]+/investigation/list$", "guardduty:ListInvestigations"),
        rule("guardduty", "GET", "^/tags/.+$", "guardduty:ListTagsForResource"),
        rule("guardduty", "POST", "^/tags/.+$", "guardduty:TagResource"),
        rule("guardduty", "DELETE", "^/tags/.+$", "guardduty:UntagResource"),

        // Inspector2 REST-JSON operations.
        rule("inspector2", "POST", "^/filters/create$", "inspector2:CreateFilter"),
        rule("inspector2", "POST", "^/filters/update$", "inspector2:UpdateFilter"),
        rule("inspector2", "POST", "^/filters/delete$", "inspector2:DeleteFilter"),
        rule("inspector2", "POST", "^/filters/list$", "inspector2:ListFilters"),
        rule("inspector2", "POST", "^/ec2deepinspectionconfiguration/get$", "inspector2:GetEc2DeepInspectionConfiguration"),
        rule("inspector2", "POST", "^/cis/scan-configuration/list$", "inspector2:ListCisScanConfigurations"),
        rule("inspector2", "POST", "^/delegatedadminaccounts/list$", "inspector2:ListDelegatedAdminAccounts"),
        rule("inspector2", "POST", "^/delegatedadminaccounts/enable$", "inspector2:EnableDelegatedAdminAccount"),
        rule("inspector2", "POST", "^/delegatedadminaccounts/disable$", "inspector2:DisableDelegatedAdminAccount"),
        rule("inspector2", "POST", "^/status/batch/get$", "inspector2:BatchGetAccountStatus"),
        rule("inspector2", "POST", "^/enable$", "inspector2:Enable"),
        rule("inspector2", "POST", "^/organizationconfiguration/update$", "inspector2:UpdateOrganizationConfiguration"),
        rule("inspector2", "POST", "^/organizationconfiguration/describe$", "inspector2:DescribeOrganizationConfiguration"),
        rule("inspector2", "POST", "^/findings/list$", "inspector2:ListFindings"),
        rule("inspector2", "POST", "^/coverage/list$", "inspector2:ListCoverage"),
        rule("inspector2", "POST", "^/vulnerabilities/search$", "inspector2:SearchVulnerabilities"),
        rule("inspector2", "POST", "^/usage/list$", "inspector2:ListUsageTotals"),
        rule("inspector2", "POST", "^/accountpermissions/list$", "inspector2:ListAccountPermissions"),
        rule("inspector2", "POST", "^/freetrialinfo/batchget$", "inspector2:BatchGetFreeTrialInfo"),
        rule("inspector2", "POST", "^/configuration/get$", "inspector2:GetConfiguration"),
        rule("inspector2", "GET", "^/encryptionkey/get$", "inspector2:GetEncryptionKey"),
        rule("inspector2", "POST", "^/cis/scan/list$", "inspector2:ListCisScans"),
        rule("inspector2", "POST", "^/members/list$", "inspector2:ListMembers"),
        rule("inspector2", "POST", "^/delegatedadminaccounts/get$", "inspector2:GetDelegatedAdminAccount"),
        rule("inspector2", "POST", "^/reporting/status/get$", "inspector2:GetFindingsReportStatus"),
        rule("inspector2", "GET", "^/tags/.+$", "inspector2:ListTagsForResource"),
        rule("inspector2", "POST", "^/tags/.+$", "inspector2:TagResource"),
        rule("inspector2", "DELETE", "^/tags/.+$", "inspector2:UntagResource"),

        // ── Kinesis ────────────────────────────────────────────────────────────
        rule("kinesis", "POST", ".*", "kinesis:*"),

        // ── SES v2 (REST-JSON). Bulk is authorized as ses:SendEmail (Alchemy and
        // AWS both grant that action on the From identity, not ses:SendBulkEmail).
        rule("ses", "POST", ".*/outbound-emails$",      "ses:SendEmail"),
        rule("ses", "POST", ".*/outbound-bulk-emails$", "ses:SendEmail")
    );

    /**
     * Actions evaluated for assumed-role callers even when global IAM
     * enforcement is off. Keep this set small: JSON 1.1 / Query auto-resolve
     * every operation, and evaluating those would deny DynamoDB/AutoScaling/…
     * Lambdas whose resource ARNs or condition keys we do not yet model.
     */
    private static final Set<String> ROLE_ENFORCED_ACTIONS = Set.of(
            "ses:SendEmail",
            "ses:SendRawEmail",
            "ses:SendBulkEmail",
            "kms:GetKeyRotationStatus"
    );

    private static ActionRule rule(String service, String method, String path, String action) {
        return new ActionRule(service, method, Pattern.compile(path, Pattern.CASE_INSENSITIVE), action);
    }

    /**
     * Resolves the IAM action for an incoming request.
     *
     * For Query-protocol services the action comes directly from the {@code Action}
     * form param (e.g. {@code sqs:SendMessage}).
     *
     * For JSON 1.1 protocol the action comes from {@code X-Amz-Target}
     * (e.g. {@code DynamoDB_20120810.PutItem} → {@code dynamodb:PutItem}).
     *
     * For REST-JSON services the action is derived from the path rule table.
     *
     * Returns {@code null} when the action is unknown (caller treats this as ALLOW).
     */
    public String resolve(String credentialScope, ContainerRequestContext ctx) {
        // Query-protocol: Action param → service:Action.
        // AWS SDKs send Query-protocol calls (IAM, STS, EC2, SQS, SNS, ...) as
        // POST with Action=... in the application/x-www-form-urlencoded body,
        // not the URL query string — so we look in both places.
        String queryAction = ctx.getUriInfo().getQueryParameters().getFirst("Action");
        if (queryAction == null || queryAction.isBlank()) {
            queryAction = readFormAction(ctx);
        }
        if (queryAction != null && !queryAction.isBlank()) {
            return credentialScope + ":" + queryAction;
        }

        // JSON 1.1: X-Amz-Target → service:OperationName
        String target = ctx.getHeaderString("X-Amz-Target");
        if (target != null && target.contains(".")) {
            String operationName = target.substring(target.lastIndexOf('.') + 1);
            return credentialScope + ":" + operationName;
        }

        // REST-JSON: match against rule table
        String method = ctx.getMethod().toUpperCase();
        String path   = ctx.getUriInfo().getPath();
        if (!path.startsWith("/")) path = "/" + path;

        // S3 sub-resource override: the URL path alone doesn't distinguish
        // s3:GetObjectAcl / s3:PutObjectAcl from s3:GetObject / s3:PutObject
        // — only the {@code ?acl} query parameter does. Without this hook,
        // an actor with s3:GetObject permission could read ACLs that their
        // policy intends to forbid.
        if ("s3".equals(credentialScope)) {
            String s3SubAction = resolveS3SubResourceAction(method, ctx);
            if (s3SubAction != null) {
                return s3SubAction;
            }
        }

        for (ActionRule rule : RULES) {
            if (rule.service().equals(credentialScope)
                    && rule.method().equals(method)
                    && rule.pathPattern().matcher(path).find()) {
                return rule.action();
            }
        }

        LOG.debugv("No action mapping for {0} {1} {2} — defaulting to ALLOW", credentialScope, method, path);
        return null;
    }

    /** One bucket sub-resource operation: the query parameter that selects it, and the IAM action. */
    private record SubResourceAction(String queryParameter, String action) {}

    private static SubResourceAction sub(String queryParameter, String action) {
        return new SubResourceAction(queryParameter, action);
    }

    private static final List<SubResourceAction> GET_BUCKET_SUBRESOURCES = List.of(
            sub("uploads",           "s3:ListBucketMultipartUploads"),
            sub("notification",      "s3:GetBucketNotification"),
            sub("versioning",        "s3:GetBucketVersioning"),
            sub("versions",          "s3:ListBucketVersions"),
            sub("location",          "s3:GetBucketLocation"),
            sub("tagging",           "s3:GetBucketTagging"),
            sub("object-lock",       "s3:GetBucketObjectLockConfiguration"),
            sub("website",           "s3:GetBucketWebsite"),
            sub("logging",           "s3:GetBucketLogging"),
            sub("policy",            "s3:GetBucketPolicy"),
            sub("cors",              "s3:GetBucketCORS"),
            sub("lifecycle",         "s3:GetLifecycleConfiguration"),
            sub("acl",               "s3:GetBucketAcl"),
            sub("encryption",        "s3:GetEncryptionConfiguration"),
            sub("publicAccessBlock", "s3:GetBucketPublicAccessBlock"),
            sub("ownershipControls", "s3:GetBucketOwnershipControls"),
            sub("requestPayment",    "s3:GetBucketRequestPayment"),
            sub("accelerate",        "s3:GetAccelerateConfiguration"),
            sub("replication",       "s3:GetReplicationConfiguration"),
            sub("metrics",           "s3:GetMetricsConfiguration"));

    private static final List<SubResourceAction> PUT_BUCKET_SUBRESOURCES = List.of(
            sub("notification",      "s3:PutBucketNotification"),
            sub("versioning",        "s3:PutBucketVersioning"),
            sub("tagging",           "s3:PutBucketTagging"),
            sub("object-lock",       "s3:PutBucketObjectLockConfiguration"),
            sub("website",           "s3:PutBucketWebsite"),
            sub("logging",           "s3:PutBucketLogging"),
            sub("policy",            "s3:PutBucketPolicy"),
            sub("cors",              "s3:PutBucketCORS"),
            sub("lifecycle",         "s3:PutLifecycleConfiguration"),
            sub("acl",               "s3:PutBucketAcl"),
            sub("encryption",        "s3:PutEncryptionConfiguration"),
            sub("publicAccessBlock", "s3:PutBucketPublicAccessBlock"),
            sub("ownershipControls", "s3:PutBucketOwnershipControls"),
            sub("requestPayment",    "s3:PutBucketRequestPayment"),
            sub("accelerate",        "s3:PutAccelerateConfiguration"),
            sub("replication",       "s3:PutReplicationConfiguration"),
            sub("metrics",           "s3:PutMetricsConfiguration"));

    // AWS gives only DeleteBucketPolicy and DeleteBucketWebsite their own action; removing any other
    // sub-resource is authorised by the same Put* action that sets it. ?accelerate is absent because
    // S3Controller rejects DELETE on it with 405, so no mapping should claim the request.
    private static final List<SubResourceAction> DELETE_BUCKET_SUBRESOURCES = List.of(
            sub("tagging",           "s3:DeleteBucketTagging"),
            sub("website",           "s3:DeleteBucketWebsite"),
            sub("policy",            "s3:DeleteBucketPolicy"),
            sub("cors",              "s3:PutBucketCORS"),
            sub("lifecycle",         "s3:PutLifecycleConfiguration"),
            sub("encryption",        "s3:PutEncryptionConfiguration"),
            sub("publicAccessBlock", "s3:PutBucketPublicAccessBlock"),
            sub("ownershipControls", "s3:PutBucketOwnershipControls"),
            sub("replication",       "s3:PutReplicationConfiguration"),
            sub("metrics",           "s3:PutMetricsConfiguration"));

    /**
     * {@code true} when {@code action} is one of the few operations evaluated
     * for assumed-role callers while global enforcement is off.
     */
    public boolean isRoleEnforcedAction(String action) {
        return action != null && (ROLE_ENFORCED_ACTIONS.contains(action)
                || RULES.stream().anyMatch(rule -> Set.of("emr-serverless", "guardduty", "inspector2")
                        .contains(rule.service()) && rule.action().equals(action)));
    }

    /**
     * Resolves S3 sub-resource ops (ACL, tagging, retention, etc.) that
     * cannot be distinguished from the parent op by HTTP method + path alone.
     * Returns null when no sub-resource is present so the caller falls back
     * to the standard rule table.
     */
    private static String resolveS3SubResourceAction(String method, ContainerRequestContext ctx) {
        MultivaluedMap<String, String> params = ctx.getUriInfo().getQueryParameters();
        // /{bucket}?acl -> bucket-level; /{bucket}/{key}?acl -> object-level.
        // A trailing slash is a valid key character, so /bucket/folder/?acl is an object request -
        // we cannot use endsWith("/") to infer bucket-level.
        String path = ctx.getUriInfo().getPath();
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        int firstSlash = stripped.indexOf('/');
        boolean isBucketLevel = firstSlash < 0 || firstSlash == stripped.length() - 1;
        if (!isBucketLevel) {
            return objectSubResourceAction(method, params);
        }
        List<SubResourceAction> chain = switch (method) {
            case "GET" -> GET_BUCKET_SUBRESOURCES;
            case "PUT" -> PUT_BUCKET_SUBRESOURCES;
            case "DELETE" -> DELETE_BUCKET_SUBRESOURCES;
            default -> List.of();
        };
        for (SubResourceAction entry : chain) {
            if (params.containsKey(entry.queryParameter())) {
                return entry.action();
            }
        }
        return null;
    }

    /**
     * Object-level S3 actions. A request naming a {@code versionId} is authorized as the
     * {@code *Version*} variant of its action, as on AWS (s3:GetObjectVersion,
     * s3:GetObjectVersionTagging, ...); retention and legal hold have no such variant.
     */
    private static String objectSubResourceAction(String method, MultivaluedMap<String, String> params) {
        boolean versioned = params.containsKey("versionId");
        if (params.containsKey("acl")) {
            return switch (method) {
                case "GET" -> versioned ? "s3:GetObjectVersionAcl" : "s3:GetObjectAcl";
                case "PUT" -> versioned ? "s3:PutObjectVersionAcl" : "s3:PutObjectAcl";
                default -> null;
            };
        }
        if (params.containsKey("tagging")) {
            return switch (method) {
                case "GET" -> versioned ? "s3:GetObjectVersionTagging" : "s3:GetObjectTagging";
                case "PUT" -> versioned ? "s3:PutObjectVersionTagging" : "s3:PutObjectTagging";
                case "DELETE" -> versioned ? "s3:DeleteObjectVersionTagging" : "s3:DeleteObjectTagging";
                default -> null;
            };
        }
        if (params.containsKey("retention")) {
            return switch (method) {
                case "GET" -> "s3:GetObjectRetention";
                case "PUT" -> "s3:PutObjectRetention";
                default -> null;
            };
        }
        if (params.containsKey("legal-hold")) {
            return switch (method) {
                case "GET" -> "s3:GetObjectLegalHold";
                case "PUT" -> "s3:PutObjectLegalHold";
                default -> null;
            };
        }
        if (params.containsKey("attributes") && "GET".equals(method)) {
            return versioned ? "s3:GetObjectVersionAttributes" : "s3:GetObjectAttributes";
        }
        if (params.containsKey("uploadId")) {
            return switch (method) {
                case "GET" -> "s3:ListMultipartUploadParts";
                case "DELETE" -> "s3:AbortMultipartUpload";
                default -> null;
            };
        }
        if (versioned) {
            return switch (method) {
                case "GET", "HEAD" -> "s3:GetObjectVersion";
                case "DELETE" -> "s3:DeleteObjectVersion";
                default -> null;
            };
        }
        return null;
    }

    /**
     * Reads {@code Action} from a {@code application/x-www-form-urlencoded}
     * request body and restores the entity stream so downstream consumers
     * (e.g. {@code AwsQueryController}'s {@code MultivaluedMap} injection)
     * can still parse the form themselves. Returns {@code null} if the
     * request is not form-encoded or the body has no {@code Action} field.
     */
    private static String readFormAction(ContainerRequestContext ctx) {
        // Delegates to RequestBodyReader so this and ResourceArnBuilder's per-service resource
        // lookups share one buffered copy of the body per request instead of each independently
        // reading (and needing to reset) the live entity stream.
        return RequestBodyReader.formField(ctx, "Action");
    }
}
