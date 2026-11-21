# Amazon SES Mail Manager

**Protocol:** JSON 1.0 (`X-Amz-Target: MailManagerSvc.<Action>`) and Smithy RPC v2 CBOR
**Endpoint:** `POST http://localhost:4566/`, signed for `ses`

Floci receives mail for Mail Manager over SMTP: each ingress point listens on its own port, checks mail against its
traffic policy and runs its rule set's actions on what it accepts.

## Ingress points

An ingress point is `PROVISIONING` for a transition, then `ACTIVE`. Its `ARecord` is the configured SMTP host name
(`localhost.floci.io` by default), and `GetIngressPoint` returns one member AWS doesn't, `SmtpPort`: the port its
listener took, from `smtp-port-start` upward. The listener speaks ESMTP (`EHLO`, `MAIL FROM` with `SIZE`, `RCPT TO`,
`DATA`, `RSET`, `NOOP`, `QUIT`). An `AUTH` ingress point takes `AUTH PLAIN` or `AUTH LOGIN` before `MAIL FROM`,
checking the password (any user name) against its SMTP password, or the secret it names (its `password` key when the
secret is JSON, otherwise the whole string); after a password change the previous one keeps working. `STARTTLS` isn't offered, so the TLS policy is kept but not
enforced. An ingress point that isn't `ACTIVE` answers `421`; a closed one stops listening.

## Traffic policies and rule sets

A traffic policy decides each recipient at `RCPT TO`: the first statement whose conditions all hold allows or denies
it, and otherwise the default action does; a denied recipient gets `550`, and a message larger than the policy's
maximum gets `552`. Conditions compare the recipient, the sender's IP address (IPv4 and IPv6 CIDRs), the TLS protocol
and membership of address lists.

A rule set's rules run in order on each accepted message; a rule applies when all its conditions hold and none of its
unless conditions do. Conditions read the envelope (`MAIL FROM`, `HELO`, recipients), the headers (`From`, `To`,
`Cc`, `Subject`, `Sender`, any MIME header), the size, the source IP, the TLS state and address lists. SPF and DKIM
verdicts are `GRAY` and the DMARC policy `NONE`, since Floci checks neither, and add-on analyses report nothing.

Actions run on Floci's services: `Archive`, `WriteToS3` (the message at `<prefix>/<message id>`), `Send` (through
SES), `Relay` (over SMTP to the relay's server, with the user name and password its secret holds), `PublishToSns`,
`InvokeLambda`, `Bounce`, `AddHeader`, `ReplaceRecipient` and `Drop`. `DeliverToMailbox` and `DeliverToQBusiness`
fail, since Floci has neither WorkMail mailboxes nor Q Business indexes for them. A failing action with
`ActionFailurePolicy: DROP` ends the message's processing; otherwise the next action runs.

## Address lists

Address lists hold up to `max-address-list-members` addresses, compared case-insensitively. An import job gives a
`PreSignedUrl` to upload a CSV file (an `address` header, then one address a line) or JSON lines
(`{"address": ...}`); starting the job imports it, counting imported and failed addresses, and it completes after a
transition.

## Archives

Archives keep each archived message with its envelope and headers until its retention ends. Searches and exports
select messages by time and by `To`, `From`, `Cc`, `Subject`, envelope sender and recipients (`CONTAINS`) and
attachments; a search completes after a transition and returns its rows, and an export writes each message as
`<location>/<export id>/<message id>.eml` (with `.metadata.json` beside it when asked) to S3. `GetArchiveMessage`
gives a download link, the metadata and the envelope; `GetArchiveMessageContent` the text and HTML bodies. Deleting
an archive leaves it `PENDING_DELETION`.

Relays, add-on subscriptions and add-on instances are kept as configured. Every resource takes tags.

## Configuration

```yaml
floci:
  services:
    mail-manager:
      enabled: true
      smtp-hostname: localhost.floci.io   # the ingress points' ARecord
      smtp-bind-host: 0.0.0.0
      smtp-port-start: 2525               # ingress points take ports from here
      smtp-port-count: 50
      max-address-list-members: 100000
      transition-millis: 2000             # provisioning, import jobs, searches and exports
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateAddonInstance` | Create an add-on instance |
| `CreateAddonSubscription` | Create an add-on subscription |
| `CreateAddressList` | Create an address list |
| `CreateAddressListImportJob` | Create an import job and the URL its file is uploaded to |
| `CreateArchive` | Create an archive |
| `CreateIngressPoint` | Create an ingress point that receives mail over SMTP |
| `CreateRelay` | Create a relay |
| `CreateRuleSet` | Create a rule set |
| `CreateTrafficPolicy` | Create a traffic policy |
| `DeleteAddonInstance` | Delete an add-on instance |
| `DeleteAddonSubscription` | Delete an add-on subscription |
| `DeleteAddressList` | Delete an address list |
| `DeleteArchive` | Delete an archive (it stays PENDING_DELETION) |
| `DeleteIngressPoint` | Delete an ingress point |
| `DeleteRelay` | Delete a relay |
| `DeleteRuleSet` | Delete a rule set |
| `DeleteTrafficPolicy` | Delete a traffic policy |
| `DeregisterMemberFromAddressList` | Remove an address from an address list |
| `GetAddonInstance` | Describe an add-on instance |
| `GetAddonSubscription` | Describe an add-on subscription |
| `GetAddressList` | Describe an address list |
| `GetAddressListImportJob` | Describe an address list import job |
| `GetArchive` | Describe an archive |
| `GetArchiveExport` | Describe an archive export |
| `GetArchiveMessage` | Get an archived message's download link, metadata and envelope |
| `GetArchiveMessageContent` | Get an archived message's text and HTML bodies |
| `GetArchiveSearch` | Describe an archive search |
| `GetArchiveSearchResults` | Get a completed search's messages |
| `GetIngressPoint` | Describe an ingress point |
| `GetMemberOfAddressList` | Describe an address list member |
| `GetRelay` | Describe a relay |
| `GetRuleSet` | Describe a rule set |
| `GetTrafficPolicy` | Describe a traffic policy |
| `ListAddonInstances` | List add-on instances |
| `ListAddonSubscriptions` | List add-on subscriptions |
| `ListAddressListImportJobs` | List an address list's import jobs |
| `ListAddressLists` | List address lists |
| `ListArchiveExports` | List an archive's exports |
| `ListArchiveSearches` | List an archive's searches |
| `ListArchives` | List archives |
| `ListIngressPoints` | List ingress points |
| `ListMembersOfAddressList` | List an address list's members, optionally by prefix |
| `ListRelays` | List relays |
| `ListRuleSets` | List rule sets |
| `ListTagsForResource` | List a resource's tags |
| `ListTrafficPolicies` | List traffic policies |
| `RegisterMemberToAddressList` | Add an address to an address list |
| `StartAddressListImportJob` | Import the uploaded addresses into the list |
| `StartArchiveExport` | Export an archive's messages to S3 |
| `StartArchiveSearch` | Search an archive's messages by time and filters |
| `StopAddressListImportJob` | Stop an import job |
| `StopArchiveExport` | Stop an archive export |
| `StopArchiveSearch` | Stop an archive search |
| `TagResource` | Tag a Mail Manager resource |
| `UntagResource` | Remove tags |
| `UpdateArchive` | Update an archive |
| `UpdateIngressPoint` | Change an ingress point's rules, policy, password or status |
| `UpdateRelay` | Update a relay |
| `UpdateRuleSet` | Update a rule set |
| `UpdateTrafficPolicy` | Update a traffic policy |
<!-- floci:actions:end -->
