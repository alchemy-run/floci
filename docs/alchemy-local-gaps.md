# Alchemy local-dev gaps

Alchemy runs its live AWS suites against Floci with `ALCHEMY_TEST_DEV=1`
(`ALCHEMY_FLOCI_IMAGE=floci:dev` while iterating). This page is the order
book for emulator gaps those suites surface. Control-plane CRUD that already
works is not listed.

> **Evidence caveat (2026-08-18):** every run made through `pnpm
> test:aws:floci` before this date silently executed against **live AWS** —
> `Bun.spawn` in `scripts/test-aws-floci.ts` snapshots the environment at
> process start, so the script's `process.env.ALCHEMY_TEST_DEV = "1"`
> mutation never reached the spawned test process (fixed by passing
> `env: { ...process.env }` explicitly). Only runs made with a literal
> `ALCHEMY_TEST_DEV=1` shell prefix actually hit Floci. Per-service claims
> below that cite `test:aws:floci` evidence from before the fix are suspect
> until re-verified against a genuine emulator run.

## Genuine census (first real full run, 2026-08-18)

`pnpm test:aws:floci --retry 0` after the env fix: **635 passed / 253
failed** across 294 files (at `--concurrency 200` the RPC sidecar spawner
collapses with mass `WebSocket connection failed`; the script now defaults
to 64). Verified green in isolation: AutoScaling (incl. all 10 bindings),
ACM (14/14), ApiGateway RestApi lifecycle, CloudFront KVS bindings.

Failure classes, largest first:

1. **Split-brain suites (not Floci gaps).** The runner includes a service's
   whole test directory when *any* of its resources is `flociDual`'d. A
   non-dualized resource in that directory (IAM User/Group/Policy/…,
   EC2 Instance, …) deploys **live** while the test body's distilled
   clients are pinned to Floci — list/describe assertions then read an
   empty emulator. Fixing these means dualizing the resources (and
   implementing whatever Floci ops they need), not patching handlers
   blindly. Biggest offenders: IAM (38), EC2 Instance suites.
2. **Missing IAM Query ops** (needed before IAM resources can dualize):
   `ListAccountAliases`/aliases CRUD, `GetAccountPasswordPolicy` + CRUD,
   `ListVirtualMFADevices` + CRUD, `GetServerCertificate` + CRUD,
   `GetSSHPublicKey` + CRUD, `GetSAMLProvider` + CRUD,
   `GetOpenIDConnectProvider` + CRUD, credential report, account
   summary/authorization details, simulate/access-advisor family.
3. **Per-service handler gaps in already-dualized services** — the true
   order book: StepFunctions (16), SES (16), ECS (16), CloudFront (13 —
   KVS data plane now implemented), Glue (12), SecretsManager (11),
   Cognito (11), ApplicationAutoScaling (9), S3 (8), Batch (7), ELBv2 (6),
   Kinesis (5), ECR (5), DynamoDB (5), and a long tail. Each needs the
   standard inner loop against its run-log failures.

## Policy

- **`UnknownOperationException` / CloudWatch `UnsupportedOperation` = implement the operation.** These are missing handler cases, not environment problems. Add the action to the service handler, store enough in-memory state to satisfy AWS-shaped describe/list/update, write a Floci integration test, and update `docs/services/{service}.md` plus the count in `docs/services/index.md`. Do **not** paper them over with `isLocalEmulator` skips in Alchemy.
- **Wrong-shape responses** (extra fields, dropped `Condition`, leftover `StreamViewType`) are also patches — match live AWS.
- **Services with no Floci implementation at all** stay remote-only in Alchemy (`Alchemy.remote()` / live provider). Do not invent a new service here.
- Alchemy `isLocalEmulator` skips stay only for host/environment issues (port 80, advertised Function URL hostname) until the data plane is actually reachable.

## S3

Patched in this tree (tagging `NoSuchTagSet`, singleton policy collapse,
accelerate, replication, intelligent-tiering including list;
`RestoreObject` → `403 InvalidObjectState` on STANDARD;
Get/Put ObjectRetention and LegalHold → `400 InvalidRequest` without
Object Lock; presigned PUT Content-Type mismatch → `403`; copy REPLACE
`application/octet-stream` → `binary/octet-stream`). Isolated
`pnpm test:aws:floci test/AWS/S3 --retry 0 --concurrency 8` (2026-08-19):
**51 passed / 0 failed**. Remaining:

