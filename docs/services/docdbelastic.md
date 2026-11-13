# Amazon DocumentDB Elastic Clusters

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/` with the elastic cluster paths (`/cluster`, `/clusters`, `/cluster-snapshot`, `/cluster-snapshots`, `/pending-action`, `/tags`), signed for `docdb-elastic`.

Floci runs elastic clusters on MongoDB: each cluster gets DocumentDB's `mongo` container, served over TLS on its
cluster endpoint, and its data travels through stops, snapshots, copies and restores.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ApplyPendingMaintenanceAction` | Apply, schedule or withdraw a pending maintenance action |
| `CopyClusterSnapshot` | Copy a snapshot and its data, optionally with its tags |
| `CreateCluster` | Create a cluster served by MongoDB in its subnets |
| `CreateClusterSnapshot` | Snapshot an active cluster's data |
| `DeleteCluster` | Delete an active cluster and its automated snapshots |
| `DeleteClusterSnapshot` | Delete a manual snapshot |
| `GetCluster` | Describe a cluster and its shards |
| `GetClusterSnapshot` | Describe a snapshot |
| `GetPendingMaintenanceAction` | Read a cluster's pending maintenance actions |
| `ListClusterSnapshots` | List snapshots, by cluster and type |
| `ListClusters` | List clusters |
| `ListPendingMaintenanceActions` | List clusters with pending maintenance |
| `ListTagsForResource` | List a cluster's or snapshot's tags |
| `RestoreClusterFromSnapshot` | Create a cluster loaded with a snapshot's data |
| `StartCluster` | Start a stopped cluster with its data |
| `StopCluster` | Stop an active cluster, keeping its data |
| `TagResource` | Tag a cluster or snapshot |
| `UntagResource` | Remove tags from a cluster or snapshot |
| `UpdateCluster` | Change shards, network, windows, retention or credentials |
<!-- floci:actions:end -->

## Clusters

A cluster is placed in its subnets, which must share a VPC, with one network interface in each; without
`subnetIds` it takes the default VPC's default subnets in up to three Availability Zones, and without
`vpcSecurityGroupIds` the VPC's default security group. `kmsKeyId` names a KMS key (reported by ARN) or defaults
to `AWS_OWNED_KMS_KEY`. With `authType` `SECRET_ARN`, the admin password is read from the Secrets Manager secret
named (its `password` field, or the whole value).

The cluster endpoint is `<name>-<account>.<region>.docdb-elastic.<floci hostname>` on port 27017. Elastic clusters
take TLS connections only; the listener serves the certificate of floci's RDS certificate authority, shared with
DocumentDB. In DocumentDB's `mock` mode, or with no reachable Docker daemon, clusters are metadata only.

A cluster is `CREATING`, then `ACTIVE`. Updates put it in `UPDATING`, or `SPLITTING` / `MERGING` when the shard
count changes; a new admin password is set in its MongoDB. `StopCluster` dumps its data and stops the container
(`STOPPING`, then `STOPPED`); `StartCluster` starts it and loads the dump (`STARTING`, then `ACTIVE`). Only an
`ACTIVE` cluster can be updated, snapshotted, stopped or deleted; a stopped cluster is started before it is deleted.

## Snapshots

A cluster's first automated snapshot is taken when it becomes `ACTIVE`; automated snapshots go with their cluster.
A manual snapshot holds a `mongodump` of the cluster's databases, which `CopyClusterSnapshot` copies and
`RestoreClusterFromSnapshot` loads into a new cluster with the snapshot's admin user, shard count and network
unless the request names others. Snapshot names are unique in a Region. A copy or restore from a snapshot the
caller cannot see is refused with `AccessDeniedException`, as AWS refuses it.

Pending maintenance actions are reported per cluster; floci schedules none, so applying one is refused.

## Configuration

```yaml
floci:
  services:
    docdb-elastic:
      enabled: true
      transition-millis: 2000   # how long a cluster or snapshot stays in a transitional status
```
