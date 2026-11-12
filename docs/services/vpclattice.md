# VPC Lattice

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/servicenetworks/...`, `/services/...`, `/targetgroups/...`, signed for `vpc-lattice`

VPC Lattice connects services across VPCs and accounts through service networks.

## Service networks and associations

A service network groups services and resource configurations, and VPCs join it through VPC associations. A VPC
joins one service network at a time, and its security groups must be in that VPC. A service network can't be deleted
while it has VPC, service or resource associations, and a service can't be deleted while it is associated with a
network.

Services, target groups, resource gateways and configurations, and associations are created and deleted
asynchronously. They report `CREATE_IN_PROGRESS` or `DELETE_IN_PROGRESS` for a moment, and changes to them conflict
until they settle. Each service gets a DNS name under `vpc-lattice-svcs.<region>.on.aws`.

A create retried with the same `clientToken` returns what the first one made. Reusing the token with different
parameters conflicts.

## Target groups and targets

Target groups hold `IP`, `INSTANCE`, `LAMBDA` or `ALB` targets. An IP target must be in a subnet of the group's VPC,
an instance must be in that VPC, a Lambda function and a load balancer must exist, and a `LAMBDA` group holds one
function. Rejected targets come back in `unsuccessful`.

A target's status follows the group:

- `UNUSED` while no listener or rule forwards to the group
- `UNAVAILABLE` when health checks are disabled (and always for `LAMBDA` and `ALB` groups)
- otherwise `INITIAL`, then `HEALTHY` or `UNHEALTHY` from real health checks at the group's interval and thresholds
  (HTTP, HTTPS or TCP, at the target's address or the instance's container)

Deregistered targets drain for a moment before they disappear. A target group can't be deleted while a listener or
rule uses it or while it has targets.

## Listeners and rules

A service has one listener per port and per name. Listener and rule actions either forward to weighted target groups
or return a fixed status, and `TLS_PASSTHROUGH` listeners forward only to `TCP` groups. Rules match a method, path
and headers, with priorities unique per listener. `ListRules` includes the listener's default rule.

## Policies, access logs and domains

Auth policies attach to service networks, services and resource configurations. Their state is `Active` while the
resource's auth type is `AWS_IAM`. Resource policies attach by ARN.

Access log subscriptions send to an existing S3 bucket, CloudWatch Logs group or Firehose stream, at most one per
destination type and resource.

A domain verification is `PENDING` until its TXT record is published in a Route 53 hosted zone, then `VERIFIED`.

## Resource gateways and configurations

A resource gateway lives in a VPC's subnets. Resource configurations describe a DNS name, IP address, ARN or CIDR
behind a gateway (`SINGLE`, `ARN`), or group child configurations (`GROUP`, `CHILD`). A gateway with configurations,
or a configuration with associations or children, can't be deleted.

## Configuration

```yaml
floci:
  services:
    vpc-lattice:
      enabled: true
      transition-millis: 1000   # how long asynchronous creates and deletes take
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateServiceNetwork` | Create a service network |
| `GetServiceNetwork` | Describe a service network |
| `UpdateServiceNetwork` | Change a service network |
| `DeleteServiceNetwork` | Delete a service network |
| `ListServiceNetworks` | List service networks |
| `CreateService` | Create a service |
| `GetService` | Describe a service |
| `UpdateService` | Change a service |
| `DeleteService` | Delete a service |
| `ListServices` | List services |
| `CreateServiceNetworkVpcAssociation` | Create a VPC association |
| `GetServiceNetworkVpcAssociation` | Describe a VPC association |
| `UpdateServiceNetworkVpcAssociation` | Change a VPC association |
| `DeleteServiceNetworkVpcAssociation` | Delete a VPC association |
| `ListServiceNetworkVpcAssociations` | List VPC associations |
| `CreateServiceNetworkServiceAssociation` | Create a service association |
| `GetServiceNetworkServiceAssociation` | Describe a service association |
| `DeleteServiceNetworkServiceAssociation` | Delete a service association |
| `ListServiceNetworkServiceAssociations` | List service associations |
| `CreateServiceNetworkResourceAssociation` | Create a resource association |
| `GetServiceNetworkResourceAssociation` | Describe a resource association |
| `DeleteServiceNetworkResourceAssociation` | Delete a resource association |
| `ListServiceNetworkResourceAssociations` | List resource associations |
| `ListServiceNetworkVpcEndpointAssociations` | List VPC endpoint associations of a service network |
| `CreateTargetGroup` | Create a target group |
| `GetTargetGroup` | Describe a target group |
| `UpdateTargetGroup` | Change a target group |
| `DeleteTargetGroup` | Delete a target group |
| `ListTargetGroups` | List target groups |
| `RegisterTargets` | Register targets with a target group |
| `DeregisterTargets` | Deregister targets (they drain first) |
| `ListTargets` | List a target group's targets and their status |
| `CreateListener` | Create a listener |
| `GetListener` | Describe a listener |
| `UpdateListener` | Change a listener |
| `DeleteListener` | Delete a listener |
| `ListListeners` | List listeners |
| `CreateRule` | Create a rule |
| `GetRule` | Describe a rule |
| `UpdateRule` | Change a rule |
| `BatchUpdateRule` | Update up to ten rules of a listener |
| `DeleteRule` | Delete a rule |
| `ListRules` | List a listener's rules, including its default rule |
| `PutAuthPolicy` | Attach an auth policy |
| `GetAuthPolicy` | Describe an auth policy |
| `DeleteAuthPolicy` | Delete an auth policy |
| `PutResourcePolicy` | Attach a resource policy |
| `GetResourcePolicy` | Describe a resource policy |
| `DeleteResourcePolicy` | Delete a resource policy |
| `CreateAccessLogSubscription` | Create an access log subscription |
| `GetAccessLogSubscription` | Describe an access log subscription |
| `UpdateAccessLogSubscription` | Change an access log subscription |
| `DeleteAccessLogSubscription` | Delete an access log subscription |
| `ListAccessLogSubscriptions` | List access log subscriptions |
| `CreateResourceGateway` | Create a resource gateway |
| `GetResourceGateway` | Describe a resource gateway |
| `UpdateResourceGateway` | Change a resource gateway |
| `DeleteResourceGateway` | Delete a resource gateway |
| `ListResourceGateways` | List resource gateways |
| `CreateResourceConfiguration` | Create a resource configuration |
| `GetResourceConfiguration` | Describe a resource configuration |
| `UpdateResourceConfiguration` | Change a resource configuration |
| `DeleteResourceConfiguration` | Delete a resource configuration |
| `ListResourceConfigurations` | List resource configurations |
| `ListResourceEndpointAssociations` | List resource endpoint associations |
| `DeleteResourceEndpointAssociation` | Delete a resource endpoint association |
| `StartDomainVerification` | Start verifying a domain through a TXT record |
| `GetDomainVerification` | Describe a domain verification |
| `DeleteDomainVerification` | Delete a domain verification |
| `ListDomainVerifications` | List domain verifications |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags |
| `ListTagsForResource` | List a resource's tags |
<!-- floci:actions:end -->
