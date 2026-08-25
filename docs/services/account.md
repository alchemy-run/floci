# AWS Account Management

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the AWS Account Management alternate-contact lifecycle used by the AWS SDK, CLI, and Alchemy. Each account holds at most one contact per type (`BILLING`, `OPERATIONS`, `SECURITY`). Contacts are isolated by account.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `GetAlternateContact` | `POST /getAlternateContact` | Return the alternate contact for a type |
| `PutAlternateContact` | `POST /putAlternateContact` | Create or replace the alternate contact for a type |
| `DeleteAlternateContact` | `POST /deleteAlternateContact` | Delete the alternate contact for a type |

A missing contact returns `ResourceNotFoundException` (HTTP 404). `AccountId` is optional and selects a member account; omit it to target the caller.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ACCOUNT_ENABLED` | `true` | Enable or disable Account Management |
| `FLOCI_STORAGE_SERVICES_ACCOUNT_MODE` | *(inherits global)* | Optional Account storage-mode override |
| `FLOCI_STORAGE_SERVICES_ACCOUNT_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

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

- Alternate-contact get/put/delete. Primary contact, account name, and Region opt-in are not implemented.
