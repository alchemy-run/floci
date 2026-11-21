# Agents for Amazon Bedrock

**Protocol:** REST JSON
**Signing name:** `bedrock` (control plane `bedrock-agent`, runtime `bedrock-agent-runtime`)

Floci emulates Agents for Amazon Bedrock: agents with their versions, aliases, action groups,
and knowledge base associations; knowledge bases with their data sources, ingestion jobs, and
documents; and the agent runtime (InvokeAgent, agent memory, Rerank, Retrieve, and
RetrieveAndGenerate). Answers come from [Bedrock Runtime](bedrock-runtime.md)'s configured
backend, so they are the stub's deterministic turns by default, or a proxied model's.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateAgent` | Create an agent |
| `ListAgents` | List agents |
| `GetAgent` | Get an agent |
| `UpdateAgent` | Update an agent |
| `PrepareAgent` | Prepare the draft for invocation |
| `DeleteAgent` | Delete an agent |
| `ListAgentVersions` | List agent versions |
| `GetAgentVersion` | Get an agent version |
| `DeleteAgentVersion` | Delete an agent version |
| `CreateAgentAlias` | Create an agent alias |
| `ListAgentAliases` | List agent aliases |
| `GetAgentAlias` | Get an agent alias |
| `UpdateAgentAlias` | Update an agent alias |
| `DeleteAgentAlias` | Delete an agent alias |
| `CreateAgentActionGroup` | Create an action group |
| `ListAgentActionGroups` | List action groups |
| `GetAgentActionGroup` | Get an action group |
| `UpdateAgentActionGroup` | Update an action group |
| `DeleteAgentActionGroup` | Delete an action group |
| `AssociateAgentKnowledgeBase` | Attach a knowledge base to an agent |
| `ListAgentKnowledgeBases` | List knowledge base associations |
| `GetAgentKnowledgeBase` | Get a knowledge base association |
| `UpdateAgentKnowledgeBase` | Update a knowledge base association |
| `DisassociateAgentKnowledgeBase` | Detach a knowledge base |
| `CreateKnowledgeBase` | Create a knowledge base |
| `ListKnowledgeBases` | List knowledge bases |
| `GetKnowledgeBase` | Get a knowledge base |
| `UpdateKnowledgeBase` | Update a knowledge base |
| `DeleteKnowledgeBase` | Delete a knowledge base |
| `CreateDataSource` | Create a data source |
| `ListDataSources` | List data sources |
| `GetDataSource` | Get a data source |
| `UpdateDataSource` | Update a data source |
| `DeleteDataSource` | Delete a data source |
| `StartIngestionJob` | Index the data source's S3 objects |
| `ListIngestionJobs` | List ingestion jobs |
| `GetIngestionJob` | Get an ingestion job |
| `StopIngestionJob` | Stop an ingestion job |
| `IngestKnowledgeBaseDocuments` | Index inline or S3 documents |
| `ListKnowledgeBaseDocuments` | List indexed documents |
| `GetKnowledgeBaseDocuments` | Get documents' indexing status |
| `DeleteKnowledgeBaseDocuments` | Remove documents from the index |
| `InvokeAgent` | Invoke an agent alias; streams `chunk` events |
| `GetAgentMemory` | Read an agent's session summaries for a memory id |
| `DeleteAgentMemory` | Delete an agent's memory |
| `Rerank` | Rerank documents by relevance to a query |
| `Retrieve` | Search a knowledge base |
| `RetrieveAndGenerate` | Answer a question from retrieved passages, with citations |
| `RetrieveAndGenerateStream` | RetrieveAndGenerate as an event stream |
<!-- floci:actions:end -->

## Emulation Behavior

- **Agents settle at once.** A new or updated agent is `NOT_PREPARED`; `PrepareAgent` answers
  `PREPARING` and leaves it `PREPARED`. Preparing needs a `foundationModel` and an
  `agentResourceRoleArn`.
- **Versions and aliases.** An alias created without a `routingConfiguration` snapshots the
  draft (agent, action groups, knowledge bases) as the next numbered version and points at it;
  the agent must be prepared first. An alias is `PREPARED` (`CREATING` in the create response).
  `TSTALIASID` invokes the draft. Deleting an agent that has aliases, or a version an alias
  routes to, is a `ConflictException` unless `skipResourceInUseCheck` is set.
- **Action groups and knowledge base associations** attach to the `DRAFT` version and mark the
  agent `NOT_PREPARED`, as Bedrock does. No action group executor is called.
- **Knowledge bases need no vector store.** The `storageConfiguration` and embedding model are
  recorded, not used: a knowledge base is `ACTIVE` and a data source `AVAILABLE` at once.
- **Ingestion reads S3.** `StartIngestionJob` reads the S3 data source's bucket (its
  `inclusionPrefixes`, when set), splits each text object into chunks of about 300 words, drops
  documents that are no longer in the bucket, and is `COMPLETE` at once with its statistics.
  `IngestKnowledgeBaseDocuments` indexes inline text or an S3 object the same way.
- **Retrieval is lexical.** `Retrieve`, `RetrieveAndGenerate`, and an agent's knowledge bases
  score chunks by the cosine similarity of their content-word counts with the query's, so a
  chunk ranks higher the more of the query's words it shares. `Rerank` scores its documents the
  same way and accepts only reranking models (a model ARN naming a `rerank` model).
- **InvokeAgent** sends the version's instruction as the system prompt, with the best passages of
  its enabled knowledge bases, and the session's earlier turns, and streams the reply as `chunk`
  events (`x-amz-bedrock-agent-session-id` names the session). Ending the session with a
  `memoryId` on an agent with `SESSION_SUMMARY` memory records a summary of its turns, which
  `GetAgentMemory` returns and `DeleteAgentMemory` removes.
- **RetrieveAndGenerate** answers from the retrieved passages (knowledge base or external
  sources) and cites them with one citation spanning the answer; the stream sends `output`
  deltas and then the `citation` event.
- **Tags** on agents, aliases, and knowledge bases are served on `/tags/{resourceArn}` and
  answer `200`.

## Out of Scope

- Agent collaborators, flows, and prompts.
- Calling action group executors (Lambda or return of control).
- Embedding models and vector stores.
