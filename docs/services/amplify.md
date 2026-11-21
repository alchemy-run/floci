# AWS Amplify Hosting

**Protocol:** REST JSON, signed with `amplify`
**Endpoint:** `http://localhost:4566/apps`, `/webhooks`, `/artifacts` and the shared `/tags/{resourceArn}` routes. Deployed branches are served at `<branch>.<appId>.amplifyapp.com` and their verified custom domains, by `Host` header.

Floci hosts static sites the way Amplify Hosting does for apps that aren't connected to a repository: you upload a branch's built files, and the branch serves them. Apps, branches, backend environments, webhooks and domain associations behave as on AWS.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateApp` | Create an app |
| `ListApps` | Page through apps |
| `GetApp` | Return an app with its production branch |
| `UpdateApp` | Change an app |
| `DeleteApp` | Delete an app and everything in it |
| `GenerateAccessLogs` | Link a CSV of the requests a domain served |
| `CreateBranch` | Create a branch |
| `ListBranches` | Page through an app's branches |
| `GetBranch` | Return a branch |
| `UpdateBranch` | Change a branch |
| `DeleteBranch` | Delete a branch and its jobs |
| `CreateDeployment` | Stage a manual deployment with pre-signed upload URLs |
| `StartDeployment` | Deploy an upload, an S3 zip or prefix, or a zip URL |
| `StartJob` | Retry a job; release and webhook jobs need a repository |
| `ListJobs` | Page through a branch's jobs, newest first |
| `GetJob` | Return a job with its steps |
| `DeleteJob` | Delete a job that isn't the active one |
| `StopJob` | Stop a pending or running job |
| `ListArtifacts` | List a job's artifacts (none for manual deployments) |
| `GetArtifactUrl` | NotFoundException: Floci keeps no artifacts |
| `CreateBackendEnvironment` | Create a backend environment |
| `ListBackendEnvironments` | Page through backend environments |
| `GetBackendEnvironment` | Return a backend environment |
| `DeleteBackendEnvironment` | Delete a backend environment |
| `CreateWebhook` | Create a branch webhook |
| `ListWebhooks` | Page through an app's webhooks |
| `GetWebhook` | Return a webhook |
| `UpdateWebhook` | Change a webhook's branch or description |
| `DeleteWebhook` | Delete a webhook |
| `CreateDomainAssociation` | Associate a custom domain, verified through Route 53 |
| `ListDomainAssociations` | Page through domain associations |
| `GetDomainAssociation` | Return a domain association |
| `UpdateDomainAssociation` | Change a domain's subdomains |
| `DeleteDomainAssociation` | Remove a domain association |
| `ListTagsForResource` | List an app's, branch's or domain's tags |
| `TagResource` | Tag an app, branch or domain |
| `UntagResource` | Remove tags |
<!-- floci:actions:end -->

## Apps and branches

`CreateApp` gives an app an id (`d` and 13 characters) and the default domain `<appId>.amplifyapp.com`. An app connected to a repository needs an `accessToken` or `oauthToken`. Basic authorization needs `basicAuthCredentials`, the base64 of `username:password`. A branch's stage defaults to `NONE`, its TTL to `5`, and its display name to the branch name with `/` replaced by `-`. A second branch of the same name is a `BadRequestException`. `GetApp` reports the `PRODUCTION` branch as `productionBranch`. Deleting an app deletes its branches, jobs, sites, backend environments, webhooks and domain associations.

## Manual deployments

`CreateDeployment` stages a job and returns a pre-signed `zipUploadUrl` on Floci's endpoint (or one URL per file in `fileUploadUrls` when a `fileMap` is given). `PUT` the zip (or each file) to it, then `StartDeployment` with the `jobId`. `StartDeployment` can instead take a `sourceUrl`: an `s3://bucket/key.zip` (`ZIP`), an `s3://bucket/prefix` (`BUCKET_PREFIX`) or an `https://` URL of a zip. Manual deployments are for apps without a repository.

The job is `RUNNING` for a second, then `SUCCEED`, and becomes the branch's `activeJobId`; a deployment with no files is `FAILED`. Each change is published to EventBridge as an `aws.amplify` `Amplify Deployment Status Change` event with `appId`, `branchName`, `jobId` and `jobStatus`.

`StartJob` with `RETRY` deploys an earlier job's files again. For a branch connected to a repository, `RELEASE` and `WEB_HOOK` jobs fail at the `BUILD` step, because Floci doesn't clone repositories or run builds; for a branch without one they are a `BadRequestException`. `StopJob` stops a pending or running job, and `DeleteJob` deletes any job but the branch's active one. Manual deployments produce no artifacts.

## Serving

A request whose `Host` is `<branch>.<appId>.amplifyapp.com` (the branch name with `/` as `-`) or one of the branch's custom domains is served from the branch's active job:

- `/` and directories serve `index.html`, and a path without an extension tries `<path>/index.html` and `<path>.html`.
- The app's `customRules` apply in order: `301` and `302` redirects, `200` rewrites (a rewrite never hides a file that exists, unless its source is a regex), and `404` and `404-200`. Sources may be exact paths, `<*>` wildcards, `<name>` placeholders, or `</regex/>`.
- A missing file serves `404.html` when the site has one.
- With basic authorization on the branch (or else the app), requests need the matching `Authorization: Basic` header; others get `401`.

Each request is written to the app's access logs. `GenerateAccessLogs` for the app's default domain or an associated domain returns a link to a CSV of the requests in the time range.

## Custom domains

`CreateDomainAssociation` maps subdomains to branches. When the domain's public hosted zone is in Floci's Route 53, Amplify verifies it: it writes the certificate validation CNAME and a CNAME per prefixed subdomain to the branch's host, marks the subdomains verified, and the domain is `AVAILABLE` and served. Without a hosted zone the domain waits in `PENDING_VERIFICATION`, with the records to create. Deleting the association stops serving it.

## Webhooks and backend environments

Webhooks belong to a branch and get a `webhookUrl` on `webhooks.amplify.<region>`; Floci doesn't call them. Backend environments are stored with their stack name and deployment artifacts.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_AMPLIFY_ENABLED` | `true` | Enable or disable Amplify Hosting |

For the upstream API shape, see the [AWS Amplify API Reference](https://docs.aws.amazon.com/amplify/latest/APIReference/Welcome.html).
