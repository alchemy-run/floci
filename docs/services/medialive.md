# AWS Elemental MediaLive

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/prod/...`, signed for `medialive`

Floci runs a started MediaLive channel's encoder with ffmpeg: it reads the channel's input and writes its output
groups to their destinations while the channel is `RUNNING`.

## Inputs and input security groups

Push inputs (`RTMP_PUSH`, `UDP_PUSH`, `RTP_PUSH`) get one destination per pipeline: an address at the configured
ingest IP and a port from `ingest-port-start` upward. A running channel's encoder publishes that port, so content
pushed there reaches it. RTMP destinations carry their `app/instance` stream name. A push input needs an input
security group, whose CIDRs are kept with it. Pull inputs (`URL_PULL`, `RTMP_PULL`, `MP4_FILE`) have one source per
pipeline; `s3://` sources are read through Floci. Inputs with two sources or destinations are `STANDARD`, with one
`SINGLE_PIPELINE`. An input is `ATTACHED` while a channel uses it and can't be deleted then. `MEDIACONNECT` inputs
are kept as configured, but the encoder doesn't read them. Elemental Link input devices are physical hardware, so
none exist in Floci.

## Channels

A channel is `CREATING`, then `IDLE`. Its encoder settings are checked: the destinations its output groups send to,
the video and audio descriptions its outputs name, one destination URL per pipeline, and inputs of the channel's
class. Starting it makes it `STARTING`, then `RUNNING`. Stopping it makes it `STOPPING`, then `IDLE`. Deleting it is
`DELETING`, then gone. Only an `IDLE` channel is updated or changes class. State changes, input switches and alerts go
to the default event bus as `MediaLive Channel State Change`, `MediaLive Channel Input Change` and `MediaLive Channel
Alert` events.

While a channel runs, one ffmpeg container encodes its active input attachment's input. A file source loops when its
input settings' `SourceEndBehavior` is `LOOP`. Each output group encodes its outputs' video descriptions (size,
scaling, frame rate, H.264, H.265, AV1 or MPEG-2 codec, bitrate and rate control, GOP) and audio descriptions (AAC,
AC3, EAC3, MP2 or PCM, bitrate, sample rate, coding mode):

- **HLS:** a main playlist, and a playlist and segments per output.
- **Archive:** TS files that roll over at the group's interval.
- **Frame capture:** JPEGs at each output's capture interval.
- **RTMP, UDP and SRT:** streams to their destinations.

