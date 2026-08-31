# Bedrock Agents

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the Amazon Bedrock Agents build-time API used by the AWS SDK, CLI, and Alchemy: agent and alias lifecycle, prepare, and tags. There is no foundation-model inference; `PrepareAgent` marks the DRAFT version `PREPARED` immediately.

Literal `/agents` paths take JAX-RS precedence over S3's `/{bucket}` catch-all. Tag APIs share `/tags/{resourceArn}` and dispatch from the ARN service segment `bedrock`.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateAgent` | `PUT /agents/` | Create an agent in `NOT_PREPARED` |
| `GetAgent` | `GET /agents/{agentId}/` | Return an agent |
| `ListAgents` | `POST /agents/` | List agent summaries |
| `UpdateAgent` | `PUT /agents/{agentId}/` | Update instruction, model, description, and related fields |
| `PrepareAgent` | `POST /agents/{agentId}/` | Mark the DRAFT version `PREPARED` |
| `DeleteAgent` | `DELETE /agents/{agentId}/` | Delete an agent and its aliases |
| `CreateAgentAlias` | `PUT /agents/{agentId}/agentaliases/` | Create an alias (omitted routing snapshots a numbered version) |
| `GetAgentAlias` | `GET /agents/{agentId}/agentaliases/{agentAliasId}/` | Return an alias |
| `ListAgentAliases` | `POST /agents/{agentId}/agentaliases/` | List alias summaries |
| `UpdateAgentAlias` | `PUT /agents/{agentId}/agentaliases/{agentAliasId}/` | Update alias name, description, or routing |
| `DeleteAgentAlias` | `DELETE /agents/{agentId}/agentaliases/{agentAliasId}/` | Delete an alias |
| `ListTagsForResource` | `GET /tags/{resourceArn}` | List tags on an agent or alias |
| `TagResource` | `POST /tags/{resourceArn}` | Add or update tags |
| `UntagResource` | `DELETE /tags/{resourceArn}?tagKeys=` | Remove tags |

A missing agent or alias returns `ResourceNotFoundException` (HTTP 404). Reusing an agent or alias name in the same account and Region returns `ConflictException` (HTTP 409).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_BEDROCK_AGENT_ENABLED` | `true` | Enable or disable Bedrock Agents |
| `FLOCI_STORAGE_SERVICES_BEDROCK_AGENT_MODE` | *(inherits global)* | Optional Bedrock Agents storage-mode override |
| `FLOCI_STORAGE_SERVICES_BEDROCK_AGENT_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |
