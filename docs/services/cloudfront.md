# CloudFront

CloudFront management-plane and local content-delivery emulation. Supports distribution lifecycle,
cache policies, origin request policies, response headers policies, origin access controls, origin
access identities, public keys, trusted key groups, CloudFront Functions, invalidations, tagging, and
GET/HEAD/OPTIONS delivery from S3 or custom origins. Requests can also reach the fork's emulated edge through per-distribution local ports and execute CloudFront Functions.

**Protocol:** REST XML  
**API version:** `2020-05-31`  
**Endpoint prefix:** `cloudfront`  
**Namespace:** `http://cloudfront.amazonaws.com/doc/2020-05-31/`  
**Global service** : ARNs contain no region segment.

## Supported Operations

### Distributions

| Operation | Method | Path |
|---|---|---|
| `CreateDistribution` | POST | `/2020-05-31/distribution` |
| `CreateDistributionWithTags` | POST | `/2020-05-31/distribution?WithTags` |
| `GetDistribution` | GET | `/2020-05-31/distribution/{Id}` |
| `GetDistributionConfig` | GET | `/2020-05-31/distribution/{Id}/config` |
| `UpdateDistribution` | PUT | `/2020-05-31/distribution/{Id}/config` |
| `DeleteDistribution` | DELETE | `/2020-05-31/distribution/{Id}` |
| `ListDistributions` | GET | `/2020-05-31/distribution` |
| `AssociateAlias` | PUT | `/2020-05-31/distribution/{TargetDistributionId}/associate-alias` |

### Invalidations

| Operation | Method | Path |
|---|---|---|
| `CreateInvalidation` | POST | `/2020-05-31/distribution/{Id}/invalidation` |
| `GetInvalidation` | GET | `/2020-05-31/distribution/{Id}/invalidation/{InvId}` |
| `ListInvalidations` | GET | `/2020-05-31/distribution/{Id}/invalidation` |

### Cache Policies

| Operation | Method | Path |
|---|---|---|
| `CreateCachePolicy` | POST | `/2020-05-31/cache-policy` |
| `GetCachePolicy` | GET | `/2020-05-31/cache-policy/{Id}` |
| `GetCachePolicyConfig` | GET | `/2020-05-31/cache-policy/{Id}/config` |
| `UpdateCachePolicy` | PUT | `/2020-05-31/cache-policy/{Id}` |
| `DeleteCachePolicy` | DELETE | `/2020-05-31/cache-policy/{Id}` |
| `ListCachePolicies` | GET | `/2020-05-31/cache-policy` |

### Origin Request Policies

| Operation | Method | Path |
|---|---|---|
| `CreateOriginRequestPolicy` | POST | `/2020-05-31/origin-request-policy` |
| `GetOriginRequestPolicy` | GET | `/2020-05-31/origin-request-policy/{Id}` |
| `GetOriginRequestPolicyConfig` | GET | `/2020-05-31/origin-request-policy/{Id}/config` |
| `UpdateOriginRequestPolicy` | PUT | `/2020-05-31/origin-request-policy/{Id}` |
| `DeleteOriginRequestPolicy` | DELETE | `/2020-05-31/origin-request-policy/{Id}` |
| `ListOriginRequestPolicies` | GET | `/2020-05-31/origin-request-policy` |

### Response Headers Policies

| Operation | Method | Path |
|---|---|---|
| `CreateResponseHeadersPolicy` | POST | `/2020-05-31/response-headers-policy` |
| `GetResponseHeadersPolicy` | GET | `/2020-05-31/response-headers-policy/{Id}` |
| `GetResponseHeadersPolicyConfig` | GET | `/2020-05-31/response-headers-policy/{Id}/config` |
| `UpdateResponseHeadersPolicy` | PUT | `/2020-05-31/response-headers-policy/{Id}` |
| `DeleteResponseHeadersPolicy` | DELETE | `/2020-05-31/response-headers-policy/{Id}` |
| `ListResponseHeadersPolicies` | GET | `/2020-05-31/response-headers-policy` |

### Origin Access Control (OAC)