`s3://` destinations are written in the container and copied to S3 as files appear. HTTP(S) destinations, such as a
MediaPackage ingest endpoint, are written with `PUT`. `DescribeThumbnails` gives the latest frame of a running
channel. When the encoder stops on its own (the input ended or couldn't be read), the channel gets a `Pipeline
Failure` alert with what ffmpeg logged. MediaPackage, Microsoft Smooth, CMAF ingest, multiplex and MediaConnect router
output groups aren't carried; each gets an alert. A schedule's input switches change the active input attachment when
they start. Running channels need Docker: ffmpeg runs in the image Kinesis Video Streams transcodes with.

## Multiplexes, reservations and the rest

Multiplexes (in two Availability Zones) and their programs keep their settings, are started and stopped through
transitions, and give each pipeline a MediaConnect entitlement ARN; Floci doesn't multiplex transport streams.
Offerings are 12-month reservations; a reservation is `ACTIVE` until it ends. The workflow monitor's CloudWatch alarm
templates, EventBridge rule templates, their groups and signal maps are kept. A signal map discovers its entry point,
and monitor deployments complete after a transition. MediaLive Anywhere clusters, networks, nodes, SDI sources and
channel placement groups are kept. Nodes are on-premises hardware that never registers, so they can't be activated.
Every resource takes tags.

## Configuration

```yaml
floci:
  services:
    media-live:
      enabled: true
      ingest-ip: 127.0.0.1       # push inputs' destination address
      egress-ip: 127.0.0.1       # channels' egress endpoints
      ingest-port-start: 21000   # push inputs' ports, published by running channels
      ingest-port-count: 100
      transition-millis: 2000
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AcceptInputDeviceTransfer` | Accept input device transfer (none exist in Floci) |
| `BatchDelete` | Delete channels, inputs, security groups and multiplexes |
| `BatchStart` | Start channels and multiplexes |
| `BatchStop` | Stop channels and multiplexes |
| `BatchUpdateSchedule` | Add and remove schedule actions |
| `CancelInputDeviceTransfer` | Cancel input device transfer (none exist in Floci) |
| `ClaimDevice` | Claim an input device (none exist in Floci) |
| `CreateChannel` | Create channel |
| `CreateChannelPlacementGroup` | Create channel placement group |
| `CreateCloudWatchAlarmTemplate` | Create cloud watch alarm template |
| `CreateCloudWatchAlarmTemplateGroup` | Create cloud watch alarm template group |
| `CreateCluster` | Create cluster |
| `CreateEventBridgeRuleTemplate` | Create event bridge rule template |
| `CreateEventBridgeRuleTemplateGroup` | Create event bridge rule template group |
| `CreateInput` | Create input |
| `CreateInputSecurityGroup` | Create input security group |
| `CreateMultiplex` | Create multiplex |
| `CreateMultiplexProgram` | Create multiplex program |
| `CreateNetwork` | Create network |
| `CreateNode` | Create node |
| `CreateNodeRegistrationScript` | A node's registration script |
| `CreatePartnerInput` | Create the partner of a STANDARD input |
| `CreateSdiSource` | Create sdi source |
| `CreateSignalMap` | Create signal map |
| `CreateTags` | Tag a resource |
| `DeleteChannel` | Delete channel |
| `DeleteChannelPlacementGroup` | Delete channel placement group |
| `DeleteCloudWatchAlarmTemplate` | Delete cloud watch alarm template |
| `DeleteCloudWatchAlarmTemplateGroup` | Delete cloud watch alarm template group |
| `DeleteCluster` | Delete cluster |
| `DeleteEventBridgeRuleTemplate` | Delete event bridge rule template |
| `DeleteEventBridgeRuleTemplateGroup` | Delete event bridge rule template group |
| `DeleteInput` | Delete input |
| `DeleteInputSecurityGroup` | Delete input security group |
| `DeleteMultiplex` | Delete multiplex |
| `DeleteMultiplexProgram` | Delete multiplex program |
| `DeleteNetwork` | Delete network |
| `DeleteNode` | Delete node |
| `DeleteReservation` | Delete reservation |
| `DeleteSchedule` | Clear a channel's schedule |
| `DeleteSdiSource` | Delete sdi source |
| `DeleteSignalMap` | Delete signal map |
| `DeleteTags` | Remove tags |
| `DescribeAccountConfiguration` | Describe account configuration |
| `DescribeChannel` | Describe channel |
| `DescribeChannelPlacementGroup` | Describe channel placement group |
| `DescribeCluster` | Describe cluster |
| `DescribeInput` | Describe input |
| `DescribeInputDevice` | Describe input device (none exist in Floci) |
| `DescribeInputDeviceThumbnail` | Describe input device thumbnail (none exist in Floci) |
| `DescribeInputSecurityGroup` | Describe input security group |
| `DescribeMultiplex` | Describe multiplex |
| `DescribeMultiplexProgram` | Describe multiplex program |
| `DescribeNetwork` | Describe network |
| `DescribeNode` | Describe node |
| `DescribeOffering` | Describe offering |
| `DescribeReservation` | Describe reservation |
| `DescribeSchedule` | A channel's schedule |
| `DescribeSdiSource` | Describe sdi source |
| `DescribeThumbnails` | The latest frame of a running channel |
| `GetCloudWatchAlarmTemplate` | Get cloud watch alarm template |
| `GetCloudWatchAlarmTemplateGroup` | Get cloud watch alarm template group |
| `GetEventBridgeRuleTemplate` | Get event bridge rule template |
| `GetEventBridgeRuleTemplateGroup` | Get event bridge rule template group |
| `GetSignalMap` | Get signal map |
| `ListAlerts` | A channel's alerts |
| `ListChannelPlacementGroups` | List channel placement groups |
| `ListChannels` | List channels |
| `ListCloudWatchAlarmTemplateGroups` | List cloud watch alarm template groups |
| `ListCloudWatchAlarmTemplates` | List cloud watch alarm templates |
| `ListClusterAlerts` | List cluster alerts |
| `ListClusters` | List clusters |
| `ListEventBridgeRuleTemplateGroups` | List event bridge rule template groups |
| `ListEventBridgeRuleTemplates` | List event bridge rule templates |
| `ListInputDeviceTransfers` | List input device transfers (none exist in Floci) |
| `ListInputDevices` | List input devices (none exist in Floci) |
| `ListInputSecurityGroups` | List input security groups |
| `ListInputs` | List inputs |
| `ListMultiplexAlerts` | List multiplex alerts |
| `ListMultiplexPrograms` | List multiplex programs |
| `ListMultiplexes` | List multiplexes |
| `ListNetworks` | List networks |
| `ListNodes` | List nodes |
| `ListOfferings` | List offerings |
| `ListReservations` | List reservations |
| `ListSdiSources` | List sdi sources |
| `ListSignalMaps` | List signal maps |
| `ListTagsForResource` | List a resource's tags |
| `ListVersions` | List channel engine versions |
| `PurchaseOffering` | Reserve an offering |
| `RebootInputDevice` | Reboot input device (none exist in Floci) |
| `RejectInputDeviceTransfer` | Reject input device transfer (none exist in Floci) |
| `RestartChannelPipelines` | Restart a running channel's encoder |
| `StartChannel` | Start a channel; its encoder runs while RUNNING |
| `StartDeleteMonitorDeployment` | Remove a signal map's monitors |
| `StartInputDevice` | Start input device (none exist in Floci) |
| `StartInputDeviceMaintenanceWindow` | Start input device maintenance window (none exist in Floci) |
| `StartMonitorDeployment` | Deploy a signal map's monitors |
| `StartMultiplex` | Start multiplex |
| `StartUpdateSignalMap` | Update and rediscover a signal map |
| `StopChannel` | Stop channel |
| `StopInputDevice` | Stop input device (none exist in Floci) |
| `StopMultiplex` | Stop multiplex |
| `TransferInputDevice` | Transfer input device (none exist in Floci) |
| `UpdateAccountConfiguration` | Update account configuration |
| `UpdateChannel` | Update channel |
| `UpdateChannelClass` | Change an IDLE channel's class |
| `UpdateChannelPlacementGroup` | Update channel placement group |
| `UpdateCloudWatchAlarmTemplate` | Update cloud watch alarm template |
| `UpdateCloudWatchAlarmTemplateGroup` | Update cloud watch alarm template group |
| `UpdateCluster` | Update cluster |
| `UpdateEventBridgeRuleTemplate` | Update event bridge rule template |
| `UpdateEventBridgeRuleTemplateGroup` | Update event bridge rule template group |
| `UpdateInput` | Update input |
| `UpdateInputDevice` | Update input device (none exist in Floci) |
| `UpdateInputSecurityGroup` | Update input security group |
| `UpdateMultiplex` | Update multiplex |
| `UpdateMultiplexProgram` | Update multiplex program |
| `UpdateNetwork` | Update network |
| `UpdateNode` | Update node |
| `UpdateNodeState` | Activate or drain a node |
| `UpdateReservation` | Update reservation |
| `UpdateSdiSource` | Update sdi source |
<!-- floci:actions:end -->
