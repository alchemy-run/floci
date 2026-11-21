# AWS Chatbot

**Protocol:** REST JSON
**Endpoint:** `POST http://localhost:4566/<operation-path>` (for example `/create-slack-channel-configuration`), signed for `chatbot`.

Floci runs AWS Chatbot (Amazon Q Developer in chat applications) with its channel configurations, custom actions and account settings. It also delivers each SNS notification to the channels of the configurations that list the notification's topic.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AssociateToConfiguration` | Link a custom action to a configuration |
| `CreateChimeWebhookConfiguration` | Create a Chime webhook configuration |
| `CreateCustomAction` | Create a custom action |
| `CreateMicrosoftTeamsChannelConfiguration` | Create a Teams configuration for an onboarded team |
| `CreateSlackChannelConfiguration` | Create a Slack configuration for an onboarded workspace |
| `DeleteChimeWebhookConfiguration` | Delete a Chime configuration |
| `DeleteCustomAction` | Delete a custom action and its associations |
| `DeleteMicrosoftTeamsChannelConfiguration` | Delete a Teams configuration |
| `DeleteMicrosoftTeamsConfiguredTeam` | Remove a team's onboarding |
| `DeleteMicrosoftTeamsUserIdentity` | Delete a Teams user identity |
| `DeleteSlackChannelConfiguration` | Delete a Slack configuration |
| `DeleteSlackUserIdentity` | Delete a Slack user identity |
| `DeleteSlackWorkspaceAuthorization` | Remove a workspace's onboarding |
| `DescribeChimeWebhookConfigurations` | Page through Chime configurations |
| `DescribeSlackChannelConfigurations` | Page through Slack configurations |
| `DescribeSlackUserIdentities` | Page through Slack user identities |
| `DescribeSlackWorkspaces` | Page through onboarded Slack workspaces |
| `DisassociateFromConfiguration` | Unlink a custom action |
| `GetAccountPreferences` | The account's preferences |
| `GetCustomAction` | Describe a custom action |
| `GetMicrosoftTeamsChannelConfiguration` | Describe a Teams configuration |
| `ListAssociations` | Page through a configuration's associations |
| `ListCustomActions` | Page through custom actions |
| `ListMicrosoftTeamsChannelConfigurations` | Page through Teams configurations |
| `ListMicrosoftTeamsConfiguredTeams` | Page through onboarded teams |
| `ListMicrosoftTeamsUserIdentities` | Page through Teams user identities |
| `ListTagsForResource` | List tags |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags |
| `UpdateAccountPreferences` | Change the account's preferences |
| `UpdateChimeWebhookConfiguration` | Change a Chime configuration |
| `UpdateCustomAction` | Change a custom action |
| `UpdateMicrosoftTeamsChannelConfiguration` | Change a Teams configuration |
| `UpdateSlackChannelConfiguration` | Change a Slack configuration |
<!-- floci:actions:end -->

## Workspaces and teams

On AWS, a Slack workspace or Microsoft Teams team is onboarded in the chat application, outside the API. Floci starts every account with one of each onboarded:

| | ID | Name |
| --- | --- | --- |
| Slack workspace | `TFLOCI00001` | Floci |
| Microsoft Teams team | `f10c1000-0000-4000-8000-000000000001`, tenant `f10c1000-0000-4000-8000-0000000000aa` | Floci |

A channel configuration for any other workspace or team fails as AWS fails it:

- Slack: `InvalidRequestException`, `Slack workspace <id> is not authorized with AWS account <account>`
- Microsoft Teams: `The team id you are using is not configured…`

`DeleteSlackWorkspaceAuthorization` and `DeleteMicrosoftTeamsConfiguredTeam` remove the onboarding. A workspace or team that still has configurations can't be removed. Removing a team that was never configured fails with `ResourceNotFoundException`.

## Channel configurations

Slack, Microsoft Teams and Chime webhook configurations have names that are unique in the account. Their ARNs are `arn:aws:chatbot::<account>:chat-configuration/<kind>/<name>`, since Chatbot is global.

The IAM role and the SNS topics a configuration names must exist. The logging level is `ERROR`, `INFO` or `NONE`. Guardrail policies default to `AdministratorAccess`, as AWS defaults them. Deleting a configuration deletes its user identities and custom action associations. `DescribeSlackChannelConfigurations` with an unknown ARN returns an empty list, as on AWS.

## Notifications

A message published to a topic a configuration lists is delivered to the configuration's channel. Chatbot delivers the notifications it supports:

- CloudWatch alarm state changes
- EventBridge events from AWS services
- AWS Chatbot custom notifications (`{"version": "1.0", "source": "custom", "content": {...}}`)

Any other message, plain text included, isn't delivered.

Each delivery is recorded with the channel's latest 50 notifications. A Chime configuration's webhook receives the message as a Chime webhook post. The logging level decides what's logged to CloudWatch Logs `/aws/chatbot/<configuration name>` (one stream per channel), in the topic's Region: `INFO` logs every notification, and `ERROR` logs only the ones that couldn't be delivered.

## Custom actions and associations

Custom action names and aliases are unique in the account. A `ClientToken` makes a create idempotent. `AssociateToConfiguration` links a custom action to a configuration, at most 50 per configuration, and both must exist. Deleting an action removes its associations.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_CHATBOT_ENABLED` | `true` | Enable or disable AWS Chatbot |

For the upstream API shape, see the [AWS Chatbot API Reference](https://docs.aws.amazon.com/chatbot/latest/APIReference/Welcome.html).
