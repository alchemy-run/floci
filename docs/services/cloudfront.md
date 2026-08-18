# CloudFront

CloudFront management-plane emulation. Supports distribution lifecycle, cache policies, origin request policies, response headers policies, origin access controls, origin access identities, CloudFront Functions, invalidations, and tagging. Actual content delivery is not emulated — this is a management-plane-only implementation.

**Protocol:** REST XML  
**API version:** `2020-05-31`  
**Endpoint prefix:** `cloudfront`  
**Namespace:** `http://cloudfront.amazonaws.com/doc/2020-05-31/`  
**Global service** — ARNs contain no region segment.

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

### Origin Access Identity (OAI — legacy)

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
- Distribution domain names follow the pattern `{id}.cloudfront.net`.
- ARNs are global — no region segment: `arn:aws:cloudfront::{accountId}:distribution/{id}`.
- Invalidations are immediately marked `Completed`.
- `DeleteDistribution` returns `DistributionNotDisabled` (409) if `Enabled` is `true` in the config.
- All mutating operations (`PUT`, `DELETE`) require an `If-Match` header containing the current `ETag`. A missing or incorrect `ETag` returns `InvalidIfMatchVersion` (400).
- All `GET` and `POST` (create) responses include an `ETag` response header.
- All list-type sub-elements in XML follow CloudFront's `<Quantity>N</Quantity><Items>...</Items>` wrapper pattern.
- OAI `CallerReference` uniqueness is enforced — duplicate `CallerReference` values return `CloudFrontOriginAccessIdentityAlreadyExists` (409).
- `AssociateAlias` attaches a CNAME alias to the target distribution's config.
- VPC origins are immediately `Deployed`. `CreateVpcOrigin` rejects an ELB ARN that does not resolve to a load balancer in Floci's ELBv2 store with `InvalidArgument` (400).
- Key value stores are immediately `READY`. ARNs use `arn:aws:cloudfront::{account}:key-value-store/{id}`.
- `FunctionConfig.KeyValueStoreAssociations` is stored and returned on create/describe/list.
- `DescribeFunction` is `GET /function/{Name}/describe` (AWS path). `GET /function/{Name}` remains `GetFunction`.
- Cache / origin-request / response-headers policy configs are stored as the submitted XML subtree and echoed on get/list so TTL, CORS, and header settings round-trip.

## Configuration

| Property | Env var | Default | Description |
|---|---|---|---|
| `floci.services.cloudfront.enabled` | `FLOCI_SERVICES_CLOUDFRONT_ENABLED` | `true` | Enable or disable the service |
| `floci.services.cloudfront.domain-suffix` | `FLOCI_SERVICES_CLOUDFRONT_DOMAIN_SUFFIX` | `cloudfront.net` | Domain suffix for generated distribution domain names |

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

## Not Supported

- `TestFunction` execution (function is stored, not executed)
- Anycast IP lists, distribution tenants, connection groups, trust stores
- CloudFront KeyValueStore **data plane** (`cloudfront-keyvaluestore`: `GetKey` / `PutKey` / `DeleteKey` / `UpdateKeys` / `ListKeys`) — separate service
- Actual CDN content delivery and caching
