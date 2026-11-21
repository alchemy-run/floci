# Amazon Lex V2

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/`, signed for `lex` (the model building and runtime APIs); exports, artifacts
and upload URLs under `/_floci/lexv2/files/`.

Floci builds and runs Lex V2 bots: the model building API defines bots, locales, intents, slots and slot types, a
build turns a locale into a model, and the runtime converses with it through RecognizeText, RecognizeUtterance, the
session operations and StartConversation, invoking the alias's Lambda code hooks along the way.

## Bots, locales and builds

Bots are `Creating` for a transition, then `Available`; updates go `Updating`, versions `Versioning`, and deletes
`Deleting`. Each bot gets the `TestBotAlias` (`TSTALIASID`) on its DRAFT version. A locale starts with the
`FallbackIntent` (`FALLBCKINT`) and is `NotBuilt` until `BuildBotLocale`, which is `Building` for the build time and
then `Built`, or `Failed` with its reasons: an intent with no sample utterances, an utterance naming a slot the intent
lacks, a slot whose slot type doesn't exist, a required slot without a prompt, a custom slot type without values, or
no intent but the fallback. Any change to the DRAFT locale's intents, slots, slot types or custom vocabulary leaves
it `NotBuilt`; the runtime keeps conversing with the last build.

Intents keep their sample utterances, code hook settings, confirmation, closing and initial responses, contexts and
Kendra or QnA configuration; creating a slot adds it to the intent's slot priorities. Slot types are custom (values
and synonyms with a resolution strategy and regex filter), extended `AMAZON.AlphaNumeric`, grammar or composite.
Built-in intents and slot types are listed for each locale. `CreateBotVersion` snapshots built locales into a
numbered version; aliases point at a version, with locale settings (each with its Lambda code hook), conversation
logs and sentiment analysis. Bot networks name member bots' aliases and route each turn to the member whose intent
matches best. Replicas copy a bot's versions and aliases to another Region, where the runtime serves them. Bots,
aliases and test sets take tags; bots and aliases take resource policies, edited whole or by statement under a
revision ID.

## Conversations

Each turn classifies the user's words against the built model. Words that match a sample utterance, with each
`{Slot}` filled by words its slot type resolves, score 1.0 and fill those slots; otherwise intents score by how
closely the words resemble their utterances, and a score under the locale's confidence threshold goes to the
`FallbackIntent` (or a Kendra or QnA intent when the locale has one). Intents with input contexts are candidates only
while those contexts are active. Interpretations list the chosen intent first, then the fallback, then the next best.

The intent's flow then runs: its initial response, the dialog code hook, elicitation of each empty required slot in
priority order (with retries up to the prompt's maximum, and the capture and failure responses), confirmation
(`Confirmed` continues; `Denied` plays the declination response and fails the intent), fulfillment and the closing
response, following conditional branches (`{slot}`, `[sessionAttribute]` and `((requestAttribute))` comparisons with
`AND`, `OR`, `NOT`, `fn.COUNT`, `fn.IS_SET` and `fn.IS_EMPTY`) and next steps. With a fulfillment code hook the alias
locale's Lambda function is invoked with the Lex V2 1.0 event and its response decides the intent's state and
messages; without one the intent ends `ReadyForFulfillment`. Messages fill `{slot}`, `[attribute]` and
`((attribute))` placeholders. Output contexts become active when an intent closes. Built-in slot types resolve their
usual spoken and written forms: numbers, dates, times, durations, percentages, confirmations, emails, phone numbers,
UK postcodes and alphanumerics; names, places and free-form input take the words as said.

Sessions expire after the bot's idle session TTL. `PutSession` replaces a session's state, or with a `Delegate`
dialog action lets the bot decide the next step; `GetSession` and `DeleteSession` read and end it.
`RecognizeUtterance` and `PutSession` return the rest of their response in gzip-compressed, base64-encoded
`x-amz-lex-*` headers. Floci runs no speech recognition: speech input's words come from the `lex` entry of
`AI_MOCK_CONFIG` for the audio's SHA-256 (`{"lex": {"<sha256>": {"RecognizeUtterance": {"inputTranscript": "..."}}}}`),
and speech responses are Polly's audio for the locale's voice (a locale without a voice answers only in text).
`StartConversation` streams over HTTP/2 or chunked HTTP/1.1: text, DTMF digits (a turn ends at `#`) and 8 kHz PCM
audio (a turn ends at a chunk of silence), answered with transcript, intent result, text and audio events, with
heartbeats while idle.

When the alias turns on text conversation logs, each turn is written to the log group as Lex's log record; audio logs
write speech input to the bucket.

