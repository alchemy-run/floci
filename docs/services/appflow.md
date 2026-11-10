# Amazon AppFlow

**Protocol:** REST JSON, signed with `appflow`
**Endpoint:** `POST http://localhost:4566/<operation-name>` (`/create-flow`, `/start-flow`, …) and the shared `/tags/{resourceArn}` routes

Floci runs AppFlow flows between S3 buckets: a run reads the source objects, applies the flow's tasks to their records, and writes the result to the destination. Flows and connector profiles for every other connector can be created and described, but Floci can't reach those applications, so their runs end in `Error` with the reason.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CancelFlowExecutions` | Cancel runs in progress |
| `CreateConnectorProfile` | Create a connector profile with its credentials |
| `CreateFlow` | Create a flow, checking its connectors and buckets |
| `DeleteConnectorProfile` | Delete a profile no flow uses (or forceDelete) |
| `DeleteFlow` | Delete a flow |
| `DescribeConnector` | Describe a connector type or registered connector |
| `DescribeConnectorEntity` | List an S3 entity's fields |
| `DescribeConnectorProfiles` | Page through connector profiles, without credentials |
| `DescribeConnectors` | Describe connector types and registered connectors |
| `DescribeFlow` | Describe a flow and its last run |
| `DescribeFlowExecutionRecords` | Page through a flow's runs, newest first |
| `ListConnectorEntities` | List S3 buckets, prefixes and objects |
| `ListConnectors` | Page through connectors |
| `ListFlows` | Page through flows |
| `RegisterConnector` | Register a custom connector backed by a Lambda function |
| `ResetConnectorMetadataCache` | Accept a metadata cache reset |
| `StartFlow` | Run an on-demand flow, or activate a scheduled or event flow |
| `StopFlow` | Suspend a scheduled or event flow |
| `UnregisterConnector` | Unregister a custom connector |
| `UpdateConnectorProfile` | Change a profile's properties or credentials |
| `UpdateConnectorRegistration` | Change a custom connector's description or Lambda |
| `UpdateFlow` | Replace a flow's definition |
| `ListTagsForResource` | List a flow's tags |
| `TagResource` | Tag a flow |
| `UntagResource` | Remove tags from a flow |
<!-- floci:actions:end -->

## Flows

`CreateFlow` checks each connector: it must be able to play its part (source or destination), and a connector other than S3, EventBridge and Lookout for Metrics needs a connector profile that exists. An S3 bucket must exist and have a bucket policy that names `appflow.amazonaws.com` (otherwise `AccessDeniedException`), and an S3 source prefix must hold at least one object (otherwise `ConnectorServerException`). An on-demand flow is `Active` when created; a scheduled or event flow is `Draft` until `StartFlow` activates it, and `StopFlow` suspends it. `UpdateFlow` replaces the definition and bumps its schema version. A flow with a run in progress needs `forceDelete` to be deleted.

## Runs

`StartFlow` on an on-demand flow starts a run, which is `InProgress` for a second and then runs:

- The source's objects under its prefix are read as CSV (with a header row) or JSON (an array, or one object per line), as `s3InputFileType` says or their content shows. An incremental scheduled flow reads only objects changed since its last successful run.
- The tasks apply in order. `Filter` tasks keep records whose field compares as asked (`EQUAL_TO`, `NOT_EQUAL_TO`, `CONTAINS`, `LESS_THAN`, `GREATER_THAN`, their `_OR_EQUAL_TO` forms and `BETWEEN`; `PROJECTION` keeps every record), and `Validate` tasks drop the record, stop the run, or do nothing when a field is null, zero, negative or not numeric. `Map_all` copies every field but those in `EXCLUDE_SOURCE_FIELDS_LIST`; `Map` and `Passthrough` copy a field, `Merge` joins fields (with `CONCAT_FORMAT`), `Arithmetic` adds, subtracts, multiplies or divides them, `Mask` masks all, the first N or the last N characters, and `Truncate` shortens a field, each into its destination field, converted to `DESTINATION_DATA_TYPE`.
- Each S3 destination gets the records as CSV, JSON lines (the default) or Parquet (written through Floci's DuckDB sidecar), one file per source object or, with `SingleFile` aggregation, one file, at `<bucketPrefix>/<flowName>/<executionId>/<flowName>-<n>-<millis>.<csv|json|parquet>`. A `PATH` prefix configuration adds the date path (`yyyy/MM/dd/…` to the minute it names); `FILENAME` prefixes the file name with the date; `pathPrefixHierarchy` chooses whether the execution id and `schemaVersion_1` appear.

The run ends `Successful` or `Error`, with its bytes and records in `DescribeFlowExecutionRecords`, and the flow's `lastRunExecutionDetails` updated. Each run's end is published to EventBridge as an `aws.appflow` `AppFlow End Flow Run Report` event, with the flow, the execution id, the record counts and the status. `CancelFlowExecutions` cancels runs in progress (`CancelStarted`, then `Canceled`) and returns the ids it couldn't cancel. A scheduled flow runs on its `rate(…)` (for example `rate(5minutes)`) or `cron(…)` expression from its start time until its end time.

## Connectors

`DescribeConnectors`, `ListConnectors` and `DescribeConnector` describe AppFlow's connector types: whether each is a source or destination, its triggers and the destinations it reaches. `ListConnectorEntities` lists the S3 connector's buckets, and then a bucket's prefixes and objects; `DescribeConnectorEntity` gives an S3 entity's fields from its first object's CSV header or JSON keys. Custom connectors are registered with their Lambda function, which must exist. Connector profiles keep their credentials, which are never returned. When a profile is saved, Floci checks its connection as AppFlow does: a Salesforce or Pardot token against the instance's `/services/oauth2/userinfo`, and for any other connector with an instance URL (or Snowflake's account host), that the address answers. An unreachable address is a `ConnectorServerException` and a rejected token a `ConnectorAuthenticationException`; connectors with no address to check are accepted as given.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_APP_FLOW_ENABLED` | `true` | Enable or disable AppFlow |

For the upstream API shape, see the [Amazon AppFlow API Reference](https://docs.aws.amazon.com/appflow/1.0/APIReference/Welcome.html).
