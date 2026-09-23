# Amazon Detective

Floci implements the REST JSON Detective organization and behavior-graph operations used by local security-governance workflows.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ListOrganizationAdminAccounts` | Lists the Detective administrator account configured for the organization. |
| `EnableOrganizationAdminAccount` | Designates a Detective administrator account and enables its organization behavior graph. |
| `CreateGraph` | - |
| `DeleteGraph` | - |
| `ListGraphs` | Lists behavior graphs for the current account and Region. |
| `DescribeOrganizationConfiguration` | Returns the organization auto-enable setting for a behavior graph. |
| `UpdateOrganizationConfiguration` | Updates the organization auto-enable setting for a behavior graph. |
| `ListMembers` | Lists organization member accounts in a behavior graph. |
| `GetMembers` | - |
| `ListInvitations` | - |
| `ListInvestigations` | - |
| `ListDatasourcePackages` | - |
| `BatchGetGraphMemberDatasources` | Returns datasource ingest history for accounts in the caller's behavior graph; unknown accounts are returned as unprocessed. |
| `BatchGetMembershipDatasources` | Returns the caller's datasource ingest history in each requested behavior graph; unknown graphs and non-memberships are returned as unprocessed. |
| `UpdateDatasourcePackages` | - |
| `StartInvestigation` | - |
| `GetInvestigation` | - |
| `ListIndicators` | - |
| `UpdateInvestigationState` | - |
| `CreateMembers` | Enables organization accounts as behavior-graph members. |
| `StartMonitoringMember` | Starts data contribution for an accepted but disabled member account. |
<!-- floci:actions:end -->

For organization behavior graphs, member accounts can be created without an email address. They remain `ACCEPTED_BUT_DISABLED`; cross-account member ingestion is not implemented. Other members require an email address and remain `INVITED`. Duplicate member requests are returned through `UnprocessedAccounts`, while successfully processed accounts are returned through `Members`. `ListMembers` accepts the AWS-documented `MaxResults` range of 1 through 200.

Organization configuration accepts an optional `AutoEnable` field and requires the behavior graph ARN. Successful `EnableOrganizationAdminAccount` and `UpdateOrganizationConfiguration` operations return an empty HTTP 200 response body, matching the AWS API contract. `StartMonitoringMember` validates the member's state but rejects unsupported member ingestion without changing it.

## Core datasource collection

`ListDatasourcePackages` returns the graph's `DETECTIVE_CORE` ingest state and persisted `LastIngestStateChange` timestamps. With local CloudTrail enabled, it collects actual management events from Floci's CloudTrail event history into the graph and reports `STARTED`. An empty history is valid: it does not produce synthetic events. With CloudTrail disabled, it reports `DISABLED` and does not read or invent event data.

Collection is materialized on datasource reads, not by a background worker. It follows CloudTrail pagination, deduplicates event IDs, and only accepts management events for the graph owner's account and Region since graph creation. Legacy graphs without a creation timestamp begin collection at their first datasource read. Event records and ingest-state changes use the existing Detective storage backend and survive persistence reloads. Deleting the graph removes its collected events; a replacement never inherits its predecessor's data. A failed collection returns an error without committing a partial batch or reporting a new successful ingest state.

This is a limited local core datasource: it consumes the management events captured by Floci's CloudTrail implementation, not VPC flow logs or GuardDuty findings. It does not perform AWS behavioral analytics. EKS audit and Security Hub datasource ingestion, datasource updates, cross-account member collection, and investigation execution remain unsupported and are rejected explicitly. Listing investigations on a fresh graph remains empty; collecting management events does not fabricate investigations or indicators.

`BatchGetGraphMemberDatasources` and `BatchGetMembershipDatasources` report the same persisted core ingest history for the graph owner. Member accounts are reported with an empty ingest history because member ingestion is not implemented. Accounts that are not in the graph, and graphs that do not exist or where the caller has no membership, are returned through `UnprocessedAccounts` and `UnprocessedGraphs`.

The datasource listing accepts `MaxResults` from 1 through 200. Only the local core package is exposed, so there is no continuation token; supplied `NextToken` values are rejected. Missing, deleted, foreign-account, and wrong-Region graph ARNs are rejected before reading any source events.

Invalid graph, account, member, and pagination data returns modeled `ValidationException` or `ResourceNotFoundException` responses. Incompatible member transitions return `ConflictException`, and the 1,200-member behavior-graph quota is enforced with `ServiceQuotaExceededException`. Provider-side internal and throttling errors are not injected artificially.

See the [Amazon Detective API Reference](https://docs.aws.amazon.com/detective/latest/APIReference/Welcome.html).
