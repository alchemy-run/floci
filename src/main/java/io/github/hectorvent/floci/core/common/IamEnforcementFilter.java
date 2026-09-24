package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.github.hectorvent.floci.services.iam.IamActionRegistry;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourceAccountRelationship;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ResourceArnBuilder;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JAX-RS filter that enforces IAM policies when
 * {@code floci.services.iam.enforcement-enabled = true}
 * ({@code FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true} in the environment),
 * or for the assumed-role actions described below.
 *
 * <p>Bypass rules (request is always allowed through):
 * <ul>
 *   <li>Access key is {@code "test"} (root/admin stand-in)</li>
 *   <li>Access key is not found in the IAM store (backward-compatible with pre-existing credentials)</li>
 *   <li>The action cannot be resolved (unknown mapping → permissive)</li>
 *   <li>Global enforcement is off <em>and</em> the caller is not an assumed-role
 *       session calling an action in {@link IamActionRegistry#isRoleEnforcedAction}</li>
 *   <li>The action is {@code sts:GetCallerIdentity}, which AWS allows without permissions</li>
 * </ul>
 *
 * <p>Global {@code floci.services.iam.enforcement-enabled} stays off by default.
 * Assumed-role sessions (Lambda execution-role credentials) are still evaluated
 * for the explicitly modeled SES, KMS, EMR Serverless, GuardDuty, and Inspector2 actions
 * so scoped-IAM denial tests can observe {@code AccessDenied} without turning on
 * evaluation for every JSON 1.1 / Query operation.
 *
 * <p>Evaluates the caller's identity policies, optional session policy, and optional
 * permissions boundary, as well as applicable resource policies via {@link ResourcePolicyProvider}.
 *
 * <p>Reads the signing credential from either the {@code Authorization} header or, for a
 * presigned URL, the {@code X-Amz-Credential} query parameter - both request shapes get the
 * same policy evaluation. A presigned POST form carries its credential in the multipart body,
 * which is unavailable at this JAX-RS filter stage; that shape is authorized separately via
 * {@link #authorizeAdditionalResource} once {@code S3Controller} has parsed the form fields.
 */
@Provider
@ApplicationScoped
public class IamEnforcementFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(IamEnforcementFilter.class);

    /**
     * SigV4 credential scope: {@code KEY/YYYYMMDD/REGION/SERVICE/aws4_request}.
     * Captures SERVICE.
     */
    private static final Pattern SIGV4_SCOPE =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/aws4_request");

    /**
     * SigV4a (asymmetric) credential scope: {@code KEY/YYYYMMDD/SERVICE/aws4_request}
     * — no region. SES v2 and a few other SDKs sign this way.
     */
    private static final Pattern SIGV4A_SCOPE =
            Pattern.compile("Credential=\\S+/\\d{8}/([^/]+)/aws4_request");

    /**
     * Implicit identity policy for the account-root principal: full access, bounded only by SCPs.
     * The account root is not a registered IAM identity, so it has no stored identity policy.
     */
    private static final String ROOT_ALLOW_ALL =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}]}";

    private final EmulatorConfig config;
    private final AccountResolver accountResolver;
    private final IamService iamService;
    private final IamPolicyEvaluator evaluator;
    private final IamActionRegistry actionRegistry;
    private final ResourceArnBuilder arnBuilder;
    private final RequestContext requestContext;
    private final IamConditionContextResolver conditionContextResolver;
    private final CloudTrailService cloudTrailService;
    private final CurrentVertxRequest currentVertxRequest;
    private final ResolvedServiceCatalog catalog;
    private final Instance<ScpProvider> scpProvider;
    private final SessionAccountLookup sessionAccountLookup;
    private final Instance<ResourcePolicyProvider> resourcePolicyProviders;

    @Inject
    public IamEnforcementFilter(EmulatorConfig config,
                                AccountResolver accountResolver,
                                IamService iamService,
                                IamPolicyEvaluator evaluator,
                                IamActionRegistry actionRegistry,
                                ResourceArnBuilder arnBuilder,
                                RequestContext requestContext,
                                IamConditionContextResolver conditionContextResolver,
                                CloudTrailService cloudTrailService,
                                CurrentVertxRequest currentVertxRequest,
                                ResolvedServiceCatalog catalog,
                                Instance<ScpProvider> scpProvider,
                                SessionAccountLookup sessionAccountLookup,
                                Instance<ResourcePolicyProvider> resourcePolicyProviders) {
        this.config = config;
        this.accountResolver = accountResolver;
        this.iamService = iamService;
        this.evaluator = evaluator;
        this.actionRegistry = actionRegistry;
        this.arnBuilder = arnBuilder;
        this.requestContext = requestContext;
        this.conditionContextResolver = conditionContextResolver;
        this.cloudTrailService = cloudTrailService;
        this.currentVertxRequest = currentVertxRequest;
        this.catalog = catalog;
        this.scpProvider = scpProvider;
        this.sessionAccountLookup = sessionAccountLookup;
        this.resourcePolicyProviders = resourcePolicyProviders;
    }

    /** Package-private constructor for callers predating resourcePolicyProviders. */
    IamEnforcementFilter(EmulatorConfig config,
                         AccountResolver accountResolver,
                         IamService iamService,
                         IamPolicyEvaluator evaluator,
                         IamActionRegistry actionRegistry,
                         ResourceArnBuilder arnBuilder,
                         RequestContext requestContext,
                         IamConditionContextResolver conditionContextResolver,
                         CloudTrailService cloudTrailService,
                         CurrentVertxRequest currentVertxRequest,
                         ResolvedServiceCatalog catalog,
                         Instance<ScpProvider> scpProvider,
                         SessionAccountLookup sessionAccountLookup) {
        this(config, accountResolver, iamService, evaluator, actionRegistry, arnBuilder,
                requestContext, conditionContextResolver, cloudTrailService, currentVertxRequest,
                catalog, scpProvider, sessionAccountLookup, null);
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String auth = ctx.getHeaderString("Authorization");
        if (auth == null) {
            auth = presignedCredentialAsAuthorization(ctx);
        }
        if (auth == null) {
            return;
        }

        String akid = accountResolver.extractAccessKeyId(auth);
        if (akid == null || "test".equals(akid)) {
            return; // root bypass
        }

        String rawScope = extractCredentialScope(auth);
        if (rawScope == null) {
            LOG.infov("IAM skip: no credential scope akid={0} auth={1}", akid, abbreviateAuth(auth));
            return;
        }
        // Normalise signing aliases (s3express → s3) before anything keyed by scope runs:
        // action rules, ARN building and condition keys all match the canonical name, so an
        // alias would resolve to no action and be allowed through without any policy check.
        String credentialScope = catalog.canonicalCredentialScope(rawScope);

        String action = actionRegistry.resolve(credentialScope, ctx);
        if (action == null) {
            LOG.debugv("IAM skip: unmapped action scope={0} {1} {2} akid={3}",
                    credentialScope, ctx.getMethod(), ctx.getUriInfo().getPath(), akid);
            return; // unknown action → ALLOW (permissive)
        }
        if ("sts:GetCallerIdentity".equals(action)) {
            return; // AWS returns caller identity even when an identity policy explicitly denies it
        }

        boolean roleSession = iamService.isAssumedRoleSession(akid);
        boolean roleEnforced = actionRegistry.isRoleEnforcedAction(action);
        // Global enforcement stays off by default. The Lambda execution-role path
        // still evaluates, but only for explicitly role-enforced actions — JSON 1.1
        // and Query auto-resolve every operation, and evaluating those would deny
        // suites whose resource ARNs / condition keys we do not model.
        if (!config.services().iam().enforcementEnabled() && !(roleSession && roleEnforced)) {
            LOG.debugv("IAM skip: global off roleSession={0} enforced={1} action={2} akid={3}",
                    roleSession, roleEnforced, action, akid);
            return;
        }

        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        String accountId = requestContext.getAccountId() == null
                ? accountResolver.resolve(auth)
                : requestContext.getAccountId();

        // Service control policies from the caller's organization, when the Organizations
        // service is present and SCP enforcement is enabled. Resolved lazily via Instance
        // to avoid a hard IAM → Organizations dependency.
        //
        // Resolved before resolveCallerContext because the account-root branch below needs to
        // know whether a ceiling exists in order to decide between enforcing and bypassing. That
        // costs an organization lookup on requests that then bypass; both flags are opt-in, and
        // effectiveScpLevels returns null immediately when SCP enforcement is off.
        List<List<String>> scpLevels = scpProvider.isResolvable()
                ? scpProvider.get().effectiveScpLevels(accountId)
                : null;

        boolean accountRootPrincipal = false;
        CallerContext caller = iamService.resolveCallerContext(akid);
        if (caller == null) {
            // A bare 12-digit account-id key is floci's account-root principal: not a registered
            // IAM identity (resolveCallerContext → null), but in AWS the account root is still
            // bounded by SCPs. Enforce them when the account actually has an SCP ceiling; otherwise
            // preserve the historical unknown-key bypass.
            if (scpLevels == null || !akid.equals(accountId)) {
                return; // unknown access key or no SCP ceiling → bypass (backward-compat)
            }
            caller = CallerContext.of(List.of(ROOT_ALLOW_ALL));
            accountRootPrincipal = true;
        }
        if (scpLevels != null) {
            caller = caller.withScpLevels(scpLevels);
        }

        List<String> resources = arnBuilder.buildResources(credentialScope, ctx, region, accountId);

        Map<String, List<String>> conditionContext = conditionContextResolver.resolve(credentialScope, action, ctx);
        // A request naming several resources is authorized once per resource, as on AWS, so a
        // permitted first target cannot carry later targets that the policy does not allow.
        List<Map<String, List<String>>> remainingTargets =
                conditionContextResolver.resolveRemainingTargets(credentialScope, action, ctx);

        // aws:PrincipalArn is populated for every principal this filter can identify — IAM users,
        // assumed-role sessions, and now the synthesized account-root principal above, using AWS's
        // own root ARN shape (arn:aws:iam::<account>:root). Real AWS populates this key for the
        // root user, so a DenyRootUser guardrail keyed on it must fire against floci's account-root
        // stand-in the same way it enforces SCPs against it (the account-root SCP change above);
        // leaving it absent here would have made the two forms of root enforcement inconsistent.
        Optional<String> principalArn = accountRootPrincipal
                ? Optional.of("arn:aws:iam::" + accountId + ":root")
                : iamService.resolveCallerArn(akid);
        if (principalArn.isPresent()) {
            caller = caller.withPrincipalArn(principalArn.get());
            conditionContext = conditionContext == null ? new HashMap<>() : new HashMap<>(conditionContext);
            conditionContext.put("aws:PrincipalArn", List.of(principalArn.get()));
        }
        List<Map<String, List<String>>> targetContexts = new ArrayList<>();
        targetContexts.add(conditionContext);
        for (Map<String, List<String>> target : remainingTargets) {
            Map<String, List<String>> targetContext = new HashMap<>(target);
            principalArn.ifPresent(arn -> targetContext.put("aws:PrincipalArn", List.of(arn)));
            targetContexts.add(targetContext);
        }

        if (abortIfDenied(ctx, caller, action, credentialScope, resources, targetContexts,
                region, accountId, akid)) {
            return;
        }

        if ("emr-serverless:StartJobRun".equals(action) || "emr-serverless:StartSession".equals(action)) {
            String executionRoleArn = arnBuilder.buildExecutionRoleArn(ctx);
            if (executionRoleArn != null) {
                Map<String, List<String>> passRoleContext = new HashMap<>();
                principalArn.ifPresent(arn -> passRoleContext.put("aws:PrincipalArn", List.of(arn)));
                passRoleContext.put("iam:PassedToService", List.of("emr-serverless.amazonaws.com"));
                if (abortIfDenied(ctx, caller, "iam:PassRole", credentialScope, List.of(executionRoleArn),
                        List.of(passRoleContext), region, accountId, akid)) {
                    return;
                }
            }
        }

        // A PutObject carrying If-Match compares against the object it replaces, and S3 authorizes
        // that read as s3:GetObject, WITHOUT the object's tags in the request context. Measured
        // against real AWS: under a GetObject allow conditioned on s3:ExistingObjectTag the
        // conditional write is AccessDenied, under a GetObject allow scoped by prefix alone it
        // succeeds, and with no GetObject at all it is denied. If-None-Match needs no such
        // permission.
        if ("s3:PutObject".equals(action) && ctx.getHeaderString("If-Match") != null) {
            abortIfDenied(ctx, caller, "s3:GetObject", credentialScope, resources,
                    withoutObjectTags(targetContexts), region, accountId, akid);
        }
    }

    /**
     * Evaluates one action against every resource and target context, aborting the request with
     * AccessDenied on the first DENY. Returns true when the request was aborted.
     */
    private boolean abortIfDenied(ContainerRequestContext ctx, CallerContext caller, String action,
                                  String credentialScope, List<String> resources,
                                  List<Map<String, List<String>>> targetContexts,
                                  String region, String accountId, String akid) {
        for (String resource : resources) {
            List<ResourcePolicyProvider.ResourcePolicy> resourcePolicies = resolveResourcePolicies(credentialScope, resource);
            String resourceOwnerAccountId = resourcePolicies.isEmpty() ? null : resourcePolicies.getFirst().ownerAccountId();
            List<String> policyDocs = resourcePolicies.stream()
                    .map(ResourcePolicyProvider.ResourcePolicy::policyDocument)
                    .filter(doc -> doc != null && !doc.isBlank())
                    .toList();
            List<String> effectiveResourcePolicies = policyDocs.isEmpty() ? null : policyDocs;

            ResourceAccountRelationship accountRelationship = resourceOwnerAccountId == null
                    || accountId.equals(resourceOwnerAccountId)
                    ? ResourceAccountRelationship.SAME_ACCOUNT
                    : ResourceAccountRelationship.CROSS_ACCOUNT;

            for (Map<String, List<String>> targetContext : targetContexts) {
                Map<String, List<String>> effectiveContext = IamConditionContextResolver.withGlobalContext(
                        targetContext, resource, region, accountId, resourceOwnerAccountId);
                ResourcePolicyDecision resourcePolicyDecision = evaluator.evaluateResourcePolicy(
                        effectiveResourcePolicies, caller.principalArn(), action, resource, effectiveContext);
                Decision decision = evaluator.evaluateResolvedResourcePolicy(
                        caller, resourcePolicyDecision, accountRelationship, action, resource, effectiveContext);
                if (decision != Decision.DENY) {
                    continue;
                }
                LOG.infov("IAM enforcement DENY: akid={0} action={1} resource={2}", akid, action, resource);
                String denyMessage = "User: arn:aws:iam::" + accountId
                        + ":user/" + akid + " is not authorized to perform: " + action
                        + " on resource: \"" + resource + "\""
                        + " because no identity-based policy allows the " + action + " action";
                emitS3DenialIfApplicable(akid, action, resource, ctx, region, denyMessage);
                ctx.abortWith(accessDeniedResponse(action, credentialScope, ctx.getMediaType(), resource));
                return true;
            }
        }
        return false;
    }

    /** The same contexts with every object-tag key removed, keeping the principal and global keys. */
    private static List<Map<String, List<String>>> withoutObjectTags(
            List<Map<String, List<String>>> targetContexts) {
        List<Map<String, List<String>>> stripped = new ArrayList<>();
        for (Map<String, List<String>> targetContext : targetContexts) {
            if (targetContext == null) {
                stripped.add(null);
                continue;
            }
            Map<String, List<String>> copy = new HashMap<>(targetContext);
            copy.keySet().removeIf(key ->
                    key.startsWith(IamConditionContextResolver.EXISTING_OBJECT_TAG_PREFIX)
                            || key.startsWith(IamConditionContextResolver.REQUEST_OBJECT_TAG_PREFIX));
            stripped.add(copy.isEmpty() ? null : copy);
        }
        return stripped;
    }

    /**
     * Authorizes a single (action, resource) pair for the caller identified by an
     * Authorization header, following the same identity resolution and bypass rules
     * as {@link #filter}. Callers use this for a secondary resource that never appears
     * in the request URL and so is invisible to {@link ResourceArnBuilder} - such as
     * the CopyObject/UploadPartCopy source object, which arrives only in the
     * {@code x-amz-copy-source} header.
     *
     * <p>Returns normally when the action is allowed, or when enforcement does not
     * apply to this request (enforcement disabled, no Authorization header, root or
     * unknown access key). Throws {@link AwsException} with the same AccessDenied
     * shape as {@link #filter} when the caller's policies deny the action.
     *
     * <p>The account used for policy evaluation is always re-resolved from {@code akid} here
     * (see {@link #resolveCredentialAccountId}) rather than trusted from {@link RequestContext},
     * because a presigned POST's credential is invisible to {@code AccountContextFilter} - it
     * arrives only in the multipart body, parsed well after that filter already set the ambient
     * account to the configured default. The resolved account is pushed onto {@link RequestContext}
     * for the duration of this call so that {@link IamService#resolveCallerContext} and
     * {@link IamService#resolveCallerArn}, which both key their per-account lookups off the
     * ambient account, resolve the credential's actual owner instead of the default account.
     */
    public void authorizeAdditionalResource(String authorizationHeader, String action, String resource) {
        authorizeAdditionalResource(
                authorizationHeader, action, resource, ResourcePolicyDecision.NEUTRAL, null);
    }

    /**
     * Authorizes a secondary resource using an already principal-filtered resource-policy
     * decision. This preserves explicit-deny precedence while allowing either the identity or
     * resource policy to provide the base grant.
     */
    public void authorizeAdditionalResource(
            String authorizationHeader,
            String action,
            String resource,
            ResourcePolicyDecision resourcePolicyDecision) {
        authorizeAdditionalResource(
                authorizationHeader, action, resource, resourcePolicyDecision, null);
    }

    /**
     * Authorizes a secondary resource whose owning account is known. Resource-policy grants
     * crossing an account boundary require a matching identity-policy grant as well.
     */
    public void authorizeAdditionalResource(
            String authorizationHeader,
            String action,
            String resource,
            ResourcePolicyDecision resourcePolicyDecision,
            String resourceOwnerAccountId) {
        authorizeAdditionalResource(authorizationHeader, action, resource, resourcePolicyDecision,
                resourceOwnerAccountId, false);
    }

    /**
     * Like {@link #authorizeAdditionalResource(String, String, String, ResourcePolicyDecision, String)},
     * but also evaluates assumed-role sessions (Lambda execution roles) while global enforcement is
     * off - the caller class {@link #filter} evaluates for role-enforced actions. Used for the
     * secondary S3 permissions a request needs besides its own action: reading a copy source, and
     * the {@code s3:ListBucket} that decides whether a missing object may be reported as missing.
     */
    public void authorizeAdditionalResourceForSession(
            String authorizationHeader,
            String action,
            String resource,
            ResourcePolicyDecision resourcePolicyDecision,
            String resourceOwnerAccountId) {
        authorizeAdditionalResource(authorizationHeader, action, resource, resourcePolicyDecision,
                resourceOwnerAccountId, true);
    }

    private void authorizeAdditionalResource(
            String authorizationHeader,
            String action,
            String resource,
            ResourcePolicyDecision resourcePolicyDecision,
            String resourceOwnerAccountId,
            boolean includeRoleSessions) {
        if (authorizationHeader == null) {
            return;
        }
        if (!config.services().iam().enforcementEnabled()) {
            String sessionKey = includeRoleSessions ? accountResolver.extractAccessKeyId(authorizationHeader) : null;
            if (sessionKey == null || "test".equals(sessionKey) || !iamService.isAssumedRoleSession(sessionKey)) {
                return;
            }
        }
        String akid = accountResolver.extractAccessKeyId(authorizationHeader);
        if (akid == null || "test".equals(akid)) {
            return;
        }
        if (extractCredentialScope(authorizationHeader) == null) {
            return;
        }

        String accountId = resolveCredentialAccountId(akid, authorizationHeader);
        String previousAccountId = requestContext.getAccountId();
        requestContext.setAccountId(accountId);
        try {
            List<List<String>> scpLevels = scpProvider.isResolvable()
                    ? scpProvider.get().effectiveScpLevels(accountId) : null;

            boolean accountRootPrincipal = false;
            CallerContext caller = iamService.resolveCallerContext(akid);
            if (caller == null) {
                if (scpLevels == null || !akid.equals(accountId)) {
                    return;
                }
                caller = CallerContext.of(List.of(ROOT_ALLOW_ALL));
                accountRootPrincipal = true;
            }
            if (scpLevels != null) {
                caller = caller.withScpLevels(scpLevels);
            }

            Map<String, List<String>> conditionContext = null;
            Optional<String> principalArn = accountRootPrincipal
                    ? Optional.of("arn:aws:iam::" + accountId + ":root")
                    : iamService.resolveCallerArn(akid);
            if (principalArn.isPresent()) {
                caller = caller.withPrincipalArn(principalArn.get());
                conditionContext = new HashMap<>();
                conditionContext.put("aws:PrincipalArn", List.of(principalArn.get()));
            }

            ResourceAccountRelationship accountRelationship = resourceOwnerAccountId == null
                    || accountId.equals(resourceOwnerAccountId)
                    ? ResourceAccountRelationship.SAME_ACCOUNT
                    : ResourceAccountRelationship.CROSS_ACCOUNT;
            String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
            conditionContext = IamConditionContextResolver.withGlobalContext(
                    conditionContext, resource, region, accountId, resourceOwnerAccountId);
            Decision decision = evaluator.evaluateResolvedResourcePolicy(
                    caller, resourcePolicyDecision, accountRelationship,
                    action, resource, conditionContext);
            if (decision != Decision.DENY) {
                return;
            }
            LOG.infov("IAM enforcement DENY: akid={0} action={1} resource={2}", akid, action, resource);
            throw new AwsException("AccessDenied",
                    "User: arn:aws:iam::" + accountId + ":user/" + akid
                            + " is not authorized to perform: " + action
                            + " on resource: \"" + resource + "\""
                            + " because no identity-based policy allows the " + action + " action",
                    403);
        } finally {
            requestContext.setAccountId(previousAccountId);
        }
    }

    /**
     * Resolves the account that owns {@code akid} directly from the credential, following the
     * same precedence {@link AccountContextFilter} applies to a header or presigned-URL request:
     * a 12-digit access key ID is the account itself, otherwise {@link SessionAccountLookup}
     * looks up the owning account for an IAM or session credential, falling back to the configured
     * default account when neither resolves.
     */
    private String resolveCredentialAccountId(String akid, String authorizationHeader) {
        if (akid != null && !akid.matches("\\d{12}")) {
            Optional<String> credentialAccount = sessionAccountLookup.resolveAccountId(akid);
            if (credentialAccount.isPresent()) {
                return credentialAccount.get();
            }
        }
        return accountResolver.resolve(authorizationHeader);
    }

    /**
     * Best-effort CloudTrail emission for S3 access denials. Without this hook,
     * denied requests get aborted before {@code S3Controller}'s try/catch sees
     * them, so denials would never appear in CloudTrail logs — leaving a major
     * gap vs. real AWS for downstream audit ingestion. Failures here never
     * propagate (the deny response is the load-bearing behavior).
     */
    private void emitS3DenialIfApplicable(String akid, String action, String resource,
                                          ContainerRequestContext ctx, String region,
                                          String denyMessage) {
        try {
            if (action == null || !action.startsWith("s3:")) {
                return;
            }
            String eventName = mapS3ActionToEventName(action, ctx.getMethod());
            if (eventName == null) {
                return;
            }
            String[] bk = parseS3Resource(resource);
            String bucket = bk[0];
            String key = bk[1];

            String userAgent = null;
            String sourceIp = null;
            try {
                var rc = currentVertxRequest.getCurrent();
                if (rc != null) {
                    var req = rc.request();
                    if (req != null) {
                        userAgent = req.getHeader("User-Agent");
                        String fwd = req.getHeader("X-Forwarded-For");
                        if (fwd != null && !fwd.isBlank()) {
                            int comma = fwd.indexOf(',');
                            sourceIp = (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
                        } else if (req.remoteAddress() != null) {
                            sourceIp = req.remoteAddress().host();
                        }
                    }
                }
            } catch (Exception e) {
                LOG.tracev(e, "CloudTrail: could not extract request context for IAM denial {0} on {1}", action, resource);
            }

            cloudTrailService.emitS3DataEvent(CloudTrailService.S3EventInput.builder()
                    .region(region)
                    .eventName(eventName)
                    .bucketName(bucket)
                    .key(key)
                    .accessKeyId(akid)
                    .sourceIp(sourceIp)
                    .userAgent(userAgent)
                    .errorCode("AccessDenied")
                    .errorMessage(denyMessage)
                    .eventTimeMillis(System.currentTimeMillis())
                    .build());
        } catch (RuntimeException e) {
            LOG.tracev(e, "CloudTrail denial emission failed for {0} on {1}", action, resource);
        }
    }

    // Package-private for unit testing.
    static String mapS3ActionToEventName(String action, String httpMethod) {
        if (action == null) return null;
        // Action set sourced from IamActionRegistry — see that file for any
        // additions. HEAD on an object is bucketed under s3:GetObject by the
        // registry, so we distinguish via httpMethod.
        return switch (action) {
            case "s3:GetObject", "s3:GetObjectVersion" ->
                    "HEAD".equalsIgnoreCase(httpMethod) ? "HeadObject" : "GetObject";
            case "s3:PutObject" -> "PutObject";
            case "s3:DeleteObject", "s3:DeleteObjectVersion" -> "DeleteObject";
            case "s3:ListBucket" -> "ListObjects";
            case "s3:ListAllMyBuckets" -> "ListBuckets";
            case "s3:GetObjectAcl", "s3:GetObjectVersionAcl" -> "GetObjectAcl";
            case "s3:PutObjectAcl", "s3:PutObjectVersionAcl" -> "PutObjectAcl";
            case "s3:GetObjectTagging", "s3:GetObjectVersionTagging" -> "GetObjectTagging";
            case "s3:PutObjectTagging", "s3:PutObjectVersionTagging" -> "PutObjectTagging";
            case "s3:DeleteObjectTagging", "s3:DeleteObjectVersionTagging" -> "DeleteObjectTagging";
            default -> null;
        };
    }

    /** Returns [bucket, key] (key may be null if the resource is a bucket-level ARN). */
    // Package-private for unit testing.
    static String[] parseS3Resource(String resource) {
        if (resource == null || !resource.startsWith("arn:aws:s3:::")) {
            return new String[] { null, null };
        }
        String tail = resource.substring("arn:aws:s3:::".length());
        if (tail.isEmpty() || "*".equals(tail)) {
            return new String[] { null, null };
        }
        int slash = tail.indexOf('/');
        if (slash < 0) {
            return new String[] { tail, null };
        }
        String bucket = tail.substring(0, slash);
        String key = tail.substring(slash + 1);
        return new String[] { bucket, key.isEmpty() ? null : key };
    }

    private static String abbreviateAuth(String auth) {
        if (auth == null) {
            return "null";
        }
        return auth.length() <= 96 ? auth : auth.substring(0, 96) + "…";
    }

    // Package-private for unit testing.
    static String extractCredentialScope(String auth) {
        if (auth == null) {
            return null;
        }
        Matcher v4 = SIGV4_SCOPE.matcher(auth);
        if (v4.find()) {
            return v4.group(1);
        }
        Matcher v4a = SIGV4A_SCOPE.matcher(auth);
        return v4a.find() ? v4a.group(1) : null;
    }

    /**
     * Presigned URLs (and presigned POST forms, handled separately via
     * {@link #authorizeAdditionalResource}) sign via the {@code X-Amz-Credential} query
     * parameter instead of the {@code Authorization} header, so {@code ctx.getHeaderString}
     * alone misses them and this filter would silently skip IAM identity-policy evaluation for
     * every presigned request. {@link AccountContextFilter} already resolves account/region the
     * same way for the same reason. Synthesizing a {@code Credential=...} string from the query
     * parameter lets every downstream step here - access key extraction, credential scope,
     * action resolution, resource ARNs - run unchanged for both signing styles.
     */
    private static String presignedCredentialAsAuthorization(ContainerRequestContext ctx) {
        String credential = ctx.getUriInfo().getQueryParameters().getFirst("X-Amz-Credential");
        return credential == null || credential.isBlank() ? null : "Credential=" + credential;
    }

    /**
     * Builds a 403 Access Denied response in the wire format the calling SDK
     * expects. AWS SDKs hard-fail when they receive the wrong shape: an XML
     * parser blows up on a leading {@code {}, and a JSON parser blows up on
     * {@code <}. Pick the shape from request signals:
     *
     * <ul>
     *   <li>S3 → S3-flavored XML {@code <Error>...</Error>}</li>
     *   <li>{@code application/x-www-form-urlencoded} body → AWS Query
     *       {@code <ErrorResponse>...</ErrorResponse>} (IAM/STS/EC2/SQS/SNS/...)</li>
     *   <li>everything else (JSON 1.x, REST-JSON) → keep the historical JSON shape</li>
     * </ul>
     */
    private List<ResourcePolicyProvider.ResourcePolicy> resolveResourcePolicies(String credentialScope, String resourceArn) {
        if (resourcePolicyProviders == null || resourcePolicyProviders.isUnsatisfied()) {
            return List.of();
        }
        List<ResourcePolicyProvider.ResourcePolicy> policies = new ArrayList<>();
        for (ResourcePolicyProvider provider : resourcePolicyProviders) {
            List<ResourcePolicyProvider.ResourcePolicy> providerPolicies = provider.getResourcePolicies(credentialScope, resourceArn);
            if (providerPolicies != null && !providerPolicies.isEmpty()) {
                policies.addAll(providerPolicies);
            }
        }
        return policies;
    }

    // Package-private for unit testing.
    static Response accessDeniedResponse(String action, String credentialScope, MediaType requestMediaType) {
        return accessDeniedResponse(action, credentialScope, requestMediaType, null);
    }

    static Response accessDeniedResponse(String action, String credentialScope, MediaType requestMediaType, String resourceArn) {
        String message = "User is not authorized to perform: " + action;
        if ("s3".equals(credentialScope)) {
            String resourcePath = formatS3ResourcePath(resourceArn);
            return s3XmlAccessDenied(message, resourcePath);
        }
        if (isFormEncoded(requestMediaType)) {
            return queryXmlAccessDenied(message);
        }
        return jsonAccessDenied(message);
    }

    private static String formatS3ResourcePath(String resourceArn) {
        if (resourceArn == null || !resourceArn.startsWith("arn:aws:s3:::")) {
            return null;
        }
        String tail = resourceArn.substring("arn:aws:s3:::".length());
        if (tail.isEmpty() || "*".equals(tail)) {
            return null;
        }
        return "/" + tail;
    }

    private static boolean isFormEncoded(MediaType mt) {
        return mt != null
                && "application".equalsIgnoreCase(mt.getType())
                && "x-www-form-urlencoded".equalsIgnoreCase(mt.getSubtype());
    }

    private static Response queryXmlAccessDenied(String message) {
        String xml = new XmlBuilder()
                .start("ErrorResponse")
                  .start("Error")
                    .elem("Type", "Sender")
                    .elem("Code", "AccessDenied")
                    .elem("Message", message)
                  .end("Error")
                  .elem("RequestId", UUID.randomUUID().toString())
                .end("ErrorResponse")
                .build();
        return Response.status(403).type(MediaType.APPLICATION_XML).entity(xml).build();
    }

    private static Response s3XmlAccessDenied(String message) {
        return s3XmlAccessDenied(message, null);
    }

    private static Response s3XmlAccessDenied(String message, String resourcePath) {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("Error")
                  .elem("Code", "AccessDenied")
                  .elem("Message", message);
        if (resourcePath != null && !resourcePath.isBlank()) {
            xml.elem("Resource", resourcePath);
        }
        xml.elem("RequestId", UUID.randomUUID().toString())
           .end("Error");
        return Response.status(403).type(MediaType.APPLICATION_XML).entity(xml.build()).build();
    }

    private static Response jsonAccessDenied(String message) {
        String body = "{\"__type\":\"AccessDeniedException\",\"message\":\"" + message + "\"}";
        return Response.status(403).type(MediaType.APPLICATION_JSON).entity(body).build();
    }
}
