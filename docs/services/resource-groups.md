# AWS Resource Groups

**Protocol:** REST JSON, signed with `resource-groups`

**Endpoint:** `http://localhost:4566`

Floci emulates AWS Resource Groups: groups defined by a tag query, a CloudFormation stack query, or a service configuration (EC2 capacity reservation pools), their tags, ad-hoc resource searches, and group lifecycle events. This is a separate service from the Resource Groups Tagging API.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateGroup` | Create a group from a `ResourceQuery`, a `Configuration`, or both, with tags |
| `GetGroup` | Read a group by name or ARN |
| `UpdateGroup` | Change `Description`, `Criticality`, `Owner` or `DisplayName`; omitted members keep their values |
| `DeleteGroup` | Delete the group and membership, preserving its reservations |
| `ListGroups` | Page through the Region's groups, filtered by `resource-type`, `configuration-type`, `owner`, `display-name` and `criticality` |
| `GetGroupQuery` | Read a query group's `ResourceQuery` |
| `UpdateGroupQuery` | Replace a group's `ResourceQuery` |
| `GetGroupConfiguration` | Read a group's service configuration |
| `PutGroupConfiguration` | Replace a group's service configuration; a capacity pool's is fixed |
| `GroupResources` | Add verified active capacity reservations to a pool |
| `UngroupResources` | Remove resources from a pool |
| `ListGroupResources` | List a pool's members or what a query group's query selects, with scoped pagination |
| `SearchResources` | Run a `ResourceQuery` without a group |
| `GetTags` | Read a group's tags |
| `Tag` | Add or replace tags on a group |
| `Untag` | Remove tags from a group by key |
| `GetAccountSettings` | Read the account's group lifecycle events status |
| `UpdateAccountSettings` | Turn group lifecycle events on or off |
| `ListGroupingStatuses` | Rejected: grouping statuses exist only for application groups |
| `StartTagSyncTask` | Rejected: tag-sync tasks run only on application groups |
| `GetTagSyncTask` | Always `NotFoundException`, as no tag-sync task exists |
| `CancelTagSyncTask` | Always `NotFoundException`, as no tag-sync task exists |
| `ListTagSyncTasks` | Always an empty list |
<!-- floci:actions:end -->

The routes are AWS's: `POST /groups`, `/get-group`, `/update-group`, `/delete-group`, `/groups-list`, `/get-group-query`, `/update-group-query`, `/get-group-configuration`, `/put-group-configuration`, `/group-resources`, `/ungroup-resources`, `/list-group-resources`, `/resources/search`, the account settings and tag-sync routes, and `GET`/`PUT`/`PATCH /resources/{Arn}/tags`. Each is served only for a request signed for `resource-groups`; the same paths signed for S3 keep S3's path-style routing. Get, delete and list also accept the deprecated `GroupName` selector. Supplying both `Group` and `GroupName` is rejected.

With IAM enforcement enabled, signed operations are checked against their `resource-groups:` actions and the selected group ARN.

## Query groups

A group's `ResourceQuery` is evaluated whenever the group is listed, so it always reflects current tags and stacks.

- **`TAG_FILTERS_1_0`** selects the account's resources in the Region whose tags match every `TagFilters` entry: one of its `Values`, or any value when it lists none. Tags come from the Resource Groups Tagging service, so any resource whose tags the Tagging API reports is found.
- **`CLOUDFORMATION_STACK_1_0`** selects the resources of the stack named by `StackIdentifier` (its ARN) that have an ARN. A stack that does not exist yields an empty list and a `CLOUDFORMATION_STACK_NOT_EXISTING` entry in `QueryErrors`.

`ResourceTypeFilters` narrows either query to CloudFormation types such as `AWS::SQS::Queue`, or `AWS::AllSupported` (the default) for all. A resource's type is reported in CloudFormation form, derived from its ARN for a tag query and taken from the stack for a stack query. A malformed query is rejected with `BadRequestException` when the group is created or updated, or when `SearchResources` runs it.

A query group takes no members: `GroupResources` and `UngroupResources` answer `BadRequestException`.

## Capacity pools

Create a pool with this public AWS service configuration:

```json
[
  {"Type": "AWS::EC2::CapacityReservationPool"},
  {
    "Type": "AWS::ResourceGroups::Generic",
    "Parameters": [
      {"Name": "allowed-resource-types", "Values": ["AWS::EC2::CapacityReservation"]}
    ]
  }
]
```

A pool cannot also have a resource query, and its configuration cannot be changed afterwards: `PutGroupConfiguration` accepts the same two items again, in any order, and rejects anything else. `Criticality`, `Owner` and `DisplayName` are rejected as unsupported parameters on a pool, matching live AWS.

`GroupResources` accepts one to ten resource ARNs. Each successful member must be an actual active EC2 capacity reservation in the same account, region and partition. RAM-shared reservations are not accepted; reservations must be owned by the group account. Missing, foreign and inactive reservations appear in `Failed`; they are never inserted. A resource that is not a capacity reservation fails with `ResourceArnValidationException`. Repeated adds preserve one membership. Unexpected EC2 lookup errors remain errors and do not partially change membership. `UngroupResources` removes members; a capacity reservation that was not a member is reported in `Succeeded`, as it is not a member afterwards.

`ListGroupResources` returns both `Resources.Identifier` and the deprecated `ResourceIdentifiers` representation, and `QueryErrors`. It supports a `resource-type` filter and pages of one to fifty entries. Tokens bind the group generation and filter to an ordered snapshot, so later additions do not change an existing traversal. Tokens expire after fifteen minutes without a successful scoped read; the instance retains at most 256 tokens and evicts the least recently used token at capacity. Tokens remain retryable until expiry, eviction, group deletion or emulator restart/reset. Invalid tokens return `BadRequestException`.

Deleting a group removes its membership and tokens. It does not cancel or delete the EC2 reservations. EC2 launches that target the pool consume matching available member reservations; the launch preference controls On-Demand fallback. No asynchronous membership transition is simulated: accepted additions complete synchronously and `Pending` is empty.

Other service configurations are stored and echoed: `AWS::ResourceGroups::Generic` on its own, and `AWS::EC2::HostManagement`, which needs `any-host-based-license-configuration` or `allowed-host-based-license-configurations`. Floci allocates no hosts for them, and their `GroupResources` calls fail each resource with `ResourceArnValidationException`.

## Tags

A group's tags are set on `CreateGroup` and changed with `Tag` and `Untag` on `/resources/{Arn}/tags`; keys beginning with `aws:` are reserved and a group holds at most 50 tags. Groups are also resources of the Resource Groups Tagging API and Resource Explorer (`resource-groups:group`), so a tag query can select groups and the Tagging API can tag them.

## Group lifecycle events

`GetAccountSettings` reports `INACTIVE` until `UpdateAccountSettings` sets `GroupLifecycleEventsDesiredStatus`; the status follows at once. While it is `ACTIVE`, creating, updating and deleting a group publishes a `ResourceGroups Group State Change` event to the default EventBridge bus (source `aws.resource-groups`), with the group's `arn` and `name`, the `state-change` and its `new-state`. Adding or removing pool members publishes `ResourceGroups Group Membership Change` with the changed `resources`. Query groups publish no membership events.

## Application groups

Application groups (myApplications) cannot be created through `CreateGroup`, so the operations that apply only to them answer as AWS does for any other group: `ListGroupingStatuses` and `StartTagSyncTask` reject the group with `BadRequestException` (and `NotFoundException` for a group that does not exist), `GetTagSyncTask` and `CancelTagSyncTask` answer `NotFoundException`, and `ListTagSyncTasks` returns an empty list.

## Storage

Groups, membership and account settings follow the configured Floci storage mode. Pagination tokens of `ListGroupResources` are in memory and are intentionally invalidated by restart/reset.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_RESOURCE_GROUPS_ENABLED` | `true` | Enable the Resource Groups service |
| `FLOCI_STORAGE_SERVICES_RESOURCE_GROUPS_MODE` | global storage mode | Storage mode for groups, membership and account settings |
| `FLOCI_STORAGE_SERVICES_RESOURCE_GROUPS_FLUSH_INTERVAL_MS` | `5000` | Storage flush interval |

Requests use botocore's modeled field, tag-pattern and size constraints for every operation; unknown fields are ignored. The public wire contracts are documented in the [AWS Resource Groups API Reference](https://docs.aws.amazon.com/ARG/latest/APIReference/Welcome.html).
