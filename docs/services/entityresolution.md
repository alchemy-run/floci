# AWS Entity Resolution

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/` with the Entity Resolution paths (`/schemas`, `/matchingworkflows`, `/idnamespaces`, `/idmappingworkflows`, `/policies`, `/providerservices`, `/tags`), signed for `entityresolution`.

Floci runs Entity Resolution over data in floci: matching jobs read their workflows' Glue tables from S3, match the
records and write match IDs to S3; ID mapping jobs map records between namespaces; and real-time matching works on
the matches jobs found.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AddPolicyStatement` | Add a statement to a resource policy |
| `BatchDeleteUniqueId` | Remove records from a workflow's index |
| `CreateIdMappingWorkflow` | Create an ID mapping workflow |
| `CreateIdNamespace` | Create an ID namespace |
| `CreateMatchingWorkflow` | Create a matching workflow |
| `CreateSchemaMapping` | Create a schema mapping |
| `DeleteIdMappingWorkflow` | Delete an ID mapping workflow |
| `DeleteIdNamespace` | Delete an unused ID namespace |
| `DeleteMatchingWorkflow` | Delete a matching workflow |
| `DeletePolicyStatement` | Remove a statement from a resource policy |
| `DeleteSchemaMapping` | Delete an unused schema mapping |
| `GenerateMatchId` | Match a record in real time and index it |
| `GetIdMappingJob` | Describe an ID mapping job |
| `GetIdMappingWorkflow` | Describe an ID mapping workflow |
| `GetIdNamespace` | Describe an ID namespace |
| `GetMatchId` | Look a record's match up in the index |
| `GetMatchingJob` | Describe a matching job |
| `GetMatchingWorkflow` | Describe a matching workflow |
| `GetPolicy` | Read a resource policy |
| `GetProviderService` | Describe a provider service (floci has none) |
| `GetSchemaMapping` | Describe a schema mapping |
| `ListIdMappingJobs` | List an ID mapping workflow's jobs |
| `ListIdMappingWorkflows` | List ID mapping workflows |
| `ListIdNamespaces` | List ID namespaces |
| `ListMatchingJobs` | List a matching workflow's jobs |
| `ListMatchingWorkflows` | List matching workflows |
| `ListProviderServices` | List provider services (floci has none) |
| `ListSchemaMappings` | List schema mappings |
| `ListTagsForResource` | List a resource's tags |
| `PutPolicy` | Replace a resource policy |
| `StartIdMappingJob` | Run an ID mapping job |
| `StartMatchingJob` | Run a matching job over the workflow's inputs |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
| `UpdateIdMappingWorkflow` | Update an ID mapping workflow |
| `UpdateIdNamespace` | Update an ID namespace |
| `UpdateMatchingWorkflow` | Update a matching workflow |
| `UpdateSchemaMapping` | Update an unused schema mapping |
<!-- floci:actions:end -->

## Schemas and workflows

A schema mapping has one `UNIQUE_ID` field, and its match keys gather fields of one type. A schema mapping a
workflow or ID namespace uses cannot be updated or deleted, and an ID namespace an ID mapping workflow uses
cannot be deleted. Deleting a resource that does not exist succeeds, as AWS has it.

A workflow's input sources are Glue tables, whose columns must include the schema's fields; its output attributes
must be schema fields, and its rules may only name the schemas' match keys. Its role must exist and trust
`entityresolution.amazonaws.com`, or the request is refused with `AccessDeniedException` (`Exception in assuming
the passed role`). With IAM enforcement on, the role's policies must also allow `glue:GetTable` on the tables,
`s3:GetObject` on their data and `s3:PutObject` on the outputs. Floci offers no third-party provider services, so
PROVIDER workflows are refused.

## Matching

Records are normalized by attribute type (email addresses lowercased, phone numbers reduced to digits, names and
addresses lowercased without punctuation) and linked transitively into matches:

- **Rule matching** links records that agree on all the match keys of a rule; the earliest rule that links a
  match names it. `ONE_TO_ONE` compares a key with the same key, `MANY_TO_MANY` with every key of its type.
- **Rule conditions** are expressions over match keys: `EXACT(k)`, `EXACTMANYTOMANY(k)`, `k = k`,
  `LEVENSHTEIN(k, distance)`, `COSINE(k, similarity)` and `SOUNDEX(k)`, joined by `AND` and `OR`.
- **ML matching**, which floci approximates, links records whose shared keys average at least 0.85 Jaro-Winkler
  similarity, and reports that average as `ConfidenceLevel`.

A matching job reads every object under its tables' locations (delimited text with the table's delimiter, quote and
`skip.header.line.count`, or JSON lines) and writes `InputSourceARN,RecordId,MatchID,MatchRule` and the output
attributes (SHA-256 when `hashed`) to `<outputS3Path>/<workflow>/<jobId>/success/run-0-part-00000.csv`; records with
no or a repeated unique ID go to `error/`. Its matches become the workflow's record index, and a later job keeps a
match's ID. `GenerateMatchId` matches a record against the index (unless `EVENTUAL_NO_LOOKUP`) and adds it,
`GetMatchId` looks a record up, and `BatchDeleteUniqueId` removes records, reporting a record left alone in its
match as disconnected.

An ID mapping job maps each record of its SOURCE to the first TARGET record a rule matches, the rules coming from
the workflow or from the namespace its rule definition type names; a TARGET namespace may read a matching
workflow's matches. It writes `SourceInputSourceARN,SourceRecordId,TargetInputSourceARN,TargetRecordId,
TargetMatchID,MatchRule` rows to its output.

Jobs are `QUEUED`, then `RUNNING`, then `SUCCEEDED` or `FAILED` (with `errorDetails`), a transition apart; a
workflow runs one job at a time. Workflows and ID namespaces take resource policies, versioned by a token.

## Configuration

```yaml
floci:
  services:
    entityresolution:
      enabled: true
      transition-millis: 2000   # how long a job stays QUEUED, and then RUNNING
```
