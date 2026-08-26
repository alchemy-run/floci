# Redshift Data API

**Protocol:** JSON 1.1
**Management Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: RedshiftData.*`

Floci emulates the Amazon Redshift Data API against Serverless workgroups and provisioned clusters. Literal `SELECT <n> AS <alias>` statements finish immediately with a typed `longValue` row. `SELECT count(*)` scans stay `RUNNING` so `CancelStatement` can succeed (AWS ExecuteStatement is async); cancel of a finished statement still returns `ValidationException`. Metadata APIs serve canned `pg_catalog` objects (`pg_class`, `pg_attribute`, `pg_namespace`), and unknown workgroups / statement ids raise AWS-typed `ValidationException` / `ResourceNotFoundException`.

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `FLOCI_SERVICES_REDSHIFT_DATA_ENABLED` | `true` | Enable or disable the Redshift Data API |
