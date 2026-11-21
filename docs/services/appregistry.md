# AWS Service Catalog AppRegistry

**Protocol:** REST JSON, signed with `servicecatalog`
**Endpoint:** `http://localhost:4566/applications`, `/attribute-groups`, `/configuration`, `/sync` and the shared `/tags/{resourceArn}` routes

AppRegistry keeps a catalog of applications: their metadata in attribute groups, and the CloudFormation stacks and tagged resources that make them up. AWS has put AppRegistry in maintenance mode, so new accounts can't use it; Floci serves it as it works for accounts that kept access.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateApplication` | Create an application with its tag and Resource Groups |
| `ListApplications` | Page through applications |
| `GetApplication` | Return an application with its integrations |
| `UpdateApplication` | Change an application's name or description |
| `DeleteApplication` | Delete an application with no associations |
| `ListAssociatedAttributeGroups` | List an application's attribute group ids |
| `ListAttributeGroupsForApplication` | List an application's attribute groups |
| `AssociateAttributeGroup` | Associate an attribute group |
| `DisassociateAttributeGroup` | Disassociate an attribute group |
| `ListAssociatedResources` | List an application's stacks and tag values |
| `AssociateResource` | Associate a stack (tagging it) or a tag value |
| `DisassociateResource` | Disassociate a resource, removing the tag it applied |
| `GetAssociatedResource` | Return an association and its tag result |
| `CreateAttributeGroup` | Create an attribute group |
| `ListAttributeGroups` | Page through attribute groups |
| `GetAttributeGroup` | Return an attribute group with its attributes |
| `UpdateAttributeGroup` | Change an attribute group |
| `DeleteAttributeGroup` | Delete an attribute group no application uses |
| `GetConfiguration` | Return the tag key, awsApplication |
| `PutConfiguration` | Set the tag query configuration |
| `SyncResource` | Associate a stack tagged for an application |
| `ListTagsForResource` | List tags |
| `TagResource` | Tag an application or attribute group |
| `UntagResource` | Remove tags |
<!-- floci:actions:end -->

## Applications and attribute groups

Applications and attribute groups are addressed by id, name or ARN (`arn:<partition>:servicecatalog:<region>:<account>:/applications/<id>`). A second application or attribute group of the same name is a `ConflictException`; `clientToken` makes a create idempotent. An attribute group's `attributes` must be a JSON object.

An application gets the tag its resources carry, `awsApplication` with the application's ARN, and two Resource Groups that Floci's Resource Groups serves: `AWS_AppRegistry_Application-<name>` and `AWS_AppRegistry_AppTag_<account>-<name>`, each a tag query on that tag. An application with associated attribute groups or resources can't be deleted, and deleting it deletes its groups.

## Associating resources

`AssociateResource` takes a CloudFormation stack (`CFN_STACK`, by name or ARN; it must exist) or a tag value (`RESOURCE_TAG_VALUE`). A stack is tagged `awsApplication` unless the options include `SKIP_APPLICATION_TAG`, and a stack already tagged for another application is a `ConflictException`. Disassociating a stack removes the tag it applied. `SyncResource` associates a stack whose `awsApplication` tag names an application and answers `START_SYNC`, or `NO_ACTION` when there's nothing to do. `GetConfiguration` reports the tag key, `awsApplication`.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_APP_REGISTRY_ENABLED` | `true` | Enable or disable AppRegistry |

For the upstream API shape, see the [AppRegistry API Reference](https://docs.aws.amazon.com/servicecatalog/latest/dg/API_Operations_AWS_Service_Catalog_App_Registry.html).
