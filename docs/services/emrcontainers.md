# Amazon EMR on EKS

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/` with the EMR on EKS paths (`/virtualclusters`, `/jobtemplates`, `/securityconfigurations`, `/tags`), signed for `emr-containers`.

Floci runs EMR on EKS's control plane over floci EKS clusters: virtual clusters on cluster namespaces, job runs and
the job templates they start from, interactive managed endpoints, security configurations and tags. In the
`docker` runner mode a job run's Spark driver runs in a container.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CancelJobRun` | Cancel a job run that has not ended |
| `CreateJobTemplate` | Create a job template with parameters |
| `CreateManagedEndpoint` | Create an interactive endpoint on a virtual cluster |
| `CreateSecurityConfiguration` | Create a security configuration |
| `CreateVirtualCluster` | Register a virtual cluster on an EKS cluster's namespace |
| `DeleteJobTemplate` | Delete a job template |
| `DeleteManagedEndpoint` | Terminate a managed endpoint |
| `DeleteSecurityConfiguration` | Delete an unused security configuration |
| `DeleteVirtualCluster` | Terminate a virtual cluster, its job runs and endpoints |
| `DescribeJobRun` | Describe a job run |
| `DescribeJobTemplate` | Describe a job template |
| `DescribeManagedEndpoint` | Describe a managed endpoint |
| `DescribeSecurityConfiguration` | Describe a security configuration |
| `DescribeVirtualCluster` | Describe a virtual cluster |
| `GetManagedEndpointSessionCredentials` | Get a token for a managed endpoint |
| `ListJobRuns` | List a virtual cluster's job runs |
| `ListJobTemplates` | List job templates |
| `ListManagedEndpoints` | List a virtual cluster's managed endpoints |
| `ListSecurityConfigurations` | List security configurations |
| `ListTagsForResource` | List a resource's tags |
| `ListVirtualClusters` | List virtual clusters |
| `StartJobRun` | Start a job run, from a template or a request |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
| `UpdateVirtualCluster` | Change a virtual cluster's scheduler limits |
<!-- floci:actions:end -->

## Virtual clusters

A virtual cluster registers one namespace (`default` unless `eksInfo` names one) of an ACTIVE EKS cluster in the
same Region; a namespace has at most one RUNNING virtual cluster. The first registration creates EMR on EKS's
service-linked role, and on a cluster that authenticates with access entries (`API` or `API_AND_CONFIG_MAP`) adds
the role's access entry, user `emr-containers`, as AWS's integration does. A virtual cluster whose EKS cluster is
deleted is `ARRESTED`. Deleting a virtual cluster cancels its job runs and terminates its endpoints; it stays
listed as `TERMINATED`. A scheduler configuration queues runs beyond `maxConcurrentJobRuns` and refuses runs
beyond `maxInQueueJobRuns`.

## Job runs and templates

A job run takes its execution role, release and driver from the request or from a job template, whose
`${parameter}` placeholders are filled from `jobTemplateParameters` or the parameters' defaults; a NUMBER parameter
must be a number. The execution role must exist, and the release must be an EMR on EKS release
(`emr-<major>.<minor>.<patch>[-latest|-<yyyymmdd>]`, 5.32.0 on). Each state change publishes an
`EMR Job Run State Change` event from `aws.emr-containers`.

In the default `immediate` runner mode a run goes `PENDING`, `SUBMITTED`, `RUNNING` and `COMPLETED`, a transition
apart. In the `docker` mode a `sparkSubmitJobDriver` run's `spark-submit` runs in a container of the release's EMR
image through the runner EMR Serverless uses, with the run's execution role, its `spark-defaults`, its
`spark.kubernetes.driverEnv.*` variables and its S3 and CloudWatch monitoring locations; it completes when its
driver exits 0 and is retried up to its retry policy's `maxAttempts` otherwise. `spark.kubernetes.container.image`
names a custom image. Spark SQL drivers run in the immediate mode only.

Job templates are tagged when they are created; TagResource and UntagResource refuse a job template's ARN, as AWS
does.

## Configuration

```yaml
floci:
  services:
    emr-containers:
      enabled: true
      transition-millis: 2000   # how long a job run stays in each state in the immediate mode
      runner-mode: immediate     # or docker
      # image-template: "registry.internal/emr/spark/%s:latest"
      # docker-network: my-network
```
