# Directory Service

**Protocol:** JSON 1.1 (`X-Amz-Target: DirectoryService_20150416.*`)
**Management Endpoint:** `POST http://localhost:4566/`

AWS Directory Service managed directories (Simple AD and Microsoft AD), tags, conditional forwarders, and event topics. Directories become `Active` immediately. There is no Samba / Microsoft AD process.

## Supported Management Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `GetDirectoryLimits` | Read account directory quotas for the region |
| `DescribeDirectories` | List directories; unknown ids return `EntityDoesNotExistException` |
| `CreateDirectory` | Create a Simple AD directory |
| `CreateMicrosoftAD` | Create an AWS Managed Microsoft AD directory |
| `DeleteDirectory` | Delete a directory |
| `ListTagsForResource` | List tags on a directory |
| `AddTagsToResource` | Add tags to a directory |
| `RemoveTagsFromResource` | Remove tags from a directory |
| `DescribeConditionalForwarders` | List conditional forwarders |
| `CreateConditionalForwarder` | Create a conditional forwarder |
| `UpdateConditionalForwarder` | Update a conditional forwarder's DNS IPs |
| `DeleteConditionalForwarder` | Delete a conditional forwarder |
| `DescribeEventTopics` | List SNS event-topic associations |
| `RegisterEventTopic` | Associate an SNS topic |
| `DeregisterEventTopic` | Disassociate an SNS topic |
<!-- floci:actions:end -->

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_DS_ENABLED` | `true` | Enable or disable the service |
