# AWS User Notifications

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/notification-configurations/...`, signed for `notifications`

Floci turns the EventBridge events an event rule matches into notification events and emails them to the
configuration's active email contacts through Floci's SES.

## Notification configurations and event rules

A notification configuration's name is unique in the account, and its ARN names no Region. Its status follows its event
rules: `INACTIVE` without any, `ACTIVE` when every rule is active in every Region it names, and `PARTIALLY_ACTIVE`
otherwise. An event rule names an AWS event source and detail type, an optional event pattern, and the Regions it
watches; in each Region it creates a managed EventBridge rule named `AWSUserNotificationsManagedRule-<id>` on the
default bus. A rule is `CREATING` (or `UPDATING`), then `ACTIVE`. Deleting a configuration deletes its rules and their
managed rules. Configurations and event rules take tags.

Channels are associated with a configuration by ARN; associating one twice is a `ConflictException`. Email contacts
from [User Notifications Contacts](notificationscontacts.md) receive mail.

## Notification events and delivery

Each matched event becomes a notification event in the account's first active notification hub's Region, or the
default Region without one. Its content carries the source event's id, account, Region, time and resources, a headline
and the event's detail. Detail types naming a failure, error, alarm, unhealthy state or termination are `ALERT`s, the
rest `INFORMATIONAL`.

With `aggregationDuration` set, the first event of a window is delivered at once as an `AGGREGATE`; later events in the
window are its `CHILD` events, and the aggregate is delivered again with their count when the window closes (`SHORT`
after 5 minutes, `LONG` after 12 hours). Delivered mail is listed by `GET /_aws/ses`, from
`no-reply@notifications.aws`.

## Hubs, managed notifications and organizations

`RegisterNotificationHub` registers a Region as a hub (`REGISTERING`, then `ACTIVE`); the last active hub can't be
deregistered. Managed notification configurations are AWS Health's categories (Billing, Issue, Scheduled change,
Account notification, Security); channels associate with them like with configurations, and the account's primary
contact is associated by default. Organization access can be enabled and disabled, and configurations associate with
organizational units and member accounts.

## Configuration

```yaml
floci:
  services:
    notifications:
      enabled: true
      transition-millis: 2000   # how long rules and hubs stay CREATING, UPDATING or REGISTERING
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AssociateChannel` | Associate a channel with a configuration |
| `AssociateManagedNotificationAccountContact` | Associate an account contact with a managed configuration |
| `AssociateManagedNotificationAdditionalChannel` | Associate a channel with a managed configuration |
| `AssociateOrganizationalUnit` | Associate an organizational unit with a configuration |
| `CreateEventRule` | Create an event rule and its managed EventBridge rules |
| `CreateNotificationConfiguration` | Create a notification configuration |
| `DeleteEventRule` | Delete an event rule and its managed rules |
| `DeleteNotificationConfiguration` | Delete a configuration and its event rules |
| `DeregisterNotificationHub` | Deregister a notification hub |
| `DisableNotificationsAccessForOrganization` | Disable organization access |
| `DisassociateChannel` | Remove a channel from a configuration |
| `DisassociateManagedNotificationAccountContact` | Remove an account contact from a managed configuration |
| `DisassociateManagedNotificationAdditionalChannel` | Remove a channel from a managed configuration |
| `DisassociateOrganizationalUnit` | Remove an organizational unit from a configuration |
| `EnableNotificationsAccessForOrganization` | Enable organization access |
| `GetEventRule` | Describe an event rule and its per-Region status |
| `GetManagedNotificationChildEvent` | Describe a managed child event |
| `GetManagedNotificationConfiguration` | Describe an AWS Health managed configuration |
| `GetManagedNotificationEvent` | Describe a managed notification event |
| `GetNotificationConfiguration` | Describe a configuration |
| `GetNotificationEvent` | Describe a notification event |
| `GetNotificationsAccessForOrganization` | Read organization access |
| `ListChannels` | List a configuration's channels |
| `ListEventRules` | List a configuration's event rules |
| `ListManagedNotificationChannelAssociations` | List a managed configuration's channels |
| `ListManagedNotificationChildEvents` | List managed child events |
| `ListManagedNotificationConfigurations` | List AWS Health managed configurations |
| `ListManagedNotificationEvents` | List managed notification events |
| `ListMemberAccounts` | List a configuration's member accounts |
| `ListNotificationConfigurations` | List configurations |
| `ListNotificationEvents` | List notification events, with aggregates and children |
| `ListNotificationHubs` | List notification hubs |
| `ListOrganizationalUnits` | List a configuration's organizational units |
| `ListTagsForResource` | List a resource's tags |
| `RegisterNotificationHub` | Register a Region as a notification hub |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
| `UpdateEventRule` | Update an event rule's pattern and Regions |
| `UpdateManagedNotificationChannelAssociation` | Update a managed configuration's channels |
| `UpdateNotificationConfiguration` | Update a configuration's name, description and aggregation |
<!-- floci:actions:end -->
