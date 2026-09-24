# Amazon Inspector

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci implements the Amazon Inspector organization operations used by local security-governance workflows.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateFilter` | - |
| `UpdateFilter` | - |
| `DeleteFilter` | - |
| `ListFilters` | - |
| `GetEc2DeepInspectionConfiguration` | - |
| `ListCisScanConfigurations` | - |
| `ListFindings` | - |
| `ListCoverage` | - |
| `SearchVulnerabilities` | - |
| `ListUsageTotals` | - |
| `ListAccountPermissions` | - |
| `BatchGetFreeTrialInfo` | - |
| `GetConfiguration` | - |
| `GetEncryptionKey` | - |
| `ListCisScans` | - |
| `ListMembers` | - |
| `GetDelegatedAdminAccount` | - |
| `GetFindingsReportStatus` | - |
| `ListDelegatedAdminAccounts` | - |
| `EnableDelegatedAdminAccount` | - |
| `DisableDelegatedAdminAccount` | - |
| `BatchGetAccountStatus` | - |
| `Enable` | - |
| `UpdateOrganizationConfiguration` | - |
| `DescribeOrganizationConfiguration` | - |
<!-- floci:actions:end -->

## Supported behavior

The supported surface includes delegated administrator management, `BatchGetAccountStatus`, Inspector enablement, and organization auto-enable configuration. Delegation is reflected into the delegated account so organization configuration can be managed through credentials for that administrator account.

`BatchGetAccountStatus` accepts an omitted or empty `accountIds` list and resolves it to the caller account. Explicit enablement supports `EC2`, `ECR`, `LAMBDA`, `LAMBDA_CODE`, and `CODE_REPOSITORY`, with the documented request-size limits. Explicit enablement uses the account-state values `ENABLING` and `ENABLED` so SDK and deployment waiters can converge.

Organization auto-enable supports `ec2`, `ecr`, `lambda`, `lambdaCode`, and `codeRepository`. Only the delegated administrator account can update or describe organization configuration.

Floci does not run a vulnerability scanner, so `ListFindings`, `ListCoverage`, `ListUsageTotals`, and `ListCisScans` return empty pages (`ListCisScans` still requires the account to be enabled). `SearchVulnerabilities` answers from a small built-in catalog (currently `CVE-2021-44228`) and returns an empty list for any other CVE. `BatchGetFreeTrialInfo` reports a 15-day free trial per scan type starting when that type was first activated. `ListAccountPermissions` returns the full EC2, ECR, and Lambda permission set, or none for an organization member managed by a delegated administrator. `ListMembers` lists organization accounts for the delegated administrator, treating accounts with Inspector activated as associated. `GetDelegatedAdminAccount` reports the organization's delegated administrator. `GetConfiguration` returns no ECR or EC2 configuration, and `GetEncryptionKey` and `GetFindingsReportStatus` return `ResourceNotFoundException` because customer managed keys and findings reports cannot be created in Floci.

## AWS-compatible failures

Invalid account IDs, resource types, pagination input, and request shapes return `ValidationException`. Conflicting delegated-administrator state returns `ConflictException`. Organization configuration from a non-administrator account returns `AccessDeniedException`, and disabling an administrator that is not configured returns `ResourceNotFoundException`.

AWS also models internal and throttling failures. Floci does not synthesize provider-side failures without a local triggering condition.

See the [Amazon Inspector API Reference](https://docs.aws.amazon.com/inspector/v2/APIReference/Welcome.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_INSPECTOR2_ENABLED` | `true` | Enable or disable Amazon Inspector |
