# Amazon Kendra

**Protocol:** JSON 1.1
**Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: AWSKendraFrontendService.<Action>`, signed for `kendra`.

Floci runs Kendra as a search engine over the documents you put and the data sources you sync, with answers, FAQs,
facets, filters, synonyms, access control, query suggestions and search analytics.

Amazon Kendra is closed to new customers. Floci treats its accounts as existing customers; set
`existing-customer: false` to have CreateIndex refused as AWS refuses a new customer (`NotAuthorizedException`,
"Amazon Kendra is no longer accepting new customers").

## Indexes

An index is `CREATING` for a transition, then `ACTIVE`; updates go `UPDATING` and deletes `DELETING` the same way.
Its role must exist and trust `kendra.amazonaws.com`, and a KMS key must exist. Each account has at most five indexes
per edition. Every index has Kendra's reserved fields (`_category`, `_created_at`, `_source_uri` and the rest);
`UpdateIndex` adds custom fields, with their search settings and relevance tuning, and a field's type can't change.
Developer edition indexes hold 10,000 documents and five data sources, and have no capacity units.

## Documents

`BatchPutDocument` takes up to ten documents, inline or from S3 (read with the request's role). A document whose
attributes aren't fields of the index, or have the wrong type, comes back in `FailedDocuments`. The rest are
`PROCESSING` for a moment, then `INDEXED` (`UPDATED` when put again). Text is read from plain text, HTML (its title
too), XML, JSON, CSV, Markdown, RTF, PDF content streams, and Word, PowerPoint and Excel documents; a document whose
text can't be read is `FAILED`.

Custom document enrichment runs inline rules, then the pre-extraction Lambda over the raw document written to the
hook's bucket, then text extraction, then the post-extraction Lambda over `{"textContent":{"documentBodyText":...}}`,
each answering the object to index and the attribute updates to apply.

## Searching

`Query` ranks documents by BM25 over their title, body and searchable fields, with a bonus for words close together,
the fields' relevance tuning (or the query's overrides), and click and relevance feedback for the same query. Kendra's
query syntax works: `"phrases"`, `AND`, `OR`, `NOT`, parentheses, `prefix*` and field conditions such as
`_category:guide` or `_created_at:>2026-01-01`; malformed syntax answers a `QUERY_LANGUAGE_INVALID_SYNTAX` warning.
A thesaurus adds synonyms, highlighted `THESAURUS_SYNONYM`.

A question gets an `ANSWER`: the best sentence with the answer marked `TopAnswer`, or a `TABLE` excerpt from a CSV or
Excel document's matching rows. FAQ questions close to the query answer as `QUESTION_ANSWER`. Featured results sets
put their documents in `FeaturedResultsItems` for their exact queries. Results carry excerpts with highlights,
confidence, displayable attributes, facets, and spelling suggestions when asked. Attribute filters, sorting,
collapsing and paging (100 results at most) work as on AWS; documents are English unless a filter names
`_language_code`. `Retrieve` returns the best passages of up to 200 words.

## Access control

With the `ATTRIBUTE_FILTER` policy, a query sees everything unless it names a user or groups (in `UserContext`, or
as `_user_id` and `_group_ids` filters). With `USER_TOKEN`, the user and groups come from a JWT (HS256 or RS256,
keyed by a Secrets Manager secret or a JWKS URL, checked for issuer, expiry and claim) or a JSON token. Principal
mappings add groups, nested to any depth, and `AWS_SSO` group resolution adds the user's IAM Identity Center groups.
A matching `DENY` hides a document, a matching `ALLOW` shows it, and a document that allows someone else is hidden;
hierarchical lists are read from the deepest level up, and access control configurations replace a document's lists.

## Suggestions and analytics

Query suggestions are built from the query log: queries that found results, asked often enough by enough users
within the look-back window, since the last clear and without a blocked phrase. `LEARN_ONLY` answers none. Attribute
suggestions come from suggestable fields. `GetSnapshots` reports top queries, zero-click and zero-result queries,
top clicked documents, totals and daily trends for the interval (weeks start on Sunday).

## Data sources

A sync is `SYNCING`, then `SYNCING_INDEXING`, then `SUCCEEDED` (`FAILED` with the reason, `ABORTED` when stopped),
with counts of documents scanned, added, modified, deleted and failed; unchanged documents aren't put again and
documents gone from the source are deleted. Floci syncs S3 buckets (prefixes, glob patterns, metadata files and an
access control list file), websites (seed URLs or sitemaps to the crawl depth within the crawl mode, basic
authentication from Secrets Manager), and MySQL or PostgreSQL tables. Software connectors (SharePoint, Salesforce,
Confluence and the rest) need the outside system, so their syncs fail as when Kendra can't reach it. A `CUSTOM`
source's sync stays `SYNCING` until stopped, counting the documents batches put against it. Sources with a cron
`Schedule` sync when it comes due, and syncs a restart interrupted resume.

## Experiences

An experience's `HOME` endpoint (`http://localhost:4566/_floci/kendra/experiences/<region>/<index>/<id>/`) serves a
search page over its content sources. Entities are IAM Identity Center users and groups, with personas.

