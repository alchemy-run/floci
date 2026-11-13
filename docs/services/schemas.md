# EventBridge Schemas

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/v1/registries` and the rest, signed for `schemas`

The schema registry holds the shapes of events, as OpenAPI 3 or JSON Schema Draft 4.

## Registries and schemas

A registry holds schemas, and a registry with schemas can't be deleted. A schema's content must be a JSON object,
and an OpenAPI 3 schema must declare `openapi: 3.x`. Changing the content makes a new version; the same content
keeps the version. A registry's resource policy is revisioned: a put that names an old `RevisionId` fails with
`PreconditionFailedException`. `SearchSchemas` matches keywords against schema names and content.

The AWS-managed `aws.events` registry is always there. It holds schemas for `aws.events@ScheduledEvent`,
`aws.s3@ObjectCreated` and `aws.ec2@EC2InstanceStatechangeNotification`. Only its schemas and discovered schemas
can be exported, to JSON Schema Draft 4.

## Discovery

`GetDiscoveredSchema` infers a schema from sample events. The envelope becomes an `AWSEvent` object, `detail`
refers to a component named after the detail type, and each nested object gets its own component. A property is
required only when every sample has it.

A started discoverer on an event bus does the same for every event the bus accepts. It writes to the
`discovered-schemas` registry, named `source@DetailType`, and a sample that changes the schema makes a new version.
A discovered schema, a new schema or a new version publishes `Schema Created` or `Schema Version Created` from
`aws.schemas` to the default bus. A bus has one discoverer.

## Code bindings

`PutCodeBinding` generates a schema's types in `Java8`, `Python36`, `TypeScript3` or `Go1`: POJOs, typed classes,
interfaces or structs. `GetCodeBindingSource` returns them zipped.

## Configuration

```yaml
floci:
  services:
    schemas:
      enabled: true
      code-binding-millis: 2000   # how long a code binding takes to generate
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateRegistry` | Create a registry |
| `DescribeRegistry` | Describe a registry |
| `UpdateRegistry` | Change a registry's description |
| `DeleteRegistry` | Delete an empty registry |
| `ListRegistries` | List registries, local or AWS-managed |
| `PutResourcePolicy` | Set a registry's policy, checking its revision |
| `GetResourcePolicy` | Read a registry's policy |
| `DeleteResourcePolicy` | Remove a registry's policy |
| `CreateSchema` | Create a schema at version 1 |
| `UpdateSchema` | Change a schema; new content makes a new version |
| `DescribeSchema` | Describe a schema version and its content |
| `DeleteSchema` | Delete a schema |
| `DeleteSchemaVersion` | Delete one of several versions |
| `ListSchemas` | List a registry's schemas |
| `ListSchemaVersions` | List a schema's versions |
| `SearchSchemas` | Find schemas by keywords in names and content |
| `ExportSchema` | Export a discovered or AWS-managed schema to JSON Schema Draft 4 |
| `GetDiscoveredSchema` | Infer a schema from sample events |
| `PutCodeBinding` | Generate code for a schema version |
| `DescribeCodeBinding` | Read a code binding's status |
| `GetCodeBindingSource` | Download the generated code, zipped |
| `CreateDiscoverer` | Start discovering schemas from an event bus |
| `DescribeDiscoverer` | Describe a discoverer |
| `UpdateDiscoverer` | Change a discoverer |
| `DeleteDiscoverer` | Delete a discoverer |
| `ListDiscoverers` | List discoverers |
| `StartDiscoverer` | Resume discovery |
| `StopDiscoverer` | Pause discovery |
| `TagResource` | Tag a registry, schema or discoverer |
| `UntagResource` | Remove tags |
| `ListTagsForResource` | List a resource's tags |
<!-- floci:actions:end -->
