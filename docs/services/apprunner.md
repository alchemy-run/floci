# AWS App Runner

**Protocol:** JSON 1.0 (`X-Amz-Target: AppRunner.*`)
**Endpoint:** `POST http://localhost:4566/`
**Signing name:** `apprunner`

App Runner auto scaling configurations are immutable revisions. Creating the same
name again mints a new revision. Deleted revisions linger as `inactive` (the live
wire uses lowercase statuses).

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateAutoScalingConfiguration` | Create a new revision under a configuration name |
| `DescribeAutoScalingConfiguration` | Return a revision by ARN; missing ARNs are `ResourceNotFoundException` (HTTP 400) |
| `ListAutoScalingConfigurations` | List summaries, optionally filtered by name and `LatestOnly` |
| `DeleteAutoScalingConfiguration` | Deactivate a revision, or every revision of a name with `DeleteAllRevisions` |
| `CreateService` | Create a service; immediately `RUNNING` and auto-creates application/service log groups |
| `DescribeService` | Return a service by ARN; missing ARNs are `ResourceNotFoundException` (HTTP 400) |
| `ListServices` | List live service summaries |
| `UpdateService` | Patch source, instance, health, network, observability, or auto scaling |
| `DeleteService` | Delete a service (describe then returns `ResourceNotFoundException`) |
| `PauseService` / `ResumeService` | Toggle `PAUSED` / `RUNNING` |
| `StartDeployment` | Record a `START_DEPLOYMENT` operation and restart the local container |
| `ListOperations` | List operation summaries for a service |
| `DescribeCustomDomains` | Return `DNSTarget` and empty custom-domain lists |
| `ListTagsForResource` | List `{Key,Value}` tags on a configuration or service ARN |
| `TagResource` | Add or overwrite tags |
| `UntagResource` | Remove tags by key |
<!-- floci:actions:end -->

## Identity

ARN:

```
arn:aws:apprunner:<region>:<account>:autoscalingconfiguration/<name>/<revision>/<id>
```

`DeleteAllRevisions` requires the revision-less name partial
(`...:autoscalingconfiguration/<name>`). A full revision ARN with
`DeleteAllRevisions=true` returns `InvalidRequestException`.

The AWS-managed `DefaultConfiguration` revision is always present and cannot be
deleted.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_APPRUNNER_ENABLED` | `true` | Enable or disable App Runner |
| `FLOCI_STORAGE_SERVICES_APPRUNNER_MODE` | *(inherits global)* | Optional App Runner storage-mode override |
| `FLOCI_STORAGE_SERVICES_APPRUNNER_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |
