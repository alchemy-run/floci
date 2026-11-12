# AWS re:Post Private

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/spaces` and the rest, signed for `repostspace`

A private space runs on the account's IAM Identity Center. Administrators, role holders, channel members and invitees
are users and groups of its identity store, and Floci checks each one there.

## Spaces

A space is `CREATING`, then `CREATE_COMPLETED`, with its vanity domain `<subdomain>.private.repost.aws` `APPROVED`.
Subdomains are unique across accounts. The `BASIC` tier holds 10 GiB and `STANDARD` 1 TiB. A space is `UNCONFIGURED`
until it has an administrator (`RegisterAdmin`, a user or group id). `DeleteSpace` makes it `DELETING`, and then it's
gone. A Region has at most 5 spaces. A `roleArn` must be an existing role.

## Roles, channels and invitations

`BatchAddRole` and `BatchRemoveRole` change the space roles of accessors. An accessor that isn't a user or group
comes back in `errors` with code 404. `userCount` counts the users who can enter: administrators and role holders,
with groups expanded to their members. Channels have unique names (at most 50 per space), are `CREATING`, then
`CREATED`, and carry their own roles; `ListChannels` counts their users and groups. `SendInvites` invites users, and
groups' members.

## Configuration

```yaml
floci:
  services:
    repostspace:
      enabled: true
      transition-millis: 2000
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateSpace` | Create a space |
| `GetSpace` | Describe a space |
| `UpdateSpace` | Change a space's tier, role or domains |
| `DeleteSpace` | Delete a space |
| `ListSpaces` | List spaces |
| `RegisterAdmin` | Make a user or group an administrator |
| `DeregisterAdmin` | Remove an administrator |
| `BatchAddRole` | Grant space roles |
| `BatchRemoveRole` | Revoke space roles |
| `SendInvites` | Invite users and groups |
| `CreateChannel` | Create a channel |
| `GetChannel` | Describe a channel |
| `UpdateChannel` | Rename a channel |
| `ListChannels` | List channels |
| `BatchAddChannelRoleToAccessors` | Grant channel roles |
| `BatchRemoveChannelRoleFromAccessors` | Revoke channel roles |
| `TagResource` | Tag a space |
| `UntagResource` | Remove tags from a space |
| `ListTagsForResource` | List a space's tags |
<!-- floci:actions:end -->
