# EMR Serverless

EMR Serverless is an AWS service that provides serverless data analytics environments. Floci provides an emulator for the management-plane API (REST JSON) which covers the lifecycle of an Application and of the job runs submitted to it.

## Configuration

| Key | Description | Default |
|-----|-------------|---------|
| `floci.emrserverless.enabled` | Whether the EMR Serverless API is enabled | `true` |
| `floci.emrserverless.port` | The port the service is exposed on | `4566` |
| `floci.services.emrserverless.job-run-duration-seconds` | How long a `BATCH` job run stays `RUNNING` before it succeeds; `0` makes it `SUCCESS` as soon as it starts | `0` |

## Endpoints

The emulator implements the standard AWS `emr-serverless` service endpoints:

* `POST /applications` (CreateApplication)
* `GET /applications` (ListApplications)
* `GET /applications/{applicationId}` (GetApplication)
* `PATCH /applications/{applicationId}` (UpdateApplication)
* `DELETE /applications/{applicationId}` (DeleteApplication)
* `POST /applications/{applicationId}/start` (StartApplication)
* `POST /applications/{applicationId}/stop` (StopApplication)
* `POST /applications/{applicationId}/jobruns` (StartJobRun)
* `GET /applications/{applicationId}/jobruns` (ListJobRuns)
* `GET /applications/{applicationId}/jobruns/{jobRunId}` (GetJobRun)
* `DELETE /applications/{applicationId}/jobruns/{jobRunId}` (CancelJobRun)
* `GET /applications/{applicationId}/jobruns/{jobRunId}/attempts` (ListJobRunAttempts)
* `POST /tags/{resourceArn}` (TagResource)
* `DELETE /tags/{resourceArn}?tagKeys=...` (UntagResource)
* `GET /tags/{resourceArn}` (ListTagsForResource)

The tagging operations work on application and job run ARNs, as `CreateApplication` and `StartJobRun`
return them (`arn:aws:emr-serverless:<region>:<account>:/applications/<id>` and
`.../applications/<id>/jobruns/<id>`). They share the tags those calls store, so `GetApplication` and
`GetJobRun` always report the current set. Following the EMR Serverless user guide, a resource holds
at most 50 tags, and `aws:` in any case is reserved as the prefix of a tag key or value. A session ARN
(`.../sessions/<id>`) answers `ResourceNotFoundException`, as sessions are not emulated. Once an
application is deleted, its ARN and its runs' ARNs answer `ResourceNotFoundException` too, while
`GetApplication` and `GetJobRun` still report them.

Applications, job runs, creation client tokens, and tags are scoped to the request's AWS Region. An
application or run created in one Region is not returned by list, get, or tag requests signed for
another.

`ListApplications` refuses a `states` filter naming a state that does not exist, or more than 7, and a
`maxResults` outside 1 to 50, with the `ValidationException` AWS's validation layer answers.

`CreateApplication` stores every configuration `UpdateApplication` does, `runtimeConfiguration`,
`monitoringConfiguration`, `schedulerConfiguration`, `diskEncryptionConfiguration`,
`interactiveConfiguration`, `identityCenterConfiguration` and `jobLevelCostAllocationConfiguration`
among them, and `GetApplication` returns each as it was written.

`DeleteApplication` leaves the application in `TERMINATED` state. `GetApplication` continues to
report that state so lifecycle waiters can observe it, while an unfiltered `ListApplications`
omits the terminated application. A terminated application cannot be started or stopped again.

## Job runs

Job runs are metadata only: nothing executes. A run follows the job run states and `GetJobRun`'s
response shape, and settles when it is read.

* **Lifecycle**: a run is `RUNNING` from the moment it is submitted. With the default
  `job-run-duration-seconds` of `0`, a `BATCH` run is `SUCCESS` as soon as it starts; a positive
  duration keeps it `RUNNING` that long. A run whose duration would pass its
  `executionTimeoutMinutes` (720 when not given, as AWS reports it) is `CANCELLED` at the timeout.
  A `STREAMING` run stays `RUNNING` until it is cancelled.
* **Auto-start**: a run submitted to a `CREATED` or `STOPPED` application starts the application,
  as `autoStartConfiguration.enabled` defaults to `true`. With auto-start turned off, the run is
  refused with `ValidationException` until the application is started. A run on a terminated
  application is refused.
