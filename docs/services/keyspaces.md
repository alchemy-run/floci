# Amazon Keyspaces

**Protocol:** JSON 1.0 or Smithy RPC v2 CBOR (control plane), JSON 1.0 (streams), and the Cassandra native protocol
(CQL)
**Endpoints:** `POST http://localhost:4566/` with `X-Amz-Target: KeyspacesService.<Action>` or
`KeyspacesStreams.<Action>`, signed for `cassandra`; CQL on `localhost:9142`.

Floci runs Keyspaces as a working Cassandra-compatible database: the control plane creates keyspaces, tables and
types, the CQL endpoint reads and writes their rows, CDC streams carry the writes, and point-in-time recovery restores
a table to any second of the last 35 days.

## Keyspaces, tables and types

Keyspaces are single-Region or multi-Region (the Region list must include the current Region). The system keyspaces
(`system`, `system_schema`, `system_schema_mcs`, `system_multiregion_info`) are listed and can't be changed. Tables
are `CREATING` for a transition, then `ACTIVE`; updates go `UPDATING`, deletes `DELETING`, restores `RESTORING`.
Schemas are checked as Cassandra checks them: keys must be columns and can't be non-frozen collections, counters or
durations; static columns need clustering columns; counter tables hold only counters; nested collections and
user-defined types must be frozen; user-defined types must exist. `UpdateTable` adds columns and changes capacity,
encryption (a customer managed key must exist), point-in-time recovery, TTL (which can't be turned off), the default
TTL, client-side timestamps, warm throughput, replicas and CDC. A type used by a table or another type can't be
deleted.

Auto scaling registers the table's read and write capacity with Application Auto Scaling (namespace `cassandra`,
resource `keyspace/<keyspace>/table/<table>`) with a target tracking policy, as Keyspaces does; scaling sets the
table's provisioned capacity, and `GetTableAutoScalingSettings` reads the settings back.

## CQL

The CQL endpoint speaks protocol v3 and v4, with TLS when Floci serves TLS. Clients authenticate as Keyspaces requires:
with an IAM user's service-specific credential for `cassandra.amazonaws.com` (`PlainTextAuthProvider`), or with the
SigV4 authentication plugin. It serves the `system` and `system_schema` tables drivers read at connect (and
`system_schema_mcs.tables`, whose `status` tells a client when a new table is `ACTIVE`).

`SELECT` reads partitions by key (`=` or `IN`) or token range, filters clustering columns, and filters other columns
only with `ALLOW FILTERING`; rows come in token order and clustering order (`ORDER BY` reverses it), with `DISTINCT`,
`PER PARTITION LIMIT`, `LIMIT`, paging, `count(*)`, `writetime()`, `ttl()`, `token()`, `toJson()`, `SELECT JSON` and
the time and blob conversion functions. `INSERT` (including `INSERT JSON`), `UPDATE` and `DELETE` need the whole
primary key (`DELETE` takes a partition or a clustering range too); writes are last-write-wins, expire by `USING TTL`
or the table's default TTL (both need the table's TTL enabled), and support counters, list, set and map operations,
user-defined type fields, lightweight transactions (`IF NOT EXISTS`, `IF EXISTS`, `IF` conditions, returning
`[applied]`) and batches of up to 30 statements. `USING TIMESTAMP` needs client-side timestamps. Writes take
`LOCAL_QUORUM` and reads `ONE`, `LOCAL_ONE` or `LOCAL_QUORUM`, as Keyspaces requires. `CREATE`, `ALTER` and `DROP` of
keyspaces, tables (with Keyspaces' `custom_properties`) and types go through the control plane; `TRUNCATE` is refused,
as Keyspaces refuses it.

## Streams and restore

A table with CDC has a stream (`latestStreamArn`) with one shard, `ENABLING` and then `ENABLED`. Its records are the
writes, deletes and TTL expiries made while it's enabled, kept a day, with the keys and the new and old images its view
type asks for. Turning CDC off ends the stream; turning it on again starts a new one.

A table with point-in-time recovery keeps its mutations for 35 days. `RestoreTable` creates the target table
(`RESTORING`, then `ACTIVE`) with the source's rows as they were at the restore time (now by default), the source's
settings with the given overrides, and the source's user-defined types in the target keyspace.

## Configuration

```yaml
floci:
  services:
    keyspaces:
      enabled: true
      transition-millis: 2000
      max-keyspaces: 256
      max-tables: 256
      cql-port: 9142
      cql-host: 0.0.0.0
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateKeyspace` | Create a single- or multi-Region keyspace |
| `CreateTable` | Create a table, checking its schema as Cassandra does |
| `CreateType` | Create a user-defined type |
| `DeleteKeyspace` | Delete a keyspace with its tables and types |
| `DeleteTable` | Delete a table and its rows |
| `DeleteType` | Delete a type no table or type uses |
| `GetKeyspace` | Describe a keyspace and its Regions' status |
| `GetTable` | Describe a table, its settings, replicas and stream |
| `GetTableAutoScalingSettings` | Read a table's auto scaling from Application Auto Scaling |
| `GetType` | Describe a type and what refers to it |
| `ListKeyspaces` | List keyspaces, the system keyspaces first |
| `ListTables` | List a keyspace's tables |
| `ListTagsForResource` | List a keyspace's, table's or stream's tags |
| `ListTypes` | List a keyspace's types |
| `RestoreTable` | Restore a table's rows as of a time into a new table |
| `TagResource` | Tag a keyspace, table or stream |
| `UntagResource` | Remove tags |
| `UpdateKeyspace` | Add Regions to a keyspace |
| `UpdateTable` | Add columns and change a table's settings, auto scaling and CDC |
| `GetRecords` | Read a stream's change records |
| `GetShardIterator` | Start reading a stream's shard |
| `GetStream` | Describe a CDC stream and its shard |
| `ListStreams` | List CDC streams |
<!-- floci:actions:end -->
