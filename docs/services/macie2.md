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
| `ListManagedDataIdentifiers` | Lists the managed data identifiers that Floci's classification jobs evaluate. |
| `ListClassificationJobs` | Lists job summaries with `filterCriteria`, `sortCriteria`, and pagination. |
| `CreateClassificationJob` | Creates a one-time or scheduled job and runs it against Floci's S3 objects. |
| `DescribeClassificationJob` | Returns the job definition, status, statistics, and last run time. |
| `UpdateClassificationJob` | Cancels, pauses (`USER_PAUSED`), or resumes (`RUNNING`) a job. |
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

## Classification jobs

`CreateClassificationJob` validates the request as Macie does (`clientToken`, `name`, `jobType`, `s3JobDefinition` with either `bucketDefinitions` or `bucketCriteria`, `scheduleFrequency` only for `SCHEDULED` jobs, `managedDataIdentifierSelector` with `managedDataIdentifierIds`, existing `customDataIdentifierIds` and `allowListIds`, `samplingPercentage`, and tags). Reusing a `clientToken` returns the original job; reusing it with different parameters returns `ConflictException`.

Jobs run on a bounded background worker pool and read objects from Floci's S3 service in the same account and Region:

- A `ONE_TIME` job starts `RUNNING` and becomes `COMPLETE`. A `SCHEDULED` job is `IDLE` between runs and `RUNNING` during a run. With `initialRun`, the first run starts immediately and analyzes existing objects. Later runs analyze only objects created or changed since the previous run started. Scheduled runs start at 00:00 UTC on the scheduled day, and a `dayOfMonth` that a month lacks skips that month.
- Objects are decoded as UTF-8 text and evaluated with the selected managed data identifiers, the job's custom data identifiers (regex, keywords, `maximumMatchDistance`, `ignoreWords`, `severityLevels`), and regex allow lists. Binary objects and objects larger than 20 MiB are skipped. A missing bucket, a bucket in another Region, or an unreadable object sets `lastRunErrorStatus.code` to `ERROR`.
- Each object with sensitive data produces one `SensitiveData:S3Object/*` finding with `classificationDetails` (job ID and ARN, `result.sensitiveData`, `result.customDataIdentifiers`, line-range occurrences) and `resourcesAffected.s3Bucket` / `s3Object`. Findings are returned by `ListFindings`, `GetFindings`, and `GetFindingStatistics`, and `ARCHIVE` findings filters apply to them.
- `UpdateClassificationJob` accepts `CANCELLED` (from `IDLE`, `PAUSED`, `RUNNING`, or `USER_PAUSED`), `USER_PAUSED` (from `IDLE`, `PAUSED`, or `RUNNING`), and `RUNNING` (from `USER_PAUSED`). Other values return `ValidationException`; a transition that the current status does not allow returns `ConflictException`. Cancelling stops a running job before it commits further findings; a paused run resumes where it stopped. Paused jobs expire after 30 days.
- `DisableMacie` deletes every job and stops in-flight runs.

Floci implements these managed data identifiers: `AWS_CREDENTIALS`, `CREDIT_CARD_NUMBER`, `EMAIL_ADDRESS`, `OPENSSH_PRIVATE_KEY`, `PGP_PRIVATE_KEY`, `PKCS`, and `USA_SOCIAL_SECURITY_NUMBER`. `INCLUDE` with any other identifier returns `ValidationException`. Occurrences are reported as line ranges for every text format (Macie reports cells, records, or pages for structured and document formats). Bucket criteria on `S3_BUCKET_EFFECTIVE_PERMISSION` or `S3_BUCKET_SHARED_ACCESS` return `ValidationException`. Detailed sensitive data discovery results are not written to S3, and findings are not published to EventBridge or Security Hub.

AWS also models provider-side `InternalServerException`, `ServiceQuotaExceededException`, and `ThrottlingException`; these are not injected artificially.

See the [Amazon Macie API Reference](https://docs.aws.amazon.com/macie/latest/APIReference/Welcome.html).
