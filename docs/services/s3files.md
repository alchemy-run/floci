# S3 Files

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/file-systems` and the rest, signed for `s3files`

A file system presents an S3 bucket, or a prefix of it, as a shared file system.

## File systems

`CreateFileSystem` checks its inputs in the services that own them:

- The bucket must exist and have versioning enabled.
- The role must exist and trust the `elasticfilesystem` service principal.

Two file systems can't cover overlapping prefixes of one bucket. A file system is `creating`, then `available`.
While it exists, S3 refuses `DeleteBucket` on its bucket with `BucketHasS3FileSystemAttached`. A file system with
access points or mount targets is deleted only with `forceDelete`, which removes them too.

Each file system has a resource policy, which must be a JSON document with a `Statement`. It also has a
synchronization configuration:

- Import rules choose which objects load into the file system, and when.
- Expiration rules evict data that hasn't been read for 1 to 365 days.

The configuration starts at version 1. A `PutSynchronizationConfiguration` that names an older version is a conflict.

## Access points and mount targets

An access point fixes the POSIX user and the root directory for clients that use it. A mount target puts the file
system in a subnet. It holds an EC2 network interface with the mount target's security groups, which must be in the
subnet's VPC. A file system's mount targets share one VPC, with at most one per Availability Zone.

Like EFS in Floci, this is the control plane. There's no NFS endpoint to mount.

## Configuration

```yaml
floci:
  services:
    s3files:
      enabled: true
      transition-millis: 2000   # how long file systems, access points and mount targets take to create and delete
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateFileSystem` | Create a file system over a versioned bucket |
| `GetFileSystem` | Describe a file system |
| `ListFileSystems` | List file systems, by bucket |
| `DeleteFileSystem` | Delete a file system, with forceDelete if it has access points or mount targets |
| `PutFileSystemPolicy` | Set a file system's resource policy |
| `GetFileSystemPolicy` | Read a file system's resource policy |
| `DeleteFileSystemPolicy` | Remove a file system's resource policy |
| `PutSynchronizationConfiguration` | Replace the import and expiration rules |
| `GetSynchronizationConfiguration` | Read the import and expiration rules |
| `CreateAccessPoint` | Create an access point with a POSIX user and root directory |
| `GetAccessPoint` | Describe an access point |
| `ListAccessPoints` | List a file system's access points |
| `DeleteAccessPoint` | Delete an access point |
| `CreateMountTarget` | Put the file system in a subnet, with a network interface |
| `GetMountTarget` | Describe a mount target |
| `UpdateMountTarget` | Change a mount target's security groups |
| `ListMountTargets` | List mount targets, by file system or access point |
| `DeleteMountTarget` | Delete a mount target and its network interface |
| `TagResource` | Tag a file system or access point |
| `UntagResource` | Remove tags |
| `ListTagsForResource` | List a file system's or access point's tags |
<!-- floci:actions:end -->
