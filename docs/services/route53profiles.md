# Route 53 Profiles

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/profile` and the rest, signed for `route53profiles`

A profile collects DNS configuration and applies it to every VPC it's associated with.

## Profiles and VPCs

A profile is `CREATING`, then `COMPLETE`. `AssociateProfile` attaches it to a VPC that must exist in EC2, and a VPC
takes one profile. A profile that's still associated with a VPC can't be deleted.

## Resources

`AssociateResourceToProfile` attaches a resource, which is checked in the service that owns it:

- a private hosted zone (`PRIVATE_HOSTED_ZONE`)
- a Resolver rule (`RESOLVER_RULE`)
- a DNS Firewall rule group (`FIREWALL_RULE_GROUP`), with `{"priority": N}` between 100 and 9900 that no other rule
  group on the profile has
- an interface VPC endpoint (`INTERFACE_ENDPOINT`)

A rule group attached to a profile can't be deleted. Resolver rules attached to a profile forward the DNS queries of
the profile's VPCs through the embedded DNS server, like rules associated with a VPC directly. That starts once both
associations are complete and stops when either is removed.

## Configuration

```yaml
floci:
  services:
    route53profiles:
      enabled: true
      transition-millis: 2000   # how long profiles and associations take to create, update and delete
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateProfile` | Create a profile |
| `GetProfile` | Describe a profile |
| `ListProfiles` | List profiles |
| `DeleteProfile` | Delete a profile no VPC uses |
| `AssociateProfile` | Attach a profile to a VPC |
| `DisassociateProfile` | Detach a profile from a VPC |
| `GetProfileAssociation` | Describe a VPC association |
| `ListProfileAssociations` | List VPC associations, by profile or VPC |
| `AssociateResourceToProfile` | Attach a hosted zone, Resolver rule, rule group or endpoint |
| `UpdateProfileResourceAssociation` | Rename an attached resource or change its rule group priority |
| `DisassociateResourceFromProfile` | Detach a resource |
| `GetProfileResourceAssociation` | Describe an attached resource |
| `ListProfileResourceAssociations` | List a profile's resources, by type |
| `TagResource` | Tag a profile |
| `UntagResource` | Remove tags from a profile |
| `ListTagsForResource` | List a profile's tags |
<!-- floci:actions:end -->
