# AWS IoT FleetWise

**Protocol:** JSON 1.0 (`POST /` + `X-Amz-Target: IoTAutobahnControlPlane.<Action>`)
**Endpoint:** `http://localhost:4566/`, signed for `iotfleetwise`.

Floci runs IoT FleetWise's vehicle modeling, its fleets and vehicles, and its campaigns, and talks to vehicles the
way the [Edge Agent](https://github.com/aws/aws-iot-fleetwise-edge) expects, over Floci's AWS IoT MQTT broker.

## Vehicle modeling

An account has one signal catalog in each Region, as on AWS. Its nodes are validated: every node sits in a branch
(a property in a struct), sensors, actuators, attributes and properties carry a data type, a struct-typed signal
names its struct, and a removed node can't be in use by a vehicle model, campaign or state template.
`ImportSignalCatalog` reads a COVESA VSS tree in its JSON export. Every node gets a numeric signal ID, kept for the
catalog's life, that decoder manifests and collection schemes sent to vehicles refer to.

Vehicle models (model manifests) and decoder manifests start as `DRAFT` and can't change once `ACTIVE`. Decoder
problems are reported together in a `DecoderManifestValidationException` with IoT FleetWise's reasons
(`SIGNAL_NOT_IN_MODEL`, `NETWORK_INTERFACE_TYPE_INCOMPATIBLE_WITH_SIGNAL_DECODER_TYPE` and the rest). Activating a
decoder manifest makes it `INVALID`, with a message naming them, while a sensor or actuator of its vehicle model has
no decoder, unless `defaultForUnmappedSignals` is `CUSTOM_DECODING`. `ImportDecoderManifest` reads CAN DBC files:
each signal its `signalsMap` names becomes a CAN decoder with the DBC's message ID, bit layout, byte order,
signedness, factor and offset. Resources in use can't be deleted (`ConflictException`).

## Fleets, vehicles and campaigns

A vehicle is an AWS IoT thing: `CreateVehicle` creates it (`CreateIotThing`) or requires it
(`ValidateIotThingExists`), and `DeleteVehicle` leaves it. A vehicle needs an `ACTIVE` vehicle model and decoder
manifest, and its attributes must be attributes of the catalog.

A campaign targets a fleet or a vehicle. It is checked against its destinations: an S3 bucket must exist and its
policy must let `iotfleetwise.amazonaws.com` put objects, an MQTT topic destination needs its execution role, and a
Timestream destination is refused because Floci's Timestream for LiveAnalytics is closed to new customers. Event
expressions (`$variable.`Vehicle.Speed` > 50 && ...`, window functions, `isNull`, `custom_function`) are parsed and
checked against the catalog. A campaign is `CREATING`, then `WAITING_FOR_APPROVAL`; `APPROVE` runs it, `SUSPEND`
and `RESUME` stop and restart it.

## Talking to vehicles

Floci publishes, retained, a vehicle's decoder manifest on `$aws/iotfleetwise/vehicles/<vehicle>/decoder_manifests`
and the collection schemes of its running campaigns on `.../collection_schemes`, in the Edge Agent's protobuf
schemas, with event expressions compiled into condition trees. They are republished whenever the vehicle's
campaigns, fleets or manifests change. A document's sync ID is its ARN.

The agent's check-ins on `.../checkins` set `GetVehicleStatus`: a running campaign is `READY` until the vehicle
reports its sync ID, then `HEALTHY`. Data on `.../signals` (snappy-compressed or not) is decoded with the catalog's
signal IDs and data types and delivered to each destination of the campaign:

- **Amazon S3:** processed records (`vehicleName`, `campaignName`, `eventId`, `time`, `name`,
  `measure_value_BIGINT|DOUBLE|BOOLEAN|VARCHAR`, and the campaign's `dataExtraDimensions`) gathered for
  `floci.services.iotfleetwise.s3-flush-seconds` (600, IoT FleetWise's ~10 minutes) and written under
  `<prefix>/processed-data/year=YYYY/month=MM/date=DD/hour=HH/` as JSON or Parquet (the default, through the
  floci-duck sidecar), gzipped unless `storageCompressionFormat` is `NONE`; and each message as Amazon Ion under
  `<prefix>/raw-data/<vehicle>/<eventId>-<timestamp>.10n`. A failed write is retried.
- **AWS IoT topic:** the message's processed records, as a JSON array.

With `PutLoggingOptions` set to `ERROR`, dropped messages and failed deliveries are logged to the log group.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AssociateVehicleFleet` | Add a vehicle to a fleet and deploy the fleet's campaigns to it |
| `BatchCreateVehicle` | Create up to 10 vehicles, reporting each failure |
| `BatchUpdateVehicle` | Update up to 10 vehicles, reporting each failure |
| `CreateCampaign` | Create a campaign, checked against its signals, expressions and destinations |
| `CreateDecoderManifest` | Create a DRAFT decoder manifest of network interfaces and signal decoders |
| `CreateFleet` | Create a fleet of a signal catalog |
| `CreateModelManifest` | Create a DRAFT vehicle model of catalog signals |
| `CreateSignalCatalog` | Create the account's signal catalog in the Region |
| `CreateStateTemplate` | Create a state template of catalog signals |
| `CreateVehicle` | Create a vehicle and its AWS IoT thing |
| `DeleteCampaign` | Delete a campaign and withdraw it from its vehicles |
| `DeleteDecoderManifest` | Delete a decoder manifest no vehicle uses |
| `DeleteFleet` | Delete a fleet with no vehicles or campaigns |
| `DeleteModelManifest` | Delete a vehicle model no decoder manifest or vehicle uses |
| `DeleteSignalCatalog` | Delete a signal catalog nothing uses |
| `DeleteStateTemplate` | Delete a state template no vehicle uses |
| `DeleteVehicle` | Delete a vehicle, leaving its AWS IoT thing |
| `DisassociateVehicleFleet` | Remove a vehicle from a fleet and redeploy it |
| `GetCampaign` | Get a campaign with its status |
| `GetDecoderManifest` | Get a decoder manifest with its status and validation message |
| `GetEncryptionConfiguration` | Get the account's encryption configuration |
| `GetFleet` | Get a fleet |
| `GetLoggingOptions` | Get the account's logging options |
| `GetModelManifest` | Get a vehicle model |
| `GetRegisterAccountStatus` | Get the account's registration |
| `GetSignalCatalog` | Get the signal catalog with its node counts |
| `GetStateTemplate` | Get a state template by name, ID or ARN |
| `GetVehicle` | Get a vehicle |
| `GetVehicleStatus` | Get a vehicle's status in each campaign that targets it, from its check-ins |
| `ImportDecoderManifest` | Add CAN decoders from DBC files |
| `ImportSignalCatalog` | Create the signal catalog from a VSS JSON tree |
| `ListCampaigns` | List campaigns, by status |
| `ListDecoderManifestNetworkInterfaces` | List a decoder manifest's network interfaces |
| `ListDecoderManifestSignals` | List a decoder manifest's signal decoders |
| `ListDecoderManifests` | List decoder manifests, by vehicle model |
| `ListFleets` | List fleets |
| `ListFleetsForVehicle` | List a vehicle's fleets |
| `ListModelManifestNodes` | List a vehicle model's signals |
| `ListModelManifests` | List vehicle models, by signal catalog |
| `ListSignalCatalogNodes` | List the signal catalog's nodes, by type |
| `ListSignalCatalogs` | List signal catalogs |
| `ListStateTemplates` | List state templates |
| `ListTagsForResource` | List a resource's tags |
| `ListVehicles` | List vehicles, by vehicle model and attributes |
| `ListVehiclesInFleet` | List a fleet's vehicles |
| `PutEncryptionConfiguration` | Choose IoT FleetWise's default encryption or a KMS key |
| `PutLoggingOptions` | Turn error logging to CloudWatch Logs on or off |
| `RegisterAccount` | Register the account (Timestream resources are refused) |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
| `UpdateCampaign` | Approve, suspend, resume or update a campaign |
| `UpdateDecoderManifest` | Change a DRAFT decoder manifest's decoders, or activate it |
| `UpdateFleet` | Update a fleet's description |
| `UpdateModelManifest` | Change a DRAFT vehicle model's signals, or activate it |
| `UpdateSignalCatalog` | Add, update or remove catalog nodes |
| `UpdateStateTemplate` | Change a state template's signals and dimensions |
| `UpdateVehicle` | Change a vehicle's manifests, attributes and state templates |
<!-- floci:actions:end -->

## Configuration

```yaml
floci:
  services:
    iotfleetwise:
      enabled: true
      existing-customer: true      # false refuses every call, as AWS does for new customers
      campaign-creation-millis: 1000
      s3-flush-seconds: 600
      max-campaigns: 20
      max-model-manifests: 150
      max-decoder-manifests-per-model: 100
      max-vehicles-per-fleet: 2000
```
