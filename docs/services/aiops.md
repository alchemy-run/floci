# Amazon CloudWatch Investigations (AIOps)

**Protocol:** REST JSON, signed with `aiops`
**Endpoint:** `http://localhost:4566/investigationGroups` and the shared `/tags/{resourceArn}` routes

An investigation group holds the settings CloudWatch investigations run under: the IAM role they assume, how long they are kept, how they are encrypted, which tag keys bound the resources they look at, where they notify, and which accounts they may read. Floci stores investigation groups; it runs no investigations.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateInvestigationGroup` | Create the Region's investigation group |
| `ListInvestigationGroups` | List the Region's investigation group |
| `GetInvestigationGroup` | Return a group by name, ARN or id |
| `UpdateInvestigationGroup` | Change a group's role, encryption, boundaries and sources |
| `DeleteInvestigationGroup` | Delete a group |
| `PutInvestigationGroupPolicy` | Attach a resource policy |
| `GetInvestigationGroupPolicy` | Return the resource policy |
| `DeleteInvestigationGroupPolicy` | Remove the resource policy |
| `ListTagsForResource` | List a group's tags |
| `TagResource` | Tag a group |
| `UntagResource` | Remove tags from a group |
<!-- floci:actions:end -->

## Investigation groups

An account has at most one investigation group per Region; creating a second is a `ConflictException`. A group is addressed by its name, its ARN (`arn:<partition>:aiops:<region>:<account>:investigation-group/<id>`) or its id.

`CreateInvestigationGroup` needs a role that exists in IAM and trusts `aiops.amazonaws.com`; any other role is a `ValidationException`. Retention is 7 to 90 days and defaults to 90; it can't be changed later. Encryption defaults to `AWS_OWNED_KEY`, and a `CUSTOMER_MANAGED_KMS_KEY` needs a KMS key that exists. A group takes up to 50 tag key boundaries and up to 25 cross-account source roles. `UpdateInvestigationGroup` changes the role, encryption, tag key boundaries, notification channels, CloudTrail event history and cross-account sources.

A resource policy (`PutInvestigationGroupPolicy`) must be a JSON policy document with a `Statement`; it typically lets `aiops.alarms.cloudwatch.amazonaws.com` start investigations from alarms. A group without one answers `GetInvestigationGroupPolicy` with `ResourceNotFoundException`.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_AI_OPS_ENABLED` | `true` | Enable or disable CloudWatch investigations |

For the upstream API shape, see the [CloudWatch investigations API Reference](https://docs.aws.amazon.com/cloudwatchinvestigations/latest/APIReference/Welcome.html).
