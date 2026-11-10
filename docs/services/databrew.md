# AWS Glue DataBrew

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/` with DataBrew's paths (`/datasets`, `/recipes`, `/projects`, `/profileJobs`, `/recipeJobs`, `/jobs`, `/rulesets`, `/schedules`, `/tags/{ResourceArn}`), signed for `databrew`.

Floci runs DataBrew end to end: datasets read from S3 or the Glue Data Catalog, versioned recipes, projects with
interactive sessions, recipe jobs that transform the data into S3, profile jobs that write column statistics and
data quality reports, rulesets, cron schedules and tags.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDataset` | Create a dataset over S3 objects, folders or path parameters, or a Data Catalog table |
| `ListDatasets` | Page through datasets |
| `DescribeDataset` | Describe a dataset |
| `UpdateDataset` | Replace a dataset's input, format and path options |
| `DeleteDataset` | Delete a dataset no job or project uses |
| `CreateRecipe` | Create a recipe at working version 0.1 |
| `ListRecipes` | List the latest published, or every working, recipe version |
| `ListRecipeVersions` | List a recipe's published versions |
| `DescribeRecipe` | Describe a recipe version (latest published by default) |
| `UpdateRecipe` | Change the working version's description or steps |
| `PublishRecipe` | Publish the working steps as the next major version |
| `DeleteRecipeVersion` | Delete a published version, or the whole recipe with LATEST_WORKING |
| `BatchDeleteRecipeVersion` | Delete published versions, reporting each one that fails |
| `CreateProject` | Tie a dataset to a recipe with a sample |
| `ListProjects` | Page through projects |
| `DescribeProject` | Describe a project and its session |
| `UpdateProject` | Change a project's sample or role |
| `DeleteProject` | Delete a project no job uses, freeing its recipe |
| `StartProjectSession` | Open an interactive session |
| `SendProjectSessionAction` | Preview steps over the sample, or add or remove a working recipe step |
| `CreateProfileJob` | Create a profile job with sampling, profile configuration and validation rulesets |
| `UpdateProfileJob` | Replace a profile job's settings |
| `CreateRecipeJob` | Create a recipe job over a dataset and recipe version, or a project |
| `UpdateRecipeJob` | Replace a recipe job's outputs and settings |
| `ListJobs` | Page through jobs, by dataset or project |
| `DescribeJob` | Describe a job |
| `DeleteJob` | Delete a job with no run in progress |
| `StartJobRun` | Run a job against the data |
| `ListJobRuns` | List a job's runs, newest first |
| `DescribeJobRun` | Describe a run, its state and outputs |
| `StopJobRun` | Stop a starting or running run |
| `CreateRuleset` | Create data quality rules for a dataset |
| `ListRulesets` | List rulesets, by target dataset |
| `DescribeRuleset` | Describe a ruleset |
| `UpdateRuleset` | Replace a ruleset's rules |
| `DeleteRuleset` | Delete a ruleset no job validates with |
| `CreateSchedule` | Schedule jobs with a cron expression |
| `ListSchedules` | List schedules, by job |
| `DescribeSchedule` | Describe a schedule |
| `UpdateSchedule` | Replace a schedule's cron and jobs |
| `DeleteSchedule` | Delete a schedule |
| `ListTagsForResource` | List a resource's tags |
| `TagResource` | Add or replace tags |
| `UntagResource` | Remove tags |
<!-- floci:actions:end -->

## Datasets

An S3 input names one object, a folder (a key ending in `/`), or a key with `<parameter>` placeholders such as
`sales/region=<region>/part.csv`. `PathOptions.Parameters` can filter what a placeholder matches (`== :value`,
`starts_with`, `contains`, comparisons) and add the matched value as a column with `CreateColumn`, and
`FilesLimit` keeps the newest or oldest files. A Data Catalog input reads its table's S3 location. Floci reads
CSV (with `Delimiter` and `HeaderRow`) and JSON (JSON Lines, or one document with `MultiLine`), optionally
gzip-compressed, and Parquet through floci-duck; Excel, ORC and database inputs fail the run with a message saying
so. Creating a job checks that its dataset's source can be read.

## Recipes

A recipe is created as working version `0.1`. `PublishRecipe` publishes the working steps as the next major
version (`1.0`, `2.0`, …) and the working version continues as `1.1`, `2.1`, …. `DescribeRecipe` returns the latest
published version unless `RecipeVersion` names one, `LATEST_WORKING` or `LATEST_PUBLISHED`. `ListRecipes` lists
the latest published versions, or every working version with `LATEST_WORKING`; `ListRecipeVersions` lists a
recipe's published versions. A recipe is deleted by deleting its published versions and then
`LATEST_WORKING`.

