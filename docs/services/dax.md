# DAX

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonDAXV3.*`)
**Management Endpoint:** `POST http://localhost:4566/`

Amazon DynamoDB Accelerator (DAX) cluster, parameter-group, subnet-group, event, and tag APIs. Clusters become `available` immediately. There is no DAX data-plane cache process — discovery endpoints (`dax://` / `daxs://` on ports 8111 / 9111) are returned for IaC and binding tests.

## Supported Management Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateCluster` | Create a DAX cluster |
| `DescribeClusters` | List clusters; unknown names return `ClusterNotFoundFault` |
| `UpdateCluster` | Update mutable cluster attributes |
| `DeleteCluster` | Delete a cluster |
| `IncreaseReplicationFactor` | Add nodes |
| `DecreaseReplicationFactor` | Remove nodes |
| `RebootNode` | Reboot a cluster node |
| `CreateParameterGroup` | Create a parameter group |
| `DescribeParameterGroups` | List parameter groups |
| `DescribeParameters` | List parameters for a group |
| `DescribeDefaultParameters` | List default DAX parameters |
| `UpdateParameterGroup` | Override parameter values |
| `DeleteParameterGroup` | Delete a parameter group |
| `CreateSubnetGroup` | Create a subnet group |
| `DescribeSubnetGroups` | List subnet groups |
| `UpdateSubnetGroup` | Update a subnet group |
| `DeleteSubnetGroup` | Delete a subnet group |
| `DescribeEvents` | List recent DAX events |
| `ListTags` | List tags on a cluster |
| `TagResource` | Add tags to a cluster |
| `UntagResource` | Remove tags from a cluster |
<!-- floci:actions:end -->

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_DAX_ENABLED` | `true` | Enable or disable the service |
