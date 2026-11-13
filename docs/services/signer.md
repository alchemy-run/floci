# Signer

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/signing-profiles` and the rest, signed for `signer`

Signer signs code and container images with keys that AWS manages.

## Profiles

Each signing profile version gets its own ECDSA P-384 key and self-signed certificate. A profile name can't be
reused, even once the profile is canceled. Canceled profiles are hidden unless `includeCanceled` is set. Revoking a
profile version invalidates the signatures it made from its effective time on. Permissions are revisioned: a change
that names an old `revisionId` is a `ConflictException`.

## Signing jobs

`StartSigningJob` signs a .zip for the `AWSLambda-SHA384-ECDSA` platform:

- It reads a version of an object from a bucket with versioning enabled.
- It writes the signed archive to `<prefix><jobId>.zip` in the destination bucket.

The signature lives in the archive's comment: SHA384withECDSA over the archive, with the certificate and the job and
profile version ARNs. Zip readers ignore it, so the signed package unpacks and runs as before. Job status changes go
to the default event bus as `Signer Job Status Change`.

`SignPayload` signs a Notation payload with a `Notation-OCI-SHA384-ECDSA` profile. It returns a JWS
(`application/jose+json`) whose ES384 signature verifies against the certificate in its `x5c` header.

## Lambda code signing

A Lambda function with a code signing configuration checks every package it deploys. The signature must:

- come from one of the configuration's allowed profile versions
- match the code
- come from a job that isn't revoked
- not be past its validity period
- predate any revocation of its profile version

Otherwise the deploy fails with `CodeVerificationFailedException` under the `Enforce` policy, or is logged under
`Warn`. `GetRevocationStatus` reports which of a signature's job, profile version and certificates are revoked.

The IoT platforms are listed, and profiles for them need an ACM certificate, but Floci only signs for Lambda and
Notation.

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ListSigningPlatforms` | List the signing platforms |
| `GetSigningPlatform` | Describe a platform |
| `PutSigningProfile` | Create a profile with its own key and certificate |
| `GetSigningProfile` | Describe a profile |
| `ListSigningProfiles` | List profiles, by status or platform |
| `CancelSigningProfile` | Cancel a profile |
| `RevokeSigningProfile` | Revoke a profile version's signatures from a time on |
| `AddProfilePermission` | Grant an account a Signer action on the profile |
| `RemoveProfilePermission` | Remove a permission |
| `ListProfilePermissions` | List a profile's permissions |
| `StartSigningJob` | Sign a .zip from S3 into S3 |
| `DescribeSigningJob` | Describe a signing job |
| `ListSigningJobs` | List signing jobs |
| `RevokeSignature` | Revoke a job's signature |
| `GetRevocationStatus` | Check a signature's job, profile version and certificates |
| `SignPayload` | Sign a Notation payload into a JWS |
| `TagResource` | Tag a profile |
| `UntagResource` | Remove tags |
| `ListTagsForResource` | List a profile's tags |
<!-- floci:actions:end -->
