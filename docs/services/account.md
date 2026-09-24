# AWS Account Management

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci supports alternate-contact management over the AWS REST JSON protocol.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `PutAlternateContact` | - |
| `GetAlternateContact` | - |
| `DeleteAlternateContact` | - |
| `GetAccountInformation` | - |
| `PutAccountName` | - |
| `GetContactInformation` | - |
| `PutContactInformation` | - |
| `ListRegions` | - |
| `GetRegionOptStatus` | - |
| `EnableRegion` | - |
| `DisableRegion` | - |
<!-- floci:actions:end -->

Alternate contacts are isolated by caller account and stored through `StorageFactory`.

## AWS-compatible failures

`PutAlternateContact` validates the contact type, name, title, email address, phone number, and optional target account ID. `GetAlternateContact` returns `ResourceNotFoundException` when the requested contact has not been configured. Invalid request data uses `ValidationException` and unsupported cross-account access uses `AccessDeniedException`.

AWS models provider-side `InternalServerException` and `TooManyRequestsException`; Floci does not synthesize those failures without an actual triggering condition.

See the [AWS Account Management API Reference](https://docs.aws.amazon.com/accounts/latest/reference/API_Operations.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ACCOUNT_ENABLED` | `true` | Enable or disable AWS Account Management |
| `FLOCI_SERVICES_ACCOUNT_BOOTSTRAP_CONTACT_INFORMATION` | unset | Initial primary-contact JSON for the configured default account |

### Initialize a local account's primary contact

AWS accounts already have primary contact information when they are created. A new
Floci account has none until it is configured. Supply a `ContactInformation` JSON
object through `FLOCI_SERVICES_ACCOUNT_BOOTSTRAP_CONTACT_INFORMATION` to initialize
that prerequisite before clients start:

```json
{
  "FullName": "Local AWS account",
  "AddressLine1": "1 Example Street",
  "City": "Seattle",
  "StateOrRegion": "WA",
  "PostalCode": "98101",
  "CountryCode": "US",
  "PhoneNumber": "+12025550100"
}
```

The contact is validated and stored by the same implementation as
`PutContactInformation`. Bootstrap only initializes an absent contact for
`FLOCI_DEFAULT_ACCOUNT_ID`; it never overwrites saved contact information or
initializes another account. Without this setting, an unconfigured account still
returns `ResourceNotFoundException` from `GetContactInformation`.
