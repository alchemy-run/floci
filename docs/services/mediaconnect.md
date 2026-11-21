# AWS Elemental MediaConnect

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/v1/...`, signed for `mediaconnect`

Floci carries a started flow's content: its source listens for UDP, and what it receives goes to the flow's outputs
and to the flows its entitlements feed.

## Flows

A flow has one source, or two with a failover configuration, and outputs, entitlements, media streams and VPC
interfaces. Creating one leaves it `STANDBY`. `StartFlow` makes it `STARTING`, then `ACTIVE` after a transition;
`StopFlow` makes it `STOPPING`, then `STANDBY`. A flow is deleted from `STANDBY`: it is `DELETING`, then gone.
Status changes are sent to the default event bus as `aws.mediaconnect` events (`MediaConnect Flow Status Change`).

Sources take a protocol and an ingest port, or an entitlement another flow (in any account) granted this account.
Outputs push to a destination and port, or (`srt-listener`, `zixi-pull`) listen at the flow's egress IP. Each flow
gets a maintenance window, Sunday at 02:00 unless set. A VPC interface creates a network interface in its subnet, in
the flow's Availability Zone, and removing it deletes the network interface.

## The relay

While a flow is `ACTIVE`, each source of a datagram protocol (`rtp`, `rtp-fec`, `rist`, `udp`) listens on UDP: on
its own `IngestPort` when that port is free, otherwise on one from `udp-port-start` upward. `DescribeFlow` shows the
port as a member AWS doesn't have, `localIngestPort`. Packets from outside the source's `WhitelistCidr` are dropped.
What a source receives goes to the flow's enabled outputs of those protocols, with an RTP header added for `rtp`,
`rtp-fec` and `rist` outputs fed by `udp`, and removed for `udp` outputs fed by RTP. It also goes to every active flow
whose source is one of this flow's enabled entitlements. With two sources and failover enabled, `FAILOVER` carries
the primary source until it is silent for a second, and `MERGE` carries both, dropping RTP packets already sent.
Sources and outputs of other protocols (SRT, Zixi, CDI, ST 2110, NDI) are kept as configured, but carry nothing.
`DescribeFlow` lists them in `messages.errors`.

The content inside is read as an MPEG transport stream. `DescribeFlowSourceMetadata` gives its programs, their PCR
PIDs and streams, each stream's type and codec, an H.264 stream's frame size, and an AAC stream's sample rate and
channels. With thumbnails enabled in the source monitoring configuration, `DescribeFlowSourceThumbnail` gives a JPEG
of the latest frame, decoded by ffmpeg in the image Kinesis Video Streams transcodes with (this needs Docker). A
source starting or stopping to send sends a `MediaConnect Alert` event.

## Gateways and bridges

Gateways are `CREATING`, then `ACTIVE`, and keep their networks and egress CIDR blocks. Gateway instances register
through the MediaConnect Gateway agent on an on-premises machine, so Floci has none: `ListGatewayInstances` is empty.
A bridge is `CREATING`, then `DEPLOYING`, with a message that no instance is available to run it. An egress bridge's
flow source adds an output to that flow, which goes with the source. Ingress bridges take network sources on the
gateway's networks.

## Router

Router inputs and outputs are `CREATING`, then `STANDBY`; starting one is `STARTING`, then `ACTIVE`, and stopping
it is `STOPPING`, then `STANDBY`. `TakeRouterInput` routes an output to an input (`ROUTING`, then `ROUTED`), or
unroutes it. An active standard RTP or RIST input receives on its port through the same relay, from the CIDRs its
public network interface allows. It sends to the active standard RTP or RIST outputs routed to it, and its source
metadata and thumbnails read as a flow's do. A VPC network interface creates a network interface in its subnet.
Router resources are tagged as global resources.

Offerings are 12-month reservations of 10, 25, 50 or 100 Mbps outbound bandwidth. A reservation is `PROCESSING`
until it starts, `ACTIVE` until it ends, then `EXPIRED`.

## Configuration

```yaml
floci:
  services:
    media-connect:
      enabled: true
      ingest-ip: 127.0.0.1        # sources' IngestIp
      egress-ip: 127.0.0.1        # flows' EgressIp and listener outputs' address
      bind-host: 0.0.0.0
      udp-port-start: 20000       # when a source's own IngestPort is taken
      udp-port-count: 100
      max-flows: 20
      transition-millis: 2000
