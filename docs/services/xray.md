# AWS X-Ray

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the X-Ray restJson1 APIs used by Alchemy resources (groups, sampling rules, resource policies) and Lambda bindings (trace ingest, summaries, sampling, graphs, insights, Transaction Search stubs). Requests signed for `xray` are rewritten onto `/xray/...` so shared paths such as `/TagResource` do not collide.

The built-in `Default` sampling rule and `Default` group are seeded per account and region. Lambda invocations with `TracingConfig.Mode=Active` record a function-level segment named after the function so `GetTraceSummaries` with `service("functionName")` can find it.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_XRAY_ENABLED` | `true` | Enable or disable X-Ray |
| `FLOCI_STORAGE_SERVICES_XRAY_MODE` | *(inherits global)* | Optional X-Ray storage-mode override |
| `FLOCI_STORAGE_SERVICES_XRAY_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |
