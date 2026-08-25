# CloudWatch Observability Access Manager (OAM)

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the CloudWatch OAM sink and link management lifecycle used by Alchemy resources and the `ListAttachedLinks` binding. Sinks are isolated by account and region (one sink per account per region, matching AWS) and use the configured Floci storage mode.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateSink` | `POST /CreateSink` | Create the account/region sink |
| `GetSink` | `POST /GetSink` | Return a sink by ARN or id |
| `DeleteSink` | `POST /DeleteSink` | Delete a sink with no attached links |
| `ListSinks` | `POST /ListSinks` | List sinks in the account and region |
| `PutSinkPolicy` | `POST /PutSinkPolicy` | Attach a sink policy document |
| `GetSinkPolicy` | `POST /GetSinkPolicy` | Return the sink policy |
| `ListAttachedLinks` | `POST /ListAttachedLinks` | List source-account links attached to a sink |
| `CreateLink` | `POST /CreateLink` | Create a link (same-account sinks are rejected) |
| `GetLink` | `POST /GetLink` | Return a link by ARN or id |
| `UpdateLink` | `POST /UpdateLink` | Update resource types / link configuration |
| `DeleteLink` | `POST /DeleteLink` | Delete a link |
| `ListLinks` | `POST /ListLinks` | List links in the account and region |
| `ListTagsForResource` | `GET /tags/{ResourceArn}` | List tags on a sink or link |
| `TagResource` | `PUT /tags/{ResourceArn}` | Add or overwrite tags |
| `UntagResource` | `DELETE /tags/{ResourceArn}` | Remove tags |

Tag APIs share `/tags/{arn}` and are dispatched by the shared tag controller from the `oam` ARN service segment.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_OAM_ENABLED` | `true` | Enable or disable OAM |
| `FLOCI_STORAGE_SERVICES_OAM_MODE` | *(inherits global)* | Optional OAM storage-mode override |
| `FLOCI_STORAGE_SERVICES_OAM_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws oam create-sink --name local-sink
aws oam list-sinks
aws oam list-attached-links --sink-identifier arn:aws:oam:us-east-1:000000000000:sink/...
```
