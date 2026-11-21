# Amazon ECR Public

**Protocol:** JSON 1.1 (`X-Amz-Target: SpencerFrontendService.<Action>`)
**Endpoint:** `POST http://localhost:4566/`, signed for `ecr-public`.

Floci runs an account's public registry: its alias and catalog data, public repositories with their gallery catalog
data, logos, policies and tags, and the images pushed to them through the layer upload API.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateRepository` | Create a public repository with catalog data and tags |
| `DescribeRepositories` | Describe public repositories |
| `DeleteRepository` | Delete a repository, and with force its images |
| `GetRepositoryCatalogData` | Read a repository's gallery catalog data |
| `PutRepositoryCatalogData` | Update a repository's catalog data and logo |
| `SetRepositoryPolicy` | Set a repository policy |
| `GetRepositoryPolicy` | Read a repository policy |
| `DeleteRepositoryPolicy` | Delete a repository policy |
| `TagResource` | Tag a repository |
| `UntagResource` | Remove tags from a repository |
| `ListTagsForResource` | List a repository's tags |
| `DescribeRegistries` | Describe the account's public registry and alias |
| `GetRegistryCatalogData` | Read the registry's display name |
| `PutRegistryCatalogData` | Set the registry's display name |
| `GetAuthorizationToken` | Get a registry credential |
| `DescribeImageTags` | List a repository's tags and the images they name |
| `DescribeImages` | Describe a repository's images |
| `BatchDeleteImage` | Delete images by digest or tag |
| `PutImage` | Put an image manifest, optionally tagged |
| `BatchCheckLayerAvailability` | Check which layers are in a repository |
| `InitiateLayerUpload` | Start a layer upload |
| `UploadLayerPart` | Upload part of a layer |
| `CompleteLayerUpload` | Complete a layer upload by its digest |
<!-- floci:actions:end -->

## Registry and repositories

ECR Public is one global registry per account, which AWS serves from us-east-1 alone; floci keeps it for any
Region a request is signed for. The registry gets a random default alias the first time it is used, and its URI
and repository URIs are `public.ecr.aws/<alias>[/<repository>]`, as AWS reports them. `registryId` takes the
account ID, or the alias where the operation accepts one.

Catalog data takes architectures (`ARM`, `ARM 64`, `x86`, `x86-64`) and operating systems (`Linux`, `Windows`); a
logo is a PNG or JPEG of up to 512 KB, served from the catalog data's `logoUrl` on floci. Repository policies must
be JSON policy documents. Tag keys beginning with `aws:` are reserved, and a repository has at most 50 tags.

## Images

A public repository's images are kept in floci's ECR registry, in a repository no private ECR Region lists, so
layer uploads, manifests, tags, image deletion and `force` deletion of a repository behave as private ECR's do.
Without `force`, a repository with images cannot be deleted. Docker clients cannot push to or pull from
`public.ecr.aws` through floci, since that host resolves to AWS; images go in and out through the API.

## Configuration

```yaml
floci:
  services:
    ecr-public:
      enabled: true
```
