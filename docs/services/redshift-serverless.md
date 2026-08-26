# Redshift Serverless

**Protocol:** JSON 1.1
**Management Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: RedshiftServerless.*`

Floci emulates Amazon Redshift Serverless namespaces, workgroups, and snapshots as a control-plane service. Resources become `AVAILABLE` immediately and workgroups report a `*.redshift-serverless.*` endpoint on port `5439`. No warehouse container is started. `manageAdminPassword` stores a generated secret in Secrets Manager. `GetSnapshot` / `DeleteSnapshot` on a missing snapshot return `ResourceNotFoundException`.

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `FLOCI_SERVICES_REDSHIFT_SERVERLESS_ENABLED` | `true` | Enable or disable Redshift Serverless |
