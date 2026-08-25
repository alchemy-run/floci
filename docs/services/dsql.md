# Aurora DSQL

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements Amazon Aurora DSQL cluster and CDC stream management for local SDK and Alchemy workflows. Clusters and streams become `ACTIVE` immediately (the live AWS `CREATING` window is not simulated). Resources are isolated by account and region and use the configured Floci storage mode.

The public Postgres-wire endpoint `{clusterId}.dsql.{region}.on.aws:5432` is emulated with a shared Postgres container and an IAM-token auth proxy (skipped when `FLOCI_SERVICES_DSQL_MOCK=true`). Tokens are host-only SigV4 presigned URLs with `Action=DbConnect` or `Action=DbConnectAdmin`.

Tag APIs share `/tags/{arn}` and are dispatched by the shared tag controller.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateCluster` | `POST /cluster` | Create a cluster (immediately `ACTIVE`) |
| `GetCluster` | `GET /cluster/{identifier}` | Return a cluster, including tags |
| `ListClusters` | `GET /cluster` | List cluster summaries |
| `UpdateCluster` | `POST /cluster/{identifier}` | Update deletion protection |
| `DeleteCluster` | `DELETE /cluster/{identifier}` | Delete a cluster (fails while streams exist) |
| `GetClusterPolicy` | `GET /cluster/{identifier}/policy` | Return the resource-based cluster policy |
| `PutClusterPolicy` | `POST /cluster/{identifier}/policy` | Attach or replace the cluster policy |
| `DeleteClusterPolicy` | `DELETE /cluster/{identifier}/policy` | Remove the cluster policy |
| `GetVpcEndpointServiceName` | `GET /clusters/{identifier}/vpc-endpoint-service-name` | Return the PrivateLink service name |
| `CreateStream` | `POST /stream/{clusterIdentifier}` | Create a CDC stream targeting Kinesis |
| `GetStream` | `GET /stream/{clusterIdentifier}/{streamIdentifier}` | Return a stream, including tags and target |
| `ListStreams` | `GET /stream/{clusterIdentifier}` | List streams on a cluster |
| `DeleteStream` | `DELETE /stream/{clusterIdentifier}/{streamIdentifier}` | Delete a stream |
| `TagResource` | `POST /tags/{resourceArn}` | Add tags to a cluster or stream |
| `UntagResource` | `DELETE /tags/{resourceArn}?tagKeys=` | Remove tags |
| `ListTagsForResource` | `GET /tags/{resourceArn}` | List tags |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_DSQL_ENABLED` | `true` | Enable or disable Aurora DSQL |
| `FLOCI_SERVICES_DSQL_MOCK` | `false` | Control-plane only — no Postgres container or IAM proxy |
| `FLOCI_SERVICES_DSQL_PROXY_PORT` | `5432` | Host port of the DSQL Postgres IAM proxy |
| `FLOCI_STORAGE_SERVICES_DSQL_MODE` | *(inherits global)* | Optional DSQL storage-mode override |
| `FLOCI_STORAGE_SERVICES_DSQL_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws dsql create-cluster --no-deletion-protection-enabled
aws dsql list-clusters
```
