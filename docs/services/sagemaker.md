# Amazon SageMaker

Floci emulates SageMaker control-plane APIs and runs real Docker containers for local training jobs and hosted endpoints. Containers use the SageMaker `/opt/ml` contract rather than mocks.

## Supported operations

| Area | Operations |
| --- | --- |
| Models | `CreateModel`, `DescribeModel`, `DeleteModel`, `ListModels` |
| Endpoint configs | `CreateEndpointConfig`, `DescribeEndpointConfig`, `DeleteEndpointConfig`, `ListEndpointConfigs` |
| Endpoints | `CreateEndpoint`, `DescribeEndpoint`, `UpdateEndpoint`, `UpdateEndpointWeightsAndCapacities`, `DeleteEndpoint`, `ListEndpoints` |
| Training | `CreateTrainingJob`, `DescribeTrainingJob`, `ListTrainingJobs`, `StopTrainingJob` |
| Tags | `AddTags`, `ListTags`, `DeleteTags` |
| Runtime | `POST /endpoints/{EndpointName}/invocations` |

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateModel` | Registers a model from `PrimaryContainer` or `Containers`; rejects an empty `Containers` array rather than dereferencing it. |
| `DescribeModel` | Returns a model by name, scoped to the calling region. |
| `DeleteModel` | Deletes a model by name, scoped to the calling region. |
| `ListModels` | Lists models for the calling region; supports `NameContains`, `MaxResults` (1-100), and `NextToken` pagination. |
| `CreateEndpointConfig` | Creates an endpoint configuration; every `ProductionVariants[].ModelName` must already exist. |
| `DescribeEndpointConfig` | Returns an endpoint configuration by name, scoped to the calling region. |
| `DeleteEndpointConfig` | Deletes an endpoint configuration by name, scoped to the calling region. |
| `ListEndpointConfigs` | Lists endpoint configurations for the calling region; supports `MaxResults` and `NextToken`. |
| `CreateEndpoint` | Starts hosting the configured model image asynchronously; the endpoint is `Creating` until the container passes its `/ping` check. |
| `DescribeEndpoint` | Returns an endpoint's status, scoped to the calling region; once `InService` it reports `ProductionVariants` (weights, instance counts or serverless config, and `DeployedImages` with the digest-resolved image). |
| `DeleteEndpoint` | Stops and removes the endpoint's container; a start still in flight for this endpoint discards its result instead of resurrecting the deleted record. |
| `ListEndpoints` | Lists endpoints for the calling region; supports `MaxResults` and `NextToken`. |
| `UpdateEndpoint` | Swaps an endpoint to a new endpoint configuration, restarting its container; a superseded in-flight start discards its result. |
| `UpdateEndpointWeightsAndCapacities` | Applies desired weights, instance counts, or serverless concurrency to an `InService` endpoint's variants; the new values take effect immediately. |
| `CreateTrainingJob` | Runs `AlgorithmSpecification.TrainingImage` asynchronously against the `/opt/ml` contract and uploads the resulting model artifacts to S3. |
| `DescribeTrainingJob` | Returns a training job's status, scoped to the calling region. |
| `ListTrainingJobs` | Lists training jobs for the calling region; supports `NameContains`, `StatusEquals`, `MaxResults`, and `NextToken`. |
| `StopTrainingJob` | Stops a training job that is `InProgress` or `Stopping`; a no-op against a job already in a terminal state. |
| `CreateFeatureGroup` | - |
| `DescribeFeatureGroup` | - |
| `UpdateFeatureGroup` | - |
| `DeleteFeatureGroup` | - |
| `ListFeatureGroups` | - |
| `CreateCluster` | Creates a HyperPod cluster; each requested instance becomes a node that runs its group's lifecycle scripts in a container. EKS orchestration requires an `ACTIVE` Floci EKS cluster using the `API` or `API_AND_CONFIG_MAP` authentication mode. |
| `DescribeCluster` | Returns the cluster with each instance group's `CurrentCount` (running nodes), `TargetCount` and status. |
| `UpdateCluster` | Adds, resizes, reconfigures (`InstanceGroups`) and deletes (`InstanceGroupsToDelete`) instance groups and changes `NodeRecovery` and other settings of an `InService` cluster; the cluster is `Updating` until new nodes finish their lifecycle scripts. An instance group's `InstanceType` cannot change. |
| `DeleteCluster` | Deletes a cluster that is not `Creating`, stopping any node still provisioning. |
| `ListClusters` | - |
| `ListClusterNodes` | Lists a cluster's nodes with their `InstanceStatus`; supports `InstanceGroupNameContains`, creation-time filters, sorting and pagination. |
| `DescribeClusterNode` | Returns one node by `NodeId`. |
| `CreateClusterSchedulerConfig` | - |
| `DescribeClusterSchedulerConfig` | - |
| `UpdateClusterSchedulerConfig` | - |
| `DeleteClusterSchedulerConfig` | - |
| `ListClusterSchedulerConfigs` | - |
| `CreateComputeQuota` | - |
| `DescribeComputeQuota` | - |
| `UpdateComputeQuota` | - |
| `DeleteComputeQuota` | - |
| `ListComputeQuotas` | - |
| `AddTags` | Adds tags to a model, endpoint config, endpoint, or training job identified by ARN. |
| `ListTags` | Lists tags for a model, endpoint config, endpoint, or training job identified by ARN. |
| `DeleteTags` | Removes tags by key from a model, endpoint config, endpoint, or training job identified by ARN. |
<!-- floci:actions:end -->

## Training contract

`CreateTrainingJob` starts `AlgorithmSpecification.TrainingImage` with command `train` unless `ContainerEntrypoint`/`ContainerArguments` are supplied. Floci writes SageMaker config files under `/opt/ml/input/config`, downloads channel data from S3 into `/opt/ml/input/data/<channel>`, waits for container exit, and uploads `/opt/ml/model` as `model.tar.gz` under `OutputDataConfig.S3OutputPath/<TrainingJobName>/output/`.

## GPU-backed training

On AWS, `ResourceConfig.InstanceType` *is* the hardware: `ml.g5.xlarge` provisions a machine that physically has one A10G. Floci has one host with whatever cards it has, so a GPU instance type is served by substituting a local device. That substitution is off by default and the SDK request is unchanged either way.

How many accelerators each `ml.*` type carries is a property of AWS, so it ships with Floci in `sagemaker/instance-type-catalog.yaml`. Configuration covers only the local decision of which of this machine's devices may be handed out:

```yaml
floci:
  services:
    sagemaker:
      gpu:
        enabled: true
        mode: cdi            # cdi | device-ids | count
        devices:
          - nvidia.com/gpu=GPU-0e1f2a3b
