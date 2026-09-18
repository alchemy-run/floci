# Glue

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`

Floci emulates the AWS Glue Data Catalog, ETL jobs, crawlers, connections, and Glue Schema Registry.

## Supported Actions

### Jobs

| Action | Description |
|--------|-------------|
| CreateJob | Stores a job definition and optional tags. |
| GetJob | Returns a stored job definition. |
| GetJobs | Lists stored job definitions. |
| UpdateJob | Replaces the stored job update payload. |
| DeleteJob | Deletes a job, its runs, bookmark, and tags. |
| StartJobRun | Starts a job run (`jr_…` id) that completes immediately as `SUCCEEDED`. |
| GetJobRun | Returns a stored job run. |
| GetJobRuns | Lists runs for a job. |
| BatchStopJobRun | Marks running job runs `STOPPED`. |
| GetJobBookmark | Returns the stored bookmark, or `EntityNotFoundException` (400) if the job has never run. |
| ResetJobBookmark | Resets the stored bookmark, or `EntityNotFoundException` (400) if none exists. |

### Crawlers

| Action | Description |
|--------|-------------|
| CreateCrawler | Stores a crawler in `READY` state. |
| GetCrawler | Returns a stored crawler. |
| GetCrawlers | Lists stored crawlers. |
| UpdateCrawler | Updates a crawler that is not `RUNNING`. Create/Update accept `Schedule` as a cron string; GetCrawler returns `{ScheduleExpression, State}`. |
| DeleteCrawler | Deletes a crawler that is not `RUNNING`. |
| StartCrawler | Sets crawler state to `RUNNING`. |
| StopCrawler | Sets crawler state to `READY`. Idle crawlers raise `CrawlerNotRunningException` (400). |

### Connections

| Action | Description |
|--------|-------------|
| CreateConnection | Stores a connection and optional tags. |
| GetConnection | Returns a stored connection (`HidePassword` strips `PASSWORD`). |
| GetConnections | Lists stored connections. |
| UpdateConnection | Updates a stored connection. |
| DeleteConnection | Deletes a connection and its tags. |

`TagResource` / `UntagResource` / `GetTags` apply to job, crawler, connection, database, and table ARNs as well as Schema Registry resources.

### Data Catalog

#### Databases

| Action | Description |
|--------|-------------|
| CreateDatabase | Creates a database in the local Glue Data Catalog. |
| GetDatabase | Returns a stored Data Catalog database. |
| GetDatabases | Lists databases in the local Glue Data Catalog. |
| DeleteDatabase | Deletes a database from the local Glue Data Catalog. |

#### Tables

| Action | Description |
|--------|-------------|
| CreateTable | Creates a table definition in the local Glue Data Catalog. |
| GetTable | Returns a stored table definition and resolves schema references when possible. |
| GetTables | Lists table definitions for a database. |
| UpdateTable | Replaces a table definition; the previous version is archived unless `SkipArchive` is set. |
| DeleteTable | Deletes a table definition from a database. |
| BatchDeleteTable | Deletes several tables of a database, reporting the missing ones in `Errors`. |
| GetTableVersions | Lists the current and archived versions of a table, newest first. |
| GetTableVersion | Returns one version by `VersionId`, or the current version when none is given. |
| DeleteTableVersion | Deletes an archived version; the current version cannot be deleted, as on AWS. |
| BatchDeleteTableVersion | Deletes several archived versions, reporting the ones not deleted in `Errors`. |
| SearchTables | Searches every table of the catalog by `SearchText` (substring, or exact when quoted), property `Filters` (string keys use the tokenised match of the API reference, time keys honour `Comparator`, other keys read table parameters) and `SortCriteria`, paged by `MaxResults` and `NextToken`. |

#### Partitions

| Action | Description |
|--------|-------------|
| CreatePartition | Creates a partition for a Data Catalog table (`AlreadyExistsException` on duplicate). |
| GetPartition | Returns a stored partition. |
| GetPartitions | Lists partitions stored for a Data Catalog table. |
| UpdatePartition | Updates a stored partition. |
| DeletePartition | Deletes a stored partition. |
| BatchCreatePartition | Creates partitions and reports per-item `AlreadyExistsException` errors. |
| BatchGetPartition | Returns the partitions that exist (missing keys are omitted). |
| BatchUpdatePartition | Updates partitions and reports per-item `EntityNotFoundException` errors. |
| BatchDeletePartition | Deletes up to 25 partitions, reporting the ones not found in `Errors`. |
| CreatePartitionIndex | Registers a partition index on a table. Keys must name partition columns, and a table holds at most 3 indexes. The index reports `CREATING` before `ACTIVE`. |
| GetPartitionIndexes | Lists a table's partition indexes, each with its keys resolved to name and type. |
| DeletePartitionIndex | Removes a partition index from a table. The index reports `DELETING` before it disappears. |

Partition indexes carry the AWS lifecycle. A new index reports `CREATING` and then `ACTIVE`; a
deleted one reports `DELETING` and then disappears. As on AWS, only one index per table may be
created or deleted at a time, and an index that is still `CREATING` cannot be deleted yet.

The transitions are driven by reads rather than by a timer, so they are deterministic: each
`GetPartitionIndexes` reports the current state and settles it, and a client that polls (as the
Terraform provider does) converges on its next call. The `FAILED` state is not emulated, since it
only arises from a backfill failure.

#### User-defined Functions

| Action | Description |
|--------|-------------|
| CreateUserDefinedFunction | Creates a user-defined function in the Data Catalog. |
| GetUserDefinedFunction | Returns a stored user-defined function. |
| GetUserDefinedFunctions | Lists user-defined functions for a database. |
| UpdateUserDefinedFunction | Updates a stored user-defined function. |
| DeleteUserDefinedFunction | Deletes a user-defined function from a database. |

### Schema Registry

#### Registries

| Action | Description |
|--------|-------------|
| CreateRegistry | Creates a schema registry. |
| GetRegistry | Returns a stored schema registry. |
| ListRegistries | Lists schema registries. |
| UpdateRegistry | Updates a schema registry's stored metadata. |
| DeleteRegistry | Deletes a schema registry. |

#### Schemas

| Action | Description |
|--------|-------------|
| CreateSchema | Creates a schema in a registry with the supplied data format and compatibility mode. |
| GetSchema | Returns a stored schema. |
| ListSchemas | Lists schemas in a registry. |
| UpdateSchema | Updates schema metadata or compatibility settings. |
| DeleteSchema | Deletes a schema from a registry. |

#### Versions

| Action | Description |
|--------|-------------|
| RegisterSchemaVersion | Registers a new schema version definition. |
| GetSchemaByDefinition | Finds a schema version that matches a supplied definition. |
| GetSchemaVersion | Returns a stored schema version. |
| ListSchemaVersions | Lists versions for a schema. |
| DeleteSchemaVersions | Deletes schema versions from a schema. |
| GetSchemaVersionsDiff | Returns the diff between two schema version numbers. |
| CheckSchemaVersionValidity | Validates a schema definition for the supplied data format. |

#### Metadata and Tags

| Action | Description |
|--------|-------------|
| PutSchemaVersionMetadata | Adds metadata to a schema version. |
| RemoveSchemaVersionMetadata | Removes metadata from a schema version. |
| QuerySchemaVersionMetadata | Returns metadata stored for matching schema versions. |
| TagResource | Adds tags to a Glue schema registry resource. |
| UntagResource | Removes tags from a Glue schema registry resource. |
| GetTags | Returns tags stored for a Glue schema registry resource. |

Supported schema formats are `AVRO`, `JSON`, and `PROTOBUF`. Compatibility modes are `NONE`, `DISABLED`, `BACKWARD`, `BACKWARD_ALL`, `FORWARD`, `FORWARD_ALL`, `FULL`, and `FULL_ALL`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_GLUE_ENABLED` | `true` | Enable or disable the service |

