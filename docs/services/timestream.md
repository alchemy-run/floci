# Timestream for LiveAnalytics

**Protocol:** JSON 1.0 (`X-Amz-Target: Timestream_20181101.*`)
**Endpoint:** `POST http://localhost:4566/`
**Signing name:** `timestream` (both `timestream-write` and `timestream-query`)

Amazon Timestream for LiveAnalytics is closed to new AWS customers. An account that was not onboarded before
the closure receives `AccessDeniedException` on every operation, including the `DescribeEndpoints` endpoint
discovery call that every write and query client makes first:

```json
{
  "__type": "AccessDeniedException",
  "message": "Only existing Timestream for LiveAnalytics customers can access the service. Reach out to your AWS account team for more information."
}
```

Floci behaves like such a new account: it routes every `timestream-write` and `timestream-query` operation and
answers each one with this error (HTTP 403). No databases, tables, records or scheduled queries are stored. An
operation neither API defines answers `UnknownOperationException`.

For time-series workloads on new accounts use [Timestream for InfluxDB](timestream-influxdb.md), which Floci
emulates with real InfluxDB containers.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `DescribeEndpoints` | `AccessDeniedException` (account not onboarded) |
| `ListTagsForResource` | `AccessDeniedException` (account not onboarded) |
| `TagResource` | `AccessDeniedException` (account not onboarded) |
| `UntagResource` | `AccessDeniedException` (account not onboarded) |
| `CreateBatchLoadTask` | `AccessDeniedException` (account not onboarded) |
| `CreateDatabase` | `AccessDeniedException` (account not onboarded) |
| `CreateTable` | `AccessDeniedException` (account not onboarded) |
| `DeleteDatabase` | `AccessDeniedException` (account not onboarded) |
| `DeleteTable` | `AccessDeniedException` (account not onboarded) |
| `DescribeBatchLoadTask` | `AccessDeniedException` (account not onboarded) |
| `DescribeDatabase` | `AccessDeniedException` (account not onboarded) |
| `DescribeTable` | `AccessDeniedException` (account not onboarded) |
| `ListBatchLoadTasks` | `AccessDeniedException` (account not onboarded) |
| `ListDatabases` | `AccessDeniedException` (account not onboarded) |
| `ListTables` | `AccessDeniedException` (account not onboarded) |
| `ResumeBatchLoadTask` | `AccessDeniedException` (account not onboarded) |
| `UpdateDatabase` | `AccessDeniedException` (account not onboarded) |
| `UpdateTable` | `AccessDeniedException` (account not onboarded) |
| `WriteRecords` | `AccessDeniedException` (account not onboarded) |
| `CancelQuery` | `AccessDeniedException` (account not onboarded) |
| `CreateScheduledQuery` | `AccessDeniedException` (account not onboarded) |
| `DeleteScheduledQuery` | `AccessDeniedException` (account not onboarded) |
| `DescribeAccountSettings` | `AccessDeniedException` (account not onboarded) |
| `DescribeScheduledQuery` | `AccessDeniedException` (account not onboarded) |
| `ExecuteScheduledQuery` | `AccessDeniedException` (account not onboarded) |
| `ListScheduledQueries` | `AccessDeniedException` (account not onboarded) |
| `PrepareQuery` | `AccessDeniedException` (account not onboarded) |
| `Query` | `AccessDeniedException` (account not onboarded) |
| `UpdateAccountSettings` | `AccessDeniedException` (account not onboarded) |
| `UpdateScheduledQuery` | `AccessDeniedException` (account not onboarded) |
<!-- floci:actions:end -->

## Configuration

| Key | Description | Default |
|-----|-------------|---------|
| `floci.services.timestream.enabled` | Whether the Timestream for LiveAnalytics APIs are routed | `true` |

Environment override: `FLOCI_SERVICES_TIMESTREAM_ENABLED`.
