# Comprehend

**Protocol:** JSON 1.1 (`X-Amz-Target: Comprehend_20171127.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `DetectDominantLanguage` | Detect the dominant language of a text |
| `DetectEntities` | Detect named entities (people, places, organizations, dates, quantities) |
| `DetectKeyPhrases` | Detect key noun-phrases in a text |
| `DetectPiiEntities` | Detect personally identifiable information (PII) in a text |
| `ContainsPiiEntities` | Report whether a text contains PII, without locating it |
| `DetectSentiment` | Detect the overall sentiment of a text (POSITIVE/NEGATIVE/NEUTRAL/MIXED) |
| `DetectSyntax` | Tag each word with its part of speech |
| `DetectTargetedSentiment` | Detect the sentiment toward each entity mentioned in a text |
| `DetectToxicContent` | Score text segments for toxicity labels |
| `ClassifyDocument` | Classify a document with a custom classifier endpoint |
| `BatchDetectDominantLanguage` | DetectDominantLanguage for up to 25 documents |
| `BatchDetectEntities` | DetectEntities for up to 25 documents |
| `BatchDetectKeyPhrases` | DetectKeyPhrases for up to 25 documents |
| `BatchDetectSentiment` | DetectSentiment for up to 25 documents |
| `BatchDetectSyntax` | DetectSyntax for up to 25 documents |
| `BatchDetectTargetedSentiment` | DetectTargetedSentiment for up to 25 documents |
| `TagResource` | Tag a job |
| `UntagResource` | Remove tags from a job |
| `ListTagsForResource` | List a job's tags |
| `StartDocumentClassificationJob` | Start an asynchronous document classification job |
| `StartDominantLanguageDetectionJob` | Start an asynchronous dominant language detection job |
| `StartEntitiesDetectionJob` | Start an asynchronous entities detection job |
| `StartEventsDetectionJob` | Start an asynchronous events detection job |
| `StartKeyPhrasesDetectionJob` | Start an asynchronous key phrases detection job |
| `StartPiiEntitiesDetectionJob` | Start an asynchronous PII entities detection job |
| `StartSentimentDetectionJob` | Start an asynchronous sentiment detection job |
| `StartTargetedSentimentDetectionJob` | Start an asynchronous targeted sentiment detection job |
| `StartTopicsDetectionJob` | Start an asynchronous topics detection job |
| `DescribeDocumentClassificationJob` | Get a document classification job's properties |
| `DescribeDominantLanguageDetectionJob` | Get a dominant language detection job's properties |
| `DescribeEntitiesDetectionJob` | Get a entities detection job's properties |
| `DescribeEventsDetectionJob` | Get a events detection job's properties |
| `DescribeKeyPhrasesDetectionJob` | Get a key phrases detection job's properties |
| `DescribePiiEntitiesDetectionJob` | Get a PII entities detection job's properties |
| `DescribeSentimentDetectionJob` | Get a sentiment detection job's properties |
| `DescribeTargetedSentimentDetectionJob` | Get a targeted sentiment detection job's properties |
| `DescribeTopicsDetectionJob` | Get a topics detection job's properties |
| `ListDocumentClassificationJobs` | List document classification jobs |
| `ListDominantLanguageDetectionJobs` | List dominant language detection jobs |
| `ListEntitiesDetectionJobs` | List entities detection jobs |
| `ListEventsDetectionJobs` | List events detection jobs |
| `ListKeyPhrasesDetectionJobs` | List key phrases detection jobs |
| `ListPiiEntitiesDetectionJobs` | List PII entities detection jobs |
| `ListSentimentDetectionJobs` | List sentiment detection jobs |
| `ListTargetedSentimentDetectionJobs` | List targeted sentiment detection jobs |
| `ListTopicsDetectionJobs` | List topics detection jobs |
| `StopDominantLanguageDetectionJob` | Stop a dominant language detection job |
| `StopEntitiesDetectionJob` | Stop a entities detection job |
| `StopEventsDetectionJob` | Stop a events detection job |
| `StopKeyPhrasesDetectionJob` | Stop a key phrases detection job |
| `StopPiiEntitiesDetectionJob` | Stop a PII entities detection job |
| `StopSentimentDetectionJob` | Stop a sentiment detection job |
| `StopTargetedSentimentDetectionJob` | Stop a targeted sentiment detection job |
<!-- floci:actions:end -->

## Emulation Behavior

- **Rule-based analysis:** no model runs, but answers follow the text and the same text
  always gets the same answer, with offsets into the input:
  - `DetectDominantLanguage`: the script (CJK, Arabic, Devanagari), else the share of each
    language's function words, defaulting to `en`.
  - `DetectSyntax`: closed word classes, suffixes, and the previous tag
    ("The cat sat on the mat." → `DET NOUN VERB ADP DET NOUN PUNCT`).
  - `DetectEntities`: `DATE` (years, months, days), `QUANTITY` (numbers), `LOCATION`
    (a gazetteer of places), `ORGANIZATION` (company suffixes and well-known names), and
    capitalized names as `PERSON`; a lone acronym is `OTHER`.
  - `DetectKeyPhrases`: noun-phrase chunks.
  - `DetectPiiEntities` / `ContainsPiiEntities`: `EMAIL`, `PHONE`, `SSN`,
    `CREDIT_DEBIT_NUMBER`, `URL`, `IP_ADDRESS`, `AWS_ACCESS_KEY`, `DATE_TIME`, `ADDRESS`,
    and `NAME` (after "my name is", or a person entity).
  - `DetectSentiment`: a polarity lexicon with negation ("not good" is negative) and
    intensifiers; both polarities in comparable measure is `MIXED`.
  - `DetectTargetedSentiment`: each noun mention takes its clause's sentiment
    ("the screen is gorgeous but the battery is bad").
  - `DetectToxicContent`: a lexicon per toxicity label; `Toxicity` is the highest label score.
- **Batches:** `BatchDetect*` returns one `ResultList` entry per document with its `Index`;
  an empty document or one over 5,000 bytes goes to `ErrorList`. More than 25 documents is
  `BatchSizeLimitExceededException`.
- **Custom models:** Floci trains no classifiers or recognizers, so `ClassifyDocument` and
  `DetectEntities` with an `EndpointArn` return `ResourceUnavailableException`, and a job
  that names a classifier or recognizer returns `ResourceNotFoundException`.
- **Asynchronous jobs:** all nine families (`Start*`, `Describe*`, `List*`, and `Stop*` where
  AWS has it). A job analyzes its input when it starts and writes
  `<output>/<account>-<TYPE>-<JobId>/output/output.tar.gz`, one JSON line per document (a
  PII job in `ONLY_REDACTION` mode writes each document redacted instead). It reports
  `SUBMITTED` for two seconds, `IN_PROGRESS` until four, then `COMPLETED` (or `FAILED` with a
  message when no input could be read). Stopping a job that hasn't finished answers
  `STOP_REQUESTED` and leaves it `STOPPED`. An S3 URI or role ARN that breaks AWS's pattern
  is a `ValidationException`. Jobs are taggable.
- **Real input validation:** `Text` and `LanguageCode` (where applicable) are still
  required and `LanguageCode` is validated against Comprehend's supported set —
  this is protocol compatibility, not content analysis. `DetectSentiment` and
  `DetectKeyPhrases` accept the general set (`en`, `es`, `fr`, `de`, `it`, `pt`,
  `ar`, `hi`, `ja`, `ko`, `zh`, `zh-TW`); `DetectPiiEntities` and
  `ContainsPiiEntities` accept only `en`/`es`, matching AWS's narrower support
  for PII detection.
- **Out of scope:** model training (`DocumentClassifier`, `EntityRecognizer`, `Flywheel`,
  `Dataset`, `Endpoint` operations).
- **Size limits:** `Text` over 100,000 bytes is `TextSizeLimitExceededException`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_COMPREHEND_ENABLED` | `true` | Enable or disable the service |
| `AI_MOCK_CONFIG` | unset | Path to a shared mock-response config file — see "Mock Responses" below |

## Mock Responses

To pin a specific answer for a text (e.g. to exercise "escalate if sentiment is NEGATIVE"
with an exact score), point
`AI_MOCK_CONFIG` at a JSON file shared across Comprehend, Textract, and Rekognition:

```json
{
  "comprehend": {
    "I am furious about this outage": {
      "DetectSentiment": { "Sentiment": "NEGATIVE", "SentimentScore": { "Positive": 0.0, "Negative": 0.95, "Neutral": 0.05, "Mixed": 0.0 } }
    }
  }
}
```

The lookup key is the **exact `Text` value** sent in the request. The file is re-read when
its modification time changes, so it can be edited without restarting the emulator. A
missing file, an unset `AI_MOCK_CONFIG`, or no matching entry all fall back to the rule-based
analysis; mocking is opt-in and never breaks a call that isn't using it.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws comprehend detect-sentiment \
  --text "Floci makes local AWS testing painless" \
  --language-code en \
  --endpoint-url $AWS_ENDPOINT_URL

aws comprehend detect-key-phrases \
  --text "Floci makes local AWS testing painless" \
  --language-code en \
  --endpoint-url $AWS_ENDPOINT_URL

aws comprehend detect-dominant-language \
  --text "Floci makes local AWS testing painless" \
  --endpoint-url $AWS_ENDPOINT_URL

aws comprehend detect-pii-entities \
  --text "My SSN is 123-45-6789" \
  --language-code en \
  --endpoint-url $AWS_ENDPOINT_URL
```

## SDK Example (Java)

```java
ComprehendClient comprehend = ComprehendClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();

DetectSentimentResponse response = comprehend.detectSentiment(req -> req
    .text("Floci makes local AWS testing painless")
    .languageCode("en"));

System.out.println("Sentiment: " + response.sentiment());
```
