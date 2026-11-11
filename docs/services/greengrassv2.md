# AWS IoT Greengrass V2

**Protocol:** REST JSON (`/greengrass/v2/...`, `/greengrass/things/...`, `/greengrass/servicerole`, `/tags/<arn>`)
**Endpoint:** `http://localhost:4566/`, signed for `greengrass`.

Floci runs Greengrass V2's cloud side against floci's own IoT Core. A component is made from a recipe, JSON or YAML,
with `RecipeFormatVersion` `2020-01-25`. Its S3 artifacts are read when it is made: the version is `DEPLOYABLE` with
each artifact's SHA-256 digest added to the recipe, or `FAILED` naming the artifacts that could not be read. A
component can also be imported from a published Lambda function version. `GetComponentVersionArtifact` returns a
presigned floci S3 URL. `ListComponents` with scope `PUBLIC` lists a catalog of AWS-provided components (the
nucleus, CLI, log manager, shadow manager, client-device components and others), and deployments may name any
`aws.*` component.

A deployment to a thing is written to the thing's `AWSManagedGreengrassV2Deployment` named shadow. A deployment to a
thing group becomes a continuous IoT job. Each carries the fleet configuration the nucleus applies. A new deployment
to a target makes the previous one `INACTIVE` and cancels its job; cancelling or deleting a deployment cancels or
deletes its job.

Core devices are the ones that report. A nucleus's Fleet Status Service messages on
`$aws/things/<thing>/greengrassv2/health/json`, over MQTT or the IoT data plane, register the device and record its
version, platform and health, its installed components, and the status of the deployment it applied. A thing
deployment the device reports `SUCCEEDED` is `COMPLETED`. Each change is published to EventBridge as
`Greengrass V2 Effective Deployment Status Change` or `Greengrass V2 Installed Component Status Change` from
`aws.greengrass`. `ResolveComponentCandidates` picks the newest deployable version that satisfies every version
requirement and runs on the device's platform; a nucleus calls it with its certificate, so a call signed with IAM
credentials is refused.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateComponentVersion` | Make a component version from a recipe or a Lambda function version |
| `ListComponents` | List private components, or the public catalog |
| `GetComponent` | Read a component version's recipe as JSON or YAML |
| `DescribeComponent` | Describe a component version |
| `ListComponentVersions` | List a component's versions, newest first |
| `GetComponentVersionArtifact` | Get a presigned URL for an S3 artifact |
| `DeleteComponent` | Delete a component version |
| `ResolveComponentCandidates` | Resolve component versions for a core device (certificate callers) |
| `CreateDeployment` | Deploy components to a thing or thing group |
| `ListDeployments` | List deployments, latest or all |
| `GetDeployment` | Describe a deployment |
| `CancelDeployment` | Cancel a deployment and its IoT job |
| `DeleteDeployment` | Delete a deployment that is no longer active |
| `ListCoreDevices` | List the core devices that report |
| `GetCoreDevice` | Describe a core device |
| `DeleteCoreDevice` | Forget a core device |
| `ListEffectiveDeployments` | List a core device's deployments and their status |
| `ListInstalledComponents` | List a core device's installed components |
| `BatchAssociateClientDeviceWithCoreDevice` | Associate client devices with a core device |
| `BatchDisassociateClientDeviceFromCoreDevice` | Disassociate client devices |
| `ListClientDevicesAssociatedWithCoreDevice` | List a core device's client devices |
| `GetConnectivityInfo` | Read a core device's connectivity information |
| `UpdateConnectivityInfo` | Set a core device's connectivity information |
| `AssociateServiceRoleToAccount` | Associate the Greengrass service role |
| `GetServiceRoleForAccount` | Read the Greengrass service role |
| `DisassociateServiceRoleFromAccount` | Remove the Greengrass service role |
| `ListTagsForResource` | List a resource's tags |
| `TagResource` | Tag a component version, deployment or core device |
| `UntagResource` | Remove tags from a resource |
<!-- floci:actions:end -->
