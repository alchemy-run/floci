# Floci: Alchemy fork

This is [Alchemy](https://alchemy.run)'s fork of [floci-io/floci](https://github.com/floci-io/floci), a free, open-source local AWS emulator for development, testing, and CI. We patch gaps and bugs needed by Alchemy, and plan to upstream our improvements without waiting for upstream acceptance.

Floci exposes AWS-compatible APIs at `http://localhost:4566` for use with existing AWS SDKs, the AWS CLI, and infrastructure tools. This checkout combines upstream services and documentation with Alchemy's extensions and compatibility fixes.

## Documentation

- [Services overview](docs/services/index.md): supported services, APIs, and emulation limitations.
- [Quick start](docs/getting-started/quick-start.md) and [Terraform guide](docs/getting-started/terraform.md).
- [CloudFront](docs/services/cloudfront.md): local edge ports, executable CloudFront Functions, and key value stores, alongside upstream content-delivery features.
- [Lambda](docs/services/lambda.md#durable-executions): durable executions, callback/checkpoint support, and response streaming.
- [Lambda MicroVMs](docs/services/lambda-microvms.md): Docker-backed image builds and execution, authenticated endpoints, and network-connector management.
- [TLS and local certificate authority](docs/configuration/tls.md).

For the full upstream project overview, CLI and Docker quick starts, web console, architecture, SDK examples, Testcontainers integration, benchmarks, and migration guidance, see the [upstream README](https://github.com/floci-io/floci/blob/main/README.md) and [upstream documentation](https://floci.io/floci/).

Upstream releases, container images, and CLI defaults are not releases of this fork and may not include Alchemy-specific behavior. Use this checkout's service documentation when relying on fork features.
