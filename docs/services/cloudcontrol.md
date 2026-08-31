# AWS Cloud Control API

**Protocol:** JSON 1.0 (`X-Amz-Target: CloudApiService.*`)
**Endpoint:** `POST http://localhost:4566/`
**Signing name:** `cloudcontrolapi`

Cloud Control is a thin resource-type API over CloudFormation types. Create,
update, and delete return a `ProgressEvent` immediately as `SUCCESS` (the
emulator has no async handler runtime). `GetResourceRequestStatus` replays the
stored token.

## Supported Actions

| Action | Description |
| --- | --- |
| `CreateResource` | Create `AWS::SSM::Parameter` from `DesiredState` |
| `GetResource` | Read current properties; missing resources are `ResourceNotFoundException` (HTTP 404) |
| `UpdateResource` | Apply an RFC 6902 `PatchDocument` (SSM Parameter `Value` and other mutable fields) |
| `DeleteResource` | Delete the resource; missing resources are `ResourceNotFoundException` |
| `GetResourceRequestStatus` | Return the stored `ProgressEvent` for a request token |
| `ListResourceRequests` | List stored operation requests, optionally filtered |
| `CancelResourceRequest` | Cancel `PENDING` / `IN_PROGRESS` requests |
| `ListResources` | List S3 buckets, EC2 VPC/subnet/security groups, IAM users/roles, SSM parameters |

## Resource types

| Type | Create / Update / Delete | List / Get |
| --- | --- | --- |
| `AWS::SSM::Parameter` | yes | yes |
| `AWS::S3::Bucket` | no (`UnsupportedActionException`) | yes |
| `AWS::EC2::VPC` | no | yes |
| `AWS::EC2::Subnet` | no | yes |
| `AWS::EC2::SecurityGroup` | no | yes |
| `AWS::IAM::Role` | no | yes |
| `AWS::IAM::User` | no | yes |

SSM Parameter identifiers are the parameter `Name`. Properties use CloudFormation
names (`Name`, `Type`, `Value`, `Description`, `DataType`, `Tier`, `Tags`).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CLOUDCONTROL_ENABLED` | `true` | Enable or disable Cloud Control |
