# AWS Private Certificate Authority

**Protocol:** JSON 1.1
**Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: ACMPrivateCA.<Operation>`, signed for `acm-pca`

Floci runs private certificate authorities with real keys. A CA has its own key pair and CSR, and certificates it issues are X.509 certificates signed with that key, built from AWS's certificate templates. Revocations are published in CRLs in S3, audit reports are written to S3, and ACM's private certificates are signed by the CA they name.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateCertificateAuthority` | Create a CA with a new key pair and CSR |
| `DescribeCertificateAuthority` | Describe a CA |
| `ListCertificateAuthorities` | Page through your CAs, or those shared with you |
| `UpdateCertificateAuthority` | Enable or disable a CA, or change its revocation configuration |
| `DeleteCertificateAuthority` | Delete a CA into its restoration window |
| `RestoreCertificateAuthority` | Restore a deleted CA |
| `GetCertificateAuthorityCsr` | Return the CA's CSR |
| `GetCertificateAuthorityCertificate` | Return the CA's certificate and chain |
| `ImportCertificateAuthorityCertificate` | Install the CA's certificate, making it ACTIVE |
| `IssueCertificate` | Sign a CSR with a template |
| `GetCertificate` | Return an issued certificate and its chain |
| `RevokeCertificate` | Revoke a certificate and republish the CRL |
| `CreateCertificateAuthorityAuditReport` | Write an audit report to S3 |
| `DescribeCertificateAuthorityAuditReport` | Describe an audit report |
| `CreatePermission` | Let ACM renew certificates with the CA |
| `DeletePermission` | Remove ACM's permission |
| `ListPermissions` | List the CA's permissions |
| `PutPolicy` | Attach a resource policy, sharing the CA |
| `GetPolicy` | Return the CA's resource policy |
| `DeletePolicy` | Remove the CA's resource policy |
| `TagCertificateAuthority` | Tag a CA |
| `UntagCertificateAuthority` | Remove tags from a CA |
| `ListTags` | List a CA's tags |
<!-- floci:actions:end -->

## Creating and activating a CA

`CreateCertificateAuthority` generates the CA's key (`RSA_2048`, `RSA_3072`, `RSA_4096`, `EC_prime256v1`, `EC_secp384r1`, `EC_secp521r1` or `SM2`) and a CSR for its subject, signed with its signing algorithm, which must belong to the key's family. The CA is `CREATING` for a second, then `PENDING_CERTIFICATE`. `IdempotencyToken` returns the same CA for five minutes. `UsageMode` is `GENERAL_PURPOSE` by default.

A CA becomes `ACTIVE` when `ImportCertificateAuthorityCertificate` installs a certificate for its key:

- A root CA issues its own certificate from its CSR with the `RootCACertificate/V1` template, while still `PENDING_CERTIFICATE`, and imports it. The certificate must be self-signed and takes no chain.
- A subordinate CA's certificate is signed by its parent (for example with `SubordinateCACertificate_PathLen0/V1`) and imported with the chain up to a root. Each certificate in the chain must sign the one before it.

A certificate for another key is `CertificateMismatchException`. Importing again replaces the certificate, as a CA renewal does.

`UpdateCertificateAuthority` moves an active CA to `DISABLED` and back, and changes its revocation configuration. `DeleteCertificateAuthority` needs a CA that isn't `ACTIVE`; the CA is `DELETED` for its permanent deletion window (7 to 30 days, default 30), during which `RestoreCertificateAuthority` brings it back `DISABLED` (or `PENDING_CERTIFICATE` if it had no certificate). After the window it is gone. A CA past its certificate's expiry is `EXPIRED`.

## Issuing certificates

`IssueCertificate` signs the CSR it is given. The template decides the certificate's basic constraints, key usage and extended key usage:

| Template | Certificate |
| --- | --- |
| `EndEntityCertificate/V1` (default) | Server and client authentication |
| `EndEntityServerAuthCertificate/V1`, `EndEntityClientAuthCertificate/V1` | Server or client authentication |
| `CodeSigningCertificate/V1`, `OCSPSigningCertificate/V1` | Code or OCSP signing |
| `RootCACertificate/V1` | The root CA's own certificate |
| `SubordinateCACertificate_PathLen0/V1` … `_PathLen3/V1` | A subordinate CA, with that path length |

The `_APIPassthrough`, `_CSRPassthrough` and `_APICSRPassthrough` variants, and the `Blank…` templates, also take extensions from `ApiPassthrough` (key usage, extended key usage, subject alternative names, certificate policies, custom extensions, and a replacement subject) or from the CSR. Subject alternative names in the CSR are copied to end-entity certificates. Every certificate gets subject and authority key identifiers, and the CRL distribution point and OCSP URL when they are enabled.

The validity (`DAYS`, `MONTHS`, `YEARS`, `ABSOLUTE` or `END_DATE`) can't outlast the CA's own certificate. A CA in `SHORT_LIVED_CERTIFICATE` mode issues end-entity certificates for at most 7 days. `NotBefore` is an hour before issuance unless `ValidityNotBefore` says otherwise. Only an `ACTIVE` CA issues certificates (except the root's own). The certificate's ARN is the CA's ARN with `/certificate/<serial in hex>`, and `GetCertificate` returns it with the chain of the CA's certificate and its parents.

## Revocation and CRLs

`RevokeCertificate` takes the serial in hex, with or without colons. A certificate revoked twice is `RequestAlreadyProcessedException`, and a serial the CA never issued is `ResourceNotFoundException`. With a CRL configured, Floci writes the CA's CRL to `s3://<S3BucketName>/<CustomPath or crl>/<ca-id>.crl` when the CA is activated, on each revocation and when the configuration changes; the bucket must exist. OCSP URLs are written into certificates, but Floci runs no OCSP responder.

## Audit reports

`CreateCertificateAuthorityAuditReport` writes a JSON or CSV report of every certificate the CA issued, with its revocation, to `audit-report/<ca-id>/<report-id>.json` (or `.csv`) in the bucket. The report is `CREATING` for a second, then `SUCCESS`.

## Permissions, policies and sharing

`CreatePermission` lets ACM (`acm.amazonaws.com`) renew certificates; it needs all three actions, `IssueCertificate`, `GetCertificate` and `ListPermissions`. A resource policy (`PutPolicy`) that names another account as a principal shares the CA with it: that account lists it with `ListCertificateAuthorities` `ResourceOwner=OTHER_ACCOUNTS` and can use it by ARN.

## ACM private certificates

`RequestCertificate` with a `CertificateAuthorityArn` has that CA sign a new key's certificate, valid for 13 months (7 days for a short-lived CA), with the CA's chain. `RenewCertificate` signs a new one. A CA that isn't `ACTIVE` or doesn't exist fails the certificate with `FailureReason` `PCA_INVALID_STATE`, `PCA_RESOURCE_NOT_FOUND` or `PCA_INVALID_ARN`.

## Events

Issuance, revocation, CRL generation and audit reports are published to the default EventBridge bus as `aws.acm-pca` events (`ACM Private CA Certificate Issuance`, `ACM Private CA Certificate Revocation`, `ACM Private CA CRL Generation`, `ACM Private CA Audit Report Generation`), with `result` in the detail.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_ACM_PCA_ENABLED` | `true` | Enable or disable AWS Private CA |

For the upstream API shape, see the [AWS Private CA API Reference](https://docs.aws.amazon.com/privateca/latest/APIReference/Welcome.html).
