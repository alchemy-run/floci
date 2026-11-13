# AWS IoT SiteWise

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/`, signed for `iotsitewise` (the `api.`, `data.` and `monitor.` hosts alike).

Floci runs IoT SiteWise's asset modeling, its data plane with computed transforms and metrics, SiteWise SQL,
gateways, SiteWise Monitor, bulk imports and storage settings, and the newer workspace, dataset, computation model
and assistant APIs.

## Asset models and assets

Asset models are `CREATING`, then `ACTIVE`; an update makes a model `UPDATING` until its assets carry the new
definition, and a delete makes it `DELETING` until it's gone, each for
`floci.services.iotsitewise.transition-millis` (1000). A model refuses changes while it isn't `ACTIVE`
(`ConflictingOperationException`). Each update is a new version: `assetModelVersion` reads `LATEST` or `ACTIVE`, and
`If-Match`/`If-None-Match` compare against the version's ETag (`PreconditionFailedException`).

Definitions are checked as SiteWise checks them: unique property and hierarchy names, the data types each property
type allows (transforms and metrics output `DOUBLE` or `STRING`), attribute defaults of the property's type,
expressions that parse, variables that name properties of the model (or, for metrics, of a hierarchy's child model),
no aggregation in transforms, metrics that read only metrics with the same window, and no property that depends on
itself. Variables written with a property's name are stored with its ID.

Composite models can be inline, created on their own and nested, or composed from component models
(`composedAssetModelId`); interfaces are implemented with `PutAssetModelInterfaceRelationship`, matching properties
by name, by override or by creating them.

Assets carry their model's property, hierarchy and composite model IDs. Each property's alias, notification state and
unit are set on the asset and survive model updates. Assets associate to a parent through a hierarchy of the parent's
model, and one with a parent or children can't be deleted. A model with assets can't be deleted.

## Data

`BatchPutAssetPropertyValue` takes values named by asset and property ID or by alias. Each value's type must match
the property's, its time stamp must be within 7 days in the past and 10 minutes in the future
(`TimestampOutOfRangeException`), and transforms and metrics can't take values. Values sent to an alias no asset
property has go to a disassociated data stream, created with the data type of its first values unless the storage
configuration disables disassociated storage. `AssociateTimeSeriesToAssetProperty` moves a stream and its history to
a property; `DisassociateTimeSeriesFromAssetProperty` moves them back.

Transforms run whenever one of their inputs gets a value, at that value's time, with each variable's latest GOOD
value. Metrics run on each tumbling window once it ends, every `floci.services.iotsitewise.metric-tick-millis`
(1000), with the window's start exclusive and its end inclusive, and place their value at the window's end; late data
in a window already computed computes it again. Hierarchy variables read every child asset's property. The formula
language has SiteWise's operators, constants and functions: common math, comparison, conditional (`if`, `elif`),
string (`len`, `find`, `contains`, `upper`, `lower`, `jp`, `join`, `format`, f-strings, indexing and slicing), date and
time (`now`, `timestamp`, `mktime`, `localtime` and its parts), aggregation and temporal functions in metrics, and
`pretrigger` in transforms.

A property with notifications `ENABLED` publishes a `PropertyValueUpdate` message for each value it gets on its
`$aws/sitewise/asset-models/<model>/assets/<asset>/properties/<property>` topic, on Floci's AWS IoT broker.

The data plane reads the latest value, history (ascending or descending, filtered by quality), aggregates over `1m`,
`15m`, `1h` and `1d` windows (`AVERAGE`, `COUNT`, `MAXIMUM`, `MINIMUM`, `SUM`, `STANDARD_DEVIATION`) and linear or
LOCF interpolation, each also in batch.

## SiteWise SQL

`ExecuteQuery` runs on floci-duck over the views `asset`, `asset_property`, `raw_time_series`,
`latest_value_time_series` and `precomputed_aggregates`. Views listed with commas in a `FROM` clause are joined on
`asset_id` and `property_id`, as SiteWise joins them. `STR_REPLACE`, `DATE_ADD`, `DATE_SUB`, `TIMESTAMP_ADD`,
`TIMESTAMP_SUB`, `TO_TIMESTAMP`, `TO_DATE`, `TO_TIME`, `SUBSTR` with a pattern, `CAST ... FORMAT`, `STDDEV` and
`NOW` are rewritten for DuckDB. Results page through `nextToken`. Floci starts floci-duck in the background once
SiteWise resources are created (`floci.services.iotsitewise.prestart-query-engine`), so the first query doesn't wait
for it.

## Gateways and SiteWise Monitor

Gateways run on Greengrass V2 (version 2 or 3), Greengrass V1 or Siemens Industrial Edge, at most one on each core
device or thing. A capability configuration is `OUT_OF_SYNC` from the moment it changes, since no gateway reports
back.

Portals need an existing IAM role and are `CREATING`, then `ACTIVE`. A portal's start URL is served by Floci at
`/_floci/iotsitewise/portals/<id>`, listing its projects, their dashboards and the assets they share, and its PNG
logo at the image location's URL. Dashboards' definitions are JSON with a widgets list whose metrics name existing
assets and properties. Access policies grant IAM Identity Center users and groups on SSO portals and IAM users and
roles on IAM portals. A portal with projects, or a project with dashboards, can't be deleted.

## Bulk imports and storage

Bulk import jobs read CSV files with the columns they name, Parquet files (through floci-duck), MP4 clips into
`VIDEO` streams and annotation files into `ANNOTATION` streams. A job is `PENDING`, `RUNNING`, then `COMPLETED`,
`COMPLETED_WITH_FAILURES` with each rejected row in `errors.csv` under the error report location, or `FAILED`. With
adaptive ingestion, rows from the last 30 days run computations and notifications.

`PutStorageConfiguration` sets the retention period, disassociated data storage, refusing null and NaN values, and
the cold tier: `MULTI_LAYER_STORAGE` with a customer managed S3 location, to which Floci replicates time series under
`raw/`, their one-minute aggregates under `agg/` (Avro with Snappy, SiteWise's schemas and paths), index files, and
asset and hierarchy metadata as NDJSON, once when it's configured and then every
`floci.services.iotsitewise.cold-tier-export-seconds` (21600). Default encryption takes a KMS key that exists.
Logging at `ERROR` or `INFO` writes rejected ingestion to the `/aws/iotsitewise` log group.

## Computation models and actions

Anomaly detection computation models bind input and result properties of an asset model or of assets. The
`AWS/ANOMALY_DETECTION_TRAINING` action resamples the inputs at the target sampling rate over the training range and
learns each input's mean and spread (training fails with fewer than
`floci.services.iotsitewise.min-training-samples` (10) samples). `AWS/ANOMALY_DETECTION_INFERENCE` then runs at the
data upload frequency, scoring each sample by how far its inputs are from training and writing a JSON result to the
result property (`prediction`, `prediction_reason`, `anomaly_score` and per-input `diagnostics`). The Training,
Inference and TrainingWithInference actions of an asset's `AWS/L4E_ANOMALY` composite model run the same way on the
properties its `AWS/L4E_ANOMALY_INPUT` attribute lists. Every action is an execution, listed and described with its
result.

## Workspaces

Workspaces hold applications, container tasks, pipelines, queries, searches, datasets and jobs. A pipeline execution
runs each compute node as a Docker container of its task's image, with the pipeline's, task's, node's and execution's
environment variables, once the nodes it depends on succeed; a node that exits non-zero fails the execution and the
nodes after it. Workspace queries run SiteWise SQL asynchronously. Searches score time series in datasets by the words
of the statement their aliases and names hold, and find each one's highest value. Event detection enrichment marks a
dataset's segments in its range enriched; dataset exports write each time series as CSV under the destination; and
`GetCaptureData` returns a `VIDEO` stream's clips.

`InvokeAssistant` converses with a Bedrock model through Floci's Bedrock Runtime
(`floci.services.iotsitewise.assistant-model-id`), with the Region's assets as context and SiteWise SQL as a tool,
and streams its answer as an `output` event.

## Configuration

```yaml
floci:
  services:
    iotsitewise:
      enabled: true
      transition-millis: 1000
      metric-tick-millis: 1000
      cold-tier-export-seconds: 21600
      max-asset-models: 1000
      max-assets: 100000
      max-properties-per-model: 1000
      max-active-bulk-import-jobs: 10
      min-training-samples: 10
      assistant-model-id: anthropic.claude-3-5-sonnet-20240620-v1:0
      prestart-query-engine: true
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AssociateAssets` | Associate a child asset to a parent through a hierarchy of the parent's model |
| `AssociateTimeSeriesToAssetProperty` | Move a disassociated stream and its history to an asset property |
| `BatchAssociateDataSegmentsToDataset` | Add time series segments from source datasets to a dataset |
| `BatchAssociateProjectAssets` | Share assets in a project, reporting each that doesn't exist |
| `BatchDeleteDatasetDataSegments` | Remove data segments from a dataset |
| `BatchDisassociateDataSegmentsFromDataset` | Remove data segments of a source dataset from a dataset |
| `BatchDisassociateProjectAssets` | Stop sharing assets in a project |
| `BatchGetAssetPropertyAggregates` | Get aggregates of up to 16 properties, reporting each failure |
| `BatchGetAssetPropertyValue` | Get the latest values of up to 128 properties, reporting each failure |
| `BatchGetAssetPropertyValueHistory` | Get the history of up to 16 properties, reporting each failure |
| `BatchPutAssetPropertyValue` | Ingest values by asset property or alias, checking type, quality and time stamp; runs transforms and notifications |
| `CancelEnrichmentJob` | Cancel a pending or running enrichment job |
| `CancelPipelineExecution` | Cancel a pipeline execution, stopping its running container |
| `CancelQuery` | Cancel a submitted or running workspace query |
| `CreateAccessPolicy` | Grant a user, group, IAM user or IAM role a portal or project |
| `CreateApplication` | Create an IAM Identity Center application in a workspace |
| `CreateAsset` | Create a CREATING asset of an ACTIVE asset model |
| `CreateAssetModel` | Create a CREATING asset model, checking its properties, expressions and hierarchies |
| `CreateAssetModelCompositeModel` | Add a composite model, nested or composed from a component model |
| `CreateBulkImportJob` | Import CSV, Parquet, MP4 or annotation files from S3 |
| `CreateComputationModel` | Create an anomaly detection computation model over bound properties |
| `CreateDashboard` | Create a dashboard of widgets over a project's assets |
| `CreateDataset` | Create a SiteWise time series or Kendra knowledge base dataset |
| `CreateDatasetExportJob` | Export time series or a dataset's segments to S3 as CSV |
| `CreateEnrichmentJob` | Run event detection over a dataset's segments |
| `CreateGateway` | Create a gateway on a Greengrass core device or Siemens Industrial Edge thing |
| `CreatePipeline` | Create a pipeline of compute nodes that run tasks in dependency order |
| `CreatePortal` | Create a CREATING SiteWise Monitor portal, served by Floci at its start URL |
| `CreateProject` | Create a project in a portal |
| `CreateTask` | Create a container task in a workspace |
| `CreateWorkspace` | Create a CREATING workspace |
| `DeleteAccessPolicy` | Delete an access policy |
| `DeleteApplication` | Delete an application |
| `DeleteAsset` | Delete an asset with no parent or children |
| `DeleteAssetModel` | Delete an asset model no asset or other model uses |
| `DeleteAssetModelCompositeModel` | Delete a composite model and those nested in it |
| `DeleteAssetModelInterfaceRelationship` | Stop an asset model implementing an interface |
| `DeleteComputationModel` | Delete a computation model and stop its inference |
| `DeleteDashboard` | Delete a dashboard |
| `DeleteDataset` | Delete a dataset |
| `DeleteGateway` | Delete a gateway |
| `DeletePipeline` | Delete a pipeline with no running execution |
| `DeletePortal` | Delete a portal with no projects, and its access policies |
| `DeleteProject` | Delete a project with no dashboards, and its access policies |
| `DeleteTask` | Delete a task no pipeline runs |
| `DeleteTimeSeries` | Delete a time series and its data |
| `DeleteWorkspace` | Delete a workspace with no tasks, pipelines or applications |
| `DescribeAccessPolicy` | Describe an access policy |
| `DescribeAction` | Describe an action run on an asset or computation model |
| `DescribeApplication` | Describe an application |
| `DescribeAsset` | Describe an asset with its properties, hierarchies and composite models |
| `DescribeAssetCompositeModel` | Describe an asset's composite model and its action definitions |
| `DescribeAssetModel` | Describe an asset model's latest or active version, with its ETag |
| `DescribeAssetModelCompositeModel` | Describe a composite model, its composition and action definitions |
| `DescribeAssetModelInterfaceRelationship` | Describe how an asset model maps an interface's properties and hierarchies |
| `DescribeAssetProperty` | Describe an asset property with its model definition |
| `DescribeBulkImportJob` | Describe a bulk import job and its status |
| `DescribeComputationModel` | Describe a computation model and its action definitions |
| `DescribeComputationModelExecutionSummary` | Describe a computation model's trained model and inference on an asset |
| `DescribeDashboard` | Describe a dashboard |
| `DescribeDataset` | Describe a dataset and its enrichment status |
| `DescribeDatasetExportJob` | Describe a dataset export job |
| `DescribeDefaultEncryptionConfiguration` | Describe the account's default encryption |
| `DescribeEnrichmentJob` | Describe an enrichment job |
| `DescribeExecution` | Describe an action's execution with its result and details |
| `DescribeGateway` | Describe a gateway and its capabilities' sync status |
| `DescribeGatewayCapabilityConfiguration` | Describe a gateway capability's configuration |
| `DescribeLoggingOptions` | Describe the logging level |
| `DescribePipeline` | Describe a pipeline |
| `DescribePipelineExecution` | Describe a pipeline execution and each compute node's status |
| `DescribePortal` | Describe a portal |
| `DescribeProject` | Describe a project |
| `DescribeQuery` | Describe a workspace query and its statistics |
| `DescribeSearch` | Describe a search |
| `DescribeStorageConfiguration` | Describe the storage configuration |
| `DescribeTask` | Describe a task |
| `DescribeTimeSeries` | Describe a time series by alias or asset property |
| `DescribeWorkspace` | Describe a workspace |
| `DisassociateAssets` | Disassociate a child asset from its parent |
| `DisassociateTimeSeriesFromAssetProperty` | Move an asset property's alias and history to a disassociated stream |
| `ExecuteAction` | Run anomaly detection training or inference on a computation model or L4E composite model |
| `ExecuteQuery` | Run SiteWise SQL on floci-duck over the Region's assets and time series |
| `GetAssetPropertyAggregates` | Get 1m, 15m, 1h or 1d aggregates of a property |
| `GetAssetPropertyValue` | Get a property's latest value |
| `GetAssetPropertyValueHistory` | Get a property's values in a time range, filtered by quality |
| `GetCaptureData` | Get a VIDEO stream's MP4 clips in a time range |
| `GetInterpolatedAssetPropertyValues` | Get a property's values interpolated linearly or carried forward |
| `GetQueryResults` | Get a completed workspace query's rows |
| `GetSearchResults` | Get a search's results by score |
| `InvokeAssistant` | Ask the SiteWise Assistant, a Bedrock model that can run SiteWise SQL; streams its answer |
| `ListAccessPolicies` | List access policies by identity or resource |
| `ListActions` | List actions run on an asset or computation model |
| `ListApplications` | List applications |
| `ListAssetModelCompositeModels` | List an asset model's composite models |
| `ListAssetModelProperties` | List an asset model's properties, its own or all |
| `ListAssetModels` | List asset models by type and version |
| `ListAssetProperties` | List an asset's properties, its own or all |
| `ListAssetRelationships` | List an asset's path to its root |
| `ListAssets` | List a model's assets, or the top-level assets |
| `ListAssociatedAssets` | List an asset's children through a hierarchy, or its parent |
| `ListBulkImportJobs` | List bulk import jobs by status |
| `ListCompositionRelationships` | List the asset models a component model is composed into |
| `ListComputationModelDataBindingUsages` | List the computation models that bind an asset, model or property |
| `ListComputationModelResolveToResources` | List the assets a computation model has run on |
| `ListComputationModels` | List computation models |
| `ListDashboards` | List a project's dashboards |
| `ListDatasetDataSegmentRelationships` | List where a dataset's segments come from |
| `ListDatasetDataSegments` | List a dataset's segments |
| `ListDatasetExportJobs` | List a workspace's dataset export jobs |
| `ListDatasets` | List datasets by source and type |
| `ListEnrichmentJobs` | List a workspace's enrichment jobs |
| `ListExecutions` | List executions on an asset or computation model, newest first |
| `ListGateways` | List gateways |
| `ListInterfaceRelationships` | List the asset models that implement an interface |
| `ListPipelineExecutions` | List a pipeline's executions by state and time |
| `ListPipelines` | List a workspace's pipelines |
| `ListPortals` | List portals |
| `ListProjectAssets` | List the assets a project shares |
| `ListProjects` | List a portal's projects |
| `ListQueries` | List a workspace's queries by status |
| `ListSearches` | List a workspace's searches by status, type, group and start time |
| `ListTagsForResource` | List a resource's tags |
| `ListTasks` | List a workspace's tasks |
| `ListTimeSeries` | List time series by asset, alias prefix and association |
| `ListWorkspaces` | List workspaces |
| `PutAssetModelInterfaceRelationship` | Make an asset model implement an interface, mapping or creating properties |
| `PutDefaultEncryptionConfiguration` | Set default encryption, with a KMS key that exists |
| `PutLoggingOptions` | Set the logging level for `/aws/iotsitewise` |
| `PutStorageConfiguration` | Set retention, disassociated storage and the S3 cold tier, which Floci replicates to |
| `StartPipelineExecution` | Run a pipeline, each compute node a Docker container of its task's image |
| `StartQuery` | Run SiteWise SQL in a workspace in the background |
| `StartSearch` | Search a workspace's dataset time series |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
| `UpdateAccessPolicy` | Update an access policy |
| `UpdateAsset` | Rename or describe an asset |
| `UpdateAssetModel` | Replace an asset model's definition as a new version, propagated to its assets |
| `UpdateAssetModelCompositeModel` | Update a composite model as a new version of the asset model |
| `UpdateAssetProperty` | Set an asset property's alias, notification state and unit |
| `UpdateComputationModel` | Update a computation model's configuration and bindings |
| `UpdateDashboard` | Update a dashboard |
| `UpdateDataset` | Update a dataset |
| `UpdateGateway` | Rename a gateway |
| `UpdateGatewayCapabilityConfiguration` | Set a gateway capability's configuration, OUT_OF_SYNC until a gateway applies it |
| `UpdatePipeline` | Update a pipeline as a new version |
| `UpdatePortal` | Update a portal and its logo |
| `UpdateProject` | Update a project |
| `UpdateTask` | Update a task as a new version |
| `UpdateWorkspace` | Update a workspace's description and encryption |
<!-- floci:actions:end -->
