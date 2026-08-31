# Cost Explorer (`ce:*`)

**Protocol:** JSON 1.1
**Header:** `X-Amz-Target: AWSInsightsIndexService.<Action>`
**Endpoint prefix:** `ce`

Floci synthesizes Cost Explorer responses from its own resource state,
multiplied by the bundled AWS Pricing snapshot served by the
[Pricing service](pricing.md). Costs reflect what's running in Floci right now,
so any test that mutates resources (e.g. creates a bucket, runs an instance)
sees those changes in the next `GetCostAndUsage` call.

## Supported Operations

| Operation | Notes |
|-----------|-------|
| `GetCostAndUsage` | Full `TimePeriod` / `Granularity` / `Filter` / `GroupBy` / `Metrics` support |
| `GetCostAndUsageWithResources` | Same shape as `GetCostAndUsage`; for resource-level breakdown, group by `Type=DIMENSION,Key=RESOURCE_ID` |
| `GetDimensionValues` | Returns dimension values present in the synthesized data set |
| `GetTags` | Returns tag keys / values across enumerated resources |
| `GetReservationCoverage` | Stub — returns zeroed totals; full RI math lands in a follow-up PR |
| `GetReservationUtilization` | Stub — returns zeroed totals |
| `GetSavingsPlansCoverage` | Stub — returns empty list |
| `GetSavingsPlansUtilization` | Stub — returns zeroed totals |
| `GetCostCategories` | Names/values from stored cost-category definitions |
| `CreateCostCategoryDefinition` | Persists rules, default value, split-charge rules, and resource tags |
| `DescribeCostCategoryDefinition` | Full definition; missing ARN is `ResourceNotFoundException` |
| `ListCostCategoryDefinitions` | Currently-effective definitions for the account |
| `UpdateCostCategoryDefinition` | Mutates rules / default / split-charge in place (name is create-only) |
| `DeleteCostCategoryDefinition` | Removes the definition; missing ARN is `ResourceNotFoundException` |
| `ListCostCategoryResourceAssociations` | Stub — empty list (requires the ARN when supplied) |
| `GetCostForecast` | Forecast buckets synthesized from current usage |
| `GetApproximateUsageRecords` | Per-service usage-line counts over a 14-day lookback |
| `GetRightsizingRecommendation` | Stub — empty recommendations |
| `GetReservationPurchaseRecommendation` | Stub — empty recommendations |
| `ListSavingsPlansPurchaseRecommendationGeneration` | Stub — empty generation list |
| `ListCommitmentPurchaseAnalyses` | Stub — empty analysis list |
| `ListCostAllocationTags` | Stub — empty tag list |
| `ListCostAllocationTagBackfillHistory` | Stub — empty backfill list |
| `GetAnomalies` | Stub — empty anomalies for the requested `DateInterval` |
| `ProvideAnomalyFeedback` | Unknown anomaly ids raise `ValidationException` |
| `CreateAnomalyMonitor` | CUSTOM and DIMENSIONAL monitors; names unique per account |
| `GetAnomalyMonitors` | List or filter by `MonitorArnList` (unknown ARNs return an empty list) |
| `UpdateAnomalyMonitor` | Rename in place; type/spec/dimension are create-only |
| `DeleteAnomalyMonitor` | Idempotent not-found is `UnknownMonitorException` |
| `ListTagsForResource` / `TagResource` / `UntagResource` | Resource tags on cost categories and anomaly monitors |

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

- Usage forecasts (`GetUsageForecast`).
- Real Reservation / Savings Plan utilization math — currently zeroed stubs.
- Resource-level granularity beyond what `GetCostAndUsageWithResources` exposes today.
