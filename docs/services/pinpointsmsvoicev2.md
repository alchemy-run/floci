# AWS End User Messaging SMS and Voice (v2)

**Protocol:** JSON 1.0
**Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: PinpointSMSVoiceV2.<Operation>`, signed for `sms-voice`

Floci checks every send the way the service does, then delivers it. Delivery events go to the configuration set's
event destinations. A message to one of your own numbers arrives there, where keywords answer it and `STOP` opts the
sender out.

## Numbers, sender IDs and pools

`RequestPhoneNumber` leases a number of the type the country offers: `SIMULATOR` and `TEN_DLC` in the US, `TOLL_FREE`
in the US and Canada, and `LONG_CODE` elsewhere. A `TEN_DLC` number needs a `COMPLETE` 10DLC campaign registration.
Numbers are `PENDING`, then `ACTIVE`, and start with the `HELP` and `STOP` keywords, which can't be deleted.
`NumberPreference` picks a number that starts with, ends with or contains given digits. Sender IDs are 1–11 letters and
digits. They aren't offered in the US or Canada, and in the United Kingdom they send once registered.

A pool takes an origination identity's settings when it's created. A member must match the pool's message type,
opt-out list, two-way settings and opt-out management, or the call fails with `MESSAGE_TYPE_MISMATCH`,
`OPT_OUT_LIST_MISMATCH`, `TWO_WAY_CONFIG_MISMATCH` or `SELF_MANAGED_OPT_OUTS_MISMATCH`. A pool keeps at least one
member. `UpdatePool` applies its settings to its numbers. Deletion protection blocks releasing numbers and sender IDs,
and deleting pools, protect configurations, Notify configurations and RCS agents.

## Sending

`SendTextMessage`, `SendVoiceMessage` and `SendMediaMessage` send from a phone number, sender ID or pool. A pool sends
from a member that fits the destination. With no `OriginationIdentity`, a send uses the configuration set's default
sender ID, else an active number in the destination's country. A send fails when:

- the number isn't `ACTIVE`, or lacks the capability (`VOICE_CAPABILITY_NOT_AVAILABLE`, `NUMBER_CAPABILITIES_MISMATCH`);
- a US toll-free or 10DLC number's registration isn't `COMPLETE` (`REGISTRATION_NOT_COMPLETE`);
- the destination is in another country and international sending is off (`INVALID_IDENTITY_FOR_DESTINATION_COUNTRY`);
- the destination opted out of the identity's opt-out list (`DESTINATION_PHONE_NUMBER_OPTED_OUT`);
- a protect configuration blocks the destination's country or number;
- the price passes `MaxPrice` (`PRICE_OVER_THRESHOLD`) or the month's spend limit (`ServiceQuotaExceededException`);
- in the `SANDBOX` tier, the destination isn't a verified destination number or one of your numbers.

Media URLs must be readable `s3://` objects of a supported type and size. SSML must be well-formed. `DryRun` checks a
send without sending it. Simulator numbers send free.

## Events and replies

Each send emits its events after `delivery-millis`. A text gets `TEXT_SUCCESSFUL` and `TEXT_DELIVERED`, media
`MEDIA_SUCCESSFUL` and `MEDIA_DELIVERED`, voice `VOICE_INITIATED` through `VOICE_COMPLETED`, and an RCS fallback
`RCS_FALLEN_BACK_TO_SMS`. They go to every enabled event destination whose `MatchingEventTypes` match (`ALL`,
`TEXT_ALL`, or the event type), as the service's event JSON. That's an SNS publish, a CloudWatch Logs event in the
`sms-voice-events` stream, or a Firehose record. An event destination's topic, log group or delivery stream, and its
role, must exist.

A message to one of your own numbers (in any account) arrives there. Its first word is matched against the number's
keywords, or its pool's. `OPT_OUT` adds the sender to the opt-out list as an end-user opt-out, `OPT_IN` removes them,
and every keyword sends its message back. A two-way number publishes what arrives to its SNS channel, with
`originationNumber`, `destinationNumber`, `messageKeyword`, `messageBody`, `inboundMessageId` and
`previousPublishedMessageId`.

## Registrations

