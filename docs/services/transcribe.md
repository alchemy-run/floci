# Transcribe

**Protocol:** JSON 1.1
**Header:** `X-Amz-Target: Transcribe.<Action>`
**Endpoint prefix:** `transcribe`

Floci emulates Amazon Transcribe's batch control plane: transcription, call
analytics, medical, and medical scribe jobs; custom and medical vocabularies;
vocabulary filters; call analytics categories; custom language models; and tags.

No speech recognition runs. A job reads its media from Floci's S3 when it starts
and finishes at once: `COMPLETED`, with a transcript written to the output bucket
(empty text, unless `AI_MOCK_CONFIG` has a `transcribe` entry for the media URI
with a `Transcript` value), or `FAILED` with Transcribe's `FailureReason` when the
media can't be read. A standard transcription job then sends the
`Transcribe Job State Change` event (source `aws.transcribe`) to the default
EventBridge bus. A vocabulary or language model whose input in S3 can't be read is
`FAILED` the same way.

## Supported Operations

| Operation | Notes |
|-----------|-------|
| `StartTranscriptionJob` | Reads the media; writes the transcript to `OutputBucketName`/`OutputKey` when given |
| `GetTranscriptionJob` | Returns a stored job by `TranscriptionJobName` |
| `ListTranscriptionJobs` | Lists jobs with optional `Status`, `JobNameContains`, and `MaxResults` filters |
| `DeleteTranscriptionJob` | Removes a stored job |
| `StartCallAnalyticsJob` / `GetCallAnalyticsJob` / `ListCallAnalyticsJobs` / `DeleteCallAnalyticsJob` | Call analytics jobs |
| `StartMedicalTranscriptionJob` / `GetMedicalTranscriptionJob` / `ListMedicalTranscriptionJobs` / `DeleteMedicalTranscriptionJob` | Medical jobs (`en-US` only); the transcript goes to `OutputBucketName` |
| `StartMedicalScribeJob` / `GetMedicalScribeJob` / `ListMedicalScribeJobs` / `DeleteMedicalScribeJob` | HealthScribe jobs; writes `transcript.json` and `summary.json` under the job name |
| `CreateVocabulary` / `GetVocabulary` / `UpdateVocabulary` / `ListVocabularies` / `DeleteVocabulary` | Custom vocabularies from `Phrases` or a `VocabularyFileUri` |
| `CreateMedicalVocabulary` / `GetMedicalVocabulary` / `UpdateMedicalVocabulary` / `ListMedicalVocabularies` / `DeleteMedicalVocabulary` | Medical vocabularies from a `VocabularyFileUri` |
| `CreateVocabularyFilter` / `GetVocabularyFilter` / `UpdateVocabularyFilter` / `ListVocabularyFilters` / `DeleteVocabularyFilter` | Filters from `Words` or a `VocabularyFilterFileUri` |
| `CreateCallAnalyticsCategory` / `GetCallAnalyticsCategory` / `UpdateCallAnalyticsCategory` / `ListCallAnalyticsCategories` / `DeleteCallAnalyticsCategory` | Categories with 1–20 rules |
| `CreateLanguageModel` / `DescribeLanguageModel` / `ListLanguageModels` / `DeleteLanguageModel` | `COMPLETED` when the training prefix has objects, `FAILED` otherwise |
| `TagResource` / `UntagResource` / `ListTagsForResource` | Tags on any of the above by ARN |

`LanguageCode` defaults to `en-US` and `MediaFormat` defaults to `mp4` when
they are omitted from `StartTranscriptionJob`.

Errors follow Transcribe, operation by operation: a missing job or vocabulary is
`BadRequestException`; a missing filter, category, or language model is
`NotFoundException` (except `DeleteLanguageModel`, whose model has no
`NotFoundException`: `BadRequestException`); a name in use is `ConflictException`;
a media or file URI that doesn't match `(s3://|http(s*)://).+` is a
`BadRequestException` validation error.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_TRANSCRIBE_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

aws transcribe start-transcription-job \
  --transcription-job-name floci-demo \
  --media MediaFileUri=s3://my-bucket/audio.mp3 \
  --language-code en-US \
  --media-format mp3

aws transcribe get-transcription-job \
  --transcription-job-name floci-demo

aws transcribe create-vocabulary \
  --vocabulary-name floci-vocab \
  --language-code en-US

aws transcribe list-vocabularies \
  --name-contains floci
```

```python
import boto3

client = boto3.client(
    "transcribe",
    endpoint_url="http://localhost:4566",
    region_name="us-east-1",
)

client.start_transcription_job(
    TranscriptionJobName="floci-demo",
    Media={"MediaFileUri": "s3://my-bucket/audio.mp3"},
    LanguageCode="en-US",
    MediaFormat="mp3",
)

job = client.get_transcription_job(TranscriptionJobName="floci-demo")
print(job["TranscriptionJob"]["TranscriptionJobStatus"])  # COMPLETED
```

## Related Docs

- [Services overview](index.md)
- [AWS CLI & SDK setup](../getting-started/aws-setup.md)
- [Environment variables](../configuration/environment-variables.md)

## Out of Scope

- Real speech-to-text processing or audio file parsing.
- Streaming transcription.
- Transcription and call analytics job events other than the standard
  `Transcribe Job State Change`.