## Configuration

```yaml
floci:
  services:
    kendra:
      enabled: true
      existing-customer: true
      transition-millis: 2000
      document-processing-millis: 1000
      sync-sweep-interval-seconds: 30
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AssociateEntitiesToExperience` | Give IAM Identity Center users or groups access to an experience |
| `AssociatePersonasToEntities` | Make an experience's entities owners or viewers |
| `BatchDeleteDocument` | Delete up to ten documents, counted against a sync job when given |
| `BatchDeleteFeaturedResultsSet` | Delete featured results sets, reporting ones that don't exist |
| `BatchGetDocumentStatus` | Report documents as processing, indexed, updated, failed or not found |
| `BatchPutDocument` | Index up to ten documents inline or from S3, with enrichment |
| `ClearQuerySuggestions` | Forget the queries suggestions are built from |
| `CreateAccessControlConfiguration` | Create an access control list documents can share |
| `CreateDataSource` | Create an S3, web crawler, database, connector or custom data source |
| `CreateExperience` | Create a search experience with a search page at its endpoint |
| `CreateFaq` | Create an FAQ from a CSV or JSON file in S3 |
| `CreateFeaturedResultsSet` | Feature up to four documents for queries no other set features |
| `CreateIndex` | Create an index (refused when the account isn't an existing customer) |
| `CreateQuerySuggestionsBlockList` | Block the phrases of a file in S3 from suggestions |
| `CreateThesaurus` | Create a thesaurus from a Solr synonyms file in S3 |
| `DeleteAccessControlConfiguration` | Delete an access control configuration no document uses |
| `DeleteDataSource` | Delete a data source and the documents it synced |
| `DeleteExperience` | Delete an experience |
| `DeleteFaq` | Delete an FAQ |
| `DeleteIndex` | Delete an index and everything in it |
| `DeletePrincipalMapping` | Delete a group's members, in order of ordering id |
| `DeleteQuerySuggestionsBlockList` | Delete a block list |
| `DeleteThesaurus` | Delete a thesaurus |
| `DescribeAccessControlConfiguration` | Describe an access control configuration |
| `DescribeDataSource` | Describe a data source |
| `DescribeExperience` | Describe an experience and its endpoint |
| `DescribeFaq` | Describe an FAQ |
| `DescribeFeaturedResultsSet` | Describe a featured results set with its documents' titles and URIs |
| `DescribeIndex` | Describe an index with its fields and statistics |
| `DescribePrincipalMapping` | Describe a group's mapping history |
| `DescribeQuerySuggestionsBlockList` | Describe a block list |
| `DescribeQuerySuggestionsConfig` | Describe query suggestion settings and the suggestions built |
| `DescribeThesaurus` | Describe a thesaurus and its term and rule counts |
| `DisassociateEntitiesFromExperience` | Remove users or groups from an experience |
| `DisassociatePersonasFromEntities` | Remove entities' personas |
| `GetQuerySuggestions` | Suggest queries from the log, or attribute values, for what's typed |
| `GetSnapshots` | Report search analytics for an interval |
| `ListAccessControlConfigurations` | List an index's access control configurations |
| `ListDataSourceSyncJobs` | List a data source's syncs, newest first |
| `ListDataSources` | List an index's data sources |
| `ListEntityPersonas` | List an experience's personas |
| `ListExperienceEntities` | List an experience's users and groups with their display names |
| `ListExperiences` | List an index's experiences |
| `ListFaqs` | List an index's FAQs |
| `ListFeaturedResultsSets` | List an index's featured results sets |
| `ListGroupsOlderThanOrderingId` | List groups last mapped before an ordering id |
| `ListIndices` | List indexes |
| `ListQuerySuggestionsBlockLists` | List an index's block lists |
| `ListTagsForResource` | List a resource's tags |
| `ListThesauri` | List an index's thesauri |
| `PutPrincipalMapping` | Map a group's users and groups, inline or from S3 |
| `Query` | Search documents and FAQs, with answers, facets, filters and access control |
| `Retrieve` | Return the passages that best match a query |
| `StartDataSourceSyncJob` | Sync a data source in the background |
| `StopDataSourceSyncJob` | Stop a running sync |
| `SubmitFeedback` | Record clicks and relevance for a query's results |
| `TagResource` | Tag an index, data source, FAQ, thesaurus, block list or featured results set |
| `UntagResource` | Remove tags |
| `UpdateAccessControlConfiguration` | Change an access control configuration |
| `UpdateDataSource` | Change a data source |
| `UpdateExperience` | Change an experience |
| `UpdateFeaturedResultsSet` | Change a featured results set |
| `UpdateIndex` | Change an index, add fields and tune relevance |
| `UpdateQuerySuggestionsBlockList` | Change a block list, rereading its file |
| `UpdateQuerySuggestionsConfig` | Change query suggestion settings |
| `UpdateThesaurus` | Change a thesaurus, rereading its file |
<!-- floci:actions:end -->
