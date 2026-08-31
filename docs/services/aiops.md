# CloudWatch investigations (AIOps)

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the CloudWatch investigations investigation-group management lifecycle used by the AWS SDK, CLI, and Alchemy. Groups are isolated by account and region. AWS allows at most one investigation group per account per Region.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateInvestigationGroup` | `POST /investigationGroups` | Create the Region's investigation group |
| `GetInvestigationGroup` | `GET /investigationGroups/{identifier}` | Return a group by name or ARN |
| `UpdateInvestigationGroup` | `PATCH /investigationGroups/{identifier}` | Update mutable configuration |
| `DeleteInvestigationGroup` | `DELETE /investigationGroups/{identifier}` | Delete the investigation group |
| `ListInvestigationGroups` | `GET /investigationGroups` | List investigation-group summaries |
| `GetInvestigationGroupPolicy` | `GET /investigationGroups/{identifier}/policy` | Return the attached resource policy |
| `PutInvestigationGroupPolicy` | `POST /investigationGroups/{identifier}/policy` | Attach a resource policy |
| `DeleteInvestigationGroupPolicy` | `DELETE /investigationGroups/{identifier}/policy` | Remove the resource policy |
| `ListTagsForResource` | `GET /tags/{resourceArn}` | List tags on an investigation group |
| `TagResource` | `POST /tags/{resourceArn}` | Add or update tags |
| `UntagResource` | `DELETE /tags/{resourceArn}?tagKeys=` | Remove tags |

`identifier` is either the group name or its ARN (`arn:aws:aiops:<region>:<account>:investigation-group/<16-char-id>`). Creating a second group in the same Region returns `ConflictException` (HTTP 409). Reading a missing resource policy returns `ResourceNotFoundException` (HTTP 404).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_AIOPS_ENABLED` | `true` | Enable or disable CloudWatch investigations |
| `FLOCI_STORAGE_SERVICES_AIOPS_MODE` | *(inherits global)* | Optional AIOps storage-mode override |
| `FLOCI_STORAGE_SERVICES_AIOPS_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws aiops create-investigation-group \
  --name local-investigations \
  --role-arn arn:aws:iam::000000000000:role/AiopsRole \
  --retention-in-days 7

aws aiops list-investigation-groups
aws aiops get-investigation-group --identifier local-investigations
```

## Current Scope

- Investigation-group create/get/update/delete/list, resource policy, and tagging.
- Create-time tags, tag-key boundaries, CloudTrail event-history, encryption configuration, chatbot notification channels, and cross-account configurations are modeled.
- Investigation, event, and resource-timeline APIs are not implemented.
