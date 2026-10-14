# ElastiCache

**Protocol:** Query (XML) for management API + Redis RESP protocol for data plane
**Management Endpoint:** `POST http://localhost:4566/`
**Data Endpoint:** the group's AWS-shaped hostname and `Port` (TCP), see [Ports](#ports)

Floci manages real Valkey/Redis Docker containers and proxies TCP connections to them. This means any Redis client works : including IAM authentication.

## Supported Management Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ValidateIamAuthToken` | Validate an IAM auth token (data-plane auth) |
| `CreateReplicationGroup` | Start a new Redis/Valkey cluster; `AtRestEncryptionEnabled`, `KmsKeyId` (resolved to the key ARN), `SnapshotRetentionLimit`, `SnapshotWindow` and `Tags` are kept and returned, with the group `ARN` |
| `DescribeReplicationGroups` | List clusters and their connection info |
| `ModifyReplicationGroup` | Modify `SnapshotRetentionLimit` and `SnapshotWindow`, and the associated user groups |
| `DeleteReplicationGroup` | Stop and remove a cluster |
| `CreateUser` | Create an ElastiCache IAM user |
| `DescribeUsers` | List ElastiCache users |
| `ModifyUser` | Update user access strings |
| `DeleteUser` | Remove an ElastiCache user |
| `CreateCacheCluster` | - |
| `DescribeCacheClusters` | - |
| `DescribeCacheEngineVersions` | - |
| `ModifyCacheCluster` | Update node count and node type metadata |
| `DeleteCacheCluster` | - |
| `CreateCacheSubnetGroup` | Create persisted subnet metadata |
| `DescribeCacheSubnetGroups` | List persisted subnet metadata |
| `ModifyCacheSubnetGroup` | Update subnet metadata |
| `DeleteCacheSubnetGroup` | Delete subnet metadata |
| `IncreaseReplicaCount` | Update replication group replica metadata |
| `DecreaseReplicaCount` | Update replication group replica metadata |
| `ModifyReplicationGroupShardConfiguration` | Update shard metadata |
| `TestFailover` | - |
| `DescribeSnapshots` | List final snapshots created on delete |
| `DeleteSnapshot` | Delete a snapshot |
| `ListTagsForResource` | List tags for provisioned cache resources |
| `AddTagsToResource` | Add resource tags |
| `RemoveTagsFromResource` | Remove resource tags |
| `DescribeCacheParameterGroups` | List parameter groups, including the AWS defaults |
| `DescribeEvents` | Empty list (events are not recorded) |
| `CreateCacheParameterGroup` | Create a cache parameter group |
| `ModifyCacheParameterGroup` | Set parameters on a group |
| `DescribeCacheParameters` | List the parameters set on a group |
| `DeleteCacheParameterGroup` | Delete a cache parameter group |
| `CreateServerlessCache` | Create a Valkey, Redis OSS or Memcached serverless cache backed by a real engine container; answers `creating` and turns `available` once the engine is ready. `SubnetIds` and `SecurityGroupIds` must exist in EC2 (defaults: the default VPC's subnets and its `default` group), `KmsKeyId` must name a usable key, and `UserGroupId` raises `UserGroupNotFound` (user groups are not emulated) |
| `DescribeServerlessCaches` | List serverless caches with `NextToken` paging; `Endpoint` and `ReaderEndpoint` are `<name>-<6 chars>.serverless.<region code>.cache.amazonaws.com` on 6379/6380 (Memcached: 11211/11212), TLS only |
| `ModifyServerlessCache` | Update description, usage limits, security groups, snapshot settings and the redis-to-valkey engine upgrade; answers `modifying` |
| `DeleteServerlessCache` | Answers `deleting`, optionally takes `FinalSnapshotName`, then removes the cache and its container |
| `CreateServerlessCacheSnapshot` | Capture the cache's keyspace; answers `creating`, then `available` |
| `DescribeServerlessCacheSnapshots` | List snapshots, filtered by cache, name or `SnapshotType` |
| `DeleteServerlessCacheSnapshot` | Delete an `available` snapshot; a `creating` one raises `InvalidServerlessCacheSnapshotStateFault` |
| `CopyServerlessCacheSnapshot` | Copy a snapshot, keys included |
| `ExportServerlessCacheSnapshot` | Validates the snapshot, then refuses: snapshots are not kept as RDB files that could be written to S3 |
<!-- floci:actions:end -->

### Cluster Mode

`CreateReplicationGroup` provisions a real sharded Valkey cluster when the request asks for one:
`NumNodeGroups` greater than 1, a `default.*.cluster.on` parameter group, a custom parameter group
with `cluster-enabled` set to `yes`, or `ClusterMode=enabled`.

Floci starts one `--cluster-enabled` container per node : `NumNodeGroups × (1 + ReplicasPerNodeGroup)`
in total : forms the cluster (config epochs, MEET, slot assignment, replica attachment), and fronts
each node with its own auth-proxy port from the proxy port range. Nodes announce Floci's configured
hostname as their preferred endpoint (`cluster-announce-hostname` with
`cluster-preferred-endpoint-type hostname`, plus `cluster-announce-client-ipv4`/
`cluster-announce-port`), so `CLUSTER SLOTS`, `CLUSTER SHARDS` and `MOVED`/`ASK` redirects hand
clients the same name the `ConfigurationEndpoint` reports, while the cluster bus keeps using the
container network. Any cluster-aware Redis/Valkey client works against the reported
`ConfigurationEndpoint`.

Because clients must resolve the announced name to follow redirects, a `FLOCI_HOSTNAME` that only
resolves inside Floci's Docker network (such as the Compose service name `floci`) breaks
cluster-aware clients connecting from outside it. Set
`FLOCI_SERVICES_ELASTICACHE_CLUSTER_ANNOUNCE_HOSTNAME` to a universally resolvable name in that
case : the shipped `docker-compose.yml` uses `localhost.floci.io`, which public DNS resolves to
`127.0.0.1` on the host (reaching the published proxy ports) while the Compose network alias and
Floci's embedded DNS resolve it to the Floci container from inside Docker. Cluster-mode groups
then announce that name and report it as their `ConfigurationEndpoint`.

With `persistent`, `hybrid` or `wal` storage, every replication group and Memcached cluster is
re-provisioned from its persisted record on startup: containers are restarted, cluster-mode groups
are re-formed, and proxy ports are re-reserved. Caches restart empty, as on any Floci restart:
only the topology is persisted, never the keyspace.

Ports are re-reserved and records marked `creating` before Floci reports ready; the container
restarts and cluster formation run in the background so a slow Docker daemon cannot delay
readiness, and each record flips to `available` once its data plane is back. A replication group
whose data plane cannot be brought back is reported with status `create-failed` instead of
`available`, and its member clusters answer `DescribeCacheClusters` with `restore-failed`
(`CacheClusterStatus` has no `create-failed` value). A Memcached cluster that cannot be brought
back reports `restore-failed` directly.

Reporting a failed record as failed matters more than it looks. A record that is left unreconciled
keeps its `available` status with nothing behind the endpoint, so the control plane answers healthy
while every connection fails, and the proxy port it still advertises is free for the next create to
take: the old endpoint then reaches an unrelated cache rather than failing cleanly.

Restored groups and Memcached clusters keep their hostname and `Port`; only the host proxy is
re-bound to the new container.

A delete that arrives while a record is still `creating` wins. The restore takes the same
per-record monitor `DeleteReplicationGroup` and `DeleteCacheCluster` take, and skips its write-back
when the record is gone, so a group or cluster deleted in the first seconds after boot stays
deleted rather than coming back `available`. Any container the abandoned restore had already
started is stopped.

`DescribeReplicationGroups` reports the topology honestly: `ClusterEnabled`, one `NodeGroup` per
shard with its `Slots`, `NodeGroupMembers`, and `MemberClusters`. Each member also answers
`DescribeCacheClusters` (as on AWS), which is what terraform-provider-aws reads node type, engine
version and port from.

Cluster mode requires a Valkey 8.1+ image (the default `valkey/valkey:8` qualifies) for
`cluster-announce-client-ipv4` support. Each node consumes one port from the proxy range, so size
`FLOCI_SERVICES_ELASTICACHE_PROXY_BASE_PORT`/`_MAX_PORT` to the number of nodes you need.

`ModifyReplicationGroupShardConfiguration` reshards a cluster-mode group online. Adding shards
starts their nodes (with `ReplicasPerNodeGroup` replicas each), joins them to the running cluster,
and moves slots so every shard owns an equal contiguous range; keys in a moved slot migrate with it.
Decreasing `NodeGroupCount` requires `NodeGroupsToRemove` or `NodeGroupsToRetain`: the removed
shards hand their slots and keys to the remaining shards before their nodes are forgotten and
stopped. `ReshardingConfiguration` slot hints are not applied; slots are always spread evenly.

### Ports

Every cluster-mode-disabled replication group and every Memcached cluster has an AWS-shaped
hostname of its own under Floci's DNS suffix (`master.<group>.<hash>.use1.cache.localhost.floci.io`,
`<cluster>.<hash>.cfg.use1.cache.localhost.floci.io`).

