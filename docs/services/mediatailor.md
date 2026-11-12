# AWS Elemental MediaTailor

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/playbackConfiguration`, `/channel/...`, `/sourceLocation/...`, signed for `mediatailor`

Floci runs MediaTailor's ad insertion and channel assembly. Players reach an account's playback host,
`<id>.mediatailor.<region>.localhost.floci.io`, and its channel assembly host,
`<id>.channel-assembly.mediatailor.<region>.localhost.floci.io`, on Floci's port.

## Playback configurations

A playback configuration gets its session initialization, HLS and DASH endpoints on the playback host. `Put` replaces
every setting it's given and clears the ones it isn't; log configuration and tags carry over. Logging, prefetch
schedules and functions are kept per configuration. Deleting one deletes its prefetch schedules.

## Ad insertion

A session starts implicitly at the HLS endpoint (`/v1/master/...`, with `ads.<name>` query parameters as player
parameters) or explicitly with a `POST` to the session initialization endpoint (with `adsParams`, `playerParams`,
`availSuppression` and `reportingMode`). Its multivariant playlist names a personalized playlist per track.

Each media playlist is the origin's, with its ad avails filled. An avail starts at `EXT-X-CUE-OUT` or at an
`EXT-X-DATERANGE` with `SCTE35-OUT`, and lasts its duration or until `EXT-X-CUE-IN`. MediaTailor requests the ad
decision server, filling its dynamic variables (`[session.*]`, `[avail.*]`, `[player_params.*]`, through
configuration aliases), and follows VAST wrappers. Then it places the linear ads that fit the avail, with the bumpers,
and the slate for the rest. When there's no slate and the unfilled time passes the personalization threshold, the
content plays instead. Ads replace the content they cover, between discontinuity tags. Avails a session joins midway
aren't filled, and avail suppression keeps avails behind or after the live edge unfilled, or partly filled with
`PARTIAL_AVAIL`. A VOD playlist without markers gets the ad decision server's VMAP breaks (`start`, times,
percentages, `end`), or its VAST ads as a pre-roll. A live session gets the live pre-roll. Ad markers pass through when
the manifest processing rules ask. Content and ad segment URLs take the CDN prefixes.

Ads are conditioned for each track. With `TRANSCODE` (the default), ffmpeg transcodes the best MP4 media file to the
track's size, bitrate and segment duration, as TS or as fragmented MP4 with an init section, like the content. With
`NONE`, an HLS media file is used as it is. A manifest request waits up to `transcode-wait-millis` for its ads; an ad not
ready by then is left out of that avail and keeps transcoding for later sessions. Transcoding needs Docker: ffmpeg runs
in the image Kinesis Video Streams transcodes with.

The playback host serves ad segments. With server-side reporting, it fires each ad's impressions and its `start`,
quartile and `complete` tracking events as their segments are requested. With client-side reporting, the session's
tracking URL lists the avails, ads and beacons instead. Prefetch schedules request ads for their retrieval window's
dynamic variables, and avails in the consumption window that match their criteria use those ads. Recurring schedules
retrieve again after each avail. When a configuration's log percentage samples a session, its ad decision server and
manifest events go to CloudWatch Logs, in `MediaTailor/AdDecisionServerInteractions` and `MediaTailor/ManifestService`.

DASH manifests come from the origin with their segment URLs resolved and a `Location` for dynamic ones; ads aren't
inserted into them.

## Channel assembly

Source locations name the base URL their VOD and live sources' packages are read from. A program's duration is its VOD
source's, read from its manifest and clipped. A `LOOP` channel repeats its programs, in the order their relative
transitions set, from when it started. A `LINEAR` channel plays them at their absolute times, or after or before the
program they're relative to, with the filler slate in the gaps. A program's ad breaks play their slate at their
offsets.

While a channel runs, each HLS output serves a sliding window of the schedule with program date times. Ad breaks are
marked with `EXT-X-DATERANGE` SCTE-35 splice inserts, `EXT-X-CUE-OUT`/`EXT-X-CUE-IN`, or both, as the output's ad markup
types ask, so a playback configuration with the output as its source fills them. `aws.time_delay` shifts the window
back on channels with time-shifted viewing. DASH outputs answer `501`. `GetChannelSchedule` lists programs, filler
slates, alternate media for an audience, and the ad breaks. Sources that can't be read raise alerts on the program.
`AS_RUN` logs go to `MediaTailor/Channel/AsRunLog`. A channel is updated and deleted only while stopped, and a source in
use can't be deleted.

## Configuration

```yaml
floci:
  services:
    media-tailor:
      enabled: true
      request-timeout-millis: 10000   # origin, ad decision server and beacon requests
      transcode-wait-millis: 30000    # how long a manifest request waits for its ads
      session-idle-seconds: 600       # how long a session lasts after its last request
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ConfigureLogsForChannel` | Turn a channel's as-run logs on or off |
| `ConfigureLogsForPlaybackConfiguration` | Set the share of sessions logged to CloudWatch Logs |
| `CreateChannel` | Create a channel with an HLS or DASH playback URL per output |
| `CreateLiveSource` | Create live source |
| `CreatePrefetchSchedule` | Prefetch ads for matching avails |
| `CreateProgram` | Schedule a program; its duration is read from its source |
| `CreateSourceLocation` | Create source location |
| `CreateVodSource` | Create VOD source |
| `DeleteChannel` | Delete a stopped channel and its programs |
| `DeleteChannelPolicy` | Delete channel policy |
| `DeleteFunction` | Delete a function no configuration uses |
| `DeleteLiveSource` | Delete a live source no program uses |
| `DeletePlaybackConfiguration` | Delete a configuration and its prefetch schedules |
| `DeletePrefetchSchedule` | Delete prefetch schedule |
| `DeleteProgram` | Delete program |
| `DeleteSourceLocation` | Delete a source location without sources |
| `DeleteVodSource` | Delete a VOD source no program or slate uses |
| `DescribeChannel` | Describe channel |
| `DescribeLiveSource` | Describe live source |
| `DescribeProgram` | Describe a program with its scheduled start |
| `DescribeSourceLocation` | Describe source location |
| `DescribeVodSource` | Describe a VOD source with its ad break opportunities |
| `GetChannelPolicy` | Get channel policy |
| `GetChannelSchedule` | A channel's upcoming programs, filler slates and ad breaks |
| `GetFunction` | Get function |
| `GetPlaybackConfiguration` | Get a configuration with its endpoints |
| `GetPrefetchSchedule` | Get prefetch schedule |
| `ListAlerts` | A channel assembly resource's alerts |
| `ListChannels` | List channels |
| `ListFunctions` | List functions |
| `ListLiveSources` | List live sources |
| `ListPlaybackConfigurations` | List playback configurations |
| `ListPrefetchSchedules` | List a configuration's prefetch schedules |
| `ListSourceLocations` | List source locations |
| `ListTagsForResource` | List a resource's tags |
| `ListVodSources` | List VOD sources |
| `PutChannelPolicy` | Put channel policy |
| `PutFunction` | Create or replace a function |
| `PutPlaybackConfiguration` | Create or replace a playback configuration |
| `StartChannel` | Start a channel; its outputs serve while it runs |
| `StopChannel` | Stop channel |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags |
| `UpdateChannel` | Update a stopped channel |
| `UpdateLiveSource` | Update live source |
| `UpdateProgram` | Update a program's ad breaks and schedule |
| `UpdateSourceLocation` | Update source location |
| `UpdateVodSource` | Update VOD source |
<!-- floci:actions:end -->
