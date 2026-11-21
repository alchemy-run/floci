# Cost Explorer (`ce:*`)

**Protocol:** JSON 1.1
**Header:** `X-Amz-Target: AWSInsightsIndexService.<Action>`
**Endpoint prefix:** `ce`

Floci synthesizes Cost Explorer responses from its own resource state,
multiplied by the bundled AWS Pricing snapshot served by the
[Pricing service](pricing.md). Costs reflect what's running in Floci right now,
so any test that mutates resources (e.g. creates a bucket, runs an instance)
sees those changes in the next `GetCostAndUsage` call.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `GetCostAndUsage` | Full `TimePeriod` / `Granularity` / `Filter` / `GroupBy` / `Metrics` support |
| `GetCostAndUsageWithResources` | Same shape as `GetCostAndUsage`; for resource-level breakdown, group by `Type=DIMENSION,Key=RESOURCE_ID` |
| `GetDimensionValues` | Returns dimension values present in the synthesized data set |
| `GetTags` | Returns tag keys / values across enumerated resources |
| `GetReservationCoverage` | Stub; returns zeroed totals; full RI math lands in a follow-up PR |
| `GetReservationUtilization` | Stub; returns zeroed totals |
| `GetSavingsPlansCoverage` | Stub; returns empty list |
| `GetSavingsPlansUtilization` | Stub; returns zeroed totals |
| `GetSavingsPlansUtilizationDetails` | Validates the period; returns an empty per-plan list (Floci has no Savings Plans) |
| `GetCostCategories` | Lists the categories in effect in a period, or the values one assigns to the period's usage |
| `CreateCostCategoryDefinition` | Create a category with ordered `REGULAR` / `INHERITED_VALUE` rules, a default value, split charge rules and tags |
| `DescribeCostCategoryDefinition` | Read a category, optionally as of `EffectiveOn` |
| `UpdateCostCategoryDefinition` | Replace a category's rules, default value and split charge rules |
| `DeleteCostCategoryDefinition` | Delete a category and return its `EffectiveEnd` |
| `ListCostCategoryDefinitions` | List categories in effect on `EffectiveOn` with their rule counts and values |
| `ListCostCategoryResourceAssociations` | Validates the category; returns an empty association list |
| `GetCostForecast` | Projects the trailing day's cost run rate over `DAILY` or `MONTHLY` periods with a prediction interval |
| `GetUsageForecast` | Projects usage for a `USAGE_TYPE` / `USAGE_TYPE_GROUP` filter in its single unit |
| `GetApproximateUsageRecords` | Counts usage records per service over the last 30 days for a granularity and dimension |
| `GetCostAndUsageComparisons` | Compares two calendar months in total and per group |
| `GetCostComparisonDrivers` | Compares two calendar months per group, broken down by usage type |
| `GetReservationPurchaseRecommendation` | Recommends EC2 Reserved Instances sized to steadily running instances |
| `GetSavingsPlansPurchaseRecommendation` | Recommends Compute or EC2 Instance Savings Plans sized to steady on-demand spend |
| `GetSavingsPlanPurchaseRecommendationDetails` | Returns a recommendation's detail with per-day lookback metrics |
| `GetRightsizingRecommendation` | Recommends terminating or downsizing EC2 instances from their CloudWatch CPU utilization |
| `StartSavingsPlansPurchaseRecommendationGeneration` | Records a recommendation refresh that completes immediately (3 per day) |
| `ListSavingsPlansPurchaseRecommendationGeneration` | Lists recommendation refreshes with status and ID filters |
| `StartCommitmentPurchaseAnalysis` | Runs a `MAX_SAVINGS` or `CUSTOM_COMMITMENT` Savings Plans analysis over the lookback usage |
| `GetCommitmentPurchaseAnalysis` | Returns an analysis with its estimated coverage, utilization and savings |
| `ListCommitmentPurchaseAnalyses` | Lists analyses with status and ID filters |
| `ListCostAllocationTags` | Lists tag keys seen on usage in the last 12 months with their activation status |
| `UpdateCostAllocationTagsStatus` | Activates or deactivates tag keys, reporting unknown keys per entry |
| `StartCostAllocationTagBackfill` | Records a backfill from a month in the last 12 months (one per 24 hours) |
| `ListCostAllocationTagBackfillHistory` | Lists backfill requests, newest first |
| `GetAnomalies` | Validates the monitor and date interval; returns no anomalies (none are generated) |
| `ProvideAnomalyFeedback` | Rejects feedback with `ValidationException`, as no anomalies exist |
| `CreateAnomalyMonitor` | Store a dimensional or custom monitor with optional resource tags |
| `GetAnomalyMonitors` | List account-scoped monitors with ARN filters and pagination |
| `UpdateAnomalyMonitor` | Update a monitor's name |
| `DeleteAnomalyMonitor` | Delete a monitor without deleting subscriptions |
| `CreateAnomalySubscription` | Store subscriber, frequency, monitor, and threshold configuration |
| `GetAnomalySubscriptions` | List subscriptions with ARN or monitor filters and pagination |
| `UpdateAnomalySubscription` | Update supplied settings while preserving omitted fields |
| `DeleteAnomalySubscription` | Delete a subscription |
| `ListTagsForResource` | Read monitor or subscription tags |
| `TagResource` | Add or replace monitor or subscription tags |
| `UntagResource` | Remove monitor or subscription tag keys |
<!-- floci:actions:end -->