| Operation | Method | Path |
|---|---|---|
| `CreateOriginAccessControl` | POST | `/2020-05-31/origin-access-control` |
| `GetOriginAccessControl` | GET | `/2020-05-31/origin-access-control/{Id}` |
| `GetOriginAccessControlConfig` | GET | `/2020-05-31/origin-access-control/{Id}/config` |
| `UpdateOriginAccessControl` | PUT | `/2020-05-31/origin-access-control/{Id}` |
| `DeleteOriginAccessControl` | DELETE | `/2020-05-31/origin-access-control/{Id}` |
| `ListOriginAccessControls` | GET | `/2020-05-31/origin-access-control` |

### Origin Access Identity (OAI : legacy)

| Operation | Method | Path |
|---|---|---|
| `CreateCloudFrontOriginAccessIdentity` | POST | `/2020-05-31/origin-access-identity/cloudfront` |
| `GetCloudFrontOriginAccessIdentity` | GET | `/2020-05-31/origin-access-identity/cloudfront/{Id}` |
| `GetCloudFrontOriginAccessIdentityConfig` | GET | `/2020-05-31/origin-access-identity/cloudfront/{Id}/config` |
| `UpdateCloudFrontOriginAccessIdentity` | PUT | `/2020-05-31/origin-access-identity/cloudfront/{Id}/config` |
| `DeleteCloudFrontOriginAccessIdentity` | DELETE | `/2020-05-31/origin-access-identity/cloudfront/{Id}` |
| `ListCloudFrontOriginAccessIdentities` | GET | `/2020-05-31/origin-access-identity/cloudfront` |

### CloudFront Functions

| Operation | Method | Path |
|---|---|---|
| `CreateFunction` | POST | `/2020-05-31/function` |
| `GetFunction` | GET | `/2020-05-31/function/{Name}` |
| `DescribeFunction` | GET | `/2020-05-31/function/{Name}/describe` |
| `UpdateFunction` | PUT | `/2020-05-31/function/{Name}` |
| `PublishFunction` | POST | `/2020-05-31/function/{Name}/publish` |
| `TestFunction` | POST | `/2020-05-31/function/{Name}/test` |
| `DeleteFunction` | DELETE | `/2020-05-31/function/{Name}` |
| `ListFunctions` | GET | `/2020-05-31/function` |

### VPC Origins

| Operation | Method | Path |
|---|---|---|
| `CreateVpcOrigin` | POST | `/2020-05-31/vpc-origin` |
| `GetVpcOrigin` | GET | `/2020-05-31/vpc-origin/{Id}` |
| `UpdateVpcOrigin` | PUT | `/2020-05-31/vpc-origin/{Id}` |
| `DeleteVpcOrigin` | DELETE | `/2020-05-31/vpc-origin/{Id}` |
| `ListVpcOrigins` | GET | `/2020-05-31/vpc-origin` |

### Key Value Stores

| Operation | Method | Path |
|---|---|---|
| `CreateKeyValueStore` | POST | `/2020-05-31/key-value-store` |
| `DescribeKeyValueStore` | GET | `/2020-05-31/key-value-store/{Name}` |
| `UpdateKeyValueStore` | PUT | `/2020-05-31/key-value-store/{Name}` |
| `DeleteKeyValueStore` | DELETE | `/2020-05-31/key-value-store/{Name}` |
| `ListKeyValueStores` | GET | `/2020-05-31/key-value-store` |

### Key Value Store data plane (`cloudfront-keyvaluestore`)

The KVS data plane is a separate AWS service (restJson1, signed as
`cloudfront-keyvaluestore`, hosted at `{accountId}.cloudfront-kvs.global.api.aws`;
with an endpoint override SDKs prefix the account id onto the override host,
which the embedded DNS wildcard resolves back to Floci). Stores are addressed
by ARN. Mutations require `If-Match` with the store's current ETag and bump it.

| Operation | Method | Path |
|---|---|---|
| `DescribeKeyValueStore` | GET | `/key-value-stores/{KvsARN}` |
| `ListKeys` | GET | `/key-value-stores/{KvsARN}/keys` |
| `UpdateKeys` | POST | `/key-value-stores/{KvsARN}/keys` |
| `GetKey` | GET | `/key-value-stores/{KvsARN}/keys/{Key}` |
| `PutKey` | PUT | `/key-value-stores/{KvsARN}/keys/{Key}` |
| `DeleteKey` | DELETE | `/key-value-stores/{KvsARN}/keys/{Key}` |

