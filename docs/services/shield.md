# Shield

**Protocol:** JSON 1.1
**Endpoint:** `POST /` + `X-Amz-Target: AWSShield_20160616.*`

Shield Advanced protects resources against DDoS attacks.

## Subscription

`GetSubscriptionState` and `DescribeAttackStatistics` answer any account. Without a subscription, the subscription,
protection, group and tag APIs fail with `The subscription does not exist.`, as AWS does. A missing protection group
is reported before the missing subscription. A subscription runs for a year from `CreateSubscription`, and it can't
be deleted inside that commitment (`LockedSubscriptionException`).

## Protections and groups

A protection names a resource that must exist in the service that owns it. Shield protects Elastic IPs,
Application and Classic Load Balancers, CloudFront distributions, Route 53 hosted zones and Global Accelerator
accelerators. A resource takes one protection, and a protection takes one Route 53 health check. Application-layer
automatic response is for CloudFront distributions and Application Load Balancers, and it needs an AWS WAF web ACL
on the resource.

A group's members follow its pattern:

- `ALL` covers every protected resource.
- `BY_RESOURCE_TYPE` covers those of its type.
- `ARBITRARY` lists its members, which must be protected.

Deleting a protection takes it out of every group.

## Response team and contacts

The Shield Response Team role must trust `drt.shield.amazonaws.com` and have `AWSShieldDRTAccessPolicy` attached.
Log buckets, at most 10, need that role first and must exist. Proactive engagement needs an emergency contact with a
phone number. No attacks are detected, so attack listings are empty.

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `GetSubscriptionState` | Whether the account has Shield Advanced |
| `CreateSubscription` | Subscribe for a year |
| `DescribeSubscription` | Describe the subscription and its limits |
| `UpdateSubscription` | Turn auto-renewal on or off |
| `DeleteSubscription` | End the subscription after its commitment |
| `CreateProtection` | Protect a resource that exists |
| `DescribeProtection` | Describe a protection, by id or resource |
| `DeleteProtection` | Remove a protection and its group memberships |
| `ListProtections` | List protections, by ARN, name or type |
| `AssociateHealthCheck` | Attach a Route 53 health check |
| `DisassociateHealthCheck` | Detach a health check |
| `EnableApplicationLayerAutomaticResponse` | Turn on automatic layer-7 mitigation where a web ACL is in front |
| `UpdateApplicationLayerAutomaticResponse` | Change the mitigation's action |
| `DisableApplicationLayerAutomaticResponse` | Turn off automatic layer-7 mitigation |
| `CreateProtectionGroup` | Create a group by pattern |
| `DescribeProtectionGroup` | Describe a group and its members |
| `UpdateProtectionGroup` | Change a group's aggregation or pattern |
| `DeleteProtectionGroup` | Delete a group |
| `ListProtectionGroups` | List groups, by id, pattern, type or aggregation |
| `ListResourcesInProtectionGroup` | List the resources a group covers |
| `AssociateDRTRole` | Grant the response team a role |
| `DisassociateDRTRole` | Revoke the response team's role |
| `AssociateDRTLogBucket` | Grant the response team a log bucket |
| `DisassociateDRTLogBucket` | Revoke a log bucket |
| `DescribeDRTAccess` | Read the response team's access |
| `UpdateEmergencyContactSettings` | Set emergency contacts |
| `DescribeEmergencyContactSettings` | Read emergency contacts |
| `AssociateProactiveEngagementDetails` | Set contacts and start proactive engagement |
| `EnableProactiveEngagement` | Turn proactive engagement on |
| `DisableProactiveEngagement` | Turn proactive engagement off |
| `ListAttacks` | List attacks (none are detected) |
| `DescribeAttack` | Describe an attack |
| `DescribeAttackStatistics` | Read the year's attack statistics |
| `TagResource` | Tag a protection or group |
| `UntagResource` | Remove tags |
| `ListTagsForResource` | List a resource's tags |
<!-- floci:actions:end -->
