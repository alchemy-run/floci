# AWS HealthOmics

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/...` (`/referencestore`, `/sequencestore`, `/workflow`, `/run`, ...), signed for
`omics`

Floci stores references and read sets, fills analytics stores from S3, and runs workflows on their languages' engines:
Cromwell for WDL, Nextflow, and cwltool for CWL.

## References

A reference store holds references, which an import job reads from FASTA files in S3 (gzipped too) through a role that
trusts `omics.amazonaws.com`. Each reference gets its MD5 and a samtools index (`.fai`). `GetReference` serves the
source or the index in 100 MiB parts, with an optional `Range` (`0-4`). A file that isn't FASTA fails its job item. A
reference store with references, or a reference read sets name, can't be deleted.

## Read sets

A sequence store holds read sets, imported from S3 or uploaded in parts. FASTQ (plain or gzipped) and BAM/uBAM read
sets are counted read by read (`totalReadCount`, `totalBaseCount`, aligned or not); CRAM isn't. BAM and CRAM need a
reference. Each read set's ETag is the store's algorithm family (`MD5up`, `SHA256up`, `SHA512up`) over its files.

Multipart uploads take `SOURCE1` and `SOURCE2` parts, each answered with its SHA-256 checksum, which
`CompleteMultipartReadSetUpload` must repeat. Export jobs write read sets to
`<destination><readSetId>/<readSetId>_1.<ext>`. A read set unread for `archive-after-days` is `ARCHIVED`; reading it is
a `ConflictException` until an activation job makes it `ACTIVE` again. Sequence stores have an S3 access point ARN
whose access policy is kept. A store with read sets can't be deleted.

## Analytics stores

Annotation stores (GFF, TSV or VCF, with versions) and variant stores are `CREATING`, then `ACTIVE`. Variant stores,
and GFF and VCF annotation stores, need an existing reference. Import jobs read VCF, GFF or TSV files from S3, check
their columns, and count their records into `statusMessage` and the store's size. A store with data needs `force` to
delete. Shares make an annotation store, variant store or workflow available to another account once it accepts.

## Workflows and runs

A workflow (or version) comes from a zip (`definitionZip`) or an S3 object (`definitionUri`); it's `ACTIVE` once its
main file is found (`main`, or `main.wdl`, `main.nf`, `main.cwl`). Its parameter template comes from the main file's
inputs: a WDL workflow's `input` block, Nextflow's `params.*`, CWL's `inputs`.

A run checks its role and its required parameters, then waits `PENDING` until its run group has room under `maxRuns`.
It runs in a container of its language's engine: `s3://` parameters are downloaded into the run, and every task runs
in the engine's container (not in the image its runtime names). File outputs are written to
`<outputUri><runId>/out/` and WDL and CWL output values to `<runId>/logs/outputs.json`; Nextflow publishes from
`/mnt/workflow/pubdir`. The engine's log goes to CloudWatch Logs, `/aws/omics/WorkflowLog`, stream
`run/<runId>/engine`. Tasks come from Cromwell's metadata, Nextflow's trace or cwltool's log. A run past its group's
`maxDuration` (or `run-timeout-minutes`) fails, and `CancelRun` stops it. Run batches start runs from shared settings
and are `PROCESSED` once every run finishes. Run caches and configurations (with VPC subnets) are kept; cached task
results aren't reused.

## Configuration

