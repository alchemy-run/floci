# AWS License Manager

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSLicenseManager.<Action>`)
**Endpoint:** `POST http://localhost:4566/`, signed for `license-manager`

Floci tracks software licenses the way License Manager does: license configurations count the resources associated
with them, and sellers issue licenses that buyers check out, borrow and receive through grants.

## License configurations

A configuration counts by vCPU, core, socket or instance, with an optional count and hard limit. Its rules are
checked for the counting type (`#minimumVcpus`, `#maximumCores`, `#allowedTenancy` and the rest). A missing
configuration fails with `InvalidParameterValueException` ("Invalid license configuration ARN"), as on AWS.

`UpdateLicenseSpecificationsForResource` associates EC2 instances, hosts, AMIs, RDS databases and managed instances
with configurations. Usage is computed from what's associated: an instance consumes by its instance type's vCPUs (or
one, counting instances or sockets), a stopped or terminated instance nothing, and an associated AMI counts the
running instances launched from it. An association past a hard limit fails with `LicenseUsageException` and is
listed among the configuration's failures. The resource inventory lists the Region's running instances.

Service settings keep an S3 bucket, an SNS topic, the Organizations integration (required by the organization-wide
lists) and cross-account and cross-Region discovery. Report generators write CSV reports to the settings' bucket
under `license-manager-reports/<name>/` and record how the last run went. License type conversions of an instance
are `IN_PROGRESS` for a transition, then `SUCCEEDED`, or `FAILED` when the instance doesn't exist. Configurations,
licenses, grants, report generators, asset groups and rulesets take tags.

## Seller licenses

