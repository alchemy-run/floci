# Amazon IVS (Low-Latency Streaming)

**Protocol:** REST JSON (`POST /<Operation>`, `/tags/<arn>`)
**Endpoint:** `http://localhost:4566/`, signed for `ivs`.

Channels stream for real. Floci runs one [MediaMTX](https://github.com/bluenviron/mediamtx) media server
(`bluenviron/mediamtx:1.21.3-ffmpeg`) for every channel, started when the first channel is created. A broadcaster
publishes RTMP to the channel's ingest endpoint, `rtmp://<ingestEndpoint>/app/<stream key>`, where the ingest endpoint
is the gateway host and `floci.services.ivs.rtmp-port` (1935). SRT goes to the channel's `srt.endpoint`
(`floci.services.ivs.srt-port`, 9000/udp) with `streamid=publish:app/<stream key>` and the channel's passphrase.
Only a channel's current stream key publishes; deleting the key disconnects its broadcaster. RTMPS is not served.

A stream is `LIVE` from when its media arrives, `HEALTHY` while bytes keep arriving and `STARVING` after three seconds
without them, and its viewer count is the number of HLS sessions playing it. Each broadcast is a stream session with
its ingest configuration (codecs, resolution, profile, level, sample rate, channels) and events. EventBridge receives
`IVS Stream State Change` (Session Created, Stream Start, Stream End, Session Ended), `IVS Stream Health Change`
(Starvation Start and End), `IVS Recording State Change` and `IVS Ad Break State Change` events from `aws.ivs`.

The playback URL, `<gateway>/api/video/v1/<region>.<account>.channel.<id>.m3u8`, plays the channel's HLS while it is
live. A private (`authorized`) channel takes `?token=<JWT>`: ES384-signed with the private key of one of the account's
playback key pairs, with `aws:channel-arn` and `exp`, and optionally `aws:access-control-allow-origin`,
`aws:strict-origin-enforcement`, `aws:viewer-id` and `aws:viewer-session-version`. Revoking a viewer's sessions stops
their playback, segments included. A playback restriction policy limits the `Origin` that may play; countries are
restricted only with `floci.services.ivs.viewer-country` set, as floci cannot locate viewers.

A channel with a recording configuration is recorded to its S3 bucket in IVS's layout:
`ivs/v1/<account>/<channel>/<y>/<m>/<d>/<h>/<mm>/<recording id>/` with `events/recording-started.json` and
`recording-ended.json` (or `recording-failed.json`), `media/hls/master.m3u8` and `byte-range-multivariant.m3u8`, the
source rendition's `playlist.m3u8` and `byte-range-variant.m3u8`, and thumbnails at the configured interval and
resolution (`media/thumbnails/`, `media/latest_thumbnail/thumb.jpg`). Floci does not transcode, so the recording has
the one source rendition, written when the recording ends. A stream that reconnects within the configuration's
reconnect window, at the same resolution, is merged into the same recording. A configuration whose bucket does not
exist is `CREATE_FAILED`.

`PutMetadata` is validated (1 KB, 5 a second per channel) and recorded on the stream's session; it is not inserted
into the HLS segments. `InsertAdBreak` needs a live channel with an ad configuration and publishes the ad break event.
Live channels cannot be updated or deleted. With `floci.services.ivs.mock: true`, or without Docker, channels are never
live.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `BatchGetChannel` | Get several channels by ARN, with per-ARN errors |
| `BatchGetStreamKey` | Get several stream keys by ARN, with per-ARN errors |
| `BatchStartViewerSessionRevocation` | Revoke several viewers' playback sessions |
| `CreateAdConfiguration` | Create an ad configuration for server-side ad insertion |
| `CreateChannel` | Create a channel and its stream key, with RTMP and SRT ingest endpoints |
| `CreatePlaybackRestrictionPolicy` | Create a policy limiting playback by origin and country |
| `CreateRecordingConfiguration` | Create an S3 recording configuration (CREATE_FAILED when the bucket is missing) |
| `CreateStreamKey` | Create a channel's stream key (one per channel) |
| `DeleteAdConfiguration` | Delete an ad configuration no channel uses |
| `DeleteChannel` | Delete a channel that is not live, and its stream keys |
| `DeletePlaybackKeyPair` | Delete a playback key pair |
| `DeletePlaybackRestrictionPolicy` | Delete a playback restriction policy no channel uses |
| `DeleteRecordingConfiguration` | Delete a recording configuration no channel uses |
| `DeleteStreamKey` | Delete a stream key, disconnecting its broadcaster |
| `GetAdConfiguration` | Get an ad configuration |
| `GetChannel` | Get a channel |
| `GetPlaybackKeyPair` | Get a playback key pair |
| `GetPlaybackRestrictionPolicy` | Get a playback restriction policy |
| `GetRecordingConfiguration` | Get a recording configuration |
| `GetStream` | Get a channel's live stream, its health and viewer count |
| `GetStreamKey` | Get a stream key and its value |
| `GetStreamSession` | Get a stream session with its ingest configuration, recording and events |
| `ImportPlaybackKeyPair` | Import a P-384 public key for signing playback tokens (3 per account) |
| `InsertAdBreak` | Start an ad break on a live channel and publish its event |
| `ListAdConfigurations` | List ad configurations |
| `ListChannels` | List channels, filtered by name, recording configuration or restriction policy |
| `ListPlaybackKeyPairs` | List playback key pairs |
| `ListPlaybackRestrictionPolicies` | List playback restriction policies |
| `ListRecordingConfigurations` | List recording configurations |
| `ListStreamKeys` | List a channel's stream keys |
| `ListStreamSessions` | List a channel's stream sessions, newest first |
| `ListStreams` | List live streams, filtered by health |
| `PutMetadata` | Validate and record timed metadata on a live stream (1 KB, 5 a second) |
| `StartViewerSessionRevocation` | Revoke a viewer's playback sessions up to a session version |
| `StopStream` | Disconnect a channel's broadcaster |
| `UpdateAdConfiguration` | Update an ad configuration |
| `UpdateChannel` | Update a channel that is not live |
| `UpdatePlaybackRestrictionPolicy` | Update a playback restriction policy |
| `ListTagsForResource` | List a resource's tags |
| `TagResource` | Add tags to a resource |
| `UntagResource` | Remove tags from a resource |
<!-- floci:actions:end -->
