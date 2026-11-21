# End User Messaging Social

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/v1/whatsapp/...`, signed for `social-messaging`

End User Messaging Social sends WhatsApp messages from a WhatsApp Business Account linked to the AWS account.

## Linking an account

Meta's side is simulated. A signup access token stands for a WhatsApp Business Account with one phone number. The
signup callback returns the account and its unregistered numbers. Finalization registers them, each with a six-digit
two-factor PIN, and links the account. Event destinations must be existing SNS topics or Amazon Connect instances.
A Meta account can only be linked once.

## Templates and messages

Templates are checked as Meta checks them:

- a lowercase name
- a category of `MARKETING`, `UTILITY` or `AUTHENTICATION`
- a `BODY` component

Marketing templates stay `PENDING` for a review period, then become `APPROVED`. Library templates come from a small
built-in library.

A message must come from a phone number of a linked account and be a WhatsApp message object. A template message
needs an approved template, and a media message needs existing media. Each sent message is announced to the
account's SNS destinations as a WhatsApp webhook status.

Media is read from S3 when posted and written back to S3 on request. Presigned URLs aren't fetched.

## Flows, keys and calls

A flow's JSON is checked for a `version` and screens with ids. Only a valid draft can be published, only a draft can
be deleted, and a published flow can only be deprecated. A business public key must be an RSA key of at least 2048
bits. Calls need calling enabled in the phone number's call settings.

## Configuration

```yaml
floci:
  services:
    socialmessaging:
      enabled: true
      review-millis: 2000   # how long Meta takes to review a marketing template
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AssociateWhatsAppBusinessAccount` | Start or finish linking a WhatsApp Business Account |
| `GetLinkedWhatsAppBusinessAccount` | Describe a linked account |
| `ListLinkedWhatsAppBusinessAccounts` | List linked accounts |
| `DisassociateWhatsAppBusinessAccount` | Unlink an account |
| `PutWhatsAppBusinessAccountEventDestinations` | Set an account's SNS or Connect destinations |
| `GetLinkedWhatsAppBusinessAccountPhoneNumber` | Describe a phone number |
| `UpdateLinkedWhatsAppBusinessAccountPhoneNumber` | Change a phone number's call settings |
| `CreateWhatsAppMessageTemplate` | Submit a template for review |
| `CreateWhatsAppMessageTemplateFromLibrary` | Create a template from the library |
| `CreateWhatsAppMessageTemplateMedia` | Upload template header media from S3 |
| `GetWhatsAppMessageTemplate` | Read a template |
| `ListWhatsAppMessageTemplates` | List an account's templates |
| `UpdateWhatsAppMessageTemplate` | Change a reviewed template |
| `DeleteWhatsAppMessageTemplate` | Delete a template |
| `ListWhatsAppTemplateLibrary` | Browse the template library |
| `PostWhatsAppMessageMedia` | Upload media from S3 |
| `GetWhatsAppMessageMedia` | Describe media or download it to S3 |
| `DeleteWhatsAppMessageMedia` | Delete media |
| `SendWhatsAppMessage` | Send a message |
| `CreateWhatsAppFlow` | Create a flow |
| `GetWhatsAppFlow` | Describe a flow |
| `ListWhatsAppFlows` | List an account's flows |
| `UpdateWhatsAppFlow` | Change a flow's name, categories or endpoint |
| `UpdateWhatsAppFlowAssets` | Replace a draft flow's JSON |
| `PublishWhatsAppFlow` | Publish a valid draft flow |
| `DeprecateWhatsAppFlow` | Deprecate a published flow |
| `DeleteWhatsAppFlow` | Delete a draft flow |
| `GetWhatsAppFlowPreview` | Get a preview link |
| `ListWhatsAppFlowAssets` | List a flow's assets |
| `PutWhatsAppBusinessPublicKey` | Set a phone number's flow encryption key |
| `GetWhatsAppBusinessPublicKey` | Read a phone number's key |
| `GetWhatsAppCallPermission` | Check call permissions |
| `SendWhatsAppCallEvent` | Send a call event |
| `CreateWhatsAppDataset` | Create a conversion dataset |
| `SendWhatsAppConversionEvent` | Send conversion events |
| `TagResource` | Tag an account |
| `UntagResource` | Remove tags |
| `ListTagsForResource` | List an account's tags |
<!-- floci:actions:end -->
