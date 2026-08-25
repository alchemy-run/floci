# Amazon Managed Service for Prometheus (AMP)

**Protocol:** REST JSON (restJson1)
**Signing name:** `aps`
**Endpoint:** `http://localhost:4566`

Floci implements the AMP workspace and scraper control planes used by Alchemy's `AWS.AMP.*` resources. Workspace ids are `ws-<uuid without dashes>`; scraper ids are `s-<uuid>`. Create returns `ACTIVE` immediately (no collector fleet is provisioned).

Resources are isolated by account and region and use the configured Floci storage mode.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateWorkspace` | `POST /workspaces` | Create a workspace; id is `ws-<hex>` |
| `ListWorkspaces` | `GET /workspaces` | List workspaces in the account and region |
| `DescribeWorkspace` | `GET /workspaces/{workspaceId}` | Return workspace description; missing → `ResourceNotFoundException` |
| `DeleteWorkspace` | `DELETE /workspaces/{workspaceId}` | Delete a workspace |
| `UpdateWorkspaceAlias` | `POST /workspaces/{workspaceId}/alias` | Update the workspace alias |
| `DescribeWorkspaceConfiguration` | `GET /workspaces/{workspaceId}/configuration` | Retention (default 150 days) and label-set limits |
| `UpdateWorkspaceConfiguration` | `PATCH /workspaces/{workspaceId}/configuration` | Update retention / limits in place |
| `CreateLoggingConfiguration` | `POST /workspaces/{workspaceId}/logging` | Ship rule/alerting logs to CloudWatch Logs |
| `DescribeLoggingConfiguration` | `GET /workspaces/{workspaceId}/logging` | Return logging configuration |
| `UpdateLoggingConfiguration` | `PUT /workspaces/{workspaceId}/logging` | Update the log group ARN |
| `DeleteLoggingConfiguration` | `DELETE /workspaces/{workspaceId}/logging` | Remove logging configuration |
| `CreateQueryLoggingConfiguration` | `POST /workspaces/{workspaceId}/logging/query` | Ship PromQL query logs |
| `DescribeQueryLoggingConfiguration` | `GET /workspaces/{workspaceId}/logging/query` | Return query logging destinations |
| `UpdateQueryLoggingConfiguration` | `PUT /workspaces/{workspaceId}/logging/query` | Update destinations / QSP threshold |
| `DeleteQueryLoggingConfiguration` | `DELETE /workspaces/{workspaceId}/logging/query` | Remove query logging |
| `PutResourcePolicy` | `PUT /workspaces/{workspaceId}/policy` | Upsert the resource-based policy (stable revision on no-op) |
| `DescribeResourcePolicy` | `GET /workspaces/{workspaceId}/policy` | Return policy document, status, revision |
| `DeleteResourcePolicy` | `DELETE /workspaces/{workspaceId}/policy` | Remove the policy |
| `CreateAnomalyDetector` | `POST /workspaces/{workspaceId}/anomalydetectors` | Create a Random Cut Forest detector |
| `ListAnomalyDetectors` | `GET /workspaces/{workspaceId}/anomalydetectors` | List detectors in the workspace |
| `DescribeAnomalyDetector` | `GET /workspaces/{workspaceId}/anomalydetectors/{id}` | Return detector description |
| `PutAnomalyDetector` | `PUT /workspaces/{workspaceId}/anomalydetectors/{id}` | In-place update (evaluation interval, query, …) |
| `DeleteAnomalyDetector` | `DELETE /workspaces/{workspaceId}/anomalydetectors/{id}` | Delete a detector |
| `CreateScraper` | `POST /scrapers` | Create a scraper; id is `s-<uuid>` |
| `DescribeScraper` | `GET /scrapers/{scraperId}` | Return scraper description; missing → `ResourceNotFoundException` |
| `UpdateScraper` | `PUT /scrapers/{scraperId}` | In-place alias, destination, configuration, or role update |
| `DeleteScraper` | `DELETE /scrapers/{scraperId}` | Delete a scraper (idempotent not-found) |
| `ListScrapers` | `GET /scrapers` | List scrapers in the account and region |
| `GetDefaultScraperConfiguration` | `GET /scraperconfiguration` | Default Prometheus YAML as a base64 blob |
| `UpdateScraperLoggingConfiguration` | `PUT /scrapers/{scraperId}/logging-configuration` | Upsert CloudWatch Logs destination |
| `DescribeScraperLoggingConfiguration` | `GET /scrapers/{scraperId}/logging-configuration` | Return logging configuration |
| `DeleteScraperLoggingConfiguration` | `DELETE /scrapers/{scraperId}/logging-configuration` | Remove logging configuration |
| `ListTagsForResource` | `GET /tags/{resourceArn}` | List tags (ARN service `aps`) |
| `TagResource` | `POST /tags/{resourceArn}` | Merge tags on workspace, detector, rule groups, or scraper |
| `UntagResource` | `DELETE /tags/{resourceArn}` | Remove tags |

`GetDefaultScraperConfiguration` returns `{ "configuration": "<base64>" }`. Decoding the blob yields Prometheus YAML containing `scrape_configs`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_AMP_ENABLED` | `true` | Enable or disable AMP |
| `FLOCI_STORAGE_SERVICES_AMP_MODE` | *(inherits global)* | Optional AMP storage-mode override |
| `FLOCI_STORAGE_SERVICES_AMP_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws amp list-workspaces
aws amp create-workspace --alias demo
aws amp get-default-scraper-configuration
aws amp describe-scraper --scraper-id s-00000000-0000-0000-0000-000000000000
```

## Current Scope

- Workspace CRUD, configuration (retention), logging, query logging, resource policy, anomaly detectors, scrapers, and tag APIs are modeled.
- Remote-write and PromQL query endpoints exist for binding tests; they store samples in memory and do not run a real Prometheus.
- Scrapers do not actually scrape; status is `ACTIVE` on create so wait-until-active loops complete immediately.