`UpdateKeys` tolerates deletes of absent keys (bulk convergence); a bare
`DeleteKey` of a missing key returns `ResourceNotFoundException`. An
`If-Match` mismatch returns `ConflictException` (409).

### Continuous Deployment

| Operation | Method | Path |
|---|---|---|
| `CreateContinuousDeploymentPolicy` | POST | `/2020-05-31/continuous-deployment-policy` |
| `GetContinuousDeploymentPolicy` | GET | `/2020-05-31/continuous-deployment-policy/{Id}` |
| `UpdateContinuousDeploymentPolicy` | PUT | `/2020-05-31/continuous-deployment-policy/{Id}` |
| `DeleteContinuousDeploymentPolicy` | DELETE | `/2020-05-31/continuous-deployment-policy/{Id}` |
| `ListContinuousDeploymentPolicies` | GET | `/2020-05-31/continuous-deployment-policy` |
| `CopyDistribution` | POST | `/2020-05-31/distribution/{PrimaryDistributionId}/copy` |

### Public Keys and Key Groups

| Operation | Method | Path |
|---|---|---|
| `CreatePublicKey` | POST | `/2020-05-31/public-key` |
| `GetPublicKey` | GET | `/2020-05-31/public-key/{Id}` |
| `GetPublicKeyConfig` | GET | `/2020-05-31/public-key/{Id}/config` |
| `UpdatePublicKey` | PUT | `/2020-05-31/public-key/{Id}/config` |
| `DeletePublicKey` | DELETE | `/2020-05-31/public-key/{Id}` |
| `ListPublicKeys` | GET | `/2020-05-31/public-key` |
| `CreateKeyGroup` | POST | `/2020-05-31/key-group` |
| `GetKeyGroup` | GET | `/2020-05-31/key-group/{Id}` |
| `GetKeyGroupConfig` | GET | `/2020-05-31/key-group/{Id}/config` |
| `UpdateKeyGroup` | PUT | `/2020-05-31/key-group/{Id}` |
| `DeleteKeyGroup` | DELETE | `/2020-05-31/key-group/{Id}` |
| `ListKeyGroups` | GET | `/2020-05-31/key-group` |

### Realtime Log Configs

| Operation | Method | Path |
|---|---|---|
| `CreateRealtimeLogConfig` | POST | `/2020-05-31/realtime-log-config` |
| `GetRealtimeLogConfig` | POST | `/2020-05-31/get-realtime-log-config` |
| `UpdateRealtimeLogConfig` | PUT | `/2020-05-31/realtime-log-config` |
| `DeleteRealtimeLogConfig` | POST | `/2020-05-31/delete-realtime-log-config` |
| `ListRealtimeLogConfigs` | GET | `/2020-05-31/realtime-log-config` |

### Streaming, Field-Level Encryption, Monitoring

| Operation | Method | Path |
|---|---|---|
| `CreateStreamingDistribution` | POST | `/2020-05-31/streaming-distribution` |
| `GetStreamingDistribution` | GET | `/2020-05-31/streaming-distribution/{Id}` |
| `UpdateStreamingDistribution` | PUT | `/2020-05-31/streaming-distribution/{Id}/config` |
| `DeleteStreamingDistribution` | DELETE | `/2020-05-31/streaming-distribution/{Id}` |
| `CreateFieldLevelEncryptionConfig` | POST | `/2020-05-31/field-level-encryption` |
| `CreateFieldLevelEncryptionProfile` | POST | `/2020-05-31/field-level-encryption-profile` |
| `CreateMonitoringSubscription` | POST | `/2020-05-31/distributions/{DistributionId}/monitoring-subscription` |
| `GetMonitoringSubscription` | GET | `/2020-05-31/distributions/{DistributionId}/monitoring-subscription` |
| `DeleteMonitoringSubscription` | DELETE | `/2020-05-31/distributions/{DistributionId}/monitoring-subscription` |

### Tagging

| Operation | Method | Path |
|---|---|---|
| `ListTagsForResource` | GET | `/2020-05-31/tagging?Resource={arn}` |
| `TagResource` | POST | `/2020-05-31/tagging?Operation=Tag&Resource={arn}` |
| `UntagResource` | POST | `/2020-05-31/tagging?Operation=Untag&Resource={arn}` |

## Behavior

