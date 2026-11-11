# AWS Deadline Cloud

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/` with Deadline Cloud's paths (`/2023-10-12/farms`, `/2023-10-12/monitors`, `/2023-10-12/license-endpoints`, `/2023-10-12/tags/{resourceArn}`, ...), signed for `deadline`.

Floci runs Deadline Cloud farms end to end: queues, fleets, storage profiles, budgets, limits and their
associations, jobs submitted from Open Job Description templates with their steps and tasks, and the scheduling
protocol customer-managed fleet workers speak, so the Deadline worker agent can run jobs against floci.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateFarm` | Create a farm |
| `GetFarm` | Describe a farm |
| `UpdateFarm` | Update a farm |
| `DeleteFarm` | Delete a farm that holds no queues, fleets, profiles, budgets, limits or associations |
| `ListFarms` | List farms |
| `CreateQueue` | Create a queue and its log group |
| `GetQueue` | Describe a queue |
| `UpdateQueue` | Update a queue |
| `DeleteQueue` | Delete a queue |
| `ListQueues` | List a farm's queues |
| `CreateFleet` | Create a customer-managed or service-managed fleet and its log group |
| `GetFleet` | Describe a fleet |
| `UpdateFleet` | Update a fleet |
| `DeleteFleet` | Delete a fleet |
| `ListFleets` | List a farm's fleets |
| `CreateStorageProfile` | Create a storage profile |
| `GetStorageProfile` | Describe a storage profile |
| `GetStorageProfileForQueue` | Describe a storage profile a queue allows |
| `UpdateStorageProfile` | Update a storage profile |
| `DeleteStorageProfile` | Delete a storage profile |
| `ListStorageProfiles` | List a farm's storage profiles |
| `ListStorageProfilesForQueue` | List the storage profiles a queue allows |
| `CreateBudget` | Create a budget |
| `GetBudget` | Describe a budget |
| `UpdateBudget` | Update a budget |
| `DeleteBudget` | Delete a budget |
| `ListBudgets` | List a farm's budgets |
| `CreateLimit` | Create a limit |
| `GetLimit` | Describe a limit |
| `UpdateLimit` | Update a limit |
| `DeleteLimit` | Delete a limit (idempotent) |
| `ListLimits` | List a farm's limits |
| `CreateQueueFleetAssociation` | Create a queue-fleet association |
| `GetQueueFleetAssociation` | Describe a queue-fleet association |
| `UpdateQueueFleetAssociation` | Activate or stop scheduling a queue on a fleet |
| `DeleteQueueFleetAssociation` | Delete a stopped queue-fleet association |
| `ListQueueFleetAssociations` | List queue-fleet associations |
| `CreateQueueLimitAssociation` | Create a queue-limit association |
| `GetQueueLimitAssociation` | Describe a queue-limit association |
| `UpdateQueueLimitAssociation` | Activate or stop a queue's use of a limit |
| `DeleteQueueLimitAssociation` | Delete a stopped queue-limit association |
| `ListQueueLimitAssociations` | List queue-limit associations |
| `CreateQueueEnvironment` | Create a queue environment |
| `GetQueueEnvironment` | Describe a queue environment |
| `UpdateQueueEnvironment` | Update a queue environment |
| `DeleteQueueEnvironment` | Delete a queue environment |
| `ListQueueEnvironments` | List a queue's environments |
| `AssociateMemberToFarm` | Add a member to a farm |
| `DisassociateMemberFromFarm` | Remove a member from a farm |
| `ListFarmMembers` | List a farm's members |
| `AssociateMemberToQueue` | Add a member to a queue |
| `DisassociateMemberFromQueue` | Remove a member from a queue |
| `ListQueueMembers` | List a queue's members |
| `AssociateMemberToFleet` | Add a member to a fleet |
| `DisassociateMemberFromFleet` | Remove a member from a fleet |
| `ListFleetMembers` | List a fleet's members |
| `AssociateMemberToJob` | Add a member to a job |
| `DisassociateMemberFromJob` | Remove a member from a job |
| `ListJobMembers` | List a job's members |
| `ListMemberships` | List a principal's memberships |
| `CreateMonitor` | Create a monitor and its IAM Identity Center application |
| `GetMonitor` | Describe a monitor |
| `UpdateMonitor` | Update a monitor |
| `DeleteMonitor` | Delete a monitor and its IAM Identity Center application |
| `ListMonitors` | List monitors |
| `GetMonitorSettings` | Read a monitor's settings |
| `UpdateMonitorSettings` | Update a monitor's settings |
| `CreateLicenseEndpoint` | Create a license endpoint |
| `GetLicenseEndpoint` | Describe a license endpoint |
| `DeleteLicenseEndpoint` | Delete a license endpoint |
| `ListLicenseEndpoints` | List license endpoints |
| `ListAvailableMeteredProducts` | List the usage-based licensing products |
| `PutMeteredProduct` | Serve a metered product from a license endpoint |
| `DeleteMeteredProduct` | Stop serving a metered product |
| `ListMeteredProducts` | List a license endpoint's metered products |
| `ListVolumes` | List a fleet's persistent volumes (none: floci launches no service-managed workers) |
| `GetVolume` | Describe a persistent volume |
| `DeleteVolume` | Delete a persistent volume |
| `ListTagsForResource` | List a resource's tags |
| `TagResource` | Add or replace tags |
| `UntagResource` | Remove tags |
| `AssumeQueueRoleForRead` | Credentials for the queue's role |
| `AssumeQueueRoleForUser` | Credentials for the queue's role, for a submitting user |
| `AssumeFleetRoleForRead` | Credentials for the fleet's role |
| `CreateJob` | Submit a job from an Open Job Description template, expanding its steps into tasks |
| `GetJob` | Describe a job |
| `ListJobs` | List a queue's jobs |
| `ListJobParameterDefinitions` | List a job template's parameter definitions |
| `GetStep` | Describe a step |
| `ListSteps` | List a job's steps |
| `ListStepConsumers` | List the steps that depend on a step |
| `ListStepDependencies` | List the steps a step depends on |
| `GetTask` | Describe a task |
| `ListTasks` | List a step's tasks |
| `GetSession` | Describe a session |
| `ListSessions` | List a job's sessions |
| `GetSessionAction` | Describe a session action |
| `ListSessionActions` | List a job's session actions |
| `UpdateJob` | Reprioritize, requeue, suspend, cancel or archive a job |
| `UpdateStep` | Move all of a step's tasks to a target run status |
| `UpdateTask` | Move a task to a target run status, interrupting it if it is running |
| `BatchUpdateJob` | Update several jobs |
| `BatchUpdateTask` | Update several tasks |
| `BatchGetJob` | Describe several jobs |
| `BatchGetStep` | Describe several steps |
| `BatchGetTask` | Describe several tasks |
| `BatchGetSession` | Describe several sessions |
| `BatchGetSessionAction` | Describe several session actions |
| `SearchJobs` | Search jobs across queues with filters and sorting |
| `SearchSteps` | Search steps across queues |
| `SearchTasks` | Search tasks across queues |
| `SearchWorkers` | Search workers across fleets |
| `CopyJobTemplate` | Copy a job's template to S3 |
| `StartSessionsStatisticsAggregation` | Aggregate a farm's session statistics by queue, fleet, job, user and period |
| `GetSessionsStatisticsAggregation` | Read an aggregation of session statistics |
| `CreateWorker` | Register a worker of a customer-managed fleet |
| `GetWorker` | Describe a worker |
| `ListWorkers` | List a fleet's workers |
| `BatchGetWorker` | Describe several workers |
| `DeleteWorker` | Delete a worker |
| `UpdateWorker` | Start or stop a worker with its capabilities; returns its log configuration |
| `UpdateWorkerSchedule` | Report session action results and receive assigned sessions |
| `ListSessionsForWorker` | List a worker's sessions |
| `UpdateSession` | End a session |
| `AssumeFleetRoleForWorker` | Credentials for the fleet's role, for its worker |
| `AssumeQueueRoleForWorker` | Credentials for the queue's role, for a worker of an associated fleet |
| `BatchGetJobEntity` | Read the job, step, environment and attachment details a worker runs |
<!-- floci:actions:end -->

