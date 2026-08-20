# Lambda MicroVMs

Emulates the AWS Lambda MicroVMs preview service (`lambda-microvms`, API version
`2025-09-09`, SigV4 scope `lambda`). MicroVM images are **really built** as local
Docker images from the caller's code artifact, and MicroVMs are **really run**
as local Docker containers — the in-VM HTTP server is reachable through Floci's
gateway with a minted auth token, exactly like the AWS data plane.

Wire shapes mirror `distilled/packages/aws/src/services/lambda-microvms.ts`
(alchemy-effect). The acceptance suite is alchemy's
`test/AWS/Lambda/MicrovmImage.test.ts` run against Floci.

## Architecture

| AWS concept | Floci emulation |
| --- | --- |
| MicroVM image build (Firecracker snapshot) | `docker build` of the uploaded code artifact (a zip containing a Dockerfile + files), tagged `floci-microvm/{name}:{version}` |
| Managed base image (`public.ecr.aws/lambda/microvms:*`) | `FROM` lines referencing the managed base are rewritten to `public.ecr.aws/amazonlinux/amazonlinux:2023` before the build |
| MicroVM (Firecracker VM) | Docker container from the built image, wired to Floci's embedded DNS and CA |
| Suspend / Resume (memory snapshot) | `docker pause` / `docker unpause` (no memory snapshot) |
| MicroVM endpoint `{id}.lambda-microvm.{region}.on.aws` | `{id}.lambda-microvm.{region}.localhost.floci.io` — resolves to 127.0.0.1 on the host (public wildcard DNS) and to Floci in-container (embedded DNS), served on 443 by the TLS proxy |
| Endpoint auth (`X-aws-proxy-auth`) | Opaque token minted by `CreateMicrovmAuthToken`, validated by the gateway proxy before forwarding to the container |

### Data plane routing

`MicrovmEndpointRoutingFilter` (a `@PreMatching` JAX-RS filter, like the Lambda
URL and AppSync filters) rewrites requests whose `Host` contains
`.lambda-microvm.` to `/_floci/microvm-endpoint/{microvmId}{path}`.
`MicrovmEndpointProxyController` validates the `X-aws-proxy-auth` header
(an HMAC-signed statement of `microvmId | expiry | allowed ports`, signed with
a per-process secret) and forwards the request (any method, path, query,
headers, body) to the container's HTTP port, resolved via
`ContainerLifecycleManager.resolveEndpoint` (container IP in container mode,
published host port otherwise).

