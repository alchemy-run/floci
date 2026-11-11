# AWS HealthLake

**Protocol:** JSON 1.0 (`X-Amz-Target: HealthLake.<Action>`)
**Endpoint:** `POST http://localhost:4566/`, signed for `healthlake`.

Each FHIR data store is a FHIR R4 server: a HAPI FHIR container (`hapiproject/hapi:v8.12.0-2`) with its database on a
per-data-store volume. A data store is `CREATING` until its server answers, then `ACTIVE`. Its FHIR API is served at its
endpoint, `http://healthlake.<region>.localhost.floci.io:4566/datastore/<id>/r4/`. With `AWS_AUTH` a request must be
signed for `healthlake`; with `SMART_ON_FHIR` its bearer token is passed to the data store's identity provider Lambda,
which must answer `isAuthorized: true`. Without Docker, or with `floci.services.healthlake.mock: true`, floci keeps a
data store's resources itself: imports, exports and restores still work, and the endpoint answers 503.

An import reads every NDJSON object under its input URI, one resource a line, and writes each line's outcome to
`<output>/<datastore>-FHIR_IMPORT-<job>/SUCCESS/` and `FAILURE/`. An export writes a file per resource type to
`<output>/<datastore>-FHIR_EXPORT-<job>/<Type>/<Type>-0.ndjson`. A data store runs one import or export at a time. The
data access role must exist and trust `healthlake.amazonaws.com`. A `SYNTHEA` preload writes a small synthetic
population: 10 patients, each with 2 encounters and their vital signs. A data store with continuous backups can be
restored into a new one with its current resources.

The Data Transformation Agent converts C-CDA documents (Velocity templates, with HealthLake's helper library) and
CSV tables (the YAML mapping, all of its transforms, aggregations and grouped rows) into FHIR R4. A profile is an
editable draft (version 0); publishing makes an immutable version, which jobs use. `ccda-starter-kit-v1` maps patients,
problems, allergies, results and vital signs. A transformation job writes NDJSON per resource type to
`<output>/<job>/`; `StartFHIRImportJob` with a `ProfileId` converts and loads in one job; `POST /transform-data`
converts one input. Provenance adds a Device, DocumentReference and Provenance for each conversion, and drift detection
writes a coverage report. floci's agent is deterministic: it drafts a CSV mapping from a profile's sample data by
recognizing common table and column names, shows and publishes a profile, and lists the C-CDA sections a profile's
templates cover. The agent does not write C-CDA templates.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `UpdateProfileWithAgent` | Edit a profile in conversation with the agent (event stream) |
| `CreateFHIRDatastore` | Create a FHIR R4 data store and start its FHIR server |
| `DescribeFHIRDatastore` | Describe a data store |
| `ListFHIRDatastores` | List data stores, filtered by name, status or date |
| `UpdateFHIRDatastore` | Rename a data store or change its configurations |
| `DeleteFHIRDatastore` | Delete a data store and its FHIR server |
| `RestoreFHIRDatastore` | Restore a data store with continuous backups into a new one |
| `StartFHIRImportJob` | Import NDJSON from S3, or convert and import with a profile |
| `StartFHIRExportJob` | Export a data store's resources to S3 as NDJSON |
| `DescribeFHIRImportJob` | Describe an import job and its progress |
| `DescribeFHIRExportJob` | Describe an export job |
| `ListFHIRImportJobs` | List a data store's import jobs |
| `ListFHIRExportJobs` | List a data store's export jobs |
| `ListTagsForResource` | List a data store's tags |
| `TagResource` | Tag a data store |
| `UntagResource` | Remove tags from a data store |
| `CreateDataTransformationProfile` | Create a C-CDA or CSV profile from a starter, a version, a mapping or sample data |
| `GetDataTransformationProfile` | Read a profile version, or its draft |
| `UpdateDataTransformationProfile` | Replace a profile's draft mapping |
| `PublishDataTransformationProfile` | Publish the draft, or a version, as a new version |
| `ListDataTransformationProfiles` | List profiles of a source format |
| `ListDataTransformationProfileVersions` | List a profile's versions |
| `DeleteDataTransformationProfile` | Delete a profile |
| `StartDataTransformationJob` | Convert S3 input to FHIR in S3 with a published profile |
| `DescribeDataTransformationJob` | Describe a transformation job |
| `ListDataTransformationJobs` | List transformation jobs |
<!-- floci:actions:end -->