```

When Floci runs in Docker, publish the UDP ports sources listen on, for example `-p 5000:5000/udp`.

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AddBridgeOutputs` | Add network outputs to an egress bridge |
| `AddBridgeSources` | Add flow or network sources to a bridge |
| `AddFlowMediaStreams` | Add media streams to a flow |
| `AddFlowOutputs` | Add outputs to a flow |
| `AddFlowSources` | Add a source (up to two) to a flow |
| `AddFlowVpcInterfaces` | Add VPC interfaces, with network interfaces in their subnets |
| `BatchGetRouterInput` | Describe several router inputs |
| `BatchGetRouterNetworkInterface` | Describe several router network interfaces |
| `BatchGetRouterOutput` | Describe several router outputs |
| `CreateBridge` | Create a bridge |
| `CreateFlow` | Create a flow in STANDBY |
| `CreateGateway` | Create a gateway |
| `CreateRouterInput` | Create a router input |
| `CreateRouterNetworkInterface` | Create a router network interface |
| `CreateRouterOutput` | Create a router output |
| `DeleteBridge` | Delete a bridge |
| `DeleteFlow` | Delete a flow |
| `DeleteGateway` | Delete a gateway |
| `DeleteRouterInput` | Delete a router input |
| `DeleteRouterNetworkInterface` | Delete a router network interface |
| `DeleteRouterOutput` | Delete a router output |
| `DeregisterGatewayInstance` | Deregister a gateway instance (none register in Floci) |
| `DescribeBridge` | Describe a bridge |
| `DescribeFlow` | Describe a flow |
| `DescribeFlowSourceMetadata` | Read an active flow's transport stream: programs, streams and codecs |
| `DescribeFlowSourceThumbnail` | A JPEG of an active flow's latest frame |
| `DescribeGateway` | Describe a gateway |
| `DescribeGatewayInstance` | Describe a gateway instance (none register in Floci) |
| `DescribeOffering` | Describe an offering |
| `DescribeReservation` | Describe a reservation |
| `GetRouterInput` | Describe a router input |
| `GetRouterInputSourceMetadata` | Read an active router input's transport stream |
| `GetRouterInputThumbnail` | A JPEG of an active router input's latest frame |
| `GetRouterNetworkInterface` | Describe a router network interface |
| `GetRouterOutput` | Describe a router output |
| `GrantFlowEntitlements` | Grant entitlements to other accounts |
| `ListBridges` | List bridges |
| `ListEntitlements` | List the entitlements granted to this account |
| `ListFlows` | List flows |
| `ListGatewayInstances` | List gateway instances (none register in Floci) |
| `ListGateways` | List gateways |
| `ListOfferings` | List offerings |
| `ListReservations` | List reservations |
| `ListRouterInputs` | List router inputs, by filters |
| `ListRouterNetworkInterfaces` | List router network interfaces, by filters |
| `ListRouterOutputs` | List router outputs, by filters |
| `ListTagsForGlobalResource` | List a router resource's tags |
| `ListTagsForResource` | List a resource's tags |
| `PurchaseOffering` | Reserve an offering's outbound bandwidth |
| `RemoveBridgeOutput` | Remove a bridge output |
| `RemoveBridgeSource` | Remove a bridge source |
| `RemoveFlowMediaStream` | Remove a flow's media stream |
| `RemoveFlowOutput` | Remove a flow output |
| `RemoveFlowSource` | Remove a flow source |
| `RemoveFlowVpcInterface` | Remove a flow's VPC interface |
| `RestartRouterInput` | Restart an active router input |
| `RestartRouterOutput` | Restart an active router output |
| `RevokeFlowEntitlement` | Revoke an entitlement |
| `StartFlow` | Start a flow; its relay carries the source when ACTIVE |
| `StartRouterInput` | Start a router input |
| `StartRouterOutput` | Start a router output |
| `StopFlow` | Stop a flow |
| `StopRouterInput` | Stop a router input |
| `StopRouterOutput` | Stop a router output |
| `TagGlobalResource` | Tag a router resource |
| `TagResource` | Tag a flow, bridge, gateway or reservation |
| `TakeRouterInput` | Route a router output to an input, or unroute it |
| `UntagGlobalResource` | Remove a router resource's tags |
| `UntagResource` | Remove tags |
| `UpdateBridge` | Update a bridge |
| `UpdateBridgeOutput` | Update a bridge output |
| `UpdateBridgeSource` | Update a bridge source |
| `UpdateBridgeState` | Start, stop or delete a bridge |
| `UpdateFlow` | Update a flow |
| `UpdateFlowEntitlement` | Update an entitlement |
| `UpdateFlowMediaStream` | Update a flow's media stream |
| `UpdateFlowOutput` | Update a flow output |
| `UpdateFlowSource` | Update a flow source |
| `UpdateGatewayInstance` | Change a gateway instance's bridge placement (none register in Floci) |
| `UpdateRouterInput` | Update a router input |
| `UpdateRouterNetworkInterface` | Update a router network interface |
| `UpdateRouterOutput` | Update a router output |
<!-- floci:actions:end -->
