# ECR

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonEC2ContainerRegistry_V20150921.*`) for the control plane.
**Data plane:** OCI Distribution Spec v2 (`/v2/...`), proxied by Floci to a real `registry:2` container.
**Endpoint:** `POST http://localhost:4566/` (or `https://...`) for the control plane; `<account>.dkr.ecr.<region>.localhost:4566/<repo>` (HTTP) or `<account>.dkr.ecr.<region>.localhost.floci.io:4566/<repo>` (TLS) for `docker push` / `docker pull`.

## Supported Actions

| Action | Description |
| --- | --- |
| `CreateRepository` | Create a new repository (lazy-starts the backing registry on first call) |
| `DescribeRepositories` | List repositories or fetch by name |
| `DeleteRepository` | Delete a repository (with `force=true` semantics for non-empty repos) |
| `GetAuthorizationToken` | Returns a docker-login token + AWS-shaped `proxyEndpoint` (`<account>.dkr.ecr.<region>.localhost:<port>`) |
| `ListImages` | Enumerate tags and digests in a repository |
| `DescribeImages` | Image metadata: digest, size, push timestamp, manifest media type |
| `BatchGetImage` | Fetch image manifests, honoring `acceptedMediaTypes` |
| `BatchDeleteImage` | Delete images by tag or digest; publishes `ECR Image Action` (`DELETE`) to the default EventBridge bus |
| `PutImageTagMutability` | Set tag mutability and reject replacement pushes to immutable tags |
| `PutImageScanningConfiguration` | Set `scanOnPush` (round-trip; scans are not executed) |
| `TagResource` / `UntagResource` / `ListTagsForResource` | Resource tagging |
| `PutLifecyclePolicy` / `GetLifecyclePolicy` / `DeleteLifecyclePolicy` | Lifecycle policy round-trip (stored, not enforced) |
| `SetRepositoryPolicy` / `GetRepositoryPolicy` / `DeleteRepositoryPolicy` | Repository policy round-trip (stored, not enforced) |
| `PutRegistryPolicy` / `GetRegistryPolicy` / `DeleteRegistryPolicy` | Account/region registry policy singleton |
| `InitiateLayerUpload` / `UploadLayerPart` / `CompleteLayerUpload` | Layer upload (in-memory + best-effort push to `registry:2`) |
| `PutImage` | Put a manifest by tag (in-memory + best-effort push to `registry:2`); publishes `ECR Image Action` (`PUSH`) to the default EventBridge bus |
| `GetDownloadUrlForLayer` | Returns an `https://` blob URL (AWS-shaped; backing registry may be HTTP) |
| `BatchCheckLayerAvailability` | Reports `AVAILABLE` / `UNAVAILABLE` for uploaded or registry blobs |
| `StartImageScan` | Requires the image; returns `UnsupportedImageTypeException` for synthetic/scratch images |
| `DescribeImageScanFindings` | Returns `ScanNotFoundException` (scans are not executed) |

### Admin Endpoints

| Endpoint | Description |
| --- | --- |
| `POST /_floci/ecr/gc` | Run garbage collection on the backing `registry:2` container to reclaim disk after image deletions |

## Emulation Behavior