The self-signed certificate includes the SAN
`*.lambda-microvm.us-east-1.localhost.floci.io` so both in-container clients
(which trust Floci's CA via `NODE_EXTRA_CA_CERTS`) and host clients that
install the CA can connect. The CA certificate is downloadable at
`GET /_floci/tls/ca` for host-side clients (e.g. a local workerd that needs
`NODE_EXTRA_CA_CERTS`).

### Build pipeline

`CreateMicrovmImage` / `UpdateMicrovmImage`:

1. Persist the image/version record (`CREATING` / `UPDATING`, version `1`, `2`, …).
2. Asynchronously: fetch `codeArtifact.uri` (`s3://bucket/key`) from Floci's S3,
   unzip to a temp dir, rewrite managed-base `FROM` lines, `docker build`.
3. On success: version `SUCCESSFUL`/`ACTIVE`, image `CREATED`/`UPDATED`,
   `latestActiveImageVersion` set. On failure: version `FAILED` with the build
   log tail as `stateReason`, image `CREATE_FAILED`/`UPDATE_FAILED`,
   `latestFailedImageVersion` set. A build record (per host architecture) is
   listable via `ListMicrovmImageBuilds` / `GetMicrovmImageBuild`.

Builds require network access on the Docker daemon (the generated Dockerfiles
run `dnf install -y nodejs` etc. at build time). Built layers are cached by
Docker, so repeat builds are fast.

## Requirements checklist (derived from alchemy's MicroVM tests)

Op → request members actually sent → response members actually read → the
assertion they feed. This is the contract the acceptance suite exercises.

### Control plane — image lifecycle (alchemy `MicrovmImageProvider`)

| Op | Route | Request members sent | Response members read | Consumer |
| --- | --- | --- | --- | --- |
| ListManagedMicrovmImages | `GET /2025-09-09/managed-microvm-images` | `maxResults?`, `nextToken?` | `items[].imageArn`, `createdAt`; `nextToken` | `defaultBaseImageArn` picks the arn containing `al2023` |
| CreateMicrovmImage | `POST /2025-09-09/microvm-images` | `name`, `baseImageArn`, `baseImageVersion?`, `buildRoleArn`, `codeArtifact.uri`, `description?`, `logging?`, `egressNetworkConnectors?`, `cpuConfigurations?`, `resources?`, `additionalOsCapabilities?`, `hooks?`, `environmentVariables?`, `tags`, `clientToken` | `imageArn` (rest echoed) | create → `waitForReady`; `ConflictException` → lookup-by-name fallback |
| UpdateMicrovmImage | `PUT /2025-09-09/microvm-images/{imageIdentifier}` | same as create minus `name`/`tags`, plus `imageIdentifier` (ARN) | `imageArn` | artifact-hash change → new version build |
| GetMicrovmImage | `GET /2025-09-09/microvm-images/{imageIdentifier}` | ARN (or name — the create-conflict fallback passes the bare name) | `imageArn`, `name`, `state`, `latestActiveImageVersion?`, `latestFailedImageVersion?`, `createdAt`, `updatedAt?`, `tags?` | `waitForReady` polls until `CREATED`/`UPDATED`; `CREATE_FAILED`/`UPDATE_FAILED` aborts; 404 → `ResourceNotFoundException` tag |
| ListMicrovmImages | `GET /2025-09-09/microvm-images?nameFilter=` | `nameFilter?`, `maxResults?`, `nextToken?` | `items[].{imageArn,name,state,latestActiveImageVersion?,latestFailedImageVersion?,createdAt}` | name→image lookup, `list` |
| GetMicrovmImageVersion | `GET …/versions/{imageVersion}` | labels | `state`, `stateReason?`, `status`, config echo | build-failure drill-down |
| ListMicrovmImageBuilds | `GET …/versions/{imageVersion}/builds` | labels | `items[].{buildId,buildState,architecture,chipset,chipsetGeneration,stateReason?,createdAt,imageArn,imageVersion}` | per-arch failure reasons |
| TagResource / ListTags / UntagResource | `POST/GET/DELETE /2017-03-31/tags/{Resource}` | `Tags` map / `tagKeys` query | `Tags` | `syncTags` diff against observed tags |
| ListMicrovms | `GET /2025-09-09/microvms?imageIdentifier=` | `imageIdentifier?`, `imageVersion?`, paging | `items[].{microvmId,state,imageArn,imageVersion,startedAt}` | delete drains VMs in `PENDING/RUNNING/SUSPENDING/SUSPENDED` |
| TerminateMicrovm | `DELETE /2025-09-09/microvms/{microvmIdentifier}` | label | `{}` | drain + orchestrator `/terminate`; idempotent (404 caught) |
| DeleteMicrovmImage | `DELETE /2025-09-09/microvm-images/{imageIdentifier}` | label | `imageIdentifier`, `state` | `ValidationException` message must contain `running MicroVMs` while VMs are active (provider retries on exactly that); then `waitForDeleted` polls Get until 404/`DELETED` |

### Data plane — instance ops (Lambda orchestrator + Cloudflare Worker fixtures)

| Op | Route | Request members sent | Response members read | Assertion |
| --- | --- | --- | --- | --- |
| RunMicrovm | `POST /2025-09-09/microvms` | `imageIdentifier` (ARN), `idlePolicy{maxIdleDurationSeconds,suspendedDurationSeconds,autoResumeEnabled}`, `clientToken` | `microvmId`, `endpoint`, `state`, `imageArn`, `imageVersion`, `maximumDurationInSeconds`, `startedAt` | `microvmId` truthy; `endpoint` contains `lambda-microvm` and is a bare hostname (callers prepend `https://`) |
| GetMicrovm | `GET /2025-09-09/microvms/{id}` | label | `state` (+ full shape) | polled until `RUNNING` (30×2s) |
| ListMicrovms | `GET /2025-09-09/microvms` | none | `items.length` | `count ≥ 1` while a VM runs |
| CreateMicrovmAuthToken | `POST /2025-09-09/microvms/{id}/auth-token` | `expirationInMinutes: 5`, `allowedPorts: [{port: 8080}]` | `authToken` (header-name → value map) | non-empty; sent verbatim as request headers to the endpoint |
| SuspendMicrovm / ResumeMicrovm | `POST …/suspend`, `…/resume` | label | `{}` | orchestrator routes exist (docker pause/unpause) |
| TerminateMicrovm | `DELETE /2025-09-09/microvms/{id}` | label | `{}` | `/terminate` 200; `ensuring` cleanup |
| Endpoint proxy | any method on Host `{id}.lambda-microvm.{region}.…` | `X-aws-proxy-auth` header + arbitrary path/body | verbatim in-VM response | `POST /__rpc__/hello` → `"hello, {msg}!"`; `GET /echo?message=m` → `{message: m}` |

### Cross-cloud assume-role chain (Cloudflare Worker host)

The Worker signs with STS session credentials minted through this chain (all
pre-existing Floci IAM/STS features, exercised end-to-end by the suite):

1. IAM `CreateUser` + inline `PutUserPolicy` (allow `sts:AssumeRole` on `*`)
2. IAM `CreateAccessKey` → long-lived key usable for SigV4
3. IAM `CreateRole` whose trust policy names that user as principal
4. `PutRolePolicy` accumulating the MicroVM action grants
5. STS `AssumeRole` → session credentials registered via
   `IamService.registerSession`, so subsequent SigV4 requests with the ASIA key
   resolve to a valid identity/account

> **IAM policy evaluation is intentionally permissive for MicroVM actions** —
> `lambda:RunMicrovm` etc. are not on the enforcement allowlist, and unmapped
> actions are allow-by-default. Session keys only need to *resolve* (signature
> and account scoping), not evaluate. TODO: evaluate role policies once the IAM
> enforcement allowlist grows to cover MicroVM actions.

## Operations

Implemented with real behavior: CreateMicrovmImage, UpdateMicrovmImage,
GetMicrovmImage, ListMicrovmImages, DeleteMicrovmImage,
GetMicrovmImageVersion, ListMicrovmImageVersions, DeleteMicrovmImageVersion,
UpdateMicrovmImageVersion, ListMicrovmImageBuilds, GetMicrovmImageBuild,
ListManagedMicrovmImages, ListManagedMicrovmImageVersions, RunMicrovm,
GetMicrovm, ListMicrovms, SuspendMicrovm, ResumeMicrovm, TerminateMicrovm,
CreateMicrovmAuthToken, TagResource/ListTags/UntagResource (shared Lambda tag
routes).

Honest minimal stubs (not exercised by the acceptance suite):

- `CreateMicrovmShellAuthToken` — returns a token map like the regular auth
  token; there is no interactive shell ingress in the emulator.
- NetworkConnector CRUD (`/2026-04-04/network-connectors`, in the `lambda-core`
  distilled module) — not implemented; the acceptance fixtures never create
  connectors. `ingressNetworkConnectors`/`egressNetworkConnectors` on
  images/VMs are stored and echoed but have no network effect.

## Behavior notes & intentional deviations

- **No Firecracker**: suspend/resume is `docker pause`/`unpause`; there is no
  memory-snapshot restore. `SnapshotBuild` sizes in build records are synthetic.
- **Single architecture**: images build for the Docker host's architecture; the
  requested `cpuConfigurations` are stored and echoed, and the build record
  reports the actual host arch (`ARM_64` on Apple Silicon, `X86_64` otherwise).
- **`RunMicrovm` is synchronous**: the container is started before the response
  returns, so `state` is already `RUNNING` (AWS returns `PENDING` first; the
  alchemy fixtures poll `GetMicrovm` either way).
- **Endpoint domain**: `{id}.lambda-microvm.{region}.{hostname}` where
  `{hostname}` defaults to `localhost.floci.io`. The public wildcard DNS record
  `*.localhost.floci.io → 127.0.0.1` plus Floci's published port 443 make the
  endpoint reachable from the host; the embedded DNS makes it reachable from
  launched containers.
- **Auth tokens** are stateless HMAC tokens signed with a per-process secret —
  a Floci restart invalidates outstanding tokens (they are minutes-lived by
  design). Images, versions, builds and microvm records persist through the
  standard lambda storage backend. MicroVM containers do not survive a Floci
  restart.
- **`maximumDurationInSeconds`** defaults to 3600 and is not enforced (no
  reaper); `idlePolicy` is stored and echoed but idle suspension is not
  emulated.
- **IAM**: see the callout above — the IAM policy engine is intentionally
  permissive for MicroVM actions. TODO: evaluate role policies once the IAM
  enforcement allowlist grows.