## Integration with Athena

The Glue Data Catalog is automatically used by **Athena** to resolve table names to S3 locations and formats. When you submit an Athena query, Floci reads all Glue tables for the target database and generates DuckDB views on top of the underlying S3 objects before executing the SQL.

Tables can reference a Schema Registry schema version through `StorageDescriptor.SchemaReference`. On `GetTable` and `GetTables`, Floci resolves the schema definition into Glue columns when possible.

A table whose `Parameters.table_type` is `ICEBERG` (case-insensitive), as set by `pyiceberg`'s `GlueCatalog` and AWS's own Glue-Iceberg integration, is read via `iceberg_scan` against `Parameters.metadata_location` instead, following the table's real manifest list rather than its `StorageDescriptor`. See [Athena's format inference](athena.md#format-inference) for the full explanation.

For every other table, the DuckDB read function is selected based on the table's `StorageDescriptor.InputFormat` and `StorageDescriptor.SerdeInfo.SerializationLibrary`:

| Condition | DuckDB function |
|---|---|
| `InputFormat` or `SerializationLibrary` contains `parquet` | `read_parquet` |
| `InputFormat` or `SerializationLibrary` contains `json` | `read_json_auto` |
| `InputFormat` contains `hive` | `read_json_auto` |
| Anything else | `read_csv_auto` |

## Data Catalog Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a database
aws glue create-database \
  --database-input '{"Name": "analytics"}' \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a JSON table (standard AWS format for NDJSON data)
aws glue create-table \
  --database-name analytics \
  --table-input '{
    "Name": "orders",
    "StorageDescriptor": {
      "Location": "s3://my-bucket/orders/",
      "InputFormat": "org.apache.hadoop.mapred.TextInputFormat",
      "OutputFormat": "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat",
      "SerdeInfo": {
        "SerializationLibrary": "org.openx.data.jsonserde.JsonSerDe"
      },
      "Columns": [
        {"Name": "id",     "Type": "int"},
        {"Name": "amount", "Type": "double"}
      ]
    }
  }' \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a Parquet table
