# Amazon Q Business

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/applications/...`, signed for `qbusiness`

Floci indexes your documents and answers chat from them. Each answer cites its sources. Access control lists, groups
and chat controls apply as the service applies them.

## Applications, indices and retrievers

An application uses IAM Identity Center (`AWS_IAM_IDC`, with `identityCenterInstanceArn`), an IAM identity provider,
QuickSight, or `ANONYMOUS`. It's `CREATING`, then `ACTIVE`. It has one index (`STARTER` with one capacity unit,
or `ENTERPRISE`) and one retriever. A `NATIVE_INDEX` retriever names the index; delete it before its index.
`GetIndex` counts the indexed documents and their text bytes.

## Documents and data sources

`BatchPutDocument` takes up to 10 documents, as a base64 blob or an S3 object. Floci reads the text of plain text,
Markdown, CSV, JSON, XML, HTML, RTF, PDF, Word, Excel and PowerPoint, and splits it into passages. A document that
can't be read is listed in `failedDocuments`. Documents are `RECEIVED`, then `INDEXED` (`UPDATED` when replaced).
`GetDocumentContent` returns a presigned link to the raw or extracted content.

A `CUSTOM` data source's sync job counts the documents put and deleted with its `dataSourceSyncId`. It ends when
stopped. An `S3` data source's sync job crawls the bucket under its `inclusionPrefixes`, skipping `exclusionPatterns`.
It adds and updates the objects as documents, deletes the ones that are gone, and counts them. Other connectors reach
systems Floci doesn't emulate, so their syncs fail with `ConnectorUnavailable`.

## Access

A document's `accessConfiguration` allows or denies users and groups. A deny wins. Chat and search show a user only the
documents they may read; anonymous chat sees only documents without access controls. Users have aliases per index, and
groups (`PutGroup`, with members inline or in an S3 file) may contain groups. `CheckDocumentAccess` explains a
decision.

## Chat

`ChatSync`, and `Chat` over event streams, answer in retrieval mode from the passages that rank best for the question
(BM25), as the sentences that answer it with `[n]` citations and `sourceAttributions`. With `generate-answers`, Floci's
Bedrock runtime writes the answer from those passages. Without a match the answer is "Sorry, I could not find relevant
information to complete your request.", unless the response scope is `EXTENDED_KNOWLEDGE_ENABLED`. Then the model
answers, as it does in creator mode. Attachments (when the application allows them) answer within their conversation.

Chat controls apply in order: a blocked phrase in the message returns the override message; a topic the message is about
(by its name or example messages) blocks it or limits retrieval to its eligible data sources for the users it applies
to. In plugin mode, a custom plugin's best-matching OpenAPI operation is offered for review. Running the reviewed
action calls the plugin's server with the payload. Built-in plugins reach services Floci doesn't emulate.

Conversations and their messages, attachments and feedback are kept per user. An `AWS_IAM_IDC` application without
auto-subscription answers a user without a subscription with `LicenseNotFoundException`.

## Web experiences

A web experience's `defaultEndpoint` serves a chat page. `CreateAnonymousWebExperienceUrl` (for `ANONYMOUS`
applications) returns a signed URL to it, valid for 15 to 60 minutes.

## Everything else

Plugins (`CUSTOM` with an OpenAPI 3 schema in JSON or YAML, or a built-in type) are `CREATE_IN_PROGRESS`, then `READY`.
Data accessors, chat response configurations, subscriptions (for Identity Center users and groups) and the
application's resource policy (`AssociatePermission`) are kept as the service keeps them.

## Configuration

```yaml
floci:
  services:
    qbusiness:
      enabled: true
      transition-millis: 2000          # how long resources and documents take to become ready
      presigned-url-seconds: 300
      model: amazon.nova-lite-v1:0     # creator mode, extended knowledge, generated answers
      generate-answers: false          # true: retrieval answers are written by the model
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ChatSync` | Answer a chat turn from the index |
| `Chat` | Chat over event streams |
| `SearchRelevantContent` | Rank passages for a query |
| `PutFeedback` | Rate a message |
| `ListConversations` | List conversations |
| `DeleteConversation` | Delete a conversation |
| `ListMessages` | List messages |
| `ListAttachments` | List attachments |
| `DeleteAttachment` | Delete an attachment |
| `GetMedia` | Read a cited media object |
| `AssociatePermission` | Add a resource policy statement |
| `BatchDeleteDocument` | Remove documents from the index |
| `BatchPutDocument` | Index documents |
| `CancelSubscription` | Cancel a subscription |
| `CheckDocumentAccess` | Explain a user's access to a document |
| `CreateAnonymousWebExperienceUrl` | Sign an anonymous web experience URL |
| `CreateApplication` | Create an application |
| `CreateChatResponseConfiguration` | Create a chat response configuration |
| `CreateDataAccessor` | Create a data accessor |
| `CreateDataSource` | Create a data source |
| `CreateIndex` | Create an index |
| `CreatePlugin` | Create a plugin |
| `CreateRetriever` | Create a retriever |
| `CreateSubscription` | Create a subscription |
| `CreateUser` | Create an user |
| `CreateWebExperience` | Create a web experience |
| `DeleteApplication` | Delete an application |
| `DeleteChatControlsConfiguration` | Delete a chat controls configuration |
| `DeleteChatResponseConfiguration` | Delete a chat response configuration |
| `DeleteDataAccessor` | Delete a data accessor |
| `DeleteDataSource` | Delete a data source |
| `DeleteGroup` | Delete a group |
| `DeleteIndex` | Delete an index |
| `DeletePlugin` | Delete a plugin |
| `DeleteRetriever` | Delete a retriever |
| `DeleteUser` | Delete an user |
| `DeleteWebExperience` | Delete a web experience |
| `DisassociatePermission` | Remove a resource policy statement |
| `GetApplication` | Get an application |
| `GetChatControlsConfiguration` | Get a chat controls configuration |
| `GetChatResponseConfiguration` | Get a chat response configuration |
| `GetDataAccessor` | Get a data accessor |
| `GetDataSource` | Get a data source |
| `GetDocumentContent` | Link to a document's content |
| `GetGroup` | Get a group |
| `GetIndex` | Get an index |
| `GetPlugin` | Get a plugin |
| `GetPolicy` | Read the resource policy |
| `GetRetriever` | Get a retriever |
| `GetUser` | Get an user |
| `GetWebExperience` | Get a web experience |
| `ListApplications` | List applications |
| `ListChatResponseConfigurations` | List chat response configurations |
| `ListDataAccessors` | List data accessors |
| `ListDataSourceSyncJobs` | List data source sync jobs |
| `ListDataSources` | List data sources |
| `ListDocuments` | List documents |
| `ListGroups` | List groups |
| `ListIndices` | List indices |
| `ListPluginActions` | List plugin actions |
| `ListPluginTypeActions` | List a plugin type's actions |
| `ListPluginTypeMetadata` | List built-in plugin types |
| `ListPlugins` | List plugins |
| `ListRetrievers` | List retrievers |
| `ListSubscriptions` | List subscriptions |
| `ListTagsForResource` | List a resource's tags |
| `ListWebExperiences` | List web experiences |
| `PutGroup` | Put a group |
| `StartDataSourceSyncJob` | Start a sync |
| `StopDataSourceSyncJob` | Stop a sync |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
| `UpdateApplication` | Update an application |
| `UpdateChatControlsConfiguration` | Update a chat controls configuration |
| `UpdateChatResponseConfiguration` | Update a chat response configuration |
| `UpdateDataAccessor` | Update a data accessor |
| `UpdateDataSource` | Update a data source |
| `UpdateIndex` | Update an index |
| `UpdatePlugin` | Update a plugin |
| `UpdateRetriever` | Update a retriever |
| `UpdateSubscription` | Update a subscription |
| `UpdateUser` | Update an user |
| `UpdateWebExperience` | Update a web experience |
<!-- floci:actions:end -->
