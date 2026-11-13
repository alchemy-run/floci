# X-Ray

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/TraceSegments`, `/TraceSummaries`, ..., signed for `xray`

AWS X-Ray collects traces: segment documents that record a request's path through services.

## Traces

Segments arrive through `PutTraceSegments`, through the X-Ray daemon (UDP, port 2000 by default), or from Lambda
functions with active tracing. Each document is checked:

- a 16-digit hex `id` and a `trace_id` within the 30-day retention window
- a `name`, a `start_time`, and an `end_time` unless `in_progress`
- a `parent_id` for a subsegment sent on its own

Subsegments sent on their own are merged into their parents. `BatchGetTraces` returns the latest version of each
segment.

`GetTraceSummaries` filters with X-Ray's filter expressions:

- `ok`, `error`, `fault` and `throttle`
- `responsetime` and `duration`
- `http.*`, `user` and `annotation.*`
- `service(...)` and `edge(...)`, with `{ }` scopes
- `group.name`
- `AND`, `OR` and `NOT`

Summaries carry the trace's services, users, annotations, entry point and fault and error root causes.
`GetServiceGraph`, `GetTraceGraph` and `GetTimeSeriesServiceStatistics` build the services, their edges and their
request statistics from the segments. Downstream calls that don't send their own segments appear as inferred
services.

## Lambda

A function with `TracingConfig` `Active` is sampled by the sampling rules. Each sampled invocation records an
`AWS::Lambda` segment and an `AWS::Lambda::Function` segment with `Initialization`, `Invocation` and `Overhead`
subsegments. A function error faults the function segment, with the error as its cause. The runtime receives the
trace header and `AWS_XRAY_DAEMON_ADDRESS`, so subsegments the function's SDK sends join the same trace.

## Sampling

The `Default` rule (1 request a second, then 5%) and up to 25 custom rules match by service, type, host, method,
path and attributes, in priority order. `GetSamplingTargets` records the statistics SDKs report and splits each
rule's reservoir among the clients reporting it. `GetSamplingStatisticSummaries` returns the latest window of each
rule.

## Groups and insights

Groups select traces with a filter expression and scope graphs and insights. The `Default` group selects every
trace. With insights enabled, a service whose faults rise well above its baseline opens an insight, which records an
event for each anomalous interval and closes when the faults return to normal. With notifications enabled, every
insight change is sent to EventBridge as `aws.xray` `AWS X-Ray Insight Update`.

## Transaction Search

`UpdateTraceSegmentDestination` to `CloudWatchLogs` needs a CloudWatch Logs resource policy that lets
`xray.amazonaws.com` write. Segments are then also written to the `aws/spans` log group, and `StartTraceRetrieval`,
`ListRetrievedTraces` (X-Ray or OpenTelemetry format) and `GetRetrievedTracesGraph` read them back.

## Configuration

```yaml
floci:
  services:
    xray:
      enabled: true
      transition-millis: 1000          # how long encryption, destination and retrieval changes take
      daemon-port: 2000                # UDP port of the X-Ray daemon (0 disables it)
      insights-interval-millis: 60000  # how often insights are evaluated
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `PutTraceSegments` | Upload segment documents |
| `BatchGetTraces` | Read up to five traces |
| `GetTraceSummaries` | Find traces with a filter expression |
| `GetServiceGraph` | The services and edges of a time window |
| `GetTraceGraph` | The services and edges of given traces |
| `GetTimeSeriesServiceStatistics` | Request statistics of a service over time |
| `PutTelemetryRecords` | Report daemon telemetry |
| `CreateGroup` | Create a group |
| `GetGroup` | Describe a group |
| `GetGroups` | List groups |
| `UpdateGroup` | Change a group's filter or insights |
| `DeleteGroup` | Delete a group |
| `CreateSamplingRule` | Create a sampling rule |
| `GetSamplingRules` | List sampling rules |
| `UpdateSamplingRule` | Change a sampling rule |
| `DeleteSamplingRule` | Delete a sampling rule |
| `GetSamplingTargets` | Report sampling statistics and get reservoir quotas |
| `GetSamplingStatisticSummaries` | Each rule's latest statistics |
| `GetEncryptionConfig` | Describe encryption |
| `PutEncryptionConfig` | Encrypt traces with a KMS key or not |
| `PutResourcePolicy` | Attach a resource policy |
| `ListResourcePolicies` | List resource policies |
| `DeleteResourcePolicy` | Delete a resource policy |
| `GetIndexingRules` | Describe indexing rules |
| `UpdateIndexingRule` | Change the indexing sample |
| `GetTraceSegmentDestination` | Where segments are kept |
| `UpdateTraceSegmentDestination` | Turn Transaction Search on or off |
| `StartTraceRetrieval` | Start retrieving traces from Transaction Search |
| `ListRetrievedTraces` | Read retrieved traces |
| `GetRetrievedTracesGraph` | The graph of retrieved traces |
| `CancelTraceRetrieval` | Cancel a retrieval |
| `GetInsightSummaries` | List insights |
| `GetInsight` | Describe an insight |
| `GetInsightEvents` | An insight's events |
| `GetInsightImpactGraph` | The services an insight affected |
| `TagResource` | Tag a group or sampling rule |
| `UntagResource` | Remove tags |
| `ListTagsForResource` | List a resource's tags |
<!-- floci:actions:end -->
