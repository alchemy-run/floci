# Incident Manager

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/createReplicationSet` and the rest, signed for `ssm-incidents`

Incident Manager tracks incidents from response plans and engages the people who handle them.

## Onboarding

A replication set onboards the account in its Regions. An account has one, and it's `CREATING`, then `ACTIVE`.
Response plans, incidents and [SSM Contacts](ssm-contacts.md) need it. Deleting it offboards the account: its
response plans, incidents and contacts go with it. With deletion protection on, it can't be deleted.

## Response plans and incidents

A response plan holds an incident template: title, impact from 1 to 5, summary, dedupe string, SNS notification
targets and tags. It also names the SSM Contacts contacts to engage. Topics and contacts must exist.

`StartIncident` creates an incident record from the plan:

- It publishes to the plan's notification topics.
- It engages the plan's contacts.
- It records `SSM Incident Trigger` and `SSM Contacts Page for Incident` events on the timeline.

An open incident with the same dedupe string takes the new trigger instead of a second incident. Resolving an
incident sets `resolvedTime` and notifies the topics again.

Timeline events of type `Custom Event` or `Note` can be added, and can reference the incident's related items.
Findings are deployments AWS correlates with an incident; Floci correlates none, so an incident has no findings.
Response plan actions are checked but not run, since Floci has no SSM Automation.

## Configuration

```yaml
floci:
  services:
    ssm-incidents:
      enabled: true
      transition-millis: 2000   # how long the replication set takes to create, update and delete
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateReplicationSet` | Onboard the account in 1 to 3 Regions |
| `GetReplicationSet` | Describe the replication set |
| `ListReplicationSets` | List the account's replication set |
| `UpdateReplicationSet` | Add or remove Regions |
| `UpdateDeletionProtection` | Turn deletion protection on or off |
| `DeleteReplicationSet` | Offboard the account |
| `CreateResponsePlan` | Create a response plan |
| `GetResponsePlan` | Describe a response plan |
| `ListResponsePlans` | List response plans |
| `UpdateResponsePlan` | Change a response plan |
| `DeleteResponsePlan` | Delete a response plan |
| `PutResourcePolicy` | Share a response plan with a policy |
| `GetResourcePolicies` | List a response plan's policies |
| `DeleteResourcePolicy` | Remove a policy |
| `StartIncident` | Start an incident, notifying topics and engaging contacts |
| `GetIncidentRecord` | Describe an incident |
| `ListIncidentRecords` | List incidents, by creation time, impact, status or creator |
| `UpdateIncidentRecord` | Change or resolve an incident |
| `DeleteIncidentRecord` | Delete an incident |
| `CreateTimelineEvent` | Add a custom event or note |
| `GetTimelineEvent` | Describe a timeline event |
| `UpdateTimelineEvent` | Change a timeline event |
| `DeleteTimelineEvent` | Remove a timeline event |
| `ListTimelineEvents` | List an incident's timeline |
| `UpdateRelatedItems` | Add or remove a related item |
| `ListRelatedItems` | List an incident's related items |
| `ListIncidentFindings` | List an incident's findings (none in Floci) |
| `BatchGetIncidentFindings` | Describe findings by id |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags |
| `ListTagsForResource` | List a resource's tags |
<!-- floci:actions:end -->
