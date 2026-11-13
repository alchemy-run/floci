# AWS Backup Search

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/` with `/search-jobs` and `/export-search-jobs`, signed for `backup-search`; tags on `/tags/{ResourceArn}`.

Floci runs AWS Backup Search over the [backup indexes](backup.md#backup-indexes) of AWS Backup recovery points. A search job scans the indexes in its scope for the S3 objects or EBS files its filters match, and an export job writes a search's results to S3.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `StartSearchJob` | Search indexed recovery points for items matching filters |
| `ListSearchJobs` | Page through search jobs, by status |
| `GetSearchJob` | Describe a search job and its progress |
| `ListSearchJobResults` | Page through a job's matching items |
| `ListSearchJobBackups` | Page through the recovery points a job scanned |
| `StopSearchJob` | Stop a running search job |
| `StartSearchResultExportJob` | Export a search's results to S3 as CSV |
| `ListSearchResultExportJobs` | Page through export jobs |
| `GetSearchResultExportJob` | Describe an export job |
| `ListTagsForResource` | List tags |
| `TagResource` | Tag a job |
| `UntagResource` | Remove tags |
<!-- floci:actions:end -->

## Search jobs

`StartSearchJob` scopes the search to one resource type (`S3` or `EBS`). The scope can be narrowed by source resource ARNs, recovery point ARNs, the recovery points' tags, and backup creation times. Only `ACTIVE` indexes are scanned.

The job is `RUNNING` while it scans and then `COMPLETED`. `StopSearchJob` makes it `STOPPING`, then `STOPPED`, and it keeps the results found so far. Stopping a job that isn't running fails with `ConflictException`.

`SearchScopeSummary` counts the recovery points and items in scope, and `CurrentSearchProgress` counts what was scanned and matched. Results are capped at 1000, as on AWS. At most five search jobs run at once. A `ClientToken` makes a start idempotent.

An item matches when any of its filters matches. Within a filter, every kind of condition must match, and each kind matches when any of its conditions does:

- string conditions take `EQUALS_TO`, `NOT_EQUALS_TO`, `CONTAINS`, `DOES_NOT_CONTAIN`, `BEGINS_WITH`, `ENDS_WITH`, `DOES_NOT_BEGIN_WITH` and `DOES_NOT_END_WITH`
- size and time conditions take `EQUALS_TO`, `NOT_EQUALS_TO`, `LESS_THAN_EQUAL_TO` and `GREATER_THAN_EQUAL_TO`

`ListSearchJobResults` returns each match with its recovery point, source resource and vault. `ListSearchJobBackups` reports the scan status of each recovery point in scope. Search jobs can't be deleted, as on AWS.

## Export jobs

`StartSearchResultExportJob` exports a `COMPLETED` or `STOPPED` search job's results as CSV, to `<DestinationPrefix>/<export job id>/results.csv` in the destination bucket. Any other job fails with AWS's message (`Export is only supported for search job with COMPLETED,STOPPED statuses. SearchJob has status FAILED`). The export job is `RUNNING`, then `COMPLETED`, or `FAILED` when the object can't be written.

## Tags

Search and export job ARNs are AWS Backup ARNs (`arn:aws:backup:<region>:<account>:search-job/<id>`), tagged through the `/tags/{ResourceArn}` routes.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_BACKUP_SEARCH_ENABLED` | `true` | Enable or disable AWS Backup Search |

For the upstream API shape, see the [AWS Backup Search API Reference](https://docs.aws.amazon.com/aws-backup/latest/devguide/API_Operations_AWS_Backup_Search.html).
