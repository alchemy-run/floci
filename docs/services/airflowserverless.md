# Amazon MWAA Serverless

**Protocol:** JSON 1.0 (`X-Amz-Target: AmazonMWAAServerless.<Action>`)
**Endpoint:** `POST http://localhost:4566/`, signed for `airflow-serverless`

Floci runs MWAA Serverless workflows in Apache Airflow: each run executes the workflow's DAG, and its operators call
Floci's services.

## Workflows

A workflow's definition is YAML in S3, in the DAG Factory format: one DAG id mapping to its `default_args`,
`schedule`, `params` and `tasks`, each task naming an operator by import path with its arguments and `dependencies`.
Creating or updating a workflow snapshots the definition (and the code package, a zip, when given) as a new version
with a 32-character version id; later changes to the S3 objects don't affect it. The definition is checked: one DAG,
tasks that name operators, dependencies on tasks that exist and form no cycle, a schedule that is a cron expression
or preset, and a code package for operators outside `airflow.*`. A definition that can't be read or doesn't check
fails with `ValidationException`.

Workflow ARNs end in the name and a 10-character suffix. Creating a workflow creates its log group,
`/aws/mwaa-serverless/<name>-<suffix>/` unless the logging configuration names another; deleting it leaves the log
group, as on AWS. Deleting a workflow stops its runs and leaves it `DELETING` for a transition; deleting a version
removes that version. Workflows take tags.

## Runs

`StartWorkflowRun` runs a version (the latest by default) with optional override parameters, `STARTING`, then
`QUEUED` while the Airflow runtime starts and `RUNNING`, ending `SUCCESS`, `FAILED`, `STOPPED` or `TIMEOUT`. A workflow
with a schedule also runs on it (`RunType: SCHEDULED`).

Runs execute in one container of the configured Airflow image (`apache/airflow:3.1.3-python3.12` by default): the DAG
is built from the definition and run with `DAG.test()`, with the workflow role's credentials and `AWS_ENDPOINT_URL`
pointed at Floci, so the Amazon provider's operators (S3, Lambda, SQS, SNS, Step Functions, Glue, Athena, EventBridge
and the rest) act on Floci. The code package is on the Python path, so `PythonOperator` callables, `BashOperator`
scripts and custom operators run too. Each task instance records its state, attempt, timing, operator and XCom
`return_value`; its log lines go to a stream of the workflow's log group. Run and task state changes are sent to the
default event bus as `aws.airflow-serverless` events (`MWAA Serverless Workflow Run <State>` and
`MWAA Serverless Task <State>`). Running workflows needs Docker.

## Configuration

```yaml
floci:
  services:
    airflow-serverless:
      enabled: true
      airflow-image: apache/airflow:3.1.3-python3.12
      max-run-minutes: 60         # a longer run times out
      max-concurrent-runs: 20
      transition-millis: 2000     # how long a deleted workflow stays DELETING
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateWorkflow` | Create a workflow from a YAML definition in S3 |
| `DeleteWorkflow` | Delete a workflow or one of its versions |
| `GetTaskInstance` | Describe a task instance of a run |
| `GetWorkflow` | Describe a workflow or one of its versions |
| `GetWorkflowRun` | Describe a run |
| `ListTagsForResource` | List a workflow's tags |
| `ListTaskInstances` | List a run's task instances |
| `ListWorkflowRuns` | List a workflow's runs |
| `ListWorkflowVersions` | List a workflow's versions |
| `ListWorkflows` | List workflows |
| `StartWorkflowRun` | Run a workflow version in Apache Airflow |
| `StopWorkflowRun` | Stop a run |
| `TagResource` | Tag a workflow |
| `UntagResource` | Remove tags |
| `UpdateWorkflow` | Publish a workflow's next version |
<!-- floci:actions:end -->
