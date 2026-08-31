# Route 53 Resolver

Route 53 Resolver management-plane emulation over AWS JSON 1.1 (`Route53Resolver.*`). Endpoints become `OPERATIONAL` immediately so local reconcilers do not wait on the live ENI attach window.

## Supported Operations

| Operation | Protocol |
|---|---|
| CreateResolverEndpoint | JSON 1.1 |
| GetResolverEndpoint | JSON 1.1 |
| ListResolverEndpoints | JSON 1.1 |
| ListResolverEndpointIpAddresses | JSON 1.1 |
| UpdateResolverEndpoint | JSON 1.1 |
| DeleteResolverEndpoint | JSON 1.1 |
| TagResource | JSON 1.1 |
| UntagResource | JSON 1.1 |
| ListTagsForResource | JSON 1.1 |