```

| Setting | Meaning |
| --- | --- |
| `enabled` | Off by default. While off, every training container is CPU-only, exactly as before. |
| `mode` | How the request reaches the daemon. `cdi` names [Container Device Interface](https://github.com/cncf-tags/container-device-interface) devices, `device-ids` uses daemon device ids, and `count` asks for a number and lets the daemon choose. |
| `devices` | The devices Floci may use, for `cdi` and `device-ids`. Unset allows none, so a shared machine does not hand over every GPU by default. Ignored by `count`. |

`mode` defaults to `cdi` because daemons differ: Podman resolves only CDI and will accept a count request while attaching no device ([containers/podman#22645](https://github.com/containers/podman/issues/22645)). Choosing CDI means a misconfiguration fails the container start rather than quietly training on CPU. Use `count` or `device-ids` against Docker.

Floci does not reserve configured devices across concurrent training jobs. In `cdi` and `device-ids` modes, each job takes the first devices it needs from the allowlist, so two simultaneous one-GPU jobs can target the same local card and contend for memory.

A training job fails with an explanatory `FailureReason`, rather than running on CPU, when:

- the instance type belongs to a GPU family (`ml.g*`, `ml.p*`, `ml.inf*`, `ml.trn*`) that the catalog does not list, so its accelerator count is unknown
- fewer devices are allowed than the instance type needs
- `cdi` or `device-ids` is selected with no `devices` configured
- `InstanceCount` is above one, since Floci runs a job as a single container

Only the accelerator is substituted. An instance type also implies vCPU and memory, which Floci does not emulate for any service: EC2 instance types are metadata for `DescribeInstanceTypes` and do not size containers, and RDS records `DBInstanceClass` without acting on it. Resource limits are applied only where an API supplies an explicit number, as Lambda's `MemorySize` does.

## HyperPod clusters

Every instance an instance group asks for becomes a node with an EC2-style instance id. A node whose group has a `LifeCycleConfig` is provisioned the way HyperPod provisions a fresh instance: Floci downloads everything under `SourceS3Uri` to `/tmp/sagemaker-lifecycle`, writes the cluster's `/opt/ml/config/resource_config.json`, and runs `OnCreate` with bash from that directory in a short-lived container. The script's output goes to the log group `/aws/sagemaker/Clusters/<cluster-name>/<cluster-id>`, stream `LifecycleConfig/<instance-group>/<instance-id>`.

- The node is `Running` when the script exits 0. The container is removed afterwards, so an idle cluster holds no containers.
- A script that fails, or runs longer than 20 minutes, fails the node. During `CreateCluster` that fails the cluster with a `FailureMessage`; during an `UpdateCluster` scale-up the failed nodes are dropped, the group's target count rolls back to its running nodes, and the cluster returns to `InService` with a `FailureMessage`.
- Nodes without a `LifeCycleConfig` (allowed for EKS-orchestrated groups) are `Running` at once.
- At most four nodes provision at a time; each container is capped at 512 MiB.

The node image defaults to `public.ecr.aws/docker/library/ubuntu:22.04`, since HyperPod's own images are Ubuntu based. Override it with `floci.services.sagemaker.hyperpod-node-image`.

## Endpoint hosting

`CreateEndpoint` starts the model image as a long-lived Docker container with command `serve`, port `8080`, `/ping` health checks, and `/invocations` runtime proxying. `ModelDataUrl` artifacts are downloaded from S3 and placed in `/opt/ml/model`.

Endpoint containers are CPU-only for now; the `gpu` settings above apply to training jobs.

## Examples

```python
import boto3
sm = boto3.client("sagemaker", endpoint_url="http://localhost:4566", region_name="us-east-1")
sm.create_model(ModelName="m", PrimaryContainer={"Image":"my-image"}, ExecutionRoleArn="arn:aws:iam::000000000000:role/r")
sm.create_endpoint_config(EndpointConfigName="cfg", ProductionVariants=[{"VariantName":"AllTraffic","ModelName":"m","InitialInstanceCount":1,"InstanceType":"ml.t2.medium"}])
sm.create_endpoint(EndpointName="ep", EndpointConfigName="cfg")
```

```bash
aws --endpoint-url=http://localhost:4566 sagemaker list-models
```