* **Idempotency**: `clientToken` is the idempotency token. Repeating a request with the same token
  returns the original run; reusing the token with a different request answers `ConflictException`
  (HTTP 409) "Request did not match the original".
* **Execution timeout**: `executionTimeoutMinutes` is 0 to 1,000,000, 0 meaning no timeout. From
  release `emr-7.11.0`, a `BATCH` run's timeout must be 1 to 10080 minutes (7 days), as the model and
  the 7.11.0 release notes say; a run outside that is refused with `ValidationException`.
* **CancelJobRun** moves a run that has not finished to `CANCELLED`. A finished run answers
  `ValidationException` "Job run is not in a cancellable state".
* **ListJobRuns** returns the newest runs first, filtered by `states`, `createdAtAfter`,
  `createdAtBefore` and `mode`, at most 50 per page. With several `states`, the runs are grouped by
  state in the order the filter names them. A state or mode that does not exist, or more than 8
  states, is refused with `ValidationException`. An unknown application has no runs. A `nextToken`
  resumes only the listing it came from, with the same application and filters.
* **Attempts**: nothing runs, so no run fails and none is retried: every run is a single attempt,
  number 1. `GetJobRun`, `ListJobRuns` and `ListJobRunAttempts` report it, with the run's own
  creation and update times. `GetJobRun` with `attempt` 1, or without one, returns the run; an
  `attempt` of 2 or more answers `ResourceNotFoundException`, and one below 1 or not an integer
  `ValidationException`.
* `createdBy` is the caller's ARN, as `GetCallerIdentity` reports it. `releaseLabel` and
  `networkConfiguration` come from the application.

A state machine drives applications and job runs through the `emr-serverless:` integrations, `.sync`
included; see [Step Functions: Amazon EMR Serverless tasks](step-functions.md#amazon-emr-serverless-tasks).

* `POST /applications/{applicationId}/jobruns` (StartJobRun)
* `GET /applications/{applicationId}/jobruns` (ListJobRuns)
* `GET /applications/{applicationId}/jobruns/{jobRunId}` (GetJobRun)
* `DELETE /applications/{applicationId}/jobruns/{jobRunId}` (CancelJobRun)
* `GET /applications/{applicationId}/jobruns/{jobRunId}/attempts` (ListJobRunAttempts)
* `GET /applications/{applicationId}/sessions` (ListSessions, empty until interactive execution is supported)
* `GET`, `POST`, `DELETE /tags/{resourceArn}` (application tagging)

## Spark execution

Docker must be available. The execution role must exist in the application's account and trust `emr-serverless.amazonaws.com`. IAM-authorized job submission also requires `iam:PassRole` for that role. Workers receive expiring role credentials which are revoked after execution.

An image-local entry point such as `local:///usr/lib/spark/examples/src/main/python/pi.py` is translated to the configured Spark home. Output is captured in `/aws/emr-serverless`, under the stream `{applicationId}/{jobRunId}/SPARK_DRIVER`. Cancellation removes the worker before reporting `CANCELLED`. Interrupted workers are cleaned up rather than replayed after restart.

## Limitations and Differences from AWS

* **Jobs do not execute**: `StartJobRun` records a job run and moves it through its states, but no Spark or Hive job runs.
* **Unsourced errors**: AWS documents neither the error for a run submitted to an application whose auto-start is off nor the one for a run on a terminated application; Floci answers both with `ValidationException`. The same holds for a `BATCH` timeout outside 1 to 10080 minutes on `emr-7.11.0` or later (`ValidationException`) and for `GetJobRun` naming an attempt a run never made (`ResourceNotFoundException`), both in Floci's own wording. A run needs a `jobDriver`, as every `JobRun` carries one, and `jobDriver` is a union, so a run without a driver, or with one that sets both `sparkSubmit` and `hive` or neither, is refused with `ValidationException` too, in Floci's own wording.
* **StopApplication with running jobs**: AWS requires running jobs to finish or be cancelled before an application stops. Floci does not enforce it; with the default duration of `0` no `BATCH` run is ever running.
* **Not emulated**: retries (`retryPolicy` is stored and returned, but no run fails, so none is retried), queuing (`QUEUED`), `totalResourceUtilization` and `billedResourceUtilization`, and `shutdownGracePeriodInSeconds` (accepted and ignored).
* **Instant Start/Stop**: Floci marks the application `STARTED` or `STOPPED` immediately without provisioning actual compute capacity in the background.
* **Data Plane**: Floci does not implement the data-plane API or any execution environments.
