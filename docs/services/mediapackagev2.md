# AWS Elemental MediaPackage v2

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/channelGroup/...`, signed for `mediapackagev2`

Floci runs MediaPackage v2 origins: a channel's ingest endpoints take HLS from an encoder, and its origin endpoints
serve that content as TS HLS, CMAF HLS and DASH.

## Channel groups, channels and origin endpoints

A channel group gets an egress domain, `<id>.egress.mediapackagev2.<region>.localhost.floci.io`. A channel gets two
ingest endpoints, `<id>-1.ingest...` and `<id>-2.ingest...`, each with the path `/in/v1/<group>/<index>/<channel>/`.
An origin endpoint's HLS, low-latency HLS, DASH and MSS manifests get URLs on the group's egress domain under
`/out/v1/<group>/<channel>/<endpoint>/`. Requests to those hosts on Floci's port reach the channel; containers Floci
launches reach them too. Manifests are checked against the endpoint's container type, and so is its encryption
method. The container type can't change. Channel and origin endpoint policies are kept. A group can't be deleted while
it has channels, nor a channel while it has origin endpoints. Every resource takes tags.

## Ingest and egress

An encoder writes HLS to an ingest endpoint with `PUT` or `POST`, chunked or not, the way MediaLive and ffmpeg do: a
multivariant playlist, a media playlist per rendition, and its segments. Floci keeps the segments for the longest
startover or manifest window of the channel's origin endpoints, and at least `minimum-retention-seconds`.

Origin endpoints serve what was ingested:

- **TS:** a multivariant playlist, and per rendition a media playlist of its segments as ingested.
- **CMAF:** each segment remuxed with ffmpeg into an init section and a fragment, served from HLS playlists with
  `EXT-X-MAP` and from a DASH manifest with a segment timeline.

A manifest's window, its filter's time delay and its program date time interval apply. Resetting a channel or
origin endpoint drops what was received before the reset. Segments aren't encrypted, and ISM (Microsoft Smooth)
endpoints answer `501`. CMAF needs Docker: ffmpeg runs in the image Kinesis Video Streams transcodes with.

## Harvest jobs

A harvest job is `QUEUED` until its window ends. It then writes each harvested manifest's playlists and the
window's segments to its S3 destination as a VOD asset, and is `COMPLETED`, or `FAILED` when no content was received
in the window. Jobs can be cancelled while queued. Status changes go to the default event bus as `MediaPackageV2
HarvestJob Notification` events from `aws.mediapackagev2`.

## Configuration

```yaml
floci:
  services:
    media-package-v2:
      enabled: true
      transition-millis: 2000          # how long a harvest job waits after its window ends
      minimum-retention-seconds: 120   # how long ingested content is kept at least
      max-channel-groups: 3
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CancelHarvestJob` | Cancel a queued harvest job |
| `CreateChannel` | Create a channel with two ingest endpoints |
| `CreateChannelGroup` | Create a channel group with its egress domain |
| `CreateHarvestJob` | Schedule a window's harvest to S3 |
| `CreateOriginEndpoint` | Create an origin endpoint serving TS, CMAF or ISM |
| `DeleteChannel` | Delete a channel without origin endpoints |
| `DeleteChannelGroup` | Delete a channel group without channels |
| `DeleteChannelPolicy` | Delete channel policy |
| `DeleteOriginEndpoint` | Delete origin endpoint |
| `DeleteOriginEndpointPolicy` | Delete origin endpoint policy |
| `GetChannel` | Get channel |
| `GetChannelGroup` | Get channel group |
| `GetChannelPolicy` | Get channel policy |
| `GetHarvestJob` | Get harvest job |
| `GetOriginEndpoint` | Get origin endpoint |
| `GetOriginEndpointPolicy` | Get origin endpoint policy |
| `ListChannelGroups` | List channel groups |
| `ListChannels` | List channels |
| `ListHarvestJobs` | List a group's harvest jobs |
| `ListOriginEndpoints` | List origin endpoints |
| `ListTagsForResource` | List a resource's tags |
| `PutChannelPolicy` | Put channel policy |
| `PutOriginEndpointPolicy` | Put origin endpoint policy |
| `ResetChannelState` | Drop a channel's ingested content |
| `ResetOriginEndpointState` | Serve only content received after now |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags |
| `UpdateChannel` | Update channel |
| `UpdateChannelGroup` | Update channel group |
| `UpdateOriginEndpoint` | Update an origin endpoint; its container type stays |
<!-- floci:actions:end -->
