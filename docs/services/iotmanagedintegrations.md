# Managed integrations for AWS IoT Device Management

**Protocol:** REST JSON (`/managed-things`, `/cloud-connectors`, `/schema-versions`, `/tags/<arn>` and the rest)
**Endpoint:** `http://localhost:4566/`, signed for `iotmanagedintegrations`.

## Managed things

A managed thing is a device or a hub (`CONTROLLER`). Its onboarding material is checked against its type: a Zigbee
install code (`Z:<EUI-64>$I:<install code>`), a Z-Wave SmartStart code, a Matter setup payload (`MT:...`), custom
or discovered material; the same material can't onboard two things. A device or hub onboarded with a code is
`UNASSOCIATED` until a hub's discovery finds it; a device a cloud connector discovered is `ACTIVATED`. A hub gets a
device certificate from Floci's CA (`GetManagedThingCertificate`). A capability report names capabilities of the
schema catalog or of the thing's own `CapabilitySchemas`; `ListManagedThingSchemas` serves each one's schema.
`DeleteManagedThing` leaves the thing `DELETE_IN_PROGRESS` for `floci.services.iotmanagedintegrations.deletion-millis`
(2000); a hub with devices needs `Force`. Credential lockers holding things can't be deleted.

Hubs and directly connected devices run managed integrations' device SDK, which doesn't run in Floci: a hub's
Zigbee, Z-Wave or custom discovery fails, and their devices stay `UNASSOCIATED` and take no commands. OTA task
executions wait `QUEUED` for the same reason.

## Schema catalog

`ListSchemaVersions` and `GetSchemaVersion` serve a capability for every Matter 1.4 cluster (`matter.OnOff@1.4`)
and a definition for every Matter data type (`matter.uint16@1.4`), generated from the Matter data model by
`tools/aws/regen_iotmi_matter_schemas.py`: properties from the cluster's attributes, actions from its commands and
responses, events, and the cluster's enums, bitmaps and structs as `$defs`, with their Matter IDs as `extrinsicId`.
`Format=ZCL` gives the cluster itself and `Format=CONNECTOR` the IDs a connector addresses.

## Cloud-to-cloud connectors

A cloud connector is a Lambda function implementing the connector interface. A connector destination says how end
users authorize the third-party cloud: OAuth 2.0 (its client ID and secret in a Secrets Manager secret) or general
authorization. `CreateAccountAssociation` returns the OAuth authorization URL, whose redirect URI is Floci's
`/_floci/iotmanagedintegrations/oauth/callback`: Floci exchanges the code at the token URL (HTTP Basic or request
body credentials), invokes the connector's `AWS.ActivateUser`, and sends the end user on to
`oAuthCompleteRedirectUrl`. The association is then `ASSOCIATION_SUCCEEDED` (`ASSOCIATION_FAILED` when the
authorization or exchange fails); `StartAccountAssociationRefresh` starts it again.