```yaml
floci:
  services:
    omics:
      enabled: true
      engine: docker                    # or none: runs fail with ENGINE_DISABLED
      wdl-image: broadinstitute/cromwell:87
      nextflow-image: nextflow/nextflow:24.10.4
      cwl-image: quay.io/commonwl/cwltool:3.1.20241217163858
      engine-heap-mb: 1024
      run-timeout-minutes: 120
      archive-after-days: 30
      max-reference-stores: 5
      transition-millis: 2000
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AbortMultipartReadSetUpload` | Abandon an upload |
| `AcceptShare` | Accept a share |
| `BatchDeleteReadSet` | Delete read sets |
| `CancelAnnotationImportJob` | Cancel an annotation import job |
| `CancelRun` | Stop a run |
| `CancelRunBatch` | Cancel a batch's runs |
| `CancelVariantImportJob` | Cancel a variant import job |
| `CompleteMultipartReadSetUpload` | Finish an upload into a read set |
| `CreateAnnotationStore` | Create an annotation store |
| `CreateAnnotationStoreVersion` | Create an annotation store version |
| `CreateConfiguration` | Create a configuration |
| `CreateMultipartReadSetUpload` | Start a read set upload |
| `CreateReferenceStore` | Create a reference store |
| `CreateRunCache` | Create a run cache |
| `CreateRunGroup` | Create a run group |
| `CreateSequenceStore` | Create a sequence store |
| `CreateShare` | Create a share |
| `CreateVariantStore` | Create a variant store |
| `CreateWorkflow` | Create a workflow |
| `CreateWorkflowVersion` | Create a workflow version |
| `DeleteAnnotationStore` | Delete an annotation store |
| `DeleteAnnotationStoreVersions` | Delete annotation store versions |
| `DeleteBatch` | Delete a batch |
| `DeleteConfiguration` | Delete a configuration |
| `DeleteReference` | Delete a reference |
| `DeleteReferenceStore` | Delete a reference store |
| `DeleteRun` | Delete a run |
| `DeleteRunBatch` | Delete a batch's runs |
| `DeleteRunCache` | Delete a run cache |
| `DeleteRunGroup` | Delete a run group |
| `DeleteS3AccessPolicy` | Remove a store's S3 access policy |
| `DeleteSequenceStore` | Delete a sequence store |
| `DeleteShare` | Delete a share |
| `DeleteVariantStore` | Delete a variant store |
| `DeleteWorkflow` | Delete a workflow |
| `DeleteWorkflowVersion` | Delete a workflow version |
| `GetAnnotationImportJob` | Describe an annotation import job |
| `GetAnnotationStore` | Describe an annotation store |
| `GetAnnotationStoreVersion` | Describe an annotation store version |
| `GetBatch` | Describe a batch |
| `GetConfiguration` | Describe a configuration |
| `GetReadSet` | Read a read set file part |
| `GetReadSetActivationJob` | Describe a read set activation job |
| `GetReadSetExportJob` | Describe a read set export job |
| `GetReadSetImportJob` | Describe a read set import job |
| `GetReadSetMetadata` | Describe a read set |
| `GetReference` | Read a reference file part |
| `GetReferenceImportJob` | Describe a reference import job |
| `GetReferenceMetadata` | Describe a reference |
| `GetReferenceStore` | Describe a reference store |
| `GetRun` | Describe a run |
| `GetRunCache` | Describe a run cache |
| `GetRunGroup` | Describe a run group |
| `GetRunTask` | Describe a run task |
| `GetS3AccessPolicy` | Read a store's S3 access policy |
| `GetSequenceStore` | Describe a sequence store |
| `GetShare` | Describe a share |
| `GetVariantImportJob` | Describe a variant import job |
| `GetVariantStore` | Describe a variant store |
| `GetWorkflow` | Describe a workflow |
| `GetWorkflowVersion` | Describe a workflow version |
| `ListAnnotationImportJobs` | List annotation import jobs |
| `ListAnnotationStoreVersions` | List annotation store versions |
| `ListAnnotationStores` | List annotation stores |
| `ListBatch` | List batches |
| `ListConfigurations` | List configurations |
| `ListMultipartReadSetUploads` | List read set uploads |
| `ListReadSetActivationJobs` | List read set activation jobs |
| `ListReadSetExportJobs` | List read set export jobs |
| `ListReadSetImportJobs` | List read set import jobs |
| `ListReadSetUploadParts` | List an upload's parts |
| `ListReadSets` | List read sets |
| `ListReferenceImportJobs` | List reference import jobs |
| `ListReferenceStores` | List reference stores |
| `ListReferences` | List references |
| `ListRunCaches` | List run caches |
| `ListRunGroups` | List run groups |
| `ListRunTasks` | List a run's tasks |
| `ListRuns` | List runs |
| `ListRunsInBatch` | List a batch's runs |
| `ListSequenceStores` | List sequence stores |
| `ListShares` | List shares |
| `ListTagsForResource` | List a resource's tags |
| `ListVariantImportJobs` | List variant import jobs |
| `ListVariantStores` | List variant stores |
| `ListWorkflowVersions` | List workflow versions |
| `ListWorkflows` | List workflows |
| `PutS3AccessPolicy` | Set a store's S3 access policy |
| `StartAnnotationImportJob` | Start an annotation import job |
| `StartReadSetActivationJob` | Start a read set activation job |
| `StartReadSetExportJob` | Start a read set export job |
| `StartReadSetImportJob` | Start a read set import job |
| `StartReferenceImportJob` | Start a reference import job |
| `StartRun` | Start a run on its engine |
| `StartRunBatch` | Start a batch of runs |
| `StartVariantImportJob` | Start a variant import job |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
| `UpdateAnnotationStore` | Update an annotation store |
| `UpdateAnnotationStoreVersion` | Update an annotation store version |
| `UpdateRunCache` | Update a run cache |
| `UpdateRunGroup` | Update a run group |
| `UpdateSequenceStore` | Update a sequence store |
| `UpdateVariantStore` | Update a variant store |
| `UpdateWorkflow` | Update a workflow |
| `UpdateWorkflowVersion` | Update a workflow version |
| `UploadReadSetPart` | Upload a read set part |
<!-- floci:actions:end -->
