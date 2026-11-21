# Amazon Bedrock Data Automation

**Protocol:** REST JSON (control plane) and JSON 1.1 (runtime)
**Endpoint:** `http://localhost:4566/`, signed for `bedrock`. The control plane is under `/blueprints`, `/data-automation-projects`, `/data-automation-libraries`, `/invokeBlueprintOptimizationAsync` and `/getBlueprintOptimizationStatus`, and tags go through Bedrock's `/tagResource`, `/untagResource` and `/listTagsForResource`. The runtime is `POST /` with `X-Amz-Target: AmazonBedrockKeystoneRuntimeService.<Operation>`.

Floci runs Bedrock Data Automation on the documents you give it. It reads an asset from S3, extracts its text, and writes AWS's output files. It also fills the asset's matching blueprint with the values the document gives.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateBlueprint` | Create a blueprint with its schema |
| `ListBlueprints` | Page through the account's or AWS's blueprints |
| `GetBlueprint` | Describe a blueprint at a stage or version |
| `UpdateBlueprint` | Replace a stage's schema |
| `CreateBlueprintVersion` | Snapshot the LIVE stage as a version |
| `CopyBlueprintStage` | Copy one stage onto the other |
| `DeleteBlueprint` | Delete a version, or the blueprint |
| `CreateDataAutomationProject` | Create a project |
| `ListDataAutomationProjects` | Page through projects, by stage, blueprint or library |
| `GetDataAutomationProject` | Describe a project at a stage |
| `UpdateDataAutomationProject` | Replace a project stage's configuration |
| `DeleteDataAutomationProject` | Delete a project |
| `CreateDataAutomationLibrary` | Create a vocabulary library |
| `ListDataAutomationLibraries` | Page through libraries |
| `GetDataAutomationLibrary` | Describe a library |
| `UpdateDataAutomationLibrary` | Change a library's description |
| `DeleteDataAutomationLibrary` | Delete a library no project uses |
| `InvokeDataAutomationLibraryIngestionJob` | Upsert or delete entities in the background |
| `ListDataAutomationLibraryIngestionJobs` | Page through ingestion jobs |
| `GetDataAutomationLibraryIngestionJob` | Describe an ingestion job |
| `ListDataAutomationLibraryEntities` | Page through vocabulary entities |
| `GetDataAutomationLibraryEntity` | Describe a vocabulary entity |
| `InvokeBlueprintOptimizationAsync` | Rewrite a blueprint's instructions from samples |
| `GetBlueprintOptimizationStatus` | An optimization's status and output |
| `InvokeDataAutomationAsync` | Process an S3 asset and write its output to S3 |
| `InvokeDataAutomation` | Process an asset and return its output |
| `GetDataAutomationStatus` | An invocation's status and output location |
| `ListTagsForResource` | List tags |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags |
<!-- floci:actions:end -->

## Blueprints

A blueprint is a JSON schema with a `class` and properties, each with an `inferenceType` (`explicit` or `inferred`) and an `instruction`, or a `$ref` to `definitions`. A schema without those fails with `ValidationException`.

A blueprint keeps a schema per stage. `LIVE` is the default, and `DEVELOPMENT` is optional. `CreateBlueprintVersion` snapshots `LIVE` as the next numbered version. `CopyBlueprintStage` copies one stage onto the other, and fails with `ResourceNotFoundException` when the source stage doesn't exist. `DeleteBlueprint` with a version deletes that version; without one it deletes the blueprint. Names are unique in the account and Region.

`ListBlueprints` with `resourceOwner: SERVICE` lists the AWS-managed blueprints, owned by `aws` (`arn:aws:bedrock:<region>:aws:blueprint/bedrock-data-automation-public-<name>`): invoice, receipt, bank statement, payslip, US driver license and W-2.

## Projects and libraries

A project holds a standard output configuration and a custom output configuration per stage. The custom configuration names blueprints, by stage or version, which must exist, and the libraries it names must exist too. A project is `COMPLETED` once created or updated.

A library holds vocabulary entities. `InvokeDataAutomationLibraryIngestionJob` upserts or deletes entities in the background. The entities come from an inline payload, or from a JSON or JSON Lines object in S3. The job ends `COMPLETED`, `COMPLETED_WITH_ERRORS` or `FAILED`, and writes its report to `<output s3Uri>/<job id>/report.json`. A library a project uses can't be deleted.

## Processing an asset

`InvokeDataAutomationAsync` reads the asset from S3 and processes it in the background, with the project's configuration (or the defaults) and blueprints. The invocation is `Created`, then `InProgress`, then `Success`. When the input can't be read or isn't a supported file, it ends `ClientError`. The profile must be the cross-Region profile of the Region's geography (`us.data-automation-v1` in a `us-*` Region), as AWS checks it.

The output is written in AWS's layout under `<output s3Uri>/<invocation id>/`:

- `job_metadata.json`, which `GetDataAutomationStatus` points to
- `0/standard_output/0/result.json`
- `0/custom_output/0/result.json`, when a blueprint matches

The standard output holds the document's text as plain text, Markdown or HTML, by the project's text formats. It gives the text at each extraction granularity the project asks for (`DOCUMENT`, `PAGE`, `ELEMENT`, `LINE`, `WORD`), with word, line and element counts.

Floci reads the text of PDFs (uncompressed and `FlateDecode` content streams) and of text documents (plain text, Markdown, CSV, HTML). It has no OCR, so text drawn as an image isn't read, and image, audio and video assets get empty standard output.

The custom output gives the matched blueprint, the document class, and the blueprint's fields in `inference_result`. With one blueprint, the asset matches it. With several, the asset matches the one whose fields the text gives most; when none of them is given, the output is `NO_MATCH`.

When Floci's Bedrock Runtime proxies to a model (`floci.services.bedrock-runtime.backend: proxy`), that model fills the fields. Otherwise each field is the value its label gives in the text: `invoice_number` reads `Invoice Number: INV-42`. The words of a field's instruction are tried as labels too, so `The amount due` reads `Amount Due: $1,250.00`. Number, integer and boolean fields are converted.

`InvokeDataAutomation` processes an asset given as `bytes` or an `s3Uri`, and returns the same output inline. An input with neither, or with both, fails with `ValidationException`.

With `notificationConfiguration.eventBridgeConfiguration.eventBridgeEnabled`, an invocation sends `aws.bedrock` events to the default event bus:

- `Bedrock Data Automation Job Created`
- then `Bedrock Data Automation Job Succeeded`, or `Bedrock Data Automation Job Failed With Client Error` / `With Service Error`

Each event carries the job ID, status, modality, and the input and output S3 locations.

## Blueprint optimization

`InvokeBlueprintOptimizationAsync` takes samples, each an asset and its ground truth, a JSON object of the field values the asset should give. For each field whose value the asset's text shows, it rewrites the instruction to name the label the value follows. The optimized schema replaces the blueprint stage's schema, with the samples and the time recorded on it. It's also written to `<output s3Uri>/<invocation id>/optimized_blueprint.json`, with each field's accuracy before optimization. An optimization without samples fails with `ValidationException`.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_BEDROCK_DATA_AUTOMATION_ENABLED` | `true` | Enable or disable Bedrock Data Automation |

For the upstream API shape, see the [Amazon Bedrock Data Automation API Reference](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_Operations_Data_Automation_for_Amazon_Bedrock.html).