## Farms, queues and fleets

A farm refuses deletion while it holds queues, fleets, storage profiles, budgets, limits or associations. A queue
or fleet association must be stopped (`STOP_SCHEDULING_AND_COMPLETE_TASKS` or `STOP_SCHEDULING_AND_CANCEL_TASKS`)
and settle to `STOPPED` before it is deleted, and a storage profile a queue allows or a fleet uses cannot be
deleted. Creating a queue or fleet creates its log group under `/aws/deadline/<farmId>/`, which, as on AWS,
remains after the farm is deleted.

A fleet's capabilities derive from its configuration: `amount.worker.vcpu`, `amount.worker.memory`, GPU amounts,
`attr.worker.os.family` and `attr.worker.cpu.arch`, plus custom amounts and attributes. A queue whose
`defaultBudgetAction` is not `NONE` is `SCHEDULING_BLOCKED` until a budget tracks it. Budgets keep their schedule
(at most 120 days) and actions; floci bills nothing for worker time, so their usage stays at zero.

## Jobs

`CreateJob` parses the template (JSON or YAML) and validates it as `jobtemplate-2023-09`: step names, scripts,
task parameter definitions, environments and acyclic step dependencies. Submitted parameters are checked against
the template's definitions (types, bounds, allowed values) with defaults filled in, and the job's name resolves
its `{{Param.X}}` references. Each step's parameter space expands into tasks: integer ranges such as `1-100:2`,
lists, `CHUNK[INT]` chunks, and combination expressions (`A * B` products, `(A, B)` associations).

