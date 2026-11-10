# AWS App Runner

**Protocol:** JSON 1.0
**Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: AppRunner.<Operation>`, signed for `apprunner`. Services are served at `https://<id>.<region>.awsapprunner.com` (and their active custom domains), by `Host` header.

Floci runs App Runner services as Docker containers: each service runs its image, with its configuration, behind its service URL.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AssociateCustomDomain` | Associate a custom domain, returning its validation records |
| `CreateAutoScalingConfiguration` | Create an auto scaling configuration revision |
| `CreateConnection` | Create a source connection (stays PENDING_HANDSHAKE) |
| `CreateObservabilityConfiguration` | Create an observability configuration revision |
| `CreateService` | Create a service and deploy its image |
| `CreateVpcConnector` | Create a VPC connector revision |
| `CreateVpcIngressConnection` | Give a service a private domain |
| `DeleteAutoScalingConfiguration` | Make a revision (or all) INACTIVE |
| `DeleteConnection` | Delete a connection |
| `DeleteObservabilityConfiguration` | Make a revision INACTIVE |
| `DeleteService` | Stop and delete a service |
| `DeleteVpcConnector` | Make a VPC connector INACTIVE |
| `DeleteVpcIngressConnection` | Delete a VPC ingress connection |
| `DescribeAutoScalingConfiguration` | Describe an auto scaling configuration |
| `DescribeCustomDomains` | Describe custom domains, validating them through Route 53 |
| `DescribeObservabilityConfiguration` | Describe an observability configuration |
| `DescribeService` | Describe a service |
| `DescribeVpcConnector` | Describe a VPC connector |
| `DescribeVpcIngressConnection` | Describe a VPC ingress connection |
| `DisassociateCustomDomain` | Remove a custom domain |
| `ListAutoScalingConfigurations` | Page through active auto scaling configurations |
| `ListConnections` | Page through connections |
| `ListObservabilityConfigurations` | Page through active observability configurations |
| `ListOperations` | Page through a service's operations, newest first |
| `ListServices` | Page through services |
| `ListServicesForAutoScalingConfiguration` | List the services using a configuration |
| `ListTagsForResource` | List tags |
| `ListVpcConnectors` | Page through active VPC connectors |
| `ListVpcIngressConnections` | Page through VPC ingress connections |
| `PauseService` | Stop a running service's container |
| `ResumeService` | Start a paused service again |
| `StartDeployment` | Redeploy a running service |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags |
| `UpdateDefaultAutoScalingConfiguration` | Make a configuration the account default |
| `UpdateService` | Redeploy with a new configuration, rolling back on failure |
| `UpdateVpcIngressConnection` | Change a VPC ingress connection's VPC |
<!-- floci:actions:end -->

## Services

`CreateService` takes an image from ECR (which needs `AuthenticationConfiguration.AccessRoleArn`) or ECR Public. The service is `OPERATION_IN_PROGRESS` while Floci deploys it in the background:

- The image is pulled (an ECR image from Floci's ECR) and started for `linux/amd64`, with the image configuration's port (default `8080`, also set as `PORT`), runtime environment variables, `StartCommand`, and runtime secrets read from Secrets Manager (a secret or one JSON key of it) and SSM Parameter Store.
- With an instance role, the container gets that role's credentials and Floci's endpoint, so its AWS SDK calls reach Floci as the role.
- The health check (TCP, or HTTP on its path) must pass within 90 seconds.

The service is then `RUNNING`, or `CREATE_FAILED` with the reason in its service log. CPU and memory are reported in units (`0.25 vCPU` is `256`, `0.5 GB` is `512`). A service without an auto scaling configuration uses the account's `DefaultConfiguration`.

`UpdateService` redeploys with the new configuration; when the new deployment fails its health check, the service rolls back to the configuration that last ran (`ROLLBACK_SUCCEEDED`). `StartDeployment` redeploys, pulling the image again. `PauseService` stops the container, so the URL answers 404, and `ResumeService` starts it again. `DeleteService` stops it and leaves the service `DELETED`. Each of these is an operation that `ListOperations` reports. Services built from a source code repository fail to deploy, because Floci doesn't build from source.

Requests to the service URL are forwarded to the container with `X-Forwarded-Host` and `X-Forwarded-Proto`. A service whose container is gone (after Floci restarts) is started again by its first request.

## Logs

The container's output goes to `/aws/apprunner/<name>/<id>/application`, and App Runner's own messages (deployments, health checks, failures) to `/aws/apprunner/<name>/<id>/service`. The log groups stay after the service is deleted, as on AWS.

## Configurations, connectors and domains

Auto scaling and observability configurations are revisioned by name (up to five active revisions), and every account has their `DefaultConfiguration`; one can be made the account default. A configuration a service uses can't be deleted. VPC connectors are revisioned the same way, and a service's VPC egress needs an active one. VPC ingress connections give a service a private domain name. Source connections stay `PENDING_HANDSHAKE`, because the handshake happens in the provider's console.

`AssociateCustomDomain` returns the certificate validation CNAMEs for the domain (and its `www` subdomain when enabled). The domain becomes `ACTIVE`, and is served, once those records exist in Floci's Route 53.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_APP_RUNNER_ENABLED` | `true` | Enable or disable App Runner |

For the upstream API shape, see the [AWS App Runner API Reference](https://docs.aws.amazon.com/apprunner/latest/api/Welcome.html).
