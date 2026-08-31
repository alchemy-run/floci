# Redshift

**Protocol:** Query (XML)
**Management Endpoint:** `POST http://localhost:4566/` with `Action=` param

Floci emulates Amazon Redshift provisioned clusters as a control-plane service. Clusters become `available` immediately and report a `*.redshift.*` endpoint on port `5439`. No warehouse container is started.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateCluster` | Create a provisioned cluster |
| `DescribeClusters` | List clusters; `ClusterIdentifier` faults with `ClusterNotFound` when missing |
| `ModifyCluster` | Update node type, count, networking, encryption, and maintenance settings |
| `DeleteCluster` | Delete a cluster (`SkipFinalClusterSnapshot` is accepted) |
| `CreateClusterSubnetGroup` | Create a cluster subnet group from existing VPC subnets |
| `DescribeClusterSubnetGroups` | List subnet groups; named lookup faults with `ClusterSubnetGroupNotFoundFault` |
| `ModifyClusterSubnetGroup` | Replace subnet list / description |
| `DeleteClusterSubnetGroup` | Delete a subnet group that is not in use |
| `CreateTags` | Upsert tags on a cluster, subnet group, or parameter group ARN |
| `DeleteTags` | Remove tags from a cluster, subnet group, or parameter group ARN |
| `CreateClusterParameterGroup` | Create a cluster parameter group |
| `DescribeClusterParameterGroups` | List groups; named lookup faults with `ClusterParameterGroupNotFound` |
| `DeleteClusterParameterGroup` | Delete a custom cluster parameter group |
| `ModifyClusterParameterGroup` | Upsert user-sourced parameter values |
| `ResetClusterParameterGroup` | Reset named parameters (or all) to family defaults |
| `DescribeClusterParameters` | List parameters; `Source=user` returns overrides only |
| `CreateClusterSnapshot` | Create a manual snapshot of a cluster |
| `DescribeClusterSnapshots` | List snapshots (empty-ok); identifier lookup faults with `ClusterSnapshotNotFound` |
| `DeleteClusterSnapshot` | Delete a snapshot (`ClusterSnapshotNotFound` when missing) |
| `CopyClusterSnapshot` | Copy a snapshot (`ClusterSnapshotNotFound` when the source is missing) |
| `DescribeEvents` | List recent Redshift events (empty-ok) |
<!-- floci:actions:end -->

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `FLOCI_SERVICES_REDSHIFT_ENABLED` | `true` | Enable or disable Redshift |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

aws redshift create-cluster \
  --cluster-identifier warehouse \
  --node-type ra3.large \
  --master-username admin \
  --master-user-password AlchemyRedshiftTest1 \
  --db-name analytics

aws redshift describe-clusters --cluster-identifier warehouse
aws redshift delete-cluster --cluster-identifier warehouse --skip-final-cluster-snapshot
```
