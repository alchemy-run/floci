# Amazon Polly

**Protocol:** REST JSON, signed with `polly`
**Endpoint:** `http://localhost:4566/v1/...`

Floci emulates Amazon Polly: pronunciation lexicons, the voice catalog, speech synthesis (real-time, as tasks that write to S3, and over a synthesis stream), and speech marks. The audio is well-formed silence as long as the text takes to say, so players, pipelines and size checks work without a speech engine.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `PutLexicon` | Store or replace a PLS lexicon after checking it |
| `GetLexicon` | Return a lexicon's content and attributes |
| `DeleteLexicon` | Delete a lexicon |
| `ListLexicons` | Page through the Region's lexicons |
| `DescribeVoices` | List voices, filtered by engine and language |
| `SynthesizeSpeech` | Synthesize text or SSML to audio or speech marks |
| `StartSpeechSynthesisTask` | Synthesize to an S3 object as a task |
| `GetSpeechSynthesisTask` | Return a synthesis task |
| `ListSpeechSynthesisTasks` | Page through synthesis tasks, newest first, filtered by status |
| `StartSpeechSynthesisStream` | Answer a stream of text events with audio events |
<!-- floci:actions:end -->

## Lexicons

`PutLexicon` stores a [PLS](https://www.w3.org/TR/pronunciation-lexicon/) document and checks it as Polly does. It must be well-formed XML with a `<lexicon>` root (`InvalidLexiconException`). Its `alphabet` must be `ipa`, `x-sampa` or one of the Amazon alphabets (`UnsupportedPlsAlphabetException`), and its `xml:lang` a language Polly speaks (`UnsupportedPlsLanguageException`). Every lexeme needs a grapheme and a phoneme or alias of at most 100 characters (`MaxLexemeLengthExceededException`). The whole document is at most 4,000 characters (`LexiconSizeExceededException`), and a Region holds at most 100 lexicons (`MaxLexiconsNumberExceededException`). `GetLexicon` returns the content with its attributes: alphabet, language, lexeme count, size and ARN. A missing lexicon is a `LexiconNotFoundException` (HTTP 404).

## Voices

`DescribeVoices` lists Polly's voices (every `VoiceId` in the API model) with their gender, language and supported engines (`standard`, `neural`, `long-form`, `generative`), filtered by `Engine` and `LanguageCode`. `IncludeAdditionalLanguageCodes` also matches voices that speak the language as an additional one.

## Synthesis

`SynthesizeSpeech` checks the request before it synthesizes:

- The voice must exist (`ValidationException`) and support the engine, which defaults to `standard` (`EngineNotSupportedException`).
- `LanguageCode` must be the voice's language or one of its additional languages (`LanguageNotSupportedException`).
- `SampleRate` must suit the format: 8000, 16000, 22050 or 24000 for `mp3` and the Ogg formats, 8000 or 16000 for `pcm`, and 8000 for `mulaw` and `alaw` (`InvalidSampleRateException`). It defaults to 22050 (`standard`) or 24000 for the other engines, 16000 for `pcm` and 8000 for the companded formats.
- Speech marks need the `json` format (`MarksNotSupportedForFormatException`), and `ssml` marks need SSML input (`SsmlMarksNotSupportedForTextTypeException`).
- SSML must be a well-formed `<speak>` document (`InvalidSsmlException`), and every lexicon named must exist.
- The billed characters (the text an SSML document speaks) are at most 3,000, and the whole input at most 6,000 (`TextLengthExceededException`).

The answer carries `x-amzn-RequestCharacters` and audio about 65 ms long per spoken character, as:

- `mp3`: mono MPEG-2 Layer III frames (MPEG-2.5 at 8 kHz).
- `pcm`: 16-bit little-endian samples.
- `mulaw` and `alaw`: 8-bit samples.
- `ogg_vorbis` (22,050 Hz) and `ogg_opus` (48 kHz): one-second clips chained into one Ogg stream, each clip a logical stream of its own.

With the `json` format the answer is speech marks, one JSON object per line. `word` and `sentence` marks carry the byte offsets of their text in the request and a time matching that length. A `viseme` mark follows each word's first sound, and an `ssml` mark reports each `<mark name="…"/>`.

## Synthesis tasks

`StartSpeechSynthesisTask` checks the request the same way (with a 100,000-character limit). It writes the output to `<OutputS3BucketName>/<OutputS3KeyPrefix><TaskId>.<mp3|ogg|pcm|marks>` and answers with the task. A bucket that doesn't exist is an `InvalidS3BucketException`, a malformed key prefix an `InvalidS3KeyException`, and an `SnsTopicArn` that is not an SNS topic ARN an `InvalidSnsTopicArnException`. The task is `scheduled` for a second, `inProgress` for the next, then `completed`. A configured SNS topic is notified with the task's `taskId`, `taskStatus` and `outputUri`. `ListSpeechSynthesisTasks` lists the newest first and filters by `Status`. An unknown task is a `SynthesisTaskNotFoundException`, and a malformed task id an `InvalidTaskIdException`.

## Synthesis streams

`StartSpeechSynthesisStream` takes the engine, voice, format, sample rate and lexicons as `x-amzn-*` headers, checked as for `SynthesizeSpeech`. It reads `TextEvent`s from the `application/vnd.amazon.eventstream` request body. Each complete sentence (or the buffered text, when `FlushStreamConfiguration.Force` is set) is answered with `AudioEvent`s of up to 8 KiB. A `StreamClosedEvent` with the `RequestCharacters` follows the `CloseStreamEvent`. Floci reads the request body to its end before it answers, which is how the stream arrives over HTTP/1.1. A failure while synthesizing is reported as an exception event.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_POLLY_ENABLED` | `true` | Enable or disable Polly |

For the upstream API shape, see the [Amazon Polly API Reference](https://docs.aws.amazon.com/polly/latest/dg/API_Reference.html).
