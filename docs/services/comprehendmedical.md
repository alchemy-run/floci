# Amazon Comprehend Medical

**Protocol:** JSON 1.1
**Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: ComprehendMedical_20181030.<Operation>`, signed for `comprehendmedical`

Floci reads clinical text with a built-in rule-based analyzer: medical conditions, medications and their dosage, frequency and route, tests, procedures, anatomy, social history, and protected health information, each linked to ICD-10-CM, RxNorm or SNOMED CT concepts. Batch jobs analyze files in S3 the same way.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `DetectEntities` | Deprecated; answers as `DetectEntitiesV2` |
| `DetectEntitiesV2` | Medical entities with categories, types, traits and medication attributes |
| `DetectPHI` | Protected health information: names, ages, dates, contact details, identifiers, addresses, professions |
| `InferICD10CM` | Medical conditions with ICD-10-CM concepts |
| `InferRxNorm` | Medications with RxNorm concepts and their attributes |
| `InferSNOMEDCT` | Conditions, procedures and anatomy with SNOMED CT concepts |
| `StartEntitiesDetectionV2Job` | Analyze an S3 prefix as a entities detection job |
| `StartICD10CMInferenceJob` | Analyze an S3 prefix as a ICD-10-CM inference job |
| `StartPHIDetectionJob` | Analyze an S3 prefix as a PHI detection job |
| `StartRxNormInferenceJob` | Analyze an S3 prefix as a RxNorm inference job |
| `StartSNOMEDCTInferenceJob` | Analyze an S3 prefix as a SNOMED CT inference job |
| `DescribeEntitiesDetectionV2Job` | Return a entities detection job by `JobId` |
| `DescribeICD10CMInferenceJob` | Return a ICD-10-CM inference job by `JobId` |
| `DescribePHIDetectionJob` | Return a PHI detection job by `JobId` |
| `DescribeRxNormInferenceJob` | Return a RxNorm inference job by `JobId` |
| `DescribeSNOMEDCTInferenceJob` | Return a SNOMED CT inference job by `JobId` |
| `ListEntitiesDetectionV2Jobs` | Page through entities detection jobs, filtered by name, status and submit time |
| `ListICD10CMInferenceJobs` | Page through ICD-10-CM inference jobs, filtered by name, status and submit time |
| `ListPHIDetectionJobs` | Page through PHI detection jobs, filtered by name, status and submit time |
| `ListRxNormInferenceJobs` | Page through RxNorm inference jobs, filtered by name, status and submit time |
| `ListSNOMEDCTInferenceJobs` | Page through SNOMED CT inference jobs, filtered by name, status and submit time |
| `StopEntitiesDetectionV2Job` | Stop a running entities detection job |
| `StopICD10CMInferenceJob` | Stop a running ICD-10-CM inference job |
| `StopPHIDetectionJob` | Stop a running PHI detection job |
| `StopRxNormInferenceJob` | Stop a running RxNorm inference job |
| `StopSNOMEDCTInferenceJob` | Stop a running SNOMED CT inference job |
<!-- floci:actions:end -->

## Real-time analysis

The analyzer matches a lexicon of common clinical terms case-insensitively, preferring the longest term (`type 2 diabetes` over `diabetes`).

- **`DetectEntitiesV2`** (and the deprecated `DetectEntities`) returns every entity with its `Category` (`MEDICAL_CONDITION`, `MEDICATION`, `TEST_TREATMENT_PROCEDURE`, `ANATOMY`, `BEHAVIORAL_ENVIRONMENTAL_SOCIAL`, `PROTECTED_HEALTH_INFORMATION`) and `Type`. A medication carries the `DOSAGE`, `FREQUENCY`, `ROUTE_OR_MODE`, `FORM` and `DURATION` found in its clause as `Attributes`. A condition has the trait `SYMPTOM` or `DIAGNOSIS`, and `NEGATION` when a cue such as `no`, `denies` or `without` precedes it in the same clause.
- **`DetectPHI`** returns names (titled, or two capitalized words that are not clinical terms), ages, dates, phone numbers, emails, URLs, identifiers (`MRN`, `ID`), street and facility addresses, and professions.
- **`InferICD10CM`**, **`InferRxNorm`** and **`InferSNOMEDCT`** return the conditions, medications, or conditions, procedures and anatomy, each with its `ICD10CMConcepts`, `RxNormConcepts` or `SNOMEDCTConcepts`. `InferSNOMEDCT` also reports `SNOMEDCTDetails` and `Characters`.

Text must be 1 to 20,000 bytes (10,000 characters for `InferSNOMEDCT`); longer text is a `TextSizeLimitExceededException`, and missing text an `InvalidRequestException`.

A response configured in the `AI_MOCK_CONFIG` file ([format](comprehend.md#mock-responses)) under `comprehendmedical`, keyed by the exact text and then the action, is returned instead of the analysis.

## Batch jobs

The five job families (`EntitiesDetectionV2`, `ICD10CMInference`, `PHIDetection`, `RxNormInference`, `SNOMEDCTInference`) each have `Start`, `Describe`, `List` and `Stop` actions.

`Start…Job` needs `InputDataConfig.S3Bucket`, `OutputDataConfig.S3Bucket`, `LanguageCode` `en`, and a `DataAccessRoleArn` naming an IAM role that exists; any other role is an `InvalidRequestException`. The job analyzes every file under `InputDataConfig.S3Key` when it starts and writes `<file>.out` (the same JSON as the real-time action) and a `Manifest` under `<OutputDataConfig.S3Key>/<account>-<Type>-<JobId>/`, which is the `OutputDataConfig` the job reports. A `ClientRequestToken` makes the start idempotent.

The status moves on with time: `SUBMITTED` for two seconds, `IN_PROGRESS` until four, then `COMPLETED`, or `FAILED` with a `Message` when the input could not be read or held no files. `Stop…Job` on a running job leaves it `STOPPED`. An unknown `JobId` is a `ResourceNotFoundException`. `List…Jobs` filters by `JobName`, `JobStatus` and a submit-time window, newest first.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_COMPREHEND_MEDICAL_ENABLED` | `true` | Enable or disable the service |

For the upstream API shape, see the [Amazon Comprehend Medical API Reference](https://docs.aws.amazon.com/comprehend-medical/latest/api/welcome.html).