Each group reports the `Port` the request named, or AWS's default (6379 for Redis/Valkey, 11211
for Memcached), whether Floci runs in Docker or on the host. The cache container listens on that
`Port` at its own address, so any number of groups use the same `Port` at once, as on AWS, and a
`Port` is never reserved by one group. Any `Port` from 1 to 65535 is accepted.

Containers Floci launches (VPC Lambda functions, ECS tasks) resolve a group's hostname through
Floci's embedded DNS (from source, with `FLOCI_DNS_SOURCE_ENABLED`) and reach the group on its
`Port`:

- A group without authentication, and every Memcached cluster, resolves to its own cache
  container.
- A group with an auth token or IAM authentication is reached through its auth proxy, so the
  authentication is enforced for container clients too. When the proxy holds the group's `Port` on
  Floci's address, the hostname resolves to Floci (from source, the source network helper relays
  the port to the host). Otherwise the group gets a relay container of its own on the container
  network, which listens on the `Port` and relays to the group's proxy, and the hostname resolves
  to that relay.

Host clients reach a group through its auth proxy on Floci's address (from source, 127.0.0.1,
which `*.localhost.floci.io` resolves to for every group). A replication group's proxy takes the
group's `Port` when it lies in the proxy range and no other group's proxy holds it, otherwise a
free port of the range; a Memcached cluster's relay takes the cluster's `Port` when no other relay
holds it. So on the host, `<hostname>:<Port>` reaches the first group created on that `Port`;
the others are served on their proxy port, which Floci logs when it creates the group. Host
clients cannot be told apart by hostname: the proxies speak plaintext RESP, so there is no TLS
server name to route on.

