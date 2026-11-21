# Amazon Kinesis Video Streams

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/`, signed for `kinesisvideo` (the control plane, media, archived media,
signaling and WebRTC storage APIs); playback under `/_floci/kinesisvideo/playback/`; signaling WebSockets at
`ws://localhost:4566/_floci/kinesisvideo/signaling/<region>`.

Floci runs Kinesis Video Streams with real media: producers send Matroska with `PutMedia`, consumers read it back,
and Floci transcodes it with ffmpeg to MP4 clips, HLS and DASH playback and images. Signaling channels relay WebRTC
offers, answers and ICE candidates between a master and its viewers.

## Streams and channels

Streams and signaling channels are `CREATING` for a transition, then `ACTIVE`; updates go `UPDATING` and deletes
`DELETING`. Every update takes the current version (`VersionMismatchException` otherwise) and is refused while the
resource isn't `ACTIVE`. Streams keep their retention (0 to 87,600 hours), media type, device name, KMS key (the
`aws/kinesisvideo` alias by default) and storage tier; data retention changes by `INCREASE` or `DECREASE`. Tags go on
streams and channels (50 at most). `GetDataEndpoint` and `GetSignalingChannelEndpoint` return Floci's own endpoints.

Notification configurations take an SNS topic, image generation configurations an S3 destination and sampling
interval; with both enabled, Floci writes images of new fragments to the bucket and publishes an
`IMAGE_GENERATED` notification. Edge configurations stay `SYNCING`, since Floci runs no edge agent. A channel's media
storage configuration names a stream with retention, which then maps to the channel.

## Media

`PutMedia` (`POST /putMedia` with `x-amzn-stream-name` or `x-amzn-stream-arn`, and `ABSOLUTE` or `RELATIVE`
fragment timecodes) reads a producer's Matroska stream: each cluster is a fragment, numbered, with its producer and
server timestamps and duration, acknowledged with `BUFFERING`, `RECEIVED` and, when the stream retains data,
`PERSISTED` events. A stream without retention keeps fragments a minute, for live readers only.

`GetMedia` returns fragments from its start selector (`EARLIEST`, `NOW`, a fragment number, a timestamp or a
continuation token), each a Matroska document with its `AWS_KINESISVIDEO_*` tags, follows the stream live until it's
idle, and ends with a continuation token. `ListFragments` selects by server or producer timestamp;
`GetMediaForFragmentList` returns the fragments named.

## Clips, images and playback

`GetClip` returns an MP4 of the fragments in a time range; `GetImages` returns JPEG or PNG images at the sampling
interval (`NO_MEDIA` where no fragment covers a time), scaled as asked. HLS sessions (fragmented MP4 or MPEG-TS) and
DASH sessions play `LIVE`, `LIVE_REPLAY` or `ON_DEMAND`, served by Floci until they expire. They need H.264 or H.265
video with codec private data (`UnsupportedStreamMediaTypeException` and `MissingCodecPrivateDataException`
otherwise), and streams without retention can only play live. Transcoding runs ffmpeg in the image the IVS media
server uses, started on first use.

## Signaling

The master connects with `X-Amz-ChannelARN`, viewers with `X-Amz-ClientId` too. A viewer's messages go to the master,
held until it connects within the channel's message TTL; the master's go to the viewer it names. A message that can't
be delivered is answered with a `STATUS_RESPONSE` when it carries a correlation ID, and a newer master connection
replaces the older one. `GetIceServerConfig` returns TURN servers with time-limited credentials; Floci relays no media,
so peers connect directly. `SendAlexaOfferToMaster` delivers an offer and waits for the master's answer.
`JoinStorageSession` needs the channel's media storage enabled and its master connected; Floci doesn't ingest WebRTC
media into the stream.

## Configuration

```yaml
floci:
  services:
    kinesis-video:
      enabled: true
      transition-millis: 2000
      get-media-idle-millis: 3000
      ffmpeg-image: bluenviron/mediamtx:1.21.3-ffmpeg
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `GetMedia` | Read fragments from a start selector, then follow the stream live |
| `GetMediaForFragmentList` | Read the fragments named |
| `GetClip` | An MP4 clip of the fragments in a time range |
| `CreateSignalingChannel` | Create a signaling channel |
| `CreateStream` | Create a stream |
| `DeleteEdgeConfiguration` | Delete a stream's edge configuration |
| `DeleteSignalingChannel` | Delete a signaling channel at its current version |
| `DeleteStream` | Delete a stream and its media |
| `DescribeEdgeConfiguration` | Describe a stream's edge configuration |
| `DescribeImageGenerationConfiguration` | Describe a stream's image generation |
| `DescribeMappedResourceConfiguration` | List the channels that store media in a stream |
| `DescribeMediaStorageConfiguration` | Describe a channel's media storage |
| `DescribeNotificationConfiguration` | Describe a stream's notifications |
| `DescribeSignalingChannel` | Describe a signaling channel |
| `DescribeStream` | Describe a stream |
| `DescribeStreamStorageConfiguration` | Describe a stream's storage tier |
| `GetDataEndpoint` | The endpoint of a stream's media APIs |
| `GetSignalingChannelEndpoint` | A channel's WSS, HTTPS and WebRTC endpoints |
| `ListEdgeAgentConfigurations` | List a hub device's edge configurations |
| `ListSignalingChannels` | List signaling channels |
| `ListStreams` | List streams |
| `ListTagsForResource` | List a stream's or channel's tags |
| `ListTagsForStream` | List a stream's tags |
| `StartEdgeConfigurationUpdate` | Set a stream's edge configuration |
| `TagResource` | Tag a stream or channel |
| `TagStream` | Tag a stream |
| `UntagResource` | Remove tags from a stream or channel |
| `UntagStream` | Remove tags from a stream |
| `UpdateDataRetention` | Increase or decrease a stream's retention |
| `UpdateImageGenerationConfiguration` | Generate images of new fragments to S3 |
| `UpdateMediaStorageConfiguration` | Store a channel's media in a stream |
| `UpdateNotificationConfiguration` | Notify an SNS topic of generated images |
| `UpdateSignalingChannel` | Change a channel's message TTL |
| `UpdateStream` | Change a stream's device name and media type |
| `UpdateStreamStorageConfiguration` | Change a stream's storage tier |
| `GetDASHStreamingSessionURL` | A DASH playback session served by Floci |
| `GetHLSStreamingSessionURL` | An HLS playback session served by Floci |
| `GetImages` | JPEG or PNG images at a sampling interval |
| `ListFragments` | List fragments by server or producer timestamp |
| `JoinStorageSession` | Join the master to a storage session |
| `JoinStorageSessionAsViewer` | Join a viewer to a storage session |
| `GetIceServerConfig` | TURN servers with time-limited credentials |
| `SendAlexaOfferToMaster` | Send an offer to the master and wait for its answer |
| `PutMedia` | Ingest a producer's Matroska stream, acknowledging each fragment |
<!-- floci:actions:end -->