## Export, import and the Test Workbench

Bots and locales export as Lex's JSON archive (`Manifest.json`, `Bot.json` and each locale's `BotLocale.json`,
`Intent.json`, `Slot.json` and `SlotType.json`, which name one another), custom vocabularies as TSV and test sets as
CSV. `CreateUploadUrl` gives a URL to `PUT` an archive to; `StartImport` reads it into a new bot, a locale of an
existing bot, or a custom vocabulary, under `FailOnConflict`, `Overwrite` or `Append`. Archive passwords are accepted
and not applied.

Test sets import from the Lex test set CSV in S3 (`Line #`, `Conversation #`, `Source`, `Input`,
`Expected Output Intent`, `Expected Output Slot N` as `Slot = value`, context tags, request and session attributes)
or generate from a bot's recorded conversations, and are written to their storage location. A test execution replays
each conversation against an alias and compares intents, slots, contexts and agent prompts; its results are listed
overall, by conversation, by intent, by slot and by utterance, and its artifacts download as CSV. Discrepancy reports
name the test set's intents and slots the bot locale lacks.

## Analytics and design help

Every runtime turn is kept for analytics: session, intent, intent stage and utterance metrics (binned by hour or day,
grouped and filtered), intent paths, and session and utterance data, as well as aggregated utterances (hit and missed
counts in a relative window). `DeleteUtterances` removes a bot's, locale's or session's turns.