```bash
aws elasticache create-replication-group \
  --replication-group-id my-sharded-cache \
  --replication-group-description "Sharded dev cache" \
  --engine valkey \
  --cache-parameter-group-name default.valkey8.cluster.on \
  --num-node-groups 2 \
  --replicas-per-node-group 1 \
  --endpoint-url $AWS_ENDPOINT_URL

aws elasticache describe-replication-groups \
  --replication-group-id my-sharded-cache \
  --query 'ReplicationGroups[0].ConfigurationEndpoint' \
  --endpoint-url $AWS_ENDPOINT_URL

redis-cli -c -h localhost -p <configuration-endpoint-port> set mykey "hello"
```

### Cache Subnet Groups

A subnet group's VPC and each subnet's availability zone are read from the subnets themselves, as
AWS reads them, so the subnets have to exist in the emulator's EC2 first. Subnets that are unknown,
or that span more than one VPC, are refused the way AWS refuses them.

### Cache Parameter Groups

The `default.*` groups AWS publishes are listed for every family it supports, and cannot be modified
or deleted : AWS refuses those by the identifier rule, since a name it accepts cannot contain a dot.

floci does not carry AWS's per-family catalogue of parameter names, which runs to dozens per family.
It therefore stores whatever parameters a caller sets and reports them with source `user`, rather
than rejecting names a partial catalogue happens to be missing, which would refuse configurations
AWS accepts. `DescribeCacheParameters` returns those parameters; a request for `system` or
`engine-default` parameters returns none, and listings are unpaged.

A replication group that names a parameter group is refused with `CacheParameterGroupNotFound` when
no such group exists, and a parameter group still referenced by a replication group cannot be
deleted: `DeleteCacheParameterGroup` answers `InvalidCacheParameterGroupState` until that
replication group is gone. The reference counts from the moment `CreateReplicationGroup` accepts
the name, not from when the group is stored, so a delete that lands while that create is still
provisioning its container is refused too.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ELASTICACHE_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_ELASTICACHE_PROXY_BASE_PORT` | `6379` | First host port in the ElastiCache proxy range |
| `FLOCI_SERVICES_ELASTICACHE_PROXY_MAX_PORT` | `6399` | Last host port in the ElastiCache proxy range |
| `FLOCI_SERVICES_ELASTICACHE_DEFAULT_IMAGE` | `valkey/valkey:8` | Docker image for Redis/Valkey containers |

### Docker Compose

ElastiCache requires the Docker socket and port range exposure. For private registry authentication and other Docker settings see [Docker Configuration](../configuration/docker.md).

```yaml
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
      - "6379-6399:6379-6399"   # ElastiCache proxy ports
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_SERVICES_DOCKER_NETWORK: my-project_default
```

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a replication group (starts a Valkey container)
aws elasticache create-replication-group \
  --replication-group-id my-cache \
  --replication-group-description "Dev cache" \
  --endpoint-url $AWS_ENDPOINT_URL

# Get the connection port
PORT=$(aws elasticache describe-replication-groups \
  --replication-group-id my-cache \
  --query 'ReplicationGroups[0].NodeGroups[0].PrimaryEndpoint.Port' \
  --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Connect with redis-cli
redis-cli -h localhost -p $PORT ping

# Use from your application
redis-cli -h localhost -p $PORT set mykey "hello"
redis-cli -h localhost -p $PORT get mykey

# Delete the cluster
aws elasticache delete-replication-group \
  --replication-group-id my-cache \
  --endpoint-url $AWS_ENDPOINT_URL
```

## IAM Authentication

Floci supports ElastiCache IAM auth token validation. Create a user with access strings and validate tokens the same way real ElastiCache RBAC works.

```bash
# Create an ElastiCache user
aws elasticache create-user \
  --user-id alice \
  --user-name alice \
  --engine redis \
  --access-string "on ~* +@all" \
  --no-no-password-required \
  --endpoint-url $AWS_ENDPOINT_URL
```
