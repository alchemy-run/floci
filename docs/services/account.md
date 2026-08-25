# AWS Account Management

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements AWS Account Management used by the AWS SDK, CLI, and Alchemy. Each 12-digit account stores a display name, one primary contact, at most one alternate contact per type (`BILLING`, `OPERATIONS`, `SECURITY`), and Region opt-in status. Settings are isolated by account.

A first read seeds AWS-shaped defaults so the display name and primary contact always exist (they cannot be deleted on live AWS). Destroying the primary contact is a no-op and leaves the last value in place.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `GetAccountInformation` | `POST /getAccountInformation` | Return account id, name, created date, and state |
| `PutAccountName` | `POST /putAccountName` | Set the account display name |
| `GetContactInformation` | `POST /getContactInformation` | Return the primary contact |
| `PutContactInformation` | `POST /putContactInformation` | Create or replace the primary contact |
| `GetAlternateContact` | `POST /getAlternateContact` | Return the alternate contact for a type |
| `PutAlternateContact` | `POST /putAlternateContact` | Create or replace the alternate contact for a type |
| `DeleteAlternateContact` | `POST /deleteAlternateContact` | Delete the alternate contact for a type |
| `ListRegions` | `POST /listRegions` | List Region opt-in statuses |
| `GetRegionOptStatus` | `POST /getRegionOptStatus` | Return opt-in status for one Region |
| `EnableRegion` | `POST /enableRegion` | Opt in to a Region |
| `DisableRegion` | `POST /disableRegion` | Opt out of a Region that is not enabled by default |

A missing alternate contact returns `ResourceNotFoundException` (HTTP 404). `AccountId` is optional and selects a member account; omit it to target the caller.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ACCOUNT_ENABLED` | `true` | Enable or disable Account Management |
| `FLOCI_STORAGE_SERVICES_ACCOUNT_MODE` | *(inherits global)* | Optional Account storage-mode override |
| `FLOCI_STORAGE_SERVICES_ACCOUNT_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws account put-contact-information --contact-information '{
  "FullName": "Jane Doe",
  "AddressLine1": "123 Any Street",
  "City": "Seattle",
  "StateOrRegion": "WA",
  "PostalCode": "98101",
  "CountryCode": "US",
  "PhoneNumber": "+12025550100",
  "CompanyName": "Acme"
}'
aws account get-contact-information

aws account put-alternate-contact \
  --alternate-contact-type OPERATIONS \
  --name "Ops Team" \
  --title "On-Call" \
  --email-address ops@example.com \
  --phone-number "+15555550100"

aws account get-alternate-contact --alternate-contact-type OPERATIONS
aws account delete-alternate-contact --alternate-contact-type OPERATIONS
```

## Current Scope

- Account name get/put
- Primary contact get/put (delete is a no-op)
- Alternate-contact get/put/delete
- Region opt-in list/get/enable/disable
