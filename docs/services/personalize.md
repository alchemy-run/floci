# Amazon Personalize

**Protocol:** JSON 1.1 (control plane), REST JSON (runtime and events)
**Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: AmazonPersonalize.<Operation>`; the runtime
(`/recommendations`, `/personalize-ranking`, `/action-recommendations`) and events (`/events`, `/items`, `/users`,
`/actions`, `/action-interactions`) APIs on the same port. All three are signed for `personalize`.

Floci trains real models from your datasets. A campaign or recommender answers from them, and the events you record
change what it recommends next.

## Datasets

A schema is an Avro record. A dataset needs the fields its type requires: `USER_ID`, `ITEM_ID` and `TIMESTAMP` for
Interactions, `ITEM_ID` for Items, `USER_ID` for Users, `ACTION_ID` for Actions, and `USER_ID`, `ACTION_ID`, `TIMESTAMP`
and `EVENT_TYPE` for Action interactions. A dataset group holds one dataset of each type. A domain dataset group
(`ECOMMERCE`, `VIDEO_ON_DEMAND`) takes only schemas of its domain.

An import job reads CSV files from S3: one object, or every object under a folder ending in `/`. The header names schema
fields, and values must match their types. `FULL` replaces what was imported before; `INCREMENTAL` adds to it. Rows you
record through the events API stay either way. An export job writes `<path><job name>/<type>.csv` with the rows it
selects (`BULK` imported, `PUT` recorded, or `ALL`). A data deletion job removes every row for the `USER_ID`s in its CSV.
Jobs that take a role need an IAM role that trusts `personalize.amazonaws.com`.

## Events

`PutEvents` records interactions for an event tracker's dataset group, addressed by its `trackingId`. `PutItems`,
`PutUsers` and `PutActions` upsert rows by ID, and `PutActionInteractions` records action interactions. Each property
(`numberOfRatings`) must name a schema field (`NUMBER_OF_RATINGS`). A dataset group has at most one event tracker, and
needs an Interactions dataset before it can have one.

## Training

A solution version trains on the dataset group's interactions, keeping only its `eventType` and those at or above its
`eventValueThreshold`. It needs at least `min-training-interactions`, as Personalize requires 1,000. What it learns depends
on the recipe:

- **User personalization**: the items that users who share your history went on to use, scored by co-occurrence.
- **Popularity count** and **trending now**: the most-used items, and those gaining the most in the last week.
- **Similar items** (`aws-sims`, `aws-similar-items`, the domain "more like" use cases): the items used together with an
  item.
- **Personalized ranking**: a list you give, reordered for the user.
- **Item affinity**: the users most likely to use an item, for batch segment jobs.
- **Next best action**: the actions a user is likeliest to take.

Training holds each user's last interaction out to compute `GetSolutionMetrics` (coverage, mean reciprocal rank,
precision and NDCG at 5, 10 and 25). AutoML trains each listed recipe and keeps the best by its metric.
`StopSolutionVersionCreation` stops a version that hasn't finished.

## Recommendations

A campaign serves one solution version, or the solution's latest active version with `syncWithLatestSolutionVersion`. A
recommender trains its domain use case when it's created and stops answering while it's `INACTIVE`.
Recommendations use the user's latest interactions, including those recorded since training. Scores add up to 1 over
the items returned.

Filters run as Personalize runs them: `INCLUDE`/`EXCLUDE ItemID WHERE` conditions on `Items`, `Interactions`, `Users`,
`Actions` or `CurrentItem` fields, with `IN`, `NOT IN`, `=`, `<`, `>`, `<=`, `>=`, `AND`/`OR`, `$PARAMETERS` and an
optional `IF Users.<field>` condition. Clauses join with `|`. A clause whose parameters have no value is skipped.
Promotions set aside a share of the results for items their filter selects. `metadataColumns` returns item fields when
the campaign enables metadata.

Batch inference jobs answer each JSON line of their input (`{"userId": ...}`, `{"itemId": ...}`, or
`{"userId": ..., "itemList": [...]}`) into `<output path><input file>.out`. Theme generation names each line's theme
after the item. Batch segment jobs answer `{"itemId": ...}` or `{"itemAttributes": ...}` with the users most likely
to use those items.

## Metric attributions

A metric attribution sums `SAMPLECOUNT()` or `SUM(Items.<FIELD>)` over the interactions of each event type. An import
job with `publishAttributionMetricsToS3` writes the totals for the rows it imported to the attribution's S3 destination.

## Configuration

```yaml
floci:
  services:
    personalize:
      enabled: true
      transition-millis: 2000          # how long resources and jobs stay pending
      min-training-interactions: 1000  # the fewest interactions a model trains on
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateBatchInferenceJob` | Answer each line of an S3 file from a model |
| `CreateBatchSegmentJob` | Find the users likeliest to use items |
| `CreateCampaign` | Serve a solution version |
| `CreateDataDeletionJob` | Delete users' data |
| `CreateDataset` | Create a dataset |
| `CreateDatasetExportJob` | Export a dataset to S3 as CSV |
| `CreateDatasetGroup` | Create a dataset group |
| `CreateDatasetImportJob` | Import CSV from S3 |
| `CreateEventTracker` | Create an event tracker |
| `CreateFilter` | Create a filter |
| `CreateMetricAttribution` | Create a metric attribution |
| `CreateRecommender` | Train a domain recommender |
| `CreateSchema` | Create an Avro schema |
| `CreateSolution` | Create a solution |
| `CreateSolutionVersion` | Train a model |
| `DeleteCampaign` | Delete a campaign |
| `DeleteDataset` | Delete a dataset |
| `DeleteDatasetGroup` | Delete a dataset group |
| `DeleteEventTracker` | Delete an event tracker |
| `DeleteFilter` | Delete a filter |
| `DeleteMetricAttribution` | Delete a metric attribution |
| `DeleteRecommender` | Delete a recommender |
| `DeleteSchema` | Delete a schema |
| `DeleteSolution` | Delete a solution and its versions |
| `DescribeAlgorithm` | Describe a recipe's algorithm |
| `DescribeBatchInferenceJob` | Describe a batch inference job |
| `DescribeBatchSegmentJob` | Describe a batch segment job |
| `DescribeCampaign` | Describe a campaign |
| `DescribeDataDeletionJob` | Describe a data deletion job |
| `DescribeDataset` | Describe a dataset |
| `DescribeDatasetExportJob` | Describe an export job |
| `DescribeDatasetGroup` | Describe a dataset group |
| `DescribeDatasetImportJob` | Describe an import job |
| `DescribeEventTracker` | Describe an event tracker |
| `DescribeFeatureTransformation` | Describe a recipe's feature transformation |
| `DescribeFilter` | Describe a filter |
| `DescribeMetricAttribution` | Describe a metric attribution |
| `DescribeRecipe` | Describe a recipe |
| `DescribeRecommender` | Describe a recommender |
| `DescribeSchema` | Describe a schema |
| `DescribeSolution` | Describe a solution |
| `DescribeSolutionVersion` | Describe a solution version |
| `GetSolutionMetrics` | Read a model's held-out metrics |
| `ListBatchInferenceJobs` | List batch inference jobs |
| `ListBatchSegmentJobs` | List batch segment jobs |
| `ListCampaigns` | List campaigns |
| `ListDataDeletionJobs` | List data deletion jobs |
| `ListDatasetExportJobs` | List export jobs |
| `ListDatasetGroups` | List dataset groups |
| `ListDatasetImportJobs` | List import jobs |
| `ListDatasets` | List datasets |
| `ListEventTrackers` | List event trackers |
| `ListFilters` | List filters |
| `ListMetricAttributionMetrics` | List an attribution's metrics |
| `ListMetricAttributions` | List metric attributions |
| `ListRecipes` | List recipes |
| `ListRecommenders` | List recommenders |
| `ListSchemas` | List schemas |
| `ListSolutionVersions` | List solution versions |
| `ListSolutions` | List solutions |
| `ListTagsForResource` | List a resource's tags |
| `StartRecommender` | Start a recommender |
| `StopRecommender` | Stop a recommender |
| `StopSolutionVersionCreation` | Stop training |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
| `UpdateCampaign` | Change a campaign's version or settings |
| `UpdateDataset` | Move a dataset to a compatible schema |
| `UpdateMetricAttribution` | Change an attribution's metrics |
| `UpdateRecommender` | Change a recommender's settings |
| `UpdateSolution` | Change a solution's training settings |
| `GetRecommendations` | Recommend items |
| `GetPersonalizedRanking` | Rank items for a user |
| `GetActionRecommendations` | Recommend actions for a user |
| `PutEvents` | Record interactions |
| `PutItems` | Upsert items |
| `PutUsers` | Upsert users |
| `PutActions` | Upsert actions |
| `PutActionInteractions` | Record action interactions |
<!-- floci:actions:end -->
