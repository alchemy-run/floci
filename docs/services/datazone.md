# Amazon DataZone

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/` with DataZone's paths (`/v2/domains`, `/v2/domains/{domainIdentifier}/...`, `/tags/{resourceArn}`), signed for `datazone`.

Floci runs DataZone domains end to end: domain units, user and group profiles, projects, environments
provisioned from DataZone's blueprints, the business data catalog, subscriptions granted through Lake Formation,
metadata rules, data sources that import Glue tables, lineage, and SageMaker Unified Studio notebooks.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDomain` | Create a domain with its root domain unit; the execution role must trust DataZone |
| `GetDomain` | Describe a domain |
| `UpdateDomain` | Update a domain |
| `DeleteDomain` | Delete a domain and everything in it |
| `ListDomains` | List domains |
| `CreateDomainUnit` | Create a domain unit |
| `GetDomainUnit` | Describe a domain unit |
| `UpdateDomainUnit` | Update a domain unit |
| `DeleteDomainUnit` | Delete a domain unit |
| `ListDomainUnitsForParent` | List a domain unit's child units |
| `CreateUserProfile` | Create a profile for an IAM principal or SSO user |
| `GetUserProfile` | Describe a user profile |
| `UpdateUserProfile` | Update a user profile |
| `SearchUserProfiles` | Search user profiles by type and text |
| `GetIamPortalLoginUrl` | Mint a data portal sign-in URL for the caller |
| `CreateProject` | Create a project; its creator becomes its owner |
| `GetProject` | Describe a project |
| `UpdateProject` | Update a project |
| `DeleteProject` | Delete a project without environments or assets |
| `ListProjects` | List projects |
| `CreateProjectMembership` | Add a user or group to a project with a designation |
| `DeleteProjectMembership` | Delete a project member |
| `ListProjectMemberships` | List a project's members |
| `ListNotifications` | List the caller's notifications |
| `ListTagsForResource` | List a domain's tags |
| `TagResource` | Add or replace a domain's tags |
| `UntagResource` | Remove a domain's tags |
| `ListEnvironmentBlueprints` | List the managed blueprints and the domain's custom ones |
| `GetEnvironmentBlueprint` | Describe a custom environment blueprint |
| `CreateEnvironmentBlueprint` | Create a custom environment blueprint |
| `UpdateEnvironmentBlueprint` | Update a custom environment blueprint |
| `DeleteEnvironmentBlueprint` | Delete a custom environment blueprint |
| `PutEnvironmentBlueprintConfiguration` | Enable a blueprint in Regions with its provisioning and manage-access roles |
| `GetEnvironmentBlueprintConfiguration` | Describe a blueprint configuration |
| `DeleteEnvironmentBlueprintConfiguration` | Disable a blueprint no environment uses |
| `ListEnvironmentBlueprintConfigurations` | List blueprint configurations |
| `CreateEnvironmentProfile` | Create an environment profile |
| `GetEnvironmentProfile` | Describe an environment profile |
| `UpdateEnvironmentProfile` | Update an environment profile |
| `DeleteEnvironmentProfile` | Delete an environment profile |
| `ListEnvironmentProfiles` | List environment profiles |
| `CreateEnvironment` | Deploy an environment: its role, and for DefaultDataLake Glue databases, a workgroup and a log group |
| `GetEnvironment` | Describe an environment |
| `UpdateEnvironment` | Update an environment |
| `DeleteEnvironment` | Delete an environment and its role; the data lake resources are retained |
| `ListEnvironments` | List a project's environments |
| `GetEnvironmentCredentials` | Mint credentials for the environment role |
| `CreateEnvironmentAction` | Create an environment action |
| `GetEnvironmentAction` | Describe an environment action |
| `UpdateEnvironmentAction` | Update an environment action |
| `DeleteEnvironmentAction` | Delete an environment action |
| `ListEnvironmentActions` | List an environment's actions |
| `CreateSubscriptionTarget` | Create a subscription target |
| `GetSubscriptionTarget` | Describe a subscription target |
| `UpdateSubscriptionTarget` | Update a subscription target |
| `DeleteSubscriptionTarget` | Delete a subscription target |
| `ListSubscriptionTargets` | List an environment's subscription targets |
| `CreateFormType` | Create a form type |
| `GetFormType` | Describe a form type |
| `DeleteFormType` | Delete a form type |
| `CreateAssetType` | Create an asset type |
| `GetAssetType` | Describe an asset type |
| `DeleteAssetType` | Delete an asset type |
| `CreateAsset` | Create an asset of a managed or custom type with its forms |
| `GetAsset` | Describe an asset |
| `CreateAssetRevision` | Add a revision to an asset, keeping earlier revisions readable |
| `ListAssetRevisions` | List an asset's revisions |
| `DeleteAsset` | Delete an asset |
| `AcceptPredictions` | Apply a metadata generation run's predictions as a new asset revision |
| `RejectPredictions` | Discard a metadata generation run's predictions |
| `CreateListingChangeSet` | Publish or unpublish an asset or data product, enforcing metadata rules |
| `GetListing` | Describe a listing |
| `DeleteListing` | Delete a listing |
| `Search` | Search the inventory of projects the caller belongs to |
| `SearchListings` | Search published listings, with filters, sorting and aggregations |
| `SearchTypes` | Search managed or custom asset, form and lineage node types |
| `CreateGlossary` | Create a glossary |
| `GetGlossary` | Describe a glossary |
| `UpdateGlossary` | Update a glossary |
| `DeleteGlossary` | Delete a glossary |
| `CreateGlossaryTerm` | Create a glossary term |
| `GetGlossaryTerm` | Describe a glossary term |
| `UpdateGlossaryTerm` | Update a glossary term |
| `DeleteGlossaryTerm` | Delete a glossary term |
| `PostTimeSeriesDataPoints` | Record data points for an asset or listing |
| `ListTimeSeriesDataPoints` | List an entity's data points for a form |
| `GetTimeSeriesDataPoint` | Describe a time series data point |
| `DeleteTimeSeriesDataPoints` | Delete an entity's data points for a form |
| `CreateSubscriptionRequest` | Request a listing for a project, enforcing metadata rules |
| `GetSubscriptionRequestDetails` | Describe a subscription request |
| `UpdateSubscriptionRequest` | Update a subscription request |
| `DeleteSubscriptionRequest` | Delete a subscription request |
| `AcceptSubscriptionRequest` | Approve a request and grant it to the subscriber's environments |
| `RejectSubscriptionRequest` | Reject a subscription request with a comment |
| `ListSubscriptionRequests` | List subscription requests, by project or listing |
| `GetSubscription` | Describe a subscription |
| `ListSubscriptions` | List subscriptions, by project or listing |
| `CancelSubscription` | Cancel a subscription and its Lake Formation grants |
| `RevokeSubscription` | Revoke a subscription and its Lake Formation grants |
| `CreateSubscriptionGrant` | Grant a subscribed listing to an environment's subscription target |
| `GetSubscriptionGrant` | Describe a subscription grant |
| `DeleteSubscriptionGrant` | Delete a subscription grant |
| `ListSubscriptionGrants` | List subscription grants |
| `CreateDataSource` | Create a Glue, Redshift or SageMaker data source |
| `GetDataSource` | Describe a data source |
| `UpdateDataSource` | Update a data source |
| `DeleteDataSource` | Delete a data source |
| `ListDataSources` | List a project's data sources |
| `StartDataSourceRun` | Import a Glue data source's tables as assets, publishing them when configured |
| `GetDataSourceRun` | Describe a data source run |
| `ListDataSourceRuns` | List a data source's runs |
| `ListDataSourceRunActivities` | List the tables a data source run imported |
| `StartMetadataGenerationRun` | Predict business names, descriptions or glossary terms for an asset |
| `GetMetadataGenerationRun` | Describe a metadata generation run |
| `CancelMetadataGenerationRun` | Cancel a metadata generation run |
| `ListMetadataGenerationRuns` | List metadata generation runs |
| `PostLineageEvent` | Record an OpenLineage run event as lineage nodes and edges |
| `GetLineageNode` | Describe a lineage node |
| `ListLineageNodeHistory` | List a lineage node's versions |
| `GetLineageEvent` | Describe a lineage event |
| `ListLineageEvents` | List lineage events |
| `AddEntityOwner` | Add an owner of a domain unit |
| `RemoveEntityOwner` | Remove an owner of a domain unit |
| `ListEntityOwners` | List a domain unit's owners |
| `UpdateRootDomainUnitOwner` | Replace an owner of the root domain unit |
| `AddPolicyGrant` | Grant a managed policy on a domain unit, blueprint configuration, profile or asset type |
| `RemovePolicyGrant` | Remove a policy grant |
| `ListPolicyGrants` | List policy grants on an entity |
| `CreateGroupProfile` | Create a group profile |
| `GetGroupProfile` | Describe a group profile |
| `UpdateGroupProfile` | Update a group profile |
| `SearchGroupProfiles` | Search group profiles by type and text |
| `CreateRule` | Require forms or glossary terms to publish or subscribe |
| `GetRule` | Describe a rule |
| `UpdateRule` | Update a rule |
| `DeleteRule` | Delete a rule |
| `ListRules` | List the rules on a domain unit |
| `CreateDesignation` | Create a designation |
| `GetDesignation` | Describe a designation |
| `UpdateDesignation` | Update a designation |
| `DeleteDesignation` | Delete a designation |
| `ListDesignations` | List designations |
| `CreateProjectProfile` | Create a project profile |
| `GetProjectProfile` | Describe a project profile |
| `UpdateProjectProfile` | Update a project profile |
| `DeleteProjectProfile` | Delete a project profile |
| `ListProjectProfiles` | List project profiles |
| `CreateAccountPool` | Create an account pool |
| `GetAccountPool` | Describe an account pool |
| `UpdateAccountPool` | Update an account pool |
| `DeleteAccountPool` | Delete an account pool |
| `ListAccountPools` | List account pools |
| `ListAccountsInAccountPool` | List an account pool's accounts |
| `CreateConnection` | Create a connection |
| `GetConnection` | Describe a connection |
| `UpdateConnection` | Update a connection |
| `DeleteConnection` | Delete a connection |
| `ListConnections` | List connections |
| `CreateDataProduct` | Create a data product |
| `CreateDataProductRevision` | Create a data product revision |
| `GetDataProduct` | Describe a data product |
| `DeleteDataProduct` | Delete a data product |
| `ListDataProductRevisions` | List a data product's revisions |
| `CreateAssetFilter` | Define column or row filters on an asset |
| `GetAssetFilter` | Describe an asset filter |
| `UpdateAssetFilter` | Update an asset filter |
| `DeleteAssetFilter` | Delete an asset filter |
| `ListAssetFilters` | List an asset's filters |
| `AssociateGovernedTerms` | Attach governed glossary terms to an asset |
| `DisassociateGovernedTerms` | Detach governed glossary terms from an asset |
| `BatchPutAttributesMetadata` | Attach forms to an asset's columns |
| `BatchGetAttributesMetadata` | Read the forms attached to an asset's columns |
| `AssociateEnvironmentRole` | Associate a role with an environment |
| `DisassociateEnvironmentRole` | Disassociate a role from an environment |
| `PutDataExportConfiguration` | Enable or disable exporting the catalog to S3 Tables |
| `GetDataExportConfiguration` | Describe the data export configuration |
| `DeleteDataExportConfiguration` | Delete the data export configuration |
| `QueryGraph` | Traverse the lineage graph from a node |
| `DeleteLineageEvent` | Delete a lineage event |
| `GetJobRun` | Describe a lineage job run |
| `ListJobRuns` | List a lineage job's runs |
| `UpdateSubscriptionGrantStatus` | Report the grant status of an asset in a subscription grant |
| `CreateNotebook` | Create a notebook |
| `GetNotebook` | Describe a notebook |
| `UpdateNotebook` | Update a notebook |
| `DeleteNotebook` | Delete a notebook |
| `ListNotebooks` | List a project's notebooks |
| `StartNotebookImport` | Create a notebook from an IPYNB file or S3 objects |
| `StartNotebookSync` | Sync a notebook from Git; it fails, as Git is not reachable |
| `StartNotebookExport` | Export a notebook as IPYNB to the project's S3 location |
| `GetNotebookExport` | Describe a notebook export |
| `StartNotebookRun` | Queue a notebook run; it fails, as floci has no notebook compute |
| `GetNotebookRun` | Describe a notebook run |
| `StopNotebookRun` | Stop a notebook run |
| `ListNotebookRuns` | List a project's notebook runs |
<!-- floci:actions:end -->

