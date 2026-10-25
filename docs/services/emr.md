# EMR

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`
**Target prefix:** `X-Amz-Target: ElasticMapReduce.*`

Floci emulates the Amazon EMR (Elastic MapReduce) management API. Clusters (job flows), instance groups and fleets, steps, security configurations, and tags are tracked in Floci's storage backend so the AWS CLI and SDK clients can drive the full cluster lifecycle locally. EMR does not launch real Hadoop/Spark clusters — clusters transition through their lifecycle states as a state machine.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `RunJobFlow` | Creates a new cluster (job flow) and returns its `JobFlowId` |
| `DescribeCluster` | Returns the configuration and status of a cluster |
| `ListClusters` | Lists clusters, filterable by state and creation time |
| `TerminateJobFlows` | Terminates one or more clusters |
| `SetTerminationProtection` | Enables or disables termination protection |
| `SetVisibleToAllUsers` | Sets cluster visibility for the account |
| `SetKeepJobFlowAliveWhenNoSteps` | Controls auto-termination when steps complete |
| `SetUnhealthyNodeReplacement` | Toggles unhealthy-node replacement |
| `ModifyCluster` | Updates cluster-level settings such as step concurrency |
| `AddJobFlowSteps` | Adds one or more steps to a cluster |
| `DescribeStep` | Returns the detail and status of a single step |
| `ListSteps` | Lists the steps of a cluster, filterable by state |
| `CancelSteps` | Cancels pending steps |
| `AddInstanceGroups` | Adds instance groups to a running cluster |
| `ListInstanceGroups` | Lists the instance groups of a cluster |
| `AddInstanceFleet` | Adds an instance fleet to a cluster |
| `ListInstanceFleets` | Lists the instance fleets of a cluster |
| `ListInstances` | Lists the EC2 instances of a cluster |
| `ModifyInstanceGroups` | Resizes instance groups of a cluster |
| `ModifyInstanceFleet` | Changes the target capacity of an instance fleet |
| `ListBootstrapActions` | Lists the bootstrap actions a cluster ran at launch |
| `PutAutoTerminationPolicy` | Attaches or updates a cluster's auto-termination policy |
| `GetAutoTerminationPolicy` | Returns a cluster's auto-termination policy |
| `RemoveAutoTerminationPolicy` | Detaches a cluster's auto-termination policy |
| `PutManagedScalingPolicy` | Attaches or updates a cluster's managed scaling policy |
| `GetManagedScalingPolicy` | Returns a cluster's managed scaling policy |
| `RemoveManagedScalingPolicy` | Detaches a cluster's managed scaling policy |
| `CreateSecurityConfiguration` | Creates a named security configuration |
| `DescribeSecurityConfiguration` | Returns a security configuration |
| `DeleteSecurityConfiguration` | Deletes a security configuration |
| `ListSecurityConfigurations` | Lists all security configurations |
| `ListReleaseLabels` | - |
| `DescribeReleaseLabel` | - |
| `ListSupportedInstanceTypes` | - |
| `CreateStudio` | - |
| `DescribeStudio` | - |
| `ListStudios` | - |
| `UpdateStudio` | - |
| `DeleteStudio` | - |
| `AddTags` | Adds tags to a cluster |
| `RemoveTags` | Removes tags from a cluster |
| `ModifyInstanceGroups` | Resizes or reconfigures instance groups |
| `ModifyInstanceFleet` | Changes an instance fleet's target capacities |
| `ListBootstrapActions` | Lists the bootstrap actions given to `RunJobFlow` |
| `PutManagedScalingPolicy` | Sets a cluster's managed scaling policy |
| `GetManagedScalingPolicy` | Returns a cluster's managed scaling policy |
| `RemoveManagedScalingPolicy` | Removes a cluster's managed scaling policy |
| `PutAutoTerminationPolicy` | Sets a cluster's idle timeout auto termination policy |
| `GetAutoTerminationPolicy` | Returns a cluster's auto termination policy |
| `RemoveAutoTerminationPolicy` | Removes a cluster's auto termination policy |
| `PutAutoScalingPolicy` | Attaches an automatic scaling policy to a core or task instance group |
| `RemoveAutoScalingPolicy` | Removes an instance group's automatic scaling policy |
| `ListReleaseLabels` | List release labels, newest first, by prefix or application; see [Release catalogue](#release-catalogue) |
| `DescribeReleaseLabel` | A release's applications and Amazon Linux releases (the latest release without a label) |
| `ListSupportedInstanceTypes` | The instance types a release supports, with vCPU, memory, storage and architecture |
| `GetBlockPublicAccessConfiguration` | Returns the account's block public access configuration for the Region |
| `PutBlockPublicAccessConfiguration` | Sets the account's block public access configuration for the Region |
<!-- floci:actions:end -->

## Cluster lifecycle

Steps complete immediately in this metadata-only emulator. A cluster created with
`Instances.KeepJobFlowAliveWhenNoSteps` set to `false` terminates after its initial steps or
steps added later complete, reporting `ALL_STEPS_COMPLETED`. A cluster with no steps remains
`WAITING` until steps are added. Set `KeepJobFlowAliveWhenNoSteps` to `true` to keep the cluster
`WAITING` after its steps complete. Automatic step-completion termination also applies when
termination protection is enabled.
`AddJobFlowSteps` rejects a terminated cluster without adding steps or changing its
termination status, and answers an unknown cluster with `ValidationException` "Specified job flow ID
does not exist.", as AWS does.
After automatic or manual termination, instance groups and fleets report `TERMINATED` with zero
running or provisioned capacity; their requested and target capacities remain recorded.
`ListInstances` retains each group's instances with their original synthetic IDs and `TERMINATED`
status for 30 days after termination. Capacity modifications and new groups or fleets are rejected
on terminated clusters. As on AWS, `ModifyInstanceGroups` answers `ValidationException` "An instance
group may only be modified when the cluster is running or waiting.", and `ModifyInstanceFleet` answers
`InvalidRequestException` "A job flow that is shutting down, terminated, or finished may not be
modified." with `ErrorCode` `INVALID_JOB_SHUT_DOWN`. `InstanceStates` filters the modeled `RUNNING` and
`TERMINATED` instances.

`TerminateJobFlows`, the four `Set*` cluster setters and `CancelSteps` refuse a request without a
member their model requires (`JobFlowIds` and the setting, or `StepIds`) with `ValidationException`
"1 validation error detected: Value null at 'jobFlowIds' failed to satisfy constraint: Member must
not be null", AWS's framework wording, as `RunJobFlow` does for `Name`. They used to read a missing
list as empty and a missing setting as its default, and answer success. An empty `JobFlowIds` is
still accepted.

A state machine drives clusters and steps through the `elasticmapreduce:` integrations, `.sync`
included; see [Step Functions: Amazon EMR tasks](step-functions.md#amazon-emr-tasks).

## Release catalogue

`ListReleaseLabels`, `DescribeReleaseLabel` and `ListSupportedInstanceTypes` read a curated catalogue,
not AWS's full list: `emr-7.5.0` (Floci's default), `emr-7.4.0`, `emr-6.15.0`, `emr-6.14.0` and
`emr-5.36.2`. Each release's applications and versions come from its page in the Amazon EMR Release
Guide, with names lowercased and AWS's `-amzn-N` build suffix dropped, as the API reports them, and
its two most recent Amazon Linux releases. A `floci.services.emr.default-release-label` outside the
catalogue is still listed, with no applications.

`ListReleaseLabels` filters by `Prefix` (such as `emr-6`) and `Application` (`spark` or
`spark@3.4.1`, matched without regard to case) and pages with `MaxResults` (1 to 100) /
`NextToken`; a token only works with the filters it was issued for. `DescribeReleaseLabel` answers
every application at once, since AWS reserves its `MaxResults` and `NextToken` for future use.
`ListSupportedInstanceTypes` reports common families (c5, m5, m5d, m6g, r5, r6g) with
their EC2 sizes. An unknown release label, a bad token or an out-of-range `MaxResults` is
`InvalidRequestException`. `RunJobFlow` does not check its release label or instance types against
the catalogue.

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a cluster
CLUSTER_ID=$(aws emr run-job-flow \
  --name "demo-cluster" \
  --release-label emr-7.0.0 \
  --instances KeepJobFlowAliveWhenNoSteps=true,InstanceGroups='[{InstanceCount=1,InstanceGroupType=MASTER,InstanceType=m5.xlarge}]' \
  --query 'JobFlowId' --output text)

# Inspect it
aws emr describe-cluster --cluster-id "$CLUSTER_ID"
aws emr list-clusters

# Add a step
aws emr add-steps --cluster-id "$CLUSTER_ID" \
  --steps Type=CUSTOM_JAR,Name=demo,Jar=command-runner.jar,Args=[echo,hello]

# Terminate
aws emr terminate-clusters --cluster-ids "$CLUSTER_ID"
```

`aws emr add-steps --execution-role-arn` applies the role to every step in that request.
`RunJobFlow` also applies `StepExecutionRoleArn` to its initial steps. `describe-step`
includes `ExecutionRoleArn` for those steps; `list-steps` returns step summaries without it.
