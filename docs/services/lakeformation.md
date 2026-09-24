# Lake Formation

The `lakeformation` service allows you to manage data lake settings, resources, permissions, and LF-Tags, serving as the permission layer over the Glue Data Catalog.

## Configuration

| Key | Default | Description |
|---|---|---|
| `floci.services.lakeformation.enabled` | `true` | Enable or disable the Lake Formation service. |
| `floci.storage.services.lakeformation.mode` | *inherited* | Storage backend for Lake Formation settings and permissions: `memory`, `persistent`. |
| `floci.storage.services.lakeformation.flush-interval-ms` | `5000` | How often to flush state to disk when using persistent storage. |

## Supported Operations

### Data Lake Settings
*   `PutDataLakeSettings`
*   `GetDataLakeSettings`

### Resource Registration
*   `RegisterResource`
*   `UpdateResource`
*   `DeregisterResource`
*   `ListResources`
*   `DescribeResource`

### Permissions
*   `GrantPermissions`
*   `RevokePermissions`
*   `ListPermissions`

### LF-Tags
*   `CreateLFTag`
*   `GetLFTag`
*   `UpdateLFTag`
*   `DeleteLFTag`
*   `ListLFTags`
*   `AddLFTagsToResource`
*   `RemoveLFTagsFromResource`

### Data Access
*   `GetEffectivePermissionsForPath`
*   `GetTemporaryGlueTableCredentials`
*   `GetTemporaryGluePartitionCredentials`
*   `GetTemporaryDataLocationCredentials`

`GetEffectivePermissionsForPath` returns the grants on data locations, databases and tables
governed by a registered location at or above the path; an unregistered path returns an
empty list. The credential-vending operations require the S3 location to be registered
(`EntityNotFoundException` otherwise), the caller to hold explicit grants on the table
(`AccessDeniedException` otherwise, or `PermissionTypeMismatchException` when only
column/cell-filtered grants exist and the request does not declare that permission type),
and the matching data lake setting (`AllowFullTableExternalDataAccess`, or
`AllowExternalDataFiltering` plus `ExternalDataFilteringAllowList` for filtered access).
Vended credentials are real temporary sessions for the registration role, scoped down to
the vended S3 prefix.

## Implementation Details
Floci currently supports basic CRUD operations for Lake Formation resources, permissions, and tags. This state is strictly persisted without deep integration into other data-plane emulators. 

Permissions retrieval via `ListPermissions` currently lists all explicitly granted permissions to satisfy Terraform state expectations without strict caller permission filtering.