- **Real OCI registry backing.** A single shared `registry:2` container per Floci instance stores all repositories. Floci proxies Docker Distribution traffic to it and starts it lazily on the first ECR API call. The container is reused across Floci restarts (`keep-running-on-shutdown: true` by default), so pushed image bytes survive restarts.
- **TLS registry data plane.** Real AWS ECR is HTTPS-only. When TLS is enabled (`FLOCI_TLS_ENABLED=true`), Floci serves the registry data plane over TLS at `<account>.dkr.ecr.<region>.localhost.floci.io:<port>/<repo>`. The server certificate automatically includes regional wildcards `*.dkr.ecr.<region>.localhost.floci.io` for every advertised AWS region as Subject Alternative Names (SANs). Container runtimes and tools (Docker, containerd, nerdctl, podman) connect over HTTPS after trusting Floci's root CA certificate (`GET /_floci/ca.pem` or system trust store). The plain HTTP loopback path (`<account>.dkr.ecr.<region>.localhost:<port>`) remains fully supported without changes.
- **Storage cleanup.** `DeleteRepository --force` removes repository manifests, including untagged manifests. When it deletes the final repository, or when a non-retained runtime stops, Floci removes the named registry volume only in `memory` mode or when `prune-volumes-on-delete: true`.
- **Loopback URI scheme.** Repository URIs follow `<account>.dkr.ecr.<region>.localhost:<flociPort>/<repoName>`. RFC 6761 reserves `*.localhost` to resolve to the loopback address, and the docker daemon auto-trusts loopback as an insecure registry, so `docker push` and `docker pull` work without daemon configuration. A `path` URI style fallback (`localhost:<flociPort>/<account>/<region>/<repo>`) is available via `floci.services.ecr.uri-style: path` for environments where `*.localhost` resolution misbehaves.
- **Image tag mutability.** `IMMUTABLE` repositories allow the first manifest write for a tag and reject every replacement, including a replacement with the same manifest. The proxy forwards blob uploads and digest-addressed manifests unchanged.
- **Authorization.** `GetAuthorizationToken` returns `Base64("AWS:<long password>")` plus a proxy endpoint of the form `http://<account>.dkr.ecr.<region>.localhost:<port>` (contains `.ecr.`, matching live AWS). The token is longer than 100 characters. The backing `registry:2` runs without auth, so any `aws ecr get-login-password | docker login` succeeds. Login host and `repositoryUri` host are the same, so `docker push` reuses the token.
- **EventBridge image actions.** A successful `PutImage` or `BatchDeleteImage` publishes `source=aws.ecr`, `detail-type=ECR Image Action` on the default bus (`action-type` `PUSH`/`DELETE`, `result=SUCCESS`, plus repository name / digest / tag). Re-push of an identical tag throws `ImageAlreadyExistsException` and does **not** emit a second event.
- **Repository names.** Validation matches live AWS: `(?:[a-z0-9]+(?:[._-][a-z0-9]+)*/)*[a-z0-9]+(?:[._-][a-z0-9]+)*`. Consecutive separators (`--`) are rejected.
- **Manifest format negotiation.** `BatchGetImage` forwards the caller's `acceptedMediaTypes` as the upstream `Accept` header. Modern OCI manifests (`application/vnd.oci.image.manifest.v1+json`) and Docker v2 schema 2 are both supported.
- **Cross-account / cross-region isolation.** Internally the registry namespaces repositories as `<account>/<region>/<repoName>`, so the same repository name in different accounts or regions cannot collide.
- **Reconcile on first start.** When the registry container starts, Floci queries `GET /v2/_catalog` and recreates `Repository` metadata entries for any namespaces present in the registry but missing from local storage. This means image bytes are never orphaned across restarts.
- **Upgrade from direct registry access.** Floci recreates an older backing container with the same volume and a loopback-only port binding. Repositories written through the former direct endpoint retain their physical paths and map to the default account and Region; their returned URI moves to port `4566`.
- **Lambda and ECS integration.** Image-backed Lambda functions (`PackageType=Image`) and ECS task containers reference the same loopback `repositoryUri`. Floci rewrites real-AWS-shaped `<account>.dkr.ecr.<region>.amazonaws.com/...` URIs to the loopback registry at pull time, so CDK's `DockerImageFunction` (which generates AWS-shaped URIs in CloudFormation templates) works without any user-side rewriting.
- **Locally built images win.** An AWS-shaped URI that names an image already present on the Docker daemon is used as-is instead of being rewritten (`FLOCI_SERVICES_ECR_PREFER_LOCAL_IMAGES`, default `true`). Build the image under the exact URI your function or task definition declares (`docker build -t <account>.dkr.ecr.<region>.amazonaws.com/<repo>:<tag> .`) and it runs without a push to the emulated registry, and without the registry container being started. The check uses the reference Floci would launch, so with `FLOCI_DOCKER_IMAGE_REGISTRY_BASE` set the image must be present as `<base>/<account>.dkr.ecr.<region>.amazonaws.com/<repo>:<tag>`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ECR_ENABLED` | `true` | Enable the ECR control plane and lazy registry start |
| `FLOCI_SERVICES_ECR_REGISTRY_IMAGE` | `registry:2` | Backing OCI registry image |
| `FLOCI_SERVICES_ECR_REGISTRY_CONTAINER_NAME` | `floci-ecr-registry` | Container name used for idempotent reuse across restarts |
| `FLOCI_SERVICES_ECR_REGISTRY_BASE_PORT` | `5100` | First private loopback port for the backing registry |
| `FLOCI_SERVICES_ECR_REGISTRY_MAX_PORT` | `5199` | Last private loopback port for the backing registry |
| `FLOCI_SERVICES_ECR_DATA_PATH` | `./data/ecr` | Bind-mount root for the registry data directory |
| `FLOCI_SERVICES_ECR_KEEP_RUNNING_ON_SHUTDOWN` | `true` | Leave the registry container running so the next Floci start adopts it |
| `FLOCI_SERVICES_ECR_URI_STYLE` | `hostname` | `hostname` = `*.dkr.ecr.<region>.localhost`; `path` = `localhost:<port>/<account>/<region>/<repo>` |
| `FLOCI_SERVICES_ECR_PREFER_LOCAL_IMAGES` | `true` | Use an AWS-shaped ECR image URI as-is when the Docker daemon already has that image, instead of rewriting it to the loopback registry |
| `FLOCI_SERVICES_ECR_TLS_ENABLED` | `false` | Reserved for future ACM-backed TLS |

### Docker Compose port mapping

ECR uses Floci's existing `4566` listener. The backing registry binds a loopback-only implementation port, so no ECR range belongs in `docker-compose.yml`:

```yaml
# ECR uses the existing Floci API port
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
      - "6379-6399:6379-6399"   # ElastiCache
      - "7001-7099:7001-7099"   # RDS
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

`docker login 000000000000.dkr.ecr.us-east-1.localhost:4566` works once Floci starts the registry sidecar.

## Examples

```bash
export AWS_ENDPOINT=http://localhost:4566

# Create a repository
aws ecr create-repository \
  --repository-name floci-it/app \
  --endpoint-url $AWS_ENDPOINT
# {
#   "repository": {
#     "repositoryArn":  "arn:aws:ecr:us-east-1:000000000000:repository/floci-it/app",
#     "repositoryUri":  "000000000000.dkr.ecr.us-east-1.localhost:4566/floci-it/app",
#     "imageTagMutability": "MUTABLE",
#     ...
#   }
# }

# Authenticate stock docker against the emulated registry
aws ecr get-login-password --endpoint-url $AWS_ENDPOINT \
  | docker login --username AWS --password-stdin \
        000000000000.dkr.ecr.us-east-1.localhost:4566

# Push an image
docker pull alpine:3.19
docker tag  alpine:3.19 \
            000000000000.dkr.ecr.us-east-1.localhost:4566/floci-it/app:v1
docker push 000000000000.dkr.ecr.us-east-1.localhost:4566/floci-it/app:v1

# Inspect via the AWS CLI
aws ecr list-images     --repository-name floci-it/app --endpoint-url $AWS_ENDPOINT
aws ecr describe-images --repository-name floci-it/app --endpoint-url $AWS_ENDPOINT

# Pull from a clean local image store
docker rmi  000000000000.dkr.ecr.us-east-1.localhost:4566/floci-it/app:v1
docker pull 000000000000.dkr.ecr.us-east-1.localhost:4566/floci-it/app:v1

# Use the image as a Lambda function
aws lambda create-function \
  --function-name my-image-fn \
  --package-type Image \
  --code ImageUri=000000000000.dkr.ecr.us-east-1.localhost:4566/floci-it/app:v1 \
  --role arn:aws:iam::000000000000:role/lambda-role \
  --endpoint-url $AWS_ENDPOINT

aws lambda invoke --function-name my-image-fn /tmp/out.json --endpoint-url $AWS_ENDPOINT

# Tear down
aws ecr batch-delete-image --repository-name floci-it/app \
    --image-ids imageTag=v1 --endpoint-url $AWS_ENDPOINT
aws ecr delete-repository  --repository-name floci-it/app --force \
    --endpoint-url $AWS_ENDPOINT
```

### Push and Pull over TLS

When TLS is enabled (`FLOCI_TLS_ENABLED=true`), trust Floci's root CA and authenticate against the TLS registry data plane:

```bash
# 1. Download and trust Floci's root CA
curl -s http://localhost:4566/_floci/ca.pem -o floci-root-ca.pem
export AWS_CA_BUNDLE=$PWD/floci-root-ca.pem
export AWS_ENDPOINT=https://localhost:4566

# 2. Authenticate Docker against the TLS endpoint
aws ecr get-login-password --endpoint-url $AWS_ENDPOINT \
  | docker login --username AWS --password-stdin \
        000000000000.dkr.ecr.us-east-1.localhost.floci.io:4566

# 3. Tag and push an image over TLS
docker pull alpine:3.19
docker tag  alpine:3.19 \
            000000000000.dkr.ecr.us-east-1.localhost.floci.io:4566/floci-it/app:v1
docker push 000000000000.dkr.ecr.us-east-1.localhost.floci.io:4566/floci-it/app:v1

# 4. Pull over TLS
docker pull 000000000000.dkr.ecr.us-east-1.localhost.floci.io:4566/floci-it/app:v1
```

