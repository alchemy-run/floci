# CloudWatch Synthetics

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements CloudWatch Synthetics canary and group lifecycle for local SDK, CLI, and Alchemy workflows. Canaries are isolated by account and region and use the configured Floci storage mode. Groups are account-scoped (visible from every region).

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateCanary` | `POST /canary` | Create a canary in the `READY` state |
| `GetCanary` | `GET /canary/{Name}` | Return a canary and its configuration |
| `UpdateCanary` | `PATCH /canary/{Name}` | Update the supplied configuration fields |
| `DeleteCanary` | `DELETE /canary/{Name}` | Delete a canary |
| `DescribeCanaries` | `POST /canaries` | List canaries with pagination |
| `DescribeCanariesLastRun` | `POST /canaries/last-run` | List last runs (canaries that have never run are omitted) |
| `GetCanaryRuns` | `POST /canary/{Name}/runs` | List runs for a canary |
| `StartCanary` | `POST /canary/{Name}/start` | Start the canary; records an immediate PASSED run |
| `StopCanary` | `POST /canary/{Name}/stop` | Stop a running canary (`ConflictException` if not running) |
| `DescribeRuntimeVersions` | `POST /runtime-versions` | List known runtime versions |
| `CreateGroup` | `POST /group` | Create a canary group |
| `GetGroup` | `GET /group/{GroupIdentifier}` | Return a group by name, id, or ARN |
| `DeleteGroup` | `DELETE /group/{GroupIdentifier}` | Delete a group (canaries are not deleted) |
| `ListGroups` | `POST /groups` | List groups |
| `AssociateResource` | `PATCH /group/{GroupIdentifier}/associate` | Add a canary ARN to a group |
| `DisassociateResource` | `PATCH /group/{GroupIdentifier}/disassociate` | Remove a canary ARN from a group |
| `ListGroupResources` | `POST /group/{GroupIdentifier}/resources` | List canary ARNs in a group |
| `ListAssociatedGroups` | `POST /resource/{ResourceArn}/groups` | List groups a canary belongs to |
| `ListTagsForResource` | `GET /tags/{ResourceArn}` | List tags on a canary or group |
| `TagResource` | `POST /tags/{ResourceArn}` | Add or overwrite tags |
| `UntagResource` | `DELETE /tags/{ResourceArn}` | Remove tags |

`StartCanary` on a `rate(0 minute)` schedule records one PASSED run and returns the canary to `STOPPED`, matching AWS one-shot behavior. Newly created canaries are immediately `READY` (no `CREATING` delay).

Tag APIs share `/tags/{arn}` and are dispatched by the shared tag controller from the `synthetics` ARN service segment.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_SYNTHETICS_ENABLED` | `true` | Enable or disable CloudWatch Synthetics |
| `FLOCI_STORAGE_SERVICES_SYNTHETICS_MODE` | *(inherits global)* | Optional Synthetics storage-mode override |
| `FLOCI_STORAGE_SERVICES_SYNTHETICS_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws synthetics create-canary \
  --name local-canary \
  --artifact-s3-location s3://my-artifacts/canary \
  --execution-role-arn arn:aws:iam::000000000000:role/CanaryRole \
  --schedule Expression="rate(5 minutes)" \
  --runtime-version syn-nodejs-puppeteer-16.1 \
  --code Handler=index.handler,ZipFile=UEsDBAoAAAAA

aws synthetics get-canary --name local-canary
aws synthetics start-canary --name local-canary
aws synthetics get-canary-runs --name local-canary
aws synthetics delete-canary --name local-canary
```

## Current Scope

- Canary configuration, schedule, run config, tags, engine ARN, and timeline (epoch seconds) are modeled.
- `StartCanary` does not invoke a backing Lambda; it records a PASSED run immediately.
- `EngineArn` is a synthetic `cwsyn-{name}-{id}` Lambda ARN; the function is not created.
- Group membership is stored locally and does not require the canary to exist in this emulator (cross-region ARNs are accepted).
