# Amazon OpenSearch Serverless

**Protocol:** JSON 1.0
**Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: OpenSearchServerless.<Operation>`, signed for `aoss`

Floci runs each collection on its own OpenSearch container and serves it at the collection's endpoint, checking the
collection's network and data access policies on every request.

## Collections

A collection needs an encryption policy that covers its name (`collection/<pattern>`); without one,
`CreateCollection` is a `ValidationException`. It's `CREATING` while its OpenSearch starts, then `ACTIVE`, with the
endpoint `http://<id>.<region>.aoss.localhost.floci.io:4566` (or `<base-url>/_aoss-data/<region>/<id>` when Floci isn't
reached on localhost) and a dashboard endpoint beneath it. Deletion protection blocks `DeleteCollection`. Collections
can join a collection group, which can't be deleted while it has collections. Collections, collection groups and
ingestion jobs and streams take tags.

## Access

Requests to a collection's endpoint must be signed for `aoss`. They reach the collection when a network policy covers
it, and the caller's principal (its IAM user or role, or the account root) is granted, by a data access policy, the
permission the request needs: `aoss:ReadDocument` to read and search, `aoss:WriteDocument` to index and delete
documents, `aoss:CreateIndex`, `aoss:DescribeIndex`, `aoss:UpdateIndex` and `aoss:DeleteIndex` for indexes. The index
APIs (`CreateIndex`, `GetIndex`, `UpdateIndex`, `DeleteIndex`) need the same. Network policies with only
`SourceVPCEs` or `SourceServices` are honored as reaching the collection.

## Policies and configurations

Encryption, network, data access and retention policies are checked as OpenSearch Serverless checks them: their rules'
resource types and patterns, permissions that suit their resource types, and retention periods from 24 hours to 3,650
days. Each change gets a new `policyVersion`, and updating with an older version is a `ConflictException`.
`BatchGetEffectiveLifecyclePolicy` answers the most specific retention rule that matches an index. Security
configurations (SAML, IAM Identity Center, IAM federation) are kept with versions too. `GetPoliciesStats` counts them.

## Ingestion

An ingestion job indexes the documents of every object under its S3 prefix (a JSON array, a JSON object, or
newline-delimited JSON) into its index, and records what it couldn't index in its failure index; it counts the objects
discovered, ingested and failed. An ingestion stream does the same for the objects its SQS queue announces, for the
buckets it names.

## VPC endpoints and account settings

VPC endpoints name subnets of their VPC and existing security groups; they're `PENDING`, then `ACTIVE`. Account
settings hold the indexing and search capacity limits.

## Configuration

```yaml
floci:
  services:
    opensearch-serverless:
      enabled: true
      engine: opensearch                         # or none: collections keep their metadata but serve nothing
      image: opensearchproject/opensearch:2.19.5
      heap-mb: 512
      ready-seconds: 180
      stream-poll-seconds: 5
      transition-millis: 2000
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `BatchGetCollection` | Describe collections |
| `BatchGetCollectionGroup` | Describe collection groups |
| `BatchGetEffectiveLifecyclePolicy` | Find indexes' effective retention |
| `BatchGetLifecyclePolicy` | Describe lifecycle policies |
| `BatchGetVpcEndpoint` | Describe VPC endpoints |
| `CancelIngestionJob` | Cancel an ingestion job |
| `CreateAccessPolicy` | Create an access policy |
| `CreateCollection` | Create a collection on OpenSearch |
| `CreateCollectionGroup` | Create a collection group |
| `CreateIndex` | Create an index in a collection |
| `CreateIngestionJob` | Index the documents under an S3 prefix |
| `CreateIngestionStream` | Index the objects a queue announces |
| `CreateLifecyclePolicy` | Create a lifecycle policy |
| `CreateSecurityConfig` | Create a security config |
| `CreateSecurityPolicy` | Create a security policy |
| `CreateVpcEndpoint` | Create a VPC endpoint |
| `DeleteAccessPolicy` | Delete an access policy |
| `DeleteCollection` | Delete a collection |
| `DeleteCollectionGroup` | Delete a collection group |
| `DeleteIndex` | Delete an index |
| `DeleteIngestionStream` | Delete an ingestion stream |
| `DeleteLifecyclePolicy` | Delete a lifecycle policy |
| `DeleteSecurityConfig` | Delete a security config |
| `DeleteSecurityPolicy` | Delete a security policy |
| `DeleteVpcEndpoint` | Delete a VPC endpoint |
| `GetAccessPolicy` | Describe an access policy |
| `GetAccountSettings` | Read capacity limits |
| `GetIndex` | Read an index's schema |
| `GetIngestionJob` | Describe an ingestion job |
| `GetIngestionStream` | Describe an ingestion stream |
| `GetPoliciesStats` | Count policies and configurations |
| `GetSecurityConfig` | Describe a security config |
| `GetSecurityPolicy` | Describe a security policy |
| `ListAccessPolicies` | List access policies |
| `ListCollectionGroups` | List collection groups |
| `ListCollections` | List collections |
| `ListIngestionJobs` | List ingestion jobs |
| `ListIngestionStreams` | List ingestion streams |
| `ListLifecyclePolicies` | List lifecycle policies |
| `ListSecurityConfigs` | List security configs |
| `ListSecurityPolicies` | List security policies |
| `ListTagsForResource` | List a resource's tags |
| `ListVpcEndpoints` | List VPC endpoints |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
| `UpdateAccessPolicy` | Update an access policy |
| `UpdateAccountSettings` | Set capacity limits |
| `UpdateCollection` | Update a collection |
| `UpdateCollectionGroup` | Update a collection group |
| `UpdateIndex` | Update an index's mappings and settings |
| `UpdateLifecyclePolicy` | Update a lifecycle policy |
| `UpdateSecurityConfig` | Update a security config |
| `UpdateSecurityPolicy` | Update a security policy |
| `UpdateVpcEndpoint` | Update a VPC endpoint |
<!-- floci:actions:end -->