## Domains and access

A domain's execution role (and a V2 domain's service role) must exist and trust `datazone.amazonaws.com`.
DataZone authorizes a domain before looking it up, so a call on a missing or deleted domain answers
`AccessDeniedException`. The caller's IAM principal gets a user profile on first use (a role session is identified
as its role), and project-scoped calls — creating or changing assets, types, glossaries, data sources and
notebooks, searching a project's inventory, deciding subscription requests — require that profile to be a member
of the project. A project's creator becomes its owner.

Requests are checked against DataZone's published constraints (lengths, patterns, enums, required members) before
an operation runs.

## Environments

Every domain lists DataZone's managed blueprints (`DefaultDataLake`, `DefaultDataWarehouse`, `Tooling`,
`LakehouseCatalog`, `MLExperiments`, `Workflows`, `RedshiftServerless`, `EmrServerless`) with identifiers stable per
domain, beside the custom blueprints created in it. A blueprint must be enabled for the environment's Region by
`PutEnvironmentBlueprintConfiguration` before a profile or environment uses it.

Deploying an environment uses the configuration's provisioning role, which must trust DataZone. It creates the
environment role, and for `DefaultDataLake` makes the manage-access role a Lake Formation administrator, then
creates a producer and a consumer Glue database, an Athena workgroup (writing results under the configuration's
`S3Location`) and a log group, listed in `provisionedResources`. Deleting the environment removes its role and,
as DataZone's blueprint stack does, retains the data lake resources. `GetEnvironmentCredentials` mints
credentials for the environment role.

