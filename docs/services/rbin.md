# Recycle Bin

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/rules` and the rest, signed for `rbin`

Retention rules keep deleted EBS snapshots, AMIs and EBS volumes in the Recycle Bin, where EC2 lists and restores them.

## Rules

A rule names a resource type (`EBS_SNAPSHOT`, `EC2_IMAGE` or `EBS_VOLUME`) and a retention of 1 to 365 days. It's
`pending`, then `available`, after it's created or updated. A rule with resource tags covers the resources that carry
any of them (a tag without a value matches any value); a Region-level rule, with no resource tags, covers every resource
of its type except those with its exclusion tags. When several rules cover a resource, the longest retention wins.

Only Region-level rules lock. A locked rule can't be updated or deleted. `UnlockRule` starts its unlock delay
(7 to 30 days, as `pending_unlock`), after which it's `unlocked`.

## In EC2

`DeleteSnapshot`, `DeregisterImage` and `DeleteVolume` move a covered resource to the Recycle Bin with its tags; an AMI
keeps its captured file system, and a volume its data. `ListSnapshotsInRecycleBin`, `ListImagesInRecycleBin` and
`ListVolumesInRecycleBin` show what's there with when it entered and leaves; `RestoreSnapshotFromRecycleBin`,
`RestoreImageFromRecycleBin` and `RestoreVolumeFromRecycleBin` bring a resource back, tags included. Once its retention
ends, a resource is deleted for good.

## Configuration

```yaml
floci:
  services:
    rbin:
      enabled: true
      transition-millis: 2000   # how long a new or changed rule stays pending
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateRule` | Create a retention rule |
| `GetRule` | Describe a rule |
| `UpdateRule` | Change a rule's retention or tags |
| `DeleteRule` | Delete an unlocked rule |
| `ListRules` | List a resource type's rules |
| `LockRule` | Lock a Region-level rule |
| `UnlockRule` | Start a rule's unlock delay |
| `TagResource` | Tag a rule |
| `UntagResource` | Remove tags from a rule |
| `ListTagsForResource` | List a rule's tags |
<!-- floci:actions:end -->