- All distributions are immediately set to `Deployed` state (no async `InProgress` delay).
- Distribution IDs are 14 uppercase alphanumeric characters starting with `E` (e.g. `E1Z2X3C4V5B6N7`).
- Distribution domain names follow the pattern `{id}.cloudfront.net`, with the id lower-cased as AWS
  writes it in a host name.
- Public key IDs are `K` followed by 13 uppercase alphanumeric characters (e.g. `K2JCJMDEHXQW5F`),
  the value a signed URL carries as `Key-Pair-Id`. Key group, cache policy, origin request policy and
  response headers policy IDs are UUIDs, as they are on AWS.
- ARNs are global: no region segment: `arn:aws:cloudfront::{accountId}:distribution/{id}`.
- Invalidations are immediately marked `Completed`.
- `DeleteDistribution` returns `DistributionNotDisabled` (409) if `Enabled` is `true` in the config.
- All mutating operations (`PUT`, `DELETE`) require an `If-Match` header containing the current
  `ETag`. Response headers policies, public keys, and key groups distinguish a missing header
  (`InvalidIfMatchVersion`, 400) from a stale `ETag` (`PreconditionFailed`, 412). Other CloudFront
  resources currently return `InvalidIfMatchVersion` (400) for either case.
- All `GET` and `POST` (create) responses include an `ETag` response header.
- List operations emit the list envelope as the XML root (`CachePolicyList`, `FunctionList`, `KeyGroupList`, …) because distilled marks those structs as the HTTP payload : not a `List*Result` wrapper.
- Nested CloudFront collections (origins, behaviors, KVS associations) use `<Quantity>` + `<Items>`. Exceptions that distilled models as flat arrays: `KeyGroupConfig.Items` is `<Items><PublicKey>…</PublicKey></Items>` (no Quantity), and `RealtimeLogConfig` `Fields` / `EndPoints` are flat `<Field>` / `<EndPoint>` children.
- Publishing a CloudFront Function copies DEVELOPMENT to LIVE and leaves DEVELOPMENT in place so `DescribeFunction(Stage=DEVELOPMENT)` and delete-by-development-ETag keep working.
- VPC origins are immediately `Deployed`. `CreateVpcOrigin` rejects an ELB ARN that does not resolve to a load balancer in Floci's ELBv2 store with `InvalidArgument` (400).
- Key value stores are immediately `READY`. ARNs use `arn:aws:cloudfront::{account}:key-value-store/{id}`.
- `FunctionConfig.KeyValueStoreAssociations` is stored and returned on create/describe/list.
- `DescribeFunction` is `GET /function/{Name}/describe` (AWS path). `GET /function/{Name}` remains `GetFunction`.
- OAI `CallerReference` uniqueness is enforced : duplicate `CallerReference` values return `CloudFrontOriginAccessIdentityAlreadyExists` (409).
- CNAME aliases are globally unique. `AssociateAlias` atomically transfers an alias from its current
  owner to the target distribution. Exact aliases take precedence over the most-specific matching
  wildcard alias.
- Viewer GET/HEAD requests, and OPTIONS requests allowed by the matched cache behavior, addressed to
  an enabled distribution's generated domain or alias are routed to the matching S3 or custom
  origin. Origin forwarding preserves the raw path; custom-origin redirects are not followed.
