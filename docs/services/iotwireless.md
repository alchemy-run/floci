# AWS IoT Wireless

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/`, signed for `iotwireless`.

Floci runs IoT Core for LoRaWAN as a working LoRaWAN network server that LoRa Basics Station gateways connect to,
and manages Amazon Sidewalk devices, multicast groups, FUOTA tasks and gateway updates.

## Devices, gateways and destinations

Destinations deliver uplinks to an AWS IoT rule, invoked by name as if the message had been published to the topic
its SQL selects from, or publish them to an MQTT topic. The role must exist. Device profiles carry the LoRaWAN
regional settings (or Sidewalk's application server key and DAK certificate); service profiles carry
`AddGwMetadata`, which adds the receiving gateways to delivered uplinks.

LoRaWAN devices are OTAA (1.0.x or 1.1) or ABP, with exactly one activation method, a unique DevEui, and existing
profiles; a profile without `SupportsJoin` takes ABP devices only. Sidewalk devices get a Sidewalk ID and Ed25519 and
P-256 key pairs, and start `PROVISIONED`. Devices are found by ID, DevEui, thing name or Sidewalk serial number;
gateways by ID, GatewayEui or thing name. Destinations and profiles in use can't be deleted.

## The network server

`GetServiceEndpoint` gives the LNS endpoint (`ws://localhost:4566/_floci/iotwireless/<region>/lns`) and the CUPS
endpoint, with the Floci CA as `ServerTrust`. A Basics Station gateway connects to `<lns>/router-info` with its EUI,
gets its traffic URI, and receives a `router_config` for its RF region (US915 and AU915 sub-band 2, EU868 and the
other regions' default channels).

Join requests are checked against the device's AppKey (or NwkKey in 1.1) and DevNonce history, and answered on RX1
with a join accept after the join delay; the session keys derive as LoRaWAN 1.0.x or 1.1 specifies. Uplinks are
matched by DevAddr and MIC, their 32-bit frame counter reconstructed and replays dropped, their payload decrypted,
and delivered to the destination with LoRaWAN metadata (data rate, spreading factor, frequency, FCnt, gateways).
Confirmed uplinks are acknowledged; LinkCheckReq and DeviceTimeReq are answered. `SendDataToWirelessDevice` queues
a downlink sent in the next RX1 window (at once on RX2 for Class C devices, through the last gateway heard);
confirmed downlinks stay queued until acknowledged. `TestWirelessDevice` delivers `Hello` from the device.

Statistics record each device's and gateway's last uplink, RSSI and SNR, and each gateway's connection.
`UpdateMetricConfiguration` turns on summary metrics, which `GetMetrics` aggregates by hour, day or week. Join and
connection status events go to `$aws/iotwireless/events/join/<type>/lorawan/wireless_devices` and
`$aws/iotwireless/events/connection_status/<type>/lorawan/wireless_gateways` (and their per-identifier topics) when
enabled. Log levels (`ERROR` by default) write to the `AWSIoTWirelessLogs` log group.

CUPS answers `update-info` with the LNS URI and trust when the gateway's are stale, and with a pending gateway task's
update data from S3 (with its signature and key CRC) when the gateway runs the task's current version. A task goes
`PENDING`, `IN_PROGRESS`, `FIRST_RETRY`, `SECOND_RETRY`, `FAILED`, or `COMPLETED` once the gateway reports the update
version.

## Multicast and FUOTA

Multicast groups hold devices of their RF region. Starting a session sends each member McGroupSetupReq and
McClassCSessionReq (Remote Multicast Setup, FPort 200); a member's McGroupSetupAns marks it `Successful`.
`SendDataToMulticastGroup` sends a downlink with the group's address and keys through its gateways on the session's
RX2 parameters, while the session runs.

A FUOTA task reads its firmware image from S3 when started: it waits for its start time, sends FragSessionSetupReq,
then each fragment (and the redundancy as parity fragments) as DataFragment commands on FPort 201, one per
`FragmentIntervalMS`, to its devices and multicast groups, and ends `FuotaDone`. Devices' FragSessionStatusAns set
their FUOTA status.

## Positions, Sidewalk and the rest

`UpdateResourcePosition` takes a GeoJSON point; `GetResourcePosition` returns it. `GetPositionEstimate` resolves
Wi-Fi access points against gateways with positions (by EUI) and is otherwise `ResourceNotFoundException`; GNSS
scans aren't solved. Sidewalk partner accounts keep their key's fingerprint. Device import tasks read Sidewalk serial
numbers from a CSV in S3 and onboard each as a device. Network analyzer configurations, event configurations by
resource and type, log levels, things and gateway certificates are kept as configured.

## Configuration

```yaml
floci:
  services:
    iotwireless:
      enabled: true
      max-queued-messages: 100
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AssociateAwsAccountWithPartnerAccount` | Link a Sidewalk account, keeping its key's fingerprint |
| `AssociateMulticastGroupWithFuotaTask` | Add a multicast group to a pending FUOTA task |
| `AssociateWirelessDeviceWithFuotaTask` | Add a device to a pending FUOTA task |
| `AssociateWirelessDeviceWithMulticastGroup` | Add a device of the group's RF region to a multicast group |
| `AssociateWirelessDeviceWithThing` | Associate a device with an IoT thing that exists |
| `AssociateWirelessGatewayWithCertificate` | Associate a gateway with an IoT certificate |
| `AssociateWirelessGatewayWithThing` | Associate a gateway with an IoT thing that exists |
| `CancelMulticastGroupSession` | End a multicast group's session |
| `CreateDestination` | Create a destination: an IoT rule invoked by name or an MQTT topic, through a role |
| `CreateDeviceProfile` | Create a LoRaWAN or Sidewalk device profile |
| `CreateFuotaTask` | Create a FUOTA task for a firmware image in S3 |
| `CreateMulticastGroup` | Create a Class B or C multicast group with its address and key |
| `CreateNetworkAnalyzerConfiguration` | Create a network analyzer configuration |
| `CreateServiceProfile` | Create a service profile |
| `CreateWirelessDevice` | Create an OTAA or ABP LoRaWAN device, or a provisioned Sidewalk device |
| `CreateWirelessGateway` | Create a LoRaWAN gateway |
| `CreateWirelessGatewayTask` | Give a gateway a pending update task, delivered over CUPS |
| `CreateWirelessGatewayTaskDefinition` | Create a gateway update from S3, optionally tasking matching gateways |
| `DeleteDestination` | Delete a destination no device uses |
| `DeleteDeviceProfile` | Delete a device profile no device uses |
| `DeleteFuotaTask` | Delete a FUOTA task |
| `DeleteMulticastGroup` | Delete a multicast group |
| `DeleteNetworkAnalyzerConfiguration` | Delete a network analyzer configuration |
| `DeleteQueuedMessages` | Remove a queued downlink, or all of them with `*` |
| `DeleteServiceProfile` | Delete a service profile no device uses |
| `DeleteWirelessDevice` | Delete a device |
| `DeleteWirelessDeviceImportTask` | Delete a device import task |
| `DeleteWirelessGateway` | Delete a gateway, disconnecting it |
| `DeleteWirelessGatewayTask` | Delete a gateway's update task |
| `DeleteWirelessGatewayTaskDefinition` | Delete a gateway update |
| `DeregisterWirelessDevice` | Return a Sidewalk device to PROVISIONED, or end an OTAA device's session |
| `DisassociateAwsAccountFromPartnerAccount` | Unlink a Sidewalk account |
| `DisassociateMulticastGroupFromFuotaTask` | Remove a multicast group from a FUOTA task |
| `DisassociateWirelessDeviceFromFuotaTask` | Remove a device from a FUOTA task |
| `DisassociateWirelessDeviceFromMulticastGroup` | Remove a device from a multicast group |
| `DisassociateWirelessDeviceFromThing` | Disassociate a device from its thing |
| `DisassociateWirelessGatewayFromCertificate` | Disassociate a gateway from its certificate |
| `DisassociateWirelessGatewayFromThing` | Disassociate a gateway from its thing |
| `GetDestination` | Get a destination |
| `GetDeviceProfile` | Get a device profile |
| `GetEventConfigurationByResourceTypes` | Get the event topics enabled for each resource type |
| `GetFuotaTask` | Get a FUOTA task and its status |
| `GetLogLevelsByResourceTypes` | Get the default and per-type log levels |
| `GetMetricConfiguration` | Get whether summary metrics are on |
| `GetMetrics` | Aggregate summary metrics by hour, day or week |
| `GetMulticastGroup` | Get a multicast group and its device counts |
| `GetMulticastGroupSession` | Get a multicast group's session |
| `GetNetworkAnalyzerConfiguration` | Get a network analyzer configuration |
| `GetPartnerAccount` | Get a Sidewalk account |
| `GetPosition` | Get a resource's position set with UpdatePosition |
| `GetPositionConfiguration` | Get a resource's position solver configuration |
| `GetPositionEstimate` | Estimate a position from Wi-Fi access points at gateways with positions; others aren't found |
| `GetResourceEventConfiguration` | Get a resource's event topics |
| `GetResourceLogLevel` | Get a resource's log level |
| `GetResourcePosition` | Get a resource's GeoJSON position |
| `GetServiceEndpoint` | Get the LNS or CUPS endpoint gateways connect to, with the Floci CA as trust |
| `GetServiceProfile` | Get a service profile |
| `GetWirelessDevice` | Get a device by ID, DevEui, thing name or Sidewalk serial number |
| `GetWirelessDeviceImportTask` | Get a device import task and its counts |
| `GetWirelessDeviceStatistics` | Get a device's last uplink and radio metadata |
| `GetWirelessGateway` | Get a gateway by ID, GatewayEui or thing name |
| `GetWirelessGatewayCertificate` | Get a gateway's certificates |
| `GetWirelessGatewayFirmwareInformation` | Get the station version a gateway last reported |
| `GetWirelessGatewayStatistics` | Get a gateway's connection status and last uplink |
| `GetWirelessGatewayTask` | Get a gateway's update task |
| `GetWirelessGatewayTaskDefinition` | Get a gateway update |
| `ListDestinations` | List destinations |
| `ListDeviceProfiles` | List device profiles by type |
| `ListDevicesForWirelessDeviceImportTask` | List an import task's devices by onboarding status |
| `ListEventConfigurations` | List resources' event configurations by resource type |
| `ListFuotaTasks` | List FUOTA tasks |
| `ListMulticastGroups` | List multicast groups |
| `ListMulticastGroupsByFuotaTask` | List a FUOTA task's multicast groups |
| `ListNetworkAnalyzerConfigurations` | List network analyzer configurations |
| `ListPartnerAccounts` | List Sidewalk accounts |
| `ListPositionConfigurations` | List position configurations |
| `ListQueuedMessages` | List a device's queued downlinks |
| `ListServiceProfiles` | List service profiles |
| `ListTagsForResource` | List a resource's tags |
| `ListWirelessDeviceImportTasks` | List device import tasks |
| `ListWirelessDevices` | List devices by destination, profile, type, FUOTA task or multicast group |
| `ListWirelessGatewayTaskDefinitions` | List gateway updates |
| `ListWirelessGateways` | List gateways with their last uplinks |
| `PutPositionConfiguration` | Set a resource's position solver and destination |
| `PutResourceLogLevel` | Set a resource's log level |
| `ResetAllResourceLogLevels` | Clear every resource's log level |
| `ResetResourceLogLevel` | Clear a resource's log level |
| `SendDataToMulticastGroup` | Send a downlink to a multicast group through its gateways while its session runs |
| `SendDataToWirelessDevice` | Queue a downlink for the next RX1 window, or send it at once to a Class C device |
| `StartBulkAssociateWirelessDeviceWithMulticastGroup` | Add the devices matching tags and a query to a multicast group |
| `StartBulkDisassociateWirelessDeviceFromMulticastGroup` | Remove the devices matching tags and a query from a group |
| `StartFuotaTask` | Send a firmware image as fragments to a FUOTA task's devices and groups |
| `StartMulticastGroupSession` | Start a multicast session, setting it up on each member device |
| `StartSingleWirelessDeviceImportTask` | Onboard one Sidewalk device |
| `StartWirelessDeviceImportTask` | Onboard Sidewalk devices listed in a CSV in S3 |
| `TagResource` | Tag a resource |
| `TestWirelessDevice` | Deliver `Hello` from a device to its destination |
| `UntagResource` | Remove tags from a resource |
| `UpdateDestination` | Update a destination |
| `UpdateEventConfigurationByResourceTypes` | Enable or disable event topics for resource types |
| `UpdateFuotaTask` | Update a pending FUOTA task |
| `UpdateLogLevelsByResourceTypes` | Set the default and per-type log levels |
| `UpdateMetricConfiguration` | Turn summary metrics on or off |
| `UpdateMulticastGroup` | Update a multicast group |
| `UpdateNetworkAnalyzerConfiguration` | Add and remove a network analyzer configuration's members |
| `UpdatePartnerAccount` | Change a Sidewalk account's key |
| `UpdatePosition` | Set a resource's position |
| `UpdateResourceEventConfiguration` | Enable or disable a resource's event topics |
| `UpdateResourcePosition` | Set or clear a resource's GeoJSON position |
| `UpdateWirelessDevice` | Update a device's name, destination, profiles, ABP counter or FPorts |
| `UpdateWirelessDeviceImportTask` | Add a CSV of devices to an import task |
| `UpdateWirelessGateway` | Update a gateway, reconfiguring it if it's connected |
<!-- floci:actions:end -->
