# EMR Serverless

Floci supports the EMR Serverless application and job-run REST JSON APIs. Spark batch jobs execute with `spark-submit` in a Docker worker, using entry points already present in the worker image. Job success requires an observed zero exit code and confirmed worker removal.

## Configuration

| Key | Description | Default |
|-----|-------------|---------|
| `floci.services.emrserverless.enabled` | Whether the EMR Serverless API is enabled | `true` |
| `floci.services.emrserverless.spark-image` | Versioned Docker image containing Spark and job entry points | `apache/spark:3.5.2-scala2.12-java17-python3-ubuntu` |
| `floci.services.emrserverless.spark-home` | Spark installation directory inside the image | `/opt/spark` |
| `floci.services.emrserverless.job-timeout-seconds` | Worker execution limit, from 1 to 900 seconds | `300` |

Environment overrides are `FLOCI_SERVICES_EMRSERVERLESS_SPARK_IMAGE`, `FLOCI_SERVICES_EMRSERVERLESS_SPARK_HOME`, and `FLOCI_SERVICES_EMRSERVERLESS_JOB_TIMEOUT_SECONDS`. Requests use the shared Floci gateway port.

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
* `GET /applications/{applicationId}/sessions` (ListSessions, empty until interactive execution is supported)
* `GET`, `POST`, `DELETE /tags/{resourceArn}` (application tagging)

## Spark execution

Docker must be available. The execution role must exist in the application's account and trust `emr-serverless.amazonaws.com`. IAM-authorized job submission also requires `iam:PassRole` for that role. Workers receive expiring role credentials which are revoked after execution.

An image-local entry point such as `local:///usr/lib/spark/examples/src/main/python/pi.py` is translated to the configured Spark home. Output is captured in `/aws/emr-serverless`, under the stream `{applicationId}/{jobRunId}/SPARK_DRIVER`. Cancellation removes the worker before reporting `CANCELLED`. Interrupted workers are cleaned up rather than replayed after restart.

## Limitations and Differences from AWS

* **Bounded local execution**: One worker runs at a time with one CPU and 1536 MiB of container memory. The pending queue is limited to 32 jobs and a five-minute wait. Requested cloud capacity does not expand these local limits.
* **Image-local Spark only**: S3 entry points, Hive, streaming, VPC-attached workers, per-worker specifications, configuration overrides, execution-policy overrides, and automatic retries are unsupported.
* **Instant application Start/Stop**: Application state changes immediately; compute is allocated only when a job runs. Active jobs must terminate before the application can be stopped or deleted.
* **Interactive execution**: Sessions and dashboards remain unsupported and return explicit errors rather than synthetic activity or entitlement denials.
