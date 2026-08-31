# MediaPackage v2

**Protocol:** REST JSON
**Endpoint prefix:** `mediapackagev2`
**Paths:** `/channelGroup/*`

Floci emulates AWS Elemental MediaPackage v2 so Alchemy can create channel
groups, channels, and origin endpoints locally. The Sweep test plants a
group → channel → origin-endpoint chain and reaps children before the
parent.

No real packaging or ingest is performed. Ingest and HLS URLs are
synthetic hostnames on `*.mediapackagev2.{region}.amazonaws.com`.

## Supported Operations

| Operation | Notes |
|-----------|-------|
| `CreateChannelGroup` / `GetChannelGroup` / `ListChannelGroups` / `UpdateChannelGroup` / `DeleteChannelGroup` | Deleting a group that still has channels is `ConflictException`. Deleting a missing group succeeds. |
| `CreateChannel` / `GetChannel` / `ListChannels` / `UpdateChannel` / `DeleteChannel` | Two synthetic ingest endpoints. Deleting a channel that still has origin endpoints is `ConflictException`. |
| `CreateOriginEndpoint` / `GetOriginEndpoint` / `ListOriginEndpoints` / `UpdateOriginEndpoint` / `DeleteOriginEndpoint` | HLS/DASH/MSS manifests get synthetic `Url`s. |
| `PutChannelPolicy` / `GetChannelPolicy` / `DeleteChannelPolicy` | Stored on the channel |
| `PutOriginEndpointPolicy` / `GetOriginEndpointPolicy` / `DeleteOriginEndpointPolicy` | Stored on the endpoint |
| `ResetChannelState` / `ResetOriginEndpointState` | Returns `ResetAt` immediately |
| `CreateHarvestJob` / `GetHarvestJob` / `ListHarvestJobs` / `CancelHarvestJob` | Jobs complete immediately (`COMPLETED`) |
| `TagResource` / `UntagResource` / `ListTagsForResource` | Shared `/tags/{arn}` |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_MEDIAPACKAGEV2_ENABLED` | `true` | Enable or disable the service |
