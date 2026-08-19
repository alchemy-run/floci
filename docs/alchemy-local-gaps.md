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
domain names, binaryMediaTypes, and `rootResourceId` on every RestApi
response shape — its absence failed alchemy's RestApi precreate). Alchemy
now `flociDual`s RestApi and the related v1/v2 resources. Api mappings
stay live-only (unimplemented). Still missing ReimportApi, routing rules,
TestInvokeAuthorizer, docs parts, client certificates.

## AppSync

Patched in this tree (API cache, EvaluateCode/MappingTemplate, GraphQL
data plane, AWS-shaped URLs, ACM cert check). Alchemy now `flociDual`s
GraphqlApi and related resources. Test HTTP rewrite now maps
`*.appsync-api.*.amazonaws.com` onto the gateway (same as S3 website).
Still missing DynamoDB/HTTP/OpenSearch/RDS adapters, subscriptions, and
Lambda-authorizer data-plane.

## AppConfig

Patched in this tree (update/delete, extensions, validate, stop
deployment). Alchemy now `flociDual`s Application, Environment, Profile,
Strategy, Deployment, HostedConfigurationVersion, Extension, and
ExtensionAssociation. Extension actions do not emit EventBridge events.

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
describe-by-id NotFound, default-VPC reseed). Alchemy now `flociDual`s
the networking/storage resources the suite hits. Instance and FlowLog
stay live-only (no Floci FlowLog; Instance is smoke/VM). Transit /
Carrier / LocalGateway / CoreNetwork route targets stay
`UnsupportedOperation`.

## ECR

Patched in this tree (registry policy, scan config, layer upload/PutImage,
download URL, scan APIs, describe fallback across account ids). Alchemy
now `flociDual`s Repository, Image, and RegistryPolicy. EventBridge image
action notifications and docker-push to `amazonaws.com` URIs remain.

## ECS

No missing operations. Source now round-trips task-def/service fields
(logConfiguration, dependsOn, circuit breaker, FARGATE_SPOT, container
`healthCheck`, `serviceRegistries`, …). Cluster, Service, Task,
TaskDefinition already dual; CapacityProvider now dual.
Remaining: EC2 `InvalidVpcID.NotFound` / hung subnet create (Bindings),
Route53 hosted-zone lookup (`ServiceHostedZoneNotFound` after local
`CreateHostedZone`), ELBv2 Listener `InternalFailure`, Cloud Map + EFS
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

| Gap | Evidence | Notes |
|---|---|---|
| Function URL HTTP from the host | `Function.test.ts` `create, update, delete function` hangs on `GET functionUrl` | Create/delete work. Invoke via SDK timeout test is fine. Data-plane URL may not be reachable at the advertised hostname/port. |
| IAM inline policy `Condition` | Same test: `GetRolePolicy` omits `lambda:InvokedViaFunctionUrl` | Alchemy asserts the Function URL invoke condition. |
| CloudWatch Logs log-group reap | Prior destroy hangs | Logs describe/delete is slow or unimplemented enough that alchemy now skips reap on the emulator account. |

## IAM

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
`DescribePendingMaintenanceActions`, start/stop/failover no-ops, parameter-group
ARNs + `Source=user` on describe. Remaining:

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
record fields. Remaining:

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
Against current `floci:dev`: 12/20 (bindings already green). Alchemy now
`flociDual`s `Key` and `Alias`. Still missing `alias/aws/ssm` for SSM
SecureString `keyArn`.

## CloudFront

Patched in this tree (VpcOrigin + KeyValueStore CRUD, DescribeFunction,
KVS associations, policy XML round-trip). Against current `floci:dev`:
12/21. Alchemy now `flociDual`s Distribution and the other control-plane
resources. KvEntries / KvRoutesUpdate stay live-only — CloudFront-KeyValueStore
data plane (`GET /key-value-stores/{arn}`) has no Floci service.

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
| Bindings IAM outsider From is `MessageRejected`, not `AccessDeniedException` | `SendEmail > a sender outside the bound identity is denied by the scoped IAM policy` | Needs Lambda execution-role credentials (today `test`/`test` bypass) plus `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED` and SES send action/ARN mapping. Not a SES handler bug — IAM fires before SES on AWS. |

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
label defaults to latest). Against current `floci:dev` (pre-rebuild): 9/13.

| Gap | Evidence | Notes |
|---|---|---|
| `UnlabelParameterVersion` not in running image | Label/Unlabel binding test 5xx | Source is patched; needs `pnpm floci:build`. |
| `DescribeKey(alias/aws/ssm)` | SecureString `keyArn` stays undefined | KMS: seed the AWS-managed SSM key alias. |
| `ListRuleNamesByTarget` | `consumeParameterEvents` out-of-band check | Implemented in EventBridge source; needs image rebuild. |
| SSMContacts / SSMIncidents | no Floci service | Remote-only. |

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

Implemented in this tree: workgroup update/tags, data catalogs, named queries, prepared statements, batch-get, runtime stats stub. Remaining:

| Gap | Evidence | Notes |
|---|---|---|
| Runtime statistics are a stub | `GetQueryRuntimeStatistics` | Timeline fields exist; no real engine counters. |
| Query data plane still needs DuckDB | `StartQueryExecution` | Unchanged; mock mode skips execution. |
| `StopQueryExecution` on a terminal query | Alchemy `StopQueryExecution` binding | AWS is a no-op (stays `SUCCEEDED`). Source now matches; needs image rebuild. |

## Batch

Implemented in this tree: update/delete CE+queue, cancel/terminate, snapshot, tag APIs. Remaining:

| Gap | Evidence | Notes |
|---|---|---|
| Cancel/terminate of already-succeeded jobs | Immediate runner | Jobs finish in-process; cancel after submit is usually terminal. |
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