- Every distribution is also served as `{id}.cloudfront.{host}` for each endpoint host Floci
  resolves: `localhost`, `localhost.floci.io`, `localhost.localstack.cloud`, `FLOCI_HOSTNAME` and
  every `FLOCI_DNS_EXTRA_SUFFIXES` entry. `{id}.cloudfront.localhost.floci.io` and
  `{id}.cloudfront.localhost` reach loopback with no host-file edit and are covered by the generated
  HTTPS certificate, so a signed URL for either can be downloaded over `https://`. See
  [Downloading over HTTPS](#downloading-over-https).
- Origin custom headers are persisted through the CloudFront API and CloudFormation. They replace
  same-named viewer headers on custom-origin GET/HEAD/OPTIONS requests. For in-process S3 origins, a
  configured `Origin` header is used for S3 CORS evaluation. AWS-prohibited names, malformed
  values, inconsistent quantities, duplicates, and quota violations are rejected with modeled
  CloudFront errors when the distribution is created or updated.
- Cache behaviors with enabled `TrustedKeyGroups` require a valid CloudFront signed URL or signed
  cookie before the origin is contacted. Signed URL parameters take precedence over signed cookies.
  Canned and custom policies support SHA-1 or SHA-256 signatures with RSA-2048 or ECDSA P-256 public
  keys. Custom policies enforce resource wildcards, expiration, optional activation time, and
  IPv4 CIDR restrictions. Canned resources compare literally, including query strings. Exact custom
  resources can include one raw query delimiter. As a conservative limitation, other custom
  resources containing a raw `?` fail closed because the character is ambiguous with CloudFront's
  one-character wildcard; custom query-string wildcards are therefore not supported. Invalid or
  expired signatures return 403.
- A key group must contain one to five existing public keys. Public keys that belong to a key group
  and key groups referenced by a cache behavior cannot be deleted until those references are removed.
- Application query parameters are retained when constructing the resource covered by a signature.
  CloudFront signing parameters are excluded from that resource and are never sent to the origin.
- S3-origin reads honor anonymous access, OAI bucket-policy or object-ACL grants, and OAC
  service-principal bucket-policy grants (including the distribution `AWS:SourceArn`) when strict S3
  authentication is enabled. OAC `always`, `never`, and unsigned `no-override` requests follow their
  documented signing behavior; signed `no-override` viewer requests retain their authorization.
- Cache-policy, origin-request-policy, and legacy `ForwardedValues` data-plane evaluation is not
  implemented yet. Viewer query strings therefore follow CloudFront's default behavior and are not
  forwarded to origins.
- Custom origins that resolve to loopback, private, link-local, carrier-grade NAT, or other non-routable addresses are rejected by default. Development-only private origins must be explicitly allowlisted by exact hostname.
- Response headers policies validate the AWS configuration shape and are applied after the origin
  response, including CORS preflight fields, origin override behavior, custom headers, security
  headers, allowed header removals, and sampled `Server-Timing` metrics. `Pragma: server-timing`
  forces those metrics for enabled policies. Distribution writes reject unknown policy IDs, and
  policies attached to a cache behavior cannot be deleted.
- Up to 20 custom response headers policies can be created, and one policy can be associated with
  up to 100 distributions.
- The five AWS managed response headers policy IDs are available and can be selected with
  `ListResponseHeadersPolicies?Type=managed`; `Type` uses the AWS lowercase `managed` or `custom`
  values.

## Configuration

| Property | Env var | Default | Description |
|---|---|---|---|
| `floci.services.cloudfront.enabled` | `FLOCI_SERVICES_CLOUDFRONT_ENABLED` | `true` | Enable or disable the service |
| `floci.services.cloudfront.domain-suffix` | `FLOCI_SERVICES_CLOUDFRONT_DOMAIN_SUFFIX` | `cloudfront.net` | Domain suffix for generated distribution domain names |
| `floci.services.cloudfront.allowed-private-origin-hosts` | `FLOCI_SERVICES_CLOUDFRONT_ALLOWED_PRIVATE_ORIGIN_HOSTS` | `[]` | Exact custom-origin hosts permitted to resolve to private/non-routable addresses (comma-separated in the environment variable) |

## CLI Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# Create a distribution with an S3 origin
aws cloudfront create-distribution --distribution-config '{
  "CallerReference": "ref-1",
  "Enabled": true,
  "Comment": "my distribution",
  "Origins": {
    "Quantity": 1,
    "Items": [{
      "Id": "my-origin",
      "DomainName": "mybucket.s3.amazonaws.com",
      "S3OriginConfig": {"OriginAccessIdentity": ""}
    }]
  },
  "DefaultCacheBehavior": {
    "TargetOriginId": "my-origin",
    "ViewerProtocolPolicy": "redirect-to-https",
    "CachePolicyId": "658327ea-f89d-4fab-a63d-7e88639e58f6",
    "AllowedMethods": {"Quantity": 2, "Items": ["GET","HEAD"]},
    "Compress": true
  }
}'

# Get a distribution
aws cloudfront get-distribution --id E1Z2X3C4V5B6N7

# List distributions
aws cloudfront list-distributions

# Create a cache invalidation
aws cloudfront create-invalidation \
  --distribution-id E1Z2X3C4V5B6N7 \
  --invalidation-batch '{
    "CallerReference": "inv-1",
    "Paths": {"Quantity": 1, "Items": ["/*"]}
  }'

