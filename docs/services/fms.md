# AWS Firewall Manager

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSFMS_20180101.<Action>`)
**Endpoint:** `POST http://localhost:4566/`, signed for `fms`.

Floci runs Firewall Manager's administration over AWS Organizations: the default administrator an organization's
management account designates, further administrators with their scopes, and the policies, lists, resource sets,
notification channel and third-party firewalls an administrator manages.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AssociateAdminAccount` | Designate the organization's default administrator |
| `DisassociateAdminAccount` | Offboard the organization's administrators |
| `GetAdminAccount` | Read the default administrator |
| `PutAdminAccount` | Add or rescope an administrator |
| `GetAdminScope` | Read an administrator's scope |
| `ListAdminAccountsForOrganization` | List the organization's administrators |
| `ListAdminsManagingAccount` | List the administrators covering this account |
| `ListMemberAccounts` | List the accounts an administrator covers |
| `PutPolicy` | Create or update a security policy |
| `GetPolicy` | Describe a policy |
| `DeletePolicy` | Delete a policy |
| `ListPolicies` | List policies |
| `ListComplianceStatus` | List a policy's compliance per account |
| `GetComplianceDetail` | Read a policy's compliance in an account |
| `GetViolationDetails` | Read a resource's violations |
| `GetProtectionStatus` | Read Shield Advanced attack data |
| `PutAppsList` | Create or update an applications list |
| `GetAppsList` | Describe an applications list |
| `DeleteAppsList` | Delete an applications list |
| `ListAppsLists` | List applications lists, or the default one |
| `PutProtocolsList` | Create or update a protocols list |
| `GetProtocolsList` | Describe a protocols list |
| `DeleteProtocolsList` | Delete a protocols list |
| `ListProtocolsLists` | List protocols lists, or the default one |
| `PutResourceSet` | Create or update a resource set |
| `GetResourceSet` | Describe a resource set |
| `DeleteResourceSet` | Delete an unused resource set |
| `ListResourceSets` | List resource sets |
| `BatchAssociateResource` | Add resources to a resource set |
| `BatchDisassociateResource` | Remove resources from a resource set |
| `ListResourceSetResources` | List a resource set's resources |
| `ListDiscoveredResources` | List member resources of a type |
| `PutNotificationChannel` | Set the SNS notification channel |
| `GetNotificationChannel` | Read the notification channel |
| `DeleteNotificationChannel` | Remove the notification channel |
| `AssociateThirdPartyFirewall` | Onboard a third-party firewall |
| `DisassociateThirdPartyFirewall` | Offboard a third-party firewall |
| `GetThirdPartyFirewallAssociationStatus` | Read third-party firewall onboarding |
| `ListThirdPartyFirewallFirewallPolicies` | List a third-party firewall's policies |
| `ListTagsForResource` | List a resource's tags |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
<!-- floci:actions:end -->

## Administrators

`AssociateAdminAccount` is the management account's: it names a member of the organization the default
administrator, enables `fms.amazonaws.com` service access and creates the `AWSServiceRoleForFMS` service-linked
role. The organization is `ONBOARDING` for a transition, during which it cannot be offboarded, as AWS refuses it.
`PutAdminAccount` adds administrators with account, organizational unit, Region and policy type scopes once the
default administrator exists; `ListAdminsManagingAccount` and `ListMemberAccounts` follow those scopes. An account
outside an organization, or one with no administrator, gets `ResourceNotFoundException` from `GetAdminAccount`.

## What an administrator manages

Only an administrator manages policies, applications and protocols lists, resource sets, its notification
channel and third-party firewalls; any other account is refused with `AccessDeniedException`. Updates are versioned:
a `PolicyUpdateToken`, `ListUpdateToken` or `UpdateToken` that is not the current one is refused, and a list keeps
its earlier entries by version. A policy's managed service data must be JSON of the policy's type; a policy whose
type or Region its administrator's scope leaves out is `OUT_OF_ADMIN_SCOPE`. The AWS-managed default lists
(`FMS-Default-Public-Access-Apps-Allowed`, `FMS-Default-Protocols-Allowed`) can be read but not changed.

Floci evaluates no resources against policies and creates none in member accounts: compliance reports every
covered account compliant with no violators, and `ListDiscoveredResources` finds nothing. Third-party firewall
onboarding completes at once; floci has no Marketplace subscriptions.

## Configuration

```yaml
floci:
  services:
    fms:
      enabled: true
      transition-millis: 2000   # how long an organization stays ONBOARDING
```
