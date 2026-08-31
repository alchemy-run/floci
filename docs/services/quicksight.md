# Amazon QuickSight

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the QuickSight management APIs used by Alchemy resources and bindings: data sources, datasets, dashboards, SPICE ingestions, dashboard snapshot jobs, embed URLs, and resource tags. Resources are isolated by account and region.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateDataSource` | `POST /accounts/{AwsAccountId}/data-sources` | Create a data source |
| `DescribeDataSource` | `GET /accounts/{AwsAccountId}/data-sources/{DataSourceId}` | Describe a data source |
| `UpdateDataSource` | `PUT /accounts/{AwsAccountId}/data-sources/{DataSourceId}` | Update a data source |
| `DeleteDataSource` | `DELETE /accounts/{AwsAccountId}/data-sources/{DataSourceId}` | Delete a data source |
| `CreateDataSet` | `POST /accounts/{AwsAccountId}/data-sets` | Create a dataset |
| `DescribeDataSet` | `GET /accounts/{AwsAccountId}/data-sets/{DataSetId}` | Describe a dataset |
| `UpdateDataSet` | `PUT /accounts/{AwsAccountId}/data-sets/{DataSetId}` | Update a dataset |
| `DeleteDataSet` | `DELETE /accounts/{AwsAccountId}/data-sets/{DataSetId}` | Delete a dataset |
| `CreateDashboard` | `POST /accounts/{AwsAccountId}/dashboards/{DashboardId}` | Create a dashboard |
| `DescribeDashboard` | `GET /accounts/{AwsAccountId}/dashboards/{DashboardId}` | Describe a dashboard |
| `UpdateDashboard` | `PUT /accounts/{AwsAccountId}/dashboards/{DashboardId}` | Update a dashboard |
| `DeleteDashboard` | `DELETE /accounts/{AwsAccountId}/dashboards/{DashboardId}` | Delete a dashboard |
| `CreateIngestion` | `PUT /accounts/{AwsAccountId}/data-sets/{DataSetId}/ingestions/{IngestionId}` | Start a SPICE ingestion |
| `DescribeIngestion` | `GET /accounts/{AwsAccountId}/data-sets/{DataSetId}/ingestions/{IngestionId}` | Describe an ingestion |
| `CancelIngestion` | `DELETE /accounts/{AwsAccountId}/data-sets/{DataSetId}/ingestions/{IngestionId}` | Cancel an in-flight ingestion |
| `ListIngestions` | `GET /accounts/{AwsAccountId}/data-sets/{DataSetId}/ingestions` | List dataset ingestions |
| `StartDashboardSnapshotJob` | `POST /accounts/{AwsAccountId}/dashboards/{DashboardId}/snapshot-jobs` | Start a snapshot export |
| `DescribeDashboardSnapshotJob` | `GET /accounts/{AwsAccountId}/dashboards/{DashboardId}/snapshot-jobs/{SnapshotJobId}` | Describe a snapshot job |
| `DescribeDashboardSnapshotJobResult` | `GET /accounts/{AwsAccountId}/dashboards/{DashboardId}/snapshot-jobs/{SnapshotJobId}/result` | Describe snapshot job result |
| `GenerateEmbedUrlForRegisteredUser` | `POST /accounts/{AwsAccountId}/embed-url/registered-user` | Embed URL for a registered user |
| `GenerateEmbedUrlForAnonymousUser` | `POST /accounts/{AwsAccountId}/embed-url/anonymous-user` | Embed URL for anonymous access |
| `ListTagsForResource` | `GET /resources/{ResourceArn}/tags` | List resource tags |
| `TagResource` | `POST /resources/{ResourceArn}/tags` | Tag a resource |
| `UntagResource` | `DELETE /resources/{ResourceArn}/tags` | Untag a resource |

CreateIngestion on a `DIRECT_QUERY` dataset returns `InvalidParameterValueException`. Missing datasets, dashboards, and ingestions return `ResourceNotFoundException`. Unregistered users return `QuickSightUserNotFoundException`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_QUICKSIGHT_ENABLED` | `true` | Enable or disable QuickSight |
| `FLOCI_STORAGE_SERVICES_QUICKSIGHT_MODE` | *(inherits global)* | Optional QuickSight storage-mode override |
| `FLOCI_STORAGE_SERVICES_QUICKSIGHT_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

## Current Scope

- Data sources, datasets, and dashboards converge immediately (`CREATION_SUCCESSFUL` / `UPDATE_SUCCESSFUL`).
- SPICE ingestions are control-plane only; no Athena query is executed.
- Snapshot jobs complete immediately without writing PDF/CSV artifacts.
- Registered-user embed URLs require a registered QuickSight user (none are created by default).
- Analyses, templates, themes, folders, namespaces, and users are not implemented.
