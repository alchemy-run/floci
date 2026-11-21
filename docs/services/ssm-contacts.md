# SSM Contacts

**Protocol:** JSON 1.1
**Endpoint:** `POST /` + `X-Amz-Target: SSMContacts.*`

The contacts [Incident Manager](ssm-incidents.md) engages: people, escalation plans and on-call schedules. Every
operation needs the account's replication set. Without one, it fails with `Account not found for the request`, as
AWS does.

## Channels

A personal contact has email, SMS and voice channels. A channel is activated with a six-digit code sent to it,
right away or, with `DeferActivation`, on `SendActivationCode`. Email goes through SES and shows up at `GET
/_aws/ses`; SMS and voice go to the SNS phone inbox at `GET /_aws/sns?phone=`. Changing a channel's address means
activating it again.

## Plans and rotations

A plan is checked against its contact's type:

- A personal contact's stages target its own channels.
- An escalation plan's stages target other contacts.
- An on-call schedule names rotations.

A rotation's shifts come from its recurrence: daily, weekly or monthly hand-offs, `RecurrenceMultiplier` hand-offs
per shift, and `NumberOfOnCalls` members on call at once, in turn. Shift coverage limits each day to its covered
hours. An override puts other members on call for a window; its shifts are `OVERRIDDEN`.

## Engagements and pages

An engagement pages people by the contact's plan. Stages are timed from the engagement's start and delivered on
schedule:

- A personal contact's stages deliver to its activated channels, retrying on each channel's interval.
- An escalation plan engages its targets stage by stage, until an essential target reads a page.
- An on-call schedule engages whoever its rotations have on call.

A page carries an accept code. `AcceptPage` with `AcceptCodeValidation: ENFORCE` checks it. Receipts record each
`SENT`, `DELIVERED`, `READ`, `ERROR` and `STOP`.

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateContact` | Create a personal contact, escalation plan or on-call schedule |
| `GetContact` | Describe a contact and its plan |
| `ListContacts` | List contacts, by alias prefix or type |
| `UpdateContact` | Change a contact's name or plan |
| `DeleteContact` | Delete a contact no rotation or escalation plan uses |
| `PutContactPolicy` | Set a contact's resource policy |
| `GetContactPolicy` | Read a contact's resource policy |
| `CreateContactChannel` | Add an email, SMS or voice channel |
| `GetContactChannel` | Describe a channel |
| `ListContactChannels` | List a contact's channels |
| `UpdateContactChannel` | Rename a channel or change its address |
| `DeleteContactChannel` | Delete a channel no plan uses |
| `SendActivationCode` | Send a channel its activation code |
| `ActivateContactChannel` | Activate a channel with its code |
| `DeactivateContactChannel` | Deactivate a channel |
| `CreateRotation` | Create an on-call rotation |
| `GetRotation` | Describe a rotation |
| `ListRotations` | List rotations, by name prefix |
| `UpdateRotation` | Change a rotation |
| `DeleteRotation` | Delete a rotation no schedule uses |
| `CreateRotationOverride` | Put other members on call for a window |
| `GetRotationOverride` | Describe an override |
| `ListRotationOverrides` | List a rotation's overrides in a window |
| `DeleteRotationOverride` | Remove an override |
| `ListRotationShifts` | List a rotation's shifts in a window |
| `ListPreviewRotationShifts` | Preview the shifts of a rotation before creating it |
| `StartEngagement` | Engage a contact |
| `StopEngagement` | Stop an engagement |
| `DescribeEngagement` | Describe an engagement |
| `ListEngagements` | List engagements, by incident or time |
| `DescribePage` | Describe a page |
| `ListPagesByContact` | List a contact's pages |
| `ListPagesByEngagement` | List an engagement's pages |
| `ListPageReceipts` | List a page's receipts |
| `ListPageResolutions` | List the contacts a page was resolved through |
| `AcceptPage` | Acknowledge a page, checking its accept code |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags |
| `ListTagsForResource` | List a resource's tags |
<!-- floci:actions:end -->
