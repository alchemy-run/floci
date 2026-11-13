# Amazon Managed Grafana

**Protocol:** REST JSON (`/workspaces/...`, `/tags/<arn>`, `/versions`)
**Endpoint:** `http://localhost:4566/`, signed for `grafana`.

Each workspace runs a real Grafana of its version in Docker: 9.4.17, 10.4.19, 12.4.15 or 13.2.6, the last release
of each line Amazon Managed Grafana offers, with 13.2 the default. A workspace is `CREATING` until its Grafana
answers, then `ACTIVE`. Its endpoint, `<id>.grafana-workspace.<region>.localhost.floci.io:4566`, serves that Grafana;
the host resolves to floci on the host machine and inside Lambda and task containers. Service accounts and tokens
the API makes are made in the workspace's Grafana, so the `glsa_` key `CreateWorkspaceServiceAccountToken` returns
works against the endpoint as a Bearer token. An API key is a service account of the key's role with one token, as
Grafana keeps API keys since version 10.

`UpdateWorkspaceConfiguration` applies `plugins.pluginAdminEnabled` and `unifiedAlerting.enabled` to Grafana, and
upgrades the workspace to a later version in place. Its dashboards and settings live on a per-workspace volume, so
an upgrade keeps them. `AWS_SSO` authentication needs an IAM Identity Center instance in the account; Identity
Center permissions apply only to workspaces that use it. Grafana itself takes only the credentials the API hands
out: set `floci.services.grafana.anonymous-role` to `Viewer`, `Editor` or `Admin` to let a browser in. Without
Docker, or with `floci.services.grafana.mock: true`, a workspace is `ACTIVE` without a Grafana and its keys are
minted in the same format.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateWorkspace` | Create a workspace and start its Grafana |
| `ListWorkspaces` | List workspaces |
| `DescribeWorkspace` | Describe a workspace |
| `UpdateWorkspace` | Change a workspace's settings |
| `DeleteWorkspace` | Delete a workspace and its Grafana |
| `DescribeWorkspaceAuthentication` | Read a workspace's authentication |
| `UpdateWorkspaceAuthentication` | Set a workspace's providers and SAML configuration |
| `DescribeWorkspaceConfiguration` | Read a workspace's Grafana configuration and version |
| `UpdateWorkspaceConfiguration` | Configure Grafana or upgrade its version |
| `ListPermissions` | List Identity Center users and groups with roles |
| `UpdatePermissions` | Grant or revoke Identity Center roles |
| `AssociateLicense` | Add a Grafana Enterprise license or free trial |
| `DisassociateLicense` | Remove a license |
| `CreateWorkspaceServiceAccount` | Make a service account in the workspace's Grafana |
| `ListWorkspaceServiceAccounts` | List service accounts |
| `DeleteWorkspaceServiceAccount` | Delete a service account |
| `CreateWorkspaceServiceAccountToken` | Make a service account token in Grafana |
| `ListWorkspaceServiceAccountTokens` | List a service account's tokens |
| `DeleteWorkspaceServiceAccountToken` | Revoke a token |
| `CreateWorkspaceApiKey` | Make an API key |
| `DeleteWorkspaceApiKey` | Delete an API key |
| `ListVersions` | List versions, or those a workspace can upgrade to |
| `ListTagsForResource` | List a workspace's tags |
| `TagResource` | Tag a workspace |
| `UntagResource` | Remove tags from a workspace |
<!-- floci:actions:end -->
