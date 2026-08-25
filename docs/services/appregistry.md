# AWS Service Catalog AppRegistry

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the AppRegistry application management API used by the AWS SDK, CLI, and Alchemy. Requests are signed with credential scope `servicecatalog` and rewritten onto an internal `/servicecatalog-appregistry` prefix so they do not collide with AppConfig or AppIntegrations `/applications` routes.

AWS Service Catalog AppRegistry entered maintenance mode on 2026-07-30. New-customer `CreateApplication` calls are denied with `AccessDeniedException` (HTTP 403) whose message includes `maintenance mode`. Get, list, update, delete, and tag operations still work for applications that already exist.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateApplication` | `POST /applications` | Denied with `AccessDeniedException` (maintenance mode) |
| `GetApplication` | `GET /applications/{application}` | Return an application by name, ID, or ARN |
| `UpdateApplication` | `PATCH /applications/{application}` | Update name and/or description |
| `DeleteApplication` | `DELETE /applications/{application}` | Delete an application with no associated resources |
| `ListApplications` | `GET /applications` | List application summaries |
| `ListTagsForResource` | `GET /tags/{resourceArn}` | List tags on an application |
| `TagResource` | `POST /tags/{resourceArn}` | Add or update tags |
| `UntagResource` | `DELETE /tags/{resourceArn}?tagKeys=` | Remove tags |

`application` is the application name, ID, or ARN (`arn:aws:servicecatalog:<region>:<account>:/applications/<id>`).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_APPREGISTRY_ENABLED` | `true` | Enable or disable AppRegistry |
| `FLOCI_STORAGE_SERVICES_APPREGISTRY_MODE` | *(inherits global)* | Optional AppRegistry storage-mode override |
| `FLOCI_STORAGE_SERVICES_APPREGISTRY_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |
