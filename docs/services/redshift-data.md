# Redshift Data API

**Protocol:** JSON 1.1
**Management Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: RedshiftData.*`

Floci emulates the Amazon Redshift Data API against Serverless workgroups and provisioned clusters. Statements finish immediately against an in-memory catalog: `SELECT <n> AS <alias>` returns a typed `longValue` row, metadata APIs serve canned `pg_catalog` objects (`pg_class`, `pg_attribute`, `pg_namespace`), and unknown workgroups / statement ids raise AWS-typed `ValidationException` / `ResourceNotFoundException`.

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `FLOCI_SERVICES_REDSHIFT_DATA_ENABLED` | `true` | Enable or disable the Redshift Data API |
