# AppSync

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/v1/apis/...`

Floci implements the AWS AppSync Management API, providing local emulation of GraphQL API configuration, schema management, data source binding, resolver mapping, API key provisioning, custom domains, and channel namespaces.

## Supported Operations

### GraphQL API

| Operation | Description |
|---|---|
| `CreateGraphqlApi` | Create a GraphQL API |
| `GetGraphqlApi` | Get a GraphQL API by ID |
| `UpdateGraphqlApi` | Update a GraphQL API |
| `DeleteGraphqlApi` | Delete a GraphQL API and all child resources |
| `ListGraphqlApis` | List all GraphQL APIs |

### Schema

| Operation | Description |
|---|---|
| `StartSchemaCreation` | Start schema creation — validates and parses SDL using graphql-java (invalid SDL returns 400) |
| `GetSchemaCreationStatus` | Get schema creation status |
| `GetIntrospectionSchema` | Get the introspection schema (HTTP payload is the raw SDL/JSON blob) |

### Data Sources

| Operation | Description |
|---|---|
| `CreateDataSource` | Create a data source |
| `GetDataSource` | Get a data source by name |
| `UpdateDataSource` | Update a data source |
| `DeleteDataSource` | Delete a data source |
| `ListDataSources` | List all data sources for an API |

### Resolvers

| Operation | Description |
|---|---|
| `CreateResolver` | Create a resolver |
| `GetResolver` | Get a resolver by type and field |
| `UpdateResolver` | Update a resolver |
| `DeleteResolver` | Delete a resolver |
| `ListResolvers` | List all resolvers for an API |
| `ListResolversByType` | List resolvers for a specific type |
| `ListResolversByFunction` | List resolvers attached to a specific function |

### Functions

| Operation | Description |
|---|---|
| `CreateFunction` | Create a function configuration |
| `GetFunction` | Get a function by ID |
| `UpdateFunction` | Update a function |
| `DeleteFunction` | Delete a function |
| `ListFunctions` | List all functions for an API |

### Types

| Operation | Description |
|---|---|
| `CreateType` | Create a type |
| `GetType` | Get a type by name |
| `UpdateType` | Update a type |
| `DeleteType` | Delete a type |
| `ListTypes` | List all types for an API |

### API Keys

| Operation | Description |
|---|---|
| `CreateApiKey` | Create an API key (`id` is the `da2-` secret used as `x-api-key`) |
| `GetApiKey` | Get an API key by ID |
| `UpdateApiKey` | Update an API key |
| `DeleteApiKey` | Delete an API key |
| `ListApiKeys` | List all API keys for an API |

### Tags

| Operation | Description |
|---|---|
| `TagResource` | Add tags to a resource |
| `UntagResource` | Remove tags from a resource |
| `ListTagsForResource` | List tags on a resource |

### Environment Variables

| Operation | Description |
|---|---|
| `GetEnvironmentVariables` | Get environment variables for an API |
| `PutEnvironmentVariables` | Set environment variables for an API |

### Domain Names

| Operation | Description |
|---|---|
| `CreateDomainName` | Register a custom domain name (rejects a UUID ACM ARN that does not exist) |
| `GetDomainName` | Get domain name configuration |
| `UpdateDomainName` | Update domain name description |
| `ListDomainNames` | List all domain names |
| `DeleteDomainName` | Delete a custom domain name |
| `AssociateApi` | Associate a domain name with a GraphQL API |
| `GetAssociatedApi` | Get the API associated with a domain name |
| `DisassociateApi` | Disassociate a domain name from a GraphQL API |
| `ListApiAssociations` | List all associations for an API |

### Channel Namespaces

| Operation | Description |
|---|---|
| `CreateChannelNamespace` | Create a channel namespace |
| `GetChannelNamespace` | Get a channel namespace by name |
| `UpdateChannelNamespace` | Update a channel namespace description |
| `ListChannelNamespaces` | List all channel namespaces for an API |
| `DeleteChannelNamespace` | Delete a channel namespace |

### Merged API Associations

| Operation | Description |
|---|---|
| `CreateApiAssociation` | Associate a source API with a merged API |
| `GetApiAssociation` | Get a merged API association |
| `DeleteApiAssociation` | Delete a merged API association |
| `ListApiAssociations` | List all merged API associations |

### API Cache

| Operation | Description |
|---|---|
| `CreateApiCache` | Create the API cache (AVAILABLE immediately in the emulator) |
| `GetApiCache` | Get the API cache; `NotFoundException` when none exists |
| `UpdateApiCache` | Update TTL, behavior, type, and health metrics |
| `DeleteApiCache` | Delete the API cache |
| `FlushApiCache` | Flush the API cache; `NotFoundException` when none exists |

### Evaluate

| Operation | Description |
|---|---|
| `EvaluateCode` | Evaluate `APPSYNC_JS` `request` / `response` functions |
| `EvaluateMappingTemplate` | Evaluate a VTL mapping template |

### GraphQL data plane

| Operation | Description |
|---|---|
| `GraphQL` | `POST /v1/apis/{apiId}/graphql` and `{apiId}.appsync-api.{region}.amazonaws.com/graphql` |

### Enhanced Metrics

| Operation | Description |
|---|---|
| `GetEnhancedMetricsConfig` | Get the enhanced metrics configuration |

## Schema Registry

`StartSchemaCreation` validates the provided GraphQL SDL using [graphql-java](https://github.com/graphql-java/graphql-java). Invalid schemas are rejected with a `BadRequestException` (400) at registration time, preventing them from being caught later during query execution.

The following **AWS scalar types** are pre-registered and available in any schema without requiring explicit `scalar` declarations:

| Scalar | Java Type | Validation |
|--------|-----------|------------|
| `AWSJSON` | String | Valid JSON syntax |
| `AWSDateTime` | String | ISO 8601 datetime |
| `AWSDate` | String | ISO 8601 date (yyyy-MM-dd) |
| `AWSTime` | String | ISO 8601 time |
| `AWSTimestamp` | Long | Unix epoch seconds (0 to 32503680000) |
| `AWSEmail` | String | RFC 5322 email format |
| `AWSURL` | String | Valid URL |
| `AWSPhone` | String | E.164 format (+1234567890) |
| `AWSIPAddress` | String | IPv4 or IPv6 |
| `AWSBoolean` | Boolean | Boolean value |
| `AWSLong` | Long | 64-bit signed integer |
| `AWSInteger` | Integer | 32-bit signed integer |
| `AWSShort` | Integer | 16-bit signed integer (-32768 to 32767) |
| `AWSFloat` | Double | IEEE 754 double-precision |
| `AWSBigDecimal` | String | Arbitrary-precision decimal |
| `AWSBigInt` | String | Arbitrary-precision integer |
| `AWSByte` | String | Base64-encoded byte array |

The following **AppSync directives** are pre-defined and recognized in schemas:

| Directive | Locations | Purpose |
|-----------|-----------|---------|
| `@aws_api_key` | OBJECT, FIELD_DEFINITION | Require API key auth |
| `@aws_iam` | OBJECT, FIELD_DEFINITION | Require IAM auth |
| `@aws_cognito_user_pools(cognito_groups: [String!]!)` | OBJECT, FIELD_DEFINITION | Require Cognito user pool auth |
| `@aws_oidc` | OBJECT, FIELD_DEFINITION | Require OIDC auth |
| `@aws_lambda` | OBJECT, FIELD_DEFINITION | Require Lambda auth |
| `@aws_subscribe(mutations: [String!]!)` | FIELD_DEFINITION | Link subscription to mutation |
| `@aws_auth(cognito_groups: [String!]!)` | OBJECT | Require Cognito groups |
| `@aws_delta_sync` | OBJECT | Delta sync configuration |

Unknown directives are rejected during schema registration.

Schema extensions (`extend type Query { ... }`) are supported natively through graphql-java.

## Pagination

All `List` operations support cursor-based pagination via query parameters:

| Parameter | Description |
|---|---|
| `maxResults` | Maximum number of items to return |
| `nextToken` | Opaque token for the next page |

The `nextToken` is a Base64 URL-encoded integer offset. A missing token starts from offset 0. An invalid token returns `InvalidNextTokenException` (400).

```bash
# First page
aws appsync list-graphql-apis \
  --max-results 10 \
  --endpoint-url $AWS_ENDPOINT_URL

