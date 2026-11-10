# Amazon Route 53 Domains

**Protocol:** JSON 1.1
**Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: Route53Domains_v20140515.<Operation>`, signed for `route53domains`

Floci acts as the registrar for Route 53 Domains. Domains registered or transferred in Floci belong to the calling account and are delegated to a Route 53 hosted zone Floci creates for them. Every change is an operation you can follow, and registrations and renewals appear in the account's billing records. No real domain is ever registered or charged.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CheckDomainAvailability` | Check whether a domain can be registered, with its price |
| `CheckDomainTransferability` | Check whether a domain can be transferred in |
| `GetDomainSuggestions` | Suggest related domain names |
| `ListPrices` | List TLD prices |
| `GetDomainPrices` | Return a domain's TLD prices |
| `RegisterDomain` | Register a domain and delegate it to a new hosted zone |
| `TransferDomain` | Transfer a domain in with its auth code |
| `GetDomainDetail` | Return a domain's details, with redacted contacts under privacy |
| `ListDomains` | Page through the account's domains, filtered and sorted |
| `UpdateDomainNameservers` | Update domain nameservers |
| `UpdateDomainContact` | Update domain contact |
| `UpdateDomainContactPrivacy` | Update domain contact privacy |
| `EnableDomainAutoRenew` | Enable domain auto renew |
| `DisableDomainAutoRenew` | Disable domain auto renew |
| `EnableDomainTransferLock` | Enable domain transfer lock |
| `DisableDomainTransferLock` | Disable domain transfer lock |
| `RenewDomain` | Renew domain |
| `DeleteDomain` | Delete domain |
| `RetrieveDomainAuthCode` | Return a domain's auth code |
| `PushDomain` | Release a .uk domain to another registrar |
| `AssociateDelegationSignerToDomain` | Associate delegation signer to domain |
| `DisassociateDelegationSignerFromDomain` | Disassociate delegation signer from domain |
| `TransferDomainToAnotherAwsAccount` | Offer a domain to another AWS account, with a password |
| `CancelDomainTransferToAnotherAwsAccount` | Cancel an offer to another AWS account |
| `AcceptDomainTransferFromAnotherAwsAccount` | Accept a domain offered by another AWS account |
| `RejectDomainTransferFromAnotherAwsAccount` | Reject a domain offered by another AWS account |
| `ListTagsForDomain` | List tags for domain |
| `UpdateTagsForDomain` | Update tags for domain |
| `DeleteTagsForDomain` | Delete tags for domain |
| `GetContactReachabilityStatus` | Always DONE |
| `ResendContactReachabilityEmail` | Report the registrant email as verified |
| `GetOperationDetail` | Return an operation |
| `ListOperations` | Page through operations, filtered by status, type and time |
| `ResendOperationAuthorization` | Accept the request for a known operation |
| `ViewBilling` | Page through registration, renewal and transfer charges |
<!-- floci:actions:end -->

## Domains and TLDs

Floci supports about 45 TLDs (`com`, `net`, `org`, `io`, `dev`, `app`, `ai`, `co.uk`, `de`, …) with list prices in USD; any other TLD is an `UnsupportedTLD`. A name whose label isn't a valid DNS label is an `InvalidInput`.

A domain is `UNAVAILABLE` when any Floci account holds it, or when it is one of the well-known names Floci treats as registered elsewhere (`example`, `amazon`, `google`, `github`, … under any TLD). Otherwise it is `AVAILABLE`, with its price. `CheckDomainTransferability` answers `DOMAIN_IN_OWN_ACCOUNT`, `DOMAIN_IN_ANOTHER_ACCOUNT`, `TRANSFERABLE` for the well-known names, or `UNTRANSFERABLE` for a domain nobody holds. `GetDomainSuggestions` suggests the label under other TLDs and with common prefixes and suffixes.

## Registering and transferring

`RegisterDomain` and `TransferDomain` check the contacts as the registrar does: first and last name, contact type, address, city, country, a phone number in the `+1.2065550100` form, and an email. A contact that is not a `PERSON` also needs an organization. They also check the duration (1 to 10 years) and, with `Consent`, that the price is within `MaxPrice`.

- A registration needs an available domain. A second registration of the same domain is a `DuplicateRequest`.
- A transfer needs a domain registered elsewhere and an `AuthCode`.
- Floci creates a public hosted zone for the domain and sets the domain's name servers to the zone's, unless a transfer names its own name servers.
- Privacy protection and auto-renew default to on, and so does the transfer lock, except for TLDs whose registries don't support one (`uk`, `co.uk`, `de`, `eu`).
- With privacy on, `GetDomainDetail` returns the redacted contact.

A domain the account doesn't hold answers `InvalidInput` with "Domain … not found in account …", as Route 53 Domains does.

## Changing a domain

`UpdateDomainNameservers` takes one to six name servers; a server inside the domain needs glue IPs. Contacts, privacy, auto-renew and the transfer lock can all be changed. `RenewDomain` needs the current expiry year and keeps the expiry within ten years of today. `AssociateDelegationSignerToDomain` computes each key's tag and SHA-256 DS digest and makes the domain's `DnsSec` `signedDelegation`. `DeleteDomain` releases a domain (not for `.uk` domains, which `PushDomain` releases to another registrar instead).

`TransferDomainToAnotherAwsAccount` returns a password; the other account accepts the domain with it (`AcceptDomainTransferFromAnotherAwsAccount`) or rejects it, and the sender can cancel.

## Operations

Each change returns an `OperationId`. The operation is `SUBMITTED`, `IN_PROGRESS` after a second, then `SUCCESSFUL`. `ListOperations` filters by status, type and submission time. An unknown operation is an `InvalidInput`. Contact reachability is always `DONE`.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_ROUTE53_DOMAINS_ENABLED` | `true` | Enable or disable Route 53 Domains |

For the upstream API shape, see the [Amazon Route 53 Domains API Reference](https://docs.aws.amazon.com/Route53/latest/APIReference/API_Operations_Amazon_Route_53_Domains.html).
