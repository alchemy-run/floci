# Amazon DynamoDB Accelerator (DAX)

**Protocol:** JSON 1.1
**Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: AmazonDAXV3.<Operation>`, signed for `dax`.

Floci runs the DAX control plane: clusters and their nodes, parameter groups, subnet groups, events and tags.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateCluster` | Create a cluster of 1 to 11 nodes in a subnet group, with an IAM role DAX can assume |
| `DescribeClusters` | Describe clusters by name, or page through all of them |
| `UpdateCluster` | Change an available cluster's description, maintenance window, notifications, parameter group or security groups |
| `DeleteCluster` | Delete a cluster; it is `deleting` until the transition elapses |
| `IncreaseReplicationFactor` | Add nodes, optionally in named Availability Zones |
| `DecreaseReplicationFactor` | Remove named nodes, nodes in named zones, or the newest nodes |
| `RebootNode` | Reboot a node, applying pending parameter changes to it |
| `ListTags` | List a cluster's tags by ARN |
| `TagResource` | Add or replace a cluster's tags |
| `UntagResource` | Remove tags from a cluster |
| `CreateParameterGroup` | Create a parameter group |
| `DescribeParameterGroups` | Describe parameter groups, including `default.dax1.0` |
| `UpdateParameterGroup` | Change `query-ttl-millis` or `record-ttl-millis`; clusters using the group need a reboot |
| `DeleteParameterGroup` | Delete a parameter group no cluster uses |
| `DescribeParameters` | List a group's parameters, filtered by `system` or `user` source |
| `DescribeDefaultParameters` | List the parameters' default values |
| `CreateSubnetGroup` | Create a subnet group over EC2 subnets in one VPC |
| `DescribeSubnetGroups` | Describe subnet groups by name, or page through all of them |
| `UpdateSubnetGroup` | Change a subnet group's description or subnets |
| `DeleteSubnetGroup` | Delete a subnet group no cluster uses |
| `DescribeEvents` | List cluster, parameter group and subnet group events, newest first |
<!-- floci:actions:end -->

## Clusters

A cluster needs a node type DAX offers (`dax.t3.small`, `dax.r5.large`, …), a replication factor of 1 to 11 and
an IAM role that trusts `dax.amazonaws.com`. A role that does not exist or does not trust DAX fails with
`InvalidParameterValueException: No permission to assume role`, as on AWS.

Nodes are named `<cluster>-a`, `<cluster>-b`, … and spread across the subnet group's Availability Zones, or
placed in the zones `AvailabilityZones` lists. The discovery endpoint is
`<cluster>.<id>.dax-clusters.<region>.<dns-suffix>` on port 8111 (`dax://`), or 9111 (`daxs://`) with
`ClusterEndpointEncryptionType: TLS`; each node has its own endpoint under `nodes.dax-clusters`. A cluster
created without a subnet group uses the `default` group, which Floci builds from the default VPC's
default subnets. Without `SecurityGroupIds` a cluster gets the VPC's default security group.

Changes are asynchronous, as on AWS. A new cluster and its nodes are `creating`, a resize is `modifying`, a
rebooted node is `rebooting` and a deleted cluster is `deleting` until
`floci.services.dax.transition-millis` (default `2000`) has passed; clusters and nodes then report
`available`, and a deleted cluster is gone. `UpdateCluster`, resizing, `RebootNode` and tagging require an
`available` cluster and otherwise fail with `InvalidClusterStateFault`.

`IncreaseReplicationFactor` adds nodes; `DecreaseReplicationFactor` removes the nodes named in
`NodeIdsToRemove`, one node per zone in `AvailabilityZones`, or the newest nodes. A node quota of 11 per
cluster and 50 per account applies, with 50 clusters per account.

Tags apply to clusters, by ARN (`arn:aws:dax:<region>:<account>:cache/<name>`). `TagResource` and
`UntagResource` return the cluster's tags; removing a key the cluster does not have fails with
`TagNotFoundFault`.

## Parameter groups

DAX has two parameters, `query-ttl-millis` and `record-ttl-millis`, both defaulting to `300000`. The
`default.dax1.0` group cannot be modified or deleted. Changing a group's values, or moving a cluster to
another group, marks the cluster's nodes `pending-reboot` (`NodeIdsToReboot`); each node picks the change up
when it is rebooted, and the cluster returns to `in-sync` once every node has. `DescribeParameters` filters by
`Source`: `system` for default values, `user` for changed ones.

## Subnet groups

A subnet group's subnets must exist in EC2 and share one VPC (`InvalidSubnet` otherwise), up to 20 per group.
`SupportedNetworkTypes` is `ipv4`, plus `ipv6` and `dual_stack` when every subnet has an IPv6 block; a cluster's
`NetworkType` must be one of them. A group in use cannot be deleted (`SubnetGroupInUseFault`), and an update
cannot drop the zone of a node in use (`SubnetInUse`).

## Events and notifications

Creating, resizing, rebooting, updating and deleting clusters, and changes to parameter and subnet groups,
record events for 14 days. `DescribeEvents` returns the last hour by default, newest first, filtered by
`SourceName` and `SourceType`, `StartTime` and `EndTime`, or `Duration` in minutes. A cluster with an
`active` `NotificationTopicArn` publishes each of its events to that SNS topic.

## Not emulated

The DAX data plane, the cluster protocol on ports 8111 and 9111 that DAX clients use for item and query
caching, is not served: clients should talk to DynamoDB directly in Floci.
