# AWS User Notifications Contacts

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/emailcontacts/...`, signed for `notifications-contacts`

Email contacts are where [User Notifications](notifications.md) sends notifications. Floci emails activation codes
through its SES, so they can be read from `GET /_aws/ses`.

## Email contacts

A contact's email address is unique in the account, and its ARN names no Region. It is `inactive` until activated.
`SendActivationCode` emails a 7-character code from `no-reply@notifications.aws`; a new code can be sent a minute after
the last, and a code expires after `activation-code-seconds`. `ActivateEmailContact` with the code makes the contact
`active`; a wrong or expired code is a `ValidationException`, and an active contact can't be activated again. Contacts
take tags.

## Configuration

```yaml
floci:
  services:
    notifications-contacts:
      enabled: true
      activation-code-seconds: 1800   # how long an activation code stays valid
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ActivateEmailContact` | Activate a contact with its code |
| `CreateEmailContact` | Create an inactive email contact |
| `DeleteEmailContact` | Delete an email contact |
| `GetEmailContact` | Describe an email contact |
| `ListEmailContacts` | List email contacts |
| `ListTagsForResource` | List a contact's tags |
| `SendActivationCode` | Email an activation code through SES |
| `TagResource` | Tag a contact |
| `UntagResource` | Remove tags from a contact |
<!-- floci:actions:end -->
