# AWS CodeConnections

**Protocol:** JSON 1.0 and Smithy RPC v2 CBOR
**Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: CodeConnections_20231201.<Operation>` (or `com.amazonaws.codeconnections.CodeConnections_20231201.<Operation>`), or `POST /service/CodeConnections_20231201/operation/<Operation>` with a CBOR body, signed for `codeconnections`.

Floci runs AWS CodeConnections: hosts for self-managed providers, connections, repository links, and Git sync configurations.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateConnection` | Create a PENDING connection |
| `CreateHost` | Create a host for a self-managed provider |
| `CreateRepositoryLink` | Link a repository through an AVAILABLE connection |
| `CreateSyncConfiguration` | Sync a stack with a file in a linked repository |
| `DeleteConnection` | Delete a connection |
| `DeleteHost` | Delete a host with no connections |
| `DeleteRepositoryLink` | Delete a link with no sync configurations |
| `DeleteSyncConfiguration` | Delete a sync configuration |
| `GetConnection` | Describe a connection |
| `GetHost` | Describe a host |
| `GetRepositoryLink` | Describe a repository link |
| `GetRepositorySyncStatus` | A branch's latest sync (none until one runs) |
| `GetResourceSyncStatus` | A stack's latest sync (none until one runs) |
| `GetSyncBlockerSummary` | A sync configuration's blockers |
| `GetSyncConfiguration` | Describe a sync configuration |
| `ListConnections` | Page through connections, by provider or host |
| `ListHosts` | Page through hosts |
| `ListRepositoryLinks` | Page through repository links |
| `ListRepositorySyncDefinitions` | A repository's sync definitions |
| `ListSyncConfigurations` | Page through a link's sync configurations |
| `ListTagsForResource` | List tags |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags |
| `UpdateHost` | Change a host's endpoint or VPC configuration |
| `UpdateRepositoryLink` | Change a link's connection or key |
| `UpdateSyncBlocker` | Resolve a sync blocker |
| `UpdateSyncConfiguration` | Change a sync configuration |
<!-- floci:actions:end -->

## Connections and hosts

A connection is `PENDING` until its handshake with the provider, which on AWS happens in the console, outside the API. Floci can't complete a handshake, so every account and Region starts with a demo GitHub connection that is already `AVAILABLE`, with a repository link on it:

| | ID | |
| --- | --- | --- |
| Connection | `arn:aws:codeconnections:<region>:<account>:connection/f10c1000-0000-4000-8000-0000000000c1` | `floci-demo`, GitHub, `AVAILABLE` |
| Repository link | `f10c1000-0000-4000-8000-0000000000c2` | `floci-demo/app` |

Connections to GitHub, Bitbucket, GitLab and Azure DevOps take a `ProviderType`. Connections to GitHub Enterprise Server and GitLab self-managed take the `HostArn` of a host for the provider's endpoint, and get their provider type from it.

A host is `PENDING` until its app is set up with the provider, and its endpoint and VPC configuration can be updated meanwhile. A host with connections can't be deleted (`ResourceUnavailableException`).

## Repository links and Git sync

A repository link needs an `AVAILABLE` connection. Linking a repository through a `PENDING` connection fails with `InvalidInputException`, and linking the same repository twice fails with `ResourceAlreadyExistsException`.

A sync configuration ties a CloudFormation stack (`CFN_STACK_SYNC`, by resource name) to a branch and a config file in a linked repository. A link with sync configurations can't be deleted (`SyncConfigurationStillExistsException`). `ListRepositorySyncDefinitions` lists each configuration's branch, config-file directory and target.

Floci can't reach the providers, so no commit is ever synced. `GetRepositorySyncStatus` and `GetResourceSyncStatus` answer `ResourceNotFoundException` until a sync has run, as AWS answers before a first sync. A configuration has no sync blockers.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_CODE_CONNECTIONS_ENABLED` | `true` | Enable or disable AWS CodeConnections |

For the upstream API shape, see the [AWS CodeConnections API Reference](https://docs.aws.amazon.com/codeconnections/latest/APIReference/Welcome.html).