## Catalog and subscriptions

Assets have revisions; publishing an asset snapshots a revision into a listing every user of the domain can find
with `SearchListings`. Data products bundle assets and publish their assets' listings with their own. Metadata
rules on a domain unit (or an ancestor with `includeChildDomainUnits`) can require forms or glossary terms before
publishing, and forms on a subscription request.

Accepting a subscription request approves a subscription and grants it to every environment of the subscribing
project with an automatic subscription target for the asset's type: a Glue table is granted to the target's
authorized principals as Lake Formation `SELECT` and `DESCRIBE`. Cancelling or revoking removes the grants unless
the owner retains them. Subscription steps post notifications and `aws.datazone` events to the default event bus.

## Data sources, metadata and lineage

A Glue data source run reads the tables of each configured database, filtered by its `INCLUDE`/`EXCLUDE`
expressions (`*` matches any characters), and creates a `amazon.datazone.GlueTableAssetType` asset per new table, a
new revision per changed table, or leaves an unchanged table alone, publishing them when `publishOnImport` is set.
Redshift and SageMaker sources run and fail, as their source is not reachable from floci. A metadata generation
run predicts business names (from technical names), descriptions and glossary term associations, which the asset's
owners accept or reject as a new revision. Lineage events are OpenLineage run events: each records its job, run and
input and output datasets as lineage nodes, which `GetLineageNode`, `ListLineageNodeHistory` and `QueryGraph`
read.

## Notebooks

Notebooks, their cells, imports from S3 and `IPYNB` exports to the project's S3 location are kept. Runs need
SageMaker Unified Studio compute and Git syncs need the repository, neither of which floci has: a run is queued and
fails, and a sync fails, each with that reason. `PDF` exports fail for want of a renderer.

## Configuration

```yaml
floci:
  services:
    datazone:
      enabled: true
      transition-millis: 2000   # how long a domain, environment, data source or run stays in its transitional state
```
