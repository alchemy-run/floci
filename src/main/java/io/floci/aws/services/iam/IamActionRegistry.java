package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsProtocolClaimFilter;
import io.floci.aws.core.common.AwsQueryServiceResolver;
import io.floci.aws.core.common.ProtocolClaim;
import io.floci.aws.core.common.RequestResources;
import io.floci.aws.core.common.WireProtocol;
import io.floci.aws.services.dynamodb.DynamoDbPartiQLParser;
import io.floci.aws.services.eks.EksController;
import io.floci.aws.services.route53.Route53Controller;
import io.floci.aws.services.s3.S3ObjectActions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps (credentialScope, httpMethod, requestPath) → IAM action string.
 *
 * For Query-protocol services (SQS, SNS, IAM, STS, ...) the Action form
 * parameter, or the controller's Operation fallback, is mapped directly to
 * {@code <service>:<Action>}.
 *
 * For REST-JSON services the first matching rule wins (specific before wildcard), and a request no
 * rule matches is the operation whose route in botocore's model it matches ({@link IamRestRoutes}),
 * when Floci can check that operation as AWS does.
 */
@ApplicationScoped
public class IamActionRegistry {

    private static final Map<String, String> ROUTE53_DELEGATION_ACTIONS = Map.of(
            "createReusableDelegationSet", "route53:CreateReusableDelegationSet",
            "getReusableDelegationSet", "route53:GetReusableDelegationSet",
            "listReusableDelegationSets", "route53:ListReusableDelegationSets",
            "deleteReusableDelegationSet", "route53:DeleteReusableDelegationSet");