# Next page (use the nextToken from previous response)
aws appsync list-graphql-apis \
  --max-results 10 \
  --next-token "eyJvZmZzZXQiOjEwfQ==" \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Cascade Delete

Deleting a GraphQL API (`DeleteGraphqlApi`) automatically deletes all child resources:

- Schema and schema creation status
- All data sources
- All resolvers
- All functions
- All types
- All API keys
- All channel namespaces
- All domain name associations

This matches AWS behavior where deleting an API removes its entire configuration.

`CreateGraphqlApi` advertises AWS-shaped URIs:

```
https://{apiId}.appsync-api.{region}.amazonaws.com/graphql
```

The gateway also accepts the path-style data-plane URL `POST /v1/apis/{apiId}/graphql`. Host headers matching `{apiId}.appsync-api.{region}.*` are rewritten onto that path. API_KEY APIs require `x-api-key`; AWS_IAM APIs accept a SigV4 `Authorization` header (and an additional API_KEY provider if configured).

Resolvers run `APPSYNC_JS` (`request`/`response`) or VTL templates. `NONE` data sources use the request `payload` as `ctx.result`. `AWS_LAMBDA` data sources invoke the configured function with that payload. Pipeline resolvers chain AppSync Functions and expose `ctx.prev.result`.

## Not Implemented

