# IAM Access Analyzer

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the IAM Access Analyzer analyzer and archive-rule management lifecycle used by the AWS SDK, CLI, and Alchemy. Analyzers are isolated by account and region. AWS allows at most one analyzer of each type per account per Region.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateAnalyzer` | `PUT /analyzer` | Create an analyzer |
| `GetAnalyzer` | `GET /analyzer/{analyzerName}` | Return an analyzer |
| `UpdateAnalyzer` | `PUT /analyzer/{analyzerName}` | Update configuration (unused-access age is create-only) |
| `DeleteAnalyzer` | `DELETE /analyzer/{analyzerName}` | Delete an analyzer |
| `ListAnalyzers` | `GET /analyzer` | List analyzer summaries |
| `CreateArchiveRule` | `PUT /analyzer/{analyzerName}/archive-rule` | Create an archive rule |
| `GetArchiveRule` | `GET /analyzer/{analyzerName}/archive-rule/{ruleName}` | Return an archive rule |
| `UpdateArchiveRule` | `PUT /analyzer/{analyzerName}/archive-rule/{ruleName}` | Update an archive-rule filter |
| `DeleteArchiveRule` | `DELETE /analyzer/{analyzerName}/archive-rule/{ruleName}` | Delete an archive rule |
| `ListArchiveRules` | `GET /analyzer/{analyzerName}/archive-rule` | List archive rules |
| `ListTagsForResource` | `GET /tags/{resourceArn}` | List tags on an analyzer |
| `TagResource` | `POST /tags/{resourceArn}` | Add or update tags |
| `UntagResource` | `DELETE /tags/{resourceArn}?tagKeys=` | Remove tags |

Creating a second analyzer of the same type in a Region returns `ServiceQuotaExceededException` (HTTP 402). Reusing an analyzer name returns `ConflictException` (HTTP 409). Changing `unusedAccessAge` in place returns `ValidationException`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ACCESSANALYZER_ENABLED` | `true` | Enable or disable IAM Access Analyzer |
| `FLOCI_STORAGE_SERVICES_ACCESSANALYZER_MODE` | *(inherits global)* | Optional Access Analyzer storage-mode override |
| `FLOCI_STORAGE_SERVICES_ACCESSANALYZER_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws accessanalyzer create-analyzer \
  --analyzer-name local-external-access \
  --type ACCOUNT

aws accessanalyzer get-analyzer --analyzer-name local-external-access
```

## Current Scope

- Analyzer create/get/update/delete/list, archive rules, and tagging.
- Unused-access analyzers record `configuration.unusedAccess.unusedAccessAge` (default 90 days).
- Policy checks (`ValidatePolicy`, `CheckNoNewAccess`, `CheckAccessNotGranted`, `CheckNoPublicAccess`) return AWS-shaped PASS/FAIL results.
- Findings list/statistics and analyzed-resource list return empty pages; `GetFindingV2` returns `ResourceNotFoundException`.
- Policy generation without CloudTrail details (and unknown job ids) returns `ValidationException`.