Recipe jobs and project previews apply the steps for real. Supported operations: `RENAME`, `DELETE`,
`DUPLICATE`, `MOVE_TO_START`, `MOVE_TO_END`, `MOVE_TO_INDEX`, `MOVE_BEFORE`, `MOVE_AFTER`, `SORT`,
`CHANGE_DATA_TYPE`, `UPPER_CASE`, `LOWER_CASE`, `CAPITAL_CASE`, `SENTENCE_CASE`, `ADD_PREFIX`, `ADD_SUFFIX`,
`REPLACE_TEXT`, `REPLACE_PATTERN`, `REMOVE_COMBINED`, `EXTRACT_PATTERN`, `MERGE`,
`SPLIT_COLUMN_SINGLE_DELIMITER`, `FILL_WITH_EMPTY`, `FILL_WITH_NULL`, `FILL_WITH_CUSTOM`,
`FILL_WITH_MOST_FREQUENT`, `FILL_WITH_AVERAGE`, `FILL_WITH_SUM`, `FILL_WITH_MEDIAN`, `FILL_WITH_LAST_VALID`,
`REMOVE_MISSING`, `REMOVE_VALUES`, `REMOVE_DUPLICATES`, `DELETE_DUPLICATE_ROWS`, `ADD`, `SUBTRACT`,
`MULTIPLY`, `DIVIDE`, `ABSOLUTE`, `ROUND`, `FLOOR`, `CEILING` and `DATE_FORMAT`. A step's
`ConditionExpressions` (`IS`, `IS_NOT`, `IS_MISSING`, `IS_NOT_MISSING`, `STARTS_WITH`, `ENDS_WITH`,
`CONTAINS`, `MATCHES`, comparisons, `IS_BETWEEN`, `IS_NUMERIC` and their negations) choose the rows it changes,
or, for `REMOVE_VALUES`, the rows it removes. A step with any other operation fails the job run with a message
naming the step and the operation.

## Projects and sessions

A project ties a dataset to a recipe; a recipe belongs to at most one project. `StartProjectSession` opens a
session (`INITIALIZING`, then `READY` after `floci.services.databrew.session-startup-millis`) and returns its
`ClientSessionId`; another start fails with `ConflictException` unless it sets `AssumeControl`.
`SendProjectSessionAction` with `Preview` applies the working recipe, up to `StepIndex`, plus the given step to
the project's sample (`FIRST_N`, `LAST_N` or `RANDOM` rows) and returns the result as JSON in `Result`, framed by
`ViewFrame`. Without `Preview` it inserts the step into the working recipe (at `StepIndex`, or at the end), or
removes the step at `StepIndex` when no step is given.

## Jobs and runs

A recipe job takes a dataset and a published recipe version (or `LATEST_PUBLISHED`), or a project, whose working
recipe it runs. A run is `STARTING`, then `RUNNING`, for `floci.services.databrew.run-startup-millis` (default
`5000`) and then executes:

- A recipe job writes each output under its location as
  `<job>_<yyyyMMdd-HHmmss>_part00000.<ext>`: CSV (with its delimiter) or JSON Lines, optionally GZIP-compressed,
  or Parquet through floci-duck. `Overwrite` clears the location first, `PartitionColumns` writes
  `column=value/` folders, and `MaxOutputFiles` splits the rows. Data Catalog outputs write to their `S3Options`
  location.
- A profile job samples the dataset (`JobSample`) and writes `<dataset>_<run>.json` to its output location: row,
  column and duplicate counts and, for each column (or the `ProfileColumns`), its type, missing, distinct and
  unique counts, most common values, and numeric or length statistics. Each validation ruleset adds a
  `<dataset>_<run>_dq-validation-report.json` with every rule's result.

`StopJobRun` moves a running run to `STOPPING` and then `STOPPED`. A failed run is retried up to `MaxRetries`
times under the same run ID, counting `Attempt`. Finished runs report `DataBrew Job State Change` events
(`source` `aws.databrew`) to EventBridge and, with `LogSubscription` enabled, log to the
`/aws-glue-databrew/jobs/<job>` log group.

## Rulesets

A ruleset targets a dataset ARN. Rules are check expressions over aggregate statistics
(`AGG(MISSING_VALUES_PERCENTAGE) == :val1`, with `ROW_COUNT`, `COLUMN_COUNT`, `DUPLICATE_ROWS_COUNT`,
`DUPLICATE_ROWS_PERCENTAGE`, `MISSING_VALUES_COUNT`, `VALID_VALUES_COUNT`, `VALID_VALUES_PERCENTAGE`,
`DISTINCT_VALUES_COUNT`, `UNIQUE_VALUES_COUNT`, `MIN`, `MAX`, `SUM`, `MEAN`, `MEDIAN`, `STANDARD_DEVIATION`) or
over each row (`:col1 > :val1`, `starts_with`, `ends_with`, `contains`, `matches`, `is_between … and …`,
`in (…)`, `is_missing`, combined with `and`, `or`, `not`). References resolve through `SubstitutionMap`, where a
backquoted value names a column, and `ColumnSelectors` apply the rule to each selected column. A row rule passes
when the passing rows meet its `Threshold`, 100% by default.

## Schedules

A schedule's `CronExpression` uses the AWS form `cron(minutes hours day-of-month month day-of-week year)`. Floci
checks schedules every 30 seconds and starts each listed job when its cron fires.