| Gap | Evidence | Notes |
|---|---|---|
| Website data plane on `*.s3-website-*.amazonaws.com:80` | Alchemy `ownership controls and website hosting serves index.html` | Gateway already serves website Host headers on `:4566`. Host port 80 + public DNS is an environment problem; alchemy rewrites those URLs onto the gateway under `ALCHEMY_TEST_DEV`. |

## S3 Vectors

Patched in this tree (tags, bucket policy, `ListVectors`, create-time
metadata). Alchemy now `flociDual`s VectorBucket and Index. Needs image
rebuild (old image falls through missing `GetVectorBucketPolicy` to S3).

## API Gateway

Patched in this tree (usage-plan get/update, key/authorizer/deployment
updates, gateway responses, v1 VPC links, ExportApi, cache flush, v2
domain names, binaryMediaTypes, `rootResourceId` on every RestApi
response, `GetResources?embed=methods`, GetUsage/UpdateUsage, v2 stage
`description` + tags, TagResource on `/apis/{id}/stages/{name}` ARNs,
and `{apiId}.execute-api.{region}.amazonaws.com` Host
routing onto `/execute-api/{apiId}/{stage}/…`). Alchemy now `flociDual`s
RestApi and the related v1/v2 resources. Api mappings stay live-only
(unimplemented). Still missing ReimportApi, routing rules,
TestInvokeAuthorizer, docs parts, client certificates. WebSocket
`wss://*.execute-api.*.amazonaws.com` from the host is a platform issue
(Alchemy's HttpClient rewrite does not apply to the `ws` package).

## AppSync

Patched in this tree (API cache, EvaluateCode/MappingTemplate, GraphQL
data plane, AWS-shaped URLs, ACM cert check). Embedded DNS now resolves
`{apiId}.appsync-api.{region}.amazonaws.com` and `appsync.{region}.amazonaws.com`
to Floci so in-Lambda GraphQL `fetch()` / distilled clients reach the
emulator; TLS SAN `*.appsync-api.us-east-1.amazonaws.com` covers HTTPS:443
(the single-label `*.us-east-1.amazonaws.com` wildcard does not).
`EvaluateCode` `outErrors` is the AWS JSON string. Alchemy `flociDual`s
GraphqlApi and related resources. Test-process HTTP rewrite still maps
`*.appsync-api.*.amazonaws.com` onto the gateway (same as S3 website).
Still missing DynamoDB/HTTP/OpenSearch/RDS adapters, subscriptions, and
Lambda-authorizer data-plane.

## AppConfig

Patched in this tree (update/delete, extensions, validate, stop
deployment, strategy duration, extension action dispatch). Alchemy now
`flociDual`s Application, Environment, Profile, Strategy, Deployment,
HostedConfigurationVersion, Extension, and ExtensionAssociation.
`StartDeployment` stays `DEPLOYING` when `DeploymentDurationInMinutes > 0`
so `StopDeployment` can roll it back. Associated extension actions invoke
Lambda (`Event`) and emit EventBridge (`Source=aws.appconfig`) on
`ON_DEPLOYMENT_START` / `COMPLETE` / `ROLLED_BACK`.

## WAFv2

Patched in this tree (API keys, managed-rule catalog, sampled/rate/top-path,
TagResource persist). Against current `floci:dev`: 7/21. Alchemy now
`flociDual`s WebACL, RuleGroup, IPSet, RegexPatternSet, LoggingConfiguration,
and WebACLAssociation (creates were stamping the live account). Sampled
requests / rate keys / top-path return empty AWS-shaped payloads.

## ACM

Patched in this tree (`SearchCertificates`, `UpdateCertificateOptions`,
`RenewCertificate`, `ResendValidationEmail`, `RevokeCertificate`; pending
public certs). Alchemy now `flociDual`s Certificate and AccountConfiguration.
ACMPCA stays remote-only. Bindings 500s are Lambda Function URL reachability.
`NoSuchHostedZone` is Route53.

## Scheduler / Pipes

Scheduler Schedule/ScheduleGroup and Pipes Pipe are `flociDual` (15/17).
Needs rebuild for context-attribute substitution and Lambda bare-array
batch. No missing operations.

## EC2

Patched in this tree (peering, DHCP, prefix lists, ENIs, snapshots,
describe-by-id NotFound, default-VPC reseed, CloudWatch VPC flow logs +
`vpc-flow-log` tags, CreateVolume `KmsKeyId` alias→ARN, DescribeFlowLogs
filters/pagination, DeleteVpc default-furniture reap, instance
control-plane `running` without waiting on Docker). Alchemy now
`flociDual`s Instance, FlowLog, and the networking/storage resources the
suite hits. Transit / Carrier / LocalGateway / CoreNetwork route targets
stay `UnsupportedOperation`. Hosted-instance HTTP smoke (userdata +
published ports) is still a data-plane/platform question.

## ECR

Patched in this tree (registry policy, scan config, layer upload/PutImage,
download URL, scan APIs, describe fallback across account ids, AWS-shaped
`GetAuthorizationToken` proxyEndpoint, hostname-style docker-push lookup,
EventBridge `ECR Image Action` on PutImage / BatchDeleteImage). Alchemy
`flociDual`s Repository, Image, and RegistryPolicy.

Repository-name validation matches live AWS
`(?:[a-z0-9]+(?:[._-][a-z0-9]+)*/)*[a-z0-9]+(?:[._-][a-z0-9]+)*` —
consecutive separators (`--`) are rejected on both. ECS-generated names
containing `--` are out-of-scope (name generation), not an ECR emulator
bug.

Remaining: `docker push` to real `*.amazonaws.com` hostnames (Floci
returns `*.localhost` URIs).

## ECS

No missing operations. Source now round-trips task-def/service fields
(logConfiguration, dependsOn, circuit breaker, FARGATE_SPOT, container
`healthCheck`, `serviceRegistries`, …). Cluster, Service, Task,
TaskDefinition already dual; CapacityProvider now dual.
Remaining: EC2 `InvalidVpcID.NotFound` / hung subnet create (Bindings),
Route53 hosted-zone lookup (`ServiceHostedZoneNotFound` after local
`CreateHostedZone` — fixed in Route 53 `ListHostedZonesByName`), Cloud Map + EFS
on `ServicePhase2Config`, AAS unstamped-row delete, stale live-AWS
`delete (remote)` / `ServiceNotActiveException`, ECR repo-name pattern.

## DynamoDB

Patched in this tree (stream spec omitted when disabled, resource policy,
Contributor Insights, Kinesis destination precision, Kinesis
`ListTagsForResource`, CloudWatch `DescribeInsightRules`).

`UpdateTable` matches AWS no-op semantics: unchanged
`ProvisionedThroughput` is rejected only when it is the sole field on the
request. Alchemy's Table reconciler always sends BillingMode /
AttributeDefinitions / SSE / deletion protection alongside throughput, so
those updates now succeed (unblocks Application Auto Scaling's nine
DynamoDB-backed suites).

On-demand backups (`CreateBackup` / `DescribeBackup` / `ListBackups` /
`DeleteBackup` / `RestoreTableFromBackup`) and
`RestoreTableToPointInTime` are implemented. `ExportTableToPointInTime`
and PITR restore emit `PointInTimeRecoveryUnavailableException` when
PITR is disabled. `ExecuteTransaction` returns per-statement
`Responses` for PartiQL `SELECT`.

Isolated DynamoDB (2026-08-19): **103 passed / 2 failed / 1 todo**.
Remaining Bindings `DeleteItem` and `Backups` delete are Lambda Function
URL dropping HTTP DELETE bodies (`RequestParseError: Unexpected end of
JSON input`) — the DynamoDB APIs themselves work. Out of scope for this
service.

## Lambda

Patched in this tree (`AddPermission`/`GetPolicy` keep
`lambda:InvokedViaFunctionUrl` and `lambda:FunctionUrlAuthType`
Conditions; `UpdateFunctionCode` persists `Architectures`;
`GetAccountSettings`; `InvokeWithResponseStream` event-stream;
VPC Hyperplane ENIs on create/delete; Extensions API writes
`EXTENSION Name: "…" ` to CloudWatch; `GetLayerVersionByArn`;
`UpdateFunctionUrlConfig` Cors MaxAge is null-safe;
unsigned AWS_IAM Function URLs return 403).

`Version` and `Alias` now deploy locally (the `DurableFunction` suite
publishes a version and creates a `live` alias against Floci,
2026-08-19 — account `000000000000`, `PublishVersion`/`CreateAlias` in
the emulator log). The `LayerVersion` dualization status is unverified.

Lambda containers now receive `ASIA…` credentials minted from the
function's execution role (see IAM). The `test` root bypass is only used
when the function has no role.

**Durable Functions** are emulated (`services/lambda/durable/`, see
[docs/services/lambda.md](services/lambda.md#durable-executions)):
durable `Invoke` (`X-Amz-Durable-Execution-Name` → 202 +
`X-Amz-Durable-Execution-Arn`, idempotent reattach by name),
the checkpoint data plane the Durable Execution SDK speaks from inside
the function (`CheckpointDurableExecution`, `GetDurableExecutionState`),
real suspend/resume (a durable wait arms a Vert.x timer and the function
is re-invoked with `UpdatedOperationIds`; no container is held during the
wait), callbacks, `Stop`, `List`, `Get`, and synthesized `History`.
`timeout 900 pnpm test:aws:floci test/AWS/Lambda/DurableFunction.test.ts
--retry 0` is green (2026-08-19): typed-error probe + the full 2-step +
5s-sleep suspend/resume lifecycle.

| Gap | Evidence | Notes |
|---|---|---|
| `LayerVersion` dualization unverified | historical `391965393224` / `us-west-2` ARNs | `Version`/`Alias` verified local via `DurableFunction.test.ts`; re-run the layer suites to confirm. Floci implements layer CRUD. |
| Durable chained invokes | `CHAINED_INVOKE` checkpoint op | Fails the operation with a typed `ChainedInvokeNotSupported` error instead of invoking the child durable function. |
| Durable execution timeout / retention not enforced | `DurableConfig.ExecutionTimeout` / `RetentionPeriodInDays` accepted at CreateFunction | Executions never TIMED_OUT server-side and are retained until function delete or emulator restart. |
| Suspended durable executions don't survive restart | In-memory Vert.x timers | Timers are not re-armed from persisted state after an emulator restart; a suspended execution stays RUNNING forever. |
| OTLP export from Lambda to a Cloudflare collector | `Telemetry.test.ts` `/probe` status 0 | Platform: collector reachability from the Lambda container, not a missing Lambda op. |
| CloudWatch Logs log-group reap | Prior destroy hangs | Logs describe/delete is slow or unimplemented enough that alchemy now skips reap on the emulator account. |

## Lambda MicroVMs

Implemented in this tree (see `docs/services/lambda-microvms.md`): the full
`2025-09-09` control plane (image CRUD + versions + builds, managed base-image
catalog, Run/Get/List/Suspend/Resume/Terminate, auth tokens, Lambda-shared tag
routes), real `docker build` of code artifacts, real Docker containers per
MicroVM, and the authenticated endpoint data plane on
`{id}.lambda-microvm.{region}.localhost.floci.io:443`.

Acceptance status (`LAMBDA_TEST_MICROVM=1 pnpm test:aws:floci
test/AWS/Lambda/MicrovmImage.test.ts --retry 0`, 2026-08-19): **3 passed / 1
failed**. The Lambda-host lifecycle, the in-VM tagged-RPC + fetch round-trip,
and the external Dockerfile+context build all pass against Floci. The
following Alchemy-side registrations were flipped to get there (single-line
`flociDual` wraps in `Providers.ts`, pending coordinator review):
`Lambda.MicrovmImage`, `Lambda.NetworkConnector`, `IAM.User`, `IAM.AccessKey`
(`IAM.Role` was already dual). Without them the mixed state was actively
harmful: the dualized Role landed in Floci (account `000000000000`) while the
live-only MicrovmImage handed that role ARN to real AWS, which rejects it with
`AccessDeniedException: Cross-account pass role is not allowed`.

**The one remaining failure — `drives the MicroVM from a Cloudflare Worker
(cross-cloud assume-role)` — is Alchemy-side wiring, not a Floci gap:**

| Gap | Evidence | Notes |
|---|---|---|
| Worker-side MicroVM bindings never provide `Endpoint` | `MicrovmBinding.ts`: `makeAssumeRoleResolver` provides `Credentials`/`Region`/`FetchHttpClient` only; same for `withRuntimeCredentials` | Inside workerd the STS `AssumeRole` (and, if it got that far, every MicroVM control-plane op) resolves the default real-AWS endpoint. Real STS rejects the Floci-minted user key: `UnknownAwsError: The security token included in the request is invalid.` (verified in the worker log). Fix: provide an `Endpoint` layer (host-reachable emulator URL) through the binding when the stack is dev-mode, or inject `AWS_ENDPOINT_URL` into the local worker env and honor it in distilled's endpoint resolution under workerd. |
| Local workerd does not trust Floci's CA | MicroVM RPC stubs call `https://{endpoint}` (TLS terminated by Floci's self-signed cert) | Bites immediately after the endpoint gap is fixed. Host-side workerd needs the CA (downloadable at `GET /_floci/tls/ca`) via `NODE_EXTRA_CA_CERTS` or equivalent; Floci-launched Lambda containers already get it injected. The endpoint hostnames (`*.lambda-microvm.us-east-1.localhost.floci.io`) already resolve to `127.0.0.1` from the host and are covered by the cert's SANs. |
| `Lambda.NetworkConnector` emulation is identity-only | Floci stores connector records; no real network plumbing | The MicroVM fixtures never create connectors, so this is inert for the acceptance suite. |

IAM policy evaluation is **intentionally permissive for MicroVM actions**:
`lambda:RunMicrovm` etc. are not on the enforcement allowlist and unmapped
actions are allow-by-default; STS `AssumeRole` session keys only need to
resolve as identities (they do — `IamService.registerSession`). TODO: evaluate
role policies once the IAM enforcement allowlist grows to cover MicroVM
actions.

## IAM

Lambda execution-role sessions (`ASIA…` minted at container launch) are
evaluated for `ses:SendEmail` / `ses:SendRawEmail` / `ses:SendBulkEmail`
and `kms:GetKeyRotationStatus` even while
`floci.services.iam.enforcement-enabled` is off. `test` keys, unknown
keys, unmapped actions, and all other role-issued calls stay permissive.

| Gap | Evidence | Notes |
|---|---|---|
| `GetRolePolicy` drops `Condition` | Lambda Function URL policy | Store and return the full statement, including `Condition`. |

## Auto Scaling

Implemented in this tree (scheduled actions, execute policy, standby,
instance protection/health, cancel/rollback refresh). Remaining after the
image picks up those handlers:

| Gap | Evidence | Notes |
|---|---|---|
| Default subnet `subnet-default-a` missing | Alchemy ASG / LifecycleHook / ScheduledAction | EC2 default-VPC restore; Alchemy `TestNetwork` waits but create still races a missing subnet. |
| Launch template name not found after create | Alchemy `AutoScalingGroup` / `ScalingPolicy` list | EC2 `DescribeLaunchTemplates` visibility, not an ASG handler gap. |

## Application Auto Scaling

Control plane is implemented: scalable targets (local DynamoDB
`table/{name}` and ECS `service/{cluster}/{service}` IDs are stored
without probing the backing service), target-tracking policies +
managed CloudWatch alarms, scheduled actions, empty
`DescribeScalingActivities` pages, and `GetPredictiveScalingForecast`
with AWS-shaped non-ECS `AccessDeniedException` ("GetPredictiveScalingForecast
is not supported.") that distilled maps to
`PredictiveScalingForecastNotSupported`.

Isolated `pnpm test:aws:floci test/AWS/ApplicationAutoScaling --retry 0
--concurrency 8` (2026-08-19): **10 passed / 0 failed** after DynamoDB
`UpdateTable` no-op parity.

| Gap | Evidence | Notes |
|---|---|---|
| DynamoDB `UpdateTable` no-op | Bindings / ScalableTarget / ScalingPolicy / ScheduledAction | Fixed — reject unchanged throughput only when it is the sole UpdateTable field. |
| Policies stay inert | documented | No control loop; `DescribeScalingActivities` returns an empty page. |

## Step Functions

Patched in this tree (`UpdateStateMachine`, `TestState`, `RedriveExecution`,
Map Run list/describe/update, Fail-state Catch unwrap, `sync-states` DNS +
TLS SAN so Lambda `StartSyncExecution` can reach Floci on 443, optimized
`lambda:invoke` envelope wraps `{Payload, StatusCode}`). Alchemy now
`flociDual`s StateMachine and Activity (creates were landing on live AWS).
Remaining after rebuild:

| Gap | Evidence | Notes |
|---|---|---|
| `RedriveExecution` restarts from the start | documented | Not failure-point resume. |
| Distributed Map Runs | metadata only | Execute inline; no real Map Run engine. |
| ASL validation is partial | `ValidateStateMachineDefinition` | StartAt/States + JSONata/ItemReader only. |

## RDS

Patched in this tree: custom cluster endpoints, instance/cluster snapshots
(create/copy/delete + not-found), `ResetDBParameterGroup`, `DescribeEvents`,
`DescribePendingMaintenanceActions`, `ApplyPendingMaintenanceAction`
(`ResourceNotFoundFault` on a missing ARN), start/stop/failover no-ops,
parameter-group ARNs + `Source=user` on describe, distilled Query list keys
(`Parameters.Parameter.N`), and create-time `MasterUserPassword` /
`BackupRetentionPeriod` validation (`InvalidParameterValue`). Remaining:

| Gap | Evidence | Notes |
|---|---|---|
| DB proxy CRUD | Alchemy `DBProxy*` suites are env-gated (`AWS_TEST_RDS_DBPROXY`) | `DescribeDBProxies` still returns an empty list. Full `CreateDBProxy` / endpoints / target groups are not modeled. |
| Real data-plane containers | `RDSData` bindings + instance/cluster lifecycle | Gated by `RDS_TEST_LIFECYCLE` / `SLOW`. Mock mode is enough for control-plane list/diff tests. |

Alchemy now `flociDual`s cluster/instance/parameter-group/subnet-group
resources. Proxy CRUD stays live-only.

## Cloud Map

Patched in this tree: namespace/service updates, SOA TTL, `TYPE`/`NAME` list
filters, service attributes, `UpdateInstanceCustomHealthStatus`.

## Route 53

Patched in this tree: private-zone VPC attach/associate/auth, query logging
configs, `UpdateHostedZoneComment`, `TestDNSAnswer`,
`GetHealthCheckLastFailureReason`, `ListHostedZonesByVPC`, geo/cidr/geoproximity
record fields, DNS-order `ListHostedZonesByName` (fixes ECS
`ServiceHostedZoneNotFound` after a local `CreateHostedZone`),
`QueryLoggingConfigAlreadyExists`, weighted/failover `SetIdentifier` DELETE.

| Gap | Evidence | Notes |
|---|---|---|
| `logs:PutResourcePolicy` | Alchemy `QueryLoggingConfig.test.ts` | CloudWatch Logs, not Route 53. Query-logging CRUD is implemented; the test also needs the Logs resource-policy action. |
| Route53 Domains / Profiles / Resolver | No Floci service | Stay remote-only in Alchemy. |

## CloudWatch / CloudWatch Logs

Patched in this tree: `DescribeInsightRules` returns an empty
`InsightRules` list so DynamoDB Contributor Insights teardown can finish.

| Gap | Evidence | Notes |
|---|---|---|
| Log group lifecycle used by Lambda delete | Destroy hang before alchemy skip | Need cheap no-op or fast not-found for `/aws/lambda/*`. |

## ELBv2

Patched in this tree (Trust Store CRUD, `ModifyCapacityReservation`,
authenticate-oidc round-trip, CreateListener NLB/TCP/TLS — the
`InternalFailure: Unexpected error: null` was an immutable region-map
`UnsupportedOperationException`, not an NPE — SNI `IsDefault` +
ModifyListener default-cert-only). Alchemy `flociDual`s LB, Listener,
Rule, TargetGroup, TrustStore, ListenerCertificate, and
TargetGroupAttachment. Unsigned Query `CreateListener` (NLB/TCP) against
`alchemy-floci` returns 200.

## EventBridge

Patched in this tree (Connection + ApiDestination CRUD, `ListRuleNamesByTarget`).
Alchemy `flociDual`s Archive/Connection/ApiDestination. Against current
`floci:dev`: 17/19. The two failures are the old image still rejecting
`DescribeConnection` and `ListRuleNamesByTarget`.

## EventBridge Scheduler

Control-plane CRUD (12 ops) already works against the current image.
Context-attribute substitution in `Target.Input` is patched in this tree
(`<aws.scheduler.schedule-arn>` etc.) and needs the image rebuild.

| Gap | Evidence | Notes |
|---|---|---|
| Context attrs not substituted in running image | `ScheduleEventSource` asserts a real `:schedule/default/` ARN | Source replaces placeholders at fire time; current `floci:dev` still delivers the literal `"<aws.scheduler.schedule-arn>"`. |

## EventBridge Pipes

Control-plane CRUD + tags (10 ops) work after Alchemy `flociDual`s `Pipe`
(without dual, CreatePipe hit live AWS with a `000000000000` role ARN →
`AccessDeniedException: Cross-account pass role is not allowed`).
Lambda target batch shape is patched in this tree and needs the image rebuild.

| Gap | Evidence | Notes |
|---|---|---|
| Lambda target `{Records:[...]}` envelope in running image | `Pipes delivery` → `MessageNotDelivered` | AWS Pipes Lambda targets get a bare JSON array. Source now emits that; current `floci:dev` still wraps `Records`. |

## KMS

Patched in this tree (`UpdateAlias`, `GenerateDataKeyPair*`,
`DeriveSharedSecret`, rotation period, Verify/CancelKeyDeletion shapes).
Alchemy now `flociDual`s `Key` and `Alias`. SSM lazily seeds
`alias/aws/ssm` on the first SecureString so `DescribeKey` can resolve
`keyArn`.

`kms:GetKeyRotationStatus` is in the Lambda-role IAM allowlist, so the
bindings least-privilege test (`the role only receives the bound actions`)
can observe `AccessDeniedException`. Other KMS actions stay permissive
while global enforcement is off (alias condition keys are not modeled).

## CloudFront

Patched in this tree (VpcOrigin + KeyValueStore CRUD, DescribeFunction,
KVS associations, policy XML round-trip, list envelopes as HttpPayload
roots, KeyGroup `PublicKey` items, realtime-log Fields/EndPoints, Function
DEVELOPMENT+LIVE stages). KvEntries / KvRoutesUpdate stay live-only —
CloudFront-KeyValueStore data plane is a separate service (now implemented
in this tree; see KVS data-plane section above).

## Cognito

Patched in this tree: user-pool JWT issuer is now AWS-shaped
(`https://cognito-idp.<region>.amazonaws.com/<poolId>`), custom schema
attributes are prefixed `custom:` on create, `DeleteUser` /
`AdminListDevices` exist, and `AWSCognitoIdentityService` implements
identity-pool CRUD, tags, roles (no PassRole / cross-account check),
guest `GetId` / credentials / OpenID token, and identity admin.

User-pool control plane (UserPool, Client, Domain, Group, User,
IdentityProvider, ResourceServer, RiskConfiguration, custom schema) is
green against `floci:dev`. Remaining Alchemy suite failures are not
missing IdP APIs:

| Gap | Evidence | Notes |
|---|---|---|
| IdentityPool / RoleAttachment not `flociDual` | create without `(local)`; `ResourceNotFoundException` on distilled describe; `AccessDeniedException: Cross-account pass role` | Alchemy still registers live-only `IdentityPoolProvider` / `IdentityPoolRoleAttachmentProvider`. Floci implements the identity APIs; Bindings + IdentityPool tests hit real AWS (split-brain). |
| Triggers Function URL socket close | `HttpClientError` POST `…lambda-url…/sign-up-flow` | One Lambda is both the Function URL and PreSignUp/PreTokenGeneration. Cognito `RequestResponse`-invokes that same function while the URL invoke is in-flight. Reproduced isolated. Lambda same-function concurrency — trigger handlers themselves exist. |
| Cross-region Lambda triggers | `InvalidParameterException` | Only if a pool's `LambdaConfig` ARN region differs from the pool; same-account local Functions work. |

## Firehose

Patched in this tree (SSE start/stop, Kinesis-as-source, list pagination).
Suite was 0/7 against live Firehose because `DeliveryStream` was not
`flociDual` (create used a Floci S3 bucket). Alchemy now registers
`DeliveryStream` and `Kinesis.Stream` / `StreamConsumer` via `flociDual`.
SSE start/stop still needs the image rebuild (`InvalidAction` on current
`floci:dev`).

## Kinesis

Patched in this tree: `UpdateShardCount` (uniform split/merge),
`UpdateMaxRecordSize` / `MaxRecordSizeInKiB` on describe,
`UpdateStreamWarmThroughput` + `DescribeAccountSettings` (commitment
`ENABLED`), `DescribeLimits`, `ListStreams.StreamSummaries`,
`CreateStream` tags / warm throughput / max record size, consumer
`TagResource` (consumer ARNs no longer mutate the stream), even
hash-key ranges, and `MergeShards` adjacency + parent lineage.
`DeleteStream EnforceConsumerDeletion` removes registered consumers.

Isolated `pnpm test:aws:floci test/AWS/Kinesis --retry 0 --concurrency 8`
(2026-08-19): **37 passed / 0 failed** (two transport flakes on a
mid-run container restart reran green).

## SES

Patched in this tree (receipt rules/filters, tenants, MREs, VDM,
SendBounce, deliverability stubs, `PutTenantSuppressionAttributes` as
POST, v2 email-address identities stay PENDING, bulk
`MessageRejected` → entry `MESSAGE_REJECTED`). Alchemy now
`flociDual`s the SES resource set. New v1 actions are on
`AwsQueryController.SES_ACTIONS`. Inbound receiving data plane stays
out of scope. Contact lists are 1-per-account (concurrent
Contact/ContactList tests collide).

| Gap | Evidence | Notes |
|---|---|---|
| ~~Bindings IAM outsider From is `MessageRejected`, not `AccessDeniedException`~~ | `SendEmail > a sender outside the bound identity is denied by the scoped IAM policy` | **Fixed:** Lambda containers sign as an `ASIA` session mapped to the execution role. `IamEnforcementFilter` evaluates `ses:SendEmail` (global enforcement stays off) against `arn:aws:ses:…:identity/<from>`. `AWSLambdaBasicExecutionRole` is seeded as CloudWatch Logs only so its old `*/*` placeholder cannot grant the send. |

## Secrets Manager

Patched in this tree (`CancelRotateSecret`; persist Put/Get/Delete
ResourcePolicy; `RotateSecret` no longer reserves an empty `AWSPENDING`
version before `createSecret`). Isolated `test/AWS/SecretsManager`
against a rebuilt image: resource CRUD, bindings (Get/Put/Describe/
List/Batch/GetRandomPassword), and rotation schedule config.

| Gap | Evidence | Notes |
|---|---|---|
| `ValidateResourcePolicy` / replica APIs | not called by suite | Still unimplemented. |

Alchemy: `RotationSchedule` is `flociDual` so Bindings call Floci
`RotateSecret` (which invokes the emulated Lambda through the four-step
protocol).

## SSM

Patched in this tree (`UnlabelParameterVersion`; PutParameter tags/metadata;
label defaults to latest; first SecureString seeds KMS `alias/aws/ssm`).
Needs image rebuild for the alias seed.

| Gap | Evidence | Notes |
|---|---|---|
| SSMContacts / SSMIncidents | no Floci service | Remote-only. |

## SNS

Patched in this tree (`AddPermission` / `RemovePermission` persist topic
policy Sids; GCM/FCM `CreatePlatformApplication` rejects fake credentials
with `InvalidParameter`; SMS sandbox / attributes / opt-out / origination
list ops). Needs image rebuild.

| Gap | Evidence | Notes |
|---|---|---|
| Real APNS / FCM delivery | Mobile push is captured in-memory | Unchanged mock. |

## Glue

Implemented in this tree: jobs, crawlers, connections, job runs/bookmarks, and `GetTags`/`TagResource` on catalog ARNs. Remaining:

| Gap | Evidence | Notes |
|---|---|---|
| Job runs complete immediately | `StartJobRun` | No Spark/Python worker; runs are stored as `SUCCEEDED`. |
| Crawlers do not inspect S3 | `StartCrawler` | State machine only (`READY`/`RUNNING`); no table inference. |
| `AWSGlueServiceRole` missing | Job/Crawler/Bindings beforeAll | IAM managed-policy seed. |

Alchemy now `flociDual`s Glue Connection/Crawler/Database/Job/Table,
Athena DataCatalog/NamedQuery/PreparedStatement/WorkGroup, and Batch
ComputeEnvironment/JobDefinition/JobQueue.

## Athena

Implemented in this tree: workgroup update/tags, data catalogs, named queries, prepared statements, batch-get, runtime stats stub, Glue-qualified DuckDB views + headerless CSV, EventBridge `Athena Query State Change` on every execution transition, `NamedQuery` not-found message matching distilled `NamedQueryNotFound`, in-process `SELECT <n>` (no DuckDB). Remaining:

| Gap | Evidence | Notes |
|---|---|---|
| Runtime statistics are a stub | `GetQueryRuntimeStatistics` | Timeline fields exist; no real engine counters. |
| Query data plane still needs DuckDB | `StartQueryExecution` | Unchanged; mock mode skips execution but still emits state-change events. |
| `StopQueryExecution` on a terminal query | Alchemy `StopQueryExecution` binding | AWS is a no-op (stays `SUCCEEDED`). Source now matches. |

## Batch

Implemented in this tree: update/delete CE+queue, cancel/terminate (no-op when already terminal), snapshot (`earliestTimeAtPosition`), tag APIs, unmanaged `unmanagedvCpus`, distilled not-found / `ComputeEnvironmentInUse` messages, and `AWSBatchServiceRole` IAM seed. The Alchemy image sets `immediate-complete: false` so `SubmitJob` leaves jobs `RUNNABLE` for binding cancel/terminate tests. Isolated `pnpm test:aws:floci test/AWS/Batch --retry 0 --concurrency 4`: 10 passed, 2 todo (`AWS_TEST_SLOW`).

| Gap | Evidence | Notes |
|---|---|---|
| No array / multi-node jobs | Existing limitation | Unchanged. |

## How to reproduce

```bash
# from alchemy-effect, after `pnpm floci:build`
ALCHEMY_TEST_DEV=1 ALCHEMY_FLOCI_IMAGE=floci:dev \
  pnpm test test/AWS/S3/Bucket.test.ts --profile testing --retry 0
ALCHEMY_TEST_DEV=1 ALCHEMY_FLOCI_IMAGE=floci:dev \
  pnpm test test/AWS/{DynamoDB/Table,Lambda/Function,Kinesis/Stream,SQS/Queue,SNS/Topic}.test.ts \
  --profile testing --retry 0
```
