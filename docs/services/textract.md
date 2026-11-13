# Textract

**Protocol:** JSON 1.1
**Header:** `X-Amz-Target: Textract.<Action>`

Floci emulates the AWS Textract API: document analysis with AWS-shaped responses, async jobs, and custom query adapters. No OCR runs: an analysis returns a fixed set of `Block` objects with synthetic metadata, unless `AI_MOCK_CONFIG` has an answer for the document (see "Mock Responses").

## Supported Operations

| Operation | Notes |
|-----------|-------|
| `DetectDocumentText` | Returns stub PAGE + LINE + WORD blocks |
| `AnalyzeDocument` | Returns stub blocks; `FeatureTypes` accepted but ignored |
| `StartDocumentTextDetection` | Returns a `JobId`; job is immediately SUCCEEDED |
| `GetDocumentTextDetection` | Returns `SUCCEEDED` + stub blocks for a known `JobId` |
| `StartDocumentAnalysis` | Returns a `JobId`; job is immediately SUCCEEDED |
| `GetDocumentAnalysis` | Returns `SUCCEEDED` + stub blocks for a known `JobId` |
| `AnalyzeExpense` | One expense document per page with the document's blocks; no summary fields unless mocked |
| `AnalyzeID` | One identity document per page (1–2 pages); no fields unless mocked |
| `StartExpenseAnalysis` / `GetExpenseAnalysis` | Async `AnalyzeExpense` |
| `StartLendingAnalysis` / `GetLendingAnalysis` / `GetLendingAnalysisSummary` | Each page `UNCLASSIFIED`, grouped into one document |
| `CreateAdapter` / `GetAdapter` / `UpdateAdapter` / `DeleteAdapter` / `ListAdapters` | Custom query adapters; ARNs keep Textract's `:/adapters/<id>` form |
| `CreateAdapterVersion` / `GetAdapterVersion` / `DeleteAdapterVersion` / `ListAdapterVersions` | Reads the training manifest from S3; a version is `ACTIVE` at once and changes no analysis |
| `TagResource` / `UntagResource` / `ListTagsForResource` | Tags on adapters and adapter versions |

A `Document` or `DocumentLocation` is read: an S3 object that can't be read is refused with
`InvalidS3ObjectException`, and a request without one with `InvalidParameterException`, as Textract
refuses them. Deleting an adapter version that doesn't exist succeeds, as it does on AWS.

### Block shape

Each response includes a 3-block hierarchy matching the [AWS Block API shape](https://docs.aws.amazon.com/textract/latest/dg/API_Block.html):

| BlockType | Text | Relationships |
|-----------|------|---------------|
| `PAGE` | *(none)* | CHILD → LINE |
| `LINE` | `"Floci"` | CHILD → WORD |
| `WORD` | `"Floci"` | *(none)* |

Every block includes: `Id` (UUID), `Confidence` (99.9), `Page` (1), and a `Geometry` with `BoundingBox` + 4-point `Polygon`.

### Async job lifecycle

`Start*` operations store a job ID and its result in memory and return the ID immediately. `Get*` calls with a valid job ID return `JobStatus: SUCCEEDED` and the same result every time, so a result can be fetched repeatedly. A job is retained for 7 days, after which it expires and `Get*` returns `InvalidJobIdException`; expired jobs are also swept when a new job is stored. The store is capped at 10,000 jobs: once the cap is reached the oldest job is evicted first, which AWS does not do, so a job that is still inside its 7-day window can read as `InvalidJobIdException` once enough newer jobs exist. Job IDs are not persisted across restarts. Using a `GetDocumentTextDetection` job ID in `GetDocumentAnalysis` (or vice-versa) returns `InvalidJobIdException`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_TEXTRACT_ENABLED` | `true` | Enable or disable the service |
| `AI_MOCK_CONFIG` | unset | Path to a shared mock-response config file — see "Mock Responses" below |

## Mock Responses

`DetectDocumentText` and `AnalyzeDocument` return the same stub `Block` list for every
call by default. To exercise application logic that reads the detected text, point
`AI_MOCK_CONFIG` at a JSON file shared across Textract, Comprehend, and Rekognition:

```json
{
  "textract": {
    "my-bucket/invoice.pdf": {
      "DetectDocumentText": {
        "DocumentMetadata": { "Pages": 1 },
        "Blocks": [{ "BlockType": "LINE", "Id": "line-1", "Confidence": 99.5, "Text": "Invoice Total: $42.00" }],
        "DetectDocumentTextModelVersion": "1.0"
      }
    }
  }
}
```

A document is looked up by each of its keys in turn: **`"<Bucket>/<Name>"`** for an S3
document, then **`"sha256:<hex>"`** of the document's bytes, whether they were sent inline
(`Bytes`) or read from S3. The content key answers for a document wherever it was uploaded,
including a bucket whose name is generated at deploy time. Async jobs look their document up
when they start. `AnalyzeDocument`, `AnalyzeExpense`, `AnalyzeID`, and lending pages fall back
to the document's `DetectDocumentText` blocks. The file is re-read when its modification time
changes, so it can be edited without restarting the emulator. A missing file, an unset
`AI_MOCK_CONFIG`, or no matching entry all fall back to the default stub.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# DetectDocumentText
aws textract detect-document-text \
  --document '{"S3Object":{"Bucket":"my-bucket","Name":"test.pdf"}}'

# AnalyzeDocument
aws textract analyze-document \
  --document '{"S3Object":{"Bucket":"my-bucket","Name":"test.pdf"}}' \
  --feature-types TABLES FORMS

# Async: start + poll
JOB_ID=$(aws textract start-document-text-detection \
  --document-location '{"S3Object":{"Bucket":"my-bucket","Name":"test.pdf"}}' \
  --query JobId --output text)

aws textract get-document-text-detection --job-id "$JOB_ID"
```

```python
import boto3

client = boto3.client("textract", endpoint_url="http://localhost:4566")

# Sync
resp = client.detect_document_text(
    Document={"S3Object": {"Bucket": "my-bucket", "Name": "test.pdf"}}
)
for block in resp["Blocks"]:
    print(block["BlockType"], block.get("Text", ""))

# Async
job = client.start_document_text_detection(
    DocumentLocation={"S3Object": {"Bucket": "my-bucket", "Name": "test.pdf"}}
)
result = client.get_document_text_detection(JobId=job["JobId"])
print(result["JobStatus"])  # SUCCEEDED
```

## Out of Scope

- Real OCR or document analysis (a fixed stub block list unless mocked).
- Adapter training: adapter versions don't change analysis.
- `GetDocumentTextDetection` / `GetDocumentAnalysis` pagination via `NextToken`.
- Persistent job storage across restarts.

