# MediaConvert

**Protocol:** REST JSON
**Endpoint prefix:** `mediaconvert`
**Paths:** `/2017-08-29/*`

Floci emulates AWS Elemental MediaConvert so Alchemy can create queues,
templates, presets, and jobs locally and exercise the runtime bindings
(`ListJobs`, `GetJob`, `CancelJob`, `SearchJobs`, `CreateJob`, `Probe`,
`StartJobsQuery`, `GetJobsQueryResults`).

No real transcode is performed. A job that is accepted is stored as
`COMPLETE`. Probe of a missing S3 input returns `NotFoundException`.
`CreateJob` with a role that cannot be assumed returns
`BadRequestException`. Every account has a system `Default` on-demand
queue.

## Supported Operations

| Operation | Notes |
|-----------|-------|
| `ListJobs` / `SearchJobs` | In-memory job history, optional status/queue/input filters |
| `GetJob` / `CancelJob` | Typed `NotFoundException` for a missing id |
| `CreateJob` | Requires an assumable IAM role; no billable transcode |
| `Probe` | `NotFoundException` when the S3 input does not exist |
| `StartJobsQuery` / `GetJobsQueryResults` | Completes immediately |
| `CreateQueue` / `GetQueue` / `ListQueues` / `UpdateQueue` / `DeleteQueue` | Includes the system `Default` queue |
| `CreateJobTemplate` / `GetJobTemplate` / `ListJobTemplates` / `UpdateJobTemplate` / `DeleteJobTemplate` | Named templates |
| `CreatePreset` / `GetPreset` / `ListPresets` / `UpdatePreset` / `DeletePreset` | Named presets |
| `TagResource` / `UntagResource` / `ListTagsForResource` | Tags on jobs, queues, templates, presets |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_MEDIACONVERT_ENABLED` | `true` | Enable or disable the service |