Floci invokes the connector as managed integrations does, with `header.auth` (the user's token or the general
authorization's secret) and `payload.operationName`, retrying failed invocations five times:

- `StartDeviceDiscovery` with `DiscoveryType` `CLOUD` invokes `AWS.DiscoverDevices`; the connector reports the
  devices with a `DEVICE_DISCOVERY` connector event. Each becomes, or updates, a managed thing associated with the
  account association, its Matter capability report translated through the catalog, and the discovery
  `SUCCEEDED`. A discovery with no report within
  `floci.services.iotmanagedintegrations.discovery-timeout-seconds` (300) is `TIMED_OUT`.
- `SendManagedThingCommand` translates each capability action into its Matter cluster command (`ReadState` and
  `UpdateState` into the connector interface's `0xff02` and `0xff01`) and invokes `AWS.SendCommand`. The
  connector's `DEVICE_COMMAND_RESPONSE` event updates `GetManagedThingState`; `DEVICE_EVENT` events do too.
- `DeleteAccountAssociation` invokes `AWS.DeactivateUser`.

## Notifications

Destinations are Kinesis data streams written through a role, which must trust `iotmanagedintegrations.amazonaws.com`.
A notification configuration routes one event type to a destination. Floci delivers `DEVICE_LIFE_CYCLE`,
`DEVICE_STATE`, `DEVICE_COMMAND`, `DEVICE_COMMAND_REQUEST`, `DEVICE_EVENT`, `DEVICE_DISCOVERY_STATUS`,
`DEVICE_OTA`, `ACCOUNT_ASSOCIATION` and `CONNECTOR_ERROR_REPORT` events in managed integrations' envelope
(`messageType`, `source`, `customerAccountId`, `resources`, `payload`).

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateAccountAssociation` | Link an end user's third-party account, returning the OAuth authorization URL |
| `CreateCloudConnector` | Register a connector Lambda function |
| `CreateConnectorDestination` | Say how end users authorize a connector's cloud |
| `CreateCredentialLocker` | Create a credential locker |
| `CreateDestination` | Create a Kinesis destination for notifications |
| `CreateEventLogConfiguration` | Create an event log configuration |
| `CreateManagedThing` | Onboard a device or hub with its material, checked against its type |
| `CreateNotificationConfiguration` | Route an event type to a destination |
| `CreateOtaTask` | Create an OTA update of S3 firmware for managed things |
| `CreateOtaTaskConfiguration` | Create an OTA task configuration |
| `CreateProvisioningProfile` | Create a fleet-provisioning profile (with a claim certificate) or a JITR profile |
| `DeleteAccountAssociation` | Delete an account association |
| `DeleteCloudConnector` | Delete a cloud connector |
| `DeleteConnectorDestination` | Delete a connector destination |
| `DeleteCredentialLocker` | Delete a credential locker |
| `DeleteDestination` | Delete a destination |
| `DeleteEventLogConfiguration` | Delete an event log configuration |
| `DeleteManagedThing` | Delete a managed thing (and, with Force, a hub's devices) |
| `DeleteNotificationConfiguration` | Delete a notification configuration |
| `DeleteOtaTask` | Delete an OTA task |
| `DeleteOtaTaskConfiguration` | Delete an OTA task configuration |
| `DeleteProvisioningProfile` | Delete a provisioning profile |
| `DeregisterAccountAssociation` | Remove a managed thing's account association |
| `GetAccountAssociation` | Get an account association |
| `GetCloudConnector` | Get a cloud connector |
| `GetConnectorDestination` | Get a connector destination |
| `GetCredentialLocker` | Get a credential locker |
| `GetCustomEndpoint` | Get the custom endpoint |
| `GetDefaultEncryptionConfiguration` | Get the default encryption configuration |
| `GetDestination` | Get a destination |
| `GetDeviceDiscovery` | Get a device discovery |
| `GetEventLogConfiguration` | Get an event log configuration |
| `GetHubConfiguration` | Get the hub configuration |
| `GetManagedThing` | Get a managed thing |
| `GetManagedThingCapabilities` | Get a managed thing capabilities |
| `GetManagedThingCertificate` | Get a hub's device certificate |
| `GetManagedThingConnectivityData` | Get whether a managed thing is reachable |
| `GetManagedThingMetaData` | Get a managed thing meta data |
| `GetManagedThingState` | Get a managed thing's reported capability properties |
| `GetNotificationConfiguration` | Get a notification configuration |
| `GetOtaTask` | Get an OTA task |
| `GetOtaTaskConfiguration` | Get an OTA task configuration |
| `GetProvisioningProfile` | Get a provisioning profile |
| `GetRuntimeLogConfiguration` | Get a runtime log configuration |
| `GetSchemaVersion` | Get a capability or definition schema, in the AWS, ZCL or CONNECTOR format |
| `ListAccountAssociations` | List account associations |
| `ListCloudConnectors` | List cloud connectors |
| `ListConnectorDestinations` | List connector destinations |
| `ListCredentialLockers` | List credential lockers |
| `ListDestinations` | List destinations |
| `ListDeviceDiscoveries` | List device discoveries |
| `ListDiscoveredDevices` | List discovered devices |
| `ListEventLogConfigurations` | List event log configurations |
| `ListManagedThingAccountAssociations` | List managed thing account associations |
| `ListManagedThingSchemas` | List the schemas of a managed thing's capabilities |
| `ListManagedThings` | List managed things |
| `ListNotificationConfigurations` | List notification configurations |
| `ListOtaTaskConfigurations` | List OTA task configurations |
| `ListOtaTaskExecutions` | List OTA task executions |
| `ListOtaTasks` | List OTA tasks |
| `ListProvisioningProfiles` | List provisioning profiles |
| `ListSchemaVersions` | List catalog schemas, by ID, namespace, visibility and version |
| `ListTagsForResource` | List a resource's tags |
| `PutDefaultEncryptionConfiguration` | Choose the default encryption or a customer KMS key |
| `PutHubConfiguration` | Set the hub token expiry |
| `PutRuntimeLogConfiguration` | Set a managed thing's device SDK log settings |
| `RegisterAccountAssociation` | Associate a discovered managed thing with an account association |
| `RegisterCustomEndpoint` | Create the account's endpoint for hubs and devices |
| `ResetRuntimeLogConfiguration` | Return a managed thing's log settings to the defaults |
| `SendConnectorEvent` | Report discovered devices, command responses, device events or WebRTC requests |
| `SendManagedThingCommand` | Send capability actions; a connector's device gets them as Matter cluster commands |
| `StartAccountAssociationRefresh` | Start the end user's OAuth authorization again |
| `StartDeviceDiscovery` | Discover a connector's devices for an account association |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
| `UpdateAccountAssociation` | Update an account association |
| `UpdateCloudConnector` | Update a cloud connector |
| `UpdateConnectorDestination` | Update a connector destination |
| `UpdateDestination` | Update a destination |
| `UpdateEventLogConfiguration` | Update an event log configuration |
| `UpdateManagedThing` | Update a managed thing |
| `UpdateNotificationConfiguration` | Update a notification configuration |
| `UpdateOtaTask` | Update an OTA task |
<!-- floci:actions:end -->

## Configuration

```yaml
floci:
  services:
    iotmanagedintegrations:
      enabled: true
      deletion-millis: 2000
      discovery-timeout-seconds: 300
      max-managed-things: 10000
      max-credential-lockers: 100
```
