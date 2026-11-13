# CloudWatch Observability Admin

**Protocol:** REST JSON
**Endpoint:** `POST http://localhost:4566/<Operation>`, signed for `observabilityadmin`

Floci audits the telemetry of the resources it emulates, applies telemetry rules to them, runs CloudWatch pipelines'
processors, and copies log events for organization centralization rules.

## Telemetry evaluation

`StartTelemetryEvaluation` is `STARTING`, then `RUNNING`, in the calling Region (its home Region) and the `Regions` it
names; `StopTelemetryEvaluation` is `STOPPING`, then `STOPPED`. While it runs, `ListResourceTelemetry` reports each
resource's state for logs, metrics and traces:

- **EC2 instances:** metrics are `Enabled` with detailed monitoring.
- **VPCs:** logs are `Enabled` when the VPC has a flow log (source type `VPC_FLOW_LOGS`).
- **Lambda functions:** logs and metrics are `Enabled`; traces are `Enabled` with active tracing.
- **EKS clusters:** logs are `Enabled` when any control plane log type is.

Filters by type, identifier prefix, tags and states apply. Calling it while evaluation isn't running is a
`ValidationException`.

## Telemetry rules

Telemetry rules need evaluation running. A rule's source types must suit its resource type, and its `Scope` and
`SelectionCriteria` are filter strings (`tag:Environment = 'prod' AND ResourceId LIKE 'vpc-%'`). Two rules can't share a
configuration. Every evaluation cycle (`evaluation-interval-seconds`), and right after a rule changes, rules configure
the resources they select:

- **VPC logs:** a flow log into the log group `/aws/vpc`, with the rule's retention, traffic type, log format and
  aggregation interval, tagged `CloudWatchTelemetryRuleManaged=true`.
- **EC2 instance metrics:** detailed monitoring.
- **Lambda function traces:** active tracing.
- **EKS cluster logs:** the control plane log types the rule's source types name (all of them without any).

Deleting a rule leaves what it configured. A rule's `RegionStatuses` list its home Region and the `Regions` it names.

## Organizations

Organization evaluation, organization telemetry rules and centralization rules belong to the organization's management
account, with trusted access enabled for `observabilityadmin.amazonaws.com`; other callers get an
`AccessDeniedException`. Organization rules apply to the member accounts their `Scope` selects (`AccountId`,
`OrganizationId`, `OrganizationUnitId`).