Tasks of a step whose dependencies have not all succeeded are `PENDING`; others are `READY`, or `NOT_COMPATIBLE`
when the queue's fleets cannot meet the step's host requirements. Run statuses roll up into each step and job,
which publish `Job Lifecycle Status Change`, `Job Run Status Change`, `Step Run Status Change` and
`Task Run Status Change` events from `aws.deadline` to the default event bus. Jobs, steps and tasks are
requeued, suspended, failed, succeeded or canceled through their update operations; a task in flight is
interrupted and settles when its worker reports.

## Workers

A worker of a customer-managed fleet registers with `CreateWorker`, starts with `UpdateWorker` and its
capabilities, and polls `UpdateWorkerSchedule`. Each poll applies the session actions the worker reports and
assigns work: the highest-priority ready task (oldest job first) of a queue its fleet is actively associated with,
whose host requirements its capabilities meet and whose queue limits have room. The task runs in a session that
syncs job attachments and enters the queue's, job's and step's environments, takes further ready tasks of the
same step, then exits its environments in reverse order and ends. A failed task retries up to the job's
`maxRetriesPerTask`; a job that reaches `maxFailedTasksCount` failed tasks cancels the rest. `BatchGetJobEntity`
serves the job, step, environment and attachment details the agent needs, with path mapping rules between the
job's and the fleet's storage profiles, and `AssumeFleetRoleForWorker` and `AssumeQueueRoleForWorker` vend the
fleet's and queue's role credentials.

Service-managed fleets keep their configuration and capabilities, but floci launches no EC2 workers for them:
their tasks wait for a worker, and they have no persistent volumes.

## Monitors and licensing

A monitor creates an IAM Identity Center application in the given instance (through floci's SSO Admin service)
and serves at `https://<subdomain>.<region>.deadlinecloud.amazonaws.com`. License endpoints check their VPC and
serve the usage-based licensing products of `ListAvailableMeteredProducts`.

## Configuration

```yaml
floci:
  services:
    deadline:
      enabled: true
      transition-millis: 2000   # how long a fleet, job update, license endpoint or statistics aggregation stays in progress
```
