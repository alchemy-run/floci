# Security Lake

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/v1/datalake` and the rest, signed for `securitylake`

Security Lake collects security logs into an S3 data lake that subscribers read.

## Data lakes

Enabling the lake in a Region creates real resources:

- an S3 bucket, `aws-security-data-lake-<region>-…`, with the lake's lifecycle rules
- a Glue database, `amazon_security_lake_glue_db_<region>`
- a Lake Formation registration of the bucket under the metastore manager role, which must exist

The lake is `INITIALIZED`, then `COMPLETED`. Updating it rewrites the bucket's lifecycle. A lake with subscribers
can't be disabled. Disabling it keeps the bucket and the database, as AWS does. An account without a lake in the
Region gets `UnauthorizedException` from the source and subscriber APIs.

## Sources

An AWS log source (`ROUTE53`, `VPC_FLOW`, `CLOUD_TRAIL_MGMT` and the rest) gets a Glue table over
`aws/<SOURCE>/<version>/` in each Region that has a lake. Regions without one come back in `failed`.

A custom source gets its own pieces:

- a Glue table over `ext/<name>/<version>/`
- a Glue crawler that runs as its crawler role
- a provider role its provider identity can assume to write there

A custom source whose provider role is gone is `MISCONFIGURED` and shows up in `ListDataLakeExceptions`.

## Subscribers

A subscriber reads the sources it names, which must be enabled. It gets an IAM role its identity can assume, with the
external ID as a condition, allowed to read the bucket. An SQS notification creates the subscriber's queue, and the
bucket notifies it of every new object. An HTTPS notification takes an endpoint and a target role.

## Configuration

```yaml
floci:
  services:
    securitylake:
      enabled: true
      transition-millis: 2000   # how long a data lake takes to finish setting up
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDataLake` | Enable the lake in Regions: bucket, lifecycle, Glue database, Lake Formation |
| `UpdateDataLake` | Change a lake's lifecycle, encryption or replication |
| `DeleteDataLake` | Disable the lake in Regions, keeping the buckets |
| `ListDataLakes` | List the lakes |
| `CreateAwsLogSource` | Collect AWS log sources, with their Glue tables |
| `DeleteAwsLogSource` | Stop collecting AWS log sources |
| `CreateCustomLogSource` | Add a custom source with its table, crawler and provider role |
| `DeleteCustomLogSource` | Remove a custom source |
| `ListLogSources` | List log sources by Region |
| `GetDataLakeSources` | List sources and whether they're collecting |
| `ListDataLakeExceptions` | List sources that can't collect |
| `CreateDataLakeExceptionSubscription` | Subscribe to exceptions |
| `UpdateDataLakeExceptionSubscription` | Change the exception subscription |
| `GetDataLakeExceptionSubscription` | Read the exception subscription |
| `DeleteDataLakeExceptionSubscription` | Remove the exception subscription |
| `CreateDataLakeOrganizationConfiguration` | Auto-enable sources for new accounts |
| `DeleteDataLakeOrganizationConfiguration` | Stop auto-enabling sources |
| `GetDataLakeOrganizationConfiguration` | Read the organization configuration |
| `RegisterDataLakeDelegatedAdministrator` | Register a delegated administrator |
| `DeregisterDataLakeDelegatedAdministrator` | Remove the delegated administrator |
| `CreateSubscriber` | Add a subscriber with an assumable role |
| `GetSubscriber` | Describe a subscriber |
| `UpdateSubscriber` | Change a subscriber's sources or identity |
| `DeleteSubscriber` | Remove a subscriber, its role and notification |
| `ListSubscribers` | List subscribers |
| `CreateSubscriberNotification` | Notify a subscriber's queue or endpoint of new data |
| `UpdateSubscriberNotification` | Change a subscriber's notification |
| `DeleteSubscriberNotification` | Remove a subscriber's notification |
| `TagResource` | Tag a lake or subscriber |
| `UntagResource` | Remove tags |
| `ListTagsForResource` | List a resource's tags |
<!-- floci:actions:end -->
