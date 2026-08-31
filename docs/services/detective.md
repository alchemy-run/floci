# Amazon Detective

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the Amazon Detective behavior-graph lifecycle used by the AWS SDK, CLI, and Alchemy. An account may administer at most one behavior graph per Region. A second `CreateGraph` in the same Region returns `ConflictException` (HTTP 409).

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateGraph` | `POST /graph` | Enable Detective and create the Region's behavior graph |
| `ListGraphs` | `POST /graphs/list` | List the administrator account's behavior graphs |
| `DeleteGraph` | `POST /graph/removal` | Disable Detective and delete the behavior graph |
| `ListTagsForResource` | `GET /tags/{resourceArn}` | List tags on a behavior graph |
| `TagResource` | `POST /tags/{resourceArn}` | Add or update tags |
| `UntagResource` | `DELETE /tags/{resourceArn}?tagKeys=` | Remove tags |

Deleting a graph that does not exist returns `ResourceNotFoundException` (HTTP 404).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_DETECTIVE_ENABLED` | `true` | Enable or disable Amazon Detective |
| `FLOCI_STORAGE_SERVICES_DETECTIVE_MODE` | *(inherits global)* | Optional Detective storage-mode override |
| `FLOCI_STORAGE_SERVICES_DETECTIVE_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws detective create-graph --tags env=test
aws detective list-graphs
```