Registration types (US toll-free, US 10DLC brand and campaign, United Kingdom sender ID) define sections and fields.
`PutRegistrationFieldValue` checks select choices, text length, emails, URLs, phone numbers and attachments (PNG,
JPEG or PDF, up to 500 KB, from the body or S3). Values change only in a `DRAFT` version. Submitting needs every required
field, and an association for types that associate before submitting. A submitted version is reviewed after the
transition delay and approved, or denied when a campaign's brand registration isn't `COMPLETE`. Approval marks the
associated numbers and sender IDs registered.

## Everything else

Protect configurations hold a country rule set per capability, plus number overrides (`ALLOW` or `BLOCK`, until they
expire). One can be the account default. Verified destination numbers get a six-digit code by text or voice, valid for
10 minutes. Notify configurations are `PENDING`, then `ACTIVE`, and fill the OTP template's `code`, `brand` and `expiry`.
RCS agents stay `CREATED` (no carrier launch), so RCS sends use their `FallbackConfiguration`. Resource policies on
numbers, sender IDs, pools and opt-out lists share them with the accounts they name (`Owner: SHARED`).
`CarrierLookup` returns the country and dialing code, and the number's type.

## Configuration

```yaml
floci:
  services:
    pinpoint-sms-voice-v2:
      enabled: true
      transition-millis: 2000          # numbers, pools, Notify configurations, registration reviews
      delivery-millis: 200             # how long after a send its events go out
      account-tier: PRODUCTION         # or SANDBOX
      monthly-spend-limit: 10000       # USD per message kind; overrides may set less
      end-user-opt-outs-locked: false  # true: DeleteOptedOutNumber can't undo a STOP
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AssociateOriginationIdentity` | Add an identity to a pool |
| `AssociateProtectConfiguration` | Attach a protect configuration to a configuration set |
| `CarrierLookup` | Look up a number's country and type |
| `CreateConfigurationSet` | Create a configuration set |
| `CreateEventDestination` | Create an event destination |
| `CreateNotifyConfiguration` | Create a Notify configuration |
| `CreateOptOutList` | Create an opt-out list |
| `CreatePool` | Create a pool |
| `CreateProtectConfiguration` | Create a protect configuration |
| `CreateRcsAgent` | Create an RCS agent |
| `CreateRegistration` | Create a registration |
| `CreateRegistrationAssociation` | Create a registration association |
| `CreateRegistrationAttachment` | Create a registration attachment |
| `CreateRegistrationVersion` | Create a registration version |
| `CreateVerifiedDestinationNumber` | Create a verified destination number |
| `DeleteAccountDefaultProtectConfiguration` | Clear the account's default protect configuration |
| `DeleteConfigurationSet` | Delete a configuration set |
| `DeleteDefaultMessageType` | Clear a configuration set's default message type |
| `DeleteDefaultSenderId` | Clear a configuration set's default sender ID |
| `DeleteEventDestination` | Delete an event destination |
| `DeleteKeyword` | Delete a keyword |
| `DeleteMediaMessageSpendLimitOverride` | Remove the media monthly spend limit override |
| `DeleteNotifyConfiguration` | Delete a Notify configuration |
| `DeleteNotifyMessageSpendLimitOverride` | Remove the Notify monthly spend limit override |
| `DeleteOptOutList` | Delete an opt-out list |
| `DeleteOptedOutNumber` | Opt a number back in |
| `DeletePool` | Delete a pool |
| `DeleteProtectConfiguration` | Delete a protect configuration |
| `DeleteProtectConfigurationRuleSetNumberOverride` | Delete a protect configuration rule set number override |
| `DeleteRcsAgent` | Delete an RCS agent |
| `DeleteRcsMessageSpendLimitOverride` | Remove the RCS monthly spend limit override |
| `DeleteRegistration` | Delete a registration |
| `DeleteRegistrationAttachment` | Delete a registration attachment |
| `DeleteRegistrationFieldValue` | Delete a registration field value |
| `DeleteResourcePolicy` | Delete a resource policy |
| `DeleteTextMessageSpendLimitOverride` | Remove the text monthly spend limit override |
| `DeleteVerifiedDestinationNumber` | Delete a verified destination number |
| `DeleteVoiceMessageSpendLimitOverride` | Remove the voice monthly spend limit override |
| `DescribeAccountAttributes` | Read the account tier and defaults |
| `DescribeAccountLimits` | Read quotas and usage |
| `DescribeConfigurationSets` | Describe configuration sets |
| `DescribeKeywords` | Describe keywords |
| `DescribeNotifyConfigurations` | Describe Notify configurations |
| `DescribeNotifyTemplates` | Describe Notify templates |
| `DescribeOptOutLists` | Describe opt-out lists |
| `DescribeOptedOutNumbers` | Describe opted out numbers |
| `DescribePhoneNumbers` | Describe phone numbers |
| `DescribePools` | Describe pools |
| `DescribeProtectConfigurations` | Describe protect configurations |
| `DescribeRcsAgentCountryLaunchStatus` | Describe RCS agent country launch status |
| `DescribeRcsAgents` | Describe RCS agents |
| `DescribeRegistrationAttachments` | Describe registration attachments |
| `DescribeRegistrationFieldDefinitions` | Describe registration field definitions |
| `DescribeRegistrationFieldValues` | Describe registration field values |
| `DescribeRegistrationSectionDefinitions` | Describe registration section definitions |
| `DescribeRegistrationTypeDefinitions` | Describe registration type definitions |
| `DescribeRegistrationVersions` | Describe registration versions |
| `DescribeRegistrations` | Describe registrations |
| `DescribeSenderIds` | Describe sender ids |
| `DescribeSpendLimits` | Read monthly spend limits |
| `DescribeVerifiedDestinationNumbers` | Describe verified destination numbers |
| `DisassociateOriginationIdentity` | Remove an identity from a pool |
| `DisassociateProtectConfiguration` | Detach a protect configuration from a configuration set |
| `DiscardRegistrationVersion` | Discard a draft registration version |
| `GetProtectConfigurationCountryRuleSet` | Get a protect configuration country rule set |
| `GetResourcePolicy` | Get a resource policy |
| `ListAvailablePhoneNumbers` | List numbers available to lease |
| `ListNotifyCountries` | List Notify countries |
| `ListPoolOriginationIdentities` | List pool origination identities |
| `ListProtectConfigurationRuleSetNumberOverrides` | List protect configuration rule set number overrides |
| `ListRegistrationAssociations` | List registration associations |
| `ListTagsForResource` | List a resource's tags |
| `PutKeyword` | Put a keyword |
| `PutMessageFeedback` | Record a message's feedback |
| `PutOptedOutNumber` | Opt a number out |
| `PutProtectConfigurationRuleSetNumberOverride` | Put a protect configuration rule set number override |
| `PutRegistrationFieldValue` | Put a registration field value |
| `PutResourcePolicy` | Put a resource policy |
| `ReleasePhoneNumber` | Release a phone number |
| `ReleaseSenderId` | Release a sender ID |
| `RequestPhoneNumber` | Lease a phone number |
| `RequestSenderId` | Request a sender ID |
| `SendDestinationNumberVerificationCode` | Send a verification code |
| `SendMediaMessage` | Send an MMS |
| `SendNotifyTextMessage` | Send a Notify OTP by SMS |
| `SendNotifyVoiceMessage` | Send a Notify OTP by voice |
| `SendRcsMessage` | Send an RCS message, or its fallback |
| `SendTextMessage` | Send an SMS |
| `SendVoiceMessage` | Send a voice message |
| `SetAccountDefaultProtectConfiguration` | Make a protect configuration the account default |
| `SetDefaultMessageFeedbackEnabled` | Turn message feedback on by default |
| `SetDefaultMessageType` | Set a configuration set's default message type |
| `SetDefaultSenderId` | Set a configuration set's default sender ID |
| `SetMediaMessageSpendLimitOverride` | Set the media monthly spend limit |
| `SetNotifyMessageSpendLimitOverride` | Set the Notify monthly spend limit |
| `SetRcsMessageSpendLimitOverride` | Set the RCS monthly spend limit |
| `SetTextMessageSpendLimitOverride` | Set the text monthly spend limit |
| `SetVoiceMessageSpendLimitOverride` | Set the voice monthly spend limit |
| `SubmitRegistrationVersion` | Submit a registration for review |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
| `UpdateEventDestination` | Update an event destination |
| `UpdateNotifyConfiguration` | Update a Notify configuration |
| `UpdatePhoneNumber` | Update a phone number |
| `UpdatePool` | Update a pool |
| `UpdateProtectConfiguration` | Update a protect configuration |
| `UpdateProtectConfigurationCountryRuleSet` | Update a protect configuration country rule set |
| `UpdateRcsAgent` | Update an RCS agent |
| `UpdateSenderId` | Update a sender ID |
| `VerifyDestinationNumber` | Verify a destination number |
<!-- floci:actions:end -->
