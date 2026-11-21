# Fraud Detector

**Protocol:** JSON 1.1
**Endpoint:** `POST /` + `X-Amz-Target: AWSHawksNestServiceFacade.*`

Amazon Fraud Detector scores events with rules and models. An event type names the variables, entity types and
labels its events carry; a detector evaluates one event type with versioned rules.

## Rules

Rules are written in DETECTORPL. A rule reads variables (`$email`), lists (`$ip in @blocked_ips`), literals,
`null`, `true` and `false`, and combines them with `== != > >= < <=`, `in` and `not in`, `and`, `or`, `!`,
arithmetic and the functions `regex_match`, `lowercase`, `uppercase`, `getcurrentdatetime`, `isbefore`, `isafter`
and `getepochmilliseconds`. `CreateRule` parses the expression and checks that its variables belong to the
detector's event type and that its lists and outcomes exist.

## Predictions

`GetEventPrediction` runs the detector's ACTIVE version, or the version it names. A missing variable takes its
default value. The version's model versions score the event, its external models are invoked through their
SageMaker endpoints, and its rules run in order: `FIRST_MATCHED` stops at the first match, `ALL_MATCHED` runs
them all. Every prediction is kept for `ListEventPredictions` and `GetEventPredictionMetadata`, which shows each
rule's expression with the values it saw. When the event type ingests events, the prediction stores the event;
with event orchestration, it is published to EventBridge as `Event Prediction Result Returned` from
`aws.frauddetector`.

## Models

A model version trains on labeled events: the event type's stored events in a time window, or a CSV file in S3.
It learns the log-odds of fraud for each value of its variables (numbers fall into quantile bins). Activating it
creates the variable `<modelId>_insightscore`, the fraud probability scaled to 0–1000, which rules can read.

## Batch jobs

Batch import and batch prediction jobs read a CSV file from S3 with `EVENT_ID`, `EVENT_TIMESTAMP`, `ENTITY_ID`,
`ENTITY_TYPE`, `EVENT_LABEL` and variable columns. An import stores the events and writes failed rows to
`<outputPath>/<jobId>_failed_records.csv`; a prediction writes each row's outcomes, rule results and model scores
to `<outputPath>/<jobId>_output.csv`. A job whose role doesn't exist fails.

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `PutEntityType` | Create or update an entity type |
| `GetEntityTypes` | Describe one entity type or list them |
| `DeleteEntityType` | Delete an entity type no event type uses |
| `PutLabel` | Create or update a label |
| `GetLabels` | Describe one label or list them |
| `DeleteLabel` | Delete a label no event type uses |
| `PutOutcome` | Create or update an outcome |
| `GetOutcomes` | Describe one outcome or list them |
| `DeleteOutcome` | Delete an outcome no rule returns |
| `CreateVariable` | Create a variable with a default value of its data type |
| `BatchCreateVariable` | Create several variables, reporting each failure |
| `GetVariables` | Describe one variable or list them |
| `BatchGetVariable` | Describe several variables by name |
| `UpdateVariable` | Change a variable's default value, description or type |
| `DeleteVariable` | Delete a variable no event type or rule uses |
| `CreateList` | Create a list of values rules can test with `in @list` |
| `UpdateList` | Replace, append to or remove from a list |
| `GetListElements` | Read a list's elements |
| `GetListsMetadata` | Describe one list or list them |
| `DeleteList` | Delete a list no rule uses |
| `PutEventType` | Create or update an event type from existing variables, entity types and labels |
| `GetEventTypes` | Describe event types with their ingested event statistics |
| `DeleteEventType` | Delete an event type no detector or model uses, with its stored events |
| `PutDetector` | Create or update a detector for an event type |
| `GetDetectors` | Describe one detector or list them |
| `DescribeDetector` | List a detector's versions |
| `DeleteDetector` | Delete a detector with no versions or rules |
| `CreateRule` | Create a rule; its expression is parsed and checked against the detector's event type |
| `UpdateRuleVersion` | Create the next version of a rule |
| `UpdateRuleMetadata` | Change a rule version's description |
| `GetRules` | Describe a detector's rules and their versions |
| `DeleteRule` | Delete a rule version no ACTIVE or INACTIVE detector version uses |
| `CreateDetectorVersion` | Create a DRAFT detector version from rules, model versions and external models |
| `GetDetectorVersion` | Describe a detector version |
| `UpdateDetectorVersion` | Change a DRAFT detector version |
| `UpdateDetectorVersionMetadata` | Change a detector version's description |
| `UpdateDetectorVersionStatus` | Activate or deactivate a version; activating one deactivates the previous |
| `DeleteDetectorVersion` | Delete a version that isn't ACTIVE |
| `CreateModel` | Create a model for an event type |
| `UpdateModel` | Change a model's description |
| `GetModels` | Describe models |
| `DeleteModel` | Delete a model with no versions |
| `CreateModelVersion` | Train a new major version on stored events or an S3 CSV file |
| `UpdateModelVersion` | Train the next minor version of a major version |
| `UpdateModelVersionStatus` | Activate, deactivate or cancel training; activating creates the score variable |
| `GetModelVersion` | Describe a model version |
| `DescribeModelVersions` | Describe model versions with their training results |
| `DeleteModelVersion` | Delete a model version no detector version uses |
| `PutExternalModel` | Register a SageMaker endpoint with its input and output mapping |
| `GetExternalModels` | Describe external models |
| `DeleteExternalModel` | Delete an external model no detector version uses |
| `SendEvent` | Store an event of an event type that ingests events |
| `GetEvent` | Read a stored event |
| `UpdateEventLabel` | Label a stored event |
| `DeleteEvent` | Delete a stored event and, optionally, its predictions |
| `DeleteEventsByEventType` | Delete every stored event of an event type in the background |
| `GetDeleteEventsByEventTypeStatus` | Read the progress of an event type's deletion |
| `GetEventPrediction` | Score an event with the detector's models, external models and rules |
| `ListEventPredictions` | List past predictions by event, event type, detector, version and time |
| `GetEventPredictionMetadata` | Show what a prediction evaluated, with each rule's values |
| `CreateBatchImportJob` | Store events from an S3 CSV file |
| `GetBatchImportJobs` | Describe batch import jobs |
| `CancelBatchImportJob` | Cancel a running batch import |
| `DeleteBatchImportJob` | Delete a finished batch import job |
| `CreateBatchPredictionJob` | Score events from an S3 CSV file and write the results to S3 |
| `GetBatchPredictionJobs` | Describe batch prediction jobs |
| `CancelBatchPredictionJob` | Cancel a running batch prediction |
| `DeleteBatchPredictionJob` | Delete a finished batch prediction job |
| `GetKMSEncryptionKey` | Read the account's encryption key |
| `PutKMSEncryptionKey` | Set the account's encryption key to an existing KMS key or DEFAULT |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
| `ListTagsForResource` | List a resource's tags |
<!-- floci:actions:end -->