# Create an OAI (Origin Access Identity)
aws cloudfront create-cloud-front-origin-access-identity \
  --cloud-front-origin-access-identity-config \
  "CallerReference=oai-1,Comment=my-oai"

# Create an OAC (Origin Access Control)
aws cloudfront create-origin-access-control \
  --origin-access-control-config '{
    "Name": "my-oac",
    "Description": "",
    "OriginAccessControlOriginType": "s3",
    "SigningBehavior": "always",
    "SigningProtocol": "sigv4"
  }'

# Create a key value store
aws cloudfront create-key-value-store --name my-store --comment "route metadata"

# Create a cache policy
aws cloudfront create-cache-policy --cache-policy-config '{
  "Name": "my-cache-policy",
  "DefaultTTL": 86400,
  "MinTTL": 0,
  "MaxTTL": 31536000,
  "ParametersInCacheKeyAndForwardedToOrigin": {
    "EnableAcceptEncodingGzip": true,
    "EnableAcceptEncodingBrotli": true,
    "HeadersConfig": {"HeaderBehavior": "none"},
    "CookiesConfig": {"CookieBehavior": "none"},
    "QueryStringsConfig": {"QueryStringBehavior": "none"}
  }
}'

# Disable and delete a distribution
ETAG=$(aws cloudfront get-distribution --id E1Z2X3C4V5B6N7 \
  --query 'ETag' --output text)
aws cloudfront update-distribution --id E1Z2X3C4V5B6N7 \
  --if-match "$ETAG" \
  --distribution-config '...(config with Enabled: false)...'
ETAG=$(aws cloudfront get-distribution --id E1Z2X3C4V5B6N7 \
  --query 'ETag' --output text)
aws cloudfront delete-distribution --id E1Z2X3C4V5B6N7 --if-match "$ETAG"
```

## Downloading over HTTPS

A distribution's own domain name (`{id}.cloudfront.net` by default) resolves to nothing local, so
address the distribution by one of its local delivery hostnames instead. `*.cloudfront.localhost.floci.io`
resolves to `127.0.0.1` through public DNS and works on Linux, macOS and in containers.
`*.cloudfront.localhost` needs no DNS at all but only resolves where the runtime handles `.localhost`
itself, which macOS and browsers do and Debian-based images do not.

Start Floci with [TLS](../configuration/tls.md) enabled and trust its CA once:

```bash
docker run -e FLOCI_TLS_ENABLED=true -p 4566:4566 floci/floci:latest
curl -s http://localhost:4566/_floci/ca.pem -o floci-root-ca.pem
```

Sign the URL of the hostname you download from, including its port, and fetch it:

```bash
HOST=e1z2x3c4v5b6n7.cloudfront.localhost.floci.io:4566

SIGNED=$(aws cloudfront sign \
  --url "https://$HOST/hello.txt" \
  --key-pair-id K2JCJMDEHXQW5F \
  --private-key file://private_key.pem \
  --date-less-than 2026-12-31T00:00:00Z)

curl --cacert floci-root-ca.pem "$SIGNED"
```

To drop the `:4566`, publish the HTTPS port Floci also binds when TLS is on (`-p 443:443`, see
`FLOCI_TLS_AWS_HTTPS_PORT`) and sign `https://e1z2x3c4v5b6n7.cloudfront.localhost.floci.io/hello.txt`.

Set `FLOCI_SERVICES_CLOUDFRONT_DOMAIN_SUFFIX=cloudfront.localhost.floci.io` to have
`CreateDistribution` return that hostname as the `DomainName`, so test code can sign the API response
as it is.

## The Emulated Edge

