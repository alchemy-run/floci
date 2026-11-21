# Amazon Glacier

**Protocol:** REST JSON (`/<account>/vaults/...`, the account `-` for the caller's own)
**Endpoint:** `http://localhost:4566/`, signed for `glacier`.

Floci runs Glacier's vault API. Archives are kept and checked against their SHA-256 tree hashes, whether uploaded
whole or in parts. A job retrieves an archive or a megabyte-aligned range of it, takes the vault's inventory as JSON
or CSV, or runs a select query over a CSV archive into S3. Each job finishes `floci.services.glacier.job-millis`
after it starts, whatever its tier, and notifies its SNS topic and the vault's. A vault's inventory is taken
`floci.services.glacier.inventory-millis` after its archives last change. `DescribeVault` and inventory jobs report
the last one, and only an inventoried empty vault can be deleted.

A vault lock's policy is in effect from its initiation, in progress or locked: an action it denies, such as
`glacier:DeleteArchive` under a `glacier:ArchiveAgeInDays` condition, is refused whatever IAM allows. A vault's access
policy takes part in IAM evaluation, so it can grant another account access. The data retrieval policy applies to
Standard retrievals. Glacier is closed to new customers. floci treats its accounts as existing customers; set
`floci.services.glacier.existing-customer: false` to refuse every call, as AWS refuses a new account.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ListVaults` | List vaults by name |
| `CreateVault` | Create a vault |
| `DescribeVault` | Describe a vault as of its last inventory |
| `DeleteVault` | Delete an inventoried empty vault |
| `ListTagsForVault` | List a vault's tags |
| `SetVaultAccessPolicy` | Set a vault's access policy |
| `GetVaultAccessPolicy` | Read a vault's access policy |
| `DeleteVaultAccessPolicy` | Delete a vault's access policy |
| `SetVaultNotifications` | Notify an SNS topic of a vault's finished jobs |
| `GetVaultNotifications` | Read a vault's notification configuration |
| `DeleteVaultNotifications` | Delete a vault's notification configuration |
| `InitiateVaultLock` | Attach a lock policy in progress, for 24 hours |
| `GetVaultLock` | Read a vault lock |
| `AbortVaultLock` | Remove a lock still in progress |
| `CompleteVaultLock` | Lock a vault's lock policy for good |
| `UploadArchive` | Upload an archive, checked against its tree hash |
| `DeleteArchive` | Delete an archive |
| `InitiateMultipartUpload` | Start an upload in parts |
| `ListMultipartUploads` | List a vault's uploads in progress |
| `UploadMultipartPart` | Upload a part, checked against its tree hash |
| `CompleteMultipartUpload` | Assemble the parts into an archive |
| `AbortMultipartUpload` | Abandon an upload |
| `ListParts` | List an upload's parts |
| `InitiateJob` | Start an archive retrieval, inventory or select job |
| `ListJobs` | List a vault's jobs |
| `DescribeJob` | Describe a job |
| `GetJobOutput` | Download a job's output, or a range of it |
| `GetDataRetrievalPolicy` | Read the Region's data retrieval policy |
| `SetDataRetrievalPolicy` | Set the Region's data retrieval policy |
| `ListProvisionedCapacity` | List provisioned capacity units |
| `PurchaseProvisionedCapacity` | Buy a provisioned capacity unit |
| `AddTagsToVault` | Tag a vault |
| `RemoveTagsFromVault` | Remove tags from a vault |
<!-- floci:actions:end -->