Bot recommendations read Lex-format transcripts from S3 and group what customers first ask into recommended intents,
with an exported locale archive and the associated transcripts. Descriptive bot building and sample utterance
generation (with the locale's generative AI build-time settings turned on) draft intents from a description and new
utterances from an intent's own. The bot analyzer reviews a locale: intents with few or shared utterances, required
slots without prompts, slots no utterance mentions and unused slot types.

## Configuration

```yaml
floci:
  services:
    lexv2:
      enabled: true
      transition-millis: 2000     # how long statuses take to settle
      build-millis: 3000          # how long a locale build takes
      max-bots: 10000
      max-aliases-per-bot: 100
      max-intents-per-locale: 1000
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `BatchCreateCustomVocabularyItem` | Add phrases to a locale's custom vocabulary |
| `BatchDeleteCustomVocabularyItem` | Remove custom vocabulary items |
| `BatchUpdateCustomVocabularyItem` | Change custom vocabulary items |
| `BuildBotLocale` | Build a DRAFT locale into the model the runtime uses |
| `CreateBot` | Create a bot and its test alias |
| `CreateBotAlias` | Create an alias of a bot version |
| `CreateBotLocale` | Add a locale, with its fallback intent, to the DRAFT version |
| `CreateBotReplica` | Replicate a bot's versions and aliases to another Region |
| `CreateBotVersion` | Snapshot built locales into a numbered version |
| `CreateExport` | Export a bot, locale, custom vocabulary or test set |
| `CreateIntent` | Create an intent in a DRAFT locale |
| `CreateResourcePolicy` | Set a bot's or alias's resource policy |
| `CreateResourcePolicyStatement` | Add a statement to a resource policy |
| `CreateSlot` | Create a slot in an intent |
| `CreateSlotType` | Create a custom, extended, grammar or composite slot type |
| `CreateTestSetDiscrepancyReport` | Check a test set's intents and slots against a bot locale |
| `CreateUploadUrl` | A URL to upload an import's archive to |
| `DeleteBot` | Delete a bot and everything in it |
| `DeleteBotAlias` | Delete an alias |
| `DeleteBotAnalyzerRecommendation` | Delete a bot analysis |
| `DeleteBotLocale` | Delete a DRAFT locale |
| `DeleteBotReplica` | Delete a bot's replica in a Region |
| `DeleteBotVersion` | Delete a numbered version |
| `DeleteCustomVocabulary` | Delete a locale's custom vocabulary |
| `DeleteExport` | Delete an export |
| `DeleteImport` | Delete an import |
| `DeleteIntent` | Delete an intent and its slots |
| `DeleteResourcePolicy` | Delete a resource policy |
| `DeleteResourcePolicyStatement` | Remove a statement from a resource policy |
| `DeleteSlot` | Delete a slot |
| `DeleteSlotType` | Delete a slot type |
| `DeleteTestSet` | Delete a test set |
| `DeleteUtterances` | Delete a bot's, locale's or session's recorded utterances |
| `DescribeBot` | Describe a bot |
| `DescribeBotAlias` | Describe an alias, with its version history |
| `DescribeBotAnalyzerRecommendation` | A bot analysis's issues and proposed fixes |
| `DescribeBotLocale` | Describe a locale, with its build status and counts |
| `DescribeBotRecommendation` | Describe a bot recommendation and its results |
| `DescribeBotReplica` | Describe a bot's replica |
| `DescribeBotResourceGeneration` | Describe a generation from a description |
| `DescribeBotVersion` | Describe a numbered version |
| `DescribeCustomVocabularyMetadata` | Describe a locale's custom vocabulary |
| `DescribeExport` | Describe an export, with its download URL |
| `DescribeImport` | Describe an import |
| `DescribeIntent` | Describe an intent |
| `DescribeResourcePolicy` | Read a resource policy |
| `DescribeSlot` | Describe a slot |
| `DescribeSlotType` | Describe a slot type |
| `DescribeTestExecution` | Describe a test execution |
| `DescribeTestSet` | Describe a test set |
| `DescribeTestSetDiscrepancyReport` | A discrepancy report's intent and slot errors |
| `DescribeTestSetGeneration` | Describe a test set generation |
| `GenerateBotElement` | Draft new sample utterances for an intent |
| `GetTestExecutionArtifactsUrl` | A URL to download a test execution's results |
| `ListAggregatedUtterances` | Utterances with hit and missed counts in a window |
| `ListBotAliasReplicas` | List a replica's aliases |
| `ListBotAliases` | List a bot's aliases |
| `ListBotAnalyzerHistory` | List a bot's analyses |
| `ListBotLocales` | List a version's locales |
| `ListBotRecommendations` | List a locale's bot recommendations |
| `ListBotReplicas` | List a bot's replicas |
| `ListBotResourceGenerations` | List a locale's generations |
| `ListBotVersionReplicas` | List a replica's versions |
| `ListBotVersions` | List a bot's versions |
| `ListBots` | List bots |
| `ListBuiltInIntents` | List built-in intents |
| `ListBuiltInSlotTypes` | List built-in slot types |
| `ListCustomVocabularyItems` | List a custom vocabulary's items |
| `ListExports` | List exports |
| `ListImports` | List imports |
| `ListIntentMetrics` | Intent metrics from recorded conversations |
| `ListIntentPaths` | The intents conversations go to next along a path |
| `ListIntentStageMetrics` | Slot elicitation and confirmation stage metrics |
| `ListIntents` | List a locale's intents |
| `ListRecommendedIntents` | List a bot recommendation's intents |
| `ListSessionAnalyticsData` | Recorded sessions |
| `ListSessionMetrics` | Session metrics from recorded conversations |
| `ListSlotTypes` | List a locale's slot types |
| `ListSlots` | List an intent's slots |
| `ListTagsForResource` | List a bot's, alias's or test set's tags |
| `ListTestExecutionResultItems` | A test execution's results at one level |
| `ListTestExecutions` | List test executions |
| `ListTestSetRecords` | List a test set's turns |
| `ListTestSets` | List test sets |
| `ListUtteranceAnalyticsData` | Recorded utterances |
| `ListUtteranceMetrics` | Utterance metrics from recorded conversations |
| `SearchAssociatedTranscripts` | The transcripts behind recommended intents |
| `StartBotAnalyzer` | Analyze a locale's design |
| `StartBotRecommendation` | Recommend intents from transcripts in S3 |
| `StartBotResourceGeneration` | Draft a locale's intents from a description |
| `StartImport` | Import an uploaded archive or a test set CSV |
| `StartTestExecution` | Run a test set against an alias |
| `StartTestSetGeneration` | Generate a test set from recorded conversations |
| `StopBotAnalyzer` | Stop a bot analysis |
| `StopBotRecommendation` | Stop a bot recommendation |
| `TagResource` | Tag a bot, alias or test set |
| `UntagResource` | Remove tags |
| `UpdateBot` | Update a bot |
| `UpdateBotAlias` | Update an alias |
| `UpdateBotLocale` | Update a DRAFT locale's settings |
| `UpdateBotRecommendation` | Update a bot recommendation's encryption setting |
| `UpdateExport` | Update an export's password |
| `UpdateIntent` | Update an intent, with its slot priorities |
| `UpdateResourcePolicy` | Replace a resource policy |
| `UpdateSlot` | Update a slot |
| `UpdateSlotType` | Update a slot type |
| `UpdateTestSet` | Rename or redescribe a test set |
| `RecognizeText` | One turn of text with a bot alias |
| `RecognizeUtterance` | One turn of text or speech, with the result in headers |
| `StartConversation` | A conversation streamed over HTTP/2 |
| `GetSession` | Read a session |
| `PutSession` | Set a session's state |
| `DeleteSession` | End a session |
<!-- floci:actions:end -->
