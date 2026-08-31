# Amazon AppFlow

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the AppFlow flow management lifecycle used by the AWS SDK, CLI, and Alchemy. Flows are isolated by account and region. An S3 source is validated at create/update time by listing the source prefix — an empty prefix fails with `ConnectorServerException`, matching AWS.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateFlow` | `POST /create-flow` | Create a flow |
| `DescribeFlow` | `POST /describe-flow` | Return a flow by name |
| `UpdateFlow` | `POST /update-flow` | Update trigger, source, destination, tasks, and description |
| `DeleteFlow` | `POST /delete-flow` | Delete a flow |
| `ListFlows` | `POST /list-flows` | List flow summaries |
| `StartFlow` | `POST /start-flow` | Run an OnDemand flow (S3-to-S3 copy) and return `executionId` |
| `StopFlow` | `POST /stop-flow` | Deactivate a scheduled/event flow; OnDemand returns `UnsupportedOperationException` |
| `DescribeFlowExecutionRecords` | `POST /describe-flow-execution-records` | List run history |
| `CancelFlowExecutions` | `POST /cancel-flow-executions` | Cancel in-progress runs; finished ids land in `invalidExecutions` |
| `ListTagsForResource` | `GET /tags/{resourceArn}` | List tags on a flow |
| `TagResource` | `POST /tags/{resourceArn}` | Add or update tags |
| `UntagResource` | `DELETE /tags/{resourceArn}?tagKeys=` | Remove tags |

Flow ARNs are `arn:aws:appflow:<region>:<account>:flow/<flowName>`. Creating a second flow with the same name returns `ConflictException` (HTTP 409). A missing flow returns `ResourceNotFoundException` (HTTP 404).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_APPFLOW_ENABLED` | `true` | Enable or disable AppFlow |
| `FLOCI_STORAGE_SERVICES_APPFLOW_MODE` | *(inherits global)* | Optional AppFlow storage-mode override |
| `FLOCI_STORAGE_SERVICES_APPFLOW_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws appflow create-flow \
  --flow-name local-copy \
  --trigger-config TriggerType=OnDemand \
  --source-flow-config ConnectorType=S3,SourceConnectorProperties='{S3={BucketName=src,BucketPrefix=input}}' \
  --destination-flow-config-list ConnectorType=S3,DestinationConnectorProperties='{S3={BucketName=dst,BucketPrefix=output}}' \
  --tasks TaskType=Map_all,SourceFields=[]

aws appflow describe-flow --flow-name local-copy
```

## Current Scope

- Flow create/describe/update/delete/list and tagging.
- S3-to-S3 source-prefix validation at create and update.
- OnDemand `StartFlow` copies source objects into the destination prefix and records a `Successful` execution.
- `StopFlow` on OnDemand flows returns `UnsupportedOperationException`.
- Connector profiles for S3 (no `instanceUrl`) and custom connectors beyond the profile CRUD already implemented.
