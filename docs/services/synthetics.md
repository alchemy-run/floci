# CloudWatch Synthetics

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/canary/...`, signed for `synthetics`

CloudWatch Synthetics runs canaries: scripts that check an endpoint or a workflow on a schedule.

## Canaries and their Lambda resources

A canary's code is published as a Lambda layer named `cwsyn-<name>-<id>`, and a backing function of the same name
runs it on the runtime version's Lambda runtime (`nodejs22.x` for `syn-nodejs-puppeteer-16.1`). The code is a zip
with the script where the runtime looks for it: `nodejs/node_modules/<file>.js` for Node.js, `python/<file>.py` for
Python. Lambda must be able to assume the execution role; when it can't, the create ends in `ERROR` with
`CREATE_FAILED`. Deprecated runtime versions are refused.

Create, update, start, stop and delete are asynchronous, as on AWS: a canary passes through `CREATING`, `UPDATING`,
`STARTING`, `STOPPING` and `DELETING`. An update that Lambda rejects rolls back. Deleting a canary removes its function
and layers when `ProvisionedResourceCleanup` is `AUTOMATIC` (the default) or `DeleteLambda` is set.

## Runs

A started canary runs at once, then on its schedule: `rate(n minutes)` up to `rate(1 hour)`, `cron(...)`, or
`rate(0 minute)` for a single run, after which it stops. `DurationInSeconds` stops it after that long, and
`RetryConfig.MaxRetries` retries a failed run.

The script runs with floci's Synthetics library:

- `executeStep` and `executeHttpStep` (Node.js), `execute_step` (Python)
- the logger, `getConfiguration`, and the canary name, ARN and runtime version
- the `Synthetics`, `SyntheticsLogger` and `SyntheticsLogHelper` modules, plus their `@aws/...` names
- `@aws/synthetics-playwright` and `@amzn/synthetics-playwright`

There is no browser, so the Puppeteer, Playwright and Selenium page APIs fail the run with a clear reason.

Each run writes `SyntheticsReport-PASSED.json` (or `-FAILED`) and `log.txt` to the artifact location as the execution
role. A role that may not write there fails the run. Runs publish `SuccessPercent`, `Duration` and `Failed` to the
`CloudWatchSynthetics` namespace. They also send `Synthetics Canary TestRun Successful` and
`Synthetics Canary TestRun Failure` events, and every state change sends `Synthetics Canary Status Change`, to the
default EventBridge bus as `aws.synthetics`. Runs are kept for the canary's retention periods.

A dry run tries a changed configuration on its own function without changing the canary. An update with its
`DryRunId` applies the configuration once the dry run has passed.

## Groups

Groups are global to the account. A group holds up to ten canaries from any Region, and an account has up to twenty
groups. Deleting a canary takes it out of its groups.

## Configuration

```yaml
floci:
  services:
    synthetics:
      enabled: true
      transition-millis: 1000   # how long a canary's asynchronous state changes take
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateCanary` | Create a canary and its backing layer and function |
| `GetCanary` | Describe a canary, or a dry run's configuration |
| `DescribeCanaries` | List canaries |
| `UpdateCanary` | Change a canary, or apply a passed dry run |
| `DeleteCanary` | Delete a canary and, by default, its Lambda resources |
| `StartCanary` | Start running a canary on its schedule |
| `StopCanary` | Stop a running canary |
| `StartCanaryDryRun` | Run a changed configuration once without applying it |
| `GetCanaryRuns` | List a canary's runs or dry runs |
| `DescribeCanariesLastRun` | Each canary's most recent run |
| `DescribeRuntimeVersions` | List runtime versions and their deprecation dates |
| `CreateGroup` | Create a group |
| `GetGroup` | Describe a group by name, ID or ARN |
| `DeleteGroup` | Delete a group |
| `ListGroups` | List groups |
| `AssociateResource` | Add a canary to a group |
| `DisassociateResource` | Remove a canary from a group |
| `ListGroupResources` | List a group's canaries |
| `ListAssociatedGroups` | List the groups holding a canary |
| `TagResource` | Tag a canary or group |
| `UntagResource` | Remove tags |
| `ListTagsForResource` | List a canary's or group's tags |
<!-- floci:actions:end -->