## Cost anomaly configuration

Monitors and subscriptions support creation, listing, updates, deletion, and resource tags.
They are global within the calling account: requests signed for different regions see the same
resources, while another account cannot read or modify them. Definitions and tags use Floci's
configured storage and survive restarts in persistent storage modes.

`DIMENSIONAL` monitors support `SERVICE`, `LINKED_ACCOUNT`, `TAG`, and `COST_CATEGORY`.
Tag and cost-category monitors provide a key in `MonitorSpecification`; `CUSTOM` monitors
provide linked-account, tag, or cost-category values. `MonitorSpecification` is a JSON object,
including when the caller's configuration tool represents it internally as a JSON string.

Subscriptions support `DAILY` or `WEEKLY` email configuration and `IMMEDIATE` SNS configuration.
Updates preserve omitted fields and reject invalid changes before writing. The deprecated
`Threshold` input is accepted as an absolute-cost shorthand; reads return its normalized
`ThresholdExpression`. Monitor names are labels, not idempotency keys.

`GetAnomalyMonitors` and `GetAnomalySubscriptions` accept their ARN-list filters, `MaxResults`,
and `NextPageToken`; subscriptions also support a `MonitorArn` filter. Tags are managed through
`ResourceArn`, `ResourceTags`, and `ResourceTagKeys`, and are not embedded in the resource
definitions returned by the list APIs.

Deleting a monitor leaves subscriptions intact and editable. In this emulator, their saved
monitor ARN lists remain unchanged until explicitly updated. Monitor evaluation, anomaly
generation, email confirmation, and email/SNS notification delivery are not emulated. Floci
does not synthesize subscriber confirmation status or a monitor's last evaluation date.
Organizations management-account eligibility is not enforced for the additional dimensional
monitor types.

For example, create a monitor and a daily subscription against the local endpoint:

```bash
MONITOR_ARN=$(aws ce create-anomaly-monitor --endpoint-url http://localhost:4566 \
  --anomaly-monitor '{"MonitorName":"services","MonitorType":"DIMENSIONAL","MonitorDimension":"SERVICE"}' \
  --resource-tags Key=Environment,Value=development --query MonitorArn --output text)

aws ce create-anomaly-subscription --endpoint-url http://localhost:4566 \
  --anomaly-subscription "{\"SubscriptionName\":\"daily-alerts\",\"Frequency\":\"DAILY\",\"MonitorArnList\":[\"$MONITOR_ARN\"],\"Subscribers\":[{\"Address\":\"alerts@example.com\",\"Type\":\"EMAIL\"}],\"ThresholdExpression\":{\"Dimensions\":{\"Key\":\"ANOMALY_TOTAL_IMPACT_ABSOLUTE\",\"Values\":[\"100\"],\"MatchOptions\":[\"GREATER_THAN_OR_EQUAL\"]}}}"
```

## Cost categories

Cost category rules are evaluated in order against every usage line. A `REGULAR` rule assigns its `Value` when
its `Rule` expression matches; an `INHERITED_VALUE` rule takes the value of a tag (`TAG` with a `DimensionKey`) or
the linked account (`LINKED_ACCOUNT_NAME`). A line no rule matches gets `DefaultValue`, or no value. The result
backs `CostCategories` filters in every query, `GroupBy` with `Type=COST_CATEGORY` (group keys read
`<category>$<value>`, as tag group keys read `<key>$<value>`), and `GetCostCategories`.

Filters and rules support the `EQUALS`, `ABSENT`, `STARTS_WITH`, `ENDS_WITH` and `CONTAINS` match options with
`CASE_SENSITIVE` or `CASE_INSENSITIVE`. `EffectiveStart` defaults to the start of the current month and may be
the start of any of the previous 12 months. Split charge rules are validated and stored but do not redistribute
cost. Floci keeps no history of a category's earlier rule versions: a category reads the same for any
`EffectiveOn` at or after its `EffectiveStart`, and is gone once deleted.

## Forecasts, comparisons and recommendations

Floci derives usage from current resource state, so cost is a steady run rate. Forecasts project the trailing
day's rate over each requested period and widen the prediction interval with `PredictionIntervalLevel`; when the
filter matches no cost they fail with `DataUnavailableException`, as AWS does without history. Month comparisons
differ only by the number of days in each month while resources stay the same.

Reserved Instance and Savings Plans recommendations size the commitment to the instances that ran for the whole
lookback period (and, for Compute Savings Plans, Lambda spend), priced with typical discounts for the term,
payment option and offering class. Rightsizing reads each instance's `AWS/EC2` `CPUUtilization` from CloudWatch
over 14 days: a peak at or below 1% recommends `TERMINATE`, a peak below 40% recommends `MODIFY` to the next size
down in the family, and an instance without metrics gets no recommendation.

Cost allocation tags are the tag keys seen on usage in the last 12 months; `aws:` keys are `AWSGenerated`, the
rest `UserDefined`, and every key starts `Inactive`. No anomalies are generated, so `GetAnomalies` returns an
empty list and `ProvideAnomalyFeedback` rejects every anomaly ID.

## Cost synthesis model

Each Floci service that wants to participate in cost reporting ships a
{@code @ApplicationScoped} bean implementing `ResourceUsageEnumerator`
(in `core/common/`). Cost Explorer auto-discovers these via CDI — adding a new
service with cost data needs zero changes to `CostExplorerService`.

The bundled enumerators cover:

| Service | Priced unit | Source |
|---------|-------------|--------|
| `AmazonEC2` | `BoxUsage:<instanceType>` × hours | `Ec2Service.describeInstances` |
| `AmazonS3` | `TimedStorage-Standard` × GB-month | `S3Service.listBuckets` + `listObjects` |
| `AWSLambda` | `AWS-Lambda-Requests` (zero quantity, catalog only) | `LambdaService.listFunctions` |
| Other Floci services (DDB, SQS, SNS, …) | catalog only, zero quantity | `UnpricedServicesEnumerator` |

Unpriced services emit zero-quantity catalog rows so they remain visible in
`GetDimensionValues SERVICE` responses without contributing billed cost.

## `RECORD_TYPE` semantics

`GROUP_BY=RECORD_TYPE` distinguishes:

| Record type | When emitted |
|-------------|--------------|
| `Usage` | All synthesized usage rows (always present) |
| `Credit` | When `FLOCI_SERVICES_CE_CREDIT_USD_MONTHLY > 0` (see below) |
| `Tax` / `Refund` / `DiscountedUsage` / `SavingsPlan*` | Reserved for future PRs; not currently emitted |

### Synthetic credit injection

Set `FLOCI_SERVICES_CE_CREDIT_USD_MONTHLY` (default `0.0`) to emit a monthly
`Credit` row that offsets `min(creditUsd, monthly Usage cost)`. Useful for
exercising any code path that computes net cost (gross usage − credits) without
having to build credit fixtures by hand.

```yaml
floci:
  services:
    ce:
      credit-usd-monthly: 100.0
```

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CE_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_CE_CREDIT_USD_MONTHLY` | `0.0` | Synthetic monthly credit, applied as a `Credit` `RECORD_TYPE` row |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

aws ce get-cost-and-usage \
  --time-period Start=2026-01-01,End=2026-02-01 \
  --granularity MONTHLY \
  --metrics UnblendedCost \
  --group-by Type=DIMENSION,Key=SERVICE

aws ce get-dimension-values \
  --time-period Start=2026-01-01,End=2026-02-01 \
  --dimension SERVICE
```

```python
import boto3

ce = boto3.client(
    "ce",
    endpoint_url="http://localhost:4566",
    region_name="us-east-1",
)

resp = ce.get_cost_and_usage(
    TimePeriod={"Start": "2026-01-01", "End": "2026-02-01"},
    Granularity="MONTHLY",
    Metrics=["UnblendedCost"],
    GroupBy=[{"Type": "DIMENSION", "Key": "SERVICE"}],
    Filter={
        "Not": {"Dimensions": {"Key": "SERVICE", "Values": ["AmazonRDS"]}}
    },
)
for result in resp["ResultsByTime"]:
    for group in result["Groups"]:
        print(group["Keys"], group["Metrics"]["UnblendedCost"]["Amount"])
```

## Out of Scope

- Forecasting (`GetCostForecast`, `GetUsageForecast`).
- Right-sizing recommendations (`GetRightsizingRecommendation`).
- Anomaly evaluation and results (`GetAnomalies`), email confirmation, and email/SNS delivery.
- Real Reservation / Savings Plan utilization math — currently zeroed stubs.
- Cost category management (`CreateCostCategoryDefinition` / `*Definition` / `ListCostCategoryDefinitions`).
- Resource-level granularity beyond what `GetCostAndUsageWithResources` exposes today.
