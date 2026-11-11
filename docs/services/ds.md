# AWS Directory Service

**Protocol:** JSON 1.1 (`X-Amz-Target: DirectoryService_20150416.<Action>`)
**Endpoint:** `POST http://localhost:4566/`

Floci runs AWS Managed Microsoft AD directories, AD Connectors, shared directories and hybrid directories in your
floci VPCs, with what they are configured with: trusts, conditional forwarders, IP routes, RADIUS, single
sign-on, certificates with LDAPS, settings, schema extensions, snapshots, Region replicas and event topics.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDirectory` | Create a Simple AD directory (closed to new customers unless enabled) |
| `CreateMicrosoftAD` | Create a Managed Microsoft AD directory in two subnets of a VPC |
| `ConnectDirectory` | Create an AD Connector to the directory at the customer DNS addresses |
| `DescribeDirectories` | Describe directories |
| `DescribeDomainControllers` | Describe a directory's domain controllers |
| `GetDirectoryLimits` | Read the directory limits and counts |
| `DeleteDirectory` | Delete a directory, its network interfaces and security group |
| `ResetUserPassword` | Reset a directory user's password |
| `CreateComputer` | Create a computer object in the directory |
| `CreateSnapshot` | Create a snapshot |
| `DescribeSnapshots` | List snapshots |
| `DeleteSnapshot` | Delete a snapshot |
| `RestoreFromSnapshot` | Restore a directory's users, computers, forwarders, routes and settings |
| `GetSnapshotLimits` | Read the manual snapshot limit and count |
| `UpdateNumberOfDomainControllers` | Add or remove domain controllers |
| `UpdateDirectorySetup` | Update a directory's operating system, size or network type |
| `DescribeUpdateDirectory` | List a directory's setup updates |
| `AddRegion` | Replicate an Enterprise Edition directory to a Region |
| `RemoveRegion` | Remove a Region replica |
| `DescribeRegions` | List a directory's Regions |
| `ShareDirectory` | Share a directory with an account |
| `AcceptSharedDirectory` | Accept a shared directory |
| `RejectSharedDirectory` | Reject a shared directory |
| `UnshareDirectory` | Stop sharing a directory |
| `DescribeSharedDirectories` | List a directory's shares |
| `AddTagsToResource` | Add or replace tags |
| `RemoveTagsFromResource` | Remove tags |
| `ListTagsForResource` | List a directory's tags |
| `CreateConditionalForwarder` | Create a conditional forwarder |
| `UpdateConditionalForwarder` | Update a conditional forwarder |
| `DeleteConditionalForwarder` | Delete a conditional forwarder |
| `DescribeConditionalForwarders` | List a directory's conditional forwarders |
| `CreateTrust` | Create a trust with a remote domain |
| `DescribeTrusts` | List trusts |
| `VerifyTrust` | Verify a trust against its remote domain |
| `UpdateTrust` | Update a trust |
| `DeleteTrust` | Delete a trust |
| `AddIpRoutes` | Add IP routes to the directory |
| `RemoveIpRoutes` | Remove IP routes |
| `ListIpRoutes` | List a directory's IP routes |
| `EnableRadius` | Enable RADIUS multi-factor authentication |
| `UpdateRadius` | Update RADIUS settings |
| `DisableRadius` | Disable RADIUS |
| `CreateAlias` | Set the directory's access URL alias |
| `EnableSso` | Enable single sign-on (needs an alias) |
| `DisableSso` | Disable single sign-on |
| `RegisterCertificate` | Register an LDAPS or client authentication certificate |
| `DescribeCertificate` | Describe a certificate |
| `ListCertificates` | List a directory's certificates |
| `DeregisterCertificate` | Deregister a certificate not in use |
| `EnableLDAPS` | Enable client LDAPS (needs a certificate) |
| `DisableLDAPS` | Disable client LDAPS |
| `DescribeLDAPSSettings` | Read the directory's LDAPS status |
| `EnableClientAuthentication` | Enable smart card authentication (needs a certificate) |
| `DisableClientAuthentication` | Disable smart card authentication |
| `DescribeClientAuthenticationSettings` | Read the directory's client authentication settings |
| `DescribeSettings` | Read the directory's protocol and cipher settings |
| `UpdateSettings` | Update the directory's protocol and cipher settings |
| `StartSchemaExtension` | Extend the schema with LDIF |
| `CancelSchemaExtension` | Cancel a schema extension |
| `ListSchemaExtensions` | List a directory's schema extensions |
| `CreateLogSubscription` | Forward the directory's security logs to a log group |
| `DeleteLogSubscription` | Delete a log subscription |
| `ListLogSubscriptions` | List log subscriptions |
| `RegisterEventTopic` | Announce directory status changes to an SNS topic |
| `DeregisterEventTopic` | Stop announcing to an SNS topic |
| `DescribeEventTopics` | List registered event topics |
| `StartADAssessment` | Assess a self-managed domain for a hybrid directory |
| `DescribeADAssessment` | Describe an assessment |
| `ListADAssessments` | List assessments |
| `DeleteADAssessment` | Delete an assessment |
| `CreateHybridAD` | Create a hybrid directory from a successful assessment |
| `UpdateHybridAD` | Update a hybrid directory's administrator or instances |
| `DescribeHybridADUpdate` | List a hybrid directory's updates |
| `EnableCAEnrollmentPolicy` | Enable certificate enrollment through a Private CA connector |
| `DisableCAEnrollmentPolicy` | Disable certificate enrollment |
| `DescribeCAEnrollmentPolicy` | Read the directory's CA enrollment policy |
| `EnableDirectoryDataAccess` | Enable Directory Service Data access |
| `DisableDirectoryDataAccess` | Disable Directory Service Data access |
| `DescribeDirectoryDataAccess` | Read the Directory Service Data access status |
<!-- floci:actions:end -->

## Directories

A directory goes in two subnets of different Availability Zones of its VPC. Directory Service creates a
`<directoryId>_controllers` security group there and one network interface per domain controller; their
addresses are the directory's DNS addresses. A directory moves `Requested`, `Creating`, `Active`, takes an
automatic snapshot, and publishes each stage change to its registered SNS topics. Deleting it removes its network
interfaces and security group. Passwords follow Active Directory's policy (8 to 64 characters, three character
classes), and requests are checked against the service's published constraints.

As on AWS, Simple AD is closed to new customers: `CreateDirectory` fails with `ClientException` ("Simple AD is no
longer open to new customers") unless `floci.services.ds.simple-ad-open` is set.

An AD Connector reaches the directory that serves one of its customer DNS addresses: the domain name must match
and the service account's credentials must sign in, or the connector fails with the reason in `StageReason`.
A trust verifies when its remote domain is a directory floci knows (in any account) that shares the VPC or is
reachable through the trust's conditional forwarders, and fails verification otherwise.

Snapshots capture users, computers, forwarders, routes and settings, which `RestoreFromSnapshot` puts back.
Directories share across accounts by handshake (accept or reject) or through Organizations, and Enterprise
Edition directories replicate to further Regions. A hybrid directory needs an assessment that found its DNS
addresses and self-managed instances.

## Configuration

```yaml
floci:
  services:
    ds:
      enabled: true
      transition-millis: 2000   # how long a directory, trust, snapshot or update stays in its transitional state
      simple-ad-open: false     # whether accounts may still create Simple AD directories
```