!!! note "Pulling from other containers (EKS, same-network consumers)"
    The `localhost`-based repository URI only works from the host. [Floci EKS](eks.md#pulling-images-from-floci-ecr)
    configures a containerd mirror to reach Floci's data plane, so Helm charts can reference the
    pushed URI as-is.

## SDK Example (Java)

```java
EcrClient ecr = EcrClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();

// Create a repository
Repository repo = ecr.createRepository(req -> req.repositoryName("floci-it/app"))
    .repository();

// Get a docker login token
GetAuthorizationTokenResponse auth = ecr.getAuthorizationToken();
AuthorizationData data = auth.authorizationData().get(0);
String decoded = new String(Base64.getDecoder().decode(data.authorizationToken()));
// decoded = "AWS:<password>"; pipe the password to `docker login --username AWS --password-stdin <proxyEndpoint>`

// List images after a docker push
ListImagesResponse images = ecr.listImages(req -> req.repositoryName("floci-it/app"));
images.imageIds().forEach(System.out::println);

// Force-delete the repository
ecr.deleteRepository(req -> req.repositoryName("floci-it/app").force(true));
```

## Using with AWS CDK

CDK's `DockerImageFunction` works against Floci unchanged:

```typescript
import * as lambda from 'aws-cdk-lib/aws-lambda';

new lambda.DockerImageFunction(this, 'MyFn', {
  functionName: 'hello',
  code: lambda.DockerImageCode.fromImageAsset('./docker-fn'),  // local Dockerfile
});
```

`cdk bootstrap` creates the asset ECR repository (`cdk-hnb659fds-container-assets-…`) via Floci's CloudFormation provisioner; `cdk deploy` runs `docker build` + `docker push` against the emulated registry; `aws lambda invoke` then pulls the image from the loopback registry and runs the handler. See [`compatibility-tests/compat-cdk`](https://github.com/floci-io/floci/tree/main/compatibility-tests/compat-cdk) for a working end-to-end example.

## Not Implemented

The following ECR features are **not** implemented. Stored values for policies and lifecycle rules round-trip via the API but are not enforced at runtime:

- Replication and pull-through cache
- Image scanning execution (the APIs exist: `StartImageScan` returns `UnsupportedImageTypeException` for synthetic images; `DescribeImageScanFindings` returns `ScanNotFoundException`)
- Image signing and notary attachments
- Lifecycle policy enforcement (the policy text is stored but not applied)
- Repository / registry policy enforcement (no IAM evaluation)
- TLS via emulated ACM
- `docker push` to real `*.amazonaws.com` ECR hostnames (Floci advertises `*.localhost` URIs; rewriting public AWS hostnames is out of scope)

## Troubleshooting

**`Function.TimedOut` when invoking image-backed Lambdas on native Linux Docker.** Lambda containers reach Floci's Runtime API server via the docker bridge gateway. On Ubuntu / Pop!_OS / Debian with UFW enabled, the default `INPUT DROP` policy blocks this path. See [Quick Start → Lambda on native Linux Docker](../getting-started/quick-start.md#lambda-on-native-linux-docker-ufw) for the one-line `ufw allow in on docker0` fix.

**`docker login` fails with TLS errors.** Floci's emulated registry serves plain HTTP. Docker auto-trusts loopback addresses (`127.0.0.1`, `*.localhost`) as insecure registries, so this should not normally happen. If your URIs end up pointing somewhere non-loopback (e.g. you set `FLOCI_HOSTNAME=floci` for Docker Compose), add the hostname to the daemon's `insecure-registries` array in `/etc/docker/daemon.json`.

**Disk not reclaimed after deleting images.** `BatchDeleteImage` removes manifests but blobs remain on disk until garbage collection runs. Trigger it with `curl -X POST http://localhost:4566/_floci/ecr/gc`. The endpoint runs `registry garbage-collect` inside the backing container and returns the reclaimed blob list. The operation is serialized : ECR API calls block for its duration (typically a few seconds).

**`*.localhost` does not resolve to loopback on this platform.** Set `floci.services.ecr.uri-style: path` to fall back to `localhost:<port>/<account>/<region>/<repo>` URIs.
