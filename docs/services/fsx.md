# Amazon FSx

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSSimbaAPIService_v20180301.<Action>`)
**Endpoint:** `POST http://localhost:4566/`, signed for `fsx`.

Floci runs FSx's control plane for the four file system types (Lustre, Windows File Server, NetApp ONTAP and
OpenZFS) and Lustre file caches: their placement in VPCs, backups, volumes, storage virtual machines, snapshots,
data repositories and tasks, aliases, S3 access point attachments and tags. Floci serves no file system protocol:
a file system cannot be mounted.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AssociateFileSystemAliases` | Add DNS aliases to a Windows file system |
| `CopyBackup` | Copy a backup, within or across Regions |
| `CreateBackup` | Back up a file system or ONTAP volume |
| `CreateFileCache` | Create a Lustre file cache |
| `CreateFileSystem` | Create a Lustre, Windows, ONTAP or OpenZFS file system |
| `CreateFileSystemFromBackup` | Restore a file system from a backup |
| `DeleteBackup` | Delete a backup |
| `DeleteFileCache` | Delete a file cache |
| `DeleteFileSystem` | Delete a file system, with a final backup unless skipped |
| `DescribeBackups` | Describe backups |
| `DescribeFileCaches` | Describe file caches |
| `DescribeFileSystemAliases` | List a Windows file system's aliases |
| `DescribeFileSystems` | Describe file systems |
| `DescribeSharedVpcConfiguration` | Read the shared VPC setting |
| `DisassociateFileSystemAliases` | Remove DNS aliases from a Windows file system |
| `ReleaseFileSystemNfsV3Locks` | Release an OpenZFS file system's NFSv3 locks |
| `StartMisconfiguredStateRecovery` | Recover a misconfigured file system |
| `UpdateFileCache` | Update a file cache |
| `UpdateFileSystem` | Update a file system's capacity or settings |
| `UpdateSharedVpcConfiguration` | Set the shared VPC setting |
| `CopySnapshotAndUpdateVolume` | Update an OpenZFS volume from another's snapshot |
| `CreateAndAttachS3AccessPoint` | Attach an S3 access point to a volume |
| `CreateSnapshot` | Snapshot an OpenZFS volume |
| `CreateStorageVirtualMachine` | Create an ONTAP storage virtual machine |
| `CreateVolume` | Create an ONTAP or OpenZFS volume |
| `CreateVolumeFromBackup` | Restore an ONTAP volume from a backup |
| `DeleteSnapshot` | Delete a snapshot |
| `DeleteStorageVirtualMachine` | Delete a storage virtual machine |
| `DeleteVolume` | Delete a volume |
| `DescribeS3AccessPointAttachments` | Describe S3 access point attachments |
| `DescribeSnapshots` | Describe snapshots |
| `DescribeStorageVirtualMachines` | Describe storage virtual machines |
| `DescribeVolumes` | Describe volumes |
| `DetachAndDeleteS3AccessPoint` | Detach and delete an S3 access point |
| `RestoreVolumeFromSnapshot` | Roll an OpenZFS volume back to a snapshot |
| `UpdateSnapshot` | Rename a snapshot |
| `UpdateStorageVirtualMachine` | Update a storage virtual machine |
| `UpdateVolume` | Update a volume |
| `CancelDataRepositoryTask` | Cancel a running data repository task |
| `CreateDataRepositoryAssociation` | Link a Lustre directory to an S3 prefix |
| `CreateDataRepositoryTask` | Run an import, export or release task |
| `DeleteDataRepositoryAssociation` | Unlink a data repository |
| `DescribeDataRepositoryAssociations` | Describe data repository associations |
| `DescribeDataRepositoryTasks` | Describe data repository tasks |
| `UpdateDataRepositoryAssociation` | Update a data repository association |
| `ListTagsForResource` | List a resource's tags |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
<!-- floci:actions:end -->

## File systems

A file system is placed in its subnets, which must share a VPC, with a network interface in each carrying its
security groups; Lustre takes one subnet, Multi-AZ Windows, ONTAP and OpenZFS two with a preferred one. Storage
capacity, throughput and per-unit throughput are checked as FSx checks them per deployment type (Lustre scratch
capacities of 1200 GiB and steps of 2400 or 3600, persistent throughput tiers, Windows, ONTAP and OpenZFS throughput
levels). A Windows file system joins an AWS Managed Microsoft AD (`ActiveDirectoryId`) or a self-managed domain;
persistent file systems are encrypted with the KMS key given or `aws/fsx`. A file system is `CREATING`, then
`AVAILABLE`; an update is `UPDATING` with its administrative actions until it completes; a deletion is `DELETING`
until it is gone, releasing its network interfaces and, for Windows and OpenZFS unless skipped, taking a final
backup. An ONTAP file system with storage virtual machines, or an OpenZFS one with child volumes, cannot be deleted.

## Backups, volumes and snapshots

Backups are of a file system, or of an ONTAP volume; scratch file systems take none, and one backup of a resource
runs at a time. Backups copy within and across Regions and restore into new file systems or ONTAP volumes; a backup
being created or copied cannot be deleted. An OpenZFS file system has a root volume, `/fsx`, under which child
volumes nest by path and take snapshots; a volume rolls back to a snapshot only by dropping later ones
(`DELETE_INTERMEDIATE_SNAPSHOTS`), and is deleted with its children and snapshots only with
`DELETE_CHILD_VOLUMES_AND_SNAPSHOTS`. An ONTAP storage virtual machine gets a root volume and endpoints; its volumes
mount at unique junction paths and get a final backup on deletion unless skipped.

## Data repositories

Lustre `PERSISTENT_2` and `SCRATCH_2` file systems link directories to S3 prefixes through up to eight
non-overlapping data repository associations (older deployments through `ImportPath`); file caches link to S3 or NFS.
A data repository task counts the S3 objects an import covers under its associations' prefixes and writes its report
to `<Report.Path>/<TaskId>/failures/`; floci holds no file data, so an export or release moves nothing. One task runs
on a file system at a time, and an ended task cannot be cancelled.

## Configuration

```yaml
floci:
  services:
    fsx:
      enabled: true
      transition-millis: 2000   # how long a resource stays in a transitional lifecycle state
```
