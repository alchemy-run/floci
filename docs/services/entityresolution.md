# Amazon Entity Resolution

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements Entity Resolution schema mappings and matching workflow definitions used by the AWS SDK, CLI, and Alchemy. Resources are isolated by account and region. `Get*` responses omit tags; use `ListTagsForResource`.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateSchemaMapping` | `POST /schemas` | Create a schema mapping |
| `GetSchemaMapping` | `GET /schemas/{schemaName}` | Return a schema mapping by name |
| `UpdateSchemaMapping` | `PUT /schemas/{schemaName}` | Update description and mapped fields (conflicts while a workflow references it) |
| `DeleteSchemaMapping` | `DELETE /schemas/{schemaName}` | Delete a schema mapping; missing names succeed |
| `ListSchemaMappings` | `GET /schemas` | List schema mapping summaries |
| `CreateMatchingWorkflow` | `POST /matchingworkflows` | Create a matching workflow |
| `GetMatchingWorkflow` | `GET /matchingworkflows/{workflowName}` | Return a matching workflow by name |
| `UpdateMatchingWorkflow` | `PUT /matchingworkflows/{workflowName}` | Update description, sources, techniques, and role |
| `DeleteMatchingWorkflow` | `DELETE /matchingworkflows/{workflowName}` | Delete a matching workflow; missing names succeed |
| `ListMatchingWorkflows` | `GET /matchingworkflows` | List matching workflow summaries |
| `ListTagsForResource` | `GET /tags/{resourceArn}` | List tags |
| `TagResource` | `POST /tags/{resourceArn}` | Add or update tags |
| `UntagResource` | `DELETE /tags/{resourceArn}?tagKeys=` | Remove tags |

ARNs are `arn:aws:entityresolution:<region>:<account>:schemamapping/<name>` and `arn:aws:entityresolution:<region>:<account>:matchingworkflow/<name>`. A missing resource returns `ResourceNotFoundException` (HTTP 404). Creating a duplicate name returns `ConflictException` (HTTP 400).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ENTITYRESOLUTION_ENABLED` | `true` | Enable or disable Entity Resolution |
| `FLOCI_STORAGE_SERVICES_ENTITYRESOLUTION_MODE` | *(inherits global)* | Optional storage-mode override |
| `FLOCI_STORAGE_SERVICES_ENTITYRESOLUTION_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |
