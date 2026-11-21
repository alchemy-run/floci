# AWS Data Exchange

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/` with Data Exchange's paths (`/v1/data-sets`, `/v1/jobs`, `/v1/event-actions`, `/v1/data-grants`, `/v1/received-data-grants`, `/v1` for `SendApiAsset`, `/tags/{ResourceArn}`), signed for `dataexchange`.

Floci runs Data Exchange for providers and subscribers: owned data sets with revisions and assets, jobs that move data
in and out, data grants between accounts with the entitled data sets they create, auto-export event actions,
provider notifications and EventBridge events.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDataSet` | Create an owned data set of one asset type |
| `ListDataSets` | List owned and entitled data sets, by origin |
| `GetDataSet` | Describe a data set |
| `UpdateDataSet` | Rename or redescribe an owned data set |
| `DeleteDataSet` | Delete an owned data set no grant shares, with its revisions and assets |
| `SendDataSetNotification` | Notify a shared data set's receivers through EventBridge |
| `CreateRevision` | Add a draft revision |
| `ListDataSetRevisions` | List a data set's revisions, newest first |
| `GetRevision` | Describe a revision |
| `UpdateRevision` | Change a revision's comment, or finalize it to publish it to receivers |
| `DeleteRevision` | Delete a revision not published to receivers |
| `RevokeRevision` | Revoke a finalized revision, for receivers too |
| `ListRevisionAssets` | List a revision's assets |
| `GetAsset` | Describe an asset |
| `UpdateAsset` | Rename an asset of a draft revision |
| `DeleteAsset` | Delete an asset of a draft revision |
| `CreateJob` | Create an import or export job |
| `ListJobs` | List jobs, by data set or revision |
| `GetJob` | Describe a job, its state, signed URLs and errors |
| `StartJob` | Run a waiting job |
| `CancelJob` | Cancel a waiting job |
| `CreateEventAction` | Auto-export revisions published to an entitled data set |
| `ListEventActions` | List event actions, by data set |
| `GetEventAction` | Describe an event action |
| `UpdateEventAction` | Change an event action's export destination |
| `DeleteEventAction` | Delete an event action |
| `CreateDataGrant` | Share an owned data set with another account |
| `ListDataGrants` | List the grants this account sent |
| `GetDataGrant` | Describe a sent grant |
| `DeleteDataGrant` | End a grant and the receiver's entitled data set |
| `AcceptDataGrant` | Accept a received grant, creating the entitled data set |
| `ListReceivedDataGrants` | List received grants, by acceptance state |
| `GetReceivedDataGrant` | Describe a received grant |
| `SendApiAsset` | Call an API asset's API Gateway stage |
| `ListTagsForResource` | List a resource's tags |
| `TagResource` | Add or replace tags |
| `UntagResource` | Remove tags |
<!-- floci:actions:end -->

## Data sets, revisions and assets

A data set's `AssetType` (`S3_SNAPSHOT`, `API_GATEWAY_API`, `REDSHIFT_DATA_SHARE`, `S3_DATA_ACCESS`,
`LAKE_FORMATION_DATA_PERMISSION`) fixes the assets its revisions hold. Assets are added by import jobs while a
revision is a draft; finalizing a revision (`UpdateRevision` with `Finalized`) requires at least one asset and
freezes its assets. `RevokeRevision` revokes a finalized revision, which can then no longer be exported.

## Jobs

`CreateJob` returns a `WAITING` job; `StartJob` moves it to `IN_PROGRESS`, and it ends `COMPLETED` or `ERROR` with
`Errors` after `floci.services.dataexchange.job-millis`. `CancelJob` cancels a waiting job.

| Type | What it does |
| --- | --- |
| `IMPORT_ASSETS_FROM_S3` | Copies each S3 object into the revision as an asset named by its key |
| `IMPORT_ASSET_FROM_SIGNED_URL` | Returns a `SignedUrl` to `PUT` the file to; on start, checks its `Md5Hash` and adds the asset |
| `EXPORT_ASSETS_TO_S3` | Writes assets to S3, at `Key` or the asset's name |
| `EXPORT_ASSET_TO_SIGNED_URL` | Returns a `SignedUrl` to `GET` the asset from, valid for an hour |
| `EXPORT_REVISIONS_TO_S3` | Writes every asset of each revision under its `KeyPattern` (default `${Revision.CreatedAt}/${Asset.Name}`; also `${Revision.CreatedAt.Year}`, `.Month`, `.Day`, `${Revision.Id}`, `${Asset.Id}`) |
| `IMPORT_ASSET_FROM_API_GATEWAY_API` | Returns an `ApiSpecificationUploadUrl`; on start, checks the API exists in Floci's API Gateway and the specification's MD5, and adds an API asset whose endpoint is the stage |
| `IMPORT_ASSETS_FROM_REDSHIFT_DATA_SHARES` | Adds one asset per datashare ARN |
| `CREATE_S3_DATA_ACCESS_FROM_S3_BUCKET` | Adds an S3 data access asset for an existing bucket, with an access point alias and ARN |
| `IMPORT_ASSETS_FROM_LAKE_FORMATION_TAG_POLICY` | Adds a Lake Formation LF-tag policy asset |

`SendApiAsset` calls an API asset's API Gateway stage in Floci with the request's method, path, query string,
`x-amzn-dataexchange-header-*` headers and body, adding the asset's API key, and returns the API's response.

## Data grants and entitled data sets

`CreateDataGrant` shares an owned data set with another account (`ReceiverPrincipal`), which sees it with
`ListReceivedDataGrants`. `AcceptDataGrant` creates an `ENTITLED` data set in the receiver's account holding the
source's finalized revisions and assets. From then on, finalizing a revision on the source publishes it to the
receiver, and revoking it revokes it there. `DeleteDataGrant` ends the receiver's access and removes the entitled data
set. A shared data set cannot be deleted while its grant exists. `SendDataSetNotification` notifies the receivers of
an owned data set shared through an accepted grant; any other data set has no subscribers and is rejected.

Every account also holds an entitled `AWS Data Exchange Heartbeat (Test product)` data set
(`f10c1000000040008000000000000ea7`), like the free AWS product, whose revision holds the current Unix time and
which receives a new revision every hour.

## Event actions and events

An event action on an entitled data set (`RevisionPublished`) runs an `EXPORT_REVISIONS_TO_S3` job for each revision
published to it, at most five per data set. Data Exchange reports `aws.dataexchange` events to the account's
EventBridge: `Revision Published To Data Set`, `Revision Revoked`, `Data Set Update Delayed`,
`Data Updated in Data Set`, `Deprecation Planned for Data Set`, `Schema Change Planned for Data Set`,
`Auto-export Job Completed`, `Auto-export Job Failed`, `Data Grant Accepted` and `Data Grant Revoked`.

## Not emulated

Data sets published to AWS Marketplace products, and product-related events, are not emulated.