These AWS AppSync operations are not yet implemented and are tracked in future phases:

- **Data source adapters** (beyond NONE + Lambda): DynamoDB, HTTP, EventBridge, OpenSearch, RDS connectors
- **Subscriptions**: WebSocket real-time subscriptions
- **Lambda authorizer data-plane**: AWS_LAMBDA primary auth is stored but not invoked on GraphQL requests
- **Merged API source management**: `StartSchemaMerge`, `ListTypesByAssociation`
- **Data source introspection**: `StartDataSourceIntrospection`, `GetDataSourceIntrospection`

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_APPSYNC_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a GraphQL API
aws appsync create-graphql-api \
  --name my-api \
  --authentication-type API_KEY \
  --endpoint-url $AWS_ENDPOINT_URL

# Start schema creation
aws appsync start-schema-creation \
  --api-id API_ID \
  --definition 'type Query { hello: String }' \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a data source (NONE type for local resolvers)
aws appsync create-data-source \
  --api-id API_ID \
  --name my-datasource \
  --type NONE \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a resolver
aws appsync create-resolver \
  --api-id API_ID \
  --type-name Query \
  --field-name hello \
  --data-source-name my-datasource \
  --endpoint-url $AWS_ENDPOINT_URL

# Create an API key
aws appsync create-api-key \
  --api-id API_ID \
  --description "Test key" \
  --endpoint-url $AWS_ENDPOINT_URL

# List all APIs
aws appsync list-graphql-apis \
  --endpoint-url $AWS_ENDPOINT_URL

# Register a custom domain
aws appsync create-domain-name \
  --domain-name api.example.com \
  --certificate-arn arn:aws:acm:us-east-1:000000000000:certificate/123 \
  --endpoint-url $AWS_ENDPOINT_URL

# Associate domain with API
aws appsync associate-api \
  --domain-name api.example.com \
  --api-id API_ID \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a channel namespace
aws appsync create-channel-namespace \
  --api-id API_ID \
  --name my-channels \
  --endpoint-url $AWS_ENDPOINT_URL
```
