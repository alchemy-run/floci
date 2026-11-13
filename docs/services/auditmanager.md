# AWS Audit Manager

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/` with Audit Manager's paths (`/account`, `/assessments`, `/assessmentFrameworks`, `/controls`, `/insights`, …), signed for `auditmanager`; tags on `/tags/{resourceArn}`.

Floci runs Audit Manager per account and Region: the control and framework libraries, assessments with their reviews, delegations and evidence, signed assessment reports in S3, and insights.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `GetAccountStatus` | Whether the account is registered in the Region |
| `RegisterAccount` | Register the account, optionally with a KMS key and delegated administrator |
| `DeregisterAccount` | Deregister, deleting data under the ALL policy |
| `RegisterOrganizationAdminAccount` | Register a delegated administrator in Organizations |
| `DeregisterOrganizationAdminAccount` | Remove the delegated administrator |
| `GetOrganizationAdminAccount` | The organization's delegated administrator |
| `GetSettings` | Read one settings attribute, or all |
| `UpdateSettings` | Change SNS topic, defaults, KMS key, evidence finder and policies |
| `GetServicesInScope` | AWS services an assessment can scope |
| `ListKeywordsForDataSource` | Keywords a data source can map to |
| `GetEvidenceFileUploadUrl` | A URL to PUT a manual evidence file to |
| `CreateControl` | Create a custom control with its mapping sources |
| `ListControls` | Page through Core or custom controls |
| `GetControl` | Describe a control |
| `UpdateControl` | Change a custom control |
| `DeleteControl` | Delete a custom control and remove it from frameworks |
| `CreateAssessmentFramework` | Create a custom framework of control sets |
| `ListAssessmentFrameworks` | Page through standard or custom frameworks |
| `GetAssessmentFramework` | Describe a framework with its controls |
| `UpdateAssessmentFramework` | Change a custom framework |
| `DeleteAssessmentFramework` | Delete a custom framework |
| `StartAssessmentFrameworkShare` | Share a framework snapshot with another account and Region |
| `ListAssessmentFrameworkShareRequests` | Page through sent or received share requests |
| `UpdateAssessmentFrameworkShare` | Accept (copying the framework), decline or revoke a share |
| `DeleteAssessmentFrameworkShare` | Delete a share request |
| `CreateAssessment` | Create an assessment from a framework |
| `ListAssessments` | Page through assessments |
| `GetAssessment` | Describe an assessment with evidence counts |
| `UpdateAssessment` | Change an active assessment's name, scope, destination and roles |
| `DeleteAssessment` | Delete an assessment and its evidence |
| `UpdateAssessmentStatus` | Make an assessment inactive |
| `UpdateAssessmentControlSetStatus` | Set a control set's review status |
| `UpdateAssessmentControl` | Set a control's status or add a comment |
| `BatchImportEvidenceToAssessmentControl` | Import manual evidence |
| `BatchCreateDelegationByAssessment` | Delegate control sets to resource owners |
| `BatchDeleteDelegationByAssessment` | Remove delegations |
| `GetDelegations` | Page through delegations |
| `ListNotifications` | Page through delegation notifications |
| `GetChangeLogs` | Page through an assessment's change log |
| `GetEvidenceFoldersByAssessment` | Page through an assessment's evidence folders |
| `GetEvidenceFoldersByAssessmentControl` | Page through a control's evidence folders |
| `GetEvidenceFolder` | Describe an evidence folder with its counts |
| `GetEvidenceByEvidenceFolder` | Page through a folder's evidence |
| `GetEvidence` | Describe an evidence item |
| `AssociateAssessmentReportEvidenceFolder` | Select a folder's evidence for reports |
| `DisassociateAssessmentReportEvidenceFolder` | Deselect a folder's evidence |
| `BatchAssociateAssessmentReportEvidence` | Select evidence items for reports |
| `BatchDisassociateAssessmentReportEvidence` | Deselect evidence items |
| `CreateAssessmentReport` | Write a signed report zip to S3 |
| `ListAssessmentReports` | Page through reports |
| `GetAssessmentReportUrl` | A presigned URL to a report |
| `DeleteAssessmentReport` | Delete a report and its object |
| `ValidateAssessmentReportIntegrity` | Check a report's signature |
| `GetInsights` | Evidence counts across active assessments |
| `GetInsightsByAssessment` | Evidence counts for an assessment |
| `ListControlDomainInsights` | Evidence counts by control domain |
| `ListControlDomainInsightsByAssessment` | An assessment's counts by control domain |
| `ListControlInsightsByControlDomain` | Evidence counts per control in a domain |
| `ListAssessmentControlInsightsByControlDomain` | An assessment's counts per control in a domain |
| `ListTagsForResource` | List tags |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags |
<!-- floci:actions:end -->

## Registration

An account starts unregistered in every Region. Until `RegisterAccount`, every operation but `GetAccountStatus` and `RegisterAccount` answers `AccessDeniedException`, as AWS does. (AWS stopped taking new registrations on 2026-04-30; Floci still registers accounts.) `DeregisterAccount` keeps the account's data, or deletes its assessments, custom controls and frameworks, evidence and reports when the settings' deregistration policy is `ALL`. A delegated administrator is registered in AWS Organizations, for the service principal `auditmanager.amazonaws.com`.

`GetSettings` and `UpdateSettings` hold the SNS topic delegation notifications are published to, the default report destination and process owners, the KMS key (`DEFAULT` until set), evidence finder, the deregistration policy and the default export destination.

## Controls and frameworks

Every account has the catalog's Core controls, each in a control domain (data protection, identity and access management, logging and monitoring, network security, resilience, asset management) and mapped to an AWS Config managed rule or to manual evidence. It also has a standard framework, `AWS Audit Manager Sample Framework`, with one control set per domain. `ListControls` filters Core controls by a domain's ARN (`controlCatalogId`).

Custom controls map to data sources (`AWS_Config`, `AWS_Security_Hub`, `AWS_Cloudtrail`, `AWS_API_Call` with a keyword, or `MANUAL`), and custom frameworks group controls into control sets. Names are unique in the account's library. Deleting a custom control takes it out of the frameworks that use it. Core controls and the standard framework can't be changed.

`StartAssessmentFrameworkShare` snapshots a custom framework for another account and Region. The recipient sees the request as `RECEIVED`. Accepting it copies the framework and its custom controls into the recipient's library. The request can also be declined or revoked, and it expires after 120 days.

## Assessments

`CreateAssessment` copies the framework's control sets and controls into the assessment. Control set IDs are the control set names. The process owner roles must exist in IAM, the report destination bucket must exist in S3, and the scope's accounts must be the caller's account or accounts of its organization.

The assessment then supports these updates:

- Delegate control sets to resource owners. Delegations notify through `ListNotifications` and the settings' SNS topic.
- Review controls, with statuses and comments, and control sets. Marking a control set `REVIEWED` completes its delegations.
- Make the assessment inactive. That stops changes and evidence collection for good.

Every change is recorded in `GetChangeLogs`.

Evidence is filed in daily folders per control and data source:

- **Manual evidence** is imported with `BatchImportEvidenceToAssessmentControl`. An item is a text response, an S3 object of the account, or a file PUT to the URL `GetEvidenceFileUploadUrl` returns.
- **AWS Config evidence** is collected from Floci's AWS Config. Each evaluation result of a rule whose managed rule identifier is a control's keyword, or of the custom rule named by a `Custom_<name>` keyword, becomes a compliance check. Collection runs when the assessment is created and again, at most every 30 seconds, when its evidence or insights are read.

CloudTrail, Security Hub and API call sources collect no evidence.

Insights count compliance checks that passed (`COMPLIANT`), failed (`NON_COMPLIANT`), or were inconclusive (manual evidence, and any other result). Counts are given across active assessments, per assessment, per control domain (custom controls are `UNCATEGORIZED`), and per control.

## Assessment reports

`CreateAssessmentReport` writes `AssessmentReport_<name>_<id>.zip` to the assessment's report destination. The zip holds `AssessmentReport.json`, which summarizes the assessment with its control sets, controls, reviews and comments. It also holds the evidence selected for the report with the associate operations. A `queryStatement` (an evidence finder search) needs evidence finder enabled, and selects all of the assessment's evidence.

The zip is signed `SHA256withRSA` with the account's own key, and the signature is stored in the object's metadata. `ValidateAssessmentReportIntegrity` checks an object against it, so a changed or re-uploaded report fails validation. `GetAssessmentReportUrl` returns a presigned S3 URL. `DeleteAssessmentReport` deletes the report record and its object.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_AUDIT_MANAGER_ENABLED` | `true` | Enable or disable Audit Manager |

For the upstream API shape, see the [AWS Audit Manager API Reference](https://docs.aws.amazon.com/audit-manager/latest/APIReference/Welcome.html).
