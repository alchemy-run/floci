# AWS Resource Access Manager (RAM)

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the RAM resource-share lifecycle and the read APIs Alchemy bindings exercise: listing shares, associations, invitations, resources, principals, resource policies, and the AWS-managed permission catalog.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateResourceShare` | `POST /createresourceshare` | Create a resource share |
| `GetResourceShares` | `POST /getresourceshares` | List or look up shares owned by this account |
| `UpdateResourceShare` | `POST /updateresourceshare` | Update name / `allowExternalPrincipals` |
| `DeleteResourceShare` | `DELETE /deleteresourceshare` | Delete a share by ARN |
| `AssociateResourceShare` | `POST /associateresourceshare` | Attach principals, resources, or sources |
| `DisassociateResourceShare` | `POST /disassociateresourceshare` | Detach principals, resources, or sources |
| `GetResourceShareAssociations` | `POST /getresourceshareassociations` | List principal/resource/source associations |
| `GetResourceShareInvitations` | `POST /getresourceshareinvitations` | List invitations sent or received |
| `AcceptResourceShareInvitation` | `POST /acceptresourceshareinvitation` | Accept a pending invitation |
| `RejectResourceShareInvitation` | `POST /rejectresourceshareinvitation` | Reject a pending invitation |
| `ListPendingInvitationResources` | `POST /listpendinginvitationresources` | Resources offered by an invitation |
| `ListResources` | `POST /listresources` | Resources this account shares |
| `ListPrincipals` | `POST /listprincipals` | Principals this account shares with |
| `GetResourcePolicies` | `POST /getresourcepolicies` | RAM policies on resource ARNs |
| `CreatePermission` | `POST /createpermission` | Create a customer-managed permission |
| `GetPermission` | `POST /getpermission` | Fetch a permission by ARN (optional version) |
| `ListPermissions` | `POST /listpermissions` | AWS-managed and customer-managed permissions |
| `ListPermissionVersions` | `POST /listpermissionversions` | List versions of a permission |
| `CreatePermissionVersion` | `POST /createpermissionversion` | Publish a new default permission version |
| `DeletePermissionVersion` | `DELETE /deletepermissionversion` | Delete a non-default permission version |
| `DeletePermission` | `DELETE /deletepermission` | Delete a customer-managed permission |
| `TagResource` | `POST /tagresource` | Add tags to a share or permission |
| `UntagResource` | `POST /untagresource` | Remove tags from a share or permission |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_RAM_ENABLED` | `true` | Enable or disable RAM |
| `FLOCI_STORAGE_SERVICES_RAM_MODE` | *(inherits global)* | Optional RAM storage-mode override |
| `FLOCI_STORAGE_SERVICES_RAM_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |
