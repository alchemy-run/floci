# CloudWatch Application Signals

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the CloudWatch Application Signals grouping-configuration singleton and `StartDiscovery` used by the AWS SDK, CLI, and Alchemy. State is isolated by account and region.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `ListGroupingAttributeDefinitions` | `POST /grouping-attribute-definitions` | Return the account grouping configuration |
| `PutGroupingConfiguration` | `PUT /grouping-configuration` | Replace the grouping-attribute definition list |
| `DeleteGroupingConfiguration` | `DELETE /grouping-configuration` | Delete the grouping configuration (idempotent) |
| `StartDiscovery` | `POST /start-discovery` | Enable Application Signals discovery (idempotent) |

An account with no grouping configuration returns an empty `GroupingAttributeDefinitions` list and omits `UpdatedAt`. `UpdatedAt` is epoch seconds.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_APPLICATION_SIGNALS_ENABLED` | `true` | Enable or disable Application Signals |
| `FLOCI_STORAGE_SERVICES_APPLICATION_SIGNALS_MODE` | *(inherits global)* | Optional storage-mode override |
| `FLOCI_STORAGE_SERVICES_APPLICATION_SIGNALS_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws application-signals start-discovery

aws application-signals put-grouping-configuration \
  --grouping-attribute-definitions '[{"GroupingName":"Team","GroupingSourceKeys":["Tag.team"]}]'

aws application-signals list-grouping-attribute-definitions
```
