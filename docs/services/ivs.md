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

## Real-Time Streaming (stages)

IVS Real-Time's API signs as `ivs` too and is served alongside: stages, participant tokens, encoder, storage and
ingest configurations, public keys, compositions and participant replication. Stages are published to for real,
through the same media server, over the two protocols IVS takes from encoders other than its broadcast SDK:

- **WHIP**: a stage's `endpoints.whip` is `<gateway>/_floci/ivsrealtime/whip`. A WHIP client (OBS, ffmpeg's `whip`
  muxer, a browser) POSTs its SDP offer with a participant token as its bearer token. The token is verified as IVS
  does: an ES384 JWT that `CreateParticipantToken` issued, or one the application signed with a key it imported
  (`ImportPublicKey`, the key's ARN as `kid`), unexpired, for a stage that exists, of a participant not
  disconnected. A token without `PUBLISH`, or an offer without H.264 video, is refused with a `PUBLISH_ERROR`.
  WebRTC media flows on `floci.services.ivs.webrtc-port` (8189/udp).
- **RTMP and RTMPS**: an ingest configuration's stream key publishes to the stage's `endpoints.rtmp`
  (`floci.services.ivs.rtmp-port`) or `endpoints.rtmps` (`floci.services.ivs.rtmps-port`, 1937, with a certificate
  Floci's CA issues), as `rtmp(s)://<endpoint>/app/<stream key>`. An RTMPS configuration refuses plain RTMP unless
  `insecureIngest`, and a key whose configuration names no stage is refused.

A publisher joins the stage's session (started by its first participant, ended after its last) as a participant,
`CONNECTED` and `published` while its media flows, with `JOINED`, `PUBLISH_STARTED`, `PUBLISH_STOPPED` and `LEFT`
participant events and `IVS Stage Update` events (Participant Published, Unpublished, Publish Error, Replication Start
and End). `DisconnectParticipant` disconnects a participant and refuses it from then on.

A stage with an `autoParticipantRecordingConfiguration` records each publisher to its storage configuration's bucket
in IVS's layout, `<stage>/<session>/<participant>/<time>/`: `events/recording-started.json` and
`recording-ended.json`, `media/hls/multivariant.m3u8` and `high/playlist.m3u8` with fMP4 segments and
`EXT-X-PROGRAM-DATE-TIME`, and thumbnails, with `IVS Participant Recording State Change` events. A publisher that
returns within the reconnect window continues the same recording.

`StartComposition` mixes the stage's publishers (a grid, or picture-in-picture) with ffmpeg at its encoder
configuration's size, into a channel, which goes live as if broadcast to, and into an S3 composite recording at
`<stage>/<composition>/<destination>/composite/` with HLS, thumbnails and metadata files, re-laid out as publishers
come and go, with `IVS Composition State Change` events. A composition stops itself after
`floci.services.ivs.composition-idle-seconds` (60) without publishers, and is deleted five minutes after it stops.
`StartParticipantReplication` pulls a publisher into another stage, where it appears as a `REPLICA` participant.

The `events` endpoint, which IVS's broadcast SDKs use to subscribe, is not served: subscribing to a stage needs the
IVS SDKs' own signaling.

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
| `CreateEncoderConfiguration` | Create a composition encoder configuration (size, frame rate, bitrate) |
| `CreateIngestConfiguration` | Create an RTMP(S) ingest configuration and its stream key for a stage |
| `CreateParticipantToken` | Issue an ES384 participant token for a stage |
| `CreateStage` | Create a stage with its WHIP and RTMP(S) endpoints, participant recording and tokens |
| `CreateStorageConfiguration` | Create a storage configuration for an existing S3 bucket |
| `DeleteEncoderConfiguration` | Delete an encoder configuration no running composition uses |
| `DeleteIngestConfiguration` | Delete an ingest configuration (force disconnects a live one) |
| `DeletePublicKey` | Delete an imported public key; tokens it signed are refused |
| `DeleteStage` | Delete a stage, disconnecting its participants and failing its compositions |
| `DeleteStorageConfiguration` | Delete a storage configuration no stage or composition uses |
| `DisconnectParticipant` | Disconnect a participant and refuse it from then on |
| `GetComposition` | Get a composition and its destinations' states |
| `GetEncoderConfiguration` | Get an encoder configuration |
| `GetIngestConfiguration` | Get an ingest configuration, ACTIVE while publishing |
| `GetParticipant` | Get a participant of a session, with its protocol and recording |
| `GetPublicKey` | Get an imported public key |
| `GetStage` | Get a stage and its active session |
| `GetStageSession` | Get a stage session |
| `GetStorageConfiguration` | Get a storage configuration |
| `ImportPublicKey` | Import a P-384 public key for self-signed participant tokens (3 per account) |
| `ListCompositions` | List compositions, filtered by stage or encoder configuration |
| `ListEncoderConfigurations` | List encoder configurations |
| `ListIngestConfigurations` | List ingest configurations, filtered by stage or state |
| `ListParticipantEvents` | List a participant's events in a session |
| `ListParticipantReplicas` | List a participant's replications |
| `ListParticipants` | List a session's participants, with one filter |
| `ListPublicKeys` | List imported public keys |
| `ListStageSessions` | List a stage's sessions, newest first |
| `ListStages` | List stages |
| `ListStorageConfigurations` | List storage configurations |
| `RevokeConnectionToken` | Revoke a connection token |
| `StartComposition` | Mix a stage's publishers into a channel and/or an S3 composite recording |
| `StartParticipantReplication` | Replicate a publishing participant into another stage |
| `StopComposition` | Stop a composition, finishing its recordings |
| `StopParticipantReplication` | Stop a participant's replication |
| `UpdateIngestConfiguration` | Move an ingest configuration to another stage, or change redundant ingest |
| `UpdateStage` | Update a stage's name and participant recording |
<!-- floci:actions:end -->
