# Amazon DevOps Guru

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements DevOps Guru restJson1 account/region singletons used by Alchemy: service integration, notification channels, event sources, and insight lookup. Configuration is isolated by account and region and uses the configured Floci storage mode.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `DescribeServiceIntegration` | `GET /service-integrations` | Return the current integration configuration |
| `UpdateServiceIntegration` | `PUT /service-integrations` | Merge the supplied OpsCenter, log-anomaly, and KMS sections |
| `AddNotificationChannel` | `PUT /channels` | Register an SNS notification channel |
| `ListNotificationChannels` | `POST /channels` | List notification channels |
| `RemoveNotificationChannel` | `DELETE /channels/{id}` | Remove a notification channel |
| `DescribeEventSourcesConfig` | `POST /event-sources` | Return CodeGuru Profiler event-source status |
| `UpdateEventSourcesConfig` | `PUT /event-sources` | Update CodeGuru Profiler event-source status |


`DescribeServiceIntegration` always answers. When the account has never been updated, the response is the AWS default: OpsCenter and log-anomaly detection `DISABLED`, encryption type `AWS_OWNED_KMS_KEY`.

`UpdateServiceIntegration` applies only the sections present on the request. Destroying the Alchemy resource restores those defaults with a subsequent update.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_DEVOPS_GURU_ENABLED` | `true` | Enable or disable DevOps Guru |
| `FLOCI_STORAGE_SERVICES_DEVOPS_GURU_MODE` | *(inherits global)* | Optional storage-mode override |
| `FLOCI_STORAGE_SERVICES_DEVOPS_GURU_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws devops-guru describe-service-integration

aws devops-guru update-service-integration \
  --service-integration '{"LogsAnomalyDetection":{"OptInStatus":"ENABLED"}}'

aws devops-guru describe-service-integration
```

## Current Scope

- Service-integration describe/update for OpsCenter, log-anomaly detection, and KMS encryption.
- Notification channel add/list/remove (config is immutable; filter changes are remove + re-add).
- Event-source config for Amazon CodeGuru Profiler.
- `DescribeInsight` on a missing id returns typed `ResourceNotFoundException`.
- Resource collections and cost estimation are not implemented.
