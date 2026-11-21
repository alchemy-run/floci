# Amazon DevOps Guru

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/` with DevOps Guru's paths (`/insights`, `/anomalies`, `/channels`, `/resource-collections`, `/accounts/health`, `/organization/...`), signed for `devops-guru`.

Floci runs DevOps Guru's configuration and its analysis: the resource collection it covers, integrations, event
sources, notification channels, feedback and cost estimates, and the reactive insights it opens from CloudWatch.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `GetResourceCollection` | Read the stacks or tag values DevOps Guru analyzes |
| `UpdateResourceCollection` | Add or remove stacks or tag values |
| `ListMonitoredResources` | List the monitored resources, by type |
| `DescribeInsight` | Describe an insight |
| `DeleteInsight` | Delete a closed insight |
| `ListInsights` | List ongoing, closed or any insights |
| `SearchInsights` | Search insights by time, severity, status, collection and service |
| `DescribeAnomaly` | Describe an anomaly |
| `ListAnomaliesForInsight` | List an insight's anomalies |
| `ListRecommendations` | List an insight's recommendations |
| `ListEvents` | List the monitored resources' CloudTrail events |
| `ListAnomalousLogGroups` | List the log groups with error lines during an insight |
| `DescribeAccountHealth` | Count open insights, analyzed metrics and resource hours |
| `DescribeAccountOverview` | Count insights and mean time to recover over a period |
| `DescribeResourceCollectionHealth` | Health per stack, service or tag value |
| `DescribeOrganizationHealth` | Count open insights across the named accounts |
| `DescribeOrganizationOverview` | Count insights across the named accounts over a period |
| `DescribeOrganizationResourceCollectionHealth` | Health per account, stack, service or tag value |
| `ListOrganizationInsights` | List the named accounts' insights |
| `SearchOrganizationInsights` | Search the named accounts' insights |
| `DescribeServiceIntegration` | Read the OpsCenter, log anomaly and KMS settings |
| `UpdateServiceIntegration` | Update the OpsCenter, log anomaly and KMS settings |
| `DescribeEventSourcesConfig` | Read the CodeGuru Profiler event source |
| `UpdateEventSourcesConfig` | Enable or disable the CodeGuru Profiler event source |
| `AddNotificationChannel` | Notify an SNS topic of insights, with filters |
| `RemoveNotificationChannel` | Remove a notification channel |
| `ListNotificationChannels` | List notification channels |
| `PutFeedback` | Record feedback on an insight |
| `DescribeFeedback` | Read an insight's feedback |
| `StartCostEstimation` | Estimate the monthly cost of analyzing a collection |
| `GetCostEstimation` | Read the cost estimate by resource type |
<!-- floci:actions:end -->

## Analysis

The resource collection names CloudFormation stacks (`*` for all) or one `devops-guru-*` tag key and its values;
the resources in those stacks or carrying those tags are the monitored resources. floci's analysis reads
CloudWatch: an alarm in `ALARM` on a metric whose dimension names a monitored resource (`FunctionName`,
`QueueName`, `TableName`, ...) is an ongoing reactive anomaly. Anomalies group into one reactive insight per stack
or tag value; an anomaly closes when its alarm leaves `ALARM`, and an insight when all of its anomalies have.
Severity follows the metric: errors and faults are `HIGH`, latency, throttling and age `MEDIUM`, others `LOW`.

Each new insight, new anomaly, severity upgrade, recommendation and closure notifies the channels whose filters
match, through their SNS topics, and publishes an `aws.devops-guru` event (`DevOps Guru New Insight Open`,
`DevOps Guru Insight Closed`, ...). Recommendations follow the anomalous metric's service. `ListEvents` reads the
monitored resources' CloudTrail events. With log anomaly detection enabled, `ListAnomalousLogGroups` reports the
error lines of the insight's Lambda functions' log groups. floci makes no proactive insights, and the OpsCenter
integration creates no OpsItems.

Only closed insights can be deleted. An account has at most two notification channels, one per topic; a
channel's configuration does not change. Cost estimates price each monitored resource at $0.0028 per hour over a
month. Organization views aggregate the accounts named.

## Configuration

```yaml
floci:
  services:
    devopsguru:
      enabled: true
      transition-millis: 2000   # how long a cost estimation stays ONGOING
```
