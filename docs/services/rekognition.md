# Rekognition

**Protocol:** JSON 1.1 (`X-Amz-Target: RekognitionService.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `DetectLabels` | Detect objects, scenes, and concepts in an image |
| `DetectFaces` | Detect faces in an image and their attributes |
| `DetectText` | Detect text in an image |
| `CompareFaces` | Compare a face in a source image with faces in a target image |
| `DetectModerationLabels` | Detect unsafe or inappropriate content in an image |
| `DetectProtectiveEquipment` | Detect persons and the protective equipment they wear |
| `RecognizeCelebrities` | Recognize celebrities in an image |
| `GetCelebrityInfo` | Get a celebrity's name and links by id |
| `CreateCollection` | Create a face collection |
| `DescribeCollection` | Get a collection's face and user counts |
| `ListCollections` | List collections |
| `DeleteCollection` | Delete a collection |
| `IndexFaces` | Index the faces in an image into a collection |
| `ListFaces` | List a collection's faces |
| `DeleteFaces` | Delete faces from a collection |
| `SearchFaces` | Search a collection for faces like a stored face |
| `SearchFacesByImage` | Search a collection for faces like the largest face in an image |
| `CreateUser` | Create a user in a collection |
| `DeleteUser` | Delete a user |
| `ListUsers` | List a collection's users |
| `AssociateFaces` | Associate faces with a user |
| `DisassociateFaces` | Disassociate faces from a user |
| `SearchUsers` | Search a collection for users like a user or face |
| `SearchUsersByImage` | Search a collection for users like the largest face in an image |
| `CreateFaceLivenessSession` | Create a Face Liveness session |
| `GetFaceLivenessSessionResults` | Get a Face Liveness session's status |
| `CreateProject` | Create a Custom Labels project |
| `DeleteProject` | Delete a project |
| `DescribeProjects` | List projects |
| `DescribeProjectVersions` | List a project's model versions |
| `DetectCustomLabels` | Detect custom labels with a trained model |
| `StartProjectVersion` | Start a model version |
| `StopProjectVersion` | Stop a model version |
| `CreateStreamProcessor` | Create a stream processor |
| `DescribeStreamProcessor` | Get a stream processor |
| `ListStreamProcessors` | List stream processors |
| `StartStreamProcessor` | Start a stream processor |
| `StopStreamProcessor` | Stop a stream processor |
| `DeleteStreamProcessor` | Delete a stream processor |
| `TagResource` | Tag a collection, project, or stream processor |
| `UntagResource` | Remove tags |
| `ListTagsForResource` | List tags |
| `StartCelebrityRecognition` | Start a stored-video celebrity recognition job |
| `StartContentModeration` | Start a stored-video content moderation job |
| `StartFaceDetection` | Start a stored-video face detection job |
| `StartFaceSearch` | Start a stored-video face search job |
| `StartLabelDetection` | Start a stored-video label detection job |
| `StartPersonTracking` | Start a stored-video person tracking job |
| `StartSegmentDetection` | Start a stored-video segment detection job |
| `StartTextDetection` | Start a stored-video text detection job |
| `GetCelebrityRecognition` | Get a stored-video celebrity recognition job's results |
| `GetContentModeration` | Get a stored-video content moderation job's results |
| `GetFaceDetection` | Get a stored-video face detection job's results |
| `GetFaceSearch` | Get a stored-video face search job's results |
| `GetLabelDetection` | Get a stored-video label detection job's results |
| `GetPersonTracking` | Get a stored-video person tracking job's results |
| `GetSegmentDetection` | Get a stored-video segment detection job's results |
| `GetTextDetection` | Get a stored-video text detection job's results |
| `StartMediaAnalysisJob` | Start a media analysis job over a manifest |
| `GetMediaAnalysisJob` | Get a media analysis job |
| `ListMediaAnalysisJobs` | List media analysis jobs |
<!-- floci:actions:end -->

## Emulation Behavior

- **No image analysis:** Floci reads no pixels, so what an image shows never changes
  the result unless the mock config names it (see "Mock Responses").
- **Images are checked as Rekognition checks them:** an image Rekognition refuses is
  refused here, with Rekognition's error, before any response. Measured against
  Rekognition in us-east-2, for each operation and for both `Bytes` and `S3Object`:
    - Anything but a JPEG or PNG (WebP, GIF, other bytes) is refused with
      `InvalidImageFormatException`, and so is a cut-off or hollow file the way Rekognition
      judges it: a PNG without an `IDAT` or `IEND` chunk, or a JPEG cut inside its headers or
      whose frame or scan header is shorter than its component count requires. A JPEG whose
      headers are complete through the start-of-scan header is accepted however much of the
      image data after it is missing.
    - A JPEG with stray bytes between its segments is accepted, as Rekognition accepts it.
    - `Bytes` that are not base64 are refused with `SerializationException`; base64
      without its `=` padding is accepted.
    - `Bytes` that are empty or longer than 5,242,880 bytes are refused with
      `ValidationException`, naming the member (`image.bytes`, `sourceImage.bytes`,
      `targetImage.bytes`). For `CompareFaces` both images' lengths are checked before
      either image is read, and every violation is reported in one error.
    - An `S3Object` that cannot be read (a missing bucket or object, or a bucket in
      another region than the request) is refused with `InvalidS3ObjectException`. A bucket
      created without a location constraint is in the default region. The object is read
      from Floci's S3, so it has to be uploaded first; only as much of it as its format
      needs is read, so a large object is not loaded into memory.
    - A side over 10,000 pixels is refused with `ImageTooLargeException` by
      `DetectModerationLabels` and `DetectLabels`, and with
      `InvalidImageFormatException` by the other operations.
    - Two documented limits are not enforced, because Rekognition does not enforce them:
      images under 80 pixels are accepted, and an S3 object over 15 MB is accepted.
- **One canned label:** `DetectLabels` reports a single label, as every photo has one.
- **Nothing else is detected:** `DetectText`, `DetectFaces`, `DetectModerationLabels`,
  `DetectProtectiveEquipment`, and `RecognizeCelebrities` report nothing found rather
  than fabricating text, biometric attributes, or a moderation flag for content that
  was never analyzed. `CompareFaces` refuses a source image without a face with
  `InvalidParameterException`, as Rekognition does, and `GetCelebrityInfo` knows no
  celebrity unless the mock config names its id.
- **Face collections:** collections, their faces and users, and tags are stored.
  `IndexFaces` indexes the `FaceDetails` of the image's `DetectFaces` mock (nothing
  without one); `SearchFacesByImage` and `SearchUsersByImage` refuse an image without a
  face with `InvalidParameterException`. Unknown face ids in `DeleteFaces`,
  `AssociateFaces`, and `DisassociateFaces` are reported per face (`FACE_NOT_FOUND`),
  not as an error.
- **Face Liveness:** a session is `CREATED` until it expires three minutes after
  creation; Floci runs no liveness check.
- **Stored video and media analysis:** starting a job checks that the S3 object is
  readable (`InvalidS3ObjectException`), that a face search's collection exists, and that
  a segment detection names its segment types. A job reports `IN_PROGRESS` for two
  seconds, then `SUCCEEDED` with an empty result list. The same `ClientRequestToken`
  returns the same job. No SNS notification is sent.
- **Stream processors:** create, describe, list, start, stop, and delete, with tags;
  starting moves one to `RUNNING`, stopping back to `STOPPED`.
- **Custom Labels:** projects can be created, listed, and deleted, but never trained, so
  every project version ARN is unknown (`ResourceNotFoundException`).
- **Real input validation:** `Image` (or `SourceImage`/`TargetImage` for
  `CompareFaces`) is required, must be a JSON structure, and must specify
  `Bytes` (string-typed) or `S3Object` (structure-typed) — matching AWS's own
  modeling and rejecting wrong JSON types as `SerializationException`. This is
  protocol compatibility, not content analysis.
- **Both `Bytes` and `S3Object`:** supplying both is accepted rather than rejected,
  since nothing in the Rekognition API model declares that combination invalid. The
  bytes are what is checked.
- **Out of scope:** model training (`Datasets`, `CreateProjectVersion`).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_REKOGNITION_ENABLED` | `true` | Enable or disable the service |
| `AI_MOCK_CONFIG` | unset | Path to a shared mock-response config file — see "Mock Responses" below |

## Mock Responses

To exercise application logic that branches on detection results, point `AI_MOCK_CONFIG` at a JSON file shared
across Rekognition, Textract, and Comprehend:

```json
{
  "rekognition": {
    "my-bucket/cat.jpg": {
      "DetectLabels": {
        "Labels": [{ "Name": "Cat", "Confidence": 97.2 }, { "Name": "Animal", "Confidence": 98.1 }],
        "LabelModelVersion": "1.0"
      }
    }
  }
}
```

The object named by the key has to exist in Floci's S3 and be an image Rekognition
accepts: the image is checked before the mock is looked up, so a call naming an object
that was never uploaded fails with `InvalidS3ObjectException`, as it does on AWS.

The lookup key is **`"<Bucket>/<Name>"`** from `Image.S3Object` (or `SourceImage.S3Object`
for `CompareFaces` — `TargetImage` has no independent key in this scheme). A `Bytes`-backed
image is keyed by **`"sha256:<hex>"`** of its decoded bytes, as Textract documents are, so a mock
can answer for a fixed image wherever it comes from. The file is re-read
when its modification time changes, so it can be edited without restarting the emulator. A
missing file, an unset `AI_MOCK_CONFIG`, or no matching entry all fall back to the defaults
above. A `DetectFaces` mock also gives `IndexFaces` and the by-image searches their faces, and
`GetCelebrityInfo` is keyed by the celebrity id.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws rekognition detect-labels \
  --image '{"S3Object":{"Bucket":"my-bucket","Name":"photo.jpg"}}' \
  --endpoint-url $AWS_ENDPOINT_URL

aws rekognition detect-text \
  --image '{"S3Object":{"Bucket":"my-bucket","Name":"photo.jpg"}}' \
  --endpoint-url $AWS_ENDPOINT_URL

aws rekognition compare-faces \
  --source-image '{"S3Object":{"Bucket":"my-bucket","Name":"source.jpg"}}' \
  --target-image '{"S3Object":{"Bucket":"my-bucket","Name":"target.jpg"}}' \
  --endpoint-url $AWS_ENDPOINT_URL
```

## SDK Example (Java)

```java
RekognitionClient rekognition = RekognitionClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();

DetectLabelsResponse response = rekognition.detectLabels(req -> req
    .image(img -> img.s3Object(s3 -> s3.bucket("my-bucket").name("photo.jpg"))));

System.out.println("Labels: " + response.labels());
```