`CreateLicense` issues a license that is `PENDING_AVAILABLE` for a transition, then `AVAILABLE`; it expires at its
validity's end. Each `CreateLicenseVersion` must name the current version and publishes the next. The issuer's key
fingerprint is `aws:<account>:<issuer>:issuer-fingerprint` (or names the issuer's KMS key). Licenses and versions are
read by the issuer, the beneficiary and accounts with an active grant.

`CheckoutLicense` finds an available license by product SKU and key fingerprint for the beneficiary (or a grantee),
consumes the requested entitlements up to their maximum unless overage is allowed
(`EntitlementNotAllowedException` past it, `NoEntitlementsAllowedException` for one the license lacks), and returns a
consumption token and a PS384-signed JWT of the checkout. Provisional checkouts expire after the license's time to
live; `ExtendLicenseConsumption` renews them and `CheckInLicense` releases them. `CheckoutBorrowLicense` borrows for
the borrow configuration's time to live, checked in early only when the license allows it. `GetLicenseUsage` sums
active consumptions. A license with active consumptions can't be deleted; a license with grants goes
`PENDING_DELETE`, otherwise `DELETED`.

Grants give another account (an account's root ARN or an organization) operations on a license: they're
`PENDING_ACCEPT` until the grantee accepts (`ACTIVE`) or rejects them, versioned by `CreateGrantVersion`, and
`DELETED` at their current version. Received licenses carry their grant's status and allowed operations.
`CreateToken` mints a signed refresh token for a license; `GetAccessToken` exchanges it for an hour-long access token
until the token is deleted or expires.

Floci doesn't require the License Manager service-linked role before the first call.

## License assets

Asset rulesets match instances, license configurations and received licenses by their attributes (matching
statements with `equals`, `notEquals`, `contains` and `beginsWith`, combined with and and or); asset groups list the
assets their rulesets match. AWS-managed rulesets are listed with `ShowAWSManagedLicenseAssetRulesets`. A ruleset an
asset group uses can't be deleted.

## Configuration

```yaml
floci:
  services:
    license-manager:
      enabled: true
      transition-millis: 2000          # how long licenses and conversions take
      max-license-configurations: 1000
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AcceptGrant` | Accept a grant made to this account |
| `CheckInLicense` | Release a checkout or borrow |
| `CheckoutBorrowLicense` | Borrow entitlements for the borrow time to live |
| `CheckoutLicense` | Check out entitlements by product SKU and key fingerprint |
| `CreateGrant` | Grant operations on a license to another account |
| `CreateGrantVersion` | Publish a grant's next version |
| `CreateLicense` | Issue a seller license |
| `CreateLicenseAssetGroup` | Create an asset group of rulesets |
| `CreateLicenseAssetRuleset` | Create a ruleset matching instances, licenses or configurations |
| `CreateLicenseConfiguration` | Create a license configuration |
| `CreateLicenseConversionTaskForResource` | Convert an instance's license type |
| `CreateLicenseManagerReportGenerator` | Create a report generator writing to the settings' bucket |
| `CreateLicenseVersion` | Publish a license's next version |
| `CreateToken` | Mint a signed refresh token for a license |
| `DeleteGrant` | Delete a grant at its current version |
| `DeleteLicense` | Delete a license |
| `DeleteLicenseAssetGroup` | Delete an asset group |
| `DeleteLicenseAssetRuleset` | Delete an unused ruleset |
| `DeleteLicenseConfiguration` | Delete a license configuration and its associations |
| `DeleteLicenseManagerReportGenerator` | Delete a report generator |
| `DeleteToken` | Delete a refresh token |
| `ExtendLicenseConsumption` | Renew a provisional checkout |
| `GetAccessToken` | Exchange a refresh token for an hour-long access token |
| `GetGrant` | Describe a grant or one of its versions |
| `GetLicense` | Describe a license or one of its versions |
| `GetLicenseAssetGroup` | Describe an asset group |
| `GetLicenseAssetRuleset` | Describe a ruleset |
| `GetLicenseConfiguration` | Describe a configuration, with its consumption |
| `GetLicenseConversionTask` | Describe a conversion task |
| `GetLicenseManagerReportGenerator` | Describe a report generator and its last run |
| `GetLicenseUsage` | A license's consumed entitlements |
| `GetServiceSettings` | Read the service settings |
| `ListAssetsForLicenseAssetGroup` | The assets an asset group's rulesets match |
| `ListAssociationsForLicenseConfiguration` | List a configuration's associated resources |
| `ListDistributedGrants` | List grants this account made |
| `ListFailuresForLicenseConfigurationOperations` | List associations a configuration refused |
| `ListLicenseAssetGroups` | List asset groups |
| `ListLicenseAssetRulesets` | List rulesets, with AWS-managed ones on request |
| `ListLicenseConfigurations` | List license configurations |
| `ListLicenseConfigurationsForOrganization` | List the organization's license configurations |
| `ListLicenseConversionTasks` | List conversion tasks |
| `ListLicenseManagerReportGenerators` | List report generators |
| `ListLicenseSpecificationsForResource` | List a resource's license configurations |
| `ListLicenseVersions` | List a license's versions |
| `ListLicenses` | List licenses this account issued |
| `ListReceivedGrants` | List grants made to this account |
| `ListReceivedGrantsForOrganization` | List a license's grants to organizations |
| `ListReceivedLicenses` | List licenses this account benefits from or was granted |
| `ListReceivedLicensesForOrganization` | List licenses granted to the organization |
| `ListResourceInventory` | List the Region's running instances |
| `ListTagsForResource` | List a resource's tags |
| `ListTokens` | List refresh tokens |
| `ListUsageForLicenseConfiguration` | List what each associated resource consumes |
| `RejectGrant` | Reject a grant made to this account |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags |
| `UpdateLicenseAssetGroup` | Update an asset group |
| `UpdateLicenseAssetRuleset` | Update a ruleset |
| `UpdateLicenseConfiguration` | Update a configuration's count, rules or status |
| `UpdateLicenseManagerReportGenerator` | Update a report generator and run it |
| `UpdateLicenseSpecificationsForResource` | Associate or disassociate a resource and configurations |
| `UpdateServiceSettings` | Set the bucket, topic, Organizations integration and discovery |
<!-- floci:actions:end -->
