# AWS B2B Data Interchange

**Protocol:** JSON 1.0
**Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: B2BI.<Operation>`, signed for `b2bi`.

Floci runs B2B Data Interchange's X12 processing: it parses X12 documents into the service's JSON and XML representations, maps them with JSONata or XSLT, converts them back into X12, and processes the files trading partners put in S3.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateCapability` | Create an EDI capability over a transformer and S3 locations |
| `CreatePartnership` | Create a partnership, its trading partner ID and folders |
| `CreateProfile` | Create a profile, with its log groups when logging is enabled |
| `CreateStarterMappingTemplate` | A transaction set's template, or a sample's parse |
| `CreateTransformer` | Create an inactive transformer |
| `DeleteCapability` | Delete an unused capability |
| `DeletePartnership` | Delete a partnership |
| `DeleteProfile` | Delete a profile without partnerships |
| `DeleteTransformer` | Delete a transformer no capability uses |
| `GenerateMapping` | Derive a template from sample input and output |
| `GetCapability` | Describe a capability |
| `GetPartnership` | Describe a partnership |
| `GetProfile` | Describe a profile |
| `GetTransformer` | Describe a transformer |
| `GetTransformerJob` | A job's status and output files |
| `ListCapabilities` | Page through capabilities |
| `ListPartnerships` | Page through partnerships, by profile |
| `ListProfiles` | Page through profiles |
| `ListTagsForResource` | List tags |
| `ListTransformers` | Page through transformers |
| `StartTransformerJob` | Transform one S3 object |
| `TagResource` | Tag a resource |
| `TestConversion` | Write JSON or XML as X12 |
| `TestMapping` | Apply a JSONata or XSLT template |
| `TestParsing` | Parse X12 from S3 into JSON or XML |
| `UntagResource` | Remove tags |
| `UpdateCapability` | Change a capability |
| `UpdatePartnership` | Change a partnership's name, capabilities and options |
| `UpdateProfile` | Change a profile |
| `UpdateTransformer` | Change or activate an inactive transformer |
<!-- floci:actions:end -->

## X12 parsing and conversion

`TestParsing` reads an X12 file from S3 and returns B2BI's JSON or XML representation of it. The JSON nests `interchanges`, `functional_groups`, `transactions` and `segments`. Elements are keyed `BEG_03`, composites `REF_04_01`, and each loop iteration is its own `{"N1-4000_loop": [...]}`; empty elements are left out. The XML is an `ediroot` document. Both match what AWS returns for the same file.

Segments are filed into the transaction set's loops using schemas vendored for about 40 widely used transaction sets (180, 204, 210, 214, 810, 820, 846, 850, 855, 856, 860, 940, 945, 990, 997 and more), in versions 4010 and 5010. Other transaction sets parse with their segments listed without loops, with a validation message that says so.

A document of another transaction set or version fails as AWS fails it (`The EDI file could not be parsed. EDI transaction set does not match 810`). Structure problems are reported in `validationMessages`:

- a segment outside the transaction set
- SE, GE and IEA counts or control numbers that don't match
- a failed element-length or mandatory-element rule from `advancedOptions`

`splitBy: TRANSACTION` also returns each transaction as its own document.

`TestConversion` writes either representation as X12. SE, GE and IEA counts are recomputed, and the result is parsed again for validation messages. `TestMapping` applies a JSONata template to JSON or an XSLT template to XML. As on AWS, the JSONata result comes back as a JSON string of the mapped document.

`CreateStarterMappingTemplate` returns the transaction set's full structure with placeholder values, or the parsed sample when `outputSampleLocation` names one. A JSONata template is returned as JSON, and an XSLT template as a stylesheet that writes the XML.

`GenerateMapping` uses no model in Floci. Each value of the sample output is looked up in the sample input: found values are selected by their path (arrays map element by element), and the rest are written as literals. `mappingAccuracy` is the share of output values the template reproduces.

## Resources

Profiles, transformers, capabilities and partnerships have AWS's IDs (`p-`, `tr-`, `ca-`, `ps-`, and `tp-` for trading partners) and ARNs, and list-form tags. A `clientToken` makes a create idempotent.

- **Profiles.** A profile with logging `ENABLED` creates `/aws/vendedlogs/b2bi/default` and `/aws/vendedlogs/b2bi/profile/<profileId>`. Deleting the profile leaves both log groups.
- **Transformers.** A transformer takes either the original shape (`fileFormat`, `mappingTemplate`, `ediType`) or the current one: an `inputConversion` (inbound) or an `outputConversion` (outbound), with a `mapping`. Its template must compile. It starts inactive. An active transformer rejects every update (`An active Transformer cannot be updated`), and only an active one runs jobs.
- **Capabilities.** A capability's transformer and S3 buckets must exist.
- **Partnerships.** A partnership's profile and capabilities must exist.

Deleting a profile that partnerships use, a capability a partnership uses, or a transformer a capability uses fails with `ConflictException`.

## Transformations

`StartTransformerJob` transforms one S3 object, and `GetTransformerJob` reports it as `running`, then `succeeded` or `failed`, with its output files:

- An inbound transformer parses X12 and maps the result to `<output key>/<name>.json` (or `.xml`). With split by transaction, it writes `<name>_<n>.json`.
- An outbound transformer maps JSON or XML into the representation and writes it as X12 to `<output key>/<name>.x12`.

B2BI also processes partner files on its own. Creating a partnership creates the trading partner's folder in each of its capabilities' input locations, at `<input key>/<tradingPartnerId>/`. A file put in that folder is transformed into `<output key>/<tradingPartnerId>/`.

For an inbound capability, the partnership's acknowledgment options decide what else is written to `<output key>/<tradingPartnerId>/acknowledgments/`:

- a 997, or a 999 for 5010
- a TA1

For an outbound capability, the X12 is written with the partnership's outbound options:

- interchange and group headers
- delimiters
- control numbers counting up from the starting numbers
- the GS05 time format
- line wrapping

The X12 is validated unless `validateEdi` is false.

Every transformation sends an `aws.b2bi` event to the default event bus, `Transformation Completed` or `Transformation Failed`. The event carries the job ID, trading partner, X12 transaction set and version, the input and output S3 attributes, and the validation status, plus split attributes when the input was split. An acknowledgment sends `Acknowledgement Completed` or `Acknowledgement Failed`. Each transformation is also logged to `/aws/vendedlogs/b2bi/transformers`.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_B2BI_ENABLED` | `true` | Enable or disable B2B Data Interchange |

For the upstream API shape, see the [AWS B2B Data Interchange API Reference](https://docs.aws.amazon.com/b2bi/latest/APIReference/Welcome.html).
