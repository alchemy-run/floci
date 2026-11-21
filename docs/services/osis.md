# Amazon OpenSearch Ingestion

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/2022-01-01/osis/...`, signed for `osis`

Floci runs each pipeline's configuration on its own Data Prepper container, so events posted to a pipeline's ingest
endpoint are processed and written to the sinks it names.

## Pipelines

A pipeline is `CREATING` while its Data Prepper starts, then `ACTIVE`. If Data Prepper can't run the configuration, the
pipeline is `CREATE_FAILED` and its status reason carries Data Prepper's error. `UpdatePipeline` restarts it with the
new configuration (`UPDATING`, then `ACTIVE` or `UPDATE_FAILED`). `StopPipeline` removes its container (`STOPPING`,
then `STOPPED`) and `StartPipeline` starts it again. A pipeline in a transitional state rejects updates, stops and
deletes with a `ConflictException`. `GetPipelineChangeProgress` reports the stages of the latest change.

The configuration is Data Prepper's YAML. `CreatePipeline`, `UpdatePipeline` and `ValidatePipeline` check that every
sub-pipeline has one source and at least one sink, that each plugin is one OpenSearch Ingestion runs, that `pipeline`
sources and sinks name defined sub-pipelines, and that no sub-pipeline configures a buffer. Every role a plugin names
in `aws.sts_role_arn`, and the `PipelineRoleArn`, must exist and trust `osis-pipelines.amazonaws.com`. A plugin that
reaches AWS without a role needs the pipeline's `PipelineRoleArn`.

The pipeline's Data Prepper holds a session for the pipeline's role (the `PipelineRoleArn`, or the first role its
plugins name), so its S3 and OpenSearch requests are made as that role. Its SDK calls go to Floci. OpenSearch sinks
that name a Floci domain endpoint write to that domain. With `LogPublishingOptions`, Data Prepper's log goes to the
log group, which must start with `/aws/vendedlogs/`, in the stream `pipeline/<name>`.

`Destinations` lists the OpenSearch domains, collections and S3 buckets the sinks write to. Pipelines take tags.

## Ingest endpoints

A pipeline with an HTTP or OpenTelemetry source has an ingest endpoint,
`http://<name>-<id>.<region>.osis.localhost.floci.io:4566`. Requests must be signed for `osis`; each is forwarded to
the source whose `path` it names (with `${pipelineName}` replaced), and the source's answer is returned. A request to
a pipeline that isn't `ACTIVE` or `UPDATING` gets a 503. When Floci isn't reached on localhost, the endpoint is
`<base-url>/_osis-ingest/<region>/<name>-<id>`.

## VPC pipelines and pipeline endpoints

A pipeline with `VpcOptions` names subnets of one VPC and their security groups; with `SERVICE` endpoint management it
reports a VPC endpoint in that VPC, and with `CUSTOMER` a VPC endpoint service name. Pipeline endpoints connect other
VPCs to a VPC pipeline: they can't be in the pipeline's own VPC, and an account other than the pipeline's needs the
pipeline's resource policy to allow it `osis:CreatePipelineEndpoint`. An endpoint is `CREATING`, then `ACTIVE`, and has
its own ingest URL. `RevokePipelineEndpointConnections` revokes endpoints (`REVOKING`, then `REVOKED`).

Resource policies grant only `osis:CreatePipelineEndpoint` on the pipeline, to 12-digit account IDs; anything else is
`Invalid resource policy.` `GetResourcePolicy` answers `{}` for a pipeline without one.

## Blueprints

`ListPipelineBlueprints` and `GetPipelineBlueprint` serve OpenSearch Ingestion's blueprints for log aggregation, Apache
logs, OpenTelemetry logs and traces, S3, HTTP to S3, and DynamoDB change data capture, as YAML or JSON.

## Configuration

```yaml
floci:
  services:
    osis:
      enabled: true
      engine: data-prepper                            # or none: pipelines keep their metadata but run nothing
      image: opensearchproject/data-prepper:2.12.2    # the image pipelines run on
      ready-seconds: 300                              # how long Data Prepper may take to start
      heap-mb: 512                                    # each pipeline's Java heap
      transition-millis: 2000
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreatePipeline` | Create a pipeline that runs on Data Prepper |
| `CreatePipelineEndpoint` | Connect another VPC to a VPC pipeline |
| `DeletePipeline` | Delete a pipeline and its container |
| `DeletePipelineEndpoint` | Delete a pipeline endpoint |
| `DeleteResourcePolicy` | Remove a pipeline's resource policy |
| `GetPipeline` | Describe a pipeline |
| `GetPipelineBlueprint` | Read a blueprint as YAML or JSON |
| `GetPipelineChangeProgress` | Report the latest change's stages |
| `GetResourcePolicy` | Read a pipeline's resource policy |
| `ListPipelineBlueprints` | List the blueprints |
| `ListPipelineEndpointConnections` | List endpoints connected to the account's pipelines |
| `ListPipelineEndpoints` | List the account's pipeline endpoints |
| `ListPipelines` | List pipelines |
| `ListTagsForResource` | List a pipeline's tags |
| `PutResourcePolicy` | Let accounts create pipeline endpoints |
| `RevokePipelineEndpointConnections` | Revoke pipeline endpoints |
| `StartPipeline` | Start a stopped pipeline's Data Prepper |
| `StopPipeline` | Stop a pipeline's Data Prepper |
| `TagResource` | Tag a pipeline |
| `UntagResource` | Remove tags from a pipeline |
| `UpdatePipeline` | Restart a pipeline with new settings |
| `ValidatePipeline` | Check a pipeline configuration |
<!-- floci:actions:end -->