    private static final Logger LOG = Logger.getLogger(IamActionRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IamRestRoutes restRoutes;

    public IamActionRegistry() {
        this(IamRestRoutes.vendored());
    }

    IamActionRegistry(IamRestRoutes restRoutes) {
        this.restRoutes = restRoutes;
    }

    /**
     * A route and the action it is checked as. A rule naming a query parameter matches only a request
     * carrying it with that value, as {@code GET /layers?find=LayerVersion} is GetLayerVersionByArn
     * where {@code GET /layers} is ListLayers. A rule flagged to match before the S3 query
     * overrides is one of the S3 Control routes, which the control plane answers whatever its
     * query string carries.
     */
    private record ActionRule(String service, String method, Pattern pathPattern, String queryParameter,
                              String queryValue, String action, boolean beforeS3QueryOverrides) {

        boolean matches(String scope, String requestMethod, String path, ContainerRequestContext ctx) {
            return service.equals(scope) && method.equals(requestMethod) && pathPattern.matcher(path).find()
                    && (queryParameter == null
                    || queryValue.equals(ctx.getUriInfo().getQueryParameters().getFirst(queryParameter)));
        }
    }

    /**
     * The routes of the S3 Control model, checked as the actions the Service Reference gives
     * them by {@code IamRestRouteCoverageTest}. S3 Control clients sign with the s3 scope
     * (botocore's s3control model declares signingName "s3"), so its operations are
     * {@code s3:} actions, and its routes must be matched before the S3 data-plane query
     * overrides: the control plane answers a tagging route whatever its query string carries,
     * so a parameter such as {@code ?tagging} must not re-point the authorization at the
     * data-plane object tagging it suggests, and the data-plane rules below would otherwise
     * read {@code /v20180820/...} as a bucket named v20180820.
     *
     * <p>The account-level Block Public Access is the one route the Service Reference cannot
     * settle, because it files S3's and S3 Control's GetPublicAccessBlock, PutPublicAccessBlock
     * and DeletePublicAccessBlock under the bucket actions; the S3 User Guide's Block Public
     * Access page gives the account level its own actions, and the exception is written down
     * where the data is checked. ListAccessPoints and the account actions have no resource
     * type, so IAM checks them against the wildcard rather than a bucket.
     */
    private static final List<ActionRule> RULES = List.of(
        // ── S3 Control ───────────────────────────────────────────────────────────
        s3ControlRule("GET",    "^/v20180820/tags/.+",        "s3:ListTagsForResource"),
        s3ControlRule("POST",   "^/v20180820/tags/.+",        "s3:TagResource"),
        s3ControlRule("DELETE", "^/v20180820/tags/.+",        "s3:UntagResource"),
        s3ControlRule("GET",    "^/v20180820/accesspoint/?$", "s3:ListAccessPoints"),
        s3ControlRule("GET", "^/v20180820/accesspointforobjectlambda/[^/]+/configuration/?$", "s3:GetAccessPointConfigurationForObjectLambda"),
        s3ControlRule("PUT", "^/v20180820/accesspointforobjectlambda/[^/]+/configuration/?$", "s3:PutAccessPointConfigurationForObjectLambda"),
        s3ControlRule("GET", "^/v20180820/accesspointforobjectlambda/[^/]+/policyStatus/?$", "s3:GetAccessPointPolicyStatusForObjectLambda"),
        s3ControlRule("POST", "^/v20180820/accessgrantsinstance/identitycenter/?$", "s3:AssociateAccessGrantsIdentityCenter"),
        s3ControlRule("DELETE", "^/v20180820/accessgrantsinstance/resourcepolicy/?$", "s3:DeleteAccessGrantsInstanceResourcePolicy"),
        s3ControlRule("DELETE", "^/v20180820/accessgrantsinstance/identitycenter/?$", "s3:DissociateAccessGrantsIdentityCenter"),
        s3ControlRule("GET", "^/v20180820/accessgrantsinstance/resourcepolicy/?$", "s3:GetAccessGrantsInstanceResourcePolicy"),
        s3ControlRule("PUT", "^/v20180820/accessgrantsinstance/resourcepolicy/?$", "s3:PutAccessGrantsInstanceResourcePolicy"),
        s3ControlRule("DELETE", "^/v20180820/accesspointforobjectlambda/[^/]+/policy/?$", "s3:DeleteAccessPointPolicyForObjectLambda"),
        s3ControlRule("GET", "^/v20180820/accesspointforobjectlambda/[^/]+/policy/?$", "s3:GetAccessPointPolicyForObjectLambda"),
        s3ControlRule("GET", "^/v20180820/accessgrantsinstance/caller/grants/?$", "s3:ListCallerAccessGrants"),
        s3ControlRule("PUT", "^/v20180820/accesspointforobjectlambda/[^/]+/policy/?$", "s3:PutAccessPointPolicyForObjectLambda"),
        s3ControlRule("GET", "^/v20180820/accessgrantsinstance/dataaccess/?$", "s3:GetDataAccess"),
        s3ControlRule("DELETE", "^/v20180820/accessgrantsinstance/location/[^/]+/?$", "s3:DeleteAccessGrantsLocation"),
        s3ControlRule("GET", "^/v20180820/accessgrantsinstance/location/[^/]+/?$", "s3:GetAccessGrantsLocation"),
        s3ControlRule("GET", "^/v20180820/accessgrantsinstance/locations/?$", "s3:ListAccessGrantsLocations"),
        s3ControlRule("POST", "^/v20180820/async-requests/mrap/put-policy/?$", "s3:PutMultiRegionAccessPointPolicy"),
        s3ControlRule("PUT", "^/v20180820/accessgrantsinstance/location/[^/]+/?$", "s3:UpdateAccessGrantsLocation"),
        s3ControlRule("POST", "^/v20180820/accessgrantsinstance/location/?$", "s3:CreateAccessGrantsLocation"),
        s3ControlRule("GET", "^/v20180820/mrap/instances/.+/policystatus/?$", "s3:GetMultiRegionAccessPointPolicyStatus"),
        s3ControlRule("PUT", "^/v20180820/accesspointforobjectlambda/[^/]+/?$", "s3:CreateAccessPointForObjectLambda"),
        s3ControlRule("DELETE", "^/v20180820/accessgrantsinstance/grant/[^/]+/?$", "s3:DeleteAccessGrant"),
        s3ControlRule("DELETE", "^/v20180820/accesspointforobjectlambda/[^/]+/?$", "s3:DeleteAccessPointForObjectLambda"),
        s3ControlRule("GET", "^/v20180820/accessgrantsinstance/grant/[^/]+/?$", "s3:GetAccessGrant"),
        s3ControlRule("GET", "^/v20180820/accessgrantsinstance/prefix/?$", "s3:GetAccessGrantsInstanceForPrefix"),
        s3ControlRule("GET", "^/v20180820/accesspointforobjectlambda/[^/]+/?$", "s3:GetAccessPointForObjectLambda"),
        s3ControlRule("GET", "^/v20180820/accessgrantsinstance/grants/?$", "s3:ListAccessGrants"),
        s3ControlRule("POST", "^/v20180820/accessgrantsinstance/grant/?$", "s3:CreateAccessGrant"),
        s3ControlRule("POST", "^/v20180820/async-requests/mrap/create/?$", "s3:CreateMultiRegionAccessPoint"),
        s3ControlRule("POST", "^/v20180820/async-requests/mrap/delete/?$", "s3:DeleteMultiRegionAccessPoint"),
        s3ControlRule("GET", "^/v20180820/accesspointforobjectlambda/?$", "s3:ListAccessPointsForObjectLambda"),
        s3ControlRule("GET", "^/v20180820/accesspoint/[^/]+/policyStatus/?$", "s3:GetAccessPointPolicyStatus"),
        s3ControlRule("GET", "^/v20180820/mrap/instances/.+/policy/?$", "s3:GetMultiRegionAccessPointPolicy"),
        s3ControlRule("GET", "^/v20180820/mrap/instances/.+/routes/?$", "s3:GetMultiRegionAccessPointRoutes"),
        s3ControlRule("PATCH", "^/v20180820/mrap/instances/.+/routes/?$", "s3:SubmitMultiRegionAccessPointRoutes"),
        s3ControlRule("GET", "^/v20180820/accessgrantsinstances/?$", "s3:ListAccessGrantsInstances"),
        s3ControlRule("POST", "^/v20180820/accessgrantsinstance/?$", "s3:CreateAccessGrantsInstance"),
        s3ControlRule("DELETE", "^/v20180820/accessgrantsinstance/?$", "s3:DeleteAccessGrantsInstance"),
        s3ControlRule("DELETE", "^/v20180820/storagelens/[^/]+/tagging/?$", "s3:DeleteStorageLensConfigurationTagging"),
        s3ControlRule("GET", "^/v20180820/async-requests/mrap/.+/?$", "s3:DescribeMultiRegionAccessPointOperation"),
        s3ControlRule("GET", "^/v20180820/accessgrantsinstance/?$", "s3:GetAccessGrantsInstance"),
        s3ControlRule("GET", "^/v20180820/storagelens/[^/]+/tagging/?$", "s3:GetStorageLensConfigurationTagging"),
        s3ControlRule("PUT", "^/v20180820/storagelens/[^/]+/tagging/?$", "s3:PutStorageLensConfigurationTagging"),
        s3ControlRule("DELETE", "^/v20180820/accesspoint/[^/]+/policy/?$", "s3:DeleteAccessPointPolicy"),
        s3ControlRule("GET", "^/v20180820/accesspoint/[^/]+/policy/?$", "s3:GetAccessPointPolicy"),
        s3ControlRule("PUT", "^/v20180820/accesspoint/[^/]+/policy/?$", "s3:PutAccessPointPolicy"),
        s3ControlRule("DELETE", "^/v20180820/accesspoint/[^/]+/scope/?$", "s3:DeleteAccessPointScope"),
        s3ControlRule("GET", "^/v20180820/accesspoint/[^/]+/scope/?$", "s3:GetAccessPointScope"),
        s3ControlRule("PUT", "^/v20180820/accesspoint/[^/]+/scope/?$", "s3:PutAccessPointScope"),
        s3ControlRule("DELETE", "^/v20180820/storagelensgroup/[^/]+/?$", "s3:DeleteStorageLensGroup"),
        s3ControlRule("GET", "^/v20180820/storagelensgroup/[^/]+/?$", "s3:GetStorageLensGroup"),
        s3ControlRule("PUT", "^/v20180820/storagelensgroup/[^/]+/?$", "s3:UpdateStorageLensGroup"),
        s3ControlRule("POST", "^/v20180820/storagelensgroup/?$", "s3:CreateStorageLensGroup"),
        s3ControlRule("GET", "^/v20180820/storagelensgroup/?$", "s3:ListStorageLensGroups"),
        s3ControlRule("GET", "^/v20180820/mrap/instances/.+/?$", "s3:GetMultiRegionAccessPoint"),
        s3ControlRule("GET", "^/v20180820/mrap/instances/?$", "s3:ListMultiRegionAccessPoints"),
        s3ControlRule("POST", "^/v20180820/jobs/[^/]+/priority/?$", "s3:UpdateJobPriority"),
        s3ControlRule("DELETE", "^/v20180820/jobs/[^/]+/tagging/?$", "s3:DeleteJobTagging"),
        s3ControlRule("GET", "^/v20180820/jobs/[^/]+/tagging/?$", "s3:GetJobTagging"),
        s3ControlRule("PUT", "^/v20180820/jobs/[^/]+/tagging/?$", "s3:PutJobTagging"),
        s3ControlRule("PUT", "^/v20180820/accesspoint/[^/]+/?$", "s3:CreateAccessPoint"),
        s3ControlRule("DELETE", "^/v20180820/accesspoint/[^/]+/?$", "s3:DeleteAccessPoint"),
        s3ControlRule("DELETE", "^/v20180820/storagelens/[^/]+/?$", "s3:DeleteStorageLensConfiguration"),
        s3ControlRule("GET", "^/v20180820/accesspoint/[^/]+/?$", "s3:GetAccessPoint"),
        s3ControlRule("GET", "^/v20180820/storagelens/[^/]+/?$", "s3:GetStorageLensConfiguration"),
        s3ControlRule("PUT", "^/v20180820/storagelens/[^/]+/?$", "s3:PutStorageLensConfiguration"),
        s3ControlRule("POST", "^/v20180820/jobs/[^/]+/status/?$", "s3:UpdateJobStatus"),
        s3ControlRule("GET", "^/v20180820/storagelens/?$", "s3:ListStorageLensConfigurations"),
        s3ControlRule("GET", "^/v20180820/jobs/[^/]+/?$", "s3:DescribeJob"),
        s3ControlRule("POST", "^/v20180820/jobs/?$", "s3:CreateJob"),
        s3ControlRule("GET", "^/v20180820/jobs/?$", "s3:ListJobs"),
        s3ControlRule("PUT",    "^/v20180820/configuration/publicAccessBlock/?$",
                "s3:PutAccountPublicAccessBlock"),
        s3ControlRule("GET",    "^/v20180820/configuration/publicAccessBlock/?$",
                "s3:GetAccountPublicAccessBlock"),
        s3ControlRule("DELETE", "^/v20180820/configuration/publicAccessBlock/?$",
                "s3:PutAccountPublicAccessBlock"),

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
        // Code signing configs first: a config's function list ends in /functions, as ListFunctions does.
        rule("lambda", "GET",    "^/2020-04-22/code-signing-configs/[^/]+/functions$",
                "lambda:ListFunctionsByCodeSigningConfig"),
        rule("lambda", "GET",    "^/2020-04-22/code-signing-configs/[^/]+$",  "lambda:GetCodeSigningConfig"),
        rule("lambda", "PUT",    "^/2020-04-22/code-signing-configs/[^/]+$",  "lambda:UpdateCodeSigningConfig"),
        rule("lambda", "DELETE", "^/2020-04-22/code-signing-configs/[^/]+$",  "lambda:DeleteCodeSigningConfig"),
        rule("lambda", "GET",    "^/2020-04-22/code-signing-configs$",        "lambda:ListCodeSigningConfigs"),
        rule("lambda", "POST",   "^/2020-04-22/code-signing-configs$",        "lambda:CreateCodeSigningConfig"),
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
        rule("lambda", "GET",    ".*/functions/[^/]+/durable-executions$", "lambda:ListDurableExecutionsByFunction"),
        rule("lambda", "POST",   ".*/durable-executions/.+/checkpoint$",   "lambda:CheckpointDurableExecution"),
        rule("lambda", "GET",    ".*/durable-executions/.+/state$",        "lambda:GetDurableExecutionState"),
        rule("lambda", "GET",    ".*/durable-executions/.+/history$",      "lambda:GetDurableExecutionHistory"),
        rule("lambda", "POST",   ".*/durable-executions/.+/stop$",         "lambda:StopDurableExecution"),
        rule("lambda", "GET",    ".*/durable-executions/.+$",              "lambda:GetDurableExecution"),
        rule("lambda", "POST",   ".*/durable-execution-callbacks/.+/succeed$",
                "lambda:SendDurableExecutionCallbackSuccess"),
        rule("lambda", "POST",   ".*/durable-execution-callbacks/.+/fail$", "lambda:SendDurableExecutionCallbackFailure"),
        rule("lambda", "POST",   ".*/durable-execution-callbacks/.+/heartbeat$",
                "lambda:SendDurableExecutionCallbackHeartbeat"),
        rule("lambda", "GET",    ".*/event-source-mappings/[^/]+$",        "lambda:GetEventSourceMapping"),
        rule("lambda", "PUT",    ".*/event-source-mappings/[^/]+$",        "lambda:UpdateEventSourceMapping"),
        rule("lambda", "GET",    ".*/functions/[^/]+/configuration$",      "lambda:GetFunctionConfiguration"),
        rule("lambda", "GET",    ".*/functions/[^/]+/versions$",           "lambda:ListVersionsByFunction"),
        rule("lambda", "POST",   ".*/functions/[^/]+/versions$",           "lambda:PublishVersion"),
        rule("lambda", "POST",   ".*/functions/[^/]+/invoke-async$",       "lambda:InvokeAsync"),
        rule("lambda", "GET",    ".*/functions/[^/]+/urls$",               "lambda:ListFunctionUrlConfigs"),
        rule("lambda", "GET",    ".*/functions/[^/]+/concurrency$",        "lambda:GetFunctionConcurrency"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/concurrency$",        "lambda:PutFunctionConcurrency"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/concurrency$",        "lambda:DeleteFunctionConcurrency"),
        rule("lambda", "GET",    ".*/functions/[^/]+/provisioned-concurrency$", "List", "ALL",
                "lambda:ListProvisionedConcurrencyConfigs"),
        rule("lambda", "GET",    ".*/functions/[^/]+/provisioned-concurrency$",
                "lambda:GetProvisionedConcurrencyConfig"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/provisioned-concurrency$",
                "lambda:PutProvisionedConcurrencyConfig"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/provisioned-concurrency$",
                "lambda:DeleteProvisionedConcurrencyConfig"),
        rule("lambda", "GET",    ".*/functions/[^/]+/event-invoke-config/list$",
                "lambda:ListFunctionEventInvokeConfigs"),
        rule("lambda", "GET",    ".*/functions/[^/]+/event-invoke-config$", "lambda:GetFunctionEventInvokeConfig"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/event-invoke-config$", "lambda:PutFunctionEventInvokeConfig"),
        rule("lambda", "POST",   ".*/functions/[^/]+/event-invoke-config$", "lambda:UpdateFunctionEventInvokeConfig"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/event-invoke-config$", "lambda:DeleteFunctionEventInvokeConfig"),
        rule("lambda", "GET",    ".*/functions/[^/]+/code-signing-config$", "lambda:GetFunctionCodeSigningConfig"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/code-signing-config$", "lambda:PutFunctionCodeSigningConfig"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/code-signing-config$", "lambda:DeleteFunctionCodeSigningConfig"),
        rule("lambda", "GET",    ".*/functions/[^/]+/recursion-config$",   "lambda:GetFunctionRecursionConfig"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/recursion-config$",   "lambda:PutFunctionRecursionConfig"),
        rule("lambda", "GET",    ".*/functions/[^/]+/function-scaling-config$", "lambda:GetFunctionScalingConfig"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/function-scaling-config$", "lambda:PutFunctionScalingConfig"),
        rule("lambda", "GET",    ".*/functions/[^/]+/runtime-management-config$", "lambda:GetRuntimeManagementConfig"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/runtime-management-config$", "lambda:PutRuntimeManagementConfig"),
        rule("lambda", "GET",    "^/2016-08-19/account-settings/?$",       "lambda:GetAccountSettings"),
        rule("lambda", "GET",    "^/2018-10-31/layers/[^/]+/versions/[^/]+/policy$", "lambda:GetLayerVersionPolicy"),
        rule("lambda", "POST",   "^/2018-10-31/layers/[^/]+/versions/[^/]+/policy$",
                "lambda:AddLayerVersionPermission"),
        rule("lambda", "DELETE", "^/2018-10-31/layers/[^/]+/versions/[^/]+/policy/[^/]+$",
                "lambda:RemoveLayerVersionPermission"),
        rule("lambda", "GET",    "^/2018-10-31/layers/[^/]+/versions/[^/]+$", "lambda:GetLayerVersion"),
        rule("lambda", "DELETE", "^/2018-10-31/layers/[^/]+/versions/[^/]+$", "lambda:DeleteLayerVersion"),
        rule("lambda", "GET",    "^/2018-10-31/layers/[^/]+/versions$",    "lambda:ListLayerVersions"),
        rule("lambda", "POST",   "^/2018-10-31/layers/[^/]+/versions$",    "lambda:PublishLayerVersion"),
        rule("lambda", "GET",    "^/2025-11-30/capacity-providers/[^/]+/function-versions$",
                "lambda:ListFunctionVersionsByCapacityProvider"),
        rule("lambda", "GET",    "^/2025-11-30/capacity-providers/[^/]+$", "lambda:GetCapacityProvider"),
        rule("lambda", "PUT",    "^/2025-11-30/capacity-providers/[^/]+$", "lambda:UpdateCapacityProvider"),
        rule("lambda", "DELETE", "^/2025-11-30/capacity-providers/[^/]+$", "lambda:DeleteCapacityProvider"),
        rule("lambda", "GET",    "^/2025-11-30/capacity-providers$",       "lambda:ListCapacityProviders"),
        rule("lambda", "POST",   "^/2025-11-30/capacity-providers$",       "lambda:CreateCapacityProvider"),
        // The resource policy routes of the function API (2026-07-09) and of Web Functions (2025-03-07).
        rule("lambda", "PUT",    "^/2026-07-09/resource-policy/.+",        "lambda:PutResourcePolicy"),
        rule("lambda", "GET",    "^/2026-07-09/resource-policy/.+",        "lambda:GetResourcePolicy"),
        rule("lambda", "DELETE", "^/2026-07-09/resource-policy/.+",        "lambda:DeleteResourcePolicy"),

        // ── Lambda Web Functions (/2025-03-07, signs as lambda) ─────────────────
        rule("lambda", "PUT",    "^/2025-03-07/web-functions$",                       "lambda:CreateWebFunction"),
        rule("lambda", "POST",   "^/2025-03-07/web-functions$",                       "lambda:ListWebFunctions"),
        rule("lambda", "GET",    "^/2025-03-07/web-functions/[^/]+$",                 "lambda:GetWebFunction"),
        rule("lambda", "DELETE", "^/2025-03-07/web-functions/[^/]+$",                 "lambda:DeleteWebFunction"),
        rule("lambda", "POST",   "^/2025-03-07/web-functions/[^/]+/revisions$",       "lambda:CreateWebFunctionRevision"),
        rule("lambda", "POST",   "^/2025-03-07/web-functions/[^/]+/list-revisions$",  "lambda:ListWebFunctionRevisions"),
        rule("lambda", "GET",    "^/2025-03-07/web-functions/[^/]+/revisions/[^/]+$", "lambda:GetWebFunctionRevision"),
        rule("lambda", "DELETE", "^/2025-03-07/web-functions/[^/]+/revisions/[^/]+$", "lambda:DeleteWebFunctionRevision"),
        rule("lambda", "PUT",    "^/2025-03-07/web-functions/[^/]+/endpoints$",       "lambda:CreateWebFunctionEndpoint"),
        rule("lambda", "POST",   "^/2025-03-07/web-functions/[^/]+/list-endpoints$",  "lambda:ListWebFunctionEndpoints"),
        rule("lambda", "GET",    "^/2025-03-07/web-functions/[^/]+/endpoints/[^/]+$", "lambda:GetWebFunctionEndpoint"),
        rule("lambda", "PATCH",  "^/2025-03-07/web-functions/[^/]+/endpoints/[^/]+$", "lambda:UpdateWebFunctionEndpoint"),
        rule("lambda", "DELETE", "^/2025-03-07/web-functions/[^/]+/endpoints/[^/]+$", "lambda:DeleteWebFunctionEndpoint"),
        rule("lambda", "GET",    "^/2025-03-07/web-account-settings$",                "lambda:GetWebAccountSettings"),
        rule("lambda", "GET",    "^/2025-03-07/tags/.+",                              "lambda:ListTags"),
        rule("lambda", "POST",   "^/2025-03-07/tags/.+",                              "lambda:TagResource"),
        rule("lambda", "DELETE", "^/2025-03-07/tags/.+",                              "lambda:UntagResource"),
        rule("lambda", "PUT",    "^/2025-03-07/resource-policy/.+",                   "lambda:PutResourcePolicy"),
        rule("lambda", "GET",    "^/2025-03-07/resource-policy/.+",                   "lambda:GetResourcePolicy"),
        rule("lambda", "DELETE", "^/2025-03-07/resource-policy/.+",                   "lambda:DeleteResourcePolicy"),
        // InvokeWithResponseStream and GetLayerVersionByArn are authorized as the action they share with
        // Invoke and GetLayerVersion (iam-authorized-actions.json).
        rule("lambda", "POST",   ".*/functions/[^/]+/response-streaming-invocations$", "lambda:InvokeFunction"),
        rule("lambda", "GET",    "^/2018-10-31/layers$", "find", "LayerVersion",     "lambda:GetLayerVersion"),
        rule("lambda", "GET",    "^/2018-10-31/layers$",                              "lambda:ListLayers"),
        // Lambda MicroVMs (2025-09-09) and their tags, and network connectors (2026-04-04).
        rule("lambda", "POST",   "^/2025-09-09/microvms/[^/]+/auth-token$",            "lambda:CreateMicrovmAuthToken"),
        rule("lambda", "POST",   "^/2025-09-09/microvms/[^/]+/shell-auth-token$",      "lambda:CreateMicrovmShellAuthToken"),
        rule("lambda", "POST",   "^/2025-09-09/microvms/[^/]+/resume$",                "lambda:ResumeMicrovm"),
        rule("lambda", "POST",   "^/2025-09-09/microvms/[^/]+/suspend$",               "lambda:SuspendMicrovm"),
        rule("lambda", "GET",    "^/2025-09-09/microvms/[^/]+$",                       "lambda:GetMicrovm"),
        rule("lambda", "DELETE", "^/2025-09-09/microvms/[^/]+$",                       "lambda:TerminateMicrovm"),
        rule("lambda", "GET",    "^/2025-09-09/microvms$",                             "lambda:ListMicrovms"),
        rule("lambda", "POST",   "^/2025-09-09/microvms$",                             "lambda:RunMicrovm"),
        rule("lambda", "GET",    "^/2025-09-09/microvm-images/[^/]+/versions/[^/]+/builds/[^/]+$",
                "lambda:GetMicrovmImageBuild"),
        rule("lambda", "GET",    "^/2025-09-09/microvm-images/[^/]+/versions/[^/]+/builds$",
                "lambda:ListMicrovmImageBuilds"),
        rule("lambda", "GET",    "^/2025-09-09/microvm-images/[^/]+/versions/[^/]+$",  "lambda:GetMicrovmImageVersion"),
        rule("lambda", "PATCH",  "^/2025-09-09/microvm-images/[^/]+/versions/[^/]+$",  "lambda:UpdateMicrovmImageVersion"),
        rule("lambda", "DELETE", "^/2025-09-09/microvm-images/[^/]+/versions/[^/]+$",  "lambda:DeleteMicrovmImageVersion"),
        rule("lambda", "GET",    "^/2025-09-09/microvm-images/[^/]+/versions$",        "lambda:ListMicrovmImageVersions"),
        rule("lambda", "GET",    "^/2025-09-09/microvm-images/[^/]+$",                 "lambda:GetMicrovmImage"),
        rule("lambda", "PUT",    "^/2025-09-09/microvm-images/[^/]+$",                 "lambda:UpdateMicrovmImage"),
        rule("lambda", "DELETE", "^/2025-09-09/microvm-images/[^/]+$",                 "lambda:DeleteMicrovmImage"),
        rule("lambda", "GET",    "^/2025-09-09/microvm-images$",                       "lambda:ListMicrovmImages"),
        rule("lambda", "POST",   "^/2025-09-09/microvm-images$",                       "lambda:CreateMicrovmImage"),
        rule("lambda", "GET",    "^/2025-09-09/managed-microvm-images/[^/]+/versions$",
                "lambda:ListManagedMicrovmImageVersions"),
        rule("lambda", "GET",    "^/2025-09-09/managed-microvm-images$",               "lambda:ListManagedMicrovmImages"),
        rule("lambda", "GET",    "^/2017-03-31/tags/.+",                               "lambda:ListTags"),
        rule("lambda", "POST",   "^/2017-03-31/tags/.+",                               "lambda:TagResource"),
        rule("lambda", "DELETE", "^/2017-03-31/tags/.+",                               "lambda:UntagResource"),
        rule("lambda", "GET",    "^/2026-04-04/network-connectors/[^/]+/versions$",    "lambda:ListNetworkConnectorVersions"),
        rule("lambda", "GET",    "^/2026-04-04/network-connectors/[^/]+$",             "lambda:GetNetworkConnector"),
        rule("lambda", "PUT",    "^/2026-04-04/network-connectors/[^/]+$",             "lambda:UpdateNetworkConnector"),
        rule("lambda", "DELETE", "^/2026-04-04/network-connectors/[^/]+$",             "lambda:DeleteNetworkConnector"),
        rule("lambda", "GET",    "^/2026-04-04/network-connectors$",                   "lambda:ListNetworkConnectors"),
        rule("lambda", "POST",   "^/2026-04-04/network-connectors$",                   "lambda:CreateNetworkConnector"),

        // ── DynamoDB (JSON 1.1, action from X-Amz-Target handled separately) ──
        // Handled via Query-style action extraction in the filter

        // ── RDS Data API ───────────────────────────────────────────────────────
        rule("rds-data", "POST", "^/Execute/?$",              "rds-data:ExecuteStatement"),
        rule("rds-data", "POST", "^/ExecuteSql/?$",           "rds-data:ExecuteSql"),
        rule("rds-data", "POST", "^/BatchExecute/?$",         "rds-data:BatchExecuteStatement"),
        rule("rds-data", "POST", "^/BeginTransaction/?$",     "rds-data:BeginTransaction"),
        rule("rds-data", "POST", "^/CommitTransaction/?$",    "rds-data:CommitTransaction"),
        rule("rds-data", "POST", "^/RollbackTransaction/?$",  "rds-data:RollbackTransaction"),

        // ── Amazon Inspector ───────────────────────────────────────────────────
        rule("inspector2", "POST", "^/disable/?$", "inspector2:Disable"),

        // ── API Gateway ────────────────────────────────────────────────────────
        // The management API, v1 and v2, is authorized by HTTP method on the resource path:
        // apigateway:GET, POST, PUT, PATCH or DELETE. Two operations have an action of their own. The
        // others AWS authorizes only by an action of their own name, the v2 routing rules, portals and
        // portal products, are not served by Floci and need their own rules when they are.
        rule("apigateway", "POST",   "^/rejectdomainnameaccessassociations$",       "apigateway:RejectAccessAssociation"),
        rule("apigateway", "PUT",    "^/v2/domainnames/[^/]+/routingrules/[^/]+$",  "apigateway:UpdateRoutingRule"),
        rule("apigateway", "GET",    ".*",                                          "apigateway:GET"),
        rule("apigateway", "POST",   ".*",                                          "apigateway:POST"),
        rule("apigateway", "PUT",    ".*",                                          "apigateway:PUT"),
        rule("apigateway", "PATCH",  ".*",                                          "apigateway:PATCH"),
        rule("apigateway", "DELETE", ".*",                                          "apigateway:DELETE"),

        // ── EventBridge Scheduler ──────────────────────────────────────────────
        rule("scheduler", "GET",    "^/schedules/[^/]+$",       "scheduler:GetSchedule"),
        rule("scheduler", "POST",   "^/schedules/[^/]+$",       "scheduler:CreateSchedule"),
        rule("scheduler", "PUT",    "^/schedules/[^/]+$",       "scheduler:UpdateSchedule"),
        rule("scheduler", "DELETE", "^/schedules/[^/]+$",       "scheduler:DeleteSchedule"),
        rule("scheduler", "GET",    "^/schedules$",             "scheduler:ListSchedules"),
        rule("scheduler", "GET",    "^/schedule-groups/[^/]+$", "scheduler:GetScheduleGroup"),
        rule("scheduler", "POST",   "^/schedule-groups/[^/]+$", "scheduler:CreateScheduleGroup"),
        rule("scheduler", "DELETE", "^/schedule-groups/[^/]+$", "scheduler:DeleteScheduleGroup"),
        rule("scheduler", "GET",    "^/schedule-groups$",       "scheduler:ListScheduleGroups"),
        // Tagging is served at /tags/<arn> and at /tags, naming the ARN in the query or the body.
        rule("scheduler", "GET",    "^/tags(/.+)?$",            "scheduler:ListTagsForResource"),
        rule("scheduler", "POST",   "^/tags(/.+)?$",            "scheduler:TagResource"),
        rule("scheduler", "DELETE", "^/tags(/.+)?$",            "scheduler:UntagResource"),

        // ── Aurora DSQL ────────────────────────────────────────────────────────
        rule("dsql", "POST",   "^/cluster$",                                  "dsql:CreateCluster"),
        rule("dsql", "GET",    "^/cluster$",                                  "dsql:ListClusters"),
        rule("dsql", "GET",    "^/cluster/[^/]+$",                            "dsql:GetCluster"),
        rule("dsql", "POST",   "^/cluster/[^/]+$",                            "dsql:UpdateCluster"),
        rule("dsql", "DELETE", "^/cluster/[^/]+$",                            "dsql:DeleteCluster"),
        rule("dsql", "POST",   "^/cluster/[^/]+/policy$",                     "dsql:PutClusterPolicy"),
        rule("dsql", "GET",    "^/cluster/[^/]+/policy$",                     "dsql:GetClusterPolicy"),
        rule("dsql", "DELETE", "^/cluster/[^/]+/policy$",                     "dsql:DeleteClusterPolicy"),
        rule("dsql", "GET",    "^/clusters/[^/]+/vpc-endpoint-service-name$", "dsql:GetVpcEndpointServiceName"),
        rule("dsql", "POST",   "^/stream/[^/]+$",                             "dsql:CreateStream"),
        rule("dsql", "GET",    "^/stream/[^/]+$",                             "dsql:ListStreams"),
        rule("dsql", "GET",    "^/stream/[^/]+/[^/]+$",                       "dsql:GetStream"),
        rule("dsql", "DELETE", "^/stream/[^/]+/[^/]+$",                       "dsql:DeleteStream"),
        // Tagging is served at /tags/<arn> and at /tags, naming the ARN in the query or the body.
        rule("dsql", "GET",    "^/tags(/.+)?$",                               "dsql:ListTagsForResource"),
        rule("dsql", "POST",   "^/tags(/.+)?$",                               "dsql:TagResource"),
        rule("dsql", "DELETE", "^/tags(/.+)?$",                               "dsql:UntagResource"),

        // ── EventBridge Pipes ──────────────────────────────────────────────────
        rule("pipes", "POST",   "^/v1/pipes/[^/]+/start$", "pipes:StartPipe"),
        rule("pipes", "POST",   "^/v1/pipes/[^/]+/stop$",  "pipes:StopPipe"),
        rule("pipes", "GET",    "^/v1/pipes/[^/]+$",       "pipes:DescribePipe"),
        rule("pipes", "POST",   "^/v1/pipes/[^/]+$",       "pipes:CreatePipe"),
        rule("pipes", "PUT",    "^/v1/pipes/[^/]+$",       "pipes:UpdatePipe"),
        rule("pipes", "DELETE", "^/v1/pipes/[^/]+$",       "pipes:DeletePipe"),
        rule("pipes", "GET",    "^/v1/pipes$",             "pipes:ListPipes"),
        rule("pipes", "GET",    "^/tags(/.+)?$",           "pipes:ListTagsForResource"),
        rule("pipes", "POST",   "^/tags(/.+)?$",           "pipes:TagResource"),
        rule("pipes", "DELETE", "^/tags(/.+)?$",           "pipes:UntagResource"),

        // ── EKS ────────────────────────────────────────────────────────────────
        // Floci takes an access entry's principal ARN whole, slashes included, so any other GET or DELETE under
        // an entry is DescribeAccessEntry or DeleteAccessEntry. The entry's access policy routes come first, as
        // they win in the router; a DELETE is a disassociate only when a cluster access policy ARN follows.
        rule("eks", "GET",    "^/clusters/[^/]+/access-entries/.+/access-policies$",     "eks:ListAssociatedAccessPolicies"),
        rule("eks", "POST",   "^/clusters/[^/]+/access-entries/.+/access-policies$",     "eks:AssociateAccessPolicy"),
        rule("eks", "DELETE", "^/clusters/[^/]+/access-entries/.+/access-policies/" + EksController.ACCESS_POLICY_ARN + "$",
                "eks:DisassociateAccessPolicy"),
        rule("eks", "GET",    "^/clusters/[^/]+/access-entries/.+$",                     "eks:DescribeAccessEntry"),
        rule("eks", "DELETE", "^/clusters/[^/]+/access-entries/.+$",                     "eks:DeleteAccessEntry"),
        rule("eks", "POST",   "^/clusters/[^/]+/access-entries/.+$",                     "eks:UpdateAccessEntry"),
        rule("eks", "GET",    "^/clusters/[^/]+/access-entries$",                        "eks:ListAccessEntries"),
        rule("eks", "POST",   "^/clusters/[^/]+/access-entries$",                        "eks:CreateAccessEntry"),
        rule("eks", "GET",    "^/clusters$",                                             "eks:ListClusters"),
        rule("eks", "POST",   "^/clusters$",                                             "eks:CreateCluster"),
        rule("eks", "GET",    "^/clusters/[^/]+$",                                       "eks:DescribeCluster"),
        rule("eks", "DELETE", "^/clusters/[^/]+$",                                       "eks:DeleteCluster"),
        rule("eks", "POST",   "^/clusters/[^/]+/update-config$",                         "eks:UpdateClusterConfig"),
        rule("eks", "POST",   "^/clusters/[^/]+/encryption-config/associate$", "eks:AssociateEncryptionConfig"),
        rule("eks", "GET",    "^/clusters/[^/]+/updates$",                               "eks:ListUpdates"),
        rule("eks", "POST",   "^/clusters/[^/]+/updates$",                               "eks:UpdateClusterVersion"),
        rule("eks", "GET",    "^/clusters/[^/]+/updates/[^/]+$",                         "eks:DescribeUpdate"),
        rule("eks", "POST",   "^/clusters/[^/]+/updates/[^/]+/cancel-update$",           "eks:CancelUpdate"),
        rule("eks", "GET",    "^/clusters/[^/]+/node-groups$",                           "eks:ListNodegroups"),
        rule("eks", "POST",   "^/clusters/[^/]+/node-groups$",                           "eks:CreateNodegroup"),
        rule("eks", "GET",    "^/clusters/[^/]+/node-groups/[^/]+$",                     "eks:DescribeNodegroup"),
        rule("eks", "DELETE", "^/clusters/[^/]+/node-groups/[^/]+$",                     "eks:DeleteNodegroup"),
        rule("eks", "POST",   "^/clusters/[^/]+/node-groups/[^/]+/update-config$",       "eks:UpdateNodegroupConfig"),
        rule("eks", "POST",   "^/clusters/[^/]+/node-groups/[^/]+/update-version$",      "eks:UpdateNodegroupVersion"),
        rule("eks", "GET",    "^/clusters/[^/]+/fargate-profiles$",                      "eks:ListFargateProfiles"),
        rule("eks", "POST",   "^/clusters/[^/]+/fargate-profiles$",                      "eks:CreateFargateProfile"),
        rule("eks", "GET",    "^/clusters/[^/]+/fargate-profiles/[^/]+$",                "eks:DescribeFargateProfile"),
        rule("eks", "DELETE", "^/clusters/[^/]+/fargate-profiles/[^/]+$",                "eks:DeleteFargateProfile"),
        rule("eks", "GET",    "^/clusters/[^/]+/addons$",                                "eks:ListAddons"),
        rule("eks", "POST",   "^/clusters/[^/]+/addons$",                                "eks:CreateAddon"),
        rule("eks", "GET",    "^/clusters/[^/]+/addons/[^/]+$",                          "eks:DescribeAddon"),
        rule("eks", "DELETE", "^/clusters/[^/]+/addons/[^/]+$",                          "eks:DeleteAddon"),
        rule("eks", "POST",   "^/clusters/[^/]+/addons/[^/]+/update$",                   "eks:UpdateAddon"),
        rule("eks", "GET",    "^/addons/supported-versions$",                            "eks:DescribeAddonVersions"),
        rule("eks", "GET",    "^/addons/configuration-schemas$", "eks:DescribeAddonConfiguration"),
        rule("eks", "GET",    "^/clusters/[^/]+/pod-identity-associations$", "eks:ListPodIdentityAssociations"),
        rule("eks", "POST",   "^/clusters/[^/]+/pod-identity-associations$", "eks:CreatePodIdentityAssociation"),
        rule("eks", "GET",    "^/clusters/[^/]+/pod-identity-associations/[^/]+$",
                "eks:DescribePodIdentityAssociation"),
        rule("eks", "POST",   "^/clusters/[^/]+/pod-identity-associations/[^/]+$",
                "eks:UpdatePodIdentityAssociation"),
        rule("eks", "DELETE", "^/clusters/[^/]+/pod-identity-associations/[^/]+$",
                "eks:DeletePodIdentityAssociation"),
        rule("eks", "GET",    "^/clusters/[^/]+/identity-provider-configs$", "eks:ListIdentityProviderConfigs"),
        rule("eks", "POST",   "^/clusters/[^/]+/identity-provider-configs/associate$",
                "eks:AssociateIdentityProviderConfig"),
        rule("eks", "POST",   "^/clusters/[^/]+/identity-provider-configs/describe$",
                "eks:DescribeIdentityProviderConfig"),
        rule("eks", "POST",   "^/clusters/[^/]+/identity-provider-configs/disassociate$",
                "eks:DisassociateIdentityProviderConfig"),
        rule("eks", "POST",   "^/clusters/[^/]+/insights$",                              "eks:ListInsights"),
        rule("eks", "GET",    "^/clusters/[^/]+/insights/[^/]+$",                        "eks:DescribeInsight"),
        rule("eks", "GET",    "^/clusters/[^/]+/insights-refresh$",                      "eks:DescribeInsightsRefresh"),
        rule("eks", "POST",   "^/clusters/[^/]+/insights-refresh$",                      "eks:StartInsightsRefresh"),
        rule("eks", "GET",    "^/clusters/[^/]+/capabilities$",                          "eks:ListCapabilities"),
        rule("eks", "POST",   "^/clusters/[^/]+/capabilities$",                          "eks:CreateCapability"),
        rule("eks", "GET",    "^/clusters/[^/]+/capabilities/[^/]+$",                    "eks:DescribeCapability"),
        rule("eks", "POST",   "^/clusters/[^/]+/capabilities/[^/]+$",                    "eks:UpdateCapability"),
        rule("eks", "DELETE", "^/clusters/[^/]+/capabilities/[^/]+$",                    "eks:DeleteCapability"),
        rule("eks", "GET",    "^/clusters/[^/]+/certificate-authorities$", "eks:ListCertificateAuthorities"),
        rule("eks", "POST",   "^/clusters/[^/]+/certificate-authorities$", "eks:CreateCertificateAuthority"),
        rule("eks", "GET",    "^/clusters/[^/]+/certificate-authorities/[^/]+$",
                "eks:DescribeCertificateAuthority"),
        rule("eks", "DELETE", "^/clusters/[^/]+/certificate-authorities/[^/]+$",
                "eks:DeleteCertificateAuthority"),
        rule("eks", "POST",   "^/clusters/[^/]+/certificate-authorities/[^/]+/activate$",
                "eks:ActivateCertificateAuthority"),
        rule("eks", "GET",    "^/access-policies$",                                      "eks:ListAccessPolicies"),
        rule("eks", "GET",    "^/cluster-versions$",                                     "eks:DescribeClusterVersions"),
        rule("eks", "POST",   "^/cluster-registrations$",                                "eks:RegisterCluster"),
        rule("eks", "DELETE", "^/cluster-registrations/[^/]+$",                          "eks:DeregisterCluster"),
        rule("eks", "GET",    "^/eks-anywhere-subscriptions$", "eks:ListEksAnywhereSubscriptions"),
        rule("eks", "POST",   "^/eks-anywhere-subscriptions$", "eks:CreateEksAnywhereSubscription"),
        rule("eks", "GET",    "^/eks-anywhere-subscriptions/[^/]+$", "eks:DescribeEksAnywhereSubscription"),
        rule("eks", "POST",   "^/eks-anywhere-subscriptions/[^/]+$", "eks:UpdateEksAnywhereSubscription"),
        rule("eks", "DELETE", "^/eks-anywhere-subscriptions/[^/]+$", "eks:DeleteEksAnywhereSubscription"),
        rule("eks", "GET",    "^/tags(/.+)?$",                                           "eks:ListTagsForResource"),
        rule("eks", "POST",   "^/tags(/.+)?$",                                           "eks:TagResource"),
        rule("eks", "DELETE", "^/tags(/.+)?$",                                           "eks:UntagResource"),

        // ── Control Tower ──────────────────────────────────────────────────────
        rule("controltower", "GET",    "^/tags(/.+)?$", "controltower:ListTagsForResource"),
        rule("controltower", "POST",   "^/tags(/.+)?$", "controltower:TagResource"),
        rule("controltower", "DELETE", "^/tags(/.+)?$", "controltower:UntagResource"),

        // ── Bedrock ────────────────────────────────────────────────────────────
        rule("bedrock", "POST",   "^/resource-policy$",   "bedrock:PutResourcePolicy"),
        rule("bedrock", "GET",    "^/resource-policy/.+", "bedrock:GetResourcePolicy"),
        rule("bedrock", "DELETE", "^/resource-policy/.+", "bedrock:DeleteResourcePolicy"),

        // ── SES v2 ─────────────────────────────────────────────────────────────
        // SES v1 is a Query API and resolves from its Action like any other; v2 is REST-JSON under
        // /v2/email, so every operation needs its own rule. One rule per operation in the SES v2
        // model, each mapped to the IAM action AWS's service reference lists for it: always
        // ses:<Operation>, in the ses namespace SES v2 shares with v1. SendEmail has no entry there;
        // the Service Authorization Reference's SES v2 page defines ses:SendEmail as v2's only send
        // action whatever the content (ses:SendRawEmail belongs to v1), so it takes its name too.
        // ListConfigurationSets and ListEmailIdentities keep their GET routes next to the POST ones
        // the model moved them to, since older SDKs, and Floci's own routes, still use them.
        rule("ses", "GET",   "^/v2/email/account/?$",                                                "ses:GetAccount"),
        rule("ses", "PUT",   "^/v2/email/account/dedicated-ips/warmup/?$",                           "ses:PutAccountDedicatedIpWarmupAttributes"),
        rule("ses", "POST",  "^/v2/email/account/details/?$",                                        "ses:PutAccountDetails"),
        rule("ses", "PUT",   "^/v2/email/account/pricing-attributes/?$",                             "ses:PutAccountPricingAttributes"),
        rule("ses", "PUT",   "^/v2/email/account/sending/?$",                                        "ses:PutAccountSendingAttributes"),
        rule("ses", "PUT",   "^/v2/email/account/suppression/?$",                                    "ses:PutAccountSuppressionAttributes"),
        rule("ses", "PUT",   "^/v2/email/account/vdm/?$",                                            "ses:PutAccountVdmAttributes"),
        rule("ses", "GET",   "^/v2/email/configuration-sets/?$",                                     "ses:ListConfigurationSets"),
        rule("ses", "POST",  "^/v2/email/configuration-sets/?$",                                     "ses:CreateConfigurationSet"),
        rule("ses", "DELETE","^/v2/email/configuration-sets/[^/]+/?$",                               "ses:DeleteConfigurationSet"),
        rule("ses", "GET",   "^/v2/email/configuration-sets/[^/]+/?$",                               "ses:GetConfigurationSet"),
        rule("ses", "PUT",   "^/v2/email/configuration-sets/[^/]+/archiving-options/?$",             "ses:PutConfigurationSetArchivingOptions"),
        rule("ses", "PUT",   "^/v2/email/configuration-sets/[^/]+/delivery-options/?$",              "ses:PutConfigurationSetDeliveryOptions"),
        rule("ses", "GET",   "^/v2/email/configuration-sets/[^/]+/event-destinations/?$",            "ses:GetConfigurationSetEventDestinations"),
        rule("ses", "POST",  "^/v2/email/configuration-sets/[^/]+/event-destinations/?$",            "ses:CreateConfigurationSetEventDestination"),
        rule("ses", "DELETE","^/v2/email/configuration-sets/[^/]+/event-destinations/[^/]+/?$",      "ses:DeleteConfigurationSetEventDestination"),
        rule("ses", "PUT",   "^/v2/email/configuration-sets/[^/]+/event-destinations/[^/]+/?$",      "ses:UpdateConfigurationSetEventDestination"),
        rule("ses", "PUT",   "^/v2/email/configuration-sets/[^/]+/reputation-options/?$",            "ses:PutConfigurationSetReputationOptions"),
        rule("ses", "PUT",   "^/v2/email/configuration-sets/[^/]+/sending/?$",                       "ses:PutConfigurationSetSendingOptions"),
        rule("ses", "PUT",   "^/v2/email/configuration-sets/[^/]+/suppression-options/?$",           "ses:PutConfigurationSetSuppressionOptions"),
        rule("ses", "PUT",   "^/v2/email/configuration-sets/[^/]+/tracking-options/?$",              "ses:PutConfigurationSetTrackingOptions"),
        rule("ses", "PUT",   "^/v2/email/configuration-sets/[^/]+/vdm-options/?$",                   "ses:PutConfigurationSetVdmOptions"),
        rule("ses", "GET",   "^/v2/email/contact-lists/?$",                                          "ses:ListContactLists"),
        rule("ses", "POST",  "^/v2/email/contact-lists/?$",                                          "ses:CreateContactList"),
        rule("ses", "DELETE","^/v2/email/contact-lists/[^/]+/?$",                                    "ses:DeleteContactList"),
        rule("ses", "GET",   "^/v2/email/contact-lists/[^/]+/?$",                                    "ses:GetContactList"),
        rule("ses", "PUT",   "^/v2/email/contact-lists/[^/]+/?$",                                    "ses:UpdateContactList"),
        rule("ses", "POST",  "^/v2/email/contact-lists/[^/]+/contacts/?$",                           "ses:CreateContact"),
        rule("ses", "POST",  "^/v2/email/contact-lists/[^/]+/contacts/list/?$",                      "ses:ListContacts"),
        rule("ses", "DELETE","^/v2/email/contact-lists/[^/]+/contacts/[^/]+/?$",                     "ses:DeleteContact"),
        rule("ses", "GET",   "^/v2/email/contact-lists/[^/]+/contacts/[^/]+/?$",                     "ses:GetContact"),
        rule("ses", "PUT",   "^/v2/email/contact-lists/[^/]+/contacts/[^/]+/?$",                     "ses:UpdateContact"),
        rule("ses", "GET",   "^/v2/email/custom-verification-email-templates/?$",                    "ses:ListCustomVerificationEmailTemplates"),
        rule("ses", "POST",  "^/v2/email/custom-verification-email-templates/?$",                    "ses:CreateCustomVerificationEmailTemplate"),
        rule("ses", "DELETE","^/v2/email/custom-verification-email-templates/[^/]+/?$",              "ses:DeleteCustomVerificationEmailTemplate"),
        rule("ses", "GET",   "^/v2/email/custom-verification-email-templates/[^/]+/?$",              "ses:GetCustomVerificationEmailTemplate"),
        rule("ses", "PUT",   "^/v2/email/custom-verification-email-templates/[^/]+/?$",              "ses:UpdateCustomVerificationEmailTemplate"),
        rule("ses", "GET",   "^/v2/email/dedicated-ip-pools/?$",                                     "ses:ListDedicatedIpPools"),
        rule("ses", "POST",  "^/v2/email/dedicated-ip-pools/?$",                                     "ses:CreateDedicatedIpPool"),
        rule("ses", "DELETE","^/v2/email/dedicated-ip-pools/[^/]+/?$",                               "ses:DeleteDedicatedIpPool"),
        rule("ses", "GET",   "^/v2/email/dedicated-ip-pools/[^/]+/?$",                               "ses:GetDedicatedIpPool"),
        rule("ses", "PUT",   "^/v2/email/dedicated-ip-pools/[^/]+/scaling/?$",                       "ses:PutDedicatedIpPoolScalingAttributes"),
        rule("ses", "GET",   "^/v2/email/dedicated-ips/?$",                                          "ses:GetDedicatedIps"),
        rule("ses", "GET",   "^/v2/email/dedicated-ips/[^/]+/?$",                                    "ses:GetDedicatedIp"),
        rule("ses", "PUT",   "^/v2/email/dedicated-ips/[^/]+/pool/?$",                               "ses:PutDedicatedIpInPool"),
        rule("ses", "PUT",   "^/v2/email/dedicated-ips/[^/]+/warmup/?$",                             "ses:PutDedicatedIpWarmupAttributes"),
        rule("ses", "GET",   "^/v2/email/deliverability-dashboard/?$",                               "ses:GetDeliverabilityDashboardOptions"),
        rule("ses", "PUT",   "^/v2/email/deliverability-dashboard/?$",                               "ses:PutDeliverabilityDashboardOption"),
        rule("ses", "GET",   "^/v2/email/deliverability-dashboard/blacklist-report/?$",              "ses:GetBlacklistReports"),
        rule("ses", "GET",   "^/v2/email/deliverability-dashboard/campaigns/[^/]+/?$",               "ses:GetDomainDeliverabilityCampaign"),
        rule("ses", "GET",   "^/v2/email/deliverability-dashboard/domains/[^/]+/campaigns/?$",       "ses:ListDomainDeliverabilityCampaigns"),
        rule("ses", "GET",   "^/v2/email/deliverability-dashboard/statistics-report/[^/]+/?$",       "ses:GetDomainStatisticsReport"),
        rule("ses", "POST",  "^/v2/email/deliverability-dashboard/test/?$",                          "ses:CreateDeliverabilityTestReport"),
        rule("ses", "GET",   "^/v2/email/deliverability-dashboard/test-reports/?$",                  "ses:ListDeliverabilityTestReports"),
        rule("ses", "GET",   "^/v2/email/deliverability-dashboard/test-reports/[^/]+/?$",            "ses:GetDeliverabilityTestReport"),
        rule("ses", "POST",  "^/v2/email/email-address-insights/?$",                                 "ses:GetEmailAddressInsights"),
        rule("ses", "POST",  "^/v2/email/export-jobs/?$",                                            "ses:CreateExportJob"),
        rule("ses", "GET",   "^/v2/email/export-jobs/[^/]+/?$",                                      "ses:GetExportJob"),
        rule("ses", "PUT",   "^/v2/email/export-jobs/[^/]+/cancel/?$",                               "ses:CancelExportJob"),
        rule("ses", "GET",   "^/v2/email/identities/?$",                                             "ses:ListEmailIdentities"),
        rule("ses", "POST",  "^/v2/email/identities/?$",                                             "ses:CreateEmailIdentity"),
        rule("ses", "DELETE","^/v2/email/identities/[^/]+/?$",                                       "ses:DeleteEmailIdentity"),
        rule("ses", "GET",   "^/v2/email/identities/[^/]+/?$",                                       "ses:GetEmailIdentity"),
        rule("ses", "PUT",   "^/v2/email/identities/[^/]+/configuration-set/?$",                     "ses:PutEmailIdentityConfigurationSetAttributes"),
        rule("ses", "PUT",   "^/v2/email/identities/[^/]+/dkim/?$",                                  "ses:PutEmailIdentityDkimAttributes"),
        rule("ses", "PUT",   "^/v2/email/identities/[^/]+/dkim/signing/?$",                          "ses:PutEmailIdentityDkimSigningAttributes"),
        rule("ses", "PUT",   "^/v2/email/identities/[^/]+/feedback/?$",                              "ses:PutEmailIdentityFeedbackAttributes"),
        rule("ses", "PUT",   "^/v2/email/identities/[^/]+/mail-from/?$",                             "ses:PutEmailIdentityMailFromAttributes"),
        rule("ses", "GET",   "^/v2/email/identities/[^/]+/policies/?$",                              "ses:GetEmailIdentityPolicies"),
        rule("ses", "DELETE","^/v2/email/identities/[^/]+/policies/[^/]+/?$",                        "ses:DeleteEmailIdentityPolicy"),
        rule("ses", "POST",  "^/v2/email/identities/[^/]+/policies/[^/]+/?$",                        "ses:CreateEmailIdentityPolicy"),
        rule("ses", "PUT",   "^/v2/email/identities/[^/]+/policies/[^/]+/?$",                        "ses:UpdateEmailIdentityPolicy"),
        rule("ses", "POST",  "^/v2/email/identity/certificates/?$",                                  "ses:AssociateEmailIdentityCertificate"),
        rule("ses", "POST",  "^/v2/email/identity/certificates/delete/?$",                           "ses:DisassociateEmailIdentityCertificate"),
        rule("ses", "POST",  "^/v2/email/identity/certificates/list/?$",                             "ses:ListEmailIdentityCertificates"),
        rule("ses", "POST",  "^/v2/email/import-jobs/?$",                                            "ses:CreateImportJob"),
        rule("ses", "POST",  "^/v2/email/import-jobs/list/?$",                                       "ses:ListImportJobs"),
        rule("ses", "GET",   "^/v2/email/import-jobs/[^/]+/?$",                                      "ses:GetImportJob"),
        rule("ses", "GET",   "^/v2/email/insights/[^/]+/?$",                                         "ses:GetMessageInsights"),
        rule("ses", "POST",  "^/v2/email/list-configuration-sets/?$",                                "ses:ListConfigurationSets"),
        rule("ses", "POST",  "^/v2/email/list-export-jobs/?$",                                       "ses:ListExportJobs"),
        rule("ses", "POST",  "^/v2/email/list-identities/?$",                                        "ses:ListEmailIdentities"),
        rule("ses", "POST",  "^/v2/email/metrics/batch/?$",                                          "ses:BatchGetMetricData"),
        rule("ses", "GET",   "^/v2/email/multi-region-endpoints/?$",                                 "ses:ListMultiRegionEndpoints"),
        rule("ses", "POST",  "^/v2/email/multi-region-endpoints/?$",                                 "ses:CreateMultiRegionEndpoint"),
        rule("ses", "DELETE","^/v2/email/multi-region-endpoints/[^/]+/?$",                           "ses:DeleteMultiRegionEndpoint"),
        rule("ses", "GET",   "^/v2/email/multi-region-endpoints/[^/]+/?$",                           "ses:GetMultiRegionEndpoint"),
        rule("ses", "POST",  "^/v2/email/outbound-bulk-emails/?$",                                   "ses:SendBulkEmail"),
        rule("ses", "POST",  "^/v2/email/outbound-custom-verification-emails/?$",                    "ses:SendCustomVerificationEmail"),
        rule("ses", "POST",  "^/v2/email/outbound-emails/?$",                                        "ses:SendEmail"),
        rule("ses", "POST",  "^/v2/email/reputation/entities/?$",                                    "ses:ListReputationEntities"),
        rule("ses", "GET",   "^/v2/email/reputation/entities/[^/]+/[^/]+/?$",                        "ses:GetReputationEntity"),
        rule("ses", "PUT",   "^/v2/email/reputation/entities/[^/]+/[^/]+/customer-managed-status/?$","ses:UpdateReputationEntityCustomerManagedStatus"),
        rule("ses", "PUT",   "^/v2/email/reputation/entities/[^/]+/[^/]+/policy/?$",                 "ses:UpdateReputationEntityPolicy"),
        rule("ses", "POST",  "^/v2/email/resources/tenants/list/?$",                                 "ses:ListResourceTenants"),
        rule("ses", "GET",   "^/v2/email/suppression/addresses/?$",                                  "ses:ListSuppressedDestinations"),
        rule("ses", "PUT",   "^/v2/email/suppression/addresses/?$",                                  "ses:PutSuppressedDestination"),
        rule("ses", "DELETE","^/v2/email/suppression/addresses/[^/]+/?$",                            "ses:DeleteSuppressedDestination"),
        rule("ses", "GET",   "^/v2/email/suppression/addresses/[^/]+/?$",                            "ses:GetSuppressedDestination"),
        rule("ses", "DELETE","^/v2/email/tags/?$",                                                   "ses:UntagResource"),
        rule("ses", "GET",   "^/v2/email/tags/?$",                                                   "ses:ListTagsForResource"),
        rule("ses", "POST",  "^/v2/email/tags/?$",                                                   "ses:TagResource"),
        rule("ses", "GET",   "^/v2/email/templates/?$",                                              "ses:ListEmailTemplates"),
        rule("ses", "POST",  "^/v2/email/templates/?$",                                              "ses:CreateEmailTemplate"),
        rule("ses", "DELETE","^/v2/email/templates/[^/]+/?$",                                        "ses:DeleteEmailTemplate"),
        rule("ses", "GET",   "^/v2/email/templates/[^/]+/?$",                                        "ses:GetEmailTemplate"),
        rule("ses", "PUT",   "^/v2/email/templates/[^/]+/?$",                                        "ses:UpdateEmailTemplate"),
        rule("ses", "POST",  "^/v2/email/templates/[^/]+/render/?$",                                 "ses:TestRenderEmailTemplate"),
        rule("ses", "POST",  "^/v2/email/tenant/suppression/?$",                                     "ses:PutTenantSuppressionAttributes"),
        rule("ses", "POST",  "^/v2/email/tenants/?$",                                                "ses:CreateTenant"),
        rule("ses", "POST",  "^/v2/email/tenants/delete/?$",                                         "ses:DeleteTenant"),
        rule("ses", "POST",  "^/v2/email/tenants/get/?$",                                            "ses:GetTenant"),
        rule("ses", "POST",  "^/v2/email/tenants/list/?$",                                           "ses:ListTenants"),
        rule("ses", "POST",  "^/v2/email/tenants/resources/?$",                                      "ses:CreateTenantResourceAssociation"),
        rule("ses", "POST",  "^/v2/email/tenants/resources/delete/?$",                               "ses:DeleteTenantResourceAssociation"),
        rule("ses", "POST",  "^/v2/email/tenants/resources/list/?$",                                 "ses:ListTenantResources"),
        rule("ses", "POST",  "^/v2/email/update-configuration-sets/?$",                              "ses:UpdateConfigurationSet"),
        rule("ses", "POST",  "^/v2/email/vdm/recommendations/?$",                                    "ses:ListRecommendations"),

        // ── Resource Groups ─────────────────────────────────────────────────────
        rule("geo", "GET", "^/tracking/v0/trackers/[^/]+/devices/[^/]+/positions/latest/?$", "geo:GetDevicePosition"),
        rule("geo", "POST", "^/tracking/v0/trackers/[^/]+/devices/[^/]+/list-positions/?$", "geo:GetDevicePositionHistory"),
        rule("geo", "POST", "^/geofencing/v0/collections/[^/]+/forecast-geofence-events/?$", "geo:ForecastGeofenceEvents"),
        rule("geo", "GET", "^/geofencing/v0/collections/[^/]+/geofences/[^/]+/?$", "geo:GetGeofence"),
        rule("geo", "PUT", "^/geofencing/v0/collections/[^/]+/geofences/[^/]+/?$", "geo:PutGeofence"),
        rule("geo", "POST", "^/routes/v0/calculators/[^/]+/calculate/route-matrix/?$", "geo:CalculateRouteMatrix"),
        rule("geo", "GET", "^/maps/v0/maps/[^/]+/glyphs/[^/]+/[^/]+/?$", "geo:GetMapGlyphs"),
        rule("geo", "POST", "^/geofencing/v0/collections/[^/]+/delete-geofences/?$", "geo:BatchDeleteGeofence"),
        rule("geo", "DELETE", "^/tracking/v0/trackers/[^/]+/consumers/.+/?$", "geo:DisassociateTrackerConsumer"),
        rule("geo", "POST", "^/geofencing/v0/collections/[^/]+/list-geofences/?$", "geo:ListGeofences"),
        rule("geo", "POST", "^/geofencing/v0/collections/[^/]+/put-geofences/?$", "geo:BatchPutGeofence"),
        rule("geo", "POST", "^/routes/v0/calculators/[^/]+/calculate/route/?$", "geo:CalculateRoute"),
        rule("geo", "POST", "^/geofencing/v0/collections/[^/]+/positions/?$", "geo:BatchEvaluateGeofences"),
        rule("geo", "POST", "^/tracking/v0/trackers/[^/]+/delete-positions/?$", "geo:BatchDeleteDevicePositionHistory"),
        rule("geo", "POST", "^/tracking/v0/trackers/[^/]+/positions/verify/?$", "geo:VerifyDevicePosition"),
        rule("geo", "POST", "^/tracking/v0/trackers/[^/]+/list-positions/?$", "geo:ListDevicePositions"),
        rule("geo", "POST", "^/tracking/v0/trackers/[^/]+/list-consumers/?$", "geo:ListTrackerConsumers"),
        rule("geo", "POST", "^/tracking/v0/trackers/[^/]+/get-positions/?$", "geo:BatchGetDevicePosition"),
        rule("geo", "POST", "^/places/v0/indexes/[^/]+/search/suggestions/?$", "geo:SearchPlaceIndexForSuggestions"),
        rule("geo", "GET", "^/places/v0/indexes/[^/]+/places/[^/]+/?$", "geo:GetPlace"),
        rule("geo", "POST", "^/places/v0/indexes/[^/]+/search/position/?$", "geo:SearchPlaceIndexForPosition"),
        rule("geo", "POST", "^/tracking/v0/trackers/[^/]+/consumers/?$", "geo:AssociateTrackerConsumer"),
        rule("geo", "POST", "^/tracking/v0/trackers/[^/]+/positions/?$", "geo:BatchUpdateDevicePosition"),
        rule("geo", "DELETE", "^/geofencing/v0/collections/[^/]+/?$", "geo:DeleteGeofenceCollection"),
        rule("geo", "GET", "^/geofencing/v0/collections/[^/]+/?$", "geo:DescribeGeofenceCollection"),
        rule("geo", "PATCH", "^/geofencing/v0/collections/[^/]+/?$", "geo:UpdateGeofenceCollection"),
        rule("geo", "GET", "^/maps/v0/maps/[^/]+/sprites/[^/]+/?$", "geo:GetMapSprites"),
        rule("geo", "POST", "^/places/v0/indexes/[^/]+/search/text/?$", "geo:SearchPlaceIndexForText"),
        rule("geo", "GET", "^/maps/v0/maps/[^/]+/tiles/[^/]+/[^/]+/[^/]+/?$", "geo:GetMapTile"),
        rule("geo", "GET", "^/maps/v0/maps/[^/]+/style-descriptor/?$", "geo:GetMapStyleDescriptor"),
        rule("geo", "DELETE", "^/routes/v0/calculators/[^/]+/?$", "geo:DeleteRouteCalculator"),
        rule("geo", "GET", "^/routes/v0/calculators/[^/]+/?$", "geo:DescribeRouteCalculator"),
        rule("geo", "PATCH", "^/routes/v0/calculators/[^/]+/?$", "geo:UpdateRouteCalculator"),
        rule("geo", "DELETE", "^/tracking/v0/trackers/[^/]+/?$", "geo:DeleteTracker"),
        rule("geo", "GET", "^/tracking/v0/trackers/[^/]+/?$", "geo:DescribeTracker"),
        rule("geo", "PATCH", "^/tracking/v0/trackers/[^/]+/?$", "geo:UpdateTracker"),
        rule("geo", "POST", "^/geofencing/v0/list-collections/?$", "geo:ListGeofenceCollections"),
        rule("geo", "DELETE", "^/places/v0/indexes/[^/]+/?$", "geo:DeletePlaceIndex"),
        rule("geo", "GET", "^/places/v0/indexes/[^/]+/?$", "geo:DescribePlaceIndex"),
        rule("geo", "PATCH", "^/places/v0/indexes/[^/]+/?$", "geo:UpdatePlaceIndex"),
        rule("geo", "POST", "^/metadata/v0/jobs/cancel-job/?$", "geo:CancelJob"),
        rule("geo", "DELETE", "^/metadata/v0/keys/[^/]+/?$", "geo:DeleteKey"),
        rule("geo", "GET", "^/metadata/v0/keys/[^/]+/?$", "geo:DescribeKey"),
        rule("geo", "POST", "^/metadata/v0/jobs/list-jobs/?$", "geo:ListJobs"),
        rule("geo", "POST", "^/routes/v0/list-calculators/?$", "geo:ListRouteCalculators"),
        rule("geo", "PATCH", "^/metadata/v0/keys/[^/]+/?$", "geo:UpdateKey"),
        rule("geo", "POST", "^/geofencing/v0/collections/?$", "geo:CreateGeofenceCollection"),
        rule("geo", "POST", "^/tracking/v0/list-trackers/?$", "geo:ListTrackers"),
        rule("geo", "GET", "^/metadata/v0/jobs/[^/]+/?$", "geo:GetJob"),
        rule("geo", "DELETE", "^/maps/v0/maps/[^/]+/?$", "geo:DeleteMap"),
        rule("geo", "GET", "^/maps/v0/maps/[^/]+/?$", "geo:DescribeMap"),
        rule("geo", "POST", "^/places/v0/list-indexes/?$", "geo:ListPlaceIndexes"),
        rule("geo", "PATCH", "^/maps/v0/maps/[^/]+/?$", "geo:UpdateMap"),
        rule("geo", "POST", "^/routes/v0/calculators/?$", "geo:CreateRouteCalculator"),
        rule("geo", "POST", "^/metadata/v0/list-keys/?$", "geo:ListKeys"),
        rule("geo", "POST", "^/tracking/v0/trackers/?$", "geo:CreateTracker"),
        rule("geo", "POST", "^/places/v0/indexes/?$", "geo:CreatePlaceIndex"),
        rule("geo", "POST", "^/maps/v0/list-maps/?$", "geo:ListMaps"),
        rule("geo", "POST", "^/metadata/v0/keys/?$", "geo:CreateKey"),
        rule("geo", "POST", "^/metadata/v0/jobs/?$", "geo:StartJob"),
        rule("geo", "POST", "^/maps/v0/maps/?$", "geo:CreateMap"),
        rule("geo-maps", "GET", "^/v2/styles/[^/]+/[^/]+/[^/]+/sprites/[^/]+/?$", "geo-maps:GetSprites"),
        rule("geo-maps", "GET", "^/v2/glyphs/[^/]+/[^/]+/?$", "geo-maps:GetGlyphs"),
        rule("geo-maps", "GET", "^/v2/tiles/[^/]+/[^/]+/[^/]+/[^/]+/?$", "geo-maps:GetTile"),
        rule("geo-maps", "GET", "^/v2/styles/[^/]+/descriptor/?$", "geo-maps:GetStyleDescriptor"),
        rule("geo-maps", "GET", "^/v2/static/[^/]+/?$", "geo-maps:GetStaticMap"),
        rule("geo-places", "GET", "^/v2/place/[^/]+/?$", "geo-places:GetPlace"),
        rule("geo-places", "POST", "^/v2/reverse-geocode/?$", "geo-places:ReverseGeocode"),
        rule("geo-places", "POST", "^/v2/search-nearby/?$", "geo-places:SearchNearby"),
        rule("geo-places", "POST", "^/v2/autocomplete/?$", "geo-places:Autocomplete"),
        rule("geo-places", "POST", "^/v2/search-text/?$", "geo-places:SearchText"),
        rule("geo-places", "POST", "^/v2/geocode/?$", "geo-places:Geocode"),
        rule("geo-places", "POST", "^/v2/suggest/?$", "geo-places:Suggest"),
        rule("geo-routes", "POST", "^/v2/optimize-waypoints/?$", "geo-routes:OptimizeWaypoints"),
        rule("geo-routes", "POST", "^/v2/snap-to-roads/?$", "geo-routes:SnapToRoads"),
        rule("geo-routes", "POST", "^/v2/route-matrix/?$", "geo-routes:CalculateRouteMatrix"),
        rule("geo-routes", "POST", "^/v2/isolines/?$", "geo-routes:CalculateIsolines"),
        rule("geo-routes", "POST", "^/v2/routes/?$", "geo-routes:CalculateRoutes"),
        rule("geo", "GET", "^/tags/.+", "geo:ListTagsForResource"),
        rule("geo", "POST", "^/tags/.+", "geo:TagResource"),
        rule("geo", "DELETE", "^/tags/.+", "geo:UntagResource"),
        rule("amplify", "POST", "^/apps/[^/]+/branches/[^/]+/deployments/start/?$", "amplify:StartDeployment"),
        rule("amplify", "GET", "^/apps/[^/]+/branches/[^/]+/jobs/[^/]+/artifacts/?$", "amplify:ListArtifacts"),
        rule("amplify", "POST", "^/apps/[^/]+/branches/[^/]+/deployments/?$", "amplify:CreateDeployment"),
        rule("amplify", "DELETE", "^/apps/[^/]+/backendenvironments/[^/]+/?$", "amplify:DeleteBackendEnvironment"),
        rule("amplify", "GET", "^/apps/[^/]+/backendenvironments/[^/]+/?$", "amplify:GetBackendEnvironment"),
        rule("amplify", "DELETE", "^/apps/[^/]+/branches/[^/]+/jobs/[^/]+/stop/?$", "amplify:StopJob"),
        rule("amplify", "POST", "^/apps/[^/]+/backendenvironments/?$", "amplify:CreateBackendEnvironment"),
        rule("amplify", "GET", "^/apps/[^/]+/backendenvironments/?$", "amplify:ListBackendEnvironments"),
        rule("amplify", "DELETE", "^/apps/[^/]+/branches/[^/]+/jobs/[^/]+/?$", "amplify:DeleteJob"),
        rule("amplify", "GET", "^/apps/[^/]+/branches/[^/]+/jobs/[^/]+/?$", "amplify:GetJob"),
        rule("amplify", "GET", "^/apps/[^/]+/branches/[^/]+/jobs/?$", "amplify:ListJobs"),
        rule("amplify", "POST", "^/apps/[^/]+/branches/[^/]+/jobs/?$", "amplify:StartJob"),
        rule("amplify", "POST", "^/apps/[^/]+/accesslogs/?$", "amplify:GenerateAccessLogs"),
        rule("amplify", "DELETE", "^/apps/[^/]+/branches/[^/]+/?$", "amplify:DeleteBranch"),
        rule("amplify", "GET", "^/apps/[^/]+/branches/[^/]+/?$", "amplify:GetBranch"),
        rule("amplify", "POST", "^/apps/[^/]+/branches/[^/]+/?$", "amplify:UpdateBranch"),
        rule("amplify", "POST", "^/apps/[^/]+/branches/?$", "amplify:CreateBranch"),
        rule("amplify", "POST", "^/apps/[^/]+/webhooks/?$", "amplify:CreateWebhook"),
        rule("amplify", "DELETE", "^/apps/[^/]+/domains/[^/]+/?$", "amplify:DeleteDomainAssociation"),
        rule("amplify", "GET", "^/apps/[^/]+/domains/[^/]+/?$", "amplify:GetDomainAssociation"),
        rule("amplify", "GET", "^/apps/[^/]+/branches/?$", "amplify:ListBranches"),
        rule("amplify", "GET", "^/apps/[^/]+/webhooks/?$", "amplify:ListWebhooks"),
        rule("amplify", "POST", "^/apps/[^/]+/domains/[^/]+/?$", "amplify:UpdateDomainAssociation"),
        rule("amplify", "POST", "^/apps/[^/]+/domains/?$", "amplify:CreateDomainAssociation"),
        rule("amplify", "GET", "^/apps/[^/]+/domains/?$", "amplify:ListDomainAssociations"),
        rule("amplify", "GET", "^/artifacts/[^/]+/?$", "amplify:GetArtifactUrl"),
        rule("amplify", "DELETE", "^/webhooks/[^/]+/?$", "amplify:DeleteWebhook"),
        rule("amplify", "GET", "^/webhooks/[^/]+/?$", "amplify:GetWebhook"),
        rule("amplify", "POST", "^/webhooks/[^/]+/?$", "amplify:UpdateWebhook"),
        rule("amplify", "DELETE", "^/apps/[^/]+/?$", "amplify:DeleteApp"),
        rule("amplify", "GET", "^/apps/[^/]+/?$", "amplify:GetApp"),
        rule("amplify", "POST", "^/apps/[^/]+/?$", "amplify:UpdateApp"),
        rule("amplify", "POST", "^/apps/?$", "amplify:CreateApp"),
        rule("amplify", "GET", "^/apps/?$", "amplify:ListApps"),
        rule("appflow", "POST", "^/cancel-flow-executions/?$", "appflow:CancelFlowExecutions"),
        rule("appflow", "POST", "^/create-connector-profile/?$", "appflow:CreateConnectorProfile"),
        rule("appflow", "POST", "^/create-flow/?$", "appflow:CreateFlow"),
        rule("appflow", "POST", "^/delete-connector-profile/?$", "appflow:DeleteConnectorProfile"),
        rule("appflow", "POST", "^/delete-flow/?$", "appflow:DeleteFlow"),
        rule("appflow", "POST", "^/describe-connector/?$", "appflow:DescribeConnector"),
        rule("appflow", "POST", "^/describe-connector-entity/?$", "appflow:DescribeConnectorEntity"),
        rule("appflow", "POST", "^/describe-connector-profiles/?$", "appflow:DescribeConnectorProfiles"),
        rule("appflow", "POST", "^/describe-connectors/?$", "appflow:DescribeConnectors"),
        rule("appflow", "POST", "^/describe-flow/?$", "appflow:DescribeFlow"),
        rule("appflow", "POST", "^/describe-flow-execution-records/?$", "appflow:DescribeFlowExecutionRecords"),
        rule("appflow", "POST", "^/list-connector-entities/?$", "appflow:ListConnectorEntities"),
        rule("appflow", "POST", "^/list-connectors/?$", "appflow:ListConnectors"),
        rule("appflow", "POST", "^/list-flows/?$", "appflow:ListFlows"),
        rule("appflow", "POST", "^/register-connector/?$", "appflow:RegisterConnector"),
        rule("appflow", "POST", "^/reset-connector-metadata-cache/?$", "appflow:ResetConnectorMetadataCache"),
        rule("appflow", "POST", "^/start-flow/?$", "appflow:StartFlow"),
        rule("appflow", "POST", "^/stop-flow/?$", "appflow:StopFlow"),
        rule("appflow", "POST", "^/unregister-connector/?$", "appflow:UnregisterConnector"),
        rule("appflow", "POST", "^/update-connector-profile/?$", "appflow:UpdateConnectorProfile"),
        rule("appflow", "POST", "^/update-connector-registration/?$", "appflow:UpdateConnectorRegistration"),
        rule("appflow", "POST", "^/update-flow/?$", "appflow:UpdateFlow"),
        rule("servicecatalog", "GET", "^/_appregistry/applications/[^/]+/attribute-group-details/?$", "servicecatalog:ListAttributeGroupsForApplication"),
        rule("servicecatalog", "PUT", "^/_appregistry/applications/[^/]+/attribute-groups/[^/]+/?$", "servicecatalog:AssociateAttributeGroup"),
        rule("servicecatalog", "DELETE", "^/_appregistry/applications/[^/]+/attribute-groups/[^/]+/?$", "servicecatalog:DisassociateAttributeGroup"),
        rule("servicecatalog", "GET", "^/_appregistry/applications/[^/]+/attribute-groups/?$", "servicecatalog:ListAssociatedAttributeGroups"),
        rule("servicecatalog", "PUT", "^/_appregistry/applications/[^/]+/resources/[^/]+/[^/]+/?$", "servicecatalog:AssociateResource"),
        rule("servicecatalog", "DELETE", "^/_appregistry/applications/[^/]+/resources/[^/]+/[^/]+/?$", "servicecatalog:DisassociateResource"),
        rule("servicecatalog", "GET", "^/_appregistry/applications/[^/]+/resources/[^/]+/[^/]+/?$", "servicecatalog:GetAssociatedResource"),
        rule("servicecatalog", "GET", "^/_appregistry/applications/[^/]+/resources/?$", "servicecatalog:ListAssociatedResources"),
        rule("servicecatalog", "DELETE", "^/_appregistry/attribute-groups/[^/]+/?$", "servicecatalog:DeleteAttributeGroup"),
        rule("servicecatalog", "GET", "^/_appregistry/attribute-groups/[^/]+/?$", "servicecatalog:GetAttributeGroup"),
        rule("servicecatalog", "PATCH", "^/_appregistry/attribute-groups/[^/]+/?$", "servicecatalog:UpdateAttributeGroup"),
        rule("servicecatalog", "POST", "^/_appregistry/attribute-groups/?$", "servicecatalog:CreateAttributeGroup"),
        rule("servicecatalog", "GET", "^/_appregistry/attribute-groups/?$", "servicecatalog:ListAttributeGroups"),
        rule("servicecatalog", "DELETE", "^/_appregistry/applications/[^/]+/?$", "servicecatalog:DeleteApplication"),
        rule("servicecatalog", "GET", "^/_appregistry/applications/[^/]+/?$", "servicecatalog:GetApplication"),
        rule("servicecatalog", "GET", "^/_appregistry/configuration/?$", "servicecatalog:GetConfiguration"),
        rule("servicecatalog", "PUT", "^/_appregistry/configuration/?$", "servicecatalog:PutConfiguration"),
        rule("servicecatalog", "PATCH", "^/_appregistry/applications/[^/]+/?$", "servicecatalog:UpdateApplication"),
        rule("servicecatalog", "POST", "^/_appregistry/applications/?$", "servicecatalog:CreateApplication"),
        rule("servicecatalog", "GET", "^/_appregistry/applications/?$", "servicecatalog:ListApplications"),
        rule("servicecatalog", "POST", "^/_appregistry/sync/[^/]+/[^/]+/?$", "servicecatalog:SyncResource"),
        rule("auditmanager", "GET", "^/_auditmanager/assessments/[^/]+/controlSets/[^/]+/evidenceFolders/[^/]+/evidence/[^/]+/?$", "auditmanager:GetEvidence"),
        rule("auditmanager", "GET", "^/_auditmanager/assessments/[^/]+/evidenceFolders-by-assessment-control/[^/]+/[^/]+/?$", "auditmanager:GetEvidenceFoldersByAssessmentControl"),
        rule("auditmanager", "GET", "^/_auditmanager/assessments/[^/]+/controlSets/[^/]+/evidenceFolders/[^/]+/evidence/?$", "auditmanager:GetEvidenceByEvidenceFolder"),
        rule("auditmanager", "PUT", "^/_auditmanager/assessments/[^/]+/batchDisassociateFromAssessmentReport/?$", "auditmanager:BatchDisassociateAssessmentReportEvidence"),
        rule("auditmanager", "PUT", "^/_auditmanager/assessments/[^/]+/batchAssociateToAssessmentReport/?$", "auditmanager:BatchAssociateAssessmentReportEvidence"),
        rule("auditmanager", "PUT", "^/_auditmanager/assessments/[^/]+/disassociateFromAssessmentReport/?$", "auditmanager:DisassociateAssessmentReportEvidenceFolder"),
        rule("auditmanager", "POST", "^/_auditmanager/assessments/[^/]+/controlSets/[^/]+/controls/[^/]+/evidence/?$", "auditmanager:BatchImportEvidenceToAssessmentControl"),
        rule("auditmanager", "POST", "^/_auditmanager/account/deregisterOrganizationAdminAccount/?$", "auditmanager:DeregisterOrganizationAdminAccount"),
        rule("auditmanager", "GET", "^/_auditmanager/assessments/[^/]+/controlSets/[^/]+/evidenceFolders/[^/]+/?$", "auditmanager:GetEvidenceFolder"),
        rule("auditmanager", "POST", "^/_auditmanager/account/registerOrganizationAdminAccount/?$", "auditmanager:RegisterOrganizationAdminAccount"),
        rule("auditmanager", "PUT", "^/_auditmanager/assessments/[^/]+/associateToAssessmentReport/?$", "auditmanager:AssociateAssessmentReportEvidenceFolder"),
        rule("auditmanager", "GET", "^/_auditmanager/insights/control-domains-by-assessment/?$", "auditmanager:ListControlDomainInsightsByAssessment"),
        rule("auditmanager", "POST", "^/_auditmanager/assessmentFrameworks/[^/]+/shareRequests/?$", "auditmanager:StartAssessmentFrameworkShare"),
        rule("auditmanager", "PUT", "^/_auditmanager/assessments/[^/]+/controlSets/[^/]+/controls/[^/]+/?$", "auditmanager:UpdateAssessmentControl"),
        rule("auditmanager", "DELETE", "^/_auditmanager/assessmentFrameworkShareRequests/[^/]+/?$", "auditmanager:DeleteAssessmentFrameworkShare"),
        rule("auditmanager", "PUT", "^/_auditmanager/assessmentFrameworkShareRequests/[^/]+/?$", "auditmanager:UpdateAssessmentFrameworkShare"),
        rule("auditmanager", "GET", "^/_auditmanager/account/organizationAdminAccount/?$", "auditmanager:GetOrganizationAdminAccount"),
        rule("auditmanager", "GET", "^/_auditmanager/assessmentFrameworkShareRequests/?$", "auditmanager:ListAssessmentFrameworkShareRequests"),
        rule("auditmanager", "PUT", "^/_auditmanager/assessments/[^/]+/controlSets/[^/]+/status/?$", "auditmanager:UpdateAssessmentControlSetStatus"),
        rule("auditmanager", "GET", "^/_auditmanager/insights/controls-by-assessment/?$", "auditmanager:ListAssessmentControlInsightsByControlDomain"),
        rule("auditmanager", "GET", "^/_auditmanager/assessments/[^/]+/evidenceFolders/?$", "auditmanager:GetEvidenceFoldersByAssessment"),
        rule("auditmanager", "POST", "^/_auditmanager/assessmentReports/integrity/?$", "auditmanager:ValidateAssessmentReportIntegrity"),
        rule("auditmanager", "POST", "^/_auditmanager/account/deregisterAccount/?$", "auditmanager:DeregisterAccount"),
        rule("auditmanager", "GET", "^/_auditmanager/assessments/[^/]+/reports/[^/]+/url/?$", "auditmanager:GetAssessmentReportUrl"),
        rule("auditmanager", "POST", "^/_auditmanager/assessments/[^/]+/delegations/?$", "auditmanager:BatchCreateDelegationByAssessment"),
        rule("auditmanager", "PUT", "^/_auditmanager/assessments/[^/]+/delegations/?$", "auditmanager:BatchDeleteDelegationByAssessment"),
        rule("auditmanager", "GET", "^/_auditmanager/insights/control-domains/?$", "auditmanager:ListControlDomainInsights"),
        rule("auditmanager", "POST", "^/_auditmanager/account/registerAccount/?$", "auditmanager:RegisterAccount"),
        rule("auditmanager", "GET", "^/_auditmanager/assessments/[^/]+/changelogs/?$", "auditmanager:GetChangeLogs"),
        rule("auditmanager", "DELETE", "^/_auditmanager/assessmentFrameworks/[^/]+/?$", "auditmanager:DeleteAssessmentFramework"),
        rule("auditmanager", "GET", "^/_auditmanager/assessmentFrameworks/[^/]+/?$", "auditmanager:GetAssessmentFramework"),
        rule("auditmanager", "PUT", "^/_auditmanager/assessmentFrameworks/[^/]+/?$", "auditmanager:UpdateAssessmentFramework"),
        rule("auditmanager", "DELETE", "^/_auditmanager/assessments/[^/]+/reports/[^/]+/?$", "auditmanager:DeleteAssessmentReport"),
        rule("auditmanager", "GET", "^/_auditmanager/evidenceFileUploadUrl/?$", "auditmanager:GetEvidenceFileUploadUrl"),
        rule("auditmanager", "GET", "^/_auditmanager/insights/assessments/[^/]+/?$", "auditmanager:GetInsightsByAssessment"),
        rule("auditmanager", "GET", "^/_auditmanager/assessmentFrameworks/?$", "auditmanager:ListAssessmentFrameworks"),
        rule("auditmanager", "POST", "^/_auditmanager/assessmentFrameworks/?$", "auditmanager:CreateAssessmentFramework"),
        rule("auditmanager", "POST", "^/_auditmanager/assessments/[^/]+/reports/?$", "auditmanager:CreateAssessmentReport"),
        rule("auditmanager", "PUT", "^/_auditmanager/assessments/[^/]+/status/?$", "auditmanager:UpdateAssessmentStatus"),
        rule("auditmanager", "GET", "^/_auditmanager/dataSourceKeywords/?$", "auditmanager:ListKeywordsForDataSource"),
        rule("auditmanager", "GET", "^/_auditmanager/assessmentReports/?$", "auditmanager:ListAssessmentReports"),
        rule("auditmanager", "GET", "^/_auditmanager/insights/controls/?$", "auditmanager:ListControlInsightsByControlDomain"),
        rule("auditmanager", "GET", "^/_auditmanager/account/status/?$", "auditmanager:GetAccountStatus"),
        rule("auditmanager", "GET", "^/_auditmanager/notifications/?$", "auditmanager:ListNotifications"),
        rule("auditmanager", "DELETE", "^/_auditmanager/assessments/[^/]+/?$", "auditmanager:DeleteAssessment"),
        rule("auditmanager", "GET", "^/_auditmanager/assessments/[^/]+/?$", "auditmanager:GetAssessment"),
        rule("auditmanager", "PUT", "^/_auditmanager/assessments/[^/]+/?$", "auditmanager:UpdateAssessment"),
        rule("auditmanager", "GET", "^/_auditmanager/assessments/?$", "auditmanager:ListAssessments"),
        rule("auditmanager", "POST", "^/_auditmanager/assessments/?$", "auditmanager:CreateAssessment"),
        rule("auditmanager", "GET", "^/_auditmanager/delegations/?$", "auditmanager:GetDelegations"),
        rule("auditmanager", "DELETE", "^/_auditmanager/controls/[^/]+/?$", "auditmanager:DeleteControl"),
        rule("auditmanager", "GET", "^/_auditmanager/controls/[^/]+/?$", "auditmanager:GetControl"),
        rule("auditmanager", "PUT", "^/_auditmanager/controls/[^/]+/?$", "auditmanager:UpdateControl"),
        rule("auditmanager", "GET", "^/_auditmanager/settings/[^/]+/?$", "auditmanager:GetSettings"),
        rule("auditmanager", "GET", "^/_auditmanager/controls/?$", "auditmanager:ListControls"),
        rule("auditmanager", "POST", "^/_auditmanager/controls/?$", "auditmanager:CreateControl"),
        rule("auditmanager", "GET", "^/_auditmanager/insights/?$", "auditmanager:GetInsights"),
        rule("auditmanager", "GET", "^/_auditmanager/services/?$", "auditmanager:GetServicesInScope"),
        rule("auditmanager", "PUT", "^/_auditmanager/settings/?$", "auditmanager:UpdateSettings"),
        rule("backup", "GET", "^/backup-vaults/[^/]+/recovery-points/.+/index/?$", "backup:GetRecoveryPointIndexDetails"),
        rule("backup", "POST", "^/backup-vaults/[^/]+/recovery-points/.+/index/?$", "backup:UpdateRecoveryPointIndexSettings"),
        rule("backup-search", "PUT", "^/search-jobs/[^/]+/actions/cancel/?$", "backup:StopSearchJob"),
        rule("backup-search", "GET", "^/search-jobs/[^/]+/search-results/?$", "backup:ListSearchJobResults"),
        rule("backup", "GET", "^/indexes/recovery-point/?$", "backup:ListIndexedRecoveryPoints"),
        rule("backup-search", "GET", "^/search-jobs/[^/]+/backups/?$", "backup:ListSearchJobBackups"),
        rule("backup-search", "GET", "^/export-search-jobs/[^/]+/?$", "backup:GetSearchResultExportJob"),
        rule("backup-search", "GET", "^/export-search-jobs/?$", "backup:ListSearchResultExportJobs"),
        rule("backup-search", "PUT", "^/export-search-jobs/?$", "backup:StartSearchResultExportJob"),
        rule("backup-search", "GET", "^/search-jobs/[^/]+/?$", "backup:GetSearchJob"),
        rule("backup-search", "GET", "^/search-jobs/?$", "backup:ListSearchJobs"),
        rule("backup-search", "PUT", "^/search-jobs/?$", "backup:StartSearchJob"),
        rule("bedrock", "POST", "^/data-automation-libraries/.+/library-ingestion-jobs/.+/?$", "bedrock:GetDataAutomationLibraryIngestionJob"),
        rule("bedrock", "POST", "^/data-automation-libraries/.+/library-ingestion-jobs/?$", "bedrock:ListDataAutomationLibraryIngestionJobs"),
        rule("bedrock", "PUT", "^/data-automation-libraries/.+/library-ingestion-jobs/?$", "bedrock:InvokeDataAutomationLibraryIngestionJob"),
        rule("bedrock", "POST", "^/data-automation-libraries/.+/entityType/[^/]+/entities/?$", "bedrock:ListDataAutomationLibraryEntities"),
        rule("bedrock", "POST", "^/data-automation-libraries/.+/entityType/[^/]+/entities/[^/]+/?$", "bedrock:GetDataAutomationLibraryEntity"),
        rule("bedrock", "POST", "^/invokeBlueprintOptimizationAsync/?$", "bedrock:InvokeBlueprintOptimizationAsync"),
        rule("bedrock", "POST", "^/getBlueprintOptimizationStatus/.+/?$", "bedrock:GetBlueprintOptimizationStatus"),
        rule("bedrock", "DELETE", "^/data-automation-libraries/.+/?$", "bedrock:DeleteDataAutomationLibrary"),
        rule("bedrock", "POST", "^/data-automation-libraries/.+/?$", "bedrock:GetDataAutomationLibrary"),
        rule("bedrock", "PUT", "^/data-automation-libraries/.+/?$", "bedrock:UpdateDataAutomationLibrary"),
        rule("bedrock", "POST", "^/data-automation-libraries/?$", "bedrock:ListDataAutomationLibraries"),
        rule("bedrock", "PUT", "^/data-automation-libraries/?$", "bedrock:CreateDataAutomationLibrary"),
        rule("bedrock", "DELETE", "^/data-automation-projects/.+/?$", "bedrock:DeleteDataAutomationProject"),
        rule("bedrock", "POST", "^/data-automation-projects/.+/?$", "bedrock:GetDataAutomationProject"),
        rule("bedrock", "PUT", "^/data-automation-projects/.+/?$", "bedrock:UpdateDataAutomationProject"),
        rule("bedrock", "POST", "^/data-automation-projects/?$", "bedrock:ListDataAutomationProjects"),
        rule("bedrock", "PUT", "^/data-automation-projects/?$", "bedrock:CreateDataAutomationProject"),
        rule("bedrock", "PUT", "^/blueprints/.+/copy-stage/?$", "bedrock:CopyBlueprintStage"),
        rule("bedrock", "POST", "^/blueprints/.+/versions/?$", "bedrock:CreateBlueprintVersion"),
        rule("bedrock", "DELETE", "^/blueprints/.+/?$", "bedrock:DeleteBlueprint"),
        rule("bedrock", "POST", "^/blueprints/.+/?$", "bedrock:GetBlueprint"),
        rule("bedrock", "PUT", "^/blueprints/.+/?$", "bedrock:UpdateBlueprint"),
        rule("bedrock", "POST", "^/blueprints/?$", "bedrock:ListBlueprints"),
        rule("bedrock", "PUT", "^/blueprints/?$", "bedrock:CreateBlueprint"),
        rule("chatbot", "POST", "^/_chatbot/create-ms-teams-channel-configuration/?$", "chatbot:CreateMicrosoftTeamsChannelConfiguration"),
        rule("chatbot", "POST", "^/_chatbot/delete-ms-teams-channel-configuration/?$", "chatbot:DeleteMicrosoftTeamsChannelConfiguration"),
        rule("chatbot", "POST", "^/_chatbot/describe-chime-webhook-configurations/?$", "chatbot:DescribeChimeWebhookConfigurations"),
        rule("chatbot", "POST", "^/_chatbot/describe-slack-channel-configurations/?$", "chatbot:DescribeSlackChannelConfigurations"),
        rule("chatbot", "POST", "^/_chatbot/update-ms-teams-channel-configuration/?$", "chatbot:UpdateMicrosoftTeamsChannelConfiguration"),
        rule("chatbot", "POST", "^/_chatbot/delete-slack-workspace-authorization/?$", "chatbot:DeleteSlackWorkspaceAuthorization"),
        rule("chatbot", "POST", "^/_chatbot/list-ms-teams-channel-configurations/?$", "chatbot:ListMicrosoftTeamsChannelConfigurations"),
        rule("chatbot", "POST", "^/_chatbot/create-chime-webhook-configuration/?$", "chatbot:CreateChimeWebhookConfiguration"),
        rule("chatbot", "POST", "^/_chatbot/create-slack-channel-configuration/?$", "chatbot:CreateSlackChannelConfiguration"),
        rule("chatbot", "POST", "^/_chatbot/delete-chime-webhook-configuration/?$", "chatbot:DeleteChimeWebhookConfiguration"),
        rule("chatbot", "POST", "^/_chatbot/delete-slack-channel-configuration/?$", "chatbot:DeleteSlackChannelConfiguration"),
        rule("chatbot", "POST", "^/_chatbot/get-ms-teams-channel-configuration/?$", "chatbot:GetMicrosoftTeamsChannelConfiguration"),
        rule("chatbot", "POST", "^/_chatbot/update-chime-webhook-configuration/?$", "chatbot:UpdateChimeWebhookConfiguration"),
        rule("chatbot", "POST", "^/_chatbot/update-slack-channel-configuration/?$", "chatbot:UpdateSlackChannelConfiguration"),
        rule("chatbot", "POST", "^/_chatbot/delete-ms-teams-configured-teams/?$", "chatbot:DeleteMicrosoftTeamsConfiguredTeam"),
        rule("chatbot", "POST", "^/_chatbot/disassociate-from-configuration/?$", "chatbot:DisassociateFromConfiguration"),
        rule("chatbot", "POST", "^/_chatbot/describe-slack-user-identities/?$", "chatbot:DescribeSlackUserIdentities"),
        rule("chatbot", "POST", "^/_chatbot/list-ms-teams-configured-teams/?$", "chatbot:ListMicrosoftTeamsConfiguredTeams"),
        rule("chatbot", "POST", "^/_chatbot/delete-ms-teams-user-identity/?$", "chatbot:DeleteMicrosoftTeamsUserIdentity"),
        rule("chatbot", "POST", "^/_chatbot/list-ms-teams-user-identities/?$", "chatbot:ListMicrosoftTeamsUserIdentities"),
        rule("chatbot", "POST", "^/_chatbot/associate-to-configuration/?$", "chatbot:AssociateToConfiguration"),
        rule("chatbot", "POST", "^/_chatbot/delete-slack-user-identity/?$", "chatbot:DeleteSlackUserIdentity"),
        rule("chatbot", "POST", "^/_chatbot/update-account-preferences/?$", "chatbot:UpdateAccountPreferences"),
        rule("chatbot", "POST", "^/_chatbot/describe-slack-workspaces/?$", "chatbot:DescribeSlackWorkspaces"),
        rule("chatbot", "POST", "^/_chatbot/get-account-preferences/?$", "chatbot:GetAccountPreferences"),
        rule("chatbot", "POST", "^/_chatbot/list-tags-for-resource/?$", "chatbot:ListTagsForResource"),
        rule("chatbot", "POST", "^/_chatbot/create-custom-action/?$", "chatbot:CreateCustomAction"),
        rule("chatbot", "POST", "^/_chatbot/delete-custom-action/?$", "chatbot:DeleteCustomAction"),
        rule("chatbot", "POST", "^/_chatbot/update-custom-action/?$", "chatbot:UpdateCustomAction"),
        rule("chatbot", "POST", "^/_chatbot/list-custom-actions/?$", "chatbot:ListCustomActions"),
        rule("chatbot", "POST", "^/_chatbot/get-custom-action/?$", "chatbot:GetCustomAction"),
        rule("chatbot", "POST", "^/_chatbot/list-associations/?$", "chatbot:ListAssociations"),
        rule("chatbot", "POST", "^/_chatbot/untag-resource/?$", "chatbot:UntagResource"),
        rule("chatbot", "POST", "^/_chatbot/tag-resource/?$", "chatbot:TagResource"),
        rule("aiops", "POST", "^/investigationGroups/?$", "aiops:CreateInvestigationGroup"),
        rule("aiops", "GET", "^/investigationGroups/?$", "aiops:ListInvestigationGroups"),
        rule("aiops", "GET", "^/investigationGroups/[^/]+/policy/?$", "aiops:GetInvestigationGroupPolicy"),
        rule("aiops", "POST", "^/investigationGroups/[^/]+/policy/?$", "aiops:PutInvestigationGroupPolicy"),
        rule("aiops", "DELETE", "^/investigationGroups/[^/]+/policy/?$", "aiops:DeleteInvestigationGroupPolicy"),
        rule("aiops", "GET", "^/investigationGroups/[^/]+/?$", "aiops:GetInvestigationGroup"),
        rule("aiops", "PATCH", "^/investigationGroups/[^/]+/?$", "aiops:UpdateInvestigationGroup"),
        rule("aiops", "DELETE", "^/investigationGroups/[^/]+/?$", "aiops:DeleteInvestigationGroup"),
        rule("polly", "PUT", "^/v1/lexicons/[^/]+/?$", "polly:PutLexicon"),
        rule("polly", "GET", "^/v1/lexicons/[^/]+/?$", "polly:GetLexicon"),
        rule("polly", "DELETE", "^/v1/lexicons/[^/]+/?$", "polly:DeleteLexicon"),
        rule("polly", "GET", "^/v1/lexicons/?$", "polly:ListLexicons"),
        rule("polly", "GET", "^/v1/voices/?$", "polly:DescribeVoices"),
        rule("polly", "POST", "^/v1/speech/?$", "polly:SynthesizeSpeech"),
        rule("polly", "POST", "^/v1/synthesisTasks/?$", "polly:StartSpeechSynthesisTask"),
        rule("polly", "GET", "^/v1/synthesisTasks/[^/]+/?$", "polly:GetSpeechSynthesisTask"),
        rule("polly", "GET", "^/v1/synthesisTasks/?$", "polly:ListSpeechSynthesisTasks"),
        rule("polly", "POST", "^/v1/synthesisStream/?$", "polly:StartSpeechSynthesisStream"),
        rule("resource-groups", "POST", "^/_rg/groups/?$", "resource-groups:CreateGroup"),
        rule("resource-groups", "POST", "^/_rg/get-group/?$", "resource-groups:GetGroup"),
        rule("resource-groups", "POST", "^/_rg/delete-group/?$", "resource-groups:DeleteGroup"),
        rule("resource-groups", "POST", "^/_rg/group-resources/?$", "resource-groups:GroupResources"),
        rule("resource-groups", "POST", "^/_rg/list-group-resources/?$", "resource-groups:ListGroupResources"),
        rule("resource-groups", "POST", "^/_rg/update-group/?$", "resource-groups:UpdateGroup"),
        rule("resource-groups", "POST", "^/_rg/groups-list/?$", "resource-groups:ListGroups"),
        rule("resource-groups", "POST", "^/_rg/get-group-query/?$", "resource-groups:GetGroupQuery"),
        rule("resource-groups", "POST", "^/_rg/update-group-query/?$", "resource-groups:UpdateGroupQuery"),
        rule("resource-groups", "POST", "^/_rg/get-group-configuration/?$", "resource-groups:GetGroupConfiguration"),
        rule("resource-groups", "POST", "^/_rg/put-group-configuration/?$", "resource-groups:PutGroupConfiguration"),
        rule("resource-groups", "POST", "^/_rg/ungroup-resources/?$", "resource-groups:UngroupResources"),
        rule("resource-groups", "POST", "^/_rg/resources/search/?$", "resource-groups:SearchResources"),
        rule("resource-groups", "POST", "^/_rg/get-account-settings/?$", "resource-groups:GetAccountSettings"),
        rule("resource-groups", "POST", "^/_rg/update-account-settings/?$", "resource-groups:UpdateAccountSettings"),
        rule("resource-groups", "POST", "^/_rg/list-grouping-statuses/?$", "resource-groups:ListGroupingStatuses"),
        rule("resource-groups", "POST", "^/_rg/start-tag-sync-task/?$", "resource-groups:StartTagSyncTask"),
        rule("resource-groups", "POST", "^/_rg/get-tag-sync-task/?$", "resource-groups:GetTagSyncTask"),
        rule("resource-groups", "POST", "^/_rg/cancel-tag-sync-task/?$", "resource-groups:CancelTagSyncTask"),
        rule("resource-groups", "POST", "^/_rg/list-tag-sync-tasks/?$", "resource-groups:ListTagSyncTasks"),
        rule("resource-groups", "GET", "^/_rg/resources/.+/tags/?$", "resource-groups:GetTags"),
        rule("resource-groups", "PUT", "^/_rg/resources/.+/tags/?$", "resource-groups:Tag"),
        rule("resource-groups", "PATCH", "^/_rg/resources/.+/tags/?$", "resource-groups:Untag"),

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
        rule("inspector2", "POST", "^/cis/scan-configuration/create$", "inspector2:CreateCisScanConfiguration"),
        rule("inspector2", "POST", "^/cis/scan-configuration/update$", "inspector2:UpdateCisScanConfiguration"),
        rule("inspector2", "POST", "^/cis/scan-configuration/delete$", "inspector2:DeleteCisScanConfiguration"),
        rule("inspector2", "POST", "^/disable$", "inspector2:Disable"),
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

    private static ActionRule rule(String service, String method, String path, String action) {
        return rule(service, method, path, null, null, action);
    }

    private static ActionRule rule(String service, String method, String path, String queryParameter,
                                   String queryValue, String action) {
        return new ActionRule(service, method, Pattern.compile(path, Pattern.CASE_INSENSITIVE), queryParameter,
                queryValue, action, false);
    }

    private static ActionRule s3ControlRule(String method, String path, String action) {
        return new ActionRule("s3", method, Pattern.compile(path, Pattern.CASE_INSENSITIVE), null, null,
                action, true);
    }

    /**
     * Whether the request is one of the S3 Control routes, as the rules flag them. A denial then
     * answers in S3 Control's own REST-XML envelope, the only one the s3control SDK
     * deserializers read (issue #557), so the route decides the shape and not the action: the
     * data plane resolves actions a control route never shares, and an action-name list would
     * have to track every route to stay right. A request whose route cannot be read, which only
     * a synthetic context is, is not a control one.
     */
    public static boolean isS3ControlRequest(String credentialScope, ContainerRequestContext ctx) {
        if (!"s3".equals(credentialScope) || ctx.getMethod() == null || ctx.getUriInfo() == null
                || ctx.getUriInfo().getRequestUri() == null) {
            return false;
        }
        String method = ctx.getMethod().toUpperCase();
        String path = sentPath(ctx.getUriInfo());
        for (ActionRule rule : RULES) {
            if (rule.beforeS3QueryOverrides() && rule.matches(credentialScope, method, path, ctx)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the IAM action for an incoming request.
     *
     * For Query-protocol requests the action comes from {@code Action} or
     * {@code Operation} (e.g. {@code sqs:SendMessage}). REST requests never
     * read these caller-controlled fields as an IAM action.
     *
     * For JSON 1.1 protocol the action comes from {@code X-Amz-Target}
     * (e.g. {@code DynamoDB_20120810.PutItem} → {@code dynamodb:PutItem}).
     *
     * For REST-JSON services the action is derived from the path rule table, or else from the
     * operation whose botocore route the request matches.
     *
     * Returns {@code null} when the action is unknown (caller treats this as ALLOW).
     */
    public String resolve(String credentialScope, ContainerRequestContext ctx) {
        // REST requests with Action or Operation fields still use their method and path rules.
        if (isQueryRequest(ctx)) {
            String queryAction = queryAction(ctx);
            if (queryAction != null && !queryAction.isBlank()) {
                return IamAuthorizedActions.actionFor(credentialScope, queryAction);
            }
        }

        // JSON 1.1: X-Amz-Target → the action AWS checks for the operation it names
        String operationName = targetOperation(ctx);
        if (operationName != null) {
            return resolveOperation(credentialScope, operationName, ctx);
        }

        return resolveRoute(credentialScope, ctx);
    }

    /** The operation a JSON request's {@code X-Amz-Target} names, the part after its last dot; null without one. */
    public static String targetOperation(ContainerRequestContext ctx) {
        String target = ctx.getHeaderString("X-Amz-Target");
        return target != null && target.contains(".") ? target.substring(target.lastIndexOf('.') + 1) : null;
    }

    /** The action for a dispatched operation, including statement-dependent JSON and CBOR calls. */
    public String resolveOperation(String credentialScope, String operationName, ContainerRequestContext ctx) {
        if ("dynamodb".equals(credentialScope) && "ExecuteStatement".equals(operationName)) {
            String statement = RequestBodyReader.documentField(ctx, MAPPER, "Statement");
            String statementAction = DynamoDbPartiQLParser.iamAction(statement);
            if (statementAction != null) {
                return statementAction;
            }
        }
        return IamAuthorizedActions.actionFor(credentialScope, operationName);
    }

    /** The serving method identifies these actions even when path encoding or matrix parameters vary. */
    public String resolveMatchedRoute(String credentialScope, ResourceInfo resourceInfo) {
        if ("route53".equals(credentialScope) && resourceInfo != null
                && Route53Controller.class.equals(resourceInfo.getResourceClass())
                && resourceInfo.getResourceMethod() != null) {
            return ROUTE53_DELEGATION_ACTIONS.get(resourceInfo.getResourceMethod().getName());
        }
        return null;
    }

    /**
     * The action of a REST request, from its method and path alone. A REST request is the operation
     * whose route it reached, so neither an {@code X-Amz-Target} header nor a Query {@code Action}
     * field may name another one: a caller could attach either to have the call checked as an
     * action they hold. The path is matched as sent, still percent-encoded, which is the form the
     * router matched: decoded, an encoded {@code /} inside a parameter splits it into two segments,
     * so the route's rule would not match and the call would pass as an unknown action. It is read
     * from the request URI because RESTEasy Reactive does not serve {@code UriInfo.getPath(false)}.
     *
     * <p>A request no rule matches is the operation whose route, in botocore's model of its service, it
     * matches, and is checked as that operation's action ({@link IamAuthorizedActions#actionFor}), as a
     * request naming its operation is, when Floci can check it as AWS does ({@code checkedAsAws}). One it
     * cannot is left unmapped rather than checked on {@code *}, which would refuse a grant scoped to the
     * resource AWS checks it on. The scopes the vendored routes list as hand-routed, S3's and API Gateway's,
     * have their rules alone. Returns {@code null} when nothing maps the request (caller treats this as
     * ALLOW).
     */
    public String resolveRoute(String credentialScope, ContainerRequestContext ctx) {
        String method = ctx.getMethod().toUpperCase();
        // An S3 key may end in a slash, PUT /bucket// writes the key "/", and S3's rules already take a
        // bucket's one trailing slash, so an S3 path is matched as sent.
        String path = "s3".equals(credentialScope) ? sentPath(ctx.getUriInfo()) : routedPath(ctx);

        if ("s3".equals(credentialScope)) {
            // An S3 Control route is matched before the query overrides below, which would read the
            // query string of a route the control plane answers whatever it carries: ?tagging on a
            // tagging route is caller-supplied noise, not the object sub-resource it names.
            for (ActionRule rule : RULES) {
                if (rule.beforeS3QueryOverrides() && rule.matches(credentialScope, method, path, ctx)) {
                    return rule.action();
                }
            }
            // S3 sub-resource override: the URL path alone doesn't distinguish
            // s3:GetObjectAcl / s3:PutObjectAcl from s3:GetObject / s3:PutObject:
            // only the {@code ?acl} query parameter does. Without this hook,
            // an actor with s3:GetObject permission could read ACLs that their
            // policy intends to forbid.
            String s3SubAction = resolveS3SubResourceAction(method, ctx);
            if (s3SubAction != null) {
                return s3SubAction;
            }
        }

        for (ActionRule rule : RULES) {
            if (rule.matches(credentialScope, method, path, ctx)) {
                return rule.action();
            }
        }
        Optional<String> operation = restRoutes.operation(credentialScope, method, path,
                ctx.getUriInfo() == null ? null : ctx.getUriInfo().getQueryParameters());
        if (operation.isPresent()) {
            String action = IamAuthorizedActions.actionFor(credentialScope, operation.get());
            if (checkedAsAws(credentialScope, operation.get(), action)) {
                return action;
            }
        }

        LOG.debugv("No action mapping for {0} {1} {2} — defaulting to ALLOW", credentialScope, method, path);
        return null;
    }

    /** A REST model's explicit denial status, retaining the protocol default when none is modelled. */
    public static int accessDeniedStatus(String credentialScope, int fallback) {
        return IamRestRoutes.vendored().accessDeniedStatus(credentialScope, fallback);
    }

    /**
     * The value a REST request's path gives each label of the vendored route it matches, decoded, by the label's
     * name; empty when it matches none. {@link RequestResources} reads a member the table of request resources
     * locates in the path from them, as {@link #resolveRoute} matches the route.
     */
    public static Map<String, String> pathLabels(String credentialScope, ContainerRequestContext ctx) {
        IamRestRoutes routes = IamRestRoutes.vendored();
        if (credentialScope == null || !routes.routes(credentialScope)) {
            return Map.of();
        }
        return routes.match(credentialScope, ctx.getMethod().toUpperCase(), routedPath(ctx),
                        ctx.getUriInfo() == null ? null : ctx.getUriInfo().getQueryParameters())
                .map(IamRestRoutes.Match::labels).orElse(Map.of());
    }

    /**
     * Whether a request of the operation is checked on the resources AWS checks its action on: those its request
     * names, which the vendored request resources read for the operation ({@link RequestResources#reads}) or
     * {@code ResourceArnBuilder} builds for its namespace ({@link RequestResources#builtByHand}), or {@code *}
     * when AWS authorizes the action on no resource type ({@link IamAuthorizedActions#actsOnNoResourceType}), so
     * none of the resources in the request's path is needed.
     */
    private static boolean checkedAsAws(String credentialScope, String operation, String action) {
        return RequestResources.reads(credentialScope + ":" + operation)
                || RequestResources.builtByHand(credentialScope)
                || IamAuthorizedActions.actsOnNoResourceType(action);
    }

    /**
     * The path the router matched, relative to the application root. A request no filter rewrote is matched
     * on its normalized path, still percent-encoded but with encoded unreserved characters such as {@code %2E}
     * decoded and dot segments removed, so {@code /functions/x/%2E%2E/fn} is {@code /functions/fn}; a request
     * a filter rewrote is matched on its new path, decoded. {@link #sentPath} for a synthetic context.
     */
    static String rawPath(ContainerRequestContext ctx) {
        ResteasyReactiveRequestContext request = routingContext(ctx);
        return request == null ? sentPath(ctx.getUriInfo()) : rooted(request.getPathWithoutPrefix());
    }

    /**
     * The request path relative to the application root, as {@link UriInfo#getPath()} gives it, but
     * still percent-encoded.
     */
    static String sentPath(UriInfo uriInfo) {
        String path = uriInfo.getRequestUri().getRawPath();
        String base = uriInfo.getBaseUri().getRawPath();
        if (base != null && base.length() > 1 && path.startsWith(base)) {
            path = path.substring(base.length() - 1);
        }
        return rooted(path);
    }

    /**
     * {@link #rawPath} decoded: the path {@link UriInfo#getPath()} would give for the route the router
     * matched. {@link UriInfo#getPath()} itself for a synthetic context.
     */
    public static String decodedPath(ContainerRequestContext ctx) {
        ResteasyReactiveRequestContext request = routingContext(ctx);
        if (request == null) {
            return ctx.getUriInfo().getPath();
        }
        String path = request.getPathWithoutPrefix();
        boolean rewritten = !request.getPath().equals(request.serverRequest().getRequestNormalisedPath());
        return rooted(rewritten ? path : URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8));
    }

    private static ResteasyReactiveRequestContext routingContext(ContainerRequestContext ctx) {
        return ctx instanceof ResteasyReactiveContainerRequestContext container
                && container.getServerRequestContext() instanceof ResteasyReactiveRequestContext request
                ? request : null;
    }

    private static String rooted(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * {@link #rawPath} without a trailing slash: the router matches {@code /clusters/} as {@code /clusters},
     * so a rule ending at the route's last segment has to match it too, or the request would pass as an
     * unknown action.
     */
    static String routedPath(ContainerRequestContext ctx) {
        return withoutTrailingSlash(rawPath(ctx));
    }

    /** The path without its trailing slashes, the root's own kept. */
    static String withoutTrailingSlash(String path) {
        String trimmed = path;
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Returns the Query action from GET query parameters or the POST form body, preserving the
     * entity stream. Both use the controller's Action-first, Operation-second rule; a POST URL
     * Action must not override the form operation that will execute.
     */
    public String queryAction(ContainerRequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.getMethod())) {
            return AwsQueryServiceResolver.action(ctx.getUriInfo().getQueryParameters());
        }
        return AwsQueryServiceResolver.action(
                RequestBodyReader.formField(ctx, "Action"),
                RequestBodyReader.formField(ctx, "Operation"));
    }

    /** Returns the API version from the same Query input that the controller dispatches. */
    public String queryVersion(ContainerRequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.getMethod())) {
            return ctx.getUriInfo().getQueryParameters().getFirst("Version");
        }
        return RequestBodyReader.formField(ctx, "Version");
    }

    /** The root form POST or CloudWatch GET that the Query controller dispatches. */
    public static boolean isQueryRequest(ContainerRequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.getMethod())) {
            return ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim
                    && claim.protocol() == WireProtocol.AWS_QUERY;
        }
        if (ctx.getUriInfo() == null) {
            return false;
        }
        String path = ctx.getUriInfo().getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            return false;
        }
        MediaType mediaType = ctx.getMediaType();
        return "POST".equalsIgnoreCase(ctx.getMethod())
                && mediaType != null
                && "application".equalsIgnoreCase(mediaType.getType())
                && "x-www-form-urlencoded".equalsIgnoreCase(mediaType.getSubtype());
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
            sub("policyStatus",      "s3:GetBucketPolicyStatus"),
            sub("cors",              "s3:GetBucketCORS"),
            sub("lifecycle",         "s3:GetLifecycleConfiguration"),
            sub("acl",               "s3:GetBucketAcl"),
            sub("encryption",        "s3:GetEncryptionConfiguration"),
            sub("publicAccessBlock", "s3:GetBucketPublicAccessBlock"),
            sub("ownershipControls", "s3:GetBucketOwnershipControls"),
            sub("requestPayment",    "s3:GetBucketRequestPayment"),
            sub("accelerate",        "s3:GetAccelerateConfiguration"),
            sub("replication",       "s3:GetReplicationConfiguration"),
            sub("metrics",           "s3:GetMetricsConfiguration"),
            sub("intelligent-tiering", "s3:GetIntelligentTieringConfiguration"),
            sub("analytics",         "s3:GetAnalyticsConfiguration"),
            sub("inventory",         "s3:GetInventoryConfiguration"));

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
            sub("metrics",           "s3:PutMetricsConfiguration"),
            sub("intelligent-tiering", "s3:PutIntelligentTieringConfiguration"),
            sub("analytics",         "s3:PutAnalyticsConfiguration"),
            sub("inventory",         "s3:PutInventoryConfiguration"));

    // AWS gives only DeleteBucketPolicy and DeleteBucketWebsite their own action; removing any other
    // sub-resource is authorised by the same Put* action that sets it. ?accelerate is absent because
    // S3Controller rejects DELETE on it with 405, so no mapping should claim the request.
    private static final List<SubResourceAction> DELETE_BUCKET_SUBRESOURCES = List.of(
            sub("tagging",           "s3:PutBucketTagging"),
            sub("website",           "s3:DeleteBucketWebsite"),
            sub("policy",            "s3:DeleteBucketPolicy"),
            sub("cors",              "s3:PutBucketCORS"),
            sub("lifecycle",         "s3:PutLifecycleConfiguration"),
            sub("encryption",        "s3:PutEncryptionConfiguration"),
            sub("publicAccessBlock", "s3:PutBucketPublicAccessBlock"),
            sub("ownershipControls", "s3:PutBucketOwnershipControls"),
            sub("replication",       "s3:PutReplicationConfiguration"),
            sub("metrics",           "s3:PutMetricsConfiguration"),
            sub("intelligent-tiering", "s3:PutIntelligentTieringConfiguration"),
            sub("analytics",         "s3:PutAnalyticsConfiguration"),
            sub("inventory",         "s3:PutInventoryConfiguration"));

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
     * The operation an object sub-resource selects, checked in the order S3Controller dispatches
     * them: a request naming several runs the first, so it is checked as that one. A
     * {@code versionId} selects the version action AWS checks in place of an action that has one
     * (see {@link S3ObjectActions#forVersion}). Null where the controller falls through to the plain
     * object operation on the current version, which the rules check.
     */
    private static String objectSubResourceAction(String method, MultivaluedMap<String, String> params) {
        String versionId = params.getFirst("versionId");
        return switch (method) {
            case "GET" -> {
                if (params.containsKey("uploadId")) {
                    yield "s3:ListMultipartUploadParts";
                }
                if (params.containsKey("tagging")) {
                    yield S3ObjectActions.forVersion("s3:GetObjectTagging", versionId);
                }
                if (params.containsKey("annotation")) {
                    yield S3ObjectActions.forVersion(annotationReadAction(params), versionId);
                }
                if (params.containsKey("retention")) {
                    yield "s3:GetObjectRetention";
                }
                if (params.containsKey("legal-hold")) {
                    yield "s3:GetObjectLegalHold";
                }
                if (params.containsKey("acl")) {
                    yield S3ObjectActions.forVersion("s3:GetObjectAcl", versionId);
                }
                yield versionId != null ? "s3:GetObjectVersion" : null;
            }
            case "HEAD" -> {
                if (params.containsKey("annotation")) {
                    yield S3ObjectActions.forVersion(annotationReadAction(params), versionId);
                }
                yield versionId != null ? "s3:GetObjectVersion" : null;
            }
            case "PUT" -> {
                if (params.containsKey("tagging")) {
                    yield S3ObjectActions.forVersion("s3:PutObjectTagging", versionId);
                }
                if (params.containsKey("retention")) {
                    yield "s3:PutObjectRetention";
                }
                if (params.containsKey("legal-hold")) {
                    yield "s3:PutObjectLegalHold";
                }
                if (params.containsKey("acl")) {
                    yield S3ObjectActions.forVersion("s3:PutObjectAcl", versionId);
                }
                // A PUT naming an uploadId and a partNumber is UploadPart, which the rules check as
                // s3:PutObject, as they do PutObject and CopyObject.
                yield params.containsKey("annotation")
                        ? S3ObjectActions.forVersion("s3:PutObjectAnnotation", versionId)
                        : null;
            }
            case "DELETE" -> {
                if (params.containsKey("tagging")) {
                    yield S3ObjectActions.forVersion("s3:DeleteObjectTagging", versionId);
                }
                if (params.containsKey("annotation")) {
                    yield S3ObjectActions.forVersion("s3:DeleteObjectAnnotation", versionId);
                }
                if (params.containsKey("uploadId")) {
                    yield "s3:AbortMultipartUpload";
                }
                yield versionId != null ? "s3:DeleteObjectVersion" : null;
            }
            case "POST" -> {
                if (params.containsKey("uploads")) {
                    yield "s3:PutObject";
                }
                if (params.containsKey("restore")) {
                    yield "s3:RestoreObject";
                }
                if (params.containsKey("select")) {
                    // S3Controller selects from the version a versionId names.
                    yield S3ObjectActions.forVersion("s3:GetObject", versionId);
                }
                yield params.containsKey("uploadId") ? "s3:PutObject" : null;
            }
            default -> null;
        };
    }

    /** A named annotation is GetObjectAnnotation; an empty or absent name lists them, as S3Controller does. */
    private static String annotationReadAction(MultivaluedMap<String, String> params) {
        String name = params.getFirst("annotationName");
        return name != null && !name.isEmpty() ? "s3:GetObjectAnnotation" : "s3:ListObjectAnnotations";
    }

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

    /**
     * Every Secrets Manager operation: requests name a secret (or {@code *}) that
     * {@link ResourceArnBuilder} resolves to the stored secret's ARN, so a least-privilege
     * execution role is denied the operations its policy does not grant, as on AWS.
     */
    private static final String ROLE_ENFORCED_SECRETS_MANAGER_PREFIX = "secretsmanager:";

    /**
     * {@code true} when {@code action} is one of the few operations evaluated
     * for assumed-role callers while global enforcement is off.
     */
    public boolean isRoleEnforcedAction(String action) {
        return action != null && (ROLE_ENFORCED_ACTIONS.contains(action)
                || action.startsWith(ROLE_ENFORCED_SECRETS_MANAGER_PREFIX)
                || RULES.stream().anyMatch(rule -> Set.of("emr-serverless", "guardduty", "inspector2")
                        .contains(rule.service()) && rule.action().equals(action)));
    }

}
