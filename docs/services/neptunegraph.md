# Amazon Neptune Analytics

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/graphs/...`, signed for `neptune-graph`

Floci runs each Neptune Analytics graph on its own Neo4j container, so graphs answer openCypher.

## Graphs

A graph is `CREATING` while its container starts, then `AVAILABLE`. Its name is unique, its provisioned memory is one of
Neptune Analytics' sizes, and its endpoint is `<id>.<region>.neptune-graph.localhost.floci.io`. Deletion protection is on
unless turned off. `StopGraph` stops the container (`STOPPING`, then `STOPPED`) and keeps the data; `StartGraph` starts
it again. `ResetGraph` empties the graph. `UpdateGraph` changes its connectivity, memory and deletion protection.
`DeleteGraph` and `ResetGraph` take a snapshot first unless `skipSnapshot` is set. Private graph endpoints are kept per
VPC. Graphs, snapshots, and import and export tasks take tags.

## Queries

`ExecuteQuery` runs openCypher with its parameters and answers `{"results": [...]}`. A node is answered with its
`~id`, `~entityType`, `~labels` and `~properties`; a relationship with its `~id`, `~start`, `~end`, `~type` and
`~properties`. `explainMode` answers the query plan. A query's timeout applies. Syntax errors are a
`ValidationException` with reason `MALFORMED_QUERY`, timeouts an `UnprocessableException` with reason `QUERY_TIMEOUT`.
`ListQueries` and `GetQuery` show running queries, and `CancelQuery` terminates one. `GetGraphSummary` counts nodes,
edges, labels and properties, and with `DETAILED` the node and edge structures. Neptune Analytics' graph algorithms
(`neptune.algo.*`) and vector search aren't available.

## Snapshots, imports and exports

A snapshot holds a graph's nodes and edges; `RestoreGraphFromSnapshot` creates a graph from it.

An import task reads every file under its S3 source through its role, which must exist and trust
`neptune-graph.amazonaws.com`. It takes these formats:

- **CSV:** Gremlin CSV, with `~id`, `~label`, `~from` and `~to` columns.
- **OPEN_CYPHER:** openCypher CSV, with `:ID`, `:LABEL`, `:START_ID`, `:END_ID` and `:TYPE` columns.
- **NTRIPLES:** IRIs and blank nodes become nodes, `rdf:type` their labels, and literals their typed properties.
- **PARQUET:** rows named like Gremlin CSV columns, read by Floci's DuckDB sidecar.

Typed property columns (`name:Int`, `tags:String[]`) are typed. `CreateGraphUsingImportTask` creates the graph it loads
into; `StartImportTask` loads into an empty graph. The task is `INITIALIZING`, then `ANALYZING_DATA`, then `IMPORTING`,
then `SUCCEEDED`. With `failOnError` (the default), any file error fails the task, and a graph the task created is then
`FAILED`. Imported nodes and edges keep their source ids as a `~id` property.

An export task writes `nodes/` and `edges/` files under `<destination>/<taskId>/` as openCypher CSV, or as Parquet
through DuckDB. Its filter keeps the labels it names, with their properties renamed from their sources.

## Configuration

```yaml
floci:
  services:
    neptune-graph:
      enabled: true
      query-engine: neo4j        # or none: graphs keep their metadata but run no queries
      image: neo4j:5-community   # the image graphs run on
      ready-seconds: 120         # how long a graph's container may take to answer
      transition-millis: 2000
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CancelExportTask` | Cancel a running export task |
| `CancelImportTask` | Cancel a running import task |
| `CancelQuery` | Terminate a running query |
| `CreateGraph` | Create a graph on its own Neo4j container |
| `CreateGraphSnapshot` | Snapshot a graph's nodes and edges |
| `CreateGraphUsingImportTask` | Create a graph and import into it |
| `CreatePrivateGraphEndpoint` | Create a graph's endpoint in a VPC |
| `DeleteGraph` | Delete a graph, with a final snapshot unless skipped |
| `DeleteGraphSnapshot` | Delete graph snapshot |
| `DeletePrivateGraphEndpoint` | Delete private graph endpoint |
| `ExecuteQuery` | Run openCypher |
| `GetExportTask` | Get export task |
| `GetGraph` | Get graph |
| `GetGraphSnapshot` | Get graph snapshot |
| `GetGraphSummary` | Count a graph's nodes, edges, labels and properties |
| `GetImportTask` | Get import task |
| `GetPrivateGraphEndpoint` | Get private graph endpoint |
| `GetQuery` | A running query |
| `ListExportTasks` | List export tasks |
| `ListGraphSnapshots` | List graph snapshots |
| `ListGraphs` | List graphs |
| `ListImportTasks` | List import tasks |
| `ListPrivateGraphEndpoints` | List a graph's private endpoints |
| `ListQueries` | A graph's running queries |
| `ListTagsForResource` | List a resource's tags |
| `ResetGraph` | Empty a graph, with a snapshot unless skipped |
| `RestoreGraphFromSnapshot` | Create a graph from a snapshot |
| `StartExportTask` | Export a graph to S3 as CSV or Parquet |
| `StartGraph` | Start a stopped graph |
| `StartImportTask` | Import into an empty graph |
| `StopGraph` | Stop a graph, keeping its data |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags |
| `UpdateGraph` | Update a graph's connectivity, memory and deletion protection |
<!-- floci:actions:end -->