A centralization rule is `Provisioning`, then `Healthy`, or `Unhealthy` with `TRUSTED_ACCESS_NOT_ENABLED` or
`DESTINATION_ACCOUNT_NOT_IN_ORGANIZATION`. While healthy, every log event a member account writes in a source Region to a
log group the `LogGroupSelectionCriteria` select is copied to the destination account and Region, and to its backup
Region, into the log group `LogGroupNamePattern` names (`${source.accountId}`, `${source.region}` and
`${source.logGroup}` expand; the source log group's name without one) and the stream
`<account>_<region>_<source stream>`.

## Telemetry pipelines

A pipeline's configuration is checked as CloudWatch checks it: one source, at most 20 processors, at most one parser and
only first, `parse_xml` only after a parser and at most 5 times, `add_entries`, `copy_values`, `lookup` and `grok` at
most once, `when` only where it's supported and well formed, and `cloudwatch_logs` sinks with `@original` exactly when
the source is `cloudwatch_logs`, an existing log group otherwise. The source's role must trust
`telemetry-pipelines.observabilityadmin.amazonaws.com`. A pipeline is `CREATING` (or `UPDATING`), then `ACTIVE`.

`TestTelemetryPipeline` runs records through the processors: `parse_json`, `csv`, `key_value`, `grok` (common patterns),
`parse_vpc`, `parse_xml`, `add_entries`, `copy_values`, `move_keys`, `rename_keys`, `delete_entries`, `flatten`,
`lowercase_string`, `uppercase_string`, `trim_string`, `substitute_string`, `truncate`, `convert_entry_type`,
`select_entries`, `list_to_map`, `drop_events`, `add_attributes` and `delete_attributes`, with processor and entry
`when` and `when_else` conditions. A processor Floci doesn't run gives that record an error.

A pipeline with an `s3` source reads the objects its SQS queue announces (`ndjson`, `json` or newline codecs) every
`pipeline-poll-seconds`, and writes the processed events to the first sink whose `when` holds, in the stream named after
the pipeline.

## Enrichment and integrations

`StartTelemetryEnrichment` needs evaluation running and creates the Resource Explorer managed view
`AWSManagedViewForCloudWatchTelemetry`, which `ListManagedViews` and `GetManagedView` show; enrichment is `Impaired` when
evaluation stops. An S3 Tables integration creates a table bucket for its telemetry (one integration per Region) and
deletes it with the integration. Integration roles must trust `observabilityadmin.amazonaws.com`. Rules, pipelines and
integrations take tags.

## Configuration

```yaml
floci:
  services:
    observability-admin:
      enabled: true
      evaluation-interval-seconds: 60   # how often running evaluation applies telemetry rules
      pipeline-poll-seconds: 5          # how often S3-source pipelines poll their queue
      transition-millis: 2000
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateCentralizationRuleForOrganization` | Create a centralization rule for the organization |
| `CreateDatasetIntegration` | Create a dataset integration |
| `CreateS3TableIntegration` | Create an S3 Tables integration |
| `CreateTelemetryPipeline` | Create a telemetry pipeline |
| `CreateTelemetryRule` | Create a telemetry rule |
| `CreateTelemetryRuleForOrganization` | Create a telemetry rule for the organization |
| `DeleteCentralizationRuleForOrganization` | Delete a centralization rule for the organization |
| `DeleteDatasetIntegration` | Delete a dataset integration |
| `DeleteS3TableIntegration` | Delete an S3 Tables integration |
| `DeleteTelemetryPipeline` | Delete a telemetry pipeline |
| `DeleteTelemetryRule` | Delete a telemetry rule |
| `DeleteTelemetryRuleForOrganization` | Delete a telemetry rule for the organization |
| `GetCentralizationRuleForOrganization` | Describe a centralization rule for the organization |
| `GetDatasetIntegration` | Describe a dataset integration |
| `GetS3TableIntegration` | Describe an S3 Tables integration |
| `GetTelemetryEnrichmentStatus` | Read enrichment status |
| `GetTelemetryEvaluationStatus` | Read evaluation status |
| `GetTelemetryEvaluationStatusForOrganization` | Read organization evaluation status |
| `GetTelemetryPipeline` | Describe a telemetry pipeline |
| `GetTelemetryRule` | Describe a telemetry rule |
| `GetTelemetryRuleForOrganization` | Describe a telemetry rule for the organization |
| `ListCentralizationRulesForOrganization` | List centralization rules for the organization |
| `ListDatasetIntegrations` | List dataset integrations |
| `ListResourceTelemetry` | Audit resources' telemetry |
| `ListResourceTelemetryForOrganization` | Audit member accounts' resources |
| `ListS3TableIntegrations` | List S3 Tables integrations |
| `ListTagsForResource` | List a resource's tags |
| `ListTelemetryPipelines` | List telemetry pipelines |
| `ListTelemetryRules` | List telemetry rules |
| `ListTelemetryRulesForOrganization` | List telemetry rules for the organization |
| `StartTelemetryEnrichment` | Start telemetry enrichment |
| `StartTelemetryEvaluation` | Start telemetry evaluation |
| `StartTelemetryEvaluationForOrganization` | Start telemetry evaluation for the organization |
| `StopTelemetryEnrichment` | Stop telemetry enrichment |
| `StopTelemetryEvaluation` | Stop telemetry evaluation |
| `StopTelemetryEvaluationForOrganization` | Stop telemetry evaluation for the organization |
| `TagResource` | Tag a resource |
| `TestTelemetryPipeline` | Run records through pipeline processors |
| `UntagResource` | Remove tags from a resource |
| `UpdateCentralizationRuleForOrganization` | Update a centralization rule for the organization |
| `UpdateDatasetIntegration` | Update a dataset integration |
| `UpdateTelemetryPipeline` | Update a telemetry pipeline |
| `UpdateTelemetryRule` | Update a telemetry rule |
| `UpdateTelemetryRuleForOrganization` | Update a telemetry rule for the organization |
| `ValidateTelemetryPipelineConfiguration` | Check a pipeline configuration |
<!-- floci:actions:end -->