aws glue create-table \
  --database-name analytics \
  --table-input '{
    "Name": "events",
    "StorageDescriptor": {
      "Location": "s3://my-bucket/events/",
      "InputFormat": "org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat",
      "SerdeInfo": {
        "SerializationLibrary": "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe"
      },
      "Columns": [
        {"Name": "event_id", "Type": "string"},
        {"Name": "ts",       "Type": "bigint"}
      ]
    }
  }' \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Schema Registry Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

cat > /tmp/order.avsc <<'JSON'
{"type":"record","name":"Order","namespace":"example","fields":[{"name":"id","type":"long"}]}
JSON

cat > /tmp/order-v2.avsc <<'JSON'
{"type":"record","name":"Order","namespace":"example","fields":[{"name":"id","type":"long"},{"name":"amount","type":["null","double"],"default":null}]}
JSON

aws glue create-registry \
  --registry-name local-registry \
  --endpoint-url $AWS_ENDPOINT_URL

aws glue create-schema \
  --registry-id RegistryName=local-registry \
  --schema-name orders \
  --data-format AVRO \
  --compatibility BACKWARD \
  --schema-definition file:///tmp/order.avsc \
  --endpoint-url $AWS_ENDPOINT_URL

aws glue register-schema-version \
  --schema-id RegistryName=local-registry,SchemaName=orders \
  --schema-definition file:///tmp/order-v2.avsc \
  --endpoint-url $AWS_ENDPOINT_URL

aws glue list-schema-versions \
  --schema-id RegistryName=local-registry,SchemaName=orders \
  --endpoint-url $AWS_ENDPOINT_URL
```