A request whose `Host` is a distribution's domain name (`{Id}.cloudfront.net`)
or one of its alternate domain names : or a request to the distribution's own
[local port](#per-distribution-ports) : is served by the emulated edge:

1. the cache behavior whose path pattern matches is selected;
2. a CloudFront event object is built from the request;
3. the behavior's `viewer-request` function runs;
4. the function's response is returned, or the (possibly rewritten) request is
   forwarded to the resolved origin : including an origin the function chose
   with `cf.updateRequestOrigin()`;
5. the behavior's `viewer-response` function, if any, runs over the result.

```bash
curl -H "Host: E1Z2X3C4V5B6N7.cloudfront.net" http://localhost:4566/index.html
```

An origin that is itself an emulated AWS endpoint (an S3 bucket's virtual host,
a Lambda function URL) is routed back into the emulator with its AWS `Host`
intact, so bucket origins work without extra configuration.

### Per-distribution ports

`{Id}.cloudfront.net` is a real AWS hostname that resolves to nothing on a
developer's machine, so reaching the edge through it needs a `/etc/hosts` entry
and a fight with TLS. Every distribution therefore also gets a plain-HTTP port
of its own, openable in a browser like any other local dev server:

```bash
$ curl -s http://localhost:4566/_floci/cloudfront-edge/E1Z2X3C4V5B6N7
{"DistributionId":"E1Z2X3C4V5B6N7","Port":9500,"Url":"http://localhost:9500"}

$ curl http://localhost:9500/index.html
```

`GET /_floci/cloudfront-edge` lists every assignment. The viewer's `Host` is
passed to the edge untouched, so `event.request.headers.host` is the address
the request actually arrived on.

The emulator assigns ports from a range. A containerized emulator is only
reachable on ports the container publishes, so narrow the range to those with
`FLOCI_SERVICES_CLOUDFRONT_EDGE_PORTS` : a comma-separated list of ports and/or
`from-to` ranges : and publish them (`docker run -p 9500-9519:9500-9519 …`).

| Setting | Env | Default |
|---|---|---|
| Per-distribution ports | `FLOCI_SERVICES_CLOUDFRONT_EDGE_PORTS_ENABLED` | `true` |
| First port of the range | `FLOCI_SERVICES_CLOUDFRONT_EDGE_BASE_PORT` | `9500` |
| Last port of the range | `FLOCI_SERVICES_CLOUDFRONT_EDGE_MAX_PORT` | `9519` |
| Explicit port list (overrides the range) | `FLOCI_SERVICES_CLOUDFRONT_EDGE_PORTS` | unset |

### CloudFront Functions

Functions run in a JavaScript sandbox that matches the CloudFront Functions
runtime rather than Node.js: only the ECMAScript built-ins, `console`, and the
built-in `cloudfront` module (`cf.kvs().get/exists/meta`,
`cf.updateRequestOrigin`, `cf.selectRequestOriginById`) are in scope. There is
no `fetch`, no timer, no `require`/`import`, no `process`, and `eval` and the
`Function` constructor are disabled. Code is always strict and must be within
CloudFront's 10 KB limit. The same runtime backs `TestFunction`, so a local
test result reflects what the edge will do.

The runtime is a `node` child process. Both emulator images ship Node; set
`FLOCI_SERVICES_CLOUDFRONT_FUNCTION_RUNTIME_COMMAND` to point at a different
JavaScript runtime.

| Setting | Env | Default |
|---|---|---|
| Function runtime command | `FLOCI_SERVICES_CLOUDFRONT_FUNCTION_RUNTIME_COMMAND` | `node` |
| Function timeout (ms) | `FLOCI_SERVICES_CLOUDFRONT_FUNCTION_TIMEOUT_MS` | `5000` |
| Max function source bytes | `FLOCI_SERVICES_CLOUDFRONT_FUNCTION_MAX_CODE_BYTES` | `10240` |
| Loopback origin host | `FLOCI_SERVICES_CLOUDFRONT_ORIGIN_LOOPBACK_HOST` | auto-detected |

When the emulator runs in a container, an origin pointing at `localhost` means
the developer's machine, so it resolves to `host.docker.internal` (or the
docker bridge). Run the container with
`--add-host=host.docker.internal:host-gateway` on native Linux Docker.
Custom-origin delivery rejects private/non-routable destinations by default;
explicitly allowlist development origin hostnames with
`FLOCI_SERVICES_CLOUDFRONT_ALLOWED_PRIVATE_ORIGIN_HOSTS` when using that path.

## Not Supported

- Caching, compression and the viewer protocol policy : every request reaches
  the origin on the scheme it arrived on
- `ComputeUtilization` is derived from the emulator's own JS engine and is not
  comparable to the value AWS reports
- Lambda@Edge associations
- Anycast IP lists, distribution tenants, connection groups, trust stores
- CloudFormation provisioning of custom `AWS::CloudFront::ResponseHeadersPolicy` resources
  (literal custom or managed policy IDs are supported on distributions)
- Global CDN propagation
