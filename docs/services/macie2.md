# Amazon Macie

Floci implements the REST JSON organization surfaces used to configure Macie locally.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `EnableOrganizationAdminAccount` | Designates the delegated Macie administrator account. |
| `GetMacieSession` | Returns the current account Macie session. |
| `EnableMacie` | Enables Macie for the current account. |
| `UpdateMacieSession` | - |
| `DisableMacie` | - |
| `TestCustomDataIdentifier` | - |
| `CreateMember` | - |
| `ListMembers` | - |
| `GetAdministratorAccount` | - |
| `ListInvitations` | - |
| `GetInvitationsCount` | - |
| `CreateSampleFindings` | - |
| `ListFindings` | - |
| `GetFindings` | - |
| `FindingStatistics` | - |
| `GetBucketStatistics` | - |
| `SearchResources` | - |
| `CreateAllowList` | - |
| `GetAllowList` | - |
| `UpdateAllowList` | - |
| `DeleteAllowList` | - |
| `ListAllowLists` | - |
| `CreateCustomDataIdentifier` | - |
| `GetCustomDataIdentifier` | - |
| `DeleteCustomDataIdentifier` | - |
| `ListCustomDataIdentifiers` | - |
| `BatchGetCustomDataIdentifiers` | - |
| `CreateFindingsFilter` | - |
| `GetFindingsFilter` | - |
| `UpdateFindingsFilter` | - |
| `DeleteFindingsFilter` | - |
| `ListFindingsFilters` | - |
| `ListManagedDataIdentifiers` | - |
| `ListClassificationJobs` | - |
| `CreateClassificationJob` | - |
| `DescribeClassificationJob` | - |
| `UpdateClassificationJob` | - |
| `GetExportConfiguration` | - |
| `PutExportConfiguration` | - |
| `GetAutomatedDiscoveryConfiguration` | - |
| `UpdateAutomatedDiscoveryConfiguration` | - |
| `ListClassificationScopes` | - |
| `GetClassificationScope` | - |
| `GetRevealConfiguration` | - |
| `UpdateRevealConfiguration` | - |
| `GetUsageTotals` | - |
| `UpdateOrganizationConfiguration` | Updates organization auto-enable settings as the delegated administrator. |
| `DescribeOrganizationConfiguration` | Reads organization auto-enable settings as the delegated administrator. |
| `ListOrganizationAdminAccounts` | Lists the delegated Macie administrator for the organization. |
<!-- floci:actions:end -->

Delegation is visible in both the management-account and delegated-account request scopes. Designating the delegated administrator enables Macie for that account in the Region, matching AWS Organizations integration behavior.

Before Macie is enabled, `GetMacieSession` and the other session-scoped operations return `AccessDeniedException` ("Macie is not enabled for this account."), as AWS does. Enabling an already enabled session or attempting incompatible administrator state returns `ConflictException`. Request validation and missing-resource behavior use the modeled `ValidationException` and `ResourceNotFoundException` responses.

AWS also models provider-side `InternalServerException`, `ServiceQuotaExceededException`, and `ThrottlingException`; these are not injected artificially.

See the [Amazon Macie API Reference](https://docs.aws.amazon.com/macie/latest/APIReference/Welcome.html).
